package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;

import com.lecture.rag.day3.agent.AgentEvent;
import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * RAG 한 턴의 공통 흐름을 담은 베이스 클래스. 서브클래스는 필요한 훅만 오버라이드하면 된다.
 *
 * <pre>
 *  (1) 질문 분석/재작성   rewriteQueries()   [기본: 미구현 → 원문 질문 그대로]
 *  (2) 벡터 검색          기본 제공
 *      + 키워드 검색      keywordSearch()    [기본: 미구현]
 *  (3) 재정렬             rerank()           [기본: 미구현 → 유사도 순 상위 topK]
 *  (4) 프롬프트 조립 + 스트리밍 생성  기본 제공
 *  (5) 자기 검증          selfCheck()        [기본: 미구현]
 * </pre>
 *
 * <p>훅이 {@code Optional.empty()}를 돌려주면 "아직 구현 안 됨"으로 간주해서, 해당 기능 토글이 켜져 있을 때
 * 화면에 점선 카드로 "여기가 네가 채울 자리다"를 띄운다({@link AgentEvent#stepTodo}).
 *
 * <p>각 단계는 {@code Flux.concat(시작 이벤트, Flux.defer(실제 작업))} 형태로 이어붙인다. 이렇게 하면
 * 블로킹 작업(검색·LLM 호출)이 시작되기 <b>전에</b> "실행 중" 이벤트가 먼저 프론트에 도착해서,
 * 화면에서 단계가 순서대로 켜지는 게 보인다.
 */
public abstract class AbstractRagPipeline implements RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(AbstractRagPipeline.class);

    protected static final String STEP_ANALYZE = "analyze";
    protected static final String STEP_RETRIEVE = "retrieve";
    protected static final String STEP_KEYWORD = "keyword";
    protected static final String STEP_RERANK = "rerank";
    protected static final String STEP_GENERATE = "generate";
    protected static final String STEP_VERIFY = "verify";

    protected final KnowledgeBase knowledgeBase;
    protected final ChatModel chatModel;

    protected AbstractRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        this.knowledgeBase = knowledgeBase;
        this.chatModel = chatModel;
    }

    // ------------------------------------------------------------------ 훅

    /**
     * (1) 질문 재작성 — 짧고 모호한 질문("그건 얼마야?")을 검색에 쓸 수 있는 완전한 질문으로 다시 쓴다.
     * 여러 개를 돌려주면 각각 검색해서 결과를 합친다(multi-query retrieval).
     *
     * @return 검색에 쓸 쿼리 목록. {@code Optional.empty()} = 미구현
     */
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
            RagOptions options) {
        return Optional.empty();
    }

    /**
     * (2-b) 키워드 검색 — 벡터 검색이 놓치는 정확한 고유명사/조항 번호를 잡기 위한 검색.
     * {@code knowledgeBase.allChunks()}로 전체 청크를 받아서 직접 점수를 매기면 된다.
     *
     * @return 키워드 검색으로 찾은 청크. {@code Optional.empty()} = 미구현
     */
    protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {
        return Optional.empty();
    }

    /**
     * (3) 재정렬 — 넓게 가져온 후보를 다시 채점해서 진짜 관련 있는 것만 상위로 올린다.
     *
     * @return 재정렬된 청크(앞쪽이 더 관련 있음). {@code Optional.empty()} = 미구현
     */
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
        return Optional.empty();
    }

    /**
     * (5) 자기 검증 — 생성된 답변이 근거에 실제로 있는 내용인지 스스로 확인한다.
     *
     * @return 사용자에게 보여줄 검증 결과 한 줄. {@code Optional.empty()} = 미구현
     */
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
            RagOptions options) {
        return Optional.empty();
    }

    // ------------------------------------------------------------------ 편의 도구

    /** 훅 구현에서 LLM을 부를 때 쓰는 ChatClient. */
    protected ChatClient chatClient() {
        return ChatClient.builder(this.chatModel).build();
    }

    /** 이 파이프라인이 실제로 반응하는 토글인지 (선언하지 않은 기능은 무시한다). */
    protected boolean active(RagOptions options, String feature) {
        return options.enabled(feature) && supportedFeatures().contains(feature);
    }

    @Override
    public Flux<AgentEvent> run(ChatRequest request) {
        return new Execution(request).stream();
    }

    // ------------------------------------------------------------------ 실행

    private final class Execution {

        private final ChatRequest request;
        private final RagOptions options;
        private final String question;
        private final long startedAt = System.currentTimeMillis();

        private List<String> queries;
        private List<Document> candidates = List.of();
        private List<Document> selected = List.of();
        private List<SourceRef> sources = List.of();
        private final StringBuilder answer = new StringBuilder();

        private long retrieveMs;
        private long generateMs;
        private int contextChars;
        private Usage usage;

        private Execution(ChatRequest request) {
            this.request = request;
            this.options = request.optionsOrDefault();
            this.question = request.questionOrEmpty();
            this.queries = List.of(this.question);
        }

        Flux<AgentEvent> stream() {
            if (AbstractRagPipeline.this.knowledgeBase.isEmpty()) {
                return Flux.just(
                        AgentEvent.notice("warn", "지식 베이스가 비어 있습니다. 왼쪽 패널에서 문서를 먼저 업로드하세요."),
                        AgentEvent.token("아직 인덱싱된 문서가 없어서 답변할 근거가 없습니다. 왼쪽 '지식 베이스' 패널에서 PDF나 텍스트 파일을 업로드해 주세요."),
                        AgentEvent.done(0));
            }
            return Flux.concat(
                    analyzePhase(),
                    retrievePhase(),
                    rerankPhase(),
                    generatePhase(),
                    verifyPhase(),
                    Flux.defer(this::finishPhase))
                    .onErrorResume(this::toErrorEvents)
                    // 검색·LLM 호출은 블로킹이라 서블릿 요청 스레드를 붙잡지 않도록 별도 스레드에서 실행
                    .subscribeOn(Schedulers.boundedElastic());
        }

        // (1) 질문 분석/재작성 --------------------------------------------------
        private Flux<AgentEvent> analyzePhase() {
            if (!active(this.options, RagOptions.FEATURE_REWRITE)) {
                return Flux.empty();
            }
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_ANALYZE, "질문 재작성")),
                    Flux.defer(() -> {
                        long t0 = System.currentTimeMillis();
                        Optional<List<String>> rewritten =
                                rewriteQueries(this.question, this.request.historyOrEmpty(), this.options);
                        if (rewritten.isEmpty() || rewritten.get().isEmpty()) {
                            return Flux.just(AgentEvent.stepTodo(STEP_ANALYZE, "질문 재작성",
                                    "질문을 검색용으로 다시 쓰는 로직을 구현하세요. 여러 개를 돌려주면 multi-query 검색이 됩니다.",
                                    "StudentRagPipeline#rewriteQueries"));
                        }
                        this.queries = rewritten.get();
                        return Flux.just(AgentEvent.stepDone(STEP_ANALYZE, "질문 재작성",
                                System.currentTimeMillis() - t0,
                                String.join(" / ", this.queries)));
                    }));
        }

        // (2) 검색 -------------------------------------------------------------
        private Flux<AgentEvent> retrievePhase() {
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_RETRIEVE, "벡터 검색")),
                    Flux.defer(() -> Flux.fromIterable(doRetrieve())));
        }

        private List<AgentEvent> doRetrieve() {
            List<AgentEvent> events = new ArrayList<>();
            long t0 = System.currentTimeMillis();

            int topK = this.options.topKOrDefault();
            double threshold = this.options.similarityThresholdOrDefault();
            // 재정렬을 켰으면 일부러 넓게(2배) 가져와서 재정렬할 여지를 준다 — Day2에서 배운 wide retrieval
            int searchTopK = active(this.options, RagOptions.FEATURE_RERANK) ? topK * 2 : topK;

            List<List<Document>> perQuery = new ArrayList<>();
            for (String query : this.queries) {
                perQuery.add(AbstractRagPipeline.this.knowledgeBase.search(
                        query, searchTopK, threshold, this.request.docIdsOrEmpty()));
            }
            this.candidates = RagPrompts.mergeDistinct(perQuery);
            this.retrieveMs = System.currentTimeMillis() - t0;

            String detail = this.candidates.size() + "개 청크"
                    + (this.queries.size() > 1 ? " (쿼리 " + this.queries.size() + "개 병합)" : "")
                    + " · topK=" + searchTopK + (threshold > 0 ? ", 임계값 " + threshold : "");
            events.add(AgentEvent.stepDone(STEP_RETRIEVE, "벡터 검색", this.retrieveMs, detail));

            if (this.candidates.isEmpty()) {
                events.add(AgentEvent.notice("warn",
                        "유사도 임계값(" + threshold + ") 이상인 청크가 없습니다. 설정에서 임계값을 낮추거나 질문을 바꿔보세요."));
            }

            // 키워드(하이브리드) 검색 훅
            if (active(this.options, RagOptions.FEATURE_KEYWORD)) {
                long k0 = System.currentTimeMillis();
                Optional<List<Document>> keywordHits =
                        keywordSearch(this.question, this.request.docIdsOrEmpty(), this.options);
                if (keywordHits.isEmpty()) {
                    events.add(AgentEvent.stepTodo(STEP_KEYWORD, "키워드 검색 (하이브리드)",
                            "knowledgeBase.allChunks()로 전체 청크를 받아 질문의 단어가 몇 번 나오는지 세어보세요. 벡터 검색이 놓친 고유명사를 잡을 수 있습니다.",
                            "StudentRagPipeline#keywordSearch"));
                }
                else {
                    int before = this.candidates.size();
                    this.candidates = RagPrompts.mergeDistinct(List.of(this.candidates, keywordHits.get()));
                    events.add(AgentEvent.stepDone(STEP_KEYWORD, "키워드 검색 (하이브리드)",
                            System.currentTimeMillis() - k0,
                            "키워드로 " + keywordHits.get().size() + "개 추가 → 후보 " + before + "개에서 "
                                    + this.candidates.size() + "개로"));
                }
            }
            return events;
        }

        // (3) 재정렬 -----------------------------------------------------------
        private Flux<AgentEvent> rerankPhase() {
            if (!active(this.options, RagOptions.FEATURE_RERANK)) {
                return Flux.defer(() -> Flux.fromIterable(finalizeSources(this.candidates)));
            }
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_RERANK, "재정렬 (rerank)")),
                    Flux.defer(() -> {
                        long t0 = System.currentTimeMillis();
                        Optional<List<Document>> reranked = rerank(this.question, this.candidates, this.options);
                        List<AgentEvent> events = new ArrayList<>();
                        if (reranked.isEmpty()) {
                            events.add(AgentEvent.stepTodo(STEP_RERANK, "재정렬 (rerank)",
                                    "후보를 LLM으로 0~10점 채점해서 상위 topK개만 남기세요. Day2 Lab2.2의 LlmReranker가 그대로 쓸 수 있는 예시입니다.",
                                    "StudentRagPipeline#rerank"));
                            events.addAll(finalizeSources(this.candidates));
                        }
                        else {
                            events.add(AgentEvent.stepDone(STEP_RERANK, "재정렬 (rerank)",
                                    System.currentTimeMillis() - t0,
                                    "후보 " + this.candidates.size() + "개 → 상위 "
                                            + Math.min(reranked.get().size(), this.options.topKOrDefault()) + "개 채택"));
                            events.addAll(finalizeSources(reranked.get()));
                        }
                        return Flux.fromIterable(events);
                    }));
        }

        /** 최종 근거 확정 + 프론트로 전송. */
        private List<AgentEvent> finalizeSources(List<Document> documents) {
            this.selected = documents.size() > this.options.topKOrDefault()
                    ? documents.subList(0, this.options.topKOrDefault())
                    : documents;
            this.sources = RagPrompts.toSources(this.selected);
            return List.of(
                    AgentEvent.sources(this.sources),
                    AgentEvent.metric("chunks", "사용한 청크", this.sources.size()));
        }

        // (4) 생성 -------------------------------------------------------------
        private Flux<AgentEvent> generatePhase() {
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_GENERATE, "LLM 답변 생성")),
                    Flux.defer(this::doGenerate));
        }

        private Flux<AgentEvent> doGenerate() {
            long t0 = System.currentTimeMillis();
            String context = RagPrompts.formatContext(this.sources);
            this.contextChars = context.length();

            String systemPrompt = this.options.systemPrompt() == null || this.options.systemPrompt().isBlank()
                    ? RagPrompts.DEFAULT_SYSTEM_PROMPT
                    : this.options.systemPrompt();
            List<Message> history = RagPrompts.historyMessages(
                    this.request.historyOrEmpty(), this.options.maxHistoryOrDefault());

            Flux<AgentEvent> tokens = ChatClient.builder(AbstractRagPipeline.this.chatModel).build()
                    .prompt()
                    .options(ChatOptions.builder().temperature(this.options.temperatureOrDefault()))
                    .system(systemPrompt)
                    .messages(history)
                    .user(RagPrompts.formatUserMessage(this.question, context))
                    .stream()
                    .chatResponse()
                    .mapNotNull(this::toTokenEvent);

            return Flux.concat(
                    Flux.just(AgentEvent.metric("contextChars", "컨텍스트 길이", this.contextChars)),
                    tokens,
                    Flux.defer(() -> {
                        this.generateMs = System.currentTimeMillis() - t0;
                        return Flux.just(AgentEvent.stepDone(STEP_GENERATE, "LLM 답변 생성", this.generateMs,
                                this.answer.length() + "자 생성"));
                    }));
        }

        /** 스트리밍 응답 조각 하나를 token 이벤트로. 본문이 없는 조각(마지막 usage 청크 등)은 버린다. */
        private AgentEvent toTokenEvent(ChatResponse response) {
            if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Usage current = response.getMetadata().getUsage();
                if (current.getTotalTokens() != null && current.getTotalTokens() > 0) {
                    this.usage = current;
                }
            }
            String text = response.getResult() == null || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
            if (text == null || text.isEmpty()) {
                return null;
            }
            this.answer.append(text);
            return AgentEvent.token(text);
        }

        // (5) 자기 검증 --------------------------------------------------------
        private Flux<AgentEvent> verifyPhase() {
            if (!active(this.options, RagOptions.FEATURE_SELF_CHECK)) {
                return Flux.empty();
            }
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_VERIFY, "자기 검증")),
                    Flux.defer(() -> {
                        long t0 = System.currentTimeMillis();
                        Optional<String> verdict =
                                selfCheck(this.question, this.answer.toString(), this.sources, this.options);
                        if (verdict.isEmpty()) {
                            return Flux.just(AgentEvent.stepTodo(STEP_VERIFY, "자기 검증",
                                    "답변의 각 문장이 근거 청크에 실제로 있는지 LLM에게 물어보고, 없으면 경고를 띄우세요(Self-RAG의 축소판).",
                                    "StudentRagPipeline#selfCheck"));
                        }
                        return Flux.just(
                                AgentEvent.stepDone(STEP_VERIFY, "자기 검증",
                                        System.currentTimeMillis() - t0, verdict.get()),
                                AgentEvent.notice("info", "자기 검증: " + verdict.get()));
                    }));
        }

        // 마무리 ---------------------------------------------------------------
        private Flux<AgentEvent> finishPhase() {
            long totalMs = System.currentTimeMillis() - this.startedAt;
            List<AgentEvent> events = new ArrayList<>();
            events.add(AgentEvent.metric("retrieveMs", "검색 시간(ms)", this.retrieveMs));
            events.add(AgentEvent.metric("generateMs", "생성 시간(ms)", this.generateMs));
            events.add(AgentEvent.metric("answerChars", "답변 길이", this.answer.length()));
            if (this.usage != null) {
                events.add(AgentEvent.metric("promptTokens", "프롬프트 토큰", this.usage.getPromptTokens()));
                events.add(AgentEvent.metric("completionTokens", "생성 토큰", this.usage.getCompletionTokens()));
                if (this.generateMs > 0 && this.usage.getCompletionTokens() != null) {
                    events.add(AgentEvent.metric("tokensPerSecond", "생성 속도(tok/s)",
                            Math.round(this.usage.getCompletionTokens() * 1000.0 / this.generateMs * 10) / 10.0));
                }
            }
            events.add(AgentEvent.done(totalMs));
            return Flux.fromIterable(events);
        }

        private Flux<AgentEvent> toErrorEvents(Throwable throwable) {
            log.error("파이프라인 [{}] 실행 실패", id(), throwable);
            String message = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            return Flux.just(
                    AgentEvent.stepError(STEP_GENERATE, "실행 실패", message),
                    AgentEvent.error(message));
        }
    }
}

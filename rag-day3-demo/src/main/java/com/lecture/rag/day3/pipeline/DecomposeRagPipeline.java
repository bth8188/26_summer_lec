package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.agent.AgentEvent;
import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 질의 분해 파이프라인. {@link AbstractRagPipeline}을 상속하지 않고 {@link RagPipeline}을 직접 구현합니다.
 *
 * <p>"근로장학금은 시간당 얼마이고 월 최대 근로시간은 몇 시간인가요?" 처럼 한 문장에 질문이 두 개
 * 들어 있으면, 임베딩이 두 주제 사이 어중간한 지점을 가리켜 어느 쪽 근거도 제대로 못 잡습니다.
 * 하위 질문으로 쪼개 따로 검색한 뒤 근거를 합쳐서 한 번에 답합니다.
 */
@Component
public class DecomposeRagPipeline implements RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(DecomposeRagPipeline.class);

    /** 하위 질문 수 상한입니다. 하나당 검색이 한 번씩 더 나갑니다. */
    private static final int MAX_SUB_QUESTIONS = 3;

    private final KnowledgeBase knowledgeBase;
    private final ChatModel chatModel;

    public DecomposeRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        this.knowledgeBase = knowledgeBase;
        this.chatModel = chatModel;
    }

    @Override
    public String id() {
        return "decompose";
    }

    @Override
    public String name() {
        return "질의 분해";
    }

    @Override
    public String tier() {
        return "custom";
    }

    @Override
    public String description() {
        return "질문을 하위 질문으로 쪼개 각각 검색한 뒤 근거를 합쳐 답한다. 한 문장에 질문이 여러 개일 때 쓴다.";
    }

    @Override
    public Flux<AgentEvent> run(ChatRequest request) {
        RagOptions options = request.optionsOrDefault();
        String question = request.questionOrEmpty();

        if (this.knowledgeBase.isEmpty()) {
            return Flux.just(
                    AgentEvent.notice("warn", "지식 베이스가 비어 있습니다. 왼쪽 패널에서 문서를 먼저 업로드하세요."),
                    AgentEvent.token("아직 인덱싱된 문서가 없어서 답변할 근거가 없습니다."),
                    AgentEvent.done(0));
        }

        long startedAt = System.currentTimeMillis();
        List<String> subQuestions = new ArrayList<>();
        List<SourceRef> sources = new ArrayList<>();
        StringBuilder answer = new StringBuilder();

        Flux<AgentEvent> decompose = Flux.concat(
                Flux.just(AgentEvent.stepStart("decompose", "질의 분해")),
                Flux.defer(() -> {
                    long t0 = System.currentTimeMillis();
                    subQuestions.addAll(decompose(question));
                    return Flux.just(AgentEvent.stepDone("decompose", "질의 분해",
                            System.currentTimeMillis() - t0,
                            subQuestions.size() + "개로 분해 · " + String.join(" | ", subQuestions)));
                }));

        Flux<AgentEvent> retrieve = Flux.concat(
                Flux.just(AgentEvent.stepStart("retrieve", "하위 질문별 검색")),
                Flux.defer(() -> {
                    long t0 = System.currentTimeMillis();
                    List<AgentEvent> events = new ArrayList<>();
                    List<List<Document>> perQuestion = new ArrayList<>();
                    for (String sub : subQuestions) {
                        List<Document> hits = this.knowledgeBase.search(sub, options.topKOrDefault(),
                                options.similarityThresholdOrDefault(), request.docIdsOrEmpty());
                        perQuestion.add(hits);
                        events.add(subQueryEvent(sub, hits));
                    }
                    List<Document> merged = RagPrompts.mergeDistinct(perQuestion);
                    if (merged.size() > options.topKOrDefault()) {
                        merged = merged.subList(0, options.topKOrDefault());
                    }
                    sources.addAll(RagPrompts.toSources(merged));
                    events.add(AgentEvent.stepDone("retrieve", "하위 질문별 검색",
                            System.currentTimeMillis() - t0,
                            "질문 " + subQuestions.size() + "개 → 청크 " + sources.size() + "개"));
                    events.add(AgentEvent.sources(sources));
                    events.add(AgentEvent.metric("chunks", "사용한 청크", sources.size()));
                    return Flux.fromIterable(events);
                }));

        Flux<AgentEvent> generate = Flux.concat(
                Flux.just(AgentEvent.stepStart("generate", "LLM 답변 생성")),
                Flux.defer(() -> {
                    long t0 = System.currentTimeMillis();
                    return Flux.concat(
                            ChatClient.builder(this.chatModel).build()
                                    .prompt()
                                    .options(ChatOptions.builder().temperature(options.temperatureOrDefault()))
                                    .system(options.systemPrompt() == null || options.systemPrompt().isBlank()
                                            ? RagPrompts.DEFAULT_SYSTEM_PROMPT
                                            : options.systemPrompt())
                                    .user(RagPrompts.formatUserMessage(question,
                                            RagPrompts.formatContext(sources)))
                                    .stream()
                                    .chatResponse()
                                    .mapNotNull(response -> toToken(response, answer)),
                            Flux.defer(() -> Flux.just(AgentEvent.stepDone("generate", "LLM 답변 생성",
                                    System.currentTimeMillis() - t0, answer.length() + "자 생성"))));
                }));

        return Flux.concat(decompose, retrieve, generate,
                        Flux.defer(() -> Flux.just(
                                AgentEvent.metric("answerChars", "답변 길이", answer.length()),
                                AgentEvent.done(System.currentTimeMillis() - startedAt))))
                .onErrorResume(throwable -> {
                    log.error("질의 분해 파이프라인 실패", throwable);
                    String message = throwable.getMessage() == null
                            ? throwable.getClass().getSimpleName()
                            : throwable.getMessage();
                    return Flux.just(AgentEvent.stepError("generate", "실행 실패", message),
                            AgentEvent.error(message));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** 분해에 실패하면 원 질문 하나로 진행합니다. 쪼개는 건 부가 단계입니다. */
    private List<String> decompose(String question) {
        List<String> subQuestions = new ArrayList<>();
        String prompt = """
                아래 질문을 쪼개는 작업만 하세요. 답은 절대 쓰지 마세요.

                한 문장에 서로 다른 것을 묻는 부분이 여러 개 있으면 각각 독립된 질문으로 나눕니다.
                하나만 묻고 있으면 원래 질문을 그대로 한 줄로 씁니다.
                모든 줄은 물음표로 끝나야 합니다.
                질문 하나를 한 줄에 하나씩, 다른 말 없이 출력하세요.

                질문: %s
                """.formatted(question);
        try {
            String raw = ChatClient.builder(this.chatModel).build()
                    .prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(prompt)
                    .call()
                    .content();
            for (String line : (raw == null ? "" : raw).split("\\R")) {
                String candidate = StudentRagPipeline.cleanQuery(line);
                // 물음표가 없으면 모델이 쪼개는 대신 답을 지어낸 것이라 검색에 쓰면 안 됩니다
                if (!candidate.endsWith("?") || subQuestions.contains(candidate)) {
                    continue;
                }
                subQuestions.add(candidate);
                if (subQuestions.size() >= MAX_SUB_QUESTIONS) {
                    break;
                }
            }
        }
        catch (Exception exception) {
            log.warn("질의 분해 실패 — 원 질문으로 검색합니다", exception);
        }
        if (subQuestions.isEmpty()) {
            subQuestions.add(question);
        }
        return subQuestions;
    }

    /**
     * 규격에 없는 이벤트라 프론트가 인스펙터 "로그" 탭에 원문 그대로 보여줍니다. 하위 질문 하나가
     * 각각 무엇을 얼마나 잘 잡았는지는 합쳐진 근거만 봐서는 알 수 없어서 따로 흘려보냅니다.
     */
    private static AgentEvent subQueryEvent(String subQuestion, List<Document> hits) {
        List<String> scores = new ArrayList<>(hits.size());
        for (Document hit : hits) {
            scores.add(hit.getScore() == null ? "-" : String.format("%.3f", hit.getScore()));
        }
        return AgentEvent.of("subQuery")
                .with("question", subQuestion)
                .with("hits", hits.size())
                .with("scores", scores);
    }

    private static AgentEvent toToken(ChatResponse response, StringBuilder answer) {
        String text = response.getResult() == null || response.getResult().getOutput() == null
                ? null
                : response.getResult().getOutput().getText();
        if (text == null || text.isEmpty()) {
            return null;
        }
        answer.append(text);
        return AgentEvent.token(text);
    }
}

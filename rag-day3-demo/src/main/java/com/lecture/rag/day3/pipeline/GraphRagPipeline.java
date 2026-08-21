package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
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
 * 골드 — LightRAG(HKUDS)에서 아이디어를 가져온 자체 구현 그래프 RAG.
 *
 * <p>{@code rag-day3-lightrag-demo}는 실제 LightRAG(Python) 서버를 별도로 띄우는 강사 전용 데모라
 * 색인에 수 분~수십 분이 걸리고 학생 노트북에서는 실행하지 않는 것을 전제로 한다. 이 파이프라인은
 * 같은 핵심 아이디어(청크에서 개체·관계를 추출해 지식 그래프를 만들고, 질문과 관련된 개체 주변을
 * 그래프로 탐색해 근거를 넓힌다)를 이 앱 안에서 OpenAI API로 직접 구현한 것이라 별도 서버 없이
 * {@code ./run.sh} 하나로 바로 동작한다.
 *
 * <pre>
 *  (1) 그래프 구성  청크마다 LLM으로 개체(ENTITY)/관계(RELATION)를 추출해 지식 그래프를 만든다.
 *                  같은 지식 베이스로는 한 번만 만들고 캐시해서 재사용한다(청크 1개 = LLM 호출 1번이라 비싸다).
 *  (2) 그래프 탐색  질문에서 개체를 뽑아 그래프 노드와 매칭하고, 1홉 이웃까지 넓혀 관련 청크를 모은다.
 *                  (LightRAG의 local/hybrid 모드에 해당 — naive처럼 벡터 검색도 같이 돌려서 합친다)
 *  (3) 생성        관계 요약 + 근거 청크를 컨텍스트로 넣어 스트리밍 답변을 만든다.
 * </pre>
 *
 * <p>규모가 작은 지식 베이스에서는 그래프가 오버엔지니어링일 수 있다 — 벡터 검색만으로 충분한 경우
 * "기본 RAG"와 "비교 실행"으로 직접 확인해보는 것이 좋다({@code rag-day3-lightrag-demo}의 강사 데모에서도
 * 문서 7개짜리 실험에서 naive(순수 벡터)가 가장 빠르고 정확했다).
 */
@Component
public class GraphRagPipeline implements RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(GraphRagPipeline.class);

    private static final String STEP_GRAPH_BUILD = "graphBuild";
    private static final String STEP_GRAPH_QUERY = "graphQuery";
    private static final String STEP_GENERATE = "generate";

    /** 그래프 추출에 쓸 청크 상한. 청크 1개 = LLM 호출 1번이라, 제한이 없으면 대형 문서에서 수백 번 호출된다. */
    private static final int MAX_GRAPH_CHUNKS = 40;

    private final KnowledgeBase knowledgeBase;
    private final ChatModel chatModel;

    /** 마지막으로 만든 그래프 캐시. 지식 베이스 내용(문서 선택 + 총 청크 수)이 그대로면 재사용한다. */
    private volatile String cachedSignature;
    private volatile GraphIndex cachedGraph;

    public GraphRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        this.knowledgeBase = knowledgeBase;
        this.chatModel = chatModel;
    }

    @Override
    public String id() {
        return "lightrag";
    }

    @Override
    public String name() {
        return "그래프 RAG (LightRAG 스타일)";
    }

    @Override
    public String tier() {
        return "gold";
    }

    @Override
    public String description() {
        return "청크에서 개체·관계를 추출해 지식 그래프를 만들고, 질문과 연결된 개체 주변을 탐색해 근거를 넓힌다.";
    }

    @Override
    public Flux<AgentEvent> run(ChatRequest request) {
        return new Execution(request).stream();
    }

    private ChatClient chatClient() {
        return ChatClient.builder(this.chatModel).build();
    }

    // ------------------------------------------------------------------ 그래프 자료구조

    private record EntityNode(String key, String name, String type, Set<String> descriptions, Set<String> chunkIds) {
    }

    private record RelationEdge(String sourceKey, String sourceName, String targetKey, String targetName,
            String description) {
    }

    private static final class GraphIndex {
        final Map<String, EntityNode> nodes = new LinkedHashMap<>();
        final List<RelationEdge> edges = new ArrayList<>();
        final Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        final Map<String, Document> chunkById = new LinkedHashMap<>();
        int sampledChunks;
        int totalChunks;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.strip().toLowerCase();
    }

    // ------------------------------------------------------------------ 그래프 빌드(+캐시)

    private record GraphBuildResult(GraphIndex graph, boolean fromCache) {
    }

    private synchronized GraphBuildResult graphFor(List<String> docIds) {
        List<Document> chunks = this.knowledgeBase.chunksOf(docIds);
        String signature = (docIds == null || docIds.isEmpty() ? "ALL" : String.join(",", docIds))
                + ":" + chunks.size();

        if (signature.equals(this.cachedSignature) && this.cachedGraph != null) {
            return new GraphBuildResult(this.cachedGraph, true);
        }

        List<Document> sample = sampleForGraph(chunks);
        GraphIndex graph = new GraphIndex();
        graph.sampledChunks = sample.size();
        graph.totalChunks = chunks.size();
        for (Document chunk : sample) {
            extractInto(graph, chunk);
        }

        this.cachedSignature = signature;
        this.cachedGraph = graph;
        return new GraphBuildResult(graph, false);
    }

    /** 청크가 상한보다 많으면 문서 전체에 고르게 퍼지도록 등간격으로 표본을 뽑는다. */
    private static List<Document> sampleForGraph(List<Document> chunks) {
        if (chunks.size() <= MAX_GRAPH_CHUNKS) {
            return chunks;
        }
        List<Document> sample = new ArrayList<>(MAX_GRAPH_CHUNKS);
        double step = (double) chunks.size() / MAX_GRAPH_CHUNKS;
        for (int i = 0; i < MAX_GRAPH_CHUNKS; i++) {
            sample.add(chunks.get((int) (i * step)));
        }
        return sample;
    }

    /** 청크 하나에서 개체·관계를 추출해 그래프에 합친다. 형식이 깨진 줄은 조용히 건너뛴다. */
    private void extractInto(GraphIndex graph, Document chunk) {
        String prompt = """
                다음 텍스트에서 등장하는 주요 개체(사람·조직·제품·장소·개념 등)와 그 사이의 관계를 추출하세요.

                [텍스트]
                %s

                아래 형식으로만, 파이프(|)로 구분해서 출력하세요. 설명이나 번호를 덧붙이지 마세요.
                ENTITY|이름|유형|한 줄 설명
                RELATION|개체1|개체2|두 개체의 관계를 한 문장으로

                텍스트에 쓰인 표현을 이름으로 그대로 사용하세요. 개체나 관계가 없으면 아무 줄도 출력하지 마세요.
                """.formatted(RagPrompts.squeeze(chunk.getText()));

        String raw;
        try {
            raw = chatClient().prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(prompt)
                    .call()
                    .content();
        }
        catch (Exception exception) {
            log.warn("그래프 추출 실패(청크 {}) — 건너뜀: {}", chunk.getId(), exception.getMessage());
            return;
        }
        if (raw == null) {
            return;
        }

        for (String line : raw.lines().toList()) {
            String[] parts = line.strip().split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String type = parts[0].strip().toUpperCase();
            if (type.equals("ENTITY")) {
                String name = parts[1].strip();
                if (name.isEmpty()) {
                    continue;
                }
                String entityType = parts[2].strip();
                String desc = parts.length > 3 ? parts[3].strip() : "";
                EntityNode node = graph.nodes.computeIfAbsent(normalize(name),
                        key -> new EntityNode(key, name, entityType, new LinkedHashSet<>(), new LinkedHashSet<>()));
                if (!desc.isEmpty()) {
                    node.descriptions().add(desc);
                }
                node.chunkIds().add(chunk.getId());
                graph.chunkById.put(chunk.getId(), chunk);
            }
            else if (type.equals("RELATION")) {
                String sourceName = parts[1].strip();
                String targetName = parts[2].strip();
                String desc = parts.length > 3 ? parts[3].strip() : "";
                if (sourceName.isEmpty() || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) {
                    continue;
                }
                String sourceKey = normalize(sourceName);
                String targetKey = normalize(targetName);
                graph.nodes.computeIfAbsent(sourceKey,
                        key -> new EntityNode(key, sourceName, "", new LinkedHashSet<>(), new LinkedHashSet<>()))
                        .chunkIds().add(chunk.getId());
                graph.nodes.computeIfAbsent(targetKey,
                        key -> new EntityNode(key, targetName, "", new LinkedHashSet<>(), new LinkedHashSet<>()))
                        .chunkIds().add(chunk.getId());
                graph.edges.add(new RelationEdge(sourceKey, sourceName, targetKey, targetName, desc));
                graph.adjacency.computeIfAbsent(sourceKey, key -> new LinkedHashSet<>()).add(targetKey);
                graph.adjacency.computeIfAbsent(targetKey, key -> new LinkedHashSet<>()).add(sourceKey);
                graph.chunkById.put(chunk.getId(), chunk);
            }
        }
    }

    // ------------------------------------------------------------------ 실행

    private final class Execution {

        private final ChatRequest request;
        private final RagOptions options;
        private final String question;
        private final long startedAt = System.currentTimeMillis();

        private List<SourceRef> sources = List.of();
        private String relationSummary = "";
        private final StringBuilder answer = new StringBuilder();

        private long graphBuildMs;
        private long graphQueryMs;
        private long generateMs;
        private int contextChars;
        private Usage usage;

        private Execution(ChatRequest request) {
            this.request = request;
            this.options = request.optionsOrDefault();
            this.question = request.questionOrEmpty();
        }

        Flux<AgentEvent> stream() {
            if (GraphRagPipeline.this.knowledgeBase.isEmpty()) {
                return Flux.just(
                        AgentEvent.notice("warn", "지식 베이스가 비어 있습니다. 왼쪽 패널에서 문서를 먼저 업로드하세요."),
                        AgentEvent.token("아직 인덱싱된 문서가 없어서 답변할 근거가 없습니다. 왼쪽 '지식 베이스' 패널에서 PDF나 텍스트 파일을 업로드해 주세요."),
                        AgentEvent.done(0));
            }
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_GRAPH_BUILD, "지식 그래프 구성")),
                    Flux.defer(this::doGraphBuild),
                    Flux.just(AgentEvent.stepStart(STEP_GRAPH_QUERY, "그래프 탐색")),
                    Flux.defer(this::doGraphQuery),
                    generatePhase(),
                    Flux.defer(this::finishPhase))
                    .onErrorResume(this::toErrorEvents)
                    // 그래프 추출·검색·LLM 호출은 전부 블로킹이라 서블릿 요청 스레드를 붙잡지 않도록 별도 스레드에서 실행
                    .subscribeOn(Schedulers.boundedElastic());
        }

        private GraphIndex graph;

        private Flux<AgentEvent> doGraphBuild() {
            long t0 = System.currentTimeMillis();
            GraphBuildResult result = GraphRagPipeline.this.graphFor(this.request.docIdsOrEmpty());
            this.graph = result.graph();
            this.graphBuildMs = System.currentTimeMillis() - t0;

            String detail = result.fromCache()
                    ? "캐시 사용 · 개체 " + this.graph.nodes.size() + "개 · 관계 " + this.graph.edges.size() + "개"
                    : "청크 " + this.graph.sampledChunks + "개 분석"
                            + (this.graph.sampledChunks < this.graph.totalChunks
                                    ? " (전체 " + this.graph.totalChunks + "개 중 표본)" : "")
                            + " · 개체 " + this.graph.nodes.size() + "개 · 관계 " + this.graph.edges.size() + "개";
            return Flux.just(AgentEvent.stepDone(STEP_GRAPH_BUILD, "지식 그래프 구성", this.graphBuildMs, detail));
        }

        private Flux<AgentEvent> doGraphQuery() {
            long t0 = System.currentTimeMillis();
            List<AgentEvent> events = new ArrayList<>();

            List<String> queryEntities = extractQueryEntities(this.question);
            Set<String> matched = new LinkedHashSet<>();
            for (String entity : queryEntities) {
                String needle = normalize(entity);
                if (needle.isEmpty()) {
                    continue;
                }
                for (EntityNode node : this.graph.nodes.values()) {
                    String haystack = node.name().toLowerCase();
                    if (haystack.contains(needle) || needle.contains(haystack)) {
                        matched.add(node.key());
                    }
                }
            }

            Set<String> related = new LinkedHashSet<>(matched);
            for (String key : matched) {
                related.addAll(this.graph.adjacency.getOrDefault(key, Set.of()));
            }

            this.relationSummary = this.graph.edges.stream()
                    .filter(edge -> related.contains(edge.sourceKey()) && related.contains(edge.targetKey()))
                    .map(edge -> edge.sourceName() + " - " + edge.targetName()
                            + (edge.description().isEmpty() ? "" : ": " + edge.description()))
                    .distinct()
                    .limit(20)
                    .collect(Collectors.joining("\n"));

            List<Document> graphDocuments = related.stream()
                    .map(this.graph.nodes::get)
                    .filter(Objects::nonNull)
                    .flatMap(node -> node.chunkIds().stream())
                    .distinct()
                    .map(this.graph.chunkById::get)
                    .filter(Objects::nonNull)
                    .toList();

            List<Document> vectorDocuments = GraphRagPipeline.this.knowledgeBase.search(
                    this.question, this.options.topKOrDefault(), this.options.similarityThresholdOrDefault(),
                    this.request.docIdsOrEmpty());

            List<Document> merged = RagPrompts.mergeDistinct(List.of(graphDocuments, vectorDocuments));
            List<Document> selected = merged.size() > this.options.topKOrDefault()
                    ? merged.subList(0, this.options.topKOrDefault())
                    : merged;
            this.sources = RagPrompts.toSources(selected);
            this.graphQueryMs = System.currentTimeMillis() - t0;

            String detail = matched.isEmpty()
                    ? "일치하는 개체 없음 → 벡터 검색만 사용 (" + vectorDocuments.size() + "개)"
                    : "질의 개체 " + matched.size() + "개 → 이웃 포함 " + related.size() + "개 · 그래프 청크 "
                            + graphDocuments.size() + "개 + 벡터 " + vectorDocuments.size() + "개 병합";
            events.add(AgentEvent.stepDone(STEP_GRAPH_QUERY, "그래프 탐색", this.graphQueryMs, detail));
            events.add(AgentEvent.sources(this.sources));
            events.add(AgentEvent.metric("chunks", "사용한 청크", this.sources.size()));
            events.add(AgentEvent.metric("entities", "그래프 개체 수", this.graph.nodes.size()));
            events.add(AgentEvent.metric("relations", "그래프 관계 수", this.graph.edges.size()));
            events.add(AgentEvent.metric("matchedEntities", "질의와 일치한 개체", matched.size()));
            if (matched.isEmpty() && !queryEntities.isEmpty()) {
                events.add(AgentEvent.notice("info", "질문에서 개체를 추출했지만 그래프와 일치하지 않아 벡터 검색 결과만 사용합니다."));
            }
            if (this.sources.isEmpty()) {
                events.add(AgentEvent.notice("warn", "그래프·벡터 검색 모두 근거를 찾지 못했습니다."));
            }
            return Flux.fromIterable(events);
        }

        private List<String> extractQueryEntities(String question) {
            String prompt = """
                    다음 질문에서 언급되었거나 관련 있을 만한 개체(사람·조직·제품·장소 등)의 이름만
                    쉼표로 구분해서 출력하세요. 없으면 빈 줄만 출력하고, 설명은 붙이지 마세요.

                    질문: %s
                    """.formatted(question);
            String raw;
            try {
                raw = GraphRagPipeline.this.chatClient().prompt()
                        .options(ChatOptions.builder().temperature(0.0))
                        .user(prompt)
                        .call()
                        .content();
            }
            catch (Exception exception) {
                return List.of();
            }
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            return List.of(raw.strip().split("\\s*,\\s*")).stream().filter(s -> !s.isBlank()).toList();
        }

        // (3) 생성 --------------------------------------------------------------
        private Flux<AgentEvent> generatePhase() {
            return Flux.concat(
                    Flux.just(AgentEvent.stepStart(STEP_GENERATE, "LLM 답변 생성")),
                    Flux.defer(this::doGenerate));
        }

        private Flux<AgentEvent> doGenerate() {
            long t0 = System.currentTimeMillis();
            String context = RagPrompts.formatContext(this.sources);
            String userMessage = (this.relationSummary.isBlank() ? "" : "[관계 요약]\n" + this.relationSummary + "\n\n")
                    + "[컨텍스트]\n" + context + "\n\n[질문]\n" + this.question;
            this.contextChars = userMessage.length();

            String systemPrompt = this.options.systemPrompt() == null || this.options.systemPrompt().isBlank()
                    ? RagPrompts.DEFAULT_SYSTEM_PROMPT
                    : this.options.systemPrompt();
            List<Message> history = RagPrompts.historyMessages(
                    this.request.historyOrEmpty(), this.options.maxHistoryOrDefault());

            Flux<AgentEvent> tokens = GraphRagPipeline.this.chatClient()
                    .prompt()
                    .options(ChatOptions.builder().temperature(this.options.temperatureOrDefault()))
                    .system(systemPrompt)
                    .messages(history)
                    .user(userMessage)
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

        // 마무리 -----------------------------------------------------------------
        private Flux<AgentEvent> finishPhase() {
            long totalMs = System.currentTimeMillis() - this.startedAt;
            List<AgentEvent> events = new ArrayList<>();
            events.add(AgentEvent.metric("graphBuildMs", "그래프 구성 시간(ms)", this.graphBuildMs));
            events.add(AgentEvent.metric("graphQueryMs", "그래프 탐색 시간(ms)", this.graphQueryMs));
            events.add(AgentEvent.metric("generateMs", "생성 시간(ms)", this.generateMs));
            events.add(AgentEvent.metric("answerChars", "답변 길이", this.answer.length()));
            if (this.usage != null) {
                events.add(AgentEvent.metric("promptTokens", "프롬프트 토큰", this.usage.getPromptTokens()));
                events.add(AgentEvent.metric("completionTokens", "생성 토큰", this.usage.getCompletionTokens()));
            }
            events.add(AgentEvent.done(totalMs));
            return Flux.fromIterable(events);
        }

        private Flux<AgentEvent> toErrorEvents(Throwable throwable) {
            log.error("그래프 RAG 파이프라인 실행 실패", throwable);
            String message = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage();
            return Flux.just(
                    AgentEvent.stepError(STEP_GENERATE, "실행 실패", message),
                    AgentEvent.error(message));
        }
    }
}

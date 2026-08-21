package com.lecture.rag.day3.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lecture.rag.day3.eval.JudgeService;
import com.lecture.rag.day3.knowledge.KnowledgeBase;
import com.lecture.rag.day3.pipeline.PipelineRegistry;

/**
 * 상태 확인 / 파이프라인 목록 / 답변 채점.
 *
 * <pre>
 * GET  /api/health      백엔드·Ollama·모델·지식베이스 상태 (프론트 상단 상태 표시등)
 * GET  /api/pipelines   선택 가능한 파이프라인 목록 (프론트 드롭다운)
 * POST /api/evaluate    LLM-as-judge 채점 (골드 티어, 답변 카드의 "채점" 버튼)
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class MetaController {

    private final KnowledgeBase knowledgeBase;
    private final PipelineRegistry registry;
    private final JudgeService judgeService;

    private final String chatModel;
    private final String embeddingModel;
    private final String ollamaBaseUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public MetaController(KnowledgeBase knowledgeBase, PipelineRegistry registry, JudgeService judgeService,
            @Value("${spring.ai.ollama.chat.options.model:unknown}") String chatModel,
            @Value("${spring.ai.ollama.embedding.options.model:unknown}") String embeddingModel,
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.knowledgeBase = knowledgeBase;
        this.registry = registry;
        this.judgeService = judgeService;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    /**
     * @param ollamaUp        Ollama 서버 응답 여부
     * @param chatModelReady  설정된 채팅 모델이 실제로 pull 되어 있는지
     * @param embeddingReady  설정된 임베딩 모델이 실제로 pull 되어 있는지
     */
    public record Health(String status, String chatModel, String embeddingModel, boolean ollamaUp,
            boolean chatModelReady, boolean embeddingReady, int documents, int chunks) {
    }

    @GetMapping("/health")
    public Health health() {
        String tags = fetchOllamaTags();
        boolean up = tags != null;
        return new Health(
                up ? "ok" : "ollama-down",
                this.chatModel,
                this.embeddingModel,
                up,
                up && containsModel(tags, this.chatModel),
                up && containsModel(tags, this.embeddingModel),
                this.knowledgeBase.documents().size(),
                this.knowledgeBase.totalChunks());
    }

    @GetMapping("/pipelines")
    public List<PipelineRegistry.PipelineInfo> pipelines() {
        return this.registry.describeAll();
    }

    @PostMapping("/evaluate")
    public JudgeService.EvalResult evaluate(@RequestBody JudgeService.EvalRequest request) {
        return this.judgeService.judge(request);
    }

    /** Ollama가 살아있는지 + 어떤 모델이 있는지 확인. 실패하면 null. */
    private String fetchOllamaTags() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(this.ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        }
        catch (Exception exception) {
            return null;
        }
    }

    /** "llama3.2:3b" 처럼 태그까지 적힌 이름과 "bge-m3"처럼 태그를 생략한 이름 모두 처리. */
    private static boolean containsModel(String tags, String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        String bare = model.contains(":") ? model.substring(0, model.indexOf(':')) : model;
        return tags.contains("\"" + model + "\"") || tags.contains("\"" + bare + ":");
    }
}

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
 * GET  /api/health      백엔드·OpenAI·모델·지식베이스 상태 (프론트 상단 상태 표시등)
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
    private final String apiKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public MetaController(KnowledgeBase knowledgeBase, PipelineRegistry registry, JudgeService judgeService,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String chatModel,
            @Value("${spring.ai.openai.embedding.options.model:unknown}") String embeddingModel,
            @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.knowledgeBase = knowledgeBase;
        this.registry = registry;
        this.judgeService = judgeService;
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.apiKey = apiKey;
    }

    /**
     * @param apiUp           OpenAI API 응답 여부 (키가 비어 있거나 요청이 실패하면 false)
     * @param chatModelReady  설정된 채팅 모델이 이 계정에서 실제로 조회되는지
     * @param embeddingReady  설정된 임베딩 모델이 이 계정에서 실제로 조회되는지
     */
    public record Health(String status, String chatModel, String embeddingModel, boolean apiUp,
            boolean chatModelReady, boolean embeddingReady, int documents, int chunks) {
    }

    @GetMapping("/health")
    public Health health() {
        if (this.apiKey == null || this.apiKey.isBlank()) {
            return new Health("openai-key-missing", this.chatModel, this.embeddingModel,
                    false, false, false, this.knowledgeBase.documents().size(), this.knowledgeBase.totalChunks());
        }
        String models = fetchOpenAiModels();
        boolean up = models != null;
        return new Health(
                up ? "ok" : "openai-down",
                this.chatModel,
                this.embeddingModel,
                up,
                up && containsModel(models, this.chatModel),
                up && containsModel(models, this.embeddingModel),
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

    /** OpenAI API가 이 키로 응답하는지 + 계정에서 어떤 모델이 보이는지 확인. 실패하면 null. */
    private String fetchOpenAiModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Authorization", "Bearer " + this.apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = this.httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        }
        catch (Exception exception) {
            return null;
        }
    }

    /** /v1/models 응답 JSON에 "id":"모델명" 이 들어있는지만 간단히 확인 (전체 파싱 불필요). */
    private static boolean containsModel(String modelsJson, String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        return modelsJson.contains("\"" + model + "\"");
    }
}

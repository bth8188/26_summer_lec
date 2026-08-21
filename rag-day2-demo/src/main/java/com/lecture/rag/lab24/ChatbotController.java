package com.lecture.rag.lab24;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lab 2.4 — CLI와 같은 로직(ChatbotService)을 REST로 노출한 것.
 *
 * 실행: 1) docker compose up -d
 *       2) run.bat api   (또는 ./mvnw spring-boot:run -Dspring-boot.run.profiles=api)
 *       3) 브라우저에서 http://localhost:8080/swagger-ui.html
 *
 * chatbot 프로필은 웹 서버를 끄고 콘솔 루프를 돌리고, api 프로필은 웹 서버를 켜고 이 컨트롤러를 노출한다.
 */
@RestController
@RequestMapping("/api/chat")
@Profile("api")
@Tag(name = "제주·김치 문서 챗봇",
        description = "김치=QuestionAnswerAdvisor(항상 검색) / 제주=@Tool(모델이 판단할 때만 검색). "
                + "두 문서 어디에도 근거가 없으면 \"모르겠습니다\"로 답한다.")
public class ChatbotController {

    /** 질문 요청 본문. */
    public record ChatRequest(String question) {}

    /** 임계값 변경 요청 본문. */
    public record ThresholdRequest(double value) {}

    /** 현재 임계값 응답. */
    public record ThresholdResponse(double threshold) {}

    /** 재인덱싱 결과 응답. */
    public record ReindexResponse(String message) {}

    private final ChatbotService service;

    public ChatbotController(ChatbotService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "질문하기",
            description = """
                    질문을 던지면 두 단계 관문을 거쳐 답한다.
                    1) 유사도 임계값을 넘긴 청크가 하나도 없으면 LLM을 호출하지 않고 바로 "모르겠습니다"
                    2) 청크는 있지만 그 안에 답이 없다고 판정되면 답변 생성을 차단하고 "모르겠습니다"
                    응답의 answered=false면 위 둘 중 하나에서 막힌 것이고, reason에 어느 관문인지 담긴다.
                    retrieved에는 검색된 청크와 점수, 임계값 통과 여부가 들어 있어 왜 그런 답이 나왔는지 추적할 수 있다.
                    """)
    public ChatbotService.Answer ask(@RequestBody ChatRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question은 필수입니다.");
        }
        return service.ask(request.question().trim());
    }

    @GetMapping("/scores")
    @Operation(summary = "유사도 원점수 조회 (임계값 튜닝용)",
            description = """
                    임계값을 적용하지 않고(similarityThresholdAll) 검색해 원점수를 그대로 돌려준다.
                    관련 질문과 무관한 질문을 각각 넣어보고, 두 점수 분포 사이 어디에 임계값을 둘지 정하는 데 쓴다.
                    passed 필드는 현재 임계값 기준 통과 여부다.
                    """)
    public ChatbotService.Scores scores(
            @Parameter(description = "점수를 확인할 질문", example = "제주도 역사")
            @RequestParam String question,
            @Parameter(description = "소스별로 가져올 개수", example = "3")
            @RequestParam(defaultValue = "3") int topK) {
        if (question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question은 필수입니다.");
        }
        return service.scores(question.trim(), topK);
    }

    @GetMapping("/threshold")
    @Operation(summary = "현재 유사도 임계값 조회")
    public ThresholdResponse getThreshold() {
        return new ThresholdResponse(service.getThreshold());
    }

    @PutMapping("/threshold")
    @Operation(summary = "유사도 임계값 변경",
            description = "0.0 ~ 1.0. 다음 질문부터 즉시 적용된다. 재시작하면 기본값으로 돌아간다.")
    public ThresholdResponse setThreshold(@RequestBody ThresholdRequest request) {
        try {
            service.setThreshold(request.value());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return new ThresholdResponse(service.getThreshold());
    }

    @PostMapping("/reindex")
    @Operation(summary = "두 위키 재인덱싱",
            description = "기존 청크를 지우고 다시 인덱싱한다. 청킹 방식이나 원본 문서를 바꿨을 때 호출한다. "
                    + "임베딩을 다시 계산하므로 시간이 걸린다.")
    public ReindexResponse reindex() {
        service.reindex();
        return new ReindexResponse("재인덱싱 완료");
    }
}

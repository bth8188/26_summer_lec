package com.lecture.rag.lab25;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lab 2.5 Swagger 버전 — 콘솔로 만든 하이브리드 챗봇(HybridWikiChatbotService)을 REST API로 옮긴 것
 * (lab13 콘솔 → lab14 Swagger로 옮긴 것과 동일한 관계).
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab25-api
 * 접속: http://localhost:8080/swagger-ui/index.html
 */
@RestController
@Profile("lab25-api")
@RequestMapping("/api/lab25")
@Tag(name = "Lab2.5 하이브리드 위키 챗봇",
        description = "김치 위키는 QuestionAnswerAdvisor로 항상 검색하고, 제주도 위키는 Tool로 모델이 필요할 때만 검색한다. "
                + "두 문서 모두에서 관련 내용을 찾지 못하면 '모르겠습니다'라고 답한다.")
public class HybridChatbotApiController {

    private final HybridWikiChatbotService chatbotService;

    public HybridChatbotApiController(HybridWikiChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @PostConstruct
    void init() {
        chatbotService.ensureIndexed();
    }

    public record ChatResult(String question, String answer) {}

    @Operation(summary = "하이브리드 RAG 챗봇에 질문",
            description = "김치(Advisor)와 제주도(Tool) 두 소스를 함께 사용해 답한다. "
                    + "예시 — kimchi: \"김치는 언제부터 먹기 시작했어?\", jeju: \"제주도 인구는 몇 명이야?\", "
                    + "관련 없는 질문(예: 오늘 날씨)은 '모르겠습니다'로 답한다.")
    @GetMapping("/chat")
    public ChatResult chat(
            @Parameter(description = "김치 또는 제주도 관련 질문", example = "제주도 인구는 몇 명이야?")
            @RequestParam String question) {
        String answer = chatbotService.chat(question);
        return new ChatResult(question, answer);
    }
}

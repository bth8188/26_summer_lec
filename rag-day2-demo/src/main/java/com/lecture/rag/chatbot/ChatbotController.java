package com.lecture.rag.chatbot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 접속: http://localhost:8080/swagger-ui.html
 */
@RestController
@Profile("chatbot")
@RequestMapping("/api/chatbot")
@Tag(name = "RAG 챗봇", description = "김치 위키(Advisor)와 제주도 위키(Tool)를 근거로 답변하는 RAG 챗봇 API")
public class ChatbotController {

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    public record ChatResult(String question, String answer) {}

    @Operation(summary = "챗봇에게 질문하기",
            description = "김치 위키는 QuestionAnswerAdvisor로 항상 검색되고, 제주도 위키는 모델이 필요하다고 "
                    + "판단할 때만 도구로 검색된다. 둘 다 관련 내용을 찾지 못하면 모른다고 답변한다.")
    @GetMapping("/chat")
    public ChatResult chat(
            @Parameter(description = "질문", example = "김치는 언제부터 먹기 시작했어?")
            @RequestParam String question) {
        return new ChatResult(question, chatbotService.chat(question));
    }
}

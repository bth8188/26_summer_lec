package com.lecture.rag.controller;

import com.lecture.rag.service.RagChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * RagDay2DemoApplication의 CLI 챗봇과 동일한 RagChatService를 사용하는 REST API.
 * Swagger UI(/swagger-ui.html)에서 kimchi(QuestionAnswerAdvisor) + jeju(@Tool) 조합 RAG를
 * GET 요청 하나로 테스트할 수 있게 한다.
 */
@RestController
@RequestMapping("/api/rag2")
@Tag(name = "Day2 RAG 챗봇", description = "kimchi-wiki는 QuestionAnswerAdvisor로, jeju-wiki는 @Tool로 검색하는 RAG 챗봇 API")
public class RagChatController {

    private final RagChatService ragChatService;

    public RagChatController(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    @Operation(summary = "RAG 챗봇에게 질문",
            description = "kimchi-wiki는 QuestionAnswerAdvisor로 질문마다 항상 자동 검색되고, "
                    + "jeju-wiki는 @Tool로 모델이 필요하다고 판단할 때만 스스로 검색한다. "
                    + "관련 문서를 찾지 못하면 추측하지 않고 모른다고 답한다.")
    @GetMapping("/chat")
    public String chat(
            @Parameter(description = "RAG 챗봇에게 물어볼 질문", example = "제주도 특산품이 뭐야")
            @RequestParam(defaultValue = "제주도 특산품이 뭐야") String question) {
        return ragChatService.chat(question);
    }
}

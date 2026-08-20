package com.lecture.rag.lab24;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wiki")
@Tag(
        name = "Wiki RAG",
        description = "김치 Advisor와 제주 Tool을 사용하는 RAG API"
)
public class WikiChatController {

    private final WikiRagService wikiRagService;

    public WikiChatController(WikiRagService wikiRagService) {
        this.wikiRagService = wikiRagService;
    }

    @PostMapping("/chat")
    @Operation(
            summary = "Wiki RAG 질문",
            description = """
                    김치 또는 제주 관련 질문을 입력합니다.
                    관련 문서의 유사도가 threshold보다 낮으면
                    '모르겠습니다'를 반환합니다.
                    """
    )
    public WikiChatResponse chat(
            @RequestBody WikiChatRequest request) {

        String answer = wikiRagService.ask(
                request.question()
        );

        return new WikiChatResponse(
                request.question(),
                answer
        );
    }
}
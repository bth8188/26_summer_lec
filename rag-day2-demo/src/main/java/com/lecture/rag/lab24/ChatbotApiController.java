package com.lecture.rag.lab24;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lab 2.4 — 콘솔 챗봇의 Swagger 버전. 로직은 ChatbotService를 그대로 쓰고 여기서는 HTTP만 붙인다.
 * Day1 api/LiveDemoController와 같은 방식(@Profile로 컨트롤러를 켜고 끔).
 *
 * 실행: 1) docker compose up -d
 *       2) ./run.sh lab24-api
 * 접속: http://localhost:8080/swagger-ui/index.html
 */
@RestController
@Profile("lab24-api")
@RequestMapping("/api/chatbot")
@Tag(name = "Lab2.4 RAG 챗봇",
        description = "김치 문서는 Advisor로 항상 검색되고, 제주 문서는 도구로 필요할 때만 검색된다. "
                + "어느 쪽에서도 근거가 안 나오면 LLM을 부르지 않고 거절한다.")
public class ChatbotApiController {

    private final ChatbotService chatbot;

    public ChatbotApiController(ChatbotService chatbot) {
        this.chatbot = chatbot;
    }

    @Operation(summary = "챗봇에게 질문하기",
            description = "응답의 sources를 보면 어느 문서가 근거였는지, toolCalled를 보면 제주 검색 도구가 호출됐는지 알 수 있다. "
                    + "refused가 true면 검색 게이트에 막혀 LLM을 아예 호출하지 않은 것. "
                    + "확인용: \"제주도 면적이 얼마야?\"(도구 경로) / \"김치는 언제부터 먹었어?\"(Advisor 경로) / "
                    + "\"아이폰 최신 모델이 뭐야?\"(거절)")
    @GetMapping("/ask")
    public ChatbotService.Answer ask(
            @Parameter(description = "제주도 또는 김치에 대한 질문. 둘 다 아닌 질문을 넣으면 refused=true로 거절되는 걸 볼 수 있다.",
                    example = "제주도 면적이 얼마야?")
            @RequestParam(defaultValue = "제주도 면적이 얼마야?") String question) {
        return chatbot.ask(question);
    }

    @Operation(summary = "인덱싱된 문서 확인",
            description = "source 태그별 청크 수를 돌려준다. 두 문서가 같은 테이블에 들어가 있고 "
                    + "태그로만 갈려 있다는 것을 눈으로 확인하는 용도.")
    @GetMapping("/documents")
    public Map<String, Integer> documents() {
        return chatbot.indexedChunkCounts();
    }
}

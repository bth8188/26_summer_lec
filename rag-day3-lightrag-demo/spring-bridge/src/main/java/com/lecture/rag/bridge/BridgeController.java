package com.lecture.rag.bridge;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BridgeController {

    private final LightRagRestClient lightRag;
    private final ChatClient chatClient;

    public BridgeController(LightRagRestClient lightRag, ChatClient.Builder builder) {
        this.lightRag = lightRag;
        this.chatClient = builder.build();
    }

    /** 방법 1 — REST 직접 호출. mode를 우리 API로 그대로 노출할 수 있다. */
    @GetMapping("/api/rest")
    public String viaRest(@RequestParam String q,
                          @RequestParam(defaultValue = "hybrid") String mode) {
        return lightRag.ask(q, mode);
    }

    /**
     * 방법 2 — Day1에 배운 ChatClient 코드 그대로. 한 줄도 바꾸지 않았다.
     * application.yml의 spring.ai.ollama.base-url이 LightRAG(9621)를 가리키고 있어서,
     * 이 호출은 실제로는 지식그래프 검색을 거친 답을 받아온다.
     */
    @GetMapping("/api/spring-ai")
    public String viaSpringAi(@RequestParam String q) {
        return chatClient.prompt().user(q).call().content();
    }
}

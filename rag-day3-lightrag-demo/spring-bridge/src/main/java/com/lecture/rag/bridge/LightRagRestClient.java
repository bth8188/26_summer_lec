package com.lecture.rag.bridge;

import java.time.Duration;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 방법 1 — LightRAG의 /query를 그대로 호출한다. 엔진 고유 기능(mode)을 그대로 쓸 수 있다. */
@Service
public class LightRagRestClient {

    private final RestClient rest;

    public LightRagRestClient(RestClient.Builder builder) {
        // 그래프 검색 + 로컬 LLM 생성은 느리다. 타임아웃을 넉넉히 두지 않으면 반드시 터진다.
        // (실측: 다른 데모가 같은 Ollama를 쓰고 있으면 5분도 부족해 HttpTimeoutException 이 난다)
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilderHolder.withTimeout(Duration.ofMinutes(10));
        this.rest = builder.baseUrl("http://localhost:9621").requestFactory(factory).build();
    }

    public record QueryRequest(String query, String mode) {}
    public record QueryResponse(String response) {}

    /** mode: naive | local | global | hybrid */
    public String ask(String question, String mode) {
        QueryResponse res = rest.post()
                .uri("/query")
                .body(new QueryRequest(question, mode))
                .retrieve()
                .body(QueryResponse.class);
        return res == null ? "" : res.response();
    }

    /** 타임아웃 설정만 분리해둔 헬퍼 (슬라이드에서는 생략해도 되는 부분) */
    static final class ClientHttpRequestFactoryBuilderHolder {
        static ClientHttpRequestFactory withTimeout(Duration timeout) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(timeout);
            return factory;
        }
    }
}

package com.lecture.rag.bridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Day3 M3.2B — 오픈소스 RAG 엔진(LightRAG)을 Spring 서비스에 붙이는 두 가지 방법을
 * 같은 앱 안에서 나란히 실증한다.
 *
 * 방법 1: REST API 직접 호출        → {@link LightRagRestClient}
 * 방법 2: Ollama 호환 엔드포인트     → {@link BridgeController#viaSpringAi}
 *         (application.yml의 base-url만 LightRAG로 돌리면 Day1 코드가 그대로 동작)
 */
@SpringBootApplication
public class BridgeApplication {
    public static void main(String[] args) {
        SpringApplication.run(BridgeApplication.class, args);
    }
}

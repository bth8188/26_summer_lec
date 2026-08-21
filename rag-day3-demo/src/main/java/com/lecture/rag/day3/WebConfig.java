package com.lecture.rag.day3;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Next.js 프론트(localhost:3000)가 이 백엔드(localhost:8081)를 브라우저에서 직접 fetch로 호출하므로
 * CORS를 열어줘야 한다 — 프록시용 Next.js API 라우트를 따로 두지 않고 프론트가 백엔드를 직접 호출하는 구조.
 *
 * <p>문서 삭제(DELETE)까지 쓰므로 허용 메서드에 DELETE가 포함되어 있다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origin}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}

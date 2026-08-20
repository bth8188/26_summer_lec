package com.lecture.rag.lab25;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * Lab 2.5 콘솔 버전 — QuestionAnswerAdvisor(kimchi-wiki) + @Tool(jeju-wiki) 하이브리드 챗봇.
 * 실행: 1) docker compose up -d  (PGVector 컨테이너 기동)
 *       2) ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab25
 *
 * 확인용 질문 예시
 *  - kimchi(Advisor) 케이스: "김치는 언제부터 먹기 시작했어?"
 *  - jeju(Tool) 케이스: "제주도 인구는 몇 명이야?"
 *  - 실패 케이스(둘 다에 없는 내용): "오늘 서울 날씨 어때?" → "모르겠습니다"로 답해야 함
 */
@Component
@Profile("lab25")
public class HybridChatbotConsoleDemo implements CommandLineRunner {

    private final HybridWikiChatbotService chatbotService;

    public HybridChatbotConsoleDemo(HybridWikiChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @Override
    public void run(String... args) {
        chatbotService.ensureIndexed();

        System.out.println("=== 하이브리드 위키 챗봇 준비 완료 (김치=Advisor, 제주도=Tool) — 종료하려면 빈 줄 입력 ===");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) {
                break;
            }

            String answer = chatbotService.chat(question);
            System.out.println("답변> " + answer);
            System.out.println();
        }
    }
}

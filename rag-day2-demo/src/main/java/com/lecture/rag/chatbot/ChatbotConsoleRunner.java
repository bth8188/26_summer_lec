package com.lecture.rag.chatbot;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=chatbot
 * 콘솔에서 질문을 입력받아 답변한다. 빈 줄을 입력하면 종료.
 * 같은 프로필로 REST API(ChatbotController, /api/chatbot/chat)와 Swagger UI도 함께 뜬다.
 */
@Component
@Profile("chatbot")
public class ChatbotConsoleRunner implements CommandLineRunner {

    private final ChatbotService chatbotService;

    public ChatbotConsoleRunner(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @Override
    public void run(String... args) {
        System.out.println("=== 챗봇 준비 완료 (제주도/김치 관련 질문 가능, 종료하려면 빈 줄 입력) ===");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) break;

            String answer = chatbotService.chat(question);
            System.out.println("답변> " + answer);
            System.out.println();
        }
    }
}

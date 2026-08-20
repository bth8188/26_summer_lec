package com.lecture.rag.lab24;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

@Component
@Profile("wiki-chat")
public class KmhRagDay2Demo implements CommandLineRunner {

    private final WikiRagService wikiRagService;

    public KmhRagDay2Demo(WikiRagService wikiRagService) {
        this.wikiRagService = wikiRagService;
    }

    @Override
    public void run(String... args) {

        Scanner scanner = new Scanner(System.in);

        System.out.println("=== Wiki RAG Chatbot ===");
        System.out.println("종료하려면 exit 입력");

        while (true) {

            System.out.print("\n질문 > ");
            String question = scanner.nextLine().trim();

            if (question.equalsIgnoreCase("exit")) {
                System.out.println("챗봇을 종료합니다.");
                break;
            }

            if (question.isBlank()) {
                continue;
            }

            String answer = wikiRagService.ask(question);

            System.out.println("답변: " + answer);
        }
    }
}
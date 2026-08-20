package com.lecture.rag;

import com.lecture.rag.service.RagChatService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Scanner;

/**
 * RAG 기반 CLI 챗봇.
 * - kimchi-wiki: QuestionAnswerAdvisor로 제공 → 질문마다 항상 자동으로 검색해서 컨텍스트에 주입.
 * - jeju-wiki: @Tool(JejuWikiSearchTool)로 제공 → 모델이 필요하다고 판단할 때만 스스로 검색 도구를 호출.
 * ChatClient 구성(kimchi Advisor + jeju Tool)과 인덱싱 로직은 RagChatService에 공통화되어 있고,
 * 같은 서비스를 REST API(RagChatController)에서도 재사용한다.
 */
@SpringBootApplication
public class RagDay2DemoApplication implements CommandLineRunner {

    private final RagChatService ragChatService;

    public RagDay2DemoApplication(RagChatService ragChatService) {
        this.ragChatService = ragChatService;
    }

    public static void main(String[] args) {
        SpringApplication.run(RagDay2DemoApplication.class, args);
    }

    @Override
    public void run(String... args) {
        ragChatService.indexIfNeeded();

        System.out.println("=== RAG 챗봇 시작 (빈 줄을 입력하면 종료됩니다) ===");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("\n질문> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String line = scanner.nextLine();
            if (line.isBlank()) {
                System.out.println("챗봇을 종료합니다.");
                break;
            }

            String answer = ragChatService.chat(line);
            System.out.println("답변> " + answer);
        }
        scanner.close();
    }
}

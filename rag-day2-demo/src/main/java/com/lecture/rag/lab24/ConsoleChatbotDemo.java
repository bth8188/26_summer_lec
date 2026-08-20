package com.lecture.rag.lab24;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * Lab 2.4 — 콘솔 버전. 알맹이는 전부 ChatbotService에 있고 여기서는 입출력만 담당한다.
 * 같은 서비스를 쓰는 Swagger 버전은 ChatbotApiController (프로필 lab24-api).
 *
 * 실행: 1) docker compose up -d
 *       2) ./run.sh lab24     (종료하려면 빈 줄 입력)
 */
@Component
@Profile("lab24")
public class ConsoleChatbotDemo implements CommandLineRunner {

    private final ChatbotService chatbot;

    public ConsoleChatbotDemo(ChatbotService chatbot) {
        this.chatbot = chatbot;
    }

    @Override
    public void run(String... args) {
        System.out.println();
        System.out.println("=== RAG 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
        System.out.println("확인용 질문: \"제주도 면적이 얼마야?\" / \"김치는 언제부터 먹었어?\" / \"아이폰 최신 모델이 뭐야?\"");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) {
                break;
            }

            ChatbotService.Answer result = chatbot.ask(question);
            System.out.println("답변> " + result.answer());
            if (result.refused()) {
                System.out.println("      (검색 게이트: 유사도 " + ChatbotService.SIMILARITY_THRESHOLD
                        + " 이상인 청크 없음 → LLM 호출 안 함)");
            } else {
                System.out.println("      (근거 문서: " + result.sources() + " / 제주 도구 "
                        + (result.toolCalled() ? "호출됨" : "호출 안 됨") + ")");
            }
            System.out.println();
        }
    }
}

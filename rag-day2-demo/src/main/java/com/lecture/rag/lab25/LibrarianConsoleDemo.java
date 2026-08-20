package com.lecture.rag.lab25;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Scanner;

/**
 * Lab 2.5 — 사서 챗봇 콘솔 버전. 알맹이는 LibrarianService에 있다.
 */
@Component
@Profile("lab25")
public class LibrarianConsoleDemo implements CommandLineRunner {

    private final LibrarianService librarian;

    public LibrarianConsoleDemo(LibrarianService librarian) {
        this.librarian = librarian;
    }

    @Override
    public void run(String... args) {
        System.out.println();
        System.out.println("=== 자료실 사서 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
        System.out.println("카탈로그 질문: \"어떤 자료들 갖고 있어?\" / \"위키 문서 몇 개야?\"");
        System.out.println("본문 질문:     \"제주도 면적이 얼마야?\"");
        System.out.println("조합 질문:     \"김치 문서에서 김치의 역사를 찾아줘\"");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) {
                break;
            }

            LibrarianService.Answer result = librarian.ask(question);
            System.out.println("답변> " + result.answer());
            System.out.println("      (카탈로그 도구 " + result.catalogCalls()
                    + "회 / 본문 도구 " + result.contentCalls() + "회)");
            System.out.println();
        }
    }
}

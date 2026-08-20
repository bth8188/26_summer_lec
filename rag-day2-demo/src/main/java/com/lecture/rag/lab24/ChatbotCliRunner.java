package com.lecture.rag.lab24;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Scanner;

/**
 * Lab 2.4 — 챗봇의 CLI 프런트엔드. 로직은 전부 ChatbotService에 있고 여기선 입출력만 담당한다.
 * (같은 서비스를 REST로 노출한 게 ChatbotController — api 프로필)
 *
 * 실행: 1) docker compose up -d
 *       2) run.bat chatbot   (또는 ./mvnw spring-boot:run -Dspring-boot.run.profiles=chatbot)
 */
@Component
@Profile("chatbot")
public class ChatbotCliRunner implements CommandLineRunner {

    /** 부팅 직후 점수 분포를 보여주기 위한 표본 질문 — 제주 / 김치 / 둘 다 아닌 것 */
    private static final List<String> CALIBRATION_QUESTIONS = List.of(
            "제주도의 역사를 알려줘",
            "김치는 어떻게 담그나요?",
            "파이썬으로 웹 서버 만드는 법 알려줘");

    private final ChatbotService service;

    public ChatbotCliRunner(ChatbotService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        System.out.println();
        calibrate();
        printBanner();

        try (Scanner scanner = new Scanner(System.in, consoleCharset())) {
            while (true) {
                System.out.print("나 > ");
                System.out.flush();
                if (!scanner.hasNextLine()) {
                    break;
                }
                String line = scanner.nextLine().trim();

                if (line.isEmpty()) {
                    continue;
                }
                if (line.equals("/exit") || line.equals("/quit")) {
                    break;
                }
                if (line.equals("/help")) {
                    printHelp();
                    continue;
                }
                if (line.startsWith("/threshold")) {
                    handleThreshold(line);
                    continue;
                }
                if (line.startsWith("/scores")) {
                    handleScores(line);
                    continue;
                }
                if (line.equals("/reindex")) {
                    service.reindex();
                    System.out.println();
                    continue;
                }
                ask(line);
            }
        }
        System.out.println("종료합니다.");
    }

    // ------------------------------------------------------------------ 대화

    private void ask(String question) {
        // LLM 호출이 느려서 그동안 타이핑하면(type-ahead) 터미널 에코와 출력이 뒤엉킨다.
        // 어떤 질문에 대한 답인지 알아볼 수 있게 질문을 다시 찍어준다.
        System.out.println("[질문] " + question);
        System.out.println("(검색 중...)");

        ChatbotService.Answer result = service.ask(question);

        System.out.println("봇 > " + result.answer());
        if (result.answered()) {
            System.out.println("     [경로] 김치 Advisor=항상 실행 / 제주 도구="
                    + (result.jejuToolCalls() > 0 ? "호출됨(" + result.jejuToolCalls() + "회)" : "호출 안 됨"));
        } else {
            System.out.println("     [차단] " + result.reason());
        }
        System.out.println();
    }

    // ------------------------------------------------------- 임계값 튜닝 지원

    /**
     * 임계값을 감으로 정하지 않기 위한 단계.
     * 임계값 없이 검색한 <b>원점수</b>를 찍어서, "관련 질문일 때 점수 분포"와
     * "무관한 질문일 때 점수 분포" 사이 어디에 선을 그어야 하는지 눈으로 본다.
     * lab22에서 LLM 점수 20개를 전부 출력해 확인했던 것과 같은 방식.
     */
    private void calibrate() {
        System.out.println("################ 임계값 캘리브레이션 (임계값 미적용 원점수) ################");
        for (String question : CALIBRATION_QUESTIONS) {
            printScores(question);
        }
        System.out.println("→ 제주/김치 관련 질문의 점수와 무관한 질문의 점수 사이에 선을 그으세요.");
        System.out.println("→ 바꾸려면 대화 중 아무 때나  /threshold 0.5");
        System.out.println();
    }

    private void printScores(String question) {
        ChatbotService.Scores scores = service.scores(question, 3);
        System.out.println("[질문] " + question + "   (현재 임계값 " + scores.threshold() + ")");
        printHits("제주", scores.jeju());
        printHits("김치", scores.kimchi());
        System.out.println();
    }

    private void printHits(String label, List<ChatbotService.Hit> hits) {
        if (hits.isEmpty()) {
            System.out.println("  " + label + ": (저장된 문서 없음)");
            return;
        }
        for (ChatbotService.Hit hit : hits) {
            System.out.printf("  %s %.4f | %s | %s%n",
                    hit.passed() ? "통과" : "차단", hit.score(), label, hit.preview());
        }
    }

    private void handleScores(String line) {
        String question = line.substring("/scores".length()).trim();
        if (question.isEmpty()) {
            System.out.println("사용법: /scores <질문>");
            return;
        }
        printScores(question);
    }

    private void handleThreshold(String line) {
        String arg = line.substring("/threshold".length()).trim();
        if (arg.isEmpty()) {
            System.out.println("현재 임계값: " + service.getThreshold());
            return;
        }
        try {
            service.setThreshold(Double.parseDouble(arg));
            System.out.println("임계값을 " + service.getThreshold() + " 로 바꿨습니다. (다음 질문부터 적용)");
        } catch (NumberFormatException e) {
            System.out.println("숫자를 입력하세요. 예: /threshold 0.5");
        } catch (IllegalArgumentException e) {
            System.out.println(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 출력

    private void printBanner() {
        System.out.println("################ 제주 · 김치 문서 챗봇 ################");
        System.out.println("김치 = QuestionAnswerAdvisor (항상 검색) / 제주 = @Tool (모델이 판단할 때만 검색)");
        System.out.println("두 문서 어디에도 근거가 없으면 \"모르겠습니다\"라고 답합니다.");
        printHelp();
    }

    private void printHelp() {
        System.out.println("""
                ---------------------------------------------------------------
                  <질문>            그냥 입력하면 챗봇에게 질문합니다
                  /scores <질문>    임계값 없이 검색한 원점수를 출력 (튜닝용)
                  /threshold <값>   유사도 임계값 변경 (예: /threshold 0.5)
                  /reindex          두 위키를 지우고 다시 인덱싱 (청킹 바꿨을 때)
                  /help             이 도움말
                  /exit             종료
                ---------------------------------------------------------------""");
    }

    /**
     * Windows 콘솔에서 한글 입력이 깨지지 않게, JVM이 감지한 콘솔 인코딩을 그대로 쓴다.
     * (Java 19+의 stdin.encoding — run.bat이 chcp 65001을 걸면 UTF-8이 된다)
     */
    private Charset consoleCharset() {
        String encoding = System.getProperty("stdin.encoding",
                System.getProperty("file.encoding", "UTF-8"));
        try {
            return Charset.forName(encoding);
        } catch (Exception e) {
            return Charset.defaultCharset();
        }
    }
}

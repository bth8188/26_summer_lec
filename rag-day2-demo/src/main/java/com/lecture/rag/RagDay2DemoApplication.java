package com.lecture.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Description;

import java.util.Scanner;
import java.util.function.Function;

@SpringBootApplication
public class RagDay2DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagDay2DemoApplication.class, args);
    }

    @Bean
    public CommandLineRunner cliRunner(ChatClient.Builder chatClientBuilder, VectorStore pgVectorStore) {
        return args -> {
           ChatClient chatClient = chatClientBuilder
                    .defaultSystem("당신은 친절한 AI 어시스턴트입니다. 검색된 문서나 도구의 결과에 정보가 없다면, 지어내지 말고 반드시 '모른다'고 대답하세요.")
                    .defaultAdvisors(
                            QuestionAnswerAdvisor.builder(pgVectorStore).build(),
                            new SimpleLoggerAdvisor()
                    )
                    .defaultTools("jejuWiki")
                    .build();

            Scanner scanner = new Scanner(System.in);
            System.out.println("==================================================");
            System.out.println("챗봇이 시작되었습니다. 질문을 입력하세요. (종료: 'exit')");
            System.out.println("==================================================");

            while (true) {
                System.out.print("\n사용자: ");
                String input = scanner.nextLine();

                if ("exit".equalsIgnoreCase(input.trim())) {
                    System.out.println("챗봇을 종료합니다.");
                    break;
                }

                if (input.trim().isEmpty()) continue;

                try {
                    String response = chatClient.prompt()
                            .user(input)
                            .call()
                            .content();
                    
                    System.out.println("챗봇: " + response);
                } catch (Exception e) {
                    System.out.println("오류가 발생했습니다: " + e.getMessage());
                }
            }
        };
    }

    public record JejuWikiRequest(String keyword) {}

    @Bean("jejuWiki")
    @Description("제주도와 관련된 지식, 관광지, 문화 등에 대한 정보를 검색할 때 사용하는 위키 도구입니다.")
    public Function<JejuWikiRequest, String> jejuWikiFunction() {
        return request -> {
            System.out.println("   [System: jejuWiki 도구가 호출되었습니다. 검색어 - " + request.keyword() + "]");
            return request.keyword() + "에 대한 제주 위키 검색 결과입니다. (임시 데이터)";
        };
    }
}
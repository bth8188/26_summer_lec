package com.lecture.rag.lab25;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Scanner;

@Component
@Profile("lab25")
public class Lab25RagChatbot implements CommandLineRunner {

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    public Lab25RagChatbot(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {

        // 김치 문서만 검색하는 Advisor
        SearchRequest kimchiSearch = SearchRequest.builder().topK(3).similarityThreshold(0.7).filterExpression("source == 'kimchi'").build();

        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).searchRequest(kimchiSearch).build();

        // 제주 문서는 Tool로 검색
        JejuSearchTool jejuTool = new JejuSearchTool(vectorStore);

        ChatClient chatClient = ChatClient.builder(chatModel).defaultSystem("""
                너는 문서 기반 RAG 챗봇이다.
                
                규칙:
                - 김치 관련 질문은 제공된 문서 내용을 참고해서 답한다.
                - 제주도 관련 질문은 jejuSearch 도구를 사용한다.
                - 검색 결과가 없거나 관련도가 낮으면 추측하지 않는다.
                - 알 수 없는 내용은 "모르겠습니다."라고 답한다.
                - 항상 한국어로 답한다.
                """).defaultAdvisors(qaAdvisor).defaultTools(jejuTool).build();

        Scanner scanner = new Scanner(System.in);

        System.out.println("============================");
        System.out.println("Lab2.5 RAG 챗봇");
        System.out.println("종료하려면 exit 입력");
        System.out.println("============================");

        while (true) {

            System.out.print("\n질문> ");

            String question = scanner.nextLine();

            if (question.equalsIgnoreCase("exit")) {
                break;
            }

            String answer = chatClient.prompt().user(question).call().content();

            System.out.println("답변> " + answer);
        }
    }


    static class JejuSearchTool {

        private final VectorStore vectorStore;

        JejuSearchTool(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
        }

        @Tool(name = "jejuSearch", description = "제주도의 역사, 관광, 지리, 문화 등 제주도 관련 내용을 검색한다.")
        public String search(String query) {

            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(3).similarityThreshold(0.7).filterExpression("source == 'jeju'").build());

            if (docs == null || docs.isEmpty()) {
                return "검색 결과가 없습니다. 모르겠습니다.";
            }

            StringBuilder result = new StringBuilder();

            for (Document doc : docs) {

                result.append(doc.getText()).append("\n\n");
            }

            return result.toString();
        }
    }
}
package com.lecture.rag;

import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class RagDay2DemoApplication {

    // 유사도 기준
    private static final double THRESHOLD = 0.65;

    public static void main(String[] args) {
        SpringApplication.run(RagDay2DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner chatRunner(
            ChatModel chatModel,
            VectorStore vectorStore
    ) {

        return args -> {

            /*
             * =========================================================
             * 1. kimchi-wiki → QuestionAnswerAdvisor
             * =========================================================
             */
            QuestionAnswerAdvisor kimchiAdvisor =
                    QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(
                                    SearchRequest.builder()
                                            .topK(5)
                                            .similarityThreshold(THRESHOLD)

                                            // kimchi-wiki 자료만 검색
                                            .filterExpression(
                                                    "wiki == 'kimchi-wiki'"
                                            )
                                            .build()
                            )
                            .build();


            /*
             * =========================================================
             * 2. jeju-wiki → Tool
             * =========================================================
             */
            JejuWikiTool jejuWikiTool =
                    new JejuWikiTool(vectorStore);


            /*
             * =========================================================
             * 3. ChatClient
             * =========================================================
             */
            ChatClient chatClient =
                    ChatClient.builder(chatModel)
                            .defaultSystem("""
                                    너는 RAG 기반 질의응답 챗봇이다.

                                    반드시 제공된 검색 자료만 근거로 답변해야 한다.

                                    규칙:
                                    1. 김치와 관련된 질문은 Advisor에서 제공되는
                                       kimchi-wiki 정보를 사용한다.

                                    2. 제주와 관련된 질문은
                                       searchJejuWiki 도구를 사용해서 정보를 검색한다.

                                    3. 검색 자료에서 답을 찾을 수 없으면
                                       추측하지 말고 반드시 "모르겠습니다."라고 답한다.

                                    4. 모델이 원래 알고 있던 지식만으로
                                       임의로 답변하면 안 된다.
                                    """)
                            .build();


            /*
             * =========================================================
             * 4. CLI
             * =========================================================
             */

            Scanner scanner = new Scanner(System.in);

            System.out.println();
            System.out.println("====================================");
            System.out.println(" RAG 챗봇 시작");
            System.out.println(" 종료하려면 exit 입력");
            System.out.println("====================================");

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


                /*
                 * =====================================================
                 * 5. 먼저 pgvector에서 관련 문서가 존재하는지 확인
                 * =====================================================
                 */

                boolean hasResult =
                        hasRelevantDocument(vectorStore, question);

                /*
                 * 관련 문서가 하나도 없으면
                 * LLM 자체 지식으로 답하지 못하게 여기서 차단
                 */
                if (!hasResult) {
                    System.out.println("AI > 모르겠습니다.");
                    continue;
                }


                /*
                 * =====================================================
                 * 6. Advisor + Tool을 동시에 사용
                 * =====================================================
                 */

                try {

                    String answer =
                            chatClient.prompt()
                                    .user(question)

                                    // kimchi-wiki
                                    .advisors(kimchiAdvisor)

                                    // jeju-wiki
                                    .tools(jejuWikiTool)

                                    .call()
                                    .content();


                    System.out.println("AI > " + answer);

                }
                catch (Exception e) {

                    System.out.println(
                            "AI 호출 중 오류가 발생했습니다."
                    );

                    System.out.println(
                            "오류: " + e.getMessage()
                    );
                }
            }
        };
    }


    /*
     * ================================================================
     * pgvector에 실제로 관련 문서가 있는지 확인
     * ================================================================
     */
    private static boolean hasRelevantDocument(
            VectorStore vectorStore,
            String question
    ) {

        /*
         * kimchi-wiki 검색
         */
        List<Document> kimchiResults =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(1)
                                .similarityThreshold(THRESHOLD)
                                .filterExpression(
                                        "wiki == 'kimchi-wiki'"
                                )
                                .build()
                );


        /*
         * jeju-wiki 검색
         */
        List<Document> jejuResults =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(1)
                                .similarityThreshold(THRESHOLD)
                                .filterExpression(
                                        "wiki == 'jeju-wiki'"
                                )
                                .build()
                );


        boolean hasKimchi =
                kimchiResults != null &&
                !kimchiResults.isEmpty();

        boolean hasJeju =
                jejuResults != null &&
                !jejuResults.isEmpty();


        return hasKimchi || hasJeju;
    }


    /*
     * ================================================================
     * jeju-wiki 전용 Tool
     * ================================================================
     */
    public static class JejuWikiTool {

        private final VectorStore vectorStore;

        public JejuWikiTool(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
        }


        @Tool(
                name = "searchJejuWiki",
                description = """
                        제주도와 관련된 질문에 답하기 위해
                        jeju-wiki Vector DB를 검색하는 도구입니다.
                        제주도의 관광지, 역사, 문화, 음식, 지리 등의
                        질문이 들어오면 이 도구를 사용하세요.
                        """
        )
        public String searchJejuWiki(

                @ToolParam(
                        description = "jeju-wiki에서 검색할 사용자의 질문"
                )
                String query

        ) {

            List<Document> documents =
                    vectorStore.similaritySearch(
                            SearchRequest.builder()
                                    .query(query)
                                    .topK(5)
                                    .similarityThreshold(THRESHOLD)

                                    // 제주 자료만 검색
                                    .filterExpression(
                                            "wiki == 'jeju-wiki'"
                                    )
                                    .build()
                    );


            /*
             * 검색 결과 없음
             */
            if (documents == null || documents.isEmpty()) {
                return "검색 결과가 없습니다. 모르겠습니다.";
            }


            /*
             * 검색된 Document 내용을
             * LLM에게 반환
             */
            return documents.stream()
                    .map(Document::getText)
                    .filter(text ->
                            text != null &&
                            !text.isBlank()
                    )
                    .collect(
                            Collectors.joining("\n\n")
                    );
        }
    }
}
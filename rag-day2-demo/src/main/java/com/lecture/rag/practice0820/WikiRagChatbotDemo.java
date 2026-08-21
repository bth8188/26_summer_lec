package com.lecture.rag.practice0820;

// cli로 인풋 받고
// 제주, 김치 문서 인덱싱
// 김치 QuestionAnswerAdvisor 생성
// 제주 tool 등록
// Scanner로 질문 반복 입력

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@Component
@Profile("wiki-rag")
public class WikiRagChatbotDemo implements CommandLineRunner {

    private static final String UNKNOWN_ANSWER = "관련 문서에서 답을 찾지 못했습니다.";

    private static final String JEJU_DOCUMENT_PATH =
            "classpath:/scenarios/6-wiki-jeju.pdf";

    private static final String KIMCHI_DOCUMENT_PATH =
            "classpath:/scenarios/7-wiki-kimchi.pdf";

    private static final int CHUNK_SIZE = 300;
    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    public WikiRagChatbotDemo(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        indexWikiDocuments();

        QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .filterExpression("source == 'kimchi'")
                        .build())
                .build();

        JejuWikiSearchTool jejuTool = new JejuWikiSearchTool(vectorStore);
        GroundedAnswerGenerator groundedAnswerGenerator = new GroundedAnswerGenerator(chatModel);

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        항상 한국어로 답변하세요.
                        제공된 제주 위키와 김치 위키 문서만 근거로 답변하세요.
                        제주도에 관한 질문에는 반드시 searchJejuWiki 도구를 사용하세요.
                        문서에서 관련 근거를 찾지 못하면 사전지식으로 추측하지 말고
                        "관련 문서에서 답을 찾지 못했습니다."라고 답하세요.
                        관련 근거가 있다면 반드시 질문에 대한 구체적인 답변 본문을 먼저 작성하세요.
                        출처만 단독으로 출력해서는 안 됩니다.
                        최종 답변은 다음 형식을 따르세요.

                        답변: 문서 근거에서 찾은 구체적인 답변
                        출처: 제주 위키 또는 김치 위키

                        searchJejuWiki 같은 Tool 이름은 출처가 아닙니다.
                        """)
                .build();

        runChatLoop(chatClient, kimchiAdvisor, jejuTool, groundedAnswerGenerator);
    }

    private void indexWikiDocuments() {
        System.out.println("=== 제주·김치 Wiki 문서 인덱싱 시작 ===");

        replaceDocument("jeju", JEJU_DOCUMENT_PATH);
        replaceDocument("kimchi", KIMCHI_DOCUMENT_PATH);

        System.out.println("=== 제주·김치 Wiki 문서 인덱싱 완료 ===");
        System.out.println();
    }

    private void replaceDocument(String source, String documentPath) {
        vectorStore.delete("source == '" + source + "'");

        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(documentPath);
        List<Document> pages = pdfReader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(CHUNK_SIZE)
                .build();
        List<Document> chunks = splitter.apply(pages);

        List<Document> chunksWithMetadata = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Document chunkWithMetadata = chunk.mutate()
                    .metadata("source", source)
                    .metadata("chunk_index", i)
                    .build();
            chunksWithMetadata.add(chunkWithMetadata);
        }

        vectorStore.add(chunksWithMetadata);
        System.out.printf("  - %s: %d개 청크 저장 완료%n", source, chunksWithMetadata.size());
    }

    private void runChatLoop(
            ChatClient chatClient,
            QuestionAnswerAdvisor kimchiAdvisor,
            JejuWikiSearchTool jejuTool,
            GroundedAnswerGenerator groundedAnswerGenerator
    ) {
        System.out.println("=== Wiki RAG 챗봇 준비 완료 ===");
        System.out.println("빈 줄을 입력하면 종료합니다.");
        System.out.println();

        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("질문> ");

            if (!scanner.hasNextLine()) {
                break;
            }

            String question = scanner.nextLine();
            if (question.isBlank()) {
                break;
            }

            int callCountBefore = jejuTool.getCallCount();
            jejuTool.clearEvidence();

            String answer;
            if (jejuTool.supports(question)) {
                boolean searchedWithOriginalQuestion = false;

                // 먼저 Spring AI의 정식 Tool Calling을 시도한다.
                chatClient.prompt()
                        .advisors(kimchiAdvisor)
                        .tools(jejuTool)
                        .user(question)
                        .call()
                        .content();

                // 소형 모델이 Tool 호출 대신 JSON 문자열만 출력한 경우의 보완 경로다.
                if (jejuTool.getCallCount() == callCountBefore) {
                    jejuTool.searchJejuWiki(question);
                    searchedWithOriginalQuestion = true;
                }

                answer = groundedAnswerGenerator.answer(
                        question,
                        jejuTool.getEvidenceText(),
                        "제주 위키"
                );

                // Tool이 검색어를 잘못 바꿔 관련 근거를 놓친 경우 원래 질문으로 재검색한다.
                if (UNKNOWN_ANSWER.equals(answer) && !searchedWithOriginalQuestion) {
                    jejuTool.searchJejuWiki(question);
                    answer = groundedAnswerGenerator.answer(
                            question,
                            jejuTool.getEvidenceText(),
                            "제주 위키"
                    );
                }
            }
            else if (question.contains("김치")) {
                List<Document> kimchiEvidence = searchKimchiEvidence(question);
                if (kimchiEvidence.isEmpty()) {
                    answer = UNKNOWN_ANSWER;
                }
                else {
                    answer = chatClient.prompt()
                            .advisors(kimchiAdvisor)
                            .user(question)
                            .call()
                            .content();
                }
            }
            else {
                answer = UNKNOWN_ANSWER;
            }

            int callCountAfter = jejuTool.getCallCount();
            boolean toolCalled = callCountAfter > callCountBefore;

            System.out.println("[제주 Tool 호출 여부] " + (toolCalled ? "호출됨" : "호출 안 됨"));
            System.out.println("답변> " + answer);
            System.out.println();
        }

        System.out.println("챗봇을 종료합니다.");
    }

    private List<Document> searchKimchiEvidence(String question) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression("source == 'kimchi'")
                .build());
    }
}

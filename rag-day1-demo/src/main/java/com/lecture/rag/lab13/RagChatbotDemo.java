package com.lecture.rag.lab13;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Scanner;

/**
 * Lab 1.3 — 첫 RAG 챗봇 (콘솔 Q&A)
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=lab13
 *
 * 확인용 질문 예시
 *  - 성공 케이스: "물탱크 용량이 얼마야?" / "환불은 며칠 안에 가능해?" / "E2 오류는 뭐야?"
 *  - 실패 케이스(문서에 없는 내용): "이 제품 방수 등급이 IP68 맞지?" 처럼 없는 사실을 물어보고
 *    모델이 여전히 그럴듯하게 답을 지어내는지 관찰 (Day2/M2.6 "모른다고 답하기"로 연결)
 */
@Component
@Profile("lab13")
public class RagChatbotDemo implements CommandLineRunner {

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final JdbcTemplate jdbcTemplate;
    private final String documentPath;

    public RagChatbotDemo(ChatModel chatModel,
                           EmbeddingModel embeddingModel,
                           JdbcTemplate jdbcTemplate,
                           @Value("${rag.demo.document-path}") String documentPath) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.jdbcTemplate = jdbcTemplate;
        this.documentPath = documentPath;
    }

    @Override
    public void run(String... args) {
        VectorStore vectorStore = buildVectorStore();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("항상 한국어로 답변하세요.")
                .build();
        // topK 조절은 여기 — 몇 개의 청크를 검색해서 프롬프트에 넣을지 정하는 값 (기본값 4)
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).build();

        System.out.println("=== RAG 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) break;

            String answer = chatClient.prompt()
                    .advisors(qaAdvisor, SimpleLoggerAdvisor.builder().build())
                    .user(question)
                    .call()
                    .content();

            System.out.println("답변> " + answer);
            System.out.println();
        }
    }

    private VectorStore buildVectorStore() {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(documentPath);
        List<Document> documents = pdfReader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(200) // topK 기본값(4)보다 청크 수가 많아지되, 문맥이 끊기지 않을 정도로
                .build();
        List<Document> chunks = splitter.apply(documents);

        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(chunks);
        return vectorStore;
    }

    /**
     * Day2/Lab2.1에서 쓰는 PgVectorStore를 오토컨피그(application.yml)가 아니라 코드에서 직접 Bean처럼 만드는 예시.
     * buildVectorStore()와 인터페이스(VectorStore) 사용법은 동일 — 구현체만 바뀐다는 걸 보여주기 위한 참고용 메서드.
     * 실행하려면 PGVector가 localhost:5432/ragdb로 떠 있어야 함 (rag-day2-demo/docker-compose.yml 참고).
     */
    private VectorStore buildPgVectorStore() {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(1024)   // bge-m3 기준
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(true)
                .build();
    }
}

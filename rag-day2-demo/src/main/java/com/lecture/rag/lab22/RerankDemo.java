package com.lecture.rag.lab22;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Lab 2.2 — Rerank
 * VectorStore에서 넓게 검색한 뒤 LLM으로 재채점
 */
@Component
@Profile("lab22")
public class RerankDemo implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public RerankDemo(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    @Override
    public void run(String... args) {

        // 1. PDF 읽기
        PagePdfDocumentReader pdfReader =
                new PagePdfDocumentReader(
                        "classpath:/docs/agentic-rag-survey.pdf"
                );

        List<Document> documents = pdfReader.get();


        // 2. 청킹
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(300)
                .build();

        List<Document> chunks = splitter.apply(documents);


        // 3. PgVectorStore에 저장
        vectorStore.add(chunks);

        System.out.println("총 청크 수: " + chunks.size());
        System.out.println();


        String query =
                "What are the main failure modes of agentic RAG systems?";


        // 4. 순수 벡터 검색 top-1
        List<Document> plainTop1 =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(1)
                                .build()
                );

        System.out.println("=== 1. 순수 벡터 검색 top-1 ===");
        printPreview(plainTop1);
        System.out.println();


        // 5. 후보를 조금 더 넓게 검색 - top-2
        List<Document> wideCandidates =
                vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(2)
                                .build()
                );


        // 6. LLM Reranker
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("항상 숫자만 답하세요.")
                .build();

        LlmReranker reranker =
                new LlmReranker(chatClient);


        System.out.println(
                "=== 2. 넓게 검색(top-2) 후보 각각의 LLM 채점 ==="
        );

        List<LlmReranker.Scored> scored =
                reranker.scoreAll(query, wideCandidates);

        scored.stream()
                .sorted(
                        (a, b) ->
                                Integer.compare(
                                        b.score(),
                                        a.score()
                                )
                )
                .forEach(
                        s -> System.out.printf(
                                "점수 %2d | %s...%n",
                                s.score(),
                                preview(s.doc(), 70)
                        )
                );

        System.out.println();


        // 7. 재채점 결과 중 top-1 선택
        List<Document> reranked =
                scored.stream()
                        .sorted(
                                (a, b) ->
                                        Integer.compare(
                                                b.score(),
                                                a.score()
                                        )
                        )
                        .limit(1)
                        .map(LlmReranker.Scored::doc)
                        .toList();


        System.out.println("=== 3. Rerank 최종 top-1 ===");

        printPreview(reranked);

        System.out.println();


        // 8. 결과 비교
        long overlap =
                plainTop1.stream()
                        .filter(reranked::contains)
                        .count();

        System.out.println(
                "순수 벡터 top-1과 rerank top-1의 겹치는 문서 수: "
                        + overlap
                        + " / 1"
        );
    }


    private void printPreview(List<Document> docs) {

        for (Document doc : docs) {

            System.out.println(
                    "- " + preview(doc, 100) + "..."
            );
        }
    }


    private String preview(Document doc, int len) {

        String text =
                doc.getText()
                        .replaceAll("\\s+", " ")
                        .trim();

        return text.substring(
                0,
                Math.min(len, text.length())
        );
    }
}
package com.lecture.rag.lab12;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lab 1.2 — 문서 로딩 -> 청킹 -> VectorStore 인덱싱 -> 검색
 * 실행: 1) cd ../rag-day2-demo && docker compose up -d   (PGVector 컨테이너 기동)
 *       2) ./run.sh lab12
 * 실습 문서: 실제 arXiv 논문 (2026-03, arXiv:2603.07379, 25페이지)
 *
 * 저장소는 PGVector. VectorStore가 인터페이스라서 스토어를 주입받는 것 말고는
 * add() / similaritySearch() 호출부가 인메모리(SimpleVectorStore) 시절과 완전히 동일하다 —
 * Day2 Lab2.1에서 "구현체만 갈아끼우면 된다"고 설명하는 지점이 바로 이것.
 */
@Component
@Profile("lab12")
public class IndexingPipelineDemo implements CommandLineRunner {

    private static final String DOCUMENT_PATH = "classpath:/docs/manual.pdf";

    // 자동설정이 만들어둔 PgVectorStore가 주입된다. 임베딩 모델을 여기서 직접 다루지 않는데도
    // 검색이 되는 이유는 스토어가 내부에서 EmbeddingModel을 호출하기 때문.
    private final VectorStore vectorStore;

    public IndexingPipelineDemo(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        // 1) 문서 로드
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(DOCUMENT_PATH);
        List<Document> documents = pdfReader.get();
        System.out.println("=== 1. 문서 로드 ===");
        System.out.println("로드된 페이지(Document) 수: " + documents.size());
        System.out.println();

        // 2) 청킹
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .build();
        List<Document> chunks = splitter.apply(documents);
        System.out.println("=== 2. 청킹 결과 ===");
        System.out.println("생성된 청크 수: " + chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunks.get(i).getText().replaceAll("\\s+", " ");
            preview = preview.substring(0, Math.min(60, preview.length()));
            System.out.printf("  [%d] %s...%n", i, preview);
        }
        System.out.println();

        // 3) 저장 — 스토어를 직접 만들지 않고 주입받은 것을 그대로 쓴다
        vectorStore.add(chunks);
        System.out.println("=== 3. VectorStore 저장 완료 (" + vectorStore.getClass().getSimpleName() + ", PostgreSQL에 영속) ===");
        System.out.println();

        // 4) 검색
        String query = "What is agentic RAG?";
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(3).build());

        System.out.println("=== 4. 검색 결과 (질문: \"" + query + "\") ===");
        for (Document doc : results) {
            System.out.println("- " + doc.getText().replaceAll("\\s+", " "));
        }
    }
}

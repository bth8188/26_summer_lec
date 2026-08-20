package com.lecture.rag.lab21m1;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.ai.reader.TextReader;

import java.util.List;

/**
 * M2.1 — 청킹 전략 4종을 실제 시나리오 문서로 비교.
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=chunking-strategies
 */
@Component
@Profile("chunking-strategies")
public class ChunkingStrategyDemo implements CommandLineRunner {

    private final EmbeddingModel embeddingModel;

    public ChunkingStrategyDemo(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        System.out.println("################ 1. Fixed-size (TokenTextSplitter) vs Recursive ################");
        compareFixedVsRecursive();

        System.out.println();
        System.out.println("################ 2. 문서 구조 활용 청킹 (제N조 경계) ################");
        structureBased();

        System.out.println();
        System.out.println("################ 3. Sliding Window (겹치는 청크) ################");
        slidingWindow();

        System.out.println();
        System.out.println("################ 4. Semantic Chunking (문장 간 유사도 급락 지점) ################");
        semanticChunking();
    }

    private Document loadFullText(String file) {
        if (file.endsWith(".pdf")) {
            PagePdfDocumentReader reader = new PagePdfDocumentReader("classpath:/scenarios/" + file);
            List<Document> pages = reader.get();
            String combined = pages.stream().map(Document::getText).reduce("", (a, b) -> a + "\n\n" + b);
            combined = combined.replaceAll("[ \\t]+", " ");
            return new Document(combined);
        } else {
            TextReader reader = new TextReader("classpath:/scenarios/" + file);
            List<Document> docs = reader.get();
            return docs.get(0);
        }
    }

    private void compareFixedVsRecursive() {
        Document doc = loadFullText("4-terms-startuprecipe.pdf");

        TokenTextSplitter fixed = TokenTextSplitter.builder().withChunkSize(300).build();
        List<Document> fixedChunks = fixed.apply(List.of(doc));
        System.out.println("[Fixed-size] 청크 수: " + fixedChunks.size());
        for (int i = 0; i < Math.min(2, fixedChunks.size()); i++) {
            System.out.println("  [" + i + "] " + preview(fixedChunks.get(i).getText(), 150));
        }

        RecursiveCharacterSplitter recursive = new RecursiveCharacterSplitter(600);
        List<Document> recursiveChunks = recursive.split(doc);
        System.out.println("[Recursive] 청크 수: " + recursiveChunks.size());
        for (int i = 0; i < Math.min(2, recursiveChunks.size()); i++) {
            System.out.println("  [" + i + "] " + preview(recursiveChunks.get(i).getText(), 150));
        }
    }

    private void structureBased() {
        Document doc = loadFullText("4-terms-startuprecipe.pdf");
        StructureBasedSplitter splitter = StructureBasedSplitter.forKoreanArticles();
        List<Document> chunks = splitter.split(doc);
        System.out.println("청크 수: " + chunks.size() + " (실제 조항 수와 비교해볼 것)");
        for (int i = 0; i < Math.min(4, chunks.size()); i++) {
            System.out.println("  [" + i + "] " + preview(chunks.get(i).getText(), 100));
        }
    }

    private void slidingWindow() {
        Document doc = loadFullText("6-wiki-jeju.pdf");
        SlidingWindowSplitter splitter = new SlidingWindowSplitter(300, 150);
        List<Document> chunks = splitter.split(doc);
        System.out.println("청크 수: " + chunks.size() + " (window=300자, stride=150자 → 약 50% 겹침)");
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            System.out.println("  [" + i + "] " + preview(chunks.get(i).getText(), 120));
        }
        if (chunks.size() >= 2) {
            String end0 = chunks.get(0).getText();
            String start1 = chunks.get(1).getText();
            System.out.println("  --- 청크0 끝부분과 청크1 시작부분이 겹치는지 확인 ---");
            System.out.println("  청크0 끝: ..." + end0.substring(Math.max(0, end0.length() - 60)));
            System.out.println("  청크1 시작: " + start1.substring(0, Math.min(60, start1.length())) + "...");
        }
    }

    private void semanticChunking() {
        Document doc = loadFullText("7-wiki-kimchi.pdf");
        SemanticChunker chunker = new SemanticChunker(embeddingModel, 0.7);
        List<SemanticChunker.SentenceSimilarity> analysis = chunker.analyze(doc);

        System.out.println("문장별 직전 문장과의 코사인 유사도:");
        for (var s : analysis) {
            System.out.printf("  %.4f | %s%n", s.similarityToPrev(), preview(s.sentence(), 60));
        }

        List<Document> chunks = chunker.split(doc);
        System.out.println("threshold=0.7 기준 생성된 청크 수: " + chunks.size());
    }

    private String preview(String text, int len) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.substring(0, Math.min(len, t.length())) + (t.length() > len ? "..." : "");
    }
}

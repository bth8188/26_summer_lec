package com.lecture.rag.chatbot;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 챗봇이 쓸 제주도/김치 위키 문서를 PGVector에 인덱싱한다.
 * 같은 vector_store 테이블을 공유하므로 source 메타데이터로 태그해서 필터로 구분한다.
 * 이미 인덱싱되어 있으면(재시작) 다시 인덱싱하지 않는다.
 */
@Component
@Profile("chatbot")
public class WikiIndexer {

    static final String JEJU_SOURCE = "jeju";
    static final String KIMCHI_SOURCE = "kimchi";

    private final VectorStore vectorStore;

    public WikiIndexer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void index() {
        indexIfMissing(JEJU_SOURCE, "classpath:/scenarios/6-wiki-jeju.pdf");
        indexIfMissing(KIMCHI_SOURCE, "classpath:/scenarios/7-wiki-kimchi.pdf");
    }

    private void indexIfMissing(String source, String path) {
        if (!existing(source).isEmpty()) {
            System.out.println("=== [" + source + "] 이미 인덱싱되어 있어 건너뜀 ===");
            return;
        }

        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(path);
        List<Document> documents = pdfReader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(300).build();
        List<Document> chunks = splitter.apply(documents);

        List<Document> tagged = chunks.stream()
                .map(chunk -> Document.builder()
                        .id(chunk.getId())
                        .text(chunk.getText())
                        .metadata(chunk.getMetadata())
                        .metadata("source", source)
                        .build())
                .toList();

        vectorStore.add(tagged);
        System.out.println("=== [" + source + "] " + tagged.size() + "개 청크 인덱싱 완료 ===");
    }

    private List<Document> existing(String source) {
        return vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(source)
                        .topK(1)
                        .filterExpression("source == '" + source + "'")
                        .similarityThresholdAll()
                        .build());
    }
}

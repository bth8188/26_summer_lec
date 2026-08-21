package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 이 앱의 지식 베이스. 업로드된 문서들의 청크를 한 곳에 모아두고 검색을 제공한다.
 *
 * <p>Day1 Lab1.4는 "PDF 하나 = VectorStore 하나"였고 새 파일을 올리면 이전 걸 통째로 갈아끼웠다.
 * 캡스톤은 여러 문서를 동시에 얹고 문서별로 지우는 게 가능해야 하므로, VectorStore 하나를 계속 쓰면서
 * 어떤 청크가 어떤 문서 소속인지 {@code docId} 메타데이터로 관리한다.
 *
 * <p>벡터와 메타데이터는 PGVector에 영속화한다. 하이브리드 키워드 검색에 필요한 전체 청크는
 * 메모리에도 보관하되, 서버 시작 시 {@code vector_store} 테이블에서 다시 읽어 복원한다.
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final EmbeddingModel embeddingModel;
    private final VectorStore store;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** docId -> 문서 메타. 업로드 순서를 유지하려고 LinkedHashMap. */
    private final Map<String, IndexedDocument> documents = new LinkedHashMap<>();

    /**
     * 청크 원본 보관소. VectorStore는 "질문과 비슷한 것"만 돌려주고 전체 목록을 주지 않기 때문에,
     * 키워드 검색(하이브리드 검색) 같은 걸 직접 구현하려면 전체 청크를 따로 들고 있어야 한다.
     */
    private final Map<String, List<Document>> chunksByDoc = new LinkedHashMap<>();

    public KnowledgeBase(EmbeddingModel embeddingModel, VectorStore store,
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** PGVector에 남아 있는 청크를 읽어 문서 목록과 키워드 검색용 메모리를 복원한다. */
    @PostConstruct
    synchronized void restoreFromPgVector() {
        List<Document> restored = this.jdbcTemplate.query(
                "SELECT id::text, content, metadata::text FROM vector_store",
                (rs, rowNum) -> restoreDocument(rs.getString(1), rs.getString(2), rs.getString(3)));

        restored.sort(Comparator
                .comparing((Document chunk) -> stringMetadata(chunk, "docId", ""))
                .thenComparingInt(chunk -> intMetadata(chunk, "chunkIndex", 0)));

        for (Document chunk : restored) {
            String docId = stringMetadata(chunk, "docId", "");
            if (!docId.isBlank()) {
                this.chunksByDoc.computeIfAbsent(docId, key -> new ArrayList<>()).add(chunk);
            }
        }
        this.chunksByDoc.forEach((docId, chunks) -> this.documents.put(docId, restoredDocument(docId, chunks)));
        log.info("PGVector에서 문서 {}개, 청크 {}개를 복원했습니다", this.documents.size(), restored.size());
    }

    public EmbeddingModel embeddingModel() {
        return this.embeddingModel;
    }

    /**
     * 청크 묶음을 임베딩해서 저장한다. 진행률을 보여줄 수 있게 배치 단위로 나눠 호출하는 걸 전제로 한다.
     * (임베딩은 청크 개수에 비례해서 시간이 걸리는 구간이라, 한 번에 다 넣으면 UI가 멈춘 것처럼 보인다)
     */
    public synchronized void addChunks(String docId, List<Document> chunks) {
        this.store.add(chunks);
        this.chunksByDoc.computeIfAbsent(docId, key -> new ArrayList<>()).addAll(chunks);
    }

    public synchronized void register(IndexedDocument document) {
        this.documents.put(document.docId(), document);
    }

    public synchronized List<IndexedDocument> documents() {
        return List.copyOf(this.documents.values());
    }

    public synchronized int totalChunks() {
        return this.chunksByDoc.values().stream().mapToInt(List::size).sum();
    }

    public synchronized boolean isEmpty() {
        return this.chunksByDoc.isEmpty();
    }

    /** 전체 청크(키워드 검색·BM25·통계 등 직접 구현할 때 쓸 재료). */
    public synchronized List<Document> allChunks() {
        List<Document> all = new ArrayList<>();
        this.chunksByDoc.values().forEach(all::addAll);
        return all;
    }

    /** 특정 문서들의 청크만. docIds가 비어 있으면 전체. */
    public synchronized List<Document> chunksOf(Collection<String> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return allChunks();
        }
        List<Document> selected = new ArrayList<>();
        for (String docId : docIds) {
            selected.addAll(this.chunksByDoc.getOrDefault(docId, List.of()));
        }
        return selected;
    }

    /**
     * 벡터 유사도 검색.
     *
     * @param docIds 검색 대상을 특정 문서로 제한 (비어 있으면 전체 문서)
     */
    public List<Document> search(String query, int topK, double similarityThreshold, Collection<String> docIds) {
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold);
        if (docIds != null && !docIds.isEmpty()) {
            // SimpleVectorStore도 메타데이터 필터를 지원한다 — PGVector에서 쓰던 문법 그대로
            String quoted = docIds.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + ", " + b).orElse("''");
            builder.filterExpression("docId in [" + quoted + "]");
        }
        return this.store.similaritySearch(builder.build());
    }

    public List<Document> search(String query, int topK, double similarityThreshold) {
        return search(query, topK, similarityThreshold, List.of());
    }

    /** 문서 하나와 그 청크 전부 삭제. */
    public synchronized boolean remove(String docId) {
        IndexedDocument removed = this.documents.remove(docId);
        List<Document> chunks = this.chunksByDoc.remove(docId);
        if (chunks != null && !chunks.isEmpty()) {
            this.store.delete(chunks.stream().map(Document::getId).toList());
        }
        return removed != null || chunks != null;
    }

    /** 지식 베이스 전체 초기화. */
    public synchronized void clear() {
        List<String> ids = allChunks().stream().map(Document::getId).toList();
        if (!ids.isEmpty()) {
            this.store.delete(ids);
        }
        this.documents.clear();
        this.chunksByDoc.clear();
        log.info("지식 베이스를 비웠습니다 (청크 {}개 삭제)", ids.size());
    }

    private Document restoreDocument(String id, String text, String metadataJson) {
        try {
            Map<String, Object> metadata = this.objectMapper.readValue(
                    metadataJson, new TypeReference<Map<String, Object>>() { });
            return Document.builder().id(id).text(text).metadata(metadata).build();
        }
        catch (Exception exception) {
            throw new IllegalStateException("PGVector 청크 메타데이터를 읽지 못했습니다: " + id, exception);
        }
    }

    private static IndexedDocument restoredDocument(String docId, List<Document> chunks) {
        Document first = chunks.getFirst();
        String fileName = stringMetadata(first, "fileName", "unknown");
        int inferredPages = chunks.stream().mapToInt(chunk -> intMetadata(chunk, "page", 0)).max().orElse(0) + 1;
        int inferredChars = chunks.stream().mapToInt(chunk -> chunk.getText().length()).sum();
        return new IndexedDocument(
                docId,
                fileName,
                stringMetadata(first, "fileType", fileName.toLowerCase().endsWith(".pdf") ? "pdf" : "text"),
                intMetadata(first, "pageCount", Math.max(1, inferredPages)),
                chunks.size(),
                intMetadata(first, "charCount", inferredChars),
                stringMetadata(first, "strategy", "PERSISTED"),
                intMetadata(first, "chunkSize", 0),
                intMetadata(first, "overlap", 0),
                longMetadata(first, "indexedAt", 0L),
                0L);
    }

    private static String stringMetadata(Document document, String key, String fallback) {
        Object value = document.getMetadata().get(key);
        return value == null ? fallback : value.toString();
    }

    private static int intMetadata(Document document, String key, int fallback) {
        Object value = document.getMetadata().get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static long longMetadata(Document document, String key, long fallback) {
        Object value = document.getMetadata().get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }
}

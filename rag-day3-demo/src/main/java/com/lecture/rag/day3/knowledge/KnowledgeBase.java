package com.lecture.rag.day3.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;

/**
 * 이 앱의 지식 베이스. 업로드된 문서들의 청크를 한 곳에 모아두고 검색을 제공한다.
 *
 * <p>Day1 Lab1.4는 "PDF 하나 = VectorStore 하나"였고 새 파일을 올리면 이전 걸 통째로 갈아끼웠다.
 * 캡스톤은 여러 문서를 동시에 얹고 문서별로 지우는 게 가능해야 하므로, VectorStore 하나를 계속 쓰면서
 * 어떤 청크가 어떤 문서 소속인지 {@code docId} 메타데이터로 관리한다.
 *
 * <p><b>인덱스는 디스크에 남습니다</b> — 문서를 올리거나 지울 때마다 {@code app.index.dir}(기본 {@code index/})에
 * 벡터와 문서 메타를 쓰고, 기동할 때 다시 읽습니다. 원래는 메모리에만 두고 재시작하면 사라지는 구조였습니다.
 *
 * <p><b>PGVector로 바꾸고 싶다면</b>(Day2에서 쓴 것): pom.xml에
 * {@code spring-ai-starter-vector-store-pgvector}를 추가하고 이 클래스의 {@code store} 초기화를
 * 주입받은 {@code VectorStore} 빈으로 바꾸면 된다. 나머지 코드는 그대로 동작한다 —
 * {@code VectorStore} 인터페이스에만 의존하도록 짜여 있기 때문이다.
 */
@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final EmbeddingModel embeddingModel;

    /** 인터페이스에만 의존합니다. pgvector 프로필에서는 여기에 PGVector 빈이 들어옵니다. */
    private final VectorStore store;

    /** docId -> 문서 메타. 업로드 순서를 유지하려고 LinkedHashMap. */
    private final Map<String, IndexedDocument> documents = new LinkedHashMap<>();

    /**
     * 청크 원본 보관소. VectorStore는 "질문과 비슷한 것"만 돌려주고 전체 목록을 주지 않기 때문에,
     * 키워드 검색(하이브리드 검색) 같은 걸 직접 구현하려면 전체 청크를 따로 들고 있어야 한다.
     */
    private final Map<String, List<Document>> chunksByDoc = new LinkedHashMap<>();

    /**
     * 인덱스를 남겨둘 위치입니다. {@code SimpleVectorStore.save}는 벡터와 청크만 담고 우리가 따로 들고 있는
     * {@link IndexedDocument} 목록은 담지 않아서, 문서 메타를 옆에 JSON으로 같이 씁니다.
     */
    private final Path storeFile;
    private final Path metaFile;
    private final ObjectMapper json = new ObjectMapper();

    /** 청크를 파일에 담기 위한 최소 형태입니다. id를 그대로 살려야 중복 제거와 문서 삭제가 유지됩니다. */
    private record StoredChunk(String id, String text, Map<String, Object> metadata) {

        static StoredChunk of(Document document) {
            return new StoredChunk(document.getId(), document.getText(), document.getMetadata());
        }

        Document toDocument() {
            return Document.builder().id(this.id).text(this.text).metadata(this.metadata).build();
        }
    }

    /** 문서 메타와 청크를 한 파일에 같이 씁니다. */
    private record StoredIndex(List<IndexedDocument> documents, Map<String, List<StoredChunk>> chunks) {
    }

    public KnowledgeBase(EmbeddingModel embeddingModel, VectorStore store,
            @Value("${app.index.dir:index}") String indexDir) {
        this.embeddingModel = embeddingModel;
        this.store = store;
        this.storeFile = Path.of(indexDir, "vectors.json");
        this.metaFile = Path.of(indexDir, "documents.json");
    }

    /** PGVector는 DB가 인덱스를 들고 있어서 파일로 또 저장할 이유가 없습니다. */
    private SimpleVectorStore fileBackedStore() {
        return this.store instanceof SimpleVectorStore simple ? simple : null;
    }

    @PostConstruct
    synchronized void restore() {
        SimpleVectorStore simple = fileBackedStore();
        if (simple == null || !Files.exists(this.storeFile) || !Files.exists(this.metaFile)) {
            return;
        }
        try {
            simple.load(this.storeFile.toFile());
            StoredIndex stored = this.json.readValue(this.metaFile, StoredIndex.class);
            for (IndexedDocument document : stored.documents()) {
                this.documents.put(document.docId(), document);
            }
            stored.chunks().forEach((docId, chunks) -> this.chunksByDoc.put(docId,
                    new ArrayList<>(chunks.stream().map(StoredChunk::toDocument).toList())));
            log.info("인덱스를 복원했습니다 (문서 {}개, 청크 {}개)", this.documents.size(), totalChunks());
        }
        catch (Exception exception) {
            log.warn("인덱스 복원 실패 — 빈 상태로 시작합니다", exception);
            this.documents.clear();
            this.chunksByDoc.clear();
        }
    }

    /** 매 변경마다 통째로 다시 씁니다. 실습 규모(문서 수십 개)에서는 증분 저장이 과합니다. */
    private void persist() {
        SimpleVectorStore simple = fileBackedStore();
        if (simple == null) {
            return;
        }
        try {
            Files.createDirectories(this.storeFile.getParent());
            simple.save(this.storeFile.toFile());
            Map<String, List<StoredChunk>> chunks = new LinkedHashMap<>();
            this.chunksByDoc.forEach((docId, list) ->
                    chunks.put(docId, list.stream().map(StoredChunk::of).toList()));
            this.json.writeValue(this.metaFile,
                    new StoredIndex(List.copyOf(this.documents.values()), chunks));
        }
        catch (Exception exception) {
            log.warn("인덱스 저장 실패 — 이번 실행에서만 유지됩니다", exception);
        }
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
        // 인덱싱이 끝나는 시점이라 여기서 한 번만 저장하면 addChunks 배치마다 쓰지 않아도 됩니다
        persist();
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
        persist();
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
        persist();
        log.info("지식 베이스를 비웠습니다 (청크 {}개 삭제)", ids.size());
    }
}

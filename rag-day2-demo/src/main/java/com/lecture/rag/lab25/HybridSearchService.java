package com.lecture.rag.lab25;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dense(벡터 코사인 유사도) + Sparse(PostgreSQL 전문 검색) 하이브리드 검색.
 *
 * Dense만 쓰면 "E2", "MCM-200" 같은 정확한 고유명사/코드는 의미상 특별하지 않아서
 * 놓칠 수 있고, Sparse(키워드)만 쓰면 동의어/의역을 못 잡는다 — 둘을 합쳐서 보완한다.
 *
 * 두 검색은 점수 스케일이 다르므로(코사인 유사도 0~1 vs ts_rank_cd 값) 점수를 직접
 * 더하지 않고, Reciprocal Rank Fusion(RRF)으로 순위만 가지고 합친다.
 *   RRF score = sum( 1 / (k + rank) ), k=60 (관례적으로 쓰는 상수, rank는 1부터 시작)
 * 두 리스트 모두에서 상위권일수록 최종 점수가 높아진다.
 */
@Component
public class HybridSearchService {

    private static final int RRF_K = 60;

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public HybridSearchService(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Document> hybridSearch(String query, String topic, int topK) {
        List<Document> denseResults = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK * 3)
                        .similarityThresholdAll()
                        .filterExpression("topic == '" + topic + "'")
                        .build());

        List<Document> sparseResults = keywordSearch(query, topic, topK * 3);

        return fuse(denseResults, sparseResults, topK);
    }

    /** PostgreSQL 전문 검색 — 'simple' 설정은 어간 추출 없이 토큰만 나누므로 한글에도 안전하게 쓸 수 있다. */
    private List<Document> keywordSearch(String query, String topic, int limit) {
        String sql = """
                SELECT id, content,
                       ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS rank
                FROM vector_store
                WHERE metadata ->> 'topic' = ?
                  AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
                ORDER BY rank DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql,
                (rs, rowNum) -> Document.builder()
                        .id(rs.getString("id"))
                        .text(rs.getString("content"))
                        .build(),
                query, topic, query, limit);
    }

    private List<Document> fuse(List<Document> dense, List<Document> sparse, int topK) {
        Map<String, Double> scoreById = new HashMap<>();
        Map<String, Document> docById = new HashMap<>();

        addRankScores(dense, scoreById, docById);
        addRankScores(sparse, scoreById, docById);

        return scoreById.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> docById.get(entry.getKey()))
                .toList();
    }

    private void addRankScores(List<Document> docs, Map<String, Double> scoreById, Map<String, Document> docById) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);
            scoreById.merge(doc.getId(), score, Double::sum);
            docById.putIfAbsent(doc.getId(), doc);
        }
    }
}

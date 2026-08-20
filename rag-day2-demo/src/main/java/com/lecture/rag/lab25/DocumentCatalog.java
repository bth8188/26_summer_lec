package com.lecture.rag.lab25;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Lab 2.5 — 문서의 서지정보를 담는 관계 테이블. 벡터 스토어가 못 하는 일을 맡는다.
 *
 * "위키 문서 몇 개야?", "어떤 자료들 갖고 있어?" 같은 질문은 임베딩으로 답할 수 없다.
 * 개수 세기, 목록 나열, 카테고리 필터링은 SQL의 영역이다.
 * 벡터 테이블의 metadata에 document_id를 심어두고 이 테이블의 id와 이어서,
 * 검색된 청크가 어느 문서 것인지 되짚거나 특정 문서로 검색 범위를 좁힌다.
 */
public class DocumentCatalog {

    /** 카탈로그에 올릴 문서 목록. 파일명에서 제목을 유추하지 않고 명시적으로 적는다. */
    public record Entry(String fileName, String title, String category) {}

    public static final List<Entry> ENTRIES = List.of(
            new Entry("1-ecommerce-manual.pdf", "이커머스 서비스 매뉴얼", "manual"),
            new Entry("2-devdocs-agentic-rag.pdf",
                    "SoK: Agentic Retrieval-Augmented Generation (RAG)", "research"),
            new Entry("3-research-llm-agent-eval.pdf",
                    "Survey on Evaluation of LLM-based Agents", "research"),
            new Entry("4-terms-startuprecipe.txt", "스타트업 레시피 서비스 이용약관", "terms"),
            new Entry("5-wiki-nhis.txt", "국민건강보험 (위키백과)", "wiki"),
            new Entry("6-wiki-jeju.txt", "제주도 (위키백과)", "wiki"),
            new Entry("7-wiki-kimchi.txt", "김치 (위키백과)", "wiki"),
            new Entry("8-opensource-spring-ai-readme.md", "Spring AI README", "opensource"));

    private final JdbcTemplate jdbc;

    public DocumentCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * PgVectorStore의 initialize-schema가 벡터 테이블만 만들어주므로 이 테이블은 직접 만든다.
     * 강의에서 흐름을 눈으로 따라갈 수 있도록 schema.sql 대신 코드에 둔다.
     */
    public void createTableIfMissing() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS document (
                    id          SERIAL PRIMARY KEY,
                    file_name   TEXT NOT NULL UNIQUE,
                    title       TEXT NOT NULL,
                    category    TEXT NOT NULL,
                    doc_type    TEXT NOT NULL,
                    char_count  INT  NOT NULL,
                    chunk_count INT  NOT NULL,
                    indexed_at  TIMESTAMP NOT NULL DEFAULT now()
                )
                """);
    }

    public boolean isEmpty() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM document", Integer.class);
        return count == null || count == 0;
    }

    /** 행을 넣고 생성된 id를 돌려준다 — 이 id가 벡터 metadata에 박히는 조인 키다. */
    public int insert(Entry entry, int charCount, int chunkCount) {
        String docType = entry.fileName().substring(entry.fileName().lastIndexOf('.') + 1);
        Integer id = jdbc.queryForObject("""
                INSERT INTO document (file_name, title, category, doc_type, char_count, chunk_count)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Integer.class,
                entry.fileName(), entry.title(), entry.category(), docType, charCount, chunkCount);
        return id == null ? -1 : id;
    }

    /** category가 null이면 전체 목록. LLM이 SQL을 쓰지 않고 인자만 넘기므로 인젝션 여지가 없다. */
    public List<Map<String, Object>> list(String category) {
        if (category == null || category.isBlank()) {
            return jdbc.queryForList(
                    "SELECT id, title, category, doc_type, chunk_count FROM document ORDER BY id");
        }
        return jdbc.queryForList(
                "SELECT id, title, category, doc_type, chunk_count FROM document WHERE category = ? ORDER BY id",
                category);
    }

    public List<Map<String, Object>> categorySummary() {
        return jdbc.queryForList(
                "SELECT category, count(*) AS document_count FROM document GROUP BY category ORDER BY category");
    }

    /** 제목/파일명에 키워드가 들어간 문서를 찾는다 — 본문 검색을 특정 문서로 좁힐 때 쓸 id를 얻는 용도. */
    public List<Map<String, Object>> findByKeyword(String keyword) {
        return jdbc.queryForList("""
                SELECT id, title, category, doc_type, chunk_count FROM document
                WHERE title ILIKE ? OR file_name ILIKE ?
                ORDER BY id
                """, "%" + keyword + "%", "%" + keyword + "%");
    }

    /** 단건 조회 — 없으면 null. */
    public Map<String, Object> findById(int id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, category, doc_type, char_count, chunk_count FROM document WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 벡터 검색 결과의 document_id를 사람이 읽을 수 있는 출처 문자열로 바꾼다. */
    public String titleOf(int documentId) {
        List<String> titles = jdbc.queryForList(
                "SELECT title FROM document WHERE id = ?", String.class, documentId);
        return titles.isEmpty() ? "(알 수 없는 문서)" : titles.get(0);
    }
}

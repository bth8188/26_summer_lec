package com.lecture.rag.dbquery;

import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * vector_store 테이블을 에이전트가 직접 조회할 수 있게 노출하는 도구.
 * @Component가 아니라 평범한 클래스라서 어느 데모에서든 JdbcTemplate만 있으면
 * import 후 new VectorStoreDbTool(jdbcTemplate)로 만들어서 .tools(...)/.defaultTools(...)에 넣어 쓰면 된다.
 */
public class VectorStoreDbTool {

    private final JdbcTemplate jdbcTemplate;

    public VectorStoreDbTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(description = "vector_store 테이블에 저장된 문서 수를 메타데이터 키 기준으로 집계한다. "
            + "대표적인 키: file_name(PDF 파일명), source(커스텀 인덱서가 넣은 값, 없으면 null)")
    public String countByMetadata(@ToolParam(description = "집계할 메타데이터 키. 예: file_name, source") String metadataKey) {
        // GROUP BY에 metadata->>? 를 그대로 또 쓰면 JDBC가 매번 별개 파라미터($1, $2)로 바인딩해서
        // Postgres가 "GROUP BY에 없는 컬럼" 에러를 던진다 — 서브쿼리로 한 번만 바인딩하고 별칭으로 묶는다.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT k, count(*) AS cnt FROM (SELECT metadata->>? AS k FROM vector_store) t "
                        + "GROUP BY k ORDER BY cnt DESC",
                metadataKey);

        if (rows.isEmpty()) return "집계 결과가 없습니다.";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append(row.get("k")).append(": ").append(row.get("cnt")).append("건\n");
        }
        return sb.toString();
    }

    @Tool(description = "vector_store 테이블의 content 컬럼에서 특정 키워드가 포함된 문서를 최대 10건 검색한다.")
    public String searchContent(@ToolParam(description = "검색할 키워드") String keyword) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT metadata->>'file_name' AS file_name, metadata->>'source' AS source, "
                        + "left(content, 120) AS preview FROM vector_store WHERE content ILIKE ? LIMIT 10",
                "%" + keyword + "%");

        if (rows.isEmpty()) return "검색 결과가 없습니다.";

        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append("- [file_name=").append(row.get("file_name"))
                    .append(", source=").append(row.get("source")).append("] ")
                    .append(row.get("preview")).append("...\n");
        }
        return sb.toString();
    }
}

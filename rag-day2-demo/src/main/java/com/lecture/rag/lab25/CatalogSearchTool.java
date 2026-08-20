package com.lecture.rag.lab25;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;

/**
 * Lab 2.5 — 관계 테이블(document)을 조회하는 도구. "무슨 자료가 있냐"류 질문 담당.
 *
 * LLM이 SQL 문자열을 만들지 않고 인자만 넘긴다(파라미터 조회). 실제 쿼리는 DocumentCatalog가
 * 미리 짜둔 것을 쓴다 — 인젝션 여지가 없고, 소형 모델도 쓸 수 있다.
 * Text-to-SQL은 모델이 SQL을 직접 쓰는 방식인데 개념은 같은 자리에 있고 신뢰성만 다르다.
 */
public class CatalogSearchTool {

    private final DocumentCatalog catalog;
    private int callCount = 0;

    public CatalogSearchTool(DocumentCatalog catalog) {
        this.catalog = catalog;
    }

    public int getCallCount() {
        return callCount;
    }

    // returnDirect = true — 조회 결과 자체가 곧 답이므로 LLM에게 다시 쓰게 하지 않고 그대로 반환한다.
    // llama3.2:3b는 8건짜리 목록을 받아도 답변에 옮겨 적지 못하고 "다음과 같습니다"로 끝내버린다(실측).
    // 목록/집계처럼 정답이 확정된 결과는 모델을 거칠수록 손해다.
    @Tool(returnDirect = true, resultConverter = PlainTextResultConverter.class,
            description = "보유한 문서의 목록과 서지정보를 조회한다. "
            + "'무슨 자료 있어?', '위키 문서 몇 개야?', '연구 논문 보여줘'처럼 "
            + "문서의 내용이 아니라 문서 자체에 대해 묻는 질문에 사용할 것.")
    public String listDocuments(
            @ToolParam(required = false,
                    description = "카테고리로 좁히려면 지정. manual, research, terms, wiki, opensource 중 하나. "
                            + "전체 목록을 보려면 비워둘 것.")
            String category) {
        callCount++;
        System.out.println("  >>> [도구 호출됨] listDocuments(" + (category == null ? "전체" : category) + ")");

        List<Map<String, Object>> rows = catalog.list(category);
        if (rows.isEmpty()) {
            return "해당 조건의 문서가 없습니다. 사용 가능한 카테고리: " + catalog.categorySummary();
        }
        return "총 " + rows.size() + "건\n" + format(rows);
    }

    @Tool(returnDirect = true, resultConverter = PlainTextResultConverter.class,
            description = "카테고리별로 문서가 몇 건씩 있는지 집계한다. "
            + "'어떤 종류의 자료가 있어?' 같은 질문에 사용할 것.")
    public String countByCategory() {
        callCount++;
        System.out.println("  >>> [도구 호출됨] countByCategory()");
        return format(catalog.categorySummary());
    }

    @Tool(description = "제목이나 파일명에 키워드가 들어간 문서를 찾아 문서 번호(id)를 알려준다. "
            + "특정 문서 안에서만 본문을 찾고 싶을 때, 먼저 이걸로 id를 얻은 뒤 searchContent에 넘길 것.")
    public String findDocument(
            @ToolParam(description = "문서 제목에 들어갈 만한 키워드. 예: 제주, 김치, 약관, Spring AI")
            String keyword) {
        callCount++;
        System.out.println("  >>> [도구 호출됨] findDocument(\"" + keyword + "\")");

        List<Map<String, Object>> rows = catalog.findByKeyword(keyword);
        if (rows.isEmpty()) {
            return "\"" + keyword + "\"에 해당하는 문서를 찾지 못했습니다.";
        }
        return format(rows);
    }

    // Map.toString()을 그대로 넘기면 "{id=1, title=...}" 같은 형태라 소형 모델이 답변으로 옮겨 적지 못한다.
    // 사람이 읽는 문장에 가깝게 만들어줄수록 최종 답변 품질이 올라간다.
    private String format(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            if (row.containsKey("document_count")) {
                sb.append("- ").append(row.get("category"))
                  .append(": ").append(row.get("document_count")).append("건\n");
            } else {
                sb.append("- ").append(row.get("id")).append("번 ")
                  .append(row.get("title"))
                  .append(" (분류: ").append(row.get("category"))
                  .append(", 형식: ").append(row.get("doc_type"))
                  .append(", ").append(row.get("chunk_count")).append("청크)\n");
            }
        }
        return sb.toString();
    }
}

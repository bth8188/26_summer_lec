package com.lecture.rag.lab25;

import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Lab 2.5 — 문서 본문을 벡터 검색하는 도구. "문서 안에 뭐라고 쓰여 있냐"류 질문 담당.
 *
 * documentId를 받으면 그 문서 안에서만 찾는다(metadata 필터). 카탈로그 도구로 id를 먼저 얻고
 * 여기에 넘기는 흐름이 관계 테이블과 벡터 스토어를 잇는 지점이다.
 * 결과에는 document_id로 되짚은 문서 제목을 붙여 출처를 밝힌다.
 */
public class ContentSearchTool {

    static final String DOCUMENT_ID = "document_id";

    private final VectorStore vectorStore;
    private final DocumentCatalog catalog;
    private final double similarityThreshold;
    private int callCount = 0;

    public ContentSearchTool(VectorStore vectorStore, DocumentCatalog catalog, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.catalog = catalog;
        this.similarityThreshold = similarityThreshold;
    }

    public int getCallCount() {
        return callCount;
    }

    @Tool(description = "문서 본문에서 질문과 관련된 내용을 찾는다. "
            + "'제주도 면적이 얼마야?', '환불 조항 알려줘'처럼 문서 안에 적힌 내용을 묻는 질문에 사용할 것. "
            + "어떤 문서가 있는지 묻는 질문에는 쓰지 말 것.")
    public String searchContent(
            @ToolParam(description = "본문에서 찾을 질문 또는 키워드")
            String query,
            @ToolParam(required = false,
                    description = "특정 문서 안에서만 찾으려면 findDocument로 얻은 문서 번호(id)를 넣을 것. "
                            + "전체 문서를 대상으로 찾으려면 비워둘 것.")
            Integer documentId) {
        callCount++;
        System.out.println("  >>> [도구 호출됨] searchContent(\"" + query + "\", documentId="
                + (documentId == null ? "전체" : documentId) + ")");

        SearchRequest.Builder request = SearchRequest.builder()
                .query(query)
                .topK(3)
                .similarityThreshold(similarityThreshold);
        if (documentId != null) {
            request.filterExpression(DOCUMENT_ID + " == " + documentId);
        }

        List<Document> results = vectorStore.similaritySearch(request.build());
        if (results.isEmpty()) {
            return "관련 내용을 문서에서 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (Document doc : results) {
            // 조인 키로 서지정보를 되짚어 출처를 밝힌다 — 벡터 스토어만으로는 못 하는 부분
            int docId = ((Number) doc.getMetadata().get(DOCUMENT_ID)).intValue();
            sb.append("- [출처: ").append(catalog.titleOf(docId)).append("] ")
              .append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }
}

package com.lecture.rag.lab24;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Lab 2.4 — 제주도 문서를 "LLM이 필요할 때만 호출하는 도구"로 노출한다.
 * lab23의 DocumentSearchTool을 그대로 본떴고, 달라진 점은 두 가지다.
 *  1) filterExpression으로 제주 문서만 본다 — 같은 테이블에 김치 문서도 들어있기 때문.
 *  2) similarityThreshold를 걸어, 무관한 질문이면 진짜로 빈손이 되게 한다.
 *     (기본값 0이면 아무 질문에나 topK가 꽉 채워져 돌아와서 "모른다"가 나올 수 없다)
 */
public class JejuSearchTool {

    static final String SOURCE = "jeju";

    private final VectorStore vectorStore;
    private final double similarityThreshold;
    private int callCount = 0;

    public JejuSearchTool(VectorStore vectorStore, double similarityThreshold) {
        this.vectorStore = vectorStore;
        this.similarityThreshold = similarityThreshold;
    }

    public int getCallCount() {
        return callCount;
    }

    @Tool(description = "제주도 위키백과 문서에서 질문과 관련된 내용을 검색한다. "
            + "제주도의 지리, 면적, 인구, 기후, 화산 형성 역사, 올레길 같은 관광지 등 "
            + "제주도에 대한 질문에만 사용할 것.")
    public String searchJeju(@ToolParam(description = "제주도 문서에서 검색할 질문 또는 키워드") String query) {
        callCount++;
        System.out.println("  >>> [도구 호출됨] searchJeju(\"" + query + "\")");

        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .similarityThreshold(similarityThreshold)
                        .filterExpression("source == '" + SOURCE + "'")
                        .build());

        if (results.isEmpty()) {
            return "제주도 문서에서 관련 내용을 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (var doc : results) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }
}

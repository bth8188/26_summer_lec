package com.lecture.rag.lab25;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 제주도 위키 문서를 "LLM이 필요할 때만 호출하는 도구"로 노출한 것 (lab23의 DocumentSearchTool과 동일 패턴).
 * topic == 'jeju' 메타데이터로 필터링해서, 같은 vector_store 테이블에 있는
 * kimchi-wiki / manual.pdf 등 다른 문서 내용과 섞이지 않게 한다.
 * 검색은 HybridSearchService(Dense+Sparse RRF)를 사용한다.
 */
public class JejuSearchTool {

    private final HybridSearchService hybridSearchService;
    private int callCount = 0;

    public JejuSearchTool(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    public int getCallCount() {
        return callCount;
    }

    @Tool(description = "제주도 위키 문서에서 질문과 관련된 내용을 검색한다. "
            + "제주도의 지리, 역사, 기후, 인구 등 제주도에 대한 질문에만 사용할 것.")
    public String searchJeju(@ToolParam(description = "제주도 문서에서 검색할 질문 또는 키워드") String query) {
        callCount++;
        System.out.println("  >>> [도구 호출됨] searchJeju(\"" + query + "\")");

        var results = hybridSearchService.hybridSearch(query, "jeju", 3);

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

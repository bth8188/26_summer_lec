package com.lecture.rag.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 제주 위키 문서를 "LLM이 필요할 때만 호출하는 도구"로 노출한다.
 * QuestionAnswerAdvisor(kimchi)와 달리, 이 도구는 모델이 스스로
 * "이 질문엔 제주 문서 검색이 필요하다"고 판단했을 때만 실제로 호출된다.
 */
public class JejuWikiSearchTool {

    private static final String SOURCE = "jeju";

    private final VectorStore vectorStore;

    public JejuWikiSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(description = "제주도 위키 문서에서 질문과 관련된 내용을 검색한다. "
            + "제주도의 역사, 지리, 기후, 문화, 관광, 방언 등 제주도 관련 질문에만 사용할 것.")
    public String searchJejuWiki(@ToolParam(description = "제주 위키 문서에서 검색할 질문 또는 키워드") String query) {
        System.out.println("  >>> [도구 호출됨] searchJejuWiki(\"" + query + "\")");

        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .filterExpression("source == '" + SOURCE + "'")
                        .build());

        if (results.isEmpty()) {
            return "제주 위키 문서에서 관련 내용을 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (var doc : results) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }
}

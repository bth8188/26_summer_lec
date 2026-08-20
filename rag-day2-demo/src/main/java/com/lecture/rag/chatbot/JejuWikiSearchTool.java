package com.lecture.rag.chatbot;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * 제주도 위키를 "항상 실행되는 Advisor"가 아니라 모델이 필요할 때만 호출하는 도구로 제공한다.
 */
public class JejuWikiSearchTool {

    private final VectorStore vectorStore;

    public JejuWikiSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Tool(description = "제주도 위키 문서에서 질문과 관련된 내용을 검색한다. "
            + "제주도의 지리, 역사, 문화, 관광 등 제주도 관련 질문에만 사용할 것.")
    public String searchJejuWiki(@ToolParam(description = "제주도 위키에서 검색할 질문 또는 키워드") String query) {
        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(3)
                        .filterExpression("source == '" + WikiIndexer.JEJU_SOURCE + "'")
                        .similarityThreshold(0.5)
                        .build());

        if (results.isEmpty()) {
            return "제주도 위키에서 관련 내용을 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (var doc : results) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }
}

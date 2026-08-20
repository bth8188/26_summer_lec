package com.lecture.rag.lab24;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

public class JejuWikiTool {

    private final VectorStore vectorStore;
    private final double threshold;
    private final String indexVersion;

    public JejuWikiTool(
            VectorStore vectorStore,
            double threshold,
            String indexVersion) {

        this.vectorStore = vectorStore;
        this.threshold = threshold;
        this.indexVersion = indexVersion;
    }

    @Tool(
            description = """
                    제주도 관련 정보를 제주 위키에서 검색한다.
                    제주도의 지리, 역사, 관광지, 기후 등
                    제주와 관련된 질문에만 사용한다.
                    """
    )
    public String searchJeju(
            @ToolParam(
                    description = "제주 위키에서 검색할 질문 또는 검색어"
            )
            String query) {

        System.out.println(
                ">>> [JejuWikiTool 호출] query = " + query
        );

        var results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(5)
                        .similarityThreshold(threshold)
                        .filterExpression(
                                "wiki == 'jeju'"
                                        + " && indexVersion == '" + indexVersion + "'"
                        )
                        .build()
        );

        if (results.isEmpty()) {
            return "제주 위키에서 관련 정보를 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();

        for (var doc : results) {
            sb.append("- ")
                    .append(
                            doc.getText()
                                    .replaceAll("\\s+", " ")
                                    .trim()
                    )
                    .append("\n");
        }

        return sb.toString();
    }
}
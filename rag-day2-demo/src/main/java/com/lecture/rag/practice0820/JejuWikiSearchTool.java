package com.lecture.rag.practice0820;

// jeju-wiki는 도구로 제공
// @Tool 메서드 제공
// 제주 문서는 PGVector에서 검색하고
// 검색 결과가 없으면 모른다고 말하기 (근거 못 찾았다고) 사전지식으로 말하면 안 됨!!

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class JejuWikiSearchTool {

    private static final Logger log = LoggerFactory.getLogger(JejuWikiSearchTool.class);

    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final int TOP_K = 5;
    private static final List<String> JEJU_KEYWORDS = List.of(
            "제주", "한라산", "성산일출봉", "성산 일출봉", "올레길",
            "만장굴", "용두암", "천제연", "김녕", "수월봉", "송악산"
    );

    private final VectorStore vectorStore;

    private int callCount = 0;
    private final List<String> evidenceTexts = new ArrayList<>();

    public JejuWikiSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public int getCallCount() {
        return callCount;
    }

    public boolean supports(String question) {
        return question != null
                && !question.contains("김치")
                && JEJU_KEYWORDS.stream().anyMatch(question::contains);
    }

    public void clearEvidence() {
        evidenceTexts.clear();
    }

    public boolean hasEvidence() {
        return !evidenceTexts.isEmpty();
    }

    public String getEvidenceText() {
        return String.join("\n\n", evidenceTexts);
    }

    @Tool(
            description = """
                    제주도의 지리, 면적, 형태, 인구, 기후, 날씨, 역사, 화산 활동과 형성 과정, 관광에 관한 위키 문서를 검색한다. 제주도에 관련이 있는 질문일 때 사용한다.
                    """
    )

    public String searchJejuWiki(@ToolParam(description = "제주 위키 문서에서 검색할 질문 또는 핵심 키워드") String query) {
        callCount++;

        log.debug("제주 Wiki Tool 호출: searchJejuWiki(\"{}\")", query);

        var results = vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(TOP_K).similarityThreshold(SIMILARITY_THRESHOLD).filterExpression("source == 'jeju'").build());

        if (results.isEmpty()) {
            return "제주 위키 문서에서 관련 근거를 찾지 못했습니다.";
        }

        results.stream()
                .map(document -> document.getText())
                .filter(text -> text != null && !text.isBlank())
                .filter(text -> !evidenceTexts.contains(text))
                .forEach(evidenceTexts::add);

        String evidence = results.stream()
                .map(document -> "[관련 근거]\n" + document.getText())
                .collect(Collectors.joining("\n\n"));

        return """
                다음은 사용자 질문에 답하기 위해 검색한 제주 위키 근거입니다.
                아래 근거에서 질문의 답을 찾아 구체적인 답변 문장을 작성하세요.
                Tool 이름만 출처로 출력하지 마세요.

                %s

                [정확한 출처명]
                제주 위키
                """.formatted(evidence);
    }
}

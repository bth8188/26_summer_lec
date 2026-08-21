package com.lecture.rag.practice0820;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Profile("wiki-rag-api")
@RequestMapping("/api/wiki-rag")
@Tag(
        name = "Wiki RAG 챗봇",
        description = "김치 Wiki Advisor와 제주 Wiki Tool을 함께 사용하는 RAG API"
)
public class WikiRagApiController {

    private static final String UNKNOWN_ANSWER = "관련 문서에서 답을 찾지 못했습니다.";
    private static final int TOP_K = 5;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor kimchiAdvisor;
    private final GroundedAnswerGenerator groundedAnswerGenerator;

    public WikiRagApiController(ChatModel chatModel, VectorStore vectorStore) {
        this.vectorStore = vectorStore;
        this.groundedAnswerGenerator = new GroundedAnswerGenerator(chatModel);
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        항상 한국어로 답변하세요.
                        제공된 제주 위키와 김치 위키 문서만 근거로 답변하세요.
                        제주도에 관한 질문에는 반드시 searchJejuWiki 도구를 사용하세요.
                        문서에서 관련 근거를 찾지 못하면 사전지식으로 추측하지 말고
                        "관련 문서에서 답을 찾지 못했습니다."라고 답하세요.
                        관련 근거가 있다면 반드시 질문에 대한 구체적인 답변 본문을 먼저 작성하세요.
                        출처만 단독으로 출력해서는 안 됩니다.
                        최종 답변은 다음 형식을 따르세요.

                        답변: 문서 근거에서 찾은 구체적인 답변
                        출처: 제주 위키 또는 김치 위키

                        searchJejuWiki 같은 Tool 이름은 출처가 아닙니다.
                        """)
                .build();
        this.kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(TOP_K)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .filterExpression("source == 'kimchi'")
                        .build())
                .build();
    }

    public record ChatResult(
            String question,
            String answer,
            boolean jejuToolCalled
    ) {
    }

    @Operation(
            summary = "제주·김치 Wiki 문서 기반 질문"
    )
    @GetMapping("/chat")
    public ChatResult chat(
            @Parameter(
                    description = "제주 또는 김치 Wiki 문서에 관한 질문"
            )
            @RequestParam String question
    ) {
        JejuWikiSearchTool jejuTool = new JejuWikiSearchTool(vectorStore);
        int callCountBefore = jejuTool.getCallCount();

        String answer;
        if (jejuTool.supports(question)) {
            boolean searchedWithOriginalQuestion = false;

            // 먼저 Spring AI의 정식 Tool Calling을 시도한다.
            chatClient.prompt()
                    .advisors(kimchiAdvisor)
                    .tools(jejuTool)
                    .user(question)
                    .call()
                    .content();

            // 소형 모델의 Tool Calling 실패 시 같은 Tool을 직접 실행한다.
            if (jejuTool.getCallCount() == callCountBefore) {
                jejuTool.searchJejuWiki(question);
                searchedWithOriginalQuestion = true;
            }

            answer = groundedAnswerGenerator.answer(
                    question,
                    jejuTool.getEvidenceText(),
                    "제주 위키"
            );

            // Tool이 검색어를 잘못 바꿔 관련 근거를 놓친 경우 원래 질문으로 재검색한다.
            if (UNKNOWN_ANSWER.equals(answer) && !searchedWithOriginalQuestion) {
                jejuTool.searchJejuWiki(question);
                answer = groundedAnswerGenerator.answer(
                        question,
                        jejuTool.getEvidenceText(),
                        "제주 위키"
                );
            }
        }
        else if (question.contains("김치")) {
            List<Document> kimchiEvidence = searchKimchiEvidence(question);
            if (kimchiEvidence.isEmpty()) {
                answer = UNKNOWN_ANSWER;
            }
            else {
                answer = chatClient.prompt()
                        .advisors(kimchiAdvisor)
                        .user(question)
                        .call()
                        .content();
            }
        }
        else {
            answer = UNKNOWN_ANSWER;
        }

        boolean toolCalled = jejuTool.getCallCount() > 0;

        return new ChatResult(
                question,
                answer,
                toolCalled
        );
    }

    private List<Document> searchKimchiEvidence(String question) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression("source == 'kimchi'")
                .build());
    }
}

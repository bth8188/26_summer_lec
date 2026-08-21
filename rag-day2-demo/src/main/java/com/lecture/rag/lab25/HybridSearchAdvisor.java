package com.lecture.rag.lab25;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * QuestionAnswerAdvisor와 같은 역할(매 질문마다 무조건 검색해서 프롬프트에 컨텍스트로 주입)이지만,
 * 내부 검색을 순수 벡터 유사도 대신 HybridSearchService(Dense+Sparse, RRF)로 바꾼 버전.
 */
public class HybridSearchAdvisor implements BaseAdvisor {

    private static final PromptTemplate DEFAULT_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            Context information is below, surrounded by ---------------------

            ---------------------
            {question_answer_context}
            ---------------------

            Given the context and provided history information and not prior knowledge,
            reply to the user comment. If the answer is not in the context, inform
            the user that you can't answer the question.
            """);

    private final HybridSearchService hybridSearchService;
    private final String topic;
    private final int topK;

    public HybridSearchAdvisor(HybridSearchService hybridSearchService, String topic, int topK) {
        this.hybridSearchService = hybridSearchService;
        this.topic = topic;
        this.topK = topK;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String query = Objects.requireNonNullElse(chatClientRequest.prompt().getUserMessage().getText(), "");

        List<Document> documents = hybridSearchService.hybridSearch(query, topic, topK);

        String documentContext = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining(System.lineSeparator()));

        UserMessage userMessage = chatClientRequest.prompt().getUserMessage();
        String augmentedUserText = DEFAULT_PROMPT_TEMPLATE
                .render(Map.of("query", userMessage.getText(), "question_answer_context", documentContext));

        return chatClientRequest.mutate()
                .prompt(chatClientRequest.prompt().augmentUserMessage(augmentedUserText))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    @Override
    public Scheduler getScheduler() {
        return BaseAdvisor.DEFAULT_SCHEDULER;
    }
}

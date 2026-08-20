package com.lecture.rag.lab24;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WikiRagService {

    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final String INDEX_VERSION = "paragraph-v1";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor kimchiAdvisor;
    private final JejuWikiTool jejuTool;

    public WikiRagService(
            VectorStore vectorStore,
            ChatModel chatModel) {

        this.vectorStore = vectorStore;

        SearchRequest kimchiSearchRequest = SearchRequest.builder()
                .topK(5)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .filterExpression(
                        "wiki == 'kimchi'"
                                + " && indexVersion == '" + INDEX_VERSION + "'"
                )
                .build();

        this.kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(kimchiSearchRequest)
                .build();

        this.jejuTool = new JejuWikiTool(
                vectorStore,
                SIMILARITY_THRESHOLD,
                INDEX_VERSION
        );

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        반드시 자연스러운 한국어만 사용해서 답변하세요.
                        당신은 김치 위키와 제주 위키의 내용만 답변하는 챗봇입니다.
                        제공된 문서와 도구 검색 결과에 있는 정보만 사용하세요.
                        
                        문서에 없는 사실이나 URL을 생성하거나 추측하지 마세요.
                        
                        검색 결과에서 답을 찾을 수 없다면
                        반드시 '모르겠습니다'라고만 답하세요.
                        """)
                .build();
    }

    public String ask(String question) {

        if (!hasRelevantDocument(question)) {
            return "모르겠습니다.";
        }

        return chatClient.prompt()
                .advisors(kimchiAdvisor)
                .tools(jejuTool)
                .user(question)
                .call()
                .content();
    }

    private boolean hasRelevantDocument(String question) {

        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(5)
                        .similarityThresholdAll()
                        .filterExpression(
                                "(wiki == 'kimchi' || wiki == 'jeju')"
                                        + " && indexVersion == '" + INDEX_VERSION + "'"
                        )
                        .build()
        );

        return results.stream()
                .anyMatch(doc ->
                        doc.getScore() >= SIMILARITY_THRESHOLD
                );
    }
}
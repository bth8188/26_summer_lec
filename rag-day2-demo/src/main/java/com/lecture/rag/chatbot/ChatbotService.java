package com.lecture.rag.chatbot;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 김치 위키는 QuestionAnswerAdvisor(항상 검색)로, 제주도 위키는 Tool(모델이 필요할 때만 검색)로 제공한다.
 * 콘솔(ChatbotConsoleRunner)과 REST API(ChatbotController)가 이 서비스를 공유해서 쓴다.
 */
@Component
@Profile("chatbot")
public class ChatbotService {

    private final ChatClient chatClient;
    private final QuestionAnswerAdvisor kimchiAdvisor;
    private final JejuWikiSearchTool jejuTool;

    public ChatbotService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("항상 한국어로 답변하세요. 검색된 내용이나 도구 결과에 관련 정보가 없으면 "
                        + "모른다고 솔직하게 답변하고 지어내지 마세요.")
                .build();
        this.kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .filterExpression("source == '" + WikiIndexer.KIMCHI_SOURCE + "'")
                        .similarityThreshold(0.5)
                        .build())
                .build();
        this.jejuTool = new JejuWikiSearchTool(vectorStore);
    }

    public String chat(String question) {
        return chatClient.prompt()
                .advisors(kimchiAdvisor, SimpleLoggerAdvisor.builder().build())
                .tools(jejuTool)
                .user(question)
                .call()
                .content();
    }
}

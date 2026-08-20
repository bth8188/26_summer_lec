package com.lecture.rag.lab25;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lab 2.5 — 하나의 챗봇에 두 검색 방식을 함께 장착:
 *  - kimchi-wiki: QuestionAnswerAdvisor (매 질문마다 무조건 검색해서 프롬프트에 주입)
 *  - jeju-wiki:   @Tool (JejuSearchTool — 모델이 필요하다고 판단할 때만 호출)
 *
 * 콘솔 버전(HybridChatbotConsoleDemo)과 Swagger 버전(HybridChatbotApiController)이
 * 이 서비스 하나를 공유한다 — lab13→lab14 관계와 동일하게, 로직은 한 곳에만 있고
 * 진입점만 다르다.
 */
@Component
public class HybridWikiChatbotService {

    private static final String NO_MATCH_ANSWER =
            "모르겠습니다. 제주도 또는 김치 위키 문서에서 관련 내용을 찾지 못했습니다.";

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final JejuSearchTool jejuSearchTool;

    public HybridWikiChatbotService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.jejuSearchTool = new JejuSearchTool(vectorStore);
    }

    /** lab21처럼 이미 인덱싱돼 있으면(topic별로) 다시 인덱싱하지 않는다. */
    public synchronized void ensureIndexed() {
        indexIfMissing("jeju", "classpath:/scenarios/6-wiki-jeju.txt");
        indexIfMissing("kimchi", "classpath:/scenarios/7-wiki-kimchi.txt");
    }

    private void indexIfMissing(String topic, String resourcePath) {
        boolean alreadyIndexed = !vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(topic)
                        .topK(1)
                        .similarityThresholdAll()
                        .filterExpression("topic == '" + topic + "'")
                        .build()).isEmpty();
        if (alreadyIndexed) {
            System.out.println("=== [" + topic + "] 이미 인덱싱됨 — 건너뜀 ===");
            return;
        }

        TextReader reader = new TextReader(resourcePath);
        reader.getCustomMetadata().put("topic", topic);
        List<Document> documents = reader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(300)
                .build();
        List<Document> chunks = splitter.apply(documents);

        vectorStore.add(chunks);
        System.out.println("=== [" + topic + "] 인덱싱 완료 — " + chunks.size() + "개 청크 ===");
    }

    /**
     * kimchi + jeju 두 토픽을 합쳐서 먼저 관련 내용이 있는지 확인하고,
     * 없으면 LLM을 아예 호출하지 않고 바로 "모르겠습니다"로 답한다.
     */
    public String chat(String question) {
        boolean hasRelevantContext = !vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(3)
                        .similarityThreshold(0.5) // day1 실측: bge-m3 기준 관련 0.6대 / 무관 0.4대 — 그 사이 컷오프
                        .filterExpression("topic IN ['kimchi', 'jeju']")
                        .build()).isEmpty();

        if (!hasRelevantContext) {
            return NO_MATCH_ANSWER;
        }

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("항상 한국어로 답변하세요. 컨텍스트나 도구 검색 결과에 관련 내용이 없으면 모른다고 답하세요.")
                .build();

        QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .filterExpression("topic == 'kimchi'")
                        .build())
                .build();

        return chatClient.prompt()
                .advisors(kimchiAdvisor)
                .tools(jejuSearchTool)
                .user(question)
                .call()
                .content();
    }
}

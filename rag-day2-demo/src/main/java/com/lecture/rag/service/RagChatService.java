package com.lecture.rag.service;

import com.lecture.rag.tool.JejuWikiSearchTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * kimchi-wiki는 QuestionAnswerAdvisor(질문마다 항상 자동 검색), jeju-wiki는 @Tool(모델이 필요하다고
 * 판단할 때만 검색)로 구성한 RAG ChatClient를 만들고 재사용하는 서비스.
 * CLI(RagDay2DemoApplication)와 REST API(RagChatController) 양쪽에서 이 서비스를 공유해서
 * ChatClient 구성 로직이 중복되지 않도록 한다.
 */
@Service
public class RagChatService {

    private static final String SOURCE_JEJU = "jeju";
    private static final String SOURCE_KIMCHI = "kimchi";

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagChatService(ChatModel chatModel, VectorStore vectorStore) {
        this.vectorStore = vectorStore;

        JejuWikiSearchTool jejuTool = new JejuWikiSearchTool(vectorStore);

        QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .filterExpression("source == '" + SOURCE_KIMCHI + "'")
                        .build())
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        너는 제주도 위키와 김치 위키 문서를 근거로 답변하는 어시스턴트다.
                        - 검색된 문서 내용에 근거해서만 답변하라.
                        - 아래 컨텍스트에 이미 질문에 대한 답이 포함되어 있다면, 절대 검색 도구를 호출하지 말고 그 내용만으로 답하라.
                        - 제주도에 관한 질문일 때만  제주 위키 검색 도구를 사용하라. 김치 관련 질문에는 절대 그 도구를 사용하지 마라.
                        - 관련 근거를 찾지 못했으면 절대 추측하지 말고 "모른다"고 솔직하게 답하라.
                        - 항상 한국어로 답변하라.
                        """)
                .defaultAdvisors(kimchiAdvisor, SimpleLoggerAdvisor.builder().build())
                .defaultTools(jejuTool)
                .build();
    }

    /** 이미 인덱싱되어 있으면 건너뛰고, 없으면 jeju/kimchi 위키를 source 태그를 붙여 인덱싱한다. */
    public void indexIfNeeded() {
        indexIfNeeded(SOURCE_JEJU, "classpath:/scenarios/6-wiki-jeju.pdf");
        indexIfNeeded(SOURCE_KIMCHI, "classpath:/scenarios/7-wiki-kimchi.pdf");
    }

    public String chat(String question) {
        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    private void indexIfNeeded(String source, String classpathLocation) {
        long existingCount = countExisting(source);
        if (existingCount > 0) {
            System.out.println("[" + source + "] 이미 인덱싱된 데이터가 " + existingCount + "건 있습니다 — 재인덱싱을 생략합니다.");
            return;
        }

        System.out.println("[" + source + "] 인덱싱을 시작합니다: " + classpathLocation);
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(classpathLocation);
        List<Document> documents = pdfReader.get();
        documents.forEach(doc -> doc.getMetadata().put("source", source));

        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(200)
                .build();
        List<Document> chunks = splitter.apply(documents);

        vectorStore.add(chunks);
        System.out.println("[" + source + "] 인덱싱 완료 — 청크 " + chunks.size() + "건 저장");
    }

    private long countExisting(String source) {
        List<Document> existing = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(source)
                        .topK(1000)
                        .similarityThresholdAll()
                        .filterExpression("source == '" + source + "'")
                        .build());
        return existing.size();
    }
}

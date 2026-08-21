package com.lecture.rag.m25query;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.lecture.rag.lab22.LlmReranker;

/**
 * M2.5 — Query Transformation. 사용자의 짧고 구어체인 질문을 Spring AI의 {@link RewriteQueryTransformer}로
 * 검색에 적합한 형태로 다듬는 걸 먼저 보여주고, 그다음 이걸 Lab2.2의 LlmReranker(rerank)와 함께
 * RetrievalAugmentationAdvisor로 조립해서 "Query Transform → Retrieve(넓게) → Rerank(좁게) → Generate"
 * 전체 파이프라인을 QuestionAnswerAdvisor만 쓰는 Day1식 단순 버전과 비교한다.
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=query-transform
 */
@Component
@Profile("query-transform")
public class QueryTransformDemo implements CommandLineRunner {

    private static final List<String> MESSY_QUERIES = List.of(
            "환불 며칠걸림?",
            "이거 물 얼마나 들어가?",
            "E2 그거 뭔 뜻?"
    );

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public QueryTransformDemo(ChatModel chatModel, EmbeddingModel embeddingModel) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void run(String... args) {
        RewriteQueryTransformer rewriteQueryTransformer = RewriteQueryTransformer.builder()
                .chatClientBuilder(ChatClient.builder(chatModel)
                        // 로컬 소형 모델(llama3.2:3b)은 지시를 안 따르고 설명을 덧붙이는 경우가 많아 강하게 제약
                        .defaultSystem("재작성된 검색어 한 줄만 한국어로 답하세요. 설명, 번역, 이유는 절대 쓰지 마세요."))
                .targetSearchSystem("전자제품 사용설명서 벡터 검색 시스템")
                .build();

        System.out.println("################ 1. QueryTransformer 단독 — 원본 질문 vs 재작성된 질문 ################");
        for (String raw : MESSY_QUERIES) {
            Query rewritten = rewriteQueryTransformer.transform(new Query(raw));
            System.out.println("원본  : " + raw);
            System.out.println("재작성: " + rewritten.text());
            System.out.println();
        }

        VectorStore vectorStore = buildVectorStore();
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("항상 한국어로 답변하세요.")
                .build();
        String query = MESSY_QUERIES.get(0); // "환불 며칠걸림?" — 매뉴얼에 환불 기한이 14일/30일 두 가지라 애매함이 잘 드러남

        System.out.println("################ 2. Day1식 단순 RAG (QuestionAnswerAdvisor, 질문 원본 그대로) ################");
        QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore).build();
        String naiveAnswer = chatClient.prompt()
                .advisors(qaAdvisor)
                .user(query)
                .call()
                .content();
        System.out.println("질문> " + query);
        System.out.println("답변> " + naiveAnswer);
        System.out.println();

        System.out.println("################ 3. Query Transform + 넓게 검색(top-10) + LLM Rerank(top-3) 파이프라인 ################");
        LlmReranker reranker = new LlmReranker(ChatClient.builder(chatModel)
                .defaultSystem("항상 숫자만 답하세요.")
                .build());

        RetrievalAugmentationAdvisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(rewriteQueryTransformer)
                .documentRetriever(VectorStoreDocumentRetriever.builder()
                        .vectorStore(vectorStore)
                        .topK(10)
                        .build())
                .documentPostProcessors((q, docs) -> reranker.rerank(q.text(), docs, 3))
                .build();

        String pipelineAnswer = chatClient.prompt()
                .advisors(retrievalAugmentationAdvisor)
                .user(query)
                .call()
                .content();
        System.out.println("질문> " + query);
        System.out.println("답변> " + pipelineAnswer);
        System.out.println();

        System.out.println("=== 관찰 포인트 ===");
        System.out.println("2번(원본 질문 그대로 검색)과 3번(재작성 질문으로 검색 + rerank)의 답변이 같은 조항(14일 vs 30일)을 가리키는지, 근거로 든 문장이 다른지 비교해볼 것.");
    }

    private VectorStore buildVectorStore() {
        PagePdfDocumentReader pdfReader = new PagePdfDocumentReader("classpath:/docs/manual.pdf");
        List<Document> documents = pdfReader.get();

        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(200).build();
        List<Document> chunks = splitter.apply(documents);

        VectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(chunks);
        return vectorStore;
    }
}

package com.lecture.rag.day3.knowledge;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 어떤 VectorStore를 쓸지 정합니다.
 *
 * <p>기본은 {@link SimpleVectorStore}입니다. {@code pgvector} 프로필로 띄우면 이 설정이 통째로 빠지고
 * 스타터가 만들어 준 PGVector 빈이 대신 주입됩니다. {@code @ConditionalOnMissingBean}은 자동설정보다
 * 먼저 평가돼서 빈이 둘 다 만들어지므로 쓸 수 없습니다.
 *
 * <pre>
 * ./mvnw spring-boot:run -Dspring-boot.run.profiles=pgvector
 * </pre>
 *
 * <p>PGVector를 쓰면 DB가 인덱스를 들고 있으므로 {@link KnowledgeBase}의 파일 영속화는 자동으로 꺼집니다.
 */
@Configuration
@Profile("!pgvector")
public class VectorStoreConfig {

    @Bean
    VectorStore simpleVectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}

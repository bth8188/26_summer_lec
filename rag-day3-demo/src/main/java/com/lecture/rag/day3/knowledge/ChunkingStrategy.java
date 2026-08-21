package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * 청킹 전략 — 프론트 인덱싱 패널의 드롭다운에 그대로 노출된다.
 *
 * <p>Day2 M2.1에서 손으로 만들어본 세 가지 전략을 그대로 옮겨온 것이다. 같은 PDF를 전략만 바꿔
 * 두 번 인덱싱해보고, 같은 질문의 검색 결과(우측 인스펙터의 근거 탭)가 어떻게 달라지는지 비교하는 게 실습 포인트다.
 *
 * <p><b>학생 확장 지점</b>: enum 상수를 하나 추가하고 {@link #split} 의 switch에 분기를 넣으면
 * 프론트 드롭다운에 자동으로 추가된다({@code GET /api/knowledge/strategies}가 이 enum을 그대로 내려줌).
 * 예: Day2에서 만든 SemanticChunker(문장 임베딩 유사도가 급락하는 지점에서 자르기)를 SEMANTIC으로 추가.
 */
public enum ChunkingStrategy {

    TOKEN("토큰 기준 (TokenTextSplitter)", "Spring AI 기본 스플리터. chunkSize는 토큰 수 기준이다."),
    RECURSIVE("재귀 문자 분할", "문장부호 → 줄바꿈 → 공백 순으로 내려가며 자연스러운 경계에서 자른다."),
    SLIDING("슬라이딩 윈도우", "앞 청크와 overlap 글자만큼 겹치게 잘라 문맥이 끊기는 걸 막는다."),
    SEMANTIC("의미 기준 (Semantic)", "문장 임베딩 유사도가 급락하는 지점에서 자른다. 문장마다 임베딩을 불러 느리다.");

    private final String label;
    private final String description;

    ChunkingStrategy(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** 프론트에서 온 문자열을 안전하게 enum으로 (모르는 값이면 TOKEN). */
    public static ChunkingStrategy parse(String value) {
        if (value == null || value.isBlank()) {
            return TOKEN;
        }
        for (ChunkingStrategy strategy : values()) {
            if (strategy.name().equalsIgnoreCase(value.trim())) {
                return strategy;
            }
        }
        return TOKEN;
    }

    /**
     * 문서(PDF면 페이지 단위 Document 리스트)를 청크로 자른다. 페이지 메타데이터는 유지된다 —
     * 그래야 프론트에서 근거를 "학칙.pdf p.3" 처럼 보여줄 수 있다.
     */
    public List<Document> split(List<Document> documents, int chunkSize, int overlap,
            EmbeddingModel embeddingModel) {
        return switch (this) {
            case TOKEN -> TokenTextSplitter.builder()
                    .withChunkSize(chunkSize)
                    // 기본값(350자)이 크면 짧은 페이지가 통째로 버려져서 낮춰둔다
                    .withMinChunkLengthToEmbed(20)
                    .build()
                    .apply(documents);
            case RECURSIVE -> splitEach(documents, new RecursiveCharacterSplitter(chunkSize));
            case SLIDING -> splitEach(documents,
                    new SlidingWindowSplitter(chunkSize, Math.max(1, chunkSize - overlap)));
            // 유일하게 임베딩 모델이 필요한 전략이라 split()이 이걸 받도록 시그니처를 넓혔습니다
            case SEMANTIC -> splitEach(documents,
                    new SemanticSplitter(embeddingModel, SEMANTIC_DROP_THRESHOLD, chunkSize)::split);
        };
    }

    /** bge-m3 기준으로 문단이 바뀔 때 유사도가 대체로 이 아래로 떨어집니다. */
    private static final double SEMANTIC_DROP_THRESHOLD = 0.55;

    private static List<Document> splitEach(List<Document> documents, CharSplitter splitter) {
        List<Document> chunks = new ArrayList<>();
        for (Document document : documents) {
            if (document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            for (String piece : splitter.split(document.getText())) {
                chunks.add(Document.builder()
                        .text(piece)
                        // 원본 메타데이터(page, fileName 등)를 청크에 그대로 물려준다
                        .metadata(new java.util.LinkedHashMap<>(document.getMetadata()))
                        .build());
            }
        }
        return chunks;
    }

    /** 문자열을 여러 조각으로 자르는 것만 하는 최소 인터페이스. */
    interface CharSplitter {
        List<String> split(String text);
    }
}

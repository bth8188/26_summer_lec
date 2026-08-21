package com.lecture.rag.day3.agent;

import java.util.Map;

/**
 * 프론트엔드 설정 패널에서 내려오는 RAG 실행 옵션. 전부 optional이고 null이면 기본값을 쓴다.
 *
 * <p>여기 값들을 UI에서 바꿔가며 답변이 어떻게 달라지는지 보는 게 이 프로젝트의 핵심 실습이다.
 * (topK를 1로 줄이면? 임계값을 0.8로 올리면? temperature를 1.5로 올리면?)
 *
 * @param topK                검색으로 가져올 청크 개수
 * @param similarityThreshold 이 점수 미만인 청크는 버림 (0 = 전부 통과)
 * @param temperature         LLM 창의성 (0에 가까울수록 문서에 충실, 높을수록 자유로움)
 * @param maxHistory          이전 대화를 몇 턴까지 프롬프트에 실어보낼지
 * @param systemPrompt        시스템 프롬프트 덮어쓰기 (비우면 기본 프롬프트 사용)
 * @param features            학생 구현 기능 on/off (rewrite / keyword / rerank / selfCheck)
 * @param extras              학생이 추가한 임의 옵션 — 프론트에서 아무 값이나 넣어 보낼 수 있는 자리
 */
public record RagOptions(
        Integer topK,
        Double similarityThreshold,
        Double temperature,
        Integer maxHistory,
        String systemPrompt,
        Map<String, Boolean> features,
        Map<String, Object> extras) {

    public static final int DEFAULT_TOP_K = 4;
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.0;
    public static final double DEFAULT_TEMPERATURE = 0.2;
    public static final int DEFAULT_MAX_HISTORY = 4;

    /** 쿼리 재작성 — 짧고 모호한 질문을 검색용 질문으로 다시 쓰기 (실버) */
    public static final String FEATURE_REWRITE = "rewrite";
    /** 키워드(하이브리드) 검색 — 벡터 검색과 키워드 검색 결과를 합치기 (실버) */
    public static final String FEATURE_KEYWORD = "keyword";
    /** 재정렬 — 넓게 검색한 후보를 LLM으로 다시 채점해서 상위만 남기기 (실버) */
    public static final String FEATURE_RERANK = "rerank";
    /** 자기 검증 — 생성된 답변이 근거에 실제로 있는지 스스로 확인 (골드) */
    public static final String FEATURE_SELF_CHECK = "selfCheck";

    public static RagOptions defaults() {
        return new RagOptions(null, null, null, null, null, Map.of(), Map.of());
    }

    public int topKOrDefault() {
        return topK == null ? DEFAULT_TOP_K : Math.clamp(topK, 1, 30);
    }

    public double similarityThresholdOrDefault() {
        return similarityThreshold == null
                ? DEFAULT_SIMILARITY_THRESHOLD
                : Math.clamp(similarityThreshold, 0.0, 1.0);
    }

    public double temperatureOrDefault() {
        return temperature == null ? DEFAULT_TEMPERATURE : Math.clamp(temperature, 0.0, 2.0);
    }

    public int maxHistoryOrDefault() {
        return maxHistory == null ? DEFAULT_MAX_HISTORY : Math.clamp(maxHistory, 0, 20);
    }

    /** 해당 기능 토글이 켜져 있는지. */
    public boolean enabled(String feature) {
        return features != null && Boolean.TRUE.equals(features.get(feature));
    }

    /** 학생이 프론트에서 추가로 보낸 값 꺼내기 (없으면 기본값). */
    public Object extra(String key, Object defaultValue) {
        if (extras == null) {
            return defaultValue;
        }
        return extras.getOrDefault(key, defaultValue);
    }
}

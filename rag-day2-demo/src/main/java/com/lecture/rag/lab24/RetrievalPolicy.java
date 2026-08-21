package com.lecture.rag.lab24;

import org.springframework.ai.vectorstore.SearchRequest;

/**
 * Lab 2.4 — 검색 정책을 한 곳에 모은 클래스.
 *
 * 두 값이 하는 일이 서로 다르다는 게 이 랩의 핵심이라 일부러 한 파일에 나란히 뒀다.
 *  - filterExpression   : "어느 문서 집합에서 찾을지" — 범위 지정. 제주/김치가 같은 PGVector 테이블에
 *                         섞여 있어서, 이게 없으면 제주 도구가 김치 문서를 물어온다.
 *  - similarityThreshold: "모르면 모른다"의 임계치. 벡터 검색은 기본적으로 아무리 무관해도 topK개를
 *                         꽉 채워 돌려주기 때문에, 이 값이 없으면 results.isEmpty()는 영원히 false다.
 *
 * 임계값은 CLI에서 /threshold 명령으로 런타임에 바꿀 수 있게 필드로 뒀다 (튜닝 실습용).
 */
public class RetrievalPolicy {

    public static final String SOURCE_JEJU = "jeju";
    public static final String SOURCE_KIMCHI = "kimchi";

    /**
     * 섹션 단위 청킹으로 바꾼 뒤 3에서 2로 낮췄다.
     * 제주 문서는 섹션이 3개(지리·역사·관광)뿐이라 topK=3이면 질문과 무관한 섹션까지 전부 컨텍스트에
     * 딸려 들어가고, 소형 모델이 그중 엉뚱한 섹션으로 답해버린다("역사"를 물었는데 지리로 답하는 문제).
     * 실측상 정답 섹션이 1위로 확실히 올라오므로 2개면 충분하다.
     */
    public static final int TOP_K = 2;

    /**
     * bge-m3 + COSINE 기준 실측값으로 정한 임계값.
     * 처음엔 0.55로 잡았는데 "제주도 역사"(최고 0.5296), "제주 역사"(0.4995)가 통째로 차단됐다.
     * 섹션 단위 청킹으로 바꾼 뒤 실측한 분포는 이렇다:
     *   관련 질문   : 0.59 ~ 0.71  (제주 역사 0.59 / 제주도 역사 0.63 / 김치의 역사 0.71)
     *   무관한 질문 : 0.23 ~ 0.29  (파이썬 웹서버 만드는 법 0.29)
     * 두 구간 사이가 크게 벌어져 있어 0.40이면 양쪽 모두 안전하게 가른다.
     * 임계값은 이렇게 감이 아니라 /scores로 분포를 보고 정하는 것 — 그게 이 랩의 요점.
     */
    public static final double DEFAULT_SIMILARITY_THRESHOLD = 0.40;

    private double similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * WikiIndexer가 심는 메타데이터 키와 반드시 일치해야 한다.
     * 오타가 나도 예외가 아니라 "조용히 0건"이 되므로, 디버깅 시 제일 먼저 볼 지점.
     */
    public static String filterOn(String source) {
        return "source == '" + source + "'";
    }

    /** 도구(@Tool)용 — 질문을 직접 넣어 검색한다. */
    public SearchRequest forTool(String query, String source) {
        return SearchRequest.builder()
                .query(query)
                .topK(TOP_K)
                .filterExpression(filterOn(source))
                .similarityThreshold(similarityThreshold)
                .build();
    }

    /**
     * QuestionAnswerAdvisor용 — query를 비워 둔다.
     * Advisor가 내부에서 SearchRequest.from(this).query(사용자입력)으로 덮어쓰기 때문에
     * 여기 넣는 query는 어차피 버려지고, topK/filter/threshold만 그대로 승계된다.
     */
    public SearchRequest forAdvisor(String source) {
        return SearchRequest.builder()
                .topK(TOP_K)
                .filterExpression(filterOn(source))
                .similarityThreshold(similarityThreshold)
                .build();
    }

    /**
     * 임계값 튜닝용 — similarityThresholdAll()로 <b>아무것도 걸러내지 않고</b> 원점수를 그대로 본다.
     * "임계값을 0.55로 잡는 게 맞나?"를 감이 아니라 실측 분포를 보고 정하기 위한 것.
     */
    public SearchRequest probe(String query, String source, int topK) {
        return SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression(filterOn(source))
                .similarityThresholdAll()
                .build();
    }
}

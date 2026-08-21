package com.lecture.rag.day3.pipeline;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

/**
 * ★ 캡스톤 실습 파일 ★ — 여기를 채우는 게 실버/골드 과제다.
 *
 * <p>검색·프롬프트·스트리밍·화면 표시 배관은 이미 다 되어 있다. 아래 네 개의 메서드 중
 * <b>하고 싶은 것만</b> 구현하면 된다. 구현하지 않은 기능은 프론트에서 토글을 켰을 때
 * "여기가 네가 채울 자리다"라는 점선 카드로 표시된다(그 상태로도 답변은 정상 동작한다).
 *
 * <pre>
 *  실버 후보 ─ rewriteQueries()  질문 재작성 / multi-query
 *            ─ keywordSearch()  키워드 검색을 섞는 하이브리드 검색
 *            ─ rerank()         LLM으로 후보 재정렬
 *  골드 후보 ─ selfCheck()       답변이 근거에 실제로 있는지 자기 검증
 * </pre>
 *
 * <p>실행 방법: 프론트 우측 상단 파이프라인 드롭다운에서 "내 파이프라인"을 고르고,
 * 설정(⚙) 패널에서 해당 기능 토글을 켠다. 구현한 단계는 실행 시간과 결과 요약까지 화면에 뜬다.
 *
 * <p>주의: {@link #supportedFeatures()}에 선언되지 않은 기능 토글은 이 파이프라인에서 무시된다.
 * 새 기능을 추가하면 여기 목록에도 추가할 것.
 */
@Component
public class StudentRagPipeline extends AbstractRagPipeline {

    public StudentRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        super(knowledgeBase, chatModel);
    }

    @Override
    public String id() {
        return "student";
    }

    @Override
    public String name() {
        return "내 파이프라인";
    }

    @Override
    public String tier() {
        return "silver";
    }

    @Override
    public String description() {
        return "StudentRagPipeline.java의 TODO를 채워서 만드는 나만의 파이프라인.";
    }

    @Override
    public List<String> supportedFeatures() {
        return List.of(
                RagOptions.FEATURE_REWRITE,
                RagOptions.FEATURE_KEYWORD,
                RagOptions.FEATURE_RERANK,
                RagOptions.FEATURE_SELF_CHECK);
    }

    // =================================================================== 실버 ①

    /**
     * 질문 재작성 / multi-query 검색.
     *
     * <p>왜 필요한가: "그거 얼마야?" 같은 짧은 질문은 임베딩이 잡을 정보가 거의 없어서 검색이 헛돈다.
     * 이전 대화를 참고해 완전한 문장으로 다시 쓰거나("연회비는 얼마인가요?"),
     * 표현이 다른 여러 버전을 만들어 각각 검색하면 회수율(recall)이 올라간다.
     *
     * <p>구현 힌트:
     * <pre>
     * 1) 이전 대화를 텍스트로 얻기:  RagPrompts.historyAsText(history, options.maxHistoryOrDefault())
     * 2) LLM 호출:                 chatClient().prompt().user(프롬프트).call().content()
     * 3) 프롬프트 예시:
     *      "다음 대화의 마지막 질문을 문서 검색에 쓸 수 있게 완전한 문장으로 바꿔 쓰세요.
     *       서로 표현이 다른 3개를 줄바꿈으로만 구분해서 출력하고, 번호나 설명은 붙이지 마세요."
     * 4) 응답을 줄 단위로 쪼개고 빈 줄을 버려서 List&lt;String&gt;으로 만들기
     * 5) 원문 질문도 목록에 포함시키는 게 안전하다 (재작성이 엉뚱해도 최소한의 검색 품질 보장)
     * </pre>
     *
     * @return 검색에 쓸 쿼리 목록. 구현 전에는 {@code Optional.empty()}
     */
    @Override
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
            RagOptions options) {
        // TODO(실버): 위 힌트를 참고해 구현하고, 아래 줄을 지우세요.
        return Optional.empty();
    }

    // =================================================================== 실버 ②

    /**
     * 키워드 검색(하이브리드 검색의 절반).
     *
     * <p>왜 필요한가: 벡터 검색은 "의미가 비슷한 것"을 찾기 때문에 <b>정확히 일치해야 하는 것</b>에 약하다.
     * 모델명(RTX-4090), 조항 번호(제12조), 사람 이름 같은 고유명사가 그렇다.
     * 단순 단어 매칭 점수를 벡터 검색 결과에 합치는 것만으로도 체감 품질이 꽤 올라간다.
     *
     * <p>구현 힌트:
     * <pre>
     * 1) 전체 청크 받기:   knowledgeBase.chunksOf(docIds)   // docIds가 비면 전체
     * 2) 질문을 공백으로 쪼개 2글자 이상 단어만 남기기
     * 3) 청크별 점수 = 청크 본문에 등장한 단어 개수 (또는 등장 횟수 합)
     *    - 대소문자 무시:  chunk.getText().toLowerCase().contains(word.toLowerCase())
     *    - 더 해보고 싶으면 BM25: 흔한 단어의 가중치를 낮추는 방식 (Day2 M2.4 참고)
     * 4) 점수 내림차순으로 상위 options.topKOrDefault()개만 반환
     * 5) 점수가 0인 청크는 버릴 것 — 안 버리면 관련 없는 청크가 컨텍스트를 오염시킨다
     * </pre>
     *
     * @return 키워드로 찾은 청크. 구현 전에는 {@code Optional.empty()}
     */
    @Override
    protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {
        // TODO(실버): 위 힌트를 참고해 구현하고, 아래 줄을 지우세요.
        return Optional.empty();
    }

    // =================================================================== 실버 ③

    /**
     * 재정렬(rerank).
     *
     * <p>왜 필요한가: 임베딩 유사도 1위가 항상 정답 청크는 아니다. 그래서 검색을 일부러 넓게(topK×2) 해두고,
     * LLM에게 "이 청크가 이 질문에 답하는 데 얼마나 도움이 되냐"를 0~10점으로 채점시켜 상위만 남긴다.
     * (이 파이프라인은 rerank 토글이 켜져 있으면 자동으로 topK의 2배를 검색해온다)
     *
     * <p>구현 힌트:
     * <pre>
     * 1) 후보 하나씩 LLM 채점:
     *      "질문: %s\n문서: %s\n이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 숫자 하나만 답하세요."
     * 2) 응답에서 첫 숫자만 정규식으로 뽑기 (LLM이 설명을 덧붙이는 경우 방어)
     * 3) 점수 내림차순 정렬 → 앞에서부터 options.topKOrDefault()개
     * 4) Day2 Lab2.2의 LlmReranker(rag-day2-demo)를 그대로 복사해와도 된다
     * 5) 주의: 후보 8개면 LLM을 8번 부른다 → 답변까지 10초 이상 걸릴 수 있다.
     *    느린 게 정상이고, 그 대가로 정확도를 사는 것이다(프론트 상단 배지에서 시간 비교 가능).
     * </pre>
     *
     * @return 관련도 높은 순으로 정렬된 청크. 구현 전에는 {@code Optional.empty()}
     */
    @Override
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
        // TODO(실버): 위 힌트를 참고해 구현하고, 아래 줄을 지우세요.
        return Optional.empty();
    }

    // =================================================================== 골드

    /**
     * 자기 검증(self-check) — Self-RAG의 축소판.
     *
     * <p>왜 필요한가: RAG를 붙여도 LLM은 컨텍스트에 없는 내용을 슬쩍 섞는다. 생성이 끝난 답변을 다시 한 번
     * "이 답변이 근거 안에 실제로 있는 내용인가?"로 검사하면, 할루시네이션을 사용자에게 경고할 수 있다.
     *
     * <p>구현 힌트:
     * <pre>
     * 1) 근거 텍스트 조립:  RagPrompts.formatContext(sources)
     * 2) LLM에게 판정 요청:
     *      "[근거]\n%s\n\n[답변]\n%s\n\n답변의 모든 문장이 근거에 실제로 있는 내용인지 판정하세요.
     *       형식: '통과' 또는 '주의: <근거에 없는 내용 요약>' 한 줄로만."
     * 3) 결과 한 줄을 그대로 반환하면 화면 단계 카드와 안내 배너에 표시된다
     * 4) 확장: Day3 오전 Lab3.1의 LLM-as-judge 점수(충실도/관련성)를 여기서 같이 계산해
     *    "충실도 4/5" 같은 문자열로 반환해도 된다. 채점 전용 엔드포인트는 이미 있다
     *    ({@code POST /api/evaluate}, 프론트 답변 카드의 "채점" 버튼)
     * </pre>
     *
     * @return 사용자에게 보여줄 검증 결과 한 줄. 구현 전에는 {@code Optional.empty()}
     */
    @Override
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
            RagOptions options) {
        // TODO(골드): 위 힌트를 참고해 구현하고, 아래 줄을 지우세요.
        return Optional.empty();
    }
}

package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StudentRagPipeline.class);

    /** 재작성 포함 검색에 쓸 쿼리 최대 개수(원문 1 + 재작성 3). 쿼리 하나가 임베딩+검색 한 번이다. */
    private static final int MAX_QUERIES = 4;

    /** 이보다 짧은 재작성 결과는 버린다. 글머리 기호를 뗀 뒤 남은 찌꺼기를 걸러내는 용도다. */
    private static final int MIN_QUERY_CHARS = 4;

    /** 재정렬 채점에 넣을 청크 본문 최대 길이. 후보 수만큼 곱해지는 비용이라 앞부분만 쓴다. */
    private static final int MAX_SCORING_CHARS = 1200;

    /** 채점에 실패했을 때 쓸 중립 점수 — 0~10의 한가운데라 원래 순서를 흔들지 않는다. */
    private static final double NEUTRAL_SCORE = 5.0;

    /** 자기 검증 결과 문자열 최대 길이. 단계 카드와 배너에 그대로 들어가서 길면 화면이 깨진다. */
    private static final int MAX_VERDICT_CHARS = 200;

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
     * <p>원문 질문을 <b>항상 목록 맨 앞에</b> 넣는다. 재작성이 엉뚱하게 나와도 최소한의 검색 품질이
     * 보장되기 때문이다. 다만 앞자리를 독점하지는 않는다 — 부모가 쿼리별 결과를
     * {@link RagPrompts#fuseByRank}(RRF)로 융합하므로, 재작성이 찾아온 청크도 순위가 높으면 채택된다.
     * 여러 쿼리가 공통으로 찾은 청크는 점수가 누적되어 가장 위로 올라온다.
     *
     * <p><b>비용 주의</b>: 쿼리 하나당 임베딩+검색이 한 번씩 돈다. 재정렬까지 같이 켜면
     * 후보가 (쿼리 수 × topK×2)까지 불어나고 그만큼 LLM 채점이 늘어난다. 그래서 {@link #MAX_QUERIES}로
     * 총 개수를 묶어둔다.
     *
     * <p>더 해볼 것:
     * <pre>
     * 1) 질문이 이미 길고 구체적이면 재작성을 건너뛰기 (짧고 모호할 때만 부르면 비용이 준다)
     * 2) 검색용 키워드만 뽑는 버전과 완전한 문장 버전을 섞기 (HyDE: 가짜 답변을 만들어 그걸로 검색)
     * 3) 재작성별 검색 결과에 가중치를 다르게 주기 — 지금은 전부 동등하게 병합된다
     * </pre>
     *
     * @return 검색에 쓸 쿼리 목록 (맨 앞이 원문 질문)
     */
    @Override
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
            RagOptions options) {
        if (question == null || question.isBlank()) {
            // 빈 목록이어도 "미구현"은 아니다 — 부모는 Optional.empty()만 TODO로 본다.
            return Optional.of(List.of());
        }

        List<String> queries = new ArrayList<>();
        queries.add(question);

        String conversation = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());
        String prompt = """
                [이전 대화]
                %s

                [마지막 질문]
                %s

                위 질문을 문서 검색에 쓸 수 있도록 완전한 문장으로 다시 쓰세요.
                - 이전 대화에서 가리키는 말("그거", "거기", "그 사람")은 실제 이름으로 바꿔 쓰세요.
                - 서로 표현이 다른 %d개를 만드세요.
                - 줄바꿈으로만 구분하고 번호, 설명, 따옴표는 붙이지 마세요.
                """.formatted(
                conversation.isBlank() ? "(이전 대화 없음)" : conversation,
                question,
                MAX_QUERIES - 1);

        try {
            addRewrites(ask(prompt), queries);
        }
        catch (Exception ex) {
            // 재작성이 실패해도 원문 질문 하나로 검색하면 된다 — 기본 RAG와 같은 수준으로 안전하게 떨어진다.
            log.warn("질문 재작성 실패 — 원문 질문만 사용합니다: {}", ex.getMessage());
        }
        return Optional.of(queries);
    }

    /**
     * 재작성 결과를 쿼리 목록에 덧붙인다. 줄 쪼개기와 글머리 기호 제거는 부모의
     * {@link AbstractRagPipeline#cleanedLines}가 해주고, 여기서는 "쿼리로 쓸 만한가"만 판단한다.
     *
     * <p>중복 쿼리는 버린다 — 같은 문장을 두 번 검색해봤자 임베딩 비용만 두 배다.
     */
    private static void addRewrites(String answer, List<String> queries) {
        for (String query : cleanedLines(answer)) {
            if (query.length() < MIN_QUERY_CHARS) {
                continue;
            }
            if (queries.stream().anyMatch(existing -> existing.equalsIgnoreCase(query))) {
                continue;
            }
            queries.add(query);
            if (queries.size() >= MAX_QUERIES) {
                // 모델이 10개를 뱉어도 여기서 끊는다. 쿼리 하나가 검색 한 번이다.
                return;
            }
        }
    }

    // =================================================================== 실버 ②

    /*
     * 키워드 검색(하이브리드 검색의 절반).
     *
     * <p>왜 필요한가: 벡터 검색은 "의미가 비슷한 것"을 찾기 때문에 <b>정확히 일치해야 하는 것</b>에 약하다.
     * 모델명(RTX-4090), 조항 번호(제12조), 사람 이름 같은 고유명사가 그렇다.
     * 단순 단어 매칭 점수를 벡터 검색 결과에 합치는 것만으로도 체감 품질이 꽤 올라간다.
     *
     * <p><b>이 기능은 베이스 클래스에 기본 구현이 들어 있다</b>
     * ({@link AbstractRagPipeline#keywordSearch}). LLM을 부르지 않는 순수 계산이라
     * 모든 파이프라인이 공유해도 부작용이 없기 때문이다. 그래서 여기서는 오버라이드하지 않아도
     * 토글만 켜면 TF-IDF 방식의 단어 매칭이 동작한다.
     *
     * <p>더 해보고 싶으면 이 메서드를 다시 오버라이드해서 바꿔볼 것:
     * <pre>
     * 1) 재료:  knowledgeBase.chunksOf(docIds)   // docIds가 비면 전체
     *          keywordsOf(query) / countOccurrences(body, term)  // 부모가 주는 헬퍼
     * 2) BM25로 바꾸기: 청크 길이로 점수를 정규화한다 (Day2 M2.4 참고)
     * 3) 형태소 분석기(은전한닢 등)를 붙여 조사를 떼어내기 — "학칙은"과 "학칙"이 같아진다
     * 4) 점수 내림차순으로 상위 options.topKOrDefault()개만 반환
     * 5) 점수가 0인 청크는 버릴 것 — 안 버리면 관련 없는 청크가 컨텍스트를 오염시킨다
     * </pre>
     */

    // =================================================================== 실버 ③

    /**
     * 재정렬(rerank).
     *
     * <p>왜 필요한가: 임베딩 유사도 1위가 항상 정답 청크는 아니다. 그래서 검색을 일부러 넓게(topK×2) 해두고,
     * LLM에게 "이 청크가 이 질문에 답하는 데 얼마나 도움이 되냐"를 0~10점으로 채점시켜 상위만 남긴다.
     * (이 파이프라인은 rerank 토글이 켜져 있으면 자동으로 topK의 2배를 검색해온다)
     *
     * <p>키워드 검색과 달리 이건 <b>베이스 클래스가 아니라 여기에</b> 둔다. 후보 하나당 LLM을 한 번씩
     * 부르는 비싼 작업이라, 부모의 기본 구현으로 두면 다른 파이프라인이 무심코 상속받아 느려지기 때문이다.
     *
     * <p>주의: 후보 8개면 LLM을 8번 부른다 → 답변까지 10초 이상 걸릴 수 있다. 느린 게 정상이고,
     * 그 대가로 정확도를 사는 것이다(프론트 상단 배지에서 시간 비교 가능).
     *
     * <p>더 해볼 것:
     * <pre>
     * 1) 후보를 한 프롬프트에 다 넣고 순위를 한 번에 매기게 하기 → LLM 호출 1번으로 줄어든다
     * 2) 점수가 일정 미만인 후보는 아예 버리기 (지금은 순서만 바꾸고 topK개는 무조건 채운다)
     * 3) 전용 리랭커 모델(bge-reranker 등)로 교체 — 생성 모델보다 빠르고 정확하다
     * </pre>
     *
     * @return 관련도 높은 순으로 정렬된 청크
     */
    @Override
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
        if (candidates.size() <= 1) {
            // 후보가 0~1개면 순서를 바꿀 게 없다. LLM을 부를 이유도 없다.
            return Optional.of(candidates);
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Document candidate : candidates) {
            scored.add(new Scored(candidate, scoreRelevance(query, candidate)));
        }

        // List.sort는 안정 정렬이라, 점수가 같으면 원래 벡터 검색 순서가 그대로 유지된다.
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        int limit = Math.min(options.topKOrDefault(), scored.size());
        return Optional.of(scored.subList(0, limit).stream().map(Scored::document).toList());
    }

    /**
     * 청크 하나가 질문에 얼마나 관련 있는지 LLM에게 0~10점으로 물어본다.
     * 호출과 숫자 파싱은 부모의 {@link AbstractRagPipeline#ask} / {@code firstNumber}에 맡기고,
     * 여기서는 채점 프롬프트와 실패 시 정책만 정한다.
     */
    private double scoreRelevance(String query, Document candidate) {
        String body = RagPrompts.squeeze(candidate.getText());
        if (body.length() > MAX_SCORING_CHARS) {
            // 채점에는 앞부분만 있어도 충분하다. 긴 청크를 통째로 넣으면 후보 수만큼 비용이 곱해진다.
            body = body.substring(0, MAX_SCORING_CHARS);
        }
        String prompt = """
                질문: %s

                문서: %s

                이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 사이 숫자 하나로만 답하세요.
                설명이나 단위는 붙이지 말고 숫자만 출력하세요.
                """.formatted(query, body);
        try {
            // 숫자가 없는 응답("관련 없습니다")도 중립 점수로 떨어진다 — 판단을 포기하고 원래 순서를 존중한다.
            return firstNumber(ask(prompt), NEUTRAL_SCORE, 0.0, 10.0);
        }
        catch (Exception ex) {
            // 채점 하나가 실패했다고 답변 전체를 날릴 이유는 없다. 중립 점수로 두면 원래 순서가 유지된다.
            log.warn("재정렬 채점 실패 — 중립 점수({})로 대체합니다: {}", NEUTRAL_SCORE, ex.getMessage());
            return NEUTRAL_SCORE;
        }
    }

    // =================================================================== 골드

    /**
     * 자기 검증(self-check) — Self-RAG의 축소판.
     *
     * <p>왜 필요한가: RAG를 붙여도 LLM은 컨텍스트에 없는 내용을 슬쩍 섞는다. 생성이 끝난 답변을 다시 한 번
     * "이 답변이 근거 안에 실제로 있는 내용인가?"로 검사하면, 할루시네이션을 사용자에게 경고할 수 있다.
     *
     * <p>돌려준 한 줄이 화면 단계 카드와 안내 배너 두 곳에 그대로 표시된다
     * ({@code AbstractRagPipeline#verifyPhase}). 그래서 사용자가 읽고 바로 판단할 수 있는 문장이어야 하고,
     * 길면 잘라낸다.
     *
     * <p>검증하는 쪽도 답변을 쓴 것과 <b>같은 모델</b>이라는 게 이 방식의 근본적인 한계다. 자기가 지어낸
     * 내용을 자기가 "통과"라고 우기는 경우가 실제로 나온다. 그래도 근거에 아예 없는 고유명사·숫자를
     * 잡아내는 데는 쓸 만하고, 이게 Self-RAG가 별도 판정 모델을 두는 이유이기도 하다.
     *
     * <p>더 해볼 것:
     * <pre>
     * 1) 문장 단위로 쪼개 각각 판정하기 → 어느 문장이 문제인지 정확히 짚어준다
     * 2) Day3 오전 Lab3.1의 LLM-as-judge 점수(충실도/관련성)를 같이 계산해 "충실도 4/5"로 반환
     *    (채점 전용 엔드포인트는 이미 있다 — {@code POST /api/evaluate}, 답변 카드의 "채점" 버튼)
     * 3) 판정 모델만 더 큰 것으로 바꾸기 — 채점자가 피채점자보다 나아야 의미가 있다
     * </pre>
     *
     * @return 사용자에게 보여줄 검증 결과 한 줄
     */
    @Override
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
            RagOptions options) {
        if (answer == null || answer.isBlank()) {
            return Optional.of("검증할 답변이 없습니다.");
        }
        if (sources.isEmpty()) {
            // 대조할 근거가 없다는 것 자체가 경고다. LLM을 부를 필요도 없다.
            return Optional.of("주의: 검색된 근거가 없어 답변을 대조할 수 없습니다.");
        }

        // 프롬프트에 <...> 자리표시자를 두면 모델이 그걸 그대로 베껴 출력한다(실측). 예시로 보여준다.
        String prompt = """
                [근거]
                %s

                [질문]
                %s

                [답변]
                %s

                답변의 모든 문장이 [근거] 안에 실제로 있는 내용인지 판정하고, 한 줄만 출력하세요.
                - 전부 근거에 있으면 정확히 이렇게: 통과
                - 근거에 없는 내용이 있으면 "주의:"로 시작해 그 내용을 한 문장으로 적으세요.
                  예) 주의: 연회비 금액이 근거에 없습니다.
                목록, 화살표, 인용부호를 쓰지 말고 위 두 형태 외에는 답하지 마세요.
                """.formatted(RagPrompts.formatContext(sources), question, answer);

        try {
            return Optional.of(normalizeVerdict(ask(prompt)));
        }
        catch (Exception ex) {
            // 검증 실패를 Optional.empty()로 돌려주면 "미구현" TODO 카드가 떠서 오해를 부른다.
            // 답변 자체는 이미 스트리밍이 끝난 상태이므로, 검증만 못 했다고 알린다.
            log.warn("자기 검증 실패: {}", ex.getMessage());
            return Optional.of("검증하지 못했습니다 (" + ex.getClass().getSimpleName() + ").");
        }
    }

    /**
     * 판정 응답을 화면에 띄울 한 줄로 정규화한다.
     *
     * <p>작은 모델은 형식을 자주 어긴다. 실측에서는 목록 기호로 시작해 프롬프트의 자리표시자를 그대로
     * 베낀 문자열이 나왔고, 그게 사용자 화면에 그대로 노출됐다. 그래서 세 가지를 한다.
     * <ol>
     *   <li>부모의 {@link AbstractRagPipeline#firstLine}으로 첫 줄만 잘라 온다</li>
     *   <li>남은 글머리 기호를 떼어낸다</li>
     *   <li>{@code 통과}/{@code 주의}로 시작하지 않으면 <b>판정 실패</b>로 표시한다 —
     *       형식을 못 지킨 응답은 판정 내용도 믿을 수 없으므로, 할루시네이션 경고인 척하면 안 된다</li>
     * </ol>
     */
    private static String normalizeVerdict(String verdict) {
        String line = firstLine(verdict, MAX_VERDICT_CHARS, "판정 결과를 읽지 못했습니다.");
        String cleaned = cleanedLines(line).stream().findFirst().orElse(line);
        if (cleaned.startsWith("통과")) {
            return "통과";
        }
        if (cleaned.startsWith("주의")) {
            return cleaned;
        }
        return "판정 형식을 지키지 않아 신뢰할 수 없습니다: " + cleaned;
    }
}

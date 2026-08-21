package com.lecture.rag.day3.pipeline;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
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

    private static final int RRF_K = 5;
    private static final double BM25_K1 = 1.2;   // 반복 등장이 포화되는 속도
    private static final double BM25_B = 0.75;   // 청크 길이 정규화 강도

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

    /** 뒤에 붙어 매칭을 막는 조사들. 긴 것부터 나열해야 "에서는"이 "는"보다 먼저 걸린다. */
    private static final List<String> PARTICLES = List.of(
            "에서는", "으로는", "에게서", "이라고", "라고", "에서", "에게", "으로", "까지", "부터",
            "보다", "처럼", "마다", "조차", "밖에", "이나", "한테", "와의", "과의",
            "은", "는", "이", "가", "을", "를", "과", "와", "의", "도", "만", "에", "로", "야");

    /** 어느 청크에나 나와서 변별력이 없는 단어. 남겨두면 엉뚱한 청크가 상위로 올라온다. */
    private static final Set<String> STOP_WORDS = Set.of(
            "무엇", "누구", "어디", "언제", "어떻게", "얼마", "관계", "설명", "알려", "뭐야", "인가", "대해", "정도");

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
        return "BM25 하이브리드 검색 + RRF 융합 재정렬. 벡터가 놓치는 고유명사를 키워드로 보완한다.";
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
        List<String> terms = keywordsOf(query);
        if (terms.isEmpty()) {
            return Optional.empty();
        }

        List<Document> chunks = this.knowledgeBase.chunksOf(docIds);   // docIds가 비면 전체 청크
        Map<String, Double> idf = idfOf(terms, chunks);
        double avgLength = averageLength(chunks);
        record Scored(Document doc, double score) {}

        List<Document> hits = chunks.stream()
                .map(chunk -> new Scored(chunk, score(chunk.getText(), terms, idf, avgLength)))
                .filter(scored -> scored.score() > 0)   // 0점은 버린다 — 남기면 컨텍스트가 오염된다
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(options.topKOrDefault())
                .map(Scored::doc)
                .toList();

        System.out.println("  [keyword] 검색어=" + terms + " idf="
                + idf.entrySet().stream().map(e -> e.getKey() + ":" + String.format("%.2f", e.getValue())).toList());
        for (Document hit : hits) {
            System.out.println("  [keyword] " + String.format("%.2f", score(hit.getText(), terms, idf, avgLength)) + " | "
                    + hit.getText().replaceAll("\\s+", " ").substring(0, Math.min(45, hit.getText().length())));
        }
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits);
    }

    /**
     * 청크 점수 = BM25.
     *
     * <p>IDF만 곱하면 아직 부족하다. "장료의 고향이 어디야?"에서 IDF는 고향 4.26 / 장료 1.06으로
     * 제대로 나왔는데도, "장료"가 8번 나오는 청크가 8 x 1.06 = 8.48점으로 "고향"이 한 번 나오는
     * 정답 청크(1 x 4.26)를 눌렀다. 등장 횟수를 선형으로 더하기 때문이다.
     *
     * <p>BM25는 두 가지를 더한다. 같은 단어가 반복돼도 점수가 <b>포화</b>되게 만들고(k1),
     * 긴 청크가 단어를 많이 담는다는 이유만으로 유리해지지 않게 <b>길이로 정규화</b>한다(b).
     * 이러면 위 예에서 장료 8회는 약 2점에 머물고 희귀한 고향 1회가 4.26점으로 이긴다.
     */
    private double score(String text, List<String> terms, Map<String, Double> idf, double avgLength) {
        String lower = text.toLowerCase();
        double norm = BM25_K1 * (1 - BM25_B + BM25_B * (text.length() / Math.max(1.0, avgLength)));
        double total = 0;
        for (String term : terms) {
            int tf = 0, from = 0;
            while ((from = lower.indexOf(term, from)) >= 0) {
                tf++;
                from += term.length();
            }
            if (tf > 0) {
                total += idf.getOrDefault(term, 1.0) * (tf * (BM25_K1 + 1)) / (tf + norm);
            }
        }
        return total;
    }

    private double averageLength(List<Document> corpus) {
        return corpus.stream().mapToInt(doc -> doc.getText().length()).average().orElse(1.0);
    }

    /** 단어가 몇 개 청크에 등장하는지로 희귀도를 계산한다. 전 청크에 다 나오는 단어는 0에 수렴. */
    private Map<String, Double> idfOf(List<String> terms, List<Document> corpus) {
        Map<String, Double> idf = new HashMap<>();
        int total = Math.max(1, corpus.size());
        for (String term : terms) {
            long df = corpus.stream()
                    .filter(doc -> doc.getText().toLowerCase().contains(term))
                    .count();
            idf.put(term, Math.log((double) total / (1 + df)) + 1.0);   // +1은 df=total일 때 0이 되는 것 방지
        }
        return idf;
    }

    /**
     * 질문을 검색어로 쪼갠다.
     *
     * <p>한국어에서는 공백으로만 자르면 조사가 붙어버려 매칭이 안 된다 —
     * "악진과"로는 본문의 "악진"을 찾지 못한다. 그래서 뒤에 붙은 조사를 떼어낸다.
     * (형태소 분석기를 쓰면 정확하지만 의존성이 늘어나서, 여기서는 접미 제거로 대신한다)
     */
    private List<String> keywordsOf(String query) {
        return Arrays.stream(query.toLowerCase().split("[^\\p{L}\\p{N}]+"))
                .map(this::stripParticle)
                .filter(word -> word.length() >= 2)
                .filter(word -> !STOP_WORDS.contains(word))
                .distinct()
                .toList();
    }

    private String stripParticle(String word) {
        for (String particle : PARTICLES) {   // 긴 것부터 검사해야 "에서는"이 "는"보다 먼저 걸린다
            if (word.length() > particle.length() + 1 && word.endsWith(particle)) {
                return word.substring(0, word.length() - particle.length());
            }
        }
        return word;
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
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // 신호 3개를 각각 순위로 바꾼 뒤 RRF로 합친다.
        // LLM 점수만 믿으면 안 되는 이유는 judge()의 주석 참고 — 이 문서에서 3B 모델은
        // 정답 청크에 6점, 무관한 청크에 9점을 줬다. 그래서 벡터·키워드 신호를 같이 세운다.
        List<String> terms = keywordsOf(query);
        // IDF는 후보만이 아니라 전체 청크로 계산해야 희귀도가 제대로 나온다
        List<Document> corpus = this.knowledgeBase.allChunks();
        Map<String, Double> idf = idfOf(terms, corpus);
        double avgLength = averageLength(corpus);
        Map<Document, Integer> vectorRank = rankOf(candidates, doc -> -candidates.indexOf(doc));
        Map<Document, Integer> keywordRank = rankOf(candidates,
                doc -> (int) Math.round(score(doc.getText(), terms, idf, avgLength) * 100));

        // LLM 채점은 기본으로 끈다 — 이 모델에서는 순위를 오히려 망가뜨린다(judge() 주석의 실측 참고).
        // 더 큰 모델을 쓸 때 extras.llmJudge=true 로 켜서 비교해볼 수 있게 남겨둔다.
        boolean useLlm = Boolean.TRUE.equals(options.extra("llmJudge", Boolean.FALSE));
        Map<Document, Integer> llmRank = useLlm
                ? rankOf(candidates, doc -> judge(query, doc.getText()))
                : Map.of();

        List<Document> fused = candidates.stream()
                .sorted(Comparator.comparingDouble((Document doc) ->
                        rrf(vectorRank.get(doc)) + rrf(keywordRank.get(doc))
                                + (useLlm ? rrf(llmRank.get(doc)) : 0.0))
                        .reversed())
                .toList();

        for (Document doc : fused) {
            System.out.println("  [rerank] 벡터" + vectorRank.get(doc) + "위 키워드" + keywordRank.get(doc)
                    + (useLlm ? "위 LLM" + llmRank.get(doc) : "") + "위 | "
                    + doc.getText().replaceAll("\\s+", " ").substring(0, Math.min(40, doc.getText().length())));
        }

        // 자르는 건 AbstractRagPipeline#finalizeSources가 topK로 알아서 한다 — 여기서는 정렬만 한다.
        // 키워드로 찾아 뒤에 붙었던 청크가 상위로 올라올 수 있는 유일한 지점이기도 하다.
        return Optional.of(fused);
    }

    /** 점수가 높은 순으로 1위부터 매긴다. 점수가 같으면 원래 순서를 유지한다. */
    private Map<Document, Integer> rankOf(List<Document> docs, ToIntFunction<Document> scorer) {
        List<Document> sorted = docs.stream()
                .sorted(Comparator.comparingInt(scorer).reversed())
                .toList();
        Map<Document, Integer> ranks = new IdentityHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i), i + 1);
        }
        return ranks;
    }

    /**
     * Reciprocal Rank Fusion — 서로 단위가 다른 순위들을 더할 수 있게 1/(k+순위)로 바꾼다.
     * 흔히 쓰는 k=60은 후보가 수백 개일 때 값이고, 여기처럼 10개 안팎이면 상위 순위 차이가
     * 뭉개지므로 k를 작게 잡는다.
     */
    private double rrf(int rank) {
        return 1.0 / (RRF_K + rank);
    }

    /**
     * 청크 하나를 0~10점으로 채점. Day2 Lab2.2 LlmReranker와 같은 방식이되 <b>온도를 0으로 고정</b>한다.
     *
     * <p><b>주의: 이 모델에서는 신뢰할 수 없다.</b> "이전, 악진과 장료의 관계는?" 질문에서
     * 정답 청크("악진의 견고한 방어, 장료의 날카로운 공격, 이전의 적절한 지원")를 9개 중 7위로 매기고,
     * 전혀 무관한 청크를 1위로 올렸다. 그래서 rerank()는 기본적으로 이 점수를 쓰지 않는다.
     *
     * <p>채점은 창의성이 필요한 작업이 아니라 같은 입력이면 같은 점수가 나와야 하는 작업이다.
     * 기본 온도(0.2)로 두면 llama3.2:3b가 무관한 청크에 6점과 8점을 오락가락 매겨서
     * 순위가 실행할 때마다 뒤집힌다(실측: 같은 청크 5회 채점에 6,6,6,8,8).
     * 온도 0에서는 관련 청크 8점 / 무관 청크 6점으로 안정적으로 갈린다.
     */
    private int judge(String query, String text) {
        String answer = chatClient().prompt()
                .options(ChatOptions.builder().temperature(0.0))
                .user("""
                        질문: %s
                        문서: %s
                        이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 숫자 하나만 답하세요. 설명은 하지 마세요.
                        """.formatted(query, text))
                .call()
                .content();

        // 숫자만 답하라고 해도 소형 모델은 설명을 덧붙인다 — 첫 숫자만 뽑아 방어한다
        Matcher matcher = FIRST_NUMBER.matcher(answer == null ? "" : answer);
        return matcher.find() ? Math.min(10, Integer.parseInt(matcher.group())) : 0;
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

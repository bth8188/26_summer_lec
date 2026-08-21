package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StudentRagPipeline.class);

    /** 쿼리 하나마다 임베딩 1회 + 벡터 검색 1회가 더 나갑니다. */
    private static final int MAX_QUERIES = 3;

    /** 3B 모델은 "번호 붙이지 마세요"를 잘 안 지킵니다. */
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*•>#]+|\\d+\\s*[.)])\\s*");
    private static final Pattern EDGE_NOISE = Pattern.compile("^[\"'“”‘’*_`]+|[\"'“”‘’*_`]+$");
    private static final Pattern QUOTES = Pattern.compile("[\"“”]");

    /** "RTX-4090", "제12조"를 쪼개면 안 되므로 양끝만 다듬습니다. */
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");

    /** 긴 것부터 떼야 "에서"가 "에"보다 먼저 걸립니다. */
    private static final List<String> PARTICLES = List.of(
            "에서", "으로", "까지", "부터", "에게",
            "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "만", "로");

    /** 후보 하나당 LLM 호출 한 번이라, 이 값이 그대로 재정렬 대기 시간입니다. */
    private static final int MAX_RERANK_CALLS = 8;

    /** 통째로 넣으면 점수 대신 요약이 옵니다. */
    private static final int RERANK_SNIPPET_CHARS = 600;

    /** "0에서 10 사이에서 8점" 같은 답이 있어 맨 앞 숫자를 바로 집으면 안 됩니다. */
    private static final List<Pattern> SCORE_PATTERNS = List.of(
            Pattern.compile("점수\\s*[:：]?\\s*(\\d{1,2})"),
            Pattern.compile("(\\d{1,2})\\s*점"),
            Pattern.compile("\\d{1,2}"));

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
     * <p>원문 질문은 항상 첫 쿼리로 남겨둡니다.
     */
    @Override
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
            RagOptions options) {

        List<String> queries = new ArrayList<>();
        queries.add(question);

        String conversation = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());
        String raw;
        try {
            raw = chatClient().prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(rewritePrompt(question, conversation))
                    .call()
                    .content();
        }
        catch (Exception exception) {
            // 부가 단계라 실패해도 원문으로 검색은 계속합니다
            log.warn("질문 재작성 실패 — 원문 질문으로만 검색합니다", exception);
            return Optional.of(queries);
        }

        for (String line : (raw == null ? "" : raw).split("\\R")) {
            String candidate = cleanQuery(line);
            if (candidate.isEmpty() || containsIgnoreCase(queries, candidate)) {
                continue;
            }
            queries.add(candidate);
            if (queries.size() >= MAX_QUERIES) {
                break;
            }
        }
        return Optional.of(queries);
    }

    // 지시어를 따옴표 예시로 보여줬더니 모델이 그대로 베껴 와서, 규칙으로만 적습니다.
    private static String rewritePrompt(String question, String conversation) {
        return """
                아래는 문서 검색 챗봇의 대화입니다. 마지막 질문을 검색용으로 다시 쓰세요.

                규칙:
                - 마지막 질문이 앞 내용을 가리키면, 가리키는 대상의 이름을 질문 안에 직접 넣습니다.
                - 앞 대화에 없는 내용은 지어내지 않습니다.
                - 반드시 질문 형태로 씁니다.
                - 앞 대화의 질문을 그대로 베끼지 않습니다.

                [이전 대화]
                %s

                [마지막 질문]
                %s

                서로 표현이 다른 질문 2개를 한 줄에 하나씩, 다른 말 없이 출력하세요.
                """.formatted(conversation.isBlank() ? "(없음)" : conversation, question);
    }

    /** 쿼리로 쓸 수 없는 줄이면 빈 문자열을 돌려줍니다. 같은 패키지의 다른 파이프라인도 씁니다. */
    static String cleanQuery(String line) {
        String text = line.strip();
        String previous;
        do {
            // "**1) 질문**" 처럼 겹쳐 붙어서 한 번에 안 벗겨집니다
            previous = text;
            text = LIST_MARKER.matcher(text).replaceFirst("");
            text = EDGE_NOISE.matcher(text).replaceAll("").strip();
            text = QUOTES.matcher(text).replaceAll("").strip();
        } while (!text.equals(previous));
        // "다음은 재작성한 질문입니다:" 같은 머리말 거르기
        if (text.length() < 3 || text.length() > 200 || text.endsWith(":") || text.endsWith("：")) {
            return "";
        }
        return text;
    }

    private static boolean containsIgnoreCase(List<String> queries, String candidate) {
        return queries.stream().anyMatch(candidate::equalsIgnoreCase);
    }

    // =================================================================== 실버 ②

    /**
     * 키워드 검색(하이브리드 검색의 절반).
     *
     * <p>왜 필요한가: 벡터 검색은 "의미가 비슷한 것"을 찾기 때문에 <b>정확히 일치해야 하는 것</b>에 약하다.
     * 모델명(RTX-4090), 조항 번호(제12조), 사람 이름 같은 고유명사가 그렇다.
     * 단순 단어 매칭 점수를 벡터 검색 결과에 합치는 것만으로도 체감 품질이 꽤 올라간다.
     *
     * <p>등장 횟수만 세면 "신청", "제도"같이 문서 전체에 깔린 단어가 점수를 독식해서 idf를 곱합니다.
     */
    @Override
    protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {
        List<String> terms = searchTerms(query);
        List<Document> chunks = this.knowledgeBase.chunksOf(docIds);
        if (terms.isEmpty() || chunks.isEmpty()) {
            return Optional.of(List.of());
        }

        List<String> lowered = chunks.stream()
                .map(chunk -> chunk.getText() == null ? "" : chunk.getText().toLowerCase(Locale.ROOT))
                .toList();

        double[] scores = new double[chunks.size()];
        for (String term : terms) {
            long df = lowered.stream().filter(text -> text.contains(term)).count();
            if (df == 0) {
                continue;
            }
            double idf = Math.log(1.0 + chunks.size() / (double) df);
            for (int i = 0; i < lowered.size(); i++) {
                int tf = countOccurrences(lowered.get(i), term);
                if (tf > 0) {
                    // 10번 나왔다고 10배 관련 있는 건 아니라서 log로 눌러둡니다
                    scores[i] += idf * (1 + Math.log(tf));
                }
            }
        }

        // 0점을 남기면 관련 없는 청크가 컨텍스트를 오염시킵니다
        return Optional.of(IntStream.range(0, chunks.size())
                .filter(i -> scores[i] > 0)
                .boxed()
                .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                .limit(options.topKOrDefault())
                .map(chunks::get)
                .toList());
    }

    private static List<String> searchTerms(String query) {
        List<String> terms = new ArrayList<>();
        for (String word : query.toLowerCase(Locale.ROOT).split("\\s+")) {
            String term = stripParticle(EDGE_PUNCT.matcher(word).replaceAll(""));
            if (term.length() >= 2 && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    /** 떼고도 2글자가 남을 때만 조사로 봅니다. 안 그러면 "도로"에서 "로"를 떼어냅니다. */
    private static String stripParticle(String word) {
        for (String particle : PARTICLES) {
            if (word.endsWith(particle) && word.length() - particle.length() >= 2) {
                return word.substring(0, word.length() - particle.length());
            }
        }
        return word;
    }

    private static int countOccurrences(String text, String term) {
        int total = 0;
        for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + term.length())) {
            total++;
        }
        return total;
    }

    // =================================================================== 실버 ③

    /**
     * 재정렬(rerank).
     *
     * <p>왜 필요한가: 임베딩 유사도 1위가 항상 정답 청크는 아니다. 그래서 검색을 일부러 넓게(topK×2) 해두고,
     * LLM에게 "이 청크가 이 질문에 답하는 데 얼마나 도움이 되냐"를 0~10점으로 채점시켜 상위만 남긴다.
     * (이 파이프라인은 rerank 토글이 켜져 있으면 자동으로 topK의 2배를 검색해온다)
     *
     * <p>재작성까지 켜면 후보가 20개를 넘겨서 앞쪽 여덟 개만 채점하고 나머지는 원래 순서로 뒤에 붙입니다.
     */
    @Override
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
        if (candidates.isEmpty()) {
            return Optional.of(List.of());
        }
        int scored = Math.min(candidates.size(), MAX_RERANK_CALLS);
        if (scored < candidates.size()) {
            log.info("재정렬 후보 {}개 중 앞 {}개만 채점합니다", candidates.size(), scored);
        }

        double[] scores = new double[scored];
        for (int i = 0; i < scored; i++) {
            scores[i] = relevanceScore(query, candidates.get(i));
        }

        List<Document> reranked = new ArrayList<>(candidates.size());
        IntStream.range(0, scored)
                .boxed()
                .sorted((a, b) -> Double.compare(scores[b], scores[a]))
                .forEach(index -> reranked.add(candidates.get(index)));
        reranked.addAll(candidates.subList(scored, candidates.size()));
        return Optional.of(reranked);
    }

    /** 하나 실패했다고 재정렬 전체를 버릴 이유는 없어서 0점으로 두고 넘어갑니다. */
    private double relevanceScore(String query, Document candidate) {
        String text = candidate.getText() == null ? "" : candidate.getText();
        String prompt = """
                질문: %s

                문서: %s

                이 문서가 질문에 답하는 데 얼마나 관련 있습니까?
                다른 말 없이 "점수: N" 한 줄만 출력하세요. N은 0에서 10 사이 정수입니다.
                """.formatted(query,
                text.length() > RERANK_SNIPPET_CHARS ? text.substring(0, RERANK_SNIPPET_CHARS) : text);
        try {
            return parseScore(chatClient().prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(prompt)
                    .call()
                    .content());
        }
        catch (Exception exception) {
            log.warn("재정렬 채점 실패 — 0점으로 둡니다", exception);
            return 0;
        }
    }

    private static double parseScore(String raw) {
        if (raw == null) {
            return 0;
        }
        for (Pattern pattern : SCORE_PATTERNS) {
            Matcher matcher = pattern.matcher(raw);
            if (matcher.find()) {
                return Math.min(Integer.parseInt(matcher.group(matcher.groupCount())), 10);
            }
        }
        return 0;
    }

    // =================================================================== 골드

    /**
     * 자기 검증(self-check) — Self-RAG의 축소판.
     *
     * <p>왜 필요한가: RAG를 붙여도 LLM은 컨텍스트에 없는 내용을 슬쩍 섞는다. 생성이 끝난 답변을 다시 한 번
     * "이 답변이 근거 안에 실제로 있는 내용인가?"로 검사하면, 할루시네이션을 사용자에게 경고할 수 있다.
     *
     * <p>답변이 이미 화면에 나간 뒤에 도는 단계라, 여기서 예외를 던지면 성공한 턴이 실패로 뒤집힙니다.
     */
    @Override
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
            RagOptions options) {
        if (answer.isBlank()) {
            return Optional.of("답변이 비어 있어 검증을 건너뛰었습니다");
        }
        if (sources.isEmpty()) {
            return Optional.of("주의: 근거로 쓴 청크가 없습니다");
        }
        String prompt = """
                [근거]
                %s

                [답변]
                %s

                답변의 모든 문장이 [근거] 안에 실제로 있는 내용인지 확인하세요.
                전부 근거에 있으면 "통과" 한 단어만 출력하세요.
                하나라도 없으면 "주의: <근거에 없는 내용 요약>" 형식으로 한 줄만 출력하세요.
                """.formatted(RagPrompts.formatContext(sources), answer);
        try {
            return Optional.of(verdictLine(chatClient().prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(prompt)
                    .call()
                    .content()));
        }
        catch (Exception exception) {
            log.warn("자기 검증 실패", exception);
            return Optional.of("검증하지 못했습니다 (LLM 호출 실패)");
        }
    }

    /** 첫 줄을 그냥 쓰면 형식을 어겼을 때 엉뚱한 문장이 판정으로 화면에 올라갑니다. */
    private static String verdictLine(String raw) {
        if (raw == null) {
            return "판정을 읽지 못했습니다";
        }
        return raw.lines()
                .map(StudentRagPipeline::stripDecoration)
                .filter(line -> line.startsWith("통과") || line.startsWith("주의"))
                .findFirst()
                .map(line -> line.length() > 120 ? line.substring(0, 120) + "…" : line)
                .orElse("판정을 읽지 못했습니다 (모델이 형식을 지키지 않음)");
    }

    private static String stripDecoration(String line) {
        String text = LIST_MARKER.matcher(line.strip()).replaceFirst("");
        return QUOTES.matcher(text).replaceAll("").strip();
    }
}

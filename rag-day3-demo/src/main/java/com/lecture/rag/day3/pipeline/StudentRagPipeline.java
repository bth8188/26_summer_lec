package com.lecture.rag.day3.pipeline;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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

    private static final Pattern ARTICLE_REFERENCE =
            Pattern.compile("제\\s*\\d+조(?:의\\s*\\d+)?(?:\\s*제\\s*\\d+항)?");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");

    private static final Set<String> STOP_WORDS = Set.of(
            "무엇", "무엇인가요", "어떻게", "알려주세요", "설명해주세요", "정한", "따른",
            "관련", "경우", "대한", "있는", "없는", "그리고", "또는", "해당");

    private static final List<String> KOREAN_PARTICLES = List.of(
            "으로부터", "에서부터", "에게서", "에서는", "으로는", "이라고", "이라는",
            "에서", "에게", "으로", "에는", "까지", "부터", "보다", "처럼", "하고",
            "이며", "라고", "의", "을", "를", "이", "가", "은", "는", "에", "와", "과", "도", "만");

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
        String historyText = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());
        String prompt = """
                당신은 한국어 문서 검색용 질의 재작성기입니다.

                [이전 대화]
                %s

                [현재 질문]
                %s

                현재 질문을 독립적으로 이해할 수 있는 검색 질의 3개로 바꿔 쓰세요.
                규칙:
                - 법령명, 조문 번호, 날짜, 비율, 기관명은 원문 그대로 보존합니다.
                - 서로 다른 표현을 사용하되 질문의 의미를 바꾸지 않습니다.
                - 답을 만들거나 추측하지 않습니다.
                - 외국어 단어나 한자를 섞지 말고 자연스러운 한국어만 사용합니다.
                - 번호, 불릿, 설명 없이 한 줄에 하나씩만 출력합니다.
                """.formatted(historyText.isBlank() ? "(이전 대화 없음)" : historyText, question);

        String response = chatClient().prompt()
                .options(ChatOptions.builder().temperature(0.0))
                .user(prompt)
                .call()
                .content();

        LinkedHashSet<String> queries = new LinkedHashSet<>();
        if (question != null && !question.isBlank()) {
            queries.add(question.strip());
        }
        if (response != null) {
            response.lines()
                    .map(StudentRagPipeline::cleanQueryLine)
                    .filter(line -> !line.isBlank())
                    .limit(3)
                    .forEach(queries::add);
        }
        return Optional.of(queries.stream().limit(4).toList());
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
        List<String> articleReferences = extractArticleReferences(query);
        List<String> keywords = extractKeywords(query);
        List<Document> allChunks = this.knowledgeBase.chunksOf(docIds);

        List<ScoredDocument> scored = new ArrayList<>();
        int originalIndex = 0;
        for (Document document : allChunks) {
            int score = keywordScore(document, query, articleReferences, keywords);
            if (score > 0) {
                scored.add(new ScoredDocument(document, score, originalIndex));
            }
            originalIndex++;
        }

        List<ScoredDocument> ranked = scored.stream()
                .sorted(Comparator.comparingInt(ScoredDocument::score).reversed()
                        .thenComparingInt(ScoredDocument::originalIndex))
                .toList();

        LinkedHashSet<Document> hits = new LinkedHashSet<>();
        for (ScoredDocument hit : ranked) {
            Document document = hit.document();
            if (!articleReferences.isEmpty() && containsAnyArticle(document, articleReferences)) {
                Document articleChunk = document;
                document = adjacentChunk(allChunks, document, 1)
                        .map(adjacent -> mergeChunks(articleChunk, adjacent))
                        .orElse(document);
            }
            hits.add(document);
            if (hits.size() >= options.topKOrDefault()) {
                break;
            }
        }
        return Optional.of(hits.stream().limit(options.topKOrDefault()).toList());
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

    private static int keywordScore(Document document, String query, List<String> articleReferences,
            List<String> keywords) {
        String text = normalize(document.getText());
        String fileName = normalize(metadataText(document, "fileName"));
        String normalizedQuery = normalize(query);
        int score = 0;

        for (String reference : articleReferences) {
            if (text.contains(reference)) {
                score += 1_000;
            }
        }
        for (String keyword : keywords) {
            int occurrences = countOccurrences(text, keyword);
            score += occurrences * (keyword.length() >= 4 ? 4 : 2);
            if (fileName.contains(keyword)) {
                score += 5;
            }
        }

        if (score == 0) {
            return 0;
        }

        boolean asksProposal = normalizedQuery.contains("입법예고") || normalizedQuery.contains("예고안");
        boolean asksAmendment = normalizedQuery.contains("개정이유") || normalizedQuery.contains("개정문");
        if (asksProposal) {
            score += fileName.contains("입법예고") ? 40 : 0;
        }
        else if (asksAmendment) {
            score += fileName.contains("개정문") || fileName.contains("개정이유") ? 40 : 0;
        }
        else {
            score += authorityBoost(fileName);
        }
        return score;
    }

    private static int authorityBoost(String fileName) {
        if (fileName.contains("현행법률")) {
            return 300;
        }
        if (fileName.contains("현행시행령")) {
            return 250;
        }
        if (fileName.contains("개정문") || fileName.contains("개정이유")) {
            return 150;
        }
        if (fileName.contains("시행안내")) {
            return 80;
        }
        if (fileName.contains("입법예고")) {
            return 20;
        }
        return 0;
    }

    private static boolean containsAnyArticle(Document document, List<String> articleReferences) {
        String text = normalize(document.getText());
        return articleReferences.stream().anyMatch(text::contains);
    }

    private static Optional<Document> adjacentChunk(List<Document> allChunks, Document document, int offset) {
        String docId = metadataText(document, "docId");
        Object indexValue = document.getMetadata().get("chunkIndex");
        if (!(indexValue instanceof Number index)) {
            return Optional.empty();
        }
        int targetIndex = index.intValue() + offset;
        return allChunks.stream()
                .filter(candidate -> docId.equals(metadataText(candidate, "docId")))
                .filter(candidate -> {
                    Object candidateIndex = candidate.getMetadata().get("chunkIndex");
                    return candidateIndex instanceof Number number && number.intValue() == targetIndex;
                })
                .findFirst();
    }

    private static Document mergeChunks(Document first, Document adjacent) {
        return Document.builder()
                .id(first.getId())
                .text(first.getText() + "\n" + adjacent.getText())
                .metadata(new java.util.LinkedHashMap<>(first.getMetadata()))
                .build();
    }

    private static List<String> extractArticleReferences(String query) {
        LinkedHashSet<String> references = new LinkedHashSet<>();
        Matcher matcher = ARTICLE_REFERENCE.matcher(query == null ? "" : query);
        while (matcher.find()) {
            references.add(normalize(matcher.group()));
        }
        return List.copyOf(references);
    }

    private static List<String> extractKeywords(String query) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        Matcher matcher = WORD.matcher(query == null ? "" : query.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = stripKoreanParticle(matcher.group());
            if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
                keywords.add(word);
            }
        }
        return List.copyOf(keywords);
    }

    private static String stripKoreanParticle(String word) {
        for (String particle : KOREAN_PARTICLES) {
            if (word.endsWith(particle) && word.length() - particle.length() >= 2) {
                return word.substring(0, word.length() - particle.length());
            }
        }
        return word;
    }

    private static int countOccurrences(String text, String keyword) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(keyword, from)) >= 0) {
            count++;
            from += keyword.length();
        }
        return count;
    }

    private static String cleanQueryLine(String line) {
        return line == null ? "" : line
                .replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "")
                .replaceAll("^[\\\"'“”]+|[\\\"'“”]+$", "")
                .strip();
    }

    private static String metadataText(Document document, String key) {
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    private record ScoredDocument(Document document, int score, int originalIndex) {
    }
}

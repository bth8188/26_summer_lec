package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

/**
 * ★ 캡스톤 실습 파일 ★ — 4개 TODO 구현 완료 버전
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
        return "StudentRagPipeline.java의 TODO를 채워서 만든 나만의 파이프라인.";
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
     * 이전 대화 맥락을 참고해 질문을 완전한 문장으로 바꾸고, 표현이 다른 버전 여러 개를 만들어
     * 벡터 검색(pgvector)의 recall(회수율)을 올린다.
     */
    @Override
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
                                                    RagOptions options) {

        String historyText = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());

        String prompt = """
                다음은 사용자와의 이전 대화입니다.
                %s

                위 대화의 맥락을 참고하여, 마지막 질문을 문서 검색에 쓸 수 있도록 완전한 문장으로 바꿔 쓰세요.
                서로 표현이 다른 3개의 검색어를 줄바꿈으로만 구분해서 출력하고, 번호나 설명은 붙이지 마세요.

                마지막 질문: %s
                """.formatted(historyText, question);

        String response = chatClient().prompt().user(prompt).call().content();

        List<String> rewritten = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();

        // 재작성이 이상하게 나와도 최소한의 검색 품질을 보장하기 위해 원문 질문도 포함
        List<String> queries = new ArrayList<>(rewritten);
        if (!queries.contains(question)) {
            queries.add(question);
        }

        return queries.isEmpty() ? Optional.empty() : Optional.of(queries);
    }

    // =================================================================== 실버 ②

    /**
     * 키워드 검색(하이브리드 검색의 절반).
     * pgvector의 벡터 검색은 "의미가 비슷한 것"을 찾기 때문에, 모델명/조항 번호/사람 이름처럼
     * "정확히 일치해야 하는" 검색에는 약하다. 단순 단어 매칭 점수를 별도로 계산해서 보완한다.
     */
    @Override
    protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {

        List<Document> chunks = knowledgeBase.chunksOf(docIds);

        List<String> words = Arrays.stream(query.split("\\s+"))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(w -> w.length() >= 2)
                .distinct()
                .toList();

        if (words.isEmpty() || chunks.isEmpty()) {
            return Optional.empty();
        }

        List<Document> ranked = chunks.stream()
                .map(doc -> Map.entry(doc, keywordScore(doc, words)))
                .filter(entry -> entry.getValue() > 0) // 점수 0인 청크는 버림 (관련 없는 청크가 컨텍스트를 오염시키지 않도록)
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(options.topKOrDefault())
                .map(Map.Entry::getKey)
                .toList();

        return ranked.isEmpty() ? Optional.empty() : Optional.of(ranked);
    }

    /** 청크 안에 검색어가 몇 번 등장하는지(대소문자 무시) 세서 점수로 사용한다. */
    private int keywordScore(Document doc, List<String> words) {
        String text = doc.getText().toLowerCase();
        int score = 0;
        for (String word : words) {
            int idx = 0;
            while ((idx = text.indexOf(word, idx)) != -1) {
                score++;
                idx += word.length();
            }
        }
        return score;
    }

    // =================================================================== 실버 ③

    /**
     * 재정렬(rerank).
     * 벡터 검색 1등이 항상 정답 청크는 아니므로, 후보를 넓게(topK×2) 가져온 뒤
     * LLM에게 "이 문서가 질문에 얼마나 도움이 되는지" 0~10점으로 채점시켜 상위만 남긴다.
     */
    @Override
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<Document> ranked = candidates.stream()
                .map(doc -> Map.entry(doc, relevanceScore(query, doc)))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(options.topKOrDefault())
                .map(Map.Entry::getKey)
                .toList();

        return Optional.of(ranked);
    }

    /** LLM에게 0~10점으로 채점시키고, 응답에서 첫 숫자만 정규식으로 뽑는다. */
    private int relevanceScore(String query, Document doc) {
        String prompt = """
                질문: %s
                문서: %s
                이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 숫자 하나만 답하세요.
                """.formatted(query, doc.getText());

        String response = chatClient().prompt().user(prompt).call().content();

        Matcher matcher = Pattern.compile("\\d+").matcher(response);
        if (matcher.find()) {
            return Math.min(Integer.parseInt(matcher.group()), 10);
        }
        return 0; // LLM이 숫자를 안 주면 최하 점수 처리
    }

    // =================================================================== 골드

    /**
     * 자기 검증(self-check) — Self-RAG의 축소판.
     * 생성된 답변이 근거(context) 안에 실제로 있는 내용인지 LLM에게 한 번 더 판정을 맡긴다.
     */
    @Override
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
                                         RagOptions options) {

        if (sources.isEmpty()) {
            return Optional.of("주의: 근거 문서가 없어 검증할 수 없습니다.");
        }

        String context = RagPrompts.formatContext(sources);

        String prompt = """
                [근거]
                %s

                [답변]
                %s

                답변의 모든 문장이 근거에 실제로 있는 내용인지 판정하세요.
                형식: '통과' 또는 '주의: <근거에 없는 내용 요약>' 한 줄로만 답하세요.
                """.formatted(context, answer);

        String result = chatClient().prompt().user(prompt).call().content();

        return Optional.of(result.trim());
    }
}
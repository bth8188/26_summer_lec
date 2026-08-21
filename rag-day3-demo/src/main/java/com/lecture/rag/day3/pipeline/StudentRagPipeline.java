package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
 * ★ 캡스톤 실습 파일 ★ — 실버/골드 과제 네 개(질문 재작성/키워드 검색/재정렬/자기 검증)를
 * 모두 구현한 참조 파이프라인. 검색·프롬프트·스트리밍·화면 표시 배관은 {@link AbstractRagPipeline}이
 * 처리하고, 이 클래스는 아래 네 개의 훅만 채운다.
 *
 * <pre>
 *  실버 ① rewriteQueries()  질문 재작성 / multi-query
 *  실버 ② keywordSearch()  키워드 검색을 섞는 하이브리드 검색
 *  실버 ③ rerank()         LLM으로 후보 재정렬
 *  골드   selfCheck()       답변이 근거에 실제로 있는지 자기 검증
 * </pre>
 *
 * <p>실행 방법: 프론트 우측 상단 파이프라인 드롭다운에서 "내 파이프라인"을 고르고,
 * 설정(⚙) 패널에서 해당 기능 토글을 켠다. 각 단계는 실행 시간과 결과 요약까지 화면에 뜬다.
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
        return "질문 재작성 · 키워드 검색 · 재정렬 · 자기 검증을 모두 구현한 파이프라인.";
    }

    @Override
    public List<String> supportedFeatures() {
        return List.of(
                RagOptions.FEATURE_REWRITE,
                RagOptions.FEATURE_KEYWORD,
                RagOptions.FEATURE_RERANK,
                RagOptions.FEATURE_SELF_CHECK);
    }

    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");

    // =================================================================== 실버 ①

    /**
     * 질문 재작성 / multi-query 검색.
     *
     * <p>왜 필요한가: "그거 얼마야?" 같은 짧은 질문은 임베딩이 잡을 정보가 거의 없어서 검색이 헛돈다.
     * 이전 대화를 참고해 완전한 문장으로 다시 쓰거나("연회비는 얼마인가요?"),
     * 표현이 다른 여러 버전을 만들어 각각 검색하면 회수율(recall)이 올라간다.
     */
    @Override
    protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history,
            RagOptions options) {
        String historyText = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());
        String prompt = """
                다음은 사용자와의 이전 대화입니다.

                [이전 대화]
                %s

                [마지막 질문]
                %s

                마지막 질문을 문서 검색에 쓸 수 있도록 완전한 문장으로 바꿔 쓰세요.
                서로 표현이 다른 3개를 만들어서 줄바꿈으로만 구분해 출력하고, 번호나 설명은 붙이지 마세요.
                """.formatted(historyText.isBlank() ? "(없음)" : historyText, question);

        String raw = chatClient().prompt()
                .options(ChatOptions.builder().temperature(0.3))
                .user(prompt)
                .call()
                .content();

        List<String> queries = new ArrayList<>();
        queries.add(question); // 재작성이 엉뚱해도 최소한의 검색 품질을 보장
        if (raw != null) {
            for (String line : raw.lines().toList()) {
                String cleaned = line.strip();
                if (!cleaned.isEmpty() && !queries.contains(cleaned)) {
                    queries.add(cleaned);
                }
            }
        }
        return Optional.of(queries);
    }

    // =================================================================== 실버 ②

    /**
     * 키워드 검색(하이브리드 검색의 절반).
     *
     * <p>왜 필요한가: 벡터 검색은 "의미가 비슷한 것"을 찾기 때문에 <b>정확히 일치해야 하는 것</b>에 약하다.
     * 모델명(RTX-4090), 조항 번호(제12조), 사람 이름 같은 고유명사가 그렇다.
     * 단순 단어 매칭 점수를 벡터 검색 결과에 합치는 것만으로도 체감 품질이 꽤 올라간다.
     */
    @Override
    protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {
        List<String> words = List.of(query.toLowerCase().split("\\s+")).stream()
                .filter(word -> word.length() >= 2)
                .toList();
        if (words.isEmpty()) {
            return Optional.of(List.of());
        }

        record Scored(Document document, int score) {
        }

        List<Scored> scored = new ArrayList<>();
        for (Document chunk : knowledgeBase.chunksOf(docIds)) {
            String text = chunk.getText() == null ? "" : chunk.getText().toLowerCase();
            int score = 0;
            for (String word : words) {
                int from = 0;
                int idx;
                while ((idx = text.indexOf(word, from)) >= 0) {
                    score++;
                    from = idx + word.length();
                }
            }
            if (score > 0) {
                scored.add(new Scored(chunk, score));
            }
        }

        return Optional.of(scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(options.topKOrDefault())
                .map(Scored::document)
                .toList());
    }

    // =================================================================== 실버 ③

    /**
     * 재정렬(rerank).
     *
     * <p>왜 필요한가: 임베딩 유사도 1위가 항상 정답 청크는 아니다. 그래서 검색을 일부러 넓게(topK×2) 해두고,
     * LLM에게 "이 청크가 이 질문에 답하는 데 얼마나 도움이 되냐"를 0~10점으로 채점시켜 상위만 남긴다.
     * (이 파이프라인은 rerank 토글이 켜져 있으면 자동으로 topK의 2배를 검색해온다)
     *
     * <p>후보 개수만큼 LLM을 호출하므로(8개면 8번) 답변까지 10초 이상 걸릴 수 있다 —
     * 느린 게 정상이고, 그 대가로 정확도를 사는 것이다.
     */
    @Override
    protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
        record Scored(Document document, int score) {
        }

        List<Scored> scored = new ArrayList<>();
        for (Document candidate : candidates) {
            String prompt = """
                    질문: %s
                    문서: %s
                    이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 숫자 하나만 답하세요.
                    """.formatted(query, RagPrompts.squeeze(candidate.getText()));

            String raw = chatClient().prompt()
                    .options(ChatOptions.builder().temperature(0.0))
                    .user(prompt)
                    .call()
                    .content();

            scored.add(new Scored(candidate, extractScore(raw)));
        }

        return Optional.of(scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(options.topKOrDefault())
                .map(Scored::document)
                .toList());
    }

    private static int extractScore(String raw) {
        if (raw == null) {
            return 0;
        }
        Matcher matcher = FIRST_NUMBER.matcher(raw);
        return matcher.find() ? Math.min(10, Integer.parseInt(matcher.group())) : 0;
    }

    // =================================================================== 골드

    /**
     * 자기 검증(self-check) — Self-RAG의 축소판.
     *
     * <p>왜 필요한가: RAG를 붙여도 LLM은 컨텍스트에 없는 내용을 슬쩍 섞는다. 생성이 끝난 답변을 다시 한 번
     * "이 답변이 근거 안에 실제로 있는 내용인가?"로 검사하면, 할루시네이션을 사용자에게 경고할 수 있다.
     */
    @Override
    protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources,
            RagOptions options) {
        String context = RagPrompts.formatContext(sources);
        String prompt = """
                [근거]
                %s

                [답변]
                %s

                답변의 모든 문장이 근거에 실제로 있는 내용인지 판정하세요.
                형식: '통과' 또는 '주의: <근거에 없는 내용 요약>' 한 줄로만 출력하세요.
                """.formatted(context, answer);

        String verdict = chatClient().prompt()
                .options(ChatOptions.builder().temperature(0.0))
                .user(prompt)
                .call()
                .content();

        return Optional.of(verdict == null || verdict.isBlank() ? "판정 실패" : verdict.strip().lines().findFirst().orElse("판정 실패"));
    }
}

package com.lecture.rag.m23mmr;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.lecture.rag.lab21m1.SlidingWindowSplitter;

/**
 * M2.3 — MMR(Maximal Marginal Relevance). 순수 Top-K와 나란히 비교해서
 * "관련성은 높은데 서로 거의 같은 내용"인 중복 청크를 MMR이 얼마나 걸러내는지 보여준다.
 * Sliding window로 일부러 겹치게 잘라서(중복 문제를 재현) 그 위에서 MMR 효과를 확인한다.
 * 실행: ./mvnw spring-boot:run -Dspring-boot.run.profiles=mmr
 */
@Component
@Profile("mmr")
public class MmrSearchDemo implements CommandLineRunner {

    private static final int CANDIDATE_POOL_SIZE = 15; // MMR이 고를 수 있는 후보 풀 크기
    private static final int TOP_N = 5;
    // lambda가 1에 가까울수록 "관련성"만, 0에 가까울수록 "다양성"만 중시 (0.5~0.7이 실무에서 흔한 시작값)
    private static final double LAMBDA = 0.6;

    private final EmbeddingModel embeddingModel;

    public MmrSearchDemo(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    private record Candidate(Document document, float[] embedding, double similarityToQuery) {}

    @Override
    public void run(String... args) {
        List<Document> chunks = loadOverlappingChunks();
        System.out.println("=== Sliding window로 겹치게 자른 청크 " + chunks.size() + "개 인덱싱 완료 ===");

        String query = "제주도의 기후와 날씨는 어떤가요?";
        List<Candidate> candidates = embedAndScore(chunks, query);

        System.out.println();
        System.out.println("################ Top-" + TOP_N + " — 순수 유사도 순 ################");
        List<Candidate> plainTopN = candidates.subList(0, Math.min(TOP_N, candidates.size()));
        print(plainTopN);

        System.out.println();
        System.out.println("################ Top-" + TOP_N + " — MMR (lambda=" + LAMBDA + ") ################");
        List<Candidate> mmrTopN = mmrSelect(candidates, TOP_N);
        print(mmrTopN);

        System.out.println();
        System.out.println("=== 관찰 포인트 ===");
        System.out.println("순수 Top-" + TOP_N + "는 유사도 순서 그대로라 인접한(=서로 겹치는) 청크가 나란히 앞쪽에 몰림.");
        System.out.println("MMR Top-" + TOP_N + "는 순위(위 [0]~[" + (TOP_N - 1) + "] 순서)가 바뀌어서 서로 다른 주제의 청크가 먼저 섞여 나옴 — 후보 풀(" + CANDIDATE_POOL_SIZE + "개)이 topN(" + TOP_N + "개)보다 훨씬 크고 중복 청크가 많을수록, 아예 순수 Top-" + TOP_N + "에 없던 청크가 MMR 결과에만 등장하는 경우도 생김.");
    }

    private List<Document> loadOverlappingChunks() {
        PagePdfDocumentReader reader = new PagePdfDocumentReader("classpath:/scenarios/6-wiki-jeju.pdf");
        List<Document> pages = reader.get();
        String combined = pages.stream().map(Document::getText).reduce("", (a, b) -> a + "\n\n" + b);
        combined = combined.replaceAll("[ \\t]+", " ");

        SlidingWindowSplitter splitter = new SlidingWindowSplitter(120, 60); // 약 50% 겹침, 짧은 문서라 후보 수 확보 위해 촘촘하게
        return splitter.split(new Document(combined));
    }

    private List<Candidate> embedAndScore(List<Document> chunks, String query) {
        float[] queryVec = embeddingModel.embed(query);

        List<Candidate> all = new ArrayList<>();
        for (Document chunk : chunks) {
            float[] vec = embeddingModel.embed(chunk.getText());
            all.add(new Candidate(chunk, vec, cosine(queryVec, vec)));
        }
        all.sort((a, b) -> Double.compare(b.similarityToQuery(), a.similarityToQuery()));
        return all.subList(0, Math.min(CANDIDATE_POOL_SIZE, all.size()));
    }

    /**
     * MMR: 매번 "질문과의 관련성"과 "이미 뽑힌 것들과의 최대 유사도(=중복도)"를 같이 고려해서
     * score = lambda * relevance - (1 - lambda) * maxSimilarityToSelected 가 가장 높은 후보를 하나씩 뽑는다.
     */
    private List<Candidate> mmrSelect(List<Candidate> candidates, int topN) {
        List<Candidate> pool = new ArrayList<>(candidates);
        List<Candidate> selected = new ArrayList<>();

        while (!pool.isEmpty() && selected.size() < topN) {
            Candidate best = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (Candidate candidate : pool) {
                double maxSimToSelected = 0.0;
                for (Candidate already : selected) {
                    maxSimToSelected = Math.max(maxSimToSelected, cosine(candidate.embedding(), already.embedding()));
                }
                double score = LAMBDA * candidate.similarityToQuery() - (1 - LAMBDA) * maxSimToSelected;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }

            selected.add(best);
            pool.remove(best);
        }
        return selected;
    }

    private void print(List<Candidate> results) {
        for (int i = 0; i < results.size(); i++) {
            Candidate c = results.get(i);
            System.out.printf("  [%d] 유사도 %.4f | %s%n", i, c.similarityToQuery(), preview(c.document().getText(), 100));
        }
    }

    private String preview(String text, int len) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.substring(0, Math.min(len, t.length())) + (t.length() > len ? "..." : "");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}

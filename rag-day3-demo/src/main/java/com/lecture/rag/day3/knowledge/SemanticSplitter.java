package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Day2 M2.1의 Semantic Chunking을 캡스톤으로 옮겨온 것입니다. 문장 단위로 쪼갠 뒤 각 문장을 임베딩하고,
 * 인접 문장의 코사인 유사도가 임계값 아래로 떨어지는 지점을 주제가 바뀐 경계로 보고 자릅니다.
 *
 * <p>문장마다 임베딩을 한 번씩 부르기 때문에 다른 전략보다 인덱싱이 눈에 띄게 느립니다. 그 대신
 * 글자 수로 자를 때 생기는 "문단 한가운데가 잘리는" 문제가 줄어듭니다.
 */
class SemanticSplitter {

    /** 이 값보다 유사도가 낮으면 주제가 바뀐 것으로 봅니다. */
    private final double dropThreshold;

    /** 경계를 못 찾아도 이 길이를 넘으면 자릅니다. 임베딩이 계속 비슷하게 나올 때의 안전장치입니다. */
    private final int maxChunkChars;

    private final EmbeddingModel embeddingModel;

    SemanticSplitter(EmbeddingModel embeddingModel, double dropThreshold, int maxChunkChars) {
        this.embeddingModel = embeddingModel;
        this.dropThreshold = dropThreshold;
        this.maxChunkChars = Math.max(100, maxChunkChars);
    }

    List<String> split(String text) {
        List<String> sentences = sentences(text);
        if (sentences.size() <= 1) {
            return sentences;
        }

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        float[] previous = null;
        for (String sentence : sentences) {
            float[] vector = this.embeddingModel.embed(sentence);
            boolean topicChanged = previous != null && cosine(previous, vector) < this.dropThreshold;
            boolean tooLong = current.length() + sentence.length() > this.maxChunkChars;
            if (!current.isEmpty() && (topicChanged || tooLong)) {
                chunks.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(sentence).append(' ');
            previous = vector;
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().strip());
        }
        return chunks;
    }

    /** 한국어는 마침표 없이 "~한다"로 끝나는 문장이 많아 종결어미도 경계로 봅니다. */
    private static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (String piece : text.split("(?<=[.!?])\\s+|(?<=다\\.)\\s*|\\n{2,}")) {
            String sentence = piece.strip();
            if (!sentence.isBlank()) {
                result.add(sentence);
            }
        }
        return result;
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

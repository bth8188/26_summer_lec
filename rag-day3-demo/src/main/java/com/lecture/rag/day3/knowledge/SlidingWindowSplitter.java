package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Day2 M2.1의 Sliding Window 청킹. chunkSize만큼 창을 만들고 stride(&lt; chunkSize)만큼만 이동해서
 * 청크끼리 서로 겹치게 만든다. Spring AI TokenTextSplitter에는 overlap 파라미터가 없어 직접 구현한 것.
 *
 * <p>겹침(overlap)을 주면 "문장 중간에서 잘려서 답을 못 찾는" 문제가 줄지만, 같은 문장이 여러 청크에
 * 중복 저장돼서 인덱스 크기와 임베딩 비용이 늘어난다 — UI에서 overlap을 0과 100으로 바꿔보며 비교해볼 것.
 */
class SlidingWindowSplitter implements ChunkingStrategy.CharSplitter {

    private final int windowChars;
    private final int strideChars;

    SlidingWindowSplitter(int windowChars, int strideChars) {
        this.windowChars = Math.max(50, windowChars);
        this.strideChars = Math.clamp(strideChars, 1, this.windowChars);
    }

    @Override
    public List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + windowChars, text.length());
            String piece = text.substring(start, end).trim();
            if (!piece.isBlank()) {
                chunks.add(piece);
            }
            if (end == text.length()) {
                break;
            }
            start += strideChars;
        }
        return chunks;
    }
}

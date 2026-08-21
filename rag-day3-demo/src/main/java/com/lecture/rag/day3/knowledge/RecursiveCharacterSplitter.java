package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Day2 M2.1의 Recursive Character Splitting을 캡스톤 프로젝트로 옮겨온 것.
 * "가능하면 자연스러운 경계에서 자르되, 그게 너무 크면 한 단계 더 작은 경계로 내려가서 다시 시도한다."
 *
 * <p>PDF에서 추출한 텍스트의 줄바꿈은 "시각적 줄바꿈"(word wrap)일 뿐 의미 단락 경계가 아니라서
 * {@code \n}을 우선순위 상단에 두면 오히려 문장을 잘게 쪼갠다 — 문장부호를 최우선으로 둔다.
 */
class RecursiveCharacterSplitter implements ChunkingStrategy.CharSplitter {

    private static final String[] SEPARATORS = { ". ", "다. ", "\n", " " };

    private final int maxChunkChars;

    RecursiveCharacterSplitter(int maxChunkChars) {
        this.maxChunkChars = Math.max(50, maxChunkChars);
    }

    @Override
    public List<String> split(String text) {
        return splitRecursive(text, 0);
    }

    private List<String> splitRecursive(String text, int separatorIndex) {
        List<String> result = new ArrayList<>();
        if (text.length() <= maxChunkChars || separatorIndex >= SEPARATORS.length) {
            if (!text.isBlank()) {
                result.add(text.trim());
            }
            return result;
        }
        String separator = SEPARATORS[separatorIndex];
        String[] parts = text.split(Pattern.quote(separator));

        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.isEmpty() ? part : current + separator + part;
            if (candidate.length() > maxChunkChars) {
                if (current.isEmpty()) {
                    flush(result, part, separatorIndex);
                }
                else {
                    flush(result, current.toString(), separatorIndex);
                    current = new StringBuilder(part);
                }
            }
            else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            flush(result, current.toString(), separatorIndex);
        }
        return result;
    }

    // 이미 maxChunkChars 이내면 그대로 채택하고, 넘칠 때만 다음 단계 구분자로 재귀 분할
    private void flush(List<String> result, String text, int separatorIndex) {
        if (text.length() <= maxChunkChars) {
            if (!text.isBlank()) {
                result.add(text.trim());
            }
        }
        else {
            result.addAll(splitRecursive(text, separatorIndex + 1));
        }
    }
}

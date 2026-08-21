package com.lecture.rag.day3.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 은행법·시행령처럼 {@code 제30조}, {@code 제30조의3} 형태로 구성된 문서를 조문 단위로 자른다.
 *
 * <p>조문 하나가 제한보다 길 때만 문장 경계로 다시 분할하며, 분할된 모든 조각 앞에 조문 제목을
 * 반복해서 붙인다. 따라서 벡터 검색이 조문의 중간 조각을 찾더라도 조문 번호와 제목을 잃지 않는다.
 * 조문 표기가 없는 보도자료·공문은 재귀 문자 분할로 자동 대체한다.
 */
final class LegalDocumentSplitter implements ChunkingStrategy.CharSplitter {

    private static final Pattern ARTICLE_HEADING = Pattern.compile(
            "(?m)^\\s*(제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?(?:\\s*\\([^\\r\\n]{1,100}\\))?)");
    private static final Pattern ARTICLE_NUMBER = Pattern.compile(
            "^\\s*(제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?)");

    private final int maxChunkChars;
    private final RecursiveCharacterSplitter fallback;

    LegalDocumentSplitter(int maxChunkChars) {
        this.maxChunkChars = Math.max(200, maxChunkChars);
        this.fallback = new RecursiveCharacterSplitter(this.maxChunkChars);
    }

    @Override
    public List<String> split(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.isBlank()) {
            return List.of();
        }

        Matcher matcher = ARTICLE_HEADING.matcher(normalized);
        List<Integer> starts = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
        }
        if (starts.isEmpty()) {
            return this.fallback.split(normalized);
        }

        List<String> chunks = new ArrayList<>();
        String preamble = normalized.substring(0, starts.getFirst()).strip();
        if (!preamble.isBlank()) {
            chunks.addAll(this.fallback.split(preamble));
        }

        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : normalized.length();
            splitArticle(normalized.substring(starts.get(i), end).strip(), chunks);
        }
        return chunks;
    }

    /** 청크 첫머리에서 정규화된 조문 번호를 꺼내 PGVector 메타데이터에 넣을 때 사용한다. */
    static String articleNumber(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = ARTICLE_NUMBER.matcher(text);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : null;
    }

    private void splitArticle(String article, List<String> chunks) {
        if (article.length() <= this.maxChunkChars) {
            chunks.add(article);
            return;
        }

        Matcher headingMatcher = ARTICLE_HEADING.matcher(article);
        if (!headingMatcher.find() || headingMatcher.start() != 0) {
            chunks.addAll(this.fallback.split(article));
            return;
        }

        String heading = headingMatcher.group(1).strip();
        String body = article.substring(headingMatcher.end()).strip();
        int bodyChunkChars = Math.max(100, this.maxChunkChars - heading.length() - 1);
        List<String> bodyChunks = new RecursiveCharacterSplitter(bodyChunkChars).split(body);
        if (bodyChunks.isEmpty()) {
            chunks.add(heading);
            return;
        }
        bodyChunks.forEach(piece -> chunks.add(heading + "\n" + piece));
    }
}

package com.lecture.rag.lab21m1;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M2.1 — 마크다운 구조 청킹. StructureBasedSplitter가 모든 헤더를 같은 무게로 보고 평평하게 자르는 것과 달리,
 * 헤더 레벨을 유지하면서 각 청크에 "H1 > H2 > H3" 경로를 붙인다.
 * "제N조"는 조항 번호 자체가 고유해서 평평하게 잘라도 되지만, 마크다운 헤더는 "Getting Started"처럼
 * 흔한 단어라 상위 헤더 없이는 어느 문서 얘기인지 알 수 없다.
 * 섹션이 maxChunkChars를 넘으면 RecursiveCharacterSplitter로 한 번 더 쪼개되, 쪼갠 조각 전부에 같은 경로를 붙인다.
 */
public class MarkdownSectionSplitter {

    public static final String SECTION_METADATA = "section";

    private static final int MAX_LEVEL = 6;
    private static final Pattern HEADER = Pattern.compile("^(#{1,6})\\s+(.+)$");
    // 헤더 줄에 박힌 뱃지/링크는 임베딩에 노이즈만 되므로 제목만 남긴다
    // (예: "# Spring AI [![build status](...)](...)" → "Spring AI")
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");

    private final int maxChunkChars;
    private final RecursiveCharacterSplitter overflowSplitter;

    public MarkdownSectionSplitter(int maxChunkChars) {
        this.maxChunkChars = maxChunkChars;
        this.overflowSplitter = new RecursiveCharacterSplitter(maxChunkChars);
    }

    public List<Document> split(Document doc) {
        List<Document> chunks = new ArrayList<>();
        String[] path = new String[MAX_LEVEL + 1];   // 배열 인덱스를 헤더 레벨로 그대로 사용 (path[1]=H1 ... path[6]=H6)
        StringBuilder body = new StringBuilder();
        String section = "";
        boolean inCodeFence = false;

        for (String line : doc.getText().split("\n", -1)) {
            if (line.startsWith("```")) {
                inCodeFence = !inCodeFence;
            }

            Matcher header = HEADER.matcher(line);
            // 코드블록 안의 "# 설치" 같은 주석 줄이 헤더로 오인되면 안 되므로 fence 안에서는 헤더 판정을 건너뛴다
            if (!inCodeFence && header.matches()) {
                flush(chunks, section, body);
                int level = header.group(1).length();
                path[level] = cleanTitle(header.group(2));
                for (int deeper = level + 1; deeper <= MAX_LEVEL; deeper++) {
                    path[deeper] = null;   // 새 섹션이 시작되면 그보다 깊은 레벨의 제목은 무효
                }
                section = breadcrumb(path);
            } else {
                body.append(line).append('\n');
            }
        }
        flush(chunks, section, body);
        return chunks;
    }

    private void flush(List<Document> chunks, String section, StringBuilder body) {
        String text = body.toString().strip();
        body.setLength(0);
        if (text.isEmpty()) {
            return;
        }
        for (String piece : applyMaxSize(text)) {
            // 메타데이터는 임베딩되지 않으므로, 검색에 걸리게 하려면 경로를 본문 앞에도 넣어야 한다
            String content = section.isEmpty() ? piece : section + "\n\n" + piece;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(SECTION_METADATA, section);
            chunks.add(new Document(content, metadata));
        }
    }

    private List<String> applyMaxSize(String text) {
        if (text.length() <= maxChunkChars) {
            return List.of(text);
        }
        return overflowSplitter.split(new Document(text)).stream().map(Document::getText).toList();
    }

    private String breadcrumb(String[] path) {
        StringBuilder crumb = new StringBuilder();
        for (int level = 1; level <= MAX_LEVEL; level++) {
            if (path[level] == null) {
                continue;
            }
            if (!crumb.isEmpty()) {
                crumb.append(" > ");
            }
            crumb.append(path[level]);
        }
        return crumb.toString();
    }

    private String cleanTitle(String rawTitle) {
        String title = IMAGE.matcher(rawTitle).replaceAll("");
        title = LINK.matcher(title).replaceAll("$1");
        return title.replaceAll("\\s+", " ").strip();
    }
}

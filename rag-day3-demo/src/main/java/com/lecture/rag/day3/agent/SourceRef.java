package com.lecture.rag.day3.agent;

import java.util.Map;

import org.springframework.ai.document.Document;

/**
 * 답변의 근거로 쓴 청크 한 개를 프론트에 넘기기 위한 DTO.
 *
 * @param index    프롬프트에 넣은 순번(1부터). LLM이 답변에 [1] [2] 로 인용하는 그 번호다.
 * @param chunkId  VectorStore가 부여한 청크 ID
 * @param docId    이 청크가 속한 업로드 문서 ID
 * @param fileName 원본 파일명
 * @param page     PDF 페이지 번호 (텍스트 파일이면 null)
 * @param score    유사도 점수 (코사인 유사도, 1에 가까울수록 비슷함)
 * @param text     청크 본문 — 프론트에서 "이 근거 실제로 뭐라고 써있나"를 펼쳐볼 수 있게 전부 보낸다
 */
public record SourceRef(
        int index,
        String chunkId,
        String docId,
        String fileName,
        Integer page,
        Double score,
        String text) {

    public static SourceRef from(int index, Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new SourceRef(
                index,
                document.getId(),
                asString(metadata.get("docId")),
                asString(metadata.getOrDefault("fileName", metadata.get("file_name"))),
                asInteger(metadata.get("page")),
                document.getScore(),
                document.getText());
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}

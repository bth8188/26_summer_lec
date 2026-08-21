package com.lecture.rag.day3.knowledge;

/**
 * 인덱싱이 끝난 업로드 문서 하나. 프론트 사이드바의 "지식 베이스" 목록에 그대로 그려진다.
 *
 * @param docId      서버가 발급한 문서 ID (삭제/필터링에 사용)
 * @param fileName   원본 파일명
 * @param type       pdf | text
 * @param pageCount  PDF 페이지 수 (텍스트 파일이면 1)
 * @param chunkCount 만들어진 청크 개수
 * @param charCount  원문 총 글자 수
 * @param strategy   사용한 청킹 전략 id
 * @param chunkSize  청크 크기 (TOKEN 전략은 토큰 수, 나머지는 글자 수)
 * @param overlap    청크 간 겹침 (SLIDING 전략에서만 사용)
 * @param indexedAt  인덱싱 완료 시각 (epoch millis)
 * @param embedMs    임베딩에 걸린 시간(ms)
 */
public record IndexedDocument(
        String docId,
        String fileName,
        String type,
        int pageCount,
        int chunkCount,
        int charCount,
        String strategy,
        int chunkSize,
        int overlap,
        long indexedAt,
        long embedMs) {
}

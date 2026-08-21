package com.lecture.rag.day3.agent;

import java.util.List;

/**
 * {@code POST /api/chat} 요청 바디.
 *
 * @param question   이번 질문
 * @param pipelineId 실행할 파이프라인 id ("basic" / "student" / 학생이 새로 만든 것)
 * @param history    이전 대화 (프론트가 보관하고 매 요청마다 보낸다 — 서버는 세션을 들고 있지 않다)
 * @param options    검색/생성 옵션
 * @param docIds     특정 문서로만 검색을 제한하고 싶을 때 (비어 있으면 전체 문서 대상)
 */
public record ChatRequest(
        String question,
        String pipelineId,
        List<Turn> history,
        RagOptions options,
        List<String> docIds) {

    /**
     * 대화 한 턴.
     *
     * @param role "user" 또는 "assistant"
     */
    public record Turn(String role, String text) {
        public boolean isUser() {
            return !"assistant".equalsIgnoreCase(role);
        }
    }

    public String questionOrEmpty() {
        return question == null ? "" : question.trim();
    }

    public RagOptions optionsOrDefault() {
        return options == null ? RagOptions.defaults() : options;
    }

    public List<Turn> historyOrEmpty() {
        return history == null ? List.of() : history;
    }

    public List<String> docIdsOrEmpty() {
        return docIds == null ? List.of() : docIds;
    }
}

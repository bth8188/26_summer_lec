package com.lecture.rag.lab24;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Lab 2.4 — 근거 판정 관문(grounding gate).
 *
 * similarityThreshold는 "완전히 무관한 질문"만 막는다. 정작 자주 터지는 건
 * <b>"주제는 맞는데 그 문서에 답이 없는 질문"</b>이다.
 * (예: 김치 위키에는 역사·종류·영양가만 있는데 "담그는 법"을 물어보는 경우 —
 *  김치 청크는 당연히 높은 점수로 통과하므로 임계값으로는 절대 못 막는다.)
 *
 * 그 지점을 소형 모델의 지시 준수력에 맡기면 100% 지어낸다. 그래서 답변을 생성하기 <b>전에</b>
 * "이 자료로 답할 수 있나?"를 예/아니오로 먼저 묻고, 아니오면 코드가 생성 자체를 차단한다.
 *
 * lab22에서 LLM을 채점기로 재활용했던 것과 같은 패턴이고, 판정 실패 시 보수적으로
 * "답할 수 없음"으로 떨어뜨리는 것도 lab22가 파싱 실패를 0점 처리하던 것과 같은 결이다.
 */
public class GroundingGate {

    private final ChatClient chatClient;

    public GroundingGate(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public boolean canAnswer(String question, String context) {
        String prompt = """
                아래 참고 자료만 보고 질문에 답할 수 있는지 판단하세요.

                질문: %s

                참고 자료:
                ---------------------
                %s
                ---------------------

                참고 자료 안에 질문에 대한 답이 그대로 적혀 있으면 "예",
                적혀 있지 않거나 추측·일반 상식이 필요하면 "아니오"라고
                다른 말 없이 한 단어로만 답하세요.
                """.formatted(question, context);

        String response = chatClient.prompt().user(prompt).call().content();
        if (response == null) {
            return false;
        }
        String trimmed = response.trim();
        // "아니오"가 "예"를 포함하지 않으므로 접두사 검사로 충분하다.
        // 판정이 애매하면(형식을 안 지키면) 답하지 않는 쪽으로 떨어뜨린다 — 지어내는 것보다 낫다.
        return trimmed.startsWith("예") || trimmed.toLowerCase().startsWith("yes");
    }
}

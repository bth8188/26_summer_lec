package com.lecture.rag.day3.pipeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;

import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.SourceRef;

/**
 * 프롬프트 조립 담당. RAG의 품질은 검색만큼이나 "찾아온 걸 어떻게 프롬프트에 넣는지"에 달려 있어서
 * 이 파일만 고쳐도 답변 품질이 눈에 띄게 바뀐다 — 실습 때 제일 먼저 만져볼 만한 곳이다.
 */
public final class RagPrompts {

    private RagPrompts() {
    }

    /**
     * 기본 시스템 프롬프트. 프론트 설정 패널에서 덮어쓸 수 있다(options.systemPrompt).
     *
     * <p>[3]번 규칙(근거 번호 표기)이 프론트의 인용 칩([1] 클릭 → 해당 근거 하이라이트) 기능과 연결된다.
     * 프롬프트에서 이 규칙을 지우면 화면의 인용 칩도 사라진다.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            당신은 업로드된 문서만 근거로 답하는 문서 QA 어시스턴트입니다.

            규칙:
            1. 반드시 한국어로 답변합니다.
            2. 아래 [컨텍스트]에 없는 내용은 절대 추측하지 말고, "업로드된 문서에서는 찾을 수 없습니다"라고 답합니다.
            3. 모든 문장 끝에 근거로 사용한 컨텍스트 번호를 반드시 붙입니다. 빠뜨리면 안 됩니다.
               예시: "제주도의 면적은 1,846km²입니다.[1] 연평균기온은 16도입니다.[2][3]"
            4. 문서에 있는 표현을 최대한 그대로 인용하고, 3~6문장으로 간결하게 정리합니다.
            """;

    /** 검색된 청크들을 번호가 붙은 컨텍스트 블록으로 만든다. */
    public static String formatContext(List<SourceRef> sources) {
        if (sources.isEmpty()) {
            return "(검색된 문서 없음)";
        }
        StringBuilder builder = new StringBuilder();
        for (SourceRef source : sources) {
            builder.append('[').append(source.index()).append("] ")
                    .append(source.fileName() == null ? "문서" : source.fileName());
            if (source.page() != null) {
                builder.append(" p.").append(source.page());
            }
            if (source.score() != null) {
                builder.append(String.format(" (유사도 %.3f)", source.score()));
            }
            builder.append('\n').append(squeeze(source.text())).append("\n\n");
        }
        return builder.toString().strip();
    }

    /**
     * 연속된 공백/줄바꿈을 하나로 줄인다. PDF에서 추출한 텍스트는 시각적 정렬 때문에 공백이 잔뜩
     * 끼어 있는데, 그게 그대로 프롬프트에 들어가면 의미 없는 토큰을 수백 개 낭비한다.
     * (프론트 인스펙터에서 "전문 보기"를 누르면 정리 전 원문을 확인할 수 있다)
     */
    public static String squeeze(String text) {
        return text == null ? "" : text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** 컨텍스트 + 질문을 하나의 user 메시지로. */
    public static String formatUserMessage(String question, String context) {
        return """
                [컨텍스트]
                %s

                [질문]
                %s
                """.formatted(context, question);
    }

    /** 이전 대화를 Message 리스트로 (최근 maxTurns개만). */
    public static List<Message> historyMessages(List<ChatRequest.Turn> history, int maxTurns) {
        if (history.isEmpty() || maxTurns <= 0) {
            return List.of();
        }
        List<ChatRequest.Turn> recent = history.size() > maxTurns
                ? history.subList(history.size() - maxTurns, history.size())
                : history;
        List<Message> messages = new ArrayList<>(recent.size());
        for (ChatRequest.Turn turn : recent) {
            if (turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            messages.add(turn.isUser() ? new UserMessage(turn.text()) : new AssistantMessage(turn.text()));
        }
        return messages;
    }

    /** 이전 대화를 한 덩어리 텍스트로 (쿼리 재작성 프롬프트에 넣을 때 쓴다). */
    public static String historyAsText(List<ChatRequest.Turn> history, int maxTurns) {
        List<Message> messages = historyMessages(history, maxTurns);
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            builder.append(message instanceof UserMessage ? "사용자: " : "어시스턴트: ")
                    .append(message.getText()).append('\n');
        }
        return builder.toString().strip();
    }

    /** 검색 결과(Document)를 프론트로 보낼 SourceRef로 — 번호는 1부터 붙는다. */
    public static List<SourceRef> toSources(List<Document> documents) {
        List<SourceRef> sources = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            sources.add(SourceRef.from(i + 1, documents.get(i)));
        }
        return sources;
    }

    /**
     * RRF 상수. 클수록 순위 차이를 덜 벌린다(1위와 5위의 점수 차가 작아진다).
     * 60은 원 논문(Cormack et al., 2009)이 제안한 값으로, 하이브리드 검색의 사실상 기본값이다.
     */
    private static final int RRF_K = 60;

    /**
     * 여러 검색 결과를 <b>RRF(Reciprocal Rank Fusion)</b>로 합친다. 중복은 제거된다.
     *
     * <p>단순히 이어붙이면(concat) 앞 목록이 topK 자리를 전부 차지해서, 뒤 목록이 찾아온 청크는
     * {@code finalizeSources}의 절단에 전부 잘려나간다 — 키워드 검색과 질문 재작성이
     * "몇 개 추가됨"이라고 표시만 되고 실제로는 답변에 아무 영향을 못 주는 원인이었다.
     *
     * <p>RRF는 <b>점수가 아니라 순위</b>로 합친다. 청크 하나의 점수는
     * {@code Σ 1/(K + 순위)}이고, 목록마다 순위는 1부터 센다.
     * 코사인 유사도(0~1)와 키워드 TF-IDF 점수(범위 없음)처럼 <b>척도가 다른 점수를 정규화 없이
     * 섞을 수 있다</b>는 게 핵심이다 — 순위는 어느 검색 방식에서나 같은 의미이기 때문이다.
     *
     * <p>부수 효과가 하나 더 있다. 여러 목록에 <b>동시에 등장한 청크가 자동으로 위로 올라온다</b>.
     * 벡터도 찾고 키워드도 찾은 청크는 1/(60+1)을 두 번 받아 어느 한쪽 1위보다 높아진다.
     * "두 방식이 모두 지목한 근거"가 가장 믿을 만하다는 직관이 그대로 수식이 된 것이다.
     *
     * @param results 합칠 검색 결과들. 각 목록은 이미 관련도 순으로 정렬되어 있어야 한다
     * @return 융합 점수 내림차순. 동점이면 먼저 들어온 목록의 것이 앞에 온다
     */
    public static List<Document> fuseByRank(List<List<Document>> results) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Document> documents = new LinkedHashMap<>();
        for (List<Document> result : results) {
            for (int rank = 0; rank < result.size(); rank++) {
                Document document = result.get(rank);
                String key = document.getId() != null ? document.getId() : document.getText();
                documents.putIfAbsent(key, document);
                scores.merge(key, 1.0 / (RRF_K + rank + 1), Double::sum);
            }
        }
        // LinkedHashMap이 삽입 순서를 유지하고 stream().sorted()가 안정 정렬이라, 동점은 원래 순서를 따른다.
        return documents.keySet().stream()
                .sorted(Comparator.comparingDouble(scores::get).reversed())
                .map(documents::get)
                .toList();
    }
}

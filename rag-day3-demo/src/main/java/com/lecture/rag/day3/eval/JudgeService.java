package com.lecture.rag.day3.eval;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.pipeline.RagPrompts;

/**
 * 골드 티어 — Day3 오전 M3.1/Lab3.1에서 배운 LLM-as-judge 채점기.
 * 프론트 답변 카드의 "채점" 버튼이 이 서비스를 호출한다({@code POST /api/evaluate}).
 *
 * <p>채점 항목은 RAGAS의 두 축을 축소한 것이다.
 * <ul>
 *   <li><b>충실도(faithfulness)</b> — 답변이 근거 문서 안의 내용만으로 이루어져 있는가 (할루시네이션 여부)</li>
 *   <li><b>관련성(relevancy)</b> — 답변이 질문에 실제로 답하고 있는가 (동문서답 여부)</li>
 * </ul>
 *
 * <p>JSON을 요구하지 않고 "충실도: N" 형태의 줄글을 요구하는 이유는, 3B급 로컬 모델이 JSON 형식을
 * 자주 깨먹기 때문이다. 형식이 단순할수록 파싱 실패가 줄어든다 — 그래도 실패할 때가 있어서
 * {@code raw}에 원문을 같이 담아 화면에 보여준다.
 */
@Service
public class JudgeService {

    private static final Logger log = LoggerFactory.getLogger(JudgeService.class);

    private static final Pattern FAITHFULNESS = Pattern.compile("충실도\\s*[:：]?\\s*([0-5])");
    private static final Pattern RELEVANCY = Pattern.compile("관련성\\s*[:：]?\\s*([0-5])");
    private static final Pattern REASON = Pattern.compile("이유\\s*[:：]\\s*(.+)", Pattern.DOTALL);

    private final ChatModel chatModel;

    public JudgeService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 채점 요청.
     *
     * @param question 원래 질문
     * @param answer   RAG가 생성한 답변
     * @param sources  그 답변의 근거로 쓴 청크들
     */
    public record EvalRequest(String question, String answer, List<SourceRef> sources) {
    }

    /**
     * 채점 결과.
     *
     * @param faithfulness 충실도 0~5 (파싱 실패 시 null)
     * @param relevancy    관련성 0~5 (파싱 실패 시 null)
     * @param reason       채점 이유
     * @param raw          LLM 원문 응답 (파싱이 깨졌을 때 화면에서 확인하기 위함)
     */
    public record EvalResult(Integer faithfulness, Integer relevancy, String reason, String raw) {
    }

    public EvalResult judge(EvalRequest request) {
        String context = RagPrompts.formatContext(request.sources() == null ? List.of() : request.sources());
        String prompt = """
                당신은 RAG 시스템의 답변을 채점하는 평가자입니다.

                [근거 문서]
                %s

                [질문]
                %s

                [채점할 답변]
                %s

                두 항목을 0~5점으로 채점하세요.
                - 충실도: 답변 내용이 근거 문서 안에 실제로 있는가 (지어낸 내용이 있으면 감점)
                - 관련성: 답변이 질문에 실제로 답하고 있는가 (동문서답이면 감점)

                아래 형식으로 정확히 세 줄만 출력하세요. 다른 말은 붙이지 마세요.
                충실도: <숫자>
                관련성: <숫자>
                이유: <한 문장>
                """.formatted(context, request.question(), request.answer());

        String raw = ChatClient.builder(this.chatModel).build()
                .prompt()
                // 채점은 창의성이 필요 없다 — 온도를 낮춰 같은 입력에 같은 점수가 나오도록
                .options(ChatOptions.builder().temperature(0.0))
                .user(prompt)
                .call()
                .content();

        if (raw == null) {
            raw = "";
        }
        Integer faithfulness = extractScore(FAITHFULNESS, raw);
        Integer relevancy = extractScore(RELEVANCY, raw);
        String reason = extractReason(raw);
        if (faithfulness == null && relevancy == null) {
            log.warn("채점 응답 파싱 실패 — 원문: {}", raw);
        }
        return new EvalResult(faithfulness, relevancy, reason, raw.strip());
    }

    private static Integer extractScore(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static String extractReason(String text) {
        Matcher matcher = REASON.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).strip().lines().findFirst().orElse("").strip();
        }
        return null;
    }
}

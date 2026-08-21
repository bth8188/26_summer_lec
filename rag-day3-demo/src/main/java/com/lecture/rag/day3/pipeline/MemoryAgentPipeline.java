package com.lecture.rag.day3.pipeline;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

/**
 * 대화 기록은 후속 질문을 검색어로 바꾸는 데만 사용하고, 답변의 사실 근거는 검색 문서로 제한하는 파이프라인.
 */
@Component
public class MemoryAgentPipeline extends StudentRagPipeline {

    private static final String GROUNDED_SYSTEM_PROMPT = """
            당신은 은행 법령과 공문을 다루는 한국어 문서 QA 에이전트입니다.

            절대 규칙:
            1. 반드시 자연스러운 한국어로만 답합니다.
            2. 사실 근거는 이번 요청의 [컨텍스트]에 있는 검색 결과만 사용합니다.
            3. 이전 대화, 이전 AI 답변, 모델의 사전지식은 사실 근거로 사용하지 않습니다.
            4. 검색 결과만으로 확인할 수 없는 내용은 추측하지 말고
               "업로드된 문서에서는 확인할 수 없습니다."라고 답합니다.
            5. 모든 사실 문장 끝에 실제로 사용한 근거 번호를 [1] 또는 [1][2] 형식으로 표시합니다.
            6. 현행 법률·시행령과 입법예고가 함께 있으면 문서 상태와 시행일을 구분합니다.
            """;

    public MemoryAgentPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        super(knowledgeBase, chatModel);
    }

    @Override
    public String id() {
        return "memory-agent";
    }

    @Override
    public String name() {
        return "메모리 에이전트";
    }

    @Override
    public String description() {
        return "대화는 질문 해석에만 사용하고 검색 문서만 근거로 한국어 답변을 생성합니다.";
    }

    @Override
    protected boolean active(RagOptions options, String feature) {
        if (RagOptions.FEATURE_REWRITE.equals(feature)) {
            return true;
        }
        return super.active(options, feature);
    }

    @Override
    protected String generationSystemPrompt(RagOptions options) {
        if (options.systemPrompt() == null || options.systemPrompt().isBlank()) {
            return GROUNDED_SYSTEM_PROMPT;
        }
        return GROUNDED_SYSTEM_PROMPT + "\n\n[추가 표현 규칙]\n" + options.systemPrompt();
    }

    @Override
    protected List<Message> generationHistory(ChatRequest request, RagOptions options) {
        return List.of();
    }

    @Override
    protected Optional<String> answerWithoutGeneration(String question, List<SourceRef> sources,
            RagOptions options) {
        return sources.isEmpty()
                ? Optional.of("업로드된 문서에서는 확인할 수 없습니다.")
                : Optional.empty();
    }
}

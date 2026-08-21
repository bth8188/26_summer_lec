package com.lecture.rag.day3.pipeline;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.knowledge.KnowledgeBase;

/**
 * 브론즈 — Day1에서 만든 것과 같은 기본 RAG. 검색 → 컨텍스트 주입 → 스트리밍 답변, 그게 전부다.
 *
 * <p>훅을 하나도 오버라이드하지 않았다는 게 포인트다. 이 파이프라인은 <b>기준선(baseline)</b> 역할이다 —
 * 자기가 만든 파이프라인이 정말 나아졌는지 확인하려면 이것과 같은 질문으로 비교해봐야 한다.
 * (프론트 설정 패널의 "비교 실행"을 켜면 내 파이프라인과 이 기본 파이프라인을 동시에 돌려 나란히 보여준다)
 */
@Component
public class BasicRagPipeline extends AbstractRagPipeline {

    public BasicRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
        super(knowledgeBase, chatModel);
    }

    @Override
    public String id() {
        return "basic";
    }

    @Override
    public String name() {
        return "기본 RAG";
    }

    @Override
    public String tier() {
        return "bronze";
    }

    @Override
    public String description() {
        return "벡터 검색 → 컨텍스트 주입 → 스트리밍 생성. Day1 수준의 기준선 파이프라인.";
    }
}

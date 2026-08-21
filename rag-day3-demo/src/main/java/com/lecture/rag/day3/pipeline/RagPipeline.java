package com.lecture.rag.day3.pipeline;

import java.util.List;

import com.lecture.rag.day3.agent.AgentEvent;
import com.lecture.rag.day3.agent.ChatRequest;

import reactor.core.publisher.Flux;

/**
 * "질문 하나를 받아서 이벤트 스트림을 뱉는 것" — 이 프로젝트에서 RAG 파이프라인의 정의는 이게 전부다.
 *
 * <p><b>이걸 구현한 @Component는 자동으로 프론트 파이프라인 드롭다운에 나타난다.</b>
 * ({@link PipelineRegistry}가 스프링 컨텍스트의 모든 RagPipeline 빈을 수집한다)
 *
 * <p>세 가지 방법으로 자기 파이프라인을 만들 수 있다.
 * <ol>
 *   <li><b>가장 쉬운 길</b> — {@link StudentRagPipeline}의 TODO 메서드를 채운다.
 *       검색·프롬프트·스트리밍 배관은 이미 되어 있고 재작성/키워드검색/재정렬/자기검증만 구현하면 된다.</li>
 *   <li><b>중간</b> — {@link AbstractRagPipeline}을 상속한 새 클래스를 만든다.
 *       공통 흐름(검색 → 컨텍스트 조립 → 스트리밍)은 물려받고 훅만 오버라이드한다.</li>
 *   <li><b>자유</b> — 이 인터페이스를 직접 구현한다. Agentic RAG(도구 호출 루프),
 *       멀티스텝 질의 분해, GraphRAG 등 흐름 자체가 다른 걸 만들 때. 이벤트만 규격대로 흘려보내면
 *       프론트는 그대로 그려준다.</li>
 * </ol>
 */
public interface RagPipeline {

    /** URL/요청 바디에 쓰이는 식별자. 영문 소문자 권장. */
    String id();

    /** 프론트 드롭다운에 보일 이름. */
    String name();

    /** bronze | silver | gold | custom — 프론트에서 뱃지 색으로 쓴다. */
    default String tier() {
        return "custom";
    }

    /** 드롭다운 아래에 보일 한 줄 설명. */
    String description();

    /**
     * 이 파이프라인이 실제로 반응하는 기능 토글 목록
     * ({@link com.lecture.rag.day3.agent.RagOptions#FEATURE_REWRITE} 등).
     * 여기 없는 토글은 프론트에서 비활성으로 표시된다.
     */
    default List<String> supportedFeatures() {
        return List.of();
    }

    /** 질문 하나를 처리하며 이벤트를 흘려보낸다. */
    Flux<AgentEvent> run(ChatRequest request);
}

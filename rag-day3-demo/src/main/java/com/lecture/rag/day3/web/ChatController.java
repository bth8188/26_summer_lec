package com.lecture.rag.day3.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lecture.rag.day3.agent.AgentEvent;
import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.pipeline.PipelineRegistry;
import com.lecture.rag.day3.pipeline.RagPipeline;

import reactor.core.publisher.Flux;

/**
 * 채팅 엔드포인트. 요청 하나당 파이프라인 하나를 실행하고 그 이벤트 스트림을 그대로 흘려보낸다.
 *
 * <p>응답 형식은 NDJSON({@code application/x-ndjson}) — JSON 객체 하나가 한 줄이다.
 * Spring MVC는 이 미디어 타입으로 {@code Flux}를 리턴하면 원소 하나가 만들어질 때마다 즉시 flush한다
 * (WebFlux 없이 서블릿 스택에서 동작한다).
 *
 * <p>프론트는 이 엔드포인트를 <b>파이프라인마다 한 번씩</b> 호출한다. 그래서 "비교 실행"은
 * 백엔드에 특별한 코드가 없다 — 같은 질문으로 두 번 부르고 화면에 나란히 그리는 것뿐이다.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final PipelineRegistry registry;

    public ChatController(PipelineRegistry registry) {
        this.registry = registry;
    }

    @PostMapping(value = "/chat", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<AgentEvent> chat(@RequestBody ChatRequest request) {
        if (request.questionOrEmpty().isEmpty()) {
            return Flux.just(AgentEvent.error("질문이 비어 있습니다."));
        }
        RagPipeline pipeline = this.registry.find(request.pipelineId()).orElse(null);
        if (pipeline == null) {
            return Flux.just(AgentEvent.error(
                    "알 수 없는 파이프라인 id: " + request.pipelineId() + " (사용 가능: " + this.registry.ids() + ")"));
        }
        return Flux.concat(
                Flux.just(AgentEvent.of("start")
                        .with("pipelineId", pipeline.id())
                        .with("pipelineName", pipeline.name())
                        .with("tier", pipeline.tier())),
                pipeline.run(request));
    }
}

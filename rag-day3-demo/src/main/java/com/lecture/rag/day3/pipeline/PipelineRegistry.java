package com.lecture.rag.day3.pipeline;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 스프링 컨텍스트에 등록된 모든 {@link RagPipeline} 빈을 모아 id로 찾을 수 있게 해준다.
 * {@code GET /api/pipelines}가 이 목록을 그대로 내려주고, 프론트 드롭다운이 그걸 그린다 —
 * 즉 {@code @Component}만 붙이면 UI에 자동 등록된다.
 */
@Component
public class PipelineRegistry {

    /** 프론트 드롭다운/뱃지에 필요한 최소 정보. */
    public record PipelineInfo(String id, String name, String tier, String description,
            List<String> supportedFeatures) {
    }

    private final Map<String, RagPipeline> pipelines = new LinkedHashMap<>();

    public PipelineRegistry(List<RagPipeline> pipelines) {
        // 기본 파이프라인(basic)을 항상 앞에 두고, 나머지는 이름순
        pipelines.stream()
                .sorted(Comparator.comparing((RagPipeline pipeline) -> "basic".equals(pipeline.id()) ? 0 : 1)
                        .thenComparing(RagPipeline::name))
                .forEach(pipeline -> this.pipelines.put(pipeline.id(), pipeline));
    }

    public List<PipelineInfo> describeAll() {
        return this.pipelines.values().stream()
                .map(pipeline -> new PipelineInfo(pipeline.id(), pipeline.name(), pipeline.tier(),
                        pipeline.description(), pipeline.supportedFeatures()))
                .toList();
    }

    public Optional<RagPipeline> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.ofNullable(this.pipelines.get("basic"));
        }
        return Optional.ofNullable(this.pipelines.get(id));
    }

    public List<String> ids() {
        return List.copyOf(this.pipelines.keySet());
    }
}

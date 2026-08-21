package com.lecture.rag.day3.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.lecture.rag.day3.agent.AgentEvent;
import com.lecture.rag.day3.knowledge.ChunkingStrategy;
import com.lecture.rag.day3.knowledge.IndexedDocument;
import com.lecture.rag.day3.knowledge.IndexingService;
import com.lecture.rag.day3.knowledge.KnowledgeBase;

import reactor.core.publisher.Flux;

/**
 * 지식 베이스(업로드된 문서) 관리 엔드포인트.
 *
 * <pre>
 * POST   /api/index                  파일 업로드 + 인덱싱 (NDJSON 진행 스트림)
 * GET    /api/knowledge              현재 인덱싱된 문서 목록
 * DELETE /api/knowledge/{docId}      문서 하나 삭제
 * DELETE /api/knowledge              전체 초기화
 * GET    /api/knowledge/strategies   선택 가능한 청킹 전략 목록
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".txt", ".md", ".markdown");

    private final KnowledgeBase knowledgeBase;
    private final IndexingService indexingService;

    public KnowledgeController(KnowledgeBase knowledgeBase, IndexingService indexingService) {
        this.knowledgeBase = knowledgeBase;
        this.indexingService = indexingService;
    }

    /** 프론트 사이드바가 그리는 지식 베이스 상태. */
    public record KnowledgeSnapshot(List<IndexedDocument> documents, int totalChunks) {
    }

    public record StrategyInfo(String id, String label, String description) {
    }

    /**
     * 업로드 + 인덱싱. 파일을 여러 개 한 번에 받을 수 있다.
     *
     * <p>MultipartFile은 요청이 끝나면 사라지므로, 비동기 스트리밍으로 넘기기 전에
     * <b>여기서 동기적으로</b> 임시 파일에 복사해둔다 (임시 파일은 IndexingService가 처리 후 삭제).
     */
    @PostMapping(value = "/index", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<AgentEvent> index(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "strategy", required = false) String strategy,
            @RequestParam(value = "chunkSize", required = false) Integer chunkSize,
            @RequestParam(value = "overlap", required = false) Integer overlap) {

        List<IndexingService.Upload> uploads = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (file.isEmpty()) {
                    continue;
                }
                String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
                if (!isAllowed(fileName)) {
                    return Flux.just(AgentEvent.error(
                            fileName + " — 지원하지 않는 형식입니다. " + ALLOWED_EXTENSIONS + " 만 올릴 수 있습니다."));
                }
                Path temp = Files.createTempFile("day3-upload-", suffixOf(fileName));
                file.transferTo(temp);
                uploads.add(new IndexingService.Upload(fileName, temp));
            }
        }
        catch (IOException | IllegalStateException exception) {
            return Flux.just(AgentEvent.error("업로드 파일 저장 실패: " + exception.getMessage()));
        }

        if (uploads.isEmpty()) {
            return Flux.just(AgentEvent.error("업로드된 파일이 없습니다."));
        }
        return this.indexingService.index(uploads,
                IndexingService.IndexOptions.of(strategy, chunkSize, overlap));
    }

    @GetMapping("/knowledge")
    public KnowledgeSnapshot knowledge() {
        return new KnowledgeSnapshot(this.knowledgeBase.documents(), this.knowledgeBase.totalChunks());
    }

    @DeleteMapping("/knowledge/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        boolean removed = this.knowledgeBase.remove(docId);
        return Map.of("removed", removed, "totalChunks", this.knowledgeBase.totalChunks());
    }

    @DeleteMapping("/knowledge")
    public Map<String, Object> clear() {
        this.knowledgeBase.clear();
        return Map.of("removed", true, "totalChunks", 0);
    }

    @GetMapping("/knowledge/strategies")
    public List<StrategyInfo> strategies() {
        return java.util.Arrays.stream(ChunkingStrategy.values())
                .map(strategy -> new StrategyInfo(strategy.name(), strategy.label(), strategy.description()))
                .toList();
    }

    private static boolean isAllowed(String fileName) {
        String lower = fileName.toLowerCase();
        return ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String suffixOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? ".tmp" : fileName.substring(dot);
    }
}

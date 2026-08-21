package com.lecture.rag.day3.knowledge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.PathResource;
import org.springframework.stereotype.Service;

import com.lecture.rag.day3.agent.AgentEvent;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 업로드된 파일을 읽고 → 자르고 → 임베딩해서 {@link KnowledgeBase}에 넣는다.
 * 진행 상황을 {@link AgentEvent} 스트림으로 흘려보내기 때문에 프론트에서 단계별 진행률이 그대로 보인다.
 *
 * <p>임베딩을 한 번에 다 넣지 않고 {@link #EMBED_BATCH}개씩 나눠 넣는 이유는 진행률 표시 때문이다.
 * 청크 200개짜리 PDF를 한 번에 넣으면 30초 넘게 화면이 멈춘 것처럼 보인다.
 */
@Service
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    /** 임베딩 배치 크기 — 진행률 갱신 단위이기도 하다. */
    private static final int EMBED_BATCH = 8;

    private final KnowledgeBase knowledgeBase;

    public IndexingService(KnowledgeBase knowledgeBase) {
        this.knowledgeBase = knowledgeBase;
    }

    /**
     * 업로드 파일 하나.
     *
     * @param fileName 원본 파일명
     * @param tempPath 컨트롤러가 저장해둔 임시 파일 경로 (이 서비스가 처리 후 삭제한다)
     */
    public record Upload(String fileName, Path tempPath) {
    }

    /**
     * 인덱싱 옵션.
     *
     * @param strategy   청킹 전략
     * @param chunkSize  청크 크기 (TOKEN=토큰 수, 그 외=글자 수)
     * @param overlap    청크 겹침 (SLIDING에서만 사용)
     */
    public record IndexOptions(ChunkingStrategy strategy, int chunkSize, int overlap) {

        public static IndexOptions of(String strategy, Integer chunkSize, Integer overlap) {
            return new IndexOptions(
                    ChunkingStrategy.parse(strategy),
                    chunkSize == null ? 400 : Math.clamp(chunkSize, 80, 4000),
                    overlap == null ? 80 : Math.clamp(overlap, 0, 1000));
        }
    }

    public Flux<AgentEvent> index(List<Upload> uploads, IndexOptions options) {
        return Flux.<AgentEvent>create(sink -> {
            long startedAt = System.currentTimeMillis();
            int totalChunks = 0;
            try {
                for (int i = 0; i < uploads.size(); i++) {
                    totalChunks += indexOne(sink, uploads.get(i), options, i + 1, uploads.size());
                }
                sink.next(AgentEvent.metric("indexedChunks", "이번에 추가된 청크", totalChunks));
                sink.next(AgentEvent.metric("totalChunks", "지식 베이스 총 청크",
                        this.knowledgeBase.totalChunks()));
                sink.next(AgentEvent.done(System.currentTimeMillis() - startedAt));
            }
            catch (Exception exception) {
                log.error("인덱싱 실패", exception);
                sink.next(AgentEvent.error(describe(exception)));
            }
            finally {
                uploads.forEach(upload -> deleteQuietly(upload.tempPath()));
                sink.complete();
            }
        })
                // 파일 파싱·임베딩은 전부 블로킹 작업이라 요청 스레드에서 돌리지 않고 별도 스레드로 넘긴다
                .subscribeOn(Schedulers.boundedElastic());
    }

    private int indexOne(reactor.core.publisher.FluxSink<AgentEvent> sink, Upload upload,
            IndexOptions options, int fileNo, int fileCount) throws Exception {

        String docId = UUID.randomUUID().toString().substring(0, 8);
        String fileName = upload.fileName() == null ? "unknown" : upload.fileName();
        String prefix = fileCount > 1 ? "(" + fileNo + "/" + fileCount + ") " : "";
        boolean pdf = fileName.toLowerCase().endsWith(".pdf");

        // 1) 읽기 -------------------------------------------------------------
        String readStep = "read-" + docId;
        sink.next(AgentEvent.stepStart(readStep, prefix + fileName + " 읽기"));
        long t0 = System.currentTimeMillis();

        List<Document> pages = pdf
                ? new PagePdfDocumentReader(new PathResource(upload.tempPath())).get()
                : new TextReader(new PathResource(upload.tempPath())).get();

        int charCount = pages.stream().mapToInt(page -> page.getText() == null ? 0 : page.getText().length()).sum();
        sink.next(AgentEvent.stepDone(readStep, prefix + fileName + " 읽기",
                System.currentTimeMillis() - t0,
                (pdf ? pages.size() + "페이지 · " : "") + String.format("%,d자", charCount)));

        if (charCount == 0) {
            sink.next(AgentEvent.notice("warn", fileName
                    + " 에서 추출된 텍스트가 0자입니다. 스캔 이미지 PDF는 텍스트 레이어가 없어 OCR이 필요합니다."));
            return 0;
        }

        // 2) 청킹 -------------------------------------------------------------
        String chunkStep = "chunk-" + docId;
        sink.next(AgentEvent.stepStart(chunkStep,
                prefix + "청킹 (" + options.strategy().name() + ", size=" + options.chunkSize() + ")"));
        t0 = System.currentTimeMillis();

        List<Document> chunks = options.strategy().split(pages, options.chunkSize(), options.overlap());
        chunks = withMetadata(chunks, docId, fileName);

        int avgChars = chunks.isEmpty() ? 0
                : chunks.stream().mapToInt(chunk -> chunk.getText().length()).sum() / chunks.size();
        sink.next(AgentEvent.stepDone(chunkStep,
                prefix + "청킹 (" + options.strategy().name() + ", size=" + options.chunkSize() + ")",
                System.currentTimeMillis() - t0,
                "청크 " + chunks.size() + "개 · 평균 " + avgChars + "자"));

        if (!chunks.isEmpty()) {
            // 첫 청크를 미리보기로 보내준다 — 청킹 전략을 바꿨을 때 뭐가 달라졌는지 눈으로 확인하는 용도
            String preview = chunks.get(0).getText();
            sink.next(AgentEvent.of("chunkPreview")
                    .with("fileName", fileName)
                    .with("text", preview.length() > 400 ? preview.substring(0, 400) + "…" : preview));
        }

        // 3) 임베딩 -----------------------------------------------------------
        String embedStep = "embed-" + docId;
        sink.next(AgentEvent.stepStart(embedStep, prefix + "임베딩 & 저장 (bge-m3)"));
        t0 = System.currentTimeMillis();

        for (int from = 0; from < chunks.size(); from += EMBED_BATCH) {
            int to = Math.min(from + EMBED_BATCH, chunks.size());
            this.knowledgeBase.addChunks(docId, chunks.subList(from, to));
            sink.next(AgentEvent.progress(embedStep, prefix + "임베딩 & 저장", to, chunks.size()));
        }
        long embedMs = System.currentTimeMillis() - t0;
        sink.next(AgentEvent.stepDone(embedStep, prefix + "임베딩 & 저장 (bge-m3)", embedMs,
                chunks.size() + "개 벡터 저장 완료"));

        IndexedDocument indexed = new IndexedDocument(docId, fileName, pdf ? "pdf" : "text",
                pages.size(), chunks.size(), charCount, options.strategy().name(),
                options.chunkSize(), options.overlap(), System.currentTimeMillis(), embedMs);
        this.knowledgeBase.register(indexed);
        sink.next(AgentEvent.document(indexed));

        return chunks.size();
    }

    /** 청크마다 docId/fileName/page/chunkIndex를 심어준다 — 근거 표시와 문서별 삭제에 필요하다. */
    private List<Document> withMetadata(List<Document> chunks, String docId, String fileName) {
        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Document.Builder builder = Document.builder()
                    .text(chunk.getText())
                    .metadata(new java.util.LinkedHashMap<>(chunk.getMetadata()))
                    .metadata("docId", docId)
                    .metadata("fileName", fileName)
                    .metadata("chunkIndex", i);
            // PagePdfDocumentReader가 넣어준 페이지 번호를 우리 이름(page)으로 정규화
            Object pageNumber = chunk.getMetadata().get(PagePdfDocumentReader.METADATA_START_PAGE_NUMBER);
            if (pageNumber != null) {
                builder.metadata("page", pageNumber);
            }
            result.add(builder.build());
        }
        return result;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        }
        catch (Exception ignored) {
            // 임시 파일 삭제 실패는 무시 — OS가 알아서 정리한다
        }
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}

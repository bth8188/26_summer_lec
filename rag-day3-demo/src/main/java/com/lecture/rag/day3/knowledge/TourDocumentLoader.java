package com.lecture.rag.day3.knowledge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 서버 기동 시 {@code docs/} 폴더의 PDF를 자동으로 찾아 {@link IndexingService}로 인덱싱한다.
 *
 * <p>이 프로젝트의 지식 베이스는 메모리에만 저장돼서 재시작할 때마다 비워진다
 * ({@link KnowledgeBase} 클래스 주석 참고). 캡스톤 데이터(서울 관광안내서 + 유니버설 관광 가이드북)처럼
 * "항상 켜져 있어야 하는" 문서는 매번 브라우저에서 손으로 올리는 대신, 여기서 자동으로 실어둔다.
 *
 * <p>PDF는 {@code app.tour-docs.dir}(기본값 {@code docs}, {@code rag-day3-demo/} 기준 상대경로)에
 * 넣어두면 된다. 폴더나 파일이 없으면 경고만 남기고 평소처럼 브라우저 업로드로 계속 쓸 수 있다.
 */
@Component
@Order(0)
public class TourDocumentLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TourDocumentLoader.class);

    private final IndexingService indexingService;
    private final KnowledgeBase knowledgeBase;
    private final Path docsDir;

    public TourDocumentLoader(IndexingService indexingService, KnowledgeBase knowledgeBase,
            @Value("${app.tour-docs.dir:docs}") String docsDir) {
        this.indexingService = indexingService;
        this.knowledgeBase = knowledgeBase;
        this.docsDir = Path.of(docsDir);
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (!Files.isDirectory(this.docsDir)) {
            log.warn("[관광 가이드북 자동 로드] '{}' 폴더가 없습니다. PDF 2개(서울 관광안내서, 유니버설 관광 가이드북)를 "
                    + "이 폴더에 넣고 서버를 재시작하세요. (경로: {})",
                    this.docsDir, this.docsDir.toAbsolutePath());
            return;
        }

        List<Path> pdfs;
        try (Stream<Path> files = Files.list(this.docsDir)) {
            pdfs = files.filter(path -> path.toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        }
        if (pdfs.isEmpty()) {
            log.warn("[관광 가이드북 자동 로드] '{}' 폴더에 PDF가 없습니다.", this.docsDir.toAbsolutePath());
            return;
        }

        List<IndexingService.Upload> uploads = new ArrayList<>(pdfs.size());
        for (Path pdf : pdfs) {
            // IndexingService가 처리 후 원본을 지우므로, 원본은 그대로 두고 임시 복사본만 넘긴다.
            Path temp = Files.createTempFile("tour-doc-", ".pdf");
            Files.copy(pdf, temp, StandardCopyOption.REPLACE_EXISTING);
            uploads.add(new IndexingService.Upload(pdf.getFileName().toString(), temp));
        }

        Map<String, IndexedDocument> indexed = new LinkedHashMap<>();
        this.indexingService.index(uploads, IndexingService.IndexOptions.of(null, null, null))
                .doOnNext(event -> {
                    if ("document".equals(event.type())) {
                        IndexedDocument document = (IndexedDocument) event.data().get("document");
                        indexed.put(document.fileName(), document);
                    }
                    if ("error".equals(event.type())) {
                        log.error("[관광 가이드북 자동 로드] 인덱싱 중 오류: {}", event.data().get("message"));
                    }
                })
                .blockLast();

        log.info("========== 관광 가이드북 RAG 자동 인덱싱 결과 ==========");
        for (Path pdf : pdfs) {
            String fileName = pdf.getFileName().toString();
            IndexedDocument document = indexed.get(fileName);
            if (document == null) {
                log.warn("  - {} : 인덱싱되지 않음 (텍스트 추출 실패 또는 오류 — 위 로그 확인)", fileName);
            }
            else {
                log.info("  - {} : {}페이지, 청크 {}개", fileName, document.pageCount(), document.chunkCount());
            }
        }
        log.info("전체 VectorStore 청크 수: {}개", this.knowledgeBase.totalChunks());
        log.info("=======================================================");
    }
}

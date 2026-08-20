package com.lecture.rag.lab24;

import com.lecture.rag.lab21m1.StructureBasedSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lab 2.4 — 위키 문서 2종을 <b>같은 PGVector 테이블</b>에 넣되, 청크마다 metadata.source를 심어
 * 나중에 filterExpression으로 갈라 쓸 수 있게 만드는 인덱서.
 *
 * <h3>청킹을 TokenTextSplitter에서 섹션 단위로 바꾼 이유</h3>
 * 처음엔 TokenTextSplitter(300토큰)를 썼는데 제주 문서(1.2KB)가 3청크로 갈리면서
 * "역사" 섹션이 두 동강 났다 — 본문은 "기후" 청크 뒤에 묻히고 마지막 문장만 다음 청크로 넘어갔다.
 * 그 청크의 임베딩은 "기후"가 지배하니 "제주도 역사"로 검색해도 안 잡혔다.
 * 반대로 chunkSize를 키워 문서를 통째로 한 청크에 담으면 검색은 되지만, 컨텍스트에 지리·기후·역사·관광이
 * 다 섞여 들어가서 소형 모델이 그 안에서 "역사"를 골라내지 못했다.
 *
 * 답은 <b>의미 단위(섹션)로 자르는 것</b>이다 — M2.1의 StructureBasedSplitter가 정확히 이걸 위한 도구라
 * lab21m1의 것을 그대로 재사용한다.
 *
 * <h3>문맥 헤더(contextual chunk header)</h3>
 * 섹션만 잘라 임베딩하면 "역사" 청크에는 정작 "제주도"라는 단어가 없어서 "제주도 역사" 질의와 잘 안 붙는다.
 * 그래서 모든 청크 앞에 문서 제목을 한 줄 붙인다. 실무 RAG에서 흔히 쓰는 기법.
 */
public class WikiIndexer {

    /**
     * 위키 섹션 헤더를 잡는 패턴: "20자 이내의 단독 줄이면서, 마침표/쉼표로 끝나지 않는 줄".
     * 예) "지리", "역사", "관광", "주요 종류(주재료별)", "영양가(100g당)", "교역 통계(2004년 기준)"
     * 본문 문장은 길거나 마침표로 끝나므로 걸리지 않는다.
     */
    private static final Pattern SECTION_HEADER = Pattern.compile(
            "(?m)^(?=[^\\n]{1,20}$)(?![^\\n]*[.,]\\s*$)[^\\n]+$");

    /** 섹션 하나가 이보다 길면 안전장치로 토큰 기준 재분할한다 (지금 문서들은 걸릴 일이 없음). */
    private static final int MAX_SECTION_CHARS = 1200;

    private final VectorStore vectorStore;

    public WikiIndexer(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ensureIndexed(String file, String source) {
        long existing = countBySource(source);
        if (existing > 0) {
            System.out.printf("[인덱싱] source='%s' — 이미 %d개 저장돼 있어 건너뜁니다.%n", source, existing);
            return;
        }
        System.out.printf("[인덱싱] source='%s' — %s 인덱싱을 시작합니다...%n", source, file);
        index(file, source);
    }

    /**
     * 해당 source의 기존 청크를 지우고 다시 인덱싱한다.
     * 청킹 방식을 바꿔가며 검색 품질을 비교하려면 재인덱싱이 필요해서 넣었다. CLI의 /reindex가 호출한다.
     */
    public void reindex(String file, String source) {
        System.out.printf("[인덱싱] source='%s' — 기존 청크를 삭제합니다.%n", source);
        vectorStore.delete(RetrievalPolicy.filterOn(source));
        index(file, source);
    }

    private void index(String file, String source) {
        String full = normalize(readAll(file));
        String title = firstNonBlankLine(full, source);

        List<Document> sections = new StructureBasedSplitter(SECTION_HEADER).split(new Document(full));

        List<Document> tagged = new ArrayList<>();
        for (Document section : sections) {
            String text = section.getText().trim();
            // 문서 제목만 들어 있는 첫 청크는 버린다 (아래에서 모든 청크에 제목을 다시 붙이므로 중복)
            if (text.isEmpty() || text.equals(title)) {
                continue;
            }
            for (String piece : capSize(text)) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source", source);
                metadata.put("title", title);
                tagged.add(new Document(title + "\n" + piece, metadata));
            }
        }

        vectorStore.add(tagged);
        System.out.printf("[인덱싱] source='%s' — 섹션 청크 %d개 저장 완료%n", source, tagged.size());
        for (Document doc : tagged) {
            System.out.println("   · " + firstNonBlankLine(doc.getText().substring(title.length()).trim(), "?")
                    + "  (" + doc.getText().length() + "자)");
        }
    }

    /** .txt/.md는 TextReader, 그 외(.pdf)는 PagePdfDocumentReader로 읽는다. */
    private String readAll(String file) {
        String path = "classpath:/scenarios/" + file;
        List<Document> docs;
        if (file.endsWith(".txt") || file.endsWith(".md")) {
            TextReader reader = new TextReader(path);
            reader.setCharset(StandardCharsets.UTF_8);
            docs = reader.get();
        } else {
            docs = new PagePdfDocumentReader(path).get();
        }
        return docs.stream()
                .map(Document::getText)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n\n" + b);
    }

    /**
     * CRLF를 LF로 통일하고(줄 단위 정규식이 \r에 걸리지 않게), 연속 공백을 한 칸으로 접는다.
     * 후자는 M2.1에서 확인한 PDF 추출 문제 대응 — .txt를 읽을 땐 무해하다.
     */
    private String normalize(String text) {
        return text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ");
    }

    private String firstNonBlankLine(String text, String fallback) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse(fallback);
    }

    private List<String> capSize(String text) {
        if (text.length() <= MAX_SECTION_CHARS) {
            return List.of(text);
        }
        return TokenTextSplitter.builder().withChunkSize(400).build()
                .apply(List.of(new Document(text)))
                .stream()
                .map(Document::getText)
                .toList();
    }

    /**
     * PgVectorStore엔 count() API가 없어서, 해당 source로 필터링한 뒤 임계값 없이 top-1000을 긁어
     * 개수를 어림잡는다 (lab21의 countExisting()과 같은 꼼수).
     */
    private long countBySource(String source) {
        List<Document> all = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(source)
                        .topK(1000)
                        .filterExpression(RetrievalPolicy.filterOn(source))
                        .similarityThresholdAll()
                        .build());
        return all.size();
    }
}

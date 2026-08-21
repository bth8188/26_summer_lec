package com.lecture.rag.lab25;

import com.lecture.rag.lab21m1.RecursiveCharacterSplitter;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lab 2.5 — 관계 테이블 + 벡터 스토어를 함께 쓰는 "사서" 챗봇.
 *
 * lab24는 저장소가 벡터 하나뿐이라 "문서 안의 내용"만 답할 수 있었다. 여기서는 질문을 두 종류로 나눈다.
 *   - 문서에 대한 질문("무슨 자료 있어?", "위키 몇 개야?")  → CatalogSearchTool (SQL)
 *   - 문서 안의 질문("제주도 면적이 얼마야?")               → ContentSearchTool (벡터)
 * 개수 세기나 목록 나열은 임베딩이 할 수 있는 일이 아니라서, 관계 테이블이 따로 필요하다.
 *
 * 두 저장소는 document.id ↔ 벡터 metadata의 document_id로 이어진다.
 *
 * 콘솔 버전은 LibrarianConsoleDemo, Swagger 버전은 LibrarianApiController가 이 서비스를 공유한다.
 *
 * 실행: 1) docker compose up -d
 *       2) ./run.sh lab25  또는  ./run.sh lab25-api   (최초 실행은 8개 문서 인덱싱에 몇 분 걸림)
 */
@Service
@Profile({"lab25", "lab25-api"})
public class LibrarianService {

    static final double SIMILARITY_THRESHOLD = 0.55;
    private static final int MAX_CHUNK_CHARS = 400;

    public record Answer(String question, String answer, int catalogCalls, int contentCalls) {}

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final DocumentCatalog catalog;

    private ChatClient chatClient;
    private CatalogSearchTool catalogTool;
    private ContentSearchTool contentTool;

    public LibrarianService(ChatModel chatModel, VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.catalog = new DocumentCatalog(jdbcTemplate);
    }

    @PostConstruct
    void init() {
        catalog.createTableIfMissing();
        indexIfEmpty();

        this.catalogTool = new CatalogSearchTool(catalog);
        this.contentTool = new ContentSearchTool(vectorStore, catalog, SIMILARITY_THRESHOLD);

        // 역할만 주면 소형 모델은 도구를 못 고른다. 어떤 질문에 어떤 도구인지 예시로 못 박는다.
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        당신은 자료실의 사서입니다. 이용자가 찾는 정보를 도구로 조회해서 알려주는 것이 역할입니다.
                        기억에 의존해 답하지 말고 반드시 도구를 먼저 사용하세요.

                        도구 선택 기준:
                        - 어떤 자료가 있는지 묻는 질문(목록, 개수, 종류)  → listDocuments 또는 countByCategory
                        - 특정 문서를 찾아 번호를 알아내야 할 때          → findDocument
                        - 자료 안에 적힌 내용을 묻는 질문                → searchContent

                        특정 문서 안에서만 찾아달라는 요청이면 findDocument로 문서 번호를 먼저 얻고,
                        그 번호를 searchContent의 documentId로 넘기세요.
                        도구가 빈 결과를 주면 지어내지 말고 자료실에 없다고 답하세요. 항상 한국어로 답하세요.
                        """)
                .build();
    }

    /** 도구 호출 횟수를 전후 비교로 판정하므로 synchronized (lab24 ChatbotService와 같은 이유). */
    public synchronized Answer ask(String question) {
        int catalogBefore = catalogTool.getCallCount();
        int contentBefore = contentTool.getCallCount();

        String answer = chatClient.prompt()
                .tools(catalogTool, contentTool)
                .user(question)
                .call()
                .content();

        return new Answer(question, answer,
                catalogTool.getCallCount() - catalogBefore,
                contentTool.getCallCount() - contentBefore);
    }

    // ---- 인덱싱: 카탈로그 행과 벡터 청크를 같이 만든다 ----

    private void indexIfEmpty() {
        if (!catalog.isEmpty()) {
            System.out.println("=== 카탈로그가 이미 채워져 있어 인덱싱을 건너뜁니다 ===");
            return;
        }

        System.out.println("=== 최초 실행 — 시나리오 문서 " + DocumentCatalog.ENTRIES.size() + "건을 인덱싱합니다 ===");
        System.out.println("(PDF 포함이라 몇 분 걸립니다)");

        for (DocumentCatalog.Entry entry : DocumentCatalog.ENTRIES) {
            String text = read(entry.fileName());
            List<Document> chunks = new RecursiveCharacterSplitter(MAX_CHUNK_CHARS)
                    .split(new Document(text));

            // 카탈로그 행을 먼저 만들어 id를 받고, 그 id를 청크 metadata에 심는다 — 이게 조인 키
            int documentId = catalog.insert(entry, text.length(), chunks.size());

            List<Document> tagged = new ArrayList<>();
            for (Document chunk : chunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put(ContentSearchTool.DOCUMENT_ID, documentId);
                metadata.put("category", entry.category());
                tagged.add(new Document(chunk.getText(), metadata));
            }
            vectorStore.add(tagged);

            System.out.println("  [" + documentId + "] " + entry.title()
                    + " → " + chunks.size() + "청크 (" + text.length() + "자)");
        }
        System.out.println("=== 인덱싱 완료 ===");
    }

    private String read(String fileName) {
        if (fileName.endsWith(".pdf")) {
            List<Document> pages = new PagePdfDocumentReader("classpath:/scenarios/" + fileName).get();
            String combined = pages.stream().map(Document::getText).reduce("", (a, b) -> a + "\n\n" + b);
            // PDF 추출 텍스트는 단어 사이 공백이 비정상적으로 벌어져서 글자 수가 부풀려진다
            // (lab21m1 ChunkingStrategyDemo.loadFullText()와 같은 처리)
            return combined.replaceAll("[ \\t]+", " ");
        }
        // txt/md는 들여쓰기가 구조라서 공백을 건드리지 않는다
        return new TextReader("classpath:/scenarios/" + fileName).get().get(0).getText();
    }

    // ---- 조회 (콘솔에서는 안 쓰고 API에서만 쓴다) ----

    /** 카탈로그 목록. category가 null이면 전체. */
    public List<Map<String, Object>> listDocuments(String category) {
        return catalog.list(category);
    }

    /**
     * 카탈로그가 기록해둔 청크 수와, 벡터 테이블에서 document_id로 실제로 세어본 청크 수를 나란히 준다.
     * 두 저장소가 정말 같은 키로 이어져 있는지 눈으로 확인하는 용도.
     */
    public Map<String, Object> chunkReport(int documentId) {
        Map<String, Object> row = catalog.findById(documentId);
        if (row == null) {
            return Map.of("error", documentId + "번 문서가 카탈로그에 없습니다");
        }
        int actual = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("문서")
                        .topK(10000)
                        .similarityThresholdAll()
                        .filterExpression(ContentSearchTool.DOCUMENT_ID + " == " + documentId)
                        .build()).size();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("documentId", documentId);
        report.put("title", row.get("title"));
        report.put("catalogChunkCount", row.get("chunk_count"));
        report.put("vectorChunkCount", actual);
        report.put("joinConsistent", Integer.valueOf(actual).equals(row.get("chunk_count")));
        return report;
    }
}

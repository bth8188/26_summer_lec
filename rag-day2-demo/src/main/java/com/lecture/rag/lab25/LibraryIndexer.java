package com.lecture.rag.lab25;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 자료실 데이터를 DB에 넣는 클래스
@Component
@Profile("librarian")
public class LibraryIndexer implements CommandLineRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final VectorStore vectorStore;

    public LibraryIndexer(DataSource dataSource, JdbcTemplate jdbc, VectorStore vectorStore) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        loadCatalog();
        indexPdf();
        System.out.println("자료실 준비 완료");
    }

    // catalog.sql로 테이블 t생성
    private void loadCatalog() {

        ResourceDatabasePopulator sql =
                new ResourceDatabasePopulator(new ClassPathResource("catalog.sql"));
        sql.setSqlScriptEncoding("UTF-8");
        sql.execute(dataSource);
    }

    private void indexPdf() {

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select doc_id, title, source_file " +
                        "from lib_document order by doc_id");

        DefaultResourceLoader loader = new DefaultResourceLoader();
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(400).build();

        for (Map<String, Object> row : rows) {

            String docId = (String) row.get("doc_id");
            String fileName = (String) row.get("source_file");

            Integer count = jdbc.queryForObject(
                    "select count(*) from vector_store " +
                            "where metadata->>'doc_id' = ?",
                    Integer.class, docId);

            if (count > 0) {
                continue;
            }

            Resource pdf = loader.getResource("classpath:/scenarios/" + fileName);

            if (!pdf.exists()) {
                System.out.println(fileName + " 파일 X");
                continue;
            }

            System.out.println(docId + " " + row.get("title") + " 읽는 중");

            List<Document> pages = new PagePdfDocumentReader(pdf).get();

            List<Document> chunks = new ArrayList<>();

            for (Document page : splitter.apply(pages)) {
                chunks.add(new Document(page.getText(), Map.of("doc_id", docId)));
            }

            vectorStore.add(chunks);
            System.out.println(docId + " 등록 " + chunks.size() + "개");
        }
    }
}

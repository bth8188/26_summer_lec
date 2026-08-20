package com.lecture.rag.lab25;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/librarian")
@Profile("librarian")
public class LeeSangHyeokLibrarian {

    private final ChatClient chatClient;

    public LeeSangHyeokLibrarian(ChatModel chatModel, VectorStore vectorStore, JdbcTemplate jdbc) {

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        너는 자료실 사서다.
                        이용자가 찾는 문서를 찾아주는 것이 너의 역할이다.
                        
                        - 어떤 자료가 있는지 물으면 searchCatalog로 목록을 본다.
                        - 내용을 물으면 searchCatalog로 문서번호를 먼저 찾고,
                          그 번호로 readDocument를 불러서 본문을 읽는다.
                        - 목록에 없는 자료는 없다고 답한다.
                        
                        답할 때는 문서번호와 제목을 같이 알려준다.
                        항상 한국어로 답한다.
                        """)
                .defaultTools(new CatalogTool(jdbc), new StackTool(vectorStore))
                .build();
    }

    @GetMapping("/ask")
    public String ask(@RequestParam String question) {

        return chatClient.prompt()
                .user(question)
                .call()
                .content();
    }

    static class CatalogTool {

        private final JdbcTemplate jdbc;

        CatalogTool(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Tool(name = "searchCatalog", description = "자료실 문서 목록을 조회한다. 문서번호를 알려준다.")
        public String searchCatalog(@ToolParam(description = "검색할 키워드. 전체 목록은 빈 값") String keyword) {

            String sql = "select d.doc_id, d.title, c.category_name, c.shelf, d.author"
                    + " from lib_document d"
                    + " join lib_category c on c.category_id = d.category_id";

            List<Map<String, Object>> rows;

            if (keyword == null || keyword.isBlank()) {
                rows = jdbc.queryForList(sql + " order by d.doc_id");
            } else {
                String like = "%" + keyword.trim() + "%";
                rows = jdbc.queryForList(sql
                        + " where d.title ilike ? or c.category_name ilike ? or d.author ilike ?"
                        + " order by d.doc_id", like, like, like);
            }

            if (rows.isEmpty()) {
                return "찾은 자료가 없습니다.";
            }

            StringBuilder sb = new StringBuilder();

            for (Map<String, Object> row : rows) {
                sb.append(row.get("doc_id")).append(" ");
                sb.append(row.get("title")).append(" / ");
                sb.append(row.get("category_name")).append(" / 서가 ");
                sb.append(row.get("shelf")).append("\n");
            }

            return sb.toString();
        }
    }

    static class StackTool {

        private final VectorStore vectorStore;

        StackTool(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
        }

        @Tool(name = "readDocument", description = "문서번호로 그 문서 본문에서 내용을 찾는다.")
        public String readDocument(
                @ToolParam(description = "문서번호. 예: D006") String docId,
                @ToolParam(description = "찾을 내용") String query) {

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(3)
                            .similarityThreshold(0.5)
                            .filterExpression("doc_id == '" + docId + "'")
                            .build());

            if (docs.isEmpty()) {
                return "본문에서 찾지 못했습니다.";
            }

            StringBuilder sb = new StringBuilder();

            for (Document doc : docs) {
                sb.append(doc.getText()).append("\n\n");
            }

            return sb.toString();
        }
    }
}

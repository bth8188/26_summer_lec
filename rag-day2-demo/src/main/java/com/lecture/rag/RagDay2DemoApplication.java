package com.lecture.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Scanner;

@SpringBootApplication
public class RagDay2DemoApplication {

	public static void main(String[] args) {

//		cli 인풋을 받고
//		pgvector에서 유사도를 검색해서
//		결과가 없으면 모른다고 대답할 수 있는 챗봇 만들기
//		rag는 qaAdvisor 하나와 tool 하나를 넣기
//		jeju-wiki는 도구로 제공
//		kimchi-wiki는 advisor로 제공

		// 1) 스프링 앱을 켜고, 컨텍스트(빈들이 들어있는 컨테이너)를 돌려받음
		ConfigurableApplicationContext ctx = SpringApplication.run(RagDay2DemoApplication.class, args);

		// 2) 필요한 빈들을 컨텍스트에서 직접 꺼내옴
		ChatModel chatModel = ctx.getBean(ChatModel.class);
		EmbeddingModel embeddingModel = ctx.getBean(EmbeddingModel.class);
		JdbcTemplate jdbcTemplate = ctx.getBean(JdbcTemplate.class);

		// 3) jeju/kimchi용 벡터스토어 2개를 직접 조립
		//    주의: main()에서 new/build()로 직접 만들면 스프링이 생명주기를 관리해주지 않으므로
		//    initializeSchema(true)를 설정해도 실제 테이블 생성이 자동으로 일어나지 않는다.
		//    그래서 afterPropertiesSet()을 수동으로 호출해서 스키마 초기화를 직접 트리거해야 한다.
		PgVectorStore jejuStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
				.vectorTableName("jeju_vector_store")
				.initializeSchema(true)
				.dimensions(1024)
				.build();
		initSchema(jejuStore, "jeju_vector_store");
		VectorStore jejuVectorStore = jejuStore;

		PgVectorStore kimchiStore = PgVectorStore.builder(jdbcTemplate, embeddingModel)
				.vectorTableName("kimchi_vector_store")
				.initializeSchema(true)
				.dimensions(1024)
				.build();
		initSchema(kimchiStore, "kimchi_vector_store");
		VectorStore kimchiVectorStore = kimchiStore;

		// 4) 인덱싱 (이미 있으면 건너뜀)
		ensureIndexed(jejuVectorStore, "classpath:/scenarios/6-wiki-jeju.pdf", "제주도");
		ensureIndexed(kimchiVectorStore, "classpath:/scenarios/7-wiki-kimchi.pdf", "김치");
		System.out.println();

		// 5) kimchi는 Advisor로 항상 자동 검색
		QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(kimchiVectorStore).build();

		// 6) jeju는 Tool로 — 모델이 필요하다고 판단할 때만 호출
		JejuTool jejuTool = new JejuTool(jejuVectorStore);

		// 7) ChatClient — 모르면 모른다고 답하도록 시스템 프롬프트에 명시
		ChatClient chatClient = ChatClient.builder(chatModel)
				.defaultSystem("""
                        항상 한국어로 답변하세요.
                        주어진 컨텍스트나 도구 검색 결과에서 답을 찾을 수 없으면,
                        지어내지 말고 "제공된 자료에서 찾을 수 없습니다"라고 솔직하게 답하세요.
                        """)
				.build();

		// 8) CLI 입력 루프
		System.out.println("=== RAG 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
		System.out.println("(김치 질문 -> Advisor가 항상 자동 검색 / 제주도 질문 -> 모델이 필요시에만 Tool 호출)");
		Scanner scanner = new Scanner(System.in);
		while (true) {
			System.out.print("질문> ");
			String question = scanner.nextLine();
			if (question == null || question.isBlank()) break;

			String answer = chatClient.prompt()
					.advisors(qaAdvisor, SimpleLoggerAdvisor.builder().build())
					.tools(jejuTool)
					.user(question)
					.call()
					.content();

			System.out.println("답변> " + answer);
			System.out.println();
		}

		ctx.close();
	}

	// main()에서 직접 만든 PgVectorStore는 스프링이 생명주기를 관리하지 않으므로
	// afterPropertiesSet()을 수동으로 호출해서 테이블 스키마 생성을 직접 트리거한다.
	private static void initSchema(PgVectorStore store, String tableName) {
		try {
			store.afterPropertiesSet();
			System.out.println("[" + tableName + "] 스키마 초기화 완료");
		} catch (Exception e) {
			throw new RuntimeException(tableName + " 스키마 초기화 실패", e);
		}
	}

	private static void ensureIndexed(VectorStore store, String pdfPath, String label) {
		long existingCount = store.similaritySearch(
				SearchRequest.builder().query(label).topK(1000).similarityThresholdAll().build()
		).size();

		if (existingCount > 0) {
			System.out.println("[" + label + "] 이미 인덱싱됨 (재인덱싱 생략)");
			return;
		}

		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfPath);
		List<Document> documents = pdfReader.get();

		TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(300).build();
		List<Document> chunks = splitter.apply(documents);

		store.add(chunks);
		System.out.println("[" + label + "] 인덱싱 완료 - 청크 수: " + chunks.size());
	}

	// jeju 검색용 Tool - static 내부 클래스로 이 파일 안에 정의
	static class JejuTool {
		private final VectorStore jejuVectorStore;
		private int callCount = 0;

		JejuTool(VectorStore jejuVectorStore) {
			this.jejuVectorStore = jejuVectorStore;
		}

		public int getCallCount() {
			return callCount;
		}

		@Tool(description = "제주도 관련 위키 문서에서 질문과 관련된 내용을 검색한다. "
				+ "제주도의 역사, 지리, 문화, 관광 등에 대한 질문일 때만 사용할 것.")
		public String searchJeju(@ToolParam(description = "제주도 위키에서 검색할 질문 또는 키워드") String query) {
			callCount++;
			System.out.println("  >>> [도구 호출됨] searchJeju(\"" + query + "\")");

			var results = jejuVectorStore.similaritySearch(
					SearchRequest.builder().query(query).topK(3).build());

			if (results.isEmpty()) {
				return "제주도 위키에서 관련 내용을 찾지 못했습니다.";
			}

			StringBuilder sb = new StringBuilder();
			for (var doc : results) {
				sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
			}
			return sb.toString();
		}
	}
}
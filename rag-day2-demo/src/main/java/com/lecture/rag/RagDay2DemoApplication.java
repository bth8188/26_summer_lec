package com.lecture.rag;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Scanner;

@SpringBootApplication
public class RagDay2DemoApplication {

	// main()에서 만든 것들을 RagApiController(Swagger용)와 공유하기 위한 static 필드
	private static QuestionAnswerAdvisor sharedQaAdvisor;
	private static JejuTool sharedJejuTool;
	private static DocumentInfoTool sharedDocumentInfoTool;
	private static ChatClient sharedChatClient;

	public static void main(String[] args) {

//		cli 인풋을 받고
//		pgvector에서 유사도를 검색해서
//		결과가 없으면 모른다고 대답할 수 있는 챗봇 만들기
//		rag는 qaAdvisor 하나와 tool 하나를 넣기
//		jeju-wiki는 도구로 제공
//		kimchi-wiki는 advisor로 제공
//		문서 정보(제목/주제/카테고리)를 조회하는 사서 도구도 추가
//		스웨거로도 확인 가능하게 만들기

		// 1) 스프링 앱을 켜고, 컨텍스트(빈들이 들어있는 컨테이너)를 돌려받음
		ConfigurableApplicationContext ctx = SpringApplication.run(RagDay2DemoApplication.class, args);

		// 2) 필요한 빈들을 컨텍스트에서 직접 꺼내옴
		ChatModel chatModel = ctx.getBean(ChatModel.class);
		EmbeddingModel embeddingModel = ctx.getBean(EmbeddingModel.class);
		JdbcTemplate jdbcTemplate = ctx.getBean(JdbcTemplate.class);

		// 3) jeju/kimchi용 벡터스토어 2개를 직접 조립
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

		// 4) 인덱싱 (이미 있으면 건너뜀) — 메타데이터(title/topic/category/source)도 같이 부여
		ensureIndexed(jejuVectorStore, "classpath:/scenarios/6-wiki-jeju.pdf",
				"제주도 위키", "제주도의 지리, 역사, 관광 정보", "지리/여행");
		ensureIndexed(kimchiVectorStore, "classpath:/scenarios/7-wiki-kimchi.pdf",
				"김치 위키", "김치의 역사, 종류, 영양 정보", "음식/문화");
		System.out.println();

		// 5) kimchi는 Advisor로 항상 자동 검색
		QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(kimchiVectorStore).build();

		// 6) jeju는 Tool로 — 모델이 필요하다고 판단할 때만 호출
		JejuTool jejuTool = new JejuTool(jejuVectorStore);

		// 7) 문서 정보(메타데이터) 조회 전용 "사서" 도구
		DocumentInfoTool documentInfoTool = new DocumentInfoTool(jejuVectorStore, kimchiVectorStore);

		// 8) ChatClient — 모르면 모른다고 답하도록 시스템 프롬프트에 명시
		ChatClient chatClient = ChatClient.builder(chatModel)
				.defaultSystem("""
                        항상 한국어로 답변하세요.
                        주어진 컨텍스트나 도구 검색 결과에서 답을 찾을 수 없으면,
                        지어내지 말고 "제공된 자료에서 찾을 수 없습니다"라고 솔직하게 답하세요.
                        """)
				.build();

		// 9) Swagger용 컨트롤러가 같은 인스턴스를 쓸 수 있도록 static 필드에 공유
		sharedQaAdvisor = qaAdvisor;
		sharedJejuTool = jejuTool;
		sharedDocumentInfoTool = documentInfoTool;
		sharedChatClient = chatClient;

		// 10) CLI 입력 루프
		System.out.println("=== RAG 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
		System.out.println("(김치 질문 -> Advisor가 항상 자동 검색 / 제주도 질문 -> 모델이 필요시에만 Tool 호출)");
		System.out.println("(문서 정보 질문 -> 예: '제주도 문서 제목이 뭐야?')");
		System.out.println("(Swagger UI로도 테스트 가능: http://localhost:8080/swagger-ui.html)");
		Scanner scanner = new Scanner(System.in);
		while (true) {
			System.out.print("질문> ");
			String question = scanner.nextLine();
			if (question == null || question.isBlank()) break;

			String answer = chatClient.prompt()
					.advisors(qaAdvisor, SimpleLoggerAdvisor.builder().build())
					.tools(jejuTool, documentInfoTool)
					.user(question)
					.call()
					.content();

			System.out.println("답변> " + answer);
			System.out.println();
		}

		// CLI 루프를 빠져나와도 Swagger API는 계속 쓸 수 있도록 ctx.close()는 하지 않음
	}

	private static void initSchema(PgVectorStore store, String tableName) {
		try {
			store.afterPropertiesSet();
			System.out.println("[" + tableName + "] 스키마 초기화 완료");
		} catch (Exception e) {
			throw new RuntimeException(tableName + " 스키마 초기화 실패", e);
		}
	}

	/**
	 * 인덱싱 시 각 청크에 커스텀 메타데이터(title, topic, category, source, language)를 부여한다.
	 * PDF 원본에는 이런 정보가 없으므로(파일명, 페이지 번호 정도만 자동 부여됨) 직접 채워넣는다.
	 */
	private static void ensureIndexed(VectorStore store, String pdfPath,
									  String title, String topic, String category) {
		long existingCount = store.similaritySearch(
				SearchRequest.builder().query(title).topK(1000).similarityThresholdAll().build()
		).size();

		if (existingCount > 0) {
			System.out.println("[" + title + "] 이미 인덱싱됨 (재인덱싱 생략)");
			return;
		}

		PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfPath);
		List<Document> documents = pdfReader.get();

		for (Document doc : documents) {
			doc.getMetadata().put("title", title);
			doc.getMetadata().put("topic", topic);
			doc.getMetadata().put("category", category);
			doc.getMetadata().put("source", "위키백과");
			doc.getMetadata().put("language", "한국어");
		}

		TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(300).build();
		List<Document> chunks = splitter.apply(documents);

		store.add(chunks);
		System.out.println("[" + title + "] 인덱싱 완료 - 청크 수: " + chunks.size());
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

		@Tool(description = "제주도 관련 위키 문서의 '내용'을 검색한다. "
				+ "제주도의 역사, 지리, 문화, 관광 등 구체적인 사실 질문일 때 사용할 것.")
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

	/**
	 * "사서" 역할의 Tool — 문서 내용이 아니라 문서 자체의 메타정보(제목/주제/카테고리/출처)를 조회한다.
	 * 내용 검색과 달리 유사도 계산 없이, metadata 필드값만 그대로 꺼내서 답한다.
	 */
	static class DocumentInfoTool {
		private final VectorStore jejuVectorStore;
		private final VectorStore kimchiVectorStore;

		DocumentInfoTool(VectorStore jejuVectorStore, VectorStore kimchiVectorStore) {
			this.jejuVectorStore = jejuVectorStore;
			this.kimchiVectorStore = kimchiVectorStore;
		}

		@Tool(description = "저장된 문서 자체의 정보(제목, 주제, 카테고리, 출처)를 조회한다. "
				+ "문서 '내용'이 아니라 '이 문서가 뭐에 대한 문서냐'를 물을 때 사용할 것. "
				+ "예: 제주도 문서 제목이 뭐야? 김치 문서는 어떤 카테고리야?")
		public String getDocumentInfo(
				@ToolParam(description = "'jeju' 또는 'kimchi' 중 하나 — 어느 문서의 정보를 조회할지") String topic) {

			System.out.println("  >>> [도구 호출됨] getDocumentInfo(\"" + topic + "\")");

			VectorStore target = topic.toLowerCase().contains("jeju") || topic.contains("제주")
					? jejuVectorStore
					: kimchiVectorStore;

			var all = target.similaritySearch(
					SearchRequest.builder().query("").topK(1).similarityThresholdAll().build());

			if (all.isEmpty()) {
				return "문서 정보를 찾을 수 없습니다.";
			}

			var meta = all.get(0).getMetadata();
			return String.format(
					"제목: %s\n주제: %s\n카테고리: %s\n출처: %s\n언어: %s",
					meta.get("title"), meta.get("topic"), meta.get("category"),
					meta.get("source"), meta.get("language")
			);
		}
	}

	/**
	 * Swagger UI에서 확인 가능한 REST API.
	 * main()에서 만든 qaAdvisor/jejuTool/documentInfoTool/chatClient를 static 필드로 공유받아 그대로 사용한다.
	 * 접속: http://localhost:8080/swagger-ui.html
	 */
	@RestController
	@RequestMapping("/api/rag")
	@Tag(name = "RAG 챗봇 API", description = "kimchi는 Advisor로 항상 검색, jeju는 Tool로 필요시에만 검색, 문서 정보는 사서 도구로 조회")
	public static class RagApiController {

		public record ChatResult(String question, String answer) {}

		@Operation(summary = "RAG 챗봇에게 질문하기",
				description = "김치 관련 질문은 Advisor가 항상 자동 검색합니다. "
						+ "제주도 관련 질문은 모델이 필요할 때만 Tool을 호출해서 검색합니다. "
						+ "'문서 제목이 뭐야?' 같은 질문은 메타데이터 조회(사서) 도구가 답합니다.")
		@GetMapping("/chat")
		public ChatResult chat(
				@Parameter(description = "제주도/김치 내용 질문 또는 문서 정보(제목/주제/카테고리) 질문",
						example = "김치는 언제부터 먹었어?")
				@RequestParam(defaultValue = "김치는 언제부터 먹었어?") String question) {

			String answer = sharedChatClient.prompt()
					.advisors(sharedQaAdvisor)
					.tools(sharedJejuTool, sharedDocumentInfoTool)
					.user(question)
					.call()
					.content();

			return new ChatResult(question, answer);
		}
	}
}
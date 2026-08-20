package com.lecture.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Scanner;

@SpringBootApplication
public class RagDay2DemoApplication implements CommandLineRunner {

	private static final String JEJU_SOURCE = "jeju-wiki";
	private static final String KIMCHI_SOURCE = "kimchi-wiki";
	private static final double SIMILARITY_THRESHOLD = 0.7;

	private final ChatModel chatModel;
	private final VectorStore vectorStore;
	private final Environment environment;

	public RagDay2DemoApplication(ChatModel chatModel, VectorStore vectorStore, Environment environment) {
		this.chatModel = chatModel;
		this.vectorStore = vectorStore;
		this.environment = environment;
	}

	public static void main(String[] args) {
		SpringApplication.run(RagDay2DemoApplication.class, args);
	}

	@Override
	public void run(String... args) {
		// 기존 lab21/lab22/lab23 데모 프로필로 실행할 때는 해당 데모만 실행한다.
		String[] activeProfiles = environment.getActiveProfiles();
		if (activeProfiles.length > 0 && List.of(activeProfiles).stream().noneMatch("chatbot"::equals)) {
			return;
		}

		indexIfAbsent("6-wiki-jeju.pdf", JEJU_SOURCE);
		indexIfAbsent("7-wiki-kimchi.pdf", KIMCHI_SOURCE);

		QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
				.searchRequest(SearchRequest.builder()
						.topK(3)
						.similarityThreshold(SIMILARITY_THRESHOLD)
						.filterExpression("source == '" + KIMCHI_SOURCE + "'")
						.build())
				.build();
		JejuWikiSearchTool jejuSearchTool = new JejuWikiSearchTool(vectorStore);

		ChatClient chatClient = ChatClient.builder(chatModel)
				.defaultSystem("""
						항상 한국어로 답변하세요.
						김치 참고 문서는 모든 질문에 자동으로 제공되지만, 질문이 김치와 무관하면 반드시 무시하세요.
						김치에 관한 질문은 제공된 김치 문서 내용만 근거로 답하세요.
						제주도에 관한 질문은 제주 위키 검색 도구를 사용하고, 검색 결과만 근거로 답하세요.
						문서나 도구에서 관련 내용을 찾지 못했다면 추측하지 말고 '모르겠습니다.'라고 답하세요.
						""")
				.build();

		System.out.println("=== 제주·김치 RAG 챗봇 (종료하려면 빈 줄 입력) ===");
		Charset consoleInputCharset = System.console() != null
				? System.console().charset()
				: Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
		try (Scanner scanner = new Scanner(System.in, consoleInputCharset)) {
			while (true) {
				System.out.print("질문> ");
				if (!scanner.hasNextLine()) {
					break;
				}

				String question = scanner.nextLine().trim();
				if (question.isEmpty()) {
					break;
				}

				jejuSearchTool.setCurrentQuestion(question);
				String answer = chatClient.prompt()
						.advisors(kimchiAdvisor)
						.tools(jejuSearchTool)
						.user(question)
						.call()
						.content();
				System.out.println("답변> " + answer);
			}
		}
	}

	private void indexIfAbsent(String fileName, String source) {
		List<Document> existing = vectorStore.similaritySearch(SearchRequest.builder()
				.query(source)
				.topK(1)
				.similarityThresholdAll()
				.filterExpression("source == '" + source + "'")
				.build());
		if (!existing.isEmpty()) {
			return;
		}

		PagePdfDocumentReader reader = new PagePdfDocumentReader("classpath:/scenarios/" + fileName);
		List<Document> pages = reader.get();
		pages.forEach(page -> page.getMetadata().put("source", source));

		TokenTextSplitter splitter = TokenTextSplitter.builder()
				.withChunkSize(300)
				.build();
		List<Document> chunks = splitter.apply(pages);
		vectorStore.add(chunks);
		System.out.printf("%s 인덱싱 완료 (%d개 청크)%n", source, chunks.size());
	}

	public static class JejuWikiSearchTool {

		private final VectorStore vectorStore;
		private String currentQuestion;

		private JejuWikiSearchTool(VectorStore vectorStore) {
			this.vectorStore = vectorStore;
		}

		private void setCurrentQuestion(String currentQuestion) {
			this.currentQuestion = currentQuestion;
		}

		@Tool(
				description = "제주도의 지리, 역사, 문화, 관광 등 제주 위키 문서에서 질문과 관련된 내용을 검색한다. "
						+ "제주도에 관한 질문에만 사용할 것.")
		public String searchJejuWiki(
				@ToolParam(description = "제주 위키에서 검색할 질문 또는 키워드")
				String query) {
			String searchQuery = currentQuestion == null || currentQuestion.isBlank() ? query : currentQuestion;
			System.out.println("  >>> [도구 호출됨] searchJejuWiki(\"" + searchQuery + "\")");
			List<Document> results = vectorStore.similaritySearch(SearchRequest.builder()
					.query(searchQuery)
					.topK(3)
					.similarityThreshold(SIMILARITY_THRESHOLD)
					.filterExpression("source == '" + JEJU_SOURCE + "'")
					.build());

			if (results.isEmpty()) {
				return "제주 위키에서 관련 내용을 찾지 못했습니다. 모르겠습니다.";
			}

			String context = results.stream()
					.map(document -> "- " + document.getText().replaceAll("\\s+", " "))
					.reduce((left, right) -> left + "\n" + right)
					.orElse("제주 위키에서 관련 내용을 찾지 못했습니다. 모르겠습니다.");
			return "다음은 제주 위키 검색 결과입니다. 김치 참고 문서는 무시하고 이 내용만 근거로 답하세요. "
					+ "질문의 답이 명시되어 있지 않으면 '모르겠습니다.'라고 답하세요.\n" + context;
		}
	}

}

package com.lecture.rag;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

import com.lecture.rag.day3.agent.ChatRequest;
import com.lecture.rag.day3.agent.RagOptions;
import com.lecture.rag.day3.agent.SourceRef;
import com.lecture.rag.day3.pipeline.RagPrompts;
import com.lecture.rag.day3.knowledge.KnowledgeBase;
import com.lecture.rag.day3.pipeline.AbstractRagPipeline;

@SpringBootApplication
public class RagDay3DemoApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagDay3DemoApplication.class, args);
	}

	// 🚀 강사님의 지시대로 파이프라인 코드를 메인 클래스 내부에 포함시켰습니다.
	@Component
	public static class StudentRagPipeline extends AbstractRagPipeline {

		public StudentRagPipeline(KnowledgeBase knowledgeBase, ChatModel chatModel) {
			super(knowledgeBase, chatModel);
		}

		@Override
		public String id() {
			return "student";
		}

		@Override
		public String name() {
			return "내 파이프라인";
		}

		@Override
		public String tier() {
			return "gold";
		}

		@Override
		public String description() {
			return "RagDay3DemoApplication.java 내부에 구현된 나만의 파이프라인입니다.";
		}

		@Override
		public List<String> supportedFeatures() {
			return List.of(
					RagOptions.FEATURE_REWRITE,
					RagOptions.FEATURE_KEYWORD,
					RagOptions.FEATURE_RERANK,
					RagOptions.FEATURE_SELF_CHECK);
		}

		// =================================================================== 실버 ① (질문 재작성)
		@Override
		protected Optional<List<String>> rewriteQueries(String question, List<ChatRequest.Turn> history, RagOptions options) {
			String historyText = RagPrompts.historyAsText(history, options.maxHistoryOrDefault());

			String prompt = "다음 대화 기록:\n" + historyText + "\n\n현재 질문: " + question +
					"\n\n위 대화의 마지막 질문을 문서 검색에 쓸 수 있게 완전한 문장으로 바꿔 쓰세요. " +
					"서로 표현이 다른 3개를 줄바꿈으로만 구분해서 출력하고, 번호나 설명은 붙이지 마세요.";

			String response = chatClient().prompt().user(prompt).call().content();

			List<String> queries = new ArrayList<>();
			queries.add(question); // 원문 보장

			if (response != null && !response.isBlank()) {
				Arrays.stream(response.split("\n"))
						.map(String::trim)
						.filter(s -> !s.isEmpty())
						.forEach(queries::add);
			}

			return Optional.of(queries);
		}

		// =================================================================== 실버 ② (키워드 검색)
		@Override
		protected Optional<List<Document>> keywordSearch(String query, List<String> docIds, RagOptions options) {
			List<Document> allChunks = knowledgeBase.chunksOf(docIds);

			List<String> keywords = Arrays.stream(query.split("\\s+"))
					.filter(w -> w.length() >= 2)
					.map(String::toLowerCase)
					.toList();

			if (keywords.isEmpty()) return Optional.empty();

			List<Document> results = allChunks.stream()
					.map(chunk -> {
						String chunkText = chunk.getText().toLowerCase();
						long score = keywords.stream().filter(chunkText::contains).count();
						return new AbstractMap.SimpleEntry<>(chunk, score);
					})
					.filter(entry -> entry.getValue() > 0)
					.sorted((e1, e2) -> Long.compare(e2.getValue(), e1.getValue()))
					.limit(options.topKOrDefault())
					.map(Map.Entry::getKey)
					.toList();

			return results.isEmpty() ? Optional.empty() : Optional.of(results);
		}

		// =================================================================== 실버 ③ (재정렬)
		@Override
		protected Optional<List<Document>> rerank(String query, List<Document> candidates, RagOptions options) {
			if (candidates == null || candidates.isEmpty()) return Optional.empty();

			List<Document> reranked = candidates.stream()
					.map(chunk -> {
						String prompt = String.format("질문: %s\n문서: %s\n이 문서가 질문에 답하는 데 얼마나 관련 있는지 0~10 숫자 하나만 답하세요.", query, chunk.getText());
						String response = chatClient().prompt().user(prompt).call().content();
						int score = 0;

						if (response != null) {
							Matcher matcher = Pattern.compile("\\d+").matcher(response);
							if (matcher.find()) {
								score = Integer.parseInt(matcher.group());
							}
						}
						return new AbstractMap.SimpleEntry<>(chunk, score);
					})
					.sorted((e1, e2) -> Integer.compare(e2.getValue(), e1.getValue()))
					.limit(options.topKOrDefault())
					.map(Map.Entry::getKey)
					.toList();

			return Optional.of(reranked);
		}

		// =================================================================== 골드 (자기 검증)
		@Override
		protected Optional<String> selfCheck(String question, String answer, List<SourceRef> sources, RagOptions options) {
			String context = RagPrompts.formatContext(sources);

			String prompt = String.format(
					"[근거]\n%s\n\n[답변]\n%s\n\n답변의 모든 문장이 근거에 실제로 있는 내용인지 판정하세요. " +
							"형식: '통과' 또는 '주의: <근거에 없는 내용 요약>' 한 줄로만.",
					context, answer);

			String response = chatClient().prompt().user(prompt).call().content();
			return Optional.ofNullable(response);
		}
	}
}
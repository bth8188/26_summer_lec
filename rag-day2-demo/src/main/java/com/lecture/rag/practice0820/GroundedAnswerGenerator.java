package com.lecture.rag.practice0820;
// 검색 문서에 실제로 존재하는 문장만 최종 답변으로 나오도록 하기 위함

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class GroundedAnswerGenerator {

    private static final String UNKNOWN_ANSWER = "관련 문서에서 답을 찾지 못했습니다.";
    private static final Pattern FIRST_NUMBER = Pattern.compile("\\d+");
    private static final Pattern QUESTION_SUFFIX = Pattern.compile(
            "(에서는|으로는|에게는|에는|에서|으로|엔|은|는|이|가|을|를|의|도|야)$"
    );
    private static final Set<String> QUESTION_STOP_WORDS = Set.of(
            "제주", "제주도", "김치", "뭐", "무엇", "어디", "어때", "있어", "알려줘"
    );
    private static final List<TopicRule> TOPIC_RULES = List.of(
            new TopicRule(List.of("몇 명", "인구", "사람이 살아", "주민"), List.of("인구", "주민등록")),
            new TopicRule(List.of("화산", "분출", "용암"), List.of("화산", "분출", "용암")),
            new TopicRule(List.of("면적", "넓이", "크기"), List.of("면적", "km²")),
            new TopicRule(List.of("기온", "온도"), List.of("기온", "℃")),
            new TopicRule(List.of("기후", "날씨"), List.of("기후", "기온", "강수량")),
            new TopicRule(List.of("관광", "관광지", "볼거리"), List.of("관광", "관광지", "올레길"))
    );

    private final ChatClient chatClient;

    public GroundedAnswerGenerator(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        당신은 검색 문서에서 질문에 답하는 문장을 고르는 시스템입니다.
                        아래 규칙을 반드시 지키세요.
                        1. 질문에 직접 답하는 문장의 번호 하나만 출력하세요.
                        2. 답하는 문장이 없으면 0만 출력하세요.
                        3. 설명, 문장 본문, 사전 지식을 출력하지 마세요.
                        """)
                .build();
    }

    public String answer(String question, String evidence, String sourceName) {
        if (evidence == null || evidence.isBlank()) {
            return UNKNOWN_ANSWER;
        }

        List<String> extractedSentences = Arrays.stream(evidence.split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(sentence -> !sentence.isBlank())
                .toList();

        List<String> sentences = filterByQuestionTopic(question, extractedSentences);

        if (sentences.isEmpty()) {
            return UNKNOWN_ANSWER;
        }

        if (sentences.size() == 1) {
            return formatAnswer(sentences.get(0), sourceName);
        }

        String numberedSentences = IntStream.range(0, sentences.size())
                .mapToObj(index -> (index + 1) + ". " + sentences.get(index))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");

        String selectedNumber = chatClient.prompt()
                .user("""
                        [질문]
                        %s

                        [문장 목록]
                        %s
                        """.formatted(question, numberedSentences))
                .call()
                .content();

        if (selectedNumber == null) {
            return UNKNOWN_ANSWER;
        }

        Matcher matcher = FIRST_NUMBER.matcher(selectedNumber);
        if (!matcher.find()) {
            return UNKNOWN_ANSWER;
        }

        int selectedIndex = Integer.parseInt(matcher.group()) - 1;
        if (selectedIndex < 0 || selectedIndex >= sentences.size()) {
            return UNKNOWN_ANSWER;
        }

        return formatAnswer(sentences.get(selectedIndex), sourceName);
    }

    private String formatAnswer(String sentence, String sourceName) {
        String normalizedSentence = sentence
                .replaceAll("\\s+", " ")
                .trim();

        return "문서에 따르면, " + normalizedSentence + "\n출처: " + sourceName;
    }

    private List<String> filterByQuestionTopic(String question, List<String> sentences) {
        if (question == null) {
            return List.of();
        }

        boolean asksWhetherVolcanic = question.contains("화산이야")
                || question.contains("화산인가")
                || question.contains("화산이니");

        if (asksWhetherVolcanic) {
            return sentences.stream()
                    .filter(sentence -> sentence.contains("화산") && sentence.contains("형성"))
                    .toList();
        }

        for (TopicRule rule : TOPIC_RULES) {
            boolean questionMatches = rule.questionKeywords().stream().anyMatch(question::contains);
            if (questionMatches) {
                return sentences.stream()
                        .filter(sentence -> rule.evidenceKeywords().stream().anyMatch(sentence::contains))
                        .toList();
            }
        }

        return filterBySharedKeywords(question, sentences);
    }

    private List<String> filterBySharedKeywords(String question, List<String> sentences) {
        List<String> keywords = Arrays.stream(question.replaceAll("[^가-힣A-Za-z0-9²℃]", " ").split("\\s+"))
                .map(token -> QUESTION_SUFFIX.matcher(token).replaceFirst(""))
                .filter(token -> token.length() >= 2 || token.equals("끝"))
                .filter(token -> !QUESTION_STOP_WORDS.contains(token))
                .toList();

        if (keywords.isEmpty()) {
            return sentences;
        }

        int highestScore = sentences.stream()
                .mapToInt(sentence -> sharedKeywordScore(sentence, keywords))
                .max()
                .orElse(0);

        if (highestScore == 0) {
            return sentences;
        }

        return sentences.stream()
                .filter(sentence -> sharedKeywordScore(sentence, keywords) == highestScore)
                .toList();
    }

    private int sharedKeywordScore(String sentence, List<String> keywords) {
        return (int) keywords.stream()
                .filter(sentence::contains)
                .count();
    }

    private record TopicRule(
            List<String> questionKeywords,
            List<String> evidenceKeywords
    ) {
    }
}

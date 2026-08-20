package com.lecture.rag.lab24;

import com.lecture.rag.lab21m1.RecursiveCharacterSplitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Lab 2.4 — 앞의 랩들을 합쳐서 만든 콘솔 RAG 챗봇.
 *  - 김치 문서: QuestionAnswerAdvisor (항상 검색 — lab23의 (A) 방식)
 *  - 제주 문서: JejuSearchTool (모델이 필요할 때만 호출 — lab23의 (B) 방식)
 *  - 둘 다 빈손이면 "모른다"고 답한다
 *
 * 두 문서가 같은 테이블에 들어가므로, 인덱싱할 때 metadata에 source를 박고
 * 검색할 때 filterExpression으로 갈라야 역할 분담이 유지된다.
 *
 * 실행: 1) docker compose up -d
 *       2) ./run.sh lab24     (종료하려면 빈 줄 입력)
 */
@Component
@Profile("lab24")
public class ConsoleChatbotDemo implements CommandLineRunner {

    private static final String KIMCHI_SOURCE = "kimchi";
    private static final int MAX_CHUNK_CHARS = 400;

    // 무관한 질문일 때 검색 결과를 진짜로 비우기 위한 하한선.
    // 값의 근거는 README 참고 — bge-m3는 무관한 문장에도 점수가 꽤 나와서 실측으로 정해야 한다.
    private static final double SIMILARITY_THRESHOLD = 0.55;

    // 기본 템플릿은 영어이고 "컨텍스트에 없으면 답할 수 없다고 하라"까지만 지시한다.
    // 여기서는 (1) 한국어, (2) 컨텍스트가 비면 도구를 시도하라는 지시를 추가한다.
    // 이게 없으면 제주 질문일 때 김치 컨텍스트가 비었다는 이유로 도구도 안 부르고 "모른다"로 끝나버린다.
    private static final PromptTemplate QA_TEMPLATE = new PromptTemplate("""
            {query}

            아래는 김치 문서에서 검색한 참고 자료입니다. 비어 있을 수도 있습니다.
            ---------------------
            {question_answer_context}
            ---------------------
            답변 규칙:
            1. 참고 자료에 답이 있으면 그 내용만 근거로 한국어로 답하세요.
            2. 참고 자료가 비었거나 질문과 무관하면, 사용할 수 있는 도구가 있는지 확인하고 반드시 먼저 호출해보세요.
            3. 참고 자료에도 없고 도구로도 찾지 못했다면 "제가 가진 자료로는 답변드릴 수 없습니다."라고만 답하세요.
               모르는 내용을 추측해서 지어내지 마세요.
            """);

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    public ConsoleChatbotDemo(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) {
        indexIfEmpty();

        JejuSearchTool jejuTool = new JejuSearchTool(vectorStore, SIMILARITY_THRESHOLD);

        // 김치 문서만 보도록 필터를 건 Advisor — 제주 문서는 이쪽으로 절대 안 딸려온다
        QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .filterExpression("source == '" + KIMCHI_SOURCE + "'")
                        .build())
                .promptTemplate(QA_TEMPLATE)
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        당신은 두 가지 자료만 다루는 도우미입니다.
                        - 김치에 대한 내용은 참고 자료로 자동 제공됩니다.
                        - 제주도에 대한 내용은 searchJeju 도구를 호출해야 얻을 수 있습니다.
                        두 자료 어디에도 없는 질문에는 아는 척하지 말고 모른다고 답하세요. 항상 한국어로 답하세요.
                        """)
                .build();

        System.out.println();
        System.out.println("=== RAG 챗봇 준비 완료 (종료하려면 빈 줄 입력) ===");
        System.out.println("확인용 질문: \"제주도 면적이 얼마야?\" / \"김치는 언제부터 먹었어?\" / \"아이폰 최신 모델이 뭐야?\"");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("질문> ");
            String question = scanner.nextLine();
            if (question == null || question.isBlank()) {
                break;
            }

            // 검색 게이트 — 어느 문서에서도 근거가 안 나오면 LLM에 묻지 않고 바로 거절한다.
            // 3B급 모델은 "모르면 모른다고 하라"는 프롬프트 지시를 자주 무시하고 사전학습 지식으로
            // 답을 지어내기 때문에(실측: "아이폰 최신 모델" 질문에 iPhone 14라고 답함),
            // 프롬프트에만 맡기지 않고 코드로 못을 박는다.
            List<String> matched = matchedSources(question);
            if (matched.isEmpty()) {
                System.out.println("답변> 제가 가진 자료로는 답변드릴 수 없습니다.");
                System.out.println("      (검색 게이트: 유사도 " + SIMILARITY_THRESHOLD + " 이상인 청크 없음 → LLM 호출 안 함)");
                System.out.println();
                continue;
            }

            int before = jejuTool.getCallCount();
            String answer = chatClient.prompt()
                    .advisors(kimchiAdvisor)
                    .tools(jejuTool)
                    .user(question)
                    .call()
                    .content();

            System.out.println("답변> " + answer);
            System.out.println("      (근거 문서: " + matched + " / 제주 도구 "
                    + (jejuTool.getCallCount() > before ? "호출됨" : "호출 안 됨") + ")");
            System.out.println();
        }
    }

    // 질문과 임계값 이상으로 닮은 청크가 어느 source에 있는지 확인한다.
    // 필터 없이 한 번만 검색하므로 비용은 임베딩 1회 + 인덱스 조회 1회뿐.
    private List<String> matchedSources(String question) {
        return vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(question)
                                .topK(3)
                                .similarityThreshold(SIMILARITY_THRESHOLD)
                                .build())
                .stream()
                .map(doc -> String.valueOf(doc.getMetadata().get("source")))
                .distinct()
                .toList();
    }

    // lab21의 countExisting() 패턴 — 이미 넣어둔 데이터가 있으면 재인덱싱하지 않는다
    private void indexIfEmpty() {
        int stored = vectorStore.similaritySearch(
                SearchRequest.builder().query("문서").topK(1000).similarityThresholdAll().build()).size();
        if (stored > 0) {
            System.out.println("=== 이미 인덱싱된 청크 " + stored + "개를 그대로 사용합니다 ===");
            return;
        }

        System.out.println("=== 최초 실행 — 제주/김치 문서를 인덱싱합니다 ===");
        List<Document> all = new ArrayList<>();
        all.addAll(load("6-wiki-jeju.txt", JejuSearchTool.SOURCE));
        all.addAll(load("7-wiki-kimchi.txt", KIMCHI_SOURCE));
        vectorStore.add(all);
        System.out.println("총 " + all.size() + "청크 저장 완료");
    }

    // PDF 대신 .txt를 쓴다 — PDF 추출 텍스트는 공백이 깨져서 챗봇 답변 품질이 떨어진다
    // (lab21m1 ChunkingStrategyDemo.loadFullText()의 공백 정규화 주석 참고)
    private List<Document> load(String file, String source) {
        String text = new TextReader("classpath:/scenarios/" + file).get().get(0).getText();
        List<Document> chunks = new RecursiveCharacterSplitter(MAX_CHUNK_CHARS).split(new Document(text));

        List<Document> tagged = new ArrayList<>();
        for (Document chunk : chunks) {
            // 이 source 태그가 Advisor/도구를 가르는 유일한 기준이다
            tagged.add(new Document(chunk.getText(), java.util.Map.of("source", source)));
        }
        System.out.println("  " + file + " → " + tagged.size() + "청크 (source=" + source + ")");
        return tagged;
    }
}

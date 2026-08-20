package com.lecture.rag.lab24;

import com.lecture.rag.lab21m1.RecursiveCharacterSplitter;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lab 2.4 — 챗봇의 알맹이. 콘솔 버전(ConsoleChatbotDemo)과 API 버전(ChatbotApiController)이
 * 이걸 그대로 공유한다. Day1에서 콘솔 데모와 Swagger 데모가 각자 인덱싱 코드를 들고 있어
 * 같은 로직이 두 벌이던 것을 여기서는 한 벌로 둔다.
 *
 * 두 문서를 다른 방식으로 붙이는 것이 이 랩의 핵심이다.
 *  - 김치: QuestionAnswerAdvisor — 질문이 뭐든 항상 검색해서 프롬프트에 넣는다
 *  - 제주: JejuSearchTool — 모델이 필요하다고 판단할 때만 호출된다
 * 둘 다 같은 테이블에 있으므로 metadata의 source 태그 + filterExpression으로 갈라낸다.
 */
@Service
@Profile({"lab24", "lab24-api"})
public class ChatbotService {

    static final String KIMCHI_SOURCE = "kimchi";
    static final double SIMILARITY_THRESHOLD = 0.55;   // 근거 유무를 가르는 하한선 — 실측으로 정한 값
    private static final int MAX_CHUNK_CHARS = 400;

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

    public static final String REFUSAL = "제가 가진 자료로는 답변드릴 수 없습니다.";

    /**
     * @param sources    답변 근거가 된 문서 (예: ["jeju"]) — 비어 있으면 거절된 것
     * @param toolCalled 제주 검색 도구가 실제로 호출됐는지
     * @param refused    검색 게이트에 막혀 LLM을 아예 호출하지 않았는지
     */
    public record Answer(String question, String answer, List<String> sources, boolean toolCalled, boolean refused) {}

    private final ChatModel chatModel;
    private final VectorStore vectorStore;

    private ChatClient chatClient;
    private JejuSearchTool jejuTool;
    private QuestionAnswerAdvisor kimchiAdvisor;

    public ChatbotService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    void init() {
        indexIfEmpty();

        this.jejuTool = new JejuSearchTool(vectorStore, SIMILARITY_THRESHOLD);

        // 김치 문서만 보도록 필터를 건 Advisor — 제주 문서는 이쪽으로 절대 안 딸려온다
        this.kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(3)
                        .similarityThreshold(SIMILARITY_THRESHOLD)
                        .filterExpression("source == '" + KIMCHI_SOURCE + "'")
                        .build())
                .promptTemplate(QA_TEMPLATE)
                .build();

        this.chatClient = ChatClient.builder(chatModel)
                .defaultSystem("""
                        당신은 두 가지 자료만 다루는 도우미입니다.
                        - 김치에 대한 내용은 참고 자료로 자동 제공됩니다.
                        - 제주도에 대한 내용은 searchJeju 도구를 호출해야 얻을 수 있습니다.
                        두 자료 어디에도 없는 질문에는 아는 척하지 말고 모른다고 답하세요. 항상 한국어로 답하세요.
                        """)
                .build();
    }

    /**
     * 도구 호출 횟수를 요청 전후로 비교해서 호출 여부를 판정하기 때문에 synchronized다.
     * API로 동시 요청이 들어오면 카운터가 섞여 엉뚱한 값이 보고된다 (강의 데모라 이 정도로 충분).
     */
    public synchronized Answer ask(String question) {
        // 검색 게이트 — 어느 문서에서도 근거가 안 나오면 LLM에 묻지 않고 바로 거절한다.
        // 3B급 모델은 "모르면 모른다고 하라"는 프롬프트 지시를 자주 무시하고 사전학습 지식으로
        // 답을 지어내기 때문에(실측: "아이폰 최신 모델" 질문에 iPhone 14라고 답함),
        // 프롬프트에만 맡기지 않고 코드로 못을 박는다.
        List<String> matched = matchedSources(question);
        if (matched.isEmpty()) {
            return new Answer(question, REFUSAL, List.of(), false, true);
        }

        int before = jejuTool.getCallCount();
        String answer = chatClient.prompt()
                .advisors(kimchiAdvisor)
                .tools(jejuTool)
                .user(question)
                .call()
                .content();

        return new Answer(question, answer, matched, jejuTool.getCallCount() > before, false);
    }

    /** 어떤 문서가 몇 청크씩 인덱싱되어 있는지 — source 태그로 갈려 있는 걸 눈으로 확인하는 용도. */
    public Map<String, Integer> indexedChunkCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Document doc : allStored()) {
            String source = String.valueOf(doc.getMetadata().get("source"));
            counts.merge(source, 1, Integer::sum);
        }
        return counts;
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
        int stored = allStored().size();
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

    // PgVectorStore에는 count()나 findAll() API가 없어서, 임계값을 모두 통과시키는 검색으로 대신한다
    private List<Document> allStored() {
        return vectorStore.similaritySearch(
                SearchRequest.builder().query("문서").topK(1000).similarityThresholdAll().build());
    }

    // PDF 대신 .txt를 쓴다 — PDF 추출 텍스트는 공백이 깨져서 챗봇 답변 품질이 떨어진다
    // (lab21m1 ChunkingStrategyDemo.loadFullText()의 공백 정규화 주석 참고)
    private List<Document> load(String file, String source) {
        String text = new TextReader("classpath:/scenarios/" + file).get().get(0).getText();
        List<Document> chunks = new RecursiveCharacterSplitter(MAX_CHUNK_CHARS).split(new Document(text));

        List<Document> tagged = new ArrayList<>();
        for (Document chunk : chunks) {
            // 이 source 태그가 Advisor/도구를 가르는 유일한 기준이다
            tagged.add(new Document(chunk.getText(), Map.of("source", source)));
        }
        System.out.println("  " + file + " → " + tagged.size() + "청크 (source=" + source + ")");
        return tagged;
    }
}

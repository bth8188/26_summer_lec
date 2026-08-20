package com.lecture.rag.lab24;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Lab 2.4 — 챗봇의 핵심 로직. CLI(ChatbotCliRunner)와 REST API(ChatbotController)가 이걸 공유한다.
 *
 *   김치 위키 → QuestionAnswerAdvisor (프롬프트 바인딩형, 매 질문마다 무조건 검색)
 *   제주 위키 → JejuWikiTool (@Tool, 모델이 필요하다고 판단할 때만 호출)
 *
 * 둘 다 같은 PGVector 테이블을 보지만 metadata.source 필터로 갈라져 있고,
 * 근거가 없으면 두 단계 관문에서 "모르겠습니다"로 빠진다.
 */
@Service
@Profile({"chatbot", "api"})
public class ChatbotService {

    public static final String JEJU_FILE = "6-wiki-jeju.txt";
    public static final String KIMCHI_FILE = "7-wiki-kimchi.txt";

    private static final String SYSTEM_PROMPT = """
            당신은 제주도와 김치, 두 가지 주제에 대해서만 답하는 문서 기반 챗봇입니다.
            - 항상 한국어로 답하세요.
            - 참고 자료나 도구 검색 결과에 적힌 내용만 사용하세요. 자료에 없는 사실은 절대 만들어내지 마세요.
            - 특히 방법·절차·레시피는 자료에 단계가 그대로 적혀 있지 않으면 "모르겠습니다."라고 답하세요.
            - 도구가 "찾지 못했습니다"라고 답하면 지어내지 말고 "모르겠습니다."라고 답하세요.
            - 사전 지식만으로 답하지 마세요. 확실하지 않으면 "모르겠습니다."가 정답입니다.
            """;

    /**
     * QuestionAnswerAdvisor의 기본 템플릿은 영어라, 소형 모델(llama3.2:3b)이 한국어로 답하다가 흔들린다.
     * 변수명은 반드시 {query} / {question_answer_context} 두 개를 그대로 써야 한다 (Advisor가 채워 넣는 이름).
     * 3번 규칙이 중요: Advisor가 매번 김치 컨텍스트를 끼워 넣기 때문에, 명시적으로 열어주지 않으면
     * 모델이 "컨텍스트에 없으니 모른다"로 끝내버리고 제주 도구를 아예 호출하지 않는다.
     */
    private static final String KIMCHI_QA_TEMPLATE = """
            사용자 질문:
            {query}

            아래는 김치 위키 문서에서 검색한 참고 자료입니다.
            ---------------------
            {question_answer_context}
            ---------------------

            규칙:
            1. 참고 자료에 답이 그대로 적혀 있을 때만, 그 내용만 근거로 한국어로 답하세요.
            2. 참고 자료에 답이 없으면 "모르겠습니다."라고만 답하세요. 비슷한 내용으로 대신 답하지 마세요.
            3. 사용자의 질문 자체가 제주도(섬·지역)에 관한 것일 때만 제주 위키 검색 도구를 사용하세요.
               참고 자료 안에 "제주도"라는 단어가 있다는 이유만으로 도구를 쓰지 마세요.
            4. 참고 자료에 없는 내용을 사전 지식으로 지어내지 마세요.
            """;

    /** 검색된 청크 하나 — 어느 문서에서 몇 점으로 걸렸는지. */
    public record Hit(String source, double score, boolean passed, String preview) {}

    /** 한 번의 질의 결과. answered=false면 "모르겠습니다"이고 reason에 어느 관문에서 막혔는지 담긴다. */
    public record Answer(boolean answered, String answer, String reason,
                         int jejuToolCalls, List<Hit> retrieved) {}

    /** 임계값 튜닝용 — 임계값을 적용하지 않은 원점수. */
    public record Scores(String question, double threshold, List<Hit> jeju, List<Hit> kimchi) {}

    private final ChatModel chatModel;
    private final VectorStore vectorStore;
    private final RetrievalPolicy policy = new RetrievalPolicy();
    private final WikiIndexer indexer;
    private final JejuWikiTool jejuTool;
    private final GroundingGate gate;

    public ChatbotService(ChatModel chatModel, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.vectorStore = vectorStore;
        this.indexer = new WikiIndexer(vectorStore);
        this.jejuTool = new JejuWikiTool(vectorStore, policy);
        this.gate = new GroundingGate(ChatClient.builder(chatModel).build());
    }

    @PostConstruct
    public void indexOnStartup() {
        indexer.ensureIndexed(JEJU_FILE, RetrievalPolicy.SOURCE_JEJU);
        indexer.ensureIndexed(KIMCHI_FILE, RetrievalPolicy.SOURCE_KIMCHI);
    }

    // ------------------------------------------------------------------ 질의

    /**
     * synchronized인 이유: 제주 도구 호출 횟수를 before/after 차이로 세기 때문에,
     * API로 동시 요청이 들어오면 카운트가 뒤섞인다. 로컬 Ollama는 어차피 요청을 직렬화하므로
     * 여기서 잠그는 비용은 사실상 없다.
     */
    public synchronized Answer ask(String question) {
        // ── 관문 1: 임계값을 넘긴 후보가 양쪽 모두 0건이면 LLM을 부르지도 않는다.
        //           "완전히 무관한 질문"은 여기서 끝난다 (모델의 판단이 개입할 여지 자체를 없앰).
        List<Document> kimchiHits = vectorStore.similaritySearch(
                policy.forTool(question, RetrievalPolicy.SOURCE_KIMCHI));
        List<Document> jejuHits = vectorStore.similaritySearch(
                policy.forTool(question, RetrievalPolicy.SOURCE_JEJU));
        List<Hit> retrieved = toHits(kimchiHits, jejuHits);

        if (kimchiHits.isEmpty() && jejuHits.isEmpty()) {
            return new Answer(false, "모르겠습니다.",
                    "검색 0건 (임계값 " + policy.getSimilarityThreshold() + ") — LLM 호출 없이 차단",
                    0, retrieved);
        }

        // ── 관문 2: 후보는 있지만 그 안에 답이 없는 경우.
        //           임계값으로는 절대 못 막는 구간이라, 생성 전에 예/아니오로 먼저 확인한다.
        if (!gate.canAnswer(question, joinContext(kimchiHits, jejuHits))) {
            return new Answer(false, "모르겠습니다.",
                    "검색은 됐지만 자료에 답이 없다고 판정 — 답변 생성 차단",
                    0, retrieved);
        }

        // 임계값을 런타임에 바꿀 수 있어야 해서 매 요청 새로 조립한다 (조립 비용은 무시할 수준).
        QuestionAnswerAdvisor kimchiAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(policy.forAdvisor(RetrievalPolicy.SOURCE_KIMCHI))
                .promptTemplate(PromptTemplate.builder().template(KIMCHI_QA_TEMPLATE).build())
                .build();

        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .build();

        int before = jejuTool.getCallCount();
        String answer = chatClient.prompt()
                .advisors(kimchiAdvisor)   // 김치: 항상 실행
                .tools(jejuTool)           // 제주: 모델이 판단할 때만
                .user(question)
                .call()
                .content();
        int calls = jejuTool.getCallCount() - before;

        return new Answer(true, answer == null ? "" : answer.trim(),
                "답변 생성됨", calls, retrieved);
    }

    // ------------------------------------------------------- 임계값 튜닝 지원

    /**
     * 임계값을 감으로 정하지 않기 위한 조회.
     * 임계값 없이(similarityThresholdAll) 검색해 <b>원점수</b>를 그대로 돌려주므로,
     * "관련 질문일 때 분포"와 "무관한 질문일 때 분포" 사이 어디에 선을 그을지 눈으로 정할 수 있다.
     */
    public Scores scores(String question, int topK) {
        return new Scores(question, policy.getSimilarityThreshold(),
                probe(question, RetrievalPolicy.SOURCE_JEJU, topK),
                probe(question, RetrievalPolicy.SOURCE_KIMCHI, topK));
    }

    public double getThreshold() {
        return policy.getSimilarityThreshold();
    }

    public void setThreshold(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("임계값은 0.0 ~ 1.0 사이여야 합니다: " + value);
        }
        policy.setSimilarityThreshold(value);
    }

    /** 두 위키를 지우고 다시 인덱싱한다 (청킹 방식을 바꿨을 때). */
    public synchronized void reindex() {
        indexer.reindex(JEJU_FILE, RetrievalPolicy.SOURCE_JEJU);
        indexer.reindex(KIMCHI_FILE, RetrievalPolicy.SOURCE_KIMCHI);
    }

    // ------------------------------------------------------------------ 내부

    private List<Hit> probe(String question, String source, int topK) {
        return vectorStore.similaritySearch(policy.probe(question, source, topK))
                .stream()
                .map(doc -> toHit(doc, source))
                .toList();
    }

    private List<Hit> toHits(List<Document> kimchiHits, List<Document> jejuHits) {
        List<Hit> hits = new ArrayList<>();
        kimchiHits.forEach(doc -> hits.add(toHit(doc, RetrievalPolicy.SOURCE_KIMCHI)));
        jejuHits.forEach(doc -> hits.add(toHit(doc, RetrievalPolicy.SOURCE_JEJU)));
        return hits;
    }

    private Hit toHit(Document doc, String source) {
        double score = doc.getScore() == null ? 0.0 : doc.getScore();
        return new Hit(source, score, score >= policy.getSimilarityThreshold(), preview(doc));
    }

    /** 관문 2에 넘길 컨텍스트 — 두 소스의 검색 결과를 그대로 이어붙인다. */
    private String joinContext(List<Document> kimchiHits, List<Document> jejuHits) {
        StringBuilder sb = new StringBuilder();
        for (Document doc : kimchiHits) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        for (Document doc : jejuHits) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }

    private String preview(Document doc) {
        String text = doc.getText().replaceAll("\\s+", " ").trim();
        return text.substring(0, Math.min(60, text.length())) + (text.length() > 60 ? "..." : "");
    }
}

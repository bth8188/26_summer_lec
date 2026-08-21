package com.lecture.rag.lab24;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lab 2.4 — 제주 위키를 "도구"로 제공. lab23의 DocumentSearchTool과 골격은 동일하고,
 * 바뀐 건 (1) filterExpression으로 제주 문서만 보는 것, (2) similarityThreshold로 0건을 만들 수 있는 것 둘뿐.
 * 검색 조건은 RetrievalPolicy에 위임해서, CLI에서 /threshold로 바꾼 값이 즉시 반영되게 했다.
 */
public class JejuWikiTool {

    private final VectorStore vectorStore;
    private final RetrievalPolicy policy;
    // REST API로도 노출되면서 여러 요청 스레드가 동시에 건드릴 수 있어 AtomicInteger로 바꿨다.
    // (호출 횟수를 before/after 차이로 세는 쪽은 ChatbotService.ask()가 synchronized로 감싼다)
    private final AtomicInteger callCount = new AtomicInteger();

    public JejuWikiTool(VectorStore vectorStore, RetrievalPolicy policy) {
        this.vectorStore = vectorStore;
        this.policy = policy;
    }

    public int getCallCount() {
        return callCount.get();
    }

    // description은 모델이 "이 도구를 부를지 말지" 판단하는 유일한 근거다.
    // 파일명(6-wiki-jeju.pdf)은 모델에게 아무 의미가 없어서, 내용이 무엇인지 + 언제 쓰면 안 되는지로 적는다.
    @Tool(description = "제주도에 관한 위키 문서를 검색한다. 사용자의 질문 자체가 제주도(섬·지역)에 관한 것일 때만 "
            + "사용할 것 — 지리, 역사, 기후, 화산·오름, 관광지, 특산물, 방언 등. "
            + "김치를 비롯한 다른 주제의 질문에는 사용하지 말 것. "
            + "참고 자료 안에 '제주도'라는 단어가 등장한다는 이유만으로 호출하지 말 것.")
    public String searchJejuWiki(@ToolParam(description = "제주 위키에서 검색할 질문 또는 키워드") String query) {
        callCount.incrementAndGet();
        System.out.println("  >>> [도구 호출됨] searchJejuWiki(\"" + query + "\")");

        var results = vectorStore.similaritySearch(
                policy.forTool(query, RetrievalPolicy.SOURCE_JEJU));

        // 임계값을 넘긴 청크가 하나도 없을 때만 여기 걸린다 — "모른다"의 1차 신호.
        // 이 문자열이 그대로 모델에게 돌아가고, 시스템 프롬프트가 "지어내지 말라"고 못 박는다.
        if (results.isEmpty()) {
            return "제주 위키에서 관련된 내용을 찾지 못했습니다.";
        }

        StringBuilder sb = new StringBuilder();
        for (var doc : results) {
            sb.append("- ").append(doc.getText().replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }
}

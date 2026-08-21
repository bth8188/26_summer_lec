package com.lecture.rag.day3.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 프론트엔드로 흘려보내는 "스트림 이벤트" 한 줄.
 *
 * <p>Day1/Day2에서는 LLM이 만든 글자만 그대로 흘려보냈다(text/plain). 그러면 화면에 답변만 뜨고
 * "지금 뭘 하고 있는지"(검색 중인지, 몇 개를 찾았는지, 어떤 청크를 근거로 썼는지)를 보여줄 수 없다.
 * 그래서 이 프로젝트는 답변 토큰과 진행 상황을 <b>같은 스트림에 섞어서</b> 한 줄에 JSON 하나씩
 * (NDJSON, {@code application/x-ndjson}) 내려보낸다. 프론트는 줄 단위로 읽어서 type별로 화면에 꽂는다.
 *
 * <pre>
 * {"type":"step","id":"retrieve","label":"벡터 검색","status":"running"}
 * {"type":"sources","sources":[{"index":1,"fileName":"학칙.pdf","page":3,"score":0.62,"text":"..."}]}
 * {"type":"token","text":"제"}
 * {"type":"token","text":"3조에"}
 * {"type":"done","ms":4120}
 * </pre>
 *
 * <p><b>학생 확장 지점</b>: 새 이벤트 타입이 필요하면 클래스를 고칠 필요 없이
 * {@code AgentEvent.of("myEvent").with("key", value)} 로 아무 필드나 붙여서 보내면 된다.
 * 프론트 쪽은 {@code src/lib/protocol.ts} 에 타입만 추가하면 된다.
 */
public final class AgentEvent {

    /** 진행 단계가 시작됨. */
    public static final String STATUS_RUNNING = "running";
    /** 진행 단계가 정상 종료됨. */
    public static final String STATUS_DONE = "done";
    /** 아직 구현되지 않은 단계(학생 과제) — 프론트에서 점선 카드로 표시된다. */
    public static final String STATUS_TODO = "todo";
    /** 단계 실행 중 예외 발생. */
    public static final String STATUS_ERROR = "error";

    private final String type;
    private final Map<String, Object> data = new LinkedHashMap<>();

    private AgentEvent(String type) {
        this.type = type;
    }

    @JsonProperty("type")
    public String type() {
        return type;
    }

    /** data 맵의 내용은 중첩 객체가 아니라 JSON 최상위 필드로 펼쳐진다(@JsonAnyGetter). */
    @JsonAnyGetter
    public Map<String, Object> data() {
        return data;
    }

    /** 값이 null이면 조용히 무시 — 프론트에서 optional 필드로 다루기 쉽게 하기 위함. */
    public AgentEvent with(String key, Object value) {
        if (value != null) {
            this.data.put(key, value);
        }
        return this;
    }

    /** 임의 타입 이벤트 만들기(학생 확장용). */
    public static AgentEvent of(String type) {
        return new AgentEvent(type);
    }

    // ---------------------------------------------------------------- 단계(step)

    public static AgentEvent stepStart(String id, String label) {
        return of("step").with("id", id).with("label", label).with("status", STATUS_RUNNING);
    }

    public static AgentEvent stepDone(String id, String label, long ms, String detail) {
        return of("step").with("id", id).with("label", label).with("status", STATUS_DONE)
                .with("ms", ms).with("detail", detail);
    }

    /**
     * "여기는 아직 네가 구현할 자리다"를 화면에 띄우는 이벤트.
     *
     * @param hint 무엇을 하면 되는지 한 줄 힌트
     * @param file 구현할 파일/메서드 위치 (예: StudentRagPipeline#rewriteQueries)
     */
    public static AgentEvent stepTodo(String id, String label, String hint, String file) {
        return of("step").with("id", id).with("label", label).with("status", STATUS_TODO)
                .with("hint", hint).with("file", file);
    }

    public static AgentEvent stepError(String id, String label, String message) {
        return of("step").with("id", id).with("label", label).with("status", STATUS_ERROR)
                .with("detail", message);
    }

    public static AgentEvent progress(String id, String label, int current, int total) {
        return of("progress").with("id", id).with("label", label)
                .with("current", current).with("total", total);
    }

    // ---------------------------------------------------------------- 본문/근거

    /** LLM이 생성한 답변 조각. */
    public static AgentEvent token(String text) {
        return of("token").with("text", text);
    }

    /** 이번 답변의 근거로 쓴 청크 목록. 프론트 우측 인스펙터의 "근거" 탭에 뜬다. */
    public static AgentEvent sources(List<SourceRef> sources) {
        return of("sources").with("sources", sources);
    }

    /** 숫자/문자 지표 한 개. 프론트 상단 배지와 인스펙터 지표 탭에 뜬다. */
    public static AgentEvent metric(String key, String label, Object value) {
        return of("metric").with("key", key).with("label", label).with("value", value);
    }

    /** 사용자에게 보여줄 안내/경고. level: info | warn | error */
    public static AgentEvent notice(String level, String message) {
        return of("notice").with("level", level).with("message", message);
    }

    /** 인덱싱 완료된 문서 하나(사이드바 문서 목록 갱신용). */
    public static AgentEvent document(Object indexedDocument) {
        return of("document").with("document", indexedDocument);
    }

    /** 스트림 정상 종료. */
    public static AgentEvent done(long ms) {
        return of("done").with("ms", ms);
    }

    /** 스트림이 예외로 끝남 — 프론트는 이 메시지를 그대로 보여준다. */
    public static AgentEvent error(String message) {
        return of("error").with("message", message);
    }
}

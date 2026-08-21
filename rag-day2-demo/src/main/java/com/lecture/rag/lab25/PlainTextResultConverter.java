package com.lecture.rag.lab25;

import org.springframework.ai.tool.execution.ToolCallResultConverter;

import java.lang.reflect.Type;

/**
 * Lab 2.5 — 도구 반환값을 JSON으로 감싸지 않고 문자열 그대로 넘기는 변환기.
 *
 * 기본 변환기는 결과를 JSON 직렬화해서 "총 8건\\n- 1번 ..." 처럼 따옴표와 \\n이 그대로 보인다.
 * LLM이 다시 읽고 정리해주는 경우엔 상관없지만, returnDirect = true 로 결과가 사용자에게
 * 곧장 가는 도구에서는 이스케이프가 그대로 노출되므로 원문을 유지해야 한다.
 */
public class PlainTextResultConverter implements ToolCallResultConverter {

    @Override
    public String convert(Object result, Type returnType) {
        return result == null ? "" : String.valueOf(result);
    }
}

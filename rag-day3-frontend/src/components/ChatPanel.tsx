"use client";

/** 가운데 영역: 대화 목록 + 입력창 + 첫 화면 안내. */

import { useEffect, useMemo, useRef, useState } from "react";

import type { AgentConsole } from "@/lib/useAgentConsole";
import { RunCard } from "./RunCard";
import { Badge, Icon, SectionLabel, Spinner } from "./ui";

export function ChatPanel({
  console: agent,
  onCitation,
}: {
  console: AgentConsole;
  onCitation: (messageId: string, runId: string, index: number) => void;
}) {
  const { activeSession, busy, documents, send, stop, regenerate, evaluate, inspected } = agent;
  const [input, setInput] = useState("");
  const scrollRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const messages = useMemo(() => activeSession?.messages ?? [], [activeSession?.messages]);

  // 새 메시지/토큰이 오면 아래로 따라간다 (사용자가 위로 스크롤한 상태면 방해하지 않는다)
  useEffect(() => {
    const container = scrollRef.current;
    if (!container) return;
    const nearBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight < 160;
    if (nearBottom) bottomRef.current?.scrollIntoView({ block: "end" });
  }, [messages]);

  const submit = () => {
    const question = input.trim();
    if (!question || busy) return;
    setInput("");
    if (textareaRef.current) textareaRef.current.style.height = "auto";
    void send(question);
  };

  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col">
      <div ref={scrollRef} className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
        <div className="mx-auto flex w-full max-w-[900px] flex-col gap-5">
          {messages.length === 0 && (
            <EmptyState
              documentNames={documents.map((document) => document.fileName)}
              onPick={(question) => {
                setInput(question);
                textareaRef.current?.focus();
              }}
            />
          )}

          {messages.map((message) =>
            message.role === "user" ? (
              <div key={message.id} className="flex justify-end">
                <div className="max-w-[80%] rounded-2xl rounded-br-md bg-[var(--user-bubble)] px-3.5 py-2.5 text-[14px] leading-relaxed whitespace-pre-wrap text-ink">
                  {message.text}
                </div>
              </div>
            ) : (
              <div
                key={message.id}
                className={`grid min-w-0 gap-3 ${
                  (message.runs?.length ?? 0) > 1 ? "lg:grid-cols-2" : "grid-cols-1"
                }`}
              >
                {message.runs?.map((run) => (
                  <RunCard
                    key={run.runId}
                    message={message}
                    run={run}
                    busy={busy}
                    active={inspected?.run.runId === run.runId}
                    onCitation={(index) => onCitation(message.id, run.runId, index)}
                    onInspect={() => agent.setInspectorTarget({ messageId: message.id, runId: run.runId })}
                    onEvaluate={() => void evaluate(message.id, run.runId)}
                    onRegenerate={() => void regenerate(message.id)}
                  />
                ))}
              </div>
            ),
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 입력창 */}
      <div className="border-t border-line bg-panel/60 px-5 py-3.5 backdrop-blur">
        <div className="mx-auto w-full max-w-[900px]">
          <div className="panel flex items-end gap-2 p-2">
            <textarea
              ref={textareaRef}
              value={input}
              rows={1}
              placeholder={
                documents.length === 0
                  ? "문서를 먼저 업로드하세요 — 그래도 질문은 보낼 수 있습니다(근거 없음 응답)"
                  : "문서에 대해 질문하기…  (Enter 전송 · Shift+Enter 줄바꿈)"
              }
              onChange={(event) => {
                setInput(event.target.value);
                const element = event.target;
                element.style.height = "auto";
                element.style.height = `${Math.min(element.scrollHeight, 180)}px`;
              }}
              onKeyDown={(event) => {
                // 한글 입력 조합 중(isComposing)에 Enter를 삼키면 마지막 글자가 잘린다
                if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
                  event.preventDefault();
                  submit();
                }
              }}
              className="textarea max-h-[180px] flex-1 border-none bg-transparent px-2 py-1.5"
            />
            {busy ? (
              <button type="button" className="btn" onClick={stop} title="스트리밍 중단">
                <Icon name="stop" />
                중단
              </button>
            ) : (
              <button type="button" className="btn btn-primary" onClick={submit} disabled={!input.trim()}>
                <Icon name="send" />
                전송
              </button>
            )}
          </div>

          <div className="mt-2 flex flex-wrap items-center gap-2 px-1 text-[11px] text-subtle">
            <span className="flex items-center gap-1.5">
              <Icon name="bolt" className="text-accent" />
              파이프라인 <span className="text-muted">{agent.selectedPipeline?.name ?? agent.settings.pipelineId}</span>
            </span>
            {agent.settings.compare && <Badge tone="violet">비교 실행</Badge>}
            <span>topK {agent.settings.topK}</span>
            <span>temp {agent.settings.temperature}</span>
            {agent.selectedDocIds.length > 0 && (
              <Badge tone="accent">검색 대상 {agent.selectedDocIds.length}개 문서</Badge>
            )}
            {busy && (
              <span className="ml-auto flex items-center gap-1.5 text-accent">
                <Spinner /> 응답 생성 중…
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function EmptyState({
  documentNames,
  onPick,
}: {
  documentNames: string[];
  onPick: (question: string) => void;
}) {
  const first = documentNames[0];
  const suggestions = first
    ? [
        `${first} 의 핵심 내용을 3줄로 요약해줘`,
        `${first} 에서 가장 중요한 숫자나 날짜는?`,
        "문서에 없는 내용을 물어보면 어떻게 답하는지 보고 싶어 — 아무 엉뚱한 질문에 답해봐",
      ]
    : [];

  return (
    <div className="fade-in flex flex-col gap-4">
      <div className="panel p-5">
        <h2 className="text-[15px] font-semibold text-ink">RAG 에이전트 콘솔</h2>
        <p className="mt-1 text-[13px] leading-relaxed text-muted">
          문서를 인덱싱하고, 질문하고, <span className="text-ink">검색된 근거와 각 단계가 실제로 뭘 했는지</span>{" "}
          눈으로 확인하면서 파이프라인을 고쳐나가는 작업 화면입니다.
        </p>

        <ol className="mt-4 flex flex-col gap-2.5">
          <Step
            index={1}
            title="문서 인덱싱"
            body="왼쪽 지식 베이스 패널에 PDF·txt·md를 끌어다 놓으면 읽기 → 청킹 → 임베딩 단계가 실시간으로 보입니다. 청킹 전략과 청크 크기도 바꿔볼 수 있습니다."
            done={documentNames.length > 0}
          />
          <Step
            index={2}
            title="질문하고 근거 확인"
            body="답변 안의 [1] 인용 칩을 누르면 그 문장이 어느 청크에서 나왔는지, 유사도 점수가 몇인지 오른쪽 인스펙터에서 바로 보입니다."
          />
          <Step
            index={3}
            title="내 파이프라인 구현"
            body="설정(⚙)에서 파이프라인을 '내 파이프라인'으로 바꾸고 기능 토글을 켜면, 아직 구현하지 않은 단계가 TODO 카드로 표시됩니다. 백엔드 StudentRagPipeline.java의 해당 메서드를 채우면 그 자리가 실제 실행 결과로 바뀝니다."
          />
        </ol>
      </div>

      {suggestions.length > 0 && (
        <div>
          <SectionLabel>이렇게 물어보세요</SectionLabel>
          <div className="flex flex-col gap-1.5">
            {suggestions.map((suggestion) => (
              <button
                key={suggestion}
                type="button"
                onClick={() => onPick(suggestion)}
                className="card px-3 py-2 text-left text-[13px] text-muted transition-colors hover:border-accent/40 hover:text-ink"
              >
                {suggestion}
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Step({
  index,
  title,
  body,
  done,
}: {
  index: number;
  title: string;
  body: string;
  done?: boolean;
}) {
  return (
    <li className="flex gap-3">
      <span
        className={`mono mt-0.5 flex size-5 shrink-0 items-center justify-center rounded-md border text-[11px] font-semibold ${
          done ? "border-ok/50 bg-ok/10 text-ok" : "border-line bg-panel2 text-muted"
        }`}
      >
        {done ? "✓" : index}
      </span>
      <span className="min-w-0">
        <span className="block text-[13px] font-medium text-ink">{title}</span>
        <span className="mt-0.5 block text-[12px] leading-relaxed text-muted">{body}</span>
      </span>
    </li>
  );
}

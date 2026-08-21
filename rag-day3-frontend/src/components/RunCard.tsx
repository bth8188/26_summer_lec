"use client";

/**
 * 어시스턴트 실행 하나(Run)를 그리는 카드.
 * 단계 타임라인 → 안내/경고 → 답변 본문 → 지표/액션 → 채점 결과 순서.
 * 비교 실행을 켜면 이 카드가 나란히 두 개 그려진다.
 */

import { useState } from "react";

import { metricValue, type Message, type Run } from "@/lib/state";
import { MarkdownLite } from "./MarkdownLite";
import { StepTrace } from "./StepTrace";
import { Badge, CopyButton, Icon, ScoreBar, Spinner, tierLabel, tierTone } from "./ui";

function formatMs(value: number | string | undefined): string | null {
  if (typeof value !== "number") return null;
  return value >= 1000 ? `${(value / 1000).toFixed(1)}초` : `${value}ms`;
}

export function RunCard({
  message,
  run,
  active,
  onCitation,
  onInspect,
  onEvaluate,
  onRegenerate,
  busy,
}: {
  message: Message;
  run: Run;
  active: boolean;
  onCitation: (index: number) => void;
  onInspect: () => void;
  onEvaluate: () => void;
  onRegenerate: () => void;
  busy: boolean;
}) {
  const streaming = run.status === "streaming";
  const hasTodo = run.steps.some((step) => step.status === "todo");
  const [showSteps, setShowSteps] = useState(true);

  const total = formatMs(run.ms);
  const retrieve = formatMs(metricValue(run, "retrieveMs"));
  const generate = formatMs(metricValue(run, "generateMs"));
  const chunks = metricValue(run, "chunks");
  const promptTokens = metricValue(run, "promptTokens");
  const completionTokens = metricValue(run, "completionTokens");
  const speed = metricValue(run, "tokensPerSecond");

  return (
    <section
      className={`card flex min-w-0 flex-col gap-3 p-3.5 transition-colors ${
        active ? "border-accent/45" : ""
      }`}
    >
      {/* 헤더 */}
      <header className="flex flex-wrap items-center gap-2">
        <Badge tone={tierTone(run.tier)}>{run.pipelineName}</Badge>
        <span className="text-[10.5px] text-subtle">{tierLabel(run.tier)}</span>
        {streaming && (
          <span className="flex items-center gap-1.5 text-[11px] text-accent">
            <Spinner /> 실행 중
          </span>
        )}
        {run.status === "stopped" && <Badge tone="warn">중단됨</Badge>}
        {run.status === "error" && <Badge tone="err">오류</Badge>}
        {hasTodo && <Badge tone="warn">미구현 단계 있음</Badge>}
        <span className="ml-auto flex items-center gap-2">
          {total && <span className="mono text-[11px] text-subtle">{total}</span>}
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            onClick={() => setShowSteps((prev) => !prev)}
          >
            <Icon name="chevron" className={showSteps ? "rotate-90" : ""} />
            단계 {run.steps.length}
          </button>
        </span>
      </header>

      {showSteps && <StepTrace steps={run.steps} />}

      {run.notices.map((notice, index) => (
        <p
          key={index}
          className={`rounded-lg border px-2.5 py-2 text-[12px] leading-snug ${
            notice.level === "warn"
              ? "border-warn/40 bg-warn/[0.07] text-warn"
              : notice.level === "error"
                ? "border-err/40 bg-err/[0.07] text-err"
                : "border-line bg-panel2 text-muted"
          }`}
        >
          {notice.message}
        </p>
      ))}

      {run.error && (
        <p className="rounded-lg border border-err/40 bg-err/[0.07] px-2.5 py-2 text-[12px] text-err">
          {run.error}
        </p>
      )}

      {/* 답변 본문 */}
      {(run.text || streaming) && (
        <MarkdownLite text={run.text} streaming={streaming} onCitation={onCitation} />
      )}

      {/* 지표 + 액션 */}
      <footer className="flex flex-wrap items-center gap-1.5 border-t border-line pt-2.5">
        {typeof chunks !== "undefined" && <span className="chip">근거 {chunks}개</span>}
        {retrieve && <span className="chip">검색 {retrieve}</span>}
        {generate && <span className="chip">생성 {generate}</span>}
        {typeof promptTokens !== "undefined" && (
          <span className="chip mono" title="프롬프트 토큰 / 생성 토큰">
            {String(promptTokens)}→{String(completionTokens ?? "?")} tok
          </span>
        )}
        {typeof speed !== "undefined" && <span className="chip mono">{String(speed)} tok/s</span>}

        <span className="ml-auto flex items-center gap-1">
          <button type="button" className="btn btn-ghost btn-xs" onClick={onInspect}>
            <Icon name="search" />
            근거 {run.sources.length}
          </button>
          {run.text && <CopyButton text={run.text} />}
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            onClick={onRegenerate}
            disabled={busy}
            title="현재 설정으로 같은 질문을 다시 실행"
          >
            <Icon name="refresh" />
            재실행
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            onClick={onEvaluate}
            disabled={!run.text || run.evaluating || streaming}
            title="LLM-as-judge로 이 답변을 채점 (골드 티어)"
          >
            {run.evaluating ? <Spinner /> : <Icon name="scale" />}
            채점
          </button>
        </span>
      </footer>

      {/* 채점 결과 */}
      {run.evaluation && <Evaluation run={run} question={message.question ?? ""} />}
    </section>
  );
}

function Evaluation({ run, question }: { run: Run; question: string }) {
  const evaluation = run.evaluation!;
  const [showRaw, setShowRaw] = useState(false);
  const parsed = evaluation.faithfulness !== null || evaluation.relevancy !== null;

  return (
    <div className="rounded-lg border border-accent2/35 bg-accent2/[0.06] p-3">
      <div className="mb-2 flex items-center gap-2">
        <Icon name="scale" className="text-accent2" />
        <span className="text-[12.5px] font-semibold text-ink">LLM-as-judge 채점</span>
        <button
          type="button"
          className="btn btn-ghost btn-xs ml-auto"
          onClick={() => setShowRaw((prev) => !prev)}
        >
          원문 {showRaw ? "숨기기" : "보기"}
        </button>
      </div>

      {parsed ? (
        <div className="grid grid-cols-2 gap-3">
          <ScoreRow label="충실도" value={evaluation.faithfulness} hint="근거에 있는 내용만 말했는가" />
          <ScoreRow label="관련성" value={evaluation.relevancy} hint="질문에 실제로 답했는가" />
        </div>
      ) : (
        <p className="text-[12px] text-warn">
          채점 형식을 파싱하지 못했습니다 — 아래 원문을 확인하세요(로컬 3B 모델은 형식을 자주 어깁니다).
        </p>
      )}

      {evaluation.reason && <p className="mt-2 text-[12px] leading-snug text-muted">{evaluation.reason}</p>}

      {showRaw && (
        <pre className="mono mt-2 max-h-40 overflow-auto rounded-md border border-line bg-panel3 p-2 text-[11px] whitespace-pre-wrap text-muted">
          {`질문: ${question}\n\n${evaluation.raw}`}
        </pre>
      )}
    </div>
  );
}

function ScoreRow({ label, value, hint }: { label: string; value: number | null; hint: string }) {
  return (
    <div>
      <div className="mb-1 flex items-baseline justify-between">
        <span className="text-[12px] text-muted">{label}</span>
        <span className="mono text-[12.5px] font-semibold text-ink">
          {value === null ? "—" : `${value}/5`}
        </span>
      </div>
      <ScoreBar value={value ?? 0} />
      <p className="mt-1 text-[10.5px] text-subtle">{hint}</p>
    </div>
  );
}

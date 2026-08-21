"use client";

/**
 * 오른쪽 인스펙터. "왜 이 답이 나왔는가"를 확인하는 창.
 * 근거(검색된 청크 원문+점수) / 단계 / 지표 / 원본 이벤트 로그 네 개 탭.
 */

import { useEffect, useState } from "react";

import type { Message, Run } from "@/lib/state";
import { StepTrace } from "./StepTrace";
import { Badge, Icon, tierTone } from "./ui";

type Tab = "sources" | "steps" | "metrics" | "log";

const TABS: { id: Tab; label: string }[] = [
  { id: "sources", label: "근거" },
  { id: "steps", label: "단계" },
  { id: "metrics", label: "지표" },
  { id: "log", label: "로그" },
];

export function InspectorPanel({
  inspected,
  focus,
  onClose,
}: {
  inspected: { message: Message; run: Run } | null;
  focus: { runId: string; index: number } | null;
  onClose: () => void;
}) {
  const [tab, setTab] = useState<Tab>("sources");
  const [showTokens, setShowTokens] = useState(false);
  const [flashKey, setFlashKey] = useState<string | null>(null);
  const [handledFocus, setHandledFocus] = useState<string | null>(null);

  const focusKey = focus ? `${focus.runId}:${focus.index}` : null;

  // 답변 안의 인용 칩([1])을 누르면 근거 탭으로 전환 + 하이라이트.
  // 렌더 중 파생 상태 갱신(새 focus가 들어왔을 때만 1회) — 효과 안에서 setState 하지 않는 방식.
  if (focusKey && focusKey !== handledFocus) {
    setHandledFocus(focusKey);
    setFlashKey(focusKey);
    setTab("sources");
  }

  // 스크롤 이동(DOM 조작)과 하이라이트 해제(타이머)는 효과에서
  useEffect(() => {
    if (!focus) return;
    const element = document.getElementById(`source-${focus.runId}-${focus.index}`);
    element?.scrollIntoView({ block: "center", behavior: "smooth" });
    const timer = setTimeout(() => setFlashKey(null), 1700);
    return () => clearTimeout(timer);
  }, [focus]);

  const flash = flashKey && focus && flashKey === `${focus.runId}:${focus.index}` ? focus.index : null;

  const run = inspected?.run;

  return (
    <div className="flex min-h-0 flex-col">
      <header className="flex items-center gap-2 border-b border-line px-3 py-2.5">
        <span className="text-[11px] font-semibold tracking-[0.13em] text-subtle uppercase">인스펙터</span>
        {run && <Badge tone={tierTone(run.tier)}>{run.pipelineName}</Badge>}
        <button type="button" className="btn btn-ghost btn-xs ml-auto lg:hidden" onClick={onClose}>
          <Icon name="close" />
        </button>
      </header>

      {!run ? (
        <p className="px-4 py-6 text-[12.5px] leading-relaxed text-subtle">
          질문을 하나 보내면 검색된 근거와 각 단계 기록이 여기에 표시됩니다.
        </p>
      ) : (
        <>
          <nav className="flex gap-1 border-b border-line px-2 py-2">
            {TABS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setTab(item.id)}
                className={`rounded-md px-2.5 py-1 text-[12px] transition-colors ${
                  tab === item.id
                    ? "bg-accent/12 text-accent"
                    : "text-muted hover:bg-panel3 hover:text-ink"
                }`}
              >
                {item.label}
                {item.id === "sources" && run.sources.length > 0 && (
                  <span className="mono ml-1 text-[10px] opacity-70">{run.sources.length}</span>
                )}
              </button>
            ))}
          </nav>

          <div className="min-h-0 flex-1 overflow-y-auto px-3 py-3">
            {tab === "sources" && <SourcesTab run={run} flash={flash} />}
            {tab === "steps" && (
              <div className="flex flex-col gap-3">
                <StepTrace steps={run.steps} />
                {run.steps.length === 0 && <p className="text-[12px] text-subtle">단계 기록이 없습니다.</p>}
              </div>
            )}
            {tab === "metrics" && <MetricsTab run={run} />}
            {tab === "log" && (
              <LogTab run={run} showTokens={showTokens} onToggleTokens={() => setShowTokens((v) => !v)} />
            )}
          </div>
        </>
      )}
    </div>
  );
}

function SourcesTab({ run, flash }: { run: Run; flash: number | null }) {
  if (run.sources.length === 0) {
    return (
      <p className="text-[12.5px] leading-relaxed text-subtle">
        검색된 근거가 없습니다. 설정에서 유사도 임계값을 낮추거나 topK를 올려보세요.
      </p>
    );
  }
  return (
    <div className="flex flex-col gap-2">
      {run.sources.map((source) => (
        <SourceCard
          key={`${source.chunkId ?? source.index}`}
          runId={run.runId}
          source={source}
          flash={flash === source.index}
        />
      ))}
    </div>
  );
}

function SourceCard({
  runId,
  source,
  flash,
}: {
  runId: string;
  source: Run["sources"][number];
  flash: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const score = typeof source.score === "number" ? source.score : null;

  return (
    <div
      id={`source-${runId}-${source.index}`}
      className={`card p-2.5 ${flash ? "source-highlight" : ""}`}
    >
      <div className="flex items-center gap-2">
        <span className="mono flex size-5 items-center justify-center rounded-md border border-accent/45 bg-accent/10 text-[11px] font-bold text-accent">
          {source.index}
        </span>
        <span className="truncate text-[12px] text-ink" title={source.fileName ?? ""}>
          {source.fileName ?? "문서"}
          {typeof source.page === "number" ? ` · p.${source.page}` : ""}
        </span>
        {score !== null && (
          <span className="mono ml-auto text-[11px] text-muted" title="코사인 유사도">
            {score.toFixed(3)}
          </span>
        )}
      </div>

      {score !== null && (
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-panel3">
          <div
            className="h-full rounded-full bg-accent"
            style={{ width: `${Math.max(2, Math.min(100, score * 100))}%` }}
          />
        </div>
      )}

      {/*
        접힌 상태에서는 공백을 정리해서 읽기 쉽게 보여주고, "전문 보기"를 누르면 원문 그대로 보여준다.
        PDF에서 추출한 텍스트에 공백이 잔뜩 끼어 있는 게 정상이라는 것도 직접 볼 필요가 있다.
      */}
      <p
        className={`mono mt-2 text-[11px] leading-relaxed text-muted ${
          expanded ? "whitespace-pre-wrap" : "line-clamp-4"
        }`}
      >
        {expanded ? source.text : source.text.replace(/\s+/g, " ").trim()}
      </p>
      {source.text.length > 180 && (
        <button
          type="button"
          className="btn btn-ghost btn-xs mt-1.5"
          onClick={() => setExpanded((prev) => !prev)}
        >
          {expanded ? "접기" : "전문 보기 (원문 그대로)"}
        </button>
      )}
    </div>
  );
}

function MetricsTab({ run }: { run: Run }) {
  if (run.metrics.length === 0) {
    return <p className="text-[12px] text-subtle">아직 수집된 지표가 없습니다.</p>;
  }
  return (
    <dl className="flex flex-col gap-1">
      {run.metrics.map((metric) => (
        <div
          key={metric.key}
          className="flex items-baseline justify-between gap-3 rounded-lg bg-panel2 px-2.5 py-1.5"
        >
          <dt className="text-[12px] text-muted">{metric.label}</dt>
          <dd className="mono text-[12.5px] text-ink">{String(metric.value)}</dd>
        </div>
      ))}
      {typeof run.ms === "number" && (
        <div className="mt-1 flex items-baseline justify-between gap-3 rounded-lg border border-accent/30 bg-accent/[0.06] px-2.5 py-1.5">
          <dt className="text-[12px] text-accent">총 소요</dt>
          <dd className="mono text-[12.5px] text-accent">{run.ms.toLocaleString()}ms</dd>
        </div>
      )}
    </dl>
  );
}

function LogTab({
  run,
  showTokens,
  onToggleTokens,
}: {
  run: Run;
  showTokens: boolean;
  onToggleTokens: () => void;
}) {
  const events = showTokens ? run.events : run.events.filter((event) => event.type !== "token");
  const tokenCount = run.events.filter((event) => event.type === "token").length;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2 text-[11px] text-subtle">
        <span>
          이벤트 {run.events.length}개 (token {tokenCount}개)
        </span>
        <button type="button" className="btn btn-ghost btn-xs ml-auto" onClick={onToggleTokens}>
          token {showTokens ? "숨기기" : "표시"}
        </button>
      </div>
      <pre className="mono max-h-full overflow-x-auto rounded-lg border border-line bg-panel3 p-2 text-[10.5px] leading-relaxed whitespace-pre-wrap text-muted">
        {events.map((event) => JSON.stringify(event)).join("\n")}
      </pre>
      <p className="text-[10.5px] leading-snug text-subtle">
        백엔드가 NDJSON으로 흘려보낸 원본 이벤트입니다. 학생이 추가한 이벤트 타입도 여기 그대로 보입니다 —
        새 이벤트를 만들어 디버깅 용도로 쓸 수 있습니다.
      </p>
    </div>
  );
}

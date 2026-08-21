/**
 * 화면 상태 모델과 이벤트 → 상태 변환 로직.
 *
 * 핵심 개념은 <b>Run</b>이다. "질문 하나를 파이프라인 하나로 실행한 결과"가 Run이고,
 * 답변 텍스트뿐 아니라 단계 기록·근거·지표·원본 이벤트 로그를 모두 담는다.
 * 비교 실행을 켜면 어시스턴트 메시지 하나가 Run을 두 개 갖는다(내 파이프라인 vs 기본 RAG).
 */

import type { AgentEvent, EvalResult, SourceRef, StepEvent, StepStatus } from "./protocol";

export interface Step {
  id: string;
  label: string;
  status: StepStatus;
  ms?: number;
  detail?: string;
  hint?: string;
  file?: string;
  current?: number;
  total?: number;
}

export interface Metric {
  key: string;
  label: string;
  value: number | string;
}

export interface Notice {
  level: string;
  message: string;
}

export type RunStatus = "streaming" | "done" | "error" | "stopped";

export interface Run {
  /** 이 실행의 고유 id — 같은 파이프라인을 두 번 돌려도 구분되도록 */
  runId: string;
  pipelineId: string;
  pipelineName: string;
  tier?: string;
  text: string;
  steps: Step[];
  sources: SourceRef[];
  metrics: Metric[];
  notices: Notice[];
  events: AgentEvent[];
  status: RunStatus;
  error?: string;
  ms?: number;
  evaluation?: EvalResult;
  evaluating?: boolean;
  /** 인덱싱 진행 표시에 재사용할 때: 청크 미리보기 */
  preview?: { fileName: string; text: string };
}

export interface Message {
  id: string;
  role: "user" | "assistant";
  at: number;
  /** role === "user" */
  text?: string;
  /** role === "assistant" — 파이프라인별 실행 결과 */
  runs?: Run[];
  /** role === "assistant" — 이 답변이 대답한 질문(재생성·채점에 사용) */
  question?: string;
}

export interface Session {
  id: string;
  title: string;
  createdAt: number;
  messages: Message[];
}

export interface Settings {
  pipelineId: string;
  comparePipelineId: string;
  compare: boolean;
  topK: number;
  similarityThreshold: number;
  temperature: number;
  maxHistory: number;
  systemPrompt: string;
  features: Record<string, boolean>;
  strategy: string;
  chunkSize: number;
  overlap: number;
}

export const DEFAULT_SETTINGS: Settings = {
  pipelineId: "basic",
  comparePipelineId: "basic",
  compare: false,
  topK: 4,
  similarityThreshold: 0,
  temperature: 0.2,
  maxHistory: 4,
  systemPrompt: "",
  features: { rewrite: false, keyword: false, rerank: false, selfCheck: false },
  strategy: "TOKEN",
  chunkSize: 400,
  overlap: 80,
};

export function newId(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 9)}`;
}

export function emptyRun(pipelineId: string, pipelineName: string): Run {
  return {
    runId: newId("r"),
    pipelineId,
    pipelineName,
    text: "",
    steps: [],
    sources: [],
    metrics: [],
    notices: [],
    events: [],
    status: "streaming",
  };
}

export function newSession(): Session {
  return { id: newId("s"), title: "새 대화", createdAt: Date.now(), messages: [] };
}

/**
 * 이벤트 하나를 Run에 반영한다(불변 업데이트).
 * 알 수 없는 타입의 이벤트도 events 배열에는 쌓이므로 인스펙터 "로그" 탭에서 확인할 수 있다.
 */
export function applyEvent(run: Run, event: AgentEvent): Run {
  const next: Run = { ...run, events: [...run.events, event] };

  switch (event.type) {
    case "start": {
      const started = event as { pipelineName?: string; tier?: string };
      return { ...next, pipelineName: started.pipelineName ?? run.pipelineName, tier: started.tier };
    }
    case "token": {
      return { ...next, text: run.text + String((event as { text?: string }).text ?? "") };
    }
    case "step": {
      return { ...next, steps: mergeStep(run.steps, event as StepEvent) };
    }
    case "progress": {
      const progress = event as { id: string; label: string; current: number; total: number };
      return {
        ...next,
        steps: run.steps.map((step) =>
          step.id === progress.id
            ? { ...step, current: progress.current, total: progress.total }
            : step,
        ),
      };
    }
    case "sources": {
      return { ...next, sources: (event as { sources?: SourceRef[] }).sources ?? [] };
    }
    case "metric": {
      const metric = event as { key: string; label?: string; value: number | string };
      const without = run.metrics.filter((existing) => existing.key !== metric.key);
      return {
        ...next,
        metrics: [...without, { key: metric.key, label: metric.label ?? metric.key, value: metric.value }],
      };
    }
    case "notice": {
      const notice = event as { level?: string; message?: string };
      return {
        ...next,
        notices: [...run.notices, { level: notice.level ?? "info", message: notice.message ?? "" }],
      };
    }
    case "chunkPreview": {
      const preview = event as { fileName?: string; text?: string };
      return { ...next, preview: { fileName: preview.fileName ?? "", text: preview.text ?? "" } };
    }
    case "done": {
      return { ...next, status: "done", ms: (event as { ms?: number }).ms };
    }
    case "error": {
      return { ...next, status: "error", error: (event as { message?: string }).message ?? "알 수 없는 오류" };
    }
    default:
      return next;
  }
}

/** 같은 id의 단계는 갈아끼우고(running → done), 없으면 뒤에 붙인다. */
function mergeStep(steps: Step[], event: StepEvent): Step[] {
  const step: Step = {
    id: event.id,
    label: event.label,
    status: event.status,
    ms: event.ms,
    detail: event.detail,
    hint: event.hint,
    file: event.file,
  };
  const index = steps.findIndex((existing) => existing.id === event.id);
  if (index < 0) return [...steps, step];
  const copy = [...steps];
  copy[index] = { ...copy[index], ...step };
  return copy;
}

/** 스트림이 끝났는데 done 이벤트가 안 온 경우(중단 등)의 마무리 처리. */
export function finishRun(run: Run, status: RunStatus, error?: string): Run {
  if (run.status !== "streaming") return run;
  return {
    ...run,
    status,
    error: error ?? run.error,
    steps: run.steps.map((step) => (step.status === "running" ? { ...step, status: "error" } : step)),
  };
}

export function metricValue(run: Run, key: string): number | string | undefined {
  return run.metrics.find((metric) => metric.key === key)?.value;
}

/** 대화 기록을 백엔드에 보낼 형태로 (직전 답변은 실행된 첫 Run의 텍스트를 사용). */
export function toHistory(messages: Message[]): { role: "user" | "assistant"; text: string }[] {
  const history: { role: "user" | "assistant"; text: string }[] = [];
  for (const message of messages) {
    if (message.role === "user" && message.text) {
      history.push({ role: "user", text: message.text });
    }
    if (message.role === "assistant") {
      const primary = message.runs?.[0];
      if (primary?.text) history.push({ role: "assistant", text: primary.text });
    }
  }
  return history;
}

export function sessionTitle(question: string): string {
  const trimmed = question.trim().replace(/\s+/g, " ");
  return trimmed.length > 24 ? `${trimmed.slice(0, 24)}…` : trimmed || "새 대화";
}

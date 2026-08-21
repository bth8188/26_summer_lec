/**
 * 백엔드가 흘려보내는 스트림 이벤트의 타입 정의.
 * 백엔드의 `AgentEvent.java`와 1:1로 대응한다 — 한쪽을 고치면 다른 쪽도 고쳐야 한다.
 *
 * 스트림 형식은 NDJSON: 한 줄에 JSON 객체 하나.
 *   {"type":"step","id":"retrieve","label":"벡터 검색","status":"running"}
 *   {"type":"token","text":"제주"}
 */

export type StepStatus = "running" | "done" | "todo" | "error";

export interface SourceRef {
  index: number;
  chunkId?: string;
  docId?: string;
  fileName?: string;
  page?: number | null;
  score?: number | null;
  text: string;
}

export interface IndexedDocument {
  docId: string;
  fileName: string;
  type: string;
  pageCount: number;
  chunkCount: number;
  charCount: number;
  strategy: string;
  chunkSize: number;
  overlap: number;
  indexedAt: number;
  embedMs: number;
}

export interface StartEvent {
  type: "start";
  pipelineId: string;
  pipelineName: string;
  tier?: string;
}

export interface StepEvent {
  type: "step";
  id: string;
  label: string;
  status: StepStatus;
  ms?: number;
  detail?: string;
  /** status가 "todo"일 때: 무엇을 구현하면 되는지 */
  hint?: string;
  /** status가 "todo"일 때: 구현할 파일/메서드 위치 */
  file?: string;
}

export interface ProgressEvent {
  type: "progress";
  id: string;
  label: string;
  current: number;
  total: number;
}

export interface SourcesEvent {
  type: "sources";
  sources: SourceRef[];
}

export interface TokenEvent {
  type: "token";
  text: string;
}

export interface MetricEvent {
  type: "metric";
  key: string;
  label?: string;
  value: number | string;
}

export interface NoticeEvent {
  type: "notice";
  level: "info" | "warn" | "error";
  message: string;
}

export interface DocumentEvent {
  type: "document";
  document: IndexedDocument;
}

export interface ChunkPreviewEvent {
  type: "chunkPreview";
  fileName: string;
  text: string;
}

export interface DoneEvent {
  type: "done";
  ms: number;
}

export interface ErrorEvent {
  type: "error";
  message: string;
}

export type KnownEvent =
  | StartEvent
  | StepEvent
  | ProgressEvent
  | SourcesEvent
  | TokenEvent
  | MetricEvent
  | NoticeEvent
  | DocumentEvent
  | ChunkPreviewEvent
  | DoneEvent
  | ErrorEvent;

/**
 * 학생이 백엔드에서 `AgentEvent.of("myEvent").with(...)`로 새 이벤트를 만들어 보내도
 * 프론트가 죽지 않도록 열어둔 타입. 알 수 없는 타입은 인스펙터의 "로그" 탭에만 표시된다.
 */
export type AgentEvent = KnownEvent | ({ type: string } & Record<string, unknown>);

export interface PipelineInfo {
  id: string;
  name: string;
  tier: string;
  description: string;
  supportedFeatures: string[];
}

export interface StrategyInfo {
  id: string;
  label: string;
  description: string;
}

export interface Health {
  status: string;
  chatModel: string;
  embeddingModel: string;
  ollamaUp: boolean;
  chatModelReady: boolean;
  embeddingReady: boolean;
  documents: number;
  chunks: number;
}

export interface KnowledgeSnapshot {
  documents: IndexedDocument[];
  totalChunks: number;
}

export interface EvalResult {
  faithfulness: number | null;
  relevancy: number | null;
  reason: string | null;
  raw: string;
}

/** 기능 토글 — 백엔드 RagOptions의 FEATURE_* 상수와 같은 키를 쓴다. */
export const FEATURES = [
  {
    key: "rewrite",
    label: "질문 재작성",
    tier: "실버",
    hint: "짧고 모호한 질문을 검색용 질문으로 다시 쓴다(multi-query).",
  },
  {
    key: "keyword",
    label: "키워드 검색(하이브리드)",
    tier: "실버",
    hint: "벡터 검색이 놓치는 고유명사·조항 번호를 단어 매칭으로 보완한다.",
  },
  {
    key: "rerank",
    label: "재정렬(rerank)",
    tier: "실버",
    hint: "넓게 검색한 뒤 LLM으로 재채점해 상위만 남긴다. 느려지는 대신 정확해진다.",
  },
  {
    key: "selfCheck",
    label: "자기 검증",
    tier: "골드",
    hint: "생성된 답변이 근거에 실제로 있는지 스스로 확인한다.",
  },
] as const;

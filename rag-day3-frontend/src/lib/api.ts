/**
 * 백엔드(rag-day3-demo) 호출 모음. Next.js API 라우트를 두지 않고 브라우저에서 직접 호출한다
 * — 그래서 백엔드에 CORS 설정이 필요하다(rag-day3-demo/WebConfig.java).
 */

import type {
  AgentEvent,
  EvalResult,
  Health,
  KnowledgeSnapshot,
  PipelineInfo,
  SourceRef,
  StrategyInfo,
} from "./protocol";

export const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8081";

async function getJson<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, { signal });
  if (!response.ok) {
    throw new Error(`${path} 실패 (HTTP ${response.status})`);
  }
  return (await response.json()) as T;
}

/**
 * NDJSON 스트림을 이벤트 하나씩 뱉는 async generator.
 *
 * 청크 경계는 줄 단위와 일치하지 않는다(한 줄이 두 청크에 걸쳐 도착할 수 있다) —
 * 그래서 버퍼에 모아두고 개행이 나올 때마다 잘라서 파싱한다. 스트리밍 처리의 기본 패턴.
 */
async function* streamNdjson(path: string, init: RequestInit): AsyncGenerator<AgentEvent> {
  const response = await fetch(`${API_BASE}${path}`, init);
  if (!response.ok || !response.body) {
    throw new Error(`${path} 실패 (HTTP ${response.status})`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let newline = buffer.indexOf("\n");
    while (newline >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) yield JSON.parse(line) as AgentEvent;
      newline = buffer.indexOf("\n");
    }
  }
  const rest = buffer.trim();
  if (rest) yield JSON.parse(rest) as AgentEvent;
}

export interface ChatRequestBody {
  question: string;
  pipelineId: string;
  history: { role: "user" | "assistant"; text: string }[];
  docIds: string[];
  options: {
    topK: number;
    similarityThreshold: number;
    temperature: number;
    maxHistory: number;
    systemPrompt?: string;
    features: Record<string, boolean>;
  };
}

export function streamChat(body: ChatRequestBody, signal: AbortSignal) {
  return streamNdjson("/api/chat", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
}

export interface IndexParams {
  strategy: string;
  chunkSize: number;
  overlap: number;
}

export function streamIndex(files: File[], params: IndexParams, signal: AbortSignal) {
  const formData = new FormData();
  files.forEach((file) => formData.append("files", file));
  formData.append("strategy", params.strategy);
  formData.append("chunkSize", String(params.chunkSize));
  formData.append("overlap", String(params.overlap));
  return streamNdjson("/api/index", { method: "POST", body: formData, signal });
}

export const getHealth = (signal?: AbortSignal) => getJson<Health>("/api/health", signal);
export const getPipelines = (signal?: AbortSignal) => getJson<PipelineInfo[]>("/api/pipelines", signal);
export const getStrategies = (signal?: AbortSignal) =>
  getJson<StrategyInfo[]>("/api/knowledge/strategies", signal);
export const getKnowledge = (signal?: AbortSignal) =>
  getJson<KnowledgeSnapshot>("/api/knowledge", signal);

export async function deleteDocument(docId: string) {
  const response = await fetch(`${API_BASE}/api/knowledge/${docId}`, { method: "DELETE" });
  if (!response.ok) throw new Error(`문서 삭제 실패 (HTTP ${response.status})`);
}

export async function clearKnowledge() {
  const response = await fetch(`${API_BASE}/api/knowledge`, { method: "DELETE" });
  if (!response.ok) throw new Error(`초기화 실패 (HTTP ${response.status})`);
}

export async function evaluateAnswer(payload: {
  question: string;
  answer: string;
  sources: SourceRef[];
}): Promise<EvalResult> {
  const response = await fetch(`${API_BASE}/api/evaluate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!response.ok) throw new Error(`채점 실패 (HTTP ${response.status})`);
  return (await response.json()) as EvalResult;
}

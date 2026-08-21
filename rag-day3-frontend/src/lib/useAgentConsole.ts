"use client";

/**
 * 콘솔 전체의 상태를 한 곳에 모아둔 훅. 화면 컴포넌트들은 여기서 나온 값만 그린다.
 *
 * 들어 있는 것: 대화 세션 / 스트리밍 실행(Run) / 지식 베이스 / 파이프라인 목록 /
 * 백엔드·Ollama 상태 / 설정(검색 옵션·기능 토글) / 테마.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import * as api from "./api";
import type {
  Health,
  IndexedDocument,
  PipelineInfo,
  StrategyInfo,
} from "./protocol";
import {
  applyEvent,
  DEFAULT_SETTINGS,
  emptyRun,
  finishRun,
  newId,
  newSession,
  sessionTitle,
  toHistory,
  type Message,
  type Run,
  type Session,
  type Settings,
} from "./state";

const STORAGE_SESSIONS = "rag-day3.sessions";
const STORAGE_SETTINGS = "rag-day3.settings";
const STORAGE_THEME = "rag-day3.theme";
const HEALTH_INTERVAL_MS = 15_000;

export type Theme = "dark" | "light";

export interface InspectorTarget {
  messageId: string;
  runId: string;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

export function useAgentConsole() {
  const [hydrated, setHydrated] = useState(false);
  const [theme, setTheme] = useState<Theme>("dark");

  const [sessions, setSessions] = useState<Session[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string>("");
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);

  const [pipelines, setPipelines] = useState<PipelineInfo[]>([]);
  const [strategies, setStrategies] = useState<StrategyInfo[]>([]);
  const [health, setHealth] = useState<Health | null>(null);
  const [backendError, setBackendError] = useState<string | null>(null);

  const [documents, setDocuments] = useState<IndexedDocument[]>([]);
  const [totalChunks, setTotalChunks] = useState(0);
  const [selectedDocIds, setSelectedDocIds] = useState<string[]>([]);

  const [indexRun, setIndexRun] = useState<Run | null>(null);
  const [indexing, setIndexing] = useState(false);

  const [busy, setBusy] = useState(false);
  const [inspectorTarget, setInspectorTarget] = useState<InspectorTarget | null>(null);

  const chatAbort = useRef<AbortController | null>(null);
  const indexAbort = useRef<AbortController | null>(null);
  // setState 안에서 최신 세션을 읽어야 하는 경우(대화 기록 조립)를 위한 거울
  const sessionsRef = useRef<Session[]>([]);
  sessionsRef.current = sessions;

  // ------------------------------------------------------------ 초기화 / 저장

  useEffect(() => {
    try {
      const savedSessions = localStorage.getItem(STORAGE_SESSIONS);
      const parsed: Session[] = savedSessions ? JSON.parse(savedSessions) : [];
      const restored = parsed.length > 0 ? parsed : [newSession()];
      setSessions(restored);
      setActiveSessionId(restored[0].id);

      const savedSettings = localStorage.getItem(STORAGE_SETTINGS);
      if (savedSettings) {
        setSettings({ ...DEFAULT_SETTINGS, ...(JSON.parse(savedSettings) as Partial<Settings>) });
      }
      const savedTheme = localStorage.getItem(STORAGE_THEME) as Theme | null;
      if (savedTheme === "light" || savedTheme === "dark") setTheme(savedTheme);
    } catch {
      const fresh = newSession();
      setSessions([fresh]);
      setActiveSessionId(fresh.id);
    }
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (!hydrated) return;
    // 스트리밍 중에는 매 토큰마다 저장하지 않는다(불필요한 직렬화 비용)
    if (busy) return;
    try {
      localStorage.setItem(STORAGE_SESSIONS, JSON.stringify(sessions.slice(0, 20)));
    } catch {
      /* 용량 초과 등은 무시 */
    }
  }, [sessions, hydrated, busy]);

  useEffect(() => {
    if (!hydrated) return;
    localStorage.setItem(STORAGE_SETTINGS, JSON.stringify(settings));
  }, [settings, hydrated]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    if (hydrated) localStorage.setItem(STORAGE_THEME, theme);
  }, [theme, hydrated]);

  // ------------------------------------------------------------ 백엔드 상태

  const refreshHealth = useCallback(async () => {
    try {
      const result = await api.getHealth();
      setHealth(result);
      setBackendError(null);
    } catch (error) {
      setHealth(null);
      setBackendError(errorMessage(error));
    }
  }, []);

  const refreshKnowledge = useCallback(async () => {
    try {
      const snapshot = await api.getKnowledge();
      setDocuments(snapshot.documents);
      setTotalChunks(snapshot.totalChunks);
    } catch {
      /* 백엔드가 죽어 있으면 health 쪽에서 이미 알려준다 */
    }
  }, []);

  useEffect(() => {
    refreshHealth();
    refreshKnowledge();
    api.getPipelines().then(setPipelines).catch(() => setPipelines([]));
    api.getStrategies().then(setStrategies).catch(() => setStrategies([]));
    const timer = setInterval(refreshHealth, HEALTH_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [refreshHealth, refreshKnowledge]);

  // ------------------------------------------------------------ 세션

  const activeSession = useMemo(
    () => sessions.find((session) => session.id === activeSessionId) ?? sessions[0],
    [sessions, activeSessionId],
  );

  const newChat = useCallback(() => {
    const session = newSession();
    setSessions((prev) => [session, ...prev]);
    setActiveSessionId(session.id);
    setInspectorTarget(null);
  }, []);

  const deleteSession = useCallback((sessionId: string) => {
    setSessions((prev) => {
      const remaining = prev.filter((session) => session.id !== sessionId);
      const next = remaining.length > 0 ? remaining : [newSession()];
      setActiveSessionId((current) => (current === sessionId ? next[0].id : current));
      return next;
    });
  }, []);

  // ------------------------------------------------------------ 실행

  const updateRun = useCallback(
    (sessionId: string, messageId: string, runId: string, updater: (run: Run) => Run) => {
      setSessions((prev) =>
        prev.map((session) =>
          session.id !== sessionId
            ? session
            : {
                ...session,
                messages: session.messages.map((message) =>
                  message.id !== messageId
                    ? message
                    : {
                        ...message,
                        runs: message.runs?.map((run) => (run.runId === runId ? updater(run) : run)),
                      },
                ),
              },
        ),
      );
    },
    [],
  );

  /** 이번 실행에 쓸 파이프라인 목록 (비교 실행이면 두 개, 중복이면 하나). */
  const runTargets = useCallback((): { id: string; name: string }[] => {
    const find = (id: string) => pipelines.find((pipeline) => pipeline.id === id);
    const primary = find(settings.pipelineId) ?? { id: settings.pipelineId, name: settings.pipelineId };
    const targets = [{ id: primary.id, name: primary.name }];
    if (settings.compare && settings.comparePipelineId !== settings.pipelineId) {
      const secondary = find(settings.comparePipelineId);
      if (secondary) targets.push({ id: secondary.id, name: secondary.name });
    }
    return targets;
  }, [pipelines, settings.compare, settings.comparePipelineId, settings.pipelineId]);

  const streamRuns = useCallback(
    async (
      sessionId: string,
      messageId: string,
      question: string,
      history: { role: "user" | "assistant"; text: string }[],
      runs: Run[],
    ) => {
      const controller = new AbortController();
      chatAbort.current = controller;
      setBusy(true);
      setInspectorTarget({ messageId, runId: runs[0].runId });

      await Promise.all(
        runs.map(async (run) => {
          try {
            const stream = api.streamChat(
              {
                question,
                pipelineId: run.pipelineId,
                history,
                docIds: selectedDocIds,
                options: {
                  topK: settings.topK,
                  similarityThreshold: settings.similarityThreshold,
                  temperature: settings.temperature,
                  maxHistory: settings.maxHistory,
                  systemPrompt: settings.systemPrompt || undefined,
                  features: settings.features,
                },
              },
              controller.signal,
            );
            for await (const event of stream) {
              updateRun(sessionId, messageId, run.runId, (current) => applyEvent(current, event));
            }
            updateRun(sessionId, messageId, run.runId, (current) => finishRun(current, "done"));
          } catch (error) {
            const aborted = controller.signal.aborted;
            updateRun(sessionId, messageId, run.runId, (current) =>
              finishRun(current, aborted ? "stopped" : "error", aborted ? undefined : errorMessage(error)),
            );
          }
        }),
      );

      chatAbort.current = null;
      setBusy(false);
    },
    [selectedDocIds, settings, updateRun],
  );

  const send = useCallback(
    async (question: string) => {
      const trimmed = question.trim();
      if (!trimmed || busy) return;

      const sessionId = activeSession?.id ?? activeSessionId;
      const current = sessionsRef.current.find((session) => session.id === sessionId);
      const history = toHistory(current?.messages ?? []);

      const userMessage: Message = { id: newId("m"), role: "user", text: trimmed, at: Date.now() };
      const runs = runTargets().map((target) => emptyRun(target.id, target.name));
      const assistantMessage: Message = {
        id: newId("m"),
        role: "assistant",
        at: Date.now(),
        question: trimmed,
        runs,
      };

      setSessions((prev) =>
        prev.map((session) =>
          session.id !== sessionId
            ? session
            : {
                ...session,
                title: session.messages.length === 0 ? sessionTitle(trimmed) : session.title,
                messages: [...session.messages, userMessage, assistantMessage],
              },
        ),
      );

      await streamRuns(sessionId, assistantMessage.id, trimmed, history, runs);
    },
    [activeSession?.id, activeSessionId, busy, runTargets, streamRuns],
  );

  /** 같은 질문을 현재 설정으로 다시 실행 (설정을 바꿔가며 비교할 때 사용). */
  const regenerate = useCallback(
    async (messageId: string) => {
      if (busy) return;
      const sessionId = activeSession?.id ?? activeSessionId;
      const session = sessionsRef.current.find((item) => item.id === sessionId);
      if (!session) return;
      const index = session.messages.findIndex((message) => message.id === messageId);
      if (index < 0) return;
      const target = session.messages[index];
      const question = target.question ?? "";
      if (!question) return;

      // 재생성 대상 앞까지의 대화만 기록으로 사용 (자기 자신과 직전 질문은 제외)
      const history = toHistory(session.messages.slice(0, Math.max(0, index - 1)));
      const runs = runTargets().map((item) => emptyRun(item.id, item.name));

      setSessions((prev) =>
        prev.map((item) =>
          item.id !== sessionId
            ? item
            : {
                ...item,
                messages: item.messages.map((message) =>
                  message.id === messageId ? { ...message, runs, at: Date.now() } : message,
                ),
              },
        ),
      );

      await streamRuns(sessionId, messageId, question, history, runs);
    },
    [activeSession?.id, activeSessionId, busy, runTargets, streamRuns],
  );

  const stop = useCallback(() => {
    chatAbort.current?.abort();
    indexAbort.current?.abort();
  }, []);

  // ------------------------------------------------------------ 채점(골드)

  const evaluate = useCallback(
    async (messageId: string, runId: string) => {
      const sessionId = activeSession?.id ?? activeSessionId;
      const session = sessionsRef.current.find((item) => item.id === sessionId);
      const message = session?.messages.find((item) => item.id === messageId);
      const run = message?.runs?.find((item) => item.runId === runId);
      if (!message || !run || !run.text) return;

      updateRun(sessionId, messageId, runId, (current) => ({ ...current, evaluating: true }));
      try {
        const result = await api.evaluateAnswer({
          question: message.question ?? "",
          answer: run.text,
          sources: run.sources,
        });
        updateRun(sessionId, messageId, runId, (current) => ({
          ...current,
          evaluating: false,
          evaluation: result,
        }));
      } catch (error) {
        updateRun(sessionId, messageId, runId, (current) => ({
          ...current,
          evaluating: false,
          notices: [...current.notices, { level: "error", message: `채점 실패: ${errorMessage(error)}` }],
        }));
      }
    },
    [activeSession?.id, activeSessionId, updateRun],
  );

  // ------------------------------------------------------------ 인덱싱

  const indexFiles = useCallback(
    async (files: File[]) => {
      if (files.length === 0 || indexing) return;
      const controller = new AbortController();
      indexAbort.current = controller;
      setIndexing(true);

      let run = emptyRun("index", "인덱싱");
      setIndexRun(run);
      try {
        const stream = api.streamIndex(
          files,
          { strategy: settings.strategy, chunkSize: settings.chunkSize, overlap: settings.overlap },
          controller.signal,
        );
        for await (const event of stream) {
          run = applyEvent(run, event);
          setIndexRun(run);
        }
        run = finishRun(run, "done");
      } catch (error) {
        const aborted = controller.signal.aborted;
        run = finishRun(run, aborted ? "stopped" : "error", aborted ? undefined : errorMessage(error));
      }
      setIndexRun(run);
      setIndexing(false);
      indexAbort.current = null;
      await refreshKnowledge();
      await refreshHealth();
    },
    [indexing, refreshHealth, refreshKnowledge, settings.chunkSize, settings.overlap, settings.strategy],
  );

  const removeDocument = useCallback(
    async (docId: string) => {
      await api.deleteDocument(docId);
      setSelectedDocIds((prev) => prev.filter((id) => id !== docId));
      await refreshKnowledge();
    },
    [refreshKnowledge],
  );

  const clearKnowledge = useCallback(async () => {
    await api.clearKnowledge();
    setSelectedDocIds([]);
    setIndexRun(null);
    await refreshKnowledge();
  }, [refreshKnowledge]);

  const toggleDocSelection = useCallback((docId: string) => {
    setSelectedDocIds((prev) =>
      prev.includes(docId) ? prev.filter((id) => id !== docId) : [...prev, docId],
    );
  }, []);

  // ------------------------------------------------------------ 파생 값

  const selectedPipeline = useMemo(
    () => pipelines.find((pipeline) => pipeline.id === settings.pipelineId) ?? null,
    [pipelines, settings.pipelineId],
  );

  /** 인스펙터에 보여줄 실행 — 명시적으로 고른 게 없으면 마지막 어시스턴트 실행. */
  const inspected = useMemo<{ message: Message; run: Run } | null>(() => {
    const messages = activeSession?.messages ?? [];
    if (inspectorTarget) {
      const message = messages.find((item) => item.id === inspectorTarget.messageId);
      const run = message?.runs?.find((item) => item.runId === inspectorTarget.runId);
      if (message && run) return { message, run };
    }
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const message = messages[index];
      if (message.role === "assistant" && message.runs?.length) {
        return { message, run: message.runs[0] };
      }
    }
    return null;
  }, [activeSession, inspectorTarget]);

  const updateSettings = useCallback((patch: Partial<Settings>) => {
    setSettings((prev) => ({ ...prev, ...patch }));
  }, []);

  const toggleFeature = useCallback((feature: string) => {
    setSettings((prev) => ({
      ...prev,
      features: { ...prev.features, [feature]: !prev.features[feature] },
    }));
  }, []);

  return {
    hydrated,
    theme,
    setTheme,
    sessions,
    activeSession,
    activeSessionId,
    setActiveSessionId,
    newChat,
    deleteSession,
    settings,
    updateSettings,
    toggleFeature,
    pipelines,
    selectedPipeline,
    strategies,
    health,
    backendError,
    refreshHealth,
    documents,
    totalChunks,
    selectedDocIds,
    toggleDocSelection,
    indexFiles,
    indexRun,
    indexing,
    removeDocument,
    clearKnowledge,
    busy,
    send,
    regenerate,
    stop,
    evaluate,
    inspected,
    inspectorTarget,
    setInspectorTarget,
  };
}

export type AgentConsole = ReturnType<typeof useAgentConsole>;

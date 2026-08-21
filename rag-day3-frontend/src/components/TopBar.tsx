"use client";

/** 상단 바: 상태 표시등(백엔드/Ollama/모델/지식베이스) + 파이프라인 선택 + 패널 토글. */

import type { AgentConsole } from "@/lib/useAgentConsole";
import { Badge, Icon, StatusDot, tierLabel } from "./ui";

export function TopBar({
  console: agent,
  onOpenSettings,
  onToggleSidebar,
  onToggleInspector,
}: {
  console: AgentConsole;
  onOpenSettings: () => void;
  onToggleSidebar: () => void;
  onToggleInspector: () => void;
}) {
  const { health, backendError, settings, updateSettings, pipelines, theme, setTheme, totalChunks } = agent;

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line bg-panel/70 px-3 backdrop-blur">
      <button
        type="button"
        className="btn btn-ghost btn-xs lg:hidden"
        onClick={onToggleSidebar}
        title="사이드바"
      >
        <Icon name="panelLeft" />
      </button>

      <div className="flex items-center gap-2.5">
        <span className="flex size-7 items-center justify-center rounded-lg border border-accent/40 bg-accent/10">
          <Icon name="bolt" className="text-accent" />
        </span>
        <div className="leading-tight">
          <h1 className="text-[13.5px] font-semibold text-ink">RAG 에이전트 콘솔</h1>
          <p className="text-[10.5px] text-subtle">Day3 캡스톤 · 검색과 생성을 직접 조립하는 작업 화면</p>
        </div>
      </div>

      {/* 상태 표시등 */}
      <div className="ml-2 hidden items-center gap-1.5 md:flex">
        {backendError ? (
          <span className="chip" title={backendError}>
            <StatusDot state="err" />
            백엔드 응답 없음
          </span>
        ) : (
          <>
            <span className="chip">
              <StatusDot state={health?.ollamaUp ? "ok" : "err"} />
              Ollama
            </span>
            <span className="chip mono" title="채팅 모델">
              <StatusDot state={health?.chatModelReady ? "ok" : "warn"} />
              {health?.chatModel ?? "-"}
            </span>
            <span className="chip mono" title="임베딩 모델">
              <StatusDot state={health?.embeddingReady ? "ok" : "warn"} />
              {health?.embeddingModel ?? "-"}
            </span>
            <span className="chip">
              문서 {health?.documents ?? 0} · 청크 {health?.chunks ?? totalChunks}
            </span>
          </>
        )}
      </div>

      <div className="ml-auto flex items-center gap-2">
        <label className="hidden items-center gap-2 sm:flex">
          <span className="text-[11px] whitespace-nowrap text-subtle">파이프라인</span>
          <select
            className="select w-auto py-1.5 text-[12px]"
            value={settings.pipelineId}
            onChange={(event) => updateSettings({ pipelineId: event.target.value })}
          >
            {pipelines.map((pipeline) => (
              <option key={pipeline.id} value={pipeline.id}>
                {pipeline.name} · {tierLabel(pipeline.tier)}
              </option>
            ))}
          </select>
        </label>

        {settings.compare && <Badge tone="violet">비교</Badge>}

        <button type="button" className="btn btn-ghost btn-xs" onClick={onOpenSettings} title="실행 설정">
          <Icon name="gear" />
          설정
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-xs"
          onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
          title="테마 전환"
        >
          <Icon name={theme === "dark" ? "sun" : "moon"} />
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-xs"
          onClick={onToggleInspector}
          title="인스펙터 토글"
        >
          <Icon name="panelRight" />
        </button>
      </div>
    </header>
  );
}

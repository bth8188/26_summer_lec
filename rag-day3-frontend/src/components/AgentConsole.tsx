"use client";

/**
 * 전체 레이아웃 조립. 좌: 세션+지식베이스 / 중앙: 대화 / 우: 인스펙터.
 * 상태는 전부 useAgentConsole 훅에 있고, 이 컴포넌트는 배치와 패널 열림 여부만 관리한다.
 */

import { useState } from "react";

import { useAgentConsole } from "@/lib/useAgentConsole";
import { ChatPanel } from "./ChatPanel";
import { InspectorPanel } from "./InspectorPanel";
import { KnowledgePanel } from "./KnowledgePanel";
import { SettingsPanel } from "./SettingsPanel";
import { TopBar } from "./TopBar";
import { Icon, SectionLabel, Spinner } from "./ui";

export function AgentConsole() {
  const agent = useAgentConsole();
  const [showSettings, setShowSettings] = useState(false);
  const [showSidebar, setShowSidebar] = useState(true);
  const [showInspector, setShowInspector] = useState(true);
  const [focus, setFocus] = useState<{ runId: string; index: number } | null>(null);

  if (!agent.hydrated) {
    return (
      <div className="flex min-h-screen items-center justify-center gap-2 text-[13px] text-muted">
        <Spinner /> 콘솔 준비 중…
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col">
      <TopBar
        console={agent}
        onOpenSettings={() => setShowSettings(true)}
        onToggleSidebar={() => setShowSidebar((prev) => !prev)}
        onToggleInspector={() => setShowInspector((prev) => !prev)}
      />

      {agent.backendError && (
        <div className="flex items-center gap-2 border-b border-err/40 bg-err/[0.08] px-4 py-2 text-[12px] text-err">
          <Icon name="warn" />
          <span>
            백엔드({agent.backendError})에 연결할 수 없습니다. <span className="mono">rag-day3-demo</span> 에서{" "}
            <span className="mono">./run.sh</span> (Windows는 <span className="mono">run.bat</span>) 를 먼저
            실행하세요.
          </span>
          <button type="button" className="btn btn-xs ml-auto" onClick={() => void agent.refreshHealth()}>
            <Icon name="refresh" />
            다시 확인
          </button>
        </div>
      )}

      <div className="flex min-h-0 flex-1">
        {/* 왼쪽 */}
        {showSidebar && (
          <aside className="hidden w-[290px] shrink-0 flex-col gap-4 overflow-y-auto border-r border-line bg-panel/40 px-3 py-4 lg:flex">
            <section>
              <SectionLabel
                action={
                  <button type="button" className="btn btn-ghost btn-xs" onClick={agent.newChat}>
                    <Icon name="plus" />
                    새 대화
                  </button>
                }
              >
                대화
              </SectionLabel>
              <div className="flex flex-col gap-1">
                {agent.sessions.map((session) => {
                  const active = session.id === agent.activeSession?.id;
                  return (
                    <div
                      key={session.id}
                      className={`group flex items-center gap-1.5 rounded-lg px-2.5 py-2 transition-colors ${
                        active ? "bg-accent/[0.09] text-ink" : "text-muted hover:bg-panel3"
                      }`}
                    >
                      <button
                        type="button"
                        className="min-w-0 flex-1 text-left"
                        onClick={() => agent.setActiveSessionId(session.id)}
                      >
                        <span className="block truncate text-[12.5px]">{session.title}</span>
                        <span className="mono block text-[10px] text-subtle">
                          {session.messages.filter((message) => message.role === "user").length}개 질문
                        </span>
                      </button>
                      <button
                        type="button"
                        className="btn btn-ghost btn-xs opacity-0 group-hover:opacity-100"
                        title="대화 삭제"
                        onClick={() => agent.deleteSession(session.id)}
                      >
                        <Icon name="trash" />
                      </button>
                    </div>
                  );
                })}
              </div>
            </section>

            <KnowledgePanel console={agent} />
          </aside>
        )}

        {/* 가운데 */}
        <ChatPanel
          console={agent}
          onCitation={(messageId, runId, index) => {
            agent.setInspectorTarget({ messageId, runId });
            setShowInspector(true);
            setFocus({ runId, index });
          }}
        />

        {/* 오른쪽 */}
        {showInspector && (
          <aside className="hidden w-[380px] shrink-0 flex-col border-l border-line bg-panel/40 lg:flex">
            <InspectorPanel
              inspected={agent.inspected}
              focus={focus}
              onClose={() => setShowInspector(false)}
            />
          </aside>
        )}
      </div>

      {showSettings && <SettingsPanel console={agent} onClose={() => setShowSettings(false)} />}
    </div>
  );
}

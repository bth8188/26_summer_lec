"use client";

/**
 * 왼쪽 아래: 지식 베이스 패널.
 * 업로드 → 인덱싱 진행(단계별) → 문서 목록(검색 대상 선택/삭제) → 청킹 설정.
 */

import { useRef, useState } from "react";

import type { AgentConsole } from "@/lib/useAgentConsole";
import { StepTrace } from "./StepTrace";
import { Badge, Icon, SectionLabel, SliderField, Spinner } from "./ui";

const ACCEPT = ".pdf,.txt,.md,.markdown";

export function KnowledgePanel({ console: agent }: { console: AgentConsole }) {
  const {
    documents,
    totalChunks,
    selectedDocIds,
    toggleDocSelection,
    indexFiles,
    indexRun,
    indexing,
    removeDocument,
    clearKnowledge,
    settings,
    updateSettings,
    strategies,
  } = agent;

  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [showChunking, setShowChunking] = useState(false);
  const strategyInfo = strategies.find((strategy) => strategy.id === settings.strategy);

  const pick = (fileList: FileList | null) => {
    if (!fileList || fileList.length === 0) return;
    void indexFiles(Array.from(fileList));
  };

  return (
    <div className="flex min-h-0 flex-col gap-3">
      <SectionLabel
        action={
          documents.length > 0 ? (
            <button
              type="button"
              className="btn btn-ghost btn-xs"
              onClick={() => {
                if (confirm("인덱싱된 문서를 모두 삭제할까요?")) void clearKnowledge();
              }}
            >
              전체 초기화
            </button>
          ) : undefined
        }
      >
        지식 베이스
      </SectionLabel>

      {/* 업로드 영역 */}
      <div
        onDragOver={(event) => {
          event.preventDefault();
          setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          pick(event.dataTransfer.files);
        }}
        onClick={() => inputRef.current?.click()}
        className={`cursor-pointer rounded-xl border border-dashed px-3 py-4 text-center transition-colors ${
          dragging ? "border-accent bg-accent/[0.07]" : "border-line-strong bg-panel2 hover:border-accent/50"
        }`}
      >
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT}
          multiple
          hidden
          onChange={(event) => {
            pick(event.target.files);
            event.target.value = "";
          }}
        />
        {indexing ? (
          <span className="flex items-center justify-center gap-2 text-[12.5px] text-accent">
            <Spinner /> 인덱싱 중…
          </span>
        ) : (
          <>
            <Icon name="upload" className="mx-auto mb-1.5 size-5 text-muted" />
            <p className="text-[12.5px] font-medium text-ink">파일을 끌어다 놓기</p>
            <p className="mt-0.5 text-[11px] text-subtle">PDF · txt · md (여러 개 동시 가능)</p>
          </>
        )}
      </div>

      {/* 인덱싱 진행 상황 */}
      {indexRun && (indexing || indexRun.steps.length > 0) && (
        <div className="card p-2.5">
          <div className="mb-2 flex items-center justify-between">
            <span className="text-[11.5px] font-medium text-muted">인덱싱 파이프라인</span>
            {indexRun.status === "done" && <Badge tone="ok">완료</Badge>}
            {indexRun.status === "error" && <Badge tone="err">실패</Badge>}
          </div>
          <StepTrace steps={indexRun.steps} />
          {indexRun.error && <p className="mt-2 text-[11.5px] text-err">{indexRun.error}</p>}
          {indexRun.notices.map((notice, index) => (
            <p key={index} className="mt-2 text-[11.5px] leading-snug text-warn">
              {notice.message}
            </p>
          ))}
          {indexRun.preview && (
            <details className="mt-2">
              <summary className="cursor-pointer text-[11.5px] text-muted">
                첫 청크 미리보기 — 청킹이 어떻게 잘렸는지 확인
              </summary>
              <pre className="mono mt-1.5 max-h-32 overflow-auto rounded-md border border-line bg-panel3 p-2 text-[10.5px] whitespace-pre-wrap text-subtle">
                {indexRun.preview.text}
              </pre>
            </details>
          )}
        </div>
      )}

      {/* 문서 목록 */}
      {documents.length > 0 && (
        <div className="flex min-h-0 flex-col gap-1.5">
          <div className="flex items-center justify-between px-1">
            <span className="text-[11px] text-subtle">
              문서 {documents.length}개 · 청크 {totalChunks}개
            </span>
            {selectedDocIds.length > 0 && (
              <button
                type="button"
                className="btn btn-ghost btn-xs"
                onClick={() => selectedDocIds.forEach(toggleDocSelection)}
              >
                선택 해제
              </button>
            )}
          </div>

          {documents.map((document) => {
            const selected = selectedDocIds.includes(document.docId);
            return (
              <div
                key={document.docId}
                className={`card group flex items-start gap-2 px-2.5 py-2 ${
                  selected ? "border-accent/50 bg-accent/[0.05]" : ""
                }`}
              >
                <button
                  type="button"
                  onClick={() => toggleDocSelection(document.docId)}
                  title="이 문서만 검색 대상으로 삼기"
                  className={`mono mt-0.5 flex size-4 shrink-0 items-center justify-center rounded border text-[9px] ${
                    selected ? "border-accent bg-accent text-[#05201c]" : "border-line-strong text-transparent"
                  }`}
                >
                  ✓
                </button>

                <div className="min-w-0 flex-1">
                  <p className="truncate text-[12.5px] font-medium text-ink" title={document.fileName}>
                    {document.fileName}
                  </p>
                  <div className="mt-1 flex flex-wrap items-center gap-1">
                    <span className="chip">청크 {document.chunkCount}</span>
                    {document.type === "pdf" && <span className="chip">{document.pageCount}p</span>}
                    <span className="chip">{document.strategy}/{document.chunkSize}</span>
                  </div>
                </div>

                <button
                  type="button"
                  className="btn btn-ghost btn-xs opacity-0 transition-opacity group-hover:opacity-100"
                  title="이 문서 삭제"
                  onClick={() => void removeDocument(document.docId)}
                >
                  <Icon name="trash" />
                </button>
              </div>
            );
          })}
          <p className="px-1 text-[10.5px] leading-snug text-subtle">
            체크한 문서만 검색합니다 (아무것도 체크하지 않으면 전체 문서).
          </p>
        </div>
      )}

      {/* 청킹 설정 */}
      <div className="card p-2.5">
        <button
          type="button"
          className="flex w-full items-center gap-2 text-left"
          onClick={() => setShowChunking((prev) => !prev)}
        >
          <Icon name="chevron" className={showChunking ? "rotate-90" : ""} />
          <span className="text-[12px] font-medium text-ink">청킹 설정</span>
          <span className="mono ml-auto text-[10.5px] text-subtle">
            {settings.strategy} · {settings.chunkSize}
          </span>
        </button>

        {showChunking && (
          <div className="mt-3 flex flex-col gap-3">
            <label className="block">
              <span className="mb-1 block text-[12px] text-muted">전략</span>
              <select
                className="select"
                value={settings.strategy}
                onChange={(event) => updateSettings({ strategy: event.target.value })}
              >
                {strategies.map((strategy) => (
                  <option key={strategy.id} value={strategy.id}>
                    {strategy.label}
                  </option>
                ))}
              </select>
              {strategyInfo && (
                <span className="mt-1 block text-[11px] leading-snug text-subtle">
                  {strategyInfo.description}
                </span>
              )}
            </label>

            <SliderField
              label={settings.strategy === "TOKEN" ? "청크 크기 (토큰)" : "청크 크기 (글자)"}
              value={settings.chunkSize}
              min={100}
              max={1200}
              step={50}
              onChange={(value) => updateSettings({ chunkSize: value })}
              hint="작게 자르면 정확한 문장을 찾지만 문맥이 끊기고, 크게 자르면 반대가 된다."
            />

            <SliderField
              label="겹침 overlap (글자)"
              value={settings.overlap}
              min={0}
              max={400}
              step={20}
              onChange={(value) => updateSettings({ overlap: value })}
              hint={
                settings.strategy === "SLIDING"
                  ? "청크가 서로 겹치는 글자 수."
                  : "슬라이딩 윈도우 전략에서만 사용됩니다."
              }
            />
            <p className="text-[10.5px] leading-snug text-subtle">
              설정을 바꾼 뒤 <span className="text-muted">같은 문서를 다시 업로드</span>하면 새 설정으로 인덱싱된
              사본이 추가됩니다 — 두 사본을 각각 검색 대상으로 지정해 비교해볼 수 있습니다.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

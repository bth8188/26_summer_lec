"use client";

/**
 * 설정 슬라이드 패널. 여기 있는 값이 그대로 백엔드 `RagOptions`로 전달된다 —
 * 즉 화면에서 슬라이더를 움직이는 것이 곧 RAG 파라미터 실험이다.
 */

import { API_BASE } from "@/lib/api";
import { FEATURES } from "@/lib/protocol";
import { DEFAULT_SETTINGS } from "@/lib/state";
import type { AgentConsole } from "@/lib/useAgentConsole";
import { Badge, Icon, SectionLabel, SliderField, Toggle, tierLabel, tierTone } from "./ui";

export function SettingsPanel({
  console: agent,
  onClose,
}: {
  console: AgentConsole;
  onClose: () => void;
}) {
  const { settings, updateSettings, toggleFeature, pipelines, selectedPipeline } = agent;
  const supported = selectedPipeline?.supportedFeatures ?? [];

  return (
    <>
      <button
        type="button"
        aria-label="설정 닫기"
        className="fixed inset-0 z-40 bg-black/45 backdrop-blur-[2px]"
        onClick={onClose}
      />
      <aside className="fade-in fixed inset-y-0 right-0 z-50 flex w-full max-w-[440px] flex-col border-l border-line bg-panel">
        <header className="flex items-center gap-2 border-b border-line px-4 py-3">
          <Icon name="gear" className="text-accent" />
          <h2 className="text-[14px] font-semibold text-ink">실행 설정</h2>
          <button type="button" className="btn btn-ghost btn-xs ml-auto" onClick={onClose}>
            <Icon name="close" />
            닫기
          </button>
        </header>

        <div className="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4 py-4">
          {/* 파이프라인 */}
          <section>
            <SectionLabel>파이프라인</SectionLabel>
            <select
              className="select"
              value={settings.pipelineId}
              onChange={(event) => updateSettings({ pipelineId: event.target.value })}
            >
              {pipelines.map((pipeline) => (
                <option key={pipeline.id} value={pipeline.id}>
                  {pipeline.name} ({tierLabel(pipeline.tier)})
                </option>
              ))}
            </select>
            {selectedPipeline && (
              <p className="mt-1.5 flex items-start gap-2 text-[11.5px] leading-snug text-subtle">
                <Badge tone={tierTone(selectedPipeline.tier)}>{tierLabel(selectedPipeline.tier)}</Badge>
                <span>{selectedPipeline.description}</span>
              </p>
            )}

            <div className="mt-3 flex flex-col gap-2">
              <Toggle
                checked={settings.compare}
                onChange={() => updateSettings({ compare: !settings.compare })}
                label="비교 실행"
                hint="같은 질문을 두 파이프라인으로 동시에 실행해 나란히 보여줍니다. 내 파이프라인이 정말 나아졌는지 확인하는 용도 — 다만 LLM 부하가 2배가 됩니다."
              />
              {settings.compare && (
                <select
                  className="select"
                  value={settings.comparePipelineId}
                  onChange={(event) => updateSettings({ comparePipelineId: event.target.value })}
                >
                  {pipelines
                    .filter((pipeline) => pipeline.id !== settings.pipelineId)
                    .map((pipeline) => (
                      <option key={pipeline.id} value={pipeline.id}>
                        비교 대상: {pipeline.name}
                      </option>
                    ))}
                </select>
              )}
            </div>
          </section>

          {/* 검색 */}
          <section className="flex flex-col gap-4">
            <SectionLabel>검색</SectionLabel>
            <SliderField
              label="topK — 가져올 청크 수"
              value={settings.topK}
              min={1}
              max={12}
              step={1}
              onChange={(value) => updateSettings({ topK: value })}
              hint="너무 적으면 근거가 부족하고, 너무 많으면 관련 없는 청크가 답변을 흐린다."
            />
            <SliderField
              label="유사도 임계값"
              value={settings.similarityThreshold}
              min={0}
              max={0.9}
              step={0.05}
              format={(value) => (value === 0 ? "0 (전부 허용)" : value.toFixed(2))}
              onChange={(value) => updateSettings({ similarityThreshold: value })}
              hint="이 점수 미만인 청크는 버린다. 올리면 정확해지지만 '문서에서 찾을 수 없습니다'가 늘어난다."
            />
          </section>

          {/* 생성 */}
          <section className="flex flex-col gap-4">
            <SectionLabel>생성</SectionLabel>
            <SliderField
              label="temperature"
              value={settings.temperature}
              min={0}
              max={1.5}
              step={0.1}
              onChange={(value) => updateSettings({ temperature: value })}
              hint="0에 가까우면 문서 표현을 그대로 옮기고, 높으면 자유롭게 쓴다(할루시네이션도 늘어난다)."
            />
            <SliderField
              label="대화 기억 턴 수"
              value={settings.maxHistory}
              min={0}
              max={12}
              step={1}
              onChange={(value) => updateSettings({ maxHistory: value })}
              hint="이전 대화를 몇 개까지 프롬프트에 실을지. 0이면 매번 새 대화처럼 동작한다."
            />
          </section>

          {/* 기능 토글 */}
          <section>
            <SectionLabel>파이프라인 기능 (학생 구현)</SectionLabel>
            <div className="flex flex-col gap-2">
              {FEATURES.map((feature) => {
                const usable = supported.includes(feature.key);
                return (
                  <Toggle
                    key={feature.key}
                    checked={Boolean(settings.features[feature.key]) && usable}
                    disabled={!usable}
                    onChange={() => toggleFeature(feature.key)}
                    label={feature.label}
                    right={
                      usable ? (
                        <Badge tone={feature.tier === "골드" ? "warn" : "accent"}>{feature.tier}</Badge>
                      ) : (
                        <Badge>미지원</Badge>
                      )
                    }
                    hint={
                      usable
                        ? feature.hint
                        : `${selectedPipeline?.name ?? "이 파이프라인"}은 이 기능을 선언하지 않았습니다. 파이프라인을 "내 파이프라인"으로 바꾸세요.`
                    }
                  />
                );
              })}
            </div>
            <p className="mt-2 text-[11px] leading-snug text-subtle">
              토글을 켜도 백엔드에 구현이 없으면 해당 단계가 TODO 카드로 표시됩니다 — 어디를 채워야 하는지
              화면이 알려주는 구조입니다.
            </p>
          </section>

          {/* 시스템 프롬프트 */}
          <section>
            <SectionLabel>시스템 프롬프트</SectionLabel>
            <textarea
              className="textarea min-h-[120px]"
              value={settings.systemPrompt}
              placeholder={
                "비워두면 기본 프롬프트를 사용합니다.\n(한국어 답변 / 근거 없으면 모른다고 답 / [1] 인용 표기 규칙)"
              }
              onChange={(event) => updateSettings({ systemPrompt: event.target.value })}
            />
            <p className="mt-1.5 text-[11px] leading-snug text-subtle">
              프롬프트에서 인용 표기 규칙을 빼면 답변의 [1] 칩도 사라집니다 — 프롬프트가 UI 기능까지 좌우한다는
              걸 직접 확인해볼 수 있습니다.
            </p>
          </section>

          <section className="flex items-center gap-2">
            <button
              type="button"
              className="btn btn-xs"
              onClick={() =>
                updateSettings({
                  ...DEFAULT_SETTINGS,
                  pipelineId: settings.pipelineId,
                  strategy: settings.strategy,
                  chunkSize: settings.chunkSize,
                  overlap: settings.overlap,
                })
              }
            >
              <Icon name="refresh" />
              기본값으로
            </button>
            <span className="mono ml-auto truncate text-[10.5px] text-subtle" title={API_BASE}>
              {API_BASE}
            </span>
          </section>
        </div>
      </aside>
    </>
  );
}

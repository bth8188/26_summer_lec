"use client";

/**
 * 파이프라인 단계 타임라인. "지금 무슨 일이 일어나는 중인지"를 보여주는 이 화면의 핵심 위젯.
 * status가 "todo"인 단계는 점선으로 그리고, 어느 파일의 어느 메서드를 채우면 되는지 같이 표시한다.
 */

import type { Step } from "@/lib/state";
import { Icon, Spinner } from "./ui";

function StatusMark({ status }: { status: Step["status"] }) {
  if (status === "running") {
    return <Spinner className="text-accent" />;
  }
  if (status === "done") {
    return <Icon name="check" className="text-ok" />;
  }
  if (status === "error") {
    return <Icon name="warn" className="text-err" />;
  }
  return (
    <span className="flex size-[15px] items-center justify-center">
      <span className="size-2.5 rounded-full border border-dashed border-warn" />
    </span>
  );
}

export function StepTrace({ steps }: { steps: Step[] }) {
  if (steps.length === 0) return null;

  return (
    <ol className="flex flex-col gap-1.5">
      {steps.map((step) => {
        const todo = step.status === "todo";
        return (
          <li
            key={step.id}
            className={`fade-in flex items-start gap-2.5 rounded-lg px-2.5 py-2 ${
              todo
                ? "border border-dashed border-warn/45 bg-warn/[0.06]"
                : "border border-transparent bg-panel2/60"
            }`}
          >
            <span className="mt-0.5">
              <StatusMark status={step.status} />
            </span>

            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
                <span className="text-[12.5px] font-medium text-ink">{step.label}</span>
                {typeof step.ms === "number" && (
                  <span className="mono text-[10.5px] text-subtle">{step.ms.toLocaleString()}ms</span>
                )}
                {todo && (
                  <span className="mono text-[10px] font-semibold tracking-wide text-warn">TODO</span>
                )}
              </div>

              {step.detail && (
                <p className="mt-0.5 text-[11.5px] leading-snug text-muted">{step.detail}</p>
              )}
              {step.hint && (
                <p className="mt-1 text-[11.5px] leading-snug text-warn/90">{step.hint}</p>
              )}
              {step.file && (
                <p className="mono mt-1 text-[10.5px] text-subtle">📄 {step.file}</p>
              )}

              {typeof step.current === "number" && typeof step.total === "number" && step.total > 0 && (
                <div className="mt-1.5 flex items-center gap-2">
                  <div className="h-1 flex-1 overflow-hidden rounded-full bg-panel3">
                    <div
                      className="h-full rounded-full bg-accent transition-[width] duration-200"
                      style={{ width: `${Math.round((step.current / step.total) * 100)}%` }}
                    />
                  </div>
                  <span className="mono text-[10px] text-subtle">
                    {step.current}/{step.total}
                  </span>
                </div>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

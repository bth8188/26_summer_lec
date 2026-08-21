"use client";

/** 화면 곳곳에서 재사용하는 작은 조각들 (뱃지·토글·슬라이더·아이콘). */

import { useState, type ReactNode } from "react";

export function Badge({
  children,
  tone = "default",
  title,
}: {
  children: ReactNode;
  tone?: "default" | "accent" | "violet" | "ok" | "warn" | "err";
  title?: string;
}) {
  const styles: Record<string, string> = {
    default: "border-line text-muted",
    accent: "border-accent/40 text-accent bg-accent/10",
    violet: "border-accent2/40 text-accent2 bg-accent2/10",
    ok: "border-ok/40 text-ok bg-ok/10",
    warn: "border-warn/40 text-warn bg-warn/10",
    err: "border-err/40 text-err bg-err/10",
  };
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1 whitespace-nowrap rounded-md border px-1.5 py-0.5 text-[10.5px] font-medium ${styles[tone]}`}
    >
      {children}
    </span>
  );
}

export function tierTone(tier?: string): "accent" | "violet" | "warn" | "default" {
  if (tier === "bronze") return "default";
  if (tier === "silver") return "accent";
  if (tier === "gold") return "warn";
  return "violet";
}

export function tierLabel(tier?: string): string {
  switch (tier) {
    case "bronze":
      return "브론즈";
    case "silver":
      return "실버";
    case "gold":
      return "골드";
    default:
      return "커스텀";
  }
}

export function StatusDot({ state }: { state: "ok" | "warn" | "err" | "idle" }) {
  const color =
    state === "ok"
      ? "bg-ok"
      : state === "warn"
        ? "bg-warn"
        : state === "err"
          ? "bg-err"
          : "bg-subtle";
  return <span className={`inline-block size-2 rounded-full ${color}`} />;
}

export function Toggle({
  checked,
  onChange,
  disabled,
  label,
  hint,
  right,
}: {
  checked: boolean;
  onChange: () => void;
  disabled?: boolean;
  label: ReactNode;
  hint?: string;
  right?: ReactNode;
}) {
  return (
    <label
      className={`flex items-start gap-3 rounded-lg border border-line bg-panel2 px-3 py-2.5 ${
        disabled ? "opacity-50" : "cursor-pointer hover:border-line-strong"
      }`}
    >
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={onChange}
        className={`mt-0.5 flex h-[18px] w-[32px] shrink-0 items-center rounded-full border transition-colors ${
          checked ? "border-transparent bg-accent" : "border-line-strong bg-panel3"
        } ${disabled ? "cursor-not-allowed" : "cursor-pointer"}`}
      >
        <span
          className={`block size-[12px] rounded-full bg-white shadow transition-transform ${
            checked ? "translate-x-[17px]" : "translate-x-[3px]"
          }`}
        />
      </button>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2 text-[13px] font-medium text-ink">
          {label}
          {right}
        </span>
        {hint && <span className="mt-0.5 block text-[11.5px] leading-snug text-subtle">{hint}</span>}
      </span>
    </label>
  );
}

export function SliderField({
  label,
  value,
  min,
  max,
  step,
  onChange,
  format,
  hint,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  onChange: (value: number) => void;
  format?: (value: number) => string;
  hint?: string;
}) {
  return (
    <div>
      <div className="mb-1.5 flex items-baseline justify-between">
        <span className="text-[12.5px] font-medium text-ink">{label}</span>
        <span className="mono text-[12px] text-accent">{format ? format(value) : value}</span>
      </div>
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={value}
        onChange={(event) => onChange(Number(event.target.value))}
        className="w-full accent-[var(--accent)]"
      />
      {hint && <p className="mt-1 text-[11px] leading-snug text-subtle">{hint}</p>}
    </div>
  );
}

export function SectionLabel({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex items-center justify-between px-1 pb-2">
      <span className="text-[10.5px] font-semibold tracking-[0.13em] text-subtle uppercase">
        {children}
      </span>
      {action}
    </div>
  );
}

export function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      className="btn btn-ghost btn-xs"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text);
          setCopied(true);
          setTimeout(() => setCopied(false), 1200);
        } catch {
          /* 클립보드 권한이 없으면 조용히 무시 */
        }
      }}
    >
      <Icon name="copy" />
      {copied ? "복사됨" : "복사"}
    </button>
  );
}

export function ScoreBar({ value, max = 5 }: { value: number; max?: number }) {
  const ratio = Math.max(0, Math.min(1, value / max));
  const tone = ratio >= 0.8 ? "bg-ok" : ratio >= 0.5 ? "bg-warn" : "bg-err";
  return (
    <div className="h-1.5 w-full overflow-hidden rounded-full bg-panel3">
      <div className={`h-full rounded-full ${tone}`} style={{ width: `${ratio * 100}%` }} />
    </div>
  );
}

/** 얇은 선 아이콘 모음 — 외부 아이콘 라이브러리 없이 필요한 것만. */
export function Icon({ name, className = "" }: { name: IconName; className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`size-[15px] shrink-0 ${className}`}
      aria-hidden
    >
      {PATHS[name]}
    </svg>
  );
}

export type IconName =
  | "send"
  | "stop"
  | "plus"
  | "trash"
  | "gear"
  | "copy"
  | "refresh"
  | "check"
  | "warn"
  | "doc"
  | "upload"
  | "chevron"
  | "close"
  | "sun"
  | "moon"
  | "panelLeft"
  | "panelRight"
  | "bolt"
  | "scale"
  | "search"
  | "code"
  | "spinner";

const PATHS: Record<IconName, ReactNode> = {
  send: <path d="M5 12h13M12 6l6 6-6 6" />,
  stop: <rect x="6" y="6" width="12" height="12" rx="2" />,
  plus: <path d="M12 5v14M5 12h14" />,
  trash: <path d="M4 7h16M9 7V5h6v2m-8 0l1 13h8l1-13" />,
  gear: (
    <>
      <circle cx="12" cy="12" r="3.2" />
      <path d="M12 3v2m0 14v2M3 12h2m14 0h2M5.6 5.6l1.4 1.4m10 10l1.4 1.4m0-12.8l-1.4 1.4m-10 10l-1.4 1.4" />
    </>
  ),
  copy: (
    <>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M15 5H6a2 2 0 00-2 2v9" />
    </>
  ),
  refresh: <path d="M20 12a8 8 0 11-2.3-5.6M20 4v4h-4" />,
  check: <path d="M4 12.5l5 5L20 6.5" />,
  warn: (
    <>
      <path d="M12 4l9 16H3l9-16z" />
      <path d="M12 10v4m0 3h.01" />
    </>
  ),
  doc: (
    <>
      <path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8l-5-5z" />
      <path d="M14 3v5h5" />
    </>
  ),
  upload: <path d="M12 17V5m0 0L7 10m5-5l5 5M4 19h16" />,
  chevron: <path d="M9 6l6 6-6 6" />,
  close: <path d="M6 6l12 12M18 6L6 18" />,
  sun: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4l1.4 1.4m0-14.2l-1.4 1.4M6.3 17.7l-1.4 1.4" />
    </>
  ),
  moon: <path d="M20 14.5A8.5 8.5 0 019.5 4a8.5 8.5 0 1010.5 10.5z" />,
  panelLeft: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M9 4v16" />
    </>
  ),
  panelRight: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M15 4v16" />
    </>
  ),
  bolt: <path d="M13 3L5 14h5l-1 7 8-11h-5l1-7z" />,
  scale: <path d="M12 4v16M6 8l-3 6h6l-3-6zm12 0l-3 6h6l-3-6zM6 8h12" />,
  search: (
    <>
      <circle cx="11" cy="11" r="6" />
      <path d="M20 20l-4.3-4.3" />
    </>
  ),
  code: <path d="M9 8l-4 4 4 4m6-8l4 4-4 4" />,
  spinner: <path d="M12 3a9 9 0 019 9" />,
};

export function Spinner({ className = "" }: { className?: string }) {
  return <Icon name="spinner" className={`spin ${className}`} />;
}

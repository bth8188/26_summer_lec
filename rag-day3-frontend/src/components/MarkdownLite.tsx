"use client";

/**
 * 아주 작은 마크다운 렌더러 + 인용 칩([1]) 처리.
 *
 * 외부 마크다운 라이브러리를 쓰지 않는 이유는 두 가지다.
 * (1) 스트리밍 중에는 마크다운이 항상 "미완성"이라 무거운 파서가 이득이 적다.
 * (2) 답변 안의 `[1]` 을 클릭 가능한 근거 칩으로 바꾸는 게 이 화면의 핵심 기능인데,
 *     그걸 직접 다루는 게 훨씬 간단하다.
 *
 * 지원: 문단 / 글머리 목록 / 번호 목록 / 코드 블록 / 헤딩 / **굵게** / `코드` / [n] 인용
 */

import { useMemo, type ReactNode } from "react";

type Block =
  | { kind: "p"; text: string }
  | { kind: "h"; level: number; text: string }
  | { kind: "ul"; items: string[] }
  | { kind: "ol"; items: string[] }
  | { kind: "code"; lines: string[] };

function parseBlocks(source: string): Block[] {
  const blocks: Block[] = [];
  const lines = source.split("\n");
  let paragraph: string[] = [];
  let list: { kind: "ul" | "ol"; items: string[] } | null = null;
  let code: string[] | null = null;

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      blocks.push({ kind: "p", text: paragraph.join(" ").trim() });
      paragraph = [];
    }
  };
  const flushList = () => {
    if (list) {
      blocks.push(list.kind === "ul" ? { kind: "ul", items: list.items } : { kind: "ol", items: list.items });
      list = null;
    }
  };

  for (const rawLine of lines) {
    const line = rawLine.trimEnd();

    if (line.trim().startsWith("```")) {
      if (code) {
        blocks.push({ kind: "code", lines: code });
        code = null;
      } else {
        flushParagraph();
        flushList();
        code = [];
      }
      continue;
    }
    if (code) {
      code.push(rawLine);
      continue;
    }

    if (line.trim() === "") {
      flushParagraph();
      flushList();
      continue;
    }

    const heading = /^(#{1,4})\s+(.*)$/.exec(line);
    if (heading) {
      flushParagraph();
      flushList();
      blocks.push({ kind: "h", level: heading[1].length, text: heading[2] });
      continue;
    }

    const bullet = /^\s*[-*•]\s+(.*)$/.exec(line);
    if (bullet) {
      flushParagraph();
      if (!list || list.kind !== "ul") {
        flushList();
        list = { kind: "ul", items: [] };
      }
      list.items.push(bullet[1]);
      continue;
    }

    const ordered = /^\s*\d+[.)]\s+(.*)$/.exec(line);
    if (ordered) {
      flushParagraph();
      if (!list || list.kind !== "ol") {
        flushList();
        list = { kind: "ol", items: [] };
      }
      list.items.push(ordered[1]);
      continue;
    }

    flushList();
    paragraph.push(line.trim());
  }

  if (code) blocks.push({ kind: "code", lines: code });
  flushParagraph();
  flushList();
  return blocks;
}

const INLINE = /(\*\*[^*]+\*\*|`[^`]+`|\[\d{1,2}\])/g;

function renderInline(text: string, onCitation?: (index: number) => void): ReactNode[] {
  const parts = text.split(INLINE).filter((part) => part !== "");
  return parts.map((part, i) => {
    if (part.startsWith("**") && part.endsWith("**")) {
      return <strong key={i}>{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith("`") && part.endsWith("`")) {
      return <code key={i}>{part.slice(1, -1)}</code>;
    }
    const citation = /^\[(\d{1,2})\]$/.exec(part);
    if (citation) {
      const index = Number(citation[1]);
      return (
        <button
          key={i}
          type="button"
          className="citation"
          title={`근거 ${index}번 보기`}
          onClick={() => onCitation?.(index)}
        >
          {index}
        </button>
      );
    }
    return <span key={i}>{part}</span>;
  });
}

export function MarkdownLite({
  text,
  onCitation,
  streaming,
}: {
  text: string;
  onCitation?: (index: number) => void;
  streaming?: boolean;
}) {
  const blocks = useMemo(() => parseBlocks(text), [text]);

  const lastIndex = blocks.length - 1;
  const caret = (index: number) =>
    streaming && index === lastIndex ? <span className="caret-inline" /> : null;

  return (
    <div className="answer text-[14px] text-ink">
      {blocks.length === 0 && streaming && <span className="caret-inline" />}
      {blocks.map((block, index) => {
        switch (block.kind) {
          case "h":
            return (
              <p key={index} className="mt-1 mb-1 font-semibold">
                {renderInline(block.text, onCitation)}
                {caret(index)}
              </p>
            );
          case "ul":
            return (
              <ul key={index}>
                {block.items.map((item, i) => (
                  <li key={i}>
                    {renderInline(item, onCitation)}
                    {i === block.items.length - 1 ? caret(index) : null}
                  </li>
                ))}
              </ul>
            );
          case "ol":
            return (
              <ol key={index}>
                {block.items.map((item, i) => (
                  <li key={i}>
                    {renderInline(item, onCitation)}
                    {i === block.items.length - 1 ? caret(index) : null}
                  </li>
                ))}
              </ol>
            );
          case "code":
            return (
              <pre key={index}>
                <code>{block.lines.join("\n")}</code>
              </pre>
            );
          default:
            return (
              <p key={index}>
                {renderInline(block.text, onCitation)}
                {caret(index)}
              </p>
            );
        }
      })}
    </div>
  );
}

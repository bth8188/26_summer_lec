"use client";

import { useChat } from "@ai-sdk/react";
import { TextStreamChatTransport } from "ai";
import { useRef, useState } from "react";

const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8081";

export default function Home() {
  const [file, setFile] = useState<File | null>(null);
  const [indexing, setIndexing] = useState(false);
  const [indexed, setIndexed] = useState<{ fileName: string; chunkCount: number } | null>(null);
  const [indexError, setIndexError] = useState<string | null>(null);
  const [input, setInput] = useState("");
  const fileInputRef = useRef<HTMLInputElement>(null);

  const { messages, sendMessage, status, error } = useChat({
    transport: new TextStreamChatTransport({
      api: `${API_BASE}/api/chat`,
      prepareSendMessagesRequest: ({ messages }) => {
        const last = messages[messages.length - 1];
        const text = last?.parts.find((p) => p.type === "text")?.text ?? "";
        return { body: { question: text } };
      },
    }),
  });

  const busy = status === "submitted" || status === "streaming";

  async function handleUpload() {
    if (!file) return;
    setIndexing(true);
    setIndexError(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      const res = await fetch(`${API_BASE}/api/index`, { method: "POST", body: formData });
      if (!res.ok) throw new Error(`업로드 실패 (HTTP ${res.status})`);
      const data = await res.json();
      setIndexed({ fileName: data.fileName, chunkCount: data.chunkCount });
    } catch (e) {
      setIndexError(e instanceof Error ? e.message : "알 수 없는 오류");
    } finally {
      setIndexing(false);
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!input.trim() || busy) return;
    sendMessage({ text: input });
    setInput("");
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gradient-to-b from-slate-100 to-slate-200 p-6 dark:from-slate-950 dark:to-slate-900">
      <div className="flex w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xl dark:border-slate-800 dark:bg-slate-900">
        <header className="border-b border-slate-200 bg-gradient-to-r from-indigo-600 to-violet-600 px-6 py-5 dark:border-slate-800">
          <h1 className="text-lg font-semibold text-white">RAG 특강 · Day3 캡스톤 챗봇</h1>
          <p className="mt-1 text-sm text-indigo-100">PDF를 올리면 그 문서 내용으로만 답변합니다.</p>
        </header>

        <section className="flex items-center gap-3 border-b border-slate-200 px-6 py-4 dark:border-slate-800">
          <input
            ref={fileInputRef}
            type="file"
            accept="application/pdf"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            className="flex-1 text-sm text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-slate-100 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-slate-700 hover:file:bg-slate-200 dark:text-slate-300 dark:file:bg-slate-800 dark:file:text-slate-200"
          />
          <button
            onClick={handleUpload}
            disabled={!file || indexing}
            className="shrink-0 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
          >
            {indexing ? "인덱싱 중…" : "업로드 & 인덱싱"}
          </button>
        </section>

        {(indexed || indexError) && (
          <div
            className={`px-6 py-2 text-xs ${
              indexError
                ? "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
                : "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300"
            }`}
          >
            {indexError ?? `"${indexed?.fileName}" 인덱싱 완료 — 청크 ${indexed?.chunkCount}개`}
          </div>
        )}

        <div className="flex h-[420px] flex-col gap-3 overflow-y-auto px-6 py-4">
          {messages.length === 0 && (
            <p className="mt-8 text-center text-sm text-slate-400 dark:text-slate-500">
              {indexed ? "질문을 입력해보세요." : "먼저 PDF를 업로드하고 인덱싱해주세요."}
            </p>
          )}
          {messages.map((m) => (
            <div key={m.id} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
              <div
                className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-4 py-2 text-sm ${
                  m.role === "user"
                    ? "bg-indigo-600 text-white"
                    : "bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100"
                }`}
              >
                {m.parts
                  .filter((p) => p.type === "text")
                  .map((p, i) => (
                    <span key={i}>{p.text}</span>
                  ))}
              </div>
            </div>
          ))}
          {status === "submitted" && (
            <div className="flex justify-start">
              <div className="rounded-2xl bg-slate-100 px-4 py-2 text-sm text-slate-400 dark:bg-slate-800">
                생각하는 중…
              </div>
            </div>
          )}
          {error && (
            <p className="text-xs text-red-500">에러: {error.message}</p>
          )}
        </div>

        <form onSubmit={handleSubmit} className="flex gap-2 border-t border-slate-200 px-6 py-4 dark:border-slate-800">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={indexed ? "문서에 대해 질문해보세요…" : "PDF를 먼저 업로드하세요"}
            disabled={!indexed || busy}
            className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none focus:border-indigo-500 disabled:bg-slate-50 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100 dark:disabled:bg-slate-900"
          />
          <button
            type="submit"
            disabled={!indexed || busy || !input.trim()}
            className="rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-slate-300 dark:disabled:bg-slate-700"
          >
            전송
          </button>
        </form>
      </div>
    </div>
  );
}

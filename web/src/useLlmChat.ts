import { useCallback, useEffect, useRef, useState } from "react";

import type { Language } from "./i18n";
import { createSession, deleteSession, streamPlan, type LlmStreamEvent } from "./llmClient";

export type ChatProgressStage = "intent" | "extracting" | "solving" | "interpreting";

export type ChatMessage = {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  timestamp: string;
  status?: "streaming" | "complete" | "error";
  intent?: string;
  /** Pipeline stage key; ChatPanel localizes it via `chat.progress.<stage>`. */
  progress?: ChatProgressStage;
  citations?: string[];
};

export type LlmChatState = {
  messages: ChatMessage[];
  isStreaming: boolean;
  sessionId: string | null;
  error: string | null;
  sendMessage: (text: string, language?: Language) => void;
  stop: () => void;
  retry: () => void;
  clearChat: () => void;
};

const CITATION_FOOTER = /\n-{3,}[ \t]*\n[ \t]*(?:근거|Sources?)[ \t]*:[ \t]*\n([\s\S]*)$/;

export function useLlmChat(): LlmChatState {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isStreaming, setIsStreaming] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const sessionIdRef = useRef<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const inFlightRef = useRef(false);
  const lastLanguageRef = useRef<Language>("en");
  const messagesRef = useRef<ChatMessage[]>([]);

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);
  useEffect(() => () => abortRef.current?.abort(), []);

  const patchMessage = useCallback((id: string, patch: Partial<ChatMessage>) => {
    setMessages((previous) => previous.map((message) => (message.id === id ? { ...message, ...patch } : message)));
  }, []);

  const runStream = useCallback(async (userText: string, assistantId: string) => {
    inFlightRef.current = true;
    setIsStreaming(true);
    setError(null);

    if (!sessionIdRef.current) {
      try {
        const created = await createSession();
        sessionIdRef.current = created;
        setSessionId(created);
      } catch {
        // Session creation is best-effort; the orchestrator still answers statelessly.
      }
    }

    const controller = new AbortController();
    abortRef.current = controller;
    let streamed = "";
    let failed = false;

    const onEvent = (event: LlmStreamEvent) => {
      const data = event.data as Record<string, unknown> | null;
      switch (event.type) {
        case "intent":
          patchMessage(assistantId, { intent: typeof data?.status === "string" ? data.status : undefined, progress: "intent" });
          break;
        case "extracting":
        case "solving":
        case "interpreting":
          patchMessage(assistantId, { progress: event.type });
          break;
        case "llm_chunk":
          if (typeof data?.content === "string") {
            streamed += data.content;
            patchMessage(assistantId, { content: streamed });
          }
          break;
        case "llm_error":
          failed = true;
          patchMessage(assistantId, {
            status: "error",
            progress: undefined,
            content: streamed || (typeof data?.error === "string" ? data.error : ""),
          });
          if (typeof data?.error === "string") setError(data.error);
          break;
        default:
          break;
      }
    };

    try {
      const result = await streamPlan(
        { message: userText, sessionId: sessionIdRef.current ?? undefined, language: lastLanguageRef.current },
        onEvent,
        controller.signal,
      );
      if (result.error && !failed) {
        failed = true;
        setError(result.error);
        patchMessage(assistantId, { status: "error", progress: undefined, content: streamed });
      } else if (!failed) {
        const { content, citations } = splitCitations(streamed);
        patchMessage(assistantId, { status: "complete", progress: undefined, content, citations });
      }
    } catch (streamError) {
      const aborted = streamError instanceof DOMException && streamError.name === "AbortError";
      if (aborted) {
        // User pressed stop: keep any partial text, drop empty placeholders.
        if (streamed) patchMessage(assistantId, { status: "complete", progress: undefined });
        else setMessages((previous) => previous.filter((message) => message.id !== assistantId));
      } else if (!failed) {
        failed = true;
        const message = streamError instanceof Error ? streamError.message : "Unexpected error";
        setError(message);
        patchMessage(assistantId, { status: "error", progress: undefined, content: streamed });
      }
    } finally {
      abortRef.current = null;
      inFlightRef.current = false;
      setIsStreaming(false);
    }
  }, [patchMessage]);

  const sendMessage = useCallback((text: string, language?: Language) => {
    const trimmed = text.trim();
    if (!trimmed || inFlightRef.current) return;
    if (language) lastLanguageRef.current = language;
    const userMessage: ChatMessage = { id: newId(), role: "user", content: trimmed, timestamp: new Date().toISOString(), status: "complete" };
    const assistantMessage: ChatMessage = { id: newId(), role: "assistant", content: "", timestamp: new Date().toISOString(), status: "streaming", progress: "intent" };
    setMessages((previous) => [...previous, userMessage, assistantMessage]);
    void runStream(trimmed, assistantMessage.id);
  }, [runStream]);

  const stop = useCallback(() => abortRef.current?.abort(), []);

  const retry = useCallback(() => {
    if (inFlightRef.current) return;
    const current = messagesRef.current;
    const lastUserIndex = current.map((message) => message.role).lastIndexOf("user");
    if (lastUserIndex === -1) return;
    const retryText = current[lastUserIndex].content;
    setMessages(current.slice(0, lastUserIndex + 1));
    const assistantMessage: ChatMessage = { id: newId(), role: "assistant", content: "", timestamp: new Date().toISOString(), status: "streaming", progress: "intent" };
    setMessages((previous) => [...previous, assistantMessage]);
    void runStream(retryText, assistantMessage.id);
  }, [runStream]);

  const clearChat = useCallback(() => {
    abortRef.current?.abort();
    const previousSession = sessionIdRef.current;
    sessionIdRef.current = null;
    setSessionId(null);
    setMessages([]);
    setError(null);
    if (previousSession) void deleteSession(previousSession).catch(() => undefined);
  }, []);

  return { messages, isStreaming, sessionId, error, sendMessage, stop, retry, clearChat };
}

/** Splits a RAG citation footer ("---\n근거:\n- ...") out of the answer body. */
function splitCitations(text: string): { content: string; citations: string[] } {
  const match = CITATION_FOOTER.exec(text);
  if (!match) return { content: text, citations: [] };
  const citations = match[1]
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => /^[-•*][ \t]+/.test(line))
    .map((line) => line.replace(/^[-•*][ \t]+/, ""));
  if (citations.length === 0) return { content: text, citations: [] };
  return { content: text.slice(0, match.index).trimEnd(), citations };
}

function newId(): string {
  return typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : `msg-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

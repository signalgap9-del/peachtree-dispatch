import { accessToken } from "./auth";
import type { ChatMessage, LlmStatus, LlmStatusResponse, RagHealthResponse } from "./types";

const PLATFORM_API_URL = import.meta.env.VITE_PLATFORM_API_URL ?? "http://localhost:8080";
export const LLM_CHAT_URL = `${PLATFORM_API_URL}/api/v1/llm/chat`;
export const LLM_STATUS_URL = `${PLATFORM_API_URL}/api/v1/llm/status`;
export const RAG_HEALTH_URL = `${PLATFORM_API_URL}/api/v1/rag/health`;

/** Placeholder tenant for the public preview; the proactive API requires a UUID tenant query param. */
const ANONYMOUS_TENANT = "00000000-0000-0000-0000-000000000000";

const LATENCY_SAMPLE_LIMIT = 50;
const latencySamples: number[] = [];

export interface StreamChatHandlers {
  onChunk: (content: string) => void;
  onError?: (message: string) => void;
  signal?: AbortSignal;
}

/**
 * Streams a chat completion from POST /api/v1/llm/chat. The endpoint
 * returns server-sent events (`llm_chunk`, `llm_done`, `llm_error`),
 * parsed manually because EventSource only supports GET.
 */
export async function streamLlmChat(messages: ChatMessage[], handlers: StreamChatHandlers): Promise<void> {
  const startedAt = performance.now();
  let firstChunkRecorded = false;

  const response = await fetch(LLM_CHAT_URL, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeader() },
    body: JSON.stringify({ messages: messages.map(({ role, content }) => ({ role, content })) }),
    signal: handlers.signal,
  });
  if (!response.ok) {
    throw new Error(await readErrorDetail(response));
  }
  if (!response.body) {
    throw new Error("LLM stream is unavailable in this browser");
  }

  await parseSseStream(response, (event, data) => {
    if (event === "llm_chunk") {
      const content = parseChunkContent(data);
      if (!content) return;
      if (!firstChunkRecorded) {
        firstChunkRecorded = true;
        recordLlmLatency(performance.now() - startedAt);
      }
      handlers.onChunk(content);
    } else if (event === "llm_error") {
      handlers.onError?.(parseErrorMessage(data));
    }
  });
}

/** Client-measured time-to-first-chunk stats for the current session. */
export function getLlmLatencyStats(): { samples: number; averageMs: number } | null {
  if (!latencySamples.length) return null;
  const total = latencySamples.reduce((sum, value) => sum + value, 0);
  return { samples: latencySamples.length, averageMs: Math.round(total / latencySamples.length) };
}

export async function getLlmStatus(): Promise<LlmStatusResponse> {
  const response = await fetch(LLM_STATUS_URL, { headers: authHeader() });
  if (!response.ok) throw new Error(await readErrorDetail(response));
  return response.json() as Promise<LlmStatusResponse>;
}

export async function getRagHealth(): Promise<RagHealthResponse> {
  const response = await fetch(RAG_HEALTH_URL, { headers: authHeader() });
  if (!response.ok) throw new Error(await readErrorDetail(response));
  return response.json() as Promise<RagHealthResponse>;
}

/**
 * Merges /llm/status and /rag/health into the UI-facing status.
 * Returns null when the LLM module is not deployed (the controllers
 * are conditional on atmospath.llm.enabled=true and 404 otherwise).
 */
export async function getLlmServiceStatus(): Promise<LlmStatus | null> {
  try {
    const status = await getLlmStatus();
    const rag = await getRagHealth().catch(() => null);
    return {
      enabled: status.enabled,
      model: status.model,
      dailyTokensUsed: status.dailyBudgetUsed,
      dailyTokenBudget: Math.max(status.dailyBudgetUsed + status.dailyBudgetRemaining, status.dailyBudgetUsed),
      ragEnabled: rag?.searchAvailable ?? false,
      ragVectorCount: rag && rag.indexCount >= 0 ? rag.indexCount : undefined,
    };
  } catch {
    return null;
  }
}

/**
 * Best-effort dismissal via POST /api/v1/proactive/suggestions/{id}/dismiss.
 * Failures are swallowed; the banner always dismisses locally.
 */
export function dismissSuggestion(suggestionId: string): Promise<void> {
  return fetch(`${PLATFORM_API_URL}/api/v1/proactive/suggestions/${encodeURIComponent(suggestionId)}/dismiss?tenantId=${ANONYMOUS_TENANT}`, {
    method: "POST",
    headers: authHeader(),
  })
    .then(() => undefined)
    .catch(() => undefined);
}

function recordLlmLatency(ms: number): void {
  latencySamples.push(Math.round(ms));
  if (latencySamples.length > LATENCY_SAMPLE_LIMIT) latencySamples.shift();
}

function authHeader(): Record<string, string> {
  const token = accessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function readErrorDetail(response: Response): Promise<string> {
  try {
    const body = await response.json() as { error?: { message?: string }; detail?: string; message?: string };
    return body.error?.message ?? body.detail ?? body.message ?? `LLM request failed (${response.status})`;
  } catch {
    return `LLM request failed (${response.status})`;
  }
}

function parseChunkContent(data: string): string {
  try {
    const parsed = JSON.parse(data) as { content?: unknown };
    return typeof parsed.content === "string" ? parsed.content : "";
  } catch {
    return "";
  }
}

function parseErrorMessage(data: string): string {
  try {
    const parsed = JSON.parse(data) as { error?: unknown };
    return typeof parsed.error === "string" && parsed.error ? parsed.error : "LLM stream error";
  } catch {
    return "LLM stream error";
  }
}

/** Minimal SSE frame parser: buffers chunks and dispatches event/data pairs. */
async function parseSseStream(response: Response, onEvent: (event: string, data: string) => void): Promise<void> {
  const reader = response.body!.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      for (const frame of frames) dispatchFrame(frame, onEvent);
    }
    if (buffer.trim()) dispatchFrame(buffer, onEvent);
  } finally {
    reader.releaseLock();
  }
}

function dispatchFrame(frame: string, onEvent: (event: string, data: string) => void): void {
  let eventName = "message";
  const dataLines: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith(":")) continue; // keep-alive comment
    if (line.startsWith("event:")) eventName = line.slice(6).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
  }
  if (dataLines.length) onEvent(eventName, dataLines.join("\n"));
}

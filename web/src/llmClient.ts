import { accessToken } from "./auth";

const PLATFORM_API_URL = import.meta.env.VITE_PLATFORM_API_URL ?? "http://localhost:8080";
const PLAN_PATH = "/api/v1/llm/plan";
const STREAM_CONNECT_TIMEOUT_MS = 20000;
const REQUEST_TIMEOUT_MS = 10000;

export type LlmStreamEventType =
  | "intent"
  | "extracting"
  | "solving"
  | "interpreting"
  | "llm_chunk"
  | "llm_done"
  | "llm_error"
  | "risk_suggestion";

export type LlmStreamEvent = {
  type: LlmStreamEventType;
  data: unknown;
};

export type PlanRequest = {
  message: string;
  sessionId?: string;
  language?: string;
};

export type PlanResponse = {
  sessionId: string;
  intent?: string;
  chunks: string[];
  fullText: string;
  usage?: { totalTokens: number };
  error?: string;
};

export type RagSearchResult = {
  results: Array<Record<string, unknown>>;
  latencyMs: number;
  totalFound: number;
};

/**
 * Streams a conversational plan request from the platform API. The backend
 * replies with an SSE stream (`event: <name>\ndata: <json>\n\n` frames) whose
 * event names are `intent`, `extracting`, `solving`, `interpreting`,
 * `llm_chunk`, `llm_done`, and `error`. The raw `error` event is normalized
 * to `llm_error` here so consumers only deal with the client-side union.
 */
export async function streamPlan(
  request: PlanRequest,
  onEvent: (event: LlmStreamEvent) => void,
  signal?: AbortSignal,
): Promise<PlanResponse> {
  const response: PlanResponse = {
    sessionId: request.sessionId ?? "",
    chunks: [],
    fullText: "",
  };

  let fetchResponse: Response;
  const connectController = new AbortController();
  let timedOut = false;
  if (signal?.aborted) throw new DOMException("Request aborted", "AbortError");
  const onOuterAbort = () => connectController.abort();
  signal?.addEventListener("abort", onOuterAbort, { once: true });
  const connectTimeout = window.setTimeout(() => {
    timedOut = true;
    connectController.abort();
  }, STREAM_CONNECT_TIMEOUT_MS);
  try {
    fetchResponse = await fetch(`${PLATFORM_API_URL}${PLAN_PATH}`, {
      method: "POST",
      headers: planHeaders(),
      body: JSON.stringify(request),
      signal: connectController.signal,
    });
  } catch {
    if (signal?.aborted) throw new DOMException("Request aborted", "AbortError");
    response.error = timedOut
      ? "The assistant did not respond in time."
      : "Network error while contacting the assistant.";
    return response;
  } finally {
    window.clearTimeout(connectTimeout);
    signal?.removeEventListener("abort", onOuterAbort);
  }

  if (!fetchResponse.ok) {
    response.error = await readErrorMessage(fetchResponse);
    return response;
  }
  if (!fetchResponse.body) {
    response.error = "The assistant returned an empty stream.";
    return response;
  }

  const reader = fetchResponse.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  const releaseReader = () => reader.releaseLock();
  signal?.addEventListener("abort", () => void reader.cancel().catch(() => undefined), { once: true });

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = drainSseBuffer(buffer, onEvent, response);
    }
    buffer += decoder.decode();
    drainSseBuffer(`${buffer}\n\n`, onEvent, response);
  } catch (error) {
    if (isAbortError(error)) throw error;
    if (!response.error) response.error = "The assistant stream was interrupted.";
  } finally {
    releaseReader();
  }

  return response;
}

/** Parses complete SSE frames out of the buffer, returning the leftover tail. */
function drainSseBuffer(
  buffer: string,
  onEvent: (event: LlmStreamEvent) => void,
  response: PlanResponse,
): string {
  let cursor = 0;
  for (;;) {
    const boundary = findFrameBoundary(buffer, cursor);
    if (boundary === -1) break;
    const frame = buffer.slice(cursor, boundary.start);
    cursor = boundary.end;
    const event = parseSseFrame(frame);
    if (event) {
      applyEvent(event, response);
      onEvent(event);
    }
  }
  return buffer.slice(cursor);
}

function findFrameBoundary(buffer: string, from: number): { start: number; end: number } | -1 {
  const lf = buffer.indexOf("\n\n", from);
  const crlf = buffer.indexOf("\r\n\r\n", from);
  if (lf === -1 && crlf === -1) return -1;
  if (lf !== -1 && (crlf === -1 || lf < crlf)) return { start: lf, end: lf + 2 };
  return { start: crlf, end: crlf + 4 };
}

function parseSseFrame(frame: string): LlmStreamEvent | null {
  let name = "message";
  const dataLines: string[] = [];
  for (const rawLine of frame.split(/\r?\n/)) {
    if (rawLine.startsWith("event:")) name = rawLine.slice(6).trim();
    else if (rawLine.startsWith("data:")) dataLines.push(rawLine.slice(5).replace(/^ /, ""));
  }
  if (dataLines.length === 0) return null;
  const type = normalizeEventType(name);
  if (!type) return null;
  return { type, data: parseJson(dataLines.join("\n")) };
}

function normalizeEventType(name: string): LlmStreamEventType | null {
  switch (name) {
    case "intent":
    case "extracting":
    case "solving":
    case "interpreting":
    case "llm_chunk":
    case "llm_done":
    case "risk_suggestion":
      return name;
    case "error":
    case "llm_error":
      return "llm_error";
    default:
      return null;
  }
}

function applyEvent(event: LlmStreamEvent, response: PlanResponse): void {
  const data = event.data as Record<string, unknown> | null;
  switch (event.type) {
    case "intent":
      if (data && typeof data.status === "string") response.intent = data.status;
      break;
    case "llm_chunk":
      if (data && typeof data.content === "string") {
        response.chunks.push(data.content);
        response.fullText += data.content;
      }
      break;
    case "llm_done": {
      const usage = data?.usage as Record<string, unknown> | undefined;
      response.usage = { totalTokens: typeof usage?.totalTokens === "number" ? usage.totalTokens : 0 };
      break;
    }
    case "llm_error":
      response.error = typeof data?.error === "string" ? data.error : "The assistant reported an error.";
      break;
    default:
      break;
  }
}

function parseJson(raw: string): unknown {
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: unknown; detail?: unknown; message?: unknown };
    if (typeof body?.error === "string") return body.error;
    if (typeof body?.message === "string") return body.message;
    if (typeof body?.detail === "string") return body.detail;
  } catch {
    // Fall through to the status-based message.
  }
  if (response.status === 429) return "The assistant is rate-limited right now. Try again shortly.";
  if (response.status === 503) return "The assistant is not enabled on this deployment.";
  return `Assistant request failed (${response.status}).`;
}

function planHeaders(): Record<string, string> {
  const token = accessToken();
  return {
    "Content-Type": "application/json",
    Accept: "text/event-stream",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  };
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

async function platformJson<T>(path: string, init?: RequestInit): Promise<T> {
  const token = accessToken();
  const controller = new AbortController();
  let timedOut = false;
  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  let response: Response;
  try {
    response = await fetch(`${PLATFORM_API_URL}${path}`, {
      ...init,
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(init?.headers ?? {}),
      },
    });
  } catch (error) {
    if (isAbortError(error) && !timedOut) throw error;
    throw new Error(timedOut ? "The assistant did not respond in time." : "Network error while contacting the assistant.");
  } finally {
    window.clearTimeout(timeout);
  }
  if (!response.ok) throw new Error(await readErrorMessage(response));
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

/** Creates a conversation session on the platform API. */
export async function createSession(): Promise<string> {
  const body = await platformJson<{ sessionId: string }>("/api/v1/llm/session", { method: "POST" });
  return body.sessionId;
}

/** Returns the orchestrator's view of a session (last intent, solution state). */
export async function getSessionContext(sessionId: string): Promise<unknown> {
  return platformJson<unknown>(`/api/v1/llm/context/${encodeURIComponent(sessionId)}`);
}

/** Deletes a session and its stored conversation context. */
export async function deleteSession(sessionId: string): Promise<void> {
  await platformJson<void>(`/api/v1/llm/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
}

/** Hybrid (vector + keyword) search over historical route observations. */
export async function ragSearch(query: string, filters?: unknown, limit = 5): Promise<RagSearchResult> {
  return platformJson<RagSearchResult>("/api/v1/rag/search", {
    method: "POST",
    body: JSON.stringify({ query, filters: filters ?? null, limit }),
  });
}

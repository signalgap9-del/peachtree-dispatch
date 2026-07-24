import type { NationalRiskOverview } from "./types";

const PLATFORM_API_URL = import.meta.env.VITE_PLATFORM_API_URL ?? "http://localhost:8080";
export const ALERT_STREAM_URL = `${PLATFORM_API_URL}/api/v1/alerts/stream`;

const RECONNECT_BASE_DELAY_MS = 2000;
const RECONNECT_MAX_DELAY_MS = 60000;

export type AlertStreamStatus = "connecting" | "connected" | "disconnected" | "error";

export type AlertStreamEvent = {
  type: "national_risk" | "heartbeat";
  data: NationalRiskOverview | { ts: string };
  receivedAt: string;
};

export type AlertUpdateCallback = (overview: NationalRiskOverview) => void;

export type AlertStreamSnapshot = {
  status: AlertStreamStatus;
  lastEventAt?: string;
  reconnectAttempts: number;
};

type SnapshotListener = (snapshot: AlertStreamSnapshot) => void;

let eventSource: EventSource | null = null;
let status: AlertStreamStatus = "disconnected";
let lastEventAt: string | undefined;
let lastOverview: NationalRiskOverview | null = null;
let reconnectAttempts = 0;
let reconnectTimer: number | undefined;
let connectionRefs = 0;
const subscribers = new Set<AlertUpdateCallback>();
const snapshotListeners = new Set<SnapshotListener>();

// Connect/disconnect are reference-counted so the app shell, the live banner,
// and the status page can share one EventSource without tearing it down for
// the others when one consumer unmounts.
export function connectAlertStream(): void {
  connectionRefs += 1;
  openConnection();
}

export function disconnectAlertStream(): void {
  connectionRefs = Math.max(0, connectionRefs - 1);
  if (connectionRefs === 0) closeConnection();
}

export function subscribeAlertUpdates(callback: AlertUpdateCallback): () => void {
  subscribers.add(callback);
  if (lastOverview) callback(lastOverview);
  return () => {
    subscribers.delete(callback);
  };
}

export function subscribeAlertStreamSnapshot(listener: SnapshotListener): () => void {
  snapshotListeners.add(listener);
  listener(getAlertStreamStatus());
  return () => {
    snapshotListeners.delete(listener);
  };
}

export function getAlertStreamStatus(): AlertStreamSnapshot {
  return { status, lastEventAt, reconnectAttempts };
}

function openConnection(): void {
  if (eventSource) return;
  clearReconnectTimer();
  if (typeof EventSource === "undefined") {
    updateStatus("error");
    return;
  }
  updateStatus("connecting");
  const source = new EventSource(ALERT_STREAM_URL);
  eventSource = source;
  source.addEventListener("national_risk", (event) => handleStreamEvent(toStreamEvent("national_risk", event)));
  source.addEventListener("heartbeat", (event) => handleStreamEvent(toStreamEvent("heartbeat", event)));
  source.onopen = () => {
    reconnectAttempts = 0;
    updateStatus("connected");
  };
  source.onerror = () => {
    // Close so the browser's built-in retry does not race our backoff.
    closeEventSource();
    updateStatus("error");
    scheduleReconnect();
  };
}

function closeConnection(): void {
  clearReconnectTimer();
  closeEventSource();
  reconnectAttempts = 0;
  updateStatus("disconnected");
}

function closeEventSource(): void {
  eventSource?.close();
  eventSource = null;
}

function clearReconnectTimer(): void {
  if (reconnectTimer !== undefined) {
    window.clearTimeout(reconnectTimer);
    reconnectTimer = undefined;
  }
}

function scheduleReconnect(): void {
  if (connectionRefs === 0) return;
  const exponential = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * 2 ** reconnectAttempts);
  reconnectAttempts += 1;
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = undefined;
    openConnection();
  }, jittered(exponential));
}

function jittered(delayMs: number): number {
  return Math.round(delayMs * (0.75 + Math.random() * 0.5));
}

function toStreamEvent(type: AlertStreamEvent["type"], event: Event): AlertStreamEvent | null {
  const raw = (event as MessageEvent).data;
  if (typeof raw !== "string") return null;
  try {
    return { type, data: JSON.parse(raw) as AlertStreamEvent["data"], receivedAt: new Date().toISOString() };
  } catch {
    return null;
  }
}

function handleStreamEvent(streamEvent: AlertStreamEvent | null): void {
  if (!streamEvent) return;
  lastEventAt = streamEvent.receivedAt;
  if (streamEvent.type === "national_risk" && isNationalRiskOverview(streamEvent.data)) {
    lastOverview = streamEvent.data;
    for (const subscriber of subscribers) subscriber(streamEvent.data);
  }
  notifySnapshotListeners();
}

function isNationalRiskOverview(value: unknown): value is NationalRiskOverview {
  if (!value || typeof value !== "object") return false;
  const overview = value as NationalRiskOverview;
  return typeof overview.generated_at === "string"
    && typeof overview.score === "number"
    && typeof overview.level === "string"
    && typeof overview.active_alerts === "number"
    && typeof overview.severe_alerts === "number"
    && Array.isArray(overview.alerts);
}

function updateStatus(next: AlertStreamStatus): void {
  status = next;
  notifySnapshotListeners();
}

function notifySnapshotListeners(): void {
  const snapshot = getAlertStreamStatus();
  for (const listener of snapshotListeners) listener(snapshot);
}

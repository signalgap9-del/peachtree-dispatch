// API client for the freight platform services (Phase 2-4).
//
// All freight services sit behind the freight-nginx gateway (compose service
// "freight-nginx", host port 8090). Locally: docker compose --profile
// production-data --profile freight-platform up -d
//
// This is intentionally lighter than ./api.ts (the risk-engine client): the
// freight services are a separate deployment with their own gateway, so they
// get their own base URL and a thin typed wrapper.

import type {
  Bid,
  BidSubmission,
  CarrierScore,
  CreateLoadRequest,
  FreightLoad,
  LoadPage,
  Settlement,
  TelemetryPing,
  TrackingEvent,
  TrackingHistoryPage,
  Wallet,
} from "./freightTypes";

export const FREIGHT_API_URL =
  import.meta.env.VITE_FREIGHT_API_URL ?? "http://localhost:8090";

const DEFAULT_TIMEOUT_MS = 15000;

export class FreightApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(message);
    this.name = "FreightApiError";
  }
}

interface FreightRequestOptions {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
  timeoutMs?: number;
}

async function freightRequest<T>(path: string, options: FreightRequestOptions = {}): Promise<T> {
  const { method = "GET", body, signal, timeoutMs = DEFAULT_TIMEOUT_MS } = options;
  const controller = new AbortController();
  const onAbort = () => controller.abort();
  signal?.addEventListener("abort", onAbort, { once: true });
  const timer = window.setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(`${FREIGHT_API_URL}${path}`, {
      method,
      headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
      body: body !== undefined ? JSON.stringify(body) : undefined,
      signal: controller.signal,
    });
    if (!response.ok) {
      const text = await response.text().catch(() => "");
      let parsed: unknown;
      try { parsed = text ? JSON.parse(text) : undefined; } catch { parsed = text; }
      throw new FreightApiError(
        `Freight request failed: ${method} ${path} -> ${response.status}`,
        response.status,
        parsed,
      );
    }
    if (response.status === 204) return undefined as T;
    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) return (await response.json()) as T;
    return (await response.text()) as unknown as T;
  } finally {
    window.clearTimeout(timer);
    signal?.removeEventListener("abort", onAbort);
  }
}

function qs(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

export const freightApi = {
  // ── Load board ──
  listLoads: (opts: { corridor?: string; cargoType?: string; status?: string; cursor?: number; limit?: number } = {}) =>
    freightRequest<LoadPage>(
      `/loads${qs({ corridorId: opts.corridor, cargoType: opts.cargoType, status: opts.status, cursor: opts.cursor, limit: opts.limit ?? 20 })}`,
    ),
  getLoad: (id: number) => freightRequest<FreightLoad>(`/loads/${id}`),
  createLoad: (payload: CreateLoadRequest) =>
    freightRequest<FreightLoad>("/loads", { method: "POST", body: payload }),
  matchLoad: (id: number) => freightRequest<void>(`/loads/${id}/match`, { method: "POST" }),

  // ── Bidding ──
  submitBid: (payload: BidSubmission) => freightRequest<void>("/bids", { method: "POST", body: payload }),
  acceptBid: (id: number) => freightRequest<Bid>(`/bids/${id}/accept`, { method: "POST" }),
  withdrawBid: (id: number, carrierId: string) =>
    freightRequest<Bid>(`/bids/${id}/withdraw${qs({ carrierId })}`, { method: "POST" }),
  bidsForLoad: (loadId: number) => freightRequest<Bid[]>(`/bids/load/${loadId}`),

  // ── Rankings ──
  overallRankings: (limit = 10) => freightRequest<CarrierScore[]>(`/rankings/overall${qs({ limit })}`),
  corridorRankings: (corridorId: string, limit = 10) =>
    freightRequest<CarrierScore[]>(`/rankings/corridor/${encodeURIComponent(corridorId)}${qs({ limit })}`),
  carrierScores: (carrierId: string) =>
    freightRequest<Record<string, number>>(`/rankings/carrier/${encodeURIComponent(carrierId)}`),

  // ── Settlements ──
  listSettlements: (opts: { carrierId?: string; status?: string; cursor?: number; limit?: number } = {}) =>
    freightRequest<Settlement[]>(
      `/settlements${qs({ carrierId: opts.carrierId, status: opts.status, cursor: opts.cursor, limit: opts.limit ?? 20 })}`,
    ),
  getSettlement: (id: number) => freightRequest<Settlement>(`/settlements/${id}`),
  retrySettlement: (id: number) => freightRequest<void>(`/settlements/${id}/retry`, { method: "POST" }),
  getWallet: (ownerId: string) => freightRequest<Wallet>(`/settlements/wallet/${encodeURIComponent(ownerId)}`),

  // ── Tracking ──
  truckHistory: (truckId: string, opts: { cursor?: string; limit?: number } = {}) =>
    freightRequest<TrackingHistoryPage>(
      `/tracking/truck/${encodeURIComponent(truckId)}/history${qs({ cursor: opts.cursor, limit: opts.limit ?? 50 })}`,
    ),
  corridorActive: (corridorId: string) =>
    freightRequest<TrackingEvent[]>(`/tracking/corridor/${encodeURIComponent(corridorId)}/active`),

  // ── Telemetry ──
  sendPing: (ping: TelemetryPing) => freightRequest<void>("/telemetry/ping", { method: "POST", body: ping }),

  // ── Health ──
  health: () => freightRequest<string>("/health"),
};

/**
 * Open a WebSocket to the real-time tracking feed. The tracking service pushes
 * a JSON array of TrackingEvent for every flushed batch, filtered server-side
 * by the corridor or truck query param.
 */
export function openTrackingSocket(
  target: { corridor?: string; truck?: string },
  onEvents: (events: TrackingEvent[]) => void,
  onStatus?: (status: "open" | "closed" | "error") => void,
): () => void {
  const wsBase = FREIGHT_API_URL.replace(/^http/, "ws");
  const params = new URLSearchParams();
  if (target.corridor) params.set("corridor", target.corridor);
  if (target.truck) params.set("truck", target.truck);
  const suffix = params.toString() ? `?${params.toString()}` : "";
  const socket = new WebSocket(`${wsBase}/ws/tracking${suffix}`);

  socket.addEventListener("open", () => onStatus?.("open"));
  socket.addEventListener("close", () => onStatus?.("closed"));
  socket.addEventListener("error", () => onStatus?.("error"));
  socket.addEventListener("message", (message) => {
    try {
      const parsed = JSON.parse(message.data as string) as unknown;
      if (Array.isArray(parsed)) onEvents(parsed as TrackingEvent[]);
    } catch {
      // Ignore non-JSON frames (heartbeats).
    }
  });

  return () => socket.close();
}

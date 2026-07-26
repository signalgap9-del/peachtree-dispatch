import type {
  AccountSummary,
  ApiErrorEnvelope,
  DirectionsPlan,
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  WeatherRasterManifest,
  Place,
  SavedPlaceRecord,
  SavedRouteRisk,
  SavedRouteRecord,
  VehicleType,
} from "./types";
import { accessToken } from "./auth";
import { reportClientIssue } from "./telemetry";
import { readStaleCache, recordApiFailure, recordApiRetry, recordApiSuccess, recordStaleFallback, writeStaleCache } from "./resilience";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";
const weatherRasterPngUrl = `${API_URL}/risk/weather-raster.png`;
const DEFAULT_TIMEOUT_MS = 15000;
const DEFAULT_RETRIES = 2;
const DEFAULT_RETRY_BASE_DELAY_MS = 150;
const PUBLIC_RISK_CACHE_MAX_AGE_MS = 30 * 60 * 1000;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly detail?: ApiErrorEnvelope | unknown,
  ) {
    super(message);
    this.name = "ApiError";
  }

  get code() {
    if (isApiErrorEnvelope(this.detail)) return this.detail.error?.code;
    return undefined;
  }

  get requestId() {
    if (isApiErrorEnvelope(this.detail)) return this.detail.error?.requestId;
    return undefined;
  }
}

type RequestOptions = RequestInit & {
  timeoutMs?: number;
  retries?: number;
  retryBaseDelayMs?: number;
  staleCacheKey?: string;
  staleMaxAgeMs?: number;
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const {
    timeoutMs = DEFAULT_TIMEOUT_MS,
    retries,
    retryBaseDelayMs = DEFAULT_RETRY_BASE_DELAY_MS,
    staleCacheKey,
    staleMaxAgeMs = PUBLIC_RISK_CACHE_MAX_AGE_MS,
    signal,
    headers,
    ...requestOptions
  } = options;
  const token = accessToken();
  const mergedHeaders = mergeHeaders({
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
  }, headers);
  const method = normalizeMethod(requestOptions.method);
  const maxRetries = retries ?? (canRetryRequest(method, mergedHeaders) ? DEFAULT_RETRIES : 0);
  let lastError: unknown;

  for (let attempt = 0; attempt <= maxRetries; attempt += 1) {
    try {
      const response = await fetchOnce(`${API_URL}${path}`, requestOptions, mergedHeaders, timeoutMs, signal);
      if (!response.ok) throw await toApiError(response);
      const data = response.status === 204 ? undefined as T : await response.json() as T;
      recordApiSuccess(path);
      if (staleCacheKey && method === "GET") writeStaleCache(staleCacheKey, data);
      return data;
    } catch (error) {
      lastError = error;
      if (signal?.aborted || attempt >= maxRetries || !isRetryableFailure(error)) break;
      recordApiRetry({ path, status: error instanceof ApiError ? error.status : undefined });
      await waitForRetry(jitteredDelay(retryBaseDelayMs, attempt), signal);
    }
  }

  if (!signal?.aborted && staleCacheKey) {
    const stale = readStaleCache<T>(staleCacheKey, staleMaxAgeMs);
    if (stale) {
      recordStaleFallback({ path, cacheKey: staleCacheKey, cachedAt: stale.cachedAt });
      return stale.data;
    }
  }

  recordApiFailure({ path, kind: classifyFailure(lastError) });
  reportRequestFailure(path, lastError, timeoutMs);
  throw lastError instanceof Error ? lastError : new Error("Network request failed");
}

export const api = {
  searchPlaces: (query: string, options?: RequestOptions) =>
    request<Place[]>(`/places/search?q=${encodeURIComponent(query)}`, options),
  directions: (origin: Place, destination: Place, vehicleType: VehicleType, options?: RequestOptions & { riskThreshold?: number }) => {
    const { riskThreshold, ...requestOptions } = options ?? {};
    return request<DirectionsPlan>("/directions", {
      ...requestOptions,
      method: "POST",
      body: JSON.stringify({
        origin,
        destination,
        vehicle_type: vehicleType,
        ...(riskThreshold != null ? { risk_threshold: riskThreshold } : {}),
      }),
    });
  },
  nationalRisk: () => request<NationalRiskOverview>("/risk/national", { staleCacheKey: "risk:national" }),
  weatherSnapshot: () => request<NationalWeatherSnapshot>("/risk/weather-snapshot", { staleCacheKey: "risk:weather-snapshot" }),
  weatherRaster: async () => {
    const manifest = await request<WeatherRasterManifest>("/risk/weather-raster", { staleCacheKey: "risk:weather-raster" });
    const url = import.meta.env.MODE === "test" ? manifest.url : weatherRasterPngUrl;
    return { ...manifest, url };
  },
  locationRisk: (place: Place) =>
    request<LocationRisk>("/risk/location", { method: "POST", body: JSON.stringify(place) }),
  accountSummary: () => request<AccountSummary>("/me/account"),
  savedPlaces: () => request<SavedPlaceRecord[]>("/me/saved/places"),
  savedRoutes: async () => (await request<SavedRouteRecord[]>("/me/saved/routes")).map(normalizeSavedRoute),
  savedRoute: async (savedItemId: string) => normalizeSavedRoute(await request<SavedRouteRecord>(`/me/saved/routes/${savedItemId}`)),
  savedRouteCurrentRisk: (savedItemId: string) => request<SavedRouteRisk>(`/me/saved/routes/${savedItemId}/current-risk`),
  savedRouteRiskHistory: (savedItemId: string) =>
    request<Array<{ checkedAt: string | null; riskScore: number; riskTrend: string }>>(`/me/saved/routes/${savedItemId}/risk-history`),
  savePlace: (place: Place, currentRiskScore?: number) =>
    request<SavedPlaceRecord>("/me/saved/places", {
      method: "POST",
      body: JSON.stringify({
        name: place.display_name,
        longitude: place.longitude,
        latitude: place.latitude,
        currentRiskScore,
      }),
    }),
  saveRoute: (plan: DirectionsPlan) =>
    request<SavedRouteRecord>("/me/saved/routes", {
      method: "POST",
      body: JSON.stringify({
        name: plan.summary,
        originName: plan.origin.display_name,
        destinationName: plan.destination.display_name,
        vehicleType: plan.vehicle_type,
        distanceMiles: plan.distance_miles,
        durationMinutes: plan.duration_minutes,
        climateDelayMinutes: plan.climate_delay_minutes,
        riskScore: plan.risk_score,
        coordinates: plan.coordinates,
        generatedAt: plan.generated_at,
        usualDepartureTime: "08:00",
        riskThreshold: Math.max(45, Math.min(80, plan.risk_score + 15)),
        monitorEnabled: true,
        activeHazards: [...new Set(plan.weather
          .filter((point) => point.risk_score >= 55)
          .slice(0, 4)
          .map((point) => point.city))],
      }),
    }).then(normalizeSavedRoute),
  updateSavedRoute: (savedItemId: string, payload: Partial<Pick<SavedRouteRecord, "name" | "usualDepartureTime" | "riskThreshold" | "monitorEnabled">>) =>
    request<SavedRouteRecord>(`/me/saved/routes/${savedItemId}`, {
      method: "PATCH",
      body: JSON.stringify(payload),
    }).then(normalizeSavedRoute),
  deleteSavedPlace: (savedItemId: string) =>
    request<void>(`/me/saved/places/${savedItemId}`, { method: "DELETE" }),
  deleteSavedRoute: (savedItemId: string) =>
    request<void>(`/me/saved/routes/${savedItemId}`, { method: "DELETE" }),
};

function isApiErrorEnvelope(value: unknown): value is ApiErrorEnvelope {
  return Boolean(value && typeof value === "object" && "error" in value);
}

class RequestTimeoutError extends Error {
  constructor(readonly timeoutMs: number) {
    super(`Request timed out after ${timeoutMs} ms`);
    this.name = "RequestTimeoutError";
  }
}

async function fetchOnce(
  url: string,
  requestOptions: RequestInit,
  headers: Record<string, string>,
  timeoutMs: number,
  signal?: AbortSignal | null,
) {
  const controller = new AbortController();
  let timedOut = false;
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  try {
    return await fetch(url, {
      ...requestOptions,
      headers,
      signal: controller.signal,
    });
  } catch (error) {
    if (timedOut) throw new RequestTimeoutError(timeoutMs);
    throw error;
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}

async function toApiError(response: Response) {
  const body = await response.json().catch(() => ({ detail: response.statusText })) as ApiErrorEnvelope;
  const message = body.error?.message ?? (typeof body.detail === "string" ? body.detail : response.statusText);
  return new ApiError(message || "Request failed", response.status, body);
}

function mergeHeaders(base: Record<string, string>, extra?: HeadersInit): Record<string, string> {
  if (!extra) return base;
  if (extra instanceof Headers) {
    extra.forEach((value, key) => { base[key] = value; });
    return base;
  }
  if (Array.isArray(extra)) {
    for (const [key, value] of extra) base[key] = value;
    return base;
  }
  return { ...base, ...extra };
}

function normalizeMethod(method?: string) {
  return (method ?? "GET").toUpperCase();
}

function canRetryRequest(method: string, headers: Record<string, string>) {
  if (["GET", "HEAD", "OPTIONS"].includes(method)) return true;
  return Boolean(headers["Idempotency-Key"] || headers["idempotency-key"]);
}

function isRetryableFailure(error: unknown) {
  if (error instanceof RequestTimeoutError) return true;
  if (error instanceof ApiError) return error.status === 408 || error.status === 429 || error.status >= 500;
  if (error instanceof DOMException && error.name === "AbortError") return false;
  return true;
}

function jitteredDelay(baseDelayMs: number, attempt: number) {
  const exponential = baseDelayMs * (2 ** attempt);
  const jitter = 0.75 + Math.random() * 0.5;
  return Math.round(exponential * jitter);
}

function waitForRetry(delayMs: number, signal?: AbortSignal | null) {
  return new Promise<void>((resolve, reject) => {
    const cleanup = () => signal?.removeEventListener("abort", abort);
    const timeout = window.setTimeout(() => {
      cleanup();
      resolve();
    }, delayMs);
    const abort = () => {
      window.clearTimeout(timeout);
      cleanup();
      reject(new DOMException("Request aborted", "AbortError"));
    };
    if (signal?.aborted) {
      abort();
      return;
    }
    signal?.addEventListener("abort", abort, { once: true });
  });
}

function classifyFailure(error: unknown) {
  if (error instanceof ApiError) return `api_${error.status}`;
  if (error instanceof RequestTimeoutError) return "timeout";
  if (error instanceof DOMException && error.name === "AbortError") return "aborted";
  return "network";
}

function reportRequestFailure(path: string, error: unknown, timeoutMs: number) {
  if (error instanceof ApiError) {
    reportClientIssue({
      kind: "api_error",
      message: error.message,
      details: { path, status: error.status, code: error.code ?? null, requestId: error.requestId ?? null },
    });
  } else if (error instanceof RequestTimeoutError) {
    reportClientIssue({ kind: "api_timeout", message: error.message, details: { path, timeoutMs } });
  } else if (!(error instanceof DOMException && error.name === "AbortError")) {
    reportClientIssue({
      kind: "network_error",
      message: error instanceof Error ? error.message : "Network request failed",
      details: { path },
    });
  }
}

function normalizeSavedRoute(route: SavedRouteRecord): SavedRouteRecord {
  return {
    ...route,
    usualDepartureTime: route.usualDepartureTime ?? "08:00",
    riskThreshold: route.riskThreshold ?? 55,
    monitorEnabled: route.monitorEnabled ?? true,
    lastCheckedAt: route.lastCheckedAt ?? route.generatedAt,
    activeHazards: route.activeHazards ?? [],
    riskTrend: route.riskTrend ?? "STABLE",
  };
}

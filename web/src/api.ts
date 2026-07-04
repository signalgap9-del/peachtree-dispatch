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

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";
const weatherRasterPngUrl = `${API_URL}/risk/weather-raster.png`;

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
};

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { timeoutMs = 15000, signal, headers, ...requestOptions } = options;
  const controller = new AbortController();
  const abort = () => controller.abort();
  signal?.addEventListener("abort", abort, { once: true });
  const timeout = window.setTimeout(() => controller.abort(), timeoutMs);
  const token = accessToken();
  const mergedHeaders = {
    "Content-Type": "application/json",
    ...(token ? { Authorization: `Bearer ${token}` } : {}),
    ...(headers as Record<string, string> | undefined),
  };
  try {
    const response = await fetch(`${API_URL}${path}`, {
      ...requestOptions,
      headers: mergedHeaders,
      signal: controller.signal,
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({ detail: response.statusText })) as ApiErrorEnvelope;
      const message = body.error?.message ?? (typeof body.detail === "string" ? body.detail : response.statusText);
      throw new ApiError(message || "Request failed", response.status, body);
    }
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  } finally {
    window.clearTimeout(timeout);
    signal?.removeEventListener("abort", abort);
  }
}

export const api = {
  searchPlaces: (query: string, options?: RequestOptions) =>
    request<Place[]>(`/places/search?q=${encodeURIComponent(query)}`, options),
  directions: (origin: Place, destination: Place, vehicleType: VehicleType, options?: RequestOptions) =>
    request<DirectionsPlan>("/directions", {
      ...options,
      method: "POST",
      body: JSON.stringify({ origin, destination, vehicle_type: vehicleType }),
    }),
  nationalRisk: () => request<NationalRiskOverview>("/risk/national"),
  weatherSnapshot: () => request<NationalWeatherSnapshot>("/risk/weather-snapshot"),
  weatherRaster: async () => {
    const manifest = await request<WeatherRasterManifest>("/risk/weather-raster");
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

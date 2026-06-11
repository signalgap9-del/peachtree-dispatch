import type {
  DashboardSummary,
  Delivery,
  DeliveryStatus,
  DeliverySummary,
  DirectionsPlan,
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  WeatherRasterManifest,
  NetworkOverview,
  Place,
  VehicleType,
} from "./types";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ detail: response.statusText }));
    throw new Error(body.detail ?? "Request failed");
  }
  return response.json() as Promise<T>;
}

export const api = {
  dashboard: () => request<DashboardSummary>("/dashboard"),
  network: (vehicleType?: VehicleType) =>
    request<NetworkOverview>(`/network${vehicleType ? `?vehicle_type=${vehicleType}` : ""}`),
  searchPlaces: (query: string) => request<Place[]>(`/places/search?q=${encodeURIComponent(query)}`),
  directions: (origin: Place, destination: Place, vehicleType: VehicleType) =>
    request<DirectionsPlan>("/directions", {
      method: "POST",
      body: JSON.stringify({ origin, destination, vehicle_type: vehicleType }),
    }),
  nationalRisk: () => request<NationalRiskOverview>("/risk/national"),
  weatherSnapshot: () => request<NationalWeatherSnapshot>("/risk/weather-snapshot"),
  weatherRaster: () => request<WeatherRasterManifest>("/risk/weather-raster"),
  locationRisk: (place: Place) =>
    request<LocationRisk>("/risk/location", { method: "POST", body: JSON.stringify(place) }),
  deliveries: (status?: DeliveryStatus) =>
    request<DeliverySummary[]>(`/deliveries${status ? `?status=${status}` : ""}`),
  delivery: (id: string) => request<Delivery>(`/deliveries/${id}`),
  transition: (id: string, toStatus: DeliveryStatus) =>
    request<Delivery>(`/deliveries/${id}/events`, {
      method: "POST",
      body: JSON.stringify({
        event_id: `console-${crypto.randomUUID()}`,
        to_status: toStatus,
        source: "operator-console",
      }),
    }),
};

import type {
  DirectionsPlan,
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  WeatherRasterManifest,
  Place,
  SavedPlaceRecord,
  SavedRouteRecord,
  VehicleType,
} from "./types";
import { accessToken } from "./auth";

const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const token = accessToken();
  const response = await fetch(`${API_URL}${path}`, {
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    ...options,
  });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ detail: response.statusText }));
    throw new Error(body.detail ?? "Request failed");
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const api = {
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
  savedPlaces: () => request<SavedPlaceRecord[]>("/me/saved/places"),
  savedRoutes: () => request<SavedRouteRecord[]>("/me/saved/routes"),
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
      }),
    }),
  deleteSavedPlace: (savedItemId: string) =>
    request<void>(`/me/saved/places/${savedItemId}`, { method: "DELETE" }),
  deleteSavedRoute: (savedItemId: string) =>
    request<void>(`/me/saved/routes/${savedItemId}`, { method: "DELETE" }),
};

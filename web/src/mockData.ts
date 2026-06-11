import type { Place } from "./types";

export const places: Record<string, Place> = {
  atlanta: { place_id: "atlanta", display_name: "Atlanta, GA, USA", city: "Atlanta", state: "GA", latitude: 33.749, longitude: -84.388 },
  miami: { place_id: "miami", display_name: "Miami, FL, USA", city: "Miami", state: "FL", latitude: 25.7617, longitude: -80.1918 },
  nashville: { place_id: "nashville", display_name: "Nashville, TN, USA", city: "Nashville", state: "TN", latitude: 36.1627, longitude: -86.7816 },
  seattle: { place_id: "seattle", display_name: "Seattle, WA, USA", city: "Seattle", state: "WA", latitude: 47.6062, longitude: -122.3321 },
};

export const savedItems = [
  { id: "atlanta", kind: "Place", title: "Atlanta, GA", risk: 72, level: "High", change: "Heavy rain in 2h 15m", meta: "Updated 12m ago", action: "View on map" },
  { id: "miami", kind: "Place", title: "Miami, FL", risk: 78, level: "High", change: "Flash flood risk in 1h", meta: "Updated 8m ago", action: "View on map" },
  { id: "atlanta-nashville", kind: "Route", title: "Atlanta → Nashville", risk: 64, level: "Moderate", change: "Storms in 3h 40m", meta: "Updated 15m ago", action: "Compare routes" },
  { id: "i95", kind: "Corridor", title: "I-95 Corridor", risk: 69, level: "Moderate", change: "Flood risk in 2h 50m", meta: "Updated 10m ago", action: "View on map" },
  { id: "seattle", kind: "Place", title: "Seattle, WA", risk: 22, level: "Low", change: "Rain in 6h 30m", meta: "Updated 22m ago", action: "View on map" },
  { id: "seattle-miami", kind: "Route", title: "Seattle → Miami", risk: 58, level: "Moderate", change: "Risk improves in 5h", meta: "Updated 18m ago", action: "Compare routes" },
];

export const changes = [
  { title: "Miami risk is higher", from: "High", to: "Severe", detail: "Heavy rain and coastal flooding risk increase this afternoon.", target: "Miami, FL", source: "NWS · 8:02 AM ET", severity: "severe" },
  { title: "I-95 Corridor risk increased", from: "Moderate", to: "High", detail: "Strong winds and isolated severe storms developing.", target: "I-95 Corridor", source: "NWS · 7:58 AM ET", severity: "high" },
  { title: "Atlanta storms this evening", from: "Low", to: "High", detail: "Severe thunderstorms possible after 5 PM.", target: "Atlanta, GA", source: "NWS · 7:45 AM ET", severity: "high" },
];

export const riskRows = [
  { name: "Atlanta, GA", kind: "City", values: [28, 42, 65, 38] },
  { name: "Miami, FL", kind: "City", values: [76, 86, 92, 80] },
  { name: "I-95 Corridor", kind: "Corridor · 1,920 mi", values: [59, 68, 71, 55] },
  { name: "Seattle, WA", kind: "City", values: [22, 18, 24, 29] },
];

export function riskLevel(score: number) {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}

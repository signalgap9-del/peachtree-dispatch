import type { Place } from "./types";

export const places: Record<string, Place> = {
  atlanta: { place_id: "atlanta", display_name: "Atlanta, GA, USA", city: "Atlanta", state: "GA", latitude: 33.749, longitude: -84.388 },
  miami: { place_id: "miami", display_name: "Miami, FL, USA", city: "Miami", state: "FL", latitude: 25.7617, longitude: -80.1918 },
  nashville: { place_id: "nashville", display_name: "Nashville, TN, USA", city: "Nashville", state: "TN", latitude: 36.1627, longitude: -86.7816 },
  seattle: { place_id: "seattle", display_name: "Seattle, WA, USA", city: "Seattle", state: "WA", latitude: 47.6062, longitude: -122.3321 },
};

export function riskLevel(score: number) {
  if (score >= 80) return "severe";
  if (score >= 55) return "high";
  if (score >= 30) return "moderate";
  return "low";
}

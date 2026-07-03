import type { Page, Route } from "@playwright/test";

import type {
  DirectionsPlan,
  LocationRisk,
  NationalRiskOverview,
  NationalWeatherSnapshot,
  Place,
  SavedPlaceRecord,
  SavedRouteRecord,
  WeatherRasterManifest,
} from "../../src/types";

export const seattle: Place = {
  place_id: "fixture-seattle",
  display_name: "Seattle, WA, United States",
  city: "Seattle",
  state: "WA",
  latitude: 47.6062,
  longitude: -122.3321,
};

export const miami: Place = {
  place_id: "fixture-miami",
  display_name: "Miami Beach, FL, United States",
  city: "Miami Beach",
  state: "FL",
  latitude: 25.7907,
  longitude: -80.13,
};

export const atlanta: Place = {
  place_id: "fixture-atlanta",
  display_name: "Atlanta, GA, United States",
  city: "Atlanta",
  state: "GA",
  latitude: 33.749,
  longitude: -84.388,
};

export const nationalRisk: NationalRiskOverview = {
  generated_at: "2026-06-21T12:00:00Z",
  score: 64,
  level: "HIGH",
  active_alerts: 3,
  severe_alerts: 2,
  alerts_with_geometry: 1,
  by_event: { "Flash Flood Warning": 1, "Severe Thunderstorm Warning": 1 },
  source_status: { nws_alerts: "LIVE" },
  alerts: [
    {
      alert_id: "nws-flash-flood",
      event: "Flash Flood Warning",
      severity: "Severe",
      urgency: "Immediate",
      certainty: "Observed",
      headline: "Official fixture warning for route flooding.",
      area: "Miami-Dade County",
      instruction: "Avoid flooded roadways and follow local guidance.",
      score: 92,
      latitude: 25.76,
      longitude: -80.19,
    },
    {
      alert_id: "nws-thunderstorm",
      event: "Severe Thunderstorm Warning",
      severity: "Moderate",
      urgency: "Expected",
      certainty: "Likely",
      headline: "Fixture thunderstorm warning for dashboard coverage.",
      area: "North Georgia",
      score: 58,
      latitude: 33.74,
      longitude: -84.39,
    },
  ],
};

export const weatherSnapshot: NationalWeatherSnapshot = {
  generated_at: "2026-06-21T12:00:00Z",
  expires_at: "2026-06-21T13:00:00Z",
  model_version: "playwright-contract",
  refresh_minutes: 60,
  coverage: 0.98,
  source_status: { nws: "LIVE", hrrr: "LIVE", mrms: "LIVE" },
  points: [
    {
      id: "miami",
      city: "Miami, FL",
      latitude: 25.76,
      longitude: -80.19,
      temperature_f: 86,
      precipitation_probability: 78,
      wind_speed_mph: 18,
      risk_score: 72,
      risk_level: "HIGH",
      data_status: "LIVE",
      source: "NWS fixture",
    },
    {
      id: "atlanta",
      city: "Atlanta, GA",
      latitude: 33.749,
      longitude: -84.388,
      temperature_f: 81,
      precipitation_probability: 45,
      wind_speed_mph: 12,
      risk_score: 48,
      risk_level: "ELEVATED",
      data_status: "LIVE",
      source: "NOAA fixture",
    },
    {
      id: "seattle",
      city: "Seattle, WA",
      latitude: 47.6062,
      longitude: -122.3321,
      temperature_f: 62,
      precipitation_probability: 22,
      wind_speed_mph: 9,
      risk_score: 21,
      risk_level: "LOW",
      data_status: "LIVE",
      source: "NOAA fixture",
    },
    {
      id: "minneapolis",
      city: "Minneapolis, MN",
      latitude: 44.9778,
      longitude: -93.265,
      temperature_f: 30,
      precipitation_probability: 44,
      wind_speed_mph: 18,
      risk_score: 66,
      risk_level: "HIGH",
      data_status: "LIVE",
      source: "NOAA fixture",
    },
  ],
};

export const weatherRaster: WeatherRasterManifest = {
  generated_at: weatherSnapshot.generated_at,
  expires_at: weatherSnapshot.expires_at,
  layer: "risk",
  source: "playwright",
  url: "",
  bounds: [[-125, 24], [-66, 49]],
  point_count: weatherSnapshot.points.length,
  coverage: weatherSnapshot.coverage,
  model_version: weatherSnapshot.model_version,
};

const mapTileSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256"><rect width="256" height="256" fill="#eef3f8"/><path d="M0 80h256M0 160h256M80 0v256M160 0v256" stroke="#d8e3ee" stroke-width="4"/></svg>`;

export const savedPlaces: SavedPlaceRecord[] = [
  {
    savedItemId: "saved-miami",
    userId: "user-fixture",
    name: "Miami Beach, FL",
    longitude: -80.13,
    latitude: 25.7907,
    currentRiskScore: 72,
  },
  {
    savedItemId: "saved-atlanta",
    userId: "user-fixture",
    name: "Atlanta, GA",
    longitude: -84.388,
    latitude: 33.749,
    currentRiskScore: 48,
  },
];

export const savedRoutes: SavedRouteRecord[] = [
  {
    savedItemId: "saved-route-seattle-miami",
    userId: "user-fixture",
    name: "Seattle to Miami Beach",
    originName: "Seattle, WA, United States",
    destinationName: "Miami Beach, FL, United States",
    vehicleType: "CAR",
    distanceMiles: 3127,
    durationMinutes: 2910,
    climateDelayMinutes: 28,
    riskScore: 34,
    coordinates: [
      [seattle.longitude, seattle.latitude],
      [-106.65, 35.08],
      [miami.longitude, miami.latitude],
    ],
    generatedAt: "2026-06-21T12:00:00Z",
    usualDepartureTime: "08:00",
    riskThreshold: 55,
    monitorEnabled: true,
    lastCheckedAt: "2026-06-21T12:10:00Z",
    activeHazards: ["Scattered storms", "Moderate winds"],
    riskTrend: "IMPROVING",
  },
];

export const locationRisk: LocationRisk = {
  generated_at: "2026-06-21T12:00:00Z",
  place: miami,
  score: 72,
  level: "HIGH",
  summary: "Heavy rain and active flood alerts are increasing travel risk.",
  factors: {
    precipitation: 72,
    flood: 86,
    wind: 28,
    heat: 35,
    active_alerts: 92,
  },
  alerts: [nationalRisk.alerts[0]],
  weather: weatherSnapshot.points[0],
  model_version: "playwright-contract",
  source_status: { weather: "LIVE", nws_alerts: "LIVE" },
};

export const directionsPlan: DirectionsPlan = {
  generated_at: "2026-06-21T12:00:00Z",
  origin: seattle,
  destination: miami,
  vehicle_type: "CAR",
  coordinates: [
    [seattle.longitude, seattle.latitude],
    [-104.99, 39.74],
    [-90.05, 35.15],
    [miami.longitude, miami.latitude],
  ],
  distance_miles: 3127,
  duration_minutes: 2910,
  climate_delay_minutes: 28,
  risk_score: 34,
  weather: weatherSnapshot.points,
  summary: "Seattle to Miami Beach",
  model_version: "playwright-contract",
  decision: {
    action: "TAKE_LOWER_RISK",
    recommended_alternative_id: "lower-risk",
    recommended_label: "Lower weather risk",
    summary: "Lower weather risk is worth the extra time",
    primary_reason: "This route lowers the composite risk by 28 points while staying within the acceptable delay window.",
    tradeoff: "-28 risk / +3 hr 15 min",
    confidence: "HIGH",
    risk_delta: 28,
    time_delta_minutes: 195,
    severity: "MODERATE",
  },
  segments: [
    {
      segment_id: "segment-1",
      label: "WA -> ID",
      risk_score: 18,
      severity: "LOW",
      primary_hazard: "RAIN",
      coverage: 1,
      summary: "low risk from heavy precipitation",
    },
    {
      segment_id: "segment-2",
      label: "ID -> CO",
      risk_score: 32,
      severity: "MODERATE",
      primary_hazard: "WIND",
      coverage: 0.98,
      summary: "moderate risk from crosswinds",
    },
    {
      segment_id: "segment-3",
      label: "GA -> FL",
      risk_score: 62,
      severity: "HIGH",
      primary_hazard: "FLOOD",
      coverage: 0.96,
      summary: "high risk from flood-sensitive rainfall",
    },
  ],
  alternatives: [
    {
      alternative_id: "fastest",
      label: "Fastest",
      coordinates: [
        [seattle.longitude, seattle.latitude],
        [-95.36, 29.76],
        [miami.longitude, miami.latitude],
      ],
      distance_miles: 2895,
      duration_minutes: 2715,
      climate_delay_minutes: 42,
      risk_score: 62,
      data_coverage: 0.94,
      confidence: "HIGH",
      model_version: "playwright-contract",
      source_status: { weather: "LIVE", nws_alerts: "LIVE" },
      weather: weatherSnapshot.points,
      hazards: [{ category: "FLASH_FLOOD", score: 84, samples_affected: 2, summary: "Flood-prone route samples" }],
    },
    {
      alternative_id: "lower-risk",
      label: "Lower weather risk",
      coordinates: [
        [seattle.longitude, seattle.latitude],
        [-106.65, 35.08],
        [-84.38, 33.74],
        [miami.longitude, miami.latitude],
      ],
      distance_miles: 3127,
      duration_minutes: 2910,
      climate_delay_minutes: 28,
      risk_score: 34,
      data_coverage: 0.98,
      confidence: "HIGH",
      model_version: "playwright-contract",
      source_status: { weather: "LIVE", nws_alerts: "LIVE" },
      weather: weatherSnapshot.points,
      hazards: [{ category: "MODERATE_RAIN", score: 34, samples_affected: 1, summary: "Lower precipitation route" }],
    },
    {
      alternative_id: "balanced",
      label: "Balanced",
      coordinates: [
        [seattle.longitude, seattle.latitude],
        [-97.51, 35.46],
        [miami.longitude, miami.latitude],
      ],
      distance_miles: 3006,
      duration_minutes: 2780,
      climate_delay_minutes: 33,
      risk_score: 48,
      data_coverage: 0.96,
      confidence: "HIGH",
      model_version: "playwright-contract",
      source_status: { weather: "LIVE", nws_alerts: "LIVE" },
      weather: weatherSnapshot.points,
      hazards: [{ category: "THUNDERSTORM", score: 48, samples_affected: 1, summary: "Moderate convective risk" }],
    },
  ],
};

export async function installApiMocks(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem("atmospath:language", "en");
  });
  await page.route("https://basemaps.cartocdn.com/**", (route) => route.fulfill({
    contentType: "image/svg+xml",
    body: mapTileSvg,
  }));
  await page.route("**/risk/national", (route) => route.fulfill({ json: nationalRisk }));
  await page.route("**/risk/weather-snapshot", (route) => route.fulfill({ json: weatherSnapshot }));
  await page.route("**/risk/weather-raster", (route) => route.fulfill({ json: weatherRaster }));
  await page.route("**/risk/weather-raster.png", (route) => route.fulfill({
    contentType: "image/svg+xml",
    body: mapTileSvg,
  }));
  await page.route("**/risk/location", (route) => route.fulfill({ json: locationRisk }));
  await page.route("**/me/saved/places**", (route) => {
    if (route.request().method() === "DELETE") return route.fulfill({ status: 204 });
    if (route.request().method() === "POST") return route.fulfill({ status: 201, json: savedPlaces[0] });
    return route.fulfill({ json: savedPlaces });
  });
  await page.route("**/me/saved/routes**", (route) => {
    const url = route.request().url();
    if (route.request().method() === "DELETE") return route.fulfill({ status: 204 });
    if (url.includes("/current-risk")) {
      return route.fulfill({
        json: {
          savedItemId: savedRoutes[0].savedItemId,
          currentRiskScore: savedRoutes[0].riskScore,
          thresholdExceeded: false,
          lastCheckedAt: savedRoutes[0].lastCheckedAt,
          activeHazards: savedRoutes[0].activeHazards,
          riskTrend: savedRoutes[0].riskTrend,
        },
      });
    }
    if (url.includes("/risk-history")) {
      return route.fulfill({
        json: [
          { checkedAt: "2026-06-21T11:40:00Z", riskScore: 42, riskTrend: "WORSENING" },
          { checkedAt: "2026-06-21T12:10:00Z", riskScore: 34, riskTrend: "IMPROVING" },
        ],
      });
    }
    if (route.request().method() === "PATCH") {
      const body = route.request().postDataJSON() as Partial<SavedRouteRecord>;
      return route.fulfill({ json: { ...savedRoutes[0], ...body } });
    }
    if (route.request().method() === "POST") {
      const body = route.request().postDataJSON() as Partial<SavedRouteRecord>;
      return route.fulfill({
        status: 201,
        json: {
          savedItemId: "saved-route-created",
          userId: "user-fixture",
          usualDepartureTime: "08:00",
          riskThreshold: 55,
          monitorEnabled: true,
          lastCheckedAt: "2026-06-21T12:10:00Z",
          activeHazards: [],
          riskTrend: "STABLE",
          ...body,
        },
      });
    }
    return route.fulfill({ json: savedRoutes });
  });
  await page.route("**/places/search**", (route) => route.fulfill({ json: placeSearchResults(route) }));
  await page.route("**/directions", (route) => route.fulfill({ json: directionsPlan }));
}

export async function seedSignedInUser(page: Page) {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({
      sub: "user-fixture",
      email: "driver@example.com",
      exp: Math.floor(Date.now() / 1000) + 3600,
    })).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
    sessionStorage.setItem("atmospath:id-token", `e30.${payload}.sig`);
    sessionStorage.setItem("atmospath:access-token", "fixture-access-token");
  });
}

function placeSearchResults(route: Route) {
  const requestUrl = new URL(route.request().url());
  const query = requestUrl.searchParams.get("q")?.toLowerCase() ?? "";
  if (query.includes("sea")) return [seattle];
  if (query.includes("atl")) return [atlanta];
  if (query.includes("mia") || query.includes("west palm") || query.includes("beach")) return [miami];
  return [miami, atlanta, seattle];
}

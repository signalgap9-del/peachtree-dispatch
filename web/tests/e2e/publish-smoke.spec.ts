import { expect, test } from "@playwright/test";

const nationalRisk = {
  generated_at: "2026-06-21T12:00:00Z",
  score: 64,
  level: "HIGH",
  active_alerts: 1,
  severe_alerts: 1,
  alerts_with_geometry: 0,
  by_event: { "Flash Flood Warning": 1 },
  alerts: [{
    alert_id: "nws-1",
    event: "Flash Flood Warning",
    severity: "Severe",
    urgency: "Immediate",
    certainty: "Observed",
    headline: "Official test fixture for an active NWS warning.",
    area: "Test County",
    score: 92,
  }],
};

const weatherSnapshot = {
  generated_at: "2026-06-21T12:00:00Z",
  expires_at: "2026-06-21T13:00:00Z",
  model_version: "test-fixture",
  refresh_minutes: 60,
  coverage: 1,
  source_status: { nws: "LIVE" },
  points: [{
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
    source: "NWS test fixture",
  }],
};

test.beforeEach(async ({ page }) => {
  await page.route("**/risk/national", (route) => route.fulfill({ json: nationalRisk }));
  await page.route("**/risk/weather-snapshot", (route) => route.fulfill({ json: weatherSnapshot }));
  await page.route("**/risk/weather-raster", (route) => route.fulfill({
    json: {
      generated_at: weatherSnapshot.generated_at,
      expires_at: weatherSnapshot.expires_at,
      layer: "risk",
      source: "test",
      url: "",
      bounds: [],
      point_count: 1,
      coverage: 1,
      model_version: "test-fixture",
    },
  }));
});

test("home uses live API values and opens map search", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("Miami, FL", { exact: true })).toBeVisible();
  await expect(page.getByText("Flash Flood Warning", { exact: true })).toBeVisible();

  const search = page.getByPlaceholder("Search a city, address, highway, or route");
  await search.fill("Miami");
  await search.press("Enter");
  await expect(page).toHaveURL(/\/map\?search=Miami$/);
  await expect(page.getByPlaceholder("Choose destination")).toHaveValue("Miami");
});

test("anonymous saved page shows an honest private-data state", async ({ page }) => {
  await page.goto("/saved");
  await expect(page.getByRole("heading", { name: "Sign in to use your watchlist" })).toBeVisible();
  await expect(page.locator(".saved-grid")).toHaveCount(0);
});

test("alerts render only the live API response", async ({ page }) => {
  await page.goto("/alerts");
  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Official test fixture for an active NWS warning.")).toBeVisible();
  await page.getByRole("button", { name: /Severe only/ }).click();
  await expect(page.getByRole("button", { name: /Show all/ })).toBeVisible();
});

test("header navigation and unavailable local auth are explicit", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Notifications", exact: true }).click();
  await expect(page).toHaveURL(/\/alerts$/);

  await page.getByRole("button", { name: "IN", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Sign-in is available");
});

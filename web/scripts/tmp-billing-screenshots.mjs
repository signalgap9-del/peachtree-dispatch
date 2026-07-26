// Throwaway visual verification for the billing UI (not part of the build).
import { chromium } from "@playwright/test";

const BASE = "http://127.0.0.1:4174";
const OUT = process.env.SCREENSHOT_DIR ?? "./tmp-shots";

const tileSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#e9eef2"/></svg>`;

const accountSummary = {
  user: { userId: "user-fixture", subject: "user-fixture", email: "driver@example.com" },
  workspace: { tenantId: "tenant-fixture", name: "Personal workspace", role: "OWNER" },
  plan: { code: "FREE", status: "ACTIVE", savedRouteHistoryDays: 7, dispatchOptimizerEnabled: false, teamWorkspaceEnabled: false },
  dailyUsage: [
    { feature: "ROUTE_PLAN", label: "Route plans", used: 27, limit: 30, remaining: 3, resetsAt: "2026-07-28T00:00:00Z", exceeded: false },
    { feature: "PLACE_SEARCH", label: "Place searches", used: 18, limit: 100, remaining: 82, resetsAt: "2026-07-28T00:00:00Z", exceeded: false },
    { feature: "LOCATION_RISK", label: "Location risk checks", used: 111, limit: 120, remaining: 9, resetsAt: "2026-07-28T00:00:00Z", exceeded: false },
    { feature: "ALERT_SEARCH", label: "Alert searches", used: 6, limit: 150, remaining: 144, resetsAt: "2026-07-28T00:00:00Z", exceeded: false },
  ],
  savedRoutes: { feature: "SAVED_ROUTE", label: "Saved routes", used: 4, limit: 10, remaining: 6, exceeded: false },
  savedPlaces: { feature: "SAVED_PLACE", label: "Saved places", used: 7, limit: 25, remaining: 18, exceeded: false },
  readiness: [],
};

const proSubscription = { plan: "PRO", status: "ACTIVE", currentPeriodEnd: "2026-08-21T00:00:00Z", cancelAtPeriodEnd: false };
const freeSubscription = { plan: "FREE", status: "ACTIVE", currentPeriodEnd: null, cancelAtPeriodEnd: false };
const cancellingSubscription = { plan: "PRO", status: "ACTIVE", currentPeriodEnd: "2026-08-21T00:00:00Z", cancelAtPeriodEnd: true };

async function mockApi(page, { subscription } = {}) {
  await page.route("https://basemaps.cartocdn.com/**", (route) => route.fulfill({ contentType: "image/svg+xml", body: tileSvg }));
  await page.route("**/risk/national", (route) => route.fulfill({ json: { generated_at: "2026-07-27T00:00:00Z", score: 41, level: "ELEVATED", active_alerts: 12, severe_alerts: 3, alerts_with_geometry: 0, alerts: [], by_event: {} } }));
  await page.route("**/risk/weather-snapshot", (route) => route.fulfill({ json: { generated_at: "2026-07-27T00:00:00Z", expires_at: "2026-07-27T01:00:00Z", model_version: "shots", refresh_minutes: 60, coverage: 0.97, points: [{ id: "atlanta", city: "Atlanta, GA", latitude: 33.749, longitude: -84.388, temperature_f: 81, precipitation_probability: 45, wind_speed_mph: 12, risk_score: 48, risk_level: "ELEVATED", data_status: "LIVE" }], source_status: {} } }));
  await page.route("**/risk/weather-raster", (route) => route.fulfill({ json: { generated_at: "2026-07-27T00:00:00Z", expires_at: "2026-07-27T01:00:00Z", layer: "risk", source: "shots", url: "", bounds: [[-125, 24], [-66, 49]], point_count: 1, coverage: 0.97, model_version: "shots" } }));
  await page.route("**/me/account", (route) => route.fulfill({ json: accountSummary }));
  await page.route("**/billing/subscription", (route) => route.fulfill(subscription ? { json: subscription } : { status: 404, json: { error: { code: "NOT_FOUND", message: "Billing not enabled" } } }));
  await page.route("**/billing/portal", (route) => route.fulfill({ json: { portalUrl: "about:blank" } }));
}

async function seedUser(page) {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({ sub: "user-fixture", email: "driver@example.com", exp: Math.floor(Date.now() / 1000) + 3600 })).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
    sessionStorage.setItem("freightscaler:id-token", `e30.${payload}.sig`);
    sessionStorage.setItem("freightscaler:access-token", "fixture-access-token");
  });
}

const viewports = [
 { name: "desktop", width: 1440, height: 900 },
 { name: "mobile", width: 375, height: 812 },
];

const browser = await chromium.launch();
const shots = [];

async function capture(label, url, { subscription, signedIn } = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  if (signedIn) await seedUser(page);
  await mockApi(page, { subscription });
  for (const vp of viewports) {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await page.goto(`${BASE}${url}`, { waitUntil: "networkidle" });
    await page.waitForTimeout(400);
    const file = `${OUT}/${label}-${vp.name}.png`;
    await page.screenshot({ path: file, fullPage: true });
    shots.push(file);
  }
  await context.close();
}

await capture("pricing-anon", "/app/pricing");
await capture("pricing-free", "/app/pricing", { signedIn: true, subscription: freeSubscription });
await capture("account-pro", "/app/account", { signedIn: true, subscription: proSubscription });
await capture("account-pro-cancelling", "/app/account", { signedIn: true, subscription: cancellingSubscription });
await capture("account-free", "/app/account", { signedIn: true, subscription: freeSubscription });
await capture("account-billing-pending", "/app/account", { signedIn: true });

await browser.close();
console.log(shots.join("\n"));

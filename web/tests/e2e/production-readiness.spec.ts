import { expect, test, type Page } from "@playwright/test";

import { directionsPlan, installApiMocks, miami, seattle } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("critical pages load with a clean browser console", async ({ page }) => {
  const consoleIssues = collectConsoleIssues(page);
  const pageErrors = collectPageErrors(page);

  for (const path of ["/", "/map", "/dashboard", "/saved", "/alerts"]) {
    await page.goto(path);
    await expect(page.locator(".product-app")).toBeVisible();
  }

  expect(pageErrors).toEqual([]);
  expect(consoleIssues).toEqual([]);
});

test("degraded live-data state is explicit and does not show fabricated alerts", async ({ page }) => {
  await page.route("**/risk/national", (route) => route.fulfill({ status: 503, json: { detail: "NWS unavailable" } }));
  await page.route("**/risk/weather-snapshot", (route) => route.fulfill({ status: 503, json: { detail: "NOAA unavailable" } }));
  await page.route("**/risk/weather-raster", (route) => route.fulfill({ status: 503, json: { detail: "Raster unavailable" } }));

  await page.goto("/alerts");

  await expect(page.getByText("Live-data service unavailable")).toBeVisible();
  await expect(page.getByText("No live alert records available")).toBeVisible();
  await expect(page.getByText("Flash Flood Warning")).toHaveCount(0);
});

test("directions request preserves the API contract used by the risk engine", async ({ page }) => {
  const requests: unknown[] = [];
  await page.route("**/directions", async (route) => {
    requests.push(route.request().postDataJSON());
    await route.fulfill({ json: directionsPlan });
  });

  await page.goto("/map");
  await page.getByRole("button", { name: "Truck" }).click();
  await page.getByPlaceholder("Choose starting point").fill("Seattle");
  await page.getByRole("button", { name: /Seattle/ }).click();
  await page.getByPlaceholder("Choose destination").fill("Miami Beach");
  await page.getByRole("button", { name: /Miami Beach/ }).click();

  await expect(page.getByRole("heading", { name: "Seattle to Miami Beach" })).toBeVisible();
  expect(requests).toEqual([
    {
      origin: seattle,
      destination: miami,
      vehicle_type: "TRUCK",
    },
  ]);
});

test("mobile viewport keeps the main navigation and map actions usable", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("button", { name: /AtmosPath home/ })).toBeVisible();
  await expect(page.getByRole("button", { name: "KR", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: /Continue with Google/ })).toBeVisible();
  await page.getByRole("button", { name: /Map/ }).click();
  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByPlaceholder("Choose destination")).toBeVisible();

  const riskToggle = page.getByRole("button", { name: "Toggle nationwide risk heatmap" });
  const weatherToggle = page.getByRole("button", { name: "Toggle weather layer" });

  await expect(riskToggle).toBeVisible();
  await expect(weatherToggle).toBeVisible();
  await riskToggle.click();
  await weatherToggle.click();

  await expect(riskToggle).not.toHaveClass(/active/);
  await expect(weatherToggle).not.toHaveClass(/active/);
});

function collectConsoleIssues(page: Page) {
  const issues: string[] = [];
  page.on("console", (message) => {
    if (["error", "warning"].includes(message.type())) {
      const text = message.text();
      if (!isBrowserRenderingNoise(text)) issues.push(`${message.type()}: ${text}`);
    }
  });
  return issues;
}

function collectPageErrors(page: Page) {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  return errors;
}

function isBrowserRenderingNoise(text: string) {
  return [
    "Automatic fallback to software WebGL has been deprecated",
    "GPU stall due to ReadPixels",
    "GL Driver Message",
    "AJAXError: Failed to fetch (0): https://basemaps.cartocdn.com/",
    "TypeError: Failed to fetch",
  ].some((knownNoise) => text.includes(knownNoise));
}

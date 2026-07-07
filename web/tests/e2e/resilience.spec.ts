import { expect, test } from "@playwright/test";

import { installApiMocks, nationalRisk } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("public live-data reads retry transient failures before degrading the app", async ({ page }) => {
  let nationalAttempts = 0;
  await page.route("**/risk/national", (route) => {
    nationalAttempts += 1;
    if (nationalAttempts === 1) {
      return route.fulfill({ status: 503, json: { detail: "Temporary NWS outage" } });
    }
    return route.fulfill({ json: nationalRisk });
  });

  await page.goto("/status");

  await expect(page.getByRole("heading", { name: "Operational status" })).toBeVisible();
  await expect(page.getByText("All primary live feeds loaded")).toBeVisible();
  await expect(page.getByText("API resiliency")).toBeVisible();
  await expect(page.getByText("Retries this session")).toBeVisible();
  await expect(page.getByText("1", { exact: true })).toBeVisible();
  expect(nationalAttempts).toBe(2);
});

test("safe public stale cache keeps the app usable and visible during source failure", async ({ page }) => {
  await page.addInitScript((cachedNationalRisk) => {
    localStorage.setItem("atmospath:resilience-cache:risk:national", JSON.stringify({
      version: 1,
      cachedAt: new Date().toISOString(),
      data: cachedNationalRisk,
    }));
  }, nationalRisk);
  await page.route("**/risk/national", (route) => route.fulfill({ status: 503, json: { detail: "NWS unavailable" } }));

  await page.goto("/");

  await expect(page.getByRole("status", { name: "Connection resilience" })).toBeVisible();
  await expect(page.getByText("Showing recently cached live data")).toBeVisible();

  await page.goto("/status");

  await expect(page.getByText("Stale fallbacks")).toBeVisible();
  await expect(page.getByText("risk:national", { exact: true })).toBeVisible();
});

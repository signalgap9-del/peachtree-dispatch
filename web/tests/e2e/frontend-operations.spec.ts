import { expect, test } from "@playwright/test";

import { installApiMocks, seedSignedInUser } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("status page exposes source health, frontend performance, and client issue log", async ({ page }) => {
  await page.addInitScript(() => {
    sessionStorage.setItem("atmospath:perf-snapshot", JSON.stringify({
      path: "/dashboard",
      at: "2026-07-04T12:00:00Z",
      lcpMs: 1200,
      cls: 0.02,
      inpMs: 96,
      navLoadMs: 840,
    }));
    sessionStorage.setItem("atmospath:client-issues", JSON.stringify([
      {
        id: "issue-fixture",
        kind: "api_error",
        message: "Fixture API failure for observability contract",
        path: "/map",
        at: "2026-07-04T12:01:00Z",
        details: { requestId: "req_fixture" },
      },
    ]));
  });

  await page.goto("/status");

  await expect(page.getByRole("heading", { name: "Operational status" })).toBeVisible();
  await expect(page.getByText("NWS alerts")).toBeVisible();
  await expect(page.getByText("3 active alerts / 2 severe")).toBeVisible();
  await expect(page.getByText("Performance snapshot")).toBeVisible();
  await expect(page.locator(".performance-grid article")).toHaveCount(4);
  await expect(page.locator(".performance-grid").getByText("LCP")).toBeVisible();
  await expect(page.locator(".performance-grid").getByText("good <= 2,500 ms")).toBeVisible();
  await expect(page.getByText("Fixture API failure for observability contract")).toBeVisible();
  await expect(page.getByText("request req_fixture")).toBeVisible();
});

test("usage page links operators to status checks", async ({ page }) => {
  await seedSignedInUser(page);
  await page.goto("/usage");

  await page.getByRole("button", { name: "Operational status" }).click();

  await expect(page).toHaveURL(/\/status$/);
  await expect(page.getByRole("heading", { name: "Operational status" })).toBeVisible();
});

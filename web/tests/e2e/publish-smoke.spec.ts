import { expect, test } from "@playwright/test";

import { installApiMocks, seedSignedInUser } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("home page renders live data and primary navigation works", async ({ page }) => {
  await page.goto("/");

  await expect(page.locator(".live-priority").getByText("Miami, FL", { exact: true })).toBeVisible();
  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /Alerts\s+3/ })).toBeVisible();
  await expect(page.locator(".outlook-card .risk-map-canvas")).toBeVisible();
  await expect(page.locator(".outlook-card .live-risk-point")).toHaveCount(4);
  await expect(page.locator(".outlook-card .live-alert-point")).toHaveCount(2);
  await expect(page.getByText("Winter road risk")).toBeVisible();
  await expect(page.locator(".winter-risk-card").getByText("Minneapolis, MN")).toBeVisible();
  await expect(page.getByText("Operational intelligence")).toBeVisible();

  await page.getByRole("button", { name: /Open dashboard/ }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByRole("heading", { name: "National risk dashboard" })).toBeVisible();
  await expect(page.getByText("Coverage 98%")).toBeVisible();

  await page.getByRole("button", { name: /Plan route/ }).first().click();
  await expect(page).toHaveURL(/\/directions$/);
  await expect(page.getByPlaceholder("Choose destination")).toBeVisible();
});

test("home search deep-links into the map destination field", async ({ page }) => {
  await page.goto("/");

  const search = page.getByPlaceholder("Search cities, addresses, highways, or routes");
  await search.fill("Miami");
  await search.press("Enter");

  await expect(page).toHaveURL(/\/map\?search=Miami$/);
  await expect(page.getByPlaceholder("Choose destination")).toHaveValue("Miami");
});

test("alerts page filters severe events without fake fallback data", async ({ page }) => {
  await page.goto("/alerts");

  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Severe Thunderstorm Warning", { exact: true }).first()).toBeVisible();
  await page.getByPlaceholder("Search flood, heat, Miami, I-95, county...").fill("Miami");
  await expect(page).toHaveURL(/q=Miami/);
  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Severe Thunderstorm Warning", { exact: true }).first()).toBeHidden();
  await expect(page.getByText("Related weather signals")).toBeVisible();

  await page.getByPlaceholder("Search flood, heat, Miami, I-95, county...").fill("");
  await page.getByRole("button", { name: "Storm", exact: true }).click();
  await expect(page).toHaveURL(/category=storm/);
  await expect(page.getByText("Severe Thunderstorm Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeHidden();

  await page.getByRole("button", { name: "All", exact: true }).click();
  await page.getByRole("button", { name: /Severe only/ }).click();

  await expect(page.getByRole("button", { name: /Show all/ })).toBeVisible();
  await expect(page.getByText("Flash Flood Warning", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Severe Thunderstorm Warning", { exact: true }).first()).toBeHidden();
});

test("alerts page shows signed-in route impact", async ({ page }) => {
  await seedSignedInUser(page);
  await page.goto("/alerts");

  await expect(page.getByText("Route impact")).toBeVisible();
  await expect(page.getByRole("button", { name: /Seattle to Miami Beach/ })).toBeVisible();

  await page.getByRole("button", { name: /Seattle to Miami Beach/ }).click();
  await expect(page).toHaveURL(/\/directions\?origin=/);
});

test("language toggle and unavailable auth explain themselves", async ({ page }) => {
  await page.goto("/");

  await page.getByRole("button", { name: "KR", exact: true }).click();
  await expect(page.getByRole("button", { name: "EN", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: /날씨를 고려해 경로를 계획하세요/ })).toBeVisible();
  await expect(page.evaluate(() => localStorage.getItem("atmospath:language"))).resolves.toBe("ko");

  await page.getByRole("button", { name: "EN", exact: true }).click();
  await expect(page.getByRole("heading", { name: /Plan routes around weather risk/ })).toBeVisible();

  await expect(page.getByRole("button", { name: /Continue with Google/ })).toBeVisible();
  await page.getByRole("button", { name: /Continue with Google/ }).click();
  await expect(page.getByRole("status")).toContainText("OAuth secrets are not configured");

  await page.getByRole("button", { name: "IN", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Sign-in is available in the deployed preview");
});

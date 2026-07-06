import { expect, test } from "@playwright/test";

import { installApiMocks, seedSignedInUser } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("anonymous users can compare plans but not see tenant usage", async ({ page }) => {
  await page.goto("/usage");

  await expect(page.getByRole("heading", { name: "Sign in to view usage" })).toBeVisible();
  await page.getByRole("button", { name: "Compare plans" }).click();
  await expect(page).toHaveURL(/\/pricing$/);
  await expect(page.getByRole("heading", { name: "Plans without billing lock-in" })).toBeVisible();
  await expect(page.getByText("Billing disabled").first()).toBeVisible();
});

test("signed-in users see plan limits, usage meters, and readiness signals", async ({ page }) => {
  await seedSignedInUser(page);

  await page.goto("/usage");

  await expect(page.getByRole("heading", { name: "Usage and operations" })).toBeVisible();
  await expect(page.getByText("FREE").first()).toBeVisible();
  await expect(page.getByText("Route plans")).toBeVisible();
  await expect(page.getByText("8 used / 30 limit")).toBeVisible();
  await expect(page.getByText("Saved routes")).toBeVisible();
  await expect(page.getByText("1 used / 10 limit")).toBeVisible();
  await expect(page.getByText("Plan and usage limits")).toBeVisible();
  await expect(page.getByText("ENFORCED")).toBeVisible();

  await page.getByRole("button", { name: "Manage watchlist" }).click();
  await expect(page).toHaveURL(/\/saved$/);
  await expect(page.locator(".collection-insight")).toContainText("FREE");
  await expect(page.locator(".collection-insight")).toContainText("1/10");
});

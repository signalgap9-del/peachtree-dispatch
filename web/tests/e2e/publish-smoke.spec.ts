import { expect, test } from "@playwright/test";

test("home search opens the map with the destination query", async ({ page }) => {
  await page.goto("/");
  await page.getByPlaceholder("Search for a city, address, highway, or route").fill("Miami");
  await page.getByPlaceholder("Search for a city, address, highway, or route").press("Enter");

  await expect(page).toHaveURL(/\/map\?search=Miami$/);
  await expect(page.getByPlaceholder("Choose destination")).toHaveValue("Miami");
});

test("saved page search and sorting work", async ({ page }) => {
  await page.goto("/saved");
  await page.getByPlaceholder("Search saved places & routes...").fill("Seattle");
  const savedGrid = page.locator(".saved-grid");
  await expect(savedGrid.getByText("Seattle, WA", { exact: true })).toBeVisible();
  await expect(savedGrid.getByText("Atlanta, GA", { exact: true })).not.toBeVisible();

  await page.getByRole("button", { name: "Highest risk" }).click();
  await expect(page.getByRole("button", { name: "Highest risk" })).toHaveClass(/active/);
});

test("alerts can switch scope and severity", async ({ page }) => {
  await page.goto("/alerts");
  await page.getByRole("button", { name: "Nationwide", exact: true }).click();
  await page.getByRole("button", { name: "All severities", exact: true }).click();

  await expect(page.getByRole("button", { name: "Severe only", exact: true })).toBeVisible();
});

test("header actions navigate or explain unavailable account features", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Notifications", exact: true }).click();
  await expect(page).toHaveURL(/\/alerts$/);

  await page.getByRole("button", { name: "AB", exact: true }).click();
  await expect(page.getByRole("status")).toContainText("Guest portfolio session");
});

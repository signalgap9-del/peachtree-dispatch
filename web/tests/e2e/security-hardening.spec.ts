import { expect, test } from "@playwright/test";

import { directionsPlan, installApiMocks } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("map markers render provider text without interpreting HTML", async ({ page }) => {
  const maliciousPlan = structuredClone(directionsPlan);
  maliciousPlan.origin.display_name = "<img src=x onerror=window.__xss='origin'>Origin";
  maliciousPlan.destination.display_name = "<svg onload=window.__xss='destination'></svg>Destination";
  maliciousPlan.weather[0].city = "<img src=x onerror=window.__xss='weather'>Miami";

  await page.route("**/directions", (route) => route.fulfill({ json: maliciousPlan }));
  await page.goto("/map");
  await page.getByPlaceholder("Choose starting point").fill("Seattle");
  await page.getByRole("button", { name: /Seattle/ }).click();
  await page.getByPlaceholder("Choose destination").fill("Miami Beach");
  await page.getByRole("button", { name: /Miami Beach/ }).click();

  await expect(page.locator(".place-marker img, .place-marker svg, .weather-bubble img")).toHaveCount(0);
  await expect(page.locator(".place-marker").first()).toContainText("<img src=x");
  await expect(page.locator(".weather-bubble").first()).toContainText("<img src=x");
  await expect(page.evaluate(() => (window as Window & { __xss?: string }).__xss)).resolves.toBeUndefined();
});

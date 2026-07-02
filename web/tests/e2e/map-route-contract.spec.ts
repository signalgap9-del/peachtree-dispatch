import { expect, test } from "@playwright/test";

import { installApiMocks, seedSignedInUser } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("map search calculates route alternatives and saves the selected route", async ({ page }) => {
  await seedSignedInUser(page);
  const savedRouteRequests: unknown[] = [];
  await page.route("**/me/saved/routes**", (route) => {
    if (route.request().method() === "POST") {
      savedRouteRequests.push(route.request().postDataJSON());
    }
    return route.fallback();
  });

  await page.goto("/map?search=Miami");

  await expect(page.getByPlaceholder("Choose destination")).toHaveValue("Miami");
  await page.getByPlaceholder("Choose destination").fill("Miami Beach");
  await page.getByRole("button", { name: /Miami Beach/ }).click();

  await page.getByPlaceholder("Choose starting point").fill("Seattle");
  await page.getByRole("button", { name: /Seattle/ }).click();

  await expect(page.getByRole("heading", { name: "Seattle to Miami Beach" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Fastest/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Lower weather risk/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Balanced/ })).toBeVisible();
  await expect(page.getByText("Risk by route checkpoint")).toBeVisible();

  await page.getByRole("button", { name: "Why these routes?" }).click();
  await expect(page.getByText("Alternatives balance travel time with live precipitation")).toBeVisible();

  await page.getByRole("button", { name: /Fastest/ }).click();
  await expect(page.getByRole("heading", { name: "Fastest route" })).toBeVisible();

  await page.getByRole("button", { name: "Save this trip" }).click();
  await expect(page).toHaveURL(/\/saved$/);
  expect(savedRouteRequests).toEqual([
    expect.objectContaining({
      name: "Seattle to Miami Beach",
      originName: "Seattle, WA, United States",
      destinationName: "Miami Beach, FL, United States",
      vehicleType: "CAR",
      riskScore: 62,
    }),
  ]);
});

test("map controls toggle weather and risk layers without leaving the page", async ({ page }) => {
  await page.goto("/map");

  await page.getByRole("button", { name: "Toggle nationwide risk heatmap" }).click();
  await page.getByRole("button", { name: "Toggle weather layer" }).click();
  await page.getByRole("button", { name: "Show United States" }).click();

  await expect(page).toHaveURL(/\/map$/);
  await expect(page.getByText("Live U.S. risk")).toBeVisible();
});

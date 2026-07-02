import { expect, test } from "@playwright/test";

import { installApiMocks, seedSignedInUser } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("anonymous saved page asks for sign-in instead of showing sample records", async ({ page }) => {
  await page.goto("/saved");

  await expect(page.getByRole("heading", { name: "Sign in to use your watchlist" })).toBeVisible();
  await expect(page.locator(".saved-grid")).toHaveCount(0);

  await page.locator(".saved-main").getByRole("button", { name: /Continue with Google/ }).click();
  await expect(page.getByRole("status")).toContainText("OAuth secrets are not configured");
});

test("signed-in saved page loads private records through the platform API", async ({ page }) => {
  await seedSignedInUser(page);

  let placesAuthorizationHeader = "";
  let routesAuthorizationHeader = "";
  let deletedRoute = "";
  await page.route("**/me/saved/places**", (route) => {
    placesAuthorizationHeader = route.request().headers().authorization ?? "";
    return route.fallback();
  });
  await page.route("**/me/saved/routes**", (route) => {
    routesAuthorizationHeader = route.request().headers().authorization ?? "";
    if (route.request().method() === "DELETE") {
      deletedRoute = route.request().url();
    }
    return route.fallback();
  });

  await page.goto("/saved");

  await expect(page.getByText("Seattle to Miami Beach").first()).toBeVisible();
  await expect(page.getByText("Miami Beach, FL").first()).toBeVisible();
  await expect(page.getByText("Atlanta, GA").first()).toBeVisible();
  await expect(page.getByText("Saved route · CAR").first()).toBeVisible();
  await expect(page.getByText("Saved place").first()).toBeVisible();
  expect(placesAuthorizationHeader).toBe("Bearer fixture-access-token");
  expect(routesAuthorizationHeader).toBe("Bearer fixture-access-token");

  await page.getByPlaceholder("Search saved routes and places...").fill("Atlanta");
  await expect(page.getByText("Atlanta, GA").first()).toBeVisible();
  await expect(page.getByText("Miami Beach, FL").first()).toBeHidden();

  await page.locator(".saved-grid").getByRole("button").filter({ hasText: "Atlanta, GA" }).click();
  await expect(page).toHaveURL(/\/map\?search=Atlanta%2C%20GA$/);

  await page.goto("/saved");
  await page.getByRole("button", { name: "Delete saved item" }).click();
  await expect(page.getByText("Seattle to Miami Beach").first()).toBeHidden();
  expect(deletedRoute).toContain("/me/saved/routes/saved-route-seattle-miami");
});

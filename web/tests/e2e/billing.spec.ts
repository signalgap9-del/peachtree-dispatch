import { expect, test, type Page } from "@playwright/test";

import { accountSummary, installApiMocks } from "./fixtures";

const CHECKOUT_URL = "https://checkout.lemonsqueezy.test/pay/fixture-checkout";
const PORTAL_URL = "https://billing.lemonsqueezy.test/portal/fixture-portal";

const freeSubscription = {
  plan: "FREE",
  status: "ACTIVE",
  currentPeriodEnd: null,
  cancelAtPeriodEnd: false,
};

const proSubscription = {
  plan: "PRO",
  status: "ACTIVE",
  currentPeriodEnd: "2026-08-21T00:00:00Z",
  cancelAtPeriodEnd: false,
};

const billingNotFound = {
  status: 404,
  json: { error: { code: "NOT_FOUND", message: "Billing is not enabled" } },
};

// The shared fixtures predate the FreightScaler storage namespace, so seed
// tokens with the keys auth.ts actually reads.
async function seedSignedInUser(page: Page) {
  await page.addInitScript(() => {
    const payload = btoa(JSON.stringify({
      sub: "user-fixture",
      email: "driver@example.com",
      exp: Math.floor(Date.now() / 1000) + 3600,
    })).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
    sessionStorage.setItem("freightscaler:id-token", `e30.${payload}.sig`);
    sessionStorage.setItem("freightscaler:access-token", "fixture-access-token");
  });
}

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
  // Hosted checkout and portal destinations are external; stub them so the
  // redirect stays inside the test.
  await page.route("https://checkout.lemonsqueezy.test/**", (route) =>
    route.fulfill({ contentType: "text/html", body: "<html><body>checkout stub</body></html>" }));
  await page.route("https://billing.lemonsqueezy.test/**", (route) =>
    route.fulfill({ contentType: "text/html", body: "<html><body>portal stub</body></html>" }));
});

test("anonymous visitors can compare plans, and upgrading asks them to sign in", async ({ page }) => {
  await page.goto("/pricing");

  await expect(page.getByRole("heading", { name: "Choose your plan" })).toBeVisible();
  await expect(page.getByText("$19", { exact: true })).toBeVisible();
  await expect(page.getByText("Most popular")).toBeVisible();

  await page.getByRole("button", { name: "Upgrade to Pro" }).click();
  await expect(page.getByText("Sign in to upgrade")).toBeVisible();
});

test("signed-in free users are redirected to hosted checkout", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) => route.fulfill({ json: freeSubscription }));
  let checkoutBody: unknown = null;
  let authorization = "";
  await page.route("**/billing/checkout", (route) => {
    checkoutBody = route.request().postDataJSON();
    authorization = route.request().headers().authorization ?? "";
    return route.fulfill({ json: { checkoutUrl: CHECKOUT_URL } });
  });

  await page.goto("/pricing");
  await page.getByRole("button", { name: "Upgrade to Pro" }).click();

  await expect(page).toHaveURL(CHECKOUT_URL);
  expect(checkoutBody).toEqual({});
  expect(authorization).toBe("Bearer fixture-access-token");
});

test("checkout degrades to coming-soon when billing is not deployed", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) => route.fulfill(billingNotFound));
  await page.route("**/billing/checkout", (route) => route.fulfill(billingNotFound));

  await page.goto("/pricing");

  await expect(page.getByRole("button", { name: "Checkout coming soon" })).toBeDisabled();
  await expect(page.getByText("Billing goes live shortly")).toBeVisible();
});

test("account page shows the Pro subscription and opens the billing portal", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) => route.fulfill({ json: proSubscription }));
  await page.route("**/billing/portal", (route) => route.fulfill({ json: { portalUrl: PORTAL_URL } }));

  await page.goto("/account");

  await expect(page.getByRole("heading", { name: "Account" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Pro" })).toBeVisible();
  await expect(page.getByText("Active")).toBeVisible();
  await expect(page.getByText("Aug 21, 2026")).toBeVisible();
  await expect(page.locator(".primary-nav .pro-flag")).toHaveText("Pro");

  await page.getByRole("button", { name: "Manage subscription" }).click();
  await expect(page).toHaveURL(PORTAL_URL);
});

test("account page flags a scheduled cancellation", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) =>
    route.fulfill({ json: { ...proSubscription, cancelAtPeriodEnd: true } }));

  await page.goto("/account");

  await expect(page.getByText("Plan ends")).toBeVisible();
  await expect(page.getByText("Cancellation scheduled")).toBeVisible();
});

test("free account page offers the upgrade path and today's usage", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) => route.fulfill({ json: freeSubscription }));

  await page.goto("/account");

  await expect(page.getByRole("heading", { name: "Free" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Upgrade to Pro" })).toBeVisible();
  await expect(page.getByText("8 used / 30 limit")).toBeVisible();

  await page.getByRole("button", { name: "See Pro plans" }).click();
  await expect(page).toHaveURL(/\/pricing$/);
});

test("account page falls back gracefully while billing is not deployed", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/billing/subscription", (route) => route.fulfill(billingNotFound));

  await page.goto("/account");

  await expect(page.getByText("Self-serve billing opens soon")).toBeVisible();
  await expect(page.getByText("8 used / 30 limit")).toBeVisible();
});

test("anonymous visitors are asked to sign in on the account page", async ({ page }) => {
  await page.goto("/account");
  await expect(page.getByRole("heading", { name: "Sign in to manage your account" })).toBeVisible();
});

test("quota-exceeded route errors show one dismissible upgrade prompt", async ({ page }) => {
  await page.route("**/directions", (route) =>
    route.fulfill({
      status: 429,
      json: { error: { code: "QUOTA_EXCEEDED", message: "Plan limit reached", requestId: "req-quota-1" } },
    }));

  await page.goto("/directions?origin=Seattle&destination=Miami");

  // Place resolution + directions round trip can be slow under full-suite load.
  await expect(page.getByText("Daily free limit reached")).toBeVisible({ timeout: 15000 });

  await page.getByRole("button", { name: "Dismiss upgrade prompt" }).click();
  await expect(page.getByText("Daily free limit reached")).not.toBeVisible();
});

test("upgrade prompt links from the quota error to the pricing page", async ({ page }) => {
  await page.route("**/directions", (route) =>
    route.fulfill({
      status: 429,
      json: { error: { code: "QUOTA_EXCEEDED", message: "Plan limit reached" } },
    }));

  await page.goto("/directions?origin=Seattle&destination=Miami");

  await expect(page.getByText("Daily free limit reached")).toBeVisible({ timeout: 15000 });
  await page.getByRole("button", { name: "Compare plans" }).click();
  await expect(page).toHaveURL(/\/pricing$/);
});

test("usage page shows the upgrade prompt when a meter is exceeded", async ({ page }) => {
  await seedSignedInUser(page);
  await page.route("**/me/account", (route) =>
    route.fulfill({
      json: {
        ...accountSummary,
        dailyUsage: accountSummary.dailyUsage.map((usage) =>
          usage.feature === "ROUTE_PLAN" ? { ...usage, used: 30, remaining: 0, exceeded: true } : usage),
      },
    }));

  await page.goto("/usage");

  await expect(page.getByText("Daily free limit reached")).toBeVisible();
  await page.getByRole("button", { name: "Dismiss upgrade prompt" }).click();
  await expect(page.getByText("Daily free limit reached")).not.toBeVisible();
});

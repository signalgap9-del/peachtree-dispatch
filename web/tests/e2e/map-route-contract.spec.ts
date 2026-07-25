import { expect, test, type Page } from "@playwright/test";

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
  await expect(page.getByLabel("Recommended route decision")).toContainText("Lower weather risk");
  await expect(page.getByLabel("Recommended route decision")).toContainText("Risk -28");
  await expect(page.getByRole("button", { name: /Fastest/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Lower weather risk/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Balanced/ })).toBeVisible();
  await expect(page.getByLabel("Risk by route segment")).toBeVisible();
  await expect(page.getByText("Risk by route checkpoint")).toBeVisible();

  await page.getByRole("button", { name: "Why these routes?" }).click();
  await expect(page.getByText("Alternatives balance travel time with live precipitation")).toBeVisible();

  await page.getByRole("button", { name: /Fastest/ }).click();
  await expect(page.getByRole("heading", { name: "Fastest route" })).toBeVisible();
  await expect(page).toHaveURL(/\/directions\?origin=/);

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

test("layer control switches actually show and hide map layers", async ({ page }) => {
  await page.goto("/map");
  await waitForMapLayer(page, "risk-heat");
  await waitForMapLayer(page, "alert-markers");

  const panel = page.getByRole("group", { name: "Map layers" });
  await expect(panel).toBeVisible();

  const heatmap = panel.getByRole("switch", { name: "Risk heatmap" });
  await expect(heatmap).toHaveAttribute("aria-checked", "true");
  await heatmap.click();
  await expect(heatmap).toHaveAttribute("aria-checked", "false");
  await expect.poll(() => layerVisibility(page, "risk-heat")).toBe("none");
  await heatmap.click();
  await expect(heatmap).toHaveAttribute("aria-checked", "true");
  await expect.poll(() => layerVisibility(page, "risk-heat")).toBe("visible");

  const zones = panel.getByRole("switch", { name: "Alert zones" });
  await zones.click();
  await expect.poll(() => layerVisibility(page, "risk-alert-fill")).toBe("none");
  await expect.poll(() => layerVisibility(page, "risk-alert-outline")).toBe("none");
  await zones.click();
  await expect.poll(() => layerVisibility(page, "risk-alert-fill")).toBe("visible");

  const markers = panel.getByRole("switch", { name: "Alert markers" });
  await expect(page.locator(".alert-pulse-marker").first()).toBeVisible();
  await markers.click();
  await expect.poll(() => layerVisibility(page, "alert-markers")).toBe("none");
  await expect(page.locator(".alert-pulse-marker").first()).toBeHidden();
  await markers.click();
  await expect.poll(() => layerVisibility(page, "alert-markers")).toBe("visible");
  await expect(page.locator(".alert-pulse-marker").first()).toBeVisible();
});

test("severe alert marker opens a detail popup", async ({ page }) => {
  await page.goto("/map");
  await waitForMapLayer(page, "alert-markers");

  const box = await page.locator(".network-map").boundingBox();
  const point = await page.evaluate(() => {
    const host = document.querySelector(".network-map") as { __atmospathMap?: { project: (lngLat: [number, number]) => { x: number; y: number } } } | null;
    return host?.__atmospathMap?.project([-80.19, 25.76]) ?? null;
  });
  if (!box || !point) throw new Error("Map container is not ready");
  await page.mouse.click(box.x + point.x, box.y + point.y);

  const popup = page.locator(".atmospath-popup");
  await expect(popup).toBeVisible();
  await expect(popup).toContainText("Flash Flood Warning");
  await expect(popup).toContainText("Miami-Dade County");
  await expect(popup).toContainText("92");
  await expect(popup).toContainText("Avoid flooded roadways");
});

test("route segment dots follow the planned route and their layer toggle", async ({ page }) => {
  await page.goto("/directions?origin=Seattle&destination=Miami%20Beach&vehicle=car");
  await waitForMapLayer(page, "route-segment-dots");
  await expect.poll(() => layerVisibility(page, "route-segment-dots")).toBe("visible");

  const toggle = page.getByRole("switch", { name: "Route segments" });
  await toggle.click();
  await expect.poll(() => layerVisibility(page, "route-segment-dots")).toBe("none");
  await expect.poll(() => layerVisibility(page, "route-segment-dots-halo")).toBe("none");
  await toggle.click();
  await expect.poll(() => layerVisibility(page, "route-segment-dots")).toBe("visible");
});

test("directions deep link resolves places", async ({ page }) => {
  await page.goto("/directions?origin=Seattle&destination=Miami%20Beach&vehicle=car");

  await expect(page.getByRole("heading", { name: "Seattle to Miami Beach" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Calculate route" })).toBeEnabled();
});

test("place search supports keyboard selection", async ({ page }) => {
  await page.goto("/map");

  await page.getByPlaceholder("Choose destination").fill("Atlanta");
  await expect(page.locator(".place-results").getByRole("button", { name: /Atlanta/ })).toBeVisible();
  await page.getByPlaceholder("Choose destination").press("Enter");

  await expect(page.getByPlaceholder("Choose destination")).toHaveValue("Atlanta, GA, United States");
});

interface TestMapHandle {
  getLayer: (layerId: string) => unknown;
  getLayoutProperty: (layerId: string, name: string) => string | undefined;
}

async function waitForMapLayer(page: Page, layerId: string) {
  await page.waitForFunction((id) => {
    const host = document.querySelector(".network-map") as { __atmospathMap?: { getLayer: (layerId: string) => unknown } } | null;
    return Boolean(host?.__atmospathMap?.getLayer(id));
  }, layerId);
}

async function layerVisibility(page: Page, layerId: string): Promise<string | null> {
  return page.evaluate((id) => {
    const host = document.querySelector(".network-map") as { __atmospathMap?: TestMapHandle } | null;
    const map = host?.__atmospathMap;
    if (!map || !map.getLayer(id)) return null;
    return map.getLayoutProperty(id, "visibility") ?? "visible";
  }, layerId);
}

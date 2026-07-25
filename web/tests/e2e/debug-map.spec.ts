import { expect, test } from "@playwright/test";

import { installApiMocks } from "./fixtures";

test.beforeEach(async ({ page }) => {
  await installApiMocks(page);
});

test("debug map internals", async ({ page }) => {
  page.on("console", (message) => console.log(`[browser ${message.type()}] ${message.text()}`));
  page.on("pageerror", (error) => console.log(`[pageerror] ${error.message}`));
  await page.goto("/map");
  await page.waitForTimeout(4000);
  const state = await page.evaluate(() => {
    interface MapLike {
      loaded: () => boolean;
      getStyle: () => { layers: Array<{ id: string }>; sources: Record<string, unknown> };
    }
    const host = document.querySelector(".network-map") as { __atmospathMap?: MapLike } | null;
    const map = host?.__atmospathMap;
    const style = map?.getStyle();
    return {
      hasHost: Boolean(host),
      hasMap: Boolean(map),
      loaded: map?.loaded() ?? null,
      layerIds: style?.layers.map((layer) => layer.id) ?? [],
      sourceIds: style ? Object.keys(style.sources) : [],
      mode: (window as unknown as { __MODE__?: string }).__MODE__ ?? "unknown",
    };
  });
  console.log("MAP STATE:", JSON.stringify(state, null, 2));
  expect(state.hasMap).toBe(true);
});

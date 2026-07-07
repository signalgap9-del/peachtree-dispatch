import { chromium } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";

const baseUrl = process.env.SCREENSHOT_BASE_URL ?? "http://127.0.0.1:4173";
const outputDir = resolve(process.cwd(), "..", "docs", "screenshots");

const captures = [
  {
    name: "home-live.png",
    path: "/",
    viewport: { width: 1440, height: 900 },
    waitFor: ".home-page",
  },
  {
    name: "dashboard-live.png",
    path: "/dashboard",
    viewport: { width: 1440, height: 900 },
    waitFor: ".dashboard-page",
  },
  {
    name: "map-route-live.png",
    path: "/map?origin=Seattle&destination=Miami%20Beach",
    viewport: { width: 1600, height: 900 },
    waitFor: ".map-page",
    settleMs: 7000,
  },
  {
    name: "status-live.png",
    path: "/status",
    viewport: { width: 1440, height: 900 },
    waitFor: ".status-page",
  },
  {
    name: "home-mobile-live.png",
    path: "/",
    viewport: { width: 390, height: 844 },
    waitFor: ".home-page",
    isMobile: true,
  },
];

await mkdir(outputDir, { recursive: true });

const browser = await chromium.launch();
try {
  for (const capture of captures) {
    const context = await browser.newContext({
      viewport: capture.viewport,
      deviceScaleFactor: 1,
      isMobile: Boolean(capture.isMobile),
      hasTouch: Boolean(capture.isMobile),
    });
    await context.addInitScript(() => {
      localStorage.setItem("atmospath:language", "en");
    });
    const page = await context.newPage();
    page.setDefaultTimeout(Number(process.env.SCREENSHOT_TIMEOUT_MS ?? 30_000));
    const target = new URL(capture.path, baseUrl).toString();
    console.log(`Capturing ${capture.name} from ${target}`);
    await page.goto(target, { waitUntil: "networkidle" });
    if (capture.waitFor) {
      await page.locator(capture.waitFor).first().waitFor();
    }
    await page.waitForTimeout(capture.settleMs ?? Number(process.env.SCREENSHOT_SETTLE_MS ?? 1500));
    await page.screenshot({
      path: resolve(outputDir, capture.name),
      fullPage: false,
    });
    await context.close();
  }
} finally {
  await browser.close();
}

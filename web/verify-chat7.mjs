import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("pageerror", (error) => console.log("PAGEERROR:", String(error)));

await page.goto("http://127.0.0.1:5173/", { waitUntil: "domcontentloaded" });
await page.waitForSelector(".chat-toggle", { timeout: 20000 });
// risk_suggestion arrives ~1.5s after the alert stream connects.
await page.waitForTimeout(4000);
console.log("live indicator:", await page.locator(".alert-live-banner .live-indicator").count());
console.log("badge:", await page.locator(".chat-toggle-badge").count());
console.log("attention:", await page.locator(".chat-toggle.attention").count());
await page.screenshot({ path: "tmp/chat-verify/16-fab-badge.png", clip: { x: 1240, y: 680, width: 200, height: 220 } });

// Opening the chat clears the badge.
await page.locator(".chat-toggle").click();
await page.waitForTimeout(400);
console.log("badge after open:", await page.locator(".chat-toggle-badge").count());

// Stop button mid-stream.
await page.locator(".chat-quick-actions button", { hasText: "경로 계획" }).click();
await page.waitForTimeout(2100);
console.log("stop button visible mid-stream:", await page.locator(".chat-send.stop").count());
await page.locator(".chat-send.stop").click();
await page.waitForTimeout(500);
const state = await page.evaluate(() => ({
  caret: Boolean(document.querySelector(".chat-caret")),
  stop: Boolean(document.querySelector(".chat-send.stop")),
  partial: document.querySelector(".chat-message.assistant")?.innerText.slice(0, 60) ?? null,
}));
console.log("after stop:", JSON.stringify(state));
await page.screenshot({ path: "tmp/chat-verify/17-stopped.png" });
await browser.close();
console.log("done");

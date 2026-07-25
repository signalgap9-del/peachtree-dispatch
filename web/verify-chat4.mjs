import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await page.locator(".chat-toggle").click();
await page.waitForTimeout(400);
await page.locator(".chat-quick-actions button", { hasText: "경로 계획" }).click();

const start = Date.now();
for (const at of [2000, 4000, 6000, 9000]) {
  const now = Date.now();
  while (Date.now() - start < at) await new Promise((r) => setTimeout(r, 100));
  const state = await page.evaluate(() => {
    const msg = document.querySelector(".chat-message.assistant");
    return {
      caret: Boolean(document.querySelector(".chat-caret")),
      pill: Boolean(document.querySelector(".chat-citations")),
      progress: Boolean(document.querySelector(".chat-progress")),
      tail: msg ? msg.innerText.slice(-120) : null,
    };
  });
  console.log(`t=${at}`, JSON.stringify(state));
}
await browser.close();

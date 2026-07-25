import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await page.waitForTimeout(300);
await page.screenshot({ path: "tmp/chat-verify/13-mobile-closed.png" });
await page.locator(".chat-toggle").click();
await page.waitForTimeout(900);
await page.screenshot({ path: "tmp/chat-verify/14-mobile-open-empty.png" });
await page.locator(".chat-quick-actions button", { hasText: "경로 계획" }).click();
await page.waitForTimeout(7000);
await page.screenshot({ path: "tmp/chat-verify/15-mobile-complete.png" });
await browser.close();
console.log("done");

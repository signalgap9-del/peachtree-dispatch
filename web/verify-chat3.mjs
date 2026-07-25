import { chromium } from "@playwright/test";

const OUT = "tmp/chat-verify";
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("pageerror", (error) => console.log("PAGEERROR:", String(error)));

await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await page.locator(".chat-toggle").click();
await page.waitForTimeout(400);

// Quick action -> full pipeline: progress stages, streaming, citations.
await page.locator(".chat-quick-actions button", { hasText: "경로 계획" }).click();
await page.waitForTimeout(1000);
await page.screenshot({ path: `${OUT}/07-progress.png` });
await page.waitForTimeout(2500);
await page.screenshot({ path: `${OUT}/08-streaming.png` });
await page.waitForTimeout(4000);
await page.screenshot({ path: `${OUT}/09-complete.png` });

// Expand citations.
await page.locator(".chat-citations button").click();
await page.waitForTimeout(300);
await page.screenshot({ path: `${OUT}/10-citations.png` });

// Second turn: free-text input with Enter.
await page.locator(".chat-input textarea").fill("2시간 뒤에 출발하면 어때?");
await page.locator(".chat-input textarea").press("Enter");
await page.waitForTimeout(6500);
await page.screenshot({ path: `${OUT}/11-second-turn.png` });

// Mobile viewport.
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
await mobile.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await mobile.waitForTimeout(400);
await mobile.locator(".chat-toggle").click();
await mobile.waitForTimeout(400);
await mobile.locator(".chat-quick-actions button", { hasText: "경로 계획" }).click();
await mobile.waitForTimeout(7000);
await mobile.screenshot({ path: `${OUT}/12-mobile-complete.png` });

await browser.close();
console.log("done");

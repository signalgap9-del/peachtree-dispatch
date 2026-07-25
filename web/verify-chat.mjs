import { chromium } from "@playwright/test";

const OUT = "tmp/chat-verify";
const browser = await chromium.launch();

// ---- Desktop ----
const desktop = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const pageErrors = [];
desktop.on("pageerror", (error) => pageErrors.push(String(error)));
desktop.on("console", (msg) => { if (msg.type() === "error") pageErrors.push(msg.text()); });

await desktop.goto("http://localhost:5173/", { waitUntil: "networkidle" });
await desktop.waitForTimeout(800);
await desktop.screenshot({ path: `${OUT}/01-desktop-home.png` });

const fab = desktop.locator(".chat-toggle");
console.log("FAB visible:", await fab.isVisible());
await fab.click();
await desktop.waitForTimeout(500);
await desktop.screenshot({ path: `${OUT}/02-desktop-chat-empty.png` });

// Send a message; backend may or may not be up - capture whatever state results.
await desktop.locator(".chat-input textarea").fill("시애틀에서 마이애미 트럭 경로, 폭풍 피해서");
await desktop.locator(".chat-input textarea").press("Enter");
await desktop.waitForTimeout(1200);
await desktop.screenshot({ path: `${OUT}/03-desktop-chat-streaming.png` });
await desktop.waitForTimeout(5000);
await desktop.screenshot({ path: `${OUT}/04-desktop-chat-settled.png` });

// ---- Mobile ----
const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
await mobile.goto("http://localhost:5173/", { waitUntil: "networkidle" });
await mobile.waitForTimeout(600);
await mobile.locator(".chat-toggle").click();
await mobile.waitForTimeout(500);
await mobile.screenshot({ path: `${OUT}/05-mobile-chat.png` });

console.log("PAGE ERRORS:", pageErrors.length ? pageErrors : "none");
await browser.close();

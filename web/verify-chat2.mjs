import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.on("pageerror", (error) => console.log("PAGEERROR:", String(error)));

await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await page.locator(".chat-toggle").click();
await page.waitForTimeout(400);

const textarea = page.locator(".chat-input textarea");
await textarea.fill("?뚯뒪??硫붿떆吏");
await page.waitForTimeout(200);
console.log("textarea value after fill:", await textarea.inputValue());

await textarea.press("Enter");
await page.waitForTimeout(300);
console.log("rows after Enter:", await page.locator(".chat-row").count());
console.log("textarea value after Enter:", await textarea.inputValue());

// Try the explicit button path too.
await textarea.fill("?먮쾲吏?硫붿떆吏");
await page.waitForTimeout(150);
await page.locator(".chat-send").click();
await page.waitForTimeout(300);
console.log("rows after button click:", await page.locator(".chat-row").count());

await page.waitForTimeout(3000);
console.log("final rows:", await page.locator(".chat-row").count());
console.log("error bubbles:", await page.locator(".chat-message.error").count());
await page.screenshot({ path: "tmp/chat-verify/06-debug.png" });
await browser.close();

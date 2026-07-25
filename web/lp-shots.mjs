import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto("http://localhost:5173/", { waitUntil: "networkidle" }).catch(() => {});

// header CTA -> app map
await page.getByRole("button", { name: /Open app/ }).click();
await page.waitForTimeout(800);
console.log("open-app:", page.url());

// back to landing, anchor scroll
await page.goto("http://localhost:5173/", { waitUntil: "networkidle" }).catch(() => {});
await page.click('a[href="#compare"]');
await page.waitForTimeout(700);
console.log("anchor:", page.url(), "scrollY:", await page.evaluate(() => Math.round(window.scrollY)));

// hero primary CTA
await page.evaluate(() => window.scrollTo(0, 0));
await page.getByRole("button", { name: /Open the map/ }).first().click();
await page.waitForTimeout(800);
console.log("hero-cta:", page.url());

// back to site link in app header
await page.locator(".back-to-site").click();
await page.waitForTimeout(800);
console.log("back-to-site:", page.url());

// footer status link
await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
await page.waitForTimeout(300);
await page.getByRole("button", { name: "Status", exact: true }).click();
await page.waitForTimeout(900);
console.log("footer-status:", page.url());

// pricing team contact (mailto should not navigate away)
await page.goto("http://localhost:5173/#pricing", { waitUntil: "networkidle" }).catch(() => {});
console.log("title:", await page.title());
await browser.close();
console.log("done");

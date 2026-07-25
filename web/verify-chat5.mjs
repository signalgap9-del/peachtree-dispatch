import { chromium } from "@playwright/test";

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 390, height: 844 } });
await page.goto("http://127.0.0.1:5173/", { waitUntil: "networkidle" });
await page.locator(".chat-toggle").click();
await page.waitForTimeout(500);
const geo = await page.evaluate(() => {
  const pick = (el) => {
    if (!el) return null;
    const rect = el.getBoundingClientRect();
    const style = getComputedStyle(el);
    return { top: rect.top, bottom: rect.bottom, height: rect.height, zIndex: style.zIndex, position: style.position };
  };
  return {
    viewport: { h: window.innerHeight },
    panel: pick(document.querySelector(".chat-panel")),
    nav: pick(document.querySelector(".primary-nav")),
    input: pick(document.querySelector(".chat-input")),
  };
});
console.log(JSON.stringify(geo, null, 2));
await browser.close();

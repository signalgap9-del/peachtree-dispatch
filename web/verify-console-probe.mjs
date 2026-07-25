import { chromium } from "@playwright/test";

const baseUrl = "http://127.0.0.1:4173";
const browser = await chromium.launch();
const page = await browser.newPage();
const consoleIssues = [];
const failedRequests = [];
page.on("console", (message) => {
  if (["error", "warning"].includes(message.type())) {
    const location = message.location();
    consoleIssues.push(`${message.type()}: ${message.text()} @ ${location?.url ?? "?"}:${location?.lineNumber ?? "?"}`);
  }
});
page.on("requestfailed", (request) => {
  failedRequests.push(`${request.failure()?.errorText} ${request.url()}`);
});
for (const path of ["/", "/map", "/dashboard"]) {
  await page.goto(baseUrl + path, { waitUntil: "load" });
  await page.waitForTimeout(2500);
}
console.log("CONSOLE ISSUES:");
for (const issue of consoleIssues) console.log(" -", issue);
console.log("FAILED REQUESTS:");
for (const failure of failedRequests) console.log(" -", failure);
await browser.close();

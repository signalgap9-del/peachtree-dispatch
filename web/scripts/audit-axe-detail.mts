 // Detailed axe contrast + nested-interactive report.
 import { chromium } from "@playwright/test";
 import AxeBuilder from "@axe-core/playwright";
 import { installApiMocks, seedSignedInUser } from "../tests/e2e/fixtures.ts";
 
 const browser = await chromium.launch();
 for (const [name, path] of [["home", "/app"], ["dashboard", "/app/dashboard"], ["alerts", "/app/alerts"], ["place", "/app/locations/miami"], ["saved", "/app/saved"], ["usage", "/app/usage"], ["pricing", "/app/pricing"], ["status", "/app/status"], ["terms", "/app/legal/terms"]] as const) {
   const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
   const page = await context.newPage();
   await installApiMocks(page);
   await seedSignedInUser(page);
   await page.goto(`http://127.0.0.1:5173${path}`, { waitUntil: "domcontentloaded" });
   await page.waitForTimeout(2500);
   const results = await new AxeBuilder({ page }).withRules(["color-contrast", "nested-interactive"]).analyze();
   console.log(`== ${name} ==`);
   for (const violation of results.violations) {
     for (const node of violation.nodes) {
       const message = node.failureSummary?.split("\n").slice(1).join(" ") ?? "";
       console.log(`${violation.id} | ${node.target.join(" ")} | ${node.html.slice(0, 90)} | ${message}`);
     }
   }
   await context.close();
 }
 await browser.close();

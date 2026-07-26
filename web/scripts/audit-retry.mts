 // Re-captures a subset of routes serially with a fresh browser per capture.
 import { chromium } from "@playwright/test";
 import { mkdir } from "node:fs/promises";
 import { resolve } from "node:path";
 import { installApiMocks, seedSignedInUser } from "../tests/e2e/fixtures.ts";
 
 const baseUrl = process.env.AUDIT_BASE_URL ?? "http://127.0.0.1:5173";
 const phase = process.env.AUDIT_PHASE ?? "before";
 const outputDir = resolve(process.cwd(), "..", "docs", "audit", phase);
 await mkdir(outputDir, { recursive: true });
 
 const jobs = process.argv.slice(2).map((entry) => {
   const [name, vp, path, waitFor, settleMs] = entry.split("|");
   return { name, vp, path, waitFor, settleMs: Number(settleMs ?? 1600) };
 });
 
 const viewports: Record<string, { width: number; height: number }> = {
   desktop: { width: 1440, height: 900 },
   mobile: { width: 375, height: 812 },
 };
 
 for (const job of jobs) {
   const viewport = viewports[job.vp];
   const shotName = `${job.name}-${job.vp}.png`;
   let succeeded = false;
   for (let attempt = 1; attempt <= 3 && !succeeded; attempt += 1) {
     const browser = await chromium.launch();
     const context = await browser.newContext({
       viewport,
       deviceScaleFactor: 1,
       isMobile: job.vp === "mobile",
       hasTouch: job.vp === "mobile",
     });
     const page = await context.newPage();
     page.setDefaultTimeout(45_000);
     await installApiMocks(page);
     await seedSignedInUser(page);
     try {
       await page.goto(new URL(job.path, baseUrl).toString(), { waitUntil: "domcontentloaded" });
       await page.locator(job.waitFor).first().waitFor();
       await page.waitForTimeout(job.settleMs);
       await page.screenshot({ path: resolve(outputDir, shotName), fullPage: false });
       const fullHeight = await page.evaluate(() => document.documentElement.scrollHeight);
       if (fullHeight > viewport.height + 80) {
         await page.screenshot({ path: resolve(outputDir, `${job.name}-${job.vp}-full.png`), fullPage: true });
       }
       console.log(`ok ${shotName}`);
       succeeded = true;
     } catch (error) {
       console.log(`attempt ${attempt} FAIL ${shotName}: ${(error as Error).message.split("\n")[0]}`);
       await page.waitForTimeout(2500);
     }
     await context.close();
     await browser.close();
   }
   if (!succeeded) console.log(`GAVE UP ${shotName}`);
 }

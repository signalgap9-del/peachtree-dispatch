import { chromium } from "@playwright/test";
import { resolve } from "node:path";
import { readFileSync } from "node:fs";

// Renders the Open Graph share card (1200x630) and PNG icons.
// Run from web/: node scripts/generate-og-image.mjs
const WIDTH = 1200;
const HEIGHT = 630;
const publicDir = resolve(process.cwd(), "public");
const ogPath = resolve(publicDir, "og-image.png");

// Inline the favicon SVG for the logo mark in the OG card
const faviconSvg = readFileSync(resolve(publicDir, "favicon.svg"), "utf-8");
// Extract inner content of the SVG (strip the outer <svg> wrapper)
const markInner = faviconSvg
  .replace(/<svg[^>]*>/, "")
  .replace(/<\/svg>/, "")
  .replace(/<!--.*?-->/gs, "");

const card = `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@500;700;800&display=swap" rel="stylesheet" />
    <style>
      html, body { margin: 0; padding: 0; }
      svg { display: block; font-family: Inter, "Segoe UI", system-ui, sans-serif; }
    </style>
  </head>
  <body>
    <svg width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}" role="img" aria-label="FreightScaler weather-aware freight routing">
      <rect width="${WIDTH}" height="${HEIGHT}" fill="#F8FAFD" />
      <!-- Subtle grid lines for depth -->
      <g stroke="#E4EAF2" stroke-width="2.5" fill="none">
        <path d="M0 180 L1200 110" />
        <path d="M0 430 L600 390 L1200 320" />
        <path d="M240 0 L280 630" />
        <path d="M800 0 L740 630" />
        <path d="M0 560 L500 530 L1200 570" />
      </g>
      <!-- Route path visualization -->
      <path d="M280 480 C 420 440, 500 360, 620 340 C 740 320, 820 280, 940 240" fill="none" stroke="#0B57D0" stroke-width="9" stroke-linecap="round" />
      <circle cx="280" cy="480" r="13" fill="#FFFFFF" stroke="#0B57D0" stroke-width="6" />
      <text x="280" y="530" text-anchor="middle" font-size="24" font-weight="600" fill="#5F6368">Origin</text>
      <!-- Destination with alert -->
      <circle cx="940" cy="240" r="18" fill="#F9AB00" stroke="#FFFFFF" stroke-width="5" />
      <path d="M940 229 L948 244 L932 244 Z" fill="#FFFFFF" />
      <text x="940" y="290" text-anchor="middle" font-size="24" font-weight="600" fill="#5F6368">Destination</text>
      <!-- Weather alert badge on route -->
      <circle cx="620" cy="340" r="22" fill="#F9AB00" stroke="#FFFFFF" stroke-width="4" />
      <path d="M620 326 L630 344 L610 344 Z" fill="#FFFFFF" />
      <rect x="606" y="348" width="28" height="5" rx="2.5" fill="#FFFFFF" />
      <!-- Logo mark -->
      <g transform="translate(80, 60) scale(1.5)">
        ${markInner}
      </g>
      <!-- Brand name -->
      <text x="192" y="128" font-size="58" font-weight="800" fill="#202124" letter-spacing="-1">Freight<tspan fill="#0B57D0">Scaler</tspan></text>
      <text x="194" y="170" font-size="28" font-weight="500" fill="#5F6368">Weather-aware freight route planning</text>
      <!-- Status badges -->
      <rect x="920" y="64" width="200" height="50" rx="25" fill="#FFFFFF" stroke="#DADCE0" stroke-width="2" />
      <circle cx="950" cy="89" r="7" fill="#188038" />
      <text x="966" y="97" font-size="23" font-weight="700" fill="#202124">Live monitoring</text>
      <rect x="920" y="520" width="200" height="52" rx="26" fill="#0B57D0" />
      <text x="1020" y="553" text-anchor="middle" font-size="24" font-weight="700" fill="#FFFFFF">freightscaler.com</text>
    </svg>
  </body>
</html>`;

const browser = await chromium.launch();
try {
  // --- OG image ---
  const context = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  await page.setContent(card, { waitUntil: "networkidle" });
  await page.screenshot({ path: ogPath, clip: { x: 0, y: 0, width: WIDTH, height: HEIGHT } });
  console.log(`Wrote ${ogPath}`);

  // --- PNG icons from favicon.svg ---
  const iconPage = await context.newPage();
  const iconSizes = [192, 512, 180];
  for (const size of iconSizes) {
    const iconHtml = `<!doctype html><html><head><style>html,body{margin:0;padding:0;}</style></head><body>${faviconSvg.replace('viewBox="0 0 64 64"', `width="${size}" height="${size}" viewBox="0 0 64 64"`)}</body></html>`;
    await iconPage.setViewportSize({ width: size, height: size });
    await iconPage.setContent(iconHtml, { waitUntil: "networkidle" });
    const filename = size === 180 ? "apple-touch-icon.png" : `icon-${size}.png`;
    await iconPage.screenshot({
      path: resolve(publicDir, filename),
      clip: { x: 0, y: 0, width: size, height: size },
    });
    console.log(`Wrote ${resolve(publicDir, filename)}`);
  }
} finally {
  await browser.close();
}

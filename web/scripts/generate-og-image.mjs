import { chromium } from "@playwright/test";
import { resolve } from "node:path";

// Renders the Open Graph share card (1200x630) referenced by index.html.
// Run from web/: node scripts/generate-og-image.mjs
const WIDTH = 1200;
const HEIGHT = 630;
const outputPath = resolve(process.cwd(), "public", "og-image.png");

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
    <svg width="${WIDTH}" height="${HEIGHT}" viewBox="0 0 ${WIDTH} ${HEIGHT}" role="img" aria-label="AtmosPath weather-aware route planning">
      <rect width="${WIDTH}" height="${HEIGHT}" fill="#F8FAFD" />
      <g stroke="#E4EAF2" stroke-width="3" fill="none">
        <path d="M0 170 L1200 96" />
        <path d="M0 420 L540 380 L1200 300" />
        <path d="M210 0 L260 630" />
        <path d="M760 0 L700 630" />
        <path d="M0 560 L420 520 L1200 560" />
      </g>
      <path d="M980 630 C 1010 520 1090 470 1200 452" fill="none" stroke="#D7E2F0" stroke-width="5" />
      <path d="M330 226 C 450 320 540 240 640 330 C 740 420 800 468 912 472" fill="none" stroke="#0B57D0" stroke-width="10" stroke-linecap="round" />
      <circle cx="330" cy="226" r="14" fill="#FFFFFF" stroke="#0B57D0" stroke-width="6" />
      <text x="330" y="188" text-anchor="middle" font-size="27" font-weight="700" fill="#5F6368">Atlanta</text>
      <path d="M915 476 C 903 454 884 444 884 424 a31 31 0 1 1 62 0 c0 20 -19 30 -31 52 z" fill="#0B57D0" />
      <circle cx="915" cy="423" r="12" fill="#FFFFFF" />
      <text x="915" y="540" text-anchor="middle" font-size="27" font-weight="700" fill="#5F6368">Miami</text>
      <circle cx="640" cy="330" r="27" fill="#F9AB00" stroke="#FFFFFF" stroke-width="5" />
      <path d="M648 308 L626 338 h13 l-9 24 26 -32 h-13 z" fill="#FFFFFF" />
      <rect x="80" y="70" width="84" height="84" rx="20" fill="#0B57D0" />
      <path d="M100 130 c 14 0 10 -18 24 -18 s 12 10 26 -8" fill="none" stroke="#FFFFFF" stroke-width="5" stroke-linecap="round" stroke-dasharray="0.1 11" />
      <circle cx="100" cy="130" r="6" fill="#FFFFFF" />
      <path d="M150 86 c-8 0 -14 6 -14 14 c0 10 14 22 14 22 s14 -12 14 -22 c0 -8 -6 -14 -14 -14z" fill="#FFFFFF" />
      <circle cx="150" cy="100" r="5" fill="#0B57D0" />
      <text x="192" y="118" font-size="60" font-weight="800" fill="#202124" letter-spacing="-1">AtmosPath</text>
      <text x="194" y="160" font-size="30" font-weight="500" fill="#5F6368">Weather-aware route planning</text>
      <rect x="930" y="64" width="190" height="54" rx="27" fill="#FFFFFF" stroke="#DADCE0" stroke-width="2" />
      <circle cx="962" cy="91" r="8" fill="#188038" />
      <text x="980" y="100" font-size="25" font-weight="700" fill="#202124">Live alerts</text>
      <rect x="930" y="520" width="190" height="56" rx="28" fill="#FFFFFF" stroke="#0B57D0" stroke-width="2.5" />
      <text x="1025" y="557" text-anchor="middle" font-size="26" font-weight="700" fill="#0B57D0">Live risk map</text>
    </svg>
  </body>
</html>`;

const browser = await chromium.launch();
try {
  const context = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  await page.setContent(card, { waitUntil: "networkidle" });
  await page.screenshot({ path: outputPath, clip: { x: 0, y: 0, width: WIDTH, height: HEIGHT } });
} finally {
  await browser.close();
}

console.log(`Wrote ${outputPath}`);

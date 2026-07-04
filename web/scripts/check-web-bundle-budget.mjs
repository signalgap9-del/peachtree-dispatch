import { readdirSync, readFileSync, statSync } from "node:fs";
import path from "node:path";
import { gzipSync } from "node:zlib";

const assetsDir = path.resolve("dist", "assets");
const assetRows = readdirSync(assetsDir)
  .filter((name) => /\.(js|css)$/.test(name))
  .map((name) => {
    const filePath = path.join(assetsDir, name);
    const rawBytes = statSync(filePath).size;
    const gzipBytes = gzipSync(readFileSync(filePath)).length;
    return {
      name,
      rawKb: rawBytes / 1024,
      gzipKb: gzipBytes / 1024,
      type: name.endsWith(".css") ? "css" : "js",
    };
  });

const jsRows = assetRows.filter((row) => row.type === "js");
const cssRows = assetRows.filter((row) => row.type === "css");
const initialJsGzipKb = sum(jsRows.filter((row) => !row.name.startsWith("map-") && !row.name.startsWith("MapPage-")), "gzipKb");
const mapGzipKb = sum(jsRows.filter((row) => row.name.startsWith("map-")), "gzipKb");
const routeChunkGzipKb = sum(jsRows.filter((row) => row.name.startsWith("MapPage-")), "gzipKb");
const cssGzipKb = sum(cssRows, "gzipKb");

const budgets = [
  { label: "initial JS gzip", actualKb: initialJsGzipKb, limitKb: 180 },
  { label: "MapLibre vendor chunk gzip", actualKb: mapGzipKb, limitKb: 320 },
  { label: "map route chunk gzip", actualKb: routeChunkGzipKb, limitKb: 45 },
  { label: "CSS gzip", actualKb: cssGzipKb, limitKb: 90 },
];

const failures = budgets.filter((budget) => budget.actualKb > budget.limitKb);

console.log("Bundle budget report");
for (const row of assetRows.sort((a, b) => b.gzipKb - a.gzipKb)) {
  console.log(`- ${row.name}: ${formatKb(row.rawKb)} raw / ${formatKb(row.gzipKb)} gzip`);
}
console.log("");
for (const budget of budgets) {
  const status = budget.actualKb <= budget.limitKb ? "PASS" : "FAIL";
  console.log(`${status} ${budget.label}: ${formatKb(budget.actualKb)} <= ${formatKb(budget.limitKb)}`);
}

if (failures.length) {
  process.exitCode = 1;
}

function sum(rows, key) {
  return rows.reduce((total, row) => total + row[key], 0);
}

function formatKb(value) {
  return `${value.toFixed(2)} kB`;
}

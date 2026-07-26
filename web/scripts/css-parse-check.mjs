 // Reports CSS syntax errors using the postcss bundled with vite.
 import { readFileSync } from "node:fs";
 import { createRequire } from "node:module";
 const require = createRequire(import.meta.url);
 const postcss = require("postcss");
 const css = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");
 try {
   postcss.parse(css, { from: "styles.css" });
   console.log("no parse errors");
 } catch (error) {
   console.log(`${error.name}: ${error.reason}`);
   console.log(`line ${error.line} column ${error.column}`);
   const lines = css.split("\n");
   const start = Math.max(0, error.line - 3);
   for (let i = start; i < Math.min(lines.length, error.line + 2); i += 1) {
     const text = lines[i].length > 400 ? `${lines[i].slice(0, 400)}…` : lines[i];
     console.log(`${i + 1}: ${text}`);
   }
 }

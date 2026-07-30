// Dependency audit gate with a documented allow-list for advisories that have
// no installable fix. This keeps `npm run audit` failing on any NEW high or
// critical advisory while acknowledging the ones that cannot be resolved
// without breaking changes or an upstream patch release.
//
// Accepted advisories (reviewed 2026-07):
//
//  - react-router / react-router-dom
//    Conflicting advisories: one set affects 6.0.0-7.17.0 (fixed in 7.18.x),
//    another set affects 7.12.0+ with no patched release. 7.18.2 is the latest
//    react-router-dom and clears the RCE/XSS/open-redirect set; no version
//    clears both sets yet. This app uses react-router in client-side library
//    mode (BrowserRouter) only — it has no RSC/SSR server actions, so the
//    affected server-side attack surface is not present.
//
//  - brace-expansion (and the eslint chain that depends on it: minimatch,
//    @eslint/config-array, @eslint/eslintrc, eslint)
//    brace-expansion is transitive via eslint -> minimatch. The patched
//    brace-expansion line (>=5.0.8) is not compatible with the pinned minimatch;
//    clearing it requires an eslint major-version migration (9 -> 10), tracked
//    separately. The derived entries (minimatch, @eslint/*, eslint) are the same
//    root advisory surfaced up the dependency chain.
//
// Everything else at high or critical severity still fails the build.

import { execSync } from "node:child_process";

const ACCEPTED = new Set([
  "react-router",
  "react-router-dom",
  "brace-expansion",
  "minimatch",
  "@eslint/config-array",
  "@eslint/eslintrc",
  "eslint",
]);

let raw;
try {
  raw = execSync("npm audit --json", { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
} catch (error) {
  // npm audit exits non-zero when it finds vulnerabilities; the JSON report is
  // still on stdout.
  raw = error.stdout;
}

let audit;
try {
  audit = JSON.parse(raw);
} catch {
  console.error("npm audit did not return parseable JSON; failing closed.");
  process.exit(1);
}

const vulnerabilities = audit.vulnerabilities ?? {};
const blocking = Object.entries(vulnerabilities).filter(([name, info]) => {
  if (ACCEPTED.has(name)) return false;
  return info.severity === "high" || info.severity === "critical";
});

if (blocking.length > 0) {
  console.error(`\u2716 ${blocking.length} unaccepted high/critical vulnerabilit(ies):`);
  for (const [name, info] of blocking) {
    console.error(`  - ${name} (${info.severity})`);
  }
  console.error("Fix these or add a documented entry to ACCEPTED in scripts/audit-filtered.mjs.");
  process.exit(1);
}

const acceptedPresent = Object.keys(vulnerabilities).filter((name) => ACCEPTED.has(name));
if (acceptedPresent.length > 0) {
  console.log(`Accepted (documented, no installable fix): ${acceptedPresent.join(", ")}`);
}
console.log("\u2714 Audit passed: no unaccepted high/critical vulnerabilities.");

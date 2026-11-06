#!/usr/bin/env node
// Builds the deployable Worker = the live baseline + the What's New section + 4 small, anchored edits.
//   node worker/build.mjs [baselinePath] [outPath]
// Defaults: worker/baseline/cigram-admin-api.worker.js -> worker/dist/cigram-admin-api.worker.js
// Every anchor must match EXACTLY ONCE, otherwise the build stops (so a changed live Worker is never patched blindly).
import { readFileSync, writeFileSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const baselinePath = resolve(process.argv[2] || join(here, "baseline", "cigram-admin-api.worker.js"));
const outPath = resolve(process.argv[3] || join(here, "dist", "cigram-admin-api.worker.js"));

let code = readFileSync(baselinePath, "utf8");
const section = readdirSync(join(here, "src"))
  .filter((f) => /^\d+-.*\.js$/.test(f))
  .sort()
  .map((f) => readFileSync(join(here, "src", f), "utf8"))
  .join("\n");

function replaceOnce(source, anchor, replacement, label) {
  const first = source.indexOf(anchor);
  if (first < 0) throw new Error("anchor not found: " + label);
  if (source.indexOf(anchor, first + 1) >= 0) throw new Error("anchor is not unique: " + label);
  return source.slice(0, first) + replacement + source.slice(first + anchor.length);
}

// 1) the section itself, right before the main router
const A_ROUTER = "// -----------------------------------------------------------------------------\n// MAIN ROUTER\n// -----------------------------------------------------------------------------";
code = replaceOnce(code, A_ROUTER, section + "\n\n" + A_ROUTER, "MAIN ROUTER banner");

// 2) cron: one line (cheap when auto-sync is off)
const A_CRON = "    // Cron Trigger (every minute): publish the scheduled update when its time has come.";
code = replaceOnce(
  code,
  A_CRON,
  "    // What's New (v11): optional automatic discovery sync (reads one small R2 object when it is switched off).\n" +
    "    ctx.waitUntil(wnCronTick(env).catch((error) => updLog(\"whatsnew_cron_failed\", { message: String(error && error.message) })));\n" +
    A_CRON,
  "cron"
);

// 3) router: one dispatch line, after the push routes
const A_DISPATCH = "      const pushHandler = PUSH_ROUTES[request.method + \" \" + path];\n      if (pushHandler) return pushHandler(request, env, url, ctx);\n";
code = replaceOnce(code, A_DISPATCH, A_DISPATCH + "\n      const wnHandler = WN_ROUTES[request.method + \" \" + path];\n      if (wnHandler) return wnHandler(request, env, url, ctx);\n", "dispatch");

// 4) health route list
const A_HEALTH = "            .concat(Object.keys(PUSH_ROUTES)),";
code = replaceOnce(code, A_HEALTH, "            .concat(Object.keys(PUSH_ROUTES))\n            .concat(Object.keys(WN_ROUTES)),", "health list");

mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, code);
console.log("built " + outPath + " (" + code.split("\n").length + " lines)");

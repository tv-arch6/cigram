// «بلّغ المدير»: local test of the channel report routes of cigram-admin-api (Node 22, no network).
// Usage: node channel_reports_test.mjs [path to worker.js]   (default: ../deploy/cigram-admin-api/worker.js)
import assert from "node:assert/strict";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

class MemR2 {
  constructor() { this.m = new Map(); this.n = 0; }
  async get(k) {
    if (!this.m.has(k)) return null;
    const { text, etag } = this.m.get(k);
    return { key: k, etag: etag.replace(/"/g, ""), httpEtag: etag, body: text, text: async () => text, json: async () => JSON.parse(text) };
  }
  async put(k, v, opts = {}) {
    const cur = this.m.get(k);
    const cond = opts.onlyIf || {};
    if (cond.etagMatches && (!cur || cur.etag.replace(/"/g, "") !== String(cond.etagMatches).replace(/"/g, ""))) return null;
    if (cond.etagDoesNotMatch === "*" && cur) return null;
    const etag = '"e' + (++this.n) + '"';
    this.m.set(k, { text: typeof v === "string" ? v : new TextDecoder().decode(v), etag });
    return { key: k, etag: etag.replace(/"/g, ""), httpEtag: etag };
  }
  async delete(k) { this.m.delete(k); }
  async list() { return { objects: [], truncated: false }; }
  async head(k) { return this.get(k); }
}

const here = path.dirname(fileURLToPath(import.meta.url));
const file = process.argv[2] || path.join(here, "..", "deploy", "cigram-admin-api", "worker.js");
const worker = (await import(pathToFileURL(path.resolve(file)).href)).default;
const env = { MOVIES_BUCKET: new MemR2(), ADMIN_TOKEN: "adm" };
const call = async (method, p, body, admin) => {
  const headers = { "content-type": "application/json" };
  if (admin) headers.authorization = "Bearer adm";
  const res = await worker.fetch(new Request("https://admin.test" + p, { method, headers, body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  let data = {};
  try { data = await res.json(); } catch { /* not json */ }
  return { status: res.status, data };
};
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

await test("a viewer reports a channel without any credential", async () => {
  const r = await call("POST", "/channel-reports", { key: "bein sports 1", name: "beIN SPORTS 1", url: "https://old/1.m3u8", client_id: "phone-a" });
  assert.equal(r.status, 200);
  assert.equal(r.data.ok !== false, true);
});

await test("a report without a channel name is refused", async () => {
  const r = await call("POST", "/channel-reports", { url: "https://x/1.m3u8" });
  assert.equal(r.status, 400);
});

await test("the admin list needs the admin token", async () => {
  const r = await call("GET", "/admin/channel-reports", null, false);
  assert.ok(r.status === 401 || r.status === 403, "status " + r.status);
});

await test("reports of the same channel are counted once per phone", async () => {
  await call("POST", "/channel-reports", { key: "bein sports 1", name: "beIN SPORTS 1", url: "https://old/1.m3u8", client_id: "phone-a" });
  await call("POST", "/channel-reports", { key: "bein sports 1", name: "beIN SPORTS 1", url: "https://old/1.m3u8", client_id: "phone-b" });
  const r = await call("GET", "/admin/channel-reports", null, true);
  assert.equal(r.status, 200);
  const items = (r.data.data && r.data.data.items) || r.data.items;
  assert.equal(items.length, 1);
  assert.equal(items[0].count, 2);
  assert.equal(items[0].url, "https://old/1.m3u8");
});

await test("resolving a report takes it off the list, and a new report opens it again", async () => {
  const done = await call("POST", "/admin/channel-reports/resolve", { key: "bein sports 1", url: "https://new/1.m3u8" }, true);
  assert.equal(done.status, 200);
  let r = await call("GET", "/admin/channel-reports", null, true);
  let items = (r.data.data && r.data.data.items) || r.data.items;
  assert.equal(items.length, 0);
  await call("POST", "/channel-reports", { key: "bein sports 1", name: "beIN SPORTS 1", url: "https://new/1.m3u8", client_id: "phone-c" });
  r = await call("GET", "/admin/channel-reports", null, true);
  items = (r.data.data && r.data.data.items) || r.data.items;
  assert.equal(items.length, 1);
  assert.equal(items[0].count, 1);
});

console.log(passed + " passed");

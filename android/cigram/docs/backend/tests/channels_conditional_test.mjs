// 18.3 — local test of the PATCHED sigram-channels-api worker (Node 22, no network): conditional GET for /channels.
// Usage: node channels_conditional_test.mjs <path to worker.patched.mjs>
import assert from "node:assert/strict";

class MemR2 {
  constructor() { this.m = new Map(); this.n = 0; }
  async get(k, opts = {}) {
    if (!this.m.has(k)) return null;
    const { text, etag } = this.m.get(k);
    const obj = { key: k, etag: etag.replace(/"/g, ""), httpEtag: etag, text: async () => text };
    const h = opts.onlyIf && opts.onlyIf.get ? opts.onlyIf.get("if-none-match") : null;   // R2 evaluates the preconditions itself
    if (h && h === etag) return obj;                    // precondition failed -> R2Object WITHOUT body
    return Object.assign(obj, { body: text });
  }
  async put(k, v) { this.m.set(k, { text: typeof v === "string" ? v : new TextDecoder().decode(v), etag: '"e' + (++this.n) + '"' }); }
  async delete(k) { this.m.delete(k); }
}

const worker = (await import(process.argv[2])).default;
const bucket = new MemR2();
const env = { CHANNELS_BUCKET: bucket, ADMIN_TOKEN: "adm", PUBLIC_R2_BASE_URL: "https://pub.example.invalid" };
const get = (headers = {}) => worker.fetch(new Request("https://ch.test/channels", { headers }), env);
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

await bucket.put("channels.json", JSON.stringify({ categories: [{ name: "الرياضة", channels: [{ id: 1, name: "قناة", url: "https://s/1.m3u8", image: "https://pub.example.invalid/channels/1.jpg" }] }] }));

await test("the list is still served in full, with an ETag, revalidating every time", async () => {
  const r = await get();
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.ok, true);
  assert.equal(j.categories[0].channels[0].id, 1);
  assert.equal(r.headers.get("etag"), '"e1"');
  assert.equal(r.headers.get("cache-control"), "no-cache");
});

await test("If-None-Match with the current ETag answers 304 and no body", async () => {
  const r = await get({ "if-none-match": '"e1"' });
  assert.equal(r.status, 304);
  assert.equal(await r.text(), "");
  assert.equal(r.headers.get("etag"), '"e1"');
});

await test("a changed list is sent in full again (a new channel is visible at once)", async () => {
  const add = await worker.fetch(new Request("https://ch.test/channels/add", { method: "POST", headers: { "x-admin-token": "adm", "content-type": "application/json" },
    body: JSON.stringify({ category: "الأخبار", name: "جديدة", url: "https://s/2.m3u8", image_base64: Buffer.from("x").toString("base64"), image_mime: "image/png" }) }), env);
  assert.equal(add.status, 201);
  const r = await get({ "if-none-match": '"e1"' });
  assert.equal(r.status, 200);
  assert.ok((await r.json()).categories.some((c) => c.channels.some((ch) => ch.name === "جديدة")));
});

await test("a stale or foreign validator never yields 304", async () => {
  assert.equal((await get({ "if-none-match": '"something-else"' })).status, 200);
});

await test("no saved object yet: the same defaults as before", async () => {
  const empty = { CHANNELS_BUCKET: new MemR2(), ADMIN_TOKEN: "adm" };
  const r = await worker.fetch(new Request("https://ch.test/channels"), empty);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).categories.length, 2);
});

await test("admin routes still need the admin token", async () => {
  const r = await worker.fetch(new Request("https://ch.test/channels/delete", { method: "POST", body: "{}" }), env);
  assert.equal(r.status, 401);
});

console.log(`\n${passed} passed`);

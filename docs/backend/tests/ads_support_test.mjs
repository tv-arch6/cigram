// 18.2 — local test of the PATCHED cigram-ads-api worker (Node 22, no network, no Cloudflare account).
// Runs the real worker module: routing, auth (HS256 JWT), ChatHub durable object (in-memory storage), encryption.
// Usage: node ads_support_test.mjs <path to worker.patched.mjs>
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";

const mod = await import(process.argv[2]);
const { ChatHub } = mod;
const worker = mod.default;

// ---------------------------------------------------------------- in-memory Durable Object
class MemStorage {
  constructor() { this.m = new Map(); this.alarm = null; }
  async get(k) { return this.m.has(k) ? structuredClone(this.m.get(k)) : undefined; }
  async put(k, v) { this.m.set(k, structuredClone(v)); }
  async delete(k) { if (Array.isArray(k)) { for (const x of k) this.m.delete(x); return; } this.m.delete(k); }
  async list(o = {}) {
    let keys = [...this.m.keys()].filter((k) => !o.prefix || k.startsWith(o.prefix)).sort();
    if (o.start) keys = keys.filter((k) => k >= o.start);
    if (o.end) keys = keys.filter((k) => k < o.end);
    if (o.reverse) keys.reverse();
    if (o.limit) keys = keys.slice(0, o.limit);
    return new Map(keys.map((k) => [k, structuredClone(this.m.get(k))]));
  }
  async getAlarm() { return this.alarm; }
  async setAlarm(t) { this.alarm = t; }
}
function makeState() {
  return { storage: new MemStorage(), getWebSockets: () => [], acceptWebSocket() {}, waitUntil() {} };
}
const b64 = (u8) => Buffer.from(u8).toString("base64");
const env = {
  CHAT_KEY: b64(webcrypto.getRandomValues(new Uint8Array(32))),
  ADMIN_TOKEN: "owner-token-123",
  DEV_NAME: "حسن سعيد",
  DEV_AVATAR: "https://example.invalid/dev.png",
  JWT_SECRET: "jwt-test-secret",
};
const hubObj = new ChatHub(makeState(), env);
env.CHAT_HUB = { idFromName: () => "hub", get: () => ({ fetch: (u, i) => hubObj.fetch(new Request(u, i)) }) };
// the ads core durable object: only what the chat routes call (consent / settings / advertiser bookkeeping)
let consentValid = false;
env.ADS_CORE = { idFromName: () => "core", get: () => ({ fetch: async (u, i) => {
  const body = JSON.parse(await new Request(u, i).text());
  const ok = (o) => new Response(JSON.stringify(Object.assign({ ok: true }, o)));
  if (body.op === "consent_get") return ok({ valid: consentValid });
  if (body.op === "settings_public") return ok({ settings: { hours: { enabled: false } } });
  if (body.op === "adv_ensure_user") return ok({ advertiser: { id: "adv_1" } });
  if (body.op === "authfail") return ok({ blocked: false });
  return ok({});
} }) };
// a support developer (role "support") identified by DEVELOPERS
const supportToken = "support-token-xyz";
const sha = async (t) => Buffer.from(await webcrypto.subtle.digest("SHA-256", new TextEncoder().encode(t))).toString("hex");
env.DEVELOPERS = JSON.stringify([{ id: "dev_support_1", name: "دعم Cigram", avatar: "", role: "support", token_sha256: await sha(supportToken) }]);

async function jwt(claims) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  const head = enc({ alg: "HS256", typ: "JWT" }), body = enc(claims);
  const key = await webcrypto.subtle.importKey("raw", new TextEncoder().encode(env.JWT_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = Buffer.from(await webcrypto.subtle.sign("HMAC", key, new TextEncoder().encode(head + "." + body))).toString("base64url");
  return head + "." + body + "." + sig;
}
const call = async (method, path, token, body) => {
  const r = await worker.fetch(new Request("https://ads.test" + path, { method, headers: Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {}), body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  return { status: r.status, json: await r.json() };
};

const alice = await jwt({ sub: "auth-user-42", email: "alice@example.invalid", name: "Alice", exp: Math.floor(Date.now() / 1000) + 3600 });
const bob = await jwt({ sub: "auth-user-77", email: "bob@example.invalid", name: "Bob", exp: Math.floor(Date.now() / 1000) + 3600 });
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

await test("user thread view carries no internal id", async () => {
  const r = await call("GET", "/chat/thread", alice);
  assert.equal(r.status, 200);
  assert.equal(r.json.thread.id, undefined);
  assert.equal(JSON.stringify(r.json).includes("auth-user-42"), false);
});

await test("a support text message needs no advertising consent", async () => {
  const r = await call("POST", "/chat/send", alice, { client_id: "c1", type: "text", text: "مرحباً، عندي مشكلة في الترجمة" });
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.equal(r.json.message.from, "user");
});

await test("an advertising order still needs the consent", async () => {
  const r = await call("POST", "/chat/send", alice, { client_id: "c2", type: "order", order: { slot: "hero" } });
  assert.equal(r.status, 403);
  assert.equal(r.json.error, "consent_required");
});

await test("client_id makes a resend a duplicate, not a second message", async () => {
  const r = await call("POST", "/chat/send", alice, { client_id: "c1", type: "text", text: "مرحباً، عندي مشكلة في الترجمة" });
  assert.equal(r.json.duplicate, true);
  const m = await call("GET", "/chat/messages?limit=40", alice);
  assert.equal(m.json.messages.filter((x) => x.from === "user").length, 1);
});

let threadId = "";
await test("admin list shows the internal ids and finds the thread by id", async () => {
  const r = await call("GET", "/admin/chat/threads?limit=50", env.ADMIN_TOKEN);
  assert.equal(r.status, 200);
  const t = r.json.threads[0];
  threadId = t.id;
  assert.match(t.id, /^u_[0-9a-f]{28}$/);
  assert.equal(t.user_id, "auth-user-42");
  const byUid = await call("GET", "/admin/chat/threads?q=auth-user-42", env.ADMIN_TOKEN);
  assert.equal(byUid.json.threads.length, 1);
  const byKey = await call("GET", "/admin/chat/threads?q=" + threadId.slice(0, 10), env.ADMIN_TOKEN);
  assert.equal(byKey.json.threads.length, 1);
});

await test("developer reply carries the public identity; the user never sees the developer id", async () => {
  const s = await call("POST", "/admin/chat/send", supportToken, { thread: threadId, client_id: "a1", type: "text", text: "أهلاً، سنصلح التوقيت الآن" });
  assert.equal(s.status, 200, JSON.stringify(s.json));
  const u = await call("GET", "/chat/messages?limit=40", alice);
  const dev = u.json.messages.find((x) => x.from === "admin");
  assert.deepEqual(dev.dev, { name: "دعم Cigram", avatar: "" });
  assert.equal(dev.dev_id, undefined);
  const a = await call("GET", "/admin/chat/messages?thread=" + threadId + "&limit=40", env.ADMIN_TOKEN);
  assert.equal(a.json.messages.find((x) => x.from === "admin").dev_id, "dev_support_1");
});

await test("one user cannot read another user's conversation", async () => {
  const r = await call("GET", "/chat/messages?limit=40", bob);
  assert.equal(r.json.messages.length, 0);
  assert.equal(JSON.stringify(r.json).includes("سنصلح"), false);
});

await test("server-authoritative ordering and read receipts", async () => {
  await call("POST", "/chat/read", alice, { upto: 999 });
  const a = await call("GET", "/admin/chat/messages?thread=" + threadId + "&limit=40", env.ADMIN_TOKEN);
  const seqs = a.json.messages.map((m) => m.seq);
  assert.deepEqual(seqs, [...seqs].sort((x, y) => x - y));
  const reply = a.json.messages.find((x) => x.from === "admin");
  assert.ok(reply.read > 0, "the developer sees that the user read the reply");
});

await test("roles: a support developer cannot restrict or purge, the owner can", async () => {
  const ban = await call("POST", "/admin/chat/thread", supportToken, { thread: threadId, banned: true });
  assert.equal(ban.status, 403);
  const purge = await call("POST", "/admin/chat/purge", supportToken, { thread: threadId });
  assert.equal(purge.status, 403);
  const arch = await call("POST", "/admin/chat/thread", supportToken, { thread: threadId, archived: true });
  assert.equal(arch.status, 200);
  const ownerBan = await call("POST", "/admin/chat/thread", env.ADMIN_TOKEN, { thread: threadId, banned: true });
  assert.equal(ownerBan.status, 200);
  const blocked = await call("POST", "/chat/send", alice, { client_id: "c9", type: "text", text: "test" });
  assert.equal(blocked.status, 403);
  assert.equal(blocked.json.error, "banned");
});

await test("a wrong token is refused", async () => {
  const r = await call("GET", "/admin/chat/threads", "nope");
  assert.equal(r.status, 401);
});

await test("presence returns the developer's public identity and last activity", async () => {
  const r = await call("GET", "/chat/presence");
  assert.equal(r.json.developer.name, "حسن سعيد");
  assert.equal(typeof r.json.last_active, "number");
});

console.log(`\n${passed} passed`);

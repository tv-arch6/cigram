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

// ---------------------------------------------------------------- 18.3: authentication of the chat (opaque tokens, like the real auth Worker)
// The real cigram-auth-api issues OPAQUE session tokens (not JWTs); the chat Worker verifies them by calling GET /me through the
// AUTH_API service binding. This stub speaks that contract: 200 {success, user:{id,email,name}} or 401 {success:false}.
const authMode = { mode: "ok", calls: 0 };
const sessions = { "opaque-dave": { id: "auth-user-123", email: "dave@example.invalid", name: "Dave" }, "opaque-erin": { id: "auth-user-456", email: "erin@example.invalid", name: "Erin" } };
const env2 = Object.assign({}, env);
delete env2.JWT_SECRET;
env2.AUTH_API = { fetch: async (url, init) => {
  authMode.calls++;
  if (authMode.mode === "throw") throw new Error("Network connection lost");
  if (authMode.mode === "500") return new Response("upstream error", { status: 500 });
  if (authMode.mode === "429") return new Response("{}", { status: 429 });
  if (authMode.mode === "1042") return new Response("error code: 1042", { status: 404 });
  if (authMode.mode === "malformed") return new Response(JSON.stringify({ success: true }), { status: 200 });
  const t = new Headers(init.headers).get("authorization").replace(/^Bearer /, "");
  const u = sessions[t];
  if (!u) return new Response(JSON.stringify({ success: false, message: "انتهت الجلسة" }), { status: 401 });
  return new Response(JSON.stringify({ success: true, user: u, expires_at: "2026-12-01T00:00:00Z" }), { status: 200 });
} };
const logs = [];
const realLog = console.log;
console.log = (...a) => { if (typeof a[0] === "string" && a[0].startsWith('{"evt":"chat_auth"')) logs.push(a[0]); else realLog(...a); };
const call2 = async (method, path, token, body, headers = {}) => {
  const r = await worker.fetch(new Request("https://ads.test" + path, { method, headers: Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {}, headers), body: body ? JSON.stringify(body) : undefined }), env2, { waitUntil() {} });
  return { status: r.status, json: await r.json() };
};
const realNow = Date.now;
const at = (ms) => { Date.now = () => realNow() + ms; };

await test("an opaque session token is verified through the auth Worker and opens the chat", async () => {
  authMode.mode = "ok";
  const r = await call2("GET", "/chat/thread", "opaque-dave");
  assert.equal(r.status, 200, JSON.stringify(r.json));
  const w = await call2("POST", "/chat/send", "opaque-dave", { client_id: "oa1", type: "text", text: "hello from an opaque-token session" });
  assert.equal(w.status, 200, JSON.stringify(w.json));
});

await test("the identity comes from the verified token, never from the request", async () => {
  // Erin claims to be Dave in a header and in the body: the message must land in Erin's own thread
  const w = await call2("POST", "/chat/send", "opaque-erin", { client_id: "oc1", type: "text", text: "erin writes", user_id: "auth-user-123", thread: "u_dave" }, { "x-user-id": "auth-user-123" });
  assert.equal(w.status, 200, JSON.stringify(w.json));
  const mine = await call2("GET", "/chat/messages?limit=40", "opaque-erin");
  assert.deepEqual(mine.json.messages.map((m) => m.text), ["erin writes"]);
  const alices = await call2("GET", "/chat/messages?limit=40", "opaque-dave");
  assert.ok(alices.json.messages.every((m) => m.text !== "erin writes"), "Dave never sees Erin's message");
});

await test("only a real 401 from the auth Worker is a session expiry (401 invalid_token)", async () => {
  authMode.mode = "ok";
  const r = await call2("GET", "/chat/thread", "opaque-revoked-or-expired");
  assert.equal(r.status, 401);
  assert.equal(r.json.error, "invalid_token");
});

for (const mode of ["500", "429", "throw", "1042", "malformed"]) {
  await test("auth Worker problem '" + mode + "' is a temporary 503 auth_unavailable, NOT a logout", async () => {
    authMode.mode = mode;
    const r = await call2("GET", "/chat/thread", "opaque-never-seen-before-" + mode);
    assert.equal(r.status, 503, JSON.stringify(r.json));
    assert.equal(r.json.error, "auth_unavailable");
    assert.notEqual(r.json.error, "invalid_token");
    authMode.mode = "ok";
  });
}

await test("a token verified in the last 30 minutes keeps working while the auth Worker is down (bounded)", async () => {
  authMode.mode = "ok";
  assert.equal((await call2("GET", "/chat/thread", "opaque-dave")).status, 200);   // verified (cache is refreshed by this call or an earlier one)
  authMode.mode = "500";
  at(6 * 60 * 1000);                                                                  // the 5-minute cache entry has expired
  const during = await call2("GET", "/chat/thread", "opaque-dave");
  assert.equal(during.status, 200, "served from the 30-minute grace window during the outage");
  at(40 * 60 * 1000);                                                                 // beyond the window
  const after = await call2("GET", "/chat/thread", "opaque-dave");
  Date.now = realNow;
  assert.equal(after.status, 503);
  assert.equal(after.json.error, "auth_unavailable");
  authMode.mode = "ok";
});

await test("a revoked session is never served from the grace window", async () => {
  authMode.mode = "ok";
  assert.equal((await call2("GET", "/chat/thread", "opaque-erin")).status, 200);
  delete sessions["opaque-erin"];                                                    // the account logs out / is revoked
  at(6 * 60 * 1000);
  const r = await call2("GET", "/chat/thread", "opaque-erin");                       // auth Worker answers 401 -> invalid
  assert.equal(r.status, 401);
  authMode.mode = "500";
  const again = await call2("GET", "/chat/thread", "opaque-erin");                   // the stale entry was dropped: no grace
  Date.now = realNow;
  assert.equal(again.status, 503);
  authMode.mode = "ok";
});

await test("a message re-sent after a renewal (same client_id) is stored once", async () => {
  authMode.mode = "ok";
  const a = await call2("POST", "/chat/send", "opaque-dave", { client_id: "retry-1", type: "text", text: "sent twice by a retry" });
  const b = await call2("POST", "/chat/send", "opaque-dave", { client_id: "retry-1", type: "text", text: "sent twice by a retry" });
  assert.equal(a.status, 200); assert.equal(b.json.duplicate, true);
  const m = await call2("GET", "/chat/messages?limit=40", "opaque-dave");
  assert.equal(m.json.messages.filter((x) => x.text === "sent twice by a retry").length, 1);
});

await test("auth log lines carry only the outcome and the duration (no token, no user id)", async () => {
  assert.ok(logs.length >= 6);
  for (const l of logs) assert.deepEqual(Object.keys(JSON.parse(l)).sort(), ["evt", "ms", "outcome"]);
  const all = logs.join("");
  for (const secret of ["opaque-dave", "opaque-erin", "auth-user-123", "dave@example"]) assert.ok(!all.includes(secret), secret);
});
console.log = realLog;

console.log(`\n${passed} passed`);

// 19.0 — local test of the cigram-ads-api worker after apply_patch_19.py (Node 22, no network, no Cloudflare account).
// Real worker module: routing, auth, ChatHub durable object with in-memory storage, in-memory R2, a fake FCM / OAuth endpoint.
// Usage: node ads_chat19_test.mjs <path to worker.mjs>
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";

const mod = await import(process.argv[2]);
const { ChatHub } = mod;
const worker = mod.default;

// ---------------------------------------------------------------- in-memory Durable Object storage + sockets
class MemStorage {
  constructor() { this.m = new Map(); this.alarm = null; }
  async get(k) { return this.m.has(k) ? structuredClone(this.m.get(k)) : undefined; }
  async put(k, v) { await Promise.resolve(); this.m.set(k, structuredClone(v)); }
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
const sockets = [];   // { tags, attachment, readyState }
function makeSocket(tags, attachment) {
  const w = { tags, attachment, readyState: 1, sent: [], deserializeAttachment() { return this.attachment; }, serializeAttachment(v) { this.attachment = v; }, send(d) { this.sent.push(d); }, close() { this.readyState = 3; } };
  sockets.push(w);
  return w;
}
function makeState() {
  return { storage: new MemStorage(), getWebSockets: (tag) => sockets.filter((w) => w.readyState === 1 && (!tag || w.tags.includes(tag))), acceptWebSocket() {}, waitUntil() {} };
}
// ---------------------------------------------------------------- in-memory R2
function makeR2() {
  const o = new Map();
  return {
    o,
    async head(k) { return o.has(k) ? { size: new TextEncoder().encode(o.get(k)).length } : null; },
    async put(k, v) { o.set(k, typeof v === "string" ? v : new TextDecoder().decode(v)); },
    async get(k) { return o.has(k) ? { text: async () => o.get(k), arrayBuffer: async () => new TextEncoder().encode(o.get(k)).buffer } : null; },
    async delete(k) { o.delete(k); },
    async list({ prefix = "", cursor } = {}) { return { objects: [...o.keys()].filter((k) => k.startsWith(prefix)).sort().map((key) => ({ key })), truncated: false }; },
  };
}
const b64 = (u8) => Buffer.from(u8).toString("base64");
const env = {
  CHAT_KEY: b64(webcrypto.getRandomValues(new Uint8Array(32))),
  ADMIN_TOKEN: "owner-token-123",
  DEV_NAME: "حسن سعيد",
  JWT_SECRET: "jwt-test-secret",
  CHAT_RETENTION_DAYS: "1",                 // the old retention switch: it must have no effect any more
  ADS_BUCKET: makeR2(),
};
const hubState = makeState();
const hubObj = new ChatHub(hubState, env);
env.CHAT_HUB = { idFromName: () => "hub", get: () => ({ fetch: (u, i) => hubObj.fetch(new Request(u, i)) }) };
env.ADS_CORE = { idFromName: () => "core", get: () => ({ fetch: async (u, i) => {
  const body = JSON.parse(await new Request(u, i).text());
  const ok = (x) => new Response(JSON.stringify(Object.assign({ ok: true }, x)));
  if (body.op === "consent_get") return ok({ valid: true });
  if (body.op === "settings_public") return ok({ settings: { hours: { enabled: false } } });
  if (body.op === "adv_ensure_user") return ok({ advertiser: { id: "adv_1" } });
  if (body.op === "authfail") return ok({ blocked: false });
  return ok({});
} }) };

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
const exp = () => Math.floor(Date.now() / 1000) + 3600;
const alice = await jwt({ sub: "auth-user-42", email: "alice@example.invalid", name: "Alice", exp: exp() });
const bob = await jwt({ sub: "auth-user-77", email: "bob@example.invalid", name: "Bob", exp: exp() });
const ADMIN = env.ADMIN_TOKEN;
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };
const threadOf = async (tok) => (await call("GET", "/admin/chat/threads?archived=all&limit=200", ADMIN)).json.threads.find((t) => t.user_id === tok);

// ================================================================ permanent history
await call("POST", "/chat/send", alice, { client_id: "a-1", type: "text", text: "مرحباً، أحتاج مساعدة في الاشتراك" });
await call("POST", "/chat/send", alice, { client_id: "a-2", type: "text", text: "رقم الطلب 5521" });
const T = (await threadOf("auth-user-42")).id;
await call("POST", "/admin/chat/send", ADMIN, { thread: T, client_id: "r-1", type: "text", text: "أهلاً بك، سأتحقق الآن" });

await test("the purge route can never delete a conversation (owner token included)", async () => {
  const r = await call("POST", "/admin/chat/purge", ADMIN, { thread: T });
  assert.equal(r.status, 409);
  assert.equal(r.json.error, "history_is_permanent");
  const m = await call("GET", "/admin/chat/messages?thread=" + T + "&limit=50", ADMIN);
  assert.equal(m.json.messages.length, 3);
});

await test("a single message can never be deleted from the admin route", async () => {
  const r = await call("POST", "/admin/chat/delete-message", ADMIN, { thread: T, seq: 2 });
  assert.equal(r.status, 409);
  assert.equal(r.json.error, "history_is_permanent");
  const m = await call("GET", "/admin/chat/messages?thread=" + T + "&limit=50", ADMIN);
  assert.ok(m.json.messages.every((x) => !x.deleted && x.text));
});

await test("the hub itself refuses purge / delete_msg (no internal caller can destroy history either)", async () => {
  for (const op of ["purge", "delete_msg"]) {
    const r = await hubObj.fetch(new Request("https://hub/op", { method: "POST", body: JSON.stringify({ op, p: { thread: T, seq: 1 } }) }));
    assert.equal(r.status, 409);
  }
  assert.equal((await hubState.storage.list({ prefix: "m:" + T + ":" })).size, 3);
});

await test("CHAT_RETENTION_DAYS and the alarm never delete an archived conversation, however old", async () => {
  const a = await call("POST", "/admin/chat/thread", ADMIN, { thread: T, archived: true });
  assert.equal(a.json.thread.archived, true);
  const t = await hubState.storage.get("t:" + T);
  t.last_ts = Date.now() - 400 * 864e5;                   // a year old
  await hubState.storage.put("t:" + T, t);
  await hubObj.alarm();
  assert.equal((await hubState.storage.list({ prefix: "m:" + T + ":" })).size, 3);
  assert.ok(await hubState.storage.get("t:" + T));
});

await test("archived conversations stay listed (archived=all), counted and searchable; restore brings them back", async () => {
  const onlyActive = await call("GET", "/admin/chat/threads?limit=50", ADMIN);
  assert.equal(onlyActive.json.threads.find((t) => t.id === T), undefined);
  const all = await call("GET", "/admin/chat/threads?archived=all&limit=50", ADMIN);
  assert.ok(all.json.threads.find((t) => t.id === T));
  assert.equal(all.json.counts.archived, 1);
  const found = await call("GET", "/admin/chat/search?q=" + encodeURIComponent("5521"), ADMIN);
  assert.equal(found.json.hits.length, 1);
  assert.equal(found.json.hits[0].thread, T);
  assert.equal(found.json.hits[0].archived, true);
  const back = await call("POST", "/admin/chat/thread", ADMIN, { thread: T, archived: false });
  assert.equal(back.json.thread.archived, false);
  assert.equal((await call("GET", "/admin/chat/messages?thread=" + T + "&limit=50", ADMIN)).json.messages.length, 3);
});

await test("'delete all' from the user erases the IDENTITY and empties the user's view; the history stays for the admin", async () => {
  const r = await call("POST", "/chat/delete-all", alice, {});
  assert.equal(r.json.history_retained, true);
  const mine = await call("GET", "/chat/messages?limit=50", alice);
  assert.equal(mine.json.messages.length, 0);
  const adm = await call("GET", "/admin/chat/messages?thread=" + T + "&limit=50", ADMIN);
  assert.equal(adm.json.messages.length, 3);
  const t = (await call("GET", "/admin/chat/threads?archived=all&limit=50", ADMIN)).json.threads.find((x) => x.id === T);
  assert.equal(t.email, "");
  assert.equal(t.user_id, "");
  assert.equal(t.erased, true);
  // the person writes again later: only the new message is in THEIR view, the archive is still there
  const s = await call("POST", "/chat/send", alice, { client_id: "a-3", type: "text", text: "رجعت من جديد" });
  assert.equal(s.status, 200);
  const mine2 = await call("GET", "/chat/messages?limit=50", alice);
  assert.deepEqual(mine2.json.messages.map((x) => x.text), ["رجعت من جديد"]);
  assert.equal((await call("GET", "/admin/chat/messages?thread=" + T + "&limit=50", ADMIN)).json.messages.length, 4);
});

// ================================================================ ordering under concurrency
await test("30 concurrent sends from both sides get unique, contiguous sequence numbers and are all stored", async () => {
  const before = (await call("GET", "/admin/chat/messages?thread=" + T + "&limit=100", ADMIN)).json.messages.length;
  const jobs = [];
  for (let i = 0; i < 15; i++) {
    jobs.push(call("POST", "/chat/send", bob, { client_id: "bob-" + i, type: "text", text: "b" + i }));
    jobs.push(call("POST", "/chat/send", alice, { client_id: "al-" + i, type: "text", text: "a" + i }));
  }
  const res = await Promise.all(jobs);
  assert.ok(res.every((r) => r.status === 200), JSON.stringify(res.find((r) => r.status !== 200)));
  const m = await call("GET", "/admin/chat/messages?thread=" + T + "&limit=100", ADMIN);
  const seqs = m.json.messages.map((x) => x.seq);
  assert.equal(new Set(seqs).size, seqs.length);
  assert.equal(seqs.length, before + 15);
  for (let i = 1; i < seqs.length; i++) assert.equal(seqs[i], seqs[i - 1] + 1);
  // a user and the admin writing to the SAME thread at the same instant
  const tid = T;
  const race = [];
  for (let i = 0; i < 12; i++) {                                              // (the per-minute rate limit of a user is 20: only 2 user sends here)
    if (i < 2) race.push(call("POST", "/chat/send", alice, { client_id: "x-u-" + i, type: "text", text: "u" + i }));
    race.push(call("POST", "/admin/chat/send", ADMIN, { thread: tid, client_id: "x-a-" + i, type: "text", text: "d" + i }));
  }
  assert.ok((await Promise.all(race)).every((r) => r.status === 200));
  const m2 = (await call("GET", "/admin/chat/messages?thread=" + tid + "&limit=100", ADMIN)).json.messages;
  const texts = m2.map((x) => x.text);
  for (let i = 0; i < 12; i++) { if (i < 2) assert.ok(texts.includes("u" + i), "lost u" + i); assert.ok(texts.includes("d" + i), "lost d" + i); }
  const s2 = m2.map((x) => x.seq);
  assert.equal(new Set(s2).size, s2.length);
});

await test("a duplicate submission (same client_id) is stored once, concurrently too", async () => {
  const dup = await Promise.all([1, 2, 3, 4].map(() => call("POST", "/chat/send", bob, { client_id: "dup-1", type: "text", text: "مرة واحدة" })));
  assert.ok(dup.every((r) => r.status === 200));
  const bobThread = (await threadOf("auth-user-77")).id;
  const m = (await call("GET", "/admin/chat/messages?thread=" + bobThread + "&limit=100", ADMIN)).json.messages;
  assert.equal(m.filter((x) => x.text === "مرة واحدة").length, 1);
});

// ================================================================ unread / read
await test("unread counters follow the read marker exactly", async () => {
  const B = (await threadOf("auth-user-77")).id;
  const list0 = (await call("GET", "/admin/chat/threads?archived=all&limit=50", ADMIN)).json.threads.find((t) => t.id === B);
  assert.ok(list0.unread_admin >= 16);
  const msgs = (await call("GET", "/admin/chat/messages?thread=" + B + "&limit=100", ADMIN)).json.messages;
  const mid = msgs[3].seq;
  // the previous call marked them delivered, not read; read only up to `mid`
  await call("POST", "/admin/chat/read", ADMIN, { thread: B, upto: mid });
  const l1 = (await call("GET", "/admin/chat/threads?archived=all&limit=50", ADMIN)).json.threads.find((t) => t.id === B);
  assert.equal(l1.unread_admin, msgs.filter((x) => x.from === "user" && x.seq > mid).length);
  await call("POST", "/admin/chat/read", ADMIN, { thread: B, upto: 99999 });
  const l2 = (await call("GET", "/admin/chat/threads?archived=all&limit=50", ADMIN)).json.threads.find((t) => t.id === B);
  assert.equal(l2.unread_admin, 0);
  // the user sees that the admin read it; the admin's reply is unread for the user until the user reads it
  await call("POST", "/admin/chat/send", ADMIN, { thread: B, client_id: "to-bob", type: "text", text: "تم" });
  assert.equal((await call("GET", "/chat/unread", bob)).json.unread, 1);
  await call("POST", "/chat/read", bob, { upto: 99999 });
  assert.equal((await call("GET", "/chat/unread", bob)).json.unread, 0);
  const seen = (await call("GET", "/admin/chat/messages?thread=" + B + "&limit=100", ADMIN)).json.messages.find((x) => x.text === "تم");
  assert.ok(seen.read > 0);
});

// ================================================================ push (FCM v1)
const kp = await webcrypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"]);
const pkcs8 = Buffer.from(await webcrypto.subtle.exportKey("pkcs8", kp.privateKey)).toString("base64").match(/.{1,64}/g).join("\n");
const SA = { type: "service_account", project_id: "test-proj", client_email: "push@test-proj.iam.gserviceaccount.com", private_key: "-----BEGIN PRIVATE KEY-----\n" + pkcs8 + "\n-----END PRIVATE KEY-----\n" };
const realFetch = globalThis.fetch;
let fcmCalls = [];
let fcmMode = "ok";       // ok | 500 | dead
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url || url);
  if (u === "https://oauth2.googleapis.com/token") return new Response(JSON.stringify({ access_token: "ya29.fake", expires_in: 3600 }), { status: 200 });
  if (u.startsWith("https://fcm.googleapis.com/v1/projects/test-proj/messages:send")) {
    const body = JSON.parse(init.body);
    fcmCalls.push({ auth: init.headers.authorization, message: body.message });
    if (fcmMode === "500") return new Response(JSON.stringify({ error: { status: "UNAVAILABLE" } }), { status: 503 });
    if (fcmMode === "dead") return new Response(JSON.stringify({ error: { status: "NOT_FOUND", message: "unregistered" } }), { status: 404 });
    return new Response(JSON.stringify({ name: "projects/test-proj/messages/1" }), { status: 200 });
  }
  return realFetch(url, init);
};

await test("push is reported as not configured until a service account exists, and no message is lost meanwhile", async () => {
  const st = await call("GET", "/admin/chat/push-status", ADMIN);
  assert.equal(st.json.features.push_fcm, false);
  assert.ok(st.json.missing.length >= 1);
  const pres = await call("GET", "/chat/presence", null);
  assert.equal(pres.json.features.permanent_history, true);
});
env.FCM_SERVICE_ACCOUNT = JSON.stringify(SA);

const carol = await jwt({ sub: "auth-user-99", email: "carol@example.invalid", name: "Carol", exp: exp() });
const TOK_C = "fcm-device-token-carol-0000000000000001";
const TOK_ADM = "fcm-device-token-admin-0000000000000001";
await call("POST", "/chat/send", carol, { client_id: "c-1", type: "text", text: "السلام عليكم" });
const C = (await threadOf("auth-user-99")).id;

await test("device registration: invalid tokens are refused, valid ones are stored per account", async () => {
  assert.equal((await call("POST", "/chat/device", carol, { token: "short" })).status, 400);
  const ok = await call("POST", "/chat/device", carol, { token: TOK_C, app: "cigram", v: 91 });
  assert.equal(ok.status, 200);
  assert.equal(ok.json.registered, true);
  assert.equal((await call("POST", "/chat/device", null, { token: TOK_C })).status, 401);
  const adm = await call("POST", "/admin/chat/device", ADMIN, { token: TOK_ADM, app: "admin", v: 9 });
  assert.equal(adm.json.registered, true);
  assert.equal((await call("POST", "/admin/chat/device", "wrong-token", { token: TOK_ADM })).status, 401);
});

await test("an admin reply pushes ONE targeted FCM message to the right user, with name, preview and time", async () => {
  fcmCalls = [];
  const r = await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-1", type: "text", text: "وعليكم السلام، كيف أساعدك؟" });
  assert.equal(r.status, 200);
  assert.equal(fcmCalls.length, 1);
  const m = fcmCalls[0].message;
  assert.equal(m.token, TOK_C);
  assert.equal(m.data.kind, "chat");
  assert.equal(m.data.to, "user");
  assert.equal(m.data.title, "حسن سعيد");
  assert.equal(m.data.body, "وعليكم السلام، كيف أساعدك؟");
  assert.equal(m.data.msg_id, r.json.message.id);
  assert.ok(Number(m.data.ts) > 0);
  assert.equal(m.data.thread, undefined, "the user's push carries no internal thread id");
  assert.equal(m.android.priority, "HIGH");
  const dump = JSON.stringify(fcmCalls[0]);
  assert.equal(dump.includes(ADMIN), false, "no admin credential in the payload");
  assert.equal(dump.includes("owner-token"), false);
});

await test("a repeated notification for the same message id is a no-op (deduplication)", async () => {
  fcmCalls = [];
  const msgs = (await call("GET", "/admin/chat/messages?thread=" + C + "&limit=50", ADMIN)).json.messages;
  const last = msgs.filter((x) => x.from === "admin").pop();
  await hubObj.notify("user", C, { id: last.id, seq: last.seq, ts: last.ts }, "x", "y", {});
  assert.equal(fcmCalls.length, 0);
});

await test("the user's own message does not push to the user; it pushes to the admin device with the thread id", async () => {
  fcmCalls = [];
  const r = await call("POST", "/chat/send", carol, { client_id: "c-2", type: "text", text: "عندي سؤال آخر عن الاشتراك" });
  assert.equal(r.status, 200);
  assert.equal(fcmCalls.length, 1);
  const m = fcmCalls[0].message;
  assert.equal(m.token, TOK_ADM);
  assert.equal(m.data.to, "admin");
  assert.equal(m.data.thread, C);
  assert.equal(m.data.name, "Carol");
  assert.equal(m.data.body, "عندي سؤال آخر عن الاشتراك");
  assert.ok(Number(m.data.unread) >= 1);
});

await test("no push while the recipient has the app in the foreground; a backgrounded socket still gets one", async () => {
  fcmCalls = [];
  const w = makeSocket(["u:" + C], { side: "user", thread: C });
  await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-2", type: "text", text: "رسالة أثناء الاتصال" });
  assert.equal(fcmCalls.length, 0, "foreground socket: delivered live, no push");
  w.attachment = Object.assign({}, w.attachment, { fg: false });            // the app told us it went to the background (WebSocket {"t":"fg","on":false})
  await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-3", type: "text", text: "رسالة والتطبيق في الخلفية" });
  assert.equal(fcmCalls.length, 1);
  w.readyState = 3;
});

await test("an FCM outage is retried from the alarm with back-off, once per message, and delivered when FCM recovers", async () => {
  fcmCalls = [];
  fcmMode = "500";
  const r = await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-4", type: "text", text: "أثناء انقطاع الخدمة" });
  assert.equal(r.status, 200, "the chat send itself never fails because of push");
  assert.equal(fcmCalls.length, 1);
  const ob = [...(await hubState.storage.list({ prefix: "ob:" })).values()];
  assert.equal(ob.length, 1);
  assert.equal(ob[0].tries, 1);
  assert.ok(ob[0].next > Date.now(), "a later retry is scheduled");
  fcmMode = "ok";
  const key = "ob:" + r.json.message.id + ":user";
  const rec = await hubState.storage.get(key);
  rec.next = Date.now() - 1;
  await hubState.storage.put(key, rec);
  await hubObj.alarm();
  assert.equal(fcmCalls.length, 2);
  assert.equal((await hubState.storage.list({ prefix: "ob:" })).size, 0);
  await hubObj.alarm();
  assert.equal(fcmCalls.length, 2, "delivered once, never twice");
});

await test("an unregistered device token is removed instead of retried forever", async () => {
  fcmCalls = [];
  fcmMode = "dead";
  await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-5", type: "text", text: "إلى جهاز قديم" });
  fcmMode = "ok";
  assert.equal((await hubState.storage.list({ prefix: "ob:" })).size, 0);
  assert.equal((await hubState.storage.list({ prefix: "dev:user:" + C + ":" })).size, 0);
  const st = await call("GET", "/admin/chat/push-status", ADMIN);
  assert.ok(st.json.stats.dead >= 1);
});

await test("a phone that changes account stops receiving the previous account's pushes", async () => {
  await call("POST", "/chat/device", carol, { token: TOK_C });
  await call("POST", "/chat/device", bob, { token: TOK_C });            // same phone, Bob signs in
  const B = (await threadOf("auth-user-77")).id;
  assert.equal((await hubState.storage.list({ prefix: "dev:user:" + C + ":" })).size, 0);
  assert.equal((await hubState.storage.list({ prefix: "dev:user:" + B + ":" })).size, 1);
  await call("POST", "/chat/device/remove", bob, { token: TOK_C });        // sign-out
  assert.equal((await hubState.storage.list({ prefix: "dev:user:" + B + ":" })).size, 0);
});

await test("PUSH_WEBHOOK_URL keeps working (with the optional bearer key) next to FCM", async () => {
  const calls = [];
  const prev = globalThis.fetch;
  globalThis.fetch = async (url, init) => { if (String(url).startsWith("https://hook.test/")) { calls.push({ url, init }); return new Response("{}", { status: 200 }); } return prev(url, init); };
  env.PUSH_WEBHOOK_URL = "https://hook.test/push";
  env.PUSH_WEBHOOK_KEY = "hook-secret";
  await call("POST", "/chat/device", carol, { token: TOK_C });
  await call("POST", "/admin/chat/send", ADMIN, { thread: C, client_id: "rc-6", type: "text", text: "عبر webhook" });
  delete env.PUSH_WEBHOOK_URL; delete env.PUSH_WEBHOOK_KEY;
  globalThis.fetch = prev;
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.headers.authorization, "Bearer hook-secret");
  const body = JSON.parse(calls[0].init.body);
  assert.equal(body.kind, "chat");
  assert.equal(body.to, "user");
  assert.equal(JSON.stringify(body).includes(ADMIN), false);
});

// ================================================================ backup / restore
await test("backup refuses the public bucket (movies) and needs a private one", async () => {
  const saved = env.ADS_BUCKET;
  delete env.ADS_BUCKET;
  env.MOVIES_BUCKET = makeR2();
  const r = await call("POST", "/admin/chat/backup", ADMIN, {});
  assert.equal(r.status, 503);
  assert.equal(r.json.error, "backup_not_configured");
  delete env.MOVIES_BUCKET;
  env.ADS_BUCKET = saved;
});

let beforeKeys;
await test("a backup writes immutable segments + an encrypted thread snapshot, verifies, and is idempotent", async () => {
  const r = await call("POST", "/admin/chat/backup", ADMIN, {});
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.ok(r.json.segments >= 3);
  assert.equal(r.json.failed, 0);
  const keys = [...env.ADS_BUCKET.o.keys()];
  assert.ok(keys.some((k) => k.startsWith("chat-backup/v1/msgs/" + C + "/")));
  const snap = keys.find((k) => k.startsWith("chat-backup/v1/_threads/"));
  assert.ok(snap);
  const raw = env.ADS_BUCKET.o.get(snap);
  assert.equal(raw.includes("carol@example.invalid"), false, "the snapshot holds the thread records ENCRYPTED");
  const v = await call("GET", "/admin/chat/backup/verify", ADMIN);
  assert.equal(v.json.ok, true, JSON.stringify(v.json));
  beforeKeys = keys.length;
  const again = await call("POST", "/admin/chat/backup", ADMIN, {});
  assert.equal(again.json.segments, 0);
  assert.equal([...env.ADS_BUCKET.o.keys()].length, beforeKeys);
  const st = await call("GET", "/admin/chat/backup", ADMIN);
  assert.equal(st.json.threads_behind, 0);
  assert.ok(st.json.state.last_ok > 0);
});

await test("support developers cannot run the owner-only backup / restore / verify routes", async () => {
  const sup = "support-tok-1";
  const sha = Buffer.from(await webcrypto.subtle.digest("SHA-256", new TextEncoder().encode(sup))).toString("hex");
  env.DEVELOPERS = JSON.stringify([{ id: "dev_s", name: "دعم", role: "support", token_sha256: sha }]);
  assert.equal((await call("POST", "/admin/chat/backup", sup, {})).status, 403);
  assert.equal((await call("POST", "/admin/chat/restore", sup, {})).status, 403);
  assert.equal((await call("GET", "/admin/chat/backup/verify", sup)).status, 403);
  assert.equal((await call("GET", "/admin/chat/search?q=مرحبا", sup)).status, 200);
  assert.equal((await call("POST", "/admin/chat/purge", sup, { thread: C })).status, 403);
});

await test("restore brings back records the live store lost, and never overwrites a record that exists", async () => {
  const allKeys = [...(await hubState.storage.list({ prefix: "m:" + C + ":" })).keys()];
  const lost = allKeys.slice(0, 2);
  const keepKey = allKeys[allKeys.length - 1];
  const keep = await hubState.storage.get(keepKey);
  for (const k of lost) await hubState.storage.delete(k);
  await hubState.storage.delete("t:" + T);                                      // a whole thread vanished too
  // a record that exists is modified after the backup: restore must not roll it back
  const t = await hubState.storage.get("t:" + C);
  t.notes = "ملاحظة داخلية جديدة";
  await hubState.storage.put("t:" + C, t);
  const r = await call("POST", "/admin/chat/restore", ADMIN, {});
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.equal(r.json.restored_messages >= lost.length, true);
  assert.equal(r.json.restored_threads, 1);
  for (const k of lost) assert.ok(await hubState.storage.get(k), "restored " + k);
  assert.ok(await hubState.storage.get("t:" + T));
  assert.equal((await hubState.storage.get("t:" + C)).notes, "ملاحظة داخلية جديدة");
  assert.deepEqual(await hubState.storage.get(keepKey), keep);
  const msgs = (await call("GET", "/admin/chat/messages?thread=" + C + "&limit=100", ADMIN)).json.messages;
  assert.ok(msgs.some((x) => x.text === "السلام عليكم"));
});

// ================================================================ session check fallback (AUTH_DB)
await test("when the auth Worker cannot be asked, an AUTH_DB binding verifies the session read-only (no session is touched)", async () => {
  const opaque = "opaque-session-token-for-dora";
  const digest = Buffer.from(await webcrypto.subtle.digest("SHA-256", new TextEncoder().encode(opaque))).toString("base64");
  const queries = [];
  const env2 = Object.assign({}, env);
  delete env2.JWT_SECRET;
  env2.AUTH_API = { fetch: async () => { throw new Error("error code: 1042"); } };
  env2.AUTH_DB = { prepare: (sql) => ({ bind: (h) => ({ first: async () => { queries.push({ sql, h }); return h === digest ? { id: "dora-1", email: "Dora@Example.invalid", name: "Dora" } : null; } }) }) };
  const go = async (tok, e) => { const r = await worker.fetch(new Request("https://ads.test/chat/thread", { headers: { authorization: "Bearer " + tok } }), e, { waitUntil() {} }); return { status: r.status, json: await r.json() }; };
  const ok = await go(opaque, env2);
  assert.equal(ok.status, 200, JSON.stringify(ok.json));
  assert.ok(/^SELECT/.test(queries[0].sql) && !/UPDATE|DELETE|INSERT/i.test(queries[0].sql), "read only");
  const bad = await go("opaque-unknown-token", env2);
  assert.equal(bad.status, 401);
  assert.equal(bad.json.error, "invalid_token");
  const env3 = Object.assign({}, env2); delete env3.AUTH_DB;
  const un = await go("opaque-other-token", env3);
  assert.equal(un.status, 503);
  assert.equal(un.json.error, "auth_unavailable");
});

// ================================================================ websocket foreground flag, backup budget
await test("the app's {t:'fg'} message marks its socket as background / foreground (push decision)", async () => {
  const w = makeSocket(["u:" + C], { side: "user", thread: C });
  assert.equal(hubObj.userActive(C), true);
  await hubObj.webSocketMessage(w, JSON.stringify({ t: "fg", on: false }));
  assert.equal(w.attachment.fg, false);
  assert.equal(hubObj.userActive(C), false);
  await hubObj.webSocketMessage(w, JSON.stringify({ t: "fg", on: true }));
  assert.equal(hubObj.userActive(C), true);
  w.readyState = 3;
});

await test("a backlog larger than the per-run budget is backed up over several runs (the budget is a real cap) and restores completely", async () => {
  const dora = await jwt({ sub: "auth-user-big", email: "dora@example.invalid", name: "Dora", exp: exp() });
  await call("POST", "/chat/send", dora, { client_id: "d-0", type: "text", text: "start" });
  const D = (await threadOf("auth-user-big")).id;
  for (let i = 0; i < 110; i++) assert.equal((await call("POST", "/admin/chat/send", ADMIN, { thread: D, client_id: "big-" + i, type: "text", text: "رسالة رقم " + i })).status, 200);   // (an admin may send 120 per minute)
  let runs = 0, lag = 1;
  while (lag > 0 && runs < 10) {
    const r = await call("POST", "/admin/chat/backup", ADMIN, { max_messages: 100, thread: D });
    assert.equal(r.status, 200, JSON.stringify(r.json));
    assert.equal(r.json.failed, 0);
    lag = r.json.lagging_threads;
    runs++;
  }
  assert.equal(runs, 2, "111 messages with a budget of 100 need exactly two runs (" + runs + ")");
  assert.equal((await call("GET", "/admin/chat/backup/verify", ADMIN)).json.ok, true);
  // the whole thread vanishes from the live store; the restore brings back every message in order
  const keys = [...(await hubState.storage.list({ prefix: "m:" + D + ":" })).keys()];
  assert.equal(keys.length, 111);
  for (const k of keys) await hubState.storage.delete(k);
  await hubState.storage.delete("t:" + D);
  const r = await call("POST", "/admin/chat/restore", ADMIN, { thread: D });
  assert.equal(r.json.restored_messages, 111);
  assert.equal(r.json.restored_threads, 1);
  const m = (await call("GET", "/admin/chat/messages?thread=" + D + "&limit=100&before=1000", ADMIN)).json.messages;
  assert.equal(m.length, 100);
  assert.equal(m[m.length - 1].text, "رسالة رقم 109");
  assert.equal((await hubState.storage.get("t:" + D)).last_seq, 111);
});

globalThis.fetch = realFetch;
console.log("\n" + passed + " passed");

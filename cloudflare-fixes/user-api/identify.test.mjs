import test from "node:test";
import assert from "node:assert/strict";
import { sha256 } from "./helpers.mjs";
import { identify as vulnerable } from "./identify.vulnerable.mjs";
import { identify as fixed } from "./identify.fixed.mjs";

// A stand-in for the auth Worker with the behaviour of its real source:
//   GET /reactions  -> PUBLIC: 200 for ANY token (getReactions only reads the token optionally)
//   GET /me         -> 200 + user only for a valid session, otherwise 401
const VALID = "valid-session-token-of-alice";
const ALICE = { id: "11111111-aaaa-bbbb-cccc-222222222222", email: "alice@example.com" };
let authDown = false;
function installAuth() {
  globalThis.fetch = async (url, init) => {
    if (authDown) return new Response("boom", { status: 503 });
    const u = new URL(url);
    const tok = ((init && init.headers && init.headers.authorization) || "").replace(/^Bearer\s+/i, "");
    if (u.pathname === "/reactions") return Response.json({ success: true, likes: 0, dislikes: 0, authenticated: tok === VALID });
    if (u.pathname === "/me") return tok === VALID ? Response.json({ success: true, user: ALICE }) : Response.json({ success: false }, { status: 401 });
    return new Response("nf", { status: 404 });
  };
}
function env(extra) {
  const kv = new Map();
  return Object.assign({
    AUTH_BASE: "https://auth.example.workers.dev",
    CIGRAM_KV: { get: async (k) => kv.get(k) ?? null, put: async (k, v) => { kv.set(k, v); } },
    _kv: kv,
  }, extra || {});
}
const req = (token) => new Request("https://x/user/sync", { method: "POST", headers: token ? { authorization: "Bearer " + token } : {} });
const uidOf = (email) => sha256("u:" + email);

test.beforeEach(() => { authDown = false; installAuth(); });

// ---------------------------------------------------------------- the reproduction (deployed code, AUTH_ME_URL not set)
test("REPRODUCED: deployed identify() accepts a garbage token and takes the victim's identity from the request body", async () => {
  const r = await vulnerable(req("not-a-real-token-at-all"), env(), { email: "victim@example.com" });
  assert.equal(r.error, undefined, "a garbage token was accepted");
  assert.equal(r.uid, await uidOf("victim@example.com"), "…and it resolved to the victim's data key");
});

test("REPRODUCED: the same request without any account reaches another user's key even with a real user's token", async () => {
  const r = await vulnerable(req(VALID), env(), { email: "victim@example.com" });
  assert.equal(r.uid, await uidOf("victim@example.com"), "alice's token opened victim's key");
});

// ---------------------------------------------------------------- the proposed fix
test("FIXED: a garbage token is refused (401)", async () => {
  const r = await fixed(req("not-a-real-token-at-all"), env(), { email: "victim@example.com" });
  assert.equal(r.error.status, 401);
});

test("FIXED: a real token cannot claim someone else's identity (403)", async () => {
  const r = await fixed(req(VALID), env(), { email: "victim@example.com" });
  assert.equal(r.error.status, 403);
});

test("FIXED: the genuine owner gets exactly the same data key as before (existing synced data is still found)", async () => {
  const byEmail = await fixed(req(VALID), env(), { email: "ALICE@example.com", id: ALICE.id });
  assert.equal(byEmail.uid, await vulnerable(req("anything"), env(), { email: "alice@example.com" }).then((x) => x.uid));
  const byId = await fixed(req(VALID), env(), { id: ALICE.id });
  assert.equal(byId.uid, await uidOf(ALICE.id));
});

test("FIXED: a request without a claimed identity uses the verified account", async () => {
  const r = await fixed(req(VALID), env(), {});
  assert.equal(r.uid, await uidOf("alice@example.com"));
});

test("FIXED: no token at all is refused", async () => {
  assert.equal((await fixed(req(""), env(), { email: "alice@example.com" })).error.status, 401);
});

test("FIXED: an auth outage answers 503 (not 401), so phones are not logged out", async () => {
  authDown = true;
  const r = await fixed(req(VALID), env(), { email: "alice@example.com" });
  assert.equal(r.error.status, 503);
});

test("FIXED: an old cache entry '-' written by the deployed code grants nothing", async () => {
  const e = env();
  e._kv.set("tok:" + (await sha256("garbage")), "-");
  const r = await fixed(req("garbage"), e, { email: "victim@example.com" });
  assert.equal(r.error.status, 401);
});

test("FIXED: with AUTH_ME_URL configured the behaviour is unchanged (uid = user_id || id || email)", async () => {
  const e = env({ AUTH_ME_URL: "https://auth.example.workers.dev/me" });
  const ok = await fixed(req(VALID), e, { id: ALICE.id });
  assert.equal(ok.uid, await uidOf(ALICE.id));
  const bad = await fixed(req(VALID), e, { email: "victim@example.com" });
  assert.equal(bad.error.status, 403);
  const old = await vulnerable(req(VALID), env({ AUTH_ME_URL: "https://auth.example.workers.dev/me" }), { id: ALICE.id });
  assert.equal(old.uid, ok.uid, "same key as the deployed code in that mode");
});

test("FIXED: a verified answer is cached, so the auth Worker is called once per token", async () => {
  let calls = 0;
  const base = globalThis.fetch;
  globalThis.fetch = async (...a) => { calls++; return base(...a); };
  const e = env();
  await fixed(req(VALID), e, { email: "alice@example.com" });
  await fixed(req(VALID), e, { email: "alice@example.com" });
  assert.equal(calls, 1);
});

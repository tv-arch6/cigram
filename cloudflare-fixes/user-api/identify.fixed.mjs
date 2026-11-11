// PROPOSED replacement for identify() in cigram-user-api. NOT deployed.
//
// What changes:
//  1. The token is always verified against the auth Worker's /me (a route that really requires a valid session). The old
//     probe, GET /reactions?content_id=__verify__, is PUBLIC in the auth Worker: it answers 200 for any string.
//  2. When AUTH_ME_URL is NOT set (the old "trust the app" mode) the identity the app claims is accepted only when it is
//     one of the verified account's own identifiers (email / id / user_id). Same key derivation as before
//     (sha256("u:" + claimed id)), so every existing synced document is still found.
//  3. When AUTH_ME_URL IS set, behaviour is exactly as before (uid = user_id || id || email).
//  4. An auth outage (5xx / unreachable) answers 503 instead of 401, so phones do not treat it as "logged out".
//  5. Cache entries use a new prefix (tok2:) and hold the verified identifiers; old "tok:" entries (which could hold "-")
//     are never read.
import { json, sha256, bearer, str } from "./helpers.mjs";

const lower = (v) => String(v || "").toLowerCase();

export async function identify(request, env, claimed) {
  const token = bearer(request);
  if (!token) return { error: json({ ok: false, error: "unauthorized" }, 401) };
  const cacheKey = "tok2:" + (await sha256(token));
  let ids = null;
  const cached = await env.CIGRAM_KV.get(cacheKey);
  if (cached) { try { ids = JSON.parse(cached); } catch (e) { ids = null; } }

  if (!ids || typeof ids !== "object") {
    const base = (env.AUTH_BASE || "").replace(/\/+$/, "");
    let res;
    try {
      res = await fetch(env.AUTH_ME_URL || (base + "/me"), { headers: { authorization: "Bearer " + token, accept: "application/json" } });
    } catch (e) {
      return { error: json({ ok: false, error: "auth_unreachable" }, 503) };
    }
    if (res.status >= 500) return { error: json({ ok: false, error: "auth_unavailable" }, 503) };
    if (!res.ok) return { error: json({ ok: false, error: "unauthorized" }, 401) };
    let body = null;
    try { body = await res.json(); } catch (e) { body = null; }
    const u = body && (body.user || body);
    const email = lower(u && u.email), id = lower(u && u.id), userId = lower(u && u.user_id);
    const legacy = lower(u && (u.user_id || u.id || u.email));
    if (!legacy) return { error: json({ ok: false, error: "unauthorized" }, 401) };
    ids = { email, id, user_id: userId, legacy };
    await env.CIGRAM_KV.put(cacheKey, JSON.stringify(ids), { expirationTtl: 600 });
  }

  const c = claimed || {};
  const claimedId = str(c.email || c.id, 120).toLowerCase();
  let uid;
  if (env.AUTH_ME_URL) {
    // unchanged behaviour of the configured mode
    uid = ids.legacy;
    if (claimedId && claimedId !== uid && lower(c.id) !== uid) return { error: json({ ok: false, error: "identity_mismatch" }, 403) };
  } else {
    const mine = [ids.email, ids.id, ids.user_id].filter(Boolean);
    if (!claimedId) uid = ids.email || ids.id || ids.user_id;
    else if (mine.indexOf(claimedId) >= 0) uid = claimedId;                  // same key as before for a genuine owner
    else return { error: json({ ok: false, error: "identity_mismatch" }, 403) };
  }
  if (!uid) return { error: json({ ok: false, error: "no_identity" }, 400) };
  return { uid: await sha256("u:" + uid) };
}

// EXACT copy of identify() as deployed in cigram-user-api (modified 2026-10-24). Do not edit: this is the reproduction subject.
import { json, sha256, bearer, str } from "./helpers.mjs";

export async function identify(request, env, claimed) {
  const token = bearer(request);
  if (!token) return { error: json({ ok: false, error: "unauthorized" }, 401) };
  const th = await sha256(token);
  const cacheKey = "tok:" + th;
  let cached = await env.CIGRAM_KV.get(cacheKey);
  let authUid = cached || "";

  if (!cached) {
    const base = (env.AUTH_BASE || "").replace(/\/+$/, "");
    let status = 0, body = null;
    try {
      const target = env.AUTH_ME_URL || (base + "/reactions?content_id=__verify__");
      const res = await fetch(target, { headers: { authorization: "Bearer " + token } });
      status = res.status;
      try { body = await res.json(); } catch (e) { body = null; }
    } catch (e) {
      return { error: json({ ok: false, error: "auth_unreachable" }, 503) };
    }
    if (status === 401 || status === 403 || status >= 500 || status === 0) {
      return { error: json({ ok: false, error: "unauthorized" }, 401) };
    }
    if (env.AUTH_ME_URL) {
      const u = body && (body.user || body);
      const id = u && (u.user_id || u.id || u.email);
      if (!id) return { error: json({ ok: false, error: "unauthorized" }, 401) };
      authUid = String(id).toLowerCase();
    } else {
      authUid = "-";   // token accepted, identity comes from the app
    }
    await env.CIGRAM_KV.put(cacheKey, authUid, { expirationTtl: 600 });
  }

  const c = claimed || {};
  const claimedId = str(c.email || c.id, 120).toLowerCase();
  let uid;
  if (authUid && authUid !== "-") {
    uid = authUid;
    if (claimedId && claimedId !== uid && String(c.id || "").toLowerCase() !== uid) {
      return { error: json({ ok: false, error: "identity_mismatch" }, 403) };
    }
  } else {
    uid = claimedId;
  }
  if (!uid) return { error: json({ ok: false, error: "no_identity" }, 400) };
  return { uid: await sha256("u:" + uid) };
}

// ===================================================================================================================
// 19.0 — permanent support history, targeted push (FCM HTTP v1), conversation backups.   [inserted by apply_patch_19.py]
// Standalone helpers used by the ChatHub durable object and by the routes. No secret is ever put in a payload or a log.
// ===================================================================================================================
var BACKUP_PREFIX = "chat-backup/v1/";
var PUSH_RETRY_MS = [3e4, 12e4, 6e5, 36e5, 216e5];       // 30 s, 2 min, 10 min, 1 h, 6 h  (then the message is given up: the app still syncs it when opened)
var PUSH_TTL_S = 3 * 24 * 3600;                           // FCM keeps an undelivered push for 3 days
function chat19Features(env) {
  return { permanent_history: true, push_fcm: !!fcmAccount(env), push_webhook: !!env.PUSH_WEBHOOK_URL, backup: !!backupBucket(env), search: true, devices: true, contract: 19 };
}
__name(chat19Features, "chat19Features");
// the backup must never land in the PUBLIC movies bucket: private bucket only (CHAT_BACKUP_BUCKET, else ADS_BUCKET)
function backupBucket(env) {
  return env.CHAT_BACKUP_BUCKET || env.ADS_BUCKET || null;
}
__name(backupBucket, "backupBucket");
function fcmAccount(env) {
  try {
    const sa = typeof env.FCM_SERVICE_ACCOUNT === "string" ? JSON.parse(env.FCM_SERVICE_ACCOUNT) : env.FCM_SERVICE_ACCOUNT;
    return sa && sa.client_email && sa.private_key ? sa : null;
  } catch (e) {
    return null;
  }
}
__name(fcmAccount, "fcmAccount");
var _fcmTok = { v: "", until: 0 };
async function fcmAccessToken(env, sa) {
  const now = Math.floor(Date.now() / 1e3);
  if (_fcmTok.v && now < _fcmTok.until - 60) return _fcmTok.v;
  const enc = /* @__PURE__ */ __name((o) => b64url(new TextEncoder().encode(JSON.stringify(o))), "enc");
  const head = enc({ alg: "RS256", typ: "JWT" });
  const claims = enc({ iss: sa.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging", aud: "https://oauth2.googleapis.com/token", iat: now, exp: now + 3600 });
  const pem = String(sa.private_key).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey("pkcs8", fromB64(pem), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(head + "." + claims));
  const res = await fetch("https://oauth2.googleapis.com/token", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=" + head + "." + claims + "." + b64url(new Uint8Array(sig)) });
  const out = await res.json().catch(() => ({}));
  if (!out.access_token) throw new Error("fcm_oauth_failed");
  _fcmTok = { v: out.access_token, until: now + (out.expires_in || 3600) };
  return _fcmTok.v;
}
__name(fcmAccessToken, "fcmAccessToken");
// One FCM v1 data message to ONE device token. `data` values are strings, no credential inside.
async function fcmSend(env, deviceToken, data, collapse) {
  const sa = fcmAccount(env);
  if (!sa) return { ok: false, skip: "fcm_not_configured" };
  const project = String(env.FCM_PROJECT_ID || sa.project_id || "").trim();
  if (!project) return { ok: false, skip: "fcm_no_project" };
  for (let attempt = 0; attempt < 2; attempt++) {
    let at;
    try {
      at = await fcmAccessToken(env, sa);
    } catch (e) {
      return { ok: false, retry: true, code: "oauth" };
    }
    const android = { priority: "HIGH", ttl: PUSH_TTL_S + "s" };
    if (collapse) android.collapse_key = collapse;
    let res;
    try {
      res = await fetch("https://fcm.googleapis.com/v1/projects/" + project + "/messages:send", { method: "POST", headers: { authorization: "Bearer " + at, "content-type": "application/json" }, body: JSON.stringify({ message: { token: deviceToken, data, android } }) });
    } catch (e) {
      return { ok: false, retry: true, code: "network" };
    }
    if (res.ok) return { ok: true };
    const j = await res.json().catch(() => ({}));
    const st = j && j.error && j.error.status || "";
    if (res.status === 401 && attempt === 0) {
      _fcmTok = { v: "", until: 0 };
      continue;
    }
    const dead = res.status === 404 || st === "NOT_FOUND" || st === "UNREGISTERED" || res.status === 400 && st === "INVALID_ARGUMENT";
    return { ok: false, status: res.status, code: st || String(res.status), dead, retry: !dead && (res.status === 429 || res.status >= 500 || res.status === 401) };
  }
  return { ok: false, retry: true, code: "auth" };
}
__name(fcmSend, "fcmSend");
// Fallback verification of a user session straight from the auth database (read only, same rule as the auth Worker:
// hashed token + active user + not expired). Used ONLY when the auth Worker could not be asked (binding / URL problem),
// it never touches the session (no last-seen write, no fingerprint check), and it is skipped when AUTH_DB is not bound.
async function verifyViaD1(env, tok) {
  if (!env.AUTH_DB || typeof env.AUTH_DB.prepare !== "function") return null;
  try {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(tok)));
    let bin = "";
    for (const b of digest) bin += String.fromCharCode(b);
    const row = await env.AUTH_DB.prepare("SELECT u.id AS id, u.email AS email, u.name AS name FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.token_hash = ? AND u.is_active = 1 AND datetime(s.expires_at) > datetime('now') LIMIT 1").bind(btoa(bin)).first();
    if (!row) return { invalid: true };
    return { id: String(row.id || ""), email: String(row.email || "").toLowerCase(), name: String(row.name || "").slice(0, 100) };
  } catch (e) {
    return null;
  }
}
__name(verifyViaD1, "verifyViaD1");

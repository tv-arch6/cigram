// ----------------------------------------------------------------------------- 18.2 subtitle providers
// OpenSubtitles hardening, OpenSubtitles AI translation, provider health, approvals and the no-overwrite guard.
//
// The OpenSubtitles parts follow the official REST reference (https://ai.opensubtitles.com/api-docs, read 2026-11-08):
//   * base https://api.opensubtitles.com/api/v1 ; after /login every request continues on the returned base_url
//     (vip-api.opensubtitles.com for VIP accounts, with the JWT on every request);
//   * headers: Api-Key (application key), Authorization: Bearer <token> (user), User-Agent "<APP_NAME> v<APP_VERSION>";
//   * GET /subtitles  - ids without leading zeroes, parameters sorted and lowercase (avoids redirects / cache misses),
//     parent_imdb_id / parent_tmdb_id + season_number + episode_number for episodes, no ordering (expensive for them);
//   * POST /download  {file_id, sub_format} -> {link, remaining, reset_time_utc...}; the link is temporary (3 h) and UTF-8;
//   * POST /login is "expensive": 1 request / second and after a 401 the same credentials must not be sent again;
//   * GET /ai/info/translation (no account needed) lists the translation APIs, their Arabic support and price per character;
//   * POST /ai/translate -> {status: CREATED, correlation_id}; GET /ai/translate/{correlation_id} -> CREATED | PENDING |
//     COMPLETED (data.url, data.quality.valid, ...) | ERROR | TIMEOUT. It spends the account's credits.
// The page documents only the 200 responses: other status codes are handled generically (message passed through).
// SubDL and SubSource keep the owner's existing adapters; their documentation could not be read (SubDL's is behind a
// Cloudflare browser challenge, which is never bypassed), so they are only "connected" after a real request succeeded.
// Secrets stay in Cloudflare (OPENSUBTITLES_API_KEY / _USERNAME / _PASSWORD, SUBDL_API_KEY, SUBSOURCE_API_KEY).

const OS_API_HOST = "api.opensubtitles.com";
const OS_UA = "CigramAdmin v18.2";
const SUBHUB_STATE_FILE = "subhub/providers.json";
const SUBHUB_APPROVALS_FILE = "subhub/approved.json";
const SUBHUB_MAX_SOURCE = 2 * 1024 * 1024;
const SUBHUB_MAX_CUES = 6000;
let OS_SESSION = { token: "", base: OS_API_HOST, at: 0, vip: false, fp: "", allowed_downloads: 0 };
let OS_LOGIN_BLOCK = { fp: "", until: 0 };
const OS_WINDOW = { t: 0, n: 0 };
const SUBHUB_NOTE_MEM = new Map();
let OS_TRANSLATION_CATALOGUE = { at: 0, data: null };

function subhubSleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/** "tt0133093" / "0133093" / 133093 -> "133093" (the API wants no leading zeroes). */
function subsOsId(v) {
  const m = cleanString(v).replace(/^tt/i, "").replace(/^0+/, "");
  return /^\d{1,12}$/.test(m) ? m : "";
}

// ----------------------------------------------------------------------------- 18.3 conditional GET for the public catalogue
// GET /movies used to send the whole 5.3 MB R2 object every time (with the ETag header, but ignoring If-None-Match). R2 evaluates the
// preconditions itself (get(key, { onlyIf: request.headers })): an unchanged catalogue now answers 304 without a body, a changed one
// is sent in full exactly as before. `no-cache` = clients revalidate on every request, so a title added in the admin app is visible at once.
async function returnR2JsonConditional(request, env, key, missingValue = null) {
  const obj = await env.MOVIES_BUCKET.get(key, { onlyIf: request.headers });
  if (!obj) {
    if (missingValue !== null) return json(missingValue);
    return json({ ok: false, error: "not_found", object: key }, 404);
  }
  const headers = corsHeaders({
    "content-type": "application/json; charset=utf-8",
    etag: obj.httpEtag || "",
    "cache-control": "no-cache",
    "access-control-expose-headers": "ETag",
  });
  if (obj.body === undefined || obj.body === null) return new Response(null, { status: 304, headers });   // If-None-Match matched
  return new Response(obj.body, { status: 200, headers });
}

// ------------------------------------------------------------------ provider health ("connected" = a real call worked)
async function subhubNote(env, id, ok, detail) {
  const now = Date.now();
  const prev = SUBHUB_NOTE_MEM.get(id);
  if (prev && prev.ok === ok && now - prev.at < 600000) return;   // one write per state change / 10 min
  SUBHUB_NOTE_MEM.set(id, { ok, at: now });
  try {
    await wnMutate(env, SUBHUB_STATE_FILE, { providers: {} }, (doc) => {
      doc.providers = doc.providers && typeof doc.providers === "object" ? doc.providers : {};
      const p = doc.providers[id] || {};
      const iso = new Date(now).toISOString();
      if (ok) { p.last_ok = iso; p.last_ok_what = cleanString(detail).slice(0, 80); }
      else { p.last_error = cleanString(detail).slice(0, 200); p.last_error_at = iso; }
      doc.providers[id] = p;
      return { doc };
    });
  } catch { /* health is best effort and never breaks a search */ }
}

function subhubState(configured, rec) {
  if (!configured) return "not_configured";
  if (!rec) return "configured_unverified";
  const ok = rec.last_ok || "", bad = rec.last_error_at || "";
  if (bad && bad >= ok) return "error";
  return ok ? "connected" : "configured_unverified";
}

// ------------------------------------------------------------------ OpenSubtitles transport
async function osThrottle(env) {
  const now = Date.now();
  if (now - OS_WINDOW.t > 10000) { OS_WINDOW.t = now; OS_WINDOW.n = 0; }
  OS_WINDOW.n++;
  if (OS_WINDOW.n > (numOf(env && env.OS_RATE_LIMIT_10S) || 36)) {          // documented ceiling: 40 requests / 10 s (we stay below it)
    const wait = 10000 - (now - OS_WINDOW.t);
    if (wait > 4000) throw new AdminError("provider_rate_limited", "OpenSubtitles: تجاوز حد الطلبات المحلي. أعد المحاولة بعد قليل.", 429, { provider: "opensubtitles", retry_after_ms: wait });
    await subhubSleep(wait + 50);
    OS_WINDOW.t = Date.now(); OS_WINDOW.n = 1;
  }
}

async function subhubFingerprint(env) {
  const raw = cleanString(env.OPENSUBTITLES_API_KEY) + "|" + cleanString(env.OPENSUBTITLES_USERNAME) + "|" + cleanString(env.OPENSUBTITLES_PASSWORD);
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(raw));
  return Array.from(new Uint8Array(d)).slice(0, 6).map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Login once, keep the token and the returned base_url in memory (never written to storage). Returns null when no login is configured. */
async function osSession(env, force) {
  const k = subsKeys(env);
  if (!k.opensubtitles) throw new AdminError("os_no_key", "OPENSUBTITLES_API_KEY غير مضبوط في Cloudflare.", 400, { provider: "opensubtitles" });
  if (!k.opensubtitles_login) return null;
  const fp = await subhubFingerprint(env);
  const now = Date.now();
  if (!force && OS_SESSION.token && OS_SESSION.fp === fp && now - OS_SESSION.at < 20 * 3600 * 1000) return OS_SESSION;
  if (OS_LOGIN_BLOCK.fp === fp && now < OS_LOGIN_BLOCK.until) {
    throw new AdminError("os_login_blocked", "رفض OpenSubtitles بيانات الدخول سابقاً؛ لن تُرسل نفس البيانات مرة أخرى قبل تغييرها (الوثائق تمنع ذلك).", 502, { provider: "opensubtitles", retry_after_ms: OS_LOGIN_BLOCK.until - now });
  }
  await osThrottle(env);
  let res;
  try {
    res = await subsFetch("https://" + OS_API_HOST + "/api/v1/login", {
      method: "POST",
      headers: { "content-type": "application/json", accept: "application/json", "api-key": k.opensubtitles, "user-agent": OS_UA },
      body: JSON.stringify({ username: cleanString(env.OPENSUBTITLES_USERNAME), password: cleanString(env.OPENSUBTITLES_PASSWORD) }),
    });
  } catch {
    throw new AdminError("os_unreachable", "تعذر الاتصال بـ OpenSubtitles.", 502, { provider: "opensubtitles" });
  }
  const data = await res.json().catch(() => null);
  if (res.status === 401 || res.status === 400) {
    OS_LOGIN_BLOCK = { fp, until: now + 30 * 60 * 1000 };
    await subhubNote(env, "opensubtitles", false, "login rejected (" + res.status + ")");
    throw new AdminError("os_login_failed", "رفض OpenSubtitles اسم المستخدم أو كلمة المرور. راجع OPENSUBTITLES_USERNAME / OPENSUBTITLES_PASSWORD.", 502, { provider: "opensubtitles" });
  }
  if (res.status === 429) {
    const ra = Math.max(1, Number(res.headers.get("retry-after")) || 5);
    throw new AdminError("provider_rate_limited", "OpenSubtitles: حد تسجيل الدخول (طلب كل ثانية). أعد المحاولة.", 429, { provider: "opensubtitles", retry_after_ms: ra * 1000 });
  }
  if (!res.ok || !data || !data.token) {
    await subhubNote(env, "opensubtitles", false, "login " + res.status);
    throw new AdminError("os_login_failed", "تعذر تسجيل الدخول إلى OpenSubtitles (" + res.status + ").", 502, { provider: "opensubtitles" });
  }
  const base = /^[a-z0-9.-]+\.opensubtitles\.com$/i.test(cleanString(data.base_url)) ? cleanString(data.base_url).toLowerCase() : OS_API_HOST;
  OS_SESSION = { token: data.token, base, at: now, vip: Boolean(data.user && data.user.vip), fp, allowed_downloads: numOf(data.user && data.user.allowed_downloads) };
  await subhubNote(env, "opensubtitles", true, "login");
  return OS_SESSION;
}

/** One OpenSubtitles call. query: sorted + lowercase; bearer: user token required; the host follows login's base_url. */
async function osFetch(env, path, o = {}) {
  const key = cleanString(env.OPENSUBTITLES_API_KEY);
  if (!key) throw new AdminError("os_no_key", "OPENSUBTITLES_API_KEY غير مضبوط في Cloudflare.", 400, { provider: "opensubtitles" });
  let session = null;
  try { session = await osSession(env, false); } catch (e) { if (o.bearer || !(e instanceof AdminError)) throw e; }   // searches fall back to the application key alone
  if (o.bearer && !session) throw new AdminError("os_login_missing", "هذه العملية تحتاج OPENSUBTITLES_USERNAME و OPENSUBTITLES_PASSWORD.", 400, { provider: "opensubtitles" });
  await osThrottle(env);
  const u = new URL("https://" + ((session && session.base) || OS_API_HOST) + "/api/v1" + path);
  if (o.query) for (const name of Object.keys(o.query).sort()) {
    const v = o.query[name];
    if (v !== undefined && v !== null && String(v) !== "") u.searchParams.set(name, String(v).toLowerCase());
  }
  const headers = { accept: "application/json", "api-key": key, "user-agent": OS_UA };
  if (session) headers.authorization = "Bearer " + session.token;
  let body;
  if (o.json) { headers["content-type"] = "application/json"; body = JSON.stringify(o.json); }
  else if (o.form) body = o.form;                      // multipart: the runtime sets the boundary
  let res;
  try {
    res = await subsFetch(u.toString(), { method: o.method || "GET", headers, body }, o.timeout || 15000);
  } catch {
    throw new AdminError("os_unreachable", "تعذر الاتصال بـ OpenSubtitles.", 502, { provider: "opensubtitles" });
  }
  if (res.status === 401 && session && !o.retried) {      // expired token: log in again once
    await osSession(env, true);
    return osFetch(env, path, Object.assign({}, o, { retried: true }));
  }
  if (res.status === 429) {
    const ra = Math.max(1, Number(res.headers.get("retry-after")) || 5);
    await subhubNote(env, "opensubtitles", false, "rate limited (429)");
    throw new AdminError("provider_rate_limited", "OpenSubtitles: عدد الطلبات كبير. أعد المحاولة بعد " + ra + " ثانية.", 429, { provider: "opensubtitles", retry_after_ms: ra * 1000 });
  }
  return res;
}

// ------------------------------------------------------------------ OpenSubtitles search (replaces the 10-line v10 version)
/** Id variants to try, TMDB first (project rule), then IMDb; a title without any id is searched by name only when it is not a library title. */
function subsOsQueries(t) {
  const out = [];
  const add = (q) => out.push(Object.assign({ languages: "ar", type: t.type === "tv" ? "episode" : "movie" }, q));
  const tv = t.type === "tv";
  const se = tv ? Object.assign(t.season > 0 ? { season_number: t.season } : {}, t.episode > 0 ? { episode_number: t.episode } : {}) : {};
  if (subsOsId(t.tmdb_id)) add(Object.assign(tv ? { parent_tmdb_id: subsOsId(t.tmdb_id) } : { tmdb_id: subsOsId(t.tmdb_id) }, se));
  if (subsOsId(t.imdb_id)) add(Object.assign(tv ? { parent_imdb_id: subsOsId(t.imdb_id) } : { imdb_id: subsOsId(t.imdb_id) }, se));
  if (!out.length) add(Object.assign({ query: t.original || t.title }, se));
  // 19.4: the byte hash of OUR video (see subhub_moviehash.js) rides on every variant; rows with moviehash_match are exact
  if (/^[0-9a-f]{16}$/.test(cleanString(t.moviehash))) for (const q of out) q.moviehash = t.moviehash;
  return out;
}

async function subsSearchOpenSubs(env, t) {
  if (!subsKeys(env).opensubtitles) return { skipped: "no_key", items: [] };
  let lastError = "";
  for (const q of subsOsQueries(t)) {          // the next id variant is only asked when the previous one found nothing
    try {
      const res = await osFetch(env, "/subtitles", { query: q });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data) {
        lastError = "OpenSubtitles " + res.status + (data && data.message ? ": " + cleanString(data.message).slice(0, 80) : "");
        await subhubNote(env, "opensubtitles", false, "search " + lastError);
        continue;
      }
      await subhubNote(env, "opensubtitles", true, "search");
      const items = [];
      for (const row of Array.isArray(data.data) ? data.data : []) {
        const a = row.attributes || {};
        if (a.language && !subsIsArabic(a.language)) continue;
        const file = Array.isArray(a.files) && a.files[0];
        if (!file || !file.file_id) continue;
        const fd = a.feature_details || {};
        items.push({
          source: "opensubtitles", id: String(file.file_id), name: cleanString(file.file_name || a.release), release: cleanString(a.release || file.file_name),
          author: cleanString(a.uploader && a.uploader.name), downloads: numOf(a.download_count), rating: Number(a.ratings) || 0,
          hi: Boolean(a.hearing_impaired), season: numOf(fd.season_number), episode: numOf(fd.episode_number), full_season: false, dl: "opensubtitles:" + file.file_id,
          trusted: Boolean(a.from_trusted), ai_translated: Boolean(a.ai_translated), machine_translated: Boolean(a.machine_translated), fps: Number(a.fps) || 0,
          hash_match: Boolean(t.moviehash) && a.moviehash_match === true,   // 19.4: same release, byte-exact
        });
      }
      if (items.length) return { items };
      lastError = "";
    } catch (e) {
      lastError = e instanceof AdminError ? e.message : "تعذر الاتصال بـ OpenSubtitles";
      await subhubNote(env, "opensubtitles", false, "search " + lastError);
      if (e instanceof AdminError && (e.code === "provider_rate_limited" || e.code === "os_no_key")) break;
    }
  }
  return { items: [], error: lastError };
}

/** POST /download -> temporary link -> UTF-8 bytes. Quota / rate-limit answers become explicit errors. */
async function osDownloadBytes(env, fileId) {
  const fid = numOf(fileId);
  if (!fid) throw new AdminError("subs_download_failed", "معرّف ملف OpenSubtitles غير صالح.", 400);
  const res = await osFetch(env, "/download", { method: "POST", json: { file_id: fid, sub_format: "srt" }, bearer: true });
  const d = await res.json().catch(() => null);
  const msg = cleanString(d && d.message);
  if (res.status === 406 || /quota/i.test(msg)) {
    await subhubNote(env, "opensubtitles", false, "download quota");
    throw new AdminError("os_quota_exceeded", "استُهلكت حصة التنزيل في OpenSubtitles. " + msg.slice(0, 120), 429, { provider: "opensubtitles", reset_time_utc: cleanString(d && d.reset_time_utc) });
  }
  if (!res.ok || !d || !d.link) throw new AdminError("subs_download_failed", "OpenSubtitles " + res.status + (msg ? ": " + msg.slice(0, 100) : ""), 502);
  let link;
  try { link = new URL(d.link); } catch { throw new AdminError("subs_download_failed", "رابط التنزيل غير صالح.", 502); }
  if (link.protocol !== "https:" || !/(^|\.)opensubtitles\.(com|org)$/i.test(link.hostname)) {
    throw new AdminError("subs_download_failed", "رابط التنزيل خارج نطاق OpenSubtitles.", 502);   // never fetch an arbitrary host
  }
  const f = await subsFetch(link.toString(), { headers: { "user-agent": OS_UA } }, 20000);
  if (!f.ok) throw new AdminError("subs_download_failed", "OpenSubtitles " + f.status, 502);
  await subhubNote(env, "opensubtitles", true, "download");
  return new Uint8Array(await f.arrayBuffer());
}

// ------------------------------------------------------------------ OpenSubtitles AI translation
async function osTranslationCatalogue(env) {
  const now = Date.now();
  if (OS_TRANSLATION_CATALOGUE.data && now - OS_TRANSLATION_CATALOGUE.at < 3600000) return OS_TRANSLATION_CATALOGUE.data;
  const res = await osFetch(env, "/ai/info/translation", {});
  const data = await res.json().catch(() => null);
  if (!res.ok || !data || !Array.isArray(data.data)) throw new AdminError("provider_error", "تعذر قراءة قائمة خدمات الترجمة من OpenSubtitles (" + res.status + ").", 502, { provider: "opensubtitles_ai" });
  const apis = data.data.map((a) => ({
    name: cleanString(a.name), label: cleanString(a.display_name), price: Number(a.price) || 0, pricing: cleanString(a.pricing),
    reliability: cleanString(a.reliability), speed: cleanString(a.speed),
    supports_ar: Array.isArray(a.languages_supported) && a.languages_supported.some((l) => cleanString(l.language_code).toLowerCase() === "ar"),
  }));
  OS_TRANSLATION_CATALOGUE = { at: now, data: apis };
  await subhubNote(env, "opensubtitles_ai", true, "catalogue");
  return apis;
}

function subhubCues(srt) {
  const out = [];
  const re = /(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})/g;
  let m;
  while ((m = re.exec(srt))) {
    out.push([((+m[1] * 60 + +m[2]) * 60 + +m[3]) * 1000 + +m[4], ((+m[5] * 60 + +m[6]) * 60 + +m[7]) * 1000 + +m[8]]);
  }
  return out;
}

function subhubStamp(ms) {
  const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, s = Math.floor(ms / 1000) % 60, x = ms % 1000;
  return String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0") + ":" + String(s).padStart(2, "0") + "," + String(x).padStart(3, "0");
}

/** The translation must keep the source timing: same cue count -> source timestamps are restored; different count -> refused. */
function subhubKeepTiming(sourceSrt, translatedSrt) {
  const a = subhubCues(sourceSrt), b = subhubCues(translatedSrt);
  if (a.length !== b.length) return { ok: false, reason: "cue_count_differs", source: a.length, translated: b.length };
  let changed = 0;
  for (let i = 0; i < a.length; i++) if (a[i][0] !== b[i][0] || a[i][1] !== b[i][1]) changed++;
  if (!changed) return { ok: true, srt: translatedSrt, restored: 0, cues: a.length };
  let i = 0;
  const srt = translatedSrt.replace(/(\d{2}):(\d{2}):(\d{2})[,.](\d{3})(\s*-->\s*)(\d{2}):(\d{2}):(\d{2})[,.](\d{3})/g, () => {
    const c = a[i++];
    return subhubStamp(c[0]) + " --> " + subhubStamp(c[1]);
  });
  return { ok: true, srt, restored: changed, cues: a.length };
}

async function subhubSourceFrom(input) {
  const src = String(input.source_file || "");
  if (!src || src.length > SUBHUB_MAX_SOURCE) throw new AdminError("invalid_source", "ملف المصدر مفقود أو أكبر من 2 ميغابايت.", 400);
  const parsed = subsToSrt(src);
  if (parsed.cues < 1) throw new AdminError("invalid_source", "ملف المصدر لا يحتوي مقاطع صالحة.", 400);
  if (parsed.cues > SUBHUB_MAX_CUES) throw new AdminError("invalid_source", "ملف المصدر يحتوي مقاطع أكثر من اللازم.", 400);
  return parsed;
}

function subhubEstimate(srt, price) {
  const text = srt.split(/\r?\n/).filter((l) => l && !/^\d+$/.test(l.trim()) && !/-->/.test(l)).join("\n");
  return { chars_file: srt.length, chars_text: text.length, unit_price: price, credits_max: Math.ceil(srt.length * price), credits_text: Math.ceil(text.length * price) };
}

async function subhubApprovals(env) {
  const doc = await wnRead(env, SUBHUB_APPROVALS_FILE, { items: {} });
  return doc && doc.items && typeof doc.items === "object" ? doc.items : {};
}

const SUBHUB_PROVIDERS = {
  opensubtitles: { label: "OpenSubtitles.com", role: "search+download", docs: "https://ai.opensubtitles.com/api-docs", docs_checked: true },
  opensubtitles_ai: { label: "OpenSubtitles AI translation", role: "translation", docs: "https://ai.opensubtitles.com/api-docs", docs_checked: true },
  subdl: { label: "SubDL", role: "search+download", docs: "https://subdl.com/api-doc", docs_checked: false },
  subsource: { label: "SubSource", role: "search+download", docs: "", docs_checked: false },
  tmdb: { label: "TMDB", role: "ids+discovery", docs: "https://developer.themoviedb.org/reference/intro/getting-started", docs_checked: true },
};

function subhubConfigured(env, id) {
  const k = subsKeys(env);
  if (id === "opensubtitles") return Boolean(k.opensubtitles);
  if (id === "opensubtitles_ai") return Boolean(k.opensubtitles && k.opensubtitles_login);
  if (id === "subdl") return Boolean(k.subdl);
  if (id === "subsource") return Boolean(k.subsource);
  if (id === "tmdb") { try { tmdbCredentials(env); return true; } catch { return false; } }
  return false;
}

async function handleSubhubProviders(request, env) {
  const st = (await wnRead(env, SUBHUB_STATE_FILE, { providers: {} })).providers || {};
  const out = Object.entries(SUBHUB_PROVIDERS).map(([id, p]) => {
    const configured = subhubConfigured(env, id);
    const rec = st[id] || null;
    return {
      id, label: p.label, role: p.role, docs: p.docs, docs_checked: p.docs_checked, implemented: true, configured,
      state: id === "opensubtitles_ai" && !(rec && rec.last_ok_what === "translation") ? (configured ? "configured_unverified" : "not_configured") : subhubState(configured, rec),
      verified_at: rec && rec.last_ok ? rec.last_ok : "", last_error: rec && rec.last_error ? rec.last_error : "", last_error_at: rec && rec.last_error_at ? rec.last_error_at : "",
      download_login: id === "opensubtitles" ? subsKeys(env).opensubtitles_login : undefined,
    };
  });
  // "admin-private" files (undo journal, discovery config, subtitle jobs, approvals) live under _admin_private/ in the SAME public
  // bucket unless a private ADMIN_BUCKET is bound; without ADMIN_PATH_SALT the path is guessable and r2.dev serves it. Surfaced here so
  // the admin sees it in the app (checked live on 2026-11-08: /_admin_private/whatsnew/config.json answered 200 without any key).
  const storage = { admin_private_bucket: Boolean(env.ADMIN_BUCKET), path_salt: Boolean(cleanString(env.ADMIN_PATH_SALT)) };
  storage.warning = storage.admin_private_bucket ? "" : "ملفات الإدارة الخاصة محفوظة داخل الدلو العام" + (storage.path_salt ? " (بمسار مملّح)." : " بمسار معروف: يمكن لأي شخص قراءتها. اربط دلواً خاصاً ADMIN_BUCKET.");
  return okResponse({ providers: out, storage, target_languages: ["ar"], states: ["not_configured", "configured_unverified", "connected", "error"] });
}

/** Runs ONE real, free request per provider (a search / list call). Translation is never billed here. */
async function handleSubhubVerify(request, env) {
  wnRate("subhub_verify", 20);
  const input = await readRequestData(request);
  const id = cleanString(input.provider);
  if (!SUBHUB_PROVIDERS[id]) throw new AdminError("unknown_provider", "مزوّد غير معروف.", 400);
  if (!subhubConfigured(env, id)) {
    return okResponse({ provider: id, state: "not_configured", detail: "المفتاح غير مضبوط في Cloudflare." }, "غير مضبوط.");
  }
  const matrix = { type: "movie", title: "The Matrix", original: "The Matrix", year: "1999", imdb_id: "tt0133093", tmdb_id: "603", season: 0, episode: 0, release: "" };
  let detail = "";
  let ok = false;
  try {
    if (id === "opensubtitles") {
      const r = await subsSearchOpenSubs(env, matrix);
      ok = !r.error && !r.skipped; detail = ok ? "بحث حقيقي نجح (" + r.items.length + " ترجمة عربية)" : r.error;
      if (ok && subsKeys(env).opensubtitles_login) { const s = await osSession(env, false); detail += " · تسجيل الدخول نجح" + (s && s.vip ? " (VIP)" : ""); }
    } else if (id === "subdl") {
      const r = await subsSearchSubdl(env, matrix);
      ok = !r.error && !r.skipped; detail = ok ? "بحث حقيقي نجح (" + r.items.length + ")" : r.error;
      await subhubNote(env, "subdl", ok, detail);
    } else if (id === "subsource") {
      const r = await subsSearchSubsource(env, matrix);
      ok = !r.error && !r.skipped; detail = ok ? "بحث حقيقي نجح (" + r.items.length + ")" : r.error;
      await subhubNote(env, "subsource", ok, detail);
    } else if (id === "tmdb") {
      await wnTmdb(env, "/configuration", {}, { ttl: 0 });
      ok = true; detail = "طلب TMDB /configuration نجح";
      await subhubNote(env, "tmdb", true, "configuration");
    } else if (id === "opensubtitles_ai") {
      const apis = await osTranslationCatalogue(env);
      await osSession(env, false);
      ok = true; detail = "الفهرس والدخول يعملان (" + apis.filter((a) => a.supports_ar).length + " خدمة تدعم العربية). الترجمة نفسها لم تُجرَّب: تحتاج رصيداً وموافقة.";
    }
  } catch (e) {
    ok = false; detail = e instanceof AdminError ? e.message : "فشل غير متوقع";
    if (id !== "opensubtitles") await subhubNote(env, id, false, detail);
  }
  const st = (await wnRead(env, SUBHUB_STATE_FILE, { providers: {} })).providers || {};
  return okResponse({ provider: id, ok, detail, state: id === "opensubtitles_ai" ? (ok ? "configured_unverified" : "error") : subhubState(true, st[id]) }, ok ? "نجح الطلب الحقيقي." : "فشل الطلب.");
}

async function handleSubhubOptions(request, env) {
  const apis = await osTranslationCatalogue(env);
  return okResponse({ provider: "opensubtitles_ai", apis, target: "ar", note: "الأسعار من GET /ai/info/translation (رصيد لكل حرف). التقدير للمعاينة فقط." });
}

async function handleSubhubEstimate(request, env) {
  wnRate("subhub_estimate", 120);
  const input = await readRequestData(request);
  const parsed = await subhubSourceFrom(input);
  const apis = await osTranslationCatalogue(env);
  const name = cleanString(input.api);
  const api = apis.find((a) => a.name === name);
  if (!api) throw new AdminError("invalid_api", "خدمة الترجمة غير معروفة. اختر من /admin/subhub/translate/options.", 400);
  if (!api.supports_ar) throw new AdminError("target_unsupported", "هذه الخدمة لا تدعم العربية.", 400);
  return okResponse({ api: api.name, cues: parsed.cues, estimate: subhubEstimate(parsed.srt, api.price) });
}

function subhubApprovalFrom(input) {
  const a = input.approval && typeof input.approval === "object" ? input.approval : {};
  const by = cleanString(a.approved_by).slice(0, 60), at = cleanString(a.approved_at), max = Number(a.max_credits);
  if (!by || !at || !(max > 0)) throw new AdminError("approval_required", "موافقة الإدارة مطلوبة (approved_by و approved_at و max_credits). لم يُرسل شيء ولم تُحجز أي تكلفة.", 400);
  return { by, at, max };
}

/** Creates a spending envelope for a batch. Every translation inside it is debited here; nothing runs without it. */
async function handleSubhubBatch(request, env) {
  wnRate("subhub_batch", 10);
  const input = await readRequestData(request);
  const ids = Array.isArray(input.ids) ? input.ids.map(cleanString).filter(Boolean) : [];
  if (!ids.length || ids.length > 6000) throw new AdminError("invalid_batch", "الدفعة فارغة أو أكبر من 6000 عنوان.", 400);
  if (new Set(ids).size !== ids.length) throw new AdminError("duplicate_ids", "الدفعة تحتوي عناوين مكررة.", 400);
  const budget = Number(input.budget), estimate = Number(input.estimate);
  if (!(budget > 0) || !(estimate >= 0)) throw new AdminError("budget_required", "الميزانية المعتمدة مطلوبة.", 400);
  if (budget < estimate) throw new AdminError("budget_too_low", "الميزانية أقل من التكلفة المقدّرة.", 400);
  const by = cleanString(input.approved_by).slice(0, 60);
  if (!cleanString(input.approved_at) || !by) throw new AdminError("approval_required", "موافقة الإدارة على الدفعة مطلوبة (approved_by و approved_at).", 400);
  if (cleanString(input.target || "ar") !== "ar") throw new AdminError("invalid_target", "اللغة الهدف المدعومة: العربية فقط.", 400);
  const apis = await osTranslationCatalogue(env);
  const api = apis.find((a) => a.name === cleanString(input.api));
  if (!api || !api.supports_ar) throw new AdminError("invalid_api", "خدمة ترجمة غير صالحة أو لا تدعم العربية.", 400);
  const batchId = "b" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  await wnMutate(env, "subhub/batches/" + batchId + ".json", {}, () => ({ doc: { id: batchId, api: api.name, ids, budget, estimate, spent: 0, jobs: 0, approved_by: by, approved_at: cleanString(input.approved_at), created_at: new Date().toISOString() } }));
  return okResponse({ batch_id: batchId, api: api.name, budget, count: ids.length }, "اعتُمدت الدفعة. لن يُصرف أكثر من الميزانية.");
}

async function handleSubhubTranslate(request, env) {
  wnRate("subhub_translate", 60);
  const input = await readRequestData(request);
  const id = cleanString(input.content_id);
  if (!id) throw new AdminError("missing_id", "معرّف المحتوى مطلوب.", 400);
  if (cleanString(input.target || "ar") !== "ar") throw new AdminError("invalid_target", "اللغة الهدف المدعومة: العربية فقط.", 400);
  if (cleanString(input.provider || "opensubtitles_ai") !== "opensubtitles_ai") throw new AdminError("unknown_provider", "مزوّد الترجمة المدعوم: opensubtitles_ai.", 400);
  const parsed = await subhubSourceFrom(input);
  const list = await readMoviesList(env);
  if (!findById(list, id)) throw new AdminError("item_not_found", "العنوان غير موجود في المكتبة.", 404);
  const approvals = await subhubApprovals(env);
  if (approvals[id] && approvals[id].status === "approved" && cleanString(input.authorization) !== "OVERWRITE-APPROVED:" + id) {
    throw new AdminError("approved_subtitle_protected", "لهذا العنوان ترجمة معتمدة. لا تُترجم من جديد دون تفويض صريح.", 409);
  }
  const apis = await osTranslationCatalogue(env);
  const api = apis.find((a) => a.name === cleanString(input.api));
  if (!api) throw new AdminError("invalid_api", "خدمة الترجمة غير معروفة.", 400);
  if (!api.supports_ar) throw new AdminError("target_unsupported", "هذه الخدمة لا تدعم العربية.", 400);
  const est = subhubEstimate(parsed.srt, api.price);
  // spending authority: a batch envelope, or an explicit per-title approval that covers the maximum estimate
  const batchId = cleanString(input.batch_id);
  if (batchId) {
    if (!/^b[a-z0-9]{6,24}$/.test(batchId)) throw new AdminError("invalid_batch", "معرّف الدفعة غير صالح.", 400);
    await wnMutate(env, "subhub/batches/" + batchId + ".json", {}, (doc) => {
      if (!doc.id) throw new AdminError("batch_not_found", "الدفعة غير موجودة.", 404);
      if (doc.api !== api.name) throw new AdminError("api_mismatch", "الخدمة تختلف عن المعتمدة للدفعة.", 400);
      if (!doc.ids.includes(id)) throw new AdminError("not_in_batch", "العنوان ليس ضمن الدفعة المعتمدة.", 400);
      if (doc.spent + est.credits_max > doc.budget) throw new AdminError("batch_budget_exceeded", "الميزانية المعتمدة لا تكفي. لم يُرسل شيء.", 400, { budget: doc.budget, spent: doc.spent });
      doc.spent += est.credits_max; doc.jobs += 1;
      return { doc };
    });
  } else {
    const a = subhubApprovalFrom(input);
    if (a.max < est.credits_max) throw new AdminError("approval_too_low", "الحد المعتمد أقل من التكلفة القصوى المقدّرة (" + est.credits_max + ").", 400, { estimate: est });
  }
  const form = new FormData();
  form.set("api", api.name);
  form.set("translate_from", cleanString(input.source_lang) || "auto");
  form.set("translate_to", "ar");
  form.set("file", new Blob([parsed.srt], { type: "application/x-subrip" }), "source.srt");
  const res = await osFetch(env, "/ai/translate", { method: "POST", form, bearer: true, timeout: 30000 });
  const data = await res.json().catch(() => null);
  if (!res.ok || !data || !data.correlation_id) {
    throw new AdminError("provider_error", "رفض OpenSubtitles طلب الترجمة (" + res.status + (data && data.message ? ": " + cleanString(data.message).slice(0, 100) : "") + "). لم تُصرف تكلفة مؤكدة.", 502, { provider: "opensubtitles_ai" });
  }
  const cid = cleanString(data.correlation_id);
  if (!/^[A-Za-z0-9_-]{6,64}$/.test(cid)) throw new AdminError("provider_error", "معرّف مهمة غير متوقع.", 502);
  await wnMutate(env, "subhub/jobs/" + cid + ".json", {}, () => ({ doc: { correlation_id: cid, content_id: id, api: api.name, estimate: est, batch_id: batchId, source_srt: parsed.srt, created_at: new Date().toISOString(), state: "TRANSLATING" } }));
  return okResponse({ correlation_id: cid, state: "TRANSLATING", estimate: est, content_id: id }, "أُرسلت الترجمة. تابع الحالة.");
}

async function handleSubhubTranslateStatus(request, env, url) {
  wnRate("subhub_status", 600);
  const cid = cleanString(url.searchParams.get("correlation_id"));
  if (!/^[A-Za-z0-9_-]{6,64}$/.test(cid)) throw new AdminError("invalid_id", "معرّف المهمة غير صالح.", 400);
  const jobName = "subhub/jobs/" + cid + ".json";
  const job = await wnRead(env, jobName, {});
  if (!job || job.correlation_id !== cid) throw new AdminError("job_not_found", "المهمة غير معروفة (لم تُنشأ من هذا الخادم).", 404);
  if (job.state === "QUALITY_REVIEW" || job.state === "FAILED") return okResponse(subhubJobPublic(job));
  const res = await osFetch(env, "/ai/translate/" + encodeURIComponent(cid), { bearer: true });
  const d = await res.json().catch(() => null);
  if (!res.ok || !d) throw new AdminError("provider_error", "تعذر قراءة حالة الترجمة (" + res.status + ").", 502, { provider: "opensubtitles_ai" });
  const status = cleanString(d.status).toUpperCase();
  const fail = async (reason, extra) => {
    await wnMutate(env, jobName, {}, (doc) => { doc.state = "FAILED"; doc.reason = reason; doc.extra = extra || null; delete doc.source_srt; return { doc }; });
    return okResponse({ correlation_id: cid, content_id: job.content_id, state: "FAILED", reason, extra: extra || null });
  };
  if (status === "CREATED" || status === "PENDING") return okResponse({ correlation_id: cid, content_id: job.content_id, state: "TRANSLATING", provider_status: status });
  if (status === "ERROR" || status === "TIMEOUT") return fail("provider_" + status.toLowerCase());
  if (status !== "COMPLETED") return okResponse({ correlation_id: cid, content_id: job.content_id, state: "TRANSLATING", provider_status: status || "UNKNOWN" });
  const data = d.data || {};
  if (data.quality && data.quality.valid === false) return fail("provider_quality_failed", { refund_credits: numOf(data.quality_refund) });
  let link;
  try { link = new URL(cleanString(data.url)); } catch { return fail("bad_result_url"); }
  if (link.protocol !== "https:" || !/(^|\.)opensubtitles\.(com|org)$/i.test(link.hostname)) return fail("result_url_not_allowed");
  const f = await osFetchAbsolute(env, link);
  if (!f.ok) return fail("result_download_" + f.status);
  const dec = subs_decodeSubtitleBytes(new Uint8Array(await f.arrayBuffer()));
  const out = subsToSrt(dec.text);
  if (out.cues < 1) return fail("empty_result");
  if (subsArabicShare(out.srt) < 0.5) return fail("not_arabic");
  const kept = subhubKeepTiming(job.source_srt, out.srt);
  if (!kept.ok) return fail(kept.reason, { source: kept.source, translated: kept.translated });
  const report = { cues: kept.cues, timing_restored: kept.restored, arabic_share: Math.round(subsArabicShare(kept.srt) * 100) / 100, quality: data.quality || null, readability: data.readability || null, credits_left: numOf(data.credits_left), total_price: numOf(data.total_price), characters: numOf(data.characters_count) };
  await wnMutate(env, jobName, {}, (doc) => { doc.state = "QUALITY_REVIEW"; doc.report = report; doc.translated_srt = kept.srt; delete doc.source_srt; return { doc }; });
  await subhubNote(env, "opensubtitles_ai", true, "translation");
  return okResponse(subhubJobPublic(Object.assign({}, job, { state: "QUALITY_REVIEW", report, translated_srt: kept.srt })));
}

async function osFetchAbsolute(env, link) {
  const session = await osSession(env, false);
  const headers = { accept: "*/*", "api-key": cleanString(env.OPENSUBTITLES_API_KEY), "user-agent": OS_UA };
  if (session) headers.authorization = "Bearer " + session.token;
  await osThrottle(env);
  return subsFetch(link.toString(), { headers }, 20000);
}

function subhubJobPublic(job) {
  const out = { correlation_id: job.correlation_id, content_id: job.content_id, state: job.state, api: job.api, reason: job.reason || "", extra: job.extra || null, report: job.report || null };
  if (job.translated_srt) {
    out.srt = job.translated_srt.length > 700000 ? job.translated_srt.slice(0, 700000) : job.translated_srt;
    out.preview = job.translated_srt.split("\n\n").slice(0, 6).join("\n\n");
  }
  return out;
}

/** The admin approved (or revoked) the subtitle of a title: from then on no automatic route may overwrite it. */
async function handleSubhubApproval(request, env) {
  wnRate("subhub_approval", 240);
  const input = await readRequestData(request);
  const id = cleanString(input.content_id);
  const action = cleanString(input.action);
  if (!id || !["approve", "revoke"].includes(action)) throw new AdminError("invalid_request", "content_id و action (approve|revoke) مطلوبان.", 400);
  const list = await readMoviesList(env);
  if (!findById(list, id)) throw new AdminError("item_not_found", "العنوان غير موجود في المكتبة.", 404);
  const by = cleanString(input.approved_by).slice(0, 60);
  if (action === "approve" && !by) throw new AdminError("approval_required", "approved_by مطلوب.", 400);
  await wnMutate(env, SUBHUB_APPROVALS_FILE, { items: {} }, (doc) => {
    doc.items = doc.items && typeof doc.items === "object" ? doc.items : {};
    if (action === "approve") doc.items[id] = { status: "approved", url: cleanString(input.url).slice(0, 400), by, at: new Date().toISOString(), source: cleanString(input.source).slice(0, 40) };
    else delete doc.items[id];
    return { doc };
  });
  return okResponse({ content_id: id, status: action === "approve" ? "approved" : "revoked" });
}

async function handleSubhubApprovals(request, env) {
  return okResponse({ items: await subhubApprovals(env) });
}

/** Called by the automatic routes before anything is written: an existing subtitle is never replaced silently. */
async function subsGuardOverwrite(env, item, key, input) {
  if (!item) return;
  const id = cleanString(item.id);
  const approvals = await subhubApprovals(env);
  let exists = Boolean(cleanString(item.subtitle));
  if (!exists) { try { exists = Boolean(await env.MOVIES_BUCKET.head(key)); } catch { exists = false; } }
  if (!exists) return;
  if (approvals[id] && approvals[id].status === "approved") {
    if (cleanString(input.authorization) !== "OVERWRITE-APPROVED:" + id) {
      throw new AdminError("approved_subtitle_protected", "لهذا العنوان ترجمة معتمدة ولا تُستبدل دون تفويض صريح.", 409, { content_id: id });
    }
    return;
  }
  if (!truthy(input.overwrite)) throw new AdminError("subtitle_exists", "للعنوان ترجمة موجودة. أرسل overwrite=true لاستبدالها عمداً.", 409, { content_id: id });
}

function subhubLogged(name, handler) {
  return async (request, env, url, ctx) => {
    const started = Date.now();
    const rid = cleanString(request.headers.get("cf-ray")) || Math.random().toString(36).slice(2, 10);
    let status = 200;
    try {
      const res = await handler(request, env, url, ctx);
      status = res && res.status ? res.status : 200;
      return res;
    } finally {
      // no bodies, no tokens, no subtitle text: only the route, the status and the duration
      console.log(JSON.stringify({ evt: "subhub", route: name, status, ms: Date.now() - started, rid }));
    }
  };
}

const SUBHUB_ROUTES = (() => {
  const A = (name, h) => adminRoute(subhubLogged(name, h));
  return {
    "GET /admin/subhub/providers": A("providers", handleSubhubProviders),
    "POST /admin/subhub/providers/verify": A("verify", handleSubhubVerify),
    "GET /admin/subhub/translate/options": A("options", handleSubhubOptions),
    "POST /admin/subhub/translate/estimate": A("estimate", handleSubhubEstimate),
    "POST /admin/subhub/translate": A("translate", handleSubhubTranslate),
    "GET /admin/subhub/translate/status": A("translate_status", handleSubhubTranslateStatus),
    "POST /admin/subhub/translate/batch": A("batch", handleSubhubBatch),
    "POST /admin/subhub/approval": A("approval", handleSubhubApproval),
    "GET /admin/subhub/approvals": A("approvals", handleSubhubApprovals),
    "GET /admin/subhub/moviehash": A("moviehash", handleSubhubMovieHash),
  };
})();

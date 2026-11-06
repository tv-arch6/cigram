// =============================================================================
// WHAT'S NEW (v11) — smart content discovery + validated auto-import
// =============================================================================
// ADDITIVE: nothing above this section was changed. The router gained one dispatch line (WN_ROUTES),
// the health list gained the route names, and the every-minute cron gained one line (wnCronTick).
// Everything here reuses the Worker's own helpers (TMDB, duplicates, sections, subtitles, mutateMovies,
// undo journal, push) — there is NO second content database: imported items are written to movies.json
// with exactly the fields the classic add screens write, so the Cigram app reads them like any other item.
//
// ROUTES (all admin-only, ADMIN_TOKEN):
//   GET  /admin/discover/summary          dashboard card numbers (cached index only — never calls TMDB)
//   GET  /admin/discover/config           sync settings + which providers are configured
//   POST /admin/discover/config           save settings
//   POST /admin/discover/feed             discovery list (TMDB feeds + filters, or cached-index views)
//   GET  /admin/discover/genres           TMDB genre ids + Arabic names (for the genre filter)
//   POST /admin/discover/details          full TMDB details, seasons, library status, categories
//   POST /admin/discover/season           episodes of one season (TMDB) + which are already in the library
//   POST /admin/discover/video            find + validate video sources (honest 4-level result)
//   POST /admin/discover/subtitles        search subtitles (Arabic first, other languages allowed)
//   POST /admin/discover/subtitles/fetch  download + clean + validate one candidate (kept for the import)
//   POST /admin/discover/subtitles/validate  validate a subtitle the admin pasted / replaced by hand
//   POST /admin/discover/validate         pre-import validation report
//   POST /admin/discover/import           idempotent import (movie, or series header + chosen episodes)
//   POST /admin/discover/sync             manual synchronisation (feeds -> index) + first enrichment
//   POST /admin/discover/enrich           enrich the next N discovered items (video + subtitle availability)
//   GET  /admin/discover/history          synchronisation history
//
// STORAGE (admin-private R2 keys through adminKey()):
//   whatsnew/config.json   settings            whatsnew/index.json    discovery cache (capped)
//   whatsnew/history.json  sync runs (capped)  whatsnew/imports.json  import ledger (idempotency + report)
//   whatsnew/subs/<token>.srt  validated subtitle waiting for the import (removed after 24 h)
//
// HONESTY RULES (what the product promises):
//   * VidBolt serves an IFRAME PLAYER and publishes no title-lookup API. A URL built from a TMDB id is only
//     "generated". An HTTP 200 page is "reachable". Only a media file / HLS playlist that we actually fetched
//     and parsed is "playable". Embeds therefore never reach "playable" and need an explicit admin
//     acknowledgement before import; a provider that blocks automated probes (401/403/429) is "unverified".
//   * A subtitle is "downloaded" only after bytes were fetched, decoded, cleaned and validated.
//   * Validation results handed to the import are HMAC-signed (key derived from ADMIN_TOKEN), so the app
//     cannot claim a source is playable when the Worker did not see it.
//
// SECRETS (Worker secrets only — never in the app): TMDB_API_KEY or TMDB_READ_TOKEN (already used),
//   SUBDL_API_KEY / SUBSOURCE_API_KEY / OPENSUBTITLES_API_KEY (+ OPENSUBTITLES_USERNAME / _PASSWORD) (already used).
//   Cron: the existing every-minute Cron Trigger drives the optional automatic sync (see wnCronTick).

const WN_UA = "CigramAdmin-WhatsNew/1.0";
const WN_CONFIG_FILE = "whatsnew/config.json";
const WN_INDEX_FILE = "whatsnew/index.json";
const WN_HISTORY_FILE = "whatsnew/history.json";
const WN_LEDGER_FILE = "whatsnew/imports.json";
const WN_SUBS_PREFIX = "whatsnew/subs/";
const WN_TMDB_TIMEOUT_MS = 10000;
const WN_PROBE_TIMEOUT_MS = 8000;
const WN_MAX_SRT_BYTES = 1500000;
const WN_TOKEN_TTL_MS = 30 * 60 * 1000;
const WN_SUB_TMP_TTL_MS = 24 * 3600 * 1000;
const WN_INDEX_MAX = 1500;
const WN_HISTORY_MAX = 100;
const WN_LEDGER_MAX = 4000;
const WN_MAX_EPISODES_PER_CALL = 12;
const WN_MAX_PROBE_TARGETS = 8;
const WN_PAGE_SIZE = 20;
const WN_FEED_KEYS = [
  "movie:new_releases", "movie:upcoming", "movie:popular", "movie:trending",
  "tv:new_releases", "tv:recent_episodes", "tv:new_seasons", "tv:popular", "tv:trending",
  "all:new_releases", "all:trending",
];
const WN_VIEW_KEYS = ["recently_added", "ready", "unverified", "missing_video", "missing_subtitle", "imported"];
const WN_SORTS = { popularity: 1, rating: 1, release: 1 };

const WN_DEFAULT_CONFIG = {
  version: 1,
  auto_sync: false,
  interval_minutes: 360,
  language: "ar-SA",
  region: "",
  feeds: ["movie:new_releases", "movie:trending", "tv:recent_episodes", "tv:trending"],
  max_pages: 1,
  enrich_limit: 8,
  subtitle_languages: ["ar", "en"],
  subtitle_required: false,
  video_providers: [
    {
      id: "vidbolt",
      name: "VidBolt",
      kind: "embed",
      enabled: true,
      movie: "https://vidbolt.pro/movie/{tmdb}",
      tv: "https://vidbolt.pro/tv/{tmdb}/{season}/{episode}",
    },
  ],
  last_sync_at: "",
  last_success_at: "",
  last_started_at: "",
  lease_until: 0,
};

// ----------------------------------------------------------------------------- small utils

function wnToday(offsetDays) {
  return new Date(Date.now() + (offsetDays || 0) * 86400000).toISOString().slice(0, 10);
}

function wnPad2(n) {
  return String(Math.max(0, numOf(n))).padStart(2, "0");
}

function wnRandomHex(bytes) {
  const buf = new Uint8Array(bytes);
  crypto.getRandomValues(buf);
  return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("");
}

function wnB64u(text) {
  const bytes = new TextEncoder().encode(text);
  let bin = "";
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function wnUnB64u(text) {
  const b64 = String(text).replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

async function wnHmac(env, text) {
  const secret = cleanString(env.ADMIN_TOKEN);
  if (!secret) throw new AdminError("unauthorized", "رمز الإدارة غير مضبوط في الـ Worker.", 500);
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode("whatsnew-v1:" + secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text)));
  return Array.from(sig, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Signed, short-lived proof that THIS Worker validated a source. The app can pass it around but not forge it. */
async function wnSignToken(env, payload) {
  const body = wnB64u(JSON.stringify(payload));
  return body + "." + (await wnHmac(env, body));
}

async function wnVerifyToken(env, token) {
  const raw = cleanString(token);
  const dot = raw.indexOf(".");
  if (dot < 1) return null;
  const body = raw.slice(0, dot);
  const sig = raw.slice(dot + 1);
  if (!timingSafeEqualText(sig, await wnHmac(env, body))) return null;
  let payload;
  try {
    payload = JSON.parse(wnUnB64u(body));
  } catch {
    return null;
  }
  if (!payload || typeof payload !== "object") return null;
  if (!payload.x || Date.now() > payload.x) return { expired: true, payload };
  return { expired: false, payload };
}

const WN_RATE = new Map();
/** Per-isolate throttle for the expensive routes (a floor against runaway loops, not a security boundary). */
function wnRate(route, limit, windowMs) {
  const now = Date.now();
  const win = windowMs || 60000;
  const e = WN_RATE.get(route);
  if (!e || now - e.t > win) {
    WN_RATE.set(route, { t: now, n: 1 });
    return;
  }
  e.n++;
  if (e.n > limit) {
    throw new AdminError("rate_limited", "طلبات كثيرة جداً لهذه العملية. انتظر قليلاً ثم أعد المحاولة.", 429, {
      retry_after_ms: Math.max(1000, win - (now - e.t)),
    });
  }
}

/** Validates a URL we are about to fetch (SSRF guard). Returns a URL object or throws AdminError. */
function wnSafeUrl(raw, opts) {
  const o = opts || {};
  let u;
  try {
    u = new URL(cleanString(raw));
  } catch {
    throw new AdminError("invalid_url", "الرابط غير صالح.", 400);
  }
  if (u.protocol !== "https:" && !(u.protocol === "http:" && o.allowHttp)) {
    throw new AdminError("invalid_url", "يجب أن يبدأ الرابط بـ https://", 400);
  }
  if (u.username || u.password) throw new AdminError("invalid_url", "الروابط التي تحتوي بيانات دخول غير مسموحة.", 400);
  const host = u.hostname.toLowerCase();
  const bad =
    !host ||
    host === "localhost" ||
    host.startsWith("[") ||
    !host.includes(".") ||
    /\.(local|localhost|internal|lan|home|corp|intranet)$/.test(host) ||
    /^\d+$/.test(host) ||
    /^0x[0-9a-f]+$/i.test(host);
  if (bad) throw new AdminError("invalid_url", "عنوان الخادم غير مسموح.", 400);
  const ip = host.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (ip) {
    const [a, b] = [Number(ip[1]), Number(ip[2])];
    const priv =
      a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
    if (priv) throw new AdminError("invalid_url", "عناوين الشبكات الداخلية غير مسموحة.", 400);
  }
  if (u.port && !["80", "443", "8080", "8443"].includes(u.port)) {
    throw new AdminError("invalid_url", "المنفذ غير مسموح.", 400);
  }
  return u;
}

// ----------------------------------------------------------------------------- admin-private JSON files

function wnBucket(env) {
  return adminBucket(env);
}

async function wnRead(env, name, fallback) {
  const r = await r2ReadJson(wnBucket(env), adminKey(env, name), fallback, false);
  return r.doc;
}

async function wnMutate(env, name, fallback, fn) {
  return mutateJson(wnBucket(env), adminKey(env, name), fallback, async (doc) => {
    const out = await fn(doc);
    return { doc: out && out.doc !== undefined ? out.doc : doc, result: out ? out.result : undefined };
  }, 7, false);
}

function wnMergeConfig(raw) {
  const cfg = Object.assign({}, clone(WN_DEFAULT_CONFIG), raw && typeof raw === "object" ? raw : {});
  if (!Array.isArray(cfg.video_providers) || !cfg.video_providers.length) cfg.video_providers = clone(WN_DEFAULT_CONFIG.video_providers);
  if (!Array.isArray(cfg.feeds)) cfg.feeds = clone(WN_DEFAULT_CONFIG.feeds);
  cfg.feeds = cfg.feeds.filter((f) => WN_FEED_KEYS.includes(f));
  if (!Array.isArray(cfg.subtitle_languages) || !cfg.subtitle_languages.length) cfg.subtitle_languages = ["ar", "en"];
  cfg.interval_minutes = Math.min(10080, Math.max(30, numOf(cfg.interval_minutes) || 360));
  cfg.enrich_limit = Math.min(20, Math.max(0, Number.isFinite(Number(cfg.enrich_limit)) ? Number(cfg.enrich_limit) : 8));
  cfg.max_pages = Math.min(3, Math.max(1, numOf(cfg.max_pages) || 1));
  return cfg;
}

async function wnConfig(env) {
  return wnMergeConfig(await wnRead(env, WN_CONFIG_FILE, null));
}

function wnValidateProvider(p, index) {
  const id = cleanString(p && p.id).toLowerCase().replace(/[^a-z0-9_-]/g, "").slice(0, 24);
  if (!id) throw new AdminError("invalid_provider", "معرّف المزوّد مطلوب (أحرف إنجليزية/أرقام).", 400);
  const kind = ["embed", "hls", "file"].includes(lc(p.kind)) ? lc(p.kind) : "embed";
  const out = { id, name: cleanString(p.name).slice(0, 40) || id, kind, enabled: p.enabled !== false };
  for (const key of ["movie", "tv"]) {
    const tpl = cleanString(p[key]);
    if (!tpl) continue;
    if (!tpl.includes("{tmdb}")) throw new AdminError("invalid_provider", "قالب «" + key + "» للمزوّد " + id + " يجب أن يحتوي {tmdb}.", 400);
    if (key === "tv" && (!tpl.includes("{season}") || !tpl.includes("{episode}"))) {
      throw new AdminError("invalid_provider", "قالب المسلسلات للمزوّد " + id + " يجب أن يحتوي {season} و {episode}.", 400);
    }
    // validate the template by filling it with sample numbers
    wnSafeUrl(tpl.replace("{tmdb}", "1").replace("{season}", "1").replace("{episode}", "1"));
    out[key] = tpl;
  }
  if (!out.movie && !out.tv) throw new AdminError("invalid_provider", "المزوّد " + id + " بلا قوالب.", 400);
  return out;
}

// ----------------------------------------------------------------------------- TMDB (timeouts, retries, cache)

const WN_TMDB_CACHE = new Map();

async function wnTmdb(env, path, params, opts) {
  const o = opts || {};
  const ttl = o.ttl === undefined ? 600 : o.ttl;
  let auth;
  try {
    auth = tmdbCredentials(env);
  } catch (error) {
    throw new AdminError("missing_tmdb_key", "لم يتم إعداد TMDB_API_KEY أو TMDB_READ_TOKEN في إعدادات الـ Worker.", 503);
  }
  const url = new URL("https://api.themoviedb.org/3" + path);
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== null && v !== undefined && String(v) !== "") url.searchParams.set(k, String(v));
  }
  const cacheKey = url.pathname + "?" + Array.from(url.searchParams.entries()).sort().map(([k, v]) => k + "=" + v).join("&");
  const hit = WN_TMDB_CACHE.get(cacheKey);
  if (ttl > 0 && hit && hit.exp > Date.now()) return hit.data;
  const headers = { accept: "application/json", "user-agent": WN_UA };
  if (auth.mode === "api_key") url.searchParams.set("api_key", auth.value);
  else headers.authorization = "Bearer " + auth.value;

  let lastMessage = "";
  for (let attempt = 0; attempt < 3; attempt++) {
    let res;
    try {
      res = await fetchWithTimeout(url.toString(), { headers }, WN_TMDB_TIMEOUT_MS);
    } catch (error) {
      lastMessage = error && error.name === "AbortError" ? "انتهت مهلة الاتصال" : cleanString(error && error.message).slice(0, 80);
      await sleep(250 * (attempt + 1));
      continue;
    }
    if (res.status === 429) {
      const wait = Math.min(2500, (Number(res.headers.get("retry-after")) || 1) * 1000);
      try { await res.body?.cancel(); } catch { /* ignore */ }
      lastMessage = "تجاوز حد الطلبات (429)";
      await sleep(wait);
      continue;
    }
    if (res.status >= 500) {
      try { await res.body?.cancel(); } catch { /* ignore */ }
      lastMessage = "خطأ من TMDB (HTTP " + res.status + ")";
      await sleep(300 * (attempt + 1));
      continue;
    }
    if (res.status === 404) {
      try { await res.body?.cancel(); } catch { /* ignore */ }
      if (o.nullOn404) return null;
      throw new AdminError("tmdb_not_found", "العنصر غير موجود في TMDB.", 404);
    }
    if (res.status === 401 || res.status === 403) {
      try { await res.body?.cancel(); } catch { /* ignore */ }
      throw new AdminError("bad_tmdb_key", "مفتاح TMDB غير صحيح أو غير مخوّل للوصول إلى API.", 502);
    }
    let data = null;
    try {
      data = await res.json();
    } catch {
      data = null;
    }
    if (!res.ok || !data || typeof data !== "object") {
      throw new AdminError("tmdb_error", "فشل طلب TMDB (HTTP " + res.status + ").", 502);
    }
    if (ttl > 0) {
      if (WN_TMDB_CACHE.size > 400) WN_TMDB_CACHE.delete(WN_TMDB_CACHE.keys().next().value);
      WN_TMDB_CACHE.set(cacheKey, { exp: Date.now() + ttl * 1000, data });
    }
    return data;
  }
  throw new AdminError("tmdb_unavailable", "تعذر الوصول إلى TMDB بعد عدة محاولات (" + (lastMessage || "غير معروف") + "). أعد المحاولة بعد قليل.", 502);
}

async function wnGenres(env, tmdbType) {
  const data = await wnTmdb(env, "/genre/" + (tmdbType === "tv" ? "tv" : "movie") + "/list", { language: "ar" }, { ttl: 86400 });
  const map = new Map();
  for (const g of Array.isArray(data.genres) ? data.genres : []) map.set(Number(g.id), cleanString(g.name));
  return map;
}

// ----------------------------------------------------------------------------- library helpers

function wnLibraryIndex(list) {
  const byKey = new Map();
  for (const item of list) {
    if (!item || typeof item !== "object" || isEpisodeItem(item)) continue;
    const kind = itemKind(item) === "series" ? "series" : "movie";
    const id = tmdbIdOf(item);
    if (!id) continue;
    const key = kind + ":" + id;
    if (!byKey.has(key)) byKey.set(key, briefOf(item));
  }
  return byKey;
}

function wnLibraryEpisodes(list, header) {
  const seasons = new Map();
  if (!header) return seasons;
  for (const e of episodesOf(list, header)) {
    const s = numOf(e.season);
    if (!seasons.has(s)) seasons.set(s, new Set());
    seasons.get(s).add(numOf(e.episode));
  }
  return seasons;
}

function wnExistingCategories(list) {
  const counts = new Map();
  for (const item of list) {
    if (!item || typeof item !== "object" || isEpisodeItem(item)) continue;
    for (const part of cleanString(item.category).split(/[،,]/)) {
      const name = part.trim();
      if (name) counts.set(name, (counts.get(name) || 0) + 1);
    }
  }
  return Array.from(counts.entries()).sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : 1)).slice(0, 80).map(([name, count]) => ({ name, count }));
}

// ----------------------------------------------------------------------------- metadata (TMDB details, ar + en)

function wnKind(value) {
  const t = lc(value);
  return t === "tv" || t === "series" || t === "episode" ? "series" : "movie";
}

function wnTmdbId(value) {
  const m = cleanString(value).match(/^\d{1,9}$/);
  if (!m) throw new AdminError("invalid_tmdb", "معرّف TMDB غير صالح.", 400);
  return m[0];
}

/** Real TMDB metadata for one title (Arabic first, English fallback). Cached by wnTmdb. */
async function wnFetchMeta(env, kind, tmdbId) {
  const tv = kind === "series";
  const path = (tv ? "/tv/" : "/movie/") + tmdbId;
  const [ar, en] = await Promise.all([
    wnTmdb(env, path, { language: "ar-SA", append_to_response: "external_ids" }, { ttl: 1800 }),
    wnTmdb(env, path, { language: "en-US", append_to_response: "external_ids" }, { ttl: 1800 }),
  ]);
  const genres = genreNameList(ar, en);
  const date = pickText(ar, en, tv ? "first_air_date" : "release_date");
  const runtime = tv
    ? Number((Array.isArray(ar.episode_run_time) ? ar.episode_run_time[0] : 0) || (Array.isArray(en.episode_run_time) ? en.episode_run_time[0] : 0)) || 0
    : pickNumber(ar, en, "runtime");
  const imdbRaw = cleanString((ar.external_ids && ar.external_ids.imdb_id) || ar.imdb_id || (en.external_ids && en.external_ids.imdb_id) || en.imdb_id);
  const rating = Number(Number(pickNumber(ar, en, "vote_average") || 0).toFixed(1));
  const meta = {
    kind,
    tmdb_id: Number(tmdbId),
    title: pickText(ar, en, tv ? "name" : "title"),
    original_title: pickText(en, ar, tv ? "original_name" : "original_title"),
    description: pickText(ar, en, "overview"),
    tagline: pickText(ar, en, "tagline"),
    year: yearOfText(date),
    release_date: date,
    released: Boolean(date) && date <= wnToday(),
    imdb_id: extractImdbId(imdbRaw),
    rating,
    rating_text: rating > 0 ? String(rating) : "",
    vote_count: Number(ar.vote_count || en.vote_count) || 0,
    popularity: Math.round(Number(ar.popularity || en.popularity) || 0),
    genres: genres.map((g) => g.name),
    genre_ids: genres.map((g) => g.id),
    category: genres.map((g) => g.name).join("، "),
    poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"),
    backdrop: tmdbImage(pickText(ar, en, "backdrop_path"), "w1280"),
    duration: durationText(runtime),
    runtime_minutes: runtime,
    original_language: cleanString(ar.original_language || en.original_language),
    countries: tv
      ? (Array.isArray(ar.origin_country) ? ar.origin_country : Array.isArray(en.origin_country) ? en.origin_country : [])
      : (Array.isArray(ar.production_countries) ? ar.production_countries : Array.isArray(en.production_countries) ? en.production_countries : []).map((c) => cleanString(c && c.iso_3166_1)).filter(Boolean),
    status: cleanString(ar.status || en.status),
    homepage: cleanString(ar.homepage || en.homepage),
  };
  if (tv) {
    const rawSeasons = Array.isArray(ar.seasons) && ar.seasons.length ? ar.seasons : Array.isArray(en.seasons) ? en.seasons : [];
    meta.season_count = Number(ar.number_of_seasons || en.number_of_seasons) || 0;
    meta.episode_count = Number(ar.number_of_episodes || en.number_of_episodes) || 0;
    meta.seasons = rawSeasons
      .filter((s) => Number(s.season_number) > 0)
      .map((s) => ({
        season: Number(s.season_number),
        name: cleanString(s.name),
        episode_count: Number(s.episode_count) || 0,
        poster: tmdbImage(s.poster_path, "w500"),
        air_date: cleanString(s.air_date),
        aired: Boolean(cleanString(s.air_date)) && cleanString(s.air_date) <= wnToday(),
      }));
    const last = ar.last_episode_to_air || en.last_episode_to_air || null;
    const next = ar.next_episode_to_air || en.next_episode_to_air || null;
    meta.latest_episode = last
      ? { season: Number(last.season_number) || 0, episode: Number(last.episode_number) || 0, air_date: cleanString(last.air_date), name: cleanString(last.name) }
      : null;
    meta.next_episode = next
      ? { season: Number(next.season_number) || 0, episode: Number(next.episode_number) || 0, air_date: cleanString(next.air_date), name: cleanString(next.name) }
      : null;
    const lastAge = meta.latest_episode && meta.latest_episode.air_date ? (Date.now() - Date.parse(meta.latest_episode.air_date)) / 86400000 : 9999;
    meta.new_season = Boolean(meta.latest_episode && meta.latest_episode.episode === 1 && lastAge <= 45);
  }
  return meta;
}

/** Episodes of one season from TMDB (Arabic names where TMDB has them). */
async function wnSeasonEpisodes(env, tmdbId, season) {
  const path = "/tv/" + tmdbId + "/season/" + season;
  const [ar, en] = await Promise.all([
    wnTmdb(env, path, { language: "ar-SA" }, { ttl: 900, nullOn404: true }),
    wnTmdb(env, path, { language: "en-US" }, { ttl: 900, nullOn404: true }),
  ]);
  if (!ar && !en) throw new AdminError("season_not_found", "الموسم " + season + " غير موجود في TMDB.", 404);
  const arEps = Array.isArray(ar && ar.episodes) ? ar.episodes : [];
  const enEps = Array.isArray(en && en.episodes) ? en.episodes : [];
  const numbers = new Set();
  for (const e of arEps.concat(enEps)) {
    const n = Number(e && e.episode_number);
    if (Number.isInteger(n) && n > 0) numbers.add(n);
  }
  const today = wnToday();
  const episodes = Array.from(numbers).sort((a, b) => a - b).map((n) => {
    const a = arEps.find((e) => Number(e.episode_number) === n) || {};
    const b = enEps.find((e) => Number(e.episode_number) === n) || {};
    const air = pickText(a, b, "air_date");
    const runtime = pickNumber(a, b, "runtime");
    return {
      episode: n,
      name: pickText(a, b, "name"),
      overview: pickText(a, b, "overview"),
      air_date: air,
      aired: Boolean(air) && air <= today,
      runtime_minutes: runtime,
      duration: durationText(runtime),
      still: tmdbImage(pickText(a, b, "still_path"), "w780"),
    };
  });
  return {
    season: Number(season),
    name: pickText(ar, en, "name"),
    poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"),
    air_date: pickText(ar, en, "air_date"),
    episodes,
  };
}

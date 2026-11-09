// v10 — UPDATES: publishing a Version Code lower than the live one is allowed (VERSION_TOO_OLD removed); only the exact same code is refused.
// CIGRAM ADMIN API — FULL WORKER  (v2 content CMS + v3 CMS + v4 UPDATE CENTER)
// v8 — REQUESTS: viewers' requests (cigram-user-api KV) in the admin app + Telegram alerts (see "REQUESTS (v8)").
//      Binding REQUESTS_KV (= KV namespace CIGRAM_KV), secret TELEGRAM_BOT_TOKEN, optional var TELEGRAM_CHAT_ID.
// v9 — PUSH: phone notifications that arrive while the app is closed (see "PUSH (v9)"):
//      automatic push when a film / series / episodes are added, /admin/notify + update pushes reach
//      every Cigram phone, HIGH priority. Needs the secret FCM_SERVICE_ACCOUNT. Nothing else changed.
// Cloudflare Worker (Module syntax)
// COMPATIBILITY TARGET: admin-gradle.zip (Cigram Admin Android / Java build).
// Includes the /admin/*, /update/*, /movies and legacy import routes used by the supplied app.
// VidBolt: builds vidbolt.pro iframe URLs from the movie/series TMDB id; it does not extract
// or download an MP4/HLS source, and an iframe HTTP response alone cannot prove playback.
// Season commit can auto-create missing VidBolt episode embeds when the series has a TMDB id.

//
// v2 keeps EVERY original route and adds: sections registry & home order, featured,
// edit/bulk/delete with undo + automatic backups, season merge, slug checks,
// subtitle auto-linking, link/subtitle checks, maintenance mode, announcements,
// admin notes and a channel overlay. See the README bundled with the project.
// Optional bindings: ADMIN_BUCKET (private R2 bucket for notes/backups/undo),
// secret ADMIN_PATH_SALT (obscures the fallback private prefix).
// Required binding: MOVIES_BUCKET  -> your R2 bucket that contains movies.json
// Required secret : ADMIN_TOKEN    -> must match the token used by the Cigram Admin app
// Required TMDB   : TMDB_API_KEY (v3) OR TMDB_READ_TOKEN
// Optional secret : PUSH_WEBHOOK_URL   (push relay used by the update center)
// Optional secret : FCM_SERVICE_ACCOUNT (Firebase service-account JSON; sends pushes directly via FCM v1)
// Optional var    : FCM_TOPIC (default "cigram_updates"; the user app must subscribe to it)
// v4 — UPDATE CENTER: full app-update management (see the "UPDATE CENTER (v4)" section
//      and update-system/README.md). Scheduling needs a Cron Trigger (every minute).

const MOVIES_OBJECT = "movies.json";
const CAROUSEL_OBJECT = "cigram_carousel.json";
const UPDATE_OBJECT = "cigram_update.json";
const CAROUSEL_MAX = null; // unlimited
const R2_PUBLIC_BASE = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
const MAX_CAROUSEL_IMAGE_BYTES = 12 * 1024 * 1024;

function normalizeVidBoltTitle(value) {
  return cleanString(value)
    .toLowerCase()
    .normalize("NFKC")
    .replace(/[\u064B-\u065F\u0670]/g, "")
    .replace(/[أإآ]/g, "ا")
    .replace(/ى/g, "ي")
    .replace(/[ًٌٍَُِّْـ]/g, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function vidBoltTmdbId(value) {
  const raw = cleanString(value);
  if (!raw) return "";
  const direct = raw.match(/^\s*(\d+)\s*$/);
  if (direct) return direct[1];
  const urlId = raw.match(/themoviedb\.org\/(?:movie|tv)\/(\d+)/i);
  return urlId ? urlId[1] : "";
}

function isVidBoltUrl(value) {
  return /^https?:\/\/(?:www\.)?vidbolt\.(?:pro|xyz)\//i.test(cleanString(value));
}

/**
 * Builds the VidBolt iframe URL used by this Admin app.
 * This is an EMBED/player URL, not a direct MP4/HLS file URL.
 * A 200 response from the iframe host cannot prove that the selected title has playable media.
 */
async function autoGenerateVidBolt(item, env, seriesHeader = null) {
  if (!item || typeof item !== "object") return { generated: false, reason: "invalid_item" };

  const isEpisode = isEpisodeItem(item);
  const isSeries = isSeriesHeader(item);
  const kind = isEpisode ? "episode" : (isSeries ? "series" : "movie");
  const currentVideo = cleanString(item.video);
  const currentLink = cleanString(item.link);
  const oldHost = /vidbolt\.xyz/i;

  // Preserve user-supplied non-VidBolt links. Legacy VidBolt .xyz links are regenerated as .pro.
  if ((currentVideo && !isVidBoltUrl(currentVideo)) ||
      (currentLink && !isVidBoltUrl(currentLink))) {
    return { generated: false, reason: "custom_video_url_exists" };
  }

  if ((currentVideo && isVidBoltUrl(currentVideo) && !oldHost.test(currentVideo)) ||
      (currentLink && isVidBoltUrl(currentLink) && !oldHost.test(currentLink))) {
    const existing = (currentVideo || currentLink).replace(/vidbolt\.xyz/ig, "vidbolt.pro");
    if (!currentVideo) item.video = existing;
    if (!currentLink) item.link = existing;
    return { generated: false, preserved: true, url: existing, reason: "vidbolt_url_already_exists" };
  }

  const source = isEpisode && seriesHeader ? seriesHeader : item;
  const rawTmdb = item.tmdb || item.tmdb_id ||
    (seriesHeader && (seriesHeader.tmdb || seriesHeader.tmdb_id));
  let tmdbId = vidBoltTmdbId(rawTmdb);

  // Resolve a title only when no TMDB id is stored. Accept exact normalized title matches only.
  if (!tmdbId) {
    const title = cleanString(source.title || source.name || item.title);
    if (!title || !env || (!cleanString(env.TMDB_API_KEY) && !cleanString(env.TMDB_READ_TOKEN))) {
      return { generated: false, reason: "missing_tmdb_id_or_tmdb_credentials" };
    }

    try {
      const mediaType = kind === "episode" || kind === "series" ? "tv" : "movie";
      const search = await tmdbGet(env, "/search/" + mediaType, {
        query: title,
        language: "ar-SA",
        include_adult: "false",
        page: 1
      });
      const rows = Array.isArray(search.results) ? search.results : [];
      const wanted = normalizeVidBoltTitle(title);
      const exact = rows.find((row) => {
        const candidates = mediaType === "movie"
          ? [row.title, row.original_title]
          : [row.name, row.original_name];
        return candidates.some((candidate) =>
          normalizeVidBoltTitle(candidate) === wanted
        );
      });

      if (!exact || !Number.isInteger(Number(exact.id)) || Number(exact.id) <= 0) {
        return { generated: false, reason: "exact_tmdb_title_match_not_found" };
      }

      tmdbId = String(Number(exact.id));
      source.tmdb = tmdbId;
      item.tmdb = tmdbId;
      item.tmdb_id = tmdbId;
    } catch (error) {
      return {
        generated: false,
        reason: "tmdb_lookup_failed",
        message: cleanString(error && error.message)
      };
    }
  }

  let url = "";
  if (kind === "movie") {
    url = "https://vidbolt.pro/movie/" + tmdbId;
  } else if (kind === "episode") {
    const season = cleanString(item.season);
    const episode = cleanString(item.episode);
    if (!/^\d+$/.test(season) || !/^\d+$/.test(episode)) {
      return { generated: false, reason: "missing_season_or_episode" };
    }
    url = "https://vidbolt.pro/tv/" + tmdbId + "/" + season + "/" + episode;
  } else {
    return { generated: false, reason: "series_header_has_no_episode_player" };
  }

  item.video = url;
  item.link = url;
  item.tmdb = tmdbId;
  return {
    generated: true,
    url,
    tmdb_id: tmdbId,
    check: "embed_url_created_not_playback_verified"
  };
}

function corsHeaders(extra = {}) {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "Content-Type, Authorization, X-Admin-Token",
    "cache-control": "no-store",
    ...extra,
  };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: corsHeaders({
      "content-type": "application/json; charset=utf-8",
    }),
  });
}

function authorized(request, env) {
  const bearer = (request.headers.get("authorization") || "")
    .replace(/^Bearer\s+/i, "")
    .trim();
  const headerToken = (request.headers.get("x-admin-token") || "").trim();
  const token = bearer || headerToken;
  return Boolean(env.ADMIN_TOKEN) && token === env.ADMIN_TOKEN;
}

function cleanString(value) {
  if (value === null || value === undefined) return "";
  return String(value).trim();
}

/** @returns {Promise<any>} */
async function readRequestData(request) {
  const type = (request.headers.get("content-type") || "").toLowerCase();

  if (type.includes("application/json")) {
    try {
      const body = await request.json();
      return body && typeof body === "object" ? body : {};
    } catch {
      return {};
    }
  }

  if (
    type.includes("multipart/form-data") ||
    type.includes("application/x-www-form-urlencoded")
  ) {
    try {
      const form = await request.formData();
      const out = {};
      for (const [key, value] of form.entries()) {
        if (typeof value === "string") out[key] = value;
      }
      return out;
    } catch {
      // Continue to text fallback below.
    }
  }

  let raw = "";
  try {
    raw = await request.text();
  } catch {
    return {};
  }

  if (!raw.trim()) return {};

  try {
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object") return parsed;
  } catch {
    // Not JSON.
  }

  try {
    return Object.fromEntries(new URLSearchParams(raw));
  } catch {
    return {};
  }
}

/** @returns {Promise<any>} */
async function readJsonFile(env, key, fallback) {
  try {
    const obj = await env.MOVIES_BUCKET.get(key);
    if (!obj) return fallback;
    return JSON.parse(await obj.text());
  } catch {
    return fallback;
  }
}

async function writeJsonFile(env, key, data) {
  await env.MOVIES_BUCKET.put(key, JSON.stringify(data, null, 2), {
    httpMetadata: {
      contentType: "application/json; charset=utf-8",
      cacheControl: "no-store",
    },
  });
}

async function returnR2JsonObject(env, key, missingValue = null) {
  const obj = await env.MOVIES_BUCKET.get(key);
  if (!obj) {
    if (missingValue !== null) return json(missingValue);
    return json({ ok: false, error: "not_found", object: key }, 404);
  }

  return new Response(obj.body, {
    status: 200,
    headers: corsHeaders({
      "content-type": "application/json; charset=utf-8",
      etag: obj.httpEtag || "",
    }),
  });
}

// -----------------------------------------------------------------------------
// MOVIES
// -----------------------------------------------------------------------------

function getWritableMoviesArray(document) {
  if (Array.isArray(document)) {
    return { root: document, list: document };
  }

  const root = document && typeof document === "object" ? document : {};

  if (Array.isArray(root.movies)) return { root, list: root.movies };
  if (Array.isArray(root.items)) return { root, list: root.items };
  if (Array.isArray(root.data)) return { root, list: root.data };

  root.movies = [];
  return { root, list: root.movies };
}

function makeContentId(input, list) {
  const supplied = cleanString(input.id);
  if (supplied) return supplied;

  // Timestamp + short random suffix keeps IDs unique without a database.
  const suffix = Math.random().toString(36).slice(2, 7);
  let candidate = `${Date.now()}_${suffix}`;

  const used = new Set(
    list
      .filter((x) => x && typeof x === "object")
      .map((x) => cleanString(x.id))
      .filter(Boolean)
  );

  while (used.has(candidate)) {
    candidate = `${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  }
  return candidate;
}

async function addMovie(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  try {
    const input = await readRequestData(request);

    const typeRaw = cleanString(input.type).toLowerCase();
    const type = typeRaw === "series" || typeRaw === "episode" ? typeRaw : "movie";

    const acceptedKeys = [
      "section_id",
      "section_name",
      "series_id",
      "season",
      "episode",
      "title",
      "poster",
      "series_poster",
      "season_poster",
      "backdrop",
      "video",
      "link",
      "year",
      "category",
      "imdb",
      "quality",
      "description",
      "subtitle",
      "duration",
      "skip_segments",
      "series_episodes_json",
      "episodes",
      // v2 additions (all optional, old clients never send them)
      "slug",
      "tmdb",
      "imdb_id",
      "original_title",
      "featured",
      "featured_order",
      "hidden",
    ];

    /** @type {any} */
    let created = null;
    const res = await mutateMovies(
      env,
      async (list, holder, ctx) => {
        const item = {};
        item.id = makeContentId(input, list);
        if (cleanString(input.id) && findById(list, item.id)) {
          throw new AdminError("id_in_use", "المعرّف «" + item.id + "» مستخدم بالفعل.", 409);
        }
        item.type = type;
        // Permanent insertion timestamp used by Cigram to keep newest content first.
        item.created_at = new Date().toISOString();
        item.created_at_ms = Date.now();

        for (const key of acceptedKeys) {
          if (!Object.prototype.hasOwnProperty.call(input, key)) continue;
          if (key === "featured" || key === "hidden") item[key] = truthy(input[key]);
          else if (key === "featured_order") item[key] = Number(input[key]) || 0;
          else item[key] = cleanString(input[key]);
        }

        if (item.slug) slugConflict(list, item.slug, "");
        await autoGenerateVidBolt(item, env);

        // Keep newest content at index 0. In Cigram RTL horizontal lists, index 0
        // is rendered at the far right, so newly added movies/series appear first.
        list.unshift(item);
        ctx.tracker.add(item);
        // Legacy entries without a timestamp keep their existing relative order.
        sortNewestFirst(list);
        created = item;
        return { result: item, label: "إضافة " + kindLabel(itemKind(item)) + ": " + cleanString(item.title) };
      },
      { op: "add" }
    );

    return json({
      ok: true,
      success: true,
      message: "content_added",
      id: created.id,
      item: created,
      count: getWritableMoviesArray(res.doc).list.length,
      undo_id: res.undo_id,
    });
  } catch (error) {
    return legacyErrorResponse(error);
  }
}

// -----------------------------------------------------------------------------
// FULL SERIES IMPORT
// -----------------------------------------------------------------------------

class SeriesImportError extends Error {
  constructor(error, message, status = 400) {
    super(message);
    this.name = "SeriesImportError";
    this.error = error;
    this.status = status;
  }
}

function seriesError(error, message, status = 400) {
  return json({ ok: false, success: false, error, error_code: errorCodeOf(error), message }, status);
}

function seriesErrorResponse(error) {
  if (error instanceof SeriesImportError) {
    return seriesError(error.error, error.message, error.status);
  }

  return seriesError(
    "tmdb_error",
    "حدث خطأ غير متوقع أثناء فحص بيانات المسلسل: " +
      (error && error.message ? error.message : String(error)),
    502
  );
}

function validateSeriesImportInput(input) {
  const source = cleanString(
    input && (input.tmdb_url || input.url || input.imdb || input.tmdb)
  );
  const slug = cleanString(input && input.slug);

  if (!source) {
    throw new SeriesImportError(
      "missing_url",
      "أدخل رابط أو معرّف IMDb / TMDB للمسلسل.",
      400
    );
  }

  if (!slug) {
    throw new SeriesImportError(
      "missing_slug",
      "أدخل اسم مجلد المسلسل على R2.",
      400
    );
  }

  if (!/^[A-Za-z0-9._-]+$/.test(slug)) {
    throw new SeriesImportError(
      "invalid_slug",
      "اسم مجلد R2 غير صالح. استخدم أحرفاً إنجليزية وأرقاماً و . _ - فقط.",
      400
    );
  }

  return { source, slug };
}

function tmdbCredentials(env) {
  const apiKey = cleanString(env.TMDB_API_KEY);
  const readToken = cleanString(env.TMDB_READ_TOKEN).replace(/^Bearer\s+/i, "");

  if (apiKey) return { mode: "api_key", value: apiKey };
  if (readToken) return { mode: "bearer", value: readToken };

  throw new SeriesImportError(
    "missing_tmdb_key",
    "لم يتم إعداد TMDB_API_KEY أو TMDB_READ_TOKEN في إعدادات الـ Worker.",
    500
  );
}

/** @returns {Promise<any>} */
async function tmdbGet(env, path, params = {}, notFoundAsSeries = false) {
  const auth = tmdbCredentials(env);
  const url = new URL("https://api.themoviedb.org/3" + path);

  for (const [key, value] of Object.entries(params || {})) {
    if (value !== null && value !== undefined && String(value) !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  const headers = { accept: "application/json" };
  if (auth.mode === "api_key") {
    url.searchParams.set("api_key", auth.value);
  } else {
    headers.authorization = "Bearer " + auth.value;
  }

  let response;
  try {
    response = await fetch(url.toString(), { headers });
  } catch (error) {
    throw new SeriesImportError(
      "tmdb_error",
      "تعذر الاتصال بخدمة TMDB: " +
        (error && error.message ? error.message : String(error)),
      502
    );
  }

  let data = {};
  try {
    data = await response.json();
  } catch {
    data = {};
  }

  if (response.status === 401 || response.status === 403) {
    throw new SeriesImportError(
      "bad_tmdb_key",
      "مفتاح TMDB غير صحيح أو غير مخوّل للوصول إلى API.",
      500
    );
  }

  if (response.status === 404 && notFoundAsSeries) {
    throw new SeriesImportError(
      "series_not_found",
      "لم يتم العثور على المسلسل في TMDB.",
      404
    );
  }

  if (!response.ok) {
    const tmdbMessage = cleanString(data && data.status_message);
    throw new SeriesImportError(
      "tmdb_error",
      "فشل طلب TMDB (HTTP " + response.status + ")" +
        (tmdbMessage ? ": " + tmdbMessage : "."),
      502
    );
  }

  return data && typeof data === "object" ? data : {};
}

function extractImdbId(value) {
  const match = cleanString(value).match(/\b(tt\d{5,12})\b/i);
  return match ? match[1].toLowerCase() : "";
}

async function resolveTmdbSeriesId(source, env) {
  const raw = cleanString(source);

  if (/^\d+$/.test(raw)) {
    const directId = Number(raw);
    if (Number.isInteger(directId) && directId > 0) return directId;
  }

  const tmdbMatch = raw.match(
    /(?:https?:\/\/)?(?:www\.)?themoviedb\.org\/tv\/(\d+)/i
  );
  if (tmdbMatch) {
    const tmdbId = Number(tmdbMatch[1]);
    if (Number.isInteger(tmdbId) && tmdbId > 0) return tmdbId;
  }

  const imdbId = extractImdbId(raw);
  if (imdbId) {
    const found = await tmdbGet(
      env,
      "/find/" + encodeURIComponent(imdbId),
      { external_source: "imdb_id" }
    );

    const tvResults = Array.isArray(found.tv_results) ? found.tv_results : [];
    if (tvResults.length > 0) {
      const id = Number(tvResults[0] && tvResults[0].id);
      if (Number.isInteger(id) && id > 0) return id;
    }

    const episodeResults = Array.isArray(found.tv_episode_results)
      ? found.tv_episode_results
      : [];

    for (const episode of episodeResults) {
      const showId = Number(episode && episode.show_id);
      if (Number.isInteger(showId) && showId > 0) return showId;
    }

    throw new SeriesImportError(
      "series_not_found",
      "لم يتم العثور على مسلسل مرتبط بمعرّف IMDb هذا في TMDB.",
      404
    );
  }

  throw new SeriesImportError(
    "invalid_url",
    "الرابط أو المعرّف غير صالح. استخدم رابط TMDB لمسلسل، أو رابط/معرّف IMDb يبدأ بـ tt، أو رقم TMDB فقط.",
    400
  );
}

function pickText(primary, fallback, key) {
  const a = cleanString(primary && primary[key]);
  if (a) return a;
  return cleanString(fallback && fallback[key]);
}

function pickNumber(primary, fallback, key) {
  const a = Number(primary && primary[key]);
  if (Number.isFinite(a) && a > 0) return a;
  const b = Number(fallback && fallback[key]);
  return Number.isFinite(b) && b > 0 ? b : 0;
}

function yearFromDate(value) {
  const match = cleanString(value).match(/^(\d{4})/);
  return match ? Number(match[1]) : 0;
}

function tmdbImage(path, size = "w500") {
  const value = cleanString(path);
  if (!value) return "";
  if (/^https?:\/\//i.test(value)) return value;
  return "https://image.tmdb.org/t/p/" + size + (value.startsWith("/") ? value : "/" + value);
}

function durationText(minutes) {
  const total = Math.max(0, Number(minutes) || 0);
  if (!total) return "";
  const hours = Math.floor(total / 60);
  const mins = Math.floor(total % 60);
  return String(hours).padStart(2, "0") + ":" + String(mins).padStart(2, "0");
}

function genresText(arDetails, enDetails) {
  const arGenres = Array.isArray(arDetails && arDetails.genres)
    ? arDetails.genres
    : [];
  const enGenres = Array.isArray(enDetails && enDetails.genres)
    ? enDetails.genres
    : [];

  const enById = new Map(
    enGenres.map((genre) => [Number(genre && genre.id), cleanString(genre && genre.name)])
  );

  const seen = new Set();
  const names = [];

  for (const genre of arGenres) {
    const id = Number(genre && genre.id);
    const name = cleanString(genre && genre.name) || enById.get(id) || "";
    if (name && !seen.has(name)) {
      names.push(name);
      seen.add(name);
    }
  }

  for (const genre of enGenres) {
    const name = cleanString(genre && genre.name);
    if (name && !seen.has(name)) {
      names.push(name);
      seen.add(name);
    }
  }

  return names.join("، ");
}

function parseEpisodeKey(key, slug) {
  let relative = cleanString(key);
  const prefix = slug + "/";
  if (relative.startsWith(prefix)) relative = relative.slice(prefix.length);

  const patterns = [
    /(?:^|[\/_.\-\s])s(?:eason)?[\s._-]*0*(\d{1,3})[\s._-]*e(?:pisode)?[\s._-]*0*(\d{1,4})(?=$|[\/_.\-\s])/i,
    /(?:^|[\/_.\-\s])0*(\d{1,3})x0*(\d{1,4})(?=$|[\/_.\-\s])/i,
    /(?:^|\/)season[\s._-]*0*(\d{1,3})\/(?:episode|ep|e)[\s._-]*0*(\d{1,4})(?=$|[\/_.\-\s])/i,
  ];

  for (const pattern of patterns) {
    const match = relative.match(pattern);
    if (!match) continue;

    const season = Number(match[1]);
    const episode = Number(match[2]);
    if (Number.isInteger(season) && season > 0 && Number.isInteger(episode) && episode > 0) {
      return { season, episode };
    }
  }

  return null;
}

/**
 * Looser naming (episode-04.mp4, Show E04, season-2/ep03.srt, الحلقة 5).
 * Returns { season|null, episode } — a null season is resolved by the caller
 * (assumed season 1 only when NO file in the folder carries an explicit season).
 */
function parseEpisodeKeyLoose(key, slug) {
  let relative = cleanString(key);
  const prefix = slug + "/";
  if (relative.startsWith(prefix)) relative = relative.slice(prefix.length);

  const epMatch = relative.match(
    /(?:^|[\/_.\-\s])(?:episode|ep|e|الحلقة|حلقة)[\s._-]*0*(\d{1,4})(?=$|[\/_.\-\s])/i
  );
  if (!epMatch) return null;
  const episode = Number(epMatch[1]);
  if (!Number.isInteger(episode) || episode <= 0) return null;

  const seasonMatch = relative.match(
    /(?:^|[\/_.\-\s])(?:season|الموسم|موسم)[\s._-]*0*(\d{1,3})(?=$|[\/_.\-\s])/i
  );
  const season = seasonMatch ? Number(seasonMatch[1]) : null;
  return { season: Number.isInteger(season) && season > 0 ? season : null, episode };
}

function parseAnyEpisodeKey(key, slug) {
  return parseEpisodeKey(key, slug) || parseEpisodeKeyLoose(key, slug);
}

function subtitleScore(key) {
  const name = cleanString(key).split("/").pop().toLowerCase();
  let score = 0;
  if (/(^|[._\-\s])(ar|ara|arb|arabic|ar-sa|ar_sa)([._\-\s]|$)|عربي|العربية/.test(name)) score += 10;
  if (/(^|[._\-\s])(forced|sdh|hi|cc)([._\-\s]|$)/.test(name)) score -= 3;
  if (/\.srt$/.test(name)) score += 1;
  return score;
}

function r2PublicUrl(key) {
  return (
    R2_PUBLIC_BASE +
    "/" +
    cleanString(key)
      .split("/")
      .map((part) => encodeURIComponent(part))
      .join("/")
  );
}

/** @returns {Promise<any>} */
async function scanSeriesR2(env, slug) {
  const prefix = slug + "/";
  const videoRecords = [];
  const subtitleRecords = [];
  let cursor;

  do {
    const options = { prefix, limit: 1000 };
    if (cursor) options.cursor = cursor;

    const page = await env.MOVIES_BUCKET.list(options);
    const objects = Array.isArray(page.objects) ? page.objects : [];

    for (const object of objects) {
      const key = cleanString(object && object.key);
      if (!key) continue;
      if (/\.(mp4|mkv|m4v|webm|mov|m3u8)$/i.test(key)) {
        videoRecords.push({ key, parsed: parseAnyEpisodeKey(key, slug) });
      } else if (/\.(srt|vtt)$/i.test(key)) {
        subtitleRecords.push({ key, parsed: parseAnyEpisodeKey(key, slug) });
      }
    }

    cursor = page.truncated ? cleanString(page.cursor) : "";
  } while (cursor);

  // A season-less file is only trusted when no file in the folder names a season.
  const anyExplicitSeason =
    videoRecords.some((r) => r.parsed && r.parsed.season !== null) ||
    subtitleRecords.some((r) => r.parsed && r.parsed.season !== null);

  const resolve = (record) => {
    if (!record.parsed) return null;
    if (record.parsed.season === null) {
      return anyExplicitSeason ? null : { season: 1, episode: record.parsed.episode };
    }
    return record.parsed;
  };

  const byEpisode = new Map();
  const unmatched = [];
  for (const record of videoRecords) {
    const parsed = resolve(record);
    if (!parsed) {
      unmatched.push(record.key);
      continue;
    }
    const mapKey = parsed.season + ":" + parsed.episode;
    if (!byEpisode.has(mapKey)) byEpisode.set(mapKey, record.key);
  }

  const bestSub = new Map();
  const unmatchedSubs = [];
  for (const record of subtitleRecords) {
    const parsed = resolve(record);
    if (!parsed) {
      unmatchedSubs.push(record.key);
      continue;
    }
    const mapKey = parsed.season + ":" + parsed.episode;
    const score = subtitleScore(record.key);
    const current = bestSub.get(mapKey);
    if (!current || score > current.score) bestSub.set(mapKey, { key: record.key, score });
  }
  const subsByEpisode = new Map();
  for (const [mapKey, value] of bestSub) subsByEpisode.set(mapKey, value.key);

  return {
    videoCount: videoRecords.length,
    byEpisode,
    unmatched,
    subtitleCount: subtitleRecords.length,
    subsByEpisode,
    unmatchedSubs,
  };
}

function seasonSummaryByNumber(details, seasonNumber) {
  const seasons = Array.isArray(details && details.seasons) ? details.seasons : [];
  return (
    seasons.find((season) => Number(season && season.season_number) === seasonNumber) ||
    null
  );
}

/** @returns {Promise<any>} */
async function buildSeriesSnapshot(input, env) {
  const validated = validateSeriesImportInput(input);
  const tmdbId = await resolveTmdbSeriesId(validated.source, env);

  const [arDetails, enDetails, r2] = await Promise.all([
    tmdbGet(
      env,
      "/tv/" + tmdbId,
      { language: "ar-SA", append_to_response: "external_ids" },
      true
    ),
    tmdbGet(
      env,
      "/tv/" + tmdbId,
      { language: "en-US", append_to_response: "external_ids" },
      true
    ),
    scanSeriesR2(env, validated.slug),
  ]);

  const title = pickText(arDetails, enDetails, "name") || "مسلسل";
  const posterPath = pickText(arDetails, enDetails, "poster_path");
  const backdropPath = pickText(arDetails, enDetails, "backdrop_path");
  const poster = tmdbImage(posterPath, "w500");
  const backdrop = tmdbImage(backdropPath, "w1280");
  const overview = pickText(arDetails, enDetails, "overview");
  const year =
    yearFromDate(pickText(arDetails, enDetails, "first_air_date")) || 0;
  const rating = Number(
    Number(pickNumber(arDetails, enDetails, "vote_average") || 0).toFixed(1)
  );
  const category = genresText(arDetails, enDetails);

  const defaultRuntimeList = Array.isArray(arDetails.episode_run_time)
    ? arDetails.episode_run_time
    : Array.isArray(enDetails.episode_run_time)
      ? enDetails.episode_run_time
      : [];
  const defaultRuntime = Number(defaultRuntimeList[0]) || 0;

  const seasonNumbers = new Set();
  for (const details of [arDetails, enDetails]) {
    const seasons = Array.isArray(details && details.seasons) ? details.seasons : [];
    for (const season of seasons) {
      const number = Number(season && season.season_number);
      if (Number.isInteger(number) && number > 0) seasonNumbers.add(number);
    }
  }

  const sortedSeasonNumbers = Array.from(seasonNumbers).sort((a, b) => a - b);
  const seasonsOut = [];
  const episodeMeta = new Map();
  let episodeCount = 0;
  let availableCount = 0;
  let subtitleLinked = 0;

  for (const seasonNumber of sortedSeasonNumbers) {
    const [arSeason, enSeason] = await Promise.all([
      tmdbGet(env, "/tv/" + tmdbId + "/season/" + seasonNumber, {
        language: "ar-SA",
      }),
      tmdbGet(env, "/tv/" + tmdbId + "/season/" + seasonNumber, {
        language: "en-US",
      }),
    ]);

    const arSummary = seasonSummaryByNumber(arDetails, seasonNumber);
    const enSummary = seasonSummaryByNumber(enDetails, seasonNumber);
    const seasonPosterPath =
      pickText(arSeason, enSeason, "poster_path") ||
      pickText(arSummary, enSummary, "poster_path") ||
      posterPath;
    const seasonPoster = tmdbImage(seasonPosterPath, "w500");

    const arEpisodes = Array.isArray(arSeason.episodes) ? arSeason.episodes : [];
    const enEpisodes = Array.isArray(enSeason.episodes) ? enSeason.episodes : [];
    const enByEpisode = new Map(
      enEpisodes.map((episode) => [Number(episode && episode.episode_number), episode])
    );

    const episodeNumbers = new Set();
    for (const episode of arEpisodes) {
      const number = Number(episode && episode.episode_number);
      if (Number.isInteger(number) && number > 0) episodeNumbers.add(number);
    }
    for (const episode of enEpisodes) {
      const number = Number(episode && episode.episode_number);
      if (Number.isInteger(number) && number > 0) episodeNumbers.add(number);
    }

    const episodesOut = [];
    const sortedEpisodes = Array.from(episodeNumbers).sort((a, b) => a - b);

    for (const episodeNumber of sortedEpisodes) {
      const arEpisode =
        arEpisodes.find(
          (episode) => Number(episode && episode.episode_number) === episodeNumber
        ) || {};
      const enEpisode = enByEpisode.get(episodeNumber) || {};
      const mapKey = seasonNumber + ":" + episodeNumber;
      const r2Key = r2.byEpisode.get(mapKey) || "";
      const available = Boolean(r2Key);
      const video = available ? r2PublicUrl(r2Key) : "";
      const epTitle =
        pickText(arEpisode, enEpisode, "name") || "الحلقة " + episodeNumber;
      const thumbPath = pickText(arEpisode, enEpisode, "still_path");
      const thumb = tmdbImage(thumbPath, "w780");
      const epOverview = pickText(arEpisode, enEpisode, "overview");
      const epYear =
        yearFromDate(pickText(arEpisode, enEpisode, "air_date")) || year;
      const runtime =
        pickNumber(arEpisode, enEpisode, "runtime") || defaultRuntime;

      const subtitleKey = available ? r2.subsByEpisode.get(mapKey) : "";
      const subtitle = subtitleKey ? r2PublicUrl(subtitleKey) : "";
      if (subtitle) subtitleLinked++;

      episodesOut.push({
        ep: episodeNumber,
        title: epTitle,
        thumb,
        available,
        video,
        subtitle,
      });

      episodeMeta.set(mapKey, {
        overview: epOverview,
        year: epYear,
        duration: durationText(runtime),
      });

      episodeCount++;
      if (available) availableCount++;
    }

    seasonsOut.push({
      season: seasonNumber,
      poster: seasonPoster,
      episodes: episodesOut,
    });
  }

  const series = {
    title,
    poster,
    overview,
    year,
    rating,
    season_count: seasonsOut.length,
    episode_count: episodeCount,
    available_episode_count: availableCount,
    missing_episode_count: Math.max(0, episodeCount - availableCount),
    seasons: seasonsOut,
    r2_files_found: r2.videoCount,
    r2_unmatched_files: r2.unmatched,
    subtitle_count: subtitleLinked,
    missing_subtitle_count: Math.max(0, availableCount - subtitleLinked),
    r2_subtitle_files_found: r2.subtitleCount,
    r2_unmatched_subtitles: r2.unmatchedSubs,
  };

  return {
    series,
    meta: {
      tmdbId,
      slug: validated.slug,
      backdrop,
      category,
      episodeMeta,
    },
  };
}

function normalizeSeasonPosterOverrides(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return new Map();
  }

  const out = new Map();
  for (const [key, rawUrl] of Object.entries(value)) {
    const season = Number(key);
    const url = cleanString(rawUrl);
    if (
      Number.isInteger(season) &&
      season > 0 &&
      /^https?:\/\//i.test(url)
    ) {
      out.set(season, url);
    }
  }
  return out;
}

function applySeasonPosterOverrides(series, overrides) {
  if (!series || !Array.isArray(series.seasons) || !overrides || !overrides.size) {
    return;
  }

  for (const season of series.seasons) {
    const seasonNumber = Number(season && season.season);
    const override = overrides.get(seasonNumber);
    if (override) season.poster = override;
  }
}

async function previewSeries(request, env) {
  if (!authorized(request, env)) {
    return seriesError("unauthorized", "المصادقة غير صالحة أو رمز الإدارة غير صحيح.", 401);
  }

  try {
    const input = await readRequestData(request);
    const snapshot = await buildSeriesSnapshot(input, env);
    return json({ ok: true, series: snapshot.series });
  } catch (error) {
    return seriesErrorResponse(error);
  }
}

function existingCreatedAt(existing, nowIso, nowMs) {
  const createdAt = cleanString(existing && existing.created_at) || nowIso;
  let createdAtMs = Number(existing && existing.created_at_ms) || 0;
  if (!createdAtMs) {
    const parsed = Date.parse(createdAt);
    if (Number.isFinite(parsed) && parsed > 0) createdAtMs = parsed;
  }
  if (!createdAtMs) createdAtMs = nowMs;
  return { createdAt, createdAtMs };
}

// Fields an admin may have edited by hand; a re-import must never wipe them.
const SERIES_PRESERVE_KEYS = [
  "featured", "featured_order", "hidden", "slug", "tmdb", "quality", "skip_segments", "merged_from",
];

async function addSeries(request, env) {
  if (!authorized(request, env)) {
    return seriesError("unauthorized", "المصادقة غير صالحة أو رمز الإدارة غير صحيح.", 401);
  }

  try {
    const input = await readRequestData(request);
    const snapshot = await buildSeriesSnapshot(input, env);
    const series = snapshot.series;
    const meta = snapshot.meta;

    if (!series.available_episode_count) {
      throw new SeriesImportError(
        "no_episodes_available",
        "لا توجد أي حلقة فيديو جاهزة داخل مجلد R2 المحدد، لذلك لم يتم حفظ المسلسل.",
        400
      );
    }

    const overrides = normalizeSeasonPosterOverrides(input.season_posters);
    applySeasonPosterOverrides(series, overrides);

    const registry = await readSectionsRegistry(env);
    const inputSectionId = cleanString(input.section_id);
    const inputSectionName =
      cleanString(input.section_name) ||
      (inputSectionId ? lookupSectionName(registry, inputSectionId) : "");

    const seriesId = "series_" + meta.tmdbId;
    let addedCount = 0;
    let updatedCount = 0;

    await mutateMovies(
      env,
      (list, holder, ctx) => {
        addedCount = 0;
        updatedCount = 0;
        const nowIso = new Date().toISOString();
        const nowMs = Date.now();

        // The R2 folder must not belong to a different series.
        const owner = buildSlugOwners(list).get(lc(meta.slug));
        if (owner && owner.id !== seriesId) {
          throw new SeriesImportError(
            "slug_in_use",
            slugOwnerMessage(owner) +
              ". إذا كان هذا موسماً جديداً لنفس المسلسل فاستخدم «دمج المواسم» بدل استيراده كمسلسل مستقل.",
            409
          );
        }

        const existingById = new Map();
        for (const entry of list) {
          const id = cleanString(entry && entry.id);
          if (id && !existingById.has(id)) existingById.set(id, entry);
        }

        const upserts = [];
        const targetIds = new Set();

        function pushUpsert(id, type, fields) {
          const existing = existingById.get(id) || null;
          const timestamps = existingCreatedAt(existing, nowIso, nowMs);
          const kept = {};
          if (existing) {
            ctx.tracker.touch(existing);
            for (const key of SERIES_PRESERVE_KEYS) {
              if (existing[key] !== undefined) kept[key] = existing[key];
            }
            if (!fields.subtitle && cleanString(existing.subtitle)) fields.subtitle = existing.subtitle;
            if (!fields.section_id && cleanString(existing.section_id)) {
              fields.section_id = existing.section_id;
              if (!fields.section_name) fields.section_name = cleanString(existing.section_name);
            }
          }
          const item = {
            id,
            type,
            created_at: timestamps.createdAt,
            created_at_ms: timestamps.createdAtMs,
            ...kept,
            ...fields,
          };

          upserts.push(item);
          targetIds.add(id);
          if (existing) updatedCount++;
          else {
            addedCount++;
            ctx.tracker.add(item);
          }
        }

        const sectionId = inputSectionId;
        const ratingText = series.rating > 0 ? String(series.rating) : "";

        pushUpsert(seriesId, "series", {
          section_id: sectionId,
          section_name: inputSectionName,
          series_id: seriesId,
          season: "",
          episode: "",
          title: series.title,
          poster: series.poster,
          series_poster: series.poster,
          season_poster: "",
          backdrop: meta.backdrop,
          video: "",
          link: "",
          year: series.year > 0 ? String(series.year) : "",
          category: meta.category,
          imdb: ratingText,
          description: series.overview,
          duration: "",
          slug: meta.slug,
          tmdb: String(meta.tmdbId),
        });

        for (const season of series.seasons) {
          const seasonNumber = Number(season && season.season);
          if (!Number.isInteger(seasonNumber) || seasonNumber <= 0) continue;

          const seasonPoster = cleanString(season && season.poster) || series.poster;
          const episodes = Array.isArray(season && season.episodes) ? season.episodes : [];

          for (const episode of episodes) {
            if (!episode || !episode.available || !cleanString(episode.video)) continue;

            const episodeNumber = Number(episode.ep);
            if (!Number.isInteger(episodeNumber) || episodeNumber <= 0) continue;

            const episodeId = meta.tmdbId + "_s" + seasonNumber + "e" + episodeNumber;
            const episodeMeta = meta.episodeMeta.get(seasonNumber + ":" + episodeNumber) || {};
            const episodePoster = cleanString(episode.thumb) || seasonPoster || series.poster;

            pushUpsert(episodeId, "episode", {
              section_id: sectionId,
              section_name: inputSectionName,
              series_id: seriesId,
              season: String(seasonNumber),
              episode: String(episodeNumber),
              title: cleanString(episode.title) || "الحلقة " + episodeNumber,
              poster: episodePoster,
              series_poster: series.poster,
              season_poster: seasonPoster,
              backdrop: cleanString(episode.thumb) || meta.backdrop,
              video: cleanString(episode.video),
              link: cleanString(episode.video),
              subtitle: cleanString(episode.subtitle),
              year:
                Number(episodeMeta.year) > 0
                  ? String(Number(episodeMeta.year))
                  : series.year > 0
                    ? String(series.year)
                    : "",
              category: meta.category,
              imdb: ratingText,
              description: cleanString(episodeMeta.overview) || series.overview,
              duration: cleanString(episodeMeta.duration),
            });
          }
        }

        const retained = list.filter((entry) => !targetIds.has(cleanString(entry && entry.id)));
        list.splice(0, list.length, ...upserts, ...retained);

        // Same newest-first rule used by /add-movie.
        sortNewestFirst(list);
        return { label: "استيراد مسلسل: " + series.title };
      },
      { op: "add-series", backup: true }
    );

    return json({
      ok: true,
      message:
        updatedCount > 0
          ? "تم تحديث المسلسل والحلقات الموجودة وإضافة أي حلقات جديدة بدون تكرار."
          : "تمت إضافة المسلسل والحلقات المتوفرة بنجاح.",
      series,
      added_count: addedCount,
      updated_count: updatedCount,
    });
  } catch (error) {
    return adminErrorResponse(error);
  }
}

// -----------------------------------------------------------------------------
// CAROUSEL
// -----------------------------------------------------------------------------

async function readCarousel(env) {
  const parsed = await readJsonFile(env, CAROUSEL_OBJECT, null);

  if (Array.isArray(parsed)) {
    const items = parsed.slice();
    return {
      enabled: items.length > 0,
      maxItems: null,
      items,
    };
  }

  if (!parsed || typeof parsed !== "object") {
    return {
      enabled: false,
      maxItems: null,
      items: [],
    };
  }

  const items = Array.isArray(parsed.items)
    ? parsed.items.slice()
    : [];

  return {
    enabled: parsed.enabled !== false && items.length > 0,
    maxItems: null,
    updatedAt: parsed.updatedAt || null,
    items,
  };
}

async function writeCarousel(env, items) {
  const cleanItems = Array.isArray(items)
    ? items.slice()
    : [];

  const payload = {
    enabled: cleanItems.length > 0,
    maxItems: null,
    updatedAt: new Date().toISOString(),
    items: cleanItems,
  };

  await writeJsonFile(env, CAROUSEL_OBJECT, payload);
  return payload;
}


async function uploadCarouselImage(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const contentType = (request.headers.get("content-type") || "").split(";")[0].trim().toLowerCase();
  const allowed = new Map([
    ["image/jpeg", "jpg"],
    ["image/jpg", "jpg"],
    ["image/png", "png"],
    ["image/webp", "webp"],
  ]);

  if (!allowed.has(contentType)) {
    return json({ ok: false, error: "unsupported_image_type", allowed: Array.from(allowed.keys()) }, 415);
  }

  const body = await request.arrayBuffer();
  if (!body || body.byteLength <= 0) {
    return json({ ok: false, error: "empty_image" }, 400);
  }
  if (body.byteLength > MAX_CAROUSEL_IMAGE_BYTES) {
    return json({ ok: false, error: "image_too_large", maxBytes: MAX_CAROUSEL_IMAGE_BYTES }, 413);
  }

  const ext = allowed.get(contentType);
  const id = crypto.randomUUID().replace(/-/g, "").slice(0, 16);
  const key = `carousel/${Date.now()}_${id}.${ext}`;

  await env.MOVIES_BUCKET.put(key, body, {
    httpMetadata: {
      contentType,
      cacheControl: "public, max-age=31536000, immutable",
    },
    customMetadata: {
      kind: "carousel-image",
      uploadedAt: new Date().toISOString(),
    },
  });

  const url = `${R2_PUBLIC_BASE}/${key.split("/").map(encodeURIComponent).join("/")}`;
  return json({
    ok: true,
    success: true,
    key,
    url,
    contentType,
    size: body.byteLength,
  });
}

function r2CarouselKeyFromUrl(value) {
  const url = cleanString(value);
  if (!url.startsWith(R2_PUBLIC_BASE + "/carousel/")) return "";
  try {
    const u = new URL(url);
    const path = decodeURIComponent(u.pathname.replace(/^\/+/, ""));
    return path.startsWith("carousel/") ? path : "";
  } catch {
    return "";
  }
}

async function publishCarousel(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const input = await readRequestData(request);

  const contentId = cleanString(input.content_id || input.id);
  const type = cleanString(input.type).toLowerCase() === "series" ? "series" : "movie";
  const title = cleanString(input.title);
  const landscapePoster = cleanString(
    input.landscape_poster || input.poster_horizontal || input.hero
  );
  const sourcePoster = cleanString(input.source_poster);
  const sourceBackdrop = cleanString(input.source_backdrop);

  if (!contentId || !title || !/^https?:\/\//i.test(landscapePoster)) {
    return json({ ok: false, error: "missing_or_invalid_fields" }, 400);
  }

  const current = await readCarousel(env);
  const oldItems = Array.isArray(current.items) ? current.items : [];
  const previous = oldItems.find((entry) => cleanString(entry && (entry.content_id ?? entry.id)) === contentId) || null;

  const filtered = oldItems.filter((entry) => {
    const id = cleanString(entry && (entry.content_id ?? entry.id));
    return id !== contentId;
  });

  const newItem = {
    content_id: contentId,
    type,
    title,
    landscape_poster: landscapePoster,
    source_poster: sourcePoster,
    source_backdrop: sourceBackdrop,
    publishedAt: new Date().toISOString(),
  };

  filtered.unshift(newItem);
  const payload = await writeCarousel(env, filtered);

  // Delete a replaced gallery-uploaded image after the new carousel state is safely written.
  try {
    if (previous) {
      const previousUrl = cleanString(previous.landscape_poster || previous.poster_horizontal || previous.hero);
      const oldKey = r2CarouselKeyFromUrl(previousUrl);
      const newKey = r2CarouselKeyFromUrl(landscapePoster);
      if (oldKey && oldKey !== newKey) await env.MOVIES_BUCKET.delete(oldKey);
    }
  } catch {}

  return json({
    ok: true,
    success: true,
    count: payload.items.length,
    item: newItem,
  });
}

async function removeCarousel(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const input = await readRequestData(request);
  const contentId = cleanString(input.content_id || input.id);

  if (!contentId) {
    return json({ ok: false, error: "missing_content_id" }, 400);
  }

  const current = await readCarousel(env);
  const oldItems = Array.isArray(current.items) ? current.items : [];
  const removed = oldItems.find((entry) => cleanString(entry && (entry.content_id ?? entry.id)) === contentId) || null;
  const items = oldItems.filter((entry) => {
    const id = cleanString(entry && (entry.content_id ?? entry.id));
    return id !== contentId;
  });

  const payload = await writeCarousel(env, items);

  try {
    if (removed) {
      const removedUrl = cleanString(removed.landscape_poster || removed.poster_horizontal || removed.hero);
      const key = r2CarouselKeyFromUrl(removedUrl);
      if (key) await env.MOVIES_BUCKET.delete(key);
    }
  } catch {}

  return json({
    ok: true,
    success: true,
    count: payload.items.length,
  });
}

async function reorderCarousel(request, env) {
  if (!authorized(request, env)) {
    return json({ ok: false, error: "unauthorized" }, 401);
  }

  const input = await readRequestData(request);
  const order = Array.isArray(input.order) ? input.order.map(cleanString).filter(Boolean) : [];
  if (!order.length) return json({ ok: false, error: "missing_order" }, 400);

  const current = await readCarousel(env);
  const items = Array.isArray(current.items) ? current.items : [];
  const byId = new Map(items.map(x => [cleanString(x && (x.content_id ?? x.id)), x]));
  const used = new Set();
  const reordered = [];

  for (const id of order) {
    const item = byId.get(id);
    if (item && !used.has(id)) {
      reordered.push(item);
      used.add(id);
    }
  }
  for (const item of items) {
    const id = cleanString(item && (item.content_id ?? item.id));
    if (!used.has(id)) reordered.push(item);
  }

  const payload = await writeCarousel(env, reordered);
  return json({ ok: true, success: true, count: payload.items.length, items: payload.items });
}

// -----------------------------------------------------------------------------
// APP UPDATE
// -----------------------------------------------------------------------------

/**
 * Legacy entry point (POST /publish-update). It now runs through the Update Center engine:
 * the old payload { versionName, downloadUrl, message, sendPush } still works (versionCode
 * is incremented automatically like before) and the response keeps the old top-level fields.
 */
async function publishUpdate(request, env) {
  return adminRoute(handlePublishUpdate)(request, env, new URL(request.url));
}

// =============================================================================
// ADMIN V2 — CORE (concurrency-safe R2 helpers, backups, undo journal)
// =============================================================================
//
// Everything below is ADDITIVE. The original routes keep their behaviour.
//
// Storage map (all inside the R2 bucket bound as MOVIES_BUCKET unless an
// optional private bucket ADMIN_BUCKET is bound):
//
//   movies.json                      (public, existing)  items array
//   cigram_carousel.json             (public, existing)
//   cigram_update.json               (public, existing)
//   cigram_home_sections.json        (public, new)  home sections registry + order
//   cigram_maintenance.json          (public, new)  maintenance mode
//   cigram_announcements.json        (public, new)  in-app announcements
//   cigram_channels_config.json      (public, new)  channel overlay without notes
//   _admin_private/admin_meta.json   (admin only)   notes + link-check results + channel overlay (with notes)
//   _admin_private/backups/*.json    (admin only)   full movies.json backups before risky operations
//   _admin_private/undo/*.json       (admin only)   undo journal (item-level before/after)
//
// IMPORTANT: a bucket exposed through r2.dev is public as a whole. For real
// privacy of notes/backups bind a second, NON-public bucket as ADMIN_BUCKET
// (then the "_admin_private/" prefix is not used). Optional ADMIN_PATH_SALT
// secret makes the fallback prefix unguessable.

const HOME_SECTIONS_OBJECT = "cigram_home_sections.json";
const MAINTENANCE_OBJECT = "cigram_maintenance.json";
const ANNOUNCEMENTS_OBJECT = "cigram_announcements.json";
const CHANNELS_CONFIG_OBJECT = "cigram_channels_config.json";
const ADMIN_META_NAME = "admin_meta.json";
const BACKUP_PREFIX = "backups/";
const UNDO_PREFIX = "undo/";
const BACKUP_KEEP = 40;
const UNDO_KEEP = 80;
const MAX_CHECK_BATCH = 20;
const RESERVED_SLUGS = new Set([
  "carousel", "backups", "undo", "admin", "_admin_private", "api", "movies", "update",
]);

class AdminError extends Error {
  constructor(code, message, status = 400, extra = null) {
    super(message);
    this.name = "AdminError";
    this.code = code;
    this.status = status;
    this.extra = extra;
  }
}

function lc(value) {
  return cleanString(value).toLowerCase();
}

function clone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function numOf(value) {
  const n = parseInt(cleanString(value), 10);
  return Number.isFinite(n) ? n : 0;
}

function truthy(value) {
  if (value === true || value === 1) return true;
  const s = lc(value);
  return s === "true" || s === "1" || s === "yes";
}

function adminBucket(env) {
  return env.ADMIN_BUCKET || env.MOVIES_BUCKET;
}

function adminKey(env, name) {
  if (env.ADMIN_BUCKET) return name;
  const salt = cleanString(env.ADMIN_PATH_SALT);
  return "_admin_private/" + (salt ? salt + "/" : "") + name;
}

// -----------------------------------------------------------------------------
// R2 JSON with optimistic concurrency
// -----------------------------------------------------------------------------

async function r2ReadJson(bucket, key, fallback, keepRaw = true) {
  const obj = await bucket.get(key);
  if (!obj) return { doc: clone(fallback), etag: null, raw: null, exists: false };
  const raw = await obj.text();
  let doc;
  try {
    doc = JSON.parse(raw);
  } catch {
    throw new AdminError(
      "corrupt_json",
      "الملف " + key + " ليس JSON صالحاً. لم يتم تنفيذ أي تعديل حفاظاً على البيانات.",
      500
    );
  }
  return { doc, etag: obj.etag || null, raw: keepRaw ? raw : null, exists: true };
}

async function r2WriteJsonIf(bucket, key, doc, etag) {
  // Big files (movies.json) are written compact: no indentation = ~35% less memory / CPU / download size.
  // Small files keep the readable indented form. The parse-back check is only done where it is cheap.
  const compact = JSON.stringify(doc);
  const text = compact.length < 120000 ? JSON.stringify(doc, null, 2) : compact;
  if (typeof text !== "string" || text.length < 2) throw new AdminError("write_failed", "تعذر تجهيز البيانات للحفظ.", 500);
  if (text.length < 300000) JSON.parse(text); // never write something we cannot parse back
  const options = {
    httpMetadata: {
      contentType: "application/json; charset=utf-8",
      cacheControl: "no-store",
    },
  };
  if (etag) options.onlyIf = { etagMatches: etag };
  const result = await bucket.put(key, text, options);
  return result !== null && result !== undefined;
}

/**
 * Read-modify-write with retry. `fn(draft, current)` may mutate `draft` in
 * place or return { doc, result }. Throwing aborts without writing.
 */
async function mutateJson(bucket, key, fallback, fn, retries = 7, keepRaw = true) {
  for (let attempt = 0; attempt < retries; attempt++) {
    const current = await r2ReadJson(bucket, key, fallback, keepRaw);
    const draft = current.doc;
    const out = (await fn(draft, current)) || {};
    const doc = out.doc !== undefined ? out.doc : draft;
    const ok = await r2WriteJsonIf(bucket, key, doc, current.etag);
    if (ok) return { doc, result: out.result, raw: current.raw, extra: out };
    await sleep(40 * (attempt + 1) + Math.floor(Math.random() * 60));
  }
  throw new AdminError(
    "write_conflict",
    "تعذر الحفظ لأن الملف يتغير باستمرار من عملية أخرى. أعد المحاولة بعد لحظات.",
    409
  );
}

async function listAll(bucket, prefix, include) {
  const out = [];
  let cursor;
  do {
    const options = { prefix, limit: 1000 };
    if (cursor) options.cursor = cursor;
    if (include) options.include = include;
    const page = await bucket.list(options);
    for (const object of page.objects || []) out.push(object);
    cursor = page.truncated ? cleanString(page.cursor) : "";
  } while (cursor);
  return out;
}

// -----------------------------------------------------------------------------
// Item helpers
// -----------------------------------------------------------------------------

function isEpisodeItem(item) {
  const t = lc(item && item.type);
  return t === "episode" || (t === "series" && numOf(item && item.episode) > 0);
}

function isSeriesHeader(item) {
  return lc(item && item.type) === "series" && !isEpisodeItem(item);
}

function isMovieItem(item) {
  const t = lc(item && item.type);
  return t === "" || t === "movie";
}

function itemKind(item) {
  if (isEpisodeItem(item)) return "episode";
  if (isSeriesHeader(item)) return "series";
  return "movie";
}

function seriesKeyOf(header) {
  return cleanString(header && header.series_id) || cleanString(header && header.id);
}

function episodeBelongsTo(episode, header) {
  const sid = cleanString(episode && episode.series_id);
  if (!sid) return false;
  return sid === cleanString(header.series_id) || sid === cleanString(header.id);
}

function episodesOf(list, header) {
  return list.filter((x) => x && isEpisodeItem(x) && x !== header && episodeBelongsTo(x, header));
}

function findById(list, id) {
  const wanted = cleanString(id);
  if (!wanted) return null;
  return list.find((x) => x && cleanString(x.id) === wanted) || null;
}

function kindLabel(kind) {
  return kind === "series" ? "مسلسل" : kind === "episode" ? "حلقة" : "فيلم";
}

function sortNewestFirst(list) {
  const decorated = list.map((entry, index) => ({ entry, index }));
  decorated.sort((a, b) => {
    const ta = Number(a.entry && a.entry.created_at_ms) || 0;
    const tb = Number(b.entry && b.entry.created_at_ms) || 0;
    if (ta !== tb) return tb - ta;
    return a.index - b.index;
  });
  list.splice(0, list.length, ...decorated.map((x) => x.entry));
}

// -----------------------------------------------------------------------------
// Change tracker -> undo journal
// -----------------------------------------------------------------------------

class ChangeTracker {
  constructor(list) {
    this.originals = new Map();
    this.added = new Set();
    this.removed = new Set();
    this.indexOf = new Map();
    (list || []).forEach((entry, index) => {
      if (entry && cleanString(entry.id)) this.indexOf.set(cleanString(entry.id), index);
    });
  }

  touch(item) {
    const id = cleanString(item && item.id);
    if (id && !this.originals.has(id) && !this.added.has(id)) {
      this.originals.set(id, clone(item));
    }
    return item;
  }

  add(item) {
    const id = cleanString(item && item.id);
    if (id) this.added.add(id);
    return item;
  }

  remove(item) {
    const id = cleanString(item && item.id);
    if (!id) return;
    if (!this.originals.has(id)) this.originals.set(id, clone(item));
    this.removed.add(id);
  }

  records(list) {
    const current = new Map();
    for (const entry of list) {
      if (entry && cleanString(entry.id)) current.set(cleanString(entry.id), entry);
    }
    const out = [];
    for (const [id, before] of this.originals) {
      if (this.removed.has(id)) {
        out.push({ id, before, after: null, index: this.indexOf.get(id) || 0 });
        continue;
      }
      const now = current.get(id);
      if (now && JSON.stringify(now) !== JSON.stringify(before)) {
        out.push({ id, before, after: clone(now), index: this.indexOf.get(id) || 0 });
      }
    }
    for (const id of this.added) {
      const now = current.get(id);
      if (now) out.push({ id, before: null, after: clone(now), index: 0 });
    }
    return out;
  }
}

function randomId() {
  return Math.random().toString(36).slice(2, 8);
}

async function pruneByCount(bucket, prefix, keep) {
  try {
    const all = await listAll(bucket, prefix);
    if (all.length <= keep) return;
    all.sort((a, b) => (a.key < b.key ? -1 : 1));
    const doomed = all.slice(0, all.length - keep).map((o) => o.key);
    for (let i = 0; i < doomed.length; i += 100) {
      await bucket.delete(doomed.slice(i, i + 100));
    }
  } catch {
    // pruning is best effort only
  }
}

async function saveUndoRecord(env, label, op, changes, configs) {
  const cfg = configs || [];
  if (!changes.length && !cfg.length) return "";
  const id = String(Date.now()).padStart(13, "0") + "_" + randomId();
  const record = {
    id,
    label: cleanString(label) || op,
    op,
    at: new Date().toISOString(),
    changes,
    configs: cfg,
    undone_at: null,
  };
  const bucket = adminBucket(env);
  const key = adminKey(env, UNDO_PREFIX + id + ".json");
  await bucket.put(key, JSON.stringify(record), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
    customMetadata: {
      label: encodeURIComponent(record.label).slice(0, 400),
      op,
      count: String(changes.length),
      at: record.at,
    },
  });
  await pruneByCount(bucket, adminKey(env, UNDO_PREFIX), UNDO_KEEP);
  return id;
}

/** Copies movies.json to a backup object by streaming it (nothing is held in memory). Falls back to a text copy. */
async function backupMoviesObject(env, label, count) {
  const bucket = adminBucket(env);
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const key = adminKey(env, BACKUP_PREFIX + stamp + "_" + String(label || "op").replace(/[^A-Za-z0-9_-]/g, "") + ".json");
  const opts = {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
    customMetadata: { label: String(label || "op"), count: String(count || 0), at: new Date().toISOString() },
  };
  const src = await env.MOVIES_BUCKET.get(MOVIES_OBJECT);
  if (!src) return "";
  try {
    await bucket.put(key, src.body, opts);
  } catch {
    const again = await env.MOVIES_BUCKET.get(MOVIES_OBJECT);
    if (!again) return "";
    await bucket.put(key, await again.text(), opts);
  }
  await pruneByCount(bucket, adminKey(env, BACKUP_PREFIX), BACKUP_KEEP);
  return key;
}

async function saveBackup(env, label, rawText) {
  if (!rawText) return "";
  const bucket = adminBucket(env);
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const key = adminKey(env, BACKUP_PREFIX + stamp + "_" + String(label || "op").replace(/[^A-Za-z0-9_-]/g, "") + ".json");
  let count = 0;
  try {
    const parsed = JSON.parse(rawText);
    count = getWritableMoviesArray(parsed).list.length;
  } catch {
    count = 0;
  }
  await bucket.put(key, rawText, {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
    customMetadata: { label: String(label || "op"), count: String(count), at: new Date().toISOString() },
  });
  await pruneByCount(bucket, adminKey(env, BACKUP_PREFIX), BACKUP_KEEP);
  return key;
}

/**
 * Safe read-modify-write of movies.json.
 * fn(list, holder, ctx) may mutate `list`; ctx.tracker records changes.
 * options: { label, op, backup, configs }
 *   configs: optional async function returning [{object, before}] captured by caller.
 */
async function mutateMovies(env, fn, options = {}) {
  const bucket = env.MOVIES_BUCKET;
  let meta = {};
  /** @type {any} */
  let tracker = null;
  let lengthBefore = 0;
  let backupDone = false;
  const out = await mutateJson(bucket, MOVIES_OBJECT, [], async (draft, current) => {
    const holder = getWritableMoviesArray(draft);
    const list = holder.list;
    // a corrupted entry must never be silently dropped
    for (const entry of list) {
      if (!entry || typeof entry !== "object") {
        throw new AdminError("corrupt_entry", "movies.json يحتوي على عنصر غير صالح. تم إيقاف العملية لحماية البيانات.", 500);
      }
    }
    tracker = new ChangeTracker(list);
    lengthBefore = list.length;
    const ctx = { tracker, raw: null };
    meta = (await fn(list, holder, ctx)) || {};
    if (meta.expectDelta !== undefined && list.length - lengthBefore !== meta.expectDelta) {
      throw new AdminError("unexpected_loss", "عدد العناصر بعد العملية لا يطابق المتوقع. لم يُحفظ شيء.", 500);
    }
    if (options.backup && !meta.skipBackup && !backupDone) {
      backupDone = true;
      meta.backupKey = await backupMoviesObject(env, options.op || "op", lengthBefore);
    }
    return { doc: holder.root, result: meta.result };
  }, 7, false);
  const records = tracker.records(getWritableMoviesArray(out.doc).list);
  // v9: something new became visible -> phone notification (runs after the reply, never blocks it)
  await catalogFeedAfterWrite(env, getWritableMoviesArray(out.doc).list, options.op || "");   // 18.4: revision + change feed (never throws)
  pushAfterMoviesSave(env, records, getWritableMoviesArray(out.doc).list, options.op || "");
  let undoId = "";
  if (!meta.noJournal) {
    undoId = await saveUndoRecord(
      env,
      meta.label || options.label || options.op || "تعديل",
      options.op || "edit",
      records,
      meta.configs || []
    );
  }
  return {
    doc: out.doc,
    result: out.result,
    undo_id: undoId,
    backup: meta.backupKey || "",
    changed: records.length,
  };
}

// -----------------------------------------------------------------------------
// Auth + route wrapper for V2 admin routes
// -----------------------------------------------------------------------------

function timingSafeEqualText(a, b) {
  const x = String(a || "");
  const y = String(b || "");
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) {
    diff |= (x.charCodeAt(i) || 0) ^ (y.charCodeAt(i) || 0);
  }
  return diff === 0;
}

function adminAuthorized(request, env) {
  const bearer = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
  const headerToken = (request.headers.get("x-admin-token") || "").trim();
  const token = bearer || headerToken;
  return Boolean(env.ADMIN_TOKEN) && timingSafeEqualText(token, env.ADMIN_TOKEN);
}

function adminErrorResponse(error) {
  if (error instanceof AdminError) {
    return json(
      Object.assign(
        { ok: false, success: false, error: error.code, error_code: errorCodeOf(error.code), message: error.message },
        error.extra || {}
      ),
      error.status
    );
  }
  if (error instanceof SeriesImportError) {
    return seriesError(error.error, error.message, error.status);
  }
  return json(
    {
      ok: false,
      success: false,
      error: "internal_error",
      error_code: "INTERNAL_ERROR",
      message: "خطأ داخلي في الخادم: " + (error && error.message ? error.message : String(error)),
    },
    500
  );
}

// The legacy AddMovie screen shows `error` directly in a toast, so for it the human message goes there.
function legacyErrorResponse(error) {
  if (error instanceof AdminError) {
    return json(
      Object.assign({ ok: false, success: false, error: error.message, code: error.code, error_code: errorCodeOf(error.code), message: error.message }, error.extra || {}),
      error.status
    );
  }
  return adminErrorResponse(error);
}

function adminRoute(handler) {
  return async (request, env, url) => {
    if (!adminAuthorized(request, env)) {
      return json({ ok: false, success: false, error: "unauthorized", error_code: "UNAUTHORIZED", message: "رمز الإدارة غير صحيح أو مفقود." }, 401);
    }
    try {
      return await withSuccessFlag(await handler(request, env, url));
    } catch (error) {
      return adminErrorResponse(error);
    }
  };
}

// -----------------------------------------------------------------------------
// Admin meta (notes, link-check results, channel overlay)
// -----------------------------------------------------------------------------

function emptyAdminMeta() {
  return {
    version: 1,
    notes: { items: {}, seasons: {}, channels: {}, sections: {} },
    checks: {},
    channels: {},
  };
}

function normalizeAdminMeta(doc) {
  const base = emptyAdminMeta();
  const meta = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};
  const notes = meta.notes && typeof meta.notes === "object" ? meta.notes : {};
  return {
    version: 1,
    notes: {
      items: notes.items && typeof notes.items === "object" ? notes.items : base.notes.items,
      seasons: notes.seasons && typeof notes.seasons === "object" ? notes.seasons : base.notes.seasons,
      channels: notes.channels && typeof notes.channels === "object" ? notes.channels : base.notes.channels,
      sections: notes.sections && typeof notes.sections === "object" ? notes.sections : base.notes.sections,
    },
    checks: meta.checks && typeof meta.checks === "object" ? meta.checks : {},
    channels: meta.channels && typeof meta.channels === "object" ? meta.channels : {},
  };
}

async function readAdminMeta(env) {
  const current = await r2ReadJson(adminBucket(env), adminKey(env, ADMIN_META_NAME), emptyAdminMeta());
  return normalizeAdminMeta(current.doc);
}

async function mutateAdminMeta(env, fn) {
  return mutateJson(adminBucket(env), adminKey(env, ADMIN_META_NAME), emptyAdminMeta(), async (draft, current) => {
    const meta = normalizeAdminMeta(draft);
    const result = await fn(meta);
    return { doc: meta, result };
  });
}

// =============================================================================
// ADMIN V2 — SLUGS
// =============================================================================

function validSlugFormat(slug) {
  return /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(slug) && slug.length <= 80;
}

function inferSlugFromUrl(url) {
  const value = cleanString(url);
  if (!value.startsWith(R2_PUBLIC_BASE + "/")) return "";
  const rest = value.slice(R2_PUBLIC_BASE.length + 1);
  if (!rest.includes("/")) return "";
  try {
    return decodeURIComponent(rest.split("/")[0] || "");
  } catch {
    return rest.split("/")[0] || "";
  }
}

/** Map<lowercase slug, {id,title,type}> — stored slugs plus slugs inferred from episode R2 URLs. */
function buildSlugOwners(list) {
  const owners = new Map();
  const firstVideoBySeries = new Map();
  for (const item of list) {
    if (!item || !isEpisodeItem(item)) continue;
    const sid = cleanString(item.series_id);
    if (!sid || firstVideoBySeries.has(sid)) continue;
    const inferred = inferSlugFromUrl(item.video || item.link);
    if (inferred) firstVideoBySeries.set(sid, inferred);
  }
  for (const item of list) {
    if (!item || isEpisodeItem(item)) continue;
    let slug = lc(item.slug);
    let inferred = false;
    if (!slug && isSeriesHeader(item)) {
      slug = lc(firstVideoBySeries.get(seriesKeyOf(item)) || firstVideoBySeries.get(cleanString(item.id)) || "");
      inferred = Boolean(slug);
    }
    if (!slug) continue;
    if (!owners.has(slug)) {
      owners.set(slug, {
        id: cleanString(item.id),
        title: cleanString(item.title),
        type: itemKind(item),
        inferred,
      });
    }
  }
  return owners;
}

function nextFreeSlug(base, isTaken) {
  const root = base || "item";
  if (!isTaken(root)) return root;
  for (let i = 2; i < 500; i++) {
    const candidate = root + "-" + i;
    if (!isTaken(candidate)) return candidate;
  }
  return root + "-" + Date.now().toString(36);
}

function slugOwnerMessage(owner) {
  return "هذا Slug مستخدم بالفعل بواسطة " + kindLabel(owner.type) + " «" + (owner.title || owner.id) + "»";
}

async function checkSlugImpl(env, input, list) {
  const raw = cleanString(input.slug);
  const kind = lc(input.kind) || "movie";
  const excludeId = cleanString(input.exclude_id);
  const slug = raw.toLowerCase();

  const result = { ok: true, slug: raw, normalized: slug, kind, available: true, format_ok: true };

  if (!raw) {
    return { ...result, available: false, format_ok: false, error: "empty_slug", message: "أدخل Slug أولاً." };
  }
  if (!validSlugFormat(raw)) {
    return {
      ...result,
      available: false,
      format_ok: false,
      error: "invalid_slug",
      message: "Slug غير صالح. استخدم أحرفاً إنجليزية وأرقاماً و . _ - فقط ولا تبدأ برمز.",
    };
  }

  if (kind === "section") {
    const registry = await readSectionsRegistry(env);
    const hit = registry.sections.find(
      (s) =>
        (lc(s.slug) === slug || lc(s.id) === slug) &&
        cleanString(s.id) !== excludeId
    );
    if (hit) {
      return {
        ...result,
        available: false,
        error: "slug_in_use",
        message: "هذا Slug مستخدم بالفعل بواسطة القسم «" + cleanString(hit.name) + "»",
        owner: { id: hit.id, title: hit.name, type: "section" },
        suggestion: nextFreeSlug(slug, (c) => registry.sections.some((s) => lc(s.slug) === c || lc(s.id) === c)),
      };
    }
    return { ...result, message: "Slug متاح." };
  }

  if (RESERVED_SLUGS.has(slug)) {
    return {
      ...result,
      available: false,
      error: "reserved_slug",
      message: "هذا Slug محجوز لمسارات النظام في R2 («" + slug + "»). اختر اسماً آخر.",
      suggestion: slug + "-2",
    };
  }

  const owners = buildSlugOwners(list);
  const owner = owners.get(slug);
  if (owner && owner.id !== excludeId) {
    return {
      ...result,
      available: false,
      error: "slug_in_use",
      message: slugOwnerMessage(owner) + (owner.inferred ? " (مستنتج من روابط حلقاته في R2)" : ""),
      owner,
      suggestion: nextFreeSlug(slug, (c) => owners.has(c) || RESERVED_SLUGS.has(c)),
    };
  }

  let r2Exists = false;
  try {
    const page = await env.MOVIES_BUCKET.list({ prefix: raw + "/", limit: 1 });
    r2Exists = Array.isArray(page.objects) && page.objects.length > 0;
  } catch {
    r2Exists = false;
  }
  result.r2_exists = r2Exists;
  result.message = r2Exists
    ? "Slug متاح ويوجد مجلد بنفس الاسم في R2 غير مرتبط بمحتوى آخر."
    : kind === "series"
      ? "Slug متاح، لكن لا يوجد مجلد بهذا الاسم في R2 بعد."
      : "Slug متاح.";
  if (raw !== slug) result.message += " (يفضّل أحرف صغيرة فقط)";
  return result;
}

async function handleCheckSlug(request, env) {
  const input = await readRequestData(request);
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  return json(await checkSlugImpl(env, input, list));
}

// =============================================================================
// ADMIN V2 — SECTIONS REGISTRY
// =============================================================================

const SECTION_TYPES = new Set(["movie", "series", "mixed"]);

function sectionSlugFromId(id) {
  return cleanString(id).replace(/[^A-Za-z0-9._-]+/g, "-").replace(/^-+|-+$/g, "").toLowerCase() || "section";
}

function normalizeSection(raw, index) {
  const id = cleanString(raw && raw.id);
  const type = lc(raw && raw.type);
  return {
    id,
    slug: cleanString(raw && raw.slug) || sectionSlugFromId(id),
    name: cleanString(raw && raw.name),
    type: SECTION_TYPES.has(type) ? type : "mixed",
    order: Number.isFinite(Number(raw && raw.order)) ? Number(raw.order) : index + 1,
    enabled: raw && raw.enabled === false ? false : true,
    show_home: raw && raw.show_home === false ? false : true,
    more_only: raw && raw.more_only === true ? true : false,
  };
}

function deriveSectionsFromItems(list) {
  const map = new Map();
  for (const item of list) {
    if (!item || typeof item !== "object") continue;
    const id = cleanString(item.section_id);
    if (!id) continue;
    let entry = map.get(id);
    if (!entry) {
      entry = { id, name: "", kinds: new Set() };
      map.set(id, entry);
    }
    if (!entry.name) entry.name = cleanString(item.section_name);
    entry.kinds.add(isMovieItem(item) ? "movie" : "series");
  }
  const out = [];
  let index = 0;
  for (const entry of map.values()) {
    const type = entry.kinds.size > 1 ? "mixed" : entry.kinds.has("movie") ? "movie" : "series";
    out.push(normalizeSection({ id: entry.id, name: entry.name || entry.id, type }, index++));
  }
  return out;
}

async function readSectionsRegistry(env, list) {
  const current = await r2ReadJson(env.MOVIES_BUCKET, HOME_SECTIONS_OBJECT, null);
  if (current.exists && current.doc && Array.isArray(current.doc.sections)) {
    const sections = current.doc.sections.map(normalizeSection).filter((s) => s.id);
    sections.sort((a, b) => a.order - b.order);
    return { exists: true, sections, updatedAt: current.doc.updatedAt || null, raw: current.raw, etag: current.etag };
  }
  let items = list;
  if (!items) {
    const document = await readJsonFile(env, MOVIES_OBJECT, []);
    items = getWritableMoviesArray(document).list;
  }
  return { exists: false, sections: deriveSectionsFromItems(items), updatedAt: null, raw: null, etag: null };
}

function sectionCounts(list) {
  const counts = new Map();
  for (const item of list) {
    if (!item || typeof item !== "object") continue;
    const id = cleanString(item.section_id);
    if (!id) continue;
    let entry = counts.get(id);
    if (!entry) {
      entry = { total: 0, movies: 0, series: 0, episodes: 0 };
      counts.set(id, entry);
    }
    entry.total++;
    const kind = itemKind(item);
    if (kind === "movie") entry.movies++;
    else if (kind === "series") entry.series++;
    else entry.episodes++;
  }
  return counts;
}

function publicSectionsPayload(sections, updatedAt) {
  const ordered = sections
    .slice()
    .sort((a, b) => a.order - b.order)
    .map((s, i) => ({
      id: s.id,
      slug: s.slug,
      name: s.name,
      type: s.type,
      order: i + 1,
      enabled: s.enabled,
      show_home: s.show_home,
      more_only: s.more_only,
    }));
  return { version: 1, updatedAt, sections: ordered };
}

async function writeSectionsRegistry(env, sections, expectedEtag) {
  const payload = publicSectionsPayload(sections, new Date().toISOString());
  const ok = await r2WriteJsonIf(env.MOVIES_BUCKET, HOME_SECTIONS_OBJECT, payload, expectedEtag || null);
  if (!ok) {
    throw new AdminError("write_conflict", "تغيّر ملف الأقسام أثناء الحفظ. أعد المحاولة.", 409);
  }
  return payload;
}

async function handleSectionsGetPublic(request, env) {
  const registry = await readSectionsRegistry(env);
  const visible = registry.sections.filter((s) => s.enabled);
  return json({
    ok: true,
    source: registry.exists ? "registry" : "derived",
    updatedAt: registry.updatedAt,
    sections: visible.map((s) => ({
      id: s.id, slug: s.slug, name: s.name, type: s.type, order: s.order,
      show_home: s.show_home, more_only: s.more_only,
    })),
  });
}

async function handleSectionsGetAdmin(request, env) {
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  const registry = await readSectionsRegistry(env, list);
  const counts = sectionCounts(list);
  const known = new Set(registry.sections.map((s) => s.id));
  const orphans = [];
  for (const [id, c] of counts) {
    if (!known.has(id)) {
      const sample = list.find((x) => x && cleanString(x.section_id) === id);
      orphans.push({ id, name: cleanString(sample && sample.section_name) || id, ...c });
    }
  }
  return json({
    ok: true,
    source: registry.exists ? "registry" : "derived",
    updatedAt: registry.updatedAt,
    sections: registry.sections.map((s) => ({ ...s, counts: counts.get(s.id) || { total: 0, movies: 0, series: 0, episodes: 0 } })),
    orphans,
  });
}

function renameSectionOnItems(list, ctx, sectionId, newId, newName) {
  let changed = 0;
  for (const item of list) {
    if (!item || cleanString(item.section_id) !== sectionId) continue;
    ctx.tracker.touch(item);
    if (newId !== undefined && newId !== sectionId) item.section_id = newId;
    if (newName !== undefined) item.section_name = newName;
    changed++;
  }
  return changed;
}

/**
 * POST /admin/sections/save
 * { sections: [{id,name?,type?,enabled?,show_home?,more_only?,slug?}], rename_items: true }
 * The ARRAY ORDER defines the display order. Unlisted sections keep their relative
 * order after the listed ones. New ids are created. Nothing is ever deleted here.
 */
async function handleSectionsSave(request, env) {
  const input = await readRequestData(request);
  const incoming = Array.isArray(input.sections) ? input.sections : [];
  if (!incoming.length) throw new AdminError("missing_sections", "لا توجد أقسام لحفظها.", 400);

  const registry = await readSectionsRegistry(env);
  const byId = new Map(registry.sections.map((s) => [s.id, s]));
  const seen = new Set();
  const next = [];
  const renames = [];

  for (const raw of incoming) {
    const id = cleanString(raw && raw.id);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    const old = byId.get(id);
    const merged = normalizeSection({ ...(old || {}), ...raw, id }, next.length);
    if (!merged.name) throw new AdminError("missing_name", "اسم القسم مطلوب (" + id + ").", 400);
    if (old && old.name !== merged.name) renames.push({ id, name: merged.name });
    next.push(merged);
  }
  for (const s of registry.sections) {
    if (!seen.has(s.id)) next.push(s);
  }
  next.forEach((s, i) => { s.order = i + 1; });

  const configBefore = registry.exists ? { object: HOME_SECTIONS_OBJECT, before: JSON.parse(registry.raw) } : { object: HOME_SECTIONS_OBJECT, before: null };
  let undoId = "";
  if (renames.length && input.rename_items !== false) {
    const res = await mutateMovies(env, (list, holder, ctx) => {
      for (const r of renames) renameSectionOnItems(list, ctx, r.id, undefined, r.name);
      return { label: "إعادة تسمية أقسام", configs: [configBefore] };
    }, { op: "sections-rename", backup: true });
    undoId = res.undo_id;
  }
  const payload = await writeSectionsRegistry(env, next, registry.etag);
  if (!undoId) {
    undoId = await saveUndoRecord(env, "حفظ ترتيب/إعدادات الأقسام", "sections-save", [], [configBefore]);
  }
  return json({ ok: true, sections: payload.sections, undo_id: undoId });
}

async function handleSectionsSeed(request, env) {
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  const registry = await readSectionsRegistry(env, list);
  if (registry.exists) return json({ ok: true, seeded: false, sections: registry.sections });
  const payload = await writeSectionsRegistry(env, registry.sections, null);
  return json({ ok: true, seeded: true, sections: payload.sections });
}

async function handleSectionsCreate(request, env) {
  const input = await readRequestData(request);
  const name = cleanString(input.name);
  if (!name) throw new AdminError("missing_name", "اسم القسم مطلوب.", 400);
  const registry = await readSectionsRegistry(env);
  let slug = cleanString(input.slug);
  let id = cleanString(input.id);
  if (!slug) slug = sectionSlugFromId(id || name);
  if (!validSlugFormat(slug)) throw new AdminError("invalid_slug", "Slug غير صالح (أحرف إنجليزية/أرقام/. _ - فقط).", 400);
  const conflict = registry.sections.find((s) => lc(s.slug) === lc(slug));
  if (conflict) {
    throw new AdminError("slug_in_use", "هذا Slug مستخدم بالفعل بواسطة القسم «" + conflict.name + "»", 409, {
      suggestion: nextFreeSlug(lc(slug), (c) => registry.sections.some((s) => lc(s.slug) === c)),
    });
  }
  if (!id) id = slug.replace(/-/g, "_");
  if (registry.sections.some((s) => s.id === id)) {
    throw new AdminError("id_in_use", "المعرّف (ID) «" + id + "» مستخدم بالفعل في قسم آخر.", 409);
  }
  const section = normalizeSection({
    id, slug, name, type: input.type,
    enabled: input.enabled !== false,
    show_home: input.show_home !== false,
    more_only: truthy(input.more_only),
    order: registry.sections.length + 1,
  }, registry.sections.length);
  if (section.more_only) section.show_home = false;
  const configBefore = { object: HOME_SECTIONS_OBJECT, before: registry.exists ? JSON.parse(registry.raw) : null };
  // Persist current derived sections too, so creating the first section does not hide the others.
  const sections = registry.sections.concat([section]);
  const payload = await writeSectionsRegistry(env, sections, registry.etag);
  const undoId = await saveUndoRecord(env, "إنشاء قسم: " + name, "section-create", [], [configBefore]);
  return json({ ok: true, section, sections: payload.sections, undo_id: undoId });
}

async function handleSectionsUpdate(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id);
  if (!id) throw new AdminError("missing_id", "معرّف القسم مطلوب.", 400);
  const registry = await readSectionsRegistry(env);
  const old = registry.sections.find((s) => s.id === id);
  if (!old) throw new AdminError("not_found", "القسم غير موجود.", 404);

  const patch = input.changes && typeof input.changes === "object" ? input.changes : {};
  const merged = normalizeSection({ ...old, ...patch, id: patch.id !== undefined ? cleanString(patch.id) : old.id }, old.order - 1);
  if (patch.name !== undefined && !cleanString(patch.name)) throw new AdminError("missing_name", "اسم القسم لا يمكن أن يكون فارغاً.", 400);
  if (merged.more_only) merged.show_home = false;
  if (!validSlugFormat(merged.slug)) throw new AdminError("invalid_slug", "Slug غير صالح.", 400);

  const others = registry.sections.filter((s) => s.id !== old.id);
  const slugHit = others.find((s) => lc(s.slug) === lc(merged.slug));
  if (slugHit) throw new AdminError("slug_in_use", "هذا Slug مستخدم بالفعل بواسطة القسم «" + slugHit.name + "»", 409);
  if (merged.id !== old.id) {
    if (!merged.id) throw new AdminError("missing_id", "المعرّف الجديد فارغ.", 400);
    if (others.some((s) => s.id === merged.id)) throw new AdminError("id_in_use", "المعرّف الجديد مستخدم بالفعل.", 409);
  }

  const idChanged = merged.id !== old.id;
  const nameChanged = merged.name !== old.name;
  let affected = 0;
  if (idChanged || nameChanged) {
    const document = await readJsonFile(env, MOVIES_OBJECT, []);
    affected = getWritableMoviesArray(document).list.filter((x) => x && cleanString(x.section_id) === old.id).length;
    if (idChanged && affected && !truthy(input.confirm_id_change)) {
      throw new AdminError(
        "confirm_required",
        "تغيير ID القسم سيحدّث " + affected + " عنصراً مرتبطاً به وقد يؤثر على التطبيق القديم. أكّد العملية للمتابعة.",
        409,
        { affected }
      );
    }
  }

  const configBefore = { object: HOME_SECTIONS_OBJECT, before: registry.exists ? JSON.parse(registry.raw) : null };
  let undoId = "";
  if (affected && (idChanged || nameChanged)) {
    const res = await mutateMovies(env, (list, holder, ctx) => {
      renameSectionOnItems(list, ctx, old.id, idChanged ? merged.id : undefined, nameChanged ? merged.name : undefined);
      return { label: "تعديل قسم: " + merged.name, configs: [configBefore] };
    }, { op: "section-update", backup: true });
    undoId = res.undo_id;
  }
  const sections = registry.sections.map((s) => (s.id === old.id ? merged : s));
  const payload = await writeSectionsRegistry(env, sections, registry.etag);
  if (!undoId) undoId = await saveUndoRecord(env, "تعديل قسم: " + merged.name, "section-update", [], [configBefore]);
  return json({ ok: true, section: merged, sections: payload.sections, affected_items: affected, undo_id: undoId });
}

async function handleSectionsDelete(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id);
  const moveTo = cleanString(input.move_to);
  const confirm = truthy(input.confirm);
  const registry = await readSectionsRegistry(env);
  const old = registry.sections.find((s) => s.id === id);
  if (!old) throw new AdminError("not_found", "القسم غير موجود.", 404);
  if (moveTo === id) throw new AdminError("invalid_target", "لا يمكن النقل إلى نفس القسم.", 400);
  const target = moveTo ? registry.sections.find((s) => s.id === moveTo) : null;
  if (moveTo && !target) throw new AdminError("target_not_found", "قسم الوجهة غير موجود.", 404);

  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const count = getWritableMoviesArray(document).list.filter((x) => x && cleanString(x.section_id) === id).length;
  if (count > 0 && !moveTo && !confirm) {
    throw new AdminError(
      "section_not_empty",
      "القسم «" + old.name + "» يحتوي على " + count + " عنصراً. انقل المحتوى إلى قسم آخر أو أكّد الحذف صراحةً.",
      409,
      { count }
    );
  }

  const configBefore = { object: HOME_SECTIONS_OBJECT, before: registry.exists ? JSON.parse(registry.raw) : null };
  let undoId = "";
  let moved = 0;
  if (count > 0 && moveTo) {
    const res = await mutateMovies(env, (list, holder, ctx) => {
      moved = renameSectionOnItems(list, ctx, id, target.id, target.name);
      return { label: "حذف قسم «" + old.name + "» ونقل محتواه", configs: [configBefore] };
    }, { op: "section-delete", backup: true });
    undoId = res.undo_id;
  }
  const sections = registry.sections.filter((s) => s.id !== id);
  sections.forEach((s, i) => { s.order = i + 1; });
  let payload;
  if (!registry.exists && !sections.length) {
    payload = publicSectionsPayload([], new Date().toISOString());
    await r2WriteJsonIf(env.MOVIES_BUCKET, HOME_SECTIONS_OBJECT, payload, null);
  } else {
    payload = await writeSectionsRegistry(env, sections, registry.etag);
  }
  if (!undoId) undoId = await saveUndoRecord(env, "حذف قسم: " + old.name, "section-delete", [], [configBefore]);
  return json({ ok: true, moved_items: moved, orphaned_items: moveTo ? 0 : count, sections: payload.sections, undo_id: undoId });
}

function lookupSectionName(registry, sectionId) {
  const hit = registry.sections.find((s) => s.id === sectionId);
  return hit ? hit.name : "";
}

// =============================================================================
// ADMIN V2 — ITEM EDITING (single, create, bulk, delete)
// =============================================================================

const URL_FIELDS = new Set(["poster", "series_poster", "season_poster", "backdrop", "video", "link", "subtitle"]);
const STRING_FIELDS = new Set([
  "title", "poster", "series_poster", "season_poster", "backdrop", "video", "link", "year",
  "category", "imdb", "tmdb", "quality", "description", "subtitle", "duration",
  "section_id", "section_name", "slug", "series_id", "season", "episode", "skip_segments",
  "imdb_id", "original_title",
]);

function sanitizePatch(item, changes) {
  const patch = {};
  if (!changes || typeof changes !== "object") return patch;
  for (const [key, rawValue] of Object.entries(changes)) {
    if (key === "featured" || key === "hidden") {
      patch[key] = truthy(rawValue);
      continue;
    }
    if (key === "featured_order") {
      const n = Number(rawValue);
      patch[key] = Number.isFinite(n) ? n : 0;
      continue;
    }
    if (!STRING_FIELDS.has(key)) {
      throw new AdminError("field_not_editable", "الحقل «" + key + "» غير قابل للتعديل من هذه الواجهة.", 400);
    }
    const value = cleanString(rawValue);
    if (URL_FIELDS.has(key) && value && !/^https?:\/\//i.test(value)) {
      throw new AdminError("invalid_url", "الحقل «" + key + "» يجب أن يكون رابطاً يبدأ بـ http:// أو https://", 400, { field: key });
    }
    if ((key === "season" || key === "episode" || key === "year") && value && !/^\d{1,4}$/.test(value)) {
      throw new AdminError("invalid_number", "الحقل «" + key + "» يجب أن يكون رقماً.", 400, { field: key });
    }
    patch[key] = value;
  }
  if (patch.video !== undefined) {
    const oldVideo = cleanString(item.video);
    const oldLink = cleanString(item.link);
    if (patch.link === undefined && (!oldLink || oldLink === oldVideo)) patch.link = patch.video;
  }
  return patch;
}

function episodeCollision(list, seriesId, season, episode, excludeId) {
  if (!seriesId || !numOf(episode)) return null;
  return (
    list.find(
      (x) =>
        x &&
        isEpisodeItem(x) &&
        cleanString(x.id) !== excludeId &&
        cleanString(x.series_id) === seriesId &&
        numOf(x.season) === numOf(season) &&
        numOf(x.episode) === numOf(episode)
    ) || null
  );
}

function slugConflict(list, slug, excludeId) {
  const s = lc(slug);
  if (!s) return null;
  if (RESERVED_SLUGS.has(s)) {
    throw new AdminError("reserved_slug", "هذا Slug محجوز لمسارات النظام في R2 («" + s + "»).", 409);
  }
  if (!validSlugFormat(cleanString(slug))) {
    throw new AdminError("invalid_slug", "Slug غير صالح. استخدم أحرفاً إنجليزية وأرقاماً و . _ - فقط.", 400);
  }
  const owner = buildSlugOwners(list).get(s);
  if (owner && owner.id !== excludeId) {
    throw new AdminError("slug_in_use", slugOwnerMessage(owner), 409, { owner });
  }
  return null;
}

async function applySectionFields(env, patch) {
  // keep section_name in sync with the registry when only the id is supplied
  if (patch.section_id !== undefined && patch.section_name === undefined && patch.section_id) {
    const registry = await readSectionsRegistry(env);
    const name = lookupSectionName(registry, patch.section_id);
    if (name) patch.section_name = name;
  }
}

function applyItemPatch(list, ctx, item, patch, cascade) {
  ctx.tracker.touch(item);
  const sectionChanged =
    patch.section_id !== undefined && cleanString(patch.section_id) !== cleanString(item.section_id);
  Object.assign(item, patch);
  if (patch.featured === false) {
    delete item.featured_order;
  }
  if (isSeriesHeader(item) && cascade && (sectionChanged || patch.section_name !== undefined)) {
    for (const ep of episodesOf(list, item)) {
      ctx.tracker.touch(ep);
      if (patch.section_id !== undefined) ep.section_id = item.section_id;
      if (patch.section_name !== undefined) ep.section_name = item.section_name;
    }
  }
}

async function handleItemUpdate(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id);
  if (!id) throw new AdminError("missing_id", "معرّف العنصر مطلوب.", 400);
  const rawChanges = input.changes && typeof input.changes === "object" ? input.changes : {};
  const patchPre = {};
  Object.assign(patchPre, rawChanges);
  const cascade = input.cascade !== false;

  const res = await mutateMovies(env, async (list, holder, ctx) => {
    const item = findById(list, id);
    if (!item) throw new AdminError("not_found", "العنصر غير موجود (ربما حُذف).", 404);
    const patch = sanitizePatch(item, patchPre);
    await applySectionFields(env, patch);

    if (patch.slug !== undefined && patch.slug) slugConflict(list, patch.slug, cleanString(item.id));
    if (isEpisodeItem(item)) {
      const sid = patch.series_id !== undefined ? patch.series_id : cleanString(item.series_id);
      const season = patch.season !== undefined ? patch.season : item.season;
      const ep = patch.episode !== undefined ? patch.episode : item.episode;
      if (patch.series_id !== undefined || patch.season !== undefined || patch.episode !== undefined) {
        const hit = episodeCollision(list, sid, season, ep, cleanString(item.id));
        if (hit) {
          throw new AdminError(
            "episode_exists",
            "الموسم " + numOf(season) + " الحلقة " + numOf(ep) + " موجودة بالفعل في نفس المسلسل («" + cleanString(hit.title) + "»).",
            409,
            { existing: { id: cleanString(hit.id), title: cleanString(hit.title), season: cleanString(hit.season), episode: cleanString(hit.episode) } }
          );
        }
      }
    }
    applyItemPatch(list, ctx, item, patch, cascade);
    const linkedSeries = isEpisodeItem(item)
      ? list.find((x) => x && isSeriesHeader(x) &&
          (cleanString(x.series_id) === cleanString(item.series_id) ||
           cleanString(x.id) === cleanString(item.series_id)))
      : null;
    await autoGenerateVidBolt(item, env, linkedSeries);
    return { result: clone(item), label: "تعديل " + kindLabel(itemKind(item)) + ": " + cleanString(item.title) };
  }, { op: "update" });
  return json({ ok: true, item: res.result, undo_id: res.undo_id });
}

async function handleItemCreate(request, env) {
  const input = await readRequestData(request);
  const type = lc(input.type);
  if (type !== "episode" && type !== "movie" && type !== "series") {
    throw new AdminError("invalid_type", "نوع العنصر غير صالح.", 400);
  }
  const fields = input.fields && typeof input.fields === "object" ? input.fields : input;
  const res = await mutateMovies(env, async (list, holder, ctx) => {
    const base = sanitizePatch({}, filterEditable(fields));
    const item = {
      id: "",
      type,
      created_at: new Date().toISOString(),
      created_at_ms: Date.now(),
    };
    Object.assign(item, base);
    if (type !== "episode") {
      for (const k of ["poster", "backdrop", "video", "link", "year", "category", "imdb", "quality", "description", "subtitle", "duration", "section_id", "section_name", "series_id", "season", "episode"]) {
        if (item[k] === undefined) item[k] = "";
      }
    }

    if (type === "episode") {
      const sid = cleanString(item.series_id);
      if (!sid) throw new AdminError("missing_series", "الحلقة يجب أن ترتبط بمسلسل (series_id).", 400);
      const header = list.find((x) => x && isSeriesHeader(x) && (cleanString(x.series_id) === sid || cleanString(x.id) === sid));
      if (!header) throw new AdminError("series_not_found", "المسلسل المرتبط (series_id = " + sid + ") غير موجود.", 404);
      if (!numOf(item.season) || !numOf(item.episode)) {
        throw new AdminError("missing_numbers", "رقم الموسم ورقم الحلقة مطلوبان.", 400);
      }
      const hit = episodeCollision(list, sid, item.season, item.episode, "");
      if (hit) {
        throw new AdminError(
          "episode_exists",
          "الموسم " + numOf(item.season) + " الحلقة " + numOf(item.episode) + " موجودة بالفعل (" + cleanString(hit.title) + ").",
          409,
          { existing: { id: cleanString(hit.id), title: cleanString(hit.title), season: cleanString(hit.season), episode: cleanString(hit.episode), video: cleanString(hit.video || hit.link) } }
        );
      }
      const siblings = episodesOf(list, header).filter((e) => numOf(e.season) === numOf(item.season));
      const latestSibling = siblings.sort((a, b) => numOf(b.episode) - numOf(a.episode))[0] || null;
      let tmdbSeasonPoster = "";
      if (!latestSibling || !cleanString(latestSibling.season_poster)) {
        tmdbSeasonPoster = await seasonPosterFor(env, header, numOf(item.season));
      }
      const inherit = {
        section_id: header.section_id,
        section_name: header.section_name,
        series_poster: header.series_poster || header.poster,
        category: header.category,
        year: header.year,
        imdb: header.imdb,
        tmdb: header.tmdb || header.tmdb_id,
        tmdb_id: header.tmdb_id || header.tmdb,
        backdrop: header.backdrop,
        quality: latestSibling ? latestSibling.quality : header.quality,
        season_poster: (latestSibling && cleanString(latestSibling.season_poster)) || tmdbSeasonPoster || header.poster,
        poster: (latestSibling && cleanString(latestSibling.season_poster)) || tmdbSeasonPoster || header.poster,
      };
      if (cleanString(header.imdb_id) && !item.imdb_id) item.imdb_id = cleanString(header.imdb_id);
      for (const [k, v] of Object.entries(inherit)) {
        if (item[k] === undefined || item[k] === "") if (v !== undefined) item[k] = cleanString(v);
      }
      const numeric = sid.match(/^series_(\d+)$/);
      const idBase = numeric ? numeric[1] : sid;
      item.id = idBase + "_s" + numOf(item.season) + "e" + numOf(item.episode);
      if (findById(list, item.id)) item.id = item.id + "_" + randomId();
      item.season = String(numOf(item.season));
      item.episode = String(numOf(item.episode));
      if (!item.title) item.title = "الحلقة " + item.episode;
      if (item.video && !item.link) item.link = item.video;
    } else {
      if (!cleanString(item.title)) throw new AdminError("missing_title", "العنوان مطلوب.", 400);
      item.id = makeContentId({ id: cleanString(fields.id) }, list);
      if (findById(list, item.id)) throw new AdminError("id_in_use", "المعرّف مستخدم بالفعل.", 409);
      if (item.slug) slugConflict(list, item.slug, "");
      if (!truthy(input.allow_duplicate)) {
        const dups = findDuplicates(list, { type, tmdb: item.tmdb, imdb: item.imdb_id, title: item.title, year: item.year }).filter(
          (d) => d.reasons.some((r) => r !== "slug")
        );
        if (dups.length) {
          throw new AdminError(
            "duplicate_content",
            "«" + cleanString(item.title) + "» موجود بالفعل في المكتبة (" + (DUP_REASON_TEXT[dups[0].reasons[0]] || "تطابق") + ").",
            409,
            { existing: dups[0], matches: dups }
          );
        }
      }
    }
    const linkedSeries = type === "episode"
      ? list.find((x) => x && isSeriesHeader(x) &&
          (cleanString(x.series_id) === cleanString(item.series_id) ||
           cleanString(x.id) === cleanString(item.series_id)))
      : null;
    await autoGenerateVidBolt(item, env, linkedSeries);
    await applySectionFields(env, item);
    list.unshift(item);
    ctx.tracker.add(item);
    sortNewestFirst(list);
    return { result: clone(item), label: "إضافة " + kindLabel(type) + ": " + cleanString(item.title) };
  }, { op: "create" });
  return json({ ok: true, item: res.result, id: res.result.id, undo_id: res.undo_id });
}

function filterEditable(fields) {
  const out = {};
  for (const [k, v] of Object.entries(fields || {})) {
    if (STRING_FIELDS.has(k) || k === "featured" || k === "hidden" || k === "featured_order") out[k] = v;
  }
  return out;
}

// -----------------------------------------------------------------------------
// Bulk operations
// -----------------------------------------------------------------------------

const BULK_ACTIONS = new Set([
  "set_section", "feature", "unfeature", "hide", "show", "set_quality",
  "set_category", "add_category", "move_to_season", "set_fields", "delete",
]);

function addCategoryText(current, extra) {
  const wanted = cleanString(extra);
  if (!wanted) return current;
  const existing = cleanString(current);
  if (!existing) return wanted;
  const sep = existing.includes("،") ? "، " : existing.includes(",") ? ", " : "، ";
  const parts = existing.split(/[،,]/).map((s) => s.trim()).filter(Boolean);
  if (parts.some((p) => p.toLowerCase() === wanted.toLowerCase())) return existing;
  return parts.concat([wanted]).join(sep);
}

/**
 * Applies a bulk action to `list` (mutating it through ctx.tracker) and returns
 * a summary. Used for both dry-run (on a clone) and real execution.
 */
function runBulk(list, ctx, input, registry) {
  const action = lc(input.action);
  if (!BULK_ACTIONS.has(action)) {
    throw new AdminError("unknown_action", "عملية جماعية غير معروفة: " + action, 400);
  }
  const ids = Array.isArray(input.ids) ? input.ids.map(cleanString).filter(Boolean) : [];
  if (!ids.length) throw new AdminError("empty_selection", "لم يتم تحديد أي عنصر.", 400);
  if (ids.length > 2000) throw new AdminError("too_many", "التحديد كبير جداً (الحد 2000).", 400);
  const params = input.params && typeof input.params === "object" ? input.params : {};
  const cascade = input.cascade !== false;

  const selected = [];
  const missing = [];
  const seen = new Set();
  for (const id of ids) {
    if (seen.has(id)) continue;
    seen.add(id);
    const item = findById(list, id);
    if (item) selected.push(item);
    else missing.push(id);
  }

  const warnings = [];
  const blocking = [];
  const touched = new Set();
  const note = (item) => { touched.add(cleanString(item.id)); };

  if (missing.length) warnings.push(missing.length + " عنصراً من التحديد لم يعد موجوداً وسيتم تجاهله.");

  const startMaxOrder = Math.max(0, ...list.map((x) => Number(x && x.featured_order) || 0));
  let nextOrder = startMaxOrder;

  if (action === "set_section") {
    const sectionId = cleanString(params.section_id);
    if (!sectionId) throw new AdminError("missing_section", "اختر القسم الوجهة.", 400);
    const name = cleanString(params.section_name) || lookupSectionName(registry, sectionId);
    if (!name) warnings.push("اسم القسم غير معروف؛ سيتم حفظ المعرّف فقط.");
    for (const item of selected) {
      const targets = [item];
      if (isSeriesHeader(item) && cascade) targets.push(...episodesOf(list, item));
      for (const t of targets) {
        if (cleanString(t.section_id) === sectionId && (!name || cleanString(t.section_name) === name)) continue;
        ctx.tracker.touch(t);
        t.section_id = sectionId;
        if (name) t.section_name = name;
        note(t);
      }
    }
  } else if (action === "feature" || action === "unfeature") {
    for (const item of selected) {
      if (isEpisodeItem(item)) { warnings.push("الحلقات لا تدعم Featured وتم تجاهلها."); continue; }
      const on = action === "feature";
      if (on && item.featured === true) continue;
      if (!on && item.featured !== true && item.featured_order === undefined) continue;
      ctx.tracker.touch(item);
      if (on) { item.featured = true; item.featured_order = ++nextOrder; }
      else { item.featured = false; delete item.featured_order; }
      note(item);
    }
  } else if (action === "hide" || action === "show") {
    const hide = action === "hide";
    for (const item of selected) {
      if (hide === (item.hidden === true)) continue;
      ctx.tracker.touch(item);
      item.hidden = hide;
      note(item);
    }
    if (hide) {
      const selectedIds2 = new Set(selected.map((x) => cleanString(x.id)));
      const partial = selected.some(
        (x) => isSeriesHeader(x) && episodesOf(list, x).some((e) => e.hidden !== true && !selectedIds2.has(cleanString(e.id)))
      );
      if (partial) warnings.push("إخفاء مسلسل لا يخفي حلقاته تلقائياً؛ حدّد الحلقات أيضاً إذا كان مطلوباً.");
    }
  } else if (action === "set_quality") {
    const quality = cleanString(params.quality);
    for (const item of selected) {
      if (cleanString(item.quality) === quality) continue;
      ctx.tracker.touch(item);
      item.quality = quality;
      note(item);
    }
  } else if (action === "set_category" || action === "add_category") {
    const category = cleanString(params.category);
    if (!category) throw new AdminError("missing_category", "أدخل التصنيف.", 400);
    for (const item of selected) {
      const value = action === "set_category" ? category : addCategoryText(item.category, category);
      if (cleanString(item.category) === value) continue;
      ctx.tracker.touch(item);
      item.category = value;
      note(item);
    }
  } else if (action === "move_to_season") {
    const season = numOf(params.season);
    if (season <= 0) throw new AdminError("invalid_season", "رقم الموسم غير صالح.", 400);
    const eps = selected.filter(isEpisodeItem);
    if (eps.length !== selected.length) {
      warnings.push((selected.length - eps.length) + " عنصراً ليس حلقة وتم تجاهله.");
    }
    const seriesKeys = new Set(eps.map((e) => cleanString(e.series_id)));
    if (seriesKeys.size > 1) {
      blocking.push("الحلقات المحددة تنتمي إلى أكثر من مسلسل؛ نقل الموسم يجب أن يتم لمسلسل واحد في كل مرة.");
    }
    if (!eps.some((e) => cleanString(e.series_id))) {
      blocking.push("الحلقات المحددة غير مرتبطة بمسلسل.");
    }
    // collisions: target season must not already contain the same episode numbers
    const selectedIds = new Set(eps.map((e) => cleanString(e.id)));
    const targetSeen = new Set();
    for (const e of eps) {
      const sid = cleanString(e.series_id);
      const num = numOf(e.episode);
      const key = sid + ":" + num;
      if (targetSeen.has(key)) {
        blocking.push("رقم الحلقة " + num + " مكرر داخل التحديد نفسه.");
        continue;
      }
      targetSeen.add(key);
      const hit = list.find(
        (x) => x && isEpisodeItem(x) && !selectedIds.has(cleanString(x.id)) &&
          cleanString(x.series_id) === sid && numOf(x.season) === season && numOf(x.episode) === num
      );
      if (hit) blocking.push("الحلقة " + num + " موجودة بالفعل في الموسم " + season + " («" + cleanString(hit.title) + "»).");
    }
    if (!blocking.length) {
      for (const e of eps) {
        if (numOf(e.season) === season) continue;
        ctx.tracker.touch(e);
        e.season = String(season);
        note(e);
      }
    }
  } else if (action === "set_fields") {
    const patchAll = sanitizePatch({}, params.fields || {});
    if (!Object.keys(patchAll).length) throw new AdminError("empty_patch", "لا توجد حقول للتعديل.", 400);
    for (const k of ["slug", "series_id", "episode", "season", "video", "link"]) {
      if (patchAll[k] !== undefined && selected.length > 1) {
        throw new AdminError("not_bulk_safe", "الحقل «" + k + "» لا يصلح للتعديل الجماعي لأنه فريد لكل عنصر.", 400);
      }
    }
    for (const item of selected) {
      const keys = Object.keys(patchAll).filter((k) => JSON.stringify(item[k]) !== JSON.stringify(patchAll[k]));
      if (!keys.length) continue;
      ctx.tracker.touch(item);
      for (const k of keys) item[k] = patchAll[k];
      note(item);
    }
  } else if (action === "delete") {
    const cascadeEpisodes = truthy(params.cascade_episodes);
    const toRemove = new Map();
    for (const item of selected) toRemove.set(cleanString(item.id), item);
    for (const item of selected) {
      if (!isSeriesHeader(item)) continue;
      const eps = episodesOf(list, item).filter((e) => !toRemove.has(cleanString(e.id)));
      if (!eps.length) continue;
      if (cascadeEpisodes) {
        for (const e of eps) toRemove.set(cleanString(e.id), e);
      } else {
        blocking.push("المسلسل «" + cleanString(item.title) + "» يحتوي " + eps.length + " حلقة. فعّل «حذف الحلقات أيضاً» أو حدّد الحلقات أولاً.");
      }
    }
    if (!blocking.length) {
      for (const item of toRemove.values()) {
        ctx.tracker.remove(item);
        note(item);
      }
      const kept = list.filter((x) => !(x && toRemove.has(cleanString(x.id))));
      list.splice(0, list.length, ...kept);
    }
  }

  return {
    action,
    selected: selected.length,
    affected: touched.size,
    missing: missing.length,
    warnings: Array.from(new Set(warnings)),
    blocking,
    sample: Array.from(touched).slice(0, 12),
  };
}

async function handleBulk(request, env) {
  const input = await readRequestData(request);
  const registry = await readSectionsRegistry(env);
  const dryRun = truthy(input.dry_run);
  const expect = input.expect_count === undefined ? null : numOf(input.expect_count);

  if (dryRun) {
    const document = await readJsonFile(env, MOVIES_OBJECT, []);
    const list = clone(getWritableMoviesArray(document).list);
    const ctx = { tracker: new ChangeTracker(list) };
    const summary = runBulk(list, ctx, input, registry);
    return json({ ok: true, dry_run: true, ...summary });
  }

  if (expect === null) {
    throw new AdminError("confirm_required", "يجب إرسال عدد العناصر المتأثرة (expect_count) من المعاينة قبل التنفيذ.", 400);
  }

  const res = await mutateMovies(env, (list, holder, ctx) => {
    const summary = runBulk(list, ctx, input, registry);
    if (summary.blocking.length) {
      throw new AdminError("bulk_blocked", summary.blocking.join(" "), 409, { blocking: summary.blocking });
    }
    if (summary.affected !== expect) {
      throw new AdminError(
        "count_mismatch",
        "تغيّر عدد العناصر المتأثرة منذ المعاينة (كان " + expect + " وأصبح " + summary.affected + "). أعد المعاينة.",
        409,
        { now: summary.affected }
      );
    }
    const label = "عملية جماعية: " + summary.action + " (" + summary.affected + ")";
    return { result: summary, label };
  }, { op: "bulk-" + lc(input.action), backup: true });

  return json({ ok: true, dry_run: false, ...res.result, undo_id: res.undo_id, backup: res.backup });
}

async function handleItemDelete(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id);
  if (!id) throw new AdminError("missing_id", "معرّف العنصر مطلوب.", 400);
  const registry = await readSectionsRegistry(env);
  const body = {
    action: "delete",
    ids: [id],
    params: { cascade_episodes: truthy(input.cascade_episodes) },
  };
  const res = await mutateMovies(env, (list, holder, ctx) => {
    const target = findById(list, id);
    if (!target) throw new AdminError("not_found", "العنصر غير موجود.", 404);
    const summary = runBulk(list, ctx, body, registry);
    if (summary.blocking.length) {
      throw new AdminError("delete_blocked", summary.blocking.join(" "), 409, { blocking: summary.blocking });
    }
    return { result: summary, label: "حذف: " + cleanString(target.title) };
  }, { op: "delete", backup: true });
  return json({ ok: true, deleted: res.result.affected, undo_id: res.undo_id, backup: res.backup });
}

// -----------------------------------------------------------------------------
// Featured
// -----------------------------------------------------------------------------

async function handleFeaturedPublic(request, env) {
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  const featured = list
    .filter((x) => x && x.featured === true && x.hidden !== true && !isEpisodeItem(x))
    .sort((a, b) => (Number(a.featured_order) || 1e9) - (Number(b.featured_order) || 1e9));
  return json({ ok: true, count: featured.length, ids: featured.map((x) => cleanString(x.id)), items: featured });
}

async function handleFeaturedReorder(request, env) {
  const input = await readRequestData(request);
  const ids = Array.isArray(input.ids) ? input.ids.map(cleanString).filter(Boolean) : [];
  if (!ids.length) throw new AdminError("missing_ids", "لا توجد عناصر لإعادة ترتيبها.", 400);
  const res = await mutateMovies(env, (list, holder, ctx) => {
    let order = 0;
    const done = new Set();
    for (const id of ids) {
      const item = findById(list, id);
      if (!item || done.has(id) || isEpisodeItem(item)) continue;
      done.add(id);
      ctx.tracker.touch(item);
      item.featured = true;
      item.featured_order = ++order;
    }
    const rest = list
      .filter((x) => x && x.featured === true && !done.has(cleanString(x.id)))
      .sort((a, b) => (Number(a.featured_order) || 1e9) - (Number(b.featured_order) || 1e9));
    for (const item of rest) {
      ctx.tracker.touch(item);
      item.featured_order = ++order;
    }
    return { result: order, label: "ترتيب العناصر المميزة" };
  }, { op: "featured-reorder" });
  return json({ ok: true, count: res.result, undo_id: res.undo_id });
}

// -----------------------------------------------------------------------------
// Undo / history / backups
// -----------------------------------------------------------------------------

async function handleHistory(request, env) {
  const bucket = adminBucket(env);
  const all = await listAll(bucket, adminKey(env, UNDO_PREFIX), ["customMetadata"]);
  all.sort((a, b) => (a.key < b.key ? 1 : -1));
  const entries = all.slice(0, 40).map((o) => {
    const m = o.customMetadata || {};
    let label = m.label || "";
    try { label = decodeURIComponent(label); } catch { /* keep raw */ }
    const name = o.key.split("/").pop().replace(/\.json$/, "");
    return { id: name, label, op: m.op || "", count: Number(m.count) || 0, at: m.at || "" };
  });
  return json({ ok: true, entries });
}

async function handleUndo(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id || input.undo_id);
  if (!/^[0-9]{13}_[a-z0-9]+$/i.test(id)) throw new AdminError("invalid_id", "معرّف التراجع غير صالح.", 400);
  const bucket = adminBucket(env);
  const key = adminKey(env, UNDO_PREFIX + id + ".json");
  const obj = await bucket.get(key);
  if (!obj) throw new AdminError("undo_not_found", "سجل التراجع غير موجود أو انتهت صلاحيته.", 404);
  const record = JSON.parse(await obj.text());
  if (record.undone_at) throw new AdminError("already_undone", "تم التراجع عن هذه العملية من قبل.", 409);

  let conflicts = 0;
  let restored = 0;
  if (Array.isArray(record.changes) && record.changes.length) {
    await mutateMovies(env, (list, holder, ctx) => {
      conflicts = 0;
      restored = 0;
      for (const change of record.changes) {
        const idx = list.findIndex((x) => x && cleanString(x.id) === cleanString(change.id));
        if (change.before === null) {
          if (idx >= 0) {
            if (JSON.stringify(list[idx]) !== JSON.stringify(change.after)) conflicts++;
            ctx.tracker.remove(list[idx]);
            list.splice(idx, 1);
            restored++;
          }
        } else if (idx >= 0) {
          if (change.after && JSON.stringify(list[idx]) !== JSON.stringify(change.after)) conflicts++;
          list[idx] = clone(change.before);
          restored++;
        } else {
          list.splice(Math.min(Math.max(0, change.index || 0), list.length), 0, clone(change.before));
          restored++;
        }
      }
      return { noJournal: true, label: "تراجع", skipBackup: false };
    }, { op: "undo", backup: true });
  }
  for (const cfg of record.configs || []) {
    const name = cleanString(cfg.object);
    if (!name) continue;
    if (cfg.before === null || cfg.before === undefined) {
      await env.MOVIES_BUCKET.delete(name);
    } else {
      const ok = await r2WriteJsonIf(env.MOVIES_BUCKET, name, cfg.before, null);
      if (!ok) throw new AdminError("undo_failed", "تعذر استعادة الملف " + name, 500);
    }
  }
  record.undone_at = new Date().toISOString();
  await bucket.put(key, JSON.stringify(record), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
    customMetadata: {
      label: encodeURIComponent("(تم التراجع) " + record.label).slice(0, 400),
      op: record.op,
      count: String((record.changes || []).length),
      at: record.at,
    },
  });
  return json({ ok: true, restored, conflicts, label: record.label });
}

async function handleBackupsList(request, env) {
  const bucket = adminBucket(env);
  const all = await listAll(bucket, adminKey(env, BACKUP_PREFIX), ["customMetadata"]);
  all.sort((a, b) => (a.key < b.key ? 1 : -1));
  return json({
    ok: true,
    entries: all.slice(0, 40).map((o) => ({
      key: o.key.split("/").slice(-1)[0],
      size: o.size,
      label: (o.customMetadata && o.customMetadata.label) || "",
      count: Number(o.customMetadata && o.customMetadata.count) || 0,
      at: (o.customMetadata && o.customMetadata.at) || "",
    })),
  });
}

async function handleBackupRestore(request, env) {
  const input = await readRequestData(request);
  const name = cleanString(input.key);
  if (!/^[A-Za-z0-9_.\-]+\.json$/.test(name)) throw new AdminError("invalid_key", "اسم النسخة غير صالح.", 400);
  const bucket = adminBucket(env);
  const obj = await bucket.get(adminKey(env, BACKUP_PREFIX + name));
  if (!obj) throw new AdminError("backup_not_found", "النسخة الاحتياطية غير موجودة.", 404);
  const raw = await obj.text();
  let parsed;
  try { parsed = JSON.parse(raw); } catch { throw new AdminError("backup_corrupt", "النسخة الاحتياطية تالفة.", 500); }
  const restoredList = getWritableMoviesArray(parsed).list;
  if (!Array.isArray(restoredList)) throw new AdminError("backup_invalid", "بنية النسخة غير صالحة.", 500);
  const res = await mutateMovies(env, (list, holder, ctx) => {
    holder.root = parsed; // replace the whole document; the pre-restore backup is the safety net
    return { noJournal: true, label: "استعادة نسخة" };
  }, { op: "pre-restore", backup: true });
  return json({ ok: true, restored_count: restoredList.length, safety_backup: res.backup });
}

// =============================================================================
// ADMIN V2 — SEASON MERGE
// =============================================================================

const AR_ORDINALS = "الاول|الثاني|الثالث|الرابع|الخامس|السادس|السابع|الثامن|التاسع|العاشر";

function stripDiacritics(text) {
  return text.replace(/[ً-ٰٟـ]/g, "");
}

function normalizeTitleForMatch(title) {
  let s = stripDiacritics(cleanString(title)).toLowerCase();
  s = s.replace(/[أإآ]/g, "ا").replace(/ى/g, "ي").replace(/ة/g, "ه");
  s = s.replace(new RegExp("(?:^|\\s)(?:ال)?موسم\\s*(?:" + AR_ORDINALS + "|\\d+)", "g"), " ");
  s = s.replace(/\b(?:season|series)\s*\d+\b/g, " ").replace(/\bs\d{1,2}\b/g, " ").replace(/\b(?:part|pt)\s*\d+\b/g, " ");
  s = s.replace(/[^\p{L}\p{N}\s]/gu, " ").replace(/\s+/g, " ").trim();
  return s;
}

function slugBaseForMatch(slug) {
  return lc(slug).replace(/[-_.]?(?:s|season)[-_.]?\d+$/, "").replace(/[-_.]\d+$/, "");
}

function seriesIdBaseForMatch(sid) {
  return lc(sid).replace(/[-_.]?(?:s|season)\d+$/, "");
}

function tmdbIdOf(header) {
  const explicit = cleanString(header.tmdb || header.tmdb_id).match(/\d+/);
  if (explicit) return explicit[0];
  const fromSeriesId = cleanString(header.series_id || header.id).match(/^series_(\d+)$/i);
  return fromSeriesId ? fromSeriesId[1] : "";
}

function imdbIdOf(header) {
  const m = cleanString(header.imdb).match(/^tt\d{5,12}$/i);
  return m ? m[0].toLowerCase() : "";
}

function seasonSummaryOfEpisodes(eps) {
  const map = new Map();
  for (const e of eps) {
    const s = numOf(e.season);
    map.set(s, (map.get(s) || 0) + 1);
  }
  return Array.from(map.entries()).sort((a, b) => a[0] - b[0]).map(([season, count]) => ({ season, count }));
}

function describeSeries(list, header, owners) {
  const eps = episodesOf(list, header);
  let slug = cleanString(header.slug);
  if (!slug) {
    for (const e of eps) {
      slug = inferSlugFromUrl(e.video || e.link);
      if (slug) break;
    }
  }
  return {
    id: cleanString(header.id),
    series_id: seriesKeyOf(header),
    title: cleanString(header.title),
    slug,
    tmdb: tmdbIdOf(header),
    poster: cleanString(header.poster || header.series_poster),
    section_name: cleanString(header.section_name),
    created_at_ms: Number(header.created_at_ms) || 0,
    episodes: eps.length,
    seasons: seasonSummaryOfEpisodes(eps),
    season_hint: numOf(header.season) || 0,
    featured: header.featured === true,
    hidden: header.hidden === true,
  };
}

function detectSeriesDuplicates(list) {
  const headers = list.filter((x) => x && isSeriesHeader(x));
  const parent = headers.map((_, i) => i);
  const find = (i) => { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; };
  const union = (a, b) => { const ra = find(a), rb = find(b); if (ra !== rb) parent[rb] = ra; };

  const keyMap = new Map();
  const reasonOfKey = new Map();
  headers.forEach((h, i) => {
    const eps = episodesOf(list, h);
    let slug = cleanString(h.slug);
    if (!slug) for (const e of eps) { slug = inferSlugFromUrl(e.video || e.link); if (slug) break; }
    const keys = [];
    const tmdb = tmdbIdOf(h);
    if (tmdb) keys.push(["tmdb:" + tmdb, "نفس TMDB ID (" + tmdb + ")"]);
    const imdb = imdbIdOf(h);
    if (imdb) keys.push(["imdb:" + imdb, "نفس IMDb (" + imdb + ")"]);
    const nt = normalizeTitleForMatch(h.title);
    if (nt.length >= 3) keys.push(["title:" + nt, "نفس الاسم بعد تجاهل رقم الموسم"]);
    if (slug) keys.push(["slug:" + slugBaseForMatch(slug), "Slug متشابه"]);
    const sid = seriesIdBaseForMatch(seriesKeyOf(h));
    if (sid) keys.push(["sid:" + sid, "series_id متشابه"]);
    for (const [key, reason] of keys) {
      reasonOfKey.set(key, reason);
      if (!keyMap.has(key)) keyMap.set(key, []);
      keyMap.get(key).push(i);
    }
  });
  for (const members of keyMap.values()) {
    for (let j = 1; j < members.length; j++) union(members[0], members[j]);
  }
  const groups = new Map();
  headers.forEach((h, i) => {
    const root = find(i);
    if (!groups.has(root)) groups.set(root, []);
    groups.get(root).push(i);
  });

  const out = [];
  for (const members of groups.values()) {
    if (members.length < 2) continue;
    const reasons = new Set();
    for (const [key, idxs] of keyMap) {
      if (idxs.filter((i) => members.includes(i)).length >= 2) reasons.add(reasonOfKey.get(key));
    }
    const items = members.map((i) => describeSeries(list, headers[i]));
    items.sort((a, b) => b.episodes - a.episodes || a.created_at_ms - b.created_at_ms);
    out.push({
      score: reasons.size,
      reasons: Array.from(reasons),
      suggested_target_id: items[0].id,
      message: "يبدو أن هذا موسم تابع لمسلسل موجود.",
      items,
    });
  }
  out.sort((a, b) => b.score - a.score);
  return out;
}

function buildMergePlan(list, targetId, sourceIds, options) {
  const opts = options || {};
  const target = findById(list, targetId);
  if (!target || !isSeriesHeader(target)) {
    throw new AdminError("target_not_found", "المسلسل الهدف غير موجود.", 404);
  }
  const cleanSources = Array.from(new Set((sourceIds || []).map(cleanString).filter(Boolean))).filter((x) => x !== cleanString(target.id));
  if (!cleanSources.length) throw new AdminError("no_sources", "اختر مسلسلاً واحداً على الأقل لدمجه.", 400);
  const targetKey = seriesKeyOf(target);
  const overrides = opts.season_overrides && typeof opts.season_overrides === "object" ? opts.season_overrides : {};
  const onConflict = lc(opts.on_conflict) || "block";

  const sources = [];
  const moves = []; // {episode, season}
  const warnings = [];
  const blocking = [];

  for (const sid of cleanSources) {
    const src = findById(list, sid);
    if (!src || !isSeriesHeader(src)) throw new AdminError("source_not_found", "أحد المسلسلات المراد دمجها غير موجود (" + sid + ").", 404);
    const srcKey = seriesKeyOf(src);
    const eps = srcKey === targetKey ? [] : episodesOf(list, src);
    const seasons = Array.from(new Set(eps.map((e) => numOf(e.season))));
    const override = numOf(overrides[sid]);
    if (override > 0 && seasons.length > 1) {
      blocking.push("«" + cleanString(src.title) + "» يحتوي أكثر من موسم؛ لا يمكن تحويله إلى موسم واحد (" + override + ").");
    }
    for (const e of eps) {
      let season = numOf(e.season);
      if (override > 0) season = override;
      else if (!season && numOf(src.season) > 0) season = numOf(src.season);
      if (!season) {
        warnings.push("حلقات بدون رقم موسم في «" + cleanString(src.title) + "»؛ حدّد رقم الموسم يدوياً قبل الدمج.");
        blocking.push("الحلقة «" + cleanString(e.title) + "» بلا رقم موسم.");
      }
      moves.push({ episode: e, season, source: src });
    }
    sources.push({ header: src, episodes: eps.length, key: srcKey, same_series_id: srcKey === targetKey });
  }

  // conflicts: final (season, episode) pairs must be unique
  const taken = new Map();
  for (const e of episodesOf(list, target)) {
    taken.set(numOf(e.season) + ":" + numOf(e.episode), e);
  }
  const conflicts = [];
  const dropSource = new Set();
  const dropTarget = new Set();
  for (const m of moves) {
    const key = m.season + ":" + numOf(m.episode.episode);
    const hit = taken.get(key);
    if (hit && hit !== m.episode) {
      conflicts.push({ season: m.season, episode: numOf(m.episode.episode), target_id: cleanString(hit.id), source_id: cleanString(m.episode.id) });
      if (onConflict === "keep_target") dropSource.add(cleanString(m.episode.id));
      else if (onConflict === "keep_source") { dropTarget.add(cleanString(hit.id)); taken.set(key, m.episode); }
    } else {
      taken.set(key, m.episode);
    }
  }
  if (conflicts.length && onConflict === "block") {
    blocking.push("يوجد " + conflicts.length + " حلقة مكررة (نفس الموسم والحلقة). اختر طريقة معالجة التكرار أو عالجها يدوياً.");
  }

  const posterFills = moves.filter((m) => !cleanString(m.episode.season_poster) && cleanString(m.source.poster)).length;
  const sectionChanges = moves.filter((m) => cleanString(target.section_id) && cleanString(m.episode.section_id) !== cleanString(target.section_id)).length;

  return {
    target,
    targetKey,
    sources,
    moves,
    conflicts,
    dropSource,
    dropTarget,
    warnings: Array.from(new Set(warnings)),
    blocking: Array.from(new Set(blocking)),
    summary: {
      target: { id: cleanString(target.id), title: cleanString(target.title), series_id: targetKey },
      sources: sources.map((s) => ({ id: cleanString(s.header.id), title: cleanString(s.header.title), series_id: s.key, episodes: s.episodes })),
      episodes_moved: moves.length - dropSource.size,
      source_headers_removed: sources.length,
      poster_fills: posterFills,
      section_changes: sectionChanges,
      conflicts,
      conflict_policy: onConflict,
      dropped_source_episodes: dropSource.size,
      dropped_target_episodes: dropTarget.size,
    },
  };
}

function applyMergePlan(list, ctx, plan) {
  const target = plan.target;
  ctx.tracker.touch(target);
  for (const id of plan.dropSource) {
    const ep = findById(list, id);
    if (ep) ctx.tracker.remove(ep);
  }
  for (const id of plan.dropTarget) {
    const ep = findById(list, id);
    if (ep) ctx.tracker.remove(ep);
  }
  const removeIds = new Set([...plan.dropSource, ...plan.dropTarget]);
  const targetPoster = cleanString(target.series_poster || target.poster);
  const merged = Array.isArray(target.merged_from) ? target.merged_from.slice() : [];
  for (const m of plan.moves) {
    const ep = m.episode;
    if (removeIds.has(cleanString(ep.id))) continue;
    ctx.tracker.touch(ep);
    ep.series_id = plan.targetKey;
    ep.season = String(m.season);
    if (!cleanString(ep.season_poster) && cleanString(m.source.poster)) ep.season_poster = cleanString(m.source.poster);
    if (targetPoster) ep.series_poster = targetPoster;
    if (cleanString(target.section_id)) {
      ep.section_id = target.section_id;
      if (cleanString(target.section_name)) ep.section_name = target.section_name;
    }
  }
  for (const s of plan.sources) {
    merged.push({ id: cleanString(s.header.id), title: cleanString(s.header.title), series_id: s.key });
    if (s.header.featured === true && target.featured !== true) {
      target.featured = true;
      if (target.featured_order === undefined) target.featured_order = 1e6;
    }
    ctx.tracker.remove(s.header);
  }
  target.merged_from = merged;
  const sourceHeaderIds = new Set(plan.sources.map((s) => cleanString(s.header.id)));
  const kept = list.filter((x) => !(x && (sourceHeaderIds.has(cleanString(x.id)) || removeIds.has(cleanString(x.id)))));
  list.splice(0, list.length, ...kept);
}

async function handleMergeDetect(request, env) {
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  const groups = detectSeriesDuplicates(list);
  return json({ ok: true, count: groups.length, groups });
}

function mergeInput(input) {
  return {
    targetId: cleanString(input.target_id),
    sourceIds: Array.isArray(input.source_ids) ? input.source_ids : [],
    options: { season_overrides: input.season_overrides, on_conflict: input.on_conflict },
  };
}

async function handleMergePreview(request, env) {
  const input = await readRequestData(request);
  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = clone(getWritableMoviesArray(document).list);
  const m = mergeInput(input);
  const plan = buildMergePlan(list, m.targetId, m.sourceIds, m.options);
  const carousel = await readCarousel(env);
  const affectedCarousel = (carousel.items || []).filter((x) => plan.sources.some((s) => cleanString(s.header.id) === cleanString(x && (x.content_id ?? x.id)))).length;
  return json({
    ok: true,
    blocking: plan.blocking,
    warnings: plan.warnings,
    summary: { ...plan.summary, carousel_entries: affectedCarousel },
  });
}

async function handleMergeExecute(request, env) {
  const input = await readRequestData(request);
  const m = mergeInput(input);
  const expect = input.expect_moved === undefined ? null : numOf(input.expect_moved);
  if (expect === null) throw new AdminError("confirm_required", "أرسل expect_moved من المعاينة قبل التنفيذ.", 400);

  const carouselCurrent = await r2ReadJson(env.MOVIES_BUCKET, CAROUSEL_OBJECT, null);
  const carouselBefore = carouselCurrent.exists ? JSON.parse(carouselCurrent.raw) : null;

  const res = await mutateMovies(env, (list, holder, ctx) => {
    const plan = buildMergePlan(list, m.targetId, m.sourceIds, m.options);
    if (plan.blocking.length) throw new AdminError("merge_blocked", plan.blocking.join(" "), 409, { blocking: plan.blocking });
    if (plan.summary.episodes_moved !== expect) {
      throw new AdminError("count_mismatch", "تغيّر عدد الحلقات منذ المعاينة (كان " + expect + " وأصبح " + plan.summary.episodes_moved + "). أعد المعاينة.", 409);
    }
    applyMergePlan(list, ctx, plan);
    return {
      result: { ...plan.summary, target_id: cleanString(plan.target.id), source_ids: plan.sources.map((s) => cleanString(s.header.id)) },
      label: "دمج مسلسلات في «" + cleanString(plan.target.title) + "»",
      configs: carouselBefore ? [{ object: CAROUSEL_OBJECT, before: carouselBefore }] : [],
    };
  }, { op: "merge", backup: true });

  // carousel: repoint or drop entries of removed series headers (after movies.json is safe)
  let carouselChanged = 0;
  if (carouselCurrent.exists) {
    const sourceIds = new Set(res.result.source_ids);
    const items = Array.isArray(carouselBefore) ? carouselBefore : Array.isArray(carouselBefore && carouselBefore.items) ? carouselBefore.items : [];
    const hasTarget = items.some((x) => cleanString(x && (x.content_id ?? x.id)) === res.result.target_id);
    const next = [];
    for (const entry of items) {
      const id = cleanString(entry && (entry.content_id ?? entry.id));
      if (!sourceIds.has(id)) { next.push(entry); continue; }
      carouselChanged++;
      if (!hasTarget && !next.some((x) => cleanString(x && (x.content_id ?? x.id)) === res.result.target_id)) {
        next.push({ ...entry, content_id: res.result.target_id });
      }
    }
    if (carouselChanged) await writeCarousel(env, next);
  }
  return json({ ok: true, ...res.result, carousel_entries_changed: carouselChanged, undo_id: res.undo_id, backup: res.backup });
}

// =============================================================================
// ADMIN V2 — URL / SUBTITLE / VIDEO CHECKS
// =============================================================================

const CHECK_LABELS = {
  ok: "يعمل",
  missing: "الرابط مفقود",
  bad_url: "رابط غير صالح",
  forbidden: "403 ممنوع",
  unauthorized: "401 غير مصرّح",
  not_found: "404 غير موجود",
  rate_limited: "429 طلبات كثيرة",
  server_error: "خطأ في الخادم",
  http_error: "خطأ HTTP",
  timeout: "انتهت المهلة",
  unreachable: "تعذر الاتصال",
  not_video: "الرابط ليس فيديو",
  empty: "الملف فارغ",
  invalid_subtitle: "ملف الترجمة غير صالح",
  bad_encoding: "ترميز الترجمة غير UTF-8",
  not_image: "الرابط ليس صورة",
};

async function fetchWithTimeout(url, init, ms) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms);
  try {
    return await fetch(url, { ...init, signal: controller.signal, redirect: "follow" });
  } finally {
    clearTimeout(timer);
  }
}

async function readUpTo(response, maxBytes) {
  if (!response.body) return new Uint8Array(0);
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  try {
    while (total < maxBytes) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      total += value.byteLength;
    }
  } finally {
    try { await reader.cancel(); } catch { /* ignore */ }
  }
  const out = new Uint8Array(Math.min(total, maxBytes));
  let offset = 0;
  for (const c of chunks) {
    const take = Math.min(c.byteLength, out.length - offset);
    if (take <= 0) break;
    out.set(c.subarray(0, take), offset);
    offset += take;
  }
  return out;
}

function inspectSubtitleBytes(bytes) {
  const info = { utf8: true, bom: false, cues: 0, arabic_chars: 0, mojibake: false, bytes: bytes.length };
  let view = bytes;
  if (view.length >= 3 && view[0] === 0xef && view[1] === 0xbb && view[2] === 0xbf) {
    info.bom = true;
    view = view.subarray(3);
  }
  let text = "";
  try {
    // a partial read may cut a multibyte char in half: trim up to 3 trailing bytes
    let end = view.length;
    let decoded = null;
    for (let cut = 0; cut <= 3 && decoded === null; cut++) {
      try {
        decoded = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(view.subarray(0, Math.max(0, end - cut)));
      } catch {
        decoded = null;
      }
    }
    if (decoded === null) throw new Error("not utf8");
    text = decoded;
  } catch {
    info.utf8 = false;
    text = new TextDecoder("utf-8").decode(view);
  }
  info.cues = (text.match(/\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}/g) || []).length;
  if (!info.cues) {
    info.cues = (text.match(/\d{1,2}:\d{2}[,.]\d{1,3}\s*-->\s*\d{1,2}:\d{2}[,.]\d{1,3}/g) || []).length;
  }
  info.arabic_chars = (text.match(/[؀-ۿ]/g) || []).length;
  info.mojibake = /[\u00D8][\u00A7-\u00BF]|[\u00D9][\u0080-\u00BF]/.test(text) && info.arabic_chars === 0;
  info.webvtt = /^\s*WEBVTT/.test(text);
  return info;
}

async function checkUrlOne(item) {
  const id = cleanString(item && item.id);
  const kind = lc(item && item.kind) || "video";
  const url = cleanString(item && item.url);
  const base = { id, kind, url, at: new Date().toISOString() };
  const done = (status, extra) => ({ ...base, status, label: CHECK_LABELS[status] || status, ...(extra || {}) });

  if (!url) return done("missing");
  if (!/^https?:\/\//i.test(url)) return done("bad_url");
  try { new URL(url); } catch { return done("bad_url"); }

  // VidBolt links are iframe player pages (HTML), not media files: an HTTP probe from the worker gets
  // 403 / "not a video" answers that say nothing about playback, so they are accepted without a probe.
  if (kind !== "subtitle" && isVidBoltUrl(url)) {
    return done("ok", { embed: true, detail: "VidBolt iframe player (no HTTP check)" });
  }

  const started = Date.now();
  const wantBody = kind === "subtitle" || /\.m3u8(\?|$)/i.test(url);
  let response;
  try {
    response = await fetchWithTimeout(
      url,
      { method: "GET", headers: { range: wantBody ? "bytes=0-65535" : "bytes=0-1023", "user-agent": "CigramAdmin/2" } },
      9000
    );
  } catch (error) {
    const aborted = error && (error.name === "AbortError" || /abort/i.test(String(error.message)));
    return done(aborted ? "timeout" : "unreachable", { ms: Date.now() - started, detail: aborted ? "" : String(error && error.message ? error.message : error).slice(0, 160) });
  }
  const ms = Date.now() - started;
  const http = response.status;
  const type = cleanString(response.headers.get("content-type")).split(";")[0].toLowerCase();
  let size = 0;
  const range = cleanString(response.headers.get("content-range")).match(/\/(\d+)$/);
  if (range) size = Number(range[1]);
  else if (http === 200) size = Number(response.headers.get("content-length")) || 0;
  const extra = { http, ms, size, type };

  if (http === 401) { try { await response.body?.cancel(); } catch {} return done("unauthorized", extra); }
  if (http === 403) { try { await response.body?.cancel(); } catch {} return done("forbidden", extra); }
  if (http === 404 || http === 410) { try { await response.body?.cancel(); } catch {} return done("not_found", extra); }
  if (http === 429) { try { await response.body?.cancel(); } catch {} return done("rate_limited", extra); }
  if (http === 416) { try { await response.body?.cancel(); } catch {} return done("empty", extra); }
  if (http >= 500) { try { await response.body?.cancel(); } catch {} return done("server_error", extra); }
  if (http < 200 || http >= 300) { try { await response.body?.cancel(); } catch {} return done("http_error", extra); }

  if (kind === "subtitle") {
    const bytes = await readUpTo(response, 65536);
    if (!bytes.length) return done("empty", extra);
    const info = inspectSubtitleBytes(bytes);
    const sub = { ...extra, subtitle: info };
    if (/text\/html/.test(type)) return done("invalid_subtitle", sub);
    if (!info.cues) return done("invalid_subtitle", sub);
    if (!info.utf8 || info.mojibake) return done("bad_encoding", sub);
    return done("ok", sub);
  }

  if (kind === "image") {
    try { await response.body?.cancel(); } catch {}
    if (type && !type.startsWith("image/") && type !== "application/octet-stream") return done("not_image", extra);
    return done("ok", extra);
  }

  // video
  if (/\.m3u8(\?|$)/i.test(url)) {
    const bytes = await readUpTo(response, 400);
    const head = new TextDecoder("utf-8").decode(bytes);
    if (!/^\s*#EXTM3U/.test(head)) return done("not_video", { ...extra, detail: "ملف m3u8 غير صالح" });
    return done("ok", extra);
  }
  try { await response.body?.cancel(); } catch {}
  if (/text\/html|application\/json|text\/plain/.test(type)) return done("not_video", extra);
  if (http === 200 && size === 0 && cleanString(response.headers.get("content-length")) === "0") return done("empty", extra);
  return done("ok", extra);
}

async function handleCheckUrls(request, env) {
  const input = await readRequestData(request);
  const items = Array.isArray(input.items) ? input.items.slice(0, MAX_CHECK_BATCH) : [];
  if (!items.length) throw new AdminError("missing_items", "لا توجد روابط للفحص.", 400);
  const results = await Promise.all(items.map((it) => checkUrlOne(it)));
  return json({ ok: true, count: results.length, max_batch: MAX_CHECK_BATCH, results });
}

async function handleSaveChecks(request, env) {
  const input = await readRequestData(request);
  const results = Array.isArray(input.results) ? input.results.slice(0, 500) : [];
  if (!results.length) return json({ ok: true, saved: 0 });
  await mutateAdminMeta(env, (meta) => {
    for (const r of results) {
      const id = cleanString(r && r.id);
      const kind = lc(r && r.kind) || "video";
      if (!id) continue;
      if (!meta.checks[id]) meta.checks[id] = {};
      meta.checks[id][kind] = {
        status: cleanString(r.status),
        http: Number(r.http) || 0,
        ms: Number(r.ms) || 0,
        size: Number(r.size) || 0,
        type: cleanString(r.type),
        at: cleanString(r.at) || new Date().toISOString(),
      };
    }
    const keys = Object.keys(meta.checks);
    if (keys.length > 20000) for (const k of keys.slice(0, keys.length - 20000)) delete meta.checks[k];
  });
  return json({ ok: true, saved: results.length });
}

// =============================================================================
// ADMIN V2 — NOTES / META / SUBTITLE RELINK
// =============================================================================

async function handleMetaGet(request, env) {
  return json({ ok: true, ...(await readAdminMeta(env)) });
}

async function handleNoteSave(request, env) {
  const input = await readRequestData(request);
  const scope = lc(input.scope);
  const key = cleanString(input.key);
  const note = cleanString(input.note).slice(0, 2000);
  const allowed = { item: "items", season: "seasons", channel: "channels", section: "sections" };
  if (!allowed[scope] || !key) throw new AdminError("invalid_note", "نطاق الملاحظة أو مفتاحها غير صالح.", 400);
  await mutateAdminMeta(env, (meta) => {
    const bucket = meta.notes[allowed[scope]];
    if (note) bucket[key] = { text: note, at: new Date().toISOString() };
    else delete bucket[key];
  });
  return json({ ok: true, scope, key, note });
}

async function handleRelinkSubtitles(request, env) {
  const input = await readRequestData(request);
  const seriesId = cleanString(input.series_id);
  const overwrite = truthy(input.overwrite);
  const dryRun = truthy(input.dry_run);
  if (!seriesId) throw new AdminError("missing_series", "حدد المسلسل.", 400);

  const document = await readJsonFile(env, MOVIES_OBJECT, []);
  const list = getWritableMoviesArray(document).list;
  const header = list.find((x) => x && isSeriesHeader(x) && (cleanString(x.series_id) === seriesId || cleanString(x.id) === seriesId));
  if (!header) throw new AdminError("series_not_found", "المسلسل غير موجود.", 404);
  let slug = cleanString(input.slug) || cleanString(header.slug);
  if (!slug) for (const e of episodesOf(list, header)) { slug = inferSlugFromUrl(e.video || e.link); if (slug) break; }
  if (!slug) throw new AdminError("missing_slug", "تعذر معرفة مجلد المسلسل في R2. أدخل slug يدوياً.", 400);
  if (!/^[A-Za-z0-9._-]+$/.test(slug)) throw new AdminError("invalid_slug", "اسم مجلد R2 غير صالح.", 400);

  const scan = await scanSeriesR2(env, slug);
  const stats = { slug, linked: 0, kept: 0, no_subtitle: 0, unmatched_subtitles: scan.unmatchedSubs, subtitle_files: scan.subtitleCount };

  const apply = (eps, tracker) => {
    const changed = [];
    for (const ep of eps) {
      const key = numOf(ep.season) + ":" + numOf(ep.episode);
      const subKey = scan.subsByEpisode.get(key);
      if (!subKey) { stats.no_subtitle++; continue; }
      const url = r2PublicUrl(subKey);
      if (cleanString(ep.subtitle) && !overwrite) { stats.kept++; continue; }
      if (cleanString(ep.subtitle) === url) { stats.kept++; continue; }
      if (tracker) tracker.touch(ep);
      ep.subtitle = url;
      stats.linked++;
      changed.push(cleanString(ep.id));
    }
    return changed;
  };

  if (dryRun) {
    const copy = clone(list);
    const h = copy.find((x) => cleanString(x.id) === cleanString(header.id));
    apply(episodesOf(copy, h), null);
    return json({ ok: true, dry_run: true, ...stats });
  }

  stats.linked = 0; stats.kept = 0; stats.no_subtitle = 0;
  const res = await mutateMovies(env, (liveList, holder, ctx) => {
    stats.linked = 0; stats.kept = 0; stats.no_subtitle = 0;
    const h = liveList.find((x) => x && cleanString(x.id) === cleanString(header.id));
    if (!h) throw new AdminError("series_not_found", "المسلسل لم يعد موجوداً.", 404);
    apply(episodesOf(liveList, h), ctx.tracker);
    return { label: "ربط ملفات الترجمة: " + cleanString(header.title) };
  }, { op: "relink-subtitles" });
  return json({ ok: true, dry_run: false, ...stats, undo_id: res.undo_id });
}

// =============================================================================
// ADMIN V2 — MAINTENANCE / ANNOUNCEMENTS / CHANNEL OVERLAY
// =============================================================================

function normalizeMaintenance(doc) {
  const d = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};
  return {
    enabled: d.enabled === true,
    title: cleanString(d.title),
    message: cleanString(d.message),
    eta: cleanString(d.eta),
    allow_users: d.allow_users === true,
    allow_admins: d.allow_admins !== false,
    admin_emails: Array.isArray(d.admin_emails) ? d.admin_emails.map(cleanString).filter(Boolean) : [],
    updated_at: d.updated_at || null,
    version: Number(d.version) || 0,
  };
}

async function handleMaintenanceGet(request, env) {
  const doc = await readJsonFile(env, MAINTENANCE_OBJECT, null);
  return json({ ok: true, now: new Date().toISOString(), ...normalizeMaintenance(doc) });
}

async function handleMaintenanceSet(request, env) {
  const input = await readRequestData(request);
  const current = await r2ReadJson(env.MOVIES_BUCKET, MAINTENANCE_OBJECT, null);
  const old = normalizeMaintenance(current.doc);
  const next = normalizeMaintenance({
    enabled: input.enabled === undefined ? old.enabled : truthy(input.enabled),
    title: input.title === undefined ? old.title : input.title,
    message: input.message === undefined ? old.message : input.message,
    eta: input.eta === undefined ? old.eta : input.eta,
    allow_users: input.allow_users === undefined ? old.allow_users : truthy(input.allow_users),
    allow_admins: input.allow_admins === undefined ? old.allow_admins : truthy(input.allow_admins),
    admin_emails: input.admin_emails === undefined ? old.admin_emails : input.admin_emails,
  });
  if (next.enabled && !next.message) {
    next.message = "التطبيق قيد الصيانة حالياً. سنعود قريباً.";
  }
  if (next.enabled && !next.title) next.title = "صيانة مؤقتة";
  next.updated_at = new Date().toISOString();
  next.version = old.version + 1;
  const ok = await r2WriteJsonIf(env.MOVIES_BUCKET, MAINTENANCE_OBJECT, next, current.etag);
  if (!ok) throw new AdminError("write_conflict", "تغيّرت إعدادات الصيانة أثناء الحفظ. أعد المحاولة.", 409);
  const undoId = await saveUndoRecord(env, next.enabled ? "تفعيل وضع الصيانة" : "تعديل/إيقاف وضع الصيانة", "maintenance", [], [
    { object: MAINTENANCE_OBJECT, before: current.exists ? current.doc : null },
  ]);
  return json({ ok: true, ...next, undo_id: undoId });
}

function normalizeAnnouncement(raw) {
  const a = raw && typeof raw === "object" ? raw : {};
  const mode = lc(a.show_mode);
  return {
    id: cleanString(a.id),
    title: cleanString(a.title),
    body: cleanString(a.body),
    start_at: cleanString(a.start_at),
    end_at: cleanString(a.end_at),
    show_mode: mode === "always" ? "always" : "once",
    dismissible: a.dismissible === false ? false : true,
    priority: Number.isFinite(Number(a.priority)) ? Number(a.priority) : 0,
    enabled: a.enabled === false ? false : true,
    kind: ["info", "warning", "success"].includes(lc(a.kind)) ? lc(a.kind) : "info",
    rev: Number(a.rev) || 1,
    created_at: cleanString(a.created_at),
    updated_at: cleanString(a.updated_at),
  };
}

function announcementActive(a, nowMs) {
  if (!a.enabled) return false;
  const start = a.start_at ? Date.parse(a.start_at) : NaN;
  const end = a.end_at ? Date.parse(a.end_at) : NaN;
  if (Number.isFinite(start) && start > nowMs) return false;
  if (Number.isFinite(end) && end < nowMs) return false;
  return true;
}

function normalizeAnnouncementsDoc(doc) {
  const d = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};
  return {
    version: 1,
    updatedAt: d.updatedAt || null,
    items: (Array.isArray(d.items) ? d.items : []).map(normalizeAnnouncement).filter((x) => x.id),
    archive: (Array.isArray(d.archive) ? d.archive : []).map(normalizeAnnouncement).filter((x) => x.id).slice(0, 30),
  };
}

async function handleAnnouncementsPublic(request, env) {
  const doc = normalizeAnnouncementsDoc(await readJsonFile(env, ANNOUNCEMENTS_OBJECT, null));
  const now = Date.now();
  const active = doc.items
    .filter((a) => announcementActive(a, now))
    .sort((a, b) => b.priority - a.priority || Date.parse(b.created_at || 0) - Date.parse(a.created_at || 0));
  return json({ ok: true, now: new Date(now).toISOString(), updatedAt: doc.updatedAt, items: active });
}

async function handleAnnouncementsAdmin(request, env) {
  const doc = normalizeAnnouncementsDoc(await readJsonFile(env, ANNOUNCEMENTS_OBJECT, null));
  const now = Date.now();
  return json({
    ok: true,
    now: new Date(now).toISOString(),
    items: doc.items.map((a) => ({ ...a, active: announcementActive(a, now) })),
    archive: doc.archive,
  });
}

async function handleAnnouncementSave(request, env) {
  const input = await readRequestData(request);
  const incoming = normalizeAnnouncement(input.announcement || input);
  if (!incoming.title && !incoming.body) throw new AdminError("empty_announcement", "اكتب عنواناً أو نصاً للرسالة.", 400);
  for (const key of ["start_at", "end_at"]) {
    if (incoming[key] && !Number.isFinite(Date.parse(incoming[key]))) {
      throw new AdminError("invalid_date", "صيغة التاريخ غير صالحة (" + key + "). استخدم ISO مثل 2026-10-05T12:00:00Z", 400);
    }
  }
  if (incoming.start_at && incoming.end_at && Date.parse(incoming.end_at) < Date.parse(incoming.start_at)) {
    throw new AdminError("invalid_range", "تاريخ النهاية قبل تاريخ البداية.", 400);
  }
  /** @type {any} */
  let saved;
  const before = await r2ReadJson(env.MOVIES_BUCKET, ANNOUNCEMENTS_OBJECT, null);
  await mutateJson(env.MOVIES_BUCKET, ANNOUNCEMENTS_OBJECT, { version: 1, items: [], archive: [] }, (draft) => {
    const doc = normalizeAnnouncementsDoc(draft);
    const now = new Date().toISOString();
    const idx = doc.items.findIndex((x) => x.id === incoming.id);
    if (idx >= 0) {
      const old = doc.items[idx];
      const contentChanged = old.title !== incoming.title || old.body !== incoming.body;
      saved = { ...incoming, created_at: old.created_at, updated_at: now, rev: contentChanged ? old.rev + 1 : old.rev };
      doc.items[idx] = saved;
    } else {
      saved = { ...incoming, id: incoming.id || "ann_" + Date.now().toString(36) + randomId(), created_at: now, updated_at: now, rev: 1 };
      doc.items.unshift(saved);
    }
    doc.updatedAt = now;
    return { doc };
  });
  const undoId = await saveUndoRecord(env, "حفظ رسالة: " + (saved.title || saved.body.slice(0, 30)), "announcement", [], [
    { object: ANNOUNCEMENTS_OBJECT, before: before.exists ? before.doc : null },
  ]);
  return json({ ok: true, announcement: saved, undo_id: undoId });
}

async function handleAnnouncementDelete(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.id);
  if (!id) throw new AdminError("missing_id", "معرّف الرسالة مطلوب.", 400);
  const before = await r2ReadJson(env.MOVIES_BUCKET, ANNOUNCEMENTS_OBJECT, null);
  let found = false;
  await mutateJson(env.MOVIES_BUCKET, ANNOUNCEMENTS_OBJECT, { version: 1, items: [], archive: [] }, (draft) => {
    const doc = normalizeAnnouncementsDoc(draft);
    const idx = doc.items.findIndex((x) => x.id === id);
    if (idx < 0) return { doc };
    found = true;
    const [removed] = doc.items.splice(idx, 1);
    doc.archive.unshift({ ...removed, enabled: false });
    doc.archive = doc.archive.slice(0, 30);
    doc.updatedAt = new Date().toISOString();
    return { doc };
  });
  if (!found) throw new AdminError("not_found", "الرسالة غير موجودة.", 404);
  const undoId = await saveUndoRecord(env, "حذف رسالة عامة", "announcement-delete", [], [
    { object: ANNOUNCEMENTS_OBJECT, before: before.exists ? before.doc : null },
  ]);
  return json({ ok: true, undo_id: undoId });
}

// ---- channels overlay (the channels API itself lives in another Worker) -----

const CHANNEL_FIELDS = new Set(["order", "hidden", "online", "backup_url", "note", "last_check_at", "last_check_status", "section"]);

function publicChannelsConfig(meta) {
  const channels = {};
  for (const [key, c] of Object.entries(meta.channels || {})) {
    channels[key] = {
      order: Number.isFinite(Number(c.order)) ? Number(c.order) : undefined,
      hidden: c.hidden === true,
      online: c.online === true ? true : c.online === false ? false : null,
      backup_url: cleanString(c.backup_url),
      last_check_at: cleanString(c.last_check_at),
    };
  }
  return { version: 1, updatedAt: new Date().toISOString(), channels };
}

async function handleChannelMetaGet(request, env) {
  const meta = await readAdminMeta(env);
  return json({ ok: true, channels: meta.channels });
}

async function handleChannelsConfigPublic(request, env) {
  return returnR2JsonObject(env, CHANNELS_CONFIG_OBJECT, { version: 1, channels: {} });
}

async function handleChannelMetaSave(request, env) {
  const input = await readRequestData(request);
  const key = cleanString(input.key);
  if (!key) throw new AdminError("missing_key", "مفتاح القناة مطلوب.", 400);
  const entries = Array.isArray(input.entries) ? input.entries : [{ key, fields: input.fields }];
  const res = await mutateAdminMeta(env, (meta) => {
    for (const e of entries) {
      const k = cleanString(e && e.key);
      if (!k || !e.fields || typeof e.fields !== "object") continue;
      const cur = meta.channels[k] || {};
      for (const [field, value] of Object.entries(e.fields)) {
        if (!CHANNEL_FIELDS.has(field)) continue;
        if (field === "hidden") cur.hidden = truthy(value);
        else if (field === "online") cur.online = value === null ? null : truthy(value);
        else if (field === "order") cur.order = Number.isFinite(Number(value)) ? Number(value) : 0;
        else if (field === "backup_url") {
          const v = cleanString(value);
          if (v && !/^https?:\/\//i.test(v)) throw new AdminError("invalid_url", "الرابط الاحتياطي غير صالح.", 400);
          cur.backup_url = v;
        } else cur[field] = cleanString(value).slice(0, 2000);
      }
      meta.channels[k] = cur;
    }
    return publicChannelsConfig(meta);
  });
  await r2WriteJsonIf(env.MOVIES_BUCKET, CHANNELS_CONFIG_OBJECT, res.result, null);
  return json({ ok: true, saved: entries.length });
}

// =============================================================================
// ADMIN V3 — CONTENT CMS (TMDB search, duplicates, series header, whole season,
// carousel scheduling, recent additions). Every handler returns the unified shape
// { ok, success, message, data } and errors carry { success:false, error_code }.
// =============================================================================

const ERROR_CODE_MAP = {
  episode_exists: "DUPLICATE_EPISODE",
  slug_in_use: "DUPLICATE_SLUG",
  id_in_use: "DUPLICATE_ID",
  duplicate_content: "DUPLICATE_CONTENT",
  duplicate_series: "DUPLICATE_SERIES",
  duplicate_carousel: "DUPLICATE_CAROUSEL",
  unauthorized: "UNAUTHORIZED",
  not_found: "NOT_FOUND",
  write_conflict: "WRITE_CONFLICT",
  internal_error: "INTERNAL_ERROR",
};

function errorCodeOf(code) {
  const c = cleanString(code);
  return ERROR_CODE_MAP[c] || c.toUpperCase();
}

function okResponse(data, message, extra) {
  return json(
    Object.assign(
      { ok: true, success: true, message: message || "", data: data === undefined ? {} : data },
      extra || {}
    )
  );
}

/** Adds success:true to a successful admin JSON response (old handlers only set ok:true). */
async function withSuccessFlag(response) {
  try {
    if (!response || response.status >= 400) return response;
    const type = response.headers.get("content-type") || "";
    if (!type.includes("json")) return response;
    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      return new Response(text, { status: response.status, headers: response.headers });
    }
    if (body && typeof body === "object" && !Array.isArray(body) && body.ok === true && body.success === undefined) {
      body.success = true;
      return json(body, response.status);
    }
    return new Response(text, { status: response.status, headers: response.headers });
  } catch {
    return response;
  }
}

function slugifyLatin(text) {
  return cleanString(text)
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/&/g, " and ")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60)
    .replace(/-+$/g, "");
}

function suggestSlug(list, titleOriginal, titleFallback, year, tmdb, kind) {
  let base = slugifyLatin(titleOriginal) || slugifyLatin(titleFallback);
  if (!base) base = (kind === "series" ? "series-" : "movie-") + (tmdb || Date.now().toString(36));
  if (kind !== "series" && year) base = base + "-" + year;
  const owners = buildSlugOwners(list);
  return nextFreeSlug(base, (c) => owners.has(c) || RESERVED_SLUGS.has(c));
}

function briefOf(item) {
  return {
    id: cleanString(item.id),
    title: cleanString(item.title),
    year: cleanString(item.year),
    poster: cleanString(item.poster || item.series_poster),
    type: itemKind(item),
    slug: cleanString(item.slug),
    section_name: cleanString(item.section_name),
  };
}

async function readMoviesList(env) {
  let last = null;
  for (let i = 0; i < 3; i++) {
    try {
      const obj = await env.MOVIES_BUCKET.get(MOVIES_OBJECT);
      if (!obj) return [];
      const document = await obj.json();
      return getWritableMoviesArray(document).list.filter((x) => x && typeof x === "object");
    } catch (error) {
      last = error;
      await sleep(200 * (i + 1));
    }
  }
  throw new AdminError("storage_busy", "تعذرت قراءة قاعدة المحتوى الآن (" + cleanString(last && last.message).slice(0, 80) + "). أعد المحاولة بعد ثوانٍ؛ لم يتغير أي شيء.", 503);
}

function imdbOfItem(item) {
  const a = cleanString(item.imdb_id).match(/tt\d{5,12}/i);
  if (a) return a[0].toLowerCase();
  const b = cleanString(item.imdb).match(/^tt\d{5,12}$/i);
  return b ? b[0].toLowerCase() : "";
}

function yearOfText(value) {
  const m = cleanString(value).match(/(19|20)\d{2}/);
  return m ? m[0] : "";
}

/** Existing movies / series headers that match by TMDB, IMDb, slug or title+year. */
function findDuplicates(list, q) {
  const wantedKind = lc(q.type) === "series" ? "series" : "movie";
  const tmdbMatch = cleanString(q.tmdb).match(/\d+/);
  const tmdb = tmdbMatch ? tmdbMatch[0] : "";
  const imdbMatch = cleanString(q.imdb || q.imdb_id).match(/tt\d{5,12}/i);
  const imdb = imdbMatch ? imdbMatch[0].toLowerCase() : "";
  const slug = lc(q.slug);
  const nt = normalizeTitleForMatch(q.title);
  const year = yearOfText(q.year);
  const exclude = cleanString(q.exclude_id);
  const owners = slug ? buildSlugOwners(list) : null;
  const slugOwner = owners ? owners.get(slug) : null;
  const out = [];
  for (const item of list) {
    if (!item || isEpisodeItem(item)) continue;
    const id = cleanString(item.id);
    if (exclude && id === exclude) continue;
    const kind = itemKind(item);
    const reasons = [];
    if (tmdb && kind === wantedKind && tmdbIdOf(item) === tmdb) reasons.push("tmdb");
    if (imdb && kind === wantedKind && imdbOfItem(item) === imdb) reasons.push("imdb");
    if (slugOwner && slugOwner.id === id) reasons.push("slug");
    if (nt && year && kind === wantedKind && normalizeTitleForMatch(item.title) === nt && yearOfText(item.year) === year) {
      reasons.push("title_year");
    }
    if (reasons.length) out.push(Object.assign(briefOf(item), { reasons, reason: reasons[0] }));
  }
  out.sort((a, b) => b.reasons.length - a.reasons.length);
  return out;
}

const DUP_REASON_TEXT = {
  tmdb: "نفس معرّف TMDB",
  imdb: "نفس معرّف IMDb",
  slug: "نفس الـ Slug",
  title_year: "نفس الاسم والسنة",
};

async function handleDuplicatesCheck(request, env) {
  const input = await readRequestData(request);
  const list = await readMoviesList(env);
  const matches = findDuplicates(list, input);
  for (const m of matches) m.reason_text = m.reasons.map((r) => DUP_REASON_TEXT[r] || r).join(" + ");
  return okResponse({ duplicate: matches.length > 0, matches }, matches.length ? "المحتوى موجود بالفعل." : "لا يوجد تكرار.");
}

// -----------------------------------------------------------------------------
// TMDB search + details
// -----------------------------------------------------------------------------

async function tmdbGetOrNull(env, path, params) {
  try {
    return await tmdbGet(env, path, params, true);
  } catch (error) {
    if (error instanceof SeriesImportError && error.error === "series_not_found") return null;
    throw error;
  }
}

function tmdbCard(row, type) {
  const isTv = type === "series";
  const date = cleanString(isTv ? row.first_air_date : row.release_date);
  return {
    tmdb_id: Number(row.id) || 0,
    type,
    title: cleanString(isTv ? row.name : row.title) || cleanString(isTv ? row.original_name : row.original_title),
    original_title: cleanString(isTv ? row.original_name : row.original_title),
    year: yearOfText(date),
    poster: tmdbImage(row.poster_path, "w342"),
    overview: cleanString(row.overview).slice(0, 240),
    rating: Number(Number(row.vote_average || 0).toFixed(1)),
    popularity: Math.round(Number(row.popularity) || 0),
  };
}

async function handleTmdbSearch(request, env) {
  const input = await readRequestData(request);
  let type = lc(input.type) === "series" ? "series" : "movie";
  const raw = cleanString(input.query || input.q);
  if (!raw || (raw.length < 2 && !/^\d+$/.test(raw))) {
    throw new AdminError("query_too_short", "اكتب حرفين على الأقل للبحث.", 400);
  }
  let mode = "name";
  let rows = [];
  const imdb = extractImdbId(raw);
  const urlMatch = raw.match(/themoviedb\.org\/(movie|tv)\/(\d+)/i);
  if (imdb) {
    mode = "imdb";
    const found = await tmdbGet(env, "/find/" + imdb, { external_source: "imdb_id", language: "ar-SA" });
    const movies = Array.isArray(found.movie_results) ? found.movie_results : [];
    const shows = Array.isArray(found.tv_results) ? found.tv_results : [];
    if (type === "series") {
      if (shows.length || !movies.length) rows = shows;
      else {
        type = "movie";
        rows = movies;
      }
    } else if (movies.length || !shows.length) {
      rows = movies;
    } else {
      type = "series";
      rows = shows;
    }
  } else if (urlMatch || /^\d+$/.test(raw)) {
    mode = "id";
    if (urlMatch) type = lc(urlMatch[1]) === "tv" ? "series" : "movie";
    const id = urlMatch ? urlMatch[2] : raw;
    const detail = await tmdbGetOrNull(env, "/" + (type === "series" ? "tv" : "movie") + "/" + id, { language: "ar-SA" });
    if (detail) rows = [detail];
  } else {
    const path = type === "series" ? "/search/tv" : "/search/movie";
    const ar = await tmdbGet(env, path, { query: raw, language: "ar-SA", include_adult: "false", page: 1 });
    rows = Array.isArray(ar.results) ? ar.results : [];
    if (!rows.length) {
      const en = await tmdbGet(env, path, { query: raw, language: "en-US", include_adult: "false", page: 1 });
      rows = Array.isArray(en.results) ? en.results : [];
    }
  }
  const cards = rows.slice(0, 15).map((r) => tmdbCard(r, type)).filter((c) => c.tmdb_id > 0);
  const list = await readMoviesList(env);
  for (const card of cards) {
    const hit = findDuplicates(list, { type, tmdb: card.tmdb_id, title: card.title, year: card.year }).filter(
      (m) => m.reasons.includes("tmdb") || m.reasons.includes("title_year")
    )[0];
    if (hit) card.existing = hit;
  }
  return okResponse(
    { mode, type, results: cards },
    cards.length ? "" : "لا توجد نتائج مطابقة. جرّب اسماً آخر أو معرّف TMDB / IMDb."
  );
}

function genreNameList(ar, en) {
  const enById = new Map((Array.isArray(en && en.genres) ? en.genres : []).map((g) => [Number(g.id), cleanString(g.name)]));
  const out = [];
  const seen = new Set();
  const source = Array.isArray(ar && ar.genres) && ar.genres.length ? ar.genres : Array.isArray(en && en.genres) ? en.genres : [];
  for (const g of source) {
    const name = cleanString(g.name) || enById.get(Number(g.id)) || "";
    if (name && !seen.has(name)) {
      seen.add(name);
      out.push({ id: Number(g.id) || 0, name, en: enById.get(Number(g.id)) || "" });
    }
  }
  return out;
}

function suggestSections(registry, genres, kind) {
  const hits = [];
  for (const s of registry.sections) {
    if (s.enabled === false) continue;
    if (s.type && s.type !== "mixed" && s.type !== kind) continue;
    const sn = normalizeTitleForMatch(s.name);
    const sl = lc(s.slug || s.id);
    let reason = "";
    for (const g of genres) {
      const gn = normalizeTitleForMatch(g.name);
      const ge = lc(g.en);
      if ((sn && gn && (sn.includes(gn) || gn.includes(sn))) || (ge && sl && (sl.includes(ge.replace(/\s+/g, "-")) || ge.includes(sl)))) {
        reason = g.name;
        break;
      }
    }
    if (reason) hits.push({ id: s.id, name: s.name, reason });
  }
  return hits.slice(0, 4);
}

async function handleTmdbDetails(request, env) {
  const input = await readRequestData(request);
  const type = lc(input.type) === "series" ? "series" : "movie";
  const idMatch = cleanString(input.tmdb_id || input.tmdb).match(/\d+/);
  if (!idMatch) throw new AdminError("missing_tmdb", "معرّف TMDB مطلوب.", 400);
  const id = idMatch[0];
  const path = (type === "series" ? "/tv/" : "/movie/") + id;
  const [ar, en] = await Promise.all([
    tmdbGet(env, path, { language: "ar-SA", append_to_response: "external_ids" }, true),
    tmdbGet(env, path, { language: "en-US", append_to_response: "external_ids" }, true),
  ]);
  const title = pickText(ar, en, type === "series" ? "name" : "title") || "";
  const original = pickText(en, ar, type === "series" ? "original_name" : "original_title");
  const date = pickText(ar, en, type === "series" ? "first_air_date" : "release_date");
  const year = yearOfText(date);
  const genres = genreNameList(ar, en);
  const imdbRaw = cleanString((ar.external_ids && ar.external_ids.imdb_id) || ar.imdb_id || (en.external_ids && en.external_ids.imdb_id) || en.imdb_id);
  const runtime = type === "series"
    ? Number((Array.isArray(ar.episode_run_time) ? ar.episode_run_time[0] : 0) || (Array.isArray(en.episode_run_time) ? en.episode_run_time[0] : 0)) || 0
    : pickNumber(ar, en, "runtime");
  const rating = Number(Number(pickNumber(ar, en, "vote_average") || 0).toFixed(1));
  const list = await readMoviesList(env);
  const registry = await readSectionsRegistry(env, list);
  const data = {
    type,
    tmdb_id: Number(id),
    title,
    original_title: original,
    description: pickText(ar, en, "overview"),
    year,
    release_date: date,
    imdb_id: extractImdbId(imdbRaw),
    rating,
    rating_text: rating > 0 ? String(rating) : "",
    genres: genres.map((g) => g.name),
    category: genres.map((g) => g.name).join("، "),
    poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"),
    backdrop: tmdbImage(pickText(ar, en, "backdrop_path"), "w1280"),
    duration: durationText(runtime),
    runtime_minutes: runtime,
    suggested_sections: suggestSections(registry, genres, type),
    suggested_slug: suggestSlug(list, original, title, year, id, type),
    sections: registry.sections.map((s) => ({ id: s.id, name: s.name, type: s.type })),
  };
  if (type === "series") {
    data.season_count = Number(ar.number_of_seasons || en.number_of_seasons) || 0;
    data.seasons = []
      .concat(Array.isArray(ar.seasons) && ar.seasons.length ? ar.seasons : Array.isArray(en.seasons) ? en.seasons : [])
      .filter((s) => Number(s.season_number) > 0)
      .map((s) => ({
        season: Number(s.season_number),
        name: cleanString(s.name),
        episode_count: Number(s.episode_count) || 0,
        poster: tmdbImage(s.poster_path, "w342"),
        air_date: cleanString(s.air_date),
      }));
  }
  data.duplicates = findDuplicates(list, { type, tmdb: id, imdb: data.imdb_id, title, year });
  return okResponse(data);
}

// -----------------------------------------------------------------------------
// Series header (created once; episodes relate through series_id)
// -----------------------------------------------------------------------------

async function handleSeriesCreate(request, env) {
  const input = await readRequestData(request);
  const idMatch = cleanString(input.tmdb_id || input.tmdb).match(/\d+/);
  if (!idMatch) throw new AdminError("missing_tmdb", "اختر المسلسل من نتائج TMDB أولاً.", 400);
  const tmdbId = idMatch[0];
  const [ar, en] = await Promise.all([
    tmdbGet(env, "/tv/" + tmdbId, { language: "ar-SA", append_to_response: "external_ids" }, true),
    tmdbGet(env, "/tv/" + tmdbId, { language: "en-US", append_to_response: "external_ids" }, true),
  ]);
  const registry = await readSectionsRegistry(env);
  const sectionId = cleanString(input.section_id);
  const sectionName = cleanString(input.section_name) || (sectionId ? lookupSectionName(registry, sectionId) : "");
  const title = cleanString(input.title) || pickText(ar, en, "name") || "مسلسل";
  const original = cleanString(input.original_title) || pickText(en, ar, "original_name");
  const year = cleanString(input.year) || yearOfText(pickText(ar, en, "first_air_date"));
  const rating = Number(Number(pickNumber(ar, en, "vote_average") || 0).toFixed(1));
  const poster = cleanString(input.poster) || tmdbImage(pickText(ar, en, "poster_path"), "w500");
  const backdrop = cleanString(input.backdrop) || tmdbImage(pickText(ar, en, "backdrop_path"), "w1280");
  const imdbId =
    extractImdbId(cleanString(input.imdb_id)) ||
    extractImdbId(cleanString((ar.external_ids && ar.external_ids.imdb_id) || (en.external_ids && en.external_ids.imdb_id)));
  const seriesId = "series_" + tmdbId;
  const slugWanted = cleanString(input.slug);

  const res = await mutateMovies(
    env,
    (list, holder, ctx) => {
      const existing =
        findById(list, seriesId) ||
        list.find((x) => x && isSeriesHeader(x) && tmdbIdOf(x) === tmdbId);
      if (existing) {
        throw new AdminError(
          "duplicate_series",
          "هذا المسلسل موجود بالفعل («" + cleanString(existing.title) + "»). أضف الحلقات إليه بدل إنشاء نسخة جديدة.",
          409,
          { existing: briefOf(existing) }
        );
      }
      let slug = slugWanted;
      if (slug) slugConflict(list, slug, "");
      else slug = suggestSlug(list, original, title, year, tmdbId, "series");
      const now = Date.now();
      const item = {
        id: seriesId,
        type: "series",
        created_at: new Date(now).toISOString(),
        created_at_ms: now,
        section_id: sectionId,
        section_name: sectionName,
        series_id: seriesId,
        season: "",
        episode: "",
        title,
        poster,
        series_poster: poster,
        season_poster: "",
        backdrop,
        video: "",
        link: "",
        year,
        category: cleanString(input.category) || genreNameList(ar, en).map((g) => g.name).join("، "),
        imdb: rating > 0 ? String(rating) : "",
        description: cleanString(input.description) || pickText(ar, en, "overview"),
        duration: "",
        slug,
        tmdb: tmdbId,
      };
      if (imdbId) item.imdb_id = imdbId;
      if (original) item.original_title = original;
      if (cleanString(input.quality)) item.quality = cleanString(input.quality);
      if (truthy(input.hidden)) item.hidden = true;
      list.unshift(item);
      ctx.tracker.add(item);
      sortNewestFirst(list);
      return { result: clone(item), label: "إضافة مسلسل: " + title };
    },
    { op: "add-series-header" }
  );
  let r2Exists = false;
  try {
    const page = await env.MOVIES_BUCKET.list({ prefix: res.result.slug + "/", limit: 1 });
    r2Exists = Array.isArray(page.objects) && page.objects.length > 0;
  } catch {
    r2Exists = false;
  }
  return okResponse({ item: res.result, id: res.result.id, undo_id: res.undo_id, r2_exists: r2Exists }, "تم إنشاء المسلسل. أضف الحلقات أو موسماً كاملاً الآن.");
}

// -----------------------------------------------------------------------------
// R2 folder listing + file-name analysis
// -----------------------------------------------------------------------------

function slugForSeries(list, header, fallback) {
  const wanted = cleanString(fallback);
  if (wanted) return wanted;
  if (cleanString(header.slug)) return cleanString(header.slug);
  const hid = cleanString(header.id);
  for (const [slug, owner] of buildSlugOwners(list)) {
    if (owner.id === hid) return slug;
  }
  return "";
}

function findSeriesHeader(list, seriesId) {
  const sid = cleanString(seriesId);
  if (!sid) return null;
  return list.find((x) => x && isSeriesHeader(x) && (cleanString(x.id) === sid || cleanString(x.series_id) === sid)) || null;
}

async function handleParseNames(request, env) {
  const input = await readRequestData(request);
  const names = Array.isArray(input.names) ? input.names.slice(0, 300) : [];
  const rows = names.map((n) => {
    const name = cleanString(n);
    const kind = /\.(srt|vtt)$/i.test(name) ? "subtitle" : /\.(mp4|mkv|m4v|webm|mov|m3u8)$/i.test(name) ? "video" : "other";
    const parsed = parseAnyEpisodeKey(name, "");
    return { name, kind, season: parsed ? parsed.season : null, episode: parsed ? parsed.episode : null };
  });
  return okResponse({ files: rows });
}

async function handleR2List(request, env) {
  const input = await readRequestData(request);
  const list = await readMoviesList(env);
  let slug = cleanString(input.slug);
  const header = findSeriesHeader(list, input.series_id);
  if (!slug && header) slug = slugForSeries(list, header, "");
  if (!slug) throw new AdminError("missing_slug", "لا يوجد مجلد R2 مرتبط بهذا المسلسل. أدخل اسم المجلد (Slug).", 400);
  if (!validSlugFormat(slug)) throw new AdminError("invalid_slug", "اسم المجلد غير صالح.", 400);
  const scan = await scanSeriesR2(env, slug);
  const rows = [];
  const seasonFilter = numOf(input.season);
  const keys = new Set([...scan.byEpisode.keys(), ...scan.subsByEpisode.keys()]);
  for (const k of keys) {
    const parts = k.split(":");
    const season = Number(parts[0]);
    const episode = Number(parts[1]);
    if (seasonFilter && season !== seasonFilter) continue;
    const v = scan.byEpisode.get(k) || "";
    const s = scan.subsByEpisode.get(k) || "";
    const existing = header ? episodeCollision(list, seriesKeyOf(header), season, episode, "") : null;
    rows.push({
      season,
      episode,
      video: v ? r2PublicUrl(v) : "",
      video_name: v ? v.split("/").pop() : "",
      subtitle: s ? r2PublicUrl(s) : "",
      subtitle_name: s ? s.split("/").pop() : "",
      exists: Boolean(existing),
      existing_id: existing ? cleanString(existing.id) : "",
    });
  }
  rows.sort((a, b) => a.season - b.season || a.episode - b.episode);
  return okResponse({
    slug,
    folder_exists: scan.videoCount + scan.subtitleCount > 0,
    files: rows,
    unmatched: scan.unmatched.map((k) => k.split("/").pop()),
    unmatched_subtitles: scan.unmatchedSubs.map((k) => k.split("/").pop()),
    unmatched_subtitle_files: scan.unmatchedSubs.map((k) => ({ name: k.split("/").pop(), url: r2PublicUrl(k) })),
    unmatched_video_files: scan.unmatched.map((k) => ({ name: k.split("/").pop(), url: r2PublicUrl(k) })),
    video_count: scan.videoCount,
    subtitle_count: scan.subtitleCount,
  });
}

// -----------------------------------------------------------------------------
// Whole season (preview + commit)
// -----------------------------------------------------------------------------

/** TMDB tv id of a series header: stored tmdb / series_<id>, else found through its IMDb id (tt...). */
async function resolveTmdbTvId(env, header) {
  const direct = tmdbIdOf(header);
  if (direct) return direct;
  const imdb = extractImdbId(cleanString(header.imdb_id)) || imdbIdOf(header);
  if (!imdb) return "";
  try {
    const found = await tmdbGetOrNull(env, "/find/" + imdb, { external_source: "imdb_id" });
    const hit = found && Array.isArray(found.tv_results) ? found.tv_results[0] : null;
    return hit && hit.id ? String(hit.id) : "";
  } catch {
    return "";
  }
}

/** Portrait poster of one season from TMDB ("" when TMDB has none or is unreachable). */
async function seasonPosterFor(env, header, season) {
  try {
    const id = await resolveTmdbTvId(env, header);
    if (!id || !season) return "";
    const info = await tmdbSeasonInfo(env, id, season);
    return cleanString(info && info.poster);
  } catch {
    return "";
  }
}

async function tmdbSeasonInfo(env, tmdbId, season) {
  if (!tmdbId) return { poster: "", episodes: new Map(), ok: false };
  try {
    const [ar, en] = await Promise.all([
      tmdbGetOrNull(env, "/tv/" + tmdbId + "/season/" + season, { language: "ar-SA" }),
      tmdbGetOrNull(env, "/tv/" + tmdbId + "/season/" + season, { language: "en-US" }),
    ]);
    if (!ar && !en) return { poster: "", episodes: new Map(), ok: false };
    const arEps = Array.isArray(ar && ar.episodes) ? ar.episodes : [];
    const enEps = Array.isArray(en && en.episodes) ? en.episodes : [];
    const map = new Map();
    const numbers = new Set();
    for (const e of arEps.concat(enEps)) {
      const n = Number(e && e.episode_number);
      if (Number.isInteger(n) && n > 0) numbers.add(n);
    }
    for (const n of numbers) {
      const a = arEps.find((e) => Number(e.episode_number) === n) || {};
      const b = enEps.find((e) => Number(e.episode_number) === n) || {};
      map.set(n, {
        title: pickText(a, b, "name"),
        overview: pickText(a, b, "overview"),
        thumb: tmdbImage(pickText(a, b, "still_path"), "w780"),
        year: yearOfText(pickText(a, b, "air_date")),
        duration: durationText(pickNumber(a, b, "runtime")),
      });
    }
    return { poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"), episodes: map, ok: true };
  } catch (error) {
    return { poster: "", episodes: new Map(), ok: false, error: error && error.message };
  }
}

async function handleSeasonPreview(request, env) {
  const input = await readRequestData(request);
  const season = numOf(input.season);
  if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
  const list = await readMoviesList(env);
  const header = findSeriesHeader(list, input.series_id);
  if (!header) throw new AdminError("series_not_found", "المسلسل غير موجود.", 404);
  const wantVid = truthy(input.vidbolt);
  const slug = slugForSeries(list, header, input.slug);
  const slugOk = Boolean(slug) && validSlugFormat(slug);
  if (!wantVid) {
    if (!slug) throw new AdminError("missing_slug", "لا يوجد مجلد R2 لهذا المسلسل. أدخل اسم المجلد (Slug).", 400);
    if (!slugOk) throw new AdminError("invalid_slug", "اسم المجلد غير صالح.", 400);
  }
  const sid = seriesKeyOf(header);
  const warnings = [];
  const emptyScan = { videoCount: 0, byEpisode: new Map(), unmatched: [], subtitleCount: 0, subsByEpisode: new Map(), unmatchedSubs: [] };
  const tmdbId = vidBoltTmdbId(input.tmdb) || (await resolveTmdbTvId(env, header));
  const [scan, tm] = await Promise.all([
    slugOk ? scanSeriesR2(env, slug).catch(() => { warnings.push("تعذر قراءة مجلد R2 الآن."); return emptyScan; }) : Promise.resolve(emptyScan),
    tmdbSeasonInfo(env, tmdbId, season),
  ]);
  const noFiles = scan.videoCount + scan.subtitleCount === 0;
  // VidBolt: a season with no files of its own is built from TMDB (every episode of the season gets its player link)
  const vidMode = Boolean(tmdbId) && (wantVid || noFiles);
  const eps = new Set();
  for (const k of scan.byEpisode.keys()) {
    const p = k.split(":");
    if (Number(p[0]) === season) eps.add(Number(p[1]));
  }
  for (const k of scan.subsByEpisode.keys()) {
    const p = k.split(":");
    if (Number(p[0]) === season) eps.add(Number(p[1]));
  }
  const maxFound = eps.size ? Math.max(...eps) : 0;
  const expected = tm.ok ? tm.episodes.size : 0;
  if (vidMode && tm.ok) for (const n of tm.episodes.keys()) eps.add(n);
  const upper = eps.size ? Math.max(...eps) : 0;
  for (let n = 1; n <= upper; n++) eps.add(n); // gaps are shown as missing rows
  const rows = [];
  const counts = { total: 0, new: 0, update: 0, missing: 0, no_subtitle: 0, ready: 0 };
  // one pass over the list for this series (not one scan of the whole list per episode)
  const mine = new Map();
  for (const x of list) {
    if (x && isEpisodeItem(x) && cleanString(x.series_id) === sid && numOf(x.season) === season) mine.set(numOf(x.episode), x);
  }
  for (const n of Array.from(eps).sort((a, b) => a - b)) {
    const key = season + ":" + n;
    const vKey = scan.byEpisode.get(key) || "";
    const sKey = scan.subsByEpisode.get(key) || "";
    const meta = tm.episodes.get(n) || {};
    const existing = mine.get(n) || null;
    const vidUrl = vidMode && !vKey ? "https://vidbolt.pro/tv/" + tmdbId + "/" + season + "/" + n : "";
    let state = "new";
    if (!vKey && !vidUrl && !existing) state = "missing";
    else if (existing) state = "exists";
    const row = {
      ep: n,
      title: meta.title || "الحلقة " + n,
      video: vKey ? r2PublicUrl(vKey) : vidUrl,
      video_name: vKey ? vKey.split("/").pop() : "",
      subtitle: sKey ? r2PublicUrl(sKey) : "",
      subtitle_name: sKey ? sKey.split("/").pop() : "",
      thumb: meta.thumb || "",
      overview: meta.overview || "",
      duration: meta.duration || "",
      year: meta.year || "",
      state,
      existing_id: existing ? cleanString(existing.id) : "",
      existing_video: existing ? cleanString(existing.video || existing.link) : "",
      existing_subtitle: existing ? cleanString(existing.subtitle) : "",
    };
    if (state === "exists") {
      const sameVideo = !row.video || row.video === row.existing_video;
      const sameSub = !row.subtitle || row.subtitle === row.existing_subtitle;
      row.changed = !(sameVideo && sameSub);
    }
    counts.total++;
    if (state === "new") counts.new++;
    else if (state === "exists") counts.update++;
    else counts.missing++;
    if (state !== "missing" && !(row.subtitle || row.existing_subtitle)) counts.no_subtitle++;
    rows.push(row);
    if (state === "missing") warnings.push("الحلقة " + n + " غير موجودة في المجلد.");
  }
  counts.ready = counts.new + counts.update;
  if (!vidMode && tm.ok && expected && maxFound && maxFound < expected) {
    warnings.push("TMDB يذكر " + expected + " حلقات للموسم، والموجود في المجلد حتى الحلقة " + maxFound + " فقط.");
  }
  if (scan.unmatched.length) warnings.push(scan.unmatched.length + " ملف فيديو لم يُعرف رقم حلقته (لن يُستخدم).");
  if (scan.unmatchedSubs.length) warnings.push(scan.unmatchedSubs.length + " ملف ترجمة لم يُربط بحلقة.");
  if (!tm.ok) warnings.push("تعذر جلب أسماء الحلقات من TMDB؛ ستُستخدم أسماء عامة.");
  if (vidMode && !rows.length) warnings.push("TMDB لا يذكر حلقات لهذا الموسم بعد.");
  return okResponse({
    series: { id: sid, title: cleanString(header.title), poster: cleanString(header.poster), slug, tmdb: tmdbId || tmdbIdOf(header) },
    season,
    season_poster: tm.poster,
    expected_episodes: expected,
    rows,
    counts,
    warnings,
    unmatched: scan.unmatched.map((k) => k.split("/").pop()),
    unmatched_subtitles: scan.unmatchedSubs.map((k) => k.split("/").pop()),
    folder_exists: scan.videoCount + scan.subtitleCount > 0 || (vidMode && rows.length > 0),
    vidbolt: vidMode,
    tmdb: tmdbId,
  });
}

function episodeInheritance(list, header, season) {
  const siblings = episodesOf(list, header).filter((e) => numOf(e.season) === season);
  siblings.sort((a, b) => numOf(b.episode) - numOf(a.episode));
  const latest = siblings[0] || null;
  return {
    section_id: header.section_id,
    section_name: header.section_name,
    series_poster: header.series_poster || header.poster,
    category: header.category,
    year: header.year,
    imdb: header.imdb,
    tmdb: header.tmdb || header.tmdb_id,
    tmdb_id: header.tmdb_id || header.tmdb,
    backdrop: header.backdrop,
    quality: latest ? latest.quality : header.quality,
    season_poster: latest ? latest.season_poster || header.poster : header.poster,
    poster: latest ? latest.season_poster || header.poster : header.poster,
  };
}

async function handleSeasonCommit(request, env) {
  const input = await readRequestData(request);
  const season = numOf(input.season);
  if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
  const rows = Array.isArray(input.episodes) ? input.episodes : [];
  if (!rows.length) throw new AdminError("nothing_to_publish", "لا توجد حلقات للنشر.", 400);
  if (rows.length > 400) throw new AdminError("too_many", "عدد الحلقات كبير جداً في عملية واحدة.", 400);
  const updateExisting = input.update_existing !== false;
  const seasonPoster = cleanString(input.season_poster);
  if (seasonPoster && !/^https?:\/\//i.test(seasonPoster)) {
    throw new AdminError("invalid_url", "رابط بوستر الموسم غير صالح.", 400, { field: "season_poster" });
  }
  let added = 0;
  let updated = 0;
  let skipped = 0;
  let autoSeasonPoster = seasonPoster;
  let posterTried = Boolean(seasonPoster);
  let genHeader = null; // the header with its TMDB id resolved ONCE (not one TMDB search per episode)

  const res = await mutateMovies(
    env,
    async (list, holder, ctx) => {
      added = 0;
      updated = 0;
      skipped = 0;
      const header = findSeriesHeader(list, input.series_id);
      if (!header) throw new AdminError("series_not_found", "المسلسل غير موجود.", 404);
      if (!posterTried) {
        posterTried = true;
        try { autoSeasonPoster = await seasonPosterFor(env, header, season); } catch { autoSeasonPoster = ""; }
      }
      if (!genHeader) {
        genHeader = header;
        const hasId = vidBoltTmdbId(header.tmdb || header.tmdb_id) || vidBoltTmdbId(input.tmdb);
        if (!hasId && rows.some((r) => !cleanString(r && r.video))) {
          let found = "";
          try { found = await resolveTmdbTvId(env, header); } catch { found = ""; }
          if (found) genHeader = Object.assign({}, header, { tmdb: found, tmdb_id: found });
        } else if (!vidBoltTmdbId(header.tmdb || header.tmdb_id)) {
          genHeader = Object.assign({}, header, { tmdb: vidBoltTmdbId(input.tmdb), tmdb_id: vidBoltTmdbId(input.tmdb) });
        }
      }
      const sid = seriesKeyOf(header);
      const numeric = sid.match(/^series_(\d+)$/);
      const idBase = numeric ? numeric[1] : sid;
      const inherit = episodeInheritance(list, header, season);
      const effPoster = autoSeasonPoster || inherit.season_poster || cleanString(header.poster);
      if (effPoster) {
        inherit.season_poster = effPoster;
        inherit.poster = effPoster;
      }

      const seen = new Set();
      for (const row of rows) {
        const n = numOf(row && row.ep);
        let video = cleanString(row && row.video).replace(/vidbolt\.xyz/gi, "vidbolt.pro");
        const subtitle = cleanString(row && row.subtitle);
        if (!n || seen.has(n)) {
          skipped++;
          continue;
        }
        seen.add(n);
        for (const u of [video, subtitle, cleanString(row.thumb)]) {
          if (u && !/^https?:\/\//i.test(u)) {
            throw new AdminError("invalid_url", "رابط غير صالح في الحلقة " + n + ".", 400);
          }
        }

        const existing = episodeCollision(list, sid, season, n, "");
        if (existing) {
          if (!updateExisting) {
            skipped++;
            continue;
          }
          ctx.tracker.touch(existing);
          if (video) {
            existing.video = video;
            existing.link = video;
          } else if (!cleanString(existing.video || existing.link)) {
            await autoGenerateVidBolt(existing, env, genHeader);
          }
          const hasUsableVideo = cleanString(existing.video || existing.link);
          if (!hasUsableVideo && !subtitle) {
            skipped++;
            continue;
          }
          if (video) {
            existing.video = video;
            existing.link = video;
          }
          if (subtitle) existing.subtitle = subtitle;
          if (cleanString(row.title) && (!cleanString(existing.title) || /^الحلقة\s*\d+$/.test(cleanString(existing.title)))) existing.title = cleanString(row.title);
          if (!cleanString(existing.description) && cleanString(row.overview)) existing.description = cleanString(row.overview).slice(0, 1500);
          if (!cleanString(existing.duration) && cleanString(row.duration)) existing.duration = cleanString(row.duration);
          if (!cleanString(existing.year) && cleanString(row.year)) existing.year = cleanString(row.year);
          if (cleanString(row.thumb) && !cleanString(existing.backdrop)) existing.backdrop = cleanString(row.thumb);
          if (cleanString(row.thumb) && !cleanString(existing.thumb)) existing.thumb = cleanString(row.thumb);
          if (!cleanString(existing.series_poster)) existing.series_poster = cleanString(header.series_poster || header.poster);
          updated++;
          continue;
        }

        const now = Date.now();
        const item = {
          id: idBase + "_s" + season + "e" + n,
          type: "episode",
          created_at: new Date(now).toISOString(),
          created_at_ms: now,
          series_id: sid,
          season: String(season),
          episode: String(n),
          title: cleanString(row.title) || "الحلقة " + n,
          video,
          link: video,
          description: cleanString(row.overview).slice(0, 1500),
          duration: cleanString(row.duration),
        };
        if (subtitle) item.subtitle = subtitle;
        for (const [k, v] of Object.entries(inherit)) {
          if (v !== undefined && cleanString(v) !== "") item[k] = cleanString(v);
        }
        if (cleanString(row.thumb)) {
          item.backdrop = cleanString(row.thumb);
          item.thumb = cleanString(row.thumb);
        }
        if (cleanString(header.imdb_id)) item.imdb_id = cleanString(header.imdb_id);
        if (cleanString(row.year)) item.year = cleanString(row.year);

        // If this episode has no uploaded R2/video URL, generate the VidBolt episode embed from the series TMDB id.
        if (!cleanString(item.video || item.link)) {
          await autoGenerateVidBolt(item, env, genHeader);
        }
        if (!cleanString(item.video || item.link)) {
          skipped++;
          continue;
        }

        if (findById(list, item.id)) item.id = item.id + "_" + randomId();
        list.unshift(item);
        ctx.tracker.add(item);
        added++;
      }

      if (effPoster) {
        for (const ep of episodesOf(list, header).filter((e) => numOf(e.season) === season)) {
          ctx.tracker.touch(ep);
          if (seasonPoster || !cleanString(ep.season_poster)) ep.season_poster = effPoster;
          const pst = cleanString(ep.poster);
          if (!pst || pst === cleanString(ep.backdrop) || pst === cleanString(ep.thumb)) ep.poster = ep.season_poster || effPoster;
        }
      }
      sortNewestFirst(list);
      return {
        expectDelta: added,
        label: "نشر الموسم " + season + " — " + cleanString(header.title) + " (" + added + " جديدة، " + updated + " محدّثة)",
      };
    },
    { op: "season-commit", backup: true }
  );

  return okResponse(
    { added, updated, skipped, undo_id: res.undo_id, backup: res.backup },
    "تم نشر الموسم: " + added + " حلقة جديدة و " + updated + " محدّثة."
  );
}

// -----------------------------------------------------------------------------
// Carousel (scheduling, custom images, duplicate guard, undo)
// -----------------------------------------------------------------------------

function carouselDoc(raw) {
  if (Array.isArray(raw)) return { enabled: raw.length > 0, maxItems: null, updatedAt: null, items: raw.slice() };
  if (!raw || typeof raw !== "object") return { enabled: false, maxItems: null, updatedAt: null, items: [] };
  const doc = Object.assign({}, raw);
  doc.items = Array.isArray(raw.items) ? raw.items.slice() : [];
  return doc;
}

function carouselIdOf(entry) {
  return cleanString(entry && (entry.content_id ?? entry.id));
}

function parseIsoOrEmpty(value, field) {
  const text = cleanString(value);
  if (!text) return "";
  const ms = Date.parse(text);
  if (!Number.isFinite(ms)) throw new AdminError("invalid_date", "تاريخ غير صالح في الحقل «" + field + "».", 400, { field });
  return new Date(ms).toISOString();
}

function carouselStatus(entry, nowMs) {
  const start = Date.parse(cleanString(entry && entry.start_at));
  const end = Date.parse(cleanString(entry && entry.end_at));
  if (Number.isFinite(start) && start > nowMs) return "scheduled";
  if (Number.isFinite(end) && end < nowMs) return "expired";
  return "active";
}

function carouselActiveItems(items, nowMs) {
  return items.filter((x) => carouselStatus(x, nowMs) === "active");
}

/** Public carousel: items outside their Start/End window are hidden. Items without dates are unaffected. */
async function carouselPublicResponse(request, env, url) {
  const doc = await readCarousel(env);
  if (url && url.searchParams.get("all") === "1") return json(doc);
  const items = Array.isArray(doc.items) ? doc.items : [];
  const active = carouselActiveItems(items, Date.now());
  if (active.length === items.length) return json(doc);
  return json(Object.assign({}, doc, { enabled: doc.enabled !== false && active.length > 0, items: active }));
}

async function r2ImageExists(env, value) {
  const key = r2CarouselKeyFromUrl(value);
  if (!key) return true; // external image (TMDB etc.) — nothing to verify in R2
  const head = await env.MOVIES_BUCKET.head(key);
  return Boolean(head);
}

async function carouselMutate(env, label, op, fn) {
  const res = await mutateJson(env.MOVIES_BUCKET, CAROUSEL_OBJECT, { enabled: false, items: [] }, async (draft, current) => {
    const doc = carouselDoc(draft);
    const out = (await fn(doc)) || {};
    doc.enabled = doc.items.length > 0;
    doc.updatedAt = new Date().toISOString();
    if (doc.maxItems === undefined) doc.maxItems = null;
    return { doc, result: out };
  });
  let before = null;
  try {
    before = res.raw ? JSON.parse(res.raw) : null;
  } catch {
    before = null;
  }
  const undoId = await saveUndoRecord(env, label, op, [], [{ object: CAROUSEL_OBJECT, before }]);
  return { doc: res.doc, result: res.result, undo_id: undoId };
}

async function handleCarouselList(request, env) {
  const doc = carouselDoc(await readJsonFile(env, CAROUSEL_OBJECT, null));
  const list = await readMoviesList(env);
  const byId = new Map(list.filter((x) => !isEpisodeItem(x)).map((x) => [cleanString(x.id), x]));
  const now = Date.now();
  const items = doc.items.map((entry, index) => {
    const id = carouselIdOf(entry);
    const content = byId.get(id) || null;
    return Object.assign({}, entry, {
      content_id: id,
      position: index + 1,
      status: carouselStatus(entry, now),
      content_exists: Boolean(content),
      content_poster: content ? cleanString(content.poster || content.series_poster) : "",
      content_backdrop: content ? cleanString(content.backdrop) : "",
      content_kind: content ? itemKind(content) : cleanString(entry.type),
    });
  });
  return okResponse({
    items,
    count: items.length,
    active: items.filter((i) => i.status === "active").length,
    updatedAt: doc.updatedAt || null,
  });
}

async function handleCarouselSave(request, env) {
  const input = await readRequestData(request);
  const contentId = cleanString(input.content_id || input.id);
  if (!contentId) throw new AdminError("missing_id", "اختر المحتوى المراد إضافته للكاروسيل.", 400);
  const list = await readMoviesList(env);
  const content = findById(list, contentId);
  if (!content && !truthy(input.allow_missing)) {
    throw new AdminError("content_not_found", "المحتوى غير موجود في قاعدة البيانات.", 404);
  }
  if (content && isEpisodeItem(content)) {
    throw new AdminError("invalid_target", "الكاروسيل يقبل الأفلام والمسلسلات فقط، وليس الحلقات.", 400);
  }
  const type = content ? (isSeriesHeader(content) ? "series" : "movie") : lc(input.type) === "series" ? "series" : "movie";
  const title = cleanString(input.title) || cleanString(content && content.title);
  if (!title) throw new AdminError("missing_title", "العنوان مطلوب.", 400);
  const sourceBackdrop = cleanString(input.source_backdrop) || cleanString(content && content.backdrop);
  const sourcePoster = cleanString(input.source_poster) || cleanString(content && (content.poster || content.series_poster));
  const landscape = cleanString(input.landscape_poster) || sourceBackdrop || sourcePoster;
  if (!/^https?:\/\//i.test(landscape)) {
    throw new AdminError("invalid_image", "لا توجد صورة عريضة صالحة لهذه الشريحة. ارفع صورة مخصصة.", 400, { field: "landscape_poster" });
  }
  if (!(await r2ImageExists(env, landscape))) {
    throw new AdminError("image_missing", "الصورة المرفوعة غير موجودة في R2. أعد رفعها ثم احفظ.", 409);
  }
  const startAt = parseIsoOrEmpty(input.start_at, "start_at");
  const endAt = parseIsoOrEmpty(input.end_at, "end_at");
  if (startAt && endAt && Date.parse(endAt) <= Date.parse(startAt)) {
    throw new AdminError("invalid_range", "تاريخ النهاية يجب أن يكون بعد تاريخ البداية.", 400);
  }
  const force = truthy(input.force);
  const edit = truthy(input.edit);
  const position = input.position;
  const res = await carouselMutate(env, "كاروسيل: " + title, "carousel-save", (doc) => {
    const idx = doc.items.findIndex((x) => carouselIdOf(x) === contentId);
    if (idx >= 0 && !force && !edit) {
      throw new AdminError("duplicate_carousel", "«" + title + "» موجود بالفعل في الكاروسيل.", 409, { existing: doc.items[idx], position: idx + 1 });
    }
    const previous = idx >= 0 ? doc.items[idx] : null;
    const item = Object.assign({}, previous || {}, {
      content_id: contentId,
      type,
      title,
      landscape_poster: landscape,
      source_poster: sourcePoster,
      source_backdrop: sourceBackdrop,
      custom_image: truthy(input.custom_image),
      publishedAt: new Date().toISOString(),
    });
    if (startAt) item.start_at = startAt;
    else delete item.start_at;
    if (endAt) item.end_at = endAt;
    else delete item.end_at;
    if (idx >= 0) doc.items.splice(idx, 1);
    let target = 0;
    if (position === "last") target = doc.items.length;
    else if (Number.isInteger(Number(position)) && position !== "" && position !== null && position !== undefined && position !== "first") {
      target = Math.min(Math.max(0, Number(position)), doc.items.length);
    } else if (edit && idx >= 0) target = Math.min(idx, doc.items.length);
    doc.items.splice(target, 0, item);
    return { item, position: target + 1, replaced: Boolean(previous) };
  });
  return okResponse(
    { item: res.result.item, position: res.result.position, count: res.doc.items.length, undo_id: res.undo_id },
    res.result.replaced ? "تم تحديث الشريحة." : "تمت إضافة الشريحة إلى الكاروسيل."
  );
}

async function handleCarouselReorder(request, env) {
  const input = await readRequestData(request);
  const order = Array.isArray(input.order) ? input.order.map(cleanString).filter(Boolean) : [];
  if (!order.length) throw new AdminError("missing_order", "الترتيب المطلوب فارغ.", 400);
  const res = await carouselMutate(env, "ترتيب الكاروسيل", "carousel-reorder", (doc) => {
    const byId = new Map(doc.items.map((x) => [carouselIdOf(x), x]));
    const used = new Set();
    const next = [];
    for (const id of order) {
      const x = byId.get(id);
      if (x && !used.has(id)) {
        next.push(x);
        used.add(id);
      }
    }
    for (const x of doc.items) if (!used.has(carouselIdOf(x))) next.push(x);
    if (next.length !== doc.items.length) throw new AdminError("unexpected_loss", "فشل الترتيب حفاظاً على الشرائح.", 500);
    doc.items = next;
    return { count: next.length };
  });
  return okResponse({ count: res.doc.items.length, undo_id: res.undo_id }, "تم حفظ ترتيب الكاروسيل.");
}

async function handleCarouselRemove(request, env) {
  const input = await readRequestData(request);
  const id = cleanString(input.content_id || input.id);
  if (!id) throw new AdminError("missing_id", "معرّف الشريحة مطلوب.", 400);
  const res = await carouselMutate(env, "إزالة من الكاروسيل: " + id, "carousel-remove", (doc) => {
    const idx = doc.items.findIndex((x) => carouselIdOf(x) === id);
    if (idx < 0) throw new AdminError("not_found", "الشريحة غير موجودة في الكاروسيل.", 404);
    doc.items.splice(idx, 1);
    return {};
  });
  return okResponse({ count: res.doc.items.length, undo_id: res.undo_id }, "تمت إزالة الشريحة (يمكن التراجع).");
}

// -----------------------------------------------------------------------------
// Recent additions
// -----------------------------------------------------------------------------

async function handleRecent(request, env, url) {
  const limit = Math.min(50, Math.max(1, Number(url.searchParams.get("limit")) || 20));
  const list = await readMoviesList(env);
  const entries = [];
  for (const item of list) {
    const at = Number(item.created_at_ms) || Date.parse(cleanString(item.created_at)) || 0;
    if (!at) continue;
    const kind = itemKind(item);
    const hasVideo = Boolean(cleanString(item.video || item.link));
    let status = "ok";
    if (kind !== "series" && !hasVideo) status = "warning";
    else if (item.hidden === true) status = "hidden";
    entries.push({
      kind,
      id: cleanString(item.id),
      title: cleanString(item.title),
      poster: cleanString(item.poster || item.series_poster),
      at_ms: at,
      status,
      series_id: cleanString(item.series_id),
      season: cleanString(item.season),
      episode: cleanString(item.episode),
    });
  }
  entries.sort((a, b) => b.at_ms - a.at_ms);
  const top = entries.slice(0, limit);
  const doc = carouselDoc(await readJsonFile(env, CAROUSEL_OBJECT, null));
  for (const c of doc.items) {
    const at = Date.parse(cleanString(c.publishedAt));
    if (!Number.isFinite(at)) continue;
    top.push({
      kind: "carousel",
      id: carouselIdOf(c),
      title: cleanString(c.title),
      poster: cleanString(c.landscape_poster),
      at_ms: at,
      status: carouselStatus(c, Date.now()),
      series_id: "",
      season: "",
      episode: "",
    });
  }
  top.sort((a, b) => b.at_ms - a.at_ms);
  return okResponse({ items: top.slice(0, limit) });
}

// =============================================================================
// UPDATE CENTER (v4) — «إدارة تحديث التطبيق»
// -----------------------------------------------------------------------------
// Draft -> upload APK -> verify -> publish / schedule -> push -> monitor ->
// stop -> history.  Everything below is ADDITIVE: GET /update, GET
// /cigram_update.json and POST /publish-update keep working for the existing
// Cigram app (all legacy fields are still written to cigram_update.json).
//
// Storage (same R2 bucket, public/private split follows the admin v2 rules):
//   cigram_update.json                       PUBLIC   live manifest read by the app
//   updates/cigram-<version>.apk             PUBLIC   uploaded APK files (never deleted)
//   <admin>/update_history.json              PRIVATE  releases (draft/scheduled/published/...) + audit
//   <admin>/update_schedule.json             PRIVATE  tiny pointer to the next scheduled release
//   <admin>/update_stats.json                PRIVATE  analytics counters sent by the app
//
// Routes (admin = ADMIN_TOKEN required):
//   GET  /update                public   live manifest (legacy, unchanged shape + new fields)
//   POST /publish-update        admin    publish now / schedule (legacy payload still accepted)
//   GET  /update/history        admin    live manifest + all releases + audit + capabilities
//   POST /update/draft          admin    save / delete a draft
//   POST /update/stop           admin    stop the live update (published=false, history kept)
//   POST /update/schedule/cancel admin   scheduled -> draft
//   POST /update/upload         admin    chunked APK upload (?action=init|part|complete|abort)
//   POST /update/validate       admin    check an APK link / R2 key (reachable? real APK? version?)
//   POST /update/emergency      admin    force_current | block_version | unblock_version | set_min | stop
//   POST /update/push           admin    resend the push notification of a published release
//   GET  /update/stats          admin    analytics summary (empty until the app sends events)
//   POST /update/event          public   analytics event from the Cigram app
//
// Scheduling needs a Cron Trigger (see README): scheduled() activates due releases
// every minute.  As a fallback GET /update also activates a due release lazily.
// =============================================================================

const UPD_HISTORY_NAME = "update_history.json";
const UPD_STATS_NAME = "update_stats.json";
const UPD_SCHEDULE_NAME = "update_schedule.json";
const UPD_PREFIX = "updates/";
const UPD_TYPES = ["optional", "important", "force", "beta"];
const UPD_PART_SIZE = 6 * 1024 * 1024; // R2 multipart: every part except the last must be equal and >= 5 MiB
const UPD_MAX_APK_BYTES = 400 * 1024 * 1024;
const UPD_MAX_CODE = 2100000000;
const UPD_AUDIT_KEEP = 300;
const UPD_MAX_DRAFTS = 50;
const UPD_MAX_DEVICES = 50000;
const UPD_EVENTS = ["shown", "update_now", "later", "download", "launch"];
const UPD_APK_MIME = "application/vnd.android.package-archive";

// -----------------------------------------------------------------------------
// small helpers
// -----------------------------------------------------------------------------

function updLog(event, data) {
  try {
    console.log(JSON.stringify(Object.assign({ scope: "update", event }, data || {})));
  } catch {
    // logging must never break a request
  }
}

function updFail(code, message, field, status, extra) {
  const info = Object.assign({}, extra || {});
  if (field) info.field = field;
  return new AdminError(code, message, status || 400, Object.keys(info).length ? info : null);
}

/** Strict integer: 25 or "25" -> 25. Anything else (25.5, "abc", "", null) -> NaN. */
function updInt(value) {
  if (typeof value === "number") return Number.isInteger(value) ? value : NaN;
  const s = cleanString(value);
  return /^-?\d{1,12}$/.test(s) ? Number(s) : NaN;
}

function updText(value, max) {
  return cleanString(value)
    .replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/g, "")
    .slice(0, max);
}

function updNewId() {
  return "r_" + Date.now().toString(36) + "_" + randomId();
}

function updPublicUrl(key) {
  return R2_PUBLIC_BASE + "/" + key.split("/").map(encodeURIComponent).join("/");
}

/** Key inside our own bucket for an APK url that lives under updates/, else "". */
function updKeyFromUrl(value) {
  const url = cleanString(value);
  if (!url.startsWith(R2_PUBLIC_BASE + "/" + UPD_PREFIX)) return "";
  try {
    const u = new URL(url);
    const path = decodeURIComponent(u.pathname.replace(/^\/+/, ""));
    return path.startsWith(UPD_PREFIX) && !path.includes("..") ? path : "";
  } catch {
    return "";
  }
}

async function updFetch(url, init, ms) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), ms || 8000);
  try {
    return await fetch(url, Object.assign({}, init || {}, { signal: controller.signal }));
  } finally {
    clearTimeout(timer);
  }
}

function updSafeExternalUrl(value) {
  let u;
  try {
    u = new URL(value);
  } catch {
    return null;
  }
  if (u.protocol !== "https:" && u.protocol !== "http:") return null;
  const h = u.hostname.toLowerCase();
  if (!h.includes(".") && !h.includes(":")) return null;
  if (/^(localhost|.*\.local|.*\.internal)$/.test(h)) return null;
  if (/^(127\.|10\.|0\.|169\.254\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(h)) return null;
  if (h === "::1" || h.startsWith("[")) return null;
  return u;
}

async function updHashHex(text, length) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  const bytes = new Uint8Array(digest);
  let out = "";
  for (let i = 0; i < bytes.length && out.length < length; i++) out += bytes[i].toString(16).padStart(2, "0");
  return out.slice(0, length);
}

// -----------------------------------------------------------------------------
// history document (private)
// -----------------------------------------------------------------------------

function updEmptyHistory() {
  return { version: 1, releases: [], audit: [] };
}

function updNormHistory(doc) {
  const d = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};
  return {
    version: 1,
    releases: Array.isArray(d.releases) ? d.releases.filter((r) => r && typeof r === "object") : [],
    audit: Array.isArray(d.audit) ? d.audit.filter((a) => a && typeof a === "object") : [],
  };
}

async function updReadHistory(env) {
  const current = await r2ReadJson(adminBucket(env), adminKey(env, UPD_HISTORY_NAME), updEmptyHistory());
  return { doc: updNormHistory(current.doc), etag: current.etag, raw: current.raw };
}

function updMutateHistory(env, fn) {
  return mutateJson(adminBucket(env), adminKey(env, UPD_HISTORY_NAME), updEmptyHistory(), async (draft) => {
    const doc = updNormHistory(draft);
    const result = await fn(doc);
    return { doc, result };
  });
}

async function updRevertHistory(env, raw) {
  try {
    const bucket = adminBucket(env);
    const key = adminKey(env, UPD_HISTORY_NAME);
    if (raw === null || raw === undefined) await bucket.delete(key);
    else await r2WriteJsonIf(bucket, key, JSON.parse(raw), null);
  } catch (error) {
    updLog("history_revert_failed", { message: String(error && error.message) });
  }
}

async function updReadLive(env) {
  const doc = await readJsonFile(env, UPDATE_OBJECT, null);
  if (!doc || typeof doc !== "object" || Array.isArray(doc) || doc.ok === false) return null;
  if (!Number.isFinite(Number(doc.versionCode))) return null;
  return doc;
}

function updAudit(doc, action, rel, detail) {
  doc.audit.push({
    at: new Date().toISOString(),
    action,
    releaseId: rel ? rel.id : "",
    versionCode: rel ? rel.versionCode : 0,
    versionName: rel ? rel.versionName : "",
    detail: updText(detail || "", 300),
  });
  if (doc.audit.length > UPD_AUDIT_KEEP) doc.audit.splice(0, doc.audit.length - UPD_AUDIT_KEEP);
}

/** First time we see an update published by the OLD system: keep it in the history. */
function updSeedFromLive(doc, live) {
  if (!live || doc.releases.length) return false;
  const code = Number(live.versionCode) || 0;
  if (code <= 0) return false;
  const now = new Date().toISOString();
  const url = cleanString(live.downloadUrl || live.url);
  const type = lc(live.updateType);
  doc.releases.push({
    id: cleanString(live.releaseId) || "r_legacy_" + code,
    status: live.published === false ? "stopped" : "published",
    versionName: cleanString(live.versionName),
    versionCode: code,
    minVersionCode: Math.max(0, Number(live.minVersionCode) || 0),
    updateType: UPD_TYPES.includes(type) ? type : "optional",
    title: cleanString(live.title),
    description: cleanString(live.description || live.message),
    changelog: Array.isArray(live.changelog) ? live.changelog.map(cleanString).filter(Boolean) : [],
    downloadUrl: url,
    apkKey: updKeyFromUrl(url),
    apkSource: url ? (updKeyFromUrl(url) ? "r2" : "external") : "",
    fileSize: Math.max(0, Number(live.fileSize) || 0),
    sha256: "",
    packageName: cleanString(live.packageName),
    apkVersionCode: 0,
    apkVersionName: "",
    sendPush: live.sendPush === true,
    pushTitle: "",
    pushBody: "",
    push: { status: "none" },
    maintenanceDuring: false,
    maintenanceAfter: false,
    scheduledAt: "",
    createdAt: cleanString(live.publishedAt) || now,
    updatedAt: now,
    publishedAt: cleanString(live.publishedAt),
    stoppedAt: live.published === false ? cleanString(live.stoppedAt) || now : "",
    source: "legacy",
  });
  return true;
}

function updMaxCode(doc, live) {
  let max = live ? Number(live.versionCode) || 0 : 0;
  for (const r of doc.releases) {
    if (r.status === "published" || r.status === "superseded" || r.status === "stopped") {
      max = Math.max(max, Number(r.versionCode) || 0);
    }
  }
  return max;
}

function updKnownPackage(doc) {
  let best = null;
  for (const r of doc.releases) {
    if (!cleanString(r.packageName)) continue;
    if (r.status !== "published" && r.status !== "superseded" && r.status !== "stopped") continue;
    if (!best || Number(r.versionCode) > Number(best.versionCode)) best = r;
  }
  return best ? cleanString(best.packageName) : "";
}

// -----------------------------------------------------------------------------
// input sanitising / validation
// -----------------------------------------------------------------------------

function updChangelogOf(value, strict) {
  let arr = [];
  if (Array.isArray(value)) arr = value;
  else if (typeof value === "string") arr = value.split(/\r?\n/);
  const out = [];
  for (const item of arr) {
    const t = updText(item, 600)
      .replace(/^[\s•*\-–]+/, "")
      .trim();
    if (!t) continue;
    if (t.length > 200) {
      if (strict) throw updFail("INVALID_CHANGELOG", "أحد بنود «ما الجديد» أطول من 200 حرف. اختصره.", "changelog");
      out.push(t.slice(0, 200));
      continue;
    }
    out.push(t);
  }
  if (out.length > 30) {
    if (strict) throw updFail("INVALID_CHANGELOG", "عدد بنود «ما الجديد» يتجاوز 30 بنداً.", "changelog");
    out.length = 30;
  }
  return out;
}

function updDateOf(value) {
  const s = cleanString(value);
  if (!s) return "";
  const ms = Date.parse(s);
  return Number.isFinite(ms) ? new Date(ms).toISOString() : "";
}

function updFlag(input, flat, nestedKey) {
  if (input[flat] !== undefined) return truthy(input[flat]);
  const m = input.maintenance;
  if (m && typeof m === "object" && m[nestedKey] !== undefined) return truthy(m[nestedKey]);
  return false;
}

/**
 * Turns untrusted input into the editable release fields.
 * strict=true (publish / schedule) throws a clear Arabic error per field;
 * strict=false (draft) keeps whatever is valid and never throws on content.
 */
function updSanitizeFields(input, strict, opts) {
  const o = opts || {};
  const f = {};

  f.versionName = updText(input.versionName, 60);
  if (strict) {
    if (!f.versionName) throw updFail("MISSING_VERSION_NAME", "اسم الإصدار مطلوب (مثال: 2.5.0).", "versionName");
    if (!/^[0-9A-Za-z][0-9A-Za-z._+\-]{0,39}$/.test(f.versionName)) {
      throw updFail("INVALID_VERSION_NAME", "اسم الإصدار غير صالح. استخدم أرقاماً وحروفاً ونقاطاً فقط (مثال: 2.5.0).", "versionName");
    }
  }

  const code = updInt(input.versionCode);
  if (strict) {
    if (!Number.isFinite(code)) {
      throw updFail("INVALID_VERSION_CODE", "رقم الإصدار (Version Code) غير صالح. أدخل رقماً صحيحاً أكبر من صفر.", "versionCode");
    }
    if (code === 0) throw updFail("INVALID_VERSION_CODE", "رقم الإصدار (Version Code) لا يمكن أن يكون صفراً.", "versionCode");
    if (code < 0 || code > UPD_MAX_CODE) throw updFail("INVALID_VERSION_CODE", "رقم الإصدار (Version Code) خارج النطاق المسموح.", "versionCode");
  }
  f.versionCode = Number.isFinite(code) && code > 0 && code <= UPD_MAX_CODE ? code : 0;

  const rawMin = input.minVersionCode;
  const minEmpty = rawMin === undefined || rawMin === null || cleanString(rawMin) === "";
  const min = minEmpty ? 0 : updInt(rawMin);
  if (strict && (!Number.isFinite(min) || min < 0 || min > UPD_MAX_CODE)) {
    throw updFail("INVALID_MIN_VERSION", "الحد الأدنى المدعوم (minVersionCode) غير صالح. أدخل رقماً صحيحاً أو اتركه 0.", "minVersionCode");
  }
  f.minVersionCode = Number.isFinite(min) && min >= 0 && min <= UPD_MAX_CODE ? min : 0;
  if (strict && f.minVersionCode > f.versionCode) {
    throw updFail(
      "MIN_VERSION_TOO_HIGH",
      "الحد الأدنى المدعوم (" + f.minVersionCode + ") لا يمكن أن يكون أعلى من رقم الإصدار الجديد (" + f.versionCode + ").",
      "minVersionCode"
    );
  }

  const type = lc(input.updateType || input.type) || "optional";
  if (UPD_TYPES.includes(type)) f.updateType = type;
  else if (strict) throw updFail("INVALID_UPDATE_TYPE", "نوع التحديث غير معروف.", "updateType");
  else f.updateType = "optional";

  f.title = updText(input.title, 120);
  f.description = updText(input.description !== undefined ? input.description : input.message, 600);
  if (strict) {
    if (!f.title) throw updFail("MISSING_TITLE", "عنوان التحديث مطلوب.", "title");
    if (!f.description) throw updFail("MISSING_MESSAGE", "الوصف المختصر مطلوب.", "description");
  }
  f.changelog = updChangelogOf(input.changelog, strict);

  f.downloadUrl = updText(input.downloadUrl || input.url, 1000).trim();
  if (strict) {
    if (!f.downloadUrl) {
      if (f.updateType === "force") throw updFail("FORCE_WITHOUT_APK", "التحديث الإجباري يتطلب رابط APK صالحاً.", "downloadUrl");
      throw updFail("MISSING_DOWNLOAD_URL", "رابط تحميل APK مطلوب. ارفع الملف أو أدخل رابطاً خارجياً.", "downloadUrl");
    }
    let u = null;
    try {
      u = new URL(f.downloadUrl);
    } catch {
      u = null;
    }
    const okProto = u && (u.protocol === "https:" || (o.allowHttp && u.protocol === "http:"));
    if (!okProto || !u.hostname.includes(".")) {
      throw updFail("INVALID_DOWNLOAD_URL", "رابط التحميل غير صالح. يجب أن يبدأ بـ https:// ويشير إلى ملف APK.", "downloadUrl");
    }
  }
  f.apkKey = updKeyFromUrl(f.downloadUrl);
  f.apkSource = f.downloadUrl ? (f.apkKey ? "r2" : "external") : "";
  const size = updInt(input.fileSize);
  f.fileSize = Number.isFinite(size) && size > 0 ? size : 0;
  const sha = cleanString(input.sha256).toLowerCase();
  f.sha256 = /^[0-9a-f]{64}$/.test(sha) ? sha : "";
  f.packageName = updText(input.packageName, 150);
  const avc = updInt(input.apkVersionCode);
  f.apkVersionCode = Number.isFinite(avc) && avc > 0 ? avc : 0;
  f.apkVersionName = updText(input.apkVersionName, 60);

  f.sendPush = truthy(input.sendPush);
  f.pushTitle = updText(input.pushTitle, 100);
  f.pushBody = updText(input.pushBody, 300);
  if (f.sendPush) {
    if (!f.pushTitle) f.pushTitle = f.title || "تحديث جديد لسيكرام";
    if (!f.pushBody) f.pushBody = "الإصدار " + (f.versionName || "الجديد") + " أصبح متوفراً الآن.";
  }

  f.maintenanceDuring = updFlag(input, "maintenanceDuring", "enableDuring");
  f.maintenanceAfter = updFlag(input, "maintenanceAfter", "disableAfter");
  if (strict && f.maintenanceDuring && f.maintenanceAfter) {
    throw updFail("CONFLICTING_MAINTENANCE", "لا يمكن تفعيل الصيانة وإلغاؤها في نفس عملية النشر. اختر أحد الخيارين.", "maintenance");
  }

  f.scheduledAt = updDateOf(input.scheduledAt);

  if (strict && f.updateType === "force" && f.minVersionCode < 1) {
    throw updFail(
      "FORCE_WITHOUT_MIN",
      "التحديث الإجباري يحتاج حدّاً أدنى للإصدار (minVersionCode) أكبر من صفر، وإلا فلن يُجبَر أي مستخدم.",
      "minVersionCode"
    );
  }
  return f;
}

/** Version rules shared by publish / schedule / re-check inside the history lock. */
function updCheckVersion(doc, live, f, existing) {
  const max = updMaxCode(doc, live);
  const liveCode = live ? Number(live.versionCode) || 0 : 0;
  const reactivation =
    Boolean(existing) &&
    existing.status === "stopped" &&
    Boolean(live) &&
    live.published === false &&
    Number(existing.versionCode) === liveCode &&
    f.versionCode === liveCode &&
    (!live.releaseId || live.releaseId === existing.id);
  if (reactivation) return { max, reactivation: true };
  // v10: الإدارة تقدر تنشر أي رقم إصدار حتى لو كان أقل من المنشور (مثلاً 18.0.0 برقم 70 بعد 83). الممنوع فقط تكرار الرقم المنشور.
  // ملاحظة: الهواتف المثبّت عليها رقم أعلى لن يُعرض عليها هذا التحديث (التطبيق يقارن الرقم الجديد بالمثبّت).
  if (f.versionCode === max) {
    throw updFail(
      "VERSION_EXISTS",
      "رقم الإصدار (" + f.versionCode + ") منشور بالفعل. استخدم رقماً أكبر من " + max + ".",
      "versionCode",
      409,
      { currentVersionCode: max }
    );
  }
  return { max, reactivation: false };
}

function updCheckSchedule(doc, existing, f) {
  const ms = Date.parse(f.scheduledAt);
  if (!f.scheduledAt || !Number.isFinite(ms)) {
    throw updFail("INVALID_SCHEDULE", "حدّد تاريخ ووقت النشر المجدول.", "scheduledAt");
  }
  if (ms < Date.now() + 60 * 1000) {
    throw updFail("INVALID_SCHEDULE", "موعد الجدولة يجب أن يكون في المستقبل (بعد دقيقة على الأقل).", "scheduledAt");
  }
  if (ms > Date.now() + 400 * 24 * 3600 * 1000) {
    throw updFail("INVALID_SCHEDULE", "موعد الجدولة بعيد جداً (الحد الأقصى 400 يوم).", "scheduledAt");
  }
  const other = doc.releases.find((r) => r.status === "scheduled" && (!existing || r.id !== existing.id));
  if (other) {
    throw updFail(
      "SCHEDULE_CONFLICT",
      "يوجد تحديث مجدول بالفعل (" + other.versionName + "). ألغِ جدولته أو عدّله قبل جدولة تحديث آخر.",
      "scheduledAt",
      409,
      { scheduledReleaseId: other.id }
    );
  }
}

// -----------------------------------------------------------------------------
// APK inspection: zip central directory + binary AndroidManifest.xml (no libraries)
// -----------------------------------------------------------------------------

function updR2Reader(bucket, key, size) {
  return {
    size,
    async read(offset, length) {
      if (length <= 0) return new Uint8Array(0);
      const obj = await bucket.get(key, { range: { offset, length } });
      if (!obj) throw new Error("range_read_failed");
      return new Uint8Array(await obj.arrayBuffer());
    },
  };
}

function updHttpReader(url, size) {
  return {
    size,
    async read(offset, length) {
      if (length <= 0) return new Uint8Array(0);
      const res = await updFetch(url, { headers: { Range: "bytes=" + offset + "-" + (offset + length - 1) } }, 9000);
      if (res.status !== 206) {
        try {
          if (res.body) await res.body.cancel();
        } catch {
          // ignore
        }
        throw new Error("range_not_supported");
      }
      return new Uint8Array(await res.arrayBuffer());
    },
  };
}

async function updInflateRaw(bytes) {
  const stream = new Response(bytes).body.pipeThrough(new DecompressionStream("deflate-raw"));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

function updReadAxmlString(dv, bytes, p, utf8) {
  if (utf8) {
    let len = bytes[p++];
    if (len & 0x80) len = ((len & 0x7f) << 8) | bytes[p++];
    let blen = bytes[p++];
    if (blen & 0x80) blen = ((blen & 0x7f) << 8) | bytes[p++];
    return new TextDecoder("utf-8").decode(bytes.subarray(p, p + blen));
  }
  let len = dv.getUint16(p, true);
  p += 2;
  if (len & 0x8000) {
    len = ((len & 0x7fff) << 16) | dv.getUint16(p, true);
    p += 2;
  }
  let out = "";
  for (let i = 0; i < len; i++) out += String.fromCharCode(dv.getUint16(p + i * 2, true));
  return out;
}

/** Reads package / versionCode / versionName from a compiled (binary) AndroidManifest.xml. */
function updParseAxml(bytes) {
  try {
    const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    if (bytes.length < 16 || dv.getUint16(0, true) !== 0x0003) return null;
    const total = Math.min(dv.getUint32(4, true), bytes.length);
    let pos = dv.getUint16(2, true);
    let strings = [];
    const resIds = [];
    while (pos + 8 <= total) {
      const type = dv.getUint16(pos, true);
      const hsize = dv.getUint16(pos + 2, true);
      const size = dv.getUint32(pos + 4, true);
      if (size < 8 || pos + size > bytes.length) break;
      if (type === 0x0001) {
        const count = dv.getUint32(pos + 8, true);
        const flags = dv.getUint32(pos + 16, true);
        const stringsStart = dv.getUint32(pos + 20, true);
        const utf8 = (flags & 0x100) !== 0;
        strings = [];
        for (let i = 0; i < count; i++) {
          const off = dv.getUint32(pos + hsize + i * 4, true);
          strings.push(updReadAxmlString(dv, bytes, pos + stringsStart + off, utf8));
        }
      } else if (type === 0x0180) {
        for (let i = pos + hsize; i + 4 <= pos + size; i += 4) resIds.push(dv.getUint32(i, true));
      } else if (type === 0x0102) {
        const ext = pos + hsize;
        const elementName = strings[dv.getUint32(ext + 4, true)] || "";
        if (elementName !== "manifest") return null;
        const attrStart = dv.getUint16(ext + 8, true);
        const attrSize = dv.getUint16(ext + 10, true);
        const attrCount = dv.getUint16(ext + 12, true);
        const out = { packageName: "", versionCode: 0, versionName: "" };
        for (let k = 0; k < attrCount; k++) {
          const a = ext + attrStart + k * attrSize;
          const nameIdx = dv.getUint32(a + 4, true);
          const rawIdx = dv.getUint32(a + 8, true);
          const dataType = dv.getUint8(a + 15);
          const data = dv.getUint32(a + 16, true);
          const resId = nameIdx < resIds.length ? resIds[nameIdx] : 0;
          let name = strings[nameIdx] || "";
          if (resId === 0x0101021b) name = "versionCode";
          else if (resId === 0x0101021c) name = "versionName";
          let value;
          if (dataType === 0x03) value = strings[rawIdx !== 0xffffffff ? rawIdx : data] || "";
          else if (dataType === 0x10 || dataType === 0x11) value = data;
          else continue;
          if (name === "package") out.packageName = String(value);
          else if (name === "versionCode") out.versionCode = Number(value) || 0;
          else if (name === "versionName") out.versionName = String(value);
        }
        return out;
      }
      pos += size;
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * Inspects an APK through a range reader (an object with a numeric size and an
 * async read(offset, length) method that resolves to a Uint8Array).
 * Resolves to an object with the fields isZip, valid, hasManifest, hasDex, info
 * (parsed manifest data or null) and reason.
 * @param {any} reader
 * @returns {Promise<any>}
 */
async function updInspectApk(reader) {
  const size = reader.size;
  if (!(size >= 22)) return { isZip: false, valid: false, hasManifest: false, hasDex: false, info: null, reason: "too_small" };
  const head = await reader.read(0, 4);
  const pk = head.length >= 4 && head[0] === 0x50 && head[1] === 0x4b && (head[2] === 3 || head[2] === 5) && (head[3] === 4 || head[3] === 6);
  if (!pk) return { isZip: false, valid: false, hasManifest: false, hasDex: false, info: null, reason: "not_zip" };

  const tailLen = Math.min(size, 65557);
  const tail = await reader.read(size - tailLen, tailLen);
  let eocd = -1;
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 5 && tail[i + 3] === 6) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) return { isZip: true, valid: false, hasManifest: false, hasDex: false, info: null, reason: "no_eocd" };
  const tdv = new DataView(tail.buffer, tail.byteOffset, tail.byteLength);
  const cdSize = tdv.getUint32(eocd + 12, true);
  const cdOffset = tdv.getUint32(eocd + 16, true);
  if (cdOffset === 0xffffffff || cdSize === 0xffffffff) {
    return { isZip: true, valid: true, hasManifest: false, hasDex: false, info: null, reason: "zip64" };
  }
  if (cdSize <= 0 || cdSize > 32 * 1024 * 1024 || cdOffset + cdSize > size) {
    return { isZip: true, valid: false, hasManifest: false, hasDex: false, info: null, reason: "bad_directory" };
  }
  const cd = await reader.read(cdOffset, cdSize);
  const cdv = new DataView(cd.buffer, cd.byteOffset, cd.byteLength);
  let p = 0;
  let manifestEntry = null;
  let hasDex = false;
  const decoder = new TextDecoder("utf-8");
  while (p + 46 <= cd.length && cdv.getUint32(p, true) === 0x02014b50) {
    const method = cdv.getUint16(p + 10, true);
    const compSize = cdv.getUint32(p + 20, true);
    const nameLen = cdv.getUint16(p + 28, true);
    const extraLen = cdv.getUint16(p + 30, true);
    const commentLen = cdv.getUint16(p + 32, true);
    const lho = cdv.getUint32(p + 42, true);
    const name = decoder.decode(cd.subarray(p + 46, p + 46 + nameLen));
    if (name === "AndroidManifest.xml") manifestEntry = { method, compSize, lho };
    else if (/^classes\d*\.dex$/.test(name)) hasDex = true;
    p += 46 + nameLen + extraLen + commentLen;
  }
  if (!manifestEntry) {
    return { isZip: true, valid: true, hasManifest: false, hasDex, info: null, reason: "no_manifest" };
  }
  let info = null;
  try {
    if (manifestEntry.compSize > 0 && manifestEntry.compSize <= 4 * 1024 * 1024) {
      const lh = await reader.read(manifestEntry.lho, 30);
      const ldv = new DataView(lh.buffer, lh.byteOffset, lh.byteLength);
      if (ldv.getUint32(0, true) === 0x04034b50) {
        const start = manifestEntry.lho + 30 + ldv.getUint16(26, true) + ldv.getUint16(28, true);
        const raw = await reader.read(start, manifestEntry.compSize);
        let data = null;
        if (manifestEntry.method === 0) data = raw;
        else if (manifestEntry.method === 8) data = await updInflateRaw(raw);
        if (data) info = updParseAxml(data);
      }
    }
  } catch {
    info = null;
  }
  return { isZip: true, valid: true, hasManifest: true, hasDex, info, reason: info ? "" : "manifest_unreadable" };
}

/**
 * Looks at an APK link (our R2 or external) and reports what it is.
 * Never throws for an unreachable / wrong link: it describes the problem instead.
 */
async function updInspectUrl(env, url) {
  const out = {
    hosted: "external",
    reachable: false,
    status: 0,
    size: 0,
    contentType: "",
    isApk: false,
    hasManifest: false,
    apkInfo: null,
    rangeOk: false,
    reason: "",
  };
  const key = updKeyFromUrl(url);
  if (key) {
    out.hosted = "r2";
    const head = await env.MOVIES_BUCKET.head(key);
    if (!head) {
      out.reason = "not_found";
      return out;
    }
    out.reachable = true;
    out.status = 200;
    out.size = head.size || 0;
    out.rangeOk = true;
    out.contentType = (head.httpMetadata && head.httpMetadata.contentType) || "";
    if (!(out.size > 0)) {
      out.reason = "empty";
      return out;
    }
    let ins;
    try {
      ins = await updInspectApk(updR2Reader(env.MOVIES_BUCKET, key, out.size));
    } catch {
      ins = { isZip: false, valid: false, hasManifest: false, info: null, reason: "read_failed" };
    }
    out.isApk = ins.isZip && ins.valid && ins.hasManifest;
    out.hasManifest = ins.hasManifest;
    out.apkInfo = ins.info;
    out.reason = out.isApk ? (ins.info ? "" : "manifest_unreadable") : ins.reason || "not_apk";
    return out;
  }

  const safe = updSafeExternalUrl(url);
  if (!safe) {
    out.reason = "unsafe_url";
    return out;
  }
  let res;
  try {
    res = await updFetch(safe.toString(), { method: "GET", headers: { Range: "bytes=0-3" }, redirect: "follow" }, 9000);
  } catch (error) {
    out.reason = error && error.name === "AbortError" ? "timeout" : "network";
    return out;
  }
  out.status = res.status;
  out.contentType = res.headers.get("content-type") || "";
  if (res.status !== 200 && res.status !== 206) {
    try {
      if (res.body) await res.body.cancel();
    } catch {
      // ignore
    }
    out.reason = "http_" + res.status;
    return out;
  }
  out.reachable = true;
  let first = new Uint8Array(0);
  try {
    if (res.status === 206) {
      first = new Uint8Array(await res.arrayBuffer());
      const range = res.headers.get("content-range") || "";
      const m = /\/(\d+)\s*$/.exec(range);
      if (m) out.size = Number(m[1]);
      out.rangeOk = true;
    } else {
      out.size = Number(res.headers.get("content-length")) || 0;
      const reader = res.body.getReader();
      const part = await reader.read();
      first = part && part.value ? part.value.slice(0, 4) : new Uint8Array(0);
      try {
        await reader.cancel();
      } catch {
        // ignore
      }
    }
  } catch {
    out.reason = "read_failed";
    return out;
  }
  const pk = first.length >= 4 && first[0] === 0x50 && first[1] === 0x4b && (first[2] === 3 || first[2] === 5);
  if (!pk) {
    out.reason = /html|json|text/i.test(out.contentType) ? "web_page" : "not_zip";
    return out;
  }
  out.isApk = true;
  if (out.rangeOk && out.size > 22) {
    try {
      const ins = await updInspectApk(updHttpReader(safe.toString(), out.size));
      if (ins.isZip && ins.valid && !ins.hasManifest && ins.reason === "no_manifest") {
        out.isApk = false;
        out.reason = "no_manifest";
      }
      out.hasManifest = ins.hasManifest;
      out.apkInfo = ins.info;
    } catch {
      out.reason = "inspect_unavailable";
    }
  } else {
    out.reason = "inspect_unavailable";
  }
  return out;
}

/** Server-side truth about the APK. Mutates `f` (size, package, apk version) and returns warnings. */
async function updVerifyApk(env, f, doc) {
  const warnings = [];
  const r = await updInspectUrl(env, f.downloadUrl);
  if (!r.reachable) {
    if (r.reason === "not_found") {
      throw updFail("APK_NOT_FOUND", "ملف APK غير موجود في R2. ارفعه من جديد ثم أعد المحاولة.", "downloadUrl");
    }
    if (r.reason === "unsafe_url") {
      throw updFail("INVALID_DOWNLOAD_URL", "رابط التحميل غير مسموح به.", "downloadUrl");
    }
    const why = r.reason === "timeout" ? "انتهت مهلة الاتصال بالرابط." : r.status ? "الخادم أعاد HTTP " + r.status + "." : "تعذر الاتصال بالرابط.";
    throw updFail("APK_URL_UNREACHABLE", "تعذر فتح رابط APK. " + why, "downloadUrl");
  }
  if (r.reason === "empty") throw updFail("APK_EMPTY", "ملف APK فارغ.", "downloadUrl");
  if (!r.isApk) {
    const why =
      r.reason === "web_page"
        ? "الرابط يشير إلى صفحة ويب وليس إلى ملف APK مباشر."
        : r.reason === "no_manifest"
        ? "الملف لا يحتوي AndroidManifest.xml."
        : "الملف ليس APK صالحاً.";
    throw updFail("NOT_AN_APK", why, "downloadUrl");
  }
  if (r.size > 0) f.fileSize = r.size;
  if (r.apkInfo) {
    const info = r.apkInfo;
    f.packageName = info.packageName || f.packageName;
    f.apkVersionCode = info.versionCode || 0;
    f.apkVersionName = info.versionName || "";
    if (info.versionCode > 0 && info.versionCode !== f.versionCode) {
      throw updFail(
        "APK_VERSION_MISMATCH",
        "رقم الإصدار داخل ملف APK (" + info.versionCode + ") لا يطابق Version Code المكتوب (" + f.versionCode + "). عدّل الرقم أو ارفع الملف الصحيح.",
        "versionCode",
        400,
        { apkVersionCode: info.versionCode }
      );
    }
    if (info.versionName && info.versionName !== f.versionName) {
      warnings.push("اسم الإصدار داخل APK (" + info.versionName + ") يختلف عن المكتوب (" + f.versionName + ").");
    }
    const known = updKnownPackage(doc);
    if (known && info.packageName && known !== info.packageName) {
      throw updFail(
        "PACKAGE_MISMATCH",
        "اسم الحزمة داخل APK (" + info.packageName + ") يختلف عن حزمة الإصدارات السابقة (" + known + "). لن يتمكن التطبيق من تحديث نفسه بهذا الملف.",
        "downloadUrl"
      );
    }
  } else {
    warnings.push("تعذر قراءة بيانات الإصدار من داخل APK، لذلك لم يُتحقَّق من تطابق Version Code.");
  }
  return warnings;
}

// -----------------------------------------------------------------------------
// release records / manifest
// -----------------------------------------------------------------------------

function updLegacyMessage(rel) {
  const parts = [];
  if (rel.description) parts.push(rel.description);
  if (rel.changelog && rel.changelog.length) parts.push(rel.changelog.map((c) => "• " + c).join("\n"));
  return parts.join("\n");
}

function updUpsertRelease(doc, existing, f, status, nowIso) {
  let rel = existing;
  if (!rel) {
    rel = { id: updNewId(), createdAt: nowIso, push: { status: "none" } };
    doc.releases.push(rel);
  }
  rel.status = status;
  rel.versionName = f.versionName;
  rel.versionCode = f.versionCode;
  rel.minVersionCode = f.minVersionCode;
  rel.updateType = f.updateType;
  rel.title = f.title;
  rel.description = f.description;
  rel.changelog = f.changelog.slice();
  rel.downloadUrl = f.downloadUrl;
  rel.apkKey = f.apkKey;
  rel.apkSource = f.apkSource;
  rel.fileSize = f.fileSize;
  rel.sha256 = f.sha256;
  rel.packageName = f.packageName;
  rel.apkVersionCode = f.apkVersionCode;
  rel.apkVersionName = f.apkVersionName;
  rel.sendPush = f.sendPush;
  rel.pushTitle = f.pushTitle;
  rel.pushBody = f.pushBody;
  rel.maintenanceDuring = f.maintenanceDuring;
  rel.maintenanceAfter = f.maintenanceAfter;
  rel.scheduledAt = status === "scheduled" ? f.scheduledAt : "";
  rel.updatedAt = nowIso;
  return rel;
}

function updBuildManifest(rel, live, nowIso) {
  const blocked = live && Array.isArray(live.blockedVersions) ? live.blockedVersions.filter((n) => Number.isInteger(n) && n > 0) : [];
  const manifest = {
    schema: 2,
    published: true,
    releaseId: rel.id,
    versionCode: rel.versionCode,
    versionName: rel.versionName,
    minVersionCode: rel.minVersionCode,
    updateType: rel.updateType,
    force: rel.updateType === "force",
    title: rel.title,
    description: rel.description,
    message: updLegacyMessage(rel),
    changelog: rel.changelog,
    downloadUrl: rel.downloadUrl,
    url: rel.downloadUrl,
    fileSize: rel.fileSize,
    blockedVersions: blocked,
    sendPush: rel.sendPush === true,
    publishedAt: rel.publishedAt,
    updatedAt: nowIso,
  };
  if (rel.sha256) manifest.sha256 = rel.sha256;
  if (rel.packageName) manifest.packageName = rel.packageName;
  return manifest;
}

async function updWriteManifest(env, manifest) {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      await writeJsonFile(env, UPDATE_OBJECT, manifest);
      return;
    } catch (error) {
      lastError = error;
      await sleep(80 * (attempt + 1));
    }
  }
  throw lastError || new Error("manifest_write_failed");
}

async function updSetPointer(env, rel) {
  await adminBucket(env).put(
    adminKey(env, UPD_SCHEDULE_NAME),
    JSON.stringify({ releaseId: rel.id, at: rel.scheduledAt, versionCode: rel.versionCode }),
    { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } }
  );
}

async function updClearPointer(env) {
  try {
    await adminBucket(env).delete(adminKey(env, UPD_SCHEDULE_NAME));
  } catch {
    // best effort
  }
}

async function updReadPointer(env) {
  try {
    const obj = await adminBucket(env).get(adminKey(env, UPD_SCHEDULE_NAME));
    if (!obj) return null;
    const p = JSON.parse(await obj.text());
    return p && p.releaseId && Number.isFinite(Date.parse(p.at)) ? p : null;
  } catch {
    return null;
  }
}

// -----------------------------------------------------------------------------
// maintenance link (uses the existing cigram_maintenance.json, never edits its logic)
// -----------------------------------------------------------------------------

async function updSetMaintenance(env, enabled, reason) {
  const current = await r2ReadJson(env.MOVIES_BUCKET, MAINTENANCE_OBJECT, null);
  const old = normalizeMaintenance(current.doc);
  if (old.enabled === enabled) return false;
  const next = normalizeMaintenance(Object.assign({}, old, { enabled }));
  if (enabled) {
    if (!next.title) next.title = "تحديث التطبيق";
    if (!next.message) next.message = "نقوم الآن بتحديث التطبيق. سنعود قريباً.";
  }
  next.updated_at = new Date().toISOString();
  next.version = old.version + 1;
  const ok = await r2WriteJsonIf(env.MOVIES_BUCKET, MAINTENANCE_OBJECT, next, current.etag);
  if (!ok) throw new Error("maintenance_conflict");
  try {
    await saveUndoRecord(env, enabled ? "تفعيل وضع الصيانة (تحديث التطبيق)" : "إلغاء وضع الصيانة (بعد التحديث)", "maintenance", [], [
      { object: MAINTENANCE_OBJECT, before: current.exists ? current.doc : null },
    ]);
  } catch {
    // undo record is optional
  }
  updLog("maintenance", { enabled, reason: updText(reason, 80) });
  return true;
}

// -----------------------------------------------------------------------------
// push
// -----------------------------------------------------------------------------

// --- direct Firebase Cloud Messaging (HTTP v1) — no external relay needed --------------

function fcmB64url(bytes) {
  let bin = "";
  const arr = bytes instanceof Uint8Array ? bytes : new TextEncoder().encode(bytes);
  for (let i = 0; i < arr.length; i++) bin += String.fromCharCode(arr[i]);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

let fcmTokenCache = null; // { token, exp }

async function fcmAccessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (fcmTokenCache && fcmTokenCache.email === sa.client_email && fcmTokenCache.exp - 60 > now) return fcmTokenCache.token;
  const header = fcmB64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = fcmB64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = String(sa.private_key || "").replace(/\\n/g, "\n").replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(header + "." + claim)));
  const jwt = header + "." + claim + "." + fcmB64url(sig);
  const res = await updFetch(
    "https://oauth2.googleapis.com/token",
    {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + jwt,
    },
    8000
  );
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) throw new Error("FCM_AUTH " + res.status);
  fcmTokenCache = { token: data.access_token, exp: now + (data.expires_in || 3600), email: sa.client_email };
  return data.access_token;
}

async function updSendFcm(env, rel, title, body, at) {
  // v9: every Cigram phone (old + new app versions), visible notification + data, HIGH priority
  const out = await pushBroadcast(env, String(title), String(body), {
    kind: "app_update",
    type: "app_update",
    id: "upd_" + String(rel.versionCode || rel.versionName || Date.now()),
    url: String(rel.downloadUrl || ""),
    versionCode: String(rel.versionCode || ""),
    versionName: String(rel.versionName || ""),
    updateType: String(rel.updateType || ""),
    force: rel.updateType === "force" ? "true" : "false",
  });
  return { status: out.status, at: out.at || at, error: out.error, topic: out.topic };
}

async function updSendPush(env, rel) {
  const at = new Date().toISOString();
  if (env.FCM_SERVICE_ACCOUNT) {
    return updSendFcm(env, rel, rel.pushTitle || rel.title || "تحديث جديد لسيكرام", rel.pushBody || "الإصدار " + rel.versionName + " أصبح متوفراً الآن.", at);
  }
  if (!env.PUSH_WEBHOOK_URL) {
    return { status: "no_provider", at, error: "لم يُضبط مزوّد الإشعارات في الـ Worker (أضف FCM_SERVICE_ACCOUNT أو PUSH_WEBHOOK_URL). التطبيق سيكتشف التحديث عند فحص الملف." };
  }
  try {
    const res = await updFetch(
      env.PUSH_WEBHOOK_URL,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          type: "app_update",
          title: rel.pushTitle || rel.title || "تحديث جديد لسيكرام",
          body: rel.pushBody || "الإصدار " + rel.versionName + " أصبح متوفراً الآن.",
          url: rel.downloadUrl,
          versionCode: rel.versionCode,
          versionName: rel.versionName,
          updateType: rel.updateType,
          force: rel.updateType === "force",
        }),
      },
      8000
    );
    if (!res.ok) return { status: "failed", at, error: "رفض مزوّد الإشعارات الطلب (HTTP " + res.status + ")." };
    return { status: "sent", at };
  } catch (error) {
    return {
      status: "failed",
      at,
      error: error && error.name === "AbortError" ? "انتهت مهلة الاتصال بمزوّد الإشعارات." : "تعذر الاتصال بمزوّد الإشعارات.",
    };
  }
}

async function updSavePushResult(env, id, result) {
  try {
    await updMutateHistory(env, (doc) => {
      const rel = doc.releases.find((r) => r.id === id);
      if (rel) {
        rel.push = result;
        updAudit(doc, "push_" + result.status, rel, result.error || "");
      }
    });
  } catch (error) {
    updLog("push_result_not_saved", { id, message: String(error && error.message) });
  }
}

// -----------------------------------------------------------------------------
// publish / schedule core
// -----------------------------------------------------------------------------

function updIsLegacyPayload(input) {
  return input.versionCode === undefined || input.versionCode === null || cleanString(input.versionCode) === "";
}

/** The old POST /publish-update only sent versionName + downloadUrl (+ message, sendPush). */
function updLegacyPayload(input, doc, live) {
  const max = updMaxCode(doc, live);
  const message = cleanString(input.message);
  const lines = message ? message.split(/\r?\n/) : [];
  const bullets = lines.filter((l) => /^\s*[•\-*]/.test(l));
  const head = lines.filter((l) => !/^\s*[•\-*]/.test(l)).join("\n").trim();
  return Object.assign({}, input, {
    versionCode: max + 1,
    title: cleanString(input.title) || "تحديث جديد لسيكرام",
    description: head || "تم إصدار تحديث جديد لتطبيق Cigram.",
    changelog: bullets,
    updateType: input.updateType || "optional",
    minVersionCode: input.minVersionCode === undefined ? 0 : input.minVersionCode,
  });
}

async function updPublish(env, input, opts) {
  const o = opts || {};
  const nowIso = new Date().toISOString();
  const mode = o.fromScheduler ? "now" : lc(input.mode) === "schedule" ? "schedule" : "now";
  const releaseId = updText(input.releaseId || input.id, 80);
  const legacy = !o.fromScheduler && updIsLegacyPayload(input);

  const history = await updReadHistory(env);
  const doc = history.doc;
  const live = await updReadLive(env);
  updSeedFromLive(doc, live);

  const existing = releaseId ? doc.releases.find((r) => r.id === releaseId) || null : null;
  if (releaseId && !existing) throw updFail("NOT_FOUND", "الإصدار المطلوب غير موجود في السجل.", "", 404);
  if (existing && !["draft", "scheduled", "stopped", "failed"].includes(existing.status)) {
    throw updFail("INVALID_STATE", "لا يمكن نشر هذا الإصدار لأن حالته الحالية لا تسمح بذلك.", "", 409);
  }
  const payload = legacy ? updLegacyPayload(input, doc, live) : input;
  const f = updSanitizeFields(payload, true, { allowHttp: legacy });
  if (mode === "schedule") updCheckSchedule(doc, existing, f);
  updCheckVersion(doc, live, f, existing);
  const warnings = await updVerifyApk(env, f, doc);
  if (live && live.published !== false && f.minVersionCode < (Number(live.minVersionCode) || 0)) {
    warnings.push("الحد الأدنى المدعوم انخفض من " + live.minVersionCode + " إلى " + f.minVersionCode + ".");
  }

  // ----------------------------------------------------------------- schedule
  if (mode === "schedule") {
    const res = await updMutateHistory(env, (d) => {
      updSeedFromLive(d, live);
      const ex = releaseId ? d.releases.find((r) => r.id === releaseId) || null : null;
      updCheckSchedule(d, ex, f);
      updCheckVersion(d, live, f, ex);
      const rel = updUpsertRelease(d, ex, f, "scheduled", nowIso);
      updAudit(d, "schedule", rel, "مجدول: " + f.scheduledAt);
      return rel;
    });
    try {
      await updSetPointer(env, res.result);
    } catch (error) {
      warnings.push("تم حفظ الجدولة لكن تعذر تحديث مؤشر الجدولة؛ ستُنفَّذ عبر الـ Cron.");
      updLog("pointer_failed", { message: String(error && error.message) });
    }
    updLog("scheduled", { id: res.result.id, versionCode: res.result.versionCode, at: res.result.scheduledAt });
    return { mode: "schedule", release: res.result, manifest: null, warnings, push: null };
  }

  // ---------------------------------------------------------------------- now
  if (f.maintenanceDuring) {
    try {
      await updSetMaintenance(env, true, "publish");
    } catch (error) {
      warnings.push("تعذر تفعيل وضع الصيانة قبل النشر.");
      updLog("maintenance_failed", { message: String(error && error.message) });
    }
  }

  const res = await updMutateHistory(env, (d) => {
    updSeedFromLive(d, live);
    const ex = releaseId ? d.releases.find((r) => r.id === releaseId) || null : null;
    if (o.fromScheduler && (!ex || ex.status !== "scheduled")) {
      throw updFail("SCHEDULE_HANDLED", "تمت معالجة هذا التحديث المجدول مسبقاً.", "", 409);
    }
    updCheckVersion(d, live, f, ex);
    for (const r of d.releases) {
      if (r.status === "published" && (!ex || r.id !== ex.id)) {
        r.status = "superseded";
        r.supersededAt = nowIso;
      }
    }
    const rel = updUpsertRelease(d, ex, f, "published", nowIso);
    rel.publishedAt = nowIso;
    rel.stoppedAt = "";
    rel.push = { status: "none" };
    updAudit(d, o.fromScheduler ? "publish_scheduled" : "publish", rel, f.updateType);
    return rel;
  });
  const rel = res.result;
  const manifest = updBuildManifest(rel, live, nowIso);
  try {
    await updWriteManifest(env, manifest);
  } catch (error) {
    await updRevertHistory(env, res.raw);
    updLog("manifest_write_failed", { message: String(error && error.message) });
    throw updFail("MANIFEST_WRITE_FAILED", "تعذر كتابة ملف التحديث في R2، ولم يتم نشر التحديث. تحقق من الاتصال وأعد المحاولة.", "", 502);
  }
  if (existing && existing.status === "scheduled") await updClearPointer(env);

  let push = null;
  if (f.sendPush) {
    push = await updSendPush(env, rel);
    rel.push = push;
    await updSavePushResult(env, rel.id, push);
    if (push.status === "failed") warnings.push("تم نشر التحديث لكن فشل إرسال الإشعار: " + push.error);
    if (push.status === "no_provider") warnings.push(push.error);
  }
  if (f.maintenanceAfter) {
    try {
      await updSetMaintenance(env, false, "after_publish");
    } catch (error) {
      warnings.push("تم النشر لكن تعذر إلغاء وضع الصيانة.");
      updLog("maintenance_failed", { message: String(error && error.message) });
    }
  }
  updLog("published", { id: rel.id, versionCode: rel.versionCode, type: rel.updateType, push: push ? push.status : "off", scheduled: Boolean(o.fromScheduler) });
  return { mode: "now", release: rel, manifest, warnings, push };
}

// -----------------------------------------------------------------------------
// scheduling
// -----------------------------------------------------------------------------

async function updActivateScheduled(env, id) {
  const history = await updReadHistory(env);
  const rel = history.doc.releases.find((r) => r.id === id);
  if (!rel || rel.status !== "scheduled") {
    await updClearPointer(env);
    return null;
  }
  const attempts = Number(rel.attempts) || 0;
  const last = Date.parse(rel.lastAttemptAt || "");
  if (attempts > 0 && Number.isFinite(last) && Date.now() - last < 90 * 1000) return null;
  try {
    const out = await updPublish(env, Object.assign({}, rel, { releaseId: id, mode: "now" }), { fromScheduler: true });
    updLog("schedule_activated", { id, versionCode: rel.versionCode });
    return out;
  } catch (error) {
    if (error instanceof AdminError && error.code === "SCHEDULE_HANDLED") return null;
    const message = error && error.message ? error.message : String(error);
    const transient = error instanceof AdminError ? error.status >= 500 : true;
    await updMutateHistory(env, (doc) => {
      const r = doc.releases.find((x) => x.id === id);
      if (!r || r.status !== "scheduled") return;
      r.attempts = (Number(r.attempts) || 0) + 1;
      r.lastAttemptAt = new Date().toISOString();
      r.lastError = updText(message, 300);
      if (!transient || r.attempts >= 5) {
        r.status = "failed";
        r.failedAt = new Date().toISOString();
        updAudit(doc, "schedule_failed", r, message);
      }
    }).catch(() => {});
    if (!transient) await updClearPointer(env);
    updLog("schedule_failed", { id, message: updText(message, 200), transient });
    return null;
  }
}

/** authoritative=true reads the history (cron); false only reads the tiny pointer (cheap, used by GET /update). */
async function updActivateDue(env, authoritative) {
  let id = "";
  if (!authoritative) {
    const ptr = await updReadPointer(env);
    if (!ptr || Date.parse(ptr.at) > Date.now()) return null;
    id = ptr.releaseId;
  } else {
    const history = await updReadHistory(env);
    const due = history.doc.releases
      .filter((r) => r.status === "scheduled" && Date.parse(r.scheduledAt) <= Date.now())
      .sort((a, b) => Date.parse(a.scheduledAt) - Date.parse(b.scheduledAt))[0];
    if (!due) return null;
    id = due.id;
  }
  return updActivateScheduled(env, id);
}

async function updServePublic(env) {
  try {
    await updActivateDue(env, false);
  } catch (error) {
    updLog("lazy_activation_failed", { message: String(error && error.message) });
  }
  return returnR2JsonObject(env, UPDATE_OBJECT, { ok: false, error: "no_update" });
}

// -----------------------------------------------------------------------------
// route handlers (admin)
// -----------------------------------------------------------------------------

async function handlePublishUpdate(request, env) {
  const input = await readRequestData(request);
  const out = await updPublish(env, input, {});
  const rel = out.release;
  const scheduled = out.mode === "schedule";
  return json({
    ok: true,
    success: true,
    message: scheduled ? "تمت جدولة التحديث بنجاح" : "تم نشر التحديث بنجاح",
    versionCode: rel.versionCode,
    versionName: rel.versionName,
    pushSent: out.push ? out.push.status === "sent" : false,
    data: { mode: out.mode, release: rel, manifest: out.manifest, warnings: out.warnings, push: out.push },
  });
}

async function handleUpdateHistory(request, env) {
  try {
    await updActivateDue(env, true);
  } catch (error) {
    updLog("history_activation_failed", { message: String(error && error.message) });
  }
  const live = await updReadLive(env);
  let history = await updReadHistory(env);
  if (!history.doc.releases.length && live) {
    const res = await updMutateHistory(env, (d) => {
      updSeedFromLive(d, live);
    });
    history = { ...history, doc: updNormHistory(res.doc) };
  }
  const doc = history.doc;
  const releases = doc.releases.slice().sort((a, b) => {
    const dv = (Number(b.versionCode) || 0) - (Number(a.versionCode) || 0);
    if (dv !== 0) return dv;
    return Date.parse(b.createdAt || "") - Date.parse(a.createdAt || "") || 0;
  });
  const maintenance = normalizeMaintenance(await readJsonFile(env, MAINTENANCE_OBJECT, null));
  return okResponse({
    now: new Date().toISOString(),
    live,
    releases,
    audit: doc.audit.slice(-40).reverse(),
    maxVersionCode: updMaxCode(doc, live),
    push: { configured: Boolean(env.PUSH_WEBHOOK_URL || env.FCM_SERVICE_ACCOUNT) },
    maintenance: { enabled: maintenance.enabled, title: maintenance.title },
    upload: { partSize: UPD_PART_SIZE, maxBytes: UPD_MAX_APK_BYTES },
    publicBase: R2_PUBLIC_BASE,
  });
}

async function handleUpdateDraft(request, env) {
  const input = await readRequestData(request);
  const op = lc(input.op || input.action) || "save";
  const id = updText(input.id || input.releaseId, 80);

  if (op === "delete") {
    if (!id) throw updFail("NOT_FOUND", "حدّد المسودة المراد حذفها.", "", 404);
    const res = await updMutateHistory(env, (doc) => {
      const idx = doc.releases.findIndex((r) => r.id === id);
      if (idx < 0) throw updFail("NOT_FOUND", "المسودة غير موجودة.", "", 404);
      if (doc.releases[idx].status !== "draft") throw updFail("NOT_A_DRAFT", "يمكن حذف المسودات فقط. التحديثات المنشورة تبقى في السجل.", "", 409);
      const [gone] = doc.releases.splice(idx, 1);
      updAudit(doc, "draft_delete", gone, "");
      return gone;
    });
    updLog("draft_deleted", { id });
    return okResponse({ id }, "تم حذف المسودة.", { deleted: res.result.id });
  }

  const f = updSanitizeFields(input, false);
  if (!f.versionName && !f.title && !f.description && !f.downloadUrl && !f.changelog.length) {
    throw updFail("EMPTY_DRAFT", "المسودة فارغة. اكتب معلومة واحدة على الأقل قبل الحفظ.", "versionName");
  }
  const nowIso = new Date().toISOString();
  const res = await updMutateHistory(env, (doc) => {
    const ex = id ? doc.releases.find((r) => r.id === id) || null : null;
    if (id && !ex) throw updFail("NOT_FOUND", "المسودة غير موجودة.", "", 404);
    if (ex && ex.status !== "draft") throw updFail("NOT_A_DRAFT", "لا يمكن تعديل إصدار منشور أو مجدول كمسودة.", "", 409);
    if (!ex && doc.releases.filter((r) => r.status === "draft").length >= UPD_MAX_DRAFTS) {
      throw updFail("TOO_MANY_DRAFTS", "وصلت إلى الحد الأقصى للمسودات (" + UPD_MAX_DRAFTS + "). احذف بعضها أولاً.", "", 409);
    }
    const rel = updUpsertRelease(doc, ex, f, "draft", nowIso);
    updAudit(doc, ex ? "draft_update" : "draft_create", rel, "");
    return rel;
  });
  updLog("draft_saved", { id: res.result.id });
  return okResponse({ release: res.result }, "تم حفظ المسودة.");
}

async function handleUpdateStop(request, env) {
  const input = await readRequestData(request);
  const out = await updStopLive(env, updText(input.reason, 200) || "إيقاف يدوي");
  return okResponse(out, "تم إيقاف التحديث. بقي الإصدار محفوظاً في السجل.");
}

async function updStopLive(env, reason) {
  const live = await updReadLive(env);
  if (!live || live.published === false) {
    throw updFail("NOTHING_TO_STOP", "لا يوجد تحديث منشور حالياً لإيقافه.", "", 409);
  }
  const nowIso = new Date().toISOString();
  const res = await updMutateHistory(env, (doc) => {
    updSeedFromLive(doc, live);
    const rel = doc.releases.find((r) => r.status === "published") || doc.releases.find((r) => Number(r.versionCode) === Number(live.versionCode));
    if (rel) {
      rel.status = "stopped";
      rel.stoppedAt = nowIso;
      rel.stopReason = reason;
      rel.updatedAt = nowIso;
      updAudit(doc, "stop", rel, reason);
    }
    return rel || null;
  });
  const manifest = Object.assign({}, live, { published: false, stoppedAt: nowIso, updatedAt: nowIso });
  try {
    await updWriteManifest(env, manifest);
  } catch (error) {
    await updRevertHistory(env, res.raw);
    throw updFail("MANIFEST_WRITE_FAILED", "تعذر كتابة ملف التحديث في R2، ولم يتم الإيقاف. أعد المحاولة.", "", 502);
  }
  updLog("stopped", { versionCode: live.versionCode, reason: updText(reason, 80) });
  return { manifest, release: res.result };
}

async function handleUpdateScheduleCancel(request, env) {
  const input = await readRequestData(request);
  const id = updText(input.id || input.releaseId, 80);
  const res = await updMutateHistory(env, (doc) => {
    const rel = doc.releases.find((r) => r.id === id);
    if (!rel) throw updFail("NOT_FOUND", "الإصدار المجدول غير موجود.", "", 404);
    if (rel.status !== "scheduled" && rel.status !== "failed") throw updFail("INVALID_STATE", "هذا الإصدار غير مجدول.", "", 409);
    rel.status = "draft";
    rel.scheduledAt = "";
    rel.attempts = 0;
    rel.lastError = "";
    rel.updatedAt = new Date().toISOString();
    updAudit(doc, "schedule_cancel", rel, "");
    return rel;
  });
  await updClearPointer(env);
  updLog("schedule_cancelled", { id });
  return okResponse({ release: res.result }, "أُلغيت الجدولة وعاد الإصدار إلى المسودات.");
}

async function handleUpdatePushResend(request, env) {
  const input = await readRequestData(request);
  const id = updText(input.id || input.releaseId, 80);
  const history = await updReadHistory(env);
  const rel = history.doc.releases.find((r) => r.id === id);
  if (!rel) throw updFail("NOT_FOUND", "الإصدار غير موجود.", "", 404);
  if (rel.status !== "published") throw updFail("INVALID_STATE", "يمكن إرسال الإشعار للإصدار المنشور حالياً فقط.", "", 409);
  const push = await updSendPush(env, rel);
  await updSavePushResult(env, id, push);
  if (push.status === "failed") throw updFail("PUSH_FAILED", "فشل إرسال الإشعار: " + push.error, "", 502, { push });
  if (push.status === "no_provider") throw updFail("PUSH_NOT_CONFIGURED", push.error, "", 409, { push });
  return okResponse({ push }, "تم إرسال الإشعار.");
}

async function handleUpdateValidate(request, env) {
  const input = await readRequestData(request);
  let url = updText(input.url || input.downloadUrl, 1000).trim();
  const key = updText(input.key, 300);
  if (!url && key && key.startsWith(UPD_PREFIX) && !key.includes("..")) url = updPublicUrl(key);
  if (!url) throw updFail("MISSING_DOWNLOAD_URL", "أدخل رابط APK لفحصه.", "downloadUrl");
  let parsed = null;
  try {
    parsed = new URL(url);
  } catch {
    parsed = null;
  }
  if (!parsed || (parsed.protocol !== "https:" && parsed.protocol !== "http:")) {
    throw updFail("INVALID_DOWNLOAD_URL", "الرابط غير صالح. يجب أن يبدأ بـ https://", "downloadUrl");
  }
  const r = await updInspectUrl(env, url);
  const wantCode = updInt(input.versionCode);
  const wantName = updText(input.versionName, 60);
  const warnings = [];
  const matches = { versionCode: null, versionName: null };
  if (r.apkInfo) {
    if (Number.isFinite(wantCode) && wantCode > 0) {
      matches.versionCode = r.apkInfo.versionCode === wantCode;
      if (!matches.versionCode) warnings.push("Version Code داخل APK (" + r.apkInfo.versionCode + ") لا يطابق المكتوب (" + wantCode + ").");
    }
    if (wantName) {
      matches.versionName = r.apkInfo.versionName === wantName;
      if (!matches.versionName) warnings.push("اسم الإصدار داخل APK (" + r.apkInfo.versionName + ") يختلف عن المكتوب (" + wantName + ").");
    }
  }
  if (r.reachable && r.isApk && !r.apkInfo) warnings.push("تعذر قراءة بيانات الإصدار من داخل APK (الخادم الخارجي قد لا يدعم القراءة الجزئية).");
  if (!url.startsWith("https://")) warnings.push("الرابط لا يبدأ بـ https وقد يمنعه نظام أندرويد.");
  const messages = {
    not_found: "الملف غير موجود في R2.",
    empty: "الملف فارغ.",
    unsafe_url: "الرابط غير مسموح به.",
    timeout: "انتهت مهلة الاتصال بالرابط.",
    network: "تعذر الاتصال بالرابط.",
    web_page: "الرابط يشير إلى صفحة ويب وليس إلى ملف APK مباشر.",
    not_zip: "الملف ليس APK (ليس أرشيف ZIP).",
    no_manifest: "الملف لا يحتوي AndroidManifest.xml.",
  };
  let message = "";
  if (!r.reachable) message = messages[r.reason] || (r.status ? "الخادم أعاد HTTP " + r.status + "." : "تعذر فتح الرابط.");
  else if (!r.isApk) message = messages[r.reason] || "الملف ليس APK صالحاً.";
  else message = "الرابط يعمل والملف APK صالح.";
  return okResponse(
    {
      url,
      hosted: r.hosted,
      reachable: r.reachable,
      status: r.status,
      isApk: r.isApk,
      size: r.size,
      contentType: r.contentType,
      apkInfo: r.apkInfo,
      matches,
      warnings,
      reason: r.reason,
    },
    message
  );
}

// ---- chunked APK upload ------------------------------------------------------

function updSafeVersionForFile(value) {
  const s = cleanString(value)
    .replace(/[^0-9A-Za-z._-]+/g, "-")
    .replace(/^[-.]+|[-.]+$/g, "")
    .slice(0, 40);
  return s || "app";
}

async function updKeyInUse(env, doc, live, key) {
  if (live) {
    if (updKeyFromUrl(live.downloadUrl || live.url) === key) return true;
  }
  return doc.releases.some((r) => r.status !== "draft" && (r.apkKey === key || updKeyFromUrl(r.downloadUrl) === key));
}

async function updPickKey(env, base) {
  const history = await updReadHistory(env);
  const live = await updReadLive(env);
  for (let i = 1; i <= 60; i++) {
    const key = UPD_PREFIX + (i === 1 ? base : base + "-" + i) + ".apk";
    const head = await env.MOVIES_BUCKET.head(key);
    if (!head) return key;
    if (!(await updKeyInUse(env, history.doc, live, key))) return key; // orphan of a failed / draft attempt
  }
  throw updFail("UPLOAD_FAILED", "تعذر اختيار اسم ملف جديد في R2. أعد المحاولة.", "", 409);
}

function updUploadKey(url) {
  const key = updText(url.searchParams.get("key"), 300);
  if (!key.startsWith(UPD_PREFIX) || key.includes("..") || !key.toLowerCase().endsWith(".apk")) {
    throw updFail("INVALID_UPLOAD", "مسار الرفع غير صالح.", "", 400);
  }
  return key;
}

async function handleUpdateUpload(request, env, url) {
  const action = lc(url.searchParams.get("action")) || "init";

  if (action === "init") {
    const input = await readRequestData(request);
    const fileName = updText(input.fileName, 200);
    if (!fileName.toLowerCase().endsWith(".apk")) throw updFail("NOT_AN_APK", "الملف المختار ليس بصيغة APK.", "downloadUrl");
    const size = updInt(input.size);
    if (!Number.isFinite(size) || size <= 0) throw updFail("INVALID_FILE_SIZE", "حجم الملف غير صالح.", "downloadUrl");
    if (size > UPD_MAX_APK_BYTES) {
      throw updFail("APK_TOO_LARGE", "حجم الملف (" + Math.round(size / 1048576) + " MB) أكبر من الحد المسموح (" + Math.round(UPD_MAX_APK_BYTES / 1048576) + " MB).", "downloadUrl", 413);
    }
    const base = "cigram-" + updSafeVersionForFile(input.versionName || fileName.replace(/\.apk$/i, ""));
    const key = await updPickKey(env, base);
    let upload;
    try {
      upload = await env.MOVIES_BUCKET.createMultipartUpload(key, {
        httpMetadata: { contentType: UPD_APK_MIME, cacheControl: "public, max-age=86400" },
        customMetadata: { kind: "app-update-apk", uploadedAt: new Date().toISOString(), originalName: encodeURIComponent(fileName).slice(0, 200) },
      });
    } catch (error) {
      updLog("upload_init_failed", { message: String(error && error.message) });
      throw updFail("UPLOAD_FAILED", "تعذر بدء الرفع إلى R2. أعد المحاولة.", "", 502);
    }
    updLog("upload_init", { key, size });
    return okResponse({
      key,
      uploadId: upload.uploadId,
      partSize: UPD_PART_SIZE,
      parts: Math.ceil(size / UPD_PART_SIZE),
      url: updPublicUrl(key),
    });
  }

  if (action === "part") {
    const key = updUploadKey(url);
    const uploadId = updText(url.searchParams.get("uploadId"), 400);
    const partNumber = updInt(url.searchParams.get("partNumber"));
    if (!uploadId || !Number.isFinite(partNumber) || partNumber < 1 || partNumber > 10000) {
      throw updFail("INVALID_UPLOAD", "بيانات الجزء غير صالحة.", "", 400);
    }
    const declared = Number(request.headers.get("content-length")) || 0;
    if (declared > UPD_PART_SIZE + 1024) throw updFail("PART_TOO_LARGE", "حجم الجزء أكبر من المسموح.", "", 413);
    const body = await request.arrayBuffer();
    if (!body.byteLength) throw updFail("EMPTY_PART", "الجزء المرسل فارغ.", "", 400);
    if (body.byteLength > UPD_PART_SIZE) throw updFail("PART_TOO_LARGE", "حجم الجزء أكبر من المسموح.", "", 413);
    try {
      const part = await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).uploadPart(partNumber, body);
      return okResponse({ partNumber: part.partNumber, etag: part.etag, size: body.byteLength });
    } catch (error) {
      updLog("upload_part_failed", { key, partNumber, message: String(error && error.message) });
      throw updFail("UPLOAD_FAILED", "فشل رفع الجزء " + partNumber + " إلى R2. أعد المحاولة.", "", 502);
    }
  }

  if (action === "complete") {
    const input = await readRequestData(request);
    const key = cleanString(input.key);
    if (!key.startsWith(UPD_PREFIX) || key.includes("..") || !key.toLowerCase().endsWith(".apk")) {
      throw updFail("INVALID_UPLOAD", "مسار الرفع غير صالح.", "", 400);
    }
    const uploadId = updText(input.uploadId, 400);
    const parts = Array.isArray(input.parts) ? input.parts : [];
    if (!uploadId || !parts.length) throw updFail("INVALID_UPLOAD", "بيانات إنهاء الرفع ناقصة.", "", 400);
    const clean = parts
      .map((p) => ({ partNumber: updInt(p && p.partNumber), etag: cleanString(p && p.etag) }))
      .sort((a, b) => a.partNumber - b.partNumber);
    for (let i = 0; i < clean.length; i++) {
      if (!Number.isFinite(clean[i].partNumber) || clean[i].partNumber !== i + 1 || !clean[i].etag) {
        throw updFail("INVALID_UPLOAD", "قائمة الأجزاء غير مكتملة أو غير مرتبة.", "", 400);
      }
    }
    const declared = updInt(input.size);
    let object;
    try {
      object = await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).complete(clean);
    } catch (error) {
      updLog("upload_complete_failed", { key, message: String(error && error.message) });
      throw updFail("UPLOAD_FAILED", "تعذر إنهاء الرفع في R2. أعد رفع الملف.", "", 502);
    }
    const size = object && object.size ? object.size : (await env.MOVIES_BUCKET.head(key) || {}).size || 0;
    if ((Number.isFinite(declared) && declared > 0 && size !== declared) || size > UPD_MAX_APK_BYTES) {
      await env.MOVIES_BUCKET.delete(key).catch(() => {});
      throw updFail("UPLOAD_INCOMPLETE", "حجم الملف المرفوع (" + size + ") لا يطابق الحجم الأصلي. أعد الرفع.", "downloadUrl", 422);
    }
    let ins;
    try {
      ins = await updInspectApk(updR2Reader(env.MOVIES_BUCKET, key, size));
    } catch {
      ins = { isZip: false, valid: false, hasManifest: false, info: null, reason: "read_failed" };
    }
    if (!ins.isZip || !ins.valid || !ins.hasManifest) {
      await env.MOVIES_BUCKET.delete(key).catch(() => {});
      throw updFail("NOT_AN_APK", "الملف المرفوع ليس APK صالحاً (لا يحتوي AndroidManifest.xml). تم حذفه.", "downloadUrl");
    }
    const sha = cleanString(input.sha256).toLowerCase();
    updLog("upload_complete", { key, size });
    return okResponse(
      {
        key,
        url: updPublicUrl(key),
        size,
        sha256: /^[0-9a-f]{64}$/.test(sha) ? sha : "",
        apkInfo: ins.info,
        hasDex: ins.hasDex,
      },
      "تم رفع الملف بنجاح."
    );
  }

  if (action === "abort") {
    const input = await readRequestData(request);
    const key = cleanString(input.key);
    const uploadId = updText(input.uploadId, 400);
    if (key.startsWith(UPD_PREFIX) && !key.includes("..") && uploadId) {
      try {
        await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).abort();
      } catch {
        // already gone
      }
    }
    return okResponse({ aborted: true });
  }

  throw updFail("INVALID_UPLOAD", "إجراء رفع غير معروف.", "", 400);
}

// ---- emergency ------------------------------------------------------------

async function handleUpdateEmergency(request, env) {
  const input = await readRequestData(request);
  const action = lc(input.action);
  if (!truthy(input.confirm)) {
    throw updFail("CONFIRM_REQUIRED", "هذا إجراء خطير ويتطلب تأكيداً صريحاً.", "", 400);
  }
  if (action === "stop") {
    const out = await updStopLive(env, "إيقاف طارئ");
    return okResponse(out, "تم إيقاف التحديث.");
  }
  const live = await updReadLive(env);
  if (!live || live.published === false) {
    throw updFail("NO_PUBLISHED_UPDATE", "لا يوجد تحديث منشور. انشر تحديثاً أولاً لأن هذا الإجراء يحتاج رابط تحميل فعّالاً.", "", 409);
  }
  const liveCode = Number(live.versionCode) || 0;
  const nowIso = new Date().toISOString();
  const manifest = Object.assign({}, live);
  let label = "";

  if (action === "force_current") {
    manifest.updateType = "force";
    manifest.force = true;
    manifest.minVersionCode = liveCode;
    label = "تحويل التحديث الحالي إلى إجباري";
  } else if (action === "block_version" || action === "unblock_version") {
    const code = updInt(input.versionCode);
    if (!Number.isFinite(code) || code < 1) throw updFail("INVALID_VERSION_CODE", "أدخل رقم Version Code صالحاً.", "versionCode");
    const blocked = Array.isArray(live.blockedVersions) ? live.blockedVersions.filter((n) => Number.isInteger(n) && n > 0) : [];
    if (action === "block_version") {
      if (code >= liveCode) {
        throw updFail("CANNOT_BLOCK_LATEST", "لا يمكن تعطيل أحدث إصدار منشور (" + liveCode + "). عطّل إصداراً أقدم منه.", "versionCode");
      }
      if (!blocked.includes(code)) blocked.push(code);
      label = "تعطيل الإصدار " + code;
    } else {
      const idx = blocked.indexOf(code);
      if (idx < 0) throw updFail("NOT_BLOCKED", "هذا الإصدار غير معطّل أصلاً.", "versionCode");
      blocked.splice(idx, 1);
      label = "إلغاء تعطيل الإصدار " + code;
    }
    manifest.blockedVersions = blocked.sort((a, b) => a - b);
  } else if (action === "set_min") {
    const min = updInt(input.minVersionCode);
    if (!Number.isFinite(min) || min < 0) throw updFail("INVALID_MIN_VERSION", "الحد الأدنى غير صالح.", "minVersionCode");
    if (min > liveCode) {
      throw updFail("MIN_VERSION_TOO_HIGH", "الحد الأدنى (" + min + ") أعلى من الإصدار المنشور (" + liveCode + ")؛ سيُحجب كل المستخدمين بلا إمكانية تحديث.", "minVersionCode");
    }
    manifest.minVersionCode = min;
    label = "ضبط الحد الأدنى المسموح على " + min;
  } else {
    throw updFail("INVALID_ACTION", "إجراء الطوارئ غير معروف.", "", 400);
  }
  manifest.updatedAt = nowIso;

  const res = await updMutateHistory(env, (doc) => {
    updSeedFromLive(doc, live);
    const rel = doc.releases.find((r) => r.status === "published") || doc.releases.find((r) => Number(r.versionCode) === liveCode);
    if (rel) {
      if (action === "force_current") {
        rel.updateType = "force";
        rel.minVersionCode = liveCode;
      } else if (action === "set_min") {
        rel.minVersionCode = manifest.minVersionCode;
      }
      rel.updatedAt = nowIso;
    }
    updAudit(doc, "emergency_" + action, rel || { id: "", versionCode: liveCode, versionName: cleanString(live.versionName) }, label);
    return rel || null;
  });
  try {
    await updWriteManifest(env, manifest);
  } catch (error) {
    await updRevertHistory(env, res.raw);
    throw updFail("MANIFEST_WRITE_FAILED", "تعذر كتابة ملف التحديث في R2، ولم يُنفَّذ الإجراء. أعد المحاولة.", "", 502);
  }
  updLog("emergency", { action, versionCode: liveCode });
  return okResponse({ manifest, release: res.result }, label + ".");
}

// ---- analytics -------------------------------------------------------------

function updEmptyStats() {
  return { version: 1, since: new Date().toISOString(), updatedAt: null, total: 0, targets: {}, devices: {} };
}

async function updRecordEvent(env, input) {
  const event = lc(input.event);
  if (!UPD_EVENTS.includes(event)) return;
  const target = updInt(input.versionCode);
  const current = updInt(input.currentVersionCode);
  const rawDevice = updText(input.deviceId, 80);
  const device = rawDevice ? await updHashHex(rawDevice, 12) : "";
  if (event !== "launch" && !(Number.isFinite(target) && target > 0 && target <= UPD_MAX_CODE)) return;
  if (event === "launch" && !(Number.isFinite(current) && current > 0 && current <= UPD_MAX_CODE && device)) return;
  await mutateJson(
    adminBucket(env),
    adminKey(env, UPD_STATS_NAME),
    updEmptyStats(),
    (stats) => {
      if (!stats.targets || typeof stats.targets !== "object") stats.targets = {};
      if (!stats.devices || typeof stats.devices !== "object") stats.devices = {};
      if (event !== "launch") {
        const t = stats.targets[String(target)] || (stats.targets[String(target)] = { shown: 0, update_now: 0, later: 0, download: 0 });
        t[event] = (Number(t[event]) || 0) + 1;
      }
      if (device && Number.isFinite(current) && current > 0) {
        if (stats.devices[device] !== undefined || Object.keys(stats.devices).length < UPD_MAX_DEVICES) stats.devices[device] = current;
      }
      stats.total = (Number(stats.total) || 0) + 1;
      stats.updatedAt = new Date().toISOString();
    },
    4
  );
}

async function handleUpdateEvent(request, env, url, ctx) {
  const ok = () => json({ ok: true });
  try {
    const length = Number(request.headers.get("content-length")) || 0;
    if (length > 4096) return ok();
    const input = await readRequestData(request);
    const work = updRecordEvent(env, input).catch((error) => updLog("event_failed", { message: String(error && error.message) }));
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(work);
    else await work;
  } catch {
    // analytics must never fail the app
  }
  return ok();
}

async function handleUpdateStats(request, env) {
  const live = await updReadLive(env);
  const current = await r2ReadJson(adminBucket(env), adminKey(env, UPD_STATS_NAME), null);
  const stats = current.doc;
  if (!stats || !Number(stats.total)) {
    return okResponse({ hasData: false, total: 0 }, "لا توجد بيانات كافية حتى الآن");
  }
  const installed = {};
  const devices = stats.devices && typeof stats.devices === "object" ? stats.devices : {};
  let deviceCount = 0;
  for (const code of Object.values(devices)) {
    installed[String(code)] = (installed[String(code)] || 0) + 1;
    deviceCount++;
  }
  const liveCode = live ? Number(live.versionCode) || 0 : 0;
  const t = (stats.targets && stats.targets[String(liveCode)]) || { shown: 0, update_now: 0, later: 0, download: 0 };
  let onLatest = 0;
  for (const [code, n] of Object.entries(installed)) if (Number(code) >= liveCode) onLatest += n;
  return okResponse({
    hasData: true,
    since: stats.since,
    updatedAt: stats.updatedAt,
    total: stats.total,
    latestVersionCode: liveCode,
    latest: {
      shown: Number(t.shown) || 0,
      update_now: Number(t.update_now) || 0,
      later: Number(t.later) || 0,
      download: Number(t.download) || 0,
    },
    targets: stats.targets || {},
    installed,
    deviceCount,
    adoptionPercent: deviceCount > 0 && liveCode > 0 ? Math.round((onLatest / deviceCount) * 1000) / 10 : null,
  });
}

// =============================================================================
// MEDIA UPLOAD (v5) — upload a movie / episode / subtitle straight from the admin app
// =============================================================================
// ADDITIVE: nothing above this section was changed. Routes (all need ADMIN_TOKEN):
//   POST /media/upload?action=init       { kind, slug, fileName, size, season?, episode?, overwrite?, rename? }
//   POST /media/upload?action=part       &key=&uploadId=&partNumber=   (raw bytes, streamed straight into R2)
//   GET  /media/upload?action=status     &key=&uploadId=               (parts R2 already accepted -> resume)
//   POST /media/upload?action=complete   { key, uploadId, parts:[{partNumber,etag}], size }
//   POST /media/upload?action=abort      { key, uploadId }
//   GET  /media/upload?action=exists     &kind=&slug=&fileName=&season=&episode=
//   GET  /media/upload?action=verify     &key=&size=
//   POST /media/upload?action=put        &kind=&slug=&fileName=&season=&episode=&overwrite=  (small subtitle file)
// R2 layout follows the existing convention (folder = slug at the bucket root):
//   movie    {slug}/{file}.mp4
//   episode  {slug}/season-01/S01E04.mp4        (+ S01E04.srt)
// The /update/upload (APK) routes are untouched.

const MEDIA_PART_SIZE = 16 * 1024 * 1024; // every part except the last must be equal and >= 5 MiB
const MEDIA_MAX_BYTES = 60 * 1024 * 1024 * 1024;
const MEDIA_MAX_SUBTITLE_BYTES = 5 * 1024 * 1024;
const MEDIA_VIDEO_TYPES = {
  mp4: "video/mp4",
  m4v: "video/mp4",
  mkv: "video/x-matroska",
  mov: "video/quicktime",
  webm: "video/webm",
};
const MEDIA_SUBTITLE_TYPES = {
  srt: "application/x-subrip; charset=utf-8",
  vtt: "text/vtt; charset=utf-8",
};
const MEDIA_LEDGER_PREFIX = "media_uploads/";

function mediaFail(code, message, status, extra) {
  return new AdminError(code, message, status || 400, extra || null);
}

function mediaPad(n) {
  const s = String(n);
  return s.length >= 2 ? s : "0" + s;
}

/** File name -> safe base name (no path, no extension, ASCII letters/digits . _ - only). */
function mediaSafeBase(value, fallback) {
  let s = cleanString(value).split(/[\\/]/).pop() || "";
  s = s.replace(/\.[A-Za-z0-9]{1,5}$/, "");
  try {
    s = s.normalize("NFKD").replace(/[̀-ͯ]/g, "");
  } catch {
    // normalize is optional
  }
  s = s
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/\.{2,}/g, ".")
    .replace(/-{2,}/g, "-")
    .replace(/^[-._]+|[-._]+$/g, "")
    .slice(0, 100);
  return s || fallback;
}

function mediaExtOf(fileName) {
  const m = /\.([A-Za-z0-9]{1,5})$/.exec(cleanString(fileName).split(/[\\/]/).pop() || "");
  return m ? m[1].toLowerCase() : "";
}

function mediaCheckSlug(value) {
  const slug = cleanString(value);
  if (!validSlugFormat(slug)) {
    throw mediaFail("invalid_slug", "Slug غير صالح. استخدم أحرفاً إنجليزية وأرقاماً و . _ - فقط.", 400);
  }
  if (RESERVED_SLUGS.has(lc(slug))) {
    throw mediaFail("reserved_slug", "هذا Slug محجوز لمسارات النظام في R2 («" + slug + "»).", 409);
  }
  return slug;
}

/** Builds the object key on the server so the app can never choose an arbitrary path. */
function mediaBuildKey(input, fileKind) {
  const kind = lc(input.kind);
  if (kind !== "movie" && kind !== "episode") {
    throw mediaFail("invalid_kind", "نوع المحتوى غير صالح (movie / episode).", 400);
  }
  const slug = mediaCheckSlug(input.slug);
  const types = fileKind === "subtitle" ? MEDIA_SUBTITLE_TYPES : MEDIA_VIDEO_TYPES;
  const ext = mediaExtOf(input.fileName);
  if (!types[ext]) {
    throw mediaFail(
      "unsupported_type",
      fileKind === "subtitle" ? "الترجمة يجب أن تكون .srt أو .vtt." : "صيغة الفيديو غير مدعومة (mp4 / mkv / mov / webm).",
      400
    );
  }
  let key;
  if (kind === "movie") {
    key = slug + "/" + mediaSafeBase(input.fileName, slug) + "." + ext;
  } else {
    const season = updInt(input.season);
    const episode = updInt(input.episode);
    if (!Number.isFinite(season) || season < 1 || season > 200 || !Number.isFinite(episode) || episode < 1 || episode > 9999) {
      throw mediaFail("invalid_episode", "رقم الموسم أو الحلقة غير صالح.", 400);
    }
    key = slug + "/season-" + mediaPad(season) + "/S" + mediaPad(season) + "E" + mediaPad(episode) + "." + ext;
  }
  return { key, contentType: types[ext], ext, kind, slug };
}

/** Keys the app may touch through the part / complete / abort / status / verify actions. */
function mediaCheckKey(value) {
  const key = cleanString(value);
  if (
    !/^[A-Za-z0-9][A-Za-z0-9._-]*(\/[A-Za-z0-9][A-Za-z0-9._-]*){1,2}$/.test(key) ||
    key.includes("..") ||
    key.length > 300
  ) {
    throw mediaFail("invalid_key", "مسار الملف غير صالح.", 400);
  }
  const parts = key.split("/");
  if (RESERVED_SLUGS.has(lc(parts[0]))) throw mediaFail("reserved_slug", "مسار محجوز.", 409);
  const ext = mediaExtOf(parts[parts.length - 1]);
  if (!MEDIA_VIDEO_TYPES[ext] && !MEDIA_SUBTITLE_TYPES[ext]) {
    throw mediaFail("unsupported_type", "صيغة الملف غير مدعومة.", 400);
  }
  return key;
}

async function mediaFreeKey(env, key) {
  const dot = key.lastIndexOf(".");
  const stem = key.slice(0, dot);
  const ext = key.slice(dot);
  for (let i = 2; i <= 60; i++) {
    const candidate = stem + "-" + i + ext;
    if (!(await env.MOVIES_BUCKET.head(candidate))) return candidate;
  }
  throw mediaFail("name_unavailable", "تعذر اختيار اسم ملف جديد في R2. أعد المحاولة.", 409);
}

function mediaExistingInfo(key, head) {
  return {
    key,
    size: head.size || 0,
    uploaded: head.uploaded ? new Date(head.uploaded).toISOString() : "",
    contentType: head.httpMetadata && head.httpMetadata.contentType ? head.httpMetadata.contentType : "",
    url: r2PublicUrl(key),
  };
}

// ---- part ledger: the Workers R2 binding cannot list parts, so we record them ourselves ----

function mediaLedgerKey(env, uploadId) {
  const safe = cleanString(uploadId).replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 200);
  return adminKey(env, MEDIA_LEDGER_PREFIX + safe + ".json");
}

async function mediaLedgerRead(env, uploadId) {
  const obj = await adminBucket(env).get(mediaLedgerKey(env, uploadId));
  if (!obj) return null;
  try {
    return JSON.parse(await obj.text());
  } catch {
    return null;
  }
}

async function mediaLedgerWrite(env, uploadId, doc) {
  await adminBucket(env).put(mediaLedgerKey(env, uploadId), JSON.stringify(doc), {
    httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" },
  });
}

async function mediaLedgerDelete(env, uploadId) {
  try {
    await adminBucket(env).delete(mediaLedgerKey(env, uploadId));
  } catch {
    // best effort
  }
}

function mediaIsMissingUpload(error) {
  return /does not exist|NoSuchUpload|10024|not found/i.test(String((error && error.message) || error));
}

async function handleMediaUpload(request, env, url) {
  const action = lc(url.searchParams.get("action"));
  const isGet = request.method === "GET";

  // ---------------------------------------------------------------- exists
  if (isGet && action === "exists") {
    const input = {
      kind: url.searchParams.get("kind"),
      slug: url.searchParams.get("slug"),
      fileName: url.searchParams.get("fileName"),
      season: url.searchParams.get("season"),
      episode: url.searchParams.get("episode"),
    };
    const built = mediaBuildKey(input, lc(url.searchParams.get("file")) === "subtitle" ? "subtitle" : "video");
    const head = await env.MOVIES_BUCKET.head(built.key);
    return okResponse({
      key: built.key,
      exists: Boolean(head),
      existing: head ? mediaExistingInfo(built.key, head) : null,
      suggestedKey: head ? await mediaFreeKey(env, built.key) : "",
    });
  }

  // ---------------------------------------------------------------- status
  if (isGet && action === "status") {
    const key = mediaCheckKey(url.searchParams.get("key"));
    const uploadId = updText(url.searchParams.get("uploadId"), 400);
    if (!uploadId) throw mediaFail("invalid_upload", "معرّف الرفع مفقود.", 400);
    const ledger = await mediaLedgerRead(env, uploadId);
    if (!ledger || ledger.key !== key) {
      return okResponse({ alive: false, parts: [], partSize: MEDIA_PART_SIZE });
    }
    const parts = Object.keys(ledger.parts || {})
      .map((n) => ({ partNumber: Number(n), etag: ledger.parts[n].etag, size: ledger.parts[n].size }))
      .sort((a, b) => a.partNumber - b.partNumber);
    return okResponse({ alive: true, key, uploadId, parts, partSize: ledger.partSize || MEDIA_PART_SIZE, size: ledger.size || 0 });
  }

  // ---------------------------------------------------------------- verify
  if (isGet && action === "verify") {
    const key = mediaCheckKey(url.searchParams.get("key"));
    const expected = updInt(url.searchParams.get("size"));
    const head = await env.MOVIES_BUCKET.head(key);
    if (!head) return okResponse({ exists: false, key, size: 0, matches: false });
    const info = mediaExistingInfo(key, head);
    return okResponse({
      exists: true,
      key,
      size: info.size,
      mimeType: info.contentType,
      etag: head.etag || "",
      url: info.url,
      matches: Number.isFinite(expected) && expected > 0 ? info.size === expected : info.size > 0,
    });
  }

  if (isGet) throw mediaFail("invalid_upload", "إجراء رفع غير معروف.", 400);

  // ------------------------------------------------------------------ init
  if (action === "init") {
    const input = await readRequestData(request);
    const size = updInt(input.size);
    if (!Number.isFinite(size) || size <= 0) throw mediaFail("invalid_file_size", "حجم الملف غير صالح.", 400);
    if (size > MEDIA_MAX_BYTES) {
      throw mediaFail("file_too_large", "حجم الملف أكبر من الحد المسموح (" + Math.round(MEDIA_MAX_BYTES / 1073741824) + " GB).", 413);
    }
    const built = mediaBuildKey(input, "video");
    let key = built.key;
    const head = await env.MOVIES_BUCKET.head(key);
    let replaced = false;
    if (head) {
      if (truthy(input.rename)) {
        key = await mediaFreeKey(env, key);
      } else if (truthy(input.overwrite)) {
        replaced = true;
      } else {
        throw mediaFail("object_exists", "الملف موجود مسبقاً في R2.", 409, {
          existing: mediaExistingInfo(key, head),
          suggestedKey: await mediaFreeKey(env, key),
        });
      }
    }
    let upload;
    try {
      upload = await env.MOVIES_BUCKET.createMultipartUpload(key, {
        httpMetadata: { contentType: built.contentType, cacheControl: "public, max-age=31536000" },
        customMetadata: { kind: "media-" + built.kind, uploadedAt: new Date().toISOString(), originalName: encodeURIComponent(cleanString(input.fileName)).slice(0, 200) },
      });
    } catch (error) {
      updLog("media_init_failed", { message: String(error && error.message) });
      throw mediaFail("upload_failed", "تعذر بدء الرفع إلى R2. أعد المحاولة.", 502);
    }
    await mediaLedgerWrite(env, upload.uploadId, {
      key,
      uploadId: upload.uploadId,
      size,
      partSize: MEDIA_PART_SIZE,
      contentType: built.contentType,
      createdAt: new Date().toISOString(),
      parts: {},
    });
    updLog("media_init", { key, size });
    return okResponse({
      key,
      uploadId: upload.uploadId,
      partSize: MEDIA_PART_SIZE,
      parts: Math.ceil(size / MEDIA_PART_SIZE),
      url: r2PublicUrl(key),
      contentType: built.contentType,
      replaced,
    });
  }

  // ------------------------------------------------------------------ part
  if (action === "part") {
    const key = mediaCheckKey(url.searchParams.get("key"));
    const uploadId = updText(url.searchParams.get("uploadId"), 400);
    const partNumber = updInt(url.searchParams.get("partNumber"));
    if (!uploadId || !Number.isFinite(partNumber) || partNumber < 1 || partNumber > 10000) {
      throw mediaFail("invalid_upload", "بيانات الجزء غير صالحة.", 400);
    }
    const declared = Number(request.headers.get("content-length")) || 0;
    if (declared > MEDIA_PART_SIZE + 1024) throw mediaFail("part_too_large", "حجم الجزء أكبر من المسموح.", 413);
    let body = null;
    let size = 0;
    if (declared > 0 && request.body) {
      body = request.body; // streamed: the Worker never holds the whole part in memory
      size = declared;
    } else {
      const buffer = await request.arrayBuffer();
      body = buffer;
      size = buffer.byteLength;
    }
    if (!size) throw mediaFail("empty_part", "الجزء المرسل فارغ.", 400);
    if (size > MEDIA_PART_SIZE) throw mediaFail("part_too_large", "حجم الجزء أكبر من المسموح.", 413);
    let part;
    try {
      part = await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).uploadPart(partNumber, body);
    } catch (error) {
      updLog("media_part_failed", { key, partNumber, message: String(error && error.message) });
      if (mediaIsMissingUpload(error)) {
        throw mediaFail("upload_not_found", "جلسة الرفع لم تعد موجودة على الخادم. ابدأ الرفع من جديد.", 404);
      }
      throw mediaFail("upload_failed", "فشل رفع الجزء " + partNumber + " إلى R2. أعد المحاولة.", 502);
    }
    const ledger = (await mediaLedgerRead(env, uploadId)) || {
      key,
      uploadId,
      size: 0,
      partSize: MEDIA_PART_SIZE,
      createdAt: new Date().toISOString(),
      parts: {},
    };
    ledger.parts[String(part.partNumber)] = { etag: part.etag, size };
    await mediaLedgerWrite(env, uploadId, ledger);
    return okResponse({ partNumber: part.partNumber, etag: part.etag, size });
  }

  // -------------------------------------------------------------- complete
  if (action === "complete") {
    const input = await readRequestData(request);
    const key = mediaCheckKey(input.key);
    const uploadId = updText(input.uploadId, 400);
    const parts = Array.isArray(input.parts) ? input.parts : [];
    if (!uploadId || !parts.length) throw mediaFail("invalid_upload", "بيانات إنهاء الرفع ناقصة.", 400);
    const clean = parts
      .map((p) => ({ partNumber: updInt(p && p.partNumber), etag: cleanString(p && p.etag) }))
      .sort((a, b) => a.partNumber - b.partNumber);
    for (let i = 0; i < clean.length; i++) {
      if (!Number.isFinite(clean[i].partNumber) || clean[i].partNumber !== i + 1 || !clean[i].etag) {
        throw mediaFail("invalid_upload", "قائمة الأجزاء غير مكتملة أو غير مرتبة.", 400);
      }
    }
    const declared = updInt(input.size);
    let object;
    try {
      object = await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).complete(clean);
    } catch (error) {
      updLog("media_complete_failed", { key, message: String(error && error.message) });
      if (mediaIsMissingUpload(error)) {
        throw mediaFail("upload_not_found", "جلسة الرفع لم تعد موجودة على الخادم. ابدأ الرفع من جديد.", 404);
      }
      throw mediaFail("upload_failed", "تعذر إنهاء الرفع في R2. أعد المحاولة.", 502);
    }
    const head = await env.MOVIES_BUCKET.head(key);
    const size = (head && head.size) || (object && object.size) || 0;
    if (!size || (Number.isFinite(declared) && declared > 0 && size !== declared)) {
      await env.MOVIES_BUCKET.delete(key).catch(() => {});
      await mediaLedgerDelete(env, uploadId);
      throw mediaFail("upload_incomplete", "حجم الملف المرفوع (" + size + ") لا يطابق الحجم المتوقع. أعد الرفع.", 422);
    }
    await mediaLedgerDelete(env, uploadId);
    updLog("media_complete", { key, size });
    return okResponse(
      {
        objectKey: key,
        key,
        publicUrl: r2PublicUrl(key),
        url: r2PublicUrl(key),
        size,
        mimeType: (head && head.httpMetadata && head.httpMetadata.contentType) || MEDIA_VIDEO_TYPES[mediaExtOf(key)] || "",
        etag: (head && head.etag) || "",
      },
      "تم رفع الملف بنجاح."
    );
  }

  // ----------------------------------------------------------------- abort
  if (action === "abort") {
    const input = await readRequestData(request);
    const key = mediaCheckKey(input.key);
    const uploadId = updText(input.uploadId, 400);
    if (uploadId) {
      try {
        await env.MOVIES_BUCKET.resumeMultipartUpload(key, uploadId).abort();
      } catch {
        // already gone
      }
      await mediaLedgerDelete(env, uploadId);
    }
    return okResponse({ aborted: true });
  }

  // ------------------------------------------------------------------- put
  if (action === "put") {
    const input = {
      kind: url.searchParams.get("kind"),
      slug: url.searchParams.get("slug"),
      fileName: url.searchParams.get("fileName"),
      season: url.searchParams.get("season"),
      episode: url.searchParams.get("episode"),
    };
    const built = mediaBuildKey(input, "subtitle");
    const declared = Number(request.headers.get("content-length")) || 0;
    if (declared > MEDIA_MAX_SUBTITLE_BYTES) throw mediaFail("file_too_large", "ملف الترجمة كبير جداً (الحد 5 MB).", 413);
    const buffer = await request.arrayBuffer();
    if (!buffer.byteLength) throw mediaFail("empty_file", "ملف الترجمة فارغ.", 400);
    if (buffer.byteLength > MEDIA_MAX_SUBTITLE_BYTES) throw mediaFail("file_too_large", "ملف الترجمة كبير جداً (الحد 5 MB).", 413);
    try {
      new TextDecoder("utf-8", { fatal: true }).decode(buffer);
    } catch {
      throw mediaFail("invalid_encoding", "ملف الترجمة ليس بترميز UTF-8. حوّله ثم أعد المحاولة.", 400);
    }
    let key = built.key;
    const head = await env.MOVIES_BUCKET.head(key);
    if (head) {
      if (truthy(url.searchParams.get("rename"))) {
        key = await mediaFreeKey(env, key);
      } else if (!truthy(url.searchParams.get("overwrite"))) {
        throw mediaFail("object_exists", "ملف الترجمة موجود مسبقاً في R2.", 409, {
          existing: mediaExistingInfo(key, head),
          suggestedKey: await mediaFreeKey(env, key),
        });
      }
    }
    await env.MOVIES_BUCKET.put(key, buffer, {
      httpMetadata: { contentType: built.contentType, cacheControl: "public, max-age=3600" },
      customMetadata: { kind: "media-subtitle", uploadedAt: new Date().toISOString() },
    });
    return okResponse({ key, objectKey: key, publicUrl: r2PublicUrl(key), url: r2PublicUrl(key), size: buffer.byteLength }, "تم رفع الترجمة.");
  }

  throw mediaFail("invalid_upload", "إجراء رفع غير معروف.", 400);
}

const MEDIA_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /media/upload": A(handleMediaUpload),
    "POST /media/upload": A(handleMediaUpload),
  };
})();

// =============================================================================
// OPS (v6) — app errors, full statistics, AdMob control and ad diagnostics
// =============================================================================
// ADDITIVE: nothing above this section was changed.
//
// PUBLIC (consumed by the Cigram user app):
//   POST /telemetry/events      analytics batch from CigramAnalytics (header X-Cigram-Key)
//   GET  /ads/config            remote ad config read by CigramAdConfig (ADS_REMOTE_CONFIG_URL)
// ADMIN (ADMIN_TOKEN):
//   GET  /admin/ops/summary?days=1|7|30      statistics
//   GET  /admin/ops/errors?days=&all=1       grouped errors (resolved ones hidden unless all=1)
//   POST /admin/ops/errors/resolve           { sig } or { all:true } -> hidden until it happens again
//   GET  /admin/ads/config                   current ad config (+ exists flag)
//   POST /admin/ads/config                   save (validated with the same ranges the app accepts)
//   GET  /admin/ops/ads-check?days=&domain=  ad funnel per placement + verdicts + app-ads.txt check
//
// STORAGE (admin-private R2 keys, no new binding):
//   ops/inbox/<day>/<ms>-<rand>.json   one object per received batch: a plain put, so concurrent
//                                      batches never overwrite each other (nothing is lost)
//   ops/telemetry/<day>/agg.json       day aggregate; the inbox is folded into it by the cron
//                                      (every minute) and before every admin read, under a lease
//   ops/compact_lock.json              that lease (one compaction at a time)
//   ops/state.json                     resolved error signatures
//   ops/ads_config.json                ad config served on GET /ads/config
// Admin reads also count batches still waiting in the inbox, so numbers are live.
//
// OPTIONAL ENV:
//   TELEMETRY_INGEST_KEY      overrides the key the app sends (default = the key built into the app)
//   TELEMETRY_TZ_OFFSET_MIN   day boundary offset in minutes (default 180 = Baghdad)
//   ANALYTICS_FORWARD_URL     raw batches are also forwarded here (e.g. the old cigram-analytics
//                             Worker). Worker-to-worker fetch on the same account may need a
//                             Service Binding or the "global_fetch_strictly_public" flag.
//
// Each aggregate remembers the last inbox ids it folded, so a compaction that stops between
// "fold" and "delete" never counts a batch twice. Unique users are a HyperLogLog estimate (±6 %).

const OPS_DEFAULT_INGEST_KEY = "8Eq4QpLVE3Y6YMGJfwM05ylJFv_ESHWx"; // public: it ships inside the APK
const OPS_COMPACT_CRON = 400;   // inbox objects folded per cron run
const OPS_COMPACT_ADMIN = 250;  // ... before an admin read (keeps a request under the subrequest limit)
const OPS_PENDING_READ = 300;   // inbox objects still counted live by an admin read
const OPS_DONE_RING = 1500;     // folded inbox ids remembered per day
const OPS_LEASE_MS = 90000;
const OPS_MAX_BODY = 64 * 1024;
const OPS_MAX_EVENTS = 60;
const OPS_MAX_ERROR_SIGS = 250;
const OPS_MAX_CONTENT = 400;
const OPS_MAX_KEYS = 120;
const OPS_HLL_P = 8; // 256 registers
const OPS_HLL_M = 1 << OPS_HLL_P;
const OPS_EVENT_RE = /^[a-z0-9_]{1,40}$/;
const OPS_STATE_NAME = "ops/state.json";
const OPS_ADS_CONFIG_NAME = "ops/ads_config.json";
const OPS_ADMOB_PUB = "pub-7010031756978837";
const OPS_ADMOB_CERT = "f08c47fec0942fa0";

// Same ranges as CigramAdConfig in the app: outside them the app silently uses its default,
// so the server refuses them instead of saving a value that would never apply.
const OPS_ADS_BOOLS = [
  "ads_enabled", "player_ads_enabled", "mid_roll_enabled", "episode_ads_enabled",
  "support_ads_enabled", "tv_ads_enabled",
];
const OPS_ADS_NUMBERS = {
  first_ad_delay_min: [5, 120, 15],
  midroll_interval_min: [10, 180, 25],
  max_ads_per_hour: [0, 4, 4],
  cooldown_min: [5, 120, 10],
};
const OPS_ADS_MAX_FREE_IDS = 500;

function opsFail(code, message, status) {
  return new AdminError(code, message, status || 400);
}

function opsOffsetMs(env) {
  const raw = cleanString(env.TELEMETRY_TZ_OFFSET_MIN);
  const n = raw === "" ? 180 : parseInt(raw, 10);
  return (Number.isFinite(n) && Math.abs(n) <= 14 * 60 ? n : 180) * 60000;
}

function opsDay(env, ms) {
  return new Date(ms + opsOffsetMs(env)).toISOString().slice(0, 10);
}

function opsLocalHour(env, ms) {
  return new Date(ms + opsOffsetMs(env)).getUTCHours();
}

/** Last `days` day keys, newest first. */
function opsDays(env, days) {
  const out = [];
  const now = Date.now();
  for (let i = 0; i < days; i++) out.push(opsDay(env, now - i * 86400000));
  return out;
}

function opsDaysParam(url) {
  const n = parseInt(url.searchParams.get("days") || "1", 10);
  if (n === 7 || n === 30) return n;
  return 1;
}

function opsKey(env, day, part) {
  return adminKey(env, "ops/telemetry/" + day + "/" + part + ".json");
}

function opsText(value, max) {
  const s = cleanString(value);
  return s.length > max ? s.slice(0, max) : s;
}

// ------------------------------------------------------------------ HyperLogLog

function opsHash32(text) {
  let h = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  // final avalanche (murmur3 fmix32) so the low bits are well mixed too
  h ^= h >>> 16;
  h = Math.imul(h, 0x85ebca6b);
  h ^= h >>> 13;
  h = Math.imul(h, 0xc2b2ae35);
  h ^= h >>> 16;
  return h >>> 0;
}

function opsHllNew() {
  return new Array(OPS_HLL_M).fill(0);
}

function opsHllAdd(regs, text) {
  const h = opsHash32(text);
  const idx = h >>> (32 - OPS_HLL_P);
  const rest = (h << OPS_HLL_P) >>> 0;
  const rank = rest === 0 ? 32 - OPS_HLL_P + 1 : Math.clz32(rest) + 1;
  if (rank > regs[idx]) regs[idx] = rank;
}

function opsHllMerge(into, from) {
  if (!Array.isArray(from)) return into;
  for (let i = 0; i < OPS_HLL_M; i++) {
    const v = Number(from[i]) || 0;
    if (v > into[i]) into[i] = v;
  }
  return into;
}

function opsHllCount(regs) {
  let sum = 0;
  let zeros = 0;
  for (let i = 0; i < OPS_HLL_M; i++) {
    sum += Math.pow(2, -regs[i]);
    if (regs[i] === 0) zeros++;
  }
  const alpha = 0.7213 / (1 + 1.079 / OPS_HLL_M);
  let e = (alpha * OPS_HLL_M * OPS_HLL_M) / sum;
  if (e <= 2.5 * OPS_HLL_M && zeros > 0) e = OPS_HLL_M * Math.log(OPS_HLL_M / zeros);
  return Math.round(e);
}

// ------------------------------------------------------------------ aggregation

function opsEmptyAgg(day) {
  return {
    v: 1,
    day: day || "",
    batches: 0,
    events: {},          // event name -> count
    users: opsHllNew(),  // HLL of anonymous install ids
    hours: new Array(24).fill(0), // session_start per local hour
    versions: {},        // app version -> session_start count
    os: {},              // Android SDK -> session_start count
    screens: {},         // screen_view by screen
    perf: {},            // metric -> [sum_ms, count]
    plays: {},           // "movie"/"episode" -> { started, m2, half, complete }
    content: {},         // content_id -> play_started count
    ads: {},             // placement -> { request, loaded, show, closed, failed, skipped }
    adCodes: {},         // "placement|detail" -> count (failed loads / shows)
    adSkips: {},         // "placement|reason" -> count
    errors: {},          // signature -> { e, where, type, detail, count, first, last, versions, sample }
    updatedAt: null,
  };
}

function opsInc(map, key, by, maxKeys) {
  if (!key && key !== 0) return;
  const k = String(key);
  if (map[k] === undefined && Object.keys(map).length >= (maxKeys || OPS_MAX_KEYS)) return;
  map[k] = (Number(map[k]) || 0) + (by || 1);
}

function opsIsError(name) {
  return name === "app_crash" || name === "app_error" || name === "player_error"
    || name.endsWith("_error") || name.endsWith("_failed") || name.startsWith("play_resume_failed");
}

const OPS_AD_PLACEMENT_EVENTS = {
  ad_request: "request",
  ad_loaded: "loaded",
  ad_show: "show",
  ad_closed: "closed",
  ad_failed: "failed",
  ad_skipped_by_policy: "skipped",
  support_ad_request: "request",
  support_ad_loaded: "loaded",
  support_ad_show: "show",
  support_ad_closed: "closed",
  support_ad_failed: "failed",
};

function opsAdPlacement(name, p) {
  if (name.startsWith("support_ad_")) return "support";
  const pl = opsText(p.placement, 30);
  return pl || "other";
}

/** Folds one validated batch into an aggregate document (pure, used for shards and merges). */
function opsFold(agg, batch, env) {
  agg.batches = (Number(agg.batches) || 0) + 1;
  if (batch.anon) opsHllAdd(agg.users, batch.anon);
  for (const ev of batch.events) {
    const name = ev.e;
    const p = ev.p;
    opsInc(agg.events, name, 1, 300);

    if (name === "session_start") {
      agg.hours[opsLocalHour(env, ev.t)] = (Number(agg.hours[opsLocalHour(env, ev.t)]) || 0) + 1;
      opsInc(agg.versions, batch.v || "?", 1, 60);
      opsInc(agg.os, String(batch.os || "?"), 1, 40);
    } else if (name === "screen_view") {
      opsInc(agg.screens, opsText(p.screen, 40) || "?", 1, 60);
    } else if (name === "perf") {
      const metric = opsText(p.metric, 40);
      const ms = Number(p.ms);
      if (metric && Number.isFinite(ms) && ms >= 0 && ms < 600000) {
        const cur = agg.perf[metric] || (Object.keys(agg.perf).length < 40 ? (agg.perf[metric] = [0, 0]) : null);
        if (cur) { cur[0] += ms; cur[1] += 1; }
      }
    } else if (name === "play_started" || name === "watch_2m" || name === "watch_50" || name === "watch_complete") {
      const type = opsText(p.type, 20) || "other";
      const row = agg.plays[type] || (Object.keys(agg.plays).length < 8 ? (agg.plays[type] = { started: 0, m2: 0, half: 0, complete: 0 }) : null);
      if (row) {
        const col = name === "play_started" ? "started" : name === "watch_2m" ? "m2" : name === "watch_50" ? "half" : "complete";
        row[col] = (Number(row[col]) || 0) + 1;
      }
      if (name === "play_started") {
        const id = opsText(p.series_id, 80) || opsText(p.content_id, 80);
        if (id) opsInc(agg.content, id, 1, OPS_MAX_CONTENT);
      }
    }

    const adCol = OPS_AD_PLACEMENT_EVENTS[name];
    if (adCol) {
      const pl = opsAdPlacement(name, p);
      const row = agg.ads[pl] || (Object.keys(agg.ads).length < 12
        ? (agg.ads[pl] = { request: 0, loaded: 0, show: 0, closed: 0, failed: 0, skipped: 0 }) : null);
      if (row) row[adCol] = (Number(row[adCol]) || 0) + 1;
      if (adCol === "failed") opsInc(agg.adCodes, pl + "|" + (opsText(p.detail, 30) || "?"), 1, 80);
      if (adCol === "skipped") opsInc(agg.adSkips, pl + "|" + (opsText(p.detail, 30) || "?"), 1, 80);
    }

    if (opsIsError(name)) {
      const where = opsText(p.where, 100);
      const type = opsText(p.type, 80);
      const detail = opsText(p.detail || p.placement || p.slot || p.kind || p.code, 60);
      const sig = [name, where, type, detail].join("|").slice(0, 260);
      const iso = new Date(ev.t).toISOString();
      let row = agg.errors[sig];
      if (!row) {
        if (Object.keys(agg.errors).length >= OPS_MAX_ERROR_SIGS) continue;
        const sample = {};
        for (const [k, v] of Object.entries(p).slice(0, 12)) sample[k] = opsText(v, 160);
        row = agg.errors[sig] = { e: name, where, type, detail, count: 0, first: iso, last: iso, versions: {}, os: {}, sample };
      }
      row.count += 1;
      if (iso < row.first) row.first = iso;
      if (iso > row.last) row.last = iso;
      opsInc(row.versions, batch.v || "?", 1, 20);
      opsInc(row.os, String(batch.os || "?"), 1, 20);
    }
  }
  agg.updatedAt = new Date().toISOString();
  return agg;
}

function opsMergeCounts(into, from, maxKeys) {
  if (!from || typeof from !== "object") return;
  for (const [k, v] of Object.entries(from)) opsInc(into, k, Number(v) || 0, maxKeys);
}

/** Merges aggregate `b` into `a` (both opsEmptyAgg-shaped). */
function opsMerge(a, b) {
  if (!b || typeof b !== "object") return a;
  a.batches += Number(b.batches) || 0;
  opsMergeCounts(a.events, b.events, 300);
  opsHllMerge(a.users, b.users);
  if (Array.isArray(b.hours)) for (let i = 0; i < 24; i++) a.hours[i] += Number(b.hours[i]) || 0;
  opsMergeCounts(a.versions, b.versions, 60);
  opsMergeCounts(a.os, b.os, 40);
  opsMergeCounts(a.screens, b.screens, 60);
  for (const [m, v] of Object.entries(b.perf || {})) {
    const cur = a.perf[m] || (a.perf[m] = [0, 0]);
    cur[0] += Number(v && v[0]) || 0;
    cur[1] += Number(v && v[1]) || 0;
  }
  for (const [t, row] of Object.entries(b.plays || {})) {
    const cur = a.plays[t] || (a.plays[t] = { started: 0, m2: 0, half: 0, complete: 0 });
    for (const k of ["started", "m2", "half", "complete"]) cur[k] += Number(row && row[k]) || 0;
  }
  opsMergeCounts(a.content, b.content, 2000);
  for (const [pl, row] of Object.entries(b.ads || {})) {
    const cur = a.ads[pl] || (a.ads[pl] = { request: 0, loaded: 0, show: 0, closed: 0, failed: 0, skipped: 0 });
    for (const k of Object.keys(cur)) cur[k] += Number(row && row[k]) || 0;
  }
  opsMergeCounts(a.adCodes, b.adCodes, 400);
  opsMergeCounts(a.adSkips, b.adSkips, 400);
  for (const [sig, row] of Object.entries(b.errors || {})) {
    if (!row || typeof row !== "object") continue;
    const cur = a.errors[sig];
    if (!cur) {
      if (Object.keys(a.errors).length >= OPS_MAX_ERROR_SIGS * 4) continue;
      const copy = clone(row);
      copy.count = Number(copy.count) || 0;
      if (!copy.versions || typeof copy.versions !== "object") copy.versions = {};
      if (!copy.os || typeof copy.os !== "object") copy.os = {};
      a.errors[sig] = copy;
      continue;
    }
    cur.count += Number(row.count) || 0;
    if (row.first && row.first < cur.first) cur.first = row.first;
    if (row.last && row.last > cur.last) { cur.last = row.last; cur.sample = row.sample || cur.sample; }
    opsMergeCounts(cur.versions, row.versions, 40);
    opsMergeCounts(cur.os, row.os, 40);
  }
  if (b.updatedAt && (!a.updatedAt || b.updatedAt > a.updatedAt)) a.updatedAt = b.updatedAt;
  return a;
}

// ------------------------------------------------------------------ ingest

/** Validates a CigramAnalytics batch; returns null when it is not one. */
function opsParseBatch(input) {
  if (!input || typeof input !== "object" || !Array.isArray(input.events)) return null;
  const now = Date.now();
  const events = [];
  for (const raw of input.events.slice(0, OPS_MAX_EVENTS)) {
    if (!raw || typeof raw !== "object") continue;
    const e = cleanString(raw.e);
    if (!OPS_EVENT_RE.test(e)) continue;
    let t = Number(raw.t);
    // device clocks drift: anything outside [-3 days, +10 min] is filed under "now"
    if (!Number.isFinite(t) || t < now - 3 * 86400000 || t > now + 600000) t = now;
    const p = {};
    if (raw.p && typeof raw.p === "object") {
      for (const [k, v] of Object.entries(raw.p).slice(0, 16)) {
        if (/^[A-Za-z0-9_]{1,30}$/.test(k)) p[k] = opsText(v, 160);
      }
    }
    events.push({ e, t, p });
  }
  if (!events.length) return null;
  return {
    v: opsText(input.v, 20),
    anon: opsText(input.anon, 64),
    os: opsText(input.os, 4),
    events,
  };
}

function opsInboxPrefix(env, day) {
  return adminKey(env, "ops/inbox/" + (day ? day + "/" : ""));
}

/** One object per batch and day: never a read-modify-write, so nothing is lost under load. */
async function opsRecordBatch(env, batch) {
  const byDay = {};
  for (const ev of batch.events) {
    const day = opsDay(env, ev.t);
    (byDay[day] || (byDay[day] = [])).push(ev);
  }
  const bucket = adminBucket(env);
  for (const [day, events] of Object.entries(byDay)) {
    const id = Date.now() + "-" + Math.random().toString(36).slice(2, 10);
    await bucket.put(opsInboxPrefix(env, day) + id + ".json",
      JSON.stringify({ v: batch.v, anon: batch.anon, os: batch.os, events }),
      { httpMetadata: { contentType: "application/json; charset=utf-8" } });
  }
}

function opsInboxId(key) {
  return key.slice(key.lastIndexOf("/") + 1).replace(/\.json$/, "");
}

function opsInboxDay(key) {
  const parts = key.split("/");
  return parts.length >= 2 ? parts[parts.length - 2] : "";
}

async function opsReadInboxObjects(env, objects) {
  const bucket = adminBucket(env);
  const out = [];
  for (let i = 0; i < objects.length; i += 40) {
    const chunk = objects.slice(i, i + 40);
    const got = await Promise.all(chunk.map((o) => bucket.get(o.key).then(async (obj) => {
      if (!obj) return null;
      try {
        const batch = JSON.parse(await obj.text());
        return Array.isArray(batch.events) ? { key: o.key, batch } : { key: o.key, batch: null };
      } catch {
        return { key: o.key, batch: null }; // unreadable: deleted by the compaction
      }
    })));
    for (const g of got) if (g) out.push(g);
  }
  return out;
}

async function opsAcquireLease(env, owner) {
  try {
    await mutateJson(adminBucket(env), adminKey(env, "ops/compact_lock.json"), {}, (doc) => {
      const now = Date.now();
      if (doc && doc.owner && doc.owner !== owner && Number(doc.until) > now) {
        throw opsFail("busy", "compaction running");
      }
      return { doc: { owner, until: now + OPS_LEASE_MS } };
    }, 2);
    return true;
  } catch {
    return false;
  }
}

async function opsReleaseLease(env, owner) {
  try {
    await mutateJson(adminBucket(env), adminKey(env, "ops/compact_lock.json"), {}, (doc) => {
      if (doc && doc.owner === owner) return { doc: { owner: "", until: 0 } };
      throw opsFail("not_owner", "");
    }, 2);
  } catch {
    // an expired lease frees itself
  }
}

/** Folds up to `limit` inbox batches into their day aggregates, then deletes them. */
async function opsCompact(env, limit) {
  const owner = Math.random().toString(36).slice(2);
  if (!(await opsAcquireLease(env, owner))) return { folded: 0, busy: true };
  const bucket = adminBucket(env);
  let folded = 0;
  try {
    const page = await bucket.list({ prefix: opsInboxPrefix(env, ""), limit: Math.min(1000, limit) });
    const objects = page.objects || [];
    if (!objects.length) return { folded: 0 };
    const items = await opsReadInboxObjects(env, objects);
    try { await liveFoldItems(env, items); } catch { /* live counters are best effort */ }
    const byDay = {};
    for (const it of items) {
      const day = opsInboxDay(it.key);
      (byDay[day] || (byDay[day] = [])).push(it);
    }
    for (const [day, list] of Object.entries(byDay)) {
      await mutateJson(bucket, opsKey(env, day, "agg"), opsEmptyAgg(day), (doc) => {
        const agg = opsMerge(opsEmptyAgg(day), doc);
        const done = Array.isArray(doc && doc.done) ? doc.done.slice() : [];
        const seen = new Set(done);
        for (const it of list) {
          const id = opsInboxId(it.key);
          if (seen.has(id)) continue; // folded by a run that stopped before deleting
          seen.add(id);
          done.push(id);
          if (it.batch) opsFold(agg, opsParseBatch(it.batch) || { events: [] }, env);
        }
        agg.done = done.slice(-OPS_DONE_RING);
        return { doc: agg };
      });
      folded += list.length;
      await bucket.delete(list.map((it) => it.key));
    }
    return { folded, more: Boolean(page.truncated) || objects.length >= limit };
  } finally {
    await opsReleaseLease(env, owner);
  }
}

async function handleTelemetryEvents(request, env, url, ctx) {
  const accepted = () => json({ ok: true });
  try {
    const key = cleanString(request.headers.get("x-cigram-key"));
    const expected = cleanString(env.TELEMETRY_INGEST_KEY) || OPS_DEFAULT_INGEST_KEY;
    if (!timingSafeEqualText(key, expected)) return json({ ok: false, error: "unauthorized" }, 401);
    const length = Number(request.headers.get("content-length")) || 0;
    if (length > OPS_MAX_BODY) return json({ ok: false, error: "too_large" }, 413);
    const text = await request.text();
    if (text.length > OPS_MAX_BODY) return json({ ok: false, error: "too_large" }, 413);
    let input = null;
    try {
      input = JSON.parse(text);
    } catch {
      return json({ ok: false, error: "invalid_json" }, 400); // 400: the app drops the batch
    }
    const batch = opsParseBatch(input);
    if (!batch) return accepted();
    const work = [
      opsRecordBatch(env, batch).catch((error) => updLog("telemetry_failed", { message: String(error && error.message) })),
    ];
    const forward = cleanString(env.ANALYTICS_FORWARD_URL);
    if (forward.startsWith("https://")) {
      work.push(
        fetch(forward, {
          method: "POST",
          headers: { "content-type": "application/json", "x-cigram-key": key },
          body: text,
        }).catch(() => null)
      );
    }
    const all = Promise.all(work);
    if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(all);
    else await all;
  } catch {
    // telemetry must never fail the app
  }
  return accepted();
}

// ------------------------------------------------------------------ reading

async function opsReadAgg(env, day) {
  const obj = await adminBucket(env).get(opsKey(env, day, "agg"));
  const agg = opsEmptyAgg(day);
  if (!obj) return { agg, done: new Set() };
  try {
    const doc = JSON.parse(await obj.text());
    return { agg: opsMerge(agg, doc), done: new Set(Array.isArray(doc.done) ? doc.done : []) };
  } catch {
    return { agg, done: new Set() };
  }
}

async function opsReadRange(env, days) {
  // fold what is waiting first (no-op when the cron already did, or another fold is running)
  try {
    await opsCompact(env, OPS_COMPACT_ADMIN);
  } catch {
    // reading must still work
  }
  const list = opsDays(env, days);
  const reads = await Promise.all(list.map((d) => opsReadAgg(env, d)));
  const byDay = {};
  for (let i = 0; i < list.length; i++) byDay[list[i]] = reads[i];

  // batches not folded yet are counted live (read-only)
  let pendingCount = 0;
  let partial = false;
  try {
    const page = await adminBucket(env).list({ prefix: opsInboxPrefix(env, ""), limit: 1000 });
    const waiting = (page.objects || []).filter((o) => byDay[opsInboxDay(o.key)]);
    partial = Boolean(page.truncated) || waiting.length > OPS_PENDING_READ;
    const items = await opsReadInboxObjects(env, waiting.slice(0, OPS_PENDING_READ));
    for (const it of items) {
      const slot = byDay[opsInboxDay(it.key)];
      if (!slot || !it.batch || slot.done.has(opsInboxId(it.key))) continue;
      const batch = opsParseBatch(it.batch);
      if (batch) {
        opsFold(slot.agg, batch, env);
        pendingCount++;
      }
    }
  } catch {
    partial = true;
  }

  const total = opsEmptyAgg("");
  const daily = [];
  for (const day of list) {
    const d = byDay[day].agg;
    opsMerge(total, d);
    daily.push({
      day,
      users: opsHllCount(d.users),
      sessions: Number(d.events.session_start) || 0,
      plays: Number(d.events.play_started) || 0,
      errors: Object.values(d.errors).reduce((s, r) => s + (Number(r.count) || 0), 0),
      adShows: Object.values(d.ads).reduce((s, r) => s + (Number(r.show) || 0), 0),
    });
  }
  daily.reverse(); // oldest first for charts
  return { total, daily, pending: pendingCount, partial };
}

function opsTop(map, n) {
  return Object.entries(map || {})
    .map(([k, v]) => ({ key: k, count: Number(v) || 0 }))
    .sort((a, b) => b.count - a.count)
    .slice(0, n);
}

async function handleOpsSummary(request, env, url) {
  const days = opsDaysParam(url);
  const { total, daily, pending, partial } = await opsReadRange(env, days);
  const ev = total.events;
  const perf = {};
  for (const [m, v] of Object.entries(total.perf)) {
    if (v[1] > 0) perf[m] = { avgMs: Math.round(v[0] / v[1]), samples: v[1] };
  }
  const adTotals = { request: 0, loaded: 0, show: 0, failed: 0, skipped: 0 };
  for (const row of Object.values(total.ads)) for (const k of Object.keys(adTotals)) adTotals[k] += Number(row[k]) || 0;
  const errorCount = Object.values(total.errors).reduce((s, r) => s + (Number(r.count) || 0), 0);
  const crashes = Object.values(total.errors).filter((r) => r.e === "app_crash").reduce((s, r) => s + r.count, 0);
  return okResponse({
    days,
    pending,
    partial,
    hasData: total.batches > 0,
    updatedAt: total.updatedAt,
    users: opsHllCount(total.users),
    sessions: Number(ev.session_start) || 0,
    appOpens: Number(ev.app_open) || 0,
    engagedDays: Number(ev.engaged_viewer_day) || 0,
    errors: errorCount,
    crashes,
    plays: total.plays,
    perf,
    ads: adTotals,
    hours: total.hours,
    daily,
    versions: opsTop(total.versions, 12),
    os: opsTop(total.os, 12),
    screens: opsTop(total.screens, 15),
    topContent: opsTop(total.content, 15),
    topEvents: opsTop(total.events, 25),
  });
}

async function opsReadState(env) {
  const cur = await r2ReadJson(adminBucket(env), adminKey(env, OPS_STATE_NAME), { v: 1, resolved: {} });
  const doc = cur.doc && typeof cur.doc === "object" ? cur.doc : { v: 1, resolved: {} };
  if (!doc.resolved || typeof doc.resolved !== "object") doc.resolved = {};
  return doc;
}

async function handleOpsErrors(request, env, url) {
  const days = opsDaysParam(url);
  const showAll = truthy(url.searchParams.get("all"));
  const [{ total }, state] = await Promise.all([opsReadRange(env, days), opsReadState(env)]);
  const items = [];
  let hidden = 0;
  for (const [sig, row] of Object.entries(total.errors)) {
    const resolvedAt = cleanString(state.resolved[sig]);
    const resolved = Boolean(resolvedAt) && row.last <= resolvedAt;
    if (resolved && !showAll) {
      hidden++;
      continue;
    }
    items.push({
      sig,
      event: row.e,
      where: row.where,
      type: row.type,
      detail: row.detail,
      count: row.count,
      first: row.first,
      last: row.last,
      versions: opsTop(row.versions, 6),
      os: opsTop(row.os, 6),
      sample: row.sample || {},
      resolved,
    });
  }
  items.sort((a, b) => b.count - a.count || (a.last < b.last ? 1 : -1));
  return okResponse({ days, items, hidden, total: items.reduce((s, r) => s + r.count, 0) });
}

async function handleOpsErrorsResolve(request, env) {
  const input = await readRequestData(request);
  const all = truthy(input.all);
  const sig = opsText(input.sig, 300);
  if (!all && !sig) throw opsFail("invalid_input", "حدد الخطأ المطلوب أو اختر «الكل».");
  let names = [];
  if (all) {
    const { total } = await opsReadRange(env, 30);
    names = Object.keys(total.errors);
  }
  const now = new Date().toISOString();
  await mutateJson(adminBucket(env), adminKey(env, OPS_STATE_NAME), { v: 1, resolved: {} }, (doc) => {
    if (!doc.resolved || typeof doc.resolved !== "object") doc.resolved = {};
    if (all) for (const n of names) doc.resolved[n] = now;
    else doc.resolved[sig] = now;
    // keep the file small: forget marks older than 60 days
    const cutoff = new Date(Date.now() - 60 * 86400000).toISOString();
    for (const [k, v] of Object.entries(doc.resolved)) if (String(v) < cutoff) delete doc.resolved[k];
  });
  return okResponse({ resolvedAt: now, count: all ? names.length : 1 }, all ? "أُخفيت كل الأخطاء الحالية." : "عُلِّم الخطأ كمحلول.");
}

// ------------------------------------------------------------------ ad config

function opsValidateAdsConfig(input, previous) {
  const out = {};
  for (const k of OPS_ADS_BOOLS) {
    const v = input[k];
    if (v === undefined || v === null || v === "") {
      if (previous && typeof previous[k] === "boolean") out[k] = previous[k];
      continue;
    }
    if (typeof v === "boolean") out[k] = v;
    else if (v === "true" || v === "false") out[k] = v === "true";
    else throw opsFail("invalid_input", "القيمة " + k + " يجب أن تكون تشغيل أو إيقاف.");
  }
  for (const [k, [lo, hi]] of Object.entries(OPS_ADS_NUMBERS)) {
    const v = input[k];
    if (v === undefined || v === null || v === "") {
      if (previous && typeof previous[k] === "number") out[k] = previous[k];
      continue;
    }
    const n = Number(v);
    if (!Number.isInteger(n) || n < lo || n > hi) {
      throw opsFail("invalid_range", "القيمة " + k + " يجب أن تكون رقماً صحيحاً بين " + lo + " و" + hi + " (التطبيق يتجاهل غيرها).");
    }
    out[k] = n;
  }
  const tag = cleanString(input.tv_vast_tag !== undefined ? input.tv_vast_tag : previous && previous.tv_vast_tag);
  if (tag) {
    if (!tag.startsWith("https://") || tag.length > 2000) throw opsFail("invalid_input", "رابط VAST يجب أن يبدأ بـ https://");
    out.tv_vast_tag = tag;
  }
  const ids = input.ad_free_user_ids !== undefined ? input.ad_free_user_ids : previous && previous.ad_free_user_ids;
  if (ids !== undefined && ids !== null) {
    if (!Array.isArray(ids)) throw opsFail("invalid_input", "قائمة المستخدمين بلا إعلانات غير صالحة.");
    const clean = [];
    for (const id of ids) {
      const s = opsText(id, 80);
      if (s && !clean.includes(s)) clean.push(s);
    }
    if (clean.length > OPS_ADS_MAX_FREE_IDS) throw opsFail("invalid_input", "الحد الأقصى " + OPS_ADS_MAX_FREE_IDS + " مستخدم بلا إعلانات.");
    out.ad_free_user_ids = clean;
  }
  const domain = cleanString(input.developer_domain !== undefined ? input.developer_domain : previous && previous.developer_domain)
    .replace(/^https?:\/\//i, "").replace(/\/.*$/, "").toLowerCase();
  if (domain) {
    if (!/^[a-z0-9.-]{3,120}$/.test(domain) || !domain.includes(".")) throw opsFail("invalid_input", "نطاق موقع المطوّر غير صالح.");
    out.developer_domain = domain;
  }
  return out;
}

/** Public: the app only reads known keys and treats {} as "use cached copy / defaults". */
async function handleAdsConfigPublic(request, env) {
  const cur = await r2ReadJson(adminBucket(env), adminKey(env, OPS_ADS_CONFIG_NAME), null);
  const doc = cur.doc && typeof cur.doc === "object" ? cur.doc : {};
  const out = {};
  for (const k of OPS_ADS_BOOLS) if (typeof doc[k] === "boolean") out[k] = doc[k];
  for (const k of Object.keys(OPS_ADS_NUMBERS)) if (typeof doc[k] === "number") out[k] = doc[k];
  if (doc.tv_vast_tag) out.tv_vast_tag = doc.tv_vast_tag;
  if (Array.isArray(doc.ad_free_user_ids)) out.ad_free_user_ids = doc.ad_free_user_ids;
  return json(out);
}

async function handleAdsConfigGet(request, env) {
  const cur = await r2ReadJson(adminBucket(env), adminKey(env, OPS_ADS_CONFIG_NAME), null);
  const defaults = {};
  for (const k of OPS_ADS_BOOLS) defaults[k] = true;
  for (const [k, v] of Object.entries(OPS_ADS_NUMBERS)) defaults[k] = v[2];
  return okResponse({
    exists: Boolean(cur.exists),
    config: cur.doc || {},
    defaults,
    ranges: OPS_ADS_NUMBERS,
  });
}

async function handleAdsConfigSave(request, env) {
  const input = await readRequestData(request);
  const saved = await mutateJson(adminBucket(env), adminKey(env, OPS_ADS_CONFIG_NAME), {}, (doc) => {
    const next = opsValidateAdsConfig(input, doc || {});
    next.updatedAt = new Date().toISOString();
    next.version = (Number(doc && doc.version) || 0) + 1;
    return { doc: next };
  });
  updLog("ads_config_saved", { ads_enabled: saved.doc.ads_enabled, version: saved.doc.version });
  return okResponse({ config: saved.doc }, saved.doc.ads_enabled === false ? "تم إيقاف الإعلانات." : "تم حفظ إعدادات الإعلانات.");
}

// ------------------------------------------------------------------ ad check

const OPS_ADMOB_CODES = {
  "0": ["خطأ داخلي في AdMob", "غالباً مؤقت. إن استمر: حدّث مكتبة Google Mobile Ads وتأكد من وجود WebView محدّث على الأجهزة."],
  "1": ["طلب غير صالح", "معرّف الوحدة لا يتبع App ID الموجود في التطبيق، أو الوحدة محذوفة. طابق المعرّفات في حساب AdMob."],
  "2": ["خطأ شبكة", "مشكلة اتصال عند المستخدم. لا علاج من جهتك إلا إن ارتفعت النسبة كثيراً."],
  "3": ["لا يوجد إعلان (No fill)", "AdMob لم يُرجع إعلاناً: حد عرض على الحساب، أو app-ads.txt غير موثّق، أو تطبيق جديد غير مراجَع، أو طلب ضعيف في منطقة المستخدمين."],
  "8": ["App ID غير صالح", "قيمة APPLICATION_ID في Manifest لا تطابق الحساب."],
  "9": ["لا توجد شبكة وسيطة متاحة", "لا علاقة لها إن لم تستخدم Mediation."],
};

function opsAdVerdicts(total, sessions) {
  const v = [];
  const rows = total.ads;
  const placements = Object.keys(rows);
  const sum = (k) => placements.reduce((s, p) => s + (Number(rows[p][k]) || 0), 0);
  const req = sum("request");
  const loaded = sum("loaded");
  const shows = sum("show");

  if (sessions === 0 && req === 0) {
    v.push({ level: "info", title: "لا توجد بيانات بعد", text: "لم يصل أي حدث من نسخة التطبيق الجديدة. تأكد من نشر الـ Worker ومن أن المستخدمين على النسخة التي تُرسل إلى /telemetry/events." });
    return v;
  }
  if (sessions > 20 && req === 0) {
    v.push({ level: "bad", title: "لا تُطلب إعلانات إطلاقاً", text: "هناك جلسات لكن لا يوجد أي ad_request: الإعلانات معطّلة من الإعدادات، أو كل الطلبات تُرفض بسياسة الحدود قبل الطلب." });
  }
  if (req >= 20) {
    const fill = loaded / req;
    if (fill < 0.3) v.push({ level: "bad", title: "نسبة التعبئة منخفضة جداً (" + Math.round(fill * 100) + "%)", text: "أغلب الطلبات تفشل. انظر أكواد الفشل أدناه؛ الكود 3 يعني أن المشكلة في الحساب وليست في الكود." });
    else if (fill < 0.7) v.push({ level: "warn", title: "نسبة التعبئة متوسطة (" + Math.round(fill * 100) + "%)", text: "طبيعي في بعض المناطق، لكن راقب الكود 3." });
    else v.push({ level: "good", title: "نسبة التعبئة جيدة (" + Math.round(fill * 100) + "%)", text: "AdMob يُرجع إعلانات لأغلب الطلبات." });
  }
  if (loaded >= 10) {
    const rate = shows / loaded;
    if (rate < 0.4) v.push({ level: "warn", title: "إعلانات تُحمَّل ولا تُعرض (" + Math.round(rate * 100) + "%)", text: "الإعلان جاهز لكن نافذة العرض تنتهي قبل ظهوره، أو المستخدم يغادر قبل نقطة الإعلان. هذا يخفض الأداء لدى AdMob." });
  }
  const codes = {};
  for (const [k, n] of Object.entries(total.adCodes)) {
    const m = /load:(-?\d+)/.exec(k);
    if (m) codes[m[1]] = (codes[m[1]] || 0) + n;
  }
  const failTotal = Object.values(codes).reduce((s, n) => s + n, 0);
  for (const [code, n] of Object.entries(codes).sort((a, b) => b[1] - a[1])) {
    if (failTotal < 5 || n / failTotal < 0.15) continue;
    const info = OPS_ADMOB_CODES[code] || ["كود " + code, "راجع وثائق AdMob لهذا الكود."];
    v.push({ level: code === "2" ? "warn" : "bad", title: info[0] + " — " + n + " مرة (" + Math.round((n / failTotal) * 100) + "% من الفشل)", text: info[1] });
  }
  const skips = {};
  for (const [k, n] of Object.entries(total.adSkips)) {
    const reason = k.split("|")[1] || "?";
    skips[reason] = (skips[reason] || 0) + n;
  }
  const skipTotal = Object.values(skips).reduce((s, n) => s + n, 0);
  const skipText = {
    cooldown: "فترة الانتظار بين إعلانين",
    hourly_cap: "حد الإعلانات في الساعة",
    session_cap: "حد الإعلانات في الجلسة",
    episode_gap: "الفاصل بين إعلانات الحلقات",
    other_ad_showing: "إعلان آخر معروض",
    other_ad_loading: "إعلان آخر قيد التحميل",
  };
  if (skipTotal > 0 && skipTotal > shows) {
    const top = Object.entries(skips).sort((a, b) => b[1] - a[1])[0];
    v.push({ level: "warn", title: "السياسة تمنع إعلانات أكثر مما تُعرض", text: "أكثر سبب: " + (skipText[top[0]] || top[0]) + " (" + top[1] + " مرة). خفّف الحد من إعدادات الإعلانات إن أردت عرضاً أكثر." });
  }
  return v;
}

async function opsCheckAppAdsTxt(domain) {
  if (!domain) return { checked: false, ok: false, message: "أدخل نطاق موقع المطوّر المسجّل في Google Play لفحص app-ads.txt." };
  try {
    const res = await fetch("https://" + domain + "/app-ads.txt", { redirect: "follow", cf: { cacheTtl: 0 } });
    if (res.status !== 200) return { checked: true, ok: false, message: "الملف غير موجود (HTTP " + res.status + ") على https://" + domain + "/app-ads.txt" };
    const text = (await res.text()).slice(0, 65536);
    const line = text.split(/\r?\n/).find((l) => l.toLowerCase().replace(/\s+/g, "").startsWith("google.com," + OPS_ADMOB_PUB + ","));
    if (!line) return { checked: true, ok: false, message: "الملف موجود لكن لا يحتوي سطر الناشر " + OPS_ADMOB_PUB + "." };
    const norm = line.toLowerCase().replace(/\s+/g, "");
    const direct = norm.includes(",direct");
    const cert = norm.includes(OPS_ADMOB_CERT);
    return {
      checked: true,
      ok: direct && cert,
      line: line.trim(),
      message: direct && cert ? "app-ads.txt سليم." : "السطر موجود لكن ينقصه " + (!direct ? "DIRECT " : "") + (!cert ? OPS_ADMOB_CERT : ""),
    };
  } catch (error) {
    return { checked: true, ok: false, message: "تعذر الوصول إلى الموقع: " + String(error && error.message) };
  }
}

async function handleOpsAdsCheck(request, env, url) {
  const days = opsDaysParam(url);
  const [{ total }, cfgRead] = await Promise.all([
    opsReadRange(env, days),
    r2ReadJson(adminBucket(env), adminKey(env, OPS_ADS_CONFIG_NAME), null),
  ]);
  const cfg = cfgRead.doc && typeof cfgRead.doc === "object" ? cfgRead.doc : {};
  const domain = cleanString(url.searchParams.get("domain")) || cleanString(cfg.developer_domain);
  const sessions = Number(total.events.session_start) || 0;
  const verdicts = [];
  if (!cfgRead.exists) {
    verdicts.push({ level: "info", title: "لا يوجد إعداد محفوظ", text: "التطبيق يعمل بالقيم الافتراضية داخل الكود. احفظ الإعدادات مرة واحدة لتتحكم بها من هنا." });
  } else if (cfg.ads_enabled === false) {
    verdicts.push({ level: "bad", title: "الإعلانات موقوفة من اللوحة", text: "المفتاح الرئيسي على «متوقف». لن يُطلب أي إعلان AdMob." });
  } else if (cfg.max_ads_per_hour === 0) {
    verdicts.push({ level: "bad", title: "حد الإعلانات في الساعة = 0", text: "هذا يوقف إعلانات المشغّل والحلقات بالكامل." });
  }
  for (const k of ["player_ads_enabled", "mid_roll_enabled", "episode_ads_enabled", "support_ads_enabled"]) {
    if (cfg[k] === false) verdicts.push({ level: "warn", title: "موضع موقوف: " + k, text: "هذا الموضع لا يعرض إعلانات." });
  }
  verdicts.push(...opsAdVerdicts(total, sessions));
  const appAds = await opsCheckAppAdsTxt(domain);
  verdicts.push({
    level: !appAds.checked ? "info" : appAds.ok ? "good" : "bad",
    title: "app-ads.txt",
    text: appAds.message,
  });
  const placements = Object.entries(total.ads).map(([name, row]) => ({
    name,
    request: row.request, loaded: row.loaded, show: row.show, closed: row.closed, failed: row.failed, skipped: row.skipped,
    fillPercent: row.request > 0 ? Math.round((row.loaded / row.request) * 100) : null,
    showPercent: row.loaded > 0 ? Math.round((row.show / row.loaded) * 100) : null,
  }));
  return okResponse({
    days,
    sessions,
    placements,
    failures: opsTop(total.adCodes, 20),
    skips: opsTop(total.adSkips, 20),
    verdicts,
    appAds,
    config: cfg,
  });
}

// =============================================================================
// LIVE (v7) — live counters for the new admin home + notification composer
// =============================================================================
// ADDITIVE: nothing above was changed except two one-line hooks (opsCompact and the cron).
//
// ADMIN (ADMIN_TOKEN):
//   GET  /admin/live      active now, watching now, today's users / sessions / plays,
//                         ad shows today per placement, per-minute history (last 3 h)
//   POST /admin/notify    { title, body, kind, show_mode, end_at, push } -> in-app message
//                         (same storage as /admin/announcements) + optional push (FCM / webhook)
//
// HOW "LIVE" IS MEASURED (no change in the user app is needed):
//   every telemetry batch the app sends is a "seen" signal for its anonymous id. The last
//   play / stop event inside the batch decides whether that user is watching:
//     watching = last signal was a play milestone (play_started, watch_30s, watch_2m, watch_25,
//                watch_50, watch_75, or a player ad) less than 35 min ago (milestones of a
//                2 h film are at most 30 min apart) and nothing stopped it since
//     active   = watching, or seen in the last 5 min and the app was not sent to background
//   The cron compaction folds batches into ops/live.json (under its existing lease) and
//   stores one history point per minute. /admin/live also reads the batches still waiting
//   in the inbox, so a number moves as soon as a batch arrives (the app sends a batch every
//   12 events, every 30 s while events happen, and when it goes to background).
//
// OPTIONAL ENV:
//   FCM_NOTIFY_TOPIC   topic for /admin/notify pushes (default FCM_TOPIC, then "cigram_updates")

const LIVE_NAME = "ops/live.json";
const LIVE_ACTIVE_MS = 5 * 60000;
const LIVE_WATCH_MS = 35 * 60000;
const LIVE_KEEP_MS = 3 * 3600000;
const LIVE_MAX_USERS = 6000;
const LIVE_HIST_MAX = 180;
const LIVE_PENDING_READ = 120;
const LIVE_CACHE_MS = 2000;
const LIVE_PLAY_EVENTS = new Set([
  "play_started", "play_resumed", "watch_30s", "watch_2m", "watch_25", "watch_50", "watch_75", "engaged_viewer_day",
]);
const LIVE_STOP_EVENTS = new Set(["watch_complete", "player_close", "play_stopped", "session_end"]);
let liveMemo = null; // { at, data } — per isolate, keeps 1 s polling cheap

function liveEmpty() {
  return { v: 1, updatedAt: null, users: {}, hist: [] };
}

function liveNormalize(doc) {
  const d = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};
  return {
    v: 1,
    updatedAt: d.updatedAt || null,
    users: d.users && typeof d.users === "object" && !Array.isArray(d.users) ? d.users : {},
    hist: Array.isArray(d.hist) ? d.hist.filter((p) => Array.isArray(p) && p.length >= 3) : [],
  };
}

/** Arrival time of an inbox batch: its key is "<ms>-<rand>.json" (written on receipt). */
function liveArrival(key) {
  const ms = Number(opsInboxId(key).split("-")[0]);
  return Number.isFinite(ms) && ms > 0 ? ms : Date.now();
}

/** Folds one parsed batch (opsParseBatch shape) into the presence map. Idempotent. */
function liveFold(users, batch, at) {
  if (!batch || !batch.anon || !Array.isArray(batch.events)) return;
  const cur = users[batch.anon] || { s: 0, w: 0, st: "b", c: "" };
  let state = "";
  let content = "";
  for (const ev of batch.events) {
    const name = ev.e;
    const p = ev.p || {};
    if (LIVE_PLAY_EVENTS.has(name)) {
      state = "w";
      content = opsText(p.series_id, 80) || opsText(p.content_id, 80) || content;
    } else if (LIVE_STOP_EVENTS.has(name)) {
      state = "b";
    } else if (name === "app_background") {
      state = "x";
    } else if (name === "app_open" || name === "session_start") {
      state = "b";
    } else if (name === "screen_view") {
      const screen = lc(opsText(p.screen, 40));
      if (screen && screen.indexOf("play") < 0) state = "b";
    } else if (name.startsWith("ad_")) {
      const pl = lc(opsText(p.placement, 30));
      if (pl.indexOf("player") >= 0 || pl.indexOf("mid") >= 0) state = "w";
    }
  }
  if (at >= cur.s) {
    cur.s = at;
    if (state) cur.st = state;
    if (state === "w") {
      cur.w = at;
      if (content) cur.c = content;
    }
  } else if (state === "w" && at > cur.w) {
    cur.w = at; // an older batch that arrived late still proves playback at that time
  }
  users[batch.anon] = cur;
}

function liveCounts(users, now) {
  let active = 0;
  let watching = 0;
  const content = {};
  for (const u of Object.values(users)) {
    if (!u || typeof u !== "object") continue;
    const isWatching = u.st === "w" && now - (Number(u.w) || 0) <= LIVE_WATCH_MS;
    const isActive = isWatching || (u.st !== "x" && now - (Number(u.s) || 0) <= LIVE_ACTIVE_MS);
    if (isActive) active++;
    if (isWatching) {
      watching++;
      if (u.c) content[u.c] = (content[u.c] || 0) + 1;
    }
  }
  return { active, watching, content };
}

function livePrune(doc, now) {
  const entries = Object.entries(doc.users).filter(([, u]) =>
    u && typeof u === "object" && now - Math.max(Number(u.s) || 0, Number(u.w) || 0) <= LIVE_KEEP_MS);
  if (entries.length > LIVE_MAX_USERS) entries.sort((a, b) => (Number(b[1].s) || 0) - (Number(a[1].s) || 0));
  doc.users = Object.fromEntries(entries.slice(0, LIVE_MAX_USERS));
  doc.hist = doc.hist.slice(-LIVE_HIST_MAX);
}

/** Compact read-modify-write of ops/live.json (no pretty printing: the presence map can be large). */
async function liveMutate(env, fn) {
  const bucket = adminBucket(env);
  const key = adminKey(env, LIVE_NAME);
  for (let attempt = 0; attempt < 4; attempt++) {
    const obj = await bucket.get(key);
    let doc = liveEmpty();
    if (obj) {
      try {
        doc = liveNormalize(JSON.parse(await obj.text()));
      } catch {
        doc = liveEmpty(); // a corrupt presence file is simply rebuilt
      }
    }
    fn(doc);
    doc.updatedAt = new Date().toISOString();
    const options = { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } };
    if (obj && obj.etag) options.onlyIf = { etagMatches: obj.etag };
    const ok = await bucket.put(key, JSON.stringify(doc), options);
    if (ok) return doc;
    await sleep(50 * (attempt + 1) + Math.floor(Math.random() * 60));
  }
  return null;
}

/** Hook called by opsCompact with the inbox batches it is about to fold. */
async function liveFoldItems(env, items) {
  if (!items || !items.length) return;
  await liveMutate(env, (doc) => {
    for (const it of items) {
      if (!it || !it.batch) continue;
      const batch = opsParseBatch(it.batch);
      if (batch) liveFold(doc.users, batch, liveArrival(it.key));
    }
    livePrune(doc, Date.now());
  });
}

/** Cron (every minute): one history point [minute_ms, active, watching]. */
async function liveTick(env) {
  await liveMutate(env, (doc) => {
    const now = Date.now();
    livePrune(doc, now);
    const c = liveCounts(doc.users, now);
    const minute = Math.floor(now / 60000) * 60000;
    const last = doc.hist[doc.hist.length - 1];
    if (last && last[0] === minute) doc.hist[doc.hist.length - 1] = [minute, c.active, c.watching];
    else doc.hist.push([minute, c.active, c.watching]);
    doc.hist = doc.hist.slice(-LIVE_HIST_MAX);
  });
}

// ------------------------------------------------------------------ accounts (read-only, auth database)
// The accounts live in the auth Worker's D1 database (cigram-users, table "users"). Bind that same
// database to THIS Worker as AUTH_DB (Settings -> Bindings -> D1 database). Only COUNT queries are run:
// no e-mail, name or password ever leaves the database.
const ACC_CACHE_MS = 15000;
let accMemo = null;

async function liveAccounts(env) {
  const db = env.AUTH_DB;
  if (!db || typeof db.prepare !== "function") {
    return { linked: false, reason: "no_binding", message: "اربط قاعدة cigram-users بهذا الـ Worker باسم AUTH_DB." };
  }
  const now = Date.now();
  if (accMemo && now - accMemo.at < ACC_CACHE_MS && accMemo.at <= now) return accMemo.data;
  try {
    const offMin = Math.round(opsOffsetMs(env) / 60000);
    const shift = (offMin >= 0 ? "+" : "") + offMin + " minutes";
    const [totals, signed, daily] = await db.batch([
      db.prepare(
        "SELECT COUNT(*) AS total, SUM(CASE WHEN is_active = 1 THEN 1 ELSE 0 END) AS active, " +
        "SUM(CASE WHEN created_at >= datetime('now','-1 day') THEN 1 ELSE 0 END) AS new_24h, " +
        "SUM(CASE WHEN created_at >= datetime('now','-7 day') THEN 1 ELSE 0 END) AS new_7d, " +
        "MAX(created_at) AS last_at FROM users"
      ),
      db.prepare("SELECT COUNT(DISTINCT user_id) AS n FROM sessions WHERE expires_at > ?").bind(new Date(now).toISOString()),
      db.prepare(
        "SELECT substr(datetime(created_at, ?), 1, 10) AS d, COUNT(*) AS n FROM users " +
        "WHERE created_at >= datetime('now','-14 day') GROUP BY d ORDER BY d"
      ).bind(shift),
    ]);
    const t = (totals.results && totals.results[0]) || {};
    const rows = (daily.results || []).map((r) => [String(r.d || ""), Number(r.n) || 0]);
    const today = opsDay(env, now);
    const todayRow = rows.find((r) => r[0] === today);
    const data = {
      linked: true,
      total: Number(t.total) || 0,
      active: Number(t.active) || 0,
      new_24h: Number(t.new_24h) || 0,
      new_7d: Number(t.new_7d) || 0,
      new_today: todayRow ? todayRow[1] : 0,
      signed_in: Number(signed.results && signed.results[0] && signed.results[0].n) || 0,
      last_at: t.last_at ? String(t.last_at).replace(" ", "T") + "Z" : "",
      daily: rows,
    };
    accMemo = { at: now, data };
    return data;
  } catch (error) {
    return { linked: false, reason: "query_failed", message: "تعذر قراءة قاعدة الحسابات: " + String((error && error.message) || error).slice(0, 160) };
  }
}

async function handleAccountsStats(request, env) {
  return okResponse(await liveAccounts(env));
}

async function handleLive(request, env, url) {
  const now = Date.now();
  if (liveMemo && now - liveMemo.at < LIVE_CACHE_MS && liveMemo.at <= now) return okResponse(liveMemo.data);
  const bucket = adminBucket(env);
  const day = opsDay(env, now);
  const [liveObj, aggRead, page, accounts] = await Promise.all([
    bucket.get(adminKey(env, LIVE_NAME)).catch(() => null),
    opsReadAgg(env, day),
    bucket.list({ prefix: opsInboxPrefix(env, ""), limit: 1000 }).catch(() => ({ objects: [], truncated: false })),
    liveAccounts(env).catch(() => ({ linked: false, reason: "query_failed", message: "" })),
  ]);
  let doc = liveEmpty();
  if (liveObj) {
    try {
      doc = liveNormalize(JSON.parse(await liveObj.text()));
    } catch {
      doc = liveEmpty();
    }
  }
  const agg = aggRead.agg;
  const objects = (page.objects || []).slice().sort((a, b) => liveArrival(b.key) - liveArrival(a.key));
  const items = await opsReadInboxObjects(env, objects.slice(0, LIVE_PENDING_READ));
  let pending = 0;
  for (const it of items) {
    if (!it.batch) continue;
    const batch = opsParseBatch(it.batch);
    if (!batch) continue;
    liveFold(doc.users, batch, liveArrival(it.key));
    if (opsInboxDay(it.key) === day && !aggRead.done.has(opsInboxId(it.key))) {
      opsFold(agg, batch, env);
      pending++;
    }
  }
  const counts = liveCounts(doc.users, now);
  const ads = {};
  let adShows = 0;
  let adRequests = 0;
  for (const [pl, row] of Object.entries(agg.ads || {})) {
    const shows = Number(row && row.show) || 0;
    ads[pl] = shows;
    adShows += shows;
    adRequests += Number(row && row.request) || 0;
  }
  const data = {
    now: new Date(now).toISOString(),
    now_ms: now,
    day,
    active_now: counts.active,
    watching_now: counts.watching,
    watching_top: opsTop(counts.content, 5),
    users_today: opsHllCount(agg.users),
    sessions_today: Number(agg.events.session_start) || 0,
    plays_today: Number(agg.events.play_started) || 0,
    ad_shows_today: adShows,
    ad_requests_today: adRequests,
    ads_by_placement: ads,
    history: doc.hist.slice(-120),
    accounts,
    pending,
    partial: Boolean(page.truncated) || objects.length > LIVE_PENDING_READ,
    windows: { active_min: LIVE_ACTIVE_MS / 60000, watch_min: LIVE_WATCH_MS / 60000 },
    updated_at: doc.updatedAt,
  };
  liveMemo = { at: now, data };
  return okResponse(data);
}

// ------------------------------------------------------------------ notify

async function notifyPush(env, title, body, extra) {
  // v9: one visible notification on every Cigram phone, also while the app is closed
  const e = extra || {};
  const data = {
    kind: "announcement",
    type: "announcement",
    level: cleanString(e.kind) || "info",
    id: cleanString(e.announcement_id) || "ann_" + Date.now().toString(36) + randomId(),
    announcement_id: cleanString(e.announcement_id),
    content_id: cleanString(e.content_id),
    created_at: new Date().toISOString(),
  };
  const out = await pushBroadcast(env, String(title || "سيكرام"), String(body || ""), data);
  await pushLog(env, { kind: "announcement", status: out.status, title: String(title || ""), error: out.error || "" });
  return out;
}

async function handleNotify(request, env) {
  const input = await readRequestData(request);
  const title = cleanString(input.title).slice(0, 120);
  const body = cleanString(input.body).slice(0, 600);
  if (!title && !body) throw new AdminError("empty_notification", "اكتب عنواناً أو نصاً للإشعار.", 400);
  const inApp = input.in_app === false || lc(input.in_app) === "false" ? false : true;
  const wantPush = truthy(input.push);
  let announcement = null;
  let undoId = "";
  if (inApp) {
    const res = await handleAnnouncementSave(
      new Request("https://internal/admin/announcements/save", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          announcement: {
            title,
            body,
            kind: cleanString(input.kind),
            show_mode: cleanString(input.show_mode),
            dismissible: input.dismissible === false ? false : true,
            priority: Number(input.priority) || 0,
            start_at: cleanString(input.start_at),
            end_at: cleanString(input.end_at),
            enabled: true,
          },
        }),
      }),
      env
    );
    const out = await res.json();
    if (!out || !out.ok) throw new AdminError("save_failed", (out && out.message) || "تعذر حفظ الرسالة.", 500);
    announcement = out.announcement || null;
    undoId = cleanString(out.undo_id);
  }
  let push = { status: "skipped" };
  if (wantPush) {
    push = await notifyPush(env, title || "سيكرام", body, {
      kind: cleanString(input.kind) || "info",
      announcement_id: announcement ? announcement.id : "",
      content_id: cleanString(input.content_id),
    });
  }
  return okResponse({ announcement, push, in_app: inApp }, push.status === "sent" ? "تم الإرسال." : "", { undo_id: undoId });
}

const OPS_ROUTES = (() => {
  const A = adminRoute;
  const pub = (handler) => async (request, env, url, ctx) => {
    try {
      return await handler(request, env, url, ctx);
    } catch (error) {
      return adminErrorResponse(error);
    }
  };
  return {
    "POST /telemetry/events": handleTelemetryEvents,
    "GET /ads/config": pub(handleAdsConfigPublic),
    "GET /admin/ops/summary": A(handleOpsSummary),
    "GET /admin/ops/errors": A(handleOpsErrors),
    "POST /admin/ops/errors/resolve": A(handleOpsErrorsResolve),
    "GET /admin/ads/config": A(handleAdsConfigGet),
    "POST /admin/ads/config": A(handleAdsConfigSave),
    "GET /admin/ops/ads-check": A(handleOpsAdsCheck),
    "GET /admin/live": A(handleLive),
    "GET /admin/accounts/stats": A(handleAccountsStats),
    "POST /admin/notify": A(handleNotify),
  };
})();

// -----------------------------------------------------------------------------
// route table
// -----------------------------------------------------------------------------

const UPDATE_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /update/history": A(handleUpdateHistory),
    "POST /update/draft": A(handleUpdateDraft),
    "POST /update/stop": A(handleUpdateStop),
    "POST /update/schedule/cancel": A(handleUpdateScheduleCancel),
    "POST /update/upload": A(handleUpdateUpload),
    "POST /update/validate": A(handleUpdateValidate),
    "POST /update/emergency": A(handleUpdateEmergency),
    "POST /update/push": A(handleUpdatePushResend),
    "GET /update/stats": A(handleUpdateStats),
    "POST /update/event": handleUpdateEvent,
  };
})();

// -----------------------------------------------------------------------------
// V2 ROUTE TABLE
// -----------------------------------------------------------------------------

const V2_ROUTES = (() => {
  const A = adminRoute;
  const pub = (handler) => async (request, env, url) => {
    try {
      return await handler(request, env, url);
    } catch (error) {
      return adminErrorResponse(error);
    }
  };
  return {
    // public (read-only, consumed by the Cigram user app)
    "GET /home-sections": pub(handleSectionsGetPublic),
    "GET /featured": pub(handleFeaturedPublic),
    "GET /maintenance": pub(handleMaintenanceGet),
    "GET /announcements": pub(handleAnnouncementsPublic),
    "GET /channels-config": pub(handleChannelsConfigPublic),
    "GET /cigram_home_sections.json": (request, env) =>
      returnR2JsonObject(env, HOME_SECTIONS_OBJECT, { version: 1, sections: [] }),
    "GET /cigram_maintenance.json": (request, env) =>
      returnR2JsonObject(env, MAINTENANCE_OBJECT, normalizeMaintenance(null)),
    "GET /cigram_announcements.json": (request, env) =>
      returnR2JsonObject(env, ANNOUNCEMENTS_OBJECT, { version: 1, items: [] }),

    // admin
    "GET /admin/ping": A(async () => json({ ok: true, service: "cigram-admin-api", v: 2, time: new Date().toISOString() })),
    "GET /admin/meta": A(handleMetaGet),
    "POST /admin/note": A(handleNoteSave),
    "POST /admin/check-urls": A(handleCheckUrls),
    "POST /admin/save-checks": A(handleSaveChecks),
    "POST /admin/check-slug": A(handleCheckSlug),

    "POST /admin/item/update": A(handleItemUpdate),
    "POST /admin/item/create": A(handleItemCreate),
    "POST /admin/item/delete": A(handleItemDelete),
    "POST /admin/bulk": A(handleBulk),

    "GET /admin/sections": A(handleSectionsGetAdmin),
    "POST /admin/sections/seed": A(handleSectionsSeed),
    "POST /admin/sections/save": A(handleSectionsSave),
    "POST /admin/sections/create": A(handleSectionsCreate),
    "POST /admin/sections/update": A(handleSectionsUpdate),
    "POST /admin/sections/delete": A(handleSectionsDelete),

    "POST /admin/featured/reorder": A(handleFeaturedReorder),

    "GET /admin/history": A(handleHistory),
    "POST /admin/undo": A(handleUndo),
    "GET /admin/backups": A(handleBackupsList),
    "POST /admin/backups/restore": A(handleBackupRestore),

    "POST /admin/merge/detect": A(handleMergeDetect),
    "GET /admin/merge/detect": A(handleMergeDetect),
    "POST /admin/merge/preview": A(handleMergePreview),
    "POST /admin/merge/execute": A(handleMergeExecute),

    "POST /admin/relink-subtitles": A(handleRelinkSubtitles),

    "POST /admin/maintenance": A(handleMaintenanceSet),
    "GET /admin/announcements": A(handleAnnouncementsAdmin),
    "POST /admin/announcements/save": A(handleAnnouncementSave),
    "POST /admin/announcements/delete": A(handleAnnouncementDelete),

    "POST /admin/tmdb/search": A(handleTmdbSearch),
    "POST /admin/tmdb/details": A(handleTmdbDetails),
    "POST /admin/duplicates/check": A(handleDuplicatesCheck),
    "POST /admin/series/create": A(handleSeriesCreate),
    "POST /admin/parse-names": A(handleParseNames),
    "POST /admin/r2/list": A(handleR2List),
    "POST /admin/season/preview": A(handleSeasonPreview),
    "POST /admin/season/commit": A(handleSeasonCommit),
    "GET /admin/carousel": A(handleCarouselList),
    "POST /admin/carousel/save": A(handleCarouselSave),
    "POST /admin/carousel/reorder": A(handleCarouselReorder),
    "POST /admin/carousel/remove": A(handleCarouselRemove),
    "GET /admin/recent": A(handleRecent),
    "GET /carousel-active": pub(async (request, env) => carouselPublicResponse(request, env, null)),

    "GET /admin/subs/status": A(handleSubsStatus),
    "POST /admin/subs/search": A(handleSubsSearch),
    "POST /admin/subs/fetch": A(handleSubsFetch),
    "POST /admin/subs/season": A(handleSubsSeason),

    "GET /admin/channel-meta": A(handleChannelMetaGet),
    "POST /admin/channel-meta/save": A(handleChannelMetaSave),
  };
})();

// =============================================================================
// REQUESTS (v8) — the viewers' «طلب فيلم / مسلسل / قناة» in the admin app + Telegram alerts
// =============================================================================
// ADDITIVE: nothing above this section was changed. The router gained one dispatch line,
// the health list gained these route names and the cron gained one line.
//
// The Cigram app (v10.62+) already sends every request to cigram-user-api (POST /requests),
// which keeps it in its KV namespace as "request:<id>". This section reads that SAME namespace,
// so nothing changes in the app or in cigram-user-api, and old app versions are covered too.
//   Binding needed: REQUESTS_KV -> the KV namespace "CIGRAM_KV" (the one cigram-user-api uses).
//   Telegram:       secret TELEGRAM_BOT_TOKEN (from @BotFather); chat via /telegram/link below,
//                   or the variable TELEGRAM_CHAT_ID.
//
// ADMIN (ADMIN_TOKEN):
//   GET  /admin/requests?status=open|pending|in_progress|added|unavailable|all&limit=
//        { summary, top: open requests grouped by title (most requested first), items }
//   POST /admin/requests/status        { id, status, note? }               one request
//   POST /admin/requests/title-status  { kind, title_norm, status, note? }  every open request of
//        that title (e.g. «تمت الإضافة» once -> every viewer who asked gets the app notification)
//   GET  /admin/requests/telegram            setup status (binding, token, chat)
//   POST /admin/requests/telegram/link       1st call -> t.me link (press Start), 2nd call -> linked
//   POST /admin/requests/telegram/test       test message
//
// TELEGRAM ALERTS: the existing every-minute cron looks for new requests every REQ_SCAN_EVERY_MS
// (and opening the list in the admin app looks right away). You get one message per new request
// (several new ones at once arrive as one list), and another when the same title reaches 5, 10,
// 25, 50, 100, 250, 500, 1000 open requests. Never more than REQ_TG_HOURLY messages an hour.
//
// Statuses are the app's: pending (قيد المراجعة) · in_progress (قيد العمل) · added (تمت الإضافة)
// · unavailable (غير متوفر). A status change is written into the same KV record the app reads
// from GET /requests/mine, exactly as cigram-user-api's own admin page does.
//
// STORAGE: requests/bridge.json (admin-private R2: scan position + per-title counters),
//          requests/telegram.json (linked chat + hourly counter). Nothing new in KV.

const REQ_MILESTONES = [5, 10, 25, 50, 100, 250, 500, 1000];
const REQ_TG_HOURLY = 40;
const REQ_SCAN_EVERY_MS = 5 * 60000;    // cron scan interval (keeps KV list calls low)
const REQ_SCAN_ADMIN_MS = 30000;        // opening the admin list scans if the last scan is older
const REQ_SCAN_OVERLAP_MS = 3 * 60000;  // KV lists can lag ~60 s: rescan a little of the past
const REQ_SCAN_MAX = 150;               // new records read per scan
const REQ_LIST_MAX = 400;               // newest records read for the admin list
const REQ_MAX_COUNTS = 3000;
const REQ_KV_TTL = 180 * 24 * 3600;     // same as cigram-user-api REQUEST_TTL
const REQ_STATUSES = ["pending", "in_progress", "added", "unavailable"];
const REQ_KINDS = ["movie", "series", "channel"];
const REQ_BRIDGE_NAME = "requests/bridge.json";
const REQ_TG_NAME = "requests/telegram.json";
const REQ_LINK_TTL_MS = 30 * 60000;
const REQ_WRITE_RETRIES = 12;

function reqKindText(kind) {
  return kind === "series" ? "مسلسل" : kind === "channel" ? "قناة" : "فيلم";
}

function reqKindIcon(kind) {
  return kind === "series" ? "📺" : kind === "channel" ? "📡" : "🎬";
}

function reqStatusText(status) {
  if (status === "in_progress") return "قيد العمل";
  if (status === "added") return "تمت الإضافة";
  if (status === "unavailable") return "غير متوفر";
  return "قيد المراجعة";
}

function reqIsOpen(status) {
  return status !== "added" && status !== "unavailable";
}

function reqEsc(text) {
  return cleanString(text).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function reqClean(value, max) {
  return cleanString(value).replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max);
}

/** Same folding as cigram-user-api normalizeTitle (used only when a record has no title_norm). */
function reqNormTitle(value) {
  let s = String(value || "");
  try {
    s = s.normalize("NFKD");
  } catch {
    // keep as is
  }
  return s.trim().toLowerCase()
    .replace(/\p{M}+/gu, "").replace(/ـ/g, "")
    .replace(/[أإآٱ]/g, "ا").replace(/ى/g, "ي").replace(/ة/g, "ه").replace(/ؤ/g, "و").replace(/ئ/g, "ي")
    .replace(/[٠-٩]/g, (d) => String(d.charCodeAt(0) - 0x0660))
    .replace(/[^\p{L}\p{N}]+/gu, " ").trim().replace(/\s+/g, " ");
}

function reqGroupKey(r) {
  return (REQ_KINDS.includes(r.kind) ? r.kind : "movie") + "|" + (cleanString(r.title_norm) || reqNormTitle(r.title));
}

function reqAgo(ms, now) {
  if (!ms) return "";
  const s = Math.max(0, Math.floor(((now || Date.now()) - Number(ms)) / 1000));
  if (s < 60) return "الآن";
  const m = Math.floor(s / 60);
  if (m < 60) return m === 1 ? "قبل دقيقة" : m === 2 ? "قبل دقيقتين" : "قبل " + m + " دقيقة";
  const h = Math.floor(m / 60);
  if (h < 24) return h === 1 ? "قبل ساعة" : h === 2 ? "قبل ساعتين" : "قبل " + h + " ساعة";
  const d = Math.floor(h / 24);
  return d === 1 ? "أمس" : d === 2 ? "قبل يومين" : "قبل " + d + " يوم";
}

function reqCountText(n) {
  const c = Number(n) || 0;
  if (c === 0) return "ماكو طلبات";
  if (c === 1) return "طلب واحد";
  if (c === 2) return "طلبين";
  if (c <= 10) return c + " طلبات";
  return c + " طلب";
}

function reqKv(env) {
  const kv = env.REQUESTS_KV;
  if (!kv || typeof kv.get !== "function" || typeof kv.list !== "function") {
    throw new AdminError(
      "requests_kv_missing",
      "اربط قاعدة الطلبات بالـ Worker: Settings ← Bindings ← KV namespace، الاسم REQUESTS_KV، واختار CIGRAM_KV.",
      400
    );
  }
  return kv;
}

/** Ids look like cigram-user-api's: 14-digit time + "-" + 16 hex. */
function reqCleanId(value) {
  const id = cleanString(value).slice(0, 40);
  return /^[0-9]{14}-[0-9a-f]{16}$/.test(id) ? id : "";
}

function reqTsOfKey(name) {
  const n = Number(String(name).slice(8, 22));
  return Number.isFinite(n) ? n : 0;
}

async function reqKvListNames(kv, prefix, max) {
  const names = [];
  let cursor;
  do {
    const page = await kv.list(cursor ? { prefix, cursor, limit: 1000 } : { prefix, limit: 1000 });
    for (const k of page.keys || []) names.push(k.name);
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor && names.length < max);
  return names;
}

async function reqKvGetMany(kv, names) {
  const out = [];
  for (let i = 0; i < names.length; i += 40) {
    const part = await Promise.all(
      names.slice(i, i + 40).map(async (name) => {
        try {
          const r = JSON.parse((await kv.get(name)) || "null");
          return r && typeof r === "object" && reqCleanId(r.id) ? r : null;
        } catch {
          return null;
        }
      })
    );
    for (const r of part) if (r) out.push(r);
  }
  return out;
}

/** Newest records first (at most REQ_LIST_MAX). */
async function reqLoadNewest(env, max) {
  const kv = reqKv(env);
  const names = await reqKvListNames(kv, "request:", 6000);
  names.sort().reverse();
  return reqKvGetMany(kv, names.slice(0, max || REQ_LIST_MAX));
}

async function reqWriteRecord(env, record, status, note, now) {
  record.status = status;
  if (note !== undefined && note !== null) record.admin_note = reqClean(note, 300);
  record.updated_at = now;
  await reqKv(env).put("request:" + record.id, JSON.stringify(record), { expirationTtl: REQ_KV_TTL });
}

// ------------------------------------------------------------------ Telegram

function reqTgToken(env) {
  return cleanString(env.TELEGRAM_BOT_TOKEN);
}

async function reqTgReadConfig(env) {
  const cur = await r2ReadJson(adminBucket(env), adminKey(env, REQ_TG_NAME), {});
  return cur.doc && typeof cur.doc === "object" && !Array.isArray(cur.doc) ? cur.doc : {};
}

function reqTgChatOf(env, cfg) {
  const fromEnv = cleanString(env.TELEGRAM_CHAT_ID);
  if (fromEnv) return { chat_id: fromEnv, via: "env" };
  const linked = cleanString(cfg && cfg.chat_id);
  return linked ? { chat_id: linked, via: "linked" } : { chat_id: "", via: "" };
}

async function reqTgCall(env, method, payload) {
  const token = reqTgToken(env);
  if (!token) return { ok: false, description: "no_token" };
  try {
    const res = await updFetch(
      "https://api.telegram.org/bot" + token + "/" + method,
      {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(payload || {}),
      },
      8000
    );
    let body = {};
    try {
      body = await res.json();
    } catch {
      body = {};
    }
    if (!body || typeof body !== "object") body = {};
    if (body.ok === undefined) body.ok = res.ok;
    body.http = res.status;
    return body;
  } catch (error) {
    return { ok: false, description: error && error.name === "AbortError" ? "timeout" : "network_error" };
  }
}

/** Reserves one message in the hourly budget. false = budget used up (nothing is sent). */
async function reqTgReserve(env) {
  const hour = Math.floor(Date.now() / 3600000);
  const out = await mutateJson(adminBucket(env), adminKey(env, REQ_TG_NAME), {}, (cfg) => {
    if (Number(cfg.hour) !== hour) {
      cfg.hour = hour;
      cfg.sent = 0;
      cfg.suppressed = 0;
    }
    if ((Number(cfg.sent) || 0) >= REQ_TG_HOURLY) {
      cfg.suppressed = (Number(cfg.suppressed) || 0) + 1;
      return { result: false };
    }
    cfg.sent = (Number(cfg.sent) || 0) + 1;
    return { result: true };
  }, REQ_WRITE_RETRIES);
  return out.result === true;
}

async function reqTgSend(env, text, opts) {
  const cfg = await reqTgReadConfig(env);
  const chat = reqTgChatOf(env, cfg);
  if (!reqTgToken(env) || !chat.chat_id) return { ok: false, description: "not_configured" };
  if (!(opts && opts.skipBudget) && !(await reqTgReserve(env))) return { ok: false, description: "hourly_limit" };
  return reqTgCall(env, "sendMessage", {
    chat_id: chat.chat_id,
    text,
    parse_mode: "HTML",
    link_preview_options: { is_disabled: true },
  });
}

function reqTgLineOf(r) {
  return reqKindIcon(r.kind) + " <b>" + reqEsc(r.title) + "</b> — " + reqKindText(r.kind);
}

function reqTgMessages(events) {
  const fresh = events.filter((e) => e.kind === "new");
  const hot = events.filter((e) => e.kind === "milestone");
  const out = [];
  if (fresh.length === 1) {
    const r = fresh[0].record;
    const lines = ["🆕 طلب جديد على سيكرام", reqTgLineOf(r)];
    if (cleanString(r.note)) lines.push("📝 " + reqEsc(reqClean(r.note, 200)));
    if (fresh[0].count > 1) lines.push("👥 " + reqCountText(fresh[0].count) + " على هذا العنوان");
    out.push(lines.join("\n"));
  } else if (fresh.length > 1) {
    const lines = ["📥 " + fresh.length + " طلبات جديدة على سيكرام:"];
    for (const e of fresh.slice(0, 15)) {
      lines.push("• " + reqTgLineOf(e.record) + (e.count > 1 ? " (" + reqCountText(e.count) + ")" : ""));
    }
    if (fresh.length > 15) lines.push("… و" + (fresh.length - 15) + " غيرها بتطبيق الإدارة");
    out.push(lines.join("\n"));
  }
  for (const e of hot.slice(0, 5)) {
    out.push("🔥 " + reqTgLineOf(e.record) + "\nوصل " + reqCountText(e.count) + "!");
  }
  return out;
}

// ------------------------------------------------------------------ scan (cron + admin)

function reqEmptyBridge() {
  return { version: 1, scanned_to: 0, last_scan: 0, started_at: 0, seen: {}, counts: {} };
}

function reqNormBridge(doc) {
  const b = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : reqEmptyBridge();
  if (!b.seen || typeof b.seen !== "object" || Array.isArray(b.seen)) b.seen = {};
  if (!b.counts || typeof b.counts !== "object" || Array.isArray(b.counts)) b.counts = {};
  b.scanned_to = Number(b.scanned_to) || 0;
  b.last_scan = Number(b.last_scan) || 0;
  return b;
}

function reqMutateBridge(env, fn) {
  return mutateJson(
    adminBucket(env),
    adminKey(env, REQ_BRIDGE_NAME),
    reqEmptyBridge(),
    (draft, current) => fn(reqNormBridge(draft), current),
    REQ_WRITE_RETRIES
  );
}

function reqBumpCount(bridge, r, ts) {
  const key = reqGroupKey(r);
  let c = bridge.counts[key];
  if (!c || typeof c !== "object") c = { count: 0, notified: [], first_at: ts };
  if (!Array.isArray(c.notified)) c.notified = [];
  c.count = (Number(c.count) || 0) + 1;
  c.kind = r.kind;
  c.title = reqClean(r.title, 120);
  c.last_at = ts;
  bridge.counts[key] = c;
  return c;
}

function reqTrimBridge(bridge, seenBefore) {
  for (const id of Object.keys(bridge.seen)) {
    if ((Number(bridge.seen[id]) || 0) < seenBefore) delete bridge.seen[id];
  }
  const keys = Object.keys(bridge.counts);
  if (keys.length > REQ_MAX_COUNTS) {
    keys
      .sort((a, b) => (Number(bridge.counts[a].last_at) || 0) - (Number(bridge.counts[b].last_at) || 0))
      .slice(0, keys.length - REQ_MAX_COUNTS)
      .forEach((k) => delete bridge.counts[k]);
  }
}

/**
 * Finds requests that arrived since the last scan and sends the Telegram alerts.
 * Safe to run twice at the same time: which ids are new is decided inside one atomic write.
 */
async function reqBridgeScan(env, now, minGapMs) {
  if (!env.REQUESTS_KV) return { skipped: "no_kv" };
  const kv = env.REQUESTS_KV;
  const current = reqNormBridge((await r2ReadJson(adminBucket(env), adminKey(env, REQ_BRIDGE_NAME), reqEmptyBridge())).doc);
  if (current.last_scan && now - current.last_scan < minGapMs) return { skipped: "recent" };

  // first run: count what is already open (so milestones are right) but do not alert the backlog
  if (!current.scanned_to) {
    const open = (await reqLoadNewest(env, REQ_LIST_MAX)).filter((r) => reqIsOpen(r.status));
    await reqMutateBridge(env, (b) => {
      if (b.scanned_to) return;
      b.scanned_to = now;
      b.last_scan = now;
      b.started_at = now;
      for (const r of open) {
        const ts = reqTsOfKey("request:" + r.id);
        const c = reqBumpCount(b, r, ts);
        c.notified = REQ_MILESTONES.filter((m) => m <= c.count);
        // the next scan looks a few minutes back: these are already counted
        if (ts >= now - REQ_SCAN_OVERLAP_MS - 60000) b.seen[r.id] = ts;
      }
    });
    return { first: true, open: open.length };
  }

  const from = Math.max(0, current.scanned_to - REQ_SCAN_OVERLAP_MS);
  const names = [];
  for (let block = Math.floor(from / 1e6); block <= Math.floor(now / 1e6); block++) {
    const prefix = "request:" + String(block).padStart(8, "0");
    for (const name of await reqKvListNames(kv, prefix, 5000)) {
      const ts = reqTsOfKey(name);
      if (ts >= from && ts <= now + 60000 && !current.seen[name.slice(8)]) names.push(name);
    }
  }
  names.sort();
  const capped = names.length > REQ_SCAN_MAX;
  const take = names.slice(0, REQ_SCAN_MAX);
  const records = await reqKvGetMany(kv, take);
  const scannedTo = capped ? reqTsOfKey(take[take.length - 1]) : now;

  const out = await reqMutateBridge(env, (b) => {
    const events = [];
    for (const r of records) {
      if (b.seen[r.id]) continue;
      const ts = reqTsOfKey("request:" + r.id);
      b.seen[r.id] = ts;
      if (!reqIsOpen(r.status)) continue;
      const c = reqBumpCount(b, r, ts);
      events.push({ kind: "new", record: r, count: c.count });
      if (REQ_MILESTONES.includes(c.count) && !c.notified.includes(c.count)) {
        c.notified.push(c.count);
        events.push({ kind: "milestone", record: r, count: c.count });
      }
    }
    b.scanned_to = Math.max(b.scanned_to, scannedTo);
    b.last_scan = now;
    reqTrimBridge(b, from - 3600000);
    return { result: events };
  });

  const events = out.result || [];
  let sent = 0;
  for (const text of reqTgMessages(events)) {
    const res = await reqTgSend(env, text);
    if (res.ok) sent++;
    else if (res.description !== "not_configured" && res.description !== "hourly_limit") {
      updLog("requests_telegram_failed", { reason: String(res.description || res.http || "") });
    }
  }
  return { found: records.length, events: events.length, sent, capped };
}

// ------------------------------------------------------------------ admin

async function handleRequestsList(request, env, url) {
  reqKv(env);
  const now = Date.now();
  try {
    await reqBridgeScan(env, now, REQ_SCAN_ADMIN_MS);
  } catch (error) {
    updLog("requests_scan_failed", { message: String(error && error.message) });
  }
  const status = lc(url.searchParams.get("status")) || "open";
  const limitRaw = parseInt(url.searchParams.get("limit") || "200", 10);
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), REQ_LIST_MAX) : 200;
  const records = await reqLoadNewest(env, REQ_LIST_MAX);

  const summary = { open: 0, pending: 0, in_progress: 0, added: 0, unavailable: 0, total: records.length };
  const groups = new Map();
  for (const r of records) {
    const st = REQ_STATUSES.includes(r.status) ? r.status : "pending";
    summary[st]++;
    if (!reqIsOpen(st)) continue;
    summary.open++;
    const key = reqGroupKey(r);
    let g = groups.get(key);
    if (!g) {
      g = {
        key,
        kind: REQ_KINDS.includes(r.kind) ? r.kind : "movie",
        title_norm: key.slice(key.indexOf("|") + 1),
        title: reqClean(r.title, 120),
        count: 0,
        in_progress: 0,
        notes: [],
        last_ms: 0,
      };
      groups.set(key, g);
    }
    g.count++;
    if (st === "in_progress") g.in_progress++;
    const ms = Number(r.created_at) || 0;
    if (ms > g.last_ms) {
      g.last_ms = ms;
      g.title = reqClean(r.title, 120) || g.title;
    }
    const note = reqClean(r.note, 120);
    if (note && g.notes.length < 3 && !g.notes.includes(note)) g.notes.push(note);
  }
  const top = [...groups.values()]
    .sort((a, b) => b.count - a.count || b.last_ms - a.last_ms)
    .slice(0, 100)
    .map((g) => ({
      key: g.key,
      kind: g.kind,
      kind_text: reqKindText(g.kind),
      title: g.title,
      title_norm: g.title_norm,
      count: g.count,
      count_text: reqCountText(g.count),
      in_progress: g.in_progress,
      status_text: g.in_progress === g.count ? "قيد العمل" : g.in_progress ? "بعضها قيد العمل" : "قيد المراجعة",
      notes: g.notes,
      last_at: g.last_ms ? new Date(g.last_ms).toISOString() : "",
      last_ago: reqAgo(g.last_ms, now),
    }));

  const items = records
    .filter((r) => {
      const st = REQ_STATUSES.includes(r.status) ? r.status : "pending";
      if (status === "all") return true;
      if (status === "open") return reqIsOpen(st);
      return st === status;
    })
    .slice(0, limit)
    .map((r) => {
      const key = reqGroupKey(r);
      const st = REQ_STATUSES.includes(r.status) ? r.status : "pending";
      return {
        id: r.id,
        kind: REQ_KINDS.includes(r.kind) ? r.kind : "movie",
        kind_text: reqKindText(r.kind),
        title: reqClean(r.title, 120),
        title_norm: key.slice(key.indexOf("|") + 1),
        note: reqClean(r.note, 300),
        status: st,
        status_text: reqStatusText(st),
        admin_note: reqClean(r.admin_note, 300),
        app_version: reqClean(r.app_version, 24),
        logged_in: Boolean(r.user_id),
        created_at: r.created_at ? new Date(Number(r.created_at)).toISOString() : "",
        ago: reqAgo(r.created_at, now),
        same_title: groups.has(key) ? groups.get(key).count : 0,
      };
    });

  return okResponse(
    { status, summary, top, items, limit_note: records.length >= REQ_LIST_MAX ? "تظهر آخر " + REQ_LIST_MAX + " طلب." : "" },
    items.length ? "" : "ماكو طلبات هنا."
  );
}

function reqStatusInput(input) {
  const status = lc(input.status);
  if (!REQ_STATUSES.includes(status)) throw new AdminError("bad_status", "حالة غير معروفة.", 400);
  return status;
}

async function handleRequestsStatus(request, env) {
  const kv = reqKv(env);
  const input = await readRequestData(request);
  const id = reqCleanId(input.id);
  if (!id) throw new AdminError("bad_id", "رقم الطلب غير صحيح.", 400);
  const status = reqStatusInput(input);
  const raw = await kv.get("request:" + id);
  if (!raw) throw new AdminError("not_found", "الطلب مو موجود (يمكن انحذف بعد 6 أشهر).", 404);
  let record;
  try {
    record = JSON.parse(raw);
  } catch {
    throw new AdminError("corrupt_json", "سجل الطلب تالف.", 500);
  }
  await reqWriteRecord(env, record, status, input.note, Date.now());
  return okResponse({ id, status, status_text: reqStatusText(status) }, "تم: " + reqStatusText(status) + " ✅");
}

async function handleRequestsTitleStatus(request, env) {
  reqKv(env);
  const input = await readRequestData(request);
  const kind = REQ_KINDS.includes(lc(input.kind)) ? lc(input.kind) : "";
  const norm = cleanString(input.title_norm) || reqNormTitle(input.title);
  if (!kind || norm.length < 2) throw new AdminError("bad_title", "العنوان غير صحيح.", 400);
  const status = reqStatusInput(input);
  const key = kind + "|" + norm;
  const now = Date.now();
  const records = (await reqLoadNewest(env, REQ_LIST_MAX)).filter(
    (r) => reqGroupKey(r) === key && reqIsOpen(r.status) && r.status !== status
  );
  for (const r of records) await reqWriteRecord(env, r, status, input.note, now);
  if (!reqIsOpen(status)) {
    await reqMutateBridge(env, (b) => {
      delete b.counts[key];
    });
  }
  const msg =
    status === "added"
      ? "تم ✅ " + reqCountText(records.length) + " صارت «تمت الإضافة»، وكل واحد طلبه يوصله إشعار بالتطبيق."
      : "تم: " + reqCountText(records.length) + " صارت «" + reqStatusText(status) + "».";
  return okResponse({ key, status, updated: records.length }, msg);
}

async function handleRequestsTelegramStatus(request, env) {
  const cfg = await reqTgReadConfig(env);
  const chat = reqTgChatOf(env, cfg);
  const hour = Math.floor(Date.now() / 3600000);
  const tokenSet = Boolean(reqTgToken(env));
  let kv = Boolean(env.REQUESTS_KV);
  let kvHasRequests = false;
  if (kv) {
    try {
      const page = await env.REQUESTS_KV.list({ prefix: "request:", limit: 1 });
      kvHasRequests = Boolean(page && page.keys && page.keys.length);
    } catch {
      kv = false;
    }
  }
  const bridge = reqNormBridge((await r2ReadJson(adminBucket(env), adminKey(env, REQ_BRIDGE_NAME), reqEmptyBridge())).doc);
  const steps = [];
  if (!kv) steps.push("اربط REQUESTS_KV بقاعدة CIGRAM_KV.");
  if (!tokenSet) steps.push("ضيف السر TELEGRAM_BOT_TOKEN.");
  if (tokenSet && !chat.chat_id) steps.push("اضغط «ربط تليكرام».");
  return okResponse(
    {
      kv_bound: kv,
      kv_has_requests: kvHasRequests,
      token_set: tokenSet,
      linked: Boolean(chat.chat_id),
      via: chat.via,
      chat_name: chat.via === "linked" ? cleanString(cfg.chat_name) : "",
      last_scan: bridge.last_scan ? new Date(bridge.last_scan).toISOString() : "",
      last_scan_ago: reqAgo(bridge.last_scan),
      sent_this_hour: Number(cfg.hour) === hour ? Number(cfg.sent) || 0 : 0,
      hourly_limit: REQ_TG_HOURLY,
      ready: kv && tokenSet && Boolean(chat.chat_id),
      steps,
    },
    steps.length ? steps.join(" ") : "كل شي جاهز ✅ أي طلب جديد يوصلك على تليكرام."
  );
}

function reqNewCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  return "cg" + Array.from(bytes, (b) => (b % 36).toString(36)).join("");
}

async function handleRequestsTelegramLink(request, env) {
  if (!reqTgToken(env)) {
    throw new AdminError("telegram_no_token", "ضيف TELEGRAM_BOT_TOKEN بإعدادات الـ Worker أول، وبعدين اضغط ربط.", 400);
  }
  const input = await readRequestData(request);
  const me = await reqTgCall(env, "getMe", {});
  if (!me.ok || !me.result || !me.result.username) {
    throw new AdminError("telegram_bad_token", "توكن البوت غير صحيح. انسخه من @BotFather مرة ثانية.", 400);
  }
  const username = cleanString(me.result.username);
  const bucket = adminBucket(env);
  const cfgKey = adminKey(env, REQ_TG_NAME);
  const now = Date.now();
  const cfg = await reqTgReadConfig(env);
  const pending = cleanString(cfg.pending_code);
  const fresh = pending && now - (Number(cfg.pending_at) || 0) < REQ_LINK_TTL_MS;

  if (!fresh || truthy(input.reset)) {
    const code = reqNewCode();
    await mutateJson(bucket, cfgKey, {}, (doc) => {
      doc.pending_code = code;
      doc.pending_at = now;
    }, REQ_WRITE_RETRIES);
    return okResponse(
      { status: "waiting", bot: username, link: "https://t.me/" + username + "?start=" + code },
      "افتح الرابط واضغط Start بالبوت، وبعدها ارجع اضغط «ربط تليكرام» مرة ثانية."
    );
  }

  const updates = await reqTgCall(env, "getUpdates", { allowed_updates: ["message"], limit: 100 });
  if (!updates.ok) {
    const webhook = Number(updates.http) === 409;
    throw new AdminError(
      webhook ? "telegram_webhook" : "telegram_error",
      webhook ? "البوت مربوط بـ Webhook ثاني. استخدم بوت جديد مخصص للإشعارات." : "تعذر الاتصال بتليكرام. جرّب بعد شوية.",
      502
    );
  }
  const list = Array.isArray(updates.result) ? updates.result : [];
  let found = null;
  for (const u of list) {
    const m = u && u.message;
    if (m && m.chat && cleanString(m.text) === "/start " + pending) found = m;
  }
  if (!found) {
    return okResponse(
      { status: "waiting", bot: username, link: "https://t.me/" + username + "?start=" + pending },
      "بعد ما وصلت رسالة Start. افتح الرابط واضغط Start، وبعدها اضغط ربط."
    );
  }
  const chatName =
    cleanString(found.chat.title) ||
    [cleanString(found.chat.first_name), cleanString(found.chat.last_name)].filter(Boolean).join(" ") ||
    cleanString(found.chat.username);
  await mutateJson(bucket, cfgKey, {}, (doc) => {
    doc.chat_id = String(found.chat.id);
    doc.chat_name = chatName;
    doc.linked_at = now;
    doc.pending_code = "";
    doc.pending_at = 0;
  }, REQ_WRITE_RETRIES);
  await reqTgCall(env, "sendMessage", {
    chat_id: String(found.chat.id),
    text: "✅ تم ربط إشعارات طلبات سيكرام. أي طلب جديد راح يوصلك هنا.",
  });
  return okResponse({ status: "linked", bot: username, chat_name: chatName }, "تم ربط تليكرام ✅");
}

async function handleRequestsTelegramTest(request, env) {
  const res = await reqTgSend(env, "🔔 رسالة تجربة من سيكرام. إشعارات الطلبات شغّالة ✅", { skipBudget: true });
  if (!res.ok) {
    const reason =
      res.description === "not_configured"
        ? "تليكرام مو مربوط بعد."
        : "تليكرام رفض الرسالة" + (res.description ? ": " + cleanString(res.description) : ".");
    throw new AdminError("telegram_failed", reason, 400);
  }
  return okResponse({ sent: true }, "انرسلت ✅ شوف تليكرام.");
}

const REQUESTS_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /admin/requests": A(handleRequestsList),
    "POST /admin/requests/status": A(handleRequestsStatus),
    "POST /admin/requests/title-status": A(handleRequestsTitleStatus),
    "GET /admin/requests/telegram": A(handleRequestsTelegramStatus),
    "POST /admin/requests/telegram/link": A(handleRequestsTelegramLink),
    "POST /admin/requests/telegram/test": A(handleRequestsTelegramTest),
  };
})();

// =============================================================================
// PUSH (v9) — phone notifications that arrive while the Cigram app is CLOSED
// =============================================================================
// What was wrong before v9:
//   * the Cigram app listens on the FCM topic "new_titles" (and from v10.67 also on "cigram_all"),
//     but /admin/notify and the update center published to "cigram_updates" -> nobody received them;
//   * nothing at all was published when a film / series / episode was added, so phones only learned
//     about new content when the app was opened (its own catalog check) -> "the notification only
//     shows after I open the app".
// v9:
//   * every change of movies.json (add-movie, add-series, item/create, series/create, season/commit,
//     un-hiding a recently added title) is checked after it is saved and, when something NEW became
//     visible, a HIGH-priority data push is sent (delivered while the app is closed):
//        new film / new series (once it has episodes) -> kind "new_title"   -> topic "new_titles"
//        new episodes of an existing series           -> kind "new_episode" -> topic "ep_<series>"
//                                                        (only the phones that follow that series;
//                                                        app v10.67+, older versions use their own check)
//   * /admin/notify and the update center now publish to EVERY Cigram phone (topics cigram_all,
//     new_titles, cigram_updates + FCM_NOTIFY_TOPIC / FCM_TOPIC if set) with a visible notification
//     + data, HIGH priority, kept 3 days. Old app versions show it too.
//   * FCM_SERVICE_ACCOUNT is used first; PUSH_WEBHOOK_URL only when there is no service account.
//
// ADMIN (ADMIN_TOKEN):
//   GET  /admin/push/status     { configured, project_id, settings, log[] }
//   POST /admin/push/settings   { auto_content?: bool, auto_episodes?: bool }
//   POST /admin/push/test       { title?, body? }   one visible push to every phone
// Secret needed: FCM_SERVICE_ACCOUNT = the whole service-account JSON of the Firebase project "cigram"
// (Firebase console -> Project settings -> Service accounts -> Generate new private key).

const PUSH_TOPIC_ALL = "cigram_all";
const PUSH_TOPIC_TITLES = "new_titles";
const PUSH_TOPIC_LEGACY = "cigram_updates";
const PUSH_TTL = "259200s"; // 3 days
const PUSH_CHANNEL_GENERAL = "cigram_general";
const PUSH_ICON = "ic_stat_cigram";
const PUSH_COLOR = "#E21B14";
const PUSH_SETTINGS_NAME = "push_settings.json";
const PUSH_LOG_NAME = "push_log.json";
const PUSH_LOG_MAX = 40;
const PUSH_MAX_TITLES = 25;          // more "new" titles at once = an import / restore, stay silent
const PUSH_MAX_SERIES_EPISODE_PUSHES = 5;
const PUSH_UNHIDE_WINDOW_MS = 14 * 24 * 3600 * 1000;
const PUSH_CONTENT_OPS = new Set(["add", "add-series", "create", "add-series-header", "season-commit", "update", "bulk-show"]);

/** Pushes started by a request; the router hands them to ctx.waitUntil so they finish after the reply. */
const PUSH_PENDING = new Set();

function pushTrack(promise) {
  const p = Promise.resolve(promise).catch((error) => updLog("push_failed", { message: String(error && error.message) }));
  PUSH_PENDING.add(p);
  p.finally(() => PUSH_PENDING.delete(p));
  return p;
}

function pushDrainInto(ctx) {
  if (!PUSH_PENDING.size) return;
  const all = Promise.allSettled([...PUSH_PENDING]);
  if (ctx && typeof ctx.waitUntil === "function") ctx.waitUntil(all);
}

function pushTopicOk(t) {
  return /^[a-zA-Z0-9\-_.~%]{1,200}$/.test(t);
}

/** Topic of one series' followers. Must match CigramFcm.seriesTopic() in the app. */
function pushSeriesTopic(seriesId) {
  const id = cleanString(seriesId);
  if (/^[A-Za-z0-9_-]{1,100}$/.test(id)) return "ep_" + id;
  let h = 0x811c9dc5;
  for (let i = 0; i < id.length; i++) {
    h ^= id.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return "ep_h" + (h >>> 0).toString(16).padStart(8, "0");
}

/** Every topic a Cigram phone may listen on (old and new app versions). FCM allows 5 in one condition. */
function pushBroadcastTopics(env) {
  const out = [];
  for (const t of [PUSH_TOPIC_ALL, PUSH_TOPIC_TITLES, cleanString(env.FCM_NOTIFY_TOPIC), cleanString(env.FCM_TOPIC), PUSH_TOPIC_LEGACY]) {
    if (t && pushTopicOk(t) && !out.includes(t)) out.push(t);
    if (out.length >= 5) break;
  }
  return out;
}

function pushTarget(topics) {
  const list = (topics || []).filter(pushTopicOk);
  if (list.length <= 1) return { topic: list[0] || PUSH_TOPIC_ALL };
  return { condition: list.map((t) => "'" + t + "' in topics").join(" || ") };
}

function pushServiceAccount(env) {
  if (!env.FCM_SERVICE_ACCOUNT) return { error: "no_provider" };
  let sa;
  try {
    sa = typeof env.FCM_SERVICE_ACCOUNT === "string" ? JSON.parse(env.FCM_SERVICE_ACCOUNT) : env.FCM_SERVICE_ACCOUNT;
  } catch {
    return { error: "bad_json" };
  }
  if (!sa || !sa.client_email || !sa.private_key || !sa.project_id) return { error: "incomplete" };
  return { sa };
}

function pushProviderError(code) {
  if (code === "no_provider") return "الإشعار للهاتف غير مُعدّ في الـ Worker (أضف Secret اسمه FCM_SERVICE_ACCOUNT).";
  if (code === "bad_json") return "قيمة FCM_SERVICE_ACCOUNT ليست JSON صالحاً. الصق محتوى ملف حساب الخدمة كاملاً.";
  if (code === "incomplete") return "ملف حساب خدمة Firebase ناقص (project_id / client_email / private_key).";
  return "تعذر إرسال الإشعار.";
}

/** FCM data values must all be non-empty strings. */
function pushData(obj) {
  const out = {};
  for (const [k, v] of Object.entries(obj || {})) {
    if (v === undefined || v === null) continue;
    const s = String(v);
    if (s !== "") out[k] = s.slice(0, 900);
  }
  return out;
}

/**
 * Sends one FCM v1 message. message = { topic | condition, data, notification?, android? }.
 * Returns { status: "sent" | "failed" | "no_provider", at, error?, http?, id? } and never throws.
 */
async function pushSendFcm(env, message) {
  const at = new Date().toISOString();
  const got = pushServiceAccount(env);
  if (got.error) return { status: got.error === "no_provider" ? "no_provider" : "failed", at, error: pushProviderError(got.error) };
  let token;
  try {
    token = await fcmAccessToken(got.sa);
  } catch (error) {
    return { status: "failed", at, error: "فشل التحقق من حساب خدمة Firebase. تأكد أن الملف من مشروع cigram وأنه غير ملغى." };
  }
  try {
    const res = await updFetch(
      "https://fcm.googleapis.com/v1/projects/" + encodeURIComponent(got.sa.project_id) + "/messages:send",
      {
        method: "POST",
        headers: { "content-type": "application/json", authorization: "Bearer " + token },
        body: JSON.stringify({ message }),
      },
      8000
    );
    const out = await res.json().catch(() => ({}));
    if (!res.ok) {
      const reason = cleanString(out && out.error && (out.error.message || out.error.status));
      return {
        status: "failed",
        at,
        http: res.status,
        error: "رفضت Firebase الإشعار (HTTP " + res.status + ")" + (reason ? ": " + reason.slice(0, 160) : "."),
      };
    }
    return { status: "sent", at, id: cleanString(out && out.name) };
  } catch (error) {
    return { status: "failed", at, error: error && error.name === "AbortError" ? "انتهت مهلة الاتصال بـ Firebase." : "تعذر الاتصال بـ Firebase." };
  }
}

/**
 * Visible notification for every Cigram phone (announcements, updates, tests).
 * notification + data: when the app is closed Android/FCM shows it by itself (works on old app
 * versions too); when the app is open the app shows it and stores it in its notifications page.
 */
async function pushBroadcast(env, title, body, data) {
  const at = new Date().toISOString();
  const payload = pushData(Object.assign({ title, body }, data || {}));
  const topics = pushBroadcastTopics(env);
  if (!env.FCM_SERVICE_ACCOUNT && env.PUSH_WEBHOOK_URL) {
    // optional external relay (only when there is no service account in this Worker)
    try {
      const res = await updFetch(
        env.PUSH_WEBHOOK_URL,
        { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(payload) },
        8000
      );
      if (!res.ok) return { status: "failed", at, via: "webhook", error: "رفض مزوّد الإشعارات الطلب (HTTP " + res.status + ")." };
      return { status: "sent", at, via: "webhook" };
    } catch (error) {
      return { status: "failed", at, via: "webhook", error: error && error.name === "AbortError" ? "انتهت مهلة الاتصال بمزوّد الإشعارات." : "تعذر الاتصال بمزوّد الإشعارات." };
    }
  }
  const message = Object.assign(pushTarget(topics), {
    notification: { title: String(title || "سيكرام").slice(0, 120), body: String(body || "").slice(0, 600) },
    data: payload,
    android: {
      priority: "HIGH",
      ttl: PUSH_TTL,
      notification: {
        channel_id: PUSH_CHANNEL_GENERAL,
        icon: PUSH_ICON,
        color: PUSH_COLOR,
        sound: "default",
        default_vibrate_timings: true,
        notification_priority: "PRIORITY_HIGH",
        visibility: "PUBLIC",
        tag: payload.id || undefined,
      },
    },
  });
  if (!message.android.notification.tag) delete message.android.notification.tag;
  const out = await pushSendFcm(env, message);
  return Object.assign(out, { via: "fcm", topic: topics.join(" | ") });
}

/** Data-only HIGH push: the app builds the notification itself (also while it is closed). */
async function pushDataOnly(env, topics, data) {
  const message = Object.assign(pushTarget(topics), {
    data: pushData(data),
    android: { priority: "HIGH", ttl: PUSH_TTL },
  });
  const out = await pushSendFcm(env, message);
  return Object.assign(out, { via: "fcm", topic: (topics || []).join(" | ") });
}

// ---------------------------------------------------------------- settings + log

function pushDefaultSettings() {
  return { auto_content: true, auto_episodes: true, updated_at: "" };
}

async function pushReadSettings(env) {
  try {
    const got = await r2ReadJson(adminBucket(env), adminKey(env, PUSH_SETTINGS_NAME), null);
    const d = got.exists && got.doc && typeof got.doc === "object" ? got.doc : {};
    const def = pushDefaultSettings();
    return {
      auto_content: d.auto_content === false ? false : def.auto_content,
      auto_episodes: d.auto_episodes === false ? false : def.auto_episodes,
      updated_at: cleanString(d.updated_at),
    };
  } catch {
    return pushDefaultSettings();
  }
}

async function pushLog(env, entry) {
  try {
    await mutateJson(adminBucket(env), adminKey(env, PUSH_LOG_NAME), { version: 1, items: [] }, (draft) => {
      const doc = draft && typeof draft === "object" && !Array.isArray(draft) ? draft : { version: 1, items: [] };
      const items = Array.isArray(doc.items) ? doc.items : [];
      items.unshift(Object.assign({ at: new Date().toISOString() }, entry));
      return { doc: { version: 1, items: items.slice(0, PUSH_LOG_MAX) } };
    });
  } catch (error) {
    updLog("push_log_failed", { message: String(error && error.message) });
  }
}

// ---------------------------------------------------------------- content -> push

function pushNestedEpisodeCount(header) {
  if (!header) return 0;
  if (Array.isArray(header.episodes)) return header.episodes.length;
  const raw = cleanString(header.series_episodes_json);
  if (raw.length < 3) return 0;
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.length;
    if (parsed && Array.isArray(parsed.episodes)) return parsed.episodes.length;
    if (parsed && Array.isArray(parsed.seasons)) return parsed.seasons.length;
  } catch {
    return 0;
  }
  return 0;
}

function pushFindHeader(list, sid) {
  const want = cleanString(sid);
  if (!want) return null;
  return list.find((x) => x && isSeriesHeader(x) && (seriesKeyOf(x) === want || cleanString(x.id) === want)) || null;
}

function pushPoster(item) {
  const p = cleanString(item && (item.series_poster || item.poster || item.season_poster));
  return /^https:\/\//i.test(p) ? p : "";
}

/** What became newly visible in this save: { titles: [items], episodes: [{header, eps}] }. */
function pushPlanFromRecords(records, list) {
  const now = Date.now();
  const titles = [];
  const titleIds = new Set();
  const freshIds = new Set();
  const groups = new Map();
  for (const r of records || []) {
    const a = r && r.after;
    if (!a || truthy(a.hidden)) continue;
    let fresh = !r.before;
    if (!fresh && truthy(r.before.hidden)) {
      const created = Number(a.created_at_ms) || Date.parse(cleanString(a.created_at)) || 0;
      fresh = created > 0 && now - created < PUSH_UNHIDE_WINDOW_MS;
    }
    if (!fresh) continue;
    freshIds.add(cleanString(a.id));
    const kind = itemKind(a);
    if (kind === "episode") {
      const sid = cleanString(a.series_id);
      if (!sid) continue;
      if (!groups.has(sid)) groups.set(sid, []);
      groups.get(sid).push(a);
    } else if (kind === "series") {
      // a series is announced once it has something to watch
      if (episodesOf(list, a).length > 0 || pushNestedEpisodeCount(a) > 0) {
        titles.push(a);
        titleIds.add(cleanString(a.id));
      }
    } else {
      titles.push(a);
      titleIds.add(cleanString(a.id));
    }
  }
  const episodes = [];
  for (const [sid, eps] of groups) {
    const header = pushFindHeader(list, sid);
    if (!header || truthy(header.hidden)) continue;
    if (titleIds.has(cleanString(header.id))) continue;
    const older = episodesOf(list, header).filter((e) => !freshIds.has(cleanString(e.id))).length + pushNestedEpisodeCount(header);
    if (older === 0) {
      // the first episodes of this series: the series itself goes live now
      titles.push(header);
      titleIds.add(cleanString(header.id));
      continue;
    }
    eps.sort((x, y) => numOf(x.season) - numOf(y.season) || numOf(x.episode) - numOf(y.episode));
    episodes.push({ header, eps });
  }
  return { titles, episodes };
}

async function pushContentNow(env, plan, op) {
  const settings = await pushReadSettings(env);
  if (!env.FCM_SERVICE_ACCOUNT) {
    if (plan.titles.length || plan.episodes.length) {
      await pushLog(env, { kind: "content", op, status: "no_provider", title: plan.titles[0] ? cleanString(plan.titles[0].title) : "", error: pushProviderError("no_provider") });
    }
    return;
  }

  if (plan.titles.length && settings.auto_content) {
    if (plan.titles.length > PUSH_MAX_TITLES) {
      await pushLog(env, { kind: "new_title", op, status: "skipped", count: plan.titles.length, error: "عدد كبير دفعة واحدة (استيراد/استعادة) — لم يُرسل إشعار." });
    } else {
      const first = plan.titles[0];
      const firstTitle = cleanString(first.title) || "سيكرام";
      let data;
      if (plan.titles.length === 1) {
        const series = itemKind(first) === "series";
        data = {
          kind: "new_title",
          count: "1",
          title: firstTitle,
          body: (series ? "مسلسل جديد: " : "فيلم جديد: ") + firstTitle,
          content_type: series ? "series" : "movie",
          content_id: series ? seriesKeyOf(first) : cleanString(first.id),
          poster: pushPoster(first),
          year: cleanString(first.year),
          id: "nt_" + (series ? seriesKeyOf(first) : cleanString(first.id)),
        };
      } else {
        data = {
          kind: "new_title",
          count: String(plan.titles.length),
          title: firstTitle,
          body: plan.titles.length + " أفلام ومسلسلات جديدة، أحدثها: " + firstTitle,
          id: "ntb_" + Date.now().toString(36),
        };
      }
      const out = await pushDataOnly(env, [PUSH_TOPIC_TITLES], data);
      await pushLog(env, { kind: "new_title", op, status: out.status, title: firstTitle, count: plan.titles.length, error: out.error || "" });
    }
  }

  if (plan.episodes.length && settings.auto_episodes) {
    for (const g of plan.episodes.slice(0, PUSH_MAX_SERIES_EPISODE_PUSHES)) {
      const name = cleanString(g.header.title) || "مسلسل";
      const last = g.eps[g.eps.length - 1];
      const firstEp = g.eps[0];
      const data = {
        kind: "new_episode",
        series_id: seriesKeyOf(g.header),
        title: name,
        season: cleanString(last.season),
        episode: cleanString(last.episode),
        from_episode: cleanString(firstEp.episode),
        count: String(g.eps.length),
        poster: pushPoster(g.header),
        body: g.eps.length === 1
          ? "الحلقة " + cleanString(last.episode) + " من " + name
          : g.eps.length + " حلقات جديدة من " + name,
        id: "ne_" + seriesKeyOf(g.header) + "_" + cleanString(last.season) + "_" + cleanString(last.episode),
      };
      // only the followers of this series: a HIGH push that ends in a visible notification
      const out = await pushDataOnly(env, [pushSeriesTopic(seriesKeyOf(g.header))], data);
      await pushLog(env, { kind: "new_episode", op, status: out.status, title: name, count: g.eps.length, error: out.error || "" });
    }
  }
}

/** Called by mutateMovies after every successful save. Never throws, never delays the reply. */
function pushAfterMoviesSave(env, records, list, op) {
  try {
    if (!PUSH_CONTENT_OPS.has(op)) return;
    const plan = pushPlanFromRecords(records, list);
    if (!plan.titles.length && !plan.episodes.length) return;
    pushTrack(pushContentNow(env, plan, op));
  } catch (error) {
    updLog("push_plan_failed", { message: String(error && error.message) });
  }
}

// ---------------------------------------------------------------- admin routes

async function handlePushStatus(request, env) {
  const got = pushServiceAccount(env);
  const settings = await pushReadSettings(env);
  let log = [];
  try {
    const l = await r2ReadJson(adminBucket(env), adminKey(env, PUSH_LOG_NAME), null);
    log = l.exists && l.doc && Array.isArray(l.doc.items) ? l.doc.items.slice(0, 15) : [];
  } catch {
    log = [];
  }
  return okResponse({
    configured: !got.error,
    problem: got.error ? pushProviderError(got.error) : "",
    project_id: got.sa ? cleanString(got.sa.project_id) : "",
    webhook: Boolean(env.PUSH_WEBHOOK_URL),
    topics: { titles: PUSH_TOPIC_TITLES, all: PUSH_TOPIC_ALL, broadcast: pushBroadcastTopics(env) },
    settings,
    log,
  });
}

async function handlePushSettings(request, env) {
  const input = await readRequestData(request);
  const cur = await pushReadSettings(env);
  const next = {
    auto_content: input.auto_content === undefined ? cur.auto_content : truthy(input.auto_content),
    auto_episodes: input.auto_episodes === undefined ? cur.auto_episodes : truthy(input.auto_episodes),
    updated_at: new Date().toISOString(),
  };
  await mutateJson(adminBucket(env), adminKey(env, PUSH_SETTINGS_NAME), pushDefaultSettings(), () => ({ doc: next }));
  return okResponse({ settings: next }, "تم الحفظ.");
}

async function handlePushTest(request, env) {
  const input = await readRequestData(request);
  const title = cleanString(input.title).slice(0, 120) || "اختبار إشعارات سيكرام";
  const body = cleanString(input.body).slice(0, 600) || "إذا وصلك هذا الإشعار والتطبيق مغلق، فالإشعارات تعمل بشكل صحيح.";
  const push = await pushBroadcast(env, title, body, { kind: "announcement", type: "announcement", level: "info", id: "test_" + Date.now().toString(36), test: "1" });
  await pushLog(env, { kind: "test", status: push.status, title, error: push.error || "" });
  return okResponse({ push }, push.status === "sent" ? "أُرسل إشعار تجريبي لكل الهواتف." : "");
}

const PUSH_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /admin/push/status": A(handlePushStatus),
    "POST /admin/push/settings": A(handlePushSettings),
    "POST /admin/push/test": A(handlePushTest),
  };
})();

const subs_CP1256_HIGH =
  '€پ‚ƒ„…†‡ˆ‰ٹ‹Œچژڈ' +
  'گ‘’“”•–—ک™ڑ›œ‌‍ں' +
  ' ،¢£¤¥¦§¨©ھ«¬­®¯' +
  '°±²³´µ¶·¸¹؛»¼½¾؟' +
  'ہءآأؤإئابةتثجحخد' +
  'ذرزسشصض×طظعغـفقك' +
  'àلâمنهوçèéêëىيîï' +
  'ًٌٍَôُِ÷ّùْûü‎‏ے';

// Windows-1252 للبايتات 0x80 إلى 0x9F (الباقي يطابق رقمه). يلزم لإصلاح النص «المخربط».
const subs_CP1252_C1 =
  '€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f' +
  '\u0090‘’“”•–—˜™š›œ\u009džŸ';

const subs_ARABIC = /[؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]/g;
const subs_LETTER = /\p{L}/gu;

function subs_countOf(text, re) {
  const m = text.match(re);
  return m ? m.length : 0;
}

function subs_decodeWith(label, bytes, fatal = false) {
  return new TextDecoder(label, { fatal }).decode(bytes);
}

function subs_decodeUtf16(bytes, littleEndian) {
  try {
    return subs_decodeWith(littleEndian ? 'utf-16le' : 'utf-16be', bytes);
  } catch {
    let out = '';
    const chunk = [];
    for (let i = 0; i + 1 < bytes.length; i += 2) {
      chunk.push(littleEndian ? bytes[i] | (bytes[i + 1] << 8) : (bytes[i] << 8) | bytes[i + 1]);
      if (chunk.length === 8192) { out += String.fromCharCode(...chunk); chunk.length = 0; }
    }
    return out + String.fromCharCode(...chunk);
  }
}

function subs_decode1256(bytes) {
  try {
    return subs_decodeWith('windows-1256', bytes);
  } catch {
    let out = '';
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      out += b < 0x80 ? String.fromCharCode(b) : subs_CP1256_HIGH[b - 0x80];
    }
    return out;
  }
}

/** يرجع البايتات الأصلية لنص قُرئ خطأً بترميز Windows-1252، أو null إن لم يكن ذلك ممكناً. */
function subs_bytesAs1252(text) {
  const out = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80 || (c >= 0xa0 && c <= 0xff)) { out[i] = c; continue; }
    const k = subs_CP1252_C1.indexOf(text[i]);
    if (k < 0) return null;
    out[i] = 0x80 + k;
  }
  return out;
}

/**
 * نصوص عربية «مخربطة» شائعة في ملفات الترجمة:
 *  - «Ø§Ù„Ø³Ù„Ø§Ù…»: نص UTF-8 فُتح كـ Windows-1252 ثم حُفظ من جديد.
 *  - «ÇáÓáÇã»: نص Windows-1256 فُتح كـ Windows-1252 ثم حُفظ من جديد.
 * نصلحها فقط إذا كان الناتج عربياً بوضوح، حتى لا نلمس ترجمات فرنسية أو إسبانية سليمة.
 */
function subs_repairMojibake(text) {
  if (subs_countOf(text, subs_ARABIC) > 0) return { text, repaired: '' };
  const letters = subs_countOf(text, subs_LETTER);
  const latin1 = subs_countOf(text, /[À-ÿ]/g);
  if (!letters || latin1 / letters < 0.25) return { text, repaired: '' };
  const bytes = subs_bytesAs1252(text);
  if (!bytes) return { text, repaired: '' };

  const pattern = subs_countOf(text, /[ØÙÚÛ][\u0080-¿ŒœŠšŸŽžƒˆ˜–-›€™]/g);
  if (pattern > 3) {
    try {
      const fixed = subs_decodeWith('utf-8', bytes, true);
      if (subs_countOf(fixed, subs_ARABIC) > letters * 0.3) return { text: fixed, repaired: 'utf-8-as-1252' };
    } catch { /* ليس هذا النوع */ }
  }
  const fixed = subs_decode1256(bytes);
  if (subs_countOf(fixed, subs_ARABIC) > subs_countOf(fixed, subs_LETTER) * 0.6) return { text: fixed, repaired: '1256-as-1252' };
  return { text, repaired: '' };
}

/**
 * يحول بايتات ملف الترجمة إلى نص: UTF-8 (مع أو بدون BOM)، UTF-16، أو Windows-1256 العربي القديم،
 * ثم يصلح النص المخربط إن وُجد.
 * @param {Uint8Array} bytes
 * @returns {{ text: string, encoding: string }}
 */
function subs_decodeSubtitleBytes(bytes) {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  if (b[0] === 0xef && b[1] === 0xbb && b[2] === 0xbf) return finish(subs_decodeWith('utf-8', b.subarray(3)), 'utf-8');
  if (b[0] === 0xff && b[1] === 0xfe) return finish(subs_decodeUtf16(b.subarray(2), true), 'utf-16le');
  if (b[0] === 0xfe && b[1] === 0xff) return finish(subs_decodeUtf16(b.subarray(2), false), 'utf-16be');

  // UTF-16 بدون BOM: نصف البايتات تقريباً أصفار
  const sample = Math.min(b.length, 4000);
  let evenZero = 0;
  let oddZero = 0;
  for (let i = 0; i < sample; i++) if (b[i] === 0) (i % 2 ? oddZero++ : evenZero++);
  if (sample > 20 && oddZero > sample * 0.2 && evenZero < sample * 0.02) return finish(subs_decodeUtf16(b, true), 'utf-16le');
  if (sample > 20 && evenZero > sample * 0.2 && oddZero < sample * 0.02) return finish(subs_decodeUtf16(b, false), 'utf-16be');

  try {
    return finish(subs_decodeWith('utf-8', b, true), 'utf-8');
  } catch {
    // ليس UTF-8 صالحاً: غالباً ملف عربي قديم بترميز Windows-1256
    return finish(subs_decode1256(b), 'windows-1256');
  }

  function finish(text, encoding) {
    const r = subs_repairMojibake(text.replace(/^﻿/, ''));
    return { text: r.text, encoding: r.repaired ? `${encoding}+${r.repaired}` : encoding };
  }
}


// =============================================================================
// ARABIC SUBTITLES (v10) — SubDL (main) -> SubSource (backup) -> OpenSubtitles.com (extra)
// =============================================================================
// ADDITIVE: nothing above was changed. Routes: POST /admin/subs/search, /admin/subs/fetch, /admin/subs/season,
// GET /admin/subs/status. Secrets (Settings -> Variables and Secrets):
//   SUBDL_API_KEY            main source
//   SUBSOURCE_API_KEY        backup source
//   OPENSUBTITLES_API_KEY    extra source (+ OPENSUBTITLES_USERNAME / OPENSUBTITLES_PASSWORD to download)
// A found subtitle is downloaded, unzipped if needed, decoded (UTF-8 / UTF-16 / Windows-1256 / mojibake), cleaned
// into a UTF-8 .srt, optionally uploaded to R2 next to the video and linked to the item's `subtitle` field.

const SUBS_UA = "CigramAdmin/1.0";
const SUBS_MAX_ZIP = 6 * 1024 * 1024;
const SUBS_TIMEOUT_MS = 12000;

function subsKeys(env) {
  return {
    subdl: cleanString(env.SUBDL_API_KEY),
    subsource: cleanString(env.SUBSOURCE_API_KEY),
    opensubtitles: cleanString(env.OPENSUBTITLES_API_KEY),
    opensubtitles_login: Boolean(cleanString(env.OPENSUBTITLES_USERNAME) && cleanString(env.OPENSUBTITLES_PASSWORD)),
  };
}

async function subsFetch(url, init = {}, timeout = SUBS_TIMEOUT_MS) {
  const ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
  const timer = ctrl ? setTimeout(() => ctrl.abort(), timeout) : null;
  try {
    return await fetch(url, Object.assign({}, init, ctrl ? { signal: ctrl.signal } : {}));
  } finally {
    if (timer) clearTimeout(timer);
  }
}

// ----------------------------------------------------------------- the title to look for
function subsParseRef(text) {
  const t = cleanString(text);
  const imdb = t.match(/tt\d{5,12}/i);
  const tmdb = t.match(/themoviedb\.org\/(movie|tv)\/(\d+)/i);
  return { imdb: imdb ? imdb[0].toLowerCase() : "", tmdb: tmdb ? tmdb[2] : "", tmdbType: tmdb ? (tmdb[1] === "tv" ? "tv" : "movie") : "" };
}

function subsPad(n) {
  return String(Math.max(0, numOf(n))).padStart(2, "0");
}

/** Everything the sources need: type, ids, title, season / episode and a release hint. */
async function subsResolveTarget(env, input, list) {
  const t = { type: "", title: "", original: "", year: "", imdb_id: "", tmdb_id: "", season: 0, episode: 0, release: cleanString(input.release), item: null, header: null, slug: cleanString(input.slug) };
  const q = cleanString(input.query || input.title);
  const ref = subsParseRef([input.imdb_id, input.imdb, input.tmdb_id, input.tmdb, input.url, q].join(" "));
  const itemId = cleanString(input.item_id || input.id);
  if (itemId && list) {
    const item = findById(list, itemId);
    if (!item) throw new AdminError("item_not_found", "العنصر غير موجود في المكتبة.", 404);
    t.item = item;
    if (isEpisodeItem(item)) {
      const header = findSeriesHeader(list, item.series_id);
      t.header = header;
      t.type = "tv";
      t.season = numOf(item.season);
      t.episode = numOf(item.episode);
      const h = header || item;
      t.title = cleanString(h.title);
      t.original = cleanString(h.original_title);
      t.year = cleanString(h.year);
      t.imdb_id = imdbOfItem(h) || imdbOfItem(item);
      t.tmdb_id = (header ? tmdbIdOf(header) : "") || vidBoltTmdbId(item.tmdb);
    } else if (isSeriesHeader(item)) {
      t.header = item;
      t.type = "tv";
      t.title = cleanString(item.title);
      t.original = cleanString(item.original_title);
      t.year = cleanString(item.year);
      t.imdb_id = imdbOfItem(item);
      t.tmdb_id = tmdbIdOf(item);
      t.season = numOf(input.season);
      t.episode = numOf(input.episode);
    } else {
      t.type = "movie";
      t.title = cleanString(item.title);
      t.original = cleanString(item.original_title);
      t.year = cleanString(item.year);
      t.imdb_id = imdbOfItem(item);
      t.tmdb_id = vidBoltTmdbId(item.tmdb || item.tmdb_id);
    }
    if (!t.release) t.release = cleanString(item.quality) + " " + cleanString(item.video || item.link).split("/").pop().replace(/\.[a-z0-9]{2,4}$/i, "");
  } else {
    const ty = cleanString(input.type).toLowerCase();
    t.type = ty === "tv" || ty === "series" || ty === "episode" ? "tv" : ty === "movie" || ty === "film" ? "movie" : ref.tmdbType;
    t.title = q.replace(/https?:\/\/\S+/g, "").replace(/tt\d{5,12}/gi, "").trim();
    t.year = cleanString(input.year);
    t.season = numOf(input.season);
    t.episode = numOf(input.episode);
  }
  t.imdb_id = t.imdb_id || ref.imdb;
  t.tmdb_id = t.tmdb_id || ref.tmdb;
  if (!t.type) t.type = t.season > 0 || t.episode > 0 ? "tv" : "movie";
  // missing ids: complete them from TMDB (id <-> IMDb, or a title search) so the sources can match exactly
  try {
    if (t.tmdb_id && !t.imdb_id) {
      const ext = await tmdbGetOrNull(env, "/" + t.type + "/" + t.tmdb_id + "/external_ids", {});
      if (ext && ext.imdb_id) t.imdb_id = cleanString(ext.imdb_id).toLowerCase();
    } else if (t.imdb_id && !t.tmdb_id) {
      const f = await tmdbGetOrNull(env, "/find/" + t.imdb_id, { external_source: "imdb_id" });
      const hit = f && (t.type === "tv" ? f.tv_results : f.movie_results);
      if (hit && hit[0] && hit[0].id) t.tmdb_id = String(hit[0].id);
    } else if (!t.imdb_id && !t.tmdb_id && t.title && !t.item) {
      const r = await tmdbGetOrNull(env, "/search/" + t.type, Object.assign({ query: t.title, language: "en-US" }, t.year && t.type === "movie" ? { year: t.year } : {}));
      const hit = r && Array.isArray(r.results) ? r.results[0] : null;
      if (hit && hit.id) {
        t.tmdb_id = String(hit.id);
        if (!t.original) t.original = cleanString(hit.original_title || hit.original_name);
        const ext = await tmdbGetOrNull(env, "/" + t.type + "/" + t.tmdb_id + "/external_ids", {});
        if (ext && ext.imdb_id) t.imdb_id = cleanString(ext.imdb_id).toLowerCase();
      }
    }
  } catch { /* TMDB not reachable or no key: search by name / the ids we have */ }
  // 18.2: a library title is never matched to subtitles by its name alone
  if (t.item && !t.imdb_id && !t.tmdb_id) throw new AdminError("subs_ids_required", "هذا العنوان بلا معرّف TMDB أو IMDb. أضف المعرّف أولاً: لا تُربط الترجمة بالاسم وحده.", 400);
  if (!t.imdb_id && !t.tmdb_id && !t.title && !t.original) throw new AdminError("subs_missing_target", "أدخل اسم الفيلم أو المسلسل، أو رابط / معرّف IMDb أو TMDB.", 400);
  return t;
}

// ----------------------------------------------------------------- sources
function subsRelTokens(s) {
  return cleanString(s).toLowerCase().replace(/[^a-z0-9]+/g, " ").split(" ").filter((x) => x && x.length > 1);
}

function subsIsArabic(v) {
  const s = cleanString(v).toLowerCase();
  return s === "ar" || s === "ara" || s === "arb" || s.startsWith("arabic") || s === "ar-sa" || s.includes("عرب");
}

async function subsSearchSubdl(env, t) {
  const key = subsKeys(env).subdl;
  if (!key) return { skipped: "no_key", items: [] };
  const base = { api_key: key, languages: "AR", subs_per_page: "30", releases: "1", comment: "1", hi: "1", type: t.type };
  if (t.tmdb_id) base.tmdb_id = t.tmdb_id; else if (t.imdb_id) base.imdb_id = t.imdb_id; else base.film_name = t.original || t.title; // 18.2: TMDB first
  if (t.year && !t.imdb_id && !t.tmdb_id) base.year = t.year;
  const queries = [];
  if (t.type === "tv" && t.season > 0) {
    queries.push(Object.assign({}, base, { season_number: String(t.season), episode_number: String(t.episode || 1) }, t.episode ? {} : { full_season: "1" }));
    if (t.episode) queries.push(Object.assign({}, base, { season_number: String(t.season), full_season: "1" })); // season packs
  } else {
    queries.push(base);
  }
  const out = [];
  let lastError = "";
  for (const params of queries) {
    const u = new URL("https://api.subdl.com/api/v1/subtitles");
    for (const [k, v] of Object.entries(params)) u.searchParams.set(k, v);
    let res;
    try { res = await subsFetch(u.toString(), { headers: { accept: "application/json", "user-agent": SUBS_UA } }); } catch (e) { lastError = "تعذر الاتصال بـ SubDL"; continue; }
    let data = null;
    try { data = await res.json(); } catch { data = null; }
    if (!res.ok || !data || data.status === false) { lastError = (data && (data.error || data.message)) || "SubDL " + res.status; await subhubNote(env, "subdl", false, lastError); continue; }
    await subhubNote(env, "subdl", true, "search");
    for (const s of Array.isArray(data.subtitles) ? data.subtitles : []) {
      if (!subsIsArabic(s.language || s.lang)) continue;
      if (!s.url) continue;
      out.push({
        source: "subdl",
        id: cleanString(s.url),
        name: cleanString(s.name || s.release_name),
        release: cleanString(s.release_name || s.name),
        author: cleanString(s.author),
        downloads: 0,
        rating: 0,
        hi: Boolean(s.hi),
        season: numOf(s.season),
        episode: numOf(s.episode),
        full_season: Boolean(s.full_season) || (t.type === "tv" && !numOf(s.episode)),
        dl: "https://dl.subdl.com" + (String(s.url).startsWith("/") ? "" : "/") + s.url,
      });
    }
  }
  return { items: out, error: out.length ? "" : lastError };
}

async function subsSearchSubsource(env, t) {
  const key = subsKeys(env).subsource;
  if (!key) return { skipped: "no_key", items: [] };
  const H = { accept: "application/json", "x-api-key": key, "user-agent": SUBS_UA };
  const api = "https://api.subsource.net/api/v1";
  try {
    const u = new URL(api + "/movies/search");
    if (t.imdb_id) { u.searchParams.set("searchType", "imdb"); u.searchParams.set("imdb", t.imdb_id); }
    else { u.searchParams.set("searchType", "text"); u.searchParams.set("q", t.original || t.title); }
    if (t.type === "tv" && t.season > 0) u.searchParams.set("season", String(t.season));
    const r1 = await subsFetch(u.toString(), { headers: H });
    const d1 = await r1.json().catch(() => null);
    if (!r1.ok || !d1) return { items: [], error: "SubSource " + r1.status };
    const rows = Array.isArray(d1) ? d1 : Array.isArray(d1.data) ? d1.data : Array.isArray(d1.results) ? d1.results : [];
    const wantTv = t.type === "tv";
    const pick = rows.find((m) => (wantTv ? /tv|series/i.test(cleanString(m.type)) : !/tv|series/i.test(cleanString(m.type))) && (!t.season || !numOf(m.season) || numOf(m.season) === t.season)) || rows[0];
    const movieId = pick && (pick.movieId || pick.id || pick.linkName);
    if (!movieId) return { items: [], error: "" };
    const u2 = new URL(api + "/subtitles");
    u2.searchParams.set("movieId", String(movieId));
    u2.searchParams.set("language", "arabic");
    u2.searchParams.set("sort", "popular");
    u2.searchParams.set("limit", "50");
    const r2 = await subsFetch(u2.toString(), { headers: H });
    const d2 = await r2.json().catch(() => null);
    if (!r2.ok || !d2) return { items: [], error: "SubSource " + r2.status };
    const subs = Array.isArray(d2) ? d2 : Array.isArray(d2.data) ? d2.data : Array.isArray(d2.subtitles) ? d2.subtitles : [];
    const items = [];
    for (const s of subs) {
      const id = s.subtitleId || s.id;
      if (!id) continue;
      const rel = Array.isArray(s.releaseInfo) ? s.releaseInfo.join(" ") : cleanString(s.releaseInfo || s.release || s.name);
      const epMatch = rel.match(/s(\d{1,2})e(\d{1,3})/i);
      items.push({
        source: "subsource", id: String(id), name: rel, release: rel, author: cleanString(s.author || (s.contributors && s.contributors[0] && s.contributors[0].displayname)),
        downloads: numOf(s.downloads || s.downloadCount), rating: Number(s.rating && s.rating.total !== undefined ? s.rating.total : s.rating) || 0,
        hi: Boolean(s.hearingImpaired || s.hi), season: epMatch ? numOf(epMatch[1]) : numOf(s.season), episode: epMatch ? numOf(epMatch[2]) : numOf(s.episode),
        full_season: !epMatch && t.type === "tv", dl: api + "/subtitles/" + encodeURIComponent(id) + "/download",
      });
    }
    return { items };
  } catch (e) {
    return { items: [], error: "تعذر الاتصال بـ SubSource" };
  }
}



// ----------------------------------------------------------------- choosing the best one
function subsScore(c, t) {
  let s = 40;
  if (t.type === "tv") {
    if (t.episode && c.season === t.season && c.episode === t.episode) s += 30;
    else if (c.full_season && (!c.season || c.season === t.season)) s += 8;
    else if (t.episode && c.episode && c.episode !== t.episode) s -= 60;
    else if (t.season && c.season && c.season !== t.season) s -= 60;
  }
  const want = subsRelTokens(t.release);
  const have = new Set(subsRelTokens(c.release || c.name));
  if (want.length && have.size) {
    const inter = want.filter((x) => have.has(x));
    s += Math.round((inter.length / want.length) * 14);
    for (const q of ["2160p", "4k", "1080p", "720p", "480p"]) if (want.includes(q) && have.has(q)) s += 6;
    for (const q of ["bluray", "brrip", "bdrip", "web", "webdl", "webrip", "hdtv", "dvdrip", "hdrip"]) if (want.includes(q) && have.has(q)) s += 5;
    const group = want[want.length - 1];
    if (group && have.has(group)) s += 6;
  }
  s += Math.min(12, Math.round(Math.log10(c.downloads + 1) * 4));
  s += Math.min(10, Math.round((c.rating > 10 ? c.rating / 10 : c.rating) * 1.2));
  if (c.trusted) s += 4;
  if (c.hi) s -= 6;
  if (c.ai_translated) s -= 8;
  if (c.source === "subdl") s += 3;
  return Math.max(0, Math.min(100, s));
}

async function subsSearchAll(env, t, { all = false } = {}) {
  const keys = subsKeys(env);
  if (!keys.subdl && !keys.subsource && !keys.opensubtitles) {
    throw new AdminError("subs_no_keys", "لم يُضبط أي مصدر ترجمة. أضف SUBDL_API_KEY (الرئيسي) في Cloudflare ← Settings ← Variables and Secrets، وSUBSOURCE_API_KEY وOPENSUBTITLES_API_KEY للاحتياط.", 400);
  }
  const sources = [["subdl", subsSearchSubdl], ["subsource", subsSearchSubsource], ["opensubtitles", subsSearchOpenSubs]];
  const items = [];
  const status = {};
  for (const [name, fn] of sources) {
    // the backups are only asked when the previous sources gave nothing usable (or the admin asked for everything)
    const usable = items.some((c) => subsScore(c, t) >= 55);
    if (!all && usable) { status[name] = "not_needed"; continue; }
    const r = await fn(env, t);
    status[name] = r.skipped ? "no_key" : r.error ? "error: " + r.error : r.items.length + " arabic";
    for (const c of r.items) { c.score = subsScore(c, t); items.push(c); }
  }
  items.sort((a, b) => b.score - a.score);
  return { items, status };
}

// ----------------------------------------------------------------- download, unzip, clean
async function subsInflateRaw(bytes) {
  const ds = new DecompressionStream("deflate-raw");
  const stream = new Blob([bytes]).stream().pipeThrough(ds);
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Minimal ZIP reader (stored + deflate). Returns [{ name, data }] for the files whose name matches `want`. */
async function subsUnzip(bytes, want) {
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new AdminError("subs_bad_zip", "ملف الترجمة المضغوط تالف.", 502);
  const count = dv.getUint16(eocd + 10, true);
  let p = dv.getUint32(eocd + 16, true);
  const out = [];
  for (let n = 0; n < count && p + 46 <= bytes.length; n++) {
    if (dv.getUint32(p, true) !== 0x02014b50) break;
    const flags = dv.getUint16(p + 8, true);
    const method = dv.getUint16(p + 10, true);
    const csize = dv.getUint32(p + 20, true);
    const nameLen = dv.getUint16(p + 28, true);
    const extraLen = dv.getUint16(p + 30, true);
    const commentLen = dv.getUint16(p + 32, true);
    const local = dv.getUint32(p + 42, true);
    const rawName = bytes.subarray(p + 46, p + 46 + nameLen);
    let name;
    try { name = new TextDecoder(flags & 0x800 ? "utf-8" : "utf-8").decode(rawName); } catch { name = ""; }
    p += 46 + nameLen + extraLen + commentLen;
    if (!want.test(name) || /\/$/.test(name) || /__MACOSX/.test(name)) continue;
    if (dv.getUint32(local, true) !== 0x04034b50) continue;
    const dataStart = local + 30 + dv.getUint16(local + 26, true) + dv.getUint16(local + 28, true);
    const comp = bytes.subarray(dataStart, dataStart + csize);
    let data;
    if (method === 0) data = comp;
    else if (method === 8) data = await subsInflateRaw(comp);
    else continue;
    out.push({ name, data });
  }
  return out;
}

function subsEpisodeOf(name) {
  const n = cleanString(name).split("/").pop();
  let m = n.match(/s(\d{1,2})[\s._-]*e(\d{1,3})/i) || n.match(/(\d{1,2})x(\d{1,3})/i);
  if (m) return { season: numOf(m[1]), episode: numOf(m[2]) };
  m = n.match(/(?:^|[^a-z0-9])e(?:p(?:isode)?)?[\s._-]*(\d{1,3})(?![0-9])/i) || n.match(/الحلقة[\s._-]*(\d{1,3})/);
  return m ? { season: 0, episode: numOf(m[1]) } : null;
}

const SUBS_STAMP = "(?:\\d{1,3}:)?\\d{1,2}:\\d{1,2}(?:[,.:]\\d{1,3})?";
const SUBS_TIMING = new RegExp("^\\s*(" + SUBS_STAMP + ")\\s*-{1,2}>\\s*(" + SUBS_STAMP + ")(.*)$");

function subsStamp(stamp) {
  const m = /^(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?$/.exec(stamp.trim());
  if (!m) return null;
  const ms = m[4] ? Math.round(parseFloat("0." + m[4]) * 1000) : 0;
  const total = (+(m[1] || 0)) * 3600000 + (+m[2]) * 60000 + (+m[3]) * 1000 + ms;
  const h = Math.floor(total / 3600000), mi = Math.floor((total % 3600000) / 60000), se = Math.floor((total % 60000) / 1000), mm = total % 1000;
  return String(h).padStart(2, "0") + ":" + String(mi).padStart(2, "0") + ":" + String(se).padStart(2, "0") + "," + String(mm).padStart(3, "0");
}

/** SRT / VTT text -> clean UTF-8 SRT (numbered, normal timestamps, ASS tags and empty cues removed). */
function subsToSrt(text) {
  const lines = String(text).replace(/^﻿/, "").replace(/\r\n?/g, "\n").split("\n");
  const cues = [];
  let cur = null;
  for (let i = 0; i < lines.length; i++) {
    const m = SUBS_TIMING.exec(lines[i]);
    if (m) {
      const a = subsStamp(m[1]);
      const b = subsStamp(m[2]);
      cur = a && b ? { a, b, text: [] } : null;
      if (cur) cues.push(cur);
      continue;
    }
    if (!cur) continue;
    const l = lines[i];
    if (l.trim() === "") { cur = null; continue; }
    if (/^\d+$/.test(l.trim()) && lines[i + 1] && SUBS_TIMING.test(lines[i + 1])) { cur = null; continue; }
    cur.text.push(l.replace(/\{\\[^}]*\}/g, "").replace(/\\h/g, " ").replace(/\u0000/g, ""));
  }
  const good = cues.filter((c) => c.text.join("").trim().length > 0);
  const out = good.map((c, i) => (i + 1) + "\n" + c.a + " --> " + c.b + "\n" + c.text.map((x) => x.trimEnd()).join("\n"));
  return { srt: out.join("\n\n") + (out.length ? "\n" : ""), cues: good.length };
}

function subsArabicShare(text) {
  const letters = (text.match(/\p{L}/gu) || []).length;
  const arabic = (text.match(subs_ARABIC) || []).length;
  return letters ? arabic / letters : 0;
}

/** Downloads one candidate and returns { srt, cues, encoding, file } or throws. */
async function subsDownload(env, c, t) {
  let bytes;
  if (c.dl.startsWith("opensubtitles:")) {
    bytes = await osDownloadBytes(env, c.id);   // 18.2: session + base_url + quota handling (see subhub_providers.js)
  } else {
    const headers = { "user-agent": SUBS_UA };
    if (c.source === "subsource") headers["x-api-key"] = subsKeys(env).subsource;
    const f = await subsFetch(c.dl, { headers }, 20000);
    if (!f.ok) throw new AdminError("subs_download_failed", c.source + " " + f.status, 502);
    bytes = new Uint8Array(await f.arrayBuffer());
  }
  if (bytes.length > SUBS_MAX_ZIP) throw new AdminError("subs_too_big", "الملف كبير جداً.", 502);
  let files;
  if (bytes[0] === 0x50 && bytes[1] === 0x4b) {
    files = await subsUnzip(bytes, /\.(srt|vtt)$/i);
  } else if (bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const ds = new DecompressionStream("gzip");
    files = [{ name: "subtitle.srt", data: new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(ds)).arrayBuffer()) }];
  } else {
    files = [{ name: "subtitle.srt", data: bytes }];
  }
  if (!files.length) throw new AdminError("subs_no_srt", "لا يوجد ملف SRT داخل الأرشيف.", 502);
  // a season pack: take the file of the wanted episode
  let chosen = files[0];
  if (files.length > 1 || c.full_season) {
    let best = null;
    for (const f of files) {
      const e = subsEpisodeOf(f.name);
      if (!e) continue;
      if (t.episode && e.episode === t.episode && (!e.season || !t.season || e.season === t.season)) { best = f; break; }
    }
    if (!best && files.length > 1) throw new AdminError("subs_episode_not_in_pack", "لم أجد الحلقة المطلوبة داخل حزمة الموسم.", 502);
    if (best) chosen = best;
  }
  const dec = subs_decodeSubtitleBytes(chosen.data);
  const parsed = subsToSrt(dec.text);
  if (parsed.cues < 5) throw new AdminError("subs_empty", "الملف لا يحتوي ترجمة صالحة.", 502);
  if (subsArabicShare(parsed.srt) < 0.3) throw new AdminError("subs_not_arabic", "الملف ليس بالعربية.", 502);
  return { srt: parsed.srt, cues: parsed.cues, encoding: dec.encoding, file: chosen.name };
}

// ----------------------------------------------------------------- storing
function subsKeyFor(list, t) {
  let slug = "";
  if (t.type === "tv") {
    const h = t.header;
    slug = t.slug && validSlugFormat(t.slug) ? t.slug : h ? slugForSeries(list, h, "") : "";
    if (!slug && h) { for (const e of episodesOf(list, h)) { slug = inferSlugFromUrl(e.video || e.link); if (slug) break; } }
    if (!slug) slug = slugifyLatin(t.original || t.title) || "series-" + (t.tmdb_id || "x");
    return slug + "/season-" + subsPad(t.season) + "/S" + subsPad(t.season) + "E" + subsPad(t.episode) + ".ar.srt";
  }
  const it = t.item;
  slug = (t.slug && validSlugFormat(t.slug) ? t.slug : "") || (it && cleanString(it.slug)) || (it && inferSlugFromUrl(it.video || it.link)) || slugifyLatin(t.original || t.title) + (t.year ? "-" + t.year : "") || "movie-" + (t.tmdb_id || "x");
  return slug + "/" + slug + ".ar.srt";
}

async function subsStore(env, key, srt) {
  await env.MOVIES_BUCKET.put(key, srt, {
    httpMetadata: { contentType: "application/x-subrip; charset=utf-8", cacheControl: "public, max-age=300" },
    customMetadata: { source: "subs-v10", at: new Date().toISOString() },
  });
  return r2PublicUrl(key);
}

/** Tries the best candidates in order (different sources too) until one downloads as a real Arabic SRT. */
async function subsGetBest(env, t, candidates, picked) {
  const order = picked ? [picked].concat(candidates.filter((c) => !(c.source === picked.source && c.id === picked.id))) : candidates;
  const errors = [];
  let tries = 0;
  for (const c of order) {
    if (tries >= 4) break;
    tries++;
    try {
      const got = await subsDownload(env, c, t);
      return Object.assign({ candidate: c }, got);
    } catch (e) {
      errors.push(c.source + ": " + cleanString(e && e.message).slice(0, 120));
    }
  }
  throw new AdminError("subs_none_worked", "وُجدت نتائج لكن لم ينجح تحميل أي ترجمة عربية صالحة منها." + (errors.length ? " (" + errors.join(" | ") + ")" : ""), 502);
}

// ----------------------------------------------------------------- routes
function subsPublic(c) {
  return { source: c.source, id: c.id, name: c.name, release: c.release, author: c.author, downloads: c.downloads, rating: c.rating, hi: c.hi, season: c.season, episode: c.episode, full_season: c.full_season, score: c.score, ai_translated: Boolean(c.ai_translated), trusted: Boolean(c.trusted), dl: c.dl.startsWith("http") ? "" : c.dl };
}

function subsTargetPublic(t) {
  return { type: t.type, title: t.title, original_title: t.original, year: t.year, imdb_id: t.imdb_id, tmdb_id: t.tmdb_id, season: t.season, episode: t.episode, release: t.release };
}

async function handleSubsStatus(request, env) {
  const k = subsKeys(env);
  return okResponse({ subdl: Boolean(k.subdl), subsource: Boolean(k.subsource), opensubtitles: Boolean(k.opensubtitles), opensubtitles_download: k.opensubtitles_login });
}

async function handleSubsSearch(request, env) {
  const input = await readRequestData(request);
  const list = cleanString(input.item_id || input.id) ? await readMoviesList(env) : null;
  const t = await subsResolveTarget(env, input, list);
  const { items, status } = await subsSearchAll(env, t, { all: truthy(input.all) });
  return okResponse({ target: subsTargetPublic(t), results: items.slice(0, 40).map(subsPublic), status, best: items[0] ? subsPublic(items[0]) : null },
    items.length ? "وُجدت " + items.length + " ترجمة عربية." : "لم أجد ترجمة عربية لهذا العنوان في المصادر المتاحة.");
}

async function subsDoFetch(env, input, list) {
  const t = await subsResolveTarget(env, input, list);
  let picked = null;
  if (input.pick && cleanString(input.pick.id)) {
    const src = cleanString(input.pick.source);
    picked = { source: src, id: cleanString(input.pick.id), name: cleanString(input.pick.name), release: cleanString(input.pick.release), season: numOf(input.pick.season), episode: numOf(input.pick.episode), full_season: truthy(input.pick.full_season), downloads: 0, rating: 0, hi: false, score: 0,
      dl: src === "opensubtitles" ? "opensubtitles:" + cleanString(input.pick.id) : src === "subdl" ? "https://dl.subdl.com" + (cleanString(input.pick.id).startsWith("/") ? "" : "/") + cleanString(input.pick.id) : "https://api.subsource.net/api/v1/subtitles/" + encodeURIComponent(cleanString(input.pick.id)) + "/download" };
  }
  const { items, status } = await subsSearchAll(env, t, { all: Boolean(picked) ? false : truthy(input.all) });
  if (!items.length && !picked) throw new AdminError("subs_not_found", "لم أجد ترجمة عربية لهذا العنوان في المصادر المتاحة. جرّب إضافة معرّف IMDb أو TMDB.", 404);
  const got = await subsGetBest(env, t, items, picked);
  const out = { target: subsTargetPublic(t), source: got.candidate.source, release: got.candidate.release, score: got.candidate.score, cues: got.cues, encoding: got.encoding, file: got.file, status };
  const mode = cleanString(input.mode) || "text";
  if (mode === "text") {
    out.srt = got.srt.length > 700000 ? got.srt.slice(0, 700000) : got.srt;
    out.preview = got.srt.split("\n\n").slice(0, 6).join("\n\n");
    return { out, t, got, list };
  }
  const lst = list || (await readMoviesList(env));
  const key = subsKeyFor(lst, t);
  out.key = key;
  await subsGuardOverwrite(env, t.item, key, input);   // 18.2: existing / approved subtitles are never replaced silently
  out.url = await subsStore(env, key, got.srt);
  out.bytes = got.srt.length;
  return { out, t, got, list: lst };
}

async function handleSubsFetch(request, env) {
  const input = await readRequestData(request);
  const needList = cleanString(input.item_id || input.id) || cleanString(input.mode) !== "text";
  const list = needList ? await readMoviesList(env) : null;
  const r = await subsDoFetch(env, input, list);
  if (truthy(input.attach) && cleanString(input.item_id || input.id) && r.out.url) {
    const id = cleanString(input.item_id || input.id);
    const url = r.out.url;
    const res = await mutateMovies(env, (live, holder, ctx) => {
      const it = findById(live, id);
      if (!it) throw new AdminError("item_not_found", "العنصر لم يعد موجوداً.", 404);
      ctx.tracker.touch(it);
      it.subtitle = url;
      return { label: "ترجمة عربية تلقائية: " + cleanString(it.title) + (isEpisodeItem(it) ? " (م" + numOf(it.season) + " ح" + numOf(it.episode) + ")" : "") };
    }, { op: "subs-attach" });
    r.out.attached = true;
    r.out.undo_id = res.undo_id;
  }
  return okResponse(r.out, r.out.attached ? "تم جلب الترجمة العربية وربطها." : r.out.url ? "تم رفع الترجمة." : "تم جلب الترجمة.");
}

/** One batch of a whole season: the episodes without a subtitle (or all with overwrite). The app calls it until done. */
async function handleSubsSeason(request, env) {
  const input = await readRequestData(request);
  const list = await readMoviesList(env);
  const header = findSeriesHeader(list, input.series_id);
  if (!header) throw new AdminError("series_not_found", "المسلسل غير موجود.", 404);
  const season = numOf(input.season);
  if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
  const overwrite = truthy(input.overwrite);
  const eps = episodesOf(list, header).filter((e) => numOf(e.season) === season).sort((a, b) => numOf(a.episode) - numOf(b.episode));
  const todo = eps.filter((e) => overwrite || !cleanString(e.subtitle));
  const offset = Math.max(0, numOf(input.offset));
  const batch = Math.min(4, Math.max(1, numOf(input.batch) || 3));
  const slice = todo.slice(offset, offset + batch);
  const results = [];
  const attach = [];
  for (const ep of slice) {
    const row = { id: cleanString(ep.id), episode: numOf(ep.episode), title: cleanString(ep.title) };
    try {
      const r = await subsDoFetch(env, { item_id: ep.id, mode: "upload", overwrite: overwrite }, list);
      row.ok = true; row.url = r.out.url; row.source = r.out.source; row.score = r.out.score; row.cues = r.out.cues;
      attach.push([ep.id, r.out.url]);
    } catch (e) {
      row.ok = false;
      row.error = cleanString(e && e.message).slice(0, 160);
      if (e instanceof AdminError && e.code === "subs_no_keys") throw e;
    }
    results.push(row);
  }
  let undo = "";
  if (attach.length) {
    const res = await mutateMovies(env, (live, holder, ctx) => {
      for (const [id, url] of attach) {
        const it = findById(live, id);
        if (!it) continue;
        ctx.tracker.touch(it);
        it.subtitle = url;
      }
      return { label: "ترجمة عربية تلقائية: " + cleanString(header.title) + " — الموسم " + season + " (" + attach.length + ")" };
    }, { op: "subs-attach" });
    undo = res.undo_id;
  }
  // The attached episodes leave the "todo" list on the next call, so only the failed ones move the offset forward.
  const failed = results.filter((r) => !r.ok).length;
  const next = offset + failed;
  const remaining = Math.max(0, todo.length - attach.length);
  return okResponse({ season, total: todo.length, remaining, done: next >= remaining, next_offset: next, results, undo_id: undo, linked: attach.length },
    attach.length + " ترجمة رُبطت.");
}

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
  // 18.2 Cigram Discovery: TMDB v3 lists /trending/{type}/day, /movie/top_rated, /tv/top_rated, /tv/on_the_air
  "movie:trending_day", "tv:trending_day", "all:trending_day", "movie:top_rated", "tv:top_rated", "tv:on_the_air",
];
const WN_VIEW_KEYS = ["recently_added", "ready", "unverified", "missing_video", "missing_subtitle", "imported", "collections"];
const WN_HEARTBEAT_FILE = "whatsnew/heartbeat.json";
const WN_SORTS = { popularity: 1, rating: 1, release: 1 };

const WN_DEFAULT_CONFIG = {
  version: 1,
  auto_sync: true,          // first-hand updates by default (needs the Cron Trigger + a TMDB key; stays quiet without them)
  notify_telegram: true,    // one Telegram message per sync that found new titles (works with the app closed)
  interval_minutes: 60,
  language: "ar-SA",
  region: "",
  feeds: ["movie:new_releases", "movie:trending", "tv:recent_episodes", "tv:new_releases", "tv:trending"],
  max_pages: 2,
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

// ----------------------------------------------------------------------------- cron heartbeat
// The statistics (ops compaction + live history), the Telegram request alerts and the automatic sync ALL run from the
// Worker's Cron Trigger. If a redeploy drops the trigger they silently stop, so every tick leaves a heartbeat that the
// app can show (summary.cron) instead of failing quietly.

async function wnHeartbeat(env) {
  const doc = await wnRead(env, WN_HEARTBEAT_FILE, { at: 0 });
  if (Date.now() - (Number(doc.at) || 0) < 120000) return;
  await wnMutate(env, WN_HEARTBEAT_FILE, { at: 0 }, (d) => {
    d.at = Date.now();
    d.runs = (Number(d.runs) || 0) + 1;
    return { doc: d };
  });
}

async function wnCronStatus(env) {
  const doc = await wnRead(env, WN_HEARTBEAT_FILE, { at: 0 });
  const at = Number(doc.at) || 0;
  const age = at ? Math.round((Date.now() - at) / 1000) : -1;
  return { last_seen_at: at ? new Date(at).toISOString() : "", age_seconds: age, healthy: at > 0 && age < 600 };
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
  cfg.notify_telegram = cfg.notify_telegram !== false;
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
  const col = ar.belongs_to_collection || en.belongs_to_collection;
  meta.collection = col && col.id ? { id: Number(col.id), name: cleanString(col.name), poster: tmdbImage(col.poster_path, "w342"), backdrop: tmdbImage(col.backdrop_path, "w780") } : null;
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


// ----------------------------------------------------------------------------- video sources (adapter + honest validation)
//
// Four levels, never conflated:
//   generated  a URL was BUILT from the TMDB id (no network proof of anything)
//   resolved   the provider itself confirmed it has the title (needs a provider lookup API — VidBolt has none)
//   reachable  the provider's page answered 200 with HTML that is not an error page (an embed: playback unproven)
//   playable   we fetched a media file / HLS playlist and parsed it (container magic, playlist, first segment)

const WN_VERDICTS = {
  playable: { level: "playable", importable: true, ack: false, rank: 4, label: "فيديو صالح للتشغيل (تم التحقق منه)" },
  embed_reachable: { level: "reachable", importable: true, ack: true, rank: 3, label: "صفحة المشغّل تعمل — لا يمكن إثبات التشغيل من الخادم" },
  unverified: { level: "generated", importable: true, ack: true, rank: 2, label: "المزوّد يمنع الفحص الآلي — الرابط غير مُتحقَّق منه" },
  unavailable: { level: "failed", importable: false, ack: false, rank: 0, label: "المزوّد لا يملك هذا العنوان" },
  unreachable: { level: "failed", importable: false, ack: false, rank: 0, label: "تعذر الوصول إلى الرابط" },
  invalid: { level: "failed", importable: false, ack: false, rank: 0, label: "الرابط لا يشير إلى فيديو صالح" },
  drm: { level: "failed", importable: false, ack: false, rank: 0, label: "المحتوى محمي بـ DRM ولا يمكن التحقق منه" },
  mismatch: { level: "failed", importable: false, ack: false, rank: 0, label: "الرابط لا يطابق الموسم/الحلقة المطلوبة" },
  not_released: { level: "generated", importable: false, ack: false, rank: 0, label: "لم يصدر بعد" },
};

function wnCheck(id, label, pass, detail) {
  return { id, label, pass, detail: detail === undefined ? "" : String(detail).slice(0, 200) };
}

/** Fetch with a timeout and MANUAL redirects: every hop is re-validated, so a redirect cannot reach an internal host. */
async function wnProbeFetch(rawUrl, init, timeoutMs) {
  let url = wnSafeUrl(rawUrl, { allowHttp: true }).toString();
  const started = Date.now();
  for (let hop = 0; hop < 4; hop++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs || WN_PROBE_TIMEOUT_MS);
    let res;
    try {
      res = await fetch(url, Object.assign({}, init, { signal: controller.signal, redirect: "manual" }));
    } finally {
      clearTimeout(timer);
    }
    if (res.status >= 300 && res.status < 400 && res.headers.get("location")) {
      const next = new URL(res.headers.get("location"), url).toString();
      try { await res.body?.cancel(); } catch { /* ignore */ }
      url = wnSafeUrl(next, { allowHttp: true }).toString();
      continue;
    }
    return { res, url, ms: Date.now() - started };
  }
  throw new AdminError("too_many_redirects", "الرابط يعيد التوجيه مرات كثيرة.", 400);
}

function wnErrorPage(html) {
  const title = ((html.match(/<title[^>]*>([^<]{0,200})/i) || [])[1] || "").trim();
  if (/(^|\b)(404|403|410|not found|page not found|no results|unavailable|error|forbidden)(\b|$)|غير موجود|غير متاح/i.test(title)) return title || "error";
  if (/<meta[^>]+http-equiv=["']?refresh/i.test(html)) return "meta refresh";
  return "";
}

function wnHintOfUrl(url) {
  const name = decodeURIComponent(cleanString(url).split("?")[0].split("/").pop() || "");
  const m = name.match(/s(\d{1,2})[\s._-]*e(\d{1,3})/i) || name.match(/(?:^|[^0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)/i);
  return m ? { season: numOf(m[1]), episode: numOf(m[2]) } : null;
}

function wnDetectKind(url, fallback) {
  const path = cleanString(url).split("?")[0].toLowerCase();
  if (/\.m3u8$/.test(path)) return "hls";
  if (/\.(mp4|m4v|webm|mkv|mov)$/.test(path)) return "file";
  return fallback === "hls" || fallback === "file" ? fallback : "embed";
}

function wnFinishSource(base, verdict, checks, extra) {
  const meta = WN_VERDICTS[verdict];
  return Object.assign(
    {
      provider: base.provider,
      name: base.name,
      kind: base.kind,
      url: base.url,
      verdict,
      level: meta.level,
      importable: meta.importable,
      needs_ack: meta.ack,
      label: meta.label,
      checks,
      validated_at: new Date().toISOString(),
    },
    extra || {}
  );
}

async function wnProbeEmbed(base, checks) {
  let probe;
  try {
    probe = await wnProbeFetch(base.url, { method: "GET", headers: { accept: "text/html,*/*;q=0.5", range: "bytes=0-65535", "user-agent": WN_UA } });
  } catch (error) {
    const aborted = error && (error.name === "AbortError" || /abort/i.test(cleanString(error.message)));
    checks.push(wnCheck("http", "الاتصال بالمزوّد", false, aborted ? "انتهت المهلة" : cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const { res, ms } = probe;
  const type = cleanString(res.headers.get("content-type")).split(";")[0].toLowerCase();
  const extra = { http: res.status, ms, content_type: type };
  try {
    if (res.status === 404 || res.status === 410) {
      checks.push(wnCheck("http", "استجابة المزوّد", false, "HTTP " + res.status));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    if (res.status === 401 || res.status === 403 || res.status === 429) {
      checks.push(wnCheck("http", "استجابة المزوّد", null, "HTTP " + res.status + " — يمنع الفحص الآلي"));
      return wnFinishSource(base, "unverified", checks, extra);
    }
    if (res.status >= 400) {
      checks.push(wnCheck("http", "استجابة المزوّد", false, "HTTP " + res.status));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    checks.push(wnCheck("http", "استجابة المزوّد", true, "HTTP " + res.status + " · " + ms + "ms"));
    if (!/text\/html|application\/xhtml/.test(type)) {
      checks.push(wnCheck("content_type", "نوع المحتوى", false, type || "غير معروف"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const bytes = await readUpTo(res, 65536);
    const html = new TextDecoder("utf-8").decode(bytes);
    if (bytes.length < 120 || !/<[a-z!]/i.test(html)) {
      checks.push(wnCheck("body", "محتوى الصفحة", false, "الصفحة فارغة"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const why = wnErrorPage(html);
    if (why) {
      checks.push(wnCheck("body", "محتوى الصفحة", false, "تبدو صفحة خطأ: " + why));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    checks.push(wnCheck("body", "محتوى الصفحة", true, "صفحة مشغّل (HTML)"));
    checks.push(wnCheck("playback", "إثبات التشغيل", null, "المشغّل مضمَّن (iframe) — لا يمكن للخادم تشغيله أو فحص البث"));
    return wnFinishSource(base, "embed_reachable", checks, extra);
  } finally {
    try { await res.body?.cancel(); } catch { /* ignore */ }
  }
}

async function wnProbeFile(base, checks) {
  let probe;
  try {
    probe = await wnProbeFetch(base.url, { method: "GET", headers: { range: "bytes=0-2047", "user-agent": WN_UA } });
  } catch (error) {
    checks.push(wnCheck("http", "الاتصال", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const { res, ms } = probe;
  const type = cleanString(res.headers.get("content-type")).split(";")[0].toLowerCase();
  const extra = { http: res.status, ms, content_type: type };
  try {
    if (res.status === 404 || res.status === 410) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status));
      return wnFinishSource(base, "unavailable", checks, extra);
    }
    if (res.status === 401 || res.status === 403 || res.status === 429) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status + " — غير مصرّح"));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    if (res.status !== 200 && res.status !== 206) {
      checks.push(wnCheck("http", "الاتصال", false, "HTTP " + res.status));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    checks.push(wnCheck("http", "الاتصال", true, "HTTP " + res.status + " · " + ms + "ms"));
    if (/text\/html|application\/json|text\/plain/.test(type)) {
      checks.push(wnCheck("content_type", "نوع المحتوى", false, type));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const head = await readUpTo(res, 2048);
    const mp4 = head.length >= 12 && head[4] === 0x66 && head[5] === 0x74 && head[6] === 0x79 && head[7] === 0x70; // "ftyp"
    const ebml = head.length >= 4 && head[0] === 0x1a && head[1] === 0x45 && head[2] === 0xdf && head[3] === 0xa3; // webm / mkv
    if (!mp4 && !ebml) {
      checks.push(wnCheck("container", "صيغة الملف", false, "لا يبدأ بترويسة MP4 أو WebM/MKV"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    const range = cleanString(res.headers.get("content-range")).match(/\/(\d+)$/);
    const size = range ? Number(range[1]) : Number(res.headers.get("content-length")) || 0;
    extra.size = size;
    if (size && size < 100000) {
      checks.push(wnCheck("size", "حجم الملف", false, size + " bytes"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    checks.push(wnCheck("container", "صيغة الملف", true, mp4 ? "MP4" : "WebM/MKV"));
    return wnFinishSource(base, "playable", checks, extra);
  } finally {
    try { await res.body?.cancel(); } catch { /* ignore */ }
  }
}

async function wnProbeHls(base, checks) {
  const fetchText = async (u, max) => {
    const p = await wnProbeFetch(u, { method: "GET", headers: { accept: "application/vnd.apple.mpegurl,*/*", "user-agent": WN_UA } });
    try {
      if (p.res.status < 200 || p.res.status >= 300) return { status: p.res.status, text: "", url: p.url, ms: p.ms, type: "" };
      const bytes = await readUpTo(p.res, max);
      return { status: p.res.status, text: new TextDecoder("utf-8").decode(bytes), url: p.url, ms: p.ms, type: cleanString(p.res.headers.get("content-type")).split(";")[0].toLowerCase() };
    } finally {
      try { await p.res.body?.cancel(); } catch { /* ignore */ }
    }
  };
  let top;
  try {
    top = await fetchText(base.url, 262144);
  } catch (error) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, { http: 0 });
  }
  const extra = { http: top.status, ms: top.ms, content_type: top.type };
  if (top.status === 404 || top.status === 410) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, "HTTP " + top.status));
    return wnFinishSource(base, "unavailable", checks, extra);
  }
  if (top.status < 200 || top.status >= 300) {
    checks.push(wnCheck("http", "قائمة التشغيل", false, "HTTP " + top.status));
    return wnFinishSource(base, "unreachable", checks, extra);
  }
  checks.push(wnCheck("http", "قائمة التشغيل", true, "HTTP " + top.status));
  if (!/^\s*#EXTM3U/.test(top.text)) {
    checks.push(wnCheck("playlist", "ملف m3u8", false, "لا يبدأ بـ #EXTM3U"));
    return wnFinishSource(base, "invalid", checks, extra);
  }
  if (/#EXT-X-(SESSION-)?KEY:[^\n]*(SAMPLE-AES|streamingkeydelivery|playready|edef8ba9|widevine)/i.test(top.text)) {
    checks.push(wnCheck("drm", "الحماية", false, "DRM — لن نتجاوزه ولا يمكن فحصه"));
    return wnFinishSource(base, "drm", checks, extra);
  }
  let media = top;
  if (/#EXT-X-STREAM-INF/.test(top.text)) {
    const lines = top.text.split(/\r?\n/);
    const idx = lines.findIndex((l) => l.startsWith("#EXT-X-STREAM-INF"));
    const uri = lines.slice(idx + 1).find((l) => l.trim() && !l.startsWith("#"));
    if (!uri) {
      checks.push(wnCheck("playlist", "القائمة الرئيسية", false, "لا توجد جودات"));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    try {
      media = await fetchText(new URL(uri.trim(), top.url).toString(), 262144);
    } catch (error) {
      checks.push(wnCheck("variant", "قائمة الجودة", false, cleanString(error && error.message).slice(0, 100)));
      return wnFinishSource(base, "unreachable", checks, extra);
    }
    if (media.status < 200 || media.status >= 300 || !/^\s*#EXTM3U/.test(media.text)) {
      checks.push(wnCheck("variant", "قائمة الجودة", false, "HTTP " + media.status));
      return wnFinishSource(base, "invalid", checks, extra);
    }
    checks.push(wnCheck("variant", "قائمة الجودة", true, "تم تحميلها"));
  }
  const segLines = media.text.split(/\r?\n/);
  const segIdx = segLines.findIndex((l) => l.startsWith("#EXTINF"));
  const segUri = segIdx >= 0 ? segLines.slice(segIdx + 1).find((l) => l.trim() && !l.startsWith("#")) : "";
  if (!segUri) {
    checks.push(wnCheck("segments", "المقاطع", false, "لا توجد مقاطع #EXTINF"));
    return wnFinishSource(base, "invalid", checks, extra);
  }
  try {
    const seg = await wnProbeFetch(new URL(segUri.trim(), media.url).toString(), { method: "GET", headers: { range: "bytes=0-1023", "user-agent": WN_UA } });
    try {
      const st = seg.res.status;
      const segType = cleanString(seg.res.headers.get("content-type")).split(";")[0].toLowerCase();
      if ((st !== 200 && st !== 206) || /text\/html|application\/json/.test(segType)) {
        checks.push(wnCheck("segments", "أول مقطع", false, "HTTP " + st + " " + segType));
        return wnFinishSource(base, "unreachable", checks, extra);
      }
    } finally {
      try { await seg.res.body?.cancel(); } catch { /* ignore */ }
    }
  } catch (error) {
    checks.push(wnCheck("segments", "أول مقطع", false, cleanString(error && error.message).slice(0, 100)));
    return wnFinishSource(base, "unreachable", checks, extra);
  }
  checks.push(wnCheck("segments", "أول مقطع", true, "تم تنزيل أول بايتات المقطع"));
  return wnFinishSource(base, "playable", checks, extra);
}

const WN_PROBE_CACHE = new Map();

/** Probes ONE url for ONE target and returns a source record (signed token only when the result is importable). */
async function wnProbeSource(env, provider, url, target, hintKind, fresh) {
  const checks = [];
  const base = { provider: provider.id, name: provider.name, kind: wnDetectKind(url, hintKind || provider.kind), url };
  try {
    wnSafeUrl(url, { allowHttp: provider.id === "custom" });
  } catch (error) {
    checks.push(wnCheck("url_safe", "سلامة الرابط", false, error.message));
    return wnFinishSource(base, "invalid", checks);
  }
  checks.push(wnCheck("url_safe", "سلامة الرابط", true, new URL(url).hostname));

  // the URL must belong to THIS season/episode (custom links carry S01E03-style hints; generated ones are rebuilt)
  if (target.kind === "episode") {
    const hint = provider.id === "custom" ? wnHintOfUrl(url) : null;
    if (hint && (hint.season !== target.season || hint.episode !== target.episode)) {
      checks.push(wnCheck("episode_match", "مطابقة الموسم/الحلقة", false, "الرابط يخص S" + wnPad2(hint.season) + "E" + wnPad2(hint.episode)));
      return wnFinishSource(base, "mismatch", checks);
    }
    checks.push(wnCheck("episode_match", "مطابقة الموسم/الحلقة", true, "S" + wnPad2(target.season) + "E" + wnPad2(target.episode)));
  }

  const cached = fresh ? null : WN_PROBE_CACHE.get(url); // "re-check" skips the 5-minute probe cache
  let source;
  if (cached && cached.exp > Date.now()) {
    source = Object.assign({}, cached.source, { checks: checks.concat(cached.source.checks.filter((c) => !["url_safe", "episode_match"].includes(c.id))), cached: true });
  } else {
    const probeChecks = [];
    if (base.kind === "hls") source = await wnProbeHls(base, probeChecks);
    else if (base.kind === "file") source = await wnProbeFile(base, probeChecks);
    else source = await wnProbeEmbed(base, probeChecks);
    source.checks = checks.concat(source.checks);
    if (WN_PROBE_CACHE.size > 300) WN_PROBE_CACHE.delete(WN_PROBE_CACHE.keys().next().value);
    WN_PROBE_CACHE.set(url, { exp: Date.now() + 5 * 60000, source: clone(source) });
  }
  if (source.importable) {
    source.token = await wnSignToken(env, {
      u: url, v: source.verdict, l: source.level, k: target.kind, t: String(target.tmdb), s: target.season || 0, e: target.episode || 0,
      p: provider.id, n: source.kind, x: Date.now() + WN_TOKEN_TTL_MS,
    });
  }
  return source;
}

function wnProviderUrl(provider, target) {
  const tpl = target.kind === "movie" ? provider.movie : provider.tv;
  if (!tpl) return "";
  return tpl
    .replace("{tmdb}", String(target.tmdb))
    .replace("{season}", String(target.season || ""))
    .replace("{episode}", String(target.episode || ""));
}

/** Finds + validates sources for one movie / one episode across every enabled provider; ranks them. */
async function wnResolveTarget(env, cfg, target, opts) {
  const o = opts || {};
  const out = { kind: target.kind, season: target.season || 0, episode: target.episode || 0, tmdb_id: String(target.tmdb), sources: [] };
  if (target.released === false) {
    const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
    out.sources.push(wnFinishSource(base, "not_released", [wnCheck("released", "تاريخ الإصدار", false, "لم يصدر بعد")]));
  } else {
    const providers = (cfg.video_providers || []).filter((p) => p && p.enabled !== false);
    const jobs = [];
    for (const p of providers) {
      const url = wnProviderUrl(p, target);
      if (url) jobs.push(wnProbeSource(env, p, url, target, p.kind, o.fresh));
    }
    if (o.customUrl) {
      jobs.push(wnProbeSource(env, { id: "custom", name: "رابط يدوي", kind: "embed" }, cleanString(o.customUrl), target, "", o.fresh));
    }
    out.sources = await Promise.all(jobs);
    if (!out.sources.length) {
      const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
      out.sources.push(wnFinishSource(base, "unavailable", [wnCheck("providers", "المزوّدون", false, "لا يوجد مزوّد مُفعّل لهذا النوع")]));
    }
  }
  const order = new Map((cfg.video_providers || []).map((p, i) => [p.id, i]));
  out.sources.sort((a, b) => {
    const ra = WN_VERDICTS[a.verdict].rank;
    const rb = WN_VERDICTS[b.verdict].rank;
    if (ra !== rb) return rb - ra;
    const pa = order.has(a.provider) ? order.get(a.provider) : 99;
    const pb = order.has(b.provider) ? order.get(b.provider) : 99;
    if (pa !== pb) return pa - pb;
    return (a.ms || 0) - (b.ms || 0);
  });
  const best = out.sources.find((s) => s.importable) || null;
  out.best = best ? best.provider : "";
  out.fallbacks = out.sources.filter((s) => s.importable && s !== best).map((s) => ({ provider: s.provider, url: s.url, verdict: s.verdict }));
  out.status = best ? best.verdict : out.sources[0] ? out.sources[0].verdict : "unavailable";
  return out;
}


// ----------------------------------------------------------------------------- subtitles (search, download, validate)
// Arabic goes through the Worker's existing, field-tested pipeline (SubDL -> SubSource -> OpenSubtitles). Other
// languages use a small generic search (SubDL + OpenSubtitles). A subtitle exists for the import only after it was
// downloaded, decoded, cleaned and validated; it is then parked in the admin bucket behind an unguessable token.

function wnSubLang(value, allowAuto) {
  const l = lc(value);
  if (allowAuto && (l === "auto" || l === "")) return "auto";
  if (!/^[a-z]{2}$/.test(l)) throw new AdminError("invalid_language", "رمز اللغة غير صالح (حرفان مثل ar أو en).", 400);
  return l;
}

function wnSubTarget(meta, season, episode) {
  return {
    type: meta.kind === "series" ? "tv" : "movie",
    title: meta.title,
    original: meta.original_title,
    year: meta.year,
    imdb_id: meta.imdb_id,
    tmdb_id: String(meta.tmdb_id),
    season: numOf(season),
    episode: numOf(episode),
    release: "",
    item: null,
    header: null,
    slug: "",
  };
}

function wnSubCandidate(pick) {
  const source = lc(pick && pick.source);
  const id = cleanString(pick && pick.id);
  if (!["subdl", "subsource", "opensubtitles"].includes(source)) throw new AdminError("invalid_pick", "مصدر الترجمة غير معروف.", 400);
  if (!/^[A-Za-z0-9_./:%-]{1,220}$/.test(id) || id.includes("..")) throw new AdminError("invalid_pick", "معرّف الترجمة غير صالح.", 400);
  return {
    source,
    id,
    name: cleanString(pick.name).slice(0, 160),
    release: cleanString(pick.release).slice(0, 160),
    season: numOf(pick.season),
    episode: numOf(pick.episode),
    full_season: truthy(pick.full_season),
    dl:
      source === "opensubtitles" ? "opensubtitles:" + id
      : source === "subdl" ? "https://dl.subdl.com" + (id.startsWith("/") ? "" : "/") + id
      : "https://api.subsource.net/api/v1/subtitles/" + encodeURIComponent(id) + "/download",
  };
}

function wnLangMatches(value, lang) {
  const s = lc(value);
  return s === lang || s.startsWith(lang + "-") || s.startsWith(lang + "_") || (lang === "en" && s.startsWith("english")) || (lang === "fr" && s.startsWith("french")) || (lang === "tr" && s.startsWith("turk"));
}

/** Generic (non-Arabic) search: SubDL + OpenSubtitles. */
async function wnSubsSearchLang(env, t, lang) {
  const keys = subsKeys(env);
  if (!keys.subdl && !keys.opensubtitles) {
    throw new AdminError("subs_no_keys", "لم يُضبط أي مصدر ترجمة. أضف SUBDL_API_KEY أو OPENSUBTITLES_API_KEY في Cloudflare ← Settings ← Variables and Secrets.", 400);
  }
  const items = [];
  const status = {};
  if (keys.subdl) {
    try {
      const u = new URL("https://api.subdl.com/api/v1/subtitles");
      const q = { api_key: keys.subdl, languages: lang.toUpperCase(), subs_per_page: "30", releases: "1", type: t.type };
      if (t.imdb_id) q.imdb_id = t.imdb_id; else if (t.tmdb_id) q.tmdb_id = t.tmdb_id; else q.film_name = t.original || t.title;
      if (t.type === "tv" && t.season > 0) {
        q.season_number = String(t.season);
        if (t.episode) q.episode_number = String(t.episode); else q.full_season = "1";
      }
      for (const [k, v] of Object.entries(q)) u.searchParams.set(k, v);
      const res = await subsFetch(u.toString(), { headers: { accept: "application/json", "user-agent": SUBS_UA } });
      const data = await res.json().catch(() => null);
      if (!res.ok || !data || data.status === false) status.subdl = "error: " + ((data && (data.error || data.message)) || "SubDL " + res.status);
      else {
        for (const s of Array.isArray(data.subtitles) ? data.subtitles : []) {
          if (!s.url || !wnLangMatches(s.language || s.lang, lang)) continue;
          items.push({
            source: "subdl", id: cleanString(s.url), name: cleanString(s.name || s.release_name), release: cleanString(s.release_name || s.name),
            author: cleanString(s.author), downloads: 0, rating: 0, hi: Boolean(s.hi), season: numOf(s.season), episode: numOf(s.episode),
            full_season: Boolean(s.full_season) || (t.type === "tv" && !numOf(s.episode)),
            dl: "https://dl.subdl.com" + (String(s.url).startsWith("/") ? "" : "/") + s.url,
          });
        }
        status.subdl = items.length + " " + lang;
      }
    } catch (e) {
      status.subdl = "error: تعذر الاتصال بـ SubDL";
    }
  } else status.subdl = "no_key";
  if (keys.opensubtitles && !items.length) {
    const r = await (async () => {
      try {
        const u = new URL("https://api.opensubtitles.com/api/v1/subtitles");
        u.searchParams.set("languages", lang);
        u.searchParams.set("order_by", "download_count");
        const num = (v) => cleanString(v).replace(/^tt/i, "");
        if (t.type === "tv") {
          if (t.tmdb_id) u.searchParams.set("parent_tmdb_id", t.tmdb_id); else if (t.imdb_id) u.searchParams.set("parent_imdb_id", num(t.imdb_id)); else u.searchParams.set("query", t.original || t.title);
          if (t.season > 0) u.searchParams.set("season_number", String(t.season));
          if (t.episode > 0) u.searchParams.set("episode_number", String(t.episode));
          u.searchParams.set("type", "episode");
        } else {
          if (t.tmdb_id) u.searchParams.set("tmdb_id", t.tmdb_id); else if (t.imdb_id) u.searchParams.set("imdb_id", num(t.imdb_id)); else u.searchParams.set("query", t.original || t.title);
          u.searchParams.set("type", "movie");
        }
        const res = await subsFetch(u.toString(), { headers: { accept: "application/json", "api-key": keys.opensubtitles, "user-agent": SUBS_UA } });
        const data = await res.json().catch(() => null);
        if (!res.ok || !data) return { error: "OpenSubtitles " + res.status, rows: [] };
        const rows = [];
        for (const row of Array.isArray(data.data) ? data.data : []) {
          const a = row.attributes || {};
          const file = Array.isArray(a.files) && a.files[0];
          if (!file || !file.file_id || (a.language && !wnLangMatches(a.language, lang))) continue;
          const fd = a.feature_details || {};
          rows.push({
            source: "opensubtitles", id: String(file.file_id), name: cleanString(file.file_name || a.release), release: cleanString(a.release || file.file_name),
            author: cleanString(a.uploader && a.uploader.name), downloads: numOf(a.download_count), rating: Number(a.ratings) || 0, hi: Boolean(a.hearing_impaired),
            season: numOf(fd.season_number), episode: numOf(fd.episode_number), full_season: false, dl: "opensubtitles:" + file.file_id, trusted: Boolean(a.from_trusted),
          });
        }
        return { rows };
      } catch {
        return { error: "تعذر الاتصال بـ OpenSubtitles", rows: [] };
      }
    })();
    status.opensubtitles = r.error ? "error: " + r.error : r.rows.length + " " + lang;
    items.push(...r.rows);
  } else status.opensubtitles = keys.opensubtitles ? "not_needed" : "no_key";
  for (const c of items) c.score = subsScore(c, t);
  items.sort((a, b) => b.score - a.score);
  return { items, status };
}

async function wnSubsSearch(env, t, lang) {
  const found = lang === "ar" ? await subsSearchAll(env, t, { all: false }) : await wnSubsSearchLang(env, t, lang);
  return { items: found.items.map((c) => Object.assign(subsPublic(c), { language: lang })), status: found.status };
}

function wnLangShare(text, lang) {
  const letters = (text.match(/\p{L}/gu) || []).length;
  if (!letters) return 0;
  if (lang === "ar") return (text.match(subs_ARABIC) || []).length / letters;
  if (lang === "en" || lang === "fr" || lang === "es" || lang === "de" || lang === "it" || lang === "tr") return (text.match(/[A-Za-zÀ-ÿĞğİıŞş]/g) || []).length / letters;
  return -1; // unknown script: skip the check
}

// ----------------------------------------------------------------------------- SRT parsing + validation

function wnParseSrt(srt) {
  const cues = [];
  const toMs = (h, m, s, f) => Number(h) * 3600000 + Number(m) * 60000 + Number(s) * 1000 + Number(String(f).padEnd(3, "0").slice(0, 3));
  for (const block of String(srt).replace(/\r\n?/g, "\n").split(/\n{2,}/)) {
    const lines = block.split("\n");
    const ti = lines.findIndex((l) => l.includes("-->"));
    if (ti < 0) continue;
    const m = lines[ti].match(/(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})/);
    if (!m) continue;
    cues.push({ start: toMs(m[1], m[2], m[3], m[4]), end: toMs(m[5], m[6], m[7], m[8]), text: lines.slice(ti + 1).join("\n").trim() });
  }
  return cues;
}

/**
 * Structural validation of a CLEANED srt. issues[] block the import; warnings[] are shown but do not.
 * opts: { lang, runtimeMin, kind }
 */
function wnValidateSrt(srt, opts) {
  const o = opts || {};
  const issues = [];
  const warnings = [];
  const text = cleanString(srt);
  if (!text) {
    return { ok: false, cues: 0, issues: [{ code: "empty", message: "الملف فارغ." }], warnings };
  }
  if (text.length > WN_MAX_SRT_BYTES) issues.push({ code: "too_large", message: "الملف أكبر من الحد المسموح (1.5MB)." });
  const cues = wnParseSrt(text);
  if (cues.length < 5) issues.push({ code: "too_few_cues", message: "عدد الأسطر (" + cues.length + ") قليل جداً — الملف تالف أو غير مكتمل." });
  else if (o.kind !== "episode" && cues.length < 100) warnings.push({ code: "few_cues", message: "عدد أسطر الترجمة قليل (" + cues.length + ")." });
  const badTiming = cues.filter((c) => c.end <= c.start).length;
  if (cues.length && badTiming / cues.length > 0.02) issues.push({ code: "bad_timing", message: badTiming + " سطراً توقيته غير منطقي." });
  else if (badTiming) warnings.push({ code: "bad_timing", message: badTiming + " سطر/أسطر بتوقيت غير منطقي." });
  let outOfOrder = 0;
  for (let i = 1; i < cues.length; i++) if (cues[i].start < cues[i - 1].start - 1000) outOfOrder++;
  if (cues.length && outOfOrder / cues.length > 0.03) issues.push({ code: "out_of_order", message: "ترتيب التوقيت مضطرب (" + outOfOrder + " موضعاً)." });
  const long = cues.filter((c) => c.end - c.start > 30000).length;
  if (long) warnings.push({ code: "long_cues", message: long + " سطراً مدته أطول من 30 ثانية." });
  const last = cues.reduce((m, c) => Math.max(m, c.end), 0);
  const first = cues.length ? cues.reduce((m, c) => Math.min(m, c.start), Infinity) : 0;
  const rt = Number(o.runtimeMin) > 0 ? Number(o.runtimeMin) * 60000 : 0;
  if (rt && cues.length >= 5) {
    if (last > rt * 1.3 + 600000) warnings.push({ code: "longer_than_runtime", message: "الترجمة أطول بكثير من مدة العمل — قد تكون لنسخة أخرى." });
    else if (last < rt * 0.4) warnings.push({ code: "shorter_than_runtime", message: "الترجمة أقصر بكثير من مدة العمل — قد تكون ناقصة." });
  }
  const share = wnLangShare(text, o.lang || "");
  if (share >= 0 && o.lang && cues.length >= 5) {
    if (share < (o.lang === "ar" ? 0.3 : 0.6)) issues.push({ code: "wrong_language", message: "لغة الملف لا تطابق «" + o.lang + "»." });
  }
  if (/[ØÙÃ][\u0080-¿]/.test(text) && wnLangShare(text, "ar") < 0.05 && o.lang === "ar") {
    issues.push({ code: "bad_encoding", message: "ترميز الملف تالف (حروف مشوّهة)." });
  }
  return {
    ok: issues.length === 0,
    cues: cues.length,
    first_ms: Number.isFinite(first) ? first : 0,
    last_ms: last,
    issues,
    warnings,
    language_share: share >= 0 ? Math.round(share * 100) / 100 : null,
  };
}

// ----------------------------------------------------------------------------- download (generic languages)

async function wnSubsDownload(env, c, t, lang) {
  if (lang === "ar") return subsDownload(env, c, t); // the Worker's own Arabic path (also checks the script share)
  let bytes;
  if (c.dl.startsWith("opensubtitles:")) {
    bytes = await osDownloadBytes(env, c.id);   // 18.2: session + base_url + quota handling (see subhub_providers.js)
  } else {
    const headers = { "user-agent": SUBS_UA };
    if (c.source === "subsource") headers["x-api-key"] = subsKeys(env).subsource;
    const f = await subsFetch(c.dl, { headers }, 20000);
    if (!f.ok) throw new AdminError("subs_download_failed", c.source + " " + f.status, 502);
    bytes = new Uint8Array(await f.arrayBuffer());
  }
  if (bytes.length > SUBS_MAX_ZIP) throw new AdminError("subs_too_big", "الملف كبير جداً.", 502);
  let files;
  if (bytes[0] === 0x50 && bytes[1] === 0x4b) files = await subsUnzip(bytes, /\.(srt|vtt)$/i);
  else if (bytes[0] === 0x1f && bytes[1] === 0x8b) {
    const ds = new DecompressionStream("gzip");
    files = [{ name: "subtitle.srt", data: new Uint8Array(await new Response(new Blob([bytes]).stream().pipeThrough(ds)).arrayBuffer()) }];
  } else files = [{ name: "subtitle.srt", data: bytes }];
  if (!files.length) throw new AdminError("subs_no_srt", "لا يوجد ملف SRT داخل الأرشيف.", 502);
  let chosen = files[0];
  if (files.length > 1 || c.full_season) {
    let best = null;
    for (const f of files) {
      const e = subsEpisodeOf(f.name);
      if (e && t.episode && e.episode === t.episode && (!e.season || !t.season || e.season === t.season)) { best = f; break; }
    }
    if (!best && files.length > 1) throw new AdminError("subs_episode_not_in_pack", "لم أجد الحلقة المطلوبة داخل حزمة الموسم.", 502);
    if (best) chosen = best;
  }
  const dec = subs_decodeSubtitleBytes(chosen.data);
  const parsed = subsToSrt(dec.text);
  if (parsed.cues < 5) throw new AdminError("subs_empty", "الملف لا يحتوي ترجمة صالحة.", 502);
  return { srt: parsed.srt, cues: parsed.cues, encoding: dec.encoding, file: chosen.name };
}

// ----------------------------------------------------------------------------- parking a validated subtitle

async function wnParkSubtitle(env, srt, info) {
  const token = wnRandomHex(16);
  const bucket = wnBucket(env);
  await bucket.put(adminKey(env, WN_SUBS_PREFIX + token + ".srt"), srt, { httpMetadata: { contentType: "application/x-subrip; charset=utf-8" } });
  await bucket.put(adminKey(env, WN_SUBS_PREFIX + token + ".json"), JSON.stringify(Object.assign({ at: Date.now() }, info)), {
    httpMetadata: { contentType: "application/json; charset=utf-8" },
  });
  return token;
}

async function wnReadParkedSubtitle(env, token) {
  const t = cleanString(token);
  if (!/^[0-9a-f]{32}$/.test(t)) return null;
  const bucket = wnBucket(env);
  const [srtObj, infoObj] = await Promise.all([bucket.get(adminKey(env, WN_SUBS_PREFIX + t + ".srt")), bucket.get(adminKey(env, WN_SUBS_PREFIX + t + ".json"))]);
  if (!srtObj || !infoObj) return null;
  let info = null;
  try { info = JSON.parse(await infoObj.text()); } catch { info = null; }
  if (!info) return null;
  return { token: t, srt: await srtObj.text(), info };
}

async function wnDropParkedSubtitle(env, token) {
  const t = cleanString(token);
  if (!/^[0-9a-f]{32}$/.test(t)) return;
  try {
    await wnBucket(env).delete([adminKey(env, WN_SUBS_PREFIX + t + ".srt"), adminKey(env, WN_SUBS_PREFIX + t + ".json")]);
  } catch { /* best effort */ }
}

async function wnCleanParkedSubtitles(env) {
  try {
    const all = await listAll(wnBucket(env), adminKey(env, WN_SUBS_PREFIX));
    const doomed = all.filter((o) => o.uploaded && Date.now() - new Date(o.uploaded).getTime() > WN_SUB_TMP_TTL_MS).map((o) => o.key);
    for (let i = 0; i < doomed.length; i += 100) await wnBucket(env).delete(doomed.slice(i, i + 100));
    return doomed.length;
  } catch {
    return 0;
  }
}

function wnSubPreview(srt, n) {
  return srt.split("\n\n").slice(0, n || 6).join("\n\n");
}

// ----------------------------------------------------------------------------- routes

async function wnTargetFromInput(env, input) {
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const meta = await wnFetchMeta(env, kind, tmdb);
  const season = numOf(input.season);
  const episode = numOf(input.episode);
  if (kind === "series" && !season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب لترجمة حلقات المسلسل.", 400);
  return { meta, t: wnSubTarget(meta, season, episode), kind, tmdb, season, episode };
}

async function handleWnSubtitles(request, env) {
  wnRate("subs-search", 60);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, true);
  const { meta, t } = await wnTargetFromInput(env, input);
  const order = lang === "auto" ? ["ar", "en"] : [lang];
  let used = order[0];
  let found = { items: [], status: {} };
  for (const l of order) {
    used = l;
    found = await wnSubsSearch(env, t, l);
    if (found.items.length) break;
  }
  return okResponse(
    {
      target: { type: meta.kind, tmdb_id: meta.tmdb_id, title: meta.title, season: t.season, episode: t.episode, runtime_minutes: meta.runtime_minutes },
      lang: used,
      results: found.items.slice(0, 30),
      best: found.items[0] || null,
      status: found.status,
    },
    found.items.length ? "وُجدت " + found.items.length + " ترجمة (" + used + ")." : "لم أجد ترجمة لهذا العنوان في المصادر المتاحة."
  );
}

async function handleWnSubtitlesFetch(request, env) {
  wnRate("subs-fetch", 40);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, false);
  const { meta, t, kind, tmdb, season, episode } = await wnTargetFromInput(env, input);
  const cand = wnSubCandidate(input.pick || {});
  const got = await wnSubsDownload(env, cand, t, lang);
  const validation = wnValidateSrt(got.srt, { lang, runtimeMin: meta.runtime_minutes, kind: kind === "series" ? "episode" : "movie" });
  // matching: a candidate that names another episode is rejected even if the file itself is fine
  const mismatch = kind === "series" && cand.episode && episode && cand.episode !== episode && !cand.full_season;
  if (mismatch) validation.issues.push({ code: "episode_mismatch", message: "الترجمة للحلقة " + cand.episode + " وليست " + episode + "." });
  validation.ok = validation.issues.length === 0;
  const out = {
    lang, source: cand.source, release: cand.release, file: got.file, encoding: got.encoding, bytes: got.srt.length,
    cues: validation.cues, validation, preview: wnSubPreview(got.srt), token: "",
    season, episode, format: "srt", provider: cand.source,
  };
  if (validation.ok) {
    out.token = await wnParkSubtitle(env, got.srt, { lang, source: cand.source, release: cand.release, tmdb: tmdb, kind, season, episode, cues: validation.cues, encoding: got.encoding });
  }
  return okResponse(out, validation.ok ? "تم تنزيل الترجمة والتحقق منها." : "تم تنزيل الملف لكنه فشل في التحقق.");
}

async function handleWnSubtitlesValidate(request, env) {
  wnRate("subs-validate", 60);
  const input = await readRequestData(request);
  const lang = wnSubLang(input.lang, false);
  const { meta, kind, tmdb, season, episode } = await wnTargetFromInput(env, input);
  let text = "";
  if (cleanString(input.base64)) {
    let bin;
    try { bin = atob(cleanString(input.base64)); } catch { throw new AdminError("invalid_file", "الملف المرسل غير صالح (base64).", 400); }
    if (bin.length > WN_MAX_SRT_BYTES * 2) throw new AdminError("too_large", "الملف أكبر من الحد المسموح.", 413);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    text = subs_decodeSubtitleBytes(bytes).text;
  } else {
    text = String(input.text || "");
    if (text.length > WN_MAX_SRT_BYTES * 2) throw new AdminError("too_large", "الملف أكبر من الحد المسموح.", 413);
  }
  const parsed = subsToSrt(text);
  const validation = wnValidateSrt(parsed.srt, { lang, runtimeMin: meta.runtime_minutes, kind: kind === "series" ? "episode" : "movie" });
  const out = { lang, source: "manual", release: cleanString(input.name).slice(0, 120), cues: validation.cues, validation, preview: wnSubPreview(parsed.srt), token: "", season, episode, format: "srt", provider: "manual" };
  if (validation.ok) out.token = await wnParkSubtitle(env, parsed.srt, { lang, source: "manual", release: out.release, tmdb, kind, season, episode, cues: validation.cues });
  return okResponse(out, validation.ok ? "الملف صالح." : "الملف غير صالح.");
}


// ----------------------------------------------------------------------------- discovery feeds (TMDB) + cached-index views

async function wnMapLimit(items, limit, fn) {
  const out = new Array(items.length);
  let next = 0;
  const workers = [];
  for (let w = 0; w < Math.min(limit, items.length); w++) {
    workers.push((async () => {
      while (next < items.length) {
        const i = next++;
        out[i] = await fn(items[i], i);
      }
    })());
  }
  await Promise.all(workers);
  return out;
}

function wnFilters(input) {
  const f = {};
  const g = cleanString(input.genre);
  if (g) {
    if (!/^\d{1,6}([|,]\d{1,6})*$/.test(g)) throw new AdminError("invalid_filter", "فلتر التصنيف غير صالح.", 400);
    f.genre = g.replace(/,/g, "|");
  }
  const lang = cleanString(input.language);
  if (lang) {
    if (!/^[a-z]{2,3}$/.test(lang)) throw new AdminError("invalid_filter", "فلتر اللغة غير صالح.", 400);
    f.language = lang;
  }
  const country = cleanString(input.country).toUpperCase();
  if (country) {
    if (!/^[A-Z]{2}$/.test(country)) throw new AdminError("invalid_filter", "فلتر الدولة غير صالح.", 400);
    f.country = country;
  }
  const year = cleanString(input.year);
  if (year) {
    if (!/^(19|20)\d{2}$/.test(year)) throw new AdminError("invalid_filter", "فلتر السنة غير صالح.", 400);
    f.year = year;
  }
  const rating = cleanString(input.min_rating);
  if (rating) {
    const r = Number(rating);
    if (!Number.isFinite(r) || r < 0 || r > 10) throw new AdminError("invalid_filter", "فلتر التقييم غير صالح.", 400);
    f.min_rating = r;
  }
  for (const k of ["date_from", "date_to"]) {
    const v = cleanString(input[k]);
    if (v) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(v) || !Number.isFinite(Date.parse(v))) throw new AdminError("invalid_filter", "التاريخ غير صالح (YYYY-MM-DD).", 400);
      f[k] = v;
    }
  }
  const sort = lc(input.sort);
  if (sort) {
    if (!WN_SORTS[sort]) throw new AdminError("invalid_filter", "ترتيب غير معروف.", 400);
    f.sort = sort;
  }
  f.page = Math.min(500, Math.max(1, numOf(input.page) || 1));
  f.fresh = truthy(input.refresh); // the admin pressed Refresh: skip the short-lived cache
  const type = lc(input.type);
  f.type = type === "movie" ? "movie" : type === "series" || type === "tv" ? "series" : "any";
  return f;
}

function wnDiscoverParams(kind, feed, f, cfg) {
  const tv = kind === "series";
  const p = { language: cfg.language || "ar-SA", include_adult: "false", page: f.page };
  const dateKey = tv ? "first_air_date" : "primary_release_date";
  p.sort_by = ({ popularity: "popularity.desc", rating: "vote_average.desc", release: tv ? "first_air_date.desc" : "primary_release_date.desc" })[f.sort || "popularity"];
  if (feed === "new_releases") {
    p[dateKey + ".gte"] = f.date_from || wnToday(-45);
    p[dateKey + ".lte"] = f.date_to || wnToday();
  } else if (feed === "upcoming") {
    p[dateKey + ".gte"] = f.date_from || wnToday(1);
    p[dateKey + ".lte"] = f.date_to || wnToday(180);
  } else if (feed === "recent_episodes" || feed === "new_seasons") {
    p["air_date.gte"] = f.date_from || wnToday(feed === "new_seasons" ? -30 : -14);
    p["air_date.lte"] = f.date_to || wnToday();
  } else if (feed === "popular") {
    p["vote_count.gte"] = 50;
    if (f.date_from) p[dateKey + ".gte"] = f.date_from;
    p[dateKey + ".lte"] = f.date_to || wnToday();
  }
  if (!tv && cfg.region) p.region = cfg.region;
  if (f.genre) p.with_genres = f.genre;
  if (f.language) p.with_original_language = f.language;
  if (f.country) p.with_origin_country = f.country;
  if (f.year) {
    // an explicit year replaces the rolling window of the feed
    delete p[dateKey + ".gte"];
    delete p[dateKey + ".lte"];
    if (tv) p.first_air_date_year = f.year; else p.primary_release_year = f.year;
  }
  if (f.min_rating) {
    p["vote_average.gte"] = f.min_rating;
    p["vote_count.gte"] = Math.max(p["vote_count.gte"] || 0, 10);
  }
  return p;
}

function wnLocalFilter(row, kind, f) {
  const date = cleanString(kind === "series" ? row.first_air_date : row.release_date);
  const ids = Array.isArray(row.genre_ids) ? row.genre_ids.map(String) : [];
  if (f.genre && !f.genre.split("|").some((g) => ids.includes(g))) return false;
  if (f.language && cleanString(row.original_language) !== f.language) return false;
  if (f.country && kind === "series" && !(Array.isArray(row.origin_country) ? row.origin_country : []).includes(f.country)) return false;
  if (f.year && yearOfText(date) !== f.year) return false;
  if (f.min_rating && Number(row.vote_average || 0) < f.min_rating) return false;
  if (f.date_from && date < f.date_from) return false;
  if (f.date_to && date > f.date_to) return false;
  return true;
}

function wnVideoBrief(entry) {
  const v = entry && entry.enrich && entry.enrich.video;
  if (!v || !v.verdict) return { state: "unknown" };
  const state = v.verdict === "playable" || v.verdict === "embed_reachable" ? "ok"
    : v.verdict === "unverified" ? "unverified"
    : v.verdict === "not_released" ? "not_released" : "missing";
  return { state, verdict: v.verdict, provider: v.provider || "", checked_at: v.checked_at || "" };
}

function wnSubBrief(entry) {
  const s = entry && entry.enrich && entry.enrich.subtitle;
  if (!s || !s.checked_at) return { state: "unknown" };
  if (s.status === "unconfigured") return { state: "unconfigured" };
  if (s.status === "error") return { state: "unknown", error: s.error || "" };
  if (s.ar > 0) return { state: "ar", ar: s.ar, en: s.en || 0 };
  if (s.en > 0) return { state: "en", ar: 0, en: s.en };
  return { state: "none" };
}

function wnCard(row, kind, genreMap, lib, idx, extra) {
  const tv = kind === "series";
  const id = Number(row.id) || 0;
  const date = cleanString(tv ? row.first_air_date : row.release_date);
  const key = kind + ":" + id;
  const entry = idx && idx.items ? idx.items[key] : null;
  const card = {
    key,
    type: kind,
    tmdb_id: id,
    title: cleanString(tv ? row.name : row.title) || cleanString(tv ? row.original_name : row.original_title),
    original_title: cleanString(tv ? row.original_name : row.original_title),
    year: yearOfText(date),
    release_date: date,
    released: Boolean(date) && date <= wnToday(),
    poster: tmdbImage(row.poster_path, "w342"),
    backdrop: tmdbImage(row.backdrop_path, "w780"),
    overview: cleanString(row.overview).slice(0, 400),
    rating: Number(Number(row.vote_average || 0).toFixed(1)),
    vote_count: Number(row.vote_count) || 0,
    popularity: Math.round(Number(row.popularity) || 0),
    genre_ids: Array.isArray(row.genre_ids) ? row.genre_ids : [],
    genres: (Array.isArray(row.genre_ids) ? row.genre_ids : []).map((g) => genreMap.get(Number(g))).filter(Boolean),
    language: cleanString(row.original_language),
    countries: Array.isArray(row.origin_country) ? row.origin_country : [],
    imported: lib.has(key),
    existing: lib.get(key) || null,
    video: wnVideoBrief(entry),
    subtitle: wnSubBrief(entry),
    first_seen_at: entry ? entry.first_seen_at : "",
  };
  if (tv) {
    const e = entry && entry.enrich ? entry.enrich : {};
    card.seasons = (extra && extra.seasons) || e.seasons || 0;
    card.episodes = (extra && extra.episodes) || e.episodes || 0;
    card.latest_episode = (extra && extra.latest_episode) || e.latest_episode || null;
    card.new_season = Boolean(extra && extra.new_season);
    card.episodes_pending = e.episodes_pending || 0;
  }
  return card;
}

async function wnTvLight(env, id) {
  return wnTmdb(env, "/tv/" + id, { language: "ar-SA" }, { ttl: 3600, nullOn404: true });
}

async function wnLiveRows(env, cfg, kind, feed, f) {
  const tmdbKind = kind === "series" ? "tv" : "movie";
  if (feed === "trending") {
    const data = await wnTmdb(env, "/trending/" + tmdbKind + "/week", { language: cfg.language || "ar-SA", page: f.page }, { ttl: f.fresh ? 0 : 600 });
    let rows = (Array.isArray(data.results) ? data.results : []).filter((r) => wnLocalFilter(r, kind, f));
    if (f.sort === "rating") rows = rows.slice().sort((a, b) => Number(b.vote_average) - Number(a.vote_average));
    if (f.sort === "release") rows = rows.slice().sort((a, b) => cleanString(b.release_date || b.first_air_date).localeCompare(cleanString(a.release_date || a.first_air_date)));
    return { rows: rows.map((row) => ({ row, kind })), total_pages: Number(data.total_pages) || 1, total_results: Number(data.total_results) || rows.length };
  }
  if (feed === "trending_day" || feed === "top_rated" || feed === "on_the_air") {
    // 18.2: plain TMDB lists (no /discover parameters). on_the_air exists for TV only.
    if (feed === "on_the_air" && tmdbKind !== "tv") return { rows: [], total_pages: 1, total_results: 0 };
    const listPath = feed === "trending_day" ? "/trending/" + tmdbKind + "/day" : "/" + tmdbKind + "/" + feed;
    const ld = await wnTmdb(env, listPath, { language: cfg.language || "ar-SA", page: f.page }, { ttl: f.fresh ? 0 : 600 });
    let lr = (Array.isArray(ld.results) ? ld.results : []).filter((r) => wnLocalFilter(r, kind, f));
    if (f.sort === "rating") lr = lr.slice().sort((a, b) => Number(b.vote_average) - Number(a.vote_average));
    if (f.sort === "release") lr = lr.slice().sort((a, b) => cleanString(b.release_date || b.first_air_date).localeCompare(cleanString(a.release_date || a.first_air_date)));
    return { rows: lr.map((row) => ({ row, kind })), total_pages: Math.min(500, Number(ld.total_pages) || 1), total_results: Number(ld.total_results) || lr.length };
  }
  const data = await wnTmdb(env, "/discover/" + tmdbKind, wnDiscoverParams(kind, feed, f, cfg), { ttl: f.fresh ? 0 : 600 });
  let rows = (Array.isArray(data.results) ? data.results : []).map((row) => ({ row, kind }));
  const totalPages = Math.min(500, Number(data.total_pages) || 1);
  if (feed === "new_seasons") {
    // a "new season" needs details: keep the shows whose latest aired episode is episode 1 of a season (<= 45 days old)
    const picked = rows.slice(0, 12);
    const details = await wnMapLimit(picked, 4, async (r) => {
      try { return await wnTvLight(env, r.row.id); } catch { return null; }
    });
    const out = [];
    picked.forEach((r, i) => {
      const d = details[i];
      const last = d && d.last_episode_to_air;
      if (!last) return;
      const age = last.air_date ? (Date.now() - Date.parse(last.air_date)) / 86400000 : 9999;
      if (Number(last.episode_number) === 1 && age <= 45) {
        r.extra = {
          seasons: Number(d.number_of_seasons) || 0,
          episodes: Number(d.number_of_episodes) || 0,
          latest_episode: { season: Number(last.season_number) || 0, episode: 1, air_date: cleanString(last.air_date), name: cleanString(last.name) },
          new_season: true,
        };
        out.push(r);
      }
    });
    rows = out;
  }
  return { rows, total_pages: totalPages, total_results: Number(data.total_results) || rows.length };
}

function wnViewEntries(view, idx, lib, f) {
  const items = Object.values((idx && idx.items) || {});
  const typeOk = (e) => f.type === "any" || e.kind === f.type;
  const cutoff = Date.now() - 14 * 86400000;
  const state = (e) => wnVideoBrief(e).state;
  let rows = [];
  if (view === "imported") {
    // everything that is already in the library under a TMDB id (not just what this feature imported)
    rows = Array.from(lib.entries()).map(([key, brief]) => ({ kind: brief.type === "series" ? "series" : "movie", tmdb_id: Number(key.split(":")[1]), brief, key }));
    return rows.filter(typeOk);
  }
  for (const e of items) {
    if (!typeOk(e)) continue;
    const key = e.kind + ":" + e.tmdb_id;
    const imported = lib.has(key);
    if (view === "recently_added" && !imported && Date.parse(e.first_seen_at) >= cutoff) rows.push(e);
    else if (view === "ready" && !imported && state(e) === "ok" && e.released !== false) rows.push(e);
    else if (view === "unverified" && !imported && state(e) === "unverified") rows.push(e);
    else if (view === "missing_video" && !imported && state(e) === "missing") rows.push(e);
    else if (view === "missing_subtitle" && !imported && wnSubBrief(e).state === "none") rows.push(e);
  }
  if (view === "recently_added") rows.sort((a, b) => Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at));
  else rows.sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
  return rows;
}

function wnCardFromEntry(e, lib) {
  const key = e.kind + ":" + e.tmdb_id;
  return {
    key, type: e.kind, tmdb_id: e.tmdb_id, title: e.title, original_title: e.original_title, year: e.year, release_date: e.release_date,
    released: e.released !== false, poster: e.poster, backdrop: e.backdrop || "", overview: e.overview || "", rating: e.rating || 0, vote_count: e.vote_count || 0,
    popularity: e.popularity || 0, genres: e.genres || [], genre_ids: [], language: e.language || "", countries: e.countries || [],
    imported: lib.has(key), existing: lib.get(key) || null, video: wnVideoBrief(e), subtitle: wnSubBrief(e), first_seen_at: e.first_seen_at,
    seasons: e.enrich && e.enrich.seasons || 0, episodes: e.enrich && e.enrich.episodes || 0, latest_episode: e.enrich && e.enrich.latest_episode || null,
    new_season: Boolean(e.enrich && e.enrich.new_season), episodes_pending: e.enrich && e.enrich.episodes_pending || 0,
  };
}

async function handleWnFeed(request, env) {
  wnRate("feed", 120);
  const input = await readRequestData(request);
  const feed = cleanString(input.feed) || "all:new_releases";
  const f = wnFilters(input);
  const list = await readMoviesList(env);
  const lib = wnLibraryIndex(list);
  const idx = await wnRead(env, WN_INDEX_FILE, { items: {} });

  if (feed === "collections") {
    const groups = new Map();
    for (const e of Object.values(idx.items || {})) {
      const c = e.enrich && e.enrich.collection;
      if (!c || !c.id) continue;
      const g = groups.get(c.id) || { id: c.id, name: c.name, poster: c.poster, parts: 0, latest: "" };
      g.parts++;
      if (cleanString(e.release_date) > g.latest) g.latest = cleanString(e.release_date);
      groups.set(c.id, g);
    }
    const all = Array.from(groups.values()).sort((a, b) => (a.latest < b.latest ? 1 : -1));
    const slice = all.slice((f.page - 1) * WN_PAGE_SIZE, f.page * WN_PAGE_SIZE);
    const cards = slice.map((g) => ({
      key: "collection:" + g.id, type: "collection", tmdb_id: g.id, title: g.name, original_title: "", year: g.latest.slice(0, 4), release_date: g.latest, released: true,
      poster: g.poster, backdrop: "", overview: g.parts + " جزء مكتشف من هذه السلسلة", rating: 0, vote_count: 0, popularity: 0, genres: [], genre_ids: [], language: "", countries: [],
      imported: false, existing: null, video: { state: "unknown" }, subtitle: { state: "unknown" }, first_seen_at: "", parts_known: g.parts,
    }));
    const notes = all.length ? [] : ["لا توجد سلاسل بعد: تظهر السلاسل بعد المزامنة (تُكتشف من تفاصيل الأفلام المفحوصة)."];
    return okResponse({ feed, page: f.page, total_pages: Math.max(1, Math.ceil(all.length / WN_PAGE_SIZE)), total_results: all.length, cards, source: "index", notes });
  }
  if (WN_VIEW_KEYS.includes(feed)) {
    const rows = wnViewEntries(feed, idx, lib, f);
    const start = (f.page - 1) * WN_PAGE_SIZE;
    const slice = rows.slice(start, start + WN_PAGE_SIZE);
    const cards = slice.map((e) => {
      if (feed === "imported") {
        const b = e.brief;
        return {
          key: e.key, type: e.kind, tmdb_id: e.tmdb_id, title: b.title, original_title: "", year: b.year, release_date: "", released: true, poster: b.poster, backdrop: "",
          overview: "", rating: 0, vote_count: 0, popularity: 0, genres: [], genre_ids: [], language: "", countries: [], imported: true, existing: b,
          video: { state: "unknown" }, subtitle: { state: "unknown" }, first_seen_at: "",
        };
      }
      return wnCardFromEntry(e, lib);
    });
    const notes = [];
    if (!Object.keys(idx.items || {}).length && feed !== "imported") notes.push("لم تتم المزامنة بعد: اضغط «تحديث/مزامنة» لملء هذه القائمة.");
    return okResponse({ feed, page: f.page, total_pages: Math.max(1, Math.ceil(rows.length / WN_PAGE_SIZE)), total_results: rows.length, cards, source: "index", notes });
  }
  if (!WN_FEED_KEYS.includes(feed)) throw new AdminError("invalid_feed", "قائمة اكتشاف غير معروفة.", 400);

  const cfg = await wnConfig(env);
  const [scope, name] = feed.split(":");
  const kinds = scope === "all" ? ["movie", "series"] : [scope === "tv" ? "series" : "movie"];
  const wanted = f.type === "any" ? kinds : kinds.filter((k) => k === f.type);
  const notes = [];
  if (name === "trending" && (f.country || f.sort === "popularity")) {
    if (f.country) notes.push("TMDB لا يدعم فلتر الدولة في «الأكثر رواجاً» للأفلام.");
  }
  if (!wanted.length) return okResponse({ feed, page: f.page, total_pages: 1, total_results: 0, cards: [], source: "tmdb", notes: ["هذه القائمة لا تحتوي هذا النوع."] });

  const [movieGenres, tvGenres] = await Promise.all([
    wanted.includes("movie") ? wnGenres(env, "movie") : Promise.resolve(new Map()),
    wanted.includes("series") ? wnGenres(env, "tv") : Promise.resolve(new Map()),
  ]);
  const results = await Promise.all(wanted.map(async (kind) => {
    try {
      const live = await wnLiveRows(env, cfg, kind, name, f);
      return { kind, ok: true, live };
    } catch (error) {
      return { kind, ok: false, error };
    }
  }));
  if (results.every((r) => !r.ok)) throw results[0].error; // every source failed -> a real error, not an empty page
  const cards = [];
  let totalPages = 1;
  let totalResults = 0;
  for (const r of results) {
    if (!r.ok) {
      notes.push("تعذر تحميل " + (r.kind === "series" ? "المسلسلات" : "الأفلام") + ": " + cleanString(r.error && r.error.message).slice(0, 120));
      continue;
    }
    totalPages = Math.max(totalPages, r.live.total_pages);
    totalResults += r.live.total_results;
    const gm = r.kind === "series" ? tvGenres : movieGenres;
    for (const item of r.live.rows) cards.push(wnCard(item.row, r.kind, gm, lib, idx, item.extra));
  }
  if (wanted.length > 1) cards.sort((a, b) => b.popularity - a.popularity);
  return okResponse({ feed, page: f.page, total_pages: totalPages, total_results: totalResults, cards, source: "tmdb", notes });
}

async function handleWnGenres(request, env) {
  const [movie, tv] = await Promise.all([wnGenres(env, "movie"), wnGenres(env, "tv")]);
  const list = (m) => Array.from(m.entries()).map(([id, name]) => ({ id, name })).filter((g) => g.name).sort((a, b) => (a.name < b.name ? -1 : 1));
  return okResponse({ movie: list(movie), series: list(tv) });
}

// ----------------------------------------------------------------------------- summary / config

function wnSummaryFrom(idx, lib, cfg) {
  const items = Object.values((idx && idx.items) || {});
  const week = Date.now() - 7 * 86400000;
  const s = { new_movies: 0, new_series: 0, recent_episodes: 0, ready: 0, unverified: 0, missing_video: 0, missing_subtitle: 0, tracked: items.length };
  const fresh = [];
  for (const e of items) {
    const key = e.kind + ":" + e.tmdb_id;
    const imported = lib.has(key);
    const vs = wnVideoBrief(e).state;
    if (!imported) {
      if (Date.parse(e.first_seen_at) >= week) {
        if (e.kind === "movie") s.new_movies++; else s.new_series++;
        fresh.push(e);
      }
      if (vs === "ok" && e.released !== false) s.ready++;
      else if (vs === "unverified") s.unverified++;
      else if (vs === "missing") s.missing_video++;
      if (wnSubBrief(e).state === "none") s.missing_subtitle++;
    } else if (e.kind === "series" && e.enrich && e.enrich.episodes_pending > 0) {
      s.recent_episodes += e.enrich.episodes_pending;
    }
  }
  fresh.sort((a, b) => Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at));
  return {
    counts: s,
    available: s.ready + s.unverified,
    latest: fresh.slice(0, 6).map((e) => ({ key: e.kind + ":" + e.tmdb_id, type: e.kind, title: e.title, poster: e.poster, year: e.year })),
    last_success_at: cfg.last_success_at || "",
    last_sync_at: cfg.last_sync_at || "",
    auto_sync: Boolean(cfg.auto_sync),
    interval_minutes: cfg.interval_minutes,
    synced: Boolean(cfg.last_success_at),
  };
}

async function handleWnSummary(request, env) {
  const [cfg, idx, list, cron] = await Promise.all([wnConfig(env), wnRead(env, WN_INDEX_FILE, { items: {} }), readMoviesList(env), wnCronStatus(env)]);
  return okResponse(Object.assign(wnSummaryFrom(idx, wnLibraryIndex(list), cfg), { cron }));
}

async function handleWnCron(request, env) {
  return okResponse(await wnCronStatus(env));
}

/** A movie collection (saga): every part, with what is already in the library, ready for a bulk import. */
async function handleWnCollection(request, env) {
  wnRate("collection", 60);
  const input = await readRequestData(request);
  const id = wnTmdbId(input.id || input.collection_id);
  const [ar, en] = await Promise.all([
    wnTmdb(env, "/collection/" + id, { language: "ar-SA" }, { ttl: 1800, nullOn404: true }),
    wnTmdb(env, "/collection/" + id, { language: "en-US" }, { ttl: 1800, nullOn404: true }),
  ]);
  if (!ar && !en) throw new AdminError("collection_not_found", "السلسلة غير موجودة في TMDB.", 404);
  const [list, idx, genres] = [await readMoviesList(env), await wnRead(env, WN_INDEX_FILE, { items: {} }), await wnGenres(env, "movie")];
  const lib = wnLibraryIndex(list);
  const rows = (Array.isArray(ar && ar.parts) && ar.parts.length ? ar.parts : Array.isArray(en && en.parts) ? en.parts : []).slice().sort((a, b) => cleanString(a.release_date).localeCompare(cleanString(b.release_date)));
  const enBy = new Map((Array.isArray(en && en.parts) ? en.parts : []).map((p) => [Number(p.id), p]));
  const cards = rows.map((row) => wnCard(Object.assign({}, enBy.get(Number(row.id)) || {}, row, { title: cleanString(row.title) || cleanString((enBy.get(Number(row.id)) || {}).title) }), "movie", genres, lib, idx));
  return okResponse({
    id: Number(id), name: pickText(ar, en, "name"), overview: pickText(ar, en, "overview"), poster: tmdbImage(pickText(ar, en, "poster_path"), "w500"),
    backdrop: tmdbImage(pickText(ar, en, "backdrop_path"), "w780"), parts: cards, total: cards.length,
    released: cards.filter((c) => c.released).length, in_library: cards.filter((c) => c.imported).length,
  });
}

function wnProviderStatus(env, cfg) {
  const keys = subsKeys(env);
  let tmdb = false;
  try { tmdbCredentials(env); tmdb = true; } catch { tmdb = false; }
  return {
    tmdb,
    subdl: Boolean(keys.subdl),
    subsource: Boolean(keys.subsource),
    opensubtitles: Boolean(keys.opensubtitles),
    opensubtitles_download: Boolean(keys.opensubtitles_login),
    subtitles_any: Boolean(keys.subdl || keys.subsource || keys.opensubtitles),
    video_providers: (cfg.video_providers || []).map((p) => ({
      id: p.id, name: p.name, kind: p.kind, enabled: p.enabled !== false,
      can_prove_playback: p.kind === "hls" || p.kind === "file",
      note: p.kind === "embed" ? "مشغّل مضمَّن (iframe): يمكن فحص أن الصفحة تعمل فقط، لا إثبات التشغيل ولا واجهة بحث رسمية." : "",
    })),
  };
}

async function handleWnConfigGet(request, env) {
  const cfg = await wnConfig(env);
  return okResponse({ config: cfg, providers: wnProviderStatus(env, cfg), feeds: WN_FEED_KEYS, views: WN_VIEW_KEYS, cron: await wnCronStatus(env) });
}

async function handleWnConfigSave(request, env) {
  const input = await readRequestData(request);
  const patch = {};
  if (input.auto_sync !== undefined) patch.auto_sync = truthy(input.auto_sync);
  if (input.interval_minutes !== undefined) {
    const n = numOf(input.interval_minutes);
    if (n < 30 || n > 10080) throw new AdminError("invalid_interval", "الفاصل الزمني بين 30 دقيقة و 7 أيام.", 400);
    patch.interval_minutes = n;
  }
  if (input.enrich_limit !== undefined) {
    const n = numOf(input.enrich_limit);
    if (n < 0 || n > 20) throw new AdminError("invalid_enrich_limit", "عدد الفحوصات لكل مزامنة بين 0 و 20.", 400);
    patch.enrich_limit = n;
  }
  if (input.max_pages !== undefined) {
    const n = numOf(input.max_pages);
    if (n < 1 || n > 3) throw new AdminError("invalid_max_pages", "عدد الصفحات بين 1 و 3.", 400);
    patch.max_pages = n;
  }
  if (input.feeds !== undefined) {
    if (!Array.isArray(input.feeds) || input.feeds.some((x) => !WN_FEED_KEYS.includes(x))) throw new AdminError("invalid_feeds", "قائمة المصادر تحتوي اسماً غير معروف.", 400);
    patch.feeds = input.feeds.slice(0, 8);
  }
  if (input.language !== undefined) {
    if (!["ar-SA", "en-US"].includes(cleanString(input.language))) throw new AdminError("invalid_language", "اللغة يجب أن تكون ar-SA أو en-US.", 400);
    patch.language = cleanString(input.language);
  }
  if (input.region !== undefined) {
    const r = cleanString(input.region).toUpperCase();
    if (r && !/^[A-Z]{2}$/.test(r)) throw new AdminError("invalid_region", "رمز الدولة حرفان (مثل SA).", 400);
    patch.region = r;
  }
  if (input.subtitle_languages !== undefined) {
    if (!Array.isArray(input.subtitle_languages) || !input.subtitle_languages.length) throw new AdminError("invalid_language", "اختر لغة ترجمة واحدة على الأقل.", 400);
    patch.subtitle_languages = input.subtitle_languages.map((l) => wnSubLang(l, false)).slice(0, 5);
  }
  if (input.subtitle_required !== undefined) patch.subtitle_required = truthy(input.subtitle_required);
  if (input.notify_telegram !== undefined) patch.notify_telegram = truthy(input.notify_telegram);
  if (input.video_providers !== undefined) {
    if (!Array.isArray(input.video_providers) || !input.video_providers.length || input.video_providers.length > 5) {
      throw new AdminError("invalid_provider", "أضف من 1 إلى 5 مزوّدين.", 400);
    }
    const seen = new Set();
    patch.video_providers = input.video_providers.map((p, i) => {
      const v = wnValidateProvider(p || {}, i);
      if (seen.has(v.id)) throw new AdminError("invalid_provider", "معرّف مزوّد مكرر: " + v.id, 400);
      seen.add(v.id);
      return v;
    });
  }
  const res = await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const merged = wnMergeConfig(Object.assign({}, doc || {}, patch));
    return { doc: merged, result: merged };
  });
  return okResponse({ config: res.result, providers: wnProviderStatus(env, res.result) }, "تم حفظ الإعدادات.");
}

// ----------------------------------------------------------------------------- details / season / video routes

function wnFindHeader(list, tmdb) {
  return findById(list, "series_" + tmdb) || list.find((x) => x && isSeriesHeader(x) && tmdbIdOf(x) === tmdb) || null;
}

async function handleWnDetails(request, env) {
  wnRate("details", 120);
  const input = await readRequestData(request);
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const meta = await wnFetchMeta(env, kind, tmdb);
  const [list, cfg] = [await readMoviesList(env), await wnConfig(env)];
  const registry = await readSectionsRegistry(env, list);
  const lib = wnLibraryIndex(list);
  const existing = lib.get(kind + ":" + tmdb) || null;
  const dups = findDuplicates(list, { type: kind, tmdb, imdb: meta.imdb_id, title: meta.title, year: meta.year });
  const out = Object.assign({}, meta, {
    import: { state: existing ? "imported" : dups.length ? "duplicate" : "new", existing, duplicates: dups },
    sections: registry.sections.map((s) => ({ id: s.id, name: s.name, type: s.type, enabled: s.enabled })),
    suggested_sections: suggestSections(registry, meta.genres.map((name) => ({ name, en: "" })), kind),
    existing_categories: wnExistingCategories(list),
    suggested_slug: suggestSlug(list, meta.original_title, meta.title, meta.year, tmdb, kind),
    collection: meta.collection || null,
    providers: wnProviderStatus(env, cfg),
  });
  if (kind === "series") {
    const header = wnFindHeader(list, tmdb);
    const lib2 = wnLibraryEpisodes(list, header);
    out.library_episodes = Object.fromEntries(Array.from(lib2.entries()).map(([s, set]) => [String(s), Array.from(set).sort((a, b) => a - b)]));
    out.library_header = header ? briefOf(header) : null;
    const le = meta.latest_episode;
    out.latest_in_library = Boolean(le && lib2.get(le.season) && lib2.get(le.season).has(le.episode));
  }
  return okResponse(out);
}

async function handleWnSeason(request, env) {
  wnRate("season", 120);
  const input = await readRequestData(request);
  const tmdb = wnTmdbId(input.tmdb_id);
  const season = numOf(input.season);
  if (season < 1 || season > 200) throw new AdminError("missing_numbers", "رقم الموسم غير صالح.", 400);
  const data = await wnSeasonEpisodes(env, tmdb, season);
  const list = await readMoviesList(env);
  const have = wnLibraryEpisodes(list, wnFindHeader(list, tmdb)).get(season) || new Set();
  data.episodes = data.episodes.map((e) => Object.assign({}, e, { in_library: have.has(e.episode) }));
  data.aired_count = data.episodes.filter((e) => e.aired).length;
  return okResponse(data);
}

async function handleWnVideo(request, env) {
  wnRate("video", 60);
  const input = await readRequestData(request);
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const cfg = await wnConfig(env);
  const customUrl = cleanString(input.custom_url);
  const meta = await wnFetchMeta(env, kind, tmdb);
  let targets = [];
  let total = 1;
  let nextOffset = 0;
  if (kind === "movie") {
    targets = [{ kind: "movie", tmdb, season: 0, episode: 0, released: meta.released }];
  } else {
    const season = numOf(input.season);
    if (!season) throw new AdminError("missing_numbers", "رقم الموسم مطلوب.", 400);
    const data = await wnSeasonEpisodes(env, tmdb, season);
    let want = Array.isArray(input.episodes) && input.episodes.length ? input.episodes.map((n) => numOf(n)).filter((n) => n > 0) : data.episodes.map((e) => e.episode);
    want = Array.from(new Set(want));
    total = want.length;
    const offset = Math.max(0, numOf(input.offset));
    const slice = want.slice(offset, offset + WN_MAX_PROBE_TARGETS);
    nextOffset = offset + slice.length >= total ? 0 : offset + slice.length;
    const byNum = new Map(data.episodes.map((e) => [e.episode, e]));
    targets = slice.map((n) => {
      const ep = byNum.get(n);
      // an episode number TMDB does not list for this season can never be resolved to a URL (no guessing)
      return { kind: "episode", tmdb, season, episode: n, released: ep ? ep.aired : false, unknown: !ep, name: ep ? ep.name : "" };
    });
    if (customUrl && targets.length !== 1) throw new AdminError("custom_needs_one", "الرابط اليدوي يُفحص لحلقة واحدة فقط.", 400);
  }
  const resolved = await wnMapLimit(targets, 4, async (t) => {
    if (t.unknown) {
      const base = { provider: "n/a", name: "—", kind: "embed", url: "" };
      return { kind: "episode", season: t.season, episode: t.episode, tmdb_id: String(tmdb), sources: [wnFinishSource(base, "unavailable", [wnCheck("tmdb", "الحلقة في TMDB", false, "الحلقة غير موجودة في هذا الموسم")])], best: "", fallbacks: [], status: "unavailable", name: "" };
    }
    const r = await wnResolveTarget(env, cfg, t, { customUrl: customUrl || "", fresh: truthy(input.refresh) });
    r.name = t.name || "";
    return r;
  });
  const embeds = (cfg.video_providers || []).filter((p) => p.enabled !== false && p.kind === "embed").map((p) => p.name);
  return okResponse(
    {
      type: kind, tmdb_id: Number(tmdb), season: kind === "series" ? numOf(input.season) : 0, targets: resolved, total, next_offset: nextOffset,
      limitations: embeds.length ? [embeds.join("، ") + ": مشغّل مضمَّن بلا واجهة بحث رسمية — أعلى مستوى ممكن «الصفحة تعمل (reachable)». لا يمكن إثبات التشغيل الفعلي من الخادم، لذلك يتطلب الاستيراد إقراراً صريحاً."] : [],
    },
    "تم فحص " + resolved.length + " عنصر."
  );
}


// ----------------------------------------------------------------------------- pre-import validation + idempotent import

const WN_STATES = {
  ready: { ar: "جاهز للاستيراد", en: "Ready to Import" },
  missing_video: { ar: "الفيديو مفقود", en: "Missing Video" },
  missing_metadata: { ar: "بيانات ناقصة", en: "Missing Metadata" },
  subtitle_missing: { ar: "الترجمة مفقودة", en: "Subtitle Missing" },
  invalid_subtitle: { ar: "ترجمة غير صالحة", en: "Invalid Subtitle" },
  duplicate: { ar: "محتوى مكرر", en: "Duplicate Content" },
  import_failed: { ar: "فشل الاستيراد", en: "Import Failed" },
  imported: { ar: "تم الاستيراد بنجاح", en: "Successfully Imported" },
};

function wnStateObj(state) {
  const s = WN_STATES[state] || WN_STATES.import_failed;
  return { state, state_label: s.ar, state_label_en: s.en };
}

function wnNormalizePlan(input) {
  const kind = wnKind(input.type);
  const tmdb = wnTmdbId(input.tmdb_id);
  const sec = input.section && typeof input.section === "object" ? input.section : {};
  const opt = input.options && typeof input.options === "object" ? input.options : {};
  const plan = {
    kind,
    tmdb,
    title: cleanString(input.title).slice(0, 200),
    section_id: cleanString(sec.id || input.section_id).slice(0, 80),
    new_section: cleanString(sec.new_name).slice(0, 60),
    new_section_type: SECTION_TYPES.has(lc(sec.new_type)) ? lc(sec.new_type) : kind === "series" ? "series" : "movie",
    categories: Array.isArray(input.categories) ? input.categories.map((c) => cleanString(c).slice(0, 40)).filter(Boolean) : null,
    ack: truthy(input.ack_unverified),
    options: {
      hidden: truthy(opt.hidden),
      quality: cleanString(opt.quality).slice(0, 12),
      notify: opt.notify !== false,
      subtitle_required: truthy(opt.subtitle_required),
      header_only: truthy(opt.header_only),
    },
    movie: null,
    episodes: [],
  };
  if (kind === "movie") {
    const m = input.movie && typeof input.movie === "object" ? input.movie : {};
    plan.movie = {
      video: cleanString(m.video_token || (m.video && m.video.token)),
      subtitle: cleanString(m.subtitle_token || (m.subtitle && m.subtitle.token)),
      fallbacks: (Array.isArray(m.fallback_tokens) ? m.fallback_tokens : []).map(cleanString).filter(Boolean).slice(0, 4),
    };
  } else {
    const seen = new Set();
    for (const s of Array.isArray(input.seasons) ? input.seasons : []) {
      const season = numOf(s && s.season);
      if (season < 1 || season > 200) throw new AdminError("invalid_season", "رقم موسم غير صالح.", 400);
      for (const e of Array.isArray(s.episodes) ? s.episodes : []) {
        const episode = numOf(e && e.episode);
        if (episode < 1 || episode > 9999) throw new AdminError("invalid_episode", "رقم حلقة غير صالح في الموسم " + season + ".", 400);
        const key = season + ":" + episode;
        if (seen.has(key)) throw new AdminError("duplicate_episode_in_plan", "الحلقة " + episode + " مكررة في الموسم " + season + " داخل الطلب.", 400);
        seen.add(key);
        plan.episodes.push({
          season, episode,
          video: cleanString(e.video_token || (e.video && e.video.token)),
          subtitle: cleanString(e.subtitle_token || (e.subtitle && e.subtitle.token)),
          fallbacks: (Array.isArray(e.fallback_tokens) ? e.fallback_tokens : []).map(cleanString).filter(Boolean).slice(0, 4),
        });
      }
    }
    if (plan.episodes.length > WN_MAX_EPISODES_PER_CALL) {
      throw new AdminError("too_many_episodes", "الحد " + WN_MAX_EPISODES_PER_CALL + " حلقة في الطلب الواحد؛ قسّم الاستيراد إلى دفعات.", 400);
    }
    plan.episodes.sort((a, b) => a.season - b.season || a.episode - b.episode);
  }
  return plan;
}

function wnPlanKey(plan) {
  return plan.kind === "movie" ? "movie:" + plan.tmdb : "series:" + plan.tmdb;
}

function wnEpLabel(season, episode) {
  return "S" + wnPad2(season) + "E" + wnPad2(episode);
}

const WN_IMG_CACHE = new Map();

/** true = image reachable, false = definitely broken (404/not an image), null = could not verify. */
async function wnCheckImage(url) {
  const u = cleanString(url);
  if (!u) return { pass: false, detail: "لا يوجد رابط بوستر." };
  const hit = WN_IMG_CACHE.get(u);
  if (hit && hit.exp > Date.now()) return hit.v;
  let v;
  try {
    const p = await wnProbeFetch(u, { method: "GET", headers: { range: "bytes=0-1023", "user-agent": WN_UA } }, 6000);
    const type = cleanString(p.res.headers.get("content-type")).split(";")[0].toLowerCase();
    const st = p.res.status;
    try { await p.res.body?.cancel(); } catch { /* ignore */ }
    if ((st === 200 || st === 206) && type.startsWith("image/")) v = { pass: true, detail: type };
    else if (st === 404 || st === 410 || ((st === 200 || st === 206) && type && !type.startsWith("image/"))) v = { pass: false, detail: "HTTP " + st + " " + type };
    else v = { pass: null, detail: "HTTP " + st };
  } catch (error) {
    v = { pass: null, detail: "تعذر التحقق: " + cleanString(error && error.message).slice(0, 80) };
  }
  if (WN_IMG_CACHE.size > 300) WN_IMG_CACHE.delete(WN_IMG_CACHE.keys().next().value);
  WN_IMG_CACHE.set(u, { exp: Date.now() + 3600000, v });
  return v;
}

async function wnCheckVideoToken(env, token, target) {
  if (!cleanString(token)) return { ok: false, code: "no_video", message: "لم يتم اختيار مصدر فيديو." };
  const v = await wnVerifyToken(env, token);
  if (!v) return { ok: false, code: "bad_token", message: "رمز التحقق من الفيديو غير صالح (لم يصدر من هذا الخادم)." };
  if (v.expired) return { ok: false, code: "expired", message: "انتهت صلاحية فحص المصدر (30 دقيقة). أعد البحث عن الفيديو." };
  const p = v.payload;
  if (p.k !== target.kind || String(p.t) !== String(target.tmdb) || numOf(p.s) !== numOf(target.season) || numOf(p.e) !== numOf(target.episode)) {
    return { ok: false, code: "mismatch", message: "المصدر المُتحقَّق منه لا يخص هذا الموسم/الحلقة — لن يُربط برابط خاطئ." };
  }
  const meta = WN_VERDICTS[p.v];
  if (!meta || !meta.importable) return { ok: false, code: "not_importable", message: (meta && meta.label) || "مصدر غير صالح." };
  return { ok: true, url: p.u, provider: p.p, kind: p.n, verdict: p.v, level: p.l, needs_ack: meta.ack, label: meta.label };
}

async function wnCheckSubtitleToken(env, token, target, lang) {
  if (!cleanString(token)) return { present: false };
  const parked = await wnReadParkedSubtitle(env, token);
  if (!parked) return { present: true, ok: false, message: "ملف الترجمة المحفوظ غير موجود أو انتهت صلاحيته. أعد جلب الترجمة." };
  const info = parked.info;
  if (String(info.tmdb) !== String(target.tmdb) || (info.kind === "series") !== (target.kind !== "movie") || numOf(info.season) !== numOf(target.season) || numOf(info.episode) !== numOf(target.episode)) {
    return { present: true, ok: false, message: "هذه الترجمة تخص عنواناً/حلقة أخرى — لن تُربط بالخطأ." };
  }
  const val = wnValidateSrt(parked.srt, { lang: info.lang, kind: target.kind === "movie" ? "movie" : "episode" });
  if (!val.ok) return { present: true, ok: false, message: val.issues.map((i) => i.message).join(" ") };
  return { present: true, ok: true, parked, lang: info.lang, cues: val.cues, source: info.source, release: info.release || "", warnings: val.warnings };
}

/** Builds the validation report for a plan. Nothing is written. */
async function wnValidate(env, plan) {
  const checks = [];
  const add = (id, label, status, detail) => { checks.push({ id, label, status, detail: detail === undefined ? "" : String(detail) }); };
  const items = [];
  let meta = null;
  try {
    meta = await wnFetchMeta(env, plan.kind, plan.tmdb);
  } catch (error) {
    if (!(error instanceof AdminError) || error.code === "missing_tmdb_key" || error.code === "bad_tmdb_key") throw error;
    add("tmdb", "معرّف TMDB والنوع", "fail", error.message);
  }
  const list = await readMoviesList(env);
  const registry = await readSectionsRegistry(env, list);
  const lib = wnLibraryIndex(list);
  const ctx = { meta, list, registry, lib, plan, subs: new Map(), videos: new Map(), seasonData: new Map(), header: null };

  if (meta) {
    add("tmdb", "معرّف TMDB والنوع", "pass", (plan.kind === "movie" ? "فيلم" : "مسلسل") + " #" + plan.tmdb);
    const title = plan.title || meta.title;
    if (!title) add("metadata", "البيانات الأساسية", "fail", "العنوان مفقود.");
    else if (!meta.poster) add("metadata", "البيانات الأساسية", "fail", "لا يوجد بوستر لهذا العنوان في TMDB.");
    else {
      const warn = [];
      if (!meta.description) warn.push("بلا وصف");
      if (!meta.year) warn.push("بلا سنة");
      add("metadata", "البيانات الأساسية", warn.length ? "warn" : "pass", warn.join("، "));
    }
    const img = await wnCheckImage(meta.poster);
    add("poster_url", "رابط البوستر", img.pass === true ? "pass" : img.pass === false ? "fail" : "warn", img.detail);
    const other = lib.get((plan.kind === "movie" ? "series" : "movie") + ":" + plan.tmdb);
    if (other) add("type_conflict", "اختلاف النوع", "warn", "المعرّف نفسه موجود في المكتبة كنوع آخر («" + other.title + "»).");
  } else {
    add("metadata", "البيانات الأساسية", "fail", "تعذر تحميل بيانات TMDB.");
  }

  // category / section
  if (plan.section_id) {
    const s = registry.sections.find((x) => x.id === plan.section_id);
    add("category", "القسم", s ? (s.enabled === false ? "warn" : "pass") : "fail", s ? s.name + (s.enabled === false ? " (معطّل)" : "") : "القسم المحدد غير موجود.");
  } else if (plan.new_section) {
    const hit = registry.sections.find((x) => normalizeTitleForMatch(x.name) === normalizeTitleForMatch(plan.new_section));
    add("category", "القسم", "pass", hit ? "سيُستخدم القسم الموجود «" + hit.name + "»." : "سيُنشأ قسم جديد «" + plan.new_section + "».");
  } else {
    add("category", "القسم", "pass", "بدون قسم (مسموح).");
  }
  if (plan.categories && (plan.categories.length > 8 || plan.categories.some((c) => /[\u0000-\u001f]/.test(c)))) add("categories", "التصنيفات", "fail", "حد أقصى 8 تصنيفات بلا رموز تحكم.");

  const dupFail = (msg) => add("duplicate", "التكرار", "fail", msg);
  let allEpisodesExist = false;
  if (plan.kind === "movie") {
    const existing = lib.get("movie:" + plan.tmdb);
    const dups = meta ? findDuplicates(list, { type: "movie", tmdb: plan.tmdb, imdb: meta.imdb_id, title: plan.title || meta.title, year: meta.year }).filter((d) => d.reasons.some((r) => r !== "slug")) : [];
    if (existing || dups.length) dupFail("«" + ((existing || dups[0]).title) + "» موجود بالفعل في المكتبة.");
    else add("duplicate", "التكرار", "pass", "لا يوجد تكرار.");
    // video
    const target = { kind: "movie", tmdb: plan.tmdb, season: 0, episode: 0 };
    const v = await wnCheckVideoToken(env, plan.movie.video, target);
    const sub = await wnCheckSubtitleToken(env, plan.movie.subtitle, target);
    items.push(wnItemReport("movie", "الفيلم", v, sub, plan, ctx));
  } else {
    const header = wnFindHeader(list, plan.tmdb);
    ctx.header = header;
    const have = wnLibraryEpisodes(list, header);
    if (!plan.episodes.length && !plan.options.header_only) add("episodes", "الحلقات", "fail", "لم تُحدَّد أي حلقة للاستيراد.");
    // season/episode consistency against TMDB (one call per season, cached)
    const seasons = Array.from(new Set(plan.episodes.map((e) => e.season)));
    for (const s of seasons) {
      try { ctx.seasonData.set(s, await wnSeasonEpisodes(env, plan.tmdb, s)); } catch (error) {
        if (!(error instanceof AdminError) || error.code === "missing_tmdb_key" || error.code === "bad_tmdb_key") throw error;
        add("seasons", "الموسم " + s, "fail", error.message);
      }
    }
    let existCount = 0;
    for (const e of plan.episodes) {
      const sd = ctx.seasonData.get(e.season);
      const epMeta = sd ? sd.episodes.find((x) => x.episode === e.episode) : null;
      e.meta = epMeta || null;
      const label = wnEpLabel(e.season, e.episode);
      const exists = Boolean(have.get(e.season) && have.get(e.season).has(e.episode));
      if (exists) existCount++;
      const v = exists ? { ok: true, skip: true } : await wnCheckVideoToken(env, e.video, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      const sub = exists ? { present: false } : await wnCheckSubtitleToken(env, e.subtitle, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      const item = wnItemReport(label, label + (epMeta && epMeta.name ? " — " + epMeta.name : ""), v, sub, plan, ctx);
      if (sd && !epMeta) {
        item.checks.unshift({ id: "tmdb_episode", label: "الحلقة في TMDB", status: "fail", detail: "الحلقة غير موجودة في الموسم " + e.season });
        item.state = "missing_metadata";
      } else if (epMeta && !epMeta.aired && !exists) {
        item.checks.unshift({ id: "aired", label: "تاريخ العرض", status: "fail", detail: "لم تُعرض بعد (" + (epMeta.air_date || "بلا تاريخ") + ")" });
        item.state = "missing_video";
      }
      if (exists) { item.state = "duplicate"; item.skipped = true; item.checks.push({ id: "duplicate", label: "التكرار", status: "warn", detail: "موجودة في المكتبة — ستُتخطى." }); }
      items.push(item);
    }
    allEpisodesExist = plan.episodes.length > 0 && existCount === plan.episodes.length;
    if (allEpisodesExist) dupFail("كل الحلقات المحددة موجودة بالفعل.");
    else add("duplicate", "التكرار", existCount ? "warn" : "pass", existCount ? existCount + " حلقة موجودة وستُتخطى." : header ? "المسلسل موجود — ستُضاف الحلقات إليه." : "لا يوجد تكرار.");
  }

  const requiresAck = items.some((i) => i.requires_ack) && !items.every((i) => i.skipped);
  const fails = checks.filter((c) => c.status === "fail");
  const importable = items.filter((i) => !i.skipped);
  let state = "ready";
  const dupChk = checks.find((c) => c.id === "duplicate" && c.status === "fail");
  if (dupChk) state = "duplicate";
  else if (checks.some((c) => c.status === "fail" && ["tmdb", "metadata", "poster_url", "category", "categories", "episodes", "seasons"].includes(c.id))) state = "missing_metadata";
  else if (importable.some((i) => i.state === "missing_metadata")) state = "missing_metadata";
  else if (importable.some((i) => i.state === "missing_video")) state = "missing_video";
  else if (importable.some((i) => i.state === "invalid_subtitle")) state = "invalid_subtitle";
  else if (importable.some((i) => i.state === "subtitle_missing")) state = "subtitle_missing";
  else if (fails.length) state = "missing_metadata";
  const ok = state === "ready";
  return {
    report: Object.assign(wnStateObj(state), {
      ok,
      requires_ack: ok && requiresAck,
      can_import: ok && (!requiresAck || plan.ack),
      checks,
      items,
      warnings: checks.filter((c) => c.status === "warn").map((c) => c.detail).concat(importable.flatMap((i) => i.warnings || [])),
    }),
    ctx,
  };
}

function wnItemReport(id, label, v, sub, plan, ctx) {
  const checks = [];
  let state = "ready";
  const warnings = [];
  let requiresAck = false;
  if (v.skip) {
    return { id, label, state: "ready", checks, skipped: false, video: null, subtitle: null };
  }
  if (!v.ok) {
    checks.push({ id: "video", label: "الفيديو", status: "fail", detail: v.message });
    state = "missing_video";
  } else {
    checks.push({ id: "video", label: "الفيديو", status: v.needs_ack ? "warn" : "pass", detail: v.label + " · " + v.provider });
    if (v.needs_ack) { requiresAck = true; warnings.push(label + ": " + v.label); }
  }
  if (sub.present && !sub.ok) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "fail", detail: sub.message });
    if (state === "ready") state = "invalid_subtitle";
  } else if (sub.present) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "pass", detail: sub.lang + " · " + sub.cues + " سطر · " + sub.source });
    for (const w of sub.warnings || []) warnings.push(label + ": " + w.message);
  } else if (plan.options.subtitle_required) {
    checks.push({ id: "subtitle", label: "الترجمة", status: "fail", detail: "الترجمة مطلوبة ولم تُحدَّد." });
    if (state === "ready") state = "subtitle_missing";
  } else {
    checks.push({ id: "subtitle", label: "الترجمة", status: "skip", detail: "اختيارية — بدون ترجمة." });
  }
  return {
    id, label, state, checks, warnings, requires_ack: requiresAck,
    video: v.ok ? { url: v.url, provider: v.provider, verdict: v.verdict, level: v.level, kind: v.kind } : null,
    subtitle: sub.present && sub.ok ? { lang: sub.lang, cues: sub.cues, source: sub.source, release: sub.release } : null,
  };
}

async function handleWnValidate(request, env) {
  wnRate("validate", 90);
  const plan = wnNormalizePlan(await readRequestData(request));
  const { report } = await wnValidate(env, plan);
  return okResponse({ report }, report.state_label);
}

// ----------------------------------------------------------------------------- bookkeeping (ledger / index)

async function wnLedgerRecords(env) {
  const doc = await wnRead(env, WN_LEDGER_FILE, { records: [] });
  return Array.isArray(doc.records) ? doc.records : [];
}

async function wnLedgerAdd(env, record) {
  await wnMutate(env, WN_LEDGER_FILE, { records: [] }, (doc) => {
    if (!Array.isArray(doc.records)) doc.records = [];
    doc.records.unshift(record);
    if (doc.records.length > WN_LEDGER_MAX) doc.records.length = WN_LEDGER_MAX;
    return { doc };
  });
}

async function wnIndexMarkImported(env, key) {
  await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
    if (doc.items && doc.items[key]) doc.items[key].imported = true;
    return { doc };
  });
}

function wnSubKey(slug, season, episode, lang) {
  const s = cleanString(slug);
  if (!season) return s + "/" + s + "." + lang + ".srt";
  return s + "/season-" + wnPad2(season) + "/S" + wnPad2(season) + "E" + wnPad2(episode) + "." + lang + ".srt";
}

async function wnStoreSubtitle(env, key, srt) {
  await env.MOVIES_BUCKET.put(key, srt, {
    httpMetadata: { contentType: "application/x-subrip; charset=utf-8", cacheControl: "public, max-age=300" },
    customMetadata: { source: "whatsnew-v1", at: new Date().toISOString() },
  });
  return r2PublicUrl(key);
}

async function wnEnsureSection(env, plan, ctx) {
  if (plan.section_id) {
    const s = ctx.registry.sections.find((x) => x.id === plan.section_id);
    return { id: s.id, name: s.name, created: false };
  }
  if (!plan.new_section) return { id: "", name: "", created: false };
  const find = (registry) => registry.sections.find((x) => normalizeTitleForMatch(x.name) === normalizeTitleForMatch(plan.new_section));
  const hit = find(ctx.registry);
  if (hit) return { id: hit.id, name: hit.name, created: false, reused: true };
  for (let attempt = 0; attempt < 2; attempt++) {
    const registry = await readSectionsRegistry(env);
    const again = find(registry);
    if (again) return { id: again.id, name: again.name, created: false, reused: true };
    let base = slugifyLatin(plan.new_section);
    if (!base) {
      let h = 0;
      for (const ch of plan.new_section) h = (h * 31 + ch.codePointAt(0)) >>> 0;
      base = "sec-" + h.toString(36);
    }
    const slug = nextFreeSlug(base, (c) => registry.sections.some((s) => lc(s.slug) === c || s.id === c.replace(/-/g, "_")));
    const req = new Request("https://internal.invalid/admin/sections/create", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name: plan.new_section, type: plan.new_section_type, slug, id: slug.replace(/-/g, "_") }),
    });
    try {
      const res = await handleSectionsCreate(req, env);
      const body = await res.json();
      if (body && body.section) return { id: body.section.id, name: body.section.name, created: true, undo_id: body.undo_id || "" };
    } catch (error) {
      if (!(error instanceof AdminError) || !["slug_in_use", "id_in_use", "write_conflict"].includes(error.code)) throw error;
    }
  }
  throw new AdminError("category_create_failed", "تعذر إنشاء القسم الجديد. أعد المحاولة.", 409);
}

// ----------------------------------------------------------------------------- the import

async function handleWnImport(request, env) {
  wnRate("import", 80);
  const input = await readRequestData(request);
  const plan = wnNormalizePlan(input);
  const requestId = cleanString(input.request_id).slice(0, 80);
  const started = Date.now();

  // 1) a retried request (network retry / double tap) gets the first answer back — nothing is written twice
  if (requestId) {
    const prev = (await wnLedgerRecords(env)).find((r) => r.request_id === requestId);
    if (prev) return okResponse(Object.assign({}, prev.result, { replayed: true }), "تم تنفيذ هذا الطلب سابقاً — أُعيدت نتيجته دون تكرار.");
  }

  // 2) validate again, server-side (never trust the client's own report)
  const { report, ctx } = await wnValidate(env, plan);
  if (!report.ok) throw new AdminError(report.state === "duplicate" ? "duplicate_content" : "validation_failed", "لا يمكن الاستيراد: " + report.state_label + ".", report.state === "duplicate" ? 409 : 422, { report });
  if (report.requires_ack && !plan.ack) {
    throw new AdminError("ack_required", "بعض مصادر الفيديو غير قابلة للإثبات من الخادم (مشغّل مضمَّن/المزوّد يمنع الفحص). أكّد الإقرار ثم أعد الاستيراد.", 409, { report });
  }
  const meta = ctx.meta;
  const title = plan.title || meta.title;
  const category = plan.categories ? plan.categories.join("، ") : meta.category;

  // 3) section (existing, or created once)
  const section = await wnEnsureSection(env, plan, ctx);

  // 4) subtitles + items, committed to movies.json in ONE compare-and-swap write
  const movieTarget = { kind: "movie", tmdb: plan.tmdb, season: 0, episode: 0 };
  let moviePack = null;
  const epPacks = new Map();
  if (plan.kind === "movie") {
    moviePack = { video: await wnCheckVideoToken(env, plan.movie.video, movieTarget), sub: await wnCheckSubtitleToken(env, plan.movie.subtitle, movieTarget) };
  } else {
    for (const e of plan.episodes) {
      const t = { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode };
      epPacks.set(e.season + ":" + e.episode, { video: await wnCheckVideoToken(env, e.video, t), sub: await wnCheckSubtitleToken(env, e.subtitle, t) });
    }
  }

  let outcome = null;
  const usedTokens = [];
  const op = plan.options.notify ? (plan.kind === "movie" ? "create" : "season-commit") : "whatsnew-import";
  const res = await mutateMovies(env, async (list, holder, mctx) => {
    outcome = { created: [], skipped: [], header_created: false, slug: "", subtitles: 0 };
    usedTokens.length = 0;
    const nowMs = Date.now();
    if (plan.kind === "movie") {
      const dups = findDuplicates(list, { type: "movie", tmdb: plan.tmdb, imdb: meta.imdb_id, title, year: meta.year }).filter((d) => d.reasons.some((r) => r !== "slug"));
      if (dups.length) throw new AdminError("duplicate_content", "«" + title + "» موجود بالفعل في المكتبة.", 409, { existing: dups[0], matches: dups });
      const slug = suggestSlug(list, meta.original_title, title, meta.year, plan.tmdb, "movie");
      outcome.slug = slug;
      let subUrl = "";
      if (moviePack.sub.present && moviePack.sub.ok) {
        subUrl = await wnStoreSubtitle(env, wnSubKey(slug, 0, 0, moviePack.sub.lang), moviePack.sub.parked.srt);
        outcome.subtitles++;
        usedTokens.push(moviePack.sub.parked.token);
      }
      const item = {
        id: makeContentId({}, list),
        type: "movie",
        created_at: new Date(nowMs).toISOString(),
        created_at_ms: nowMs,
        title,
        original_title: meta.original_title,
        description: meta.description,
        year: meta.year,
        category,
        poster: meta.poster,
        backdrop: meta.backdrop,
        slug,
        section_id: section.id,
        section_name: section.name,
        imdb_id: meta.imdb_id,
        duration: meta.duration,
        tmdb: String(plan.tmdb),
        imdb: meta.rating_text,
        video: moviePack.video.url,
        link: moviePack.video.url,
        subtitle: subUrl,
        quality: plan.options.quality,
        series_id: "",
        season: "",
        episode: "",
      };
      for (const k of ["original_title", "imdb_id", "duration", "imdb", "description", "backdrop"]) if (!cleanString(item[k])) item[k] = "";
      if (plan.options.hidden) item.hidden = true;
      list.unshift(item);
      mctx.tracker.add(item);
      sortNewestFirst(list);
      outcome.created.push({ id: item.id, type: "movie", season: 0, episode: 0, title });
      return { expectDelta: 1, label: "استيراد فيلم (What's New): " + title };
    }

    // series: header once, then only the chosen episodes
    let header = wnFindHeader(list, plan.tmdb);
    if (!header) {
      const seriesId = "series_" + plan.tmdb;
      if (findById(list, seriesId)) throw new AdminError("id_in_use", "المعرّف " + seriesId + " مستخدم بعنصر ليس مسلسلاً.", 409);
      const slug = suggestSlug(list, meta.original_title, title, meta.year, plan.tmdb, "series");
      header = {
        id: seriesId, type: "series", created_at: new Date(nowMs).toISOString(), created_at_ms: nowMs,
        section_id: section.id, section_name: section.name, series_id: seriesId, season: "", episode: "", title,
        poster: meta.poster, series_poster: meta.poster, season_poster: "", backdrop: meta.backdrop, video: "", link: "",
        year: meta.year, category, imdb: meta.rating_text, description: meta.description, duration: "", slug, tmdb: String(plan.tmdb),
      };
      if (meta.imdb_id) header.imdb_id = meta.imdb_id;
      if (meta.original_title) header.original_title = meta.original_title;
      if (plan.options.quality) header.quality = plan.options.quality;
      if (plan.options.hidden) header.hidden = true;
      list.unshift(header);
      mctx.tracker.add(header);
      outcome.header_created = true;
      outcome.created.push({ id: header.id, type: "series", season: 0, episode: 0, title });
    }
    const sid = seriesKeyOf(header);
    const numeric = sid.match(/^series_(\d+)$/);
    const idBase = numeric ? numeric[1] : sid;
    const slug = slugForSeries(list, header, "") || slugifyLatin(meta.original_title || title) || "series-" + plan.tmdb;
    outcome.slug = slug;
    for (const e of plan.episodes) {
      const pack = epPacks.get(e.season + ":" + e.episode);
      const clash = episodeCollision(list, sid, e.season, e.episode, "");
      if (clash) { outcome.skipped.push({ season: e.season, episode: e.episode, reason: "exists", id: cleanString(clash.id) }); continue; }
      const inherit = episodeInheritance(list, header, e.season);
      const seasonMeta = (meta.seasons || []).find((s) => s.season === e.season);
      const effPoster = (seasonMeta && seasonMeta.poster) || inherit.season_poster || cleanString(header.poster);
      if (effPoster) { inherit.season_poster = effPoster; inherit.poster = effPoster; }
      const em = e.meta || {};
      const item = {
        id: idBase + "_s" + e.season + "e" + e.episode,
        type: "episode",
        created_at: new Date(nowMs).toISOString(),
        created_at_ms: nowMs,
        series_id: sid,
        season: String(e.season),
        episode: String(e.episode),
        title: cleanString(em.name) || "الحلقة " + e.episode,
        video: pack.video.url,
        link: pack.video.url,
        description: cleanString(em.overview).slice(0, 1500),
        duration: cleanString(em.duration),
      };
      if (pack.sub.present && pack.sub.ok) {
        item.subtitle = await wnStoreSubtitle(env, wnSubKey(slug, e.season, e.episode, pack.sub.lang), pack.sub.parked.srt);
        outcome.subtitles++;
        usedTokens.push(pack.sub.parked.token);
      }
      for (const [k, v] of Object.entries(inherit)) if (v !== undefined && cleanString(v) !== "") item[k] = cleanString(v);
      if (cleanString(em.still)) { item.backdrop = cleanString(em.still); item.thumb = cleanString(em.still); }
      if (cleanString(header.imdb_id)) item.imdb_id = cleanString(header.imdb_id);
      if (yearOfText(em.air_date)) item.year = yearOfText(em.air_date);
      if (findById(list, item.id)) item.id = item.id + "_" + randomId();
      list.unshift(item);
      mctx.tracker.add(item);
      outcome.created.push({ id: item.id, type: "episode", season: e.season, episode: e.episode, title: item.title });
    }
    if (!outcome.created.length && !outcome.header_created) {
      throw new AdminError("duplicate_content", "كل الحلقات المحددة موجودة بالفعل.", 409);
    }
    sortNewestFirst(list);
    return {
      expectDelta: outcome.created.length,
      label: "استيراد مسلسل (What's New): " + title + " — " + outcome.created.filter((c) => c.type === "episode").length + " حلقة",
    };
  }, { op, backup: plan.kind === "series" });

  // 5) bookkeeping — best effort: the content is already saved, a ledger hiccup must not turn that into a failure
  for (const t of usedTokens) await wnDropParkedSubtitle(env, t);
  const videos = plan.kind === "movie"
    ? [{ id: "movie", provider: moviePack.video.provider, verdict: moviePack.video.verdict, level: moviePack.video.level }]
    : plan.episodes.filter((e) => outcome.created.some((c) => c.season === e.season && c.episode === e.episode)).map((e) => {
      const p = epPacks.get(e.season + ":" + e.episode).video;
      return { id: wnEpLabel(e.season, e.episode), provider: p.provider, verdict: p.verdict, level: p.level };
    });
  const result = Object.assign(wnStateObj("imported"), {
    type: plan.kind, tmdb_id: Number(plan.tmdb), title, request_id: requestId, created: outcome.created, skipped: outcome.skipped,
    header_created: outcome.header_created, slug: outcome.slug, subtitles_attached: outcome.subtitles,
    section, undo_id: res.undo_id, backup: res.backup, videos, warnings: report.warnings, ms: Date.now() - started, at: new Date().toISOString(),
  });
  let bookkeeping = "";
  try {
    const fallbacks = {};
    const keep = async (label, tokens, target) => {
      const ok = [];
      for (const tk of tokens) {
        const v = await wnCheckVideoToken(env, tk, target);
        if (v.ok) ok.push({ provider: v.provider, url: v.url, verdict: v.verdict });
      }
      if (ok.length) fallbacks[label] = ok;
    };
    if (plan.kind === "movie") await keep("movie", plan.movie.fallbacks, movieTarget);
    else for (const e of plan.episodes) {
      if (outcome.created.some((c) => c.season === e.season && c.episode === e.episode)) {
        await keep(wnEpLabel(e.season, e.episode), e.fallbacks, { kind: "episode", tmdb: plan.tmdb, season: e.season, episode: e.episode });
      }
    }
    await wnLedgerAdd(env, {
      id: "imp_" + String(Date.now()).padStart(13, "0") + "_" + randomId(), request_id: requestId, key: wnPlanKey(plan), type: plan.kind, tmdb_id: Number(plan.tmdb),
      title, poster: meta.poster, at: result.at, undo_id: res.undo_id, ack_unverified: plan.ack, videos, fallbacks, result: Object.assign({}, result, { report: undefined }),
    });
    await wnIndexMarkImported(env, wnPlanKey(plan));
  } catch (error) {
    bookkeeping = "تم الحفظ لكن تعذر تسجيله في سجل الاستيراد: " + cleanString(error && error.message).slice(0, 100);
  }
  if (bookkeeping) result.bookkeeping_warning = bookkeeping;
  return okResponse(result, plan.kind === "movie" ? "تمت إضافة الفيلم إلى Cigram." : "تمت إضافة " + outcome.created.filter((c) => c.type === "episode").length + " حلقة إلى Cigram.");
}


// ----------------------------------------------------------------------------- synchronisation (manual + cron) + history

function wnEntryFromRow(row, kind, genreMap, feedKey, nowIso) {
  const tv = kind === "series";
  const date = cleanString(tv ? row.first_air_date : row.release_date);
  return {
    kind,
    tmdb_id: Number(row.id) || 0,
    title: cleanString(tv ? row.name : row.title) || cleanString(tv ? row.original_name : row.original_title),
    original_title: cleanString(tv ? row.original_name : row.original_title),
    year: yearOfText(date),
    release_date: date,
    released: Boolean(date) && date <= wnToday(),
    poster: tmdbImage(row.poster_path, "w342"),
    backdrop: tmdbImage(row.backdrop_path, "w780"),
    overview: cleanString(row.overview).slice(0, 240),
    rating: Number(Number(row.vote_average || 0).toFixed(1)),
    vote_count: Number(row.vote_count) || 0,
    popularity: Math.round(Number(row.popularity) || 0),
    genres: (Array.isArray(row.genre_ids) ? row.genre_ids : []).map((g) => genreMap.get(Number(g))).filter(Boolean),
    language: cleanString(row.original_language),
    countries: Array.isArray(row.origin_country) ? row.origin_country : [],
    feeds: [feedKey],
    first_seen_at: nowIso,
    last_seen_at: nowIso,
    imported: false,
    enrich: {},
  };
}

async function wnRunFeeds(env, cfg) {
  const nowIso = new Date().toISOString();
  const [movieGenres, tvGenres] = await Promise.all([wnGenres(env, "movie").catch(() => new Map()), wnGenres(env, "tv").catch(() => new Map())]);
  const collected = new Map();
  const errors = [];
  const report = [];
  for (const feed of cfg.feeds) {
    const [scope, name] = feed.split(":");
    const kinds = scope === "all" ? ["movie", "series"] : [scope === "tv" ? "series" : "movie"];
    for (const kind of kinds) {
      for (let page = 1; page <= cfg.max_pages; page++) {
        try {
          const live = await wnLiveRows(env, cfg, kind, name, wnFilters({ page, refresh: true }));
          for (const r of live.rows) {
            const e = wnEntryFromRow(r.row, kind, kind === "series" ? tvGenres : movieGenres, feed, nowIso);
            if (!e.tmdb_id) continue;
            const key = kind + ":" + e.tmdb_id;
            const prev = collected.get(key);
            if (prev) { if (!prev.feeds.includes(feed)) prev.feeds.push(feed); } else collected.set(key, e);
          }
          report.push({ feed, kind, page, ok: true, count: live.rows.length });
          if (page >= live.total_pages) break;
        } catch (error) {
          const message = cleanString(error && error.message).slice(0, 160);
          errors.push({ op: "feed:" + feed + ":" + kind, message });
          report.push({ feed, kind, page, ok: false, error: message });
          break;
        }
      }
    }
  }
  return { collected, errors, report };
}

async function wnMergeIndex(env, collected, lib) {
  const nowIso = new Date().toISOString();
  let newKeys = [];
  await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
    newKeys = [];
    if (!doc.items || typeof doc.items !== "object") doc.items = {};
    for (const [key, e] of collected) {
      const old = doc.items[key];
      if (old) {
        const feeds = Array.from(new Set((old.feeds || []).concat(e.feeds))).slice(0, 8);
        doc.items[key] = Object.assign({}, old, e, { feeds, first_seen_at: old.first_seen_at, enrich: old.enrich || {}, last_seen_at: nowIso });
      } else {
        doc.items[key] = e;
        newKeys.push(key);
      }
    }
    for (const [key, entry] of Object.entries(doc.items)) entry.imported = lib.has(key);
    const keys = Object.keys(doc.items);
    if (keys.length > WN_INDEX_MAX) {
      keys.sort((a, b) => Date.parse(doc.items[b].last_seen_at) - Date.parse(doc.items[a].last_seen_at));
      for (const k of keys.slice(WN_INDEX_MAX)) delete doc.items[k];
    }
    doc.updated_at = nowIso;
    return { doc };
  });
  return newKeys;
}

async function wnEnrichOne(env, cfg, entry, lib, list) {
  const key = entry.kind + ":" + entry.tmdb_id;
  const tmdb = String(entry.tmdb_id);
  const imported = lib.has(key);
  const nowIso = new Date().toISOString();
  const meta = await wnFetchMeta(env, entry.kind, tmdb);
  const enrich = { checked_at: nowIso };
  if (entry.kind === "movie" && meta.collection) enrich.collection = { id: meta.collection.id, name: meta.collection.name, poster: meta.collection.poster };
  let target = { kind: "movie", tmdb, season: 0, episode: 0, released: meta.released };
  if (entry.kind === "series") {
    const le = meta.latest_episode;
    enrich.seasons = meta.season_count;
    enrich.episodes = meta.episode_count;
    enrich.latest_episode = le;
    enrich.new_season = meta.new_season;
    if (imported) {
      const have = wnLibraryEpisodes(list, wnFindHeader(list, tmdb));
      let pending = 0;
      if (le) {
        const mine = Array.from(have.get(le.season) || []).filter((n) => n <= le.episode).length;
        pending = Math.max(0, le.episode - mine);
      }
      enrich.episodes_pending = pending;
    }
    target = le ? { kind: "episode", tmdb, season: le.season, episode: le.episode, released: true } : { kind: "episode", tmdb, season: 1, episode: 1, released: false };
  }
  const out = { enrich, video: null, subtitle: null, error: "" };
  if (!imported) {
    const r = await wnResolveTarget(env, cfg, target);
    const best = r.sources.find((s) => s.importable) || r.sources[0] || null;
    enrich.video = { verdict: r.status, provider: best ? best.provider : "", url: best && best.importable ? best.url : "", season: target.season, episode: target.episode, checked_at: nowIso };
    out.video = r.status;
    const keys = subsKeys(env);
    if (!keys.subdl && !keys.subsource && !keys.opensubtitles) {
      enrich.subtitle = { status: "unconfigured", checked_at: nowIso };
    } else if (target.released === false) {
      enrich.subtitle = { status: "ok", ar: 0, en: 0, checked_at: nowIso, note: "not_released" };
    } else {
      try {
        const t = wnSubTarget(meta, target.season, target.episode);
        const ar = await wnSubsSearch(env, t, "ar");
        let en = 0;
        if (!ar.items.length && cfg.subtitle_languages.includes("en")) en = (await wnSubsSearch(env, t, "en")).items.length;
        enrich.subtitle = { status: "ok", ar: ar.items.length, en, checked_at: nowIso };
      } catch (error) {
        enrich.subtitle = { status: "error", error: cleanString(error && error.message).slice(0, 120), checked_at: nowIso };
      }
    }
    out.subtitle = enrich.subtitle;
  }
  return Object.assign(out, { key });
}

async function wnEnrichBatch(env, cfg, limit) {
  const [list, idx] = [await readMoviesList(env), await wnRead(env, WN_INDEX_FILE, { items: {} })];
  const lib = wnLibraryIndex(list);
  const staleMs = 12 * 3600 * 1000;
  const pool = Object.values(idx.items || {}).filter((e) => {
    const imported = lib.has(e.kind + ":" + e.tmdb_id);
    const checked = e.enrich && e.enrich.checked_at ? Date.parse(e.enrich.checked_at) : 0;
    if (imported && e.kind !== "series") return false;
    return !checked || Date.now() - checked > staleMs;
  });
  pool.sort((a, b) => {
    const ua = a.enrich && a.enrich.checked_at ? 1 : 0;
    const ub = b.enrich && b.enrich.checked_at ? 1 : 0;
    if (ua !== ub) return ua - ub;
    return Date.parse(b.first_seen_at) - Date.parse(a.first_seen_at);
  });
  const batch = pool.slice(0, Math.max(0, limit));
  const counters = { enriched: 0, videos_resolved: 0, videos_unverified: 0, videos_missing: 0, subtitles_available: 0, failed: 0 };
  const errors = [];
  const results = await wnMapLimit(batch, 2, async (entry) => {
    try {
      return await wnEnrichOne(env, cfg, entry, lib, list);
    } catch (error) {
      errors.push({ op: "enrich:" + entry.kind + ":" + entry.tmdb_id, message: cleanString(error && error.message).slice(0, 160) });
      counters.failed++;
      return null;
    }
  });
  const done = results.filter(Boolean);
  for (const r of done) {
    counters.enriched++;
    if (r.video === "playable" || r.video === "embed_reachable") counters.videos_resolved++;
    else if (r.video === "unverified") counters.videos_unverified++;
    else if (r.video && r.video !== "not_released") counters.videos_missing++;
    if (r.subtitle && (r.subtitle.ar > 0 || r.subtitle.en > 0)) counters.subtitles_available++;
  }
  if (done.length) {
    await wnMutate(env, WN_INDEX_FILE, { items: {} }, (doc) => {
      for (const r of done) if (doc.items && doc.items[r.key]) doc.items[r.key].enrich = Object.assign({}, doc.items[r.key].enrich || {}, r.enrich);
      return { doc };
    });
  }
  return Object.assign(counters, { remaining: Math.max(0, pool.length - batch.length), errors });
}

async function wnHistorySave(env, run) {
  await wnMutate(env, WN_HISTORY_FILE, { runs: [] }, (doc) => {
    if (!Array.isArray(doc.runs)) doc.runs = [];
    const i = doc.runs.findIndex((r) => r.id === run.id);
    if (i >= 0) doc.runs[i] = run; else doc.runs.unshift(run);
    if (doc.runs.length > WN_HISTORY_MAX) doc.runs.length = WN_HISTORY_MAX;
    return { doc };
  });
}

function wnFoldCounters(run, c) {
  for (const k of ["enriched", "videos_resolved", "videos_unverified", "videos_missing", "subtitles_available", "failed"]) run[k] = (run[k] || 0) + (c[k] || 0);
  for (const e of c.errors || []) if (run.errors.length < 30) run.errors.push(e);
}

/** One Telegram message per sync that found new titles — arrives with the app closed. Never throws. */
async function wnTelegramNew(env, newKeys) {
  try {
    const idx = await wnRead(env, WN_INDEX_FILE, { items: {} });
    const rows = newKeys.map((k) => idx.items[k]).filter(Boolean).sort((a, b) => (b.popularity || 0) - (a.popularity || 0));
    if (!rows.length) return "empty";
    const icon = (e) => (e.kind === "series" ? "📺" : "🎬");
    const vid = (e) => {
      const s = wnVideoBrief(e).state;
      return s === "ok" ? " ✅ فيديو متاح" : s === "unverified" ? " ⚠️ فيديو غير مُتحقَّق" : s === "missing" ? " ❌ بلا فيديو" : !e.released ? " ⏳ قريباً" : "";
    };
    const lines = ["🆕 <b>جديد في ما الجديد؟</b> — " + rows.length + " عنوان جديد"];
    for (const e of rows.slice(0, 10)) {
      lines.push("• " + icon(e) + " <b>" + reqEsc(e.title) + "</b>" + (e.year ? " (" + e.year + ")" : "") + (e.rating ? " ★" + e.rating : "") + vid(e));
    }
    if (rows.length > 10) lines.push("… و" + (rows.length - 10) + " غيرها");
    lines.push("افتح تطبيق الإدارة ← ما الجديد؟ ثم اضغط البوستر لإضافته.");
    const res = await reqTgSend(env, lines.join("\n"));
    return res.ok ? "sent" : String(res.description || res.http || "failed");
  } catch (error) {
    updLog("whatsnew_telegram_failed", { message: String(error && error.message) });
    return "failed";
  }
}

async function wnSync(env, trigger, enrichLimit) {
  const started = Date.now();
  const at = new Date().toISOString();
  let cfg = null;
  await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const c = wnMergeConfig(doc);
    if (c.lease_until && c.lease_until > Date.now()) throw new AdminError("sync_running", "هناك مزامنة جارية الآن. انتظر انتهاءها ثم أعد المحاولة.", 409);
    c.lease_until = Date.now() + 4 * 60000;
    c.last_started_at = at;
    cfg = c;
    return { doc: c };
  });
  const run = {
    id: "run_" + String(Date.now()).padStart(13, "0") + "_" + randomId(), at, trigger, status: "running", feeds: [], discovered: 0, new_items: 0, new_movies: 0, new_series: 0,
    recent_episodes: 0, enriched: 0, videos_resolved: 0, videos_unverified: 0, videos_missing: 0, subtitles_available: 0, failed: 0, errors: [], ms: 0,
  };
  let success = false;
  let freshKeys = [];
  try {
    try { tmdbCredentials(env); } catch { throw new AdminError("missing_tmdb_key", "لم يتم إعداد TMDB_API_KEY أو TMDB_READ_TOKEN في إعدادات الـ Worker.", 503); }
    const list = await readMoviesList(env);
    const lib = wnLibraryIndex(list);
    const { collected, errors, report } = await wnRunFeeds(env, cfg);
    run.feeds = report;
    run.discovered = collected.size;
    run.failed += errors.length;
    for (const e of errors) run.errors.push(e);
    const newKeys = await wnMergeIndex(env, collected, lib);
    freshKeys = newKeys;
    run.new_items = newKeys.length;
    run.new_movies = newKeys.filter((k) => k.startsWith("movie:")).length;
    run.new_series = newKeys.filter((k) => k.startsWith("series:")).length;
    run.recent_episodes = Array.from(collected.values()).filter((e) => e.feeds.includes("tv:recent_episodes")).length;
    const limit = Math.min(20, Math.max(0, numOf(enrichLimit)));
    if (limit > 0 && collected.size) wnFoldCounters(run, await wnEnrichBatch(env, cfg, limit));
    await wnCleanParkedSubtitles(env);
    if (trigger === "cron" && cfg.notify_telegram !== false && freshKeys.length) run.telegram = await wnTelegramNew(env, freshKeys);
    success = collected.size > 0 || errors.length === 0;
    run.status = errors.length && !collected.size ? "failed" : errors.length || run.errors.length ? "partial" : "ok";
  } catch (error) {
    run.status = "failed";
    run.failed++;
    run.errors.push({ op: "sync", message: cleanString(error && error.message).slice(0, 200), code: error && error.code ? error.code : "" });
    if (error instanceof AdminError && error.code === "missing_tmdb_key") {
      run.ms = Date.now() - started;
      await wnHistorySave(env, run).catch(() => {});
      await wnMutate(env, WN_CONFIG_FILE, null, (doc) => { const c = wnMergeConfig(doc); c.lease_until = 0; c.last_sync_at = at; return { doc: c }; }).catch(() => {});
      throw error;
    }
  }
  run.ms = Date.now() - started;
  await wnHistorySave(env, run).catch(() => {});
  await wnMutate(env, WN_CONFIG_FILE, null, (doc) => {
    const c = wnMergeConfig(doc);
    c.lease_until = 0;
    c.last_sync_at = at;
    if (success) c.last_success_at = at;
    return { doc: c };
  }).catch(() => {});
  return run;
}

/** Called by the Worker's every-minute cron. Cheap when auto-sync is off (one small R2 read). */
async function wnCronTick(env) {
  await wnHeartbeat(env).catch(() => {});
  const cfg = await wnConfig(env);
  if (!cfg.auto_sync) return;
  if (Date.now() - (Date.parse(cfg.last_started_at) || 0) < cfg.interval_minutes * 60000) return;
  if (cfg.lease_until && cfg.lease_until > Date.now()) return;
  try { tmdbCredentials(env); } catch { return; }
  await wnSync(env, "cron", cfg.enrich_limit);
}

async function handleWnSync(request, env) {
  wnRate("sync", 10, 120000);
  const input = await readRequestData(request);
  const run = await wnSync(env, "manual", input.enrich === undefined ? 4 : numOf(input.enrich));
  const [cfg, idx, list] = await Promise.all([wnConfig(env), wnRead(env, WN_INDEX_FILE, { items: {} }), readMoviesList(env)]);
  return okResponse({ run, summary: wnSummaryFrom(idx, wnLibraryIndex(list), cfg) }, run.status === "failed" ? "فشلت المزامنة." : "اكتملت المزامنة.");
}

async function handleWnEnrich(request, env) {
  wnRate("enrich", 40, 120000);
  const input = await readRequestData(request);
  const cfg = await wnConfig(env);
  const limit = Math.min(8, Math.max(1, numOf(input.limit) || 4));
  const counters = await wnEnrichBatch(env, cfg, limit);
  const runId = cleanString(input.run_id);
  if (runId) {
    await wnMutate(env, WN_HISTORY_FILE, { runs: [] }, (doc) => {
      const run = (doc.runs || []).find((r) => r.id === runId);
      if (run) wnFoldCounters(run, counters);
      return { doc };
    });
  }
  return okResponse(counters, counters.enriched ? "تم فحص " + counters.enriched + " عنصر." : "لا يوجد ما يُفحص الآن.");
}

async function handleWnHistory(request, env, url) {
  const limit = Math.min(100, Math.max(1, numOf(url.searchParams.get("limit")) || 30));
  const [doc, ledger, cfg] = await Promise.all([wnRead(env, WN_HISTORY_FILE, { runs: [] }), wnLedgerRecords(env), wnConfig(env)]);
  const runs = (Array.isArray(doc.runs) ? doc.runs : []).slice(0, limit);
  const all = Array.isArray(doc.runs) ? doc.runs : [];
  const out = runs.map((r) => {
    const i = all.findIndex((x) => x.id === r.id);
    const newer = i > 0 ? Date.parse(all[i - 1].at) : Infinity;
    const from = Date.parse(r.at);
    const imported = ledger.filter((l) => Date.parse(l.at) >= from && Date.parse(l.at) < newer).reduce((n, l) => n + ((l.result && l.result.created) ? l.result.created.length : 0), 0);
    return Object.assign({}, r, { imported });
  });
  return okResponse({
    runs: out,
    total_imports: ledger.length,
    recent_imports: ledger.slice(0, 20).map((l) => ({ id: l.id, at: l.at, title: l.title, type: l.type, tmdb_id: l.tmdb_id, created: l.result && l.result.created ? l.result.created.length : 0, ack_unverified: Boolean(l.ack_unverified), videos: l.videos || [] })),
    last_success_at: cfg.last_success_at || "",
    last_sync_at: cfg.last_sync_at || "",
    auto_sync: cfg.auto_sync,
    interval_minutes: cfg.interval_minutes,
  });
}

// ----------------------------------------------------------------------------- 18.2 management decisions

const WN_DECISIONS_FILE = "whatsnew/decisions.json";
const WN_DECISION_VALUES = ["", "review", "approved", "rejected", "imported"];

async function handleWnDecision(request, env) {
  wnRate("decision", 240);
  const input = await readRequestData(request);
  const key = cleanString(input.key).slice(0, 60);
  if (!/^(movie|series|tv|collection):\d{1,10}$/.test(key)) throw new AdminError("invalid_key", "مفتاح العنوان غير صالح.", 400);
  const decision = cleanString(input.decision);
  if (!WN_DECISION_VALUES.includes(decision)) throw new AdminError("invalid_decision", "قرار غير معروف.", 400);
  const note = cleanString(input.note).slice(0, 300);
  await wnMutate(env, WN_DECISIONS_FILE, { items: {} }, (doc) => {
    doc.items = doc.items && typeof doc.items === "object" ? doc.items : {};
    if (!decision) delete doc.items[key];
    else doc.items[key] = { decision, note, at: new Date().toISOString() };
    return { doc };
  });
  return okResponse({ key, decision }, "حُفظ القرار.");
}

async function handleWnDecisions(request, env) {
  const doc = await wnRead(env, WN_DECISIONS_FILE, { items: {} });
  return okResponse({ items: doc.items || {} });
}

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
  };
})();
// ============================================================================================================
// CATALOG FEED (18.4) — revision tracking + incremental change feed for movies.json
// ============================================================================================================
// Why: the app downloaded the whole catalogue (5.7 MB, ~5,600 records) to learn that one episode was added.
// What this adds (additive; every existing route behaves as before):
//   GET /catalog/manifest             { ok, schema, rev, records, ids_digest, etag, updated_at, window_from, max_page }
//   GET /catalog/changes?since=<rev>  { ok, from, to, more, upserts:[record], removed:[id], head:{rev,records,ids_digest,etag} }
//                                     or { ok, full:true, reason } when the client must download /movies instead
// Both answer ETag + If-None-Match (304) and `cache-control: no-cache` (clients revalidate; the answer is tiny).
//
// How it stays correct:
//   * The feed does NOT trust the admin's change tracker. After every write (and when movies.json is found changed without a feed
//     entry: a dashboard upload, another Worker) it hashes every record, compares with the hashes of the previous revision
//     (catalog/hashes.json) and logs EXACTLY the records that differ. A change that cannot be proven (missing / out-of-step hashes, a bulk
//     import of more than 300 records) becomes a RESET revision: clients older than it download /movies once.
//     A reset is never a data loss, only a lost optimisation.
//   * mutateMovies() calls catalogFeedSync() after its compare-and-swap succeeded; head + bounded log live in ONE R2 object.
//   * A feed failure never fails the admin's write (try/catch) and never blocks /movies.
//   * `ids_digest` (sha256 of the sorted ids, one per line) + `records` let a client prove, after applying a delta, that its copy has
//     exactly the server's id set. The client falls back to the full download when they differ.
//   * Nothing is deleted from movies.json or from R2 by this code.
// Storage: adminKey(env, "catalog/feed.json") in the admin bucket (ids + records that are public in movies.json anyway).
// Rollback: remove the two routes and the call in mutateMovies (the object can stay); apps then use /movies exactly as before.

const CATALOG_FEED_FILE = "catalog/feed.json";
const CATALOG_HASH_FILE = "catalog/hashes.json";
const CATALOG_FEED_SCHEMA = 1;
const CATALOG_FEED_MAX_ENTRIES = 120;
const CATALOG_FEED_MAX_BYTES = 1500000;          // serialised size budget of the log part
const CATALOG_FEED_ENTRY_MAX_RECORDS = 300;      // a bigger single change (bulk import) is stored as a reset
const CATALOG_FEED_PAGE = 300;                   // upserts + removals per /catalog/changes answer
const CATALOG_FEED_SETTLE_MS = 20000;            // a movies.json younger than this may still be followed by its own feed write

function catalogIdOf(entry) {
  return entry && typeof entry === "object" ? cleanString(entry.id) : "";
}

function catalogIdList(list) {
  const out = [];
  for (const e of list || []) {
    const id = catalogIdOf(e);
    if (id) out.push(id);
  }
  return out;
}

async function catalogIdsDigest(ids) {
  const sorted = ids.slice().sort();                                   // UTF-16 code-unit order, same as Java String.compareTo
  const bytes = new TextEncoder().encode(sorted.join("\n"));
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function catalogFeedEmpty() {
  return { schema: CATALOG_FEED_SCHEMA, rev: 0, etag: "", records: 0, ids_digest: "", updated_at: 0, window_from: 0, entries: [] };
}

async function catalogFeedLoad(env) {
  const bucket = adminBucket(env);
  const key = adminKey(env, CATALOG_FEED_FILE);
  const obj = await bucket.get(key);
  if (!obj) return { feed: null, etag: null };
  try {
    const feed = JSON.parse(await obj.text());
    if (!feed || typeof feed !== "object" || !Array.isArray(feed.entries) || !Number.isFinite(feed.rev)) return { feed: null, etag: obj.etag || null, corrupt: true };
    return { feed, etag: obj.etag || null };
  } catch {
    return { feed: null, etag: obj.etag || null, corrupt: true };
  }
}

/** Compare-and-swap of the single feed object. `etag` null = it must not exist yet. */
async function catalogFeedSave(env, feed, etag) {
  const bucket = adminBucket(env);
  const key = adminKey(env, CATALOG_FEED_FILE);
  // keep the log inside its budget: drop the OLDEST entries first and move window_from forward
  const size = () => JSON.stringify(feed.entries).length;
  while (feed.entries.length > CATALOG_FEED_MAX_ENTRIES || (feed.entries.length > 1 && size() > CATALOG_FEED_MAX_BYTES)) {
    const dropped = feed.entries.shift();
    feed.window_from = Math.max(feed.window_from, dropped.rev);
  }
  const options = { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } };
  options.onlyIf = etag ? { etagMatches: etag } : { etagDoesNotMatch: "*" };
  const result = await bucket.put(key, JSON.stringify(feed), options);
  return result !== null && result !== undefined;
}

/** 53-bit string hash (cyrb53): synchronous and fast; used only to detect that a record changed, never for security. */
function catalogHash53(str) {
  let h1 = 0xdeadbeef, h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return (4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36);
}

/** id -> { hash, record } for the list (the last record of a duplicated id wins; the client dedupes the same way). */
function catalogIndexList(list) {
  const map = new Map();
  for (const e of list || []) {
    const id = catalogIdOf(e);
    if (!id) continue;
    map.set(id, { hash: catalogHash53(JSON.stringify(e)), record: e });
  }
  return map;
}

async function catalogHashesLoad(env) {
  const obj = await adminBucket(env).get(adminKey(env, CATALOG_HASH_FILE));
  if (!obj) return { doc: null, etag: null };
  try {
    const doc = JSON.parse(await obj.text());
    if (!doc || typeof doc !== "object" || typeof doc.h !== "object" || !Number.isFinite(doc.rev)) return { doc: null, etag: obj.etag || null };
    return { doc, etag: obj.etag || null };
  } catch {
    return { doc: null, etag: obj.etag || null };
  }
}

async function catalogHashesSave(env, doc, etag) {
  const options = { httpMetadata: { contentType: "application/json; charset=utf-8", cacheControl: "no-store" } };
  options.onlyIf = etag ? { etagMatches: etag } : { etagDoesNotMatch: "*" };
  const result = await adminBucket(env).put(adminKey(env, CATALOG_HASH_FILE), JSON.stringify(doc), options);
  return result !== null && result !== undefined;
}

/**
 * Makes the feed describe `list` (the current content of movies.json, whose etag is `etagNow`).
 * Safe to call at any time, from any number of requests: the work is idempotent and every write is a compare-and-swap.
 * Returns the feed that is current afterwards, or null when it could not be updated (the caller then serves /movies as before).
 */
async function catalogFeedSync(env, list, etagNow, op) {
  try {
    const cur = catalogIndexList(list);
    const digest = await catalogIdsDigest(Array.from(cur.keys()));
    for (let attempt = 0; attempt < 5; attempt++) {
      const lf = await catalogFeedLoad(env);
      const lh = await catalogHashesLoad(env);
      const feed = lf.feed || catalogFeedEmpty();
      const next = {};
      for (const [id, v] of cur) next[id] = v.hash;
      const up = [], rm = [];
      let resetWhy = "";
      if (!lf.feed) resetWhy = "bootstrap";
      else if (!lh.doc || lh.doc.rev !== feed.rev) resetWhy = "hash_mismatch";
      else {
        const prev = lh.doc.h;
        for (const [id, v] of cur) if (prev[id] !== v.hash) up.push(v.record);
        for (const id of Object.keys(prev)) if (!cur.has(id)) rm.push(id);
        if (up.length + rm.length > CATALOG_FEED_ENTRY_MAX_RECORDS) resetWhy = "bulk";
      }
      if (!resetWhy && up.length + rm.length === 0) {
        if (feed.etag === etagNow) return feed;
        feed.etag = etagNow;                                      // e.g. a config-only write: head etag follows, no revision
        feed.updated_at = Date.now();
        if (await catalogFeedSave(env, feed, lf.etag)) return feed;
      } else {
        const rev = feed.rev + 1;
        if (resetWhy) {
          feed.entries.push({ rev, at: Date.now(), op: "reset", reset: true, why: resetWhy, up: [], rm: [] });
          feed.window_from = rev;                                  // nobody older than `rev` can be served a delta
        } else {
          feed.entries.push({ rev, at: Date.now(), op: cleanString(op).slice(0, 40), up, rm });
        }
        feed.rev = rev;
        feed.records = cur.size;
        feed.ids_digest = digest;
        feed.etag = etagNow;
        feed.updated_at = Date.now();
        // hashes first (CAS), then the feed (CAS): a crash between the two leaves hashes ahead of the feed -> the next sync resets
        if (await catalogHashesSave(env, { schema: 1, rev, etag: etagNow, h: next }, lh.etag) && await catalogFeedSave(env, feed, lf.etag)) return feed;
      }
      await sleep(30 * (attempt + 1) + Math.floor(Math.random() * 40));
    }
  } catch (error) {
    console.log(JSON.stringify({ evt: "catalog_feed", ok: false, where: "sync", error: String(error && error.message || error).slice(0, 160) }));
  }
  return null;
}

/** Called by mutateMovies after its compare-and-swap write succeeded. Never throws. */
async function catalogFeedAfterWrite(env, list, op) {
  try {
    const head = await env.MOVIES_BUCKET.head(MOVIES_OBJECT);
    await catalogFeedSync(env, list, (head && head.etag) || "", op);
  } catch (error) {
    console.log(JSON.stringify({ evt: "catalog_feed", ok: false, where: "after_write", error: String(error && error.message || error).slice(0, 160) }));
  }
}

/**
 * Makes sure the feed describes the CURRENT movies.json before it is read. Returns { feed, busy } where busy = a very recent
 * write whose own feed entry may still be on its way (the caller answers "try again shortly", it never declares a reset for it).
 */
async function catalogFeedEnsure(env) {
  const bucket = env.MOVIES_BUCKET;
  const loaded = await catalogFeedLoad(env);
  const head = await bucket.head(MOVIES_OBJECT);
  if (!head) return { feed: loaded.feed, missing: true };
  if (loaded.feed && loaded.feed.etag && loaded.feed.etag === head.etag) return { feed: loaded.feed };
  const age = head.uploaded ? Date.now() - new Date(head.uploaded).getTime() : CATALOG_FEED_SETTLE_MS + 1;
  if (loaded.feed && age < CATALOG_FEED_SETTLE_MS) return { feed: loaded.feed, busy: true };
  const obj = await bucket.get(MOVIES_OBJECT);
  if (!obj) return { feed: loaded.feed, missing: true };
  let doc;
  try { doc = JSON.parse(await obj.text()); } catch { return { feed: loaded.feed, unreadable: true }; }
  const feed = await catalogFeedSync(env, getWritableMoviesArray(doc).list, obj.etag || head.etag, "foreign_write");
  return feed ? { feed } : { feed: loaded.feed, unreadable: !loaded.feed };
}

function catalogFeedHeaders(extra) {
  return corsHeaders(Object.assign({ "content-type": "application/json; charset=utf-8", "cache-control": "no-cache", "access-control-expose-headers": "ETag" }, extra || {}));
}

function catalogFeedReply(request, body, tag) {
  const etag = '"' + tag + '"';
  const inm = (request.headers.get("if-none-match") || "").trim();
  if (inm && inm.split(",").map((s) => s.trim().replace(/^W\//, "")).includes(etag)) {
    return new Response(null, { status: 304, headers: catalogFeedHeaders({ etag }) });
  }
  return new Response(JSON.stringify(body), { status: 200, headers: catalogFeedHeaders({ etag }) });
}

function catalogHead(feed) {
  return { rev: feed.rev, records: feed.records, ids_digest: feed.ids_digest, etag: feed.etag };
}

async function handleCatalogManifest(request, env) {
  try {
    const { feed, busy, missing, unreadable } = await catalogFeedEnsure(env);
    if (missing || !feed) return json({ ok: false, error: "catalogue_missing" }, 404);
    if (unreadable) return json({ ok: false, error: "catalogue_unreadable" }, 503);
    const body = Object.assign({ ok: true, schema: CATALOG_FEED_SCHEMA }, catalogHead(feed), {
      updated_at: feed.updated_at, window_from: feed.window_from, max_page: CATALOG_FEED_PAGE,
    });
    if (busy) { body.busy = true; return new Response(JSON.stringify(body), { status: 200, headers: catalogFeedHeaders({ "cache-control": "no-store" }) }); }
    return catalogFeedReply(request, body, "cat-" + feed.rev + "-" + feed.records);
  } catch (error) {
    return json({ ok: false, error: "feed_error" }, 500);
  }
}

async function handleCatalogChanges(request, env, url) {
  try {
    const since = Number.parseInt(url.searchParams.get("since") || "", 10);
    if (!Number.isFinite(since) || since < 0) return json({ ok: false, error: "bad_since" }, 400);
    const page = Math.max(20, Math.min(CATALOG_FEED_PAGE, Number.parseInt(url.searchParams.get("max") || "", 10) || CATALOG_FEED_PAGE));
    const { feed, busy, missing, unreadable } = await catalogFeedEnsure(env);
    if (missing || !feed) return json({ ok: false, error: "catalogue_missing" }, 404);
    if (unreadable) return json({ ok: false, error: "catalogue_unreadable" }, 503);
    const fullReply = (reason) => catalogFeedReply(request, { ok: true, full: true, reason, head: catalogHead(feed) }, "full-" + reason + "-" + feed.rev);
    if (busy) return new Response(JSON.stringify({ ok: true, busy: true, head: catalogHead(feed) }), { status: 200, headers: catalogFeedHeaders({ "cache-control": "no-store" }) });
    if (since === feed.rev) return catalogFeedReply(request, { ok: true, from: since, to: since, more: false, upserts: [], removed: [], head: catalogHead(feed) }, "chg-" + since + "-" + since);
    if (since > feed.rev) return fullReply("ahead");
    if (since < feed.window_from) return fullReply("window");
    const take = feed.entries.filter((e) => e.rev > since).sort((a, b) => a.rev - b.rev);
    if (take.length === 0 || take[0].rev !== since + 1) return fullReply("gap");
    const latest = new Map();                      // id -> record | null (removed); later revisions win
    let to = since, count = 0;
    for (const e of take) {
      if (e.reset) return fullReply("reset");
      const size = (e.up ? e.up.length : 0) + (e.rm ? e.rm.length : 0);
      if (to > since && count + size > page) break;
      for (const r of e.up || []) latest.set(catalogIdOf(r), r);
      for (const id of e.rm || []) latest.set(String(id), null);
      count += size;
      to = e.rev;
    }
    const upserts = [], removed = [];
    for (const [id, rec] of latest) { if (rec) upserts.push(rec); else removed.push(id); }
    return catalogFeedReply(request, { ok: true, from: since, to, more: to < feed.rev, upserts, removed, head: catalogHead(feed) }, "chg-" + since + "-" + to + "-" + feed.rev);
  } catch (error) {
    return json({ ok: false, error: "feed_error" }, 500);
  }
}

// ----------------------------------------------------------------------------- route table

const WN_ROUTES = (() => {
  const A = adminRoute;
  return {
    "GET /admin/discover/summary": A(handleWnSummary),
    "GET /admin/discover/config": A(handleWnConfigGet),
    "POST /admin/discover/config": A(handleWnConfigSave),
    "POST /admin/discover/feed": A(handleWnFeed),
    "GET /admin/discover/genres": A(handleWnGenres),
    "POST /admin/discover/details": A(handleWnDetails),
    "POST /admin/discover/season": A(handleWnSeason),
    "POST /admin/discover/video": A(handleWnVideo),
    "POST /admin/discover/subtitles": A(handleWnSubtitles),
    "POST /admin/discover/subtitles/fetch": A(handleWnSubtitlesFetch),
    "POST /admin/discover/subtitles/validate": A(handleWnSubtitlesValidate),
    "POST /admin/discover/validate": A(handleWnValidate),
    "POST /admin/discover/import": A(handleWnImport),
    "POST /admin/discover/sync": A(handleWnSync),
    "POST /admin/discover/enrich": A(handleWnEnrich),
    "GET /admin/discover/history": A(handleWnHistory),
    "GET /admin/discover/cron": A(handleWnCron),
    "POST /admin/discover/collection": A(handleWnCollection),
    "POST /admin/discover/decision": A(handleWnDecision),
    "GET /admin/discover/decisions": A(handleWnDecisions),
  };
})();


// -----------------------------------------------------------------------------
// MAIN ROUTER
// -----------------------------------------------------------------------------

export default {
  async scheduled(event, env, ctx) {
    // v8 requests: new viewer requests -> Telegram (scans every REQ_SCAN_EVERY_MS)
    ctx.waitUntil(
      reqBridgeScan(env, Date.now(), REQ_SCAN_EVERY_MS - 15000).catch((error) =>
        updLog("requests_scan_failed", { message: String(error && error.message) })
      )
    );
    // What's New (v11): optional automatic discovery sync (reads one small R2 object when it is switched off).
    ctx.waitUntil(wnCronTick(env).catch((error) => updLog("whatsnew_cron_failed", { message: String(error && error.message) })));
    // Cron Trigger (every minute): publish the scheduled update when its time has come.
    ctx.waitUntil(
      updActivateDue(env, true).catch((error) => updLog("cron_failed", { message: String(error && error.message) }))
    );
    // fold the telemetry inbox into the day aggregates (ops section)
    ctx.waitUntil(
      opsCompact(env, OPS_COMPACT_CRON).catch((error) => updLog("ops_compact_failed", { message: String(error && error.message) }))
        .then(() => liveTick(env))
        .catch((error) => updLog("live_tick_failed", { message: String(error && error.message) }))
    );
  },

  async fetch(request, env, ctx) {
    const response = await cigramRoute(request, env, ctx);
    pushDrainInto(ctx); // v9: let the phone notifications finish after the reply
    return response;
  },
};

async function cigramRoute(request, env, ctx) {
    try {
      if (!env.MOVIES_BUCKET) {
        return json(
          {
            ok: false,
            error: "missing_binding",
            required: "MOVIES_BUCKET",
          },
          500
        );
      }

      if (request.method === "OPTIONS") {
        return new Response(null, {
          status: 204,
          headers: corsHeaders(),
        });
      }

      const url = new URL(request.url);
      const path = url.pathname.replace(/\/+$/, "") || "/";

      // Health / route list
      if (request.method === "GET" && (path === "/" || path === "/health")) {
        return json({
          ok: true,
          service: "cigram-admin-api",
          routes: [
            "GET /movies",
            "POST /add-movie",
            "POST /preview-series",
            "POST /add-series",
            "GET /carousel",
            "POST /upload-carousel-image",
            "POST /publish-carousel",
            "POST /remove-carousel",
            "POST /reorder-carousel",
            "GET /update",
            "POST /publish-update",
          ]
            .concat(Object.keys(UPDATE_ROUTES))
            .concat(Object.keys(V2_ROUTES))
            .concat(Object.keys(MEDIA_ROUTES))
            .concat(Object.keys(OPS_ROUTES))
            .concat(Object.keys(REQUESTS_ROUTES))
            .concat(Object.keys(PUSH_ROUTES))
            .concat(Object.keys(WN_ROUTES))
            .concat(Object.keys(SUBHUB_ROUTES)),
          version: 7,
        });
      }

      // 18.4 incremental catalogue (public, conditional)
      if (request.method === "GET" && path === "/catalog/manifest") return handleCatalogManifest(request, env);
      if (request.method === "GET" && path === "/catalog/changes") return handleCatalogChanges(request, env, url);

      // Movies
      if (request.method === "GET" && path === "/movies") {
        return returnR2JsonConditional(request, env, MOVIES_OBJECT, []);
      }

      if (request.method === "POST" && path === "/add-movie") {
        return addMovie(request, env);
      }

      if (request.method === "POST" && path === "/preview-series") {
        return previewSeries(request, env);
      }

      if (request.method === "POST" && path === "/add-series") {
        return addSeries(request, env);
      }

      // Carousel
      if (request.method === "GET" && path === "/carousel") {
        return carouselPublicResponse(request, env, url);
      }

      if (request.method === "POST" && path === "/upload-carousel-image") {
        return uploadCarouselImage(request, env);
      }

      if (request.method === "POST" && path === "/publish-carousel") {
        return publishCarousel(request, env);
      }

      if (request.method === "POST" && path === "/remove-carousel") {
        return removeCarousel(request, env);
      }

      if (request.method === "POST" && path === "/reorder-carousel") {
        return reorderCarousel(request, env);
      }

      // Update system
      if (request.method === "GET" && path === "/update") {
        return updServePublic(env);
      }

      if (request.method === "POST" && path === "/publish-update") {
        return publishUpdate(request, env);
      }

      // Optional direct JSON aliases through Worker
      if (request.method === "GET" && path === "/cigram_carousel.json") {
        return returnR2JsonObject(env, CAROUSEL_OBJECT, {
          enabled: false,
          maxItems: null,
          items: [],
        });
      }

      if (request.method === "GET" && path === "/cigram_update.json") {
        return updServePublic(env);
      }

      const updateHandler = UPDATE_ROUTES[request.method + " " + path];
      if (updateHandler) return updateHandler(request, env, url, ctx);

      const v2Handler = V2_ROUTES[request.method + " " + path];
      if (v2Handler) return v2Handler(request, env, url);

      const mediaHandler = MEDIA_ROUTES[request.method + " " + path];
      if (mediaHandler) return mediaHandler(request, env, url);

      const opsHandler = OPS_ROUTES[request.method + " " + path];
      if (opsHandler) return opsHandler(request, env, url, ctx);

      const requestsHandler = REQUESTS_ROUTES[request.method + " " + path];
      if (requestsHandler) return requestsHandler(request, env, url, ctx);

      const pushHandler = PUSH_ROUTES[request.method + " " + path];
      if (pushHandler) return pushHandler(request, env, url, ctx);

      const wnHandler = WN_ROUTES[request.method + " " + path];
      if (wnHandler) return wnHandler(request, env, url, ctx);

      const subhubHandler = SUBHUB_ROUTES[request.method + " " + path];
      if (subhubHandler) return subhubHandler(request, env, url, ctx);

      return json({ ok: false, error: "not_found", path }, 404);
    } catch (error) {
      return json(
        {
          ok: false,
          error: "internal_error",
          message: error && error.message ? error.message : String(error),
        },
        500
      );
    }
}
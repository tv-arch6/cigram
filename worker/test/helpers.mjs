// Test doubles for the What's New Worker tests: an in-memory R2 with ETag compare-and-swap, a routable fetch mock,
// and TMDB / VidBolt / subtitle fixtures. These are MOCKS — they prove the Worker's logic, not a live provider.
import { createHash } from "node:crypto";
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
export const DIST = pathToFileURL(join(here, "..", "dist", "cigram-admin-api.worker.js")).href;
let moduleCounter = 0;
/** A fresh copy of the Worker module (fresh rate-limit / cache state). */
export async function loadWorker() {
  return (await import(DIST + "?instance=" + ++moduleCounter)).default;
}

export class FakeR2 {
  constructor() { this.map = new Map(); this.version = 0; this.puts = 0; }
  _obj(key, e) {
    return {
      key, size: e.bytes.length, etag: e.etag, httpEtag: '"' + e.etag + '"', uploaded: e.uploaded,
      customMetadata: e.customMetadata || {}, httpMetadata: e.httpMetadata || {},
      body: new Blob([e.bytes]).stream(),
      async text() { return new TextDecoder().decode(e.bytes); },
      async json() { return JSON.parse(new TextDecoder().decode(e.bytes)); },
      async arrayBuffer() { return e.bytes.slice().buffer; },
    };
  }
  async get(key) { const e = this.map.get(key); return e ? this._obj(key, e) : null; }
  async head(key) { return this.get(key); }
  async put(key, value, options = {}) {
    let bytes;
    if (typeof value === "string") bytes = new TextEncoder().encode(value);
    else if (value instanceof ArrayBuffer) bytes = new Uint8Array(value);
    else if (ArrayBuffer.isView(value)) bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    else if (value && typeof value.getReader === "function") bytes = new Uint8Array(await new Response(value).arrayBuffer());
    else bytes = new TextEncoder().encode(String(value));
    const cur = this.map.get(key);
    if (options.onlyIf && options.onlyIf.etagMatches !== undefined && (!cur || cur.etag !== options.onlyIf.etagMatches)) return null;
    const etag = createHash("md5").update(bytes).update(String(++this.version)).digest("hex");
    const e = { bytes, etag, uploaded: new Date(), customMetadata: options.customMetadata, httpMetadata: options.httpMetadata };
    this.map.set(key, e);
    this.puts++;
    return this._obj(key, e);
  }
  async delete(keys) { for (const k of Array.isArray(keys) ? keys : [keys]) this.map.delete(k); }
  async list({ prefix = "", limit = 1000, cursor } = {}) {
    const keys = Array.from(this.map.keys()).filter((k) => k.startsWith(prefix)).sort();
    const start = cursor ? Number(cursor) : 0;
    const slice = keys.slice(start, start + limit);
    return {
      objects: slice.map((k) => { const e = this.map.get(k); return { key: k, size: e.bytes.length, etag: e.etag, uploaded: e.uploaded, customMetadata: e.customMetadata || {}, httpMetadata: e.httpMetadata || {} }; }),
      truncated: start + limit < keys.length,
      cursor: String(start + limit),
    };
  }
  json(key) { const e = this.map.get(key); return e ? JSON.parse(new TextDecoder().decode(e.bytes)) : null; }
  text(key) { const e = this.map.get(key); return e ? new TextDecoder().decode(e.bytes) : null; }
  seedJson(key, value) { this.map.set(key, { bytes: new TextEncoder().encode(JSON.stringify(value)), etag: "seed" + ++this.version, uploaded: new Date() }); }
  keys(prefix = "") { return Array.from(this.map.keys()).filter((k) => k.startsWith(prefix)); }
}

export const TOKEN = "test-admin-token";

export function makeEnv(extra = {}) {
  const bucket = new FakeR2();
  bucket.seedJson("movies.json", []);
  return Object.assign({ MOVIES_BUCKET: bucket, ADMIN_TOKEN: TOKEN, TMDB_API_KEY: "tmdb-test-key", SUBDL_API_KEY: "subdl-test-key" }, extra);
}

/** Installs a fetch mock. handler(req, url) returns a Response, or throws. Returns the recorded calls. */
export function installFetch(handler) {
  const calls = [];
  globalThis.fetch = async (input, init = {}) => {
    const url = typeof input === "string" ? input : input.url;
    const req = { url, method: String(init.method || "GET").toUpperCase(), headers: new Headers(init.headers || {}), body: init.body };
    calls.push(req);
    if (init.signal && init.signal.aborted) throw Object.assign(new Error("aborted"), { name: "AbortError" });
    return handler(req, new URL(url));
  };
  return calls;
}

export function pending() {
  const list = [];
  return { ctx: { waitUntil(p) { list.push(Promise.resolve(p).catch(() => {})); }, passThroughOnException() {} }, flush: () => Promise.allSettled(list) };
}

export async function call(worker, env, method, path, body, opts = {}) {
  const p = pending();
  const headers = { "content-type": "application/json" };
  if (opts.auth !== false) headers.authorization = "Bearer " + (opts.token || TOKEN);
  const res = await worker.fetch(new Request("https://worker.test" + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) }), env, p.ctx);
  await p.flush();
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = null; }
  return { status: res.status, json, data: json && json.data !== undefined ? json.data : json, text };
}

// ---------------------------------------------------------------------------------------------------- fixtures
const day = 86400000;
export const iso = (offsetDays) => new Date(Date.now() + offsetDays * day).toISOString().slice(0, 10);

export const GENRES_AR = { movie: [{ id: 28, name: "أكشن" }, { id: 53, name: "إثارة" }, { id: 35, name: "كوميديا" }], tv: [{ id: 18, name: "دراما" }, { id: 9648, name: "غموض" }] };
const GENRES_EN = { 28: "Action", 53: "Thriller", 35: "Comedy", 18: "Drama", 9648: "Mystery" };

export function movieRow(id, over = {}) {
  return Object.assign({ id, title: "فيلم " + id, original_title: "Movie " + id, overview: "ملخص الفيلم " + id, release_date: iso(-10), poster_path: "/p" + id + ".jpg", backdrop_path: "/b" + id + ".jpg", vote_average: 7.84, vote_count: 120, popularity: 88.3, genre_ids: [28, 53], original_language: "en" }, over);
}
export function tvRow(id, over = {}) {
  return Object.assign({ id, name: "مسلسل " + id, original_name: "Show " + id, overview: "ملخص المسلسل " + id, first_air_date: iso(-400), poster_path: "/t" + id + ".jpg", backdrop_path: "/tb" + id + ".jpg", vote_average: 8.1, vote_count: 300, popularity: 150.2, genre_ids: [18], original_language: "en", origin_country: ["US"] }, over);
}

export function createWorld(overrides = {}) {
  const state = {
    movies: { 1001: movieRow(1001), 1002: movieRow(1002, { release_date: iso(-3), genre_ids: [35] }), 1003: movieRow(1003, { release_date: iso(20) }), 1004: movieRow(1004), 1005: movieRow(1005) },
    tv: { 2001: tvRow(2001), 2002: tvRow(2002, { first_air_date: iso(-20) }) },
    discoverMovie: [1001, 1002], discoverTv: [2001, 2002], trendingMovie: [1002, 1001],
    tmdbDown: false, tmdb429Once: false, tmdbBadKey: false,
    vidbolt: (u) => ({ status: 200 }),   // overridable per test
    subdl: { 1001: true },               // imdb ids with an Arabic subtitle
    requests: [],                        // every TMDB request seen
  };
  Object.assign(state, overrides);

  const detailMovie = (id, lang) => {
    const m = state.movies[id];
    if (!m) return null;
    const names = lang === "ar-SA" ? Object.fromEntries(GENRES_AR.movie.map((g) => [g.id, g.name])) : GENRES_EN;
    return { id, title: lang === "ar-SA" ? m.title : m.original_title, original_title: m.original_title, overview: lang === "ar-SA" ? m.overview : "Overview " + id, release_date: m.release_date, poster_path: m.poster_path, backdrop_path: m.backdrop_path, vote_average: m.vote_average, vote_count: m.vote_count, popularity: m.popularity, genres: m.genre_ids.map((g) => ({ id: g, name: names[g] })), runtime: 121, original_language: m.original_language, production_countries: [{ iso_3166_1: "US" }], status: "Released", tagline: "", homepage: "", external_ids: { imdb_id: "tt" + String(id).padStart(7, "0") } };
  };
  const detailTv = (id, lang) => {
    const t = state.tv[id];
    if (!t) return null;
    return { id, name: lang === "ar-SA" ? t.name : t.original_name, original_name: t.original_name, overview: lang === "ar-SA" ? t.overview : "Overview " + id, first_air_date: t.first_air_date, poster_path: t.poster_path, backdrop_path: t.backdrop_path, vote_average: t.vote_average, vote_count: t.vote_count, popularity: t.popularity, genres: [{ id: 18, name: lang === "ar-SA" ? "دراما" : "Drama" }], episode_run_time: [45], original_language: "en", origin_country: ["US"], status: "Returning Series", number_of_seasons: 2, number_of_episodes: 5, seasons: [{ season_number: 0, name: "Specials", episode_count: 1, poster_path: null, air_date: iso(-500) }, { season_number: 1, name: "الموسم 1", episode_count: 3, poster_path: "/s1.jpg", air_date: iso(-400) }, { season_number: 2, name: "الموسم 2", episode_count: 2, poster_path: "/s2.jpg", air_date: iso(-5) }], last_episode_to_air: { season_number: 2, episode_number: 1, air_date: iso(-5), name: "البداية" }, next_episode_to_air: { season_number: 2, episode_number: 2, air_date: iso(2), name: "التالية" }, external_ids: { imdb_id: "tt" + String(id + 9000000).padStart(7, "0") } };
  };
  const season = (id, n, lang) => {
    if (!state.tv[id] || ![1, 2].includes(n)) return null;
    const names = lang === "ar-SA" ? ["الحلقة الأولى", "الحلقة الثانية", "الحلقة الثالثة"] : ["Episode One", "Episode Two", "Episode Three"];
    const eps = n === 1
      ? [1, 2, 3].map((e) => ({ episode_number: e, name: names[e - 1], overview: "وصف " + e, air_date: iso(-400 + (e - 1) * 7), runtime: 45, still_path: "/still" + e + ".jpg" }))
      : [{ episode_number: 1, name: "البداية", overview: "وصف", air_date: iso(-5), runtime: 45, still_path: "/st21.jpg" }, { episode_number: 2, name: "التالية", overview: "وصف", air_date: iso(2), runtime: 45, still_path: null }];
    return { name: "الموسم " + n, poster_path: "/s" + n + ".jpg", air_date: eps[0].air_date, episodes: eps };
  };

  const jsonRes = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), { status, headers: Object.assign({ "content-type": "application/json" }, headers) });
  const html = (title = "Player") => new Response("<!doctype html><html><head><title>" + title + "</title></head><body><div id=\"app\"></div><iframe src=\"/embed\"></iframe>" + " ".repeat(300) + "</body></html>", { status: 200, headers: { "content-type": "text/html; charset=utf-8" } });

  const handler = async (req, u) => {
    const host = u.hostname;
    if (host === "api.themoviedb.org") {
      state.requests.push(u.pathname + u.search);
      if (state.tmdbDown) throw new TypeError("network down");
      if (state.tmdbBadKey) return jsonRes({ status_message: "Invalid API key" }, 401);
      if (state.tmdb429Once) { state.tmdb429Once = false; return jsonRes({ status_message: "slow down" }, 429, { "retry-after": "1" }); }
      const lang = u.searchParams.get("language") || "en-US";
      const path = u.pathname.replace(/^\/3/, "");
      let m;
      if (path === "/genre/movie/list") return jsonRes({ genres: GENRES_AR.movie });
      if (path === "/genre/tv/list") return jsonRes({ genres: GENRES_AR.tv });
      if (path === "/discover/movie") return jsonRes({ page: Number(u.searchParams.get("page") || 1), total_pages: 3, total_results: 60, results: state.discoverMovie.map((id) => state.movies[id]) });
      if (path === "/discover/tv") return jsonRes({ page: Number(u.searchParams.get("page") || 1), total_pages: 2, total_results: 40, results: state.discoverTv.map((id) => state.tv[id]) });
      if (path === "/trending/movie/week") return jsonRes({ page: 1, total_pages: 1, total_results: 2, results: state.trendingMovie.map((id) => state.movies[id]) });
      if (path === "/trending/tv/week") return jsonRes({ page: 1, total_pages: 1, total_results: 2, results: state.discoverTv.map((id) => state.tv[id]) });
      if ((m = path.match(/^\/movie\/(\d+)$/))) { const d = detailMovie(Number(m[1]), lang); return d ? jsonRes(d) : jsonRes({ status_message: "not found" }, 404); }
      if ((m = path.match(/^\/tv\/(\d+)$/))) { const d = detailTv(Number(m[1]), lang); return d ? jsonRes(d) : jsonRes({ status_message: "not found" }, 404); }
      if ((m = path.match(/^\/tv\/(\d+)\/season\/(\d+)$/))) { const d = season(Number(m[1]), Number(m[2]), lang); return d ? jsonRes(d) : jsonRes({ status_message: "not found" }, 404); }
      return jsonRes({ status_message: "unhandled " + path }, 404);
    }
    if (host === "image.tmdb.org") return new Response(new Uint8Array(1024), { status: 200, headers: { "content-type": "image/jpeg" } });
    if (host === "vidbolt.pro") {
      const spec = state.vidbolt(u);
      if (spec.throw) throw spec.throw;
      if (spec.status === 200) return html(spec.title);
      return new Response("nope", { status: spec.status, headers: { "content-type": "text/html" } });
    }
    if (host === "api.subdl.com") {
      const imdb = u.searchParams.get("imdb_id") || "";
      const id = Number(imdb.replace(/^tt0*/, ""));
      if (state.subdlError) return jsonRes({ status: false, error: "quota" }, 500);
      const lang = u.searchParams.get("languages");
      const has = (state.subdl[id] || (id >= 9000000 && state.subdl[id - 9000000])) && (lang === "AR" || (lang === "EN" && state.subdlEn));
      return jsonRes({ status: true, subtitles: has ? [{ url: "/subtitle/" + id + "-" + lang + ".zip", language: lang, release_name: "Release.1080p.WEB", name: "Release.1080p.WEB", author: "tester", season: Number(u.searchParams.get("season_number") || 0), episode: Number(u.searchParams.get("episode_number") || 0) }] : [] });
    }
    if (host === "dl.subdl.com") {
      const arabic = /-AR\.zip$/.test(u.pathname);
      return new Response(zipOf("sub.srt", arabic ? arabicSrt(150) : englishSrt(150)), { status: 200, headers: { "content-type": "application/zip" } });
    }
    if (host === "cdn.example.com") return state.cdn ? state.cdn(u) : new Response("nope", { status: 404 });
    return new Response("unhandled host " + host, { status: 599 });
  };
  return { state, handler, install: () => installFetch(handler), html, jsonRes };
}

// ---------------------------------------------------------------------------------------------------- subtitle builders
export function srtOf(lines) {
  return lines.map((text, i) => {
    const s = i * 4000 + 1000;
    const e = s + 2500;
    const f = (ms) => new Date(ms).toISOString().slice(11, 23).replace(".", ",");
    return (i + 1) + "\n" + f(s) + " --> " + f(e) + "\n" + text;
  }).join("\n\n") + "\n";
}
export const arabicSrt = (n) => srtOf(Array.from({ length: n }, (_, i) => "مرحبا بك في الحلقة رقم " + (i + 1) + " من القصة"));
export const englishSrt = (n) => srtOf(Array.from({ length: n }, (_, i) => "Hello and welcome to line number " + (i + 1) + " of the story"));

/** Minimal STORED zip with one file (enough for the Worker's own unzipper). */
export function zipOf(name, text) {
  const data = new TextEncoder().encode(text);
  const nameB = new TextEncoder().encode(name);
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  let crc = 0xffffffff;
  for (const b of data) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
  crc = (crc ^ 0xffffffff) >>> 0;
  const u16 = (n) => [n & 255, (n >> 8) & 255];
  const u32 = (n) => [n & 255, (n >> 8) & 255, (n >> 16) & 255, (n >> 24) & 255];
  const local = [0x50, 0x4b, 3, 4, ...u16(20), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(crc), ...u32(data.length), ...u32(data.length), ...u16(nameB.length), ...u16(0), ...nameB];
  const central = [0x50, 0x4b, 1, 2, ...u16(20), ...u16(20), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(crc), ...u32(data.length), ...u32(data.length), ...u16(nameB.length), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(0), ...nameB];
  const eocd = [0x50, 0x4b, 5, 6, ...u16(0), ...u16(0), ...u16(1), ...u16(1), ...u32(central.length), ...u32(local.length + data.length), ...u16(0)];
  const out = new Uint8Array(local.length + data.length + central.length + eocd.length);
  out.set(local, 0); out.set(data, local.length); out.set(central, local.length + data.length); out.set(eocd, local.length + data.length + central.length);
  return out;
}

// 18.2 — local test of the PATCHED cigram-admin-api worker (Node 22). No network: R2 is in memory and TMDB / SubDL
// answers are stubs that only record which official endpoint and parameters the Worker asked for.
// Usage: node admin_subhub_test.mjs <path to worker.patched.mjs>
import assert from "node:assert/strict";

const calls = [];
globalThis.fetch = async (input, init) => {
  const u = new URL(typeof input === "string" ? input : input.url);
  calls.push(u);
  const j = (o) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } });
  if (u.hostname === "api.themoviedb.org") {
    if (u.pathname.includes("/genre/")) return j({ genres: [{ id: 18, name: "دراما" }] });
    const tv = u.pathname.includes("/tv");
    return j({ page: 1, total_pages: 1, total_results: 1, results: [tv
      ? { id: 1396, name: "Breaking Bad", original_name: "Breaking Bad", first_air_date: "2008-01-20", poster_path: "/p.jpg", vote_average: 8.9, vote_count: 9000, popularity: 300, genre_ids: [18], original_language: "en", origin_country: ["US"] }
      : { id: 550, title: "Fight Club", original_title: "Fight Club", release_date: "1999-10-15", poster_path: "/f.jpg", vote_average: 8.4, vote_count: 20000, popularity: 100, genre_ids: [18], original_language: "en" }] });
  }
  if (u.hostname === "api.subdl.com") return j({ status: true, subtitles: [] });
  if (u.hostname.endsWith("opensubtitles.com") || u.hostname.endsWith("opensubtitles.org")) return osStub(u, init, j);
  return new Response("not stubbed", { status: 599 });
};

// OpenSubtitles stub: shapes copied from the official reference (https://ai.opensubtitles.com/api-docs).
// It enforces what the reference demands (Api-Key, "<app> v<version>" User-Agent, both headers on /download) so a
// Worker that forgets one fails here. It is a CONTRACT stub, not proof that the live service accepts the account.
const os = { logins: 0, loginMode: "ok", searchMode: "ok", downloadMode: "ok", translateStatus: ["PENDING", "COMPLETED"], resultSrt: "", resultQuality: { valid: true }, translateForm: null, headers: [] };
const OS_SRT_AR = "1\n00:00:10,000 --> 00:00:12,000\nمرحبا بالعالم\n\n2\n00:00:13,000 --> 00:00:15,000\nهذه ترجمة عربية كاملة\n\n3\n00:00:16,000 --> 00:00:18,000\nإلى اللقاء يا صديقي\n\n4\n00:00:19,000 --> 00:00:21,000\nشكراً جزيلاً لكم\n\n5\n00:00:22,000 --> 00:00:24,000\nنراكم في الحلقة القادمة\n";
async function osStub(u, init, j) {
  const h = new Headers(init && init.headers);
  os.headers.push({ path: u.pathname, host: u.hostname, key: h.get("api-key"), ua: h.get("user-agent"), auth: h.get("authorization") });
  if (!h.get("user-agent") || !/^\S+ v\d/.test(h.get("user-agent"))) return new Response(JSON.stringify({ error: "Blocked (kong-user-agent-block)" }), { status: 403 });
  if (u.pathname === "/api/v1/ai/info/translation") return j({ data: [
    { name: "aws", display_name: "Amazon Translate", price: 0.0054, pricing: "Pay-per-character", languages_supported: [{ language_code: "ar", language_name: "arabic" }, { language_code: "de", language_name: "german" }] },
    { name: "deepl2", display_name: "DeepL2", price: 0.0009, pricing: "Pay-per-character", languages_supported: [{ language_code: "de", language_name: "german" }] },
  ] });
  // the temporary download link is pre-signed: it needs no application key
  if (u.pathname === "/download/ABC/x.srt") return new Response(new TextEncoder().encode(OS_SRT_AR), { status: 200 });
  if (!h.get("api-key") || h.get("api-key") === "invalid") return new Response(JSON.stringify({ message: "You cannot consume this service" }), { status: 403 });
  if (u.pathname === "/api/v1/login") {
    os.logins++;
    if (os.loginMode === "bad") return new Response(JSON.stringify({ message: "Unauthorized" }), { status: 401 });
    return j({ user: { allowed_translations: 5, allowed_downloads: 100, level: "Sub leech", user_id: 1, ext_installed: false, vip: false }, base_url: "api.opensubtitles.com", token: "jwt-token", status: 200 });
  }
  if (u.pathname === "/api/v1/subtitles") {
    if (os.searchMode === "429") return new Response("{}", { status: 429, headers: { "retry-after": "2" } });
    const wantsTmdb = u.searchParams.get("tmdb_id") || u.searchParams.get("parent_tmdb_id");
    if (os.searchMode === "imdb_only" && wantsTmdb) return j({ total_pages: 1, total_count: 0, per_page: 60, page: 1, data: [] });
    return j({ total_pages: 1, total_count: 2, per_page: 60, page: 1, data: [
      { id: "1", type: "subtitle", attributes: { subtitle_id: "1", language: "ar", download_count: 500, hearing_impaired: false, fps: 23.976, ratings: 8, from_trusted: true, ai_translated: false, machine_translated: false, release: "Fight.Club.1999.1080p.BluRay.x264", uploader: { uploader_id: 1, name: "u1", rank: "Trusted member" }, feature_details: { feature_id: 9, feature_type: "Movie", year: 1999, title: "Fight Club", imdb_id: 137523, tmdb_id: 550 }, files: [{ file_id: 7001, cd_number: 1, file_name: "Fight.Club.1999.1080p.BluRay.x264" }] } },
      { id: "2", type: "subtitle", attributes: { subtitle_id: "2", language: "ar", download_count: 10, hearing_impaired: false, fps: 23.976, ratings: 0, from_trusted: false, ai_translated: true, machine_translated: false, release: "Fight.Club.1999.WEBRip", uploader: { uploader_id: 2, name: "u2", rank: "Member" }, feature_details: { feature_id: 9, feature_type: "Movie", year: 1999, title: "Fight Club", imdb_id: 137523, tmdb_id: 550 }, files: [{ file_id: 7002, cd_number: 1, file_name: "Fight.Club.1999.WEBRip" }] } },
    ] });
  }
  if (u.pathname === "/api/v1/download") {
    if (!h.get("authorization")) return new Response(JSON.stringify({ message: "Authorization required" }), { status: 401 });
    if (os.downloadMode === "quota") return new Response(JSON.stringify({ requests: 100, remaining: 0, message: "You have downloaded your allowed 100 subtitles for 24h. Your quota will be renewed in 07 hours and 40 minutes (2026-11-09 13:03:15 UTC)", reset_time: "07 hours and 40 minutes", reset_time_utc: "2026-11-09T13:03:15.000Z" }), { status: 406 });
    const link = os.downloadMode === "evil" ? "https://evil.example.invalid/x.srt" : "https://www.opensubtitles.com/download/ABC/x.srt";
    return j({ link, file_name: "x.srt", requests: 1, remaining: 99, message: "", reset_time: "", reset_time_utc: "" });
  }
  if (u.pathname === "/api/v1/ai/translate" && (init && init.method) === "POST") {
    if (!h.get("authorization")) return new Response(JSON.stringify({ message: "Authorization required" }), { status: 401 });
    os.translateForm = {};
    for (const [k, v] of init.body.entries()) os.translateForm[k] = typeof v === "string" ? v : "[file " + v.name + " " + v.size + "]";
    os.translateFile = await init.body.get("file").text();
    return j({ status: "CREATED", correlation_id: "67eda18f52e11" });
  }
  if (u.pathname === "/api/v1/ai/translate/67eda18f52e11") {
    const st = os.translateStatus.length > 1 ? os.translateStatus.shift() : os.translateStatus[0];
    if (st !== "COMPLETED") return j({ correlation_id: "67eda18f52e11", status: st });
    return j({ correlation_id: "67eda18f52e11", status: "COMPLETED", data: { file_name: "source.ar.srt", url: "https://api.opensubtitles.com/api/v1/ai/files/45536/source.ar.srt", characters_count: 1616, unit_price: 0.0054, total_price: 9, credits_left: 600, quality: os.resultQuality, readability: { advisory: true } } });
  }
  if (u.pathname === "/api/v1/ai/files/45536/source.ar.srt") return new Response(new TextEncoder().encode(os.resultSrt), { status: 200 });
  return new Response("{}", { status: 404 });
}

class MemR2 {
  constructor() { this.m = new Map(); this.n = 0; }
  async get(k, opts = {}) {
    if (!this.m.has(k)) return null;
    const { text, etag } = this.m.get(k);
    const obj = { etag, httpEtag: '"' + etag + '"', text: async () => text, json: async () => JSON.parse(text), arrayBuffer: async () => new TextEncoder().encode(text).buffer };
    // R2 evaluates conditional headers itself: a matching If-None-Match gives an R2Object WITHOUT body
    const inm = opts && opts.onlyIf && opts.onlyIf.get ? opts.onlyIf.get("if-none-match") : null;
    if (inm && inm === obj.httpEtag) return obj;
    obj.body = text;
    return obj;
  }
  async head(k) { return this.m.has(k) ? { etag: this.m.get(k).etag } : null; }
  async put(k, v, o) {
    const cur = this.m.get(k);
    if (o && o.onlyIf && o.onlyIf.etagMatches && (!cur || cur.etag !== o.onlyIf.etagMatches)) return null;
    const etag = "e" + (++this.n);
    this.m.set(k, { text: typeof v === "string" ? v : new TextDecoder().decode(v), etag });
    return { etag, key: k };
  }
  async delete(k) { this.m.delete(k); }
  async list(o = {}) { return { objects: [...this.m.keys()].filter((k) => !o.prefix || k.startsWith(o.prefix)).map((key) => ({ key })), truncated: false }; }
}

const mod = await import(process.argv[2]);
const worker = mod.default;
const bucket = new MemR2();
await bucket.put("movies.json", JSON.stringify([
  { id: "m1", type: "movie", title: "فيلم بلا معرّف", video: "https://v.example.invalid/a.mp4" },
  { id: "m2", type: "movie", title: "Fight Club", tmdb: "550", imdb_id: "tt0137523", video: "https://v.example.invalid/b.mp4" },
  { id: "m3", type: "movie", title: "Has Subtitle", tmdb: "551", imdb_id: "tt0000551", video: "https://v.example.invalid/c.mp4", subtitle: "https://pub.example.invalid/c/c.ar.srt" },
]));
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: "admin-secret", TMDB_API_KEY: "tmdb-test", SUBDL_API_KEY: "subdl-test", OS_RATE_LIMIT_10S: "100000" };
const call = async (method, path, body, token = env.ADMIN_TOKEN) => {
  const r = await worker.fetch(new Request("https://admin.test" + path, { method, headers: Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {}), body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  const reply = await r.json();
  // the admin app reads the "data" object of a reply (CxApi): do the same
  return { status: r.status, json: reply && reply.data && typeof reply.data === "object" ? Object.assign({}, reply, reply.data) : reply };
};
const logLines = [];
const realLog = console.log;
console.log = (...a) => { if (typeof a[0] === "string" && a[0].startsWith('{"evt":"subhub"')) logLines.push(a[0]); else realLog(...a); };
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

await test("GET /movies is conditional: ETag, 304 for an unchanged catalogue, full body after a change", async () => {
  const get = (h = {}) => worker.fetch(new Request("https://admin.test/movies", { headers: h }), env, { waitUntil() {} });
  const first = await get();
  assert.equal(first.status, 200);
  const etag = first.headers.get("etag");
  assert.ok(/^"e\d+"$/.test(etag), etag);
  assert.equal(first.headers.get("cache-control"), "no-cache");
  assert.equal((await first.json()).length, 3, "the full catalogue is unchanged");
  const second = await get({ "if-none-match": etag });
  assert.equal(second.status, 304);
  assert.equal(await second.text(), "");
  await bucket.put("movies.json", JSON.stringify([{ id: "n1", type: "movie", title: "New", video: "https://v.example.invalid/n.mp4" }]));
  const third = await get({ "if-none-match": etag });
  assert.equal(third.status, 200);
  assert.equal((await third.json())[0].id, "n1");
  // restore the fixture for the other tests
  await bucket.put("movies.json", JSON.stringify([
    { id: "m1", type: "movie", title: "فيلم بلا معرّف", video: "https://v.example.invalid/a.mp4" },
    { id: "m2", type: "movie", title: "Fight Club", tmdb: "550", imdb_id: "tt0137523", video: "https://v.example.invalid/b.mp4" },
    { id: "m3", type: "movie", title: "Has Subtitle", tmdb: "551", imdb_id: "tt0000551", video: "https://v.example.invalid/c.mp4", subtitle: "https://pub.example.invalid/c/c.ar.srt" },
  ]));
});

await test("health lists the new routes", async () => {
  const r = await call("GET", "/health", null, null);
  for (const k of ["POST /admin/discover/decision", "GET /admin/discover/decisions", "POST /admin/subhub/translate", "POST /admin/subhub/translate/batch", "GET /admin/subhub/providers"]) assert.ok(r.json.routes.includes(k), k);
});

for (const [feed, paths] of [
  ["all:trending_day", ["/3/trending/movie/day", "/3/trending/tv/day"]],
  ["movie:top_rated", ["/3/movie/top_rated"]],
  ["tv:top_rated", ["/3/tv/top_rated"]],
  ["tv:on_the_air", ["/3/tv/on_the_air"]],
]) {
  await test("discovery feed " + feed + " uses TMDB " + paths.join(" + "), async () => {
    calls.length = 0;
    const r = await call("POST", "/admin/discover/feed", { feed, page: 1 });
    assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 300));
    const asked = calls.filter((u) => u.hostname === "api.themoviedb.org").map((u) => u.pathname);
    for (const p of paths) assert.ok(asked.includes(p), "asked " + asked.join(","));
    assert.ok(r.json.cards.length >= 1);
    assert.equal(typeof r.json.cards[0].imported, "boolean");
  });
}

await test("existing catalogue entries are recognised (duplicate prevention by TMDB id)", async () => {
  const r = await call("POST", "/admin/discover/feed", { feed: "movie:top_rated", page: 1 });
  const fc = r.json.cards.find((c) => c.tmdb_id === 550);
  assert.equal(fc.imported, true);
});

await test("unknown feeds are still refused (no invented list)", async () => {
  const r = await call("POST", "/admin/discover/feed", { feed: "movie:made_up" });
  assert.equal(r.status, 400);
});

await test("management decisions are stored and listed", async () => {
  assert.equal((await call("POST", "/admin/discover/decision", { key: "movie:550", decision: "approved", note: "ok" })).status, 200);
  assert.equal((await call("POST", "/admin/discover/decision", { key: "movie:550", decision: "publish_now" })).status, 400);
  assert.equal((await call("POST", "/admin/discover/decision", { key: "../../x", decision: "approved" })).status, 400);
  const g = await call("GET", "/admin/discover/decisions");
  assert.equal(g.json.items["movie:550"].decision, "approved");
  assert.equal((await call("POST", "/admin/discover/decision", { key: "movie:550", decision: "" })).status, 200);
  assert.equal((await call("GET", "/admin/discover/decisions")).json.items["movie:550"], undefined);
});

await test("decisions need the admin token", async () => {
  assert.equal((await call("POST", "/admin/discover/decision", { key: "movie:1", decision: "review" }, "wrong")).status, 401);
});

await test("a library title without TMDB/IMDb ids is never searched by name", async () => {
  calls.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m1", all: true });
  assert.equal(r.status, 400);
  assert.equal(r.json.error, "subs_ids_required");
  assert.equal(calls.some((u) => u.pathname.includes("/search/")), false);
});

await test("subtitle search asks SubDL by TMDB id first", async () => {
  calls.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m2", all: true });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 200));
  const sd = calls.find((u) => u.hostname === "api.subdl.com");
  assert.equal(sd.searchParams.get("tmdb_id"), "550");
  assert.equal(sd.searchParams.get("imdb_id"), null);
  assert.equal(sd.searchParams.get("languages"), "AR");
});


// ------------------------------------------------------------------ providers: state, health, verification
await test("providers: not configured -> configured (unverified) -> connected only after a real request", async () => {
  let r = await call("GET", "/admin/subhub/providers");
  const by = (x) => Object.fromEntries(x.json.providers.map((p) => [p.id, p]));
  assert.equal(by(r).opensubtitles.state, "not_configured");
  assert.equal(by(r).opensubtitles_ai.state, "not_configured");
  assert.equal(by(r).subdl.state, "connected");                 // the SubDL search of the previous test really succeeded
  assert.ok(Object.values(by(r)).every((p) => p.implemented === true));
  env.OPENSUBTITLES_API_KEY = "os-key"; env.OPENSUBTITLES_USERNAME = "user"; env.OPENSUBTITLES_PASSWORD = "pass";
  r = await call("GET", "/admin/subhub/providers");
  assert.equal(by(r).opensubtitles.state, "configured_unverified");
  const v = await call("POST", "/admin/subhub/providers/verify", { provider: "opensubtitles" });
  assert.equal(v.status, 200, JSON.stringify(v.json).slice(0, 200));
  assert.equal(v.json.ok, true);
  r = await call("GET", "/admin/subhub/providers");
  assert.equal(by(r).opensubtitles.state, "connected");
  assert.ok(by(r).opensubtitles.verified_at);
  const vs = await call("POST", "/admin/subhub/providers/verify", { provider: "subdl" });
  assert.equal(vs.json.ok, true);
  assert.equal((await call("POST", "/admin/subhub/providers/verify", { provider: "nope" })).status, 400);
});

await test("the providers reply warns when admin-private data shares the public bucket", async () => {
  const r = await call("GET", "/admin/subhub/providers");
  assert.equal(r.json.storage.admin_private_bucket, false);
  assert.match(r.json.storage.warning, /ADMIN_BUCKET/);
  env.ADMIN_BUCKET = bucket;
  assert.equal((await call("GET", "/admin/subhub/providers")).json.storage.warning, "");
  delete env.ADMIN_BUCKET;
});

await test("OpenSubtitles requests follow the reference: Api-Key, 'app vX' User-Agent, sorted lowercase params, no leading zeros, no ordering", async () => {
  os.headers.length = 0; calls.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m2", all: true });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 200));
  const s = calls.find((u) => u.hostname === "api.opensubtitles.com" && u.pathname === "/api/v1/subtitles");
  assert.ok(s, "asked /subtitles");
  assert.equal(s.search, "?languages=ar&tmdb_id=550&type=movie");       // TMDB first, sorted, lowercase, nothing else
  assert.equal(s.searchParams.get("order_by"), null);
  const h = os.headers.find((x) => x.path === "/api/v1/subtitles");
  assert.equal(h.key, "os-key");
  assert.equal(h.ua, "CigramAdmin v18.2");
  assert.ok(r.json.results.some((c) => c.source === "opensubtitles" && c.ai_translated === true), "AI-translated uploads are flagged");
  const human = r.json.results.find((c) => c.id === "7001"), ai = r.json.results.find((c) => c.id === "7002");
  assert.ok(human.score > ai.score, "human subtitle outranks the AI-translated one");
});

await test("a TMDB miss falls back to the IMDb id without leading zeros", async () => {
  os.searchMode = "imdb_only"; calls.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m2", all: true });
  os.searchMode = "ok";
  const qs = calls.filter((u) => u.hostname === "api.opensubtitles.com" && u.pathname === "/api/v1/subtitles").map((u) => u.search);
  assert.deepEqual(qs, ["?languages=ar&tmdb_id=550&type=movie", "?imdb_id=137523&languages=ar&type=movie"]);
  assert.ok(r.json.results.some((c) => c.source === "opensubtitles"));
});

await test("a 429 from OpenSubtitles is reported per provider, not as a crash", async () => {
  os.searchMode = "429";
  const r = await call("POST", "/admin/subs/search", { item_id: "m2", all: true });
  os.searchMode = "ok";
  assert.equal(r.status, 200);
  assert.match(r.json.status.opensubtitles, /^error/);
});

await test("the local throttle keeps OpenSubtitles traffic under the documented 40 requests / 10 s", async () => {
  env.OS_RATE_LIMIT_10S = "2";
  await call("POST", "/admin/subhub/providers/verify", { provider: "opensubtitles" });   // starts a fresh window (counts 1..n)
  let limited = false;
  for (let i = 0; i < 6 && !limited; i++) {
    const r = await call("POST", "/admin/subs/search", { item_id: "m2", all: true });
    limited = /^error/.test(r.json.status.opensubtitles || "");
  }
  env.OS_RATE_LIMIT_10S = "100000";
  assert.ok(limited, "a burst is stopped locally instead of hitting the provider");
  await new Promise((r) => setTimeout(r, 10100));      // the window passes; the following tests run normally
});

await test("download needs Api-Key AND Bearer, uses the temporary link, UTF-8 Arabic SRT is validated", async () => {
  os.headers.length = 0;
  const r = await call("POST", "/admin/subs/fetch", { item_id: "m2", mode: "text", pick: { source: "opensubtitles", id: "7001", name: "x", release: "x" } });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 300));
  assert.equal(r.json.source, "opensubtitles");
  assert.equal(r.json.cues, 5);
  const d = os.headers.find((x) => x.path === "/api/v1/download");
  assert.equal(d.key, "os-key"); assert.equal(d.auth, "Bearer jwt-token");
});

await test("quota exhaustion is an explicit error with the reset time, and a foreign download host is refused", async () => {
  os.downloadMode = "quota";
  let r = await call("POST", "/admin/subs/fetch", { item_id: "m2", mode: "text", pick: { source: "opensubtitles", id: "7001", name: "x", release: "x" }, all: false });
  assert.notEqual(r.status, 200);
  assert.match(JSON.stringify(r.json), /quota|حصة/);
  os.downloadMode = "evil";
  r = await call("POST", "/admin/subs/fetch", { item_id: "m2", mode: "text", pick: { source: "opensubtitles", id: "7001", name: "x", release: "x" } });
  os.downloadMode = "ok";
  assert.notEqual(r.status, 200);
  assert.equal(calls.some((u) => u.hostname === "evil.example.invalid"), false, "never fetches an arbitrary host");
});

await test("rejected credentials are not re-sent (the reference forbids it)", async () => {
  // fresh worker module state: use a different password fingerprint so the cached good session is not reused
  env.OPENSUBTITLES_PASSWORD = "wrong"; os.loginMode = "bad"; os.logins = 0;
  const a = await call("POST", "/admin/subhub/providers/verify", { provider: "opensubtitles_ai" });
  const b = await call("POST", "/admin/subhub/providers/verify", { provider: "opensubtitles_ai" });
  assert.equal(a.json.ok, false); assert.equal(b.json.ok, false);
  assert.equal(os.logins, 1, "login is attempted once, then blocked for the same credentials");
  env.OPENSUBTITLES_PASSWORD = "pass"; os.loginMode = "ok";
});

// ------------------------------------------------------------------ translation
const srcSrt = "1\n00:00:10,000 --> 00:00:12,000\nHello world\n\n2\n00:00:13,000 --> 00:00:15,000\nThis is a full translation\n\n3\n00:00:16,000 --> 00:00:18,000\nGoodbye my friend\n\n4\n00:00:19,000 --> 00:00:21,000\nThank you all very much\n\n5\n00:00:22,000 --> 00:00:24,000\nSee you in the next episode\n";
const approval = (max) => ({ approved_by: "admin", approved_at: "2026-11-08T10:00:00Z", max_credits: max });

await test("translation options come from the provider's own catalogue and only list Arabic-capable APIs as usable", async () => {
  const r = await call("GET", "/admin/subhub/translate/options");
  assert.equal(r.status, 200);
  assert.deepEqual(r.json.apis.map((a) => [a.name, a.supports_ar]), [["aws", true], ["deepl2", false]]);
  const e = await call("POST", "/admin/subhub/translate/estimate", { source_file: srcSrt, api: "aws" });
  assert.equal(e.json.estimate.credits_max, Math.ceil(srcSrt.length * 0.0054));
  assert.equal((await call("POST", "/admin/subhub/translate/estimate", { source_file: srcSrt, api: "deepl2" })).status, 400);
});

await test("a translation is refused without management approval, with a too-low limit, or for a foreign target; nothing is sent", async () => {
  os.translateForm = null;
  const base = { content_id: "m2", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt };
  assert.equal((await call("POST", "/admin/subhub/translate", base)).json.error, "approval_required");
  assert.equal((await call("POST", "/admin/subhub/translate", Object.assign({}, base, { approval: approval(1) }))).json.error, "approval_too_low");
  assert.equal((await call("POST", "/admin/subhub/translate", Object.assign({}, base, { approval: approval(999), target: "fr" }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate", Object.assign({}, base, { approval: approval(999), content_id: "nope" }))).status, 404);
  assert.equal(os.translateForm, null, "no request reached the provider");
});

await test("approved translation: multipart request, status polling, timing restored from the source, result waits in QUALITY_REVIEW", async () => {
  os.translateForm = null;
  const sent = await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt, source_lang: "en", approval: approval(500) });
  assert.equal(sent.status, 200, JSON.stringify(sent.json).slice(0, 300));
  assert.equal(sent.json.correlation_id, "67eda18f52e11");
  assert.deepEqual([os.translateForm.api, os.translateForm.translate_from, os.translateForm.translate_to], ["aws", "en", "ar"]);
  assert.match(os.translateForm.file, /^\[file source\.srt \d+\]$/);
  assert.match(os.translateFile, /Hello world/);
  const hh = os.headers.filter((x) => x.path === "/api/v1/ai/translate").pop();
  assert.equal(hh.auth, "Bearer jwt-token");
  // translated file has the SAME 5 cues but every timestamp shifted by 40 ms: the Worker restores the source timing
  os.resultSrt = OS_SRT_AR.replace(/,000/g, ",040");
  os.translateStatus = ["PENDING", "COMPLETED"];
  const p1 = await call("GET", "/admin/subhub/translate/status?correlation_id=67eda18f52e11");
  assert.equal(p1.json.state, "TRANSLATING");
  const p2 = await call("GET", "/admin/subhub/translate/status?correlation_id=67eda18f52e11");
  assert.equal(p2.json.state, "QUALITY_REVIEW", JSON.stringify(p2.json).slice(0, 300));
  assert.equal(p2.json.report.cues, 5);
  assert.equal(p2.json.report.timing_restored, 5);
  assert.match(p2.json.srt, /00:00:10,000 --> 00:00:12,000/);
  assert.ok(!/,040/.test(p2.json.srt));
  const again = await call("GET", "/admin/subhub/translate/status?correlation_id=67eda18f52e11");
  assert.equal(again.json.state, "QUALITY_REVIEW");
  const list = JSON.parse((await bucket.get("movies.json")).__t || (await (await bucket.get("movies.json")).text()));
  assert.equal(list.find((x) => x.id === "m2").subtitle, undefined, "a translation never publishes by itself");
  const prov = (await call("GET", "/admin/subhub/providers")).json.providers.find((p) => p.id === "opensubtitles_ai");
  assert.equal(prov.state, "connected", "connected only after a translation really completed");
});

await test("a translation that changes the number of cues, is not Arabic, or fails the provider's own quality check is rejected", async () => {
  const run = async (resultSrt, quality, status = ["COMPLETED"]) => {
    // new job id: re-register by sending again (the stub always returns the same correlation id, so clear the stored job state first)
    for (const k of [...bucket.m.keys()]) if (k.includes("subhub/jobs/")) bucket.m.delete(k);
    os.resultSrt = resultSrt; os.resultQuality = quality; os.translateStatus = status;
    const sent = await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt, approval: approval(500), authorization: "" });
    assert.equal(sent.status, 200, JSON.stringify(sent.json).slice(0, 200));
    return call("GET", "/admin/subhub/translate/status?correlation_id=67eda18f52e11");
  };
  let r = await run(OS_SRT_AR.split("\n\n").slice(0, 4).join("\n\n"), { valid: true });
  assert.equal(r.json.state, "FAILED"); assert.equal(r.json.reason, "cue_count_differs");
  r = await run(srcSrt, { valid: true });
  assert.equal(r.json.reason, "not_arabic");
  r = await run(OS_SRT_AR, { valid: false });
  assert.equal(r.json.reason, "provider_quality_failed");
  os.resultQuality = { valid: true };
});

await test("a status request for a job this server never created is refused", async () => {
  assert.equal((await call("GET", "/admin/subhub/translate/status?correlation_id=abcdef123456")).status, 404);
  assert.equal((await call("GET", "/admin/subhub/translate/status?correlation_id=../../x")).status, 400);
});

await test("a batch is a spending envelope: approval, API, unique ids, budget above the estimate; every job is debited", async () => {
  const base = { ids: ["m2", "m3"], api: "aws", budget: 100, estimate: 20, approved_by: "admin", approved_at: "2026-11-08T10:00:00Z" };
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { approved_at: "" }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { budget: 1 }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { ids: ["m2", "m2"] }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { api: "deepl2" }))).status, 400);
  const ok = await call("POST", "/admin/subhub/translate/batch", base);
  assert.equal(ok.status, 200, JSON.stringify(ok.json).slice(0, 200));
  const bid = ok.json.batch_id;
  for (const k of [...bucket.m.keys()]) if (k.includes("subhub/jobs/")) bucket.m.delete(k);
  const one = await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt, batch_id: bid });
  assert.equal(one.status, 200, JSON.stringify(one.json).slice(0, 200));
  assert.equal((await call("POST", "/admin/subhub/translate", { content_id: "zz", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt, batch_id: bid })).status, 404);
  const big = "1\n00:00:01,000 --> 00:00:02,000\n" + "x".repeat(20000) + "\n";
  const over = await call("POST", "/admin/subhub/translate", { content_id: "m3", provider: "opensubtitles_ai", api: "aws", source_file: big, batch_id: bid });
  assert.equal(over.json.error, "batch_budget_exceeded");
});

// ------------------------------------------------------------------ approvals and the no-overwrite guard
await test("an existing subtitle is never replaced silently; an approved one needs the explicit authorization", async () => {
  const pick = { source: "opensubtitles", id: "7001", name: "x", release: "x" };
  // m3 already has a subtitle, not approved -> needs overwrite=true
  let r = await call("POST", "/admin/subs/fetch", { item_id: "m3", mode: "upload", pick });
  assert.equal(r.json.error, "subtitle_exists");
  // approve it -> overwrite=true is no longer enough
  assert.equal((await call("POST", "/admin/subhub/approval", { content_id: "m3", action: "approve", approved_by: "admin", url: "https://pub.example.invalid/c/c.ar.srt" })).status, 200);
  r = await call("POST", "/admin/subs/fetch", { item_id: "m3", mode: "upload", pick, overwrite: true });
  assert.equal(r.json.error, "approved_subtitle_protected");
  assert.equal((await call("GET", "/admin/subhub/approvals")).json.items.m3.status, "approved");
  // translation of an approved title is protected too
  const t = await call("POST", "/admin/subhub/translate", { content_id: "m3", provider: "opensubtitles_ai", api: "aws", source_file: srcSrt, approval: approval(500) });
  assert.equal(t.json.error, "approved_subtitle_protected");
  // explicit authorization lets it through
  r = await call("POST", "/admin/subs/fetch", { item_id: "m3", mode: "upload", pick, authorization: "OVERWRITE-APPROVED:m3" });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 300));
  // a title without any subtitle is stored normally
  r = await call("POST", "/admin/subs/fetch", { item_id: "m2", mode: "upload", pick });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 300));
  assert.ok(r.json.url);
  // the same object now exists -> a second automatic upload is refused
  r = await call("POST", "/admin/subs/fetch", { item_id: "m2", mode: "upload", pick });
  assert.equal(r.json.error, "subtitle_exists");
  assert.equal((await call("POST", "/admin/subhub/approval", { content_id: "m3", action: "revoke" })).status, 200);
  assert.equal((await call("GET", "/admin/subhub/approvals")).json.items.m3, undefined);
});

await test("every new route needs the admin token", async () => {
  for (const [m, p] of [["GET", "/admin/subhub/providers"], ["POST", "/admin/subhub/providers/verify"], ["GET", "/admin/subhub/translate/options"], ["POST", "/admin/subhub/translate/estimate"], ["POST", "/admin/subhub/translate"], ["GET", "/admin/subhub/translate/status?correlation_id=abcdef123456"], ["POST", "/admin/subhub/translate/batch"], ["POST", "/admin/subhub/approval"], ["GET", "/admin/subhub/approvals"]]) {
    assert.equal((await call(m, p, m === "POST" ? {} : null, "wrong")).status, 401, p);
    assert.equal((await call(m, p, m === "POST" ? {} : null, null)).status, 401, p);
  }
});

await test("no secret or token appears in any reply or log line", async () => {
  const all = JSON.stringify((await call("GET", "/admin/subhub/providers")).json) + JSON.stringify((await call("POST", "/admin/subhub/providers/verify", { provider: "opensubtitles" })).json);
  for (const secret of ["os-key", "jwt-token", "subdl-test", "tmdb-test", "pass"]) assert.ok(!all.includes(secret), "leaked " + secret);
});

await test("requests are logged as route + status + duration only (no body, no secret)", async () => {
  assert.ok(logLines.length > 10);
  for (const l of logLines) { const o = JSON.parse(l); assert.deepEqual(Object.keys(o).sort(), ["evt", "ms", "rid", "route", "status"]); }
  for (const secret of ["os-key", "jwt-token", "subdl-test", "Hello world"]) assert.ok(!logLines.join("").includes(secret));
});

console.log(`\n${passed} passed`);

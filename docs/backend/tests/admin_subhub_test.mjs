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
  return new Response("not stubbed", { status: 599 });
};

class MemR2 {
  constructor() { this.m = new Map(); this.n = 0; }
  async get(k) {
    if (!this.m.has(k)) return null;
    const { text, etag } = this.m.get(k);
    return { etag, httpEtag: etag, text: async () => text, json: async () => JSON.parse(text), arrayBuffer: async () => new TextEncoder().encode(text).buffer };
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
]));
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: "admin-secret", TMDB_API_KEY: "tmdb-test", SUBDL_API_KEY: "subdl-test" };
const call = async (method, path, body, token = env.ADMIN_TOKEN) => {
  const r = await worker.fetch(new Request("https://admin.test" + path, { method, headers: Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {}), body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  const reply = await r.json();
  // the admin app reads the "data" object of a reply (CxApi): do the same
  return { status: r.status, json: reply && reply.data && typeof reply.data === "object" ? Object.assign({}, reply, reply.data) : reply };
};
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

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

await test("translation providers are reported as not implemented", async () => {
  const r = await call("GET", "/admin/subhub/providers");
  assert.ok(r.json.providers.length >= 2);
  assert.ok(r.json.providers.every((p) => p.implemented === false));
});

const srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n\n2\n00:00:03,000 --> 00:00:04,000\nWorld\n";
await test("a translation request validates input, then answers 501 (nothing sent, no cost)", async () => {
  assert.equal((await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", source_file: "" })).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate", { content_id: "nope", provider: "opensubtitles_ai", source_file: srt })).status, 404);
  assert.equal((await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", source_file: srt, target: "fr" })).status, 400);
  const r = await call("POST", "/admin/subhub/translate", { content_id: "m2", provider: "opensubtitles_ai", source_file: srt });
  assert.equal(r.status, 501);
  assert.equal(r.json.error, "provider_not_implemented");
  assert.equal(calls.some((u) => /opensubtitles/.test(u.hostname)), false);
});

await test("a translation batch needs approval, a budget above the estimate and unique ids", async () => {
  const base = { ids: ["m1", "m2"], provider: "opensubtitles_ai", budget: 10, estimate: 2, approved_at: "2026-11-08T10:00:00Z" };
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { approved_at: "" }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { budget: 1 }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", Object.assign({}, base, { ids: ["m1", "m1"] }))).status, 400);
  assert.equal((await call("POST", "/admin/subhub/translate/batch", base)).status, 501);
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

console.log(`\n${passed} passed`);

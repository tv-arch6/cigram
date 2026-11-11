// 19.4 — OpenSubtitles moviehash computed from the R2 video with two 64 KiB range reads (Node 22, no network).
// Usage: node subhub_moviehash_test.mjs <path to worker.patched.mjs>
import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";

const R2 = "https://pub-eabe41d14a0644e5800344c99ef4d971.r2.dev";
const CHUNK = 65536;

// ---- independent reference: 32-bit halves with carries (no BigInt), reads the WHOLE buffer's edges
function refHash(buf) {
  let lo = buf.length >>> 0, hi = Math.floor(buf.length / 2 ** 32) >>> 0;
  const add = (off) => {
    for (let i = off; i < off + CHUNK; i += 8) {
      const l = buf.readUInt32LE(i), h = buf.readUInt32LE(i + 4);
      const s = lo + l;
      lo = s >>> 0;
      hi = (hi + h + (s > 0xffffffff ? 1 : 0)) >>> 0;
    }
  };
  add(0); add(buf.length - CHUNK);
  return hi.toString(16).padStart(8, "0") + lo.toString(16).padStart(8, "0");
}

// ---- binary R2 with ranges, HEAD size and etag; records every ranged read
class BinR2 {
  constructor() { this.m = new Map(); this.n = 0; this.reads = []; this.fullReads = []; }
  async put(k, v, o) {
    const cur = this.m.get(k);
    if (o && o.onlyIf && o.onlyIf.etagMatches && (!cur || cur.etag !== o.onlyIf.etagMatches)) return null;
    const bytes = typeof v === "string" ? new TextEncoder().encode(v) : new Uint8Array(v);
    const etag = "e" + (++this.n);
    this.m.set(k, { bytes, etag });
    return { etag, key: k, size: bytes.length };
  }
  async head(k) { const o = this.m.get(k); return o ? { etag: o.etag, httpEtag: '"' + o.etag + '"', size: o.bytes.length, key: k } : null; }
  async get(k, opts = {}) {
    const o = this.m.get(k);
    if (!o) return null;
    let bytes = o.bytes;
    if (opts.range) { bytes = bytes.subarray(opts.range.offset, opts.range.offset + opts.range.length); this.reads.push({ k, ...opts.range }); }
    else if (!k.endsWith(".json")) this.fullReads.push(k);
    const text = () => new TextDecoder().decode(bytes);
    return { etag: o.etag, httpEtag: '"' + o.etag + '"', size: o.bytes.length, body: bytes,
      text: async () => text(), json: async () => JSON.parse(text()), arrayBuffer: async () => bytes.slice().buffer };
  }
  async delete(k) { this.m.delete(k); }
  async list(o = {}) { return { objects: [...this.m.keys()].filter((k) => !o.prefix || k.startsWith(o.prefix)).map((key) => ({ key })), truncated: false }; }
}

const osSeen = [];
let EXPECT_HASH = "";
globalThis.fetch = async (input, init) => {
  const u = new URL(typeof input === "string" ? input : input.url);
  const j = (o) => new Response(JSON.stringify(o), { status: 200, headers: { "content-type": "application/json" } });
  if (u.hostname === "api.themoviedb.org") return j({ imdb_id: "tt0137523", movie_results: [{ id: 550 }], tv_results: [{ id: 1396 }] });
  if (u.hostname === "api.subdl.com") {
    // a GOOD guess by release name (score >= 55): before 19.4 OpenSubtitles was then never asked
    return j({ status: true, subtitles: [{ language: "AR", url: "/subtitle/1-1.zip", name: "Fight.Club.1999.1080p.BluRay.x264", release_name: "Fight.Club.1999.1080p.BluRay.x264", author: "a" }] });
  }
  if (u.hostname.endsWith("opensubtitles.com")) {
    osSeen.push(Object.fromEntries(u.searchParams));
    if (u.pathname !== "/api/v1/subtitles") return new Response("{}", { status: 404 });
    const mh = u.searchParams.get("moviehash");
    const row = (id, release, match) => ({ id: String(id), type: "subtitle", attributes: { language: "ar", download_count: 5, ratings: 0, from_trusted: false, ai_translated: false,
      release, moviehash_match: match, uploader: { name: "u" }, feature_details: { season_number: 0, episode_number: 0 }, files: [{ file_id: id, file_name: release }] } });
    return j({ total_count: 2, data: [row(8001, "Fight.Club.1999.1080p.BluRay.x264", false), row(8002, "Fight.Club.1999.Some.Other.Name", Boolean(mh) && mh === EXPECT_HASH)] });
  }
  return new Response("not stubbed", { status: 599 });
};

const mod = await import(process.argv[2]);
const worker = mod.default;
const bucket = new BinR2();
const video = randomBytes(3 * CHUNK + 12345);           // not a multiple of 8 on purpose: only the edges are summed
const ep = randomBytes(2 * CHUNK);                      // the smallest hashable size
await bucket.put("fight-club/Fight.Club.1999.1080p.BluRay.x264.mp4", video);
await bucket.put("bb/S01/E%20(1).mkv", ep);
await bucket.put("tiny/t.mp4", randomBytes(1000));
await bucket.put("movies.json", JSON.stringify([
  { id: "m2", type: "movie", title: "Fight Club", tmdb: "550", imdb_id: "tt0137523", quality: "1080p", video: R2 + "/fight-club/Fight.Club.1999.1080p.BluRay.x264.mp4" },
  { id: "m9", type: "movie", title: "Elsewhere", tmdb: "9", imdb_id: "tt0000009", video: "https://vidbolt.pro/e/abc" },
  { id: "m7", type: "movie", title: "Tiny", tmdb: "7", imdb_id: "tt0000007", video: R2 + "/tiny/t.mp4" },
  { id: "m8", type: "movie", title: "Gone", tmdb: "8", imdb_id: "tt0000008", video: R2 + "/gone/x.mp4" },
  { id: "e1", type: "episode", series_id: "s1", season: 1, episode: 1, title: "Pilot", video: R2 + "/bb/S01/" + encodeURIComponent("E%20(1).mkv") },
]));
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: "admin-secret", TMDB_API_KEY: "tmdb-test", SUBDL_API_KEY: "subdl-test", OPENSUBTITLES_API_KEY: "os-key", OS_RATE_LIMIT_10S: "100000" };
const call = async (method, path, body, token = env.ADMIN_TOKEN) => {
  const r = await worker.fetch(new Request("https://admin.test" + path, { method, headers: Object.assign({ "content-type": "application/json" }, token ? { authorization: "Bearer " + token } : {}), body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  const reply = await r.json();
  return { status: r.status, json: reply && reply.data && typeof reply.data === "object" ? Object.assign({}, reply, reply.data) : reply };
};
const realLog = console.log;
console.log = (...a) => { if (!(typeof a[0] === "string" && a[0].startsWith('{"evt":"subhub"'))) realLog(...a); };
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

EXPECT_HASH = refHash(video);

await test("hash = OSDb algorithm (size + Σ uint64 of both 64 KiB edges), equal to an independent implementation", async () => {
  const r = await call("GET", "/admin/subhub/moviehash?item_id=m2");
  assert.equal(r.status, 200, JSON.stringify(r.json));
  assert.equal(r.json.state, "ok");
  assert.equal(r.json.moviehash, EXPECT_HASH);
  assert.match(r.json.moviehash, /^[0-9a-f]{16}$/);
  assert.equal(r.json.size, video.length);
  assert.equal(r.json.cached, false);
});

await test("only two 64 KiB range reads, never the whole video", async () => {
  const mine = bucket.reads.filter((x) => x.k.startsWith("fight-club/"));
  assert.deepEqual(mine.map((x) => [x.offset, x.length]).sort((a, b) => a[0] - b[0]), [[0, CHUNK], [video.length - CHUNK, CHUNK]]);
  assert.deepEqual(bucket.fullReads, []);
});

await test("an all-zero file hashes to its size (known vector)", async () => {
  const size = 5 * CHUNK + 7;
  await bucket.put("zero/z.mp4", new Uint8Array(size));
  const list = JSON.parse(new TextDecoder().decode(bucket.m.get("movies.json").bytes));
  list.push({ id: "z", type: "movie", title: "Zero", tmdb: "1", video: R2 + "/zero/z.mp4" });
  await bucket.put("movies.json", JSON.stringify(list));
  const r = await call("GET", "/admin/subhub/moviehash?item_id=z");
  assert.equal(r.json.moviehash, size.toString(16).padStart(16, "0"));
});

await test("cached per etag: second call reads no bytes; a replaced video is hashed again", async () => {
  const before = bucket.reads.length;
  let r = await call("GET", "/admin/subhub/moviehash?item_id=m2");
  assert.equal(r.json.cached, true);
  assert.equal(r.json.moviehash, EXPECT_HASH);
  assert.equal(bucket.reads.length, before);
  const replaced = randomBytes(4 * CHUNK);
  await bucket.put("fight-club/Fight.Club.1999.1080p.BluRay.x264.mp4", replaced);
  r = await call("GET", "/admin/subhub/moviehash?item_id=m2");
  assert.equal(r.json.cached, false);
  assert.equal(r.json.moviehash, refHash(replaced));
  await bucket.put("fight-club/Fight.Club.1999.1080p.BluRay.x264.mp4", video);   // restore
});

await test("episode with an escaped R2 key (minimum size 128 KiB)", async () => {
  const r = await call("GET", "/admin/subhub/moviehash?item_id=e1");
  assert.equal(r.json.state, "ok", JSON.stringify(r.json));
  assert.equal(r.json.key, "bb/S01/E%20(1).mkv");
  assert.equal(r.json.moviehash, refHash(ep));
});

await test("never fatal: non-R2 video, too small, missing object", async () => {
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=m9")).json.state, "not_r2");
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=m7")).json.state, "too_small");
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=m8")).json.state, "missing");
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=nope")).status, 404);
});

await test("search sends moviehash to OpenSubtitles even after a good SubDL guess, and the byte match ranks first", async () => {
  osSeen.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m2" });
  assert.equal(r.status, 200, JSON.stringify(r.json).slice(0, 400));
  assert.equal(r.json.target.moviehash, EXPECT_HASH);
  assert.equal(r.json.target.moviehash_state, "ok");
  const q = osSeen.find((x) => x.tmdb_id);
  assert.ok(q, "OpenSubtitles was asked");
  assert.equal(q.moviehash, EXPECT_HASH);
  assert.equal(q.tmdb_id, "550");
  assert.equal(r.json.best.source, "opensubtitles");
  assert.equal(r.json.best.id, "8002");
  assert.equal(r.json.best.hash_match, true);
  const subdl = r.json.results.find((c) => c.source === "subdl");
  assert.ok(subdl && subdl.hash_match === false && subdl.score < r.json.best.score);
});

await test("a title outside R2 searches exactly as before (no moviehash param, OpenSubtitles not needed)", async () => {
  osSeen.length = 0;
  const r = await call("POST", "/admin/subs/search", { item_id: "m9" });
  assert.equal(r.status, 200);
  assert.equal(r.json.target.moviehash, "");
  assert.equal(r.json.target.moviehash_state, "not_r2");
  assert.ok(osSeen.every((x) => !x.moviehash));
});

await test("the route needs the admin token and leaks no secret", async () => {
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=m2", null, "wrong")).status, 401);
  assert.equal((await call("GET", "/admin/subhub/moviehash?item_id=m2", null, null)).status, 401);
  const all = JSON.stringify((await call("GET", "/admin/subhub/moviehash?item_id=m2")).json);
  for (const s of ["os-key", "subdl-test", "tmdb-test", "admin-secret"]) assert.ok(!all.includes(s));
});

console.log(`\n${passed} passed`);

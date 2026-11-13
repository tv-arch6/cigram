// 18.4 — contract test of the catalogue feed (/catalog/manifest, /catalog/changes) in the PATCHED cigram-admin-api worker (Node 22).
// No network: R2 is in memory. The admin's real routes (/admin/item/create|update|delete) drive the real mutateMovies write path.
// Usage: node catalog_feed_test.mjs <path to worker.patched.mjs>
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

globalThis.fetch = async () => new Response("not stubbed", { status: 599 });

class MemR2 {
  constructor() { this.m = new Map(); this.n = 0; this.failPut = null; this.puts = []; }
  async get(k, opts = {}) {
    if (!this.m.has(k)) return null;
    const { text, etag } = this.m.get(k);
    const obj = { etag, httpEtag: '"' + etag + '"', uploaded: this.m.get(k).uploaded, text: async () => text, json: async () => JSON.parse(text) };
    const inm = opts && opts.onlyIf && opts.onlyIf.get ? opts.onlyIf.get("if-none-match") : null;
    if (inm && inm === obj.httpEtag) return obj;
    obj.body = text;
    return obj;
  }
  async head(k) { return this.m.has(k) ? { etag: this.m.get(k).etag, uploaded: this.m.get(k).uploaded } : null; }
  async put(k, v, o) {
    if (this.failPut && this.failPut(k)) throw new Error("injected R2 failure for " + k);
    const cur = this.m.get(k);
    if (o && o.onlyIf && o.onlyIf.etagMatches && (!cur || cur.etag !== o.onlyIf.etagMatches)) return null;
    if (o && o.onlyIf && o.onlyIf.etagDoesNotMatch === "*" && cur) return null;
    const etag = "e" + (++this.n);
    this.m.set(k, { text: typeof v === "string" ? v : new TextDecoder().decode(v), etag, uploaded: new Date() });
    this.puts.push(k);
    return { etag, key: k };
  }
  async delete(k) { if (Array.isArray(k)) k.forEach((x) => this.m.delete(x)); else this.m.delete(k); }
  async list(o = {}) { return { objects: [...this.m.keys()].filter((k) => !o.prefix || k.startsWith(o.prefix)).map((key) => ({ key })), truncated: false }; }
  backdate(k, ms) { this.m.get(k).uploaded = new Date(Date.now() - ms); }
}

const sha = (t) => createHash("sha256").update(t).digest("hex");
const idsDigest = (ids) => sha(ids.slice().sort().join("\n"));

const mod = await import(process.argv[2]);
const worker = mod.default;
const mk = (n) => Array.from({ length: n }, (_, i) => ({ id: "m" + i, type: "movie", title: "Movie " + i, video: "https://v.example.invalid/" + i + ".mp4", poster: "https://p.example.invalid/" + i + ".jpg" }));
const series = { id: "series_1", type: "series", series_id: "series_1", title: "Show", poster: "https://p.example.invalid/s.jpg", video: "" };
const ep = (n) => ({ id: "ep_1_" + n, type: "episode", series_id: "series_1", season: "1", episode: String(n), title: "Ep " + n, video: "https://v.example.invalid/e" + n + ".mp4", season_poster: "https://p.example.invalid/sp.jpg" });
const baseDoc = () => ({ ok: true, count: 3, movies: [...mk(5), series, ep(1), ep(2)] });

const bucket = new MemR2();
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: "admin-secret", OS_RATE_LIMIT_10S: "100000" };
const put = (doc) => bucket.put("movies.json", JSON.stringify(doc));
await put(baseDoc());
const call = async (method, path, body, headers = {}, token = env.ADMIN_TOKEN) => {
  const h = Object.assign({ "content-type": "application/json" }, token && method === "POST" ? { authorization: "Bearer " + token } : {}, headers);
  const r = await worker.fetch(new Request("https://admin.test" + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined }), env, { waitUntil() {} });
  const text = await r.text();
  let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* 304 */ }
  const data = json && json.data && typeof json.data === "object" ? Object.assign({}, json, json.data) : json;
  return { status: r.status, headers: r.headers, json: data };
};
const settle = (key = "movies.json") => bucket.backdate(key, 60000);   // pretend the last write is older than the settle window
const liveIds = () => JSON.parse(bucket.m.get("movies.json").text).movies.map((x) => x.id);
const logLines = [];
const realLog = console.log;
console.log = (...a) => { if (typeof a[0] === "string" && a[0].startsWith('{"evt":"catalog_feed"')) logLines.push(a[0]); else realLog(...a); };
let passed = 0;
const test = async (name, fn) => { await fn(); passed++; console.log("ok -", name); };

await test("bootstrap: the first manifest describes the whole catalogue, ids_digest matches, 304 on the same ETag", async () => {
  const r = await call("GET", "/catalog/manifest");
  assert.equal(r.status, 200);
  assert.equal(r.json.ok, true);
  assert.equal(r.json.schema, 1);
  assert.equal(r.json.rev, 1);
  assert.equal(r.json.records, 8);
  assert.equal(r.json.ids_digest, idsDigest(liveIds()));
  assert.equal(r.json.window_from, 1);                                  // nothing older than the bootstrap can be served as a delta
  assert.match(r.headers.get("cache-control"), /no-cache/);
  const etag = r.headers.get("etag");
  assert.ok(etag);
  const again = await call("GET", "/catalog/manifest", null, { "if-none-match": etag });
  assert.equal(again.status, 304);
  assert.equal(await (await worker.fetch(new Request("https://admin.test/movies"), env, {})).status, 200);   // /movies is untouched
});

await test("a client that has never synced (since=0) and one older than the bootstrap are told to download /movies", async () => {
  const r = await call("GET", "/catalog/changes?since=0");
  assert.equal(r.json.full, true);
  assert.equal(r.json.reason, "window");
  assert.equal(r.json.head.rev, 1);
  const up = await call("GET", "/catalog/changes?since=1");
  assert.equal(up.status, 200);
  assert.equal(up.json.full, undefined);
  assert.deepEqual([up.json.from, up.json.to, up.json.more, up.json.upserts, up.json.removed], [1, 1, false, [], []]);
});

await test("adding a movie through the admin route: one revision, ONLY that record is in the delta, digest still matches", async () => {
  const c = await call("POST", "/admin/item/create", { type: "movie", title: "Brand New", video: "https://v.example.invalid/new.mp4", poster: "https://p.example.invalid/new.jpg" });
  assert.equal(c.status, 200, JSON.stringify(c.json).slice(0, 300));
  const newId = c.json.item ? c.json.item.id : (c.json.result && c.json.result.id) || liveIds().find((i) => !/^(m\d|series_1|ep_)/.test(i));
  assert.ok(newId);
  const m = await call("GET", "/catalog/manifest");
  assert.equal(m.json.rev, 2);
  assert.equal(m.json.records, 9);
  assert.equal(m.json.ids_digest, idsDigest(liveIds()));
  const d = await call("GET", "/catalog/changes?since=1");
  assert.equal(d.json.to, 2);
  assert.equal(d.json.more, false);
  assert.deepEqual(d.json.upserts.map((x) => x.id), [newId]);
  assert.equal(d.json.upserts[0].title, "Brand New");
  assert.deepEqual(d.json.removed, []);
});

await test("editing a record: the delta carries only that record with its new value", async () => {
  const u = await call("POST", "/admin/item/update", { id: "m2", changes: { title: "Movie 2 (edited)" } });
  assert.equal(u.status, 200, JSON.stringify(u.json).slice(0, 300));
  const d = await call("GET", "/catalog/changes?since=2");
  assert.equal(d.json.to, 3);
  assert.deepEqual(d.json.upserts.map((x) => [x.id, x.title]), [["m2", "Movie 2 (edited)"]]);
  // and the whole catalogue is untouched elsewhere
  const m = await call("GET", "/catalog/manifest");
  assert.equal(m.json.records, 9);
  assert.equal(m.json.ids_digest, idsDigest(liveIds()));
});

await test("a new episode is a one-record delta; the series header is not re-sent", async () => {
  const c = await call("POST", "/admin/item/create", { type: "episode", series_id: "series_1", season: 1, episode: 3, title: "Ep 3", video: "https://v.example.invalid/e3.mp4" });
  assert.equal(c.status, 200, JSON.stringify(c.json).slice(0, 400));
  const d = await call("GET", "/catalog/changes?since=3");
  assert.equal(d.json.upserts.length, 1);
  assert.equal(d.json.upserts[0].type, "episode");
  assert.equal(d.json.upserts[0].series_id, "series_1");
  assert.deepEqual(d.json.removed, []);
});

await test("deleting a record is a tombstone, nothing else is removed", async () => {
  const before = liveIds().length;
  const x = await call("POST", "/admin/item/delete", { id: "m3" });
  assert.equal(x.status, 200, JSON.stringify(x.json).slice(0, 300));
  assert.equal(liveIds().length, before - 1);
  const d = await call("GET", "/catalog/changes?since=4");
  assert.deepEqual(d.json.removed, ["m3"]);
  assert.deepEqual(d.json.upserts, []);
});

await test("a client applying every delta ends with exactly the server's id set (what the app verifies)", async () => {
  // replay: start from the bootstrap snapshot (rev 1) and apply revisions 2..head
  const snap1 = new Map(baseDoc().movies.map((x) => [x.id, x]));
  let since = 1, guard = 0;
  while (guard++ < 20) {
    const d = await call("GET", "/catalog/changes?since=" + since);
    assert.notEqual(d.json.full, true);
    for (const r of d.json.upserts) snap1.set(r.id, r);
    for (const id of d.json.removed) snap1.delete(id);
    since = d.json.to;
    if (!d.json.more) {
      assert.equal(d.json.head.records, snap1.size);
      assert.equal(d.json.head.ids_digest, idsDigest([...snap1.keys()]));
      break;
    }
  }
  assert.equal(since, 5);
  const live = new Map(JSON.parse(bucket.m.get("movies.json").text).movies.map((x) => [x.id, x]));
  for (const [id, rec] of live) assert.deepEqual(snap1.get(id), rec, "record " + id + " differs after replay");
});

await test("an out-of-band edit of movies.json (dashboard upload) becomes an EXACT delta once it is older than the settle window", async () => {
  const doc = JSON.parse(bucket.m.get("movies.json").text);
  doc.movies.find((x) => x.id === "m1").title = "Edited outside the Worker";
  await put(doc);                                                       // fresh object: the feed may not call it foreign yet
  const busy = await call("GET", "/catalog/manifest");
  assert.equal(busy.json.busy, true);
  assert.equal(busy.json.rev, 5);                                       // no reset was declared for it
  settle();
  const m = await call("GET", "/catalog/manifest");
  assert.equal(m.json.busy, undefined);
  assert.equal(m.json.rev, 6);
  const d = await call("GET", "/catalog/changes?since=5");
  assert.equal(d.json.full, undefined, "an out-of-band edit must be a delta, not a reset");
  assert.deepEqual(d.json.upserts.map((x) => [x.id, x.title]), [["m1", "Edited outside the Worker"]]);
});

await test("pagination: a long gap is served in pages that end on a revision boundary, `more` until the head", async () => {
  // three out-of-band revisions of 25 changed records each
  for (let r = 0; r < 3; r++) {
    const doc = JSON.parse(bucket.m.get("movies.json").text);
    for (let i = 0; i < 25; i++) { const t = doc.movies.find((x) => x.id === ["m0", "m1", "m2", "m4"][i % 4]); t["note_" + r + "_" + i] = String(r); doc.movies.push({ id: "p" + r + "_" + i, type: "movie", title: "P" + r + "_" + i }); }
    await put(doc); settle();
    await call("GET", "/catalog/manifest");
  }
  const head = (await call("GET", "/catalog/manifest")).json;
  assert.equal(head.rev, 9);
  const pages = [];
  let since = 6;
  for (let g = 0; g < 10; g++) {
    const d = await call("GET", "/catalog/changes?since=" + since + "&max=40");
    assert.notEqual(d.json.full, true);
    pages.push(d.json);
    since = d.json.to;
    if (!d.json.more) break;
  }
  assert.ok(pages.length >= 3, "expected several pages, got " + pages.length);
  assert.ok(pages.slice(0, -1).every((p) => p.more === true));
  assert.equal(pages[pages.length - 1].more, false);
  assert.equal(since, 9);
  const ids = new Set(pages.flatMap((p) => p.upserts.map((x) => x.id)));
  for (let r = 0; r < 3; r++) for (let i = 0; i < 25; i++) assert.ok(ids.has("p" + r + "_" + i), "p" + r + "_" + i + " missing from the pages");
});

await test("a client from the future, or with a bad `since`, is not served a delta", async () => {
  assert.equal((await call("GET", "/catalog/changes?since=999")).json.reason, "ahead");
  assert.equal((await call("GET", "/catalog/changes?since=abc")).status, 400);
  assert.equal((await call("GET", "/catalog/changes")).status, 400);
});

await test("a bulk import (> 300 changed records) is a RESET: older clients download /movies once, newer ones are unaffected", async () => {
  const before = (await call("GET", "/catalog/manifest")).json.rev;
  const doc = JSON.parse(bucket.m.get("movies.json").text);
  for (let i = 0; i < 320; i++) doc.movies.push({ id: "bulk" + i, type: "movie", title: "Bulk " + i });
  await put(doc); settle();
  const m = (await call("GET", "/catalog/manifest")).json;
  assert.equal(m.rev, before + 1);
  assert.equal(m.window_from, m.rev);
  const old = await call("GET", "/catalog/changes?since=" + before);
  assert.equal(old.json.full, true);
  assert.equal(old.json.reason, "window");
  assert.equal(old.json.head.records, m.records);
  const cur = await call("GET", "/catalog/changes?since=" + m.rev);
  assert.equal(cur.json.full, undefined);
});

await test("lost hashes (R2 object deleted) never lose data: the next write declares a reset instead of a wrong delta", async () => {
  const keys = [...bucket.m.keys()].filter((k) => k.endsWith("catalog/hashes.json"));
  assert.equal(keys.length, 1);
  const rev0 = (await call("GET", "/catalog/manifest")).json.rev;
  bucket.m.delete(keys[0]);
  const c = await call("POST", "/admin/item/create", { type: "movie", title: "After hash loss", video: "https://v.example.invalid/h.mp4" });
  assert.equal(c.status, 200);
  const m = (await call("GET", "/catalog/manifest")).json;
  assert.equal(m.rev, rev0 + 1);
  assert.equal(m.window_from, m.rev);
  assert.equal(m.records, liveIds().length);
  assert.equal(m.ids_digest, idsDigest(liveIds()));
});

await test("a feed failure never fails the admin's write, and never touches movies.json's content", async () => {
  bucket.failPut = (k) => k.includes("catalog/");
  const n = liveIds().length;
  const c = await call("POST", "/admin/item/create", { type: "movie", title: "Feed is down", video: "https://v.example.invalid/f.mp4" });
  bucket.failPut = null;
  assert.equal(c.status, 200, "the admin write must succeed while the feed is broken");
  assert.equal(liveIds().length, n + 1);
  assert.ok(logLines.some((l) => l.includes('"catalog_feed"') && l.includes('"ok":false')), "the failure is logged");
  assert.ok(!logLines.join("").includes("admin-secret"));
  // the head etag is now behind movies.json; once it is older than the settle window the feed reconciles by diff
  settle();
  const m = (await call("GET", "/catalog/manifest")).json;
  assert.equal(m.records, liveIds().length);
  assert.equal(m.ids_digest, idsDigest(liveIds()));
});

await test("the log is bounded: old entries are pruned and window_from moves forward", async () => {
  const start = (await call("GET", "/catalog/manifest")).json.rev;
  for (let i = 0; i < 125; i++) {
    const c = await call("POST", "/admin/item/update", { id: "m4", changes: { title: "loop " + i } });
    assert.equal(c.status, 200);
  }
  const m = (await call("GET", "/catalog/manifest")).json;
  assert.equal(m.rev, start + 125);
  assert.ok(m.window_from >= start + 125 - 120, "window_from " + m.window_from);
  const feed = JSON.parse([...bucket.m.entries()].find(([k]) => k.endsWith("catalog/feed.json"))[1].text);
  assert.ok(feed.entries.length <= 120);
  assert.equal((await call("GET", "/catalog/changes?since=" + start)).json.reason, "window");
  assert.equal((await call("GET", "/catalog/changes?since=" + (m.rev - 3))).json.upserts.length, 1);
});

await test("an unreadable (corrupt) movies.json is never reported as an empty catalogue", async () => {
  await bucket.put("movies.json", "{ this is not json");
  settle();
  const r = await call("GET", "/catalog/manifest");
  assert.ok(r.status === 200 || r.status === 503);
  if (r.status === 200) assert.ok(r.json.records > 0, "the last known head is kept, not zero");
  await put(baseDoc());                                                 // restore for the remaining checks
});

await test("responses carry no secret and the routes need no token", async () => {
  const r = await call("GET", "/catalog/manifest", null, {}, "");
  assert.equal(r.status, 200);
  const all = JSON.stringify(r.json) + [...r.headers.entries()].join();
  assert.ok(!all.includes("admin-secret"));
});

console.log = realLog;
console.log(passed + " passed");

// Validation report + import tests (movie, season, selected episodes, categories, duplicates, idempotency,
// failures, main-app visibility, existing admin features). External services are mocks — see helpers.mjs.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { loadWorker, makeEnv, createWorld, call, arabicSrt, englishSrt } from "./helpers.mjs";

const SECTIONS = { version: 1, sections: [
  { id: "action", slug: "action", name: "أكشن", type: "movie", order: 1, enabled: true, show_home: true },
  { id: "drama", slug: "drama", name: "دراما", type: "series", order: 2, enabled: true, show_home: true },
] };

async function setup(over = {}, envExtra = {}) {
  const worker = await loadWorker();
  const env = makeEnv(envExtra);
  env.MOVIES_BUCKET.seedJson("cigram_home_sections.json", SECTIONS);
  const world = createWorld(over);
  const calls = world.install();
  const t = { worker, env, world, calls, bucket: env.MOVIES_BUCKET, post: (p, b, o) => call(worker, env, "POST", p, b, o), get: (p, o) => call(worker, env, "GET", p, undefined, o) };
  t.library = () => JSON.parse(t.bucket.text("movies.json"));
  t.video = async (tmdb) => (await t.post("/admin/discover/video", { type: "movie", tmdb_id: tmdb })).data.targets[0].sources[0];
  t.episodes = async (tmdb, season, eps) => (await t.post("/admin/discover/video", { type: "series", tmdb_id: tmdb, season, episodes: eps })).data.targets;
  t.subtitle = async (tmdb, lang = "ar") => {
    const s = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: tmdb, lang });
    return (await t.post("/admin/discover/subtitles/fetch", { type: "movie", tmdb_id: tmdb, lang, pick: s.data.results[0] })).data;
  };
  t.moviePlan = async (tmdb, over2 = {}) => {
    const v = await t.video(tmdb);
    return Object.assign({ type: "movie", tmdb_id: tmdb, movie: { video_token: v.token }, ack_unverified: true, request_id: "req-" + tmdb + "-" + Math.random().toString(36).slice(2, 7) }, over2);
  };
  t.seriesPlan = async (tmdb, season, eps, over2 = {}) => {
    const targets = await t.episodes(tmdb, season, eps);
    return Object.assign({ type: "series", tmdb_id: tmdb, seasons: [{ season, episodes: targets.map((x) => ({ episode: x.episode, video_token: x.sources[0].token })) }], ack_unverified: true, request_id: "req-s" + tmdb + "-" + season + "-" + Math.random().toString(36).slice(2, 7) }, over2);
  };
  return t;
}

describe("validation report", () => {
  it("a good movie plan is 'Ready to Import' but requires an acknowledgement for an unprovable embed", async () => {
    const t = await setup();
    const plan = await t.moviePlan(1001, { section: { id: "action" } });
    const r = await t.post("/admin/discover/validate", plan);
    assert.equal(r.status, 200);
    const rep = r.data.report;
    assert.equal(rep.state, "ready");
    assert.equal(rep.state_label_en, "Ready to Import");
    assert.equal(rep.ok, true);
    assert.equal(rep.requires_ack, true);
    assert.equal(rep.can_import, true, "the plan carries ack_unverified");
    const noAck = await t.post("/admin/discover/validate", Object.assign({}, plan, { ack_unverified: false }));
    assert.equal(noAck.data.report.can_import, false);
    const ids = rep.checks.map((c) => c.id);
    for (const id of ["tmdb", "metadata", "poster_url", "category", "duplicate"]) assert.ok(ids.includes(id), "missing check " + id);
    assert.equal(rep.items[0].checks.find((c) => c.id === "video").status, "warn");
    assert.equal(rep.items[0].checks.find((c) => c.id === "subtitle").status, "skip");
  });

  it("states: Missing Video, Invalid Subtitle, Subtitle Missing, Duplicate Content, Missing Metadata", async () => {
    const t = await setup();
    t.bucket.seedJson("movies.json", [{ id: "m-beta", type: "movie", tmdb: "1002", title: "فيلم 1002", year: "2025" }]);
    const none = await t.post("/admin/discover/validate", { type: "movie", tmdb_id: 1001, movie: {} });
    assert.equal(none.data.report.state, "missing_video");
    const dup = await t.post("/admin/discover/validate", await t.moviePlan(1002));
    assert.equal(dup.data.report.state, "duplicate");
    assert.equal(dup.data.report.state_label_en, "Duplicate Content");
    const req = await t.post("/admin/discover/validate", Object.assign(await t.moviePlan(1001), { options: { subtitle_required: true } }));
    assert.equal(req.data.report.state, "subtitle_missing");
    const sub = await t.subtitle(1001);
    const other = await t.post("/admin/discover/validate", await t.moviePlan(1004, { movie: { video_token: (await t.video(1004)).token, subtitle_token: sub.token } }));
    assert.equal(other.data.report.state, "invalid_subtitle", "a subtitle fetched for another title must not be attached");
    // a title TMDB has no poster / title for (an id nothing else touched, so no cached copy exists)
    t.world.state.movies[1005].poster_path = null;
    t.world.state.movies[1005].title = "";
    t.world.state.movies[1005].original_title = "";
    const nm = await t.post("/admin/discover/validate", { type: "movie", tmdb_id: 1005, movie: {} });
    assert.equal(nm.data.report.state, "missing_metadata");
    const badSection = await t.post("/admin/discover/validate", await t.moviePlan(1004, { section: { id: "ghost" } }));
    assert.equal(badSection.data.report.state, "missing_metadata");
    assert.equal(badSection.data.report.checks.find((c) => c.id === "category").status, "fail");
  });

  it("an episode token can never be used for another season/episode, and forged / expired tokens are refused", async () => {
    const t = await setup();
    const [e1, e2] = await t.episodes(2001, 1, [1, 2]);
    const swapped = { type: "series", tmdb_id: 2001, ack_unverified: true, seasons: [{ season: 1, episodes: [{ episode: 2, video_token: e1.sources[0].token }] }] };
    const r = await t.post("/admin/discover/validate", swapped);
    assert.equal(r.data.report.state, "missing_video");
    assert.match(JSON.stringify(r.data.report.items[0].checks), /لن يُربط برابط خاطئ/);
    const forged = e2.sources[0].token.slice(0, -2) + (e2.sources[0].token.endsWith("aa") ? "bb" : "aa");
    const f = await t.post("/admin/discover/validate", { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: [{ episode: 2, video_token: forged }] }] });
    assert.equal(f.data.report.state, "missing_video");
    const payload = JSON.parse(Buffer.from(e2.sources[0].token.split(".")[0], "base64url").toString());
    const lying = Buffer.from(JSON.stringify(Object.assign(payload, { v: "playable", u: "https://evil.example.com/x.mp4" }))).toString("base64url") + "." + e2.sources[0].token.split(".")[1];
    const l = await t.post("/admin/discover/validate", { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: [{ episode: 2, video_token: lying }] }] });
    assert.equal(l.data.report.state, "missing_video", "an edited payload (claiming 'playable') fails the signature");
    const real = Date.now;
    Date.now = () => real() + 31 * 60000;
    try {
      const ex = await t.post("/admin/discover/validate", { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: [{ episode: 2, video_token: e2.sources[0].token }] }] });
      assert.equal(ex.data.report.state, "missing_video");
      assert.match(JSON.stringify(ex.data.report.items[0].checks), /انتهت صلاحية/);
    } finally { Date.now = real; }
  });

  it("unreleased episodes and episodes TMDB does not list are rejected", async () => {
    const t = await setup();
    const targets = await t.episodes(2001, 2, [1, 2]);
    const plan = { type: "series", tmdb_id: 2001, ack_unverified: true, seasons: [{ season: 2, episodes: [{ episode: 1, video_token: targets[0].sources[0].token }, { episode: 2, video_token: targets[0].sources[0].token }] }] };
    const r = await t.post("/admin/discover/validate", plan);
    assert.notEqual(r.data.report.state, "ready");
    const unknown = await t.post("/admin/discover/validate", { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: [{ episode: 44, video_token: "x" }] }] });
    assert.equal(unknown.data.report.state, "missing_metadata");
  });
});

describe("10-12 categories and importing a movie", () => {
  it("12) imports a movie into an EXISTING category with a validated subtitle; the main app can read it", async () => {
    const t = await setup();
    const sub = await t.subtitle(1001);
    const plan = await t.moviePlan(1001, { section: { id: "action" } });
    plan.movie.subtitle_token = sub.token;
    const noAck = await t.post("/admin/discover/import", Object.assign({}, plan, { ack_unverified: false, request_id: "noack" }));
    assert.equal(noAck.status, 409);
    assert.equal(noAck.json.error, "ack_required");
    assert.equal(t.library().length, 0, "nothing is written without the acknowledgement");
    const r = await t.post("/admin/discover/import", plan);
    assert.equal(r.status, 200, r.text);
    assert.equal(r.data.state, "imported");
    assert.equal(r.data.state_label_en, "Successfully Imported");
    assert.equal(r.data.created.length, 1);
    assert.ok(r.data.undo_id);
    const lib = t.library();
    assert.equal(lib.length, 1);
    const item = lib[0];
    assert.equal(item.type, "movie");
    assert.equal(item.title, "فيلم 1001");
    assert.equal(item.original_title, "Movie 1001");
    assert.equal(item.tmdb, "1001");
    assert.equal(item.imdb_id, "tt0001001");
    assert.equal(item.imdb, "7.8");
    assert.equal(item.year, String(new Date(Date.now() - 10 * 86400000).getFullYear()));
    assert.equal(item.duration, "02:01");
    assert.equal(item.category, "أكشن، إثارة");
    assert.equal(item.section_id, "action");
    assert.equal(item.section_name, "أكشن");
    assert.equal(item.poster, "https://image.tmdb.org/t/p/w500/p1001.jpg");
    assert.equal(item.video, "https://vidbolt.pro/movie/1001");
    assert.equal(item.link, item.video);
    assert.match(item.subtitle, /\.ar\.srt$/);
    for (const k of ["series_id", "season", "episode"]) assert.equal(item[k], "");
    const key = decodeURIComponent(new URL(item.subtitle).pathname.slice(1));
    assert.match(t.bucket.text(key), /-->/);
    assert.deepEqual(t.bucket.keys("_admin_private/whatsnew/subs/"), [], "the parked copy is removed after the import");
    const mainApp = await call(t.worker, t.env, "GET", "/movies", undefined, { auth: false });
    assert.equal(mainApp.status, 200);
    assert.ok(Array.isArray(mainApp.json) && mainApp.json.some((x) => x.id === item.id && x.video === item.video), "GET /movies (public, what the app reads) returns the new movie");
  });

  it("11/10) assigns custom categories; creates a NEW category once and reuses it afterwards", async () => {
    const t = await setup();
    const a = await t.post("/admin/discover/import", await t.moviePlan(1001, { section: { new_name: "أفلام العيد", new_type: "movie" }, categories: ["أكشن", "عيد"] }));
    assert.equal(a.status, 200, a.text);
    assert.equal(a.data.section.created, true);
    assert.equal(t.library()[0].category, "أكشن، عيد");
    assert.equal(t.library()[0].section_name, "أفلام العيد");
    const sections = (await t.get("/admin/sections")).json.sections;
    assert.equal(sections.filter((s) => s.name === "أفلام العيد").length, 1);
    const b = await t.post("/admin/discover/import", await t.moviePlan(1004, { section: { new_name: "أفلام العيد" } }));
    assert.equal(b.status, 200, b.text);
    assert.equal(b.data.section.created, false);
    assert.equal((await t.get("/admin/sections")).json.sections.filter((s) => s.name === "أفلام العيد").length, 1, "no duplicate category");
    assert.equal(a.data.section.id, b.data.section.id);
    const pub = (await call(t.worker, t.env, "GET", "/home-sections", undefined, { auth: false })).json;
    assert.ok(JSON.stringify(pub).includes("أفلام العيد"), "the new category is visible to the app");
    const none = await t.post("/admin/discover/import", await t.moviePlan(1005));
    assert.equal(none.status, 200);
    assert.equal(t.library().find((x) => x.tmdb === "1005").section_id, "", "importing without a category is allowed");
    assert.equal((await t.post("/admin/discover/import", await t.moviePlan(1002, { section: { id: "ghost" } }))).status, 422);
  });

  it("15) duplicates: re-import is refused, a retried request id is replayed, a double submit creates ONE item", async () => {
    const t = await setup();
    const plan = await t.moviePlan(1001, { request_id: "same-click" });
    const first = await t.post("/admin/discover/import", plan);
    assert.equal(first.status, 200);
    const replay = await t.post("/admin/discover/import", plan);
    assert.equal(replay.status, 200);
    assert.equal(replay.data.replayed, true);
    assert.equal(replay.data.created[0].id, first.data.created[0].id);
    assert.equal(t.library().length, 1);
    const again = await t.post("/admin/discover/import", Object.assign({}, plan, { request_id: "another" }));
    assert.equal(again.status, 409);
    assert.equal(again.json.error, "duplicate_content");
    assert.equal(again.json.error_code, "DUPLICATE_CONTENT");
    assert.equal(t.library().length, 1);
    const race = await setup();
    const p1 = await race.moviePlan(1004, { request_id: "race-a" });
    const p2 = Object.assign({}, p1, { request_id: "race-b" });
    const [x, y] = await Promise.all([race.post("/admin/discover/import", p1), race.post("/admin/discover/import", p2)]);
    assert.deepEqual([x.status, y.status].sort(), [200, 409], "exactly one of two simultaneous identical imports wins");
    assert.equal(race.library().filter((m) => m.tmdb === "1004").length, 1);
  });
});

describe("13-14 series: complete season and selected episodes", () => {
  it("13) imports a complete season: header once, every episode with its own URL, numbers and TMDB data", async () => {
    const t = await setup();
    const plan = await t.seriesPlan(2001, 1, [1, 2, 3], { section: { id: "drama" } });
    const r = await t.post("/admin/discover/import", plan);
    assert.equal(r.status, 200, r.text);
    assert.equal(r.data.header_created, true);
    assert.equal(r.data.created.length, 4, "header + 3 episodes");
    const lib = t.library();
    const header = lib.filter((x) => x.type === "series");
    assert.equal(header.length, 1);
    assert.equal(header[0].id, "series_2001");
    assert.equal(header[0].section_id, "drama");
    assert.equal(header[0].tmdb, "2001");
    const eps = lib.filter((x) => x.type === "episode").sort((a, b) => Number(a.episode) - Number(b.episode));
    assert.deepEqual(eps.map((e) => e.id), ["2001_s1e1", "2001_s1e2", "2001_s1e3"]);
    for (const e of eps) {
      assert.equal(e.series_id, "series_2001");
      assert.equal(e.season, "1");
      assert.equal(e.video, "https://vidbolt.pro/tv/2001/1/" + e.episode, "the URL belongs to exactly this episode");
      assert.equal(e.link, e.video);
      assert.equal(e.section_id, "drama");
      assert.equal(e.season_poster, "https://image.tmdb.org/t/p/w500/s1.jpg");
      assert.equal(e.duration, "00:45");
    }
    assert.deepEqual(eps.map((e) => e.title), ["الحلقة الأولى", "الحلقة الثانية", "الحلقة الثالثة"]);
    const second = await t.post("/admin/discover/import", await t.seriesPlan(2001, 2, [1]));
    assert.equal(second.status, 200, second.text);
    assert.equal(second.data.header_created, false);
    assert.equal(t.library().filter((x) => x.type === "series").length, 1);
    assert.equal(t.library().filter((x) => x.type === "episode").length, 4);
    const dup = await t.post("/admin/discover/import", await t.seriesPlan(2001, 1, [1, 2, 3]));
    assert.equal(dup.status, 409);
    assert.equal(dup.json.report.state, "duplicate");
    assert.equal(t.library().length, 5);
  });

  it("14) imports only the selected episodes (and skips ones that already exist instead of duplicating)", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/import", await t.seriesPlan(2001, 1, [1, 3]));
    assert.equal(r.status, 200, r.text);
    assert.deepEqual(t.library().filter((x) => x.type === "episode").map((e) => e.id).sort(), ["2001_s1e1", "2001_s1e3"]);
    const more = await t.post("/admin/discover/import", await t.seriesPlan(2001, 1, [1, 2]));
    assert.equal(more.status, 200, more.text);
    assert.equal(more.data.created.length, 1);
    assert.equal(more.data.created[0].episode, 2);
    assert.deepEqual(more.data.skipped.map((x) => [x.season, x.episode, x.reason]), [[1, 1, "exists"]], "the already-present episode is skipped, never duplicated");
    assert.equal(t.library().filter((x) => x.type === "episode").length, 3);
  });

  it("episode subtitles are stored per episode under the series folder and linked to the right episode", async () => {
    const t = await setup({ subdl: { 2001: true } });
    const targets = await t.episodes(2001, 1, [1, 2]);
    const eps = [];
    for (const x of targets) {
      const s = await t.post("/admin/discover/subtitles", { type: "series", tmdb_id: 2001, season: 1, episode: x.episode, lang: "ar" });
      const f = await t.post("/admin/discover/subtitles/fetch", { type: "series", tmdb_id: 2001, season: 1, episode: x.episode, lang: "ar", pick: Object.assign({}, s.data.results[0], { season: 1, episode: x.episode }) });
      assert.equal(f.data.validation.ok, true, JSON.stringify(f.data.validation));
      eps.push({ episode: x.episode, video_token: x.sources[0].token, subtitle_token: f.data.token });
    }
    const r = await t.post("/admin/discover/import", { type: "series", tmdb_id: 2001, ack_unverified: true, seasons: [{ season: 1, episodes: eps }], request_id: "subs" });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.data.subtitles_attached, 2);
    const e2 = t.library().find((x) => x.id === "2001_s1e2");
    assert.match(decodeURIComponent(e2.subtitle), /season-01\/S01E02\.ar\.srt$/);
  });
});

describe("16-18 unavailable sources and provider failures", () => {
  it("16) an unavailable video blocks the import (no silent success)", async () => {
    const t = await setup({ vidbolt: () => ({ status: 404 }) });
    const v = await t.video(1001);
    assert.equal(v.verdict, "unavailable");
    const r = await t.post("/admin/discover/import", { type: "movie", tmdb_id: 1001, movie: { video_token: v.token || "" }, ack_unverified: true, request_id: "u1" });
    assert.equal(r.status, 422);
    assert.equal(r.json.report.state, "missing_video");
    assert.equal(t.library().length, 0);
    assert.equal(t.bucket.json("_admin_private/whatsnew/imports.json"), null, "nothing recorded as imported");
  });

  it("17) unavailable subtitles: import still works without them; 'required' turns it into Subtitle Missing", async () => {
    const t = await setup();
    const s = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1002, lang: "auto" });
    assert.equal(s.data.results.length, 0);
    const r = await t.post("/admin/discover/import", await t.moviePlan(1002));
    assert.equal(r.status, 200);
    assert.equal(t.library()[0].subtitle, "");
    const req = await t.post("/admin/discover/import", await t.moviePlan(1004, { options: { subtitle_required: true } }));
    assert.equal(req.status, 422);
    assert.equal(req.json.report.state, "subtitle_missing");
  });

  it("18) TMDB failures: down -> 502 after retries, 429 -> retried, bad key -> 502, missing key -> 503", async () => {
    const t = await setup({ tmdbDown: true });
    const down = await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(down.status, 502);
    assert.equal(down.json.error, "tmdb_unavailable");
    assert.ok(t.world.state.requests.length >= 3, "retried before giving up");
    const feed = await t.post("/admin/discover/feed", { feed: "movie:new_releases" });
    assert.equal(feed.status, 502, "a failed provider is an error, not an empty list");
    t.world.state.tmdbDown = false;
    t.world.state.tmdb429Once = true;
    const limited = await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(limited.status, 200, "429 is waited out and retried");
    const bad = await setup({ tmdbBadKey: true });
    const b = await bad.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(b.status, 502);
    assert.equal(b.json.error, "bad_tmdb_key");
    const nokey = await setup({}, { TMDB_API_KEY: "" });
    const n = await nokey.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(n.status, 503);
    assert.equal(n.json.error, "missing_tmdb_key");
    assert.ok(!JSON.stringify(n.json).includes("tmdb-test-key"));
  });

  it("a failed commit leaves movies.json untouched (all-or-nothing) and reports Import Failed context", async () => {
    const t = await setup();
    const plan = await t.moviePlan(1001);
    const before = t.bucket.text("movies.json");
    const realPut = t.bucket.put.bind(t.bucket);
    t.bucket.put = async (key, value, options) => (key === "movies.json" ? null : realPut(key, value, options)); // every compare-and-swap loses
    const r = await t.post("/admin/discover/import", plan);
    t.bucket.put = realPut;
    assert.equal(r.status, 409);
    assert.equal(r.json.error, "write_conflict");
    assert.equal(t.bucket.text("movies.json"), before);
    const retry = await t.post("/admin/discover/import", Object.assign({}, plan));
    assert.equal(retry.status, 200, "the same request can simply be retried");
    assert.equal(t.library().length, 1);
  });
});

describe("19-20 main app visibility and existing administration features", () => {
  it("19) imported content is served to the main app, ordered newest first, and survives the existing admin edit/undo flows", async () => {
    const t = await setup();
    const imp = await t.post("/admin/discover/import", await t.moviePlan(1001, { section: { id: "action" } }));
    const id = imp.data.created[0].id;
    const app = (await call(t.worker, t.env, "GET", "/movies", undefined, { auth: false })).json;
    assert.equal(app[0].id, id);
    const dup = await t.post("/admin/duplicates/check", { type: "movie", tmdb: "1001" });
    assert.equal(dup.data.duplicate, true, "the classic duplicate checker sees the imported movie");
    const upd = await t.post("/admin/item/update", { id, changes: { quality: "1080p" } });
    assert.equal(upd.status, 200, upd.text);
    assert.equal(t.library()[0].quality, "1080p");
    const hist = await t.get("/admin/history");
    assert.ok(JSON.stringify(hist.json).includes("What's New"), "the import is in the undo journal");
    const undo = await t.post("/admin/undo", { id: imp.data.undo_id });
    assert.equal(undo.status, 200, undo.text);
    assert.equal(t.library().filter((x) => x.id === id).length, 0, "the existing undo removes the imported movie");
  });

  it("20) existing admin routes keep working next to the new ones", async () => {
    const t = await setup();
    assert.equal((await t.get("/admin/ping")).status, 200);
    assert.equal((await t.get("/admin/sections")).status, 200);
    assert.equal((await t.get("/admin/meta")).status, 200);
    assert.equal((await t.get("/admin/subs/status")).data.subdl, true);
    const create = await t.post("/admin/item/create", { type: "movie", fields: { title: "Manual movie", video: "https://example.com/a.mp4", year: "2024" } });
    assert.equal(create.status, 200, create.text);
    const search = await t.post("/admin/tmdb/search", { type: "movie", query: "1001" });
    assert.equal(search.status, 200);
    assert.equal((await call(t.worker, t.env, "GET", "/nope", undefined, { auth: false })).status, 404);
    const sec = await t.post("/admin/sections/create", { name: "Manual", slug: "manual", id: "manual", type: "movie" });
    assert.equal(sec.status, 200, sec.text);
  });

  it("a series import records fallbacks and a replayable ledger entry; the history counts the import", async () => {
    const t = await setup({ cdn: (u) => (u.pathname === "/files/a.mp4" ? new Response((() => { const b = new Uint8Array(2048); b.set([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70]); return b; })(), { status: 206, headers: { "content-type": "video/mp4", "content-range": "bytes 0-2047/9000000" } }) : new Response("no", { status: 404 })) });
    const r = await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001, custom_url: "https://cdn.example.com/files/a.mp4" });
    const sources = r.data.targets[0].sources;
    assert.equal(sources[0].provider, "custom");
    const plan = { type: "movie", tmdb_id: 1001, movie: { video_token: sources[0].token, fallback_tokens: [sources[1].token] }, ack_unverified: false, request_id: "fb" };
    const imp = await t.post("/admin/discover/import", plan);
    assert.equal(imp.status, 200, imp.text);
    assert.equal(t.library()[0].video, "https://cdn.example.com/files/a.mp4");
    const ledger = t.bucket.json("_admin_private/whatsnew/imports.json").records[0];
    assert.equal(ledger.fallbacks.movie[0].url, "https://vidbolt.pro/movie/1001");
    assert.equal(ledger.videos[0].verdict, "playable");
    const hist = await t.get("/admin/discover/history");
    assert.equal(hist.data.total_imports, 1);
    assert.equal(hist.data.recent_imports[0].title, "فيلم 1001");
  });
});

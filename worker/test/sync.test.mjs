// Synchronisation, cache-only dashboard summary, cron scheduling, history and throttling tests.
// External services are mocks — see helpers.mjs.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { loadWorker, makeEnv, createWorld, call, movieRow, tvRow, pending } from "./helpers.mjs";

async function setup(over = {}, envExtra = {}) {
  const worker = await loadWorker();
  const env = makeEnv(envExtra);
  const world = createWorld(over);
  const calls = world.install();
  const t = { worker, env, world, calls, bucket: env.MOVIES_BUCKET, post: (p, b, o) => call(worker, env, "POST", p, b, o), get: (p, o) => call(worker, env, "GET", p, undefined, o) };
  t.cfg = (patch) => t.post("/admin/discover/config", patch);
  return t;
}

describe("manual synchronisation", () => {
  it("fills the discovery index, records history, and the dashboard summary reads ONLY the cache", async () => {
    const t = await setup();
    await t.cfg({ feeds: ["movie:new_releases", "tv:recent_episodes"] });
    const empty = await t.get("/admin/discover/summary");
    assert.equal(empty.data.synced, false);
    assert.equal(empty.data.counts.tracked, 0);

    const r = await t.post("/admin/discover/sync", { enrich: 4 });
    assert.equal(r.status, 200, r.text);
    const run = r.data.run;
    assert.equal(run.status, "ok");
    assert.equal(run.trigger, "manual");
    assert.equal(run.discovered, 4, "2 movies + 2 series");
    assert.equal(run.new_items, 4);
    assert.equal(run.new_movies, 2);
    assert.equal(run.new_series, 2);
    assert.equal(run.enriched, 4);
    assert.equal(run.videos_resolved, 4, "all four embeds answered 200 (reachable)");
    assert.equal(run.subtitles_available, 1, "only movie 1001 has an Arabic subtitle in the mock");
    assert.equal(run.errors.length, 0);

    const before = t.calls.length;
    const sum = await t.get("/admin/discover/summary");
    assert.equal(t.calls.length, before, "the dashboard card never calls TMDB / providers");
    assert.equal(sum.data.synced, true);
    assert.equal(sum.data.counts.new_movies, 2);
    assert.equal(sum.data.counts.new_series, 2);
    assert.equal(sum.data.counts.ready, 4);
    assert.equal(sum.data.available, 4);
    assert.equal(sum.data.counts.missing_subtitle, 3);
    assert.equal(sum.data.latest.length, 4);

    const feed = await t.post("/admin/discover/feed", { feed: "ready" });
    assert.equal(feed.data.source, "index");
    assert.equal(feed.data.cards.length, 4);
    assert.equal(feed.data.cards[0].video.state, "ok");
    const missingSub = await t.post("/admin/discover/feed", { feed: "missing_subtitle", type: "movie" });
    assert.deepEqual(missingSub.data.cards.map((c) => c.tmdb_id), [1002]);
    const recent = await t.post("/admin/discover/feed", { feed: "recently_added" });
    assert.equal(recent.data.cards.length, 4);
    const hist = await t.get("/admin/discover/history");
    assert.equal(hist.data.runs.length, 1);
    assert.equal(hist.data.runs[0].discovered, 4);
    assert.ok(hist.data.last_success_at);
  });

  it("detects NEW releases on the next run and does not count known items twice (duplicate detection)", async () => {
    const t = await setup();
    await t.cfg({ feeds: ["movie:new_releases"] });
    await t.post("/admin/discover/sync", { enrich: 0 });
    const again = await t.post("/admin/discover/sync", { enrich: 0 });
    assert.equal(again.data.run.new_items, 0);
    assert.equal(again.data.run.discovered, 2);
    t.world.state.movies[1006] = movieRow(1006);
    t.world.state.discoverMovie = [1006, 1001, 1002];
    const third = await t.post("/admin/discover/sync", { enrich: 0 });
    assert.equal(third.data.run.new_items, 1);
    assert.equal(third.data.run.new_movies, 1);
    const idx = t.bucket.json("_admin_private/whatsnew/index.json");
    assert.equal(Object.keys(idx.items).length, 3);
    assert.ok(idx.items["movie:1006"].first_seen_at);
    const hist = await t.get("/admin/discover/history");
    assert.deepEqual(hist.data.runs.map((r) => r.new_items), [1, 0, 2]);
  });

  it("marks library titles as imported, tracks series with newer aired episodes than the library has", async () => {
    const t = await setup();
    t.bucket.seedJson("movies.json", [
      { id: "series_2001", type: "series", series_id: "series_2001", tmdb: "2001", title: "S", poster: "p" },
      { id: "2001_s1e1", type: "episode", series_id: "series_2001", season: "1", episode: "1", title: "e1", video: "u" },
    ]);
    await t.cfg({ feeds: ["tv:recent_episodes"] });
    const r = await t.post("/admin/discover/sync", { enrich: 4 });
    assert.equal(r.data.run.status, "ok");
    const idx = t.bucket.json("_admin_private/whatsnew/index.json");
    assert.equal(idx.items["series:2001"].imported, true);
    assert.equal(idx.items["series:2001"].enrich.episodes_pending, 1, "S2E1 aired 5 days ago and is not in the library");
    assert.ok(!idx.items["series:2001"].enrich.video, "imported titles are not probed for video again");
    const sum = await t.get("/admin/discover/summary");
    assert.equal(sum.data.counts.recent_episodes, 1);
    const imported = await t.post("/admin/discover/feed", { feed: "imported" });
    assert.deepEqual(imported.data.cards.map((c) => c.key), ["series:2001"]);
  });

  it("a feed that fails is recorded as an error (partial run) while the others still complete", async () => {
    const t = await setup();
    await t.cfg({ feeds: ["movie:new_releases", "tv:recent_episodes"] });
    const real = t.world.handler;
    let calls = 0;
    t.world.state.requests.length = 0;
    const orig = globalThis.fetch;
    globalThis.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input.url;
      if (url.includes("/discover/tv")) return new Response("{}", { status: 500 });
      calls++;
      return orig(input, init);
    };
    const r = await t.post("/admin/discover/sync", { enrich: 0 });
    globalThis.fetch = orig;
    assert.equal(r.status, 200);
    assert.equal(r.data.run.status, "partial");
    assert.equal(r.data.run.discovered, 2);
    assert.ok(r.data.run.errors.some((e) => e.op.startsWith("feed:tv:recent_episodes")));
    assert.ok(r.data.run.failed >= 1);
    assert.ok(calls > 0 && real);
  });

  it("TMDB not configured: the sync fails clearly and records it", async () => {
    const t = await setup({}, { TMDB_API_KEY: "" });
    const r = await t.post("/admin/discover/sync", {});
    assert.equal(r.status, 503);
    assert.equal(r.json.error, "missing_tmdb_key");
    const hist = await t.get("/admin/discover/history");
    assert.equal(hist.data.runs[0].status, "failed");
    const again = await t.post("/admin/discover/sync", {});
    assert.equal(again.status, 503, "the lease was released, so a second try runs (and fails the same way)");
  });

  it("two overlapping syncs are refused by the lease", async () => {
    const t = await setup();
    await t.cfg({ auto_sync: false });
    const doc = t.bucket.json("_admin_private/whatsnew/config.json");
    doc.lease_until = Date.now() + 120000;
    t.bucket.seedJson("_admin_private/whatsnew/config.json", doc);
    const r = await t.post("/admin/discover/sync", {});
    assert.equal(r.status, 409);
    assert.equal(r.json.error, "sync_running");
  });

  it("enrich runs in bounded batches and reports what is left", async () => {
    const t = await setup();
    await t.cfg({ feeds: ["movie:new_releases", "tv:recent_episodes"] });
    const run = (await t.post("/admin/discover/sync", { enrich: 0 })).data.run;
    const a = await t.post("/admin/discover/enrich", { limit: 3, run_id: run.id });
    assert.equal(a.data.enriched, 3);
    assert.equal(a.data.remaining, 1);
    const b = await t.post("/admin/discover/enrich", { limit: 3, run_id: run.id });
    assert.equal(b.data.enriched, 1);
    assert.equal(b.data.remaining, 0);
    const c = await t.post("/admin/discover/enrich", { limit: 3 });
    assert.equal(c.data.enriched, 0);
    const hist = (await t.get("/admin/discover/history")).data.runs[0];
    assert.equal(hist.enriched, 4, "the batches were folded into the run they belong to");
  });
});

describe("configuration and cron", () => {
  it("settings are validated and merged with defaults", async () => {
    const t = await setup();
    const g = await t.get("/admin/discover/config");
    assert.equal(g.data.config.auto_sync, true, "first-hand updates are on by default");
    assert.equal(g.data.config.notify_telegram, true);
    assert.equal(g.data.config.interval_minutes, 60);
    assert.equal(g.data.config.video_providers[0].id, "vidbolt");
    assert.equal(g.data.providers.tmdb, true);
    assert.equal(g.data.providers.subdl, true);
    assert.equal(g.data.providers.opensubtitles, false);
    for (const bad of [{ interval_minutes: 5 }, { interval_minutes: 99999 }, { feeds: ["movie:nope"] }, { enrich_limit: 99 }, { language: "fr-FR" }, { region: "SAU" }, { subtitle_languages: [] }]) {
      assert.equal((await t.cfg(bad)).status, 400, JSON.stringify(bad));
    }
    const ok = await t.cfg({ auto_sync: true, interval_minutes: 120, enrich_limit: 6, region: "sa", subtitle_languages: ["ar", "en", "fr"], subtitle_required: true, language: "en-US" });
    assert.equal(ok.status, 200);
    assert.equal(ok.data.config.interval_minutes, 120);
    assert.equal(ok.data.config.region, "SA");
    assert.equal(ok.data.config.language, "en-US");
    assert.ok(JSON.stringify(g.json).indexOf("tmdb-test-key") < 0 && JSON.stringify(g.json).indexOf("subdl-test-key") < 0, "secrets never leave the Worker");
  });

  it("the every-minute cron runs the sync only when auto-sync is on and the interval has elapsed", async () => {
    const t = await setup();
    const tick = async () => { const p = pending(); await t.worker.scheduled({}, t.env, p.ctx); await p.flush(); };
    const runs = () => (t.bucket.json("_admin_private/whatsnew/history.json") || { runs: [] }).runs;
    await t.cfg({ auto_sync: false });
    await tick();
    assert.equal(runs().length, 0, "auto-sync switched off: the tick only leaves its heartbeat");
    assert.equal((await t.get("/admin/discover/cron")).data.healthy, true);
    await t.cfg({ auto_sync: true, interval_minutes: 30, feeds: ["movie:new_releases"], enrich_limit: 2 });
    await tick();
    assert.equal(runs().length, 1);
    assert.equal(runs()[0].trigger, "cron");
    assert.ok(runs()[0].enriched > 0);
    await tick();
    assert.equal(runs().length, 1, "not again before the interval has elapsed");
    const cfg = t.bucket.json("_admin_private/whatsnew/config.json");
    cfg.last_started_at = new Date(Date.now() - 31 * 60000).toISOString();
    t.bucket.seedJson("_admin_private/whatsnew/config.json", cfg);
    await tick();
    assert.equal(runs().length, 2);
    const noTmdb = await setup({}, { TMDB_API_KEY: "" });
    const p = pending();
    await noTmdb.worker.scheduled({}, noTmdb.env, p.ctx);
    await p.flush();
    assert.equal((noTmdb.bucket.json("_admin_private/whatsnew/history.json") || { runs: [] }).runs.length, 0, "without TMDB the cron stays quiet instead of failing every minute");
  });
});

describe("throttling, caching and limits", () => {
  it("a runaway client gets 429 instead of hammering the providers", async () => {
    const t = await setup();
    let last = null;
    let first429 = -1;
    for (let i = 0; i < 70; i++) {
      last = await t.post("/admin/discover/subtitles/validate", { type: "movie", tmdb_id: 1001, lang: "ar", text: "" });
      if (last.status === 429 && first429 < 0) first429 = i;
    }
    assert.equal(last.status, 429);
    assert.equal(last.json.error, "rate_limited");
    assert.equal(first429, 60, "the 61st call inside a minute is refused");
    assert.ok(last.json.retry_after_ms > 0);
  });

  it("TMDB answers are cached: repeating a details call costs no new TMDB request", async () => {
    const t = await setup();
    await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    const n = t.world.state.requests.length;
    await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(t.world.state.requests.length, n);
  });

  it("an import plan is bounded (episodes per call) and malformed plans are rejected", async () => {
    const t = await setup();
    const many = { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: Array.from({ length: 13 }, (_, i) => ({ episode: i + 1, video_token: "x" })) }] };
    const r = await t.post("/admin/discover/import", many);
    assert.equal(r.status, 400);
    assert.equal(r.json.error, "too_many_episodes");
    assert.equal((await t.post("/admin/discover/import", { type: "series", tmdb_id: 2001, seasons: [{ season: 1, episodes: [{ episode: 1 }, { episode: 1 }] }] })).json.error, "duplicate_episode_in_plan");
    assert.equal((await t.post("/admin/discover/import", { type: "movie", tmdb_id: "1001; drop" })).status, 400);
    assert.equal((await t.post("/admin/discover/import", { type: "series", tmdb_id: 2001, seasons: [{ season: 0, episodes: [] }] })).status, 400);
  });
});

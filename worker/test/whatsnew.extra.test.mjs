// Cron heartbeat, Telegram new-release alerts and movie collections.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { loadWorker, makeEnv, createWorld, call, pending, movieRow } from "./helpers.mjs";

async function setup(over = {}, envExtra = {}) {
  const worker = await loadWorker();
  const env = makeEnv(envExtra);
  const world = createWorld(over);
  const calls = world.install();
  return { worker, env, world, calls, bucket: env.MOVIES_BUCKET, post: (p, b) => call(worker, env, "POST", p, b), get: (p) => call(worker, env, "GET", p) };
}
const tick = async (t) => { const p = pending(); await t.worker.scheduled({}, t.env, p.ctx); await p.flush(); };

describe("cron heartbeat (statistics, Telegram requests and auto-sync all depend on the Cron Trigger)", () => {
  it("reports a missing cron instead of failing silently, and healthy once a tick ran", async () => {
    const t = await setup();
    const none = await t.get("/admin/discover/summary");
    assert.equal(none.data.cron.healthy, false);
    assert.equal(none.data.cron.last_seen_at, "");
    await tick(t);
    const ok = await t.get("/admin/discover/summary");
    assert.equal(ok.data.cron.healthy, true);
    assert.ok(ok.data.cron.age_seconds < 60);
    const hb = t.bucket.json("_admin_private/whatsnew/heartbeat.json");
    hb.at = Date.now() - 20 * 60000;
    t.bucket.seedJson("_admin_private/whatsnew/heartbeat.json", hb);
    assert.equal((await t.get("/admin/discover/cron")).data.healthy, false, "20 minutes without a tick = stalled");
  });
});

describe("Telegram alert for new titles (no app needed)", () => {
  it("the cron sync sends ONE Arabic message listing the new titles, once", async () => {
    const t = await setup({}, { TELEGRAM_BOT_TOKEN: "123:abc", TELEGRAM_CHAT_ID: "555" });
    await t.post("/admin/discover/config", { feeds: ["movie:new_releases"], enrich_limit: 2 });
    await tick(t);
    const sent = t.world.state.telegram || [];
    assert.equal(sent.length, 1);
    assert.equal(sent[0].chat_id, "555");
    assert.equal(sent[0].parse_mode, "HTML");
    assert.match(sent[0].text, /جديد في ما الجديد؟/);
    assert.match(sent[0].text, /فيلم 1001/);
    assert.match(sent[0].text, /فيديو متاح/);
    const hist = (await t.get("/admin/discover/history")).data.runs[0];
    assert.equal(hist.telegram, "sent");
    const cfg = t.bucket.json("_admin_private/whatsnew/config.json");
    cfg.last_started_at = new Date(Date.now() - 2 * 3600000).toISOString();
    t.bucket.seedJson("_admin_private/whatsnew/config.json", cfg);
    await tick(t);
    assert.equal((t.world.state.telegram || []).length, 1, "nothing new -> no second message");
    t.world.state.movies[1006] = movieRow(1006);
    t.world.state.discoverMovie = [1006, 1001, 1002];
    const cfg2 = t.bucket.json("_admin_private/whatsnew/config.json");
    cfg2.last_started_at = new Date(Date.now() - 2 * 3600000).toISOString();
    t.bucket.seedJson("_admin_private/whatsnew/config.json", cfg2);
    await tick(t);
    assert.equal(t.world.state.telegram.length, 2);
    assert.match(t.world.state.telegram[1].text, /فيلم 1006/);
    assert.ok(!/فيلم 1001/.test(t.world.state.telegram[1].text), "only the NEW titles are listed");
  });

  it("can be switched off, stays quiet when Telegram is not configured, and a manual sync never sends", async () => {
    const off = await setup({}, { TELEGRAM_BOT_TOKEN: "123:abc", TELEGRAM_CHAT_ID: "555" });
    await off.post("/admin/discover/config", { notify_telegram: false, feeds: ["movie:new_releases"] });
    await tick(off);
    assert.equal((off.world.state.telegram || []).length, 0);
    const none = await setup();
    await none.post("/admin/discover/config", { feeds: ["movie:new_releases"] });
    await tick(none);
    const run = (await none.get("/admin/discover/history")).data.runs[0];
    assert.equal(run.status, "ok");
    assert.equal(run.telegram, "not_configured");
    const manual = await setup({}, { TELEGRAM_BOT_TOKEN: "123:abc", TELEGRAM_CHAT_ID: "555" });
    await manual.post("/admin/discover/config", { feeds: ["movie:new_releases"] });
    await manual.post("/admin/discover/sync", { enrich: 0 });
    assert.equal((manual.world.state.telegram || []).length, 0);
  });
});

describe("movie collections (sagas)", () => {
  it("details carry the collection; the collection route lists every part with library status", async () => {
    const t = await setup();
    const d = await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.deepEqual([d.data.collection.id, d.data.collection.name], [77, "سلسلة ألفا"]);
    assert.equal((await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1002 })).data.collection, null);
    t.bucket.seedJson("movies.json", [{ id: "x", type: "movie", tmdb: "1004", title: "Movie 1004", year: "2025" }]);
    const c = await t.post("/admin/discover/collection", { id: 77 });
    assert.equal(c.status, 200, c.text);
    assert.equal(c.data.name, "سلسلة ألفا");
    assert.equal(c.data.total, 2);
    assert.equal(c.data.in_library, 1);
    assert.deepEqual(c.data.parts.map((p) => [p.tmdb_id, p.imported]), [[1004, true], [1001, false]].sort(() => 0));
    assert.equal((await t.post("/admin/discover/collection", { id: 999 })).status, 404);
  });

  it("the collections view is built from synced movies", async () => {
    const t = await setup();
    await t.post("/admin/discover/config", { feeds: ["movie:new_releases"] });
    const empty = await t.post("/admin/discover/feed", { feed: "collections" });
    assert.equal(empty.data.cards.length, 0);
    assert.ok(empty.data.notes.length);
    await t.post("/admin/discover/sync", { enrich: 4 });
    const v = await t.post("/admin/discover/feed", { feed: "collections" });
    assert.equal(v.data.cards.length, 1);
    assert.equal(v.data.cards[0].type, "collection");
    assert.equal(v.data.cards[0].tmdb_id, 77);
    assert.equal(v.data.cards[0].parts_known, 1);
  });
});

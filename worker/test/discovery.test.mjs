// Discovery, details, video-source validation and subtitle tests for the What's New Worker section.
// Everything external (TMDB, VidBolt, subtitle providers, CDN) is a MOCK — see helpers.mjs.
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { loadWorker, makeEnv, createWorld, call, iso, arabicSrt, englishSrt, TOKEN } from "./helpers.mjs";

async function setup(over = {}, envExtra = {}) {
  const worker = await loadWorker();
  const env = makeEnv(envExtra);
  const world = createWorld(over);
  const calls = world.install();
  return { worker, env, world, calls, post: (p, b, o) => call(worker, env, "POST", p, b, o), get: (p, o) => call(worker, env, "GET", p, undefined, o) };
}

const mp4Bytes = () => { const b = new Uint8Array(2048); b.set([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d]); return b; };

describe("auth and routing", () => {
  it("every What's New route rejects a missing / wrong admin token with 401", async () => {
    const t = await setup();
    const routes = [["GET", "/admin/discover/summary"], ["GET", "/admin/discover/config"], ["POST", "/admin/discover/feed"], ["POST", "/admin/discover/details"], ["POST", "/admin/discover/video"], ["POST", "/admin/discover/subtitles"], ["POST", "/admin/discover/validate"], ["POST", "/admin/discover/import"], ["POST", "/admin/discover/sync"], ["GET", "/admin/discover/history"]];
    for (const [m, p] of routes) {
      const none = await call(t.worker, t.env, m, p, m === "POST" ? {} : undefined, { auth: false });
      assert.equal(none.status, 401, m + " " + p + " without token");
      const bad = await call(t.worker, t.env, m, p, m === "POST" ? {} : undefined, { token: "wrong" });
      assert.equal(bad.status, 401, m + " " + p + " with wrong token");
    }
    assert.equal(t.calls.length, 0, "no external request may happen before authentication");
  });

  it("health lists the new routes and the old ones are still registered", async () => {
    const t = await setup();
    const res = await call(t.worker, t.env, "GET", "/health", undefined, { auth: false });
    assert.equal(res.status, 200);
    for (const r of ["POST /admin/discover/import", "GET /admin/discover/summary", "POST /admin/item/create", "POST /admin/season/commit", "POST /admin/subs/fetch", "GET /movies"]) {
      assert.ok(res.json.routes.includes(r), "missing route " + r);
    }
  });
});

describe("1-5 discovery, details, seasons, episodes", () => {
  it("1) discovers recently released movies with real fields", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/feed", { feed: "movie:new_releases" });
    assert.equal(r.status, 200);
    assert.equal(r.data.source, "tmdb");
    assert.equal(r.data.cards.length, 2);
    const c = r.data.cards[0];
    assert.equal(c.key, "movie:1001");
    assert.equal(c.title, "فيلم 1001");
    assert.equal(c.original_title, "Movie 1001");
    assert.equal(c.poster, "https://image.tmdb.org/t/p/w342/p1001.jpg");
    assert.deepEqual(c.genres, ["أكشن", "إثارة"]);
    assert.equal(c.rating, 7.8);
    assert.equal(c.type, "movie");
    assert.equal(c.imported, false);
    assert.equal(c.video.state, "unknown");
    assert.equal(r.data.total_pages, 3);
    const q = t.world.state.requests.find((x) => x.startsWith("/3/discover/movie"));
    assert.match(q, /primary_release_date\.gte=/);
    assert.match(q, /primary_release_date\.lte=/);
    assert.match(q, /api_key=tmdb-test-key/, "the TMDB key is added by the Worker, server-side only");
  });

  it("lists TMDB genres (id + Arabic name) for the genre filter", async () => {
    const t = await setup();
    const r = await t.get("/admin/discover/genres");
    assert.equal(r.status, 200);
    assert.ok(r.data.movie.some((g) => g.id === 28 && g.name === "أكشن"));
    assert.ok(r.data.series.some((g) => g.id === 18 && g.name === "دراما"));
  });

  it("filters are validated and forwarded to TMDB (genre, language, rating, year, country)", async () => {
    const t = await setup();
    const year = iso(-10).slice(0, 4);
    const r = await t.post("/admin/discover/feed", { feed: "movie:popular", genre: "28", language: "en", min_rating: "7", year, country: "us", sort: "rating", page: 2 });
    assert.equal(r.status, 200);
    const q = t.world.state.requests.find((x) => x.startsWith("/3/discover/movie"));
    assert.match(q, /with_genres=28/);
    assert.match(q, /with_original_language=en/);
    assert.match(q, /vote_average\.gte=7/);
    assert.match(q, new RegExp("primary_release_year=" + year));
    assert.match(q, /with_origin_country=US/);
    assert.match(q, /sort_by=vote_average\.desc/);
    assert.match(q, /page=2/);
    assert.ok(!/primary_release_date\.lte/.test(q), "an explicit year replaces the rolling date window");
    for (const bad of [{ genre: "abc" }, { language: "english" }, { year: "1800" }, { min_rating: "11" }, { date_from: "yesterday" }, { sort: "random" }]) {
      const e = await t.post("/admin/discover/feed", Object.assign({ feed: "movie:popular" }, bad));
      assert.equal(e.status, 400, JSON.stringify(bad));
      assert.equal(e.json.error, "invalid_filter");
    }
    const unknown = await t.post("/admin/discover/feed", { feed: "movie:bogus" });
    assert.equal(unknown.status, 400);
  });

  it("2) discovers TV series, trending, merged lists, and newly released seasons", async () => {
    const t = await setup();
    const tv = await t.post("/admin/discover/feed", { feed: "tv:recent_episodes" });
    assert.equal(tv.data.cards.length, 2);
    assert.equal(tv.data.cards[0].type, "series");
    assert.match(t.world.state.requests.find((x) => x.startsWith("/3/discover/tv")), /air_date\.gte=/);
    const all = await t.post("/admin/discover/feed", { feed: "all:new_releases" });
    assert.deepEqual(new Set(all.data.cards.map((c) => c.type)), new Set(["movie", "series"]));
    const trending = await t.post("/admin/discover/feed", { feed: "movie:trending", language: "en" });
    assert.equal(trending.data.cards.length, 2);
    const seasons = await t.post("/admin/discover/feed", { feed: "tv:new_seasons" });
    assert.equal(seasons.data.cards.length, 2);
    assert.equal(seasons.data.cards[0].new_season, true);
    assert.equal(seasons.data.cards[0].seasons, 2);
    assert.equal(seasons.data.cards[0].latest_episode.episode, 1);
  });

  it("3) opens a movie's full details (real API data, duplicates, categories, sections)", async () => {
    const t = await setup();
    t.env.MOVIES_BUCKET.seedJson("cigram_home_sections.json", { version: 1, sections: [{ id: "action", slug: "action", name: "أكشن", type: "movie", order: 1, enabled: true, show_home: true }] });
    const r = await t.post("/admin/discover/details", { type: "movie", tmdb_id: 1001 });
    assert.equal(r.status, 200);
    const d = r.data;
    assert.equal(d.title, "فيلم 1001");
    assert.equal(d.original_title, "Movie 1001");
    assert.equal(d.imdb_id, "tt0001001");
    assert.equal(d.duration, "02:01");
    assert.equal(d.runtime_minutes, 121);
    assert.equal(d.released, true);
    assert.deepEqual(d.genres, ["أكشن", "إثارة"]);
    assert.equal(d.import.state, "new");
    assert.ok(d.sections.some((s) => s.id === "action"));
    assert.ok(d.suggested_sections.some((s) => s.id === "action"), "genre 'أكشن' matches the existing section");
    assert.match(d.suggested_slug, /^movie-1001-\d{4}$/);
    assert.equal(d.providers.video_providers[0].can_prove_playback, false);
    const missing = await t.post("/admin/discover/details", { type: "movie", tmdb_id: 424242 });
    assert.equal(missing.status, 404);
    assert.equal(missing.json.error, "tmdb_not_found");
    assert.equal((await t.post("/admin/discover/details", { type: "movie", tmdb_id: "abc" })).status, 400);
  });

  it("4) opens a series and returns its seasons (specials excluded) + latest / next episode", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/details", { type: "series", tmdb_id: 2001 });
    assert.equal(r.status, 200);
    assert.equal(r.data.season_count, 2);
    assert.deepEqual(r.data.seasons.map((s) => s.season), [1, 2]);
    assert.equal(r.data.seasons[0].episode_count, 3);
    assert.equal(r.data.latest_episode.season, 2);
    assert.equal(r.data.next_episode.episode, 2);
    assert.equal(r.data.new_season, true);
    assert.equal(r.data.import.state, "new");
  });

  it("5) returns episode information, aired flags and what is already in the library", async () => {
    const t = await setup();
    const s1 = await t.post("/admin/discover/season", { tmdb_id: 2001, season: 1 });
    assert.equal(s1.data.episodes.length, 3);
    assert.equal(s1.data.episodes[0].name, "الحلقة الأولى");
    assert.equal(s1.data.episodes[0].duration, "00:45");
    assert.ok(s1.data.episodes.every((e) => e.aired));
    const s2 = await t.post("/admin/discover/season", { tmdb_id: 2001, season: 2 });
    assert.deepEqual(s2.data.episodes.map((e) => e.aired), [true, false]);
    assert.equal(s2.data.aired_count, 1);
    assert.equal((await t.post("/admin/discover/season", { tmdb_id: 2001, season: 9 })).json.error, "season_not_found");
    t.env.MOVIES_BUCKET.seedJson("movies.json", [
      { id: "series_2001", type: "series", series_id: "series_2001", tmdb: "2001", title: "x" },
      { id: "2001_s1e2", type: "episode", series_id: "series_2001", season: "1", episode: "2", title: "e2", video: "u" },
    ]);
    const again = await t.post("/admin/discover/season", { tmdb_id: 2001, season: 1 });
    assert.deepEqual(again.data.episodes.map((e) => e.in_library), [false, true, false]);
    const det = await t.post("/admin/discover/details", { type: "series", tmdb_id: 2001 });
    assert.equal(det.data.import.state, "imported");
    assert.deepEqual(det.data.library_episodes, { 1: [2] });
  });
});

describe("6-7 video sources: find + validate (four honest levels)", () => {
  it("6) a VidBolt embed that answers 200 is only 'reachable' — never 'playable' — and needs an acknowledgement", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001 });
    assert.equal(r.status, 200);
    const target = r.data.targets[0];
    const s = target.sources[0];
    assert.equal(s.provider, "vidbolt");
    assert.equal(s.url, "https://vidbolt.pro/movie/1001");
    assert.equal(s.verdict, "embed_reachable");
    assert.equal(s.level, "reachable");
    assert.notEqual(s.level, "playable");
    assert.equal(s.needs_ack, true);
    assert.ok(s.token && s.token.includes("."));
    assert.ok(s.checks.some((c) => c.id === "playback" && c.pass === null), "playback is explicitly reported as unproven");
    assert.equal(target.best, "vidbolt");
    assert.ok(r.data.limitations[0].includes("VidBolt"));
  });

  it("a provider that blocks automated probes is 'unverified'; 404 and soft-404 pages are 'unavailable'; timeouts are 'unreachable'", async () => {
    const t = await setup({ vidbolt: (u) => (u.pathname === "/movie/1004" ? { status: 403 } : u.pathname === "/movie/1005" ? { status: 404 } : u.pathname === "/movie/1001" ? { status: 200, title: "404 Not Found" } : u.pathname === "/movie/1002" ? { throw: Object.assign(new Error("aborted"), { name: "AbortError" }) } : { status: 200 }) });
    const v = async (id) => (await t.post("/admin/discover/video", { type: "movie", tmdb_id: id })).data.targets[0];
    const blocked = await v(1004);
    assert.equal(blocked.sources[0].verdict, "unverified");
    assert.equal(blocked.sources[0].level, "generated");
    assert.ok(blocked.sources[0].token, "importable only with an explicit acknowledgement");
    const gone = await v(1005);
    assert.equal(gone.sources[0].verdict, "unavailable");
    assert.equal(gone.sources[0].token, undefined);
    assert.equal(gone.best, "");
    const soft = await v(1001);
    assert.equal(soft.sources[0].verdict, "unavailable");
    assert.match(soft.sources[0].checks.find((c) => c.id === "body").detail, /404/);
    const slow = await v(1002);
    assert.equal(slow.sources[0].verdict, "unreachable");
    assert.equal(slow.sources[0].token, undefined);
  });

  it("probe results are cached for 5 minutes; a re-check (refresh) probes again and sees the change", async () => {
    let status = 200;
    const t = await setup({ vidbolt: () => ({ status }) });
    const v1 = (await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001 })).data.targets[0].sources[0];
    assert.equal(v1.verdict, "embed_reachable");
    status = 404;
    const cached = (await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001 })).data.targets[0].sources[0];
    assert.equal(cached.verdict, "embed_reachable", "served from the short cache");
    const fresh = (await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001, refresh: true })).data.targets[0].sources[0];
    assert.equal(fresh.verdict, "unavailable", "refresh bypasses the cache");
  });

  it("an unreleased title is never probed and never gets a token", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1003 });
    assert.equal(r.data.targets[0].status, "not_released");
    assert.equal(r.data.targets[0].sources[0].token, undefined);
    assert.ok(!t.calls.some((c) => c.url.includes("vidbolt.pro")));
  });

  it("TV: each episode is resolved independently with the right season/episode; TMDB-unknown episodes are not guessed", async () => {
    const t = await setup();
    const r = await t.post("/admin/discover/video", { type: "series", tmdb_id: 2001, season: 1, episodes: [1, 2, 3, 9] });
    assert.equal(r.status, 200);
    const urls = r.data.targets.map((x) => x.sources[0].url);
    assert.deepEqual(urls.slice(0, 3), ["https://vidbolt.pro/tv/2001/1/1", "https://vidbolt.pro/tv/2001/1/2", "https://vidbolt.pro/tv/2001/1/3"]);
    assert.deepEqual(r.data.targets.map((x) => [x.season, x.episode]), [[1, 1], [1, 2], [1, 3], [1, 9]]);
    assert.equal(r.data.targets[3].status, "unavailable");
    assert.equal(r.data.targets[3].sources[0].token, undefined);
    assert.equal(new Set(r.data.targets.slice(0, 3).map((x) => x.sources[0].token)).size, 3);
    const s2 = await t.post("/admin/discover/video", { type: "series", tmdb_id: 2001, season: 2 });
    assert.equal(s2.data.targets[0].status, "embed_reachable");
    assert.equal(s2.data.targets[1].status, "not_released");
  });

  it("7) validates direct media: MP4 magic bytes, HLS master -> variant -> first segment; rejects HTML-as-video, DRM, SSRF", async () => {
    const hls = (path) => {
      if (path === "/hls/master.m3u8") return new Response("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv/index.m3u8\n", { status: 200, headers: { "content-type": "application/vnd.apple.mpegurl" } });
      if (path === "/hls/v/index.m3u8") return new Response("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg0.ts\n#EXT-X-ENDLIST\n", { status: 200, headers: { "content-type": "application/vnd.apple.mpegurl" } });
      if (path === "/hls/v/seg0.ts") return new Response(new Uint8Array(1024), { status: 206, headers: { "content-type": "video/mp2t" } });
      if (path === "/hls/drm.m3u8") return new Response('#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://k",KEYFORMAT="com.apple.streamingkeydelivery"\n#EXTINF:6,\na.ts\n', { status: 200 });
      return null;
    };
    const t = await setup({ cdn: (u) => hls(u.pathname) || (u.pathname === "/files/alpha.mp4" ? new Response(mp4Bytes(), { status: 206, headers: { "content-type": "video/mp4", "content-range": "bytes 0-2047/5000000" } })
      : u.pathname === "/files/fake.mp4" ? new Response("<html>login</html>", { status: 200, headers: { "content-type": "text/html" } })
      : u.pathname === "/files/tiny.mp4" ? new Response(mp4Bytes(), { status: 200, headers: { "content-type": "video/mp4", "content-length": "5000" } })
      : u.pathname === "/redir.mp4" ? new Response(null, { status: 302, headers: { location: "https://192.168.1.1/x.mp4" } })
      : new Response("nope", { status: 404 })) });
    const custom = async (url, extra = {}) => (await t.post("/admin/discover/video", Object.assign({ type: "movie", tmdb_id: 1001, custom_url: url }, extra))).data.targets[0].sources.find((s) => s.provider === "custom");
    const mp4 = await custom("https://cdn.example.com/files/alpha.mp4");
    assert.equal(mp4.verdict, "playable");
    assert.equal(mp4.level, "playable");
    assert.equal(mp4.needs_ack, false);
    assert.ok(mp4.checks.some((c) => c.id === "container" && c.pass === true));
    const best = (await t.post("/admin/discover/video", { type: "movie", tmdb_id: 1001, custom_url: "https://cdn.example.com/files/alpha.mp4" })).data.targets[0];
    assert.equal(best.best, "custom", "a verified playable file outranks the unprovable embed");
    assert.equal(best.fallbacks[0].provider, "vidbolt", "the embed is kept as a fallback");
    assert.equal((await custom("https://cdn.example.com/files/fake.mp4")).verdict, "invalid");
    assert.equal((await custom("https://cdn.example.com/files/tiny.mp4")).verdict, "invalid");
    assert.equal((await custom("https://cdn.example.com/files/missing.mp4")).verdict, "unavailable");
    assert.equal((await custom("https://cdn.example.com/hls/master.m3u8")).verdict, "playable");
    const drm = await custom("https://cdn.example.com/hls/drm.m3u8");
    assert.equal(drm.verdict, "drm");
    assert.equal(drm.token, undefined);
    for (const bad of ["https://127.0.0.1/a.mp4", "https://10.0.0.5/a.mp4", "https://localhost/a.mp4", "ftp://cdn.example.com/a.mp4", "https://user:pw@cdn.example.com/a.mp4", "https://cdn.example.com:22/a.mp4"]) {
      const s = await custom(bad);
      assert.equal(s.verdict, "invalid", bad);
      assert.equal(s.token, undefined, bad);
    }
    const redirect = await custom("https://cdn.example.com/redir.mp4");
    assert.equal(redirect.importable, false);
    assert.ok(!t.calls.some((c) => c.url.includes("192.168.1.1")), "a redirect to an internal host must never be fetched");
    const ep = (await t.post("/admin/discover/video", { type: "series", tmdb_id: 2001, season: 1, episodes: [3], custom_url: "https://cdn.example.com/files/Show.S01E02.mp4" })).data.targets[0].sources.find((s) => s.provider === "custom");
    assert.equal(ep.verdict, "mismatch");
    assert.equal(ep.token, undefined);
  });

  it("provider templates are validated when saved (https only, {tmdb} required, no private hosts)", async () => {
    const t = await setup();
    const save = (p) => t.post("/admin/discover/config", { video_providers: [p] });
    assert.equal((await save({ id: "a", movie: "https://ok.example.com/m/{tmdb}", tv: "https://ok.example.com/t/{tmdb}/{season}/{episode}" })).status, 200);
    assert.equal((await save({ id: "a", movie: "http://ok.example.com/m/{tmdb}" })).status, 400);
    assert.equal((await save({ id: "a", movie: "https://ok.example.com/m/" })).status, 400);
    assert.equal((await save({ id: "a", tv: "https://ok.example.com/t/{tmdb}" })).status, 400);
    assert.equal((await save({ id: "a", movie: "https://10.1.2.3/m/{tmdb}" })).status, 400);
    assert.equal((await save({ id: "a", movie: "https://user@ok.example.com/m/{tmdb}" })).status, 400);
  });
});

describe("8-9 subtitles: search, download, validate", () => {
  it("8) finds Arabic subtitles by external id, downloads + unzips + validates, and parks a token", async () => {
    const t = await setup();
    const s = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "ar" });
    assert.equal(s.status, 200);
    assert.equal(s.data.lang, "ar");
    assert.equal(s.data.results.length, 1);
    assert.equal(s.data.results[0].source, "subdl");
    assert.equal(s.data.results[0].language, "ar");
    const f = await t.post("/admin/discover/subtitles/fetch", { type: "movie", tmdb_id: 1001, lang: "ar", pick: s.data.results[0] });
    assert.equal(f.status, 200);
    assert.equal(f.data.validation.ok, true);
    assert.equal(f.data.cues, 150);
    assert.equal(f.data.format, "srt");
    assert.match(f.data.preview, /مرحبا/);
    assert.match(f.data.token, /^[0-9a-f]{32}$/);
    assert.ok(t.env.MOVIES_BUCKET.text("_admin_private/whatsnew/subs/" + f.data.token + ".srt").includes("-->"));
  });

  it("no result is reported as no result; other languages are searched when asked; missing keys are explained", async () => {
    const t = await setup({ subdlEn: true });
    const none = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1002, lang: "ar" });
    assert.equal(none.status, 200);
    assert.equal(none.data.results.length, 0);
    assert.equal(none.data.best, null);
    const en = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "en" });
    assert.equal(en.data.results.length, 1);
    assert.equal(en.data.results[0].language, "en");
    const enFetch = await t.post("/admin/discover/subtitles/fetch", { type: "movie", tmdb_id: 1001, lang: "en", pick: en.data.results[0] });
    assert.equal(enFetch.data.validation.ok, true);
    const auto = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "auto" });
    assert.equal(auto.data.lang, "ar", "auto prefers Arabic");
    const t2 = await setup({}, { SUBDL_API_KEY: "" });
    const nokeys = await t2.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "ar" });
    assert.equal(nokeys.status, 400);
    assert.equal(nokeys.json.error, "subs_no_keys");
    assert.equal((await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "arabic" })).status, 400);
    assert.equal((await t.post("/admin/discover/subtitles/fetch", { type: "movie", tmdb_id: 1001, lang: "ar", pick: { source: "evil", id: "x" } })).status, 400);
    assert.equal((await t.post("/admin/discover/subtitles/fetch", { type: "movie", tmdb_id: 1001, lang: "ar", pick: { source: "subdl", id: "../../etc/passwd" } })).status, 400);
  });

  it("a provider error while searching is reported, not turned into 'found'", async () => {
    const t = await setup({ subdlError: true });
    const r = await t.post("/admin/discover/subtitles", { type: "movie", tmdb_id: 1001, lang: "ar" });
    assert.equal(r.status, 200);
    assert.equal(r.data.results.length, 0);
    assert.match(JSON.stringify(r.data.status), /error|quota/i);
  });

  it("9) validates SRT structure: empty, too few cues, wrong language, bad timing, VTT, oversize, base64, season-pack episode mismatch", async () => {
    const t = await setup();
    const v = async (text, lang = "ar", extra = {}) => (await t.post("/admin/discover/subtitles/validate", Object.assign({ type: "movie", tmdb_id: 1001, lang, text }, extra)));
    const good = await v(arabicSrt(150));
    assert.equal(good.status, 200);
    assert.equal(good.data.validation.ok, true);
    assert.equal(good.data.cues, 150);
    assert.ok(good.data.token);
    const empty = await v("");
    assert.equal(empty.data.validation.ok, false);
    assert.equal(empty.data.validation.issues[0].code, "empty");
    assert.equal(empty.data.token, "");
    const few = await v(arabicSrt(3));
    assert.equal(few.data.validation.ok, false);
    assert.equal(few.data.validation.issues[0].code, "too_few_cues");
    const wrong = await v(englishSrt(150), "ar");
    assert.ok(wrong.data.validation.issues.some((i) => i.code === "wrong_language"));
    assert.equal(wrong.data.token, "");
    assert.equal((await v(englishSrt(150), "en")).data.validation.ok, true);
    const vtt = "WEBVTT\n\n" + arabicSrt(150).replace(/,(\d{3})/g, ".$1");
    const asVtt = await v(vtt);
    assert.equal(asVtt.data.validation.ok, true, "VTT input is converted to a clean SRT");
    const reversed = arabicSrt(150).replace(/(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})/g, "$2 --> $1");
    assert.equal((await v(reversed)).data.validation.ok, false);
    const garbage = await v("this is not a subtitle file at all\njust some text");
    assert.equal(garbage.data.validation.ok, false);
    const b64 = Buffer.from(arabicSrt(120), "utf8").toString("base64");
    const viaFile = await t.post("/admin/discover/subtitles/validate", { type: "movie", tmdb_id: 1001, lang: "ar", base64: b64 });
    assert.equal(viaFile.data.validation.ok, true);
    const huge = await v("x".repeat(3100000));
    assert.equal(huge.status, 413);
    const longer = await v(arabicSrt(150).replace(/\d{2}:\d{2}:\d{2},\d{3}/g, "10:00:00,000"));
    assert.equal(longer.data.validation.ok, false);
    const runtimeWarn = await v(arabicSrt(150));
    assert.ok(runtimeWarn.data.validation.warnings.some((w) => w.code === "shorter_than_runtime"), "a 10-minute file for a 121-minute movie is flagged");
    const ep = await t.post("/admin/discover/subtitles/fetch", { type: "series", tmdb_id: 2001, season: 1, episode: 2, lang: "ar", pick: { source: "subdl", id: "/subtitle/2001-AR.zip", season: 1, episode: 3 } });
    assert.equal(ep.data.validation.ok, false);
    assert.ok(ep.data.validation.issues.some((i) => i.code === "episode_mismatch"));
    assert.equal(ep.data.token, "");
  });
});

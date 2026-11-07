# «ما الجديد؟» — What's New? (Smart Content Discovery & Import)

Discovery of new movies / series / episodes from TMDB, automatic video-source and subtitle search with **honest**
validation, a pre-import report, and one-tap **إضافة إلى Cigram** — built into the existing Cigram Admin (Android, Java)
and the existing `cigram-admin-api` Cloudflare Worker. No second content database: imported titles are written to the
same `movies.json` with exactly the fields the classic add screens write.

```
Admin app (Android)            cigram-admin-api Worker                       Providers
─────────────────────          ─────────────────────────────────────         ──────────────
Dashboard card  ──────────────▶ GET  /admin/discover/summary (R2 cache only)
WnActivity (discover) ────────▶ POST /admin/discover/feed  ───────────────▶ TMDB discover/trending
WnDetailActivity ─────────────▶ POST /admin/discover/details, /season ────▶ TMDB details (ar + en)
   video  ────────────────────▶ POST /admin/discover/video ───────────────▶ VidBolt page / direct mp4,m3u8
   subtitles ─────────────────▶ POST /admin/discover/subtitles[/fetch] ───▶ SubDL / SubSource / OpenSubtitles
   report ────────────────────▶ POST /admin/discover/validate
   «إضافة إلى Cigram» ────────▶ POST /admin/discover/import ──▶ movies.json (one compare-and-swap write)
WnBulkActivity ───────────────▶ same calls, one title at a time, failures isolated
WnHistoryActivity ────────────▶ GET /admin/discover/history, config, POST /admin/discover/sync
                                scheduled(): wnCronTick()  (existing every-minute Cron Trigger)
```

## What was added

### Worker (additive — nothing existing was changed)
`worker/baseline/` is the live Worker exactly as deployed (2026-11-03). `worker/src/*.js` is the new section.
`node worker/build.mjs` splices it into the baseline with **four anchored edits** (it refuses to build if an anchor is
missing or ambiguous): the section before `MAIN ROUTER`, one router line (`WN_ROUTES`), one health-list line, one cron
line. The deployable result is `worker/dist/cigram-admin-api.worker.js`.

| Route (all admin-token only) | Purpose |
|---|---|
| `GET  /admin/discover/summary` | Dashboard numbers. **Reads the cached index only — never calls TMDB.** |
| `GET/POST /admin/discover/config` | Auto-sync, interval, enrichment limit, feeds, language/region, video providers |
| `POST /admin/discover/feed` | TMDB feeds (movies: new/upcoming/popular/trending · TV: recent episodes/new seasons/popular/trending · mixed) with filters genre, language, country, year, rating, date range, sort, paging; or cached views: recently added, ready, unverified, missing video, missing subtitle, already imported |
| `GET  /admin/discover/genres` | TMDB genre ids + Arabic names (filter) |
| `POST /admin/discover/details` | Real TMDB details, seasons, latest/next episode, library status, sections, categories |
| `POST /admin/discover/season` | Episodes of one season + aired flag + «in library» flag |
| `POST /admin/discover/video` | Find + validate video sources for a movie / chosen episodes (≤ 8 per call) |
| `POST /admin/discover/subtitles` · `/fetch` · `/validate` | Search · download + clean + validate · validate a hand-supplied file |
| `POST /admin/discover/validate` | Pre-import report (states below) |
| `POST /admin/discover/import` | Idempotent import (movie, or series header + chosen episodes, ≤ 12 per call) |
| `POST /admin/discover/sync` · `/enrich` | Manual synchronisation (bounded batches) |
| `GET  /admin/discover/history` | Sync runs (time, discovered, new, videos, subtitles, imported, failures, errors) |

### Android app
`WnActivity` (discovery center, cinematic poster grid, filters, infinite scroll, multi-select) ·
`WnDetailActivity` (details → video → subtitles → category/section → validation → import; series seasons/episodes) ·
`WnBulkActivity` (bulk import with per-title progress and independent retry) ·
`WnHistoryActivity` (sync history + settings) · `WnHomeCard` (dashboard card) · `WnApi` / `WnPipeline` / `WnLogic` / `WnUi`.
Also touched: `AndroidManifest.xml` (4 activities), `DxHomeActivity` (card), `DxSectionActivity` (row in «المحتوى»).

## The honesty rules (what the product promises)

**Video — four levels, never conflated**

| Level | Meaning | How it is reached |
|---|---|---|
| `generated` | URL built from the TMDB id | always, for template providers |
| `resolved` | the provider itself confirmed it has the title | only for a provider with a lookup API (**VidBolt has none**) |
| `reachable` | the provider page answered 200 with a non-error HTML page | an HTTP GET from the Worker |
| `playable` | a real media file / HLS playlist was fetched and parsed | MP4 `ftyp` / WebM-MKV magic, size ≥ 100 KB, or m3u8 → variant → first segment |

VidBolt (`https://vidbolt.pro/movie/{tmdb}`, `/tv/{tmdb}/{season}/{episode}`) is an **iframe player**. The Worker can only
prove the page loads, so VidBolt sources reach at most `reachable`; a provider that refuses automated probes
(401/403/429) is `unverified`. Both require an **explicit acknowledgement** («أقرّ وأريد الاستيراد») before import and
are recorded as such in the import ledger. 404 / soft-404 pages, timeouts, DRM-protected playlists (`SAMPLE-AES`,
FairPlay/PlayReady/Widevine — never bypassed), HTML served as "video", wrong container and URLs belonging to another
episode are rejected. Validation results are **HMAC-signed** (key derived from `ADMIN_TOKEN`, 30-minute expiry) and the URL
saved on an item always comes from the signed payload — the app cannot claim a source is playable, and an episode token
cannot be used for another season/episode.

**Subtitles** — «downloaded/validated» is only reported after bytes were fetched, decoded (UTF-8/16, Windows-1256,
mojibake repair), cleaned to SRT and checked (≥ 5 cues, timing, ordering, language share, runtime plausibility,
episode match). Arabic uses the Worker's existing pipeline (SubDL → SubSource → OpenSubtitles); other languages use a
generic SubDL/OpenSubtitles search. Hand-pasted or picked `.srt/.vtt` files are validated the same way.

**Import states** — `Ready to Import`, `Missing Video`, `Missing Metadata`, `Subtitle Missing`, `Invalid Subtitle`,
`Duplicate Content`, `Import Failed`, `Successfully Imported`. A subtitle is optional unless «اشتراط وجود ترجمة» is on
(existing items already play without one).

**Idempotent, atomic import** — one `mutateMovies` compare-and-swap write per call (all-or-nothing, with the existing undo
journal and backup); `request_id` replays the first answer on a retry/double tap; the duplicate guard (TMDB / IMDb / title+year)
and episode collision checks mean two simultaneous identical imports create exactly one item. Series are imported in
chunks of ≤ 12 episodes; the header is created once. Imports appear in **سجل العمليات** and can be undone with the existing undo.

## Configuration

No new secrets are required. Already used by the Worker:

| Name | Needed for |
|---|---|
| `ADMIN_TOKEN` (secret) | all admin routes + signing validation tokens |
| `TMDB_API_KEY` **or** `TMDB_READ_TOKEN` (secret) | discovery, details, seasons |
| `SUBDL_API_KEY` (secret) | Arabic + other-language subtitles (main) |
| `SUBSOURCE_API_KEY`, `OPENSUBTITLES_API_KEY` (+ `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`) | optional extra subtitle sources |
| `MOVIES_BUCKET` (binding) | content + new `whatsnew/*` files |
| `ADMIN_BUCKET` (optional private R2 binding) | **recommended**: keeps the ledger, fallbacks and parked subtitles out of the public `r2.dev` bucket |

New R2 objects (no schema change to `movies.json`): `_admin_private/whatsnew/config.json`, `index.json`, `history.json`,
`imports.json`, `subs/<token>.srt|json` (parked subtitles, purged after 24 h). With `ADMIN_BUCKET` bound the
`_admin_private/` prefix is dropped.

**Android token:** the admin token is not in the source (the repo is public). The app asks for it once on the home screen
(button «الرمز» to change it later) and keeps it only in the phone's private preferences. It must equal the Worker's `ADMIN_TOKEN`.

## Deploying the Worker

1. `cd worker && npm test` (builds `dist/` and runs the tests).
2. Compare the live Worker with `worker/baseline/…` (Cloudflare dashboard → `cigram-admin-api` → Quick edit, or
   `wrangler`). If it changed after 2026-11-03, re-export it over `baseline/` and run `node worker/build.mjs`; the build
   fails loudly if an anchor moved.
3. Deploy `worker/dist/cigram-admin-api.worker.js` (module syntax, same bindings/secrets as today). Example with wrangler:
   `wrangler deploy worker/dist/cigram-admin-api.worker.js --name cigram-admin-api --compatibility-date <your current date>`
   — keep the bindings you already have (`MOVIES_BUCKET`, `ADMIN_BUCKET`, `REQUESTS_KV`, …); `wrangler` deploys do not
   keep dashboard-only bindings unless they are in your `wrangler.toml`.
4. **Cron Trigger:** the Worker already needs one (`* * * * *`, "every minute", for the update center). «ما الجديد؟» piggybacks:
   each tick reads one small R2 object and returns at once unless *auto-sync* is ON and its interval has elapsed. With a
   coarser cron (e.g. every 5 min) auto-sync simply runs at that granularity.
5. Subrequest budget: a sync = feeds (≤ 4×pages) + `enrich_limit` titles × (1 probe + ≤ 3 subtitle searches). The default
   `enrich_limit = 8` stays under 50 subrequests (Free plan); raise it on Paid.
6. Open the admin app → **المحتوى → ما الجديد؟ → مزامنة الآن**.

## Using it (administrator)

1. **الرئيسية → بطاقة «ما الجديد؟»** (or المحتوى → ما الجديد؟). First time: press **مزامنة الآن** to fill the numbers.
2. Pick a filter chip (الكل · أفلام · مسلسلات · إصدارات جديدة · الأكثر رواجاً · أُضيف حديثاً · متاح للاستيراد · بلا فيديو · بلا ترجمة · مستورد مسبقاً · قريباً · مواسم جديدة) and/or **فلترة متقدمة**.
3. Tap a card → details open and the app **automatically** searches and validates the video, then the subtitle.
   Choose another source, paste/replace the subtitle, or check your own authorized URL if needed.
4. Pick the section (existing, **＋ قسم جديد**, or none) and the categories (several allowed).
5. Read **التحقق قبل الاستيراد**. If it says an acknowledgement is required (embedded player), tick **أقرّ وأريد الاستيراد**.
6. **إضافة إلى Cigram.** For series, tick seasons/episodes first (default: the newest season's unseen aired episodes).
7. Bulk: long-press cards to select several → **استيراد مجمّع** → options → **ابدأ**; retry any failed title alone.
8. **السجل والإعدادات**: sync history, auto-sync switch, interval, enrichment limit, which providers are configured.

## Tests (what was actually run)

* `cd worker && npm test` — **49 tests** (Node 22) against the real Worker code on an in-memory R2 with ETag compare-and-swap, with
  TMDB / VidBolt / CDN / subtitle providers **mocked**: discovery & filters, details/seasons/episodes, the four video levels,
  SSRF + redirect guard, DRM refusal, subtitle search/download/validation, categories (existing/new/none), movie import,
  complete season, selected episodes, duplicates, replay, double submit, failure injection (TMDB down/429/bad key/missing key,
  provider 404/403/timeout, failed commit), `GET /movies` visibility, existing admin routes + undo, sync/index/history, cron,
  lease, rate limiting, caching, plan bounds, token forgery/expiry. Ten deliberate code mutations were all caught.
* Java (JUnit 4, `admin/app/src/test`) — **29 tests**: `WnLogicTest` (pure logic) and `WnPipelineTest`, which runs the Android
  pipeline over real HTTP against the real Worker started by `worker/test/serve.mjs` (needs `node`; skipped otherwise).
* Every Android class was compiled with `javac` against the Android 14 framework jar (the app's other classes still compile).

## Known limitations (please read)

* **Live providers were not exercised.** The build environment had no TMDB / SubDL / OpenSubtitles credentials and its
  network policy blocked `vidbolt.pro`; all provider behaviour above is verified against mocks and the documented/observed
  shapes in the existing Worker, not against the real services.
* **The Android screens were compiled but not run** on a device or emulator (no Android SDK offline), and the Gradle build
  was not run. Layout, touch behaviour and `testOptions` in `build.gradle.kts` are unverified on-device.
* **VidBolt can never reach «playable».** It has no title-lookup API and serves an iframe; see the honesty rules.
* «Recently added» is **first seen by Cigram's discovery index** in the last 14 days (TMDB has no creation-date listing);
  it fills after a sync.
* Fallback sources are kept in the import ledger only; the main app plays `video`. Switch with the normal edit screen.
* Rate limits and the 5-minute probe / 10-minute TMDB caches are per Worker isolate (a courtesy floor, not a global quota).
* "Appears in the main Cigram app" is verified at the contract level (`GET /movies`, `/home-sections`, undo, duplicates); the
  user app binary was not available.
* Importing a single title sends the existing «new content» push (as the classic add screens do); bulk defaults to no push.

# CIGRAM 18.2.1 / Admin 2.0.1 — Performance, Stability, Cloudflare Data Architecture and Chat Authentication

Branch `ccr-d05a3c3e-7l3krw` of `tv-arch6/cigram`. Main app `com.Cigram.vid` **versionCode 90 / 18.2.1** (was 89 / 18.2.0), admin app
`com.my.newproject` **versionCode 8 / 2.0.1** (was 7 / 2.0). Package names, flavors (`play`, `direct`), targetSdk (main 36, admin 34),
content ids, preferences and Worker endpoints are unchanged.

Status words used below:
- **VERIFIED** — implemented and checked here (JVM tests, Node tests against the real Worker code, a run on the emulator, or a real public service).
- **IMPLEMENTED, NOT PRODUCTION-TESTED** — implemented and compiled/tested locally, but it needs the deployed Workers, a real account or a real device.
- **NOT TESTED** — not run at all (listed in §12). Nothing in this report is claimed "passed" unless it was run.

---

## 0. Read this first
1. **The update cannot be signed here.** The only keystore on the machine is the Android SDK's auto-generated `debug.keystore` (alias
   `androiddebugkey`). No production keystore, no `keystore.properties` (only `keystore.properties.example`), no passwords and no previous
   production APK exist in the archives or the environment. I did not generate or use any other key. The release APK / AAB files are **unsigned**
   (`apksigner verify` → "DOES NOT VERIFY: Missing META-INF/MANIFEST.MF"). They will install as an update **only after you sign them with the
   original key** (§10).
2. **Nothing is deployed.** There is no write access to Cloudflare. Three Worker bundles are delivered with exact steps (`docs/backend/DEPLOY.md`).
   The apps work against today's Workers; the Worker changes add the server half of conditional requests and fix the chat authentication on the server.
3. **No Android 8–16 device / ANR measurement.** The only runtime available was one API 31 emulator without hardware acceleration. I did not
   prove that ANRs are fixed (§5).

## 1. Audit — what was found (before any change)
Extracted `HASAN-FINAL-2.ZIP` and `ADMIN-FINAL.ZIP`, read the build files, manifests, the code paths below and the live Worker sources
(`cigram-admin-api`, `cigram-ads-api`, `cigram-auth-api`, `sigram-channels-api`, read-only through the Cloudflare connector).

| # | Finding | Evidence |
|---|---|---|
| A1 | **Chat "Session expired" — client:** the chat layer (`CigramAds`, `AdsWs`, `AdsMedia`, `AdsNotifier`) used the raw stored token and never used `CigramSession`, the app's own silent renewal (sealed credentials, renew before expiry / on 401) that the reactions feature uses. The auth Worker's sessions are opaque, last a fixed 30 days and are not extended. | `CigramAds.run()` read `CigramUserData.token()`; `CigramSession.renewBlocking` had no caller in the chat classes |
| A2 | **Chat "Session expired" — server:** `cigram-ads-api` `verifyUser` answered `401 invalid_token` for **every** failure of its call to the auth Worker's `/me` (timeout, 429, 5xx, wrong binding, malformed answer); the app prints that 401 as «انتهت الجلسة، سجّل الدخول من جديد». | live source; reproduced: stub `/me`→500 gives `401 invalid_token` on the live code (§3) |
| A3 | The WebSocket treated HTTP **403** (e.g. restricted chat) like a lost session. | `AdsWs.connect` |
| B1 | `CigramTabRefresh` called `refreshCatalogNow()` → `requestEarlyCatalogRefresh(…, coldStart = true)`, which **skips the 5-minute freshness window**; any return to Home/Search more than 2 minutes after the last download re-downloaded, re-parsed, re-indexed and re-saved the 5.3 MB catalogue. The Search page itself says "no request within 10 minutes". | `CigramStage1InitProvider` lines ~812–840, `CigramTabRefresh` |
| B2 | `CigramTabRefresh` forced `CigramChannelStore.refresh(app, true)` on every Channels tab show (bypassing its 3-minute window); the channel request also used a cache-busting `?_=<time>` and no validator. | `CigramChannelStore.refresh/download` |
| B3 | `SectionActivity` (and the TV page) called `loadCatalogJsonBlocking()` which was **network-first**: every section page opened downloaded the full catalogue, then `prepareAndStoreCatalog` re-prepared and re-saved it even when unchanged. | `SectionActivity` ~2291, `CigramStage1InitProvider.loadCatalogJsonBlocking` |
| B4 | Every Home (re)creation re-ran the whole warm-up (`prepareCatalog` + `installCatalogInMemory` + two multi-MB preference writes + image preload) on the saved catalogue even when the process already held the same one. | `CigramStage1InitProvider` ~1255–1275 |
| B5 | `MainActivity.cgStartCatalogRequest` started its own 5 MB download whenever the early download had finished more than 2 minutes ago. Measured on the emulator: 5 catalogue requests in the first 150 s (§11). | log of the previous build |
| B6 | The home carousel fetched `/carousel?ts=<now>` on every Home creation and rebuilt every slide even when the answer was identical; slides were not de-duplicated. | `MainActivity` carousel loader |
| B7 | `CigramHomeOrder.plan` (admin section order): a later duplicate of an id / slug / name silently overwrote the earlier entry's position. Name-only items of a section whose other items carry an id formed a second section ("name:x" next to "id:x"). | `CigramHomeOrder`, `addDynamicSectionItem` |
| B8 | Both Workers send the R2 `ETag` but ignore `If-None-Match`; `/channels` also sends `cache-control: no-store`. So no 304 was possible. | live Worker sources |
| C1 | Players: movie player started after a fixed **0.7 s** and resumed after a stall with **1 s**; live **1.5 s / 3 s**; together player 2 s / 4 s. None of them depended on the network. | `PlayerActivity`, `CigramLivePlayer` |
| C2 | `CigramSegmentAccel`: always 2–4 parallel range requests per piece, 2–4 pieces look-ahead, fixed 64 MB store, whatever the network. | source |
| C3 | The three players did not request **audio focus** (a call or another app did not pause playback); live and together did not handle "becoming noisy". | source (only `CigramChannelPlayer` did) |
| D1 | Admin: `CgData.load()` started `/movies` (5 MB) + `/admin/meta` + `/admin/sections` for every caller; the dashboard timer, «تحديث» and other screens could run them concurrently; an answer requested before an edit could overwrite the edited snapshot. | `CgData`, `DxHomeActivity` |
| D2 | Admin channels screen re-ran its whole load behind a blocking dialog on every `onResume`. | `CgChannelsActivity` |
| D3 | `CgHttp.apiGet` retries used a fixed 300/900 ms (no jitter). | source |
| E1 | **Existing and kept** (verified by reading): catalogue validation before saving (`isValidCatalog`), 3 attempts with back-off, memory + prefs cache, search index, image LRU + disk cache + in-flight de-dupe per URL + decode sampling + stale-view guard, incremental building of the channel pages (16 ms steps) and `onDestroyed` clean-up of callbacks/listeners, crash-loop recovery and memory trimming (`CigramStability`). | source |
| E2 | `DxHomeActivity.onResume` does **not** call `reloadContent()` on every resume (`TokenGate.ensure` only prompts when no token is stored); the load that ran too often was the 2-minute timer. | source |
| F1 | **Security (not part of this brief, found while reading):** admin-private R2 files live in the public bucket (already reported in 18.2). | see `docs/backend/DEPLOY.md` §4 |

Real-catalogue facts (public `movies.json`, 2026-11-08): 5,371,956 bytes, 5,322 records (147 movies, 60 series headers, 5,115 episodes), **no duplicate
content id**, 10 distinct (section id, section name) pairs, no id/name conflicts.

## 2. Cloudflare-first data layer (what changed, using the existing mechanisms)
No second cache or repository was added; the existing catalogue / channel stores were given one shared policy and the missing protections.

| Item | Status | How |
|---|---|---|
| One refresh policy (`CigramRefreshPolicy`, pure Java) | **VERIFIED** (12 tests) | single flight; TTL per reason (cold start 2 min, tab/resume 5 min, channel list 3 min, ×3 on metered / low-RAM); no snapshot → at most every 15 s; exponential back-off after failures (30 s … 15 min, ±25 % jitter, a permanent 4xx uses the cap); a clock that moved backwards cannot block; the user's explicit refresh always goes through |
| Tabs no longer force downloads | **VERIFIED** (code + emulator) | `CigramTabRefresh`: catalogue via the policy, channels `refresh(app, false)`; the `onDone` listener is kept only when a refresh is really running (it used to be queued forever and could pin an Activity) |
| Section / TV pages cache-first | **VERIFIED** (code) | `loadCatalogJsonBlocking`: a snapshot confirmed within the TTL is returned without network; a stale one is refreshed by **one** download (a second caller waits instead of downloading again); inside a back-off the saved copy is used |
| Validated snapshot, never replaced by junk | **VERIFIED** (existing + kept) | `isValidCatalog` before saving; an error / empty / HTML body counts as a failure and never replaces the saved snapshot |
| Conditional requests | **IMPLEMENTED, NOT PRODUCTION-TESTED** | catalogue and channel list send `If-None-Match` with the saved ETag; **HTTP 304** keeps the snapshot and only restarts its age; the 200 path stores the ETag. Today's Workers ignore the header, so the app also compares the hash of the normalised body and skips parse / index / save / redraw when identical |
| Worker side | **VERIFIED locally** (not deployed) | `cigram-admin-api` `GET /movies` and `sigram-channels-api` `GET /channels` now answer `If-None-Match` with 304 through R2's `onlyIf` evaluation, `cache-control: no-cache` (clients revalidate each time, so a title added in the admin app is visible at once). Tests: `admin_subhub_test.mjs` (304 / full body after a change), `channels_conditional_test.mjs` |
| Warm-up skipped when already done | **VERIFIED** (code + emulator) | Home recreation no longer re-prepares a catalogue the process already holds (compares the saved hash with the in-memory hash) |
| MainActivity's own download | **VERIFIED** (emulator) | skipped while the snapshot is fresh |
| Carousel | **VERIFIED** (tests + code) | de-duplicated by id (else link + image, first copy wins), not re-fetched within 2 minutes, not re-rendered or widget-refreshed when the answer is identical |
| Obsolete requests / bounded concurrency / timeouts | existing + kept | the catalogue uses one `NET_IO` executor, posters `POSTER_IO`, connect 10 s / read 15–25 s; I did not add a second executor |
| Offline & fresh-vs-cached | **VERIFIED** (emulator) | offline start with a saved catalogue renders the cached sections (§11); the admin app marks cached snapshots «محفوظة» (existing) |
| Not done | | per-endpoint cache classes for **images** (already separate), private account data (never cached by this layer) |

## 3. Private chat authentication — root cause and fix (item 14)
**Root cause (two defects that add up):** (A1) the chat used the raw stored token without `CigramSession`'s silent renewal, so an expired/revoked
30-day session showed «انتهت الجلسة» in the chat while other features renewed silently; (A2) the Worker turned any failure of its `/me` call into
`401 invalid_token`, which the app prints as the same message.

| Fix | Where | Status |
|---|---|---|
| Renew before the token expires, renew once on a 401 and retry the **same body** once (the message keeps its `client_id`, so the Worker stores it once); several failing requests share one renewal | `AdsAuthGateway` + `CigramAds.request` | **VERIFIED** (13 JVM tests incl. a local HTTP server and 8 concurrent requests → 1 login) |
| "Session expired" only when the server still refuses after a renewal, or no credentials exist to renew with; a renewal that failed for another reason is "temporary, your account is still logged in" | `AdsAuthGateway` | **VERIFIED** |
| Timeouts, offline, 5xx, 429, malformed answers, 403 are never authentication results; the stored session is never cleared by a chat request (the login screen clears only the expired marker on the user's own tap) | gateway, `AdsChatActivity.setSessionBanner/openLogin` | **VERIFIED** (tests) |
| Chunked uploads use the freshest token and renew once on a 401 | `AdsMedia.putPartWithRetry` | compiled, **NOT TESTED** end to end |
| WebSocket: a 401 handshake triggers one shared renewal and an immediate reconnect; 403 is no longer an auth failure | `AdsWs` | compiled, **NOT TESTED** end to end |
| Unsent messages stay queued (never dropped, never looped) while the session is expired and are sent after login; history is cached locally and untouched | `AdsChatActivity` | **VERIFIED** (code), emulator earlier showed the queued state |
| Worker: only a real 401/403 from `/me` is `401 invalid_token`; everything else is `503 auth_unavailable`; 6 s timeout; 30-minute grace for a token verified before an outage (revocation is checked again as soon as `/me` answers); one log line per failure with no token / id | `docs/backend/cigram-ads-api/verify_user.js` | **VERIFIED locally** (12 new tests, 23 total), **NOT deployed** |
| Identity is taken from the verified token, never from the client | existing | **VERIFIED** (test: a user claiming another id in a header and the body still lands in their own thread) |

Before / after on the **same stubbed auth Worker** (`before_after_auth.mjs`): `/me`→401: live 401 / patched 401; `/me`→500: live **401 invalid_token** / patched
**503 auth_unavailable**; `/me`→network error: live **401** / patched **503**.

**Verification list of the brief:** items 1–10 exist as tests of the logic (JVM + Node); the steps that need a real account (open chat without
logging in again, load history, send text, send an attachment, reopen, simulate network failure and expiry **against the deployed Workers**) are
**NOT TESTED in production** — no deployment access and no user credentials. One possibility I cannot verify from here: if the deployed
`cigram-ads-api` has no working `AUTH_API` service binding, **every** chat request would fail the `/me` check; after deploying, the new `chat_auth`
log lines show it (`DEPLOY.md` §7).

## 4. Every section and carousel once
Rules (deterministic, in `CigramSectionDedupe`): a section is identified by its id; items that carry only the section **name** join the id-section of the
same normalised name (an id wins); two different ids with the same title stay two sections; the admin registry's **first** entry of an id / slug / name decides
position and visibility; a repeated id is one section; carousel slides are identified by id (else link + image), the first occurrence keeps its place.
Content ids are never changed or invented.

| Check | Status |
|---|---|
| Alias / precedence / registry first-wins / carousel de-dupe / ids unchanged (8 unit tests) | **VERIFIED** |
| The app's **own** grouping code run over the **real catalogue** (reflection, JVM): 7 dynamic sections, 107 cards, every section id and title once, every item once per section | **VERIFIED** |
| Updating existing views in place instead of rebuilding Home | **NOT DONE** — the existing renderer removes and re-adds the tagged block on a changed catalogue (it already skips the redraw when the hash is unchanged); rewriting it would be a redesign of a 14,000-line class |

## 5. ANR / UI freezes — what was and was not done
Done: the repeated heavy work was removed (§2: no forced downloads, no repeated warm-up, no re-prepare on section pages), the catalogue grouping already runs
on a background thread with a saved snapshot, and a debug-only watchdog now reports main-thread stalls. **Not claimed:** that ANRs are fixed.
- **Added `CigramPerf`:** slow-step logging (≥ budget, one line per label per 30 s), duplicate-request counter (same dataset twice within 10 s is logged),
  and — **debuggable builds only** — log-only StrictMode plus a main-thread watchdog (> 700 ms). No dialogs, nothing in release builds.
- Existing and kept: incremental page building, view reuse, debounced search, `onDestroyed` clean-up, `CigramStability` memory trimming.
- Emulator evidence is in §11 (one run each, a CPU-starved software emulator: indicative only).
- **Not done:** moving every remaining main-thread read in the 14,000-line init provider and the Sketchware-generated activities; no RecyclerView migration (the
  channel pages already build incrementally; the Home is a hand-built layout whose ids and handlers must stay).

## 6. Admin app (2.0.1)
| Change | Status |
|---|---|
| `CgData.load` is **single flight** (`CgFlight`): a call during a running load joins it; `loadIfStale(max age)`; the dashboard timer skips a snapshot younger than 90 s | **VERIFIED** (5 JVM tests incl. 12 concurrent callers → fewer request sets than callers, everyone answered once) |
| An answer requested before a local edit never replaces the edited snapshot (edit counter) | **VERIFIED** (decision tested) |
| Background refresh backs off while the server fails (2, 4, 8, 10 min cap) instead of every 2 min forever | **VERIFIED** (delay function tested) |
| `CgHttp.apiGet` retries with ±25 % jitter; a 4xx is never retried | compiled |
| Channels screen: one load at a time, silent refresh on resume (≥ 30 s), redraw only when the data changed, no blocking dialog | compiled, **NOT TESTED** on a device |
| Authentication, uploads, section / carousel / channel editing | unchanged |

## 7. Images
Audited the poster / logo pipeline: it already has a byte-sized memory LRU, a disk cache, one download per URL (waiting views share it), a 12 MB cap,
`inSampleSize` decoding, a per-view token that stops an old response from appearing in a recycled card, placeholders, a low-RAM guard on the preload. **One
fix:** a 200 answer that is not an image is deleted instead of staying in the disk cache. Not changed (not needed or too invasive): priority of visible images,
cancelling in-flight requests of recycled views (the token already prevents a wrong result), an extra loader.

## 8. Playback on weak connections
| Item | Status |
|---|---|
| `CigramAdaptiveLoadControl` (subclass of `DefaultLoadControl`) used by the movie, live and watch-together players: start / re-buffer thresholds follow the network (type, metered, the system's link estimate) and real stalls of the last 3 minutes: good 0.8 s / 1.5 s (movies), weak 2.5 s / 5 s, very weak 4 s / 8 s (live 1.5/3 → 2.5/4 → 3/5); level only rises after real stalls, relaxes after 5 quiet minutes; the device-class window (RAM) is the ceiling; metered networks cap the look-ahead at 40 s; nothing is restarted, recreated or re-prepared, so the position is untouched | **VERIFIED** (12 JVM tests: thresholds, stalls, recovery, low-RAM, consistency of the window for 54 combinations) |
| Bitrate ceiling for multi-quality (HLS/DASH) sources on a weak network at the start (a manual choice overrides it; a single MP4 has no other quality — nothing claims otherwise) | compiled, **NOT TESTED** on a stream |
| `CigramSegmentAccel` follows the same verdict: good 4 connections / 4 pieces ahead / 64 MB; weak 2 / 2 / 32 MB; very weak **1 / 1 / 16 MB**; metered ≤ 2 pieces ahead and ≤ 32 MB; low-RAM halves the store | **VERIFIED** (policy tests incl. "the load control publishes its verdict to the accelerator") |
| Audio focus (call / other app pauses, resumes afterwards) and "becoming noisy" handling added to the movie, live and together players | compiled, **NOT TESTED** on a device |
| Bounded retries with back-off + jitter, a manual retry button, accurate buffering indicator | **NOT DONE** for the movie player (its error/retry flow is a polling design inside a 9,600-line activity; the stock retry policy and `CigramLoadPolicy` stay). The refresh policy's back-off covers the data requests |
| Player lifecycle | audited: release in `onStop` + `onDestroy` safety net (movie), release on teardown (live, together) — unchanged |

## 9. Preserved
Package ids, content ids, series / episode relationships, SharedPreferences (no key removed or reset), watch history, favourites, subtitles, advertising, analytics,
Arabic RTL, navigation routes and the CodeAssist `androidx.viewbinding.ViewBinding` stub (kept; excluded from Gradle's compiler only).

## 10. Versions and signing
- `versionCode` 90 / 18.2.1 (main) and 8 / 2.0.1 (admin); `aapt2 dump badging` of the release APKs confirms the package names and codes.
- **Signing: NOT DONE, and not claimed.** Searched the filesystem for `*.jks`, `*.keystore`, `keystore.properties`, `signing.properties`: only
  `/root/.android/debug.keystore` (SDK debug key — never a production key). There is no previous production APK to compare a fingerprint with. Required from the owner: the
  **original keystore + alias + passwords** of each package (or, if Play App Signing is used, the upload key registered in the Play Console), then
  `apksigner sign --ks <store> …`, `apksigner verify --print-certs` and a SHA-256 comparison with the installed app's certificate, then install over the existing app.
- Unsigned artifacts are delivered separately (not inside the source archives).

## 11. Before / after measurements
| Measure | Before | After | How measured |
|---|---|---|---|
| Catalogue body | 5,371,956 bytes per request | same body; **0 bytes** (HTTP 304) once the Worker patch is deployed, otherwise the same download but no parse/index/save/redraw when unchanged | real file |
| org.json parse / SHA-256 of that body (JVM, desktop CPU) | 92 ms / 50 ms | the hash check (50 ms) replaces a re-prepare of about 1 s (grouping alone: 1,031–1,055 ms JVM) | JUnit on the real catalogue |
| Catalogue downloads in 30 min of navigation (one page change every 30 s) | **15** | **5** (metered: **1**) | `CigramRefreshPolicyTest` simulation of the old rule vs the new policy |
| Ten quick page changes within a minute | downloads whenever the last success was > 2 min ago | **0** | test |
| Chat: failing auth check `/me`→500 | `401 invalid_token` (shown as "session expired") | `503 auth_unavailable` (session kept) | Node, same stub |
| Emulator, same seed, offline cold start, first 150 s (**one run each, software emulator, indicative only**) | 5 catalogue requests (4 × `fetchCatalog` + 1 `MainActivity`), frames of 32.8 s, 4.4 s, 3.9 s, 0.9 s, Home shows the **error screen** despite the saved catalogue (`qa-screens/18.2.1/01_…`) | 2 catalogue requests, longest frame 1.7 s, Home draws the cached sections (`02_…`) | logcat (`CigramDiag`, `Davey!`), screenshots |
| Player start threshold, good network | 0.7 s start / 1 s re-buffer | 0.8 s / 1.5 s | code |
| Player start threshold, weak network | 0.7 s / 1 s | 2.5 s / 5 s (very weak 4 s / 8 s) | code + tests |
| Accelerator on a very weak link | 2–4 connections per piece, 2–4 pieces ahead, 64 MB | 1 connection, 1 piece ahead, 16 MB | code + tests |
| Admin: concurrent catalogue loads | one per caller | one | tests |

## 12. Tests and builds
| Suite | Result |
|---|---|
| Main app JVM tests | **Without a catalogue file (what you get from the ZIP): 73 tests, 71 passed, 0 failed, 2 skipped.** The two skipped tests are `CigramSectionDedupeTest.realCatalogue*`: they need a real `movies.json`, which is production data and is not in the archive (`CIGRAM_CATALOGUE=<movies.json>` or `-Dcigram.catalogue=…`). **With the real catalogue (5.37 MB copy of the live file, run once): 73 passed, 0 failed, 0 skipped.** Per class: `SubHubTest` 24, `AdsAuthGatewayTest` 13, `AdaptiveBufferingTest` 12, `CigramRefreshPolicyTest` 12, `CigramSectionDedupeTest` 9, `NetworkClientSecurityTest` 3 |
| Admin app JVM tests | **57 tests, 56 passed, 0 failed, 1 skipped (pre-existing)** — `SubHubTest` 24, `AdminSubHubTest` 12, `CgFlightTest` 5, `WnLogicTest` 15, `WnPipelineTest` 1 (skipped by its own source) |
| Worker tests (Node, real Worker code, stubs) | `admin_subhub_test` **31**, `ads_support_test` **23**, `channels_conditional_test` **6** — all passed; `deploy/SHA256SUMS` verified |
| Clean builds | main: `testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease assembleDirectDebug assembleDirectRelease bundlePlayRelease` → exit 0; admin: `testDebugUnitTest assembleDebug assembleRelease bundleRelease` → exit 0 |

How these numbers were obtained: the archives were extracted into fresh directories and built there. `gradle.properties` enables the Gradle build cache, which can replay
test results from an earlier run of identical inputs, so the test counts above come from a second run with `--no-build-cache` (`cleanTest… test…`), i.e. the tests were really executed.
Nothing was run against a deployed Worker.

Artifact sizes (bytes): main `app-play-debug` 20,973,354 · `app-play-release-unsigned` 17,767,685 · `app-direct-debug` 20,974,530 · `app-direct-release-unsigned` 17,768,681 ·
`app-play-release.aab` 16,662,417 · admin `app-debug` 8,136,383 · `app-release-unsigned` 6,591,696 · `app-release.aab` 5,997,083.

**NOT TESTED** (explicit): Android 8–16 devices; low-memory device profile; slow-mobile and unstable-Wi-Fi playback; offline navigation on a device beyond the one emulator start;
repeated open/close of Channels; long playback sessions; channel switching; backgrounding / returning to the player; audio focus; the chunked chat upload and
WebSocket renewal against a real server; any request to a deployed Worker; leak detection (no LeakCanary run); the activity-destruction listener tests (the code already
removes callbacks in `onDestroyed`; no automated test was written); low-memory image decoding (existing code reviewed, no test); signing and update-over-install.

## 13. Remaining limitations and owner actions
1. **Sign** both apps with the original keystores (§10) and verify the certificate fingerprints before publishing.
2. **Deploy** the three Worker bundles (`BACKEND-FINAL.ZIP`, `DEPLOY.md`); then check the `chat_auth` log lines and the 304 responses (§7 of `DEPLOY.md`). Make sure `cigram-ads-api` has the `AUTH_API` service binding.
3. Test on real Android 15/16 and a low-RAM phone, on a slow connection, and play long sessions; run the chat flow with a real account against the deployed Workers.
4. Not done by design or limits: in-place Home updates, movie-player retry/back-off UI, a RecyclerView migration, admin-private R2 exposure (18.2 report), Keystore-backed token storage.
5. The admin app targets API 34 (unchanged; raising it is a product decision, the edge-to-edge helper is ready).

## 14. Archives and artifacts (verified)

Verification (2026-11-09): each ZIP passed `unzip -t`; contains no keystore, `local.properties`, build output or secret-like text (scan for private-key blocks, API-key shapes, the owner's known credential string); extracted into fresh directories;
Worker bundles from `BACKEND-FINAL.ZIP` passed the shipped Node tests (31 + 23 + 6) and `sha256sum -c deploy/SHA256SUMS`; main and admin built from the extracted ZIPs with exit 0 (main `clean testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease assembleDirectDebug assembleDirectRelease bundlePlayRelease`, admin `clean testDebugUnitTest assembleDebug assembleRelease bundleRelease`);
tests re-run with `--no-build-cache` (counts in §12). The ZIPs differ from the verified extraction only by this report file (`diff -r`).

| Archive | Bytes | SHA-256 |
|---|---|---|
| `HASAN-FINAL-2.ZIP` | 10906331 | `97efa3ec5aa0febbbebc9891f69c313ba2ac374ff2f3e436bcdc3043f519fcae` |
| `ADMIN-FINAL.ZIP` | 4682165 | `72f26d7a8373779a61566d01d3097b72c3e26f16d76d292e7aed358ad163ea11` |
| `BACKEND-FINAL.ZIP` | 330050 | `4caac35a69094e3eba0d0e826e427b579964e565abd3e9bb892988274eed35f0` |

Generated artifacts (built from the extracted ZIPs). **The release files are UNSIGNED** (`apksigner verify` → `DOES NOT VERIFY`): no original production keystore exists in this environment or the archives you gave me, and I did not create one. Sign them with your original keystore before publishing; the debug APKs are signed with the SDK debug key and are for testing only.

| File | Package / version | Bytes | SHA-256 |
|---|---|---|---|
| `cigram-admin-debug.apk` | com.my.newproject 8 / 2.0.1 | 8136383 | `48b65894e2c9e0fda5d4ef4dbcced5afcdf22936339c9f709c55086c944f239f` |
| `cigram-admin-release-UNSIGNED.aab` | com.my.newproject 8 / 2.0.1 | 5997083 | `c16b231e467123b2182b9ffef432b89ae5fc8a5ecb2295e83fa2490dcbb02865` |
| `cigram-admin-release-UNSIGNED.apk` | com.my.newproject 8 / 2.0.1 | 6591696 | `9b6425c73b2925f84452eb026ecaaaa7088fdd7327a0152b48b3f87df124593d` |
| `cigram-direct-debug.apk` | com.Cigram.vid 90 / 18.2.1 | 20974530 | `96b1e4e583596053eab8ff58f784192dbdb64e3787fff5e43f66f08e4b2f0fc4` |
| `cigram-direct-release-UNSIGNED.apk` | com.Cigram.vid 90 / 18.2.1 | 17768681 | `01b1a519cf63bdab60977a8f15f1a7179e9b6f63256684ff8d23519bde8d39fb` |
| `cigram-play-debug.apk` | com.Cigram.vid 90 / 18.2.1 | 20973354 | `81ef332706d1dcd734c90bcbafa8390be90b6fa07847865e695693cfaff9607c` |
| `cigram-play-release-UNSIGNED.aab` | com.Cigram.vid 90 / 18.2.1 | 16662417 | `e6f8cf9a85136580867008451003c0fa72d38b9bf089bf783e5b6976a1c5f43f` |
| `cigram-play-release-UNSIGNED.apk` | com.Cigram.vid 90 / 18.2.1 | 17767685 | `b706fa8c58895387df7393c80a81732f24fcfa67646fb418a69faf8a54c96cd5` |

Git: branch `ccr-d05a3c3e-7l3krw`; the archives were built from the committed tree (`git archive HEAD`) plus `docs/`, screenshots and reports.

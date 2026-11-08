# CIGRAM — Final Completion Audit & Implementation Report

Continuation of the 18.2 delivery (`FINAL-IMPLEMENTATION-REPORT.md` stays valid for Phases 2–5 of that work).
Branch `ccr-d05a3c3e-7l3krw` of `tv-arch6/cigram`. Package names, flavors and versions are unchanged
(main `com.Cigram.vid` 18.2.0 (89), admin `com.my.newproject`).

Status words (exactly as requested):
- **VERIFIED** — implemented and verified here: automated tests, a run against the real public service, or the emulator.
- **NOT PRODUCTION-TESTED** — implemented and compiled / locally tested, but it needs the deployed Workers, a real account or a real device.
- **NEEDS CREDENTIALS** — implemented, but a live success cannot exist until the owner supplies the named credential.
- **NOT IMPLEMENTED** — with the reason.

---

## 0. What this pass found and did (short)
1. **Network access.** The sandbox blocked every provider host. I asked for (and the owner's approval prompt granted) an allowlist for exactly:
   `api.opensubtitles.com`, `ai.opensubtitles.com`, `opensubtitles.stoplight.io`, `api.subdl.com`, `dl.subdl.com`, `subdl.com`,
   `api.themoviedb.org`, `developer.themoviedb.org` and the public catalogue host `pub-eabe41d14a0644e5800344c99ef4d971.r2.dev`.
   That made it possible to read the **official OpenSubtitles reference** and to run the real adapter code against the **real servers**.
2. **Correction of the previous report.** The live `cigram-admin-api` already contained working SubDL / SubSource / OpenSubtitles
   *search + download* adapters (written by the owner). The earlier statement "no provider adapter exists" was only true for
   **translation**. This pass audited those adapters against the official reference, fixed what deviated, and added translation.
3. **The real catalogue was read** (public `movies.json`, 5.3 MB, 2026-11-08 22:37 UTC). The "about 3,800 untranslated titles" figure is **not what the database says** (see §2).
4. **Security findings** (one fixed in the app, two need the owner) — §5.
5. **Not done, by hard limits:** no deployment (no write tool for Cloudflare), no authenticated provider call (no credentials), no Android 15/16 device (only an API 31 emulator image exists).

## 1. Deliverables
| File | Content |
|---|---|
| `HASAN-FINAL.ZIP` | main app source (folder `cigram/`), `docs/` (backend + tools + screenshots), both reports |
| `ADMIN-FINAL.ZIP` | admin app source (folder `admin/`), same docs |
| `BACKEND-FINAL.ZIP` | Cloudflare deployment files: `deploy/` bundles of both Workers, patches, tests, `DEPLOY.md`, `build_deploy.sh`, live-probe evidence |
| `FINAL-COMPLETION-REPORT.md` (this) | report + checklist |
Sizes and SHA-256 are in §7. Release APK/AAB outputs are **unsigned** (no keystore exists in the project or archives).

## 2. The real catalogue (Phase 2: "inspect the existing database")
Read-only, from the same public file the apps read. Reproducible with `docs/tools/catalogue_audit.py <movies.json or URL>`;
today's aggregates are in `docs/tools/catalogue_audit_2026-11-08.json` (no titles, no raw data stored).

| Measure | Count |
|---|---|
| Records | 5,322 = 147 movies + 60 series headers + 5,115 episodes |
| Playable titles (movies + episodes) | **5,262** |
| With a subtitle file (`.srt` 218, `.vtt` 9) | **227** |
| **Without any subtitle** | **5,035** (83 movies + 4,952 episodes, in 58 of the 60 series) |
| …with a TMDB or IMDb id (own, or from the series header / `series_<tmdbId>`) | **5,025** |
| …with no id at all (never searched by name: the Worker answers `subs_ids_required`) | 10 |
| Largest backlog | one series alone has 1,905 episodes without a subtitle |

So the workload is **5,035, not ~3,800**. The app's Subtitle Center computes the same number at runtime from the live catalogue
(rule: movie or episode with an empty `subtitle`), and one queue job per real title is created: nothing is invented.
**Not claimed:** none of these titles has been processed — that needs the deployed Workers plus provider credentials.

## 3. Feature-by-feature checklist

### Phase 1 — Arabic subtitle system
| # | Requirement | Status | Evidence / what is missing |
|---|---|---|---|
| 1.1 | OpenSubtitles integration (search, login, download) | **VERIFIED** (protocol) · **NEEDS CREDENTIALS** (live success) | Rewritten from the official reference; 30 Worker tests enforce Api-Key, `<app> vX` User-Agent, both headers on `/download`, sorted lowercase params, no leading zeros, base_url, 401 policy, throttle. Real-server run (`docs/backend/LIVE-PROBE-RESULT.txt`): public catalogue parsed, rejected key → `OpenSubtitles 403: You cannot consume this service`. No authenticated call was possible. |
| 1.2 | OpenSubtitles AI translation | **NEEDS CREDENTIALS** (account + credits) | Live catalogue read for real: `aws` (Arabic ✔ 0.0054/char), `gemini3-1-flash`, `gemini3-5-flash` (Arabic ✔), `deepl2` (no Arabic). Submit / poll / quality / timing code tested with contract stubs. The request encoding of `/ai/translate` is ambiguous in the official page (fields listed as "query", body typed multipart) → implemented as multipart, **must be confirmed by the first real translation**. |
| 1.3 | SubDL | **NOT PRODUCTION-TESTED** · **NEEDS CREDENTIALS** | Owner's adapter kept (TMDB id first now). SubDL's documentation is behind a Cloudflare browser challenge: **not bypassed**, so it was not checked against the docs. Real server answers `not_authorized` for a rejected key (handled). |
| 1.4 | SubSource | **NOT PRODUCTION-TESTED** · **NEEDS CREDENTIALS** | Owner's adapter kept, health recorded; docs not read. |
| 1.5 | Other sources named earlier (Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles) | **NOT IMPLEMENTED** | No official API access method was found or verified; scraping / bypassing protections is excluded by the rules. |
| 1.6 | Search by TMDB id, IMDb id, title, season, episode | **VERIFIED** (contract tests: TMDB first, IMDb fallback, `parent_*` + season + episode; library titles never by name) | live success needs credentials |
| 1.7 | Download, parse, validate, match, import | **VERIFIED** locally (24 + 12 JVM tests, 30 Worker tests) · **NOT PRODUCTION-TESTED** end to end | |
| 1.8 | Arabic SRT / VTT, language detection, encoding conversion | **VERIFIED** (JVM tests: Windows-1256, UTF-16, BOM, mojibake, centiseconds, dot separator; emulator: cp1256 file shown correctly) | |
| 1.9 | Timestamps preserved during translation | **VERIFIED** (Worker restores source timing when counts match and refuses when they differ; 4 tests) · live provider output not seen | |
| 1.10 | "Subtitles appear before dialogue" | **VERIFIED** (root cause found in ExoPlayer 2.19.1 `SubripDecoder`/`WebvttParserUtil`, own engine, emulator frames) | |
| 1.11 | Manual offset + reset | **VERIFIED** (emulator, persisted per file) | |
| 1.12 | Error handling, rate limits, auth, fallback | **VERIFIED** (429 → per-provider error not crash; quota 406/“quota” → explicit error with reset time; login rejected → not re-sent; SubDL → SubSource → OpenSubtitles chain; TMDB→IMDb fallback; foreign download host refused) | |
| 1.13 | A provider is "connected" only after a real request succeeded | **VERIFIED** | states `not_configured / configured_unverified / connected / error`, `verify` runs one free real request; 3 tests + live probe (rejected keys show `error`) |

### Phase 2 — The untranslated-titles workflow
| # | Requirement | Status | Evidence |
|---|---|---|---|
| 2.1 | Inspect the real database, identify titles without Arabic subtitles | **VERIFIED** | §2 (real catalogue read) |
| 2.2 | Batch processing with pagination, concurrency, rate limits, checkpoints, cancel / resume | **VERIFIED** locally (12 JVM tests: restart from JSON, 429 pause, quotas, backoff) · **NOT PRODUCTION-TESTED** against providers | |
| 2.3 | Search providers by TMDB / IMDb ids | **VERIFIED** (contract) · live: credentials | |
| 2.4 | Duplicate prevention, season / episode matching | **VERIFIED** (queue key = content id; matcher tests; ZIP matching shown on the emulator, `13_`, `14_`) | |
| 2.5 | Processing status per title, retry and failure tracking | **VERIFIED** (state machine, attempts, backoff) | |
| 2.6 | Review and approval before publication | **VERIFIED** (nothing is approved or published automatically; approval recorded on the Worker) | |
| 2.7 | Never overwrite an approved subtitle without explicit authorization | **VERIFIED** (Worker: `subtitle_exists`, `approved_subtitle_protected`, authorization string; app: on-screen overwrite confirmation) — test "an existing subtitle is never replaced silently" | |
| 2.8 | AI translation campaign with cost control | **NEEDS CREDENTIALS** | live prices per character; estimate shown; the admin types the maximum credits; the Worker opens a per-batch budget envelope and debits every title; nothing is sent without it |
| 2.9 | "All 3,800 processed" | **NOT CLAIMED** | 0 titles processed; the real figure is 5,035 (§2) |

### Phase 3 — Cloudflare backend
| # | Requirement | Status | Evidence |
|---|---|---|---|
| 3.1 | Subtitle API endpoints complete | **NOT PRODUCTION-TESTED** | 9 new `/admin/subhub/*` routes + hardened `/admin/subs/*`; 30 tests |
| 3.2 | Private chat endpoints complete | **NOT PRODUCTION-TESTED** | existing ChatHub + 18.2 patch; 11 tests (real DO logic, in-memory storage) |
| 3.3 | Discovery + approval endpoints | **NOT PRODUCTION-TESTED** | feeds + decisions; tests |
| 3.4 | Both apps use the same production backend | **VERIFIED** (code) | both apps call Workers under the same `…wwq-mixtv.workers.dev` account host (admin-api, auth-api, ads-api, channels-api, plus-api, …) |
| 3.5 | Authentication + authorization on sensitive endpoints | **VERIFIED** | every new route 401 without the admin token (tested); chat thread derived server-side from the verified token (tested); support role limits |
| 3.6 | Server-side upload validation | **VERIFIED** (code audit + chat tests) | existing: magic-number sniffing (JPEG, PNG, GIF, WebP, MP4, WebM; voice OGG/AAC/M4A/WebM/AMR; PDF/ZIP/DOC), 25 MB cap, rate limit, owner/thread check, files stored AES-GCM-encrypted; subtitles: parsed + Arabic share + size cap |
| 3.7 | Secrets protected | **VERIFIED** | no secret in either live source or in the bundles (pattern scan); no key in the APKs; OpenSubtitles login token only in Worker memory; logs carry route/status/duration only (tested) |
| 3.8 | Error responses, logging, request validation | **VERIFIED** (tests) | |
| 3.9 | **Deployed to production** | **NOT IMPLEMENTED (not possible here)** | the Cloudflare connector is read-only for code; `docs/backend/DEPLOY.md` has exact owner steps; bundles + SHA-256 in `deploy/` |
| 3.10 | Persistence + real sync between the two apps on the deployed backend | **NOT PRODUCTION-TESTED** | |

### Phase 4 — Private developer chat
| Requirement | Status | Evidence |
|---|---|---|
| Real send / receive, persistent history, Arabic RTL, user right / developer left, 24 h times | **VERIFIED** (UI on emulator `06_`, `07_`, `08_`, `16_`; ChatHub tests) · server round trip **NOT PRODUCTION-TESTED** | |
| Image / video / voice, emoji, receipts, retry on network loss | **VERIFIED** (UI, queue + backoff; offline message stays queued) · uploads and receipts against the deployed Worker **NOT PRODUCTION-TESTED** | |
| Developer notifications open the right conversation | **VERIFIED** (code: per-thread `PendingIntent` with the thread id; main app handles FCM `kind=chat`) · push delivery **NOT PRODUCTION-TESTED** | |
| User ids visible only to authorized developers | **VERIFIED** (tests: user view has no id; admin view `user_id`; support / owner roles) | |
| Users cannot reach other users' threads | **VERIFIED** (thread = hash of the verified uid; client-sent ids ignored; tested) | |

### Phase 5 — Discovery («ما الجديد؟»)
| Requirement | Status | Evidence |
|---|---|---|
| TMDB feeds: trending, popular, upcoming, recent, top rated, on the air | **VERIFIED** (contract: exact TMDB v3 paths) · **NEEDS CREDENTIALS** live | real TMDB server answers `Invalid API key` for a bad key (handled) |
| Posters, descriptions, ratings, dates, ids; review / approve / reject / import; duplicate prevention; existing ids preserved | **VERIFIED** (tests + unit tests `WnLogicTest` 15) | |
| Approved items really saved to the production database | **NOT PRODUCTION-TESTED** | needs deployed Worker + TMDB key |

### Phase 6 — Android compatibility and build
| Requirement | Status | Evidence |
|---|---|---|
| Preserve targetSdk 36 | **VERIFIED** (main = 36). **Admin = 34 (unchanged)**: raising it is a product decision I did not take; it is the one place where "preserve 36" does not apply | |
| Edge-to-edge on Android 15+ (system bars + keyboard insets) | Main: existing central `CigramSystemBars` (pads systemBars + cutout + IME, players excluded) **VERIFIED by code**; support chat now sets its own black window background and light bar icons · Admin: `CgSystemBars` added, dormant at targetSdk 34 · **NOT PRODUCTION-TESTED on any Android 15/16 device** (only an API 31 image is installed) | |
| Fullscreen player behaviour kept | **VERIFIED** (players are excluded from the handler; no player code changed except subtitles) | |
| Arabic RTL | **VERIFIED** (emulator screenshots of every new screen) | |
| ViewBinding dependencies and generated classes | **VERIFIED** (68 generated binding classes in main, 2 in admin; both build; the duplicate `androidx.viewbinding.ViewBinding` stub that broke release builds was removed in 18.2) | |
| Compile errors resolved; both apps build | **VERIFIED** (§6) | |

### Phase 7 — Security and release readiness
| Item | Status |
|---|---|
| **TLS validation disabled in the main app** (trust-all `X509TrustManager` + always-true `HostnameVerifier` in the shared `RequestNetworkController`; used by the catalogue, account register / login, search) | **FIXED + VERIFIED** — platform validation restored; regression test `NetworkClientSecurityTest` fails 2 of 3 on the old code and passes 3 of 3 now |
| Insecure HTTP | **PARTIAL** — `networkSecurityConfig` added to both apps: cleartext is refused for `workers.dev`, `r2.dev`, `themoviedb.org`, `tmdb.org`, `opensubtitles.com`, `subdl.com`. Other hosts stay cleartext on purpose (owner-configured IPTV / stream sources are `http://`). Manifest merge compiles; blocking behaviour **NOT device-tested** |
| Admin-private data in the **public** R2 bucket | **OPEN — needs the owner.** Verified live: `…/_admin_private/whatsnew/config.json` and `history.json` answer 200 with no credential (they hold sync settings and run history; no secret found). Fix = bind a private `ADMIN_BUCKET` (steps in `DEPLOY.md` §4). The app's «المصادر» screen warns until then |
| Exported components | **VERIFIED** — main: launcher aliases, two `cigram://` link activities, the standard widget receiver, system job services protected by `BIND_JOB_SERVICE`; admin: launcher only |
| Permissions | reviewed: nothing added; `RECORD_AUDIO` is for voice messages; `READ_EXTERNAL_STORAGE` (main) and `requestLegacyExternalStorage` (admin) pre-existing |
| Session token storage | **OPEN (residual)** — the account token is in plain `SharedPreferences` (`allowBackup=false` limits extraction); Keystore-backed storage is a separate change |
| Firebase client key in `CigramFcm.java` | pre-existing, public by design; restrict it to the package + SHA-1 in Google Cloud |
| Uploads / private-message access | **VERIFIED** (see 3.6, Phase 4) |
| Crashes, duplicate listeners, leaks, navigation | **VERIFIED** for new code only: the pre-existing chat crash («child already has a parent») was fixed in 18.2; new screens open and fail gracefully on the emulator (`15_`, `16_`). **No leak-detection tool was run; the pre-existing app was not audited line by line.** |
| Obsolete UI removed without removing needed services | **VERIFIED** (18.2: «أعلن هنا» page replaced; ads engine, campaigns, statistics untouched) |

## 4. How the translation spending is controlled (Phase 1/2, "no costly campaign without approval")
1. The app reads the provider's own catalogue (`GET /ai/info/translation`) and offers only services that support Arabic.
2. It shows the estimate for the actual file and asks the admin to **type** the maximum credits.
3. A single title needs `approval {approved_by, approved_at, max_credits ≥ estimate}`; a campaign first opens a **budget envelope** on the Worker
   (`budget ≥ estimate`, the exact title ids, one API) and every title is debited from it; when it is used up the Worker refuses the rest.
4. The result is checked (same cue count, source timestamps restored, Arabic share, the provider's own quality verdict) and waits in «مراجعة الجودة».
   It is never approved or published by the translation step.

## 5. Build and test results
Final clean builds (`gradle clean …`, Gradle 8.14.3, AGP 8.13.0, compileSdk 36), all exit 0; release outputs unsigned.

| App | Task | Output | Size (bytes) |
|---|---|---|---|
| Main | `assemblePlayDebug` | `app-play-debug.apk` | 20,958,554 |
| | `assemblePlayRelease` | `app-play-release-unsigned.apk` | 17,757,821 |
| | `assembleDirectDebug` | `app-direct-debug.apk` | 20,959,590 |
| | `assembleDirectRelease` | `app-direct-release-unsigned.apk` | 17,758,761 |
| | `bundlePlayRelease` | `app-play-release.aab` | 16,652,563 |
| Admin | `assembleDebug` | `app-debug.apk` | 8,134,032 |
| | `assembleRelease` | `app-release-unsigned.apk` | 6,589,096 |
| | `bundleRelease` | `app-release.aab` | 5,994,484 |

| Suite | Where | Result |
|---|---|---|
| `SubHubTest` (subtitle core) | main + admin | 24 / 24 each |
| `NetworkClientSecurityTest` (TLS regression: fails on the old trust-all code) | main | 3 / 3 (old code: 1 / 3) |
| `AdminSubHubTest` | admin | 12 / 12 |
| `WnLogicTest` / `WnPipelineTest` | admin | 15 / 15 · 1 skipped by its own source (as before) |
| `admin_subhub_test.mjs` (patched cigram-admin-api, in-memory R2, TMDB / SubDL / OpenSubtitles **contract** stubs) | Node 22 | **30 / 30** |
| `ads_support_test.mjs` (patched cigram-ads-api, real ChatHub logic) | Node 22 | **11 / 11** |
| `live_probe.mjs` (patched code vs the real servers, rejected key) | Node 22 | 6 / 6 scenarios behaved as designed (see `LIVE-PROBE-RESULT.txt`) |
| Emulator (API 31, software CPU): admin Subtitle Center + new «المصادر» screen, main support chat on the final APK | | open, render and fail gracefully offline; no crash |

Not run: any test against a deployed Worker, any authenticated provider call, any Android 15 / 16 device.

Live-service evidence: `docs/backend/LIVE-PROBE-RESULT.txt` (patched Worker code against the real OpenSubtitles / SubDL / TMDB servers with a rejected key).
Screenshots (`docs/screenshots-18.2/`, 1080×2280): `01`–`14` as in the 18.2 report, plus `15_admin_sources_panel_offline.png` (new «المصادر» screen, offline) and
`16_private_support_after_security_and_window_changes.png` (support chat on the final build).

## 6. Remaining blockers (owner actions, in order of importance)
1. **Deploy the two Workers** (`BACKEND-FINAL.ZIP` → `DEPLOY.md`). Until then none of the app-to-Worker features exists in production.
2. **Bind a private `ADMIN_BUCKET`** (or at least set `ADMIN_PATH_SALT`) — admin-private files are publicly readable today.
3. **Credentials**: OpenSubtitles application key + user login (downloads) + **credits** (translation); `SUBDL_API_KEY`; `SUBSOURCE_API_KEY`; TMDB key.
   Then press «المصادر» in the Subtitle Center: each provider must show «متصل» before any batch is started.
4. **First real translation on one short file** to confirm the multipart request encoding of `/ai/translate`.
5. Run a search batch on a handful of titles (not the whole 5,035) and review the results in «مراجعة الجودة».
6. Test on a real Android 15 / 16 device (edge-to-edge, keyboard, player) and on a TV; the build environment has only an API 31 emulator.
7. Decide whether the admin app should move from targetSdk 34 to 36 (the helper is ready).
8. Sign the release builds with the production keystore.
9. Optional hardening: Keystore-backed token storage; restrict the Firebase key.

---

## 7. Archives (verified)
| File | Path | Size (bytes) | SHA-256 |
|---|---|---|---|
| HASAN-FINAL.ZIP | `deliverables/HASAN-FINAL.ZIP` | 10,735,088 | `d8ce5390fa04ee57eaa436dd03adf3ec8be5f701fdb4832c834bf7bf8d34347d` |
| ADMIN-FINAL.ZIP | `deliverables/ADMIN-FINAL.ZIP` | 4,542,648 | `78ec270d9bb849cfdb4e5309406b26f16f20e5b1552f9a35a8ead9f13ab64b19` |
| BACKEND-FINAL.ZIP | `deliverables/BACKEND-FINAL.ZIP` | 301,216 | `72ae0d098ba642872fbbe6f487e9e30508cdb40aa515deffe21f705ffdb05caa` |

(This section exists only in the standalone copy of the report: it describes the archives, so it cannot be inside them.)
Built from commit `5f1c95e` (tracked files only: no `build/`, no `local.properties`, no keystore, no private key; pattern scan clean).

Verification done here:
1. `unzip -t` on all three: no errors.
2. Extracted into new empty directories (only `local.properties` with `sdk.dir` added).
3. Main: `testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease` → exit 0, APK sizes identical to the repository build
   (20,958,554 / 17,757,821). Admin: `testDebugUnitTest assembleDebug assembleRelease` → exit 0 (8,134,032 / 6,589,096).
4. Backend: `sha256sum -c deploy/SHA256SUMS` OK for both bundles; the shipped bundles pass the shipped tests (30 / 30 and 11 / 11).
5. The uploaded `cigram.zip` / `admin.zip` were never modified; the repository's first commit holds them byte-for-byte.

# CIGRAM 18.2.0 — Final Implementation Report

Deliverables: **HASAN.ZIP** (main app, folder `cigram/`), **ADMIN.ZIP** (admin app, folder `admin/`), this report.
Branch `ccr-d05a3c3e-7l3krw` of `tv-arch6/cigram`: the first commit imports both supplied archives byte-for-byte,
every later commit is a reviewable change. The uploaded archives were never modified.

Status words used below (as requested):
- **IMPLEMENTED AND TESTED** — code written and exercised by automated tests and/or on the emulator here.
- **IMPLEMENTED BUT NOT LIVE-TESTED** — code written and compiled (often unit-tested), but it needs the real backend /
  real credentials / a real device, which this environment could not reach.
- **NOT IMPLEMENTED — explanation**.

---

## 0. Environment limits (read first)
| Limit | Effect |
|---|---|
| Network policy refuses `*.workers.dev`, `pub-*.r2.dev`, `api.themoviedb.org`, `api.opensubtitles.com`, `api.subdl.com` (proxy `connect_rejected`) | No live API call, no login, no live catalogue, no live chat. |
| Documentation sites (`ai.opensubtitles.com`, `subdl.com`, Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles) not resolvable from the fetch tool | Their APIs / terms could not be read → no endpoint was guessed; those adapters are not implemented. |
| No API credentials were provided (and none must be in the APKs) | No live translation / search test. |
| No KVM | Android emulator ran in software (TCG); timings not representative. |
| Cloudflare account connector available **read-only** for code | The live source of `cigram-ads-api` and `cigram-admin-api` was read (no deploy, no secret, no data change). Backend changes are delivered as patches + local tests, **not deployed**. |

**The 3,800 untranslated titles:** NOT IDENTIFIED. The catalogue is `movies.json` in the R2 bucket `movies`; this
environment cannot read R2 objects (the public r2.dev host and the Worker are blocked, the connector has no object-read
operation). No record was invented. The Arabic Subtitle Center counts the real titles without a working subtitle from
the live catalogue when the admin opens it, and builds one queue job per real film / episode (duplicates impossible:
the key is the Cigram content id).

---

## 1. Architecture map
```
 Cigram app (com.Cigram.vid)                         Cigram Admin (com.my.newproject)
 ─────────────────────────                           ────────────────────────────────
 PlayerActivity ─ CigramSubtitleSyncEngine           SubCenterActivity / SubJobActivity / CgSeasonSubsActivity
        │   (CigramSubtitleHub: SubCodec/SubSync/SubQa) │   (same CigramSubtitleHub + EpisodeMatcher/SafeZip/SubQueue)
        │ subtitle URL from catalogue item             │ /admin/subs/search|fetch  /media/upload?action=put
        ▼                                              │ /admin/item/update (subtitle field) ─┐
  R2 public bucket  ◄──────── published .ar.srt/.vtt ──┘                                     │
  movies.json  ◄────────────── cigram-admin-api Worker (catalogue, subtitles, discovery) ◄───┘
        ▲  GET /movies                         ▲ /admin/discover/* (TMDB, server-side key)  ◄── WnActivity / WnDetailActivity
        │                                      │ 18.2 patch: feeds, /admin/discover/decision, /admin/subhub/*
 AdsChatActivity (Private Support) ── /chat/* (user, Bearer session) ─┐
 AdsNotifier / CigramPush (FCM kind=chat)                             ├─ cigram-ads-api Worker ─ ChatHub Durable Object
                                                                      │   (thread = server-derived user key, seq ordering,
 AdInboxActivity / AdChatActivity (Developer Inbox) ── /admin/chat/* ─┘    client_id dedupe, receipts, AES-GCM at rest, R2 media)
 Auth: cigram-auth-api (/login, /me)  ·  Plus: cigram-plus-api  ·  Push: cigram-push / FCM
```
Identifiers found: content id `id`; series key `series_id` (`series_<tmdb>`); `season`, `episode`; TMDB `tmdb` / `tmdb_id`;
IMDb `imdb_id` / `imdb`; subtitle URL field `subtitle` (also `subtitle_url` in intents); R2 folder `slug`;
chat thread id = `u_` + sha256(auth id or e-mail) (server-derived), messages `seq` + `client_id`.
Upload endpoints: `/media/upload` (init/part/status/complete/abort/exists/verify/put) on cigram-admin-api;
`/chat/upload/*` and `/admin/chat/upload/*` on cigram-ads-api. Storage: R2 `movies` (catalogue + media + subtitles),
admin-private prefix `_admin_private/` (or `ADMIN_BUCKET`), ChatHub Durable Object storage, R2 `cigram-ads`.

---

## 2. Feature-by-feature status

### Phase 2 — CigramSubtitleHub
| Requirement | Status |
|---|---|
| SRT + WebVTT support (parse, validate, write) | IMPLEMENTED AND TESTED (unit, both apps) |
| Search existing Arabic subtitles | IMPLEMENTED BUT NOT LIVE-TESTED (live Worker route `/admin/subs/search`, batch + single) |
| Search alternative releases | IMPLEMENTED BUT NOT LIVE-TESTED («كل النتائج واختيار إصدار آخر») |
| Retrieve files from providers | IMPLEMENTED BUT NOT LIVE-TESTED (`/admin/subs/fetch` mode=text; SubDL / SubSource / OpenSubtitles adapters already in the Worker) |
| Translate foreign files into Arabic | NOT IMPLEMENTED — the translation **contract** (`/admin/subhub/translate[/batch]`, validation, budget/approval checks), the queue states and the UI are implemented and tested locally, but **no provider adapter**: the OpenSubtitles AI / SubDL translation endpoints could not be verified from official documentation here and are never guessed. Providers answer 501 and nothing is sent or charged. |
| Manual Arabic upload | IMPLEMENTED BUT NOT LIVE-TESTED |
| Association with movie / series / season / episode | IMPLEMENTED BUT NOT LIVE-TESTED (logic unit-tested) |
| Structure + timing validation | IMPLEMENTED AND TESTED |
| Preview before approval | IMPLEMENTED AND TESTED (emulator, sample data — see §6) |
| Publish approved subtitles | IMPLEMENTED BUT NOT LIVE-TESTED (`/media/upload?action=put` + `/admin/item/update`) |
| OpenSubtitles REST search / download | Pre-existing in the Worker (kept); NOT LIVE-TESTED |
| OpenSubtitles AI translation, job status, quality assessment, download of translations | NOT IMPLEMENTED — endpoints unverified (see above) |
| SubDL (TMDB / IMDb / SubDL id, type, season, episode, language, full-season packs, single episodes) | Pre-existing in the Worker; 18.2 patch makes TMDB id first (tested locally with a stub). SubDL id lookup not added. NOT LIVE-TESTED |
| SubDL translation | NOT IMPLEMENTED — needs a subscription and confirmation of Arabic as target |
| Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles | NOT IMPLEMENTED — access methods / terms could not be checked; no scraping, no CAPTCHA / anti-bot bypass |
| Independent providers (one failing does not stop others) | Pre-existing (Worker searches each source separately, per-source status) |
| Search priority TMDB → IMDb → title+year → S/E | IMPLEMENTED BUT NOT LIVE-TESTED (backend patch, tested locally); free-text admin searches keep the title fallback |
| Never associate by similar name alone | IMPLEMENTED AND TESTED — client: titles without TMDB/IMDb are never searched in batch/single; Worker ids must match the title; an episode never takes another episode's file. Backend patch refuses library titles without ids (tested locally) |
| Translation queue record (all requested fields) and the 11 states | IMPLEMENTED AND TESTED (unit; state machine forbids shortcuts to APPROVED / PUBLISHED) |
| Arabic Subtitle Center: search, filters, categories, progress, bulk, individual, failed jobs, retry, preview, approval, publication history | IMPLEMENTED (emulator screenshots with sample data); network actions NOT LIVE-TESTED |
| Batch: pagination, configurable concurrency, rate-limit awareness, exponential backoff, persistent checkpoints, duplicate prevention, cancel / resume, provider quota tracking | IMPLEMENTED AND TESTED (unit: cursor, in-flight locks, restart from JSON, 429 pause, backoff, quota); runner against the live Worker NOT LIVE-TESTED |
| Cost estimate before bulk; explicit provider + budget + batch approval | IMPLEMENTED AND TESTED (unit + local backend validation). Price must be entered from the provider's contract (no price is invented) |

### Phase 3 — CigramSubtitleSyncEngine
| Requirement | Status |
|---|---|
| Root cause of early text | IDENTIFIED: ExoPlayer 2.19.1 adds fraction digits as raw ms (`,5` = 5 ms), drops `.`-separated SRT cues, assumes UTF-8 (bytecode verified; reproduced in `SubHubTest.exoplayerReadsShortFractionsEarly_engineDoesNot`). Release mismatch is the data-level cause. |
| Rules 1–12 (preserve timestamps, never regenerate, keep index relation, ms/time-base, ± offsets, no double delay, order, invalid times, overlaps, short durations, high CPS, keep positioning/formatting) | IMPLEMENTED AND TESTED (unit) |
| Synchronization offset / user delay / reset to zero | IMPLEMENTED AND TESTED (unit + emulator, §6) |
| Timing validation, Arabic readability checks | IMPLEMENTED AND TESTED |
| Preview against the real video timeline | IMPLEMENTED (app: delay panel shows playing time vs cue time; admin: video preview with the same timeline) — emulator §6 |
| QA: cue count, missing translations, timestamps, drift, overflow, line length, reading speed, Arabic punctuation, RTL, encoding, BOM, Windows-1256 | IMPLEMENTED AND TESTED |
| Provider quality reports as extra evidence only | IMPLEMENTED (field shown «للاستئناس فقط», never overrides local checks); no provider currently supplies one |
| Flag suspicious files for manual review | IMPLEMENTED AND TESTED (verdict PASS / REVIEW / FAIL) |
| SRT + VTT in the player without breaking settings | IMPLEMENTED; emulator §6; existing style / size / on-off / PiP paths reuse ExoPlayer's view as master |

### Phase 4 — Admin subtitle management
| Requirement | Status |
|---|---|
| `CgSubtitleUpload` extended, not replaced: Arabic uploads, SRT/VTT validation, UTF-8 normalization, Arabic encoding detection, preview, replacement / conflict handling | IMPLEMENTED (parsing unit-tested); upload NOT LIVE-TESTED |
| Movie / series / season / episode association, translation status, approval and publication | IMPLEMENTED BUT NOT LIVE-TESTED |
| One movie, one episode, multiple episodes, full-season package | IMPLEMENTED BUT NOT LIVE-TESTED |
| Safe extraction, S/E detection, matching, unmatched shown apart, never whole season to one wrong episode, manual corrections | IMPLEMENTED AND TESTED (unit: matcher + zip-slip / bomb) |
| Arabic files received / displayed correctly in admin | IMPLEMENTED AND TESTED (encoding tests; emulator §6) |

### Phase 5 — Cigram Private Support (main app)
| Requirement | Status |
|---|---|
| Old «Advertise Here» content removed, ads engine kept, «أريد إعلاناً» kept and repurposed | IMPLEMENTED AND TESTED (build; emulator §6) |
| Private 1:1 channel, no exposure between users | Pre-existing server design (thread = server-derived key); verified by local backend test «one user cannot read another user's conversation» |
| Black premium design, watermark, RTL, user right / developer left, Arabic right-aligned, English not reversed | IMPLEMENTED AND TESTED (emulator §6) |
| Developer display name / picture / admin id / online / last active | App: IMPLEMENTED BUT NOT LIVE-TESTED; server fields: backend patch (tested locally, not deployed). Admin id is never sent to users |
| User internal id visible only to admins; never in messages, notifications, profile, client logs | Backend patch removes `id` from the user's thread view (tested locally). The app never displays or logs it. Live server today still returns it to the user's own app (not displayed) |
| Logo conversation entry; notification with logo, public name, preview, time; tap opens conversation | IMPLEMENTED BUT NOT LIVE-TESTED |
| Multiple developer accounts | Backend patch (`DEVELOPERS` secret, owner / support roles) — tested locally, not deployed |
| Composer: text, emoji panel, voice record / cancel / preview / send, photo, video, upload progress, retry, reply, timestamps, delivery, read | IMPLEMENTED (emoji + voice preview new; others pre-existing and kept); emulator §6 for the UI; network NOT LIVE-TESTED |
| 24-hour clock, UTC on server, local time on device, no expiry | IMPLEMENTED |
| Reliability: WebSocket-first, polling fallback, reconnect, exponential retry, dedupe, persistent queue, upload resume, offline handling, server ordering, receipts, no duplicates, no main-thread blocking, lifecycle cleanup | Pre-existing architecture reused; 18.2 adds backoff retry of queued sends and never shows unconfirmed messages as sent. Server-side ordering / dedupe / receipts verified by the local backend test. Live NOT TESTED |

### Phase 6 — Cigram Developer Inbox (admin)
| Requirement | Status |
|---|---|
| All conversations, internal ids, search by name / internal id, open, reply, voice / photo / video, delivery / read, online, history search, unread notifications, archive, restrict | IMPLEMENTED BUT NOT LIVE-TESTED (ids / id search / history search / last seen new; the rest pre-existing) |
| Internal ids only on authenticated admin endpoints; RBAC; no cross-user reading | Backend patch, tested locally (support role cannot restrict / purge / export) |

### Phase 7 — Cigram Discovery (ما الجديد؟)
| Requirement | Status |
|---|---|
| Daily trending, weekly trending, popular movies / TV, upcoming, top rated movies / TV, new / airing series | Weekly trending, popular, upcoming, new releases / new seasons / recent episodes: pre-existing Worker feeds (popular chips added). Daily trending, top rated, on the air: backend patch, tested locally with stubbed TMDB. NOT LIVE-TESTED |
| Poster cards with the requested fields | Pre-existing (WnUi.posterCard: poster, titles, year, type, ids, rating, popularity, date, overview, genres, catalogue state) |
| Filters incl. already in catalogue / not yet added | IMPLEMENTED («مستورد مسبقاً» pre-existing, «غير المضاف فقط» new) |
| Duplicate prevention (TMDB, IMDb, content id, alternate titles) | Pre-existing server validation (`wnValidate`), kept; TMDB-id recognition tested locally |
| Never auto-publish; approve / reject / review list / bulk approval with confirmation / import history / duplicate warnings / drafts without video / publish after final approval | IMPLEMENTED (decisions + gated import + confirmed bulk approval new; import history, validation, duplicate warnings pre-existing; titles without a playable video cannot be imported and stay «معتمد»). NOT LIVE-TESTED |
| TMDB credentials server-side | Pre-existing (TMDB_API_KEY in the Worker) |
| TMDB attribution / commercial licensing | NOT VERIFIED — the owner must confirm TMDB's terms for Cigram's commercial use and show TMDB's attribution where required before production |

### Phase 8 — Obsolete advertising UI
IMPLEMENTED AND TESTED (build + emulator): main app page content removed (redirect kept), dead «أعلن هنا» row installer
removed, TV tile repurposed; admin «أعلن هنا» page editors removed from settings (server values untouched). Campaigns,
statistics, advertisers, reports, AdMob, in-house serving, «حملاتي» untouched.

### Phase 9 — Arabic localization
IMPLEMENTED: all new screens Arabic-first; physical RTL ordering (the app has no `supportsRtl`), Arabic right-aligned,
Latin ids LTR-isolated, Arabic punctuation in new texts. Times use Western digits as in the requested example (19:45);
dates use Arabic month names. Verified visually on the emulator (§6).

### Phase 10 — Security
| Item | Where |
|---|---|
| No secrets in APKs / sources | Verified: no API keys added; admin token stays user-entered; provider keys only as Worker secrets |
| Archive secret scan (private keys, `sk-` / `ghp_` / `xox` / AWS / Google key patterns, keystores) | Only match: the Firebase **client** config key in `CigramFcm.java`, present unchanged since the supplied source. It is a public app identifier by Firebase design, not a server secret; restricting it to the app package + signing SHA-1 in the Google Cloud console is recommended (§8) |
| Server-derived identity, no client user id trusted | Pre-existing ChatHub design, kept; tested locally |
| RBAC | Backend patch (owner / support) |
| Input / file validation, size limits, safe archive extraction | Subtitle 8 MB (app) / 5 MB (admin pick) / 40 MB ZIP, entry / ratio limits, only .srt/.vtt, in-memory extraction |
| Rate limiting | Existing Worker limits kept; new routes use `wnRate`; client backs off on 429 |
| Safe errors, no sensitive logs | New code logs exception class names only, never tokens / ids / texts |

---

## 3. New modules / files
Main app: `com.Cigram.subhub.{SubCue, SubDocument, SubCodec, SubSync, SubQa}`, `CigramSubtitleSyncEngine`,
`CigramSubtitleDelayPanel`, `CigramSupport`, test `SubHubTest`, doc `CIGRAM-18.2-SUBTITLES-SUPPORT.md`, Gradle wrapper.
Admin: same hub classes + `EpisodeMatcher`, `SafeZip`, `SubQueue`; `SubCenterActivity`, `SubJobActivity`,
`SubCenterRunner`, `SubCenterService`, `SubCenterStore`, `SubPublish`, `CgSeasonSubsActivity`, `WnDecisions`,
tests `SubHubTest`, `AdminSubHubTest`, doc `ADMIN-18.2-SUBTITLES-DISCOVERY-INBOX.md`, Gradle wrapper.
Backend (both archives, `docs/backend/`): patches, diffs, Node tests, README.

## 4. Modified files
Main: `app/build.gradle.kts` (testImplementation junit only), `PlayerActivity`, `CigramTogetherPlayerActivity`,
`AdsChatActivity`, `AdsIcons` (+SMILE), `AdsNotifier`, `CigramPush`, `AdvertiseActivity` (redirect), `CigramAccountDash`,
`CigramAccountUI`, `CigramTvPanels`, `MyAdsActivity`; deleted `androidx/viewbinding/ViewBinding.java` (duplicate class).
Admin: `AndroidManifest.xml`, `AdChatActivity`, `AdInboxActivity`, `AdSettingsActivity`, `CgPlayerActivity`,
`CgSubtitleUpload`, `DxSectionActivity`, `WnActivity`, `WnDetailActivity`, `WnLogic`, test `WnLogicTest`.
Full list: `git diff --name-status 3c042e2 HEAD`.

## 5. Data / storage changes and migrations
No SQL database is involved. All changes are additive and backwards compatible; **no migration** is required.
- Admin app (private storage): `files/subhub/queue.json` (queue checkpoint), `files/subhub/texts/*`, `files/subhub/history.json`,
  prefs `cigram_subcenter`, `cigram_wn_decisions`.
- Main app: prefs `subtitle_settings` → `delay:<sha1(url)>`, `subtitle_engine_legacy`; prefs `cigram_support_v1`; cache `cg_subs/`.
- Backend patch (not deployed): R2 admin-private `whatsnew/decisions.json`; ChatHub thread `uid`, message `dev_id` and
  encrypted payload `dev`, key `admin_last_active`. Old threads/messages without them keep working.

## 6. Tests executed

### 6a. Automated (all passed)
| Suite | Where | Result |
|---|---|---|
| `SubHubTest` (CigramSubtitleHub: ExoPlayer misparse reproduction, centiseconds, `.` in SRT, 4-digit fractions, frames refused, round trip, missing blank lines, VTT header / settings / ids / NOTE / STYLE / X-TIMESTAMP-MAP, SRT↔VTT, content sniffing, Windows-1256, BOM, UTF-16 with / without BOM, two mojibake kinds, delay sign and single application, overlapping and long cues, invalid / blank cues, shift clamp / drop, frame-rate stretch, translation keeps timing, drift detection, restore source timing, merged translations refused, missing / untranslated cues, overlap / short / CPS / line length, Arabic punctuation and RTL fixes, not-Arabic, encoding damage, clean pass) | main `testPlayDebugUnitTest` and admin `testDebugUnitTest` (identical file) | 24 / 24 in each app |
| `AdminSubHubTest` (episode names incl. Arabic digits, matching / ambiguity / wrong season / unmatched / double episode, manual correction + conflict, safe ZIP: traversal / macOS / non-subtitle / nested, zip bomb, state machine, duplicates, batch pagination + restart from JSON + retry after backoff, 429 pause, cancel, backoff values, quota per day, cost estimate) | admin | 12 / 12 |
| `WnLogicTest` (existing, chip list updated for 23 chips) / `WnPipelineTest` | admin | 15 / 15 · 1 skipped by its own source (as before) |
| `ads_support_test.mjs` — real patched cigram-ads-api in Node with in-memory Durable Object storage | docs/backend | 11 / 11 (same suite on the unpatched live worker: 3 / 11, documenting today's behaviour) |
| `admin_subhub_test.mjs` — real patched cigram-admin-api with in-memory R2, stubbed TMDB / SubDL | docs/backend | 14 / 14 |

### 6b. Emulator (Android 12 / API 31 x86_64, software CPU, no network, labelled local fixtures)
Fixtures: a 60 s test video with a burned-in clock, an Arabic SRT saved as **Windows-1256** containing a centisecond
cue (`00:00:02,5`), a `.`-separated SRT cue, an italic Arabic + English cue and a `{\an8}` cue starting at `12,50`.
| Check | Result |
|---|---|
| Windows-1256 Arabic decoded and shown | ✅ (`01_…png`) |
| Cue `00:00:02,5 → 00:00:05,0` on screen at video 03.960 (ExoPlayer alone would use 2.005 → 5.000) | ✅ |
| `.`-separated SRT cue (dropped by ExoPlayer) shown at 07.480 | ✅ |
| Mixed Arabic / English italic cue at 09.400 and 11.440, English not reversed | ✅ (`01_`, `02_`) |
| `{\an8}` cue at the top, start `12,50` = 12.5 s, on screen at 16.120 | ✅ (`02_`) |
| One frame at 05.560 (cue 2 starts 05.500) still empty | ⚠️ frame-callback latency of the software emulator (tens of ms); not reproducible as a timing error in unit tests |
| Gear sheet «توقيت الترجمة» presets + explanation | ✅ (`03_`) |
| Fine-tune panel: delay value, meaning, nearest cue vs. playing time, ±0.1/0.5/1 s, reset | ✅ (`04_`) |
| +0.5 s applied instantly: preview 12.500 → 13.000; saved as `delay:<sha1(url)>=500` in `subtitle_settings` | ✅ (`05_`) |
| Settings sheet stayed visible behind the panel | ❌ found → fixed (dismiss after the sheet's own tap handling; opaque panel). Re-check on the emulator was blocked by the app's existing offline overlay (the emulator has no internet) |
| Private Support screen: black design, developer identity, watermark, composer, offline banner | ✅ (`06_`) |
| Emoji panel with 4 tabs, insertion at the cursor | ✅ (`07_`) |
| Sending a message → **crash** «The specified child already has a parent» in `AdsChatActivity.buildRow` | ❌ found → fixed. The bug is in the supplied source: every bubble render crashed |
| ANR dialogs (System UI, phone, Cigram) | Emulator CPU starvation; the Cigram trace shows the pre-existing `CigramStage1InitProvider$PlayerMonitor.stage4PatchVisibleSpeedRow` text layout on the main thread, not 18.2 code |
| Private Support in the phone's **Light** mode: bubbles turned white with white text (the app's `CigramUI.round` / `CigramGlass` recolour dark colours in Light mode) | ❌ found → fixed (`CigramSupport.round`, fixed support colours); re-checked ✅ (`08_`) |
| Message sent without network stays in the conversation as «بانتظار الاتصال — سيُرسل تلقائياً» (queued, never shown as sent) | ✅ (`08_`); the automatic resend itself needs the live server (§6c) |
| Admin «مركز الترجمة العربية»: counters, filters, categories, job list from a seeded queue | ✅ (`09_`, `10_`) |
| Admin job screen: identity, state, QA report, cue-by-cue preview against the source timing | ✅ (`11_`, `12_`); identity rows were displayed in reversed order (mixed RTL / LTR) → fixed, re-checked ✅ |
| Admin «ترجمات موسم كامل» with a real ZIP (S01E01, S01E02 ×2, S01E03, S02E01, a file without a number): E01 / E03 matched, both E02 files held back as «أكثر من ملف لنفس الحلقة», S02E01 «موسم آخر», the unnumbered file «بلا رقم حلقة»; upload button counts only the 2 matched files | ✅ (`13_`, `14_`). The upload itself was **not pressed** (it writes to the live R2 catalogue) |

Screenshots (emulator, 1080×2280) — `docs/screenshots-18.2/` inside both archives, `qa-screens/18.2/` in the repository:
| File | Shows |
|---|---|
| `01_player_subtitle_timing_cp1256_dot_mixed.png` | Windows-1256 Arabic cue on time; `.`-separated cue shown |
| `02_player_subtitle_mixed_and_top_position.png` | Arabic + English italic cue; `{\an8}` cue at the top |
| `03_player_gear_subtitle_timing_presets.png` | Gear sheet «توقيت الترجمة» presets |
| `04_player_delay_panel_zero.png` / `05_player_delay_panel_plus_0_5s.png` | Fine-tune panel at 0 and after +0.5 s |
| `06_private_support_chat.png` / `07_private_support_emoji_panel.png` | Private Support screen, emoji panel |
| `08_private_support_queued_offline_message.png` | Offline message kept queued (Light-mode fix visible) |
| `09_admin_subtitle_center.png` / `10_admin_subtitle_center_jobs.png` | Admin Arabic Subtitle Center, job list |
| `11_admin_job_review.png` / `12_admin_job_qa_cue_preview.png` | Job identity / state / QA report / cue preview |
| `13_admin_season_zip_matching.png` / `14_admin_season_zip_unmatched.png` | Full-season ZIP: matched, duplicate, other season, no number |

Not captured: Developer Inbox and Discovery («ما الجديد؟») screens — both load their content from the live Workers,
which this environment cannot reach, so a screenshot would only show the network error.

### 6c. Not tested live (needs real backend / credentials / devices)
Login; live catalogue; real movies / series / episodes playback and episode selection; subtitle URLs from R2;
subtitle switching between titles; all Worker calls (search, fetch, upload, publish, discovery feeds, decisions,
chat send / WebSocket / receipts / uploads of photo / video / voice, notifications from the server, push);
admin Developer Inbox with real threads; translation providers (none implemented); the 3,800-title batch;
real TV / tablet / physical phone; casting.

## 7. Builds
Final clean builds from commit `8fdc59f` (`gradle clean …`, Gradle 8.14.3, AGP 8.13.0, JDK 17, compileSdk 36). All exited 0.
Release outputs are **unsigned** (no keystore is in the project or the archives; sign with the production key).

| App | Task | Output | Size (bytes) |
|---|---|---|---|
| Main (`cigram/`) | `testPlayDebugUnitTest` | `SubHubTest` 24 / 24 | — |
| | `assemblePlayDebug` | `app-play-debug.apk` | 20,958,205 |
| | `assemblePlayRelease` | `app-play-release-unsigned.apk` | 17,757,597 |
| | `assembleDirectDebug` | `app-direct-debug.apk` | 20,959,521 |
| | `assembleDirectRelease` | `app-direct-release-unsigned.apk` | 17,758,353 |
| | `bundlePlayRelease` | `app-play-release.aab` | 16,652,317 |
| Admin (`admin/`) | `testDebugUnitTest` | `SubHubTest` 24 / 24 · `AdminSubHubTest` 12 / 12 · `WnLogicTest` 15 / 15 · `WnPipelineTest` 1 skipped (by its own source) | — |
| | `assembleDebug` | `app-debug.apk` | 8,120,543 |
| | `assembleRelease` | `app-release-unsigned.apk` | 6,579,428 |
| | `bundleRelease` | `app-release.aab` | 5,984,905 |

Pre-existing build fix: release variants of the main app failed at `mergeDex` in the supplied project
(`androidx.viewbinding.ViewBinding` defined twice); the duplicate stub was removed (see the main app notes).

Archive check: both archives are rebuilt from the git tree (tracked files only: no `build/`, no `local.properties`, no
keystore), tested with `unzip -t`, extracted into new empty directories and built there on their own; the result and the
archive sizes are in **§9** of the copy of this report delivered next to the archives.

## 8. Outstanding backend / owner actions
1. Review and deploy `docs/backend` patches (ads-api, admin-api); set `DEV_NAME`, `DEV_AVATAR`, optional `DEVELOPERS`.
2. Implement a translation provider adapter from the official OpenSubtitles AI (or SubDL) documentation, add its secret, test
   with a real account, then mark it implemented. Until then automatic translation is unavailable by design.
3. Verify access terms of Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles before any integration.
4. Verify TMDB commercial licensing and attribution.
5. Run the live checks listed in §6c on a real device with real accounts.
6. Sign release builds with the production keystore (not in the archives).
7. Optional hardening: restrict the Firebase client API key (pre-existing in `CigramFcm.java`) to package `com.Cigram.vid` + the release SHA-1.

---

## 9. Archives (verified)
| File | Path | Size (bytes) | Entries | SHA-256 |
|---|---|---|---|---|
| HASAN.ZIP (main app, folder `cigram/`) | `deliverables/HASAN.ZIP` | 10,198,276 | 500 | `69bb1fe0884fb55817899fac118d43fe92cc7977cd80fa61b51c5e5a2c72d819` |
| ADMIN.ZIP (admin app, folder `admin/`) | `deliverables/ADMIN.ZIP` | 3,999,783 | 270 | `b0328a46a32fa11d32cc20149c2b205d1cdb80eba1e5a2bff3083c466d3bcfbf` |
| This report | `deliverables/FINAL-IMPLEMENTATION-REPORT.md` | — | — | — |

Each archive holds the project source (tracked files only), `docs/backend` (Worker patches + Node tests),
`docs/screenshots-18.2/` and a copy of this report (that copy ends at §8; this §9 exists only here, because it describes
the archives themselves). No `build/` output, no `local.properties`, no keystore, no private key (scan in Phase 10).

Verification done here:
1. `unzip -t` — no errors in either archive.
2. Each archive extracted into a new empty directory; only `local.properties` (`sdk.dir`) added, as any machine needs.
3. Built there on their own with Gradle 8.14.3 (the included wrapper pins 8.13 and downloads it on first use):
   main `testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease` → exit 0, `SubHubTest` 24 / 24,
   APK sizes identical to the repository build; admin `testDebugUnitTest assembleDebug assembleRelease` → exit 0,
   24 / 24 · 12 / 12 · 15 / 15 · 1 skipped (pre-existing).
4. The archives were then re-packed only to include the final report text; a recursive diff of the re-extracted trees
   against the built ones shows the report file as the only difference.
5. The uploaded `cigram.zip` / `admin.zip` were never modified; the repository's first commit holds them byte-for-byte.

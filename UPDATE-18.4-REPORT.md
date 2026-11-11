# CIGRAM 18.4 — offline-first content system, Cloudflare audit, scroll/startup fixes (main 18.4.0 / code 91, admin 2.1.0 / code 9)

Deliverables: `CIGRAM-MAIN-FINAL.zip` (folder `cigram/`) and `CIGRAM-ADMIN-FINAL.zip` (folder `admin/`). Each one contains its complete project, the
Cloudflare deployment files (`docs/backend/`), the audit tools (`docs/tools/`), this report and the before/after screenshots.

## 0. Read this first
| Statement | Status |
|---|---|
| Both projects compile from a fresh extraction of the ZIP; unit tests run with the Gradle build cache OFF (numbers in §8) | **VERIFIED** |
| The Worker change (revision tracking + `/catalog/manifest` + `/catalog/changes`) passes its 16 + the 60 existing Node contract tests against the real Worker code with R2 in memory | **VERIFIED locally** |
| **Nothing was deployed.** The build environment can read the Cloudflare account (list Workers / R2 / KV / D1, read Worker code) but has no tool that writes. The live `/catalog/manifest` answers **404** today (checked 2026-11-09), so the apps will use the full download exactly as before until you deploy the Worker (`docs/backend/DEPLOY.md` §8, rollback included) | **NOT DEPLOYED** |
| The app's incremental (delta) path against a real Worker, `ETag` behaviour behind Cloudflare's CDN | **NOT PRODUCTION-TESTED** (fake server with the same protocol in the JVM tests) |
| Scroll smoothness on a real device | **NOT MEASURED.** The only emulator available is a software emulator without hardware acceleration: every frame of every app (even an idle screen) is "janky" there, so it cannot tell a smooth app from a stuttering one. The scroll work below is code analysis + two targeted fixes, not a measured result |
| Real Android 8–16 devices, low-RAM devices, slow mobile networks | **NOT TESTED** |
| Signing | **Not possible**: the original production keystores are not in the ZIPs or in this environment and I did not create any. The ZIPs contain sources only; sign with your own keystore |

## 1. Cloudflare account audit (read-only, 2026-11-09)
| Item | Finding |
|---|---|
| Workers (15) | `cigram-admin-api` (modified 2026-11-07), `cigram-ads-api` (10-07), `cigram-auth-api` (11-02), `sigram-channels-api` (10-17), `cigram-user-api` (10-24), `cigram-metadata-api` (09-22, 2 MB bundle), `cigram` (11-08), `cigramweb` (11-08), `cigram-plus-api`, `cigram-stream`, `cigram-push`, `cigram-together-api`, `cigram-ads-config`, `cigram-analytics`, `calm-wood-9aa4`. The app's data path uses the first five + `cigram-ads-config` and the public R2 host |
| Live code vs the repository | The code of `cigram-admin-api`, `cigram-ads-api` and `cigram-auth-api` was fetched again from the account and is **byte-identical** to the snapshots (sha256 `0e9c06a1…`, `3bcc0725…`, `517ddfbf…`); `sigram-channels-api` was last modified before the snapshot. **No drift.** `cigram-user-api` (identity / requests / intro / featured) was read in full: no catalogue writes |
| R2 buckets (3) | `movies` (public host `pub-eabe41d1…r2.dev`), `cigram-ads`, `cigram-avatars`. The connector lists buckets but not objects: public objects were read through the public host (`movies.json`, `cigram_carousel.json` 21 KB, `cigram_update.json` 896 B) |
| KV (3) | `cigram-cache`, `cigram-state`, `CIGRAM_KV` (namespaces listed; keys are not enumerable with the connector, user documents were not read) |
| D1 (3) | `cigram-users` (tables `users`, `sessions`, `audit_logs`, `content_reactions`, `delete_tokens`, `login_links`, `rate_limits` + 6 indexes), `cigram_analytics` (`events`), `cigram-plus` (`app_config`, `plus_*`). **Only `sqlite_master` names were queried; no row of any table was read** |
| Live catalogue (`movies.json`, 5.69 MB, modified today 09:51) | 5,592 records = 150 movies + 64 series headers + 5,378 episodes. **No duplicate ids, no orphan episodes, no duplicate (series, season, episode), no record without poster or video, no section id with several names.** Findings: the envelope field `count` is **88**, not the record count; one series header has no episodes. Average record 1 KB. `docs/tools/catalogue_integrity.py` repeats the audit |
| Headers | `/movies`: `cache-control: no-store`, weak `etag` (Cloudflare weakens it when compressing), **ignores `If-None-Match`** (200), gzip: **557,565 bytes on the wire for 5,788,704 bytes of JSON**. `/channels`: no `etag` at all. So the cost of a full refresh is ~0.56 MB of transfer **plus** the device parsing / indexing 5 MB of JSON, not "5.7 MB of download" |
| Session storage | Opaque sessions in D1 (`sessions`), 30-day expiry, device fingerprint, `/me` (18.2.1 work: chat no longer treats a failed `/me` as a logout). Nothing was changed in the auth Worker |
| Files recovered into the projects | `docs/backend/live-snapshots/` = the four live Worker sources (`cigram-admin-api`, `cigram-ads-api`, `sigram-channels-api`, **`cigram-auth-api` (new)**) with `SHA256SUMS` (rollback sources; scanned: no secret in any of them). Nothing was missing from the ZIPs that Cloudflare could supply; **no production secrets exist in any file** |

## 2. Problems found and what was done
| # | Problem (evidence) | Solution | Status |
|---|---|---|---|
| 1 | The saved catalogue (5 MB) and its search index were two SharedPreferences strings: every save rewrote a ~10 MB XML file, every cold start parsed it, a crash mid-write could corrupt it, nothing verified it | `CigramCatalogStore`: generations on disk (tmp dir → fsync → read-back verify → rename → `CURRENT`), SHA-256 of every blob, previous generation kept, recovery of the last valid one. `CigramCatalogPrefs` keeps the 40 existing call sites unchanged and migrates the old file (verified copy first, then the old entry is removed) | VERIFIED (JVM) + migration VERIFIED on the emulator: 5,322 records, legacy file 5,372,345 B → 65 B |
| 2 | **`isValidCatalog` accepted `{"ok":true,"movies":[]}`**: an empty server answer replaced a valid saved catalogue | Empty containers are no longer valid; the store refuses to commit an empty / unusable catalogue (last line of defence); the sync manager rejects it | VERIFIED (JVM) |
| 3 | A catalogue answer that lost most records would silently shrink the catalogue | Held (not applied) when more than half of ≥20 records would disappear; applied only when the same answer is seen again ≥5 min later | VERIFIED (JVM) |
| 4 | Whole catalogue downloaded to learn about one change | `CigramCatalogSync` (one manager): manifest (304) → delta pages (`/catalog/changes`) → verify (record count + id digest equal the server's) → atomic commit; full download only when the server says so, the revision is older than the log, verification fails, or a weekly self-check (unmetered networks only) | VERIFIED (JVM, fake Worker); Worker side VERIFIED locally; **not deployed** |
| 5 | Interrupted multi-page sync starts over | Progress is saved after every page and resumed (also after the process is killed) | VERIFIED (JVM) |
| 6 | **Offline cold start showed a full-screen error instead of Home** even with 5 MB saved (reproduced on the emulator with 18.2.1: `qa-screens/18.4/01_…`) | The blocking "no internet" screen is shown only when no catalogue is saved; the existing "browsing offline" banner covers the rest. New build on the same emulator: no overlay, saved catalogue delivered to Home, sync fails quietly (`FAILED/manifest_io`, 1 request) | VERIFIED on the emulator (software emulator: Home needed minutes to draw its rows, see §0) |
| 7 | Admin app: an answer without a record list became an **empty catalogue** on screen and in its saved cache; every refresh downloaded everything; the cache was one 6 MB preferences string | `CgCatStore/CgCatDelta/CgCatSync/CgCatDoctor` (same code and tests as the main app) + `CgCatalog`; the saved copy is shown (marked as such) when the Worker fails; preferences keep only meta + sections; one-time verified migration of the old cache | VERIFIED (JVM) |
| 8 | Admin writes | Already targeted (`/admin/item/update|create|delete`, `/admin/season/commit`, … by content id) with compare-and-swap, a pre-write backup, an `unexpected_loss` guard and an undo journal in the live Worker; the apps never post the whole catalogue. Upload interruption recovery (`CgUploadManager`: persisted sessions, R2 part status, auto-resume) was audited, not exercised | audited |
| 9 | No revision / change tracking on the backend | `catalog_feed.js` (+ `apply_patch.py`): after every `mutateMovies` write the Worker hashes the records, diffs against the previous revision and logs exactly the changed records (bounded log, reset on anything it cannot prove, bulk > 300 = reset, never fails the admin's write). Routes `/catalog/manifest`, `/catalog/changes` (ETag/304) | VERIFIED locally (16 tests); **not deployed** |
| 10 | Home was rebuilt in the middle of a scroll when a refresh finished, and jumped to the top | The redraw waits until the finger rests (≤14 s) and the vertical scroll position is restored | code change, **not measured** |
| 11 | Structural problems in data (orphan episodes, duplicate ids / episode rows) | `CigramCatalogDoctor` (read-only, never edits data): finding after a delta → one verifying full download (≤ every 6 h, never on metered networks) | VERIFIED (JVM) |

## 3. Your table: problem → mechanism → test
| Problem | Mechanism | Test (class.method) |
|---|---|---|
| Slow content retrieval | Saved catalogue read from a verified file, Home never waits for Cloudflare | `CigramCatalogStoreTest.aBigCatalogueRoundTrips…`, emulator §6 |
| Repeated full downloads | manifest 304 / delta / refresh policy of 18.2.1 | `CigramCatalogSyncTest.nothingChangedCostsOneTinyRequest…`, `aNewMovieArrivesAsAOneRecordDelta…` |
| Missing cached content | Full snapshot downloaded in the background; previous generation kept | `…firstInstallDownloadsOnce…` |
| Empty API response | Rejected at three layers (validator, manager, store) | `…aServerThatAnswersAnEmptyCatalogue…`, `CigramCatalogPrefsTest.anEmptyCatalogueCanNeverReplaceOrRemove…` |
| Cloudflare timeout / HTTP 500 / 502 | `FAILED` outcome, saved catalogue untouched, back-off | `…timeoutsAndServerErrorsNeverTouch…` |
| Duplicate sections / carousel slides | 18.2.1 `CigramSectionDedupe` (unchanged, still tested) | `CigramSectionDedupeTest` |
| Missing posters | Catalogue metadata is never touched; the poster file is decoded from disk first and retried the next time the card is built; the doctor counts records with no poster URL (live: 0) | `CigramCatalogDoctorTest.everyKindOfFinding…` |
| Incomplete seasons | The doctor reports season holes and orphan episodes; nothing is invented | `CigramCatalogDoctorTest` |
| Corrupted cache | Last valid generation restored | `CigramCatalogStoreTest.aTruncatedNewestGeneration…`, `aSilentBitFlip…`, `aMissingOrGarbageCurrent…` |
| Interrupted synchronization | Persisted pages, resume | `…anInterruptedSyncResumes…`, `aKilledProcessResumesToo` |
| Repeated network requests | One manager, single flight (existing `CATALOG_NETWORK_RUNNING`), refresh policy | `CigramRefreshPolicyTest` |
| Outdated local records | Only changed records are replaced, the rest keeps position and bytes | `…editAndDeleteTouchOnlyTheirOwnRecords` |
| Inconsistent IDs | Ids are never generated or changed; digest check | `…contentIdsAreNeverChangedOrInvented`, `theIdDigestMatchesTheWorkers…` (also equal on the real 5,592 ids between Java and Node) |
| Session expiration | 18.2.1 `AdsAuthGateway` (unchanged) | `AdsAuthGatewayTest` |
| Application restart | Saved catalogue restored first; migration for the old format | `CigramCatalogPrefsTest.theCatalogueSavedByTheOldVersion…` |

## 4. Architecture in one paragraph
Cloudflare stays the source of truth. The app reads `catalog_v2/` (generations) through `CigramCatalogPrefs`; `CigramCatalogSync.syncOnce` runs on the existing
background pool: `GET /catalog/manifest` (If-None-Match) → nothing / `GET /catalog/changes?since=<rev>` pages → fold into the saved document by content id →
verify `records` + `ids_digest` against the server → run the existing prepare/index pipeline → atomic commit → install in memory → redraw Home only if the content
changed and the user is not scrolling. If the Worker has no feed (today) or says `full`, the same manager does the conditional full download that was there before.
Deleting a record happens only through an explicit tombstone from the server or a verified full snapshot; a bigger-than-half loss is held. Applying a delta still
parses and re-indexes the saved catalogue on a background thread (≈0.5 s on a desktop JVM for 5.2 MB; several times that on a phone): the delta saves transfer and
server work, **not** the client's CPU.

## 5. Scroll / page stutter (the Arabic request)
What the code analysis found and changed: (a) the redraw after a refresh ran on the UI thread whenever the refresh finished, including mid-scroll → deferred until
the finger rests and the scroll position restored; (b) the Home already builds ~14 cards first and drips the rest while yielding during touch (18.x work, kept);
(c) posters are decoded with subsampling + RGB_565 and cached (kept); (d) the 5 MB catalogue read on startup was an XML parse on a blocking path → now a file read
with a checksum (JVM: verified load 42 ms for 5.2 MB). **I could not measure any of this on a device** (§0) and I did not find a profiler-backed hot spot. If the
stutter persists on your phone, send a `adb shell dumpsys gfxinfo com.Cigram.vid` after scrolling Home (its "Slow UI thread / bitmap uploads / issue draw commands"
counters say which of the three it is) and the page where it happens.

## 6. Emulator results (software emulator, no KVM, one run each: indicative only)
| Scenario | 18.2.1 | 18.4.0 |
|---|---|---|
| Cold start with a stale saved catalogue and no usable internet | Full-screen "خطأ في التحميل" (`qa-screens/18.4/01_…`) | No overlay; migration of the old file (5,322 records, 5.37 MB → 65 B); `CigramDiag: HOME got catalogue tag=movies_cache`; Home rows appeared after several minutes on this emulator (`02_…`) |
| First launch after the update | — | `legacy catalogue migrated: 5322 records`, `CigramSync: outcome=FAILED/manifest_io requests=1 ms=539` (no internet), saved catalogue untouched |
| gfxinfo while swiping Home | not comparable | 13 of 13 frames "janky", median 1,250 ms: the emulator, not the app (see §0) |

## 7. Measurements
| Measure | Value |
|---|---|
| Live catalogue | 5,592 records, 5,231,949 chars compact JSON, 557,565 bytes gzip on the wire, 1 KB / record |
| JVM, real catalogue (desktop CPU) | read + count + digest 311 ms; store commit (write + fsync + verify) 187 ms; **verified load 42 ms**; one-record merge 469 ms; diagnosis 173 ms |
| Worker feed (Node, real catalogue) | bootstrap 74 ms once; manifest 0.5 ms; delta for 1 edited record 1,218 B; feed object 271 B + hashes object 155,772 B |
| Network requests on a refresh | nothing changed: **1** small request (manifest, 304); one new episode: **2** (manifest + 1 page); before: 1 request of ~0.56 MB gzip every time the policy allowed (JVM tests count them) |
| Cold / cached startup on a device, memory, failure recovery time | **not measured** (no device) |

## 8. Tests actually executed (build cache OFF, from the extracted ZIPs)
| Suite | Result |
|---|---|
| Main app JVM tests **from the ZIP as it is (no catalogue file)** | **117 tests: 114 passed, 0 failed, 3 skipped.** The 3 skipped need a real `movies.json` (production data, not in the archive): `CigramCatalogRealDataTest`, 2 × `CigramSectionDedupeTest.realCatalogue*` |
| Main app JVM tests **with the real catalogue** (`CIGRAM_CATALOGUE=<copy of the live movies.json>`, second run, cache off) | **117 tests: 117 passed, 0 failed, 0 skipped** |
| Admin app JVM tests | **94 tests: 93 passed, 0 failed, 1 skipped** (`WnPipelineTest`, skipped by its own source since 18.2) |
| Worker contract tests (Node, the real patched Worker code, R2 in memory) | `admin_subhub_test` **31**, `catalog_feed_test` **16**, `ads_support_test` **23**, `channels_conditional_test` **6**: **76 passed**; `deploy/SHA256SUMS` and `live-snapshots/SHA256SUMS` verified |
| New in 18.4 | main: store 11, sync + delta 22, preferences facade 6, doctor 4, real data 1 = **44**; admin: store 11, sync 22, doctor 4 = **37** (same sources); Worker feed **16** |
| Emulator scenarios (indicative, one run each) | §6 |

Not run at all: tests on a real device, against a deployed Worker, with a low-memory profile, chunked upload / WebSocket against a real server, scroll measurements.

## 9. Builds
Fresh extraction of each ZIP, `clean` + cache off, exit 0 for every task: main `testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease assembleDirectDebug assembleDirectRelease bundlePlayRelease`; admin `testDebugUnitTest assembleDebug assembleRelease bundleRelease`.
`aapt2` confirms `com.Cigram.vid` 91 / 18.4.0 and `com.my.newproject` 9 / 2.1.0 (package ids unchanged).
| Artifact (unsigned: no production keystore available) | Bytes | SHA-256 |
|---|---|---|
| main `app-play-release.aab` | 16,683,145 | `d5af57a5e65799fa165a5bba5471041ee74696cf32257573d37a6f8f7de72835` |
| main `app-play-release-unsigned.apk` | 17,788,417 | `72fb506ba97fbc2380ba43646a87123a2637b35b97d6c43c5dc9bc6a415ad2ef` |
| main `app-direct-release-unsigned.apk` | 17,788,969 | `546928caba84767cd0fd387c0bf09d771c1afc33136507eb6d86d3122a46b6b4` |
| admin `app-release.aab` | 6,010,969 | `230b821e43feff90b2d522504ce34ece79bddd8e4cf64ef655dfb715277dc1a5` |
| admin `app-release-unsigned.apk` | 6,605,584 | `adb6dc4d7d8169968155094ec9023275b5a7a5616fbc4f5d90930d6987bcf1f0` |

These files are not delivered (the delivery is the two source ZIPs); they show that the sources build. The ZIPs' own SHA-256 values are given in the message that delivers them (a hash cannot be stored inside the file it describes).

## 10. Remaining issues and owner actions
1. **Deploy** `docs/backend/deploy/cigram-admin-api/worker.js` (see `docs/backend/DEPLOY.md` §8: smoke test, rollback = paste `live-snapshots/cigram-admin-api.live.js`). Until then the apps use the full download. First manifest call parses `movies.json` once (74 ms in Node): check your Workers plan's CPU limit.
2. After deploying, test the delta path with a real edit (one title) and watch `adb logcat -s CigramSync`.
3. Sign both apps with your original keystores (not available to me) and verify the certificate fingerprints.
4. Test on real phones (Android 8–16, low RAM), on a slow connection and while scrolling Home and the other pages; send `gfxinfo` if the stutter remains (§5).
5. Open items from earlier reports: admin-private R2 objects are publicly readable while no `ADMIN_BUCKET` is bound (`DEPLOY.md` §4); the admin app still targets API 34.
6. Build note: after adding source files in this project's unusual layout (`com.my.newproject/` folders) run Gradle with `clean` once; an incremental build can fail with "package … does not exist" (seen with the admin project).

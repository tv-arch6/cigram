# CIGRAM 19.0 — permanent support chat, targeted push, backups, faster Home (main 18.5.0 / code 92, admin 2.2.0 / code 10)

Execution report, 2026-11-09. Same text in both projects. Package ids unchanged (`com.Cigram.vid`, `com.my.newproject`).

## 0. Read this first
| Statement | Status |
|---|---|
| Both projects compile from a clean checkout; unit tests pass; unsigned release APKs + AAB build | **VERIFIED** (numbers in §6) |
| The deployed Workers are the ones in this repository | **VERIFIED**: `cigram-admin-api`, `cigram-ads-api`, `cigram-auth-api` fetched from the account on 2026-11-09 and compared byte for byte with `docs/backend/live-snapshots/` (sha256 `0e9c06a1…`, `3bcc0725…`, `517ddfbf…`): identical. `sigram-channels-api` last modified 2026-10-17 (before the snapshot) |
| **Nothing was deployed.** The Cloudflare connector of this session can read Workers, buckets and namespaces but not write them, and the chat Worker is not reachable from the build environment (egress policy: 403). Every Worker change is code + tests + an exact deploy recipe (`docs/backend/DEPLOY.md` §9) | **NOT DEPLOYED** |
| Chat, push and backup **against the real Worker**, a real FCM delivery, a real R2 backup, the real auth path | **NOT VERIFIED** — only against the real Worker code with in-memory storage / R2 and a fake FCM endpoint |
| Anything on a phone (Home rendering, scrolling, notifications, the two apps talking to each other) | **NOT TESTED**: no device and no emulator in this environment. UI changes are compile-verified only |
| The PrimeTV APK | **Not supplied.** Only the two project ZIPs and two screen recordings arrived. PrimeTV was studied from the 6-second recording only (§2) |
| Signing | Not possible (no keystores); the APKs are unsigned |

## 1. What was investigated
Both source trees, the four live Workers (code compared by hash, see above), `cigram-push` (read in full), the live catalogue (`GET /movies`, 5,685 records, a copy is in `docs/backups/2026-11-09/` with sha256), the live `/catalog/manifest` and `/health` of `cigram-admin-api`, and both recordings frame by frame.

## 2. The two recordings
* **PrimeTV (observed):** splash → an empty dark Home with only the header → rows appear on their own without any user action: «الأخيرة» (continue watching, landscape cards), «رائج اليوم», «أفلام شائعة», «مسلسلات شائعة», «دراما تركية», «دراما كورية», «أفلام وثائقية». Poster cards share one size, rounded corners, a rating badge top-right, the title below, a dark placeholder that turns into the poster (some cards are still blurred/dark in the last frames). *Assumptions (not observable): how it fetches, caches or orders those rows.*
* **Cigram (observed):** hero carousel + rows «يعرض حالياً عربي», «أضيف مؤخراً», «أحدث الأفلام», «المسلسلات المميزة», «عالم مارفل», «حب رومانسي». Some rows show a single poster next to empty cards while images arrive. **The support chat shows the banner «انتهت جلسة حسابك ولا يمكن تجديدها تلقائياً…» on an account that is signed in** (profile with e-mail and Plus), with the two typed messages stuck in «بانتظار الاتصال».

## 3. Root causes found
1. **False «session expired» (chat).** The app says it only when the chat Worker answers 401 and no silent renewal is possible. The *live* Worker (2026-10-07) answers `401 invalid_token` for **every** failure of its call to the auth Worker (e.g. a timeout, a 429, a 5xx, a wrong or missing service binding), and the app had no way to tell that from a really dead session; an account without stored credentials (e.g. Google sign-in) can never renew. The 18.3 fix for the Worker side was never deployed. **Which of these happens in production could not be determined** (no access to the Worker's logs or bindings from here); the fixes below handle both a dead session and a Worker that cannot verify a live one, and `chat_auth` log lines will say which it is. → §4.1.
2. **Push for the private chat did not exist.** `cigram-push` is a broadcast sender (topic `cigram_all`, needs `X-Admin-Key`); the chat Worker's `notify()` posted to an optional `PUSH_WEBHOOK_URL` without credentials, skipped a notification when another one for the thread had gone out in the last 30 s, never retried, and the main app never sent its FCM token anywhere. Only the 15-minute poll could notify a closed app; the admin app had no FCM at all.
3. **History deletion paths:** `/admin/chat/purge`, `/admin/chat/delete-message`, `/chat/delete-all`, `purgeChatForUser` (called when an advertiser is deleted), the `CHAT_RETENTION_DAYS` alarm, and two admin-app buttons.
4. **Lost messages under concurrency.** Operations of one thread were not serialised: two sends racing could take the same sequence number and one overwrote the other. Reproduced against the old code in an in-memory model whose storage calls yield (`docs/backend/tests/race_probe.mjs`: 24 concurrent sends → **1** stored; after the fix **24**). Cloudflare's input gates narrow that window in production but `await`s on crypto / `fetch` open it, so the code was not safe by construction.
5. **Unread counter** was reset to 0 by any read marker, even below the newest message.
6. **Home:** every render tore down all dynamic sections and rebuilt them with a 220 ms fade (flicker, jump, second wave of poster loads); the poster loader started one task per card, downloaded the same URL several times in parallel through one shared `.part` file (corruption risk), had no limit on parallel requests, retried a broken URL on every build, and kept 18 bitmaps in a cache that was recreated on every render.
7. **Catalogue Worker:** `/catalog/manifest` still answers 404 (checked 2026-11-09 19:30 UTC): the delta feed of 18.4 is not deployed, so the apps still download `/movies` (557 KB gzip) — correctly and safely, but not incrementally. Live catalogue integrity (`docs/tools/catalogue_integrity.py`): 5,685 records = 151 movies + 65 series + 5,469 episodes, no duplicate id, no orphan episode, no record without poster or video, one series header without episodes, envelope `count` = 88 ≠ 5,685 (known, harmless).

## 4. What changed
### 4.1 Chat Worker (`cigram-ads-api`) — `docs/backend/cigram-ads-api/apply_patch_19.py` (+ `chat19_*.js`), bundle `docs/backend/deploy/cigram-ads-api/worker.js`
* Permanent history (`CHAT-RETENTION-POLICY.md`): purge / delete-message / retention / advertiser deletion can no longer delete anything (`409 history_is_permanent`, also inside the hub). Archive = a flag. Account removal and `/chat/delete-all` **erase the identity** (name, e-mail, account id) and empty the user's own view; the history stays.
* Per-thread serialisation of send / read / messages / thread update / quote / thread open; exact unread counters; `archived=all`, per-filter `counts`, `last_type`; message-text search `/admin/chat/search` (archive included).
* Targeted push: device registry (`/chat/device`, `/admin/chat/device`, `…/remove`), FCM HTTP v1 sender (service-account OAuth), outbox with retry 30 s → 6 h, one push per message id, dead-token removal, account switch on one phone, no push while the recipient has the conversation open (`{"t":"fg","on":false}` marks a background socket), payload without any credential. `PUSH_WEBHOOK_URL` still works (optional `PUSH_WEBHOOK_KEY` bearer). `GET /admin/chat/push-status` reports what is missing.
* Backups: immutable R2 segments + encrypted thread snapshot, `backup`, `backup/verify`, non-overwriting `restore`, alarm every 6 h, **private bucket only** (the public `movies` bucket is refused).
* Session check: a failure of the auth call is `503 auth_unavailable` (18.3 logic kept), plus an optional read-only fallback to the auth database when `AUTH_DB` is bound.
### 4.2 Main app (user)
* `AdsAuthGateway.classify` + `CigramSession.probe`: before a refused token is called expired, the auth service is asked from the phone. Valid there → temporary «خدمة الدعم لا تستطيع التحقق…», queue kept, retried; invalid and not renewable → expired as before. Same rule in the WebSocket path (`AdsWs`) and a back-off retry of the conversation bootstrap.
* `AdsDevice` registers the FCM token (new token, account change, daily); `AdsNotifier.showPush` de-duplicates by message id, stays silent on a signed-out phone and while the chat is on screen, shows name · preview · time · unread.
* Home: `CigramImageQueue` (single flight, 3 parallel fetches, visible > nearby > preload, cancelled / unwanted requests never fetched, 30 s → 10 min back-off for broken URLs, reset when the network returns) + `CigramImages` (unique temp file, image verified before the rename, byte-bounded shared bitmap cache, fade-in) wired into the Home loader; cached posters are decoded at once. `CigramHomeGroups`: derived rows from real fields — «أفلام الأعلى تقييماً», «مسلسلات الأعلى تقييماً», «مسلسلات تتجدد» (newest episode), «أفلام وثائقية», nationality rows only if the data says so — never over an administrator's section, ≥ 6 titles, appended last, hideable by id `derived_*`. On the live catalogue: 3 rows (30 titles each); no documentary row (2 titles), no Turkish / Korean / Arabic row (**the catalogue has no nationality field; nothing is guessed** — add `country` / `origin_country` / `language` to records to enable them). A render whose signature (plan, titles, styles, full card content) did not change is skipped: no teardown, no fade, no scroll jump.
### 4.3 Admin app
* Deleting a conversation or a message is gone from the UI (a policy dialog replaces it). Inbox: live counts on the chips, search covers the archive and the **text of every message**, «يكتب الآن…», `مؤرشفة` badge, «الإشعارات والنسخ» status screen (real answers of `push-status` and `backup`).
* `AdFcm` / `AdFcmService` / `AdInboxNotifier.showPush`: name, preview, unread, time, tap opens the conversation, de-duplicated by message id. **FCM is off until the two public Firebase settings of `com.my.newproject` (`APP_ID`, `API_KEY` in `AdFcmConfig.java`) are filled** (§7); the 15-minute poll keeps working, and the screen above names what is missing.

## 5. Not done / limits (say it plainly)
* Home was not changed into a new layout: the existing hero, rows, skeletons and snapshot-first start were kept; no visual before/after exists (no device). Turkish / Korean / international rows need data that the catalogue does not have. «Live channels» stay on their own page.
* Only the pieces that need no device were unit tested; the rendering gate, the image loader wiring and every Activity change are compile-verified.
* Conversation history in the apps is capped by the existing local store (60 messages offline); the server keeps everything and the app pages older messages on scroll.
* Content (message text) erasure for a legal request is deliberately a manual owner procedure, not an API (policy file).
* The admin app still targets API 34; `cigram-admin-api`'s `_admin_private/` objects are still publicly readable until an `ADMIN_BUCKET` is bound (DEPLOY §4, from 18.2).

## 6. Tests and builds actually run
| Run | Result |
|---|---|
| Main JVM tests (clean, build cache off for the final run, `CIGRAM_CATALOGUE` = the live 5,685-record catalogue) | **152 per flavour, 304 total (play + direct): 0 failed, 0 skipped.** New: `AdsAuthGatewayTest` +5, `AdsPushClientTest` 9, `CigramImageQueueTest` 10, `CigramHomeGroupsTest` 11 (one on the real catalogue) |
| Admin JVM tests | **99: 0 failed, 0 skipped** (93 existing + 6 new `AdPushTest`) |
| Worker suites (Node 22, the real Worker code, in-memory Durable Object storage / R2, fake FCM) | `admin_subhub` 31 · `catalog_feed` 16 · `ads_support` 23 · **`ads_chat19` 26 (new)** · `channels_conditional` 6 = **102 passed**; `build_deploy.sh` regenerates every bundle from the live snapshots (admin and channels bundles are byte-identical to the previous delivery, the ads bundle is new) |
| Builds (clean) | main `testPlayDebugUnitTest testDirectDebugUnitTest assemblePlayDebug assemblePlayRelease assembleDirectDebug assembleDirectRelease bundlePlayRelease`; admin `testDebugUnitTest assembleDebug assembleRelease bundleRelease` — all exit 0. `aapt2`: `com.Cigram.vid` 92 / 18.5.0, `com.my.newproject` 10 / 2.2.0 |

Artifacts (unsigned; sha256): main `app-play-release-unsigned.apk` `a2a33dc9…`, `app-direct-release-unsigned.apk` `71a7f0ca…`, `app-play-release.aab` `913fe131…`; admin `app-release-unsigned.apk` `1298acdc…`, `app-release.aab` `8aa96112…`. Ads Worker bundle sha256 `75a27ae0…` (`docs/backend/deploy/SHA256SUMS`).

### Your acceptance list → evidence
| Item | Evidence |
|---|---|
| Fresh install / saved catalogue / offline start / Cloudflare failure keeps content / new movie, series, episode / poster edit / interrupted sync | existing JVM suites (`CigramCatalogSyncTest`, `…StoreTest`, `…PrefsTest`) all green, incl. the live catalogue; on-screen behaviour not tested |
| No duplicate sections or carousel cards | `CigramSectionDedupeTest` (incl. live catalogue), `CigramHomeGroupsTest` (no title twice in a row) |
| Returning to Home does not rebuild | signature gate implemented, **not tested** |
| User sends → admin sees → admin replies → user receives; unread; read status; reauth; archive / restore; no purge; no deletion | `ads_support_test` + `ads_chat19_test` against the real Worker code |
| Notification to the user / to the administration, deduplicated, retried | `ads_chat19_test` with a fake FCM endpoint; app-side de-duplication in `AdsPushClientTest` / `AdPushTest`. **No real notification was sent or received** |
| Expired token, auth outage, duplicate submission, Cloudflare/FCM outage | `AdsAuthGatewayTest`, `ads_support_test`, `ads_chat19_test` |
| Network loss while sending, failed attachment upload, background notification, process restart, app update | **not tested here** (existing client queue / retry code was not changed except the bootstrap retry) |
| "Chat data is permanently safe" | **Not claimed.** Backups are verified only in memory; production is safe only after `scripts/post_deploy_smoke.sh` shows a clean `backup run` and `backup/verify` |

## 7. What you still have to do (exact)
1. `ADMIN_TOKEN=… docs/backend/scripts/preflight_backup.sh` (offline copy of the catalogue and of every conversation).
2. Deploy `docs/backend/deploy/cigram-ads-api/worker.js` (dashboard → Edit code). Optionally also `cigram-admin-api/worker.js` (the 18.4 catalogue feed, still undeployed; the apps work without it).
3. On `cigram-ads-api`: secret **`FCM_SERVICE_ACCOUNT`** (Firebase service-account JSON of project `cigram`); a **private** R2 bucket bound as **`ADS_BUCKET`** (`cigram-ads`) or `CHAT_BACKUP_BUCKET`; keep **`CHAT_KEY`** safe; check Logs for `chat_auth … unavailable` and, if present, add the service binding **`AUTH_API`** → `cigram-auth-api` or the D1 binding **`AUTH_DB`** → `cigram-users`.
4. Firebase console: add the Android app `com.my.newproject` to project `cigram`; type its `APP_ID` and `API_KEY` (public values from `google-services.json`) into `AdFcmConfig.java` and rebuild (project id and sender id are prefilled).
5. `ADMIN_TOKEN=… docs/backend/scripts/post_deploy_smoke.sh`, then one real end-to-end test: user message → admin notification with the admin app closed → reply → user notification with Cigram closed.
6. Sign both apps with your keystores; test on real phones.
Rollback of every Worker change: *Deployments → previous version*; the new storage keys are ignored by older code.

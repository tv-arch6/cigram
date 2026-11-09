# Deploying the Cigram 18.2 Workers (owner steps)

**Status: NOT deployed.** The build environment could read the two Workers' source through a read-only Cloudflare
connector but has no tool that can write a Worker, a secret, a bucket or a binding. Everything below is for the
project owner. Nothing in this file has been run against the production account.

## 0. What is in `deploy/`
| File | What it is |
|---|---|
| `deploy/cigram-admin-api/worker.js` | live `cigram-admin-api` (2026-11-08 snapshot) + `cigram-admin-api/apply_patch.py` + `subhub_providers.js` |
| `deploy/cigram-ads-api/worker.js` | live `cigram-ads-api` (same snapshot) + `cigram-ads-api/apply_patch.py` |
| `deploy/sigram-channels-api/worker.js` | live `sigram-channels-api` + `sigram-channels-api/apply_patch.py` (18.3: conditional GET for `/channels`) |
| `deploy/SHA256SUMS`, `deploy/LIVE-SOURCE-SHA256.txt` | hashes of the bundles and of the live sources they were built from |

All three bundles were built by `./build_deploy.sh <live admin worker.js> <live ads worker.js> <live channels worker.js>`, which also runs every test
(`tests/admin_subhub_test.mjs` 31, `tests/ads_support_test.mjs` 23, `tests/channels_conditional_test.mjs` 6). The patches refuse to apply when the live
code differs from the snapshot, so rerun the script against the code that is live on the day you deploy.

## 1. Before deploying
1. Copy the current code of each Worker (dashboard → Workers & Pages → the Worker → *Edit code*) into two local files.
2. `sha256sum` them. If they differ from `deploy/LIVE-SOURCE-SHA256.txt`, run `./build_deploy.sh admin.js ads.js`
   (it stops with "anchor found 0 times" if the live code moved in a way the patch cannot handle: tell the maintainer).
3. Keep the previous version: Workers → Deployments lets you roll back with one click.

## 2. Deploy
Preferred (keeps every binding, variable and secret exactly as it is): dashboard → the Worker → *Edit code* → replace the
code with `deploy/<worker>/worker.js` → *Deploy*.
With `wrangler`, a `wrangler.toml` must list the same bindings or they are removed; run `wrangler deploy --dry-run` first.
Names the code reads (from `env.*`): 
- **cigram-admin-api**: `MOVIES_BUCKET` (R2 `movies`), `ADMIN_BUCKET` (optional, see §4), `REQUESTS_KV`, `AUTH_DB`;
  secrets / vars `ADMIN_TOKEN`, `TMDB_API_KEY` or `TMDB_READ_TOKEN`, `SUBDL_API_KEY`, `SUBSOURCE_API_KEY`,
  `OPENSUBTITLES_API_KEY`, `OPENSUBTITLES_USERNAME`, `OPENSUBTITLES_PASSWORD`, `FCM_SERVICE_ACCOUNT`, `PUSH_WEBHOOK_URL`,
  `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `ADMIN_PATH_SALT`, `OS_RATE_LIMIT_10S` (optional, default 36).
- **cigram-ads-api**: Durable Object `CHAT_HUB`, `ADS_CORE`, `AUTH_API`, `MOVIES_BUCKET`, `ADS_BUCKET`;
  secrets `CHAT_KEY`, `JWT_SECRET`, `ADMIN_TOKEN`, `PUSH_WEBHOOK_URL`; vars `PUBLIC_BASE`, `CHAT_RETENTION_DAYS`,
  `AUTH_VERIFY_URL`; new optional `DEVELOPERS`, `DEV_NAME`, `DEV_AVATAR`.
Secrets are never in the bundles or the APKs; add them in Settings → Variables and Secrets.

## 3. Provider accounts (what each integration needs)
| Provider | Needs | Notes |
|---|---|---|
| OpenSubtitles.com search + download | an application **API key** (`OPENSUBTITLES_API_KEY`) and a user login (`OPENSUBTITLES_USERNAME` / `_PASSWORD`) | downloads count against the account's daily quota; VIP accounts are served from `vip-api.opensubtitles.com` (the code follows the `base_url` returned by login) |
| OpenSubtitles AI translation | the same login **plus credits** on that account | priced per character, see `GET /admin/subhub/translate/options`; every request needs the admin's explicit approval (max credits) or an approved batch budget |
| SubDL | `SUBDL_API_KEY` | owner's existing adapter; SubDL's documentation sits behind a browser challenge and could not be read |
| SubSource | `SUBSOURCE_API_KEY` | owner's existing adapter, same remark |
| TMDB | `TMDB_API_KEY` or `TMDB_READ_TOKEN` | ids, discovery feeds |

## 4. Security hardening that needs the owner (found in the audit)
**Admin-private files are publicly readable.** `adminKey()` stores the undo journal, discovery config / history, and (18.2)
subtitle jobs and approvals under `_admin_private/` in the **public** `movies` bucket when no `ADMIN_BUCKET` is bound and
`ADMIN_PATH_SALT` is empty. Checked live on 2026-11-08: `https://pub-…r2.dev/_admin_private/whatsnew/config.json` and
`…/history.json` answer **200 without any credential** (they hold sync settings and run history, no secret was found).
Fix (pick one, do it before relying on approvals / undo data):
1. Create a **private** R2 bucket (e.g. `cigram-admin-private`, no public access), bind it to `cigram-admin-api` as
   `ADMIN_BUCKET`, copy the existing objects (`_admin_private/<name>` → `<name>`), then delete the old `_admin_private/`
   objects from `movies`. The app's *المصادر* screen shows a warning until the binding exists.
2. Minimum: set a long random `ADMIN_PATH_SALT` secret (existing journals under the unsalted path are then ignored: copy them to
   `_admin_private/<salt>/…` first if you need the undo history).
The OpenSubtitles login token is kept only in the Worker's memory, never written to R2.

## 5. Smoke tests after deploying (replace `$ADMIN_TOKEN` from your own shell; never paste it in chat)
```
H="Authorization: Bearer $ADMIN_TOKEN"; A=https://cigram-admin-api.wwq-mixtv.workers.dev
curl -s $A/health | head -c 400                                   # lists /admin/subhub/* routes
curl -s -H "$H" $A/admin/subhub/providers                         # states: not_configured | configured_unverified | connected | error
curl -s -H "$H" -d '{"provider":"opensubtitles"}'  $A/admin/subhub/providers/verify
curl -s -H "$H" -d '{"provider":"subdl"}'          $A/admin/subhub/providers/verify
curl -s -H "$H" -d '{"provider":"tmdb"}'           $A/admin/subhub/providers/verify
curl -s -H "$H" $A/admin/subhub/translate/options                 # live catalogue + price per character (no credits spent)
```
`connected` appears only after a real request succeeded. A translation is **never** part of the smoke test (it spends credits):
run one deliberately from the app on a short file, with an approved limit, and check it lands in «مراجعة الجودة».

## 6. Rollback
Workers → the Worker → Deployments → select the previous version → *Rollback*. The new R2 objects (`subhub/…`) are additive and
can stay; no existing object is modified by the patches.


## 7. 18.3 additions (what changed in the Workers and what to check after deploying)
| Worker | Change | Check after deploying |
|---|---|---|
| `cigram-ads-api` | **Chat authentication.** `verifyUser` answered `401 invalid_token` (shown in the app as «انتهت الجلسة، سجّل الدخول من جديد») for EVERY failure of its call to the auth Worker's `/me`: a timeout, a 429, a 5xx, a wrong service binding, a malformed answer. Now only a real 401/403 from `/me` is a session expiry; anything else is `503 auth_unavailable` (the app keeps the session and retries), a 6 s timeout bounds the call, a token verified in the last 30 minutes keeps being served during an outage (revocation cannot be observed then; it is checked again as soon as `/me` answers), and one log line per failure (`{"evt":"chat_auth","outcome":"invalid|unavailable|grace","ms":…}`: no token, no user id). | In Cloudflare → `cigram-ads-api` → Logs: look for `chat_auth` lines with `outcome":"unavailable"`. If they appear on every request, the Worker cannot reach `https://cigram-auth-api.wwq-mixtv.workers.dev/me`: bind the auth Worker as a **service binding named `AUTH_API`** (a Worker-to-Worker `fetch` over `workers.dev` is refused by Cloudflare) or fix `AUTH_VERIFY_URL`. |
| `cigram-admin-api` | `GET /movies` is conditional: `ETag` (already sent) + `If-None-Match` → `304` without a body (R2 `onlyIf`), `cache-control: no-cache`. | `curl -sI $A/movies` shows `etag`; repeat with `-H 'If-None-Match: <that etag>'` → `HTTP 304`. The app sends the header from 18.2.1 on; older apps are unaffected (they get the full body as before). |
| `sigram-channels-api` | `GET /channels` is conditional the same way (`etag`, `cache-control: no-cache` instead of `no-store`, 304). | same check on `/channels`. A channel added in the admin app is visible at once (clients revalidate on every request). |

Without these Worker changes the app still works exactly as before: it sends `If-None-Match`, a Worker that ignores it answers 200, and the app then skips its parse / index / save / redraw work when the body hash is unchanged.


## 8. 18.4 additions — the incremental catalogue (cigram-admin-api). READY, NOT DEPLOYED
Verified against the live account on 2026-11-09: `cigram-admin-api` (modified 2026-11-07) is byte-identical to `live-snapshots/cigram-admin-api.live.js`
(sha256 `0e9c06a1…`), `cigram-ads-api` and `cigram-auth-api` match their snapshots, `sigram-channels-api` was last modified 2026-10-17. The build
environment can only READ the account, so nothing below has run against production.

| Route (public GET) | Answer |
|---|---|
| `/catalog/manifest` | `{ok, schema:1, rev, records, ids_digest, etag, updated_at, window_from, max_page}`, `ETag` + `If-None-Match` → `304`, `cache-control: no-cache`. |
| `/catalog/changes?since=<rev>[&max=<n>]` | `{ok, from, to, more, upserts:[record], removed:[id], head:{rev,records,ids_digest,etag}}`, or `{ok, full:true, reason}` when the client must download `/movies` (older than the log window, a reset revision, ahead of the server). |

How it works: after every `mutateMovies()` write (the only writer of `movies.json`) the Worker hashes the records, compares them with the previous
revision (`catalog/hashes.json`) and appends exactly the changed records to a bounded log (`catalog/feed.json`: ≤120 revisions, ≤1.5 MB). A bulk change of more
than 300 records, lost hashes or a write that cannot be proven becomes a **reset** revision: older clients download `/movies` once (never a data loss).
A `movies.json` changed outside the Worker (dashboard upload) is reconciled by the same diff once it is older than 20 s. A failure of the feed never fails the
admin's write and never touches `movies.json`. Measured with the real catalogue (5.2 MB of JSON, 5,592 records; on the wire `/movies` is gzip-compressed to ~0.56 MB): in Node the bootstrap takes
74 ms once, afterwards 0.5 ms per manifest; a one-record change is a 1.2 KB answer instead of the whole file.
Live check 2026-11-09: `GET /movies` answers `etag: W/"…"` (Cloudflare weakens the R2 ETag when it compresses), `cache-control: no-store`, ignores
`If-None-Match` (200), and `/catalog/manifest` is 404 (not deployed). The feed answers compare ETags after stripping `W/`.

Deploy (no new binding, variable or secret): dashboard → `cigram-admin-api` → *Edit code* → replace with `deploy/cigram-admin-api/worker.js` → *Deploy*.
Rollback: *Deployments* → previous version, or paste `live-snapshots/cigram-admin-api.live.js`. The two R2 objects are additive and may stay.
Smoke test:
```
A=https://cigram-admin-api.wwq-mixtv.workers.dev
curl -si $A/catalog/manifest | head -20                                  # 200, etag, rev, records equals the number of records of /movies
curl -s  "$A/catalog/changes?since=<rev from above>"                     # upserts [] / removed [] while nothing changed
# edit one title in the admin app, then:  /catalog/manifest rev is +1 and /catalog/changes?since=<old rev> contains exactly that record
```
Before the deploy the apps keep working: they get `404` for the two routes and use `/movies` (with `If-None-Match`) exactly as before.
CPU: the first manifest request parses `movies.json` once (74 ms in Node for 5.2 MB; Workers Free allows 10 ms of CPU per request: if that plan is in use the
bootstrap should be triggered by one admin write on a paid plan, or the feed left off).
`_admin_private/` is publicly readable while no `ADMIN_BUCKET` is bound (§4): the feed holds only records that are public in `movies.json`.
Not production-tested: the Worker change, the app's delta path against a real Worker, `ETag` behaviour behind Cloudflare's CDN.


## 9. 19.0 additions — permanent chat history, targeted push, backups, session fallback (cigram-ads-api). READY, NOT DEPLOYED
Built from the same live snapshot as §7/§8 by `./build_deploy.sh` (stage 1 = `apply_patch.py`, stage 2 = `apply_patch_19.py`); the bundle is
`deploy/cigram-ads-api/worker.js` (sha256 in `deploy/SHA256SUMS`). **No new binding is required to deploy it**: every new capability is optional and
reports exactly what it lacks (`GET /admin/chat/push-status`, `GET /admin/chat/backup`). Policy: `CHAT-RETENTION-POLICY.md`.

### 9.0 Order (all of it is additive; nothing is migrated, nothing is deleted)
1. `ADMIN_TOKEN=… ./scripts/preflight_backup.sh` — an offline copy of the catalogue and of every conversation (needs the OWNER token; read-only).
2. Dashboard → `cigram-ads-api` → *Edit code* → paste `deploy/cigram-ads-api/worker.js` → *Deploy*. (Rollback: *Deployments* → previous version; the new
   storage keys `dev:`, `devx:`, `ob:`, `pd:`, `bk:`, `bk_state`, `push_stats` and the thread fields `user_floor`, `last_type`, `erased_at` are ignored by the old code.)
3. `ADMIN_TOKEN=… ./scripts/post_deploy_smoke.sh`.
4. Deploy the two apps (main 18.5.0 / admin 2.2.0).

### 9.1 Push notifications — what is missing in production today (checked 2026-11-09)
* `cigram-push` (live, modified 2026-10-14) is a **broadcast** sender: it sends the topic `cigram_all` only and needs the header `X-Admin-Key`. It has no per-user
  route, and the old `notify()` of `cigram-ads-api` posted to `PUSH_WEBHOOK_URL` without any credential and dropped a notification when another one for the same
  thread was sent in the last 30 s. A private reply therefore never reached a closed app; only the 15-minute poll of the apps did.
* The main app registered no device token with the backend at all. 19.0 does (`POST /chat/device`), the admin app too (`POST /admin/chat/device`).
* **Secrets / variables to add on `cigram-ads-api`** (Settings → Variables and Secrets): `FCM_SERVICE_ACCOUNT` = the whole Firebase service-account JSON of the
  project `cigram` (the same value `cigram-admin-api` already uses; Firebase console → Project settings → Service accounts → Generate new private key, role
  *Firebase Cloud Messaging API Admin*). Optional `FCM_PROJECT_ID` (default: `project_id` of the JSON). Optional legacy webhook: `PUSH_WEBHOOK_URL` + `PUSH_WEBHOOK_KEY`
  (sent as `Authorization: Bearer`). I could not read which secrets are set (the connector shows names and code, not bindings or secrets): check them in the dashboard.
* **Admin app**: the Android app `com.my.newproject` must exist in the Firebase project `cigram` (Project settings → Your apps → Add app → Android). Then build the
  admin app with the two missing PUBLIC settings typed into `AdFcmConfig.java` (`APP_ID` = `mobilesdk_app_id` of `google-services.json`, `API_KEY` = `current_key`; `PROJECT_ID` and `SENDER_ID` are already filled with the values the main app uses). Plain Java constants on purpose: the phone IDE (CodeAssist) cannot evaluate Gradle expressions. Left empty, the admin build still works and keeps the 15-minute poll; the inbox menu
  «الإشعارات والنسخ» lists exactly which setting is missing. The main app needs nothing new (its Firebase settings are already in `CigramFcm`).
* Check: open the admin inbox → «الإشعارات والنسخ»: *FCM على الخادم: مُفعّل*, *أجهزة الإدارة المسجّلة ≥ 1*. A real test = write from a user account, close the admin
  app, wait for the notification (do not trust any "test" that does not leave the Worker).
* Delivery rules implemented: targeted tokens only (never a topic), data message (no credential or token inside), `collapse_key` per conversation, retry 30 s / 2 min /
  10 min / 1 h / 6 h (FCM TTL 3 days), one push per message id (`ob:` outbox + `pd:` markers), dead tokens removed (`UNREGISTERED`/`NOT_FOUND`), a phone that signs in with
  another account stops receiving the previous account's pushes, no push while the recipient has the conversation open.

### 9.2 Backups
Bind a **private** R2 bucket to `cigram-ads-api` as `ADS_BUCKET` (`cigram-ads` exists) or `CHAT_BACKUP_BUCKET`. The code refuses the public `movies` bucket. First run: `POST /admin/chat/backup`
(then the alarm does it every 6 h), check with `GET /admin/chat/backup` and `/backup/verify`. Keep `CHAT_KEY` (secret) — backups are encrypted with it.

### 9.3 «انتهت الجلسة» in the chat although the account is signed in
Two causes were found, one in each place; both are handled, one needs a dashboard check:
1. **Worker (needs deploying + a binding check)**: the live Worker (2026-10-07) answers `401 invalid_token` for *any* failure of its call to the auth Worker (a timeout, a 429, a 5xx,
   Cloudflare error 1042 for a Worker-to-Worker call over `workers.dev`). After 9.0 it answers `503 auth_unavailable` for those and logs `{"evt":"chat_auth","outcome":…}`. If that line
   shows `unavailable` on every request: add a **service binding named `AUTH_API`** → `cigram-auth-api`, or bind the D1 database `cigram-users` as **`AUTH_DB`** (19.0 then verifies the session
   read-only from the database when the auth Worker cannot be asked; it writes nothing and does not touch the session).
2. **App (fixed in main 18.5.0)**: a refused token is now checked against the auth service itself from the phone before the app says «sign in again». If the auth service accepts it, the chat
   shows «خدمة الدعم لا تستطيع التحقق من حسابك الآن», keeps the queue and retries; only a token the auth service also rejects (and that cannot be renewed) is reported as expired.

### 9.4 Not verified in production
The ads Worker is not reachable from the build environment (egress policy: 403), so nothing in §9 ran against the real Worker: the Worker code passes `tests/ads_chat19_test.mjs` (24) and the two
older ads suites against the real code with in-memory Durable Object storage, in-memory R2 and a fake FCM endpoint; a real FCM delivery, a real R2 backup and the real auth path are unproven until you run
`scripts/post_deploy_smoke.sh` and the end-to-end check in §9.1.

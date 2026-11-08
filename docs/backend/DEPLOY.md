# Deploying the Cigram 18.2 Workers (owner steps)

**Status: NOT deployed.** The build environment could read the two Workers' source through a read-only Cloudflare
connector but has no tool that can write a Worker, a secret, a bucket or a binding. Everything below is for the
project owner. Nothing in this file has been run against the production account.

## 0. What is in `deploy/`
| File | What it is |
|---|---|
| `deploy/cigram-admin-api/worker.js` | live `cigram-admin-api` (2026-11-08 snapshot) + `cigram-admin-api/apply_patch.py` + `subhub_providers.js` |
| `deploy/cigram-ads-api/worker.js` | live `cigram-ads-api` (same snapshot) + `cigram-ads-api/apply_patch.py` |
| `deploy/SHA256SUMS`, `deploy/LIVE-SOURCE-SHA256.txt` | hashes of the bundles and of the live sources they were built from |

Both bundles were built by `./build_deploy.sh <live admin worker.js> <live ads worker.js>`, which also runs every test
(`tests/admin_subhub_test.mjs` 30 tests, `tests/ads_support_test.mjs` 11 tests). The patches refuse to apply when the live
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

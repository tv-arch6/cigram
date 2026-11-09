# Cigram 18.2 — backend changes (NOT deployed; see DEPLOY.md)

The two Android apps talk to Cloudflare Workers. Their current source was read **read-only** from the Cloudflare
account (`workers_get_worker_code`, 2026-11-08). Nothing was deployed, no secret was read or written, no data was
changed. This folder contains additive patches against that live source, the diffs, and local tests.

| Worker | Live source sha256 (bundle as deployed) | Patch | Diff |
|---|---|---|---|
| `cigram-ads-api` (chat / ChatHub) | `3bcc07258da0c59a2110735b21cd0404a8f4c07336e071ce89752933cab7a011` | `cigram-ads-api/apply_patch.py` | `cigram-ads-api/worker.diff` |
| `cigram-admin-api` (catalogue, subtitles, discovery) | `0e9c06a17b4a1a6707c6d07599604f62ad6958ba91c5a553f87f30cd07ed7289` | `cigram-admin-api/apply_patch.py` | `cigram-admin-api/worker.diff` |

`apply_patch.py` refuses to run when any anchor is missing or ambiguous (the live code changed since 2026-11-08).

## What the patches do

### cigram-ads-api — Cigram Private Support / Developer Inbox
- **No advertising consent for support messages.** Only an advertising `order` message still requires it.
  (Live today: every message needs the ads-policy consent; the app shows that policy and asks explicitly until this is deployed.)
- **Developer accounts and roles.** Optional secret `DEVELOPERS` (JSON array of `{id, name, avatar, role: owner|support, token_sha256}`);
  `ADMIN_TOKEN` keeps working as the owner. `support` developers can read, answer and archive; restricting (ban), purge,
  delete-message, export, convert-order and template changes are **owner-only** (HTTP 403).
- **Public developer identity on messages** (`dev: {name, avatar}`); the internal developer id (`dev_id`) is returned only on
  admin routes. Optional vars `DEV_NAME`, `DEV_AVATAR` (https) for the owner account.
- **Presence** returns `developer` (public) and `last_active` (last time a developer was connected).
- **Internal ids only for admins:** the user's thread view no longer contains `id`; the thread stores the auth `uid` and the
  admin view returns it as `user_id`; admin search matches the internal thread id and `user_id`.
- Push to the user: title = developer's public name, extra `dev_name` and `ts` (the app's notification shows name, preview, time).

### cigram-admin-api — subtitle providers, translation, approvals, Discovery
Source of truth for the provider code: `cigram-admin-api/subhub_providers.js` (inserted by `apply_patch.py`).
- **OpenSubtitles (search + download), rewritten from the official reference** (https://ai.opensubtitles.com/api-docs, read 2026-11-08):
  `User-Agent: CigramAdmin v18.2`, `Api-Key` + `Authorization: Bearer` (both on `/download`), parameters sorted / lowercase / ids without
  leading zeroes / no server-side ordering, TMDB id first then IMDb id (episodes: `parent_*_id` + season + episode), login session kept
  in memory with the returned `base_url` (VIP host) and **never re-sent after a 401** (the reference forbids it), expired token → one
  re-login, local throttle under the documented 40 requests / 10 s, `429` → `provider_rate_limited` with the retry delay, download quota
  answer → `os_quota_exceeded` with the reset time, the temporary download link must stay on `*.opensubtitles.com|org`.
  AI-translated uploads are flagged (`ai_translated`) and ranked below human ones.
- **OpenSubtitles AI translation** (`GET /admin/subhub/translate/options|status`, `POST …/estimate|translate|translate/batch`): the
  catalogue and per-character prices are read live from `GET /ai/info/translation`; a request needs the admin's approval
  (`approved_by`, `approved_at`, `max_credits ≥` the estimate) **or** a batch envelope (`budget`, `ids`, `api`) that is debited per title; the
  result is checked here (same cue count, **source timestamps restored**, Arabic share, the provider's own `quality.valid`) and waits in
  `QUALITY_REVIEW`: a translation never publishes. Spending credits needs an OpenSubtitles account with credits (not available to the build).
- **Provider health**: `GET /admin/subhub/providers` → `not_configured | configured_unverified | connected | error` per provider
  (`connected` only after a real request succeeded; `opensubtitles_ai` only after a translation really completed);
  `POST …/providers/verify` runs one free, real request per provider. The reply also warns when admin-private data shares the public bucket.
- **Approvals + no silent overwrite**: `POST /admin/subhub/approval` (approve / revoke) records which title has an approved subtitle.
  `/admin/subs/fetch` (upload mode) and `/admin/subs/season` refuse to replace an existing subtitle (`subtitle_exists`) and never replace an
  approved one without `authorization: "OVERWRITE-APPROVED:<id>"` (`approved_subtitle_protected`).
- Request log lines carry only route, status, duration and request id (no body, no token, no subtitle text).
- Discovery feeds `all|movie|tv:trending_day`, `movie:top_rated`, `tv:top_rated`, `tv:on_the_air` (TMDB v3) and the management decisions
  (`POST /admin/discover/decision`, `GET /admin/discover/decisions`; a decision never imports or publishes anything).
- A **library** title without TMDB/IMDb id is refused (`400 subs_ids_required`) instead of being matched by its name.
- **SubDL / SubSource** keep the owner's existing adapters (SubDL's documentation is behind a browser challenge: never bypassed).
  Other sites (Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles): **not integrated**, no official API access method was found or
  verified; no scraping.

## Local tests (no network, no Cloudflare account)
```
./build_deploy.sh <live admin worker.js> <live ads worker.js>     # applies both patches, syntax-checks, runs both suites, writes deploy/
node tests/admin_subhub_test.mjs deploy/cigram-admin-api/worker.mjs   # 30 tests: in-memory R2, TMDB / SubDL / OpenSubtitles CONTRACT stubs
node tests/ads_support_test.mjs  deploy/cigram-ads-api/worker.mjs     # 11 tests: real ChatHub + routing, in-memory storage, HS256 auth
NODE_USE_ENV_PROXY=1 node tests/live_probe.mjs deploy/cigram-admin-api/worker.mjs   # the patched code against the REAL servers, no valid credentials
```
The stubs enforce what the OpenSubtitles reference demands, but they are **contract** stubs: a green run does not prove that a real
account is accepted. `LIVE-PROBE-RESULT.txt` is the real-server run (public translation catalogue parsed; rejected keys surface as clean errors;
no authenticated success possible without credentials).

## Deploy
See `DEPLOY.md` (exact owner steps, secrets list, smoke tests, rollback, and the storage-hardening step).

## Websites named by the project owner (Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles)
Not integrated. Their access methods and terms were not verified; never bypass CAPTCHA, logins, rate limits or anti-bot protection.

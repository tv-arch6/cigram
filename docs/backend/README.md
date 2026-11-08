# Cigram 18.2 — backend changes (NOT deployed)

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

### cigram-admin-api — CigramSubtitleHub, Discovery
- Discovery feeds `all|movie|tv:trending_day`, `movie:top_rated`, `tv:top_rated`, `tv:on_the_air` (TMDB v3:
  `/trending/{movie|tv}/day`, `/movie/top_rated`, `/tv/top_rated`, `/tv/on_the_air`). Same cards, same duplicate detection.
- `POST /admin/discover/decision`, `GET /admin/discover/decisions` — management decisions (review / approved / rejected / imported)
  in the admin-private R2 file `whatsnew/decisions.json`. A decision never imports or publishes anything.
- `GET /admin/subhub/providers`, `POST /admin/subhub/translate`, `POST /admin/subhub/translate/batch` — the translation
  **contract**: input validation (content id exists, source file parses, target = Arabic, batch ids unique, budget ≥ estimate,
  approval timestamp present). **No provider adapter is implemented**: every provider answers `501 provider_not_implemented`
  and nothing is sent or charged. Reason: the OpenSubtitles AI translation endpoints (https://ai.opensubtitles.com/api-docs)
  and SubDL translation (https://subdl.com/api-doc) could not be read from the build environment (network policy), and
  endpoints are never guessed. To enable one: implement `SUBHUB_PROVIDERS.<id>` from the official docs, add its secret in
  Cloudflare, test with a real account, then set `implemented: true`.
- Subtitle search order: **TMDB id first**, then IMDb id, then (free-text searches only) the name.
- A **library** title without TMDB/IMDb id is refused (`400 subs_ids_required`) instead of being matched by its name.

## Local tests (no network, no Cloudflare account)
```
python3 cigram-ads-api/apply_patch.py   <live ads worker.js>   /tmp/ads.patched.mjs
python3 cigram-admin-api/apply_patch.py <live admin worker.js> /tmp/admin.patched.mjs
node tests/ads_support_test.mjs   /tmp/ads.patched.mjs     # 11 tests: real ChatHub + routing, in-memory storage, HS256 auth
node tests/admin_subhub_test.mjs  /tmp/admin.patched.mjs   # 14 tests: in-memory R2, stubbed TMDB / SubDL endpoints
```
Results of the delivery run: 11/11 and 14/14 passed. The same chat tests against the **unpatched** live worker: 3/11
(the others document today's behaviour: consent required for support text, internal id in the user's thread view, no
developer accounts / identity / last-active).

## Deploy (by the project owner)
1. `wrangler deploy` the patched files (or apply the diff to the original sources and build).
2. Secrets / vars as needed: `DEVELOPERS` (optional), `DEV_NAME`, `DEV_AVATAR` (ads-api).
3. Smoke-test with the admin app (Developer Inbox, Subtitle Center «طلب ترجمة» shows the 501 explanation, Discovery new chips).

## Websites named by the project owner (Podnapisi, SubtitleCat, Addic7ed, TVSubtitles, Moviesubtitles)
Their access methods, terms and robots rules could **not** be checked (DNS refused by the build environment's network
policy). No adapter was written for them. Do not add one without an officially permitted access method; never bypass
CAPTCHA, logins, rate limits or anti-bot protection.

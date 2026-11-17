# Cigram Plus — server contract

The server side is implemented in `plus-worker/worker.js` (separate Cloudflare Worker `cigram-plus-api` + D1).
The app calls `CigramPlus.PLUS_BASE` = `https://cigram-plus-api.wwq-mixtv.workers.dev`.
Until it is deployed the app shows «خدمة التفعيل غير متاحة بعد» and nothing else changes.
To mount the same routes inside the auth worker instead, copy the handlers and set `PLUS_BASE = AUTH_BASE`.

User routes: header `Authorization: Bearer <session token>` (the token the app already uses). The worker identifies the user by binding the auth worker's D1 as `AUTH_DB` (tokens are stored there as base64(SHA-256(token)) in `sessions`). CORS is open so the website can call it.

## Plans (the SERVER decides; the app texts are informational)
| Code plan | Months | List price | Who may redeem |
|---|---|---|---|
| intro | 3 | 1,500 IQD | an account that never redeemed anything (once) |
| standard | 6 | 10,000 IQD | anyone, any time; extends a running Plus |

An intro code used by an account that already redeemed is refused with `409 intro_not_eligible` and stays unused.
"Continuous" = renewal by a new standard code; there is no automatic charging.

## GET /plus/status
`200 {"ok":true,"plan":"plus"|"free","plus_until":<epoch ms>,"activated_at":<epoch ms of the last activation>,"intro_eligible":true|false}`  (the app shows "from activated_at to plus_until")

## POST /plus/redeem   `{"code":"ABCD-1234-EFGH"}`
Success `200 {"ok":true,"plan":"plus","plus_until":…,"activated_at":…,"months":3|6,"code_plan":"intro"|"standard"}`
(plus_until = later of now / current end, plus N calendar months.)
Failure `400 invalid_code` · `409 code_used` · `409 intro_not_eligible` · `410 code_expired` · `429 rate_limited` · `401 login_required` · `501 auth_not_configured`

## Admin (header `X-Admin-Key`)
`GET /admin/plus` (page) · `POST /admin/plus/codes {plan,count,note,expires_at}` · `GET /admin/plus/stats` · `GET /admin/plus/user?user_id=` · `POST /admin/plus/grant {user_id,months}` · `POST /admin/plus/revoke {user_id}`

## Security
Codes stored as HMAC-SHA256 (secret CODE_PEPPER), single use with an atomic claim, rate limiting, events log, Plus ends by date with no cron.
Not solved: feature gating happens on the device, so a modified APK could bypass quality/PiP limits; only server-signed stream URLs would stop that.
Deploy steps: `plus-worker/README.md`.

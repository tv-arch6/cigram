# cigram

- `admin/` — Cigram Admin (Android, Java). Build needs the admin token: see `admin/admin-secrets.properties.example`.
- `worker/` — the `cigram-admin-api` Cloudflare Worker: `baseline/` (as deployed), `src/` (What's New section),
  `build.mjs` → `dist/` (deployable), `test/` (Node tests).
- `docs/WHATS-NEW.md` — «ما الجديد؟» discovery & import: design, endpoints, configuration, deployment, tests, limitations.

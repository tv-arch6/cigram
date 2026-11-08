# Cigram Web — Transformation Notes (2026-11-08)

This document records the analysis of the three supplied projects and the
changes applied to the website in this iteration. It is written to be honest
about **what was verified locally** versus **what depends on the live Cloudflare
backend** (which is not reachable from the build environment used for this work).

---

## 1. Projects analyzed

| Archive | What it is | Role |
|---|---|---|
| `site_e8906b2f.zip` | The existing Cigram website (vanilla-JS SPA, ~6.8k LOC JS, ~1.4k LOC CSS) | **Transform target** — imported into this repo and improved in place |
| `H1.zip` | Cigram Android app **18.2.0** (full Android Studio source, 201 Java files, XML resources, fonts, branding) | **Primary public reference** — design system, icons, fonts, palette |
| `A1.zip` | Cigram **Admin** Android app **2.0** (full source) | **Admin / data-model reference** |

Key architectural finding: the existing website is **already a mature,
feature-complete platform** — not a skeleton. It has a hash router, RTL-first
Arabic UI, PWA (manifest + service worker), light/dark theming that follows the
device (and can be forced by the admin app's `/home-layout`), a robust
`cigram.js` adapter that converts the real `GET /movies` + `GET /home-sections`
responses into the site's catalog shape (movies, series, seasons, episodes,
embedded-episode legacy formats, section ordering), a unified video player
shared between movies and channels, subtitles (SRT) with a fallback proxy, live
TV (HLS / DASH / raw-TS / YouTube-embed / stream-proxy), Cigram Plus, auth, and
a landing/download page.

Because the project rules require **preserving working functionality** and
**keeping the existing architecture where it is reliable**, the work this
iteration is a *focused, verifiable design-system alignment* on top of the
working platform — not a ground-up rewrite that would risk regressing a
production app.

## 2. Backend / API contract (read from `js/config.js`, unchanged)

| Concern | Endpoint |
|---|---|
| Metadata / content | `https://cigram-admin-api.wwq-mixtv.workers.dev` (`GET /movies`, `GET /home-sections`, `/carousel`, `/home-layout`) |
| Authentication | `https://cigram-auth-api.wwq-mixtv.workers.dev` |
| Cigram Plus | `https://cigram-plus-api.wwq-mixtv.workers.dev` |
| Live channels | `https://sigram-channels-api.wwq-mixtv.workers.dev/channels` |
| Subtitle proxy | `https://cigram-subs.wwq-mixtv.workers.dev` |
| Stream proxy | `https://cigram-stream.wwq-mixtv.workers.dev` |
| R2 media | `pub-eabe41d14a0644e5800344c99ef4d971.r2.dev`, `pub-6ce52a4bbf2543ee88ad9883ae42a806.r2.dev` |
| App download | `https://cigram.wwq-mixtv.workers.dev/` |

**No endpoints were invented, renamed, or removed. No backend/schema change is
required by this iteration.** The site continues to read the existing saved
backend data; no content is hardcoded.

## 3. Changes applied — Unified design system (app ↔ web)

The single clear mismatch between the website and Cigram 18.2.0 was the **dark
palette**. The app's real identity (extracted from `H1/app/src/main/res/values`
and `values-night/colors.xml`) is a **neutral charcoal** family; the website was
using a **teal-black** family. The brand red already matched.

| Token | App 18.2.0 (`colors.xml`) | Website before | Website now |
|---|---|---|---|
| Page background | `#0E0E10` | `#00121A` (teal) | **`#0E0E10`** |
| Surface / card | `#1B1B1F` | `#0B2733` | **`#1B1B1F`** |
| Raised surface | `#222226` | `#0F2F3C` | **`#222226`** |
| Stroke / divider | `#34343A` | `#1F4553` | **`#34343A`** |
| Secondary text | `#C7C7CC` | `#C3CDD2` | **`#C7C7CC`** |
| Tertiary text | `#8E8E94` | `#8796A0` | **`#8E8E94`** |
| Brand red (accent) | `#E21B14` | `#E21B14` ✓ | `#E21B14` |
| Brand gold (Plus / rating) | `#F2C14E` | `#F5C518` | **`#F2C14E`** |

Implementation detail: the retune is a **pure hue swap of literal values**
(dark-only; the `html[data-theme='light']` palette, which already matched the
app's light `colors.xml`, was left untouched). No selectors or structure were
refactored, so behavior is identical — only the colour shifts teal → charcoal.
Changed centrally in `css/v2.css` (authoritative token layer) and `css/app.css`
(base + hardcoded gradient/chip literals via `--shade-rgb`), plus the browser
chrome colour in `index.html`, `manifest.webmanifest`, and `js/theme.js`.

The **IMDb rating badge** (`#F5C518`) and the player's **intro/skip seek
markers** were intentionally kept at IMDb-yellow — they are not Plus/brand-gold
elements — while the `--gold` design token (rating stars) now matches the app.

The icon system was reviewed and **already matches the app**: `js/icons.js`
ships the same Google Material Symbols (Outlined, weight 400) that the app uses
as Android vector drawables (`nav_account` → `account_circle`, `nav_channels`
→ `smart_display`, `nav_search` → `manage_search`, etc.), and the subtitle
fonts (`alyamama`, `mohanad`) are the same font files the app ships. No icon or
font change was needed.

The service worker shell version was bumped `v13` → `v14` so returning users
re-cache the restyled shell.

## 4. Verification

### Automated (ran locally, all green)
- `node test/promo-site.test.mjs` → **9/9 passed**
- `node test/stream-site.test.mjs` → **21/21 passed**

### Playwright (Chromium, backend simulated-offline by aborting all non-local requests)
- **0 uncaught JS exceptions** across desktop (1440×900) and mobile (iPhone 13),
  over routes `#/`, `#/channels`, `#/search`, `#/account`.
- Dark mode renders charcoal: `document.body` background = `rgb(14,14,16)`
  (`#0E0E10`), `--surface` = `#1b1b1f`, `--accent` = `#e21b14`,
  `meta[theme-color]` = `#0E0E10`.
- Light mode (default `prefers-color-scheme: light`) renders `#f4f5f7` — matches
  the app's light background.
- Browser **Back** works; each route populates `#view`.
- Mobile: bottom nav present, **no horizontal overflow**.
- With the backend unreachable, content areas show the loading spinner and the
  shell stays intact (graceful degradation) — no broken layout, no thrown error.

## 5. Not verified here (requires the live Cloudflare backend)

This build environment's network policy blocks outbound requests to the Cigram
workers (egress proxy returns `403` for `cigram-admin-api`,
`sigram-channels-api`, etc.), so the following were **not** exercised against
live data and must be confirmed on deploy:

- Homepage sections / carousel from `GET /movies` + `GET /home-sections`.
- Movie & series detail population, posters/backdrops from R2.
- Search over real catalog, live channel list + stream playback.
- Login / registration / session via `cigram-auth-api`.
- Cigram Plus entitlement via `cigram-plus-api`.
- Subtitle loading via R2 / `cigram-subs` proxy.

The adapter and views were read and are structurally sound for these responses,
but a live smoke test on `*.workers.dev` + R2 should be run after deployment.

## 6. Deployment (unchanged, Cloudflare-first)

Static site: deploy the repo root to Cloudflare Pages (or any static host). The
`_headers` file and PWA assets are included. All dynamic data continues to come
from the existing Workers + R2 listed in §2; nothing in this change touches
server-side authorization or exposes admin tokens/keys (none are present in the
frontend, confirmed).

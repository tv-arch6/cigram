# Cigram 2026 — Verification Report

Environment: Linux container, OpenJDK 21, Gradle 8.14.3, Android Gradle Plugin 8.13.0, Android SDK platforms 34 / 36,
build-tools 35 / 36. Emulators: Android 12 (API 31) and Android 15 (API 35), x86_64, **software rendering without
KVM** (every frame is slow; timings are not representative of real devices).

Network: the environment's policy refuses the Cigram backend hosts (`cigram-*.wwq-mixtv.workers.dev` → HTTP 403 on
CONNECT), so no test could load the live catalogue, log in, play a real title, send a chat message or upload a file.

## Builds
| Build | Result |
|---|---|
| Original main app `assemblePlayDebug` (baseline, untouched sources) | ✅ passed |
| Original admin `assembleDebug` (baseline) | ✅ passed |
| Main `assemblePlayDebug` after every change set | ✅ passed (javac: 0 errors) |
| Main `assemblePlayRelease` · `assembleDirectRelease` · `bundlePlayRelease` (incl. lint-vital) | RESULT_MAIN_RELEASE |
| Admin `assembleDebug` · `assembleRelease` | RESULT_ADMIN_RELEASE |
| Admin unit tests (`WnLogicTest`, `WnPipelineTest`) | RESULT_ADMIN_TESTS |

Release APKs are unsigned here (no keystore in the archives); both projects now sign automatically when
`keystore.properties` or the `CIGRAM_*` variables are present.

## Tests performed (emulator)
| Area | Test | Result |
|---|---|---|
| Problem No. 1 | System splash attributes before / after (Android 12 `StartingSurfaceDrawer` log) | ✅ before `color ff0a0608 icon ColorDrawable` (empty) → after `color ff0e0e10 icon BitmapDrawable` (logo) |
| Problem No. 1 | Home after cold start (offline): overlay gone, charcoal page, new header logo | ✅ |
| Home | Section headers without red bars, «المزيد» without `‹` | ✅ (screenshot) |
| About | Page, RTL order, founder portrait (tilted, 20 % corners, dark square behind), version footer | ✅ (screenshots) |
| Terms | Page, sections, version | ✅ (screenshot) |
| TV | Forced TV mode: TV edition starts, rail on the right, D-pad navigation down the rail, Settings panel opens, focus ring visible, rail scrolls with focus | ✅ (screenshots, after fixing the rail overflow found in this test) |
| Admin | Starts, new palette, token dialog; error card explains the blocked backend | ✅ (screenshot) |
| targetSdk 36 | Android 15 emulator: RESULT_API35 |

## Not tested (needs the backend, a real device, or both)
- Catalogue rendering with real posters, scrolling performance, watermark on real posters.
- Search with a real index (the background-parse path was compiled and reviewed, not exercised with data).
- Login, registration with the consent box, Terms acceptance sent to the server.
- Movie / series / episode / live playback, subtitles, quality menu with real tracks, error messages for each category.
- Casting (Google Cast needs Play services; DLNA needs devices on the same network).
- Chat: text, voice, image, video up to 1 GB, resume after failure (server limit unknown).
- Advertising: AdMob fill / no-fill fallback, popup, splash ad timing, impressions / clicks.
- Admin: content CRUD, uploads, campaigns, inbox notifications (job scheduled; needs the backend to return threads).
- Real Android TV hardware, overscan, 4K panels, remotes with long-press; tablets; light-mode on every page.

## Device / backend dependencies and risks
- The ads worker must accept 1 GB videos at `/chat/upload/init` (client sends `size`; a 413 is explained to the user).
- The auth worker should store `terms_version` / `terms_accepted_at` from `/register`.
- Creative `name` is sent with `/admin/creatives/save`; if the worker drops unknown fields the admin app falls back to
  the title / type label.
- targetSdk 36 on devices with Android 15/16: covered by the edge-to-edge shim and back opt-out; pages that draw their own
  full-screen UI keep their behaviour. A complete pass on a physical Android 15/16 phone is still required before release.

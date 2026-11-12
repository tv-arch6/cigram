# Changelog — Cigram Player

## Plus services (latest)
- The Cigram app now also sends `plus` (true/false). **`plus=false` → this player caps the picture at 720p and disables Picture-in-Picture** (they are Cigram Plus services); `plus=true` or no `plus` extra (standalone use) → unrestricted. Nothing else changed.

## 1.0.0 + AdMob (latest change)
- **Google AdMob added to the player** (`AdMobAds`): an **App Open** ad when the app is opened from its icon (≤ once / 4 h) and an **interstitial** when the user presses watch-now (Cigram hand-over, play button, continue-watching item, next-episode button). Nothing mid-film, on pause or on the automatic next episode.
- Google UMP consent (EU/UK) before any ad; SDK started on a background thread; a slow/missing ad never blocks playback (3.5 s limit).
- AdMob IDs live in `res/values/admob.xml` — currently **Google test IDs** (no earnings) until you create the app in AdMob and paste your IDs (`ADMOB-SETUP.md`).
- The in-house ad system is now behind `AppConfig.OWN_ADS_ENABLED` (**off**); its consent dialog/screens appear only when it is on.
- New manifest entries: `AD_ID` permission, AdMob `APPLICATION_ID`, `MobileAdsInitProvider` removed (manual start after consent). New dependencies: `play-services-ads:23.4.0`, `user-messaging-platform:3.1.0` (same as the main app).
- Main app: the in-house break before an episode is skipped when the player app is installed (no double ad).
- Privacy screen now describes AdMob and offers the EU/UK "privacy options" button.
- **Compliance documents below describe the earlier no-AdMob build where noted; the AdMob changes override: advertising ID = used, third-party SDK = Google AdMob.**

## 1.0.0 (Play-readiness rework of the v1 companion player) — NOT built or run on a device in the authoring environment

### Platform / build
- `compileSdk` 35 → **36**, `targetSdk` 35 → **36** (Play requires API 36 for new apps and updates from 2026-08-31). AGP 8.13.0 / Gradle 8.13 unchanged (they support API 36).
- `versionName` 1.0 → 1.0.0 (`versionCode` stays 1 for the first upload; raise for each upload).
- Release signing stays outside the build file (`keystore.properties` / `CIGRAM_*` env vars); **`bundleRelease` now fails fast without a key**.
- `bundle { language.enableSplit = false }`; release is non-debuggable, R8 + resource shrinking on.
- Removed `usesCleartextTraffic`; replaced by `network_security_config.xml` (media links may be http, the Cigram ads server is https-only).
- `android:enableOnBackInvokedCallback="true"` + `OnBackInvokedCallback` (API 33+) — Back would have stopped working at targetSdk 36 with the old `onBackPressed()` only.
- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE` and, since AdMob was added, `com.google.android.gms.permission.AD_ID`. No install-packages, storage, media, foreground-service, boot or location permission. No own services/receivers/providers (the AdMob/UMP libraries add their own components through manifest merging).

### Standalone application
- `HomeActivity` is now a real screen: play a pasted link, open a video file from the device (system picker, no storage permission), continue-watching list (long-press to remove), privacy & ads screen, optional shortcut to the Cigram app **only if installed** (never an install prompt), content-rights notice.
- New `PrivacyActivity`: what is collected/sent, ad-statistics switch, reset of the installation id, delete watch list, privacy-policy link (hidden until `AppConfig.PRIVACY_POLICY_URL` is set).
- `PlayActivity` accepts: Cigram hand-off (`com.cigram.player.action.PLAY`), `ACTION_VIEW` for video/HLS/DASH mime types, http(s) links ending in .mp4/.m3u8/.mpd/.mkv/.webm, `content://` from file managers, `ACTION_SEND text/plain` (shared link), and `cigram-player://play?url=…`.
- Bad/missing/unsupported links never crash: the user lands on the home screen with a message.

### Playback
- Kept ExoPlayer 2.19.1 (MP4/MKV/WebM/TS, HLS, DASH, RTSP) — **not** migrated to Media3 (cannot be verified without the SDK; see risk R7).
- Added: external subtitles from the device (SRT/VTT/ASS/TTML) in addition to the subtitle URL; automatic retry (2 s / 4 s / 8 s) on network loss; clear messages for expired/unauthorised (401/403), removed (404/410), server (5xx), DRM-protected (not supported), unsupported-format and missing-file errors; rotation button + orientation that follows the video on phones; PiP aspect ratio from the video; "skip intro" button and **next-episode** (auto-play countdown with cancel) from `skip_segments` / `series_episodes_json`; continue watching.
- The ExoPlayer is created in `onStart` and released in `onStop`.

### Advertising and privacy
- (superseded by the AdMob entry above) In-house ads from `cigram-ads-api`, labelled «إعلان»; hide/report. Now off by default.
- **Only the labelled button of an ad is clickable** (previously the whole picture opened the advertiser — accidental-click risk).
- **Ad statistics need consent.** Before the first ad the user is asked once (both answers continue to playback). Declined → no installation id, no impression/click events, no id in reports; ads are still fetched (language + app version only). Switch, reset and delete are in *Privacy & ads*.
- No ads on local files; none in Picture-in-Picture; none when the caller passes `ads=false`.
- Ad video creatives must be https.

### Security hardening of the exported Activity
- Allowed schemes: http, https, rtsp(s), content. **file:// is rejected.**
- Request headers: allow-list (User-Agent, Referer, Origin, Cookie, Authorization, Accept, Accept-Language), max 12, CR/LF removed.
- Links that needed headers are never stored in the watch list.

### Main Cigram app (`CigramPlayerLauncher`)
- Now also hands over `series_episodes_json` (if ≤ 150 000 chars, Binder limit), `skip_segments` and `ads`.
- Unchanged: forwards only direct media links; embed pages, unknown types, a missing player or any exception fall back to the built-in player; `<queries><package com.cigram.player/>` for package visibility; no APK download/install of the player anywhere.

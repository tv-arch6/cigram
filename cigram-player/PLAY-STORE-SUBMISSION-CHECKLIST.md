# Play Store submission checklist — Cigram Player

Legend: ☐ to do by you · ✔ done in the project (verified by inspection only unless stated)

## A. Project
- ✔ applicationId `com.cigram.player`, versionCode 1, versionName 1.0.0, label «مشغل سيكرام», launcher icon (adaptive + legacy PNG; placeholder artwork)
- ✔ compileSdk 36 / targetSdk 36 / minSdk 24
- ✔ permissions: INTERNET, ACCESS_NETWORK_STATE, AD_ID (AdMob); no install-packages
- ✔ release: non-debuggable, R8 + shrinkResources, mapping file produced, `bundleRelease` refuses without a key
- ✔ no native libraries (64-bit / 16 KB page-size requirement not applicable to project code)
- ☐ set `AppConfig.PRIVACY_POLICY_URL` (and `SUPPORT_EMAIL`)
- ☐ replace the placeholder launcher icon with final artwork
- ☐ bump `versionCode` for every upload

## B. Build and sign (see BUILD-INSTRUCTIONS.md)
- ☐ Build succeeds: `./gradlew :app:bundleRelease` (**not yet run by the author**)
- ☐ Review `app/build/reports/lint-results-release.html` and `missing_rules.txt` (if any)
- ☐ Verify signature with `jarsigner -verify`; keep `mapping.txt`
- ☐ Upload key backed up; Play App Signing enabled

## C. Device tests before upload (not run by the author)
- ☐ Install `assembleRelease` APK on Android 8, 12, 14 and **16** (phone) + one tablet
- ☐ Home → paste an https MP4 → plays; paste HLS → plays; bad link → message, no crash; `file://` → rejected
- ☐ Open a video from the file manager (VIEW content://) and "Share" a link from a browser
- ☐ Subtitle URL (SRT) and "CC+" local subtitle
- ☐ Rotation button, auto-orientation on phone, PiP (Home button), resume after reopening, Back button/gesture (predictive back on Android 14+)
- ☐ Airplane mode during playback → toast + 3 retries → readable error; link with 403/404 → matching message
- ☐ First ad → consent dialog; "لا، شكراً" → ads without id/events (check with a proxy that no `device` is sent); "موافق" → events reach the worker; Privacy screen switch/reset/delete work
- ☐ Ads: label visible, skip from 2 s, only the button opens the advertiser, ad server offline → film starts within ~2.5 s, caps respected (≥3 min apart)
- ☐ From the Cigram app: movie, series episode (next-episode countdown, skip intro), embed-page title (stays in built-in player), player uninstalled (built-in player), subtitle/poster/resume passed
- ☐ Main app with player installed: no crash on early `finish()` of its PlayerActivity

## D. Play Console
- ☐ App created, name, default language, free/paid, category (Video Players & Editors)
- ☐ **Privacy policy URL** (public, https)
- ☐ **Data safety** per PRIVACY-DATA-SAFETY.md (not "no data collected")
- ☐ **Ads: Yes** (Google AdMob). **Advertising ID: the app USES it** (purpose: advertising). The `AD_ID` permission is declared in the manifest.
- ☐ AdMob: app created, real IDs pasted in `res/values/admob.xml`, GDPR message created, test with test IDs first (ADMOB-SETUP.md)
- ☐ Target audience and content: not for children; complete the content-rating questionnaire
- ☐ App access: no login required (state it)
- ☐ Declarations: no health, finance, news, government features; no foreground-service types (none used)
- ☐ Store listing: description without unverifiable claims (no "all movies", no unlicensed titles), screenshots (phone + 7"/10" tablet), icon 512, feature graphic
- ☐ Internal → closed testing track first; if the developer account is a personal account created after Nov 2023, complete the closed-test requirement (12 testers, 14 days) — verify in Console
- ☐ Review the pre-launch report and fix crashes/ANRs

## E. Content and rights
- ☐ Written statement that you hold the rights to everything the Cigram catalogue streams (main app) — Play can ask
- ☐ Moderation process for ad campaigns and landing pages; abuse-report contact
- ☐ Do not promote sideloading or an APK download of this player from inside any Play-distributed app (the main app does not)

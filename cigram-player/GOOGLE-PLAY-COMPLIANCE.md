# Google Play compliance report — Cigram Player

**UPDATE (AdMob): the player now serves Google AdMob ads (app-open + interstitial on watch-now). Sections below that say "first-party only" / "no AdMob" / "does not use the advertising ID" describe the earlier build; the AdMob facts in CHANGELOG.md, ADMOB-SETUP.md and PRIVACY-DATA-SAFETY.md override them. New risks: R12 below.**

Scope: this project (`com.cigram.player`) and its integration with the main Cigram app. **Code compliance ≠ successful build ≠ tested ≠ Google Play approval.** None of the last three could be completed in the authoring environment (see §6). Approval is never guaranteed.

## 1. Audit of the received project (before changes)

Confirmed in the source:

| # | Finding | Where | Risk |
|---|---|---|---|
| C1 | `targetSdk 35` | app/build.gradle.kts | Play requires **API 36** for new apps/updates from 2026-08-31 → upload rejected |
| C2 | Home screen was only an info text; no way to use the app alone | HomeActivity | "minimum functionality" / not independently useful |
| C3 | Whole ad picture (and pause card) opened the advertiser on touch | AdView, PauseAd | Ads policy: ads must not be designed to be clicked accidentally |
| C4 | Random installation UUID + impression/click events sent from the first ad, no notice, no consent, no opt-out, no privacy screen | AdsClient | Data safety / User Data / privacy-law exposure; undisclosed collection |
| C5 | Exported player accepted any http(s) URL and arbitrary request headers; no VIEW/SEND support (so not useful to other apps) | PlayerIntent, manifest | untrusted-input risk; poor interoperability |
| C6 | Back handled only with `onBackPressed()` | PlayActivity | breaks with predictive back at targetSdk 36 |
| C7 | `usesCleartextTraffic="true"` for the whole app incl. the ads server | manifest | needless exposure of ad traffic |
| C8 | Generic error message, no retry on network loss, no 401/403/404/DRM handling | PlayActivity | poor UX, review "broken functionality" |
| C9 | Main app dropped the episode list and skip ranges when handing over | CigramPlayerLauncher | functional regression for series |
| C10 | Fixed `sensorLandscape` | manifest | no portrait video, ignored on large screens at API 36 |

Checked and fine: three permissions (INTERNET, ACCESS_NETWORK_STATE, AD_ID for AdMob); no own services, receivers or providers (the AdMob/UMP libraries add theirs when merged) and no native libraries in the project; no install-packages permission anywhere in the player; R8/shrinking on; no hard-coded signing secrets; main app never downloads/installs the player (`<queries><package>` only, `resolveActivity` check, fallback on any exception); embed pages and unknown types stay in the built-in player.

## 2. Requirement → what was done

| Requirement | Implementation |
|---|---|
| API 36 | compileSdk/targetSdk 36 (AGP 8.13). Behaviour changes handled: predictive back (`OnBackInvokedCallback`), edge-to-edge (already fullscreen, bars hidden), resizable activity, orientation not forced on large screens |
| Minimal permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` (AdMob) |
| Standalone | real Home (link, file picker, continue watching, privacy), intent filters for VIEW/SEND/deep link, graceful handling of bad input |
| Playback | MP4/MKV/WebM/TS, HLS, DASH, RTSP via ExoPlayer 2.19.1; SRT/VTT/ASS/TTML subtitles (URL or device); resume; rotation; PiP; retry; messages. **Only formats ExoPlayer 2.19.1 supports are claimed; none has been played on a device by the author** |
| Ads | **Current build: Google AdMob** — app-open ad at launch (≤ once/4 h) and an interstitial after a watch-now press; EU/UK consent via Google UMP; never blocks playback (3.5 s limit); no ads in the middle of the film, on pause, in PiP, on local files or with `ads=false`. The earlier in-house system (labelled «إعلان», skip after 2 s, click only on the button, caps) is still in the code behind `OWN_ADS_ENABLED=false` |
| Data | consent for installation id and events, reset, delete, disclosure screen, docs |
| Copyright | the player has no catalogue, no scraping, no DRM circumvention, no hard-coded content sources. DRM-protected streams are reported as unsupported |
| Main-app integration | contract preserved and extended (`url`/headers/title/subtitle/poster/id/type/series_title/season/episode/resume_ms/ads + `series_episodes_json`, `skip_segments`) |
| Distribution | single AAB, no "direct" variant needed; app does not depend on another APK |

## 3. Advertising policy review (Google Play Ads policy, Deceptive Behavior, User Data)

- (Describes the in-house system, now off by default.) Ads are first-party, shown inside the app that serves them, labelled, closable (skip button from 2 s; Back works after 2 s), not shown while the app is in the background or in PiP, not triggered by system UI, and do not cover playback controls (full-screen ads pause the video and hide the controller; the pause card sits in the top corner).
- Interruption control: pre-roll + at most 4 mid-rolls only for ≥ 60 min titles (2 for 8–60 min, none shorter), ≥ 3 min between ads, ≤ 12 per hour.
- **Open risk R1:** *what the ads server serves is outside this code.* Creatives/landing pages that are adult, deceptive, malware, fake system UI or fake "close" buttons violate Play policy and make the **app** non-compliant. You need review/moderation of campaigns and landing pages and an abuse-report process (the in-app report only forwards to your worker).

## 4. Testing actually performed (and not)

Performed here:
- Java compile of **all** player sources against the real Android 16 framework (`android-all-16-robolectric-13921718`, `javac --release 8`) with **hand-written stubs for the ExoPlayer API**. Result: no errors. Limitation: stubs follow the ExoPlayer 2.19.1 API from memory of the author, so ExoPlayer call signatures are **not** verified by this test.
- JVM logic tests (10 224 checks, all passed): ad-break placement (≥5 min from start, ≥3 min from end, ≥4 min apart, deterministic per title), link/mime/header parsing, header allow-list and CR/LF stripping, scheme allow-list (file://, javascript:, intent: rejected), URL extraction from shared text, episode-list parsing/sorting/next, skip-range parsing.
- Intent hand-off parsing on a plain JVM with the real `android.content.Intent` (Cigram action, file:// rejected, empty rejected, SEND text).
- Manifest/Gradle/resource static checks (`static-checks.txt`): permissions, `exported` on every component with an intent-filter, resources referenced, no hard-coded signing secrets, no native libraries, main app has no install-packages permission.
- Javac syntax check of the changed main-app files.
- Web check of the Play target-API rule (developer.android.com/google/play/requirements/target-sdk: API 36 from 2026-08-31).

**Not performed (and therefore not claimed):** Gradle sync/build, AGP/R8/resource shrinking, lint, dependency resolution (Google Maven is unreachable from the authoring environment), AAB/APK generation and signing, any emulator/device run, real playback of MP4/HLS/subtitles, PiP, rotation, real ad-server calls, real main-app → player hand-off between two installed apps, Play Console pre-launch report. **No AAB is delivered** because none could be built.

## 5. Remaining risks that can still cause rejection or removal

| ID | Risk | What to do |
|---|---|---|
| R1 | Ad creatives/landing pages from your server violating Play policy | moderate campaigns; keep report handling; consider an allow-list of landing domains |
| R2 | **Copyright / IP policy.** A video player is fine, but Play reviews the whole developer account/ecosystem. The main Cigram app contains an embed resolver and request-blocking code for third-party embed hosts (`CigramEmbedResolver`, `CigramVidResolver`) and a catalogue; if any of it serves content you do not have rights to, the main app (and a linked player) can be removed | document your licences; be ready to prove rights; remove unlicensed sources. This project does **not** make a catalogue legal |
| R3 | Privacy policy URL and Data safety form missing/inaccurate | supply them (§ PRIVACY-DATA-SAFETY.md); retention/IP-logging answers are yours |
| R4 | Broad VIEW filter for http(s) `.mp4/.m3u8/…` links makes the app a candidate for many links | it only appears in the "open with" chooser; remove the pathPattern filter in `AndroidManifest.xml` if the reviewer objects |
| R5 | `workers.dev` is a shared domain; reviewers may see it as an unprofessional backend | move the ads API to a domain you own (change `AdsClient.API_BASE` and `network_security_config.xml`) |
| R6 | Cleartext HTTP is allowed for user-supplied media links | acceptable for a general player; document it; tighten if your use case is HTTPS-only |
| R7 | ExoPlayer 2.19.1 is no longer maintained | migrate to AndroidX Media3 (mechanical package rename + `PlayerView`); I did not do it blind because it cannot be compiled/verified here |
| R8 | Ads in a general video player for an adult audience: content-rating questionnaire must reflect that ads can contain what your campaigns contain | answer the rating questionnaire honestly; target audience not children |
| R9 | Real-device behaviour of hand-off/PiP/ads/consent is untested | run the test list in PLAY-STORE-SUBMISSION-CHECKLIST.md before upload; use internal testing track first |
| R10 | New-developer-account testing requirement (personal accounts created after Nov 2023 must run a closed test with ≥12 testers for 14 days before production) | check your account type in Play Console; plan the closed test |
| R11 | Brand/trademark: app name «مشغل سيكرام» / Cigram logo — fine if you own them; the generated launcher icon is a placeholder | replace with your final artwork |
| R12 | **AdMob in the player.** (a) The IDs shipped are Google test IDs → no earnings until you add yours. (b) AdMob may limit/refuse serving for an app that is distributed only outside a supported store (not verified). (c) Ads policy: interstitials only after a user action and app-open only at launch — implemented; any extra placement can violate it. (d) Data safety, Advertising ID and privacy policy must now describe Google AdMob. (e) Tapping your own real ads gets the account banned | follow ADMOB-SETUP.md; test with test IDs; fill the Data safety form from Google's AdMob page |

## 6. What you must provide (manual)

0. **AdMob**: create the app "Cigram Player", paste App ID + 2 ad unit IDs into `res/values/admob.xml`, create the GDPR (UMP) message.
1. Public https **privacy policy URL** → `AppConfig.PRIVACY_POLICY_URL` and Play Console.
2. Contact e-mail (`AppConfig.SUPPORT_EMAIL`, Play listing).
3. Server facts: retention of IP/ids/events, deletion process, who can see statistics, Cloudflare/storage region.
4. Proof/licence statement for the content your catalogue (main app) serves.
5. Upload keystore (create with `keytool`, back it up) and Play App Signing enrolment.
6. Store listing: short/full description (do not promise unverified formats or "free movies"), screenshots, feature graphic, final icon, content-rating questionnaire, target audience, ads declaration (Yes), advertising-ID declaration (does not use), Data safety form.
7. Decision: ads for users who decline statistics (implemented: shown without id); change if you prefer "no ads without consent".
8. Optional: ad-free users — the main app can pass `ads=false`.

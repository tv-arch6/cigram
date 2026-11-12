# Cigram 2026 — Engineering Report

Deliverables: **H1.zip** (main app, folder `cigram/`) and **A1.zip** (administration app, folder `admin/`).
Both archives are complete Gradle projects built from the supplied `HASAN.ZIP` and `admin.zip`
(the originals are untouched; the first commit of this branch imports them byte-for-byte so every change is a reviewable diff).

Versions: main **18.2.0 (89)** · admin **2.0 (7)**.

---

## 1. Problem No. 1 — "black / almost empty screen for ~2 s at launch"

### Root cause (confirmed in code and on an emulator)
It was a combination of four things, all on the cold-start path:

| # | Where | What it did |
|---|---|---|
| 1 | `res/values/cigram_launch_theme.xml` → `CigramLaunchTheme` | `android:windowSplashScreenAnimatedIcon = @android:color/transparent`. On Android 12+ the **system splash had no icon**: an empty `#0A0608` screen from the tap until MainActivity's first frame. |
| 2 | `res/drawable/cigram_launch_bg.xml` | The pre-Android-12 launch window background was a maroon radial glow **without any logo** → the same empty dark screen on Android 7–11. |
| 3 | `CigramSplash.begin()` (called from `onActivityStarted`) + `HOLD_MS = MIN_MS = MAX_MS = 2000L` | The branded overlay was added only **after** the whole `MainActivity.onCreate` (3 000+ lines of UI setup), then held for a **fixed 2 s** counted from `onActivityPreCreated`, whether Home was ready or not. |
| 4 | `MainActivity.initializeLogic()` hard-coded window colour `#00121A`; `CigramLayoutConfig` applied an Admin-forced theme with `setDefaultNightMode` from a network callback | Colour flash between launch screen and Home, and (when the Admin changes `theme.mode`) a recreation of every open page in the middle of start-up — a second, empty-looking start. |

Emulator evidence (Android 12, `StartingSurfaceDrawer` log):
- original build: `window attributes color: ff0a0608 icon android.graphics.drawable.ColorDrawable` (transparent icon);
- new build: `window attributes color: ff0e0e10 icon android.graphics.drawable.BitmapDrawable` (the Cigram logo).

Not the cause: catalogue parsing (already on `HomePrep` background threads), AdMob init (background thread), the content-provider start-up work (mostly asynchronous). One extra contributor was found and fixed: a cached in-house **splash ad** opened a full-screen black dialog and only then downloaded its picture (`AdsOverlay.showSplash`).

### Fix
| File | Method / resource | Change |
|---|---|---|
| `res/drawable/cigram_launch_bg.xml` (+ `_dark`, `_light`) | layer-list | page colour `@color/cg_launch_bg` + the rounded Cigram logo (112 dp) — visible from the very first system frame |
| `res/values/cigram_launch_theme.xml`, `res/values-v31/cigram_launch_theme.xml` | `CigramLaunchTheme`, `.Dark`, `.Light` | Android 12+ splash icon = `cigram_splash_icon` (same logo, inside the 192 dp safe circle), background follows Light/Dark (`values` / `values-night`), status / navigation bars in the page colour |
| `CigramSplash.java` | rewritten | no fixed wait: the hand-off overlay (same picture as the launch screen) leaves as soon as Home reports ready (`markHomeReady`) and at the latest 650 ms after its first frame; `syncSystemSplashTheme` keeps the Android 13+ splash in step with the user's in-app Light/Dark choice |
| `CigramStage1InitProvider.java` | `onActivityCreated`, `applyLaunchSplashTheme` | Home window gets the page colour of the same mode; splash theme delegated to `CigramSplash` |
| `CigramLayoutConfig.java` | refresh callback | Admin-forced theme is saved and applied at the next cold start, never mid-start-up |
| `AdsOverlay.java` / `AdsMedia.java` | `showSplash`, `isReady`, `prefetch` | a splash ad opens only once its picture is decoded (max 1.2 s wait, otherwise skipped this launch) |
| `CigramPalette`, `CigramTheme`, colours | global | charcoal palette (see §15) so launch, Home and every page share one page colour |

Offline start: unchanged path (Home shows its offline / retry state); the overlay never waits for the network.

---

## 2. New identity (logo `837031.png`)
The supplied file is **1024 × 1024 RGB** (not 1536 × 1536) with the transparency **checkerboard baked into the pixels**.
`branding/logo_extract.py` flood-fills the checkerboard from the borders, keeps the rounded square, de-contaminates its edge,
and extracts the white mark and a monochrome silhouette with real alpha. `branding/make_assets.py` generates every asset
(previews were inspected at 48 px, 72 px, 144 px and notification sizes; `branding/icon_variants_preview.png`).

| Asset | Files |
|---|---|
| Legacy + round launcher (all densities) | `mipmap-*/ic_launcher*.png`, `ic_launcher*_round.png` |
| Adaptive icon (bg + fg + Android 13 themed monochrome) | `mipmap-anydpi-v26/*.xml`, `drawable-nodpi/ic_launcher*_bg/_fg.png`, `ic_launcher_mono.png` |
| Notification small icon (white silhouette, 24 dp) | `drawable-{m,h,xh,xxh,xxxh}dpi/ic_stat_cigram.png` |
| Splash | `drawable-nodpi/cigram_splash_icon.png`, `cigram_logo.png` |
| In-app branding | `cigram_logo.png`, `cigram_mark.png`, `hoo.png` (home header), `cigram_watermark.png` |
| TV banner | `drawable-{xh,xxh,xxxh}dpi/tv_banner.png` |
| Play Store icon | `app/src/main/ic_launcher-playstore.png` (512 px) |
| Cigram Plus icon variants | Gold, Neon, Minimal (white), Cinema (red), Glass — same mark, own backgrounds; picker previews `ic_launcher_*_preview.png` |
| Admin | gold mark on charcoal (launcher, round, adaptive, notification) |

---

## 3. Cigram TV edition
| Item | Change |
|---|---|
| Details page (`CigramTvDetailsActivity`) | ONE main action **«شاهد الآن»**; the line under it says what plays (episode N, resume / minutes left). «من البداية» and «مشاهدة جماعية» buttons removed; start-over = **long press** on «شاهد الآن». Double activation guarded (1.5 s). The real link is validated before any player opens. Episode cards play exactly the pressed episode. |
| RTL | The app does not declare `android:supportsRtl`, so `setLayoutDirection(RTL)` is ignored by Android (confirmed on the emulator). TV rows now really flow right → left (`rtlRow()` inserts children at the front and mirrors margins). |
| Account / Settings | New TV panels (`CigramTvPanels`): 3-column focusable tiles, visible focus ring, one action per press; Account = watchlist, Cigram Plus, تبديل, أريد إعلاناً, support, account management / sign-in, about, terms; Settings = preferred quality, seek step, speed (change in place), theme, interface, notifications, subtitles, advanced, about. |
| Sidebar | Wrapped in a `ScrollView` (the 10 entries did not fit every TV height: the focused entry left the screen), charcoal colours, first focus on the top-right tile. |
| Remote seeking | `CigramTvKeys`: a held arrow / FF / RW key seeks at most every 280 ms (it used to seek on every key repeat, ~20 steps/s). Primary seek stays −10 / +10 s; 5 / 15 / 30 s selectable. |

## 4. Playback
- `CigramPlaybackError` (new) classifies every failure: **invalid URL, unsupported format, missing stream, expired source (401/403), WebView failure, timeout, offline, server failure (5xx), unreachable host** — Arabic messages, technical code in brackets. "No internet" is only said for a network error while the device really has no network.
- Used by the phone player (`PlayerMonitor.playerErrorMessage`), the stream resolver (`CigramEmbedResolver.friendly`), the TV readiness panel and live channels (`CigramChannelPlayer`: an undecodable stream is reported at once instead of 4 futile retries).
- `openPlayerForTitleItem`: a malformed link from the Admin data (empty, `null`, no scheme, script schemes, network scheme without host) is explained instead of opening a player. All schemes the stream layer plays (http/s, rtsp/s, rtmp/s, udp, srt, `data:` playlists, content, file) pass.
- Quality: menus name only **720p, 1080p, 2K, 4K**, from tracks that exist in the HLS/DASH manifest or copies that answered on the server (`CigramQuality.offered`); nothing below 720p is named, no extra badges.
- Existing and kept: AdMob first, in-house break ad on no-fill (`CigramEpisodeAds`, `CigramRewardUnlock.noFill`), resume / skip intro / auto-next settings, subtitle & audio menus in the gear sheet (`CigramPlayerSheet`).

## 5. Home
- Red accent bars beside every section title removed (`CigramDesign.accentBar` is now an invisible view so existing lookups keep working; `decorateTitle`, Channels headers, Settings headings).
- «المزيد» keeps the word, the `‹` chevron is gone (also «عرض المزيد»).
- Posters (Home rows, dynamic sections, search, section pages) carry a small transparent Cigram watermark in the poster's foreground layer, top-left (the corner with no badge / heart / rank).
- Channel artwork: a faint Cigram mark is shown only until the channel's own picture loads (`CigramChannelCard`, `CigramChannels`); channel pictures never get a watermark.

## 6. Account page (CIGRAM ID)
Same views, ids and listeners (re-parented, never deleted). Groups: **حسابي** (الحساب الشخصي, قائمة المشاهدة, الإعدادات) ·
**Cigram Plus** · **التطبيق** (تبديل, المظهر, الخطوط, الويدجت) · **الدعم والتواصل** (أريد إعلاناً, الدعم والتواصل, المساعدة, التحديثات) ·
**عن Cigram** (حول التطبيق, الشروط والأحكام) · **إجراءات الحساب**.
- **تبديل** is wired to the existing interface switch (`CigramTvMode`: phone UI / Cigram TV / automatic) and restarts on Home.
- **أريد إعلاناً** opens the existing advertising flow (`AdvertiseActivity` → `AdsChatActivity`, order card that the admin inbox receives).
- Monochrome line icons (white on dark, ink on light), no coloured tiles; thin borders between rows; four-stage gradient page; the user banner is shown without any overlay.
- The app-icon picker moved to **Settings → أيقونة التطبيق**.
- Icon collection: `AdsIcons` gained switch, bookmark, crown, Cigram mark, TV, cast, quality, subtitles, audio, speed, apps, replay/forward 10, skip, palette, document — same 24-unit grid and stroke as the existing set.

## 7. About and Terms
- `CigramAboutActivity`: what Cigram is, why, how it is built (Android/Java app + Cigram cloud services + admin app — all visible in the project), purpose, services (only features that exist), vision, and the founder **حسن سعيد** with the supplied portrait: square, 20 % corners, tilted +4°, a translucent black square tilted −6° behind it, natural colours. No dates, company data, awards or team members were invented.
- `CigramTermsActivity`: Arabic terms describing how the app works. **Not reviewed by a lawyer; no compliance claim.**
- Registration (`AccountActivity` register button → `CigramTerms.checkBeforeRegister`): an **unchecked** box «أوافق على الشروط والأحكام» (link opens the terms); no request without it. Version + timestamp stored on the device (per e-mail) and sent as `terms_version` / `terms_accepted_at` with `/register` (the auth worker must persist them for a server-side record).

## 8. Search
- The search engine parsed the whole catalogue index **on the main thread** when the page opened and after every refresh (`SearchXmlActivity … CigramSearch.reload`). Large indexes are now parsed on a dedicated background thread; previous results stay, a «جارٍ تجهيز البحث…» line shows meanwhile, the search re-runs when ready.
- The page keeps its state on keyboard / orientation / navigation config changes (`configChanges`, `adjustPan`).

## 9. Advertising
- Popup ad: 20 dp rounded card, thin white outline, «إعلان» label, **yellow action button**, round ✕ at the top; closes with ✕, Back or outside tap; once a day; impression after 1 s; image or muted looping video.
- Existing architecture kept (no duplicate system): AdMob primary for app-open / interstitial / rewarded / player breaks with UMP consent; in-house campaigns (cigram-ads-api) for home slots, splash, popup and as AdMob no-fill fallback; Cigram Plus = no ads.
- Admin: every ad has a recognisable name (creative «اسم الإعلان» required; lists show name → title → type fallback); delete confirmations name the campaign / creative / advertiser; campaigns without a name are flagged in the list.

## 10. Messaging (customer ↔ admin)
- Real backend (cigram-ads-api chat: WebSocket + polling, persistent threads, R2 media). Not a local chat.
- Videos up to **1 GB**: streamed part by part from the picked file (no cache copy, memory = one part), progress per part, **resume** after a failure (status endpoint → only missing parts), cancel. Images/documents keep 25 MB. A 413 from the server is explained.
- RTL composer: [send / mic][video][photo][«اكتب رسالتك هنا»]; outgoing bubbles right, admin bubbles left.
- Admin: «طلب إعلان» badge on ad-related threads; **notifications** for new ad requests / customer messages while the app is closed (`AdInboxNotifier`, 15-min job), permission asked once from the inbox.
- **Server dependency:** the upload size limit is decided by the ads worker (`/chat/upload/init`). Its source is not in either archive, so the 1 GB limit could not be set or verified on the server side.

## 11. Permissions
`CigramPermissions` (main): granted → nothing shown; asked only from the feature; explanation first; after a permanent refusal an «فتح الإعدادات» offer instead of a dead button. Used for the chat and voice-search microphone. Photos/videos use the **system photo picker** (no storage permission). Notification permission: already contextual (stage 6), and with targetSdk ≥ 33 Android no longer auto-prompts when the first channel is created at start-up (it did with targetSdk 24). Admin: notification permission asked once from the inbox.

## 12. Build targets and store readiness
| | main `play` | main `direct` | admin |
|---|---|---|---|
| compileSdk | 36 | 36 | 36 (was 34) |
| targetSdk | **36** (was 24) | **36** (was 24) | 34 (kept, see below) |
| minSdk | 24 | 24 | 21 |
| differences | no self-update, no Plus sales | self-update (`REQUEST_INSTALL_PACKAGES`), Plus sales | — |

targetSdk 36 behaviour changes handled: edge-to-edge (`CigramSystemBars`: an explicit insets listener on every regular page pads the content by the ROOT system-bar / cutout / keyboard insets — `fitsSystemWindows` alone was ignored and AppCompat consumed the insets before the content, both found on the Android 15 emulator, so the listener sits on the decor's content-holding child; players / TV keep their immersive mode), predictive back (`enableOnBackInvokedCallback="false"` keeps `onBackPressed` / remote BACK), contextual notification permission, explicit internal broadcasts, `RECEIVE_BOOT_COMPLETED` (the persisted new-episode job threw `IllegalArgumentException` and never ran — seen in the emulator log). PendingIntents were already immutable; no dynamic receivers, exact alarms or public-storage writes.
Admin stays at 34: it is installed privately, and API 35+ forces edge-to-edge on its hand-built screens, which have no insets handling yet.
Signing: both `app/build.gradle.kts` now read `keystore.properties` or the `CIGRAM_*` variables the CI workflow already exported (before, release builds were unsigned).

**iPad:** an Android APK cannot run on iPadOS. Real iPad support needs either a separate iOS app or a responsive web app / PWA over the same Cigram APIs; neither exists in the supplied sources and none is claimed here.

Other stores (Galaxy Store, AppGallery, Amazon …): the `direct` flavor is technically compatible (no Play-only dependency for playback; AdMob/Firebase need Google Play services and degrade gracefully without them). Each store's own listing, content-rating and policy review still applies; acceptance is not guaranteed.

## 13. Administration app
- Design: one central palette change (`CgCfg`): charcoal ladder, Cigram red primary, gold second accent; every screen follows. All existing functions and buttons untouched.
- Ads center, inbox, notifications: §9–10. Content structure (movies, series, seasons, episodes, channels, posters, links, subtitles) not modified.

## 14. Performance and stability (beyond the above)
- No main-thread catalogue/index parsing on Home or Search; no fixed waits at start.
- Theme changes from the Admin never recreate pages mid-session.
- Remote seeking throttled; TV focus never leaves the screen.
- Upload memory bounded to one part.

## 15. Visual system
Legacy teal/navy surfaces (`#00121A`, `#0B2733`, `#1F4553` …, 27 values, ~330 places) were mapped to a charcoal ladder of the same lightness (`branding`-independent script, alpha preserved), and the blue-grey text greys to neutral greys. Light mode keeps working (the light-mode map was updated with the same keys). Brand red `#E21B14` unchanged; gold `#F2C14E` for Plus / popup action.

---

## Files added
`CigramBrand, CigramPageKit, CigramAboutActivity, CigramTermsActivity, CigramTerms, CigramPlaybackError, CigramPermissions, CigramTvPanels, CigramSystemBars` (main) · `AdInboxNotifier` (admin) · launch / splash / icon resources · `branding/` (masters, scripts, preview).

## Remaining limitations (honest list)
- Backend sources are not in the archives: 1 GB server limit, persisting `terms_version` / `terms_accepted_at`, and any `name` field of creatives depend on the workers accepting them.
- Live data could not be tested here: this environment's network policy refuses the `*.workers.dev` hosts (HTTP 403 on CONNECT).
- No physical TV / phone was available; emulators ran without hardware acceleration (timings not representative).
- Casting (Google Cast / DLNA) was reviewed, not changed; it needs real devices to verify.
- A full rewrite of the phone player UI and a native TV player skin were not done; the existing gear sheet and TV key layer are used.
- The legal text needs a lawyer's review.

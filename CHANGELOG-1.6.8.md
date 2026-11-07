# 1.6.8

- **Version name** 1.6.8 (was shown as 18.0.0). **Version code unchanged: 83.**
- **AdMob is back** (CigramAdConfig.ADMOB_ENABLED = true): app-open ad, interstitial / rewarded, support ad, episode break and mid-roll ads in the built-in player, UMP consent form. Production unit IDs untouched. Cigram Plus accounts still see no ads. In-house ads of the ads worker (home slots, splash / pop-up) keep working as before; the in-house player breaks stay off while AdMob is on.
- **Companion Cigram Player**: AdMob stays on there (app-open + "watch now" interstitial). A title that the main app hands over to the companion player gets its ad from the companion only (no double ad).
- **Watch progress / continue watching**
  - History and "تابع المشاهدة" now keep the link a title was OPENED with (the vidbolt embed page, not the short-lived resolved stream). Before, opening a vidbolt film / episode from "continue watching" re-used an expired stream URL.
  - A progress record never loses its known duration when a later save reports an unknown one (stream still loading), so the progress bars on the cards no longer disappear.

## Batch 1 (performance / first run)
- **Audio boost** in the player menu («تعزيز الصوت»): 0 / +3 / +6 / +9 / +12 dB (LoudnessEnhancer), remembered, re-attached when the audio session changes.
- **Buffer by device class** (CigramBufferPolicy): weak / low-RAM phones use 10-30 s buffers, mid 15-50 s, normal phones unchanged (20-75 s).
- **Onboarding**: 3 skippable welcome screens on first launch, then the welcome sheet [استكشف التطبيق] / [تجربة Cigram Plus]. The Plus pop-up is held back that day.
- **Activation success card**: gold crown + confetti, package, duration, "from - to", active perks, big "ابدأ المشاهدة الآن" button (goes to Home).
- **Paste button** on the code page.
- Not done (needs servers / accounts / large rewrites): pre-buffering, auto fail-over between servers, offline downloads, Room cache, device limit, ZainCash / FIB / Qi Card APIs.

## Verification ring + launcher icons
- **Verification badge** (`res/drawable-nodpi/cigram_verified.png`, the blue seal): bottom corner of the round photo (bottom bar, account page, CigramTogether chat + room list) and next to the name on the account page, only while Plus is active. For OTHER members the server must send `plus` / `vip` / `verified` (or `is_vip` / `is_verified`).
- **Fix**: the saved photo no longer disappears from the bottom bar (the nav code now recognises the photo through `CigramAvatarFrame.isPhoto`).
- **App icon** (account page, «أيقونة التطبيق»): 6 icons made from the real Cigram logo (Classic free; Gold, Neon, Minimal, Cinema, Glass need Plus). Implemented with `activity-alias` + `setComponentEnabledSetting(DONT_KILL_APP)`. LAUNCHER intent moved from `.MainActivity` to the aliases. Back to Classic automatically when Plus ends.
- Server: `plus-worker` has `POST /plus/prefs {app_icon}` and `app_icon` in `GET /plus/status` (table `plus_prefs` is created automatically; also in schema.sql). Redeploy the worker.
- **Fix**: account-page photo showed square corners and a doubled badge for Plus accounts (the bottom-bar photo path was also used by the header). The header now keeps its round clipped photo with a single badge; only the bottom-bar avatar draws its own badge, and the square placeholder background is removed there.
- **Change**: the verification badge is no longer drawn on profile photos (account page, chat). It stays only on the bottom-bar account icon and beside the name on the account page.
- **Android TV**: the app had a TV mode (D-pad, side rail) but was never listed by the TV launcher. Added the `LEANBACK_LAUNCHER` category to every launcher alias, `android:banner` (`drawable-xhdpi/tv_banner.png`, 320x180), and `uses-feature` with `required="false"` for touchscreen, leanback, microphone, faketouch and camera so Play/TV devices without them are not filtered out.
- **TV (RTL)**: the side rail, its separator line and the optional side panel are now on the RIGHT edge and the page fills the rest to the left (`CigramTvRail`); the rail's own contents (logo, name, bell, red mark, icon, label) are mirrored (RTL). Every horizontal row on a TV page is aligned to the right edge and long rows open at their right end (`CigramTvUi.alignRowsRight`), so short rows no longer leave an empty right side. Phone UI untouched (TV only).
- Content loading on TV is the same pipeline as the phone (catalogue download starts when the process starts, cached for 60 min, refreshed after 5 min); the very first launch after install has to download it, so skeleton cards show for a moment.

## 1.6.9 (versionCode 84)
- Version bump: versionCode 83 -> **84**, versionName 1.6.8 -> 1.6.9 (publish with versionCode 84 in the admin app).
- **TV**: the card under the remote now grows 7% and floats above its neighbours (animated, 160 ms) in horizontal rows (posters, channels, episodes); buttons, bars and the rail are not scaled (`CigramTvFocus.lift`). Together with the RTL rail and right-aligned rows from the previous step.

## 18.1.0 (versionCode 85)
- Version: versionName **18.1.0**, versionCode **85**.
- **Plus pop-up redesigned** (`CigramPlusPromo`): white card with 20dp corners, black text, every number in red, a close icon on the top-left corner, and a yellow "اشترك بـ Plus" button with white text (Play build: "ضع كود التفعيل"). Shows the feature list and the intro/standard price. Free users only, never on TV.
- **New schedule**: only on Sunday, Thursday and Friday (device date), once on each of those days; at most once per app launch, a couple of seconds after Home opens. (Before: 3 per rolling week.)

## 18.1.1 (versionCode 87)
- Version bump: versionName 18.1.0 -> **18.1.1**, versionCode 86 -> **87** (publish with versionCode 87 in the admin app).

## 18.1.2 (versionCode 88)
- Version: versionName **18.1.1 -> 18.1.2**, versionCode **87 -> 88** (single source: `app/build.gradle.kts`, both flavors).
- **Builds with plain javac**: 13 activities imported three wildcard packages that are not on the classpath any more (`androidx.legacy.coreui`, `androidx.slidingpanelayout`, `androidx.asynclayoutinflater`); CodeAssist tolerated it, `javac` / Gradle does not. The unused imports are removed, `gradle :app:assemble{Play,Direct}{Debug,Release}` compiles.
- **Font**: Beiruti (variable font, `assets/fonts/beiruti.ttf`) is the one UI font. `CigramTypefaces` owns it (`app()`, `bold()`, real wght 700 instance on Android 8+, synthesised bold below); `CigramFontGuard` puts it on any text view still drawn in a system face (first layout passes after a screen is resumed only, never while scrolling). `sima.ttf` is removed. The subtitle font picker keeps all its fonts and gets «بيروتي» as an extra choice; unknown saved keys fall back to the default font instead of silently using the system font.
- **Logos**: `drawable/imdb` (IMDb logo) and `drawable/now` (the «جديد» badge) are the supplied pictures. Both badges are decoded at display size (`CigramImdbIcon.decodeSmall`) instead of full size scaled up by the screen density (the badge was 1500x500 -> several MB per decode).
- **Removed assets** (no reference in code, XML, manifest or dynamic `getIdentifier` lookups): drawables `a0b1c18a...`, `ic_episode_list`, `icon_circle_notifications_round`, `icon_play_arrow_round`, `icon_play_circle`, `mic_1`, `mic_3`, `mic_4`, `player_settingspng`, `reaction_like`, `reaction_dislike`, `uie`, `uii`, `vvg`, `yua`, and the 3.4 MB `src/main/java/cig.svg`.
- **Memory leaks fixed** (a `WeakHashMap<Activity, X>` never releases an entry whose value references the Activity): `CigramTheme.WATCHERS` (every themed page in light mode), `CigramChannels.STATES`, `CigramSketchwareNotifications.PAGE_STATES`, `EPISODE_REFRESH` (every series page), `DETAILS_FLIGHTS`, and the static views / activity of `CigramSeekPreview` (kept the last player page alive). They are cleared from `onActivityDestroyed` / `PlayerActivity.onDestroy`.
- **Search**: the per-keystroke loop (UI thread) rebuilt and folded a 20-field text for every catalogue item, twice. There is now one `CigramSmartSearch.normalize` (precompiled patterns, short results cached, Persian digits / yeh / keheh also folded) used by every search path, the folded text of an item is cached for the life of the catalogue snapshot, and each item is matched once per keystroke.
- **Player**: `CigramLoadPolicy` gave ~100 retries to EVERY stream as soon as one embed host had been seen (a dead direct link kept buffering for minutes). The long patience is now limited to embed hosts; other loads stop after ExoPlayer's stock retry count.
- `CigramLinkActivity` cancels its pending handler work in `onDestroy`.
- **Gradle / release builds** (none of these ran before: the export notes say the project was never built outside CodeAssist):
  - `androidx.viewbinding.ViewBinding` stub (needed only by CodeAssist's compiler) is excluded from `JavaCompile`; with it, every release build failed in D8 with "Type androidx.viewbinding.ViewBinding is defined multiple times".
  - `ExpiredTargetSdkVersion` is a lint **warning** instead of a fatal error (it aborted every `assembleRelease` / `bundleRelease`). targetSdk stays **24**: Google Play rejects uploads that target below its current minimum, so the `play` flavor must get a higher targetSdk (and an on-device QA pass: edge-to-edge, notifications, foreground-service types) before it can ship to Play.
- **Found on a real Android 7.0 (API 24) run**: the Home error sentence lost its second line (horizontal scroller measured one line, laid out narrower; the box now has a fixed width); the Search page's offline retry panel was a clickable full-page overlay on top of the tab bar, so no tab could be used until Back (it now leaves the bar free). Both existed in 18.1.1.

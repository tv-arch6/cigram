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

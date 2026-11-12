# AdMob setup — Cigram Player

## What the player now does with AdMob
| When | Ad | Rule |
|---|---|---|
| The player app is opened from its icon (cold start) | **App Open** ad | at most once every 4 h; only inside the launch window (≤ 4 s after opening); never on rotation/return |
| The user presses **شاهد هسه** in the Cigram app (hand-over) or presses play / a "continue watching" item in the player; also the "next episode" **button** | **Interstitial** | one per press; skipped if shown < 45 s ago; max 3.5 s wait, then the film starts anyway |
| Automatic next episode after the countdown, local files, shared/opened links from other apps, mid-film, pause | **no ad** | by design (only the two placements you asked for) |

Order for a title: consent (EU/UK only) → SDK start (background thread) → interstitial → film. If consent is refused, the device is offline, there is no fill or the ad is slow, the film just starts.

## Make the earnings reach YOUR AdMob account (required — the player ships with Google TEST ids)
The three values in `app/src/main/res/values/admob.xml` are **Google's public test IDs**: ads say "Test Ad" and earn **nothing**.
1. AdMob console → **Apps → Add app** → platform Android → name **Cigram Player**. Use the **same AdMob account** as the main Cigram app (publisher `pub-7010031756978837`, as seen in the main app's code) → earnings go to that account.
   - Package name `com.cigram.player`. If it asks whether the app is listed on a store, answer honestly (see risk A1 below).
2. Copy the **App ID** (`ca-app-pub-…~…`) → `admob_app_id`.
3. In that app create two ad units: **App open** → `admob_app_open_unit`, **Interstitial** → `admob_interstitial_unit` (format `ca-app-pub-…/…`).
4. Rebuild. Test on your phone with the real IDs **without tapping the ads yourself** (invalid-traffic bans are permanent). During development keep the test IDs, or add your phone as a test device in AdMob (Settings → Test devices).
5. Why a *new* AdMob app and not the main app's ad units: AdMob ties every ad unit to one app entry; serving them from a different package can be refused or flagged. A second app in the same account is the normal way.
6. AdMob → **Privacy & messaging** → create the **European regulations (GDPR)** message for this app (UMP). Without it, EU/UK users get no ads.

## Risks you must understand (honest list)
- **A1 — Distribution outside Google Play.** AdMob is built for apps in supported app stores. I could **not** verify AdMob's current rules for apps distributed only as an APK; AdMob can limit fill or disable serving for apps it cannot verify. Check AdMob Help ("Add your app", apps not in a supported store). Publishing the player on Google Play removes this uncertainty.
- **A2 — Policies.** App-open ads for the launch, interstitial after a user action: allowed formats and placements. Do not add ads at other moments, do not encourage clicks, do not click your own ads.
- **A3 — Data safety / Advertising ID.** The app now **uses the advertising ID** and the AdMob SDK collects/sends data to Google. Play Console declarations changed: see PRIVACY-DATA-SAFETY.md.
- **A4 — Own ads.** The in-house ad system (cigram-ads-api) is OFF in the player (`AppConfig.OWN_ADS_ENABLED = false`). Turn it on only if you want both; then the two systems would show ads separately.

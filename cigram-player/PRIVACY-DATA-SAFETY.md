# Privacy & Data Safety — Cigram Player

> **UPDATE — AdMob is now in the player.** With `OWN_ADS_ENABLED=false` (default) the Cigram-ads-server rows of §1 do not happen. Instead the **Google AdMob SDK** (play-services-ads) runs inside the app:
> it reads the **advertising ID** and collects/sends device and ad data to **Google** (typically: advertising ID and other device identifiers, IP address → approximate location, ad interactions/impressions/clicks, SDK diagnostics/crash data) — **third-party collection by Google**.
> Declare this in Data safety (Device or other IDs; App activity → app interactions; Approximate location; App info and performance → diagnostics), purposes *Advertising or marketing*, *Analytics*, *Fraud prevention, security and compliance*, "data is shared with a third party (Google)". **Use Google's own AdMob page "Provide information for Google Play's Data safety section" for the exact current list for SDK 23.4.0 — I could not open it from the authoring environment, so treat the list above as a starting point, not as verified.**
> In the EU/UK the user must consent through Google's UMP form before ads (implemented); other regions: no form.
> The privacy policy must name Google AdMob and link to Google's policy (policies.google.com/technologies/partner-sites), describe the advertising ID, and the UMP choice.

Based on the source code of this project (read, not assumed). Where only you know the answer it is marked **[YOU MUST SUPPLY]**.
This is a working document for filling in Play Console; it is not legal advice and not a published privacy policy.

## 1. What the app does with data

| Data | Where it goes | When | Purpose | Optional? |
|---|---|---|---|---|
| Video link you play + request headers you/the calling app supplied | the video host you chose (not Cigram) | on playback | playing the video | — |
| IP address, device language, app version (`lang`, `app_version` in `GET /ads?slot=…`) | Cigram ads server `cigram-ads-api.wwq-mixtv.workers.dev` (Cloudflare Workers) | when an ad is requested | choose/serve an ad, compatibility | ads are part of the app |
| **Random installation id** (UUID created on first use) | Cigram ads server | **only if the user allowed ad statistics** | count impressions/clicks, enforce campaign limits | **Yes – asked before the first ad; switch in Privacy & ads** |
| Ad events: `imp`, `click` + campaign id + creative id (batches ≤ 20 to `POST /ads/track`) | Cigram ads server | **only if allowed** | advertiser reporting, billing, caps | Yes |
| Ad report (`POST /ads/report`: campaign id, reason; installation id only if allowed) | Cigram ads server | when the user taps «الإبلاغ» | ad quality | user-initiated |
| Ad pictures / videos | the host in the campaign (e.g. your CDN/R2) | when an ad is shown | display | — |
| Advertiser destination | opened in the browser/app after the user taps the button | on tap | — | user-initiated |
| Subtitle file URL / local subtitle file | its host / never uploaded (local file read by the player only) | on playback | subtitles | — |
| Watch list (title, position, duration, time; link only if it needed no headers) | **device only** (SharedPreferences) | while playing | continue watching | deletable in app |
| Ad cache, daily caps, hidden campaigns, privacy choice, installation id | **device only** | — | ads | reset/deleted in app |

**Not collected / not used:** account, name, e-mail, phone, contacts, precise or approximate location, photos, files uploaded, audio, microphone, camera, own analytics or crash-reporting SDKs. **Exception: the Google AdMob SDK is present and collects data itself (see the UPDATE box above) — including the advertising ID.** The video file picked from the device is read locally and never uploaded.

**Third parties:** **Google (AdMob + UMP SDKs) — receives the advertising ID, IP address and ad interaction data directly from the device**; Cloudflare only if the in-house ads are switched on (hosts the ads worker – infrastructure/service provider acting for you); the video hosts and advertisers' sites you or the user navigate to (they receive the IP address and request like any website). No data is sold or shared with advertisers beyond aggregate statistics you compute from `imp`/`click` events (**[YOU MUST CONFIRM]** how the dashboard/exports show them to advertisers).

**Retention:** unknown from the app code. **[YOU MUST SUPPLY]** how long the ads worker / Cloudflare logs / storage keep: IP addresses, installation ids, events. The app cannot delete server-side data; resetting the id in the app creates a new unlinked id.

## 2. Suggested Play Console → Data safety answers (verify each against your server behaviour)

- *Does your app collect or share any of the required user data types?* **Yes** (do **not** answer "No data collected").
- *Data encrypted in transit?* **Yes** — the ads API is HTTPS-only (`network_security_config.xml` forbids cleartext for workers.dev). (Media links the user plays may be http; that is the user's chosen host, not data sent to you.)
- *Can users request deletion?* The in-app reset removes the id from the device and stops tracking. If your server stores events by id, state how a user can ask for deletion **[YOU MUST SUPPLY: a contact/process]**.

| Play data type | Declare? | Collected / Shared | Purpose | Optional |
|---|---|---|---|---|
| Device or other IDs → *installation id* | **Yes** | Collected; not shared with third parties (Cloudflare = service provider) | Advertising or marketing (measurement); Fraud prevention, security (frequency capping) | **Yes (user can decline)** |
| App activity → *App interactions* (ad impressions, ad clicks) | **Yes** | Collected | Advertising or marketing; Analytics | **Yes** |
| Location → *Approximate location* | **[YOU MUST DECIDE]** — only the IP address reaches your server. Declare it if your worker/Cloudflare keeps IP addresses beyond transient processing (log retention, analytics). | Collected if retained | — | — |
| App info and performance / Personal info / Financial / Messages / Photos & videos / Audio / Files / Contacts / Calendar / Health | **No** | — | — | — |

- *Advertising ID declaration (Android 13+):* **YES — the app uses the advertising ID** (Google AdMob SDK; purpose: advertising). `AD_ID` is declared.
- *Ads:* **Yes, the app contains ads** — Google AdMob (app-open and interstitial). The in-house ad system exists in the code but is OFF (`AppConfig.OWN_ADS_ENABLED=false`); if you turn it on, the §1 rows about the Cigram ads server apply again.
- *Target audience:* choose an adult/general audience, **not** "children" (the app is not built for the Families program; no age screening exists). If you target under-13s, the ad system, consent and data practices must be redesigned first.
- The Data safety form must match the app *and* your privacy policy. Re-check it whenever the ads server or the app changes.

## 3. Privacy-policy requirements document (what the policy at your public URL must say)

Required by Play for any app that handles user data; must be a public, non-editable, https URL, reachable without login, and also linked inside the app (set `AppConfig.PRIVACY_POLICY_URL`).

1. Identity: developer/company name and contact e-mail **[YOU MUST SUPPLY]**.
2. The data in §1 (collected, purpose, legal basis/consent for the installation id and ad events), including that ad statistics are **opt-in** and that declining still shows ads without an id.
3. Server-side recipients: Cloudflare as processor; that video hosts, subtitle hosts and advertisers receive the IP address when contacted.
4. Retention periods and deletion/contact procedure **[YOU MUST SUPPLY]**.
5. International transfer statement if Cloudflare/your storage is outside the user's country **[YOU MUST CONFIRM]**.
6. User rights (access/deletion/withdrawal of consent) and how to withdraw: *Privacy & ads → switch off / reset*.
7. Children: the app is not directed to children.
8. Content responsibility: the player plays only links/files the user supplies or other apps hand over; no DRM bypass.
9. Date of last update.
Do not invent or copy a policy from another app; have it reviewed (EU/GDPR, ePrivacy for the identifier, local law).

## 4. In-app disclosure that ships with the app
First ad → dialog «الإعلانات والخصوصية» (both answers continue playback) → *Privacy & ads* screen with the table above in plain Arabic. If you change what is collected, change `PrivacyActivity`, the dialog in `PlayActivity.askConsent()`, this file and the Play form together.

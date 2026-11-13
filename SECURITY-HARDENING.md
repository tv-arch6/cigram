# Cigram 18.6.0 — security hardening (19.6)

Trigger: VirusTotal flagged `app-*.apk` by **1 of 67** engines (AhnLab-V3, `PUP/Android.Malct.*`).
`Malct` is AhnLab's generic cloud/heuristic label, not a named malware family; the other 66 engines were clean.

## What the source audit found

Reviewed: manifest, build files, network layer, updater, WebView use, reflection, permissions, embedded secrets.

* **No** dynamic dex loading, shell execution, native loading, SMS / contacts / call-log / accessibility / overlay /
  device-admin use, no trust-all TLS (already fixed in 18.2), no private keys or service-account secrets in the app.
* Signals that make heuristic engines nervous, and what was done about each:

| Signal | Status |
|---|---|
| APK signed with the shared **Android Debug** certificate | **Fixed**: release builds are signed with your own key (see below) and CI refuses a debug-signed APK |
| No code shrinking / obfuscation (R8 was off) | **Fixed**: R8 + resource shrinking ON for release (`-Pcigram.r8=false` to opt out) |
| Self-updater (`REQUEST_INSTALL_PACKAGES`) with optional integrity check, `http://` links accepted | **Hardened**: HTTPS only, SHA-256 mandatory, signer pinned, fail-closed (details below) |
| `android:usesCleartextTraffic="true"` | **Removed** (it was already overridden by `network_security_config.xml`, which still allows plain-http IPTV hosts and forbids it for Cigram's own back-ends — behaviour unchanged) |
| Exported background job service `CigramStage6JobService` | **Fixed**: `exported="false"` (JobScheduler binds it as the system, no other app needs to) |
| `READ_EXTERNAL_STORAGE` (API ≤ 32) never requested by any code | **Removed** (a `maxSdkVersion=28` copy is still added by the build because `WRITE_EXTERNAL_STORAGE` is declared for saving photos on Android 9-) |
| Duplicate `androidx.viewbinding.ViewBinding` class in the source tree | **Fixed without breaking the phone build**: the stub file stays in `src/main/java` (CodeAssist's own view-binding generator needs it), and `app/build.gradle.kts` excludes it from Gradle's javac so R8 no longer fails with `Type … is defined multiple times` |
| `RECORD_AUDIO`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, ad SDK, runtime launcher-icon switch | Kept: real features (voice messages, push, persisted job, ads, Cigram Plus icons) |

## Updater (`CigramUpdateVerify`, `CigramUpdateChecker`) — direct flavor only

An update is installed only if **all** of these hold, otherwise the file is deleted and the viewer is told why:

1. the link is `https://` with a real host and no user-info (`https://good@evil/` is refused);
2. the published manifest carries `sha256` and the downloaded file matches it (**no fingerprint = no install**);
3. when Android can read the archive: same package, higher `versionCode`, and signed by the same certificate as the
   installed app (or a certificate whose rotation lineage contains it);
4. any unexpected error while checking = refusal.

## What you must do (these cannot be done from the code)

1. **Create the release key** (once, back it up, never commit it):
   `tools/make-release-keystore.sh` → writes `cigram-release.jks` + git-ignored `keystore.properties`.
   For CI add the four secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
2. **Publish `sha256` in the update manifest** for every release (the CI job summary prints it). Without it the in-app
   updater now refuses to install. `docs/backend` already accepts the field (`sha256`, 64 hex chars).
3. **Existing installs signed with the debug key cannot be updated in place by an APK signed with a new key** — Android
   refuses a signature change. Those users must uninstall once and install the new build (the uninstall clears the
   app's local data; account data lives on the server). The new key is the app's identity from then on, so keep it safe.
   If you would rather avoid the one-time reinstall, the old debug keystore (on the phone that built the scanned APK)
   can be used with `apksigner rotate` to create a v3 lineage — that needs that exact keystore file.
4. **Register the new certificate** wherever the old one was registered: Google Sign-In OAuth client (SHA-1) and any
   API-key restriction in Google Cloud / Firebase (`CigramFcm` embeds a Firebase client key; it is public by design but
   should be restricted to the package + certificate). `tools/make-release-keystore.sh` prints the SHA-1/SHA-256.
5. **Re-scan** the new signed build on VirusTotal. If AhnLab still reports the heuristic label, send that sample to
   AhnLab as a false positive through their vendor channel; a single-vendor heuristic cannot be removed from our side,
   so a zero-detection result cannot be promised from the code alone. Publishing the `play` flavor through Google Play
   is the cleanest distribution route (no self-updater, no `REQUEST_INSTALL_PACKAGES`).

## Verified here

* `compilePlayDebugJavaWithJavac` before and after the changes — OK.
* `DiagnosticsTest` (updater decision table, URL gate, hash) — 15/15 pass. The other tests under `src/test` import
  Robolectric, which `app/build.gradle.kts` (CodeAssist-generated) never declared, so the suite did not compile before this
  change either; it was left alone.
* `assemblePlayRelease` and `assembleDirectRelease` with R8 on — OK, no missing-class warnings; APK 14.3 MB (was 20.0 MB).
* `apksigner verify`: v2 + v3, signer = the release certificate, no "Android Debug" anywhere; the `play` flavor has no
  `REQUEST_INSTALL_PACKAGES`.
* Release build with no keystore and `-Pcigram.requireSigning=true` fails with a clear message; debug builds are unaffected.

**Not verified:** running the R8-shrunk APK on a device/emulator (none available here). `proguard-rules.pro` was written for
this code base, but smoke-test login, chat, playback, cast and the updater on a phone before publishing.

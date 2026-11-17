# Cigram

| Folder | What | Version |
|---|---|---|
| `Sources/`, `project.yml` | Cigram for iPhone / iPad (UIKit, iOS 12+) | **1.1.0** |
| `android/cigram` | Cigram for Android phones + Android TV | 19.6.0 |
| `android/admin` | Cigram Studio (the owner's admin app) | 3.1.0 |

## Builds (GitHub Actions → Run workflow → Artifacts)
- **Build Cigram iOS (IPA)** → `Cigram-ipa` (unsigned: sign it on the phone with Sideloadly / AltStore / TrollStore).
- **Build Cigram Android** → `cigram-android` (APK of the app + APK of the admin).
  Optional secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (sign with your own key so the APK
  installs as an update over the one your users have) and `CIGRAM_ADMIN_TOKEN` (built into the admin app: it then never asks
  for anything).

See `CIGRAM-19.6-REPORT.md` for what changed.

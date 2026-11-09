# cigram

Two Android projects (original structures and package ids kept) + the Cloudflare Worker sources they talk to.

| Folder | What | Version |
|---|---|---|
| `cigram-main/` | Cigram main app (`com.Cigram.vid`) | 18.5.0 (92) |
| `cigram-admin/` | Cigram administration app (`com.my.newproject`) | 2.2.0 (10) |
| `*/docs/backend/` | Worker sources, patches, deploy bundles, tests, owner scripts (identical copy in both projects) | 19.0 |

Start with `cigram-main/UPDATE-19.0-REPORT.md` (what was done, what was verified, what is still yours to deploy).
The first commit (tag `baseline-pristine-import`) is the untouched CIGRAM-MAIN-FINAL / CIGRAM-ADMIN-FINAL import.
Build: `cd cigram-main && ./gradlew testPlayDebugUnitTest assemblePlayRelease` · `cd cigram-admin && ./gradlew testDebugUnitTest assembleRelease` (Android SDK 36; set `sdk.dir` in `local.properties`).

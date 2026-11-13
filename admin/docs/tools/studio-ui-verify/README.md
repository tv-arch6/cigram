# studio-ui-verify — draws the Studio 4 screens without an Android SDK

`gradle test` here builds the admin sources (plain `javac`, `--release 8`), starts Robolectric with native graphics, creates
`DxHomeActivity`, the six `StudioRoomActivity` rooms and `StudioSearchActivity` over a small fake catalogue, and writes PNGs to `shots/`.

## Put these jars in `libs/` (versions used when it was last run)
| file | source |
|---|---|
| `android-all.jar` | `org.robolectric:android-all:16-robolectric-13921718` |
| `okhttp.jar` `okio.jar` `kotlin-stdlib.jar` | `com.squareup.okhttp3:okhttp:4.12.0` · `com.squareup.okio:okio-jvm:3.6.0` · `org.jetbrains.kotlin:kotlin-stdlib:1.9.10` |
| `gson.jar` | `com.google.code.gson:gson:2.11.0` |
| `firebase-messaging.jar` `firebase-common.jar` `play-services-tasks.jar` `play-services-basement.jar` | `classes.jar` taken out of the AARs `firebase-messaging:24.1.2` · `firebase-common:21.0.0` · `play-services-tasks:18.2.0` · `play-services-basement:18.4.0` (Google Maven) |
| `monitor-1.7.2.jar` `espresso-idling-resource-3.6.1.jar` | `classes.jar` of `androidx.test:monitor:1.7.2` and `androidx.test.espresso:espresso-idling-resource:3.6.1` |

`stubs/` replaces the few androidx classes, the generated `R` and the view binding that `SeriesImportActivity` imports (that screen is untouched).

## Known limits of this drawing
* An `EditText` outside a real window draws its cursor but not its text or hint (the unmodified `CgUi.field` does the same), so the search field looks empty in `search_*.png`; the results under it are real.
* No Alyamama font (assets are not loaded): the device default font is used, so widths differ a little from a phone.
* Live numbers show dashes: nothing is fetched. The catalogue is the six fake rows in `StudioShotsTest.fakeCatalogue()`.

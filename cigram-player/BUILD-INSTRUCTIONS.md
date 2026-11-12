# Build instructions — Cigram Player

Requirements: JDK 17+, Android SDK with **platform 36** and build-tools 36.x, internet access to Google Maven / Maven Central, Gradle 8.13 (or the wrapper).

```bash
cd cigram-player
echo "sdk.dir=$HOME/Android/Sdk" > local.properties        # path of your Android SDK
gradle wrapper --gradle-version 8.13 && chmod +x gradlew   # the export has no gradlew

# 1) debug build (no key needed) – smoke test on a device/emulator
./gradlew :app:assembleDebug

# 2) upload key (once). Keep the .jks and passwords safe; enrol in Play App Signing in Play Console.
keytool -genkeypair -v -storetype PKCS12 -keystore cigram-player-upload.jks \
        -alias cigram-player -keyalg RSA -keysize 2048 -validity 10000
cp keystore.properties.example keystore.properties           # fill storeFile/passwords/alias (file is git-ignored)
#   or export CIGRAM_STORE_FILE / CIGRAM_STORE_PASSWORD / CIGRAM_KEY_ALIAS / CIGRAM_KEY_PASSWORD

# 3) Google Play bundle (refuses to run without a key).
#    FIRST set isMinifyEnabled = true and isShrinkResources = true in app/build.gradle.kts (they are false so that CodeAssist does not hang in R8)
./gradlew clean :app:bundleRelease        # -> app/build/outputs/bundle/release/app-release.aab
# 4) the same code as a signed APK for testing on a device
./gradlew :app:assembleRelease            # -> app/build/outputs/apk/release/app-release.apk
```

Verification commands
```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath     # dependency resolution
./gradlew :app:checkReleaseDuplicateClasses
./gradlew :app:lintVitalRelease                                         # full lint: ./gradlew :app:lintRelease
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/app-release.aab
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
# test the bundle exactly like Play would deliver it
java -jar bundletool.jar build-apks --bundle=app-release.aab --output=app.apks --mode=universal \
     --ks=cigram-player-upload.jks --ks-key-alias=cigram-player
java -jar bundletool.jar install-apks --apks=app.apks
```
Keep `app/build/outputs/mapping/release/mapping.txt` for every uploaded version (the AAB also embeds it for Play Console).
If R8 reports "Missing classes", add the lines from `app/build/outputs/mapping/release/missing_rules.txt` to `proguard-rules.pro`.

Hand-off test without the Cigram app (adb):
```bash
adb shell am start -a com.cigram.player.action.PLAY --es url "https://<your-test-host>/video.m3u8" --es title "Test"
adb shell am start -a android.intent.action.VIEW -d "https://<your-test-host>/video.mp4" -t video/mp4 com.cigram.player
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "https://<your-test-host>/video.mp4" com.cigram.player
adb shell am start -a com.cigram.player.action.PLAY --es url "file:///sdcard/a.mp4"      # must be rejected, no crash
```

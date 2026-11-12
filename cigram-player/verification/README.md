# Verification kit (what was actually run, and how to repeat it)

Needs only a JDK 17+ and the Android 16 framework jar (Maven Central: `org.robolectric:android-all:16-robolectric-13921718`).

```bash
J=app/src/main/java/com/cigram/player
ANDROID=android-all-16-robolectric-13921718.jar
mkdir -p out
# compile every player source (ExoPlayer API is a STUB written from memory: it only proves our own code is consistent,
# it does NOT prove the real ExoPlayer signatures — the real Gradle build does that)
javac --release 8 -proc:none -cp $ANDROID -d out $(find verification/stubs-exoplayer -name '*.java') $J/*.java
javac --release 8 -proc:none -cp out:$ANDROID -d out verification/tests/*.java
java -cp out:$ANDROID com.cigram.player.LogicTests     # expect: passed=10224 failed=0
java -cp out:$ANDROID com.cigram.player.IntentTests
```
`static-checks.txt` is the output of the manifest/Gradle/resource inspection. None of this replaces `./gradlew bundleRelease`, lint, or a device test.

#!/bin/sh
# usage: sh verification/run.sh /path/to/android-all-16-robolectric-13921718.jar   (run from the project root)
set -e
ANDROID="$1"; J=app/src/main/java/com/cigram/player; rm -rf out; mkdir -p out
# stubs = hand-written API outlines of ExoPlayer, Google Mobile Ads and UMP (from memory / copied from how the main app calls them):
# they prove our own code is consistent, NOT that the real library signatures match. The Gradle build does that.
javac --release 8 -proc:none -Xlint:-options -cp "$ANDROID" -d out $(find verification/stubs-exoplayer verification/stubs-gms verification/stubs-gms-r -name '*.java') $J/*.java
javac --release 8 -proc:none -Xlint:-options -cp "out:$ANDROID" -d out verification/tests/*.java
java -cp "out:$ANDROID" com.cigram.player.LogicTests
java -cp "out:$ANDROID" com.cigram.player.IntentTests

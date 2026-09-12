#!/usr/bin/env bash
# Reuse Next Player's AndroidJUnitRunner in a DISPOSABLE checkout, never its shared checkout.
set -euo pipefail
: "${NEXTPLAYER_CHECKOUT:?Set NEXTPLAYER_CHECKOUT to an isolated disposable Next Player checkout}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a task-owned ARM64 emulator}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
checkout=$(cd "$NEXTPLAYER_CHECKOUT" && pwd)
tests="$checkout/app/src/androidTest"
package=io/github/anilbeesetti/nextlib/media3ext/ffdecoder
mkdir -p "$tests/java/$package" "$tests/assets"
cp "$repo/media3ext/src/test/android/FfmpegVideoInitializationTest.java" "$tests/java/$package/"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=12:duration=12' \
    -c:v libx264 -preset ultrafast -g 12 -bf 0 -pix_fmt yuv420p -an "$build/h264.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=12:duration=12' \
    -c:v libx265 -preset ultrafast -x265-params 'pools=2:frame-threads=1:bframes=0:keyint=12:log-level=error' \
    -tag:v hvc1 -pix_fmt yuv420p -an "$build/hevc.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=12:duration=2' \
    -c:v libsvtav1 -preset 12 -svtav1-params 'lp=2' -an "$build/av1.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=12:duration=2' \
    -c:v mpeg2video -flags +global_header+low_delay -strict unofficial -g 12 -bf 0 \
    -an "$build/mpeg2.mp4"
cp "$build/"*.mp4 "$tests/assets/"
# Expose the versions already used by Next Player to its instrumentation classpath.
# Apply this only to the disposable host; no distributed dependencies are added.
cat > "$build/host.gradle" <<'GRADLE'
import org.gradle.api.tasks.testing.Test

gradle.projectsEvaluated {
    rootProject.allprojects {
        tasks.withType(Test).configureEach { ignoreFailures = false }
    }
    def app = rootProject.findProject(':app')
    if (app != null) {
        def libs = app.extensions.getByType(org.gradle.api.artifacts.VersionCatalogsExtension).named('libs')
        app.dependencies.add('androidTestImplementation', libs.findLibrary('github-anilbeesetti-nextlib-media3ext').get())
        app.dependencies.add('androidTestImplementation', libs.findLibrary('androidx-media3-exoplayer').get())
    }
}
GRADLE
(cd "$checkout" && ./gradlew -I "$build/host.gradle" -PnextlibPath="$repo" \
    :app:assembleDebug :app:assembleDebugAndroidTest --console=plain)
adb -s "$ANDROID_SERIAL" install -r "$checkout/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk"
adb -s "$ANDROID_SERIAL" install -r "$checkout/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegVideoInitializationTest \
    dev.anilbeesetti.nextplayer.debug.test/androidx.test.runner.AndroidJUnitRunner | tee "$build/results.txt"
# am instrument can exit zero even when a test fails.
grep -q '^OK (3 tests)' "$build/results.txt"

#!/usr/bin/env bash
# Run the production Java/JNI decoder and renderer regression tests on an explicit target.
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a task-owned disposable emulator}"
repo=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$repo"
./gradlew :media3ext:assembleDebugAndroidTest "$@"
adb -s "$ANDROID_SERIAL" install -r media3ext/build/outputs/apk/androidTest/debug/media3ext-debug-androidTest.apk
mkdir -p media3ext/build/reports/video-decoder
report=media3ext/build/reports/video-decoder/instrumentation.txt
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
    io.github.anilbeesetti.nextlib.media3ext.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
# am instrument can exit zero even when JUnit fails.
! rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_STATUS_CODE: -[12]' "$report"
rg -q '^OK \([0-9]+ tests?\)' "$report"

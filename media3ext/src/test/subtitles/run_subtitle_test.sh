#!/usr/bin/env bash
# Run on an already booted disposable emulator. Requires host FFmpeg and Python 3.
set -euo pipefail
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable emulator}"
case "$ANDROID_SERIAL" in emulator-*) ;; *) echo 'Use a disposable emulator' >&2; exit 1 ;; esac
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
cd "$repo"
python3 media3ext/src/test/subtitles/generate_fixtures.py
./gradlew :media3ext:assembleDebugAndroidTest --console=plain
adb -s "$ANDROID_SERIAL" install -r media3ext/build/outputs/apk/androidTest/debug/media3ext-debug-androidTest.apk
result=$(mktemp)
trap 'rm -f "$result"' EXIT
adb -s "$ANDROID_SERIAL" shell am instrument -w \
    -e class io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegTextRendererTest,io.github.anilbeesetti.nextlib.media3ext.ffdecoder.SubtitlePlaybackTest,io.github.anilbeesetti.nextlib.media3ext.ffdecoder.WebmWebvttOutputTest,io.github.anilbeesetti.nextlib.media3ext.ffdecoder.SubtitleViewportTest \
    io.github.anilbeesetti.nextlib.media3ext.test/androidx.test.runner.AndroidJUnitRunner | tee "$result"
# `am instrument` itself can exit successfully even when a test fails.
rg -q '^OK \([0-9]+ tests?\)' "$result"

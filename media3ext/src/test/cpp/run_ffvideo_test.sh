#!/usr/bin/env bash
# ARM64 Android API 26+ target; use a disposable emulator.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable ARM64 emulator}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++"
if [[ ! -x "$compiler" ]]; then
    compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
fi
"$compiler" --target=aarch64-linux-android26 -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/arm64-v8a" \
    "$repo/media3ext/src/test/cpp/ffvideo_test.cpp" "$repo/media3ext/src/main/cpp/ffcommon.cpp" \
    -L"$repo/ffmpeg/output/lib/arm64-v8a" -lavcodec -lavutil -lswscale -lswresample \
    -landroid -lmediandk -llog -o "$build/ffvideo_test"
adb shell mkdir -p /data/local/tmp/nextlib-ffvideo-test
adb push "$repo/media3ext/src/test/cpp/fixtures/vp9.ivf" "$build/ffvideo_test" "$repo"/ffmpeg/output/lib/arm64-v8a/*.so /data/local/tmp/nextlib-ffvideo-test/ >/dev/null
adb shell 'cd /data/local/tmp/nextlib-ffvideo-test && LD_LIBRARY_PATH=. ./ffvideo_test'

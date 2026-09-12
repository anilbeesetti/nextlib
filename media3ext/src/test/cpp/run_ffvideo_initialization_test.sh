#!/usr/bin/env bash
# ARM64 disposable emulator; optional FFMPEG_TEST_LIB_DIR enables the dimension-dependent fixture.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a task-owned ARM64 emulator}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
device_dir="/data/local/tmp/nextlib-init-$(basename "$build")"
trap 'adb -s "$ANDROID_SERIAL" shell rm -rf "$device_dir" >/dev/null; rm -rf "$build"' EXIT
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++"
[[ -x "$compiler" ]] || compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
"$compiler" --target=aarch64-linux-android26 -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/arm64-v8a" \
    "$repo/media3ext/src/test/cpp/ffvideo_initialization_test.cpp" \
    "$repo/media3ext/src/main/cpp/ffcommon.cpp" \
    -L"$repo/ffmpeg/output/lib/arm64-v8a" -lavcodec -lavutil -lswscale -lswresample \
    -landroid -llog -o "$build/ffvideo_initialization_test"
adb -s "$ANDROID_SERIAL" shell mkdir -p "$device_dir"
adb -s "$ANDROID_SERIAL" push "$build/ffvideo_initialization_test" "$repo"/ffmpeg/output/lib/arm64-v8a/*.so "$device_dir/" >/dev/null
fixture=""
if [[ -n ${FFMPEG_TEST_LIB_DIR:-} ]]; then
    ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=320x180:rate=1' \
        -map 0:v -c:v msmpeg4v3 -frames:v 1 -f data "$build/dimensions.bin"
    adb -s "$ANDROID_SERIAL" push "$FFMPEG_TEST_LIB_DIR/libavcodec.so" "$build/dimensions.bin" "$device_dir/" >/dev/null
    fixture=dimensions.bin
fi
adb -s "$ANDROID_SERIAL" shell "cd $device_dir && LD_LIBRARY_PATH=. ./ffvideo_initialization_test $fixture"

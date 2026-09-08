#!/usr/bin/env bash
# Requires a disposable ARM64 Android emulator.
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
"$compiler" --target=aarch64-linux-android23 -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/arm64-v8a" \
    "$repo/mediainfo/src/test/cpp/rotation_test.cpp" \
    -L"$repo/ffmpeg/output/lib/arm64-v8a" -lavformat -lavcodec -lavutil -lswscale \
    -ljnigraphics -o "$build/rotation_test"
device_dir=/data/local/tmp/nextlib-rotation-test
adb shell mkdir -p "$device_dir"
adb push "$build/rotation_test" "$repo"/ffmpeg/output/lib/arm64-v8a/*.so "$device_dir/" >/dev/null
adb shell "cd $device_dir && LD_LIBRARY_PATH=. ./rotation_test"

#!/usr/bin/env bash
# Requires a host FFmpeg with libsvtav1 and a disposable ARM64 Android emulator.
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
    "$repo/media3ext/src/test/cpp/av1_decoder_test.cpp" \
    -L"$repo/ffmpeg/output/lib/arm64-v8a" -lavformat -lavcodec -lavutil \
    -o "$build/av1_decoder_test"
device_dir=/data/local/tmp/nextlib-av1-test
adb shell mkdir -p "$device_dir"
adb push "$build/av1_decoder_test" "$repo"/ffmpeg/output/lib/arm64-v8a/*.so "$device_dir/" >/dev/null
for depth in 8 10; do
    pixel_format=yuv420p
    [[ $depth == 8 ]] || pixel_format=yuv420p10le
    ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' \
        -t 2 -c:v libsvtav1 -preset 12 -crf 36 -pix_fmt "$pixel_format" \
        -svtav1-params 'lp=2:film-grain=8' "$build/av1-$depth.mp4"
    adb push "$build/av1-$depth.mp4" "$device_dir/" >/dev/null
    adb shell "cd $device_dir && LD_LIBRARY_PATH=. ./av1_decoder_test av1-$depth.mp4 $depth 48"
done

#!/usr/bin/env bash
# Requires built FFmpeg libraries, host ffmpeg, and a disposable ARM64 API 26+ emulator.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable ARM64 emulator}"
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
device_dir=/data/local/tmp/nextlib-ffaudio-$(basename "$build")
trap 'adb -s "$ANDROID_SERIAL" shell rm -rf "$device_dir" >/dev/null; rm -rf "$build"' EXIT
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++"
if [[ ! -x "$compiler" ]]; then
    compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
fi
asan=$("$compiler" --target=aarch64-linux-android26 -print-file-name=libclang_rt.asan-aarch64-android.so)
"$compiler" --target=aarch64-linux-android26 -std=c++17 -O1 -g \
    -fsanitize=address -fno-omit-frame-pointer -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/arm64-v8a" \
    "$repo/media3ext/src/test/cpp/ffaudio_reset_test.cpp" "$repo/media3ext/src/main/cpp/ffcommon.cpp" \
    -L"$repo/ffmpeg/output/lib/arm64-v8a" -lavformat -lavcodec -lavutil -lswresample \
    -landroid -llog -o "$build/ffaudio_reset_test"
ffmpeg -hide_banner -loglevel error -f lavfi -i 'sine=frequency=997:sample_rate=48000' \
    -t 0.1 -ac 2 -c:a truehd -strict experimental "$build/truehd.mka"
adb -s "$ANDROID_SERIAL" shell mkdir -p "$device_dir"
adb -s "$ANDROID_SERIAL" push "$build/ffaudio_reset_test" "$build/truehd.mka" "$asan" \
    "$repo"/ffmpeg/output/lib/arm64-v8a/*.so "$device_dir/" >/dev/null
adb -s "$ANDROID_SERIAL" shell "cd $device_dir && LD_LIBRARY_PATH=. \
    LD_PRELOAD=./libclang_rt.asan-aarch64-android.so ASAN_OPTIONS=detect_leaks=0 \
    ./ffaudio_reset_test truehd.mka"

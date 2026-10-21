#!/usr/bin/env bash
# Requires a host FFmpeg with libsvtav1 and a disposable Android target.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to a disposable Android target}"
abi=$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    armeabi-v7a) target=armv7a-linux-androideabi ;;
    x86_64) target=x86_64-linux-android ;;
    x86) target=i686-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/clang++"
if [[ ! -x "$compiler" ]]; then
    compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
fi
"$compiler" --target="${target}23" -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/$abi" \
    "$repo/media3ext/src/test/cpp/av1_decoder_test.cpp" \
    "$repo/media3ext/src/main/cpp/ffcommon.cpp" \
    -L"$repo/ffmpeg/output/lib/$abi" -lavformat -lavcodec -lavutil -lswscale -lswresample \
    -landroid -llog \
    -o "$build/av1_decoder_test"
device_dir=$(adb -s "$ANDROID_SERIAL" shell mktemp -d /data/local/tmp/nextlib-av1-test.XXXXXX | tr -d '\r')
trap 'adb -s "$ANDROID_SERIAL" shell rm -rf "$device_dir"; rm -rf "$build"' EXIT
adb -s "$ANDROID_SERIAL" push "$build/av1_decoder_test" "$repo"/ffmpeg/output/lib/"$abi"/*.so "$device_dir/" >/dev/null
for depth in 8 10; do
    pixel_format=yuv420p
    [[ $depth == 8 ]] || pixel_format=yuv420p10le
    ffmpeg -hide_banner -loglevel error -f lavfi -i 'testsrc2=size=640x360:rate=24' \
        -t 2 -c:v libsvtav1 -preset 12 -crf 36 -pix_fmt "$pixel_format" \
        -svtav1-params 'lp=2:film-grain=8' "$build/av1-$depth.mp4"
    adb -s "$ANDROID_SERIAL" push "$build/av1-$depth.mp4" "$device_dir/" >/dev/null
    adb -s "$ANDROID_SERIAL" shell "cd '$device_dir' && LD_LIBRARY_PATH=. ./av1_decoder_test av1-$depth.mp4 $depth 48"
done

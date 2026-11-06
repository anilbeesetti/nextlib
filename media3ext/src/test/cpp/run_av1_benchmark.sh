#!/usr/bin/env bash
# Run on a disposable Android target. The input must contain only AV1 video.
set -euo pipefail
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL}"
input=${1:?Usage: run_av1_benchmark.sh input.ivf [repeats] [threads]}
repeats=${2:-5}
threads=${3:-4}
repo=$(cd "$(dirname "$0")/../../../.." && pwd)
abi=$(adb -s "$ANDROID_SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
case "$abi" in
    arm64-v8a) target=aarch64-linux-android ;;
    armeabi-v7a) target=armv7a-linux-androideabi ;;
    x86_64) target=x86_64-linux-android ;;
    x86) target=i686-linux-android ;;
    *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
esac
host=linux-x86_64
[[ $(uname -s) != Darwin ]] || host=darwin-x86_64
build=$(mktemp -d)
remote=$(adb -s "$ANDROID_SERIAL" shell mktemp -d /data/local/tmp/nextlib-av1-bench.XXXXXX | tr -d '\r')
trap 'adb -s "$ANDROID_SERIAL" shell rm -rf "$remote"; rm -rf "$build"' EXIT
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/clang++" \
    --target="${target}23" -std=c++17 -O2 -static-libstdc++ \
    -Wl,-z,max-page-size=16384 -I"$repo/ffmpeg/output/include/$abi" \
    "$repo/media3ext/src/test/cpp/av1_decoder_benchmark.cpp" \
    "$repo/media3ext/src/main/cpp/ffcommon.cpp" \
    -L"$repo/ffmpeg/output/lib/$abi" -lavformat -lavcodec -lavutil -lswscale -lswresample \
    -landroid -llog -o "$build/av1_decoder_benchmark"
adb -s "$ANDROID_SERIAL" push "$build/av1_decoder_benchmark" \
    "$repo"/ffmpeg/output/lib/"$abi"/*.so "$remote/" >/dev/null
adb -s "$ANDROID_SERIAL" push "$input" "$remote/input.ivf" >/dev/null
for ((run=0; run<repeats; run++)); do
    for rgba in 0 1; do
        # Alternate order between runs to reduce systematic warm-up bias.
        for ((order=0; order<2; order++)); do
            delay=$(((run + order) % 2))
            adb -s "$ANDROID_SERIAL" shell "cd '$remote' && LD_LIBRARY_PATH=. ./av1_decoder_benchmark input.ivf '$threads' '$delay' '$rgba'"
        done
    done
done

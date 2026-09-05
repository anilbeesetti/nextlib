#!/bin/bash
set -euo pipefail

# Versions
VPX_VERSION=1.13.0
MBEDTLS_VERSION=3.4.1
FFMPEG_VERSION=6.0

# Directories
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR=$BASE_DIR/build
OUTPUT_DIR=$BASE_DIR/output
SOURCES_DIR=$BASE_DIR/sources
FFMPEG_DIR=$SOURCES_DIR/ffmpeg-$FFMPEG_VERSION
VPX_DIR=$SOURCES_DIR/libvpx-$VPX_VERSION
MBEDTLS_DIR=$SOURCES_DIR/mbedtls-$MBEDTLS_VERSION

# Configuration
ANDROID_ABIS="x86 x86_64 armeabi-v7a arm64-v8a"
ANDROID_PLATFORM=21
ENABLED_DECODERS="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo libvpx_vp8 libvpx_vp9"
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || sysctl -n hw.physicalcpu 2>/dev/null || echo 4)

# Gradle supplies these; standalone callers use the same pinned versions.
CATALOG="$BASE_DIR/../gradle/libs.versions.toml"
ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-$(sed -n 's/^ndk = "\(.*\)"/\1/p' "$CATALOG")}
ANDROID_CMAKE_VERSION=${ANDROID_CMAKE_VERSION:-$(sed -n 's/^cmake = "\(.*\)"/\1/p' "$CATALOG")}
: "${ANDROID_NDK_VERSION:?Missing NDK version}"
: "${ANDROID_CMAKE_VERSION:?Missing CMake version}"
ANDROID_NDK_HOME="$ANDROID_HOME/ndk/$ANDROID_NDK_VERSION"
CMAKE_EXECUTABLE="$ANDROID_HOME/cmake/$ANDROID_CMAKE_VERSION/bin/cmake"

case "$(uname -s)" in
  Darwin) HOST_PLATFORM=darwin-x86_64 ;;
  Linux) HOST_PLATFORM=linux-x86_64 ;;
  *) echo "Build FFmpeg on macOS or Linux (WSL on Windows)." >&2; exit 1 ;;
esac
TOOLCHAIN_PREFIX="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_PLATFORM"

PACKAGES=()
[[ -x "$TOOLCHAIN_PREFIX/bin/clang" ]] || PACKAGES+=("ndk/$ANDROID_NDK_VERSION")
[[ -x "$CMAKE_EXECUTABLE" ]] || PACKAGES+=("cmake/$ANDROID_CMAKE_VERSION")
if (( ${#PACKAGES[@]} )); then
  ANDROID_CLI=${ANDROID_CLI:-android}
  command -v "$ANDROID_CLI" >/dev/null || {
    echo "Install Android CLI from https://developer.android.com/tools/agents and add android to PATH (or set ANDROID_CLI)." >&2
    exit 1
  }
  "$ANDROID_CLI" --sdk="$ANDROID_HOME" sdk install "${PACKAGES[@]}"
fi
[[ -x "$TOOLCHAIN_PREFIX/bin/clang" && -x "$CMAKE_EXECUTABLE" ]] || {
  echo "Android NDK or CMake installation is incomplete." >&2
  exit 1
}
for tool in curl tar make pkg-config; do
  command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done

mkdir -p "$SOURCES_DIR"

# Publish a source directory only after a complete download and extraction.
downloadSource() (
  destination=$2
  staging=$(mktemp -d "$SOURCES_DIR/.download.XXXXXX")
  trap 'rm -rf "$staging"' EXIT
  curl --fail --location --retry 3 "$1" -o "$staging/source.tar.gz"
  tar -zxf "$staging/source.tar.gz" -C "$staging"
  mv "$staging/$(basename "$destination")" "$destination"
)

function buildLibVpx() {
  pushd "$VPX_DIR"

  for ABI in $ANDROID_ABIS; do
    VPX_AS=${TOOLCHAIN_PREFIX}/bin/llvm-as
    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      EXTRA_BUILD_FLAGS="--force-target=armv7-android-gcc --disable-neon"
      TOOLCHAIN=armv7a-linux-androideabi21-
      ;;
    arm64-v8a)
      EXTRA_BUILD_FLAGS="--force-target=armv8-android-gcc"
      TOOLCHAIN=aarch64-linux-android21-
      ;;
    x86)
      EXTRA_BUILD_FLAGS="--force-target=x86-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic"
      VPX_AS=$(command -v yasm || printf '%s\n' "${TOOLCHAIN_PREFIX}/bin/yasm")
      TOOLCHAIN=i686-linux-android21-
      ;;
    x86_64)
      EXTRA_BUILD_FLAGS="--force-target=x86_64-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic --disable-neon --disable-neon-asm"
      VPX_AS=$(command -v yasm || printf '%s\n' "${TOOLCHAIN_PREFIX}/bin/yasm")
      TOOLCHAIN=x86_64-linux-android21-
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    CC="${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang"
    CC="$CC" \
      CXX="${CC}++" \
      LD="$CC" \
      AR=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
      AS=${VPX_AS} \
      STRIP=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
      NM=${TOOLCHAIN_PREFIX}/bin/llvm-nm \
      LDFLAGS="-Wl,-z,max-page-size=16384" \
      ./configure \
      --prefix="$BUILD_DIR/external/$ABI" \
      --libc="${TOOLCHAIN_PREFIX}/sysroot" \
      --enable-vp8 \
      --enable-vp9 \
      --enable-static \
      --disable-shared \
      --disable-examples \
      --disable-docs \
      --enable-realtime-only \
      --enable-install-libs \
      --enable-multithread \
      --disable-webm-io \
      --disable-libyuv \
      --enable-better-hw-compatibility \
      --disable-runtime-cpu-detect \
      ${EXTRA_BUILD_FLAGS}

    make clean
    make -j$JOBS
    make install
  done
  popd
}

function buildMbedTLS() {
    pushd "$MBEDTLS_DIR"

    for ABI in $ANDROID_ABIS; do

      CMAKE_BUILD_DIR=$MBEDTLS_DIR/mbedtls_build_${ABI}
      rm -rf "${CMAKE_BUILD_DIR}"
      mkdir -p "${CMAKE_BUILD_DIR}"
      cd "${CMAKE_BUILD_DIR}"

      "${CMAKE_EXECUTABLE}" .. \
       -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
       -DANDROID_ABI=$ABI \
       -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
       -DCMAKE_INSTALL_PREFIX="$BUILD_DIR/external/$ABI" \
       -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
       -DENABLE_TESTING=0

      make -j$JOBS
      make install

    done
    popd
}

function buildFfmpeg() {
  pushd "$FFMPEG_DIR"
  EXTRA_BUILD_CONFIGURATION_FLAGS=""
  COMMON_OPTIONS=""

  # Add enabled decoders to FFmpeg build configuration
  for decoder in $ENABLED_DECODERS; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
  done

  # Build FFmpeg for each architecture and platform
  for ABI in $ANDROID_ABIS; do
    EXTRA_BUILD_CONFIGURATION_FLAGS=""

    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      TOOLCHAIN=armv7a-linux-androideabi21-
      CPU=armv7-a
      ARCH=arm
      ;;
    arm64-v8a)
      TOOLCHAIN=aarch64-linux-android21-
      CPU=armv8-a
      ARCH=aarch64
      ;;
    x86)
      TOOLCHAIN=i686-linux-android21-
      CPU=i686
      ARCH=i686
      EXTRA_BUILD_CONFIGURATION_FLAGS=--disable-asm
      ;;
    x86_64)
      TOOLCHAIN=x86_64-linux-android21-
      CPU=x86_64
      ARCH=x86_64
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    # Referencing dependencies without pkgconfig
    DEP_CFLAGS="-I$BUILD_DIR/external/$ABI/include"
    DEP_LD_FLAGS="-L$BUILD_DIR/external/$ABI/lib"

    # Configure FFmpeg build
    ./configure \
      --prefix="$BUILD_DIR/$ABI" \
      --enable-cross-compile \
      --arch=$ARCH \
      --cpu=$CPU \
      --cross-prefix="${TOOLCHAIN_PREFIX}/bin/$TOOLCHAIN" \
      --nm="${TOOLCHAIN_PREFIX}/bin/llvm-nm" \
      --ar="${TOOLCHAIN_PREFIX}/bin/llvm-ar" \
      --ranlib="${TOOLCHAIN_PREFIX}/bin/llvm-ranlib" \
      --strip="${TOOLCHAIN_PREFIX}/bin/llvm-strip" \
      --extra-cflags="-O3 -fPIC $DEP_CFLAGS" \
      --extra-ldflags="$DEP_LD_FLAGS -Wl,-z,max-page-size=16384" \
      --pkg-config="$(command -v pkg-config)" \
      --target-os=android \
      --enable-shared \
      --disable-static \
      --disable-doc \
      --disable-programs \
      --disable-everything \
      --disable-vulkan \
      --disable-avdevice \
      --disable-avformat \
      --disable-postproc \
      --disable-avfilter \
      --disable-symver \
      --enable-parsers \
      --enable-demuxers \
      --enable-swresample \
      --enable-avformat \
      --enable-libvpx \
      --enable-protocol=file,http,https,mmsh,mmst,pipe,rtmp,rtmps,rtmpt,rtmpts,rtp,tls \
      --enable-version3 \
      --enable-mbedtls \
      --extra-ldexeflags=-pie \
      --disable-debug \
      ${EXTRA_BUILD_CONFIGURATION_FLAGS} \
      ${COMMON_OPTIONS}

    # Build FFmpeg
    echo "Building FFmpeg for $ARCH..."
    make clean
    make -j$JOBS
    make install

    OUTPUT_LIB=${OUTPUT_DIR}/lib/${ABI}
    mkdir -p "${OUTPUT_LIB}"
    cp "${BUILD_DIR}"/"${ABI}"/lib/*.so "${OUTPUT_LIB}"

    OUTPUT_HEADERS=${OUTPUT_DIR}/include/${ABI}
    mkdir -p "${OUTPUT_HEADERS}"
    cp -r "${BUILD_DIR}"/"${ABI}"/include/* "${OUTPUT_HEADERS}"

  done
  popd
}

# Gradle owns up-to-date checks. Existing directories can be left by failed builds.
if [[ ! -d "$MBEDTLS_DIR" ]]; then
  downloadSource "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v${MBEDTLS_VERSION}.tar.gz" "$MBEDTLS_DIR"
fi
if [[ ! -d "$VPX_DIR" ]]; then
  downloadSource "https://github.com/webmproject/libvpx/archive/refs/tags/v${VPX_VERSION}.tar.gz" "$VPX_DIR"
fi
if [[ ! -d "$FFMPEG_DIR" ]]; then
  downloadSource "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" "$FFMPEG_DIR"
fi
buildMbedTLS
buildLibVpx
buildFfmpeg

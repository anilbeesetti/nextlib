#!/bin/bash
set -euo pipefail

# Versions
DAV1D_VERSION=1.5.4
MBEDTLS_VERSION=3.6.7
FFMPEG_VERSION=9.0.1
LIBASS_VERSION=0.17.5
FREETYPE_VERSION=2.14.1
FRIBIDI_VERSION=1.0.16
HARFBUZZ_VERSION=14.4.0
FONTCONFIG_VERSION=2.16.0
EXPAT_VERSION=2.8.4
UNIBREAK_VERSION=7.0

# Directories
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR=$BASE_DIR/build
OUTPUT_DIR=$BASE_DIR/output
SOURCES_DIR=$BASE_DIR/sources
FFMPEG_DIR=$SOURCES_DIR/ffmpeg-$FFMPEG_VERSION
DAV1D_DIR=$SOURCES_DIR/dav1d-$DAV1D_VERSION
MBEDTLS_DIR=$SOURCES_DIR/mbedtls-$MBEDTLS_VERSION

# Configuration
ANDROID_ABIS="x86 x86_64 armeabi-v7a arm64-v8a"
ANDROID_PLATFORM=21
ENABLED_DECODERS="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo vp8 vp9 libdav1d pgssub dvdsub dvbsub"
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
for tool in curl tar make pkg-config meson ninja nasm python3 gperf; do
  command -v "$tool" >/dev/null || { echo "Missing build tool: $tool" >&2; exit 1; }
done

mkdir -p "$SOURCES_DIR"

# Publish a source directory only after a complete download and extraction.
downloadSource() (
  destination=$2
  [[ ! -d "$destination" ]] || exit 0
  staging=$(mktemp -d "$SOURCES_DIR/.download.XXXXXX")
  trap 'rm -rf "$staging"' EXIT
  curl --fail --location --retry 3 "$1" -o "$staging/source.tar"
  tar -xf "$staging/source.tar" -C "$staging"
  mv "$staging/$(basename "$destination")" "$destination"
)

# All native dependencies use the same target and installation prefix.
function setAbiToolchain() {
  case $ABI in
    armeabi-v7a) NATIVE_CPU=arm; NATIVE_TARGET=armv7a-linux-androideabi ;;
    arm64-v8a) NATIVE_CPU=aarch64; NATIVE_TARGET=aarch64-linux-android ;;
    x86) NATIVE_CPU=x86; NATIVE_TARGET=i686-linux-android ;;
    x86_64) NATIVE_CPU=x86_64; NATIVE_TARGET=x86_64-linux-android ;;
  esac
}

function buildMeson() {
  local name=$1 source=$2
  shift 2
  for ABI in $ANDROID_ABIS; do
    setAbiToolchain
    local prefix="$BUILD_DIR/external/$ABI" build="$BUILD_DIR/$name/$ABI"
    local cross="$BUILD_DIR/$name/$ABI.meson"
    mkdir -p "$BUILD_DIR/$name"
    cat > "$cross" <<EOF
[binaries]
c = '$TOOLCHAIN_PREFIX/bin/$NATIVE_TARGET$ANDROID_PLATFORM-clang'
cpp = '$TOOLCHAIN_PREFIX/bin/$NATIVE_TARGET$ANDROID_PLATFORM-clang++'
ar = '$TOOLCHAIN_PREFIX/bin/llvm-ar'
strip = '$TOOLCHAIN_PREFIX/bin/llvm-strip'
pkg-config = '$(command -v pkg-config)'
nasm = '$(command -v nasm)'

[properties]
needs_exe_wrapper = true
pkg_config_libdir = '$prefix/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = '$NATIVE_CPU'
cpu = '$NATIVE_CPU'
endian = 'little'
EOF
    # Reconfigure after interrupted builds and source/toolchain changes.
    rm -rf "$build"
    PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" meson setup "$build" "$source" \
      --cross-file="$cross" --prefix="$prefix" --libdir=lib \
      --buildtype=release --default-library=static --wrap-mode=nodownload \
      -Db_staticpic=true -Dprefer_static=true -Dauto_features=disabled "$@"
    ninja -C "$build" -j"$JOBS"
    ninja -C "$build" install
  done
}

function buildCmake() {
  local name=$1 source=$2
  shift 2
  for ABI in $ANDROID_ABIS; do
    local build="$BUILD_DIR/$name/$ABI"
    rm -rf "$build"
    "$CMAKE_EXECUTABLE" -S "$source" -B "$build" \
      -DANDROID_PLATFORM="$ANDROID_PLATFORM" -DANDROID_ABI="$ABI" \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DCMAKE_INSTALL_PREFIX="$BUILD_DIR/external/$ABI" -DCMAKE_INSTALL_LIBDIR=lib \
      -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON "$@"
    "$CMAKE_EXECUTABLE" --build "$build" -j "$JOBS"
    "$CMAKE_EXECUTABLE" --install "$build"
  done
}

function buildAutotools() (
  name=$1 source=$2
  shift 2
  for ABI in $ANDROID_ABIS; do
    setAbiToolchain
    build="$BUILD_DIR/$name/$ABI"
    rm -rf "$build"
    mkdir -p "$build"
    cd "$build"
    CC="$TOOLCHAIN_PREFIX/bin/$NATIVE_TARGET$ANDROID_PLATFORM-clang" \
      AR="$TOOLCHAIN_PREFIX/bin/llvm-ar" RANLIB="$TOOLCHAIN_PREFIX/bin/llvm-ranlib" \
      NM="$TOOLCHAIN_PREFIX/bin/llvm-nm" STRIP="$TOOLCHAIN_PREFIX/bin/llvm-strip" \
      CFLAGS="-O3 -fPIC" LDFLAGS="-Wl,-z,max-page-size=16384" \
      PKG_CONFIG="$(command -v pkg-config) --static" PKG_CONFIG_PATH= \
      PKG_CONFIG_LIBDIR="$BUILD_DIR/external/$ABI/lib/pkgconfig" \
      "$source/configure" --host="$NATIVE_TARGET" --prefix="$BUILD_DIR/external/$ABI" "$@"
    make -j"$JOBS"
    make install
  done
)

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
      CPU=x86-64
      ARCH=x86_64
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    # Restrict pkg-config to target libraries, never the host's installed dav1d.
    DEP_CFLAGS="-I$BUILD_DIR/external/$ABI/include"
    DEP_LD_FLAGS="-L$BUILD_DIR/external/$ABI/lib"

    # Configure FFmpeg build
    PKG_CONFIG_PATH= PKG_CONFIG_LIBDIR="$BUILD_DIR/external/$ABI/lib/pkgconfig" ./configure \
      --prefix="$BUILD_DIR/$ABI" \
      --enable-cross-compile \
      --x86asmexe="$(command -v nasm)" \
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
      --pkg-config-flags=--static \
      --target-os=android \
      --enable-shared \
      --disable-static \
      --disable-doc \
      --disable-programs \
      --disable-everything \
      --disable-vulkan \
      --disable-avdevice \
      --disable-avformat \
      --disable-avfilter \
      --disable-symver \
      --enable-parsers \
      --enable-demuxers \
      --enable-swresample \
      --enable-avformat \
      --enable-libdav1d \
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
  # GitHub's generated source archives omit required submodules/generated files.
  downloadSource "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-${MBEDTLS_VERSION}/mbedtls-${MBEDTLS_VERSION}.tar.bz2" "$MBEDTLS_DIR"
fi
if [[ ! -d "$FFMPEG_DIR" ]]; then
  downloadSource "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" "$FFMPEG_DIR"
fi
if [[ ! -d "$DAV1D_DIR" ]]; then
  downloadSource "https://github.com/videolan/dav1d/archive/refs/tags/${DAV1D_VERSION}.tar.gz" "$DAV1D_DIR"
fi

downloadSource "https://github.com/libass/libass/releases/download/$LIBASS_VERSION/libass-$LIBASS_VERSION.tar.xz" "$SOURCES_DIR/libass-$LIBASS_VERSION"
downloadSource "https://download.savannah.gnu.org/releases/freetype/freetype-$FREETYPE_VERSION.tar.xz" "$SOURCES_DIR/freetype-$FREETYPE_VERSION"
downloadSource "https://github.com/fribidi/fribidi/releases/download/v$FRIBIDI_VERSION/fribidi-$FRIBIDI_VERSION.tar.xz" "$SOURCES_DIR/fribidi-$FRIBIDI_VERSION"
downloadSource "https://github.com/harfbuzz/harfbuzz/releases/download/$HARFBUZZ_VERSION/harfbuzz-$HARFBUZZ_VERSION.tar.xz" "$SOURCES_DIR/harfbuzz-$HARFBUZZ_VERSION"
downloadSource "https://www.freedesktop.org/software/fontconfig/release/fontconfig-$FONTCONFIG_VERSION.tar.xz" "$SOURCES_DIR/fontconfig-$FONTCONFIG_VERSION"
downloadSource "https://github.com/libexpat/libexpat/releases/download/R_${EXPAT_VERSION//./_}/expat-$EXPAT_VERSION.tar.xz" "$SOURCES_DIR/expat-$EXPAT_VERSION"
downloadSource "https://github.com/adah1972/libunibreak/releases/download/libunibreak_${UNIBREAK_VERSION//./_}/libunibreak-$UNIBREAK_VERSION.tar.gz" "$SOURCES_DIR/libunibreak-$UNIBREAK_VERSION"

buildCmake mbedtls "$MBEDTLS_DIR" -DUSE_STATIC_MBEDTLS_LIBRARY=ON -DUSE_SHARED_MBEDTLS_LIBRARY=OFF -DENABLE_PROGRAMS=OFF -DENABLE_TESTING=OFF
buildMeson dav1d "$DAV1D_DIR" -Denable_tools=false -Denable_tests=false
buildMeson freetype "$SOURCES_DIR/freetype-$FREETYPE_VERSION" -Dharfbuzz=disabled -Dzlib=system -Dmmap=enabled
buildMeson fribidi "$SOURCES_DIR/fribidi-$FRIBIDI_VERSION" -Ddocs=false -Dbin=false -Dtests=false
buildMeson harfbuzz "$SOURCES_DIR/harfbuzz-$HARFBUZZ_VERSION" -Dtests=disabled -Dutilities=disabled -Dsubset=disabled -Draster=disabled -Dvector=disabled -Dgpu=disabled
buildCmake expat "$SOURCES_DIR/expat-$EXPAT_VERSION" -DEXPAT_SHARED_LIBS=OFF -DEXPAT_BUILD_TOOLS=OFF -DEXPAT_BUILD_EXAMPLES=OFF -DEXPAT_BUILD_TESTS=OFF -DEXPAT_BUILD_DOCS=OFF
buildMeson fontconfig "$SOURCES_DIR/fontconfig-$FONTCONFIG_VERSION" -Dxml-backend=expat -Dcache-build=disabled -Ddefault-fonts-dirs=/system/fonts -Dadditional-fonts-dirs=/product/fonts,/system_ext/fonts
buildAutotools unibreak "$SOURCES_DIR/libunibreak-$UNIBREAK_VERSION" --disable-shared --enable-static
# Upstream Autotools supplies the public symbol export list for the shared library.
# Its font dependencies are static, so consumers only need libass.so.
buildAutotools libass "$SOURCES_DIR/libass-$LIBASS_VERSION" --enable-shared --disable-static --enable-fontconfig --enable-libunibreak --disable-test --disable-profile
for ABI in $ANDROID_ABIS; do
  mkdir -p "$OUTPUT_DIR/lib/$ABI" "$OUTPUT_DIR/include/$ABI"
  cp "$BUILD_DIR/external/$ABI/lib/libass.so" "$OUTPUT_DIR/lib/$ABI/"
  cp -R "$BUILD_DIR/external/$ABI/include/ass" "$OUTPUT_DIR/include/$ABI/"
done
for entry in "libass-$LIBASS_VERSION/COPYING" "freetype-$FREETYPE_VERSION/docs/FTL.TXT" "fribidi-$FRIBIDI_VERSION/COPYING" "harfbuzz-$HARFBUZZ_VERSION/COPYING" "fontconfig-$FONTCONFIG_VERSION/COPYING" "expat-$EXPAT_VERSION/COPYING" "libunibreak-$UNIBREAK_VERSION/LICENCE"; do
  mkdir -p "$OUTPUT_DIR/licenses/native-dependencies/$(dirname "$entry")"
  cp "$SOURCES_DIR/$entry" "$OUTPUT_DIR/licenses/native-dependencies/$entry"
done
buildFfmpeg

#!/bin/bash

# Versions
VPX_VERSION=1.13.0
MBEDTLS_VERSION=3.4.1
DAV1D_VERSION=1.4.1
FFMPEG_VERSION=6.0

# Directories
BASE_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR=$BASE_DIR/build
OUTPUT_DIR=$BASE_DIR/output
SOURCES_DIR=$BASE_DIR/sources
FFMPEG_DIR=$SOURCES_DIR/ffmpeg-$FFMPEG_VERSION
VPX_DIR=$SOURCES_DIR/libvpx-$VPX_VERSION
MBEDTLS_DIR=$SOURCES_DIR/mbedtls-$MBEDTLS_VERSION
DAV1D_DIR=$SOURCES_DIR/dav1d-$DAV1D_VERSION

# Configuration
ANDROID_ABIS="x86 x86_64 armeabi-v7a arm64-v8a"
ANDROID_PLATFORM=21
ENABLED_DECODERS="vorbis opus flac alac pcm_mulaw pcm_alaw mp3 amrnb amrwb aac ac3 eac3 dca mlp truehd h264 hevc mpeg2video mpegvideo libvpx_vp8 libvpx_vp9 libdav1d"
JOBS=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || sysctl -n hw.pysicalcpu || echo 4)

# Set up host platform variables
HOST_PLATFORM="linux-x86_64"
case "$OSTYPE" in
darwin*) HOST_PLATFORM="darwin-x86_64" ;;
linux*) HOST_PLATFORM="linux-x86_64" ;;
msys)
  case "$(uname -m)" in
  x86_64) HOST_PLATFORM="windows-x86_64" ;;
  i686) HOST_PLATFORM="windows" ;;
  esac
  ;;
esac

# Build tools
TOOLCHAIN_PREFIX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_PLATFORM}"
CMAKE_EXECUTABLE=${ANDROID_SDK_HOME}/cmake/3.22.1/bin/cmake
# Using Build machine's Ninja. It is used for libdav1d building. Needs to be installed
NINJA_EXECUTABLE=$(which ninja)
# Meson is used for libdav1d building. Needs to be installed
MESON_EXECUTABLE=$(which meson)

NASM_EXECUTABLE=$(which nasm)

export FAM_CC=${TOOLCHAIN_PREFIX}/bin/${TARGET}-clang
export FAM_CXX=${FAM_CC}++
export FAM_LD=${FAM_CC}

mkdir -p $SOURCES_DIR

function downloadLibVpx() {
  pushd $SOURCES_DIR
  echo "Downloading Vpx source code of version $VPX_VERSION..."
  VPX_FILE=libvpx-$VPX_VERSION.tar.gz
  curl -L "https://github.com/webmproject/libvpx/archive/refs/tags/v${VPX_VERSION}.tar.gz" -o $VPX_FILE
  [ -e $VPX_FILE ] || { echo "$VPX_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $VPX_FILE
  rm $VPX_FILE
  popd
}

function downloadMbedTLS() {
  pushd $SOURCES_DIR
  echo "Downloading mbedtls source code of version $MBEDTLS_VERSION..."
  MBEDTLS_FILE=mbedtls-$MBEDTLS_VERSION.tar.gz
  curl -L "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v${MBEDTLS_VERSION}.tar.gz" -o $MBEDTLS_FILE
  [ -e $MBEDTLS_FILE ] || { echo "$MBEDTLS_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $MBEDTLS_FILE
  rm $MBEDTLS_FILE
  popd
}

function downloadDav1d() {
    pushd $SOURCES_DIR
    echo "Downloading Dav1d source code of version $DAV1D_VERSION..."
    DAV1D_FILE=dav1d-$DAV1D_VERSION.tar.gz
    curl -L "https://code.videolan.org/videolan/dav1d/-/archive/${DAV1D_VERSION}/dav1d-${DAV1D_VERSION}.tar.gz" -o $DAV1D_FILE
    [ -e $DAV1D_FILE ] || { echo "$DAV1D_FILE does not exist. Exiting..."; exit 1; }
    tar -zxf $DAV1D_FILE
    rm $DAV1D_FILE
    popd
}

function downloadFfmpeg() {
  pushd $SOURCES_DIR
  echo "Downloading FFmpeg source code of version $FFMPEG_VERSION..."
  FFMPEG_FILE=ffmpeg-$FFMPEG_VERSION.tar.gz
  curl -L "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" -o $FFMPEG_FILE
  [ -e $FFMPEG_FILE ] || { echo "$FFMPEG_FILE does not exist. Exiting..."; exit 1; }
  tar -zxf $FFMPEG_FILE
  rm $FFMPEG_FILE
  popd
}

function buildLibVpx() {
  pushd $VPX_DIR

  VPX_AS=${TOOLCHAIN_PREFIX}/bin/llvm-as
  for ABI in $ANDROID_ABIS; do
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
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=i686-linux-android21-
      ;;
    x86_64)
      EXTRA_BUILD_FLAGS="--force-target=x86_64-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic --disable-neon --disable-neon-asm"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=x86_64-linux-android21-
      ;;
    *)
      echo "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    CC=${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang \
      CXX=${CC}++ \
      LD=${CC} \
      AR=${TOOLCHAIN_PREFIX}/bin/llvm-ar \
      AS=${VPX_AS} \
      STRIP=${TOOLCHAIN_PREFIX}/bin/llvm-strip \
      NM=${TOOLCHAIN_PREFIX}/bin/llvm-nm \
      ./configure \
      --prefix=$BUILD_DIR/external/$ABI \
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
    pushd $MBEDTLS_DIR

    for ABI in $ANDROID_ABIS; do

      CMAKE_BUILD_DIR=$MBEDTLS_DIR/mbedtls_build_${ABI}
      rm -rf ${CMAKE_BUILD_DIR}
      mkdir -p ${CMAKE_BUILD_DIR}
      cd ${CMAKE_BUILD_DIR}

      ${CMAKE_EXECUTABLE} .. \
       -DANDROID_PLATFORM=${ANDROID_PLATFORM} \
       -DANDROID_ABI=$ABI \
       -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
       -DCMAKE_INSTALL_PREFIX=$BUILD_DIR/external/$ABI \
       -DENABLE_TESTING=0

      make -j$JOBS
      make install

    done
    popd
}

function buildDav1d() {
    pushd $DAV1D_DIR

    for ABI in $ANDROID_ABIS; do
      CPU_FAMILY=
      case $ABI in
        armeabi-v7a)
          TARGET_TRIPLE_MACHINE_ARCH=arm
          TOOLCHAIN=armv7a-linux-androideabi21-
          ;;
        arm64-v8a)
          TARGET_TRIPLE_MACHINE_ARCH=aarch64
          TOOLCHAIN=aarch64-linux-android21-
          ;;
        x86)
          TARGET_TRIPLE_MACHINE_ARCH=i686
          TOOLCHAIN=i686-linux-android21-
          CPU_FAMILY=x86
          ;;
        x86_64)
          TARGET_TRIPLE_MACHINE_ARCH=x86_64
          TOOLCHAIN=x86_64-linux-android21-
          ;;
      esac

      CROSS_PREFIX_WITH_PATH=${TOOLCHAIN_PREFIX}/bin/llvm-

      [ -z "${CPU_FAMILY}" ] && CPU_FAMILY=${TARGET_TRIPLE_MACHINE_ARCH}

      CROSS_FILE_NAME=crossfile-${ABI}.meson

      echo "
      [binaries]
      c = '${TOOLCHAIN_PREFIX}/bin/${TOOLCHAIN}clang'
      ar = '${CROSS_PREFIX_WITH_PATH}ar'
      strip = '${CROSS_PREFIX_WITH_PATH}strip'
      nasm = '${NASM_EXECUTABLE}'
      pkg-config = '$(which pkg-config)'

      [properties]
      needs_exe_wrapper = true
      sys_root = '${TOOLCHAIN_PREFIX}/sysroot'

      [host_machine]
      system = 'linux'
      cpu_family = '${CPU_FAMILY}'
      cpu = '${TARGET_TRIPLE_MACHINE_ARCH}'
      endian = 'little'

      [built-in options]
      prefix = '$BUILD_DIR/external/$ABI'" > "${CROSS_FILE_NAME}"

      BUILD_DIRECTORY=build/${ABI}

      rm -rf ${BUILD_DIRECTORY}

      ${MESON_EXECUTABLE} setup . ${BUILD_DIRECTORY} \
        --cross-file ${CROSS_FILE_NAME} \
        --default-library=static \
        -Denable_asm=true \
        -Denable_tools=false \
        -Denable_tests=false \
        -Denable_examples=false \
        -Dtestdata_tests=false

      pushd ${BUILD_DIRECTORY}

      ${NINJA_EXECUTABLE} -j$JOBS
      ${NINJA_EXECUTABLE} install

      popd

    done
    popd
}

function buildFfmpeg() {
  pushd $FFMPEG_DIR
  EXTRA_BUILD_CONFIGURATION_FLAGS=""
  COMMON_OPTIONS=""

  # Add enabled decoders to FFmpeg build configuration
  for decoder in $ENABLED_DECODERS; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
  done

  # Build FFmpeg for each architecture and platform
  for ABI in $ANDROID_ABIS; do

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
      --prefix=$BUILD_DIR/$ABI \
      --enable-cross-compile \
      --arch=$ARCH \
      --cpu=$CPU \
      --cross-prefix="${TOOLCHAIN_PREFIX}/bin/$TOOLCHAIN" \
      --nm="${TOOLCHAIN_PREFIX}/bin/llvm-nm" \
      --ar="${TOOLCHAIN_PREFIX}/bin/llvm-ar" \
      --ranlib="${TOOLCHAIN_PREFIX}/bin/llvm-ranlib" \
      --strip="${TOOLCHAIN_PREFIX}/bin/llvm-strip" \
      --extra-cflags="-O3 -fPIC $DEP_CFLAGS" \
      --extra-ldflags="$DEP_LD_FLAGS" \
      --pkg-config="$(which pkg-config)" \
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

if [[ ! -d "$OUTPUT_DIR" && ! -d "$BUILD_DIR" ]]; then
  # Download MbedTLS source code if it doesn't exist
  if [[ ! -d "$MBEDTLS_DIR" ]]; then
    downloadMbedTLS
  fi

  # Download Vpx source code if it doesn't exist
  if [[ ! -d "$VPX_DIR" ]]; then
    downloadLibVpx
  fi

  # Download Dav1d source code if it doesn't exist
  if [[ ! -d "$DAV1D_DIR" ]]; then
    downloadDav1d
  fi

  # Download Ffmpeg source code if it doesn't exist
  if [[ ! -d "$FFMPEG_DIR" ]]; then
    downloadFfmpeg
  fi

  # Building library
  buildMbedTLS
  buildLibVpx
  buildDav1d
  buildFfmpeg
fi

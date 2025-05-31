#!/bin/bash
set -e

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

# Flags for controlling build steps
CLEAN_BUILD=false
SELECTED_ABIS=""
BUILD_MBEDTLS=true
BUILD_LIBVPX=true
BUILD_FFMPEG=true

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

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
log_info() {
  echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
  echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
  echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
  echo -e "${RED}[ERROR]${NC} $1"
}

# Process selected ABIs if specified
if [[ -n "$SELECTED_ABIS" ]]; then
  # Replace commas with spaces
  ANDROID_ABIS=$(echo "$SELECTED_ABIS" | tr ',' ' ')
  
  # Validate ABIs
  for ABI in $ANDROID_ABIS; do
    case "$ABI" in
      x86|x86_64|armeabi-v7a|arm64-v8a) ;;
      *)
        log_error "Unsupported ABI: $ABI"
        log_info "Supported ABIs: x86, x86_64, armeabi-v7a, arm64-v8a"
        exit 1
        ;;
    esac
  done
fi

# Check for required environment variables
if [[ -z "$ANDROID_NDK_HOME" ]]; then
  log_error "ANDROID_NDK_HOME environment variable is not set"
  log_info "Please set ANDROID_NDK_HOME to the path of your Android NDK installation"
  exit 1
fi

if [[ -z "$ANDROID_SDK_HOME" ]]; then
  log_error "ANDROID_SDK_HOME environment variable is not set"
  log_info "Please set ANDROID_SDK_HOME to the path of your Android SDK installation"
  exit 1
fi

# Check for required tools
command -v curl >/dev/null 2>&1 || { log_error "curl is required but not installed. Aborting."; exit 1; }
command -v yasm >/dev/null 2>&1 || { log_warning "yasm is not installed. Aborting."; exit 1; }
command -v pkg-config >/dev/null 2>&1 || { log_warning "pkg-config is not installed. This might cause issues."; }

# Build tools
TOOLCHAIN_PREFIX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_PLATFORM}"
CMAKE_EXECUTABLE=${ANDROID_SDK_HOME}/cmake/3.22.1/bin/cmake

# Check if toolchain and cmake exist
if [[ ! -d "$TOOLCHAIN_PREFIX" ]]; then
  log_error "Android NDK toolchain not found at $TOOLCHAIN_PREFIX"
  exit 1
fi

if [[ ! -f "$CMAKE_EXECUTABLE" ]]; then
  log_error "CMake not found at $CMAKE_EXECUTABLE"
  log_info "Make sure you have CMake 3.22.1 installed via Android SDK Manager"
  exit 1
fi

# Clean build if requested
if [[ "$CLEAN_BUILD" = true ]]; then
  log_info "Cleaning previous build..."
  rm -rf "$BUILD_DIR" "$OUTPUT_DIR"
fi

# Create necessary directories
mkdir -p "$SOURCES_DIR"
mkdir -p "$BUILD_DIR"
mkdir -p "$OUTPUT_DIR"

# Set up trap to handle interruptions
cleanup() {
  log_warning "Build interrupted. Cleaning up..."
  exit 1
}

trap cleanup INT TERM

# Function to download LibVpx
function downloadLibVpx() {
  pushd $SOURCES_DIR
  log_info "Downloading Vpx source code of version $VPX_VERSION..."
  VPX_FILE=libvpx-$VPX_VERSION.tar.gz
  curl -L "https://github.com/webmproject/libvpx/archive/refs/tags/v${VPX_VERSION}.tar.gz" -o $VPX_FILE
  if [[ ! -f $VPX_FILE ]]; then
    log_error "$VPX_FILE download failed. Exiting..."
    exit 1
  fi
  tar -zxf $VPX_FILE
  rm $VPX_FILE
  log_success "LibVpx source code downloaded and extracted successfully"
  popd
}

# Function to download MbedTLS
function downloadMbedTLS() {
  pushd $SOURCES_DIR
  log_info "Downloading mbedtls source code of version $MBEDTLS_VERSION..."
  MBEDTLS_FILE=mbedtls-$MBEDTLS_VERSION.tar.gz
  curl -L "https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v${MBEDTLS_VERSION}.tar.gz" -o $MBEDTLS_FILE
  if [[ ! -f $MBEDTLS_FILE ]]; then
    log_error "$MBEDTLS_FILE download failed. Exiting..."
    exit 1
  fi
  tar -zxf $MBEDTLS_FILE
  rm $MBEDTLS_FILE
  log_success "MbedTLS source code downloaded and extracted successfully"
  popd
}

# Function to download FFmpeg
function downloadFfmpeg() {
  pushd $SOURCES_DIR
  log_info "Downloading FFmpeg source code of version $FFMPEG_VERSION..."
  FFMPEG_FILE=ffmpeg-$FFMPEG_VERSION.tar.gz
  curl -L "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.gz" -o $FFMPEG_FILE
  if [[ ! -f $FFMPEG_FILE ]]; then
    log_error "$FFMPEG_FILE download failed. Exiting..."
    exit 1
  fi
  tar -zxf $FFMPEG_FILE
  rm $FFMPEG_FILE
  log_success "FFmpeg source code downloaded and extracted successfully"
  popd
}

# Function to build LibVpx
function buildLibVpx() {
  log_info "Building LibVpx for all ABIs..."
  pushd $VPX_DIR

  for ABI in $ANDROID_ABIS; do
    log_info "Building LibVpx for $ABI..."
    
    # Set up environment variables
    VPX_AS=${TOOLCHAIN_PREFIX}/bin/llvm-as
    case $ABI in
    armeabi-v7a)
      EXTRA_BUILD_FLAGS="--force-target=armv7-android-gcc --disable-neon"
      TOOLCHAIN=armv7a-linux-androideabi$ANDROID_PLATFORM-
      ;;
    arm64-v8a)
      EXTRA_BUILD_FLAGS="--force-target=armv8-android-gcc"
      TOOLCHAIN=aarch64-linux-android$ANDROID_PLATFORM-
      ;;
    x86)
      EXTRA_BUILD_FLAGS="--force-target=x86-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=i686-linux-android$ANDROID_PLATFORM-
      ;;
    x86_64)
      EXTRA_BUILD_FLAGS="--force-target=x86_64-android-gcc --disable-sse2 --disable-sse3 --disable-ssse3 --disable-sse4_1 --disable-avx --disable-avx2 --enable-pic --disable-neon --disable-neon-asm"
      VPX_AS=${TOOLCHAIN_PREFIX}/bin/yasm
      TOOLCHAIN=x86_64-linux-android$ANDROID_PLATFORM-
      ;;
    *)
      log_error "Unsupported architecture: $ABI"
      exit 1
      ;;
    esac

    # Check if yasm is available for x86/x86_64
    if [[ "$ABI" == "x86" || "$ABI" == "x86_64" ]]; then
      if [[ ! -f "${TOOLCHAIN_PREFIX}/bin/yasm" ]]; then
        log_warning "yasm not found at ${TOOLCHAIN_PREFIX}/bin/yasm"
        log_info "Using llvm-as instead, but this may cause issues"
        VPX_AS=${TOOLCHAIN_PREFIX}/bin/llvm-as
      fi
    fi

    # Configure and build
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

    if [[ $? -ne 0 ]]; then
      log_error "LibVpx configuration failed for $ABI"
      exit 1
    fi

    make clean
    make -j$JOBS
    if [[ $? -ne 0 ]]; then
      log_error "LibVpx build failed for $ABI"
      exit 1
    fi
    
    make install
    if [[ $? -ne 0 ]]; then
      log_error "LibVpx installation failed for $ABI"
      exit 1
    fi
    
    log_success "LibVpx built successfully for $ABI"
  done
  
  popd
}

# Function to build MbedTLS
function buildMbedTLS() {
  log_info "Building MbedTLS for all ABIs..."
  pushd $MBEDTLS_DIR

  for ABI in $ANDROID_ABIS; do
    log_info "Building MbedTLS for $ABI..."
    
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

    if [[ $? -ne 0 ]]; then
      log_error "MbedTLS configuration failed for $ABI"
      exit 1
    fi

    make -j$JOBS
    if [[ $? -ne 0 ]]; then
      log_error "MbedTLS build failed for $ABI"
      exit 1
    fi
    
    make install
    if [[ $? -ne 0 ]]; then
      log_error "MbedTLS installation failed for $ABI"
      exit 1
    fi
    
    log_success "MbedTLS built successfully for $ABI"
  done
  
  popd
}

# Function to build FFmpeg
function buildFfmpeg() {
  log_info "Building FFmpeg for all ABIs..."
  pushd $FFMPEG_DIR
  
  EXTRA_BUILD_CONFIGURATION_FLAGS=""
  COMMON_OPTIONS=""

  # Add enabled decoders to FFmpeg build configuration
  for decoder in $ENABLED_DECODERS; do
    COMMON_OPTIONS="${COMMON_OPTIONS} --enable-decoder=${decoder}"
  done

  # Build FFmpeg for each architecture and platform
  for ABI in $ANDROID_ABIS; do
    log_info "Building FFmpeg for $ABI..."
    
    # Set up environment variables
    case $ABI in
    armeabi-v7a)
      TOOLCHAIN=armv7a-linux-androideabi$ANDROID_PLATFORM-
      CPU=armv7-a
      ARCH=arm
      EXTRA_BUILD_CONFIGURATION_FLAGS=""
      ;;
    arm64-v8a)
      TOOLCHAIN=aarch64-linux-android$ANDROID_PLATFORM-
      CPU=armv8-a
      ARCH=aarch64
      EXTRA_BUILD_CONFIGURATION_FLAGS=""
      ;;
    x86)
      TOOLCHAIN=i686-linux-android$ANDROID_PLATFORM-
      CPU=i686
      ARCH=i686
      EXTRA_BUILD_CONFIGURATION_FLAGS="--disable-asm"
      ;;
    x86_64)
      TOOLCHAIN=x86_64-linux-android$ANDROID_PLATFORM-
      CPU=x86_64
      ARCH=x86_64
      EXTRA_BUILD_CONFIGURATION_FLAGS=""
      ;;
    *)
      log_error "Unsupported architecture: $ABI"
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
      --enable-protocol=file,http,https,mmsh,mmst,pipe,rtmp,rtmps,rtmpt,rtmpts,rtp,tls \
      --enable-version3 \
      --enable-mbedtls \
      --extra-ldexeflags=-pie \
      --disable-debug \
      ${EXTRA_BUILD_CONFIGURATION_FLAGS} \
      ${COMMON_OPTIONS}

    if [[ $? -ne 0 ]]; then
      log_error "FFmpeg configuration failed for $ABI"
      exit 1
    fi

    # Build FFmpeg
    log_info "Building FFmpeg for $ARCH..."
    make clean
    make -j$JOBS
    if [[ $? -ne 0 ]]; then
      log_error "FFmpeg build failed for $ABI"
      exit 1
    fi
    
    make install
    if [[ $? -ne 0 ]]; then
      log_error "FFmpeg installation failed for $ABI"
      exit 1
    fi

    # Copy built libraries and headers to output directory
    OUTPUT_LIB=${OUTPUT_DIR}/lib/${ABI}
    mkdir -p "${OUTPUT_LIB}"
    cp "${BUILD_DIR}"/"${ABI}"/lib/*.so "${OUTPUT_LIB}"

    OUTPUT_HEADERS=${OUTPUT_DIR}/include/${ABI}
    mkdir -p "${OUTPUT_HEADERS}"
    cp -r "${BUILD_DIR}"/"${ABI}"/include/* "${OUTPUT_HEADERS}"
    
    log_success "FFmpeg built successfully for $ABI"
  done
  
  popd
}

# Main execution flow
log_info "Starting build process with $JOBS parallel jobs"

# Download source code if needed
if [[ ! -d "$MBEDTLS_DIR" ]]; then
  downloadMbedTLS
fi

if [[ ! -d "$VPX_DIR" ]]; then
  downloadLibVpx
fi

if [[ ! -d "$FFMPEG_DIR" ]]; then
  downloadFfmpeg
fi

# Build libraries
if [[ "$BUILD_MBEDTLS" = true ]]; then
  buildMbedTLS
else
  log_info "Skipping MbedTLS build as requested"
fi

if [[ "$BUILD_LIBVPX" = true ]]; then
  buildLibVpx
else
  log_info "Skipping LibVpx build as requested"
fi

if [[ "$BUILD_FFMPEG" = true ]]; then
  buildFfmpeg
else
  log_info "Skipping FFmpeg build as requested"
fi

log_success "Build completed successfully!"
log_info "Libraries are available in: $OUTPUT_DIR/lib"
log_info "Headers are available in: $OUTPUT_DIR/include"
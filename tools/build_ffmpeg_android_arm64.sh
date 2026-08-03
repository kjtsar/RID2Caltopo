#!/usr/bin/env bash
set -euo pipefail

# Build FFmpeg shared libs for Android arm64-v8a and install into this app.
# Usage:
#   tools/build_ffmpeg_android_arm64.sh /absolute/path/to/ffmpeg-source
# Optional env:
#   NDK_ROOT=/Users/<you>/Library/Android/sdk/ndk/29.0.14206865
#   API=29

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 /absolute/path/to/ffmpeg-source"
  exit 1
fi

FFMPEG_SRC="$1"
if [[ ! -d "$FFMPEG_SRC" ]]; then
  echo "FFmpeg source dir not found: $FFMPEG_SRC"
  exit 1
fi
if [[ ! -x "$FFMPEG_SRC/configure" ]]; then
  echo "Missing configure script in: $FFMPEG_SRC"
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
"$SCRIPT_DIR/verify_ffmpeg_source.sh" "$FFMPEG_SRC"

API="${API:-29}"
NDK_ROOT="${NDK_ROOT:-}"
if [[ -z "$NDK_ROOT" ]]; then
  if [[ -d "$HOME/Library/Android/sdk/ndk/29.0.14206865" ]]; then
    NDK_ROOT="$HOME/Library/Android/sdk/ndk/29.0.14206865"
  elif [[ -d "$HOME/Library/Android/sdk/ndk/27.0.12077973" ]]; then
    NDK_ROOT="$HOME/Library/Android/sdk/ndk/27.0.12077973"
  else
    echo "Could not auto-detect Android NDK. Set NDK_ROOT env var."
    exit 1
  fi
fi

TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "NDK toolchain path not found: $TOOLCHAIN"
  exit 1
fi

TARGET="aarch64-linux-android"
CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
SYSROOT="$TOOLCHAIN/sysroot"

if [[ ! -x "$CC" ]]; then
  echo "Compiler not found: $CC"
  exit 1
fi

BUILD_DIR="$REPO_ROOT/.build/ffmpeg-android-arm64"
PREFIX="$BUILD_DIR/prefix"

INSTALL_LIB_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
INSTALL_INC_DIR="$REPO_ROOT/app/src/main/cpp/ffmpeg/include"

mkdir -p "$BUILD_DIR" "$INSTALL_LIB_DIR" "$INSTALL_INC_DIR"

pushd "$FFMPEG_SRC" >/dev/null

# Clean previous configure/build output in source tree.
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="$PREFIX" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --sysroot="$SYSROOT" \
  --pkg-config=false \
  --enable-shared \
  --disable-static \
  --disable-programs \
  --disable-doc \
  --disable-avdevice \
  --disable-postproc \
  --disable-swresample \
  --enable-avformat \
  --enable-avcodec \
  --enable-avutil \
  --enable-swscale \
  --enable-network \
  --enable-protocol=rtmp,rtmps,rtsp,tcp,udp,http,https,file \
  --enable-demuxer=flv,rtsp,mpegts,mov,matroska,h264,hevc,aac \
  --enable-parser=h264,hevc,aac \
  --enable-decoder=h264,hevc,aac,pcm_alaw,pcm_mulaw \
  --enable-bsfs \
  --disable-debug

make -j"$(sysctl -n hw.ncpu)"
make install

popd >/dev/null

# Install the shared libraries expected by CMake detection.
cp -f "$PREFIX/lib/libavformat"*.so "$INSTALL_LIB_DIR/"
cp -f "$PREFIX/lib/libavcodec"*.so "$INSTALL_LIB_DIR/"
cp -f "$PREFIX/lib/libavutil"*.so "$INSTALL_LIB_DIR/"

# Optional runtime helpers if available.
if compgen -G "$PREFIX/lib/libswscale*.so" >/dev/null; then
  cp -f "$PREFIX/lib/libswscale"*.so "$INSTALL_LIB_DIR/"
fi
if compgen -G "$PREFIX/lib/libswresample*.so" >/dev/null; then
  cp -f "$PREFIX/lib/libswresample"*.so "$INSTALL_LIB_DIR/"
fi

# Install headers into the include path expected by CMake.
rm -rf "$INSTALL_INC_DIR"
mkdir -p "$INSTALL_INC_DIR"
cp -R "$PREFIX/include"/* "$INSTALL_INC_DIR/"

echo ""
echo "FFmpeg Android arm64 build complete."
echo "Installed libs to: $INSTALL_LIB_DIR"
echo "Installed headers to: $INSTALL_INC_DIR"
echo "Next: run ./gradlew :app:externalNativeBuildDebug"

#!/bin/sh
set -x
echo "$0 tracing enabled"
cd ~/Projects/mediamtx-1.8.2

export ANDROID_NDK=$HOME/Library/Android/Sdk/ndk/29.0.14206865
export CC=$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang
export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=1
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"

go build -o mediamtx

# readelf missing
# readelf -l libmediamtx.so | grep LOAD

cp -p mediamtx ~/Projects/RID2Caltopo/app/src/main/assets

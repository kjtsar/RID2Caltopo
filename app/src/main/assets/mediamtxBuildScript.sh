#!/bin/sh
#
# Note to future self.  After cloning and pulling a branch, remember to run
# the following once before running this script:
#   git clean -fdx
#   go clean -cache -modcache
#   go generate ./...
# then you're good to run this script: ./mediamtxBuildScript.sh
#
set -ex
echo "$0 tracing enabled"
echo `pwd`
export ANDROID_NDK=$HOME/Library/Android/Sdk/ndk/29.0.14206865
export CC=$ANDROID_NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android21-clang
export GOOS=android
export GOARCH=arm64
export CGO_ENABLED=1 ;# 1 for debug, 0 for production
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384"

go build -o mediamtx

# readelf missing
# readelf -l libmediamtx.so | grep LOAD
ASSETS_DIR=~/Projects/RID2Caltopo/app/src/main/assets
cp -p mediamtx $ASSETS_DIR
cp -p $0 $ASSETS_DIR

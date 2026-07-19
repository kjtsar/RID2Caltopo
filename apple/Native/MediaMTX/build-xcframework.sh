#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$SCRIPT_DIR/../../Build/MediaMTX"
OUTPUT="$BUILD_DIR/MediaMTXMobile.xcframework"

"$SCRIPT_DIR/build-ios-device.sh"
"$SCRIPT_DIR/build-ios-simulator.sh"

for VARIANT in device simulator; do
    INCLUDE_DIR="$BUILD_DIR/$VARIANT/include"
    mkdir -p "$INCLUDE_DIR"
    cp "$BUILD_DIR/$VARIANT/libmediamtx_mobile.h" "$INCLUDE_DIR/libmediamtx_mobile.h"
    cp "$SCRIPT_DIR/module.modulemap" "$INCLUDE_DIR/module.modulemap"
done

rm -rf "$OUTPUT"
xcodebuild -create-xcframework \
    -library "$BUILD_DIR/device/libmediamtx_mobile.a" \
    -headers "$BUILD_DIR/device/include" \
    -library "$BUILD_DIR/simulator/libmediamtx_mobile.a" \
    -headers "$BUILD_DIR/simulator/include" \
    -output "$OUTPUT"

echo "Built $OUTPUT"

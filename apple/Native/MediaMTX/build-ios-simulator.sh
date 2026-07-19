#!/bin/bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
R2C_APPLE_SDK=iphonesimulator exec "$SCRIPT_DIR/build-ios-device.sh" "$@"

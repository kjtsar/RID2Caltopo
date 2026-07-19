#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
apple_dir="${script_dir:h}"
project="$apple_dir/RID2CaltopoApple.xcodeproj"
scheme="RID2CaltopoApple"
bundle_id="${R2C_APP_BUNDLE_ID:-org.ncssar.RID2CaltopoApple}"
output_dir="${1:-$apple_dir/Build/AppStoreScreenshots}"
derived_data="$apple_dir/Build/AppStoreScreenshotDerivedData"
iphone_name="${R2C_SCREENSHOT_IPHONE:-iPhone 17 Pro Max}"
ipad_name="${R2C_SCREENSHOT_IPAD:-iPad Pro 13-inch (M5)}"

device_id() {
    local name="$1"
    xcrun simctl list devices available -j | python3 -c '
import json, sys
name = sys.argv[1]
data = json.load(sys.stdin)
matches = [device["udid"] for devices in data["devices"].values() for device in devices if device["name"] == name and device.get("isAvailable", True)]
if len(matches) != 1:
    raise SystemExit(f"expected one available Simulator named {name!r}, found {len(matches)}")
print(matches[0])
' "$name"
}

iphone_id="${R2C_SCREENSHOT_IPHONE_UDID:-$(device_id "$iphone_name")}"
ipad_id="${R2C_SCREENSHOT_IPAD_UDID:-$(device_id "$ipad_name")}"
mkdir -p "$output_dir"

echo "Building Release app for arm64 Simulator screenshots"
xcodebuild -quiet \
    -project "$project" \
    -scheme "$scheme" \
    -configuration Release \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$derived_data" \
    ARCHS=arm64 \
    ONLY_ACTIVE_ARCH=YES \
    CODE_SIGNING_ALLOWED=NO \
    -jobs 1 \
    build

app="$derived_data/Build/Products/Release-iphonesimulator/RID2CaltopoApple.app"
[[ -d "$app" ]] || { echo "Missing built application: $app" >&2; exit 1; }

booted_here=()
cleanup() {
    for udid in "${booted_here[@]}"; do
        xcrun simctl shutdown "$udid" >/dev/null 2>&1 || true
    done
}
trap cleanup EXIT

capture_device() {
    local udid="$1"
    local label="$2"
    local expected_width="$3"
    local expected_height="$4"

    local state
    state="$(xcrun simctl list devices | rg -F "$udid" | sed -E 's/^.*\((Booted|Shutdown)\).*$/\1/' | head -1)"
    if [[ "$state" != "Booted" ]]; then
        xcrun simctl boot "$udid"
        booted_here+=("$udid")
    fi
    xcrun simctl bootstatus "$udid" -b
    xcrun simctl uninstall "$udid" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl install "$udid" "$app"
    xcrun simctl status_bar "$udid" override \
        --time '9:41' --batteryState charged --batteryLevel 100 \
        --wifiBars 3 --cellularBars 4 >/dev/null 2>&1 || true

    local names=("01-nearby-aircraft" "02-live-map" "03-status")
    local waits=(6 6 10)
    local launches=(
        "--demo-rid --no-location --manual-radios"
        "--demo-rid --show-map --no-location --manual-radios"
        "--demo-rid --show-status --no-location --manual-radios"
    )
    local index
    for index in {1..3}; do
        local destination="$output_dir/$label-${names[$index]}.png"
        local arguments=(${(z)launches[$index]})
        xcrun simctl launch --terminate-running-process "$udid" "$bundle_id" "${arguments[@]}" >/dev/null
        sleep "${waits[$index]}"
        xcrun simctl io "$udid" screenshot --type=png "$destination"
        local dimensions
        dimensions="$(sips -g pixelWidth -g pixelHeight "$destination" | awk '/pixelWidth/{w=$2} /pixelHeight/{h=$2} END{print w "x" h}')"
        [[ "$dimensions" == "${expected_width}x${expected_height}" ]] || {
            echo "Unexpected $label screenshot size $dimensions; expected ${expected_width}x${expected_height}" >&2
            exit 1
        }
        echo "  $destination ($dimensions)"
    done

    xcrun simctl terminate "$udid" "$bundle_id" >/dev/null 2>&1 || true
    xcrun simctl status_bar "$udid" clear >/dev/null 2>&1 || true
}

echo "Capturing $iphone_name"
capture_device "$iphone_id" iphone-6.9 1320 2868
echo "Capturing $ipad_name"
capture_device "$ipad_id" ipad-13 2064 2752

echo "App Store screenshots captured in $output_dir"

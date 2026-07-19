#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
repo_root="${script_dir:h}"
project="$script_dir/RID2CaltopoApple.xcodeproj"
scheme="RID2CaltopoApple"
team="${R2C_DEVELOPMENT_TEAM:-}"
bundle_id="${R2C_APP_BUNDLE_ID:-org.ncssar.RID2CaltopoApple}"
build_number="${R2C_BUILD_NUMBER:-$(date -u +%Y%m%d%H%M)}"
marketing_version="${R2C_MARKETING_VERSION:-1.0}"
archive_path=""
export_path=""
upload=false
internal_only=false
preflight=false
skip_release_check=false

usage() {
    cat <<'USAGE'
usage: apple/archive-for-testflight.sh --team TEAM_ID [options]

Options:
  --team TEAM_ID           Apple Developer team (or R2C_DEVELOPMENT_TEAM)
  --bundle-id BUNDLE_ID    App Store Connect bundle ID
  --build-number NUMBER    Unique CFBundleVersion (default: UTC timestamp)
  --marketing-version VER App Store CFBundleShortVersionString (default: 1.0)
  --archive-path PATH      Verified unsigned xcarchive destination
  --export-path PATH       Locally exported IPA directory
  --internal-only          Restrict an uploaded build to internal TestFlight
  --upload                 Upload after archive verification
  --preflight              Print configuration/signing readiness without building
  --skip-release-check     Reuse a release gate that just passed on unchanged sources
  --help                   Show this help

The script first creates and verifies an unsigned archive, then asks Xcode to
export a locally signed App Store IPA. It never stores Apple credentials and
uploads only when --upload is explicitly supplied.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --team) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; team="$2"; shift 2 ;;
        --bundle-id) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; bundle_id="$2"; shift 2 ;;
        --build-number) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; build_number="$2"; shift 2 ;;
        --marketing-version) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; marketing_version="$2"; shift 2 ;;
        --archive-path) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; archive_path="$2"; shift 2 ;;
        --export-path) [[ $# -ge 2 ]] || { usage >&2; exit 2; }; export_path="$2"; shift 2 ;;
        --internal-only) internal_only=true; shift ;;
        --upload) upload=true; shift ;;
        --preflight) preflight=true; shift ;;
        --skip-release-check) skip_release_check=true; shift ;;
        --help|-h) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

[[ -n "$team" ]] || { echo "Missing Apple Developer team. Pass --team or set R2C_DEVELOPMENT_TEAM." >&2; exit 2; }
print -r -- "$team" | grep -Eq '^[A-Z0-9]{10}$' || { echo "Invalid Apple Developer team: $team" >&2; exit 2; }
print -r -- "$bundle_id" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$' || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
[[ "$bundle_id" == *.* ]] || { echo "Invalid bundle ID: $bundle_id" >&2; exit 2; }
if [[ "$build_number" != <-> && "$build_number" != <->.<-> && "$build_number" != <->.<->.<-> ]]; then
    echo "Invalid CFBundleVersion: $build_number" >&2
    exit 2
fi
print -r -- "$marketing_version" | grep -Eq '^[0-9]+(\.[0-9]+){1,2}$' || { echo "Invalid CFBundleShortVersionString: $marketing_version" >&2; exit 2; }
if $internal_only && ! $upload; then
    echo "--internal-only requires --upload" >&2
    exit 2
fi

archive_path="${archive_path:-$script_dir/Build/RID2CaltopoApple-unsigned-$build_number.xcarchive}"
export_path="${export_path:-$script_dir/Build/TestFlight-$build_number}"

echo "RID2Caltopo TestFlight configuration"
echo "  team: $team"
echo "  bundle: $bundle_id"
echo "  build: $build_number"
echo "  version: $marketing_version"
echo "  archive: $archive_path"
echo "  upload: $upload"
if $upload; then echo "  internal only: $internal_only"; fi

identity_summary="$(security find-identity -v -p codesigning)"
if print -r -- "$identity_summary" | grep -Eq '[1-9][0-9]* valid identities found'; then
    print -r -- "$identity_summary"
else
    echo "  signing identity: none currently installed"
    echo "  Xcode may create a managed distribution identity during export when a paid account is configured."
fi

xcodebuild -quiet \
    -project "$project" \
    -scheme "$scheme" \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -showBuildSettings \
    DEVELOPMENT_TEAM="$team" \
    PRODUCT_BUNDLE_IDENTIFIER="$bundle_id" \
    CURRENT_PROJECT_VERSION="$build_number" \
    MARKETING_VERSION="$marketing_version" >/dev/null

if $preflight; then
    echo "Preflight completed without changing Apple or App Store Connect state."
    exit 0
fi

if ! $skip_release_check; then
    [[ ! -e "$archive_path" ]] || { echo "Archive already exists: $archive_path" >&2; exit 1; }
    "$script_dir/release-check.sh" \
        --archive-path "$archive_path" \
        --bundle-id "$bundle_id" \
        --build-number "$build_number" \
        --marketing-version "$marketing_version"
else
    echo "Reusing the caller-confirmed Apple release gate for unchanged sources only."
    "$script_dir/verify-unsigned-archive.sh" "$archive_path" "$bundle_id" "$build_number" "$marketing_version"
fi

[[ ! -e "$export_path" ]] || { echo "Export path already exists: $export_path" >&2; exit 1; }

options_dir="$(mktemp -d -t r2c-testflight-options)"
trap 'rm -rf "$options_dir"' EXIT
options_plist="$options_dir/ExportOptions.plist"
cp "$script_dir/TestFlightExportOptions.plist" "$options_plist"
/usr/libexec/PlistBuddy -c "Add :teamID string $team" "$options_plist"
/usr/libexec/PlistBuddy -c 'Set :destination export' "$options_plist"

xcodebuild -quiet \
    -exportArchive \
    -archivePath "$archive_path" \
    -exportPath "$export_path" \
    -exportOptionsPlist "$options_plist" \
    -allowProvisioningUpdates

ipas=("$export_path"/*.ipa(N))
[[ ${#ipas[@]} -eq 1 ]] || { echo "Expected one IPA in $export_path, found ${#ipas[@]}" >&2; exit 1; }
ipa="${ipas[1]}"
"$script_dir/verify-testflight-export.sh" "$ipa" "$team" "$bundle_id" "$build_number" "$marketing_version"

if ! $upload; then
    echo "Distribution-signed IPA is ready: $ipa"
    echo "Re-run with --upload only when the App Store Connect record and metadata are ready."
    exit 0
fi

upload_options="$options_dir/UploadOptions.plist"
cp "$script_dir/TestFlightExportOptions.plist" "$upload_options"
/usr/libexec/PlistBuddy -c "Add :teamID string $team" "$upload_options"
if $internal_only; then
    /usr/libexec/PlistBuddy -c 'Set :testFlightInternalTestingOnly true' "$upload_options"
fi

upload_result="${export_path}-upload"
[[ ! -e "$upload_result" ]] || { echo "Upload result path already exists: $upload_result" >&2; exit 1; }
xcodebuild -quiet \
    -exportArchive \
    -archivePath "$archive_path" \
    -exportPath "$upload_result" \
    -exportOptionsPlist "$upload_options" \
    -allowProvisioningUpdates

echo "Upload completed for build $build_number. Check App Store Connect processing status."

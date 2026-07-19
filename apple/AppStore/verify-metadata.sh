#!/bin/zsh
set -euo pipefail

script_dir="${0:A:h}"
locale_dir="$script_dir/metadata/en-US"

check_limit() {
    local file="$1"
    local maximum="$2"
    local label="$3"
    [[ -s "$file" ]] || { echo "Missing or empty $label: $file" >&2; exit 1; }
    local count
    count="$(python3 -c 'import pathlib, sys; print(len(pathlib.Path(sys.argv[1]).read_text().rstrip("\\n")))' "$file")"
    (( count <= maximum )) || { echo "$label is $count characters; maximum is $maximum" >&2; exit 1; }
    echo "  $label: $count/$maximum"
}

echo "App Store metadata"
check_limit "$locale_dir/name.txt" 30 "name"
check_limit "$locale_dir/subtitle.txt" 30 "subtitle"
check_limit "$locale_dir/promotional_text.txt" 170 "promotional text"
check_limit "$locale_dir/keywords.txt" 100 "keywords"
check_limit "$locale_dir/description.txt" 4000 "description"
check_limit "$locale_dir/whats_new.txt" 4000 "what's new"
[[ -s "$locale_dir/review_notes.txt" ]] || { echo "Missing review notes" >&2; exit 1; }

privacy="$script_dir/../../PrivacyPolicy.md"
[[ -s "$privacy" ]] || { echo "Missing public privacy policy source: $privacy" >&2; exit 1; }
rg -q '^Last updated: ' "$privacy"
rg -q 'does not contain advertising or analytics SDKs' "$privacy"
rg -q 'Apple Keychain' "$privacy"
rg -q 'tracker peer coordination' "$privacy"
rg -q 'diagnostic bundle' "$privacy"

python3 - "$script_dir/../App/PrivacyInfo.xcprivacy" <<'PY'
import plistlib
import sys

with open(sys.argv[1], "rb") as source:
    manifest = plistlib.load(source)

if manifest.get("NSPrivacyTracking") is not False:
    raise SystemExit("privacy manifest must declare tracking false")
if manifest.get("NSPrivacyTrackingDomains") != []:
    raise SystemExit("privacy manifest must not declare tracking domains")

expected = {
    "NSPrivacyCollectedDataTypeOtherDiagnosticData",
    "NSPrivacyCollectedDataTypePreciseLocation",
    "NSPrivacyCollectedDataTypeDeviceID",
    "NSPrivacyCollectedDataTypeOtherUserContent",
}
entries = manifest.get("NSPrivacyCollectedDataTypes", [])
actual = {entry.get("NSPrivacyCollectedDataType") for entry in entries}
if actual != expected:
    raise SystemExit(f"unexpected privacy data types: {sorted(actual)}")
for entry in entries:
    data_type = entry["NSPrivacyCollectedDataType"]
    if entry.get("NSPrivacyCollectedDataTypeLinked") is not True:
        raise SystemExit(f"{data_type} must be disclosed as linked")
    if entry.get("NSPrivacyCollectedDataTypeTracking") is not False:
        raise SystemExit(f"{data_type} must declare tracking false")
    if entry.get("NSPrivacyCollectedDataTypePurposes") != ["NSPrivacyCollectedDataTypePurposeAppFunctionality"]:
        raise SystemExit(f"{data_type} has unexpected purposes")
PY

echo "App Store metadata and public privacy-policy source verified."

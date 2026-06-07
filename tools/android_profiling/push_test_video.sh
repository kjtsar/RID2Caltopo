#!/usr/bin/env bash
set -euo pipefail

SERIAL=""
SRC="/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/PowerHouseTeam.mp4"
DEST_DIR="/sdcard/Download"

usage() {
  cat <<'EOF'
Usage: push_test_video.sh [options]

Pushes a captured-video file to the connected Android device so it can be opened
from the RID2Caltopo captured-video picker.

Options:
  --serial <serial>   adb device serial. Defaults to the only physical device.
  --src <path>        Source video path. Default: PowerHouseTeam.mp4 test asset.
  --dest-dir <path>   Device destination directory. Default: /sdcard/Download
  -h, --help          Show this help.
EOF
}

pick_default_serial() {
  local devices
  mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/{print $1}')
  if [[ "${#devices[@]}" -eq 1 ]]; then
    printf '%s\n' "${devices[0]}"
    return 0
  fi
  echo "Unable to auto-select a physical device. Pass --serial." >&2
  adb devices -l >&2 || true
  return 1
}

run_adb() {
  if [[ -n "$SERIAL" ]]; then
    adb -s "$SERIAL" "$@"
  else
    adb "$@"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:?missing serial}"
      shift 2
      ;;
    --src)
      SRC="${2:?missing source path}"
      shift 2
      ;;
    --dest-dir)
      DEST_DIR="${2:?missing destination dir}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "Source file not found: $SRC" >&2
  exit 1
fi

if [[ -z "$SERIAL" ]]; then
  SERIAL="$(pick_default_serial)"
fi

dest_path="${DEST_DIR}/$(basename "$SRC")"
run_adb push "$SRC" "$dest_path"
echo "Pushed to ${dest_path} on ${SERIAL}"

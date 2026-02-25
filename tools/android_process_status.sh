#!/usr/bin/env bash
set -euo pipefail

PKG="${1:-org.ncssar.rid2caltopo}"
FORCE_STOP="${2:-}"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found in PATH"
  exit 1
fi

echo "== Device =="
adb devices
echo

echo "== PID =="
if adb shell pidof "$PKG" >/dev/null 2>&1; then
  adb shell pidof "$PKG"
else
  echo "<none>"
fi
echo

echo "== Active Services =="
adb shell dumpsys activity services "$PKG"
echo

echo "== Process State (cached/empty/adj) =="
adb shell dumpsys activity processes | awk -v pkg="$PKG" '
  $0 ~ "ProcessRecord\\{.*" pkg {show=1; n=0}
  show {print; n++}
  show && n>=220 {exit}
' | grep -E "ProcessRecord|curProcState|setProcState|oom adj|cached|empty|lastTopTime|whenUnimportant|hasForegroundServices|executingServices|services|receivers|adjSeq|lruSeq" || true
echo

if [[ "$FORCE_STOP" == "--force-stop" ]]; then
  echo "== Force Stop =="
  adb shell am force-stop "$PKG"
  if adb shell pidof "$PKG" >/dev/null 2>&1; then
    echo "Process still present:"
    adb shell pidof "$PKG"
  else
    echo "Process removed."
  fi
fi


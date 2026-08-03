#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../../../.." && pwd)"

echo "Delegating to the pinned, patch-based MediaMTX build." >&2
exec "$repo_root/tools/build_mediamtx_android_arm64.sh" "$@"

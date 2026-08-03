#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
    echo "usage: $0 /absolute/path/to/ffmpeg-source" >&2
    exit 2
fi

source_dir="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=/dev/null
source "$repo_root/third_party/mediamtx/manifest.env"

[[ -x "$source_dir/configure" ]] || {
    echo "FFmpeg source not found: $source_dir" >&2
    exit 1
}
grep -q "FFMPEG_VERSION \"$FFMPEG_TAG\"" "$source_dir/libavutil/ffversion.h" || {
    echo "FFmpeg source is not $FFMPEG_TAG" >&2
    exit 1
}
git -C "$source_dir" rev-parse --is-inside-work-tree >/dev/null 2>&1 || {
    echo "FFmpeg source must be a verified Git checkout" >&2
    exit 1
}
[[ "$(git -C "$source_dir" rev-parse HEAD)" == "$FFMPEG_COMMIT" ]] || {
    echo "FFmpeg checkout is not pinned commit $FFMPEG_COMMIT" >&2
    exit 1
}
git -C "$source_dir" diff --quiet
git -C "$source_dir" diff --cached --quiet

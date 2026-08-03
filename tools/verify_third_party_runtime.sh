#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
media_binary="$repo_root/app/src/main/assets/mediamtx"
ffmpeg_dir="$repo_root/app/src/main/jniLibs/arm64-v8a"
ffmpeg_header="$repo_root/app/src/main/cpp/ffmpeg/include/libavutil/ffversion.h"

[[ -x "$media_binary" ]] || {
    echo "Missing MediaMTX runtime; run tools/build_mediamtx_android_arm64.sh" >&2
    exit 1
}
go version -m "$media_binary" | grep -q 'path[[:space:]]github.com/bluenviron/mediamtx'

for library in libavformat.so libavcodec.so libavutil.so libswscale.so; do
    [[ -f "$ffmpeg_dir/$library" ]] || {
        echo "Missing FFmpeg runtime: $ffmpeg_dir/$library" >&2
        exit 1
    }
done
grep -q 'FFMPEG_VERSION "n7.0"' "$ffmpeg_header"
strings "$ffmpeg_dir/libavutil.so" | grep -q 'FFmpeg version n7.0'

if git -C "$repo_root" ls-files --error-unmatch \
    app/src/main/assets/mediamtx \
    app/src/main/jniLibs/arm64-v8a/libavutil.so >/dev/null 2>&1; then
    echo "Generated media binaries must not be tracked by Git" >&2
    exit 1
fi

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# shellcheck source=/dev/null
source "$repo_root/third_party/mediamtx/manifest.env"

source_dir="$($script_dir/prepare_mediamtx_source.sh)"
ndk_root="${NDK_ROOT:-$HOME/Library/Android/sdk/ndk/$ANDROID_NDK_VERSION}"
toolchain="$ndk_root/toolchains/llvm/prebuilt/darwin-x86_64"
api="${API:-21}"
cc="$toolchain/bin/aarch64-linux-android${api}-clang"

[[ -x "$cc" ]] || {
    echo "Android NDK compiler not found: $cc" >&2
    exit 1
}
[[ "$(go version | awk '{print $3}')" == "$GO_VERSION" ]] || {
    echo "MediaMTX requires $GO_VERSION; found $(go version)" >&2
    exit 1
}

asset_dir="$repo_root/app/src/main/assets"
versioned_binary="$asset_dir/mediamtx-1.16.2-rid2caltopo"
mkdir -p "$asset_dir" "$repo_root/.build/go-cache" "$repo_root/.build/go-mod-cache"

(
    cd "$source_dir"
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    go generate ./...
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    GOOS=android \
    GOARCH=arm64 \
    CGO_ENABLED=1 \
    CC="$cc" \
    CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
    go build -trimpath -buildvcs=false -o "$versioned_binary" .
)

ln -sfn "$(basename "$versioned_binary")" "$asset_dir/mediamtx"
go version -m "$versioned_binary" | grep -q 'path[[:space:]]github.com/bluenviron/mediamtx'
shasum -a 256 "$versioned_binary"

#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"

# shellcheck source=/dev/null
source "$repo_root/third_party/mediamtx/manifest.env"

if [[ -n "${MEDIAMTX_SOURCE_DIR:-}" ]]; then
    source_dir="$MEDIAMTX_SOURCE_DIR"
    [[ -f "$source_dir/.rid2caltopo-patched-source" ]] || {
        echo "Refusing unverified MediaMTX source: $source_dir" >&2
        exit 1
    }
else
    source_dir="$($script_dir/prepare_mediamtx_source.sh)"
fi
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
versioned_binary="${OUTPUT:-$asset_dir/mediamtx-1.16.2-rid2caltopo}"
trimpath="${MEDIAMTX_TRIMPATH:-0}"
mkdir -p "$asset_dir" "$repo_root/.build/go-cache" "$repo_root/.build/go-mod-cache"

case "$trimpath" in
    0|1) ;;
    *)
        echo "MEDIAMTX_TRIMPATH must be 0 or 1" >&2
        exit 1
        ;;
esac

(
    cd "$source_dir"
    GOCACHE="$repo_root/.build/go-cache" \
    GOMODCACHE="$repo_root/.build/go-mod-cache" \
    go generate ./...
    if [[ "$trimpath" == "1" ]]; then
        GOCACHE="$repo_root/.build/go-cache" \
        GOMODCACHE="$repo_root/.build/go-mod-cache" \
        GOOS=android \
        GOARCH=arm64 \
        CGO_ENABLED=1 \
        CC="$cc" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -mod=vendor -trimpath -buildvcs=false -o "$versioned_binary" .
    else
        GOCACHE="$repo_root/.build/go-cache" \
        GOMODCACHE="$repo_root/.build/go-mod-cache" \
        GOOS=android \
        GOARCH=arm64 \
        CGO_ENABLED=1 \
        CC="$cc" \
        CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
        go build -mod=vendor -buildvcs=false -o "$versioned_binary" .
    fi
)

if [[ "$versioned_binary" == "$asset_dir/"* ]]; then
    ln -sfn "$(basename "$versioned_binary")" "$asset_dir/mediamtx"
fi
go version -m "$versioned_binary" | grep -q 'path[[:space:]]github.com/bluenviron/mediamtx'
binary_sha="$(shasum -a 256 "$versioned_binary" | awk '{print $1}')"
if [[ "$trimpath" == "1" && "$binary_sha" != "$REFERENCE_ANDROID_TRIMPATH_SHA256" ]]; then
    echo "Unexpected trimpath MediaMTX SHA-256: $binary_sha" >&2
    exit 1
fi
printf '%s  %s\n' "$binary_sha" "$versioned_binary"

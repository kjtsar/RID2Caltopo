#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: tools/sync_release_notes.sh VERSION" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(dirname -- "$script_dir")
version=$1

case "$version" in
    ''|*[!0-9.]*) echo "Invalid version: $version" >&2; exit 2 ;;
esac

canonical="$repo_root/release-notes/$version/whats_new.txt"
apple_metadata="$repo_root/apple/AppStore/metadata/en-US/whats_new.txt"

if [ ! -s "$canonical" ]; then
    echo "Missing canonical release notes: $canonical" >&2
    exit 1
fi

cp "$canonical" "$apple_metadata"
echo "Synchronized release notes for $version"

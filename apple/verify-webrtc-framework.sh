#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 APP_BUNDLE" >&2
    exit 2
fi

app=$1
app_binary="$app/RID2CaltopoApple"
framework="$app/Frameworks/WebRTC.framework"
framework_binary="$framework/WebRTC"
framework_info="$framework/Info.plist"

test -x "$app_binary" || { echo "Missing app binary: $app_binary" >&2; exit 1; }
test -x "$framework_binary" || { echo "Missing WebRTC framework binary: $framework_binary" >&2; exit 1; }
plutil -lint "$framework_info" >/dev/null
file "$framework_binary" | grep -q 'Mach-O.*arm64'
otool -L "$app_binary" | grep -q '@rpath/WebRTC.framework/WebRTC'

symbols=$(nm -gU "$framework_binary")
for symbol in \
    '_RTCInitializeSSL' \
    '_OBJC_CLASS_$_RTCPeerConnectionFactory' \
    '_OBJC_CLASS_$_RTCPeerConnection' \
    '_OBJC_CLASS_$_RTCVideoSource' \
    '_OBJC_CLASS_$_RTCVideoTrack'; do
    echo "$symbols" | grep -F -q "$symbol" || {
        echo "WebRTC framework is missing required symbol: $symbol" >&2
        exit 1
    }
done

echo "WebRTC framework verified: $framework"

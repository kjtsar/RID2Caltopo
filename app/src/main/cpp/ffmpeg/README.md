# FFmpeg Drop-In Layout

To enable the real FFmpeg decoder backend, place these files:

- Headers under:
  - `app/src/main/cpp/ffmpeg/include/`
- Shared libraries for arm64 under:
  - `app/src/main/jniLibs/arm64-v8a/libavformat.so`
  - `app/src/main/jniLibs/arm64-v8a/libavcodec.so`
  - `app/src/main/jniLibs/arm64-v8a/libavutil.so`

Optional but commonly needed for full decode/render pipelines:

- `libswscale.so`
- `libswresample.so`

FFmpeg is third-party software and is not covered by RID2Caltopo's Apache-2.0
license. Before distributing these libraries, retain the exact source and
configure options used to build them and comply with the applicable FFmpeg
license. Builds that enable GPL or nonfree components have different
redistribution consequences. See the repository's
[Third-Party Software Notices](../../../../../THIRD_PARTY_NOTICES.md) and
<https://ffmpeg.org/legal.html>.

Current status:

- If required libs are missing, `ffmpeg_bridge` builds in `stub` mode.
- Stub mode exercises session/surface/event plumbing only and does not decode video.

RID2Caltopo currently pins the unmodified official FFmpeg `n7.0` tag at
commit `083443d67cb159ce469e5d902346b8d0c2cd1c93`. Use
`tools/build_ffmpeg_android_arm64.sh` for Android or
`apple/Native/FFmpeg/build-xcframework.sh` for Apple. Both scripts verify the
source revision before building; generated headers and libraries are excluded
from Git.

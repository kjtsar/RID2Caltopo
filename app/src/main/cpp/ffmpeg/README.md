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

Current status:

- If required libs are missing, `ffmpeg_bridge` builds in `stub` mode.
- Stub mode exercises session/surface/event plumbing only and does not decode video.

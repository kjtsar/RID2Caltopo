# Connected-device RTMP lifecycle qualification

This harness forwards a host TCP port to the connected device's MediaMTX RTMP
port, repeatedly publishes a moving H.264/FLV test stream, and requires this
ordered chain in every cycle: MediaMTX publisher admission, a fresh app-side
FFmpeg render start, `decoder_opened`, and advancing `SurfaceTexture` frame
counts on one texture.

The device must have a debug build with the Streams qualification Activity hook.
The harness launches it with `OPEN_STREAMS_QUALIFICATION=true`. Between publisher
cycles it sends HOME and relaunches through that hook, exercising an app
background/foreground transition without force-stopping the app. The host needs
`adb` and an `ffmpeg` build with `libx264` on PATH.

```sh
python3 tools/stream_test/run_connected_stream_lifecycle_qualification.py \
  --serial DEVICE_SERIAL
```

Useful controls include `--cycles`, `--timeout`, `--restart-pause`,
`--activity-settle`, `--local-port`, `--designator`, and `--log-file`. Run
`--help` for defaults.
The complete logcat capture is written to a timestamped file in the system temp
directory unless `--log-file` is supplied.

Run the pure parser/qualification tests with:

```sh
python3 -m unittest discover -s tools/stream_test -p 'test_*.py'
```

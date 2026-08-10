# RID2Caltopo Android Runtime Tracing

This workflow captures real Android runtime behavior for the local-playback
path instead of relying only on the in-app anomaly timing summary. The setup is
aimed at `PowerHouseTeam.mp4`, but the same steps work for any captured video.

## What this adds

- Perfetto command-line capture via `adb shell perfetto`
- Focused logcat capture for `FfmpegProbeService`, `FfmpegBridge`, and
  `ffmpeg_bridge`
- Pre/post snapshots of process state, meminfo, gfxinfo, and thermal service
- Native `ATrace` slices and counters inside `ffmpeg_bridge.c` so the trace can
  separate:
  - `RID2C avcodec_send_packet`
  - `RID2C avcodec_receive_frame`
  - `RID2C anomaly_rgba_convert`
  - `RID2C anomaly_process_frame`
  - `RID2C anomaly_overlay_convert`
  - `RID2C render_rgba_convert`
  - `RID2C render_surface_post`

The trace also exports these counters:

- `RID2C anomaly_us`
- `RID2C reg_health`
- `RID2C rescan_mode`
- `RID2C render_queue_depth`
- `RID2C render_latency_ms`

## One-time prep

Build a new app binary that contains the native trace slices, then deploy it
with your usual install path. To validate compileability locally:

```sh
./gradlew :app:assembleDebug
```

If you want the repo's sample clip on-device:

```sh
tools/android_profiling/push_test_video.sh --serial <device-serial>
```

That pushes:

- `/Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/PowerHouseTeam.mp4`

to:

- `/sdcard/Download/PowerHouseTeam.mp4`

## Capturing a trace

Use the physical tablet serial unless you intentionally want the emulator.

```sh
tools/android_profiling/capture_perfetto_trace.sh \
  --serial <device-serial> \
  --duration 20 \
  --trace-name powerhouse_team_scan60 \
  --launch-app
```

The script will:

1. Verify the package and Perfetto on the device.
2. Start focused logcat capture.
3. Optionally launch `org.ncssar.rid2caltopo/.app.R2CActivity`.
4. Give you a short ready delay.
5. Record a Perfetto trace.
6. Pull the trace and supporting logs into:
   `/Users/kjt/Projects/RID2Caltopo/tools/android_profiling/out/`

During the ready-delay window:

1. Open `PowerHouseTeam.mp4` through the captured-video flow.
2. Set the anomaly mode you want to profile.
3. Start playback and let the unstable runtime segment run during the capture.

## How to read the trace

Open the `.perfetto-trace` file in [ui.perfetto.dev](https://ui.perfetto.dev/)
and search for `RID2C`.

Use these cues:

- If `RID2C anomaly_process_frame` stays near the harness expectation while
  `RID2C avcodec_receive_frame` or scheduler gaps widen, the slowdown is likely
  decode cadence, upstream delivery, or CPU scheduling rather than anomaly work.
- If `RID2C anomaly_process_frame` widens materially during the bad runs, the
  detector itself is growing on-device and the trace width tells you whether the
  cost is the detector core or the RGBA conversion around it.
- If `RID2C render_surface_post` grows while anomaly slices stay flat, the
  renderer or Surface path is the likely bottleneck.
- If `RID2C render_queue_depth` drains to zero before visible stalls, playback
  is source-limited. If it grows large while rendered output still lags, the
  render side is falling behind.
- If bad runs correlate with low CPU frequency tracks, long runnable-but-not-
  running gaps, GC work, or thermal state changes, the noise is systemic rather
  than detector-specific.

## Correlating with existing RID2C logs

The logcat file captured next to the trace is useful for:

- `decoded frame gap`
- `reader_wait_long`
- `render control`
- session lifecycle transitions

These help explain whether a bad runtime window came from:

- decoder starvation
- bursty source cadence
- render backlog
- local-playback pacing
- registration health or rescan-mode flips

## Suggested PowerHouseTeam passes

Capture at least one trace for each app configuration you are comparing:

- `scan_zone=0.80`
- `scan_zone=0.70`
- `scan_zone=0.60`
- `scan_zone=0.50`

Name each trace accordingly, for example:

- `powerhouse_team_scan80`
- `powerhouse_team_scan60_run1`
- `powerhouse_team_scan60_run2`

That makes it much easier to compare whether the runtime instability tracks the
anomaly pipeline, render/decode behavior, or broader device conditions.

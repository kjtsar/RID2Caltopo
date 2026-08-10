## IR Anomaly Detector Handoff: Post-Registration Fix / Perf Investigation

### Goal of Next Thread

Start from the current registration-fixed baseline and add lightweight stage timing so we can see where runtime is actually going on-device. Do not resume planner tuning blindly until timing data is in place.

### Current Ground Truth

The original full-rescan problem was real registration starvation, not planner-threshold tuning:

- `PowerHouseTeam + affine`: mostly `affine-too-few-corners`
- `PowerHouseTeam + gmv`: mostly `gmv-too-few-anchors`
- `PowerHouse1 + affine`: mostly `affine-too-few-corners`
- `PowerHouse1 + gmv`: mostly `gmv-too-few-anchors`

That starvation was materially improved by a registration-only fix:

- registration uses a broader ROI than anomaly scan
- registration uses a lightly filtered luma path
- GMV patch support was increased
- affine tracking patch support was increased

### Registration Evolution So Far

This is the short historical sequence the next thread should keep in mind:

1. Initial diagnosis
   - selective refresh was frequently collapsing back toward broad rescans
   - the root cause was not primarily planner thresholds
   - the main issue was frame-to-frame registration starvation:
     `affine-too-few-corners` for affine and `gmv-too-few-anchors` for GMV

2. Registration-only recovery pass
   - registration was decoupled from the narrower anomaly `scan_zone`
   - a lightly filtered registration-only luma path was added
   - patch support was enlarged for both GMV and affine tracking
   - this materially improved registration validity in the focused replays

3. Planner experiment after the registration fix
   - `stale-high` no longer forcing `FULL` was tried after validity improved
   - the harness looked better on selective refresh mix
   - but early false positives increased, some misses increased, and Android
     playback did not improve enough
   - that experiment was reverted, so the current baseline is the safer
     post-registration-fix planner behavior

4. Current interpretation
   - registration starvation is no longer the dominant correctness failure in
     the focused baseline
   - parallax/discontinuity spikes still happen and are now measurable
   - but the remaining common issues have looked more like stale coverage /
     refresh-mask breadth than outright registration invalidity
   - at the same time, timing now shows `registration_solve` is the dominant
     runtime bucket, so registration remains the center of gravity for both
     continuity and performance

### Changes That Should Stay

These changes are intended to remain:

1. Registration ROI decoupled from anomaly scan ROI
   - anomaly scan still uses `scan_zone`
   - registration operates on a broader ROI/full frame path

2. Registration-only prefilter
   - separate filtered luma path for registration
   - detector/anomaly scoring still uses original path

3. Larger registration support
   - GMV patch half-width increased from `3` to `4` (`7x7` -> `9x9`)
   - affine tracking patch half-width increased from `2` to `3` (`5x5` -> `7x7`)

4. Existing telemetry should stay for now
   - scan-plan reason flags
   - registration invalid-reason detail
   - harness summary JSON/stderr reporting
   - registration consistency/parallax telemetry:
     - residual std
     - residual max
     - motion dx/dy std
     - quadrant residual spread

### Change That Was Tried And Reverted

One planner experiment was tested and then reverted:

- `stale-high` no longer forcing `FULL`

Why it was reverted:

- harness selective refresh improved dramatically
- but early false positives increased
- some misses increased
- user reported Android playback remained around `0.74x` realtime
- likely cause: more `PARTIAL` / `TARGET_ONLY` rescans still pay ROI-wide planner and mask-build costs every analyzed frame, so harness gains did not translate cleanly to device playback

The codebase should now be back on the safer pre-experiment planner behavior.

### Verified State After Revert

Focused replay check after the revert on `PowerHouseTeam 0s-10s affine` returned to the earlier post-registration-fix posture:

- `full=76 partial=149 target_only=75`
- `reg-invalid=1/300`
- `Frames with boxes=53`

This is the current baseline to preserve while doing the next pass.

### What The Yellow Boxes Mean

Overlay color meanings in native code:

- blue: `color`
- red: `thermal`
- green: `motion`
- yellow: `persist` / multi-cue saliency

There is also a lighter amber rectangle used as a plausible thermal blob debug overlay.

If yellow boxes sit on top of red boxes, that usually means saliency/persist is reinforcing the same thermal region, not that a second object exists there.

### Parallax Status

Parallax is plausible and now measurable, but it is not currently the dominant blocker:

- registration starvation is no longer dominating focused replays
- one-off parallax/discontinuity spikes do happen
- but the main post-fix remaining planner reasons were more about stale coverage / mask breadth than registration invalidity

If parallax is used later, the safest place is planner-side softening in `build_scan_plan()`, not registration invalidation or core health classification.

### ShowHot Status

`ShowHot` does not cost us when disabled.

`draw_hot_overlay_rgba()` is only called when `cfg->show_hot_overlay` is true. With it off, the cost is effectively just the boolean check.

### Likely Runtime Hotspots

Based on current code inspection, the most likely expensive stages are:

1. Registration prep every analyzed frame
   - full-frame motion-grid luma build
   - registration prefilter

2. Registration model estimation
   - GMV / affine matching and fit

3. ROI-wide scan planning
   - warped-valid / newly-exposed / stale accounting

4. ROI-wide selective refresh mask build
   - even `PARTIAL` / `TARGET_ONLY` still loop over the sampled ROI

5. Sampled-grid prep and stats
   - sampled luma grid
   - integral-image / local statistics setup

6. Detector scoring work
   - thermal
   - color
   - motion
   - saliency/persist

7. Tracking / revisit maintenance
   - target-track propagation and update
   - target revisit cell annotation

8. Overlay drawing
   - mostly box drawing and optional debug overlays
   - `ShowHot` only matters if enabled

### Recommended Next Step

Do not do more algorithm tuning first.

Add stage timing instrumentation for:

- registration prep
- registration solve
- scan planning
- refresh-mask build
- sampled-grid / integral-image prep
- thermal scoring
- color scoring
- motion scoring
- saliency/persist scoring
- target tracking
- overlay drawing

Expose that timing through:

- harness stderr/summary JSON
- app debug summary only when debug timing is enabled

### Important Shipping Requirement

All debug/timing/telemetry added during this investigation must be easy to disable for shipping.

Target design:

1. Heavy timing / verbose per-frame summaries behind compile-time guards
   - example: `#if ANOMALY_DEBUG_TIMING`

2. Lightweight counters/timers optionally available behind cheap runtime flags

3. Release builds default-off

4. Core algorithm path should not depend on debug collection

Avoid turning permanent debug overhead into shipped cost.

### Files Most Relevant To Next Thread

- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h`
- `/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/ffmpeg_bridge.c`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/anomaly_video_test.c`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/run_focused_registration_experiments.py`
- `/Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/README.md`

### Useful Commands

Rebuild harness:

```sh
cmake --build tools/anomaly_test/build
```

Focused four-case matrix:

```sh
python3 tools/anomaly_test/run_focused_registration_experiments.py \
  --output-dir /tmp/focused_registration \
  --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.8
```

Android build:

```sh
./gradlew assembleDebug
```

### Trace-Based Runtime Findings Added In This Thread

The next thread should start from the fact that we now have real Android
runtime traces and stage timing, not just harness intuition.

#### What was added

1. Native Perfetto trace slices in `ffmpeg_bridge.c`
   - `RID2C anomaly_process_frame`
   - `RID2C avcodec_send_packet`
   - `RID2C avcodec_receive_frame`
   - `RID2C anomaly_rgba_convert`
   - `RID2C anomaly_overlay_convert`
   - `RID2C render_rgba_convert`
   - `RID2C render_surface_post`

2. Command-line Android trace workflow
   - [tools/android_profiling/capture_perfetto_trace.sh](/Users/kjt/Projects/RID2Caltopo/tools/android_profiling/capture_perfetto_trace.sh)
   - [tools/android_profiling/push_test_video.sh](/Users/kjt/Projects/RID2Caltopo/tools/android_profiling/push_test_video.sh)
   - [docs/IR_Anomaly_Runtime_Tracing.md](/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Runtime_Tracing.md)

3. Android debug build now compiles native timing buckets on by default
   - `app/build.gradle` passes `-DANOMALY_DEBUG_TIMING=ON` for `debug`
   - `release` is explicitly left timing-off

4. Direct logcat emission of the timing summary every 30 analyzed frames
   - emitted from `ffmpeg_bridge.c`
   - grep for `anomaly timing` or `timing[`

#### First real device conclusion

Perfetto and device timing showed the app-side bottleneck was real detector
cost, not conversion or render posting:

- `anomaly_process_frame` on device was about `35-38 ms`
- `anomaly_rgba_convert` was about `0.21 ms`
- `render_surface_post` was about `0.78 ms`

So the slowdown was inside `anomaly_process_frame`.

#### Stage timing conclusion before reuse optimization

Representative device timing lines on `PowerHouseTeam.mp4` showed:

- `total ~38 ms`
- `prep ~0.33 ms`
- `solve ~20.3-20.6 ms`
- `plan ~2.7-2.8 ms`
- `mask ~3.x ms`

That established:

1. `registration_solve` was the dominant hotspot
2. `scan_planning` and `refresh_mask_build` were secondary
3. `prep` was negligible

#### Optimization sequence added in this thread

1. Affine fit fast path
   - in stable high-match cases, accept the cheap least-squares fit directly
   - fall back to full affine RANSAC when consistency is weaker

2. Stable affine registration reuse
   - cache a very healthy affine registration model in `anomaly_state_t`
   - allow one-frame reuse in safe `healthy` `target-only` / `partial` cases
   - later extended to allow two-frame reuse only in ultra-stable
     `target-only` cases

These changes live in:

- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)

#### Current harness state after the second reuse pass

Using:

```sh
python3 tools/anomaly_test/run_registration_perf_benchmarks.py \
  --binary tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/registration_perf_bench_reuse2
```

Current results:

- `PowerHouseTeam-affine-s0.80`
  - realtime `1.73x`
  - avg total `18.44 ms`
  - reg solve `4.75 ms`
  - plan `1.81 ms`
  - mask `1.69 ms`

- `PowerHouseTeam-affine-s0.60`
  - realtime `2.52x`
  - avg total `12.51 ms`
  - reg solve `4.45 ms`
  - plan `0.91 ms`
  - mask `0.95 ms`

- `PowerHouse1-affine-s0.80`
  - realtime `0.97x`
  - avg total `30.41 ms`
  - reg solve `5.21 ms`
  - thermal `10.09 ms`

- `PowerHouse1-opening-affine-s0.60`
  - realtime `1.24x`
  - avg total `22.17 ms`
  - reg solve `5.74 ms`
  - thermal `6.72 ms`

Interpretation:

1. Registration solve is no longer the dominant harness cost
2. `PowerHouse1` now looks much more thermal-bound than solve-bound
3. `PowerHouseTeam` harness is comfortably faster than realtime again

#### Current device state after reuse optimization

The newest Android logcat timing lines showed the reuse path is definitely
active on device:

- some frames still show `solve ~20.5 ms`, `total ~38 ms`
- alternating very stable frames show `solve = 0.00 ms`, `total ~18-20 ms`

Representative examples:

- frame `330`: `total=38.15 ms`, `solve=20.56 ms`
- frame `360`: `total=17.57 ms`, `solve=0.00 ms`
- frame `390`: `total=37.92 ms`, `solve=20.54 ms`
- frame `420`: `total=19.56 ms`, `solve=0.00 ms`
- frame `540`: `total=17.96 ms`, `solve=0.00 ms`
- frame `600`: `total=18.01 ms`, `solve=0.00 ms`

So the solve-skipping optimization is working on device.

#### Important unresolved issue

The app UI reportedly still showed around `.35x realtime`, but the timing logs
do not support that as steady-state anomaly compute anymore.

From the newest device timing sequence:

- frames `330 -> 600` advanced over about `10` wall-clock seconds
- that is about `270` analyzed frames / `10 s` ≈ `26.9 fps`
- for a `30 fps` local clip, that is about `0.90x realtime`

So the next thread should assume:

1. anomaly compute is materially improved already
2. the UI realtime indicator is probably being dragged down by startup gaps,
   packet-gap accounting, local playback pacing, or metric definition
3. the next bottleneck may now be playback accounting/pacing rather than pure
   registration solve

#### Recommended next thread

Do not immediately spend another thread only on affine solve.

Start by reconciling the app’s displayed realtime factor with the real timing:

1. Inspect how `localPlaybackRealtimeFactor` is computed in Kotlin
2. Check whether startup idle, packet gaps, or EOF spacing are included
3. Check whether render pacing is intentionally throttling local playback
4. Compare UI realtime factor against the actual `anomaly timing` cadence

#### Follow-up finding

`localPlaybackRealtimeFactor` was being computed as a whole-session cumulative
average from the first rendered frame to the latest rendered frame:

- media span = `last_pts - first_pts`
- wall span = `last_render_at - first_render_at`
- realtime = `media span / wall span`

That means the UI metric could stay artificially low long after steady-state
processing improved, because any early startup drag or earlier slow segment kept
polluting the denominator.

The native local-file playback path also intentionally paces renders near source
cadence, so the UI metric is fundamentally a playback-speed metric, not a pure
anomaly-stage-throughput metric.

Current adjustment:

- retain the full-session span for debugging context
- compute the UI-facing realtime factor from a recent local-playback render
  window instead of the entire session
- label the panel’s long span as `session avg` so it is clear when the recent
  factor and cumulative factor differ

Likely files:

- [app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegProbeService.kt](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegProbeService.kt)
- [app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt](/Users/kjt/Projects/RID2Caltopo/app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt)
- [app/src/main/cpp/ffmpeg_bridge.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/ffmpeg_bridge.c)

If the UI metric turns out to be correct even after cleanup, only then return
to deeper tracker/feature-cost optimization inside affine tracking.

Single focused replay example:

```sh
./tools/anomaly_test/build/anomaly_video_test \
  /Users/kjt/Projects/RID2Caltopo/app/src/test/resources/vidcap/PowerHouseTeam.mp4 \
  --registration affine --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.8 \
  --time-start 0 --time-end 10.0 --no-video \
  -c /tmp/pht_affine_check.csv
```

### Success Criteria For Next Thread

1. Timing data identifies the dominant on-device hotspots clearly.
2. Debug timing can be enabled for development and disabled for shipping.
3. Registration validity remains improved.
4. No reintroduction of the stale-planner quality/perf regression.

### Latest Status Update

This section supersedes the earlier "UI metric may be misleading" suspicion.

What is now established:

1. The app realtime metric was partly misleading at first.
   - `localPlaybackRealtimeFactor` was changed to use a recent playback window
     instead of a whole-session cumulative average.
   - the panel still retains the long-span `session avg` context

2. After that metric fix, the app still honestly reports about `0.82x-0.90x`
   realtime on local playback of `PowerHouseTeam.mp4`.
   - this is not primarily profiler overhead
   - this is not primarily the old cumulative-average UI bug

3. App-side debug timing was then turned back off for Android debug builds.
   - app debug build now uses `-DANOMALY_DEBUG_TIMING=OFF`
   - harness timing builds remain available and unchanged

4. The harness does **not** reproduce the app slowdown.
   - local harness `affine` on `PowerHouseTeam.mp4` stays faster than realtime
   - depending on the harness path/window, observed results were roughly
     `1.36x` to `1.86x` realtime

5. `affine` remains the correct registration backend for now.
   - harness comparison showed `gmv` much slower on `PowerHouseTeam.mp4`
   - `gmv` did not show a reviewed-quality advantage in the checked window

6. A more aggressive affine cached-registration reuse experiment was tested in
   the harness and rejected.
   - it made throughput worse, not better
   - it also showed at least mild behavior risk
   - the main workspace was restored to the safer reuse policy afterward

7. Overlay polish work was completed.
   - main ROI markers now render as segmented crosshairs with a center gap
     rather than full boxes plus a central plus sign
   - user confirmed this visual change is good

8. Saliency/yellow boxes are user-toggleable in app settings.
   - do not assume they must be removed in code by default

### Current Interpretation

The remaining app slowdown is probably **not** best attacked first by:

- swapping backends again
- making affine reuse more aggressive
- revisiting the earlier UI metric accounting work

The strongest current interpretation is:

1. the pure detector core on host is still comfortably faster than realtime
2. the app's local playback path still loses meaningful time outside the pure
   harness detector path
3. if detector-core work is still worth optimizing, the next likely native
   buckets are planner / refresh-mask / sampled-grid prep costs inside affine,
   not reuse-budget policy

### Recommended Next Thread

Open a fresh thread rather than continuing indefinitely from this one.

Primary goals for the next thread:

1. Reconcile app-vs-harness end-to-end cost without re-polluting the app with
   heavy debug overlays or persistent UI instrumentation.

2. Measure local playback overhead with the same clip in at least two app
   conditions:
   - anomaly off
   - anomaly on with current affine configuration

3. Compare those app measurements against the harness baseline to identify what
   portion of the remaining loss is:
   - decode/render/pacing overhead
   - detector-core overhead
   - planner/mask/sample prep specifically

4. If another harness-side native optimization pass is needed, prefer
   investigating:
   - `scan planning`
   - `refresh mask build`
   - `sampled-grid / stats prep`
   before revisiting cached-affine reuse policy.

### Useful Known Results

Representative harness findings from this phase:

- `PowerHouseTeam.mp4`, `affine`, first `10s`:
  about `1.36x-1.86x` realtime depending on harness path/config
- representative `registration_solve avg` in harness:
  about `4-5 ms`
- representative `registration_solve max` in harness:
  about `21-25 ms`
- harness registration validity remained good:
  about `1/300` `reg-invalid` in the focused runs

Representative app findings from this phase:

- app local playback still honestly reports about `0.82x-0.90x`
- temporary app debug-window breakdown showed large `process` cost but also
  confirmed the slowdown was real, not just a UI artifact

### Do Not Re-Do First

Unless new evidence appears, do **not** spend the next thread first on:

- more GMV-vs-affine swapping
- more aggressive affine reuse-budget widening
- removing saliency in code just to hide yellow boxes
- re-debugging the old whole-session realtime metric issue

# IR Anomaly Detector

Current implementation note: for the consolidated current-state detector
reference, including app defaults, FFmpeg threading, scan planning, visible
color, motion, saliency, and runtime caveats, start with
[Current Anomaly Detector](Current_Anomaly_Detector.md).

## Operator Summary

RID2Caltopo's IR anomaly detector is designed to help an operator notice small,
unusual regions in thermal video while suppressing camera motion, edge noise,
and one-frame false positives.

At a high level, the detector:

1. Looks for small thermal or motion anomalies inside the configured scan zone.
2. Estimates camera motion so detections can be compared in a motion-stabilized
   frame of reference instead of raw screen coordinates.
3. Requires persistence across analyzed frames before promoting a detection to a
   visible ROI box.
4. Uses unified saliency only when there is usable motion-registration support.
   If the app cannot successfully derive a corresponding motion vector, saliency
   is reset instead of lingering.

For IR work, the default app posture is now:

- `Thermal Palette`: `Black Hot`
- `Appearance`: `Thermal`
- `Registration`: `Affine`
- `Frame Stride`: `1`
- `Scan Zone`: `80%`
- `Min Hits`: `2`
- `Thermal Min Delta`: `10.0`

The redesign posture behind those defaults is:

- registration and target-state propagation run every frame
- carried coverage supports continuity, not brand-new strong detections
- selective refresh should prefer smaller scan zones and planner-driven revisits
  before falling back to stride-heavy sampling

### What the Main Controls Mean

- `Detection`: Master enable for anomaly processing.
- `Appearance`: Chooses the primary appearance detector.
  `Thermal` is the current IR-focused mode. `Color` exists but has not been the
  main tuning target so far.
- `Motion`: Enables the motion detector as a separate cue.
- `Saliency`: Enables unified saliency. This combines appearance evidence with
  motion-supported temporal evidence. For parity with harness runs, this can now
  be turned on or off explicitly.
- `Thermal Palette`: `Black Hot` means darker pixels are treated as hotter.
  `White Hot` means brighter pixels are treated as hotter.
- `Sensitivity`: Maps to the detector's score threshold.
  Lower sensitivity is stricter. Higher sensitivity is more willing to draw
  boxes.
- `Motion Evidence`: Scales how strongly motion contributes to scoring.
- `Scan Zone`: Restricts analysis to the centered portion of the frame to avoid
  edge distortion and wide-angle clutter.
- `Min Hits`: Number of analyzed frames that must agree before a box is shown.
- `Frame Stride`: Analyze every Nth frame. Higher values reduce CPU load but
  increase latency and can miss short events.
- `Detail`: Pixel sampling step. Lower values inspect more detail and cost more.
- `Registration`: Chooses camera-motion compensation backend.
  `Affine` is usually the preferred setting for drone IR review.
- `Thermal Min Delta`: Minimum thermal contrast before thermal evidence is taken
  seriously.
- `Show Hottest Region` and `Show Candidate Blobs`: Debug visualizations.

### Practical Tuning Guidance

- If you see too many false positives:
  raise `Sensitivity` strictness, raise `Min Hits`, lower `Scan Zone`, or raise
  `Thermal Min Delta`.
- If you are missing teammate detections:
  lower strictness, reduce `Frame Stride`, lower `Thermal Min Delta`, or reduce
  `Min Hits`.
- If detections drift with camera motion:
  verify `Registration` is `Affine` and keep `Saliency` enabled only if the run
  has usable motion support.
- If low-end hardware struggles:
  lower `Scan Zone` first, keep `Frame Stride=1` if at all possible, and only
  then consider increasing `Detail` step size or disabling cues you do not
  need.

### Reset Behavior

`Reset AD Controls to Realtime Defaults` is intended to restore the operating
defaults without changing the user's anomaly-detection context. It now preserves:

- `Appearance` (`Thermal` vs `Color`)
- `Thermal Palette` (`Black Hot` vs `White Hot`)

Other detector controls return to the app's realtime defaults.

### Persistence

All app detector settings are persisted. That includes:

- detection enabled
- appearance selection
- motion enabled
- saliency enabled
- show-hot overlay
- candidate blobs
- frame stride
- detail / pixel step
- sensitivity
- motion evidence sensitivity
- min area fraction
- thermal palette
- registration mode
- scan zone
- min hits
- thermal min delta

## Technical Appendix

### Scope

This document describes the IR-focused anomaly detector shared by:

- the Android app through `ffmpeg_bridge.c`
- the host-side harness through `tools/anomaly_test/anomaly_video_test.c`

The important architectural point is that detector logic lives in
[`anomaly_analysis.c`](../app/src/main/cpp/anomaly_analysis.c)
and is intended to behave the same in both environments.

### Pipeline Overview

For each analyzed frame, the detector does roughly the following:

1. Convert the input frame into working luma grids.
2. Estimate camera motion using either `GMV` or `Affine` registration.
3. Reject or down-weight evidence when the registration fit is unstable.
4. Compute cue-specific candidate evidence:
   thermal hotspot, color outlier, motion, and optional unified saliency.
5. Promote raw candidates into per-algorithm temporal accumulators.
6. Draw ROI boxes only after `min_hits` and hold them briefly after misses.

### Detectors

#### Thermal

Thermal mode looks for local luma values that stand out from neighboring
regions and, once the background model is warm, from their own temporal
history.

- In `Black Hot`, darker-than-background regions are hotter.
- In `White Hot`, brighter-than-background regions are hotter.
- `thermal_min_delta` acts as a hard floor on thermal contrast before thermal
  evidence is allowed to matter.

#### Motion

Motion detection is not just "pixel changed." The code first estimates camera
motion, then looks for local motion that remains after subtracting the camera's
global movement. This is what allows the detector to remain useful while the
aircraft is panning, yawing, or drifting.

#### Unified Saliency

Unified saliency combines:

- thermal spatial evidence
- optional color support
- thermal temporal evidence from the background model
- stamped local motion support
- registration quality scaling

The current contract is stricter than the earlier implementation:

- saliency now requires usable motion-registration support
- if the detector cannot successfully decipher a corresponding motion vector,
  saliency should not score
- when that prerequisite disappears, saliency tracks are cleared instead of
  being allowed to coast on hold frames

This behavior was tightened to eliminate persistent parked ROI boxes that could
survive after motion support had gone invalid.

### Registration Backends

#### GMV

`GMV` is a simpler global-motion estimator. It is useful for comparison and can
be cheaper, but it is more sensitive to difficult camera motion and scene
structure.

#### Affine

`Affine` fits a richer motion model and is usually the preferred backend for
IR playback review. In practice it tends to do a better job stabilizing
detections through realistic drone motion.

Both backends can declare a scene discontinuity or otherwise provide weak
registration quality. When that happens, downstream scoring is suppressed or
reset.

### Registration Evolution

The detector's recent registration work followed this path:

1. The first focused diagnosis was that selective-refresh instability was being
   driven mainly by registration starvation, not by planner-threshold tuning.
   In the key black-hot replays, affine commonly failed as
   `affine-too-few-corners` and GMV commonly failed as `gmv-too-few-anchors`.

2. The main recovery pass was registration-only:
   registration was allowed to use a broader ROI than anomaly scanning,
   registration got its own lightly filtered luma path, and patch support was
   increased for both GMV and affine matching. That substantially improved
   registration validity in the focused replay matrix.

3. After that fix, one planner-side selective-refresh experiment was tried and
   reverted. It improved harness refresh mix but increased some quality issues
   and did not sufficiently improve Android playback. That revert is important
   because it means the present baseline should be understood as
   post-registration-fix but pre-new-planner-tuning.

4. Parallax is still considered real and worth measuring, but it is not
   currently treated as the dominant blocker in the focused baseline. If
   parallax handling is revisited, the safer direction is planner-side
   softening rather than making registration invalidation more aggressive.

### Temporal Stabilization

Every major cue feeds a temporal accumulator:

- raw candidates are generated in the current analyzed frame
- candidate positions are compared against the existing track
- nearby candidates are blended with an EMA
- `min_hits` gates promotion to a visible ROI
- a short hold period reduces flicker after brief misses

This is why `frame_stride` and `min_hits` interact directly with perceived
latency.

Approximate direct-observation latency is:

`frame interval × frame_stride × min_hits`

For example, 30 fps with `stride=1` and `min_hits=2` means the detector needs
agreement across analyzed frames roughly 67 ms apart. At `stride=3`, that
widens to roughly 200 ms between analyzed-frame confirmations.

### Harness and App Parity

The harness exists to qualify the same detector code the app uses. The goal is
not merely "similar behavior," but shared detector behavior under shared
settings.

Parity depends on matching at least these parameters:

- algorithm mask
- thermal polarity
- registration backend
- frame stride
- threshold / sensitivity
- min hits
- scan zone
- thermal min delta

The app now exposes the important harness-facing knobs that were previously
implicit:

- saliency on/off
- registration backend
- thermal min delta

That makes it easier to compare a harness command line directly against app
behavior.

### Stage Timing

For focused performance work, the shared detector can be compiled with coarse
stage timing enabled via `ANOMALY_DEBUG_TIMING`.

The intended usage right now is primarily through the host harness:

```sh
cd /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test
cmake -B build_timing -DANOMALY_DEBUG_TIMING=ON
cmake --build build_timing
./build_timing/anomaly_video_test /path/to/clip.mp4 \
  --registration affine --stride 1 -p bh -a 6 -t 2.8 \
  --summary-json /tmp/clip_summary.json --no-video
```

The focused four-case replay can also use the timing-enabled binary:

```sh
python3 /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/run_focused_registration_experiments.py \
  --binary /Users/kjt/Projects/RID2Caltopo/tools/anomaly_test/build_timing/anomaly_video_test \
  --output-dir /tmp/focused_registration_timing \
  --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.8
```

When compiled in, timing is surfaced in two places:

- harness stderr end-of-run summary
- harness `--summary-json` under `stage_timing`

The current stage names mean:

- `registration_prep`: build motion-grid luma plus registration-only prefilter
- `registration_solve`: estimate GMV or affine registration model
- `scan_planning`: selective refresh planner over the scan ROI
- `refresh_mask_build`: partial/target-only refresh-mask construction
- `sampled_grid_prep`: sampled-grid extraction, integral images, and color tile stats
- `thermal_scoring`: thermal spatial/temporal scoring and thermal candidate work
- `color_scoring`: color cue scoring
- `motion_scoring`: residual motion scoring after registration compensation
- `saliency_scoring`: persist / unified saliency scoring
- `target_tracking`: track propagation, track updates, revisit annotation
- `overlay_draw`: debug overlay and box drawing

Interpretation guidance:

- Start with `avg_total_ms` and `realtime_factor` for throughput.
- Use the per-stage `avg_ms` values to identify steady-state hotspots.
- Use `max_ms` values to spot spikes, but do not treat single-frame maxima as
  typical cost.
- Stage buckets are approximate and may not add up exactly to total frame
  timing because some framing work is intentionally left outside the named
  buckets.
- Stages at `0.00 ms` usually indicate the corresponding cue was disabled in
  that run.

The compile-time flag defaults off so shipping builds do not carry this
instrumentation unless explicitly enabled.

### Current App Defaults

The app-side `AnomalyConfig` defaults currently resolve to:

- `Appearance`: `Thermal`
- `Thermal Palette`: `Black Hot`
- `Motion`: enabled
- `Saliency`: disabled
- `Registration`: `Affine`
- `Frame Stride`: `1`
- `Sensitivity`: `42%`
- `Motion Evidence Sensitivity`: `60%`
- `Min Area Fraction`: `0.0015`
- `Scan Zone`: `0.50`
- `Min Hits`: `2`
- `Thermal Min Delta`: `10.0`

### Typical Harness Commands

IR-focused thermal + motion, without saliency:

```sh
./build/anomaly_video_test path/to/clip.mp4 \
  --registration affine --stride 1 -p bh -a 6 -t 2.8 -m 2 -s 0.6 --min-delta 10
```

IR-focused thermal + motion + saliency, matching the app when saliency is on:

```sh
./build/anomaly_video_test path/to/clip.mp4 \
  --registration affine --stride 3 -p bh -a 14 -t 2.8 -m 2 -s 0.6 --min-delta 10
```

`-a 6` means thermal + motion.

`-a 14` means thermal + motion + unified saliency.

### Known Tradeoff

The recent fix that makes saliency require motion-vector support eliminates the
persistent false ROI problem that was being chased in playback review. The
tradeoff is that some detections that previously scored as saliency with weak
or missing motion support may now be suppressed. If teammate detection quality
drops, the first places to investigate are:

- `Thermal Min Delta`
- `Sensitivity`
- `Frame Stride`
- `Min Hits`
- whether the target clip should run with saliency enabled at all

### Source Files

- Shared detector:
  [`anomaly_analysis.c`](../app/src/main/cpp/anomaly_analysis.c)
- Native bridge:
  [`ffmpeg_bridge.c`](../app/src/main/cpp/ffmpeg_bridge.c)
- App config model:
  [`AnomalyModels.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt)
- App settings persistence:
  [`AnomalyPrefs.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyPrefs.kt)
- Harness usage:
  [`tools/anomaly_test/README.md`](../tools/anomaly_test/README.md)

# Current Anomaly Detector

Last code review: 2026-06-13.

This document describes the anomaly detector as it exists in the current
RID2Caltopo tree. It is intentionally implementation-facing: it separates the
shipping runtime path from older design goals and historical tuning notes.

Primary source files:

- [`AnomalyModels.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyModels.kt)
- [`AnomalyPrefs.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/AnomalyPrefs.kt)
- [`StreamTile.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/StreamTile.kt)
- [`StreamsViewModel.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt)
- [`FfmpegBridge.kt`](../app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegBridge.kt)
- [`ffmpeg_bridge.c`](../app/src/main/cpp/ffmpeg_bridge.c)
- [`anomaly_runtime_budget.h`](../app/src/main/cpp/anomaly_runtime_budget.h)
- [`anomaly_runtime_handoff.h`](../app/src/main/cpp/anomaly_runtime_handoff.h)
- [`anomaly_runtime_pressure.h`](../app/src/main/cpp/anomaly_runtime_pressure.h)
- [`anomaly_analysis.h`](../app/src/main/cpp/anomaly_analysis.h)
- [`anomaly_analysis.c`](../app/src/main/cpp/anomaly_analysis.c)
- [`anomaly_video_test.c`](../tools/anomaly_test/anomaly_video_test.c)

Related maintained documents:

- [Anomaly Detector: An Introduction for SAR Users](Anomaly_Detector_Introduction_for_SAR.md)
- [IR Anomaly Detector](IR_Anomaly_Detector.md)
- [Color Anomaly Detector](Color_Anomaly_Detector.md)

## Summary

The anomaly detector is a native C analyzer shared by the Android app and the
host replay harness. The Android app feeds decoded video through FFmpeg,
optionally converts frames to RGBA, calls `anomaly_process_frame()`, receives
zero or more normalized boxes, and draws those boxes back onto the rendered
frame.

The detector is not one single cue. It is a multi-cue system with these
algorithm bits:

- `ANOMALY_ALGO_COLOR` (`0x01`): visible-color outlier / color-blob path.
- `ANOMALY_ALGO_THERMAL` (`0x02`): thermal local-contrast and temporal
  background path.
- `ANOMALY_ALGO_MOTION` (`0x04`): residual motion path after global camera
  compensation.
- `ANOMALY_ALGO_PERSIST` (`0x08`): unified saliency / multi-cue persisted
  path.
- `ANOMALY_ALGO_MOTION_TOLERANCE` (`0x10`): experimental residual-displacement
  motion path.

The default app configuration is conservative and starts disabled. When enabled
without prior user changes, the current `AnomalyConfig()` resolves to:

- `enabled = false`
- appearance selection `Color`; operators explicitly select `Infrared` for
  thermal footage
- algorithm set containing `Motion`; the resolved appearance cue adds
  `ColorOutlier` for the default color footage
- saliency disabled
- the stored baseline has `frameStride = 1`; default Color conversion applies
  adaptive `30..60` frame discovery cadence with a two-second cap
- `pixelStep = 0` in stored settings, converted to dense `pixelStep = 1` for
  Color analysis
- sensitivity `0.59`, which maps to about a `3.04` native score threshold
- motion evidence sensitivity `0.60`
- min area fraction `0.0015`
- thermal polarity `Black Hot`
- registration mode `Affine`
- movement estimator mode `Layered Active`
- scan zone `0.50`
- min hits `2`
- thermal min delta `10.0`
- small target screen fraction `1/200`
- color frontend mode `Legacy`

`AnomalyPrefs` migrates removed or unknown appearance selections to `Color`
and migrates legacy realtime-like saved defaults to this current posture so
profiles do not remain stranded on the older thermal-only configuration.

The current native tree also contains a gated selective-refresh improvement:
full scans can retain a bounded percentage of thermal candidates as provisional
target tracks, and interim refreshes can revisit those predicted target gates
using movement-estimator evidence. This is not a user-facing default change.
The app defaults still analyze every frame (`frameStride = 1`) because the
selective stride path has not yet passed the PowerHouse app-default quality
gates.

## App Configuration Path

`AnomalyConfig` is the Kotlin source of truth for operator-facing state. The
app persists it through `AnomalyPrefs`, keeps a default copy in
`StreamsViewModel`, and writes per-stream overrides into
`_anomalyConfigByDesignator`.

The important Kotlin-to-native conversion happens in `toNativeConfig()`:

- `appearanceSelection` resolves to either thermal or color.
- The resolved appearance cue is added to the non-appearance algorithm set.
- `saliencyEnabled` adds `PersistentDarkPatch` / `ANOMALY_ALGO_PERSIST`.
- `sensitivity` maps logarithmically to native `score_threshold`:
  `15.0^(1.0 - sensitivity)`, clamped to `1.0..15.0`.
- `motionEvidenceSensitivity` maps to `0.25..2.0` using a squared curve.
- `minAreaFraction` is scaled by sensitivity before native use.
- Native registration, movement-estimator, stride, pixel step, scan-zone,
  min-hit, thermal-delta, and small-target values are clamped.
- Color appearance with `ColorFrontendMode.Legacy` is sent to native as
  `FreshRgba`; otherwise the configured color frontend mode is passed through.

The UI exposes the main controls through `StreamTile.kt`:

- detection on/off
- appearance: infrared or color
- thermal polarity: white hot, black hot
- motion and saliency toggles
- sensitivity and motion evidence sliders
- scan zone, min hits, frame stride, detail / pixel step
- registration backend
- thermal min delta
- debug overlays such as hottest region and candidate blobs

`Reset AD Controls to Realtime Defaults` creates a fresh `AnomalyConfig()` but
preserves the user's appearance and thermal-polarity context.

## Android Runtime Path

The live app path is:

1. `StreamsViewModel` chooses or loads an `AnomalyConfig`.
2. `toNativeConfig()` converts it into `NativeAnomalyConfig`.
3. `FfmpegBridge.updateAnomalyConfig()` calls the JNI native update function.
4. `ffmpeg_bridge.c` copies the config into the per-session `anomaly_cfg`.
5. `reconfigure_anomaly_mode()` starts, stops, or resets the anomaly runtime.
6. FFmpeg decodes frames.
7. The render path sends frames either to the AD worker queue or directly to
   the render queue.
8. The AD worker converts a frame to RGBA, calls `anomaly_process_frame()`, and
   applies any overlay to the decoded frame.
9. The render thread displays the decoded frame with or without AD annotations.

The current render architecture is threaded for render sessions:

`FFmpeg decode -> AD input queue -> AD worker -> render queue -> render thread`

If AD is disabled, not yet ready, thermally paused, runtime-disabled, or unable
to accept a frame, the frame can bypass analysis and continue to render. AD is
advisory; the video display path should not depend on every frame being
analyzed.

The AD input queue has a hard capacity of 24 frames. Under pressure the worker
progressively degrades:

- normal: analyze every queued frame allowed by configuration
- analyze-alternate: double the effective frame stride
- bypass-alternate: forward every other frame without analysis
- bypass-all: forward frames without analysis

If the live AD input queue is full and cannot enqueue, the native bridge tries
to forward the frame directly to the render queue without AD analysis using the
`"live-ad-pressure"` fallback reason. The intended live behavior is pressure
degradation without blocking display. If the render queue also cannot accept
the frame, the frame is dropped and counted as a render drop.

The pure queue-pressure policy now lives in
`anomaly_runtime_pressure.{h,c}`. The bridge still owns the concrete pthreads,
queues, AVFrame ownership, render forwarding, counters, and app logging.

The pure AD worker frame-handoff decision now lives in
`anomaly_runtime_handoff.{h,c}`. The bridge converts a dequeued packet to
metadata and asks whether it should analyze or forward without analysis. The
bridge still owns the actual `AVFrame` pointers, queue slots, overlay frames,
worker counters, and cleanup.

For local playback, there is one extra rule: if the configured frame stride is
greater than one, the AD worker bypasses frames whose `frame_id` does not match
the stride cadence before calling the analyzer. Live pressure modes can also
override the effective stride or bypass analysis.

Motion Estimation is modularized behind `anomaly_motion_estimator.{h,c}`, but
it is still executed synchronously inside `anomaly_process_frame()` by the AD
worker. The standalone R2CAD target architecture may split ME into its own
worker and evidence queue later; that is not the current shipping thread model.

## Native Analyzer Contract

The standalone native entrypoint is:

```c
int anomaly_process_frame(
    anomaly_state_t        *state,
    const anomaly_config_t *cfg,
    uint8_t                *rgba,
    int                     rgba_stride,
    int                     width,
    int                     height,
    int64_t                 source_ts_us,
    anomaly_result_t       *result_out
);
```

The analyzer has no JNI or FFmpeg dependencies. It operates on RGBA input and
mutates the RGBA buffer only when drawing boxes or overlays. It also fills
`anomaly_result_t` with:

- detection boxes
- scene-discontinuity state
- registration health
- selected rescan mode and scan plan
- GMV / affine registration debug data
- movement debug data
- thermal, color, motion, and saliency debug data
- optional per-stage timing data when compiled with `ANOMALY_DEBUG_TIMING`

`anomaly_state_t` is per stream. It owns temporal accumulators, previous luma
grids, thermal background state, motion persistence, visible-color history,
ROI coverage state, target tracks, cached registration, saliency auxiliary
tracks, and scratch buffers. The app resets this state on material anomaly
runtime changes.

## Frame-Level Pipeline

Each analyzed frame follows this broad sequence:

1. Validate config and frame geometry.
2. Increment `state->frame_counter`.
3. Compute the centered scan-zone ROI and the wider registration ROI.
4. Resolve `sample_step` from `pixel_step` and source size.
5. Build coarse full-frame luma grids for registration and motion.
6. Estimate or reuse camera registration.
7. Run the movement sidecar.
8. Capture an immutable movement snapshot for detector scoring.
9. Build a previous-sample lookup when ROI state and registration allow it.
10. Build a scan plan: full, partial, target-only, or stride skip.
11. Build a selective refresh mask when using partial or target-only refresh.
12. Motion-compensate existing accumulators and target tracks.
13. Build sampled ROI luma and integral images.
14. Prepare color sampling state if color is active.
15. Score thermal, color, motion, and saliency cues.
16. Update ROI state and previous-frame luma state.
17. Confirm any target-gated selective revisit candidates.
18. Update per-algorithm temporal accumulators.
19. Update target tracks and movement evidence.
20. Apply publication gates.
21. Assemble normalized boxes and draw them into the RGBA buffer.

The result is a set of normalized boxes, not a durable semantic target list.
The native target-track state helps continuity and revisit planning, but the
display contract remains "draw current boxes when publication is allowed."

## Sampling and Scan Zone

The scan zone is a centered fraction of the frame. The current default is
`0.50`, so only the centered 50 percent of width and height is analyzed for
anomaly cues. Registration uses a wider ROI than anomaly scanning so camera
motion estimation is not starved by a smaller scan zone.

`pixelStep = 0` means automatic sampling. The native `effective_sample_step()`
currently uses a coarser sample step on HD/FHD input and a finer step below
that. Explicit operator detail settings override the automatic step.

The sampled ROI grid drives thermal, color, motion support, saliency, ROI-state
updates, and much of the debug output. This shared grid is why detail and frame
stride affect both cost and detection quality.

## Registration

Registration is used to estimate camera motion so target evidence can be
compared in a stabilized frame of reference.

The supported backends are:

- `GMV`: a simpler global motion vector / similarity estimator.
- `Affine`: a richer backend and the current default.

Registration can be invalid for several explicit reasons, including too few
GMV anchors, too few affine corners, too few affine matches, failed fit, high
residual, excessive motion, scale out of range, or negative determinant.

The current health enum is:

- `UNKNOWN`
- `INVALID`
- `HARD_DEGRADED`
- `SOFT_DEGRADED`
- `HEALTHY`

Health and invalid-reason information affects scan planning, saliency scoring,
track prediction, and debug output. Scene discontinuity clears ROI tracks and
can force a full refresh.

## Scan Planning and ROI State

The current analyzer has moved beyond a pure "scan everything or skip" model.
It now carries explicit sampled-ROI state:

- ROI bounds and sample step
- last luma
- thermal and temporal scores
- color luma, U, V, raw score, contrast weight, bins, phase
- valid, fresh, carried, newly exposed, color-valid, and coverage-age masks
- per-cell summaries

The scan planner can choose:

- `FULL`: rescan the full sampled scan zone.
- `PARTIAL`: carry healthy prior state and refresh selected samples.
- `TARGET_ONLY`: revisit active or recent target regions.
- `APPEARANCE_STRIDE_SKIP`: age tracks without an appearance refresh.

Full refresh is forced or favored when registration is invalid, hard-degraded,
scene-discontinuous, too much state is stale, too much of the ROI is newly
exposed, sample step changes, mask construction fails, or periodic refresh is
due. Periodic full refresh is currently bounded by both time and frame count:
`ANOMALY_FULL_RESCAN_INTERVAL_US = 333000` and
`ANOMALY_FULL_RESCAN_INTERVAL_FRAMES = 20`.

Carried state supports continuity. It should not be interpreted as equally
strong fresh evidence for creating brand-new detections.

Selective refresh is target-safe by construction. Raw interim winners outside
active target revisit gates are suppressed; they cannot reset accumulators or
create new tracks. Brand-new target creation is still tied to full refresh
frames.

Full refresh frames can seed provisional target tracks from more than the
single published winner. The current retention rule is:

- collect eligible thermal blob candidates near the normal threshold
- rank by score, isolation, quality, patch support, motion support, and compact
  target size
- keep `ceil(candidate_count * 0.25)`, clamped to a minimum of `2` when any
  candidate exists and a maximum of `4`

These retained candidates are not public detections. They exist so subsequent
partial or target-only refreshes have predicted regions to revisit.

## Thermal Path

Thermal is the default appearance path. It operates on luma, with polarity
deciding whether darker or brighter pixels are treated as hotter:

- black hot: hotter means darker-than-background
- white hot: hotter means brighter-than-background

The current thermal path has two complementary components:

- spatial local contrast from sampled-grid integral images
- temporal contrast from a one-sided EMA background model

Spatial scoring compares each sample against a local window, not the whole
frame. The hard `thermal_min_delta` gate prevents tiny compression or sensor
noise deltas in nearly uniform regions from becoming huge Z-scores. The default
native minimum is `10.0` luma units.

The thermal background model is one-sided:

- it adapts quickly toward colder / non-subject background values
- it adapts slowly toward warmer / subject-like values
- warmup is required before temporal scoring is trusted

Thermal candidates are blobbed and ranked before publishing. Candidate blob
debug overlays can draw the best plausible thermal blob separately from the
final promoted detection box.

Full-scan thermal candidates also feed the provisional target bank. The bank
uses a relaxed candidate floor below the publish threshold, but the retained
candidate is marked non-publishing. It must later receive normal confirmation
or a target-gated revisit confirmation before it can contribute to a visible
box.

## Visible-Color Path

The color path is active when appearance resolves to color or the color
algorithm bit is otherwise present. It uses the same public RGBA analyzer
boundary as IR.

Current frontend modes:

- `Legacy`
- `FreshRgba`
- `FreshYuv`

At the current analyzer boundary, `FreshYuv` cannot receive native YUV directly
from the app and effectively falls back to RGBA-derived color samples.

The color detector no longer publishes the single strangest sampled pixel.
Instead it follows this chain:

1. Prepare sampled color state.
2. Build current and recent chroma histograms.
3. Score chroma rarity, local UV support, color contrast rescue, and, where
   allowed, temporal rescue.
4. Convert raw color evidence into a patch support map.
5. Flood-fill connected supported cells into color blob candidates.
6. Reject blobs that are too large, ring-like, too broadly supported, or low
   quality.
7. Rank compact, isolated, target-scale blobs.
8. Publish the best thresholded candidate, with a raw fallback retained for
   debug.

Legacy mode remains stateful and can carry color state through selective
refresh. Fresh RGBA mode is current-frame-first at the frontend and moves
temporal influence later into candidate ranking and track support.

Color evidence can also feed unified saliency and target-track updates. In
that role, color is a spatial support cue rather than a standalone guarantee
that the visible-color detector should publish a box.

## Motion Path

Motion is computed after camera registration. The goal is not "pixel changed";
it is local residual motion that remains after global camera movement is
projected out.

The motion path:

- compares current and previous luma on a coarser motion grid
- estimates global residual statistics
- suppresses broad global motion and zoom-like changes
- evaluates compact local candidate neighborhoods
- scores texture, structure, support, registration residual standout, local
  coherence, density, and motion persistence
- stamps motion support into the saliency maps when saliency is active

`ANOMALY_ALGO_MOTION_TOLERANCE` switches the local metric toward residual
displacement rather than simple luma difference. This remains experimental.

## Movement Sidecar

The movement estimator mode is separate from the main registration backend.
The Kotlin enum currently exposes:

- `Legacy`
- `Layered Shadow`
- `Layered Active`

Native output includes movement-tile and AOI-style debug information:
confidence, parallax load, local outlier load, unstable fraction, residual
statistics, and independent/parallax/unstable counts. The sidecar can update
target-track movement evidence even when the primary display cue is thermal,
color, or saliency.

Detector scoring now consumes movement through a snapshot seam rather than by
reaching into estimator internals. The snapshot is still produced
synchronously, but the ownership boundary is intentionally async-ready:
detector scoring sees confidence, parallax load, local outlier load,
suppression scale, and tile/AOI query results through the snapshot interface.

Target-gated selective refresh uses that snapshot to grade revisits:

- spatial and temporal thermal salience can relax the threshold only inside a
  predicted target gate
- compact local motion unexplained by the global model can boost confirmation
- broad camera/global motion, parallax-like residuals, low-confidence evidence,
  or off-gate winners are suppressed

In the current validation run this path produced useful diagnostics but did not
yet pass the app-default stride-2 quality gate, so it remains behind existing
operator controls.

## Unified Saliency

Unified saliency is enabled by `saliencyEnabled`, which adds
`ANOMALY_ALGO_PERSIST`. It combines:

- thermal spatial evidence
- thermal temporal evidence when the background model is ready
- color support when color evidence exists
- stamped motion support
- registration support

The current contract is strict about motion support: saliency candidates require
usable motion-vector support. If motion or registration support is unavailable,
the saliency patch is suppressed rather than allowed to coast.

Saliency also has additional behavior beyond a single primary winner:

- tracked saliency can suppress weak jumps to a nearby new winner
- clearly stronger new winners can pull the track quickly
- one auxiliary saliency track can be maintained for a separated secondary
  candidate
- saliency display classification can choose thermal, color, motion, or
  saliency-style display attribution depending on the dominant local cue

When saliency is enabled together with thermal or color, a derived persistent
candidate can be built from the strongest thermal or color candidate plus local
patch support, motion support, and, for color, track persistence bonus.

## Temporal Stabilization

Each major displayed cue feeds a per-algorithm accumulator:

- color
- thermal
- motion
- unified saliency

Raw candidates are compared against the accumulator gate. Nearby detections
update the accumulator by EMA; distant detections reset the accumulator to the
new location unless saliency switch suppression applies. `min_hits` gates
promotion, and hold frames reduce flicker after brief misses.

The current constants include:

- EMA alpha `0.30`
- accumulator gate radius `0.15` normalized units
- hold frames `8`
- max hits `10`

`minHits = 2` means the user usually sees a box only after two analyzed-frame
confirmations, subject to publication gates.

## Target Tracks and Provisional Candidates

Native target tracks are internal continuity state. They are separate from the
public detection contract and can be used for revisit planning before they are
eligible to publish.

A target track records:

- normalized center and support geometry
- confidence, hit count, miss count, and hold count
- whether it should force a revisit
- the cue algorithm that created or confirmed it
- movement evidence accumulated from the movement sidecar
- whether it is `publish_confirmed`

The `publish_confirmed` flag is important. Full-scan provisional candidates
can create or update target tracks so the scan planner has something to
predict and revisit, but those provisional observations do not set
`publish_confirmed`. Normal raw observations and confirmed target-gated
revisits do set it. `assemble_anomaly_boxes()` ignores target tracks that have
not been publish-confirmed, even if their hit count reaches `minHits`.

Revisit gates expand while a target is provisional and tighten after
confirmation. This gives weak early candidates enough room to survive
registration prediction without allowing off-gate interim winners to create
new visible detections.

## Publication Gates

The analyzer can find raw candidates and still draw no box. Publication is
blocked when:

- anomaly detection is inactive
- the transition warmup block is active
- publish hold is active
- a scene discontinuity is present
- camera motion / registration state is unstable enough for publication holdoff
- the relevant accumulator has not reached `min_hits`

This distinction matters in debugging. A log may show a raw thermal, color, or
saliency score above threshold while `box_count` remains zero because the
publication gates correctly suppressed the visible overlay.

## Overlay and Box Semantics

Native boxes use normalized frame coordinates plus:

- RGB stroke color
- crosshair flag
- stroke weight
- algorithm id

`assemble_anomaly_boxes()` chooses visible boxes from the current accumulator
state. The caller may also request candidate-blob debug overlays. Those debug
overlays are diagnostic and do not mean the detector promoted that blob as a
stable operational detection.

The app applies the RGBA overlay back to the decoded frame before rendering.
If no box is drawn, the AD worker still may have analyzed the frame; the render
path just receives a normal unannotated frame.

## Telemetry and Debugging

The native bridge keeps session-level counters for:

- anomaly frames processed
- annotated frames
- total, max, and last anomaly processing time
- AD queue depth and max depth
- AD forwarded-without-analysis count
- AD runtime-disable count
- AD worker processed, skipped, annotated, and overlay-enqueued counts
- registration-health counts
- rescan-mode counts

`FfmpegProbeService` reads these through `nativeGetSessionPerfStats()` and
surfaces them in runtime snapshots.

Useful native log strings include:

- `anomaly config applied`
- `local playback AD branch`
- `ad worker progress`
- `anomaly frame result`
- `color dropout`
- `anomaly timing`
- `ad pressure mode`

Perfetto trace labels are available around:

- `RID2C anomaly_rgba_convert`
- `RID2C anomaly_process_frame`
- `RID2C anomaly_us`
- `RID2C reg_health`
- `RID2C rescan_mode`
- `RID2C ad_queue_depth`
- `RID2C render_queue_depth`

For on-device runtime work, use the tracked
[`capture_perfetto_trace.sh`](../tools/android_profiling/capture_perfetto_trace.sh)
helper to collect Perfetto and supporting device diagnostics.

The host harness summary JSON also records selective-refresh diagnostics:

- provisional candidates found on full refreshes
- provisional candidates retained
- target-gated revisit confirmations
- salience boosts
- independent-motion boosts
- global-motion rejections
- off-gate interim winners suppressed

## Harness Parity

The host harness compiles and calls the same C analyzer. It is the preferred
way to test detector logic in isolation because it removes Android UI, decode,
thread scheduling, and render-queue behavior from the question.

Parity requires matching at least:

- algorithm mask
- thermal polarity
- registration mode
- movement estimator mode
- frame stride
- pixel step
- score threshold / sensitivity mapping
- min hits
- scan zone
- thermal min delta
- small target fraction
- color frontend mode

Do not compare a harness run with `stride=1` and dense detail to the current app
default and call the result an app parity failure. That is a different posture.

For captured local playback review, the authoritative host posture is:

```text
tools/anomaly_test/build_timing/anomaly_video_test \
  app/src/test/resources/vidcap/PowerHouse1.mp4 \
  --app-defaults \
  --app-display-output
```

`--app-defaults` must model `defaultAnomalyConfig.forLocalPlaybackReview()`,
including fixed local playback stride `2`. The app-visible output stream is the
release qualification stream; raw detector boxes are useful for diagnosis but
are not equivalent to what the operator sees.

Two focused CTest gates now pin this:

- `anomaly_video_app_defaults_local_playback_config` must pass and confirm
  local playback stride `2`
- `anomaly_video_powerhouse1_app_local_opening_recall` currently fails and is
  the app-equivalent PowerHouse1 opening regression gate

## Current Caveats

- The current app default is detection off and, when enabled from defaults,
  uses thermal plus motion support. Captured local playback review applies
  fixed `frameStride = 2` through `forLocalPlaybackReview()`.
- Selective refresh and target-only stride behavior are implemented for
  diagnostics and experimentation, but not promoted to realtime defaults. In
  the latest focused gate, stride-2 improved runtime and kept the Team early
  false-positive window clean, but still missed the PowerHouse1 opening target.
- As of the 2026-06-14 PowerHouse1 handoff, app-local playback AD is still
  regressed. The host app-local opening gate reports `0/108` reviewed target
  hits and `0/1` matched tracks for
  `app/src/test/resources/vidcap/PowerHouse1.mp4`.
- Adaptive stride has native controller support and harness summary telemetry,
  but remains experimental. The current controller can shorten quickly on
  registration, target-track, weak-lock, and target-rich signals; focused
  PowerHouse samples stayed pinned at stride 2 and did not preserve fixed
  stride-1 recall well enough for a default change.
- The public analyzer boundary is RGBA. Color `FreshYuv` is named but not yet a
  true YUV-fed app path.
- Saliency is stricter than older behavior: it requires motion / registration
  support and clears or suppresses tracks when that support is unavailable.
- AD pressure handling can cause analyzed-frame cadence to differ from the
  operator's configured stride.
- "Raw candidate above threshold" and "visible box drawn" are different states.
  Always check publication gates, accumulator hits, holdoff, and `box_count`.
- UI persistence spans `AnomalyConfig`, `AnomalyPrefs`, `StreamsViewModel`, and
  `toNativeConfig()`. A new user-facing AD setting is incomplete unless it is
  wired through all of those layers and the native config if needed.

## Practical Debugging Order

When a detector symptom appears, isolate the layer first:

1. Confirm the applied config in logs: enabled, mask, registration, stride,
   pixel step, threshold, min hits, scan zone, color frontend.
2. Confirm whether the frame reached the AD worker or bypassed due to disabled
   AD, missing thread, queue pressure, thermal pause, or overload runtime
   disable.
3. Check whether `anomaly_process_frame()` ran and how long it took.
4. Check registration health, invalid reason, and rescan mode.
5. Compare raw cue scores against threshold.
6. Check accumulator hits and publication gates.
7. Only then tune cue scoring or thresholds.

That order keeps UI/config bugs, FFmpeg queue behavior, and detector-core
behavior from being mixed into one misleading "AD failed" bucket.

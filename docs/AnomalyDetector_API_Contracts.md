# AnomalyDetector API Contracts

This document captures the current native AnomalyDetector control, runtime, and
result contracts for the modularization effort. It is descriptive first: runtime
behavior should not change merely because this contract is written down.

## Runtime Contract

The stand-alone detector core should remain a plain C module with this lifecycle
shape:

- `anomaly_detector_state_init()`: initialize caller-owned detector state.
- `anomaly_detector_state_reset()`: discard temporal detector evidence before a new
  stream, replay discontinuity, or reset-sensitive config transition.
- `anomaly_detector_process()`: process one ordered frame/config pair and fill a
  structured result.
- `anomaly_detector_state_cleanup()`: release detector-owned heap allocations.

These facade names currently forward to the existing `anomaly_state_*()` and
`anomaly_process_frame()` implementation. The old symbols remain available for
current app and harness callers while the stand-alone boundary settles.
`anomaly_detector_process_with_args()` exposes the same processing path through
an `anomaly_detector_process_args_t` bundle so future adapters can pass the
state, frame, config, and result contract as one named unit.
`anomaly_detector_process_args_make()` is the canonical helper for constructing
that bundle while preserving optional null fields for current fallback paths.
`anomaly_detector_process_args_frame_ready()` names the subset of that bundle
required to process frame pixels: a non-null detector state plus a processable
frame; config and result output remain optional according to current core
fallback behavior.

The current `anomaly_process_frame()` mutates `anomaly_state_t` and draws
overlays in-place on the RGBA frame. A future stand-alone API may make overlay
drawing adapter-owned, but replay and annotation tools must still be able to
recover the same detection boxes and debug metadata from `anomaly_result_t`.
`anomaly_detector_process_args_may_annotate_frame()` exposes the current
in-place annotation predicate for facade callers: a ready structured call with
either hot overlay enabled or anomaly detection enabled may modify the input
RGBA frame.
`anomaly_detector_process_args_frame_output()` exposes the current annotated
frame output view for facade callers. For ready structured calls, it aliases
the input RGBA buffer and carries stride, dimensions, source timestamp, and
whether annotations may be present in-place under the current config. Unready
calls produce an empty output view.
`anomaly_detector_result_annotations()` exposes the current publishable
annotation view for standalone consumers: null, zero, or negative result box
counts produce an empty view; positive counts expose `anomaly_result_t.boxes`
bounded to `ANOMALY_MAX_BOXES_PER_FRAME`.
`anomaly_detector_process_output()` combines the frame-output view and
annotation view into one processed-output contract so standalone adapters can
consume the current RGBA output and published annotations together without
duplicating facade composition rules.
`anomaly_detector_process_frame()` is the first facade call shaped like the
standalone one-frame runtime: it processes a structured frame/config/state
bundle through the existing core path, optionally reports the legacy box-count
return value, and returns the combined processed-output view.
`anomaly_detector_process_frame_apply_annotation_cadence()` composes that
one-frame process call with the annotation cadence snapshot policy in a single
adapter-facing call. It reports the raw detector box count separately while
returning frame output plus cadence-stabilized annotations.
`anomaly_detector_process_frame_input()` exposes the same one-frame
frame-in/annotated-frame-out shape through positional state, frame, config,
and result pointers for adapters that do not need to construct an args bundle
explicitly.
`anomaly_detector_runtime_t` is the first owned standalone runtime facade. It
keeps detector state, realtime default config, result storage, annotation
cadence snapshot state, frame ordinal, cadence window, and the last raw
detector box count together so a native adapter can initialize once and then
submit sequential frames through
`anomaly_detector_runtime_process_frame()`. Runtime processing returns the
current frame output with cadence-stabilized annotations and advances the frame
ordinal after each call.
`anomaly_detector_runtime_process_frame_result()` exposes the same runtime
processing path with per-frame metadata: processed frame ordinal, raw detector
box count, stable annotation count, cadence window, and whether stabilized
annotations are visible. The output-only runtime call delegates to this richer
result helper.
`anomaly_detector_runtime_init_with_config()` initializes the same owned
runtime from a caller-supplied native config and explicit annotation cadence
window. It copies the config, clears owned result/cadence bookkeeping, starts
the frame ordinal at zero, and clamps invalid cadence values to one frame.
`anomaly_detector_runtime_apply_config()` applies a caller-supplied config to
an already-initialized runtime using the public config-transition classifier.
Display/debug/live-update transitions preserve runtime sequence state while
reset-sensitive transitions reset owned detector state, result storage,
annotation cadence state, frame ordinal, and the last raw detector count.
Runtime read-only accessors expose the current config, result, stable
annotation snapshot, next frame ordinal, cadence window, and last raw detector
box count without requiring adapters to depend on `anomaly_detector_runtime_t`
field layout.
The Android FFmpeg bridge applies annotation cadence to normal operator
overlays as a visibility policy: raw detector boxes are still computed every
analyzed frame, visible ROI coordinates ease toward nearby same-algorithm raw
boxes, and only appearance/disappearance is held to cadence boundaries. Skipped
AD worker frames may draw the current held annotation snapshot so normal
operator overlays do not blink off simply because analysis was bypassed for
stride or pressure. Troubleshooting hot/candidate overlay modes keep the legacy
raw in-place draw path so diagnostic visuals remain frame-exact.

Callers should treat reset-sensitive config changes as requiring
`anomaly_state_reset()` unless a narrower transition is explicitly documented.
Current Android/JNI behavior does not reset for every config edit; it often
copies the next-frame config while retaining old detector state. That is a
current adapter behavior, not the final published API policy.

The native API exposes `anomaly_config_transition_classify()` as an advisory
config-transition classifier so adapters can compare old/new configs without
duplicating field knowledge. The classifier is policy metadata first; adopting
it in the Android bridge is a separate behavior-changing step.

`anomaly_detector_process()` currently accepts only
`ANOMALY_FRAME_FORMAT_RGBA8888` frames. Unsupported formats are rejected through
the same zero-work result-initialization path rather than converted in the core;
future YUV or native-camera frame support should arrive as an explicit input
contract change. `anomaly_detector_frame_input_ready()` exposes this current
processable-frame predicate so adapters and harnesses can share the same
RGBA-only readiness rule without duplicating facade internals.

## Config Contract

`anomaly_detector_default_window_frames()` converts a caller-supplied frame
rate into the default realtime window used by standalone native config helpers.
The default window is 0.5 seconds: 30 fps maps to 15 frames, fractional rates
round to the nearest frame, low rates keep at least one frame, and invalid
rates fall back to 30 fps.
`anomaly_detector_config_make_realtime_default()` returns a native realtime
default config for a selected algorithm mask and frame rate. This helper is
for standalone/native adapters; current Android preference defaults are still
owned by `AnomalyConfig`/`AnomalyPrefs` and are not changed by this contract.
`anomaly_detector_annotation_cadence_allows_update()` exposes the standalone
annotation-output cadence policy: frame ordinal zero may publish, subsequent
visible annotation-state changes are allowed only on cadence-window boundaries
such as the 15-frame half-second window at 30 fps. Invalid cadence values fall
back to every-frame updates so adapters do not divide by bad input.
`anomaly_detector_annotation_cadence_state_t` plus
`anomaly_detector_annotation_cadence_update_visibility()` provide the stateful
adapter primitive for that policy: adapters may compute desired annotation
visibility every frame, but the visible output state is held between cadence
boundaries so annotations do not appear or disappear faster than the configured
window.
`anomaly_detector_annotation_cadence_snapshot_state_t` and
`anomaly_detector_annotation_cadence_update_snapshot()` extend that policy to
annotation boxes: adapters can retain a stable annotation view between cadence
boundaries, refresh boxes at allowed boundaries, and clear boxes only when
disappearance is allowed.
`anomaly_detector_annotation_cadence_snapshot_view()` exposes the current held
snapshot without changing visibility or detector state.
`anomaly_detector_result_apply_annotation_visibility_cadence()` applies the
visibility policy to result boxes without freezing moving annotations: when
annotations are visible and the current result still has boxes, nearby
same-algorithm boxes are smoothed toward the latest raw boxes every frame;
large jumps snap to the latest raw box; when the current result has no boxes,
the last visible boxes are held only until disappearance is allowed.
`anomaly_detector_process_output_apply_annotation_cadence()` applies that
snapshot policy to a processed-output view: it preserves the frame output and
replaces only the annotation view with cadence-stabilized annotations.

Current native config fields map to these transition classes.

| Field | Class | Contract note |
| --- | --- | --- |
| `enabled` | live processing | Gates processing. Enable/disable may reset through the current bridge runtime path. |
| `show_hot_overlay` | display-only | Affects overlay rendering, not detector evidence. |
| `show_candidate_blobs` | display-only | Adds visible candidate/debug boxes, not scoring state. |
| `algorithm_mask` | reset-sensitive processing | Changes active modal/additive paths and publication semantics. |
| `registration_mode` | reset-sensitive processing | Changes camera-motion model; ROI carry-forward and target prediction may be stale. |
| `movement_estimator_mode` | reset-sensitive processing | Changes motion evidence consumed by tracks and debug. |
| `stride_mode` | reset-sensitive processing | Changes planner cadence and adaptive state interpretation. |
| `frame_stride` | reset-sensitive processing | Changes full-refresh cadence and local playback bypass cadence. |
| `adaptive_min_stride_frames` | reset-sensitive processing | Changes adaptive planner bounds while adaptive counters/EMA persist. |
| `adaptive_max_stride_frames` | reset-sensitive processing | Changes adaptive planner bounds while adaptive counters/EMA persist. |
| `adaptive_max_stride_seconds` | reset-sensitive processing | Changes time-derived adaptive cap policy. |
| `pixel_step` | reset-sensitive processing | Changes sampled-grid resolution and cached appearance state. |
| `score_threshold` | live processing | Changes score gates without invalidating geometry/state by itself. |
| `motion_evidence_scale` | live processing | Scales motion evidence without invalidating buffers by itself. |
| `min_area_fraction` | reset-sensitive processing | Changes target span, box sizing, and track support assumptions. |
| `thermal_polarity` | reset-sensitive processing | Flips thermal meaning; background and thermal history become semantically stale. |
| `scan_zone` | reset-sensitive processing | Changes ROI/sample geometry; tracks may remain from old zone. |
| `min_hits` | live processing, ambiguous | Existing hit counts remain numeric, but their publish meaning changes. Prefer a documented transition before publishing. |
| `thermal_min_delta` | live processing | Changes thermal delta gate without invalidating geometry/state by itself. |
| `small_target_screen_fraction` | reset-sensitive processing | Changes sample-step cap and target apparent-size scale. |
| `color_frontend_mode` | reset-sensitive processing | Changes color sampling/scoring semantics while color history may persist. |
| `thermal_debug_target_enabled` | debug/telemetry-only | Enables target tracing. |
| `thermal_debug_target_x_norm` | debug/telemetry-only | Target trace coordinate. |
| `thermal_debug_target_y_norm` | debug/telemetry-only | Target trace coordinate. |
| `color_debug_target_enabled` | debug/telemetry-only | Enables color target tracing. |
| `color_debug_target_x_norm` | debug/telemetry-only | Target trace coordinate. |
| `color_debug_target_y_norm` | debug/telemetry-only | Target trace coordinate. |

Reset-sensitive stale state can include:

- per-algorithm accumulators and color promotion tracks,
- previous luma and previous registration luma,
- motion persistence,
- thermal background and thermal target persistence,
- color recent history,
- ROI sample state and carry-forward masks,
- target tracks,
- cached registration,
- publish/adaptive stride state,
- saliency auxiliary tracks.

## Current Adapter Behavior

Today the Android app owns UI/default/persistence policy. Kotlin builds a
`NativeAnomalyConfig`, `FfmpegProbeService` applies it to active render
sessions, and `ffmpeg_bridge.c` copies every field into `session->anomaly_cfg`
under the bridge lock.

Current native bridge reset behavior is narrower than the reset-sensitive list
above. `reconfigure_anomaly_mode()` resets detector state for runtime
enable/pause/disable/thread-start transitions. Most tuning changes while AD
remains enabled are next-frame config updates with old detector state retained.

Local playback step/back replay is a special downstream adapter path: the bridge
resets anomaly state while replaying history frames so frame-step review does
not inherit future-frame ROI state.

## Result And Debug Contract

Frame-step replay, manual annotation, native harness review, and debug
forensics are downstream consumers. The stand-alone detector should preserve
structured access to:

- `anomaly_result_t.boxes[]`, `box_count`, algorithm id, normalized box
  coordinates, and box weight,
- source-frame timestamp supplied by the caller and discontinuity reporting,
- `registration_health`, `rescan_mode`, `scan_plan`, and adaptive stride fields,
- registration/GMV debug, movement debug, motion debug, thermal debug, color
  debug, saliency debug, and timing,
- target-trace inputs and outputs for thermal and color debugging,
- stable timing stage names including separate scan-planning and refresh-mask
  buckets.

Adapters may turn these fields into annotated video, detection CSV, summary
JSON, JSONL traces, or app debug summaries. The core detector should not depend
on those adapter formats.
The host video harness can now emit either raw detector annotations or the
app-display publication stream. `anomaly_video_test --app-display-output`
restores a clean decoded frame after analysis, applies the facade visibility
cadence and ROI smoothing policy, redraws only the app-visible boxes, and writes
those displayed boxes to CSV/video so display stability can be scored without
watching the Android app.

## Motion Evidence Contract

MotionEstimator producer outputs should be reusable evidence, not detections.
The first adopted producer shape is a read-only movement snapshot view over the
registration-backed movement sidecar. Its current backing store may still be
`anomaly_debug_movement_t`, but consumers should depend on the snapshot/query
contract rather than on local helper structs in `anomaly_analysis.c`.

The movement snapshot contract should preserve:

- global confidence, parallax load, local-outlier load, and suppression scale,
- normalized tile queries for appearance detectors and revisit confirmation,
- stable tile class semantics for background/parallax/local-outlier/unstable,
- caller-owned lifetime; no hidden heap ownership, queues, or worker threads.

Appearance-proposal motion scoring is a separate later contract because it is
seeded by IR/Color evidence and should not be confused with camera-motion
estimation itself.

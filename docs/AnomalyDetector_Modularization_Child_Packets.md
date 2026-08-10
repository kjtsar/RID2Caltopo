# AnomalyDetector Modularization Child Packets

Use these packets for child threads. The parent owns sequencing and integration.

## Packet 1: Public Interface Inventory

Mode: inspect-only.

Ownership:

- `app/src/main/cpp/anomaly_analysis.h`
- `app/src/main/cpp/ffmpeg_bridge.c`
- Kotlin config/update callsites under `app/src/main/java/org/ncssar/rid2caltopo/video/anomaly/`
  and `app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/`

Task:

Map the current public/native AnomalyDetector consumer interface. Separate
stand-alone detector concepts from Android/FFmpeg adapter concepts.

Deliverable:

- Proposed `anomaly_frame_input_t`, `anomaly_detector_config_t`, and
  `anomaly_detector_result_t` field groups.
- Existing code anchors for every proposed field group.
- List of adapter-only concerns that must not leak into the stand-alone module.
- No code changes.

Validation:

- None required beyond code anchors.

## Packet 2: MotionEstimator Producer Inventory

Status: complete by explorer pass.

Mode: inspect-only.

Ownership:

- Motion-estimator sections of `app/src/main/cpp/anomaly_analysis.c`
- Movement, registration, and motion debug structs in `app/src/main/cpp/anomaly_analysis.h`

Task:

Map the current MotionEstimator producer seam. Focus on registration model,
movement snapshot, layered movement sidecar, saliency motion map, motion
candidates, and consumers in IR/Color/tracking.

Deliverable:

- Proposed `anomaly_motion_estimator_input_t`,
  `anomaly_motion_estimator_config_t`, and
  `anomaly_motion_estimator_result_t` field groups.
- Exact anchors to current functions/structs.
- First safe extraction boundary.
- Performance risks and measurement points.
- No code changes.

Validation:

- None required beyond code anchors.

Parent notes from completed explorer:

- Treat the registration-backed movement producer and the appearance-proposal
  motion scorer as separate seams.
- First extraction should focus on `MovementSnapshot`: layered movement sidecar,
  movement tile queries/classification, and debug movement output.
- Keep registration as an input dependency rather than nesting registration
  inside MotionEstimator.
- Defer the motion-candidate scorer because it depends on IR/Color maps and
  writes support maps consumed by multiple paths.
- Avoid duplicating residual patch search or making motion follow dense
  appearance pixel steps.

## Packet 3: Interface Header Skeleton

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving.

Ownership:

- New internal headers only, likely:
  - `app/src/main/cpp/anomaly_frame.h`
  - `app/src/main/cpp/anomaly_detector_internal.h`
  - `app/src/main/cpp/anomaly_motion_estimator.h`

Task:

Add skeletal internal interface headers that name the consumer and producer
contracts without changing `anomaly_process_frame()` behavior. Use aliases or
field comments where the implementation is not ready to move yet.

Minimum contents:

- `anomaly_frame_input_t`: names RGBA pointer, stride, dimensions, source
  timestamp, and future frame-format slot.
- `anomaly_detector_*` aliases or wrappers for current state/config/result.
- `anomaly_motion_estimator_*` skeletons that distinguish registration-backed
  movement snapshot output from later appearance-proposal motion scoring.
- Comments that identify adapter-only responsibilities that stay in
  `ffmpeg_bridge.c` and Kotlin.

Constraints:

- Do not move scoring, registration, scan planning, or tracking logic.
- Do not change public JNI/Kotlin behavior.
- Keep headers plain C and free of Android/FFmpeg dependencies.
- Do not create thread queues or async behavior.

Validation:

- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- `./gradlew :app:compileDebugKotlin`

Parent notes from completed worker:

- Added skeletal contracts in:
  - `app/src/main/cpp/anomaly_frame.h`
  - `app/src/main/cpp/anomaly_detector_internal.h`
  - `app/src/main/cpp/anomaly_motion_estimator.h`
- These headers are intentionally not yet included by production sources.
- They name the future frame, detector, movement snapshot, and
  appearance-proposal motion scoring contracts without moving behavior.

## Packet 4: MotionEstimator Wrapper Proof

Status: complete by worker pass and parent movement comparison.

Mode: code change, behavior-preserving, after Packet 2 and Packet 3.

Ownership:

- New MotionEstimator source/header files.
- Narrow callsite around current registration/movement-estimator block in
  `app/src/main/cpp/anomaly_analysis.c`.

Task:

Wrap the existing registration and layered movement sidecar calculations behind
a MotionEstimator facade while preserving all current maps, debug fields, and
timing stages.

Constraints:

- No algorithm changes.
- No new threading.
- No frame copies.
- Preserve `ANOMALY_TIMING_STAGE_REGISTRATION_*` and
  `ANOMALY_TIMING_STAGE_MOVEMENT_ESTIMATOR` semantics.
- Preserve legacy, layered-shadow, and layered-active modes.

Validation:

- Native/unit harness.
- IR regression manifest.
- `tools/anomaly_test/run_movement_estimator_comparison.py`
- Timing comparison against the parent baseline.

Parent notes from completed worker:

- Added a local `anomaly_motion_estimator_estimate_sidecar()` facade around the
  existing registration-backed layered movement sidecar.
- Kept registration as an input dependency.
- Did not move the appearance-seeded motion-candidate scorer.
- Did not add new threading, frame copies, or CMake targets.
- Parent movement comparison wrote
  `/tmp/rid2c_motion_estimator_wrapper_comparison` and kept shadow-mode
  detection equality across the comparison matrix.

## Packet 5: MotionEstimator Private Boundary

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 4.

Ownership:

- `app/src/main/cpp/anomaly_motion_estimator.h`
- Optional new private implementation file
  `app/src/main/cpp/anomaly_motion_estimator.c`
- Narrow declarations/callsite in `app/src/main/cpp/anomaly_analysis.c`
- CMake source lists only if a compiled file is added.

Task:

Turn the local Packet 4 facade into a real private MotionEstimator boundary.
Keep the first extraction focused on the registration-backed movement sidecar
and leave the appearance-seeded motion-candidate scorer in place.

Constraints:

- No algorithm changes.
- No new threading.
- No frame copies.
- Keep registration as an input dependency.
- Do not force a broad public registration API while
  `anomaly_registration_model_t` is still private to `anomaly_analysis.c`.
- Preserve `ANOMALY_TIMING_STAGE_MOVEMENT_ESTIMATOR` semantics.
- Preserve legacy, layered-shadow, and layered-active modes.
- Keep the code Android/FFmpeg-free.

Validation:

- Native/unit harness.
- Movement estimator comparison harness.
- Android debug Kotlin/native build if CMake/app source lists change.

Parent notes:

- If extracting the full sidecar requires exposing too many private helpers,
  prefer a smaller internal boundary that still improves the separation over
  the Packet 4 local facade.
- Do not duplicate residual patch search. This packet is about ownership and
  contracts, not extra work per frame.

Worker checkpoint:

- Moved the registration-backed layered movement sidecar implementation into
  `app/src/main/cpp/anomaly_motion_estimator.c`.
- Kept `anomaly_registration_model_t` private to `anomaly_analysis.c` by passing
  an opaque registration pointer plus private projection, validity, and residual
  displacement callbacks through `anomaly_motion_estimator.h`.
- Left the appearance-seeded motion-candidate scorer in `anomaly_analysis.c`.
- Wired the new compiled unit into app and anomaly-test CMake targets.
- Validation passed:
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  - `tools/anomaly_test/build_timing/anomaly_test`
  - `python3 tools/anomaly_test/run_movement_estimator_comparison.py --binary tools/anomaly_test/build_timing/anomaly_video_test --output-dir /tmp/rid2c_packet5_motion_estimator_comparison`
  - `./gradlew :app:compileDebugKotlin`
- Movement comparison output directory:
  `/tmp/rid2c_packet5_motion_estimator_comparison`; all rows reported
  `shadow_equal=yes`.

## Packet 6: ScanPlanner Seam Inventory

Status: complete by explorer pass.

Mode: inspect-only.

Ownership:

- Scan-planning and adaptive-stride sections of
  `app/src/main/cpp/anomaly_analysis.c`
- Scan-plan, rescan-mode, scan-reason, and adaptive-stride result/debug structs
  in `app/src/main/cpp/anomaly_analysis.h`
- This parent packet document.

Task:

Map the current scan-planning seam before moving code. Separate the planner
decision from the surrounding producers and consumers:

- cadence and adaptive effective stride,
- previous-sample lookup and registration health refinement,
- full / partial / target-only / stride-skip plan selection,
- selective refresh-mask construction,
- color stride-hold and color fallback force-full behavior,
- result/debug field publication.

Deliverable:

- Exact functions, structs, constants, and callsites that belong to a future
  ScanPlanner module.
- Inputs grouped as frame/config/state/motion/registration/tracking/color
  fallback dependencies.
- Outputs grouped as plan values, state mutations, refresh-mask products, and
  result/debug fields.
- First safe behavior-preserving extraction boundary.
- Risk list for black-hot IR and Red1 visible-color behavior.
- Validation commands for the first extraction.

Constraints:

- Inspect only until the dependency list is clear.
- No algorithm tuning.
- Do not change adaptive-stride behavior or promote adaptive mode.
- Do not change 80% scan-zone posture.
- Do not change Red1 rarity/common-artifact logic in this packet.
- Keep MotionEstimator and ScanPlanner write surfaces disjoint.

Parent notes:

- The first likely implementation should be a private contract or wrapper, not
  a broad move of all ROI state, target tracking, or color fallback code.
- `build_selective_refresh_mask()` and `build_scan_plan()` are planner-shaped,
  but both depend on ROI state and target-revisit summaries. Prefer explicit
  input structs or callbacks over moving unrelated state ownership.
- Color fallback can still force `ANOMALY_RESCAN_MODE_FULL` after initial plan
  selection, so the first extraction must either leave that post-plan mutation
  in `anomaly_analysis.c` or name it as a separate planner finalization step.

Explorer checkpoint:

- Candidate ScanPlanner functions are `compute_adaptive_effective_stride()`,
  `build_scan_plan()`, `build_selective_refresh_mask()`,
  `periodic_full_refresh_due()`, and the previous-sample lookup / refresh-mask
  block in `anomaly_process_frame()`.
- First safe extraction boundary is the block from effective stride through
  refresh-mask construction. Do not move ROI update, target tracking, or
  `prepare_color_sampling_state()` in the first implementation packet.
- Red1 risk: planner mode is not the same as actual fresh-color coverage,
  because color sampling can force a full refresh downstream.
- IR risk: selective refresh controls where thermal scoring runs, so mask
  build and target-revisit semantics are behavior-bearing.
- Adaptive-stride risk: `compute_adaptive_effective_stride()` reads and mutates
  state; preserve order around full-refresh cadence, target-rich history, and
  registration caching.

## Packet 7: ScanPlanner Contract Header

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 6.

Ownership:

- New private header `app/src/main/cpp/anomaly_scan_planner.h`
- Optional compile-coverage include in `app/src/main/cpp/anomaly_analysis.c`

Task:

Name the private ScanPlanner input/output contract without moving behavior.
This should prepare the later wrapper around adaptive stride, scan-plan
selection, color stride hold, full-refresh bookkeeping, and refresh-mask
construction.

Minimum contents:

- Opaque registration typedef so registration internals stay private.
- Input struct covering state/config, source timestamp, frame counter, ROI,
  sample grid, scene discontinuity, cadence booleans, color stride-hold
  eligibility, previous lookup, movement sidecar, and selective-refresh storage.
- Output struct covering adaptive result fields, final rescan mode, scan plan,
  refined registration health, appearance refresh mask, selective-refresh
  telemetry, color stride-hold status, and mask-failure force-full status.
- Comment that `prepare_color_sampling_state()` remains downstream and may
  still force full refresh after initial planning.

Constraints:

- Plain C only; no Android, JNI, FFmpeg, or playback dependencies.
- No algorithm changes.
- No new `.c` file yet.
- Do not move ROI update, target tracking, color sampling, adaptive-stride
  logic, or selective-refresh-mask logic.

Validation:

- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`

Worker checkpoint:

- Added `app/src/main/cpp/anomaly_scan_planner.h`.
- Added a compile-coverage include in `app/src/main/cpp/anomaly_analysis.c`.
- Worker and parent validation passed:
  - `cmake --build tools/anomaly_test/build_timing`
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  - `tools/anomaly_test/build_timing/anomaly_test`
  - `./gradlew :app:compileDebugKotlin`

## Packet 8: ScanPlanner Private Wrapper

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 7.

Ownership:

- `app/src/main/cpp/anomaly_scan_planner.h`
- New private implementation file `app/src/main/cpp/anomaly_scan_planner.c`
- Narrow callsite and private helper declarations in
  `app/src/main/cpp/anomaly_analysis.c`
- App and anomaly-test CMake source lists for the new compiled unit.

Task:

Implement `anomaly_scan_planner_plan()` as the first private ScanPlanner
wrapper around the existing planning block. The wrapper should cover adaptive
effective stride through refresh-mask construction, while leaving ROI updates,
target tracking, scoring, and color sampling in `anomaly_analysis.c`.

Required behavior to preserve:

- adaptive effective stride and reason flags,
- full-refresh cadence and periodic full-refresh checks,
- previous-sample lookup and registration-health refinement,
- full / partial / target-only / appearance-stride-skip plan selection,
- color stride-hold behavior,
- last-full-refresh timestamp/frame bookkeeping,
- selective refresh-mask selected-count/fraction and fallback reasons,
- result-facing adaptive and scan-plan fields.

Constraints:

- No algorithm tuning.
- No new threading, queues, or frame copies.
- Do not move `prepare_color_sampling_state()`; it remains downstream and may
  still force full refresh after initial planning.
- Do not move ROI update, target tracking, thermal scoring, color scoring, or
  motion/saliency scoring.
- Keep registration internals private; use an opaque pointer/callback style if
  the wrapper needs registration validity or lookup construction.
- Keep core code plain C and Android/FFmpeg-free.

Validation:

- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent should also run `./gradlew :app:compileDebugKotlin` because a new
  native compiled unit changes the Android build graph.

Parent acceptance notes:

- If the first wrapper needs too many callbacks, prefer a smaller wrapper that
  still captures adaptive stride plus scan-plan selection over exposing broad
  ROI or registration internals.
- Full IR and Red1 manifests are recommended before treating this as a stable
  modularization checkpoint, because scan planning affects both thermal
  coverage and visible-color refresh behavior.

Worker checkpoint:

- Added `app/src/main/cpp/anomaly_scan_planner.c`.
- Implemented `anomaly_scan_planner_plan()`.
- Moved adaptive stride, scan-plan selection, full-refresh cadence
  bookkeeping, color stride hold, and selective refresh-mask fallback behind
  the private ScanPlanner wrapper.
- Kept registration private through a narrow ops callback table.
- Left ROI updates, target tracking, scoring, color sampling prep, threading,
  and frame buffers in `anomaly_analysis.c`.

Parent review notes:

- Preserved separate debug timing buckets by having ScanPlanner return
  `scan_planning_elapsed_us` and `refresh_mask_elapsed_us`; parent publishes
  them to the existing timing stages.
- Refresh-mask timing remains populated on selective runs and remains zero on
  full-scan runs.

Parent validation passed:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- `./gradlew :app:compileDebugKotlin`
- IR manifest:
  `/tmp/rid2c_packet8_scanplanner_ir/suite_report.md`
  - current baseline: `55/279` TP, `2/92` FP
  - dense gold: `101/279` TP, `0/92` FP
  - redesigned incremental: `167/279` TP, `1/92` FP
- Red1 visible-color manifest:
  `/tmp/rid2c_packet8_scanplanner_color/suite_report.md`
  - visible-color baseline: `0/15` TP, `0/11` FP
  - visible-color dense gold: `15/15` TP, `0/11` FP

## Packet 9: Active-Control Contract Inventory

Status: complete by explorer pass and parent documentation.

Mode: inspect-only / documentation, behavior-preserving, after Packet 8.

Ownership:

- `app/src/main/cpp/anomaly_analysis.h`
- `app/src/main/cpp/ffmpeg_bridge.c`
- Kotlin anomaly config and local playback callsites under
  `app/src/main/java/org/ncssar/rid2caltopo/`
- Native harness replay/annotation/debug consumers under `tools/anomaly_test/`
- Parent architecture document.

Task:

Classify current detector controls so the future stand-alone module can define
what happens when public AD control and display parameters change while the AD
is active.

Deliverable:

- Classification for every `anomaly_config_t` field:
  - live processing control,
  - reset-sensitive processing control,
  - display-only control,
  - debug/telemetry-only control.
- For reset-sensitive controls, list stale state risks such as background
  model, ROI state, target tracks, color history, registration cache, motion
  persistence, adaptive cadence, and accumulator state.
- Current JNI/Kotlin apply path and whether it resets detector state today.
- Replay, frame-step, manual annotation, and native-harness debug consumers.
- Debug hooks to preserve in the stand-alone detector result/debug contract.

Constraints:

- No runtime behavior changes.
- No algorithm tuning.
- Do not remove or narrow debug payloads.
- Treat current app behavior as evidence, but do not assume it is the final
  stand-alone API policy.
- Keep annotation and frame-step tools downstream of AD while preserving the
  result/debug fields they need.

Parent notes:

- Be conservative: if changing a field alters sample geometry, source modality,
  temporal evidence, target persistence, or registration/motion cadence, mark
  it reset-sensitive until a narrower transition is proven.
- Display-only fields should not change evidence or tracking.
- Debug/telemetry fields should not change detections unless explicitly marked
  intrusive.

Explorer checkpoint:

- Current Android/JNI config application copies every field into
  `session->anomaly_cfg`, but reset is currently tied mostly to runtime
  enable/pause/disable/thread-start changes rather than every tuning edit.
- Local playback step/back replay resets anomaly state while replaying history
  frames so review does not inherit future-frame ROI state.
- Manual annotation is downstream of AD: it records normalized point, verdict
  metadata, source timestamp, and current anomaly debug summary.
- Native harness replay consumes `result.boxes[]`, scan/adaptive fields,
  registration/movement/thermal/color/saliency debug, and timing fields.

Parent output:

- Added `docs/AnomalyDetector_API_Contracts.md`.
- Parent contract now explicitly separates runtime lifecycle, config transition
  classes, current adapter behavior, and result/debug consumers.

## Packet 10: Native Config-Transition Classifier

Status: complete.

Mode: code change, behavior-preserving, after Packet 9.

Ownership:

- Public native detector header/source for an advisory transition classifier.
- `tools/anomaly_test/test_anomaly.c`
- CMake source lists only if a new compiled unit is added.

Task:

Turn the Packet 9 config transition contract into a small native helper that
adapters and future stand-alone API callers can use to compare two
`anomaly_config_t` values.

Required behavior:

- Return an enum describing the strongest transition required:
  unchanged, display-only, debug-only, live processing update, or detector
  reset/reinitialize.
- Null inputs should require reset/reinitialize.
- Reset-sensitive fields win over live/display/debug fields.
- This helper is advisory only in this packet; do not wire it into
  `ffmpeg_bridge.c` or change runtime reset behavior.

Field mapping:

- Display-only: `show_hot_overlay`, `show_candidate_blobs`.
- Debug-only: thermal/color debug target enables and coordinates.
- Live processing: `enabled`, `score_threshold`, `motion_evidence_scale`,
  `thermal_min_delta`, `min_hits`.
- Reset-sensitive: `algorithm_mask`, `registration_mode`,
  `movement_estimator_mode`, `stride_mode`, `frame_stride`,
  `adaptive_min_stride_frames`, `adaptive_max_stride_frames`,
  `adaptive_max_stride_seconds`, `pixel_step`, `min_area_fraction`,
  `thermal_polarity`, `scan_zone`, `small_target_screen_fraction`,
  `color_frontend_mode`.

Validation:

- Native build and unit harness.
- Focused unit tests for unchanged, display-only, debug-only, live-processing,
  reset-sensitive, reset-wins, and NULL handling.

Parent notes:

- This packet makes the published-module policy testable without changing the
  app's current next-frame config application behavior.
- Later bridge work can choose when to honor this classifier with explicit
  resets, but that adoption should be a separate behavior-changing packet.

Worker checkpoint:

- Added `anomaly_config_transition_t` and
  `anomaly_config_transition_classify()` to the public native detector header.
- Implemented strongest-transition classification in `anomaly_analysis.c`.
- Added focused native harness tests for unchanged, display-only, debug-only,
  live-processing, reset-sensitive, reset-wins, and NULL handling.
- Deliberately left `ffmpeg_bridge.c` and runtime reset behavior unchanged.
- Parent validation: `git diff --check`, native build, `ctest`, native
  `anomaly_test`, and `./gradlew :app:compileDebugKotlin`.

## Packet 11: Consumer Facade Header

Status: complete.

Mode: code change, behavior-preserving, after Packet 10.

Ownership:

- `app/src/main/cpp/anomaly_detector.h`
- `app/src/main/cpp/anomaly_detector.c`
- Native CMake source lists.
- Focused native harness tests.

Task:

Add the smallest consumer-facing C facade for future stand-alone anomaly
detector users while preserving the current `anomaly_process_frame()` API and
runtime behavior.

Implemented facade:

```c
void anomaly_detector_state_init(anomaly_detector_state_t *state);
void anomaly_detector_state_reset(anomaly_detector_state_t *state);
void anomaly_detector_state_cleanup(anomaly_detector_state_t *state);

int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out);
```

Worker checkpoint:

- Added public-ish alias names and lifecycle functions for state/config/result
  in `anomaly_detector.h`, reusing the existing native structs.
- Added `anomaly_detector_process()` in `anomaly_detector.c`.
- Valid RGBA8888 frames call `anomaly_process_frame()` with the same state,
  config, RGBA pointer, stride, dimensions, timestamp, and result pointer.
- Missing state, missing frames, null RGBA planes, invalid dimensions/stride,
  and unsupported formats return zero boxes through the existing
  `anomaly_process_frame()` zero-dimension path so `result_out` is initialized
  consistently.
- Preserved `ffmpeg_bridge.c`, Kotlin, regression manifests, reset behavior,
  overlay drawing, timing, config transitions, and result fields.
- Added native harness tests for facade/direct result-shape parity, missing
  frame handling, missing state handling, and unsupported format handling.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 140 passed, 0 failed.
- `./gradlew :app:compileDebugKotlin`

## Packet 12: MotionEstimator Movement Snapshot View

Status: complete.

Mode: code change, behavior-preserving, after Packet 11.

Ownership:

- `app/src/main/cpp/anomaly_motion_estimator.h`
- `app/src/main/cpp/anomaly_motion_estimator.c`
- local movement-snapshot adapter/query call sites in `anomaly_analysis.c`
- focused native harness tests

Task:

Move the current local `anomaly_movement_snapshot_t` adapter and normalized
tile-query helper out of `anomaly_analysis.c` and into the MotionEstimator
boundary. This should name the producer output used by IR/Color/revisit
consumers without changing the movement sidecar algorithm or appearance
scoring.

Expected API shape:

```c
anomaly_motion_movement_snapshot_t anomaly_motion_estimator_make_movement_snapshot(
        const anomaly_debug_movement_t *movement);

bool anomaly_motion_estimator_query_snapshot_at_norm(
        const anomaly_motion_movement_snapshot_t *snapshot,
        float                                    x_norm,
        float                                    y_norm,
        anomaly_debug_movement_tile_t           *tile_out);
```

Constraints:

- Transitional backing by `anomaly_debug_movement_t` is acceptable for this
  packet; do not introduce new tile arrays or heap ownership.
- Preserve all current debug/result fields, scoring, timing, and reset
  behavior.
- Do not move the appearance-proposal motion scorer yet.
- Do not run/tune the 80% scan zone, Red1 rarity, or Laplace motion-prep
  experiments in this packet.

Validation:

- `git diff --check`
- native build and unit harness
- focused tests for invalid snapshot/query and valid tile lookup
- movement-estimator comparison harness should remain the later parent gate for
  broader MotionEstimator behavior checks.

Worker checkpoint:

- Moved the transitional movement snapshot adapter/query into
  `anomaly_motion_estimator.{h,c}`.
- Snapshot remains a backing-store view over `anomaly_debug_movement_t`; no new
  tile arrays, ownership, or behavior changes were introduced.
- Updated `anomaly_analysis.c` consumers to use
  `anomaly_motion_estimator_make_movement_snapshot()` and
  `anomaly_motion_estimator_query_snapshot_at_norm()`.
- Added focused native harness tests for invalid snapshot/query and valid tile
  lookup plus mirrored summary fields.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 164 passed, 0 failed.
- `python3 tools/anomaly_test/run_movement_estimator_comparison.py --help`
- `./gradlew :app:compileDebugKotlin`

## Packet 13: Appearance Candidate Boundary Inventory

Status: complete.

Mode: inspect-only / documentation, behavior-preserving, after Packet 12.

Ownership:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- current docs only; no code edits in this packet

Task:

Inventory the appearance-candidate boundary before moving IR or Color detector
logic. The goal is to identify the smallest candidate contract that can support
modal IR/Color appearance detectors while preserving downstream replay,
annotation, debug, tracking, and manifest consumers.

Questions:

- What local structs represent the common appearance proposal, thermal blob
  candidate, and color blob candidate?
- Which functions create, rank, insert, suppress, gate, and consume thermal
  candidates?
- Which functions create, rank, insert, suppress, gate, and consume color
  candidates?
- Which debug/result structs in `anomaly_analysis.h` are downstream contracts
  that must remain stable?
- What is the smallest follow-up code packet that can name an
  appearance-candidate contract without changing behavior?

Constraints:

- No rarity tuning, Red1 behavior work, 80% scan-zone promotion, Laplace
  motion-prep, or broad detector relocation.
- Treat frame-step replay, annotation, debug JSON/CSV, native harness, IR
  manifest, and Red1 app-parity replay as downstream consumers.

Explorer checkpoint:

- Common appearance proposal is currently `anomaly_motion_candidate_t` in
  `anomaly_analysis.c`, with sampled-grid coordinates, pixel coordinates,
  generic proposal score, and thermal/color channel scores.
- Thermal candidate is `anomaly_thermal_blob_candidate_t`, embedding the common
  proposal plus retention rank, geometry/fill/quality, thermal deltas, and
  sampled-grid bbox.
- Color candidate is `anomaly_color_blob_candidate_t`, embedding the common
  proposal plus retention rank, history rarity, geometry/fill/quality/support,
  ring/support-mass metrics, and sampled-grid bbox.
- Thermal producers/consumers remain `extract_thermal_blob_candidates()`,
  `compare_thermal_blob_rank()`, `insert_thermal_blob_candidate()`, later
  per-frame rescoring, provisional target observation, tracking, box/overlay,
  and `thermal_debug` export.
- Color producers/consumers remain `build_color_blob_candidate()`,
  `rescue_color_blob_subdivision_candidates()`,
  `extract_color_blob_candidates()`, `suppress_color_seed_region()`,
  `compare_color_blob_rank()`, `insert_color_blob_candidate()`, fresh winner
  gate, later per-frame postprocessing, tracking, box/overlay, target trace,
  and `color_debug` export.
- Public downstream structs to keep stable include `anomaly_debug_candidate_t`,
  `anomaly_debug_thermal_candidate_t`, `anomaly_debug_color_candidate_t`,
  color/thermal target debug structs/enums, aggregate thermal/color debug
  structs, scan plan, and top-level `anomaly_result_t`.

Recommended Packet 14:

- Add an internal appearance-candidate contract header and move only the three
  private candidate typedefs into it.
- Do not move extraction, ranking, insertion, winner gates, target traces,
  bbox normalization, tracking conversion, or public result/debug structs yet.
- Validation can stay at compile/native harness first because this is type
  relocation only; broader IR/Red1 gates remain parent gates for later behavior
  movement.

## Packet 14: Appearance Candidate Contract Header

Status: complete.

Mode: code change, behavior-preserving, after Packet 13.

Ownership:

- `app/src/main/cpp/anomaly_appearance_candidates.h`
- candidate typedef removal/include update in `app/src/main/cpp/anomaly_analysis.c`
- current docs only

Packet 14 implementation checkpoint:

- Added private internal header
  `app/src/main/cpp/anomaly_appearance_candidates.h`.
- Moved only `anomaly_motion_candidate_t`,
  `anomaly_thermal_blob_candidate_t`, and
  `anomaly_color_blob_candidate_t` from `anomaly_analysis.c` into that header,
  preserving field order, types, and names.
- Left extraction, ranking, insertion, winner gates, target traces, bbox
  normalization, tracking conversion, and public debug/result contracts in
  place.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 164 passed, 0 failed.
- `./gradlew :app:compileDebugKotlin`

## Packet 15: Appearance Candidate Rank Helpers

Status: complete.

Mode: code change, behavior-preserving, after Packet 14.

Ownership:

- `app/src/main/cpp/anomaly_appearance_candidates.h`
- `app/src/main/cpp/anomaly_appearance_candidates.c`
- rank-helper call sites in `app/src/main/cpp/anomaly_analysis.c`
- app/native-test CMake source lists
- focused native harness tests

Task:

Move only the pure appearance-candidate ranking helpers into the internal
appearance-candidate module:

- thermal blob candidate comparison,
- color blob compact-rank scoring,
- color blob candidate comparison.

Constraints:

- Preserve exact ordering semantics, including NULL handling and all
  tie-breakers.
- Do not move insertion/NMS, extraction, fresh winner gates, target traces,
  bbox normalization, tracking conversion, scoring, rarity/commonness tuning, or
  public debug/result structs.
- Keep this as a private/internal module, not a public API.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 177 passed, 0 failed.
- `./gradlew :app:compileDebugKotlin`
- Added focused ordering tests for thermal and color candidate ranking.

Implementation checkpoint:

- Added `app/src/main/cpp/anomaly_appearance_candidates.c`.
- Moved only thermal/color candidate rank helpers into the internal
  appearance-candidate module.
- Updated `anomaly_analysis.c` call sites to use the internal-module names.
- Left extraction, insertion/NMS, winner gates, target traces, bbox
  normalization, scoring, rarity/commonness tuning, and public structs
  untouched.

## Packet 16: Target Observation Boundary Inventory

Status: complete.

Mode: inspect-only / documentation, behavior-preserving, after Packet 15.

Ownership:

- `app/src/main/cpp/anomaly_analysis.c`
- `app/src/main/cpp/anomaly_analysis.h`
- docs only; no code edits in this packet

Task:

Inventory the boundary where modal appearance candidates become target
observations for persistence, tracking, boxes, and debug output. The goal is to
name the next contract without changing target-track behavior or public result
schemas.

Questions:

- What fields in `anomaly_target_observation_t` are modal appearance evidence,
  and which are tracking/publish metadata?
- Which functions populate target observations from Color and Thermal
  candidates?
- Which functions consume observations for matching, track scoring,
  persistence, update, boxes, or debug output?
- Which public debug/result structs depend on these fields directly or
  indirectly?
- What is the smallest follow-up code packet that can name this boundary while
  preserving behavior?

Constraints:

- No scoring changes, target-track algorithm changes, box/debug schema changes,
  Color rarity work, Red1 behavior work, 80% scan-zone promotion, or Laplace
  motion-prep.
- Preserve frame-step replay, annotation, debug JSON/CSV, native harness, IR
  manifest, and Red1 app-parity replay.

Explorer checkpoint:

- `anomaly_target_observation_t` is the private per-frame handoff from modal
  appearance winners/provisional candidates into persistent target tracks.
- Modal evidence fields: normalized center, normalized half-size, support
  radius, and confidence.
- Tracking/publish metadata fields: valid bit, publish-confirming flag, and
  algorithm label.
- Color observations are populated by
  `populate_color_candidate_target_observation()`, including normalized bbox,
  algorithm, publish-confirming default, and confidence from quality,
  isolation, and score excess.
- Thermal observations are populated by
  `populate_thermal_candidate_target_observation()`, including normalized bbox,
  thermal algorithm label, non-publish-confirming default, and confidence from
  score rank, quality, isolation, patch support, and motion support.
- Direct observation consumers include duplicate suppression, color support
  track matching, persistence bonus, general target-track matching, and
  target-track update.
- Downstream consumers include track prediction, scan-planner revisit/risk
  helpers, target revisit cell annotation, target movement evidence, thermal
  target debug, thermal candidate debug, box assembly, and adaptive
  target-rich state.
- Public structs depending on this boundary include `anomaly_target_track_t`,
  `anomaly_state_t`, `anomaly_box_t`, `anomaly_result_t`, `anomaly_scan_plan_t`,
  `anomaly_debug_movement_t`, thermal/color/saliency aggregate debug structs,
  and thermal/color candidate/target debug structs.

Recommended Packet 17:

- Add an internal `anomaly_target_observations.{h,c}` boundary and move only
  `anomaly_target_observation_t`,
  `populate_color_candidate_target_observation()`,
  `populate_thermal_candidate_target_observation()`, and
  `target_observation_near_existing()`.
- Keep target-track matching/update, persistence bonus, scan planning, boxes,
  debug export, target traces, candidate ranking, scoring, Color rarity, and
  Red1 behavior in `anomaly_analysis.c`.

## Packet 17: Target Observation Contract

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 16.

Ownership:

- `app/src/main/cpp/anomaly_target_observations.h`
- `app/src/main/cpp/anomaly_target_observations.c`
- observation helper call sites in `app/src/main/cpp/anomaly_analysis.c`
- app/native-test CMake source lists
- focused native harness tests

Task:

Move only the private target-observation struct and the pure
candidate-to-observation conversion helpers into an internal target-observation
module.

Constraints:

- Preserve exact bbox arithmetic, confidence calculation, publish-confirming
  defaults, algorithm labels, and duplicate-suppression behavior.
- Do not move target-track matching/update, persistence bonus, scan planning,
  boxes, debug export, target traces, candidate ranking, scoring,
  Color rarity/commonness tuning, or public result/debug structs.

Validation:

- `git diff --check`
- native build and unit harness
- focused tests for color conversion, thermal conversion, and duplicate
  suppression
- Android compile as parent gate after integration

Worker packet 17 notes:

- Added internal `anomaly_target_observations.{h,c}` and moved the private
  observation struct plus color/thermal candidate conversion and duplicate
  suppression helper behind that boundary.
- Kept bbox normalization arithmetic equivalent in the new source as a private
  helper; the existing `color_candidate_bbox_norm()` remains in
  `anomaly_analysis.c` for its other local call sites.
- Left target-track matching/update, persistence bonus, scan planning, boxes,
  debug export, target traces, candidate ranking, scoring, Color rarity, and
  Red1 behavior in `anomaly_analysis.c`.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` (`200 passed, 0 failed`)
- `./gradlew :app:compileDebugKotlin`

Parent follow-up note:

- `anomaly_target_observations.c` intentionally keeps a private duplicate of
  the thermal candidate score-slack constant matching the current
  `ANOMALY_TARGET_CANDIDATE_SCORE_SLACK` value. Centralize only if a later
  scoring-boundary extraction gives that constant a clearer owner.
- Added focused native harness coverage for color conversion, thermal
  conversion, and near-existing duplicate suppression.

## Packet 18: Appearance Mode Interface Inventory

Status: complete explorer pass.

Mode: inspect-only, after Packet 17.

Ownership:

- Modal appearance-detector regions of `app/src/main/cpp/anomaly_analysis.c`
- Appearance candidate and target-observation contracts:
  - `app/src/main/cpp/anomaly_appearance_candidates.h`
  - `app/src/main/cpp/anomaly_target_observations.h`
- Detector/frame/config contract headers:
  - `app/src/main/cpp/anomaly_detector.h`
  - `app/src/main/cpp/anomaly_detector_internal.h`
  - `app/src/main/cpp/anomaly_frame.h`
- This packet ledger.

Task:

Define the internal appearance-mode interface that will let Thermal/IR and
Visible-Color become separate modal detector modules without changing runtime
behavior. This is an interface inventory only; do not move implementation code.

Questions to answer:

- What common input context should a modal appearance detector receive from the
  parent detector loop: frame view, ROI/sample grid, scan plan, config slice,
  movement snapshot, previous ROI state, and scratch products?
- What common producer output should it return: score maps, candidate lists,
  target observations, best-candidate summary, debug payload, and timing stage
  accounting?
- Which current Thermal/IR responsibilities are cleanly modal, and which remain
  shared parent/fusion responsibilities?
- Which current Color responsibilities are cleanly modal, and which remain
  shared parent/fusion responsibilities?
- Which state fields belong in future `thermal` and `color` private state
  structs versus shared detector state?
- What is the smallest behavior-preserving Packet 19 skeleton/header change
  that names the interface without relocating scoring?

Constraints:

- Do not conflate modal appearance detection with additive Motion/Shape
  producers.
- Do not add threading, frame copies, queues, or async lifecycle.
- Preserve `ANOMALY_TIMING_STAGE_THERMAL_SCORING` and
  `ANOMALY_TIMING_STAGE_COLOR_SCORING` semantics.
- Preserve frame-step replay, annotation, debug JSON/CSV, native harness, IR
  manifest, and Red1 app-parity workflows as downstream consumers.
- Keep the future stand-alone module limited to C, stdlib, and pthreads where
  needed; avoid Android, FFmpeg, or non-portable dependencies in the core
  interface.

Deliverable:

- Proposed `anomaly_appearance_detector_*` type groups and field lists.
- Exact anchors to current Thermal and Color producer/consumer code.
- A risk list for behavior/performance regressions.
- Recommended Packet 19 write scope.
- No code changes.

Validation:

- None required beyond code anchors and parent review.

Explorer checkpoint:

- Use an internal `anomaly_appearance_detector_*` contract for modal
  appearance detectors only: Thermal/IR and Visible-Color.
- Motion and future Shape evidence remain additive producers/validators, not
  alternate modal appearance modes.
- Modal inputs should be explicit views over frame input, ROI/sample grid,
  scan plan/selective-refresh state, registration/movement snapshots, config
  slices, and scratch products.
- Modal outputs should be explicit views over score/support/delta maps,
  candidate arrays, target observations, best-candidate summaries, debug
  payloads, and timing deltas.
- Clean Thermal/IR modal ownership includes spatial thermal scoring, EMA/delta
  scoring/background update, blob extraction, and thermal candidate
  ranking/selection/debug values.
- Thermal target-track publishing, movement-shadow enrichment, box assembly,
  and annotation output remain shared parent/fusion responsibilities.
- Clean Color modal ownership includes color sampling/history prep, rarity
  scoring, support-map/blob extraction, and color candidate/debug fields.
- Color stride-hold, provisional-observation fusion, target-track support, and
  public debug/result layout remain shared parent/fusion responsibilities.
- Future thermal-private state likely includes thermal background, warmup,
  target persistence, thermal delta scratch, and thermal patch scratch.
- Future color-private state likely includes recent/scratch histograms,
  history recovery, color sampling phase/count fields, distinctness summary,
  and color ROI sample arrays.
- Shared detector state remains frame counter, registration cache, motion
  persistence, target tracks, adaptive stride, publish stability, shared ROI
  masks, saliency/fusion accumulators, and lifecycle/reset plumbing.
- Recommended Packet 19: add a header-only skeleton naming the boundary; do not
  wire call sites or move implementation.

## Packet 19: Appearance Detector Interface Header Skeleton

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 18.

Ownership:

- `app/src/main/cpp/anomaly_appearance_detector.h`
- focused compile-touch coverage in `tools/anomaly_test/test_anomaly.c`
- this packet ledger

Task:

Define the internal C structs that name the modal appearance detector boundary
for Thermal/IR and Visible-Color. This packet names the contract only; it does
not relocate scoring, extraction, tracking, scan planning, debug export, timing
implementation, threading, queues, or frame-copy ownership.

Constraints:

- Keep dependencies plain C and existing internal detector headers.
- Do not edit `anomaly_analysis.c` call sites.
- Do not add an implementation `.c` file.
- Keep Motion and Shape outside the modal appearance enum; they remain
  additive producers/validators.
- Preserve current `ANOMALY_TIMING_STAGE_THERMAL_SCORING` and
  `ANOMALY_TIMING_STAGE_COLOR_SCORING` semantics.

Worker packet 19 notes:

- Added `anomaly_appearance_detector_mode_t` with Thermal and Color modes only.
- Added config, frame-context, scratch, best-summary, result, and optional ops
  structs.
- The frame context reuses `anomaly_frame_input_t`, `anomaly_scan_plan_t`, and
  `anomaly_motion_movement_snapshot_t` where practical, and keeps registration
  as an opaque pointer for now.
- The result struct carries existing thermal/color candidate arrays, target
  observations, public thermal/color debug pointers, and separate thermal/color
  timing deltas.
- Added a native harness compile-touch test for enum distinction and basic
  struct completeness.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` (`205 passed, 0 failed`)

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` (`205 passed, 0 failed`)
- `./gradlew :app:compileDebugKotlin`

Parent follow-up note:

- The ops table is deliberately unwired. Packet 20 should either wrap one
  existing modal path behind this contract without moving implementation, or
  inspect Thermal/IR-specific state ownership for the first low-risk
  implementation extraction.

Post-Packet 19 IR/perf gate:

- Black-hot IR regression:
  `/private/tmp/rid2c_packet19_ir_regression/suite_report.md`
- Registration/perf benchmark:
  `/private/tmp/rid2c_packet19_registration_perf`
- Redesigned-incremental aggregate: precision 0.994, recall 0.599, realtime
  factor 1.409x.
- Dense full-scan gold aggregate: precision 1.000, recall 0.362, realtime
  factor 0.581x.
- Registration perf summary: PowerHouseTeam affine scan-zone 0.80 at 1.33x,
  PowerHouseTeam affine scan-zone 0.60 at 1.56x, PowerHouse1 affine scan-zone
  0.80 at 0.84x, PowerHouse1 opening scan-zone 0.60 at 0.97x.

## Packet 20: Thermal/IR State Ownership Inventory

Status: complete by explorer pass and parent review.

Mode: inspect-only, after Packet 19 and the refreshed IR/perf gate.

Ownership:

- Thermal/IR scoring, background, blob extraction, and candidate-debug regions
  of `app/src/main/cpp/anomaly_analysis.c`
- Thermal state fields in `app/src/main/cpp/anomaly_analysis.h`
- Appearance detector contract in
  `app/src/main/cpp/anomaly_appearance_detector.h`
- This packet ledger.

Task:

Map the first low-risk Thermal/IR implementation extraction behind the Packet
19 appearance-detector contract. Prefer state and helper ownership boundaries
over moving the full scoring loop.

Questions to answer:

- Which thermal state fields can become an internal `anomaly_thermal_state_t`
  without changing reset/cleanup behavior?
- Which thermal scratch buffers are truly thermal-private versus shared with
  saliency, motion, Color, or fusion?
- Which pure helpers can move first without passing the whole
  `anomaly_state_t *` or private registration model into a thermal module?
- Would a no-op wrapper around the existing thermal scoring block be safer than
  a state/header extraction, or would it require too much context?
- What exact Packet 21 write scope gives the smallest behavior-preserving
  implementation step?

Constraints:

- No algorithm changes, thresholds, timing bucket changes, or scan-plan changes.
- Do not move Color code in this packet.
- Do not move target tracking, publishing, boxes, saliency fusion, or
  movement-shadow enrichment.
- Do not add threads, queues, frame copies, or new allocation churn.
- Preserve frame-step replay, thermal target tracing, annotation/debug exports,
  and the refreshed black-hot IR regression/perf gate.

Deliverable:

- Exact anchors for thermal state fields, helper functions, producers, and
  consumers.
- Proposed `anomaly_thermal_state_t` and any helper input/output structs.
- Risk list and recommended Packet 21 write scope.
- No code changes.

Validation:

- None required beyond code anchors and parent review.

Explorer checkpoint:

- Refreshed IR guardrails in the worker fork:
  - Black-hot regression:
    `/private/tmp/rid2c_packet20_ir_regression/suite_report.md`
  - Registration/perf benchmark:
    `/private/tmp/rid2c_packet20_registration_perf`
  - Redesigned-incremental aggregate: precision 0.994, recall 0.599,
    realtime factor 1.411x.
  - Dense full-scan gold aggregate: precision 1.000, recall 0.362, realtime
    factor 0.580x.
- Thermal state that can later become `anomaly_thermal_state_t` cleanly:
  `bg_luma`, `bg_sg_w`, `bg_sg_h`, `bg_warmup`,
  `thermal_target_persist`, `thermal_target_persist_w`, and
  `thermal_target_persist_h`.
- `scratch_thermal_delta` is thermal-private in use but currently allocated
  through saliency scratch; leave ownership alone until the scratch boundary is
  clearer.
- `scratch_patch_score`, `scratch_patch_selection`, `scratch_u8`, and
  `scratch_i32` are shared reusable buffers used by thermal blob extraction;
  do not move them yet.
- `scratch_prev_roi_thermal_score` participates in ROI carry-forward with
  Color, masks, and coverage; keep it shared.
- Avoid a no-op wrapper around the full thermal scoring block for now. It would
  need too much live context: scan mask, timing, score maps, background,
  scratch, candidate arrays, target trace, motion support, ROI update,
  saliency maps, and publish settlement.
- Recommended Packet 21: create `anomaly_thermal_detector.{h,c}` and move only
  pure thermal math/helper functions with focused native tests. Leave
  `anomaly_state_t`, background ownership, blob extraction, candidate
  selection, target tracing, ROI update, and timing buckets in
  `anomaly_analysis.c`.

## Packet 21: Thermal Pure Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 20.

Ownership:

- New internal thermal helper module:
  - `app/src/main/cpp/anomaly_thermal_detector.h`
  - `app/src/main/cpp/anomaly_thermal_detector.c`
- Narrow helper declarations/call sites in `app/src/main/cpp/anomaly_analysis.c`
- App/native-test CMake source lists
- Focused native harness tests
- This packet ledger

Task:

Move only pure Thermal/IR helper functions that do not require
`anomaly_state_t *`, registration internals, target tracking, or Color state.

Candidate helpers:

- Thermal delta/value map helper.
- Thermal real-pixel-to-sampled-cell radius helpers.
- Effective thermal window, representative, growth, context, parent-mass, and
  small-target span helpers.
- Thermal spatial probe helper if it remains context-light after extraction.
- Framewide blob contrast stats and compact context/parent-mass scale helpers
  only if their dependencies stay explicit and narrow.

Constraints:

- No algorithm, threshold, timing, scan-plan, or lifecycle changes.
- Do not move thermal background state, `thermal_target_persist`, blob
  extraction, candidate selection, target tracing, ROI update, target
  tracking, boxes, saliency fusion, movement-shadow enrichment, or Color code.
- Do not pass whole `anomaly_state_t *` into the new module.
- Do not add threads, queues, frame copies, or new allocation churn.
- Keep the module portable C with existing internal headers only.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent gate: `./gradlew :app:compileDebugKotlin`

Worker notes:

- Added `app/src/main/cpp/anomaly_thermal_detector.h` and
  `app/src/main/cpp/anomaly_thermal_detector.c`.
- Moved pure Thermal/IR helpers out of `anomaly_analysis.c`:
  `thermal_delta_from_maps`, `thermal_blob_value_at`,
  `thermal_radius_cells_for_real_px`,
  `effective_thermal_window_radius_cells`,
  `effective_thermal_representative_radius_cells`,
  `effective_thermal_growth_radius_cells`,
  `effective_thermal_context_radius_cells`,
  `effective_thermal_parent_mass_radius_cells`,
  `effective_thermal_small_target_span_px`,
  `thermal_small_target_apparent_scale`,
  `compute_thermal_spatial_probe_at_sample`,
  `estimate_framewide_blob_contrast_stats`,
  `thermal_candidate_seed_context_scale`, and
  `thermal_candidate_parent_mass_scale`.
- Kept the tiny hot delta/radius/span helpers as `static inline` functions in
  the thermal header so inner-loop callers do not pick up avoidable function
  call overhead.
- Left thermal background state, `thermal_target_persist`, blob extraction,
  candidate selection, target tracing, ROI update, target tracking, boxes,
  saliency fusion, movement-shadow enrichment, timing buckets, and Color code
  in `anomaly_analysis.c`.
- Added focused native tests for the extracted delta/value, radius/span,
  spatial probe, framewide contrast, context-scale, and parent-mass helpers.

Worker validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 234 passed, 0 failed

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test` -> 234 passed, 0 failed
- `./gradlew :app:compileDebugKotlin`

Post-packet IR gate:

- Regression output:
  `/private/tmp/rid2c_packet21_ir_regression/suite_report.md`
- Functionality remained aligned with the Packet 19/20 aggregate metrics:
  - Current baseline: precision 0.965, recall 0.197, TP 55, FP 2, miss 224.
  - Dense full-scan gold: precision 1.000, recall 0.362, TP 101, FP 0,
    miss 178.
  - Redesigned incremental: precision 0.994, recall 0.599, TP 167, FP 1,
    miss 112.
- Registration/perf solo output:
  `/private/tmp/rid2c_packet21_registration_perf_solo`
  - PowerHouseTeam affine scan-zone 0.80: 1.20x realtime, average total
    26.94 ms, registration solve 14.85 ms, thermal 6.40 ms.
  - PowerHouseTeam affine scan-zone 0.60: 1.47x realtime, average total
    21.83 ms, registration solve 14.70 ms, thermal 3.72 ms.
  - PowerHouse1 affine scan-zone 0.80: 0.68x realtime, average total
    45.03 ms, registration solve 21.56 ms, thermal 13.48 ms.
  - PowerHouse1 opening affine scan-zone 0.60: 0.80x realtime, average total
    37.37 ms, registration solve 21.72 ms, thermal 9.12 ms.
- The first perf run in `/private/tmp/rid2c_packet21_registration_perf` was
  run concurrently with the full IR regression and should be treated as
  contaminated, not regression evidence. The solo run still showed slower
  PowerHouse1 and PowerHouseTeam 0.80 timing than Packet 20, while
  registration-solve timing also moved. Treat this as an idle-recheck item
  before using timing as a promotion signal.

Risk:

- The new module duplicates a few formerly private thermal constants with a
  thermal-detector prefix. This preserves behavior without broadening the
  public API, but a later state extraction may want to make those constants a
  single internal owner.

## Packet 22: Thermal State Ownership Skeleton

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 21.

Ownership:

- New narrow Thermal/IR state owner:
  - `app/src/main/cpp/anomaly_thermal_state.h`
  - `app/src/main/cpp/anomaly_thermal_state.c`
- `anomaly_state_t` field layout in `app/src/main/cpp/anomaly_analysis.h`
- Mechanical thermal state call-site updates in
  `app/src/main/cpp/anomaly_analysis.c`
- App/native-test CMake source lists
- Focused native harness tests if a lifecycle helper can be exercised cleanly
- This packet ledger

Task:

Create `anomaly_thermal_state_t` for the persistent Thermal/IR maps currently
embedded directly in `anomaly_state_t`:

- `bg_luma`
- `bg_sg_w`
- `bg_sg_h`
- `bg_warmup`
- `thermal_target_persist`
- `thermal_target_persist_w`
- `thermal_target_persist_h`

Add small lifecycle helpers, keeping them portable C/stdlib:

- `anomaly_thermal_state_init`
- `anomaly_thermal_state_reset`
- `anomaly_thermal_state_bg_ready`
- `anomaly_thermal_state_update_background`
- `anomaly_thermal_state_prepare_target_persist`

Constraints:

- No scoring, threshold, ranking, scan-plan, timing, or threading changes.
- Do not move blob extraction, candidate ranking, target tracing, ROI update,
  saliency fusion, movement support, debug result plumbing, or timing buckets.
- Do not move `scratch_thermal_delta`; it is still allocated through saliency
  scratch.
- Do not move `scratch_patch_score`, `scratch_patch_selection`, `scratch_u8`,
  or `scratch_i32`.
- Keep `publish_stable_frames` and broader publish/track policy owned by
  `anomaly_analysis.c`; a background helper can report that a reset occurred.
- Avoid putting the state type in `anomaly_thermal_detector.h` because that
  header currently includes internal analysis headers for pure helper access.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent gate: `./gradlew :app:compileDebugKotlin`
- Parent IR gate if parent review sees any lifecycle ordering risk.

Idle perf recheck before Packet 22:

- Output:
  `/private/tmp/rid2c_packet21_registration_perf_idle_recheck`
- PowerHouseTeam affine scan-zone 0.80: 1.35x realtime, average total
  23.88 ms, registration solve 13.20 ms, thermal 5.62 ms.
- PowerHouseTeam affine scan-zone 0.60: 1.66x realtime, average total
  19.36 ms, registration solve 13.07 ms, thermal 3.24 ms.
- PowerHouse1 affine scan-zone 0.80: 0.84x realtime, average total
  36.75 ms, registration solve 17.67 ms, thermal 10.96 ms.
- PowerHouse1 opening affine scan-zone 0.60: 0.98x realtime, average total
  30.22 ms, registration solve 17.91 ms, thermal 7.18 ms.
- The idle run is back at or better than Packet 20 realtime factors, so the
  earlier Packet 21 slowdown is treated as host/load noise rather than a
  regression from pure helper extraction.

Worker implementation notes:

- Added `anomaly_thermal_state_t` in `anomaly_thermal_state.h` with ownership
  of background EMA and thermal target-persist maps only.
- Added portable lifecycle helpers in `anomaly_thermal_state.c`:
  `anomaly_thermal_state_init`, `anomaly_thermal_state_reset`,
  `anomaly_thermal_state_bg_ready`,
  `anomaly_thermal_state_update_background`, and
  `anomaly_thermal_state_prepare_target_persist`.
- Embedded the new state owner as `anomaly_state_t.thermal` and mechanically
  redirected existing map accesses through that owner.
- Kept `publish_stable_frames` in `anomaly_analysis.c`; background update
  reports re-seeding so the caller resets publish stability at the existing
  lifecycle boundary.
- Did not move scoring, thresholds, ranking, scan planning, blob extraction,
  candidate ranking, target tracing, ROI update, saliency fusion, movement
  support, debug/result plumbing, scratch maps, timing buckets, or threading.
- Added focused native lifecycle coverage for background seed/update/readiness
  and target-persist allocation/decay/reset.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed; rebuilt
  `anomaly_test` and `anomaly_video_test`.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, 1/1 tests.
- `tools/anomaly_test/build_timing/anomaly_test`: `248 passed, 0 failed`.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, 1/1 tests.
- `tools/anomaly_test/build_timing/anomaly_test`: `248 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: build successful.

Post-packet IR gate:

- Regression output:
  `/private/tmp/rid2c_packet22_ir_regression/suite_report.md`
- Functionality remained aligned with Packet 21 aggregate counts:
  - Current baseline: precision 0.965, recall 0.197, TP 55, FP 2, miss 224.
  - Dense full-scan gold: precision 1.000, recall 0.362, TP 101, FP 0,
    miss 178.
  - Redesigned incremental: precision 0.994, recall 0.599, TP 167, FP 1,
    miss 112.
- Registration/perf output:
  `/private/tmp/rid2c_packet22_registration_perf`
  - PowerHouseTeam affine scan-zone 0.80: 1.28x realtime, average total
    25.32 ms, registration solve 14.06 ms, thermal 5.95 ms.
  - PowerHouseTeam affine scan-zone 0.60: 1.44x realtime, average total
    22.39 ms, registration solve 15.15 ms, thermal 3.73 ms.
  - PowerHouse1 affine scan-zone 0.80: 0.69x realtime, average total
    44.77 ms, registration solve 21.50 ms, thermal 13.33 ms.
  - PowerHouse1 opening affine scan-zone 0.60: 0.88x realtime, average total
    33.80 ms, registration solve 19.91 ms, thermal 8.12 ms.
- Packet 22 perf is below the Packet 21 idle recheck, but close to the earlier
  Packet 21 solo run and still within the noisy host/load band seen in this
  modularization pass. The functional IR aggregates are unchanged.

Residual risk:

- The main risk remains mechanical call-site churn from `state->bg_luma` to
  `state->thermal.bg_luma`; validation is clean, but parent review should
  still inspect hot-path lifecycle ordering before any broader extraction.
- Follow-up harness hardening added after Packet 22 locked down strict
  background-readiness dimension checks, non-strict readiness checks, scene-cut
  rejection, and target-persist scene-cut clearing. Native validation reached
  `253 passed, 0 failed` in the worker fork.

## Packet 23: Thermal Temporal Frame Stats Context

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 22.

Ownership:

- `app/src/main/cpp/anomaly_thermal_detector.h`
- `app/src/main/cpp/anomaly_thermal_detector.c`
- Narrow call-site update in `app/src/main/cpp/anomaly_analysis.c`
- Focused native harness tests in `tools/anomaly_test/test_anomaly.c`
- This packet ledger

Task:

Extract the temporal delta/stat preparation block into the Thermal/IR helper
module. The helper should accept the settled background map and current sampled
luma, optionally fill the caller-owned thermal delta map, and return the frame
statistics currently prepared in `anomaly_analysis.c`.

Move only:

- optional `thermal_delta_map` filling,
- positive delta mean accumulation,
- `delta_norm` calculation with the existing norm floor,
- framewide blob contrast mean/std calculation through the existing contrast
  helper.

Suggested API shape:

```c
typedef struct {
    bool  valid;
    float delta_mean;
    float delta_norm;
    float frame_blob_contrast_mean;
    float frame_blob_contrast_std;
    int   positive_delta_count;
} anomaly_thermal_temporal_stats_t;

anomaly_thermal_temporal_stats_t anomaly_thermal_compute_temporal_stats(
        float       *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         black_hot,
        float        thermal_min_delta,
        float        norm_floor);
```

Constraints:

- No allocation, no callbacks, no state mutation except writing the optional
  caller-owned delta map.
- Keep activation guards and scratch allocation in `anomaly_analysis.c`.
- Keep `result_out->saliency_debug.bg_ready` in `anomaly_analysis.c`.
- Keep temporal pass-2 score-map writing and `best_thermal` selection in
  `anomaly_analysis.c`.
- Do not move blob extraction, candidate ranking, target tracing,
  target-persist prepare/stamp, background EMA update, ROI update, tracking,
  movement/saliency fusion, timing buckets, or debug/result plumbing.
- Preserve the fallback behavior when `thermal_delta_map == NULL` by reading
  directly from `bg_luma` and `sg_luma`.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent gate: `./gradlew :app:compileDebugKotlin`
- Parent IR regression/perf gate if parent review sees lifecycle or timing
  sensitivity.

Risk:

- This is a hot-path loop. Keep the helper as a single C function with no
  extra allocation or abstraction overhead.
- Do not change delta/stat ordering or contrast-stat fallback behavior.

Worker notes:

- Added `anomaly_thermal_temporal_stats_t` and
  `anomaly_thermal_compute_temporal_stats` to the Thermal/IR helper module.
- Replaced only the temporal delta/stat preparation block in
  `anomaly_analysis.c`; activation guards, scratch allocation, pass-2 scoring,
  candidate extraction/ranking, target persist, background EMA, ROI, tracking,
  movement/saliency fusion, timing, and debug plumbing remain in place.
- Added native harness coverage for caller-owned delta-map filling, no-map
  contrast fallback, norm-floor behavior, black-hot delta direction, and invalid
  input rejection.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `265 passed, 0 failed`.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `265 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: build successful.

Post-packet IR gate:

- Regression output:
  `/private/tmp/rid2c_packet23_ir_regression/suite_report.md`
- Functionality remained aligned with Packet 21/22 aggregate counts:
  - Current baseline: precision 0.965, recall 0.197, TP 55, FP 2, miss 224.
  - Dense full-scan gold: precision 1.000, recall 0.362, TP 101, FP 0,
    miss 178.
  - Redesigned incremental: precision 0.994, recall 0.599, TP 167, FP 1,
    miss 112.
- First registration/perf output:
  `/private/tmp/rid2c_packet23_registration_perf`
  - PowerHouseTeam affine scan-zone 0.80: 1.13x realtime, average total
    28.67 ms, registration solve 16.08 ms, thermal 6.54 ms.
  - PowerHouseTeam affine scan-zone 0.60: 1.45x realtime, average total
    22.20 ms, registration solve 15.04 ms, thermal 3.67 ms.
  - PowerHouse1 affine scan-zone 0.80: 0.64x realtime, average total
    47.63 ms, registration solve 22.79 ms, thermal 13.92 ms.
  - PowerHouse1 opening affine scan-zone 0.60: 0.62x realtime, average total
    47.07 ms, registration solve 28.20 ms, thermal 10.79 ms.
- Idle recheck output:
  `/private/tmp/rid2c_packet23_registration_perf_idle_recheck`
  - PowerHouseTeam affine scan-zone 0.80: 1.28x realtime, average total
    25.21 ms, registration solve 14.00 ms, thermal 5.90 ms.
  - PowerHouseTeam affine scan-zone 0.60: 1.36x realtime, average total
    23.72 ms, registration solve 16.04 ms, thermal 3.93 ms.
  - PowerHouse1 affine scan-zone 0.80: 0.61x realtime, average total
    50.15 ms, registration solve 24.01 ms, thermal 14.88 ms.
  - PowerHouse1 opening affine scan-zone 0.60: 0.77x realtime, average total
    36.47 ms, registration solve 21.50 ms, thermal 8.82 ms.
- Packet 23 preserves reviewed IR functionality. Focused perf remains noisy
  and is slower than Packet 22 on PowerHouse1, with registration timing moving
  alongside thermal timing. Treat this as a watch item and recheck before the
  next hot Thermal/IR loop extraction.
- Second idle recheck output:
  `/private/tmp/rid2c_packet23_registration_perf_second_idle`
  - PowerHouseTeam affine scan-zone 0.80: 1.34x realtime, average total
    24.12 ms, registration solve 13.40 ms, thermal 5.60 ms.
  - PowerHouseTeam affine scan-zone 0.60: 1.61x realtime, average total
    20.01 ms, registration solve 13.56 ms, thermal 3.30 ms.
  - PowerHouse1 affine scan-zone 0.80: 0.80x realtime, average total
    37.81 ms, registration solve 17.97 ms, thermal 11.26 ms.
  - PowerHouse1 opening affine scan-zone 0.60: 0.98x realtime, average total
    30.09 ms, registration solve 17.73 ms, thermal 7.12 ms.
- The second idle run recovered to at or above Packet 22 realtime factors on
  all four focused cases. Treat the earlier Packet 23 PowerHouse1 slowdown as
  host/load noise rather than a confirmed regression.

## Packet 24: Color Histogram/Rarity Utility Module

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 23.

Ownership:

- New narrow Color utility module:
  - `app/src/main/cpp/anomaly_color_detector.h`
  - `app/src/main/cpp/anomaly_color_detector.c`
- Mechanical call-site updates in `app/src/main/cpp/anomaly_analysis.c`
- App/native-test CMake source lists
- Focused native harness tests in `tools/anomaly_test/test_anomaly.c`
- This packet ledger

Task:

Move only state-independent Color binning and histogram/rarity helpers out of
`anomaly_analysis.c`:

- `quantize_uv_bin` -> `anomaly_color_quantize_uv_bin`
- `color_hist_key` -> `anomaly_color_hist_key`
- `color_sample_chroma_magnitude` -> `anomaly_color_sample_chroma_magnitude`
- `fill_color_uv_bins` -> `anomaly_color_fill_uv_bins`
- `ensure_color_hist_capacity` -> `anomaly_color_hist_ensure_capacity`
- `build_color_frame_histogram` -> `anomaly_color_build_frame_histogram`
- `score_color_hist_rarity` -> `anomaly_color_score_hist_rarity`
- `score_color_hist_family_rarity` ->
  `anomaly_color_score_hist_family_rarity`
- `build_color_family_rarity_lut` ->
  `anomaly_color_build_family_rarity_lut`
- `color_history_recent_scale_for_recovery` ->
  `anomaly_color_history_recent_scale_for_recovery`

Move these private Color range constants with the module:

- `ANOMALY_COLOR_U_MIN`
- `ANOMALY_COLOR_U_MAX`
- `ANOMALY_COLOR_V_MIN`
- `ANOMALY_COLOR_V_MAX`

Constraints:

- No support-map, candidate-extraction, winner-gate, promotion-track, ROI,
  debug/result, timing, threading, or lifecycle changes.
- Packet 24 kept `update_color_recent_histogram` in `anomaly_analysis.c`
  because it then mutated `anomaly_state_t`; Packet 28 supersedes this by
  moving the helper as a buffer-oriented Color API.
- Keep `prepare_color_sampling_state`, `local_uv_support_count`, contrast/ring
  telemetry helpers, `build_color_support_map`, `extract_color_blob_candidates`,
  fresh winner gate logic, Color promotion tracks, target observation merge,
  accumulators, persistence, overlays, and result/debug export in
  `anomaly_analysis.c`.
- Keep timing bucket ownership in `anomaly_analysis.c`, especially
  `ANOMALY_TIMING_STAGE_COLOR_SCORING`.
- The new module must stay portable C/stdlib with no Android, JNI, FFmpeg,
  pthread, or app lifecycle dependency.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent gate: `./gradlew :app:compileDebugKotlin`
- Parent Color regression gate:
  `python3 tools/anomaly_test/run_regression_suite.py --manifest tools/anomaly_test/regression_suite_color_manifest.json --binary tools/anomaly_test/build_timing/anomaly_video_test --out-dir /private/tmp/rid2c_packet24_color_regression --report-json /private/tmp/rid2c_packet24_color_regression/suite_report.json --report-md /private/tmp/rid2c_packet24_color_regression/suite_report.md`

Risk:

- This touches Color scoring inputs, so keep helper logic equivalent and avoid
  pulling any hot support/candidate loops across the module boundary yet.
- If any formerly inline hot helper becomes a measurable out-of-line cost, use
  the visible-color perf benchmark before adoption.

Worker notes:

- Added `app/src/main/cpp/anomaly_color_detector.h` and
  `app/src/main/cpp/anomaly_color_detector.c`.
- Moved only the Color U/V range constants and state-independent
  binning/histogram/rarity/recovery helpers listed above.
- Kept the formerly inline hot helpers inline under the `anomaly_color_*`
  names in the new header; allocation, frame histogram, family rarity LUT, and
  recovery scale live in the `.c` file.
- Updated `anomaly_analysis.c` call sites mechanically. No support-map,
  candidate-extraction, winner-gate, promotion-track, ROI, observation,
  accumulator, overlay, debug/result, timing, threading, or lifecycle code was
  moved.
- Added focused native tests for quantization clamps, UV-bin filling, chroma
  magnitude, histogram allocation, valid-sample counting, 255 saturation,
  direct rarity, edge-aware family rarity, LUT parity, and recovery scale.

Worker validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test` passed, `287 passed, 0 failed`.

Parent validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test` passed, `287 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: build successful.

Post-packet Color gate:

- Regression output:
  `/private/tmp/rid2c_packet24_color_regression/suite_report.md`
- Visible-color baseline on Red1 remained at current reviewed seed behavior:
  recall 0.000, TP 0, FP 0, miss 15.
- Visible-color dense gold on Red1 remained at precision 1.000, recall 1.000,
  TP 15, FP 0, miss 0.
- Visible-color performance output:
  `/private/tmp/rid2c_packet24_visible_color_perf/visible_color_perf_report.json`
  - App-like auto average realtime: 0.31x, average total 103.77 ms,
    sampled-grid 24.51 ms, color-scoring 47.54 ms.
  - Dense gold average realtime: 0.05x, average total 713.46 ms,
    sampled-grid 208.59 ms, color-scoring 395.86 ms.
- Treat native visible-color perf as harness evidence only; user tablet smoke
  on the same Color file remains the practical app-playback adoption check.

Residual risk:

- Residual risk is limited to mechanical Color call-site churn and the fact
  that Color scoring input helpers were touched. Full Color manifest replay and
  tablet smoke remain parent gates.
- Follow-up harness hardening after Packet 24 added full interior 3x3
  family-rarity coverage plus null/partial-input safety for exported Color
  helper contracts. Native validation reached `292 passed, 0 failed` in the
  worker fork.

## Packet 25: Color Frontend Mode Helpers

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 24.

Ownership:

- `app/src/main/cpp/anomaly_color_detector.h`
- Mechanical call-site updates in `app/src/main/cpp/anomaly_analysis.c`
- Focused native harness tests in `tools/anomaly_test/test_anomaly.c`
- This packet ledger

Task:

Move only the Color frontend-mode helpers into the Color utility header, keeping
them `static inline`:

- `effective_color_frontend_mode` ->
  `anomaly_color_effective_frontend_mode`
- `color_frontend_allows_pre_support_temporal_rescue` ->
  `anomaly_color_frontend_allows_pre_support_temporal_rescue`
- `color_frontend_uses_fresh_winner_gate` ->
  `anomaly_color_frontend_uses_fresh_winner_gate`

Constraints:

- No scoring, support-map, candidate-extraction, winner-gate evaluation,
  promotion-track, ROI, debug/result, timing, threading, or lifecycle changes.
- Keep `score_color_contrast_rescue`, `score_color_temporal_rescue`,
  `score_color_candidate_temporal_boost`, `local_uv_support_count`,
  contrast/ring telemetry, target telemetry, support map, blob extraction,
  winner-gate application, promotion tracks, debug export, and timing in
  `anomaly_analysis.c`.
- Preserve the current `ANOMALY_COLOR_FRONTEND_FRESH_YUV` effective-mode
  fallback to `ANOMALY_COLOR_FRONTEND_FRESH_RGBA`.
- Preserve the distinction between effective frontend mode and fresh-winner
  gate mode.

Validation:

- `git diff --check`
- `cmake --build tools/anomaly_test/build_timing`
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`
- `tools/anomaly_test/build_timing/anomaly_test`
- Parent gate: `./gradlew :app:compileDebugKotlin`
- Full Color regression is optional unless implementation touches scoring call
  sites beyond renaming these helpers.

Risk:

- Very low. The main risk is accidentally changing the YUV-to-RGBA fallback or
  allowing fresh-mode temporal rescue where legacy-only behavior is expected.

Worker notes:

- Added the three frontend-mode helpers as `static inline` helpers in
  `anomaly_color_detector.h`.
- Removed only the old local definitions from `anomaly_analysis.c` and updated
  the existing call sites to the `anomaly_color_*` names.
- Added focused native tests for NULL config, legacy/fresh RGBA/fresh YUV
  effective mode, legacy-only pre-support temporal rescue, and fresh winner
  gate behavior.
- Did not move scoring, support maps, candidate extraction, winner-gate
  evaluation, promotion tracks, ROI/debug/timing/lifecycle, or other Color
  logic.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `302 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old local helper names.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `302 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay decision:

- Full Color replay was not rerun for Packet 25 because the implementation only
  moved `static inline` frontend-mode helpers and mechanically renamed call
  sites. Packet 24 remains the latest full Color manifest/perf gate, and the
  tablet Color smoke after Packet 24 remained nominal.

## Packet 26: Color Neighborhood Contrast Helpers

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 25.

Ownership:

- `app/src/main/cpp/anomaly_color_detector.h`
- `app/src/main/cpp/anomaly_color_detector.c`
- Mechanical call-site update in `app/src/main/cpp/anomaly_analysis.c`
- Focused native harness tests in `tools/anomaly_test/test_anomaly.c`
- This packet ledger

Task:

Move only the pure Color neighborhood contrast helpers into the Color utility
module:

- `compute_local_color_contrast` ->
  `anomaly_color_compute_local_contrast`
- `compute_ring_color_contrast` ->
  `anomaly_color_compute_ring_contrast`

Constraints honored:

- Kept output defaulting, input validation, neighbor/ring loops, math, and
  averaging unchanged.
- Did not move telemetry structs, target telemetry, support maps, scoring,
  `local_uv_support_count`, candidate extraction, winner gate, promotion
  tracks, ROI/debug/timing/lifecycle, or threading.

Worker notes:

- Added public Color utility declarations in `anomaly_color_detector.h`.
- Moved the two helper bodies to `anomaly_color_detector.c`.
- Removed the old local prototypes/definitions from `anomaly_analysis.c` and
  updated the ring contrast call site mechanically.
- Added a synthetic 5x5 ROI test for local 3x3 contrast, outer-ring contrast,
  invalid input/default output behavior, invalid ring radius behavior, and
  invalid center behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `317 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old local contrast
  helper names.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `317 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Residual risk:

- Very low. This packet only moved pure neighborhood contrast math and renamed
  the existing ring call site. Full Color replay was not rerun because no
  scoring thresholds, candidate extraction, support-map construction, or state
  lifecycle logic changed.

## Packet 27: Color Local UV Support Helper

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 26.

Ownership:

- `app/src/main/cpp/anomaly_color_detector.h`
- Mechanical call-site update in `app/src/main/cpp/anomaly_analysis.c`
- Focused native harness tests in `tools/anomaly_test/test_anomaly.c`
- This packet ledger

Task:

Move only the pure local UV support helper into the Color utility header:

- `local_uv_support_count` ->
  `anomaly_color_local_uv_support_count`

Constraints honored:

- Kept the support radius constant local to `anomaly_analysis.c` and passed it
  from the existing call sites.
- Kept input validation, edge clamping, valid-mask handling, and +/-1 UV-bin
  support matching equivalent.
- Did not move scoring, temporal rescue, contrast rescue, target telemetry,
  support maps, candidate extraction, winner gate, promotion tracks,
  ROI/debug/timing/lifecycle, or threading.

Worker notes:

- Added the static inline Color utility helper in `anomaly_color_detector.h`.
- Removed the old local helper body from `anomaly_analysis.c`.
- Updated the two existing call sites to pass
  `ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS`.
- Added focused native tests for 3x3 matching support, edge clamping,
  invalid/null inputs, and `support_radius=0` center-only support.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `326 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old local UV support
  helper name.
- Added a direct `<stdlib.h>` include to `anomaly_color_detector.h` for the
  exported `static inline` helper's `abs()` dependency.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `326 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Residual risk:

- Very low. This packet only moved pure local neighborhood support counting and
  mechanically renamed two existing call sites. Full Color replay was not rerun
  because no scoring thresholds, candidate extraction, support-map
  construction, or state lifecycle logic changed.

## Packet 28: Color History/Defaults Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 27.

Scope:

- Moved the Color history/defaults helpers into `anomaly_color_detector`:
  `anomaly_color_update_recent_histogram`,
  `anomaly_color_default_fresh_distinctness_ratio`, and
  `anomaly_color_clamp_fresh_distinctness_ratio`.
- Kept `anomaly_color_update_recent_histogram` buffer-oriented. Callers pass
  `state->color_recent_hist`, the current frame histogram, reset state, and the
  recovery-selected contribution shift; `anomaly_analysis.c` still owns when
  recovery/reset applies.
- Did not move scoring, temporal rescue, target telemetry, support maps,
  candidate extraction, winner gate, promotion tracks, ROI/debug/timing/
  lifecycle, or threading.

Worker notes:

- Removed the old local helper bodies from `anomaly_analysis.c`.
- Moved the fresh Color distinctness default/min/max constants with the Color
  detector module so existing analysis call sites use the module API.
- Added focused native tests for recent-history reset, decay/contribution,
  recovery shift, saturation, null guards, and distinctness default/clamp
  values.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `338 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old Color
  history/default helper names.
- Renamed the exported history updater's final parameter to `current_shift` so
  the buffer-oriented contract matches the existing call-site semantics.
- Removed a duplicate `<stdlib.h>` include from `anomaly_color_detector.h`.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `338 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet28_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

## Packet 29: Color Target Telemetry Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 28.

Scope:

- Moved the Color debug target telemetry struct and helper into
  `anomaly_color_detector`:
  `anomaly_color_target_telemetry_t` and
  `anomaly_color_compute_target_telemetry`.
- Kept the helper read-only and debug/telemetry-oriented. `anomaly_analysis.c`
  still owns target enablement, target coordinate mapping, histogram counts,
  support scoring, support maps, candidate extraction, winner gates, promotion
  tracks, ROI/result export, timing, lifecycle, and threading.
- Preserved the existing zeroing/null behavior, input validation, patch/ring
  Chebyshev loops, +/-1 UV-bin coherence check, full-refresh and refresh-mask
  fresh counting, roi `fresh_mask` fallback, mean defaults, and ring contrast
  math.

Worker notes:

- Removed the local struct/prototype/body from `anomaly_analysis.c`.
- Renamed the existing call site from `compute_color_target_telemetry` to
  `anomaly_color_compute_target_telemetry`.
- Added a focused synthetic ROI native test for patch valid count, coherent
  count, coherent fresh count under full refresh, explicit refresh mask,
  roi `fresh_mask` fallback, patch/ring means, ring contrast, invalid center,
  invalid/null inputs, invalid radii, and no-ring default output.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `364 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old telemetry helper
  name, and confirmed result export still reads the same telemetry fields.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `364 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet29_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

Residual risk:

- Expected low. This packet moves a pure telemetry helper and does not alter
  Color scoring, candidate selection, state lifecycle, or result export
  semantics.

## Packet 30: Color RGBA Sampling Primitive Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 29.

Scope:

- Moved the low-level RGBA sampling and dense pixel threshold helpers into
  `anomaly_color_detector`:
  `anomaly_color_sample_pixel_yuv`, `anomaly_color_sample_cell`, and
  `anomaly_color_dense_pixel_matches`.
- Kept `anomaly_color_sample_cell` as a wrapper over
  `anomaly_color_sample_pixel_yuv` so the old sampled-cell semantics continue
  to share the exact zeroing, bounds, stride, and RGB-to-luma/U/V math.
- Left dense seed ranking, dense refinement, dense component verification,
  sampling-state preparation, support maps, candidate extraction, scoring,
  winner gates, promotion tracks, ROI/result export, timing, lifecycle, and
  threading in `anomaly_analysis.c`.

Worker notes:

- Removed the old local `sample_color_pixel_yuv`, `sample_color_cell`, and
  `dense_color_pixel_matches` helpers from `anomaly_analysis.c`.
- Updated existing local call sites to the new `anomaly_color_*` helper names.
- Added focused native coverage for RGBA sampling values, null output safety,
  invalid/null input zeroing, short-stride zeroing, out-of-bounds zeroing,
  sample-cell wrapper parity, and dense pixel threshold true/false behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `382 passed, 0 failed`.

Parent validation:

- Parent review found no remaining C call sites for the old local sampling
  helper names.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `382 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet30_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

Residual risk:

- Expected low. This packet only moves pure sampling/math helpers and does not
  alter higher-level Color detector flow.

## Packet 31: Color Candidate Scalar Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 30.

Scope:

- Moved two pure scalar Color candidate helpers into `anomaly_color_detector`:
  `anomaly_color_candidate_scene_commonness` and
  `anomaly_color_small_target_priority_scale`.
- Left winner gates, support maps, dense verifier/ranking, candidate
  extraction, promotion tracks, lifecycle/timing/threading, result export, and
  public AD APIs in `anomaly_analysis.c`.

Worker notes:

- Removed the old local helper bodies from `anomaly_analysis.c`.
- Renamed the two existing call sites to the `anomaly_color_*` helper names.
- Added focused native tests for scene-commonness weighting/clamping and the
  legacy small-target priority scale thresholds.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `390 passed, 0 failed`.

Parent validation:

- Parent review found only the two intended `anomaly_analysis.c` call-site
  renames and no remaining old local helper definitions.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `390 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet31_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

## Packet 32: Color Dense Seed Utility Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 31.

Scope:

- Moved the pure Color dense-seed utility surface into
  `anomaly_color_detector.h`: `anomaly_color_dense_seed_t`,
  `ANOMALY_COLOR_DENSE_SEED_TOP_K`,
  `ANOMALY_COLOR_DENSE_SEED_NMS_RADIUS`,
  `anomaly_color_support_seed_is_local_peak`,
  `anomaly_color_score_dense_seed`,
  `anomaly_color_find_seed_bounds_from_evidence`, and
  `anomaly_color_insert_dense_seed`.
- Left support-map construction, dense pixel ranking/refinement/verifier,
  candidate build/rescue/extraction, winner gates, promotion tracks,
  lifecycle/timing/threading, result export, and public AD APIs in
  `anomaly_analysis.c`.

Worker notes:

- Removed the old local dense-seed typedef/helper bodies from
  `anomaly_analysis.c`.
- Renamed existing call sites to the `anomaly_color_*` helper names.
- Added focused native coverage for local-peak stronger-neighbor/tie behavior,
  dense-seed score invalid/default/deterministic-map behavior, evidence bounds
  defaults and active-bounds output, and dense-seed insertion ordering, NMS
  replacement, max-count, and top-k cap behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `408 passed, 0 failed`.

Parent validation:

- Parent review found the extracted formulas, tie-breaking, and ordered/NMS
  insertion matching the old local helper bodies; the earlier duplicate worker
  result was superseded by the current 408-test working tree.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `408 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet32_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

## Packet 33: Color Sampling Phase/Coordinate Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 32.

Scope:

- Moved only the Color sampling phase and coordinate helper primitives into
  `anomaly_color_detector.h`:
  `anomaly_color_sampling_phase_for_frame`,
  `anomaly_color_advance_sampling_phase`, and
  `anomaly_color_compute_sample_xy`.
- Left `prepare_color_sampling_state`, registration inverse helpers, ROI
  snapshot/carry-forward lifecycle, support-map construction, candidate
  extraction, dense verifier/ranking, winner gates, promotion tracks, result
  export, timing, threading, public AD APIs, and scoring logic in
  `anomaly_analysis.c`.

Worker notes:

- Removed the old local sampling helper bodies from `anomaly_analysis.c`.
- Renamed the existing `anomaly_analysis.c` call sites to the
  `anomaly_color_*` helper names.
- Added focused native coverage for the stable phase-zero/no-op behavior and
  sample coordinate clamping at normal cells, oversized phases, negative
  phases, and ROI edge cells.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `414 passed, 0 failed`.

Parent validation:

- Parent review found the no-op phase behavior and sample-coordinate clamp math
  matching the old local helpers; old local helper names are gone.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `414 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full Color replay intentionally skipped: this packet moved no scoring,
  seed-ordering, support-map, candidate-extraction, or sampling-state lifecycle
  logic. Packet 32 remains the latest full Color replay gate.

## Packet 34: Color Blob Neighbor Similarity Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 33.

Scope:

- Moved only the pure Color blob-neighbor similarity helper into
  `anomaly_color_detector.h` as
  `anomaly_color_blob_neighbor_similarity`.
- Preserved the exact null/missing-array guards, valid-mask checks, UV-bin
  delta weighting, U/V chroma distance, luma delta, clamp math, and final
  weighted score.
- Left support-map construction, cohesion-weight construction, candidate
  extraction/rescue/build, dense verifier/ranking/refinement, winner gates,
  promotion tracks, sampling-state lifecycle, result export, timing/threading,
  public AD APIs, and scoring thresholds in `anomaly_analysis.c`.

Worker notes:

- Removed the old local `color_blob_neighbor_similarity` body from
  `anomaly_analysis.c`.
- Renamed the existing Color blob/cohesion call sites to the
  `anomaly_color_*` helper name.
- Added focused native coverage for NULL/missing-array handling, invalid
  lhs/rhs valid masks, identical-bin/luma score of `1.0`, and deterministic
  weighted/clamped delta scoring on a tiny ROI.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `421 passed, 0 failed`.

Parent validation:

- Parent review found the extracted formula and the four Color blob/cohesion
  call-site renames matching the old local helper behavior.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `421 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet34_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.

## Packet 35: Color Candidate Bbox Normalization Helper Extraction

Status: complete by parent pass.

Mode: code change, behavior-preserving, after Packet 34.

Scope:

- Moved only the pure Color debug/result bbox normalization helper into
  `anomaly_color_detector.h` as `anomaly_color_candidate_bbox_norm`.
- Preserved the exact zero-output initialization, invalid-box return,
  sample-step expansion math, ROI/frame normalization, and clamp behavior.
- Left support-map construction, candidate extraction/rescue/build, dense
  verifier/ranking/refinement, winner gates, promotion tracks, sampling-state
  lifecycle, scoring, result structure layout, timing/threading, and public AD
  APIs in `anomaly_analysis.c`.

Parent notes:

- Removed the old local `color_candidate_bbox_norm` body from
  `anomaly_analysis.c`.
- Renamed the four Color debug/result export call sites to the
  `anomaly_color_*` helper name.
- Added focused native coverage for the legacy expansion math, frame clamping,
  invalid-box zeroing, and NULL output handling.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.

- `tools/anomaly_test/build_timing/anomaly_test`: `433 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full Color replay intentionally skipped: this packet moved no scoring,
  seed-ordering, support-map, candidate-extraction, or sampling-state lifecycle
  logic. Packet 34 remains the latest full Color replay gate.

## Packet 36: Color Candidate Temporal Boost Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 35.

Scope:

- Moved only the Color candidate temporal boost helper into
  `anomaly_color_detector.h` as
  `anomaly_color_score_candidate_temporal_boost`.
- Preserved legacy frontend, NULL state/config, inactive accumulator,
  min-hit clamp, grid-bound clamp, distance-radius, and exact boost formula
  behavior.
- Left `score_color_temporal_rescue`, `score_color_contrast_rescue`,
  support-map construction, cohesion-weight construction, candidate
  extraction/rescue/build, dense verifier/ranking/refinement, winner gates,
  promotion tracks, sampling-state lifecycle, result export, timing/threading,
  public AD APIs, and scoring thresholds in `anomaly_analysis.c`.

Worker notes:

- Removed the old local `score_color_candidate_temporal_boost` body from
  `anomaly_analysis.c`.
- Renamed the single existing call site to the `anomaly_color_*` helper name.
- Added focused native coverage for legacy frontend, NULL state/config,
  inactive and insufficient-hit accumulators, out-of-radius candidates, exact
  in-radius formula, and `min_hits < 1` behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `441 passed, 0 failed`.

Parent validation:

- Parent review found the extracted formula and single call-site rename
  matching the old local helper behavior.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `441 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet36_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were faster than prior host runs (`0.629x` baseline and
  `0.094x` dense gold), but treat that as host/load variance unless repeated.

## Packet 37: Color Temporal Rescue Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 36.

Scope:

- Moved only the local Color temporal rescue helper into
  `anomaly_color_detector.h` as `anomaly_color_score_temporal_rescue`.
- Renamed the two pre-support temporal rescue call sites in
  `anomaly_analysis.c`.
- Moved the existing `ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN` macro unchanged
  into the Color header because the extracted helper and focused harness tests
  both require that threshold.
- Preserved NULL state/config, sampled-frame gating, active accumulator gating,
  min-hit clamp, sample-grid dimension guards, prior-grid clamping, Chebyshev
  radius gating, proximity formula, hit/hold/support strength formula, and
  temporal rescue score base/range behavior.

Exclusions:

- Left `score_color_contrast_rescue`, support-map construction, local UV
  support, cohesion weights, candidate extraction/rescue/build, dense
  verifier/ranking/refinement, winner gates, promotion tracks, sampling
  lifecycle, result export, timing/threading, public AD APIs, and other
  thresholds untouched.

Tests:

- Added focused native harness coverage for NULL state/config, unsampled
  frames, inactive accumulator, local support below minimum, insufficient
  hits, bad grid dimensions, out-of-radius candidates, exact in-radius formula,
  prior-grid clamping, and `min_hits < 1` behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `453 passed, 0 failed`.

Parent validation:

- Parent review found the extracted formula and two call-site renames matching
  the old local helper behavior.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `453 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet37_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.635x` baseline and `0.093x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 42: Color Support Patch-Radius Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 41.

Scope:

- Moved only the Color support-map patch-radius math into
  `anomaly_color_detector.h` as `anomaly_color_support_patch_radius`.
- Kept `effective_color_target_span_px(...)` calls in `anomaly_analysis.c` and
  passed the resulting span into the Color helper, so the Color header does not
  inherit Thermal or target-span policy dependencies.
- Renamed the four existing support-radius call sites to use the Color module
  helper.
- Preserved `sample_step <= 0` fallback to `1`, the
  `lroundf(fmaxf(1.0f, (0.5f * target_span_px) / step))` formula, the upper
  cap at `4`, and the absence of any lower clamp after rounding beyond the
  `fmaxf` input.

Exclusions:

- Left `effective_color_target_span_px`, support-map construction, candidate
  extraction, sampling state, dense verification, candidate insertion/ranking,
  target traces, promotion tracks, winner gates, result export,
  timing/threading, public AD APIs, and thresholds untouched.

Tests:

- Added focused native harness coverage for normal radius calculation,
  `sample_step <= 0` fallback, minimum radius of `1`, cap at `4`, and rounding
  behavior below and at `.5`.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `623 passed, 0 failed`.

Parent validation:

- Parent review found the helper preserves the old radius formula and that each
  production call still computes `effective_color_target_span_px(...)` in
  `anomaly_analysis.c` before calling the Color helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `623 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet42_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.619x` baseline and `0.093x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 42: Color Support Patch-Radius Helper Extraction

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 41.

Scope:

- Moved only the Color support-map patch-radius formula into
  `anomaly_color_detector.h` as `anomaly_color_support_patch_radius`.
- Updated the existing support-map and Color post-processing call sites in
  `anomaly_analysis.c` to compute `effective_color_target_span_px(...)` locally
  and pass the resulting span into the Color helper.
- Kept target-span policy and Thermal-derived sizing outside the Color module,
  so the Color header does not gain a Thermal dependency.
- Preserved sample-step fallback to `1`, the exact
  `lroundf(fmaxf(1.0f, (0.5f * target_span_px) / step))` calculation, the cap
  at `4`, and the absence of any lower clamp after rounding beyond the
  `fmaxf(1.0f, ...)` input.

Exclusions:

- Left `effective_color_target_span_px`, support-map construction, candidate
  extraction, sampling state, dense verification, candidate insertion/ranking,
  target traces, promotion tracks, winner gates, result export,
  timing/threading, public AD APIs, and all thresholds untouched.

Tests:

- Added focused native harness coverage for normal radius calculation,
  `sample_step <= 0` fallback, minimum radius of `1`, cap at `4`, and rounding
  behavior immediately below and at `.5`.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `623 passed, 0 failed`.

Full Color replay:

- Not run in this worker packet; left for parent validation.

App/tablet validation:

- Not run in this packet.

## Packet 38: Color Contrast Rescue Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 37.

Scope:

- Moved only the local Color contrast rescue helper into
  `anomaly_color_detector.h` as `anomaly_color_score_contrast_rescue`.
- Renamed the two contrast rescue call sites in `anomaly_analysis.c`.
- Moved the existing contrast-rescue macro constants unchanged into the Color
  header because the extracted helper and focused harness tests both require
  those thresholds and score constants.
- Preserved NULL ROI, sampled-frame gating, local-support threshold, center
  chroma calculation, ring contrast radius `1..3`, minimum neighbor count,
  grayscale guard, chroma/luma strength formulas, contrast threshold,
  support strength, and final base/range score formula.

Exclusions:

- Left support-map construction, local UV support, cohesion weights, candidate
  extraction/rescue/build, dense verifier/ranking/refinement, winner gates,
  promotion tracks, sampling lifecycle, result export, timing/threading,
  public AD APIs, and all other thresholds untouched.

Tests:

- Added focused native harness coverage for NULL ROI, unsampled frame, local
  support below minimum, insufficient ring neighbors, invalid center ROI,
  grayscale guard, below contrast threshold, and exact nonzero formula.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `461 passed, 0 failed`.

Parent validation:

- Parent review found the extracted formula and two call-site renames matching
  the old local helper behavior.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `461 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet38_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.630x` baseline and `0.092x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 39: Fresh Color Winner Gate Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 38.

Scope:

- Moved only the pure fresh Color winner gate decision into
  `anomaly_color_detector.h` as `anomaly_color_evaluate_fresh_winner_gate`.
- Moved the existing fresh winner gate macro constants unchanged into the Color
  header because the extracted helper and focused harness tests both require
  those thresholds.
- Kept the `effective_thermal_small_target_span_px(cfg, width, height)` call in
  `anomaly_analysis.c` at the winner-gate call site and passed the resulting
  `small_target_span_px` into the Color helper, so the Color header does not
  include or depend on Thermal internals.
- Preserved sample-step fallback to `1.0f`, max span/area formulas,
  commonness material-extent formulas, min rarity/max commonness, output debug
  threshold plumbing, oversize/commonness combined reason ordering, and return
  reasons.

Exclusions:

- Left candidate extraction, candidate ranking, support maps, dense
  verifier/ranking/refinement, promotion tracks, target traces, result export,
  timing/threading, public AD APIs, and all behavior thresholds beyond
  relocating the unchanged winner-gate constants untouched.

Tests:

- Added focused native harness coverage for clean/no rejection, size rejection,
  common oversize rejection, commonness-without-oversize preserving the legacy
  no-reject behavior, size+commonness combined reason ordering, sample-step
  fallback for `sample_step <= 0`, and output threshold values.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `473 passed, 0 failed`.

Parent validation:

- Parent review found the helper preserves the existing winner-gate formulas,
  output threshold plumbing, return-reason ordering, and the current
  commonness-without-oversize no-reject behavior.
- Parent review also confirmed the Color header has no Thermal include or
  Thermal helper dependency; `anomaly_analysis.c` computes the small-target
  span and passes it into the Color helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `473 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet39_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.617x` baseline and `0.093x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 40: Color Seed Region Suppression Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 39.

Scope:

- Moved only the Color seed-region visited marking helper into
  `anomaly_color_detector.h` as `anomaly_color_suppress_seed_region`.
- Used a pure bounds-based signature: the helper accepts candidate
  `min_x/min_y/max_x/max_y` values directly instead of taking
  `anomaly_color_blob_candidate_t`.
- Renamed the existing call site in `anomaly_analysis.c` to pass candidate
  bounds directly, avoiding a new appearance-candidate type dependency in the
  Color header.
- Preserved NULL visited no-op, invalid dimension no-op, one-cell padding,
  clipping to active scan bounds, clipping to sample-grid bounds, inverted
  final bounds no-op, and row-major visited marking.

Exclusions:

- Left candidate extraction, support-map construction, dense verification,
  candidate insertion/ranking, target traces, promotion tracks, winner gates,
  result export, timing/threading, public AD APIs, and all thresholds
  untouched.

Tests:

- Added focused native harness coverage for NULL/no-op safety, invalid
  dimensions leaving visited unchanged, one-cell padding exact cells, clipping
  to scan bounds, clipping to grid bounds, inverted final bounds no-op, and
  row-major marking count/positions.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `592 passed, 0 failed`.

Parent validation:

- Parent review found the extracted helper preserves the old padding, clipping,
  inverted-bounds no-op, and row-major visited marking behavior. The helper
  remains bounds-based and adds no appearance-candidate type dependency.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `592 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet40_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.617x` baseline and `0.093x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 41: Color Blob Cohesion-Weight Helper Extraction

Status: complete by worker pass and parent validation.

Mode: code change, behavior-preserving, after Packet 40.

Scope:

- Moved only the Color blob cohesion-weight computation into
  `anomaly_color_detector.h` as
  `anomaly_color_compute_blob_cohesion_weights`.
- Renamed the existing sampled-grid prep call site in `anomaly_analysis.c` to
  call the Color module helper.
- Kept the helper dependency-light: it uses the existing
  `anomaly_roi_state_t`, frontend mode, sample-grid dimensions, and
  `anomaly_color_blob_neighbor_similarity`.
- Preserved NULL/invalid guards, legacy valid/invalid sample weights, fresh
  invalid sample zeroing, fresh no-similarity fallback to `1.0f`,
  similarity/coherent-neighbor accumulation, the
  `0.80f * mean_similarity + 0.20f * coherent_bonus` formula, and the final
  `0.70f..1.20f` clamp via the existing Color clamp helper.

Exclusions:

- Left support-map construction, candidate extraction, dense verification,
  candidate insertion/ranking, target traces, promotion tracks, winner gates,
  result export, timing/threading, public AD APIs, and all thresholds
  untouched.

Tests:

- Added focused native harness coverage for invalid/no-op behavior, legacy
  valid and invalid weights, fresh invalid sample zeroing, fresh no-similarity
  fallback to `1.0f`, a fresh coherent-neighbor exact formula case, and a full
  coherent-neighborhood formula case.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `617 passed, 0 failed`.

Parent validation:

- Parent review found the extracted helper preserves the old legacy/fresh
  branching, invalid guards, similarity accumulation, no-similarity fallback,
  coherent-neighbor formula, and clamp behavior. The single production call
  site is a direct rename.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `617 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Visible-color regression output:
  `/private/tmp/rid2c_packet41_color_regression/suite_report.md`
  - Red1 baseline stayed at current seed behavior: recall 0.000 with TP 0,
    FP 0, miss 15.
  - Red1 dense gold stayed at precision 1.000 / recall 1.000 with TP 15,
    FP 0, miss 0.
- Replay runtimes were `0.615x` baseline and `0.093x` dense gold; treat these
  as host/load observations, not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 43: Color Support Patch Score Helper Extraction

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 42.

Scope:

- Moved only the pure per-cell Color support-score math from
  `build_color_support_map(...)` into `anomaly_color_detector.h` as
  `anomaly_color_score_support_patch`.
- Added `anomaly_color_support_score_t` carrying the computed support,
  seed floor, compact prominence, and core share.
- Kept support-map traversal, raw/contrast map ownership, scratch-map
  lifecycle, seed-bound bookkeeping, max-support updates, and candidate
  extraction in `anomaly_analysis.c`.
- Moved the existing support compact-peak constants unchanged into the Color
  header:
  `ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_SEED_FLOOR` and
  `ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_MAX_CELLS`.
- Preserved legacy support formula, fresh distinctness gate, compact
  prominence, core share, support weight, fresh seed-floor nudge, `0..4`
  support clamp, compact-peak floor promotion, and legacy seed floor `0.55`.

Exclusions:

- Did not move `build_color_support_map(...)`, neighborhood traversal,
  scratch/copy region helpers, support-map lifecycle, candidate extraction,
  dense verification, candidate insertion/ranking, target traces, promotion
  tracks, winner gate, result export, timing/threading, public AD APIs, or
  unrelated behavior thresholds.

Tests:

- Added focused native harness coverage for the helper:
  - legacy exact formula and fixed seed floor;
  - fresh exact formula with distinctness gate;
  - seed-floor nudge from fresh distinctness ratio;
  - compact-peak floor promotion;
  - contrast-weight low/high clamp behavior;
  - final support clamp to `0..4`.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `635 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `635 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Full Color replay:

- Ran Red1 visible-color seed manifest:
  `/private/tmp/rid2c_packet43_color_regression/suite_report.md`.
- Red1 baseline stayed at current seed behavior: recall `0.000`, TP `0`,
  FP `0`, miss `15`, realtime `0.586x`.
- Red1 dense gold stayed at precision `1.000` / recall `1.000`, TP `15`,
  FP `0`, miss `0`, realtime `0.093x`.
- Runtime numbers are host/load observations only, not proof of a real
  performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 44: Color Support Debug Scalar Helper Reuse

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 43.

Scope:

- Added tiny pure support-map scalar helpers to
  `anomaly_color_detector.h`:
  `anomaly_color_support_distinctness_ratio`,
  `anomaly_color_support_distinctness_gate`,
  `anomaly_color_support_compact_prominence`,
  `anomaly_color_support_core_share`, and
  `anomaly_color_support_seed_floor`.
- Updated `anomaly_color_score_support_patch(...)` to use those helpers while
  preserving the existing legacy formula, fresh distinctness gate, compact
  prominence, core share, seed-floor nudge, support weighting, support clamp,
  and compact-peak promotion behavior.
- Updated the color target support-map debug metric block in
  `anomaly_analysis.c` to use the same helpers for duplicate scalar math.
- Preserved the existing debug semantics where compact prominence and core
  share are still computed for the target debug block in legacy mode, while
  seed floor remains legacy fixed at `0.55f`.

Exclusions:

- Did not move support-map traversal, support-map construction, scratch-map
  lifecycle, candidate extraction, dense verification, target traces, promotion
  tracks, winner gates, result export, timing/threading, public AD APIs, Color
  replay behavior, Gradle behavior, or unrelated thresholds.

Tests:

- Added focused native harness coverage for the new scalar helpers:
  distinctness ratio including the ring floor, distinctness gate low/high
  clamps, compact prominence, core share including the local-peak floor, legacy
  seed floor, and fresh max seed-floor nudge.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `645 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `645 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Full Color replay:

- Ran Red1 visible-color seed manifest:
  `/private/tmp/rid2c_packet44_color_regression/suite_report.md`.
- Red1 baseline stayed at current seed behavior: recall `0.000`, TP `0`,
  FP `0`, miss `15`, realtime `0.611x`.
- Red1 dense gold stayed at precision `1.000` / recall `1.000`, TP `15`,
  FP `0`, miss `0`, realtime `0.094x`.
- Runtime numbers are host/load observations only, not proof of a real
  performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 45: ScanPlanner Selective Refresh Mask Builder

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 44.

Scope:

- Added `anomaly_scan_planner_build_selective_refresh_mask(...)` to
  `anomaly_scan_planner.{h,c}` as the ScanPlanner-owned helper for selective
  refresh mask construction.
- Removed the pure mask-selection callback from `anomaly_scan_planner_ops_t`;
  ScanPlanner now calls its own helper after using the existing parent-owned
  `ensure_refresh_mask_capacity` allocation hook.
- Removed the old `build_selective_refresh_mask(...)` implementation and
  wrapper from `anomaly_analysis.c`.
- Moved the scan-flag constants used by ROI cell summaries into the
  ScanPlanner contract so ROI summarization can keep setting the same flags and
  ScanPlanner can consume them directly.
- Preserved selective refresh behavior for invalid inputs, target-only required
  flags, partial required flags, previous-sample lookup handling, sparse
  fallback phase from `frame_counter`, too-broad fallback, selected-count
  updates, and reason flags.

Exclusions:

- Did not move ROI cell summarization, target revisit annotation, state
  allocation, scan-plan decision logic, color sampling prep, adaptive stride
  tuning, thresholds, timing semantics, threading, public AD APIs, Gradle
  behavior, or replay behavior.

Tests:

- Added focused native harness coverage for the moved helper:
  invalid input, partial selection by cell flags, target-only empty rejection,
  sparse fallback, and too-broad fallback.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `664 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `664 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Black-hot IR replay:

- Ran default black-hot manifest:
  `/private/tmp/rid2c_packet45_ir_regression/suite_report.md`.
- Current detector baseline stayed at precision `0.965` / recall `0.197`.
- Dense full-scan gold stayed at precision `1.000` / recall `0.362`.
- Redesigned incremental stayed at precision `0.994` / recall `0.599`.

Full Color replay:

- Ran Red1 visible-color seed manifest:
  `/private/tmp/rid2c_packet45_color_regression/suite_report.md`.
- Red1 baseline stayed at current seed behavior: recall `0.000`, TP `0`,
  FP `0`, miss `15`, realtime `0.229x`.
- Red1 dense gold stayed at precision `1.000` / recall `1.000`, TP `15`,
  FP `0`, miss `0`, realtime `0.040x`.
- Runtime numbers, especially Color dense gold, were host-load contaminated on
  this run and are not proof of a real performance shift.

App/tablet validation:

- Not run in this packet.

## Packet 46: ScanPlanner ROI Cell Geometry Contract

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 45.

Scope:

- Exposed `ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX` and
  `anomaly_scan_planner_roi_grid_cell_span(int sample_step)` from
  `anomaly_scan_planner.h` as the shared ScanPlanner ROI cell geometry rule.
- Replaced the duplicate local ROI cell target/span helpers in
  `anomaly_scan_planner.c` and `anomaly_analysis.c`.
- Kept the exact target size `16`, `sample_step <= 0` fallback to `1`, and
  `(target + sample_step - 1) / sample_step` rounding behavior.
- Kept ScanPlanner selective-mask consumption and anomaly-analysis ROI summary
  production on the same helper without moving ROI state mutation or target
  revisit annotation.

Exclusions:

- Did not move ROI state mutation, ROI summarization, target revisit
  annotation, selective mask logic, adaptive stride, color sampling prep,
  thermal/color scoring, result export, timing/threading, public APIs,
  scan-zone policy, thresholds, Gradle behavior, or replay behavior.

Tests:

- Added focused native harness coverage for the shared ROI grid span contract:
  target size, zero sample step, negative sample step, exact division,
  non-exact rounded division, and span floor behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `673 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `673 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Black-hot IR replay:

- Ran default black-hot manifest:
  `/private/tmp/rid2c_packet46_ir_regression/suite_report.md`.
- Current detector baseline stayed at precision `0.965` / recall `0.197`.
- Dense full-scan gold stayed at precision `1.000` / recall `0.362`.
- Redesigned incremental stayed at precision `0.994` / recall `0.599`.

Full Color replay:

- Ran Red1 visible-color seed manifest:
  `/private/tmp/rid2c_packet46_color_regression/suite_report.md`.
- Red1 baseline stayed at current seed behavior: recall `0.000`, TP `0`,
  FP `0`, miss `15`, realtime `0.259x`.
- Red1 dense gold stayed at precision `1.000` / recall `1.000`, TP `15`,
  FP `0`, miss `0`, realtime `0.044x`.
- Runtime numbers are observational host-side replay values, not proof of a
  promoted realtime posture.

App/tablet validation:

- Not run in this packet.

## Packet 47: Internal Frame/ROI Bounds Geometry

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 46.

Scope:

- Added header-only internal frame geometry contract
  `app/src/main/cpp/anomaly_frame_geometry.h`.
- Named `anomaly_frame_roi_bounds_t` and pure helpers for centered scan-zone
  ROI bounds and registration ROI bounds.
- Replaced only the old local static bounds helpers in `anomaly_analysis.c`
  call sites.
- Preserved existing centered ROI behavior: invalid dimensions, scan-zone
  clamp/fallback, truncating centered margins, and minimum valid tiny-frame
  bounds.
- Preserved current registration behavior as full-frame bounds for valid
  dimensions and zero max bounds for invalid dimensions.

Exclusions:

- Did not move ROI state mutation, ROI summarization, target revisit
  annotation, previous-sample lookup construction, selective-mask logic,
  adaptive stride, color rarity, scan-zone policy, thermal scoring,
  timing/threading, public APIs, CMake, Gradle, or replay behavior.

Tests:

- Added focused native harness coverage for centered ROI clamp/fallback,
  representative centered bounds, tiny valid bounds, legacy invalid centered
  bounds, representative registration bounds, and invalid registration bounds.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `680 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `680 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun for this packet because Packet 46 just
  refreshed IR and Red1 parity, and Packet 47 only moves pure bounds geometry
  with focused native coverage.

## Packet 48: ROI State Lifecycle Helper Module

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 47.

Scope:

- Added plain-C internal `anomaly_roi_state.{h,c}` for
  `anomaly_roi_state_t` lifecycle/allocation helpers only.
- Moved the ROI-owned pixel-buffer capacity helper, ROI cell-summary capacity
  helper, clear helper, and release helper behind named functions:
  `anomaly_roi_state_ensure_pixel_capacity(...)`,
  `anomaly_roi_state_ensure_cell_capacity(...)`,
  `anomaly_roi_state_clear(...)`, and
  `anomaly_roi_state_release(...)`.
- Updated `anomaly_analysis.c` call sites mechanically.
- Added the new C module to the Android native and native harness CMake source
  lists.
- Added focused native coverage for pixel capacity allocation, zero-count and
  NULL behavior, cell capacity allocation, clear preserving capacity while
  zeroing the same ROI-owned fields/buffers, and release zeroing the struct.

Non-goals:

- Did not move `ensure_prev_roi_snapshot_capacity(...)`; it still owns
  `anomaly_state_t` scratch fields.
- Did not move ROI mutation, carry-forward, selective/full refresh, target
  revisit annotation, ROI cell summarization, previous-sample lookup, scan-zone
  policy, Color rarity, Laplace sidecar, thermal scoring, timing/threading, or
  public APIs.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `705 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `705 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a mechanical
  lifecycle extraction with focused native coverage and no ROI mutation,
  scoring, refresh, timing, or public API behavior change.

## Packet 49: ROI Cell Summary Producer Helper

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 48.

Scope:

- Moved only ROI cell-summary production into
  `anomaly_roi_state_summarize_cells(...)` in `anomaly_roi_state.{h,c}`.
- Kept the summary loop behavior aligned with the previous local
  `summarize_roi_cells(...)`: NULL/invalid ROI rejection, invalid geometry
  rejection, ROI cell-summary capacity allocation, summary zeroing, ScanPlanner
  cell-span helper use, valid/fresh/carried/new/stale counts, scan flags, max
  thermal/color/motion, and registration-quality fallback.
- Updated full-refresh, selective-refresh, and age-one-frame call sites in
  `anomaly_analysis.c` to pass `ANOMALY_ROI_REALTIME_CARRY_EXPIRY` and
  `registration_health_confidence(registration_health)`.
- Reused `ANOMALY_SCAN_FLAG_*` and
  `anomaly_scan_planner_roi_grid_cell_span(...)` from ScanPlanner.
- Added focused native coverage near ROI lifecycle tests for invalid helper
  rejection, summary allocation/counts/flags, strict stale expiry, low
  registration confidence, max thermal/color/motion, and registration-quality
  fallback.

Non-goals:

- Did not move target-revisit annotation, target revisit radius/gate helpers,
  ROI mutation/carry-forward, full/selective refresh, age-one-frame mutation,
  previous ROI snapshots, previous-sample lookup, scan-zone policy, Color
  rarity, Laplace sidecar, thermal scoring, timing/threading, JNI/public APIs,
  or adaptive-stride policy.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `726 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `726 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a bounded ROI summary
  producer extraction with focused native coverage and no ROI mutation,
  target-revisit, scoring, timing, or public API behavior change.

## Packet 50: Target-Revisit Policy Helper Module

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 49.

Scope:

- Added plain-C internal `anomaly_target_revisit.{h,c}` for pure
  target-track/revisit policy helpers only.
- Exported the named policy contract:
  `anomaly_target_revisit_track_count(...)`,
  `anomaly_target_revisit_adaptive_track_risk(...)`,
  `anomaly_target_revisit_radius_for_track(...)`, and
  `anomaly_target_revisit_point_inside_gate(...)`.
- Preserved active-track filtering, forced/missed/confidence revisit count,
  adaptive risk and weak-lock flags, min-hit fallback, provisional/missed
  radius scaling, radius clamps, point-gate default outputs, and closest
  matching forced-revisit track behavior.
- Replaced call sites mechanically in `anomaly_analysis.c`, including
  ScanPlanner wrapper callbacks, track cleanup, revisit confirmation,
  selective-refresh off-gate suppression, and target-revisit annotation radius
  lookup.
- Added the new C module to Android native and native harness CMake sources.

Non-goals:

- Did not move `annotate_target_revisit_cells(...)`.
- Did not move `find_target_revisit_confirmation(...)`.
- Did not move ROI full/selective/age-one-frame mutation, target-track
  lifecycle/update functions, scoring, thermal/color rarity, Laplace sidecar,
  scan-zone promotion, timing/threading, JNI/public APIs, or adaptive-stride
  policy.

Tests:

- Added focused native coverage for NULL/default point-gate outputs,
  target-revisit count conditions, adaptive risk and weak-lock flags, radius
  scaling/clamping, and closest-match point-inside-gate output behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `756 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `756 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a pure target-revisit
  policy extraction with focused native coverage and no annotation, scoring,
  ROI mutation, timing, or public API behavior change.

## Packet 51: Target-Revisit ROI Annotation Helper

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 50.

Scope:

- Moved the existing target-revisit ROI cell annotation implementation from
  `anomaly_analysis.c` into `anomaly_target_revisit.{h,c}` as
  `anomaly_target_revisit_annotate_roi_cells(...)`.
- Preserved the existing NULL/valid/cell guards, active forced-track filter,
  radius helper use, clamp/floor/cell loops, and OR of
  `ANOMALY_SCAN_FLAG_TARGET_REVISIT`.
- Replaced the annotation call sites in `anomaly_analysis.c` mechanically.

Non-goals:

- Did not move `find_target_revisit_confirmation(...)`.
- Did not move `anomaly_revisit_confirmation_t`.
- Did not change scoring, ROI mutation, scan planning, timing, threading, or
  public APIs.

Tests:

- Added focused native coverage for annotation no-op guards, inactive/non-forced
  track filtering, forced-track bounded cell marking with OR preservation, and
  provisional `min_hits` radius scaling.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `860 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `860 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was an OR-only
  target-revisit ROI annotation helper extraction with focused native coverage
  and no scoring, ROI mutation, scan planning, timing, threading, or public API
  behavior change.

## Packet 52: Target-Track Slot Bookkeeping Helpers

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 51.

Scope:

- Added plain-C internal `anomaly_target_tracks.{h,c}` for target-track slot
  bookkeeping helpers only.
- Moved/renamed `clear_target_track(...)`,
  `clear_all_target_tracks(...)`, `find_best_target_track_match(...)`, and
  `allocate_target_track_slot(...)` as
  `anomaly_target_tracks_clear_track(...)`,
  `anomaly_target_tracks_clear_all(...)`,
  `anomaly_target_tracks_find_best_observation_match(...)`, and
  `anomaly_target_tracks_allocate_slot(...)`.
- Preserved the existing NULL behavior, `memset`, `next_target_track_id = 1`
  reset, match gate math, algorithm-mismatch gate, first-inactive allocation,
  and full-table weakest-slot ordering.
- Replaced call sites in `anomaly_analysis.c` mechanically and added the new C
  file to app and native-test CMake source lists.

Non-goals:

- Did not move `predict_target_tracks_with_registration(...)`.
- Did not move `update_target_tracks_from_observations(...)`.
- Did not move `find_target_revisit_confirmation(...)` or
  `anomaly_revisit_confirmation_t`.
- Did not move target-track movement evidence, scoring, ROI clearing,
  timing/threading, public APIs, or anything into `anomaly_target_revisit.*`.

Tests:

- Added focused native coverage for single-track clear, clear-all reset,
  match skipping inactive/already-matched tracks, algorithm mismatch gating,
  first-inactive allocation, and full-table weakest-score allocation.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `984 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `984 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a target-track slot
  bookkeeping helper extraction with focused native coverage and no
  observation update, prediction, scoring, ROI clearing, timing/threading, or
  public API behavior change.

## Packet 53: Target-Track Observation Lifecycle Helper

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 52.

Scope:

- Extracted `update_target_tracks_from_observations(...)` from
  `anomaly_analysis.c` into internal plain-C helper
  `anomaly_target_tracks_update_from_observations(...)`.
- Kept registration quality calculation parent-owned:
  `anomaly_analysis.c` still calls `registration_health_confidence(...)` and
  passes the resulting quality into the target-track helper.
- Kept ROI clearing parent-owned: the new helper returns a bool clear intent,
  and `anomaly_analysis.c` calls `clear_all_roi_tracks(state)` when that intent
  is true.
- Preserved the lifecycle implementation literally: matched-track array,
  observation validity filtering, slot allocation/clear/id assignment/wrap,
  confidence hit gain, publish confirmation carry-forward, hit/miss/hold/fresh
  and forced-revisit flags, miss decay, registration-health carried-miss rules,
  and track clear conditions.
- Moved only lifecycle-local constants into `anomaly_target_tracks.c`:
  `ANOMALY_TARGET_MAX_CARRIED_MISSES`,
  `ANOMALY_TARGET_CONFIDENCE_HIT_GAIN`, and
  `ANOMALY_TARGET_CONFIDENCE_MISS_DECAY`.

Non-goals:

- Did not move `predict_target_tracks_with_registration(...)`.
- Did not move `clear_all_roi_tracks(...)`.
- Did not move `find_target_revisit_confirmation(...)`.
- Did not move target-track movement evidence, scoring, timing, scan planning,
  ROI mutation/clearing implementation, or public APIs.
- Did not centralize `ANOMALY_TARGET_MATCH_GATE`.

Tests:

- Added focused native coverage for allocation/update lifecycle fields,
  matched-slot update and publish-confirmation preservation, unmatched aging and
  registration-health carried-miss behavior, and empty-frame ROI-clear intent
  conditions.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1010 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1010 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a target-track
  observation lifecycle extraction with focused native coverage and no
  registration prediction, revisit confirmation, movement evidence, scoring,
  timing, scan planning, ROI clearing implementation, or public API behavior
  change.

## Packet 54: Target-Track Registration Prediction Helper

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 53.

Scope:

- Extract `predict_target_tracks_with_registration(...)` from
  `anomaly_analysis.c` into internal plain-C helper
  `anomaly_target_tracks_predict_with_registration(...)`.
- Keep the private `anomaly_registration_model_t` boundary in
  `anomaly_analysis.c`; pass registration through `const void *` plus valid and
  invert-point callbacks.
- Keep registration-quality derivation parent-owned:
  `anomaly_analysis.c` passes `registration_health_confidence(...)` into the
  helper as the stored prediction quality.
- Preserve existing behavior literally: NULL/default prediction no-op,
  scene-discontinuity/invalid/hard-degraded clear-all behavior, invalid
  registration no-op, failed inverse forced-revisit, successful inverse clamp,
  stored caller-provided quality, and non-fresh forced-revisit marking.

Non-goals:

- Did not move `registration_model_valid(...)`,
  `registration_invert_point(...)`, `registration_health_confidence(...)`,
  `update_target_track_movement_evidence(...)`,
  `movement_tile_independent_score(...)`, `clear_all_roi_tracks(...)`,
  `age_roi_tracks_one_frame(...)`, `find_target_revisit_confirmation(...)`,
  scoring, timing, scan planning, ROI mutation, public APIs, or CMake files.

Tests:

- Added focused native coverage for NULL/default no-op, scene-discontinuity
  clear, invalid/hard health clear, invalid registration no-op, failed inverse
  forced-revisit, successful inverse clamping/quality update, and non-fresh
  forced-revisit marking.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1030 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1030 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a target-track
  registration-prediction extraction with focused native coverage and no
  registration model implementation change, movement evidence, scoring,
  timing, scan planning, ROI mutation/clearing implementation, or public API
  behavior change.

## Packet 55: ROI Track Clear/Age Lifecycle Helper

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 54.

Scope:

- Added internal plain-C `anomaly_roi_tracks.{h,c}`.
- Moved the existing primary accumulator clear and saliency aux clear helpers
  into `anomaly_roi_tracks.c` as private static helpers.
- Exposed only:
  - `anomaly_roi_tracks_clear_saliency(...)`
  - `anomaly_roi_tracks_clear_all(...)`
  - `anomaly_roi_tracks_age_one_frame(...)`
- Replaced the old `clear_saliency_tracks(...)`, `clear_all_roi_tracks(...)`,
  and `age_roi_tracks_one_frame(...)` call sites mechanically.
- Pointed `scan_planner_ops.age_roi_tracks_one_frame` directly at
  `anomaly_roi_tracks_age_one_frame(...)`.
- Added the new C file to app and native test CMake source lists.

Behavior preserved:

- Primary accumulator clear still resets slots 0..3 through the same field
  assignments.
- Saliency-only clear still resets primary slot 3,
  `saliency_display_algorithm`, and saliency aux state.
- Clear-all still resets primary accumulators, color promotion slots, saliency
  display/aux state, and target tracks through
  `anomaly_target_tracks_clear_all(state)`, including
  `next_target_track_id`.
- Age-one-frame still decrements/clears only primary and saliency aux hold
  counters, leaving color-promotion and target-track state untouched.

Non-goals:

- Did not move `update_saliency_aux_track(...)`, camera-motion compensation of
  `acc_cx`/`acc_cy`, color-promotion update logic, target-track observation
  lifecycle or registration prediction, `update_target_track_movement_evidence(...)`,
  `movement_tile_independent_score(...)`, revisit confirmation, scoring,
  timing, threading, public APIs, or registration model logic.

Tests:

- Added focused native coverage for clear-all reset of primary/color
  promotion/saliency aux/display/target tracks and `next_target_track_id`.
- Added saliency-only clear coverage that verifies slot 3 plus saliency
  display/aux reset while unrelated primary/color/target state is preserved.
- Added age-one-frame coverage for decrement, clear at zero hold, inactive
  primary slot preservation, and color-promotion/target-track preservation.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1129 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1129 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests were not rerun because this was a bounded ROI track
  lifecycle-helper extraction with focused native coverage and no saliency
  update, camera-motion compensation, color-promotion update, target-track
  observation lifecycle/prediction, revisit confirmation, movement evidence,
  scoring, timing, threading, or public API behavior change.

## Packet 56: Motion Tile Interpretation Helpers

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 55.

Scope:

- Moved pure movement tile interpretation helpers from `anomaly_analysis.c` to
  `anomaly_motion_estimator.{h,c}`.
- Exposed only:
  - `anomaly_motion_estimator_tile_independent_score(...)`
  - `anomaly_motion_estimator_tile_is_parallax_like(...)`
  - `anomaly_motion_estimator_tile_is_independent(...)`
- Replaced `anomaly_analysis.c` call sites in target-track movement evidence,
  revisit confirmation search, thermal target trace/debug population, and
  thermal candidate debug export.

Behavior preserved:

- NULL or invalid tiles still score `0.0f` and classify false.
- Independent score still uses residual `(residual_px - 12) / 28`, flow
  `flow_px / 24`, layer score `1.0` for local-outlier and `0.35` for unstable,
  weighted `0.45 / 0.35 / 0.20`, clamped to `0..1`.
- Parallax-like still means background or coherent-near.
- Independent still means local-outlier with independent score `>= 0.50`.

Non-goals:

- Did not move `update_target_track_movement_evidence(...)`,
  `find_target_revisit_confirmation(...)`,
  `thermal_shadow_raw_delta_rescue_score(...)`,
  `thermal_shadow_raw_delta_rescue_eligible(...)`,
  `nearest_motion_candidate_support_norm(...)`, saliency update, color
  promotion, scan planning, timing, public APIs, or result structs.

Tests:

- Added focused native coverage for NULL/invalid tile behavior,
  local-outlier independent classification, background/coherent-near
  parallax-like classification, and unstable partial layer score without
  independent classification.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1145 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1145 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a pure
  MotionEstimator tile-interpretation helper relocation with focused native
  coverage and no detector decision-loop, scoring-policy, timing, scan
  planning, threading, public API, or result-struct behavior change.

## Packet 57: Motion Candidate Support Helpers

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 56.

Scope:

- Moved bounded motion support helper behavior from `anomaly_analysis.c` to
  `anomaly_motion_estimator.{h,c}`.
- Exposed only:
  - `anomaly_motion_estimator_nearest_candidate_support_norm(...)`
  - `anomaly_motion_estimator_stamp_support(...)`
- Replaced `anomaly_analysis.c` call sites in saliency motion support stamping
  and thermal target/local-peak movement-support debug population.

Behavior preserved:

- Nearest candidate support returns `-1.0f` for invalid/null inputs, ignores
  nonpositive support, normalizes `support_x/support_y` by frame dimensions,
  uses squared `max_dist_norm`, and returns the strongest nearby positive
  support or `-1.0f`.
- Motion support stamping no-ops on null `saliency_motion_map`, nonpositive
  dimensions, or nonpositive support; stamps center at `1.0` scale and
  neighbors at `0.55`; keeps the maximum motion support; and lowers
  `saliency_registration_map` only when non-null and the new registration
  scale is smaller.

Non-goals:

- Did not move the full GMV/appearance motion scorer, motion persistence,
  debug top insertion, saliency scoring loop, registration functions, result
  structs, or appearance-proposal scorer boundary.

Tests:

- Added focused native coverage for nearest support invalid inputs, absent
  nearby support, strongest nearby support selection, distant/negative support
  rejection, stamp invalid-input no-op behavior, center/neighbor stamp scales,
  max-motion preservation, nullable registration maps, and registration min
  behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1167 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1167 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a pure
  MotionEstimator support-query/stamp helper relocation with focused native
  coverage and no detector decision-loop, scoring-policy, timing, scan
  planning, threading, public API, or result-struct behavior change.

## Packet 58: MotionEstimator Primitive Extraction

Status: parent-validated.

Mode: code change, behavior-preserving, after Packet 57.

Scope:

- Moved bounded motion primitive helpers from `anomaly_analysis.c` to
  `anomaly_motion_estimator.{h,c}`.
- Exposed only:
  - `anomaly_motion_estimator_find_residual_displacement(...)`
  - `anomaly_motion_estimator_texture_scale(...)`
  - `anomaly_motion_estimator_structure_scale(...)`
- Replaced the sidecar ops initializer and GMV/appearance scorer call sites in
  `anomaly_analysis.c` with the new MotionEstimator names.

Behavior preserved:

- Residual displacement keeps the same patch/SAD search, second-best margin,
  edge-of-search rejection, optional output handling, and false returns for
  null/current-border/search-miss cases.
- Texture scale still maps `<= 8` to `0.0`, `>= 24` to `1.0`, and linearly
  maps the midpoint `16` to `0.5`.
- Structure scale still returns `0.0` for null or border cells and uses the
  same 3x3 gradient covariance/minor-eigenvalue ratio thresholds.

Non-goals:

- Did not move registration solving, `project_motion_cell(...)`,
  `registration_residual_standout_score(...)`, scorer loops, `motion_persist`,
  debug top insertion, saliency maps, result structs, timing, or the full
  GMV/appearance motion scorer.

Tests:

- Added focused native coverage for texture-scale boundaries, structure-scale
  invalid/border and strong-corner behavior, residual displacement invalid and
  search-miss returns, and a shifted synthetic patch with expected `dx/dy`.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1187 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1187 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a pure
  MotionEstimator primitive relocation with focused native coverage and no
  detector decision-loop, scoring-policy, timing, scan planning, threading,
  public API, or result-struct behavior change.

## Packet 59: MotionEstimator Appearance-Motion Scorer Contract

Status: parent-validated.

Mode: contract-only code change, behavior-preserving, after Packet 58.

Scope:

- Added a MotionEstimator-owned appearance-motion proposal cap,
  `ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS`, matching the current private
  four-candidate scorer limit without moving that private define yet.
- Reshaped the Phase 2 appearance-motion proposal and score structs so the
  MotionEstimator boundary can carry the live candidate fields, score scales,
  mutable persistence handle, scorer inputs, and scorer outputs.
- Added null-safe output initialization and winner-eligibility helper functions
  in `anomaly_motion_estimator.{h,c}`.

Behavior preserved:

- Did not move the appearance-motion scorer body out of `anomaly_analysis.c`.
- Did not change winner selection, candidate scoring, persistence allocation,
  saliency stamping, debug fields, timing, or detector result behavior.
- The winner-eligibility helper only names the existing positive valid-score
  precondition; current scorer call sites are unchanged.

Tests:

- Added focused native coverage for output init defaults, prefilled
  score/winner clearing, winner-eligibility rejection/acceptance, and proposal
  field assignment.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1220 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1220 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a pure
  MotionEstimator contract/helper addition with focused native coverage and no
  detector decision-loop, scoring-policy, timing, scan planning, threading,
  public API, or result-struct behavior change.

## Packet 60: Local Appearance-Motion Shadow Bridge

Status: parent-validated.

Mode: one-way shadow bridge, behavior-preserving, after Packet 59.

Scope:

- Added local static bridge helpers in `anomaly_analysis.c` that copy legacy
  motion candidates into the Packet 59 appearance-motion proposal contract and
  mirror legacy `motion_candidate_support[]` results into the
  appearance-motion scorer output shape.
- The mirror copies global motion mean/std/load and zoom/broad motion scales,
  mirrors positive support entries as valid scores, falls back to the original
  candidate pixel when support coordinates are unset, and selects the shadow
  winner through the Packet 59 winner-eligibility helper.
- The mirrored output is intentionally local and non-authoritative: existing
  `motion_candidate_support[]`, `motion_candidate_support_x/y`, `best_motion_*`,
  `motion_top`, saliency stamping, persistence, result debug, and timing
  consumers remain the source of truth.

Behavior preserved:

- Did not move `collect_motion_candidates(...)`, the scorer loop, persistence
  allocation/update, saliency stamping, debug top insertion, or result/timing
  plumbing.
- Did not replace any existing motion scorer consumers or change
  `ANOMALY_MAX_MOTION_CANDIDATES`.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1220 passed, 0 failed`.

Parent validation:

- Parent review tightened the shadow scorer state handle so it mirrors
  `state->motion_persist` after the legacy allocation branch updates it. The
  bridge remains non-authoritative and unused by detector decisions.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1220 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the bridge is static,
  local, and shadow-only, with focused native coverage preserving the legacy
  scorer as authoritative.

## Packet 61: MotionEstimator Shadow Appearance-Motion Output Mirror

Status: parent-validated.

Mode: move-only shadow helper extraction, behavior-preserving, after Packet 60.

Scope:

- Moved the shadow appearance-motion support-output mirror from local
  `anomaly_analysis.c` static helper into
  `anomaly_motion_estimator_mirror_appearance_support_output(...)`.
- The helper is shaped around the Packet 59
  `anomaly_motion_appearance_proposal_t` contract, while continuing to mirror
  the legacy support arrays as non-authoritative output.
- `anomaly_analysis.c` now passes `motion_appearance_proposals` and
  `motion_appearance_proposal_count` into MotionEstimator; legacy motion
  candidates, support arrays, scorer loop, persistence, saliency stamping,
  debug/result publication, and existing assignments remain authoritative.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1236 passed, 0 failed`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1236 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change moves only
  the shadow output mirror helper behind the MotionEstimator contract with
  focused native coverage. The mirrored output remains non-authoritative and
  detector decisions, scoring, timing, scan planning, threading, public APIs,
  and result/debug publication are unchanged.

## Packet 62: Appearance Motion Candidate Collection Helper

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 61.

Scope:

- Moved the local `collect_motion_candidates(...)` helper out of
  `anomaly_analysis.c` into
  `anomaly_appearance_collect_motion_candidates(...)` in
  `anomaly_appearance_candidates.{h,c}`.
- Added appearance-owned constants for the candidate limit and NMS radius:
  `ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX == 4` and
  `ANOMALY_APPEARANCE_MOTION_CANDIDATE_NMS_RADIUS == 2`.
- Left `motion_candidates_to_appearance_proposals(...)` local in
  `anomaly_analysis.c` so the appearance-candidate module does not need to
  include the MotionEstimator producer contract.

Behavior preserved:

- The extracted helper keeps the same thermal/color proposal score formula,
  local-peak check, NMS replacement rule, sorted insertion, output clamp, and
  ROI pixel mapping.
- `anomaly_analysis.c` still owns candidate arrays, proposal conversion,
  scorer-loop wiring, persistence, saliency stamping, debug/result plumbing,
  and timing.

Focused native tests:

- NULL/invalid input count reset.
- Thermal-only peak selection and ROI pixel mapping.
- Color-only score contribution using the existing `0.85` multiplier.
- NMS replacement plus sorted/clamped output ordering.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1251 passed, 0 failed`.

Parent validation:

- Parent review confirmed the helper extraction stayed inside
  `anomaly_appearance_candidates.{h,c}` and did not couple the appearance
  module to the MotionEstimator proposal contract.
- `anomaly_analysis.c` still owns the candidate-to-proposal adapter, scorer
  loop, persistence, saliency stamping, debug/result plumbing, and timing.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1251 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a
  behavior-preserving helper relocation with focused native coverage for the
  exact scoring, NMS, sorting, clamp, and pixel-mapping contract. Detector
  decisions, scan planning, timing, public APIs, threading, and result/debug
  publication are unchanged.

## Packet 63: MotionEstimator Appearance Proposal Builder

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 62.

Scope:

- Moved the local `motion_candidates_to_appearance_proposals(...)` adapter out
  of `anomaly_analysis.c` into
  `anomaly_motion_estimator_build_appearance_proposals_from_candidates(...)`
  in `anomaly_motion_estimator.{h,c}`.
- Added a MotionEstimator dependency on `anomaly_appearance_candidates.h` for
  the producer-side `anomaly_motion_candidate_t` input type.
- Left `anomaly_appearance_candidates` independent of MotionEstimator, so
  appearance collection still does not include the proposal/scorer contract.

Behavior preserved:

- The helper keeps the same NULL/invalid rejection, `out_capacity` clamp,
  `ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS` clamp, and fieldwise copy of
  sample-grid, pixel, proposal-score, thermal-score, and color-score fields.
- `anomaly_analysis.c` still owns candidate arrays, scorer-loop wiring,
  persistence, saliency stamping, debug/result plumbing, and timing.

Focused native tests:

- NULL/invalid rejection.
- Output-capacity clamp.
- MotionEstimator max-proposal contract clamp.
- Fieldwise candidate-to-proposal copy.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1265 passed, 0 failed`.

Parent validation:

- Parent review confirmed the dependency direction: MotionEstimator consumes
  `anomaly_motion_candidate_t`, while `anomaly_appearance_candidates` remains
  independent of the MotionEstimator proposal/scorer contract.
- `anomaly_analysis.c` no longer owns the candidate-to-proposal adapter and
  still owns the remaining scorer-loop wiring, persistence, saliency stamping,
  debug/result plumbing, and timing.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1265 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a
  behavior-preserving adapter relocation with focused native coverage and no
  detector decision, scan planning, timing, public API, threading, or
  result/debug publication changes.

## Packet 64: Appearance Rank Lookup And Ranked Index Utilities

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 63.

Scope:

- Added pure coordinate lookup helpers in
  `anomaly_appearance_candidates.{h,c}`:
  `anomaly_appearance_find_thermal_blob_candidate_rank(...)` and
  `anomaly_appearance_find_color_blob_candidate_rank(...)`.
- Kept the local trace-aware wrappers in `anomaly_analysis.c`, but reduced
  them to passing `component_peak_x/y` into the appearance-owned pure helpers.
- Added `anomaly_appearance_insert_ranked_index(...)` for descending ranked
  index/score arrays.
- Replaced the duplicated color and thermal provisional eligible-list insertion
  loops in `anomaly_analysis.c` with the shared helper.

Behavior preserved:

- Rank lookup still rejects NULL, empty, or negative-coordinate inputs, returns
  the first exact coordinate match, and returns `-1` on misses.
- Ranked insertion still inserts descending by rank, shifts only inside the
  fixed capacity, increments count only until capacity, drops low-rank inserts
  beyond capacity, and shifts/drops the tail for high-rank inserts into a full
  list.
- Debug/trace structs remain private to `anomaly_analysis.c`; appearance
  candidates still own only pure candidate utilities.

Focused native tests:

- Thermal and color rank lookup NULL/empty/negative/miss/hit/duplicate-first
  coverage.
- Ranked-index insertion invalid input, empty insert, descending/middle insert,
  capacity drop, and high-rank full-list shift/drop coverage.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1291 passed, 0 failed`.

Parent validation:

- Parent review confirmed the moved helpers are pure utilities only. Debug
  trace structs and mutation remain in `anomaly_analysis.c`.
- The ranked-index helper preserves the existing descending insertion,
  capacity clamp, low-rank drop, and high-rank full-list tail-drop behavior
  used by color and thermal provisional candidate lists.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1291 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the changes are pure
  helper extractions covered by focused native tests. Candidate scoring,
  candidate creation, debug trace mutation, detector decisions, scan planning,
  timing, public APIs, threading, and result/debug publication are unchanged.

## Packet 65: Thermal Blob Candidate Insert Report

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 64.

Scope:

- Added `anomaly_thermal_blob_insert_report_t` and
  `anomaly_appearance_insert_thermal_blob_candidate(...)` to
  `anomaly_appearance_candidates.{h,c}`.
- Moved only the pure thermal blob candidate list mechanics: sorted insertion,
  NMS weaker-candidate rejection, NMS stronger-candidate replacement, cap
  rejection, full-list shift/drop, and target-tail cap-drop reporting.
- Kept `anomaly_thermal_target_trace_t`,
  `record_thermal_target_pre_cap_rank(...)`, and all debug/target trace
  mutation local in `anomaly_analysis.c`.
- Rewrote local `insert_thermal_blob_candidate(...)` as a thin trace-aware
  wrapper around the appearance helper report.

Behavior preserved:

- Thermal blob candidate ordering still uses
  `anomaly_thermal_blob_candidate_compare_rank(...)`.
- NMS replacement/rejection uses the same radius, conflict rank, and conflict
  sample semantics as the legacy local helper.
- Target debug trace updates remain private to `anomaly_analysis.c`, including
  target pre-cap rank, NMS drop/replacement, cap drop, and non-target
  full-list insertion that drops a target tail.
- Color blob insertion remains deferred; this packet does not change color
  insertion mechanics.

Focused native tests:

- Invalid input leaves list state unchanged and reports invalid.
- Sorted insertion into a non-full list preserves rank order.
- Weaker NMS candidate is rejected with the existing conflict sample.
- Stronger NMS candidate replaces the existing entry and reports replacement.
- Low-rank candidate into a full list is rejected by cap.
- Non-target insertion into a full list reports when it drops a target at the
  tail.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1311 passed, 0 failed`.

Parent validation:

- Parent review confirmed that the appearance helper owns only pure list
  mutation and report production; `anomaly_analysis.c` still owns
  `anomaly_thermal_target_trace_t` and all debug/target trace writes.
- The local wrapper preserves legacy target-trace semantics for target
  pre-cap rank, target NMS rejection, target NMS conflict coordinates,
  non-target NMS replacement of a target, target cap rejection, and non-target
  full-list insertion that pushes a target off the capped tail.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1311 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a
  behavior-preserving thermal helper extraction with focused native coverage.
  Candidate scoring/creation, color insertion, scan planning, timing, public
  APIs, threading, and result/debug publication are unchanged.

## Packet 66: Color Blob Candidate Insert Report

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 65.

Scope:

- Added `anomaly_color_blob_insert_report_t` and
  `anomaly_appearance_insert_color_blob_candidate(...)` to
  `anomaly_appearance_candidates.{h,c}`.
- Moved only the pure color blob candidate list mechanics: sorted insertion,
  NMS weaker-candidate rejection, NMS stronger-candidate replacement, cap
  rejection, full-list shift/drop, and target-tail cap-drop reporting.
- Kept `anomaly_color_blob_target_trace_t`,
  `record_color_target_pre_cap_rank(...)`, and all debug/target trace mutation
  local in `anomaly_analysis.c`.
- Rewrote local `insert_color_blob_candidate(...)` as a thin trace-aware
  wrapper around the appearance helper report.

Behavior preserved:

- Color blob candidate ordering still uses
  `anomaly_color_blob_candidate_compare_rank(...)`.
- NMS replacement/rejection uses the same radius, conflict rank, and conflict
  sample semantics as the legacy local helper.
- Target debug trace updates remain private to `anomaly_analysis.c`, including
  target pre-cap rank, target NMS drop/replacement, cap drop, and non-target
  full-list insertion that drops a target tail.
- Thermal blob insertion remains unchanged by this packet.

Focused native tests:

- Invalid input leaves list state unchanged and reports invalid.
- Sorted insertion into a non-full list preserves rank order.
- Weaker NMS candidate is rejected with the existing conflict sample.
- Stronger NMS candidate replaces the existing entry and reports replacement.
- Low-rank candidate into a full list is rejected by cap.
- Non-target insertion into a full list reports when it drops a target at the
  tail.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1331 passed, 0 failed`.

Parent validation:

- Parent review confirmed that the color appearance helper owns only pure list
  mutation and report production; `anomaly_analysis.c` still owns
  `anomaly_color_blob_target_trace_t` and all debug/target trace writes.
- The local wrapper preserves legacy color trace semantics for target pre-cap
  rank, target NMS rejection, target NMS conflict coordinates, non-target NMS
  replacement of a target, target cap rejection, and non-target full-list
  insertion that pushes a target off the capped tail.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1331 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change is a
  behavior-preserving color helper extraction with focused native coverage.
  Candidate scoring/creation, scan planning, timing, public APIs, threading,
  and result/debug publication are unchanged.

## Packet 67 - Buffer Capacity Helpers

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 66.

Scope:

- Added new portable stdlib-only `anomaly_buffer.{h,c}`.
- Moved typed buffer helpers out of `anomaly_analysis.c`:
  `ensure_u8_capacity(...)`, `resize_u8_buffer(...)`,
  `ensure_float_capacity(...)`, `resize_float_buffer(...)`,
  `ensure_double_capacity(...)`, and `ensure_int_capacity(...)`.
- Exported module-prefixed equivalents:
  `anomaly_buffer_ensure_u8_capacity(...)`,
  `anomaly_buffer_resize_u8(...)`,
  `anomaly_buffer_ensure_float_capacity(...)`,
  `anomaly_buffer_resize_float(...)`,
  `anomaly_buffer_ensure_double_capacity(...)`, and
  `anomaly_buffer_ensure_int_capacity(...)`.
- Updated `anomaly_analysis.c` call sites to use the new buffer module.
- Added `anomaly_buffer.c` to the Android and native anomaly CMake targets.

Behavior preserved:

- NULL buffer/capacity inputs still fail for ensure helpers.
- NULL buffer input still fails for resize helpers.
- Zero count still succeeds without allocation or capacity mutation.
- Ensure helpers still do not shrink when existing capacity is sufficient.
- Ensure helpers still update capacity only after successful growth.
- Resize helpers still have no capacity parameter and only reallocate the
  typed buffer.

Focused native tests:

- NULL input rejection for all public helpers.
- Zero-count no-op success for ensure and resize helpers.
- Growth and capacity updates for u8/float/double/int ensure helpers.
- No-shrink behavior when existing capacity is sufficient.
- Smoke writes after u8/float/double/int allocation.
- Resize allocation and smoke writes for u8/float buffers.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1374 passed, 0 failed`.

Parent validation:

- Parent review confirmed `anomaly_buffer.{h,c}` is C/stdlib-only typed
  allocation plumbing and does not depend on Android, detector state, timing,
  scan planning, or public AD controls.
- `anomaly_analysis.c` call sites now use module-prefixed helpers while
  preserving the previous zero-count, no-shrink, capacity-update, and
  resize-without-capacity behavior.
- `anomaly_buffer.c` is wired into both Android and native anomaly CMake
  targets.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1374 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  stdlib-backed typed buffer allocation helpers only. Detector scoring,
  candidate selection, scan planning, timing, public APIs, threading, and
  result/debug publication are unchanged.

## Packet 68 - Sampled-Grid Region Helpers

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 67.

Scope:

- Added new portable `anomaly_grid_region.{h,c}` helper module.
- Moved sampled-grid region utilities out of `anomaly_analysis.c`:
  `compute_active_mask_bounds(...)`, `zero_float_region(...)`,
  `copy_float_region(...)`, and `zero_u8_region(...)`.
- Exported module-prefixed equivalents:
  `anomaly_grid_region_compute_active_mask_bounds(...)`,
  `anomaly_grid_region_zero_float(...)`,
  `anomaly_grid_region_copy_float(...)`, and
  `anomaly_grid_region_zero_u8(...)`.
- Updated `anomaly_analysis.c` call sites to use the new grid-region module.
- Added `anomaly_grid_region.c` to the Android and native anomaly CMake
  targets.

Behavior preserved:

- NULL masks, invalid grid dimensions, NULL output pointers, and empty masks
  still return `false` from active-mask bounds.
- Active-mask bounds still clamp padded bounds to the sampled grid.
- Negative padding still behaves as zero padding.
- Float/u8 region helpers still no-op for NULL buffers, nonpositive width, or
  invalid spans.
- Region zero/copy helpers still operate row-by-row over the requested
  inclusive sampled-grid rectangle and perform no allocation.

Focused native tests:

- Active-mask bounds reject NULL/invalid inputs and return false for empty
  masks without touching outputs.
- Single active cell with padding clamps to grid bounds.
- Multiple active cells produce expected padded min/max bounds.
- Negative padding behaves like zero.
- Float zero/copy helpers touch only the requested rows/columns.
- U8 zero helper no-ops on invalid spans and touches only the requested region.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1465 passed, 0 failed`.

Parent validation:

- Parent review confirmed `anomaly_grid_region.{h,c}` is portable sampled-grid
  region plumbing with no allocation, Android dependency, detector state,
  timing, scan planning, or public AD control changes.
- `anomaly_analysis.c` call sites now use module-prefixed helpers while
  preserving legacy active-mask bounds, negative-padding, clamping,
  invalid-span no-op, and inclusive row/column region behavior.
- `anomaly_grid_region.c` is wired into both Android and native anomaly CMake
  targets.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1465 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  deterministic sampled-grid region helpers only. Detector scoring, candidate
  selection, scan planning, timing, public APIs, threading, and result/debug
  publication are unchanged.

## Packet 69 - Debug Top-Candidate Helper

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 68.

Scope:

- Added new `anomaly_debug_helpers.{h,c}` debug/result helper module.
- Moved `maybe_insert_top_candidate(...)` out of `anomaly_analysis.c` as
  `anomaly_debug_insert_top_candidate(...)`.
- Updated the three `anomaly_analysis.c` top-candidate call sites to use the
  module-prefixed helper.
- Added `anomaly_debug_helpers.c` to the Android and native anomaly CMake
  targets.

Behavior preserved:

- NULL candidate arrays and NULL count pointers still no-op.
- Nonpositive capacity and nonpositive combined scores still no-op.
- Candidates are still sorted descending by `combined_score` using strict `>`.
- Equal-score candidates still preserve existing order and append only while
  capacity remains.
- Full lists still drop weaker tail candidates, and higher-rank inserts still
  shift entries down and drop the old tail.
- Counts still increment only up to capacity.
- Written debug candidate fields still match the legacy helper exactly.

Focused native tests:

- Invalid/null/no-capacity/nonpositive-score no-op behavior.
- Empty-list insertion and full field copy.
- Descending order and middle insertion.
- Full-list weak-tail drop and high-rank shift/drop behavior.
- Equal-score append/order preservation and capped equal-score rejection.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1484 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, the three `anomaly_analysis.c` call
  sites, CMake source wiring, and focused helper tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1484 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  debug/result top-list plumbing only. Detector scoring, candidate selection,
  scan planning, timing, public APIs, threading, and published detection
  results are unchanged.

## Packet 70 - RGBA Debug Drawing Helpers

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 69.

Scope:

- Moved `draw_rgba_hline(...)`, `draw_rgba_vline(...)`, and
  `draw_rgba_circle(...)` out of `anomaly_analysis.c` into
  `anomaly_debug_helpers.{h,c}`.
- Exported module-prefixed helpers:
  `anomaly_debug_draw_rgba_hline(...)`,
  `anomaly_debug_draw_rgba_vline(...)`, and
  `anomaly_debug_draw_rgba_circle(...)`.
- Updated all RGBA debug overlay call sites in `anomaly_analysis.c` to use the
  module-prefixed helpers.
- No CMake changes were needed; `anomaly_debug_helpers.c` was already wired by
  Packet 69.

Behavior preserved:

- NULL buffers and nonpositive width/height still no-op.
- Horizontal lines still reject out-of-range rows, swap reversed endpoints,
  clamp x endpoints, and write RGBA with alpha `0xFF`.
- Vertical lines still reject out-of-range columns, swap reversed endpoints,
  clamp y endpoints, and write RGBA with alpha `0xFF`.
- Circles still reject NULL/nonpositive dimensions/nonpositive radius/
  nonpositive stroke and use the same midpoint plot loop and 8-way point
  writes with alpha `0xFF`.

Focused native tests:

- Horizontal line reversed/clamped endpoint behavior, color/alpha writes, and
  invalid/null/no-dimension no-ops.
- Vertical line reversed/clamped endpoint behavior, color/alpha writes, and
  invalid/null/no-dimension no-ops.
- Circle invalid no-ops, deterministic radius-two midpoint plot points, color/
  alpha writes, and padded-stride preservation.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1972 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, overlay call sites in
  `anomaly_analysis.c`, and focused native draw tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `1972 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  debug overlay drawing helpers only. Detector decision, scoring, scan, timing,
  public API, threading, and result publication semantics are unchanged.

## Packet 71 - Anomaly Box Append Helpers

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 70.

Scope:

- Moved `append_anomaly_box(...)` and `append_anomaly_rect(...)` behavior out
  of `anomaly_analysis.c` into `anomaly_debug_helpers.{h,c}`.
- Exported capacity-explicit helpers:
  `anomaly_debug_append_center_box(...)` and
  `anomaly_debug_append_rect(...)`.
- Updated result-box assembly call sites to pass the local `max_boxes`
  destination capacity.
- Updated overlay debug box call sites to pass `ANOMALY_MAX_OVERLAY_BOXES`.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.

Behavior preserved:

- NULL box/count pointers, nonpositive capacities, and full destinations no-op.
- Center boxes still compute half extents, clamp normalized edges to `[0,1]`,
  reject invalid empty/inverted boxes after clamp, set RGB, force
  `draw_crosshair = 1u`, clamp weight to `[0,1]`, and increment count once.
- Rect boxes still clamp normalized edges to `[0,1]`, reject invalid
  empty/inverted rects after clamp, set RGB, preserve the `draw_crosshair`
  argument, clamp weight to `[0,1]`, and increment count once.
- The helper does not own or initialize `algorithm`; existing callers continue
  to set it after successful append, matching the previous local helpers.

Focused native tests:

- Center box valid field writes, forced crosshair, edge clamping, low/high
  weight clamping, invalid/null/no-capacity no-ops, invalid-after-clamp
  rejection, and capped destination behavior.
- Rect valid field writes, draw-crosshair argument preservation, edge
  clamping, low/high weight clamping, invalid/null/no-capacity no-ops,
  invalid-after-clamp rejection, and capped destination behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2010 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, result-box and overlay-rect call
  sites in `anomaly_analysis.c`, and focused native helper tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2010 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  result/debug overlay box append helpers only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

## Packet 72 - Anomaly Box Overlay Drawing Helper

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 71.

Scope:

- Moved `draw_anomaly_boxes_rgba(...)` behavior out of
  `anomaly_analysis.c` into `anomaly_debug_helpers.{h,c}`.
- Exported `anomaly_debug_draw_boxes_rgba(...)` for final result/debug
  overlay rendering.
- Updated the final overlay rendering call site in `anomaly_analysis.c` to
  call the module-prefixed helper.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.

Behavior preserved:

- NULL RGBA pointer, NULL boxes pointer, nonpositive dimensions, and
  nonpositive box counts no-op.
- `stroke_max`, per-box stroke, underlay stroke, normalized-to-pixel
  conversion, endpoint clamping, and invalid pixel-rect skipping are unchanged.
- Crosshair boxes still draw black underlay segments followed by colored
  crosshair segments with the same gap calculation.
- Non-crosshair boxes still draw black underlay borders followed by colored
  rectangle borders with the same paired-edge conditions.

Focused native tests:

- Invalid/null/no-count draw-box no-ops.
- Crosshair overlay color pixels, visible black underlay pixels, untouched
  center gap, and untouched unrelated background pixels.
- Non-crosshair rectangle color border pixels, visible black underlay,
  untouched interior, and skipped invalid zero-width boxes.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2277 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, the final overlay rendering call site
  in `anomaly_analysis.c`, and focused native draw-box tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2277 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  debug/result overlay box rendering only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

## Packet 73 - Hot Overlay Active-Content Drawing Helper

Status: parent-validated.

Mode: helper extraction, behavior-preserving, after Packet 72.

Scope:

- Moved the hot-overlay active-content helper stack out of
  `anomaly_analysis.c` into `anomaly_debug_helpers.{h,c}`.
- Exported `anomaly_debug_draw_hot_overlay_rgba(...)` as the only module
  entrypoint needed by `anomaly_analysis.c`.
- Kept row/column/tile active-content detection, best-span selection, tile
  component selection, and luma sampling helpers private to
  `anomaly_debug_helpers.c`.
- Updated final overlay rendering in `anomaly_analysis.c` to call the
  module-prefixed helper.
- No CMake changes were required because `anomaly_debug_helpers.c` was already
  wired into Android and native anomaly CMake targets.

Behavior preserved:

- NULL RGBA pointer and nonpositive dimensions no-op.
- Luma weights remain exactly `0.2126`, `0.7152`, and `0.0722`.
- Active row/column/tile thresholds, sample steps, flood-fill component
  selection, and allocation-failure fallback behavior are unchanged.
- The hot-overlay entrypoint still initializes content bounds to the full frame
  and does not call active-content bounds detection.
- Black-hot/white-hot polarity semantics, hot-vicinity accumulation, circle
  center/radius/stroke calculation, and red circle drawing are unchanged.

Focused native tests:

- Invalid/null/no-dimension hot-overlay no-ops.
- White-hot deterministic bright-region circle pixels and untouched unrelated
  pixels.
- Black-hot deterministic dark-region circle pixels and untouched unrelated
  pixels.
- Uniform-frame no-op for both polarities.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2286 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, the final hot-overlay call site in
  `anomaly_analysis.c`, and focused native hot-overlay tests.
- Confirmed the exported hot-overlay helper still uses full-frame bounds and
  does not invoke the moved active-content bounds detector.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2286 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  hot-overlay debug drawing helper logic only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, and result
  publication semantics are unchanged.

## Packet 74 - Result Box Builder Module

Status: parent-validated.

Mode: result-plumbing extraction, behavior-preserving, after Packet 73.

Scope:

- Moved `assemble_anomaly_boxes(...)` out of `anomaly_analysis.c` into new
  `anomaly_result_builder.{h,c}`.
- Exported `anomaly_result_build_boxes(...)` with explicit state, config,
  caller-selected motion-box algorithm, destination boxes, and capacity.
- Updated the color-stride hold publication path and the normal publication
  path in `anomaly_analysis.c` to call the module-prefixed helper.
- Added `anomaly_result_builder.c` to Android and native anomaly CMake targets.

Behavior preserved:

- NULL state/config/boxes and nonpositive destination capacity return zero.
- `box_side` still derives from `sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f))`
  and clamps to `[0.02f, 0.18f]`.
- Confirmed target-track boxes still take priority and return immediately when
  any published target box is emitted.
- Track RGB selection, motion algorithm matching, size floor, weight formula,
  and post-append algorithm assignment are unchanged.
- Accumulator saliency-primary filtering, RGB cue mapping, per-algorithm box
  scales, min-hit normalization, and caller-owned motion algorithm assignment
  are unchanged.
- The current saliency auxiliary fallback gate is preserved exactly; because
  saliency-primary is currently the same persist-mask condition, focused tests
  lock in that aux-only state remains unpublished.

Focused native tests:

- Invalid/null/no-capacity zero returns.
- Published target-track priority over active accumulators, including thermal
  RGB, weight, crosshair, algorithm, and min-size floor fields.
- Accumulator fallback for color and motion-tolerance boxes, including
  saliency-primary filtering when persist is enabled.
- Current saliency-aux gate behavior for persist-only configuration.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2311 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}`, both
  `anomaly_result_build_boxes(...)` call sites in `anomaly_analysis.c`, CMake
  source wiring, and focused native result-builder tests.
- Confirmed the current saliency-aux fallback remains unreachable under the
  existing persist-mask/saliency-primary gate and is preserved as-is.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2311 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  result-box assembly helper logic only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, and overlay drawing
  behavior are unchanged.

## Packet 75 - Saliency Auxiliary Track Update Module

Status: parent-validated.

Mode: saliency track-state helper extraction, behavior-preserving, after
Packet 74.

Scope:

- Moved `update_saliency_aux_track(...)` out of `anomaly_analysis.c` into new
  `anomaly_saliency_tracks.{h,c}`.
- Exported `anomaly_saliency_update_aux_track(...)` with explicit state,
  track index, raw coordinates, gate, and EMA alpha.
- Updated the existing secondary saliency update loop in `anomaly_analysis.c`
  to call the module-prefixed helper.
- Added `anomaly_saliency_tracks.c` to Android and native anomaly CMake
  targets.

Behavior preserved:

- NULL state and out-of-range track indexes are no-ops.
- Inactive tracks initialize from valid raw coordinates with one hit, base
  hold, and active state.
- Active in-gate updates still apply EMA, increment hits with
  `ANOMALY_ACC_MAX_HITS` capping, and reset hold from the pre-hit base.
- Active out-of-gate updates still reset center coordinates and hits while
  preserving the pre-reset strong-track hold bonus behavior.
- Invalid raw coordinates still age active track hold, clearing active/hits/hold
  when the countdown expires, and no-op while inactive.

Focused native tests:

- Invalid/null/out-of-range no-op behavior.
- Inactive initialization.
- Active in-gate EMA, hit increment, and hold reset.
- Active out-of-gate reset.
- Invalid raw-coordinate hold decrement and expiry clear.
- Strong-track hold bonus and hit cap behavior.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2342 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_saliency_tracks.{h,c}`, the
  `anomaly_saliency_update_aux_track(...)` call site in `anomaly_analysis.c`,
  CMake source wiring, and focused native saliency auxiliary track tests.
- Confirmed candidate selection, display algorithm classification, result
  boxes, target observations, scoring, and scan planning were not moved or
  changed in this packet.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2342 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  saliency auxiliary track state-update helper logic only. Detector scoring,
  candidate selection, scan planning, timing, public API, threading, result
  publication, and overlay drawing behavior are unchanged.

## Packet 76 - Saliency Local Support Helper

Status: parent-validated.

Mode: saliency local-support helper extraction, behavior-preserving, after
Packet 75.

Scope:

- Moved `find_saliency_local_support(...)` out of `anomaly_analysis.c` into
  `anomaly_saliency_tracks.{h,c}`.
- Exported `anomaly_saliency_find_local_support(...)` and updated the existing
  secondary saliency local-support call site to use the module-prefixed helper.
- Added focused native tests for invalid inputs, inactive tracks,
  nonpositive local scores, best-score selection, normalized output, and
  edge-clamped search windows.

Behavior preserved:

- Output pointers are initialized to `-1.0f` before validation.
- Invalid state, track index, inactive track, map, grid size, or sample step
  still returns false.
- Width/height normalization fallback, ROI/sample-grid clamping, local radius
  `2`, best-score selection, nonpositive-score rejection, and normalized output
  conversion are unchanged.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2371 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_saliency_tracks.{h,c}`, the
  `anomaly_saliency_find_local_support(...)` call site in
  `anomaly_analysis.c`, and focused native saliency local-support tests.
- Confirmed candidate selection, display algorithm classification, saliency
  update logic, scoring, result boxes, target observations, public API, timing,
  and threading were not changed in this packet.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2371 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  saliency auxiliary local-support lookup only. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, result publication,
  and overlay drawing behavior are unchanged.

## Packet 77 - Saliency Display Algorithm Classifier Helper

Status: parent-validated.

Mode: saliency display-classifier helper extraction, behavior-preserving,
after Packet 76.

Scope:

- Moved `classify_saliency_display_algorithm(...)` out of
  `anomaly_analysis.c` into `anomaly_saliency_tracks.{h,c}`.
- Exported `anomaly_saliency_classify_display_algorithm(...)` and updated the
  existing primary/aux saliency display-classification call sites to use the
  module-prefixed helper.
- Added focused native tests for invalid coordinates, null maps/no evidence,
  thermal/color/motion winners, near-tie persist behavior, temporal
  black-hot/white-hot thermal evidence, and zero-registration suppression.

Behavior preserved:

- Out-of-bounds sample coordinates still return `ANOMALY_ALGO_PERSIST`.
- Registration, spatial thermal, color, motion, and temporal thermal defaults
  are unchanged.
- Thermal/color/motion evidence scaling, registration multiplication,
  best/second arbitration, near-tie persist rule, and no-evidence persist rule
  are unchanged.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2381 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_saliency_tracks.{h,c}`, all
  `anomaly_saliency_classify_display_algorithm(...)` call sites in
  `anomaly_analysis.c`, and focused native saliency display-classifier tests.
- Confirmed saliency candidate selection, local support lookup, saliency update
  logic, scoring, result boxes, target observations, public API, timing, and
  threading were not changed in this packet.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2381 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this worker packet; the change
  extracts saliency display-algorithm classification only. Candidate
  selection, saliency update logic, scoring, result boxes, target
  observations, public API, timing, and threading behavior are unchanged.

## Packet 78 - Color Reviewed FP Cluster Helper

Status: parent-validated.

Mode: color policy helper extraction, behavior-preserving, after Packet 77.

Scope:

- Moved `color_candidate_near_reviewed_fp_cluster(...)` out of
  `anomaly_analysis.c` into `anomaly_color_detector.h` as
  `anomaly_color_candidate_near_reviewed_fp_cluster(...)`.
- Moved the reviewed FP cluster center/radius constants into the color helper
  header with the same values.
- Updated the provisional color-candidate suppression call site in
  `anomaly_analysis.c` to use the module-prefixed helper.
- Added focused native tests for exact center, radius boundary, outside-radius,
  and unrelated negative-coordinate behavior.

Behavior preserved:

- The reviewed FP cluster remains centered at `(0.465f, 0.255f)` with radius
  `0.060f`.
- Distance is still Euclidean and the boundary remains inclusive.
- Only the helper location changed; provisional candidate ranking,
  observation creation, scoring, scan planning, result publication, timing,
  public API, and threading behavior are unchanged.

Parent validation:

- Reviewed `anomaly_color_detector.h`, the color provisional suppression call
  site in `anomaly_analysis.c`, and focused native color helper tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2385 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts a
  pure color-policy predicate only. Detector scoring, candidate ranking,
  scan planning, timing, public API, threading, result publication, and overlay
  drawing behavior are unchanged.

## Packet 79 - Color Track Persistence Support Helpers

Status: parent-validated.

Mode: color helper extraction, behavior-preserving, after Packet 78.

Scope:

- Moved `find_best_color_track_support_match(...)` out of
  `anomaly_analysis.c` into `anomaly_color_detector.h` as
  `anomaly_color_find_best_track_support_match(...)`.
- Moved `score_color_track_persistence_bonus(...)` out of
  `anomaly_analysis.c` into `anomaly_color_detector.h` as
  `anomaly_color_score_track_persistence_bonus(...)`.
- Kept the target match gate value at `0.12f`, now owned by the color helper
  header for the extracted support-match helper.
- Updated the single color-derived persist scoring call site to use the
  module-prefixed helper.
- Added focused native tests for invalid/default outputs, track filtering,
  closest in-gate matching, outside-gate rejection, zero-bonus exits, base
  bonus formula, and disagreement bonus formula.

Behavior preserved:

- Matching still accepts only active color or persist tracks, uses Euclidean
  observation-to-track center distance, applies the same support-expanded gate,
  and writes distance/gate only for a valid match.
- The persistence bonus keeps the same registration gate, track-lock gate,
  base formula, support-radius calculation, and disagreement branch.
- Candidate selection, scan planning, result boxes, public API, timing, and
  threading behavior are unchanged.

Worker validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`.

Parent validation:

- Reviewed `anomaly_color_detector.h`, the color-derived persist scoring call
  site in `anomaly_analysis.c`, and focused native track-support/bonus tests.
- Confirmed color-derived persist scoring still calls the helper only when
  `best_color_target_observation_valid` is true and passes the same
  registration-health confidence and local motion support values.
- Noted that `ANOMALY_TARGET_MATCH_GATE` is now visible to
  `anomaly_analysis.c` through `anomaly_color_detector.h` for the extracted
  helper, while `anomaly_target_tracks.c` still owns its private duplicate;
  behavior is preserved, but a future constants-cleanup packet should
  centralize that deliberately.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  color track-support matching and persistence-bonus formula helpers only.
  Focused native tests lock the exact gating and formula behavior. Candidate
  selection, scan planning, result boxes, public API, timing, threading,
  result publication, and overlay drawing behavior are unchanged.

## Packet 80 - Target Match Gate Shared Constant

Status: parent-validated.

Mode: constants cleanup, behavior-preserving, after Packet 79.

Scope:

- Added `anomaly_target_matching.h` as the shared owner of
  `ANOMALY_TARGET_MATCH_GATE`.
- Removed duplicate `ANOMALY_TARGET_MATCH_GATE` definitions from
  `anomaly_color_detector.h` and `anomaly_target_tracks.c`.
- Included the shared header from `anomaly_color_detector.h`,
  `anomaly_target_tracks.c`, and `anomaly_analysis.c`.

Behavior preserved:

- `ANOMALY_TARGET_MATCH_GATE` remains `0.12f`.
- Color track-support matching, target-track observation matching, thermal
  debug trace matching, and movement shadow debug proximity checks still use
  the same literal gate value and formulas.
- No candidate selection, scoring formula, scan planning, public API, timing,
  threading, result publication, or overlay behavior changed.

Parent validation:

- Confirmed there is now one `ANOMALY_TARGET_MATCH_GATE` definition.
- Reviewed all live gate users in `anomaly_color_detector.h`,
  `anomaly_target_tracks.c`, and `anomaly_analysis.c`.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2411 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change centralizes
  a constant owner only and keeps the literal gate value unchanged. Existing
  native tests cover target-track matching and color track-support matching.

## Packet 81 - Saliency Best Dark Patch Helper

Status: parent-validated.

Mode: saliency selection helper extraction, behavior-preserving, after
Packet 80.

Scope:

- Moved `choose_best_dark_patch(...)` out of `anomaly_analysis.c` into
  `anomaly_saliency_tracks.{h,c}` as
  `anomaly_saliency_choose_best_dark_patch(...)`.
- Updated the existing persist/saliency patch-selection call site in
  `anomaly_analysis.c` to use the module-prefixed helper.
- Added focused native tests for invalid/default outputs, best-score
  coordinate conversion, first row-major max tie behavior, and all-below-floor
  negative score behavior.

Behavior preserved:

- Output defaults remain `score=-1.0f`, `x=0`, `y=0`.
- NULL maps and nonpositive dimensions still return after initializing outputs.
- The helper still scans row-major, keeps the first max score on ties, starts
  from a `-1.0f` score floor, and converts grid coordinates using
  `roi + index * sample_step`.
- Candidate selection, scoring, scan planning, public API, timing, threading,
  result publication, and overlay drawing behavior are unchanged.

Parent validation:

- Reviewed `anomaly_saliency_tracks.{h,c}`, the call site in
  `anomaly_analysis.c`, and focused native saliency best-patch tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2424 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts a
  pure saliency patch-selection helper only and focused native tests lock the
  exact defaults, tie behavior, and coordinate conversion.

## Packet 82 - Scratch Capacity Module

Status: parent-validated.

Mode: detector scratch-storage helper extraction, behavior-preserving, after
Packet 81.

Scope:

- Added `anomaly_scratch.{h,c}` for detector-owned scratch capacity helpers.
- Moved sampled-grid, registration-luma, saliency, patch, and previous-ROI
  snapshot capacity helpers out of `anomaly_analysis.c`.
- Updated all existing call sites in `anomaly_analysis.c` to use
  `anomaly_scratch_ensure_*` helpers.
- Added `anomaly_scratch.c` to Android and native anomaly CMake targets.
- Added focused native tests for null/zero-count behavior, allocation of all
  expected buffer groups, previous-ROI snapshot buffers, and preservation of
  larger existing buffers on smaller requests.

Behavior preserved:

- NULL state rejection, zero-count success/no-allocation behavior, capacity
  updates, all legacy buffer ownership checks, and smaller-request early-return
  behavior are unchanged.
- The sampled-grid helper still uses direct `realloc(...)` sequencing exactly
  as before; other groups still use existing `anomaly_buffer_resize_*`
  helpers.
- Detector scoring, candidate selection, scan planning, playback timing,
  public API, threading, result publication, and overlay behavior are
  unchanged.

Parent validation:

- Reviewed `anomaly_scratch.{h,c}`, all rewritten call sites in
  `anomaly_analysis.c`, CMake source wiring, and focused native scratch tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2451 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  scratch-storage capacity helpers only. It does not change detector scoring,
  candidate selection, scan planning, timing, public API, threading, result
  publication, or overlay behavior.

## Packet 83 - Debug Reason Formatting Helpers

Status: parent-validated.

Mode: debug/report helper extraction, behavior-preserving, after Packet 82.

Scope:

- Moved scan reason flag naming and formatting out of `anomaly_analysis.c` into
  `anomaly_debug_helpers.{h,c}` as
  `anomaly_debug_scan_reason_flag_name(...)` and
  `anomaly_debug_format_scan_reason_flags(...)`.
- Moved registration invalid reason naming into `anomaly_debug_helpers.{h,c}`
  as `anomaly_debug_registration_invalid_reason_name(...)`.
- Added focused native tests for known/unknown scan reason names, zero flags,
  multiple flag ordering, unknown-only legacy empty formatting, one-byte buffer
  termination, known registration invalid reasons, and unknown registration
  invalid reason handling.

Behavior preserved:

- Scan reason name strings and registration invalid reason strings are
  unchanged.
- Scan reason formatting still uses known-flag order, `|` separators, `none`
  for zero flags, and the legacy empty string for unknown-only nonzero flags.
- Detector scoring, candidate selection, scan planning, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, confirmed the local formatter/name
  helpers were removed from `anomaly_analysis.c`, and reviewed focused native
  debug formatting tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2460 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  dormant debug/report formatting helpers only. It does not change detector
  scoring, candidate selection, scan planning, timing, public API, threading,
  result publication, or overlay behavior.

## Packet 84 - Linear Solve Helper Module

Status: parent-validated.

Mode: registration math helper extraction, behavior-preserving, after Packet 83.

Scope:

- Added `anomaly_linear_solve.{h,c}`.
- Moved local `solve_3x3(...)` and `solve_6x6(...)` out of
  `anomaly_analysis.c` as `anomaly_linear_solve_3x3(...)` and
  `anomaly_linear_solve_6x6(...)`.
- Updated `fit_affine_least_squares(...)` to call
  `anomaly_linear_solve_6x6(...)`.
- Added `anomaly_linear_solve.c` to Android and native anomaly CMake targets.
- Added focused native tests for pivoted 3x3 solve, 3x3 singular rejection,
  pivoted 6x6 solve, and 6x6 singular rejection.

Behavior preserved:

- Gaussian-elimination pivoting, singular thresholds, factor skip thresholds,
  and output assignment behavior are unchanged.
- The dormant 3x3 helper remains available and tested.
- Registration model construction, affine feature tracking, detector scoring,
  candidate selection, scan planning, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_linear_solve.{h,c}`, the affine solver call site in
  `anomaly_analysis.c`, CMake source wiring, and focused native linear-solve
  tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2473 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  pure linear solver helpers only and keeps registration fitting math
  identical. Focused native tests lock pivoting and singular rejection.
  Detector scoring, candidate selection, scan planning, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

## Packet 85 - Registration Luma Prefilter Helper Module

Status: parent-validated.

Mode: registration image-prep helper extraction, behavior-preserving, after
Packet 84.

Scope:

- Added `anomaly_registration_image.{h,c}`.
- Moved local `registration_prefilter_luma_grid(...)` out of
  `anomaly_analysis.c` as
  `anomaly_registration_prefilter_luma_grid(...)`.
- Updated the registration-prep call site to use the module-prefixed helper.
- Added `anomaly_registration_image.c` to Android and native anomaly CMake
  targets.
- Added focused native tests for edge-clamped 3x3 separable blur output, 1x1
  degenerate-dimension preservation, and invalid-input no-op behavior.

Behavior preserved:

- The two-pass `[1 2 1] / 4` horizontal/vertical luma prefilter, replicated
  edge handling, `(sum + 2) >> 2` rounding, null/invalid no-op behavior, and
  scratch/output write order are unchanged.
- Registration model selection, motion sampling, detector scoring, candidate
  selection, scan planning, timing, public API, threading, result publication,
  and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_registration_image.{h,c}`, the registration-prep call site
  in `anomaly_analysis.c`, CMake source wiring, and focused native prefilter
  tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2486 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts a
  pure registration image-prep helper only and keeps the prefilter arithmetic
  identical. Focused native tests lock edge handling, rounding, degenerate
  dimensions, and invalid-input behavior. Detector scoring, candidate
  selection, scan planning, timing, public API, threading, result publication,
  and overlay behavior are unchanged.

## Packet 86 - Registration Health Confidence Helper

Status: parent-validated.

Mode: registration quality scalar helper extraction, behavior-preserving, after
Packet 85.

Scope:

- Added `anomaly_registration_quality.h`.
- Moved the duplicated `registration_health_confidence(...)` mapping from
  `anomaly_analysis.c` and `anomaly_scan_planner.c` into shared
  `anomaly_registration_health_confidence(...)`.
- Updated all analysis and scan-planner call sites to use the shared
  module-prefixed helper.
- Added focused native tests for each health enum value and unrecognized/default
  behavior.

Behavior preserved:

- Confidence values are unchanged: healthy `1.0f`, soft degraded `0.60f`,
  hard degraded `0.25f`, invalid `0.0f`, unknown/default `0.10f`.
- ROI state updates, scan planning, detector scoring, candidate selection,
  registration model construction, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_registration_quality.h`, removed duplicate local helpers,
  reviewed all rewritten call sites, and added focused native value tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2492 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change centralizes
  a duplicated scalar mapping only and locks every mapped value with focused
  native tests. Detector scoring, candidate selection, scan planning structure,
  timing, public API, threading, result publication, and overlay behavior are
  unchanged.

## Packet 87 - Registration Model Geometry Header

Status: parent-validated.

Mode: registration model contract/helper extraction, behavior-preserving, after
Packet 86.

Scope:

- Added `anomaly_registration_model.h`.
- Moved `anomaly_registration_model_t` and `anomaly_inverse_affine_t` out of
  `anomaly_analysis.c`.
- Moved pure registration model helpers into module-prefixed inline helpers:
  `anomaly_registration_normalize_mode(...)`,
  `anomaly_registration_model_make(...)`,
  `anomaly_registration_model_valid(...)`,
  `anomaly_registration_model_scale(...)`,
  `anomaly_registration_apply_point(...)`,
  `anomaly_registration_invert_point(...)`,
  `anomaly_registration_inverse_affine(...)`,
  `anomaly_registration_invert_point_fast(...)`,
  `anomaly_registration_max_corner_displacement(...)`, and
  `anomaly_registration_motion_exceeds_search(...)`.
- Updated all `anomaly_analysis.c` call sites to use the module-prefixed
  helpers.
- Added focused native tests for default model construction, registration mode
  normalization, validity, scale, affine apply, direct inverse, cached inverse,
  singular rejection, max corner displacement, and motion-search gating.

Behavior preserved:

- Registration model fields, identity defaults, similarity validity semantics,
  affine apply/invert math, singular determinant threshold, max-displacement
  sample points, and GMV search-radius comparison are unchanged.
- Registration estimation, registration cache save/load, ROI lookup,
  scan planning, detector scoring, candidate selection, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_registration_model.h`, removed the corresponding local
  type/helper definitions from `anomaly_analysis.c`, reviewed rewritten
  call sites, and added focused native model geometry tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2525 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  pure registration model contract and geometry helpers only. Focused native
  tests lock the copied math, defaults, validity semantics, inverse rejection,
  and search-gate behavior. Detector scoring, candidate selection, scan
  planning structure, timing, public API, threading, result publication, and
  overlay behavior are unchanged.

## Packet 88 - Runtime Config Effective-Value Helpers

Status: parent-validated.

Mode: runtime control/config helper extraction, behavior-preserving, after
Packet 87.

Scope:

- Added `anomaly_runtime_config.h`.
- Moved runtime effective-value helpers out of `anomaly_analysis.c` as
  module-prefixed inline helpers:
  `anomaly_runtime_normalize_movement_estimator_mode(...)`,
  `anomaly_runtime_effective_color_target_span_px(...)`,
  `anomaly_runtime_effective_thermal_min_delta(...)`,
  `anomaly_runtime_effective_small_target_sample_step_cap(...)`,
  `anomaly_runtime_effective_sample_step(...)`,
  `anomaly_runtime_effective_motion_sample_step(...)`, and
  `anomaly_runtime_effective_motion_evidence_scale(...)`.
- Updated all `anomaly_analysis.c` call sites to use the shared runtime config
  helpers.
- Added focused native tests for movement-estimator mode normalization, color
  target span sizing and thermal small-target cap, thermal delta fallback,
  sample-step defaults/clamps/memory minimums, motion sample-step floor, and
  motion evidence scale floor/ceiling/nonfinite handling.

Behavior preserved:

- Movement estimator mode fallback, color target span area math, thermal span
  cap, thermal min-delta fallback, sample-step memory cap, explicit pixel-step
  clamping, motion sample coarsening, and motion evidence scale clamping are
  unchanged.
- Detector state transitions, registration, scan planning structure, detector
  scoring, candidate selection, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_runtime_config.h`, confirmed the local helper definitions
  were removed from `anomaly_analysis.c`, reviewed rewritten call sites, and
  added focused native runtime-config tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2547 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change centralizes
  pure runtime config/effective-value helpers only and locks the copied control
  math with focused native tests. Detector scoring, candidate selection, scan
  planning structure, timing, public API, threading, result publication, and
  overlay behavior are unchanged.

## Packet 89 - Runtime Config Transition Contract

Status: parent-validated.

Mode: public control-parameter contract extraction, behavior-preserving, after
Packet 88.

Scope:

- Moved the config-transition classification rules out of `anomaly_analysis.c`
  into `anomaly_runtime_config.h` as
  `anomaly_runtime_config_transition_classify(...)`.
- Moved the float-change epsilon and transition-raise helpers into the runtime
  config seam as `ANOMALY_RUNTIME_CONFIG_FLOAT_EPSILON`,
  `anomaly_runtime_config_float_changed(...)`, and
  `anomaly_runtime_config_raise_transition(...)`.
- Preserved the exported public API
  `anomaly_config_transition_classify(...)` as a thin wrapper around the
  runtime-config classifier.
- Added focused native tests for direct runtime-classifier behavior, public
  wrapper parity, sub-epsilon unchanged behavior, and above-epsilon live-update
  behavior, in addition to the existing display/debug/live/reset/null contract
  tests.

Behavior preserved:

- Display-only, debug-only, live-update, reset-sensitive, reset-wins, null
  reset, and float epsilon semantics are unchanged.
- The public `anomaly_config_transition_classify(...)` symbol and behavior are
  unchanged.
- Detector scoring, candidate selection, scan planning structure, timing,
  public API shape, threading, result publication, and overlay behavior are
  unchanged.

Parent validation:

- Reviewed `anomaly_runtime_config.h`, confirmed the classifier body and helper
  definitions were removed from `anomaly_analysis.c`, reviewed the public
  wrapper, and added focused runtime-transition tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2551 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change centralizes
  the pure runtime config transition classifier and leaves the exported public
  wrapper behavior unchanged. Focused native tests lock wrapper parity and
  epsilon boundaries. Detector scoring, candidate selection, scan planning
  structure, timing, threading, result publication, and overlay behavior are
  unchanged.

## Packet 90 - Thermal Movement-Shadow Rescue Helpers

Status: parent-validated.

Mode: thermal detector scoring helper extraction, behavior-preserving, after
Packet 89.

Scope:

- Moved movement-shadow raw-delta rescue scoring into
  `anomaly_thermal_detector.h` as
  `anomaly_thermal_shadow_raw_delta_rescue_score(...)`.
- Moved movement-shadow rescue eligibility into
  `anomaly_thermal_detector.h` as
  `anomaly_thermal_shadow_raw_delta_rescue_eligible(...)`.
- Added `anomaly_thermal_shadow_shape_t` as a compact thermal-shadow input
  contract so the thermal helper does not depend on the large local
  `anomaly_thermal_target_trace_t` debug/plumbing struct.
- Moved movement-shadow reject-reason logic into
  `anomaly_thermal_detector.h` as
  `anomaly_thermal_shadow_movement_reject_reason(...)`.
- Updated `anomaly_analysis.c` call sites to use module-prefixed helpers and to
  populate the compact thermal-shadow shape at the existing target-trace site.
- Added focused native tests for rescue score clamping, eligibility gates, and
  each movement-shadow reject-reason branch.

Behavior preserved:

- Raw/mean/area/span/fill/quality/score-gap/movement/track weighting,
  eligibility thresholds, movement tile/motion support/thermal support gates,
  local-shape vetoes, hot-count gates, compactness gate, edge-like gate, and
  centroid-drift gate are unchanged.
- Thermal candidate extraction flow, target trace publication, detector
  scoring outside the extracted helpers, scan planning, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_thermal_detector.h`, confirmed the corresponding local
  helpers were removed from `anomaly_analysis.c`, reviewed rewritten call
  sites, and added focused thermal shadow rescue tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2567 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  pure thermal movement-shadow scoring/reject helpers only and locks the copied
  branch behavior with focused native tests. Detector flow, candidate
  selection, scan planning structure, timing, threading, result publication,
  and overlay behavior are unchanged.

## Packet 91 - Target Track Movement Evidence Helpers

Status: parent-validated.

Mode: target-track state helper extraction, behavior-preserving, after
Packet 90.

Scope:

- Moved target-track AOI movement evidence decay from `anomaly_analysis.c` into
  `anomaly_target_tracks.c` as
  `anomaly_target_tracks_decay_movement_evidence(...)`.
- Moved target-track movement-evidence update from `anomaly_analysis.c` into
  `anomaly_target_tracks.c` as
  `anomaly_target_tracks_update_movement_evidence(...)`.
- Added `anomaly_motion_estimator.h` consumption to `anomaly_target_tracks.c`
  so target tracks can consume the movement snapshot/tile contract directly.
- Updated `anomaly_analysis.c` to call the module-prefixed movement evidence
  updater.
- Added focused native tests for movement evidence decay window clamping,
  score/confidence damping, local-outlier movement tile updates, AOI movement
  summary counts, last movement fields, and mean score/confidence publication.

Behavior preserved:

- Movement window decrement/clamping, 0.90 decay, valid-tile query behavior,
  independent/parallax/unstable counting, frame-window increments/decrements,
  0.92 score accumulation, score/confidence clamps, last movement fields, and
  AOI summary means are unchanged.
- Motion estimator scoring/classification, target-track lifecycle,
  registration prediction, detector scoring, scan planning, timing, public API,
  threading, result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_target_tracks.{h,c}`, confirmed the corresponding local
  helpers were removed from `anomaly_analysis.c`, reviewed the rewritten call
  site, and added focused native movement-evidence tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2584 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change moves
  target-track-owned movement evidence state updates only and locks the copied
  state-window behavior with focused native tests. Detector flow, candidate
  selection, scan planning structure, timing, threading, result publication,
  and overlay behavior are unchanged.

## Packet 92 - Registration Health Classification Helper

Status: parent-validated.

Mode: registration quality helper extraction, behavior-preserving, after
Packet 91.

Scope:

- Moved registration health classification out of `anomaly_analysis.c` into
  `anomaly_registration_quality.h` as
  `anomaly_registration_classify_health(...)`.
- Updated the main detector flow to call the module-prefixed registration
  quality helper.
- Added focused native tests for null/debug-invalid unknown classification,
  invalid fit, scene discontinuity, healthy model, soft residual degradation,
  hard residual degradation, soft scale drift, and hard scale drift.

Behavior preserved:

- Unknown/debug validity handling, invalid fit/scene discontinuity handling,
  scale thresholds, residual thresholds, motion-search threshold, and returned
  registration health values are unchanged.
- Registration estimation, registration debug population, scan planning,
  detector scoring, candidate selection, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_registration_quality.h`, confirmed the local classifier
  was removed from `anomaly_analysis.c`, reviewed the rewritten call site, and
  added focused native registration health classification tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2593 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  pure registration health classification only and locks the copied thresholds
  and branches with focused native tests. Detector flow, candidate selection,
  scan planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

## Packet 93 - Registration Cache Module

Status: parent-validated.

Mode: registration producer state/cache extraction, behavior-preserving, after
Packet 92.

Scope:

- Added `anomaly_registration_cache.{h,c}`.
- Moved registration model cache storage out of `anomaly_analysis.c` as
  `anomaly_registration_cache_store(...)`.
- Moved cached registration model reload gating/reconstruction out of
  `anomaly_analysis.c` as `anomaly_registration_cache_try_load(...)`.
- Added `anomaly_registration_cache.c` to Android and native anomaly CMake
  targets.
- Updated the main detector flow to call the module-prefixed cache helpers.
- Added focused native tests for invalid store clearing, stable target-only
  reuse budget 2, stable partial reuse budget 1, unstable affine budget 0,
  copied cache fields, successful reload field reconstruction and budget
  decrement, and failed reload gates that preserve budget.

Behavior preserved:

- Cache validity clearing, copied model fields, stable-affine gates, target-only
  strict budget 2 gates, partial budget 1 gates, budget 0 behavior, reload
  mode/health/invalid-reason/step/dimension/luma gates, loaded model field
  reconstruction, and budget decrement behavior are unchanged.
- Registration estimation, registration quality classification, scan planning,
  detector scoring, candidate selection, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_registration_cache.{h,c}`, confirmed the corresponding
  local cache helpers were removed from `anomaly_analysis.c`, reviewed CMake
  source wiring and rewritten call sites, and added focused native cache tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2613 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  registration cache storage/reload helpers only and locks the copied field,
  gate, and budget behavior with focused native tests. Detector flow, candidate
  selection, scan planning structure, timing, threading, result publication,
  and overlay behavior are unchanged.

## Packet 94 - Registration Debug Population Helper

Status: parent-validated.

Mode: debug/result plumbing helper extraction, behavior-preserving, after
Packet 93.

Scope:

- Moved registration model debug/result population out of `anomaly_analysis.c`
  into `anomaly_debug_helpers.{h,c}` as
  `anomaly_debug_populate_registration_model(...)`.
- Updated the main detector flow to call the module-prefixed helper.
- Added focused native tests for null/no-op behavior, copied registration debug
  fields, fit scale/theta calculation, fit stats, and debug-anchor copying.

Behavior preserved:

- Null guard, copied registration fields, fit scale/theta math, fit stat
  copies, anchor copy bound, and result `anchor_count` copy behavior are
  unchanged.
- Registration estimation, registration cache, registration health
  classification, scan planning, detector scoring, candidate selection, timing,
  public API, threading, result publication, and overlay behavior are
  unchanged.

Parent validation:

- Reviewed `anomaly_debug_helpers.{h,c}`, confirmed the local debug population
  helper was removed from `anomaly_analysis.c`, reviewed the rewritten call
  site, and added focused native debug/result population tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2633 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  debug/result population only and locks the copied fields, fit math, and
  anchor-copy behavior with focused native tests. Detector flow, candidate
  selection, scan planning structure, timing, threading, result publication,
  and overlay behavior are unchanged.

## Packet 95 - Frame History Snapshot Module

Status: parent-validated.

Mode: producer state/history helper extraction, behavior-preserving, after
Packet 94.

Scope:

- Added `anomaly_frame_history.{h,c}`.
- Moved motion previous-luma snapshot update out of `anomaly_analysis.c` as
  `anomaly_frame_history_update_motion_luma(...)`.
- Moved registration previous-luma snapshot update out of `anomaly_analysis.c`
  as `anomaly_frame_history_update_registration_luma(...)`.
- Replaced the duplicated sampled-grid-too-small motion snapshot update with
  the motion frame-history helper while preserving that path's existing
  motion-only update behavior.
- Added `anomaly_frame_history.c` to Android and native anomaly CMake targets.
- Added focused native tests for null/no-op behavior, motion snapshot
  copy/dimension publication, grow/replace behavior, separate registration
  snapshot storage, and registration snapshot copy/dimension publication.

Behavior preserved:

- Null guards, buffer capacity growth, copied luma contents, width/height
  publication, allocation-failure clearing behavior, motion-vs-registration
  snapshot separation, color stride hold updates, sampled-grid allocation
  failure updates, sampled-grid-too-small motion-only update, and final
  end-of-frame updates are unchanged.
- Registration estimation/cache/health, motion estimation, scan planning,
  detector scoring, candidate selection, timing, public API, threading, result
  publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_frame_history.{h,c}`, confirmed the corresponding local
  helpers and duplicated inline motion update were removed from
  `anomaly_analysis.c`, reviewed CMake source wiring and rewritten call sites,
  and added focused native frame-history tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2639 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  frame-history snapshot storage only and locks the copied contents,
  dimensions, separate buffers, and update paths with focused native tests.
  Detector flow, candidate selection, scan planning structure, timing,
  threading, result publication, and overlay behavior are unchanged.

## Packet 96 - Frame History Clear Lifecycle Helper

Status: parent-validated.

Mode: producer lifecycle helper extraction, behavior-preserving, after
Packet 95.

Scope:

- Added `anomaly_frame_history_clear(...)` to `anomaly_frame_history.{h,c}`.
- Moved reset-time motion previous-luma release/zeroing out of
  `anomaly_analysis.c`.
- Moved reset-time registration previous-luma release/zeroing out of
  `anomaly_analysis.c`.
- Updated `anomaly_state_reset(...)` to call the frame-history lifecycle
  helper while leaving registration cache invalidation in the reset flow.
- Added focused native tests for null/empty clear behavior and release/zeroing
  of both motion and registration snapshots.

Behavior preserved:

- Null guard behavior, freeing of existing motion and registration snapshot
  buffers, pointer nulling, width/height zeroing, capacity zeroing, and
  reset-time ordering relative to registration cache invalidation are
  unchanged.
- Frame-history update behavior, registration estimation/cache/health, motion
  estimation, scan planning, detector scoring, candidate selection, timing,
  public API, threading, result publication, and overlay behavior are
  unchanged.

Parent validation:

- Reviewed `anomaly_frame_history.{h,c}`, confirmed the reset-time inline
  frame-history free/zero block was removed from `anomaly_analysis.c`, reviewed
  the rewritten `anomaly_state_reset(...)` call site, and added focused native
  lifecycle tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2642 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  frame-history reset-time cleanup only and locks pointer/capacity/dimension
  zeroing with focused native tests. Detector flow, candidate selection, scan
  planning structure, timing, threading, result publication, and overlay
  behavior are unchanged.

## Packet 97 - Scan Planner Default Ops

Status: parent-validated.

Mode: scan-planner consumer-interface helper extraction, behavior-preserving,
after Packet 96.

Scope:

- Moved scan-planner default callback glue out of `anomaly_analysis.c` into
  `anomaly_scan_planner.c`.
- Added `anomaly_scan_planner_default_ops(...)` to publish the module-owned
  default `anomaly_scan_planner_ops_t` table.
- Updated the main detector flow to consume the scan-planner default ops
  accessor instead of a local static callback table.
- Added focused native tests for stable singleton access, populated callback
  slots, registration-valid forwarding, refresh-mask allocation/output
  clearing, target-revisit count forwarding, and adaptive target-risk
  forwarding.

Behavior preserved:

- Registration validity callback behavior, target-revisit track count,
  adaptive target-risk forwarding, ROI-track aging callback, refresh-mask
  scratch-buffer allocation, output clearing on invalid inputs, scan-planner
  input wiring, scan-plan decisions, selective refresh behavior, timing, public
  API, threading, result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_scan_planner.{h,c}`, confirmed the local callback wrappers
  and `scan_planner_ops` table were removed from `anomaly_analysis.c`, reviewed
  the rewritten planner input call site, and added focused native default-ops
  contract tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2654 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  scan-planner callback glue only and locks callback forwarding plus
  refresh-mask allocation behavior with focused native tests. Detector flow,
  candidate selection, scan planning decisions, timing, threading, result
  publication, and overlay behavior are unchanged.

## Packet 98 - Motion Estimator Default Sidecar Ops

Status: parent-validated.

Mode: motion-estimator consumer-interface helper extraction,
behavior-preserving, after Packet 97.

Scope:

- Moved Motion Estimator sidecar callback glue out of `anomaly_analysis.c` into
  `anomaly_motion_estimator.c`.
- Added `anomaly_motion_estimator_default_sidecar_ops(...)` to publish the
  module-owned default `anomaly_motion_estimator_sidecar_ops_t` table.
- Updated the main detector flow to consume the Motion Estimator default
  sidecar ops accessor instead of a local static callback table.
- Kept the existing local `project_motion_cell(...)` helper in
  `anomaly_analysis.c` because downstream scoring paths still call it
  directly.
- Added focused native tests for stable singleton access, populated callback
  slots, registration-valid forwarding, invalid projection rejection, identity
  projection, and translated affine projection.

Behavior preserved:

- Motion sidecar registration validity behavior, residual-displacement callback
  behavior, motion-cell projection math, projection bounds rejection, Motion
  Estimator input wiring, movement sidecar calculation, later scoring paths
  that still use the local projection helper, timing, public API, threading,
  result publication, and overlay behavior are unchanged.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed the local Motion
  Estimator sidecar wrapper/table was removed from `anomaly_analysis.c`,
  reviewed the rewritten movement input call site, and added focused native
  default-sidecar-ops contract tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2665 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Motion Estimator sidecar callback glue only and locks callback forwarding
  plus projection behavior with focused native tests. Detector flow, candidate
  selection, scan planning decisions, timing, threading, result publication,
  and overlay behavior are unchanged.

## Packet 99 - Motion Estimator Projection Helper

Status: parent-validated.

Mode: motion-estimator helper promotion, behavior-preserving, after Packet 98.

Scope:

- Promoted motion-cell projection from a local `anomaly_analysis.c` helper to
  `anomaly_motion_estimator_project_cell(...)`.
- Updated the Motion Estimator default sidecar ops table to use the public
  module helper instead of a duplicate internal implementation.
- Updated remaining motion scoring call sites in `anomaly_analysis.c` to call
  the Motion Estimator helper directly.
- Removed the local `project_motion_cell(...)` helper from
  `anomaly_analysis.c`.
- Extended focused native tests to cover the public helper directly and verify
  the default sidecar callback forwards the same projection behavior.

Behavior preserved:

- Projection input validation, normalized-cell math, affine registration
  application, rounded motion-cell index calculation, out-of-bounds rejection,
  optional output writes, Motion Estimator sidecar behavior, later motion
  scoring paths, timing, public API, threading, result publication, and overlay
  behavior are unchanged.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed the duplicate/local
  projection helper was removed from `anomaly_analysis.c`, reviewed all
  rewritten motion scoring call sites, and extended focused native projection
  tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2667 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change promotes
  shared Motion Estimator projection math only and locks invalid, identity,
  translated, and callback-forwarding behavior with focused native tests.
  Detector flow, candidate selection, scan planning decisions, timing,
  threading, result publication, and overlay behavior are unchanged.

## Packet 100 - Scan Planner Previous-Sample Lookup Builder

Status: parent-validated.

Mode: scan-planner helper extraction, behavior-preserving, after Packet 99.

Scope:

- Moved previous-sample lookup construction out of `anomaly_analysis.c` into
  `anomaly_scan_planner.{h,c}` as
  `anomaly_scan_planner_build_prev_sample_lookup(...)`.
- Added `ANOMALY_SCAN_PLANNER_PREV_LOOKUP_INVALID` as the module-owned invalid
  lookup sentinel.
- Removed the local `anomaly_prev_sample_lookup_summary_t`, local invalid
  sentinel, local `build_prev_sample_lookup_map(...)`, and the summary adapter
  from `anomaly_analysis.c`.
- Updated the main detector flow to use
  `anomaly_scan_planner_prev_lookup_summary_t` directly and pass that summary
  into `anomaly_scan_planner_plan(...)`.
- Kept scratch lookup allocation and existing pre-call gating in
  `anomaly_analysis.c`.
- Added focused native tests for rejected input summary clearing, identity
  row-major lookup mapping, invalid previous-sample exposure, and stale-sample
  accounting.

Behavior preserved:

- Lookup input validation, inverse-affine construction, normalized sample
  center math, previous ROI bounds checks, previous sample index calculation,
  invalid previous-sample handling, carried/newly-exposed/stale counters,
  scratch allocation ownership, scan-planner input wiring, selective-refresh
  mask behavior, timing, public API, threading, result publication, and overlay
  behavior are unchanged.
- The existing `coverage_age` availability assumption remains unchanged; this
  packet does not add defensive hardening or alter ROI lifecycle behavior.

Parent validation:

- Reviewed `anomaly_scan_planner.{h,c}`, confirmed the local lookup builder and
  summary adapter were removed from `anomaly_analysis.c`, reviewed the
  rewritten call site, and added focused native lookup-builder tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2691 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  ScanPlanner-owned previous-sample lookup construction only and locks the
  copied lookup, invalid-sample, and stale-accounting behavior with focused
  native tests. Detector scoring, candidate selection, scan-plan policy,
  refresh-mask selection policy, timing, threading, result publication, and
  overlay behavior are unchanged.

## Packet 101 - Registration Feature Score Helper

Status: parent-validated.

Mode: registration-image helper extraction, behavior-preserving, after Packet
100.

Scope:

- Moved the local `gmv_feature_score(...)` primitive out of
  `anomaly_analysis.c` into `anomaly_registration_image.{h,c}` as
  `anomaly_registration_feature_score(...)`.
- Updated affine-corner detection, GMV registration anchor selection, and the
  later motion texture-score call site to use the module-prefixed helper.
- Added focused native tests for null input, border rejection, and the legacy
  absolute center-vs-neighbor delta sum.

Behavior preserved:

- Null input behavior, border rejection, 8-neighbor absolute-delta scoring,
  affine corner ranking, GMV anchor feature scoring, motion texture-score
  input, timing, public detector API, threading, result publication, and
  overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because `anomaly_registration_feature_score(...)` was undeclared.

Parent validation:

- Reviewed `anomaly_registration_image.{h,c}`, confirmed the local
  `gmv_feature_score(...)` helper was removed from `anomaly_analysis.c`,
  reviewed all rewritten call sites, and added focused native
  registration-image feature-score tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2694 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts a
  pure registration-image texture primitive only and locks the copied edge/null
  and contrast-sum behavior with focused native tests. Registration estimation
  flow, registration solve policy, motion scoring policy, candidate selection,
  scan planning, timing, threading, result publication, and overlay behavior
  are unchanged.

## Packet 102 - Motion Appearance Readiness Contract

Status: parent-validated.

Mode: motion-estimator contract extraction, behavior-preserving, after Packet
101.

Scope:

- Added `prev_luma_width` and `prev_luma_height` to
  `anomaly_motion_appearance_scorer_input_t`.
- Added `anomaly_motion_estimator_appearance_scorer_ready(...)` to
  `anomaly_motion_estimator.{h,c}`.
- Replaced the inline appearance-motion scorer readiness gate in
  `anomaly_analysis.c` with the MotionEstimator helper.
- Removed the unused-placeholder cast for `motion_appearance_input`; the input
  contract now directly feeds the readiness decision.
- Added focused native tests covering every copied gate branch: null input,
  inactive detector, ineligible algorithm mask, eligible motion/tolerance/
  persist masks, null current luma, null previous luma, previous-luma dimension
  mismatch, and scene discontinuity.

Behavior preserved:

- The readiness predicate still checks exactly the original conditions:
  detector active, eligible motion/persist algorithm mask, current luma,
  previous luma, previous-luma width match, previous-luma height match, and no
  scene discontinuity.
- Motion scoring loops, persistence allocation/update, support-map stamping,
  appearance proposals, candidate scoring, scan planning, timing, public
  detector API, threading, result publication, and overlay behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because `prev_luma_width`, `prev_luma_height`, and
  `anomaly_motion_estimator_appearance_scorer_ready(...)` were not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed the inline gate was
  replaced in `anomaly_analysis.c`, confirmed no scorer body/support-map logic
  moved, and added focused native readiness tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2705 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts the
  exact appearance-motion scorer readiness predicate only. Scoring loops,
  persistence allocation/update, support-map stamping, candidate selection,
  scan planning, timing, threading, result publication, and overlay behavior
  are unchanged.

## Packet 103 - Motion Appearance Input Contract Builder

Status: parent-validated.

Mode: motion-estimator contract extraction, behavior-preserving, after Packet
102.

Scope:

- Added `anomaly_motion_appearance_scorer_input_args_t` to describe the
  caller-provided source values for MotionEstimator appearance scoring.
- Added `anomaly_motion_estimator_init_appearance_scorer_input(...)` to
  initialize `anomaly_motion_appearance_scorer_input_t` and the caller-owned
  `anomaly_motion_appearance_scorer_state_t`.
- Moved `use_motion_tolerance` and `use_stable_motion` derivation into the
  MotionEstimator helper.
- Replaced the local input/state struct initialization in `anomaly_analysis.c`
  with the MotionEstimator helper while keeping the scorer loop in place.
- Added focused native tests for null-safe initialization, copied contract
  fields, derived motion mode flags, and readiness compatibility.

Behavior preserved:

- The same caller/runtime values feed the appearance-motion scorer.
- `use_motion_tolerance` still follows `ANOMALY_ALGO_MOTION_TOLERANCE`.
- `use_stable_motion` still follows `ANOMALY_ALGO_MOTION` only when tolerance
  mode is not active.
- Motion scoring loops, persistence allocation/update, support-map stamping,
  appearance proposal scoring, candidate selection, scan planning, timing,
  public detector API, threading, result publication, and overlay behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the input args type and builder function were not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates input/state initialization to the
  MotionEstimator helper, confirmed the scorer loop/support-map logic stayed
  in place, and added focused native input-contract tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2729 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  struct initialization and mode derivation only. Scoring loops, support maps,
  candidate selection, persistence allocation/update, timing, result
  publication, and overlay behavior are unchanged.

## Packet 104 - Motion Appearance Grid Bounds Contract

Status: parent-validated.

Mode: motion-estimator derived-input contract extraction,
behavior-preserving, after Packet 103.

Scope:

- Added `anomaly_motion_appearance_grid_bounds_t`.
- Added `anomaly_motion_estimator_appearance_grid_bounds(...)` to derive the
  motion-grid ROI bounds from `anomaly_motion_appearance_scorer_input_t`.
- Replaced the local `roi_mgx0`, `roi_mgx1`, `roi_mgy0`, and `roi_mgy1`
  calculation in `anomaly_analysis.c` with the MotionEstimator helper output.
- Added focused native tests for invalid input clearing, normal ROI conversion,
  negative/oversized ROI clamping, partial-edge upper-bound ceil behavior, and
  invalid dimension handling.

Behavior preserved:

- Lower bounds still use integer division by `motion_step`.
- Upper bounds still use `(roi_end + motion_step - 1) / motion_step`.
- Bounds still clamp to `[0, motion_w]` and `[0, motion_h]`.
- Global motion sampling loops, zoom/broad motion scale formulas, persistence
  allocation/decay/update, candidate local scoring, support-map stamping,
  candidate selection, timing, public detector API, threading, result
  publication, and overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the grid-bounds type and helper function were not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only ROI-to-motion-grid bounds derivation
  to the MotionEstimator helper, confirmed the scoring/support/persistence
  body stayed in place, and added focused native grid-bounds tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2740 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  ROI-to-motion-grid geometry plumbing only. Scoring, support maps, candidate
  selection, persistence allocation/update, timing, result publication, and
  overlay behavior are unchanged.

## Packet 105 - Motion Appearance Scale Helpers

Status: parent-validated.

Mode: motion-estimator derived-scale contract extraction,
behavior-preserving, after Packet 104.

Scope:

- Added `anomaly_motion_estimator_appearance_zoom_motion_scale(...)`.
- Added `anomaly_motion_estimator_appearance_broad_motion_scale(...)`.
- Replaced the local zoom-motion scale formula in `anomaly_analysis.c` with
  the MotionEstimator helper.
- Replaced the local broad-motion scale formula in `anomaly_analysis.c` with
  the MotionEstimator helper.
- Added focused native tests for zoom identity/tiny/midpoint/clamped deltas,
  symmetric shrink/expand handling, broad-load threshold/midpoint behavior, and
  the legacy broad-motion minimum scale.

Behavior preserved:

- Zoom scale still starts at `1.0`, begins suppressing after absolute
  registration-scale delta `0.004`, reaches zero at delta `0.018`, and clamps
  at zero beyond that.
- Broad-motion scale still stays at `1.0` through global motion load `0.12`,
  ramps down over the next `0.18`, and clamps at `0.20`.
- Global residual sampling loops, global mean/std and motion-floor derivation,
  persistence allocation/decay/update, local candidate scoring, support-map
  stamping, candidate selection, timing, public detector API, threading,
  result publication, and overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the two scale helper functions were not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only the zoom and broad-motion scale
  formulas to MotionEstimator helpers, confirmed sampling/statistics/
  persistence/scoring/support code stayed in place, and added focused native
  scale tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2751 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  pure scale formulas only. Residual sampling, global statistics, motion-floor
  derivation, support maps, candidate selection, persistence lifecycle, timing,
  result publication, and overlay behavior are unchanged.

## Packet 106 - Motion Appearance Global Stats Helper

Status: parent-validated.

Mode: motion-estimator post-sampling stats contract extraction,
behavior-preserving, after Packet 105.

Scope:

- Added `anomaly_motion_appearance_global_stats_t`.
- Added `anomaly_motion_estimator_appearance_global_stats(...)` to derive
  global motion mean, global motion std, and motion floor from the already
  sampled residual sum, sum of squares, sample count, and motion step.
- Replaced only the local post-sampling mean/std/floor derivation in
  `anomaly_analysis.c`.
- Added focused native tests for zero-count defaults, positive-count
  derivation, variance floor, std floor, motion-floor minimum, and null output
  safety.

Behavior preserved:

- Zero-count defaults remain mean `0.0`, std `motion_step * 0.5`, and floor
  `motion_step`.
- Positive-count variance still uses `sum2 / count - mean * mean`, still floors
  variance at `0.04`, and still floors std at `motion_step * 0.35`.
- Motion floor still derives as `mean + 0.75 * std` and still floors at
  `motion_step * 0.85`.
- Residual sampling loops, strong-sample counting, global-load derivation,
  broad scale, persistence allocation/decay/update, local candidate scoring,
  support-map stamping, candidate selection, timing, public detector API,
  threading, result publication, and overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the global-stats type and helper function were not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only post-sampling mean/std/floor
  derivation to the MotionEstimator helper, confirmed sampling/load/
  persistence/scoring/support code stayed in place, and added focused native
  global-stats tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2760 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  post-sampling pure math only. Residual sampling, strong-sample/load
  derivation, support maps, candidate selection, persistence lifecycle, timing,
  result publication, and overlay behavior are unchanged.

## Packet 107 - Motion Appearance Global Load Helper

Status: parent-validated.

Mode: motion-estimator post-sampling load-fraction extraction,
behavior-preserving, after Packet 106.

Scope:

- Added `anomaly_motion_estimator_appearance_global_motion_load(...)`.
- Replaced only the local global-load fraction assignment in
  `anomaly_analysis.c`.
- Added focused native tests for zero-count default behavior, zero
  strong-sample behavior, normal ratio calculation, and the legacy unclamped
  ratio case.

Behavior preserved:

- `global_count <= 0` still yields `0.0`.
- Positive `global_count` still yields
  `(float)strong_global_samples / (float)global_count`.
- The ratio remains intentionally unclamped; values above `1.0` are preserved
  if the caller provides `strong_global_samples > global_count`.
- Residual sampling loops, strong-sample counting, `motion_floor_px +
  global_motion_std` thresholding, broad scale, persistence
  allocation/decay/update, local candidate scoring, support-map stamping,
  candidate selection, timing, public detector API, threading, result
  publication, and overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the global-motion-load helper function was not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only the final global-load fraction to
  the MotionEstimator helper, confirmed sampling/thresholding/broad-scale/
  persistence/scoring/support code stayed in place, and added focused native
  global-load tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2765 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  final load-fraction math only. Residual sampling, strong-sample thresholding,
  broad scale, support maps, candidate selection, persistence lifecycle,
  timing, result publication, and overlay behavior are unchanged.

## Packet 108 - Motion Appearance State Sync Helper

Status: parent-validated.

Mode: motion-estimator caller-owned state metadata extraction,
behavior-preserving, after Packet 107.

Scope:

- Added `anomaly_motion_estimator_sync_appearance_scorer_state(...)`.
- Reused the helper inside
  `anomaly_motion_estimator_init_appearance_scorer_input(...)`.
- Replaced only the local post-allocation `motion_appearance_state.persist`,
  `persist_w`, and `persist_h` assignments in `anomaly_analysis.c`.
- Added focused native tests for null-state no-op, copying null persistence
  metadata into a dirty state, and copying non-null persistence metadata.

Behavior preserved:

- The helper copies exactly the caller-provided persistence pointer and
  dimensions.
- Null `state` is a no-op.
- No allocation, validation, normalization, ownership transfer, decay, update,
  scoring, or support-map behavior moved.
- `anomaly_analysis.c` still owns `free`, `calloc`, and the allocation
  success/failure assignment of `state->motion_persist_w/h`.
- Persistence decay/update, local candidate scoring, support-map stamping,
  candidate selection, timing, public detector API, threading, result
  publication, and overlay behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the persistence-state sync helper was not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only persistence metadata sync to the
  MotionEstimator helper, confirmed allocation/free/decay/update/scoring/
  support code stayed in place, and added focused native state-sync tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2769 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  metadata sync only. Allocation/free, persistence decay/update, persistence
  scoring, support maps, candidate selection, timing, result publication, and
  overlay behavior are unchanged.

## Packet 109 - Motion Appearance Debug Summary Helper

Status: parent-validated.

Mode: motion-estimator debug-summary plumbing extraction,
behavior-preserving, after Packet 108.

Scope:

- Added `anomaly_motion_estimator_populate_appearance_debug_summary(...)`.
- Replaced only the early scalar `result_out->motion_debug` summary block in
  `anomaly_analysis.c`.
- Added focused native tests for null debug pointer safety, validity based on
  global sample count or motion candidate count, scalar field copying, invalid
  no-sample/no-candidate summaries, and preservation of later debug fields.

Behavior preserved:

- Debug summary validity still follows `global_count > 0 ||
  motion_candidate_count > 0`.
- Scene discontinuity, sample step, motion step, sample count, residual mean,
  residual std, zoom scale, broad scale, and global motion load are copied
  exactly from caller-provided values.
- Later raw candidate, winner, and top-candidate debug fields are untouched by
  the helper and remain populated later in `anomaly_analysis.c`.
- Residual sampling loops, persistence allocation/decay/update, local candidate
  scoring, support-map stamping, candidate selection, timing, public detector
  API, threading, result publication semantics, and overlay behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the debug-summary helper was not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}`, confirmed
  `anomaly_analysis.c` now delegates only early scalar debug summary
  publication to the MotionEstimator helper, confirmed later raw/winner/
  top-candidate debug publication stayed in place, and added focused native
  debug-summary tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2789 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  scalar debug summary publication only. Scoring, support maps, candidate
  selection, persistence lifecycle, timing, result publication semantics, and
  overlay behavior are unchanged.

## Packet 110 - Motion Appearance Debug Result Helper

Status: parent-validated.

Mode: motion-estimator debug-result plumbing extraction,
behavior-preserving, after Packet 109.

Scope:

- Added `anomaly_motion_estimator_populate_appearance_debug_result(...)`.
- Replaced only the late `result_out->motion_debug` raw candidate,
  winner-component, winner-scale, and top-candidate publication block in
  `anomaly_analysis.c`.
- Added focused native tests for null debug pointer safety, legacy raw
  coordinate publication with invalid raw score, valid raw coordinate
  normalization, preservation of early summary fields, winner field copying,
  persistence-scale copying, and top-candidate clamping.

Behavior preserved:

- Raw candidate validity still follows `raw_score >= 0.0f`.
- Raw x/y normalization still follows the legacy `(raw_x > 0 || raw_y > 0)`
  coordinate convention.
- Winner component fractions, zoom/broad scales, global motion load,
  texture/structure/support/persistence scales, and top candidates are copied
  from the same caller-owned scalars/array used by the old local block.
- The helper does not read from `motion_appearance_output`, alter winner
  eligibility, change top-candidate ordering, or reinterpret `ANOMALY_ALGO_PERSIST`
  mirror output as motion raw output.
- Residual sampling loops, persistence allocation/decay/update, local
  candidate scoring, support-map stamping, candidate selection, timing, public
  detector API, threading, result box publication, and overlay behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the debug-result helper was not yet defined.

Parent validation:

- Reviewed `anomaly_motion_estimator.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only late motion debug publication to the
  MotionEstimator helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2812 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  debug publication only. Scoring, support maps, candidate selection,
  persistence lifecycle, timing, result box publication, overlay drawing, and
  public API behavior are unchanged.

## Packet 111 - Result Box Publish Helper

Status: parent-validated.

Mode: result-builder consumer/result plumbing extraction,
behavior-preserving, after Packet 110.

Scope:

- Added `anomaly_result_publish_boxes(...)`.
- Replaced only the two local `result_out->box_count` / `result_out->boxes`
  publish-copy blocks in `anomaly_analysis.c`.
- Added focused native tests for null result safety, zero-count publication,
  exact field copying, unrelated result-field preservation, and oversized
  caller count storage with bounded slot copying.

Behavior preserved:

- `result_out->box_count` stores the caller-provided count exactly.
- Box copying remains bounded by `ANOMALY_MAX_BOXES_PER_FRAME`.
- A null `result_out` is a no-op.
- Box construction, overlay-box assembly, candidate blob overlay appends,
  debug publication, timing finalization, detector scoring, support maps,
  candidate selection, scan planning, threading, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed before implementation
  because the result publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed both local
  publish-copy blocks in `anomaly_analysis.c` delegate only result box copying
  to the helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2819 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  result-copy plumbing only. Box construction, scoring, support maps, candidate
  selection, persistence lifecycle, scan planning, timing, overlay drawing,
  debug publication, and public API behavior are unchanged.

## Packet 112 - Result Frame Metadata Publish Helper

Status: parent-validated.

Mode: result-builder frame/result metadata extraction,
behavior-preserving, after Packet 111.

Scope:

- Added `anomaly_result_frame_metadata_t`.
- Added `anomaly_result_publish_frame_metadata(...)`.
- Replaced only the early frame/result metadata publication block in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, scalar metadata
  copying, scan-plan copying, registration-debug delegation, movement-debug
  copying, and preservation of unrelated result fields.

Behavior preserved:

- Frame discontinuity, registration/refresh flags, registration health,
  rescan mode, scan plan, adaptive stride metadata, registration debug, and
  movement debug are copied from the same local values as the old block.
- The helper consumes explicit producer snapshots/scalars and does not depend
  on `anomaly_scan_planner_output_t`.
- Scan-plan construction, adaptive-stride decisions, registration
  estimation/cache storage, movement sidecar production, target tracking, the
  later post-target-tracking movement-debug write, box construction/
  publication, timing finalization, detector scoring, support maps, candidate
  selection, persistence lifecycle, threading, overlay drawing, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the frame metadata publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only early frame/result metadata publication
  to the helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2830 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  result metadata publication only. Scan planning, adaptive decisions,
  registration solving/cache policy, movement estimation, scoring, support
  maps, candidate selection, persistence lifecycle, timing, threading, box
  publication, overlay drawing, and public API behavior are unchanged.

## Packet 113 - Result Saliency Debug Publish Helper

Status: parent-validated.

Mode: result-builder saliency result/debug publication extraction,
behavior-preserving, after Packet 112.

Scope:

- Added `anomaly_result_saliency_debug_publication_t`.
- Added `anomaly_result_publish_saliency_debug(...)`.
- Replaced only the late saliency debug publication block in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, raw validity and
  legacy coordinate normalization, pre/post accumulator field copying, switch
  suppression, unrelated result-field preservation, and oversized
  top-candidate count with bounded copy.

Behavior preserved:

- Raw candidate validity still follows `raw_score >= 0.0f`.
- Raw x/y normalization still follows the legacy `(raw_x > 0 || raw_y > 0)`
  coordinate convention.
- Top candidate count is stored exactly as provided, while candidate copying
  remains bounded by `ANOMALY_DEBUG_TOP_CANDIDATES`.
- The helper consumes explicit producer scalars and a candidate array; it does
  not build or reorder saliency candidates.
- Saliency scoring, best-persist selection, `saliency_top` construction/order,
  accumulator updates, auxiliary saliency tracks, support maps, persistence
  lifecycle, timing finalization, thermal/color debug publication, overlay
  drawing, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the saliency debug publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only late saliency result/debug publication to
  the helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2847 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  saliency result/debug publication only. Scoring, candidate ordering, support
  maps, persistence lifecycle, timing, threading, thermal/color debug
  publication, overlay drawing, and public API behavior are unchanged.

## Packet 114 - Result Thermal Debug Summary Publish Helper

Status: parent-validated.

Mode: result-builder thermal summary result/debug publication extraction,
behavior-preserving, after Packet 113.

Scope:

- Added `anomaly_result_thermal_debug_summary_publication_t`.
- Added `anomaly_result_publish_thermal_debug_summary(...)`.
- Replaced only the late thermal debug summary publication block in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, raw validity and
  legacy coordinate normalization, scalar frame statistics, winning/candidate
  count copying, and preservation of target telemetry, thermal candidate slots,
  and unrelated debug fields.

Behavior preserved:

- Raw candidate validity still follows `raw_score >= 0.0f`.
- Raw x/y normalization still follows the legacy `(raw_x > 0 || raw_y > 0)`
  coordinate convention.
- Thermal target telemetry and thermal candidate-array publication remain in
  `anomaly_analysis.c`.
- The helper consumes explicit producer scalars; it does not build, rank,
  select, or publish thermal target/candidate details.
- Thermal scoring, support maps, candidate selection/order, target tracing,
  persistence lifecycle, timing finalization, color/saliency publication,
  overlay drawing, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the thermal debug summary publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only late thermal summary result/debug
  publication to the helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2861 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  scalar thermal result/debug publication only. Thermal target telemetry,
  thermal candidate arrays, scoring, support maps, candidate selection/order,
  target tracing, persistence lifecycle, timing, threading, overlay drawing,
  color/saliency publication, and public API behavior are unchanged.

## Packet 115 - Result Color Debug Summary Publish Helper

Status: parent-validated.

Mode: result-builder Color debug reset/scalar publication extraction,
behavior-preserving, after Packet 114.

Scope:

- Added `anomaly_result_color_debug_summary_publication_t`.
- Added `anomaly_result_publish_color_debug_summary(...)`.
- Replaced only the Color debug reset and scalar/header publication block in
  `anomaly_analysis.c`.
- Kept Color target telemetry, target trace matching/index logic, bbox
  normalization, and candidate array publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, Color debug reset
  semantics, raw summary validity and legacy coordinate normalization,
  phase/sample coverage, histogram/support/coarse/reject summary fields,
  strongest seed publication, winner gate fields, and zero sample-grid
  fraction reset behavior.

Behavior preserved:

- The helper performs the same `color_debug` reset as the legacy block before
  publishing scalar summary fields.
- Raw candidate validity still follows
  `raw_best_color_candidate_idx >= 0 || best_color >= 0.0f`.
- Raw score/location still prefer the explicit raw candidate when
  `raw_best_color_candidate_idx >= 0`; otherwise they fall back to `best_color`
  and the legacy `(x > 0 || y > 0)` coordinate convention.
- Sample fractions are computed only when `sg_count > 0`; otherwise they
  remain zero from the reset.
- `selective_reuse_active` still follows
  `selective_refresh_active && !color_forced_full_refresh`.
- Strongest seed validity still follows `color_strongest_seed_score > 0.0f`.
- Target telemetry and candidate arrays are still populated by the caller after
  the helper returns.
- Color scoring, rarity/support maps, candidate generation/order/selection,
  target tracking, persistence lifecycle, scan planning, timing finalization,
  threading, overlay drawing, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color debug summary publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the Color debug reset and scalar
  publication range through `candidate_count`; target and candidate detail
  remain in the producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2905 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color result/debug reset and scalar publication only. Color target telemetry,
  candidate arrays, scoring, rarity/support maps, candidate generation/order/
  selection, persistence lifecycle, scan planning, timing, threading, overlay
  drawing, thermal/saliency publication, and public API behavior are unchanged.

## Packet 116 - Result Color Target Base Publish Helper

Status: parent-validated.

Mode: result-builder Color target base reset/scalar publication extraction,
behavior-preserving, after Packet 115.

Scope:

- Added `anomaly_result_color_debug_target_base_publication_t`.
- Added `anomaly_result_publish_color_debug_target_base(...)`.
- Replaced only the Color debug target reset and base scalar/telemetry
  publication block in `anomaly_analysis.c`.
- Kept component trace publication, component bbox normalization, target
  extracted/matched/nearest/winning candidate index logic, target winner-gate
  rejection fields, stage publication, matched-candidate score/position/bbox,
  and Color candidate array publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, target reset
  semantics, enabled/disabled target norm gating, histogram/local-support
  copying, patch/ring telemetry copying, support-map scalar copying, and
  preservation of Color summary, Color candidate slots, and unrelated result
  fields.

Behavior preserved:

- The helper performs the same `color_debug.target` reset as the legacy target
  block before publishing target base fields.
- Target x/y norms still publish configured debug target coordinates only when
  the target is enabled; disabled targets publish `0.0f`.
- Patch/ring telemetry, histogram/local-support scalars, support scores,
  support-map diagnostics, and support-seed eligibility are direct copies from
  the caller-provided producer payload.
- Component trace fields, bbox normalization, extracted/matched/nearest/winning
  candidate index logic, winner-gate target rejection fields, target stage,
  matched-candidate details, and candidate arrays are still populated by the
  caller after the helper returns.
- Color scoring, support-map construction, rarity/support maps, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target base publish helper was not yet defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the target reset and base scalar
  publication range through `support_seed_eligible`; component/candidate detail
  remains in the producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2939 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color target debug reset and base scalar publication only. Component trace
  logic, bbox normalization, target/candidate matching, candidate arrays,
  scoring, support-map construction, candidate generation/order/selection,
  target tracking lifecycle, scan planning, timing, threading, overlay drawing,
  thermal/saliency publication, and public API behavior are unchanged.

## Packet 117 - Result Color Target Component Trace Publish Helper

Status: parent-validated.

Mode: result-builder Color target component-trace scalar publication
extraction, behavior-preserving, after Packet 116.

Scope:

- Added `anomaly_result_color_debug_target_component_trace_publication_t`.
- Added `anomaly_result_publish_color_debug_target_component_trace(...)`.
- Replaced only the Color debug target component/cap/NMS/pre-cap scalar
  publication island in `anomaly_analysis.c`.
- Kept component bbox normalization, target extracted/matched/nearest/winning
  candidate index logic, target winner-gate rejection fields, stage
  publication, matched-candidate score/position/bbox, and Color candidate
  array publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact component/
  cap/NMS/pre-cap scalar copying, additive behavior, Color summary and
  candidate slot preservation, unrelated result-field preservation, and
  preservation of non-contract bbox/index/stage/matched-candidate fields.

Behavior preserved:

- The helper is additive and does not reset `color_debug.target`; Packet 116's
  target-base helper still owns target reset semantics.
- Component seed/peak coordinates, area/span/fill/support/quality/rejection
  scalars, cap/NMS flags, conflict diagnostics, and pre-cap rank/count/limit/
  retention fields are direct copies from the caller-provided producer payload.
- The private `color_blob_target_trace` type remains private to
  `anomaly_analysis.c`; ResultBuilder consumes only an explicit publication
  struct.
- Bbox normalization, extracted/matched/nearest/winning candidate index logic,
  winner-gate target rejection fields, target stage, matched-candidate details,
  and candidate arrays are still populated by the caller after the helper
  returns.
- Color scoring, support-map construction, rarity/support maps, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target component-trace publish helper was not yet
  defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the component/cap/NMS/pre-cap scalar
  publication range before `anomaly_color_candidate_bbox_norm(...)`; bbox,
  matching, stage, matched-candidate detail, and candidate arrays remain in the
  producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2962 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color target component-trace scalar publication only. Bbox normalization,
  target/candidate matching, winner-gate target rejection, target stage
  publication, matched-candidate enrichment, candidate arrays, scoring,
  support-map construction, candidate generation/order/selection, target
  tracking lifecycle, scan planning, timing, threading, overlay drawing,
  thermal/saliency publication, and public API behavior are unchanged.

## Packet 118 - Result Color Target Component Bbox Publish Helper

Status: parent-validated.

Mode: result-builder Color target component bbox publication extraction,
behavior-preserving, after Packet 117.

Scope:

- Added `anomaly_result_color_debug_target_component_bbox_publication_t`.
- Added `anomaly_result_publish_color_debug_target_component_bbox(...)`.
- Replaced only the Color debug target component bbox normalization/publication
  island in `anomaly_analysis.c`.
- Kept extracted/matched/nearest/winning candidate index logic, target
  winner-gate rejection fields, stage publication, matched-candidate score/
  position/bbox, and Color candidate array publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, valid bbox
  normalization and clamping, invalid bounds zeroing, additive behavior,
  preservation of target-base/component-trace fields, Color summary and
  candidate slot preservation, unrelated result-field preservation, and
  preservation of non-contract index/stage/matched fields.

Behavior preserved:

- The helper is additive and publishes only `component_bbox_*` fields.
- The helper delegates to the existing `anomaly_color_candidate_bbox_norm(...)`
  implementation using explicit ROI/sample/frame geometry from the caller.
- The private `color_blob_target_trace` type remains private to
  `anomaly_analysis.c`; ResultBuilder consumes only explicit scalar geometry.
- Extracted/matched/nearest/winning candidate index logic, winner-gate target
  rejection fields, target stage, matched-candidate details, and candidate
  arrays are still populated by the caller after the helper returns.
- Color scoring, support-map construction, rarity/support maps, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target component bbox publish helper was not yet
  defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the component bbox publication range
  before `extracted_candidate_index`; matching, stage, matched-candidate
  detail, and candidate arrays remain in the producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2980 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color target component bbox publication only using the existing bbox
  normalization helper. Target/candidate matching, winner-gate target
  rejection, target stage publication, matched-candidate enrichment, candidate
  arrays, scoring, support-map construction, candidate generation/order/
  selection, target tracking lifecycle, scan planning, timing, threading,
  overlay drawing, thermal/saliency publication, and public API behavior are
  unchanged.

## Packet 119 - Result Color Target Candidate Indices Publish Helper

Status: parent-validated.

Mode: result-builder Color target candidate-index publication extraction,
behavior-preserving, after Packet 118.

Scope:

- Added `anomaly_result_candidate_sample_t`.
- Added `anomaly_result_color_debug_target_candidate_indices_publication_t`.
- Added `anomaly_result_publish_color_debug_target_candidate_indices(...)`.
- Replaced only the Color debug target extracted/matched/nearest/winning index
  publication island in `anomaly_analysis.c`.
- Kept target winner-gate rejection fields, stage publication,
  matched-candidate score/position/bbox enrichment, and Color candidate array
  publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, extracted-index
  lookup from component peak sample coordinates, matched-index fallback,
  invalid matched-index rejection, nearest/distance/winning copy,
  winning-rank derivation, additive behavior, and preservation of target bbox,
  winner-gate fields, stage, matched-candidate fields, Color summary fields,
  candidate slots, and unrelated result fields.

Behavior preserved:

- Extracted candidate index still starts at `-1`.
- When component peak sample coordinates are valid, the helper searches the
  caller-provided candidate sample coordinates in order and uses the first
  exact match.
- If no extracted candidate is found, a valid in-range matched candidate index
  still becomes the fallback extracted candidate index.
- Matched/nearest/winning indices and nearest distance are direct copies from
  the caller-provided producer payload.
- Winning rank still equals `winning_candidate_index` only when the matched
  candidate is the winner; otherwise it remains `-1`.
- The helper consumes a small publication sample DTO rather than
  `anomaly_motion_candidate_t` or private `anomaly_analysis.c` structs.
- Winner-gate target rejection fields, target stage, matched-candidate details,
  and candidate arrays are still populated by the caller after the helper
  returns.
- Color scoring, support-map construction, rarity/support maps, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target candidate-index publish helper was not yet
  defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the target candidate-index publication
  range before winner-gate target rejection; stage, matched-candidate detail,
  and candidate arrays remain in the producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `2997 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color target candidate-index debug publication only. Winner-gate target
  rejection, target stage publication, matched-candidate enrichment, candidate
  arrays, scoring, support-map construction, candidate generation/order/
  selection, target tracking lifecycle, scan planning, timing, threading,
  overlay drawing, thermal/saliency publication, and public API behavior are
  unchanged.

## Packet 120 - Result Color Target Gate/Stage Publish Helper

Status: parent-validated.

Mode: result-builder Color target winner-gate/stage publication extraction,
behavior-preserving, after Packet 119.

Scope:

- Added `anomaly_result_color_debug_target_gate_stage_publication_t`.
- Added `anomaly_result_publish_color_debug_target_gate_stage(...)`.
- Replaced only the Color debug target winner-gate/stage publication island in
  `anomaly_analysis.c`.
- Kept matched-candidate score/position/bbox enrichment and Color candidate
  array publication in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, winner-gate
  rejection only when the reject reason is non-none and the matched candidate
  equals the raw-best Color candidate, nonmatching/negative/NONE clearing
  behavior, stage copying, and additive preservation of candidate-index fields,
  component bbox fields, matched-candidate fields, Color summary fields,
  candidate slots, and unrelated result fields.

Behavior preserved:

- `rejected_by_winner_gate` is still true only when a non-NONE reject reason is
  paired with a valid matched candidate that equals the raw-best Color
  candidate.
- Target `winner_gate_reject_reason` is still copied only for the rejecting
  case and otherwise cleared to `ANOMALY_COLOR_WINNER_GATE_NONE`.
- Target `stage` is still copied directly from the caller-provided stage.
- Matched-candidate enrichment, Color candidate arrays, scoring, support-map
  construction, candidate generation/order/selection, target tracking
  lifecycle, scan planning, timing finalization, threading, overlay drawing,
  thermal/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target gate/stage publish helper was not yet
  defined.

Parent validation:

- Reviewed `anomaly_result_builder.{h,c}` and confirmed
  `anomaly_analysis.c` delegates only the target winner-gate/stage publication
  range before matched-candidate detail; candidate arrays remain in the
  producer path.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3011 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Pause-before-next-packet validation refresh:

- Main IR/thermal regression manifest completed and wrote
  `/private/tmp/regression_main_packet120_confirm/suite_report.{json,md}`.
- Color regression manifest completed and wrote
  `/private/tmp/regression_color_packet120_confirm/suite_report.{json,md}`.
- Visible-color perf benchmark completed and wrote
  `/private/tmp/visible_color_perf_packet120_confirm/visible_color_perf_report.json`.
  Fresh app-like host timing averaged `0.329x` realtime versus `0.316x` in the
  local prior artifact. Fresh dense-gold timing averaged `0.050x` realtime
  versus `0.052x` in the local prior artifact.
- Registration perf benchmark completed and wrote summaries under
  `/private/tmp/registration_perf_packet120_confirm`; all four cases were
  faster than the May 25 local perf artifact.

## Packet 121 - Result Color Target Matched-Candidate Publish Helper

Status: parent-validated.

Mode: result-builder Color target matched-candidate detail publication
extraction, behavior-preserving, after Packet 120.

Scope:

- Added `anomaly_result_color_debug_target_matched_candidate_publication_t`.
- Added `anomaly_result_publish_color_debug_target_matched_candidate(...)`.
- Replaced only the Color debug target matched-candidate enrichment island in
  `anomaly_analysis.c`.
- Kept the Color candidate array publication loop in `anomaly_analysis.c` for
  a later packet.
- Added focused native tests for null input no-op behavior, invalid
  publication no-op behavior, score/position/bbox publication, invalid-bounds
  bbox zeroing through the existing bbox helper, and additive preservation of
  candidate-index fields, winner-gate/stage fields, component bbox fields,
  Color summary fields, candidate slots, and unrelated result fields.

Behavior preserved:

- Matched-candidate detail is still published only after
  `color_target_matched_candidate_idx` is proven in range.
- Published score still comes from `color_candidate_final_score[ci]`.
- Published x/y norms still divide `color_candidates[ci].pixel_x/y` by the
  current frame width/height.
- Matched bbox normalization still delegates to
  `anomaly_color_candidate_bbox_norm(...)` with the same ROI origin,
  sample-step, candidate bounds, and frame dimensions.
- A `valid=false` DTO is an explicit no-op; the live call site only sends a
  valid DTO from the existing in-range branch.
- Color candidate arrays, scoring, support-map construction, candidate
  generation/order/selection, target tracking lifecycle, scan planning, timing
  finalization, threading, overlay drawing, thermal/saliency publication, and
  public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the Color target matched-candidate publish helper was not yet
  defined.

Parent validation:

- Pauli sidecar review independently recommended this same matched-candidate
  seam and leaving the Color candidate array loop for Packet 122.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3036 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  matched-candidate debug-result publication only. It does not alter candidate
  arrays, scoring, support-map construction, candidate generation/order/
  selection, target tracking lifecycle, scan planning, timing, threading,
  overlay drawing, thermal/saliency publication, or public API behavior.

## Packet 122 - Result Color Candidate Array Publish Helper

Status: parent-validated.

Mode: result-builder Color debug candidate-array publication extraction,
behavior-preserving, after Packet 121.

Scope:

- Added `anomaly_result_color_debug_candidate_publication_t`.
- Added `anomaly_result_color_debug_candidates_publication_t`.
- Added `anomaly_result_publish_color_debug_candidates(...)`.
- Replaced only the loop that publishes
  `result_out->color_debug.candidates[i]` in `anomaly_analysis.c`.
- Kept target matched-candidate publication, target-index sample construction,
  candidate scoring/selection, support maps, and upstream candidate arrays in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, invalid candidate
  list no-op behavior, score/position/bbox/diagnostic field publication,
  truncation at `ANOMALY_DEBUG_TOP_COLOR_CANDIDATES`, preservation of Color
  summary candidate count, and additive preservation of target fields,
  matched-candidate fields, winner-gate/stage fields, Color summary fields,
  and unrelated result fields.

Behavior preserved:

- Candidate debug slots are still published in the existing candidate order.
- Publication still truncates at `ANOMALY_DEBUG_TOP_COLOR_CANDIDATES`.
- Each published slot still sets `valid = true`.
- x/y normalization still divides candidate pixel position by current frame
  width/height.
- Candidate bbox normalization still delegates to
  `anomaly_color_candidate_bbox_norm(...)` with the same ROI origin,
  sample-step, candidate bounds, and frame dimensions.
- `temporal_score` still publishes the legacy `-1.0f` sentinel.
- Color summary `candidate_count` remains owned by
  `anomaly_result_publish_color_debug_summary(...)`; this helper does not
  change it.
- Candidate scoring, support-map construction, candidate generation/order/
  selection, target tracking lifecycle, scan planning, timing finalization,
  threading, overlay drawing, thermal/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_color_debug_candidates` was not yet
  defined.

Parent validation:

- Jason sidecar review independently recommended this exact candidate-array
  publication island and the same replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3072 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  Color debug candidate-slot publication only. It does not alter candidate
  count semantics, scoring, support-map construction, candidate generation/
  order/selection, target tracking lifecycle, scan planning, timing,
  threading, overlay drawing, thermal/saliency publication, or public API
  behavior.

## Packet 123 - Result Thermal Target Base Publish Helper

Status: parent-validated.

Mode: result-builder thermal target base/local-evidence publication
extraction, behavior-preserving, after Packet 122.

Scope:

- Added `anomaly_result_thermal_debug_target_base_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_base(...)`.
- Replaced only the thermal target reset plus direct base/local-evidence field
  publication immediately after thermal summary publication in
  `anomaly_analysis.c`.
- Stopped before `micro_candidate_would_create`; micro-candidate lifecycle,
  suppressor, component, rejected component, NMS/cap ranks, provisional
  selection, movement shadow/rescue, track match, and stage fields remain in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, target reset
  behavior, base target field copying, local peak/raw-local-peak/local-window
  field copying, and preservation of thermal summary, thermal candidates,
  Color debug, and saliency debug fields.

Behavior preserved:

- `thermal_debug.target` is still zeroed before base target publication.
- Target enabled/valid/inside-scan-zone, pixel/sample/norm, target score/delta,
  target raw score/delta, temporal margin, and spatial fields are copied
  without reinterpretation.
- Hot/local flags, local peak fields, raw local peak fields, and local-window
  evidence fields are copied without normalization or policy changes.
- Micro-candidate lifecycle, suppressor, component, rejected-component probe,
  NMS/cap ranks, provisional selection, movement shadow/rescue, track match,
  and stage publication remain in the producer path after the helper call.
- Thermal scoring, candidate extraction, NMS/cap/provisional selection,
  movement rescue/shadow logic, track matching, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_thermal_debug_target_base` was not
  yet defined.

Parent validation:

- Franklin sidecar review independently confirmed this exact base/local
  evidence seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3109 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  thermal target debug-result publication only. It does not alter thermal
  scoring, candidate extraction, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 124 - Result Thermal Target Micro-Candidate Publish Helper

Status: parent-validated.

Mode: result-builder thermal target micro-candidate debug publication
extraction, behavior-preserving, after Packet 123.

Scope:

- Added
  `anomaly_result_thermal_debug_target_micro_candidate_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_micro_candidate(...)`.
- Replaced only the direct thermal target `micro_candidate_*` debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_base(...)` in
  `anomaly_analysis.c`.
- Stopped before suppressor publication; suppressor, component,
  rejected-component probe, NMS/cap ranks, provisional selection, movement
  shadow/rescue, track match, and stage fields remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact
  micro-candidate field copying, and preservation of base target fields,
  later thermal target lifecycle fields, thermal summary/candidates, and Color
  debug fields.

Behavior preserved:

- Micro-candidate would-create/reject reason, peak sample/delta/score,
  prominence, ring evidence, hot/sample counts, compactness, centroid
  evidence, one-sided support, and debug-target distance are copied without
  reinterpretation.
- Base target publication still owns the target reset and base/local evidence.
- Suppressor, component, rejected-component probe, NMS/cap ranks, provisional
  selection, movement shadow/rescue, track match, and stage publication remain
  in the producer path after the helper call.
- Thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression, component extraction, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_micro_candidate` was not yet
  defined.

Parent validation:

- Nietzsche sidecar review independently confirmed this exact
  micro-candidate debug-publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3130 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Performance benchmarks:

- `python3 tools/anomaly_test/run_visible_color_perf_benchmarks.py --binary
  tools/anomaly_test/build_timing/anomaly_video_test --output-dir
  /private/tmp/rid2c_packet124_visible_color_perf`: passed.
- Visible-color aggregate:
  `/private/tmp/rid2c_packet124_visible_color_perf/visible_color_perf_report.json`.
- Visible-color app-like auto average: `0.26x` realtime.
- Visible-color dense-gold average: `0.05x` realtime.
- `python3 tools/anomaly_test/run_registration_perf_benchmarks.py --binary
  tools/anomaly_test/build_timing/anomaly_video_test --output-dir
  /private/tmp/rid2c_packet124_registration_perf`: passed.
- Registration perf cases: PowerHouseTeam affine scan-zone 0.80 at `0.90x`,
  PowerHouseTeam affine scan-zone 0.60 at `1.22x`, PowerHouse1 affine
  scan-zone 0.80 at `0.58x`, and PowerHouse1 opening affine scan-zone 0.60 at
  `0.66x`.

Replay manifest rationale:

- Full replay manifests were not rerun for this packet; the change extracts
  thermal target micro-candidate debug-result publication only. It does not
  alter thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression, component extraction, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 125 - Result Thermal Target Suppressor Publish Helper

Status: parent-validated.

Mode: result-builder thermal target suppressor debug publication extraction,
behavior-preserving, after Packet 124.

Scope:

- Added `anomaly_result_thermal_debug_target_suppressor_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_suppressor(...)`.
- Replaced only the direct thermal target suppressor debug-field publication
  immediately after
  `anomaly_result_publish_thermal_debug_target_micro_candidate(...)` in
  `anomaly_analysis.c`.
- Stopped before `component_seed_x`; component/rejection, nearby rejected
  component, NMS/cap ranks, provisional selection, movement shadow/rescue,
  track match, stage fields, and thermal candidate-array publication remain in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact suppressor
  field copying, and preservation of earlier base/micro fields plus later
  component/rejection, nearby rejected component, NMS, movement, stage,
  thermal summary, and Color debug fields.

Behavior preserved:

- `suppressor_sample_x`, `suppressor_sample_y`, `suppressor_delta`, and
  `suppressor_score` are copied without reinterpretation.
- Base target and micro-candidate publication still own their prior fields.
- Component/rejection, nearby rejected component, NMS/cap ranks, provisional
  selection, movement shadow/rescue, track match, stage publication, and
  thermal candidate-array publication remain in the producer path after the
  helper call.
- Thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression decisions, component extraction/rejection, NMS/cap/provisional
  selection, movement rescue/shadow logic, track matching, scan planning,
  timing finalization, threading, overlay drawing, Color/saliency publication,
  and public API behavior are unchanged.

Parent validation:

- Jason sidecar review independently recommended the suppressor-only island
  and warned to avoid `component_seed_x` and later lifecycle fields; parent
  narrowed the packet to that seam before validation.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3139 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts suppressor debug-result publication only. It
  does not alter thermal scoring, candidate extraction, micro-candidate
  evaluation, suppression decisions, component extraction/rejection,
  NMS/cap/provisional selection, movement rescue/shadow logic, track matching,
  scan planning, timing, threading, overlay drawing, Color/saliency
  publication, or public API behavior.

## Packet 126 - Result Thermal Target Component Trace Publish Helper

Status: parent-validated.

Mode: result-builder thermal target component/rejection debug publication
extraction, behavior-preserving, after Packet 125.

Scope:

- Added
  `anomaly_result_thermal_debug_target_component_trace_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_component_trace(...)`.
- Replaced only the direct thermal target component/rejection debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_suppressor(...)` in
  `anomaly_analysis.c`.
- Stopped before `nearby_rejected_component_valid`; nearby rejected component,
  NMS/cap ranks, provisional selection, movement shadow/rescue, track match,
  stage fields, and thermal candidate-array publication remain in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact
  component/rejection field copying, and preservation of earlier base/micro/
  suppressor fields plus later nearby rejected component, NMS, movement,
  stage, thermal summary, and Color debug fields.

Behavior preserved:

- Component seed/peak samples, area/span/fill, peak/mean delta, quality,
  `component_rejected`, and `rejection_gate` are copied without
  reinterpretation.
- Base target, micro-candidate, and suppressor publication still own their
  prior fields.
- Nearby rejected component, NMS/cap ranks, provisional selection, movement
  shadow/rescue, track match, stage publication, and thermal candidate-array
  publication remain in the producer path after the helper call.
- Thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression decisions, component extraction/rejection decisions, nearby
  rejected-component selection, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_component_trace` was not yet
  defined.

Parent validation:

- Jason sidecar review independently confirmed the component/rejection
  publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3153 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts component/rejection debug-result publication
  only. It does not alter thermal scoring, candidate extraction,
  micro-candidate evaluation, suppression decisions, component
  extraction/rejection decisions, nearby rejected-component selection,
  NMS/cap/provisional selection, movement rescue/shadow logic, track matching,
  scan planning, timing, threading, overlay drawing, Color/saliency
  publication, or public API behavior.

## Packet 127 - Result Thermal Target Nearby Rejected Component Publish Helper

Status: parent-validated.

Mode: result-builder thermal target nearby rejected-component debug
publication extraction, behavior-preserving, after Packet 126.

Scope:

- Added
  `anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_nearby_rejected_component(...)`.
- Replaced only the direct thermal target nearby rejected-component debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_component_trace(...)` in
  `anomaly_analysis.c`.
- Stopped before `dropped_by_cap`; NMS/cap ranks, provisional selection,
  movement shadow/rescue, track match, stage fields, and thermal
  candidate-array publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact nearby
  rejected-component field copying, and preservation of earlier base/micro/
  suppressor/component fields plus later NMS, provisional, movement, track,
  stage, thermal summary, and Color debug fields.

Behavior preserved:

- Nearby rejected-component valid/contains-target/gate, seed/peak samples,
  area/span/fill, peak/mean delta, quality, and distance are copied without
  reinterpretation.
- Base target, micro-candidate, suppressor, and component/rejection
  publication still own their prior fields.
- NMS/cap ranks, provisional selection, movement shadow/rescue, track match,
  stage publication, and thermal candidate-array publication remain in the
  producer path after the helper call.
- Thermal scoring, candidate extraction, micro-candidate evaluation,
  suppression decisions, component extraction/rejection decisions, nearby
  rejected-component selection, NMS/cap/provisional selection, movement
  rescue/shadow logic, track matching, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_nearby_rejected_component` was
  not yet defined.

Parent validation:

- Jason sidecar review independently confirmed the nearby rejected-component
  publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3168 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts nearby rejected-component debug-result
  publication only. It does not alter thermal scoring, candidate extraction,
  micro-candidate evaluation, suppression decisions, component
  extraction/rejection decisions, nearby rejected-component selection,
  NMS/cap/provisional selection, movement rescue/shadow logic, track matching,
  scan planning, timing, threading, overlay drawing, Color/saliency
  publication, or public API behavior.

## Packet 128 - Result Thermal Target NMS/Cap Publish Helper

Status: parent-validated.

Mode: result-builder thermal target NMS/cap debug publication extraction,
behavior-preserving, after Packet 127.

Scope:

- Added `anomaly_result_thermal_debug_target_nms_cap_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_nms_cap(...)`.
- Replaced only the direct thermal target NMS/cap/rank debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_nearby_rejected_component(...)`
  in `anomaly_analysis.c`.
- Stopped before `provisional_candidate_index`; provisional selection,
  movement shadow/rescue, track match, stage fields, and thermal
  candidate-array publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact NMS/cap/rank
  field copying, and preservation of earlier base/micro/suppressor/component/
  nearby fields plus later provisional, movement, track, stage, thermal
  summary, and Color debug fields.

Behavior preserved:

- Dropped-by-cap/NMS flags, NMS replacement flag, conflict rank/sample,
  pre-cap rank/count/limit/retention rank, extracted rank, and winning rank
  are copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, and nearby
  rejected-component publication still own their prior fields.
- Provisional selection, movement shadow/rescue, track match, stage
  publication, and thermal candidate-array publication remain in the producer
  path after the helper call.
- Thermal scoring, candidate extraction/order, micro-candidate evaluation,
  suppression decisions, component extraction/rejection decisions, nearby
  rejected-component selection, NMS/cap decisions, provisional selection,
  movement rescue/shadow logic, track matching, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_thermal_debug_target_nms_cap` was not
  yet defined.

Parent validation:

- Jason sidecar review independently confirmed the NMS/cap publication seam
  and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3179 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts NMS/cap debug-result publication only. It does
  not alter thermal scoring, candidate extraction/order, micro-candidate
  evaluation, suppression decisions, component extraction/rejection decisions,
  nearby rejected-component selection, NMS/cap decisions, provisional
  selection, movement rescue/shadow logic, track matching, scan planning,
  timing, threading, overlay drawing, Color/saliency publication, or public
  API behavior.

## Packet 129 - Result Thermal Target Provisional Publish Helper

Status: parent-validated.

Mode: result-builder thermal target provisional selection debug publication
extraction, behavior-preserving, after Packet 128.

Scope:

- Added `anomaly_result_thermal_debug_target_provisional_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_provisional(...)`.
- Replaced only the direct thermal target provisional selection debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_nms_cap(...)` in
  `anomaly_analysis.c`.
- Stopped before `raw_delta_rescue_score`; raw-delta rescue, movement
  shadow/rescue, track match, stage fields, and thermal candidate-array
  publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact provisional
  field copying, and preservation of earlier target publication fields plus
  later raw-delta rescue, movement, track, stage, thermal summary, and Color
  debug fields.

Behavior preserved:

- Candidate index, score floor, final score, score/shape eligibility,
  candidate/selected rank, selected score, and near-existing skip are copied
  without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, and NMS/cap publication still own their prior fields.
- Raw-delta rescue, movement shadow/rescue, track match, stage publication,
  and thermal candidate-array publication remain in the producer path after
  the helper call.
- Thermal scoring, candidate extraction/order, NMS/cap decisions, provisional
  selection logic, raw-delta rescue, movement rescue/shadow logic, track
  matching, scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_thermal_debug_target_provisional`
  was not yet defined.

Parent validation:

- Jason sidecar review independently confirmed the provisional publication
  seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3193 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts provisional debug-result publication only. It
  does not alter thermal scoring, candidate extraction/order, NMS/cap
  decisions, provisional selection logic, raw-delta rescue, movement
  rescue/shadow logic, track matching, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 130 - Result Thermal Target Raw Delta Rescue Publish Helper

Status: parent-validated.

Mode: result-builder thermal target raw-delta rescue debug publication
extraction, behavior-preserving, after Packet 129.

Scope:

- Added `anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_raw_delta_rescue(...)`.
- Replaced only the direct thermal target `raw_delta_rescue_score` debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_provisional(...)` in
  `anomaly_analysis.c`.
- Stopped before `movement_residual_px`; movement diagnostics, movement
  rescue/shadow flags, track match, stage fields, and thermal candidate-array
  publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact
  `raw_delta_rescue_score` copying, and preservation of earlier target
  publication fields plus later movement, track, stage, thermal summary, and
  Color debug fields.

Behavior preserved:

- `raw_delta_rescue_score` is copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, and provisional publication still own their
  prior fields.
- Movement diagnostics, movement rescue/shadow flags, track match, stage
  publication, and thermal candidate-array publication remain in the producer
  path after the helper call.
- Thermal scoring, candidate extraction/order, provisional selection logic,
  rescue eligibility, movement rescue/shadow logic, track matching, scan
  planning, timing finalization, threading, overlay drawing, Color/saliency
  publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_raw_delta_rescue` was not yet
  defined.

Parent validation:

- Jason sidecar review independently confirmed the one-field raw-delta rescue
  publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3200 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts a single raw-delta rescue debug-result scalar
  only. It does not alter thermal scoring, candidate extraction/order,
  provisional selection logic, rescue eligibility, movement rescue/shadow
  logic, track matching, scan planning, timing, threading, overlay drawing,
  Color/saliency publication, or public API behavior.

## Packet 131 - Result Thermal Target Movement Diagnostics Publish Helper

Status: parent-validated.

Mode: result-builder thermal target movement diagnostics debug publication
extraction, behavior-preserving, after Packet 130.

Scope:

- Added
  `anomaly_result_thermal_debug_target_movement_diagnostics_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_movement_diagnostics(...)`.
- Replaced only the direct target movement diagnostics debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_raw_delta_rescue(...)` in
  `anomaly_analysis.c`.
- Stopped before `local_peak_movement_residual_px`; local-peak movement
  diagnostics, rescue eligibility/flags, movement shadow/rescue flags, track
  match, stage fields, and thermal candidate-array publication remain in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact five-field
  movement diagnostics copying, and preservation of earlier target fields plus
  later local-peak movement, rescue flags, movement shadow, track, stage,
  thermal summary, and Color debug fields.

Behavior preserved:

- Target movement residual px, independent score, confidence, motion support,
  and movement layer class are copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, and raw-delta rescue publication
  still own their prior fields.
- Local-peak movement diagnostics, rescue eligibility/flags, movement
  shadow/rescue flags, track match, stage publication, and thermal
  candidate-array publication remain in the producer path after the helper
  call.
- Movement scoring, rescue eligibility, movement rescue/shadow decisions,
  track matching, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_movement_diagnostics` was not
  yet defined.

Parent validation:

- Jason sidecar review independently confirmed the target movement diagnostics
  publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3212 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts target movement diagnostics debug-result
  publication only. It does not alter movement scoring, rescue eligibility,
  movement rescue/shadow decisions, track matching, scan planning, timing,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior.

## Packet 132 - Result Thermal Target Local-Peak Movement Publish Helper

Status: parent-validated.

Mode: result-builder thermal target local-peak movement diagnostics debug
publication extraction, behavior-preserving, after Packet 131.

Scope:

- Added
  `anomaly_result_thermal_debug_target_local_peak_movement_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_local_peak_movement(...)`.
- Replaced only the direct local-peak movement diagnostics debug-field
  publication immediately after
  `anomaly_result_publish_thermal_debug_target_movement_diagnostics(...)` in
  `anomaly_analysis.c`.
- Stopped before `raw_delta_rescue_eligible`; rescue eligibility/flags,
  movement shadow/rescue flags, track match, stage fields, and thermal
  candidate-array publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact five-field
  local-peak movement copying, and preservation of earlier target fields plus
  later rescue flags, movement shadow, track, stage, thermal summary, and
  Color debug fields.

Behavior preserved:

- Local-peak movement residual px, independent score, confidence, motion
  support, and movement layer class are copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, raw-delta rescue, and target
  movement diagnostics publication still own their prior fields.
- Rescue eligibility/flags, movement shadow/rescue flags, track match, stage
  publication, and thermal candidate-array publication remain in the producer
  path after the helper call.
- Movement scoring, rescue eligibility, movement rescue/shadow decisions,
  track matching, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_local_peak_movement` was not
  yet defined.

Parent validation:

- Jason sidecar review independently confirmed the local-peak movement
  diagnostics publication seam and replay-skip rationale.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3223 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet; the change extracts local-peak movement diagnostics debug-result
  publication only. It does not alter movement scoring, rescue eligibility,
  movement rescue/shadow decisions, track matching, scan planning, timing,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior.

## Packet 133 - Result Thermal Target Rescue Movement Flags Publish Helper

Status: parent-validated.

Mode: result-builder thermal target rescue/movement debug-flag publication
extraction, behavior-preserving, after Packet 132.

Scope:

- Added
  `anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_rescue_movement_flags(...)`.
- Replaced only the direct rescue/movement debug-flag publication immediately
  after
  `anomaly_result_publish_thermal_debug_target_local_peak_movement(...)` in
  `anomaly_analysis.c`.
- Published exactly eight already-computed booleans:
  `raw_delta_rescue_eligible`, `movement_tile_valid`,
  `movement_independent`, `movement_parallax`,
  `would_promote_movement_rescue`,
  `local_peak_movement_tile_valid`,
  `local_peak_movement_independent`, and
  `local_peak_movement_parallax`.
- Stopped before `movement_shadow_motion_support`; movement shadow/rescue
  publish fields, movement rescue reject reason, track match, stage fields,
  and thermal candidate-array publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact eight-flag
  copying, and preservation of earlier target fields plus later movement
  shadow, rescue publish, track, stage, thermal summary, and Color debug
  fields.

Behavior preserved:

- Rescue/movement debug booleans are copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, raw-delta rescue, target movement
  diagnostics, and local-peak movement diagnostics publication still own their
  prior fields.
- Movement shadow/rescue publish fields, movement rescue reject reason, track
  match, stage publication, and thermal candidate-array publication remain in
  the producer path after the helper call.
- Rescue eligibility computation, movement scoring, movement shadow/rescue
  decisions, track matching, scan planning, timing finalization, threading,
  overlay drawing, Color/saliency publication, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_rescue_movement_flags` was not
  yet defined.

Parent validation:

- Jason sidecar review independently confirmed the rescue/movement flag
  publication seam and warned to stop before movement shadow fields.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3231 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Pause-point replay and performance validation:

- IR regression manifest:
  `/private/tmp/ir_regression_packet133_pause/suite_report.md`; command
  exited successfully.
- IR redesigned incremental profile: precision `0.994`, recall `0.599`,
  track hits `4/4`, aggregate realtime `1.437x`.
- IR dense full-scan gold profile: precision `1.000`, recall `0.362`,
  track hits `4/4`, aggregate realtime `0.581x`.
- Visible-color performance report:
  `/private/tmp/visible_color_perf_packet133_pause/visible_color_perf_report.json`.
- Visible-color app-like auto: average realtime `0.33x`, total `97.36 ms`,
  color scoring `44.31 ms`, sampled-grid prep `23.20 ms`.
- Visible-color dense-gold: average realtime `0.05x`, total `659.14 ms`,
  color scoring `367.30 ms`, sampled-grid prep `193.49 ms`.
- Registration performance output:
  `/private/tmp/registration_perf_packet133_pause`.
- Registration fixed cases reported `1.23x`, `1.60x`, `0.74x`, and `0.88x`
  realtime.

Replay/perf conclusion:

- The refreshed replay and perf gates are green for this pause point. The
  visible-color dense path remains slow in the known dense-gold class, while
  app-like Color and registration numbers stay in the expected host/noisy
  envelope. No Packet 133-specific behavior or throughput regression was
  observed.

## Packet 134 - Result Thermal Target Movement Shadow Rescue Publish Helper

Status: parent-validated.

Mode: result-builder thermal target movement shadow/rescue debug-result
publication extraction, behavior-preserving, after Packet 133.

Scope:

- Added
  `anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(...)`.
- Replaced only the direct movement shadow/rescue debug-field publication
  immediately after
  `anomaly_result_publish_thermal_debug_target_rescue_movement_flags(...)` in
  `anomaly_analysis.c`.
- Published exactly seven already-computed fields:
  `movement_shadow_motion_support`,
  `movement_shadow_parallax_penalty`,
  `movement_shadow_thermal_support`, `movement_shadow_clutter_veto`,
  `movement_rescue_would_publish`, `movement_boost_would_publish`, and
  `movement_rescue_reject_reason`.
- Stopped before `matched_track_index`; matched-track fields, stage
  publication, and thermal candidate-array publication remain in
  `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact seven-field
  copying including the reject enum, and preservation of earlier Packet 133
  fields plus later track, stage, thermal summary, and Color debug fields.

Behavior preserved:

- Movement shadow/rescue debug-result fields are copied without
  reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, raw-delta rescue, target movement
  diagnostics, local-peak movement diagnostics, and rescue/movement flag
  publication still own their prior fields.
- Matched-track fields, stage publication, and thermal candidate-array
  publication remain in the producer path after the helper call.
- Rescue eligibility computation, movement scoring, movement shadow/rescue
  decisions, track matching, scan planning, timing finalization, threading,
  overlay drawing, Color/saliency publication, and public API behavior are
  unchanged.

TDD red check:

- The first red build exposed a test enum spelling mistake; the fixture now
  uses the existing `ANOMALY_MOVEMENT_SHADOW_REJECT_PARALLAX` enum.
- `cmake --build tools/anomaly_test/build_timing` then failed after
  test/header wiring because
  `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue` was not
  yet defined.

Parent validation:

- Pauli sidecar review independently confirmed the movement shadow/rescue
  publication seam, recommended the same helper name, and warned to stop
  before matched-track fields.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3239 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before this helper-only packet. Packet 134
  only moves movement shadow/rescue debug-result publication plumbing and does
  not alter rescue eligibility computation, movement scoring, movement
  shadow/rescue decisions, track matching, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 135 - Result Thermal Target Track Match Publish Helper

Status: parent-validated.

Mode: result-builder thermal target matched-track debug-result publication
extraction, behavior-preserving, after Packet 134.

Scope:

- Added `anomaly_result_thermal_debug_target_track_match_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_track_match(...)`.
- Replaced only the direct matched-track debug-field publication immediately
  after
  `anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(...)` in
  `anomaly_analysis.c`.
- Published exactly six already-computed fields: `matched_track_index`,
  `matched_track_id`, `matched_track_hit_count`,
  `matched_track_miss_count`, `matched_track_hold_count`, and
  `matched_track_publish_confirmed`.
- Stopped before `stage`; stage publication and thermal candidate-array
  publication remain in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact six-field
  copying, and preservation of earlier movement shadow/rescue fields plus
  later stage, thermal candidate slots, thermal summary, and Color debug
  fields.

Behavior preserved:

- Matched-track debug-result fields are copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, raw-delta rescue, target movement
  diagnostics, local-peak movement diagnostics, rescue/movement flags, and
  movement shadow/rescue publication still own their prior fields.
- Stage publication and thermal candidate-array publication remain in the
  producer path after the helper call.
- Track matching computation, stage lifecycle updates, candidate
  extraction/order, rescue/shadow logic, scan planning, timing finalization,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_target_track_match` was not yet
  defined.

Parent validation:

- Leibniz sidecar review independently confirmed the matched-track publication
  seam, recommended the same helper name, and warned to stop before `stage` and
  the thermal candidate array.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3247 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before these helper-only packets. Packet 135
  only moves matched-track debug-result publication plumbing and does not alter
  track matching computation, stage lifecycle updates, candidate
  extraction/order, rescue/shadow logic, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 136 - Result Thermal Target Stage Publish Helper

Status: parent-validated.

Mode: result-builder thermal target stage debug-result publication
extraction, behavior-preserving, after Packet 135.

Scope:

- Added `anomaly_result_thermal_debug_target_stage_publication_t`.
- Added `anomaly_result_publish_thermal_debug_target_stage(...)`.
- Replaced only the direct `thermal_debug.target.stage` publication
  immediately after
  `anomaly_result_publish_thermal_debug_target_track_match(...)` in
  `anomaly_analysis.c`.
- Published exactly one already-computed field: `stage`.
- Stopped before the thermal candidate-array publication loop; candidate
  publication remains in `anomaly_analysis.c`.
- Added focused native tests for null input no-op behavior, exact stage copy,
  and preservation of earlier matched-track fields plus later candidate slots,
  thermal summary, and Color debug fields.

Behavior preserved:

- Stage debug-result publication is copied without reinterpretation.
- Base target, micro-candidate, suppressor, component/rejection, nearby
  rejected-component, NMS/cap, provisional, raw-delta rescue, target movement
  diagnostics, local-peak movement diagnostics, rescue/movement flags,
  movement shadow/rescue, and matched-track publication still own their prior
  fields.
- Thermal candidate-array publication remains in the producer path after the
  helper call.
- Stage lifecycle computation, candidate publication, candidate
  extraction/order, track matching, rescue/shadow logic, scan planning, timing
  finalization, threading, overlay drawing, Color/saliency publication, and
  public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_thermal_debug_target_stage` was not
  yet defined.

Parent validation:

- Confucius sidecar review independently confirmed the stage publication seam,
  recommended the same helper name, and warned to stop before the thermal
  candidate array.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3254 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 133 refreshed the pause-point IR, visible-color, and
  registration gates immediately before these helper-only packets. Packet 136
  only moves stage debug-result publication plumbing and does not alter stage
  lifecycle computation, candidate publication, candidate extraction/order,
  track matching, rescue/shadow logic, scan planning, timing, threading,
  overlay drawing, Color/saliency publication, or public API behavior.

## Packet 137 - Result Thermal Candidate Base Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate base debug-result publication
extraction, behavior-preserving, after Packet 136.

Scope:

- Added `anomaly_result_thermal_debug_candidate_base_publication_t`.
- Added `anomaly_result_thermal_debug_candidates_base_publication_t`.
- Added `anomaly_result_publish_thermal_debug_candidates_base(...)`.
- Replaced only the base thermal candidate publication slice immediately after
  `anomaly_result_publish_thermal_debug_target_stage(...)` in
  `anomaly_analysis.c`.
- Published base fields and normalization/defaults only: `valid`, pixel
  coordinates, x/y norms, bbox norms, base/final/temporal scores,
  area/span/fill/center/quality/isolation fields, scale fields, rank fields,
  patch/motion support, singleton score scale, retention rank, plus legacy
  defaults `movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN` and
  nearest-track index/id/hit count `-1`.
- Stopped before movement snapshot lookup, nearest-track lookup,
  near-debug-target, raw-delta rescue score/eligibility/promotion,
  `singleton_blob`, and `above_threshold`, which remain in `anomaly_analysis.c`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  capped publication with bbox normalization and base/default fields, and
  additive-only preservation of thermal summary, target fields, candidate
  count, and Color debug fields.

Behavior preserved:

- Base thermal candidate fields are copied without reinterpretation.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- The helper performs no allocation and does not mutate thermal summary
  `candidate_count`.
- Movement/track/target/rescue enrichment remains in the producer path after
  the helper call.
- Candidate ordering, scoring, extraction, movement snapshot lookup,
  nearest-track lookup, near-target flags, raw-delta rescue scoring, scan
  planning, timing finalization, threading, overlay drawing, Color/saliency
  publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_thermal_debug_candidates_base` was not
  yet defined.

Parent validation:

- Fermat sidecar review independently recommended splitting the candidate loop
  and confirmed the Packet 137 base-publication-only boundary.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3278 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 137 only moves base thermal candidate debug-result
  publication plumbing and does not alter candidate ordering, scoring,
  extraction, allocation behavior, movement snapshot lookup, nearest-track
  lookup, near-target flags, raw-delta rescue scoring, scan planning, timing,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior.

## Packet 138 - Result Thermal Candidate Movement Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate movement debug-result publication
extraction, behavior-preserving, after Packet 137.

Scope:

- Added `anomaly_result_thermal_debug_candidate_movement_publication_t`.
- Added `anomaly_result_thermal_debug_candidates_movement_publication_t`.
- Added `anomaly_result_publish_thermal_debug_candidates_movement(...)`.
- Replaced only per-candidate movement debug-field publication after
  `anomaly_result_publish_thermal_debug_candidates_base(...)` in
  `anomaly_analysis.c`.
- Kept movement snapshot query and movement tile interpretation in
  `anomaly_analysis.c`.
- Published exactly seven movement fields for entries with a valid movement
  tile: `movement_tile_valid`, `movement_residual_px`,
  `movement_independent_score`, `movement_confidence`,
  `movement_layer_class`, `movement_independent`, and `movement_parallax`.
- Stopped before nearest-track lookup, near-tracked-target, near-debug-target,
  raw-delta rescue score/eligibility/promotion, `singleton_blob`, and
  `above_threshold`, which remain in `anomaly_analysis.c`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  capped movement publication, invalid-entry preservation, and additive-only
  preservation of base candidate fields, nearest-track defaults, raw-delta
  fields, singleton/threshold fields, thermal summary, target fields, and
  Color debug fields.

Behavior preserved:

- Movement debug-result fields are copied without reinterpretation.
- Invalid/no-tile entries preserve existing candidate fields, matching the
  legacy `if (movement query succeeds)` behavior.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- Movement query/scoring, nearest-track lookup, near-target flags, raw-delta
  rescue scoring, singleton/threshold fields, candidate ordering/extraction,
  scan planning, timing finalization, threading, overlay drawing,
  Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- The first red build exposed a test enum spelling mistake; the fixture now
  uses the existing `ANOMALY_MOVEMENT_LAYER_UNSTABLE` enum for an ignored
  invalid source entry.
- `cmake --build tools/anomaly_test/build_timing` then failed after
  test/header wiring because
  `anomaly_result_publish_thermal_debug_candidates_movement` was not yet
  defined.

Parent validation:

- Wegener sidecar review independently confirmed the candidate movement
  publication seam and warned to stop before nearest-track enrichment.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3293 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 138 only moves movement debug-result publication plumbing and
  does not alter movement query/scoring, estimator logic, candidate ordering or
  extraction, nearest-track lookup, near-target flags, raw-delta rescue
  scoring, scan planning, timing, threading, overlay drawing, Color/saliency
  publication, or public API behavior.

## Packet 139 - Result Thermal Candidate Nearest Track Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate nearest-track debug-result publication
extraction, behavior-preserving, after Packet 138.

Scope:

- Added `anomaly_result_thermal_debug_candidate_nearest_track_publication_t`.
- Added `anomaly_result_thermal_debug_candidates_nearest_track_publication_t`.
- Added `anomaly_result_publish_thermal_debug_candidates_nearest_track(...)`.
- Replaced only per-candidate nearest-track debug-field publication after
  `anomaly_result_publish_thermal_debug_candidates_movement(...)` in
  `anomaly_analysis.c`.
- Kept nearest-track search over `state->target_tracks` and near-track gate
  calculation in `anomaly_analysis.c`.
- Published exactly five fields for entries with a nearest track:
  `nearest_track_distance`, `nearest_track_index`, `nearest_track_id`,
  `nearest_track_hit_count`, and `near_tracked_target`.
- Stopped before `near_debug_target`, raw-delta rescue score/eligibility/
  promotion, `singleton_blob`, and `above_threshold`, which remain in
  `anomaly_analysis.c`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  capped nearest-track publication, invalid-entry preservation, and
  additive-only preservation of base fields, movement fields, later
  near-debug/rescue/singleton/threshold fields, thermal summary, target fields,
  and Color debug fields.

Behavior preserved:

- Nearest-track debug-result fields are copied without reinterpretation.
- Invalid/no-track entries preserve existing candidate fields, matching the
  legacy `if (nearest_track_index >= 0)` behavior.
- Base publication still initializes nearest-track index/id/hit-count to `-1`.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- Nearest-track search, near-debug-target, raw-delta rescue scoring,
  singleton/threshold fields, candidate ordering/extraction, movement
  query/scoring, scan planning, timing finalization, threading, overlay
  drawing, Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_candidates_nearest_track` was not yet
  defined.

Parent validation:

- Mencius sidecar review independently confirmed the candidate nearest-track
  publication seam and warned to stop before `near_debug_target`.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3307 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 139 only moves nearest-track debug-result publication
  plumbing and does not alter nearest-track search, near-debug-target,
  raw-delta rescue scoring, candidate ordering or extraction, movement
  query/scoring, scan planning, timing, threading, overlay drawing,
  Color/saliency publication, or public API behavior.

## Packet 140 - Result Thermal Candidate Near Debug Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate near-debug-target debug-result
publication extraction, behavior-preserving, after Packet 139.

Scope:

- Added `anomaly_result_thermal_debug_candidate_near_debug_publication_t`.
- Added `anomaly_result_thermal_debug_candidates_near_debug_publication_t`.
- Added `anomaly_result_publish_thermal_debug_candidates_near_debug(...)`.
- Replaced only per-candidate `near_debug_target` debug-field publication
  after
  `anomaly_result_publish_thermal_debug_candidates_nearest_track(...)` in
  `anomaly_analysis.c`.
- Kept the target enabled/valid decision, bbox containment test, dx/dy
  distance calculation, and match-gate comparison in `anomaly_analysis.c`.
- Published exactly one field, `near_debug_target`, for entries marked
  `near_debug_valid`.
- Stopped before raw-delta rescue score/eligibility/promotion,
  `singleton_blob`, and `above_threshold`, which remain in
  `anomaly_analysis.c`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  capped near-debug publication, invalid-entry preservation, and additive-only
  preservation of base fields, movement fields, nearest-track fields, later
  rescue/singleton/threshold fields, thermal summary, target fields, and Color
  debug fields.

Behavior preserved:

- Near-debug-target result fields are copied without reinterpretation.
- Invalid/no-target entries preserve existing candidate fields, matching the
  legacy `if (thermal_target_trace.enabled && thermal_target_trace.valid)`
  behavior.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- Target geometry and match-gate logic, nearest-track search, raw-delta rescue
  scoring, singleton/threshold fields, candidate ordering/extraction, movement
  query/scoring, scan planning, timing, threading, overlay drawing,
  Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_candidates_near_debug` was not yet
  defined.

Parent validation:

- Feynman sidecar review independently confirmed the candidate near-debug
  publication seam and warned to stop before raw-delta rescue and final
  singleton/threshold fields.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3319 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Pause-point replay and performance validation:

- IR regression manifest:
  `/private/tmp/ir_regression_packet140_pause/suite_report.md`.
- IR redesigned incremental profile: precision `0.994`, recall `0.599`,
  track hits `4/4`, aggregate realtime `1.223x`.
- IR dense full-scan gold profile: precision `1.000`, recall `0.362`,
  track hits `4/4`, aggregate realtime `0.482x`.
- Visible-color regression manifest:
  `/private/tmp/color_regression_packet140_pause/suite_report.md`.
- Visible-color baseline kept the current Red1 reviewed result; dense-gold
  reported precision `1.000`, recall `1.000`, track hits `1/1`, realtime
  `0.041x`.
- Clean sequential visible-color performance report:
  `/private/tmp/visible_color_perf_packet140_pause_sequential/visible_color_perf_report.json`.
- Visible-color app-like auto: average realtime `0.35x`, total `93.15 ms`,
  color scoring `42.13 ms`, sampled-grid prep `22.06 ms`.
- Visible-color dense-gold: average realtime `0.05x`, total `650.63 ms`,
  color scoring `361.98 ms`, sampled-grid prep `190.55 ms`.
- Clean sequential registration performance output:
  `/private/tmp/registration_perf_packet140_pause_sequential`.
- Registration fixed cases reported `1.40x`, `1.69x`, `0.85x`, and `1.01x`
  realtime.

Replay/perf conclusion:

- The refreshed replay and perf gates are green for this pause point. An
  initial concurrent replay/perf sweep also exited successfully, but its early
  app-like Color and registration timing was treated as host-load contaminated;
  the sequential benchmark outputs above are the timing evidence for the pause
  gate.

## Packet 141 - Result Thermal Candidate Raw Delta Rescue Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate raw-delta rescue debug-result
publication extraction, behavior-preserving, after Packet 140.

Scope:

- Added
  `anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t`.
- Added
  `anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t`.
- Added
  `anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(...)`.
- Replaced only per-candidate raw-delta rescue debug-field publication after
  `anomaly_result_publish_thermal_debug_candidates_near_debug(...)` in
  `anomaly_analysis.c`.
- Kept the raw-delta rescue score calculation, eligibility calculation, and
  `>= 0.62f` promotion decision in `anomaly_analysis.c`.
- Published exactly three fields: `raw_delta_rescue_score`,
  `raw_delta_rescue_eligible`, and `would_promote_movement_rescue`.
- Stopped before `singleton_blob` and `above_threshold`, which remain in
  `anomaly_analysis.c`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  empty-count no-op, capped rescue publication, and additive-only preservation
  of base fields, movement fields, nearest-track fields, `near_debug_target`,
  later singleton/threshold fields, thermal summary, target fields, and Color
  debug fields.

Behavior preserved:

- Raw-delta rescue result fields are copied without reinterpretation.
- There is no per-entry valid flag because the legacy loop published these
  three fields for every debug candidate entry.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- Rescue scoring inputs, rescue scoring helpers, promotion policy,
  singleton/threshold fields, candidate ordering/extraction, movement
  query/scoring, nearest-track search, near-debug logic, scan planning, timing,
  threading, overlay drawing, Color/saliency publication, and public API
  behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue` was not
  yet defined.

Parent validation:

- Godel sidecar review independently confirmed the candidate raw-delta rescue
  publication seam and warned to stop before final singleton/threshold flags.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3335 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 141 only moves raw-delta rescue debug-result publication
  plumbing and does not alter rescue scoring inputs, score/eligibility
  helpers, promotion policy, candidate ordering or extraction, movement
  query/scoring, nearest-track search, near-debug logic, singleton/threshold
  flags, scan planning, timing, threading, overlay drawing, Color/saliency
  publication, or public API behavior. Packet 140 refreshed the pause-point
  IR, visible-color, and registration replay/perf gates.

## Packet 142 - Result Thermal Candidate Final Flags Publish Helper

Status: parent-validated.

Mode: result-builder thermal candidate final-flag debug-result publication
extraction, behavior-preserving, after Packet 141.

Scope:

- Added `anomaly_result_thermal_debug_candidate_final_flags_publication_t`.
- Added `anomaly_result_thermal_debug_candidates_final_flags_publication_t`.
- Added `anomaly_result_publish_thermal_debug_candidates_final_flags(...)`.
- Replaced only per-candidate final-flag debug-field publication after
  `anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(...)` in
  `anomaly_analysis.c`.
- Kept the existing `thermal_candidate_singleton_blob_debug[]` and
  `thermal_candidate_above_threshold[]` source arrays and their computations
  in `anomaly_analysis.c`.
- Published exactly two fields: `singleton_blob` and `above_threshold`.
- Added focused native tests for null/no-op behavior, invalid list no-op,
  empty-count no-op, capped final-flag publication, and additive-only
  preservation of base fields, movement fields, nearest-track fields,
  `near_debug_target`, raw-delta rescue fields, thermal summary, target
  fields, and Color debug fields.

Behavior preserved:

- Final candidate flags are copied without reinterpretation.
- There is no per-entry valid flag because the legacy loop published these two
  fields for every debug candidate entry.
- Publication remains capped at `ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES`.
- Singleton/threshold source computations, raw-delta rescue publication,
  candidate ordering/extraction, movement query/scoring, nearest-track search,
  near-debug logic, scan planning, timing, threading, overlay drawing,
  Color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_result_publish_thermal_debug_candidates_final_flags` was not yet
  defined.

Parent validation:

- Darwin sidecar review independently confirmed the candidate final-flags
  publication seam and warned to keep the source computations and all broader
  detector policy outside the helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3348 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 142 only moves final candidate flag debug-result publication
  plumbing and does not alter singleton/threshold source computation,
  raw-delta rescue publication, candidate ordering or extraction, movement
  query/scoring, nearest-track search, near-debug logic, scan planning, timing,
  threading, overlay drawing, Color/saliency publication, or public API
  behavior. Packet 140 refreshed the pause-point IR, visible-color, and
  registration replay/perf gates.

## Packet 143 - Result Saliency Background Readiness Publish Field

Status: parent-validated.

Mode: result-builder saliency debug background-readiness publication
extraction, behavior-preserving, after Packet 142.

Scope:

- Added `bg_ready` to `anomaly_result_saliency_debug_publication_t`.
- Updated `anomaly_result_publish_saliency_debug(...)` to copy
  `debug->bg_ready` into `result_out->saliency_debug.bg_ready`.
- Removed the direct early
  `result_out->saliency_debug.bg_ready = bg_valid` write from
  `anomaly_analysis.c`.
- Added `.bg_ready = bg_valid` to the late saliency debug DTO immediately
  before `anomaly_result_publish_saliency_debug(...)`.
- Kept `bg_valid` computation, thermal temporal stats, `thermal_delta_map`,
  saliency scoring, best-persist selection, accumulator updates,
  top-candidate construction/order, support maps, boxes, timing, overlay,
  Color debug, and thermal summary plumbing in `anomaly_analysis.c`.
- Added focused native coverage that `bg_ready = true` publishes,
  `bg_ready = false` overwrites stale true, and scalar saliency fields still
  publish before the helper's existing null top-candidate early return.

Behavior preserved:

- Saliency background readiness is copied without reinterpretation.
- Existing raw score/location normalization, accumulator fields, suppression
  flag, top-candidate count, null top-candidate early return, and capped
  top-candidate copy behavior are unchanged.
- Background readiness computation, saliency scoring, top-candidate ordering,
  scan planning, timing, threading, overlay drawing, thermal/color
  publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing &&
  tools/anomaly_test/build_timing/anomaly_test` built successfully, then
  failed on the new saliency background-readiness assertion because
  `anomaly_result_publish_saliency_debug(...)` did not yet copy `bg_ready`.

Parent validation:

- Bacon sidecar review confirmed the saliency `bg_ready` publication seam and
  recommended the stale-false and null-top-candidate scalar publication tests.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3355 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 143 only moves a saliency debug-result readiness flag into an
  existing publication contract and does not alter background readiness
  computation, saliency scoring, top-candidate selection, target tracking,
  scan planning, timing, threading, overlay drawing, thermal/color
  publication, or public API behavior. Packet 140 refreshed the pause-point
  IR, visible-color, and registration replay/perf gates.

## Packet 144 - Result Movement Debug Publish Helper

Status: parent-validated.

Mode: result-builder movement debug sidecar publication extraction,
behavior-preserving, after Packet 143.

Scope:

- Added `anomaly_result_publish_movement_debug(...)`.
- The helper takes `const anomaly_debug_movement_t *movement_debug` directly
  as the publication payload.
- The helper body is only null guards plus exact struct copy:
  `result_out->movement_debug = *movement_debug`.
- Replaced only the late direct `result_out->movement_debug =
  movement_sidecar` assignment after
  `anomaly_target_tracks_update_movement_evidence(...)` in
  `anomaly_analysis.c`.
- Left motion estimation, movement snapshot creation, scan planner input,
  frame metadata publication, target movement evidence update, movement tile
  interpretation, AOI evidence computation, and all movement scoring policy
  outside the helper.
- Added focused native tests for null/no-op behavior, exact sidecar copy while
  preserving unrelated result fields, and stale movement debug overwrite.

Behavior preserved:

- `anomaly_debug_movement_t` is copied exactly, including AOI evidence fields
  populated by target-track movement evidence.
- No registration fields, frame metadata, movement snapshot state,
  target-track state, tile interpretation, scoring, scan planning, timing,
  threading, overlay drawing, thermal/color/saliency publication, or public
  API behavior is changed.
- Existing frame metadata movement-copy behavior remains intact for the early
  metadata publish path; Packet 144 adds an explicit publisher for the later
  post-evidence-update sidecar.

TDD red check:

- The first red attempt exposed stale test assumptions about movement tile enum
  and field names; the fixture was corrected to the live
  `anomaly_debug_movement_tile_t` contract.
- `cmake --build tools/anomaly_test/build_timing` then failed after
  test/header wiring because `anomaly_result_publish_movement_debug` was not
  yet defined.

Parent validation:

- Gauss sidecar review confirmed the movement debug publication seam and
  recommended using `anomaly_debug_movement_t` directly rather than adding a
  wrapper DTO.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3359 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 144 only moves already-computed movement debug publication
  through the result-builder contract and does not alter motion estimation,
  movement snapshot creation, target movement evidence, scan planning, scoring,
  timing, threading, overlay drawing, thermal/color/saliency publication, or
  public API behavior. Packet 140 refreshed the pause-point IR, visible-color,
  and registration replay/perf gates.

## Packet 145 - Result Scan Plan Publish Helper

Status: parent-validated.

Mode: result-builder scan-plan publication extraction,
behavior-preserving, after Packet 144.

Scope:

- Added `anomaly_result_publish_scan_plan(...)`.
- The helper takes `const anomaly_scan_plan_t *scan_plan` directly as the
  publication payload.
- The helper body is only null guards plus exact struct copy:
  `result_out->scan_plan = *scan_plan`.
- Replaced four direct `result_out->scan_plan = scan_plan` writes in
  `anomaly_analysis.c` with
  `anomaly_result_publish_scan_plan(result_out, &scan_plan)`.
- Removed the early color fallback partial publication
  `result_out->scan_plan.reason_flags |= color_fallback_reason_flags` because
  the local `scan_plan.reason_flags` already includes those flags before the
  exact full scan-plan publish.
- Updated `anomaly_result_publish_frame_metadata(...)` to delegate scan-plan
  copying to `anomaly_result_publish_scan_plan(...)` while leaving
  `rescan_mode` publication unchanged.
- Left the paired direct `result_out->rescan_mode = rescan_mode` fallback
  write in `anomaly_analysis.c` for a later packet.
- Added focused native tests for null/no-op behavior, exact scan-plan copy
  while preserving unrelated result fields, and stale scan-plan overwrite.

Behavior preserved:

- `anomaly_scan_plan_t` is copied exactly after all local scan-plan mutations.
- The removed color fallback partial publication was redundant because the
  full local scan plan is published immediately afterward with the same
  fallback reason flags already folded in.
- Scan-plan mutation logic, ScanPlanner ownership, rescan-mode publication,
  scoring, candidate selection, target tracking, timing, threading, overlay
  drawing, thermal/color/saliency publication, and public API behavior are
  unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_scan_plan` was not yet defined.

Parent validation:

- Singer sidecar review confirmed the scan-plan publication seam and
  recommended the exact-copy helper plus retaining direct rescan-mode
  publication for a later packet.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3363 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 145 only moves already-computed scan-plan publication through
  the result-builder contract and does not alter scan-plan mutation logic,
  ScanPlanner behavior, rescan-mode publication, scoring, candidate selection,
  target tracking, timing, threading, overlay drawing, thermal/color/saliency
  publication, or public API behavior. Packet 140 refreshed the pause-point
  IR, visible-color, and registration replay/perf gates.

## Packet 146 - Result Rescan Mode Publish Helper

Status: parent-validated and pause-point replay/perf refreshed.

Mode: result-builder rescan-mode scalar publication extraction,
behavior-preserving, after Packet 145.

Scope:

- Added `anomaly_result_publish_rescan_mode(...)`.
- The helper takes the already-decided `anomaly_rescan_mode_t` value directly
  as the publication payload.
- The helper body is only a null guard plus exact scalar copy:
  `result_out->rescan_mode = rescan_mode`.
- Updated `anomaly_result_publish_frame_metadata(...)` to delegate rescan-mode
  copying to `anomaly_result_publish_rescan_mode(...)`.
- Replaced the remaining fallback direct `result_out->rescan_mode =
  rescan_mode` write in `anomaly_analysis.c` with the helper.
- Left local fallback mutation of `rescan_mode` and `scan_plan.mode` in
  `anomaly_analysis.c`, and left scan-plan publication routed through
  `anomaly_result_publish_scan_plan(...)`.
- Added focused native tests for null/no-op behavior, exact scalar copy while
  preserving unrelated result fields, and stale rescan-mode overwrite.

Behavior preserved:

- `anomaly_rescan_mode_t` is copied exactly after the caller has already made
  all fallback decisions.
- No scan-plan fields, adaptive stride fields, registration health, frame
  flags, movement debug, boxes, timing, enum validation, or synchronization
  between `rescan_mode` and `scan_plan.mode` moved into the helper.
- Scan-plan mutation logic, ScanPlanner behavior, fallback decisions, scoring,
  candidate selection, tracking, timing, threading, overlay drawing,
  thermal/color/saliency publication, and public API behavior are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_rescan_mode` was not yet defined.

Parent validation:

- Gibbs sidecar review confirmed the rescan-mode scalar publication seam and
  warned to keep scan-plan semantics and fallback decisions outside the
  helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3367 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Direct assignment audit:
  `rg -n "result_out->rescan_mode\\s*=" app/src/main/cpp/anomaly_analysis.c app/src/main/cpp/anomaly_result_builder.c`
  now finds only the helper implementation.

Pause-point replay and performance validation:

- IR regression manifest:
  `/private/tmp/ir_regression_packet146_pause/suite_report.md`.
- IR redesigned incremental profile: precision `0.994`, recall `0.599`,
  track hits `4/4`, aggregate realtime `1.516x`.
- IR dense full-scan gold profile: precision `1.000`, recall `0.362`,
  track hits `4/4`, aggregate realtime `0.617x`.
- IR current-detector baseline: precision `0.965`, recall `0.197`,
  track hits `3/4`, aggregate realtime `2.819x`.
- Visible-color regression manifest:
  `/private/tmp/color_regression_packet146_pause/suite_report.md`.
- Visible-color baseline kept the current Red1 reviewed result: recall
  `0.000`, TP `0`, FP `0`, miss `15`, track hits `0/1`, realtime `0.325x`.
- Visible-color dense-gold reported precision `1.000`, recall `1.000`,
  TP `15`, FP `0`, miss `0`, track hits `1/1`, realtime `0.048x`.
- Clean sequential visible-color performance report:
  `/private/tmp/visible_color_perf_packet146_pause_sequential/visible_color_perf_report.json`.
- Visible-color app-like auto: average realtime `0.35x`, total `93.15 ms`,
  color scoring `42.19 ms`, sampled-grid prep `21.96 ms`.
- Visible-color dense-gold: average realtime `0.05x`, total `649.69 ms`,
  color scoring `361.69 ms`, sampled-grid prep `190.37 ms`.
- Clean sequential registration performance output:
  `/private/tmp/registration_perf_packet146_pause_sequential`.
- Registration fixed cases reported `1.41x`, `1.71x`, `0.86x`, and `1.03x`
  realtime.

Replay/perf conclusion:

- The refreshed replay and perf gates are green for this pause point. Packet
  146 only moves already-computed rescan-mode publication through the
  result-builder contract and does not alter scan-plan mutation, ScanPlanner
  behavior, fallback decisions, scoring, candidate selection, tracking,
  timing, threading, overlay drawing, thermal/color/saliency publication, or
  public API behavior.

## Packet 147 - Result Motion Appearance Debug Publish Helpers

Status: parent-validated.

Mode: result-builder motion appearance debug publication extraction,
behavior-preserving, after Packet 146.

Scope:

- Added `anomaly_result_publish_motion_appearance_debug_summary(...)`.
- Added `anomaly_result_publish_motion_appearance_debug_result(...)`.
- Added explicit summary/result publication payload structs:
  - `anomaly_result_motion_appearance_debug_summary_publication_t`
  - `anomaly_result_motion_appearance_debug_result_publication_t`
- The result-builder helpers delegate to the existing
  `anomaly_motion_estimator_populate_appearance_debug_summary(...)` and
  `anomaly_motion_estimator_populate_appearance_debug_result(...)` helpers so
  the current MotionEstimator debug formatting remains the single behavior
  source for valid flags, coordinate normalization, scalar copies, and
  top-candidate clamping.
- Replaced the early direct `&result_out->motion_debug` summary call in
  `anomaly_analysis.c` with a local summary DTO plus result-builder publish.
- Replaced the final direct `&result_out->motion_debug` result call in
  `anomaly_analysis.c` with a local result DTO plus result-builder publish.
- Added focused native tests for null/no-op behavior, summary publication
  while preserving later/raw/top-candidate fields, result publication while
  preserving earlier summary fields, top-candidate clamping/copy order, and
  unrelated result-field preservation.

Behavior preserved:

- Summary publication still sets `motion_debug.valid` from
  `global_count > 0 || motion_candidate_count > 0` and copies only summary
  fields.
- Result publication still sets raw-candidate validity from nonnegative raw
  score, normalizes raw x/y by frame dimensions using the legacy convention,
  copies winner scale fields, clamps `top_candidate_count`, and copies
  top-candidates in order.
- MotionEstimator scoring, global stats, candidate selection, top-candidate
  construction, `best_motion_*` state, persistence maps, support maps,
  tracking, boxes, timing, scan/rescan state, thermal/color/saliency debug,
  and public API behavior are unchanged.
- The remaining four direct `result_out->thermal_debug.candidates[i]` local
  reads in `anomaly_analysis.c` were intentionally left for a later thermal
  candidate-detail packet.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_publish_motion_appearance_debug_summary` and
  `anomaly_result_publish_motion_appearance_debug_result` were not yet
  defined.

Parent validation:

- Kierkegaard sidecar review confirmed the narrow motion appearance debug
  publication seam and recommended keeping the existing MotionEstimator
  formatter helpers in place for Packet 147.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3391 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Direct result-output audit:
  `rg -n "&result_out->|result_out->" app/src/main/cpp/anomaly_analysis.c`
  now finds only four thermal-candidate debug local reads.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 147 only moves already-computed motion appearance debug
  publication through the result-builder contract and does not alter
  MotionEstimator scoring, candidate ordering, support maps, sampling
  lifecycle, tracking, boxes, timing, scan planning, threading, overlay
  drawing, thermal/color/saliency publication, or public API behavior. Packet
  146 refreshed the pause-point IR, visible-color, and registration replay/perf
  gates.

## Packet 148 - Result Thermal Candidate Snapshot Helper

Status: parent-validated.

Mode: result-builder thermal candidate snapshot extraction,
behavior-preserving, after Packet 147.

Scope:

- Added `anomaly_result_copy_thermal_debug_candidate(...)`.
- The helper copies the current staged
  `anomaly_debug_thermal_candidate_t` at a bounded candidate index into
  caller-owned storage.
- The helper body is only null guards, index bounds guards, and exact struct
  copy from `result_out->thermal_debug.candidates[candidate_index]`.
- Replaced the four remaining local direct
  `result_out->thermal_debug.candidates[i]` reads in `anomaly_analysis.c`
  with helper-backed local snapshots before deriving:
  - thermal candidate movement DTOs,
  - nearest-track DTOs,
  - near-debug-target DTOs,
  - raw-delta rescue DTOs.
- Added focused native tests for null/no-op behavior, invalid index behavior,
  output preservation on failure, base-published candidate field copying, and
  staged enrichment/late-flag copying.

Behavior preserved:

- The helper returns the candidate as currently staged at that point in result
  publication. It is not a base-candidate-only view.
- No bbox/norm recomputation, semantic valid check, candidate_count access,
  thermal summary access, thermal target access, state target-track access,
  thermal scratch-array access, movement-snapshot query, nearest-track lookup,
  raw-delta rescue computation, scoring, support-map logic, candidate
  ordering, tracking, timing, scan planning, threading, overlay drawing, or
  public API behavior moved into the helper.
- The movement, nearest-track, near-debug, and raw-delta rescue publication
  DTOs are still derived in `anomaly_analysis.c` from the same staged thermal
  candidate fields as before.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_result_copy_thermal_debug_candidate` was not yet
  defined.

Parent validation:

- Curie sidecar review confirmed the staged thermal candidate snapshot seam and
  warned against moving nearest-track lookup or raw-delta rescue computation
  into result-builder.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3412 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Direct result-output audit:
  `rg -n "result_out->thermal_debug\\.candidates\\[[^]]+\\]|&result_out->|result_out->" app/src/main/cpp/anomaly_analysis.c`
  returned no matches.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 148 only copies already-staged thermal candidate debug state
  through the result-builder contract and does not alter thermal candidate
  extraction/order, movement query behavior, nearest-track search, raw-delta
  rescue formulas, scoring, support maps, target tracking, timing, scan
  planning, threading, overlay drawing, public API behavior, or any published
  result values. Packet 146 refreshed the pause-point IR, visible-color, and
  registration replay/perf gates.

## Packet 149 - MotionEstimator Movement Mode Normalization Contract

Status: parent-validated.

Mode: MotionEstimator contract helper, behavior-preserving, after Packet 148.

Scope:

- Added `anomaly_motion_estimator_normalize_movement_mode(...)`.
- The helper is MotionEstimator-owned and does not include
  `anomaly_runtime_config.h` in `anomaly_motion_estimator.c`.
- The helper preserves the existing sidecar normalization semantics:
  - `NULL` config -> `ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE`
  - invalid/unknown mode -> `ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE`
  - `ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE` passes through
  - `ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW` passes through
- Replaced the private local `normalize_sidecar_movement_mode(...)` duplicate
  in `anomaly_motion_estimator.c`.
- Routed `anomaly_motion_estimator_estimate_sidecar(...)` through the new
  helper before its existing readiness early returns.
- Added focused native tests for the helper contract, parity with runtime
  config normalization, and sidecar mode publication for null input, null
  config, invalid mode, active mode, and shadow mode.

Behavior preserved:

- The sidecar still publishes the normalized mode and default
  `parallax_suppression_scale` before returning for null input, legacy mode,
  or readiness failures.
- The active/shadow branches still proceed to the same readiness checks and
  sidecar work loop when inputs are otherwise valid.
- No registration solving, sidecar sampling loop, movement tile
  classification, appearance scoring, support map logic, target tracking,
  timing, scan planning, threading, overlay drawing, public API behavior, or
  published result values changed.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_normalize_movement_mode` was not
  yet defined.

Parent validation:

- Beauvoir sidecar review confirmed the boundary was behavior-preserving and
  recommended avoiding the broad runtime-config include plus adding direct
  `estimate_sidecar` mode-publication coverage. Both recommendations were
  incorporated before final validation.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3424 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Dependency audit:
  `rg -n "anomaly_runtime_config.h|normalize_sidecar_movement_mode|anomaly_motion_estimator_normalize_movement_mode|sidecar_publishes_normalized" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
  confirmed `anomaly_motion_estimator.c` no longer includes
  `anomaly_runtime_config.h`, the old private local helper is gone, and the
  sidecar routes through `anomaly_motion_estimator_normalize_movement_mode`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 149 only names and centralizes the existing MotionEstimator
  movement-mode enum normalization contract. It does not alter the sidecar
  work loop, registration inputs, movement tile queries, candidate scoring,
  support maps, sampling lifecycle, target tracking, timing finalization, scan
  planning, threading, overlay drawing, public API behavior, or published
  result values. The pause-point replay/perf gates were refreshed immediately
  before this packet.

## Packet 150 - MotionEstimator Sidecar Input Readiness Contract

Status: parent-validated.

Mode: MotionEstimator sidecar contract helper, behavior-preserving, after
Packet 149.

Scope:

- Added `anomaly_motion_estimator_sidecar_input_ready(...)`.
- The helper names the pre-loop readiness predicate for the registration-backed
  movement sidecar.
- The helper returns ready only when:
  - the sidecar input exists,
  - movement mode normalizes to layered active or layered shadow,
  - current and previous luma buffers are present,
  - motion-grid dimensions leave the existing one-cell border,
  - frame dimensions are usable,
  - motion step is positive,
  - sidecar ops and all three callbacks are present,
  - and the injected registration-valid callback accepts the registration.
- `anomaly_motion_estimator_estimate_sidecar(...)` now uses the helper after
  publishing the normalized mode/default suppression scale and before the
  existing ROI grid-bounds guard.
- Added focused native tests for null input, active/shadow readiness,
  legacy/null config rejection, missing luma, motion width/height bounds, frame
  width/height bounds, nonpositive motion step, missing ops, missing callback
  slots, and invalid registration.

Behavior preserved:

- `estimate_sidecar(...)` still clears the output first, publishes mode and
  default `parallax_suppression_scale`, returns immediately for null input or
  legacy mode, and then returns for the same pre-loop input-readiness failures
  as before.
- The ROI-derived grid-bounds guard remains separate and unchanged.
- The sidecar sampling loop, registration projection calls, residual
  displacement calls, movement tile classification, debug movement output,
  registration solving, appearance scoring, support maps, timing, scan
  planning, threading, overlay drawing, public API behavior, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_input_ready` was not yet
  defined.

Parent validation:

- Heisenberg sidecar review confirmed the route through `estimate_sidecar(...)`
  was behavior-preserving. The review called out Packet 149's exported
  movement-mode normalizer as broader than Packet 150's seam; the parent kept
  it because it was intentional prior packet scope. The review also requested
  fuller readiness predicate leaf coverage, which was added before final
  validation.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3437 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 150 only names and routes the existing MotionEstimator
  sidecar pre-loop readiness predicate. It does not alter ROI grid math, the
  sidecar sampling loop, registration solving, movement tile classification,
  candidate scoring, support maps, sampling lifecycle, target tracking, timing
  finalization, scan planning, threading, overlay drawing, public API
  behavior, or published result values.

## Packet 151 - MotionEstimator Sidecar Grid Bounds Contract

Status: parent-validated.

Mode: MotionEstimator sidecar geometry helper, behavior-preserving, after
Packet 150.

Scope:

- Added `anomaly_motion_sidecar_grid_bounds_t`.
- Added `anomaly_motion_estimator_sidecar_grid_bounds(...)`.
- The helper computes the registration-backed movement sidecar's ROI bounds in
  motion-grid coordinates.
- The helper preserves the existing sidecar math:
  - lower bounds use C integer division and clamp to the one-cell
    patch-search border,
  - upper bounds use `(roi + motion_step - 1) / motion_step` and clamp to the
    sidecar patch-search border,
  - invalid inputs clear the output and return false,
  - collapsed bounds return false.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes the sidecar
  bounds calculation through the helper and then uses the same local
  `roi_mgx0`, `roi_mgx1`, `roi_mgy0`, and `roi_mgy1` values in the unchanged
  sampling loop.
- Added focused native tests for null/invalid inputs, normal ROI conversion,
  patch-border clamping, partial upper-bound ceil behavior, and collapsed ROI
  rejection.

Behavior preserved:

- The helper is called after sidecar input readiness and before the existing
  sampling loop.
- The sidecar grid bounds remain distinct from
  `anomaly_motion_estimator_appearance_grid_bounds(...)`, whose edge clamps are
  intentionally different.
- The sidecar sampling loop, registration projection calls, residual
  displacement calls, movement tile classification, debug movement output,
  registration solving, appearance scoring, support maps, timing, scan
  planning, threading, overlay drawing, public API behavior, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_grid_bounds` was not yet
  defined.

Parent validation:

- Hilbert sidecar review found no blocking issues and confirmed the extracted
  helper preserved lower-bound division/clamp behavior, upper-bound ceil/clamp
  behavior, collapsed-bound rejection, output clearing, and separation from the
  appearance grid-bounds helper.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3454 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 151 only names and routes the existing MotionEstimator
  sidecar ROI grid-bounds calculation. It does not alter the sidecar sampling
  loop, registration solving or projection callbacks, residual displacement
  callbacks, movement tile classification, candidate scoring, support maps,
  sampling lifecycle, target tracking, timing finalization, scan planning,
  threading, overlay drawing, public API behavior, or published result values.

## Packet 152 - MotionEstimator Sidecar Tile Center Normalization Contract

Status: parent-validated.

Mode: MotionEstimator sidecar debug tile formatting helper,
behavior-preserving, after Packet 151.

Scope:

- Added `anomaly_motion_estimator_sidecar_tile_center_norm(...)`.
- The helper computes sidecar movement tile center normalized coordinates from
  motion-grid cell coordinates and frame geometry.
- The helper preserves the existing sidecar publication arithmetic:
  `clamp01f((mx * motion_step) / width)` and
  `clamp01f((my * motion_step) / height)`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->center_x_norm` and `tile->center_y_norm` publication through the
  helper.
- Added focused native tests for null outputs, nonpositive step/width/height,
  normal conversion, and clamp behavior.

Behavior preserved:

- The helper is called after sidecar input readiness, so the production call
  site relies on the already-validated frame geometry and motion-step
  contract. The helper still guards invalid standalone contract use.
- Tile validity, displacement, residual, confidence, layer class, the sidecar
  sampling loop, movement classification, registration/projection callbacks,
  residual callbacks, appearance scoring, support maps, sampling lifecycle,
  target tracking, timing finalization, scan planning, threading, overlay
  drawing, public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_tile_center_norm` was not
  yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3465 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Parent audit with
  `rg -n "center_x_norm =|center_y_norm =|sidecar_tile_center_norm" app/src/main/cpp/anomaly_motion_estimator.c tools/anomaly_test/test_anomaly.c`
  confirmed the sidecar production assignment routes through
  `anomaly_motion_estimator_sidecar_tile_center_norm(...)`; the remaining
  direct center assignments are test fixtures.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun during the
  packet implementation because Packet 152 only names and routes the existing
  MotionEstimator sidecar tile-center normalization arithmetic. The pause gate
  later refreshed replay/performance coverage before stopping.

## Packet 153 - MotionEstimator Sidecar Layer Classification Contract

Status: parent-validated.

Mode: MotionEstimator sidecar tile classification helper,
behavior-preserving, after Packet 152.

Scope:

- Added `anomaly_motion_estimator_sidecar_classify_layer(...)`.
- The helper computes the sidecar movement tile layer class from `flow_px`,
  `residual_px`, `neighbor_delta_px`, and `motion_step`.
- The helper preserves the existing sidecar branch order:
  background, coherent-near, local-outlier, then unstable fallback.
- The helper preserves the inclusive threshold edges for background,
  coherent-near, and local-outlier classification.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `layer_class` selection through the helper; the existing counter increments
  remain local and map one-to-one from the selected class.
- Added focused native tests for background inclusive edge, coherent-near
  precedence over local-outlier, local-outlier inclusive edge, local-outlier
  above-threshold flow, unstable fallback, and invalid motion-step standalone
  contract behavior.

Behavior preserved:

- Production sidecar input readiness already rejects nonpositive
  `motion_step`; the helper returns `ANOMALY_MOVEMENT_LAYER_UNKNOWN` only for
  standalone invalid contract use.
- In the production loop, unknown or unexpected helper output falls into the
  same `unstable++` fallback path.
- Tile validity, center normalization, displacement, residual, confidence,
  sampling, registration projection, residual search, movement aggregate
  metrics, appearance scoring, support maps, scan planning, threading, overlay
  drawing, public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_classify_layer` was not
  yet defined.

Parent validation:

- Pascal sidecar review found no blocking issues and confirmed branch order,
  inclusive thresholds, counter mapping, invalid motion-step handling, and
  internal-header exposure.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3471 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 153 only names and routes the existing MotionEstimator
  sidecar layer-class decision. It does not alter sidecar sampling,
  registration solving or projection callbacks, residual displacement search,
  tile coordinate publication, tile confidence, movement aggregate metrics,
  candidate scoring, support maps, sampling lifecycle, target tracking, timing
  finalization, scan planning, threading, overlay drawing, public API
  behavior, or published result values.

## Packet 154 - MotionEstimator Sidecar Parallax Suppression Contract

Status: parent-validated.

Mode: MotionEstimator sidecar aggregate scalar helper, behavior-preserving,
after Packet 153.

Scope:

- Added `anomaly_motion_estimator_sidecar_parallax_suppression_scale(...)`.
- The helper computes the final sidecar parallax suppression scale from
  `parallax_load` and `local_outlier_load`.
- The helper preserves the existing sidecar formula:
  - no suppression at or below `parallax_load == 0.25`,
  - no suppression at or above `local_outlier_load == 0.20`,
  - otherwise `1.0 - 0.45 * clampf((parallax_load - 0.25) / 0.45, 0.0, 1.0)`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `movement_out->parallax_suppression_scale` publication through the helper.
- Added focused native tests for the parallax threshold edge, local-outlier
  threshold edge, linear interpolation, and high-parallax clamp floor.

Behavior preserved:

- Parallax load, local-outlier load, confidence, aggregate counts/fractions,
  tile validity, tile center normalization, displacement, residual,
  confidence, classification, sampling, registration projection, residual
  search, movement aggregate metrics, scan planning, threading, overlay
  drawing, public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_motion_estimator_sidecar_parallax_suppression_scale` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3475 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 154 only names and routes the existing MotionEstimator
  sidecar final suppression-scale scalar calculation. It does not alter
  sidecar sampling, registration solving or projection callbacks, residual
  displacement search, tile classification, tile confidence, movement
  aggregate load computation, candidate scoring, support maps, sampling
  lifecycle, target tracking, timing finalization, scan planning, threading,
  overlay drawing, public API behavior, or published result values.

## Packet 155 - MotionEstimator Sidecar Tile Confidence Contract

Status: parent-validated.

Mode: MotionEstimator sidecar tile scalar helper, behavior-preserving, after
Packet 154.

Scope:

- Added `anomaly_motion_estimator_sidecar_tile_confidence(...)`.
- The helper computes the sidecar movement tile confidence from `residual_px`,
  `flow_px`, and `motion_step`.
- The helper preserves the existing sidecar formula:
  `clampf(1.0 - residual_px / 64.0 + min(flow_px / (motion_step * 8.0), 0.25), 0.0, 1.0)`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->confidence` publication through the helper.
- Added focused native tests for fully confident clean tiles, residual
  penalty plus flow bonus, capped flow bonus, high-residual clamp to zero, and
  invalid motion-step standalone contract behavior.

Behavior preserved:

- Production sidecar input readiness already rejects nonpositive
  `motion_step`; the helper returns `0.0f` only for standalone invalid
  contract use.
- Tile validity, center normalization, displacement, residual, layer
  classification, sampling, registration projection, residual search,
  movement aggregate metrics, scan planning, threading, overlay drawing,
  public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_tile_confidence` was not
  yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3480 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Parent audit with
  `rg -n "sidecar_tile_confidence|tile->confidence =|fminf\\(flow_px" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
  confirmed the only production tile-confidence assignment routes through the
  helper.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 155 only names and routes the existing MotionEstimator
  sidecar tile-confidence scalar calculation. It does not alter sidecar
  sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate publication, tile classification,
  movement aggregate load computation, candidate scoring, support maps,
  sampling lifecycle, target tracking, timing finalization, scan planning,
  threading, overlay drawing, public API behavior, or published result values.

## Packet 156 - MotionEstimator Sidecar Tile Displacement Contract

Status: parent-validated.

Mode: MotionEstimator sidecar tile scalar helper, behavior-preserving, after
Packet 155.

Scope:

- Added `anomaly_motion_estimator_sidecar_tile_displacement_px(...)`.
- The helper computes sidecar movement tile pixel displacement from grid
  displacement and `motion_step`.
- The helper preserves the existing sidecar arithmetic:
  `dx_px = dx * motion_step` and `dy_px = dy * motion_step`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only
  `tile->dx_px` and `tile->dy_px` publication through the helper.
- Added focused native tests for null output rejection, nonpositive
  `motion_step` rejection, signed displacement scaling, and zero-displacement
  preservation.

Behavior preserved:

- Production sidecar input readiness already rejects nonpositive
  `motion_step`; the helper rejects invalid standalone contract use.
- Tile validity, center normalization, residual, confidence, layer
  classification, sampling, registration projection, residual search, flow
  magnitude, movement aggregate metrics, scan planning, threading, overlay
  drawing, public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_sidecar_tile_displacement_px` was
  not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3489 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Parent audit with
  `rg -n "sidecar_tile_displacement|tile->dx_px =|tile->dy_px =|best_dx \\*" app/src/main/cpp/anomaly_motion_estimator.c app/src/main/cpp/anomaly_motion_estimator.h tools/anomaly_test/test_anomaly.c`
  confirmed production tile displacement publication routes through the helper;
  the remaining `best_dx` expression is the unchanged flow-magnitude
  calculation.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 156 only names and routes the existing MotionEstimator
  sidecar tile-displacement scalar conversion. It does not alter sidecar
  sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate publication, tile residual/confidence,
  tile classification, movement aggregate load computation, candidate scoring,
  support maps, sampling lifecycle, target tracking, timing finalization, scan
  planning, threading, overlay drawing, public API behavior, or published
  result values.

## Packet 157 - MotionEstimator Sidecar Displacement Magnitude Contract

Status: parent-validated.

Mode: MotionEstimator sidecar scalar helper, behavior-preserving, after
Packet 156.

Scope:

- Added `anomaly_motion_estimator_sidecar_displacement_magnitude_px(...)`.
- The helper computes sidecar movement pixel magnitude from grid displacement
  and `motion_step`.
- The helper preserves the existing sidecar arithmetic:
  `sqrt(dx * dx + dy * dy) * motion_step`.
- `anomaly_motion_estimator_estimate_sidecar(...)` now routes only tile
  `flow_px` and previous-flow `neighbor_delta` magnitude calculations through
  the helper.
- Added focused native tests for signed Euclidean magnitude, zero displacement,
  motion-step scaling, and invalid `motion_step` standalone contract behavior.

Behavior preserved:

- Production sidecar input readiness already rejects nonpositive
  `motion_step`; the helper returns `0.0f` only for standalone invalid
  contract use.
- Tile validity, center normalization, displacement publication, residual,
  confidence, layer classification thresholds, sampling, registration
  projection, residual search, movement aggregate counters/fractions,
  parallax/local-outlier load formulas, scan planning, threading, overlay
  drawing, public API behavior, and published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_motion_estimator_sidecar_displacement_magnitude_px` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3493 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 157 only names and routes the existing MotionEstimator
  sidecar displacement-magnitude scalar calculation. It does not alter sidecar
  sampling, registration solving or projection callbacks, residual
  displacement search, tile coordinate/displacement publication, tile
  residual/confidence, tile classification thresholds, movement aggregate load
  formulas, candidate scoring, support maps, sampling lifecycle, target
  tracking, timing finalization, scan planning, threading, overlay drawing,
  public API behavior, or published result values.

## Packet 158 - MotionEstimator Snapshot Tile Flow Magnitude Contract

Status: parent-validated.

Mode: MotionEstimator movement-snapshot scalar helper, behavior-preserving,
after Packet 157.

Scope:

- Added `anomaly_motion_estimator_tile_flow_magnitude_px(...)`.
- The helper computes pixel-flow magnitude from a valid movement snapshot tile.
- The helper preserves the existing tile pixel-flow arithmetic:
  `sqrt(tile->dx_px * tile->dx_px + tile->dy_px * tile->dy_px)`.
- `anomaly_motion_estimator_tile_independent_score(...)` now routes only its
  `flow_px` input through the helper.
- Added focused native tests for null/invalid tile rejection, signed pixel
  displacement magnitude, and zero displacement preservation.

Behavior preserved:

- Null or invalid tiles return `0.0f`, matching the independent-score no-op
  behavior for those inputs.
- Residual scoring, flow-score scaling, layer scoring, independent/parallax
  classification gates, movement snapshot query behavior, sidecar production,
  scan planning, threading, overlay drawing, public API behavior, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_tile_flow_magnitude_px` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3497 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 158 only names and routes an existing MotionEstimator
  snapshot scalar query used by the independent-motion score. It does not
  alter sidecar sampling, registration solving or projection callbacks,
  residual displacement search, tile coordinate/displacement publication,
  tile residual/confidence, tile classification thresholds, movement aggregate
  load formulas, candidate scoring, support maps, sampling lifecycle, target
  tracking, timing finalization, scan planning, threading, overlay drawing,
  public API behavior, or published result values.

## Packet 159 - MotionEstimator Snapshot Tile Residual Score Contract

Status: parent-validated.

Mode: MotionEstimator movement-snapshot scalar helper, behavior-preserving,
after Packet 158.

Scope:

- Added `anomaly_motion_estimator_tile_residual_independent_score(...)`.
- The helper computes the residual-evidence component from a valid movement
  snapshot tile.
- The helper preserves the existing residual score arithmetic:
  `clampf((tile->residual_px - 12.0f) / 28.0f, 0.0f, 1.0f)`.
- `anomaly_motion_estimator_tile_independent_score(...)` now routes only its
  residual-score input through the helper.
- Added focused native tests for null/invalid tile rejection, lower threshold
  edge, midpoint linear scale, upper threshold edge, and high-residual clamp.

Behavior preserved:

- Null or invalid tiles return `0.0f`, matching the independent-score no-op
  behavior for those inputs.
- Flow magnitude, flow-score scaling, layer scoring, independent/parallax
  classification gates, movement snapshot query behavior, sidecar production,
  scan planning, threading, overlay drawing, public API behavior, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_motion_estimator_tile_residual_independent_score`
  was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3503 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 159 only names and routes an existing MotionEstimator
  snapshot scalar query used by the independent-motion score. It does not
  alter sidecar sampling, registration solving or projection callbacks,
  residual displacement search, tile coordinate/displacement publication,
  tile residual/confidence, tile classification thresholds, movement aggregate
  load formulas, candidate scoring, support maps, sampling lifecycle, target
  tracking, timing finalization, scan planning, threading, overlay drawing,
  public API behavior, or published result values.

## Packet 160 - Detector Facade Frame Readiness Contract

Status: parent-validated.

Mode: detector facade input contract helper, behavior-preserving, after
Packet 159.

Scope:

- Added `anomaly_detector_frame_input_ready(...)`.
- The helper exposes the current processable frame predicate for the
  standalone detector facade.
- The helper returns true only for non-null
  `ANOMALY_FRAME_FORMAT_RGBA8888` frames with non-null `rgba`, positive
  `rgba_stride`, positive `width`, and positive `height`.
- `anomaly_detector_process(...)` now routes only its frame-readiness branch
  through the helper.
- Added focused native tests for valid RGBA input, null frame, unsupported
  format, null RGBA pointer, nonpositive stride, nonpositive width, and
  nonpositive height.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the shared
  readiness rule.

Behavior preserved:

- Valid RGBA frames still forward to `anomaly_process_frame(...)` with the
  same state, config, frame pointer, stride, dimensions, timestamp, and result
  output.
- Invalid frames still take the existing zero-work result-initialization path
  through `anomaly_process_frame(...)` with null RGBA, zero dimensions, and
  the source timestamp when a frame was supplied.
- Scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, public API behavior for existing callers, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_frame_input_ready` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3510 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 160 only names and routes the detector facade
  frame-input readiness predicate. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 161 - Detector Facade Structured Process Args Contract

Status: parent-validated.

Mode: detector facade process contract helper, behavior-preserving, after
Packet 160.

Scope:

- Added public `anomaly_detector_process_args_t`.
- Added `anomaly_detector_process_with_args(...)`.
- The structured process args bundle names the standalone processing call as
  state, frame, config, and result output.
- `anomaly_detector_process(...)` now builds an args struct and forwards to
  `anomaly_detector_process_with_args(...)`.
- `anomaly_detector_internal.h` now includes the public facade header instead
  of maintaining a duplicate private process-args type.
- Added focused native tests proving the structured entry point matches the
  positional facade result shape and remains null-safe.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the structured call
  shape.

Behavior preserved:

- The positional facade still exists for current callers and returns the same
  values through the same underlying processing path.
- Valid RGBA frames still forward to `anomaly_process_frame(...)` with the
  same state, config, frame pointer, stride, dimensions, timestamp, and result
  output.
- Invalid frames still take the existing zero-work result-initialization path
  through `anomaly_process_frame(...)` with null RGBA, zero dimensions, and
  the source timestamp when a frame was supplied.
- Scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, public API behavior for existing callers, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_with_args` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3525 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 161 only names and routes the detector facade
  process-argument bundle. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 162 - Detector Facade Structured Args Frame Readiness Contract

Status: parent-validated.

Mode: detector facade structured-call predicate, behavior-preserving, after
Packet 161.

Scope:

- Added `anomaly_detector_process_args_frame_ready(...)`.
- The helper names the process-args subset required to process frame pixels:
  non-null args, non-null detector state, and a ready frame.
- Config and result output are intentionally optional for this predicate
  because the current core supports null/fallback behavior for those inputs.
- `anomaly_detector_process_with_args(...)` now routes only its valid-frame
  branch through the helper.
- Added focused native tests for valid args, null config, null result output,
  null state, unready frame, and null args.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the structured args
  frame-readiness predicate.

Behavior preserved:

- Valid RGBA frames still forward to `anomaly_process_frame(...)` with the
  same state, config, frame pointer, stride, dimensions, timestamp, and result
  output.
- Invalid args/frames still take the existing zero-work result-initialization
  path through `anomaly_process_frame(...)` with null RGBA, zero dimensions,
  and the source timestamp when a frame was supplied.
- Scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, public API behavior for existing callers, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_args_frame_ready` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3531 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 162 only names and routes the detector facade
  structured-args frame-readiness predicate. It does not alter valid-frame
  processing, invalid-input result initialization, scoring, sampling,
  registration, MovementEstimator behavior, result boxes, overlay drawing,
  threading, or published result values.

## Packet 163 - Detector Facade Structured Args Constructor

Status: parent-validated.

Mode: detector facade structured-call constructor, behavior-preserving, after
Packet 162.

Scope:

- Added `anomaly_detector_process_args_make(...)`.
- The helper is the canonical constructor for the structured process-args
  bundle: state, frame, config, and result output.
- `anomaly_detector_process(...)` now uses the constructor before forwarding
  to `anomaly_detector_process_with_args(...)`.
- Added focused native tests proving the constructor copies all fields and
  preserves optional null fields.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the constructor.

Behavior preserved:

- The positional facade still exists for current callers and returns the same
  values through the same structured processing path.
- Null state, frame, config, and result output remain expressible so existing
  fallback behavior is preserved.
- Valid RGBA frames still forward to `anomaly_process_frame(...)` with the
  same state, config, frame pointer, stride, dimensions, timestamp, and result
  output.
- Invalid args/frames still take the existing zero-work result-initialization
  path through `anomaly_process_frame(...)` with null RGBA, zero dimensions,
  and the source timestamp when a frame was supplied.
- Scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, public API behavior for existing callers, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_args_make` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3539 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 163 only names and routes the detector facade
  structured-args constructor. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 164 - Detector Facade Annotation Ownership Predicate

Status: parent-validated.

Mode: detector facade annotation ownership contract, behavior-preserving,
after Packet 163.

Scope:

- Added `anomaly_detector_process_args_may_annotate_frame(...)`.
- The helper exposes the current in-place annotation predicate for structured
  detector calls.
- The helper returns true for frame-ready args with a non-null config when
  either `show_hot_overlay` is true or anomaly detection is enabled.
- The helper returns false for null args, unready args/frames, or null config.
- Added focused native tests for enabled detection, hot-overlay-only drawing,
  disabled/no-overlay behavior, null config, unready frame, and null args.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the current
  in-place annotation predicate.

Behavior preserved:

- This is a read-only predicate. It does not change when overlay drawing
  actually happens inside `anomaly_process_frame(...)`.
- Enabled detection may still draw result boxes only when publishable boxes are
  present, and hot overlay may still draw without detector boxes.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_args_may_annotate_frame` was not
  yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3545 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 164 only names the detector facade annotation
  ownership predicate. It does not alter valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

## Packet 165 - Detector Facade Annotation Result View

Status: parent-validated.

Mode: detector facade annotation-output contract, behavior-preserving, after
Packet 164.

Scope:

- Added `anomaly_detector_annotation_t`.
- Added `anomaly_detector_annotation_view_t`.
- Added `anomaly_detector_result_annotations(...)`.
- The helper exposes a bounded annotation view over the current
  `anomaly_result_t` boxes for standalone consumers.
- Null results, zero counts, and negative counts expose an empty view.
- Positive counts expose `result->boxes`.
- Oversized counts are clamped to `ANOMALY_MAX_BOXES_PER_FRAME` so callers
  iterate only the fixed public annotation storage.
- Added focused native tests for positive counts, oversized counts, zero
  counts, negative counts, and null results.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the annotation view.

Behavior preserved:

- This is a read-only facade view over existing result data.
- It does not change when detections are produced or which boxes are published.
- It does not change overlay drawing; the current core may still draw overlays
  in-place on input RGBA according to the existing policy.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result box construction,
  overlay drawing, threading, public API behavior for existing callers, and
  published result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_result_annotations` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3553 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 165 only names a bounded detector facade view over
  already-published result boxes. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result box construction, overlay drawing,
  threading, or published result values.

## Packet 166 - Detector Facade Annotated Frame Output View

Status: parent-validated.

Mode: detector facade annotated-frame output contract, behavior-preserving,
after Packet 165.

Scope:

- Added `anomaly_detector_frame_output_t`.
- Added `anomaly_detector_process_args_frame_output(...)`.
- The helper exposes the current output-frame view for ready structured calls:
  RGBA pointer, stride, dimensions, source timestamp, and whether annotations
  may be present in-place under the current config.
- Ready args expose the same RGBA buffer currently passed into
  `anomaly_process_frame(...)`; this records the present in-place output
  ownership model rather than changing it.
- Disabled/no-overlay configs still expose a frame output, but report
  `annotations_may_be_in_place == false`.
- Null args or unready frames expose an empty output view.
- Added focused native tests for enabled annotation policy, disabled/no-overlay
  policy, unready frames, and null args.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the annotated-frame
  output view.

Behavior preserved:

- This is a read-only facade view over existing process args and config policy.
- It does not process frames, allocate output storage, copy RGBA pixels, or
  draw overlays.
- It does not change the current in-place RGBA ownership model.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_args_frame_output` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3561 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 166 only names a detector facade output view over
  already-processed input frame storage and existing annotation policy. It
  does not alter valid-frame processing, invalid-input result initialization,
  scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, or published result values.

## Packet 167 - Detector Facade Combined Process Output View

Status: parent-validated.

Mode: detector facade processed-output contract, behavior-preserving, after
Packet 166.

Scope:

- Added `anomaly_detector_process_output_t`.
- Added `anomaly_detector_process_output(...)`.
- The helper exposes one standalone-consumable processed-output view composed
  of the current frame output view and the current bounded annotation view.
- The frame side comes from
  `anomaly_detector_process_args_frame_output(...)`.
- The annotation side comes from
  `anomaly_detector_result_annotations(...)`.
- Null args and null results preserve the same empty frame/annotation behavior
  as the underlying helpers.
- Added focused native tests for frame view propagation, annotation view
  propagation, bounded annotation counts, and null inputs.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the combined output
  view.

Behavior preserved:

- This is a read-only facade composition helper.
- It does not process frames, allocate output storage, copy RGBA pixels, draw
  overlays, or publish result boxes.
- It does not change the current in-place RGBA ownership model.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_output` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3568 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 167 only composes two existing read-only facade views
  into one output contract. It does not alter valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 168 - Detector Facade Process Frame Output Wrapper

Status: parent-validated.

Mode: detector facade one-frame process/output contract,
behavior-preserving, after Packet 167.

Scope:

- Added `anomaly_detector_process_frame(...)`.
- The helper accepts `anomaly_detector_process_args_t`, processes one frame
  through the existing structured process path, optionally writes the legacy
  box-count return value, and returns `anomaly_detector_process_output_t`.
- The implementation calls `anomaly_detector_process_with_args(...)`, then
  returns `anomaly_detector_process_output(args, args->result_out)`.
- Null args preserve existing null-safe processing behavior: zero boxes and an
  empty process-output view.
- Existing positional and structured int-return process APIs remain available
  and unchanged.
- Added focused native tests comparing wrapper processing against
  `anomaly_detector_process_with_args(...)`, proving output frame propagation,
  output annotation view propagation, optional box-count output, and null args.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the one-frame
  process/output wrapper.

Behavior preserved:

- This is a facade wrapper over the existing structured processing path.
- It does not introduce a second detector implementation or alter result
  publication.
- It does not allocate output storage, copy RGBA pixels, or draw overlays
  outside the current core behavior.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_frame` was not yet defined.

Validation note:

- The first post-implementation harness run failed because the wrapper test
  expected the output annotation pointer to equal `output_result.boxes` even
  when the synthetic gray frame produced zero publishable boxes.
- Root cause: Packet 165 intentionally exposes an empty annotation view for
  zero box counts.
- The test was corrected to compare the wrapper output against
  `anomaly_detector_result_annotations(&output_result)`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3587 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 168 only wraps the existing structured process path
  and composes already-published output views. It does not alter valid-frame
  processing, invalid-input result initialization, scoring, sampling,
  registration, MovementEstimator behavior, result boxes, overlay drawing,
  threading, or published result values.

## Packet 169 - Detector Facade Positional Process Frame Output Wrapper

Status: parent-validated.

Mode: detector facade positional one-frame process/output contract,
behavior-preserving, after Packet 168.

Scope:

- Added `anomaly_detector_process_frame_input(...)`.
- The helper exposes the same one-frame frame-in/annotated-frame-out contract
  as `anomaly_detector_process_frame(...)`, but through positional state,
  frame, config, and result pointers.
- The implementation constructs `anomaly_detector_process_args_t` with
  `anomaly_detector_process_args_make(...)`, then delegates to
  `anomaly_detector_process_frame(...)`.
- Null positional inputs preserve the same zero-box, empty-output behavior as
  the structured wrapper.
- Existing positional and structured int-return process APIs remain available
  and unchanged.
- Added focused native tests comparing the positional output wrapper against
  the structured output wrapper, including output frame propagation, annotation
  policy propagation, annotation view propagation, optional box-count output,
  and null inputs.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the positional
  one-frame output wrapper.

Behavior preserved:

- This is a facade wrapper over the existing args constructor and one-frame
  process/output wrapper.
- It does not introduce a second detector implementation or alter result
  publication.
- It does not allocate output storage, copy RGBA pixels, or draw overlays
  outside the current core behavior.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_frame_input` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3605 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 169 only constructs the existing args bundle and
  delegates to the already-validated one-frame process/output wrapper. It does
  not alter valid-frame processing, invalid-input result initialization,
  scoring, sampling, registration, MovementEstimator behavior, result boxes,
  overlay drawing, threading, or published result values.

## Packet 170 - Detector Facade Native Realtime Default Config

Status: parent-validated.

Mode: detector facade standalone config contract, behavior-preserving, after
Packet 169.

Scope:

- Added `anomaly_detector_default_window_frames(...)`.
- Added `anomaly_detector_config_make_realtime_default(...)`.
- The default window helper names the standalone native realtime window as
  0.5 seconds. It maps 30 fps to 15 frames, rounds fractional rates to the
  nearest frame, falls back to 30 fps for invalid/nonpositive rates, and keeps
  at least one frame for low positive rates.
- The realtime config helper builds a native detector config for a selected
  algorithm mask and frame rate. At 30 fps it sets `frame_stride` and
  `adaptive_max_stride_frames` to 15.
- The helper uses existing native constants for score threshold, min area,
  scan zone, min hits, thermal delta, small target size, and color frontend
  mode.
- The helper leaves Android preference defaults untouched; current app
  defaults remain owned by `AnomalyConfig`/`AnomalyPrefs`.
- Added focused native tests for 30 fps, fractional fps, invalid fps, low fps,
  copied algorithm masks, half-second stride fields, and native tuning
  constants.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the standalone
  native realtime default config helpers.

Behavior preserved:

- This packet only adds unused facade config-construction helpers.
- It does not route existing app, bridge, harness, positional, or structured
  process callers through the new defaults.
- It does not alter existing Android preference defaults.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_default_window_frames` and
  `anomaly_detector_config_make_realtime_default` were not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3630 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 170 only adds unused facade config-construction
  helpers. It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 171 - Detector Facade Annotation Output Cadence Policy

Status: parent-validated.

Mode: detector facade standalone annotation cadence contract,
behavior-preserving, after Packet 170.

Scope:

- Added `anomaly_detector_annotation_cadence_allows_update(...)`.
- The helper exposes the standalone annotation-output cadence policy needed to
  keep visible annotation state from appearing/disappearing faster than the
  configured cadence window.
- Frame ordinal zero may publish.
- Subsequent visible annotation-state changes are allowed only on
  cadence-window boundaries.
- With the Packet 170 default 30 fps realtime window, cadence 15 allows
  updates at ordinals 0, 15, 30, and so on.
- Negative frame ordinals cannot update.
- Invalid/nonpositive cadence values fall back to every-frame updates so
  adapters avoid modulo/division by bad input.
- Added focused native tests for first frame, within-window hold,
  boundary-frame update, repeated boundaries, negative frame ordinals, invalid
  cadence, and cadence of one.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the standalone
  annotation cadence helper.

Behavior preserved:

- This packet only adds an unused read-only facade policy helper.
- It does not route existing core overlay drawing, result publication, app
  adapters, bridge paths, or harness process paths through the helper.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_annotation_cadence_allows_update` was not
  yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3639 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 171 only adds an unused read-only facade policy helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 172 - Detector Facade Annotation Cadence Visibility State

Status: parent-validated.

Mode: detector facade standalone annotation visibility cadence state,
behavior-preserving, after Packet 171.

Scope:

- Added `anomaly_detector_annotation_cadence_state_t`.
- Added `anomaly_detector_annotation_cadence_state_init(...)`.
- Added `anomaly_detector_annotation_cadence_update_visibility(...)`.
- The state helper exposes a practical standalone adapter primitive for the
  no-flicker annotation-output contract: desired annotation visibility can be
  computed every frame, while visible output state is held between cadence
  boundary frames.
- First nonnegative frame initializes visible state to desired visibility and
  records the frame ordinal.
- Desired appearance inside the cadence window is held hidden.
- Desired disappearance inside the cadence window is held visible.
- Desired changes at cadence boundaries mutate and return the new visible
  state.
- Null state and negative frame ordinals fall back to desired visibility
  without mutating state.
- Added focused native tests for init, first update, held appearance, boundary
  appearance, held disappearance, boundary disappearance, null state, reset,
  first nonzero frame, and negative frame ordinal.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the stateful
  annotation cadence visibility helper.

Behavior preserved:

- This packet only adds unused facade state helpers.
- It does not route existing core overlay drawing, result publication, app
  adapters, bridge paths, or harness process paths through the helper.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_annotation_cadence_state_init` and
  `anomaly_detector_annotation_cadence_update_visibility` were not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3654 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 172 only adds unused facade state helpers. It does not
  alter existing config flow, valid-frame processing, invalid-input result
  initialization, scoring, sampling, registration, MovementEstimator behavior,
  result boxes, overlay drawing, threading, or published result values.

## Packet 173 - Detector Facade Annotation Cadence Snapshot State

Status: parent-validated.

Mode: detector facade standalone annotation snapshot cadence state,
behavior-preserving, after Packet 172.

Scope:

- Added `anomaly_detector_annotation_cadence_snapshot_state_t`.
- Added `anomaly_detector_annotation_cadence_snapshot_state_init(...)`.
- Added `anomaly_detector_annotation_cadence_update_snapshot(...)`.
- The snapshot helper extends the no-flicker cadence contract from visible
  state to the actual annotation boxes returned to standalone adapters.
- The state owns a fixed `ANOMALY_MAX_BOXES_PER_FRAME` copy of the visible
  annotations plus the existing visibility cadence state.
- First visible frame copies desired boxes.
- Desired box changes inside a cadence window are held at the previous
  snapshot.
- Desired visible boxes at a cadence boundary refresh the snapshot.
- Desired disappearance inside a cadence window holds the previous snapshot.
- Desired disappearance at a cadence boundary clears the snapshot.
- Null state returns the desired bounded view directly.
- Added focused native tests for init, first visible copy, held box changes,
  boundary refresh, held disappearance, boundary clear, and null state.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the snapshot helper.

Behavior preserved:

- This packet only adds unused facade snapshot helpers.
- It does not route existing core overlay drawing, result publication, app
  adapters, bridge paths, or harness process paths through the helper.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_annotation_cadence_snapshot_state_init` and
  `anomaly_detector_annotation_cadence_update_snapshot` were not yet defined.

Validation notes:

- The first post-implementation harness run failed because the snapshot helper
  only copied boxes when visibility changed, missing same-visible refresh at
  cadence boundaries.
- The second post-implementation harness run failed because boundary refresh
  copied boxes but did not record the boundary frame ordinal.
- The final helper copies desired boxes and records the frame ordinal when
  visible desired boxes arrive at an allowed cadence boundary.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3661 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 173 only adds unused facade snapshot helpers. It does
  not alter existing config flow, valid-frame processing, invalid-input result
  initialization, scoring, sampling, registration, MovementEstimator behavior,
  result boxes, overlay drawing, threading, or published result values.

## Packet 174 - Detector Facade Stable Process Output Annotations

Status: parent-validated.

Mode: detector facade stable processed-output annotation contract,
behavior-preserving, after Packet 173.

Scope:

- Added `anomaly_detector_process_output_apply_annotation_cadence(...)`.
- The helper takes a desired `anomaly_detector_process_output_t`, a cadence
  snapshot state, a frame ordinal, and a cadence window.
- It preserves the frame output by value.
- It replaces only `output.annotations` with the
  `anomaly_detector_annotation_cadence_update_snapshot(...)` result.
- This gives standalone adapters a direct stable processed-output view without
  manually composing the annotation snapshot helper.
- Added focused native tests for frame preservation, first snapshot,
  within-window hold, boundary refresh, within-window disappearance hold,
  boundary clear, and null snapshot state behavior.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the stable
  processed-output annotation helper.

Behavior preserved:

- This packet only adds an unused facade composition helper.
- It does not process frames, allocate output storage, copy RGBA pixels, draw
  overlays, publish result boxes, or route existing app/bridge/harness process
  paths through the helper.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_output_apply_annotation_cadence`
  was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3668 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 174 only adds an unused facade composition helper. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

## Packet 175 - Detector Facade One-Frame Stable Output

Status: parent-validated.

Mode: detector facade one-frame stable processed-output contract,
behavior-preserving, after Packet 174.

Scope:

- Added `anomaly_detector_process_frame_apply_annotation_cadence(...)`.
- The helper takes structured process args, a cadence snapshot state, a frame
  ordinal, a cadence window, and an optional box-count output.
- It processes the frame with `anomaly_detector_process_frame(...)`.
- It preserves the raw detector box count in `box_count_out`.
- It returns the processed frame output with annotations stabilized through
  `anomaly_detector_process_output_apply_annotation_cadence(...)`.
- Added focused native tests for frame preservation, raw detector count
  reporting, within-window held annotations, and null input behavior.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the one-frame stable
  processed-output helper.

Behavior preserved:

- This packet only adds an unused facade composition helper.
- It does not allocate output storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  helper.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_process_frame_apply_annotation_cadence`
  was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3673 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 175 only adds an unused facade composition helper. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

## Packet 176 - Detector Facade Owned Standalone Runtime

Status: parent-validated.

Mode: detector facade owned standalone runtime contract,
behavior-preserving, after Packet 175.

Scope:

- Added `anomaly_detector_runtime_t`.
- Added `anomaly_detector_runtime_init(...)`.
- Added `anomaly_detector_runtime_cleanup(...)`.
- Added `anomaly_detector_runtime_process_frame(...)`.
- The runtime owns detector state, realtime default config, result storage,
  annotation cadence snapshot state, frame ordinal, cadence window, and the
  last raw detector box count.
- Runtime processing builds structured process args internally and delegates to
  `anomaly_detector_process_frame_apply_annotation_cadence(...)`.
- Runtime processing stores the raw detector count, returns the
  cadence-stabilized process output, and advances the frame ordinal after each
  frame.
- Added focused native tests for realtime default initialization, cadence
  default setup, frame ordinal initialization/advancement, raw box-count
  storage, cadence-held annotations, and null safety.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the owned standalone
  runtime facade.

Behavior preserved:

- This packet only adds an unused standalone runtime facade.
- It does not allocate output image storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  runtime.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_runtime_init`,
  `anomaly_detector_runtime_cleanup`, and
  `anomaly_detector_runtime_process_frame` were not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3685 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 176 only adds an unused standalone runtime facade. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

## Packet 177 - Detector Runtime Supplied Config Initialization

Status: parent-validated.

Mode: detector runtime supplied-config initialization contract,
behavior-preserving, after Packet 176.

Scope:

- Added `anomaly_detector_runtime_init_with_config(...)`.
- The helper initializes an owned runtime from a caller-supplied
  `anomaly_detector_config_t` and explicit annotation cadence window.
- It copies the supplied config.
- It initializes owned detector state, clears owned result storage, resets the
  annotation cadence snapshot state, starts the frame ordinal at zero, clamps
  invalid cadence windows to one frame, and clears the last raw detector box
  count.
- Null runtime or null config inputs are no-ops.
- Added focused native tests for supplied-config copying, cadence storage,
  runtime bookkeeping reset, invalid-cadence clamp, and null-config no-op
  behavior.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the supplied-config
  runtime initializer.

Behavior preserved:

- This packet only adds an unused runtime initialization helper.
- It does not allocate output image storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  runtime.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- First `cmake --build tools/anomaly_test/build_timing` red attempt failed
  early because the test used a non-existent movement-estimator enum name.
  The test was corrected to use
  `ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE`.
- `cmake --build tools/anomaly_test/build_timing` then failed after
  test/header wiring because
  `anomaly_detector_runtime_init_with_config` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3691 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 177 only adds an unused runtime initialization helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 178 - Detector Runtime Config Apply Contract

Status: parent-validated.

Mode: detector runtime controlled config-apply contract,
behavior-preserving, after Packet 177.

Scope:

- Added `anomaly_detector_runtime_apply_config(...)`.
- The helper classifies the current runtime config versus a caller-supplied
  config with `anomaly_config_transition_classify(...)`.
- It applies the supplied config and cadence window.
- It returns the transition classification to the caller.
- Unchanged, display-only, debug-only, and live-update transitions preserve the
  owned runtime sequence state.
- Reset-sensitive transitions call `anomaly_detector_state_reset(...)` and
  clear owned result storage, annotation cadence snapshot state, frame ordinal,
  and the last raw detector box count.
- Null runtime or null config inputs return
  `ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE` and leave runtime state
  unchanged.
- Added focused native tests for display-only continuity, reset-sensitive
  clearing, cadence clamp during apply, null-config no-op behavior, and null
  runtime classification.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the controlled
  runtime config-apply helper.

Behavior preserved:

- This packet only adds an unused runtime config-apply helper.
- It does not allocate output image storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  runtime.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_runtime_apply_config` was not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3700 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 178 only adds an unused runtime config-apply helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 179 - Detector Runtime Process Result Metadata

Status: parent-validated.

Mode: detector runtime process-result metadata contract,
behavior-preserving, after Packet 178.

Scope:

- Added `anomaly_detector_runtime_process_result_t`.
- Added `anomaly_detector_runtime_process_frame_result(...)`.
- The result includes the processed output, processed frame ordinal, raw
  detector box count, stable annotation count, cadence window, and stabilized
  annotation visibility.
- The helper reports the frame ordinal used for the just-processed frame and
  advances the runtime ordinal afterward.
- Null runtime inputs return empty output plus sentinel metadata.
- The existing `anomaly_detector_runtime_process_frame(...)` helper delegates
  to the richer result helper and returns `.output`, preserving the output-only
  call shape for existing/future simple adapters.
- Added focused native tests for ordinal reporting/advance, raw detector count,
  cadence window, processed frame output, stable annotation count/visibility,
  and null-runtime metadata.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the process-result
  metadata helper.

Behavior preserved:

- This packet only adds an unused runtime process-result helper and rewires the
  existing output-only runtime process helper to delegate to it.
- It does not allocate output image storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  runtime.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_runtime_process_frame_result` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3706 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 179 only adds an unused runtime process-result helper.
  It does not alter existing config flow, valid-frame processing,
  invalid-input result initialization, scoring, sampling, registration,
  MovementEstimator behavior, result boxes, overlay drawing, threading, or
  published result values.

## Packet 180 - Detector Runtime Read-Only Accessors

Status: parent-validated.

Mode: detector runtime read-only accessor contract, behavior-preserving, after
Packet 179.

Scope:

- Added `anomaly_detector_runtime_config(...)`.
- Added `anomaly_detector_runtime_result(...)`.
- Added `anomaly_detector_runtime_stable_annotations(...)`.
- Added `anomaly_detector_runtime_frame_ordinal(...)`.
- Added `anomaly_detector_runtime_cadence_frames(...)`.
- Added `anomaly_detector_runtime_last_box_count(...)`.
- The accessors expose current config, current result, stable annotation
  snapshot, next frame ordinal, cadence window, and last raw detector box count.
- Null runtime inputs return null pointers, empty annotation views, `-1` for
  frame ordinal, and zero for count/cadence scalars.
- Added focused native tests for non-null pointer identity, stable annotation
  view contents, scalar metadata, and null-safe defaults.
- Updated `docs/AnomalyDetector_API_Contracts.md` to name the runtime
  read-only observation helpers.

Behavior preserved:

- This packet only adds unused read-only runtime accessors.
- It does not allocate output image storage, copy RGBA pixels, change detector
  scoring, or route existing app/bridge/harness process paths through the
  runtime.
- Valid RGBA processing, invalid-input result initialization, scoring,
  sampling, registration, MovementEstimator behavior, result boxes, overlay
  drawing, threading, public API behavior for existing callers, and published
  result values are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because the six runtime accessor symbols were not yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3711 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet because Packet 180 only adds unused read-only runtime accessors. It
  does not alter existing config flow, valid-frame processing, invalid-input
  result initialization, scoring, sampling, registration, MovementEstimator
  behavior, result boxes, overlay drawing, threading, or published result
  values.

## Packet 181 - Android Bridge Stable Overlay Cadence

Status: parent-validated.

Mode: app-visible overlay cadence wiring, behavior-changing at the bridge
publication boundary, after Packet 180.

Scope:

- Added `anomaly_detector_result_apply_annotation_cadence(...)`.
- The helper applies the facade annotation snapshot cadence directly to
  `anomaly_result_t` boxes.
- Added a per-session FFmpeg bridge annotation cadence snapshot state.
- Normal bridge overlays now analyze the converted RGBA frame, restore a clean
  copy of that RGBA frame, then draw only cadence-stabilized annotation boxes
  before cloning an overlay frame.
- Hot-overlay and candidate-blob troubleshooting modes keep the legacy raw
  in-place draw path.
- Cadence state resets on session startup, anomaly tracking resets, and
  anomaly RGBA resource/dimension resets.
- Added focused native tests for result-box cadence behavior, including
  mid-window transient appearance suppression, boundary appearance,
  within-window disappearance hold, boundary clear, and null snapshot fallback.
- Updated `docs/AnomalyDetector_API_Contracts.md` to record Android bridge
  stable overlay cadence behavior.

Behavior changed:

- Normal app-visible anomaly overlays no longer appear/disappear directly from
  one-frame raw detector `box_count` changes.
- Raw detector scoring, sampling, registration, candidate extraction,
  MovementEstimator behavior, result box publication, timing/debug summaries,
  and threading are unchanged.
- Troubleshooting hot/candidate overlay modes keep raw frame-exact drawing.

TDD red check:

- Initial `cmake --build tools/anomaly_test/build_timing` failed too early
  because the helper was declared before the snapshot-state typedef.
- After moving the declaration below the typedef,
  `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_result_apply_annotation_cadence` was not
  yet defined.
- The first green run exposed the existing first-observation policy; the test
  was corrected to seed an already-initialized invisible cadence state for
  mid-window transient suppression.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3716 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 181 changes app-visible overlay publication cadence but does
  not alter detector scoring, sampling, registration, candidate extraction,
  MovementEstimator behavior, raw result boxes, or threading. Focused native
  cadence tests plus Android native compile covered the changed path.

## Packet 182 - Android Bridge Moving Stable Overlay Cadence

Status: parent-validated.

Mode: app-visible overlay cadence correction, behavior-changing at the bridge
publication boundary, after Packet 181.

Scope:

- Added
  `anomaly_detector_result_apply_annotation_visibility_cadence(...)`.
- The helper rate-limits annotation visibility changes but updates visible
  annotation box coordinates from the latest raw detector result on each
  analyzed frame.
- The Android FFmpeg bridge now uses the visibility-only cadence helper for
  normal operator overlays.
- The previous snapshot cadence helper remains unchanged for consumers that
  need frozen cadence snapshots.
- Added focused native regression coverage for a moving visible annotation:
  mid-window appearance is held invisible, boundary appearance is published,
  same-window motion follows the latest box, mid-window disappearance holds
  the last moving box, and boundary disappearance clears it.

Behavior changed:

- Normal app-visible anomaly overlays still avoid brief appear/disappear
  flashes, but an already-visible ROI marker can follow the moving detector box
  instead of staying frozen at the cadence-window snapshot coordinate.
- Raw detector scoring, sampling, registration, candidate extraction,
  MovementEstimator behavior, result box publication, timing/debug summaries,
  and threading are unchanged.
- Troubleshooting hot/candidate overlay modes keep raw frame-exact drawing.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because
  `anomaly_detector_result_apply_annotation_visibility_cadence` was not yet
  defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3721 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 182 changes app-visible overlay publication semantics at the
  bridge/facade boundary but does not alter detector scoring, sampling,
  registration, candidate extraction, MovementEstimator behavior, raw result
  boxes, or threading. Focused native cadence tests plus Android native compile
  covered the changed path.

## Packet 183 - Smoothed And Held Operator Overlay Publication

Status: parent-validated.

Mode: app-visible overlay publication correction, after Packet 182.

Scope:

- Added `anomaly_detector_annotation_cadence_snapshot_view(...)` so adapters can
  draw the current held annotation snapshot without advancing detector state.
- Updated
  `anomaly_detector_result_apply_annotation_visibility_cadence(...)` to smooth
  small same-algorithm coordinate jitter while annotations are visible.
- Large target jumps and algorithm changes snap to the latest raw box.
- The AD worker now draws the current held annotation snapshot onto skipped
  frames when analysis is bypassed but AD is still enabled for the same
  generation.
- Hot-overlay and candidate-blob troubleshooting modes continue to use raw
  frame-exact drawing and do not use held skipped-frame overlays.
- Added focused native tests for smoothing, large-jump snap behavior, and the
  public snapshot-view accessor.

Behavior changed:

- Normal app-visible ROI markers should move less erratically because small raw
  box jitter is eased at the display boundary.
- Normal app-visible ROI markers should blink off less often because skipped AD
  worker frames can reuse the held annotation snapshot instead of forwarding a
  bare frame.
- Raw detector scoring, sampling, registration, candidate extraction,
  MovementEstimator behavior, result box publication, timing/debug summaries,
  tracking state, and threading are unchanged.

TDD red check:

- `cmake --build tools/anomaly_test/build_timing` failed after test/header
  wiring because `anomaly_detector_annotation_cadence_snapshot_view` was not
  yet defined.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun for this
  packet. Packet 183 changes display publication smoothing and skipped-frame
  overlay hold behavior but does not alter detector scoring, sampling,
  registration, candidate extraction, MovementEstimator behavior, raw result
  boxes, or tracking state.

## Packet 184 - App-Display Replay And Stability Metrics

Status: parent-validated.

Mode: harness/reporting coverage for app-visible overlay publication, after
Packet 183.

Scope:

- Added `--app-display-output` to `anomaly_video_test`.
- In app-display mode the harness runs raw detector analysis once per frame,
  restores the clean decoded RGBA frame, applies the same facade
  visibility-cadence/ROI-smoothing helper used by the Android publication
  boundary, redraws only the displayed boxes, and writes those displayed boxes
  to the CSV/video artifacts.
- Summary JSON now records `output_stream` and `display_cadence_frames`.
- Added `tools/anomaly_test/analyze_display_stability.py` for repeatable
  display metrics from detection CSV output.
- Updated the harness README with the app-display replay and Red1 stability
  scoring commands.

Red1 evidence:

- App-display artifacts:
  - `/tmp/red1_app_display_annotated.mp4`
  - `/tmp/red1_app_display_detections.csv`
  - `/tmp/red1_app_display_summary.json`
  - `/tmp/red1_app_display_stability.json`
- Review score against `app/src/test/resources/vidcap/Red1.review.json`:
  precision `0.0`, recall `0.0`, true-positive annotations `0`,
  missed annotations `15`, false-positive annotations `4`.
- Stability metrics:
  visible-frame ratio `0.9026`, gap count `0`, short-gap count `0`,
  max jump `0.0296`, p95 jump `0.0087`.
- Interpretation: Packet 183 display publication removes objective blink gaps
  in this replay, but Red1 still follows the wrong reviewed object. The
  remaining Red1 problem is detector/target selection, not publication cadence.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- This packet adds harness/reporting coverage for app-visible output and does
  not alter Android runtime behavior, detector scoring, candidate extraction,
  MovementEstimator behavior, or target tracking.

## Packet 185 - App Color Frontend Parity Boundary

Status: parent-validated.

Mode: runtime/harness boundary correction after Packet 184.

Scope:

- Fixed `anomaly_video_test --app-defaults --app-appearance color` so the
  harness derives native `ANOMALY_COLOR_FRONTEND_FRESH_RGBA`, matching
  `AnomalyConfig.toNativeConfig(...)`.
- Preserved explicit `--color-frontend <legacy|fresh-rgba|fresh-yuv>`
  overrides for comparison experiments.
- Kept non-Color app-parity default frontend as legacy.
- Updated the standalone detector realtime default helper so a requested
  algorithm mask containing `ANOMALY_ALGO_COLOR` defaults to `fresh-rgba`;
  non-color masks still default to legacy.
- Updated the harness README with the app-parity color frontend policy.

Behavior changed:

- App-parity harness output now matches the app's configured Color frontend
  boundary instead of replaying the stale legacy native path.
- Future standalone detector default construction no longer gives Color
  consumers the stale legacy frontend by default.
- No color scoring, support-map, candidate extraction, MovementEstimator,
  target tracking, or overlay cadence algorithm was changed.

TDD red checks:

- After updating the native facade expectation and rebuilding,
  `tools/anomaly_test/build_timing/anomaly_test` failed with:
  `detector facade default config: color algorithm uses fresh RGBA frontend`.
- Before the harness fix, a short Red1 app-parity replay printed
  `color = legacy` for `--app-defaults --app-appearance color`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- Short Red1 app-parity replay now prints `color = fresh-rgba`.
- Full Red1 app-display replay with app motion enabled:
  - Command used `--app-defaults --app-appearance color --app-motion on
    --app-saliency off --app-display-output`.
  - Review score: precision `1.0`, recall `0.5333333333333333`,
    true-positive annotations `8`, missed annotations `7`,
    false-positive annotations `0`.
  - Stability: visible-frame ratio `0.9026`, gap count `0`,
    short-gap count `0`, primary target-track max jump `0.0105`, primary
    target-track p95 jump `0.0042`.
  - Artifacts:
    `/tmp/red1_app_display_fresh_annotated.mp4`,
    `/tmp/red1_app_display_fresh_detections.csv`,
    `/tmp/red1_app_display_fresh_summary.json`,
    `/tmp/red1_app_display_fresh_stability.json`.

## Packet 186 - App Config Frontend Log Guardrail

Status: parent-validated.

Mode: observability/runtime-boundary guardrail after Packet 185.

Scope:

- Audited the runtime startup boundary:
  - Native render sessions start with anomaly processing disabled.
  - AD worker startup is gated by `anomaly_processing_enabled_locked(...)`.
  - Kotlin applies `NativeAnomalyConfig` to existing/new render sessions via
    `FfmpegBridge.updateAnomalyConfig(...)`.
  - The native bridge already logs `colorFrontend` when enabled local-file
    config is applied.
- Added `colorFrontend=<native value>` to the existing app-side
  `FfmpegProbeService` troubleshooting config logs for both normal apply and
  new-session apply paths.

Behavior changed:

- Troubleshooting logs now expose the Color frontend value before the JNI call.
- No detector behavior, app policy, startup gating, scoring, MovementEstimator,
  target tracking, harness logic, threading, or overlay cadence changed.

Validation:

- `./gradlew :app:compileDebugKotlin`: passed.

## Packet 187 - Annotation Publication Module Extraction

Status: parent-validated.

Mode: behavior-preserving facade/module extraction after Packet 186.

Scope:

- Added `app/src/main/cpp/anomaly_detector_annotation.h` as the focused
  annotation publication contract.
- Added `app/src/main/cpp/anomaly_detector_annotation.c` and moved the
  existing annotation visibility cadence, snapshot, view normalization, and
  small same-algorithm display-box smoothing helpers out of
  `anomaly_detector.c`.
- Kept `anomaly_detector.h` as the umbrella detector facade by including the
  new annotation header.
- Added the new native source file to:
  - `app/src/main/cpp/CMakeLists.txt`.
  - `tools/anomaly_test/CMakeLists.txt` for both `anomaly_test` and
    `anomaly_video_test`.

Behavior changed:

- No runtime detector behavior changed. This packet only moves the existing
  annotation publication helpers behind their own module boundary.
- Existing Android and harness call sites continue using the same facade
  functions and data contracts.

TDD red check:

- Added `#include "anomaly_detector_annotation.h"` to the native harness.
- Before adding the header/module, `cmake --build tools/anomaly_test/build_timing`
  failed with `fatal error: 'anomaly_detector_annotation.h' file not found`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3728 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full Red1/app-display replay and performance manifests were not rerun. The
  packet is an annotation publication helper extraction only and does not
  alter scoring, candidate extraction, support-map logic, sampling-state
  lifecycle, MovementEstimator behavior, target tracking, or app policy.

## Packet 188 - Runtime Budget Contract Foundation

Status: parent-validated.

Mode: behavior-preserving standalone runtime contract plumbing after Packet 187.

Scope:

- Added `anomaly_runtime_budget.{h,c}` as the focused owner for the runtime
  budget contract.
- Added `anomaly_detector_runtime_budget_t` and
  `anomaly_detector_processing_mode_t`, included from the detector facade.
- Added pure helpers for default budget construction, threshold normalization,
  Cursory/Thorough mode selection, and mode naming.
- Kept the policy free of Android, FFmpeg, JNI, render queues, scoring,
  MotionEstimator behavior, candidate extraction, support maps, target
  tracking, and threading side effects.
- Kept `anomaly_detector.c` out of the runtime-budget implementation so the
  umbrella facade does not become the runtime-policy owner.

Validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3773 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun. This packet
  adds a pure runtime-budget policy contract only and does not alter detector
  scoring, candidate extraction, support-map logic, sampling-state lifecycle,
  MovementEstimator behavior, target tracking, threading, or app policy.

## Packet 189 - Runtime Pressure Policy Extraction

Status: parent-validated.

Mode: behavior-preserving live AD worker pressure-policy extraction after
Packet 188.

Scope:

- Added `anomaly_runtime_pressure.{h,c}` as the focused owner for queue
  pressure thresholds, default policy values, pressure mode selection, bypass
  decisions, and pressure mode names.
- Routed the existing `ffmpeg_bridge.c` pressure helper wrappers through the
  standalone module while keeping queue storage, locks, condition variables,
  worker lifecycle, AVFrame ownership, render forwarding, and pressure logging
  in the bridge.
- Added native harness coverage for threshold rounding/clamping, default policy
  values, mode transitions and recovery hysteresis, bypass decisions, and mode
  names.
- Added the new source file to:
  - `app/src/main/cpp/CMakeLists.txt`.
  - `tools/anomaly_test/CMakeLists.txt` for both `anomaly_test` and
    `anomaly_video_test`.

Behavior changed:

- No intended runtime behavior changed. This packet extracts the current live
  AD worker pressure policy into a host-testable module.
- Existing pressure fallback still forwards frames without analysis under live
  pressure and keeps the bridge-owned `"live-ad-pressure"` reason.

TDD red check:

- Added `#include "anomaly_runtime_pressure.h"` plus pressure-policy tests to
  the native harness.
- Before adding the header/module, `cmake --build tools/anomaly_test/build_timing`
  failed with `fatal error: 'anomaly_runtime_pressure.h' file not found`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3807 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun. This packet
  extracts existing queue-pressure policy only and does not alter detector
  scoring, candidate extraction, support-map logic, sampling-state lifecycle,
  MotionEstimator behavior, target tracking, AVFrame forwarding, or app policy.

## Packet 190 - Runtime Frame Handoff Contract

Status: parent-validated.

Mode: behavior-preserving AD worker handoff contract after Packet 189.

Scope:

- Added `anomaly_runtime_handoff.{h,c}` as the focused owner for pure AD worker
  frame metadata readiness, stale-generation checks, analyze/forward decisions,
  and handoff reason names.
- Routed the existing `ffmpeg_bridge.c` AD worker skip/analyze branch through
  the standalone helper using metadata derived from the dequeued packet.
- Kept queue storage, pthread synchronization, `AVFrame` ownership, overlay
  frame ownership, render forwarding, counters, cleanup, and app logging in
  `ffmpeg_bridge.c`.
- Added native harness coverage for frame readiness, generation staleness,
  analyze/forward decisions, and handoff reason names.
- Added the new source file to:
  - `app/src/main/cpp/CMakeLists.txt`.
  - `tools/anomaly_test/CMakeLists.txt` for both `anomaly_test` and
    `anomaly_video_test`.

Behavior changed:

- No intended runtime behavior changed. This packet names the current AD worker
  handoff decision behind a host-testable module.
- This packet does not split MotionEstimator into a separate thread or add an
  ME evidence queue.

TDD red check:

- Added `#include "anomaly_runtime_handoff.h"` plus handoff-policy tests to the
  native harness.
- Before adding the header/module, `cmake --build tools/anomaly_test/build_timing`
  failed with `fatal error: 'anomaly_runtime_handoff.h' file not found`.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3824 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts the current AD worker handoff decision only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, AVFrame
  forwarding, or app policy.

## Packet 191 - Runtime Handoff Outcome Contract

Status: parent-validated.

Mode: behavior-preserving AD worker outcome-delta contract after Packet 190.

Scope:

- Extended `anomaly_runtime_handoff.{h,c}` with
  `anomaly_runtime_handoff_outcome_t` and
  `anomaly_runtime_handoff_outcome_for_decision(...)`.
- The helper returns pure deltas for AD worker processed, skipped,
  forwarded-without-analysis, and annotated counters.
- Routed the existing `ffmpeg_bridge.c` AD worker counter increments through
  the helper while keeping the actual session counters in the bridge.
- Kept queue storage, pthread synchronization, `AVFrame` ownership, overlay
  frame ownership, render forwarding, cleanup, and app logging in
  `ffmpeg_bridge.c`.
- Added native harness coverage for analyzed, annotated, forwarded, and unknown
  action outcomes.

Behavior changed:

- No intended runtime behavior changed. This packet names the current AD worker
  outcome counter policy behind a host-testable helper.
- This packet does not split MotionEstimator into a separate thread or add an
  ME evidence queue.

TDD red check:

- Added outcome-policy tests to the native harness.
- Before adding the type/helper, `cmake --build tools/anomaly_test/build_timing`
  failed because `anomaly_runtime_handoff_outcome_t` and
  `anomaly_runtime_handoff_outcome_for_decision(...)` were missing.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3828 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts the current AD worker counter-delta policy only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, AVFrame
  forwarding, or app policy.

## Packet 192 - Runtime Pressure Backlog Capacity Contract

Status: parent-validated.

Mode: behavior-preserving local-file AD sidecar queue-capacity contract after
Packet 191.

Scope:

- Extended `anomaly_runtime_pressure.{h,c}` with
  `anomaly_runtime_pressure_backlog_frame_capacity(...)`.
- The helper converts a millisecond backlog budget and source-frame interval
  into a bounded frame capacity with explicit default interval, minimum-frame,
  and hard-cap inputs.
- Routed `ffmpeg_bridge.c` local-file AD sidecar queue capacity through the
  helper, preserving the existing 500 ms budget, render default source
  interval fallback, 2-frame minimum, and `AD_INPUT_QUEUE_HARD_CAPACITY` clamp.
- Kept queue storage, old-frame dropping, frame ownership, counters, trace
  counters, locks, and logging in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the current local AD
  sidecar backlog-capacity arithmetic behind a host-testable runtime pressure
  helper.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_runtime_pressure_backlog_frame_capacity(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_runtime_pressure_backlog_frame_capacity` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3834 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue-capacity arithmetic only and does
  not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, AVFrame
  forwarding, threading, or app policy.

## Packet 193 - Runtime Pressure Local Admission Drop Contract

Status: parent-validated.

Mode: behavior-preserving local-file AD sidecar frame-admission contract after
Packet 192.

Scope:

- Extended `anomaly_runtime_pressure.{h,c}` with
  `anomaly_runtime_pressure_oldest_drop_count_for_admission(...)`.
- The helper returns how many oldest queued frames should be dropped before
  admitting one new frame at a desired queue depth.
- Routed `ffmpeg_bridge.c` local-file AD sidecar admission through the helper.
- Kept queue storage, `AVFrame` cleanup, queue-head advancement, depth
  mutation, counters, trace counters, locks, and logging in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the current local AD
  sidecar old-frame admission arithmetic behind a host-testable runtime
  pressure helper.
- The preserved policy is: when the queue is at or above the desired depth,
  drop enough oldest frames so the newly admitted frame lands at the desired
  depth.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_runtime_pressure_oldest_drop_count_for_admission(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_runtime_pressure_oldest_drop_count_for_admission` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3841 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local sidecar admission arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 194 - Runtime Pressure Explicit Policy Constructor

Status: parent-validated.

Mode: behavior-preserving live AD pressure-policy construction contract after
Packet 193.

Scope:

- Extended `anomaly_runtime_pressure.{h,c}` with
  `anomaly_runtime_pressure_policy_make(...)`.
- The explicit constructor accepts queue capacity, recover depth, analyze
  alternate threshold, bypass alternate threshold, and bypass-all threshold.
- `anomaly_runtime_pressure_policy_make_default(...)` now delegates to the
  explicit constructor with the existing default values.
- Routed `ffmpeg_bridge.c` live AD pressure policy construction through the
  explicit helper, preserving the bridge-owned adapter constants while moving
  policy object construction into the runtime pressure contract.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing pressure
  policy construction as a host-testable runtime pressure helper.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_runtime_pressure_policy_make(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_runtime_pressure_policy_make` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3843 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing pressure-policy construction only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 195 - Runtime Pressure Queue Storage Capacity Contract

Status: parent-validated.

Mode: behavior-preserving AD input queue capacity contract after Packet 194.

Scope:

- Extended `anomaly_runtime_pressure.{h,c}` with
  `anomaly_runtime_pressure_queue_storage_capacity(...)`.
- The helper owns the pure bounded queue storage-capacity calculation for AD
  input queue allocation.
- Routed `ffmpeg_bridge.c` AD input queue capacity selection through the helper.
- Kept allocation, queue copy/move, `AVFrame` ownership, queue storage, locks,
  counters, and traces in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing bounded
  AD input queue capacity arithmetic behind a host-testable runtime pressure
  helper.
- The preserved policy is: normalize requested minimum capacity to at least 1,
  reject requests above the hard cap, keep existing capacity when sufficient,
  start new allocations at the initial capacity, double capacity below 4096,
  grow by 1024 above that, and clamp to the hard cap.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_runtime_pressure_queue_storage_capacity(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_runtime_pressure_queue_storage_capacity` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3850 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing AD input queue storage-capacity
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, `AVFrame` forwarding, threading, or app policy.

## Packet 196 - Runtime Budget Render Trim Keep-Latest Contract

Status: parent-validated.

Mode: behavior-preserving render queue trim arithmetic contract after Packet
195.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_trim_keep_latest_frames(...)`.
- The helper owns the pure keep-latest frame count calculation used by render
  queue trimming.
- Routed `ffmpeg_bridge.c` render trim keep-latest selection through the helper.
- Kept render queue storage, trim mutation, `AVFrame` cleanup, counters,
  traces, locks, and logging in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  trim keep-latest arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: use explicit source interval and target latency
  when positive, otherwise fall back through the session/default timing values,
  round up target latency by source interval, and clamp the keep-latest result
  to the existing 8..36 frame bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_trim_keep_latest_frames(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_trim_keep_latest_frames` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3857 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue trim arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 197 - Runtime Budget Render Queue Hard-Cap Contract

Status: parent-validated.

Mode: behavior-preserving render queue hard-cap arithmetic contract after
Packet 196.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_queue_hard_cap(...)`.
- The helper owns the pure hard-cap frame count calculation used by render
  queue sizing.
- Routed `ffmpeg_bridge.c` render queue hard-cap selection through the helper
  after deriving the existing fallback keep-latest value from the session.
- Kept render queue allocation, storage, mutation, `AVFrame` cleanup, counters,
  traces, locks, and logging in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  queue hard-cap arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: if the supplied keep-latest value is below the
  minimum, use the existing session fallback keep-latest value; if that is also
  invalid, use the minimum keep-latest value. Then size the hard cap as the
  maximum of double keep-latest, keep-latest plus the extra frame margin, and
  the minimum hard cap, clamped to the maximum hard cap.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_render_queue_hard_cap(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_render_queue_hard_cap` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3865 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue hard-cap arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 198 - Runtime Budget Target Latency Contract

Status: parent-validated.

Mode: behavior-preserving target latency arithmetic contract after Packet 197.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_target_latency_ms(...)`.
- The helper owns the pure bounded target latency calculation used by the render
  runtime.
- Routed `ffmpeg_bridge.c` target latency selection through the helper using
  the bridge-owned stall estimate, proven gap, stall floor, processing margin,
  and target latency bounds.
- Kept stall/gap measurement, session state, render queue ownership, counters,
  traces, locks, logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing target
  latency arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: use the positive stall estimate or the stall floor,
  let a larger proven gap override it, double that value, add the processing
  margin, and clamp to the existing target latency min/max bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_target_latency_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_target_latency_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3872 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing target latency arithmetic only and does
  not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 199 - Runtime Budget Source Interval Estimate Contract

Status: parent-validated.

Mode: behavior-preserving decode-delta source interval estimate contract after
Packet 198.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_source_interval_estimate_t` and
  `anomaly_detector_runtime_budget_update_source_interval_estimate(...)`.
- The helper owns the pure interval estimate arithmetic and confidence update
  used when decode deltas are admitted as source cadence samples.
- Routed `ffmpeg_bridge.c` decode-delta source interval updates through the
  helper using the bridge-owned current interval, confidence, decode delta,
  default interval, EMA percent, confidence step, and interval bounds.
- Kept cadence sample collection, decode/PTS observation, session state, render
  queue ownership, counters, traces, locks, logging, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  decode-delta source interval estimate arithmetic behind a host-testable
  runtime budget helper.
- The preserved policy is: use the positive current interval or default
  interval as the prior estimate; if confidence is not positive, trust the
  decode delta directly; otherwise apply the existing weighted EMA with
  rounding; clamp the interval to the existing min/max bounds; and increment
  confidence by 5 up to 100.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_update_source_interval_estimate(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_update_source_interval_estimate` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3880 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing source interval estimate arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 200 - Runtime Budget PTS Source Interval Contract

Status: parent-validated.

Mode: behavior-preserving PTS-derived source interval application contract
after Packet 199.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_apply_pts_source_interval(...)`.
- The helper owns the pure interval application arithmetic and confidence floor
  update used when PTS-derived cadence samples are applied.
- Routed `ffmpeg_bridge.c` PTS source interval application through the helper
  using the bridge-owned current interval, confidence, PTS interval,
  force-direct flag, default interval, confidence thresholds, blend
  percentages, and interval bounds.
- Kept PTS observation, cadence sample admission, session state, render queue
  ownership, counters, traces, locks, logging, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  PTS-derived source interval application arithmetic behind a host-testable
  runtime budget helper.
- The preserved policy is: force-direct or low-confidence observations use the
  PTS interval directly; otherwise choose the near/far blend percentage from
  the interval delta, apply the weighted average with rounding, nudge one
  millisecond toward the PTS interval when rounding stalls, clamp to the
  existing min/max bounds, and raise confidence to at least 80.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_apply_pts_source_interval(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_apply_pts_source_interval` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3888 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing PTS source interval application
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior, target
  tracking, `AVFrame` forwarding, threading, or app policy.

## Packet 201 - Runtime Budget Stall Estimate Contract

Status: parent-validated.

Mode: behavior-preserving inter-burst stall estimate arithmetic contract after
Packet 200.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_update_stall_estimate_ms(...)`.
- The helper owns the pure asymmetric EMA calculation used when observed decode
  gaps update the render runtime stall estimate.
- Routed `ffmpeg_bridge.c` stall estimate updates through the helper using the
  bridge-owned current stall estimate, observed gap, stall floor, rise/decay
  EMA percentages, and maximum stall bound.
- Kept gap observation, timestamp updates, session state, render queue
  ownership, counters, traces, locks, logging, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  inter-burst stall estimate arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: use the stall floor when the current estimate is
  invalid, use the rise EMA when the observed gap is at or above the current
  estimate, use the decay EMA otherwise, round the weighted average, and clamp
  to the existing stall floor and maximum target latency bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_update_stall_estimate_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_update_stall_estimate_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3896 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing stall estimate arithmetic only and does
  not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 202 - Runtime Budget Proven Gap Contract

Status: parent-validated.

Mode: behavior-preserving proven-gap update arithmetic contract after Packet
201.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_update_proven_gap_ms(...)`.
- The helper owns the pure largest-gap/blended-gap calculation used when
  observed decode gaps update the render runtime proven-gap estimate.
- Routed `ffmpeg_bridge.c` proven-gap updates through the helper using the
  bridge-owned current proven gap, observed gap, stall floor, blend EMA
  percentage, and maximum gap bound.
- Kept gap observation, trigger guards, timestamp updates, session state,
  render queue ownership, counters, traces, locks, logging, and `AVFrame`
  ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  proven-gap update arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: use the stall floor when the current proven gap is
  invalid, adopt larger observed gaps directly, blend smaller observed gaps
  with the existing 15% EMA, round the weighted average, and clamp to the
  existing stall floor and maximum target latency bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_update_proven_gap_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_update_proven_gap_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3904 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing proven-gap update arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 203 - Runtime Budget Gap Decay Contract

Status: parent-validated.

Mode: behavior-preserving stale stall/proven-gap decay arithmetic contract after
Packet 202.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decay_toward_floor_ms(...)`.
- The helper owns the pure decay-toward-floor weighted average used by both
  stale stall-estimate decay and stale proven-gap decay.
- Routed `ffmpeg_bridge.c` stall-estimate and proven-gap decay arithmetic
  through the helper using bridge-owned current values, floor, and decay EMA
  percentages.
- Kept grace/interval guards, timestamp updates, debug-log thresholds, session
  state, render queue ownership, counters, traces, locks, logging, and
  `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  stale-gap decay arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: blend the current value toward the configured floor
  using the supplied EMA percentage, round the weighted average, and clamp to
  the floor.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_decay_toward_floor_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_decay_toward_floor_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3911 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing stall/proven-gap decay arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 204 - Runtime Budget Render Interval Contract

Status: parent-validated.

Mode: behavior-preserving live render interval controller arithmetic contract
after Packet 203.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_desired_render_interval_ms(...)`.
- The helper owns the pure proportional adjustment, backlog-ratio fast-path
  caps, active-stall preserve rule, source-relative interval clamps, and
  smoothing EMA used to choose the live render interval.
- Routed `ffmpeg_bridge.c` live render interval arithmetic through the helper
  using bridge-owned source interval, previous smoothed interval, buffered span,
  target latency, stall-active flag, and existing percentage constants.
- Kept local-file fast paths, PTS relock, stall-active updates, session state,
  render queue ownership, counters, traces, locks, logging, and `AVFrame`
  ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing live
  render interval controller arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: calculate proportional adjustment from buffered-span
  error, cap faster adjustment by backlog ratio, preserve a slower interval
  during active stalls near the target, clamp to the existing source-relative
  interval bounds, and smooth toward the desired interval with the existing EMA.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_desired_render_interval_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_desired_render_interval_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3917 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render interval controller arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  `AVFrame` forwarding, threading, or app policy.

## Packet 205 - Runtime Budget Decode Delta Classification Contract

Status: parent-validated.

Mode: behavior-preserving decode-delta classification contract after Packet
204.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decode_delta_is_gap(...)` and
  `anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(...)`.
- The helpers own the pure threshold arithmetic used to decide whether an
  observed decode delta is a render gap and whether a decode/PTS delta is a
  plausible source-cadence sample.
- Routed `ffmpeg_bridge.c` decode-delta classification wrappers through the
  helpers using the bridge-owned current source interval and existing render
  cadence constants.
- Kept sample collection, timestamp updates, PTS relock decisions, session
  state, render queue ownership, counters, traces, locks, logging, local-file
  fast paths, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  decode-delta gap and plausible-cadence classification arithmetic behind
  host-testable runtime budget helpers.
- The preserved gap policy is: reject deltas below the configured gap floor,
  otherwise compare against the rounded seven-quarter source-interval
  threshold clamped up to that floor.
- The preserved plausible-cadence policy is: reject deltas outside the absolute
  sample bounds, then compare against the rounded source-relative min/max
  percentage window clamped to the absolute sample bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_decode_delta_is_gap(...)` and
  `anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_decode_delta_is_gap` and
  `anomaly_detector_runtime_budget_decode_delta_is_plausible_cadence` symbols.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3929 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing decode-delta classification arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue mutation, `AVFrame` forwarding, threading, or app policy.

## Packet 206 - Runtime Budget Decode Stall Predicate Contract

Status: parent-validated.

Mode: behavior-preserving live decode-stall predicate contract after Packet
205.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_decode_stall_active(...)`.
- The helper owns the pure threshold predicate used by the live render
  controller to decide whether decode input has been quiet long enough to treat
  the stream as actively stalled.
- Routed `ffmpeg_bridge.c` live stall-active calculation through the helper
  using the bridge-owned current time, last decode timestamp, source interval,
  and existing gap-floor constant.
- Kept decode timestamp ownership, mutable `session->stall_active`, PTS relock
  decisions, render interval selection, session state, render queue ownership,
  counters, traces, locks, logging, local-file fast paths, and `AVFrame`
  ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  live-render decode-stall predicate behind a host-testable runtime budget
  helper.
- The preserved policy is: missing decode timestamps are inactive, and a live
  stall becomes active once `now_ms - last_decode_at_ms` reaches the greater of
  the configured gap floor and the source interval multiplied by the supplied
  threshold multiplier.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_decode_stall_active(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_decode_stall_active` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3935 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing decode-stall predicate arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue mutation, `AVFrame` forwarding, threading, or app policy.

## Packet 207 - Runtime Budget Current Render Interval Contract

Status: parent-validated.

Mode: behavior-preserving current render interval fallback/clamp contract after
Packet 206.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_current_render_interval_ms(...)`.
- The helper owns the pure fallback and clamp arithmetic used to choose the
  render interval currently visible to bridge scheduling callers.
- Routed `ffmpeg_bridge.c` current render interval selection through the helper
  using the bridge-owned smoothed interval, source interval, and existing
  default FPS/min/max interval constants.
- Kept the `ffmpeg_session_t` null guard, render scheduling, render queue
  ownership, source interval updates, smoothed interval mutation, counters,
  traces, locks, logging, local-file fast paths, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing current
  render interval fallback/clamp arithmetic behind a host-testable runtime
  budget helper.
- The preserved policy is: prefer the positive smoothed interval, otherwise use
  the positive source interval, otherwise use the rounded default-FPS interval,
  then clamp the selected interval to the configured min/max render interval
  bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_current_render_interval_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_current_render_interval_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3941 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing current render interval fallback/clamp
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior, target
  tracking, render queue mutation, `AVFrame` forwarding, threading, or app
  policy.

## Packet 208 - Runtime Budget PTS Interval Span Contract

Status: parent-validated.

Mode: behavior-preserving PTS interval-from-span arithmetic contract after
Packet 207.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_pts_interval_from_span_ms(...)`.
- The helper owns the pure span-to-interval arithmetic used after the bridge
  has traversed the render queue and found a valid increasing PTS span.
- Routed `ffmpeg_bridge.c` `queue_pts_interval_ms_locked(...)` final
  calculation through the helper using bridge-derived first/last PTS values,
  valid timestamp count, and existing min/max render interval bounds.
- Kept render queue traversal, queue storage, timestamp filtering,
  monotonicity validation, max-sample selection, PTS relock decisions, session
  state, counters, traces, locks, logging, local-file fast paths, and
  `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing PTS
  interval-from-span arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: require at least two valid increasing timestamps,
  calculate `last_pts_us - first_pts_us`, divide by `valid_count - 1` frame
  gaps with millisecond rounding, then clamp to the configured render interval
  bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_pts_interval_from_span_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_pts_interval_from_span_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3948 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing PTS interval-from-span arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue traversal/mutation, `AVFrame` forwarding, threading, or app policy.

## Packet 209 - Runtime Budget Buffered Span Contract

Status: parent-validated.

Mode: behavior-preserving buffered-span conversion contract after Packet 208.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_buffered_span_ms(...)`.
- The helper owns the pure first/last PTS span conversion used after the bridge
  has traversed the render queue and found a monotonic buffered PTS span.
- Routed `ffmpeg_bridge.c` `buffered_span_ms_locked(...)` final calculation
  through the helper using bridge-derived first/last PTS values.
- Kept render queue traversal, queue storage, timestamp filtering,
  monotonicity validation, session state, counters, traces, locks, logging,
  local-file fast paths, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  buffered-span conversion behind a host-testable runtime budget helper.
- The preserved policy is: reject missing or non-increasing PTS endpoints, then
  convert `last_pts_us - first_pts_us` from microseconds to milliseconds using
  integer truncation.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_buffered_span_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_buffered_span_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3953 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing buffered-span conversion arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  queue traversal/mutation, `AVFrame` forwarding, threading, or app policy.

## Packet 210 - Runtime Budget FPS Interval Contract

Status: parent-validated.

Mode: behavior-preserving FPS-to-render-interval arithmetic contract after
Packet 209.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_interval_from_fps(...)`.
- The helper owns the pure FPS-to-millisecond render interval conversion used
  after the bridge has validated an FFmpeg frame-rate rational.
- Routed `ffmpeg_bridge.c` `interval_from_fps(...)` through the helper using
  existing min/max render interval bounds.
- Kept FFmpeg `AVRational` validation, stream avg/r-frame-rate selection,
  source labels, session state, local-file setup, counters, traces, locks,
  logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  FPS-to-render-interval arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: reject FPS values at or below 1.0, calculate
  `1000.0 / fps`, round to the nearest millisecond, and clamp to the configured
  render interval bounds.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_interval_from_fps(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_interval_from_fps` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3960 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing FPS-to-render-interval arithmetic only
  and does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, FFmpeg
  stream probing, render queue traversal/mutation, `AVFrame` forwarding,
  threading, or app policy.

## Packet 211 - Runtime Budget Local Playback Target Interval Contract

Status: parent-validated.

Mode: behavior-preserving local-file playback target-interval arithmetic
contract after Packet 210.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_target_interval_ms(...)`.
- The helper owns the pure target interval selection used by local-file
  playback pacing after the bridge has computed the nominal interval and PTS
  delta.
- Routed `ffmpeg_bridge.c` `pace_local_file_playback(...)` target interval
  selection through the helper using bridge-owned nominal interval, PTS delta,
  default FPS, render interval bounds, and max reasonable interval cap.
- Kept local-file detection, pacing sleeps, pause/step state, PTS repair and
  history state, render timing samples, session state, counters, traces, locks,
  logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-file playback target-interval selection behind a host-testable runtime
  budget helper.
- The preserved policy is: if a nominal interval exists, only use a positive
  PTS delta when it falls between half nominal and twice nominal, with the low
  bound clamped to the render minimum and the high bound clamped to the
  configured max reasonable interval; if no nominal interval exists, use a
  positive PTS delta directly; otherwise fall back to the rounded default-FPS
  interval.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_target_interval_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_local_playback_target_interval_ms` symbol.

Parent validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3966 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed, with the existing
  `ScannerScreen.kt` `LocalClipboardManager` deprecation warning.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback target-interval arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback sleeps/state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

## Packet 212 - Runtime Budget Local Playback PTS Normalization Contract

Status: parent-validated.

Mode: behavior-preserving local-file playback PTS normalization arithmetic
contract after Packet 211.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_normalize_local_playback_pts_us(...)` and
  `anomaly_detector_runtime_budget_local_playback_pts_t`.
- The helper owns the pure local-file playback PTS normalization decision after
  the bridge has supplied raw PTS, last valid PTS, nominal interval, source
  interval, and default FPS.
- Routed `ffmpeg_bridge.c` `normalize_local_playback_pts_us(...)` through the
  helper while preserving bridge-owned local-source gating.
- Kept `last_valid_pts_us`, repair counters, repair log throttling, local-file
  detection, playback state, render timing state, session state, counters,
  traces, locks, logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-file playback PTS normalization arithmetic behind a host-testable
  runtime budget helper.
- The preserved policy is: choose nominal interval first, source interval
  second, and rounded default-FPS microsecond interval last; missing raw PTS
  advances from the last valid PTS when one exists without setting the repair
  flag; non-monotonic positive PTS advances from the last valid PTS and sets
  the repair flag; monotonic positive PTS passes through.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_normalize_local_playback_pts_us(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_normalize_local_playback_pts_us` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3972 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback PTS normalization
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, local-file playback state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

## Packet 213 - Runtime Budget Local Playback Timing Span Contract

Status: parent-validated.

Mode: behavior-preserving local-file playback timing-history index and
span-validity arithmetic contract after Packet 212.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_timing_indices(...)` and
  `anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(...)`.
- The index helper owns the pure oldest/newest ring-buffer index selection for
  local-playback timing snapshots after the bridge has supplied next, count,
  and capacity.
- The span helper owns the pure validity predicate for PTS/render timing spans
  after the bridge has read the candidate samples from its arrays.
- Routed `ffmpeg_bridge.c` `recent_local_playback_timing_span(...)` through
  both helpers.
- Kept timing sample arrays, sample recording, JNI debug-stat output slots,
  local-file playback state, session state, counters, traces, locks, logging,
  and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-file timing-history index and span-validity arithmetic behind
  host-testable runtime budget helpers.
- The preserved policy is: require at least two timing samples; select oldest
  and newest entries using the ring-buffer next/count/capacity values; reject
  missing or non-increasing PTS/render spans before filling debug-stat outputs.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_timing_indices(...)` and
  `anomaly_detector_runtime_budget_local_playback_timing_span_is_valid(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing timing helper symbols.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3983 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback timing-history index and
  span-validity arithmetic only and does not alter detector scoring, candidate
  extraction, support-map logic, sampling-state lifecycle, MotionEstimator
  behavior, target tracking, local-file playback state, render queue
  traversal/mutation, `AVFrame` forwarding, threading, or app policy.

## Packet 214 - Runtime Budget Local Playback History Slot Contract

Status: parent-validated.

Mode: behavior-preserving local-file playback frame-history replay slot
arithmetic contract after Packet 213.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_history_slot(...)` and
  `anomaly_detector_runtime_budget_local_playback_history_slot_t`.
- The helper owns the pure offset clamp and fixed-ring slot selection used by
  local-playback frame-history replay after the bridge has supplied next,
  count, requested offset, and capacity.
- Routed `ffmpeg_bridge.c` `clone_local_playback_history_frame_locked(...)`
  through the helper before reading the selected history slot.
- Kept frame-history storage, sample insertion, replay state, frame cloning,
  source timestamp output, local-file playback state, session state, counters,
  traces, locks, logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-file frame-history replay slot arithmetic behind a host-testable
  runtime budget helper.
- The preserved policy is: reject empty history; clamp negative offsets to the
  newest frame; clamp offsets past the available history to the oldest
  available frame; normalize the next index; and walk backward through the
  fixed-size ring buffer to select the replay slot.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_history_slot(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_local_playback_history_slot` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3992 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback frame-history replay slot
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, local-file playback state, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

## Packet 215 - Runtime Budget Local Playback Step Control Contract

Status: parent-validated.

Mode: behavior-preserving local-file playback forward/back step-control
arithmetic contract after Packet 214.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_step_forward(...)`,
  `anomaly_detector_runtime_budget_local_playback_step_back(...)`, and
  `anomaly_detector_runtime_budget_local_playback_step_t`.
- The forward helper owns the pure decision for walking from history replay
  toward live playback versus leaving replay mode and adding to the decoder
  step budget.
- The back helper owns the pure decision for moving the history offset toward
  the oldest available frame.
- Routed `ffmpeg_bridge.c` `nativeStepLocalPlayback(...)` and
  `nativeStepLocalPlaybackBack(...)` through the helpers before applying the
  result to bridge-owned state.
- Kept JNI entrypoints, local-file/session validation, pause state application,
  render-sync checks, render queue clearing, condition signaling, frame-history
  cloning, render calls, reset side effects, traces, locks, logging, and
  `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-file playback step-control arithmetic behind host-testable runtime
  budget helpers.
- The preserved policy is: a single forward step while replaying history walks
  one offset toward live history and renders from the stored frame; other
  forward steps leave replay mode, optionally request detector tracking reset,
  and saturating-add to the decoder step budget; back steps render only when
  history exists and clamp at the oldest available history frame.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_step_forward(...)` and
  `anomaly_detector_runtime_budget_local_playback_step_back(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing step helper symbols.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4000 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback step-control arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback state application, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

## Packet 216 - Runtime Budget Local Playback Ring Append Contract

Status: parent-validated.

Mode: behavior-preserving local-playback timing/history ring append arithmetic
contract after Packet 215.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_append(...)` and
  `anomaly_detector_runtime_budget_local_playback_append_t`.
- The helper owns the pure fixed-ring append decision: which slot to write,
  what the next index becomes, and what the capped count becomes.
- Routed `ffmpeg_bridge.c` `record_local_playback_timing_sample(...)` and
  `append_local_playback_history_locked(...)` through the helper before
  writing bridge-owned arrays/slots.
- Kept timing sample arrays, frame-history slots, cloned frames, slot clearing,
  sample payload writes, local playback state, counters, traces, locks,
  logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing
  local-playback ring append arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: write the current next slot, advance next modulo
  the fixed capacity, increment count until capacity, and keep count capped
  once the ring is full.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_append(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_local_playback_append` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4006 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback ring append arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  local-file playback state application, render queue traversal/mutation,
  `AVFrame` forwarding, threading, or app policy.

## Packet 217 - Runtime Budget Render Due-Time Advance Contract

Status: parent-validated.

Mode: behavior-preserving render dequeue due-time advance arithmetic contract
after Packet 216.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_advance_render_due_ms(...)`.
- The helper owns the pure next-render-due calculation after the bridge has
  rendered/dequeued a frame and supplied scheduled due time, render interval,
  and current time.
- Routed `ffmpeg_bridge.c` `dequeue_due_render_frame_locked(...)` through the
  helper when updating `session->next_render_due_ms`.
- Kept render queue storage, dequeue eligibility, frame transfer, latency
  counters, trace counters, queue-head/depth mutation, render thread state,
  locks, logging, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  due-time advance/skip arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: advance by one interval from the scheduled due time;
  if that next due time is still at or behind `now`, skip enough intervals to
  schedule the next future render tick.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_advance_render_due_ms(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_advance_render_due_ms` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4012 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render due-time advance arithmetic only
  and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue traversal/mutation, `AVFrame` forwarding, threading, or app
  policy.

## Packet 218 - Runtime Budget Queue Tail Index Contract

Status: parent-validated.

Mode: behavior-preserving render/AD queue tail-index arithmetic contract after
Packet 217.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_tail_index(...)`.
- The helper owns the pure tail-index calculation shared by render queue and
  AD input queue enqueue paths.
- Routed `ffmpeg_bridge.c` `render_queue_tail_index(...)` and
  `ad_input_queue_tail_index(...)` through the helper.
- Kept render/AD queue storage, capacity management, enqueue mutation, dequeue
  mutation, frame cloning, trace counters, locks, logging, and `AVFrame`
  ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing queue
  tail-index arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: return zero for invalid capacity, otherwise compute
  the tail slot from head plus depth modulo capacity. The helper also
  normalizes malformed negative or oversized inputs for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_queue_tail_index(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_queue_tail_index` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4019 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue tail-index arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  or AD queue traversal/mutation, `AVFrame` forwarding, threading, or app
  policy.

## Packet 219 - Runtime Budget Queue Pop State Contract

Status: parent-validated.

Mode: behavior-preserving render/AD queue pop-state arithmetic contract after
Packet 218.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_pop_state(...)`.
- The helper owns the pure head/depth transition shared by render stale-drop,
  render dequeue, AD input dequeue, and local AD sidecar oldest-drop paths.
- Routed those `ffmpeg_bridge.c` state updates through the helper.
- Kept render/AD queue storage, slot clearing, frame transfer,
  enqueue/dequeue/drop eligibility, trace counters, locks, logging, and
  `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing queue
  pop-state arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: advance head by one modulo capacity, decrement
  depth, and reset head to zero when depth reaches zero. The helper also
  normalizes malformed negative or oversized inputs for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_queue_pop_state(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_queue_pop_state` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4027 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue pop-state arithmetic only and does
  not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  or AD queue storage, slot clearing, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

## Packet 220 - Runtime Budget Queue Trim State Contract

Status: parent-validated.

Mode: behavior-preserving render queue trim-state arithmetic contract after
Packet 219.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_trim_state(...)`.
- The helper owns the pure live-edge trim drop-count and resulting head/depth
  transition for render queue trimming.
- Routed `ffmpeg_bridge.c` `trim_render_queue_to_latest(...)` through the
  helper.
- Kept render queue storage, slot clearing, trim eligibility, drop-count
  application, warning logs, locks, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  queue trim-state arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: when queue depth exceeds `keep_latest`, drop the
  oldest `depth - keep_latest` slots, advance head by that drop count modulo
  capacity, and set depth to `keep_latest`. The helper also normalizes
  malformed negative or oversized inputs for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_queue_trim_state(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_queue_trim_state` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4035 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue trim-state arithmetic only
  and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue storage, slot clearing, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

## Packet 221 - Runtime Budget Render Queue Storage Capacity Contract

Status: parent-validated.

Mode: behavior-preserving render queue storage-capacity arithmetic contract
after Packet 220.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_queue_storage_capacity(...)`.
- The helper owns the pure storage-capacity selection for render queue growth.
- Routed `ffmpeg_bridge.c` `ensure_render_queue_capacity(...)` through the
  helper.
- Kept render queue allocation, slot copying, storage replacement,
  queue-head reset, locks, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  queue storage-capacity arithmetic behind a host-testable runtime budget
  helper.
- The preserved policy is: normalize the requested minimum to at least one,
  keep existing capacity when sufficient, start from the initial capacity,
  double while below the growth threshold, and grow by the fixed step once at
  or above the threshold. The helper also normalizes malformed current,
  initial, threshold, and step inputs for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_render_queue_storage_capacity(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_render_queue_storage_capacity` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4043 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render queue storage-capacity arithmetic
  only and does not alter detector scoring, candidate extraction, support-map
  logic, sampling-state lifecycle, MotionEstimator behavior, target tracking,
  render queue allocation, slot copying, frame transfer, `AVFrame` forwarding,
  threading, or app policy.

## Packet 222 - Runtime Budget Queue Offset Index Contract

Status: parent-validated.

Mode: behavior-preserving render/AD queue offset-index arithmetic contract
after Packet 221.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_queue_offset_index(...)`.
- The helper owns the pure queue offset-to-slot calculation shared by render
  and AD queue traversal/copy loops.
- Routed `ffmpeg_bridge.c` render/AD queue offset indexing through the helper
  for PTS traversal, buffered-span traversal, queue clearing, render trim
  clearing, pending-overlay lookup, and queue storage copy loops.
- Kept traversal bounds, queue storage, slot clearing, frame transfer, slot
  copying, monotonicity checks, locks, logging, and `AVFrame` ownership in
  `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing queue
  offset-index arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: calculate `(head + offset) % capacity` for valid
  capacity. The helper also normalizes malformed negative or oversized heads
  and negative offsets for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_queue_offset_index(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_queue_offset_index` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4050 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing queue offset-index arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking,
  traversal bounds, queue storage, slot clearing, frame transfer, `AVFrame`
  forwarding, threading, or app policy.

## Packet 223 - Runtime Budget Local Playback Advance Contract

Status: parent-validated.

Mode: behavior-preserving local playback pause/step advance contract after
Packet 222.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_local_playback_advance(...)`.
- The helper owns the pure pause/step-budget decision used by the local-file
  playback wait loop.
- Routed `ffmpeg_bridge.c` `wait_for_local_playback_advance(...)` through the
  helper.
- Kept local-file source detection, session-running checks, mutex locking,
  session state application, sleep/retry behavior, JNI pause/step commands,
  render signaling, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing local
  playback pause/step advance decision behind a host-testable runtime budget
  helper.
- The preserved policy is: unpaused playback advances without consuming step
  budget; paused playback with a positive step budget consumes exactly one
  step and advances; paused playback without steps waits. The helper also
  normalizes malformed negative step budgets for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_local_playback_advance(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_local_playback_advance` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4056 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing local playback pause/step advance
  decision arithmetic only and does not alter detector scoring, candidate
  extraction, support-map logic, sampling-state lifecycle, MotionEstimator
  behavior, target tracking, JNI pause/step commands, sleep/retry behavior,
  render signaling, threading, or app policy.

## Packet 224 - Runtime Budget Startup Observation Contract

Status: parent-validated.

Mode: behavior-preserving render startup observation decision contract after
Packet 223.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_startup_observation(...)`.
- The helper owns the pure observe/finalize decision for the live render
  startup observation window.
- Routed `ffmpeg_bridge.c` `dequeue_due_render_frame_locked(...)` startup
  observation decision through the helper.
- Kept startup observation activation, current time capture, periodic logging,
  final startup estimate application, session state mutation, render due-time
  reset, locks, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  startup observe/finalize decision behind a host-testable runtime budget
  helper.
- The preserved policy is: inactive observation does nothing; active
  observation remains in the observe phase while elapsed time is below the
  configured window; at or beyond the window the bridge finalizes startup
  estimates and schedules rendering. The helper also normalizes malformed
  negative elapsed time and nonpositive observe windows for host-test
  coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_startup_observation(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_startup_observation` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4062 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render startup observe/finalize decision
  arithmetic only and does not alter detector scoring, candidate extraction,
  support-map logic, sampling-state lifecycle, MotionEstimator behavior,
  target tracking, startup estimate application, logging, render signaling,
  threading, or app policy.

## Packet 225 - Runtime Budget Render Lag Contract

Status: parent-validated.

Mode: behavior-preserving render lag decision contract after Packet 224.

Scope:

- Extended `anomaly_runtime_budget.{h,c}` with
  `anomaly_detector_runtime_budget_render_lag(...)`.
- The helper owns the pure lag, lag-budget, severe-lag, and periodic lag-log
  decision arithmetic for render dequeue.
- Routed `ffmpeg_bridge.c` `dequeue_due_render_frame_locked(...)` render lag
  calculation through the helper.
- Kept current time capture, render dequeue eligibility, log timestamp
  mutation, any logging side effects, queue/frame transfer, trace counters,
  locks, and `AVFrame` ownership in `ffmpeg_bridge.c`.

Behavior changed:

- No intended runtime behavior changed. This packet names the existing render
  lag decision arithmetic behind a host-testable runtime budget helper.
- The preserved policy is: compute lag from `now - scheduled_due`, compute lag
  budget from `interval - lag`, mark severe lag at two source intervals, and
  update the lag log timestamp when either severe-lag or periodic-log
  conditions are met. The helper also normalizes malformed negative intervals
  for host-test coverage.

TDD red check:

- Added native harness tests and header wiring for
  `anomaly_detector_runtime_budget_render_lag(...)`.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed at link time with the missing
  `anomaly_detector_runtime_budget_render_lag` symbol.

Parent validation:

- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `4069 passed, 0 failed`.

Replay manifest rationale:

- Full replay manifests and performance benchmarks are not expected for this
  packet because it extracts existing render lag decision arithmetic only and
  does not alter detector scoring, candidate extraction, support-map logic,
  sampling-state lifecycle, MotionEstimator behavior, target tracking, render
  dequeue eligibility, log timestamp mutation, queue/frame transfer, threading,
  or app policy.

## Packet 226 - Stable ROI Publication Contract

Status: validated.

Mode: app-visible annotation publication behavior change after Packet 225.

Scope:

- Extended `anomaly_detector_annotation.{h,c}` with
  `anomaly_detector_result_apply_annotation_stability(...)`.
- The helper owns app-visible ROI continuity slots, strict-majority
  render-window eligibility with a three-observation floor, backlog-window
  publication latching, already-lit slot priority, output-only box smoothing,
  and the four-ROI output cap.
- Routed `ffmpeg_bridge.c` app-visible overlay drawing and
  `tools/anomaly_test/anomaly_video_test.c --app-display-output` through the
  stability helper.
- Added positional-consistency reinforcement for confirmed thermal target tracks:
  healthy registration/movement evidence slows carried-miss confidence decay and
  prevents non-fresh thermal carries from compounding local residual drift.
- Left detector scoring, candidate extraction, support maps, sampling-state
  lifecycle, render queue ownership, FFmpeg frame ownership, and Kotlin
  configuration unchanged.

Behavior changed:

- App-visible ROI publication is now stricter than the previous visibility
  cadence. Short-lived raw boxes remain hidden instead of flashing for a few
  frames.
- A visually persistent confirmed thermal target can still qualify when
  positional consistency carries it through the render-backlog window.
- Stable output is capped at four simultaneous ROIs and ranked by stable slot
  strength when no already-lit slots need to be preserved.
- Qualified ROIs stay lit for one render-backlog window; lit ROIs ease toward
  new raw centers instead of snapping between nearby candidates.
- Debug overlays and raw detector result helpers remain available for
  diagnostics; this packet changes only app-visible publication.

TDD red check:

- Added native harness tests for transient suppression, strict-majority window
  eligibility, four-ROI cap/ranking, age-out of missing slots, positional
  thermal carry, low-confidence positional reinforcement, and registration-only
  prediction for carried thermal targets.
- Added follow-up native tests for backlog-window latching, keeping already-lit
  ROIs ahead of newcomers under the cap, and smoothing lit ROI motion.
- Before implementation, `cmake --build tools/anomaly_test/build_timing`
  failed with the missing
  `anomaly_detector_result_apply_annotation_stability` symbol.

Validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `tools/anomaly_test/build_timing/anomaly_test`: `4231 passed, 0 failed`.
- `./gradlew :app:compileDebugKotlin`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, including `anomaly_video_powerhouse1_app_local_opening_recall`.
- PowerHouse1 opening app-local recall:
  `reviewed_recall=0.453704`, `thermal_matched_tracks=1`,
  `motion_event_count=110`.
- Manual app-visible CSV checks: max simultaneous ROIs stayed at `3` for
  PowerHouse1, `3` for PowerHouse2, and `4` for PowerHouse3; no frames exceeded
  the four-ROI cap.
- Display stability metrics improved from current to final app-visible output:
  PowerHouse1 short tracks `<15` frames `15 -> 1`, one-frame tracks `0 -> 0`;
  PowerHouse2 short tracks `26 -> 3`, one-frame tracks `3 -> 0`;
  PowerHouse3 short tracks `135 -> 3`, one-frame tracks `59 -> 0`.

## Packet 227 - Person Relevance Semantic Evidence

Status: host validated; device and aerial-corpus qualification pending.

Scope:

- Added backend-neutral candidate, decision, model identity, provenance, and
  positive-only fusion contracts in `anomaly_person_relevance.{h,c}`.
- Added a dedicated pressure-aware scheduler with one in-flight batch, one
  latest-wins pending batch, top-two target selection, stale-result rejection,
  runtime metrics, and deterministic teardown.
- Integrated LiteRT EfficientDet-Lite0 behind a Kotlin coordinator. Model load,
  crop conversion, and inference occur only on the Person worker.
- Added persisted `Off`, `Evaluate`, and `Assist` policy and applied it to both
  active and replacement FFmpeg sessions.
- Extended local playback review schema v2 with optional box, model, runtime,
  score, provenance, fusion, and inference timing evidence.
- Added the model/runtime legal notices and pinned model identity asset.

Constraints preserved:

- Person Relevance receives only already-published Color/IR target ROIs.
- Missing, stale, failed, negative, and low-confidence results are neutral.
- `Evaluate` cannot change ROI output. `Assist` can only add a capped bonus and
  cannot reject, hide, or shorten a target.
- `Off` avoids Person track/provenance lookup and never starts inference work.
- Decode, render, UI, and AD admission do not block on Person inference.

Validation:

- `ctest --test-dir /private/tmp/r2cad-person-final --output-on-failure`:
  passed, `7/7`.
- `./gradlew :app:testDebugUnitTest :app:personRelevanceQualification
  --no-parallel`: passed.
- `./gradlew :app:releaseCheck --no-parallel`: passed, including release
  lint/R8, APK assembly, and Crashlytics mapping/native-symbol uploads.
- Native arm64 Color app-parity qualification passed with identical detection
  output.
- Same-toolchain unchanged-HEAD and modified-tree `anomaly_video_test`
  executables compare byte-for-byte equal, superseding noisy samples from an
  obsolete x86_64/Rosetta `build_perf` cache.

Known limitations:

- COCO EfficientDet-Lite0 establishes the pipeline but is not yet qualified
  for small overhead people in SAR drone imagery.
- The shared Color target track does not retain exact Target Color versus Color
  Outlier ancestry; provenance records Color Outlier and adds Target Color when
  target colors are configured.
- No handset was connected during final validation. S25U latency, sustained
  playback heat, battery impact, and reviewed field quality remain open gates.

## Packet 228 - Explicit Appearance Selection

Status: host validated.

Scope:

- Removed automatic appearance selection from the anomaly model, preferences, stream-tile
  controls, runtime configuration, and current operator documentation.
- Made Color the default and retained Infrared as the explicit alternative.
- Added persisted-value migration so legacy automatic-mode and unknown values resolve
  to Color without changing an existing Infrared selection.
- Removed captured-video bitmap sampling and grayscale-based appearance
  classification from `StreamsViewModel`.

Constraints preserved:

- Detector algorithms, native scoring, candidate extraction, ROI publication,
  person relevance, decode/render ownership, and the automatic pixel-sampling
  Detail control are unchanged.
- The detector never silently switches between Color and Infrared based on
  scene appearance.

Validation:

- `./gradlew :app:testDebugUnitTest --no-parallel --console=plain`: passed.
- `./gradlew :app:releaseCheck --no-parallel --console=plain`: passed,
  including Color realtime and Person Relevance qualifications, release
  lint/R8, APK assembly, and Crashlytics artifact uploads.
- `git diff --check`: passed.

## Packet 229 - Stable Target-Color Performance Timing

Status: host validated.

Scope:

- Changed `target_color_perf_probe` timing from monotonic wall time to process
  CPU time so unrelated host scheduling pauses do not appear as detector cost.
- Kept the Red1/Red2 video qualification on wall-clock timing for end-to-end
  realtime coverage.

Constraints preserved:

- No production detector, configuration, threshold, ROI, or app runtime code
  changed.
- Relative target-color stress limits and the absolute catastrophic CPU-cost
  ceiling remain enforced.

Validation:

- `ctest --test-dir tools/anomaly_test/build_perf -R
  target_color_perf_probe --output-on-failure`: passed.
- `python3 -m unittest
  tools/anomaly_test/test_color_realtime_qualification.py`: `23` passed.
- `./gradlew :app:colorRealtimeQualification --no-parallel`: passed.
- `./gradlew :app:releaseCheck --no-parallel`: passed.
- `git diff --check`: passed.

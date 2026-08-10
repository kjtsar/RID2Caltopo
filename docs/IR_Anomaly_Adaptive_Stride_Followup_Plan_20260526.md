# IR Anomaly Follow-Up Plan: Adaptive Stride and Stable ROI Boxes

Date: 2026-05-26

## Status

- Slice 1, config and plumbing, shipped in `v1.5.6(80)rc1`.
- Adaptive stride is configurable but disabled by default.
- Native adaptive stride selection has landed as an experimental controller.
  Fixed stride mode keeps the existing modulo cadence.
- Adaptive stride is not default-ready yet. In the focused adaptive sample, the
  controller stayed at stride 2 because target-rich and weak-lock reasons were
  active, improving cadence versus full stride-1 but reducing recall.
- PH1 target-local telemetry was investigated as a separate read-only slice.
- PH1 read-only investigation found that the existing
  `--thermal-target cx,cy` plus `--thermal-debug-jsonl file.jsonl` harness seam
  already answers most early thermal-extraction questions. The smallest missing
  telemetry is after candidate extraction: provisional retention, selected vs.
  skipped, matched track fate, and off-gate suppression details.

## Current State

The current detector defaults remain conservative:

- App realtime/default posture still uses `frameStride = 1`.
- The provisional target candidate bank is implemented in native code.
- Movement-estimator scoring is exposed through a synchronous snapshot seam.
- Target-gated selective refresh exists, but is not promoted to defaults.
- Focused app-default gates pass, but stride-based selective refresh still
  fails PowerHouse1.

Latest focused results from the candidate-bank pass:

- App-default Team target: `94/171 TP`, `1/44 FP`.
- App-default Team early false-positive window: `0/48 FP`.
- App-default PowerHouse1 opening: `9/144 TP`, `0 FP`.
- Stride-2 selective Team target: `70/171 TP`, `1/44 FP`, `6.33x`.
- Stride-2 selective Team early false-positive window: `0/48 FP`.
- Stride-2 selective PowerHouse1 opening: `0/144 TP`, `3.01x`.

This means the next work should improve candidate formation and control-loop
behavior before any default change.

Latest parent-thread validation of the native adaptive controller:

- Native build: `cmake --build /private/tmp/rid2c_anomaly_release`.
- Native unit harness: `/private/tmp/rid2c_anomaly_release/anomaly_test`
  reported `102 passed, 0 failed`.
- Fixed-mode PowerHouse suite:
  - current detector baseline: `71/279 TP`, `4/92 FP`, `7.416x`
  - dense full-scan gold: `101/279 TP`, `0/92 FP`, `2.418x`
  - redesigned incremental fixed stride-1: `167/279 TP`, `1/92 FP`, `5.868x`
- Redesigned incremental focused fixed-mode gates:
  - Team early false-positive window: `0/48 FP`
  - Team target window: `108/171 TP`, `1/44 FP`
  - PowerHouse1 opening: `59/108 TP`, `0 FP`
- Experimental adaptive sample with `--stride-mode adaptive --stride 1`:
  - Team early false-positive window: `1/48 FP`
  - Team target window: `89/171 TP`, `1/44 FP`
  - PowerHouse1 opening: `28/108 TP`, `0 FP`
  - adaptive summary stayed at `min=2`, `max=2`, `avg=2.0`
- Android verification:
  - `./gradlew testDebugUnitTest --tests org.ncssar.rid2caltopo.video.anomaly.AnomalyConfigTest`
  - `./gradlew compileDebugKotlin`

## Goal

Prototype an adaptive-stride Anomaly Detector control path that can respond to
movement and registration state without making visible ROI boxes flicker.

The key architectural split:

- Analysis cadence: how often the detector performs heavier full analysis.
- Box decision cadence: how often visible ROI membership may materially change.
- Movement-maintained display: how existing boxes move between decision points.

The user preference is that adaptive analysis cadence should be responsive, but
visible ROI add/remove/switch decisions should occur only on a bounded decision
cadence.

## User-Facing Configuration

Replace or extend the current fixed frame-stride setting with:

- Stride mode:
  - Fixed
  - Adaptive
- Fixed mode:
  - existing fixed frame stride behavior
- Adaptive mode:
  - minimum stride in frames
  - maximum stride in seconds

Recommended defaults:

- Adaptive minimum stride: `2` frames.
- Adaptive maximum stride: `1.0` second.

The maximum should be derived from actual source frame rate:

```text
maxStrideFrames = clamp(round(sourceFps * maxStrideSeconds), minStrideFrames, hardSafetyCap)
```

Open implementation question:

- Use a hard safety cap of `33` frames initially, or allow higher values for
  high-FPS sources while showing the latency cost clearly.

Initial recommendation:

- Clamp adaptive max frames to `33` until the display-buffer behavior is proven.

## Native Control Loop

The adaptive stride controller should shorten analysis cadence quickly when the
scene becomes hard:

- degraded or invalid registration
- scene discontinuity
- high parallax or movement load
- active provisional targets
- weak or unconfirmed target locks
- recent target-rich full scans

It may lengthen stride only after stable low-motion windows:

- healthy registration
- low movement load
- no provisional targets
- target-poor recent full scans
- fresh ROI state

Recommended behavior:

- Drop stride quickly, for example `15 -> 2`, when high-risk signals appear.
- Raise stride more conservatively after sustained stability.
- Smooth raw movement signals with a short EMA, but do not over-rate-limit the
  response to real motion.

## ROI Box Stability

Do not tie visible ROI membership changes directly to the current adaptive
analysis stride.

Structural visible changes should occur only on the max-stride decision
cadence:

- create a new visible box
- remove a visible box
- switch a visible box to a different target
- materially change algorithm attribution

Non-structural updates may occur every frame:

- move an existing box by registration / movement prediction
- adjust size or weight modestly
- update internal confidence
- maintain tentative buffered state

This should reduce sporadic boxes while still allowing the detector to react
quickly internally.

## Optional Render-Delay Buffer

After the adaptive controller is working, consider a bounded display buffer:

- Buffer up to `maxStrideFrames` frames.
- Full scans and interim prediction can mark tentative targets in the buffer.
- At decision boundaries, confirm, adjust, or discard tentative boxes before
  the frames are rendered.
- Movement estimation maintains box positions within the buffered interval.

Latency examples:

- 30 fps, `0.5s` max: about `15` frames.
- 30 fps, `1.0s` max: about `30` frames.
- 60 fps, `1.0s` max with a `33` frame cap: about `0.55s`.

Do not implement the render-delay buffer in the first pass unless the adaptive
config/control loop is already clean.

## PowerHouse1 Detection Recommendation

Treat PowerHouse1 as a candidate-formation problem before treating it as a
revisit problem.

Add target-local telemetry for full scans:

- Was the reviewed target hot enough?
- Did it form a thermal blob?
- Was it rejected by area, ring, support mass, quality, or score floor?
- Was it retained as a provisional candidate?
- Was it later confirmed, suppressed, or aged out?

Then consider retaining target-local near misses, not only final ranked blob
candidates. A weak target embedded in a larger rejected thermal component may
need subdivision or local-peak extraction before revisit logic can help.

Existing PH1 telemetry command:

```bash
./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/PowerHouse1.mp4 \
  --app-defaults --no-video --time-start 0.0 --time-end 4.8 \
  --thermal-target 0.3181,0.2789 \
  --thermal-debug-jsonl /private/tmp/ph1_app_default_thermal.jsonl \
  --summary-json /private/tmp/ph1_app_default_summary.json \
  -c /private/tmp/ph1_app_default_detections.csv
```

Selective/fixed stride-2 comparison:

```bash
./tools/anomaly_test/build/anomaly_video_test app/src/test/resources/vidcap/PowerHouse1.mp4 \
  --app-defaults --app-frame-stride 2 --no-video --time-start 0.0 --time-end 4.8 \
  --thermal-target 0.3181,0.2789 \
  --thermal-debug-jsonl /private/tmp/ph1_stride2_thermal.jsonl \
  --summary-json /private/tmp/ph1_stride2_summary.json \
  -c /private/tmp/ph1_stride2_detections.csv
```

Smallest useful telemetry additions:

- `provisional_score_floor`
- `provisional_final_score`
- `provisional_score_eligible`
- `provisional_shape_eligible`
- `provisional_candidate_rank`
- `provisional_selected_rank`
- `provisional_candidate_rank_score`
- `provisional_near_existing_skip`
- matched target-track id/index after target-track update
- matched track `hit_count`, `miss_count`, `hold_count`, and
  `publish_confirmed`
- target-local off-gate suppression distance/gate result

## Movement Estimation Recommendation

Keep the movement snapshot seam. It should remain the only detector-facing
interface, whether movement is computed synchronously or eventually by a
worker.

Near-term performance guidance:

- Compute movement tiles once per analyzed frame.
- Avoid detector-side recomputation through AOI queries.
- Use layered-active mode only when selective refresh, adaptive stride, or
  target prediction consumes it.
- Consider lower-cost movement quality levels when the scene is stable.

## Color Detection Recommendation

Do not mix a major color-detector rewrite into the adaptive-stride pass.

Recommended color direction:

- Preserve blob-first color architecture.
- Improve candidate formation before ranking changes.
- Let color act as a secondary support cue for IR provisional candidates when
  it agrees spatially.
- Keep heavier color modes behind harness and device perf gates.

## Suggested Implementation Slices

### Slice 1: Config and Plumbing

Ownership:

- Kotlin anomaly config model and persistence.
- AD settings UI for fixed/adaptive stride.
- Native config bridge fields.
- Native harness app-default/adaptive CLI flags.

Requirements:

- Fixed mode must preserve existing behavior.
- Adaptive mode must carry min frames and max seconds/derived max frames.
- Unit tests should cover defaults, persistence, clamping, and native mapping.

### Slice 2: Native Adaptive Controller

Ownership:

- Native analyzer state/config.
- Current adaptive stride computation.
- Debug summary fields for selected stride and reason.

Requirements:

- Responsive stride shortening on registration/movement/target-risk signals.
- Conservative stride lengthening after stable windows.
- No default promotion until gates pass.

### Slice 3: ROI Decision Cadence

Ownership:

- Native target/box publication policy.
- Decision epoch based on derived max stride.
- Movement-maintained position updates between epochs.

Requirements:

- Prevent structural visible box changes outside decision epochs.
- Preserve per-frame movement updates for existing boxes.
- Do not let one-off interim candidates publish by themselves.

### Slice 4: PH1 Telemetry and Candidate Formation

Ownership:

- Native thermal target-local telemetry.
- Harness JSONL / summary outputs.
- Narrow candidate-formation experiment if telemetry identifies a clear seam.

Requirements:

- Start with telemetry before heuristic changes.
- Keep changes scoped to PH1 candidate formation.
- Do not broaden thresholds/defaults without focused gates.

## Validation Gates

Native build:

```bash
cmake --build /private/tmp/rid2c_anomaly_release
```

Focused app-default gates:

- PowerHouseTeam target window: at least `83/171 TP`, no more than `1/44 FP`.
- PowerHouseTeam early false-positive window: `0/48 FP`.
- PowerHouse1 opening: at least `9/144 TP`.

Selective/adaptive candidate gates:

- Must improve runtime versus stride-1 full scan.
- Must not drop PowerHouse1 to zero.
- Must not reintroduce Team early false positives.

Regression:

```bash
python3 tools/anomaly_test/run_regression_suite.py \
  --manifest tools/anomaly_test/regression_suite_manifest.json \
  --binary /private/tmp/rid2c_anomaly_release/anomaly_video_test \
  --out-dir /private/tmp/ir_adaptive_stride_suite \
  --report-json /private/tmp/ir_adaptive_stride_suite/suite_report.json \
  --report-md /private/tmp/ir_adaptive_stride_suite/suite_report.md
```

Android:

```bash
./gradlew testDebugUnitTest --tests org.ncssar.rid2caltopo.video.anomaly.AnomalyConfigTest
./gradlew compileDebugKotlin
```

Recommended follow-up after harness gates pass:

- Capture Perfetto and compare AD worker CPU, scan-mode mix, adaptive stride
  reasons, movement estimator cost, and render latency.

## Non-Goals For First Child Pass

- Do not change realtime defaults.
- Do not enable adaptive stride by default.
- Do not implement the render-delay buffer unless explicitly assigned.
- Do not rewrite color detection.
- Do not thread the movement estimator yet.
- Do not loosen publication semantics for one-off interim hits.

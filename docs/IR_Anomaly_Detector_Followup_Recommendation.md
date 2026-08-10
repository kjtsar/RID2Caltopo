# IR Anomaly Detector Follow-Up Recommendation

This note is intended as a handoff for a follow-on thread focused on detector
quality, performance, and regression coverage.

## Recommended Work Order

1. Land detector correctness fixes and restore unit-test confidence.
2. Add runtime benchmarking/reporting for app and harness runs.
3. Build a real-clip regression suite from reviewed black-hot footage.
4. Prototype hot-path performance changes, starting with `double` to `float`
   conversion in the sampled-grid path.

## 1. Correctness and Coverage

### Immediate fixes

- Keep thermal-only runs thermal-only:
  do not compute or consume color evidence unless `ANOMALY_ALGO_COLOR` is
  enabled.
- Repair stale tests that no longer match app defaults, especially thermal
  polarity defaults and app-to-native config translation.

### Recommended additional tests

- Add a unit test proving that thermal-only config does not include color
  influence in the native path.
- Add a test that exercises `Appearance=Thermal`, `Motion=on`, `Saliency=on`
  and confirms the algorithm mask still excludes color.
- Expand config tests to pin the current realtime defaults documented in
  `docs/IR_Anomaly_Detector.md`.

## 2. Runtime Benchmarking and User-Facing Performance Reporting

### Goal

Every real-video run should report whether analysis was faster than, equal to,
or slower than real time.

### Minimum acceptance

- For harness runs, print:
  input duration, wall-clock analysis time, and realtime factor.
- Express the result in an operator-friendly way, for example:
  `0.82x realtime (slower than realtime)` or
  `1.34x realtime (faster than realtime)`.
- For app playback review, surface a similarly simple status from existing
  telemetry so the operator can tell whether anomaly analysis is keeping up.

### Suggested metrics

- `analysis_wall_ms`
- `media_span_ms`
- `realtime_factor = media_span_ms / analysis_wall_ms`
- analyzed-frame count
- average anomaly processing ms per analyzed frame
- max anomaly processing ms per analyzed frame

### Likely implementation points

- Harness:
  `tools/anomaly_test/anomaly_video_test.c`
- App runtime summary:
  `app/src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegProbeService.kt`
  and
  `app/src/main/java/org/ncssar/rid2caltopo/video/StreamsViewModel.kt`

## 3. Real-Clip Regression Suite

### Initial clip set

Start with these black-hot clips:

- `PowerHouse1.mp4`
- `PowerHouse2.mp4`
- `PowerHouse3.mp4`
- `PowerHouseTeam.mp4`

These can be used whole or chopped into shorter scenario-focused segments.

### Recommendation

- Preserve the original full clips as source material.
- Derive short, purpose-built excerpts for regressions:
  false-positive scenes, good detections, motion-heavy pans, scene cuts,
  cluttered canopy, and low-contrast targets.
- Pair each excerpt with reviewed labels so changes can be scored, not just
  eyeballed.

### Suggested outputs per clip

- reviewed detection CSV
- clip metadata file with detector settings
- summary metrics:
  true positives, false positives, missed detections, and latency to first box

### Acceptance target

The suite should make it easy to answer:

- Did recall improve or regress?
- Did false positives improve or regress?
- Did latency change?
- Did runtime change?

## 4. Hot-Path Performance Prototype: `double` to `float`

### Why this is promising

The sampled-grid and integral-image path currently stores luma and related
scratch buffers as `double`, even though source luma is 8-bit and the detector
is not doing numerically fragile optimization there. Converting those hot-path
buffers to `float` may reduce memory bandwidth and improve cache behavior.

### Scope to investigate first

- sampled luma grid
- integral-image buffers
- any saliency/patch scratch that currently pays extra conversion cost

Primary file:

- `app/src/main/cpp/anomaly_analysis.c`

### Guardrails

- Compare detection outputs against the reviewed real-clip suite before and
  after the conversion.
- Track runtime changes on the same clips.
- Treat small numeric drift as acceptable only if reviewed quality metrics stay
  flat or improve.

## Suggested Deliverables For The Follow-On Thread

- code changes for benchmarking/reporting
- a first-pass real-clip regression manifest built from the PowerHouse clips
- a baseline metrics report for current detector behavior
- a prototype `float` branch with before/after quality and runtime results

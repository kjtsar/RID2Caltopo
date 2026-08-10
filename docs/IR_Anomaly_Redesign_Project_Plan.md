# IR Anomaly Redesign Project Plan

## Purpose

This document is the shared source of truth for the next-generation sampling,
persistence, and validation redesign of RID2Caltopo's IR anomaly detector.

All implementation threads should read this document first, then follow their
thread-specific scope and non-goals.

Primary detector code:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h)

Related design notes:

- [docs/IR_Anomaly_Detector.md](/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Detector.md)
- [docs/IR_Anomaly_Detector_Followup_Recommendation.md](/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Detector_Followup_Recommendation.md)

## Problem Statement

The current detector uses a sampled grid and a default Auto pixel step that
becomes materially coarse on HD/FHD input. That likely hurts recall for very
small black-hot targets. The current detector also uses frame stride as a major
performance lever, which increases temporal gaps and may weaken motion
registration and persistence stability.

The redesign should preserve very small target recall, keep motion registration
healthy, reduce unnecessary rescanning, and use registration-aware state reuse
instead of skipping important evidence.

## Current-State Findings

### 1. Coarse default sampling hurts small-target recall

The current Auto detail path returns a larger sample step on HD/FHD input, and
the thermal/background/saliency paths operate on that sampled ROI grid.

Relevant locations:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:144)
- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:5128)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:46)

### 2. Frame skipping currently skips state refresh, not just scoring

When a frame is skipped due to `frame_stride`, the detector ages tracks and can
still draw boxes, but it returns before doing registration refresh,
background-model refresh, and other per-frame state updates.

Relevant location:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:4999)

### 3. Registration compensation exists for tracks, but not for full coverage state

The current code motion-compensates track coordinates with the affine inverse,
but persistent thermal/background/motion support maps are still refreshed from
rescanned sampled grids rather than being broadly carried forward using healthy
registration.

Relevant locations:

- [app/src/main/cpp/anomaly_analysis.c](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.c:5091)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:190)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:194)
- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:202)

### 4. Current operator posture still favors stride and coarse detail under load

The existing detector notes still frame higher `Frame Stride` and larger detail
step as normal performance escape hatches. That posture does not match the
target design direction for drone IR review.

Relevant locations:

- [docs/IR_Anomaly_Detector.md](/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Detector.md:20)
- [docs/IR_Anomaly_Detector.md](/Users/kjt/Projects/RID2Caltopo/docs/IR_Anomaly_Detector.md:69)

## Design Goals

- Preserve very small target recall in black-hot IR video.
- Keep motion registration healthy and useful.
- Favor `frame_stride=1` unless there is a very strong measured reason not to.
- Avoid rescanning the entire scan zone every frame when not necessary.
- Use registration to carry forward coverage and target state when registration
  is healthy.
- Fully rescan only when registration is lost, scene continuity breaks, or
  carried coverage is no longer trustworthy.
- When new pixels move into the scan zone due to camera motion, rescan only
  those newly exposed pixels or tiles when practical.
- Favor per-pixel or near-per-pixel initial coverage over coarse fixed global
  skipping.
- If performance is still tight, prefer reducing scan zone size and improving
  hot-path efficiency before dropping recall.

## Recommended Architecture

### Summary

The redesign should split detector work into two layers:

1. A per-frame registration and state-propagation layer that always runs.
2. A selective appearance-refresh layer that decides whether to do a full
   rescan, a partial rescan, or a target-only revisit.

### Core principle

The detector should stop treating "not rescanned this frame" as implicit
unknown state. Instead, it should keep explicit ROI coverage state and classify
each pixel or cell as one of:

- freshly observed this frame
- carried forward by registration from prior state
- newly exposed and needing direct scan
- stale or invalid

### Default operating posture

- Realtime review:
  `frame_stride=1`, affine registration every frame, dense or near-dense
  appearance coverage, registration-aware partial rescans, smaller scan zone as
  the first performance lever.
- Offline review:
  `frame_stride=1`, full per-pixel scan every frame as gold mode, with
  incremental mode available for comparison and benchmarking.

## Persistent State Model

### Existing state that remains relevant

The current `anomaly_state_t` already includes:

- per-algorithm accumulators
- `prev_luma`
- `motion_persist`
- `bg_luma`
- `thermal_target_persist`
- scratch buffers

See:

- [app/src/main/cpp/anomaly_analysis.h](/Users/kjt/Projects/RID2Caltopo/app/src/main/cpp/anomaly_analysis.h:175)

### New ROI persistent state

Add a dedicated ROI-state structure with dense or near-dense coverage:

- ROI geometry:
  `roi_x0`, `roi_y0`, `roi_x1`, `roi_y1`, `width`, `height`
- persistent per-pixel arrays:
  `bg_luma`
  `last_luma`
  `thermal_score`
  `temporal_score`
  `valid_mask`
  `fresh_mask`
  `carried_mask`
  `new_exposed_mask`
  `reg_confidence`
  `coverage_age`

### New ROI cell summaries

Add a cell-grid summary used only for planning and telemetry. Suggested cell
size is 8x8 or 16x16 ROI pixels.

Per-cell summaries:

- valid count
- fresh count
- carried count
- newly exposed count
- stale count
- registration quality
- scan flags
- max thermal score
- max motion support

### New target-track state

Add explicit target tracks independent of sampled-grid-only persistence:

- `active`
- `id`
- `confidence`
- `hit_count`
- `miss_count`
- `hold_count`
- predicted center and bbox
- support radius
- last registration quality
- forced-revisit flag

## Registration-Aware Coverage Strategy

### Registration cadence

Registration and `prev_luma` refresh should run every frame, even if appearance
refresh is throttled temporarily.

### Health tiers

Classify registration each frame into:

- `HEALTHY`
- `SOFT_DEGRADED`
- `HARD_DEGRADED`
- invalid / discontinuity

Inputs should include:

- affine validity
- residual
- zoom scale
- global motion load
- warped valid coverage fraction

### Coverage carry-forward

For each current ROI pixel:

1. Inverse-warp the current pixel into the previous ROI state.
2. If the source lands outside the prior ROI or on invalid source state, mark
   it as newly exposed.
3. If the source lands on valid prior state, carry forward state and increment
   coverage age.

Carried state is allowed to support continuity, but must not be treated as
equivalent to direct fresh observation for originating strong new targets.

## Scan / Rescan Policy

### Full rescan

Require a full scan-zone rescan when:

- scene discontinuity is detected
- registration is hard-degraded or invalid
- warped valid coverage fraction is too low
- newly exposed fraction is too large
- stale coverage fraction is too large
- ROI geometry changed enough to invalidate carry-forward

### Partial rescan

Allow partial rescans when:

- registration is healthy or only soft-degraded
- enough carried coverage remains valid
- new exposure is localized
- stale or low-confidence areas are localized

Priority order for partial refresh:

1. newly exposed tiles
2. active/recent target revisit windows
3. stale tiles
4. low-registration-confidence tiles
5. periodic background-maintenance tiles

### Target-only revisit

Allow target-only revisit when:

- registration is healthy
- no large new-exposure region exists
- no large stale region exists
- the main need is to recheck active or recently missed targets

## Coverage Aging Rules

Per-pixel or per-cell aging should be explicit:

- freshly rescanned this frame:
  age resets to `0`
- carried forward from prior frame:
  age increments by `1`

Suggested initial expiry policy:

- realtime:
  expire non-target carried coverage after 3 carried-only frames
- offline:
  allow somewhat longer carry, but prefer direct scan

Target neighborhoods can tolerate slightly longer carry only when registration
remains healthy.

## Target Revisit Rules

Every active or recently missed target should get a dense local revisit window
each frame.

Revisit behavior:

- predict current position using registration
- rescan a padded neighborhood around the predicted footprint
- if directly reobserved, refresh confidence and geometry
- if not directly reobserved but registration remains healthy, allow only a
  short carry-forward hold
- if registration is weak, decay quickly rather than ghosting

## Recommended Threshold Direction

Initial thresholds should be conservative and can be tuned after telemetry and
real-clip review.

Suggested starting points:

- full rescan if newly exposed fraction exceeds about 25%
- full rescan if stale fraction exceeds about 35%
- partial rescan only if warped valid fraction remains above about 80%
- carried-only coverage in non-target regions expires after about 3 frames in
  realtime review

These are starting points, not final tuned values.

## Telemetry And Benchmarking

Every real-video run should report whether analysis was faster or slower than
realtime.

Minimum reporting:

- `analysis_wall_ms`
- `media_span_ms`
- `realtime_factor`
- analyzed-frame count
- average anomaly-processing ms per analyzed frame
- max anomaly-processing ms per analyzed frame

Recommended additional telemetry:

- registration health counts
- full/partial/target-only rescan counts
- fresh/carried/newly-exposed/stale fractions
- target revisit count

Operator-facing wording should be simple:

- `1.28x realtime (faster than realtime)`
- `0.83x realtime (slower than realtime)`

## Validation Plan

### Unit tests

Add focused tests for:

- registration-health classification
- inverse-warp new-exposure detection
- coverage aging and expiry
- fallback triggers for full rescans
- fresh-vs-carried evidence behavior

### Real-clip regression suite

Use the reviewed black-hot clip set:

- `PowerHouse1.mp4`
- `PowerHouse2.mp4`
- `PowerHouse3.mp4`
- `PowerHouseTeam.mp4`

Recommended regression artifacts:

- reviewed detection CSV
- clip metadata/settings file
- summary metrics:
  recall
  false positives
  misses
  latency to first box
  runtime / realtime factor

### Comparison baselines

Compare at least:

1. current detector behavior
2. dense full-scan `frame_stride=1` gold mode
3. redesigned incremental mode

## Phased Implementation Plan

### Phase 1

Registration cadence + telemetry.

### Phase 2

ROI persistent coverage state + cell summaries + registration health
classifier.

### Phase 3

Scan planner with full/partial/target-only modes.

### Phase 4

Target revisit + persistence redesign.

### Phase 5

Selective dense refresh using planner outputs.

### Phase 6

Validation, docs, defaults, and measured tuning.

## Thread Partitioning

### Thread 1: Registration Cadence + Telemetry

Owns:

- decoupling registration from appearance stride
- per-run telemetry
- debug enums for registration health / rescan mode

Should not own:

- ROI coverage-mask architecture
- target-track redesign
- final defaults/doc posture

### Thread 2: ROI Coverage State + Scan Planner

Owns:

- ROI persistent state
- coverage masks
- cell summaries
- registration-health classification
- full/partial/target-only scan planning

Should not own:

- full target-lifecycle redesign
- doc/default updates

### Thread 3: Target Revisit + Persistence Redesign

Owns:

- explicit target tracks
- registration-driven target prediction
- forced revisit windows
- fresh-vs-carried scoring rules

Should not own:

- global telemetry framework
- independent scan planner separate from Thread 2

### Thread 4: Validation, Regression Suite, Docs, Defaults

Owns:

- unit/regression scaffolding
- PowerHouse manifest/output conventions
- docs updates
- final recommended default posture after metrics

Should not own:

- independent reinvention of detector core architecture

## Interface Expectations Between Threads

### Expected outputs from Thread 1

- per-frame registration-health enum
- rescan-mode enum placeholder or stable debug field
- realtime-factor telemetry path

### Expected outputs from Thread 2

- stable ROI-state struct(s)
- stable cell-summary struct(s)
- stable scan-plan output

### Expected outputs from Thread 3

- target-track struct(s)
- target prediction / revisit integration against Thread 2 planner outputs

### Expected outputs from Thread 4

- regression manifest
- reporting format
- updated docs once implementation stabilizes

## Deliverable Format For Each Thread

Each thread should return:

- summary of changes made
- files touched
- interfaces added or changed
- tests run
- assumptions made
- blockers or downstream risks

## What Not To Do

- Do not use frame skipping as the primary default performance strategy.
- Do not rely on coarse global pixel skipping as the main IR recall/performance
  tradeoff.
- Do not let carried evidence originate strong new detections without direct
  support.
- Do not optimize first and measure later.
- Do not let each thread reinterpret the architecture independently.

## Suggested Handoff Pattern

Each implementation thread should be launched with:

1. this document
2. a short wrapper prompt identifying the thread number and owned sections
3. explicit non-goals
4. expected return format

Suggested wrapper pattern:

`Read docs/IR_Anomaly_Redesign_Project_Plan.md first. You are Thread N. Own the
sections assigned to your role. Stay within scope. Leave clear interfaces for
downstream threads.`

## Open Questions Log

- Final dense vs near-dense default for realtime mode should be decided after
  telemetry and PowerHouse review.
- Final scan-zone default may change if dense coverage proves affordable with
  planner-driven rescans.
- Thresholds for soft/hard registration degradation need measured tuning rather
  than intuition-only tuning.

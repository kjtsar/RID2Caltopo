# R2CAD Performance and Scene-Coverage Plan

## Objective

Reduce Color Anomaly Detector tail latency without weakening the approved
uniqueness-first behavior, selected-target-color behavior, app-visible
annotations, or moving-drone coverage.

The optimization target is full-scan burst cost and render responsiveness, not
average throughput alone.

## Fixed Constraints

- Preserve uniqueness-first Color AD ranking and winner semantics.
- Preserve the separate selected-target-color detector and keep it off when no
  target colors are selected.
- Qualify selected-target-color performance with two and three families enabled.
- Do not split a moving image into fixed screen stripes or phases.
- Use registration to carry scene evidence into the current frame.
- Use Motion Estimator confidence, displacement, parallax, and unstable/outlier
  tiles to decide whether carried evidence is trustworthy.
- Never draw an old pixel overlay directly onto a different frame.
- Require functionality and performance qualification for behavior changes.

## Packet A: Performance Measurement Foundation

### Scope

- Build a separately optimized host performance harness.
- Keep deterministic correctness builds available.
- Replace per-cell timing calls with low-overhead, non-overlapping stage timing.
- Report tail latency, including full-scan p95 and maximum frame cost.
- Gate burst regressions in addition to overall realtime throughput.

### Acceptance

- Red1 and Red2 app-parity output signatures remain identical.
- Reviewed precision and recall do not regress.
- Optimized timing output separates seed scoring, blob extraction, and ranking.
- Focused parser/gate tests pass.
- `git diff --check` passes.

## Packet B: Behavior-Identical Color Hot Path

### Scope

- Skip contrast-rescue work when rarity already exceeds the maximum possible
  rescue result.
- Hoist frame-invariant frontend, threshold, and prior-track calculations out of
  sampled-cell loops.
- Build a compact rarity-qualified work list before neighborhood calculations
  when it produces an output-identical result.
- Reuse target-debug support already computed for the same cell.

### Acceptance

- Candidate and app-visible output signatures are byte-identical on reviewed
  Color clips.
- Full functionality regression passes.
- Optimized full-scan p95 and maximum improve or remain neutral.
- No threshold, ranking, cadence, or publication behavior changes.

## Packet C: Multi-Target-Color Performance Gate

### Scope

- Add deterministic two-family and three-family stress scenes to the tracked
  selected-target-color performance probe.
- Include mixed-color background and compact-subject cases.
- Feed results into `colorRealtimeQualification` and the release gate.

### Acceptance

- Two- and three-family cases stay within the same-run performance ratio gate.
- Expected ROI presence/absence is asserted, not just timing.
- Existing single-family cases remain unchanged.

## Packet D: Motion-Aware Scene Coverage in Shadow Mode

### Operating States

1. `FULL_REQUIRED`: startup, scene discontinuity, invalid registration, hard
   degradation, or excessive newly exposed content.
2. `LOCKED_INCREMENTAL`: registration carries scene evidence while a bounded
   mask refreshes mandatory and high-debt blocks.
3. `RECOVERY`: soft degradation temporarily increases refresh coverage and
   uncertainty padding before either returning to lock or requiring a full scan.

### Coverage Model

Maintain block-level metadata aligned with the existing ROI or Motion Estimator
tile grid:

- last genuine scan frame and source timestamp
- carried-forward age and coverage debt
- registration confidence
- Motion Estimator confidence and layer class
- newly exposed fraction
- active-target intersection
- uncertainty/parallax/outlier status

Registration determines where prior scene samples moved. Motion Estimator is a
local trust and prioritization signal; its residual tile displacement is not a
replacement for global registration.

### Priority Order

1. Newly exposed or unmapped scene content.
2. Active target-revisit regions.
3. Unstable, parallax-heavy, local-outlier, or low-confidence blocks.
4. Oldest carried blocks by coverage debt.
5. Blocks near the entering edge inferred from the affine transform.

Every trusted carried block must receive a genuine refresh within a bounded
source-time interval. Fixed screen-space stripes are prohibited.

### Publication Rule

Carried evidence may nominate or coast an existing target. A new published
detection requires fresh support in its core region. Freshness and registration
confidence travel with observations so stale evidence cannot publish a ghost ROI.

### Shadow-Mode Acceptance

- Existing scan decisions and detector outputs remain authoritative.
- Shadow telemetry records state, selected blocks, selected fraction, coverage
  debt, fallback reason, and estimated work.
- Deterministic tests cover translation, rotation/scale, leading-edge exposure,
  soft degradation, hard lock loss, and target revisit.
- Replay comparison proves that shadow masks cover every accepted full-scan
  candidate before enablement is considered.

## Packet E: Shadow Evaluation and Enablement Contract

Compare shadow masks against reviewed app-parity clips and movement-heavy clips.
Define thresholds from evidence rather than hard-coding them during Packet D.

Required measurements:

- candidate coverage and time-to-fresh-verification
- missed full-scan candidates
- coverage-debt distribution and maximum age
- selected sample fraction per frame
- full/partial/recovery state transitions
- p50, p95, and maximum detector frame cost
- reviewed precision, recall, track match, and false positives

Enable motion-aware scheduling only after zero unexplained reviewed-candidate
misses and full behavior/performance qualification.

### Packet E Evidence (2026-07-10)

The shadow analyzer uses the candidate center's 8x6 block as the fresh-support
criterion. Its provisional explained-miss bound is 60 analyzed frames and
2 seconds, matching the current maximum full-refresh policy; enablement should
use a much shorter candidate-verification fallback.

- Red1: 5 authoritative full frames and 3 raw full-scan candidates. Same-frame
  shadow coverage was 0/3; all three center blocks were selected later, after
  1-2 source frames (33-67 ms). Shadow states were 12 full-required,
  27 locked-incremental, and 21 recovery frames. Maximum age was 30 frames /
  1.000 seconds and maximum debt was 7.0.
- Red2: 7 authoritative full frames and 4 raw full-scan candidates. Same-frame
  shadow coverage was 4/4. Shadow states were 15 full-required,
  54 locked-incremental, and 26 recovery frames. Maximum age was 35 frames /
  1.194 seconds and maximum debt was 10.0.
- Red1 and Red2 app-visible detection signatures remained identical to the
  frozen Packet D baselines: 64 and 117 rows respectively.

These seven candidates are enough to reject silent same-frame publication from
carried evidence, but not enough to establish a production threshold. Keep the
scheduler shadow-only. A future enabled design should hold a newly nominated
candidate until its center block is freshly scanned, target verification within
2 source frames / 100 ms, and force an authoritative full scan if that short
deadline is missed. Any hard registration condition, missing lookup, scene
discontinuity, or mandatory-mask overflow continues to require full fallback.
Broader movement-heavy reviewed clips and Android-device timing remain required
before enablement.

## Deferred Live-Render Synchronization

Treat live render decoupling as a separate project after device traces show that
AD work is delaying presentation. Any implementation must identify results by
generation, frame id, and source timestamp. An overlay may attach to its exact
pending frame; otherwise it must be dropped or transformed as a tracked
observation under bounded age and confidence. It must never be painted onto an
unrelated newer frame.

## Parent Acceptance Ladder

For each accepted packet:

1. Focused unit/native tests.
2. Rebuilt native harness and `ctest`.
3. App-parity or app-local reviewed replay with output comparison.
4. Optimized performance qualification with tail metrics.
5. Focused Gradle tests and compile.
6. `./gradlew :app:releaseCheck` for the integrated result.
7. Parent review of the complete diff and `git diff --check`.

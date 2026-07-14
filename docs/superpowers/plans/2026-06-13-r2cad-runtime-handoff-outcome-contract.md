# R2CAD Runtime Handoff Outcome Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the host-testable AD worker handoff contract to name worker outcome counter deltas without moving counters, queues, threads, or AVFrame ownership out of `ffmpeg_bridge.c`.

**Status:** Completed and parent-validated.

**Completion evidence:**

- Red check: `cmake --build tools/anomaly_test/build_timing` failed before the
  new type/helper existed because `anomaly_runtime_handoff_outcome_t` and
  `anomaly_runtime_handoff_outcome_for_decision(...)` were missing.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3828 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

**Architecture:** Add a small outcome-delta helper to `anomaly_runtime_handoff.{h,c}`. The helper takes the already-computed handoff decision plus `overlay_present` and returns pure counter deltas for processed, skipped, forwarded-without-analysis, and annotated frames. `ffmpeg_bridge.c` remains responsible for applying those deltas to the native session counters and for all frame/queue/render ownership.

**Tech Stack:** Standard C, existing native harness in `tools/anomaly_test/test_anomaly.c`, CMake native build, Android Gradle Kotlin compile.

---

## File Structure

- Modify `app/src/main/cpp/anomaly_runtime_handoff.h`
  - Add `anomaly_runtime_handoff_outcome_t` and a pure helper declaration.
- Modify `app/src/main/cpp/anomaly_runtime_handoff.c`
  - Implement the pure outcome helper.
- Modify `tools/anomaly_test/test_anomaly.c`
  - Add native tests for analyzed, skipped, forwarded, annotated, and invalid-decision outcomes.
- Modify `app/src/main/cpp/ffmpeg_bridge.c`
  - Use the outcome helper to apply the existing AD worker counter increments.
- Modify `docs/AnomalyDetector_Modularization_Parent.md`
  - Add Packet 191 parent checkpoint after validation.
- Modify `docs/AnomalyDetector_Modularization_Child_Packets.md`
  - Add Packet 191 child record after validation.
- Modify this plan
  - Mark completed steps and final validation evidence.

This packet intentionally does not move queue storage, condition variables, AD worker lifecycle, AVFrame clone/free logic, overlay rendering, render queue fallback, MotionEstimator execution, or session counters out of `ffmpeg_bridge.c`.

---

### Task 1: Add Handoff Outcome Tests

**Files:**
- Modify: `tools/anomaly_test/test_anomaly.c`

- [x] **Step 1: Add focused failing tests near the handoff tests**

Add these tests after `test_runtime_handoff_reason_names`:

```c
static void test_runtime_handoff_outcome_counts_analyzed_frame(void) {
    anomaly_runtime_handoff_decision_t decision = {
        .action = ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE,
        .reason = ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY,
    };
    anomaly_runtime_handoff_outcome_t outcome =
        anomaly_runtime_handoff_outcome_for_decision(decision, false);

    EXPECT(outcome.processed_delta == 1 &&
           outcome.skipped_delta == 0 &&
           outcome.forwarded_without_analysis_delta == 0 &&
           outcome.annotated_delta == 0,
           "runtime handoff outcome: analyzed frame counts processed only");
}

static void test_runtime_handoff_outcome_counts_annotated_frame(void) {
    anomaly_runtime_handoff_decision_t decision = {
        .action = ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE,
        .reason = ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY,
    };
    anomaly_runtime_handoff_outcome_t outcome =
        anomaly_runtime_handoff_outcome_for_decision(decision, true);

    EXPECT(outcome.processed_delta == 1 &&
           outcome.skipped_delta == 0 &&
           outcome.forwarded_without_analysis_delta == 0 &&
           outcome.annotated_delta == 1,
           "runtime handoff outcome: analyzed overlay counts annotated");
}

static void test_runtime_handoff_outcome_counts_forwarded_frame(void) {
    anomaly_runtime_handoff_decision_t decision = {
        .action = ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS,
        .reason = ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS,
    };
    anomaly_runtime_handoff_outcome_t outcome =
        anomaly_runtime_handoff_outcome_for_decision(decision, true);

    EXPECT(outcome.processed_delta == 1 &&
           outcome.skipped_delta == 1 &&
           outcome.forwarded_without_analysis_delta == 1 &&
           outcome.annotated_delta == 1,
           "runtime handoff outcome: forwarded overlay preserves existing annotated-count behavior");
}

static void test_runtime_handoff_outcome_handles_unknown_action(void) {
    anomaly_runtime_handoff_decision_t decision = {
        .action = (anomaly_runtime_handoff_action_t)99,
        .reason = ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME,
    };
    anomaly_runtime_handoff_outcome_t outcome =
        anomaly_runtime_handoff_outcome_for_decision(decision, false);

    EXPECT(outcome.processed_delta == 1 &&
           outcome.skipped_delta == 1 &&
           outcome.forwarded_without_analysis_delta == 1 &&
           outcome.annotated_delta == 0,
           "runtime handoff outcome: unknown action is treated as forwarded");
}
```

- [x] **Step 2: Register the tests in `main`**

Add these calls after `test_runtime_handoff_reason_names();`:

```c
    test_runtime_handoff_outcome_counts_analyzed_frame();
    test_runtime_handoff_outcome_counts_annotated_frame();
    test_runtime_handoff_outcome_counts_forwarded_frame();
    test_runtime_handoff_outcome_handles_unknown_action();
```

- [x] **Step 3: Run the red build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: FAIL because `anomaly_runtime_handoff_outcome_t` and `anomaly_runtime_handoff_outcome_for_decision(...)` do not exist yet.

---

### Task 2: Implement Handoff Outcome Helper

**Files:**
- Modify: `app/src/main/cpp/anomaly_runtime_handoff.h`
- Modify: `app/src/main/cpp/anomaly_runtime_handoff.c`

- [x] **Step 1: Add the outcome struct and helper declaration**

Add to `anomaly_runtime_handoff.h` after `anomaly_runtime_handoff_decision_t`:

```c
typedef struct {
    int processed_delta;
    int skipped_delta;
    int forwarded_without_analysis_delta;
    int annotated_delta;
} anomaly_runtime_handoff_outcome_t;
```

Add the helper declaration:

```c
anomaly_runtime_handoff_outcome_t anomaly_runtime_handoff_outcome_for_decision(
        anomaly_runtime_handoff_decision_t decision,
        bool overlay_present);
```

- [x] **Step 2: Implement the helper**

Implement the helper in `anomaly_runtime_handoff.c`:

- `processed_delta` is always 1 for a dequeued worker packet.
- `skipped_delta` and `forwarded_without_analysis_delta` are 1 when action is not `ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE`.
- `annotated_delta` is 1 when `overlay_present` is true.
- Unknown actions are treated as forwarded without analysis.

- [x] **Step 3: Run green native build and harness**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
```

Expected: build succeeds and all native unit tests pass.

---

### Task 3: Route Bridge Counter Updates Through Outcome Helper

**Files:**
- Modify: `app/src/main/cpp/ffmpeg_bridge.c`

- [x] **Step 1: Replace inline skipped/forwarded counter increments**

In `ad_thread_main`, after computing `handoff_decision`, compute the eventual `overlay_present` and apply `anomaly_runtime_handoff_outcome_for_decision(...)` to the existing counters. Preserve behavior:

- skipped and forwarded counters still increment for forward-without-analysis decisions,
- processed counter increments once for every dequeued packet that reaches the normal post-analysis path,
- annotated counter increments when `packet.overlay_frame != NULL`,
- local playback attach/late and overlay-enqueued counters remain bridge-owned.

- [x] **Step 2: Run focused validation**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
./gradlew :app:compileDebugKotlin
```

Expected: all pass.

---

### Task 4: Update Packet Docs And Validate

**Files:**
- Modify: `docs/AnomalyDetector_Modularization_Parent.md`
- Modify: `docs/AnomalyDetector_Modularization_Child_Packets.md`
- Modify: `docs/superpowers/plans/2026-06-13-r2cad-runtime-handoff-outcome-contract.md`

- [x] **Step 1: Add Packet 191 ledger entries**

Record that `anomaly_runtime_handoff.{h,c}` now also owns pure outcome deltas for AD worker processed/skipped/forwarded/annotated counters, while the session counters and all queue/frame ownership remain in `ffmpeg_bridge.c`.

- [x] **Step 2: Run final validation ladder**

Run:

```bash
git diff --check
cmake --build tools/anomaly_test/build_timing
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
tools/anomaly_test/build_timing/anomaly_test
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest
./gradlew :app:compileDebugKotlin
```

Expected: all pass.

- [x] **Step 3: Review final diff**

Expected: diff only contains the focused handoff outcome contract, bridge counter call-through, tests, ledgers, and this plan.

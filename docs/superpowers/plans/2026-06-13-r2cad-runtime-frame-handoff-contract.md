# R2CAD Runtime Frame Handoff Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a host-testable AD worker frame-handoff contract that names the current analyze-versus-forward decision without moving queue, thread, or AVFrame ownership out of `ffmpeg_bridge.c`.

**Status:** Completed and parent-validated.

**Completion evidence:**

- Red check: `cmake --build tools/anomaly_test/build_timing` failed before the
  new handoff header/module existed with
  `fatal error: 'anomaly_runtime_handoff.h' file not found`.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3824 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

**Follow-on direction:** The current AD worker handoff is named and tested, but
the bridge still owns concrete queue slots and `AVFrame` lifetime. The next
runtime packet should continue extracting host-testable contracts before moving
ownership out of `ffmpeg_bridge.c`; a separate ME worker/evidence queue remains
future work.

**Architecture:** Add `anomaly_runtime_handoff.{h,c}` as a pure C module for worker-frame metadata validation, stale-generation detection, and analyze/forward decision naming. `ffmpeg_bridge.c` remains responsible for Android/FFmpeg queues, pthread synchronization, AVFrame ownership, rendering, and counters. The AD worker should call the new helper for the current `process_enabled`, generation, and pressure-bypass decision.

**Tech Stack:** Standard C, existing native harness in `tools/anomaly_test/test_anomaly.c`, CMake native build, Android Gradle Kotlin compile.

---

## File Structure

- Create `app/src/main/cpp/anomaly_runtime_handoff.h`
  - Own small metadata structs and pure helper declarations.
- Create `app/src/main/cpp/anomaly_runtime_handoff.c`
  - Implement frame readiness, stale-generation, analyze decision, and reason naming.
- Modify `app/src/main/cpp/ffmpeg_bridge.c`
  - Include the new header.
  - Route the AD worker's analyze-versus-forward decision through the helper.
- Modify `app/src/main/cpp/CMakeLists.txt`
  - Add `anomaly_runtime_handoff.c` to the Android `ffmpeg_bridge` target.
- Modify `tools/anomaly_test/CMakeLists.txt`
  - Add `../../app/src/main/cpp/anomaly_runtime_handoff.c` to both `anomaly_test` and `anomaly_video_test`.
- Modify `tools/anomaly_test/test_anomaly.c`
  - Include `anomaly_runtime_handoff.h`.
  - Add focused native tests for frame readiness, stale generation, analyze/forward decisions, and reason names.
- Modify `AD_Guideance.md`, `docs/Current_Anomaly_Detector.md`, `docs/AnomalyDetector_Modularization_Parent.md`, and `docs/AnomalyDetector_Modularization_Child_Packets.md`
  - Record Packet 190 and current runtime direction.

This packet intentionally does not move queue storage, condition variables, AD worker lifecycle, AVFrame clone/free logic, overlay rendering, render queue fallback, or MotionEstimator execution out of `ffmpeg_bridge.c` or `anomaly_process_frame()`.

---

### Task 1: Add Runtime Handoff Tests

**Files:**
- Modify: `tools/anomaly_test/test_anomaly.c`

- [x] **Step 1: Include the handoff header**

Add the include near the other runtime headers:

```c
#include "anomaly_runtime_handoff.h"
```

- [x] **Step 2: Add failing handoff tests near the runtime pressure tests**

Add these tests after `test_runtime_pressure_mode_names`:

```c
static void test_runtime_handoff_frame_readiness(void) {
    anomaly_runtime_handoff_frame_t frame =
        anomaly_runtime_handoff_frame_make(12, 3, 40000, 41000, 640, 480, true);

    EXPECT(anomaly_runtime_handoff_frame_ready(frame),
           "runtime handoff frame: valid frame is ready");

    frame.has_frame = false;
    EXPECT(!anomaly_runtime_handoff_frame_ready(frame),
           "runtime handoff frame: missing frame is not ready");

    frame = anomaly_runtime_handoff_frame_make(12, 3, 40000, 41000, 0, 480, true);
    EXPECT(!anomaly_runtime_handoff_frame_ready(frame),
           "runtime handoff frame: invalid width is not ready");

    frame = anomaly_runtime_handoff_frame_make(12, 3, 40000, 41000, 640, -1, true);
    EXPECT(!anomaly_runtime_handoff_frame_ready(frame),
           "runtime handoff frame: invalid height is not ready");
}

static void test_runtime_handoff_generation_staleness(void) {
    anomaly_runtime_handoff_frame_t frame =
        anomaly_runtime_handoff_frame_make(12, 3, 40000, 41000, 640, 480, true);

    EXPECT(!anomaly_runtime_handoff_frame_is_stale(frame, 3),
           "runtime handoff generation: matching generation is current");
    EXPECT(anomaly_runtime_handoff_frame_is_stale(frame, 4),
           "runtime handoff generation: mismatched generation is stale");
}

static void test_runtime_handoff_decides_analysis_and_forwarding(void) {
    anomaly_runtime_handoff_frame_t frame =
        anomaly_runtime_handoff_frame_make(12, 3, 40000, 41000, 640, 480, true);

    anomaly_runtime_handoff_decision_t decision =
        anomaly_runtime_handoff_decide(frame, true, 3, false);
    EXPECT(decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE &&
           decision.reason == ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY,
           "runtime handoff decision: ready enabled current frame is analyzed");

    decision = anomaly_runtime_handoff_decide(frame, false, 3, false);
    EXPECT(decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS &&
           decision.reason == ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED,
           "runtime handoff decision: disabled processing forwards without analysis");

    decision = anomaly_runtime_handoff_decide(frame, true, 4, false);
    EXPECT(decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS &&
           decision.reason == ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION,
           "runtime handoff decision: stale generation forwards without analysis");

    decision = anomaly_runtime_handoff_decide(frame, true, 3, true);
    EXPECT(decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS &&
           decision.reason == ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS,
           "runtime handoff decision: pressure bypass forwards without analysis");

    frame.has_frame = false;
    decision = anomaly_runtime_handoff_decide(frame, true, 3, false);
    EXPECT(decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS &&
           decision.reason == ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME,
           "runtime handoff decision: invalid frame forwards without analysis");
}

static void test_runtime_handoff_reason_names(void) {
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY),
                  "analyze-ready") == 0,
           "runtime handoff reason name: analyze ready is named");
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED),
                  "processing-disabled") == 0,
           "runtime handoff reason name: processing disabled is named");
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION),
                  "stale-generation") == 0,
           "runtime handoff reason name: stale generation is named");
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS),
                  "pressure-bypass") == 0,
           "runtime handoff reason name: pressure bypass is named");
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME),
                  "invalid-frame") == 0,
           "runtime handoff reason name: invalid frame is named");
    EXPECT(strcmp(anomaly_runtime_handoff_reason_name(
                   (anomaly_runtime_handoff_reason_t)99),
                  "unknown") == 0,
           "runtime handoff reason name: invalid reason is unknown");
}
```

- [x] **Step 3: Register the tests in `main`**

Add these calls after `test_runtime_pressure_mode_names();`:

```c
    test_runtime_handoff_frame_readiness();
    test_runtime_handoff_generation_staleness();
    test_runtime_handoff_decides_analysis_and_forwarding();
    test_runtime_handoff_reason_names();
```

- [x] **Step 4: Run the red build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: FAIL because `anomaly_runtime_handoff.h` and its symbols do not exist yet.

---

### Task 2: Add Runtime Handoff Module And Build Wiring

**Files:**
- Create: `app/src/main/cpp/anomaly_runtime_handoff.h`
- Create: `app/src/main/cpp/anomaly_runtime_handoff.c`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `tools/anomaly_test/CMakeLists.txt`

- [x] **Step 1: Create `anomaly_runtime_handoff.h`**

```c
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    int64_t frame_id;
    int64_t generation_id;
    int64_t source_ts_us;
    int64_t enqueued_at_ms;
    int width;
    int height;
    bool has_frame;
} anomaly_runtime_handoff_frame_t;

typedef enum {
    ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE = 0,
    ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS = 1,
} anomaly_runtime_handoff_action_t;

typedef enum {
    ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY = 0,
    ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED = 1,
    ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION = 2,
    ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS = 3,
    ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME = 4,
} anomaly_runtime_handoff_reason_t;

typedef struct {
    anomaly_runtime_handoff_action_t action;
    anomaly_runtime_handoff_reason_t reason;
} anomaly_runtime_handoff_decision_t;

anomaly_runtime_handoff_frame_t anomaly_runtime_handoff_frame_make(
        int64_t frame_id,
        int64_t generation_id,
        int64_t source_ts_us,
        int64_t enqueued_at_ms,
        int width,
        int height,
        bool has_frame);

bool anomaly_runtime_handoff_frame_ready(
        anomaly_runtime_handoff_frame_t frame);

bool anomaly_runtime_handoff_frame_is_stale(
        anomaly_runtime_handoff_frame_t frame,
        int64_t current_generation_id);

anomaly_runtime_handoff_decision_t anomaly_runtime_handoff_decide(
        anomaly_runtime_handoff_frame_t frame,
        bool processing_enabled,
        int64_t current_generation_id,
        bool pressure_bypass);

const char *anomaly_runtime_handoff_reason_name(
        anomaly_runtime_handoff_reason_t reason);

#ifdef __cplusplus
}
#endif
```

- [x] **Step 2: Create `anomaly_runtime_handoff.c`**

Implement the pure helper exactly as a metadata policy module. It must not include FFmpeg, JNI, pthreads, or anomaly detector internals.

- [x] **Step 3: Wire CMake**

Add `anomaly_runtime_handoff.c` to:

- `app/src/main/cpp/CMakeLists.txt`
- both executable source lists in `tools/anomaly_test/CMakeLists.txt`

- [x] **Step 4: Run green native build and harness**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
```

Expected: build succeeds and all native unit tests pass.

---

### Task 3: Route AD Worker Decision Through Handoff Helper

**Files:**
- Modify: `app/src/main/cpp/ffmpeg_bridge.c`

- [x] **Step 1: Include `anomaly_runtime_handoff.h`**

Add:

```c
#include "anomaly_runtime_handoff.h"
```

- [x] **Step 2: Add bridge wrapper for packet metadata**

Near the pressure helpers, add a bridge-local wrapper that converts `render_queue_slot_t` to `anomaly_runtime_handoff_frame_t`. The wrapper must not escape AVFrame ownership.

- [x] **Step 3: Replace the inline skip condition**

In `ad_thread_main`, keep the existing pressure-bypass calculation, then call `anomaly_runtime_handoff_decide(...)`. Preserve the current side effects:

- forwarded frames set `packet.analyzed = false`
- `skipped = true`
- `ad_worker_skipped_frame_count` increments
- `ad_forwarded_without_analysis_count` increments
- analyzed frames still call `build_overlay_frame(...)`

- [x] **Step 4: Run focused validation**

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
- Modify: `AD_Guideance.md`
- Modify: `docs/Current_Anomaly_Detector.md`
- Modify: `docs/AnomalyDetector_Modularization_Parent.md`
- Modify: `docs/AnomalyDetector_Modularization_Child_Packets.md`

- [x] **Step 1: Add Packet 190 ledger entries**

Record that `anomaly_runtime_handoff.{h,c}` names only the current AD worker frame metadata and analyze/forward decision. State explicitly that it does not split ME into its own thread.

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

Expected: diff only contains the focused handoff contract, bridge call-through, CMake wiring, native tests, docs, and this plan.

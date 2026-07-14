# R2CAD Runtime Budget Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first standalone R2CAD realtime budget contract so the one detector implementation can choose Cursory or Thorough work without depending on Android/FFmpeg internals.

**Architecture:** Keep this as a behavior-preserving contract packet behind a focused runtime-budget module. Add plain-C runtime budget types and pure helper functions in `anomaly_runtime_budget.{h,c}`, include that contract from the umbrella detector facade, prove the thresholds with native unit tests, and do not wire the policy into Android, FFmpeg, scoring, MotionEstimator, candidate extraction, support maps, or threading yet.

**Tech Stack:** Standard C, existing native harness in `tools/anomaly_test/test_anomaly.c`, CMake native build, Android Gradle Kotlin compile.

---

## File Structure

- Create `app/src/main/cpp/anomaly_runtime_budget.h`
  - Own `anomaly_detector_processing_mode_t`, `anomaly_detector_runtime_budget_t`, and pure helper declarations.
- Create `app/src/main/cpp/anomaly_runtime_budget.c`
  - Implement default budget construction, budget normalization, and processing-mode selection.
  - Keep helpers side-effect-free so they can be tested without threads.
- Modify `app/src/main/cpp/anomaly_detector.h`
  - Include `anomaly_runtime_budget.h` so facade consumers can discover the contract from the module entrypoint without moving implementation into the facade.
- Modify `app/src/main/cpp/CMakeLists.txt`
  - Add `anomaly_runtime_budget.c` to the Android `ffmpeg_bridge` target.
- Modify `tools/anomaly_test/CMakeLists.txt`
  - Add `../../app/src/main/cpp/anomaly_runtime_budget.c` to both native harness targets.
- Modify `tools/anomaly_test/test_anomaly.c`
  - Add focused tests beside the existing detector facade/runtime tests.
  - Register the new tests in `main`.
- Modify `docs/AnomalyDetector_Modularization_Parent.md`
  - Add the packet checkpoint after validation.
- Modify `docs/AnomalyDetector_Modularization_Child_Packets.md`
  - Add the matching child-packet record after validation.

This packet keeps `anomaly_detector.{h,c}` as an umbrella facade rather than adding more behavior there. Runtime-budget policy gets a narrow owner now so the facade does not become a dumping ground again.

---

### Task 1: Add Runtime Budget Contract Tests

**Files:**
- Modify: `tools/anomaly_test/test_anomaly.c`
- Create later: `app/src/main/cpp/anomaly_runtime_budget.h`
- Create later: `app/src/main/cpp/anomaly_runtime_budget.c`
- Modify later: `app/src/main/cpp/anomaly_detector.h`
- Modify later: `app/src/main/cpp/CMakeLists.txt`
- Modify later: `tools/anomaly_test/CMakeLists.txt`

- [ ] **Step 1: Add focused failing tests near the detector facade runtime tests**

Insert these functions in `tools/anomaly_test/test_anomaly.c` after `test_detector_facade_default_config_make_realtime_default` and before the annotation cadence tests:

```c
static void test_detector_facade_runtime_budget_defaults(void) {
    anomaly_detector_runtime_budget_t budget =
        anomaly_detector_runtime_budget_make_default(30.0f);

    EXPECT_NEAR(budget.startup_skip_seconds, 0.25f, 0.0001f,
                "runtime budget default: startup skip is 0.25s");
    EXPECT_NEAR(budget.cursory_backlog_seconds, 0.25f, 0.0001f,
                "runtime budget default: cursory threshold is 0.25s");
    EXPECT_NEAR(budget.thorough_backlog_seconds, 0.5f, 0.0001f,
                "runtime budget default: thorough threshold is 0.5s");
    EXPECT_NEAR(budget.max_backlog_seconds, 0.5f, 0.0001f,
                "runtime budget default: max backlog is at least 0.5s");
    EXPECT(budget.render_backlog_seconds == 0.0f &&
           budget.startup_elapsed_seconds == 0.0f &&
           !budget.adapter_pressure,
           "runtime budget default: dynamic telemetry starts empty");

    budget = anomaly_detector_runtime_budget_make_default(0.0f);
    EXPECT_NEAR(budget.startup_skip_seconds, 0.25f, 0.0001f,
                "runtime budget default: invalid fps keeps time thresholds");
}

static void test_detector_facade_runtime_budget_normalizes_thresholds(void) {
    anomaly_detector_runtime_budget_t budget = {
        .render_backlog_seconds = -1.0f,
        .startup_elapsed_seconds = -2.0f,
        .startup_skip_seconds = -3.0f,
        .cursory_backlog_seconds = 0.0f,
        .thorough_backlog_seconds = 0.1f,
        .max_backlog_seconds = 0.2f,
        .adapter_pressure = false,
    };

    anomaly_detector_runtime_budget_t out =
        anomaly_detector_runtime_budget_normalize(budget);

    EXPECT(out.render_backlog_seconds == 0.0f &&
           out.startup_elapsed_seconds == 0.0f,
           "runtime budget normalize: negative dynamic telemetry clamps to zero");
    EXPECT_NEAR(out.startup_skip_seconds, 0.25f, 0.0001f,
                "runtime budget normalize: invalid startup skip uses default");
    EXPECT_NEAR(out.cursory_backlog_seconds, 0.25f, 0.0001f,
                "runtime budget normalize: invalid cursory threshold uses default");
    EXPECT_NEAR(out.thorough_backlog_seconds, 0.5f, 0.0001f,
                "runtime budget normalize: thorough threshold stays above cursory");
    EXPECT_NEAR(out.max_backlog_seconds, 0.5f, 0.0001f,
                "runtime budget normalize: max backlog stays at least thorough threshold");
}

static void test_detector_facade_runtime_budget_selects_processing_mode(void) {
    anomaly_detector_runtime_budget_t budget =
        anomaly_detector_runtime_budget_make_default(30.0f);

    budget.startup_elapsed_seconds = 0.10f;
    budget.render_backlog_seconds = 1.0f;
    EXPECT(anomaly_detector_runtime_budget_processing_mode(budget) ==
           ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY,
           "runtime budget mode: startup window forces cursory mode");

    budget.startup_elapsed_seconds = 0.30f;
    budget.render_backlog_seconds = 0.10f;
    EXPECT(anomaly_detector_runtime_budget_processing_mode(budget) ==
           ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY,
           "runtime budget mode: low backlog selects cursory mode");

    budget.render_backlog_seconds = 0.25f;
    EXPECT(anomaly_detector_runtime_budget_processing_mode(budget) ==
           ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY,
           "runtime budget mode: boundary backlog remains cursory");

    budget.render_backlog_seconds = 0.50f;
    EXPECT(anomaly_detector_runtime_budget_processing_mode(budget) ==
           ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH,
           "runtime budget mode: sufficient backlog selects thorough mode");

    budget.adapter_pressure = true;
    EXPECT(anomaly_detector_runtime_budget_processing_mode(budget) ==
           ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY,
           "runtime budget mode: adapter pressure forces cursory mode");
}

static void test_detector_facade_runtime_budget_names_modes(void) {
    EXPECT(strcmp(anomaly_detector_processing_mode_name(
                   ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY),
                  "cursory") == 0,
           "runtime budget mode name: cursory is named");
    EXPECT(strcmp(anomaly_detector_processing_mode_name(
                   ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH),
                  "thorough") == 0,
           "runtime budget mode name: thorough is named");
    EXPECT(strcmp(anomaly_detector_processing_mode_name(
                   (anomaly_detector_processing_mode_t)99),
                  "unknown") == 0,
           "runtime budget mode name: invalid mode is unknown");
}
```

- [ ] **Step 2: Register the tests in `main`**

Find the block that calls the existing detector facade tests near the end of `tools/anomaly_test/test_anomaly.c` and add:

```c
    test_detector_facade_runtime_budget_defaults();
    test_detector_facade_runtime_budget_normalizes_thresholds();
    test_detector_facade_runtime_budget_selects_processing_mode();
    test_detector_facade_runtime_budget_names_modes();
```

- [ ] **Step 3: Run the red build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: FAIL because `anomaly_runtime_budget.h` is not included yet and `anomaly_detector_runtime_budget_t`, `anomaly_detector_runtime_budget_make_default`, `anomaly_detector_runtime_budget_normalize`, `anomaly_detector_runtime_budget_processing_mode`, `anomaly_detector_processing_mode_name`, and the mode enum are not declared yet.

---

### Task 2: Add A Focused Runtime Budget Module

**Files:**
- Create: `app/src/main/cpp/anomaly_runtime_budget.h`
- Create: `app/src/main/cpp/anomaly_runtime_budget.c`
- Modify: `app/src/main/cpp/anomaly_detector.h`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `tools/anomaly_test/CMakeLists.txt`

- [ ] **Step 1: Create the public runtime-budget contract**

Create `app/src/main/cpp/anomaly_runtime_budget.h`:

```c
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>

typedef enum {
    ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY = 0,
    ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH = 1,
} anomaly_detector_processing_mode_t;

typedef struct {
    float render_backlog_seconds;
    float startup_elapsed_seconds;
    float startup_skip_seconds;
    float cursory_backlog_seconds;
    float thorough_backlog_seconds;
    float max_backlog_seconds;
    bool adapter_pressure;
} anomaly_detector_runtime_budget_t;

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps);

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget);

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget);

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode);

#ifdef __cplusplus
}
#endif
```

- [ ] **Step 2: Include the focused contract from the umbrella facade**

In `app/src/main/cpp/anomaly_detector.h`, add this include after `#include "anomaly_frame.h"`:

```c
#include "anomaly_runtime_budget.h"
```

- [ ] **Step 3: Create the pure helper implementation**

Create `app/src/main/cpp/anomaly_runtime_budget.c`:

```c
#include "anomaly_runtime_budget.h"

static float anomaly_detector_positive_or_default(float value, float fallback) {
    return value > 0.0f ? value : fallback;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps) {
    (void)frame_rate_fps;
    anomaly_detector_runtime_budget_t budget = {
        .render_backlog_seconds = 0.0f,
        .startup_elapsed_seconds = 0.0f,
        .startup_skip_seconds = 0.25f,
        .cursory_backlog_seconds = 0.25f,
        .thorough_backlog_seconds = 0.5f,
        .max_backlog_seconds = 0.5f,
        .adapter_pressure = false,
    };
    return budget;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget) {
    if (budget.render_backlog_seconds < 0.0f) {
        budget.render_backlog_seconds = 0.0f;
    }
    if (budget.startup_elapsed_seconds < 0.0f) {
        budget.startup_elapsed_seconds = 0.0f;
    }

    budget.startup_skip_seconds =
        anomaly_detector_positive_or_default(budget.startup_skip_seconds, 0.25f);
    budget.cursory_backlog_seconds =
        anomaly_detector_positive_or_default(budget.cursory_backlog_seconds, 0.25f);
    budget.thorough_backlog_seconds =
        anomaly_detector_positive_or_default(budget.thorough_backlog_seconds, 0.5f);
    if (budget.thorough_backlog_seconds < budget.cursory_backlog_seconds) {
        budget.thorough_backlog_seconds = budget.cursory_backlog_seconds;
    }

    budget.max_backlog_seconds =
        anomaly_detector_positive_or_default(budget.max_backlog_seconds, 0.5f);
    if (budget.max_backlog_seconds < budget.thorough_backlog_seconds) {
        budget.max_backlog_seconds = budget.thorough_backlog_seconds;
    }
    return budget;
}

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget) {
    budget = anomaly_detector_runtime_budget_normalize(budget);
    if (budget.adapter_pressure) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.startup_elapsed_seconds < budget.startup_skip_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds <= budget.cursory_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds >= budget.thorough_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH;
    }
    return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
}

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode) {
    switch (mode) {
        case ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY:
            return "cursory";
        case ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH:
            return "thorough";
        default:
            return "unknown";
    }
}
```

- [ ] **Step 4: Wire the new module into Android CMake**

In `app/src/main/cpp/CMakeLists.txt`, add `anomaly_runtime_budget.c` to the `ffmpeg_bridge` source list immediately after `anomaly_detector_annotation.c`:

```cmake
    anomaly_detector_annotation.c
    anomaly_runtime_budget.c
```

- [ ] **Step 5: Wire the new module into harness CMake**

In `tools/anomaly_test/CMakeLists.txt`, add `../../app/src/main/cpp/anomaly_runtime_budget.c` to both the `anomaly_test` and `anomaly_video_test` source lists immediately after `../../app/src/main/cpp/anomaly_detector_annotation.c`:

```cmake
    ../../app/src/main/cpp/anomaly_detector_annotation.c
    ../../app/src/main/cpp/anomaly_runtime_budget.c
```

- [ ] **Step 6: Re-run the build to confirm the new module links**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: build succeeds. If it fails with a missing source/header in only one target, update both Android and harness CMake wiring before continuing.

---

### Task 3: Run Focused Native Tests

**Files:**
- Verify: `tools/anomaly_test/build_timing/anomaly_test`

- [ ] **Step 1: Run the focused native build and unit binary**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
```

Expected: both commands succeed, and `anomaly_test` reports all tests passed. The pass count should increase by the number of new `EXPECT` checks.

---

### Task 4: Record The Packet In The Modularization Ledgers

**Files:**
- Modify: `docs/AnomalyDetector_Modularization_Parent.md`
- Modify: `docs/AnomalyDetector_Modularization_Child_Packets.md`

- [ ] **Step 1: Add a parent checkpoint**

Append this section to `docs/AnomalyDetector_Modularization_Parent.md`:

```markdown
Packet 188 parent checkpoint:

- `anomaly_runtime_budget.{h,c}` now owns the first standalone R2CAD runtime
  budget contract.
- `anomaly_detector_runtime_budget_t` carries adapter-supplied realtime
  telemetry without depending on Android, FFmpeg, JNI, or render queue state.
- `anomaly_detector_runtime_budget_processing_mode(...)` maps startup,
  adapter pressure, and render backlog thresholds to Cursory or Thorough mode.
- This packet intentionally does not wire the policy into Android, FFmpeg,
  scoring, MotionEstimator, candidate extraction, support maps, target
  tracking, or threading. It is contract plumbing for the future standalone
  threaded runtime.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed.
  - `tools/anomaly_test/build_timing/anomaly_test`: passed.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet adds pure facade policy helpers only and does not alter detector
  execution behavior.
```

Only write validation lines after the commands actually pass. If a command is skipped, replace `passed` with the actual reason it was not run.

- [ ] **Step 2: Add a child-packet record**

Append this section to `docs/AnomalyDetector_Modularization_Child_Packets.md`:

```markdown
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

Validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed.
- `tools/anomaly_test/build_timing/anomaly_test`: passed.
- `./gradlew :app:compileDebugKotlin`: passed.
```

Only write validation lines after the commands actually pass. If a command is skipped, replace `passed` with the actual reason it was not run.

---

### Task 5: Run The Validation Ladder

**Files:**
- Verify all modified files.

- [ ] **Step 1: Run whitespace validation**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [ ] **Step 2: Run native build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: build succeeds with exit code 0.

- [ ] **Step 3: Run CTest**

Run:

```bash
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
```

Expected: `100% tests passed`.

- [ ] **Step 4: Run direct native harness**

Run:

```bash
tools/anomaly_test/build_timing/anomaly_test
```

Expected: all native unit tests pass.

- [ ] **Step 5: Run Android Kotlin/native compile gate**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: Gradle task succeeds.

- [ ] **Step 6: Review final diff**

Run:

```bash
git diff -- AD_Guideance.md app/src/main/cpp/anomaly_detector.h app/src/main/cpp/anomaly_runtime_budget.h app/src/main/cpp/anomaly_runtime_budget.c app/src/main/cpp/CMakeLists.txt tools/anomaly_test/CMakeLists.txt tools/anomaly_test/test_anomaly.c docs/AnomalyDetector_Modularization_Parent.md docs/AnomalyDetector_Modularization_Child_Packets.md docs/superpowers/plans/2026-06-12-r2cad-runtime-budget-contract.md
```

Expected: diff only contains the approved guidance updates, focused runtime budget contract module, umbrella facade include, CMake wiring, tests, ledger updates, and this plan.

---

## Self-Review

- Spec coverage: The plan implements the first testable contract from `AD_Guideance.md`: one detector implementation with adapter-supplied runtime telemetry selecting Cursory or Thorough mode. It deliberately leaves actual worker-thread scheduling and Android/FFmpeg integration for later packets.
- Placeholder scan: No placeholder implementation steps remain; validation lines in ledger snippets explicitly say to use actual command outcomes.
- Boundary check: The runtime budget contract has a focused module owner and is only exposed through `anomaly_detector.h` as the umbrella entrypoint, keeping implementation logic out of the facade.
- Type consistency: The new names are consistent across header, implementation, tests, and docs:
  - `anomaly_detector_runtime_budget_t`
  - `anomaly_detector_processing_mode_t`
  - `ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY`
  - `ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH`
  - `anomaly_detector_runtime_budget_make_default`
  - `anomaly_detector_runtime_budget_normalize`
  - `anomaly_detector_runtime_budget_processing_mode`
  - `anomaly_detector_processing_mode_name`

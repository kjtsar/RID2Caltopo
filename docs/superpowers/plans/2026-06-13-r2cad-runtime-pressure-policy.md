# R2CAD Runtime Pressure Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the existing AD worker queue-pressure policy from `ffmpeg_bridge.c` into a host-testable standalone C module without changing live app behavior.

**Status:** Completed and parent-validated.

**Completion evidence:**

- Red check: `cmake --build tools/anomaly_test/build_timing` failed before the
  new pressure header/module existed with
  `fatal error: 'anomaly_runtime_pressure.h' file not found`.
- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: `3807 passed, 0 failed`.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

**Follow-on direction:** The current app path already has decode, AD worker, and
render thread surfaces. Continue extracting host-testable runtime contracts
before moving additional ownership out of `ffmpeg_bridge.c`; keep the
MotionEstimator running inside the current AD worker/analyzer path until a
separate ME worker/evidence queue has explicit ownership and synchronization
tests.

**Architecture:** Add `anomaly_runtime_pressure.{h,c}` as the focused owner for pressure thresholds, mode naming, mode selection, and per-frame bypass decisions. Keep `ffmpeg_bridge.c` responsible for Android/FFmpeg queues, pthreads, AVFrame ownership, render forwarding, and counters; it should call the new pure helpers instead of owning the policy math. This is a behavior-preserving extraction and does not split ME/AD worker threads yet.

**Tech Stack:** Standard C, existing native harness in `tools/anomaly_test/test_anomaly.c`, CMake native build, Android Gradle Kotlin compile, existing Kotlin source-guard test for pressure fallback.

---

## File Structure

- Create `app/src/main/cpp/anomaly_runtime_pressure.h`
  - Own pressure mode enum, policy struct, default policy construction, threshold calculation, mode selection, bypass decision, and mode naming.
- Create `app/src/main/cpp/anomaly_runtime_pressure.c`
  - Implement pure pressure helpers extracted from current `ffmpeg_bridge.c` behavior.
- Modify `app/src/main/cpp/ffmpeg_bridge.c`
  - Include `anomaly_runtime_pressure.h`.
  - Replace the private pressure enum with aliases to the standalone enum.
  - Keep the existing `AD_PRESSURE_*` constants as bridge defaults for now.
  - Route `ad_pressure_mode_name(...)`, `ad_queue_depth_threshold(...)`, and `select_ad_pressure_mode_locked(...)` through the standalone helper.
  - Route `bypass_for_pressure` through the standalone helper.
- Modify `app/src/main/cpp/CMakeLists.txt`
  - Add `anomaly_runtime_pressure.c` to the Android `ffmpeg_bridge` target.
- Modify `tools/anomaly_test/CMakeLists.txt`
  - Add `../../app/src/main/cpp/anomaly_runtime_pressure.c` to both `anomaly_test` and `anomaly_video_test`.
- Modify `tools/anomaly_test/test_anomaly.c`
  - Include `anomaly_runtime_pressure.h`.
  - Add focused native tests for threshold rounding/clamping, mode selection and hysteresis, recovery behavior, bypass decisions, and names.
- Modify `docs/AnomalyDetector_Modularization_Parent.md`
  - Add Packet 189 checkpoint after validation.
- Modify `docs/AnomalyDetector_Modularization_Child_Packets.md`
  - Add Packet 189 record after validation.

This packet intentionally does not move queue storage, locks, condition variables, AVFrame clone/clear logic, rendering fallback, or worker lifecycle out of `ffmpeg_bridge.c`.

---

### Task 1: Add Runtime Pressure Policy Tests

**Files:**
- Modify: `tools/anomaly_test/test_anomaly.c`
- Create later: `app/src/main/cpp/anomaly_runtime_pressure.h`
- Create later: `app/src/main/cpp/anomaly_runtime_pressure.c`

- [x] **Step 1: Include the pressure header in the native test harness**

In `tools/anomaly_test/test_anomaly.c`, add this include after `#include "anomaly_runtime_budget.h"` or near the other anomaly runtime includes:

```c
#include "anomaly_runtime_pressure.h"
```

- [x] **Step 2: Add focused failing tests near the runtime-budget tests**

Insert these functions after `test_detector_facade_runtime_budget_names_modes` and before `test_detector_facade_annotation_cadence_contract`:

```c
static void test_runtime_pressure_thresholds_round_up_and_clamp(void) {
    EXPECT(anomaly_runtime_pressure_depth_threshold(24, 50) == 12,
           "runtime pressure threshold: 50 percent of 24 is 12");
    EXPECT(anomaly_runtime_pressure_depth_threshold(24, 66) == 16,
           "runtime pressure threshold: 66 percent of 24 rounds up to 16");
    EXPECT(anomaly_runtime_pressure_depth_threshold(24, 80) == 20,
           "runtime pressure threshold: 80 percent of 24 rounds up to 20");
    EXPECT(anomaly_runtime_pressure_depth_threshold(15, 50) == 8,
           "runtime pressure threshold: odd capacity rounds up");
    EXPECT(anomaly_runtime_pressure_depth_threshold(15, 66) == 10,
           "runtime pressure threshold: 66 percent of 15 is 10");
    EXPECT(anomaly_runtime_pressure_depth_threshold(15, 80) == 12,
           "runtime pressure threshold: 80 percent of 15 is 12");
    EXPECT(anomaly_runtime_pressure_depth_threshold(0, 80) == 0,
           "runtime pressure threshold: zero capacity returns zero");
    EXPECT(anomaly_runtime_pressure_depth_threshold(5, 0) == 1,
           "runtime pressure threshold: positive capacity minimum is one");
    EXPECT(anomaly_runtime_pressure_depth_threshold(5, 150) == 5,
           "runtime pressure threshold: threshold is clamped to capacity");
}

static void test_runtime_pressure_default_policy_matches_bridge_constants(void) {
    anomaly_runtime_pressure_policy_t policy =
        anomaly_runtime_pressure_policy_make_default(24);

    EXPECT(policy.queue_capacity == 24,
           "runtime pressure default policy: capacity is retained");
    EXPECT(policy.recover_depth == 2,
           "runtime pressure default policy: recover depth matches bridge default");
    EXPECT(policy.analyze_alternate_pct == 50,
           "runtime pressure default policy: analyze threshold matches bridge default");
    EXPECT(policy.bypass_alternate_pct == 66,
           "runtime pressure default policy: bypass alternate threshold matches bridge default");
    EXPECT(policy.bypass_all_pct == 80,
           "runtime pressure default policy: bypass all threshold matches bridge default");

    policy = anomaly_runtime_pressure_policy_make_default(-1);
    EXPECT(policy.queue_capacity == 0,
           "runtime pressure default policy: invalid capacity clamps to zero");
}

static void test_runtime_pressure_selects_modes_and_recovers(void) {
    anomaly_runtime_pressure_policy_t policy =
        anomaly_runtime_pressure_policy_make_default(24);

    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
                   2) == ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
           "runtime pressure mode: recover depth selects normal");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
                   12) == ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE,
           "runtime pressure mode: analyze threshold selects analyze alternate");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
                   16) == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE,
           "runtime pressure mode: bypass alternate threshold selects bypass alternate");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
                   20) == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL,
           "runtime pressure mode: bypass all threshold selects bypass all");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL,
                   10) == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL,
           "runtime pressure mode: bypass all is sticky above recover depth");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE,
                   10) == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE,
           "runtime pressure mode: bypass alternate is sticky above recover depth");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE,
                   10) == ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE,
           "runtime pressure mode: analyze alternate is sticky above recover depth");
    EXPECT(anomaly_runtime_pressure_select_mode(
                   policy,
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL,
                   2) == ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
           "runtime pressure mode: sticky mode recovers at recover depth");
}

static void test_runtime_pressure_bypass_decision(void) {
    EXPECT(!anomaly_runtime_pressure_should_bypass_analysis(
                    ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL,
                    2),
           "runtime pressure bypass: normal never bypasses");
    EXPECT(!anomaly_runtime_pressure_should_bypass_analysis(
                    ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE,
                    2),
           "runtime pressure bypass: analyze alternate changes stride but does not bypass");
    EXPECT(!anomaly_runtime_pressure_should_bypass_analysis(
                    ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE,
                    1),
           "runtime pressure bypass: bypass alternate keeps odd frames");
    EXPECT(anomaly_runtime_pressure_should_bypass_analysis(
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE,
                   2),
           "runtime pressure bypass: bypass alternate drops even frames");
    EXPECT(anomaly_runtime_pressure_should_bypass_analysis(
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL,
                   1),
           "runtime pressure bypass: bypass all drops every frame");
}

static void test_runtime_pressure_mode_names(void) {
    EXPECT(strcmp(anomaly_runtime_pressure_mode_name(
                   ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL),
                  "normal") == 0,
           "runtime pressure mode name: normal is named");
    EXPECT(strcmp(anomaly_runtime_pressure_mode_name(
                   ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE),
                  "analyze-alternate") == 0,
           "runtime pressure mode name: analyze alternate is named");
    EXPECT(strcmp(anomaly_runtime_pressure_mode_name(
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE),
                  "bypass-alternate") == 0,
           "runtime pressure mode name: bypass alternate is named");
    EXPECT(strcmp(anomaly_runtime_pressure_mode_name(
                   ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL),
                  "bypass-all") == 0,
           "runtime pressure mode name: bypass all is named");
    EXPECT(strcmp(anomaly_runtime_pressure_mode_name(
                   (anomaly_runtime_pressure_mode_t)99),
                  "unknown") == 0,
           "runtime pressure mode name: invalid mode is unknown");
}
```

- [x] **Step 3: Register the tests in `main`**

Find the detector facade/runtime test calls in `main` and add these calls after `test_detector_facade_runtime_budget_names_modes();`:

```c
    test_runtime_pressure_thresholds_round_up_and_clamp();
    test_runtime_pressure_default_policy_matches_bridge_constants();
    test_runtime_pressure_selects_modes_and_recovers();
    test_runtime_pressure_bypass_decision();
    test_runtime_pressure_mode_names();
```

- [x] **Step 4: Run the red build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: FAIL because `anomaly_runtime_pressure.h` and its symbols do not exist yet.

---

### Task 2: Add Runtime Pressure Module And Build Wiring

**Files:**
- Create: `app/src/main/cpp/anomaly_runtime_pressure.h`
- Create: `app/src/main/cpp/anomaly_runtime_pressure.c`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Modify: `tools/anomaly_test/CMakeLists.txt`

- [x] **Step 1: Create the public runtime-pressure contract**

Create `app/src/main/cpp/anomaly_runtime_pressure.h`:

```c
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL = 0,
    ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE = 1,
    ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE = 2,
    ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL = 3,
} anomaly_runtime_pressure_mode_t;

typedef struct {
    int queue_capacity;
    int recover_depth;
    int analyze_alternate_pct;
    int bypass_alternate_pct;
    int bypass_all_pct;
} anomaly_runtime_pressure_policy_t;

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make_default(
        int queue_capacity);

int anomaly_runtime_pressure_depth_threshold(int queue_capacity, int pct);

anomaly_runtime_pressure_mode_t anomaly_runtime_pressure_select_mode(
        anomaly_runtime_pressure_policy_t policy,
        anomaly_runtime_pressure_mode_t   current_mode,
        int                               queue_depth_before_dequeue);

bool anomaly_runtime_pressure_should_bypass_analysis(
        anomaly_runtime_pressure_mode_t mode,
        int64_t                         pressure_frame_counter);

const char *anomaly_runtime_pressure_mode_name(
        anomaly_runtime_pressure_mode_t mode);

#ifdef __cplusplus
}
#endif
```

- [x] **Step 2: Create the pure helper implementation**

Create `app/src/main/cpp/anomaly_runtime_pressure.c`:

```c
#include "anomaly_runtime_pressure.h"

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make_default(
        int queue_capacity) {
    anomaly_runtime_pressure_policy_t policy = {
        .queue_capacity = queue_capacity > 0 ? queue_capacity : 0,
        .recover_depth = 2,
        .analyze_alternate_pct = 50,
        .bypass_alternate_pct = 66,
        .bypass_all_pct = 80,
    };
    return policy;
}

int anomaly_runtime_pressure_depth_threshold(int queue_capacity, int pct) {
    if (queue_capacity <= 0) {
        return 0;
    }
    int threshold = (queue_capacity * pct + 99) / 100;
    if (threshold < 1) {
        threshold = 1;
    }
    if (threshold > queue_capacity) {
        threshold = queue_capacity;
    }
    return threshold;
}

anomaly_runtime_pressure_mode_t anomaly_runtime_pressure_select_mode(
        anomaly_runtime_pressure_policy_t policy,
        anomaly_runtime_pressure_mode_t   current_mode,
        int                               queue_depth_before_dequeue) {
    if (policy.queue_capacity <= 0) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL;
    }
    if (queue_depth_before_dequeue <= policy.recover_depth) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL;
    }

    const int bypass_all_threshold =
        anomaly_runtime_pressure_depth_threshold(
                policy.queue_capacity,
                policy.bypass_all_pct);
    const int bypass_alternate_threshold =
        anomaly_runtime_pressure_depth_threshold(
                policy.queue_capacity,
                policy.bypass_alternate_pct);
    const int analyze_alternate_threshold =
        anomaly_runtime_pressure_depth_threshold(
                policy.queue_capacity,
                policy.analyze_alternate_pct);

    if (queue_depth_before_dequeue >= bypass_all_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL;
    }

    if (queue_depth_before_dequeue >= bypass_alternate_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE;
    }

    if (queue_depth_before_dequeue >= analyze_alternate_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE;
    }

    return ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL;
}

bool anomaly_runtime_pressure_should_bypass_analysis(
        anomaly_runtime_pressure_mode_t mode,
        int64_t                         pressure_frame_counter) {
    switch (mode) {
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL:
            return true;
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE:
            return (pressure_frame_counter % 2) == 0;
        case ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE:
        case ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL:
        default:
            return false;
    }
}

const char *anomaly_runtime_pressure_mode_name(
        anomaly_runtime_pressure_mode_t mode) {
    switch (mode) {
        case ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE:
            return "analyze-alternate";
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE:
            return "bypass-alternate";
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL:
            return "bypass-all";
        case ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL:
            return "normal";
        default:
            return "unknown";
    }
}
```

- [x] **Step 3: Wire the new module into Android CMake**

In `app/src/main/cpp/CMakeLists.txt`, add `anomaly_runtime_pressure.c` to the `ffmpeg_bridge` source list immediately after `anomaly_runtime_budget.c`:

```cmake
    anomaly_runtime_budget.c
    anomaly_runtime_pressure.c
```

- [x] **Step 4: Wire the new module into harness CMake**

In `tools/anomaly_test/CMakeLists.txt`, add `../../app/src/main/cpp/anomaly_runtime_pressure.c` to both `anomaly_test` and `anomaly_video_test` immediately after `../../app/src/main/cpp/anomaly_runtime_budget.c`:

```cmake
    ../../app/src/main/cpp/anomaly_runtime_budget.c
    ../../app/src/main/cpp/anomaly_runtime_pressure.c
```

- [x] **Step 5: Run the native build and unit binary**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
```

Expected: build succeeds and `anomaly_test` passes. This proves the standalone pressure module before bridge integration.

---

### Task 3: Route `ffmpeg_bridge.c` Through The Standalone Policy

**Files:**
- Modify: `app/src/main/cpp/ffmpeg_bridge.c`

- [x] **Step 1: Include the standalone pressure header**

Add this include with the other anomaly includes near the top of `ffmpeg_bridge.c`:

```c
#include "anomaly_runtime_pressure.h"
```

- [x] **Step 2: Replace the private enum with compatibility aliases**

Replace the current private `ad_pressure_mode_t` enum:

```c
typedef enum {
    AD_PRESSURE_MODE_NORMAL = 0,
    AD_PRESSURE_MODE_ANALYZE_ALTERNATE = 1,
    AD_PRESSURE_MODE_BYPASS_ALTERNATE = 2,
    AD_PRESSURE_MODE_BYPASS_ALL = 3,
} ad_pressure_mode_t;
```

with:

```c
typedef anomaly_runtime_pressure_mode_t ad_pressure_mode_t;
#define AD_PRESSURE_MODE_NORMAL ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL
#define AD_PRESSURE_MODE_ANALYZE_ALTERNATE ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE
#define AD_PRESSURE_MODE_BYPASS_ALTERNATE ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE
#define AD_PRESSURE_MODE_BYPASS_ALL ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL
```

- [x] **Step 3: Route pressure mode names through the module**

Replace the body of `ad_pressure_mode_name(...)` with:

```c
static const char *ad_pressure_mode_name(ad_pressure_mode_t mode) {
    return anomaly_runtime_pressure_mode_name(mode);
}
```

- [x] **Step 4: Route threshold calculation through the module**

Replace the body of `ad_queue_depth_threshold(...)` with:

```c
static int ad_queue_depth_threshold(const ffmpeg_session_t *session, int pct) {
    return anomaly_runtime_pressure_depth_threshold(
            session != NULL ? session->ad_input_queue_capacity : 0,
            pct);
}
```

- [x] **Step 5: Route mode selection through the module**

Replace the body of `select_ad_pressure_mode_locked(...)` with:

```c
static ad_pressure_mode_t select_ad_pressure_mode_locked(ffmpeg_session_t *session,
                                                         int queue_depth_before_dequeue) {
    if (session == NULL) return AD_PRESSURE_MODE_NORMAL;
    anomaly_runtime_pressure_policy_t policy =
        anomaly_runtime_pressure_policy_make_default(session->ad_input_queue_capacity);
    policy.recover_depth = AD_PRESSURE_RECOVER_DEPTH;
    policy.analyze_alternate_pct = AD_PRESSURE_ANALYZE_ALTERNATE_PCT;
    policy.bypass_alternate_pct = AD_PRESSURE_BYPASS_ALTERNATE_PCT;
    policy.bypass_all_pct = AD_PRESSURE_BYPASS_ALL_PCT;
    return anomaly_runtime_pressure_select_mode(
            policy,
            session->ad_pressure_mode,
            queue_depth_before_dequeue);
}
```

- [x] **Step 6: Route bypass decisions through the module**

In `ad_thread_main(...)`, replace:

```c
        bool bypass_for_pressure = false;
        if (process_enabled && packet.generation_id == generation_id) {
            if (pressure_mode == AD_PRESSURE_MODE_BYPASS_ALL) {
                bypass_for_pressure = true;
            } else if (pressure_mode == AD_PRESSURE_MODE_BYPASS_ALTERNATE &&
                       (session->ad_pressure_frame_counter % 2) == 0) {
                bypass_for_pressure = true;
            }
        }
```

with:

```c
        bool bypass_for_pressure = false;
        if (process_enabled && packet.generation_id == generation_id) {
            bypass_for_pressure =
                anomaly_runtime_pressure_should_bypass_analysis(
                        pressure_mode,
                        session->ad_pressure_frame_counter);
        }
```

- [x] **Step 7: Build and run the focused source guard test**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
tools/anomaly_test/build_timing/anomaly_test
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest
```

Expected:
- native build succeeds,
- native harness passes,
- Kotlin pressure policy source-guard test passes.

---

### Task 4: Update Ledgers With Actual Validation

**Files:**
- Modify: `docs/AnomalyDetector_Modularization_Parent.md`
- Modify: `docs/AnomalyDetector_Modularization_Child_Packets.md`

- [x] **Step 1: Append Packet 189 parent checkpoint**

Append this section to `docs/AnomalyDetector_Modularization_Parent.md` after Packet 188:

```markdown
Packet 189 parent checkpoint:

- `anomaly_runtime_pressure.{h,c}` now owns the pure AD worker queue-pressure
  policy for threshold rounding/clamping, mode naming, mode selection, and
  per-frame bypass decisions.
- `ffmpeg_bridge.c` still owns Android/FFmpeg session state, AVFrame queues,
  pthread lifecycle, render forwarding, counters, and logging, but delegates
  pressure policy math to the standalone helper.
- The extraction preserves the current live behavior: queue pressure degrades
  to render, sticky pressure modes remain until recover depth, bypass-all
  skips every frame, and bypass-alternate skips even pressure-frame counters.
- This packet does not split ME and AD into separate internal R2CAD threads,
  does not alter scoring, MovementEstimator behavior, candidate extraction,
  support maps, target tracking, or app-visible detector output.
- Parent validation:
  - `git diff --check`: passed.
  - `cmake --build tools/anomaly_test/build_timing`: passed.
  - `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
    passed, `1/1`.
  - `tools/anomaly_test/build_timing/anomaly_test`: passed with the actual
    pass count recorded here.
  - `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
    passed.
  - `./gradlew :app:compileDebugKotlin`: passed.
- Full replay manifests and performance benchmarks were not rerun because this
  packet is a behavior-preserving pressure-policy extraction and does not alter
  detector scoring or frame analysis results.
```

Replace the native pass-count sentence with the actual pass count after running validation.

- [x] **Step 2: Append Packet 189 child record**

Append this section to `docs/AnomalyDetector_Modularization_Child_Packets.md`:

```markdown
## Packet 189 - Runtime Pressure Policy Extraction

Status: parent-validated.

Mode: behavior-preserving runtime policy extraction after Packet 188.

Scope:

- Added `anomaly_runtime_pressure.{h,c}` as the focused owner for AD worker
  pressure threshold, mode selection, bypass, and naming policy.
- Routed `ffmpeg_bridge.c` pressure helpers through the standalone module while
  leaving queue storage, locks, AVFrame ownership, pthread lifecycle, render
  forwarding, and counters in the bridge.
- Added native unit coverage for threshold rounding/clamping, default policy,
  sticky/recovering mode selection, bypass decisions, and mode names.
- Preserved the existing Kotlin pressure source-guard test.

Validation:

- `git diff --check`: passed.
- `cmake --build tools/anomaly_test/build_timing`: passed.
- `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`:
  passed, `1/1`.
- `tools/anomaly_test/build_timing/anomaly_test`: passed with the actual pass
  count recorded here.
- `./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest`:
  passed.
- `./gradlew :app:compileDebugKotlin`: passed.

Replay manifest rationale:

- Full replay manifests and performance benchmarks were not rerun. This packet
  extracts existing pressure-policy math and does not alter detector scoring,
  candidate extraction, support maps, sampling-state lifecycle, MotionEstimator
  behavior, target tracking, or app-visible detector output.
```

Replace the native pass-count sentence with the actual pass count after running validation.

---

### Task 5: Run Full Validation And Final Review

**Files:**
- Verify all modified files.

- [x] **Step 1: Run whitespace validation**

Run:

```bash
git diff --check
```

Expected: no output and exit code 0.

- [x] **Step 2: Run native build**

Run:

```bash
cmake --build tools/anomaly_test/build_timing
```

Expected: build succeeds with exit code 0.

- [x] **Step 3: Run CTest**

Run:

```bash
ctest --test-dir tools/anomaly_test/build_timing --output-on-failure
```

Expected: `100% tests passed`.

- [x] **Step 4: Run direct native harness**

Run:

```bash
tools/anomaly_test/build_timing/anomaly_test
```

Expected: all native unit tests pass.

- [x] **Step 5: Run focused Kotlin pressure policy test**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests org.ncssar.rid2caltopo.video.ffmpeg.FfmpegBridgePressurePolicyTest
```

Expected: Gradle test task succeeds.

- [x] **Step 6: Run Android Kotlin/native compile gate**

Run:

```bash
./gradlew :app:compileDebugKotlin
```

Expected: Gradle task succeeds.

- [x] **Step 7: Review final diff**

Run:

```bash
git diff -- app/src/main/cpp/ffmpeg_bridge.c app/src/main/cpp/anomaly_runtime_pressure.h app/src/main/cpp/anomaly_runtime_pressure.c app/src/main/cpp/CMakeLists.txt tools/anomaly_test/CMakeLists.txt tools/anomaly_test/test_anomaly.c docs/AnomalyDetector_Modularization_Parent.md docs/AnomalyDetector_Modularization_Child_Packets.md docs/superpowers/plans/2026-06-13-r2cad-runtime-pressure-policy.md
```

Expected: diff only contains the focused pressure-policy module extraction, bridge call-throughs, build wiring, tests, ledgers, and this plan.

---

## Self-Review

- Spec coverage: The plan extracts the existing app AD worker pressure policy into a standalone C module and keeps actual queue/thread ownership in `ffmpeg_bridge.c`.
- Scope check: The plan does not split ME and AD worker threads, change scoring, change candidate extraction, move AVFrame ownership, or alter render queue behavior.
- Boundary check: `anomaly_runtime_pressure.{h,c}` owns pure pressure math; `ffmpeg_bridge.c` remains the adapter/runtime owner for Android/FFmpeg-specific state.
- Regression policy: This is behavior-preserving pressure math extraction. It runs native unit tests, CTest, a focused Kotlin pressure guard, and compile gate. Full replay manifests are documented as skipped because frame analysis results are not changed.
- Placeholder scan: No placeholder implementation steps remain; ledger validation lines explicitly instruct the worker to record actual outcomes.
- Type consistency: The new public names are consistent:
  - `anomaly_runtime_pressure_mode_t`
  - `anomaly_runtime_pressure_policy_t`
  - `ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL`
  - `ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE`
  - `ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE`
  - `ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL`
  - `anomaly_runtime_pressure_policy_make_default`
  - `anomaly_runtime_pressure_depth_threshold`
  - `anomaly_runtime_pressure_select_mode`
  - `anomaly_runtime_pressure_should_bypass_analysis`
  - `anomaly_runtime_pressure_mode_name`

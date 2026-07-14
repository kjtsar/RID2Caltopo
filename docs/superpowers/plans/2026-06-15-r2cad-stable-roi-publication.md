# R2CAD Stable ROI Publication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish app-visible ROIs only after targets are stable for most of the render-backlog window, capped at four simultaneous ROIs.

**Architecture:** Keep raw detector boxes unchanged, and replace the app-visible annotation cadence snapshot with a stability snapshot in `anomaly_detector_annotation.*`. The snapshot tracks recent box continuity by algorithm and normalized center, then returns only slots that satisfy a strict-majority rolling-window hit threshold with a three-observation floor. Qualified slots latch for one render-backlog window, already-lit ROIs are preserved before newcomers under the four-ROI cap, and visible coordinates are smoothed so boxes do not snap between nearby candidates. Confirmed thermal target tracks with healthy registration/movement evidence can be positionally carried through the short backlog window so visually persistent subjects are reinforced instead of dropped.

**Tech Stack:** Native C, existing anomaly unit harness in `tools/anomaly_test/test_anomaly.c`, Android CMake target already including `anomaly_detector_annotation.c`.

---

## File Structure

- Modify `app/src/main/cpp/anomaly_detector_annotation.h` to extend the annotation snapshot state with stability slots and expose a stable-publication function.
- Modify `app/src/main/cpp/anomaly_detector_annotation.c` to implement rolling-window slot matching, eligibility, ranking, and four-ROI output.
- Modify `tools/anomaly_test/test_anomaly.c` to add focused TDD tests near the existing annotation cadence tests.
- Modify `docs/AnomalyDetector_Modularization_Parent.md` and `docs/AnomalyDetector_Modularization_Child_Packets.md` only after validation, recording the packet scope and gates.

## Task 1: Add Red Tests For Stable Publication

**Files:**
- Modify: `tools/anomaly_test/test_anomaly.c`

- [x] Add helper functions near the existing annotation cadence tests:

```c
static anomaly_detector_annotation_t make_test_annotation(
        float left,
        float top,
        float right,
        float bottom,
        float weight,
        int algorithm) {
    anomaly_detector_annotation_t box;
    memset(&box, 0, sizeof(box));
    box.left_norm = left;
    box.top_norm = top;
    box.right_norm = right;
    box.bottom_norm = bottom;
    box.weight = weight;
    box.algorithm = algorithm;
    return box;
}
```

- [x] Add tests that call `anomaly_detector_result_apply_annotation_stability()`:

```c
static void test_detector_facade_annotation_stability_hides_transient_boxes(void) {
    anomaly_detector_result_t result;
    anomaly_detector_annotation_cadence_snapshot_state_t state;
    memset(&result, 0, sizeof(result));
    anomaly_detector_annotation_cadence_snapshot_state_init(&state);
    result.box_count = 1;
    result.boxes[0] = make_test_annotation(0.10f, 0.10f, 0.20f, 0.20f, 0.95f, ANOMALY_ALGO_THERMAL);

    anomaly_detector_annotation_view_t view =
        anomaly_detector_result_apply_annotation_stability(&result, &state, 0, 15);
    EXPECT(view.boxes == NULL && view.box_count == 0,
           "detector facade annotation stability: one-frame ROI remains hidden");

    view = anomaly_detector_result_apply_annotation_stability(&result, &state, 1, 15);
    EXPECT(view.boxes == NULL && view.box_count == 0,
           "detector facade annotation stability: below floor remains hidden");
}
```

```c
static void test_detector_facade_annotation_stability_requires_window_majority(void) {
    anomaly_detector_result_t result;
    anomaly_detector_annotation_cadence_snapshot_state_t state;
    memset(&result, 0, sizeof(result));
    anomaly_detector_annotation_cadence_snapshot_state_init(&state);
    result.box_count = 1;
    result.boxes[0] = make_test_annotation(0.30f, 0.30f, 0.40f, 0.40f, 0.95f, ANOMALY_ALGO_THERMAL);

    anomaly_detector_annotation_view_t view = {0};
    for (int frame = 0; frame < 7; frame++) {
        view = anomaly_detector_result_apply_annotation_stability(&result, &state, frame, 15);
    }
    EXPECT(view.boxes == NULL && view.box_count == 0,
           "detector facade annotation stability: less than a majority of the window remains hidden");

    view = anomaly_detector_result_apply_annotation_stability(&result, &state, 7, 15);
    EXPECT(view.boxes == state.boxes && view.box_count == 1 &&
           fabsf(view.boxes[0].left_norm - 0.30f) < 0.0001f,
           "detector facade annotation stability: majority of the window publishes ROI");
}
```

```c
static void test_detector_facade_annotation_stability_caps_and_ranks_rois(void) {
    anomaly_detector_result_t result;
    anomaly_detector_annotation_cadence_snapshot_state_t state;
    memset(&result, 0, sizeof(result));
    anomaly_detector_annotation_cadence_snapshot_state_init(&state);
    result.box_count = 5;
    for (int i = 0; i < 5; i++) {
        float x = 0.05f + 0.10f * (float)i;
        result.boxes[i] = make_test_annotation(x, 0.20f, x + 0.04f, 0.26f, 0.50f + 0.08f * (float)i, ANOMALY_ALGO_THERMAL);
    }

    anomaly_detector_annotation_view_t view = {0};
    for (int frame = 0; frame < 9; frame++) {
        view = anomaly_detector_result_apply_annotation_stability(&result, &state, frame, 15);
    }
    EXPECT(view.boxes == state.boxes && view.box_count == 4,
           "detector facade annotation stability: stable output is capped at four ROIs");
    EXPECT(fabsf(view.boxes[0].left_norm - result.boxes[4].left_norm) < 0.0001f,
           "detector facade annotation stability: strongest ROI is ranked first");
}
```

```c
static void test_detector_facade_annotation_stability_ages_out_missing_slots(void) {
    anomaly_detector_result_t result;
    anomaly_detector_annotation_cadence_snapshot_state_t state;
    memset(&result, 0, sizeof(result));
    anomaly_detector_annotation_cadence_snapshot_state_init(&state);
    result.box_count = 1;
    result.boxes[0] = make_test_annotation(0.60f, 0.30f, 0.68f, 0.38f, 0.95f, ANOMALY_ALGO_THERMAL);

    anomaly_detector_annotation_view_t view = {0};
    for (int frame = 0; frame < 9; frame++) {
        view = anomaly_detector_result_apply_annotation_stability(&result, &state, frame, 15);
    }
    EXPECT(view.box_count == 1,
           "detector facade annotation stability: stable ROI is initially visible");

    result.box_count = 0;
    for (int frame = 9; frame < 25; frame++) {
        view = anomaly_detector_result_apply_annotation_stability(&result, &state, frame, 15);
    }
    EXPECT(view.boxes == NULL && view.box_count == 0,
           "detector facade annotation stability: missing ROI ages out after window");
}
```

- [x] Register the tests in `main()` after `test_detector_facade_annotation_cadence_snapshot_contract()`.
- [x] Run `cmake --build tools/anomaly_test/build_timing`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [x] Expected result: build fails because `anomaly_detector_result_apply_annotation_stability` does not exist.

## Task 2: Implement Stable Annotation Snapshot

**Files:**
- Modify: `app/src/main/cpp/anomaly_detector_annotation.h`
- Modify: `app/src/main/cpp/anomaly_detector_annotation.c`

- [x] Add constants and slot state to the header:

```c
#define ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS 8

typedef struct {
    bool initialized;
    bool active;
    int algorithm;
    int hit_count;
    int64_t first_seen_frame_ordinal;
    int64_t last_seen_frame_ordinal;
    float score_sum;
    anomaly_detector_annotation_t box;
} anomaly_detector_annotation_stability_slot_t;
```

- [x] Extend `anomaly_detector_annotation_cadence_snapshot_state_t` with:

```c
anomaly_detector_annotation_stability_slot_t stable_slots[ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS];
```

- [x] Declare:

```c
anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_stability(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    window_frames);
```

- [x] Implement the function to normalize `window_frames` to at least one, require `max(3, floor(window_frames / 2) + 1)` hits, match boxes by same algorithm and center distance within `0.12f`, age inactive slots when `frame_ordinal - last_seen_frame_ordinal >= window_frames`, latch qualified slots for the backlog window, keep already-lit eligible slots before newcomers, smooth visible coordinates toward current raw boxes, copy eligible slots into `state->boxes`, sort by strength descending for open slots, cap output at four, and return empty when none qualify.
- [x] Update app-visible callers to use the stability function for normal app-visible annotations.
- [x] Add positional-consistency target-track carry so confirmed thermal targets with healthy registration/movement evidence persist through the backlog window.
- [x] Run `cmake --build tools/anomaly_test/build_timing` and `tools/anomaly_test/build_timing/anomaly_test`; expected result: new tests pass.
- [x] Add follow-up red/green tests for backlog-window publication latching, already-lit ROI priority over newcomers, and smoothing lit ROI motion.

## Task 3: Validation And Docs

**Files:**
- Modify: `docs/AnomalyDetector_Modularization_Parent.md`
- Modify: `docs/AnomalyDetector_Modularization_Child_Packets.md`

- [x] Run `git diff --check`.
- [x] Run `ctest --test-dir tools/anomaly_test/build_timing --output-on-failure`.
- [x] Run `tools/anomaly_test/build_timing/anomaly_test`.
- [x] Run `./gradlew :app:compileDebugKotlin`.
- [x] Add a packet note to both anomaly modularization ledgers saying this packet changed app-visible ROI publication and positional thermal carry, not scoring/candidate extraction.
- [x] Record final manual CSV metrics:
  - PowerHouse1 opening app-local recall `0.453704`, with one matched thermal track.
  - Max simultaneous app-visible ROIs: PowerHouse1 `3`, PowerHouse2 `3`, PowerHouse3 `4`.
  - Short app-visible tracks `<15` frames improved: PowerHouse1 `15 -> 1`, PowerHouse2 `26 -> 3`, PowerHouse3 `135 -> 3`.
  - One-frame app-visible tracks improved: PowerHouse1 `0 -> 0`, PowerHouse2 `3 -> 0`, PowerHouse3 `59 -> 0`.
- [ ] Commit code, tests, docs, and this plan after validation.

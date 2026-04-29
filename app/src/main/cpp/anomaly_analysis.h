// anomaly_analysis.h — Standalone anomaly detection for SAR drone video.
//
// No JNI, FFmpeg, or Android dependencies.  All inputs/outputs are plain C
// (RGBA pixel buffers, simple structs).  This makes it possible to unit-test
// the algorithms directly with a native host binary.
#pragma once

#include <stdbool.h>
#include <stdint.h>

// ── Algorithm selector bits ────────────────────────────────────────────────
#define ANOMALY_ALGO_COLOR    0x01
#define ANOMALY_ALGO_THERMAL  0x02
#define ANOMALY_ALGO_MOTION   0x04

// ── Thermal polarity ───────────────────────────────────────────────────────
#define ANOMALY_THERMAL_WHITE_HOT 1
#define ANOMALY_THERMAL_BLACK_HOT 2

// ── Default tuning knobs ───────────────────────────────────────────────────
#define ANOMALY_DEFAULT_FRAME_STRIDE      3
#define ANOMALY_DEFAULT_SCORE_THRESHOLD   1.8f
#define ANOMALY_DEFAULT_MIN_AREA_FRACTION 0.0015f
#define ANOMALY_SCAN_ZONE_DEFAULT         0.80f
#define ANOMALY_DEFAULT_MIN_HITS          2

// ── GMV / similarity-transform tuning ─────────────────────────────────────
#define ANOMALY_GMV_SEARCH_RADIUS   20
#define ANOMALY_GMV_PATCH_HALF       2
#define ANOMALY_GMV_RESIDUAL_THRESH  0.05f
#define ANOMALY_GMV_MIN_SCALE        0.70f
#define ANOMALY_GMV_MAX_SCALE        1.43f

// ── Temporal accumulator tuning ────────────────────────────────────────────
#define ANOMALY_ACC_EMA_ALPHA    0.30f
#define ANOMALY_ACC_GATE_RADIUS  0.15f
#define ANOMALY_ACC_HOLD_FRAMES  8
#define ANOMALY_ACC_MAX_HITS     10

#define ANOMALY_MAX_BOXES_PER_FRAME 3

// ── Types ──────────────────────────────────────────────────────────────────

typedef struct {
    float left_norm, top_norm, right_norm, bottom_norm;
    uint8_t r, g, b;
    float weight;   // stroke scale 0–1: thin on first visible hit, full at sustained
} anomaly_box_t;

// All config values read from Kotlin via JNI; must be accessed under g_lock
// in the bridge but are passed by value into anomaly_process_frame().
typedef struct {
    bool  enabled;
    int   algorithm_mask;
    int   frame_stride;
    float score_threshold;
    float min_area_fraction;
    int   thermal_polarity;
    float scan_zone;
    int   min_hits;
} anomaly_config_t;

// Per-stream mutable state owned by the decode thread (no locking needed).
typedef struct {
    int64_t  frame_counter;
    // Per-algorithm temporal accumulators: index 0=color, 1=thermal, 2=motion
    float    acc_cx[3];
    float    acc_cy[3];
    int      acc_hits[3];
    int      acc_hold[3];
    bool     acc_active[3];
    // Previous-frame luma grid for motion estimation
    uint8_t *prev_luma;
    int      prev_luma_width;
    int      prev_luma_height;
} anomaly_state_t;

// Returned from anomaly_process_frame(); caller may inspect detections.
typedef struct {
    int           box_count;
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    bool          had_discontinuity;  // true when a scene cut was detected
} anomaly_result_t;

// 2-D similarity transform: curr→prev mapping.
//   prev_x = a*curr_x - b*curr_y + tx
//   prev_y = b*curr_x + a*curr_y + ty
typedef struct {
    float a, b, tx, ty;
    float mean_residual;
    bool  valid;
} similarity_2d_t;

// ── API ────────────────────────────────────────────────────────────────────

// Zero-initialize state (no heap allocation; call before first use).
void anomaly_state_init(anomaly_state_t *state);

// Reset temporal accumulators and discard prev_luma (e.g. on config change).
void anomaly_state_reset(anomaly_state_t *state);

// Full cleanup — same as reset but intended for final teardown.
void anomaly_state_cleanup(anomaly_state_t *state);

// Process one RGBA frame.
//   - Increments internal frame counter; skips stride-gated frames.
//   - Draws detection boxes in-place on rgba (may be NULL in tests if you
//     only care about the returned result).
//   - result_out: filled when non-NULL; valid even when box_count == 0.
//   - Returns the number of boxes drawn (0 = nothing visible this frame).
int anomaly_process_frame(
    anomaly_state_t        *state,
    const anomaly_config_t *cfg,
    uint8_t                *rgba,
    int                     rgba_stride,
    int                     width,
    int                     height,
    int64_t                 source_ts_us,
    anomaly_result_t       *result_out
);

// Exposed for direct unit testing of the math.
similarity_2d_t fit_similarity_2d(
    const float *src_x, const float *src_y,
    const float *dst_x, const float *dst_y,
    int n
);

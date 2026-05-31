// test_anomaly.c — Native unit tests for the anomaly detection algorithms.
//
// Build & run (from this directory):
//   cmake -B build && cmake --build build && ./build/anomaly_test
//
// Tests exercise anomaly_analysis.c directly — the same code the Android app
// uses — so there is no separate reimplementation to keep in sync.
#include "anomaly_analysis.h"
#include "anomaly_appearance_detector.h"
#include "anomaly_appearance_candidates.h"
#include "anomaly_buffer.h"
#include "anomaly_color_detector.h"
#include "anomaly_debug_helpers.h"
#include "anomaly_detector.h"
#include "anomaly_frame_geometry.h"
#include "anomaly_grid_region.h"
#include "anomaly_linear_solve.h"
#include "anomaly_motion_estimator.h"
#include "anomaly_registration_cache.h"
#include "anomaly_registration_image.h"
#include "anomaly_registration_model.h"
#include "anomaly_registration_quality.h"
#include "anomaly_result_builder.h"
#include "anomaly_roi_tracks.h"
#include "anomaly_roi_state.h"
#include "anomaly_runtime_config.h"
#include "anomaly_saliency_tracks.h"
#include "anomaly_scan_planner.h"
#include "anomaly_scratch.h"
#include "anomaly_target_observations.h"
#include "anomaly_target_revisit.h"
#include "anomaly_target_tracks.h"
#include "anomaly_thermal_detector.h"
#include "anomaly_thermal_state.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

// ── Minimal test framework ─────────────────────────────────────────────────

static int g_pass = 0;
static int g_fail = 0;

#define EXPECT(cond, msg) \
    do { \
        if (cond) { \
            g_pass++; \
        } else { \
            fprintf(stderr, "FAIL [%s:%d] %s\n", __FILE__, __LINE__, msg); \
            g_fail++; \
        } \
    } while (0)

#define EXPECT_NEAR(a, b, tol, msg) \
    EXPECT(fabsf((float)(a) - (float)(b)) <= (float)(tol), msg)

// ── Frame helpers ──────────────────────────────────────────────────────────

// Allocate a W×H RGBA frame filled with a uniform gray value.
static uint8_t *make_gray_frame(int w, int h, uint8_t gray) {
    uint8_t *buf = (uint8_t *)malloc((size_t)w * h * 4);
    for (int i = 0; i < w * h; i++) {
        buf[i * 4 + 0] = gray;
        buf[i * 4 + 1] = gray;
        buf[i * 4 + 2] = gray;
        buf[i * 4 + 3] = 0xFF;
    }
    return buf;
}

// Set a single pixel in an RGBA frame.
static void set_pixel(uint8_t *buf, int stride, int x, int y,
                      uint8_t r, uint8_t g, uint8_t b) {
    uint8_t *px = buf + y * stride + x * 4;
    px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
}

static bool rgba_pixel_is(const uint8_t *buf, int stride, int x, int y,
                          uint8_t r, uint8_t g, uint8_t b, uint8_t a) {
    const uint8_t *px = buf + y * stride + x * 4;
    return px[0] == r && px[1] == g && px[2] == b && px[3] == a;
}

static anomaly_config_t default_cfg(int algorithm_mask);

static void test_anomaly_buffer_rejects_null_inputs(void) {
    size_t capacity = 0;
    uint8_t *u8 = NULL;
    float *f32 = NULL;
    double *f64 = NULL;
    int *i32 = NULL;

    EXPECT(!anomaly_buffer_ensure_u8_capacity(NULL, &capacity, 4),
           "buffer u8 ensure: NULL buffer pointer is rejected");
    EXPECT(!anomaly_buffer_ensure_u8_capacity(&u8, NULL, 4),
           "buffer u8 ensure: NULL capacity pointer is rejected");
    EXPECT(!anomaly_buffer_resize_u8(NULL, 4),
           "buffer u8 resize: NULL buffer pointer is rejected");

    EXPECT(!anomaly_buffer_ensure_float_capacity(NULL, &capacity, 4),
           "buffer float ensure: NULL buffer pointer is rejected");
    EXPECT(!anomaly_buffer_ensure_float_capacity(&f32, NULL, 4),
           "buffer float ensure: NULL capacity pointer is rejected");
    EXPECT(!anomaly_buffer_resize_float(NULL, 4),
           "buffer float resize: NULL buffer pointer is rejected");

    EXPECT(!anomaly_buffer_ensure_double_capacity(NULL, &capacity, 4),
           "buffer double ensure: NULL buffer pointer is rejected");
    EXPECT(!anomaly_buffer_ensure_double_capacity(&f64, NULL, 4),
           "buffer double ensure: NULL capacity pointer is rejected");
    EXPECT(!anomaly_buffer_ensure_int_capacity(NULL, &capacity, 4),
           "buffer int ensure: NULL buffer pointer is rejected");
    EXPECT(!anomaly_buffer_ensure_int_capacity(&i32, NULL, 4),
           "buffer int ensure: NULL capacity pointer is rejected");
}

static void test_anomaly_buffer_zero_count_is_noop_success(void) {
    size_t capacity = 7;
    uint8_t *u8 = NULL;
    float *f32 = NULL;
    double *f64 = NULL;
    int *i32 = NULL;

    EXPECT(anomaly_buffer_ensure_u8_capacity(&u8, &capacity, 0),
           "buffer u8 ensure: zero count succeeds");
    EXPECT(u8 == NULL && capacity == 7,
           "buffer u8 ensure: zero count leaves buffer and capacity unchanged");
    EXPECT(anomaly_buffer_resize_u8(&u8, 0),
           "buffer u8 resize: zero count succeeds");
    EXPECT(u8 == NULL,
           "buffer u8 resize: zero count leaves buffer unchanged");

    EXPECT(anomaly_buffer_ensure_float_capacity(&f32, &capacity, 0),
           "buffer float ensure: zero count succeeds");
    EXPECT(f32 == NULL && capacity == 7,
           "buffer float ensure: zero count leaves buffer and capacity unchanged");
    EXPECT(anomaly_buffer_resize_float(&f32, 0),
           "buffer float resize: zero count succeeds");
    EXPECT(f32 == NULL,
           "buffer float resize: zero count leaves buffer unchanged");

    EXPECT(anomaly_buffer_ensure_double_capacity(&f64, &capacity, 0),
           "buffer double ensure: zero count succeeds");
    EXPECT(f64 == NULL && capacity == 7,
           "buffer double ensure: zero count leaves buffer and capacity unchanged");
    EXPECT(anomaly_buffer_ensure_int_capacity(&i32, &capacity, 0),
           "buffer int ensure: zero count succeeds");
    EXPECT(i32 == NULL && capacity == 7,
           "buffer int ensure: zero count leaves buffer and capacity unchanged");
}

static void test_anomaly_buffer_grows_preserves_capacity_and_allows_writes(void) {
    uint8_t *u8 = NULL;
    float *f32 = NULL;
    double *f64 = NULL;
    int *i32 = NULL;
    size_t u8_capacity = 0;
    size_t f32_capacity = 0;
    size_t f64_capacity = 0;
    size_t i32_capacity = 0;

    EXPECT(anomaly_buffer_ensure_u8_capacity(&u8, &u8_capacity, 4),
           "buffer u8 ensure: grows buffer");
    EXPECT(u8 != NULL && u8_capacity == 4,
           "buffer u8 ensure: records grown capacity");
    u8[3] = 12;
    uint8_t *u8_saved = u8;
    EXPECT(anomaly_buffer_ensure_u8_capacity(&u8, &u8_capacity, 2),
           "buffer u8 ensure: smaller request succeeds");
    EXPECT(u8 == u8_saved && u8_capacity == 4 && u8[3] == 12,
           "buffer u8 ensure: sufficient capacity is not shrunk");

    EXPECT(anomaly_buffer_ensure_float_capacity(&f32, &f32_capacity, 3),
           "buffer float ensure: grows buffer");
    EXPECT(f32 != NULL && f32_capacity == 3,
           "buffer float ensure: records grown capacity");
    f32[2] = 1.25f;
    float *f32_saved = f32;
    EXPECT(anomaly_buffer_ensure_float_capacity(&f32, &f32_capacity, 1),
           "buffer float ensure: smaller request succeeds");
    EXPECT(f32 == f32_saved && f32_capacity == 3 && fabsf(f32[2] - 1.25f) < 0.0001f,
           "buffer float ensure: sufficient capacity is not shrunk");

    EXPECT(anomaly_buffer_ensure_double_capacity(&f64, &f64_capacity, 2),
           "buffer double ensure: grows buffer");
    EXPECT(f64 != NULL && f64_capacity == 2,
           "buffer double ensure: records grown capacity");
    f64[1] = 2.50;

    EXPECT(anomaly_buffer_ensure_int_capacity(&i32, &i32_capacity, 5),
           "buffer int ensure: grows buffer");
    EXPECT(i32 != NULL && i32_capacity == 5,
           "buffer int ensure: records grown capacity");
    i32[4] = 42;
    int *i32_saved = i32;
    EXPECT(anomaly_buffer_ensure_int_capacity(&i32, &i32_capacity, 3),
           "buffer int ensure: smaller request succeeds");
    EXPECT(i32 == i32_saved && i32_capacity == 5 && i32[4] == 42,
           "buffer int ensure: sufficient capacity is not shrunk");

    EXPECT(f64[1] == 2.50,
           "buffer double ensure: grown buffer accepts writes");

    free(u8);
    free(f32);
    free(f64);
    free(i32);
}

static void test_anomaly_buffer_resize_allocates_without_capacity(void) {
    uint8_t *u8 = NULL;
    float *f32 = NULL;

    EXPECT(anomaly_buffer_resize_u8(&u8, 3),
           "buffer u8 resize: allocates requested count");
    EXPECT(u8 != NULL,
           "buffer u8 resize: buffer is assigned");
    u8[2] = 9;
    EXPECT(u8[2] == 9,
           "buffer u8 resize: resized buffer accepts writes");

    EXPECT(anomaly_buffer_resize_float(&f32, 2),
           "buffer float resize: allocates requested count");
    EXPECT(f32 != NULL,
           "buffer float resize: buffer is assigned");
    f32[1] = 3.5f;
    EXPECT_NEAR(f32[1], 3.5f, 0.0001f,
                "buffer float resize: resized buffer accepts writes");

    free(u8);
    free(f32);
}

static void test_grid_region_active_mask_bounds_rejects_invalid_and_empty(void) {
    uint8_t empty[6] = {0};
    int min_sx = 11;
    int min_sy = 12;
    int max_sx = 13;
    int max_sy = 14;

    EXPECT(!anomaly_grid_region_compute_active_mask_bounds(
                   NULL, 3, 2, 1, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: NULL mask is rejected");
    EXPECT(!anomaly_grid_region_compute_active_mask_bounds(
                   empty, 3, 2, 1, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: empty mask returns false");
    EXPECT(min_sx == 11 && min_sy == 12 && max_sx == 13 && max_sy == 14,
           "grid region bounds: empty mask leaves outputs untouched");
    EXPECT(!anomaly_grid_region_compute_active_mask_bounds(
                   empty, 0, 2, 1, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: nonpositive width is rejected");
    EXPECT(!anomaly_grid_region_compute_active_mask_bounds(
                   empty, 3, 2, 1, NULL, &min_sy, &max_sx, &max_sy),
           "grid region bounds: NULL output is rejected");
}

static void test_grid_region_active_mask_bounds_padding_and_clamp(void) {
    uint8_t mask[20] = {0};
    int min_sx = -1;
    int min_sy = -1;
    int max_sx = -1;
    int max_sy = -1;

    mask[0 * 5 + 4] = 1;
    EXPECT(anomaly_grid_region_compute_active_mask_bounds(
                   mask, 5, 4, 2, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: single active cell returns true");
    EXPECT(min_sx == 2 && min_sy == 0 && max_sx == 4 && max_sy == 2,
           "grid region bounds: padded single cell clamps to grid");

    memset(mask, 0, sizeof(mask));
    mask[1 * 5 + 1] = 1;
    mask[3 * 5 + 3] = 1;
    EXPECT(anomaly_grid_region_compute_active_mask_bounds(
                   mask, 5, 4, 1, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: multiple cells return true");
    EXPECT(min_sx == 0 && min_sy == 0 && max_sx == 4 && max_sy == 3,
           "grid region bounds: multiple cells produce padded min/max");

    EXPECT(anomaly_grid_region_compute_active_mask_bounds(
                   mask, 5, 4, -3, &min_sx, &min_sy, &max_sx, &max_sy),
           "grid region bounds: negative pad returns true");
    EXPECT(min_sx == 1 && min_sy == 1 && max_sx == 3 && max_sy == 3,
           "grid region bounds: negative pad behaves like zero");
}

static void test_grid_region_float_zero_and_copy_touch_only_region(void) {
    float dst[20];
    float src[20];
    for (int i = 0; i < 20; i++) {
        dst[i] = 100.0f + (float)i;
        src[i] = 200.0f + (float)i;
    }

    anomaly_grid_region_zero_float(dst, 5, 1, 1, 3, 2);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 5; x++) {
            int idx = y * 5 + x;
            bool inside = x >= 1 && x <= 3 && y >= 1 && y <= 2;
            if (inside) {
                EXPECT_NEAR(dst[idx], 0.0f, 0.0001f,
                            "grid region zero float: clears requested cell");
            } else {
                EXPECT_NEAR(dst[idx], 100.0f + (float)idx, 0.0001f,
                            "grid region zero float: leaves outside cell untouched");
            }
        }
    }

    anomaly_grid_region_copy_float(dst, src, 5, 2, 0, 4, 1);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 5; x++) {
            int idx = y * 5 + x;
            bool copied = x >= 2 && x <= 4 && y >= 0 && y <= 1;
            bool zeroed = x >= 1 && x <= 3 && y >= 1 && y <= 2;
            float expected = copied
                ? src[idx]
                : (zeroed ? 0.0f : 100.0f + (float)idx);
            EXPECT_NEAR(dst[idx], expected, 0.0001f,
                        "grid region copy float: only requested region is copied");
        }
    }
}

static void test_grid_region_u8_zero_noops_invalid_and_touches_region(void) {
    uint8_t map[20];
    for (int i = 0; i < 20; i++) {
        map[i] = (uint8_t)(i + 1);
    }

    anomaly_grid_region_zero_u8(map, 5, 3, 2, 1, 2);
    anomaly_grid_region_zero_u8(map, 5, 1, 3, 2, 1);
    anomaly_grid_region_zero_u8(map, 0, 0, 0, 1, 1);
    for (int i = 0; i < 20; i++) {
        EXPECT(map[i] == (uint8_t)(i + 1),
               "grid region zero u8: invalid spans and width are no-ops");
    }

    anomaly_grid_region_zero_u8(map, 5, 0, 2, 2, 3);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 5; x++) {
            int idx = y * 5 + x;
            bool inside = x >= 0 && x <= 2 && y >= 2 && y <= 3;
            EXPECT(map[idx] == (inside ? 0u : (uint8_t)(idx + 1)),
                   "grid region zero u8: only requested region is cleared");
        }
    }
}

static void test_debug_insert_top_candidate_noops_invalid_inputs(void) {
    anomaly_debug_candidate_t candidates[2];
    memset(candidates, 0, sizeof(candidates));
    candidates[0].valid = true;
    candidates[0].pixel_x = 7;
    candidates[0].combined_score = 1.5f;
    int count = 1;

    anomaly_debug_insert_top_candidate(NULL, &count, 2, 1, 2, 0.1f, 0.2f, 0.3f, 0.4f, 2.0f);
    EXPECT(count == 1 && candidates[0].pixel_x == 7,
           "debug top insert: NULL candidates no-ops");

    anomaly_debug_insert_top_candidate(candidates, NULL, 2, 1, 2, 0.1f, 0.2f, 0.3f, 0.4f, 2.0f);
    EXPECT(count == 1 && candidates[0].pixel_x == 7,
           "debug top insert: NULL count no-ops");

    anomaly_debug_insert_top_candidate(candidates, &count, 0, 1, 2, 0.1f, 0.2f, 0.3f, 0.4f, 2.0f);
    EXPECT(count == 1 && candidates[0].pixel_x == 7,
           "debug top insert: no capacity no-ops");

    anomaly_debug_insert_top_candidate(candidates, &count, 2, 1, 2, 0.1f, 0.2f, 0.3f, 0.4f, 0.0f);
    EXPECT(count == 1 && candidates[0].pixel_x == 7,
           "debug top insert: nonpositive score no-ops");
}

static void test_debug_insert_top_candidate_empty_and_field_copy(void) {
    anomaly_debug_candidate_t candidates[3];
    memset(candidates, 0, sizeof(candidates));
    int count = 0;

    anomaly_debug_insert_top_candidate(
            candidates, &count, 3,
            11, 13,
            0.25f, 0.75f,
            1.25f, 2.50f, 3.75f);

    EXPECT(count == 1,
           "debug top insert: count increments into empty list");
    EXPECT(candidates[0].valid &&
           candidates[0].pixel_x == 11 &&
           candidates[0].pixel_y == 13 &&
           fabsf(candidates[0].x_norm - 0.25f) < 0.0001f &&
           fabsf(candidates[0].y_norm - 0.75f) < 0.0001f &&
           fabsf(candidates[0].spatial_score - 1.25f) < 0.0001f &&
           fabsf(candidates[0].temporal_score - 2.50f) < 0.0001f &&
           fabsf(candidates[0].combined_score - 3.75f) < 0.0001f,
           "debug top insert: written slot fields match inputs");
}

static void test_debug_insert_top_candidate_descending_middle_insert(void) {
    anomaly_debug_candidate_t candidates[4];
    memset(candidates, 0, sizeof(candidates));
    int count = 0;

    anomaly_debug_insert_top_candidate(candidates, &count, 4, 10, 0, 0.0f, 0.0f, 0.0f, 0.0f, 10.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 4, 30, 0, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 4, 70, 0, 0.0f, 0.0f, 0.0f, 0.0f, 7.0f);

    EXPECT(count == 3,
           "debug top insert: count tracks inserted candidates");
    EXPECT(candidates[0].pixel_x == 10 && candidates[1].pixel_x == 70 && candidates[2].pixel_x == 30,
           "debug top insert: middle insertion preserves descending score order");
    EXPECT_NEAR(candidates[0].combined_score, 10.0f, 0.0001f,
                "debug top insert: first score remains highest");
    EXPECT_NEAR(candidates[1].combined_score, 7.0f, 0.0001f,
                "debug top insert: inserted middle score is second");
    EXPECT_NEAR(candidates[2].combined_score, 3.0f, 0.0001f,
                "debug top insert: lower score shifts to tail");
}

static void test_debug_insert_top_candidate_capacity_tail_behavior(void) {
    anomaly_debug_candidate_t candidates[3];
    memset(candidates, 0, sizeof(candidates));
    int count = 0;

    anomaly_debug_insert_top_candidate(candidates, &count, 3, 90, 0, 0.0f, 0.0f, 0.0f, 0.0f, 9.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 3, 60, 0, 0.0f, 0.0f, 0.0f, 0.0f, 6.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 3, 30, 0, 0.0f, 0.0f, 0.0f, 0.0f, 3.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 3, 20, 0, 0.0f, 0.0f, 0.0f, 0.0f, 2.0f);

    EXPECT(count == 3,
           "debug top insert: full list count stays capped");
    EXPECT(candidates[0].pixel_x == 90 && candidates[1].pixel_x == 60 && candidates[2].pixel_x == 30,
           "debug top insert: weaker tail candidate is dropped when full");

    anomaly_debug_insert_top_candidate(candidates, &count, 3, 80, 0, 0.0f, 0.0f, 0.0f, 0.0f, 8.0f);

    EXPECT(count == 3,
           "debug top insert: high-rank insert into full list stays capped");
    EXPECT(candidates[0].pixel_x == 90 && candidates[1].pixel_x == 80 && candidates[2].pixel_x == 60,
           "debug top insert: high-rank insert shifts and drops old tail");
}

static void test_debug_insert_top_candidate_equal_score_appends(void) {
    anomaly_debug_candidate_t candidates[3];
    memset(candidates, 0, sizeof(candidates));
    int count = 0;

    anomaly_debug_insert_top_candidate(candidates, &count, 3, 1, 0, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 3, 2, 0, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f);

    EXPECT(count == 2,
           "debug top insert: equal score appends when there is room");
    EXPECT(candidates[0].pixel_x == 1 && candidates[1].pixel_x == 2,
           "debug top insert: equal score preserves existing order using strict greater-than");

    anomaly_debug_insert_top_candidate(candidates, &count, 3, 3, 0, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f);
    anomaly_debug_insert_top_candidate(candidates, &count, 3, 4, 0, 0.0f, 0.0f, 0.0f, 0.0f, 5.0f);

    EXPECT(count == 3,
           "debug top insert: full equal-score list stays capped");
    EXPECT(candidates[0].pixel_x == 1 && candidates[1].pixel_x == 2 && candidates[2].pixel_x == 3,
           "debug top insert: equal-score candidate below full capacity is dropped");
}

static void test_debug_append_center_box_fields_and_clamps(void) {
    anomaly_box_t boxes[3];
    memset(boxes, 0, sizeof(boxes));
    int count = 0;

    anomaly_debug_append_center_box(
            boxes, &count, 3,
            0.50f, 0.25f, 0.20f, 0.10f,
            0x12, 0x34, 0x56,
            0.65f);

    EXPECT(count == 1,
           "debug append center box: valid box increments count");
    EXPECT_NEAR(boxes[0].left_norm, 0.40f, 0.0001f,
                "debug append center box: left edge uses half width");
    EXPECT_NEAR(boxes[0].right_norm, 0.60f, 0.0001f,
                "debug append center box: right edge uses half width");
    EXPECT_NEAR(boxes[0].top_norm, 0.20f, 0.0001f,
                "debug append center box: top edge uses half height");
    EXPECT_NEAR(boxes[0].bottom_norm, 0.30f, 0.0001f,
                "debug append center box: bottom edge uses half height");
    EXPECT(boxes[0].r == 0x12 && boxes[0].g == 0x34 && boxes[0].b == 0x56,
           "debug append center box: RGB fields are copied");
    EXPECT(boxes[0].draw_crosshair == 1u,
           "debug append center box: center boxes always draw crosshair");
    EXPECT_NEAR(boxes[0].weight, 0.65f, 0.0001f,
                "debug append center box: valid weight is preserved");

    anomaly_debug_append_center_box(
            boxes, &count, 3,
            -0.10f, 1.10f, 0.40f, 0.40f,
            0xAA, 0xBB, 0xCC,
            2.0f);

    EXPECT(count == 2,
           "debug append center box: clamped edge box increments count");
    EXPECT_NEAR(boxes[1].left_norm, 0.0f, 0.0001f,
                "debug append center box: left edge clamps low");
    EXPECT_NEAR(boxes[1].right_norm, 0.10f, 0.0001f,
                "debug append center box: right edge remains normalized after clamp");
    EXPECT_NEAR(boxes[1].top_norm, 0.90f, 0.0001f,
                "debug append center box: top edge remains normalized after clamp");
    EXPECT_NEAR(boxes[1].bottom_norm, 1.0f, 0.0001f,
                "debug append center box: bottom edge clamps high");
    EXPECT_NEAR(boxes[1].weight, 1.0f, 0.0001f,
                "debug append center box: high weight clamps to one");
}

static void test_debug_append_center_box_noops_invalid_and_capped(void) {
    anomaly_box_t boxes[2];
    memset(boxes, 0, sizeof(boxes));
    boxes[0].left_norm = 0.11f;
    boxes[0].right_norm = 0.22f;
    int count = 1;

    anomaly_debug_append_center_box(NULL, &count, 2, 0.5f, 0.5f, 0.2f, 0.2f,
                                    1, 2, 3, 0.5f);
    EXPECT(count == 1 && fabsf(boxes[0].left_norm - 0.11f) < 0.0001f,
           "debug append center box: NULL boxes no-op");

    anomaly_debug_append_center_box(boxes, NULL, 2, 0.5f, 0.5f, 0.2f, 0.2f,
                                    1, 2, 3, 0.5f);
    EXPECT(count == 1 && fabsf(boxes[0].right_norm - 0.22f) < 0.0001f,
           "debug append center box: NULL count no-op");

    anomaly_debug_append_center_box(boxes, &count, 0, 0.5f, 0.5f, 0.2f, 0.2f,
                                    1, 2, 3, 0.5f);
    EXPECT(count == 1,
           "debug append center box: nonpositive capacity no-op");

    anomaly_debug_append_center_box(boxes, &count, 2, 1.20f, 0.5f, 0.10f, 0.2f,
                                    1, 2, 3, 0.5f);
    EXPECT(count == 1,
           "debug append center box: invalid box after clamp no-op");

    anomaly_debug_append_center_box(boxes, &count, 2, 0.50f, 0.50f, 0.20f, 0.20f,
                                    4, 5, 6, -2.0f);
    EXPECT(count == 2,
           "debug append center box: second valid box reaches cap");
    EXPECT_NEAR(boxes[1].weight, 0.0f, 0.0001f,
                "debug append center box: low weight clamps to zero");

    anomaly_debug_append_center_box(boxes, &count, 2, 0.30f, 0.30f, 0.20f, 0.20f,
                                    7, 8, 9, 0.8f);
    EXPECT(count == 2 && boxes[1].r == 4,
           "debug append center box: full destination no-op");
}

static void test_debug_append_rect_fields_clamps_and_crosshair(void) {
    anomaly_box_t boxes[3];
    memset(boxes, 0, sizeof(boxes));
    int count = 0;

    anomaly_debug_append_rect(
            boxes, &count, 3,
            -0.25f, 0.20f, 1.25f, 0.80f,
            0xA1, 0xB2, 0xC3,
            -0.25f,
            false);

    EXPECT(count == 1,
           "debug append rect: valid clamped rect increments count");
    EXPECT_NEAR(boxes[0].left_norm, 0.0f, 0.0001f,
                "debug append rect: left edge clamps low");
    EXPECT_NEAR(boxes[0].right_norm, 1.0f, 0.0001f,
                "debug append rect: right edge clamps high");
    EXPECT_NEAR(boxes[0].top_norm, 0.20f, 0.0001f,
                "debug append rect: top edge is copied");
    EXPECT_NEAR(boxes[0].bottom_norm, 0.80f, 0.0001f,
                "debug append rect: bottom edge is copied");
    EXPECT(boxes[0].r == 0xA1 && boxes[0].g == 0xB2 && boxes[0].b == 0xC3,
           "debug append rect: RGB fields are copied");
    EXPECT(boxes[0].draw_crosshair == 0u,
           "debug append rect: false draw_crosshair is preserved");
    EXPECT_NEAR(boxes[0].weight, 0.0f, 0.0001f,
                "debug append rect: low weight clamps to zero");

    anomaly_debug_append_rect(
            boxes, &count, 3,
            0.10f, 0.30f, 0.40f, 0.60f,
            0x01, 0x02, 0x03,
            4.0f,
            true);

    EXPECT(count == 2,
           "debug append rect: second valid rect increments count");
    EXPECT(boxes[1].draw_crosshair == 1u,
           "debug append rect: true draw_crosshair is preserved");
    EXPECT_NEAR(boxes[1].weight, 1.0f, 0.0001f,
                "debug append rect: high weight clamps to one");
}

static void test_debug_append_rect_noops_invalid_and_capped(void) {
    anomaly_box_t boxes[2];
    memset(boxes, 0, sizeof(boxes));
    boxes[0].r = 0x77;
    int count = 1;

    anomaly_debug_append_rect(NULL, &count, 2, 0.1f, 0.1f, 0.2f, 0.2f,
                              1, 2, 3, 0.5f, true);
    EXPECT(count == 1 && boxes[0].r == 0x77,
           "debug append rect: NULL boxes no-op");

    anomaly_debug_append_rect(boxes, NULL, 2, 0.1f, 0.1f, 0.2f, 0.2f,
                              1, 2, 3, 0.5f, true);
    EXPECT(count == 1 && boxes[0].r == 0x77,
           "debug append rect: NULL count no-op");

    anomaly_debug_append_rect(boxes, &count, 0, 0.1f, 0.1f, 0.2f, 0.2f,
                              1, 2, 3, 0.5f, true);
    EXPECT(count == 1,
           "debug append rect: nonpositive capacity no-op");

    anomaly_debug_append_rect(boxes, &count, 2, 1.10f, 0.1f, 1.20f, 0.2f,
                              1, 2, 3, 0.5f, true);
    EXPECT(count == 1,
           "debug append rect: invalid rect after clamp no-op");

    anomaly_debug_append_rect(boxes, &count, 2, 0.1f, 0.1f, 0.2f, 0.2f,
                              4, 5, 6, 0.5f, true);
    EXPECT(count == 2,
           "debug append rect: valid rect reaches cap");

    anomaly_debug_append_rect(boxes, &count, 2, 0.2f, 0.2f, 0.3f, 0.3f,
                              7, 8, 9, 0.5f, false);
    EXPECT(count == 2 && boxes[1].r == 4,
           "debug append rect: full destination no-op");
}

static void test_debug_draw_rgba_hline_clamps_reverses_and_writes_alpha(void) {
    uint8_t rgba[5 * 3 * 4];
    memset(rgba, 0x11, sizeof(rgba));

    anomaly_debug_draw_rgba_hline(rgba, 5 * 4, 5, 3, 7, -2, 1, 0x21, 0x42, 0x63);

    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 5; x++) {
            bool drawn = y == 1;
            EXPECT(rgba_pixel_is(rgba, 5 * 4, x, y,
                                 drawn ? 0x21 : 0x11,
                                 drawn ? 0x42 : 0x11,
                                 drawn ? 0x63 : 0x11,
                                 drawn ? 0xFF : 0x11),
                   "debug draw hline: reversed/clamped line only touches requested row");
        }
    }

    uint8_t saved[sizeof(rgba)];
    memcpy(saved, rgba, sizeof(rgba));
    anomaly_debug_draw_rgba_hline(rgba, 5 * 4, 5, 3, 0, 4, 3, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_hline(NULL, 5 * 4, 5, 3, 0, 4, 1, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_hline(rgba, 5 * 4, 0, 3, 0, 4, 1, 0xAA, 0xBB, 0xCC);
    EXPECT(memcmp(saved, rgba, sizeof(rgba)) == 0,
           "debug draw hline: out-of-range/null/nonpositive dimensions no-op");
}

static void test_debug_draw_rgba_vline_clamps_reverses_and_writes_alpha(void) {
    uint8_t rgba[4 * 5 * 4];
    memset(rgba, 0x22, sizeof(rgba));

    anomaly_debug_draw_rgba_vline(rgba, 4 * 4, 4, 5, 8, -3, 2, 0x12, 0x34, 0x56);

    for (int y = 0; y < 5; y++) {
        for (int x = 0; x < 4; x++) {
            bool drawn = x == 2;
            EXPECT(rgba_pixel_is(rgba, 4 * 4, x, y,
                                 drawn ? 0x12 : 0x22,
                                 drawn ? 0x34 : 0x22,
                                 drawn ? 0x56 : 0x22,
                                 drawn ? 0xFF : 0x22),
                   "debug draw vline: reversed/clamped line only touches requested column");
        }
    }

    uint8_t saved[sizeof(rgba)];
    memcpy(saved, rgba, sizeof(rgba));
    anomaly_debug_draw_rgba_vline(rgba, 4 * 4, 4, 5, 0, 4, -1, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_vline(NULL, 4 * 4, 4, 5, 0, 4, 2, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_vline(rgba, 4 * 4, 4, 0, 0, 4, 2, 0xAA, 0xBB, 0xCC);
    EXPECT(memcmp(saved, rgba, sizeof(rgba)) == 0,
           "debug draw vline: out-of-range/null/nonpositive dimensions no-op");
}

static void test_debug_draw_rgba_circle_invalid_noops_and_symmetric_points(void) {
    enum { W = 9, H = 9, STRIDE = W * 4 + 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x33, sizeof(rgba));

    anomaly_debug_draw_rgba_circle(rgba, STRIDE, W, H, 4, 4, 0, 1, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_circle(rgba, STRIDE, W, H, 4, 4, 2, 0, 0xAA, 0xBB, 0xCC);
    anomaly_debug_draw_rgba_circle(NULL, STRIDE, W, H, 4, 4, 2, 1, 0xAA, 0xBB, 0xCC);
    for (int i = 0; i < (int)sizeof(rgba); i++) {
        EXPECT(rgba[i] == 0x33,
               "debug draw circle: null/nonpositive radius/stroke no-op");
    }

    anomaly_debug_draw_rgba_circle(rgba, STRIDE, W, H, 4, 4, 2, 1, 0xA1, 0xB2, 0xC3);

    int expected_points[12][2] = {
        {6, 4}, {4, 6}, {2, 4}, {4, 2},
        {6, 5}, {5, 6}, {3, 6}, {2, 5},
        {2, 3}, {3, 2}, {5, 2}, {6, 3},
    };
    int drawn_count = 0;
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            bool expected = false;
            for (int i = 0; i < 12; i++) {
                if (expected_points[i][0] == x && expected_points[i][1] == y) {
                    expected = true;
                    break;
                }
            }
            if (expected) drawn_count++;
            EXPECT(rgba_pixel_is(rgba, STRIDE, x, y,
                                 expected ? 0xA1 : 0x33,
                                 expected ? 0xB2 : 0x33,
                                 expected ? 0xC3 : 0x33,
                                 expected ? 0xFF : 0x33),
                   "debug draw circle: radius-two midpoint plot touches expected symmetric points");
        }
    }
    EXPECT(drawn_count == 12,
           "debug draw circle: deterministic radius-two plot has expected point count");
    for (int y = 0; y < H; y++) {
        EXPECT(rgba[y * STRIDE + W * 4] == 0x33 &&
               rgba[y * STRIDE + W * 4 + 1] == 0x33 &&
               rgba[y * STRIDE + W * 4 + 2] == 0x33 &&
               rgba[y * STRIDE + W * 4 + 3] == 0x33,
               "debug draw circle: padded stride bytes are untouched");
    }
}

static void test_debug_draw_boxes_rgba_noops_invalid_inputs(void) {
    enum { W = 8, H = 8, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x44, sizeof(rgba));
    anomaly_box_t box = {
        .left_norm = 0.10f, .top_norm = 0.10f,
        .right_norm = 0.90f, .bottom_norm = 0.90f,
        .r = 0xAA, .g = 0xBB, .b = 0xCC,
        .draw_crosshair = 1u,
        .weight = 1.0f,
    };

    anomaly_debug_draw_boxes_rgba(NULL, STRIDE, W, H, &box, 1);
    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, 0, H, &box, 1);
    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, W, 0, &box, 1);
    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, W, H, NULL, 1);
    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, W, H, &box, 0);

    for (int i = 0; i < (int)sizeof(rgba); i++) {
        EXPECT(rgba[i] == 0x44,
               "debug draw boxes: NULL/invalid dimensions/count are no-op");
    }
}

static void test_debug_draw_boxes_rgba_crosshair_color_underlay_and_gap(void) {
    enum { W = 20, H = 20, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x55, sizeof(rgba));
    anomaly_box_t box = {
        .left_norm = 0.20f, .top_norm = 0.20f,
        .right_norm = 0.80f, .bottom_norm = 0.80f,
        .r = 0x10, .g = 0x20, .b = 0x30,
        .draw_crosshair = 1u,
        .weight = 1.0f,
    };

    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, W, H, &box, 1);

    EXPECT(rgba_pixel_is(rgba, STRIDE, 4, 8, 0x10, 0x20, 0x30, 0xFF),
           "debug draw boxes: crosshair horizontal segment draws color");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 8, 4, 0x10, 0x20, 0x30, 0xFF),
           "debug draw boxes: crosshair vertical segment draws color");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 4, 7, 0x00, 0x00, 0x00, 0xFF),
           "debug draw boxes: crosshair horizontal underlay remains visible");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 7, 4, 0x00, 0x00, 0x00, 0xFF),
           "debug draw boxes: crosshair vertical underlay remains visible");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 9, 9, 0x55, 0x55, 0x55, 0x55),
           "debug draw boxes: crosshair center gap remains untouched");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 0, 0, 0x55, 0x55, 0x55, 0x55),
           "debug draw boxes: unrelated background pixel remains untouched");
}

static void test_debug_draw_boxes_rgba_rect_color_underlay_and_skip_invalid(void) {
    enum { W = 20, H = 20, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x66, sizeof(rgba));
    anomaly_box_t boxes[2] = {
        {
            .left_norm = 0.20f, .top_norm = 0.25f,
            .right_norm = 0.80f, .bottom_norm = 0.75f,
            .r = 0xA1, .g = 0xB2, .b = 0xC3,
            .draw_crosshair = 0u,
            .weight = 1.0f,
        },
        {
            .left_norm = 0.90f, .top_norm = 0.90f,
            .right_norm = 0.90f, .bottom_norm = 0.95f,
            .r = 0x01, .g = 0x02, .b = 0x03,
            .draw_crosshair = 0u,
            .weight = 1.0f,
        },
    };

    anomaly_debug_draw_boxes_rgba(rgba, STRIDE, W, H, boxes, 2);

    EXPECT(rgba_pixel_is(rgba, STRIDE, 4, 5, 0xA1, 0xB2, 0xC3, 0xFF),
           "debug draw boxes: rectangle top-left border draws color");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 15, 14, 0xA1, 0xB2, 0xC3, 0xFF),
           "debug draw boxes: rectangle bottom-right border draws color");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 10, 7, 0x00, 0x00, 0x00, 0xFF),
           "debug draw boxes: rectangle underlay remains visible inside stroke band");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 10, 10, 0x66, 0x66, 0x66, 0x66),
           "debug draw boxes: rectangle interior remains untouched");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 17, 18, 0x66, 0x66, 0x66, 0x66),
           "debug draw boxes: invalid zero-width box is skipped");
}

static void test_debug_draw_hot_overlay_rgba_noops_invalid_inputs(void) {
    enum { W = 32, H = 32, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x50, sizeof(rgba));
    uint8_t saved[sizeof(rgba)];
    memcpy(saved, rgba, sizeof(rgba));

    anomaly_debug_draw_hot_overlay_rgba(NULL, STRIDE, W, H, ANOMALY_THERMAL_WHITE_HOT);
    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, 0, H, ANOMALY_THERMAL_WHITE_HOT);
    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, 0, ANOMALY_THERMAL_WHITE_HOT);
    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, -1, H, ANOMALY_THERMAL_WHITE_HOT);
    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, -1, ANOMALY_THERMAL_WHITE_HOT);

    EXPECT(memcmp(saved, rgba, sizeof(rgba)) == 0,
           "debug hot overlay: NULL/nonpositive dimensions are no-op");
}

static void test_debug_draw_hot_overlay_rgba_white_hot_marks_bright_region(void) {
    enum { W = 80, H = 80, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x30, sizeof(rgba));
    for (int y = 38; y <= 42; y++) {
        for (int x = 38; x <= 42; x++) {
            set_pixel(rgba, STRIDE, x, y, 0xF0, 0xF0, 0xF0);
        }
    }

    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, H, ANOMALY_THERMAL_WHITE_HOT);

    EXPECT(rgba_pixel_is(rgba, STRIDE, 48, 40, 0xFF, 0x30, 0x30, 0xFF),
           "debug hot overlay: white-hot bright region draws right circle point");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 40, 48, 0xFF, 0x30, 0x30, 0xFF),
           "debug hot overlay: white-hot bright region draws lower circle point");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 38, 38, 0xF0, 0xF0, 0xF0, 0xFF),
           "debug hot overlay: white-hot hot region interior remains unchanged");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 0, 0, 0x30, 0x30, 0x30, 0x30),
           "debug hot overlay: white-hot unrelated background remains unchanged");
}

static void test_debug_draw_hot_overlay_rgba_black_hot_marks_dark_region(void) {
    enum { W = 80, H = 80, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0xD0, sizeof(rgba));
    for (int y = 38; y <= 42; y++) {
        for (int x = 38; x <= 42; x++) {
            set_pixel(rgba, STRIDE, x, y, 0x10, 0x10, 0x10);
        }
    }

    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, H, ANOMALY_THERMAL_BLACK_HOT);

    EXPECT(rgba_pixel_is(rgba, STRIDE, 48, 40, 0xFF, 0x30, 0x30, 0xFF),
           "debug hot overlay: black-hot dark region draws right circle point");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 40, 32, 0xFF, 0x30, 0x30, 0xFF),
           "debug hot overlay: black-hot dark region draws upper circle point");
    EXPECT(rgba_pixel_is(rgba, STRIDE, 0, 0, 0xD0, 0xD0, 0xD0, 0xD0),
           "debug hot overlay: black-hot unrelated background remains unchanged");
}

static void test_debug_draw_hot_overlay_rgba_uniform_frame_noop(void) {
    enum { W = 80, H = 80, STRIDE = W * 4 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0x70, sizeof(rgba));
    uint8_t saved[sizeof(rgba)];
    memcpy(saved, rgba, sizeof(rgba));

    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, H, ANOMALY_THERMAL_WHITE_HOT);
    anomaly_debug_draw_hot_overlay_rgba(rgba, STRIDE, W, H, ANOMALY_THERMAL_BLACK_HOT);

    EXPECT(memcmp(saved, rgba, sizeof(rgba)) == 0,
           "debug hot overlay: uniform frame has no hot circle");
}

static void test_debug_scan_reason_flag_names_and_formatting(void) {
    char buffer[128];

    EXPECT(strcmp(anomaly_debug_scan_reason_flag_name(ANOMALY_SCAN_REASON_NO_SAMPLES),
                  "no-samples") == 0,
           "debug scan reason: names known scan flags");
    EXPECT(strcmp(anomaly_debug_scan_reason_flag_name(0x80000000u), "unknown") == 0,
           "debug scan reason: unknown flag name is unknown");

    anomaly_debug_format_scan_reason_flags(0u, buffer, sizeof(buffer));
    EXPECT(strcmp(buffer, "none") == 0,
           "debug scan reason: zero flags format as none");

    anomaly_debug_format_scan_reason_flags(
            ANOMALY_SCAN_REASON_REG_INVALID | ANOMALY_SCAN_REASON_MASK_EMPTY,
            buffer,
            sizeof(buffer));
    EXPECT(strcmp(buffer, "reg-invalid|mask-empty") == 0,
           "debug scan reason: multiple flags format in known order");

    anomaly_debug_format_scan_reason_flags(0x80000000u, buffer, sizeof(buffer));
    EXPECT(strcmp(buffer, "") == 0,
           "debug scan reason: unknown-only flags preserve empty legacy output");

    buffer[0] = 'x';
    anomaly_debug_format_scan_reason_flags(ANOMALY_SCAN_REASON_NO_SAMPLES, buffer, 1);
    EXPECT(buffer[0] == '\0',
           "debug scan reason: one-byte buffer is terminated");
}

static void test_debug_registration_invalid_reason_names(void) {
    EXPECT(strcmp(anomaly_debug_registration_invalid_reason_name(
                          ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS),
                  "gmv-too-few-anchors") == 0,
           "debug registration reason: names GMV invalid reason");
    EXPECT(strcmp(anomaly_debug_registration_invalid_reason_name(
                          ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET),
                  "affine-negative-det") == 0,
           "debug registration reason: names affine invalid reason");
    EXPECT(strcmp(anomaly_debug_registration_invalid_reason_name(
                          (anomaly_registration_invalid_reason_t)999),
                  "unknown") == 0,
           "debug registration reason: unknown reason is unknown");
}

static void test_debug_populate_registration_model_noops_null_inputs(void) {
    anomaly_result_t result;
    memset(&result, 0x5A, sizeof(result));
    anomaly_result_t saved = result;
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 3, 5);

    anomaly_debug_populate_registration_model(NULL, &result);
    EXPECT(memcmp(&result, &saved, sizeof(result)) == 0,
           "debug registration populate: NULL model is no-op");

    anomaly_debug_populate_registration_model(&model, NULL);
    EXPECT(memcmp(&result, &saved, sizeof(result)) == 0,
           "debug registration populate: NULL result is no-op");
}

static void test_debug_populate_registration_model_copies_fields_and_anchors(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 7, 9);
    anomaly_result_t result;
    memset(&result, 0, sizeof(result));

    model.debug_valid = true;
    model.scene_discontinuity = true;
    model.anchor_count = 2;
    model.tracked_match_count = 11;
    model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH;
    model.similarity.a = 3.0f;
    model.similarity.b = 4.0f;
    model.similarity.tx = 0.125f;
    model.similarity.ty = -0.250f;
    model.similarity.mean_residual = 0.375f;
    model.fit_det = 1.25f;
    model.fit_min_scale = 0.90f;
    model.fit_max_scale = 1.10f;
    model.fit_anchor_residual_std = 0.031f;
    model.fit_anchor_residual_max = 0.094f;
    model.fit_motion_dx_std = 0.062f;
    model.fit_motion_dy_std = 0.047f;
    model.fit_quadrant_residual_spread = 0.125f;
    model.anchors[0] = (anomaly_debug_gmv_anchor_t){
        .valid = true,
        .zone_gx = 1,
        .zone_gy = 2,
        .pixel_x = 30,
        .pixel_y = 40,
        .x_norm = 0.30f,
        .y_norm = 0.40f,
        .texture_score = 77,
        .match_dx = -2,
        .match_dy = 3,
        .best_sad = 101,
        .second_best_sad = 202,
    };
    model.anchors[1] = (anomaly_debug_gmv_anchor_t){
        .valid = true,
        .zone_gx = 3,
        .zone_gy = 4,
        .pixel_x = 50,
        .pixel_y = 60,
        .x_norm = 0.50f,
        .y_norm = 0.60f,
        .texture_score = 88,
        .match_dx = 4,
        .match_dy = -5,
        .best_sad = 303,
        .second_best_sad = 404,
    };

    anomaly_debug_populate_registration_model(&model, &result);

    EXPECT(result.gmv_debug.valid && result.gmv_debug.scene_discontinuity,
           "debug registration populate: validity and discontinuity are copied");
    EXPECT(result.gmv_debug.sample_step == 7 &&
           result.gmv_debug.motion_step == 9 &&
           result.gmv_debug.anchor_count == 2 &&
           result.gmv_debug.invalid_reason == ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH &&
           result.gmv_debug.tracked_match_count == 11,
           "debug registration populate: discrete metadata fields are copied");
    EXPECT_NEAR(result.gmv_debug.fit_a, 3.0f, 0.0001f,
                "debug registration populate: fit a is copied");
    EXPECT_NEAR(result.gmv_debug.fit_b, 4.0f, 0.0001f,
                "debug registration populate: fit b is copied");
    EXPECT_NEAR(result.gmv_debug.fit_tx, 0.125f, 0.0001f,
                "debug registration populate: fit tx is copied");
    EXPECT_NEAR(result.gmv_debug.fit_ty, -0.250f, 0.0001f,
                "debug registration populate: fit ty is copied");
    EXPECT_NEAR(result.gmv_debug.fit_scale, 5.0f, 0.0001f,
                "debug registration populate: fit scale uses registration model scale");
    EXPECT_NEAR(result.gmv_debug.fit_theta_deg, 53.1301f, 0.0005f,
                "debug registration populate: fit theta uses atan2 degrees");
    EXPECT_NEAR(result.gmv_debug.fit_mean_residual, 0.375f, 0.0001f,
                "debug registration populate: mean residual is copied");
    EXPECT_NEAR(result.gmv_debug.fit_det, 1.25f, 0.0001f,
                "debug registration populate: fit determinant is copied");
    EXPECT_NEAR(result.gmv_debug.fit_min_scale, 0.90f, 0.0001f,
                "debug registration populate: fit min scale is copied");
    EXPECT_NEAR(result.gmv_debug.fit_max_scale, 1.10f, 0.0001f,
                "debug registration populate: fit max scale is copied");
    EXPECT_NEAR(result.gmv_debug.fit_anchor_residual_std, 0.031f, 0.0001f,
                "debug registration populate: anchor residual std is copied");
    EXPECT_NEAR(result.gmv_debug.fit_anchor_residual_max, 0.094f, 0.0001f,
                "debug registration populate: anchor residual max is copied");
    EXPECT_NEAR(result.gmv_debug.fit_motion_dx_std, 0.062f, 0.0001f,
                "debug registration populate: motion dx std is copied");
    EXPECT_NEAR(result.gmv_debug.fit_motion_dy_std, 0.047f, 0.0001f,
                "debug registration populate: motion dy std is copied");
    EXPECT_NEAR(result.gmv_debug.fit_quadrant_residual_spread, 0.125f, 0.0001f,
                "debug registration populate: quadrant residual spread is copied");
    EXPECT(memcmp(&result.gmv_debug.anchors[0], &model.anchors[0], sizeof(model.anchors[0])) == 0 &&
           memcmp(&result.gmv_debug.anchors[1], &model.anchors[1], sizeof(model.anchors[1])) == 0,
           "debug registration populate: debug anchors are copied");
}

static void test_result_build_boxes_invalid_inputs_return_zero(void) {
    anomaly_state_t state;
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    anomaly_box_t boxes[2];
    memset(&state, 0, sizeof(state));
    memset(boxes, 0x7A, sizeof(boxes));

    EXPECT(anomaly_result_build_boxes(NULL, &cfg, ANOMALY_ALGO_MOTION, boxes, 2) == 0,
           "result builder: NULL state returns zero");
    EXPECT(anomaly_result_build_boxes(&state, NULL, ANOMALY_ALGO_MOTION, boxes, 2) == 0,
           "result builder: NULL config returns zero");
    EXPECT(anomaly_result_build_boxes(&state, &cfg, ANOMALY_ALGO_MOTION, NULL, 2) == 0,
           "result builder: NULL boxes returns zero");
    EXPECT(anomaly_result_build_boxes(&state, &cfg, ANOMALY_ALGO_MOTION, boxes, 0) == 0,
           "result builder: nonpositive capacity returns zero");
}

static void test_result_build_boxes_target_tracks_take_priority(void) {
    anomaly_state_t state;
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL);
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    memset(&state, 0, sizeof(state));
    memset(boxes, 0, sizeof(boxes));
    cfg.min_area_fraction = 0.01f;
    cfg.min_hits = 2;

    state.target_tracks[0].active = true;
    state.target_tracks[0].publish_confirmed = true;
    state.target_tracks[0].hit_count = 2;
    state.target_tracks[0].confidence = 0.60f;
    state.target_tracks[0].center_x_norm = 0.40f;
    state.target_tracks[0].center_y_norm = 0.60f;
    state.target_tracks[0].half_w_norm = 0.02f;
    state.target_tracks[0].half_h_norm = 0.08f;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_THERMAL;
    state.acc_active[0] = true;
    state.acc_hits[0] = 5;
    state.acc_cx[0] = 0.75f;
    state.acc_cy[0] = 0.25f;

    int count = anomaly_result_build_boxes(
            &state, &cfg, ANOMALY_ALGO_MOTION, boxes, ANOMALY_MAX_BOXES_PER_FRAME);

    EXPECT(count == 1, "result builder: published target track suppresses accumulator boxes");
    EXPECT(boxes[0].algorithm == ANOMALY_ALGO_THERMAL,
           "result builder: target box preserves track algorithm");
    EXPECT(boxes[0].r == 0xF2 && boxes[0].g == 0x30 && boxes[0].b == 0x30,
           "result builder: target thermal algorithm uses thermal RGB");
    EXPECT(boxes[0].draw_crosshair == 1u,
           "result builder: target boxes are center crosshair boxes");
    EXPECT_NEAR(boxes[0].left_norm, 0.3575f, 0.0001f,
                "result builder: target width uses min-area floor when larger");
    EXPECT_NEAR(boxes[0].right_norm, 0.4425f, 0.0001f,
                "result builder: target right uses min-area floor");
    EXPECT_NEAR(boxes[0].top_norm, 0.5200f, 0.0001f,
                "result builder: target height preserves larger track size");
    EXPECT_NEAR(boxes[0].bottom_norm, 0.6800f, 0.0001f,
                "result builder: target bottom preserves larger track size");
    EXPECT_NEAR(boxes[0].weight, 0.95f, 0.0001f,
                "result builder: target weight formula is preserved");
}

static void test_result_build_boxes_accumulator_fallback_and_persist_filter(void) {
    anomaly_state_t state;
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    memset(&state, 0, sizeof(state));
    memset(boxes, 0, sizeof(boxes));
    cfg.min_area_fraction = 0.01f;
    cfg.min_hits = 2;

    state.acc_active[0] = true;
    state.acc_hits[0] = 4;
    state.acc_cx[0] = 0.30f;
    state.acc_cy[0] = 0.40f;
    state.acc_active[2] = true;
    state.acc_hits[2] = 2;
    state.acc_cx[2] = 0.70f;
    state.acc_cy[2] = 0.20f;

    int count = anomaly_result_build_boxes(
            &state, &cfg, ANOMALY_ALGO_MOTION_TOLERANCE, boxes, ANOMALY_MAX_BOXES_PER_FRAME);

    EXPECT(count == 2, "result builder: accumulators publish when no target tracks exist");
    EXPECT(boxes[0].algorithm == ANOMALY_ALGO_COLOR,
           "result builder: color accumulator keeps color algorithm");
    EXPECT(boxes[0].r == 0x2D && boxes[0].g == 0x6C && boxes[0].b == 0xFF,
           "result builder: color accumulator uses color RGB");
    EXPECT_NEAR(boxes[0].weight, 0.61f, 0.0001f,
                "result builder: accumulator weight formula uses min-hit normalization");
    EXPECT(boxes[1].algorithm == ANOMALY_ALGO_MOTION_TOLERANCE,
           "result builder: motion accumulator uses caller-owned motion algorithm");
    EXPECT(boxes[1].r == 0x23 && boxes[1].g == 0xC5 && boxes[1].b == 0x52,
           "result builder: motion accumulator uses motion RGB");
    EXPECT_NEAR(boxes[1].left_norm, 0.635f, 0.0001f,
                "result builder: motion accumulator uses 1.3x scale");

    cfg.algorithm_mask = ANOMALY_ALGO_COLOR | ANOMALY_ALGO_PERSIST;
    state.acc_active[3] = true;
    state.acc_hits[3] = 3;
    state.acc_cx[3] = 0.55f;
    state.acc_cy[3] = 0.65f;
    state.saliency_display_algorithm = ANOMALY_ALGO_THERMAL;
    memset(boxes, 0, sizeof(boxes));

    count = anomaly_result_build_boxes(
            &state, &cfg, ANOMALY_ALGO_MOTION_TOLERANCE, boxes, ANOMALY_MAX_BOXES_PER_FRAME);

    EXPECT(count == 1, "result builder: persist mask filters non-saliency accumulators");
    EXPECT(boxes[0].algorithm == ANOMALY_ALGO_PERSIST,
           "result builder: saliency-primary accumulator assigns persist algorithm");
    EXPECT(boxes[0].r == 0xF2 && boxes[0].g == 0x30 && boxes[0].b == 0x30,
           "result builder: saliency display algorithm controls RGB");
    EXPECT_NEAR(boxes[0].weight, 0.48f, 0.0001f,
                "result builder: saliency accumulator weight formula is preserved");
}

static void test_result_build_boxes_saliency_aux_current_gate_noop(void) {
    anomaly_state_t state;
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_PERSIST);
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    memset(&state, 0, sizeof(state));
    memset(boxes, 0, sizeof(boxes));
    cfg.min_area_fraction = 0.01f;
    cfg.min_hits = 2;

    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 5;
    state.saliency_aux_cx[0] = 0.60f;
    state.saliency_aux_cy[0] = 0.70f;
    state.saliency_aux_display_algorithm[0] = ANOMALY_ALGO_COLOR;

    int count = anomaly_result_build_boxes(
            &state, &cfg, ANOMALY_ALGO_MOTION, boxes, ANOMALY_MAX_BOXES_PER_FRAME);

    EXPECT(count == 0,
           "result builder: current persist saliency-primary gate keeps aux fallback unreachable");
}

static void test_saliency_update_aux_track_noops_invalid_inputs(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 4;
    state.saliency_aux_hold[0] = 5;
    state.saliency_aux_cx[0] = 0.25f;
    state.saliency_aux_cy[0] = 0.35f;

    anomaly_saliency_update_aux_track(NULL, 0, 0.45f, 0.55f, 0.10f, 0.50f);
    anomaly_saliency_update_aux_track(&state, -1, 0.45f, 0.55f, 0.10f, 0.50f);
    anomaly_saliency_update_aux_track(
            &state, ANOMALY_SALIENCY_EXTRA_TRACKS, 0.45f, 0.55f, 0.10f, 0.50f);

    EXPECT(state.saliency_aux_active[0], "saliency aux track: invalid index keeps active flag");
    EXPECT(state.saliency_aux_hits[0] == 4, "saliency aux track: invalid index keeps hits");
    EXPECT(state.saliency_aux_hold[0] == 5, "saliency aux track: invalid index keeps hold");
    EXPECT_NEAR(state.saliency_aux_cx[0], 0.25f, 0.0001f,
                "saliency aux track: invalid index keeps cx");
    EXPECT_NEAR(state.saliency_aux_cy[0], 0.35f, 0.0001f,
                "saliency aux track: invalid index keeps cy");
}

static void test_saliency_update_aux_track_initializes_inactive_track(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    anomaly_saliency_update_aux_track(&state, 0, 0.45f, 0.55f, 0.10f, 0.50f);

    EXPECT(state.saliency_aux_active[0], "saliency aux track: valid raw initializes active");
    EXPECT(state.saliency_aux_hits[0] == 1, "saliency aux track: valid raw initializes hits");
    EXPECT(state.saliency_aux_hold[0] == ANOMALY_ACC_HOLD_FRAMES,
           "saliency aux track: valid raw initializes base hold");
    EXPECT_NEAR(state.saliency_aux_cx[0], 0.45f, 0.0001f,
                "saliency aux track: valid raw initializes cx");
    EXPECT_NEAR(state.saliency_aux_cy[0], 0.55f, 0.0001f,
                "saliency aux track: valid raw initializes cy");
}

static void test_saliency_update_aux_track_ingate_ema_and_hold_reset(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 2;
    state.saliency_aux_hold[0] = 1;
    state.saliency_aux_cx[0] = 0.20f;
    state.saliency_aux_cy[0] = 0.30f;

    anomaly_saliency_update_aux_track(&state, 0, 0.24f, 0.38f, 0.10f, 0.25f);

    EXPECT(state.saliency_aux_active[0], "saliency aux track: in-gate update remains active");
    EXPECT(state.saliency_aux_hits[0] == 3, "saliency aux track: in-gate update increments hits");
    EXPECT(state.saliency_aux_hold[0] == ANOMALY_ACC_HOLD_FRAMES,
           "saliency aux track: in-gate update resets hold to pre-hit base");
    EXPECT_NEAR(state.saliency_aux_cx[0], 0.21f, 0.0001f,
                "saliency aux track: in-gate update applies cx EMA");
    EXPECT_NEAR(state.saliency_aux_cy[0], 0.32f, 0.0001f,
                "saliency aux track: in-gate update applies cy EMA");
}

static void test_saliency_update_aux_track_out_of_gate_resets_track(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 5;
    state.saliency_aux_hold[0] = 2;
    state.saliency_aux_cx[0] = 0.20f;
    state.saliency_aux_cy[0] = 0.30f;

    anomaly_saliency_update_aux_track(&state, 0, 0.60f, 0.70f, 0.05f, 0.25f);

    EXPECT(state.saliency_aux_active[0], "saliency aux track: out-of-gate reset remains active");
    EXPECT(state.saliency_aux_hits[0] == 1, "saliency aux track: out-of-gate reset drops hits");
    EXPECT(state.saliency_aux_hold[0] == ANOMALY_ACC_HOLD_FRAMES + 6,
           "saliency aux track: out-of-gate reset preserves pre-reset hold bonus");
    EXPECT_NEAR(state.saliency_aux_cx[0], 0.60f, 0.0001f,
                "saliency aux track: out-of-gate reset updates cx");
    EXPECT_NEAR(state.saliency_aux_cy[0], 0.70f, 0.0001f,
                "saliency aux track: out-of-gate reset updates cy");
}

static void test_saliency_update_aux_track_invalid_raw_ages_and_expires(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    anomaly_saliency_update_aux_track(&state, 0, -1.0f, 0.70f, 0.05f, 0.25f);
    EXPECT(!state.saliency_aux_active[0],
           "saliency aux track: invalid raw no-ops while inactive");

    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 4;
    state.saliency_aux_hold[0] = 2;
    state.saliency_aux_cx[0] = 0.20f;
    state.saliency_aux_cy[0] = 0.30f;

    anomaly_saliency_update_aux_track(&state, 0, -1.0f, 0.70f, 0.05f, 0.25f);

    EXPECT(state.saliency_aux_active[0],
           "saliency aux track: invalid raw keeps active while hold remains");
    EXPECT(state.saliency_aux_hits[0] == 4,
           "saliency aux track: invalid raw keeps hits while hold remains");
    EXPECT(state.saliency_aux_hold[0] == 1,
           "saliency aux track: invalid raw decrements hold");

    anomaly_saliency_update_aux_track(&state, 0, -1.0f, 0.70f, 0.05f, 0.25f);

    EXPECT(!state.saliency_aux_active[0],
           "saliency aux track: invalid raw clears active when hold expires");
    EXPECT(state.saliency_aux_hits[0] == 0,
           "saliency aux track: invalid raw clears hits when hold expires");
    EXPECT(state.saliency_aux_hold[0] == 0,
           "saliency aux track: invalid raw clears hold when hold expires");
}

static void test_saliency_update_aux_track_hold_bonus_and_hit_cap(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = ANOMALY_ACC_MAX_HITS;
    state.saliency_aux_hold[0] = 1;
    state.saliency_aux_cx[0] = 0.20f;
    state.saliency_aux_cy[0] = 0.30f;

    anomaly_saliency_update_aux_track(&state, 0, 0.22f, 0.32f, 0.10f, 0.50f);

    EXPECT(state.saliency_aux_hits[0] == ANOMALY_ACC_MAX_HITS,
           "saliency aux track: in-gate update caps hits");
    EXPECT(state.saliency_aux_hold[0] == ANOMALY_ACC_HOLD_FRAMES + 6,
           "saliency aux track: existing strong track receives hold bonus");
    EXPECT_NEAR(state.saliency_aux_cx[0], 0.21f, 0.0001f,
                "saliency aux track: capped update still applies cx EMA");
    EXPECT_NEAR(state.saliency_aux_cy[0], 0.31f, 0.0001f,
                "saliency aux track: capped update still applies cy EMA");
}

static void test_saliency_find_local_support_invalid_inputs_initialize_outputs(void) {
    anomaly_state_t state;
    float map[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    float x = 2.0f;
    float y = 3.0f;
    float score = 4.0f;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_cx[0] = 0.5f;
    state.saliency_aux_cy[0] = 0.5f;

    bool ok = anomaly_saliency_find_local_support(
            NULL, 0, map, 2, 2, 0, 0, 1, 10, 10, &x, &y, &score);

    EXPECT(!ok, "saliency local support: null state returns false");
    EXPECT_NEAR(x, -1.0f, 0.0001f, "saliency local support: null state initializes x");
    EXPECT_NEAR(y, -1.0f, 0.0001f, "saliency local support: null state initializes y");
    EXPECT_NEAR(score, -1.0f, 0.0001f, "saliency local support: null state initializes score");

    ok = anomaly_saliency_find_local_support(
            &state, -1, map, 2, 2, 0, 0, 1, 10, 10, NULL, NULL, NULL);
    EXPECT(!ok, "saliency local support: negative track index returns false");

    x = 2.0f;
    y = 3.0f;
    score = 4.0f;
    ok = anomaly_saliency_find_local_support(
            &state, ANOMALY_SALIENCY_EXTRA_TRACKS, map, 2, 2, 0, 0, 1, 10, 10, &x, &y, &score);

    EXPECT(!ok, "saliency local support: invalid track index returns false");
    EXPECT_NEAR(x, -1.0f, 0.0001f, "saliency local support: invalid index initializes x");
    EXPECT_NEAR(y, -1.0f, 0.0001f, "saliency local support: invalid index initializes y");
    EXPECT_NEAR(score, -1.0f, 0.0001f, "saliency local support: invalid index initializes score");

    ok = anomaly_saliency_find_local_support(
            &state, 0, NULL, 2, 2, 0, 0, 1, 10, 10, NULL, NULL, NULL);
    EXPECT(!ok, "saliency local support: null map returns false with null outputs");

    ok = anomaly_saliency_find_local_support(
            &state, 0, map, 0, 2, 0, 0, 1, 10, 10, NULL, NULL, NULL);
    EXPECT(!ok, "saliency local support: nonpositive sg_w returns false");

    ok = anomaly_saliency_find_local_support(
            &state, 0, map, 2, 0, 0, 0, 1, 10, 10, NULL, NULL, NULL);
    EXPECT(!ok, "saliency local support: nonpositive sg_h returns false");

    ok = anomaly_saliency_find_local_support(
            &state, 0, map, 2, 2, 0, 0, 0, 10, 10, NULL, NULL, NULL);
    EXPECT(!ok, "saliency local support: nonpositive sample step returns false");
}

static void test_saliency_find_local_support_inactive_track_returns_false(void) {
    anomaly_state_t state;
    float map[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    float x = 2.0f;
    float y = 3.0f;
    float score = 4.0f;
    memset(&state, 0, sizeof(state));

    bool ok = anomaly_saliency_find_local_support(
            &state, 0, map, 2, 2, 0, 0, 1, 10, 10, &x, &y, &score);

    EXPECT(!ok, "saliency local support: inactive track returns false");
    EXPECT_NEAR(x, -1.0f, 0.0001f, "saliency local support: inactive track initializes x");
    EXPECT_NEAR(y, -1.0f, 0.0001f, "saliency local support: inactive track initializes y");
    EXPECT_NEAR(score, -1.0f, 0.0001f, "saliency local support: inactive track initializes score");
}

static void test_saliency_find_local_support_nonpositive_scores_return_false(void) {
    anomaly_state_t state;
    float map[9] = {
        0.0f, -0.2f, 0.0f,
        -0.1f, 0.0f, -0.3f,
        0.0f, -0.4f, 0.0f
    };
    float x = 2.0f;
    float y = 3.0f;
    float score = 4.0f;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_cx[0] = 0.5f;
    state.saliency_aux_cy[0] = 0.5f;

    bool ok = anomaly_saliency_find_local_support(
            &state, 0, map, 3, 3, 0, 0, 1, 10, 10, &x, &y, &score);

    EXPECT(!ok, "saliency local support: all nonpositive local scores return false");
    EXPECT_NEAR(x, -1.0f, 0.0001f, "saliency local support: nonpositive scores keep x unset");
    EXPECT_NEAR(y, -1.0f, 0.0001f, "saliency local support: nonpositive scores keep y unset");
    EXPECT_NEAR(score, -1.0f, 0.0001f, "saliency local support: nonpositive scores keep score unset");
}

static void test_saliency_find_local_support_best_score_and_normalized_output(void) {
    anomaly_state_t state;
    float map[25] = {0.0f};
    float x = -1.0f;
    float y = -1.0f;
    float score = -1.0f;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_cx[0] = 0.20f;
    state.saliency_aux_cy[0] = 0.375f;
    map[2 * 5 + 2] = 0.5f;
    map[3 * 5 + 4] = 0.9f;
    map[4 * 5 + 4] = 0.7f;

    bool ok = anomaly_saliency_find_local_support(
            &state, 0, map, 5, 5, 10, 20, 5, 101, 81, &x, &y, &score);

    EXPECT(ok, "saliency local support: positive local support returns true");
    EXPECT_NEAR(x, 0.30f, 0.0001f, "saliency local support: best sx converts to normalized x");
    EXPECT_NEAR(y, 0.4375f, 0.0001f, "saliency local support: best sy converts to normalized y");
    EXPECT_NEAR(score, 0.9f, 0.0001f, "saliency local support: returns best local score");
}

static void test_saliency_find_local_support_edge_clamps_search_window(void) {
    anomaly_state_t state;
    float map[4] = {
        0.2f, 0.7f,
        0.6f, 0.5f
    };
    float x = -1.0f;
    float y = -1.0f;
    float score = -1.0f;
    memset(&state, 0, sizeof(state));
    state.saliency_aux_active[0] = true;
    state.saliency_aux_cx[0] = -0.25f;
    state.saliency_aux_cy[0] = -0.25f;

    bool ok = anomaly_saliency_find_local_support(
            &state, 0, map, 2, 2, 10, 20, 5, 101, 81, &x, &y, &score);

    EXPECT(ok, "saliency local support: edge-clamped search returns true");
    EXPECT_NEAR(x, 0.15f, 0.0001f, "saliency local support: clamped edge search reports best x");
    EXPECT_NEAR(y, 0.25f, 0.0001f, "saliency local support: clamped edge search reports best y");
    EXPECT_NEAR(score, 0.7f, 0.0001f, "saliency local support: edge search chooses best score");
}

static void test_saliency_choose_best_dark_patch_invalid_defaults(void) {
    float score = 9.0f;
    int x = 9;
    int y = 9;

    anomaly_saliency_choose_best_dark_patch(NULL, 3, 2, 10, 20, 4, &score, &x, &y);

    EXPECT_NEAR(score, -1.0f, 0.0001f,
                "saliency best dark patch: null map initializes score");
    EXPECT(x == 0, "saliency best dark patch: null map initializes x");
    EXPECT(y == 0, "saliency best dark patch: null map initializes y");

    score = 9.0f;
    x = 9;
    y = 9;
    anomaly_saliency_choose_best_dark_patch(NULL, 0, 2, 10, 20, 4, &score, &x, &y);

    EXPECT_NEAR(score, -1.0f, 0.0001f,
                "saliency best dark patch: invalid width initializes score");
    EXPECT(x == 0, "saliency best dark patch: invalid width initializes x");
    EXPECT(y == 0, "saliency best dark patch: invalid width initializes y");
}

static void test_saliency_choose_best_dark_patch_best_score_and_coordinates(void) {
    float map[6] = {
        0.10f, 0.20f, 0.30f,
        0.40f, 0.95f, 0.60f
    };
    float score = -1.0f;
    int x = 0;
    int y = 0;

    anomaly_saliency_choose_best_dark_patch(map, 3, 2, 10, 20, 5, &score, &x, &y);

    EXPECT_NEAR(score, 0.95f, 0.0001f,
                "saliency best dark patch: returns best score");
    EXPECT(x == 15, "saliency best dark patch: converts best sx to ROI x");
    EXPECT(y == 25, "saliency best dark patch: converts best sy to ROI y");
}

static void test_saliency_choose_best_dark_patch_first_max_and_negative_scores(void) {
    float tie_map[4] = {
        0.50f, 0.80f,
        0.80f, 0.10f
    };
    float negative_map[4] = {
        -4.0f, -3.0f,
        -2.0f, -5.0f
    };
    float score = 0.0f;
    int x = 0;
    int y = 0;

    anomaly_saliency_choose_best_dark_patch(tie_map, 2, 2, 7, 11, 3, &score, &x, &y);

    EXPECT_NEAR(score, 0.80f, 0.0001f,
                "saliency best dark patch: tie keeps first row-major max score");
    EXPECT(x == 10 && y == 11,
           "saliency best dark patch: tie keeps first row-major max coordinate");

    anomaly_saliency_choose_best_dark_patch(negative_map, 2, 2, 7, 11, 3, &score, &x, &y);

    EXPECT_NEAR(score, -1.0f, 0.0001f,
                "saliency best dark patch: all scores below initial floor keep default score");
    EXPECT(x == 7 && y == 11,
           "saliency best dark patch: all scores below floor keep default coordinate");
}

static void test_saliency_classify_display_algorithm_invalid_xy_returns_persist(void) {
    float thermal[4] = {1.0f, 1.0f, 1.0f, 1.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, NULL, NULL, NULL, NULL, NULL,
                   2, 2, -1, 0, false, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_PERSIST,
           "saliency classify: negative sx returns persist");
    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, NULL, NULL, NULL, NULL, NULL,
                   2, 2, 0, 2, false, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_PERSIST,
           "saliency classify: out-of-range sy returns persist");
}

static void test_saliency_classify_display_algorithm_null_maps_no_evidence_returns_persist(void) {
    EXPECT(anomaly_saliency_classify_display_algorithm(
                   NULL, NULL, NULL, NULL, NULL, NULL,
                   2, 2, 1, 1, false, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_PERSIST,
           "saliency classify: null maps without temporal evidence return persist");
}

static void test_saliency_classify_display_algorithm_thermal_spatial_wins(void) {
    float thermal[4] = {0.0f, 1.0f, 0.0f, 0.0f};
    float color[4] = {0.0f, 0.5f, 0.0f, 0.0f};
    float motion[4] = {0.0f, 0.5f, 0.0f, 0.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, color, motion, NULL, NULL, NULL,
                   2, 2, 1, 0, true, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_THERMAL,
           "saliency classify: strongest thermal spatial evidence wins");
}

static void test_saliency_classify_display_algorithm_color_wins(void) {
    float thermal[4] = {0.0f, 0.5f, 0.0f, 0.0f};
    float color[4] = {0.0f, 2.0f, 0.0f, 0.0f};
    float motion[4] = {0.0f, 0.5f, 0.0f, 0.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, color, motion, NULL, NULL, NULL,
                   2, 2, 1, 0, true, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_COLOR,
           "saliency classify: strongest color evidence wins");
}

static void test_saliency_classify_display_algorithm_motion_wins_without_bg(void) {
    float thermal[4] = {0.0f, 0.2f, 0.0f, 0.0f};
    float color[4] = {0.0f, 0.5f, 0.0f, 0.0f};
    float motion[4] = {0.0f, 3.0f, 0.0f, 0.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, color, motion, NULL, NULL, NULL,
                   2, 2, 1, 0, false, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_MOTION,
           "saliency classify: motion uses 0.45 multiplier without bg and can win");
}

static void test_saliency_classify_display_algorithm_near_tie_returns_persist(void) {
    float thermal[4] = {0.0f, 1.0f, 0.0f, 0.0f};
    float color[4] = {0.0f, 1.8333334f, 0.0f, 0.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, color, NULL, NULL, NULL, NULL,
                   2, 2, 1, 0, false, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_PERSIST,
           "saliency classify: near thermal/color tie returns persist");
}

static void test_saliency_classify_display_algorithm_thermal_temporal_paths(void) {
    float bg[4] = {0.0f, 0.8f, 0.7f, 0.0f};
    float sg_black_hot[4] = {0.0f, 0.2f, 0.0f, 0.0f};
    float sg_white_hot[4] = {0.0f, 0.0f, 1.3f, 0.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   NULL, NULL, NULL, NULL, bg, sg_black_hot,
                   2, 2, 1, 0, true, true, 0.1f, 0.0f, 0.5f) == ANOMALY_ALGO_THERMAL,
           "saliency classify: black-hot temporal delta can classify thermal");
    EXPECT(anomaly_saliency_classify_display_algorithm(
                   NULL, NULL, NULL, NULL, bg, sg_white_hot,
                   2, 2, 0, 1, true, false, 0.1f, 0.0f, 0.5f) == ANOMALY_ALGO_THERMAL,
           "saliency classify: white-hot temporal delta can classify thermal");
}

static void test_saliency_classify_display_algorithm_registration_zero_suppresses(void) {
    float thermal[4] = {0.0f, 1.0f, 0.0f, 0.0f};
    float color[4] = {0.0f, 2.0f, 0.0f, 0.0f};
    float motion[4] = {0.0f, 3.0f, 0.0f, 0.0f};
    float registration[4] = {1.0f, 0.0f, 1.0f, 1.0f};

    EXPECT(anomaly_saliency_classify_display_algorithm(
                   thermal, color, motion, registration, NULL, NULL,
                   2, 2, 1, 0, true, false, 0.1f, 0.0f, 1.0f) == ANOMALY_ALGO_PERSIST,
           "saliency classify: zero registration scales all evidence to persist");
}

static void test_scratch_capacity_null_and_zero_count_contracts(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(!anomaly_scratch_ensure_sampled_grid_capacity(NULL, 1),
           "scratch capacity: sampled grid rejects null state");
    EXPECT(!anomaly_scratch_ensure_registration_luma_capacity(NULL, 1),
           "scratch capacity: registration luma rejects null state");
    EXPECT(!anomaly_scratch_ensure_saliency_capacity(NULL, 1),
           "scratch capacity: saliency rejects null state");
    EXPECT(!anomaly_scratch_ensure_patch_capacity(NULL, 1),
           "scratch capacity: patch rejects null state");
    EXPECT(!anomaly_scratch_ensure_prev_roi_snapshot_capacity(NULL, 1),
           "scratch capacity: prev ROI rejects null state");

    EXPECT(anomaly_scratch_ensure_sampled_grid_capacity(&state, 0),
           "scratch capacity: sampled grid zero count succeeds");
    EXPECT(anomaly_scratch_ensure_registration_luma_capacity(&state, 0),
           "scratch capacity: registration luma zero count succeeds");
    EXPECT(anomaly_scratch_ensure_saliency_capacity(&state, 0),
           "scratch capacity: saliency zero count succeeds");
    EXPECT(anomaly_scratch_ensure_patch_capacity(&state, 0),
           "scratch capacity: patch zero count succeeds");
    EXPECT(anomaly_scratch_ensure_prev_roi_snapshot_capacity(&state, 0),
           "scratch capacity: prev ROI zero count succeeds");
    EXPECT(state.scratch_sampled_grid_capacity == 0 &&
           state.scratch_registration_luma_capacity == 0 &&
           state.scratch_saliency_capacity == 0 &&
           state.scratch_patch_capacity == 0 &&
           state.scratch_prev_roi_capacity == 0,
           "scratch capacity: zero count leaves capacities unchanged");

    anomaly_state_cleanup(&state);
}

static void test_scratch_capacity_allocates_all_primary_groups(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(anomaly_scratch_ensure_sampled_grid_capacity(&state, 3),
           "scratch capacity: sampled grid allocation succeeds");
    EXPECT(state.scratch_sampled_grid_capacity == 3 &&
           state.scratch_sg_luma != NULL &&
           state.scratch_ii_sum != NULL &&
           state.scratch_ii_sum2 != NULL,
           "scratch capacity: sampled grid owns all expected buffers");

    EXPECT(anomaly_scratch_ensure_registration_luma_capacity(&state, 4),
           "scratch capacity: registration luma allocation succeeds");
    EXPECT(state.scratch_registration_luma_capacity == 4 &&
           state.scratch_registration_luma != NULL &&
           state.scratch_registration_tmp != NULL,
           "scratch capacity: registration luma owns both buffers");

    EXPECT(anomaly_scratch_ensure_saliency_capacity(&state, 5),
           "scratch capacity: saliency allocation succeeds");
    EXPECT(state.scratch_saliency_capacity == 5 &&
           state.scratch_saliency_spatial != NULL &&
           state.scratch_saliency_color != NULL &&
           state.scratch_saliency_motion != NULL &&
           state.scratch_saliency_registration != NULL &&
           state.scratch_thermal_delta != NULL,
           "scratch capacity: saliency owns every score buffer");

    EXPECT(anomaly_scratch_ensure_patch_capacity(&state, 6),
           "scratch capacity: patch allocation succeeds");
    EXPECT(state.scratch_patch_capacity == 6 &&
           state.scratch_patch_score != NULL &&
           state.scratch_patch_selection != NULL,
           "scratch capacity: patch owns score and selection buffers");

    anomaly_state_cleanup(&state);
}

static void test_scratch_capacity_prev_roi_snapshot_buffers(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(anomaly_scratch_ensure_prev_roi_snapshot_capacity(&state, 7),
           "scratch capacity: prev ROI snapshot allocation succeeds");
    EXPECT(state.scratch_prev_roi_capacity == 7 &&
           state.scratch_prev_roi_last_luma != NULL &&
           state.scratch_prev_roi_thermal_score != NULL &&
           state.scratch_prev_roi_temporal_score != NULL &&
           state.scratch_prev_roi_color_luma != NULL &&
           state.scratch_prev_roi_color_u != NULL &&
           state.scratch_prev_roi_color_v != NULL &&
           state.scratch_prev_roi_color_raw_score != NULL &&
           state.scratch_prev_roi_color_contrast_weight != NULL &&
           state.scratch_prev_roi_color_u_bin != NULL &&
           state.scratch_prev_roi_color_v_bin != NULL &&
           state.scratch_prev_roi_valid_mask != NULL &&
           state.scratch_prev_roi_coverage_age != NULL &&
           state.scratch_prev_roi_color_valid_mask != NULL &&
           state.scratch_prev_roi_color_phase_x != NULL &&
           state.scratch_prev_roi_color_phase_y != NULL,
           "scratch capacity: prev ROI snapshot owns every legacy buffer");

    anomaly_state_cleanup(&state);
}

static void test_scratch_capacity_preserves_existing_larger_buffers(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(anomaly_scratch_ensure_patch_capacity(&state, 8),
           "scratch capacity: patch initial allocation succeeds");
    float *patch_score = state.scratch_patch_score;
    float *patch_selection = state.scratch_patch_selection;
    EXPECT(anomaly_scratch_ensure_patch_capacity(&state, 4),
           "scratch capacity: smaller patch request succeeds");
    EXPECT(state.scratch_patch_capacity == 8 &&
           state.scratch_patch_score == patch_score &&
           state.scratch_patch_selection == patch_selection,
           "scratch capacity: smaller patch request preserves buffers and capacity");

    EXPECT(anomaly_scratch_ensure_saliency_capacity(&state, 9),
           "scratch capacity: saliency initial allocation succeeds");
    float *saliency_spatial = state.scratch_saliency_spatial;
    EXPECT(anomaly_scratch_ensure_saliency_capacity(&state, 3),
           "scratch capacity: smaller saliency request succeeds");
    EXPECT(state.scratch_saliency_capacity == 9 &&
           state.scratch_saliency_spatial == saliency_spatial,
           "scratch capacity: smaller saliency request preserves buffers and capacity");

    anomaly_state_cleanup(&state);
}

static void test_roi_state_capacity_allocation(void) {
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, 5),
           "roi state capacity: allocates pixel buffers");
    EXPECT(roi.pixel_capacity == 5,
           "roi state capacity: records pixel capacity");
    EXPECT(roi.last_luma != NULL && roi.thermal_score != NULL &&
           roi.temporal_score != NULL && roi.color_luma != NULL &&
           roi.color_u != NULL && roi.color_v != NULL &&
           roi.color_raw_score != NULL && roi.color_contrast_weight != NULL &&
           roi.color_u_bin != NULL && roi.color_v_bin != NULL &&
           roi.valid_mask != NULL && roi.fresh_mask != NULL &&
           roi.carried_mask != NULL && roi.new_exposed_mask != NULL &&
           roi.color_valid_mask != NULL && roi.color_phase_x != NULL &&
           roi.color_phase_y != NULL && roi.reg_confidence != NULL &&
           roi.coverage_age != NULL,
           "roi state capacity: allocates every pixel-owned buffer");

    float *last_luma = roi.last_luma;
    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, 3),
           "roi state capacity: smaller request succeeds");
    EXPECT(roi.pixel_capacity == 5 && roi.last_luma == last_luma,
           "roi state capacity: smaller request preserves capacity");

    anomaly_roi_state_release(&roi);
}

static void test_roi_state_zero_count_and_null_behavior(void) {
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, 0),
           "roi state capacity: zero pixel count succeeds");
    EXPECT(roi.pixel_capacity == 0 && roi.last_luma == NULL,
           "roi state capacity: zero pixel count leaves buffers untouched");
    EXPECT(!anomaly_roi_state_ensure_pixel_capacity(NULL, 1),
           "roi state capacity: NULL roi is rejected");
    EXPECT(anomaly_roi_state_ensure_cell_capacity(&roi, 0),
           "roi cell capacity: zero cell count succeeds");
    EXPECT(roi.cell_capacity == 0 && roi.cell_summaries == NULL,
           "roi cell capacity: zero cell count leaves buffers untouched");
    EXPECT(!anomaly_roi_state_ensure_cell_capacity(NULL, 1),
           "roi cell capacity: NULL roi is rejected");

    anomaly_roi_state_clear(NULL);
    anomaly_roi_state_release(NULL);
}

static void test_roi_state_cell_capacity_allocation(void) {
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    EXPECT(anomaly_roi_state_ensure_cell_capacity(&roi, 4),
           "roi cell capacity: allocates cell summaries");
    EXPECT(roi.cell_capacity == 4 && roi.cell_summaries != NULL,
           "roi cell capacity: records cell capacity");
    anomaly_roi_cell_summary_t *cells = roi.cell_summaries;
    EXPECT(anomaly_roi_state_ensure_cell_capacity(&roi, 2),
           "roi cell capacity: smaller request succeeds");
    EXPECT(roi.cell_capacity == 4 && roi.cell_summaries == cells,
           "roi cell capacity: smaller request preserves capacity");

    anomaly_roi_state_release(&roi);
}

static void test_roi_state_clear_preserves_capacity_and_zeros_fields(void) {
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));
    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, 3),
           "roi clear: pixel capacity setup succeeds");
    EXPECT(anomaly_roi_state_ensure_cell_capacity(&roi, 2),
           "roi clear: cell capacity setup succeeds");

    roi.valid = true;
    roi.roi_x0 = 1;
    roi.roi_y0 = 2;
    roi.roi_x1 = 3;
    roi.roi_y1 = 4;
    roi.width = 5;
    roi.height = 6;
    roi.sample_step = 7;
    roi.cell_size_px = 8;
    roi.cell_cols = 9;
    roi.cell_rows = 10;
    memset(roi.color_valid_mask, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.color_phase_x, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.color_phase_y, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.color_u_bin, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.color_v_bin, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.valid_mask, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.fresh_mask, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.carried_mask, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.new_exposed_mask, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    memset(roi.coverage_age, 0x7f, roi.pixel_capacity * sizeof(uint8_t));
    for (size_t i = 0; i < roi.pixel_capacity; ++i) {
        roi.color_luma[i] = 1.0f;
        roi.color_u[i] = 2.0f;
        roi.color_v[i] = 3.0f;
        roi.color_raw_score[i] = 4.0f;
        roi.color_contrast_weight[i] = 5.0f;
        roi.reg_confidence[i] = 6.0f;
    }
    roi.cell_summaries[0].valid_count = 11;
    roi.cell_summaries[0].registration_quality = 0.5f;
    size_t pixel_capacity = roi.pixel_capacity;
    size_t cell_capacity = roi.cell_capacity;
    uint8_t *valid_mask = roi.valid_mask;
    anomaly_roi_cell_summary_t *cell_summaries = roi.cell_summaries;

    anomaly_roi_state_clear(&roi);

    EXPECT(!roi.valid && roi.roi_x0 == 0 && roi.roi_y0 == 0 &&
           roi.roi_x1 == 0 && roi.roi_y1 == 0 &&
           roi.width == 0 && roi.height == 0 && roi.sample_step == 0 &&
           roi.cell_size_px == 0 && roi.cell_cols == 0 && roi.cell_rows == 0,
           "roi clear: invalidates ROI geometry fields");
    EXPECT(roi.pixel_capacity == pixel_capacity && roi.cell_capacity == cell_capacity &&
           roi.valid_mask == valid_mask && roi.cell_summaries == cell_summaries,
           "roi clear: preserves allocated capacity");
    EXPECT(roi.color_valid_mask[0] == 0 && roi.color_phase_x[1] == 0 &&
           roi.color_phase_y[2] == 0 && roi.color_u_bin[0] == 0 &&
           roi.color_v_bin[1] == 0 && roi.valid_mask[2] == 0 &&
           roi.fresh_mask[0] == 0 && roi.carried_mask[1] == 0 &&
           roi.new_exposed_mask[2] == 0 && roi.coverage_age[0] == 0,
           "roi clear: zeros mask buffers");
    EXPECT(roi.color_luma[0] == 0.0f && roi.color_u[1] == 0.0f &&
           roi.color_v[2] == 0.0f && roi.color_raw_score[0] == 0.0f &&
           roi.color_contrast_weight[1] == 0.0f && roi.reg_confidence[2] == 0.0f,
           "roi clear: zeros color and registration buffers");
    EXPECT(roi.cell_summaries[0].valid_count == 0 &&
           roi.cell_summaries[0].registration_quality == 0.0f,
           "roi clear: zeros cell summaries");

    anomaly_roi_state_release(&roi);
}

static void test_roi_state_release_zeros_struct(void) {
    anomaly_roi_state_t roi;
    anomaly_roi_state_t zero;
    memset(&roi, 0, sizeof(roi));
    memset(&zero, 0, sizeof(zero));

    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, 2),
           "roi release: pixel capacity setup succeeds");
    EXPECT(anomaly_roi_state_ensure_cell_capacity(&roi, 2),
           "roi release: cell capacity setup succeeds");
    roi.valid = true;
    roi.width = 2;
    anomaly_roi_state_release(&roi);
    EXPECT(memcmp(&roi, &zero, sizeof(roi)) == 0,
           "roi release: frees owned buffers and zeros struct");
}

static void test_roi_state_summarize_cells_rejects_null_and_invalid(void) {
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    anomaly_roi_state_summarize_cells(NULL, NULL, 3, 0.6f);

    roi.valid = false;
    roi.width = 2;
    roi.height = 2;
    roi.cell_cols = 1;
    roi.cell_rows = 1;
    anomaly_roi_state_summarize_cells(&roi, NULL, 3, 0.6f);
    EXPECT(roi.cell_summaries == NULL && roi.cell_capacity == 0,
           "roi summarize: invalid ROI is ignored without allocating cells");

    roi.valid = true;
    roi.width = 0;
    roi.height = 2;
    anomaly_roi_state_summarize_cells(&roi, NULL, 3, 0.6f);
    EXPECT(roi.cell_summaries == NULL && roi.cell_capacity == 0,
           "roi summarize: invalid dimensions are ignored without allocating cells");

    anomaly_roi_state_release(&roi);
}

static void test_roi_state_summarize_cells_counts_flags_and_scores(void) {
    enum { width = 17, height = 17, count = width * height };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    EXPECT(anomaly_roi_state_ensure_pixel_capacity(&roi, count),
           "roi summarize: pixel capacity setup succeeds");
    memset(roi.valid_mask, 0, count * sizeof(uint8_t));
    memset(roi.fresh_mask, 0, count * sizeof(uint8_t));
    memset(roi.carried_mask, 0, count * sizeof(uint8_t));
    memset(roi.new_exposed_mask, 0, count * sizeof(uint8_t));
    memset(roi.coverage_age, 0, count * sizeof(uint8_t));
    memset(roi.reg_confidence, 0, count * sizeof(float));
    memset(roi.thermal_score, 0, count * sizeof(float));
    memset(roi.color_raw_score, 0, count * sizeof(float));
    float motion[count];
    memset(motion, 0, sizeof(motion));

    roi.valid = true;
    roi.width = width;
    roi.height = height;
    roi.sample_step = 1;
    roi.cell_cols = 2;
    roi.cell_rows = 2;

    size_t top_left_fresh = 0u;
    roi.valid_mask[top_left_fresh] = 1u;
    roi.fresh_mask[top_left_fresh] = 1u;
    roi.thermal_score[top_left_fresh] = 3.0f;
    roi.color_raw_score[top_left_fresh] = 2.0f;
    roi.reg_confidence[top_left_fresh] = 0.7f;
    motion[top_left_fresh] = 0.4f;

    size_t top_left_stale = 1u;
    roi.valid_mask[top_left_stale] = 1u;
    roi.carried_mask[top_left_stale] = 1u;
    roi.coverage_age[top_left_stale] = 4u;
    roi.thermal_score[top_left_stale] = 5.0f;
    roi.color_raw_score[top_left_stale] = 1.0f;
    roi.reg_confidence[top_left_stale] = 0.2f;
    motion[top_left_stale] = 0.1f;

    size_t top_left_new = 2u;
    roi.new_exposed_mask[top_left_new] = 1u;
    roi.reg_confidence[top_left_new] = 0.0f;

    size_t top_right_new = 16u;
    roi.new_exposed_mask[top_right_new] = 1u;
    roi.color_raw_score[top_right_new] = 6.0f;
    roi.reg_confidence[top_right_new] = 0.0f;
    motion[top_right_new] = 0.8f;

    size_t bottom_left_fresh_new = (size_t)16 * width;
    roi.valid_mask[bottom_left_fresh_new] = 1u;
    roi.fresh_mask[bottom_left_fresh_new] = 1u;
    roi.new_exposed_mask[bottom_left_fresh_new] = 1u;
    roi.reg_confidence[bottom_left_fresh_new] = 0.9f;
    motion[bottom_left_fresh_new] = 0.3f;

    size_t bottom_right_carried = (size_t)16 * width + 16u;
    roi.valid_mask[bottom_right_carried] = 1u;
    roi.carried_mask[bottom_right_carried] = 1u;
    roi.coverage_age[bottom_right_carried] = 3u;
    roi.reg_confidence[bottom_right_carried] = 0.0f;

    anomaly_roi_state_summarize_cells(&roi, motion, 3, 0.6f);

    EXPECT(roi.cell_capacity == 4 && roi.cell_summaries != NULL,
           "roi summarize: allocates summaries for ROI cells");

    const anomaly_roi_cell_summary_t *top_left = &roi.cell_summaries[0];
    EXPECT(top_left->valid_count == 2 && top_left->fresh_count == 1 &&
           top_left->carried_count == 1 && top_left->newly_exposed_count == 1 &&
           top_left->stale_count == 1,
           "roi summarize: top-left cell counts valid/fresh/carried/new/stale samples");
    EXPECT((top_left->scan_flags & ANOMALY_SCAN_FLAG_NEW_EXPOSED) != 0u &&
           (top_left->scan_flags & ANOMALY_SCAN_FLAG_STALE) != 0u &&
           (top_left->scan_flags & ANOMALY_SCAN_FLAG_LOW_CONFIDENCE) != 0u,
           "roi summarize: top-left cell records new/stale/low-confidence flags");
    EXPECT_NEAR(top_left->max_thermal_score, 5.0f, 0.0001f,
                "roi summarize: records max thermal score");
    EXPECT_NEAR(top_left->max_color_score, 2.0f, 0.0001f,
                "roi summarize: records max color score");
    EXPECT_NEAR(top_left->max_motion_support, 0.4f, 0.0001f,
                "roi summarize: records max motion support");
    EXPECT_NEAR(top_left->registration_quality, 0.7f, 0.0001f,
                "roi summarize: records max per-sample registration confidence");

    const anomaly_roi_cell_summary_t *top_right = &roi.cell_summaries[1];
    EXPECT(top_right->valid_count == 0 && top_right->newly_exposed_count == 1,
           "roi summarize: top-right cell counts newly exposed invalid samples");
    EXPECT((top_right->scan_flags & ANOMALY_SCAN_FLAG_NEW_EXPOSED) != 0u &&
           (top_right->scan_flags & ANOMALY_SCAN_FLAG_LOW_CONFIDENCE) != 0u,
           "roi summarize: top-right cell records new and low-confidence flags");
    EXPECT_NEAR(top_right->registration_quality, 0.6f, 0.0001f,
                "roi summarize: falls back to frame registration confidence");
    EXPECT_NEAR(top_right->max_color_score, 6.0f, 0.0001f,
                "roi summarize: top-right cell records color max");
    EXPECT_NEAR(top_right->max_motion_support, 0.8f, 0.0001f,
                "roi summarize: top-right cell records motion max");

    const anomaly_roi_cell_summary_t *bottom_left = &roi.cell_summaries[2];
    EXPECT(bottom_left->valid_count == 1 && bottom_left->fresh_count == 1 &&
           bottom_left->newly_exposed_count == 1,
           "roi summarize: bottom-left cell counts valid fresh newly exposed sample");
    EXPECT((bottom_left->scan_flags & ANOMALY_SCAN_FLAG_NEW_EXPOSED) != 0u,
           "roi summarize: bottom-left cell records newly exposed flag");
    EXPECT_NEAR(bottom_left->registration_quality, 0.9f, 0.0001f,
                "roi summarize: bottom-left cell records registration quality");

    const anomaly_roi_cell_summary_t *bottom_right = &roi.cell_summaries[3];
    EXPECT(bottom_right->valid_count == 1 && bottom_right->carried_count == 1 &&
           bottom_right->stale_count == 0,
           "roi summarize: carry expiry is strict greater-than");
    EXPECT((bottom_right->scan_flags & ANOMALY_SCAN_FLAG_LOW_CONFIDENCE) != 0u &&
           (bottom_right->scan_flags & ANOMALY_SCAN_FLAG_STALE) == 0u,
           "roi summarize: bottom-right cell records low-confidence but not stale");
    EXPECT_NEAR(bottom_right->registration_quality, 0.6f, 0.0001f,
                "roi summarize: bottom-right cell uses fallback registration quality");

    anomaly_roi_state_release(&roi);
}

static void test_target_revisit_null_defaults_and_track_count(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(anomaly_target_revisit_track_count(NULL) == 0,
           "target revisit: NULL state has no tracks");

    int track_index = 99;
    float gate_radius = 99.0f;
    EXPECT(!anomaly_target_revisit_point_inside_gate(NULL, 0.5f, 0.5f, 2, &track_index, &gate_radius),
           "target revisit gate: NULL state is rejected");
    EXPECT(track_index == -1 && gate_radius == 0.0f,
           "target revisit gate: NULL state writes default outputs");

    track_index = 99;
    gate_radius = 99.0f;
    EXPECT(!anomaly_target_revisit_point_inside_gate(&state, -0.1f, 0.5f, 2, &track_index, &gate_radius),
           "target revisit gate: negative coordinates are rejected");
    EXPECT(track_index == -1 && gate_radius == 0.0f,
           "target revisit gate: rejected coordinates write default outputs");

    state.target_tracks[0].active = true;
    state.target_tracks[0].confidence = ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN - 0.01f;
    EXPECT(anomaly_target_revisit_track_count(&state) == 0,
           "target revisit count: low-confidence plain active track is ignored");

    state.target_tracks[0].confidence = ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN;
    EXPECT(anomaly_target_revisit_track_count(&state) == 1,
           "target revisit count: confidence threshold includes active track");

    state.target_tracks[1].active = true;
    state.target_tracks[1].miss_count = 1;
    state.target_tracks[2].active = true;
    state.target_tracks[2].forced_revisit = true;
    state.target_tracks[3].forced_revisit = true;
    EXPECT(anomaly_target_revisit_track_count(&state) == 3,
           "target revisit count: counts confidence, missed, and forced active tracks only");
}

static void test_target_revisit_adaptive_risk_and_weak_lock(void) {
    bool has_track_risk = true;
    bool has_weak_lock = true;
    anomaly_target_revisit_adaptive_track_risk(NULL, 2, &has_track_risk, &has_weak_lock);
    EXPECT(!has_track_risk && !has_weak_lock,
           "target revisit risk: NULL state clears output flags");

    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].publish_confirmed = true;
    state.target_tracks[0].hit_count = 2;
    state.target_tracks[0].confidence = 0.70f;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(!has_track_risk && !has_weak_lock,
           "target revisit risk: strong confirmed track is not risky");

    state.target_tracks[0].publish_confirmed = false;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_track_risk && !has_weak_lock,
           "target revisit risk: unconfirmed track is risky without weak lock");

    state.target_tracks[0].publish_confirmed = true;
    state.target_tracks[0].hit_count = 1;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_track_risk,
           "target revisit risk: hit count below min_hits is risky");

    state.target_tracks[0].hit_count = 2;
    state.target_tracks[0].forced_revisit = true;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_track_risk,
           "target revisit risk: forced revisit track is risky");

    state.target_tracks[0].forced_revisit = false;
    state.target_tracks[0].miss_count = 1;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_track_risk && has_weak_lock,
           "target revisit risk: missed track is risky and weak");

    state.target_tracks[0].miss_count = 0;
    state.target_tracks[0].confidence = 0.54f;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(!has_track_risk && has_weak_lock,
           "target revisit risk: low confidence is weak without track risk");

    state.target_tracks[0].confidence = 0.70f;
    state.target_tracks[0].movement_parallax_frames = 1;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_weak_lock,
           "target revisit risk: parallax frames mark weak lock");

    state.target_tracks[0].movement_parallax_frames = 0;
    state.target_tracks[0].movement_window_frames = 6;
    state.target_tracks[0].movement_valid_frames = 2;
    anomaly_target_revisit_adaptive_track_risk(&state, 2, &has_track_risk, &has_weak_lock);
    EXPECT(has_weak_lock,
           "target revisit risk: less than half valid movement frames marks weak lock");
}

static void test_target_revisit_radius_scaling_and_clamps(void) {
    anomaly_target_track_t track;
    memset(&track, 0, sizeof(track));

    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(NULL, 2), 0.0f, 0.0001f,
                "target revisit radius: NULL track returns zero");

    track.hit_count = 1;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 1), 0.012f, 0.0001f,
                "target revisit radius: minimum base radius clamps up");

    track.support_radius_norm = 0.020f;
    track.half_w_norm = 0.030f;
    track.half_h_norm = 0.030f;
    track.hit_count = 2;
    track.miss_count = 0;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 2), 0.030f, 0.0001f,
                "target revisit radius: uses max support and half extents");

    track.hit_count = 0;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 2),
                0.030f * ANOMALY_TARGET_PROVISIONAL_REVISIT_SCALE,
                0.0001f,
                "target revisit radius: provisional tracks scale up");

    track.hit_count = 0;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 0),
                0.030f * ANOMALY_TARGET_PROVISIONAL_REVISIT_SCALE,
                0.0001f,
                "target revisit radius: min_hits below one falls back to one");

    track.hit_count = 2;
    track.miss_count = 1;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 2), 0.030f * 1.45f, 0.0001f,
                "target revisit radius: missed tracks scale up");

    track.support_radius_norm = 0.20f;
    track.half_w_norm = 0.01f;
    track.half_h_norm = 0.01f;
    track.miss_count = 0;
    EXPECT_NEAR(anomaly_target_revisit_radius_for_track(&track, 2),
                ANOMALY_TARGET_REVISIT_MAX_RADIUS,
                0.0001f,
                "target revisit radius: large radius clamps down");
}

static void test_target_revisit_point_inside_gate_closest_match(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    state.target_tracks[0].active = true;
    state.target_tracks[0].forced_revisit = true;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.050f;
    state.target_tracks[0].hit_count = 2;

    state.target_tracks[1].active = true;
    state.target_tracks[1].forced_revisit = true;
    state.target_tracks[1].center_x_norm = 0.52f;
    state.target_tracks[1].center_y_norm = 0.50f;
    state.target_tracks[1].support_radius_norm = 0.050f;
    state.target_tracks[1].hit_count = 2;

    state.target_tracks[2].active = true;
    state.target_tracks[2].forced_revisit = false;
    state.target_tracks[2].center_x_norm = 0.515f;
    state.target_tracks[2].center_y_norm = 0.50f;
    state.target_tracks[2].support_radius_norm = 0.050f;
    state.target_tracks[2].hit_count = 2;

    int track_index = -1;
    float gate_radius = 0.0f;
    EXPECT(anomaly_target_revisit_point_inside_gate(&state, 0.515f, 0.50f, 2, &track_index, &gate_radius),
           "target revisit gate: point inside forced track matches");
    EXPECT(track_index == 1,
           "target revisit gate: closest forced revisit track wins");
    EXPECT_NEAR(gate_radius, 0.050f, 0.0001f,
                "target revisit gate: writes matching gate radius");

    track_index = 99;
    gate_radius = 99.0f;
    EXPECT(!anomaly_target_revisit_point_inside_gate(&state, 0.90f, 0.90f, 2, &track_index, &gate_radius),
           "target revisit gate: off-gate point is rejected");
    EXPECT(track_index == -1 && gate_radius == 0.0f,
           "target revisit gate: off-gate point writes default outputs");

    EXPECT(anomaly_target_revisit_point_inside_gate(&state, 0.515f, 0.50f, 2, NULL, NULL),
           "target revisit gate: NULL output pointers are allowed");
}

static void test_target_revisit_annotate_roi_cells_no_op_filters(void) {
    anomaly_roi_cell_summary_t cells[4];
    memset(cells, 0, sizeof(cells));
    cells[0].scan_flags = ANOMALY_SCAN_FLAG_NEW_EXPOSED;

    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));
    roi.valid = true;
    roi.cell_cols = 2;
    roi.cell_rows = 2;
    roi.cell_summaries = cells;

    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    anomaly_target_revisit_annotate_roi_cells(NULL, &state, 2);
    anomaly_target_revisit_annotate_roi_cells(&roi, NULL, 2);

    roi.valid = false;
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    roi.valid = true;
    roi.cell_summaries = NULL;
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    roi.cell_summaries = cells;
    roi.cell_cols = 0;
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    roi.cell_cols = 2;
    roi.cell_rows = 0;
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    roi.cell_rows = 2;

    state.target_tracks[0].active = true;
    state.target_tracks[0].forced_revisit = false;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.50f;

    state.target_tracks[1].active = false;
    state.target_tracks[1].forced_revisit = true;
    state.target_tracks[1].center_x_norm = 0.50f;
    state.target_tracks[1].center_y_norm = 0.50f;
    state.target_tracks[1].support_radius_norm = 0.50f;

    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    EXPECT(cells[0].scan_flags == ANOMALY_SCAN_FLAG_NEW_EXPOSED &&
           cells[1].scan_flags == 0u &&
           cells[2].scan_flags == 0u &&
           cells[3].scan_flags == 0u,
           "target revisit annotate: null/invalid and non-forced or inactive tracks are no-ops");
}

static void test_target_revisit_annotate_roi_cells_marks_forced_track_bounds(void) {
    anomaly_roi_cell_summary_t cells[100];
    memset(cells, 0, sizeof(cells));
    cells[44].scan_flags = ANOMALY_SCAN_FLAG_NEW_EXPOSED;

    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));
    roi.valid = true;
    roi.cell_cols = 10;
    roi.cell_rows = 10;
    roi.cell_summaries = cells;

    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].forced_revisit = true;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.050f;
    state.target_tracks[0].hit_count = 2;

    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    for (int y = 0; y < 10; y++) {
        for (int x = 0; x < 10; x++) {
            size_t idx = (size_t)y * 10u + (size_t)x;
            bool expected = (x >= 4 && x <= 5 && y >= 4 && y <= 5);
            bool marked = (cells[idx].scan_flags & ANOMALY_SCAN_FLAG_TARGET_REVISIT) != 0u;
            EXPECT(marked == expected,
                   "target revisit annotate: forced track marks expected bounded cells");
        }
    }
    EXPECT((cells[44].scan_flags & ANOMALY_SCAN_FLAG_NEW_EXPOSED) != 0u,
           "target revisit annotate: marks by OR without clearing existing flags");
}

static void test_target_revisit_annotate_roi_cells_min_hits_scales_provisional(void) {
    anomaly_roi_cell_summary_t cells[10000];
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));
    roi.valid = true;
    roi.cell_cols = 100;
    roi.cell_rows = 100;
    roi.cell_summaries = cells;

    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].forced_revisit = true;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.020f;
    state.target_tracks[0].hit_count = 1;

    memset(cells, 0, sizeof(cells));
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 1);
    EXPECT((cells[45u * 100u + 50u].scan_flags & ANOMALY_SCAN_FLAG_TARGET_REVISIT) == 0u,
           "target revisit annotate: confirmed-radius pass leaves outer provisional cell unmarked");

    memset(cells, 0, sizeof(cells));
    anomaly_target_revisit_annotate_roi_cells(&roi, &state, 2);
    EXPECT((cells[45u * 100u + 50u].scan_flags & ANOMALY_SCAN_FLAG_TARGET_REVISIT) != 0u,
           "target revisit annotate: provisional min_hits scaling expands marked cells");
}

static void test_target_tracks_clear_single_track_zeroes_fields(void) {
    anomaly_target_track_t track;
    memset(&track, 0x5A, sizeof(track));

    anomaly_target_tracks_clear_track(NULL);
    anomaly_target_tracks_clear_track(&track);

    const uint8_t *bytes = (const uint8_t *)&track;
    for (size_t i = 0; i < sizeof(track); i++) {
        EXPECT(bytes[i] == 0, "target tracks clear: clear_track should memset track to zero");
    }
}

static void test_target_tracks_clear_all_resets_tracks_and_next_id(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        state.target_tracks[i].active = true;
        state.target_tracks[i].id = 100 + i;
        state.target_tracks[i].confidence = 0.5f;
    }
    state.next_target_track_id = 42;

    anomaly_target_tracks_clear_all(NULL);
    anomaly_target_tracks_clear_all(&state);

    EXPECT(state.next_target_track_id == 1,
           "target tracks clear: clear_all should reset next_target_track_id to 1");
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        EXPECT(!state.target_tracks[i].active,
               "target tracks clear: clear_all should clear every track");
        EXPECT(state.target_tracks[i].id == 0,
               "target tracks clear: clear_all should zero target ids");
    }
}

static void test_roi_tracks_clear_all_resets_all_lifecycle_state(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    for (int i = 0; i < 4; i++) {
        state.acc_active[i] = true;
        state.acc_hits[i] = 3 + i;
        state.acc_hold[i] = 4 + i;
        state.acc_presence_mask[i] = 0xFu;
        state.acc_cx[i] = 0.20f + 0.01f * (float)i;
        state.acc_cy[i] = 0.40f + 0.01f * (float)i;
    }
    for (int i = 0; i < ANOMALY_COLOR_PROMOTION_TRACKS; i++) {
        state.color_promotion_active[i] = true;
        state.color_promotion_hits[i] = 2 + i;
        state.color_promotion_hold[i] = 5 + i;
        state.color_promotion_cx[i] = 0.10f + 0.02f * (float)i;
        state.color_promotion_cy[i] = 0.30f + 0.02f * (float)i;
    }
    state.saliency_display_algorithm = ANOMALY_ALGO_COLOR;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        state.saliency_aux_active[i] = true;
        state.saliency_aux_hits[i] = 6;
        state.saliency_aux_hold[i] = 7;
        state.saliency_aux_cx[i] = 0.51f;
        state.saliency_aux_cy[i] = 0.61f;
        state.saliency_aux_display_algorithm[i] = ANOMALY_ALGO_THERMAL;
    }
    state.target_tracks[0].active = true;
    state.target_tracks[0].id = 22;
    state.target_tracks[0].confidence = 0.75f;
    state.next_target_track_id = 23;

    anomaly_roi_tracks_clear_all(NULL);
    anomaly_roi_tracks_clear_all(&state);

    for (int i = 0; i < 4; i++) {
        EXPECT(!state.acc_active[i], "roi tracks clear_all: clears primary active flags");
        EXPECT(state.acc_hits[i] == 0, "roi tracks clear_all: clears primary hits");
        EXPECT(state.acc_hold[i] == 0, "roi tracks clear_all: clears primary hold");
        EXPECT(state.acc_presence_mask[i] == 0u, "roi tracks clear_all: clears primary masks");
        EXPECT_NEAR(state.acc_cx[i], 0.0f, 0.0001f, "roi tracks clear_all: clears primary cx");
        EXPECT_NEAR(state.acc_cy[i], 0.0f, 0.0001f, "roi tracks clear_all: clears primary cy");
    }
    for (int i = 0; i < ANOMALY_COLOR_PROMOTION_TRACKS; i++) {
        EXPECT(!state.color_promotion_active[i], "roi tracks clear_all: clears color promotion active");
        EXPECT(state.color_promotion_hits[i] == 0, "roi tracks clear_all: clears color promotion hits");
        EXPECT(state.color_promotion_hold[i] == 0, "roi tracks clear_all: clears color promotion hold");
        EXPECT_NEAR(state.color_promotion_cx[i], 0.0f, 0.0001f, "roi tracks clear_all: clears color promotion cx");
        EXPECT_NEAR(state.color_promotion_cy[i], 0.0f, 0.0001f, "roi tracks clear_all: clears color promotion cy");
    }
    EXPECT(state.saliency_display_algorithm == ANOMALY_ALGO_PERSIST,
           "roi tracks clear_all: resets saliency display algorithm");
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        EXPECT(!state.saliency_aux_active[i], "roi tracks clear_all: clears saliency aux active");
        EXPECT(state.saliency_aux_hits[i] == 0, "roi tracks clear_all: clears saliency aux hits");
        EXPECT(state.saliency_aux_hold[i] == 0, "roi tracks clear_all: clears saliency aux hold");
        EXPECT_NEAR(state.saliency_aux_cx[i], 0.0f, 0.0001f, "roi tracks clear_all: clears saliency aux cx");
        EXPECT_NEAR(state.saliency_aux_cy[i], 0.0f, 0.0001f, "roi tracks clear_all: clears saliency aux cy");
        EXPECT(state.saliency_aux_display_algorithm[i] == ANOMALY_ALGO_PERSIST,
               "roi tracks clear_all: resets saliency aux display algorithm");
    }
    EXPECT(!state.target_tracks[0].active,
           "roi tracks clear_all: clears target tracks");
    EXPECT(state.target_tracks[0].id == 0,
           "roi tracks clear_all: clears target track ids");
    EXPECT(state.next_target_track_id == 1,
           "roi tracks clear_all: resets next target track id");
}

static void test_roi_tracks_clear_saliency_only_preserves_unrelated_state(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    for (int i = 0; i < 4; i++) {
        state.acc_active[i] = true;
        state.acc_hits[i] = 10 + i;
        state.acc_hold[i] = 20 + i;
        state.acc_presence_mask[i] = (uint32_t)(0x10u + (uint32_t)i);
        state.acc_cx[i] = 0.10f * (float)(i + 1);
        state.acc_cy[i] = 0.20f * (float)(i + 1);
    }
    state.color_promotion_active[0] = true;
    state.color_promotion_hits[0] = 4;
    state.color_promotion_hold[0] = 8;
    state.color_promotion_cx[0] = 0.33f;
    state.color_promotion_cy[0] = 0.44f;
    state.target_tracks[0].active = true;
    state.target_tracks[0].id = 9;
    state.next_target_track_id = 10;
    state.saliency_display_algorithm = ANOMALY_ALGO_COLOR;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        state.saliency_aux_active[i] = true;
        state.saliency_aux_hits[i] = 3;
        state.saliency_aux_hold[i] = 4;
        state.saliency_aux_cx[i] = 0.55f;
        state.saliency_aux_cy[i] = 0.66f;
        state.saliency_aux_display_algorithm[i] = ANOMALY_ALGO_THERMAL;
    }

    anomaly_roi_tracks_clear_saliency(NULL);
    anomaly_roi_tracks_clear_saliency(&state);

    for (int i = 0; i < 3; i++) {
        EXPECT(state.acc_active[i], "roi tracks clear_saliency: preserves unrelated primary active flags");
        EXPECT(state.acc_hits[i] == 10 + i, "roi tracks clear_saliency: preserves unrelated primary hits");
        EXPECT(state.acc_hold[i] == 20 + i, "roi tracks clear_saliency: preserves unrelated primary hold");
        EXPECT(state.acc_presence_mask[i] == (uint32_t)(0x10u + (uint32_t)i),
               "roi tracks clear_saliency: preserves unrelated primary masks");
    }
    EXPECT(!state.acc_active[3], "roi tracks clear_saliency: clears saliency primary slot");
    EXPECT(state.acc_hits[3] == 0, "roi tracks clear_saliency: clears saliency primary hits");
    EXPECT(state.acc_hold[3] == 0, "roi tracks clear_saliency: clears saliency primary hold");
    EXPECT(state.acc_presence_mask[3] == 0u, "roi tracks clear_saliency: clears saliency primary mask");
    EXPECT(state.color_promotion_active[0], "roi tracks clear_saliency: preserves color promotion active");
    EXPECT(state.color_promotion_hits[0] == 4, "roi tracks clear_saliency: preserves color promotion hits");
    EXPECT(state.color_promotion_hold[0] == 8, "roi tracks clear_saliency: preserves color promotion hold");
    EXPECT_NEAR(state.color_promotion_cx[0], 0.33f, 0.0001f,
                "roi tracks clear_saliency: preserves color promotion cx");
    EXPECT(state.target_tracks[0].active && state.target_tracks[0].id == 9,
           "roi tracks clear_saliency: preserves target tracks");
    EXPECT(state.next_target_track_id == 10,
           "roi tracks clear_saliency: preserves next target track id");
    EXPECT(state.saliency_display_algorithm == ANOMALY_ALGO_PERSIST,
           "roi tracks clear_saliency: resets saliency display algorithm");
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        EXPECT(!state.saliency_aux_active[i], "roi tracks clear_saliency: clears saliency aux active");
        EXPECT(state.saliency_aux_hits[i] == 0, "roi tracks clear_saliency: clears saliency aux hits");
        EXPECT(state.saliency_aux_hold[i] == 0, "roi tracks clear_saliency: clears saliency aux hold");
        EXPECT(state.saliency_aux_display_algorithm[i] == ANOMALY_ALGO_PERSIST,
               "roi tracks clear_saliency: resets saliency aux display algorithm");
    }
}

static void test_roi_tracks_age_one_frame_lifecycle_and_preserves_unrelated_state(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.acc_active[0] = true;
    state.acc_hits[0] = 5;
    state.acc_hold[0] = 2;
    state.acc_presence_mask[0] = 0x7u;
    state.acc_cx[0] = 0.25f;
    state.acc_cy[0] = 0.35f;
    state.acc_active[1] = true;
    state.acc_hits[1] = 6;
    state.acc_hold[1] = 1;
    state.acc_presence_mask[1] = 0x8u;
    state.acc_cx[1] = 0.45f;
    state.acc_cy[1] = 0.55f;
    state.acc_active[2] = false;
    state.acc_hits[2] = 7;
    state.acc_hold[2] = 9;
    state.saliency_aux_active[0] = true;
    state.saliency_aux_hits[0] = 4;
    state.saliency_aux_hold[0] = 2;
    state.saliency_aux_cx[0] = 0.65f;
    state.saliency_aux_cy[0] = 0.75f;
    state.saliency_aux_display_algorithm[0] = ANOMALY_ALGO_COLOR;
    state.color_promotion_active[0] = true;
    state.color_promotion_hits[0] = 11;
    state.color_promotion_hold[0] = 12;
    state.color_promotion_cx[0] = 0.12f;
    state.color_promotion_cy[0] = 0.13f;
    state.target_tracks[0].active = true;
    state.target_tracks[0].id = 31;
    state.target_tracks[0].confidence = 0.62f;
    state.next_target_track_id = 32;

    anomaly_roi_tracks_age_one_frame(NULL);
    anomaly_roi_tracks_age_one_frame(&state);

    EXPECT(state.acc_active[0], "roi tracks age: keeps primary track active while hold remains");
    EXPECT(state.acc_hold[0] == 1, "roi tracks age: decrements primary hold");
    EXPECT(state.acc_hits[0] == 5, "roi tracks age: preserves primary hits while hold remains");
    EXPECT(!state.acc_active[1], "roi tracks age: clears primary track when hold reaches zero");
    EXPECT(state.acc_hits[1] == 0, "roi tracks age: clears primary hits when hold reaches zero");
    EXPECT(state.acc_hold[1] == 0, "roi tracks age: clears primary hold when hold reaches zero");
    EXPECT(!state.acc_active[2] && state.acc_hits[2] == 7 && state.acc_hold[2] == 9,
           "roi tracks age: leaves inactive primary slots untouched");
    EXPECT(state.saliency_aux_active[0], "roi tracks age: keeps saliency aux active while hold remains");
    EXPECT(state.saliency_aux_hold[0] == 1, "roi tracks age: decrements saliency aux hold");

    state.saliency_aux_hold[0] = 1;
    anomaly_roi_tracks_age_one_frame(&state);
    EXPECT(!state.saliency_aux_active[0], "roi tracks age: clears saliency aux when hold reaches zero");
    EXPECT(state.saliency_aux_hits[0] == 0, "roi tracks age: clears saliency aux hits at zero hold");
    EXPECT(state.saliency_aux_hold[0] == 0, "roi tracks age: clears saliency aux hold at zero hold");
    EXPECT(state.saliency_aux_display_algorithm[0] == ANOMALY_ALGO_PERSIST,
           "roi tracks age: resets saliency aux display algorithm at zero hold");
    EXPECT(state.color_promotion_active[0], "roi tracks age: preserves color promotion active");
    EXPECT(state.color_promotion_hits[0] == 11, "roi tracks age: preserves color promotion hits");
    EXPECT(state.color_promotion_hold[0] == 12, "roi tracks age: preserves color promotion hold");
    EXPECT(state.target_tracks[0].active && state.target_tracks[0].id == 31,
           "roi tracks age: preserves target tracks");
    EXPECT(state.next_target_track_id == 32,
           "roi tracks age: preserves next target track id");
}

static void test_target_tracks_match_skips_inactive_and_already_matched(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.500f;
    state.target_tracks[0].center_y_norm = 0.500f;
    state.target_tracks[1].active = false;
    state.target_tracks[1].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[1].center_x_norm = 0.501f;
    state.target_tracks[1].center_y_norm = 0.500f;
    state.target_tracks[2].active = true;
    state.target_tracks[2].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[2].center_x_norm = 0.530f;
    state.target_tracks[2].center_y_norm = 0.500f;

    bool matched_tracks[ANOMALY_MAX_TARGET_TRACKS];
    memset(matched_tracks, 0, sizeof(matched_tracks));
    matched_tracks[0] = true;

    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.algorithm = ANOMALY_ALGO_COLOR;
    obs.center_x_norm = 0.500f;
    obs.center_y_norm = 0.500f;

    EXPECT(anomaly_target_tracks_find_best_observation_match(&state, &obs, matched_tracks) == 2,
           "target tracks match: skip already matched and inactive tracks");
    EXPECT(anomaly_target_tracks_find_best_observation_match(NULL, &obs, matched_tracks) == -1,
           "target tracks match: reject null state");
    obs.valid = false;
    EXPECT(anomaly_target_tracks_find_best_observation_match(&state, &obs, matched_tracks) == -1,
           "target tracks match: reject invalid observations");
}

static void test_target_tracks_algorithm_mismatch_gate(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_THERMAL;
    state.target_tracks[0].center_x_norm = 0.580f;
    state.target_tracks[0].center_y_norm = 0.500f;
    state.target_tracks[1].active = true;
    state.target_tracks[1].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[1].center_x_norm = 0.600f;
    state.target_tracks[1].center_y_norm = 0.500f;

    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.algorithm = ANOMALY_ALGO_COLOR;
    obs.center_x_norm = 0.500f;
    obs.center_y_norm = 0.500f;

    EXPECT(anomaly_target_tracks_find_best_observation_match(&state, &obs, NULL) == 1,
           "target tracks match: algorithm mismatch gate skips farther cross-algorithm tracks");
}

static void test_target_tracks_allocate_prefers_first_inactive(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        state.target_tracks[i].active = true;
    }
    state.target_tracks[3].active = false;
    state.target_tracks[5].active = false;

    EXPECT(anomaly_target_tracks_allocate_slot(NULL) == -1,
           "target tracks allocate: reject null state");
    EXPECT(anomaly_target_tracks_allocate_slot(&state) == 3,
           "target tracks allocate: return first inactive slot");
}

static void test_target_tracks_allocate_full_uses_weakest_score(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        state.target_tracks[i].active = true;
        state.target_tracks[i].confidence = 0.70f;
        state.target_tracks[i].hit_count = 1;
    }
    state.target_tracks[0].confidence = 0.50f;
    state.target_tracks[0].hit_count = 0;
    state.target_tracks[2].confidence = 0.60f;
    state.target_tracks[2].hit_count = 4;
    state.target_tracks[4].confidence = 0.45f;
    state.target_tracks[4].hit_count = 1;

    EXPECT(anomaly_target_tracks_allocate_slot(&state) == 2,
           "target tracks allocate: full allocation picks weakest confidence-minus-hit score");
}

static void test_target_tracks_update_allocates_track_and_sets_lifecycle_fields(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 1;

    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.algorithm = ANOMALY_ALGO_COLOR;
    obs.center_x_norm = 0.42f;
    obs.center_y_norm = 0.57f;
    obs.half_w_norm = 0.012f;
    obs.half_h_norm = 0.018f;
    obs.support_radius_norm = 0.025f;
    obs.confidence = 0.31f;
    obs.publish_confirming = true;

    bool clear_intent = anomaly_target_tracks_update_from_observations(
            &state,
            &obs,
            1,
            ANOMALY_REG_HEALTH_HEALTHY,
            1.0f);

    const anomaly_target_track_t *track = &state.target_tracks[0];
    EXPECT(!clear_intent,
           "target tracks update: observation allocation should not request ROI clear");
    EXPECT(track->active && track->id == 1 && state.next_target_track_id == 2,
           "target tracks update: first valid observation allocates first track id");
    EXPECT(track->algorithm == ANOMALY_ALGO_COLOR,
           "target tracks update: allocation copies observation algorithm");
    EXPECT_NEAR(track->center_x_norm, 0.42f, 0.0001f,
                "target tracks update: allocation copies center x");
    EXPECT_NEAR(track->center_y_norm, 0.57f, 0.0001f,
                "target tracks update: allocation copies center y");
    EXPECT_NEAR(track->half_w_norm, 0.012f, 0.0001f,
                "target tracks update: allocation copies half width");
    EXPECT_NEAR(track->half_h_norm, 0.018f, 0.0001f,
                "target tracks update: allocation copies half height");
    EXPECT_NEAR(track->support_radius_norm, 0.025f, 0.0001f,
                "target tracks update: allocation copies support radius");
    EXPECT_NEAR(track->confidence, 0.53f, 0.0001f,
                "target tracks update: allocation applies hit confidence gain");
    EXPECT(track->publish_confirmed && track->hit_count == 1 && track->miss_count == 0,
           "target tracks update: allocation sets publish, hit, and miss lifecycle fields");
    EXPECT(track->hold_count == ANOMALY_ACC_HOLD_FRAMES &&
                   track->forced_revisit &&
                   track->fresh_observation,
           "target tracks update: allocation sets hold, forced revisit, and fresh flags");
    EXPECT_NEAR(track->last_registration_quality, 1.0f, 0.0001f,
                "target tracks update: allocation stores caller-provided registration quality");
}

static void test_target_tracks_update_matches_existing_and_preserves_publish_confirmation(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 7;
    state.target_tracks[2].active = true;
    state.target_tracks[2].id = 6;
    state.target_tracks[2].algorithm = ANOMALY_ALGO_THERMAL;
    state.target_tracks[2].center_x_norm = 0.50f;
    state.target_tracks[2].center_y_norm = 0.50f;
    state.target_tracks[2].support_radius_norm = 0.020f;
    state.target_tracks[2].confidence = 0.40f;
    state.target_tracks[2].publish_confirmed = true;
    state.target_tracks[2].hit_count = 3;

    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.algorithm = ANOMALY_ALGO_COLOR;
    obs.center_x_norm = 0.505f;
    obs.center_y_norm = 0.495f;
    obs.confidence = 0.25f;
    obs.publish_confirming = false;

    bool clear_intent = anomaly_target_tracks_update_from_observations(
            &state,
            &obs,
            1,
            ANOMALY_REG_HEALTH_SOFT_DEGRADED,
            0.60f);

    const anomaly_target_track_t *track = &state.target_tracks[2];
    EXPECT(!clear_intent,
           "target tracks update: matched observation should not request ROI clear");
    EXPECT(track->active && track->id == 6 && state.next_target_track_id == 7,
           "target tracks update: matching observation updates same slot without allocating id");
    EXPECT(track->publish_confirmed,
           "target tracks update: matching observation preserves existing publish confirmation");
    EXPECT(track->algorithm == ANOMALY_ALGO_COLOR && track->hit_count == 4,
           "target tracks update: matching observation refreshes algorithm and hit count");
    EXPECT_NEAR(track->confidence, 0.62f, 0.0001f,
                "target tracks update: matching observation gains confidence from prior track");
    EXPECT(track->forced_revisit && track->fresh_observation && track->hold_count == ANOMALY_ACC_HOLD_FRAMES,
           "target tracks update: matching observation refreshes lifecycle flags");
}

static void test_target_tracks_update_unmatched_ages_and_respects_registration_health(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 4;
    state.target_tracks[0].active = true;
    state.target_tracks[0].id = 3;
    state.target_tracks[0].center_x_norm = 0.10f;
    state.target_tracks[0].center_y_norm = 0.10f;
    state.target_tracks[0].confidence = 0.80f;
    state.target_tracks[0].miss_count = 1;
    state.target_tracks[0].hold_count = 5;
    state.target_tracks[0].fresh_observation = true;

    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.algorithm = ANOMALY_ALGO_COLOR;
    obs.center_x_norm = 0.80f;
    obs.center_y_norm = 0.80f;
    obs.confidence = 0.40f;

    bool clear_intent = anomaly_target_tracks_update_from_observations(
            &state,
            &obs,
            1,
            ANOMALY_REG_HEALTH_HEALTHY,
            1.0f);

    const anomaly_target_track_t *aged = &state.target_tracks[0];
    EXPECT(!clear_intent,
           "target tracks update: non-empty frame should not request ROI clear");
    EXPECT(aged->active && aged->miss_count == 2 && aged->hold_count == 4,
           "target tracks update: unmatched track ages miss and hold counts");
    EXPECT(!aged->fresh_observation && aged->forced_revisit,
           "target tracks update: healthy carried miss requests forced revisit");
    EXPECT_NEAR(aged->confidence, 0.58f, 0.0001f,
                "target tracks update: unmatched track decays confidence");

    anomaly_target_tracks_update_from_observations(
            &state,
            &obs,
            1,
            ANOMALY_REG_HEALTH_HARD_DEGRADED,
            0.25f);
    EXPECT(!state.target_tracks[0].active,
           "target tracks update: hard-degraded unmatched track clears");
}

static void test_target_tracks_update_empty_frame_returns_clear_intent_only_without_revisit_tracks(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));

    EXPECT(!anomaly_target_tracks_update_from_observations(
                   &state,
                   NULL,
                   0,
                   ANOMALY_REG_HEALTH_HEALTHY,
                   1.0f),
           "target tracks update: empty frame without prior target ids should not request ROI clear");

    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 2;
    state.target_tracks[0].active = true;
    state.target_tracks[0].confidence = 0.40f;
    state.target_tracks[0].hold_count = 3;
    state.target_tracks[0].forced_revisit = true;

    EXPECT(!anomaly_target_tracks_update_from_observations(
                   &state,
                   NULL,
                   0,
                   ANOMALY_REG_HEALTH_HEALTHY,
                   1.0f),
           "target tracks update: empty frame with revisit track should not request ROI clear");

    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 2;
    state.target_tracks[0].active = true;
    state.target_tracks[0].confidence = 0.04f;
    state.target_tracks[0].hold_count = 1;

    EXPECT(anomaly_target_tracks_update_from_observations(
                   &state,
                   NULL,
                   0,
                   ANOMALY_REG_HEALTH_INVALID,
                   0.0f),
           "target tracks update: empty frame requests ROI clear only after prior tracks drain");
}

typedef struct {
    bool valid;
    bool invert_ok;
    float dx;
    float dy;
} target_tracks_test_registration_t;

static bool target_tracks_test_registration_valid(const void *registration) {
    const target_tracks_test_registration_t *reg =
            (const target_tracks_test_registration_t *)registration;
    return reg != NULL && reg->valid;
}

static bool target_tracks_test_registration_invert(
        const void *registration,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    const target_tracks_test_registration_t *reg =
            (const target_tracks_test_registration_t *)registration;
    if (reg == NULL || !reg->invert_ok || out_x == NULL || out_y == NULL) return false;
    *out_x = x + reg->dx;
    *out_y = y + reg->dy;
    return true;
}

static void test_target_tracks_predict_null_and_default_noop(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].center_x_norm = 0.25f;
    state.target_tracks[0].center_y_norm = 0.40f;
    state.target_tracks[0].last_registration_quality = 0.30f;

    anomaly_target_tracks_predict_with_registration(NULL, NULL);
    anomaly_target_tracks_predict_with_registration(&state, NULL);

    anomaly_target_tracks_registration_prediction_t prediction;
    memset(&prediction, 0, sizeof(prediction));
    anomaly_target_tracks_predict_with_registration(&state, &prediction);

    EXPECT(state.target_tracks[0].active,
           "target tracks predict: null/default prediction should leave active tracks unchanged");
    EXPECT_NEAR(state.target_tracks[0].center_x_norm, 0.25f, 0.0001f,
                "target tracks predict: default prediction should preserve center x");
    EXPECT_NEAR(state.target_tracks[0].last_registration_quality, 0.30f, 0.0001f,
                "target tracks predict: default prediction should preserve registration quality");
}

static void test_target_tracks_predict_scene_discontinuity_clears(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 8;
    state.target_tracks[0].active = true;
    state.target_tracks[0].id = 7;

    anomaly_target_tracks_registration_prediction_t prediction;
    memset(&prediction, 0, sizeof(prediction));
    prediction.health = ANOMALY_REG_HEALTH_HEALTHY;
    prediction.scene_discontinuity = true;

    anomaly_target_tracks_predict_with_registration(&state, &prediction);

    EXPECT(!state.target_tracks[0].active,
           "target tracks predict: scene discontinuity should clear tracks");
    EXPECT(state.next_target_track_id == 1,
           "target tracks predict: scene discontinuity should reset next id");
}

static void test_target_tracks_predict_invalid_and_hard_health_clear(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.next_target_track_id = 4;
    state.target_tracks[0].active = true;

    anomaly_target_tracks_registration_prediction_t prediction;
    memset(&prediction, 0, sizeof(prediction));
    prediction.health = ANOMALY_REG_HEALTH_INVALID;

    anomaly_target_tracks_predict_with_registration(&state, &prediction);
    EXPECT(!state.target_tracks[0].active,
           "target tracks predict: invalid health should clear tracks");

    state.next_target_track_id = 5;
    state.target_tracks[1].active = true;
    prediction.health = ANOMALY_REG_HEALTH_HARD_DEGRADED;
    anomaly_target_tracks_predict_with_registration(&state, &prediction);
    EXPECT(!state.target_tracks[1].active,
           "target tracks predict: hard-degraded health should clear tracks");
    EXPECT(state.next_target_track_id == 1,
           "target tracks predict: hard-degraded health should reset next id");
}

static void test_target_tracks_decay_movement_evidence_clamps_windows(void) {
    anomaly_target_track_t track;
    memset(&track, 0, sizeof(track));
    track.movement_window_frames = 5;
    track.movement_valid_frames = 7;
    track.movement_independent_frames = 6;
    track.movement_parallax_frames = 8;
    track.movement_independent_score_sum = 10.0f;
    track.movement_confidence_sum = 5.0f;
    track.last_movement_independent_score = 0.75f;

    anomaly_target_tracks_decay_movement_evidence(&track);

    EXPECT(track.movement_window_frames == 4,
           "target tracks movement: decay decrements window");
    EXPECT(track.movement_valid_frames == 4,
           "target tracks movement: decay clamps valid frames to window");
    EXPECT(track.movement_independent_frames == 4,
           "target tracks movement: decay clamps independent frames to valid");
    EXPECT(track.movement_parallax_frames == 4,
           "target tracks movement: decay clamps parallax frames to valid");
    EXPECT_NEAR(track.movement_independent_score_sum, 9.0f, 0.0001f,
                "target tracks movement: decay damps independent score sum");
    EXPECT_NEAR(track.movement_confidence_sum, 4.5f, 0.0001f,
                "target tracks movement: decay damps confidence sum");
    EXPECT_NEAR(track.last_movement_independent_score, 0.0f, 0.0001f,
                "target tracks movement: decay clears last independent score");
}

static void test_target_tracks_update_movement_evidence_records_independent_tile(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].center_x_norm = 0.25f;
    state.target_tracks[0].center_y_norm = 0.40f;

    anomaly_debug_movement_t movement;
    memset(&movement, 0, sizeof(movement));
    movement.valid = true;
    movement.sample_count = 1;
    movement.tile_cols = ANOMALY_MOVEMENT_GRID_COLS;
    movement.tile_rows = ANOMALY_MOVEMENT_GRID_ROWS;
    movement.tiles[0].valid = true;
    movement.tiles[0].center_x_norm = 0.25f;
    movement.tiles[0].center_y_norm = 0.40f;
    movement.tiles[0].dx_px = 12.0f;
    movement.tiles[0].dy_px = 0.0f;
    movement.tiles[0].residual_px = 40.0f;
    movement.tiles[0].confidence = 0.80f;
    movement.tiles[0].layer_class = ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;

    anomaly_target_tracks_update_movement_evidence(&state, &movement);

    EXPECT(movement.aoi_query_count == 1,
           "target tracks movement: active track increments AOI query count");
    EXPECT(movement.aoi_valid_count == 1,
           "target tracks movement: valid nearby tile increments AOI valid count");
    EXPECT(movement.aoi_independent_count == 1,
           "target tracks movement: local outlier tile increments independent count");
    EXPECT(state.target_tracks[0].movement_window_frames == 1 &&
           state.target_tracks[0].movement_valid_frames == 1 &&
           state.target_tracks[0].movement_independent_frames == 1,
           "target tracks movement: independent tile updates movement windows");
    EXPECT_NEAR(state.target_tracks[0].movement_independent_score_sum, 0.825f, 0.0001f,
                "target tracks movement: independent score sum records tile score");
    EXPECT_NEAR(state.target_tracks[0].movement_confidence_sum, 0.80f, 0.0001f,
                "target tracks movement: confidence sum records tile confidence");
    EXPECT_NEAR(state.target_tracks[0].last_movement_dx_px, 12.0f, 0.0001f,
                "target tracks movement: last movement dx records tile dx");
    EXPECT_NEAR(state.target_tracks[0].last_movement_independent_score, 0.825f, 0.0001f,
                "target tracks movement: last independent score records tile score");
    EXPECT_NEAR(movement.aoi_independent_score_mean, 0.825f, 0.0001f,
                "target tracks movement: AOI independent score mean records tile score");
    EXPECT_NEAR(movement.aoi_confidence_mean, 0.80f, 0.0001f,
                "target tracks movement: AOI confidence mean records tile confidence");
}

static void test_target_tracks_predict_invalid_registration_leaves_tracks_unchanged(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].center_x_norm = 0.35f;
    state.target_tracks[0].center_y_norm = 0.45f;
    state.target_tracks[0].forced_revisit = false;
    state.target_tracks[0].last_registration_quality = 0.20f;
    target_tracks_test_registration_t reg = {
        .valid = false,
        .invert_ok = true,
        .dx = 0.10f,
        .dy = 0.10f,
    };
    anomaly_target_tracks_registration_prediction_t prediction = {
        .registration = &reg,
        .health = ANOMALY_REG_HEALTH_HEALTHY,
        .quality = 0.90f,
        .scene_discontinuity = false,
        .valid = target_tracks_test_registration_valid,
        .invert_point = target_tracks_test_registration_invert,
    };

    anomaly_target_tracks_predict_with_registration(&state, &prediction);

    EXPECT(state.target_tracks[0].active && !state.target_tracks[0].forced_revisit,
           "target tracks predict: invalid registration should leave flags unchanged");
    EXPECT_NEAR(state.target_tracks[0].center_x_norm, 0.35f, 0.0001f,
                "target tracks predict: invalid registration should preserve center x");
    EXPECT_NEAR(state.target_tracks[0].last_registration_quality, 0.20f, 0.0001f,
                "target tracks predict: invalid registration should preserve quality");
}

static void test_target_tracks_predict_failed_inverse_marks_forced_revisit(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].center_x_norm = 0.35f;
    state.target_tracks[0].center_y_norm = 0.45f;
    target_tracks_test_registration_t reg = {
        .valid = true,
        .invert_ok = false,
    };
    anomaly_target_tracks_registration_prediction_t prediction = {
        .registration = &reg,
        .health = ANOMALY_REG_HEALTH_SOFT_DEGRADED,
        .quality = 0.60f,
        .scene_discontinuity = false,
        .valid = target_tracks_test_registration_valid,
        .invert_point = target_tracks_test_registration_invert,
    };

    anomaly_target_tracks_predict_with_registration(&state, &prediction);

    EXPECT(state.target_tracks[0].forced_revisit,
           "target tracks predict: failed inverse should force revisit");
    EXPECT_NEAR(state.target_tracks[0].center_x_norm, 0.35f, 0.0001f,
                "target tracks predict: failed inverse should preserve center x");
}

static void test_target_tracks_predict_success_clamps_updates_quality_and_nonfresh_revisit(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].center_x_norm = 0.95f;
    state.target_tracks[0].center_y_norm = 0.05f;
    state.target_tracks[0].fresh_observation = true;
    state.target_tracks[1].active = true;
    state.target_tracks[1].center_x_norm = 0.40f;
    state.target_tracks[1].center_y_norm = 0.60f;
    state.target_tracks[1].fresh_observation = false;
    target_tracks_test_registration_t reg = {
        .valid = true,
        .invert_ok = true,
        .dx = 0.20f,
        .dy = -0.10f,
    };
    anomaly_target_tracks_registration_prediction_t prediction = {
        .registration = &reg,
        .health = ANOMALY_REG_HEALTH_HEALTHY,
        .quality = 0.84f,
        .scene_discontinuity = false,
        .valid = target_tracks_test_registration_valid,
        .invert_point = target_tracks_test_registration_invert,
    };

    anomaly_target_tracks_predict_with_registration(&state, &prediction);

    EXPECT_NEAR(state.target_tracks[0].center_x_norm, 1.0f, 0.0001f,
                "target tracks predict: successful inverse should clamp center x");
    EXPECT_NEAR(state.target_tracks[0].center_y_norm, 0.0f, 0.0001f,
                "target tracks predict: successful inverse should clamp center y");
    EXPECT_NEAR(state.target_tracks[0].last_registration_quality, 0.84f, 0.0001f,
                "target tracks predict: successful inverse should store caller quality");
    EXPECT(!state.target_tracks[0].forced_revisit,
           "target tracks predict: fresh successful inverse should preserve forced revisit false");
    EXPECT_NEAR(state.target_tracks[1].center_x_norm, 0.60f, 0.0001f,
                "target tracks predict: successful inverse should update non-fresh center x");
    EXPECT_NEAR(state.target_tracks[1].center_y_norm, 0.50f, 0.0001f,
                "target tracks predict: successful inverse should update non-fresh center y");
    EXPECT(state.target_tracks[1].forced_revisit,
           "target tracks predict: non-fresh successful inverse should force revisit");
}

static void test_appearance_detector_interface_contract(void) {
    anomaly_appearance_detector_config_t cfg = {
        .mode = ANOMALY_APPEARANCE_DETECTOR_THERMAL,
        .algorithm_mask = ANOMALY_ALGO_THERMAL,
        .score_threshold = 2.0f,
        .thermal_polarity = 1,
        .thermal_min_delta = 0.05f,
        .small_target_screen_fraction = 0.02f,
        .color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
    };
    anomaly_appearance_frame_context_t context = {0};
    anomaly_appearance_scratch_t scratch = {0};
    anomaly_appearance_detector_result_t result = {
        .mode = ANOMALY_APPEARANCE_DETECTOR_COLOR,
    };
    anomaly_appearance_detector_ops_t ops = {
        .mode = ANOMALY_APPEARANCE_DETECTOR_THERMAL,
    };

    EXPECT(ANOMALY_APPEARANCE_DETECTOR_THERMAL != ANOMALY_APPEARANCE_DETECTOR_COLOR,
           "appearance detector modes are distinct");
    EXPECT(sizeof(cfg) > 0 && sizeof(context) > 0 && sizeof(scratch) > 0 &&
                   sizeof(result) > 0 && sizeof(ops) > 0,
           "appearance detector interface structs are complete");
    EXPECT(cfg.algorithm_mask == ANOMALY_ALGO_THERMAL,
           "appearance detector config carries algorithm mask");
    EXPECT(result.mode == ANOMALY_APPEARANCE_DETECTOR_COLOR,
           "appearance detector result carries mode");
    EXPECT(ops.mode == ANOMALY_APPEARANCE_DETECTOR_THERMAL,
           "appearance detector ops carries mode");
}

static void test_thermal_detector_delta_and_radius_helpers(void) {
    const float bg_luma[2] = {10.0f, 20.0f};
    const float sg_luma[2] = {12.0f, 15.0f};
    const float delta_map[2] = {7.0f, 8.0f};
    const float score_map[2] = {3.0f, 4.0f};
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);

    EXPECT_NEAR(thermal_delta_from_maps(NULL, bg_luma, sg_luma, 0, false), 2.0f, 0.0001f,
                "thermal delta: white-hot derives luma minus background");
    EXPECT_NEAR(thermal_delta_from_maps(NULL, bg_luma, sg_luma, 1, true), 5.0f, 0.0001f,
                "thermal delta: black-hot derives background minus luma");
    EXPECT_NEAR(thermal_delta_from_maps(delta_map, bg_luma, sg_luma, 0, false), 7.0f, 0.0001f,
                "thermal delta: cached delta map wins");
    EXPECT_NEAR(thermal_blob_value_at(score_map, delta_map, bg_luma, sg_luma, 0, true, false, 6.0f),
                7.0f, 0.0001f,
                "thermal blob value: cached delta above floor is returned");
    EXPECT_NEAR(thermal_blob_value_at(score_map, delta_map, bg_luma, sg_luma, 0, true, false, 8.0f),
                -1.0f, 0.0001f,
                "thermal blob value: cached delta below floor is rejected");
    EXPECT_NEAR(thermal_blob_value_at(score_map, NULL, bg_luma, sg_luma, 1, false, false, 1.0f),
                4.0f, 0.0001f,
                "thermal blob value: spatial score map is used before background settles");

    EXPECT(thermal_radius_cells_for_real_px(12, 4, 1, 0) == 3,
           "thermal radius: exact sampled cell conversion");
    EXPECT(thermal_radius_cells_for_real_px(13, 4, 1, 0) == 4,
           "thermal radius: real pixels round up to cells");
    EXPECT(effective_thermal_window_radius_cells(4) == 3,
           "thermal radius: window radius matches reference step");
    EXPECT(effective_thermal_representative_radius_cells(8) == 3,
           "thermal radius: representative radius honors minimum cells");
    EXPECT(effective_thermal_growth_radius_cells(1) == 12,
           "thermal radius: growth radius honors max cells");
    EXPECT(effective_thermal_context_radius_cells(4) == 8,
           "thermal radius: context radius matches reference step");
    EXPECT(effective_thermal_parent_mass_radius_cells(4) == 10,
           "thermal radius: parent mass radius matches reference step");

    EXPECT_NEAR(effective_thermal_small_target_span_px(&cfg, 200, 100), 2.0f, 0.0001f,
                "thermal target span: default fraction respects minimum span");
    cfg.small_target_screen_fraction = 0.05f;
    EXPECT_NEAR(effective_thermal_small_target_span_px(&cfg, 200, 100), 11.18034f, 0.0001f,
                "thermal target span: config fraction scales by diagonal");
    EXPECT_NEAR(thermal_small_target_apparent_scale(&cfg, 5.0f, 200, 100), 1.0f, 0.0001f,
                "thermal apparent scale: target at limit keeps full scale");
    EXPECT(thermal_small_target_apparent_scale(&cfg, 40.0f, 200, 100) < 0.25f,
           "thermal apparent scale: oversized target is strongly discounted");
}

static void test_thermal_detector_probe_and_context_helpers(void) {
    const float luma[9] = {
        10.0f, 10.0f, 10.0f,
        10.0f, 20.0f, 10.0f,
        10.0f, 10.0f, 10.0f,
    };
    const float delta[25] = {
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 4.0f, 5.0f, 4.0f, 0.0f,
        0.0f, 5.0f, 10.0f, 5.0f, 0.0f,
        0.0f, 4.0f, 5.0f, 4.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };
    const float contrast_delta[4] = {5.0f, 4.0f, 5.0f, 4.0f};
    float abs_delta = 0.0f;
    float std = 0.0f;
    float score = 0.0f;
    float contrast_mean = -1.0f;
    float contrast_std = -1.0f;

    compute_thermal_spatial_probe_at_sample(
        luma, 3, 3, 1, 1, 4, false, &abs_delta, &std, &score);
    EXPECT_NEAR(abs_delta, 8.888889f, 0.0001f,
                "thermal probe: center white-hot delta matches local mean");
    EXPECT_NEAR(std, 3.142697f, 0.0001f,
                "thermal probe: local standard deviation matches neighborhood");
    EXPECT_NEAR(score, 2.828427f, 0.0001f,
                "thermal probe: score is delta over std");

    compute_thermal_spatial_probe_at_sample(
        NULL, 3, 3, 1, 1, 4, false, &abs_delta, &std, &score);
    EXPECT_NEAR(abs_delta, -1.0f, 0.0001f,
                "thermal probe: invalid input leaves sentinel delta");

    estimate_framewide_blob_contrast_stats(
        contrast_delta, NULL, NULL, 2, 2, true, false, 3.0f, &contrast_mean, &contrast_std);
    EXPECT_NEAR(contrast_mean, 0.5f, 0.0001f,
                "thermal contrast stats: adjacent contrast mean is stable");
    EXPECT_NEAR(contrast_std, 0.5f, 0.0001f,
                "thermal contrast stats: adjacent contrast std is stable");

    EXPECT_NEAR(thermal_candidate_seed_context_scale(
                    NULL, NULL, NULL, 5, 5, 2, 2, 4, false, false,
                    2.0f, 0.5f, 0.4f, 0.1f, 1.0f),
                1.0f, 0.0001f,
                "thermal context scale: invalid background is neutral");
    EXPECT_NEAR(thermal_candidate_seed_context_scale(
                    delta, NULL, NULL, 5, 5, 0, 0, 4, true, false,
                    2.0f, 0.5f, 0.4f, 0.1f, 1.0f),
                0.45f, 0.0001f,
                "thermal context scale: sub-threshold seed is rejected");
    EXPECT(thermal_candidate_seed_context_scale(
               delta, NULL, NULL, 5, 5, 2, 2, 4, true, false,
               2.0f, 0.5f, 0.4f, 0.1f, 1.0f) > 0.0f,
           "thermal context scale: valid seed returns a positive scale");

    EXPECT_NEAR(thermal_candidate_parent_mass_scale(
                    NULL, NULL, NULL, 5, 5, 2, 2, 4, false, false,
                    2.0f, 0.4f, 0.1f, 1.0f),
                1.0f, 0.0001f,
                "thermal parent scale: invalid background is neutral");
    EXPECT_NEAR(thermal_candidate_parent_mass_scale(
                    delta, NULL, NULL, 5, 5, 0, 0, 4, true, false,
                    2.0f, 0.4f, 0.1f, 1.0f),
                0.50f, 0.0001f,
                "thermal parent scale: sub-threshold seed is rejected");
    EXPECT(thermal_candidate_parent_mass_scale(
               delta, NULL, NULL, 5, 5, 2, 2, 4, true, false,
               2.0f, 0.4f, 0.1f, 1.0f) > 0.0f,
           "thermal parent scale: valid seed returns a positive scale");
}

static void test_thermal_detector_temporal_stats_helper(void) {
    const float bg[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    const float sg[4] = {5.0f, 4.0f, 5.0f, 4.0f};
    float delta[4] = {-1.0f, -1.0f, -1.0f, -1.0f};

    anomaly_thermal_temporal_stats_t stats =
        anomaly_thermal_compute_temporal_stats(
            delta, bg, sg, 2, 2, false, 3.0f, 0.5f);
    EXPECT(stats.valid, "thermal temporal stats: valid inputs produce stats");
    EXPECT(stats.positive_delta_count == 4,
           "thermal temporal stats: counts positive deltas");
    EXPECT_NEAR(stats.delta_mean, 4.5f, 0.0001f,
                "thermal temporal stats: positive delta mean matches legacy loop");
    EXPECT_NEAR(stats.delta_norm, 0.5f, 0.0001f,
                "thermal temporal stats: norm respects caller floor");
    EXPECT_NEAR(stats.frame_blob_contrast_mean, 0.5f, 0.0001f,
                "thermal temporal stats: contrast mean uses framewide helper");
    EXPECT_NEAR(stats.frame_blob_contrast_std, 0.5f, 0.0001f,
                "thermal temporal stats: contrast std uses framewide helper");
    EXPECT_NEAR(delta[0], 5.0f, 0.0001f,
                "thermal temporal stats: fills caller delta map");
    EXPECT_NEAR(delta[3], 4.0f, 0.0001f,
                "thermal temporal stats: fills full caller delta map");

    stats = anomaly_thermal_compute_temporal_stats(
        NULL, bg, sg, 2, 2, false, 3.0f, 0.5f);
    EXPECT(stats.valid, "thermal temporal stats: null delta map still computes");
    EXPECT_NEAR(stats.frame_blob_contrast_mean, 0.5f, 0.0001f,
                "thermal temporal stats: contrast fallback reads bg and current luma");

    const float black_hot_sg[4] = {-5.0f, -4.0f, -5.0f, -4.0f};
    stats = anomaly_thermal_compute_temporal_stats(
        NULL, bg, black_hot_sg, 2, 2, true, 3.0f, 0.5f);
    EXPECT_NEAR(stats.delta_mean, 4.5f, 0.0001f,
                "thermal temporal stats: black-hot delta direction is preserved");

    stats = anomaly_thermal_compute_temporal_stats(
        NULL, NULL, sg, 2, 2, false, 3.0f, 0.5f);
    EXPECT(!stats.valid, "thermal temporal stats: invalid inputs are rejected");
}

static void test_thermal_state_lifecycle_helpers(void) {
    anomaly_thermal_state_t state;
    anomaly_thermal_state_init(&state);
    EXPECT(state.bg_luma == NULL && state.thermal_target_persist == NULL,
           "thermal state init clears owned maps");

    const float frame0[4] = {10.0f, 20.0f, 30.0f, 40.0f};
    bool reset = anomaly_thermal_state_update_background(
        &state, frame0, 2, 2, true, false, false, NULL, 0.5f, 0.1f);
    EXPECT(reset, "thermal state background update reports initial seed reset");
    EXPECT(state.bg_luma != NULL && state.bg_sg_w == 2 && state.bg_sg_h == 2,
           "thermal state background update owns seeded dimensions");
    EXPECT(state.bg_warmup == 0,
           "thermal state background seed starts warmup at zero");
    EXPECT(!anomaly_thermal_state_bg_ready(&state, 2, 2, 1, true, false),
           "thermal state background is not ready before warmup");

    const float frame1[4] = {12.0f, 18.0f, 28.0f, 42.0f};
    const uint8_t refresh_mask[4] = {1u, 0u, 1u, 1u};
    reset = anomaly_thermal_state_update_background(
        &state, frame1, 2, 2, true, false, true, refresh_mask, 0.5f, 0.1f);
    EXPECT(!reset, "thermal state background update does not reset matching grid");
    EXPECT(state.bg_warmup == 1,
           "thermal state background update increments warmup");
    EXPECT_NEAR(state.bg_luma[0], 11.0f, 0.0001f,
                "thermal state background update applies cold alpha");
    EXPECT_NEAR(state.bg_luma[1], 20.0f, 0.0001f,
                "thermal state background update honors selective refresh mask");
    EXPECT(anomaly_thermal_state_bg_ready(&state, 2, 2, 1, true, false),
           "thermal state background ready helper checks warmup and dimensions");
    EXPECT(!anomaly_thermal_state_bg_ready(&state, 3, 2, 1, true, false),
           "thermal state background ready helper rejects mismatched current dimensions");
    EXPECT(anomaly_thermal_state_bg_ready(&state, 3, 2, 1, false, false),
           "thermal state background ready helper can ignore current dimensions");
    EXPECT(!anomaly_thermal_state_bg_ready(&state, 2, 2, 1, true, true),
           "thermal state background ready helper rejects scene discontinuity");

    EXPECT(anomaly_thermal_state_prepare_target_persist(&state, 4, 2, 2, false, 0.5f),
           "thermal state target persist allocates matching map");
    state.thermal_target_persist[0] = 0.8f;
    EXPECT(anomaly_thermal_state_prepare_target_persist(&state, 4, 2, 2, false, 0.5f),
           "thermal state target persist keeps existing matching map");
    EXPECT_NEAR(state.thermal_target_persist[0], 0.4f, 0.0001f,
                "thermal state target persist decays existing map");
    EXPECT(anomaly_thermal_state_prepare_target_persist(&state, 4, 2, 2, true, 0.5f),
           "thermal state target persist resets on scene discontinuity");
    EXPECT_NEAR(state.thermal_target_persist[0], 0.0f, 0.0001f,
                "thermal state target persist reset clears carried support");

    anomaly_thermal_state_reset(&state);
    EXPECT(state.bg_luma == NULL && state.bg_sg_w == 0 && state.bg_sg_h == 0 &&
           state.thermal_target_persist == NULL,
           "thermal state reset releases owned maps");
}

static void test_color_detector_histogram_and_rarity_helpers(void) {
    EXPECT(anomaly_color_quantize_uv_bin(-200.0f,
                                         ANOMALY_COLOR_DETECTOR_U_MIN,
                                         ANOMALY_COLOR_DETECTOR_U_MAX,
                                         ANOMALY_COLOR_U_BINS) == 0,
           "color quantize: low values clamp to first bin");
    EXPECT(anomaly_color_quantize_uv_bin(200.0f,
                                         ANOMALY_COLOR_DETECTOR_U_MIN,
                                         ANOMALY_COLOR_DETECTOR_U_MAX,
                                         ANOMALY_COLOR_U_BINS) == ANOMALY_COLOR_U_BINS - 1,
           "color quantize: high values clamp to last bin");
    EXPECT(anomaly_color_quantize_uv_bin(0.0f,
                                         ANOMALY_COLOR_DETECTOR_U_MIN,
                                         ANOMALY_COLOR_DETECTOR_U_MAX,
                                         ANOMALY_COLOR_U_BINS) == 6,
           "color quantize: midpoint maps to middle bin");
    EXPECT(anomaly_color_hist_key(2, 3) == 2 * ANOMALY_COLOR_V_BINS + 3,
           "color histogram key uses u-major layout");

    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));
    float color_u[4] = {-200.0f, 0.0f, 111.0f, 20.0f};
    float color_v[4] = {-200.0f, 0.0f, 111.0f, -20.0f};
    uint8_t u_bin[4] = {99u, 99u, 99u, 99u};
    uint8_t v_bin[4] = {99u, 99u, 99u, 99u};
    uint8_t valid[4] = {1u, 0u, 1u, 1u};
    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_u_bin = u_bin;
    roi.color_v_bin = v_bin;
    roi.color_valid_mask = valid;

    for (size_t i = 0; i < 4; i++) {
        anomaly_color_fill_uv_bins(&roi, i);
    }
    EXPECT(u_bin[0] == 0u && v_bin[0] == 0u,
           "color fill bins: low sample clamps both channels");
    EXPECT(u_bin[1] == 6u && v_bin[1] == 6u,
           "color fill bins: midpoint fills expected bins");
    EXPECT(u_bin[2] == 11u && v_bin[2] == 11u,
           "color fill bins: high sample fills last bins");
    EXPECT_NEAR(anomaly_color_sample_chroma_magnitude(&roi, 3),
                sqrtf((20.0f * 20.0f) + (-20.0f * -20.0f)),
                0.0001f,
                "color chroma magnitude: computes vector length");

    uint8_t *allocated_hist = NULL;
    size_t allocated_bins = 0;
    EXPECT(anomaly_color_hist_ensure_capacity(&allocated_hist, &allocated_bins),
           "color hist capacity: allocates histogram buffer");
    EXPECT(allocated_hist != NULL && allocated_bins == ANOMALY_COLOR_HIST_BINS,
           "color hist capacity: records allocated bin count");
    free(allocated_hist);

    uint8_t hist[ANOMALY_COLOR_HIST_BINS];
    memset(hist, 77, sizeof(hist));
    int valid_samples = anomaly_color_build_frame_histogram(&roi, 2, 2, hist);
    EXPECT(valid_samples == 3,
           "color frame histogram: counts only valid samples");
    EXPECT(hist[anomaly_color_hist_key(0, 0)] == 1u &&
           hist[anomaly_color_hist_key(11, 11)] == 1u,
           "color frame histogram: increments valid color bins");
    EXPECT(hist[anomaly_color_hist_key(6, 6)] == 0u,
           "color frame histogram: ignores invalid samples");

    enum { saturation_samples = 300 };
    uint8_t sat_u[saturation_samples];
    uint8_t sat_v[saturation_samples];
    uint8_t sat_valid[saturation_samples];
    memset(sat_u, 4, sizeof(sat_u));
    memset(sat_v, 5, sizeof(sat_v));
    memset(sat_valid, 1, sizeof(sat_valid));
    roi.color_u_bin = sat_u;
    roi.color_v_bin = sat_v;
    roi.color_valid_mask = sat_valid;
    memset(hist, 0, sizeof(hist));
    valid_samples = anomaly_color_build_frame_histogram(&roi, 20, 15, hist);
    EXPECT(valid_samples == saturation_samples,
           "color frame histogram: returns all valid samples before saturation");
    EXPECT(hist[anomaly_color_hist_key(4, 5)] == 255u,
           "color frame histogram: bin counts saturate at 255");

    uint8_t current_hist[ANOMALY_COLOR_HIST_BINS];
    uint8_t recent_hist[ANOMALY_COLOR_HIST_BINS];
    float family_lut[ANOMALY_COLOR_HIST_BINS];
    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    current_hist[anomaly_color_hist_key(2, 2)] = 4u;
    recent_hist[anomaly_color_hist_key(2, 2)] = 5u;
    EXPECT_NEAR(anomaly_color_score_hist_rarity(
                    current_hist, recent_hist, anomaly_color_hist_key(2, 2)),
                0.1f,
                0.0001f,
                "color rarity: direct rarity uses current and recent counts");

    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    current_hist[anomaly_color_hist_key(0, 0)] = 2u;
    current_hist[anomaly_color_hist_key(1, 0)] = 3u;
    current_hist[anomaly_color_hist_key(0, 1)] = 4u;
    current_hist[anomaly_color_hist_key(1, 1)] = 5u;
    recent_hist[anomaly_color_hist_key(1, 1)] = 6u;
    EXPECT_NEAR(anomaly_color_score_hist_family_rarity(current_hist, recent_hist, 0, 0),
                1.0f / 21.0f,
                0.0001f,
                "color family rarity: edge neighborhood excludes out-of-range bins");
    anomaly_color_build_family_rarity_lut(current_hist, recent_hist, family_lut);
    EXPECT_NEAR(family_lut[anomaly_color_hist_key(0, 0)],
                1.0f / 21.0f,
                0.0001f,
                "color family rarity LUT: matches direct family rarity");
    EXPECT_NEAR(anomaly_color_score_hist_family_rarity(current_hist, recent_hist, 11, 11),
                1.0f,
                0.0001f,
                "color family rarity: empty opposite edge is maximally rare");

    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    for (int v = 4; v <= 6; v++) {
        for (int u = 4; u <= 6; u++) {
            current_hist[anomaly_color_hist_key(u, v)] = 1u;
        }
    }
    recent_hist[anomaly_color_hist_key(5, 5)] = 8u;
    EXPECT_NEAR(anomaly_color_score_hist_family_rarity(current_hist, recent_hist, 5, 5),
                1.0f / 18.0f,
                0.0001f,
                "color family rarity: interior neighborhood includes all 3x3 bins");

    EXPECT_NEAR(anomaly_color_history_recent_scale_for_recovery(
                    ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES),
                0.0f,
                0.0001f,
                "color recovery scale: initial recovery suppresses recent history");
    EXPECT_NEAR(anomaly_color_history_recent_scale_for_recovery(
                    ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES / 2),
                0.5f,
                0.0001f,
                "color recovery scale: partial recovery ramps linearly");
    EXPECT_NEAR(anomaly_color_history_recent_scale_for_recovery(0),
                1.0f,
                0.0001f,
                "color recovery scale: completed recovery uses full recent history");

    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    current_hist[0] = 8u;
    current_hist[1] = 1u;
    recent_hist[0] = 200u;
    recent_hist[1] = 200u;
    anomaly_color_update_recent_histogram(
        recent_hist,
        current_hist,
        true,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 1u && recent_hist[1] == 1u,
           "color recent histogram: reset clears history before current contribution");

    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    current_hist[0] = 64u;
    current_hist[1] = 1u;
    recent_hist[0] = 100u;
    recent_hist[1] = 8u;
    anomaly_color_update_recent_histogram(
        recent_hist,
        current_hist,
        false,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 58u && recent_hist[1] == 5u,
           "color recent histogram: decays history and adds current contribution");

    memset(current_hist, 0, sizeof(current_hist));
    memset(recent_hist, 0, sizeof(recent_hist));
    current_hist[0] = 64u;
    recent_hist[0] = 100u;
    anomaly_color_update_recent_histogram(
        recent_hist,
        current_hist,
        false,
        ANOMALY_COLOR_HISTORY_RECOVERY_SEED_SHIFT);
    EXPECT(recent_hist[0] == 54u,
           "color recent histogram: recovery uses seed shift for current contribution");

    memset(current_hist, 255, sizeof(current_hist));
    memset(recent_hist, 255, sizeof(recent_hist));
    anomaly_color_update_recent_histogram(
        recent_hist,
        current_hist,
        false,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 158u,
           "color recent histogram: normal update preserves exact decay and contribution");
    anomaly_color_update_recent_histogram(
        recent_hist,
        current_hist,
        false,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 110u,
           "color recent histogram: repeated normal updates continue exact decay behavior");

    memset(current_hist, 255, sizeof(current_hist));
    memset(recent_hist, 255, sizeof(recent_hist));
    anomaly_color_update_recent_histogram(recent_hist, current_hist, false, 0);
    EXPECT(recent_hist[0] == 255u,
           "color recent histogram: combined history and current contribution saturates");

    memset(recent_hist, 7, sizeof(recent_hist));
    anomaly_color_update_recent_histogram(
        recent_hist,
        NULL,
        true,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 7u,
           "color recent histogram: NULL current histogram leaves recent history unchanged");
    anomaly_color_update_recent_histogram(
        NULL,
        current_hist,
        true,
        ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
    EXPECT(recent_hist[0] == 7u,
           "color recent histogram: NULL recent histogram is safe");

    EXPECT_NEAR(anomaly_color_default_fresh_distinctness_ratio(),
                1.28f,
                0.0001f,
                "color distinctness: default ratio stays fixed");
    EXPECT_NEAR(anomaly_color_clamp_fresh_distinctness_ratio(0.4f),
                1.10f,
                0.0001f,
                "color distinctness: clamp enforces minimum");
    EXPECT_NEAR(anomaly_color_clamp_fresh_distinctness_ratio(1.42f),
                1.42f,
                0.0001f,
                "color distinctness: clamp preserves in-range values");
    EXPECT_NEAR(anomaly_color_clamp_fresh_distinctness_ratio(3.0f),
                2.10f,
                0.0001f,
                "color distinctness: clamp enforces maximum");

    EXPECT_NEAR(anomaly_color_sample_chroma_magnitude(NULL, 0),
                0.0f,
                0.0001f,
                "color chroma magnitude: NULL roi is safe and neutral");
    EXPECT(!anomaly_color_hist_ensure_capacity(NULL, &allocated_bins),
           "color hist capacity: NULL buffer pointer is rejected");
    EXPECT(!anomaly_color_hist_ensure_capacity(&allocated_hist, NULL),
           "color hist capacity: NULL capacity pointer is rejected");
    EXPECT(anomaly_color_build_frame_histogram(NULL, 2, 2, hist) == 0,
           "color frame histogram: NULL roi returns no valid samples");
}

static void test_color_detector_candidate_scalar_helpers(void) {
    EXPECT_NEAR(anomaly_color_candidate_scene_commonness(5.0f, 3.0f, 10.0f, 6.0f),
                0.5f,
                0.0001f,
                "color candidate scene commonness: blends current and recent ratios");
    EXPECT_NEAR(anomaly_color_candidate_scene_commonness(10.0f, 10.0f, 0.0f, 0.0f),
                0.0f,
                0.0001f,
                "color candidate scene commonness: zero maxima are neutral");
    EXPECT_NEAR(anomaly_color_candidate_scene_commonness(4.0f, 4.0f, 1.0f, 1.0f),
                1.0f,
                0.0001f,
                "color candidate scene commonness: result clamps at one");

    EXPECT_NEAR(anomaly_color_small_target_priority_scale(0.6f, 0.2f, 1.0f, 0.8f),
                1.3664f,
                0.0001f,
                "color small-target priority: compact unique blobs get the legacy boost");
    EXPECT_NEAR(anomaly_color_small_target_priority_scale(0.9f, 0.6f, 1.0f, 0.45f),
                1.05f,
                0.0001f,
                "color small-target priority: medium compact blobs keep the base scale");
    EXPECT_NEAR(anomaly_color_small_target_priority_scale(1.05f, 0.9f, 1.0f, 0.8f),
                0.72f,
                0.0001f,
                "color small-target priority: near-limit blobs are discounted");
    EXPECT_NEAR(anomaly_color_small_target_priority_scale(1.4f, 1.3f, 1.0f, 0.1f),
                0.1148f,
                0.0001f,
                "color small-target priority: oversized non-unique blobs get the legacy penalty");
    EXPECT_NEAR(anomaly_color_small_target_priority_scale(1.4f, 1.3f, 0.0f, 0.1f),
                1.0f,
                0.0001f,
                "color small-target priority: invalid target span is neutral");
}

static void test_color_detector_rgba_sampling_helpers(void) {
    enum { W = 3, H = 2, STRIDE = 16 };
    uint8_t rgba[STRIDE * H];
    memset(rgba, 0xEE, sizeof(rgba));
    set_pixel(rgba, STRIDE, 0, 0, 100, 0, 0);
    set_pixel(rgba, STRIDE, 1, 0, 0, 100, 0);
    set_pixel(rgba, STRIDE, 2, 0, 0, 0, 100);
    set_pixel(rgba, STRIDE, 0, 1, 7, 8, 9);
    set_pixel(rgba, STRIDE, 1, 1, 10, 20, 30);
    set_pixel(rgba, STRIDE, 2, 1, 40, 50, 60);

    float luma = -1.0f;
    float u = -2.0f;
    float v = -3.0f;
    anomaly_color_sample_pixel_yuv(rgba, STRIDE, W, H, 1, 1, &luma, &u, &v);
    EXPECT_NEAR(luma, 18.5960f, 0.0001f,
                "color RGBA sampling: luma conversion matches legacy coefficients");
    EXPECT_NEAR(u, 5.8315f, 0.0001f,
                "color RGBA sampling: U conversion matches legacy coefficients");
    EXPECT_NEAR(v, -7.1501f, 0.0001f,
                "color RGBA sampling: V conversion matches legacy coefficients");

    anomaly_color_sample_pixel_yuv(rgba, STRIDE, W, H, 1, 1, NULL, NULL, NULL);

    luma = 99.0f;
    u = 88.0f;
    v = 77.0f;
    anomaly_color_sample_pixel_yuv(NULL, STRIDE, W, H, 1, 1, &luma, &u, &v);
    EXPECT_NEAR(luma, 0.0f, 0.0001f,
                "color RGBA sampling: NULL input zeros luma");
    EXPECT_NEAR(u, 0.0f, 0.0001f,
                "color RGBA sampling: NULL input zeros U");
    EXPECT_NEAR(v, 0.0f, 0.0001f,
                "color RGBA sampling: NULL input zeros V");

    luma = 99.0f;
    u = 88.0f;
    v = 77.0f;
    anomaly_color_sample_pixel_yuv(rgba, 8, W, H, 2, 0, &luma, &u, &v);
    EXPECT_NEAR(luma, 0.0f, 0.0001f,
                "color RGBA sampling: short stride guard zeros luma");
    EXPECT_NEAR(u, 0.0f, 0.0001f,
                "color RGBA sampling: short stride guard zeros U");
    EXPECT_NEAR(v, 0.0f, 0.0001f,
                "color RGBA sampling: short stride guard zeros V");

    luma = 99.0f;
    u = 88.0f;
    v = 77.0f;
    anomaly_color_sample_pixel_yuv(rgba, STRIDE, W, H, W, 0, &luma, &u, &v);
    EXPECT_NEAR(luma, 0.0f, 0.0001f,
                "color RGBA sampling: out-of-bounds x zeros luma");
    EXPECT_NEAR(u, 0.0f, 0.0001f,
                "color RGBA sampling: out-of-bounds x zeros U");
    EXPECT_NEAR(v, 0.0f, 0.0001f,
                "color RGBA sampling: out-of-bounds x zeros V");

    float cell_luma = -1.0f;
    float cell_u = -2.0f;
    float cell_v = -3.0f;
    anomaly_color_sample_cell(rgba, STRIDE, W, H, 1, 1, &cell_luma, &cell_u, &cell_v);
    EXPECT_NEAR(cell_luma, 18.5960f, 0.0001f,
                "color RGBA sampling: sample cell wrapper preserves luma");
    EXPECT_NEAR(cell_u, 5.8315f, 0.0001f,
                "color RGBA sampling: sample cell wrapper preserves U");
    EXPECT_NEAR(cell_v, -7.1501f, 0.0001f,
                "color RGBA sampling: sample cell wrapper preserves V");

    EXPECT(anomaly_color_dense_pixel_matches(10.0f, 20.0f, 100.0f,
                                             13.0f, 24.0f, 112.0f,
                                             5.0f, 12.0f),
           "color dense pixel match: inclusive chroma and luma thresholds match");
    EXPECT(!anomaly_color_dense_pixel_matches(10.0f, 20.0f, 100.0f,
                                              13.0f, 24.1f, 112.0f,
                                              5.0f, 12.0f),
           "color dense pixel match: rejects chroma above threshold");
    EXPECT(!anomaly_color_dense_pixel_matches(10.0f, 20.0f, 100.0f,
                                              13.0f, 24.0f, 112.1f,
                                              5.0f, 12.0f),
           "color dense pixel match: rejects luma above threshold");
}

static void test_color_detector_sampling_phase_helpers(void) {
    int phase_index = -1;
    int phase_x = -1;
    int phase_y = -1;
    anomaly_color_sampling_phase_for_frame(NULL, 0, &phase_index, &phase_x, &phase_y);
    EXPECT(phase_index == 0 && phase_x == 0 && phase_y == 0,
           "color sampling phase: NULL state and nonpositive step use phase zero");

    phase_index = -1;
    phase_x = -1;
    phase_y = -1;
    anomaly_state_t state;
    anomaly_state_init(&state);
    anomaly_color_sampling_phase_for_frame(&state, 5, &phase_index, &phase_x, &phase_y);
    EXPECT(phase_index == 0 && phase_x == 2 && phase_y == 2,
           "color sampling phase: step midpoint remains stable");

    anomaly_color_advance_sampling_phase(NULL, true, 5);
    anomaly_color_advance_sampling_phase(&state, false, 0);
    anomaly_state_cleanup(&state);
}

static void test_color_detector_sample_xy_helpers(void) {
    int sample_x = -1;
    int sample_y = -1;
    anomaly_color_compute_sample_xy(
        10, 20, 30, 45,
        1, 2, 5,
        2, 2,
        &sample_x, &sample_y);
    EXPECT(sample_x == 17 && sample_y == 32,
           "color sample xy: normal cell uses local phase");

    anomaly_color_compute_sample_xy(
        10, 20, 30, 45,
        0, 0, 5,
        99, 99,
        &sample_x, &sample_y);
    EXPECT(sample_x == 14 && sample_y == 24,
           "color sample xy: oversized phase clamps to cell edge");

    anomaly_color_compute_sample_xy(
        10, 20, 23, 31,
        2, 2, 5,
        2, 2,
        &sample_x, &sample_y);
    EXPECT(sample_x == 22 && sample_y == 30,
           "color sample xy: ROI edge cell clamps to ROI boundary");

    anomaly_color_compute_sample_xy(
        10, 20, 30, 45,
        1, 1, 5,
        -3, -4,
        &sample_x, &sample_y);
    EXPECT(sample_x == 15 && sample_y == 25,
           "color sample xy: negative phase clamps to cell origin");
}

static void test_color_detector_dense_seed_helpers(void) {
    float peak_map[9] = {
        0.0f, 0.0f, 0.0f,
        0.0f, 0.70f, 0.0f,
        0.0f, 0.0f, 0.0f,
    };
    EXPECT(anomaly_color_support_seed_is_local_peak(
               peak_map, 3, 3, 1, 1, 0, 0, 2, 2, 0.70f),
           "dense seed peak: isolated local max is accepted");
    peak_map[5] = 0.73f;
    EXPECT(!anomaly_color_support_seed_is_local_peak(
               peak_map, 3, 3, 1, 1, 0, 0, 2, 2, 0.70f),
           "dense seed peak: stronger neighbor is rejected");
    peak_map[5] = 0.0f;
    peak_map[3] = 0.70f;
    EXPECT(!anomaly_color_support_seed_is_local_peak(
               peak_map, 3, 3, 1, 1, 0, 0, 2, 2, 0.70f),
           "dense seed peak: equal predecessor tie is rejected");
    EXPECT(!anomaly_color_support_seed_is_local_peak(
               NULL, 3, 3, 1, 1, 0, 0, 2, 2, 0.70f),
           "dense seed peak: null map is rejected");

    float support_map[25];
    float contrast_map[25];
    memset(support_map, 0, sizeof(support_map));
    memset(contrast_map, 0, sizeof(contrast_map));
    support_map[2 * 5 + 2] = 0.8f;
    support_map[1 * 5 + 2] = 0.2f;
    support_map[2 * 5 + 3] = 0.4f;
    support_map[0 * 5 + 0] = 0.1f;
    contrast_map[2 * 5 + 2] = 0.7f;
    EXPECT_NEAR(anomaly_color_score_dense_seed(
                    NULL, NULL, 5, 5, 2, 2, 0, 0, 4, 4, 0.8f),
                0.0f, 0.0001f,
                "dense seed score: null support map returns zero");
    EXPECT_NEAR(anomaly_color_score_dense_seed(
                    support_map, NULL, 0, 5, 2, 2, 0, 0, 4, 4, 0.8f),
                0.0f, 0.0001f,
                "dense seed score: invalid dimensions return zero");
    EXPECT_NEAR(anomaly_color_score_dense_seed(
                    support_map, NULL, 5, 5, 2, 2, 0, 0, 4, 4, 0.8f),
                0.672278f, 0.0001f,
                "dense seed score: default contrast uses neutral weight");
    EXPECT_NEAR(anomaly_color_score_dense_seed(
                    support_map, contrast_map, 5, 5, 2, 2, 0, 0, 4, 4, 0.8f),
                0.587572f, 0.0001f,
                "dense seed score: deterministic 5x5 support and contrast map");

    float max_support = -1.0f;
    int min_sx = -2;
    int min_sy = -2;
    int max_sx = 99;
    int max_sy = 99;
    int count = -1;
    anomaly_color_find_seed_bounds_from_evidence(
        NULL, 4, 3, 0, 0, 3, 2, 0.6f,
        &max_support, &min_sx, &min_sy, &max_sx, &max_sy, &count);
    EXPECT_NEAR(max_support, 0.0f, 0.0001f,
                "seed bounds: null map clears max support");
    EXPECT(min_sx == 4 && min_sy == 3 && max_sx == -1 && max_sy == -1 && count == 0,
           "seed bounds: null map emits default empty bounds");

    const float evidence_map[12] = {
        0.1f, 0.2f, 0.3f, 0.4f,
        0.0f, 0.7f, 0.2f, 0.9f,
        0.6f, 0.1f, 0.8f, 0.0f,
    };
    anomaly_color_find_seed_bounds_from_evidence(
        evidence_map, 4, 3, 1, 0, 3, 2, 0.6f,
        &max_support, &min_sx, &min_sy, &max_sx, &max_sy, &count);
    EXPECT_NEAR(max_support, 0.9f, 0.0001f,
                "seed bounds: max support is tracked in active bounds");
    EXPECT(min_sx == 1 && min_sy == 1 && max_sx == 3 && max_sy == 2 && count == 3,
           "seed bounds: active map emits min/max/count for seeds above floor");

    anomaly_color_dense_seed_t seeds[ANOMALY_COLOR_DENSE_SEED_TOP_K];
    int seed_count = 0;
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 0, .sy = 0, .support = 0.5f, .score = 1.0f});
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 3, .sy = 0, .support = 0.6f, .score = 3.0f});
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 6, .sy = 0, .support = 0.7f, .score = 2.0f});
    EXPECT(seed_count == 3 &&
               seeds[0].score == 3.0f &&
               seeds[1].score == 2.0f &&
               seeds[2].score == 1.0f,
           "dense seed insert: seeds remain in descending score order");
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 4, .sy = 1, .support = 0.9f, .score = 4.0f});
    EXPECT(seed_count == 3 &&
               seeds[0].sx == 4 &&
               seeds[0].sy == 1 &&
               seeds[0].score == 4.0f,
           "dense seed insert: NMS replacement keeps the stronger nearby seed");
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 5, .sy = 1, .support = 0.9f, .score = 0.5f});
    EXPECT(seed_count == 3 && seeds[0].score == 4.0f,
           "dense seed insert: weaker nearby seed is ignored");
    anomaly_color_insert_dense_seed(
        seeds, &seed_count, 3,
        &(anomaly_color_dense_seed_t){.sx = 9, .sy = 0, .support = 0.9f, .score = 0.4f});
    EXPECT(seed_count == 3,
           "dense seed insert: max count cap is respected");

    memset(seeds, 0, sizeof(seeds));
    seed_count = 0;
    for (int i = 0; i < ANOMALY_COLOR_DENSE_SEED_TOP_K + 8; i++) {
        anomaly_color_insert_dense_seed(
            seeds, &seed_count, ANOMALY_COLOR_DENSE_SEED_TOP_K + 8,
            &(anomaly_color_dense_seed_t){
                .sx = i * (ANOMALY_COLOR_DENSE_SEED_NMS_RADIUS + 1),
                .sy = 20,
                .support = 1.0f,
                .score = (float)i,
            });
    }
    EXPECT(seed_count == ANOMALY_COLOR_DENSE_SEED_TOP_K,
           "dense seed insert: top-k cap is respected");
    EXPECT(seeds[0].score == (float)(ANOMALY_COLOR_DENSE_SEED_TOP_K + 7) &&
               seeds[ANOMALY_COLOR_DENSE_SEED_TOP_K - 1].score == 8.0f,
           "dense seed insert: top-k cap keeps highest-scoring seeds");
}

static void test_color_detector_frontend_mode_helpers(void) {
    anomaly_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));

    EXPECT(anomaly_color_effective_frontend_mode(NULL) == ANOMALY_COLOR_FRONTEND_LEGACY,
           "color frontend mode: NULL config resolves to legacy");

    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_LEGACY;
    EXPECT(anomaly_color_effective_frontend_mode(&cfg) == ANOMALY_COLOR_FRONTEND_LEGACY,
           "color frontend mode: legacy remains legacy");

    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    EXPECT(anomaly_color_effective_frontend_mode(&cfg) == ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
           "color frontend mode: fresh RGBA remains fresh RGBA");

    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_YUV;
    EXPECT(anomaly_color_effective_frontend_mode(&cfg) == ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
           "color frontend mode: fresh YUV falls back to fresh RGBA effective mode");

    EXPECT(anomaly_color_frontend_allows_pre_support_temporal_rescue(
               ANOMALY_COLOR_FRONTEND_LEGACY),
           "color frontend mode: legacy allows pre-support temporal rescue");
    EXPECT(!anomaly_color_frontend_allows_pre_support_temporal_rescue(
               ANOMALY_COLOR_FRONTEND_FRESH_RGBA),
           "color frontend mode: fresh RGBA blocks pre-support temporal rescue");
    EXPECT(!anomaly_color_frontend_allows_pre_support_temporal_rescue(
               ANOMALY_COLOR_FRONTEND_FRESH_YUV),
           "color frontend mode: fresh YUV blocks pre-support temporal rescue");

    EXPECT(!anomaly_color_frontend_uses_fresh_winner_gate(ANOMALY_COLOR_FRONTEND_LEGACY),
           "color frontend mode: legacy does not use fresh winner gate");
    EXPECT(anomaly_color_frontend_uses_fresh_winner_gate(ANOMALY_COLOR_FRONTEND_FRESH_RGBA),
           "color frontend mode: fresh RGBA uses fresh winner gate");
    EXPECT(anomaly_color_frontend_uses_fresh_winner_gate(ANOMALY_COLOR_FRONTEND_FRESH_YUV),
           "color frontend mode: fresh YUV uses fresh winner gate");
}

static void test_color_detector_candidate_temporal_boost_helper(void) {
    anomaly_state_t state;
    anomaly_config_t cfg;
    memset(&state, 0, sizeof(state));
    memset(&cfg, 0, sizeof(cfg));

    cfg.min_hits = 2;
    state.acc_active[0] = true;
    state.acc_hits[0] = 4;
    state.acc_hold[0] = ANOMALY_ACC_HOLD_FRAMES / 2;
    state.acc_cx[0] = 0.5f;
    state.acc_cy[0] = 0.5f;

    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_LEGACY, 11, 9, 5, 4),
                0.0f, 0.0001f,
                "candidate temporal boost: legacy frontend returns zero");
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    NULL, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 5, 4),
                0.0f, 0.0001f,
                "candidate temporal boost: null state returns zero");
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, NULL, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 5, 4),
                0.0f, 0.0001f,
                "candidate temporal boost: null config returns zero");

    state.acc_active[0] = false;
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 5, 4),
                0.0f, 0.0001f,
                "candidate temporal boost: inactive accumulator returns zero");
    state.acc_active[0] = true;
    state.acc_hits[0] = 1;
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 5, 4),
                0.0f, 0.0001f,
                "candidate temporal boost: insufficient hits return zero");
    state.acc_hits[0] = 4;

    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9,
                    5 + ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1,
                    4),
                0.0f, 0.0001f,
                "candidate temporal boost: out-of-radius candidate returns zero");

    float proximity = 1.0f -
        (1.0f / (float)(ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1));
    float hit_strength = (float)(state.acc_hits[0] - cfg.min_hits) / 3.0f;
    float hold_strength = (float)state.acc_hold[0] / (float)ANOMALY_ACC_HOLD_FRAMES;
    float expected = 0.18f + 0.52f * (0.55f * proximity +
                                      0.25f * hit_strength +
                                      0.20f * hold_strength);
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 6, 4),
                expected, 0.0001f,
                "candidate temporal boost: in-radius candidate uses exact formula");

    cfg.min_hits = 0;
    state.acc_hits[0] = 1;
    state.acc_hold[0] = 0;
    float expected_min_one = 0.18f + 0.52f * 0.55f;
    EXPECT_NEAR(anomaly_color_score_candidate_temporal_boost(
                    &state, &cfg, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 11, 9, 5, 4),
                expected_min_one, 0.0001f,
                "candidate temporal boost: config min_hits below one behaves as one");
}

static void test_color_detector_temporal_rescue_helper(void) {
    anomaly_state_t state;
    anomaly_config_t cfg;
    memset(&state, 0, sizeof(state));
    memset(&cfg, 0, sizeof(cfg));

    cfg.min_hits = 2;
    state.acc_active[0] = true;
    state.acc_hits[0] = 4;
    state.acc_hold[0] = ANOMALY_ACC_HOLD_FRAMES / 2;
    state.acc_cx[0] = 0.5f;
    state.acc_cy[0] = 0.5f;
    int support = ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN + 2;

    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    NULL, &cfg, 11, 9, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: null state returns zero");
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, NULL, 11, 9, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: null config returns zero");
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 5, 4, false, support),
                0.0f, 0.0001f,
                "temporal rescue: unsampled frame returns zero");

    state.acc_active[0] = false;
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: inactive accumulator returns zero");
    state.acc_active[0] = true;

    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 5, 4, true,
                    ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN - 1),
                0.0f, 0.0001f,
                "temporal rescue: local support below minimum returns zero");

    state.acc_hits[0] = 1;
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: insufficient hits return zero");
    state.acc_hits[0] = 4;

    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 0, 9, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: zero grid width returns zero");
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, -1, 5, 4, true, support),
                0.0f, 0.0001f,
                "temporal rescue: bad grid height returns zero");

    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9,
                    5 + ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1,
                    4,
                    true,
                    support),
                0.0f, 0.0001f,
                "temporal rescue: out-of-radius candidate returns zero");

    float proximity = 1.0f -
        (1.0f / (float)(ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1));
    float hit_strength = (float)(state.acc_hits[0] - cfg.min_hits) / 3.0f;
    float hold_strength = (float)state.acc_hold[0] / (float)ANOMALY_ACC_HOLD_FRAMES;
    float support_strength = (float)(support - ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) / 4.0f;
    float expected = ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_BASE +
        ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_RANGE *
            (0.45f * proximity +
             0.25f * hit_strength +
             0.15f * hold_strength +
             0.15f * support_strength);
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 6, 4, true, support),
                expected, 0.0001f,
                "temporal rescue: in-radius candidate uses exact formula");

    state.acc_cx[0] = 1.5f;
    state.acc_cy[0] = -0.5f;
    float clamped_expected = ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_BASE +
        ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_RANGE *
            (0.45f +
             0.25f * hit_strength +
             0.15f * hold_strength +
             0.15f * support_strength);
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 10, 0, true, support),
                clamped_expected, 0.0001f,
                "temporal rescue: prior grid position clamps to sample grid");

    cfg.min_hits = 0;
    state.acc_hits[0] = 1;
    state.acc_hold[0] = 0;
    state.acc_cx[0] = 0.5f;
    state.acc_cy[0] = 0.5f;
    float expected_min_one = ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_BASE +
        ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_RANGE * 0.45f;
    EXPECT_NEAR(anomaly_color_score_temporal_rescue(
                    &state, &cfg, 11, 9, 5, 4, true,
                    ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN),
                expected_min_one, 0.0001f,
                "temporal rescue: config min_hits below one behaves as one");
}

static void test_color_detector_contrast_rescue_helper(void) {
    enum { W = 7, H = 7, COUNT = W * H, CX = 3, CY = 3 };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    float color_u[COUNT];
    float color_v[COUNT];
    float color_luma[COUNT];
    uint8_t valid[COUNT];
    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_luma = color_luma;
    roi.color_valid_mask = valid;

    int support = ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN + 2;

    EXPECT_NEAR(anomaly_color_score_contrast_rescue(NULL, W, H, CX, CY, true, support),
                0.0f, 0.0001f,
                "contrast rescue: null roi returns zero");

    memset(valid, 1, sizeof(valid));
    for (int i = 0; i < COUNT; i++) {
        color_u[i] = 20.0f;
        color_v[i] = 0.0f;
        color_luma[i] = 100.0f;
    }
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, false, support),
                0.0f, 0.0001f,
                "contrast rescue: unsampled frame returns zero");
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(
                    &roi, W, H, CX, CY, true,
                    ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN - 1),
                0.0f, 0.0001f,
                "contrast rescue: local support below minimum returns zero");

    memset(valid, 0, sizeof(valid));
    valid[(size_t)CY * W + CX] = 1u;
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, true, support),
                0.0f, 0.0001f,
                "contrast rescue: insufficient ring neighbors return zero");

    memset(valid, 1, sizeof(valid));
    valid[(size_t)CY * W + CX] = 0u;
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, true, support),
                0.0f, 0.0001f,
                "contrast rescue: invalid center returns zero");

    memset(valid, 1, sizeof(valid));
    for (int i = 0; i < COUNT; i++) {
        color_u[i] = 8.0f;
        color_v[i] = 4.0f;
        color_luma[i] = 120.0f;
    }
    color_u[(size_t)CY * W + CX] = 3.0f;
    color_v[(size_t)CY * W + CX] = 4.0f;
    color_luma[(size_t)CY * W + CX] = 100.0f;
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, true, support),
                0.0f, 0.0001f,
                "contrast rescue: grayscale guard returns zero");

    for (int i = 0; i < COUNT; i++) {
        color_u[i] = 12.0f;
        color_v[i] = 0.0f;
        color_luma[i] = 100.0f;
    }
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, true, support),
                0.0f, 0.0001f,
                "contrast rescue: below contrast threshold returns zero");

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            size_t idx = (size_t)y * W + (size_t)x;
            color_u[idx] = 30.0f;
            color_v[idx] = 0.0f;
            color_luma[idx] = 114.0f;
        }
    }
    color_u[(size_t)CY * W + CX] = 20.0f;
    color_v[(size_t)CY * W + CX] = 0.0f;
    color_luma[(size_t)CY * W + CX] = 100.0f;

    float chroma_strength = anomaly_color_clampf(
        (10.0f - ANOMALY_COLOR_CONTRAST_CHROMA_SOFT) /
        (ANOMALY_COLOR_CONTRAST_CHROMA_HARD - ANOMALY_COLOR_CONTRAST_CHROMA_SOFT),
        0.0f,
        1.0f);
    float luma_strength = anomaly_color_clampf(
        (14.0f - ANOMALY_COLOR_CONTRAST_LUMA_SOFT) /
        (ANOMALY_COLOR_CONTRAST_LUMA_HARD - ANOMALY_COLOR_CONTRAST_LUMA_SOFT),
        0.0f,
        1.0f);
    float contrast_weight = 1.0f + 0.45f * (0.65f * chroma_strength + 0.35f * luma_strength);
    float contrast_strength = anomaly_color_clampf(
        (contrast_weight - ANOMALY_COLOR_CONTRAST_RESCUE_MIN) /
        (1.40f - ANOMALY_COLOR_CONTRAST_RESCUE_MIN),
        0.0f,
        1.0f);
    float support_strength = (float)(support - ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) / 4.0f;
    float expected = ANOMALY_COLOR_RESCUE_SCORE_BASE +
        ANOMALY_COLOR_RESCUE_SCORE_RANGE *
            (0.75f * contrast_strength + 0.25f * support_strength);
    EXPECT_NEAR(anomaly_color_score_contrast_rescue(&roi, W, H, CX, CY, true, support),
                expected, 0.0001f,
                "contrast rescue: exact nonzero formula matches");
}

static void test_color_detector_fresh_winner_gate_helper(void) {
    float max_span = -1.0f;
    float max_area = -1.0f;
    float min_rarity = -1.0f;
    float max_commonness = -1.0f;
    float small_target_span_px = 8.0f;
    int sample_step = 4;
    float small_target_cells = small_target_span_px / (float)sample_step;
    float expected_max_span =
        fmaxf(1.75f, small_target_cells * ANOMALY_FRESH_COLOR_WINNER_MAX_SPAN_SCALE);
    float expected_max_area =
        fmaxf(3.0f,
              small_target_cells * small_target_cells *
                  ANOMALY_FRESH_COLOR_WINNER_MAX_AREA_SCALE);

    anomaly_color_winner_gate_reason_t reason =
        anomaly_color_evaluate_fresh_winner_gate(
            small_target_span_px,
            sample_step,
            2.0f,
            1.5f,
            0.01f,
            0.10f,
            &max_span,
            &max_area,
            &min_rarity,
            &max_commonness);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_NONE,
           "fresh winner gate: clean candidate is not rejected");
    EXPECT_NEAR(max_span, expected_max_span, 0.0001f,
                "fresh winner gate: reports max span threshold");
    EXPECT_NEAR(max_area, expected_max_area, 0.0001f,
                "fresh winner gate: reports max area threshold");
    EXPECT_NEAR(min_rarity, ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY, 0.000001f,
                "fresh winner gate: reports minimum rarity threshold");
    EXPECT_NEAR(max_commonness, ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS, 0.0001f,
                "fresh winner gate: reports maximum commonness threshold");

    reason = anomaly_color_evaluate_fresh_winner_gate(
        small_target_span_px,
        sample_step,
        2.0f,
        expected_max_span + 0.01f,
        0.01f,
        0.10f,
        NULL,
        NULL,
        NULL,
        NULL);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_SIZE,
           "fresh winner gate: oversize candidate returns size rejection");

    reason = anomaly_color_evaluate_fresh_winner_gate(
        small_target_span_px,
        sample_step,
        expected_max_area + 0.01f,
        1.0f,
        ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY * 0.5f,
        ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS + 0.01f,
        NULL,
        NULL,
        NULL,
        NULL);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_SIZE_AND_COMMONNESS,
           "fresh winner gate: common oversize candidate returns combined rejection");

    reason = anomaly_color_evaluate_fresh_winner_gate(
        small_target_span_px,
        sample_step,
        2.0f,
        1.0f,
        ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY * 0.5f,
        ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS + 0.01f,
        NULL,
        NULL,
        NULL,
        NULL);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_NONE,
           "fresh winner gate: commonness alone preserves legacy no-reject behavior");

    reason = anomaly_color_evaluate_fresh_winner_gate(
        small_target_span_px,
        sample_step,
        expected_max_area + 0.01f,
        expected_max_span + 0.01f,
        ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY * 0.5f,
        ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS + 0.01f,
        NULL,
        NULL,
        NULL,
        NULL);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_SIZE_AND_COMMONNESS,
           "fresh winner gate: size plus commonness returns combined reason before size");

    max_span = -1.0f;
    max_area = -1.0f;
    reason = anomaly_color_evaluate_fresh_winner_gate(
        small_target_span_px,
        0,
        2.0f,
        1.0f,
        0.01f,
        0.10f,
        &max_span,
        &max_area,
        NULL,
        NULL);
    EXPECT(reason == ANOMALY_COLOR_WINNER_GATE_NONE,
           "fresh winner gate: non-positive sample step still evaluates clean candidate");
    EXPECT_NEAR(max_span,
                fmaxf(1.75f,
                      small_target_span_px * ANOMALY_FRESH_COLOR_WINNER_MAX_SPAN_SCALE),
                0.0001f,
                "fresh winner gate: sample step fallback uses one for span");
    EXPECT_NEAR(max_area,
                fmaxf(3.0f,
                      small_target_span_px * small_target_span_px *
                          ANOMALY_FRESH_COLOR_WINNER_MAX_AREA_SCALE),
                0.0001f,
                "fresh winner gate: sample step fallback uses one for area");
}

static int count_marked_cells(const uint8_t *visited, int w, int h) {
    int count = 0;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            if (visited[(size_t)y * (size_t)w + (size_t)x] != 0u) count++;
        }
    }
    return count;
}

static void test_color_detector_suppress_seed_region_helper(void) {
    enum { W = 8, H = 6, COUNT = W * H };
    uint8_t visited[COUNT];

    anomaly_color_suppress_seed_region(NULL, W, H, 0, 0, W - 1, H - 1, 3, 2, 3, 2);

    memset(visited, 7, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, 0, H, 0, 0, W - 1, H - 1, 3, 2, 3, 2);
    anomaly_color_suppress_seed_region(visited, W, -1, 0, 0, W - 1, H - 1, 3, 2, 3, 2);
    for (int i = 0; i < COUNT; i++) {
        EXPECT(visited[i] == 7u,
               "color suppress seed region: invalid dimensions leave visited unchanged");
    }

    memset(visited, 0, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, W, H, 0, 0, W - 1, H - 1, 3, 2, 3, 2);
    EXPECT(count_marked_cells(visited, W, H) == 9,
           "color suppress seed region: one-cell padding marks 3x3 area");
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            uint8_t expected = (x >= 2 && x <= 4 && y >= 1 && y <= 3) ? 1u : 0u;
            EXPECT(visited[(size_t)y * W + (size_t)x] == expected,
                   "color suppress seed region: one-cell padding marks exact cells");
        }
    }

    memset(visited, 0, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, W, H, 3, 2, 3, 2, 3, 2, 3, 2);
    EXPECT(count_marked_cells(visited, W, H) == 1,
           "color suppress seed region: scan bounds clip padded area");
    EXPECT(visited[(size_t)2 * W + 3] == 1u,
           "color suppress seed region: scan bounds preserve requested cell");

    memset(visited, 0, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, W, H, -5, -5, 99, 99, 0, 0, 0, 0);
    EXPECT(count_marked_cells(visited, W, H) == 4,
           "color suppress seed region: grid bounds clip padded corner");
    EXPECT(visited[(size_t)0 * W + 0] == 1u &&
           visited[(size_t)0 * W + 1] == 1u &&
           visited[(size_t)1 * W + 0] == 1u &&
           visited[(size_t)1 * W + 1] == 1u,
           "color suppress seed region: grid bounds mark corner cells");

    memset(visited, 0, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, W, H, 5, 0, 6, H - 1, 0, 0, 0, 0);
    EXPECT(count_marked_cells(visited, W, H) == 0,
           "color suppress seed region: inverted final bounds no-op");

    memset(visited, 0, sizeof(visited));
    anomaly_color_suppress_seed_region(visited, W, H, 0, 0, W - 1, H - 1, 1, 1, 2, 2);
    int positions[] = {
        0 * W + 0, 0 * W + 1, 0 * W + 2, 0 * W + 3,
        1 * W + 0, 1 * W + 1, 1 * W + 2, 1 * W + 3,
        2 * W + 0, 2 * W + 1, 2 * W + 2, 2 * W + 3,
        3 * W + 0, 3 * W + 1, 3 * W + 2, 3 * W + 3,
    };
    EXPECT(count_marked_cells(visited, W, H) == 16,
           "color suppress seed region: row-major marking count matches padded bbox");
    for (size_t i = 0; i < sizeof(positions) / sizeof(positions[0]); i++) {
        EXPECT(visited[positions[i]] == 1u,
               "color suppress seed region: row-major expected position is marked");
    }
}

static void test_color_detector_support_patch_radius_helper(void) {
    EXPECT(anomaly_color_support_patch_radius(10.0f, 2) == 3,
           "color support patch radius: normal calculation rounds expected radius");
    EXPECT(anomaly_color_support_patch_radius(4.0f, 0) == 2,
           "color support patch radius: nonpositive sample step falls back to one");
    EXPECT(anomaly_color_support_patch_radius(0.2f, 4) == 1,
           "color support patch radius: minimum fmax input yields radius one");
    EXPECT(anomaly_color_support_patch_radius(100.0f, 1) == 4,
           "color support patch radius: radius is capped at four");
    EXPECT(anomaly_color_support_patch_radius(5.9f, 2) == 1,
           "color support patch radius: value below .5 rounds down");
    EXPECT(anomaly_color_support_patch_radius(6.0f, 2) == 2,
           "color support patch radius: value at .5 rounds up");
}

static void test_color_detector_support_scalar_formula_helpers(void) {
    float distinctness_ratio =
        anomaly_color_support_distinctness_ratio(0.90f, 0.20f);
    EXPECT_NEAR(distinctness_ratio, 0.90f / fmaxf(0.20f, 0.08f), 0.0001f,
                "color support scalar helpers: distinctness ratio matches formula");

    float distinctness_ratio_clamped_ring =
        anomaly_color_support_distinctness_ratio(0.40f, 0.02f);
    EXPECT_NEAR(distinctness_ratio_clamped_ring, 0.40f / 0.08f, 0.0001f,
                "color support scalar helpers: distinctness ratio preserves ring floor");

    float gate =
        anomaly_color_support_distinctness_gate(
            distinctness_ratio,
            ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT);
    float gate_expected = anomaly_color_clampf(
        (distinctness_ratio - (ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT - 0.18f)) / 0.62f,
        0.0f,
        1.0f);
    EXPECT_NEAR(gate, gate_expected, 0.0001f,
                "color support scalar helpers: distinctness gate matches formula");
    EXPECT_NEAR(
        anomaly_color_support_distinctness_gate(0.10f, ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX),
        0.0f,
        0.0001f,
        "color support scalar helpers: distinctness gate clamps low");
    EXPECT_NEAR(
        anomaly_color_support_distinctness_gate(5.0f, ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MIN),
        1.0f,
        0.0001f,
        "color support scalar helpers: distinctness gate clamps high");

    float compact_prominence =
        anomaly_color_support_compact_prominence(0.90f, 0.20f, gate);
    float compact_prominence_expected = anomaly_color_clampf(
        0.65f * ((0.90f - 0.20f - 0.20f) / 1.20f) +
        0.35f * gate,
        0.0f,
        1.0f);
    EXPECT_NEAR(compact_prominence, compact_prominence_expected, 0.0001f,
                "color support scalar helpers: compact prominence matches formula");

    float core_share = anomaly_color_support_core_share(0.70f, 0.90f);
    EXPECT_NEAR(core_share, anomaly_color_clampf(0.70f / fmaxf(0.90f, 0.001f), 0.0f, 1.0f),
                0.0001f,
                "color support scalar helpers: core share matches formula");
    EXPECT_NEAR(anomaly_color_support_core_share(0.70f, 0.0f), 1.0f, 0.0001f,
                "color support scalar helpers: core share preserves local peak floor and clamp");

    EXPECT_NEAR(
        anomaly_color_support_seed_floor(
            ANOMALY_COLOR_FRONTEND_LEGACY,
            ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX),
        0.55f,
        0.0001f,
        "color support scalar helpers: legacy seed floor stays fixed");
    EXPECT_NEAR(
        anomaly_color_support_seed_floor(
            ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
            ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX),
        0.71f,
        0.0001f,
           "color support scalar helpers: fresh seed floor preserves max nudge");
}

static void test_color_detector_reviewed_fp_cluster_helper(void) {
    EXPECT(anomaly_color_candidate_near_reviewed_fp_cluster(
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_X,
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_Y),
           "reviewed FP cluster: exact center is rejected");
    EXPECT(anomaly_color_candidate_near_reviewed_fp_cluster(
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_X + ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_RADIUS,
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_Y),
           "reviewed FP cluster: radius boundary is rejected");
    EXPECT(!anomaly_color_candidate_near_reviewed_fp_cluster(
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_X + ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_RADIUS + 0.001f,
                   ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_Y),
           "reviewed FP cluster: outside radius is allowed");
    EXPECT(!anomaly_color_candidate_near_reviewed_fp_cluster(-0.10f, -0.10f),
           "reviewed FP cluster: unrelated negative coordinates are allowed");
}

static anomaly_target_observation_t color_track_support_test_obs(
        float cx,
        float cy,
        float support_radius) {
    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));
    obs.valid = true;
    obs.center_x_norm = cx;
    obs.center_y_norm = cy;
    obs.support_radius_norm = support_radius;
    return obs;
}

static void test_color_detector_track_support_match_invalid_defaults(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.50f, 0.50f, 0.040f);

    float dist = 9.0f;
    float gate = 9.0f;
    EXPECT(anomaly_color_find_best_track_support_match(NULL, &obs, &dist, &gate) == -1,
           "color track support match: NULL state is rejected");
    EXPECT_NEAR(dist, 0.0f, 0.0001f,
                "color track support match: NULL state clears distance output");
    EXPECT_NEAR(gate, 0.0f, 0.0001f,
                "color track support match: NULL state clears gate output");

    dist = 9.0f;
    gate = 9.0f;
    EXPECT(anomaly_color_find_best_track_support_match(&state, NULL, &dist, &gate) == -1,
           "color track support match: NULL observation is rejected");
    EXPECT_NEAR(dist, 0.0f, 0.0001f,
                "color track support match: NULL observation clears distance output");
    EXPECT_NEAR(gate, 0.0f, 0.0001f,
                "color track support match: NULL observation clears gate output");

    obs.valid = false;
    dist = 9.0f;
    gate = 9.0f;
    EXPECT(anomaly_color_find_best_track_support_match(&state, &obs, &dist, &gate) == -1,
           "color track support match: invalid observation is rejected");
    EXPECT_NEAR(dist, 0.0f, 0.0001f,
                "color track support match: invalid observation clears distance output");
    EXPECT_NEAR(gate, 0.0f, 0.0001f,
                "color track support match: invalid observation clears gate output");
}

static void test_color_detector_track_support_match_filters_and_gate(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.50f, 0.50f, 0.040f);

    state.target_tracks[0].active = false;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;

    state.target_tracks[1].active = true;
    state.target_tracks[1].algorithm = ANOMALY_ALGO_THERMAL;
    state.target_tracks[1].center_x_norm = 0.50f;
    state.target_tracks[1].center_y_norm = 0.50f;

    float dist = 9.0f;
    float gate = 9.0f;
    EXPECT(anomaly_color_find_best_track_support_match(&state, &obs, &dist, &gate) == -1,
           "color track support match: skips inactive and non-color/persist tracks");
    EXPECT_NEAR(dist, 0.0f, 0.0001f,
                "color track support match: no acceptable track leaves distance default");
    EXPECT_NEAR(gate, 0.0f, 0.0001f,
                "color track support match: no acceptable track leaves gate default");

    memset(&state, 0, sizeof(state));
    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.10f;
    state.target_tracks[0].center_y_norm = 0.10f;
    state.target_tracks[0].support_radius_norm = 0.050f;
    dist = 9.0f;
    gate = 9.0f;
    EXPECT(anomaly_color_find_best_track_support_match(&state, &obs, &dist, &gate) == -1,
           "color track support match: outside gate is rejected");
    EXPECT_NEAR(dist, 0.0f, 0.0001f,
                "color track support match: outside gate leaves distance default");
    EXPECT_NEAR(gate, 0.0f, 0.0001f,
                "color track support match: outside gate leaves gate default");
}

static void test_color_detector_track_support_match_closest_in_gate(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.50f, 0.50f, 0.040f);

    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.40f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.060f;

    state.target_tracks[1].active = true;
    state.target_tracks[1].algorithm = ANOMALY_ALGO_PERSIST;
    state.target_tracks[1].center_x_norm = 0.53f;
    state.target_tracks[1].center_y_norm = 0.50f;
    state.target_tracks[1].support_radius_norm = 0.080f;

    float dist = 0.0f;
    float gate = 0.0f;
    int match = anomaly_color_find_best_track_support_match(&state, &obs, &dist, &gate);
    EXPECT(match == 1,
           "color track support match: closest in-gate color/persist track wins");
    EXPECT_NEAR(dist, 0.030f, 0.0001f,
                "color track support match: returns Euclidean distance");
    EXPECT_NEAR(gate, ANOMALY_TARGET_MATCH_GATE + 0.25f * 0.080f, 0.0001f,
                "color track support match: returns exact support-expanded gate");
}

static void test_color_detector_track_persistence_bonus_rejects_invalid_inputs(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.50f, 0.50f, 0.040f);

    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(NULL, &obs, 0.80f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: NULL state returns zero");
    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(&state, NULL, 0.80f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: NULL observation returns zero");
    obs.valid = false;
    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(&state, &obs, 0.80f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: invalid observation returns zero");
    obs.valid = true;
    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(&state, &obs, 0.54f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: low registration returns zero");
    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(&state, &obs, 0.80f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: no match returns zero");

    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.040f;
    state.target_tracks[0].confidence = 1.0f;
    state.target_tracks[0].last_registration_quality = 0.54f;
    EXPECT_NEAR(anomaly_color_score_track_persistence_bonus(&state, &obs, 0.80f, 1.0f),
                0.0f, 0.0001f,
                "color track persistence bonus: low track lock returns zero");
}

static void test_color_detector_track_persistence_bonus_base_formula(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.50f, 0.50f, 0.040f);

    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_PERSIST;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.040f;
    state.target_tracks[0].confidence = 1.50f;
    state.target_tracks[0].last_registration_quality = 0.90f;

    float registration_quality = 0.775f;
    float lock_factor = anomaly_color_clampf((registration_quality - 0.55f) / 0.45f, 0.0f, 1.0f);
    float expected = (0.16f + 0.34f * 1.0f) * 1.0f * lock_factor;
    EXPECT_NEAR(
        anomaly_color_score_track_persistence_bonus(&state, &obs, registration_quality, 1.0f),
        expected,
        0.0001f,
        "color track persistence bonus: base formula clamps confidence and track lock");
}

static void test_color_detector_track_persistence_bonus_disagreement_formula(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_target_observation_t obs =
        color_track_support_test_obs(0.56f, 0.50f, 0.050f);

    state.target_tracks[0].active = true;
    state.target_tracks[0].algorithm = ANOMALY_ALGO_COLOR;
    state.target_tracks[0].center_x_norm = 0.50f;
    state.target_tracks[0].center_y_norm = 0.50f;
    state.target_tracks[0].support_radius_norm = 0.050f;
    state.target_tracks[0].confidence = 0.80f;
    state.target_tracks[0].last_registration_quality = 1.0f;

    float dist = 0.060f;
    float gate = ANOMALY_TARGET_MATCH_GATE + 0.25f * 0.050f;
    float closeness = anomaly_color_clampf(1.0f - dist / gate, 0.0f, 1.0f);
    float base_expected =
        (0.16f + 0.34f * closeness) *
        0.80f *
        1.0f;
    float relative_offset = anomaly_color_clampf((dist / 0.050f) - 0.18f, 0.0f, 1.0f);
    float disagreement_expected =
        0.20f *
        relative_offset *
        0.75f *
        1.0f;
    EXPECT_NEAR(
        anomaly_color_score_track_persistence_bonus(&state, &obs, 1.0f, 0.75f),
        base_expected + disagreement_expected,
        0.0001f,
        "color track persistence bonus: disagreement branch preserves exact formula");
}

static void test_color_detector_support_patch_score_helper(void) {
    anomaly_color_support_score_t legacy = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_LEGACY,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        0.80f,
        0.60f,
        0.20f,
        0.0f,
        0.50f,
        0.80f,
        4,
        1.0f);
    float legacy_expected =
        0.55f * 0.80f +
        0.75f * 0.60f +
        0.90f * 0.50f -
        0.30f * 0.20f;
    EXPECT_NEAR(legacy.support, legacy_expected, 0.0001f,
                "color support score: legacy formula matches exact weights");
    EXPECT_NEAR(legacy.seed_floor, 0.55f, 0.0001f,
                "color support score: legacy seed floor stays fixed");
    EXPECT_NEAR(legacy.compact_prominence, 0.0f, 0.0001f,
                "color support score: legacy compact prominence remains zero");
    EXPECT_NEAR(legacy.core_share, 0.0f, 0.0001f,
                "color support score: legacy core share remains zero");

    anomaly_color_support_score_t fresh = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        0.70f,
        0.50f,
        0.0f,
        0.20f,
        0.40f,
        0.90f,
        12,
        0.60f);
    float distinctness_ratio = 0.90f / fmaxf(0.20f, 0.08f);
    float distinctness_gate = anomaly_color_clampf(
        (distinctness_ratio - (ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT - 0.18f)) / 0.62f,
        0.0f,
        1.0f);
    float compact_prominence = anomaly_color_clampf(
        0.65f * ((0.90f - 0.20f - 0.20f) / 1.20f) +
        0.35f * distinctness_gate,
        0.0f,
        1.0f);
    float core_share = anomaly_color_clampf(0.70f / fmaxf(0.90f, 0.001f), 0.0f, 1.0f);
    float fresh_expected =
        0.70f * (0.45f + 0.75f * compact_prominence) +
        0.50f * (0.12f + 0.28f * core_share) +
        0.30f * 0.40f -
        0.75f * 0.20f;
    fresh_expected *= 0.45f + 0.75f * distinctness_gate;
    fresh_expected *= anomaly_color_clampf(0.55f + 0.45f * 0.60f, 0.35f, 1.20f) *
                      anomaly_color_clampf(0.92f + 0.18f * distinctness_gate, 0.80f, 1.12f);
    EXPECT_NEAR(fresh.support, fresh_expected, 0.0001f,
                "color support score: fresh formula and distinctness gate match exact weights");
    EXPECT_NEAR(fresh.compact_prominence, compact_prominence, 0.0001f,
                "color support score: fresh compact prominence is reported");
    EXPECT_NEAR(fresh.core_share, core_share, 0.0001f,
                "color support score: fresh core share is reported");

    anomaly_color_support_score_t nudged = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX,
        0.70f,
        0.50f,
        0.0f,
        0.20f,
        0.40f,
        0.90f,
        12,
        0.60f);
    EXPECT_NEAR(nudged.seed_floor, 0.71f, 0.0001f,
                "color support score: fresh distinctness ratio nudges seed floor");

    anomaly_color_support_score_t compact_floor = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        0.80f,
        0.10f,
        0.0f,
        0.10f,
        0.10f,
        0.80f,
        1,
        -10.0f);
    float compact_expected_prominence = anomaly_color_clampf(
        0.65f * ((0.80f - 0.10f - 0.20f) / 1.20f) + 0.35f,
        0.0f,
        1.0f);
    float compact_expected_floor =
        fmaxf(0.55f, ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_SEED_FLOOR) +
        0.06f * anomaly_color_clampf((compact_expected_prominence - 0.45f) / 0.35f, 0.0f, 1.0f) +
        0.04f * anomaly_color_clampf((0.75f - 0.10f) / 0.45f, 0.0f, 1.0f);
    EXPECT_NEAR(compact_floor.support, compact_expected_floor, 0.0001f,
                "color support score: compact peak floor promotes support");

    anomaly_color_support_score_t contrast_low = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_LEGACY,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        1.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        1.0f,
        1,
        -10.0f);
    anomaly_color_support_score_t contrast_high = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_LEGACY,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        1.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        1.0f,
        1,
        10.0f);
    EXPECT_NEAR(contrast_low.support, 0.55f * 0.35f, 0.0001f,
                "color support score: contrast weight clamps low");
    EXPECT_NEAR(contrast_high.support, 0.55f * 1.20f, 0.0001f,
                "color support score: contrast weight clamps high");

    anomaly_color_support_score_t clamped = anomaly_color_score_support_patch(
        ANOMALY_COLOR_FRONTEND_LEGACY,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT,
        10.0f,
        10.0f,
        0.0f,
        0.0f,
        10.0f,
        10.0f,
        20,
        10.0f);
    EXPECT_NEAR(clamped.support, 4.0f, 0.0001f,
                "color support score: final support clamps to four");
}

static void test_color_detector_local_uv_support_helper(void) {
    enum { W = 3, H = 3, COUNT = W * H };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    uint8_t u_bin[COUNT] = {
        4u, 5u, 6u,
        5u, 5u, 5u,
        6u, 5u, 4u,
    };
    uint8_t v_bin[COUNT] = {
        6u, 5u, 4u,
        5u, 5u, 5u,
        4u, 5u, 6u,
    };
    uint8_t valid[COUNT];
    memset(valid, 1, sizeof(valid));

    roi.color_u_bin = u_bin;
    roi.color_v_bin = v_bin;
    roi.color_valid_mask = valid;

    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 1) == 9,
           "color local UV support: counts matching 3x3 neighborhood");

    u_bin[(size_t)0 * W + 1] = 9u;
    v_bin[(size_t)1 * W + 0] = 9u;
    valid[(size_t)1 * W + 1] = 0u;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 0, 0, 5, 5, 1) == 1,
           "color local UV support: clamps edge neighborhood");

    EXPECT(anomaly_color_local_uv_support_count(NULL, W, H, 1, 1, 5, 5, 1) == 0,
           "color local UV support: NULL roi returns zero");
    EXPECT(anomaly_color_local_uv_support_count(&roi, 0, H, 1, 1, 5, 5, 1) == 0,
           "color local UV support: invalid dimensions return zero");
    roi.color_valid_mask = NULL;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 1) == 0,
           "color local UV support: missing valid mask returns zero");
    roi.color_valid_mask = valid;
    roi.color_u_bin = NULL;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 1) == 0,
           "color local UV support: missing U bins returns zero");
    roi.color_u_bin = u_bin;
    roi.color_v_bin = NULL;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 1) == 0,
           "color local UV support: missing V bins returns zero");

    roi.color_v_bin = v_bin;
    valid[(size_t)1 * W + 1] = 1u;
    u_bin[(size_t)1 * W + 1] = 5u;
    v_bin[(size_t)1 * W + 1] = 5u;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 0) == 1,
           "color local UV support: radius zero counts matching center only");
    valid[(size_t)1 * W + 1] = 0u;
    EXPECT(anomaly_color_local_uv_support_count(&roi, W, H, 1, 1, 5, 5, 0) == 0,
           "color local UV support: radius zero ignores invalid center");
}

static void test_color_detector_blob_neighbor_similarity_helper(void) {
    enum { COUNT = 4 };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    float color_u[COUNT] = {10.0f, 10.0f, 18.0f, 90.0f};
    float color_v[COUNT] = {20.0f, 20.0f, 26.0f, -90.0f};
    float color_luma[COUNT] = {100.0f, 100.0f, 112.0f, 180.0f};
    uint8_t u_bin[COUNT] = {5u, 5u, 7u, 11u};
    uint8_t v_bin[COUNT] = {6u, 6u, 7u, 0u};
    uint8_t valid[COUNT] = {1u, 1u, 1u, 0u};

    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_luma = color_luma;
    roi.color_u_bin = u_bin;
    roi.color_v_bin = v_bin;
    roi.color_valid_mask = valid;

    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(NULL, 0, 1),
                0.0f,
                0.0001f,
                "color blob neighbor similarity: NULL roi returns zero");

    anomaly_roi_state_t missing_arrays = roi;
    missing_arrays.color_luma = NULL;
    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&missing_arrays, 0, 1),
                0.0f,
                0.0001f,
                "color blob neighbor similarity: missing arrays return zero");

    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&roi, 0, 3),
                0.0f,
                0.0001f,
                "color blob neighbor similarity: invalid rhs mask returns zero");
    valid[0] = 0u;
    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&roi, 0, 1),
                0.0f,
                0.0001f,
                "color blob neighbor similarity: invalid lhs mask returns zero");
    valid[0] = 1u;

    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&roi, 0, 1),
                1.0f,
                0.0001f,
                "color blob neighbor similarity: identical bins and luma return one");

    float chroma_delta = sqrtf((8.0f * 8.0f) + (6.0f * 6.0f));
    float expected =
        0.50f * 0.10f +
        0.35f * ((56.0f - chroma_delta) / 56.0f) +
        0.15f * ((32.0f - 12.0f) / 32.0f);
    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&roi, 0, 2),
                expected,
                0.0001f,
                "color blob neighbor similarity: weighted deltas match legacy formula");

    EXPECT_NEAR(anomaly_color_blob_neighbor_similarity(&roi, 2, 3),
                0.0f,
                0.0001f,
                "color blob neighbor similarity: invalid neighbor clamps to zero");
}

static void test_color_detector_blob_cohesion_weights_helper(void) {
    enum { W = 3, H = 3, COUNT = W * H, CENTER = 4 };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    uint8_t valid[COUNT];
    float weights[COUNT];
    float color_u[COUNT];
    float color_v[COUNT];
    float color_luma[COUNT];
    uint8_t u_bin[COUNT];
    uint8_t v_bin[COUNT];

    memset(valid, 1, sizeof(valid));
    for (int i = 0; i < COUNT; i++) {
        weights[i] = -3.0f;
        color_u[i] = 0.0f;
        color_v[i] = 0.0f;
        color_luma[i] = 100.0f;
        u_bin[i] = 5u;
        v_bin[i] = 5u;
    }
    roi.color_valid_mask = valid;
    roi.color_contrast_weight = weights;
    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_luma = color_luma;
    roi.color_u_bin = u_bin;
    roi.color_v_bin = v_bin;

    anomaly_color_compute_blob_cohesion_weights(NULL, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, 0, H);
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, -1);
    for (int i = 0; i < COUNT; i++) {
        EXPECT_NEAR(weights[i], -3.0f, 0.0001f,
                    "color blob cohesion: invalid dimensions are no-op");
    }

    roi.color_valid_mask = NULL;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    for (int i = 0; i < COUNT; i++) {
        EXPECT_NEAR(weights[i], -3.0f, 0.0001f,
                    "color blob cohesion: missing valid mask is no-op");
    }
    roi.color_valid_mask = valid;
    roi.color_contrast_weight = NULL;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    roi.color_contrast_weight = weights;

    valid[0] = 1u;
    valid[1] = 0u;
    valid[2] = 1u;
    weights[0] = weights[1] = weights[2] = -3.0f;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_LEGACY, 3, 1);
    EXPECT_NEAR(weights[0], 1.0f, 0.0001f,
                "color blob cohesion: legacy valid sample weight is one");
    EXPECT_NEAR(weights[1], 0.0f, 0.0001f,
                "color blob cohesion: legacy invalid sample weight is zero");
    EXPECT_NEAR(weights[2], 1.0f, 0.0001f,
                "color blob cohesion: legacy resumes valid sample weight after invalid");

    memset(valid, 0, sizeof(valid));
    for (int i = 0; i < COUNT; i++) weights[i] = -3.0f;
    valid[CENTER] = 0u;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    EXPECT_NEAR(weights[CENTER], 0.0f, 0.0001f,
                "color blob cohesion: fresh invalid sample weight is zero");

    memset(valid, 1, sizeof(valid));
    for (int i = 0; i < COUNT; i++) {
        weights[i] = -3.0f;
        color_u[i] = 120.0f;
        color_v[i] = -120.0f;
        color_luma[i] = 220.0f;
        u_bin[i] = 20u;
        v_bin[i] = 0u;
    }
    color_u[CENTER] = 0.0f;
    color_v[CENTER] = 0.0f;
    color_luma[CENTER] = 0.0f;
    u_bin[CENTER] = 0u;
    v_bin[CENTER] = 20u;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    EXPECT_NEAR(weights[CENTER], 1.0f, 0.0001f,
                "color blob cohesion: fresh no-similarity fallback weight is one");

    memset(valid, 0, sizeof(valid));
    for (int i = 0; i < COUNT; i++) {
        weights[i] = -3.0f;
        color_u[i] = 80.0f;
        color_v[i] = -80.0f;
        color_luma[i] = 180.0f;
        u_bin[i] = 12u;
        v_bin[i] = 1u;
    }
    valid[CENTER] = 1u;
    valid[1] = 1u;
    valid[3] = 1u;
    color_u[CENTER] = color_u[1] = color_u[3] = 10.0f;
    color_v[CENTER] = color_v[1] = color_v[3] = 20.0f;
    color_luma[CENTER] = color_luma[1] = color_luma[3] = 100.0f;
    u_bin[CENTER] = u_bin[1] = u_bin[3] = 5u;
    v_bin[CENTER] = v_bin[1] = v_bin[3] = 6u;
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    float expected_cohesion = 0.80f * 1.0f + 0.20f * 0.25f;
    float expected_weight = 0.80f + 0.35f * expected_cohesion;
    EXPECT_NEAR(weights[CENTER], expected_weight, 0.0001f,
                "color blob cohesion: fresh coherent neighbors preserve exact formula");

    for (int i = 0; i < COUNT; i++) {
        valid[i] = 1u;
        weights[i] = -3.0f;
        color_u[i] = 10.0f;
        color_v[i] = 20.0f;
        color_luma[i] = 100.0f;
        u_bin[i] = 5u;
        v_bin[i] = 6u;
    }
    anomaly_color_compute_blob_cohesion_weights(&roi, ANOMALY_COLOR_FRONTEND_FRESH_RGBA, W, H);
    float expected_full_cohesion = 0.80f * 1.0f + 0.20f * 1.0f;
    float expected_full_weight = 0.80f + 0.35f * expected_full_cohesion;
    EXPECT_NEAR(weights[CENTER], expected_full_weight, 0.0001f,
                "color blob cohesion: fresh coherent full neighborhood preserves formula");
}

static void test_color_detector_candidate_bbox_norm_helper(void) {
    float left = -1.0f;
    float top = -1.0f;
    float right = -1.0f;
    float bottom = -1.0f;

    anomaly_color_candidate_bbox_norm(
        10,
        20,
        4,
        2,
        3,
        4,
        5,
        100.0f,
        200.0f,
        &left,
        &top,
        &right,
        &bottom);
    EXPECT_NEAR(left, 16.0f / 100.0f, 0.0001f,
                "color bbox norm: left preserves legacy expansion math");
    EXPECT_NEAR(top, 30.0f / 200.0f, 0.0001f,
                "color bbox norm: top preserves legacy expansion math");
    EXPECT_NEAR(right, 32.0f / 100.0f, 0.0001f,
                "color bbox norm: right preserves legacy expansion math");
    EXPECT_NEAR(bottom, 46.0f / 200.0f, 0.0001f,
                "color bbox norm: bottom preserves legacy expansion math");

    anomaly_color_candidate_bbox_norm(
        0,
        0,
        1,
        -2,
        -1,
        200,
        250,
        100.0f,
        200.0f,
        &left,
        &top,
        &right,
        &bottom);
    EXPECT_NEAR(left, 0.0f, 0.0001f,
                "color bbox norm: left clamps to frame");
    EXPECT_NEAR(top, 0.0f, 0.0001f,
                "color bbox norm: top clamps to frame");
    EXPECT_NEAR(right, 1.0f, 0.0001f,
                "color bbox norm: right clamps to frame");
    EXPECT_NEAR(bottom, 1.0f, 0.0001f,
                "color bbox norm: bottom clamps to frame");

    left = top = right = bottom = 0.5f;
    anomaly_color_candidate_bbox_norm(
        10,
        20,
        4,
        5,
        3,
        4,
        5,
        100.0f,
        200.0f,
        &left,
        &top,
        &right,
        &bottom);
    EXPECT_NEAR(left, 0.0f, 0.0001f,
                "color bbox norm: invalid bbox zeros left");
    EXPECT_NEAR(top, 0.0f, 0.0001f,
                "color bbox norm: invalid bbox zeros top");
    EXPECT_NEAR(right, 0.0f, 0.0001f,
                "color bbox norm: invalid bbox zeros right");
    EXPECT_NEAR(bottom, 0.0f, 0.0001f,
                "color bbox norm: invalid bbox zeros bottom");

    anomaly_color_candidate_bbox_norm(
        0,
        0,
        4,
        0,
        0,
        0,
        0,
        100.0f,
        100.0f,
        NULL,
        NULL,
        NULL,
        NULL);
}

static void test_color_detector_contrast_helpers(void) {
    enum { W = 5, H = 5, COUNT = W * H };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    float color_u[COUNT];
    float color_v[COUNT];
    float color_luma[COUNT];
    uint8_t color_valid[COUNT];
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            size_t idx = (size_t)y * W + (size_t)x;
            int dx = abs(x - 2);
            int dy = abs(y - 2);
            int chebyshev = dx > dy ? dx : dy;
            if (chebyshev == 0) {
                color_u[idx] = 10.0f;
                color_v[idx] = 20.0f;
                color_luma[idx] = 100.0f;
            } else if (chebyshev == 1) {
                color_u[idx] = 7.0f;
                color_v[idx] = 16.0f;
                color_luma[idx] = 92.0f;
            } else {
                color_u[idx] = 4.0f;
                color_v[idx] = 12.0f;
                color_luma[idx] = 80.0f;
            }
            color_valid[idx] = 1u;
        }
    }
    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_luma = color_luma;
    roi.color_valid_mask = color_valid;

    float avg_chroma = -1.0f;
    float avg_luma = -1.0f;
    int neighbor_count = -1;
    anomaly_color_compute_local_contrast(
        &roi, W, H, 2, 2, &avg_chroma, &avg_luma, &neighbor_count);
    EXPECT(neighbor_count == 8,
           "color local contrast: counts valid 3x3 neighbors");
    EXPECT_NEAR(avg_chroma, 5.0f, 0.0001f,
                "color local contrast: averages chroma distance");
    EXPECT_NEAR(avg_luma, 8.0f, 0.0001f,
                "color local contrast: averages luma distance");

    avg_chroma = -1.0f;
    avg_luma = -1.0f;
    neighbor_count = -1;
    anomaly_color_compute_ring_contrast(
        &roi, W, H, 2, 2, 1, 2, &avg_chroma, &avg_luma, &neighbor_count);
    EXPECT(neighbor_count == 16,
           "color ring contrast: counts outer Chebyshev ring neighbors");
    EXPECT_NEAR(avg_chroma, 10.0f, 0.0001f,
                "color ring contrast: averages ring chroma distance");
    EXPECT_NEAR(avg_luma, 20.0f, 0.0001f,
                "color ring contrast: averages ring luma distance");

    avg_chroma = 123.0f;
    avg_luma = 456.0f;
    neighbor_count = 7;
    anomaly_color_compute_local_contrast(
        NULL, W, H, 2, 2, &avg_chroma, &avg_luma, &neighbor_count);
    EXPECT_NEAR(avg_chroma, 0.0f, 0.0001f,
                "color local contrast: invalid input defaults chroma output");
    EXPECT_NEAR(avg_luma, 0.0f, 0.0001f,
                "color local contrast: invalid input defaults luma output");
    EXPECT(neighbor_count == 0,
           "color local contrast: invalid input defaults neighbor count");

    avg_chroma = 123.0f;
    avg_luma = 456.0f;
    neighbor_count = 7;
    anomaly_color_compute_ring_contrast(
        &roi, W, H, 2, 2, 2, 2, &avg_chroma, &avg_luma, &neighbor_count);
    EXPECT_NEAR(avg_chroma, 0.0f, 0.0001f,
                "color ring contrast: invalid radius defaults chroma output");
    EXPECT_NEAR(avg_luma, 0.0f, 0.0001f,
                "color ring contrast: invalid radius defaults luma output");
    EXPECT(neighbor_count == 0,
           "color ring contrast: invalid radius defaults neighbor count");

    color_valid[(size_t)2 * W + 2] = 0u;
    avg_chroma = 123.0f;
    avg_luma = 456.0f;
    neighbor_count = 7;
    anomaly_color_compute_ring_contrast(
        &roi, W, H, 2, 2, 1, 2, &avg_chroma, &avg_luma, &neighbor_count);
    EXPECT_NEAR(avg_chroma, 0.0f, 0.0001f,
                "color ring contrast: invalid center defaults chroma output");
    EXPECT_NEAR(avg_luma, 0.0f, 0.0001f,
                "color ring contrast: invalid center defaults luma output");
    EXPECT(neighbor_count == 0,
           "color ring contrast: invalid center defaults neighbor count");
}

static void test_color_detector_target_telemetry(void) {
    enum { W = 5, H = 5, COUNT = W * H };
    anomaly_roi_state_t roi;
    memset(&roi, 0, sizeof(roi));

    float color_u[COUNT];
    float color_v[COUNT];
    float color_luma[COUNT];
    uint8_t color_u_bin[COUNT];
    uint8_t color_v_bin[COUNT];
    uint8_t color_valid[COUNT];
    uint8_t refresh_mask[COUNT];
    uint8_t fresh_mask[COUNT];
    memset(refresh_mask, 0, sizeof(refresh_mask));
    memset(fresh_mask, 0, sizeof(fresh_mask));

    for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
            size_t idx = (size_t)y * W + (size_t)x;
            int dx = abs(x - 2);
            int dy = abs(y - 2);
            int chebyshev = dx > dy ? dx : dy;
            if (chebyshev <= 1) {
                color_u[idx] = 10.0f;
                color_v[idx] = 20.0f;
                color_luma[idx] = 100.0f;
            } else {
                color_u[idx] = 4.0f;
                color_v[idx] = 8.0f;
                color_luma[idx] = 70.0f;
            }
            color_u_bin[idx] = 5u;
            color_v_bin[idx] = 5u;
            color_valid[idx] = 1u;
        }
    }
    color_u_bin[(size_t)1 * W + 1] = 9u;

    roi.color_u = color_u;
    roi.color_v = color_v;
    roi.color_luma = color_luma;
    roi.color_u_bin = color_u_bin;
    roi.color_v_bin = color_v_bin;
    roi.color_valid_mask = color_valid;
    roi.fresh_mask = fresh_mask;

    anomaly_color_target_telemetry_t telemetry;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, true, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 9,
           "color target telemetry: counts valid patch cells");
    EXPECT(telemetry.coherent_patch_cell_count == 8,
           "color target telemetry: counts +/-1 coherent UV-bin cells");
    EXPECT(telemetry.coherent_patch_fresh_cell_count == 8,
           "color target telemetry: full refresh makes coherent cells fresh");
    EXPECT(telemetry.coherent_patch_multicell,
           "color target telemetry: reports multicell coherent patch");
    EXPECT_NEAR(telemetry.patch_mean_u, 10.0f, 0.0001f,
                "color target telemetry: patch mean U");
    EXPECT_NEAR(telemetry.patch_mean_v, 20.0f, 0.0001f,
                "color target telemetry: patch mean V");
    EXPECT_NEAR(telemetry.patch_mean_luma, 100.0f, 0.0001f,
                "color target telemetry: patch mean luma");
    EXPECT(telemetry.ring_neighbor_count == 16,
           "color target telemetry: counts valid outer ring cells");
    EXPECT_NEAR(telemetry.ring_mean_u, 4.0f, 0.0001f,
                "color target telemetry: ring mean U");
    EXPECT_NEAR(telemetry.ring_mean_v, 8.0f, 0.0001f,
                "color target telemetry: ring mean V");
    EXPECT_NEAR(telemetry.ring_mean_luma, 70.0f, 0.0001f,
                "color target telemetry: ring mean luma");
    EXPECT_NEAR(telemetry.ring_chroma_contrast, sqrtf(180.0f), 0.0001f,
                "color target telemetry: ring chroma contrast uses patch-ring means");
    EXPECT_NEAR(telemetry.ring_luma_contrast, 30.0f, 0.0001f,
                "color target telemetry: ring luma contrast uses patch-ring means");

    refresh_mask[(size_t)2 * W + 2] = 1u;
    refresh_mask[(size_t)2 * W + 3] = 1u;
    refresh_mask[(size_t)1 * W + 1] = 1u;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, false, refresh_mask, &telemetry);
    EXPECT(telemetry.coherent_patch_fresh_cell_count == 2,
           "color target telemetry: explicit refresh mask counts coherent fresh cells only");

    fresh_mask[(size_t)1 * W + 2] = 1u;
    fresh_mask[(size_t)2 * W + 2] = 1u;
    fresh_mask[(size_t)3 * W + 3] = 1u;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, false, NULL, &telemetry);
    EXPECT(telemetry.coherent_patch_fresh_cell_count == 3,
           "color target telemetry: falls back to roi fresh mask");

    float one_u[1] = {3.0f};
    float one_v[1] = {4.0f};
    float one_luma[1] = {5.0f};
    uint8_t one_bin[1] = {5u};
    uint8_t one_valid[1] = {1u};
    anomaly_roi_state_t one_roi;
    memset(&one_roi, 0, sizeof(one_roi));
    one_roi.color_u = one_u;
    one_roi.color_v = one_v;
    one_roi.color_luma = one_luma;
    one_roi.color_u_bin = one_bin;
    one_roi.color_v_bin = one_bin;
    one_roi.color_valid_mask = one_valid;
    anomaly_color_compute_target_telemetry(
        &one_roi, 1, 1, 0, 0, 0, 1, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 1 && telemetry.ring_neighbor_count == 0,
           "color target telemetry: single-cell ROI has patch but no ring");
    EXPECT_NEAR(telemetry.patch_mean_u, 3.0f, 0.0001f,
                "color target telemetry: single-cell patch mean U");
    EXPECT_NEAR(telemetry.patch_mean_v, 4.0f, 0.0001f,
                "color target telemetry: single-cell patch mean V");
    EXPECT_NEAR(telemetry.patch_mean_luma, 5.0f, 0.0001f,
                "color target telemetry: single-cell patch mean luma");
    EXPECT_NEAR(telemetry.ring_mean_u, 0.0f, 0.0001f,
                "color target telemetry: missing ring keeps zero mean U");
    EXPECT_NEAR(telemetry.ring_chroma_contrast, 0.0f, 0.0001f,
                "color target telemetry: missing ring keeps zero contrast");

    telemetry.patch_valid_count = 99;
    telemetry.patch_mean_u = 99.0f;
    anomaly_color_compute_target_telemetry(
        NULL, W, H, 2, 2, 1, 2, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 0 &&
           fabsf(telemetry.patch_mean_u) <= 0.0001f,
           "color target telemetry: null ROI zeros output");

    roi.color_u = NULL;
    telemetry.patch_valid_count = 99;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 0,
           "color target telemetry: invalid ROI arrays zero output");
    roi.color_u = color_u;

    color_valid[(size_t)2 * W + 2] = 0u;
    telemetry.patch_valid_count = 99;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 0,
           "color target telemetry: invalid center leaves zero output");
    color_valid[(size_t)2 * W + 2] = 1u;

    telemetry.patch_valid_count = 99;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, -1, 2, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 0,
           "color target telemetry: negative inner radius zeros output");
    telemetry.patch_valid_count = 99;
    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 2, 2, false, NULL, &telemetry);
    EXPECT(telemetry.patch_valid_count == 0,
           "color target telemetry: outer radius must exceed inner radius");

    anomaly_color_compute_target_telemetry(
        &roi, W, H, 2, 2, 1, 2, false, NULL, NULL);
}

static void stamp_color_patch(uint8_t *buf, int stride, int w, int h,
                              int cx, int cy, int radius,
                              uint8_t r, uint8_t g, uint8_t b) {
    for (int dy = -radius; dy <= radius; dy++) {
        int y = cy + dy;
        if (y < 0 || y >= h) continue;
        for (int dx = -radius; dx <= radius; dx++) {
            int x = cx + dx;
            if (x < 0 || x >= w) continue;
            set_pixel(buf, stride, x, y, r, g, b);
        }
    }
}

static void stamp_color_rect(uint8_t *buf, int stride, int w, int h,
                             int x0, int y0, int rect_w, int rect_h,
                             uint8_t r, uint8_t g, uint8_t b) {
    for (int y = y0; y < y0 + rect_h; y++) {
        if (y < 0 || y >= h) continue;
        for (int x = x0; x < x0 + rect_w; x++) {
            if (x < 0 || x >= w) continue;
            set_pixel(buf, stride, x, y, r, g, b);
        }
    }
}

static void stamp_texture_field(uint8_t *buf, int stride, int w, int h, int shift_x) {
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            int src_x = x - shift_x;
            if (src_x < 0 || src_x >= w) continue;
            uint8_t gray = (uint8_t)(32 + ((src_x * 37 + y * 17 + (src_x * y) / 29) % 192));
            set_pixel(buf, stride, x, y, gray, gray, gray);
        }
    }
}

static int count_mask_set(const uint8_t *mask, int count) {
    if (mask == NULL || count <= 0) return 0;
    int set_count = 0;
    for (int i = 0; i < count; i++) {
        if (mask[i] != 0u) set_count++;
    }
    return set_count;
}

static const anomaly_target_track_t *find_active_track(
        const anomaly_state_t *state,
        int                    algorithm) {
    if (state == NULL) return NULL;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        const anomaly_target_track_t *track = &state->target_tracks[i];
        if (!track->active) continue;
        if (track->algorithm == algorithm) return track;
    }
    return NULL;
}

static anomaly_config_t default_cfg(int algorithm_mask) {
    anomaly_config_t c = {0};
    c.enabled           = true;
    c.algorithm_mask    = algorithm_mask;
    c.registration_mode = ANOMALY_REGISTRATION_GMV;
    c.frame_stride      = 1;
    c.pixel_step        = 0;
    c.score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD;
    c.motion_evidence_scale = 1.0f;
    c.min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    c.thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT;
    c.scan_zone         = 1.0f;  // full frame for most tests
    c.min_hits          = 1;     // show on first hit unless overridden
    c.thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA;
    c.color_frontend_mode = ANOMALY_COLOR_FRONTEND_LEGACY;
    return c;
}

// ── Appearance candidate rank tests ────────────────────────────────────────

static anomaly_thermal_blob_candidate_t make_thermal_rank_candidate(void) {
    anomaly_thermal_blob_candidate_t candidate;
    memset(&candidate, 0, sizeof(candidate));
    candidate.retention_rank = 0.5f;
    candidate.retention_rank_valid = true;
    candidate.area = 4.0f;
    candidate.span = 3.0f;
    candidate.candidate.thermal_score = 2.0f;
    candidate.peak_delta = 14.0f;
    candidate.quality = 0.7f;
    return candidate;
}

static anomaly_thermal_blob_candidate_t make_thermal_insert_candidate(
        int sg_x,
        int sg_y,
        float retention_rank,
        float area) {
    anomaly_thermal_blob_candidate_t candidate = make_thermal_rank_candidate();
    candidate.candidate.sg_x = sg_x;
    candidate.candidate.sg_y = sg_y;
    candidate.retention_rank = retention_rank;
    candidate.area = area;
    return candidate;
}

static anomaly_color_blob_candidate_t make_color_rank_candidate(void) {
    anomaly_color_blob_candidate_t candidate;
    memset(&candidate, 0, sizeof(candidate));
    candidate.retention_rank = 0.5f;
    candidate.retention_rank_valid = true;
    candidate.hist_rarity_score = 0.4f;
    candidate.area = 4.0f;
    candidate.span = 3.0f;
    candidate.candidate.color_score = 2.0f;
    candidate.quality = 0.7f;
    candidate.center_share = 0.5f;
    candidate.peak_support = 8.0f;
    return candidate;
}

static anomaly_color_blob_candidate_t make_color_insert_candidate(
        int sg_x,
        int sg_y,
        float retention_rank,
        float rarity_score) {
    anomaly_color_blob_candidate_t candidate = make_color_rank_candidate();
    candidate.candidate.sg_x = sg_x;
    candidate.candidate.sg_y = sg_y;
    candidate.retention_rank = retention_rank;
    candidate.hist_rarity_score = rarity_score;
    return candidate;
}

static void test_thermal_blob_candidate_rank_ordering(void) {
    anomaly_thermal_blob_candidate_t base = make_thermal_rank_candidate();
    anomaly_thermal_blob_candidate_t challenger = base;

    EXPECT(anomaly_thermal_blob_candidate_compare_rank(NULL, NULL) == 0,
           "thermal rank: NULL equals NULL");
    EXPECT(anomaly_thermal_blob_candidate_compare_rank(NULL, &base) > 0,
           "thermal rank: NULL sorts after candidate");
    EXPECT(anomaly_thermal_blob_candidate_compare_rank(&base, NULL) < 0,
           "thermal rank: candidate sorts before NULL");

    challenger = base;
    challenger.retention_rank = base.retention_rank + 0.1f;
    challenger.area = base.area + 100.0f;
    EXPECT(anomaly_thermal_blob_candidate_compare_rank(&challenger, &base) < 0,
           "thermal rank: higher valid retention rank wins before geometry");

    challenger = base;
    challenger.area = base.area - 1.0f;
    EXPECT(anomaly_thermal_blob_candidate_compare_rank(&challenger, &base) < 0,
           "thermal rank: smaller area wins when retention rank ties");

    challenger = base;
    challenger.span = base.span - 1.0f;
    EXPECT(anomaly_thermal_blob_candidate_compare_rank(&challenger, &base) < 0,
           "thermal rank: smaller span wins when area ties");
}

static void test_color_blob_candidate_rank_ordering(void) {
    anomaly_color_blob_candidate_t base = make_color_rank_candidate();
    anomaly_color_blob_candidate_t challenger = base;

    EXPECT(anomaly_color_blob_candidate_compare_rank(NULL, NULL) == 0,
           "color rank: NULL equals NULL");
    EXPECT(anomaly_color_blob_candidate_compare_rank(NULL, &base) > 0,
           "color rank: NULL sorts after candidate");
    EXPECT(anomaly_color_blob_candidate_compare_rank(&base, NULL) < 0,
           "color rank: candidate sorts before NULL");

    challenger = base;
    challenger.retention_rank = base.retention_rank + 0.1f;
    challenger.hist_rarity_score = 0.0f;
    EXPECT(anomaly_color_blob_candidate_compare_rank(&challenger, &base) < 0,
           "color rank: higher retention rank wins before rarity");

    challenger = base;
    challenger.retention_rank_valid = false;
    challenger.hist_rarity_score = base.hist_rarity_score + 0.1f;
    base.retention_rank_valid = false;
    EXPECT(anomaly_color_blob_candidate_compare_rank(&challenger, &base) < 0,
           "color rank: higher histogram rarity wins when retention rank ties");

    base = make_color_rank_candidate();
    base.retention_rank_valid = false;
    base.hist_rarity_score = 0.0f;
    base.area = 1.0f;
    base.span = 1.0f;
    challenger = base;
    challenger.area = 4.0f;
    challenger.span = 3.0f;
    EXPECT(anomaly_color_blob_candidate_compact_rank(challenger.area, challenger.span) >
               anomaly_color_blob_candidate_compact_rank(base.area, base.span),
           "color rank: compact-rank fixture has stronger compact preference");
    EXPECT(anomaly_color_blob_candidate_compare_rank(&challenger, &base) < 0,
           "color rank: compact rank breaks score and quality ties");
}

static void test_thermal_blob_candidate_rank_lookup(void) {
    anomaly_thermal_blob_candidate_t candidates[3];
    memset(candidates, 0, sizeof(candidates));
    candidates[0].candidate.sg_x = 4;
    candidates[0].candidate.sg_y = 5;
    candidates[1].candidate.sg_x = 7;
    candidates[1].candidate.sg_y = 8;
    candidates[2].candidate.sg_x = 7;
    candidates[2].candidate.sg_y = 8;

    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(NULL, 3, 4, 5) == -1,
           "thermal rank lookup: NULL list returns miss");
    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(candidates, 0, 4, 5) == -1,
           "thermal rank lookup: empty list returns miss");
    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(candidates, 3, -1, 5) == -1,
           "thermal rank lookup: negative coordinates return miss");
    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(candidates, 3, 2, 5) == -1,
           "thermal rank lookup: missing coordinate returns miss");
    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(candidates, 3, 4, 5) == 0,
           "thermal rank lookup: exact first coordinate returns rank");
    EXPECT(anomaly_appearance_find_thermal_blob_candidate_rank(candidates, 3, 7, 8) == 1,
           "thermal rank lookup: duplicate coordinate returns first rank");
}

static void test_color_blob_candidate_rank_lookup(void) {
    anomaly_color_blob_candidate_t candidates[3];
    memset(candidates, 0, sizeof(candidates));
    candidates[0].candidate.sg_x = 3;
    candidates[0].candidate.sg_y = 6;
    candidates[1].candidate.sg_x = 9;
    candidates[1].candidate.sg_y = 2;
    candidates[2].candidate.sg_x = 9;
    candidates[2].candidate.sg_y = 2;

    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(NULL, 3, 3, 6) == -1,
           "color rank lookup: NULL list returns miss");
    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(candidates, 0, 3, 6) == -1,
           "color rank lookup: empty list returns miss");
    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(candidates, 3, 3, -1) == -1,
           "color rank lookup: negative coordinates return miss");
    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(candidates, 3, 3, 7) == -1,
           "color rank lookup: missing coordinate returns miss");
    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(candidates, 3, 3, 6) == 0,
           "color rank lookup: exact first coordinate returns rank");
    EXPECT(anomaly_appearance_find_color_blob_candidate_rank(candidates, 3, 9, 2) == 1,
           "color rank lookup: duplicate coordinate returns first rank");
}

static void test_appearance_ranked_index_insert_rejects_invalid_inputs(void) {
    int indices[3] = {10, 11, 12};
    float ranks[3] = {0.9f, 0.8f, 0.7f};
    int count = 2;

    anomaly_appearance_insert_ranked_index(99, 1.0f, NULL, ranks, &count, 3);
    EXPECT(count == 2 && indices[0] == 10,
           "ranked index insert: NULL indices leaves state unchanged");
    anomaly_appearance_insert_ranked_index(99, 1.0f, indices, NULL, &count, 3);
    EXPECT(count == 2 && indices[0] == 10,
           "ranked index insert: NULL ranks leaves state unchanged");
    anomaly_appearance_insert_ranked_index(99, 1.0f, indices, ranks, NULL, 3);
    EXPECT(count == 2 && indices[0] == 10,
           "ranked index insert: NULL count leaves state unchanged");
    anomaly_appearance_insert_ranked_index(99, 1.0f, indices, ranks, &count, 0);
    EXPECT(count == 2 && indices[0] == 10,
           "ranked index insert: nonpositive capacity leaves state unchanged");
}

static void test_appearance_ranked_index_insert_orders_and_clamps(void) {
    int indices[3] = {0};
    float ranks[3] = {0.0f};
    int count = 0;

    anomaly_appearance_insert_ranked_index(10, 0.50f, indices, ranks, &count, 3);
    EXPECT(count == 1 && indices[0] == 10 && ranks[0] == 0.50f,
           "ranked index insert: empty list accepts first item");

    anomaly_appearance_insert_ranked_index(11, 0.70f, indices, ranks, &count, 3);
    anomaly_appearance_insert_ranked_index(12, 0.60f, indices, ranks, &count, 3);
    EXPECT(count == 3,
           "ranked index insert: count reaches capacity");
    EXPECT(indices[0] == 11 && indices[1] == 12 && indices[2] == 10,
           "ranked index insert: inserts descending by rank");
    EXPECT_NEAR(ranks[0], 0.70f, 0.0001f,
                "ranked index insert: highest rank stays first");
    EXPECT_NEAR(ranks[1], 0.60f, 0.0001f,
                "ranked index insert: middle rank lands in middle");
    EXPECT_NEAR(ranks[2], 0.50f, 0.0001f,
                "ranked index insert: lowest retained rank stays last");

    anomaly_appearance_insert_ranked_index(13, 0.10f, indices, ranks, &count, 3);
    EXPECT(count == 3 && indices[0] == 11 && indices[1] == 12 && indices[2] == 10,
           "ranked index insert: low-rank full-list insert is dropped");

    anomaly_appearance_insert_ranked_index(14, 0.80f, indices, ranks, &count, 3);
    EXPECT(count == 3 && indices[0] == 14 && indices[1] == 11 && indices[2] == 12,
           "ranked index insert: high-rank full-list insert shifts and drops tail");
    EXPECT_NEAR(ranks[0], 0.80f, 0.0001f,
                "ranked index insert: high-rank replacement score is first");
    EXPECT_NEAR(ranks[2], 0.60f, 0.0001f,
                "ranked index insert: dropped tail removes previous lowest score");
}

static void test_thermal_blob_insert_rejects_invalid_inputs(void) {
    anomaly_thermal_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_thermal_insert_candidate(1, 1, 0.40f, 4.0f);
    int count = 1;
    anomaly_thermal_blob_candidate_t candidate =
        make_thermal_insert_candidate(4, 4, 0.90f, 2.0f);
    anomaly_thermal_blob_insert_report_t report;

    anomaly_appearance_insert_thermal_blob_candidate(
            NULL, &count, &candidate, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "thermal blob insert: NULL list is invalid and leaves state");

    anomaly_appearance_insert_thermal_blob_candidate(
            top, NULL, &candidate, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "thermal blob insert: NULL count is invalid and leaves state");

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, NULL, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "thermal blob insert: NULL candidate is invalid and leaves state");

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &candidate, 0, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "thermal blob insert: nonpositive capacity is invalid");
}

static void test_thermal_blob_insert_sorts_candidates(void) {
    anomaly_thermal_blob_candidate_t top[3];
    memset(top, 0, sizeof(top));
    int count = 0;
    anomaly_thermal_blob_insert_report_t report;

    anomaly_thermal_blob_candidate_t low =
        make_thermal_insert_candidate(1, 1, 0.20f, 4.0f);
    anomaly_thermal_blob_candidate_t high =
        make_thermal_insert_candidate(8, 1, 0.80f, 4.0f);
    anomaly_thermal_blob_candidate_t mid =
        make_thermal_insert_candidate(1, 8, 0.50f, 4.0f);

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &low, 3, 1, -1, false, &report);
    EXPECT(report.valid && report.inserted && report.insert_rank == 0 && count == 1,
           "thermal blob insert: first candidate inserts at rank zero");

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &high, 3, 1, -1, false, &report);
    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &mid, 3, 1, -1, false, &report);

    EXPECT(count == 3,
           "thermal blob insert: count grows to inserted candidates");
    EXPECT(top[0].candidate.sg_x == 8 &&
           top[1].candidate.sg_y == 8 &&
           top[2].candidate.sg_x == 1,
           "thermal blob insert: list is sorted by thermal rank");
    EXPECT(report.insert_rank == 1 && report.pre_cap_rank == 1,
           "thermal blob insert: report carries insertion rank");
}

static void test_thermal_blob_insert_nms_rejects_weaker_candidate(void) {
    anomaly_thermal_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_thermal_insert_candidate(4, 4, 0.80f, 4.0f);
    int count = 1;
    anomaly_thermal_blob_candidate_t weak =
        make_thermal_insert_candidate(5, 4, 0.30f, 4.0f);
    anomaly_thermal_blob_insert_report_t report;

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &weak, 2, 1, -1, true, &report);

    EXPECT(count == 1 && top[0].candidate.sg_x == 4,
           "thermal blob insert: weaker NMS candidate leaves list unchanged");
    EXPECT(report.valid && report.rejected_by_nms && !report.inserted,
           "thermal blob insert: weaker NMS candidate reports rejection");
    EXPECT(report.pre_cap_rank == 0 &&
           report.nms_conflict_rank == 0 &&
           report.nms_conflict_sample_x == 4 &&
           report.nms_conflict_sample_y == 4,
           "thermal blob insert: NMS rejection reports existing conflict sample");
}

static void test_thermal_blob_insert_nms_replaces_weaker_candidate(void) {
    anomaly_thermal_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_thermal_insert_candidate(4, 4, 0.30f, 4.0f);
    int count = 1;
    anomaly_thermal_blob_candidate_t strong =
        make_thermal_insert_candidate(5, 4, 0.90f, 4.0f);
    anomaly_thermal_blob_insert_report_t report;

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &strong, 2, 1, 0, false, &report);

    EXPECT(count == 1 && top[0].candidate.sg_x == 5,
           "thermal blob insert: stronger NMS candidate replaces existing");
    EXPECT(report.valid && report.inserted && report.replaced_existing_by_nms,
           "thermal blob insert: stronger NMS candidate reports replacement");
    EXPECT(report.nms_conflict_rank == 0 &&
           report.nms_conflict_sample_x == 5 &&
           report.nms_conflict_sample_y == 4,
           "thermal blob insert: non-target replacement reports incoming sample");
}

static void test_thermal_blob_insert_cap_rejects_low_rank_candidate(void) {
    anomaly_thermal_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_thermal_insert_candidate(1, 1, 0.90f, 4.0f);
    top[1] = make_thermal_insert_candidate(5, 1, 0.80f, 4.0f);
    int count = 2;
    anomaly_thermal_blob_candidate_t low =
        make_thermal_insert_candidate(9, 1, 0.10f, 4.0f);
    anomaly_thermal_blob_insert_report_t report;

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &low, 2, 1, -1, true, &report);

    EXPECT(count == 2 && top[1].candidate.sg_x == 5,
           "thermal blob insert: full-list low-rank candidate leaves list");
    EXPECT(report.valid && report.rejected_by_cap && !report.inserted,
           "thermal blob insert: low-rank full-list candidate reports cap rejection");
    EXPECT(report.pre_cap_rank == 2 && report.candidate_count_before == 2,
           "thermal blob insert: cap rejection reports pre-cap rank and count");
}

static void test_thermal_blob_insert_reports_target_tail_drop(void) {
    anomaly_thermal_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_thermal_insert_candidate(1, 1, 0.90f, 4.0f);
    top[1] = make_thermal_insert_candidate(5, 1, 0.50f, 4.0f);
    int count = 2;
    anomaly_thermal_blob_candidate_t incoming =
        make_thermal_insert_candidate(9, 1, 0.70f, 4.0f);
    anomaly_thermal_blob_insert_report_t report;

    anomaly_appearance_insert_thermal_blob_candidate(
            top, &count, &incoming, 2, 1, 1, false, &report);

    EXPECT(count == 2 && top[0].candidate.sg_x == 1 && top[1].candidate.sg_x == 9,
           "thermal blob insert: full-list insertion drops previous tail");
    EXPECT(report.valid && report.inserted && report.insert_rank == 1,
           "thermal blob insert: inserted candidate reports capped insert rank");
    EXPECT(report.target_tail_dropped_by_cap,
           "thermal blob insert: non-target insertion reports target tail cap drop");
}

static void test_color_blob_insert_rejects_invalid_inputs(void) {
    anomaly_color_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_color_insert_candidate(1, 1, 0.40f, 0.20f);
    int count = 1;
    anomaly_color_blob_candidate_t candidate =
        make_color_insert_candidate(4, 4, 0.90f, 0.70f);
    anomaly_color_blob_insert_report_t report;

    anomaly_appearance_insert_color_blob_candidate(
            NULL, &count, &candidate, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "color blob insert: NULL list is invalid and leaves state");

    anomaly_appearance_insert_color_blob_candidate(
            top, NULL, &candidate, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "color blob insert: NULL count is invalid and leaves state");

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, NULL, 2, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "color blob insert: NULL candidate is invalid and leaves state");

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &candidate, 0, 1, -1, false, &report);
    EXPECT(!report.valid && count == 1 && top[0].candidate.sg_x == 1,
           "color blob insert: nonpositive capacity is invalid");
}

static void test_color_blob_insert_sorts_candidates(void) {
    anomaly_color_blob_candidate_t top[3];
    memset(top, 0, sizeof(top));
    int count = 0;
    anomaly_color_blob_insert_report_t report;

    anomaly_color_blob_candidate_t low =
        make_color_insert_candidate(1, 1, 0.20f, 0.20f);
    anomaly_color_blob_candidate_t high =
        make_color_insert_candidate(8, 1, 0.80f, 0.20f);
    anomaly_color_blob_candidate_t mid =
        make_color_insert_candidate(1, 8, 0.50f, 0.20f);

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &low, 3, 1, -1, false, &report);
    EXPECT(report.valid && report.inserted && report.insert_rank == 0 && count == 1,
           "color blob insert: first candidate inserts at rank zero");

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &high, 3, 1, -1, false, &report);
    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &mid, 3, 1, -1, false, &report);

    EXPECT(count == 3,
           "color blob insert: count grows to inserted candidates");
    EXPECT(top[0].candidate.sg_x == 8 &&
           top[1].candidate.sg_y == 8 &&
           top[2].candidate.sg_x == 1,
           "color blob insert: list is sorted by color rank");
    EXPECT(report.insert_rank == 1 && report.pre_cap_rank == 1,
           "color blob insert: report carries insertion rank");
}

static void test_color_blob_insert_nms_rejects_weaker_candidate(void) {
    anomaly_color_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_color_insert_candidate(4, 4, 0.80f, 0.20f);
    int count = 1;
    anomaly_color_blob_candidate_t weak =
        make_color_insert_candidate(5, 4, 0.30f, 0.20f);
    anomaly_color_blob_insert_report_t report;

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &weak, 2, 1, -1, true, &report);

    EXPECT(count == 1 && top[0].candidate.sg_x == 4,
           "color blob insert: weaker NMS candidate leaves list unchanged");
    EXPECT(report.valid && report.rejected_by_nms && !report.inserted,
           "color blob insert: weaker NMS candidate reports rejection");
    EXPECT(report.pre_cap_rank == 0 &&
           report.nms_conflict_rank == 0 &&
           report.nms_conflict_sample_x == 4 &&
           report.nms_conflict_sample_y == 4,
           "color blob insert: NMS rejection reports existing conflict sample");
}

static void test_color_blob_insert_nms_replaces_weaker_candidate(void) {
    anomaly_color_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_color_insert_candidate(4, 4, 0.30f, 0.20f);
    int count = 1;
    anomaly_color_blob_candidate_t strong =
        make_color_insert_candidate(5, 4, 0.90f, 0.20f);
    anomaly_color_blob_insert_report_t report;

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &strong, 2, 1, 0, false, &report);

    EXPECT(count == 1 && top[0].candidate.sg_x == 5,
           "color blob insert: stronger NMS candidate replaces existing");
    EXPECT(report.valid && report.inserted && report.replaced_existing_by_nms,
           "color blob insert: stronger NMS candidate reports replacement");
    EXPECT(report.nms_conflict_rank == 0 &&
           report.nms_conflict_sample_x == 5 &&
           report.nms_conflict_sample_y == 4,
           "color blob insert: non-target replacement reports incoming sample");
}

static void test_color_blob_insert_cap_rejects_low_rank_candidate(void) {
    anomaly_color_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_color_insert_candidate(1, 1, 0.90f, 0.20f);
    top[1] = make_color_insert_candidate(5, 1, 0.80f, 0.20f);
    int count = 2;
    anomaly_color_blob_candidate_t low =
        make_color_insert_candidate(9, 1, 0.10f, 0.20f);
    anomaly_color_blob_insert_report_t report;

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &low, 2, 1, -1, true, &report);

    EXPECT(count == 2 && top[1].candidate.sg_x == 5,
           "color blob insert: full-list low-rank candidate leaves list");
    EXPECT(report.valid && report.rejected_by_cap && !report.inserted,
           "color blob insert: low-rank full-list candidate reports cap rejection");
    EXPECT(report.pre_cap_rank == 2 && report.candidate_count_before == 2,
           "color blob insert: cap rejection reports pre-cap rank and count");
}

static void test_color_blob_insert_reports_target_tail_drop(void) {
    anomaly_color_blob_candidate_t top[2];
    memset(top, 0, sizeof(top));
    top[0] = make_color_insert_candidate(1, 1, 0.90f, 0.20f);
    top[1] = make_color_insert_candidate(5, 1, 0.50f, 0.20f);
    int count = 2;
    anomaly_color_blob_candidate_t incoming =
        make_color_insert_candidate(9, 1, 0.70f, 0.20f);
    anomaly_color_blob_insert_report_t report;

    anomaly_appearance_insert_color_blob_candidate(
            top, &count, &incoming, 2, 1, 1, false, &report);

    EXPECT(count == 2 && top[0].candidate.sg_x == 1 && top[1].candidate.sg_x == 9,
           "color blob insert: full-list insertion drops previous tail");
    EXPECT(report.valid && report.inserted && report.insert_rank == 1,
           "color blob insert: inserted candidate reports capped insert rank");
    EXPECT(report.target_tail_dropped_by_cap,
           "color blob insert: non-target insertion reports target tail cap drop");
}

static void test_motion_candidate_collection_null_and_reset(void) {
    anomaly_motion_candidate_t candidates[ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX];
    memset(candidates, 0, sizeof(candidates));
    int count = 7;

    anomaly_appearance_collect_motion_candidates(NULL, NULL, 3, 3, 0, 0, 1, NULL, &count);
    EXPECT(count == 0,
           "motion candidate collection: NULL output candidates reset count");

    count = 7;
    anomaly_appearance_collect_motion_candidates(NULL, NULL, 0, 3, 0, 0, 1, candidates, &count);
    EXPECT(count == 0,
           "motion candidate collection: invalid geometry resets count");

    anomaly_appearance_collect_motion_candidates(NULL, NULL, 3, 3, 0, 0, 1, candidates, NULL);
    EXPECT(count == 0,
           "motion candidate collection: NULL count is tolerated");
}

static void test_motion_candidate_collection_thermal_peak_mapping(void) {
    float thermal_map[9] = {0};
    anomaly_motion_candidate_t candidates[ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX];
    int count = 0;
    thermal_map[4] = 2.5f;
    thermal_map[1] = 0.25f;

    anomaly_appearance_collect_motion_candidates(
            thermal_map,
            NULL,
            3,
            3,
            10,
            20,
            4,
            candidates,
            &count);

    EXPECT(count == 1,
           "motion candidate collection: thermal-only peak is selected");
    EXPECT(candidates[0].sg_x == 1 && candidates[0].sg_y == 1,
           "motion candidate collection: thermal peak sample coordinate is preserved");
    EXPECT(candidates[0].pixel_x == 14 && candidates[0].pixel_y == 24,
           "motion candidate collection: thermal peak maps to ROI pixel coordinate");
    EXPECT_NEAR(candidates[0].proposal_score, 2.5f, 0.0001f,
                "motion candidate collection: thermal-only proposal score is thermal score");
}

static void test_motion_candidate_collection_color_only_score(void) {
    float color_map[9] = {0};
    anomaly_motion_candidate_t candidates[ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX];
    int count = 0;
    color_map[4] = 2.0f;

    anomaly_appearance_collect_motion_candidates(
            NULL,
            color_map,
            3,
            3,
            0,
            0,
            2,
            candidates,
            &count);

    EXPECT(count == 1,
           "motion candidate collection: color-only peak is selected");
    EXPECT_NEAR(candidates[0].proposal_score, 1.7f, 0.0001f,
                "motion candidate collection: color-only score uses 0.85 contribution");
    EXPECT_NEAR(candidates[0].color_score, 2.0f, 0.0001f,
                "motion candidate collection: color-only score is retained");
    EXPECT_NEAR(candidates[0].thermal_score, 0.0f, 0.0001f,
                "motion candidate collection: missing thermal score is clamped to zero");
}

static void test_motion_candidate_collection_peak_nms_and_clamp(void) {
    float thermal_map[15] = {0};
    anomaly_motion_candidate_t candidates[ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX];
    int count = 0;

    thermal_map[0] = 1.0f;
    thermal_map[2] = 5.0f;
    thermal_map[5] = 3.0f;
    thermal_map[8] = 4.0f;
    thermal_map[11] = 2.0f;
    thermal_map[14] = 6.0f;

    anomaly_appearance_collect_motion_candidates(
            thermal_map,
            NULL,
            15,
            1,
            100,
            200,
            3,
            candidates,
            &count);

    EXPECT(count == ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX,
           "motion candidate collection: output is clamped to max candidate count");
    EXPECT(candidates[0].sg_x == 14 && candidates[1].sg_x == 2 &&
           candidates[2].sg_x == 8 && candidates[3].sg_x == 5,
           "motion candidate collection: candidates are sorted and weaker NMS neighbor is replaced");
    EXPECT_NEAR(candidates[0].proposal_score, 6.0f, 0.0001f,
                "motion candidate collection: strongest score remains first");
    EXPECT(candidates[0].pixel_x == 142 && candidates[0].pixel_y == 200,
           "motion candidate collection: sorted candidates retain pixel mapping");
}

// ── Target observation boundary tests ─────────────────────────────────────

static void test_color_candidate_target_observation_conversion(void) {
    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));

    bool ok = anomaly_target_observation_populate_color_candidate(
            10,
            20,
            4,
            2,
            3,
            4,
            5,
            40,
            50,
            3.1f,
            0.7f,
            0.5f,
            2.3f,
            200.0f,
            100.0f,
            ANOMALY_ALGO_PERSIST,
            &obs);

    EXPECT(ok, "target observation: color candidate conversion succeeds");
    EXPECT(obs.valid, "target observation: color candidate marks observation valid");
    EXPECT(obs.publish_confirming,
           "target observation: color candidate publishes confirming observation");
    EXPECT(obs.algorithm == ANOMALY_ALGO_PERSIST,
           "target observation: color candidate preserves algorithm label");
    EXPECT_NEAR(obs.center_x_norm, 0.20f, 0.0001f,
                "target observation: color center x matches pixel normalization");
    EXPECT_NEAR(obs.center_y_norm, 0.50f, 0.0001f,
                "target observation: color center y matches pixel normalization");
    EXPECT_NEAR(obs.half_w_norm, 0.04f, 0.0001f,
                "target observation: color half width matches bbox conversion");
    EXPECT_NEAR(obs.half_h_norm, 0.08f, 0.0001f,
                "target observation: color half height matches bbox conversion");
    EXPECT_NEAR(obs.support_radius_norm, 0.124f, 0.0001f,
                "target observation: color support radius matches conversion");
    EXPECT_NEAR(obs.confidence, 0.636f, 0.0001f,
                "target observation: color confidence matches formula");
}

static void test_thermal_candidate_target_observation_conversion(void) {
    anomaly_target_observation_t obs;
    memset(&obs, 0, sizeof(obs));

    bool ok = anomaly_target_observation_populate_thermal_candidate(
            10,
            20,
            4,
            2,
            3,
            4,
            5,
            30,
            35,
            2.8f,
            0.6f,
            0.4f,
            0.75f,
            0.25f,
            2.3f,
            200.0f,
            100.0f,
            &obs);

    EXPECT(ok, "target observation: thermal candidate conversion succeeds");
    EXPECT(obs.valid, "target observation: thermal candidate marks observation valid");
    EXPECT(!obs.publish_confirming,
           "target observation: thermal candidate does not publish confirming observation");
    EXPECT(obs.algorithm == ANOMALY_ALGO_THERMAL,
           "target observation: thermal candidate uses thermal algorithm label");
    EXPECT_NEAR(obs.center_x_norm, 0.15f, 0.0001f,
                "target observation: thermal center x matches pixel normalization");
    EXPECT_NEAR(obs.center_y_norm, 0.35f, 0.0001f,
                "target observation: thermal center y matches pixel normalization");
    EXPECT_NEAR(obs.half_w_norm, 0.04f, 0.0001f,
                "target observation: thermal half width matches bbox conversion");
    EXPECT_NEAR(obs.half_h_norm, 0.08f, 0.0001f,
                "target observation: thermal half height matches bbox conversion");
    EXPECT_NEAR(obs.support_radius_norm, 0.14f, 0.0001f,
                "target observation: thermal support radius matches conversion");
    EXPECT_NEAR(obs.confidence, 0.56028f, 0.0001f,
                "target observation: thermal confidence matches formula");
}

static void test_target_observation_duplicate_suppression(void) {
    anomaly_target_observation_t observations[2];
    memset(observations, 0, sizeof(observations));
    observations[0].valid = true;
    observations[0].center_x_norm = 0.50f;
    observations[0].center_y_norm = 0.50f;
    observations[0].support_radius_norm = 0.03f;

    anomaly_target_observation_t near_candidate;
    memset(&near_candidate, 0, sizeof(near_candidate));
    near_candidate.valid = true;
    near_candidate.center_x_norm = 0.53f;
    near_candidate.center_y_norm = 0.50f;
    near_candidate.support_radius_norm = 0.02f;

    anomaly_target_observation_t far_candidate = near_candidate;
    far_candidate.center_x_norm = 0.60f;

    anomaly_target_observation_t invalid_candidate = near_candidate;
    invalid_candidate.valid = false;

    EXPECT(anomaly_target_observation_near_existing(
                observations, 1, &near_candidate),
           "target observation: near candidate is suppressed as duplicate");
    EXPECT(!anomaly_target_observation_near_existing(
                observations, 1, &far_candidate),
           "target observation: far candidate is not suppressed");
    EXPECT(!anomaly_target_observation_near_existing(
                observations, 1, &invalid_candidate),
           "target observation: invalid candidate is not suppressed");
}

// ── anomaly_detector_process facade tests ──────────────────────────────────

static void expect_same_result_shape(
        const anomaly_result_t *direct,
        const anomaly_result_t *facade,
        const char             *msg) {
    EXPECT(direct->box_count == facade->box_count, msg);
    EXPECT(direct->had_discontinuity == facade->had_discontinuity, msg);
    EXPECT(direct->registration_ran_this_frame == facade->registration_ran_this_frame, msg);
    EXPECT(direct->appearance_refresh_ran_this_frame == facade->appearance_refresh_ran_this_frame, msg);
    EXPECT(direct->registration_health == facade->registration_health, msg);
    EXPECT(direct->rescan_mode == facade->rescan_mode, msg);
    EXPECT(direct->adaptive_effective_stride == facade->adaptive_effective_stride, msg);
    EXPECT(direct->adaptive_stable_frames == facade->adaptive_stable_frames, msg);
    EXPECT(direct->adaptive_drop_hold_frames == facade->adaptive_drop_hold_frames, msg);
    EXPECT_NEAR(direct->adaptive_motion_load, facade->adaptive_motion_load, 0.0001f, msg);
    EXPECT(direct->adaptive_reason_flags == facade->adaptive_reason_flags, msg);
}

static void test_detector_facade_matches_process_frame(void) {
    const int W = 16;
    const int H = 16;
    uint8_t *direct_frame = make_gray_frame(W, H, 80);
    uint8_t *facade_frame = make_gray_frame(W, H, 80);
    anomaly_state_t direct_state;
    anomaly_detector_state_t facade_state;
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_result_t direct_result;
    anomaly_detector_result_t facade_result;
    anomaly_frame_input_t frame = {
        .rgba = facade_frame,
        .rgba_stride = W * 4,
        .width = W,
        .height = H,
        .source_timestamp_us = 12345,
        .frame_format = ANOMALY_FRAME_FORMAT_RGBA8888,
    };
    anomaly_state_init(&direct_state);
    anomaly_detector_state_init(&facade_state);

    int direct_boxes = anomaly_process_frame(
            &direct_state, &cfg, direct_frame, W * 4, W, H, 12345, &direct_result);
    int facade_boxes = anomaly_detector_process(
            &facade_state, &frame, &cfg, &facade_result);

    EXPECT(direct_boxes == facade_boxes,
           "detector facade: return value matches anomaly_process_frame");
    expect_same_result_shape(
            &direct_result,
            &facade_result,
            "detector facade: result shape matches anomaly_process_frame");

    anomaly_state_cleanup(&direct_state);
    anomaly_detector_state_cleanup(&facade_state);
    free(direct_frame);
    free(facade_frame);
}

static void test_detector_facade_rejects_missing_frame(void) {
    anomaly_detector_state_t state;
    anomaly_detector_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_detector_result_t result;
    anomaly_detector_state_init(&state);

    int boxes = anomaly_detector_process(&state, NULL, &cfg, &result);

    EXPECT(boxes == 0, "detector facade: missing frame returns zero boxes");
    EXPECT(result.box_count == 0, "detector facade: missing frame initializes result");
    EXPECT(result.adaptive_effective_stride == cfg.frame_stride,
           "detector facade: missing frame result preserves configured stride");

    anomaly_detector_state_cleanup(&state);
}

static void test_detector_facade_rejects_missing_state(void) {
    const int W = 8;
    const int H = 8;
    uint8_t *frame_buf = make_gray_frame(W, H, 80);
    anomaly_detector_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_detector_result_t result;
    anomaly_frame_input_t frame = {
        .rgba = frame_buf,
        .rgba_stride = W * 4,
        .width = W,
        .height = H,
        .source_timestamp_us = 22222,
        .frame_format = ANOMALY_FRAME_FORMAT_RGBA8888,
    };

    int boxes = anomaly_detector_process(NULL, &frame, &cfg, &result);

    EXPECT(boxes == 0, "detector facade: missing state returns zero boxes");
    EXPECT(result.box_count == 0, "detector facade: missing state initializes result");

    free(frame_buf);
}

static void test_detector_facade_rejects_unsupported_format(void) {
    const int W = 8;
    const int H = 8;
    uint8_t *frame_buf = make_gray_frame(W, H, 80);
    anomaly_detector_state_t state;
    anomaly_detector_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_detector_result_t result;
    anomaly_frame_input_t frame = {
        .rgba = frame_buf,
        .rgba_stride = W * 4,
        .width = W,
        .height = H,
        .source_timestamp_us = 67890,
        .frame_format = ANOMALY_FRAME_FORMAT_RESERVED_YUV,
    };
    anomaly_detector_state_init(&state);

    int boxes = anomaly_detector_process(&state, &frame, &cfg, &result);

    EXPECT(boxes == 0, "detector facade: unsupported format returns zero boxes");
    EXPECT(result.box_count == 0, "detector facade: unsupported format initializes result");
    EXPECT(result.adaptive_effective_stride == cfg.frame_stride,
           "detector facade: unsupported format result preserves configured stride");

    anomaly_detector_state_cleanup(&state);
    free(frame_buf);
}

// ── MotionEstimator movement snapshot tests ───────────────────────────────

static void test_motion_estimator_texture_scale_boundaries(void) {
    EXPECT_NEAR(anomaly_motion_estimator_texture_scale(8), 0.0f, 0.0001f,
                "motion texture scale: score <= 8 returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_texture_scale(-5), 0.0f, 0.0001f,
                "motion texture scale: negative score returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_texture_scale(24), 1.0f, 0.0001f,
                "motion texture scale: score >= 24 returns one");
    EXPECT_NEAR(anomaly_motion_estimator_texture_scale(40), 1.0f, 0.0001f,
                "motion texture scale: high score stays clamped to one");
    EXPECT_NEAR(anomaly_motion_estimator_texture_scale(16), 0.5f, 0.0001f,
                "motion texture scale: midpoint score returns half");
}

static void test_motion_estimator_structure_scale_invalid_and_border(void) {
    uint8_t luma[25];
    memset(luma, 0, sizeof(luma));

    EXPECT_NEAR(anomaly_motion_estimator_structure_scale(NULL, 5, 5, 2, 2),
                0.0f, 0.0001f,
                "motion structure scale: NULL luma returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_structure_scale(luma, 5, 5, 1, 2),
                0.0f, 0.0001f,
                "motion structure scale: left border returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_structure_scale(luma, 5, 5, 2, 1),
                0.0f, 0.0001f,
                "motion structure scale: top border returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_structure_scale(luma, 5, 5, 3, 2),
                0.0f, 0.0001f,
                "motion structure scale: right border returns zero");
    EXPECT_NEAR(anomaly_motion_estimator_structure_scale(luma, 5, 5, 2, 3),
                0.0f, 0.0001f,
                "motion structure scale: bottom border returns zero");
}

static void test_motion_estimator_structure_scale_strong_corner(void) {
    const int W = 9;
    const int H = 9;
    uint8_t luma[81];
    memset(luma, 20, sizeof(luma));
    for (int y = 4; y < H; y++) {
        for (int x = 4; x < W; x++) {
            luma[y * W + x] = 220;
        }
    }

    float scale = anomaly_motion_estimator_structure_scale(luma, W, H, 4, 4);

    EXPECT(scale > 0.0f, "motion structure scale: strong corner scores positive");
    EXPECT(scale <= 1.0f, "motion structure scale: strong corner remains clamped");
}

static void test_motion_estimator_residual_displacement_rejects_invalid_and_miss(void) {
    uint8_t curr[81];
    uint8_t prev[81];
    memset(curr, 10, sizeof(curr));
    memset(prev, 10, sizeof(prev));
    int best_dx = 99;
    int best_dy = 99;
    int best_sad = 99;

    EXPECT(!anomaly_motion_estimator_find_residual_displacement(
                NULL, prev, 9, 9, 4, 4, 4, 4, 1, 2, &best_dx, &best_dy, &best_sad),
           "motion residual displacement: NULL current luma returns false");
    EXPECT(!anomaly_motion_estimator_find_residual_displacement(
                curr, NULL, 9, 9, 4, 4, 4, 4, 1, 2, &best_dx, &best_dy, &best_sad),
           "motion residual displacement: NULL previous luma returns false");
    EXPECT(!anomaly_motion_estimator_find_residual_displacement(
                curr, prev, 9, 9, 0, 4, 4, 4, 1, 2, &best_dx, &best_dy, &best_sad),
           "motion residual displacement: current border cell returns false");
    EXPECT(!anomaly_motion_estimator_find_residual_displacement(
                curr, prev, 9, 9, 4, 4, -10, -10, 1, 2, &best_dx, &best_dy, &best_sad),
           "motion residual displacement: search window with no valid candidates returns false");
}

static void test_motion_estimator_residual_displacement_shifted_patch(void) {
    const int W = 9;
    const int H = 9;
    uint8_t curr[81];
    uint8_t prev[81];
    memset(curr, 10, sizeof(curr));
    memset(prev, 10, sizeof(prev));

    const uint8_t patch[9] = {
        30, 70, 110,
        90, 210, 40,
        150, 60, 180,
    };
    int idx = 0;
    for (int ky = -1; ky <= 1; ky++) {
        for (int kx = -1; kx <= 1; kx++) {
            curr[(4 + ky) * W + (4 + kx)] = patch[idx];
            prev[(3 + ky) * W + (5 + kx)] = patch[idx];
            idx++;
        }
    }

    int best_dx = 0;
    int best_dy = 0;
    int best_sad = -1;
    bool found = anomaly_motion_estimator_find_residual_displacement(
            curr, prev, W, H, 4, 4, 4, 4, 1, 2, &best_dx, &best_dy, &best_sad);

    EXPECT(found, "motion residual displacement: shifted synthetic patch is found");
    EXPECT(best_dx == 1, "motion residual displacement: shifted patch dx matches");
    EXPECT(best_dy == -1, "motion residual displacement: shifted patch dy matches");
    EXPECT(best_sad == 0, "motion residual displacement: shifted patch SAD is exact");
}

static void test_motion_estimator_appearance_scorer_output_init_defaults(void) {
    anomaly_motion_estimator_init_appearance_scorer_output(NULL);

    anomaly_motion_appearance_scorer_output_t out;
    memset(&out, 0xA5, sizeof(out));

    anomaly_motion_estimator_init_appearance_scorer_output(&out);

    EXPECT(!out.valid, "motion appearance output init: default output invalid");
    EXPECT_NEAR(out.global_motion_mean, 0.0f, 0.0001f,
                "motion appearance output init: global mean defaults zero");
    EXPECT_NEAR(out.global_motion_std, 0.0f, 0.0001f,
                "motion appearance output init: global std defaults zero");
    EXPECT_NEAR(out.global_motion_load, 0.0f, 0.0001f,
                "motion appearance output init: global load defaults zero");
    EXPECT_NEAR(out.zoom_motion_scale, 1.0f, 0.0001f,
                "motion appearance output init: zoom scale defaults one");
    EXPECT_NEAR(out.broad_motion_scale, 1.0f, 0.0001f,
                "motion appearance output init: broad scale defaults one");
    EXPECT(out.score_count == 0,
           "motion appearance output init: score count defaults zero");
}

static void test_motion_estimator_appearance_scorer_output_init_clears_scores(void) {
    anomaly_motion_appearance_scorer_output_t out;
    memset(&out, 0, sizeof(out));
    out.valid = true;
    out.score_count = ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS;
    for (int i = 0; i < ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS; i++) {
        out.scores[i].valid = true;
        out.scores[i].score = 2.5f + (float)i;
        out.scores[i].pixel_x = 100 + i;
        out.scores[i].pixel_y = 200 + i;
        out.scores[i].texture_scale = 0.75f;
        out.scores[i].structure_scale = 0.65f;
        out.scores[i].support_scale = 0.55f;
        out.scores[i].registration_scale = 0.45f;
        out.scores[i].persistence_scale = 0.35f;
    }
    out.winner.valid = true;
    out.winner.score = 9.0f;
    out.winner.pixel_x = 12;
    out.winner.pixel_y = 34;

    anomaly_motion_estimator_init_appearance_scorer_output(&out);

    EXPECT(out.score_count == 0,
           "motion appearance output init: prefilled score count cleared");
    EXPECT(!out.winner.valid,
           "motion appearance output init: prefilled winner validity cleared");
    EXPECT_NEAR(out.winner.score, 0.0f, 0.0001f,
                "motion appearance output init: prefilled winner score cleared");
    EXPECT(out.winner.pixel_x == 0 && out.winner.pixel_y == 0,
           "motion appearance output init: prefilled winner pixels cleared");
    for (int i = 0; i < ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS; i++) {
        EXPECT(!out.scores[i].valid,
               "motion appearance output init: prefilled score validity cleared");
        EXPECT_NEAR(out.scores[i].score, 0.0f, 0.0001f,
                    "motion appearance output init: prefilled score value cleared");
        EXPECT(out.scores[i].pixel_x == 0 && out.scores[i].pixel_y == 0,
               "motion appearance output init: prefilled score pixels cleared");
    }
}

static void test_motion_estimator_appearance_score_winner_eligibility(void) {
    anomaly_motion_appearance_score_t score;
    memset(&score, 0, sizeof(score));

    EXPECT(!anomaly_motion_estimator_appearance_score_is_winner_eligible(NULL),
           "motion appearance winner eligibility: NULL score rejected");
    EXPECT(!anomaly_motion_estimator_appearance_score_is_winner_eligible(&score),
           "motion appearance winner eligibility: invalid score rejected");

    score.valid = true;
    score.score = 0.0f;
    EXPECT(!anomaly_motion_estimator_appearance_score_is_winner_eligible(&score),
           "motion appearance winner eligibility: zero score rejected");

    score.score = -0.1f;
    EXPECT(!anomaly_motion_estimator_appearance_score_is_winner_eligible(&score),
           "motion appearance winner eligibility: negative score rejected");

    score.score = 0.01f;
    EXPECT(anomaly_motion_estimator_appearance_score_is_winner_eligible(&score),
           "motion appearance winner eligibility: valid positive score accepted");
}

static void test_motion_estimator_appearance_proposal_carries_candidate_fields(void) {
    anomaly_motion_appearance_proposal_t proposal;
    memset(&proposal, 0, sizeof(proposal));

    proposal.sg_x = 3;
    proposal.sg_y = 4;
    proposal.pixel_x = 30;
    proposal.pixel_y = 40;
    proposal.proposal_score = 5.5f;
    proposal.thermal_score = 2.25f;
    proposal.color_score = 1.75f;

    EXPECT(proposal.sg_x == 3 && proposal.sg_y == 4,
           "motion appearance proposal: sample-grid fields carry assigned values");
    EXPECT(proposal.pixel_x == 30 && proposal.pixel_y == 40,
           "motion appearance proposal: pixel fields carry assigned values");
    EXPECT_NEAR(proposal.proposal_score, 5.5f, 0.0001f,
                "motion appearance proposal: proposal score carries assigned value");
    EXPECT_NEAR(proposal.thermal_score, 2.25f, 0.0001f,
                "motion appearance proposal: thermal score carries assigned value");
    EXPECT_NEAR(proposal.color_score, 1.75f, 0.0001f,
                "motion appearance proposal: color score carries assigned value");
}

static void test_motion_estimator_build_appearance_proposals_rejects_invalid_inputs(void) {
    anomaly_motion_candidate_t candidate;
    anomaly_motion_appearance_proposal_t proposal;
    memset(&candidate, 0, sizeof(candidate));
    memset(&proposal, 0xA5, sizeof(proposal));

    EXPECT(anomaly_motion_estimator_build_appearance_proposals_from_candidates(
                   NULL, 1, &proposal, 1) == 0,
           "motion appearance proposal builder: NULL candidates rejected");
    EXPECT(anomaly_motion_estimator_build_appearance_proposals_from_candidates(
                   &candidate, 1, NULL, 1) == 0,
           "motion appearance proposal builder: NULL output rejected");
    EXPECT(anomaly_motion_estimator_build_appearance_proposals_from_candidates(
                   &candidate, 0, &proposal, 1) == 0,
           "motion appearance proposal builder: nonpositive count rejected");
    EXPECT(anomaly_motion_estimator_build_appearance_proposals_from_candidates(
                   &candidate, 1, &proposal, 0) == 0,
           "motion appearance proposal builder: nonpositive capacity rejected");
}

static void test_motion_estimator_build_appearance_proposals_clamps_to_capacity(void) {
    anomaly_motion_candidate_t candidates[3];
    anomaly_motion_appearance_proposal_t proposals[2];
    memset(candidates, 0, sizeof(candidates));
    memset(proposals, 0, sizeof(proposals));
    for (int i = 0; i < 3; i++) {
        candidates[i].sg_x = 10 + i;
        candidates[i].pixel_x = 100 + i;
    }

    int copied = anomaly_motion_estimator_build_appearance_proposals_from_candidates(
            candidates, 3, proposals, 2);

    EXPECT(copied == 2,
           "motion appearance proposal builder: count clamps to output capacity");
    EXPECT(proposals[0].sg_x == 10 && proposals[1].sg_x == 11,
           "motion appearance proposal builder: capacity clamp copies first candidates");
}

static void test_motion_estimator_build_appearance_proposals_clamps_to_contract_max(void) {
    anomaly_motion_candidate_t candidates[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2];
    anomaly_motion_appearance_proposal_t proposals[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2];
    memset(candidates, 0, sizeof(candidates));
    memset(proposals, 0, sizeof(proposals));
    for (int i = 0; i < ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2; i++) {
        candidates[i].sg_y = 20 + i;
        candidates[i].pixel_y = 200 + i;
    }

    int copied = anomaly_motion_estimator_build_appearance_proposals_from_candidates(
            candidates,
            ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2,
            proposals,
            ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2);

    EXPECT(copied == ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS,
           "motion appearance proposal builder: count clamps to contract maximum");
    EXPECT(proposals[copied - 1].sg_y == 20 + ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS - 1,
           "motion appearance proposal builder: max clamp keeps final copied candidate");
}

static void test_motion_estimator_build_appearance_proposals_copies_candidate_fields(void) {
    anomaly_motion_candidate_t candidate;
    memset(&candidate, 0, sizeof(candidate));
    candidate.sg_x = 5;
    candidate.sg_y = 6;
    candidate.pixel_x = 50;
    candidate.pixel_y = 60;
    candidate.proposal_score = 7.5f;
    candidate.thermal_score = 3.25f;
    candidate.color_score = 4.25f;
    anomaly_motion_appearance_proposal_t proposal;
    memset(&proposal, 0, sizeof(proposal));

    int copied = anomaly_motion_estimator_build_appearance_proposals_from_candidates(
            &candidate, 1, &proposal, 1);

    EXPECT(copied == 1,
           "motion appearance proposal builder: single candidate copied");
    EXPECT(proposal.sg_x == 5 && proposal.sg_y == 6,
           "motion appearance proposal builder: sample-grid fields copied");
    EXPECT(proposal.pixel_x == 50 && proposal.pixel_y == 60,
           "motion appearance proposal builder: pixel fields copied");
    EXPECT_NEAR(proposal.proposal_score, 7.5f, 0.0001f,
                "motion appearance proposal builder: proposal score copied");
    EXPECT_NEAR(proposal.thermal_score, 3.25f, 0.0001f,
                "motion appearance proposal builder: thermal score copied");
    EXPECT_NEAR(proposal.color_score, 4.25f, 0.0001f,
                "motion appearance proposal builder: color score copied");
}

static void test_motion_estimator_mirror_appearance_support_null_out_safe(void) {
    anomaly_motion_estimator_mirror_appearance_support_output(
            NULL, 0, NULL, NULL, NULL, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, NULL);

    EXPECT(true, "motion appearance mirror: NULL output is safe");
}

static void test_motion_estimator_mirror_appearance_support_copies_global_stats_without_inputs(void) {
    anomaly_motion_appearance_scorer_output_t out;
    memset(&out, 0xA5, sizeof(out));

    anomaly_motion_estimator_mirror_appearance_support_output(
            NULL, 3, NULL, NULL, NULL, 1.25f, 2.5f, 0.75f, 0.5f, 1.5f, &out);

    EXPECT(!out.valid, "motion appearance mirror: missing proposals/support stays invalid");
    EXPECT(out.score_count == 0,
           "motion appearance mirror: missing proposals/support leaves score count zero");
    EXPECT_NEAR(out.global_motion_mean, 1.25f, 0.0001f,
                "motion appearance mirror: copies global motion mean without inputs");
    EXPECT_NEAR(out.global_motion_std, 2.5f, 0.0001f,
                "motion appearance mirror: copies global motion std without inputs");
    EXPECT_NEAR(out.global_motion_load, 0.75f, 0.0001f,
                "motion appearance mirror: copies global motion load without inputs");
    EXPECT_NEAR(out.zoom_motion_scale, 0.5f, 0.0001f,
                "motion appearance mirror: copies zoom scale without inputs");
    EXPECT_NEAR(out.broad_motion_scale, 1.5f, 0.0001f,
                "motion appearance mirror: copies broad scale without inputs");
}

static void test_motion_estimator_mirror_appearance_support_clamps_count(void) {
    anomaly_motion_appearance_proposal_t proposals[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2];
    float support[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2];
    memset(proposals, 0, sizeof(proposals));
    for (int i = 0; i < ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2; i++) {
        proposals[i].pixel_x = 10 + i;
        proposals[i].pixel_y = 20 + i;
        support[i] = 0.25f + (float)i;
    }
    anomaly_motion_appearance_scorer_output_t out;

    anomaly_motion_estimator_mirror_appearance_support_output(
            proposals, ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS + 2,
            support, NULL, NULL, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, &out);

    EXPECT(out.score_count == ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS,
           "motion appearance mirror: clamps score count to contract maximum");
    EXPECT(out.valid, "motion appearance mirror: clamped positive count is valid");
}

static void test_motion_estimator_mirror_appearance_support_falls_back_to_proposal_pixels(void) {
    anomaly_motion_appearance_proposal_t proposals[1];
    memset(proposals, 0, sizeof(proposals));
    proposals[0].pixel_x = 123;
    proposals[0].pixel_y = 456;
    float support[1] = { 0.9f };
    int support_x[1] = { 0 };
    int support_y[1] = { 0 };
    anomaly_motion_appearance_scorer_output_t out;

    anomaly_motion_estimator_mirror_appearance_support_output(
            proposals, 1, support, support_x, support_y,
            0.0f, 0.0f, 0.0f, 1.0f, 1.0f, &out);

    EXPECT(out.scores[0].pixel_x == 123 && out.scores[0].pixel_y == 456,
           "motion appearance mirror: zero support coordinates fall back to proposal pixels");
}

static void test_motion_estimator_mirror_appearance_support_uses_nonzero_support_pixels(void) {
    anomaly_motion_appearance_proposal_t proposals[1];
    memset(proposals, 0, sizeof(proposals));
    proposals[0].pixel_x = 123;
    proposals[0].pixel_y = 456;
    float support[1] = { 0.9f };
    int support_x[1] = { 321 };
    int support_y[1] = { 654 };
    anomaly_motion_appearance_scorer_output_t out;

    anomaly_motion_estimator_mirror_appearance_support_output(
            proposals, 1, support, support_x, support_y,
            0.0f, 0.0f, 0.0f, 1.0f, 1.0f, &out);

    EXPECT(out.scores[0].pixel_x == 321 && out.scores[0].pixel_y == 654,
           "motion appearance mirror: nonzero support coordinates are used");
}

static void test_motion_estimator_mirror_appearance_support_winner_uses_highest_positive(void) {
    anomaly_motion_appearance_proposal_t proposals[4];
    float support[4] = { -0.2f, 0.0f, 0.4f, 1.2f };
    memset(proposals, 0, sizeof(proposals));
    for (int i = 0; i < 4; i++) {
        proposals[i].pixel_x = 100 + i;
        proposals[i].pixel_y = 200 + i;
    }
    anomaly_motion_appearance_scorer_output_t out;

    anomaly_motion_estimator_mirror_appearance_support_output(
            proposals, 4, support, NULL, NULL, 0.0f, 0.0f, 0.0f, 1.0f, 1.0f, &out);

    EXPECT(!out.scores[0].valid && !out.scores[1].valid,
           "motion appearance mirror: nonpositive support scores are invalid");
    EXPECT(out.scores[2].valid && out.scores[3].valid,
           "motion appearance mirror: positive support scores are valid");
    EXPECT(out.winner.valid && out.winner.pixel_x == 103 && out.winner.pixel_y == 203,
           "motion appearance mirror: winner comes from highest positive support");
    EXPECT_NEAR(out.winner.score, 1.2f, 0.0001f,
                "motion appearance mirror: winner score is highest positive support");
}

static void test_motion_estimator_snapshot_rejects_invalid_input(void) {
    anomaly_motion_movement_snapshot_t null_snapshot =
        anomaly_motion_estimator_make_movement_snapshot(NULL);
    anomaly_debug_movement_tile_t tile;
    memset(&tile, 0, sizeof(tile));

    EXPECT(!null_snapshot.valid,
           "motion snapshot: NULL movement returns invalid snapshot");
    EXPECT(null_snapshot.movement == NULL,
           "motion snapshot: NULL movement has no backing store");
    EXPECT_NEAR(null_snapshot.suppression_scale, 1.0f, 0.0001f,
                "motion snapshot: invalid snapshot defaults suppression scale");
    EXPECT(!anomaly_motion_estimator_query_snapshot_at_norm(
                &null_snapshot, 0.5f, 0.5f, &tile),
           "motion snapshot: invalid snapshot query returns false");
    EXPECT(!anomaly_motion_estimator_query_snapshot_at_norm(
                NULL, 0.5f, 0.5f, &tile),
           "motion snapshot: NULL snapshot query returns false");
    EXPECT(!anomaly_motion_estimator_query_snapshot_at_norm(
                &null_snapshot, 0.5f, 0.5f, NULL),
           "motion snapshot: NULL tile output query returns false");

    anomaly_debug_movement_t movement;
    memset(&movement, 0, sizeof(movement));
    anomaly_motion_movement_snapshot_t invalid_snapshot =
        anomaly_motion_estimator_make_movement_snapshot(&movement);

    EXPECT(!invalid_snapshot.valid,
           "motion snapshot: invalid movement returns invalid snapshot");
    EXPECT(invalid_snapshot.movement == &movement,
           "motion snapshot: invalid movement still records backing store");
    EXPECT(!anomaly_motion_estimator_query_snapshot_at_norm(
                &invalid_snapshot, 0.5f, 0.5f, &tile),
           "motion snapshot: invalid movement query returns false");
}

static void test_motion_estimator_snapshot_queries_valid_tile(void) {
    anomaly_debug_movement_t movement;
    memset(&movement, 0, sizeof(movement));
    movement.valid = true;
    movement.sample_count = 1;
    movement.tile_cols = ANOMALY_MOVEMENT_GRID_COLS;
    movement.tile_rows = ANOMALY_MOVEMENT_GRID_ROWS;
    movement.confidence = 0.72f;
    movement.parallax_load = 0.31f;
    movement.local_outlier_load = 0.18f;
    movement.parallax_suppression_scale = 0.87f;

    const int tile_idx = 10;
    movement.tiles[tile_idx].valid = true;
    movement.tiles[tile_idx].center_x_norm = 0.25f;
    movement.tiles[tile_idx].center_y_norm = 0.40f;
    movement.tiles[tile_idx].dx_px = 3.0f;
    movement.tiles[tile_idx].dy_px = -2.0f;
    movement.tiles[tile_idx].residual_px = 19.0f;
    movement.tiles[tile_idx].confidence = 0.66f;
    movement.tiles[tile_idx].layer_class = ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;

    anomaly_motion_movement_snapshot_t snapshot =
        anomaly_motion_estimator_make_movement_snapshot(&movement);
    anomaly_debug_movement_tile_t queried_tile;
    memset(&queried_tile, 0, sizeof(queried_tile));

    EXPECT(snapshot.valid, "motion snapshot: valid movement returns valid snapshot");
    EXPECT(snapshot.movement == &movement,
           "motion snapshot: valid snapshot points at backing movement");
    EXPECT(snapshot.sample_count == movement.sample_count,
           "motion snapshot: sample count mirrors movement");
    EXPECT(snapshot.tile_cols == movement.tile_cols,
           "motion snapshot: tile cols mirror movement");
    EXPECT(snapshot.tile_rows == movement.tile_rows,
           "motion snapshot: tile rows mirror movement");
    EXPECT_NEAR(snapshot.confidence, movement.confidence, 0.0001f,
                "motion snapshot: confidence mirrors movement");
    EXPECT_NEAR(snapshot.parallax_load, movement.parallax_load, 0.0001f,
                "motion snapshot: parallax load mirrors movement");
    EXPECT_NEAR(snapshot.local_outlier_load, movement.local_outlier_load, 0.0001f,
                "motion snapshot: local outlier load mirrors movement");
    EXPECT_NEAR(snapshot.suppression_scale, movement.parallax_suppression_scale, 0.0001f,
                "motion snapshot: suppression scale mirrors movement");
    EXPECT(anomaly_motion_estimator_query_snapshot_at_norm(
                &snapshot, 0.26f, 0.39f, &queried_tile),
           "motion snapshot: valid normalized query finds nearest tile");
    EXPECT(queried_tile.valid, "motion snapshot: queried tile remains valid");
    EXPECT_NEAR(queried_tile.dx_px, movement.tiles[tile_idx].dx_px, 0.0001f,
                "motion snapshot: queried tile dx matches");
    EXPECT_NEAR(queried_tile.dy_px, movement.tiles[tile_idx].dy_px, 0.0001f,
                "motion snapshot: queried tile dy matches");
    EXPECT_NEAR(queried_tile.residual_px, movement.tiles[tile_idx].residual_px, 0.0001f,
                "motion snapshot: queried tile residual matches");
    EXPECT(queried_tile.layer_class == ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER,
           "motion snapshot: queried tile layer class matches");
}

static void test_motion_estimator_tile_classification_null_and_invalid(void) {
    anomaly_debug_movement_tile_t tile;
    memset(&tile, 0, sizeof(tile));
    tile.valid = false;
    tile.residual_px = 40.0f;
    tile.dx_px = 24.0f;
    tile.layer_class = ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;

    EXPECT_NEAR(anomaly_motion_estimator_tile_independent_score(NULL), 0.0f, 0.0001f,
                "motion tile classification: NULL tile scores zero");
    EXPECT_NEAR(anomaly_motion_estimator_tile_independent_score(&tile), 0.0f, 0.0001f,
                "motion tile classification: invalid tile scores zero");
    EXPECT(!anomaly_motion_estimator_tile_is_independent(NULL, 1.0f),
           "motion tile classification: NULL tile is not independent");
    EXPECT(!anomaly_motion_estimator_tile_is_independent(&tile, 1.0f),
           "motion tile classification: invalid tile is not independent");
    EXPECT(!anomaly_motion_estimator_tile_is_parallax_like(NULL),
           "motion tile classification: NULL tile is not parallax-like");
    EXPECT(!anomaly_motion_estimator_tile_is_parallax_like(&tile),
           "motion tile classification: invalid tile is not parallax-like");
}

static void test_motion_estimator_tile_classification_local_outlier_independent(void) {
    anomaly_debug_movement_tile_t tile;
    memset(&tile, 0, sizeof(tile));
    tile.valid = true;
    tile.residual_px = 40.0f;
    tile.dx_px = 24.0f;
    tile.dy_px = 0.0f;
    tile.layer_class = ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER;

    float independent_score = anomaly_motion_estimator_tile_independent_score(&tile);

    EXPECT_NEAR(independent_score, 1.0f, 0.0001f,
                "motion tile classification: local outlier high residual/flow scores one");
    EXPECT(anomaly_motion_estimator_tile_is_independent(&tile, independent_score),
           "motion tile classification: local outlier above threshold is independent");
    EXPECT(!anomaly_motion_estimator_tile_is_parallax_like(&tile),
           "motion tile classification: local outlier is not parallax-like");
}

static void test_motion_estimator_tile_classification_parallax_like_layers(void) {
    anomaly_debug_movement_tile_t tile;
    memset(&tile, 0, sizeof(tile));
    tile.valid = true;
    tile.layer_class = ANOMALY_MOVEMENT_LAYER_BACKGROUND;

    EXPECT(anomaly_motion_estimator_tile_is_parallax_like(&tile),
           "motion tile classification: background tile is parallax-like");
    EXPECT(!anomaly_motion_estimator_tile_is_independent(&tile, 1.0f),
           "motion tile classification: background tile is not independent");

    tile.layer_class = ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR;
    EXPECT(anomaly_motion_estimator_tile_is_parallax_like(&tile),
           "motion tile classification: coherent-near tile is parallax-like");
    EXPECT(!anomaly_motion_estimator_tile_is_independent(&tile, 1.0f),
           "motion tile classification: coherent-near tile is not independent");
}

static void test_motion_estimator_tile_classification_unstable_partial_score(void) {
    anomaly_debug_movement_tile_t tile;
    memset(&tile, 0, sizeof(tile));
    tile.valid = true;
    tile.residual_px = 12.0f;
    tile.dx_px = 0.0f;
    tile.dy_px = 0.0f;
    tile.layer_class = ANOMALY_MOVEMENT_LAYER_UNSTABLE;

    float independent_score = anomaly_motion_estimator_tile_independent_score(&tile);

    EXPECT_NEAR(independent_score, 0.07f, 0.0001f,
                "motion tile classification: unstable layer contributes partial score");
    EXPECT(!anomaly_motion_estimator_tile_is_independent(&tile, independent_score),
           "motion tile classification: unstable tile is not independent");
    EXPECT(!anomaly_motion_estimator_tile_is_parallax_like(&tile),
           "motion tile classification: unstable tile is not parallax-like");
}

static void test_motion_estimator_nearest_support_invalid_and_absent(void) {
    const float support[] = {0.0f, -2.0f, 3.0f};
    const int support_x[] = {10, 11, 80};
    const int support_y[] = {10, 11, 80};

    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    NULL, support_x, support_y, 3, 100, 100, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: NULL support returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, NULL, support_y, 3, 100, 100, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: NULL support_x returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, support_x, NULL, 3, 100, 100, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: NULL support_y returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, support_x, support_y, 0, 100, 100, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: nonpositive count returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, support_x, support_y, 3, 0, 100, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: nonpositive frame width returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, support_x, support_y, 3, 100, 0, 0.1f, 0.1f, 0.05f),
                -1.0f, 0.0001f,
                "motion nearest support: nonpositive frame height returns -1");
    EXPECT_NEAR(anomaly_motion_estimator_nearest_candidate_support_norm(
                    support, support_x, support_y, 3, 100, 100, 0.1f, 0.1f, 0.01f),
                -1.0f, 0.0001f,
                "motion nearest support: no nearby positive support returns -1");
}

static void test_motion_estimator_nearest_support_picks_strongest_nearby(void) {
    const float support[] = {1.2f, 3.4f, 9.0f, -4.0f};
    const int support_x[] = {50, 52, 90, 50};
    const int support_y[] = {50, 48, 90, 51};

    float nearest = anomaly_motion_estimator_nearest_candidate_support_norm(
            support, support_x, support_y, 4, 100, 100, 0.5f, 0.5f, 0.05f);

    EXPECT_NEAR(nearest, 3.4f, 0.0001f,
                "motion nearest support: strongest nearby positive support wins");
}

static void test_motion_estimator_stamp_support_invalid_noop(void) {
    float motion[] = {0.25f, 0.50f, 0.75f, 1.00f};
    float registration[] = {0.90f, 0.80f, 0.70f, 0.60f};

    anomaly_motion_estimator_stamp_support(NULL, registration, 2, 2, 0, 0, 2.0f, 0.2f);
    anomaly_motion_estimator_stamp_support(motion, registration, 0, 2, 0, 0, 2.0f, 0.2f);
    anomaly_motion_estimator_stamp_support(motion, registration, 2, 0, 0, 0, 2.0f, 0.2f);
    anomaly_motion_estimator_stamp_support(motion, registration, 2, 2, 0, 0, 0.0f, 0.2f);
    anomaly_motion_estimator_stamp_support(motion, registration, 2, 2, 0, 0, -1.0f, 0.2f);

    EXPECT_NEAR(motion[0], 0.25f, 0.0001f,
                "motion stamp: invalid inputs leave motion[0] unchanged");
    EXPECT_NEAR(motion[1], 0.50f, 0.0001f,
                "motion stamp: invalid inputs leave motion[1] unchanged");
    EXPECT_NEAR(motion[2], 0.75f, 0.0001f,
                "motion stamp: invalid inputs leave motion[2] unchanged");
    EXPECT_NEAR(motion[3], 1.00f, 0.0001f,
                "motion stamp: invalid inputs leave motion[3] unchanged");
    EXPECT_NEAR(registration[0], 0.90f, 0.0001f,
                "motion stamp: invalid inputs leave registration[0] unchanged");
    EXPECT_NEAR(registration[3], 0.60f, 0.0001f,
                "motion stamp: invalid inputs leave registration[3] unchanged");
}

static void test_motion_estimator_stamp_support_scales_and_registration_min(void) {
    float motion[9];
    float registration[9];
    for (int i = 0; i < 9; i++) {
        motion[i] = 0.0f;
        registration[i] = 0.8f;
    }
    motion[0] = 1.5f;

    anomaly_motion_estimator_stamp_support(motion, registration, 3, 3, 1, 1, 2.0f, 0.3f);

    EXPECT_NEAR(motion[4], 2.0f, 0.0001f,
                "motion stamp: center receives full support");
    EXPECT_NEAR(motion[1], 1.1f, 0.0001f,
                "motion stamp: neighbor receives scaled support");
    EXPECT_NEAR(motion[0], 1.5f, 0.0001f,
                "motion stamp: existing stronger neighbor support is preserved");
    EXPECT_NEAR(registration[4], 0.3f, 0.0001f,
                "motion stamp: registration center lowers to smaller scale");
    EXPECT_NEAR(registration[8], 0.3f, 0.0001f,
                "motion stamp: registration neighbor lowers to smaller scale");

    motion[4] = 3.0f;
    registration[4] = 0.2f;
    anomaly_motion_estimator_stamp_support(motion, registration, 3, 3, 1, 1, 1.0f, 0.6f);

    EXPECT_NEAR(motion[4], 3.0f, 0.0001f,
                "motion stamp: existing stronger center support is preserved");
    EXPECT_NEAR(registration[4], 0.2f, 0.0001f,
                "motion stamp: registration is not raised by larger scale");

    anomaly_motion_estimator_stamp_support(motion, NULL, 3, 3, 1, 1, 4.0f, 0.1f);
    EXPECT_NEAR(motion[4], 4.0f, 0.0001f,
                "motion stamp: NULL registration map still stamps motion support");
}

// ── anomaly_config_transition_classify tests ───────────────────────────────

static void test_config_transition_unchanged(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_config_t after = before;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_UNCHANGED,
           "config transition: unchanged configs classify unchanged");
}

static void test_config_transition_display_only(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_config_t after = before;
    after.show_hot_overlay = !after.show_hot_overlay;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_DISPLAY_ONLY,
           "config transition: display-only change classifies display-only");
}

static void test_config_transition_debug_only(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_COLOR);
    anomaly_config_t after = before;
    after.color_debug_target_enabled = true;
    after.color_debug_target_x_norm = 0.45f;
    after.color_debug_target_y_norm = 0.55f;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_DEBUG_ONLY,
           "config transition: debug-only change classifies debug-only");
}

static void test_config_transition_live_update(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_config_t after = before;
    after.score_threshold += 0.25f;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_LIVE_UPDATE,
           "config transition: live processing change classifies live update");
}

static void test_config_transition_reset_sensitive(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_COLOR);
    anomaly_config_t after = before;
    after.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "config transition: reset-sensitive change classifies reset");
}

static void test_config_transition_reset_wins(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_THERMAL);
    anomaly_config_t after = before;
    after.show_candidate_blobs = !after.show_candidate_blobs;
    after.thermal_debug_target_enabled = true;
    after.score_threshold += 0.25f;
    after.pixel_step += 1;

    EXPECT(anomaly_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "config transition: reset-sensitive change wins over lower classes");
}

static void test_config_transition_null_requires_reset(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);

    EXPECT(anomaly_config_transition_classify(NULL, &cfg) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "config transition: NULL before requires reset");
    EXPECT(anomaly_config_transition_classify(&cfg, NULL) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "config transition: NULL after requires reset");
    EXPECT(anomaly_config_transition_classify(NULL, NULL) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "config transition: NULL before and after requires reset");
}

static void test_runtime_config_transition_contract_matches_public_wrapper(void) {
    anomaly_config_t before = default_cfg(ANOMALY_ALGO_COLOR);
    anomaly_config_t after = before;
    after.scan_zone = 0.75f;

    EXPECT(anomaly_runtime_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE,
           "runtime config transition: direct classifier sees reset-sensitive changes");
    EXPECT(anomaly_runtime_config_transition_classify(&before, &after) ==
               anomaly_config_transition_classify(&before, &after),
           "runtime config transition: public wrapper matches runtime classifier");

    after = before;
    after.score_threshold += ANOMALY_RUNTIME_CONFIG_FLOAT_EPSILON * 0.5f;
    EXPECT(anomaly_runtime_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_UNCHANGED,
           "runtime config transition: sub-epsilon float changes are unchanged");
    after.score_threshold = before.score_threshold + ANOMALY_RUNTIME_CONFIG_FLOAT_EPSILON * 2.0f;
    EXPECT(anomaly_runtime_config_transition_classify(&before, &after) ==
               ANOMALY_CONFIG_TRANSITION_LIVE_UPDATE,
           "runtime config transition: above-epsilon score changes are live updates");
}

static void test_runtime_config_movement_mode_normalization(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);

    EXPECT(anomaly_runtime_normalize_movement_estimator_mode(NULL) ==
               ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE,
           "runtime config: NULL movement estimator mode defaults to legacy affine");
    cfg.movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    EXPECT(anomaly_runtime_normalize_movement_estimator_mode(&cfg) ==
               ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE,
           "runtime config: layered active movement estimator mode is preserved");
    cfg.movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW;
    EXPECT(anomaly_runtime_normalize_movement_estimator_mode(&cfg) ==
               ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW,
           "runtime config: layered shadow movement estimator mode is preserved");
    cfg.movement_estimator_mode = 99;
    EXPECT(anomaly_runtime_normalize_movement_estimator_mode(&cfg) ==
               ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE,
           "runtime config: invalid movement estimator mode falls back to legacy affine");
}

static void test_runtime_config_effective_target_and_thermal_values(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL);
    cfg.small_target_screen_fraction = 1.0f;
    cfg.min_area_fraction = 0.01f;

    EXPECT_NEAR(anomaly_runtime_effective_color_target_span_px(&cfg, 100, 100), 10.0f, 0.0001f,
                "runtime config: color target span uses configured area fraction");
    cfg.min_area_fraction = -1.0f;
    EXPECT_NEAR(anomaly_runtime_effective_color_target_span_px(&cfg, 100, 100),
                sqrtf(ANOMALY_DEFAULT_MIN_AREA_FRACTION * 10000.0f),
                0.0001f,
                "runtime config: invalid color area fraction falls back to default");
    cfg.min_area_fraction = 1.0f;
    cfg.small_target_screen_fraction = 0.10f;
    EXPECT_NEAR(anomaly_runtime_effective_color_target_span_px(&cfg, 100, 100),
                sqrtf(200.0f),
                0.0001f,
                "runtime config: color target span is capped by small-target thermal span");

    EXPECT_NEAR(anomaly_runtime_effective_thermal_min_delta(NULL), ANOMALY_THERMAL_MIN_DELTA, 0.0001f,
                "runtime config: NULL thermal min delta uses default");
    cfg.thermal_min_delta = 17.5f;
    EXPECT_NEAR(anomaly_runtime_effective_thermal_min_delta(&cfg), 17.5f, 0.0001f,
                "runtime config: positive thermal min delta is preserved");
    cfg.thermal_min_delta = 0.0f;
    EXPECT_NEAR(anomaly_runtime_effective_thermal_min_delta(&cfg), ANOMALY_THERMAL_MIN_DELTA, 0.0001f,
                "runtime config: nonpositive thermal min delta uses default");
}

static void test_runtime_config_effective_sample_steps(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);

    EXPECT(anomaly_runtime_effective_small_target_sample_step_cap(&cfg, 640, 480) == 2,
           "runtime config: small target sample cap preserves legacy compact-frame cap");
    EXPECT(anomaly_runtime_effective_sample_step(&cfg, 640, 480) == 2,
           "runtime config: VGA default sample step is 2");
    EXPECT(anomaly_runtime_effective_sample_step(&cfg, 1280, 720) == 3,
           "runtime config: HD default sample step is capped by small-target span");
    EXPECT(anomaly_runtime_effective_motion_sample_step(&cfg, 640, 480) == 2,
           "runtime config: VGA motion sample step floor is 2");

    cfg.pixel_step = 99;
    EXPECT(anomaly_runtime_effective_sample_step(&cfg, 1920, 1080) == 5,
           "runtime config: explicit pixel step clamps to small-target cap");
    cfg.pixel_step = 1;
    EXPECT(anomaly_runtime_effective_sample_step(&cfg, 4000, 3000) == 3,
           "runtime config: explicit pixel step respects memory minimum");
    EXPECT(anomaly_runtime_effective_motion_sample_step(&cfg, 1280, 720) == 3,
           "runtime config: HD motion sample step preserves coarser motion floor");
}

static void test_runtime_config_motion_evidence_scale(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);

    EXPECT_NEAR(anomaly_runtime_effective_motion_evidence_scale(NULL), 1.0f, 0.0001f,
                "runtime config: NULL motion evidence scale defaults to 1");
    cfg.motion_evidence_scale = 2.5f;
    EXPECT_NEAR(anomaly_runtime_effective_motion_evidence_scale(&cfg), 2.5f, 0.0001f,
                "runtime config: nominal motion evidence scale is preserved");
    cfg.motion_evidence_scale = 0.01f;
    EXPECT_NEAR(anomaly_runtime_effective_motion_evidence_scale(&cfg), 0.10f, 0.0001f,
                "runtime config: low motion evidence scale clamps to floor");
    cfg.motion_evidence_scale = 99.0f;
    EXPECT_NEAR(anomaly_runtime_effective_motion_evidence_scale(&cfg), 4.0f, 0.0001f,
                "runtime config: high motion evidence scale clamps to ceiling");
    cfg.motion_evidence_scale = NAN;
    EXPECT_NEAR(anomaly_runtime_effective_motion_evidence_scale(&cfg), 1.0f, 0.0001f,
                "runtime config: nonfinite motion evidence scale defaults to 1");
}

static anomaly_thermal_shadow_shape_t valid_thermal_shadow_shape(void) {
    anomaly_thermal_shadow_shape_t shape = {0};
    shape.local_peak_movement_tile_valid = true;
    shape.movement_motion_support = 1.2f;
    shape.local_peak_movement_motion_support = 0.4f;
    shape.target_spatial_score = 6.0f;
    shape.local_peak_raw_spatial_score = 5.5f;
    shape.micro_candidate_ring_hot_fraction = 0.10f;
    shape.micro_candidate_compactness = 0.65f;
    shape.micro_candidate_one_sided_support = 0.20f;
    shape.local_window_raw_delta_mean = 4.0f;
    shape.micro_candidate_hot_count = 2;
    shape.local_window_hot_count = 3;
    shape.micro_candidate_centroid_offset = 0.8f;
    return shape;
}

static void test_thermal_shadow_rescue_score_and_eligibility(void) {
    float score = anomaly_thermal_shadow_raw_delta_rescue_score(
            20.0f,
            19.0f,
            1.0f,
            1.0f,
            0.80f,
            1.10f,
            4.0f,
            5.0f,
            10.0f,
            4.0f,
            0.80f,
            true,
            true);
    EXPECT_NEAR(score, 1.0f, 0.0001f,
                "thermal shadow: strong rescue score clamps to 1");
    EXPECT(anomaly_thermal_shadow_raw_delta_rescue_eligible(
            13.0f, 2.0f, 2.5f, 0.50f, 4.0f, 5.0f, 10.0f, true),
           "thermal shadow: compact independent below-threshold candidate is eligible");
    EXPECT(!anomaly_thermal_shadow_raw_delta_rescue_eligible(
            13.0f, 5.0f, 2.5f, 0.50f, 4.0f, 5.0f, 10.0f, true),
           "thermal shadow: over-area candidate is not eligible");
    EXPECT(!anomaly_thermal_shadow_raw_delta_rescue_eligible(
            13.0f, 2.0f, 2.5f, 0.50f, 4.0f, 5.0f, 10.0f, false),
           "thermal shadow: candidate without independent movement is not eligible");
}

static void test_thermal_shadow_movement_reject_reason(void) {
    anomaly_thermal_shadow_shape_t shape = valid_thermal_shadow_shape();
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_NONE,
           "thermal shadow: valid movement shadow shape is accepted");
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(NULL, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE,
           "thermal shadow: NULL shape rejects as no movement tile");

    shape = valid_thermal_shadow_shape();
    shape.local_peak_movement_tile_valid = false;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE,
           "thermal shadow: invalid movement tile rejects");
    shape = valid_thermal_shadow_shape();
    shape.movement_motion_support = 0.2f;
    shape.local_peak_movement_motion_support = 0.3f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOTION_SUPPORT,
           "thermal shadow: weak motion support rejects");
    shape = valid_thermal_shadow_shape();
    shape.target_spatial_score = 2.0f;
    shape.local_peak_raw_spatial_score = 3.0f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_WEAK_THERMAL,
           "thermal shadow: weak thermal support rejects");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_ring_hot_fraction = -1.0f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_NO_LOCAL_SHAPE,
           "thermal shadow: missing local shape metrics reject");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_ring_hot_fraction = 0.30f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_RING_HOT,
           "thermal shadow: hot ring rejects");
    shape = valid_thermal_shadow_shape();
    shape.local_window_raw_delta_mean = 12.0f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_LOCAL_MEAN_HOT,
           "thermal shadow: hot local mean rejects");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_hot_count = 11;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_TOO_MANY_HOT,
           "thermal shadow: too many hot samples rejects");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_compactness = 0.30f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_LOW_COMPACTNESS,
           "thermal shadow: low compactness rejects");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_one_sided_support = 0.70f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_EDGE_LIKE,
           "thermal shadow: edge-like support rejects");
    shape = valid_thermal_shadow_shape();
    shape.micro_candidate_centroid_offset = 3.5f;
    EXPECT(anomaly_thermal_shadow_movement_reject_reason(&shape, 5.0f) ==
               ANOMALY_MOVEMENT_SHADOW_REJECT_CENTROID_DRIFT,
           "thermal shadow: centroid drift rejects");
}

static void test_registration_prefilter_luma_grid_edges(void) {
    const uint8_t src[9] = {
        0, 16, 32,
        64, 128, 192,
        224, 240, 255
    };
    const uint8_t expected[9] = {
        23, 44, 65,
        98, 128, 158,
        191, 212, 232
    };
    uint8_t tmp[9] = {0};
    uint8_t dst[9] = {0};

    anomaly_registration_prefilter_luma_grid(src, 3, 3, tmp, dst);
    for (int i = 0; i < 9; i++) {
        EXPECT(dst[i] == expected[i],
               "registration prefilter: separable edge-clamped blur matches expected");
    }
}

static void test_registration_prefilter_luma_grid_degenerate_dimensions(void) {
    const uint8_t src[1] = {123};
    uint8_t tmp[1] = {0};
    uint8_t dst[1] = {0};

    anomaly_registration_prefilter_luma_grid(src, 1, 1, tmp, dst);
    EXPECT(tmp[0] == 123,
           "registration prefilter: 1x1 horizontal pass preserves value");
    EXPECT(dst[0] == 123,
           "registration prefilter: 1x1 vertical pass preserves value");
}

static void test_registration_prefilter_luma_grid_invalid_inputs_noop(void) {
    const uint8_t src[4] = {1, 2, 3, 4};
    uint8_t tmp[4] = {10, 11, 12, 13};
    uint8_t dst[4] = {20, 21, 22, 23};

    anomaly_registration_prefilter_luma_grid(NULL, 2, 2, tmp, dst);
    anomaly_registration_prefilter_luma_grid(src, 0, 2, tmp, dst);
    anomaly_registration_prefilter_luma_grid(src, 2, -1, tmp, dst);
    EXPECT(tmp[0] == 10 && tmp[3] == 13,
           "registration prefilter: invalid inputs leave scratch unchanged");
    EXPECT(dst[0] == 20 && dst[3] == 23,
           "registration prefilter: invalid inputs leave output unchanged");
}

static void test_registration_health_confidence_values(void) {
    EXPECT_NEAR(anomaly_registration_health_confidence(ANOMALY_REG_HEALTH_HEALTHY), 1.0f, 0.0001f,
                "registration health confidence: healthy maps to 1.0");
    EXPECT_NEAR(anomaly_registration_health_confidence(ANOMALY_REG_HEALTH_SOFT_DEGRADED), 0.60f, 0.0001f,
                "registration health confidence: soft degraded maps to 0.60");
    EXPECT_NEAR(anomaly_registration_health_confidence(ANOMALY_REG_HEALTH_HARD_DEGRADED), 0.25f, 0.0001f,
                "registration health confidence: hard degraded maps to 0.25");
    EXPECT_NEAR(anomaly_registration_health_confidence(ANOMALY_REG_HEALTH_INVALID), 0.0f, 0.0001f,
                "registration health confidence: invalid maps to 0.0");
    EXPECT_NEAR(anomaly_registration_health_confidence(ANOMALY_REG_HEALTH_UNKNOWN), 0.10f, 0.0001f,
                "registration health confidence: unknown maps to 0.10");
    EXPECT_NEAR(anomaly_registration_health_confidence((anomaly_registration_health_t)99), 0.10f, 0.0001f,
                "registration health confidence: unrecognized health maps to unknown confidence");
}

static anomaly_registration_model_t valid_registration_health_model(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 2, 1);
    model.debug_valid = true;
    model.similarity.valid = true;
    model.similarity.a = 1.0f;
    model.similarity.b = 0.0f;
    model.similarity.mean_residual = 0.0f;
    model.affine[0] = 1.0f;
    model.affine[1] = 0.0f;
    model.affine[2] = 0.0f;
    model.affine[3] = 0.0f;
    model.affine[4] = 1.0f;
    model.affine[5] = 0.0f;
    return model;
}

static void test_registration_classify_health_contract(void) {
    anomaly_registration_model_t model = valid_registration_health_model();

    EXPECT(anomaly_registration_classify_health(NULL, 100, 100) ==
               ANOMALY_REG_HEALTH_UNKNOWN,
           "registration health classify: NULL model is unknown");
    model.debug_valid = false;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_UNKNOWN,
           "registration health classify: debug-invalid model is unknown");

    model = valid_registration_health_model();
    model.similarity.valid = false;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_INVALID,
           "registration health classify: fit-invalid model is invalid");
    model = valid_registration_health_model();
    model.scene_discontinuity = true;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_INVALID,
           "registration health classify: scene discontinuity is invalid");

    model = valid_registration_health_model();
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_HEALTHY,
           "registration health classify: nominal model is healthy");
    model.similarity.mean_residual = ANOMALY_GMV_RESIDUAL_THRESH;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_SOFT_DEGRADED,
           "registration health classify: elevated residual is soft degraded");
    model.similarity.mean_residual = ANOMALY_GMV_RESIDUAL_THRESH * 2.0f;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_HARD_DEGRADED,
           "registration health classify: high residual is hard degraded");

    model = valid_registration_health_model();
    model.similarity.a = 0.85f;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_SOFT_DEGRADED,
           "registration health classify: soft scale drift is soft degraded");
    model.similarity.a = 0.75f;
    EXPECT(anomaly_registration_classify_health(&model, 100, 100) ==
               ANOMALY_REG_HEALTH_HARD_DEGRADED,
           "registration health classify: far scale drift is hard degraded");
}

static anomaly_registration_model_t reusable_registration_cache_model(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 3, 5);
    model.debug_valid = true;
    model.similarity.valid = true;
    model.similarity.a = 1.0f;
    model.similarity.b = 0.0f;
    model.similarity.tx = 0.01f;
    model.similarity.ty = -0.02f;
    model.similarity.mean_residual = 0.003f;
    model.affine[0] = 1.0f;
    model.affine[1] = 0.01f;
    model.affine[2] = 0.02f;
    model.affine[3] = -0.01f;
    model.affine[4] = 1.0f;
    model.affine[5] = -0.03f;
    model.anchor_count = 16;
    model.tracked_match_count = 60;
    model.invalid_reason = ANOMALY_REG_INVALID_REASON_NONE;
    model.fit_det = 1.0f;
    model.fit_min_scale = 1.0f;
    model.fit_max_scale = 1.0f;
    model.fit_anchor_residual_std = 0.002f;
    model.fit_anchor_residual_max = 0.010f;
    model.fit_motion_dx_std = 0.003f;
    model.fit_motion_dy_std = 0.004f;
    model.fit_quadrant_residual_spread = 0.003f;
    return model;
}

static void test_registration_cache_invalid_store_clears_validity(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    state.cached_registration_valid = true;
    state.cached_registration_reuse_budget = 2;

    anomaly_registration_cache_store(
            &state,
            NULL,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_TARGET_ONLY);

    EXPECT(!state.cached_registration_valid,
           "registration cache: NULL model clears cached validity");
    EXPECT(state.cached_registration_reuse_budget == 0,
           "registration cache: NULL model clears reuse budget");
}

static void test_registration_cache_store_sets_budget_and_copies_fields(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    anomaly_registration_model_t model = reusable_registration_cache_model();

    anomaly_registration_cache_store(
            &state,
            &model,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_TARGET_ONLY);

    EXPECT(state.cached_registration_valid,
           "registration cache: valid model marks cache valid");
    EXPECT(state.cached_registration_reuse_budget == 2,
           "registration cache: very stable target-only model gets budget 2");
    EXPECT(state.cached_registration_mode == ANOMALY_REGISTRATION_AFFINE &&
           state.cached_registration_sample_step == 3 &&
           state.cached_registration_motion_step == 5,
           "registration cache: mode and steps are copied");
    EXPECT(state.cached_registration_anchor_count == 16 &&
           state.cached_registration_tracked_match_count == 60,
           "registration cache: match counts are copied");
    EXPECT_NEAR(state.cached_registration_affine[2], 0.02f, 0.0001f,
                "registration cache: affine translation is copied");
    EXPECT_NEAR(state.cached_registration_similarity_tx, 0.01f, 0.0001f,
                "registration cache: similarity tx is copied");
    EXPECT_NEAR(state.cached_registration_fit_quadrant_residual_spread, 0.003f, 0.0001f,
                "registration cache: fit residual spread is copied");

    model.tracked_match_count = 50;
    model.fit_anchor_residual_max = 0.030f;
    anomaly_registration_cache_store(
            &state,
            &model,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_PARTIAL);
    EXPECT(state.cached_registration_reuse_budget == 1,
           "registration cache: stable partial model gets budget 1");

    model.fit_det = 0.970f;
    anomaly_registration_cache_store(
            &state,
            &model,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_PARTIAL);
    EXPECT(state.cached_registration_reuse_budget == 0,
           "registration cache: unstable affine gets budget 0");
}

static void test_registration_cache_try_load_copies_model_and_decrements_budget(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    uint8_t prev_luma[4] = {0};
    state.prev_registration_luma = prev_luma;
    state.prev_registration_luma_width = 22;
    state.prev_registration_luma_height = 11;
    anomaly_registration_model_t stored = reusable_registration_cache_model();
    anomaly_registration_cache_store(
            &state,
            &stored,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_TARGET_ONLY);

    anomaly_registration_model_t loaded;
    memset(&loaded, 0, sizeof(loaded));
    EXPECT(anomaly_registration_cache_try_load(
            &loaded,
            &state,
            ANOMALY_REGISTRATION_AFFINE,
            3,
            5,
            22,
            11),
           "registration cache: matching cache gates load");
    EXPECT(state.cached_registration_reuse_budget == 1,
           "registration cache: successful load decrements reuse budget");
    EXPECT(loaded.debug_valid && loaded.similarity.valid,
           "registration cache: loaded model is debug and fit valid");
    EXPECT(loaded.anchor_count == stored.anchor_count &&
           loaded.tracked_match_count == stored.tracked_match_count,
           "registration cache: loaded model copies counts");
    EXPECT_NEAR(loaded.affine[5], stored.affine[5], 0.0001f,
                "registration cache: loaded model copies affine");
    EXPECT_NEAR(loaded.fit_motion_dy_std, stored.fit_motion_dy_std, 0.0001f,
                "registration cache: loaded model copies fit stats");
}

static void test_registration_cache_try_load_rejects_gate_mismatch(void) {
    anomaly_state_t state;
    memset(&state, 0, sizeof(state));
    uint8_t prev_luma[4] = {0};
    state.prev_registration_luma = prev_luma;
    state.prev_registration_luma_width = 22;
    state.prev_registration_luma_height = 11;
    anomaly_registration_model_t stored = reusable_registration_cache_model();
    anomaly_registration_cache_store(
            &state,
            &stored,
            ANOMALY_REG_HEALTH_HEALTHY,
            ANOMALY_RESCAN_MODE_TARGET_ONLY);

    anomaly_registration_model_t loaded;
    memset(&loaded, 0, sizeof(loaded));
    EXPECT(!anomaly_registration_cache_try_load(
            &loaded,
            &state,
            ANOMALY_REGISTRATION_GMV,
            3,
            5,
            22,
            11),
           "registration cache: non-affine mode does not load");
    EXPECT(state.cached_registration_reuse_budget == 2,
           "registration cache: failed load does not decrement budget");
    EXPECT(!anomaly_registration_cache_try_load(
            &loaded,
            &state,
            ANOMALY_REGISTRATION_AFFINE,
            3,
            5,
            23,
            11),
           "registration cache: dimension mismatch does not load");
}

static void test_registration_model_make_defaults(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 3, 5);

    EXPECT(model.mode == ANOMALY_REGISTRATION_AFFINE,
           "registration model: make preserves mode");
    EXPECT(model.sample_step == 3 && model.motion_step == 5,
           "registration model: make preserves sample and motion steps");
    EXPECT_NEAR(model.affine[0], 1.0f, 0.0001f,
                "registration model: make initializes affine m00 identity");
    EXPECT_NEAR(model.affine[4], 1.0f, 0.0001f,
                "registration model: make initializes affine m11 identity");
    EXPECT_NEAR(model.similarity.a, 1.0f, 0.0001f,
                "registration model: make initializes similarity scale");
    EXPECT(!model.similarity.valid,
           "registration model: make starts invalid until estimator marks fit valid");
    EXPECT(model.invalid_reason == ANOMALY_REG_INVALID_REASON_NONE,
           "registration model: make initializes invalid reason to none");
}

static void test_registration_model_normalize_and_valid(void) {
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.registration_mode = ANOMALY_REGISTRATION_AFFINE;
    EXPECT(anomaly_registration_normalize_mode(&cfg) == ANOMALY_REGISTRATION_AFFINE,
           "registration model: normalize preserves affine mode");
    cfg.registration_mode = 99;
    EXPECT(anomaly_registration_normalize_mode(&cfg) == ANOMALY_REGISTRATION_GMV,
           "registration model: normalize falls back to GMV");
    EXPECT(anomaly_registration_normalize_mode(NULL) == ANOMALY_REGISTRATION_GMV,
           "registration model: normalize NULL falls back to GMV");

    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_GMV, 2, 4);
    EXPECT(!anomaly_registration_model_valid(&model),
           "registration model: invalid while similarity is not valid");
    model.similarity.valid = true;
    EXPECT(anomaly_registration_model_valid(&model),
           "registration model: valid when similarity is valid");
    EXPECT(!anomaly_registration_model_valid(NULL),
           "registration model: NULL model is invalid");
}

static void test_registration_model_scale_and_apply(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 1, 2);
    model.similarity.a = 3.0f;
    model.similarity.b = 4.0f;
    EXPECT_NEAR(anomaly_registration_model_scale(&model), 5.0f, 0.0001f,
                "registration model: scale uses similarity vector magnitude");
    EXPECT_NEAR(anomaly_registration_model_scale(NULL), 1.0f, 0.0001f,
                "registration model: NULL scale defaults to 1");

    model.affine[0] = 2.0f;
    model.affine[1] = 0.5f;
    model.affine[2] = 0.1f;
    model.affine[3] = -0.25f;
    model.affine[4] = 1.5f;
    model.affine[5] = -0.2f;
    float x = 0.0f;
    float y = 0.0f;
    anomaly_registration_apply_point(&model, 0.2f, 0.4f, &x, &y);
    EXPECT_NEAR(x, 0.7f, 0.0001f,
                "registration model: affine apply computes x");
    EXPECT_NEAR(y, 0.35f, 0.0001f,
                "registration model: affine apply computes y");
    anomaly_registration_apply_point(NULL, 0.2f, 0.4f, &x, &y);
    EXPECT_NEAR(x, 0.2f, 0.0001f,
                "registration model: NULL apply copies x");
    EXPECT_NEAR(y, 0.4f, 0.0001f,
                "registration model: NULL apply copies y");
}

static void test_registration_model_inverse_round_trip(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 1, 3);
    model.affine[0] = 1.2f;
    model.affine[1] = 0.1f;
    model.affine[2] = 0.05f;
    model.affine[3] = -0.2f;
    model.affine[4] = 0.9f;
    model.affine[5] = -0.03f;

    float px = 0.0f;
    float py = 0.0f;
    anomaly_registration_apply_point(&model, 0.35f, 0.60f, &px, &py);
    float rx = 0.0f;
    float ry = 0.0f;
    EXPECT(anomaly_registration_invert_point(&model, px, py, &rx, &ry),
           "registration model: direct inverse accepts nonsingular affine");
    EXPECT_NEAR(rx, 0.35f, 0.0001f,
                "registration model: direct inverse round-trips x");
    EXPECT_NEAR(ry, 0.60f, 0.0001f,
                "registration model: direct inverse round-trips y");

    anomaly_inverse_affine_t inv = anomaly_registration_inverse_affine(&model);
    EXPECT(inv.valid,
           "registration model: cached inverse accepts nonsingular affine");
    EXPECT(anomaly_registration_invert_point_fast(&inv, px, py, &rx, &ry),
           "registration model: cached inverse projects point");
    EXPECT_NEAR(rx, 0.35f, 0.0001f,
                "registration model: cached inverse round-trips x");
    EXPECT_NEAR(ry, 0.60f, 0.0001f,
                "registration model: cached inverse round-trips y");
}

static void test_registration_model_inverse_rejects_singular(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 1, 3);
    model.affine[0] = 1.0f;
    model.affine[1] = 2.0f;
    model.affine[3] = 0.5f;
    model.affine[4] = 1.0f;
    float x = 7.0f;
    float y = 8.0f;

    EXPECT(!anomaly_registration_invert_point(&model, 0.5f, 0.5f, &x, &y),
           "registration model: direct inverse rejects singular affine");
    anomaly_inverse_affine_t inv = anomaly_registration_inverse_affine(&model);
    EXPECT(!inv.valid,
           "registration model: cached inverse rejects singular affine");
    EXPECT(!anomaly_registration_invert_point_fast(&inv, 0.5f, 0.5f, &x, &y),
           "registration model: invalid cached inverse rejects projection");
}

static void test_registration_model_motion_search_gate(void) {
    anomaly_registration_model_t model =
        anomaly_registration_model_make(ANOMALY_REGISTRATION_AFFINE, 1, 4);
    model.affine[2] = 0.05f;
    model.affine[5] = 0.0f;

    EXPECT_NEAR(anomaly_registration_max_corner_displacement(&model, 101, 101), 0.05f, 0.0001f,
                "registration model: max corner displacement reports affine translation");
    EXPECT(!anomaly_registration_motion_exceeds_search(&model, 101, 101, 1.0f),
           "registration model: small motion stays inside search window");

    model.affine[2] = 0.90f;
    model.affine[5] = 0.90f;
    EXPECT(anomaly_registration_motion_exceeds_search(&model, 101, 101, 1.0f),
           "registration model: large motion exceeds search window");
    model.motion_step = 0;
    EXPECT(!anomaly_registration_motion_exceeds_search(&model, 101, 101, 1.0f),
           "registration model: nonpositive motion step disables search-exceeded gate");
}

static void test_linear_solve_3x3_solution_and_pivot(void) {
    const float a[3][3] = {
        {0.0f, 2.0f, 1.0f},
        {1.0f, -2.0f, -3.0f},
        {-1.0f, 1.0f, 2.0f}
    };
    const float b[3] = {8.0f, -10.0f, 5.0f};
    float out[3] = {0.0f, 0.0f, 0.0f};

    EXPECT(anomaly_linear_solve_3x3(a, b, out),
           "linear solve 3x3: pivoted nonsingular system solves");
    EXPECT_NEAR(out[0], 2.0f, 0.0001f,
                "linear solve 3x3: x solution matches expected");
    EXPECT_NEAR(out[1], 3.0f, 0.0001f,
                "linear solve 3x3: y solution matches expected");
    EXPECT_NEAR(out[2], 2.0f, 0.0001f,
                "linear solve 3x3: z solution matches expected");
}

static void test_linear_solve_3x3_singular_rejects(void) {
    const float a[3][3] = {
        {1.0f, 2.0f, 3.0f},
        {2.0f, 4.0f, 6.0f},
        {3.0f, 6.0f, 9.0f}
    };
    const float b[3] = {1.0f, 2.0f, 3.0f};
    float out[3] = {7.0f, 8.0f, 9.0f};

    EXPECT(!anomaly_linear_solve_3x3(a, b, out),
           "linear solve 3x3: singular system is rejected");
}

static void test_linear_solve_6x6_solution_and_pivot(void) {
    const float a[6][6] = {
        {0.0f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        {2.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f},
        {0.0f, 0.0f, 3.0f, 0.0f, 0.0f, 0.0f},
        {0.0f, 0.0f, 0.0f, 4.0f, 0.0f, 0.0f},
        {0.0f, 0.0f, 0.0f, 0.0f, 5.0f, 0.0f},
        {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 6.0f}
    };
    const float b[6] = {2.0f, 2.0f, 9.0f, 16.0f, 25.0f, 36.0f};
    float out[6] = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

    EXPECT(anomaly_linear_solve_6x6(a, b, out),
           "linear solve 6x6: pivoted nonsingular system solves");
    EXPECT_NEAR(out[0], 1.0f, 0.0001f,
                "linear solve 6x6: x0 solution matches expected");
    EXPECT_NEAR(out[1], 2.0f, 0.0001f,
                "linear solve 6x6: x1 solution matches expected");
    EXPECT_NEAR(out[2], 3.0f, 0.0001f,
                "linear solve 6x6: x2 solution matches expected");
    EXPECT_NEAR(out[3], 4.0f, 0.0001f,
                "linear solve 6x6: x3 solution matches expected");
    EXPECT_NEAR(out[4], 5.0f, 0.0001f,
                "linear solve 6x6: x4 solution matches expected");
    EXPECT_NEAR(out[5], 6.0f, 0.0001f,
                "linear solve 6x6: x5 solution matches expected");
}

static void test_linear_solve_6x6_singular_rejects(void) {
    float a[6][6] = {{0.0f}};
    float b[6] = {0.0f};
    float out[6] = {1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f};
    for (int i = 0; i < 6; i++) {
        a[i][i] = 1.0f;
        b[i] = (float)(i + 1);
    }
    a[4][4] = 0.0f;

    EXPECT(!anomaly_linear_solve_6x6((const float (*)[6])a, b, out),
           "linear solve 6x6: singular system is rejected");
}

// ── fit_similarity_2d tests ────────────────────────────────────────────────

static void test_similarity_pure_translation(void) {
    // 4 points all shifted by (+0.1, -0.05)
    float sx[4] = {0.2f, 0.8f, 0.2f, 0.8f};
    float sy[4] = {0.2f, 0.2f, 0.8f, 0.8f};
    float dx[4], dy[4];
    for (int i = 0; i < 4; i++) { dx[i] = sx[i] + 0.1f; dy[i] = sy[i] - 0.05f; }

    similarity_2d_t r = fit_similarity_2d(sx, sy, dx, dy, 4);
    EXPECT(r.valid, "pure-translation: valid");
    EXPECT_NEAR(r.a,  1.0f, 0.001f, "pure-translation: a≈1");
    EXPECT_NEAR(r.b,  0.0f, 0.001f, "pure-translation: b≈0");
    EXPECT_NEAR(r.tx, 0.1f, 0.001f, "pure-translation: tx≈0.1");
    EXPECT_NEAR(r.ty,-0.05f,0.001f, "pure-translation: ty≈-0.05");
    EXPECT_NEAR(r.mean_residual, 0.0f, 0.001f, "pure-translation: residual≈0");
}

static void test_similarity_pure_rotation_90(void) {
    // 90° CCW rotation about (0.5, 0.5):
    //   x' = -(y - 0.5) + 0.5 = 1 - y
    //   y' =  (x - 0.5) + 0.5 = x
    // In our transform T(curr)=prev: dst = R*src
    //   a*x - b*y + tx = 1-y  →  a=0, b=1, tx=1, ty=0... let's verify via fit.
    float sx[4] = {0.5f, 1.0f, 0.5f, 0.0f};
    float sy[4] = {0.0f, 0.5f, 1.0f, 0.5f};
    float dx[4], dy[4];
    for (int i = 0; i < 4; i++) {
        dx[i] = 1.0f - sy[i];
        dy[i] = sx[i];
    }

    similarity_2d_t r = fit_similarity_2d(sx, sy, dx, dy, 4);
    EXPECT(r.valid, "90deg-rotation: valid");
    EXPECT_NEAR(r.a, 0.0f, 0.001f, "90deg-rotation: a≈0");
    EXPECT_NEAR(r.b, 1.0f, 0.001f, "90deg-rotation: b≈1");
    EXPECT_NEAR(r.mean_residual, 0.0f, 0.001f, "90deg-rotation: residual≈0");
    // Scale should be 1.
    float scale = sqrtf(r.a * r.a + r.b * r.b);
    EXPECT_NEAR(scale, 1.0f, 0.001f, "90deg-rotation: scale≈1");
}

static void test_similarity_degenerate_n1(void) {
    float sx[1] = {0.5f}, sy[1] = {0.5f};
    float dx[1] = {0.6f}, dy[1] = {0.5f};
    similarity_2d_t r = fit_similarity_2d(sx, sy, dx, dy, 1);
    EXPECT(!r.valid, "n=1: not valid");
}

static void test_similarity_degenerate_collinear(void) {
    // All points on x=0.5 line → denominator D collapses.
    float sx[4] = {0.5f, 0.5f, 0.5f, 0.5f};
    float sy[4] = {0.1f, 0.3f, 0.6f, 0.9f};
    float dx[4] = {0.6f, 0.6f, 0.6f, 0.6f};
    float dy[4] = {0.1f, 0.3f, 0.6f, 0.9f};
    similarity_2d_t r = fit_similarity_2d(sx, sy, dx, dy, 4);
    // D = n*S - (Sx²+Sy²); with all xi=0.5 and yi varying,
    // Sx = 4*0.5=2, Sx²=4, S = 4*0.25+Σyi².  D may or may not be zero
    // depending on the yi spread.  Just verify it doesn't crash.
    (void)r;  // result may or may not be valid — just must not crash
    EXPECT(1, "collinear: did not crash");
}

// ── Anomaly detection tests ────────────────────────────────────────────────

static void test_uniform_no_detection(void) {
    // Uniform gray: no pixel stands out → no detection box.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    EXPECT(boxes == 0, "uniform: no boxes on uniform gray");
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_thermal_hotspot_detected(void) {
    // Center pixel much brighter than uniform background → thermal hotspot.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 64);
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            set_pixel(frame, W * 4, W / 2 + dx, H / 2 + dy, 255, 255, 255);
        }
    }

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    EXPECT(boxes > 0, "thermal: bright center pixel detected");
    if (boxes > 0) {
        // Box should be centered roughly in the middle of the frame.
        float cx = (res.boxes[0].left_norm + res.boxes[0].right_norm) * 0.5f;
        float cy = (res.boxes[0].top_norm  + res.boxes[0].bottom_norm) * 0.5f;
        EXPECT(cx > 0.2f && cx < 0.8f, "thermal: box centered horizontally");
        EXPECT(cy > 0.2f && cy < 0.8f, "thermal: box centered vertically");
    }
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_outlier_detected(void) {
    // Compact red patch in gray scene → color outlier with local support.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    stamp_color_rect(frame, W * 4, W, H, W / 2 - 1, H / 2 - 1, 4, 4, 220, 20, 20);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes > 0, "color: red patch in gray scene detected");
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_dense_verifier_rejects_sparse_sampled_impostor(void) {
    // Four isolated red pixels can line up on adjacent sampled cells and look
    // like a compact coarse-grid blob, but there is no dense pixel blob to
    // support them. The dense verifier should reject this impostor.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    set_pixel(frame, W * 4, W / 2 - 1, H / 2 - 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 + 1, H / 2 - 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 - 1, H / 2 + 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 + 1, H / 2 + 1, 220, 20, 20);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "color dense verifier: isolated sampled pixels do not become a blob");
    EXPECT(res.color_debug.candidate_count == 0,
           "color dense verifier: impostor component is rejected before candidate retention");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_dense_peak_seed_rejects_sparse_impostor_fresh_rgba(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    set_pixel(frame, W * 4, W / 2 - 1, H / 2 - 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 + 1, H / 2 - 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 - 1, H / 2 + 1, 220, 20, 20);
    set_pixel(frame, W * 4, W / 2 + 1, H / 2 + 1, 220, 20, 20);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    cfg.small_target_screen_fraction = 1.0f / 20.0f;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "color dense peak seed: sparse fresh-rgba impostor rejected");
    EXPECT(res.color_debug.candidate_count == 0,
           "color dense peak seed: sparse fresh-rgba impostor retains no candidate");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_dense_span_reject_reports_measured_area(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    stamp_color_rect(frame, W * 4, W, H, W / 2 - 14, H / 2 - 1, 28, 3, 220, 20, 20);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    cfg.small_target_screen_fraction = 1.0f / 20.0f;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "color dense span reject: elongated same-color stripe is rejected");
    EXPECT(res.color_debug.strongest_reject_reason == ANOMALY_COLOR_BLOB_REJECT_AREA,
           "color dense span reject: strongest reject is reported as area");
    EXPECT(res.color_debug.strongest_reject_area > 0.0f,
           "color dense span reject: measured dense area is preserved in telemetry");
    EXPECT(res.color_debug.strongest_reject_span > 0.0f,
           "color dense span reject: measured dense span is preserved in telemetry");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_fresh_compact_unique_blob_survives(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    stamp_color_rect(frame, W * 4, W, H, W / 2 - 2, H / 2 - 2, 5, 5, 235, 24, 24);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    cfg.small_target_screen_fraction = 1.0f / 20.0f;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes > 0, "color fresh blob-first: compact unique color blob survives");
    EXPECT(res.color_debug.candidate_count > 0,
           "color fresh blob-first: compact unique color blob is retained as a candidate");
    EXPECT(res.color_debug.winning_candidate_index >= 0,
           "color fresh blob-first: compact unique color blob can win");
    if (res.color_debug.winning_candidate_index >= 0 &&
        res.color_debug.winning_candidate_index < res.color_debug.candidate_count) {
        const anomaly_debug_color_candidate_t *winner =
            &res.color_debug.candidates[res.color_debug.winning_candidate_index];
        float cx = (winner->bbox_left_norm + winner->bbox_right_norm) * 0.5f;
        float cy = (winner->bbox_top_norm + winner->bbox_bottom_norm) * 0.5f;
        EXPECT(fabsf(cx - 0.5f) < 0.08f && fabsf(cy - 0.5f) < 0.08f,
               "color fresh blob-first: winning compact blob stays near source patch");
        EXPECT(winner->hist_rarity_score > 0.0f,
               "color fresh blob-first: winning compact blob exposes rarity at its peak pixel");
    }

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_fresh_oversized_blob_is_not_candidate(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    stamp_color_rect(frame, W * 4, W, H, W / 2 - 18, H / 2 - 18, 36, 36, 235, 24, 24);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "color fresh blob-first: oversized same-color blob does not fire");
    EXPECT(res.color_debug.candidate_count == 0,
           "color fresh blob-first: oversized same-color blob is not retained as a candidate");
    EXPECT(res.color_debug.strongest_reject_reason == ANOMALY_COLOR_BLOB_REJECT_AREA,
           "color fresh blob-first: oversized same-color blob is rejected by the small-target area cap");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_fresh_ranking_prefers_peak_unique_blob_over_plateau(void) {
    const int W = 200, H = 160;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    stamp_color_rect(frame, W * 4, W, H, 48, 74, 16, 16, 205, 72, 72);
    stamp_color_rect(frame, W * 4, W, H, 138, 78, 5, 5, 24, 44, 245);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    cfg.small_target_screen_fraction = 1.0f / 20.0f;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes > 0, "color fresh blob-first: peak-unique compact blob can win over color plateau");
    EXPECT(res.color_debug.candidate_count > 0,
           "color fresh blob-first: peak-unique scenario retains at least one candidate");
    EXPECT(res.color_debug.winning_candidate_index >= 0,
           "color fresh blob-first: peak-unique scenario reports a winner");
    if (res.color_debug.winning_candidate_index >= 0 &&
        res.color_debug.winning_candidate_index < res.color_debug.candidate_count) {
        const anomaly_debug_color_candidate_t *winner =
            &res.color_debug.candidates[res.color_debug.winning_candidate_index];
        float cx = (winner->bbox_left_norm + winner->bbox_right_norm) * 0.5f;
        float cy = (winner->bbox_top_norm + winner->bbox_bottom_norm) * 0.5f;
        EXPECT(fabsf(cx - 0.70f) < 0.08f && fabsf(cy - 0.50f) < 0.08f,
               "color fresh blob-first: ranking follows the most unique compact blob, not the broad plateau");
        for (int i = 0; i < res.color_debug.candidate_count &&
                        i < ANOMALY_DEBUG_TOP_COLOR_CANDIDATES; i++) {
            EXPECT(winner->hist_rarity_score >= res.color_debug.candidates[i].hist_rarity_score,
                   "color fresh blob-first: winner rarity is anchored by the strongest candidate peak pixel");
        }
    }

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_black_hot_thermal(void) {
    // Black-hot polarity: dark center pixel in bright background.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 200);
    set_pixel(frame, W * 4, W / 2, H / 2, 0, 0, 0);  // black center

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.thermal_polarity = ANOMALY_THERMAL_BLACK_HOT;
    cfg.score_threshold  = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    EXPECT(boxes > 0, "black-hot: dark center pixel detected");
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_high_threshold_no_detection(void) {
    // With the normalized local thermal window, an isolated outlier in a
    // uniform field can still score strongly because the neighborhood remains
    // deliberately compact in real-pixel terms. Use a very high threshold here
    // so the test continues to verify "same outlier suppressed" without
    // depending on the exact calibration of that local window.
    //
    // The pixel must also pass ANOMALY_THERMAL_MIN_DELTA (currently 10 luma
    // units) before Z-scoring even begins — this blocks HEVC/noise artefacts
    // in near-uniform regions.  We therefore use a +20-unit outlier (well above
    // the gate) and bracket the threshold broadly:
    //   threshold = 3.0  → fires
    //   threshold = 30.0 → silent
    //
    // Use separate frame copies: process_frame draws boxes in-place, so
    // reusing the same buffer across runs would corrupt the second test.
    const int W = 160, H = 120;

    // Verify it DOES fire at low threshold (sanity check).
    {
        uint8_t *frame = make_gray_frame(W, H, 64);
        set_pixel(frame, W * 4, W / 2, H / 2, 84, 84, 84);  // +20 units, > MIN_DELTA
        anomaly_state_t st; anomaly_state_init(&st);
        anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
        cfg.score_threshold = 3.0f;
        cfg.min_hits = 1;
        anomaly_result_t res;
        int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
        EXPECT(boxes > 0, "threshold=3.0: outlier above MIN_DELTA fires");
        anomaly_state_cleanup(&st);
        free(frame);
    }

    // Verify it does NOT fire at high threshold.
    {
        uint8_t *frame = make_gray_frame(W, H, 64);
        set_pixel(frame, W * 4, W / 2, H / 2, 84, 84, 84);  // same +20-unit outlier
        anomaly_state_t st; anomaly_state_init(&st);
        anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
        cfg.score_threshold = 30.0f;
        cfg.min_hits = 1;
        anomaly_result_t res;
        int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
        EXPECT(boxes == 0, "threshold=30: same outlier suppressed");
        anomaly_state_cleanup(&st);
        free(frame);
    }
}

static void test_runtime_min_delta_override(void) {
    const int W = 160, H = 120;
    uint8_t *frame = make_gray_frame(W, H, 64);
    set_pixel(frame, W * 4, W / 2, H / 2, 84, 84, 84);  // +20 units

    anomaly_state_t st;
    anomaly_state_init(&st);
    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.thermal_min_delta = 30.0f;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "runtime min_delta override suppresses +20-unit outlier");

    anomaly_state_cleanup(&st);
    free(frame);
}

static void test_thermal_saliency_detected(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *warm_frame = make_gray_frame(W, H, 200);
    uint8_t *frame = make_gray_frame(W, H, 200);
    set_pixel(frame, W * 4, W / 2,     H / 2,     0, 0, 0);
    set_pixel(frame, W * 4, W / 2 + 2, H / 2,     8, 8, 8);
    set_pixel(frame, W * 4, W / 2,     H / 2 + 2, 6, 6, 6);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_PERSIST);
    cfg.thermal_polarity = ANOMALY_THERMAL_BLACK_HOT;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = 0;
    for (int i = 0; i < ANOMALY_THERMAL_BG_WARMUP + 1; i++) {
        anomaly_process_frame(&st, &cfg, warm_frame, W * 4, W, H, 0, &res);
    }
    for (int i = 0; i < 2; i++) {
        boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    }
    EXPECT(boxes == 0, "thermal saliency: suppressed when no motion vector is available");

    anomaly_state_cleanup(&st);
    free(warm_frame);
    free(frame);
}

static void test_unified_saliency_color_support(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    set_pixel(frame, W * 4, W / 2, H / 2, 240, 20, 20);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_PERSIST);
    cfg.score_threshold = 1.2f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
    EXPECT(boxes == 0, "unified saliency: suppressed when no motion vector is available");

    anomaly_state_cleanup(&st);
    free(frame);
}

static void test_min_hits_gate(void) {
    // min_hits=2: first frame should not show a box, second should.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 2;
    cfg.frame_stride = 1;

    uint8_t *frame = make_gray_frame(W, H, 64);
    set_pixel(frame, W * 4, W / 2, H / 2, 255, 255, 255);

    anomaly_result_t res1, res2;
    int boxes1 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res1);
    int boxes2 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res2);

    EXPECT(boxes1 == 0, "min_hits=2: no box on first hit");
    EXPECT(boxes2 > 0,  "min_hits=2: box appears on second hit");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_scan_zone_excludes_corner(void) {
    // scan_zone=0.5: outlier at frame corner (0,0) should be excluded.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 64);
    set_pixel(frame, W * 4, 0, 0, 255, 255, 255);  // bright pixel at corner

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.scan_zone = 0.5f;  // only scan central 50%
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    EXPECT(boxes == 0, "scan_zone=0.5: corner outlier excluded");
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_motion_static_scene(void) {
    // Same frame twice → no real motion → no motion box.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 100);
    // Add a few texture patches so the GMV block-matcher has something to work with.
    for (int y = 20; y < 100; y += 20)
        for (int x = 20; x < 140; x += 20)
            set_pixel(frame, W * 4, x, y, 200, 200, 200);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.motion_evidence_scale = 2.0f;

    anomaly_result_t res1, res2;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res1);  // establishes prev_luma
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res2);

    EXPECT(boxes == 0, "motion: identical frames produce no motion box");
    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_motion_moving_patch(void) {
    // A bright 3×3 patch moves 40px between frames; static texture elsewhere.
    //
    // Pixel layout must be motion-grid-aligned (motion_step=4 for frames
    // below 720p) so the bright patch actually lands in the luma grid.
    // Frame is 320×240; motion_step=4; bright patch y=88 (grid y=22).
    const int W = 320, H = 240;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    // Place 3×3 bright patches at grid-aligned coords (multiple of motion_step=4).
    // Frame 1: patch at x=160, y=88
    uint8_t *f1 = make_gray_frame(W, H, 80);
    for (int y = 20; y < H - 20; y += 40)
        for (int x = 20; x < W - 20; x += 40) {
            set_pixel(f1, W * 4, x, y, 120, 120, 120);
            if (x + 4 < W) set_pixel(f1, W * 4, x + 4, y, 40, 40, 40);
            if (y + 4 < H) set_pixel(f1, W * 4, x, y + 4, 200, 200, 200);
        }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            set_pixel(f1, W * 4, 160 + dx, 88 + dy, 240, 240, 240);

    // Frame 2: patch jumped to x=120, y=88 (moved 40px left; x=120 is grid-aligned)
    uint8_t *f2 = make_gray_frame(W, H, 80);
    for (int y = 20; y < H - 20; y += 40)
        for (int x = 20; x < W - 20; x += 40) {
            set_pixel(f2, W * 4, x, y, 120, 120, 120);
            if (x + 4 < W) set_pixel(f2, W * 4, x + 4, y, 40, 40, 40);
            if (y + 4 < H) set_pixel(f2, W * 4, x, y + 4, 200, 200, 200);
        }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            set_pixel(f2, W * 4, 120 + dx, 88 + dy, 240, 240, 240);

    anomaly_result_t r1, r2;
    anomaly_process_frame(&st, &cfg, f1, W * 4, W, H, 0, &r1);  // prime prev_luma
    int boxes = anomaly_process_frame(&st, &cfg, f2, W * 4, W, H, 0, &r2);

    EXPECT(boxes > 0, "motion: moving bright patch produces motion box");

    free(f1);
    free(f2);
    anomaly_state_cleanup(&st);
}

static void test_motion_moving_patch_affine_registration(void) {
    const int W = 320, H = 240;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.registration_mode = ANOMALY_REGISTRATION_AFFINE;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    uint8_t *f1 = make_gray_frame(W, H, 80);
    for (int y = 20; y < H - 20; y += 40)
        for (int x = 20; x < W - 20; x += 40) {
            set_pixel(f1, W * 4, x, y, 120, 120, 120);
            if (x + 4 < W) set_pixel(f1, W * 4, x + 4, y, 40, 40, 40);
            if (y + 4 < H) set_pixel(f1, W * 4, x, y + 4, 200, 200, 200);
        }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            set_pixel(f1, W * 4, 160 + dx, 88 + dy, 240, 240, 240);

    uint8_t *f2 = make_gray_frame(W, H, 80);
    for (int y = 20; y < H - 20; y += 40)
        for (int x = 20; x < W - 20; x += 40) {
            set_pixel(f2, W * 4, x, y, 120, 120, 120);
            if (x + 4 < W) set_pixel(f2, W * 4, x + 4, y, 40, 40, 40);
            if (y + 4 < H) set_pixel(f2, W * 4, x, y + 4, 200, 200, 200);
        }
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            set_pixel(f2, W * 4, 120 + dx, 88 + dy, 240, 240, 240);

    anomaly_result_t r1, r2;
    anomaly_process_frame(&st, &cfg, f1, W * 4, W, H, 0, &r1);
    int boxes = anomaly_process_frame(&st, &cfg, f2, W * 4, W, H, 0, &r2);

    EXPECT(boxes > 0, "motion-affine: moving bright patch produces motion box");
    EXPECT(r2.gmv_debug.valid, "motion-affine: registration debug valid");

    free(f1);
    free(f2);
    anomaly_state_cleanup(&st);
}

static void test_accumulator_hold_after_miss(void) {
    // Detection fires on frame 1, disappears on frame 2.
    // Accumulator hold should keep the box visible for ANOMALY_ACC_HOLD_FRAMES more frames.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    uint8_t *hotspot_frame = make_gray_frame(W, H, 64);
    set_pixel(hotspot_frame, W * 4, W / 2, H / 2, 255, 255, 255);
    uint8_t *plain_frame   = make_gray_frame(W, H, 64);

    anomaly_result_t res;
    // Prime with a hotspot hit.
    anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res);
    // Now switch to plain frame — accumulator should hold for several frames.
    int held = 0;
    for (int i = 0; i < ANOMALY_ACC_HOLD_FRAMES; i++) {
        int b = anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res);
        if (b > 0) held++;
    }
    EXPECT(held > 0, "hold: box persists at least 1 frame after detection disappears");

    free(hotspot_frame);
    free(plain_frame);
    anomaly_state_cleanup(&st);
}

static void test_frame_stride_gates_full_refresh_only(void) {
    // frame_stride=3: Color only admits fresh ROI evidence on cadence frames.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 1.2f;
    cfg.min_hits = 1;
    cfg.frame_stride = 3;

    uint8_t *frame = make_gray_frame(W, H, 64);
    stamp_color_rect(frame, W * 4, W, H, W / 2 - 1, H / 2 - 1, 4, 4, 220, 20, 20);

    anomaly_result_t res;
    int b1 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=1, full
    int b2 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=2, hold
    anomaly_rescan_mode_t frame2_mode = res.rescan_mode;
    bool frame2_refreshed = res.appearance_refresh_ran_this_frame;
    int b3 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=3, full

    EXPECT(b1 > 0, "stride=3: frame 1 full-refresh analyzes");
    EXPECT(b2 >= 0, "stride=3: frame 2 may carry existing boxes");
    EXPECT(frame2_mode == ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP ||
           frame2_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY,
           "stride=3: Color frame 2 avoids broad partial refresh");
    EXPECT(!frame2_refreshed, "stride=3: Color frame 2 skips appearance refresh");
    EXPECT(b3 > 0, "stride=3: frame 3 cadence full-refresh analyzes");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_color_stride_hold_blocks_new_non_cadence_roi(void) {
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 1.2f;
    cfg.min_hits = 1;
    cfg.frame_stride = 3;

    uint8_t *plain_frame = make_gray_frame(W, H, 64);
    uint8_t *color_frame = make_gray_frame(W, H, 64);
    stamp_color_rect(color_frame, W * 4, W, H, W / 2 - 1, H / 2 - 1, 4, 4, 220, 20, 20);

    anomaly_result_t res;
    int b1 = anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res); // full
    int b2 = anomaly_process_frame(&st, &cfg, color_frame, W * 4, W, H, 0, &res); // hold
    anomaly_rescan_mode_t frame2_mode = res.rescan_mode;
    bool frame2_refreshed = res.appearance_refresh_ran_this_frame;
    int b3 = anomaly_process_frame(&st, &cfg, color_frame, W * 4, W, H, 0, &res); // full

    EXPECT(b1 == 0, "color stride hold: plain full-refresh has no ROI");
    EXPECT(b2 == 0, "color stride hold: new non-cadence color ROI is ignored");
    EXPECT(frame2_mode == ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
           "color stride hold: non-cadence frame reports stride skip");
    EXPECT(!frame2_refreshed, "color stride hold: non-cadence frame does not refresh appearance");
    EXPECT(b3 > 0, "color stride hold: cadence frame can acquire color ROI");

    free(plain_frame);
    free(color_frame);
    anomaly_state_cleanup(&st);
}

static void test_frame_stride_selective_frames_age_tracks(void) {
    // Hold lifetime should age on non-cadence selective frames too.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 3;

    uint8_t *hotspot_frame = make_gray_frame(W, H, 64);
    stamp_color_rect(hotspot_frame, W * 4, W, H, W / 2 - 1, H / 2 - 1, 4, 4, 220, 20, 20);
    uint8_t *plain_frame = make_gray_frame(W, H, 64);

    anomaly_result_t res;
    int detected = anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // full
    anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // selective
    anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // full
    EXPECT(detected > 0, "stride hold: detection established on first full-refresh frame");
    EXPECT(st.acc_active[0], "stride hold: color track active after detection");
    int hold_before_skips = st.acc_hold[0];
    EXPECT(hold_before_skips > 0, "stride hold: hold initialized with positive budget");

    anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res); // selective
    EXPECT(!res.appearance_refresh_ran_this_frame,
           "stride hold: non-cadence frame avoids full appearance refresh");
    anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res); // selective
    EXPECT(st.acc_hold[0] <= hold_before_skips,
           "stride hold: selective frames do not extend hold budget");

    free(hotspot_frame);
    free(plain_frame);
    anomaly_state_cleanup(&st);
}

static void test_frame_stride_selective_still_runs_registration(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.registration_mode = ANOMALY_REGISTRATION_GMV;
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 2;

    uint8_t *frame1 = make_gray_frame(W, H, 64);
    uint8_t *frame2 = make_gray_frame(W, H, 64);
    uint8_t *frame3 = make_gray_frame(W, H, 64);
    stamp_texture_field(frame1, W * 4, W, H, 0);
    stamp_texture_field(frame2, W * 4, W, H, 4);
    stamp_texture_field(frame3, W * 4, W, H, 8);

    anomaly_result_t r1, r2, r3;
    anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1); // full, seeds prev_luma
    anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2); // full cadence
    anomaly_process_frame(&st, &cfg, frame3, W * 4, W, H, 0, &r3); // selective, should still register

    EXPECT(r1.registration_ran_this_frame, "stride registration: initial frame reports registration pass");
    EXPECT(r1.appearance_refresh_ran_this_frame, "stride registration: initial frame runs full refresh");
    EXPECT(r2.appearance_refresh_ran_this_frame, "stride registration: analyzed frame reports appearance refresh");
    EXPECT(r3.registration_ran_this_frame, "stride registration: skipped frame still runs registration");
    EXPECT(r3.rescan_mode != ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
           "stride registration: selective frame plans an actual rescan mode");
    EXPECT(r3.gmv_debug.valid, "stride registration: skipped frame still produces registration debug");
    EXPECT(r3.registration_health != ANOMALY_REG_HEALTH_UNKNOWN,
           "stride registration: skipped frame exposes a non-unknown registration health");

    free(frame1);
    free(frame2);
    free(frame3);
    anomaly_state_cleanup(&st);
}

static void test_large_motion_discontinuity_clears_rois(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 1;

    uint8_t *frame1 = make_gray_frame(W, H, 64);
    uint8_t *frame2 = make_gray_frame(W, H, 64);
    stamp_texture_field(frame1, W * 4, W, H, 0);
    stamp_texture_field(frame2, W * 4, W, H, 220);
    stamp_color_patch(frame1, W * 4, W, H, W / 2, H / 2, 2, 255, 32, 32);

    anomaly_result_t r1, r2;
    int b1 = anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1);
    int b2 = anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2);
    EXPECT(b1 > 0, "large motion: initial hotspot detected");
    EXPECT(b2 == 0, "large motion: stale ROI cleared instead of persisting");
    EXPECT(!st.acc_active[0], "large motion: color ROI state cleared after oversized jump");
    EXPECT(r2.rescan_mode == ANOMALY_RESCAN_MODE_FULL,
           "large motion: planner falls back to full rescan on discontinuity");

    free(frame1);
    free(frame2);
    anomaly_state_cleanup(&st);
}

static void init_scan_planner_mask_state(
        anomaly_state_t *st,
        int              width,
        int              height,
        int             *prev_lookup,
        uint8_t         *valid_mask,
        anomaly_roi_cell_summary_t *cell_summaries) {
    anomaly_state_init(st);
    st->frame_counter = 3;
    st->roi_state.valid = true;
    st->roi_state.width = width;
    st->roi_state.height = height;
    st->roi_state.sample_step = 16;
    st->roi_state.cell_cols = width;
    st->roi_state.cell_rows = height;
    st->roi_state.valid_mask = valid_mask;
    st->roi_state.cell_summaries = cell_summaries;
    memset(valid_mask, 1, (size_t)width * (size_t)height);
    memset(cell_summaries, 0, (size_t)width * (size_t)height * sizeof(*cell_summaries));
    for (int i = 0; i < width * height; i++) {
        prev_lookup[i] = i;
    }
}

static int sparse_refresh_expected_count(int width, int height, int frame_counter) {
    int selected = 0;
    int sparse_phase = frame_counter % 10;
    for (int sy = 0; sy < height; sy++) {
        for (int sx = 0; sx < width; sx++) {
            if (((sx + sy + sparse_phase) % 10) == 0) selected++;
        }
    }
    return selected;
}

static void test_scan_planner_roi_grid_cell_span_contract(void) {
    EXPECT(ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX == 16,
           "scan planner ROI grid span: target size stays 16px");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(0) == 16,
           "scan planner ROI grid span: zero sample step falls back to 1");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(-4) == 16,
           "scan planner ROI grid span: negative sample step falls back to 1");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(1) == 16,
           "scan planner ROI grid span: unit sample step keeps target size");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(2) == 8,
           "scan planner ROI grid span: exact division is preserved");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(3) == 6,
           "scan planner ROI grid span: non-exact division rounds up");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(5) == 4,
           "scan planner ROI grid span: ceil target/sample formula is used");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(16) == 1,
           "scan planner ROI grid span: target-sized sample step returns one cell");
    EXPECT(anomaly_scan_planner_roi_grid_cell_span(17) == 1,
           "scan planner ROI grid span: span never drops below one");
}

static void test_frame_centered_roi_bounds_contract(void) {
    anomaly_frame_roi_bounds_t bounds =
            anomaly_frame_centered_roi_bounds(100, 60, 0.8f);
    EXPECT(bounds.x0 == 9 && bounds.x1 == 91 &&
           bounds.y0 == 5 && bounds.y1 == 55,
           "frame centered ROI: representative fractional zone matches legacy truncation");

    bounds = anomaly_frame_centered_roi_bounds(100, 60, 0.25f);
    EXPECT(bounds.x0 == 25 && bounds.x1 == 75 &&
           bounds.y0 == 15 && bounds.y1 == 45,
           "frame centered ROI: low scan zone clamps to 0.5");

    bounds = anomaly_frame_centered_roi_bounds(100, 60, 1.5f);
    EXPECT(bounds.x0 == 0 && bounds.x1 == 100 &&
           bounds.y0 == 0 && bounds.y1 == 60,
           "frame centered ROI: high scan zone clamps to full frame");

    bounds = anomaly_frame_centered_roi_bounds(1, 1, 0.5f);
    EXPECT(bounds.x0 == 0 && bounds.x1 == 1 &&
           bounds.y0 == 0 && bounds.y1 == 1,
           "frame centered ROI: valid tiny frame keeps at least one pixel");

    bounds = anomaly_frame_centered_roi_bounds(-8, 0, 0.5f);
    EXPECT(bounds.x0 == 0 && bounds.x1 == -8 &&
           bounds.y0 == 0 && bounds.y1 == 0,
           "frame centered ROI: invalid dimensions preserve legacy bounds");
}

static void test_frame_registration_roi_bounds_contract(void) {
    anomaly_frame_roi_bounds_t bounds =
            anomaly_frame_registration_roi_bounds(100, 60);
    EXPECT(bounds.x0 == 0 && bounds.x1 == 100 &&
           bounds.y0 == 0 && bounds.y1 == 60,
           "frame registration ROI: representative frame uses full-frame bounds");

    bounds = anomaly_frame_registration_roi_bounds(-8, 0);
    EXPECT(bounds.x0 == 0 && bounds.x1 == 0 &&
           bounds.y0 == 0 && bounds.y1 == 0,
           "frame registration ROI: invalid dimensions clamp max bounds to zero");
}

static void test_scan_planner_selective_refresh_helper_invalid_input(void) {
    int selected = 99;
    uint32_t reasons = 0u;
    bool ok = anomaly_scan_planner_build_selective_refresh_mask(
            NULL,
            ANOMALY_RESCAN_MODE_PARTIAL,
            false,
            4,
            4,
            NULL,
            NULL,
            &selected,
            &reasons);
    EXPECT(!ok, "selective refresh helper: invalid input is rejected");
    EXPECT(selected == 0, "selective refresh helper: invalid input clears selected count");
    EXPECT((reasons & ANOMALY_SCAN_REASON_MASK_BUILD_FAILED) != 0u,
           "selective refresh helper: invalid input reports mask build failure");
}

static void test_scan_planner_selective_refresh_helper_partial_flags(void) {
    const int width = 4, height = 4, total = width * height;
    anomaly_state_t st;
    int prev_lookup[16];
    uint8_t valid_mask[16];
    uint8_t refresh_mask[16];
    anomaly_roi_cell_summary_t cell_summaries[16];
    init_scan_planner_mask_state(&st, width, height, prev_lookup, valid_mask, cell_summaries);

    cell_summaries[1].scan_flags = ANOMALY_SCAN_FLAG_NEW_EXPOSED;
    cell_summaries[5].scan_flags = ANOMALY_SCAN_FLAG_STALE;
    cell_summaries[10].scan_flags = ANOMALY_SCAN_FLAG_TARGET_REVISIT;

    int selected = 0;
    uint32_t reasons = 0u;
    bool ok = anomaly_scan_planner_build_selective_refresh_mask(
            &st,
            ANOMALY_RESCAN_MODE_PARTIAL,
            false,
            width,
            height,
            prev_lookup,
            refresh_mask,
            &selected,
            &reasons);
    EXPECT(ok, "selective refresh helper: partial flags build a mask");
    EXPECT(selected == 3, "selective refresh helper: partial mode selects flagged cells");
    EXPECT(count_mask_set(refresh_mask, total) == 3,
           "selective refresh helper: partial mask set count matches selected count");
    EXPECT(refresh_mask[1] && refresh_mask[5] && refresh_mask[10],
           "selective refresh helper: partial mask preserves required flag locations");
    EXPECT(reasons == 0u, "selective refresh helper: partial flag selection has no fallback reason");
}

static void test_scan_planner_selective_refresh_helper_target_only_empty(void) {
    const int width = 4, height = 4;
    anomaly_state_t st;
    int prev_lookup[16];
    uint8_t valid_mask[16];
    uint8_t refresh_mask[16];
    anomaly_roi_cell_summary_t cell_summaries[16];
    init_scan_planner_mask_state(&st, width, height, prev_lookup, valid_mask, cell_summaries);
    cell_summaries[2].scan_flags = ANOMALY_SCAN_FLAG_NEW_EXPOSED;

    int selected = 42;
    uint32_t reasons = 0u;
    bool ok = anomaly_scan_planner_build_selective_refresh_mask(
            &st,
            ANOMALY_RESCAN_MODE_TARGET_ONLY,
            false,
            width,
            height,
            prev_lookup,
            refresh_mask,
            &selected,
            &reasons);
    EXPECT(!ok, "selective refresh helper: target-only without target cells is rejected");
    EXPECT(selected == 0, "selective refresh helper: target-only empty reports zero selected");
    EXPECT((reasons & ANOMALY_SCAN_REASON_MASK_EMPTY) != 0u,
           "selective refresh helper: target-only empty reports mask empty");
}

static void test_scan_planner_selective_refresh_helper_sparse_fallback(void) {
    const int width = 5, height = 5, total = width * height;
    anomaly_state_t st;
    int prev_lookup[25];
    uint8_t valid_mask[25];
    uint8_t refresh_mask[25];
    anomaly_roi_cell_summary_t cell_summaries[25];
    init_scan_planner_mask_state(&st, width, height, prev_lookup, valid_mask, cell_summaries);

    int selected = 0;
    uint32_t reasons = 0u;
    bool ok = anomaly_scan_planner_build_selective_refresh_mask(
            &st,
            ANOMALY_RESCAN_MODE_PARTIAL,
            true,
            width,
            height,
            prev_lookup,
            refresh_mask,
            &selected,
            &reasons);
    int expected = sparse_refresh_expected_count(width, height, (int)st.frame_counter);
    EXPECT(ok, "selective refresh helper: sparse fallback builds a partial mask");
    EXPECT(selected == expected,
           "selective refresh helper: sparse fallback follows frame-counter phase");
    EXPECT(count_mask_set(refresh_mask, total) == expected,
           "selective refresh helper: sparse fallback mask count matches selected count");
    EXPECT(reasons == 0u, "selective refresh helper: sparse fallback does not force a reason flag");
}

static void test_scan_planner_selective_refresh_helper_too_broad_fallback(void) {
    const int width = 5, height = 5, total = width * height;
    anomaly_state_t st;
    int prev_lookup[25];
    uint8_t valid_mask[25];
    uint8_t refresh_mask[25];
    anomaly_roi_cell_summary_t cell_summaries[25];
    init_scan_planner_mask_state(&st, width, height, prev_lookup, valid_mask, cell_summaries);
    for (int i = 0; i < total; i++) {
        cell_summaries[i].scan_flags = ANOMALY_SCAN_FLAG_NEW_EXPOSED;
    }

    int selected = 0;
    uint32_t reasons = 0u;
    bool ok = anomaly_scan_planner_build_selective_refresh_mask(
            &st,
            ANOMALY_RESCAN_MODE_PARTIAL,
            false,
            width,
            height,
            prev_lookup,
            refresh_mask,
            &selected,
            &reasons);
    int expected = sparse_refresh_expected_count(width, height, (int)st.frame_counter);
    EXPECT(ok, "selective refresh helper: too-broad mask falls back instead of failing");
    EXPECT((reasons & ANOMALY_SCAN_REASON_MASK_TOO_BROAD) != 0u,
           "selective refresh helper: too-broad fallback reports reason flag");
    EXPECT(selected == expected,
           "selective refresh helper: too-broad fallback follows frame-counter phase");
    EXPECT(count_mask_set(refresh_mask, total) == expected,
           "selective refresh helper: too-broad fallback mask count matches selected count");
}

static void test_scan_planner_partial_mode_on_localized_exposure(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.score_threshold = 2.0f;
    cfg.frame_stride = 10;

    uint8_t *frame1 = make_gray_frame(W, H, 64);
    uint8_t *frame2 = make_gray_frame(W, H, 64);
    stamp_texture_field(frame1, W * 4, W, H, 0);
    stamp_texture_field(frame2, W * 4, W, H, 4);

    anomaly_result_t r1, r2;
    anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1);
    anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2);

    EXPECT(r2.scan_plan.valid, "partial mode: planner emits a valid scan plan");
    EXPECT(r2.rescan_mode == ANOMALY_RESCAN_MODE_PARTIAL,
           "partial mode: localized exposure maps to partial rescan");
    EXPECT(r2.scan_plan.warped_valid_fraction >= 0.80f,
           "partial mode: carried coverage stays above the full-rescan floor");
    EXPECT(r2.scan_plan.stale_fraction < 0.35f,
           "partial mode: stale coverage stays below the full-rescan threshold");
    int partial_total = st.roi_state.width * st.roi_state.height;
    int partial_fresh = count_mask_set(st.roi_state.fresh_mask, partial_total);
    int partial_carried = count_mask_set(st.roi_state.carried_mask, partial_total);
    EXPECT(partial_total > 0, "partial mode: roi state populated");
    EXPECT(partial_fresh >= 0 && partial_fresh < partial_total,
           "partial mode: refresh never broadens into a full-frame rescan");
    EXPECT(partial_carried == partial_total - partial_fresh,
           "partial mode: untouched ROI samples are carried forward");

    free(frame1);
    free(frame2);
    anomaly_state_cleanup(&st);
}

static void test_scan_planner_target_only_mode_with_active_track(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 10;

    uint8_t *frame1 = make_gray_frame(W, H, 64);
    uint8_t *frame2 = make_gray_frame(W, H, 64);
    stamp_texture_field(frame1, W * 4, W, H, 0);
    stamp_texture_field(frame2, W * 4, W, H, 0);
    stamp_color_patch(frame1, W * 4, W, H, W / 2, H / 2, 2, 255, 24, 24);
    stamp_color_patch(frame2, W * 4, W, H, W / 2, H / 2, 2, 255, 24, 24);

    anomaly_result_t r1, r2;
    int b1 = anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1);
    int b2 = anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2);
    EXPECT(b1 > 0, "target-only mode: first frame establishes an active track");
    EXPECT(b2 >= 0, "target-only mode: follow-up frame processes successfully");
    EXPECT(r2.scan_plan.target_revisit_track_count > 0,
           "target-only mode: planner sees an active target revisit hint");
    EXPECT(r2.rescan_mode == ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
           "target-only mode: Color stride gap holds instead of refreshing target-only");
    EXPECT(st.target_tracks[0].active,
           "target-only mode: explicit target track stays active");
    EXPECT(st.target_tracks[0].hit_count > 0,
           "target-only mode: explicit target track accumulates direct hits");
    int target_total = st.roi_state.width * st.roi_state.height;
    int target_fresh = count_mask_set(st.roi_state.fresh_mask, target_total);
    int target_carried = count_mask_set(st.roi_state.carried_mask, target_total);
    EXPECT(target_total > 0, "target-only mode: roi state populated");
    EXPECT(target_fresh == target_total,
           "target-only mode: Color stride hold leaves previous full-refresh ROI mask intact");
    EXPECT(target_carried == 0,
           "target-only mode: Color stride hold avoids selective ROI mutation");

    free(frame1);
    free(frame2);
    anomaly_state_cleanup(&st);
}

static void test_color_target_track_uses_dense_candidate_geometry(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 1;

    uint8_t *frame1 = make_gray_frame(W, H, 64);
    uint8_t *frame2 = make_gray_frame(W, H, 64);
    stamp_texture_field(frame1, W * 4, W, H, 0);
    stamp_texture_field(frame2, W * 4, W, H, 0);
    stamp_color_patch(frame1, W * 4, W, H, W / 2, H / 2, 2, 255, 24, 24);
    stamp_color_patch(frame2, W * 4, W, H, W / 2, H / 2, 2, 255, 24, 24);

    anomaly_result_t r1, r2;
    int b1 = anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1);
    int b2 = anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2);
    EXPECT(b1 > 0, "dense geometry: first frame establishes a color target");
    EXPECT(b2 >= 0, "dense geometry: follow-up frame processes successfully");

    const anomaly_target_track_t *track = find_active_track(&st, ANOMALY_ALGO_COLOR);
    EXPECT(track != NULL, "dense geometry: explicit color target track remains active");
    EXPECT(r2.color_debug.winning_candidate_index >= 0,
           "dense geometry: winning color candidate is available for comparison");
    if (track != NULL && r2.color_debug.winning_candidate_index >= 0) {
        int ci = r2.color_debug.winning_candidate_index;
        const anomaly_debug_color_candidate_t *dbg = &r2.color_debug.candidates[ci];
        float bbox_w = dbg->bbox_right_norm - dbg->bbox_left_norm;
        float bbox_h = dbg->bbox_bottom_norm - dbg->bbox_top_norm;
        EXPECT_NEAR(track->half_w_norm * 2.0f, bbox_w, 0.012f,
                    "dense geometry: track width follows dense candidate bbox");
        EXPECT_NEAR(track->half_h_norm * 2.0f, bbox_h, 0.012f,
                    "dense geometry: track height follows dense candidate bbox");
        EXPECT(track->half_w_norm < 0.020f && track->half_h_norm < 0.020f,
               "dense geometry: tracked footprint stays compact instead of reverting to generic square");
    }

    free(frame1);
    free(frame2);
    anomaly_state_cleanup(&st);
}

static void test_small_target_caps_sample_step(void) {
    const int W = 1280, H = 720;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.small_target_screen_fraction = 1.0f / 200.0f;

    uint8_t *frame = make_gray_frame(W, H, 96);
    anomaly_result_t res;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    int expected_sample_step = 3;
    EXPECT(res.scan_plan.sampled_width == (W + expected_sample_step - 1) / expected_sample_step,
           "small-target cap: sampled width reflects <= half-target sample spacing");
    EXPECT(res.scan_plan.sampled_height == (H + expected_sample_step - 1) / expected_sample_step,
           "small-target cap: sampled height reflects <= half-target sample spacing");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_explicit_detail_clamps_large_sample_grid(void) {
    const int W = 1800, H = 1400;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.pixel_step = 1;
    cfg.scan_zone = 1.0f;
    cfg.small_target_screen_fraction = 1.0f / 200.0f;

    uint8_t *frame = make_gray_frame(W, H, 96);
    anomaly_result_t res;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    int expected_sample_step = 2;
    EXPECT(res.scan_plan.sampled_width == (W + expected_sample_step - 1) / expected_sample_step,
           "detail clamp: explicit 1px detail is coarsened on large frames");
    EXPECT(res.scan_plan.sampled_height == (H + expected_sample_step - 1) / expected_sample_step,
           "detail clamp: sampled height reflects memory guard");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_periodic_full_refresh_replaces_indefinite_target_only_reuse(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 10;

    uint8_t *frame = make_gray_frame(W, H, 64);
    stamp_texture_field(frame, W * 4, W, H, 0);
    stamp_color_patch(frame, W * 4, W, H, W / 2, H / 2, 2, 255, 24, 24);

    anomaly_result_t r1, r2, r3;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 1000, &r1);
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 100000, &r2);
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 400000, &r3);

    EXPECT(r2.rescan_mode == ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
           "periodic refresh: stable follow-up frame holds Color ROI state");
    EXPECT(r3.rescan_mode == ANOMALY_RESCAN_MODE_FULL,
           "periodic refresh: ~333ms cadence forces a full refresh");
    EXPECT((r3.scan_plan.reason_flags & ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH) != 0u,
           "periodic refresh: scan plan records the cadence-driven full rescan reason");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_scan_zone_growth_reallocates_scratch_buffers(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(
        ANOMALY_ALGO_COLOR |
        ANOMALY_ALGO_THERMAL |
        ANOMALY_ALGO_MOTION |
        ANOMALY_ALGO_PERSIST);
    cfg.score_threshold = 2.0f;
    cfg.scan_zone = 0.50f;
    cfg.pixel_step = 1;

    uint8_t *frame = make_gray_frame(W, H, 96);
    stamp_texture_field(frame, W * 4, W, H, 0);
    stamp_color_patch(frame, W * 4, W, H, W / 2, H / 2, 3, 250, 32, 32);

    anomaly_result_t small, large;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 1000, &small);

    cfg.scan_zone = 0.80f;
    anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 33333, &large);

    EXPECT(large.scan_plan.sampled_width > small.scan_plan.sampled_width,
           "scan-zone growth: sampled width expands without crashing");
    EXPECT(large.scan_plan.sampled_height > small.scan_plan.sampled_height,
           "scan-zone growth: sampled height expands without crashing");

    free(frame);
    anomaly_state_cleanup(&st);
}

// ── Entry point ────────────────────────────────────────────────────────────

int main(void) {
    printf("Running anomaly detection unit tests...\n");

    test_anomaly_buffer_rejects_null_inputs();
    test_anomaly_buffer_zero_count_is_noop_success();
    test_anomaly_buffer_grows_preserves_capacity_and_allows_writes();
    test_anomaly_buffer_resize_allocates_without_capacity();
    test_grid_region_active_mask_bounds_rejects_invalid_and_empty();
    test_grid_region_active_mask_bounds_padding_and_clamp();
    test_grid_region_float_zero_and_copy_touch_only_region();
    test_grid_region_u8_zero_noops_invalid_and_touches_region();
    test_debug_insert_top_candidate_noops_invalid_inputs();
    test_debug_insert_top_candidate_empty_and_field_copy();
    test_debug_insert_top_candidate_descending_middle_insert();
    test_debug_insert_top_candidate_capacity_tail_behavior();
    test_debug_insert_top_candidate_equal_score_appends();
    test_debug_append_center_box_fields_and_clamps();
    test_debug_append_center_box_noops_invalid_and_capped();
    test_debug_append_rect_fields_clamps_and_crosshair();
    test_debug_append_rect_noops_invalid_and_capped();
    test_debug_draw_rgba_hline_clamps_reverses_and_writes_alpha();
    test_debug_draw_rgba_vline_clamps_reverses_and_writes_alpha();
    test_debug_draw_rgba_circle_invalid_noops_and_symmetric_points();
    test_debug_draw_boxes_rgba_noops_invalid_inputs();
    test_debug_draw_boxes_rgba_crosshair_color_underlay_and_gap();
    test_debug_draw_boxes_rgba_rect_color_underlay_and_skip_invalid();
    test_debug_draw_hot_overlay_rgba_noops_invalid_inputs();
    test_debug_draw_hot_overlay_rgba_white_hot_marks_bright_region();
    test_debug_draw_hot_overlay_rgba_black_hot_marks_dark_region();
    test_debug_draw_hot_overlay_rgba_uniform_frame_noop();
    test_debug_scan_reason_flag_names_and_formatting();
    test_debug_registration_invalid_reason_names();
    test_debug_populate_registration_model_noops_null_inputs();
    test_debug_populate_registration_model_copies_fields_and_anchors();
    test_result_build_boxes_invalid_inputs_return_zero();
    test_result_build_boxes_target_tracks_take_priority();
    test_result_build_boxes_accumulator_fallback_and_persist_filter();
    test_result_build_boxes_saliency_aux_current_gate_noop();
    test_saliency_update_aux_track_noops_invalid_inputs();
    test_saliency_update_aux_track_initializes_inactive_track();
    test_saliency_update_aux_track_ingate_ema_and_hold_reset();
    test_saliency_update_aux_track_out_of_gate_resets_track();
    test_saliency_update_aux_track_invalid_raw_ages_and_expires();
    test_saliency_update_aux_track_hold_bonus_and_hit_cap();
    test_saliency_find_local_support_invalid_inputs_initialize_outputs();
    test_saliency_find_local_support_inactive_track_returns_false();
    test_saliency_find_local_support_nonpositive_scores_return_false();
    test_saliency_find_local_support_best_score_and_normalized_output();
    test_saliency_find_local_support_edge_clamps_search_window();
    test_saliency_choose_best_dark_patch_invalid_defaults();
    test_saliency_choose_best_dark_patch_best_score_and_coordinates();
    test_saliency_choose_best_dark_patch_first_max_and_negative_scores();
    test_saliency_classify_display_algorithm_invalid_xy_returns_persist();
    test_saliency_classify_display_algorithm_null_maps_no_evidence_returns_persist();
    test_saliency_classify_display_algorithm_thermal_spatial_wins();
    test_saliency_classify_display_algorithm_color_wins();
    test_saliency_classify_display_algorithm_motion_wins_without_bg();
    test_saliency_classify_display_algorithm_near_tie_returns_persist();
    test_saliency_classify_display_algorithm_thermal_temporal_paths();
    test_saliency_classify_display_algorithm_registration_zero_suppresses();
    test_scratch_capacity_null_and_zero_count_contracts();
    test_scratch_capacity_allocates_all_primary_groups();
    test_scratch_capacity_prev_roi_snapshot_buffers();
    test_scratch_capacity_preserves_existing_larger_buffers();
    test_roi_state_capacity_allocation();
    test_roi_state_zero_count_and_null_behavior();
    test_roi_state_cell_capacity_allocation();
    test_roi_state_clear_preserves_capacity_and_zeros_fields();
    test_roi_state_release_zeros_struct();
    test_roi_state_summarize_cells_rejects_null_and_invalid();
    test_roi_state_summarize_cells_counts_flags_and_scores();
    test_target_revisit_null_defaults_and_track_count();
    test_target_revisit_adaptive_risk_and_weak_lock();
    test_target_revisit_radius_scaling_and_clamps();
    test_target_revisit_point_inside_gate_closest_match();
    test_target_revisit_annotate_roi_cells_no_op_filters();
    test_target_revisit_annotate_roi_cells_marks_forced_track_bounds();
    test_target_revisit_annotate_roi_cells_min_hits_scales_provisional();
    test_target_tracks_clear_single_track_zeroes_fields();
    test_target_tracks_clear_all_resets_tracks_and_next_id();
    test_roi_tracks_clear_all_resets_all_lifecycle_state();
    test_roi_tracks_clear_saliency_only_preserves_unrelated_state();
    test_roi_tracks_age_one_frame_lifecycle_and_preserves_unrelated_state();
    test_target_tracks_match_skips_inactive_and_already_matched();
    test_target_tracks_algorithm_mismatch_gate();
    test_target_tracks_allocate_prefers_first_inactive();
    test_target_tracks_allocate_full_uses_weakest_score();
    test_target_tracks_update_allocates_track_and_sets_lifecycle_fields();
    test_target_tracks_update_matches_existing_and_preserves_publish_confirmation();
    test_target_tracks_update_unmatched_ages_and_respects_registration_health();
    test_target_tracks_update_empty_frame_returns_clear_intent_only_without_revisit_tracks();
    test_target_tracks_predict_null_and_default_noop();
    test_target_tracks_predict_scene_discontinuity_clears();
    test_target_tracks_predict_invalid_and_hard_health_clear();
    test_target_tracks_decay_movement_evidence_clamps_windows();
    test_target_tracks_update_movement_evidence_records_independent_tile();
    test_target_tracks_predict_invalid_registration_leaves_tracks_unchanged();
    test_target_tracks_predict_failed_inverse_marks_forced_revisit();
    test_target_tracks_predict_success_clamps_updates_quality_and_nonfresh_revisit();

    test_thermal_blob_candidate_rank_ordering();
    test_color_blob_candidate_rank_ordering();
    test_thermal_blob_candidate_rank_lookup();
    test_color_blob_candidate_rank_lookup();
    test_appearance_ranked_index_insert_rejects_invalid_inputs();
    test_appearance_ranked_index_insert_orders_and_clamps();
    test_thermal_blob_insert_rejects_invalid_inputs();
    test_thermal_blob_insert_sorts_candidates();
    test_thermal_blob_insert_nms_rejects_weaker_candidate();
    test_thermal_blob_insert_nms_replaces_weaker_candidate();
    test_thermal_blob_insert_cap_rejects_low_rank_candidate();
    test_thermal_blob_insert_reports_target_tail_drop();
    test_color_blob_insert_rejects_invalid_inputs();
    test_color_blob_insert_sorts_candidates();
    test_color_blob_insert_nms_rejects_weaker_candidate();
    test_color_blob_insert_nms_replaces_weaker_candidate();
    test_color_blob_insert_cap_rejects_low_rank_candidate();
    test_color_blob_insert_reports_target_tail_drop();
    test_motion_candidate_collection_null_and_reset();
    test_motion_candidate_collection_thermal_peak_mapping();
    test_motion_candidate_collection_color_only_score();
    test_motion_candidate_collection_peak_nms_and_clamp();

    test_appearance_detector_interface_contract();
    test_thermal_detector_delta_and_radius_helpers();
    test_thermal_detector_probe_and_context_helpers();
    test_thermal_detector_temporal_stats_helper();
    test_thermal_state_lifecycle_helpers();
    test_color_detector_histogram_and_rarity_helpers();
    test_color_detector_candidate_scalar_helpers();
    test_color_detector_rgba_sampling_helpers();
    test_color_detector_sampling_phase_helpers();
    test_color_detector_sample_xy_helpers();
    test_color_detector_dense_seed_helpers();
    test_color_detector_frontend_mode_helpers();
    test_color_detector_candidate_temporal_boost_helper();
    test_color_detector_temporal_rescue_helper();
    test_color_detector_contrast_rescue_helper();
    test_color_detector_fresh_winner_gate_helper();
    test_color_detector_suppress_seed_region_helper();
    test_color_detector_support_patch_radius_helper();
    test_color_detector_support_scalar_formula_helpers();
    test_color_detector_reviewed_fp_cluster_helper();
    test_color_detector_track_support_match_invalid_defaults();
    test_color_detector_track_support_match_filters_and_gate();
    test_color_detector_track_support_match_closest_in_gate();
    test_color_detector_track_persistence_bonus_rejects_invalid_inputs();
    test_color_detector_track_persistence_bonus_base_formula();
    test_color_detector_track_persistence_bonus_disagreement_formula();
    test_color_detector_support_patch_score_helper();
    test_color_detector_local_uv_support_helper();
    test_color_detector_blob_neighbor_similarity_helper();
    test_color_detector_blob_cohesion_weights_helper();
    test_color_detector_candidate_bbox_norm_helper();
    test_color_detector_contrast_helpers();
    test_color_detector_target_telemetry();

    test_color_candidate_target_observation_conversion();
    test_thermal_candidate_target_observation_conversion();
    test_target_observation_duplicate_suppression();

    test_detector_facade_matches_process_frame();
    test_detector_facade_rejects_missing_frame();
    test_detector_facade_rejects_missing_state();
    test_detector_facade_rejects_unsupported_format();

    test_motion_estimator_texture_scale_boundaries();
    test_motion_estimator_structure_scale_invalid_and_border();
    test_motion_estimator_structure_scale_strong_corner();
    test_motion_estimator_residual_displacement_rejects_invalid_and_miss();
    test_motion_estimator_residual_displacement_shifted_patch();
    test_motion_estimator_appearance_scorer_output_init_defaults();
    test_motion_estimator_appearance_scorer_output_init_clears_scores();
    test_motion_estimator_appearance_score_winner_eligibility();
    test_motion_estimator_appearance_proposal_carries_candidate_fields();
    test_motion_estimator_build_appearance_proposals_rejects_invalid_inputs();
    test_motion_estimator_build_appearance_proposals_clamps_to_capacity();
    test_motion_estimator_build_appearance_proposals_clamps_to_contract_max();
    test_motion_estimator_build_appearance_proposals_copies_candidate_fields();
    test_motion_estimator_mirror_appearance_support_null_out_safe();
    test_motion_estimator_mirror_appearance_support_copies_global_stats_without_inputs();
    test_motion_estimator_mirror_appearance_support_clamps_count();
    test_motion_estimator_mirror_appearance_support_falls_back_to_proposal_pixels();
    test_motion_estimator_mirror_appearance_support_uses_nonzero_support_pixels();
    test_motion_estimator_mirror_appearance_support_winner_uses_highest_positive();
    test_motion_estimator_snapshot_rejects_invalid_input();
    test_motion_estimator_snapshot_queries_valid_tile();
    test_motion_estimator_tile_classification_null_and_invalid();
    test_motion_estimator_tile_classification_local_outlier_independent();
    test_motion_estimator_tile_classification_parallax_like_layers();
    test_motion_estimator_tile_classification_unstable_partial_score();
    test_motion_estimator_nearest_support_invalid_and_absent();
    test_motion_estimator_nearest_support_picks_strongest_nearby();
    test_motion_estimator_stamp_support_invalid_noop();
    test_motion_estimator_stamp_support_scales_and_registration_min();

    test_config_transition_unchanged();
    test_config_transition_display_only();
    test_config_transition_debug_only();
    test_config_transition_live_update();
    test_config_transition_reset_sensitive();
    test_config_transition_reset_wins();
    test_config_transition_null_requires_reset();
    test_runtime_config_transition_contract_matches_public_wrapper();
    test_runtime_config_movement_mode_normalization();
    test_runtime_config_effective_target_and_thermal_values();
    test_runtime_config_effective_sample_steps();
    test_runtime_config_motion_evidence_scale();
    test_thermal_shadow_rescue_score_and_eligibility();
    test_thermal_shadow_movement_reject_reason();
    test_registration_prefilter_luma_grid_edges();
    test_registration_prefilter_luma_grid_degenerate_dimensions();
    test_registration_prefilter_luma_grid_invalid_inputs_noop();
    test_registration_health_confidence_values();
    test_registration_classify_health_contract();
    test_registration_cache_invalid_store_clears_validity();
    test_registration_cache_store_sets_budget_and_copies_fields();
    test_registration_cache_try_load_copies_model_and_decrements_budget();
    test_registration_cache_try_load_rejects_gate_mismatch();
    test_registration_model_make_defaults();
    test_registration_model_normalize_and_valid();
    test_registration_model_scale_and_apply();
    test_registration_model_inverse_round_trip();
    test_registration_model_inverse_rejects_singular();
    test_registration_model_motion_search_gate();
    test_linear_solve_3x3_solution_and_pivot();
    test_linear_solve_3x3_singular_rejects();
    test_linear_solve_6x6_solution_and_pivot();
    test_linear_solve_6x6_singular_rejects();

    test_similarity_pure_translation();
    test_similarity_pure_rotation_90();
    test_similarity_degenerate_n1();
    test_similarity_degenerate_collinear();

    test_uniform_no_detection();
    test_thermal_hotspot_detected();
    test_color_outlier_detected();
    test_color_dense_verifier_rejects_sparse_sampled_impostor();
    test_color_dense_peak_seed_rejects_sparse_impostor_fresh_rgba();
    test_color_dense_span_reject_reports_measured_area();
    test_color_fresh_compact_unique_blob_survives();
    test_color_fresh_oversized_blob_is_not_candidate();
    test_color_fresh_ranking_prefers_peak_unique_blob_over_plateau();
    test_black_hot_thermal();
    test_high_threshold_no_detection();
    test_runtime_min_delta_override();
    test_thermal_saliency_detected();
    test_unified_saliency_color_support();
    test_min_hits_gate();
    test_scan_zone_excludes_corner();
    test_motion_static_scene();
    test_motion_moving_patch();
    test_motion_moving_patch_affine_registration();
    test_accumulator_hold_after_miss();
    test_frame_stride_gates_full_refresh_only();
    test_color_stride_hold_blocks_new_non_cadence_roi();
    test_frame_stride_selective_frames_age_tracks();
    test_frame_stride_selective_still_runs_registration();
    test_large_motion_discontinuity_clears_rois();
    test_scan_planner_roi_grid_cell_span_contract();
    test_frame_centered_roi_bounds_contract();
    test_frame_registration_roi_bounds_contract();
    test_scan_planner_selective_refresh_helper_invalid_input();
    test_scan_planner_selective_refresh_helper_partial_flags();
    test_scan_planner_selective_refresh_helper_target_only_empty();
    test_scan_planner_selective_refresh_helper_sparse_fallback();
    test_scan_planner_selective_refresh_helper_too_broad_fallback();
    test_scan_planner_partial_mode_on_localized_exposure();
    test_scan_planner_target_only_mode_with_active_track();
    test_color_target_track_uses_dense_candidate_geometry();
    test_small_target_caps_sample_step();
    test_explicit_detail_clamps_large_sample_grid();
    test_periodic_full_refresh_replaces_indefinite_target_only_reuse();
    test_scan_zone_growth_reallocates_scratch_buffers();

    printf("\nResults: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail > 0 ? 1 : 0;
}

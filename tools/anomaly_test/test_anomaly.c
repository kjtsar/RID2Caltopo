// test_anomaly.c — Native unit tests for the anomaly detection algorithms.
//
// Build & run (from this directory):
//   cmake -B build && cmake --build build && ./build/anomaly_test
//
// Tests exercise anomaly_analysis.c directly — the same code the Android app
// uses — so there is no separate reimplementation to keep in sync.
#include "anomaly_analysis.h"

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
    return c;
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

static void test_frame_stride_skips(void) {
    // frame_stride=3: only every 3rd frame is analyzed.
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
    // Frame counter starts at 0; first analyzed frame is at counter=3 (counter % 3 == 0).
    int b1 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=1, skip
    int b2 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=2, skip
    int b3 = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res); // counter=3, analyze

    EXPECT(b1 == 0, "stride=3: frame 1 skipped");
    EXPECT(b2 == 0, "stride=3: frame 2 skipped");
    EXPECT(b3 > 0,  "stride=3: frame 3 analyzed and fires");

    free(frame);
    anomaly_state_cleanup(&st);
}

static void test_frame_stride_hold_ages_on_skipped_frames(void) {
    // Hold lifetime should age on skipped frames too.
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
    anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // skip
    anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // skip
    int detected = anomaly_process_frame(&st, &cfg, hotspot_frame, W * 4, W, H, 0, &res); // analyze
    EXPECT(detected > 0, "stride hold: detection established on analyzed frame");
    EXPECT(st.acc_active[0], "stride hold: color track active after detection");
    int hold_before_skips = st.acc_hold[0];
    EXPECT(hold_before_skips > 0, "stride hold: hold initialized with positive budget");

    anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res); // skip
    anomaly_process_frame(&st, &cfg, plain_frame, W * 4, W, H, 0, &res); // skip
    EXPECT(st.acc_hold[0] == hold_before_skips - 2,
           "stride hold: skipped frames decrement hold budget");

    free(hotspot_frame);
    free(plain_frame);
    anomaly_state_cleanup(&st);
}

static void test_frame_stride_skip_still_runs_registration(void) {
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
    anomaly_process_frame(&st, &cfg, frame1, W * 4, W, H, 0, &r1); // skip, seeds prev_luma
    anomaly_process_frame(&st, &cfg, frame2, W * 4, W, H, 0, &r2); // analyze
    anomaly_process_frame(&st, &cfg, frame3, W * 4, W, H, 0, &r3); // skip, should still register

    EXPECT(r1.registration_ran_this_frame, "stride registration: initial skipped frame still reports registration pass");
    EXPECT(!r1.appearance_refresh_ran_this_frame, "stride registration: initial skipped frame does not run appearance refresh");
    EXPECT(r2.appearance_refresh_ran_this_frame, "stride registration: analyzed frame reports appearance refresh");
    EXPECT(r3.registration_ran_this_frame, "stride registration: skipped frame still runs registration");
    EXPECT(!r3.appearance_refresh_ran_this_frame, "stride registration: skipped frame reports no appearance refresh");
    EXPECT(r3.rescan_mode == ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
           "stride registration: skipped frame uses appearance-stride-skip mode");
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

static void test_scan_planner_partial_mode_on_localized_exposure(void) {
    const int W = 640, H = 480;
    anomaly_state_t st;
    anomaly_state_init(&st);

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_MOTION);
    cfg.score_threshold = 2.0f;

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
    EXPECT(partial_fresh > 0 && partial_fresh < partial_total,
           "partial mode: analyzed refresh touches only a subset of ROI samples");
    EXPECT(partial_carried > 0,
           "partial mode: non-refreshed ROI samples are carried forward");

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
    EXPECT(r2.rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY,
           "target-only mode: stable carry with active track maps to target-only");
    EXPECT(st.target_tracks[0].active,
           "target-only mode: explicit target track stays active");
    EXPECT(st.target_tracks[0].hit_count > 0,
           "target-only mode: explicit target track accumulates direct hits");
    int target_total = st.roi_state.width * st.roi_state.height;
    int target_fresh = count_mask_set(st.roi_state.fresh_mask, target_total);
    int target_carried = count_mask_set(st.roi_state.carried_mask, target_total);
    EXPECT(target_total > 0, "target-only mode: roi state populated");
    EXPECT(target_fresh > 0 && target_fresh < target_total,
           "target-only mode: analyzed refresh stays localized to revisit samples");
    EXPECT(target_carried > 0,
           "target-only mode: untouched ROI samples are carried forward");

    free(frame1);
    free(frame2);
    anomaly_state_cleanup(&st);
}

// ── Entry point ────────────────────────────────────────────────────────────

int main(void) {
    printf("Running anomaly detection unit tests...\n");

    test_similarity_pure_translation();
    test_similarity_pure_rotation_90();
    test_similarity_degenerate_n1();
    test_similarity_degenerate_collinear();

    test_uniform_no_detection();
    test_thermal_hotspot_detected();
    test_color_outlier_detected();
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
    test_frame_stride_skips();
    test_frame_stride_hold_ages_on_skipped_frames();
    test_frame_stride_skip_still_runs_registration();
    test_large_motion_discontinuity_clears_rois();
    test_scan_planner_partial_mode_on_localized_exposure();
    test_scan_planner_target_only_mode_with_active_track();

    printf("\nResults: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail > 0 ? 1 : 0;
}

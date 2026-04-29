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

static anomaly_config_t default_cfg(int algorithm_mask) {
    anomaly_config_t c;
    c.enabled           = true;
    c.algorithm_mask    = algorithm_mask;
    c.frame_stride      = 1;
    c.score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD;
    c.min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    c.thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT;
    c.scan_zone         = 1.0f;  // full frame for most tests
    c.min_hits          = 1;     // show on first hit unless overridden
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
    set_pixel(frame, W * 4, W / 2, H / 2, 255, 255, 255);

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
    // Red pixel in gray scene → color outlier.
    const int W = 160, H = 120;
    anomaly_state_t st;
    anomaly_state_init(&st);

    uint8_t *frame = make_gray_frame(W, H, 128);
    set_pixel(frame, W * 4, W / 2, H / 2, 220, 20, 20);  // vivid red

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_COLOR);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;

    anomaly_result_t res;
    int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);

    EXPECT(boxes > 0, "color: red pixel in gray scene detected");
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
    // With local tile normalization an isolated outlier in N tile samples scores
    // ≈ √N σ regardless of its absolute magnitude (both deviation and σ scale
    // together).  For a 160×120 frame sampled every 2 px over an 8×8 tile grid
    // each center tile holds roughly 48 samples → outlier score ≈ √48 ≈ 6.9 σ.
    //
    // The pixel must also pass ANOMALY_THERMAL_MIN_DELTA (currently 10 luma
    // units) before Z-scoring even begins — this blocks HEVC/noise artefacts
    // in near-uniform regions.  We therefore use a +20-unit outlier (well above
    // the gate) and bracket the threshold around √48:
    //   threshold = 3.0  → fires   (6.9 > 3.0)
    //   threshold = 15.0 → silent  (6.9 < 15.0)
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
        cfg.score_threshold = 15.0f;
        cfg.min_hits = 1;
        anomaly_result_t res;
        int boxes = anomaly_process_frame(&st, &cfg, frame, W * 4, W, H, 0, &res);
        EXPECT(boxes == 0, "threshold=15: same outlier suppressed");
        anomaly_state_cleanup(&st);
        free(frame);
    }
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
        for (int x = 20; x < W - 20; x += 40)
            set_pixel(f1, W * 4, x, y, 120, 120, 120);
    for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++)
            set_pixel(f1, W * 4, 160 + dx, 88 + dy, 240, 240, 240);

    // Frame 2: patch jumped to x=120, y=88 (moved 40px left; x=120 is grid-aligned)
    uint8_t *f2 = make_gray_frame(W, H, 80);
    for (int y = 20; y < H - 20; y += 40)
        for (int x = 20; x < W - 20; x += 40)
            set_pixel(f2, W * 4, x, y, 120, 120, 120);
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

    anomaly_config_t cfg = default_cfg(ANOMALY_ALGO_THERMAL);
    cfg.score_threshold = 2.0f;
    cfg.min_hits = 1;
    cfg.frame_stride = 3;

    uint8_t *frame = make_gray_frame(W, H, 64);
    set_pixel(frame, W * 4, W / 2, H / 2, 255, 255, 255);

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
    test_min_hits_gate();
    test_scan_zone_excludes_corner();
    test_motion_static_scene();
    test_motion_moving_patch();
    test_accumulator_hold_after_miss();
    test_frame_stride_skips();

    printf("\nResults: %d passed, %d failed\n", g_pass, g_fail);
    return g_fail > 0 ? 1 : 0;
}

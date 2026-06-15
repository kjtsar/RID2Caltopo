// anomaly_video_test.c — Run anomaly detection on a captured MP4 and produce:
//
//   1. An annotated MP4 with detection boxes drawn on every frame
//   2. A CSV detection log with one row per visible box per frame
//
// The CSV has a blank "label" column at the right edge.  Open it in
// Numbers or Excel alongside the annotated video, scrub to each flagged
// timestamp, and fill in G (good / true-positive), B (bad / false-positive),
// or ? (unsure).  Those labels become the ground truth for regression tests.
//
// --probe cx,cy  diagnostic mode: for every frame, sample the same detector-side
//   thermal statistics used by anomaly_analysis.c at the given normalised
//   position and write them to a separate _probe.csv file.  This tells you what
//   score the live detector sees at the subject's known location.
//
// Build:  cmake --build build
// Usage:  ./build/anomaly_video_test <input.mp4> [options]
//         ./build/anomaly_video_test PowerHouse3.mp4 -p bh -t 2.8
//         ./build/anomaly_video_test PowerHouse1.mp4 -p bh --probe 0.42,0.28
//
// Requires ffmpeg and ffprobe on PATH.

#include "anomaly_analysis.h"
#include "anomaly_debug_helpers.h"
#include "anomaly_detector.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define APP_DEFAULT_SENSITIVITY 0.59f
#define APP_DEFAULT_MOTION_EVIDENCE_SENSITIVITY 0.60f
#define APP_DEFAULT_MIN_AREA_FRACTION 0.0015f
#define APP_DEFAULT_SCAN_ZONE 0.50f
#define APP_DEFAULT_MIN_HITS 2
#define APP_DEFAULT_THERMAL_MIN_DELTA 10.0f
#define APP_DEFAULT_SMALL_TARGET_SCREEN_FRACTION (1.0f / 200.0f)
#define APP_DEFAULT_FRAME_STRIDE 1
#define APP_DEFAULT_ADAPTIVE_MIN_STRIDE_FRAMES 2
#define APP_DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS 1.0f
#define APP_COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES 4
#define APP_LOCAL_PLAYBACK_REVIEW_FRAME_STRIDE 2

typedef enum {
    APP_APPEARANCE_AUTO = 0,
    APP_APPEARANCE_THERMAL = 1,
    APP_APPEARANCE_COLOR = 2,
} app_appearance_selection_t;

typedef enum {
    APP_REGISTRATION_GMV = 1,
    APP_REGISTRATION_AFFINE = 2,
} app_registration_mode_t;

typedef enum {
    APP_THERMAL_POLARITY_WHITE_HOT = 1,
    APP_THERMAL_POLARITY_BLACK_HOT = 2,
} app_thermal_polarity_t;

typedef struct {
    bool enabled;
    bool show_hot_overlay;
    bool show_candidate_blobs;
    bool troubleshooting_debug;
    bool color_algorithm_enabled;
    bool motion_algorithm_enabled;
    bool saliency_enabled;
    app_appearance_selection_t appearance_selection;
    int stride_mode;
    int frame_stride;
    int adaptive_min_stride_frames;
    float adaptive_max_stride_seconds;
    int pixel_step;
    float sensitivity;
    float motion_evidence_sensitivity;
    float min_area_fraction;
    app_thermal_polarity_t thermal_polarity;
    app_registration_mode_t registration_mode;
    int movement_estimator_mode;
    float scan_zone;
    int min_hits;
    float thermal_min_delta;
    float small_target_screen_fraction;
} app_anomaly_config_t;

static float app_clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static int app_clampi(int value, int min_value, int max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static float app_motion_evidence_scale_for_sensitivity(float sensitivity) {
    float clamped = app_clampf(sensitivity, 0.0f, 1.0f);
    float scale = clamped <= 0.60f
        ? 0.25f + (clamped * 1.25f)
        : 1.0f + ((clamped - 0.60f) * 2.5f);
    return app_clampf(scale, 0.25f, 2.0f);
}

static app_anomaly_config_t default_app_cfg(void) {
    app_anomaly_config_t cfg;
    memset(&cfg, 0, sizeof(cfg));
    cfg.enabled = true;
    cfg.show_hot_overlay = false;
    cfg.show_candidate_blobs = false;
    cfg.troubleshooting_debug = false;
    cfg.color_algorithm_enabled = false;
    cfg.motion_algorithm_enabled = true;
    cfg.saliency_enabled = false;
    cfg.appearance_selection = APP_APPEARANCE_AUTO;
    cfg.stride_mode = ANOMALY_STRIDE_MODE_FIXED;
    cfg.frame_stride = APP_LOCAL_PLAYBACK_REVIEW_FRAME_STRIDE;
    cfg.adaptive_min_stride_frames = APP_DEFAULT_ADAPTIVE_MIN_STRIDE_FRAMES;
    cfg.adaptive_max_stride_seconds = APP_DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS;
    cfg.pixel_step = 0;
    cfg.sensitivity = APP_DEFAULT_SENSITIVITY;
    cfg.motion_evidence_sensitivity = APP_DEFAULT_MOTION_EVIDENCE_SENSITIVITY;
    cfg.min_area_fraction = APP_DEFAULT_MIN_AREA_FRACTION;
    cfg.thermal_polarity = APP_THERMAL_POLARITY_BLACK_HOT;
    cfg.registration_mode = APP_REGISTRATION_AFFINE;
    cfg.movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    cfg.scan_zone = APP_DEFAULT_SCAN_ZONE;
    cfg.min_hits = APP_DEFAULT_MIN_HITS;
    cfg.thermal_min_delta = APP_DEFAULT_THERMAL_MIN_DELTA;
    cfg.small_target_screen_fraction = APP_DEFAULT_SMALL_TARGET_SCREEN_FRACTION;
    return cfg;
}

static const char *app_appearance_name(app_appearance_selection_t selection) {
    switch (selection) {
        case APP_APPEARANCE_COLOR: return "color";
        case APP_APPEARANCE_THERMAL: return "thermal";
        case APP_APPEARANCE_AUTO:
        default:
            return "auto";
    }
}

static const char *app_registration_name(app_registration_mode_t mode) {
    return mode == APP_REGISTRATION_AFFINE ? "affine" : "gmv";
}

static const char *stride_mode_name(int mode) {
    return mode == ANOMALY_STRIDE_MODE_ADAPTIVE ? "adaptive" : "fixed";
}

static const char *color_frontend_name(int mode) {
    switch (mode) {
        case ANOMALY_COLOR_FRONTEND_FRESH_RGBA: return "fresh-rgba";
        case ANOMALY_COLOR_FRONTEND_FRESH_YUV: return "fresh-yuv";
        case ANOMALY_COLOR_FRONTEND_LEGACY:
        default:
            return "legacy";
    }
}

static const char *app_polarity_name(app_thermal_polarity_t polarity) {
    return polarity == APP_THERMAL_POLARITY_BLACK_HOT ? "black-hot" : "white-hot";
}

static bool parse_app_appearance(const char *text, app_appearance_selection_t *out) {
    if (text == NULL || out == NULL) return false;
    if (strcmp(text, "auto") == 0) {
        *out = APP_APPEARANCE_AUTO;
        return true;
    }
    if (strcmp(text, "thermal") == 0 || strcmp(text, "ir") == 0) {
        *out = APP_APPEARANCE_THERMAL;
        return true;
    }
    if (strcmp(text, "color") == 0) {
        *out = APP_APPEARANCE_COLOR;
        return true;
    }
    return false;
}

static void derive_native_cfg_from_app(const app_anomaly_config_t *app_cfg,
                                       anomaly_config_t *native_cfg) {
    if (app_cfg == NULL || native_cfg == NULL) return;

    int mask = 0;
    if (app_cfg->appearance_selection == APP_APPEARANCE_COLOR) {
        mask |= ANOMALY_ALGO_COLOR;
    } else {
        // App Auto resolves to thermal unless a detected mode is supplied.
        mask |= ANOMALY_ALGO_THERMAL;
    }
    if (app_cfg->motion_algorithm_enabled) mask |= ANOMALY_ALGO_MOTION;
    if (app_cfg->saliency_enabled) mask |= ANOMALY_ALGO_PERSIST;

    float sensitivity = app_clampf(app_cfg->sensitivity, 0.0f, 1.0f);
    float motion_sensitivity = app_clampf(app_cfg->motion_evidence_sensitivity, 0.0f, 1.0f);
    float score_threshold =
        (float)pow(15.0, 1.0 - (double)sensitivity);
    score_threshold = app_clampf(score_threshold, 1.0f, 15.0f);
    float motion_evidence_scale =
        app_motion_evidence_scale_for_sensitivity(motion_sensitivity);
    float area_scale = 0.10f + (4.90f * sensitivity * sensitivity);
    float effective_min_area_fraction =
        app_clampf(app_cfg->min_area_fraction * area_scale, 0.00005f, 0.03f);
    bool color_realtime_stride_default =
        app_cfg->appearance_selection == APP_APPEARANCE_COLOR &&
        app_cfg->stride_mode == ANOMALY_STRIDE_MODE_FIXED &&
        app_cfg->frame_stride == APP_DEFAULT_FRAME_STRIDE &&
        app_cfg->adaptive_min_stride_frames == APP_DEFAULT_ADAPTIVE_MIN_STRIDE_FRAMES &&
        fabsf(app_cfg->adaptive_max_stride_seconds - APP_DEFAULT_ADAPTIVE_MAX_STRIDE_SECONDS) < 0.001f;

    memset(native_cfg, 0, sizeof(*native_cfg));
    native_cfg->enabled = app_cfg->enabled;
    native_cfg->show_hot_overlay = app_cfg->show_hot_overlay;
    native_cfg->show_candidate_blobs = app_cfg->show_candidate_blobs;
    native_cfg->algorithm_mask = mask;
    native_cfg->registration_mode =
        app_cfg->registration_mode == APP_REGISTRATION_AFFINE
            ? ANOMALY_REGISTRATION_AFFINE
            : ANOMALY_REGISTRATION_GMV;
    native_cfg->movement_estimator_mode = app_clampi(
            app_cfg->movement_estimator_mode,
            ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE,
            ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE);
    native_cfg->stride_mode =
        color_realtime_stride_default ||
        app_cfg->stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE
            ? ANOMALY_STRIDE_MODE_ADAPTIVE
            : ANOMALY_STRIDE_MODE_FIXED;
    native_cfg->frame_stride = app_clampi(
            color_realtime_stride_default
                ? APP_COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES
                : app_cfg->frame_stride,
            1,
            10);
    native_cfg->adaptive_min_stride_frames =
        app_clampi(
            color_realtime_stride_default
                ? APP_COLOR_REALTIME_ADAPTIVE_MIN_STRIDE_FRAMES
                : app_cfg->adaptive_min_stride_frames,
            2,
            33);
    native_cfg->adaptive_max_stride_frames =
        app_clampi(33, native_cfg->adaptive_min_stride_frames, 33);
    native_cfg->adaptive_max_stride_seconds =
        app_clampf(app_cfg->adaptive_max_stride_seconds, 0.1f, 10.0f);
    native_cfg->pixel_step = app_clampi(app_cfg->pixel_step, 0, 8);
    native_cfg->score_threshold = score_threshold;
    native_cfg->motion_evidence_scale = motion_evidence_scale;
    native_cfg->min_area_fraction = effective_min_area_fraction;
    native_cfg->thermal_polarity =
        app_cfg->thermal_polarity == APP_THERMAL_POLARITY_BLACK_HOT
            ? ANOMALY_THERMAL_BLACK_HOT
            : ANOMALY_THERMAL_WHITE_HOT;
    native_cfg->scan_zone = app_clampf(app_cfg->scan_zone, 0.5f, 1.0f);
    native_cfg->min_hits = app_clampi(app_cfg->min_hits, 1, 10);
    native_cfg->thermal_min_delta = app_clampf(app_cfg->thermal_min_delta, 1.0f, 64.0f);
    native_cfg->small_target_screen_fraction =
        app_clampf(app_cfg->small_target_screen_fraction, 0.0015f, 0.03f);
    native_cfg->color_frontend_mode =
        app_cfg->appearance_selection == APP_APPEARANCE_COLOR
            ? ANOMALY_COLOR_FRONTEND_FRESH_RGBA
            : ANOMALY_COLOR_FRONTEND_LEGACY;
}

// ── pixel-font frame-number overlay ───────────────────────────────────────
// 5×7 bitmap glyphs for digits 0-9.  Each byte is one row, MSB = leftmost px.
static const uint8_t kDigitFont[10][7] = {
    { 0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E }, // 0
    { 0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E }, // 1
    { 0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F }, // 2
    { 0x0E, 0x11, 0x01, 0x06, 0x01, 0x11, 0x0E }, // 3
    { 0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02 }, // 4
    { 0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E }, // 5
    { 0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E }, // 6
    { 0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08 }, // 7
    { 0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E }, // 8
    { 0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C }, // 9
};
static const uint8_t kLetterCFont[7] = { 0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E };
static const uint8_t kLetterSFont[7] = { 0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E };
static const uint8_t kLetterTFont[7] = { 0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04 };
static const uint8_t kMinusFont[7]   = { 0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00 };
#define FONT_W 5
#define FONT_H 7

typedef struct {
    int frame_num;
    double time_s;
    float x_norm;
    float y_norm;
} color_debug_target_row_t;

static double clamp_double(double value, double min_value, double max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static double parse_ratio_or_zero(const char *text) {
    if (text == NULL || text[0] == '\0') return 0.0;
    int num = 0;
    int den = 0;
    if (sscanf(text, "%d/%d", &num, &den) == 2) {
        if (den != 0) return (double)num / (double)den;
        return 0.0;
    }
    double value = 0.0;
    if (sscanf(text, "%lf", &value) == 1) return value;
    return 0.0;
}

static int load_color_target_csv(const char *path,
                                 color_debug_target_row_t **rows_out,
                                 int *count_out) {
    if (rows_out) *rows_out = NULL;
    if (count_out) *count_out = 0;
    if (path == NULL || path[0] == '\0' || rows_out == NULL || count_out == NULL) return 0;

    FILE *fp = fopen(path, "r");
    if (fp == NULL) return -1;

    int capacity = 0;
    int count = 0;
    color_debug_target_row_t *rows = NULL;
    char line[512];
    while (fgets(line, sizeof(line), fp) != NULL) {
        char *cursor = line;
        while (*cursor == ' ' || *cursor == '\t') cursor++;
        if (*cursor == '\0' || *cursor == '\n' || *cursor == '#') continue;

        color_debug_target_row_t row;
        if (sscanf(cursor, "%d,%lf,%f,%f",
                   &row.frame_num, &row.time_s, &row.x_norm, &row.y_norm) != 4) {
            continue;
        }
        if (count >= capacity) {
            int new_capacity = capacity > 0 ? capacity * 2 : 64;
            color_debug_target_row_t *grown =
                (color_debug_target_row_t *)realloc(rows, (size_t)new_capacity * sizeof(*grown));
            if (grown == NULL) {
                free(rows);
                fclose(fp);
                return -1;
            }
            rows = grown;
            capacity = new_capacity;
        }
        rows[count++] = row;
    }
    fclose(fp);
    *rows_out = rows;
    *count_out = count;
    return 0;
}

static const color_debug_target_row_t *find_color_target_row(
        const color_debug_target_row_t *rows,
        int row_count,
        int frame_num,
        double time_s,
        int *cursor_io) {
    if (rows == NULL || row_count <= 0) return NULL;
    int cursor = (cursor_io != NULL && *cursor_io >= 0) ? *cursor_io : 0;
    if (cursor >= row_count) return NULL;
    while (cursor < row_count && rows[cursor].frame_num < frame_num) {
        cursor++;
    }
    if (cursor_io != NULL) *cursor_io = cursor;
    if (cursor >= row_count) return NULL;
    if (rows[cursor].frame_num == frame_num) return &rows[cursor];
    if (fabs(rows[cursor].time_s - time_s) <= 0.051) return &rows[cursor];
    return NULL;
}

typedef struct {
    uint32_t flag;
    const char *name;
} scan_reason_counter_desc_t;

static const scan_reason_counter_desc_t kScanReasonCounters[] = {
    { ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH, "no-appearance-refresh" },
    { ANOMALY_SCAN_REASON_NO_SAMPLES, "no-samples" },
    { ANOMALY_SCAN_REASON_PREV_STATE_INVALID, "prev-state-invalid" },
    { ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY, "scene-discontinuity" },
    { ANOMALY_SCAN_REASON_REG_INVALID, "reg-invalid" },
    { ANOMALY_SCAN_REASON_REG_HARD_DEGRADED, "reg-hard-degraded" },
    { ANOMALY_SCAN_REASON_WARP_LOW, "warp-low" },
    { ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH, "new-exposed-high" },
    { ANOMALY_SCAN_REASON_STALE_HIGH, "stale-high" },
    { ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH, "sample-step-mismatch" },
    { ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE, "target-only-eligible" },
    { ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE, "partial-eligible" },
    { ANOMALY_SCAN_REASON_MASK_BUILD_FAILED, "mask-build-failed" },
    { ANOMALY_SCAN_REASON_MASK_EMPTY, "mask-empty" },
    { ANOMALY_SCAN_REASON_MASK_TOO_BROAD, "mask-too-broad" },
    { ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH, "periodic-full-refresh" },
};

typedef struct {
    int code;
    const char *name;
} registration_reason_counter_desc_t;

static const registration_reason_counter_desc_t kRegistrationReasonCounters[] = {
    { ANOMALY_REG_INVALID_REASON_NONE, "none" },
    { ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE, "debug-input-unavailable" },
    { ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS, "gmv-too-few-anchors" },
    { ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID, "gmv-fit-invalid" },
    { ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH, "gmv-residual-too-high" },
    { ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE, "gmv-motion-too-large" },
    { ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE, "gmv-scale-out-of-range" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE, "affine-roi-degenerate" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS, "affine-too-few-corners" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES, "affine-too-few-matches" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED, "affine-fit-failed" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH, "affine-residual-too-high" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE, "affine-motion-too-large" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE, "affine-scale-out-of-range" },
    { ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET, "affine-negative-det" },
};

static const scan_reason_counter_desc_t kAdaptiveStrideReasonCounters[] = {
    { ANOMALY_ADAPTIVE_STRIDE_REASON_REG_INVALID, "reg-invalid" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_REG_DEGRADED, "reg-degraded" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_SCENE_DISCONTINUITY, "scene-discontinuity" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_MOVEMENT_LOAD, "movement-load" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_TRACK, "target-track" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_WEAK_TARGET_LOCK, "weak-target-lock" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_RICH_RECENT, "target-rich-recent" },
    { ANOMALY_ADAPTIVE_STRIDE_REASON_STABLE_WINDOW, "stable-window" },
};

static const char *timing_stage_name(anomaly_timing_stage_t stage) {
    switch (stage) {
        case ANOMALY_TIMING_STAGE_REGISTRATION_PREP: return "registration_prep";
        case ANOMALY_TIMING_STAGE_REGISTRATION_SOLVE: return "registration_solve";
        case ANOMALY_TIMING_STAGE_MOVEMENT_ESTIMATOR: return "movement_estimator";
        case ANOMALY_TIMING_STAGE_SCAN_PLANNING: return "scan_planning";
        case ANOMALY_TIMING_STAGE_REFRESH_MASK_BUILD: return "refresh_mask_build";
        case ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP: return "sampled_grid_prep";
        case ANOMALY_TIMING_STAGE_THERMAL_SCORING: return "thermal_scoring";
        case ANOMALY_TIMING_STAGE_COLOR_SCORING: return "color_scoring";
        case ANOMALY_TIMING_STAGE_MOTION_SCORING: return "motion_scoring";
        case ANOMALY_TIMING_STAGE_SALIENCY_SCORING: return "saliency_scoring";
        case ANOMALY_TIMING_STAGE_TARGET_TRACKING: return "target_tracking";
        case ANOMALY_TIMING_STAGE_OVERLAY_DRAW: return "overlay_draw";
        case ANOMALY_TIMING_STAGE_COUNT:
        default:
            return "unknown";
    }
}

static const char *movement_estimator_name(int mode) {
    switch (mode) {
        case ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW:
            return "layered_shadow";
        case ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE:
            return "layered_active";
        case ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE:
        default:
            return "legacy_affine";
    }
}

static const char *movement_layer_name(int layer) {
    switch (layer) {
        case ANOMALY_MOVEMENT_LAYER_BACKGROUND:
            return "background";
        case ANOMALY_MOVEMENT_LAYER_COHERENT_NEAR:
            return "coherent_near";
        case ANOMALY_MOVEMENT_LAYER_UNSTABLE:
            return "unstable";
        case ANOMALY_MOVEMENT_LAYER_LOCAL_OUTLIER:
            return "local_outlier";
        case ANOMALY_MOVEMENT_LAYER_UNKNOWN:
        default:
            return "unknown";
    }
}

// Draw a single scaled pixel at (px,py) with RGBA colour; clips to frame.
static void put_pixel(uint8_t *rgba, int stride, int W, int H,
                      int px, int py, uint8_t r, uint8_t g, uint8_t b) {
    if (px < 0 || py < 0 || px >= W || py >= H) return;
    uint8_t *p = rgba + py * stride + px * 4;
    p[0] = r; p[1] = g; p[2] = b; p[3] = 0xFF;
}

// Draw one digit at pixel origin (ox, oy) with the given scale and colour.
static void draw_digit(uint8_t *rgba, int stride, int W, int H,
                       int digit, int ox, int oy, int scale,
                       uint8_t r, uint8_t g, uint8_t b) {
    if (digit < 0 || digit > 9) return;
    const uint8_t *glyph = kDigitFont[digit];
    for (int row = 0; row < FONT_H; row++) {
        uint8_t bits = glyph[row];
        for (int col = 0; col < FONT_W; col++) {
            if (bits & (0x10 >> col)) {
                for (int sy = 0; sy < scale; sy++)
                    for (int sx = 0; sx < scale; sx++)
                        put_pixel(rgba, stride, W, H,
                                  ox + col * scale + sx,
                                  oy + row * scale + sy, r, g, b);
            }
        }
    }
}

static const uint8_t *glyph_for_char(char ch) {
    if (ch >= '0' && ch <= '9') return kDigitFont[ch - '0'];
    switch (ch) {
        case 'C': return kLetterCFont;
        case 'S': return kLetterSFont;
        case 'T': return kLetterTFont;
        case '-': return kMinusFont;
        default:  return NULL;
    }
}

static void draw_char(uint8_t *rgba, int stride, int W, int H,
                      char ch, int ox, int oy, int scale,
                      uint8_t r, uint8_t g, uint8_t b) {
    const uint8_t *glyph = glyph_for_char(ch);
    if (glyph == NULL) return;
    for (int row = 0; row < FONT_H; row++) {
        uint8_t bits = glyph[row];
        for (int col = 0; col < FONT_W; col++) {
            if (bits & (0x10 >> col)) {
                for (int sy = 0; sy < scale; sy++)
                    for (int sx = 0; sx < scale; sx++)
                        put_pixel(rgba, stride, W, H,
                                  ox + col * scale + sx,
                                  oy + row * scale + sy, r, g, b);
            }
        }
    }
}

static void draw_text(uint8_t *rgba, int stride, int W, int H,
                      const char *text, int x0, int y0, int scale,
                      uint8_t r, uint8_t g, uint8_t b) {
    if (text == NULL) return;
    int glyph_w = FONT_W * scale + scale;
    for (int i = 0; text[i] != '\0'; i++) {
        draw_char(rgba, stride, W, H, text[i], x0 + i * glyph_w, y0, scale, r, g, b);
    }
}

static void fill_rect(uint8_t *rgba, int stride, int W, int H,
                      int x0, int y0, int x1, int y1,
                      uint8_t r, uint8_t g, uint8_t b) {
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    if (x1 < 0 || y1 < 0 || x0 >= W || y0 >= H) return;
    if (x0 < 0) x0 = 0; if (x1 >= W) x1 = W - 1;
    if (y0 < 0) y0 = 0; if (y1 >= H) y1 = H - 1;
    for (int y = y0; y <= y1; y++) {
        uint8_t *row = rgba + y * stride;
        for (int x = x0; x <= x1; x++) {
            uint8_t *p = row + x * 4;
            p[0] = r; p[1] = g; p[2] = b; p[3] = 0xFF;
        }
    }
}

static void stroke_rect(uint8_t *rgba, int stride, int W, int H,
                        int x0, int y0, int x1, int y1, int stroke,
                        uint8_t r, uint8_t g, uint8_t b) {
    if (stroke < 1) stroke = 1;
    for (int t = 0; t < stroke; t++) {
        fill_rect(rgba, stride, W, H, x0, y0 + t, x1, y0 + t, r, g, b);
        fill_rect(rgba, stride, W, H, x0, y1 - t, x1, y1 - t, r, g, b);
        fill_rect(rgba, stride, W, H, x0 + t, y0, x0 + t, y1, r, g, b);
        fill_rect(rgba, stride, W, H, x1 - t, y0, x1 - t, y1, r, g, b);
    }
}

static void draw_crosshair(uint8_t *rgba, int stride, int W, int H,
                           int cx, int cy, int half, int stroke,
                           uint8_t r, uint8_t g, uint8_t b) {
    if (stroke < 1) stroke = 1;
    for (int t = 0; t < stroke; t++) {
        fill_rect(rgba, stride, W, H, cx - half, cy + t, cx + half, cy + t, r, g, b);
        fill_rect(rgba, stride, W, H, cx + t, cy - half, cx + t, cy + half, r, g, b);
    }
}

static float rgba_luma(const uint8_t *px) {
    return (0.2126f * (float)px[0]) + (0.7152f * (float)px[1]) + (0.0722f * (float)px[2]);
}

static void compute_pixel_support(const uint8_t *rgba, int stride, int W, int H,
                                  int cx, int cy, int black_hot,
                                  float *center_out, float *mean9_out,
                                  float *top3mean_out, int *near_count_out) {
    if (center_out) *center_out = 0.0f;
    if (mean9_out) *mean9_out = 0.0f;
    if (top3mean_out) *top3mean_out = 0.0f;
    if (near_count_out) *near_count_out = 0;
    if (rgba == NULL || W <= 0 || H <= 0) return;

    float vals[9];
    int n = 0;
    for (int y = cy - 1; y <= cy + 1; y++) {
        int yy = y < 0 ? 0 : (y >= H ? H - 1 : y);
        const uint8_t *row = rgba + yy * stride;
        for (int x = cx - 1; x <= cx + 1; x++) {
            int xx = x < 0 ? 0 : (x >= W ? W - 1 : x);
            vals[n++] = rgba_luma(row + xx * 4);
        }
    }

    float center = vals[4];
    float sum = 0.0f;
    for (int i = 0; i < 9; i++) sum += vals[i];
    for (int i = 0; i < 9 - 1; i++) {
        for (int j = i + 1; j < 9; j++) {
            bool swap = black_hot ? (vals[j] < vals[i]) : (vals[j] > vals[i]);
            if (swap) {
                float t = vals[i];
                vals[i] = vals[j];
                vals[j] = t;
            }
        }
    }
    float top3 = (vals[0] + vals[1] + vals[2]) / 3.0f;
    float near_threshold = 8.0f;
    int near_count = 0;
    for (int i = 0; i < 9; i++) {
        float v = vals[i];
        if (black_hot ? (v <= center + near_threshold) : (v >= center - near_threshold)) {
            near_count++;
        }
    }

    if (center_out) *center_out = center;
    if (mean9_out) *mean9_out = sum / 9.0f;
    if (top3mean_out) *top3mean_out = top3;
    if (near_count_out) *near_count_out = near_count;
}

static void find_extreme_pixel(const uint8_t *rgba, int stride, int W, int H,
                               const anomaly_config_t *cfg,
                               int *best_x_out, int *best_y_out, float *best_luma_out) {
    if (best_x_out) *best_x_out = 0;
    if (best_y_out) *best_y_out = 0;
    if (best_luma_out) *best_luma_out = 0.0f;
    if (rgba == NULL || cfg == NULL || W <= 0 || H <= 0) return;

    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)W);
    int roi_x1 = W - roi_x0;
    int roi_y0 = (int)(margin * (float)H);
    int roi_y1 = H - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = W; }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = H; }

    int best_x = roi_x0;
    int best_y = roi_y0;
    float best_luma = rgba_luma(rgba + roi_y0 * stride + roi_x0 * 4);
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);

    for (int y = roi_y0; y < roi_y1; y++) {
        const uint8_t *row = rgba + y * stride;
        for (int x = roi_x0; x < roi_x1; x++) {
            float luma = rgba_luma(row + x * 4);
            if ((black_hot && luma < best_luma) || (!black_hot && luma > best_luma)) {
                best_luma = luma;
                best_x = x;
                best_y = y;
            }
        }
    }

    if (best_x_out) *best_x_out = best_x;
    if (best_y_out) *best_y_out = best_y;
    if (best_luma_out) *best_luma_out = best_luma;
}

static void draw_number_badge(uint8_t *rgba, int stride, int W, int H,
                              int x0, int y0, int number, int scale,
                              uint8_t bg_r, uint8_t bg_g, uint8_t bg_b,
                              uint8_t fg_r, uint8_t fg_g, uint8_t fg_b) {
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", number);
    int ndigits = (int)strlen(buf);
    int glyph_w = FONT_W * scale + scale;
    int glyph_h = FONT_H * scale;
    int pad = scale;
    fill_rect(rgba, stride, W, H,
              x0, y0,
              x0 + ndigits * glyph_w - scale + pad * 2,
              y0 + glyph_h + pad * 2,
              bg_r, bg_g, bg_b);
    for (int i = 0; i < ndigits; i++) {
        draw_digit(rgba, stride, W, H, buf[i] - '0',
                   x0 + pad + i * glyph_w, y0 + pad, scale,
                   fg_r, fg_g, fg_b);
    }
}

static void draw_text_badge(uint8_t *rgba, int stride, int W, int H,
                            int x0, int y0, const char *text, int scale,
                            uint8_t bg_r, uint8_t bg_g, uint8_t bg_b,
                            uint8_t fg_r, uint8_t fg_g, uint8_t fg_b) {
    if (text == NULL) return;
    int nchars = (int)strlen(text);
    int glyph_w = FONT_W * scale + scale;
    int glyph_h = FONT_H * scale;
    int pad = scale;
    fill_rect(rgba, stride, W, H,
              x0, y0,
              x0 + nchars * glyph_w - scale + pad * 2,
              y0 + glyph_h + pad * 2,
              bg_r, bg_g, bg_b);
    draw_text(rgba, stride, W, H, text, x0 + pad, y0 + pad, scale, fg_r, fg_g, fg_b);
}

// Render the frame number in the top-left corner.
// White text on an opaque black badge for maximum legibility.
static void draw_frame_number(uint8_t *rgba, int W, int H, int frame_num) {
    // Choose scale so the label is comfortably readable at any resolution.
    int scale = (W >= 1280 || H >= 720) ? 5 : 3;
    int glyph_w = FONT_W * scale + scale;   // inter-character gap
    int glyph_h = FONT_H * scale;

    // Convert frame number to digit array (up to 6 digits).
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", frame_num);
    int ndigits = (int)strlen(buf);

    int margin = scale * 2;
    int pad = scale * 2;
    int bg_x0 = margin - pad;
    int bg_y0 = margin - pad;
    int bg_x1 = margin + ndigits * glyph_w - scale + pad;
    int bg_y1 = margin + glyph_h + pad;
    fill_rect(rgba, W * 4, W, H, bg_x0, bg_y0, bg_x1, bg_y1, 0, 0, 0);

    // White foreground with a thicker black outline.
    for (int d = 0; d < ndigits; d++) {
        int digit = buf[d] - '0';
        int ox    = margin + d * glyph_w;
        int oy    = margin;
        draw_digit(rgba, W * 4, W, H, digit, ox - 2, oy,     scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox + 2, oy,     scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy - 2, scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy + 2, scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy,     scale, 255, 255, 255);
    }
}

static void draw_saliency_debug_overlay(uint8_t *rgba, int W, int H,
                                        const uint8_t *raw_rgba,
                                        const anomaly_config_t *cfg,
                                        const anomaly_result_t *result) {
    if (rgba == NULL || raw_rgba == NULL || cfg == NULL || result == NULL) return;
    const anomaly_debug_saliency_t *dbg = &result->saliency_debug;
    int min_dim = W < H ? W : H;
    int stroke = (W >= 1280 || H >= 720) ? 2 : 1;
    int label_scale = (W >= 1280 || H >= 720) ? 2 : 1;
    int cross_half = min_dim / 60;
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    if (cross_half < 6) cross_half = 6;
    if (cross_half > 14) cross_half = 14;

    float box_side = sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f));
    box_side = (box_side < 0.02f) ? 0.02f : (box_side > 0.18f ? 0.18f : box_side);
    box_side *= 0.9f;  // match saliency box scale in anomaly_analysis.c
    int half_w = (int)lroundf(box_side * 0.5f * (float)(W - 1));
    int half_h = (int)lroundf(box_side * 0.5f * (float)(H - 1));

    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        if (!c->valid) continue;
        int cx = c->pixel_x;
        int cy = c->pixel_y;
        int x0 = cx - half_w;
        int y0 = cy - half_h;
        int x1 = cx + half_w;
        int y1 = cy + half_h;
        uint8_t r = (i == 0) ? 255 : 255;
        uint8_t g = (i == 0) ? 255 : 255;
        uint8_t b = (i == 0) ? 255 : 255;
        stroke_rect(rgba, W * 4, W, H, x0, y0, x1, y1, stroke, r, g, b);
        draw_number_badge(rgba, W * 4, W, H,
                          x0, y0 - (FONT_H * label_scale + label_scale * 2 + 2),
                          i + 1, label_scale,
                          0, 0, 0,
                          255, 255, 255);
        char score_buf[32];
        int s10 = (int)lroundf(c->spatial_score * 10.0f);
        int t10 = (int)lroundf(c->temporal_score * 10.0f);
        int c10 = (int)lroundf(c->combined_score * 10.0f);
        snprintf(score_buf, sizeof(score_buf), "S%dT%dC%d", s10, t10, c10);
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + 2,
                        score_buf, label_scale,
                        0, 0, 0,
                        255, 255, 255);
        float center = 0.0f, mean9 = 0.0f, top3 = 0.0f;
        int near_count = 0;
        compute_pixel_support(raw_rgba, W * 4, W, H, cx, cy, black_hot,
                              &center, &mean9, &top3, &near_count);
        char support_buf[32];
        snprintf(support_buf, sizeof(support_buf), "N%dK%d", near_count, (int)lroundf(top3));
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + (FONT_H * label_scale + label_scale * 2 + 4),
                        support_buf, label_scale,
                        0, 0, 0,
                        255, 255, 255);
    }

    if (dbg->acc_post_active) {
        int cx = (int)lroundf(dbg->acc_post_x_norm * (float)(W - 1));
        int cy = (int)lroundf(dbg->acc_post_y_norm * (float)(H - 1));
        draw_crosshair(rgba, W * 4, W, H, cx, cy, cross_half, stroke + 1, 0x23, 0xC5, 0x52);
        draw_number_badge(rgba, W * 4, W, H,
                          6, H - (FONT_H * label_scale + label_scale * 2 + 10),
                          dbg->acc_post_hits, label_scale,
                          0, 0, 0,
                          0x23, 0xC5, 0x52);
    }

    int extreme_x = 0, extreme_y = 0;
    float extreme_luma = 0.0f;
    find_extreme_pixel(raw_rgba, W * 4, W, H, cfg, &extreme_x, &extreme_y, &extreme_luma);
    draw_crosshair(rgba, W * 4, W, H, extreme_x, extreme_y, cross_half / 2, stroke + 1, 0xF2, 0x30, 0x30);
    float extreme_mean9 = 0.0f, extreme_top3 = 0.0f;
    int extreme_near_count = 0;
    compute_pixel_support(raw_rgba, W * 4, W, H, extreme_x, extreme_y, black_hot,
                          NULL, &extreme_mean9, &extreme_top3, &extreme_near_count);
    char extreme_buf[32];
    snprintf(extreme_buf, sizeof(extreme_buf), "X%dN%d", (int)lroundf(extreme_luma), extreme_near_count);
    draw_text_badge(rgba, W * 4, W, H,
                    extreme_x + cross_half / 2 + 4, extreme_y,
                    extreme_buf, label_scale,
                    0, 0, 0,
                    0xF2, 0x30, 0x30);

    draw_number_badge(rgba, W * 4, W, H,
                      6 + (FONT_W * label_scale + label_scale) * 2,
                      H - (FONT_H * label_scale + label_scale * 2 + 10),
                      dbg->bg_ready ? 1 : 0, label_scale,
                      0, 0, 0,
                      255, 255, 255);
}

static void draw_motion_debug_overlay(uint8_t *rgba, int W, int H,
                                      const anomaly_result_t *result) {
    if (rgba == NULL || result == NULL) return;
    const anomaly_debug_motion_t *dbg = &result->motion_debug;
    if (!dbg->valid) return;

    int label_scale = (W >= 1280 || H >= 720) ? 2 : 1;
    int stroke = (W >= 1280 || H >= 720) ? 2 : 1;
    float box_side = 0.06f;
    int half_w = (int)lroundf(box_side * 0.5f * (float)(W - 1));
    int half_h = (int)lroundf(box_side * 0.5f * (float)(H - 1));

    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        if (!c->valid) continue;
        int cx = c->pixel_x;
        int cy = c->pixel_y;
        int x0 = cx - half_w;
        int y0 = cy - half_h;
        int x1 = cx + half_w;
        int y1 = cy + half_h;
        stroke_rect(rgba, W * 4, W, H, x0, y0, x1, y1, stroke, 0x23, 0xC5, 0x52);
        draw_number_badge(rgba, W * 4, W, H,
                          x0, y0 - (FONT_H * label_scale + label_scale * 2 + 2),
                          i + 1, label_scale,
                          0, 0, 0,
                          0x23, 0xC5, 0x52);
        char score_buf[32];
        int z10 = (int)lroundf(c->spatial_score * 10.0f);
        int c10 = (int)lroundf(c->combined_score * 10.0f);
        snprintf(score_buf, sizeof(score_buf), "Z%dC%d", z10, c10);
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + 2,
                        score_buf, label_scale,
                        0, 0, 0,
                        0x23, 0xC5, 0x52);
    }

    if (dbg->raw_candidate_valid) {
        int cx = (int)lroundf(dbg->raw_x_norm * (float)(W - 1));
        int cy = (int)lroundf(dbg->raw_y_norm * (float)(H - 1));
        draw_crosshair(rgba, W * 4, W, H, cx, cy, 8, stroke + 1, 0x23, 0xC5, 0x52);
    }

    char stats_buf[128];
    snprintf(stats_buf, sizeof(stats_buf), "M%d S%d U%d V%d Z%d B%d",
             (int)lroundf(dbg->residual_mean),
             (int)lroundf(dbg->residual_std),
             dbg->sample_step,
             dbg->motion_step,
             (int)lroundf(dbg->zoom_motion_scale * 100.0f),
             (int)lroundf(dbg->broad_motion_scale * 100.0f));
    draw_text_badge(rgba, W * 4, W, H,
                    6, 26,
                    stats_buf, label_scale,
                    0, 0, 0,
                    0x23, 0xC5, 0x52);
}

// ── helpers ────────────────────────────────────────────────────────────────

static const char *algo_label(int algo) {
    switch (algo) {
        case ANOMALY_ALGO_COLOR:   return "color";
        case ANOMALY_ALGO_THERMAL: return "thermal";
        case ANOMALY_ALGO_MOTION:  return "motion";
        case ANOMALY_ALGO_MOTION_TOLERANCE: return "motion_tolerance";
        case ANOMALY_ALGO_PERSIST: return "saliency";
        default:                   return "unknown";
    }
}

static double monotonic_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + ((double)ts.tv_nsec / 1000000000.0);
}

static const char *realtime_descriptor(double realtime_factor) {
    if (realtime_factor > 1.02) return "faster than realtime";
    if (realtime_factor < 0.98) return "slower than realtime";
    return "equal to realtime";
}

static void json_write_string(FILE *out, const char *text) {
    fputc('"', out);
    if (text != NULL) {
        for (const unsigned char *p = (const unsigned char *)text; *p != '\0'; p++) {
            switch (*p) {
                case '\\': fputs("\\\\", out); break;
                case '"':  fputs("\\\"", out); break;
                case '\n': fputs("\\n", out); break;
                case '\r': fputs("\\r", out); break;
                case '\t': fputs("\\t", out); break;
                default:
                    if (*p < 0x20) {
                        fprintf(out, "\\u%04x", (unsigned int)*p);
                    } else {
                        fputc((int)*p, out);
                    }
                    break;
            }
        }
    }
    fputc('"', out);
}

static void dump_saliency_debug(FILE *out, int frame_num, double time_s,
                                const uint8_t *raw_rgba, int W, int H,
                                const anomaly_config_t *cfg,
                                const anomaly_result_t *result) {
    if (out == NULL || raw_rgba == NULL || cfg == NULL || result == NULL) return;
    const anomaly_debug_saliency_t *dbg = &result->saliency_debug;
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    fprintf(out, "\nSaliency debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  bg_ready=%d raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f)\n",
            dbg->bg_ready ? 1 : 0,
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm);
    fprintf(out, "  tracked_score_pre=%.3f switch_suppressed=%d\n",
            (double)dbg->tracked_score_pre,
            dbg->switch_suppressed ? 1 : 0);
    fprintf(out, "  acc_pre:  active=%d hits=%d xy=(%.4f, %.4f)\n",
            dbg->acc_pre_active ? 1 : 0, dbg->acc_pre_hits,
            (double)dbg->acc_pre_x_norm, (double)dbg->acc_pre_y_norm);
    fprintf(out, "  acc_post: active=%d hits=%d xy=(%.4f, %.4f)\n",
            dbg->acc_post_active ? 1 : 0, dbg->acc_post_hits,
            (double)dbg->acc_post_x_norm, (double)dbg->acc_post_y_norm);
    fprintf(out, "  top candidates (%d):\n", dbg->top_candidate_count);
    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        float center = 0.0f, mean9 = 0.0f, top3 = 0.0f;
        int near_count = 0;
        compute_pixel_support(raw_rgba, W * 4, W, H, c->pixel_x, c->pixel_y, black_hot,
                              &center, &mean9, &top3, &near_count);
        fprintf(out, "    #%d px=(%d,%d) xy=(%.4f,%.4f) spatial=%.3f temporal=%.3f combined=%.3f\n",
                i + 1, c->pixel_x, c->pixel_y,
                (double)c->x_norm, (double)c->y_norm,
                (double)c->spatial_score,
                (double)c->temporal_score,
                (double)c->combined_score);
        fprintf(out, "       support: center=%.1f mean9=%.1f top3=%.1f near_count=%d\n",
                (double)center, (double)mean9, (double)top3, near_count);
    }
    if (result->box_count > 0) {
        fprintf(out, "  visible boxes:\n");
        for (int i = 0; i < result->box_count; i++) {
            const anomaly_box_t *b = &result->boxes[i];
            fprintf(out, "    box[%d] algo=%s center=(%.4f,%.4f) size=(%.4f,%.4f) weight=%.2f\n",
                    i, algo_label(b->algorithm),
                    (double)((b->left_norm + b->right_norm) * 0.5f),
                    (double)((b->top_norm + b->bottom_norm) * 0.5f),
                    (double)(b->right_norm - b->left_norm),
                    (double)(b->bottom_norm - b->top_norm),
                    (double)b->weight);
        }
    }
    fflush(out);
}

static void dump_motion_debug(FILE *out, int frame_num, double time_s,
                              const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_motion_t *dbg = &result->motion_debug;
    if (!dbg->valid) return;
    fprintf(out, "\nMotion debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  discontinuity=%d sample_step=%d motion_step=%d samples=%d\n",
            dbg->scene_discontinuity ? 1 : 0,
            dbg->sample_step,
            dbg->motion_step,
            dbg->sample_count);
    fprintf(out, "  residual_mean=%.3f residual_std=%.3f\n",
            (double)dbg->residual_mean,
            (double)dbg->residual_std);
    fprintf(out, "  zoom_scale=%.3f broad_scale=%.3f motion_load=%.3f winner(tex=%.2f str=%.2f sup=%.2f pers=%.2f)\n",
            (double)dbg->zoom_motion_scale,
            (double)dbg->broad_motion_scale,
            (double)dbg->global_motion_load,
            (double)dbg->winner_texture_scale,
            (double)dbg->winner_structure_scale,
            (double)dbg->winner_support_scale,
            (double)dbg->winner_persistence_scale);
    fprintf(out, "  raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f)\n",
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm);
    fprintf(out, "  top motion candidates (%d):\n", dbg->top_candidate_count);
    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        fprintf(out, "    #%d px=(%d,%d) xy=(%.4f,%.4f) z=%.3f combined=%.3f\n",
                i + 1, c->pixel_x, c->pixel_y,
                (double)c->x_norm, (double)c->y_norm,
                (double)c->spatial_score,
                (double)c->combined_score);
    }
}

static void dump_thermal_debug(FILE *out, int frame_num, double time_s,
                               const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_thermal_t *dbg = &result->thermal_debug;
    fprintf(out, "\nThermal debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  bg_ready=%d raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f) winner_idx=%d\n",
            dbg->bg_ready ? 1 : 0,
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm,
            dbg->winning_candidate_index);
    fprintf(out, "  thermal candidates (%d):\n", dbg->candidate_count);
    for (int i = 0; i < dbg->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES; i++) {
        const anomaly_debug_thermal_candidate_t *c = &dbg->candidates[i];
        fprintf(out,
                "    #%d px=(%d,%d) xy=(%.4f,%.4f) base=%.3f final=%.3f area=%.1f span=%.1f fill=%.2f center=%.2f quality=%.2f isolation=%.2f patch=%.2f motion=%.2f sing_scale=%.2f rank=%.2f%s%s\n",
                i + 1,
                c->pixel_x,
                c->pixel_y,
                (double)c->x_norm,
                (double)c->y_norm,
                (double)c->base_score,
                (double)c->final_score,
                (double)c->area,
                (double)c->span,
                (double)c->fill,
                (double)c->center_share,
                (double)c->quality,
                (double)c->isolation_rank,
                (double)c->patch_support,
                (double)c->motion_support,
                (double)c->singleton_score_scale,
                (double)c->retention_rank,
                c->singleton_blob ? " singleton" : "",
                (i == dbg->winning_candidate_index) ? "  <winner>" : "");
    }
    if (dbg->target.enabled) {
        fprintf(out,
                "  target enabled=%d valid=%d inside=%d sample=(%d,%d) delta=%.3f score=%.3f local_max=%d started=%d stage=%d gate=%d extracted_rank=%d winner_rank=%d dropped_cap=%d dropped_nms=%d replaced_nms=%d nms_rank=%d nms_sample=(%d,%d)\n",
                dbg->target.enabled ? 1 : 0,
                dbg->target.valid ? 1 : 0,
                dbg->target.inside_scan_zone ? 1 : 0,
                dbg->target.sample_x,
                dbg->target.sample_y,
                (double)dbg->target.target_delta,
                (double)dbg->target.target_score,
                dbg->target.local_max ? 1 : 0,
                dbg->target.started_component ? 1 : 0,
                dbg->target.stage,
                dbg->target.rejection_gate,
                dbg->target.extracted_rank,
                dbg->target.winning_rank,
                dbg->target.dropped_by_cap ? 1 : 0,
                dbg->target.dropped_by_nms ? 1 : 0,
                dbg->target.replaced_by_nms ? 1 : 0,
                dbg->target.nms_conflict_rank,
                dbg->target.nms_conflict_sample_x,
                dbg->target.nms_conflict_sample_y);
    }
}

static void dump_color_debug(FILE *out, int frame_num, double time_s,
                             const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_color_t *dbg = &result->color_debug;
    fprintf(out, "\nColor debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f) winner_idx=%d phase=%d (%d,%d)\n",
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm,
            dbg->winning_candidate_index,
            dbg->active_phase_index,
            dbg->active_phase_x,
            dbg->active_phase_y);
    fprintf(out,
            "  reuse=%d forced_full=%d fallback_flags=0x%X fresh=%d (%.2f) carried=%d (%.2f) unsampled=%d (%.2f)\n",
            dbg->selective_reuse_active ? 1 : 0,
            dbg->forced_full_refresh ? 1 : 0,
            dbg->fallback_reason_flags,
            dbg->fresh_sample_count,
            (double)dbg->fresh_sample_fraction,
            dbg->carried_sample_count,
            (double)dbg->carried_sample_fraction,
            dbg->unsampled_new_exposed_count,
            (double)dbg->unsampled_new_exposed_fraction);
    fprintf(out, "  color candidates (%d):\n", dbg->candidate_count);
    for (int i = 0; i < dbg->candidate_count && i < ANOMALY_DEBUG_TOP_COLOR_CANDIDATES; i++) {
        const anomaly_debug_color_candidate_t *c = &dbg->candidates[i];
        fprintf(out,
                "    #%d px=(%d,%d) xy=(%.4f,%.4f) base=%.3f final=%.3f area=%.1f span=%.1f fill=%.2f center=%.2f quality=%.2f isolation=%.2f ring=%.2f support=%.2f contrast=%.2f rank=%.2f%s\n",
                i + 1,
                c->pixel_x,
                c->pixel_y,
                (double)c->x_norm,
                (double)c->y_norm,
                (double)c->base_score,
                (double)c->final_score,
                (double)c->area,
                (double)c->span,
                (double)c->fill,
                (double)c->center_share,
                (double)c->quality,
                (double)c->isolation_score,
                (double)c->ring_fraction,
                (double)c->support_mass,
                (double)c->contrast_weight,
                (double)c->retention_rank,
                (i == dbg->winning_candidate_index) ? "  <winner>" : "");
    }
    if (dbg->target.enabled) {
        fprintf(out,
                "  target enabled=%d valid=%d inside=%d refresh_skipped=%d sampled=%d carried=%d sample=(%d,%d) "
                "rarity=%.4f local_support=%d patch_valid=%d coherent=%d fresh_coherent=%d multicell=%d "
                "ring_neighbors=%d ring_chroma=%.3f ring_luma=%.3f "
                "patch_uvl=(%.3f,%.3f,%.3f) ring_uvl=(%.3f,%.3f,%.3f) "
                "pre_support=%.3f support=%.3f seed=%d matched=%d nearest=%d dist=%.4f winner=%d "
                "drop(cap=%d nms=%d repl=%d gate=%d) pre_cap=%d/%d nms_rank=%d win_rank=%d stage=%d\n",
                dbg->target.enabled ? 1 : 0,
                dbg->target.valid ? 1 : 0,
                dbg->target.inside_scan_zone ? 1 : 0,
                dbg->target.refresh_skipped ? 1 : 0,
                dbg->target.sampled_this_frame ? 1 : 0,
                dbg->target.carried_from_history ? 1 : 0,
                dbg->target.sample_x,
                dbg->target.sample_y,
                (double)dbg->target.hist_rarity_score,
                dbg->target.local_support_count,
                dbg->target.patch_valid_count,
                dbg->target.coherent_patch_cell_count,
                dbg->target.coherent_patch_fresh_cell_count,
                dbg->target.coherent_patch_multicell ? 1 : 0,
                dbg->target.ring_neighbor_count,
                (double)dbg->target.ring_chroma_contrast,
                (double)dbg->target.ring_luma_contrast,
                (double)dbg->target.patch_mean_u,
                (double)dbg->target.patch_mean_v,
                (double)dbg->target.patch_mean_luma,
                (double)dbg->target.ring_mean_u,
                (double)dbg->target.ring_mean_v,
                (double)dbg->target.ring_mean_luma,
                (double)dbg->target.pre_support_score,
                (double)dbg->target.support_score,
                dbg->target.support_seed_eligible ? 1 : 0,
                dbg->target.matched_candidate_index,
                dbg->target.nearest_candidate_index,
                (double)dbg->target.nearest_candidate_distance,
                dbg->target.winning_candidate_index,
                dbg->target.dropped_by_cap ? 1 : 0,
                dbg->target.dropped_by_nms ? 1 : 0,
                dbg->target.replaced_by_nms ? 1 : 0,
                dbg->target.rejected_by_winner_gate ? 1 : 0,
                dbg->target.pre_cap_rank,
                dbg->target.pre_cap_limit,
                dbg->target.nms_conflict_rank,
                dbg->target.winning_rank,
                dbg->target.stage);
    }
}

static const char *thermal_target_stage_name(anomaly_debug_thermal_target_stage_t stage) {
    switch (stage) {
        case ANOMALY_THERMAL_TARGET_STAGE_NOT_HOT: return "not_hot";
        case ANOMALY_THERMAL_TARGET_STAGE_SUPPRESSED_BY_NEIGHBOR: return "suppressed_by_neighbor";
        case ANOMALY_THERMAL_TARGET_STAGE_MERGED_INTO_COMPONENT: return "merged_into_component";
        case ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE: return "rejected_by_gate";
        case ANOMALY_THERMAL_TARGET_STAGE_EXTRACTED: return "extracted";
        case ANOMALY_THERMAL_TARGET_STAGE_NONE:
        default: return "none";
    }
}

static const char *thermal_target_gate_name(anomaly_debug_thermal_target_gate_t gate) {
    switch (gate) {
        case ANOMALY_THERMAL_TARGET_GATE_MAX_AREA: return "max_area";
        case ANOMALY_THERMAL_TARGET_GATE_RING_HOT: return "ring_hot";
        case ANOMALY_THERMAL_TARGET_GATE_SIDE_HOT: return "side_hot";
        case ANOMALY_THERMAL_TARGET_GATE_SUPPORT_MASS: return "support_mass";
        case ANOMALY_THERMAL_TARGET_GATE_SUPPORT_NEAR: return "support_near";
        case ANOMALY_THERMAL_TARGET_GATE_ZERO_QUALITY: return "zero_quality";
        case ANOMALY_THERMAL_TARGET_GATE_NONE:
        default: return "none";
    }
}

static const char *thermal_micro_reject_name(anomaly_debug_thermal_micro_reject_t reason) {
    switch (reason) {
        case ANOMALY_THERMAL_MICRO_REJECT_NO_HOT_PEAK: return "no_hot_peak";
        case ANOMALY_THERMAL_MICRO_REJECT_NOT_LOCAL_MAX: return "not_local_max";
        case ANOMALY_THERMAL_MICRO_REJECT_WEAK_PROMINENCE: return "weak_prominence";
        case ANOMALY_THERMAL_MICRO_REJECT_RING_HOT: return "ring_hot";
        case ANOMALY_THERMAL_MICRO_REJECT_TOO_MANY_HOT: return "too_many_hot";
        case ANOMALY_THERMAL_MICRO_REJECT_LOW_COMPACTNESS: return "low_compactness";
        case ANOMALY_THERMAL_MICRO_REJECT_EDGE_LIKE: return "edge_like";
        case ANOMALY_THERMAL_MICRO_REJECT_CENTROID_DRIFT: return "centroid_drift";
        case ANOMALY_THERMAL_MICRO_REJECT_TOO_FAR: return "too_far";
        case ANOMALY_THERMAL_MICRO_REJECT_NONE:
        default: return "none";
    }
}

static const char *movement_shadow_reject_name(anomaly_debug_movement_shadow_reject_t reason) {
    switch (reason) {
        case ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE: return "no_movement_tile";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_PARALLAX: return "parallax";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_NOT_INDEPENDENT: return "not_independent";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_WEAK_THERMAL: return "weak_thermal";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_RING_HOT: return "ring_hot";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_LOCAL_MEAN_HOT: return "local_mean_hot";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_TOO_MANY_HOT: return "too_many_hot";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_LOW_COMPACTNESS: return "low_compactness";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_EDGE_LIKE: return "edge_like";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_CENTROID_DRIFT: return "centroid_drift";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_NO_LOCAL_SHAPE: return "no_local_shape";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOTION_SUPPORT: return "no_motion_support";
        case ANOMALY_MOVEMENT_SHADOW_REJECT_NONE:
        default: return "none";
    }
}

static const char *color_target_stage_name(anomaly_debug_color_target_stage_t stage) {
    switch (stage) {
        case ANOMALY_COLOR_TARGET_STAGE_OUTSIDE_SCAN_ZONE: return "outside_scan_zone";
        case ANOMALY_COLOR_TARGET_STAGE_INVALID_SAMPLE: return "invalid_sample";
        case ANOMALY_COLOR_TARGET_STAGE_RARITY_REJECTED: return "rarity_rejected";
        case ANOMALY_COLOR_TARGET_STAGE_LOCAL_SUPPORT_REJECTED: return "local_support_rejected";
        case ANOMALY_COLOR_TARGET_STAGE_SUPPORT_MAP_REJECTED: return "support_map_rejected";
        case ANOMALY_COLOR_TARGET_STAGE_NO_CANDIDATE: return "no_candidate";
        case ANOMALY_COLOR_TARGET_STAGE_EXTRACTED: return "extracted";
        case ANOMALY_COLOR_TARGET_STAGE_WINNER: return "winner";
        case ANOMALY_COLOR_TARGET_STAGE_NONE:
        default: return "none";
    }
}

static void write_thermal_debug_jsonl(FILE *out, int frame_num, double time_s,
                                      const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_thermal_t *dbg = &result->thermal_debug;
    fprintf(out,
            "{\"frame\":%d,\"time_s\":%.3f,\"bg_ready\":%s,\"raw_candidate_valid\":%s,"
            "\"raw_score\":%.6f,\"raw_x_norm\":%.6f,\"raw_y_norm\":%.6f,"
            "\"frame_delta_mean\":%.6f,\"frame_delta_norm\":%.6f,"
            "\"frame_blob_contrast_mean\":%.6f,\"frame_blob_contrast_std\":%.6f,"
            "\"winning_candidate_index\":%d,\"candidate_count\":%d,\"candidates\":[",
            frame_num,
            time_s,
            dbg->bg_ready ? "true" : "false",
            dbg->raw_candidate_valid ? "true" : "false",
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm,
            (double)dbg->frame_delta_mean,
            (double)dbg->frame_delta_norm,
            (double)dbg->frame_blob_contrast_mean,
            (double)dbg->frame_blob_contrast_std,
            dbg->winning_candidate_index,
            dbg->candidate_count);
    for (int i = 0; i < dbg->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES; i++) {
        const anomaly_debug_thermal_candidate_t *c = &dbg->candidates[i];
        fprintf(out,
                "%s{\"index\":%d,\"valid\":%s,\"pixel_x\":%d,\"pixel_y\":%d,"
                "\"x_norm\":%.6f,\"y_norm\":%.6f,"
                "\"bbox_left_norm\":%.6f,\"bbox_top_norm\":%.6f,"
                "\"bbox_right_norm\":%.6f,\"bbox_bottom_norm\":%.6f,"
                "\"base_score\":%.6f,\"final_score\":%.6f,\"temporal_score\":%.6f,"
                "\"area\":%.6f,\"span\":%.6f,\"fill\":%.6f,\"center_share\":%.6f,"
                "\"quality\":%.6f,\"isolation_rank\":%.6f,"
                "\"peak_delta\":%.6f,\"mean_delta\":%.6f,"
                "\"score_scale\":%.6f,\"history_scale\":%.6f,"
                "\"apparent_size_scale\":%.6f,\"isolation_track_scale\":%.6f,"
                "\"context_scale\":%.6f,\"parent_scale\":%.6f,"
                "\"area_rank\":%.6f,\"span_rank\":%.6f,\"center_rank\":%.6f,"
                "\"quality_rank\":%.6f,\"patch_support\":%.6f,\"motion_support\":%.6f,"
                "\"singleton_score_scale\":%.6f,\"retention_rank\":%.6f,"
                "\"raw_delta_rescue_eligible\":%s,\"raw_delta_rescue_score\":%.6f,"
                "\"movement_tile_valid\":%s,\"movement_layer\":\"%s\","
                "\"movement_residual_px\":%.6f,\"movement_independent_score\":%.6f,"
                "\"movement_confidence\":%.6f,\"movement_independent\":%s,"
                "\"movement_parallax\":%s,\"would_promote_movement_rescue\":%s,"
                "\"near_tracked_target\":%s,\"nearest_track_distance\":%.6f,"
                "\"nearest_track_index\":%d,\"nearest_track_id\":%d,"
                "\"nearest_track_hit_count\":%d,\"near_debug_target\":%s,"
                "\"singleton_blob\":%s,\"above_threshold\":%s}",
                (i == 0) ? "" : ",",
                i,
                c->valid ? "true" : "false",
                c->pixel_x,
                c->pixel_y,
                (double)c->x_norm,
                (double)c->y_norm,
                (double)c->bbox_left_norm,
                (double)c->bbox_top_norm,
                (double)c->bbox_right_norm,
                (double)c->bbox_bottom_norm,
                (double)c->base_score,
                (double)c->final_score,
                (double)c->temporal_score,
                (double)c->area,
                (double)c->span,
                (double)c->fill,
                (double)c->center_share,
                (double)c->quality,
                (double)c->isolation_rank,
                (double)c->peak_delta,
                (double)c->mean_delta,
                (double)c->score_scale,
                (double)c->history_scale,
                (double)c->apparent_size_scale,
                (double)c->isolation_track_scale,
                (double)c->context_scale,
                (double)c->parent_scale,
                (double)c->area_rank,
                (double)c->span_rank,
                (double)c->center_rank,
                (double)c->quality_rank,
                (double)c->patch_support,
                (double)c->motion_support,
                (double)c->singleton_score_scale,
                (double)c->retention_rank,
                c->raw_delta_rescue_eligible ? "true" : "false",
                (double)c->raw_delta_rescue_score,
                c->movement_tile_valid ? "true" : "false",
                movement_layer_name(c->movement_layer_class),
                (double)c->movement_residual_px,
                (double)c->movement_independent_score,
                (double)c->movement_confidence,
                c->movement_independent ? "true" : "false",
                c->movement_parallax ? "true" : "false",
                c->would_promote_movement_rescue ? "true" : "false",
                c->near_tracked_target ? "true" : "false",
                (double)c->nearest_track_distance,
                c->nearest_track_index,
                c->nearest_track_id,
                c->nearest_track_hit_count,
                c->near_debug_target ? "true" : "false",
                c->singleton_blob ? "true" : "false",
                c->above_threshold ? "true" : "false");
    }
    fprintf(out,
            "],\"target\":{\"enabled\":%s,\"valid\":%s,\"inside_scan_zone\":%s,"
            "\"pixel_x\":%d,\"pixel_y\":%d,\"sample_x\":%d,\"sample_y\":%d,"
            "\"x_norm\":%.6f,\"y_norm\":%.6f,"
            "\"target_delta\":%.6f,\"target_score\":%.6f,"
            "\"target_raw_delta\":%.6f,\"target_raw_score\":%.6f,"
            "\"target_temporal_margin\":%.6f,"
            "\"target_spatial_abs_delta\":%.6f,"
            "\"target_spatial_std\":%.6f,\"target_spatial_score\":%.6f,"
            "\"hot_eligible\":%s,\"started_component\":%s,\"local_max\":%s,"
            "\"local_peak_radius\":%d,\"local_peak_sample_x\":%d,\"local_peak_sample_y\":%d,"
            "\"local_peak_delta\":%.6f,\"local_peak_score\":%.6f,"
            "\"local_peak_distance\":%.6f,"
            "\"local_peak_raw_sample_x\":%d,\"local_peak_raw_sample_y\":%d,"
            "\"local_peak_raw_delta\":%.6f,\"local_peak_raw_score\":%.6f,"
            "\"local_peak_raw_distance\":%.6f,"
            "\"local_peak_raw_temporal_margin\":%.6f,"
            "\"local_peak_raw_spatial_abs_delta\":%.6f,"
            "\"local_peak_raw_spatial_std\":%.6f,"
            "\"local_peak_raw_spatial_score\":%.6f,"
            "\"local_peak_is_component_seed\":%s,"
            "\"local_window_sample_count\":%d,\"local_window_hot_count\":%d,"
            "\"local_window_raw_delta_sum\":%.6f,\"local_window_raw_delta_mean\":%.6f,"
            "\"local_window_weighted_centroid_dx\":%.6f,"
            "\"local_window_weighted_centroid_dy\":%.6f,"
            "\"micro_candidate_would_create\":%s,"
            "\"micro_candidate_reject_reason\":\"%s\","
            "\"micro_candidate_peak_sample_x\":%d,\"micro_candidate_peak_sample_y\":%d,"
            "\"micro_candidate_peak_delta\":%.6f,\"micro_candidate_peak_score\":%.6f,"
            "\"micro_candidate_prominence\":%.6f,"
            "\"micro_candidate_ring_mean\":%.6f,"
            "\"micro_candidate_ring_hot_fraction\":%.6f,"
            "\"micro_candidate_hot_count\":%d,"
            "\"micro_candidate_sample_count\":%d,"
            "\"micro_candidate_compactness\":%.6f,"
            "\"micro_candidate_centroid_dx\":%.6f,"
            "\"micro_candidate_centroid_dy\":%.6f,"
            "\"micro_candidate_centroid_offset\":%.6f,"
            "\"micro_candidate_one_sided_support\":%.6f,"
            "\"micro_candidate_distance_to_debug_target\":%.6f,"
            "\"suppressor_sample_x\":%d,\"suppressor_sample_y\":%d,"
            "\"suppressor_delta\":%.6f,\"suppressor_score\":%.6f,"
            "\"component_seed_x\":%d,\"component_seed_y\":%d,"
            "\"component_peak_x\":%d,\"component_peak_y\":%d,"
            "\"component_area\":%.6f,\"component_span\":%.6f,\"component_fill\":%.6f,"
            "\"component_peak_delta\":%.6f,\"component_mean_delta\":%.6f,"
            "\"component_quality\":%.6f,\"component_rejected\":%s,"
            "\"rejection_gate\":\"%s\",\"stage\":\"%s\","
            "\"nearby_rejected_component_valid\":%s,"
            "\"nearby_rejected_component_contains_target\":%s,"
            "\"nearby_rejected_component_gate\":\"%s\","
            "\"nearby_rejected_component_seed_x\":%d,"
            "\"nearby_rejected_component_seed_y\":%d,"
            "\"nearby_rejected_component_peak_x\":%d,"
            "\"nearby_rejected_component_peak_y\":%d,"
            "\"nearby_rejected_component_area\":%.6f,"
            "\"nearby_rejected_component_span\":%.6f,"
            "\"nearby_rejected_component_fill\":%.6f,"
            "\"nearby_rejected_component_peak_delta\":%.6f,"
            "\"nearby_rejected_component_mean_delta\":%.6f,"
            "\"nearby_rejected_component_quality\":%.6f,"
            "\"nearby_rejected_component_distance\":%.6f,"
            "\"dropped_by_cap\":%s,\"dropped_by_nms\":%s,\"replaced_by_nms\":%s,"
            "\"nms_conflict_rank\":%d,\"nms_conflict_sample_x\":%d,\"nms_conflict_sample_y\":%d,"
            "\"pre_cap_rank\":%d,\"pre_cap_candidate_count\":%d,"
            "\"pre_cap_limit\":%d,\"pre_cap_retention_rank\":%.6f,"
            "\"extracted_rank\":%d,\"winning_rank\":%d,"
            "\"provisional_candidate_index\":%d,"
            "\"provisional_score_floor\":%.6f,\"provisional_final_score\":%.6f,"
            "\"provisional_score_eligible\":%s,\"provisional_shape_eligible\":%s,"
            "\"provisional_candidate_rank\":%.6f,\"provisional_selected_rank\":%d,"
            "\"provisional_selected_score\":%.6f,\"provisional_near_existing_skip\":%s,"
            "\"raw_delta_rescue_eligible\":%s,\"raw_delta_rescue_score\":%.6f,"
            "\"movement_tile_valid\":%s,\"movement_layer\":\"%s\","
            "\"movement_residual_px\":%.6f,\"movement_independent_score\":%.6f,"
            "\"movement_confidence\":%.6f,\"movement_motion_support\":%.6f,"
            "\"movement_independent\":%s,"
            "\"movement_parallax\":%s,\"would_promote_movement_rescue\":%s,"
            "\"local_peak_movement_tile_valid\":%s,"
            "\"local_peak_movement_layer\":\"%s\","
            "\"local_peak_movement_residual_px\":%.6f,"
            "\"local_peak_movement_independent_score\":%.6f,"
            "\"local_peak_movement_confidence\":%.6f,"
            "\"local_peak_movement_motion_support\":%.6f,"
            "\"local_peak_movement_independent\":%s,"
            "\"local_peak_movement_parallax\":%s,"
            "\"movement_shadow_motion_support\":%s,"
            "\"movement_shadow_parallax_penalty\":%s,"
            "\"movement_shadow_thermal_support\":%s,"
            "\"movement_shadow_clutter_veto\":%s,"
            "\"movement_rescue_would_publish\":%s,"
            "\"movement_boost_would_publish\":%s,"
            "\"movement_rescue_reject_reason\":\"%s\","
            "\"matched_track_index\":%d,\"matched_track_id\":%d,"
            "\"matched_track_hit_count\":%d,\"matched_track_miss_count\":%d,"
            "\"matched_track_hold_count\":%d,\"matched_track_publish_confirmed\":%s}}\n",
            dbg->target.enabled ? "true" : "false",
            dbg->target.valid ? "true" : "false",
            dbg->target.inside_scan_zone ? "true" : "false",
            dbg->target.pixel_x,
            dbg->target.pixel_y,
            dbg->target.sample_x,
            dbg->target.sample_y,
            (double)dbg->target.x_norm,
            (double)dbg->target.y_norm,
            (double)dbg->target.target_delta,
            (double)dbg->target.target_score,
            (double)dbg->target.target_raw_delta,
            (double)dbg->target.target_raw_score,
            (double)dbg->target.target_temporal_margin,
            (double)dbg->target.target_spatial_abs_delta,
            (double)dbg->target.target_spatial_std,
            (double)dbg->target.target_spatial_score,
            dbg->target.hot_eligible ? "true" : "false",
            dbg->target.started_component ? "true" : "false",
            dbg->target.local_max ? "true" : "false",
            dbg->target.local_peak_radius,
            dbg->target.local_peak_sample_x,
            dbg->target.local_peak_sample_y,
            (double)dbg->target.local_peak_delta,
            (double)dbg->target.local_peak_score,
            (double)dbg->target.local_peak_distance,
            dbg->target.local_peak_raw_sample_x,
            dbg->target.local_peak_raw_sample_y,
            (double)dbg->target.local_peak_raw_delta,
            (double)dbg->target.local_peak_raw_score,
            (double)dbg->target.local_peak_raw_distance,
            (double)dbg->target.local_peak_raw_temporal_margin,
            (double)dbg->target.local_peak_raw_spatial_abs_delta,
            (double)dbg->target.local_peak_raw_spatial_std,
            (double)dbg->target.local_peak_raw_spatial_score,
            dbg->target.local_peak_is_component_seed ? "true" : "false",
            dbg->target.local_window_sample_count,
            dbg->target.local_window_hot_count,
            (double)dbg->target.local_window_raw_delta_sum,
            (double)dbg->target.local_window_raw_delta_mean,
            (double)dbg->target.local_window_weighted_centroid_dx,
            (double)dbg->target.local_window_weighted_centroid_dy,
            dbg->target.micro_candidate_would_create ? "true" : "false",
            thermal_micro_reject_name(dbg->target.micro_candidate_reject_reason),
            dbg->target.micro_candidate_peak_sample_x,
            dbg->target.micro_candidate_peak_sample_y,
            (double)dbg->target.micro_candidate_peak_delta,
            (double)dbg->target.micro_candidate_peak_score,
            (double)dbg->target.micro_candidate_prominence,
            (double)dbg->target.micro_candidate_ring_mean,
            (double)dbg->target.micro_candidate_ring_hot_fraction,
            dbg->target.micro_candidate_hot_count,
            dbg->target.micro_candidate_sample_count,
            (double)dbg->target.micro_candidate_compactness,
            (double)dbg->target.micro_candidate_centroid_dx,
            (double)dbg->target.micro_candidate_centroid_dy,
            (double)dbg->target.micro_candidate_centroid_offset,
            (double)dbg->target.micro_candidate_one_sided_support,
            (double)dbg->target.micro_candidate_distance_to_debug_target,
            dbg->target.suppressor_sample_x,
            dbg->target.suppressor_sample_y,
            (double)dbg->target.suppressor_delta,
            (double)dbg->target.suppressor_score,
            dbg->target.component_seed_x,
            dbg->target.component_seed_y,
            dbg->target.component_peak_x,
            dbg->target.component_peak_y,
            (double)dbg->target.component_area,
            (double)dbg->target.component_span,
            (double)dbg->target.component_fill,
            (double)dbg->target.component_peak_delta,
            (double)dbg->target.component_mean_delta,
            (double)dbg->target.component_quality,
            dbg->target.component_rejected ? "true" : "false",
            thermal_target_gate_name(dbg->target.rejection_gate),
            thermal_target_stage_name(dbg->target.stage),
            dbg->target.nearby_rejected_component_valid ? "true" : "false",
            dbg->target.nearby_rejected_component_contains_target ? "true" : "false",
            thermal_target_gate_name(dbg->target.nearby_rejected_component_gate),
            dbg->target.nearby_rejected_component_seed_x,
            dbg->target.nearby_rejected_component_seed_y,
            dbg->target.nearby_rejected_component_peak_x,
            dbg->target.nearby_rejected_component_peak_y,
            (double)dbg->target.nearby_rejected_component_area,
            (double)dbg->target.nearby_rejected_component_span,
            (double)dbg->target.nearby_rejected_component_fill,
            (double)dbg->target.nearby_rejected_component_peak_delta,
            (double)dbg->target.nearby_rejected_component_mean_delta,
            (double)dbg->target.nearby_rejected_component_quality,
            (double)dbg->target.nearby_rejected_component_distance,
            dbg->target.dropped_by_cap ? "true" : "false",
            dbg->target.dropped_by_nms ? "true" : "false",
            dbg->target.replaced_by_nms ? "true" : "false",
            dbg->target.nms_conflict_rank,
            dbg->target.nms_conflict_sample_x,
            dbg->target.nms_conflict_sample_y,
            dbg->target.pre_cap_rank,
            dbg->target.pre_cap_candidate_count,
            dbg->target.pre_cap_limit,
            (double)dbg->target.pre_cap_retention_rank,
            dbg->target.extracted_rank,
            dbg->target.winning_rank,
            dbg->target.provisional_candidate_index,
            (double)dbg->target.provisional_score_floor,
            (double)dbg->target.provisional_final_score,
            dbg->target.provisional_score_eligible ? "true" : "false",
            dbg->target.provisional_shape_eligible ? "true" : "false",
            (double)dbg->target.provisional_candidate_rank,
            dbg->target.provisional_selected_rank,
            (double)dbg->target.provisional_selected_score,
            dbg->target.provisional_near_existing_skip ? "true" : "false",
            dbg->target.raw_delta_rescue_eligible ? "true" : "false",
            (double)dbg->target.raw_delta_rescue_score,
            dbg->target.movement_tile_valid ? "true" : "false",
            movement_layer_name(dbg->target.movement_layer_class),
            (double)dbg->target.movement_residual_px,
            (double)dbg->target.movement_independent_score,
            (double)dbg->target.movement_confidence,
            (double)dbg->target.movement_motion_support,
            dbg->target.movement_independent ? "true" : "false",
            dbg->target.movement_parallax ? "true" : "false",
            dbg->target.would_promote_movement_rescue ? "true" : "false",
            dbg->target.local_peak_movement_tile_valid ? "true" : "false",
            movement_layer_name(dbg->target.local_peak_movement_layer_class),
            (double)dbg->target.local_peak_movement_residual_px,
            (double)dbg->target.local_peak_movement_independent_score,
            (double)dbg->target.local_peak_movement_confidence,
            (double)dbg->target.local_peak_movement_motion_support,
            dbg->target.local_peak_movement_independent ? "true" : "false",
            dbg->target.local_peak_movement_parallax ? "true" : "false",
            dbg->target.movement_shadow_motion_support ? "true" : "false",
            dbg->target.movement_shadow_parallax_penalty ? "true" : "false",
            dbg->target.movement_shadow_thermal_support ? "true" : "false",
            dbg->target.movement_shadow_clutter_veto ? "true" : "false",
            dbg->target.movement_rescue_would_publish ? "true" : "false",
            dbg->target.movement_boost_would_publish ? "true" : "false",
            movement_shadow_reject_name(dbg->target.movement_rescue_reject_reason),
            dbg->target.matched_track_index,
            dbg->target.matched_track_id,
            dbg->target.matched_track_hit_count,
            dbg->target.matched_track_miss_count,
            dbg->target.matched_track_hold_count,
            dbg->target.matched_track_publish_confirmed ? "true" : "false");
}

static void write_color_debug_jsonl(FILE *out, int frame_num, double time_s,
                                    const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_color_t *dbg = &result->color_debug;
    fprintf(out,
            "{\"frame\":%d,\"time_s\":%.3f,\"raw_candidate_valid\":%s,"
            "\"raw_score\":%.6f,\"raw_x_norm\":%.6f,\"raw_y_norm\":%.6f,"
            "\"active_phase_index\":%d,\"active_phase_x\":%d,\"active_phase_y\":%d,"
            "\"selective_reuse_active\":%s,\"forced_full_refresh\":%s,"
            "\"fallback_reason_flags\":%u,\"fresh_sample_count\":%d,\"carried_sample_count\":%d,"
            "\"unsampled_new_exposed_count\":%d,\"fresh_sample_fraction\":%.6f,"
            "\"carried_sample_fraction\":%.6f,\"unsampled_new_exposed_fraction\":%.6f,"
            "\"nonzero_histogram_bins\":%d,\"max_histogram_current_count\":%.6f,"
            "\"max_histogram_recent_count\":%.6f,\"support_seed_count\":%d,"
            "\"support_peak_score\":%.6f,\"coarse_component_count\":%d,"
            "\"coarse_oversized_count\":%d,\"dense_verify_component_count\":%d,"
            "\"adaptive_source_coarse_count\":%d,\"fresh_distinctness_ratio\":%.6f,"
            "\"blob_reject_area_count\":%d,"
            "\"blob_reject_ring_count\":%d,\"blob_reject_support_mass_count\":%d,"
            "\"blob_reject_quality_count\":%d,\"blob_examined_count\":%d,"
            "\"strongest_reject_reason\":%d,\"strongest_reject_peak_support\":%.6f,"
            "\"strongest_reject_area\":%.6f,\"strongest_reject_span\":%.6f,"
            "\"strongest_reject_ring_fraction\":%.6f,\"strongest_reject_support_mass\":%.6f,"
            "\"strongest_reject_quality\":%.6f,\"raw_candidate_index\":%d,"
            "\"winner_gate_active\":%s,\"winner_gate_reject_reason\":%d,"
            "\"winner_gate_max_span\":%.6f,\"winner_gate_max_area\":%.6f,"
            "\"winner_gate_min_rarity\":%.6f,\"winner_gate_max_commonness\":%.6f,"
            "\"winning_candidate_index\":%d,"
            "\"candidate_count\":%d,\"candidates\":[",
            frame_num,
            time_s,
            dbg->raw_candidate_valid ? "true" : "false",
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm,
            dbg->active_phase_index,
            dbg->active_phase_x,
            dbg->active_phase_y,
            dbg->selective_reuse_active ? "true" : "false",
            dbg->forced_full_refresh ? "true" : "false",
            dbg->fallback_reason_flags,
            dbg->fresh_sample_count,
            dbg->carried_sample_count,
            dbg->unsampled_new_exposed_count,
            (double)dbg->fresh_sample_fraction,
            (double)dbg->carried_sample_fraction,
            (double)dbg->unsampled_new_exposed_fraction,
            dbg->nonzero_histogram_bins,
            (double)dbg->max_histogram_current_count,
            (double)dbg->max_histogram_recent_count,
            dbg->support_seed_count,
            (double)dbg->support_peak_score,
            dbg->coarse_component_count,
            dbg->coarse_oversized_count,
            dbg->dense_verify_component_count,
            dbg->adaptive_source_coarse_count,
            (double)dbg->fresh_distinctness_ratio,
            dbg->blob_reject_area_count,
            dbg->blob_reject_ring_count,
            dbg->blob_reject_support_mass_count,
            dbg->blob_reject_quality_count,
            dbg->blob_examined_count,
            dbg->strongest_reject_reason,
            (double)dbg->strongest_reject_peak_support,
            (double)dbg->strongest_reject_area,
            (double)dbg->strongest_reject_span,
            (double)dbg->strongest_reject_ring_fraction,
            (double)dbg->strongest_reject_support_mass,
            (double)dbg->strongest_reject_quality,
            dbg->raw_candidate_index,
            dbg->winner_gate_active ? "true" : "false",
            dbg->winner_gate_reject_reason,
            (double)dbg->winner_gate_max_span,
            (double)dbg->winner_gate_max_area,
            (double)dbg->winner_gate_min_rarity,
            (double)dbg->winner_gate_max_commonness,
            dbg->winning_candidate_index,
            dbg->candidate_count);
    for (int i = 0; i < dbg->candidate_count && i < ANOMALY_DEBUG_TOP_COLOR_CANDIDATES; i++) {
        const anomaly_debug_color_candidate_t *c = &dbg->candidates[i];
        fprintf(out,
                "%s{\"index\":%d,\"valid\":%s,\"pixel_x\":%d,\"pixel_y\":%d,"
                "\"x_norm\":%.6f,\"y_norm\":%.6f,\"bbox_left_norm\":%.6f,"
                "\"bbox_top_norm\":%.6f,\"bbox_right_norm\":%.6f,\"bbox_bottom_norm\":%.6f,"
                "\"base_score\":%.6f,\"final_score\":%.6f,\"temporal_score\":%.6f,"
                "\"area\":%.6f,\"span\":%.6f,\"fill\":%.6f,\"center_share\":%.6f,"
                "\"quality\":%.6f,\"isolation_score\":%.6f,\"ring_fraction\":%.6f,"
                "\"support_mass\":%.6f,\"contrast_weight\":%.6f,\"hist_key\":%d,"
                "\"hist_current_count\":%.6f,\"hist_recent_count\":%.6f,\"hist_rarity_score\":%.6f,"
                "\"small_target_span_ratio\":%.6f,\"small_target_area_ratio\":%.6f,"
                "\"scene_commonness\":%.6f,\"retention_rank\":%.6f,\"above_threshold\":%s}",
                (i == 0) ? "" : ",",
                i,
                c->valid ? "true" : "false",
                c->pixel_x,
                c->pixel_y,
                (double)c->x_norm,
                (double)c->y_norm,
                (double)c->bbox_left_norm,
                (double)c->bbox_top_norm,
                (double)c->bbox_right_norm,
                (double)c->bbox_bottom_norm,
                (double)c->base_score,
                (double)c->final_score,
                (double)c->temporal_score,
                (double)c->area,
                (double)c->span,
                (double)c->fill,
                (double)c->center_share,
                (double)c->quality,
                (double)c->isolation_score,
                (double)c->ring_fraction,
                (double)c->support_mass,
                (double)c->contrast_weight,
                c->hist_key,
                (double)c->hist_current_count,
                (double)c->hist_recent_count,
                (double)c->hist_rarity_score,
                (double)c->small_target_span_ratio,
                (double)c->small_target_area_ratio,
                (double)c->scene_commonness,
                (double)c->retention_rank,
                c->above_threshold ? "true" : "false");
    }
    fprintf(out,
            "],\"target\":{\"enabled\":%s,\"valid\":%s,\"inside_scan_zone\":%s,"
            "\"refresh_skipped\":%s,\"sampled_this_frame\":%s,\"carried_from_history\":%s,"
            "\"pixel_x\":%d,\"pixel_y\":%d,\"sample_x\":%d,\"sample_y\":%d,"
            "\"x_norm\":%.6f,\"y_norm\":%.6f,\"hist_key\":%d,\"hist_current_count\":%.6f,"
            "\"hist_recent_count\":%.6f,\"hist_rarity_score\":%.6f,\"local_support_count\":%d,"
            "\"patch_valid_count\":%d,\"coherent_patch_cell_count\":%d,"
            "\"coherent_patch_fresh_cell_count\":%d,\"coherent_patch_multicell\":%s,"
            "\"patch_mean_u\":%.6f,\"patch_mean_v\":%.6f,\"patch_mean_luma\":%.6f,"
            "\"ring_mean_u\":%.6f,\"ring_mean_v\":%.6f,\"ring_mean_luma\":%.6f,"
            "\"ring_chroma_contrast\":%.6f,\"ring_luma_contrast\":%.6f,"
            "\"ring_neighbor_count\":%d,"
            "\"pre_support_score\":%.6f,\"support_score\":%.6f,"
            "\"support_map_local_peak\":%.6f,\"support_map_ring_mean\":%.6f,"
            "\"support_map_density\":%.6f,\"support_map_distinctness_ratio\":%.6f,"
            "\"support_map_compact_prominence\":%.6f,\"support_map_core_share\":%.6f,"
            "\"support_map_seed_floor\":%.6f,\"support_seed_eligible\":%s,"
            "\"component_seed_x\":%d,\"component_seed_y\":%d,"
            "\"component_peak_x\":%d,\"component_peak_y\":%d,"
            "\"component_area\":%.6f,\"component_span\":%.6f,\"component_fill\":%.6f,"
            "\"component_peak_support\":%.6f,\"component_mean_support\":%.6f,"
            "\"component_quality\":%.6f,\"component_ring_fraction\":%.6f,"
            "\"component_support_mass\":%.6f,\"component_rejected\":%s,"
            "\"component_rejection_reason\":%d,"
            "\"component_bbox_left_norm\":%.6f,\"component_bbox_top_norm\":%.6f,"
            "\"component_bbox_right_norm\":%.6f,\"component_bbox_bottom_norm\":%.6f,"
            "\"dropped_by_cap\":%s,\"dropped_by_nms\":%s,\"replaced_by_nms\":%s,"
            "\"rejected_by_winner_gate\":%s,"
            "\"nms_conflict_rank\":%d,\"nms_conflict_sample_x\":%d,"
            "\"nms_conflict_sample_y\":%d,"
            "\"pre_cap_rank\":%d,\"pre_cap_candidate_count\":%d,"
            "\"pre_cap_limit\":%d,\"pre_cap_retention_rank\":%.6f,"
            "\"winning_rank\":%d,\"winner_gate_reject_reason\":%d,"
            "\"extracted_candidate_index\":%d,"
            "\"matched_candidate_index\":%d,\"nearest_candidate_index\":%d,"
            "\"nearest_candidate_distance\":%.6f,\"winning_candidate_index\":%d,"
            "\"matched_candidate_score\":%.6f,\"matched_candidate_x_norm\":%.6f,"
            "\"matched_candidate_y_norm\":%.6f,\"matched_bbox_left_norm\":%.6f,"
            "\"matched_bbox_top_norm\":%.6f,\"matched_bbox_right_norm\":%.6f,"
            "\"matched_bbox_bottom_norm\":%.6f,\"stage\":\"%s\"}}\n",
            dbg->target.enabled ? "true" : "false",
            dbg->target.valid ? "true" : "false",
            dbg->target.inside_scan_zone ? "true" : "false",
            dbg->target.refresh_skipped ? "true" : "false",
            dbg->target.sampled_this_frame ? "true" : "false",
            dbg->target.carried_from_history ? "true" : "false",
            dbg->target.pixel_x,
            dbg->target.pixel_y,
            dbg->target.sample_x,
            dbg->target.sample_y,
            (double)dbg->target.x_norm,
            (double)dbg->target.y_norm,
            dbg->target.hist_key,
            (double)dbg->target.hist_current_count,
            (double)dbg->target.hist_recent_count,
            (double)dbg->target.hist_rarity_score,
            dbg->target.local_support_count,
            dbg->target.patch_valid_count,
            dbg->target.coherent_patch_cell_count,
            dbg->target.coherent_patch_fresh_cell_count,
            dbg->target.coherent_patch_multicell ? "true" : "false",
            (double)dbg->target.patch_mean_u,
            (double)dbg->target.patch_mean_v,
            (double)dbg->target.patch_mean_luma,
            (double)dbg->target.ring_mean_u,
            (double)dbg->target.ring_mean_v,
            (double)dbg->target.ring_mean_luma,
            (double)dbg->target.ring_chroma_contrast,
            (double)dbg->target.ring_luma_contrast,
            dbg->target.ring_neighbor_count,
            (double)dbg->target.pre_support_score,
            (double)dbg->target.support_score,
            (double)dbg->target.support_map_local_peak,
            (double)dbg->target.support_map_ring_mean,
            (double)dbg->target.support_map_density,
            (double)dbg->target.support_map_distinctness_ratio,
            (double)dbg->target.support_map_compact_prominence,
            (double)dbg->target.support_map_core_share,
            (double)dbg->target.support_map_seed_floor,
            dbg->target.support_seed_eligible ? "true" : "false",
            dbg->target.component_seed_x,
            dbg->target.component_seed_y,
            dbg->target.component_peak_x,
            dbg->target.component_peak_y,
            (double)dbg->target.component_area,
            (double)dbg->target.component_span,
            (double)dbg->target.component_fill,
            (double)dbg->target.component_peak_support,
            (double)dbg->target.component_mean_support,
            (double)dbg->target.component_quality,
            (double)dbg->target.component_ring_fraction,
            (double)dbg->target.component_support_mass,
            dbg->target.component_rejected ? "true" : "false",
            dbg->target.component_rejection_reason,
            (double)dbg->target.component_bbox_left_norm,
            (double)dbg->target.component_bbox_top_norm,
            (double)dbg->target.component_bbox_right_norm,
            (double)dbg->target.component_bbox_bottom_norm,
            dbg->target.dropped_by_cap ? "true" : "false",
            dbg->target.dropped_by_nms ? "true" : "false",
            dbg->target.replaced_by_nms ? "true" : "false",
            dbg->target.rejected_by_winner_gate ? "true" : "false",
            dbg->target.nms_conflict_rank,
            dbg->target.nms_conflict_sample_x,
            dbg->target.nms_conflict_sample_y,
            dbg->target.pre_cap_rank,
            dbg->target.pre_cap_candidate_count,
            dbg->target.pre_cap_limit,
            (double)dbg->target.pre_cap_retention_rank,
            dbg->target.winning_rank,
            dbg->target.winner_gate_reject_reason,
            dbg->target.extracted_candidate_index,
            dbg->target.matched_candidate_index,
            dbg->target.nearest_candidate_index,
            (double)dbg->target.nearest_candidate_distance,
            dbg->target.winning_candidate_index,
            (double)dbg->target.matched_candidate_score,
            (double)dbg->target.matched_candidate_x_norm,
            (double)dbg->target.matched_candidate_y_norm,
            (double)dbg->target.matched_bbox_left_norm,
            (double)dbg->target.matched_bbox_top_norm,
            (double)dbg->target.matched_bbox_right_norm,
            (double)dbg->target.matched_bbox_bottom_norm,
            color_target_stage_name(dbg->target.stage));
}

static void dump_gmv_debug(FILE *out, int frame_num, double time_s,
                           const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_gmv_t *dbg = &result->gmv_debug;
    if (!dbg->valid) return;
    fprintf(out, "\nGMV debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  discontinuity=%d sample_step=%d motion_step=%d anchors=%d\n",
            dbg->scene_discontinuity ? 1 : 0,
            dbg->sample_step,
            dbg->motion_step,
            dbg->anchor_count);
    fprintf(out, "  fit: a=%.5f b=%.5f tx=%.5f ty=%.5f scale=%.5f theta=%.2fdeg residual=%.5f\n",
            (double)dbg->fit_a,
            (double)dbg->fit_b,
            (double)dbg->fit_tx,
            (double)dbg->fit_ty,
            (double)dbg->fit_scale,
            (double)dbg->fit_theta_deg,
            (double)dbg->fit_mean_residual);
    fprintf(out, "  consistency: rstd=%.5f rmax=%.5f dxstd=%.5f dystd=%.5f qspread=%.5f\n",
            (double)dbg->fit_anchor_residual_std,
            (double)dbg->fit_anchor_residual_max,
            (double)dbg->fit_motion_dx_std,
            (double)dbg->fit_motion_dy_std,
            (double)dbg->fit_quadrant_residual_spread);
    for (int i = 0; i < dbg->anchor_count && i < ANOMALY_GMV_MAX_DEBUG_ANCHORS; i++) {
        const anomaly_debug_gmv_anchor_t *a = &dbg->anchors[i];
        fprintf(out,
                "    anchor[%d] zone=(%d,%d) px=(%d,%d) xy=(%.4f,%.4f) tex=%d match=(%d,%d) sad=%d second=%d\n",
                i,
                a->zone_gx,
                a->zone_gy,
                a->pixel_x,
                a->pixel_y,
                (double)a->x_norm,
                (double)a->y_norm,
                a->texture_score,
                a->match_dx,
                a->match_dy,
                a->best_sad,
                a->second_best_sad);
    }
}

// Remove extension and directory prefix to get a bare basename.
static void basename_noext(const char *path, char *out, size_t out_sz) {
    const char *slash = strrchr(path, '/');
    const char *name  = slash ? slash + 1 : path;
    const char *dot   = strrchr(name, '.');
    size_t len = dot ? (size_t)(dot - name) : strlen(name);
    if (len >= out_sz) len = out_sz - 1;
    memcpy(out, name, len);
    out[len] = '\0';
}

static void usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s <input.mp4> [options]\n"
        "\n"
        "Output files (written alongside the input by default):\n"
        "  -o <file.mp4>    Annotated video  (default: <input>_annotated.mp4)\n"
        "  -c <file.csv>    Detection log    (default: <input>_detections.csv)\n"
        "  --no-video       Skip annotated video (CSV only)\n"
        "  --app-display-output\n"
        "                   Write/draw the app-visible annotation stream after\n"
        "                   cadence and ROI smoothing instead of raw detector boxes\n"
        "\n"
        "Detector settings:\n"
        "  -t <float>       Score threshold  (default: %.1f)\n"
        "  -m <int>         Min hits to show box (default: %d)\n"
        "  -s <float>       Scan zone 0.5–1.0    (default: %.2f)\n"
        "  -a <int>         Algorithm mask 1=color 2=thermal 4=motion 8=saliency (default: 7)\n"
        "  -p <wh|bh>       Thermal polarity: wh=white-hot bh=black-hot (default: wh)\n"
        "  --registration <gmv|affine>\n"
        "                   Camera registration backend (default: gmv)\n"
        "  --movement-estimator <legacy-affine|layered-shadow|layered-active>\n"
        "                   Parallax-aware movement sidecar mode\n"
        "                   (default: legacy-affine)\n"
        "  --stride <int>   Analyze every Nth frame (default: 1)\n"
        "  --stride-mode <fixed|adaptive>\n"
        "                   Carry app stride mode through config plumbing\n"
        "  --adaptive-min-stride-frames <int>\n"
        "                   Adaptive stride minimum (default: 2)\n"
        "  --adaptive-max-stride-seconds <float>\n"
        "                   Adaptive stride max latency seconds (default: 1.0)\n"
        "  --pixel-step <n> Override appearance sampling step (default: 0=Auto)\n"
        "  --color-frontend <legacy|fresh-rgba|fresh-yuv>\n"
        "                   Visible-color frontend mode (default here: fresh-rgba)\n"
        "  --min-delta <f>  Override ANOMALY_THERMAL_MIN_DELTA (default: %.1f)\n"
        "  --small-target-fraction <f>\n"
        "                   Override the maximum normalized on-screen size\n"
        "                   treated as a small target (default: %.6f)\n"
        "  --debug-frame N  Dump saliency candidate details for frame N\n"
        "  --debug-time-start S  Dump debug details beginning at time S seconds\n"
        "  --debug-time-end S    Dump debug details ending at time S seconds\n"
        "  --debug-overlay  Burn saliency candidate ranks / latch marker into video\n"
        "  --time-start S   Decode and score beginning at source time S seconds\n"
        "  --time-end S     Stop scoring at source time S seconds\n"
        "  --summary-json <file>\n"
        "                   Write a machine-readable run summary for reviewed\n"
        "                   clip scoring / regression reports\n"
        "  --thermal-debug-jsonl <file>\n"
        "                   Write per-frame thermal candidate telemetry as JSONL\n"
        "  --thermal-target cx,cy\n"
        "                   Trace dense thermal extraction for one normalized target\n"
        "                   point and include the stage-by-stage outcome in thermal\n"
        "                   debug JSONL rows\n"
        "\n"
        "Diagnostic:\n"
        "  --probe cx,cy    Sample the detector's scored point nearest the given\n"
        "                   normalised position\n"
        "                   every frame; write detector-side spatial/temporal scores\n"
        "                   to _probe.csv. Use this to tune threshold / min-delta.\n"
        "\n"
        "App-parity mode:\n"
        "  --app-defaults   Derive native detector config from the app's Kotlin\n"
        "                   captured-playback review defaults and mapping logic\n"
        "                   Add --app-display-output when qualifying app-visible\n"
        "                   behavior; without it CSV/video report raw detector boxes.\n"
        "  --app-appearance <auto|thermal|color>\n"
        "  --app-motion <on|off>\n"
        "  --app-saliency <on|off>\n"
        "  --app-sensitivity <0..1>\n"
        "  --app-motion-sensitivity <0..1>\n"
        "  --app-stride-mode <fixed|adaptive>\n"
        "  --app-adaptive-min-stride-frames <int>\n"
        "  --app-adaptive-max-stride-seconds <float>\n"
        "                   These flags mirror the app-side config derivation in\n"
        "                   AnomalyModels.kt before the native bridge call.\n"
        "\n"
        "Tip for IR/thermal footage: add  -p bh -a 6  (thermal + motion, black-hot)\n",
        prog,
        (double)ANOMALY_DEFAULT_SCORE_THRESHOLD,
        ANOMALY_DEFAULT_MIN_HITS,
        (double)ANOMALY_SCAN_ZONE_DEFAULT,
        (double)ANOMALY_THERMAL_MIN_DELTA,
        (double)ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT);
}

// ── main ───────────────────────────────────────────────────────────────────

int main(int argc, char **argv) {
    if (argc < 2 || !strcmp(argv[1], "-h") || !strcmp(argv[1], "--help")) {
        usage(argv[0]);
        return argc < 2 ? 1 : 0;
    }

    const char *input = argv[1];
    char output_video[1024] = "";
    char output_csv[1024]   = "";
    char output_summary_json[1024] = "";
    char thermal_debug_jsonl_path[1024] = "";
    char color_debug_jsonl_path[1024] = "";
    char color_target_csv_path[1024] = "";
    int  write_video = 1;
    int  app_display_output = 0;

    anomaly_config_t cfg = {
        .enabled           = true,
        .algorithm_mask    = ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_MOTION,
        .registration_mode = ANOMALY_REGISTRATION_GMV,
        .movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE,
        .stride_mode       = ANOMALY_STRIDE_MODE_FIXED,
        .frame_stride      = 1,
        .adaptive_min_stride_frames = 2,
        .adaptive_max_stride_frames = 33,
        .adaptive_max_stride_seconds = 1.0f,
        .pixel_step        = 0,
        .score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD,
        .motion_evidence_scale = 1.0f,
        .min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION,
        .thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT,
        .scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT,
        .min_hits          = ANOMALY_DEFAULT_MIN_HITS,
        .thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA,
        .small_target_screen_fraction = ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT,
        .color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA,
    };
    int requested_color_frontend_mode = cfg.color_frontend_mode;
    int requested_movement_estimator_mode = cfg.movement_estimator_mode;
    bool color_frontend_overridden = false;
    bool movement_estimator_overridden = false;

    // Probe state (--probe cx,cy).
    int    probe_active  = 0;
    float  probe_cx      = 0.0f, probe_cy = 0.0f;
    char   probe_csv_path[1024] = "";
    int    thermal_target_active = 0;
    float  thermal_target_cx = 0.0f, thermal_target_cy = 0.0f;
    int    debug_frame = -1;
    double debug_time_start = -1.0;
    double debug_time_end = -1.0;
    double clip_time_start = 0.0;
    double clip_time_end = -1.0;
    int    debug_overlay = 0;
    // min-delta override (0 = use compiled-in constant).
    float  min_delta_override = 0.0f;
    bool   app_parity_mode = false;
    app_anomaly_config_t app_cfg = default_app_cfg();

    for (int i = 2; i < argc; i++) {
        if      (!strcmp(argv[i], "-o")          && i+1 < argc) snprintf(output_video, sizeof(output_video), "%s", argv[++i]);
        else if (!strcmp(argv[i], "-c")          && i+1 < argc) snprintf(output_csv,   sizeof(output_csv),   "%s", argv[++i]);
        else if (!strcmp(argv[i], "-t")          && i+1 < argc) cfg.score_threshold   = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-m")          && i+1 < argc) cfg.min_hits          = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-s")          && i+1 < argc) cfg.scan_zone         = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-a")          && i+1 < argc) cfg.algorithm_mask    = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--registration") && i+1 < argc) {
            const char *mode = argv[++i];
            cfg.registration_mode = strcmp(mode, "affine") == 0
                                   ? ANOMALY_REGISTRATION_AFFINE
                                   : ANOMALY_REGISTRATION_GMV;
        }
        else if (!strcmp(argv[i], "--movement-estimator") && i+1 < argc) {
            const char *mode = argv[++i];
            if (strcmp(mode, "legacy-affine") == 0 || strcmp(mode, "legacy_affine") == 0) {
                requested_movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
            } else if (strcmp(mode, "layered-shadow") == 0 || strcmp(mode, "layered_shadow") == 0) {
                requested_movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW;
            } else if (strcmp(mode, "layered-active") == 0 || strcmp(mode, "layered_active") == 0) {
                requested_movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
            } else {
                fprintf(stderr, "Error: --movement-estimator expects legacy-affine, layered-shadow, or layered-active\n");
                return 1;
            }
            movement_estimator_overridden = true;
            cfg.movement_estimator_mode = requested_movement_estimator_mode;
        }
        else if (!strcmp(argv[i], "--stride")    && i+1 < argc) cfg.frame_stride      = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--stride-mode") && i+1 < argc) {
            const char *mode = argv[++i];
            if (strcmp(mode, "adaptive") == 0) {
                cfg.stride_mode = ANOMALY_STRIDE_MODE_ADAPTIVE;
            } else if (strcmp(mode, "fixed") == 0) {
                cfg.stride_mode = ANOMALY_STRIDE_MODE_FIXED;
            } else {
                fprintf(stderr, "Error: --stride-mode expects fixed or adaptive\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--adaptive-min-stride-frames") && i+1 < argc) {
            cfg.adaptive_min_stride_frames = app_clampi(atoi(argv[++i]), 2, 33);
        }
        else if (!strcmp(argv[i], "--adaptive-max-stride-seconds") && i+1 < argc) {
            cfg.adaptive_max_stride_seconds = app_clampf((float)atof(argv[++i]), 0.1f, 10.0f);
        }
        else if (!strcmp(argv[i], "--pixel-step") && i+1 < argc) cfg.pixel_step       = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--color-frontend") && i+1 < argc) {
            const char *mode = argv[++i];
            if (strcmp(mode, "legacy") == 0) {
                requested_color_frontend_mode = ANOMALY_COLOR_FRONTEND_LEGACY;
            } else if (strcmp(mode, "fresh-rgba") == 0) {
                requested_color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
            } else if (strcmp(mode, "fresh-yuv") == 0) {
                requested_color_frontend_mode = ANOMALY_COLOR_FRONTEND_FRESH_YUV;
            } else {
                fprintf(stderr, "Error: --color-frontend expects legacy, fresh-rgba, or fresh-yuv\n");
                return 1;
            }
            cfg.color_frontend_mode = requested_color_frontend_mode;
            color_frontend_overridden = true;
        }
        else if (!strcmp(argv[i], "--min-delta") && i+1 < argc) min_delta_override    = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "--small-target-fraction") && i+1 < argc) {
            cfg.small_target_screen_fraction = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-defaults")) {
            app_parity_mode = true;
        }
        else if (!strcmp(argv[i], "--app-appearance") && i+1 < argc) {
            app_parity_mode = true;
            if (!parse_app_appearance(argv[++i], &app_cfg.appearance_selection)) {
                fprintf(stderr, "Error: --app-appearance expects auto, thermal, or color\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--app-motion") && i+1 < argc) {
            app_parity_mode = true;
            const char *value = argv[++i];
            if (!strcmp(value, "on")) app_cfg.motion_algorithm_enabled = true;
            else if (!strcmp(value, "off")) app_cfg.motion_algorithm_enabled = false;
            else {
                fprintf(stderr, "Error: --app-motion expects on or off\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--app-saliency") && i+1 < argc) {
            app_parity_mode = true;
            const char *value = argv[++i];
            if (!strcmp(value, "on")) app_cfg.saliency_enabled = true;
            else if (!strcmp(value, "off")) app_cfg.saliency_enabled = false;
            else {
                fprintf(stderr, "Error: --app-saliency expects on or off\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--app-sensitivity") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.sensitivity = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-motion-sensitivity") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.motion_evidence_sensitivity = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-registration") && i+1 < argc) {
            app_parity_mode = true;
            const char *value = argv[++i];
            app_cfg.registration_mode =
                strcmp(value, "affine") == 0 ? APP_REGISTRATION_AFFINE : APP_REGISTRATION_GMV;
        }
        else if (!strcmp(argv[i], "--app-polarity") && i+1 < argc) {
            app_parity_mode = true;
            const char *value = argv[++i];
            app_cfg.thermal_polarity =
                strcmp(value, "bh") == 0 ? APP_THERMAL_POLARITY_BLACK_HOT : APP_THERMAL_POLARITY_WHITE_HOT;
        }
        else if (!strcmp(argv[i], "--app-scan-zone") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.scan_zone = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-min-hits") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.min_hits = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-frame-stride") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.frame_stride = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-stride-mode") && i+1 < argc) {
            app_parity_mode = true;
            const char *mode = argv[++i];
            if (strcmp(mode, "adaptive") == 0) {
                app_cfg.stride_mode = ANOMALY_STRIDE_MODE_ADAPTIVE;
            } else if (strcmp(mode, "fixed") == 0) {
                app_cfg.stride_mode = ANOMALY_STRIDE_MODE_FIXED;
            } else {
                fprintf(stderr, "Error: --app-stride-mode expects fixed or adaptive\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--app-adaptive-min-stride-frames") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.adaptive_min_stride_frames = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-adaptive-max-stride-seconds") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.adaptive_max_stride_seconds = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-pixel-step") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.pixel_step = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-min-area-fraction") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.min_area_fraction = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-thermal-min-delta") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.thermal_min_delta = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--app-small-target-fraction") && i+1 < argc) {
            app_parity_mode = true;
            app_cfg.small_target_screen_fraction = (float)atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "-p")          && i+1 < argc) {
            cfg.thermal_polarity = strcmp(argv[++i], "bh") == 0
                                   ? ANOMALY_THERMAL_BLACK_HOT : ANOMALY_THERMAL_WHITE_HOT;
        }
        else if (!strcmp(argv[i], "--probe")     && i+1 < argc) {
            if (sscanf(argv[++i], "%f,%f", &probe_cx, &probe_cy) == 2) {
                probe_active = 1;
            } else {
                fprintf(stderr, "Error: --probe expects cx,cy (e.g. --probe 0.42,0.28)\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--thermal-target") && i+1 < argc) {
            if (sscanf(argv[++i], "%f,%f", &thermal_target_cx, &thermal_target_cy) == 2) {
                thermal_target_active = 1;
            } else {
                fprintf(stderr, "Error: --thermal-target expects cx,cy (e.g. --thermal-target 0.42,0.28)\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--debug-frame") && i+1 < argc) {
            debug_frame = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-time-start") && i+1 < argc) {
            debug_time_start = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-time-end") && i+1 < argc) {
            debug_time_end = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--time-start") && i+1 < argc) {
            clip_time_start = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--time-end") && i+1 < argc) {
            clip_time_end = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--summary-json") && i+1 < argc) {
            snprintf(output_summary_json, sizeof(output_summary_json), "%s", argv[++i]);
        }
        else if (!strcmp(argv[i], "--thermal-debug-jsonl") && i+1 < argc) {
            snprintf(thermal_debug_jsonl_path, sizeof(thermal_debug_jsonl_path), "%s", argv[++i]);
        }
        else if (!strcmp(argv[i], "--color-debug-jsonl") && i+1 < argc) {
            snprintf(color_debug_jsonl_path, sizeof(color_debug_jsonl_path), "%s", argv[++i]);
        }
        else if (!strcmp(argv[i], "--color-target-csv") && i+1 < argc) {
            snprintf(color_target_csv_path, sizeof(color_target_csv_path), "%s", argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-overlay")) {
            debug_overlay = 1;
        }
        else if (!strcmp(argv[i], "--app-display-output")) app_display_output = 1;
        else if (!strcmp(argv[i], "--no-video")) write_video = 0;
        else { fprintf(stderr, "Unknown option: %s\n\n", argv[i]); usage(argv[0]); return 1; }
    }

    if (app_parity_mode) {
        derive_native_cfg_from_app(&app_cfg, &cfg);
        if (color_frontend_overridden) {
            cfg.color_frontend_mode = requested_color_frontend_mode;
        }
        if (movement_estimator_overridden) {
            cfg.movement_estimator_mode = requested_movement_estimator_mode;
        }
    }
    bool app_qualification_ready = app_parity_mode && app_display_output;
    if (app_parity_mode && !app_display_output) {
        fprintf(stderr,
                "Warning: --app-defaults without --app-display-output runs the app-derived detector config, "
                "but reports raw detector boxes instead of the app-visible annotation stream.\n");
    }

    // ── Auto-detect dimensions and fps with ffprobe ──────────────────────
    int    W = 0, H = 0;
    double fps = 30.0, full_duration_s = 0.0;
    {
        char cmd[2048];
        snprintf(cmd, sizeof(cmd),
            "ffprobe -v error -select_streams v:0 "
            "-show_entries stream=width,height,avg_frame_rate,r_frame_rate,duration "
            "-of default=noprint_wrappers=1:nokey=1 \"%s\" 2>/dev/null", input);
        FILE *p = popen(cmd, "r");
        if (!p) { fprintf(stderr, "Error: ffprobe failed — is ffprobe on PATH?\n"); return 1; }
        char avg_fps_text[64] = "";
        char real_fps_text[64] = "";
        char duration_text[64] = "";
        if (fscanf(p, "%d\n%d\n%63s\n%63s\n%63s", &W, &H,
                   avg_fps_text, real_fps_text, duration_text) != 5) {
            pclose(p);
            fprintf(stderr, "Error: could not parse ffprobe output for \"%s\"\n", input);
            return 1;
        }
        pclose(p);
        double avg_fps = parse_ratio_or_zero(avg_fps_text);
        double real_fps = parse_ratio_or_zero(real_fps_text);
        full_duration_s = parse_ratio_or_zero(duration_text);
        if (avg_fps > 0.0 && avg_fps < 240.0) {
            fps = avg_fps;
        } else if (real_fps > 0.0 && real_fps < 240.0) {
            fps = real_fps;
        }
    }
    if (W <= 0 || H <= 0) {
        fprintf(stderr, "Error: could not read video info from \"%s\"\n", input);
        return 1;
    }
    cfg.adaptive_min_stride_frames = app_clampi(cfg.adaptive_min_stride_frames, 2, 33);
    cfg.adaptive_max_stride_seconds = app_clampf(cfg.adaptive_max_stride_seconds, 0.1f, 10.0f);
    cfg.adaptive_max_stride_frames = app_clampi(
            (int)floor(fps * (double)cfg.adaptive_max_stride_seconds + 0.5),
            cfg.adaptive_min_stride_frames,
            33);
    if (clip_time_start < 0.0) clip_time_start = 0.0;
    if (full_duration_s > 0.0) {
        clip_time_start = clamp_double(clip_time_start, 0.0, full_duration_s);
    }
    if (clip_time_end >= 0.0 && clip_time_end < clip_time_start) {
        fprintf(stderr, "Error: --time-end must be >= --time-start\n");
        return 1;
    }
    double requested_media_end = clip_time_end;
    if (requested_media_end < 0.0) {
        requested_media_end = full_duration_s;
    } else if (full_duration_s > 0.0 && requested_media_end > full_duration_s) {
        requested_media_end = full_duration_s;
    }
    double clip_duration_s = 0.0;
    if (requested_media_end > clip_time_start) {
        clip_duration_s = requested_media_end - clip_time_start;
    } else if (full_duration_s > 0.0 && clip_time_end < 0.0) {
        clip_duration_s = full_duration_s - clip_time_start;
    }
    int total_frames = (clip_duration_s > 0.0) ? (int)(clip_duration_s * fps + 0.5) : 0;

    // ── Build default output paths in the same directory as the input ────
    char dir_prefix[512] = "";
    {
        const char *slash = strrchr(input, '/');
        if (slash) {
            size_t dlen = (size_t)(slash - input) + 1;
            if (dlen >= sizeof(dir_prefix)) dlen = sizeof(dir_prefix) - 1;
            memcpy(dir_prefix, input, dlen);
            dir_prefix[dlen] = '\0';
        }
    }
    char base[256];
    basename_noext(input, base, sizeof(base));

    if (!output_video[0])
        snprintf(output_video, sizeof(output_video), "%s%s_annotated.mp4", dir_prefix, base);
    if (!output_csv[0])
        snprintf(output_csv, sizeof(output_csv), "%s%s_detections.csv", dir_prefix, base);
    if (probe_active && !probe_csv_path[0])
        snprintf(probe_csv_path, sizeof(probe_csv_path), "%s%s_probe.csv", dir_prefix, base);

    // Effective min-delta (CLI override or compiled-in constant).
    double effective_min_delta = (min_delta_override > 0.0f)
                                 ? (double)min_delta_override
                                 : (double)ANOMALY_THERMAL_MIN_DELTA;
    cfg.thermal_min_delta = (float)effective_min_delta;
    cfg.thermal_debug_target_enabled = thermal_target_active != 0;
    cfg.thermal_debug_target_x_norm = thermal_target_cx;
    cfg.thermal_debug_target_y_norm = thermal_target_cy;

    // ── Print run summary ────────────────────────────────────────────────
    fprintf(stderr, "\n");
    fprintf(stderr, "Input      : %s\n", input);
    fprintf(stderr, "Dimensions : %dx%d @ %.2f fps", W, H, fps);
    if (total_frames > 0) fprintf(stderr, "  (~%d frames, %.1fs)", total_frames, clip_duration_s);
    fprintf(stderr, "\n");
    fprintf(stderr, "CSV        : %s\n", output_csv);
    if (write_video) fprintf(stderr, "Video      : %s\n", output_video);
    if (output_summary_json[0]) fprintf(stderr, "Summary    : %s\n", output_summary_json);
    if (probe_active) fprintf(stderr, "Probe CSV  : %s  (cx=%.3f cy=%.3f)\n",
                              probe_csv_path, (double)probe_cx, (double)probe_cy);
    if (thermal_target_active) fprintf(stderr, "Thermal target: cx=%.3f cy=%.3f\n",
                                       (double)thermal_target_cx, (double)thermal_target_cy);
    if (clip_time_start > 0.0 || clip_time_end >= 0.0) {
        if (clip_time_end >= 0.0) {
            fprintf(stderr, "Clip range : %.3fs to %.3fs\n", clip_time_start, requested_media_end);
        } else {
            fprintf(stderr, "Clip range : %.3fs to end\n", clip_time_start);
        }
    }
    fprintf(stderr, "\n");
    fprintf(stderr, "Detector settings:\n");
    if (app_parity_mode) {
        fprintf(stderr, "  mode       = app-parity\n");
        fprintf(stderr, "  app appearance = %s\n", app_appearance_name(app_cfg.appearance_selection));
        fprintf(stderr, "  app motion     = %s\n", app_cfg.motion_algorithm_enabled ? "on" : "off");
        fprintf(stderr, "  app saliency   = %s\n", app_cfg.saliency_enabled ? "on" : "off");
        fprintf(stderr, "  app sensitivity= %.2f\n", (double)app_cfg.sensitivity);
        fprintf(stderr, "  app motion sens= %.2f\n", (double)app_cfg.motion_evidence_sensitivity);
        fprintf(stderr, "  app register   = %s\n", app_registration_name(app_cfg.registration_mode));
        fprintf(stderr, "  app polarity   = %s\n", app_polarity_name(app_cfg.thermal_polarity));
        fprintf(stderr, "  app stride mode= %s\n", stride_mode_name(app_cfg.stride_mode));
    }
    fprintf(stderr, "  threshold  = %.2f\n", (double)cfg.score_threshold);
    fprintf(stderr, "  min_hits   = %d\n",   cfg.min_hits);
    fprintf(stderr, "  scan_zone  = %.2f\n", (double)cfg.scan_zone);
    fprintf(stderr, "  algorithm  = %d (%s%s%s)\n", cfg.algorithm_mask,
        (cfg.algorithm_mask & ANOMALY_ALGO_COLOR)   ? "color "   : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_THERMAL) ? "thermal " : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_MOTION)  ? "motion "  : "");
    if (cfg.algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE)
        fprintf(stderr, "               %s\n", "motion_tolerance");
    if (cfg.algorithm_mask & ANOMALY_ALGO_PERSIST)
        fprintf(stderr, "               %s\n", "saliency");
    fprintf(stderr, "  polarity   = %s\n",
        cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "black-hot" : "white-hot");
    fprintf(stderr, "  register   = %s\n",
        cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv");
    fprintf(stderr, "  movement   = %s\n", movement_estimator_name(cfg.movement_estimator_mode));
    fprintf(stderr, "  stride     = %s fixed=%d adaptive=%d..%d frames (%.1fs)\n",
            stride_mode_name(cfg.stride_mode),
            cfg.frame_stride,
            cfg.adaptive_min_stride_frames,
            cfg.adaptive_max_stride_frames,
            (double)cfg.adaptive_max_stride_seconds);
    fprintf(stderr, "  pixel_step = %d%s\n", cfg.pixel_step,
            cfg.pixel_step <= 0 ? " (Auto)" : "");
    fprintf(stderr, "  color      = %s\n", color_frontend_name(cfg.color_frontend_mode));
    fprintf(stderr, "  min_delta  = %.1f%s\n", effective_min_delta,
            min_delta_override > 0.0f ? " (override)" : "");
    fprintf(stderr, "  small      = 1/%.1f\n",
            1.0 / (double)cfg.small_target_screen_fraction);
    fprintf(stderr, "\n");

    // ── Allocate frame buffer ────────────────────────────────────────────
    size_t frame_bytes = (size_t)W * H * 4;
    uint8_t *rgba = malloc(frame_bytes);
    int debug_window_active = (debug_time_start >= 0.0 || debug_time_end >= 0.0);
    int needs_clean_rgba = app_display_output || debug_overlay || debug_frame > 0 || debug_window_active;
    uint8_t *raw_rgba = needs_clean_rgba ? malloc(frame_bytes) : NULL;
    if (!rgba) { fprintf(stderr, "Out of memory\n"); return 1; }
    if (needs_clean_rgba && !raw_rgba) {
        fprintf(stderr, "Out of memory\n");
        free(rgba);
        return 1;
    }

    // ── Open CSV ─────────────────────────────────────────────────────────
    FILE *csv = fopen(output_csv, "w");
    if (!csv) { fprintf(stderr, "Cannot write %s\n", output_csv); return 1; }

    // Header lines document the settings so the file is self-contained.
    fprintf(csv, "# input: %s\n", input);
    fprintf(csv, "# output_stream: %s\n",
            app_display_output ? "app-display" : "raw-detector");
    fprintf(csv, "# app_qualification_ready: %s\n",
            app_qualification_ready ? "true" : "false");
    if (app_parity_mode) {
        fprintf(csv,
                "# mode: app-parity  app_appearance: %s  app_motion: %d  app_saliency: %d  "
                "app_sensitivity: %.4f  app_motion_sensitivity: %.4f  app_registration: %s  app_polarity: %s\n",
                app_appearance_name(app_cfg.appearance_selection),
                app_cfg.motion_algorithm_enabled ? 1 : 0,
                app_cfg.saliency_enabled ? 1 : 0,
                (double)app_cfg.sensitivity,
                (double)app_cfg.motion_evidence_sensitivity,
                app_registration_name(app_cfg.registration_mode),
                app_polarity_name(app_cfg.thermal_polarity));
    }
    fprintf(csv, "# threshold: %.2f  min_hits: %d  scan_zone: %.2f  "
                 "algo: %d  polarity: %s  stride_mode: %s  stride: %d  adaptive_min_stride_frames: %d  adaptive_max_stride_frames: %d  adaptive_max_stride_seconds: %.3f  pixel_step: %d  registration: %s  "
                 "movement: %s  color_frontend: %s  small_target_fraction: %.6f\n",
            (double)cfg.score_threshold, cfg.min_hits, (double)cfg.scan_zone,
            cfg.algorithm_mask,
            cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "bh" : "wh",
            stride_mode_name(cfg.stride_mode),
            cfg.frame_stride,
            cfg.adaptive_min_stride_frames,
            cfg.adaptive_max_stride_frames,
            (double)cfg.adaptive_max_stride_seconds,
            cfg.pixel_step,
            cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv",
            movement_estimator_name(cfg.movement_estimator_mode),
            color_frontend_name(cfg.color_frontend_mode),
            (double)cfg.small_target_screen_fraction);
    if (clip_time_end >= 0.0 || clip_duration_s > 0.0) {
        fprintf(csv, "# clip_start_s: %.3f  clip_end_s: %.3f\n",
                clip_time_start,
                clip_time_end >= 0.0 ? requested_media_end : (clip_time_start + clip_duration_s));
    } else {
        fprintf(csv, "# clip_start_s: %.3f  clip_end_s: end\n", clip_time_start);
    }
    fprintf(csv, "# label column: G=good/true-positive  "
                 "B=bad/false-positive  ?=unsure  (leave blank to skip)\n");
    fprintf(csv, "frame,time_s,algorithm,cx_norm,cy_norm,"
                 "box_w_norm,box_h_norm,weight,label\n");

    // ── Open FFmpeg input pipe ───────────────────────────────────────────
    char in_cmd[2048];
    if (clip_duration_s > 0.0) {
        snprintf(in_cmd, sizeof(in_cmd),
            "ffmpeg -ss %.6f -i \"%s\" -t %.6f -pix_fmt rgba -f rawvideo pipe:1 2>/dev/null",
            clip_time_start, input, clip_duration_s);
    } else {
        snprintf(in_cmd, sizeof(in_cmd),
            "ffmpeg -ss %.6f -i \"%s\" -pix_fmt rgba -f rawvideo pipe:1 2>/dev/null",
            clip_time_start, input);
    }
    FILE *in_pipe = popen(in_cmd, "r");
    if (!in_pipe) { fprintf(stderr, "Error: ffmpeg decode pipe failed\n"); return 1; }

    // ── Open FFmpeg output pipe (annotated video) ────────────────────────
    FILE *out_pipe = NULL;
    if (write_video) {
        char out_cmd[2048];
        snprintf(out_cmd, sizeof(out_cmd),
            "ffmpeg -f rawvideo -pix_fmt rgba -video_size %dx%d -framerate %.4f "
            "-i pipe:0 -vf format=yuv420p -c:v libx264 -crf 23 -movflags +faststart "
            "-y \"%s\" 2>/dev/null",
            W, H, fps, output_video);
        out_pipe = popen(out_cmd, "w");
        if (!out_pipe) { fprintf(stderr, "Error: ffmpeg encode pipe failed\n"); return 1; }
    }

    // ── Open probe CSV (if requested) ────────────────────────────────────
    FILE *probe_csv = NULL;
    FILE *thermal_debug_jsonl = NULL;
    FILE *color_debug_jsonl = NULL;
    color_debug_target_row_t *color_target_rows = NULL;
    int color_target_row_count = 0;
    int color_target_row_cursor = 0;
    if (probe_active) {
        probe_csv = fopen(probe_csv_path, "w");
        if (!probe_csv) {
            fprintf(stderr, "Cannot write probe CSV: %s\n", probe_csv_path);
            return 1;
        }
        fprintf(probe_csv, "# probe location: cx=%.4f cy=%.4f  polarity=%s\n",
                (double)probe_cx, (double)probe_cy,
                cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "black-hot" : "white-hot");
        fprintf(probe_csv, "# effective_score matches anomaly_analysis.c exactly:\n");
        fprintf(probe_csv, "#   warmup  -> spatial_abs_delta / spatial_std\n");
        fprintf(probe_csv, "#   bg_ready -> (temporal_delta - temporal_mean) / temporal_norm\n");
        fprintf(probe_csv, "# threshold=%.2f  min_delta=%.1f\n",
                (double)cfg.score_threshold, effective_min_delta);
        fprintf(probe_csv,
                "frame,time_s,px,py,inside_scan_zone,bg_ready,used_temporal,"
                "sample_luma,spatial_mean,spatial_std,spatial_abs_delta,spatial_score,"
                "temporal_delta,temporal_mean,temporal_norm,temporal_score,"
                "effective_score,passes_min_delta,passes_threshold\n");
    }
    if (thermal_debug_jsonl_path[0]) {
        thermal_debug_jsonl = fopen(thermal_debug_jsonl_path, "w");
        if (!thermal_debug_jsonl) {
            fprintf(stderr, "Cannot write thermal debug JSONL: %s\n", thermal_debug_jsonl_path);
            return 1;
        }
    }
    if (color_target_csv_path[0]) {
        if (load_color_target_csv(color_target_csv_path, &color_target_rows, &color_target_row_count) != 0) {
            fprintf(stderr, "Cannot read color target CSV: %s\n", color_target_csv_path);
            if (thermal_debug_jsonl) fclose(thermal_debug_jsonl);
            return 1;
        }
    }
    if (color_debug_jsonl_path[0]) {
        color_debug_jsonl = fopen(color_debug_jsonl_path, "w");
        if (!color_debug_jsonl) {
            fprintf(stderr, "Cannot write color debug JSONL: %s\n", color_debug_jsonl_path);
            if (thermal_debug_jsonl) fclose(thermal_debug_jsonl);
            free(color_target_rows);
            return 1;
        }
    }

    // ── Process frames ───────────────────────────────────────────────────
    anomaly_state_t state;
    anomaly_state_init(&state);
    anomaly_detector_annotation_cadence_snapshot_state_t display_cadence;
    anomaly_detector_annotation_cadence_snapshot_state_init(&display_cadence);
    int display_cadence_frames = anomaly_detector_default_window_frames((float)fps);
    int64_t display_analyzed_frame_count = 0;

    int    frame_num        = 0;
    int    detection_frames = 0;
    int    total_boxes      = 0;
    int    rescan_full_frames = 0;
    int    rescan_partial_frames = 0;
    int    rescan_target_only_frames = 0;
    int    rescan_stride_skip_frames = 0;
    int    scan_reason_counts[sizeof(kScanReasonCounters) / sizeof(kScanReasonCounters[0])] = {0};
    int    registration_reason_counts[sizeof(kRegistrationReasonCounters) / sizeof(kRegistrationReasonCounters[0])] = {0};
    int    adaptive_stride_reason_counts[sizeof(kAdaptiveStrideReasonCounters) / sizeof(kAdaptiveStrideReasonCounters[0])] = {0};
    int64_t adaptive_stride_sum = 0;
    int    adaptive_stride_min = 0;
    int    adaptive_stride_max = 0;
    int    adaptive_stride_frame_count = 0;
    double adaptive_motion_load_sum = 0.0;
    int64_t stage_timing_total_us[ANOMALY_TIMING_STAGE_COUNT] = {0};
    int64_t stage_timing_max_us[ANOMALY_TIMING_STAGE_COUNT] = {0};
    int64_t frame_timing_total_us = 0;
    int64_t frame_timing_max_us = 0;
    int    timing_frame_count = 0;
    int    movement_frame_count = 0;
    int64_t movement_background_count = 0;
    int64_t movement_coherent_near_count = 0;
    int64_t movement_unstable_count = 0;
    int64_t movement_local_outlier_count = 0;
    double movement_parallax_load_sum = 0.0;
    double movement_local_outlier_load_sum = 0.0;
    double movement_confidence_sum = 0.0;
    double movement_suppression_scale_sum = 0.0;
    int64_t movement_aoi_query_count = 0;
    int64_t movement_aoi_valid_count = 0;
    int64_t movement_aoi_independent_count = 0;
    int64_t movement_aoi_parallax_count = 0;
    int64_t movement_aoi_unstable_count = 0;
    double movement_aoi_independent_score_sum = 0.0;
    double movement_aoi_confidence_sum = 0.0;
    int64_t provisional_candidate_count = 0;
    int64_t provisional_candidate_selected_count = 0;
    int64_t revisit_confirmation_count = 0;
    int64_t revisit_salience_boost_count = 0;
    int64_t revisit_independent_motion_boost_count = 0;
    int64_t revisit_global_motion_reject_count = 0;
    int64_t suppressed_offgate_winner_count = 0;
    double wall_start_s     = monotonic_seconds();

    while (fread(rgba, 1, frame_bytes, in_pipe) == frame_bytes) {
        frame_num++;
        double local_time_s = (double)(frame_num - 1) / fps;
        double time_s = clip_time_start + local_time_s;
        if (raw_rgba) memcpy(raw_rgba, rgba, frame_bytes);

        // ── Per-frame probe ──────────────────────────────────────────────
        if (probe_csv) {
            anomaly_probe_t probe;
            int ok = anomaly_probe_thermal_point(&state, &cfg, rgba, W * 4, W, H,
                                                 probe_cx, probe_cy, &probe);
            int passes_delta = ok && probe.valid && probe.inside_scan_zone
                               && ((probe.used_temporal_score ? probe.temporal_delta : probe.spatial_abs_delta)
                                   >= (float)effective_min_delta);
            int passes_threshold = ok && probe.valid && probe.inside_scan_zone
                                   && (probe.effective_score >= cfg.score_threshold);
            fprintf(probe_csv,
                    "%d,%.3f,%d,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d\n",
                    frame_num, time_s, probe.pixel_x, probe.pixel_y,
                    probe.inside_scan_zone ? 1 : 0,
                    probe.bg_ready ? 1 : 0,
                    probe.used_temporal_score ? 1 : 0,
                    probe.sample_luma,
                    probe.spatial_mean,
                    probe.spatial_std,
                    probe.spatial_abs_delta,
                    probe.spatial_score,
                    probe.temporal_delta,
                    probe.temporal_mean,
                    probe.temporal_norm,
                    probe.temporal_score,
                    probe.effective_score,
                    passes_delta, passes_threshold);
        }

        cfg.color_debug_target_enabled = false;
        cfg.color_debug_target_x_norm = 0.0f;
        cfg.color_debug_target_y_norm = 0.0f;
        const color_debug_target_row_t *color_target_row = find_color_target_row(
            color_target_rows,
            color_target_row_count,
            frame_num,
            time_s,
            &color_target_row_cursor);
        if (color_target_row != NULL) {
            cfg.color_debug_target_enabled = true;
            cfg.color_debug_target_x_norm = color_target_row->x_norm;
            cfg.color_debug_target_y_norm = color_target_row->y_norm;
        }

        anomaly_result_t result;
        memset(&result, 0, sizeof(result));
        int64_t source_ts_us = (int64_t)llround(time_s * 1000000.0);
        anomaly_detector_annotation_view_t output_annotations = {0};
        anomaly_process_frame(&state, &cfg, rgba, W * 4, W, H, source_ts_us, &result);
        output_annotations = anomaly_detector_result_annotations(&result);
        if (app_display_output) {
            output_annotations =
                anomaly_detector_result_apply_annotation_stability(
                        &result,
                        &display_cadence,
                        display_analyzed_frame_count,
                        display_cadence_frames);
        }
        display_analyzed_frame_count++;
        if (app_display_output) {
            memcpy(rgba, raw_rgba, frame_bytes);
            if (output_annotations.box_count > 0) {
                anomaly_debug_draw_boxes_rgba(
                        rgba,
                        W * 4,
                        W,
                        H,
                        output_annotations.boxes,
                        output_annotations.box_count);
            }
        }
        switch (result.rescan_mode) {
            case ANOMALY_RESCAN_MODE_FULL:
                rescan_full_frames++;
                break;
            case ANOMALY_RESCAN_MODE_PARTIAL:
                rescan_partial_frames++;
                break;
            case ANOMALY_RESCAN_MODE_TARGET_ONLY:
                rescan_target_only_frames++;
                break;
            case ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP:
                rescan_stride_skip_frames++;
                break;
            case ANOMALY_RESCAN_MODE_UNSET:
            default:
                break;
        }
        for (size_t ri = 0; ri < sizeof(kScanReasonCounters) / sizeof(kScanReasonCounters[0]); ri++) {
            if ((result.scan_plan.reason_flags & kScanReasonCounters[ri].flag) != 0u) {
                scan_reason_counts[ri]++;
            }
        }
        for (size_t ri = 0; ri < sizeof(kRegistrationReasonCounters) / sizeof(kRegistrationReasonCounters[0]); ri++) {
            if (result.gmv_debug.invalid_reason == kRegistrationReasonCounters[ri].code) {
                registration_reason_counts[ri]++;
                break;
            }
        }
        if (result.adaptive_effective_stride > 0) {
            adaptive_stride_frame_count++;
            adaptive_stride_sum += result.adaptive_effective_stride;
            if (adaptive_stride_min == 0 || result.adaptive_effective_stride < adaptive_stride_min) {
                adaptive_stride_min = result.adaptive_effective_stride;
            }
            if (result.adaptive_effective_stride > adaptive_stride_max) {
                adaptive_stride_max = result.adaptive_effective_stride;
            }
            adaptive_motion_load_sum += result.adaptive_motion_load;
        }
        for (size_t ri = 0; ri < sizeof(kAdaptiveStrideReasonCounters) / sizeof(kAdaptiveStrideReasonCounters[0]); ri++) {
            if ((result.adaptive_reason_flags & kAdaptiveStrideReasonCounters[ri].flag) != 0u) {
                adaptive_stride_reason_counts[ri]++;
            }
        }
        provisional_candidate_count += result.scan_plan.provisional_candidate_count;
        provisional_candidate_selected_count += result.scan_plan.provisional_candidate_selected_count;
        revisit_confirmation_count += result.scan_plan.revisit_confirmation_count;
        revisit_salience_boost_count += result.scan_plan.revisit_salience_boost_count;
        revisit_independent_motion_boost_count += result.scan_plan.revisit_independent_motion_boost_count;
        revisit_global_motion_reject_count += result.scan_plan.revisit_global_motion_reject_count;
        suppressed_offgate_winner_count += result.scan_plan.suppressed_offgate_winner_count;
        if (result.timing.compiled) {
            timing_frame_count++;
            frame_timing_total_us += result.timing.total_us;
            if (result.timing.total_us > frame_timing_max_us) {
                frame_timing_max_us = result.timing.total_us;
            }
            for (int stage = 0; stage < ANOMALY_TIMING_STAGE_COUNT; stage++) {
                stage_timing_total_us[stage] += result.timing.stage_us[stage];
                if (result.timing.stage_us[stage] > stage_timing_max_us[stage]) {
                    stage_timing_max_us[stage] = result.timing.stage_us[stage];
                }
            }
        }
        if (result.movement_debug.valid) {
            movement_frame_count++;
            movement_background_count += result.movement_debug.background_count;
            movement_coherent_near_count += result.movement_debug.coherent_near_count;
            movement_unstable_count += result.movement_debug.unstable_count;
            movement_local_outlier_count += result.movement_debug.local_outlier_count;
            movement_parallax_load_sum += result.movement_debug.parallax_load;
            movement_local_outlier_load_sum += result.movement_debug.local_outlier_load;
            movement_confidence_sum += result.movement_debug.confidence;
            movement_suppression_scale_sum += result.movement_debug.parallax_suppression_scale;
            movement_aoi_query_count += result.movement_debug.aoi_query_count;
            movement_aoi_valid_count += result.movement_debug.aoi_valid_count;
            movement_aoi_independent_count += result.movement_debug.aoi_independent_count;
            movement_aoi_parallax_count += result.movement_debug.aoi_parallax_count;
            movement_aoi_unstable_count += result.movement_debug.aoi_unstable_count;
            movement_aoi_independent_score_sum +=
                result.movement_debug.aoi_independent_score_mean *
                (double)result.movement_debug.aoi_valid_count;
            movement_aoi_confidence_sum +=
                result.movement_debug.aoi_confidence_mean *
                (double)result.movement_debug.aoi_valid_count;
        }
        int debug_this_frame = (debug_frame > 0 && frame_num == debug_frame);
        if (debug_window_active) {
            int at_or_after_start = (debug_time_start < 0.0) || (time_s >= debug_time_start);
            int at_or_before_end = (debug_time_end < 0.0) || (time_s <= debug_time_end);
            if (at_or_after_start && at_or_before_end) debug_this_frame = 1;
        }
        if (debug_this_frame) {
            dump_gmv_debug(stderr, frame_num, time_s, &result);
            dump_thermal_debug(stderr, frame_num, time_s, &result);
            dump_color_debug(stderr, frame_num, time_s, &result);
            dump_saliency_debug(stderr, frame_num, time_s, raw_rgba, W, H, &cfg, &result);
            dump_motion_debug(stderr, frame_num, time_s, &result);
        }
        if (thermal_debug_jsonl != NULL) {
            write_thermal_debug_jsonl(thermal_debug_jsonl, frame_num, time_s, &result);
        }
        if (color_debug_jsonl != NULL) {
            write_color_debug_jsonl(color_debug_jsonl, frame_num, time_s, &result);
        }

        if (output_annotations.box_count > 0) {
            detection_frames++;
            for (int i = 0; i < output_annotations.box_count; i++) {
                const anomaly_box_t *b = &output_annotations.boxes[i];
                float cx = (b->left_norm  + b->right_norm)  * 0.5f;
                float cy = (b->top_norm   + b->bottom_norm) * 0.5f;
                float bw = b->right_norm  - b->left_norm;
                float bh = b->bottom_norm - b->top_norm;
                fprintf(csv, "%d,%.3f,%s,%.4f,%.4f,%.4f,%.4f,%.2f,\n",
                        frame_num, time_s, algo_label(b->algorithm),
                        (double)cx, (double)cy, (double)bw, (double)bh,
                        (double)b->weight);
                total_boxes++;
            }
        }

        // Progress line: overwrite in place.
        if (frame_num % 30 == 0 || frame_num == 1) {
            int elapsed = (int)(monotonic_seconds() - wall_start_s);
            if (total_frames > 0) {
                int eta = (frame_num > 0 && elapsed > 0)
                          ? (int)((double)elapsed / frame_num * (total_frames - frame_num))
                          : 0;
                fprintf(stderr, "\r  frame %5d / %d  (%.1fs)  "
                                "detections: %d frames  ETA: %ds     ",
                        frame_num, total_frames, time_s, detection_frames, eta);
            } else {
                fprintf(stderr, "\r  frame %5d  (%.1fs)  detections: %d frames   ",
                        frame_num, time_s, detection_frames);
            }
            fflush(stderr);
        }

        if (out_pipe) {
            if (debug_overlay && (cfg.algorithm_mask & ANOMALY_ALGO_PERSIST)) {
                draw_saliency_debug_overlay(rgba, W, H, raw_rgba, &cfg, &result);
            }
            if (debug_overlay && (cfg.algorithm_mask & ANOMALY_ALGO_MOTION)) {
                draw_motion_debug_overlay(rgba, W, H, &result);
            }
            draw_frame_number(rgba, W, H, frame_num);
            fwrite(rgba, 1, frame_bytes, out_pipe);
        }
    }
    fprintf(stderr, "\n\n");

    // ── Tear down ────────────────────────────────────────────────────────
    pclose(in_pipe);
    if (out_pipe) pclose(out_pipe);
    fclose(csv);
    if (probe_csv) fclose(probe_csv);
    if (thermal_debug_jsonl) fclose(thermal_debug_jsonl);
    if (color_debug_jsonl) fclose(color_debug_jsonl);
    anomaly_state_cleanup(&state);
    free(rgba);
    free(raw_rgba);
    free(color_target_rows);

    // ── Summary ──────────────────────────────────────────────────────────
    double analysis_wall_s = monotonic_seconds() - wall_start_s;
    if (analysis_wall_s < 0.0) analysis_wall_s = 0.0;
    double media_span_s = (fps > 0.0 ? (double)frame_num / fps : 0.0);
    if (clip_duration_s > 0.0) {
        media_span_s = clip_duration_s;
    }
    double realtime_factor = (analysis_wall_s > 0.0 && media_span_s > 0.0)
                             ? (media_span_s / analysis_wall_s)
                             : 0.0;
    fprintf(stderr, "Done in %.1fs.\n", analysis_wall_s);
    if (media_span_s > 0.0 && analysis_wall_s > 0.0) {
        fprintf(stderr, "  Input duration   : %.1f s\n", media_span_s);
        fprintf(stderr, "  Analysis wall    : %.1f s\n", analysis_wall_s);
        fprintf(stderr, "  Realtime factor  : %.2fx realtime (%s)\n",
                realtime_factor, realtime_descriptor(realtime_factor));
    }
    fprintf(stderr, "  Frames processed : %d\n", frame_num);
    fprintf(stderr, "  Frames with boxes: %d (%.1f%%)\n",
            detection_frames,
            frame_num > 0 ? 100.0 * detection_frames / frame_num : 0.0);
    fprintf(stderr, "  Total box events : %d\n", total_boxes);
    fprintf(stderr, "  Rescan modes     : full=%d partial=%d target-only=%d stride-skip=%d\n",
            rescan_full_frames,
            rescan_partial_frames,
            rescan_target_only_frames,
            rescan_stride_skip_frames);
    fprintf(stderr, "  Scan reasons     :");
    for (size_t ri = 0; ri < sizeof(kScanReasonCounters) / sizeof(kScanReasonCounters[0]); ri++) {
        if (scan_reason_counts[ri] <= 0) continue;
        fprintf(stderr, " %s=%d",
                kScanReasonCounters[ri].name,
                scan_reason_counts[ri]);
    }
    fprintf(stderr, "\n");
    fprintf(stderr, "  Reg reasons      :");
    for (size_t ri = 0; ri < sizeof(kRegistrationReasonCounters) / sizeof(kRegistrationReasonCounters[0]); ri++) {
        if (registration_reason_counts[ri] <= 0) continue;
        fprintf(stderr, " %s=%d",
                kRegistrationReasonCounters[ri].name,
                registration_reason_counts[ri]);
    }
    fprintf(stderr, "\n");
    if (movement_frame_count > 0) {
        fprintf(stderr,
                "  Movement sidecar : frames=%d bg=%lld coherent=%lld unstable=%lld outlier=%lld "
                "parallax=%.3f outlier-load=%.3f confidence=%.3f suppress=%.3f\n",
                movement_frame_count,
                (long long)movement_background_count,
                (long long)movement_coherent_near_count,
                (long long)movement_unstable_count,
                (long long)movement_local_outlier_count,
                movement_parallax_load_sum / (double)movement_frame_count,
                movement_local_outlier_load_sum / (double)movement_frame_count,
                movement_confidence_sum / (double)movement_frame_count,
                movement_suppression_scale_sum / (double)movement_frame_count);
        if (movement_aoi_query_count > 0) {
            fprintf(stderr,
                    "  AOI movement     : queries=%lld valid=%lld independent=%lld parallax=%lld unstable=%lld "
                    "ind-score=%.3f confidence=%.3f\n",
                    (long long)movement_aoi_query_count,
                    (long long)movement_aoi_valid_count,
                    (long long)movement_aoi_independent_count,
                    (long long)movement_aoi_parallax_count,
                    (long long)movement_aoi_unstable_count,
                    movement_aoi_valid_count > 0
                        ? movement_aoi_independent_score_sum / (double)movement_aoi_valid_count
                        : 0.0,
                    movement_aoi_valid_count > 0
                        ? movement_aoi_confidence_sum / (double)movement_aoi_valid_count
                        : 0.0);
        }
    }
    if (timing_frame_count > 0) {
        fprintf(stderr, "  Stage timing     : avg-total=%.2f ms max-total=%.2f ms\n",
                (double)frame_timing_total_us / (double)timing_frame_count / 1000.0,
                (double)frame_timing_max_us / 1000.0);
        for (int stage = 0; stage < ANOMALY_TIMING_STAGE_COUNT; stage++) {
            fprintf(stderr, "    %-18s avg=%.2f ms max=%.2f ms\n",
                    timing_stage_name((anomaly_timing_stage_t)stage),
                    (double)stage_timing_total_us[stage] / (double)timing_frame_count / 1000.0,
                    (double)stage_timing_max_us[stage] / 1000.0);
        }
    }
    fprintf(stderr, "  CSV written      : %s\n", output_csv);
    if (write_video)
        fprintf(stderr, "  Video written    : %s\n", output_video);
    if (probe_active)
        fprintf(stderr, "  Probe CSV        : %s\n", probe_csv_path);
    if (thermal_debug_jsonl_path[0])
        fprintf(stderr, "  Thermal JSONL    : %s\n", thermal_debug_jsonl_path);
    if (color_debug_jsonl_path[0])
        fprintf(stderr, "  Color JSONL      : %s\n", color_debug_jsonl_path);
    fprintf(stderr, "\n");
    if (output_summary_json[0]) {
        FILE *summary = fopen(output_summary_json, "w");
        if (!summary) {
            fprintf(stderr, "Warning: could not write summary JSON: %s\n", output_summary_json);
        } else {
            fprintf(summary, "{\n");
            fprintf(summary, "  \"input\": ");
            json_write_string(summary, input);
            fprintf(summary, ",\n  \"csv\": ");
            json_write_string(summary, output_csv);
            fprintf(summary, ",\n  \"video\": ");
            if (write_video) {
                json_write_string(summary, output_video);
            } else {
                fprintf(summary, "null");
            }
            fprintf(summary, ",\n  \"output_stream\": ");
            json_write_string(summary, app_display_output ? "app-display" : "raw-detector");
            fprintf(summary, ",\n  \"display_cadence_frames\": %d", display_cadence_frames);
            fprintf(summary, ",\n  \"app_parity\": {\n");
            fprintf(summary, "    \"enabled\": %s,\n", app_parity_mode ? "true" : "false");
            fprintf(summary, "    \"qualification_ready\": %s,\n",
                    app_qualification_ready ? "true" : "false");
            fprintf(summary, "    \"uses_app_config_mapping\": %s,\n",
                    app_parity_mode ? "true" : "false");
            fprintf(summary, "    \"uses_app_visible_annotations\": %s,\n",
                    app_display_output ? "true" : "false");
            fprintf(summary, "    \"processes_every_decoded_frame\": true,\n");
            fprintf(summary, "    \"outer_ad_frame_skip\": false,\n");
            fprintf(summary, "    \"render_stride_simulated\": 1,\n");
            fprintf(summary, "    \"qualification_note\": ");
            json_write_string(summary,
                              app_qualification_ready
                                  ? "Harness is configured for app-visible AD qualification."
                                  : "Use --app-defaults with --app-display-output for app-visible AD qualification.");
            fprintf(summary, "\n  }");
            fprintf(summary, ",\n  \"clip_start_s\": %.6f,\n", clip_time_start);
            if (clip_time_end >= 0.0) {
                fprintf(summary, "  \"clip_end_s\": %.6f,\n", requested_media_end);
            } else {
                fprintf(summary, "  \"clip_end_s\": null,\n");
            }
            fprintf(summary, "  \"fps\": %.6f,\n", fps);
            fprintf(summary, "  \"frame_count\": %d,\n", frame_num);
            fprintf(summary, "  \"detection_frames\": %d,\n", detection_frames);
            fprintf(summary, "  \"total_boxes\": %d,\n", total_boxes);
            fprintf(summary, "  \"analysis_wall_s\": %.6f,\n", analysis_wall_s);
            fprintf(summary, "  \"media_span_s\": %.6f,\n", media_span_s);
            fprintf(summary, "  \"realtime_factor\": %.6f,\n", realtime_factor);
            fprintf(summary, "  \"realtime_label\": ");
            json_write_string(summary, realtime_descriptor(realtime_factor));
            fprintf(summary, ",\n  \"rescan_modes\": {\n");
            fprintf(summary, "    \"full\": %d,\n", rescan_full_frames);
            fprintf(summary, "    \"partial\": %d,\n", rescan_partial_frames);
            fprintf(summary, "    \"target_only\": %d,\n", rescan_target_only_frames);
            fprintf(summary, "    \"appearance_stride_skip\": %d\n", rescan_stride_skip_frames);
            fprintf(summary, "  },\n  \"adaptive_stride\": {\n");
            fprintf(summary, "    \"frame_count\": %d,\n", adaptive_stride_frame_count);
            fprintf(summary, "    \"min\": %d,\n", adaptive_stride_min);
            fprintf(summary, "    \"max\": %d,\n", adaptive_stride_max);
            fprintf(summary, "    \"avg\": %.6f,\n",
                    adaptive_stride_frame_count > 0
                        ? (double)adaptive_stride_sum / (double)adaptive_stride_frame_count
                        : 0.0);
            fprintf(summary, "    \"avg_motion_load\": %.6f,\n",
                    adaptive_stride_frame_count > 0
                        ? adaptive_motion_load_sum / (double)adaptive_stride_frame_count
                        : 0.0);
            fprintf(summary, "    \"reason_counts\": {\n");
            for (size_t ri = 0; ri < sizeof(kAdaptiveStrideReasonCounters) / sizeof(kAdaptiveStrideReasonCounters[0]); ri++) {
                fprintf(summary, "      \"%s\": %d%s\n",
                        kAdaptiveStrideReasonCounters[ri].name,
                        adaptive_stride_reason_counts[ri],
                        (ri + 1) < sizeof(kAdaptiveStrideReasonCounters) / sizeof(kAdaptiveStrideReasonCounters[0]) ? "," : "");
            }
            fprintf(summary, "    }\n");
            fprintf(summary, "  },\n  \"scan_reason_counts\": {\n");
            for (size_t ri = 0; ri < sizeof(kScanReasonCounters) / sizeof(kScanReasonCounters[0]); ri++) {
                fprintf(summary, "    \"%s\": %d%s\n",
                        kScanReasonCounters[ri].name,
                        scan_reason_counts[ri],
                        (ri + 1) < (sizeof(kScanReasonCounters) / sizeof(kScanReasonCounters[0])) ? "," : "");
            }
            fprintf(summary, "  },\n  \"registration_reason_counts\": {\n");
            for (size_t ri = 0; ri < sizeof(kRegistrationReasonCounters) / sizeof(kRegistrationReasonCounters[0]); ri++) {
                fprintf(summary, "    \"%s\": %d%s\n",
                        kRegistrationReasonCounters[ri].name,
                        registration_reason_counts[ri],
                        (ri + 1) < (sizeof(kRegistrationReasonCounters) / sizeof(kRegistrationReasonCounters[0])) ? "," : "");
            }
            fprintf(summary, "  },\n  \"selective_revisit\": {\n");
            fprintf(summary, "    \"provisional_candidate_count\": %lld,\n", (long long)provisional_candidate_count);
            fprintf(summary, "    \"provisional_candidate_selected_count\": %lld,\n", (long long)provisional_candidate_selected_count);
            fprintf(summary, "    \"confirmation_count\": %lld,\n", (long long)revisit_confirmation_count);
            fprintf(summary, "    \"salience_boost_count\": %lld,\n", (long long)revisit_salience_boost_count);
            fprintf(summary, "    \"independent_motion_boost_count\": %lld,\n", (long long)revisit_independent_motion_boost_count);
            fprintf(summary, "    \"global_motion_reject_count\": %lld,\n", (long long)revisit_global_motion_reject_count);
            fprintf(summary, "    \"suppressed_offgate_winner_count\": %lld\n", (long long)suppressed_offgate_winner_count);
            fprintf(summary, "  },\n  \"movement_estimator\": {\n");
            fprintf(summary, "    \"mode\": ");
            json_write_string(summary, movement_estimator_name(cfg.movement_estimator_mode));
            fprintf(summary, ",\n    \"frame_count\": %d,\n", movement_frame_count);
            fprintf(summary, "    \"background_count\": %lld,\n", (long long)movement_background_count);
            fprintf(summary, "    \"coherent_near_count\": %lld,\n", (long long)movement_coherent_near_count);
            fprintf(summary, "    \"unstable_count\": %lld,\n", (long long)movement_unstable_count);
            fprintf(summary, "    \"local_outlier_count\": %lld,\n", (long long)movement_local_outlier_count);
            fprintf(summary, "    \"avg_parallax_load\": %.6f,\n",
                    movement_frame_count > 0 ? movement_parallax_load_sum / (double)movement_frame_count : 0.0);
            fprintf(summary, "    \"avg_local_outlier_load\": %.6f,\n",
                    movement_frame_count > 0 ? movement_local_outlier_load_sum / (double)movement_frame_count : 0.0);
            fprintf(summary, "    \"avg_confidence\": %.6f,\n",
                    movement_frame_count > 0 ? movement_confidence_sum / (double)movement_frame_count : 0.0);
            fprintf(summary, "    \"avg_parallax_suppression_scale\": %.6f,\n",
                    movement_frame_count > 0 ? movement_suppression_scale_sum / (double)movement_frame_count : 1.0);
            fprintf(summary, "    \"aoi_query_count\": %lld,\n", (long long)movement_aoi_query_count);
            fprintf(summary, "    \"aoi_valid_count\": %lld,\n", (long long)movement_aoi_valid_count);
            fprintf(summary, "    \"aoi_independent_count\": %lld,\n", (long long)movement_aoi_independent_count);
            fprintf(summary, "    \"aoi_parallax_count\": %lld,\n", (long long)movement_aoi_parallax_count);
            fprintf(summary, "    \"aoi_unstable_count\": %lld,\n", (long long)movement_aoi_unstable_count);
            fprintf(summary, "    \"aoi_avg_independent_score\": %.6f,\n",
                    movement_aoi_valid_count > 0
                        ? movement_aoi_independent_score_sum / (double)movement_aoi_valid_count
                        : 0.0);
            fprintf(summary, "    \"aoi_avg_confidence\": %.6f\n",
                    movement_aoi_valid_count > 0
                        ? movement_aoi_confidence_sum / (double)movement_aoi_valid_count
                        : 0.0);
            fprintf(summary, "  },\n  \"stage_timing\": {\n");
            fprintf(summary, "    \"compiled\": %s,\n", timing_frame_count > 0 ? "true" : "false");
            fprintf(summary, "    \"frame_count\": %d,\n", timing_frame_count);
            fprintf(summary, "    \"avg_total_ms\": %.6f,\n",
                    timing_frame_count > 0
                        ? ((double)frame_timing_total_us / (double)timing_frame_count / 1000.0)
                        : 0.0);
            fprintf(summary, "    \"max_total_ms\": %.6f,\n",
                    (double)frame_timing_max_us / 1000.0);
            fprintf(summary, "    \"stages\": {\n");
            for (int stage = 0; stage < ANOMALY_TIMING_STAGE_COUNT; stage++) {
                fprintf(summary,
                        "      \"%s\": { \"avg_ms\": %.6f, \"max_ms\": %.6f }%s\n",
                        timing_stage_name((anomaly_timing_stage_t)stage),
                        timing_frame_count > 0
                            ? ((double)stage_timing_total_us[stage] / (double)timing_frame_count / 1000.0)
                            : 0.0,
                        (double)stage_timing_max_us[stage] / 1000.0,
                        (stage + 1) < ANOMALY_TIMING_STAGE_COUNT ? "," : "");
            }
            fprintf(summary, "    }\n");
            fprintf(summary, "  },\n  \"config\": {\n");
            fprintf(summary, "    \"threshold\": %.6f,\n", (double)cfg.score_threshold);
            fprintf(summary, "    \"min_hits\": %d,\n", cfg.min_hits);
            fprintf(summary, "    \"scan_zone\": %.6f,\n", (double)cfg.scan_zone);
            fprintf(summary, "    \"algorithm_mask\": %d,\n", cfg.algorithm_mask);
            fprintf(summary, "    \"polarity\": ");
            json_write_string(summary,
                              cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "bh" : "wh");
            fprintf(summary, ",\n    \"stride_mode\": ");
            json_write_string(summary, stride_mode_name(cfg.stride_mode));
            fprintf(summary, ",\n    \"stride\": %d,\n", cfg.frame_stride);
            fprintf(summary, "    \"adaptive_min_stride_frames\": %d,\n",
                    cfg.adaptive_min_stride_frames);
            fprintf(summary, "    \"adaptive_max_stride_frames\": %d,\n",
                    cfg.adaptive_max_stride_frames);
            fprintf(summary, "    \"adaptive_max_stride_seconds\": %.6f,\n",
                    (double)cfg.adaptive_max_stride_seconds);
            fprintf(summary, "    \"pixel_step\": %d,\n", cfg.pixel_step);
            fprintf(summary, "    \"registration\": ");
            json_write_string(summary,
                              cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv");
            fprintf(summary, ",\n    \"movement_estimator\": ");
            json_write_string(summary, movement_estimator_name(cfg.movement_estimator_mode));
            fprintf(summary, ",\n    \"color_frontend\": ");
            json_write_string(summary, color_frontend_name(cfg.color_frontend_mode));
            fprintf(summary, ",\n    \"thermal_min_delta\": %.6f,\n", effective_min_delta);
            fprintf(summary, "    \"small_target_fraction\": %.6f\n",
                    (double)cfg.small_target_screen_fraction);
            fprintf(summary, "  }\n}\n");
            fclose(summary);
        }
    }
    if (probe_active) {
        fprintf(stderr, "Probe interpretation:\n");
        fprintf(stderr, "  spatial_* rows describe the sampled-window score used during\n");
        fprintf(stderr, "    thermal background warmup.\n");
        fprintf(stderr, "  temporal_* rows describe the background-delta score used once\n");
        fprintf(stderr, "    bg_ready=1.\n");
        fprintf(stderr, "  effective_score is the exact value compared against threshold.\n");
        fprintf(stderr, "  If abs_delta/delta is small (< %.0f) but effective_score is large,\n",
                effective_min_delta);
        fprintf(stderr, "    investigate a noisy uniform region or a high frame delta mean.\n");
        fprintf(stderr, "  If effective_score > %.1f with min_delta > %.0f, the detector should\n",
                (double)cfg.score_threshold, effective_min_delta);
        fprintf(stderr, "    fire, assuming the probe location is inside the scan zone (%.0f%%).\n\n",
                100.0 * cfg.scan_zone);
    } else {
        fprintf(stderr, "Next step: open the CSV in Numbers/Excel and watch the\n");
        fprintf(stderr, "annotated video alongside it.  For each row, fill in the\n");
        fprintf(stderr, "label column:  G = good detection,  B = false positive,\n");
        fprintf(stderr, "               ? = unsure.\n\n");
    }

    return 0;
}

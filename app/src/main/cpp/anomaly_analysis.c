// anomaly_analysis.c — Standalone anomaly detection for SAR drone video.
// See anomaly_analysis.h for full documentation.
#include "anomaly_analysis.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

// ── Internal helpers ──────────────────────────��────────────────────────────

#define ANOMALY_LOCAL_MOTION_REGION_STRIDE_CELLS 4
#define ANOMALY_LOCAL_MOTION_REGION_RADIUS_CELLS 6
#define ANOMALY_LOCAL_MOTION_MIN_SAMPLES 10
#define ANOMALY_LOCAL_MOTION_INLIER_RADIUS_CELLS 1.35f
#define ANOMALY_LOCAL_MOTION_MIN_SCALE 0.12f
#define ANOMALY_REG_RESIDUAL_CENTER_HALF 1
#define ANOMALY_REG_RESIDUAL_RING_HALF 3
#define ANOMALY_REG_RESIDUAL_SOFT_THRESH 0.60f
#define ANOMALY_REG_RESIDUAL_HARD_THRESH 0.25f
#define ANOMALY_REG_RESIDUAL_MIN_SCALE 0.12f
#define ANOMALY_SALIENCY_RING_MARGIN 0.55f
#define ANOMALY_SALIENCY_RING_SOFT_SCALE 1.35f
#define ANOMALY_SALIENCY_RING_HARD_SCALE 0.18f
#define ANOMALY_SALIENCY_PLATEAU_SUPPORT 6
#define ANOMALY_SALIENCY_SELECTION_SUPPORT_RADIUS 2
#define ANOMALY_MAX_MOTION_CANDIDATES 4
#define ANOMALY_MAX_THERMAL_CANDIDATES 8
#define ANOMALY_MAX_OVERLAY_BOXES 8
#define ANOMALY_MOTION_CANDIDATE_NMS_RADIUS 2
#define ANOMALY_MOTION_LOCAL_RADIUS_CELLS 2
#define ANOMALY_MOTION_GLOBAL_STRIDE_CELLS 4
#define ANOMALY_MOTION_LOCAL_MIN_SAMPLES 4
#define ANOMALY_THERMAL_MOTION_BOOST 0.45f
#define ANOMALY_COLOR_MOTION_BOOST 0.35f
#define ANOMALY_THERMAL_FOOTPRINT_RADIUS 3
#define ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP 4
#define ANOMALY_THERMAL_GROWTH_MAX_RADIUS 12
#define ANOMALY_THERMAL_GROWTH_MAX_DIAMETER_PX 20
#define ANOMALY_THERMAL_SMALL_TARGET_DIAMETER_PX 10
#define ANOMALY_THERMAL_SMALL_TARGET_SCREEN_FRACTION ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT
#define ANOMALY_THERMAL_REPRESENTATIVE_RADIUS 5
#define ANOMALY_THERMAL_REPRESENTATIVE_MIN_AREA 6
#define ANOMALY_THERMAL_BROAD_CONTEXT_RADIUS 8
#define ANOMALY_THERMAL_TARGET_HISTORY_RADIUS 2
#define ANOMALY_THERMAL_TARGET_HISTORY_DECAY 0.90f
#define ANOMALY_THERMAL_TARGET_HISTORY_GAIN 0.60f
#define ANOMALY_THERMAL_MAX_BLOB_AREA_SAMPLES 24
#define ANOMALY_THERMAL_BLOB_OVERLAY_SCORE_MARGIN 0.28f
#define ANOMALY_PUBLISH_BG_SETTLE_FRAMES 24
#define ANOMALY_PUBLISH_STABLE_RELEASE_FRAMES 4
#define ANOMALY_PUBLISH_STABLE_GMV_RESIDUAL 0.0035f
#define ANOMALY_PUBLISH_STABLE_MOTION_LOAD 0.025f
#define ANOMALY_PUBLISH_STABLE_ZOOM_SCALE 0.92f
#define ANOMALY_PUBLISH_DISCONTINUITY_HOLDOFF_FRAMES 6
#define ANOMALY_PUBLISH_UNSTABLE_HOLDOFF_FRAMES 3
#define ANOMALY_PUBLISH_GMV_RESIDUAL_GATE 0.010f
#define ANOMALY_PUBLISH_ZOOM_SCALE_GATE 0.60f
#define ANOMALY_PUBLISH_GLOBAL_MOTION_GATE 0.085f
#define ANOMALY_SALIENCY_SECONDARY_MIN_SEPARATION 0.070f
#define ANOMALY_SALIENCY_SECONDARY_SCORE_MARGIN 1.20f
#define ANOMALY_SALIENCY_SECONDARY_TRACKED_SCORE_MARGIN 2.10f
#define ANOMALY_SALIENCY_SECONDARY_TRACK_REACQUIRE_GATE 0.090f
#define ANOMALY_SALIENCY_SECONDARY_HOLD_BONUS 6
#define ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS 2
#define ANOMALY_SALIENCY_SECONDARY_LOCAL_THRESHOLD_SLACK 0.40f
#define ANOMALY_SALIENCY_BOUNDARY_RADIUS_CELLS 6
#define ANOMALY_ROI_CELL_TARGET_SIZE_PX 16
#define ANOMALY_ROI_REALTIME_CARRY_EXPIRY 3
#define ANOMALY_SCAN_FLAG_NEW_EXPOSED 0x01u
#define ANOMALY_SCAN_FLAG_STALE 0x02u
#define ANOMALY_SCAN_FLAG_TARGET_REVISIT 0x04u
#define ANOMALY_SCAN_FLAG_LOW_CONFIDENCE 0x08u
#define ANOMALY_TARGET_MATCH_GATE 0.12f
#define ANOMALY_TARGET_MAX_CARRIED_MISSES 2
#define ANOMALY_TARGET_CONFIDENCE_HIT_GAIN 0.22f
#define ANOMALY_TARGET_CONFIDENCE_MISS_DECAY 0.22f
#define ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN 0.20f
#define ANOMALY_MAX_COLOR_CANDIDATES 8
#define ANOMALY_COLOR_U_MIN (-112.0f)
#define ANOMALY_COLOR_U_MAX (112.0f)
#define ANOMALY_COLOR_V_MIN (-112.0f)
#define ANOMALY_COLOR_V_MAX (112.0f)
#define ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS 1
#define ANOMALY_COLOR_LOCAL_SUPPORT_MIN 3

static inline float clamp01f(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static inline int clamp_i32(int value, int min_value, int max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline float clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline int quantize_uv_bin(float value, float min_v, float max_v, int bins) {
    float clamped = clampf(value, min_v, max_v);
    float norm = (clamped - min_v) / fmaxf(max_v - min_v, 1e-6f);
    int bin = (int)(norm * (float)bins);
    if (bin < 0) bin = 0;
    if (bin >= bins) bin = bins - 1;
    return bin;
}

static inline int color_hist_key(int u_bin, int v_bin) {
    return u_bin * ANOMALY_COLOR_V_BINS + v_bin;
}

static inline void fill_color_uv_bins(
        anomaly_roi_state_t *roi_state,
        size_t               idx) {
    if (roi_state == NULL || roi_state->color_u_bin == NULL || roi_state->color_v_bin == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL) {
        return;
    }
    roi_state->color_u_bin[idx] = (uint8_t)quantize_uv_bin(
        roi_state->color_u[idx],
        ANOMALY_COLOR_U_MIN,
        ANOMALY_COLOR_U_MAX,
        ANOMALY_COLOR_U_BINS);
    roi_state->color_v_bin[idx] = (uint8_t)quantize_uv_bin(
        roi_state->color_v[idx],
        ANOMALY_COLOR_V_MIN,
        ANOMALY_COLOR_V_MAX,
        ANOMALY_COLOR_V_BINS);
}

static bool ensure_color_hist_capacity(uint8_t **buffer, size_t *capacity_bins) {
    if (buffer == NULL || capacity_bins == NULL) return false;
    if (*buffer != NULL && *capacity_bins >= ANOMALY_COLOR_HIST_BINS) return true;
    size_t old_bins = *capacity_bins;
    uint8_t *grown = (uint8_t *)realloc(*buffer, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
    if (grown == NULL) return false;
    if (ANOMALY_COLOR_HIST_BINS > old_bins) {
        memset(grown + old_bins, 0, (ANOMALY_COLOR_HIST_BINS - old_bins) * sizeof(uint8_t));
    }
    *buffer = grown;
    *capacity_bins = ANOMALY_COLOR_HIST_BINS;
    return true;
}

static int build_color_frame_histogram(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        uint8_t                   *hist_out) {
    if (roi_state == NULL || hist_out == NULL || sg_w <= 0 || sg_h <= 0 ||
        roi_state->color_valid_mask == NULL || roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL) {
        return 0;
    }

    memset(hist_out, 0, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
    int valid_samples = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) continue;

            int u_bin = (int)roi_state->color_u_bin[idx];
            int v_bin = (int)roi_state->color_v_bin[idx];
            int key = color_hist_key(u_bin, v_bin);
            if (hist_out[key] < 255u) hist_out[key] += 1u;
            valid_samples++;
        }
    }
    return valid_samples;
}

static void update_color_recent_histogram(
        anomaly_state_t *state,
        const uint8_t   *current_hist,
        bool             reset_history) {
    if (state == NULL || current_hist == NULL || state->color_recent_hist == NULL) return;

    if (reset_history) {
        memcpy(state->color_recent_hist,
               current_hist,
               ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
        return;
    }

    for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
        uint16_t decayed = (uint16_t)(state->color_recent_hist[i] >> ANOMALY_COLOR_HISTORY_DECAY_SHIFT);
        uint16_t combined = decayed + (uint16_t)current_hist[i];
        state->color_recent_hist[i] = (uint8_t)(combined > 255u ? 255u : combined);
    }
}

static inline float score_color_hist_rarity(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        int          key) {
    int cur = current_hist != NULL ? (int)current_hist[key] : 0;
    int rec = recent_hist != NULL ? (int)recent_hist[key] : 0;
    return 1.0f / (float)(cur + rec + 1);
}

static inline float score_color_hist_family_rarity(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        int            center_u_bin,
        int            center_v_bin) {
    int family = 0;
    for (int dv = -1; dv <= 1; dv++) {
        int v_bin = center_v_bin + dv;
        if (v_bin < 0 || v_bin >= ANOMALY_COLOR_V_BINS) continue;
        for (int du = -1; du <= 1; du++) {
            int u_bin = center_u_bin + du;
            if (u_bin < 0 || u_bin >= ANOMALY_COLOR_U_BINS) continue;
            int key = color_hist_key(u_bin, v_bin);
            family += current_hist != NULL ? (int)current_hist[key] : 0;
            family += recent_hist != NULL ? (int)recent_hist[key] : 0;
        }
    }
    return 1.0f / (float)(family + 1);
}

static int local_uv_support_count(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        center_sx,
        int                        center_sy,
        int                        center_u_bin,
        int                        center_v_bin) {
    if (roi_state == NULL || sg_w <= 0 || sg_h <= 0 ||
        roi_state->color_valid_mask == NULL ||
        roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL) {
        return 0;
    }

    int support = 0;
    int sx0 = center_sx - ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS;
    int sx1 = center_sx + ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS;
    int sy0 = center_sy - ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS;
    int sy1 = center_sy + ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS;
    if (sx0 < 0) sx0 = 0;
    if (sy0 < 0) sy0 = 0;
    if (sx1 >= sg_w) sx1 = sg_w - 1;
    if (sy1 >= sg_h) sy1 = sg_h - 1;

    for (int sy = sy0; sy <= sy1; sy++) {
        for (int sx = sx0; sx <= sx1; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) continue;

            int u_bin = (int)roi_state->color_u_bin[idx];
            int v_bin = (int)roi_state->color_v_bin[idx];
            if (abs(u_bin - center_u_bin) <= 1 &&
                abs(v_bin - center_v_bin) <= 1) {
                support++;
            }
        }
    }
    return support;
}

#if ANOMALY_DEBUG_TIMING
static int64_t anomaly_timing_now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t)ts.tv_sec * 1000000ll) + ((int64_t)ts.tv_nsec / 1000ll);
}

static inline void anomaly_timing_add_elapsed(
        anomaly_debug_timing_t *timing,
        anomaly_timing_stage_t  stage,
        int64_t                 started_us) {
    if (timing == NULL || started_us <= 0 || stage < 0 || stage >= ANOMALY_TIMING_STAGE_COUNT) return;
    int64_t elapsed_us = anomaly_timing_now_us() - started_us;
    if (elapsed_us > 0) timing->stage_us[stage] += elapsed_us;
}
#else
static int64_t anomaly_timing_now_us(void) {
    return 0;
}

static inline void anomaly_timing_add_elapsed(
        anomaly_debug_timing_t *timing,
        anomaly_timing_stage_t  stage,
        int64_t                 started_us) {
    (void)timing;
    (void)stage;
    (void)started_us;
}
#endif

static inline void finalize_result_timing(
        anomaly_result_t       *result_out,
        anomaly_debug_timing_t *timing,
        int64_t                 frame_started_us) {
    if (timing == NULL) return;
#if ANOMALY_DEBUG_TIMING
    if (frame_started_us > 0) {
        int64_t total_us = anomaly_timing_now_us() - frame_started_us;
        timing->total_us = total_us > 0 ? total_us : 0;
    }
#else
    (void)frame_started_us;
#endif
    if (result_out != NULL) {
        result_out->timing = *timing;
    }
}

static void registration_prefilter_luma_grid(
        const uint8_t *src,
        int            width,
        int            height,
        uint8_t       *tmp,
        uint8_t       *dst) {
    if (src == NULL || tmp == NULL || dst == NULL || width <= 0 || height <= 0) return;

    for (int y = 0; y < height; y++) {
        const int row = y * width;
        for (int x = 0; x < width; x++) {
            int x0 = x > 0 ? (x - 1) : x;
            int x2 = x + 1 < width ? (x + 1) : x;
            int sum = (int)src[row + x0] + (2 * (int)src[row + x]) + (int)src[row + x2];
            tmp[row + x] = (uint8_t)((sum + 2) >> 2);
        }
    }

    for (int y = 0; y < height; y++) {
        int y0 = y > 0 ? (y - 1) : y;
        int y2 = y + 1 < height ? (y + 1) : y;
        for (int x = 0; x < width; x++) {
            int sum = (int)tmp[y0 * width + x] + (2 * (int)tmp[y * width + x]) + (int)tmp[y2 * width + x];
            dst[y * width + x] = (uint8_t)((sum + 2) >> 2);
        }
    }
}

static void compute_centered_roi_bounds(
        int    width,
        int    height,
        float  zone_fraction,
        int   *roi_x0_out,
        int   *roi_y0_out,
        int   *roi_x1_out,
        int   *roi_y1_out) {
    int roi_x0 = 0;
    int roi_y0 = 0;
    int roi_x1 = width;
    int roi_y1 = height;
    if (width > 0 && height > 0) {
        float zone = clampf(zone_fraction, 0.5f, 1.0f);
        float margin = (1.0f - zone) * 0.5f;
        roi_x0 = (int)(margin * (float)width);
        roi_x1 = width - roi_x0;
        roi_y0 = (int)(margin * (float)height);
        roi_y1 = height - roi_y0;
        if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = width; }
        if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = height; }
    }
    if (roi_x0_out != NULL) *roi_x0_out = roi_x0;
    if (roi_y0_out != NULL) *roi_y0_out = roi_y0;
    if (roi_x1_out != NULL) *roi_x1_out = roi_x1;
    if (roi_y1_out != NULL) *roi_y1_out = roi_y1;
}

static void compute_registration_roi_bounds(
        int  width,
        int  height,
        int *roi_x0_out,
        int *roi_y0_out,
        int *roi_x1_out,
        int *roi_y1_out) {
    if (roi_x0_out != NULL) *roi_x0_out = 0;
    if (roi_y0_out != NULL) *roi_y0_out = 0;
    if (roi_x1_out != NULL) *roi_x1_out = width > 0 ? width : 0;
    if (roi_y1_out != NULL) *roi_y1_out = height > 0 ? height : 0;
}

static bool ensure_u8_capacity(uint8_t **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    uint8_t *grown = (uint8_t *)realloc(*buffer, count * sizeof(uint8_t));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

static bool ensure_float_capacity(float **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    float *grown = (float *)realloc(*buffer, count * sizeof(float));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

static bool ensure_double_capacity(double **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    double *grown = (double *)realloc(*buffer, count * sizeof(double));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

static bool ensure_int_capacity(int **buffer, size_t *capacity, size_t count) {
    if (buffer == NULL || capacity == NULL) return false;
    if (count == 0) return true;
    if (*buffer != NULL && *capacity >= count) return true;
    int *grown = (int *)realloc(*buffer, count * sizeof(int));
    if (grown == NULL) return false;
    *buffer = grown;
    *capacity = count;
    return true;
}

static bool ensure_prev_roi_snapshot_capacity(anomaly_state_t *state, size_t sample_count) {
    if (state == NULL) return false;
    if (sample_count == 0) return true;
    return
        ensure_float_capacity(&state->scratch_prev_roi_last_luma,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_thermal_score,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_temporal_score,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_color_luma,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_color_u,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_color_v,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_color_raw_score,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_float_capacity(&state->scratch_prev_roi_color_contrast_weight,
                              &state->scratch_prev_roi_capacity,
                              sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_color_u_bin,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_color_v_bin,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_valid_mask,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_coverage_age,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_color_valid_mask,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_color_phase_x,
                           &state->scratch_prev_roi_capacity,
                           sample_count) &&
        ensure_u8_capacity(&state->scratch_prev_roi_color_phase_y,
                           &state->scratch_prev_roi_capacity,
                           sample_count);
}

static inline float thermal_delta_from_maps(
        const float *delta_map,
        const float *bg_luma,
        const float *sg_luma,
        size_t       idx,
        bool         black_hot) {
    if (delta_map != NULL) return delta_map[idx];
    if (bg_luma == NULL || sg_luma == NULL) return 0.0f;
    float bg = bg_luma[idx];
    float lum = sg_luma[idx];
    return black_hot ? (bg - lum) : (lum - bg);
}

static inline float thermal_blob_value_at(
        const float *thermal_score_map,
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        size_t       idx,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta) {
    if (bg_valid) {
        float delta = thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            idx,
            black_hot);
        return delta >= thermal_min_delta ? delta : -1.0f;
    }
    return thermal_score_map != NULL ? thermal_score_map[idx] : -1.0f;
}

static bool ensure_roi_state_capacity(anomaly_roi_state_t *roi_state, size_t pixel_count) {
    if (roi_state == NULL) return false;
    if (pixel_count == 0) return true;
    if (roi_state->pixel_capacity >= pixel_count &&
        roi_state->last_luma != NULL &&
        roi_state->thermal_score != NULL &&
        roi_state->temporal_score != NULL &&
        roi_state->color_luma != NULL &&
        roi_state->color_u != NULL &&
        roi_state->color_v != NULL &&
        roi_state->color_raw_score != NULL &&
        roi_state->color_contrast_weight != NULL &&
        roi_state->color_u_bin != NULL &&
        roi_state->color_v_bin != NULL &&
        roi_state->valid_mask != NULL &&
        roi_state->fresh_mask != NULL &&
        roi_state->carried_mask != NULL &&
        roi_state->new_exposed_mask != NULL &&
        roi_state->color_valid_mask != NULL &&
        roi_state->color_phase_x != NULL &&
        roi_state->color_phase_y != NULL &&
        roi_state->reg_confidence != NULL &&
        roi_state->coverage_age != NULL) {
        return true;
    }
    if (!ensure_float_capacity(&roi_state->last_luma, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->thermal_score, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->temporal_score, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->color_luma, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->color_u, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->color_v, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->color_raw_score, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->color_contrast_weight, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->color_u_bin, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->color_v_bin, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->valid_mask, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->fresh_mask, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->carried_mask, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->new_exposed_mask, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->color_valid_mask, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->color_phase_x, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->color_phase_y, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_float_capacity(&roi_state->reg_confidence, &roi_state->pixel_capacity, pixel_count) ||
        !ensure_u8_capacity(&roi_state->coverage_age, &roi_state->pixel_capacity, pixel_count)) {
        return false;
    }
    return true;
}

static bool ensure_roi_cell_capacity(anomaly_roi_state_t *roi_state, size_t cell_count) {
    if (roi_state == NULL) return false;
    if (cell_count == 0) return true;
    if (roi_state->cell_capacity >= cell_count && roi_state->cell_summaries != NULL) {
        return true;
    }
    anomaly_roi_cell_summary_t *grown = (anomaly_roi_cell_summary_t *)realloc(
        roi_state->cell_summaries,
        cell_count * sizeof(anomaly_roi_cell_summary_t));
    if (grown == NULL) return false;
    roi_state->cell_summaries = grown;
    roi_state->cell_capacity = cell_count;
    return true;
}

static void clear_roi_state(anomaly_roi_state_t *roi_state) {
    if (roi_state == NULL) return;
    roi_state->valid = false;
    roi_state->roi_x0 = 0;
    roi_state->roi_y0 = 0;
    roi_state->roi_x1 = 0;
    roi_state->roi_y1 = 0;
    roi_state->width = 0;
    roi_state->height = 0;
    roi_state->sample_step = 0;
    roi_state->cell_size_px = 0;
    roi_state->cell_cols = 0;
    roi_state->cell_rows = 0;
    if (roi_state->pixel_capacity > 0) {
        memset(roi_state->color_valid_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_phase_x, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_phase_y, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_u_bin, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_v_bin, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->valid_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->fresh_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->carried_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->new_exposed_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->coverage_age, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        if (roi_state->color_luma != NULL) {
            memset(roi_state->color_luma, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_u != NULL) {
            memset(roi_state->color_u, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_v != NULL) {
            memset(roi_state->color_v, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_raw_score != NULL) {
            memset(roi_state->color_raw_score, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_contrast_weight != NULL) {
            memset(roi_state->color_contrast_weight, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->reg_confidence != NULL) {
            memset(roi_state->reg_confidence, 0, roi_state->pixel_capacity * sizeof(float));
        }
    }
    if (roi_state->cell_capacity > 0 && roi_state->cell_summaries != NULL) {
        memset(roi_state->cell_summaries, 0,
               roi_state->cell_capacity * sizeof(anomaly_roi_cell_summary_t));
    }
}

static void release_roi_state(anomaly_roi_state_t *roi_state) {
    if (roi_state == NULL) return;
    free(roi_state->last_luma);
    free(roi_state->thermal_score);
    free(roi_state->temporal_score);
    free(roi_state->color_luma);
    free(roi_state->color_u);
    free(roi_state->color_v);
    free(roi_state->color_raw_score);
    free(roi_state->color_contrast_weight);
    free(roi_state->color_u_bin);
    free(roi_state->color_v_bin);
    free(roi_state->valid_mask);
    free(roi_state->fresh_mask);
    free(roi_state->carried_mask);
    free(roi_state->new_exposed_mask);
    free(roi_state->color_valid_mask);
    free(roi_state->color_phase_x);
    free(roi_state->color_phase_y);
    free(roi_state->reg_confidence);
    free(roi_state->coverage_age);
    free(roi_state->cell_summaries);
    memset(roi_state, 0, sizeof(*roi_state));
}

static inline float registration_health_confidence(anomaly_registration_health_t health) {
    switch (health) {
        case ANOMALY_REG_HEALTH_HEALTHY:
            return 1.0f;
        case ANOMALY_REG_HEALTH_SOFT_DEGRADED:
            return 0.60f;
        case ANOMALY_REG_HEALTH_HARD_DEGRADED:
            return 0.25f;
        case ANOMALY_REG_HEALTH_INVALID:
            return 0.0f;
        case ANOMALY_REG_HEALTH_UNKNOWN:
        default:
            return 0.10f;
    }
}

static inline int roi_grid_cell_span(int sample_step) {
    int step = sample_step > 0 ? sample_step : 1;
    int span = (ANOMALY_ROI_CELL_TARGET_SIZE_PX + step - 1) / step;
    return span > 0 ? span : 1;
}

static inline int thermal_radius_cells_for_real_px(
        int target_radius_px,
        int sample_step,
        int min_cells,
        int max_cells) {
    int step = sample_step > 0 ? sample_step : 1;
    int radius = (target_radius_px + step - 1) / step;
    if (radius < min_cells) radius = min_cells;
    if (max_cells > 0 && radius > max_cells) radius = max_cells;
    return radius;
}

static inline int effective_thermal_window_radius_cells(int sample_step) {
    int target_radius_px =
        ANOMALY_THERMAL_WIN_RADIUS * ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(target_radius_px, sample_step, 1, 0);
}

static inline int effective_thermal_representative_radius_cells(int sample_step) {
    int target_radius_px = 3 * ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        3,
        ANOMALY_THERMAL_GROWTH_MAX_RADIUS);
}

static inline int effective_thermal_growth_radius_cells(int sample_step) {
    int target_radius_px = 3 * ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        2,
        ANOMALY_THERMAL_GROWTH_MAX_RADIUS);
}

static inline int effective_thermal_context_radius_cells(int sample_step) {
    int target_radius_px =
        ANOMALY_THERMAL_BROAD_CONTEXT_RADIUS * ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        6,
        0);
}

static inline int effective_thermal_parent_mass_radius_cells(int sample_step) {
    int target_radius_px =
        (ANOMALY_THERMAL_BROAD_CONTEXT_RADIUS + 2) * ANOMALY_THERMAL_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        6,
        0);
}

static inline float effective_thermal_small_target_span_px(
        const anomaly_config_t *cfg,
        int                     frame_w,
        int                     frame_h) {
    float fw = (float)(frame_w > 0 ? frame_w : 1);
    float fh = (float)(frame_h > 0 ? frame_h : 1);
    float diagonal_px = sqrtf(fw * fw + fh * fh);
    float fraction = ANOMALY_THERMAL_SMALL_TARGET_SCREEN_FRACTION;
    if (cfg != NULL &&
        isfinite(cfg->small_target_screen_fraction) &&
        cfg->small_target_screen_fraction > 0.0f) {
        fraction = cfg->small_target_screen_fraction;
    }
    float span_px = diagonal_px * fraction;
    if (span_px < 2.0f) span_px = 2.0f;
    return span_px;
}

static inline float thermal_small_target_apparent_scale(
        const anomaly_config_t *cfg,
        float span_px,
        int   frame_w,
        int   frame_h) {
    if (span_px <= 0.0f) return 1.0f;
    float limit_px = effective_thermal_small_target_span_px(cfg, frame_w, frame_h);
    if (span_px <= limit_px) return 1.0f;
    if (span_px <= limit_px * 1.35f) {
        float t = (span_px - limit_px) / fmaxf(limit_px * 0.35f, 0.001f);
        return 1.0f - 0.28f * clampf(t, 0.0f, 1.0f);
    }
    if (span_px <= limit_px * 2.10f) {
        float t = (span_px - limit_px * 1.35f) / fmaxf(limit_px * 0.75f, 0.001f);
        return 0.72f - 0.54f * clampf(t, 0.0f, 1.0f);
    }
    return 0.18f;
}

static inline float effective_color_target_span_px(
        const anomaly_config_t *cfg,
        int                     frame_w,
        int                     frame_h) {
    float area_fraction = cfg != NULL ? cfg->min_area_fraction : ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    if (!isfinite(area_fraction) || area_fraction <= 0.0f) {
        area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    }
    float frame_area = (float)(frame_w > 0 ? frame_w : 1) * (float)(frame_h > 0 ? frame_h : 1);
    float span_px = sqrtf(fmaxf(area_fraction * frame_area, 4.0f));
    return clampf(span_px, 3.0f, effective_thermal_small_target_span_px(cfg, frame_w, frame_h));
}

static void clear_target_track(anomaly_target_track_t *track) {
    if (track == NULL) return;
    memset(track, 0, sizeof(*track));
}

static void clear_all_target_tracks(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        clear_target_track(&state->target_tracks[i]);
    }
    state->next_target_track_id = 1;
}

static inline int target_revisit_track_count(const anomaly_state_t *state) {
    if (state == NULL) return 0;
    int count = 0;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        const anomaly_target_track_t *track = &state->target_tracks[i];
        if (!track->active) continue;
        if (track->forced_revisit ||
            track->miss_count > 0 ||
            track->confidence >= ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN) {
            count++;
        }
    }
    return count;
}

static inline int popcount_u8(uint8_t value) {
    int count = 0;
    while (value != 0) {
        count += (value & 1u);
        value >>= 1u;
    }
    return count;
}

static const char *scan_reason_flag_name(uint32_t flag) {
    switch (flag) {
        case ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH:
            return "no-appearance-refresh";
        case ANOMALY_SCAN_REASON_NO_SAMPLES:
            return "no-samples";
        case ANOMALY_SCAN_REASON_PREV_STATE_INVALID:
            return "prev-state-invalid";
        case ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY:
            return "scene-discontinuity";
        case ANOMALY_SCAN_REASON_REG_INVALID:
            return "reg-invalid";
        case ANOMALY_SCAN_REASON_REG_HARD_DEGRADED:
            return "reg-hard-degraded";
        case ANOMALY_SCAN_REASON_WARP_LOW:
            return "warp-low";
        case ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH:
            return "new-exposed-high";
        case ANOMALY_SCAN_REASON_STALE_HIGH:
            return "stale-high";
        case ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH:
            return "sample-step-mismatch";
        case ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE:
            return "target-only-eligible";
        case ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE:
            return "partial-eligible";
        case ANOMALY_SCAN_REASON_MASK_BUILD_FAILED:
            return "mask-build-failed";
        case ANOMALY_SCAN_REASON_MASK_EMPTY:
            return "mask-empty";
        case ANOMALY_SCAN_REASON_MASK_TOO_BROAD:
            return "mask-too-broad";
        default:
            return "unknown";
    }
}

static const char *registration_invalid_reason_name(
        anomaly_registration_invalid_reason_t reason) {
    switch (reason) {
        case ANOMALY_REG_INVALID_REASON_NONE:
            return "none";
        case ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE:
            return "debug-input-unavailable";
        case ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS:
            return "gmv-too-few-anchors";
        case ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID:
            return "gmv-fit-invalid";
        case ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH:
            return "gmv-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE:
            return "gmv-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE:
            return "gmv-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE:
            return "affine-roi-degenerate";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS:
            return "affine-too-few-corners";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES:
            return "affine-too-few-matches";
        case ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED:
            return "affine-fit-failed";
        case ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH:
            return "affine-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE:
            return "affine-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE:
            return "affine-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET:
            return "affine-negative-det";
        default:
            return "unknown";
    }
}

static void format_scan_reason_flags(
        uint32_t flags,
        char    *buffer,
        size_t   buffer_size) {
    if (buffer == NULL || buffer_size == 0) return;
    buffer[0] = '\0';
    if (flags == 0u) {
        snprintf(buffer, buffer_size, "none");
        return;
    }
    const uint32_t known_flags[] = {
        ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH,
        ANOMALY_SCAN_REASON_NO_SAMPLES,
        ANOMALY_SCAN_REASON_PREV_STATE_INVALID,
        ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY,
        ANOMALY_SCAN_REASON_REG_INVALID,
        ANOMALY_SCAN_REASON_REG_HARD_DEGRADED,
        ANOMALY_SCAN_REASON_WARP_LOW,
        ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH,
        ANOMALY_SCAN_REASON_STALE_HIGH,
        ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH,
        ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE,
        ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE,
        ANOMALY_SCAN_REASON_MASK_BUILD_FAILED,
        ANOMALY_SCAN_REASON_MASK_EMPTY,
        ANOMALY_SCAN_REASON_MASK_TOO_BROAD,
    };
    size_t offset = 0;
    for (size_t i = 0; i < sizeof(known_flags) / sizeof(known_flags[0]); i++) {
        uint32_t flag = known_flags[i];
        if ((flags & flag) == 0u) continue;
        int written = snprintf(buffer + offset,
                               buffer_size - offset,
                               "%s%s",
                               offset > 0 ? "|" : "",
                               scan_reason_flag_name(flag));
        if (written < 0) break;
        if ((size_t)written >= buffer_size - offset) {
            offset = buffer_size - 1;
            break;
        }
        offset += (size_t)written;
    }
}

static inline float effective_thermal_min_delta(const anomaly_config_t *cfg) {
    if (cfg == NULL || cfg->thermal_min_delta <= 0.0f) {
        return ANOMALY_THERMAL_MIN_DELTA;
    }
    return cfg->thermal_min_delta;
}

static inline int effective_sample_step(const anomaly_config_t *cfg, int width, int height) {
    if (cfg != NULL && cfg->pixel_step > 0) {
        return clamp_i32(cfg->pixel_step, 1, 8);
    }
    return (width >= 1280 || height >= 720) ? 4 : 2;
}

static inline int effective_motion_sample_step(const anomaly_config_t *cfg, int width, int height) {
    int sample_step = effective_sample_step(cfg, width, height);
    // Motion should track compact moving blobs, not every pixel-scale shimmer.
    // Keep it intentionally coarser than thermal/detail sampling so 1px detail
    // does not explode motion cost or make canopy texture look like target motion.
    int min_motion_step = (width >= 1280 || height >= 720) ? 3 : 2;
    if (sample_step < min_motion_step) sample_step = min_motion_step;
    return sample_step;
}

static inline float effective_motion_evidence_scale(const anomaly_config_t *cfg) {
    if (cfg == NULL) return 1.0f;
    float scale = cfg->motion_evidence_scale;
    if (!isfinite(scale)) return 1.0f;
    if (scale < 0.10f) return 0.10f;
    if (scale > 4.00f) return 4.00f;
    return scale;
}

static inline float motion_texture_scale(int texture_score) {
    if (texture_score <= 8) return 0.0f;
    if (texture_score >= 24) return 1.0f;
    return (float)(texture_score - 8) / 16.0f;
}

static float motion_structure_scale(
        const uint8_t *luma,
        int            w,
        int            h,
        int            x,
        int            y) {
    if (luma == NULL || x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2) {
        return 0.0f;
    }

    float sum_gxx = 0.0f;
    float sum_gyy = 0.0f;
    float sum_gxy = 0.0f;
    for (int ky = -1; ky <= 1; ky++) {
        for (int kx = -1; kx <= 1; kx++) {
            int sx = x + kx;
            int sy = y + ky;
            float gx = (float)luma[sy * w + (sx + 1)] - (float)luma[sy * w + (sx - 1)];
            float gy = (float)luma[(sy + 1) * w + sx] - (float)luma[(sy - 1) * w + sx];
            sum_gxx += gx * gx;
            sum_gyy += gy * gy;
            sum_gxy += gx * gy;
        }
    }

    float tr = sum_gxx + sum_gyy;
    if (tr < 1e-3f) return 0.0f;
    float det_term = (sum_gxx - sum_gyy) * (sum_gxx - sum_gyy) + 4.0f * sum_gxy * sum_gxy;
    float root = sqrtf(fmaxf(det_term, 0.0f));
    float minor = 0.5f * (tr - root);
    float corner_ratio = minor / tr;
    if (corner_ratio <= 0.03f) return 0.0f;
    if (corner_ratio >= 0.16f) return 1.0f;
    return (corner_ratio - 0.03f) / 0.13f;
}

static bool solve_3x3(
        const float a_in[3][3],
        const float b_in[3],
        float out[3]) {
    float a[3][4];
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) a[r][c] = a_in[r][c];
        a[r][3] = b_in[r];
    }

    for (int pivot = 0; pivot < 3; pivot++) {
        int best_row = pivot;
        float best_abs = fabsf(a[pivot][pivot]);
        for (int r = pivot + 1; r < 3; r++) {
            float cand = fabsf(a[r][pivot]);
            if (cand > best_abs) {
                best_abs = cand;
                best_row = r;
            }
        }
        if (best_abs < 1e-4f) return false;
        if (best_row != pivot) {
            for (int c = pivot; c < 4; c++) {
                float tmp = a[pivot][c];
                a[pivot][c] = a[best_row][c];
                a[best_row][c] = tmp;
            }
        }
        float inv = 1.0f / a[pivot][pivot];
        for (int c = pivot; c < 4; c++) a[pivot][c] *= inv;
        for (int r = 0; r < 3; r++) {
            if (r == pivot) continue;
            float factor = a[r][pivot];
            if (fabsf(factor) < 1e-6f) continue;
            for (int c = pivot; c < 4; c++) {
                a[r][c] -= factor * a[pivot][c];
            }
        }
    }

    out[0] = a[0][3];
    out[1] = a[1][3];
    out[2] = a[2][3];
    return true;
}

static bool solve_6x6(
        const float a_in[6][6],
        const float b_in[6],
        float out[6]) {
    float a[6][7];
    for (int r = 0; r < 6; r++) {
        for (int c = 0; c < 6; c++) a[r][c] = a_in[r][c];
        a[r][6] = b_in[r];
    }

    for (int pivot = 0; pivot < 6; pivot++) {
        int best_row = pivot;
        float best_abs = fabsf(a[pivot][pivot]);
        for (int r = pivot + 1; r < 6; r++) {
            float cand = fabsf(a[r][pivot]);
            if (cand > best_abs) {
                best_abs = cand;
                best_row = r;
            }
        }
        if (best_abs < 1e-6f) return false;
        if (best_row != pivot) {
            for (int c = pivot; c < 7; c++) {
                float tmp = a[pivot][c];
                a[pivot][c] = a[best_row][c];
                a[best_row][c] = tmp;
            }
        }
        float inv = 1.0f / a[pivot][pivot];
        for (int c = pivot; c < 7; c++) a[pivot][c] *= inv;
        for (int r = 0; r < 6; r++) {
            if (r == pivot) continue;
            float factor = a[r][pivot];
            if (fabsf(factor) < 1e-8f) continue;
            for (int c = pivot; c < 7; c++) {
                a[r][c] -= factor * a[pivot][c];
            }
        }
    }

    for (int i = 0; i < 6; i++) out[i] = a[i][6];
    return true;
}

static int compare_float_qsort(const void *a, const void *b) {
    float fa = *(const float *)a;
    float fb = *(const float *)b;
    if (fa < fb) return -1;
    if (fa > fb) return 1;
    return 0;
}

typedef struct {
    int sg_x;
    int sg_y;
    int pixel_x;
    int pixel_y;
    float proposal_score;
    float thermal_score;
    float color_score;
} anomaly_motion_candidate_t;

typedef struct {
    anomaly_motion_candidate_t candidate;
    float retention_rank;
    bool retention_rank_valid;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float peak_delta;
    float mean_delta;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
} anomaly_thermal_blob_candidate_t;

typedef struct {
    anomaly_motion_candidate_t candidate;
    float retention_rank;
    bool retention_rank_valid;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float peak_support;
    float mean_support;
    float isolation_score;
    float ring_fraction;
    float support_mass;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
} anomaly_color_blob_candidate_t;

typedef struct {
    bool enabled;
    bool valid;
    bool inside_scan_zone;
    int target_idx;
    int target_sx;
    int target_sy;
    int target_px;
    int target_py;
    float target_x_norm;
    float target_y_norm;
    float target_delta;
    float target_score;
    bool hot_eligible;
    bool started_component;
    bool local_max;
    int suppressor_sx;
    int suppressor_sy;
    float suppressor_delta;
    float suppressor_score;
    int component_seed_x;
    int component_seed_y;
    int component_peak_x;
    int component_peak_y;
    float component_area;
    float component_span;
    float component_fill;
    float component_peak_delta;
    float component_mean_delta;
    float component_quality;
    bool component_rejected;
    anomaly_debug_thermal_target_gate_t rejection_gate;
    bool dropped_by_cap;
    bool dropped_by_nms;
    bool replaced_by_nms;
    int nms_conflict_rank;
    int nms_conflict_sample_x;
    int nms_conflict_sample_y;
    int extracted_rank;
    int winning_rank;
    anomaly_debug_thermal_target_stage_t stage;
} anomaly_thermal_target_trace_t;

typedef struct {
    int mode;
    float affine[6];  // prev = A(curr): [m00 m01 m02; m10 m11 m12]
    similarity_2d_t similarity;
    bool scene_discontinuity;
    bool debug_valid;
    int sample_step;
    int motion_step;
    int anchor_count;
    int tracked_match_count;
    anomaly_registration_invalid_reason_t invalid_reason;
    float fit_det;
    float fit_min_scale;
    float fit_max_scale;
    float fit_anchor_residual_std;
    float fit_anchor_residual_max;
    float fit_motion_dx_std;
    float fit_motion_dy_std;
    float fit_quadrant_residual_spread;
    anomaly_debug_gmv_anchor_t anchors[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
} anomaly_registration_model_t;

static int find_target_blob_rank(
        const anomaly_thermal_blob_candidate_t *top,
        int top_count,
        const anomaly_thermal_target_trace_t *target_trace) {
    if (top == NULL || top_count <= 0 || target_trace == NULL) return -1;
    if (target_trace->component_peak_x < 0 || target_trace->component_peak_y < 0) return -1;
    for (int i = 0; i < top_count; i++) {
        if (top[i].candidate.sg_x == target_trace->component_peak_x &&
            top[i].candidate.sg_y == target_trace->component_peak_y) {
            return i;
        }
    }
    return -1;
}

static int compare_thermal_blob_rank(
        const anomaly_thermal_blob_candidate_t *lhs,
        const anomaly_thermal_blob_candidate_t *rhs) {
    if (lhs == NULL && rhs == NULL) return 0;
    if (lhs == NULL) return 1;
    if (rhs == NULL) return -1;

    if (lhs->retention_rank_valid && rhs->retention_rank_valid) {
        if (lhs->retention_rank > rhs->retention_rank) return -1;
        if (lhs->retention_rank < rhs->retention_rank) return 1;
    }

    if (lhs->area < rhs->area) return -1;
    if (lhs->area > rhs->area) return 1;

    if (lhs->span < rhs->span) return -1;
    if (lhs->span > rhs->span) return 1;

    if (lhs->candidate.thermal_score > rhs->candidate.thermal_score) return -1;
    if (lhs->candidate.thermal_score < rhs->candidate.thermal_score) return 1;

    if (lhs->peak_delta > rhs->peak_delta) return -1;
    if (lhs->peak_delta < rhs->peak_delta) return 1;

    if (lhs->quality > rhs->quality) return -1;
    if (lhs->quality < rhs->quality) return 1;

    return 0;
}

static void insert_thermal_blob_candidate(
        anomaly_thermal_blob_candidate_t *top,
        int *top_count,
        const anomaly_thermal_blob_candidate_t *candidate,
        anomaly_thermal_target_trace_t *target_trace,
        bool candidate_is_target) {
    if (top == NULL || top_count == NULL || candidate == NULL) return;
    int target_rank_before = find_target_blob_rank(top, *top_count, target_trace);
    int insert_at = *top_count;
    for (int i = 0; i < *top_count; i++) {
        int ddx = abs(top[i].candidate.sg_x - candidate->candidate.sg_x);
        int ddy = abs(top[i].candidate.sg_y - candidate->candidate.sg_y);
        if (ddx <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS &&
            ddy <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS) {
            if (compare_thermal_blob_rank(candidate, &top[i]) < 0) {
                if (target_trace != NULL && target_rank_before == i && !candidate_is_target) {
                    target_trace->dropped_by_nms = true;
                    target_trace->replaced_by_nms = true;
                    target_trace->nms_conflict_rank = i;
                    target_trace->nms_conflict_sample_x = candidate->candidate.sg_x;
                    target_trace->nms_conflict_sample_y = candidate->candidate.sg_y;
                }
                if (target_trace != NULL && candidate_is_target) {
                    target_trace->nms_conflict_rank = i;
                    target_trace->nms_conflict_sample_x = top[i].candidate.sg_x;
                    target_trace->nms_conflict_sample_y = top[i].candidate.sg_y;
                }
                top[i] = *candidate;
            } else if (target_trace != NULL && candidate_is_target) {
                target_trace->dropped_by_nms = true;
                target_trace->nms_conflict_rank = i;
                target_trace->nms_conflict_sample_x = top[i].candidate.sg_x;
                target_trace->nms_conflict_sample_y = top[i].candidate.sg_y;
            }
            return;
        }
        if (compare_thermal_blob_rank(candidate, &top[i]) < 0) {
            insert_at = i;
            break;
        }
    }
    if (insert_at >= ANOMALY_MAX_THERMAL_CANDIDATES) {
        if (target_trace != NULL && candidate_is_target) {
            target_trace->dropped_by_cap = true;
        }
        return;
    }

    if (target_trace != NULL &&
        target_rank_before == (ANOMALY_MAX_THERMAL_CANDIDATES - 1) &&
        *top_count >= ANOMALY_MAX_THERMAL_CANDIDATES &&
        insert_at <= target_rank_before &&
        !candidate_is_target) {
        target_trace->dropped_by_cap = true;
    }

    int move_limit = *top_count < ANOMALY_MAX_THERMAL_CANDIDATES
        ? *top_count
        : (ANOMALY_MAX_THERMAL_CANDIDATES - 1);
    for (int i = move_limit; i > insert_at; i--) {
        top[i] = top[i - 1];
    }
    if (*top_count < ANOMALY_MAX_THERMAL_CANDIDATES) (*top_count)++;
    top[insert_at] = *candidate;
}

static int compare_color_blob_rank(
        const anomaly_color_blob_candidate_t *lhs,
        const anomaly_color_blob_candidate_t *rhs) {
    if (lhs == NULL && rhs == NULL) return 0;
    if (lhs == NULL) return 1;
    if (rhs == NULL) return -1;

    if (lhs->retention_rank_valid && rhs->retention_rank_valid) {
        if (lhs->retention_rank > rhs->retention_rank) return -1;
        if (lhs->retention_rank < rhs->retention_rank) return 1;
    }
    if (lhs->area < rhs->area) return -1;
    if (lhs->area > rhs->area) return 1;
    if (lhs->span < rhs->span) return -1;
    if (lhs->span > rhs->span) return 1;
    if (lhs->candidate.color_score > rhs->candidate.color_score) return -1;
    if (lhs->candidate.color_score < rhs->candidate.color_score) return 1;
    if (lhs->quality > rhs->quality) return -1;
    if (lhs->quality < rhs->quality) return 1;
    return 0;
}

static void insert_color_blob_candidate(
        anomaly_color_blob_candidate_t *top,
        int                            *top_count,
        const anomaly_color_blob_candidate_t *candidate) {
    if (top == NULL || top_count == NULL || candidate == NULL) return;

    int insert_at = *top_count;
    for (int i = 0; i < *top_count; i++) {
        int ddx = abs(top[i].candidate.sg_x - candidate->candidate.sg_x);
        int ddy = abs(top[i].candidate.sg_y - candidate->candidate.sg_y);
        if (ddx <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS &&
            ddy <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS) {
            if (compare_color_blob_rank(candidate, &top[i]) < 0) {
                top[i] = *candidate;
            }
            return;
        }
        if (compare_color_blob_rank(candidate, &top[i]) < 0) {
            insert_at = i;
            break;
        }
    }

    if (insert_at >= ANOMALY_MAX_COLOR_CANDIDATES) return;
    int move_limit = *top_count < ANOMALY_MAX_COLOR_CANDIDATES
        ? *top_count
        : (ANOMALY_MAX_COLOR_CANDIDATES - 1);
    for (int i = move_limit; i > insert_at; i--) {
        top[i] = top[i - 1];
    }
    if (*top_count < ANOMALY_MAX_COLOR_CANDIDATES) (*top_count)++;
    top[insert_at] = *candidate;
}

static int color_support_patch_radius(
        const anomaly_config_t *cfg,
        int frame_w,
        int frame_h,
        int sample_step) {
    float target_span_px = effective_color_target_span_px(cfg, frame_w, frame_h);
    int step = sample_step > 0 ? sample_step : 1;
    int patch_radius = (int)lroundf(fmaxf(1.0f, (0.5f * target_span_px) / (float)step));
    if (patch_radius > 4) patch_radius = 4;
    return patch_radius;
}

static bool compute_active_mask_bounds(
        const uint8_t *mask,
        int sg_w,
        int sg_h,
        int pad,
        int *min_sx_out,
        int *min_sy_out,
        int *max_sx_out,
        int *max_sy_out) {
    if (mask == NULL || sg_w <= 0 || sg_h <= 0 ||
        min_sx_out == NULL || min_sy_out == NULL ||
        max_sx_out == NULL || max_sy_out == NULL) {
        return false;
    }

    int min_sx = sg_w;
    int min_sy = sg_h;
    int max_sx = -1;
    int max_sy = -1;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (mask[idx] == 0u) continue;
            if (sx < min_sx) min_sx = sx;
            if (sx > max_sx) max_sx = sx;
            if (sy < min_sy) min_sy = sy;
            if (sy > max_sy) max_sy = sy;
        }
    }
    if (max_sx < min_sx || max_sy < min_sy) return false;

    if (pad < 0) pad = 0;
    min_sx -= pad;
    min_sy -= pad;
    max_sx += pad;
    max_sy += pad;
    if (min_sx < 0) min_sx = 0;
    if (min_sy < 0) min_sy = 0;
    if (max_sx >= sg_w) max_sx = sg_w - 1;
    if (max_sy >= sg_h) max_sy = sg_h - 1;

    *min_sx_out = min_sx;
    *min_sy_out = min_sy;
    *max_sx_out = max_sx;
    *max_sy_out = max_sy;
    return true;
}

static void zero_float_region(
        float *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (map == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memset(&map[row_idx], 0, (size_t)span * sizeof(float));
    }
}

static void copy_float_region(
        float *dst,
        const float *src,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (dst == NULL || src == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memcpy(&dst[row_idx], &src[row_idx], (size_t)span * sizeof(float));
    }
}

static void zero_u8_region(
        uint8_t *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (map == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memset(&map[row_idx], 0, (size_t)span * sizeof(uint8_t));
    }
}

static inline void color_sampling_phase_for_frame(
        const anomaly_state_t *state,
        int sample_step,
        int *phase_index_out,
        int *phase_x_out,
        int *phase_y_out) {
    int phase_index = 0;
    int phase_x = 0;
    int phase_y = 0;
    int step = sample_step > 0 ? sample_step : 1;
    if (step > 1 && state != NULL) {
        int phase_count = step * step;
        if (phase_count < 1) phase_count = 1;
        phase_index = (int)(state->color_phase_counter % (uint64_t)phase_count);
        phase_x = phase_index % step;
        phase_y = phase_index / step;
    }
    if (phase_index_out != NULL) *phase_index_out = phase_index;
    if (phase_x_out != NULL) *phase_x_out = phase_x;
    if (phase_y_out != NULL) *phase_y_out = phase_y;
}

static inline void advance_color_sampling_phase(
        anomaly_state_t *state,
        bool appearance_refresh_ran,
        int sample_step) {
    if (state == NULL || !appearance_refresh_ran) return;
    int step = sample_step > 0 ? sample_step : 1;
    if (step <= 1) return;
    state->color_phase_counter += 1u;
}

static inline void compute_color_sample_xy(
        int roi_x0,
        int roi_y0,
        int roi_x1,
        int roi_y1,
        int sx,
        int sy,
        int sample_step,
        int phase_x,
        int phase_y,
        int *sample_x_out,
        int *sample_y_out) {
    int step = sample_step > 0 ? sample_step : 1;
    int cell_x0 = roi_x0 + sx * step;
    int cell_y0 = roi_y0 + sy * step;
    int cell_x1 = cell_x0 + step;
    int cell_y1 = cell_y0 + step;
    if (cell_x1 > roi_x1) cell_x1 = roi_x1;
    if (cell_y1 > roi_y1) cell_y1 = roi_y1;
    if (cell_x1 <= cell_x0) cell_x1 = cell_x0 + 1;
    if (cell_y1 <= cell_y0) cell_y1 = cell_y0 + 1;
    int local_phase_x = phase_x;
    int local_phase_y = phase_y;
    if (local_phase_x < 0) local_phase_x = 0;
    if (local_phase_y < 0) local_phase_y = 0;
    int max_local_x = cell_x1 - cell_x0 - 1;
    int max_local_y = cell_y1 - cell_y0 - 1;
    if (local_phase_x > max_local_x) local_phase_x = max_local_x;
    if (local_phase_y > max_local_y) local_phase_y = max_local_y;
    int sample_x = cell_x0 + local_phase_x;
    int sample_y = cell_y0 + local_phase_y;
    if (sample_x >= roi_x1) sample_x = roi_x1 - 1;
    if (sample_y >= roi_y1) sample_y = roi_y1 - 1;
    if (sample_x < roi_x0) sample_x = roi_x0;
    if (sample_y < roi_y0) sample_y = roi_y0;
    if (sample_x_out != NULL) *sample_x_out = sample_x;
    if (sample_y_out != NULL) *sample_y_out = sample_y;
}

typedef struct {
    float m00;
    float m01;
    float m02;
    float m10;
    float m11;
    float m12;
    bool valid;
} anomaly_color_inverse_affine_t;

static inline bool color_registration_model_valid(const anomaly_registration_model_t *model) {
    return model != NULL && model->similarity.valid;
}

static inline anomaly_color_inverse_affine_t color_registration_inverse_affine(
        const anomaly_registration_model_t *model) {
    anomaly_color_inverse_affine_t inv;
    memset(&inv, 0, sizeof(inv));
    if (model == NULL) return inv;
    float det = model->affine[0] * model->affine[4] - model->affine[1] * model->affine[3];
    if (fabsf(det) < 1e-6f) return inv;
    float inv_det = 1.0f / det;
    inv.m00 =  model->affine[4] * inv_det;
    inv.m01 = -model->affine[1] * inv_det;
    inv.m02 = (model->affine[1] * model->affine[5] - model->affine[4] * model->affine[2]) * inv_det;
    inv.m10 = -model->affine[3] * inv_det;
    inv.m11 =  model->affine[0] * inv_det;
    inv.m12 = (model->affine[3] * model->affine[2] - model->affine[0] * model->affine[5]) * inv_det;
    inv.valid = true;
    return inv;
}

static inline bool color_registration_invert_point_fast(
        const anomaly_color_inverse_affine_t *inv,
        float                                 x,
        float                                 y,
        float                                *out_x,
        float                                *out_y) {
    if (inv == NULL || !inv->valid || out_x == NULL || out_y == NULL) return false;
    *out_x = inv->m00 * x + inv->m01 * y + inv->m02;
    *out_y = inv->m10 * x + inv->m11 * y + inv->m12;
    return true;
}

static inline void sample_color_cell(
        const uint8_t *rgba,
        int rgba_stride,
        int sample_x,
        int sample_y,
        float *luma_out,
        float *u_out,
        float *v_out) {
    if (luma_out != NULL) *luma_out = 0.0f;
    if (u_out != NULL) *u_out = 0.0f;
    if (v_out != NULL) *v_out = 0.0f;
    if (rgba == NULL || rgba_stride <= 0 || sample_x < 0 || sample_y < 0) return;
    const uint8_t *px = rgba + sample_y * rgba_stride + sample_x * 4;
    float r = (float)px[0];
    float g = (float)px[1];
    float b = (float)px[2];
    if (luma_out != NULL) *luma_out = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
    if (u_out != NULL) *u_out = (-0.14713f * r) - (0.28886f * g) + (0.43600f * b);
    if (v_out != NULL) *v_out = ( 0.61500f * r) - (0.51499f * g) - (0.10001f * b);
}

static bool prepare_color_sampling_state(
        anomaly_state_t                   *state,
        const anomaly_registration_model_t *registration,
        const uint8_t                     *rgba,
        int                                rgba_stride,
        int                                frame_width,
        int                                frame_height,
        int                                roi_x0,
        int                                roi_y0,
        int                                roi_x1,
        int                                roi_y1,
        int                                sample_step,
        int                                sg_w,
        int                                sg_h,
        bool                               selective_refresh_active,
        const uint8_t                     *refresh_mask,
        int                                active_phase_x,
        int                                active_phase_y,
        bool                              *forced_full_refresh_out,
        uint32_t                          *fallback_reason_flags_out,
        int                               *fresh_count_out,
        int                               *carried_count_out,
        int                               *unsampled_count_out) {
    if (forced_full_refresh_out != NULL) *forced_full_refresh_out = false;
    if (fallback_reason_flags_out != NULL) *fallback_reason_flags_out = 0u;
    if (fresh_count_out != NULL) *fresh_count_out = 0;
    if (carried_count_out != NULL) *carried_count_out = 0;
    if (unsampled_count_out != NULL) *unsampled_count_out = 0;
    if (state == NULL) return false;
    anomaly_roi_state_t *roi_state = &state->roi_state;
    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    if (!ensure_roi_state_capacity(roi_state, sg_count)) return false;

    bool full_refresh = !selective_refresh_active || refresh_mask == NULL || !color_registration_model_valid(registration);
    if (!full_refresh) {
        if (!roi_state->valid ||
            roi_state->width != sg_w ||
            roi_state->height != sg_h ||
            roi_state->sample_step != sample_step ||
            roi_state->color_valid_mask == NULL) {
            full_refresh = true;
            if (fallback_reason_flags_out != NULL) {
                *fallback_reason_flags_out |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
            }
        }
    }
    if (full_refresh) {
        if (forced_full_refresh_out != NULL) *forced_full_refresh_out = selective_refresh_active;
        for (int sy = 0; sy < sg_h; sy++) {
            for (int sx = 0; sx < sg_w; sx++) {
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                int sample_x = 0;
                int sample_y = 0;
                compute_color_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                sample_color_cell(
                        rgba, rgba_stride, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                fill_color_uv_bins(roi_state, idx);
                roi_state->color_valid_mask[idx] = 1u;
                roi_state->color_phase_x[idx] = (uint8_t)active_phase_x;
                roi_state->color_phase_y[idx] = (uint8_t)active_phase_y;
            }
        }
        if (fresh_count_out != NULL) *fresh_count_out = (int)sg_count;
        return true;
    }

    if (!ensure_prev_roi_snapshot_capacity(state, sg_count)) return false;
    float *prev_color_luma = state->scratch_prev_roi_color_luma;
    float *prev_color_u = state->scratch_prev_roi_color_u;
    float *prev_color_v = state->scratch_prev_roi_color_v;
    float *prev_color_raw_score = state->scratch_prev_roi_color_raw_score;
    float *prev_color_contrast_weight = state->scratch_prev_roi_color_contrast_weight;
    uint8_t *prev_color_u_bin = state->scratch_prev_roi_color_u_bin;
    uint8_t *prev_color_v_bin = state->scratch_prev_roi_color_v_bin;
    uint8_t *prev_color_valid_mask = state->scratch_prev_roi_color_valid_mask;
    uint8_t *prev_color_phase_x = state->scratch_prev_roi_color_phase_x;
    uint8_t *prev_color_phase_y = state->scratch_prev_roi_color_phase_y;
    memcpy(prev_color_luma, roi_state->color_luma, sg_count * sizeof(float));
    memcpy(prev_color_u, roi_state->color_u, sg_count * sizeof(float));
    memcpy(prev_color_v, roi_state->color_v, sg_count * sizeof(float));
    memcpy(prev_color_raw_score, roi_state->color_raw_score, sg_count * sizeof(float));
    memcpy(prev_color_contrast_weight, roi_state->color_contrast_weight, sg_count * sizeof(float));
    memcpy(prev_color_u_bin, roi_state->color_u_bin, sg_count * sizeof(uint8_t));
    memcpy(prev_color_v_bin, roi_state->color_v_bin, sg_count * sizeof(uint8_t));
    memcpy(prev_color_valid_mask, roi_state->color_valid_mask, sg_count * sizeof(uint8_t));
    memcpy(prev_color_phase_x, roi_state->color_phase_x, sg_count * sizeof(uint8_t));
    memcpy(prev_color_phase_y, roi_state->color_phase_y, sg_count * sizeof(uint8_t));

    float fw = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float fh = (float)(frame_height > 1 ? frame_height - 1 : 1);
    anomaly_color_inverse_affine_t inv = color_registration_inverse_affine(registration);
    if (!inv.valid) {
        if (fallback_reason_flags_out != NULL) {
            *fallback_reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        }
        if (forced_full_refresh_out != NULL) *forced_full_refresh_out = true;
        for (int sy = 0; sy < sg_h; sy++) {
            for (int sx = 0; sx < sg_w; sx++) {
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                int sample_x = 0;
                int sample_y = 0;
                compute_color_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                sample_color_cell(
                        rgba, rgba_stride, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                fill_color_uv_bins(roi_state, idx);
                roi_state->color_valid_mask[idx] = 1u;
                roi_state->color_phase_x[idx] = (uint8_t)active_phase_x;
                roi_state->color_phase_y[idx] = (uint8_t)active_phase_y;
            }
        }
        if (fresh_count_out != NULL) *fresh_count_out = (int)sg_count;
        return true;
    }

    int fresh_count = 0;
    int carried_count = 0;
    int unsampled_count = 0;
    int prev_roi_x0 = roi_state->roi_x0;
    int prev_roi_y0 = roi_state->roi_y0;
    int prev_roi_x1 = roi_state->roi_x1;
    int prev_roi_y1 = roi_state->roi_y1;
    int prev_width = roi_state->width;
    int prev_height = roi_state->height;
    int prev_sample_step = roi_state->sample_step;
    for (int sy = 0; sy < sg_h; sy++) {
        int center_y = roi_y0 + sy * sample_step + sample_step / 2;
        if (center_y >= roi_y1) center_y = roi_y1 - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (refresh_mask[idx] != 0u) {
                int sample_x = 0;
                int sample_y = 0;
                compute_color_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                sample_color_cell(
                        rgba, rgba_stride, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                fill_color_uv_bins(roi_state, idx);
                roi_state->color_valid_mask[idx] = 1u;
                roi_state->color_phase_x[idx] = (uint8_t)active_phase_x;
                roi_state->color_phase_y[idx] = (uint8_t)active_phase_y;
                fresh_count++;
                continue;
            }

            int center_x = roi_x0 + sx * sample_step + sample_step / 2;
            if (center_x >= roi_x1) center_x = roi_x1 - 1;
            float nx = clamp01f((float)center_x / fw);
            float ny = clamp01f((float)center_y / fh);
            float px = 0.0f;
            float py = 0.0f;
            bool carried = false;
            if (color_registration_invert_point_fast(&inv, nx, ny, &px, &py)) {
                int prev_px = clamp_i32((int)lroundf(px * fw), 0, frame_width - 1);
                int prev_py = clamp_i32((int)lroundf(py * fh), 0, frame_height - 1);
                if (prev_px >= prev_roi_x0 && prev_px < prev_roi_x1 &&
                    prev_py >= prev_roi_y0 && prev_py < prev_roi_y1) {
                    int prev_sx = (prev_px - prev_roi_x0) / prev_sample_step;
                    int prev_sy = (prev_py - prev_roi_y0) / prev_sample_step;
                    if (prev_sx >= 0 && prev_sy >= 0 &&
                        prev_sx < prev_width && prev_sy < prev_height) {
                        size_t prev_idx = (size_t)prev_sy * (size_t)prev_width + (size_t)prev_sx;
                        if (prev_color_valid_mask[prev_idx] != 0u) {
                            roi_state->color_luma[idx] = prev_color_luma[prev_idx];
                            roi_state->color_u[idx] = prev_color_u[prev_idx];
                            roi_state->color_v[idx] = prev_color_v[prev_idx];
                            roi_state->color_raw_score[idx] = prev_color_raw_score[prev_idx];
                            roi_state->color_contrast_weight[idx] = prev_color_contrast_weight[prev_idx];
                            roi_state->color_u_bin[idx] = prev_color_u_bin[prev_idx];
                            roi_state->color_v_bin[idx] = prev_color_v_bin[prev_idx];
                            roi_state->color_valid_mask[idx] = 1u;
                            roi_state->color_phase_x[idx] = prev_color_phase_x[prev_idx];
                            roi_state->color_phase_y[idx] = prev_color_phase_y[prev_idx];
                            carried = true;
                            carried_count++;
                        }
                    }
                }
            }
            if (!carried) {
                roi_state->color_luma[idx] = 0.0f;
                roi_state->color_u[idx] = 0.0f;
                roi_state->color_v[idx] = 0.0f;
                roi_state->color_raw_score[idx] = 0.0f;
                roi_state->color_contrast_weight[idx] = 0.0f;
                roi_state->color_u_bin[idx] = 0u;
                roi_state->color_v_bin[idx] = 0u;
                roi_state->color_valid_mask[idx] = 0u;
                roi_state->color_phase_x[idx] = 0u;
                roi_state->color_phase_y[idx] = 0u;
                unsampled_count++;
            }
        }
    }
    if (fresh_count_out != NULL) *fresh_count_out = fresh_count;
    if (carried_count_out != NULL) *carried_count_out = carried_count;
    if (unsampled_count_out != NULL) *unsampled_count_out = unsampled_count;
    return true;
}

static void compute_local_color_contrast(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        sx,
        int                        sy,
        float                     *avg_chroma_out,
        float                     *avg_luma_out,
        int                       *neighbor_count_out) {
    if (avg_chroma_out != NULL) *avg_chroma_out = 0.0f;
    if (avg_luma_out != NULL) *avg_luma_out = 0.0f;
    if (neighbor_count_out != NULL) *neighbor_count_out = 0;
    if (roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL ||
        roi_state->color_luma == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h) {
        return;
    }
    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    if (roi_state->color_valid_mask[idx] == 0u) return;

    float center_u = roi_state->color_u[idx];
    float center_v = roi_state->color_v[idx];
    float center_luma = roi_state->color_luma[idx];
    float chroma_sum = 0.0f;
    float luma_sum = 0.0f;
    int neighbor_count = 0;
    for (int ny = sy - 1; ny <= sy + 1; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - 1; nx <= sx + 1; nx++) {
            if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            if (roi_state->color_valid_mask[nidx] == 0u) continue;
            float du = center_u - roi_state->color_u[nidx];
            float dv = center_v - roi_state->color_v[nidx];
            chroma_sum += sqrtf((du * du) + (dv * dv));
            luma_sum += fabsf(center_luma - roi_state->color_luma[nidx]);
            neighbor_count++;
        }
    }
    if (neighbor_count <= 0) return;
    if (avg_chroma_out != NULL) *avg_chroma_out = chroma_sum / (float)neighbor_count;
    if (avg_luma_out != NULL) *avg_luma_out = luma_sum / (float)neighbor_count;
    if (neighbor_count_out != NULL) *neighbor_count_out = neighbor_count;
}

static void compute_color_contrast_weights(
        anomaly_roi_state_t *roi_state,
        int                  sg_w,
        int                  sg_h) {
    if (roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_contrast_weight == NULL || sg_w <= 0 || sg_h <= 0) {
        return;
    }

#define T ANOMALY_LOCAL_TILE_SIZE
    double tile_sum_chroma[T][T], tile_sum_chroma2[T][T];
    double tile_sum_luma[T][T], tile_sum_luma2[T][T];
    int tile_n[T][T];
    memset(tile_sum_chroma, 0, sizeof(tile_sum_chroma));
    memset(tile_sum_chroma2, 0, sizeof(tile_sum_chroma2));
    memset(tile_sum_luma, 0, sizeof(tile_sum_luma));
    memset(tile_sum_luma2, 0, sizeof(tile_sum_luma2));
    memset(tile_n, 0, sizeof(tile_n));

    double global_sum_chroma = 0.0;
    double global_sum_chroma2 = 0.0;
    double global_sum_luma = 0.0;
    double global_sum_luma2 = 0.0;
    int global_n = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        int tr = sy * T / sg_h;
        if (tr >= T) tr = T - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            int tc = sx * T / sg_w;
            if (tc >= T) tc = T - 1;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) {
                roi_state->color_contrast_weight[idx] = 0.0f;
                continue;
            }
            float avg_chroma = 0.0f;
            float avg_luma = 0.0f;
            int neighbor_count = 0;
            compute_local_color_contrast(roi_state, sg_w, sg_h, sx, sy, &avg_chroma, &avg_luma, &neighbor_count);
            if (neighbor_count <= 0) {
                roi_state->color_contrast_weight[idx] = 0.35f;
                continue;
            }
            tile_sum_chroma[tr][tc] += avg_chroma;
            tile_sum_chroma2[tr][tc] += (double)avg_chroma * (double)avg_chroma;
            tile_sum_luma[tr][tc] += avg_luma;
            tile_sum_luma2[tr][tc] += (double)avg_luma * (double)avg_luma;
            tile_n[tr][tc]++;
            global_sum_chroma += avg_chroma;
            global_sum_chroma2 += (double)avg_chroma * (double)avg_chroma;
            global_sum_luma += avg_luma;
            global_sum_luma2 += (double)avg_luma * (double)avg_luma;
            global_n++;
        }
    }

    double global_mean_chroma = global_n > 0 ? global_sum_chroma / (double)global_n : 0.0;
    double global_std_chroma = global_n > 0
        ? sqrt(fmax(global_sum_chroma2 / (double)global_n - global_mean_chroma * global_mean_chroma, 0.01))
        : 1.0;
    double global_mean_luma = global_n > 0 ? global_sum_luma / (double)global_n : 0.0;
    double global_std_luma = global_n > 0
        ? sqrt(fmax(global_sum_luma2 / (double)global_n - global_mean_luma * global_mean_luma, 0.01))
        : 1.0;

    for (int sy = 0; sy < sg_h; sy++) {
        int tr = sy * T / sg_h;
        if (tr >= T) tr = T - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            int tc = sx * T / sg_w;
            if (tc >= T) tc = T - 1;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) {
                roi_state->color_contrast_weight[idx] = 0.0f;
                continue;
            }
            float avg_chroma = 0.0f;
            float avg_luma = 0.0f;
            int neighbor_count = 0;
            compute_local_color_contrast(roi_state, sg_w, sg_h, sx, sy, &avg_chroma, &avg_luma, &neighbor_count);
            if (neighbor_count <= 0) {
                roi_state->color_contrast_weight[idx] = 0.35f;
                continue;
            }

            double mean_chroma = global_mean_chroma;
            double std_chroma = global_std_chroma;
            double mean_luma = global_mean_luma;
            double std_luma = global_std_luma;
            if (tile_n[tr][tc] >= ANOMALY_LOCAL_TILE_MIN_N) {
                double fn = (double)tile_n[tr][tc];
                mean_chroma = tile_sum_chroma[tr][tc] / fn;
                std_chroma = sqrt(fmax(tile_sum_chroma2[tr][tc] / fn - mean_chroma * mean_chroma, 0.01));
                mean_luma = tile_sum_luma[tr][tc] / fn;
                std_luma = sqrt(fmax(tile_sum_luma2[tr][tc] / fn - mean_luma * mean_luma, 0.01));
            }
            double chroma_floor = mean_chroma + (0.35 * std_chroma);
            double luma_floor = mean_luma + (0.35 * std_luma);
            double chroma_signal = ((double)avg_chroma - chroma_floor) / fmax(chroma_floor + 1.5, 1.0);
            double luma_signal = ((double)avg_luma - luma_floor) / fmax(luma_floor + 4.0, 1.0);
            double combined = 0.65 * chroma_signal + 0.35 * luma_signal;
            float weight = clampf(0.35f + (float)combined, 0.20f, 1.15f);
            roi_state->color_contrast_weight[idx] = weight;
        }
    }
#undef T
}

static void build_color_support_map(
        const anomaly_config_t *cfg,
        const float            *raw_map,
        const float            *contrast_map,
        int                     sg_w,
        int                     sg_h,
        int                     frame_w,
        int                     frame_h,
        int                     sample_step,
        int                     active_min_sx,
        int                     active_min_sy,
        int                     active_max_sx,
        int                     active_max_sy,
        float                  *support_map,
        float                  *scratch_map,
        float                  *max_support_out,
        int                    *seed_min_sx_out,
        int                    *seed_min_sy_out,
        int                    *seed_max_sx_out,
        int                    *seed_max_sy_out,
        int                    *seed_count_out) {
    if (raw_map == NULL || support_map == NULL || scratch_map == NULL || sg_w <= 0 || sg_h <= 0) return;
    if (max_support_out != NULL) *max_support_out = 0.0f;
    if (seed_count_out != NULL) *seed_count_out = 0;
    if (seed_min_sx_out != NULL) *seed_min_sx_out = sg_w;
    if (seed_min_sy_out != NULL) *seed_min_sy_out = sg_h;
    if (seed_max_sx_out != NULL) *seed_max_sx_out = -1;
    if (seed_max_sy_out != NULL) *seed_max_sy_out = -1;
    int patch_radius = color_support_patch_radius(cfg, frame_w, frame_h, sample_step);
    if (active_min_sx < 0) active_min_sx = 0;
    if (active_min_sy < 0) active_min_sy = 0;
    if (active_max_sx >= sg_w) active_max_sx = sg_w - 1;
    if (active_max_sy >= sg_h) active_max_sy = sg_h - 1;
    if (active_max_sx < active_min_sx || active_max_sy < active_min_sy) {
        return;
    }
    zero_float_region(scratch_map, sg_w, active_min_sx, active_min_sy, active_max_sx, active_max_sy);

    for (int sy = active_min_sy; sy <= active_max_sy; sy++) {
        for (int sx = active_min_sx; sx <= active_max_sx; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            float center = raw_map[idx];
            if (center <= 0.0f) {
                scratch_map[idx] = 0.0f;
                continue;
            }
            float sum = 0.0f;
            float ring_sum = 0.0f;
            int count = 0;
            int support_count = 0;
            for (int ny = sy - patch_radius; ny <= sy + patch_radius; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - patch_radius; nx <= sx + patch_radius; nx++) {
                    if (nx < 0 || nx >= sg_w) continue;
                    int chebyshev = abs(nx - sx);
                    int dy = abs(ny - sy);
                    if (dy > chebyshev) chebyshev = dy;
                    float v = raw_map[(size_t)ny * (size_t)sg_w + (size_t)nx];
                    if (v <= 0.0f) continue;
                    sum += v;
                    count++;
                    if (v >= 0.35f) support_count++;
                    if (chebyshev == patch_radius) ring_sum += v;
                }
            }
            float mean = count > 0 ? (sum / (float)count) : 0.0f;
            float ring_mean = patch_radius > 0 ? (ring_sum / (float)(patch_radius * 8)) : 0.0f;
            float density = count > 0 ? ((float)support_count / (float)count) : 0.0f;
            float contrast_weight = contrast_map != NULL ? contrast_map[idx] : 1.0f;
            float support_weight = clampf(0.55f + 0.45f * contrast_weight, 0.35f, 1.20f);
            float patch_support =
                0.55f * center +
                0.75f * mean +
                0.90f * density -
                0.30f * ring_mean;
            patch_support *= support_weight;
            float clamped_support = clampf(patch_support, 0.0f, 4.0f);
            scratch_map[idx] = clamped_support;
            if (max_support_out != NULL && clamped_support > *max_support_out) {
                *max_support_out = clamped_support;
            }
            if (clamped_support >= 0.55f) {
                if (seed_count_out != NULL) (*seed_count_out)++;
                if (seed_min_sx_out != NULL && sx < *seed_min_sx_out) *seed_min_sx_out = sx;
                if (seed_min_sy_out != NULL && sy < *seed_min_sy_out) *seed_min_sy_out = sy;
                if (seed_max_sx_out != NULL && sx > *seed_max_sx_out) *seed_max_sx_out = sx;
                if (seed_max_sy_out != NULL && sy > *seed_max_sy_out) *seed_max_sy_out = sy;
            }
        }
    }

    if (support_map != scratch_map) {
        copy_float_region(
                support_map,
                scratch_map,
                sg_w,
                active_min_sx,
                active_min_sy,
                active_max_sx,
                active_max_sy);
    }
}

static void extract_color_blob_candidates(
        const anomaly_config_t *cfg,
        const float            *color_support_map,
        const float            *contrast_map,
        int                     sg_w,
        int                     sg_h,
        int                     frame_w,
        int                     frame_h,
        int                     roi_x0,
        int                     roi_y0,
        int                     sample_step,
        int                     active_min_sx,
        int                     active_min_sy,
        int                     active_max_sx,
        int                     active_max_sy,
        uint8_t                *visited,
        int                    *queue,
        anomaly_color_blob_candidate_t *out_candidates,
        int                    *out_count) {
    if (out_count != NULL) *out_count = 0;
    if (color_support_map == NULL || visited == NULL || queue == NULL ||
        out_candidates == NULL || out_count == NULL || sg_w <= 0 || sg_h <= 0) {
        return;
    }

    if (active_min_sx < 0) active_min_sx = 0;
    if (active_min_sy < 0) active_min_sy = 0;
    if (active_max_sx >= sg_w) active_max_sx = sg_w - 1;
    if (active_max_sy >= sg_h) active_max_sy = sg_h - 1;
    if (active_max_sx < active_min_sx || active_max_sy < active_min_sy) return;
    zero_u8_region(visited, sg_w, active_min_sx, active_min_sy, active_max_sx, active_max_sy);

    float target_span_px = effective_color_target_span_px(cfg, frame_w, frame_h);
    float small_target_limit_px = effective_thermal_small_target_span_px(cfg, frame_w, frame_h);
    int target_span_cells = (int)lroundf(fmaxf(1.0f, target_span_px / (float)(sample_step > 0 ? sample_step : 1)));
    int max_blob_area = target_span_cells * target_span_cells * 3;
    if (max_blob_area < 4) max_blob_area = 4;
    if (max_blob_area > 36) max_blob_area = 36;

    for (int sy = active_min_sy; sy <= active_max_sy; sy++) {
        for (int sx = active_min_sx; sx <= active_max_sx; sx++) {
            size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (visited[seed_idx] != 0u) continue;
            float seed_support = color_support_map[seed_idx];
            if (seed_support < 0.55f) continue;

            int head = 0;
            int tail = 0;
            queue[tail++] = (int)seed_idx;
            visited[seed_idx] = 1u;

            int area = 0;
            int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
            double sum_support = 0.0;
            float peak_support = seed_support;
            int peak_x = sx;
            int peak_y = sy;

            while (head < tail) {
                int cur = queue[head++];
                int cx = cur % sg_w;
                int cy = cur / sg_w;
                float cur_support = color_support_map[cur];
                if (cur_support <= 0.0f) continue;

                area++;
                sum_support += (double)cur_support;
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;
                if (cur_support > peak_support) {
                    peak_support = cur_support;
                    peak_x = cx;
                    peak_y = cy;
                }

                float blob_mean = area > 0 ? (float)(sum_support / (double)area) : seed_support;
                float join_floor = fmaxf(0.25f, fminf(seed_support, blob_mean) * 0.45f);
                float band = fmaxf(0.35f, seed_support * 0.65f);
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        int nx = cx + ox;
                        int ny = cy + oy;
                        if (nx < active_min_sx || nx > active_max_sx ||
                            ny < active_min_sy || ny > active_max_sy) {
                            continue;
                        }
                        size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                        if (visited[nidx] != 0u) continue;
                        float neighbor = color_support_map[nidx];
                        if (neighbor < join_floor) continue;
                        if (fabsf(neighbor - cur_support) > band &&
                            fabsf(neighbor - blob_mean) > band) {
                            continue;
                        }
                        visited[nidx] = 1u;
                        queue[tail++] = (int)nidx;
                    }
                }
            }

            if (area <= 0) continue;
            int span_w = max_x - min_x + 1;
            int span_h = max_y - min_y + 1;
            int bbox_area = span_w * span_h;
            float fill = bbox_area > 0 ? ((float)area / (float)bbox_area) : 0.0f;
            float span = (float)(span_w > span_h ? span_w : span_h);
            float span_px = span * (float)(sample_step > 0 ? sample_step : 1);
            float mean_support = (float)(sum_support / (double)area);
            float center_share = sum_support > 0.0 ? (peak_support / (float)sum_support) : 0.0f;
            float contrast_sum = 0.0f;
            int contrast_count = 0;
            for (int gy = min_y; gy <= max_y; gy++) {
                for (int gx = min_x; gx <= max_x; gx++) {
                    size_t cidx = (size_t)gy * (size_t)sg_w + (size_t)gx;
                    if (color_support_map[cidx] <= 0.0f) continue;
                    float contrast_weight = contrast_map != NULL ? contrast_map[cidx] : 1.0f;
                    contrast_sum += contrast_weight;
                    contrast_count++;
                }
            }
            float mean_contrast_weight = contrast_count > 0
                ? (contrast_sum / (float)contrast_count)
                : 1.0f;

            int ring_total = 0;
            int ring_supported = 0;
            for (int gy = min_y - 1; gy <= max_y + 1; gy++) {
                if (gy < 0 || gy >= sg_h) continue;
                for (int gx = min_x - 1; gx <= max_x + 1; gx++) {
                    if (gx < 0 || gx >= sg_w) continue;
                    bool inside_bbox = gx >= min_x && gx <= max_x && gy >= min_y && gy <= max_y;
                    if (inside_bbox) continue;
                    ring_total++;
                    float ring_support = color_support_map[(size_t)gy * (size_t)sg_w + (size_t)gx];
                    if (ring_support >= fmaxf(0.30f, mean_support * 0.45f)) ring_supported++;
                }
            }
            float ring_fraction = ring_total > 0 ? ((float)ring_supported / (float)ring_total) : 0.0f;

            int support_radius = span <= 2.0f ? 2 : 3;
            int support_total = 0;
            int support_supported = 0;
            int near_total = 0;
            int near_supported = 0;
            for (int gy = min_y - support_radius; gy <= max_y + support_radius; gy++) {
                if (gy < 0 || gy >= sg_h) continue;
                for (int gx = min_x - support_radius; gx <= max_x + support_radius; gx++) {
                    if (gx < 0 || gx >= sg_w) continue;
                    bool inside_bbox = gx >= min_x && gx <= max_x && gy >= min_y && gy <= max_y;
                    if (inside_bbox) continue;
                    int dx_to_blob = 0;
                    if (gx < min_x) dx_to_blob = min_x - gx;
                    else if (gx > max_x) dx_to_blob = gx - max_x;
                    int dy_to_blob = 0;
                    if (gy < min_y) dy_to_blob = min_y - gy;
                    else if (gy > max_y) dy_to_blob = gy - max_y;
                    int chebyshev = dx_to_blob > dy_to_blob ? dx_to_blob : dy_to_blob;
                    if (chebyshev <= 0 || chebyshev > support_radius) continue;
                    support_total++;
                    float neighbor = color_support_map[(size_t)gy * (size_t)sg_w + (size_t)gx];
                    bool supported = neighbor >= fmaxf(0.20f, mean_support * 0.30f);
                    if (supported) support_supported++;
                    if (chebyshev <= 2) {
                        near_total++;
                        if (supported) near_supported++;
                    }
                }
            }
            float support_fraction = support_total > 0 ? ((float)support_supported / (float)support_total) : 0.0f;
            float support_mass = area > 0 ? ((float)support_supported / (float)area) : 0.0f;
            float near_fraction = near_total > 0 ? ((float)near_supported / (float)near_total) : 0.0f;

            if (area > max_blob_area) continue;
            if (ring_fraction >= 0.36f) continue;
            if (support_mass >= 2.80f && near_fraction >= 0.22f) continue;

            float apparent_size_scale = thermal_small_target_apparent_scale(cfg, span_px, frame_w, frame_h);
            float area_scale;
            if (area <= 1) area_scale = 0.60f;
            else if (area <= 4) area_scale = 1.08f;
            else if (area <= 9) area_scale = 1.16f;
            else if (area <= 16) area_scale = 0.72f;
            else area_scale = 0.24f;

            float span_scale;
            if (span_px <= 2.0f) span_scale = 0.55f;
            else if (span_px <= target_span_px * 0.85f) span_scale = 0.95f;
            else if (span_px <= target_span_px * 1.30f) span_scale = 1.12f;
            else if (span_px <= small_target_limit_px) span_scale = 0.62f;
            else span_scale = 0.20f;

            float fill_scale = clampf(0.50f + 0.75f * fill, 0.40f, 1.18f);
            float center_scale = clampf(0.60f + 0.95f * center_share, 0.48f, 1.20f);
            float isolation_score =
                0.45f * clampf((1.0f - ring_fraction) / 0.80f, 0.0f, 1.0f) +
                0.35f * clampf((1.8f - support_mass) / 1.8f, 0.0f, 1.0f) +
                0.20f * clampf((0.30f - support_fraction) / 0.30f, 0.0f, 1.0f);
            float isolation_scale = 0.42f + 0.78f * clampf(isolation_score, 0.0f, 1.0f);
            float strength_scale = clampf((peak_support + 0.65f * mean_support) / 3.8f, 0.22f, 1.28f);
            float contrast_scale = clampf(0.55f + 0.55f * mean_contrast_weight, 0.35f, 1.18f);
            float quality = area_scale * span_scale * fill_scale * center_scale * isolation_scale;
            quality *= apparent_size_scale;
            quality *= contrast_scale;
            quality = clampf(quality, 0.0f, 1.40f);
            if (quality <= 0.0f) continue;

            float final_score = peak_support * strength_scale * (0.62f + 0.58f * quality);
            float retention_rank = 0.0f;
            bool retention_rank_valid = false;
            if (sample_step <= 1) {
                float density_rank = clampf((fill - 0.25f) / 0.45f, 0.0f, 1.0f);
                float score_rank = clampf((final_score - 0.80f) / 2.20f, 0.0f, 1.0f);
                float area_pref = area <= 2 ? 0.42f : (area <= 9 ? 1.00f : 0.38f);
                float span_pref = span_px <= target_span_px * 0.60f ? 0.48f
                    : (span_px <= target_span_px * 1.25f ? 1.00f : 0.32f);
                retention_rank =
                    0.28f * score_rank +
                    0.22f * clampf(quality / 1.2f, 0.0f, 1.0f) +
                    0.18f * clampf(isolation_score, 0.0f, 1.0f) +
                    0.16f * density_rank +
                    0.10f * area_pref +
                    0.06f * span_pref;
                retention_rank = clampf(retention_rank, 0.0f, 1.0f);
                retention_rank_valid = true;
            }

            anomaly_color_blob_candidate_t candidate;
            memset(&candidate, 0, sizeof(candidate));
            candidate.candidate.sg_x = peak_x;
            candidate.candidate.sg_y = peak_y;
            candidate.candidate.pixel_x = roi_x0 + peak_x * sample_step;
            candidate.candidate.pixel_y = roi_y0 + peak_y * sample_step;
            candidate.candidate.proposal_score = peak_support;
            candidate.candidate.thermal_score = 0.0f;
            candidate.candidate.color_score = final_score;
            candidate.retention_rank = retention_rank;
            candidate.retention_rank_valid = retention_rank_valid;
            candidate.area = (float)area;
            candidate.span = span;
            candidate.fill = fill;
            candidate.center_share = center_share;
            candidate.quality = quality;
            candidate.peak_support = peak_support;
            candidate.mean_support = mean_support;
            candidate.isolation_score = isolation_score;
            candidate.ring_fraction = ring_fraction;
            candidate.support_mass = support_mass;
            candidate.min_x = min_x;
            candidate.min_y = min_y;
            candidate.max_x = max_x;
            candidate.max_y = max_y;
            insert_color_blob_candidate(out_candidates, out_count, &candidate);
        }
    }
}

static void extract_thermal_blob_candidates(
        const anomaly_config_t *cfg,
        const float *thermal_score_map,
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          frame_w,
        int          frame_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        uint8_t     *visited,
        int         *queue,
        float       *thermal_value_map,
        float       *candidate_seed_map,
        anomaly_thermal_blob_candidate_t *out_candidates,
        int         *out_count,
        anomaly_thermal_target_trace_t *target_trace) {
    if (out_count != NULL) *out_count = 0;
    if (thermal_score_map == NULL || thermal_value_map == NULL || candidate_seed_map == NULL ||
        visited == NULL || queue == NULL || out_candidates == NULL || out_count == NULL ||
        (bg_valid && thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0) {
        return;
    }

    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    memset(visited, 0, sg_count * sizeof(uint8_t));
    for (size_t i = 0; i < sg_count; i++) {
        candidate_seed_map[i] = -1.0f;
        float score = thermal_score_map[i];
        if (score <= 0.0f) {
            thermal_value_map[i] = -1.0f;
            continue;
        }
        if (bg_valid) {
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[i]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    i,
                    black_hot);
            thermal_value_map[i] = delta >= thermal_min_delta ? delta : -1.0f;
        } else {
            thermal_value_map[i] = score;
        }
    }
    if (target_trace != NULL) {
        memset(target_trace, 0, sizeof(*target_trace));
        target_trace->enabled = cfg != NULL && cfg->thermal_debug_target_enabled;
        target_trace->suppressor_sx = -1;
        target_trace->suppressor_sy = -1;
        target_trace->component_seed_x = -1;
        target_trace->component_seed_y = -1;
        target_trace->component_peak_x = -1;
        target_trace->component_peak_y = -1;
        target_trace->nms_conflict_rank = -1;
        target_trace->nms_conflict_sample_x = -1;
        target_trace->nms_conflict_sample_y = -1;
        target_trace->extracted_rank = -1;
        target_trace->winning_rank = -1;
        if (target_trace->enabled) {
            int px = clamp_i32((int)lroundf(cfg->thermal_debug_target_x_norm * (float)(frame_w - 1)), 0, frame_w - 1);
            int py = clamp_i32((int)lroundf(cfg->thermal_debug_target_y_norm * (float)(frame_h - 1)), 0, frame_h - 1);
            int local_x = px - roi_x0;
            int local_y = py - roi_y0;
            target_trace->target_px = px;
            target_trace->target_py = py;
            target_trace->target_x_norm = cfg->thermal_debug_target_x_norm;
            target_trace->target_y_norm = cfg->thermal_debug_target_y_norm;
            target_trace->inside_scan_zone =
                local_x >= 0 && local_x < sg_w * sample_step &&
                local_y >= 0 && local_y < sg_h * sample_step;
            if (target_trace->inside_scan_zone) {
                int sx = clamp_i32(local_x / sample_step, 0, sg_w - 1);
                int sy = clamp_i32(local_y / sample_step, 0, sg_h - 1);
                target_trace->valid = true;
                target_trace->target_sx = sx;
                target_trace->target_sy = sy;
                target_trace->target_idx = sy * sg_w + sx;
            }
        }
    }
    if (target_trace != NULL && target_trace->enabled && target_trace->valid) {
        size_t tidx = (size_t)target_trace->target_idx;
        target_trace->target_delta = thermal_value_map[tidx];
        target_trace->target_score = thermal_score_map[tidx];
        target_trace->hot_eligible = target_trace->target_delta > 0.0f;
        target_trace->stage = target_trace->hot_eligible
            ? ANOMALY_THERMAL_TARGET_STAGE_MERGED_INTO_COMPONENT
            : ANOMALY_THERMAL_TARGET_STAGE_NOT_HOT;
        target_trace->local_max = true;
        for (int ny = target_trace->target_sy - 1; ny <= target_trace->target_sy + 1; ny++) {
            if (ny < 0 || ny >= sg_h) continue;
            for (int nx = target_trace->target_sx - 1; nx <= target_trace->target_sx + 1; nx++) {
                if (nx < 0 || nx >= sg_w) continue;
                if (nx == target_trace->target_sx && ny == target_trace->target_sy) continue;
                size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                float ndelta = thermal_value_map[nidx];
                float nscore = thermal_score_map[nidx];
                if (ndelta > target_trace->target_delta ||
                    (ndelta == target_trace->target_delta && nscore > target_trace->target_score)) {
                    target_trace->local_max = false;
                    if (ndelta > target_trace->suppressor_delta ||
                        (ndelta == target_trace->suppressor_delta && nscore > target_trace->suppressor_score)) {
                        target_trace->suppressor_sx = nx;
                        target_trace->suppressor_sy = ny;
                        target_trace->suppressor_delta = ndelta;
                        target_trace->suppressor_score = nscore;
                    }
                }
            }
        }
        if (!target_trace->local_max && target_trace->hot_eligible) {
            target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_SUPPRESSED_BY_NEIGHBOR;
        }
    }

    float contrast_band = frame_contrast_mean + 1.25f * frame_contrast_std;
    if (contrast_band < 0.8f) contrast_band = 0.8f;

    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (visited[seed_idx] != 0u) continue;
            float seed_delta = thermal_value_map[seed_idx];
            if (seed_delta <= 0.0f) continue;
            if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                (int)seed_idx == target_trace->target_idx) {
                target_trace->started_component = true;
                target_trace->component_seed_x = sx;
                target_trace->component_seed_y = sy;
            }

            float local_band = contrast_band;
            float max_band = fmaxf(2.0f, seed_delta * 0.34f);
            if (local_band > max_band) local_band = max_band;

            int head = 0;
            int tail = 0;
            queue[tail++] = (int)seed_idx;
            visited[seed_idx] = 1u;

            int area = 0;
            int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
            double sum_delta = 0.0;
            double sum_score = 0.0;
            float peak_delta = seed_delta;
            float peak_score = thermal_score_map[seed_idx];
            int peak_x = sx;
            int peak_y = sy;

            while (head < tail) {
                int cur = queue[head++];
                int cx = cur % sg_w;
                int cy = cur / sg_w;
                float cur_delta = thermal_value_map[cur];
                float cur_score = thermal_score_map[cur];
                if (cur_delta <= 0.0f) continue;
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    cur == target_trace->target_idx) {
                    target_trace->component_seed_x = sx;
                    target_trace->component_seed_y = sy;
                }

                area++;
                sum_delta += (double)cur_delta;
                sum_score += (double)cur_score;
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;
                if (cur_score > peak_score || (cur_score == peak_score && cur_delta > peak_delta)) {
                    peak_score = cur_score;
                    peak_delta = cur_delta;
                    peak_x = cx;
                    peak_y = cy;
                }

                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        int nx = cx + ox;
                        int ny = cy + oy;
                        if (nx < 0 || nx >= sg_w || ny < 0 || ny >= sg_h) continue;
                        size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                        if (visited[nidx] != 0u) continue;
                        float ndelta = thermal_value_map[nidx];
                        if (ndelta <= 0.0f) continue;
                        float blob_mean = area > 0 ? (float)(sum_delta / (double)area) : seed_delta;
                        float blob_band = fmaxf(local_band * 1.10f, seed_delta * 0.28f);
                        if (fabsf(ndelta - cur_delta) > local_band &&
                            fabsf(ndelta - blob_mean) > blob_band) {
                            continue;
                        }
                        visited[nidx] = 1u;
                        queue[tail++] = (int)nidx;
                    }
                }
            }

            if (area <= 0) continue;

            int span_w = max_x - min_x + 1;
            int span_h = max_y - min_y + 1;
            int bbox_area = span_w * span_h;
            float fill = bbox_area > 0 ? ((float)area / (float)bbox_area) : 0.0f;
            float span = (float)(span_w > span_h ? span_w : span_h);
            float span_px = span * (float)(sample_step > 0 ? sample_step : 1);
            float small_target_limit_px = effective_thermal_small_target_span_px(cfg, frame_w, frame_h);
            float apparent_size_scale = thermal_small_target_apparent_scale(cfg, span_px, frame_w, frame_h);
            float mean_delta = (float)(sum_delta / (double)area);
            float center_share = sum_delta > 0.0 ? (peak_delta / (float)sum_delta) : 0.0f;
            float peakiness = (peak_delta - mean_delta) / fmaxf(local_band, 1.0f);

            float ring_threshold = fmaxf(thermal_min_delta, mean_delta * 0.55f);
            int ring_total = 0;
            int ring_hot = 0;
            int side_total[4] = {0, 0, 0, 0};
            int side_hot[4] = {0, 0, 0, 0};
            for (int gy = min_y - 1; gy <= max_y + 1; gy++) {
                if (gy < 0 || gy >= sg_h) continue;
                for (int gx = min_x - 1; gx <= max_x + 1; gx++) {
                    if (gx < 0 || gx >= sg_w) continue;
                    bool inside_bbox =
                        gx >= min_x && gx <= max_x &&
                        gy >= min_y && gy <= max_y;
                    if (inside_bbox) continue;
                    bool touches_ring =
                        gx >= min_x - 1 && gx <= max_x + 1 &&
                        gy >= min_y - 1 && gy <= max_y + 1;
                    if (!touches_ring) continue;
                    ring_total++;
                    size_t ridx = (size_t)gy * (size_t)sg_w + (size_t)gx;
                    float ring_delta = thermal_value_map[ridx];
                    bool ring_is_hot = ring_delta >= ring_threshold;
                    if (ring_is_hot) ring_hot++;

                    if (gx == min_x - 1) {
                        side_total[0]++;
                        if (ring_is_hot) side_hot[0]++;
                    }
                    if (gx == max_x + 1) {
                        side_total[1]++;
                        if (ring_is_hot) side_hot[1]++;
                    }
                    if (gy == min_y - 1) {
                        side_total[2]++;
                        if (ring_is_hot) side_hot[2]++;
                    }
                    if (gy == max_y + 1) {
                        side_total[3]++;
                        if (ring_is_hot) side_hot[3]++;
                    }
                }
            }

            float ring_hot_fraction = ring_total > 0 ? ((float)ring_hot / (float)ring_total) : 0.0f;
            float max_side_hot_fraction = 0.0f;
            for (int si = 0; si < 4; si++) {
                if (side_total[si] <= 0) continue;
                float side_fraction = (float)side_hot[si] / (float)side_total[si];
                if (side_fraction > max_side_hot_fraction) max_side_hot_fraction = side_fraction;
            }

            int support_radius = span <= 2.0f ? 2 : (span <= 4.0f ? 3 : 4);
            float support_threshold = fmaxf(thermal_min_delta, mean_delta * 0.42f);
            int support_total = 0;
            int support_hot = 0;
            int support_near_total = 0;
            int support_near_hot = 0;
            for (int gy = min_y - support_radius; gy <= max_y + support_radius; gy++) {
                if (gy < 0 || gy >= sg_h) continue;
                for (int gx = min_x - support_radius; gx <= max_x + support_radius; gx++) {
                    if (gx < 0 || gx >= sg_w) continue;
                    bool inside_bbox =
                        gx >= min_x && gx <= max_x &&
                        gy >= min_y && gy <= max_y;
                    if (inside_bbox) continue;

                    int dx_to_blob = 0;
                    if (gx < min_x) dx_to_blob = min_x - gx;
                    else if (gx > max_x) dx_to_blob = gx - max_x;
                    int dy_to_blob = 0;
                    if (gy < min_y) dy_to_blob = min_y - gy;
                    else if (gy > max_y) dy_to_blob = gy - max_y;
                    int chebyshev = dx_to_blob > dy_to_blob ? dx_to_blob : dy_to_blob;
                    if (chebyshev <= 0 || chebyshev > support_radius) continue;

                    support_total++;
                    size_t sidx = (size_t)gy * (size_t)sg_w + (size_t)gx;
                    float support_delta = thermal_value_map[sidx];
                    bool support_is_hot = support_delta >= support_threshold;
                    if (support_is_hot) support_hot++;
                    if (chebyshev <= 2) {
                        support_near_total++;
                        if (support_is_hot) support_near_hot++;
                    }
                }
            }

            float support_hot_fraction = support_total > 0 ? ((float)support_hot / (float)support_total) : 0.0f;
            float support_mass_ratio = area > 0 ? ((float)support_hot / (float)area) : 0.0f;
            float support_near_fraction = support_near_total > 0
                ? ((float)support_near_hot / (float)support_near_total)
                : 0.0f;

            if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                target_trace->component_peak_x = peak_x;
                target_trace->component_peak_y = peak_y;
                target_trace->component_area = (float)area;
                target_trace->component_span = span;
                target_trace->component_fill = fill;
                target_trace->component_peak_delta = peak_delta;
                target_trace->component_mean_delta = mean_delta;
            }

            if (bg_valid && area > ANOMALY_THERMAL_MAX_BLOB_AREA_SAMPLES) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_MAX_AREA;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid && ring_hot_fraction >= 0.22f) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_RING_HOT;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid && max_side_hot_fraction >= 0.60f) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_SIDE_HOT;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid &&
                support_mass_ratio >= 1.85f &&
                support_hot_fraction >= 0.14f &&
                support_near_fraction >= 0.18f) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_SUPPORT_MASS;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid &&
                support_mass_ratio >= 2.40f &&
                support_near_fraction >= 0.12f) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_SUPPORT_NEAR;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }

            float area_scale;
            if (area <= 1) area_scale = 1.05f;
            else if (area <= 4) area_scale = 1.18f;
            else if (area <= 8) area_scale = 0.92f;
            else if (area <= 12) area_scale = 0.48f;
            else area_scale = 0.14f;

            float span_scale;
            if (span_px <= 3.0f) span_scale = 1.18f;
            else if (span_px <= 6.0f) span_scale = 1.00f;
            else if (span_px <= 9.0f) span_scale = 0.60f;
            else if (span_px <= small_target_limit_px) span_scale = 0.28f;
            else span_scale = 0.10f;

            float fill_scale = clampf(0.55f + 0.70f * fill, 0.45f, 1.15f);
            float center_scale = clampf(0.72f + 0.62f * center_share, 0.55f, 1.18f);
            float peak_scale = clampf(0.85f + 0.18f * peakiness, 0.70f, 1.18f);
            float context_scale = 1.0f;
            if (bg_valid) {
                float context_penalty =
                    0.55f * clampf((support_mass_ratio - 0.55f) / 1.35f, 0.0f, 1.0f) +
                    0.25f * clampf((support_hot_fraction - 0.05f) / 0.20f, 0.0f, 1.0f) +
                    0.20f * clampf((support_near_fraction - 0.05f) / 0.25f, 0.0f, 1.0f);
                context_scale = 1.0f - 0.72f * clampf(context_penalty, 0.0f, 1.0f);
            }
            float quality = area_scale * span_scale * fill_scale * center_scale * peak_scale * context_scale;
            quality *= apparent_size_scale;
            if (span_px >= small_target_limit_px && area >= 8) {
                quality *= 0.18f;
            }
            quality = clampf(quality, 0.0f, 1.35f);
            if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                target_trace->component_quality = quality;
            }
            if (quality <= 0.0f) {
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_ZERO_QUALITY;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }

            anomaly_thermal_blob_candidate_t candidate;
            memset(&candidate, 0, sizeof(candidate));
            candidate.candidate.sg_x = peak_x;
            candidate.candidate.sg_y = peak_y;
            candidate.candidate.pixel_x = roi_x0 + peak_x * sample_step;
            candidate.candidate.pixel_y = roi_y0 + peak_y * sample_step;
            candidate.candidate.proposal_score = peak_score;
            candidate.candidate.thermal_score = peak_score;
            candidate.candidate.color_score = 0.0f;
            candidate.retention_rank = 0.0f;
            candidate.retention_rank_valid = false;
            candidate.area = (float)area;
            candidate.span = span;
            candidate.fill = fill;
            candidate.center_share = center_share;
            candidate.quality = quality;
            candidate.peak_delta = peak_delta;
            candidate.mean_delta = mean_delta;
            candidate.min_x = min_x;
            candidate.min_y = min_y;
            candidate.max_x = max_x;
            candidate.max_y = max_y;
            if (sample_step <= 1) {
                float area_pref = area <= 0 ? 0.0f
                    : (area <= 1 ? 1.00f
                    : (area <= 2 ? 0.97f
                    : (area <= 4 ? 0.90f
                    : (area <= 6 ? 0.72f
                    : (area <= 8 ? 0.50f : 0.18f)))));
                float span_pref = span_px <= 0.0f ? 0.0f
                    : (span_px <= 2.0f ? 1.00f
                    : (span_px <= 4.0f ? 0.96f
                    : (span_px <= 7.0f ? 0.84f
                    : (span_px <= small_target_limit_px ? 0.64f : 0.18f))));
                float quality_pref = clampf((quality - 0.30f) / 0.85f, 0.0f, 1.0f);
                float peak_pref = clampf(peak_score / 6.0f, 0.0f, 1.0f);
                float center_pref = clampf((center_share - 0.22f) / 0.42f, 0.0f, 1.0f);
                float retention_rank =
                    0.22f * area_pref +
                    0.20f * span_pref +
                    0.22f * quality_pref +
                    0.22f * peak_pref +
                    0.14f * center_pref;
                if (area <= 4 && span_px <= small_target_limit_px) {
                    retention_rank += 0.06f;
                }
                if (area >= 8 || span_px > small_target_limit_px * 1.15f) {
                    retention_rank -= 0.08f;
                }
                candidate.retention_rank = retention_rank;
                candidate.retention_rank_valid = true;
            }

            candidate_seed_map[(size_t)peak_y * (size_t)sg_w + (size_t)peak_x] =
                candidate.candidate.proposal_score;
            bool candidate_is_target =
                target_trace != NULL && target_trace->enabled && target_trace->valid &&
                target_trace->component_seed_x == sx && target_trace->component_seed_y == sy;
            insert_thermal_blob_candidate(
                    out_candidates,
                    out_count,
                    &candidate,
                    target_trace,
                    candidate_is_target);
            if (candidate_is_target) {
                target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_EXTRACTED;
            }
        }
    }
    if (target_trace != NULL &&
        target_trace->enabled &&
        target_trace->valid &&
        target_trace->stage == ANOMALY_THERMAL_TARGET_STAGE_EXTRACTED) {
        target_trace->extracted_rank = find_target_blob_rank(out_candidates, *out_count, target_trace);
    }
}

static void collect_motion_candidates(
        const float *thermal_map,
        const float *color_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        anomaly_motion_candidate_t *out_candidates,
        int         *out_count) {
    if (out_count != NULL) *out_count = 0;
    if (out_candidates == NULL || out_count == NULL || sg_w <= 0 || sg_h <= 0) return;

    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            float thermal_score = thermal_map != NULL ? thermal_map[idx] : -1.0f;
            if (thermal_score < 0.0f) thermal_score = 0.0f;
            float color_score = color_map != NULL ? color_map[idx] : 0.0f;
            if (color_score < 0.0f) color_score = 0.0f;
            float proposal_score = thermal_score;
            if (color_score > 0.0f) {
                proposal_score += thermal_score > 0.0f ? (0.60f * color_score)
                                                       : (0.85f * color_score);
            }
            if (proposal_score <= 0.0f) continue;

            bool is_peak = true;
            for (int ny = sy - 1; ny <= sy + 1 && is_peak; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
                    size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                    float nthermal = thermal_map != NULL ? thermal_map[nidx] : -1.0f;
                    if (nthermal < 0.0f) nthermal = 0.0f;
                    float ncolor = color_map != NULL ? color_map[nidx] : 0.0f;
                    if (ncolor < 0.0f) ncolor = 0.0f;
                    float neighbor_score = nthermal;
                    if (ncolor > 0.0f) {
                        neighbor_score += nthermal > 0.0f ? (0.60f * ncolor)
                                                           : (0.85f * ncolor);
                    }
                    if (neighbor_score > proposal_score) {
                        is_peak = false;
                        break;
                    }
                }
            }
            if (!is_peak) continue;

            int insert_at = *out_count;
            bool rejected = false;
            for (int i = 0; i < *out_count; i++) {
                int ddx = abs(out_candidates[i].sg_x - sx);
                int ddy = abs(out_candidates[i].sg_y - sy);
                if (ddx <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS &&
                    ddy <= ANOMALY_MOTION_CANDIDATE_NMS_RADIUS) {
                    if (proposal_score > out_candidates[i].proposal_score) {
                        out_candidates[i].sg_x = sx;
                        out_candidates[i].sg_y = sy;
                        out_candidates[i].pixel_x = roi_x0 + sx * sample_step;
                        out_candidates[i].pixel_y = roi_y0 + sy * sample_step;
                        out_candidates[i].proposal_score = proposal_score;
                        out_candidates[i].thermal_score = thermal_score;
                        out_candidates[i].color_score = color_score;
                    }
                    rejected = true;
                    break;
                }
                if (proposal_score > out_candidates[i].proposal_score) {
                    insert_at = i;
                    break;
                }
            }
            if (rejected) continue;
            if (insert_at >= ANOMALY_MAX_MOTION_CANDIDATES) continue;

            int move_limit = *out_count < ANOMALY_MAX_MOTION_CANDIDATES
                ? *out_count
                : (ANOMALY_MAX_MOTION_CANDIDATES - 1);
            for (int i = move_limit; i > insert_at; i--) {
                out_candidates[i] = out_candidates[i - 1];
            }
            if (*out_count < ANOMALY_MAX_MOTION_CANDIDATES) (*out_count)++;

            out_candidates[insert_at].sg_x = sx;
            out_candidates[insert_at].sg_y = sy;
            out_candidates[insert_at].pixel_x = roi_x0 + sx * sample_step;
            out_candidates[insert_at].pixel_y = roi_y0 + sy * sample_step;
            out_candidates[insert_at].proposal_score = proposal_score;
            out_candidates[insert_at].thermal_score = thermal_score;
            out_candidates[insert_at].color_score = color_score;
        }
    }
}

static int normalize_registration_mode(const anomaly_config_t *cfg) {
    if (cfg != NULL && cfg->registration_mode == ANOMALY_REGISTRATION_AFFINE) {
        return ANOMALY_REGISTRATION_AFFINE;
    }
    return ANOMALY_REGISTRATION_GMV;
}

static anomaly_registration_model_t make_registration_model(
        int mode,
        int sample_step,
        int motion_step) {
    anomaly_registration_model_t model;
    memset(&model, 0, sizeof(model));
    model.mode = mode;
    model.sample_step = sample_step;
    model.motion_step = motion_step;
    model.affine[0] = 1.0f;
    model.affine[4] = 1.0f;
    model.similarity.a = 1.0f;
    model.similarity.valid = false;
    model.invalid_reason = ANOMALY_REG_INVALID_REASON_NONE;
    return model;
}

static inline bool registration_model_valid(const anomaly_registration_model_t *model) {
    return model != NULL && model->similarity.valid;
}

static inline float registration_model_scale(const anomaly_registration_model_t *model) {
    if (model == NULL) return 1.0f;
    return sqrtf(model->similarity.a * model->similarity.a +
                 model->similarity.b * model->similarity.b);
}

static void cache_registration_model(
        anomaly_state_t                     *state,
        const anomaly_registration_model_t  *model,
        anomaly_registration_health_t        registration_health,
        anomaly_rescan_mode_t                rescan_mode) {
    if (state == NULL || model == NULL || !registration_model_valid(model)) {
        if (state != NULL) {
            state->cached_registration_valid = false;
            state->cached_registration_reuse_budget = 0;
        }
        return;
    }
    state->cached_registration_valid = true;
    state->cached_registration_mode = model->mode;
    state->cached_registration_sample_step = model->sample_step;
    state->cached_registration_motion_step = model->motion_step;
    state->cached_registration_anchor_count = model->anchor_count;
    state->cached_registration_tracked_match_count = model->tracked_match_count;
    state->cached_registration_invalid_reason = model->invalid_reason;
    state->cached_registration_health = registration_health;
    state->cached_registration_last_rescan_mode = rescan_mode;
    memcpy(state->cached_registration_affine, model->affine, sizeof(model->affine));
    state->cached_registration_similarity_a = model->similarity.a;
    state->cached_registration_similarity_b = model->similarity.b;
    state->cached_registration_similarity_tx = model->similarity.tx;
    state->cached_registration_similarity_ty = model->similarity.ty;
    state->cached_registration_similarity_mean_residual = model->similarity.mean_residual;
    state->cached_registration_fit_det = model->fit_det;
    state->cached_registration_fit_min_scale = model->fit_min_scale;
    state->cached_registration_fit_max_scale = model->fit_max_scale;
    state->cached_registration_fit_anchor_residual_std = model->fit_anchor_residual_std;
    state->cached_registration_fit_anchor_residual_max = model->fit_anchor_residual_max;
    state->cached_registration_fit_motion_dx_std = model->fit_motion_dx_std;
    state->cached_registration_fit_motion_dy_std = model->fit_motion_dy_std;
    state->cached_registration_fit_quadrant_residual_spread = model->fit_quadrant_residual_spread;

    bool stable_affine =
        model->mode == ANOMALY_REGISTRATION_AFFINE &&
        registration_health == ANOMALY_REG_HEALTH_HEALTHY &&
        model->invalid_reason == ANOMALY_REG_INVALID_REASON_NONE &&
        !model->scene_discontinuity &&
        model->anchor_count >= 12 &&
        model->tracked_match_count >= 48 &&
        model->similarity.mean_residual <= 0.005f &&
        model->fit_anchor_residual_max <= 0.040f &&
        model->fit_motion_dx_std <= 0.010f &&
        model->fit_motion_dy_std <= 0.010f &&
        model->fit_quadrant_residual_spread <= 0.010f &&
        model->fit_det > 0.985f &&
        model->fit_det < 1.015f &&
        model->fit_min_scale >= 0.985f &&
        model->fit_max_scale <= 1.015f &&
        (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY ||
         rescan_mode == ANOMALY_RESCAN_MODE_PARTIAL);
    if (!stable_affine) {
        state->cached_registration_reuse_budget = 0;
    } else if (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY &&
               model->tracked_match_count >= 56 &&
               model->similarity.mean_residual <= 0.004f &&
               model->fit_anchor_residual_max <= 0.020f &&
               model->fit_motion_dx_std <= 0.006f &&
               model->fit_motion_dy_std <= 0.006f &&
               model->fit_quadrant_residual_spread <= 0.004f &&
               model->fit_det > 0.992f &&
               model->fit_det < 1.008f &&
               model->fit_min_scale >= 0.992f &&
               model->fit_max_scale <= 1.008f) {
        state->cached_registration_reuse_budget = 2;
    } else {
        state->cached_registration_reuse_budget = 1;
    }
}

static bool try_load_cached_registration_model(
        anomaly_registration_model_t *model_out,
        anomaly_state_t              *state,
        int                           mode,
        int                           motion_sample_step,
        int                           motion_step,
        int                           motion_w,
        int                           motion_h) {
    if (model_out == NULL || state == NULL ||
        mode != ANOMALY_REGISTRATION_AFFINE ||
        !state->cached_registration_valid ||
        state->cached_registration_reuse_budget <= 0 ||
        state->cached_registration_mode != ANOMALY_REGISTRATION_AFFINE ||
        state->cached_registration_health != ANOMALY_REG_HEALTH_HEALTHY ||
        state->cached_registration_invalid_reason != ANOMALY_REG_INVALID_REASON_NONE ||
        state->cached_registration_sample_step != motion_sample_step ||
        state->cached_registration_motion_step != motion_step ||
        state->prev_registration_luma == NULL ||
        state->prev_registration_luma_width != motion_w ||
        state->prev_registration_luma_height != motion_h) {
        return false;
    }

    anomaly_registration_model_t model = make_registration_model(
        ANOMALY_REGISTRATION_AFFINE,
        motion_sample_step,
        motion_step);
    memcpy(model.affine, state->cached_registration_affine, sizeof(model.affine));
    model.similarity.a = state->cached_registration_similarity_a;
    model.similarity.b = state->cached_registration_similarity_b;
    model.similarity.tx = state->cached_registration_similarity_tx;
    model.similarity.ty = state->cached_registration_similarity_ty;
    model.similarity.mean_residual = state->cached_registration_similarity_mean_residual;
    model.similarity.valid = true;
    model.debug_valid = true;
    model.anchor_count = state->cached_registration_anchor_count;
    model.tracked_match_count = state->cached_registration_tracked_match_count;
    model.invalid_reason =
        (anomaly_registration_invalid_reason_t)state->cached_registration_invalid_reason;
    model.fit_det = state->cached_registration_fit_det;
    model.fit_min_scale = state->cached_registration_fit_min_scale;
    model.fit_max_scale = state->cached_registration_fit_max_scale;
    model.fit_anchor_residual_std = state->cached_registration_fit_anchor_residual_std;
    model.fit_anchor_residual_max = state->cached_registration_fit_anchor_residual_max;
    model.fit_motion_dx_std = state->cached_registration_fit_motion_dx_std;
    model.fit_motion_dy_std = state->cached_registration_fit_motion_dy_std;
    model.fit_quadrant_residual_spread = state->cached_registration_fit_quadrant_residual_spread;
    *model_out = model;
    state->cached_registration_reuse_budget -= 1;
    return true;
}

static inline void registration_apply_point(
        const anomaly_registration_model_t *model,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    if (out_x == NULL || out_y == NULL) return;
    if (model == NULL) {
        *out_x = x;
        *out_y = y;
        return;
    }
    *out_x = model->affine[0] * x + model->affine[1] * y + model->affine[2];
    *out_y = model->affine[3] * x + model->affine[4] * y + model->affine[5];
}

static float registration_max_corner_displacement(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height) {
    if (model == NULL || width <= 1 || height <= 1) return 0.0f;
    static const float points[5][2] = {
        {0.0f, 0.0f},
        {1.0f, 0.0f},
        {0.0f, 1.0f},
        {1.0f, 1.0f},
        {0.5f, 0.5f},
    };
    float max_disp = 0.0f;
    for (int i = 0; i < 5; i++) {
        float x1 = 0.0f;
        float y1 = 0.0f;
        registration_apply_point(model, points[i][0], points[i][1], &x1, &y1);
        float dx = x1 - points[i][0];
        float dy = y1 - points[i][1];
        float disp = sqrtf(dx * dx + dy * dy);
        if (disp > max_disp) max_disp = disp;
    }
    return max_disp;
}

static bool registration_motion_exceeds_search(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height,
        float                               fraction) {
    if (model == NULL || width <= 1 || height <= 1 || model->motion_step <= 0) return false;
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float search_dx = ((float)(ANOMALY_GMV_SEARCH_RADIUS * model->motion_step)) / fw;
    float search_dy = ((float)(ANOMALY_GMV_SEARCH_RADIUS * model->motion_step)) / fh;
    float search_limit = sqrtf(search_dx * search_dx + search_dy * search_dy) * fraction;
    return registration_max_corner_displacement(model, width, height) > search_limit;
}

static inline bool registration_invert_point(
        const anomaly_registration_model_t *model,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    if (model == NULL || out_x == NULL || out_y == NULL) return false;
    float det = model->affine[0] * model->affine[4] - model->affine[1] * model->affine[3];
    if (fabsf(det) < 1e-6f) return false;
    float dx = x - model->affine[2];
    float dy = y - model->affine[5];
    *out_x = ( model->affine[4] * dx - model->affine[1] * dy) / det;
    *out_y = (-model->affine[3] * dx + model->affine[0] * dy) / det;
    return true;
}

typedef struct {
    float m00;
    float m01;
    float m02;
    float m10;
    float m11;
    float m12;
    bool valid;
} anomaly_inverse_affine_t;

static inline anomaly_inverse_affine_t registration_inverse_affine(
        const anomaly_registration_model_t *model) {
    anomaly_inverse_affine_t inv;
    memset(&inv, 0, sizeof(inv));
    if (model == NULL) return inv;
    float det = model->affine[0] * model->affine[4] - model->affine[1] * model->affine[3];
    if (fabsf(det) < 1e-6f) return inv;
    float inv_det = 1.0f / det;
    inv.m00 =  model->affine[4] * inv_det;
    inv.m01 = -model->affine[1] * inv_det;
    inv.m02 = (model->affine[1] * model->affine[5] - model->affine[4] * model->affine[2]) * inv_det;
    inv.m10 = -model->affine[3] * inv_det;
    inv.m11 =  model->affine[0] * inv_det;
    inv.m12 = (model->affine[3] * model->affine[2] - model->affine[0] * model->affine[5]) * inv_det;
    inv.valid = true;
    return inv;
}

static inline bool registration_invert_point_fast(
        const anomaly_inverse_affine_t *inv,
        float                           x,
        float                           y,
        float                          *out_x,
        float                          *out_y) {
    if (inv == NULL || !inv->valid || out_x == NULL || out_y == NULL) return false;
    *out_x = inv->m00 * x + inv->m01 * y + inv->m02;
    *out_y = inv->m10 * x + inv->m11 * y + inv->m12;
    return true;
}

static bool project_motion_cell(
        const anomaly_registration_model_t *model,
        int             width,
        int             height,
        int             motion_step,
        int             motion_w,
        int             motion_h,
        int             mx,
        int             my,
        int            *px_idx_out,
        int            *py_idx_out) {
    if (model == NULL || width <= 1 || height <= 1 || motion_step <= 0 ||
        motion_w <= 0 || motion_h <= 0) {
        return false;
    }
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float cx_n = (float)(mx * motion_step) / fw;
    float cy_n = (float)(my * motion_step) / fh;
    float px_n = 0.0f;
    float py_n = 0.0f;
    registration_apply_point(model, cx_n, cy_n, &px_n, &py_n);
    int px_idx = (int)(px_n * fw / (float)motion_step + 0.5f);
    int py_idx = (int)(py_n * fh / (float)motion_step + 0.5f);
    if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) return false;
    if (px_idx_out != NULL) *px_idx_out = px_idx;
    if (py_idx_out != NULL) *py_idx_out = py_idx;
    return true;
}

static void stamp_motion_support(
        float *saliency_motion_map,
        float *saliency_registration_map,
        int    sg_w,
        int    sg_h,
        int    sg_x,
        int    sg_y,
        float  support,
        float  registration_scale) {
    if (saliency_motion_map == NULL || sg_w <= 0 || sg_h <= 0 || support <= 0.0f) return;
    for (int oy = -1; oy <= 1; oy++) {
        int sy = sg_y + oy;
        if (sy < 0 || sy >= sg_h) continue;
        for (int ox = -1; ox <= 1; ox++) {
            int sx = sg_x + ox;
            if (sx < 0 || sx >= sg_w) continue;
            float scale = (ox == 0 && oy == 0) ? 1.0f : 0.55f;
            float stamped = support * scale;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (stamped > saliency_motion_map[idx]) saliency_motion_map[idx] = stamped;
            if (saliency_registration_map != NULL && registration_scale < saliency_registration_map[idx]) {
                saliency_registration_map[idx] = registration_scale;
            }
        }
    }
}

static bool estimate_local_motion_region(
        const float   *motion_dx_map,
        const float   *motion_dy_map,
        const uint8_t *motion_valid_map,
        int            motion_w,
        int            motion_h,
        int            cx,
        int            cy,
        int            radius,
        float         *out_dx,
        float         *out_dy,
        float         *out_jitter) {
    if (motion_dx_map == NULL || motion_dy_map == NULL || motion_valid_map == NULL ||
        out_dx == NULL || out_dy == NULL || out_jitter == NULL ||
        motion_w <= 0 || motion_h <= 0) {
        return false;
    }

    int rx0 = clamp_i32(cx - radius, 0, motion_w - 1);
    int rx1 = clamp_i32(cx + radius, 0, motion_w - 1);
    int ry0 = clamp_i32(cy - radius, 0, motion_h - 1);
    int ry1 = clamp_i32(cy + radius, 0, motion_h - 1);
    int max_samples = (rx1 - rx0 + 1) * (ry1 - ry0 + 1);
    if (max_samples <= 0) return false;

    float *dx_samples = (float *)malloc((size_t)max_samples * sizeof(float));
    float *dy_samples = (float *)malloc((size_t)max_samples * sizeof(float));
    if (dx_samples == NULL || dy_samples == NULL) {
        free(dx_samples);
        free(dy_samples);
        return false;
    }

    int count = 0;
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            size_t idx = (size_t)y * (size_t)motion_w + (size_t)x;
            if (motion_valid_map[idx] == 0) continue;
            dx_samples[count] = motion_dx_map[idx];
            dy_samples[count] = motion_dy_map[idx];
            count++;
        }
    }
    if (count < ANOMALY_LOCAL_MOTION_MIN_SAMPLES) {
        free(dx_samples);
        free(dy_samples);
        return false;
    }

    qsort(dx_samples, (size_t)count, sizeof(float), compare_float_qsort);
    qsort(dy_samples, (size_t)count, sizeof(float), compare_float_qsort);
    float median_dx = dx_samples[count / 2];
    float median_dy = dy_samples[count / 2];

    float sum_dx = 0.0f;
    float sum_dy = 0.0f;
    float dev_sum = 0.0f;
    int inlier_count = 0;
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            size_t idx = (size_t)y * (size_t)motion_w + (size_t)x;
            if (motion_valid_map[idx] == 0) continue;
            float ddx = motion_dx_map[idx] - median_dx;
            float ddy = motion_dy_map[idx] - median_dy;
            float dist = sqrtf(ddx * ddx + ddy * ddy);
            if (dist > ANOMALY_LOCAL_MOTION_INLIER_RADIUS_CELLS) continue;
            sum_dx += motion_dx_map[idx];
            sum_dy += motion_dy_map[idx];
            dev_sum += dist;
            inlier_count++;
        }
    }

    free(dx_samples);
    free(dy_samples);

    if (inlier_count < ANOMALY_LOCAL_MOTION_MIN_SAMPLES / 2) return false;
    *out_dx = sum_dx / (float)inlier_count;
    *out_dy = sum_dy / (float)inlier_count;
    *out_jitter = dev_sum / (float)inlier_count;
    return true;
}

static void sample_local_motion_field(
        const float *field_dx,
        const float *field_dy,
        const float *field_jitter,
        const uint8_t *field_valid,
        int field_w,
        int field_h,
        int region_stride,
        float cell_x,
        float cell_y,
        float *out_dx,
        float *out_dy,
        float *out_jitter,
        float *out_confidence) {
    if (out_dx == NULL || out_dy == NULL || out_jitter == NULL || out_confidence == NULL) return;
    *out_dx = 0.0f;
    *out_dy = 0.0f;
    *out_jitter = 0.0f;
    *out_confidence = 0.0f;
    if (field_dx == NULL || field_dy == NULL || field_jitter == NULL || field_valid == NULL ||
        field_w <= 0 || field_h <= 0 || region_stride <= 0) {
        return;
    }

    float gx = cell_x / (float)region_stride;
    float gy = cell_y / (float)region_stride;
    int ix = (int)floorf(gx);
    int iy = (int)floorf(gy);
    float fx = gx - (float)ix;
    float fy = gy - (float)iy;
    float sum_w = 0.0f;
    float sum_dx = 0.0f;
    float sum_dy = 0.0f;
    float sum_jitter = 0.0f;

    for (int oy = 0; oy <= 1; oy++) {
        for (int ox = 0; ox <= 1; ox++) {
            int sx = ix + ox;
            int sy = iy + oy;
            if (sx < 0 || sx >= field_w || sy < 0 || sy >= field_h) continue;
            size_t sidx = (size_t)sy * (size_t)field_w + (size_t)sx;
            if (field_valid[sidx] == 0) continue;
            float wx = ox == 0 ? (1.0f - fx) : fx;
            float wy = oy == 0 ? (1.0f - fy) : fy;
            float w = wx * wy;
            sum_w += w;
            sum_dx += field_dx[sidx] * w;
            sum_dy += field_dy[sidx] * w;
            sum_jitter += field_jitter[sidx] * w;
        }
    }

    if (sum_w <= 1e-4f) return;
    *out_dx = sum_dx / sum_w;
    *out_dy = sum_dy / sum_w;
    *out_jitter = sum_jitter / sum_w;
    *out_confidence = sum_w;
}

static bool find_residual_motion_displacement(
        const uint8_t *curr_luma,
        const uint8_t *prev_luma,
        int            motion_w,
        int            motion_h,
        int            mx,
        int            my,
        int            pred_x,
        int            pred_y,
        int            patch_half,
        int            search_radius,
        int           *best_dx_out,
        int           *best_dy_out,
        int           *best_sad_out) {
    if (curr_luma == NULL || prev_luma == NULL) return false;
    if (mx < patch_half || mx >= motion_w - patch_half ||
        my < patch_half || my >= motion_h - patch_half) {
        return false;
    }

    long best_sad = 0x7FFFFFFFL;
    long second_best_sad = 0x7FFFFFFFL;
    int best_dx = 0;
    int best_dy = 0;
    int best_dist2 = 0x7FFFFFFF;
    bool found = false;

    for (int dy = -search_radius; dy <= search_radius; dy++) {
        for (int dx = -search_radius; dx <= search_radius; dx++) {
            int cx = pred_x + dx;
            int cy = pred_y + dy;
            if (cx < patch_half || cx >= motion_w - patch_half ||
                cy < patch_half || cy >= motion_h - patch_half) {
                continue;
            }
            long sad = 0;
            for (int ky = -patch_half; ky <= patch_half; ky++) {
                for (int kx = -patch_half; kx <= patch_half; kx++) {
                    int curr_v = curr_luma[(my + ky) * motion_w + (mx + kx)];
                    int prev_v = prev_luma[(cy + ky) * motion_w + (cx + kx)];
                    int d = curr_v - prev_v;
                    sad += d < 0 ? -d : d;
                }
            }
            int dist2 = dx * dx + dy * dy;
            if (sad < best_sad || (sad == best_sad && dist2 < best_dist2)) {
                second_best_sad = best_sad;
                best_sad = sad;
                best_dx = dx;
                best_dy = dy;
                best_dist2 = dist2;
                found = true;
            } else if (sad < second_best_sad) {
                second_best_sad = sad;
            }
        }
    }

    if (!found) return false;
    if (second_best_sad < 0x7FFFFFFFL) {
        long sad_margin = second_best_sad - best_sad;
        if (sad_margin < 12) return false;
        if ((double)second_best_sad < (double)best_sad * 1.08) return false;
    }
    if (abs(best_dx) >= search_radius || abs(best_dy) >= search_radius) {
        return false;
    }
    if (best_dx_out != NULL) *best_dx_out = best_dx;
    if (best_dy_out != NULL) *best_dy_out = best_dy;
    if (best_sad_out != NULL) *best_sad_out = best_sad >= 0x7FFFFFFFL ? -1 : (int)best_sad;
    return true;
}

static bool bilinear_sample_u8(
        const uint8_t *grid,
        int            w,
        int            h,
        float          x,
        float          y,
        float         *out_value) {
    if (grid == NULL || out_value == NULL || w <= 1 || h <= 1) return false;
    if (x < 0.0f || y < 0.0f || x > (float)(w - 1) || y > (float)(h - 1)) return false;
    int x0 = (int)floorf(x);
    int y0 = (int)floorf(y);
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    if (x1 >= w) x1 = w - 1;
    if (y1 >= h) y1 = h - 1;
    float fx = x - (float)x0;
    float fy = y - (float)y0;
    float p00 = (float)grid[y0 * w + x0];
    float p10 = (float)grid[y0 * w + x1];
    float p01 = (float)grid[y1 * w + x0];
    float p11 = (float)grid[y1 * w + x1];
    *out_value =
        p00 * (1.0f - fx) * (1.0f - fy) +
        p10 * fx * (1.0f - fy) +
        p01 * (1.0f - fx) * fy +
        p11 * fx * fy;
    return true;
}

static float registration_residual_standout_score(
        const uint8_t      *curr_luma,
        const uint8_t      *prev_luma,
        int                 motion_w,
        int                 motion_h,
        int                 motion_step,
        int                 width,
        int                 height,
        const anomaly_registration_model_t *model,
        int                 mx,
        int                 my) {
    if (curr_luma == NULL || prev_luma == NULL || motion_w <= 1 || motion_h <= 1 ||
        width <= 1 || height <= 1 || !registration_model_valid(model)) {
        return 0.0f;
    }

    const int center_half = ANOMALY_REG_RESIDUAL_CENTER_HALF;
    const int ring_half = ANOMALY_REG_RESIDUAL_RING_HALF;
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float center_sum = 0.0f;
    int center_count = 0;
    float ring_sum = 0.0f;
    float ring_sum2 = 0.0f;
    int ring_count = 0;

    for (int oy = -ring_half; oy <= ring_half; oy++) {
        for (int ox = -ring_half; ox <= ring_half; ox++) {
            int sx = mx + ox;
            int sy = my + oy;
            if (sx < 0 || sx >= motion_w || sy < 0 || sy >= motion_h) continue;

            float x_norm = ((float)(sx * motion_step)) / fw;
            float y_norm = ((float)(sy * motion_step)) / fh;
            float prev_x_norm = 0.0f;
            float prev_y_norm = 0.0f;
            registration_apply_point(model, x_norm, y_norm, &prev_x_norm, &prev_y_norm);
            float prev_x = (prev_x_norm * fw) / (float)motion_step;
            float prev_y = (prev_y_norm * fh) / (float)motion_step;
            float prev_sample = 0.0f;
            if (!bilinear_sample_u8(prev_luma, motion_w, motion_h, prev_x, prev_y, &prev_sample)) continue;

            float resid = fabsf((float)curr_luma[sy * motion_w + sx] - prev_sample);
            if (abs(ox) <= center_half && abs(oy) <= center_half) {
                center_sum += resid;
                center_count++;
            } else {
                ring_sum += resid;
                ring_sum2 += resid * resid;
                ring_count++;
            }
        }
    }

    if (center_count <= 0 || ring_count < 8) return 0.0f;
    float center_mean = center_sum / (float)center_count;
    float ring_mean = ring_sum / (float)ring_count;
    float ring_var = ring_sum2 / (float)ring_count - ring_mean * ring_mean;
    if (ring_var < 0.0f) ring_var = 0.0f;
    float ring_std = sqrtf(ring_var);
    if (ring_std < 1.0f) ring_std = 1.0f;
    return (center_mean - ring_mean) / ring_std;
}

static int gmv_feature_score(const uint8_t *luma, int w, int h, int x, int y) {
    if (luma == NULL || w <= 0 || h <= 0) return 0;
    if (x <= 0 || x >= w - 1 || y <= 0 || y >= h - 1) return 0;
    int c = luma[y * w + x];
    int score = 0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int v = luma[(y + dy) * w + (x + dx)];
            int d = c - v;
            score += d < 0 ? -d : d;
        }
    }
    return score;
}

typedef struct {
    float x0;
    float y0;
    float x1;
    float y1;
    float err;
} affine_match_t;

static bool fit_affine_least_squares(
        const affine_match_t *matches,
        int                   count,
        float                 out_affine[6]) {
    if (matches == NULL || out_affine == NULL || count < 3) return false;
    float ata[6][6];
    float atb[6];
    memset(ata, 0, sizeof(ata));
    memset(atb, 0, sizeof(atb));
    for (int i = 0; i < count; i++) {
        const affine_match_t *m = &matches[i];
        float row0[6] = {m->x0, m->y0, 1.0f, 0.0f, 0.0f, 0.0f};
        float row1[6] = {0.0f, 0.0f, 0.0f, m->x0, m->y0, 1.0f};
        for (int r = 0; r < 6; r++) {
            atb[r] += row0[r] * m->x1 + row1[r] * m->y1;
            for (int c = 0; c < 6; c++) {
                ata[r][c] += row0[r] * row0[c] + row1[r] * row1[c];
            }
        }
    }
    return solve_6x6(ata, atb, out_affine);
}

static inline void affine_apply(
        const float affine[6],
        float x,
        float y,
        float *out_x,
        float *out_y) {
    if (out_x == NULL || out_y == NULL) return;
    *out_x = affine[0] * x + affine[1] * y + affine[2];
    *out_y = affine[3] * x + affine[4] * y + affine[5];
}

static bool summarize_affine_match_residuals(
        const float          affine[6],
        const affine_match_t *matches,
        int                   match_count,
        float                 inlier_thresh,
        int                  *inlier_count_out,
        float                *mean_residual_out,
        float                *max_residual_out) {
    if (inlier_count_out != NULL) *inlier_count_out = 0;
    if (mean_residual_out != NULL) *mean_residual_out = 0.0f;
    if (max_residual_out != NULL) *max_residual_out = 0.0f;
    if (affine == NULL || matches == NULL || match_count <= 0 || inlier_thresh <= 0.0f) {
        return false;
    }

    int inlier_count = 0;
    float residual_sum = 0.0f;
    float max_residual = 0.0f;
    for (int mi = 0; mi < match_count; mi++) {
        float px = 0.0f;
        float py = 0.0f;
        affine_apply(affine, matches[mi].x0, matches[mi].y0, &px, &py);
        float dx = px - matches[mi].x1;
        float dy = py - matches[mi].y1;
        float resid = sqrtf(dx * dx + dy * dy);
        if (resid > max_residual) max_residual = resid;
        if (resid <= inlier_thresh) {
            residual_sum += resid;
            inlier_count++;
        }
    }

    if (inlier_count_out != NULL) *inlier_count_out = inlier_count;
    if (mean_residual_out != NULL) {
        *mean_residual_out = inlier_count > 0 ? (residual_sum / (float)inlier_count) : max_residual;
    }
    if (max_residual_out != NULL) *max_residual_out = max_residual;
    return true;
}

static void compute_registration_consistency_stats(
        const anomaly_registration_model_t *model,
        const float                        *src_x,
        const float                        *src_y,
        const float                        *dst_x,
        const float                        *dst_y,
        int                                 count,
        float                              *residual_std_out,
        float                              *residual_max_out,
        float                              *motion_dx_std_out,
        float                              *motion_dy_std_out,
        float                              *quadrant_residual_spread_out) {
    if (residual_std_out != NULL) *residual_std_out = 0.0f;
    if (residual_max_out != NULL) *residual_max_out = 0.0f;
    if (motion_dx_std_out != NULL) *motion_dx_std_out = 0.0f;
    if (motion_dy_std_out != NULL) *motion_dy_std_out = 0.0f;
    if (quadrant_residual_spread_out != NULL) *quadrant_residual_spread_out = 0.0f;
    if (model == NULL || src_x == NULL || src_y == NULL || dst_x == NULL || dst_y == NULL || count <= 0) {
        return;
    }

    double sum_residual = 0.0;
    double sum_residual2 = 0.0;
    double max_residual = 0.0;
    double sum_dx = 0.0;
    double sum_dx2 = 0.0;
    double sum_dy = 0.0;
    double sum_dy2 = 0.0;
    double quad_sum[4] = {0.0, 0.0, 0.0, 0.0};
    int quad_count[4] = {0, 0, 0, 0};

    for (int i = 0; i < count; i++) {
        float px =
            model->affine[0] * src_x[i] +
            model->affine[1] * src_y[i] +
            model->affine[2];
        float py =
            model->affine[3] * src_x[i] +
            model->affine[4] * src_y[i] +
            model->affine[5];
        double ex = (double)px - (double)dst_x[i];
        double ey = (double)py - (double)dst_y[i];
        double residual = sqrt(ex * ex + ey * ey);
        sum_residual += residual;
        sum_residual2 += residual * residual;
        if (residual > max_residual) max_residual = residual;

        double dx = (double)dst_x[i] - (double)src_x[i];
        double dy = (double)dst_y[i] - (double)src_y[i];
        sum_dx += dx;
        sum_dx2 += dx * dx;
        sum_dy += dy;
        sum_dy2 += dy * dy;

        int qx = src_x[i] >= 0.5f ? 1 : 0;
        int qy = src_y[i] >= 0.5f ? 1 : 0;
        int q = qy * 2 + qx;
        quad_sum[q] += residual;
        quad_count[q] += 1;
    }

    double mean_residual = sum_residual / (double)count;
    double var_residual = fmax(sum_residual2 / (double)count - mean_residual * mean_residual, 0.0);
    double mean_dx = sum_dx / (double)count;
    double mean_dy = sum_dy / (double)count;
    double var_dx = fmax(sum_dx2 / (double)count - mean_dx * mean_dx, 0.0);
    double var_dy = fmax(sum_dy2 / (double)count - mean_dy * mean_dy, 0.0);
    double quad_min = 0.0;
    double quad_max = 0.0;
    bool quad_seen = false;
    for (int q = 0; q < 4; q++) {
        if (quad_count[q] <= 0) continue;
        double quad_mean = quad_sum[q] / (double)quad_count[q];
        if (!quad_seen) {
            quad_min = quad_mean;
            quad_max = quad_mean;
            quad_seen = true;
        } else {
            if (quad_mean < quad_min) quad_min = quad_mean;
            if (quad_mean > quad_max) quad_max = quad_mean;
        }
    }

    if (residual_std_out != NULL) *residual_std_out = (float)sqrt(var_residual);
    if (residual_max_out != NULL) *residual_max_out = (float)max_residual;
    if (motion_dx_std_out != NULL) *motion_dx_std_out = (float)sqrt(var_dx);
    if (motion_dy_std_out != NULL) *motion_dy_std_out = (float)sqrt(var_dy);
    if (quadrant_residual_spread_out != NULL) {
        *quadrant_residual_spread_out = quad_seen ? (float)(quad_max - quad_min) : 0.0f;
    }
}

static bool estimate_translation_seed(
        const uint8_t *prev_luma,
        const uint8_t *curr_luma,
        int            w,
        int            h,
        int            roi_x0,
        int            roi_x1,
        int            roi_y0,
        int            roi_y1,
        int            search_radius,
        int           *best_dx_out,
        int           *best_dy_out) {
    if (prev_luma == NULL || curr_luma == NULL || w <= 0 || h <= 0) return false;
    long best_sad = 0x7FFFFFFFFFFFFFFFL;
    int best_dx = 0;
    int best_dy = 0;
    bool found = false;
    int sample_stride = 2;
    int roi_w = roi_x1 - roi_x0 + 1;
    int roi_h = roi_y1 - roi_y0 + 1;
    if (roi_w >= 240 || roi_h >= 180) {
        sample_stride = 3;
    }
    if (roi_w >= 420 || roi_h >= 300) {
        sample_stride = 4;
    }
    for (int dy = -search_radius; dy <= search_radius; dy++) {
        for (int dx = -search_radius; dx <= search_radius; dx++) {
            int x0 = roi_x0;
            int x1 = roi_x1;
            int y0 = roi_y0;
            int y1 = roi_y1;
            if (dx < 0) {
                x0 = clamp_i32(-dx, x0, x1);
            } else if (dx > 0) {
                x1 = clamp_i32(w - 1 - dx, x0, x1);
            }
            if (dy < 0) {
                y0 = clamp_i32(-dy, y0, y1);
            } else if (dy > 0) {
                y1 = clamp_i32(h - 1 - dy, y0, y1);
            }
            if (x1 < x0 || y1 < y0) continue;
            long sad = 0;
            int count = 0;
            for (int y = y0; y <= y1; y += sample_stride) {
                const uint8_t *curr_row = curr_luma + (y * w);
                const uint8_t *prev_row = prev_luma + ((y + dy) * w);
                for (int x = x0; x <= x1; x += sample_stride) {
                    int d = (int)curr_row[x] - (int)prev_row[x + dx];
                    sad += d < 0 ? -d : d;
                    count++;
                }
            }
            if (count < 24) continue;
            if (!found || sad < best_sad) {
                best_sad = sad;
                best_dx = dx;
                best_dy = dy;
                found = true;
            }
        }
    }
    if (!found) return false;
    if (best_dx_out != NULL) *best_dx_out = best_dx;
    if (best_dy_out != NULL) *best_dy_out = best_dy;
    return true;
}

static int detect_affine_corners(
        const uint8_t *luma,
        int            w,
        int            h,
        int            roi_x0,
        int            roi_x1,
        int            roi_y0,
        int            roi_y1,
        int            min_distance,
        int            max_corners,
        int           *out_x,
        int           *out_y,
        int           *out_score) {
    if (luma == NULL || out_x == NULL || out_y == NULL || out_score == NULL ||
        w <= 2 || h <= 2 || max_corners <= 0) {
        return 0;
    }
    int count = 0;
    for (int y = roi_y0; y <= roi_y1; y++) {
        if (y <= 1 || y >= h - 2) continue;
        for (int x = roi_x0; x <= roi_x1; x++) {
            if (x <= 1 || x >= w - 2) continue;
            int score = gmv_feature_score(luma, w, h, x, y);
            if (score < ANOMALY_GMV_MIN_TEXTURE_SCORE) continue;
            bool too_close = false;
            for (int i = 0; i < count; i++) {
                int dx = x - out_x[i];
                int dy = y - out_y[i];
                if (dx * dx + dy * dy < min_distance * min_distance) {
                    too_close = true;
                    if (score > out_score[i]) {
                        out_x[i] = x;
                        out_y[i] = y;
                        out_score[i] = score;
                    }
                    break;
                }
            }
            if (too_close) continue;
            int insert_at = count;
            for (int i = 0; i < count; i++) {
                if (score > out_score[i]) {
                    insert_at = i;
                    break;
                }
            }
            if (insert_at >= max_corners) continue;
            int move_limit = count < max_corners ? count : (max_corners - 1);
            for (int i = move_limit; i > insert_at; i--) {
                out_x[i] = out_x[i - 1];
                out_y[i] = out_y[i - 1];
                out_score[i] = out_score[i - 1];
            }
            out_x[insert_at] = x;
            out_y[insert_at] = y;
            out_score[insert_at] = score;
            if (count < max_corners) count++;
        }
    }
    return count;
}

static bool patch_mse_at(
        const uint8_t *prev_luma,
        const uint8_t *curr_luma,
        int            w,
        int            h,
        int            prev_x,
        int            prev_y,
        int            curr_x,
        int            curr_y,
        int            patch_half,
        float          abort_mse_over,
        float         *out_mse) {
    if (prev_luma == NULL || curr_luma == NULL || out_mse == NULL) return false;
    if (prev_x - patch_half < 0 || prev_y - patch_half < 0 ||
        prev_x + patch_half >= w || prev_y + patch_half >= h ||
        curr_x - patch_half < 0 || curr_y - patch_half < 0 ||
        curr_x + patch_half >= w || curr_y + patch_half >= h) {
        return false;
    }
    float err = 0.0f;
    int patch_span = patch_half * 2 + 1;
    int count = patch_span * patch_span;
    float abort_err = abort_mse_over > 0.0f ? (abort_mse_over * (float)count) : -1.0f;
    for (int oy = -patch_half; oy <= patch_half; oy++) {
        for (int ox = -patch_half; ox <= patch_half; ox++) {
            float d = (float)prev_luma[(prev_y + oy) * w + (prev_x + ox)] -
                      (float)curr_luma[(curr_y + oy) * w + (curr_x + ox)];
            err += d * d;
            if (abort_err > 0.0f && err > abort_err) {
                return false;
            }
        }
    }
    if (count <= 0) return false;
    *out_mse = err / (float)count;
    return true;
}

static int track_affine_features(
        const uint8_t *prev_luma,
        const uint8_t *curr_luma,
        int            w,
        int            h,
        const int     *corner_x,
        const int     *corner_y,
        int            corner_count,
        int            base_dx,
        int            base_dy,
        int            patch_half,
        int            search_radius,
        affine_match_t *out_matches,
        int            max_matches) {
    if (prev_luma == NULL || curr_luma == NULL || corner_x == NULL || corner_y == NULL ||
        out_matches == NULL || max_matches <= 0) {
        return 0;
    }
    int match_count = 0;
    for (int i = 0; i < corner_count && match_count < max_matches; i++) {
        int x = corner_x[i];
        int y = corner_y[i];
        int pred_x = x - base_dx;
        int pred_y = y - base_dy;
        float best_err = 0.0f;
        float second_err = 0.0f;
        int best_x = 0;
        int best_y = 0;
        bool have_best = false;
        bool have_second = false;
        for (int dy = -search_radius; dy <= search_radius; dy++) {
            for (int dx = -search_radius; dx <= search_radius; dx++) {
                int cx = pred_x + dx;
                int cy = pred_y + dy;
                float mse = 0.0f;
                float abort_mse_over = have_best ? (best_err * 1.05f) : -1.0f;
                if (!patch_mse_at(
                        prev_luma,
                        curr_luma,
                        w,
                        h,
                        x,
                        y,
                        cx,
                        cy,
                        patch_half,
                        abort_mse_over,
                        &mse)) continue;
                if (!have_best || mse < best_err) {
                    second_err = best_err;
                    have_second = have_best;
                    best_err = mse;
                    best_x = cx;
                    best_y = cy;
                    have_best = true;
                } else if (!have_second || mse < second_err) {
                    second_err = mse;
                    have_second = true;
                }
            }
        }
        if (!have_best) continue;
        if (have_second && second_err <= best_err * 1.05f) continue;
        out_matches[match_count].x0 = (float)x;
        out_matches[match_count].y0 = (float)y;
        out_matches[match_count].x1 = (float)best_x;
        out_matches[match_count].y1 = (float)best_y;
        out_matches[match_count].err = best_err;
        match_count++;
    }
    return match_count;
}

static bool summarize_affine_as_similarity(
        const float affine[6],
        float mean_residual,
        similarity_2d_t *out_similarity) {
    if (affine == NULL || out_similarity == NULL) return false;
    float a = 0.5f * (affine[0] + affine[4]);
    float b = 0.5f * (affine[3] - affine[1]);
    out_similarity->a = a;
    out_similarity->b = b;
    out_similarity->tx = affine[2];
    out_similarity->ty = affine[5];
    out_similarity->mean_residual = mean_residual;
    out_similarity->valid = true;
    return true;
}

static bool fit_affine_ransac(
        const affine_match_t *matches,
        int                   match_count,
        float                 out_affine[6],
        float                *out_mean_residual) {
    if (matches == NULL || out_affine == NULL || out_mean_residual == NULL || match_count < 3) {
        return false;
    }
    float best_affine[6];
    int best_inliers[64];
    int best_inlier_count = 0;
    float best_mean = 1e9f;
    const int max_iters = 120;
    const float inlier_thresh = 1.5f;
    const float inlier_thresh_sq = inlier_thresh * inlier_thresh;
    for (int iter = 0; iter < max_iters; iter++) {
        int i0 = (iter * 17 + 1) % match_count;
        int i1 = (iter * 29 + 7) % match_count;
        int i2 = (iter * 43 + 11) % match_count;
        if (i0 == i1 || i0 == i2 || i1 == i2) continue;
        affine_match_t sample[3] = {matches[i0], matches[i1], matches[i2]};
        float affine[6];
        if (!fit_affine_least_squares(sample, 3, affine)) continue;
        int inliers[64];
        int inlier_count = 0;
        float residual_sum = 0.0f;
        for (int mi = 0; mi < match_count; mi++) {
            float px = 0.0f;
            float py = 0.0f;
            affine_apply(affine, matches[mi].x0, matches[mi].y0, &px, &py);
            float dx = px - matches[mi].x1;
            float dy = py - matches[mi].y1;
            float resid_sq = dx * dx + dy * dy;
            if (resid_sq <= inlier_thresh_sq && inlier_count < (int)(sizeof(inliers) / sizeof(inliers[0]))) {
                inliers[inlier_count++] = mi;
                residual_sum += sqrtf(resid_sq);
            }
        }
        if (inlier_count < 3) continue;
        float mean = residual_sum / (float)inlier_count;
        if (inlier_count > best_inlier_count || (inlier_count == best_inlier_count && mean < best_mean)) {
            memcpy(best_affine, affine, sizeof(best_affine));
            memcpy(best_inliers, inliers, (size_t)inlier_count * sizeof(int));
            best_inlier_count = inlier_count;
            best_mean = mean;
            if (best_inlier_count == match_count) break;
        }
    }
    if (best_inlier_count < 3) return false;

    affine_match_t refined[64];
    for (int i = 0; i < best_inlier_count; i++) refined[i] = matches[best_inliers[i]];
    if (!fit_affine_least_squares(refined, best_inlier_count, out_affine)) {
        memcpy(out_affine, best_affine, sizeof(best_affine));
        *out_mean_residual = best_mean;
        return true;
    }
    int residual_count = 0;
    if (!summarize_affine_match_residuals(
            out_affine,
            matches,
            match_count,
            inlier_thresh,
            &residual_count,
            out_mean_residual,
            NULL)) {
        *out_mean_residual = best_mean;
    }
    return true;
}

static inline float ii_query(const float *ii, int stride,
                             int sx0, int sy0, int sx1, int sy1) {
    return ii[sy1 * stride + sx1]
           - (sx0 > 0 ? ii[sy1 * stride + (sx0 - 1)] : 0.0)
           - (sy0 > 0 ? ii[(sy0 - 1) * stride + sx1] : 0.0)
           + (sx0 > 0 && sy0 > 0 ? ii[(sy0 - 1) * stride + (sx0 - 1)] : 0.0);
}

static float saliency_boundary_structure_scale(
        const float  *patch_score_map,
        const float  *thermal_delta_map,
        const float  *bg_luma,
        const float  *sg_luma,
        int           sg_w,
        int           sg_h,
        int           sx,
        int           sy,
        bool          bg_valid,
        bool          black_hot,
        float         thermal_min_delta,
        float         delta_norm) {
    if (!bg_valid || patch_score_map == NULL ||
        (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    size_t center_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float seed_delta = thermal_delta_map != NULL
        ? thermal_delta_map[center_idx]
        : thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            center_idx,
            black_hot);
    if (seed_delta < thermal_min_delta || patch_score_map[center_idx] <= 0.0f) {
        return 1.0f;
    }

    static const int dirs[8][2] = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1},
    };
    static const int opposite_dir[8] = {4, 5, 6, 7, 0, 1, 2, 3};
    static const int orth_axis[4] = {2, 3, 0, 1};

    float frame_band = fmaxf(1.5f, (float)delta_norm * 0.90f);
    float max_band = fmaxf(2.0f, seed_delta * 0.22f);
    if (frame_band > max_band) frame_band = max_band;
    float sustain_floor = fmaxf(
        thermal_min_delta,
        fmaxf(seed_delta * 0.60f, seed_delta - fmaxf(frame_band * 1.75f, 2.2f)));

    int reach[8] = {0};
    float near_ratio[8] = {0.0f};
    float axis_support[4] = {0.0f};
    float axis_balance[4] = {0.0f};
    float axis_near_ratio[4] = {0.0f};

    for (int di = 0; di < 8; di++) {
        float ratio_sum = 0.0f;
        int ratio_count = 0;
        for (int step = 1; step <= ANOMALY_SALIENCY_BOUNDARY_RADIUS_CELLS; step++) {
            int gx = sx + dirs[di][0] * step;
            int gy = sy + dirs[di][1] * step;
            if (gx < 0 || gx >= sg_w || gy < 0 || gy >= sg_h) break;
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (step <= 2 && delta > 0.0f) {
                ratio_sum += delta / fmaxf(seed_delta, 1.0f);
                ratio_count++;
            }
            if (delta < sustain_floor) break;
            if (fabsf(delta - seed_delta) > frame_band) break;
            reach[di] = step;
        }
        if (ratio_count > 0) {
            near_ratio[di] = ratio_sum / (float)ratio_count;
        }
    }

    for (int axis = 0; axis < 4; axis++) {
        int a = axis;
        int b = opposite_dir[axis];
        int major = reach[a] > reach[b] ? reach[a] : reach[b];
        int minor = reach[a] > reach[b] ? reach[b] : reach[a];
        axis_support[axis] = (float)(reach[a] + reach[b]);
        axis_balance[axis] = major > 0 ? ((float)minor / (float)major) : 0.0f;
        axis_near_ratio[axis] = 0.5f * (near_ratio[a] + near_ratio[b]);
    }

    int best_axis = 0;
    for (int axis = 1; axis < 4; axis++) {
        if (axis_support[axis] > axis_support[best_axis]) best_axis = axis;
    }
    int normal_axis = orth_axis[best_axis];
    float major_support = axis_support[best_axis];
    float normal_support = axis_support[normal_axis];
    float diagonal_support = 0.0f;
    for (int axis = 0; axis < 4; axis++) {
        if (axis == best_axis || axis == normal_axis) continue;
        if (axis_support[axis] > diagonal_support) diagonal_support = axis_support[axis];
    }
    float competing_support = normal_support > diagonal_support ? normal_support : diagonal_support;
    float linearity = major_support > 0.0f
        ? (major_support - competing_support) / major_support
        : 0.0f;
    float major_balance = axis_balance[best_axis];
    float major_near_ratio = axis_near_ratio[best_axis];
    float normal_near_ratio = axis_near_ratio[normal_axis];

    float penalty = 0.0f;
    if (major_support >= 5.0f &&
        linearity >= 0.30f &&
        major_balance >= 0.22f &&
        major_near_ratio >= 0.58f &&
        normal_near_ratio <= 0.72f) {
        penalty =
            0.36f * clampf((major_support - 4.5f) / 4.0f, 0.0f, 1.0f) +
            0.28f * clampf((linearity - 0.30f) / 0.45f, 0.0f, 1.0f) +
            0.20f * clampf((major_balance - 0.22f) / 0.45f, 0.0f, 1.0f) +
            0.16f * clampf((0.72f - normal_near_ratio) / 0.45f, 0.0f, 1.0f);
    }
    if (major_support >= 7.0f &&
        linearity >= 0.45f &&
        major_balance >= 0.35f &&
        major_near_ratio >= 0.70f &&
        normal_near_ratio <= 0.50f) {
        penalty += 0.20f;
    }

    return clampf(1.0f - 0.72f * clampf(penalty, 0.0f, 1.0f), 0.28f, 1.0f);
}

static void build_patch_selection_map(
        const float *score_map,
        int          sg_w,
        int          sg_h,
        float       *selection_map) {
    if (selection_map == NULL || sg_w <= 0 || sg_h <= 0) return;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            float center = score_map[sy * sg_w + sx];
            selection_map[sy * sg_w + sx] = -1.0f;
            if (center <= 0.0f) continue;

            // Require a local maximum so clutter edges don't drag the centroid.
            bool is_peak = true;
            for (int ny = sy - 1; ny <= sy + 1 && is_peak; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w) continue;
                    if (nx == sx && ny == sy) continue;
                    if (score_map[ny * sg_w + nx] > center) {
                        is_peak = false;
                        break;
                    }
                }
            }
            if (!is_peak) continue;

            float top1 = center, top2 = -1.0f, top3 = -1.0f;
            int support = 0;
            float sum_w = 0.0f;
            float sum_dx = 0.0f, sum_dy = 0.0f;
            float sum_dx2 = 0.0f, sum_dy2 = 0.0f, sum_dxdy = 0.0f;
            float ring_sum = 0.0f;
            int ring_count = 0;
            float outer_sum = 0.0f;
            int outer_count = 0;
            for (int ny = sy - ANOMALY_SALIENCY_SELECTION_SUPPORT_RADIUS;
                 ny <= sy + ANOMALY_SALIENCY_SELECTION_SUPPORT_RADIUS; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - ANOMALY_SALIENCY_SELECTION_SUPPORT_RADIUS;
                     nx <= sx + ANOMALY_SALIENCY_SELECTION_SUPPORT_RADIUS; nx++) {
                    if (nx < 0 || nx >= sg_w) continue;
                    float v = score_map[ny * sg_w + nx];
                    if (v <= 0.0f) continue;
                    support++;
                    float dx = (float)(nx - sx);
                    float dy = (float)(ny - sy);
                    sum_w += v;
                    sum_dx += dx * v;
                    sum_dy += dy * v;
                    sum_dx2 += dx * dx * v;
                    sum_dy2 += dy * dy * v;
                    sum_dxdy += dx * dy * v;
                    if (!(nx == sx && ny == sy)) {
                        ring_sum += v;
                        ring_count++;
                        if (abs(nx - sx) > 1 || abs(ny - sy) > 1) {
                            outer_sum += v;
                            outer_count++;
                        }
                    }
                    if (v > top1) {
                        top3 = top2;
                        top2 = top1;
                        top1 = v;
                    } else if (v > top2) {
                        top3 = top2;
                        top2 = v;
                    } else if (v > top3) {
                        top3 = v;
                    }
                }
            }

            float sum = top1;
            int n = 1;
            if (top2 > 0.0f) { sum += top2; n++; }
            if (top3 > 0.0f) { sum += top3; n++; }
            float score = sum / (float)n;
            float ring_mean = ring_count > 0 ? (ring_sum / (float)ring_count) : 0.0f;
            float ring_margin = center - ring_mean;

            // Small support bonus rewards a tiny coherent cluster but never
            // dominates the raw darkness score; we still want very small targets.
            float support_bonus = 0.08f * (float)(support > 1 ? (support - 1) : 0);
            if (support_bonus > 0.32f) support_bonus = 0.32f;
            score += support_bonus;

            // Penalise elongated / edge-like support that tends to occur on
            // foliage boundaries and clearing edges. Small compact peaks keep
            // most of their score; broad one-sided ridges lose some of it.
            if (sum_w > 0.0f) {
                float mean_dx = sum_dx / sum_w;
                float mean_dy = sum_dy / sum_w;
                float var_x = fmaxf(sum_dx2 / sum_w - mean_dx * mean_dx, 0.0f);
                float var_y = fmaxf(sum_dy2 / sum_w - mean_dy * mean_dy, 0.0f);
                float cov_xy = (sum_dxdy / sum_w) - mean_dx * mean_dy;
                float tr = var_x + var_y;
                float det_term = (var_x - var_y) * (var_x - var_y) + 4.0f * cov_xy * cov_xy;
                float root = sqrtf(fmaxf(det_term, 0.0f));
                float major = 0.5f * (tr + root);
                float minor = 0.5f * (tr - root);
                float anisotropy = (major + minor) > 1e-4f
                    ? (major - minor) / (major + minor)
                    : 0.0f;
                float center_share = center / sum_w;
                float offset_mag = sqrtf(mean_dx * mean_dx + mean_dy * mean_dy);
                float outer_share = outer_sum / sum_w;

                float elongation_penalty = 0.55f * anisotropy;
                float offset_penalty = 0.35f * offset_mag;
                float center_penalty = 0.0f;
                if (center_share < 0.26f) {
                    center_penalty = (0.26f - center_share) * 2.5f;
                }
                float outer_penalty = 0.0f;
                if (outer_count >= 3 &&
                    outer_share >= 0.34f &&
                    anisotropy >= 0.38f &&
                    offset_mag >= 0.16f) {
                    outer_penalty =
                        0.40f * clampf((outer_share - 0.34f) / 0.36f, 0.0f, 1.0f) +
                        0.35f * clampf((anisotropy - 0.38f) / 0.45f, 0.0f, 1.0f) +
                        0.25f * clampf((offset_mag - 0.16f) / 0.55f, 0.0f, 1.0f);
                }
                float compact_penalty =
                    elongation_penalty + offset_penalty + center_penalty + outer_penalty;
                if (compact_penalty > 1.35f) compact_penalty = 1.35f;
                score -= compact_penalty;
            }
            if (ring_margin < ANOMALY_SALIENCY_RING_MARGIN) {
                float penalty =
                    (ANOMALY_SALIENCY_RING_MARGIN - ring_margin) * ANOMALY_SALIENCY_RING_SOFT_SCALE;
                if (support >= ANOMALY_SALIENCY_PLATEAU_SUPPORT) {
                    penalty *= 1.35f;
                }
                score -= penalty;
                if (support >= ANOMALY_SALIENCY_PLATEAU_SUPPORT &&
                    ring_margin < (ANOMALY_SALIENCY_RING_MARGIN * 0.35f)) {
                    score *= ANOMALY_SALIENCY_RING_HARD_SCALE;
                }
            }
            selection_map[sy * sg_w + sx] = score;
        }
    }
}

static void estimate_representative_blob_delta_stats(
        const float *score_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sample_step,
        const anomaly_motion_candidate_t *candidates,
        int          candidate_count,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        delta_norm,
        float       *ratio_out,
        float       *std_out) {
    if (ratio_out != NULL) *ratio_out = 0.0f;
    if (std_out != NULL) *std_out = 0.0f;
    if (score_map == NULL || bg_luma == NULL || sg_luma == NULL ||
        candidates == NULL || candidate_count <= 0 || sg_w <= 0 || sg_h <= 0 ||
        !bg_valid) {
        return;
    }

    float weighted_ratio = 0.0f;
    float weighted_std = 0.0f;
    float total_weight = 0.0f;
    int growth_radius_cells = effective_thermal_representative_radius_cells(sample_step);

    for (int ci = 0; ci < candidate_count; ci++) {
        int sx = candidates[ci].sg_x;
        int sy = candidates[ci].sg_y;
        if (sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) continue;
        size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
        float seed_delta = black_hot
            ? (bg_luma[seed_idx] - (float)sg_luma[seed_idx])
            : ((float)sg_luma[seed_idx] - bg_luma[seed_idx]);
        if (seed_delta < thermal_min_delta) continue;

        float score_center = score_map[seed_idx];
        if (score_center <= 0.0f) continue;

        int rx0 = clamp_i32(sx - growth_radius_cells, 0, sg_w - 1);
        int rx1 = clamp_i32(sx + growth_radius_cells, 0, sg_w - 1);
        int ry0 = clamp_i32(sy - growth_radius_cells, 0, sg_h - 1);
        int ry1 = clamp_i32(sy + growth_radius_cells, 0, sg_h - 1);
        float seed_floor = fmaxf(
            thermal_min_delta,
            fmaxf(seed_delta * 0.58f, seed_delta - fmaxf(2.0f, (float)delta_norm * 0.75f)));

        int area = 0;
        int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
        double sum_delta = 0.0;
        double sum_delta2 = 0.0;
        for (int gy = ry0; gy <= ry1; gy++) {
            for (int gx = rx0; gx <= rx1; gx++) {
                int ring = abs(gx - sx);
                int dy = abs(gy - sy);
                if (dy > ring) ring = dy;
                if (ring > growth_radius_cells) continue;
                size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
                float score = score_map[idx];
                if (score <= 0.0f) continue;
                float delta = black_hot
                    ? (bg_luma[idx] - (float)sg_luma[idx])
                    : ((float)sg_luma[idx] - bg_luma[idx]);
                if (delta < seed_floor) continue;
                area++;
                sum_delta += (double)delta;
                sum_delta2 += (double)delta * (double)delta;
                if (gx < min_x) min_x = gx;
                if (gx > max_x) max_x = gx;
                if (gy < min_y) min_y = gy;
                if (gy > max_y) max_y = gy;
            }
        }
        if (area < ANOMALY_THERMAL_REPRESENTATIVE_MIN_AREA) continue;

        float span = (float)((max_x - min_x) > (max_y - min_y)
            ? (max_x - min_x + 1)
            : (max_y - min_y + 1));
        float mean_delta = (float)(sum_delta / (double)area);
        float var_delta = area > 1
            ? (float)fmax(sum_delta2 / (double)area - (double)mean_delta * (double)mean_delta, 0.0)
            : 0.0f;
        float std_delta = sqrtf(var_delta);
        float mean_ratio = mean_delta / fmaxf(seed_delta, 1.0f);
        if (mean_ratio <= 0.0f) continue;

        float weight = (float)area * fmaxf(span, 1.0f);
        weighted_ratio += weight * mean_ratio;
        weighted_std += weight * std_delta;
        total_weight += weight;
    }

    if (total_weight > 0.0f) {
        if (ratio_out != NULL) *ratio_out = weighted_ratio / total_weight;
        if (std_out != NULL) *std_out = weighted_std / total_weight;
    }
}

static void estimate_framewide_blob_contrast_stats(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float       *contrast_mean_out,
        float       *contrast_std_out) {
    if (contrast_mean_out != NULL) *contrast_mean_out = 0.0f;
    if (contrast_std_out != NULL) *contrast_std_out = 0.0f;
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0) return;

    double sum = 0.0;
    double sum2 = 0.0;
    int count = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            float delta0 = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (delta0 < thermal_min_delta) continue;
            if (sx + 1 < sg_w) {
                size_t nidx = idx + 1u;
                float delta1 = thermal_delta_map != NULL
                    ? thermal_delta_map[nidx]
                    : thermal_delta_from_maps(
                        thermal_delta_map,
                        bg_luma,
                        sg_luma,
                        nidx,
                        black_hot);
                if (delta1 >= thermal_min_delta) {
                    float hi = delta0 > delta1 ? delta0 : delta1;
                    float lo = delta0 > delta1 ? delta1 : delta0;
                    float ratio = lo / fmaxf(hi, 1.0f);
                    if (ratio >= 0.72f) {
                        float diff = fabsf(delta0 - delta1);
                        sum += (double)diff;
                        sum2 += (double)diff * (double)diff;
                        count++;
                    }
                }
            }
            if (sy + 1 < sg_h) {
                size_t nidx = idx + (size_t)sg_w;
                float delta1 = thermal_delta_map != NULL
                    ? thermal_delta_map[nidx]
                    : thermal_delta_from_maps(
                        thermal_delta_map,
                        bg_luma,
                        sg_luma,
                        nidx,
                        black_hot);
                if (delta1 >= thermal_min_delta) {
                    float hi = delta0 > delta1 ? delta0 : delta1;
                    float lo = delta0 > delta1 ? delta1 : delta0;
                    float ratio = lo / fmaxf(hi, 1.0f);
                    if (ratio >= 0.72f) {
                        float diff = fabsf(delta0 - delta1);
                        sum += (double)diff;
                        sum2 += (double)diff * (double)diff;
                        count++;
                    }
                }
            }
        }
    }
    if (count > 0) {
        float mean = (float)(sum / (double)count);
        float var = count > 1
            ? (float)fmax(sum2 / (double)count - (double)mean * (double)mean, 0.0)
            : 0.0f;
        if (contrast_mean_out != NULL) *contrast_mean_out = mean;
        if (contrast_std_out != NULL) *contrast_std_out = sqrtf(var);
    }
}

static float thermal_candidate_seed_context_scale(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        representative_delta_ratio,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        float        delta_norm) {
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float seed_delta = thermal_delta_map != NULL
        ? thermal_delta_map[seed_idx]
        : thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            seed_idx,
            black_hot);
    if (seed_delta < thermal_min_delta) return 0.45f;

    int radius = effective_thermal_context_radius_cells(sample_step);
    static const int dirs[8][2] = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1},
    };
    int reach[8] = {0};
    int warm_dirs = 0;
    int cool_dirs = 0;
    float near_ratio_sum = 0.0f;
    int near_ratio_count = 0;
    float near_drop_sum = 0.0f;
    int near_drop_count = 0;
    int flat_dirs = 0;
    float sustain_floor = fmaxf(
        thermal_min_delta,
        fmaxf(seed_delta * 0.72f, seed_delta - fmaxf(2.25f, (float)delta_norm * 0.80f)));
    float near_floor = fmaxf(
        thermal_min_delta,
        fmaxf(seed_delta * 0.60f, seed_delta - fmaxf(3.25f, (float)delta_norm)));
    float frame_band = frame_contrast_mean + 1.10f * frame_contrast_std;
    if (frame_band < 0.8f) frame_band = 0.8f;
    float max_band = fmaxf(1.3f, seed_delta * 0.22f);
    if (frame_band > max_band) frame_band = max_band;

    for (int di = 0; di < 8; di++) {
        bool first_step_seen = false;
        for (int step = 1; step <= radius; step++) {
            int gx = sx + dirs[di][0] * step;
            int gy = sy + dirs[di][1] * step;
            if (gx < 0 || gx >= sg_w || gy < 0 || gy >= sg_h) break;
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (step == 1) {
                first_step_seen = true;
                if (delta > 0.0f) {
                    float drop = seed_delta - delta;
                    near_drop_sum += drop;
                    near_drop_count++;
                    if (drop < frame_band) flat_dirs++;
                } else {
                    near_drop_sum += seed_delta;
                    near_drop_count++;
                }
            }
            if (step <= 2 && delta > 0.0f) {
                near_ratio_sum += delta / fmaxf(seed_delta, 1.0f);
                near_ratio_count++;
            }
            if (delta >= sustain_floor) {
                reach[di] = step;
            } else {
                if (step == 1 && delta <= near_floor) {
                    cool_dirs++;
                }
                break;
            }
        }
        if (!first_step_seen) {
            cool_dirs++;
        }
        if (reach[di] >= 2) warm_dirs++;
    }

    int vertical = reach[0] + reach[4];
    int horizontal = reach[2] + reach[6];
    int diag_a = reach[1] + reach[5];
    int diag_b = reach[3] + reach[7];
    int major_axis = vertical;
    if (horizontal > major_axis) major_axis = horizontal;
    if (diag_a > major_axis) major_axis = diag_a;
    if (diag_b > major_axis) major_axis = diag_b;
    int minor_axis = vertical;
    if (horizontal < minor_axis) minor_axis = horizontal;
    if (diag_a < minor_axis) minor_axis = diag_a;
    if (diag_b < minor_axis) minor_axis = diag_b;
    float axis_anisotropy = (major_axis + minor_axis) > 0
        ? (float)(major_axis - minor_axis) / (float)(major_axis + minor_axis)
        : 0.0f;
    float near_ratio = near_ratio_count > 0 ? (near_ratio_sum / (float)near_ratio_count) : 0.0f;
    float near_drop_mean = near_drop_count > 0 ? (near_drop_sum / (float)near_drop_count) : seed_delta;
    float representative_ratio = clampf(representative_delta_ratio, 0.0f, 1.0f);

    float scale = 1.0f;
    if (major_axis >= 5 && axis_anisotropy >= 0.55f) {
        scale *= 0.50f;
    } else if (major_axis >= 4 && axis_anisotropy >= 0.40f) {
        scale *= 0.72f;
    }
    if (warm_dirs >= 4) {
        scale *= 0.68f;
    } else if (warm_dirs == 3) {
        scale *= 0.82f;
    }
    if (near_ratio >= fmaxf(0.62f, representative_ratio + 0.04f)) {
        scale *= 0.62f;
    } else if (near_ratio >= fmaxf(0.48f, representative_ratio - 0.02f)) {
        scale *= 0.82f;
    }
    if (near_drop_mean < frame_band) {
        scale *= 0.42f;
    } else if (near_drop_mean < frame_band * 1.4f) {
        scale *= 0.68f;
    } else if (near_drop_mean < frame_band * 1.9f) {
        scale *= 0.86f;
    }
    if (flat_dirs >= 5) {
        scale *= 0.32f;
    } else if (flat_dirs >= 3) {
        scale *= 0.58f;
    }
    if (near_drop_mean >= frame_band * 2.4f && flat_dirs <= 1 && major_axis <= 3) {
        scale *= 1.30f;
    } else if (near_drop_mean >= frame_band * 1.8f && flat_dirs <= 2 && major_axis <= 4) {
        scale *= 1.16f;
    }
    if (cool_dirs >= 5 && warm_dirs <= 2 && major_axis <= 3) {
        scale *= 1.24f;
    } else if (cool_dirs >= 4 && major_axis <= 4) {
        scale *= 1.12f;
    }
    return clampf(scale, 0.22f, 1.35f);
}

static float thermal_candidate_parent_mass_scale(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        float        delta_norm) {
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float seed_delta = thermal_delta_map != NULL
        ? thermal_delta_map[seed_idx]
        : thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            seed_idx,
            black_hot);
    if (seed_delta < thermal_min_delta) return 0.50f;

    int radius = effective_thermal_parent_mass_radius_cells(sample_step);
    float frame_band = frame_contrast_mean + 1.25f * frame_contrast_std;
    if (frame_band < 1.0f) frame_band = 1.0f;
    float max_band = fmaxf(1.8f, seed_delta * 0.34f);
    if (frame_band > max_band) frame_band = max_band;
    float parent_floor = fmaxf(thermal_min_delta, seed_delta - frame_band);

    int area = 0;
    int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
    int dir_hits[8] = {0};
    static const int dirs[8][2] = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1},
    };
    for (int gy = clamp_i32(sy - radius, 0, sg_h - 1);
         gy <= clamp_i32(sy + radius, 0, sg_h - 1);
         gy++) {
        for (int gx = clamp_i32(sx - radius, 0, sg_w - 1);
             gx <= clamp_i32(sx + radius, 0, sg_w - 1);
             gx++) {
            int ring = abs(gx - sx);
            int dy = abs(gy - sy);
            if (dy > ring) ring = dy;
            if (ring > radius) continue;
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (delta < parent_floor) continue;
            area++;
            if (gx < min_x) min_x = gx;
            if (gx > max_x) max_x = gx;
            if (gy < min_y) min_y = gy;
            if (gy > max_y) max_y = gy;
            if (gx == sx && gy == sy) continue;
            for (int di = 0; di < 8; di++) {
                int dot = (gx - sx) * dirs[di][0] + (gy - sy) * dirs[di][1];
                if (dot > 0) dir_hits[di]++;
            }
        }
    }
    if (area <= 0) return 1.0f;

    float span = (float)(((max_x - min_x) > (max_y - min_y))
        ? (max_x - min_x + 1) : (max_y - min_y + 1));
    float span_px = span * (float)(sample_step > 0 ? sample_step : 1);
    int active_dirs = 0;
    for (int di = 0; di < 8; di++) {
        if (dir_hits[di] > 0) active_dirs++;
    }

    float scale = 1.0f;
    if (span_px >= 16.0f || area >= 24) {
        scale *= 0.28f;
    } else if (span_px >= 12.0f || area >= 16) {
        scale *= 0.46f;
    } else if (span_px >= 9.0f || area >= 10) {
        scale *= 0.68f;
    }
    if (active_dirs >= 6) {
        scale *= 0.55f;
    } else if (active_dirs >= 4) {
        scale *= 0.76f;
    }
    if (frame_band <= seed_delta * 0.14f && active_dirs <= 2 && span_px <= 6.0f) {
        scale *= 1.10f;
    }
    return clampf(scale, 0.18f, 1.10f);
}

static float thermal_candidate_quality(
        const float *score_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        delta_mean,
        float        delta_norm,
        float        representative_delta_ratio,
        float        representative_delta_std,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        float        best_small_span_px,
        int         *bbox_min_x_out,
        int         *bbox_min_y_out,
        int         *bbox_max_x_out,
        int         *bbox_max_y_out,
        float       *area_out,
        float       *span_out,
        float       *fill_out,
        float       *center_share_out) {
    (void)delta_mean;
    (void)delta_norm;
    (void)representative_delta_ratio;
    (void)representative_delta_std;

    if (bbox_min_x_out != NULL) *bbox_min_x_out = sx;
    if (bbox_min_y_out != NULL) *bbox_min_y_out = sy;
    if (bbox_max_x_out != NULL) *bbox_max_x_out = sx;
    if (bbox_max_y_out != NULL) *bbox_max_y_out = sy;
    if (area_out != NULL) *area_out = 0.0f;
    if (span_out != NULL) *span_out = 0.0f;
    if (fill_out != NULL) *fill_out = 0.0f;
    if (center_share_out != NULL) *center_share_out = 0.0f;
    if (score_map == NULL || sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 0.15f;
    }

    float center = score_map[sy * sg_w + sx];
    if (center <= 0.0f) return 0.15f;

    int growth_radius_cells = effective_thermal_growth_radius_cells(sample_step);

    int rx0 = clamp_i32(sx - growth_radius_cells, 0, sg_w - 1);
    int rx1 = clamp_i32(sx + growth_radius_cells, 0, sg_w - 1);
    int ry0 = clamp_i32(sy - growth_radius_cells, 0, sg_h - 1);
    int ry1 = clamp_i32(sy + growth_radius_cells, 0, sg_h - 1);
    int local_w = rx1 - rx0 + 1;
    int local_h = ry1 - ry0 + 1;
    int local_count = local_w * local_h;
    if (local_count <= 0) return 0.15f;

    bool use_raw_delta = (bg_valid && bg_luma != NULL && sg_luma != NULL);
    float seed_delta = 0.0f;
    float min_delta = thermal_min_delta;
    float frame_contrast_band = 0.0f;
    float score_threshold = center * 0.28f;
    float seed_neighbor_peak = 0.0f;
    float seed_prominence = center;
    float required_prominence = 0.0f;
    float peakiness_scale = 1.0f;
    if (score_threshold < 0.25f) score_threshold = 0.25f;
    if (score_threshold > center) score_threshold = center;
    if (use_raw_delta) {
        size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
        float bg = bg_luma[seed_idx];
        float lum = (float)sg_luma[seed_idx];
        seed_delta = black_hot ? (bg - lum) : (lum - bg);
        if (seed_delta < thermal_min_delta || center <= 0.0f) {
            use_raw_delta = false;
        } else {
            frame_contrast_band = frame_contrast_mean + 1.25f * frame_contrast_std;
            if (frame_contrast_band < 0.8f) frame_contrast_band = 0.8f;
            {
                float max_frame_band = fmaxf(1.4f, seed_delta * 0.18f);
                if (frame_contrast_band > max_frame_band) frame_contrast_band = max_frame_band;
            }
            min_delta = seed_delta - frame_contrast_band;
            if (min_delta < thermal_min_delta) min_delta = thermal_min_delta;
        }
    }

    for (int ny = sy - 1; ny <= sy + 1; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - 1; nx <= sx + 1; nx++) {
            if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
            float neighbor_value = score_map[ny * sg_w + nx];
            if (use_raw_delta) {
                size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                float nbg = bg_luma[nidx];
                float nlum = (float)sg_luma[nidx];
                float ndelta = black_hot ? (nbg - nlum) : (nlum - nbg);
                if (ndelta > seed_neighbor_peak) seed_neighbor_peak = ndelta;
            } else if (neighbor_value > seed_neighbor_peak) {
                seed_neighbor_peak = neighbor_value;
            }
        }
    }

    if (use_raw_delta) {
        seed_prominence = seed_delta - seed_neighbor_peak;
        required_prominence = frame_contrast_band * 0.70f;
        if (required_prominence < 0.60f) required_prominence = 0.60f;
        if (required_prominence > seed_delta * 0.12f) {
            float cap = fmaxf(0.60f, seed_delta * 0.12f);
            required_prominence = cap;
        }
    } else {
        seed_prominence = center - seed_neighbor_peak;
        required_prominence = fmaxf(0.12f, center * 0.08f);
    }

    if (seed_prominence <= 0.0f) {
        return 0.0f;
    }
    if (seed_prominence < required_prominence) {
        peakiness_scale = clampf(seed_prominence / fmaxf(required_prominence, 0.001f), 0.0f, 1.0f);
        peakiness_scale *= peakiness_scale;
    } else if (seed_prominence >= required_prominence * 1.6f) {
        peakiness_scale = 1.10f;
    }

    uint8_t *seen = (uint8_t *)calloc((size_t)local_count, sizeof(uint8_t));
    uint8_t *blob_mask = (uint8_t *)calloc((size_t)local_count, sizeof(uint8_t));
    int *queue = (int *)malloc((size_t)local_count * sizeof(int));
    if (seen == NULL || blob_mask == NULL || queue == NULL) {
        free(seen);
        free(blob_mask);
        free(queue);
        return 0.15f;
    }

    int seed_local_x = sx - rx0;
    int seed_local_y = sy - ry0;
    int head = 0;
    int tail = 0;
    queue[tail++] = seed_local_y * local_w + seed_local_x;
    seen[seed_local_y * local_w + seed_local_x] = 1u;

    int area = 0;
    int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
    float sum_score = 0.0f;
    float center_mass = 0.0f;
    int plateau_cells = 0;
    float plateau_sum = 0.0f;
    int ring_hits[ANOMALY_THERMAL_GROWTH_MAX_RADIUS + 1];
    int ring_possible[ANOMALY_THERMAL_GROWTH_MAX_RADIUS + 1];
    float ring_score[ANOMALY_THERMAL_GROWTH_MAX_RADIUS + 1];
    memset(ring_hits, 0, sizeof(ring_hits));
    memset(ring_possible, 0, sizeof(ring_possible));
    memset(ring_score, 0, sizeof(ring_score));
    for (int gy = ry0; gy <= ry1; gy++) {
        for (int gx = rx0; gx <= rx1; gx++) {
            int ring = abs(gx - sx);
            int dy = abs(gy - sy);
            if (dy > ring) ring = dy;
            if (ring > growth_radius_cells) continue;
            ring_possible[ring]++;
        }
    }
    while (head < tail) {
        int local_idx = queue[head++];
        int lx = local_idx % local_w;
        int ly = local_idx / local_w;
        int gx = rx0 + lx;
        int gy = ry0 + ly;
        float value = score_map[gy * sg_w + gx];
        if (value <= 0.0f) continue;
        if (use_raw_delta) {
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float bg = bg_luma[idx];
            float lum = (float)sg_luma[idx];
            float delta = black_hot ? (bg - lum) : (lum - bg);
            if (delta < min_delta) continue;
            if (frame_contrast_band > 0.0f && fabsf(delta - seed_delta) > frame_contrast_band) continue;
        } else if (value < score_threshold) {
            continue;
        }

        area++;
        blob_mask[local_idx] = 1u;
        sum_score += value;
        int ring = abs(gx - sx);
        int dy = abs(gy - sy);
        if (dy > ring) ring = dy;
        if (ring > growth_radius_cells) ring = growth_radius_cells;
        ring_hits[ring]++;
        ring_score[ring] += value;
        if (value >= center * 0.82f) {
            plateau_cells++;
            plateau_sum += value;
        }
        if (gx < min_x) min_x = gx;
        if (gx > max_x) max_x = gx;
        if (gy < min_y) min_y = gy;
        if (gy > max_y) max_y = gy;
        if (abs(gx - sx) <= 1 && abs(gy - sy) <= 1) {
            center_mass += value;
        }

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) continue;
                int nlx = lx + ox;
                int nly = ly + oy;
                if (nlx < 0 || nlx >= local_w || nly < 0 || nly >= local_h) continue;
                int nlocal_idx = nly * local_w + nlx;
                if (seen[nlocal_idx]) continue;
                seen[nlocal_idx] = 1u;
                queue[tail++] = nlocal_idx;
            }
        }
    }

    if (area <= 0) {
        free(seen);
        free(blob_mask);
        free(queue);
        return 0.15f;
    }

    int span_w = max_x - min_x + 1;
    int span_h = max_y - min_y + 1;
    int bbox_area = span_w * span_h;
    float fill_ratio = bbox_area > 0 ? ((float)area / (float)bbox_area) : 0.0f;
    float max_span = (float)(span_w > span_h ? span_w : span_h);
    float center_share = sum_score > 0.0f ? (center_mass / sum_score) : 0.0f;
    float plateau_ratio = area > 0 ? ((float)plateau_cells / (float)area) : 0.0f;
    float plateau_mass_share = sum_score > 0.0f ? (plateau_sum / sum_score) : 0.0f;
    float span_px = max_span * (float)(sample_step > 0 ? sample_step : 1);
    float border_mean = 0.0f;
    float border_peak = 0.0f;
    float border_hot_fraction = 0.0f;
    bool border_isolated = true;

    {
        memset(seen, 0, (size_t)local_count * sizeof(uint8_t));
        for (int ly = 0; ly < local_h; ly++) {
            for (int lx = 0; lx < local_w; lx++) {
                int local_idx = ly * local_w + lx;
                if (blob_mask[local_idx] == 0u) continue;
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        int nlx = lx + ox;
                        int nly = ly + oy;
                        if (nlx < 0 || nlx >= local_w || nly < 0 || nly >= local_h) continue;
                        int nlocal_idx = nly * local_w + nlx;
                        if (blob_mask[nlocal_idx] != 0u) continue;
                        seen[nlocal_idx] = 1u;
                    }
                }
            }
        }

        double border_sum = 0.0;
        int border_count = 0;
        int border_hot = 0;
        for (int ly = 0; ly < local_h; ly++) {
            for (int lx = 0; lx < local_w; lx++) {
                int local_idx = ly * local_w + lx;
                if (seen[local_idx] == 0u) continue;
                int gx = rx0 + lx;
                int gy = ry0 + ly;
                float border_value = 0.0f;
                if (use_raw_delta) {
                    size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
                    float bg = bg_luma[idx];
                    float lum = (float)sg_luma[idx];
                    border_value = black_hot ? (bg - lum) : (lum - bg);
                } else {
                    border_value = score_map[gy * sg_w + gx];
                }
                if (border_value <= 0.0f) continue;
                border_sum += (double)border_value;
                border_count++;
                if (border_value > border_peak) border_peak = border_value;
                if (use_raw_delta) {
                    if (border_value >= min_delta) border_hot++;
                } else if (border_value >= score_threshold) {
                    border_hot++;
                }
            }
        }
        if (border_count > 0) {
            border_mean = (float)(border_sum / (double)border_count);
            border_hot_fraction = (float)border_hot / (float)border_count;
        }
    }

    int farthest_ring = 0;
    int outer_ring_hits = 0;
    int outer_ring_possible = 0;
    for (int ring = 1; ring <= growth_radius_cells; ring++) {
        if (ring_hits[ring] > 0) farthest_ring = ring;
        if (ring >= 3) {
            outer_ring_hits += ring_hits[ring];
            outer_ring_possible += ring_possible[ring];
        }
    }
    float outer_fill = outer_ring_possible > 0
        ? ((float)outer_ring_hits / (float)outer_ring_possible)
        : 0.0f;
    bool touches_growth_limit =
        (min_x == rx0 || max_x == rx1 || min_y == ry0 || max_y == ry1);

    float area_scale;
    if (area <= 1) {
        area_scale = 0.68f;
    } else if (area <= 4) {
        area_scale = 1.18f;
    } else if (area <= 7) {
        area_scale = 1.00f;
    } else if (area <= 10) {
        area_scale = 0.62f;
    } else {
        area_scale = 0.20f;
    }

    float span_scale;
    if (span_px <= 3.0f) {
        span_scale = 1.18f;
    } else if (span_px <= 6.0f) {
        span_scale = 1.00f;
    } else if (span_px <= 9.0f) {
        span_scale = 0.58f;
    } else if (span_px <= (float)ANOMALY_THERMAL_SMALL_TARGET_DIAMETER_PX) {
        span_scale = 0.30f;
    } else {
        span_scale = 0.10f;
    }

    float fill_scale = clampf(0.35f + 1.05f * fill_ratio, 0.30f, 1.18f);
    float center_scale = clampf(0.48f + 1.15f * center_share, 0.30f, 1.18f);
    float plateau_scale = 1.0f;
    float border_scale = 1.0f;
    float required_border_gap = required_prominence;
    float seed_border_gap = 0.0f;
    float seed_border_mean_gap = 0.0f;
    if (use_raw_delta && frame_contrast_band > required_border_gap) {
        required_border_gap = frame_contrast_band;
    }
    if (required_border_gap < 0.70f) required_border_gap = 0.70f;
    if (border_peak > 0.0f) {
        seed_border_gap = use_raw_delta ? (seed_delta - border_peak) : (center - border_peak);
        if (seed_border_gap <= 0.0f) {
            border_scale *= 0.10f;
        } else if (seed_border_gap < required_border_gap * 0.60f) {
            border_scale *= 0.28f;
        } else if (seed_border_gap < required_border_gap) {
            border_scale *= 0.55f;
        } else if (seed_border_gap < required_border_gap * 1.35f) {
            border_scale *= 0.82f;
        }
    }
    if (border_hot_fraction >= 0.42f) {
        border_scale *= 0.28f;
    } else if (border_hot_fraction >= 0.26f) {
        border_scale *= 0.54f;
    } else if (border_hot_fraction >= 0.14f) {
        border_scale *= 0.78f;
    }
    if (border_mean > 0.0f) {
        seed_border_mean_gap = use_raw_delta ? (seed_delta - border_mean) : (center - border_mean);
        if (seed_border_mean_gap <= required_prominence * 0.50f) {
            border_scale *= 0.52f;
        } else if (seed_border_mean_gap <= required_prominence * 0.85f) {
            border_scale *= 0.76f;
        }
    }
    if (border_peak > 0.0f) {
        if (seed_border_gap < required_border_gap * 0.85f) {
            border_isolated = false;
        }
    }
    if (border_mean > 0.0f) {
        if (seed_border_mean_gap < required_border_gap * 0.65f) {
            border_isolated = false;
        }
    }
    if (border_hot_fraction >= 0.18f) {
        border_isolated = false;
    }
    if (!border_isolated) {
        free(blob_mask);
        free(queue);
        return 0.0f;
    }
    if (area > 2) {
        if (plateau_ratio >= 0.78f) {
            plateau_scale *= 0.32f;
        } else if (plateau_ratio >= 0.60f) {
            plateau_scale *= 0.56f;
        } else if (plateau_ratio >= 0.45f) {
            plateau_scale *= 0.78f;
        }
        if (plateau_mass_share >= 0.82f) {
            plateau_scale *= 0.68f;
        } else if (plateau_mass_share >= 0.68f) {
            plateau_scale *= 0.84f;
        }
    }
    float growth_scale;
    if (farthest_ring <= 2) {
        growth_scale = 1.08f;
    } else if (farthest_ring == 3) {
        growth_scale = 0.84f;
    } else if (farthest_ring == 4) {
        growth_scale = 0.48f;
    } else {
        growth_scale = 0.20f;
    }
    if (outer_fill >= 0.35f) {
        growth_scale *= 0.40f;
    } else if (outer_fill >= 0.20f) {
        growth_scale *= 0.68f;
    }
    if (touches_growth_limit) {
        growth_scale *= 0.36f;
    }
    if (best_small_span_px > 0.0f &&
        best_small_span_px <= effective_thermal_small_target_span_px(
            NULL,
            sample_step * sg_w,
            sample_step * sg_h) &&
        span_px >= best_small_span_px + 2.0f) {
        growth_scale *= 0.60f;
    }

    float quality = area_scale * span_scale * fill_scale * center_scale *
        plateau_scale * growth_scale * border_scale;
    quality *= peakiness_scale;
    if (span_px >= effective_thermal_small_target_span_px(
            NULL,
            sample_step * sg_w,
            sample_step * sg_h) && area >= 8) {
        quality *= 0.18f;
    }
    if (use_raw_delta && seed_delta > 0.0f && frame_contrast_band > 0.0f) {
        float contrast_fraction = frame_contrast_band / fmaxf(seed_delta, 1.0f);
        if (contrast_fraction >= 0.22f) {
            quality *= 0.78f;
        }
    }
    quality = clampf(quality, 0.0f, 1.35f);

    if (area_out != NULL) *area_out = (float)area;
    if (span_out != NULL) *span_out = max_span;
    if (fill_out != NULL) *fill_out = fill_ratio;
    if (center_share_out != NULL) *center_share_out = center_share;
    if (bbox_min_x_out != NULL) *bbox_min_x_out = min_x;
    if (bbox_min_y_out != NULL) *bbox_min_y_out = min_y;
    if (bbox_max_x_out != NULL) *bbox_max_x_out = max_x;
    if (bbox_max_y_out != NULL) *bbox_max_y_out = max_y;
    free(seen);
    free(blob_mask);
    free(queue);
    return quality;
}

static float thermal_candidate_seed_strength(
        float base_score,
        float quality,
        float area,
        float span,
        float fill,
        float center_share,
        float isolation_rank) {
    float base_scale = clampf((base_score - 0.75f) / 3.00f, 0.0f, 1.0f);
    float quality_scale = clampf((quality - 0.30f) / 0.95f, 0.0f, 1.0f);

    float area_scale;
    if (area <= 1.0f) {
        area_scale = 0.55f;
    } else if (area <= 6.0f) {
        area_scale = 1.00f;
    } else if (area <= 10.0f) {
        area_scale = 0.55f;
    } else {
        area_scale = 0.15f;
    }

    float span_scale;
    if (span <= 2.0f) {
        span_scale = 1.00f;
    } else if (span <= 4.0f) {
        span_scale = 0.90f;
    } else if (span <= 6.0f) {
        span_scale = 0.45f;
    } else {
        span_scale = 0.10f;
    }

    float fill_scale = clampf((fill - 0.28f) / 0.52f, 0.0f, 1.0f);
    float center_scale = clampf((center_share - 0.18f) / 0.38f, 0.0f, 1.0f);
    float isolation_scale = clampf((isolation_rank - 0.38f) / 0.42f, 0.0f, 1.0f);
    return clampf(
        base_scale * quality_scale * area_scale * span_scale *
        (0.35f + 0.65f * fill_scale) * (0.35f + 0.65f * center_scale) *
        (0.20f + 0.80f * isolation_scale),
        0.0f,
        1.0f);
}

static float thermal_candidate_history_scale(
        const anomaly_state_t *state,
        int                    sg_w,
        int                    sg_h,
        int                    sx,
        int                    sy) {
    if (state == NULL || state->thermal_target_persist == NULL ||
        state->thermal_target_persist_w != sg_w || state->thermal_target_persist_h != sg_h ||
        sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    float peak = 0.0f;
    float sum = 0.0f;
    int count = 0;
    int rx0 = clamp_i32(sx - ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_w - 1);
    int rx1 = clamp_i32(sx + ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_w - 1);
    int ry0 = clamp_i32(sy - ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_h - 1);
    int ry1 = clamp_i32(sy + ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_h - 1);
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            float value = state->thermal_target_persist[y * sg_w + x];
            if (value > peak) peak = value;
            sum += value;
            count++;
        }
    }
    float local_support = count > 0 ? fmaxf(peak, sum / (float)count) : peak;
    local_support = clampf(local_support, 0.0f, 1.0f);
    float shaped_support = clampf((local_support - 0.15f) / 0.70f, 0.0f, 1.0f);
    return 1.0f + ANOMALY_THERMAL_TARGET_HISTORY_GAIN * shaped_support;
}

static void stamp_thermal_target_support(
        float *persist_map,
        int    sg_w,
        int    sg_h,
        int    sx,
        int    sy,
        float  strength) {
    if (persist_map == NULL || sg_w <= 0 || sg_h <= 0 || strength <= 0.0f) return;
    int rx0 = clamp_i32(sx - ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_w - 1);
    int rx1 = clamp_i32(sx + ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_w - 1);
    int ry0 = clamp_i32(sy - ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_h - 1);
    int ry1 = clamp_i32(sy + ANOMALY_THERMAL_TARGET_HISTORY_RADIUS, 0, sg_h - 1);
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            int dx = abs(x - sx);
            int dy = abs(y - sy);
            float weight;
            if (dx == 0 && dy == 0) weight = 1.00f;
            else if ((dx + dy) == 1) weight = 0.82f;
            else if (dx <= 1 && dy <= 1) weight = 0.62f;
            else if ((dx + dy) == 2) weight = 0.42f;
            else weight = 0.24f;
            float candidate = strength * weight;
            float *slot = &persist_map[y * sg_w + x];
            if (candidate > *slot) *slot = candidate;
        }
    }
}

static void build_motion_selection_map(
        const float *motion_z_map,
        const uint8_t *curr_luma,
        int          motion_w,
        int          motion_h,
        float       *selection_map,
        float       *component_area_frac_map,
        float       *component_span_frac_map,
        float       *component_fill_ratio_map) {
    if (selection_map == NULL || motion_z_map == NULL || motion_w <= 0 || motion_h <= 0) return;
    size_t cell_count = (size_t)motion_w * (size_t)motion_h;
    int *component_map = (int *)malloc(cell_count * sizeof(int));
    int *queue = (int *)malloc(cell_count * sizeof(int));
    uint8_t *mass_seen = (uint8_t *)malloc(cell_count * sizeof(uint8_t));
    if (component_map == NULL || queue == NULL || mass_seen == NULL) {
        free(component_map);
        free(queue);
        free(mass_seen);
        for (size_t i = 0; i < cell_count; i++) selection_map[i] = -1.0f;
        return;
    }

    for (size_t i = 0; i < cell_count; i++) {
        selection_map[i] = -1.0f;
        if (component_area_frac_map != NULL) component_area_frac_map[i] = 0.0f;
        if (component_span_frac_map != NULL) component_span_frac_map[i] = 0.0f;
        if (component_fill_ratio_map != NULL) component_fill_ratio_map[i] = 0.0f;
        component_map[i] = -1;
        mass_seen[i] = 0;
    }

    int component_id = 0;
    for (int my = 0; my < motion_h; my++) {
        for (int mx = 0; mx < motion_w; mx++) {
            int seed_idx = my * motion_w + mx;
            float seed_excess = motion_z_map[seed_idx] - 1.0f;
            if (seed_excess <= 0.0f || component_map[seed_idx] >= 0) continue;

            int head = 0, tail = 0;
            queue[tail++] = seed_idx;
            component_map[seed_idx] = component_id;

            int min_x = mx, max_x = mx;
            int min_y = my, max_y = my;
            int area = 0;
            float peak_excess = seed_excess;
            int peak_idx = seed_idx;
            float sum_excess = 0.0f;
            float luma_sum = 0.0f;
            float luma_sum_sq = 0.0f;

            while (head < tail) {
                int idx = queue[head++];
                int cx = idx % motion_w;
                int cy = idx / motion_w;
                float excess = motion_z_map[idx] - 1.0f;
                if (excess <= 0.0f) continue;

                area++;
                sum_excess += excess;
                if (curr_luma != NULL) {
                    float lum = (float)curr_luma[idx];
                    luma_sum += lum;
                    luma_sum_sq += lum * lum;
                }
                if (excess > peak_excess) {
                    peak_excess = excess;
                    peak_idx = idx;
                }
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;

                for (int ny = cy - 1; ny <= cy + 1; ny++) {
                    if (ny < 0 || ny >= motion_h) continue;
                    for (int nx = cx - 1; nx <= cx + 1; nx++) {
                        if (nx < 0 || nx >= motion_w) continue;
                        int nidx = ny * motion_w + nx;
                        if (component_map[nidx] >= 0) continue;
                        if ((motion_z_map[nidx] - 1.0f) <= 0.0f) continue;
                        component_map[nidx] = component_id;
                        queue[tail++] = nidx;
                    }
                }
            }

            if (area <= 0) {
                component_id++;
                continue;
            }

            float mean_excess = sum_excess / (float)area;
            int box_w = max_x - min_x + 1;
            int box_h = max_y - min_y + 1;
            int box_area = box_w * box_h;
            float fill_ratio = box_area > 0 ? ((float)area / (float)box_area) : 0.0f;
            float component_area_frac = cell_count > 0 ? ((float)area / (float)cell_count) : 0.0f;
            float footprint_area_frac = cell_count > 0 ? ((float)box_area / (float)cell_count) : 0.0f;
            float span_frac_w = motion_w > 0 ? ((float)box_w / (float)motion_w) : 1.0f;
            float span_frac_h = motion_h > 0 ? ((float)box_h / (float)motion_h) : 1.0f;
            float max_span_frac = span_frac_w > span_frac_h ? span_frac_w : span_frac_h;
            float aspect = (box_w > box_h)
                ? ((float)box_w / (float)(box_h > 0 ? box_h : 1))
                : ((float)box_h / (float)(box_w > 0 ? box_w : 1));

            int pad = 3;
            int rx0 = clamp_i32(min_x - pad, 0, motion_w - 1);
            int rx1 = clamp_i32(max_x + pad, 0, motion_w - 1);
            int ry0 = clamp_i32(min_y - pad, 0, motion_h - 1);
            int ry1 = clamp_i32(max_y + pad, 0, motion_h - 1);
            float outer_sum = 0.0f;
            int outer_count = 0;
            int quiet_count = 0;
            int moving_count = 0;
            float outer_luma_sum = 0.0f;
            int outer_luma_count = 0;
            for (int ry = ry0; ry <= ry1; ry++) {
                for (int rx = rx0; rx <= rx1; rx++) {
                    int ridx = ry * motion_w + rx;
                    if (component_map[ridx] == component_id) continue;
                    float excess = motion_z_map[ridx] - 1.0f;
                    if (excess < 0.0f) excess = 0.0f;
                    outer_sum += excess;
                    outer_count++;
                    if (excess <= 0.35f) {
                        quiet_count++;
                    } else {
                        moving_count++;
                    }
                    if (curr_luma != NULL) {
                        outer_luma_sum += (float)curr_luma[ridx];
                        outer_luma_count++;
                    }
                }
            }
            float outer_mean = outer_count > 0 ? (outer_sum / (float)outer_count) : 0.0f;
            float quiet_fraction = outer_count > 0 ? ((float)quiet_count / (float)outer_count) : 0.0f;
            float moving_fraction = outer_count > 0 ? ((float)moving_count / (float)outer_count) : 0.0f;
            float component_luma_mean = area > 0 ? (luma_sum / (float)area) : 0.0f;
            float component_luma_var = area > 0
                ? (luma_sum_sq / (float)area) - (component_luma_mean * component_luma_mean)
                : 0.0f;
            if (component_luma_var < 0.0f) component_luma_var = 0.0f;
            float component_luma_std = sqrtf(component_luma_var);
            float outer_luma_mean = outer_luma_count > 0 ? (outer_luma_sum / (float)outer_luma_count) : component_luma_mean;
            float tone_delta = fabsf(component_luma_mean - outer_luma_mean);
            float tone_coherence = tone_delta / (component_luma_std + 4.0f);
            int homogeneous_mass_count = 0;
            float homogeneous_mass_frac = 0.0f;
            if (curr_luma != NULL) {
                int mass_pad = ANOMALY_MOTION_HOMOGENEOUS_MASS_PAD;
                int mass_x0 = clamp_i32(min_x - mass_pad, 0, motion_w - 1);
                int mass_x1 = clamp_i32(max_x + mass_pad, 0, motion_w - 1);
                int mass_y0 = clamp_i32(min_y - mass_pad, 0, motion_h - 1);
                int mass_y1 = clamp_i32(max_y + mass_pad, 0, motion_h - 1);
                int mass_window_area = (mass_x1 - mass_x0 + 1) * (mass_y1 - mass_y0 + 1);
                if (mass_window_area > 0) {
                    memset(mass_seen, 0, cell_count * sizeof(uint8_t));
                    int mass_head = 0;
                    int mass_tail = 0;
                    queue[mass_tail++] = peak_idx;
                    mass_seen[peak_idx] = 1;
                    float seed_luma = (float)curr_luma[peak_idx];
                    while (mass_head < mass_tail) {
                        int idx = queue[mass_head++];
                        int cx = idx % motion_w;
                        int cy = idx / motion_w;
                        homogeneous_mass_count++;
                        for (int ny = cy - 1; ny <= cy + 1; ny++) {
                            if (ny < mass_y0 || ny > mass_y1) continue;
                            for (int nx = cx - 1; nx <= cx + 1; nx++) {
                                if (nx < mass_x0 || nx > mass_x1) continue;
                                int nidx = ny * motion_w + nx;
                                if (mass_seen[nidx]) continue;
                                float lum = (float)curr_luma[nidx];
                                if (fabsf(lum - seed_luma) > ANOMALY_MOTION_HOMOGENEOUS_MASS_DELTA) continue;
                                mass_seen[nidx] = 1;
                                queue[mass_tail++] = nidx;
                            }
                        }
                    }
                    homogeneous_mass_frac = (float)homogeneous_mass_count / (float)mass_window_area;
                }
            }

            float contrast_ratio = (mean_excess + 0.50f) / (outer_mean + 0.50f);
            if (contrast_ratio < 0.10f) contrast_ratio = 0.10f;
            if (contrast_ratio > 2.50f) contrast_ratio = 2.50f;

            float score = peak_excess;
            if (area <= 1) {
                score *= 0.15f;
            } else {
                float area_bonus = 0.22f * (float)(area - 1);
                if (area_bonus > 1.10f) area_bonus = 1.10f;
                score += area_bonus;
            }

            score *= contrast_ratio;
            score *= (0.45f + 0.85f * quiet_fraction);
            score -= 2.40f * moving_fraction;

            if (fill_ratio < ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO) {
                float fill_penalty = (ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO - fill_ratio) * 3.1f;
                if (fill_ratio < ANOMALY_MOTION_COMPONENT_MIN_FILL_RATIO) {
                    fill_penalty += (ANOMALY_MOTION_COMPONENT_MIN_FILL_RATIO - fill_ratio) * 2.6f;
                }
                if (fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                    fill_penalty += (ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO - fill_ratio) * 5.0f;
                }
                if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                    fill_penalty *= 1.35f;
                }
                score -= fill_penalty;
            }
            if (aspect > 2.5f) {
                float aspect_penalty = (aspect - 2.5f) * 0.55f;
                if (aspect_penalty > 2.0f) aspect_penalty = 2.0f;
                score -= aspect_penalty;
            } else if (aspect > ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) {
                float aspect_penalty = (aspect - ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) * 0.95f;
                if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                    aspect_penalty *= 1.25f;
                }
                score -= aspect_penalty;
            }
            if (area <= 2 && fill_ratio < 0.58f && aspect > 1.35f) {
                score -= 1.75f;
            } else if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                       fill_ratio < 0.50f &&
                       aspect > 1.20f) {
                score -= 0.90f;
            }
            if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                score -= 1.35f;
                if (max_span_frac > ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) {
                    score -= 1.10f;
                }
            }
            if (curr_luma != NULL) {
                if (tone_coherence < ANOMALY_MOTION_COMPONENT_MIN_TONE_COHERENCE) {
                    float tone_penalty =
                        (ANOMALY_MOTION_COMPONENT_MIN_TONE_COHERENCE - tone_coherence) * 2.2f;
                    if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                        tone_penalty *= 1.30f;
                    }
                    score -= tone_penalty;
                } else if (tone_coherence >= ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE &&
                           area >= 2 &&
                           area <= 10 &&
                           fill_ratio >= 0.50f) {
                    float tone_bonus =
                        (tone_coherence - ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE) * 0.35f;
                    if (tone_bonus > 0.45f) tone_bonus = 0.45f;
                    score += tone_bonus;
                }
                if (component_luma_std > 16.0f && area <= 6) {
                    float std_penalty = (component_luma_std - 16.0f) * 0.06f;
                    if (fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                        std_penalty *= 1.5f;
                    }
                    score -= std_penalty;
                }
                if (homogeneous_mass_count >= ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_COUNT &&
                    homogeneous_mass_frac >= ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) {
                    float mass_penalty = 1.0f +
                        ((float)(homogeneous_mass_count - ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_COUNT) * 0.12f);
                    if (homogeneous_mass_frac > ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) {
                        mass_penalty +=
                            (homogeneous_mass_frac - ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) * 4.0f;
                    }
                    if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_HARD_MAX) {
                        mass_penalty *= 1.25f;
                    }
                    score -= mass_penalty;
                }
            }
            bool veto_fragment =
                area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_FRAGMENT_FILL_VETO &&
                max_span_frac >= ANOMALY_MOTION_COMPONENT_FRAGMENT_SPAN_VETO;
            bool hard_veto_fragment =
                area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_HARD_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO &&
                max_span_frac >= ANOMALY_MOTION_COMPONENT_FRAGMENT_SPAN_VETO;
            if (hard_veto_fragment) {
                if (curr_luma == NULL || tone_coherence < (ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE + 0.35f)) {
                    score = 0.0f;
                }
            } else if (veto_fragment) {
                if (curr_luma == NULL || tone_coherence < ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE) {
                    score *= 0.18f;
                }
            }
            if (curr_luma != NULL &&
                homogeneous_mass_count >= ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_COUNT &&
                homogeneous_mass_frac >= ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_FRAC) {
                score *= 0.10f;
            }
            if (component_area_frac > ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) {
                float t = (component_area_frac - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_AREA_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 3.2f * t;
            } else {
                float compact_bonus = 1.0f - (component_area_frac / ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (compact_bonus < 0.0f) compact_bonus = 0.0f;
                score += 0.35f * compact_bonus;
            }
            if (footprint_area_frac > ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) {
                float t = (footprint_area_frac - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_AREA_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 2.1f * t;
            }
            if (max_span_frac > ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) {
                float t = (max_span_frac - ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_SPAN_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 2.8f * t;
            }
            if (area >= 2 &&
                area <= 8 &&
                fill_ratio >= ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO &&
                aspect <= ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) {
                float compact_blob_bonus = 0.18f * (float)(area - 1);
                if (compact_blob_bonus > 0.85f) compact_blob_bonus = 0.85f;
                score += compact_blob_bonus;
            }

            for (int i = 0; i < tail; i++) {
                int idx = queue[i];
                float excess = motion_z_map[idx] - 1.0f;
                if (excess <= 0.0f) continue;
                selection_map[idx] = score + 0.08f * (excess - mean_excess);
                if (component_area_frac_map != NULL) component_area_frac_map[idx] = component_area_frac;
                if (component_span_frac_map != NULL) component_span_frac_map[idx] = max_span_frac;
                if (component_fill_ratio_map != NULL) component_fill_ratio_map[idx] = fill_ratio;
            }
            component_id++;
        }
    }

    free(component_map);
    free(queue);
    free(mass_seen);
}

static void choose_best_dark_patch(
        const float *selection_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        float       *best_score_out,
        int         *best_x_out,
        int         *best_y_out) {
    if (best_score_out != NULL) *best_score_out = -1.0f;
    if (best_x_out != NULL) *best_x_out = 0;
    if (best_y_out != NULL) *best_y_out = 0;
    if (selection_map == NULL || sg_w <= 0 || sg_h <= 0) return;

    float best_score = -1.0f;
    int best_sx = 0, best_sy = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            float score = selection_map[sy * sg_w + sx];
            if (score > best_score) {
                best_score = score;
                best_sx = sx;
                best_sy = sy;
            }
        }
    }

    if (best_score_out != NULL) *best_score_out = best_score;
    if (best_x_out != NULL) *best_x_out = roi_x0 + best_sx * sample_step;
    if (best_y_out != NULL) *best_y_out = roi_y0 + best_sy * sample_step;
}

static void maybe_insert_top_candidate(
        anomaly_debug_candidate_t *candidates,
        int                      *count,
        int                       max_count,
        int                       pixel_x,
        int                       pixel_y,
        float                     x_norm,
        float                     y_norm,
        float                     spatial_score,
        float                     temporal_score,
        float                     combined_score) {
    if (candidates == NULL || count == NULL || max_count <= 0 || combined_score <= 0.0f) return;

    int insert_at = *count;
    if (insert_at > max_count) insert_at = max_count;
    for (int i = 0; i < *count && i < max_count; i++) {
        if (combined_score > candidates[i].combined_score) {
            insert_at = i;
            break;
        }
    }
    if (insert_at >= max_count) return;

    int limit = *count < max_count ? *count : (max_count - 1);
    for (int i = limit; i > insert_at; i--) {
        candidates[i] = candidates[i - 1];
    }
    if (*count < max_count) (*count)++;

    anomaly_debug_candidate_t *slot = &candidates[insert_at];
    slot->valid = true;
    slot->pixel_x = pixel_x;
    slot->pixel_y = pixel_y;
    slot->x_norm = x_norm;
    slot->y_norm = y_norm;
    slot->spatial_score = spatial_score;
    slot->temporal_score = temporal_score;
    slot->combined_score = combined_score;
}

static void draw_rgba_hline(uint8_t *rgba, int rgba_stride,
                            int width, int height,
                            int x0, int x1, int y,
                            uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (y < 0 || y >= height) return;
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    x0 = clamp_i32(x0, 0, width - 1);
    x1 = clamp_i32(x1, 0, width - 1);
    uint8_t *row = rgba + (y * rgba_stride);
    for (int x = x0; x <= x1; x++) {
        uint8_t *px = row + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

static void draw_rgba_vline(uint8_t *rgba, int rgba_stride,
                            int width, int height,
                            int y0, int y1, int x,
                            uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (x < 0 || x >= width) return;
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    y0 = clamp_i32(y0, 0, height - 1);
    y1 = clamp_i32(y1, 0, height - 1);
    for (int y = y0; y <= y1; y++) {
        uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

static void draw_rgba_circle(uint8_t *rgba, int rgba_stride,
                             int width, int height,
                             int cx, int cy, int radius, int stroke,
                             uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0 || radius <= 0 || stroke <= 0) return;
    for (int band = 0; band < stroke; band++) {
        int rr = radius + band;
        int x = rr;
        int y = 0;
        int err = 1 - x;
        while (x >= y) {
            int points[8][2] = {
                {cx + x, cy + y}, {cx + y, cy + x},
                {cx - y, cy + x}, {cx - x, cy + y},
                {cx - x, cy - y}, {cx - y, cy - x},
                {cx + y, cy - x}, {cx + x, cy - y},
            };
            for (int i = 0; i < 8; i++) {
                int px = points[i][0];
                int py = points[i][1];
                if (px < 0 || px >= width || py < 0 || py >= height) continue;
                uint8_t *dst = rgba + (py * rgba_stride) + (px * 4);
                dst[0] = r; dst[1] = g; dst[2] = b; dst[3] = 0xFF;
            }
            y++;
            if (err < 0) {
                err += 2 * y + 1;
            } else {
                x--;
                err += 2 * (y - x + 1);
            }
        }
    }
}

static inline double rgba_luma_at(const uint8_t *px) {
    return (0.2126 * (double)px[0]) + (0.7152 * (double)px[1]) + (0.0722 * (double)px[2]);
}

static bool row_has_active_content_range(const uint8_t *rgba, int rgba_stride,
                                         int width, int height, int y,
                                         int x0, int x1) {
    if (rgba == NULL || y < 0 || y >= height || width <= 0) return false;
    if (x0 < 0) x0 = 0;
    if (x1 >= width) x1 = width - 1;
    if (x1 <= x0) return false;
    const uint8_t *row = rgba + (y * rgba_stride);
    double min_luma = 0.0;
    double max_luma = 0.0;
    int textured_samples = 0;
    int sample_count = 0;
    bool first = true;
    int span = x1 - x0 + 1;
    int step = span > 320 ? 2 : 1;
    double prev_luma = 0.0;
    bool prev_valid = false;
    for (int x = x0; x <= x1; x += step) {
        double luma = rgba_luma_at(row + (x * 4));
        if (first) {
            min_luma = max_luma = luma;
            first = false;
        } else {
            if (luma < min_luma) min_luma = luma;
            if (luma > max_luma) max_luma = luma;
        }
        if (prev_valid && fabs(luma - prev_luma) >= 10.0) {
            textured_samples++;
        }
        prev_luma = luma;
        prev_valid = true;
        sample_count++;
    }
    if ((max_luma - min_luma) < 8.0) return false;
    if (sample_count <= 1) return false;
    double textured_fraction = (double)textured_samples / (double)(sample_count - 1);
    return textured_fraction >= 0.10;
}

static bool col_has_active_content_range(const uint8_t *rgba, int rgba_stride,
                                         int width, int height, int x,
                                         int y0, int y1) {
    if (rgba == NULL || x < 0 || x >= width || height <= 0) return false;
    if (y0 < 0) y0 = 0;
    if (y1 >= height) y1 = height - 1;
    if (y1 <= y0) return false;
    double min_luma = 0.0;
    double max_luma = 0.0;
    int textured_samples = 0;
    int sample_count = 0;
    bool first = true;
    int span = y1 - y0 + 1;
    int step = span > 320 ? 2 : 1;
    double prev_luma = 0.0;
    bool prev_valid = false;
    for (int y = y0; y <= y1; y += step) {
        const uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
        double luma = rgba_luma_at(px);
        if (first) {
            min_luma = max_luma = luma;
            first = false;
        } else {
            if (luma < min_luma) min_luma = luma;
            if (luma > max_luma) max_luma = luma;
        }
        if (prev_valid && fabs(luma - prev_luma) >= 10.0) {
            textured_samples++;
        }
        prev_luma = luma;
        prev_valid = true;
        sample_count++;
    }
    if ((max_luma - min_luma) < 8.0) return false;
    if (sample_count <= 1) return false;
    double textured_fraction = (double)textured_samples / (double)(sample_count - 1);
    return textured_fraction >= 0.10;
}

typedef bool (*active_span_fn)(const uint8_t *rgba, int rgba_stride,
                               int width, int height, int index,
                               int aux0, int aux1);

static void detect_best_active_span(const uint8_t *rgba, int rgba_stride,
                                    int width, int height,
                                    int length, int aux0, int aux1,
                                    active_span_fn is_active,
                                    int *start_out, int *end_out) {
    int best_start = 0;
    int best_end = length - 1;
    int best_len = -1;
    int center = length / 2;
    bool best_contains_center = false;
    int best_center_distance = length;

    int run_start = -1;
    for (int i = 0; i <= length; i++) {
        bool active = (i < length) && is_active(rgba, rgba_stride, width, height, i, aux0, aux1);
        if (active) {
            if (run_start < 0) run_start = i;
            continue;
        }
        if (run_start < 0) continue;
        int run_end = i - 1;
        int run_len = run_end - run_start + 1;
        bool contains_center = (run_start <= center && center <= run_end);
        int run_center = (run_start + run_end) / 2;
        int center_distance = abs(run_center - center);
        bool better = false;
        if (best_len < 0) {
            better = true;
        } else if (contains_center != best_contains_center) {
            better = contains_center;
        } else if (run_len != best_len) {
            better = run_len > best_len;
        } else if (center_distance != best_center_distance) {
            better = center_distance < best_center_distance;
        }
        if (better) {
            best_start = run_start;
            best_end = run_end;
            best_len = run_len;
            best_contains_center = contains_center;
            best_center_distance = center_distance;
        }
        run_start = -1;
    }

    if (best_len < 0) {
        best_start = 0;
        best_end = length - 1;
    }
    if (start_out) *start_out = best_start;
    if (end_out) *end_out = best_end;
}

static bool tile_has_active_content(const uint8_t *rgba, int rgba_stride,
                                    int width, int height,
                                    int x0, int y0, int x1, int y1) {
    if (rgba == NULL || width <= 0 || height <= 0) return false;
    x0 = clamp_i32(x0, 0, width - 1);
    x1 = clamp_i32(x1, 0, width - 1);
    y0 = clamp_i32(y0, 0, height - 1);
    y1 = clamp_i32(y1, 0, height - 1);
    if (x1 <= x0 || y1 <= y0) return false;

    double min_luma = 0.0;
    double max_luma = 0.0;
    double sum_luma = 0.0;
    int sample_count = 0;
    int horiz_edges = 0;
    int horiz_pairs = 0;
    int vert_edges = 0;
    int vert_pairs = 0;
    bool first = true;
    int span_w = x1 - x0 + 1;
    int span_h = y1 - y0 + 1;
    int step_x = span_w > 48 ? 2 : 1;
    int step_y = span_h > 48 ? 2 : 1;

    for (int y = y0; y <= y1; y += step_y) {
        const uint8_t *row = rgba + (y * rgba_stride);
        double prev_luma = 0.0;
        bool prev_valid = false;
        for (int x = x0; x <= x1; x += step_x) {
            double luma = rgba_luma_at(row + (x * 4));
            if (first) {
                min_luma = max_luma = luma;
                first = false;
            } else {
                if (luma < min_luma) min_luma = luma;
                if (luma > max_luma) max_luma = luma;
            }
            sum_luma += luma;
            sample_count++;
            if (prev_valid) {
                horiz_pairs++;
                if (fabs(luma - prev_luma) >= 10.0) horiz_edges++;
            }
            prev_luma = luma;
            prev_valid = true;
        }
    }
    for (int x = x0; x <= x1; x += step_x) {
        double prev_luma = 0.0;
        bool prev_valid = false;
        for (int y = y0; y <= y1; y += step_y) {
            const uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
            double luma = rgba_luma_at(px);
            if (prev_valid) {
                vert_pairs++;
                if (fabs(luma - prev_luma) >= 10.0) vert_edges++;
            }
            prev_luma = luma;
            prev_valid = true;
        }
    }

    if (sample_count <= 0) return false;
    double mean_luma = sum_luma / (double)sample_count;
    double luma_range = max_luma - min_luma;
    double horiz_fraction = (horiz_pairs > 0) ? ((double)horiz_edges / (double)horiz_pairs) : 0.0;
    double vert_fraction = (vert_pairs > 0) ? ((double)vert_edges / (double)vert_pairs) : 0.0;

    if (luma_range < 12.0) return false;
    if (mean_luma < 28.0 || mean_luma > 245.0) return false;
    return horiz_fraction >= 0.08 && vert_fraction >= 0.08;
}

static bool detect_active_content_bounds_tiles(const uint8_t *rgba, int rgba_stride,
                                               int width, int height,
                                               int *x0_out, int *y0_out,
                                               int *x1_out, int *y1_out) {
    if (rgba == NULL || width <= 0 || height <= 0) return false;
    int tile_size = clamp_i32(((width < height) ? width : height) / 18, 16, 48);
    int tiles_x = (width + tile_size - 1) / tile_size;
    int tiles_y = (height + tile_size - 1) / tile_size;
    if (tiles_x <= 0 || tiles_y <= 0) return false;

    size_t tile_count = (size_t)tiles_x * (size_t)tiles_y;
    uint8_t *active = (uint8_t *)calloc(tile_count, sizeof(uint8_t));
    uint8_t *visited = (uint8_t *)calloc(tile_count, sizeof(uint8_t));
    int *queue = (int *)malloc(tile_count * sizeof(int));
    if (active == NULL || visited == NULL || queue == NULL) {
        free(active);
        free(visited);
        free(queue);
        return false;
    }

    for (int ty = 0; ty < tiles_y; ty++) {
        int py0 = ty * tile_size;
        int py1 = clamp_i32(((ty + 1) * tile_size) - 1, 0, height - 1);
        for (int tx = 0; tx < tiles_x; tx++) {
            int px0 = tx * tile_size;
            int px1 = clamp_i32(((tx + 1) * tile_size) - 1, 0, width - 1);
            if (tile_has_active_content(rgba, rgba_stride, width, height, px0, py0, px1, py1)) {
                active[(ty * tiles_x) + tx] = 1u;
            }
        }
    }

    int best_tx0 = 0, best_ty0 = 0, best_tx1 = tiles_x - 1, best_ty1 = tiles_y - 1;
    int best_area = -1;
    bool best_contains_center = false;
    double best_center_distance = (double)(tiles_x + tiles_y);

    int center_tx = tiles_x / 2;
    int center_ty = tiles_y / 2;
    for (int ty = 0; ty < tiles_y; ty++) {
        for (int tx = 0; tx < tiles_x; tx++) {
            int start_index = (ty * tiles_x) + tx;
            if (!active[start_index] || visited[start_index]) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start_index;
            visited[start_index] = 1u;
            int comp_tx0 = tx, comp_ty0 = ty, comp_tx1 = tx, comp_ty1 = ty;
            int comp_tiles = 0;

            while (head < tail) {
                int index = queue[head++];
                int cx = index % tiles_x;
                int cy = index / tiles_x;
                comp_tiles++;
                if (cx < comp_tx0) comp_tx0 = cx;
                if (cx > comp_tx1) comp_tx1 = cx;
                if (cy < comp_ty0) comp_ty0 = cy;
                if (cy > comp_ty1) comp_ty1 = cy;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 0 || nx >= tiles_x || ny < 0 || ny >= tiles_y) continue;
                        int next_index = (ny * tiles_x) + nx;
                        if (!active[next_index] || visited[next_index]) continue;
                        visited[next_index] = 1u;
                        queue[tail++] = next_index;
                    }
                }
            }

            int span_tiles_x = comp_tx1 - comp_tx0 + 1;
            int span_tiles_y = comp_ty1 - comp_ty0 + 1;
            int comp_area = span_tiles_x * span_tiles_y;
            bool contains_center =
                (comp_tx0 <= center_tx && center_tx <= comp_tx1 &&
                 comp_ty0 <= center_ty && center_ty <= comp_ty1);
            double comp_center_x = 0.5 * (double)(comp_tx0 + comp_tx1);
            double comp_center_y = 0.5 * (double)(comp_ty0 + comp_ty1);
            double dx = comp_center_x - (double)center_tx;
            double dy = comp_center_y - (double)center_ty;
            double center_distance = sqrt((dx * dx) + (dy * dy));
            bool better = false;
            if (best_area < 0) {
                better = true;
            } else if (contains_center != best_contains_center) {
                better = contains_center;
            } else if (comp_area != best_area) {
                better = comp_area > best_area;
            } else if (center_distance != best_center_distance) {
                better = center_distance < best_center_distance;
            }
            if (better) {
                best_tx0 = comp_tx0;
                best_ty0 = comp_ty0;
                best_tx1 = comp_tx1;
                best_ty1 = comp_ty1;
                best_area = comp_area;
                best_contains_center = contains_center;
                best_center_distance = center_distance;
            }
        }
    }

    free(active);
    free(visited);
    free(queue);

    if (best_area < 0) return false;

    int x0 = best_tx0 * tile_size;
    int y0 = best_ty0 * tile_size;
    int x1 = clamp_i32(((best_tx1 + 1) * tile_size) - 1, 0, width - 1);
    int y1 = clamp_i32(((best_ty1 + 1) * tile_size) - 1, 0, height - 1);
    if ((x1 - x0 + 1) < width / 3 || (y1 - y0 + 1) < height / 3) {
        return false;
    }
    if (x0_out) *x0_out = x0;
    if (y0_out) *y0_out = y0;
    if (x1_out) *x1_out = x1;
    if (y1_out) *y1_out = y1;
    return true;
}

static void detect_active_content_bounds(const uint8_t *rgba, int rgba_stride,
                                         int width, int height,
                                         int *x0_out, int *y0_out,
                                         int *x1_out, int *y1_out) {
    int x0 = 0;
    int y0 = 0;
    int x1 = width - 1;
    int y1 = height - 1;
    if (!detect_active_content_bounds_tiles(rgba, rgba_stride, width, height,
                                            &x0, &y0, &x1, &y1)) {
        detect_best_active_span(rgba, rgba_stride, width, height,
                                height, 0, width - 1,
                                row_has_active_content_range,
                                &y0, &y1);
        detect_best_active_span(rgba, rgba_stride, width, height,
                                width, y0, y1,
                                col_has_active_content_range,
                                &x0, &x1);
        detect_best_active_span(rgba, rgba_stride, width, height,
                                height, x0, x1,
                                row_has_active_content_range,
                                &y0, &y1);
    }

    if ((x1 - x0 + 1) < width / 3 || (y1 - y0 + 1) < height / 3) {
        x0 = 0;
        y0 = 0;
        x1 = width - 1;
        y1 = height - 1;
    }
    if (x0_out) *x0_out = x0;
    if (y0_out) *y0_out = y0;
    if (x1_out) *x1_out = x1;
    if (y1_out) *y1_out = y1;
}

static void draw_hot_overlay_rgba(uint8_t *rgba, int rgba_stride,
                                  int width, int height,
                                  int thermal_polarity) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    int content_x0 = 0, content_y0 = 0, content_x1 = width - 1, content_y1 = height - 1;
    double sum_luma = 0.0;
    double hottest_luma = 0.0;
    int hottest_x = 0;
    int hottest_y = 0;
    bool black_hot = (thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    bool first = true;
    for (int y = content_y0; y <= content_y1; y++) {
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = content_x0; x <= content_x1; x++) {
            const uint8_t *px = row + (x * 4);
            double luma = rgba_luma_at(px);
            sum_luma += luma;
            if (first ||
                (black_hot && luma < hottest_luma) ||
                (!black_hot && luma > hottest_luma)) {
                hottest_luma = luma;
                hottest_x = x;
                hottest_y = y;
                first = false;
            }
        }
    }
    if (first) return;

    int content_width = content_x1 - content_x0 + 1;
    int content_height = content_y1 - content_y0 + 1;
    double mean_luma = sum_luma / (double)(content_width * content_height);
    bool hottest_is_hot = black_hot ? (hottest_luma < mean_luma) : (hottest_luma > mean_luma);
    if (!hottest_is_hot) return;

    int min_dim = width < height ? width : height;
    int vicinity = clamp_i32((int)lround((double)min_dim * 0.028), 10, 24);
    int hot_left = hottest_x;
    int hot_right = hottest_x;
    int hot_top = hottest_y;
    int hot_bottom = hottest_y;
    int hot_count = 0;
    for (int y = hottest_y - vicinity; y <= hottest_y + vicinity; y++) {
        if (y < content_y0 || y > content_y1) continue;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = hottest_x - vicinity; x <= hottest_x + vicinity; x++) {
            if (x < content_x0 || x > content_x1) continue;
            int dx = x - hottest_x;
            int dy = y - hottest_y;
            if ((dx * dx) + (dy * dy) > (vicinity * vicinity)) continue;
            const uint8_t *px = row + (x * 4);
            double luma = rgba_luma_at(px);
            bool is_hot = black_hot ? (luma < mean_luma) : (luma > mean_luma);
            if (!is_hot) continue;
            hot_count++;
            if (x < hot_left) hot_left = x;
            if (x > hot_right) hot_right = x;
            if (y < hot_top) hot_top = y;
            if (y > hot_bottom) hot_bottom = y;
        }
    }

    int center_x = (hot_left + hot_right) / 2;
    int center_y = (hot_top + hot_bottom) / 2;
    int half_w = (hot_right - hot_left + 1) / 2;
    int half_h = (hot_bottom - hot_top + 1) / 2;
    int content_radius = (int)ceil(sqrt((double)(half_w * half_w + half_h * half_h)));
    int padding = clamp_i32((int)lround((double)min_dim * 0.008), 4, 8);
    int radius = clamp_i32(content_radius + padding, 8, min_dim / 5);
    int stroke = clamp_i32(2 + (hot_count / 24), 2, 5);
    draw_rgba_circle(rgba, rgba_stride, width, height, center_x, center_y, radius, stroke, 0xFF, 0x30, 0x30);
}

static void append_anomaly_box(anomaly_box_t *boxes, int *box_count,
                               float center_x_norm, float center_y_norm,
                               float box_w_norm, float box_h_norm,
                               uint8_t r, uint8_t g, uint8_t b,
                               float weight) {
    if (boxes == NULL || box_count == NULL) return;
    if (*box_count >= ANOMALY_MAX_BOXES_PER_FRAME) return;
    float half_w = box_w_norm * 0.5f;
    float half_h = box_h_norm * 0.5f;
    float left   = clamp01f(center_x_norm - half_w);
    float right  = clamp01f(center_x_norm + half_w);
    float top    = clamp01f(center_y_norm - half_h);
    float bottom = clamp01f(center_y_norm + half_h);
    if (right <= left || bottom <= top) return;
    anomaly_box_t *slot = &boxes[*box_count];
    slot->left_norm   = left;
    slot->top_norm    = top;
    slot->right_norm  = right;
    slot->bottom_norm = bottom;
    slot->r = r; slot->g = g; slot->b = b;
    slot->draw_crosshair = 1u;
    slot->weight = weight < 0.0f ? 0.0f : (weight > 1.0f ? 1.0f : weight);
    *box_count += 1;
}

static void append_anomaly_rect(anomaly_box_t *boxes, int *box_count,
                                float left_norm, float top_norm,
                                float right_norm, float bottom_norm,
                                uint8_t r, uint8_t g, uint8_t b,
                                float weight,
                                bool draw_crosshair) {
    if (boxes == NULL || box_count == NULL) return;
    if (*box_count >= ANOMALY_MAX_OVERLAY_BOXES) return;
    float left = clamp01f(left_norm);
    float top = clamp01f(top_norm);
    float right = clamp01f(right_norm);
    float bottom = clamp01f(bottom_norm);
    if (right <= left || bottom <= top) return;
    anomaly_box_t *slot = &boxes[*box_count];
    slot->left_norm = left;
    slot->top_norm = top;
    slot->right_norm = right;
    slot->bottom_norm = bottom;
    slot->r = r; slot->g = g; slot->b = b;
    slot->draw_crosshair = draw_crosshair ? 1u : 0u;
    slot->weight = weight < 0.0f ? 0.0f : (weight > 1.0f ? 1.0f : weight);
    *box_count += 1;
}

static int assemble_anomaly_boxes(const anomaly_state_t *state,
                                  const anomaly_config_t *cfg,
                                  int motion_box_algorithm,
                                  anomaly_box_t *boxes,
                                  int max_boxes) {
    if (state == NULL || cfg == NULL || boxes == NULL || max_boxes <= 0) return 0;

    float box_side = sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f));
    box_side = (box_side < 0.02f) ? 0.02f : (box_side > 0.18f ? 0.18f : box_side);

    static const uint8_t algo_rgb[4][3] = {
        {0x2D, 0x6C, 0xFF},
        {0xF2, 0x30, 0x30},
        {0x23, 0xC5, 0x52},
        {0xFF, 0xE0, 0x3B},
    };
    static const float algo_box_scale[4] = {1.0f, 1.0f, 1.3f, 0.9f};
    int algo_bits[4] = {ANOMALY_ALGO_COLOR, ANOMALY_ALGO_THERMAL, motion_box_algorithm, ANOMALY_ALGO_PERSIST};
    bool saliency_primary =
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0;

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    int box_count = 0;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS && box_count < max_boxes; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || track->hit_count < min_hits) continue;
        int rgb_idx = 3;
        if (track->algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
        else if (track->algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
        else if (track->algorithm == motion_box_algorithm || track->algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
        float weight = clampf(0.25f + track->confidence + 0.05f * (float)track->hit_count, 0.25f, 1.0f);
        append_anomaly_box(
                boxes,
                &box_count,
                track->center_x_norm,
                track->center_y_norm,
                fmaxf(track->half_w_norm * 2.0f, box_side * 0.85f),
                fmaxf(track->half_h_norm * 2.0f, box_side * 0.85f),
                algo_rgb[rgb_idx][0],
                algo_rgb[rgb_idx][1],
                algo_rgb[rgb_idx][2],
                weight);
        if (box_count > 0) {
            boxes[box_count - 1].algorithm = track->algorithm;
        }
    }
    if (box_count > 0) return box_count;
    for (int ai = 0; ai < 4 && box_count < max_boxes; ai++) {
        if (saliency_primary && ai != 3) continue;
        if (!state->acc_active[ai]) continue;
        if (state->acc_hits[ai] < min_hits) continue;
        float weight = 0.35f + 0.13f * (float)(state->acc_hits[ai] - min_hits);
        if (weight > 1.0f) weight = 1.0f;
        float bw = box_side * algo_box_scale[ai];
        int cue_algorithm = ai == 3 ? state->saliency_display_algorithm : algo_bits[ai];
        int rgb_idx = ai;
        if (ai == 3) {
            if (cue_algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
            else if (cue_algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
            else if (cue_algorithm == motion_box_algorithm || cue_algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
            else rgb_idx = 3;
        }
        append_anomaly_box(
                boxes,
                &box_count,
                state->acc_cx[ai],
                state->acc_cy[ai],
                bw,
                bw,
                algo_rgb[rgb_idx][0],
                algo_rgb[rgb_idx][1],
                algo_rgb[rgb_idx][2],
                weight);
        if (box_count > 0) {
            boxes[box_count - 1].algorithm = algo_bits[ai];
        }
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0 && !saliency_primary) {
        for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS && box_count < max_boxes; ti++) {
            if (!state->saliency_aux_active[ti]) continue;
            if (state->saliency_aux_hits[ti] < min_hits) continue;
            float weight = 0.30f + 0.11f * (float)(state->saliency_aux_hits[ti] - min_hits);
            if (weight > 0.92f) weight = 0.92f;
            float bw = box_side * 0.82f;
            int cue_algorithm = state->saliency_aux_display_algorithm[ti];
            int rgb_idx = 3;
            if (cue_algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
            else if (cue_algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
            else if (cue_algorithm == motion_box_algorithm || cue_algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
            append_anomaly_box(
                    boxes,
                    &box_count,
                    state->saliency_aux_cx[ti],
                    state->saliency_aux_cy[ti],
                    bw,
                    bw,
                    algo_rgb[rgb_idx][0],
                    algo_rgb[rgb_idx][1],
                    algo_rgb[rgb_idx][2],
                    weight);
            if (box_count > 0) {
                boxes[box_count - 1].algorithm = ANOMALY_ALGO_PERSIST;
            }
        }
    }
    return box_count;
}

static void update_saliency_aux_track(
        anomaly_state_t *state,
        int              track_idx,
        float            raw_cx,
        float            raw_cy,
        float            gate,
        float            alpha) {
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS) return;
    int base_hold_frames = ANOMALY_ACC_HOLD_FRAMES;
    if (state->saliency_aux_hits[track_idx] >= 3) {
        base_hold_frames += ANOMALY_SALIENCY_SECONDARY_HOLD_BONUS;
    }
    if (raw_cx >= 0.0f && raw_cy >= 0.0f) {
        if (!state->saliency_aux_active[track_idx]) {
            state->saliency_aux_cx[track_idx] = raw_cx;
            state->saliency_aux_cy[track_idx] = raw_cy;
            state->saliency_aux_hits[track_idx] = 1;
            state->saliency_aux_hold[track_idx] = base_hold_frames;
            state->saliency_aux_active[track_idx] = true;
            return;
        }

        float ddx = raw_cx - state->saliency_aux_cx[track_idx];
        float ddy = raw_cy - state->saliency_aux_cy[track_idx];
        float dist = sqrtf(ddx * ddx + ddy * ddy);
        if (dist <= gate) {
            state->saliency_aux_cx[track_idx] += alpha * ddx;
            state->saliency_aux_cy[track_idx] += alpha * ddy;
            int h = state->saliency_aux_hits[track_idx] + 1;
            state->saliency_aux_hits[track_idx] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
        } else {
            state->saliency_aux_cx[track_idx] = raw_cx;
            state->saliency_aux_cy[track_idx] = raw_cy;
            state->saliency_aux_hits[track_idx] = 1;
        }
        state->saliency_aux_hold[track_idx] = base_hold_frames;
        return;
    }

    if (!state->saliency_aux_active[track_idx]) return;
    int hold = state->saliency_aux_hold[track_idx] - 1;
    if (hold <= 0) {
        state->saliency_aux_active[track_idx] = false;
        state->saliency_aux_hits[track_idx] = 0;
        state->saliency_aux_hold[track_idx] = 0;
    } else {
        state->saliency_aux_hold[track_idx] = hold;
    }
}

static void clear_primary_track(
        anomaly_state_t *state,
        int              track_idx) {
    if (state == NULL || track_idx < 0 || track_idx >= 4) return;
    state->acc_active[track_idx] = false;
    state->acc_hits[track_idx] = 0;
    state->acc_hold[track_idx] = 0;
    state->acc_presence_mask[track_idx] = 0u;
    state->acc_cx[track_idx] = 0.0f;
    state->acc_cy[track_idx] = 0.0f;
}

static void clear_saliency_aux_track_state(
        anomaly_state_t *state,
        int              track_idx) {
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS) return;
    state->saliency_aux_active[track_idx] = false;
    state->saliency_aux_hits[track_idx] = 0;
    state->saliency_aux_hold[track_idx] = 0;
    state->saliency_aux_cx[track_idx] = 0.0f;
    state->saliency_aux_cy[track_idx] = 0.0f;
    state->saliency_aux_display_algorithm[track_idx] = ANOMALY_ALGO_PERSIST;
}

static void clear_saliency_tracks(anomaly_state_t *state) {
    if (state == NULL) return;
    clear_primary_track(state, 3);
    state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        clear_saliency_aux_track_state(state, i);
    }
}

static void clear_all_roi_tracks(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int ai = 0; ai < 4; ai++) {
        clear_primary_track(state, ai);
    }
    state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        clear_saliency_aux_track_state(state, i);
    }
    clear_all_target_tracks(state);
}

static void age_roi_tracks_one_frame(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int ai = 0; ai < 4; ai++) {
        if (!state->acc_active[ai]) continue;
        int hold = state->acc_hold[ai] - 1;
        if (hold <= 0) {
            clear_primary_track(state, ai);
        } else {
            state->acc_hold[ai] = hold;
        }
    }
    for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
        if (!state->saliency_aux_active[ti]) continue;
        int hold = state->saliency_aux_hold[ti] - 1;
        if (hold <= 0) {
            clear_saliency_aux_track_state(state, ti);
        } else {
            state->saliency_aux_hold[ti] = hold;
        }
    }
}

typedef struct {
    bool  valid;
    int   algorithm;
    float center_x_norm;
    float center_y_norm;
    float half_w_norm;
    float half_h_norm;
    float support_radius_norm;
    float confidence;
} anomaly_target_observation_t;

static int find_best_target_track_match(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        const bool                         *matched_tracks) {
    if (state == NULL || obs == NULL || !obs->valid) return -1;
    float best_dist = ANOMALY_TARGET_MATCH_GATE;
    int best_idx = -1;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || (matched_tracks != NULL && matched_tracks[ti])) continue;
        float dx = obs->center_x_norm - track->center_x_norm;
        float dy = obs->center_y_norm - track->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        float gate = ANOMALY_TARGET_MATCH_GATE + 0.25f * track->support_radius_norm;
        if (dist > gate) continue;
        if (obs->algorithm != track->algorithm && dist > best_dist * 0.65f) continue;
        if (best_idx < 0 || dist < best_dist) {
            best_idx = ti;
            best_dist = dist;
        }
    }
    return best_idx;
}

static int allocate_target_track_slot(
        anomaly_state_t *state) {
    if (state == NULL) return -1;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        if (!state->target_tracks[ti].active) return ti;
    }
    int weakest_idx = 0;
    float weakest_score = state->target_tracks[0].confidence;
    for (int ti = 1; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        float score = state->target_tracks[ti].confidence - 0.10f * (float)state->target_tracks[ti].hit_count;
        if (score < weakest_score) {
            weakest_score = score;
            weakest_idx = ti;
        }
    }
    return weakest_idx;
}

static void predict_target_tracks_with_registration(
        anomaly_state_t                   *state,
        const anomaly_registration_model_t *registration,
        anomaly_registration_health_t      registration_health,
        bool                               scene_discontinuity) {
    if (state == NULL) return;
    if (scene_discontinuity ||
        registration_health == ANOMALY_REG_HEALTH_INVALID ||
        registration_health == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        clear_all_target_tracks(state);
        return;
    }
    if (!registration_model_valid(registration)) return;

    float reg_quality = registration_health_confidence(registration_health);
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active) continue;
        float nx = 0.0f;
        float ny = 0.0f;
        if (!registration_invert_point(registration,
                                       track->center_x_norm,
                                       track->center_y_norm,
                                       &nx,
                                       &ny)) {
            track->forced_revisit = true;
            continue;
        }
        track->center_x_norm = clamp01f(nx);
        track->center_y_norm = clamp01f(ny);
        track->last_registration_quality = reg_quality;
        if (!track->fresh_observation) {
            track->forced_revisit = true;
        }
    }
}

static void update_target_tracks_from_observations(
        anomaly_state_t                    *state,
        const anomaly_target_observation_t *observations,
        int                                 observation_count,
        anomaly_registration_health_t       registration_health) {
    if (state == NULL) return;

    bool matched_tracks[ANOMALY_MAX_TARGET_TRACKS];
    memset(matched_tracks, 0, sizeof(matched_tracks));
    float reg_quality = registration_health_confidence(registration_health);
    bool had_any_target_tracks = state->next_target_track_id > 1;

    for (int oi = 0; oi < observation_count; oi++) {
        const anomaly_target_observation_t *obs = &observations[oi];
        if (!obs->valid) continue;
        int track_idx = find_best_target_track_match(state, obs, matched_tracks);
        if (track_idx < 0) {
            track_idx = allocate_target_track_slot(state);
            if (track_idx < 0) continue;
            clear_target_track(&state->target_tracks[track_idx]);
            state->target_tracks[track_idx].active = true;
            state->target_tracks[track_idx].id = state->next_target_track_id++;
            if (state->next_target_track_id <= 0) state->next_target_track_id = 1;
        }

        anomaly_target_track_t *track = &state->target_tracks[track_idx];
        track->active = true;
        track->algorithm = obs->algorithm;
        track->center_x_norm = obs->center_x_norm;
        track->center_y_norm = obs->center_y_norm;
        track->half_w_norm = obs->half_w_norm;
        track->half_h_norm = obs->half_h_norm;
        track->support_radius_norm = obs->support_radius_norm;
        track->confidence = clampf(fmaxf(track->confidence, obs->confidence) +
                                   ANOMALY_TARGET_CONFIDENCE_HIT_GAIN,
                                   0.0f,
                                   1.0f);
        track->hit_count++;
        track->miss_count = 0;
        track->hold_count = ANOMALY_ACC_HOLD_FRAMES;
        track->last_registration_quality = reg_quality;
        track->forced_revisit = true;
        track->fresh_observation = true;
        matched_tracks[track_idx] = true;
    }

    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || matched_tracks[ti]) continue;
        track->fresh_observation = false;
        track->miss_count++;
        track->hold_count--;
        track->confidence = clampf(track->confidence - ANOMALY_TARGET_CONFIDENCE_MISS_DECAY, 0.0f, 1.0f);
        track->forced_revisit =
            registration_health >= ANOMALY_REG_HEALTH_SOFT_DEGRADED &&
            track->miss_count <= ANOMALY_TARGET_MAX_CARRIED_MISSES;
        if (track->hold_count <= 0 ||
            track->miss_count > ANOMALY_TARGET_MAX_CARRIED_MISSES ||
            registration_health <= ANOMALY_REG_HEALTH_HARD_DEGRADED ||
            track->confidence < 0.05f) {
            clear_target_track(track);
        }
    }
    if (had_any_target_tracks &&
        observation_count == 0 &&
        target_revisit_track_count(state) == 0) {
        clear_all_roi_tracks(state);
    }
}

static bool find_saliency_local_support(
        const anomaly_state_t *state,
        int                    track_idx,
        const float           *patch_selection_map,
        int                    sg_w,
        int                    sg_h,
        int                    roi_x0,
        int                    roi_y0,
        int                    sample_step,
        int                    width,
        int                    height,
        float                 *x_norm_out,
        float                 *y_norm_out,
        float                 *score_out) {
    if (x_norm_out != NULL) *x_norm_out = -1.0f;
    if (y_norm_out != NULL) *y_norm_out = -1.0f;
    if (score_out != NULL) *score_out = -1.0f;
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS ||
        !state->saliency_aux_active[track_idx] || patch_selection_map == NULL ||
        sg_w <= 0 || sg_h <= 0 || sample_step <= 0) {
        return false;
    }

    float fw = (float)(width > 1 ? width - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);
    int track_x = clamp_i32((int)lroundf(state->saliency_aux_cx[track_idx] * fw), roi_x0, roi_x0 + (sg_w - 1) * sample_step);
    int track_y = clamp_i32((int)lroundf(state->saliency_aux_cy[track_idx] * fh), roi_y0, roi_y0 + (sg_h - 1) * sample_step);
    int track_sx = clamp_i32((track_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
    int track_sy = clamp_i32((track_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);

    float best_score = -1.0f;
    int best_sx = track_sx;
    int best_sy = track_sy;
    for (int sy = track_sy - ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS;
         sy <= track_sy + ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS; sy++) {
        if (sy < 0 || sy >= sg_h) continue;
        for (int sx = track_sx - ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS;
             sx <= track_sx + ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS; sx++) {
            if (sx < 0 || sx >= sg_w) continue;
            float score = patch_selection_map[sy * sg_w + sx];
            if (score > best_score) {
                best_score = score;
                best_sx = sx;
                best_sy = sy;
            }
        }
    }
    if (best_score <= 0.0f) return false;

    int best_x = roi_x0 + best_sx * sample_step;
    int best_y = roi_y0 + best_sy * sample_step;
    if (x_norm_out != NULL) *x_norm_out = (float)best_x / fw;
    if (y_norm_out != NULL) *y_norm_out = (float)best_y / fh;
    if (score_out != NULL) *score_out = best_score;
    return true;
}

static int classify_saliency_display_algorithm(
        const float *saliency_spatial_map,
        const float *saliency_color_map,
        const float *saliency_motion_map,
        const float *saliency_registration_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        delta_mean,
        float        delta_norm) {
    if (sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) return ANOMALY_ALGO_PERSIST;
    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float registration = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
    float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
    float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
    float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
    float thermal_temporal = 0.0f;
    if (bg_valid && bg_luma != NULL && sg_luma != NULL) {
        float bg = bg_luma[idx];
        float lum = (float)sg_luma[idx];
        float delta = black_hot ? (bg - lum) : (lum - bg);
        if (delta >= thermal_min_delta) {
            thermal_temporal = (float)((delta - delta_mean) / delta_norm);
        }
    }

    float thermal_evidence = fmaxf(thermal_spatial > 0.0f ? thermal_spatial : 0.0f,
                                   thermal_temporal > 0.0f ? thermal_temporal : 0.0f);
    float color_evidence = color_support > 0.0f ? (0.60f * color_support) : 0.0f;
    float motion_evidence = motion_support > 0.0f
        ? ((bg_valid ? 0.60f : 0.45f) * motion_support)
        : 0.0f;
    thermal_evidence *= registration;
    color_evidence *= registration;
    motion_evidence *= registration;

    float best = thermal_evidence;
    float second = color_evidence > motion_evidence ? color_evidence : motion_evidence;
    int algorithm = ANOMALY_ALGO_THERMAL;
    if (color_evidence > best) {
        second = thermal_evidence > motion_evidence ? thermal_evidence : motion_evidence;
        best = color_evidence;
        algorithm = ANOMALY_ALGO_COLOR;
    }
    if (motion_evidence > best) {
        second = thermal_evidence > color_evidence ? thermal_evidence : color_evidence;
        best = motion_evidence;
        algorithm = ANOMALY_ALGO_MOTION;
    }
    if (best <= 0.0f) return ANOMALY_ALGO_PERSIST;
    if (second > 0.0f && best < second * 1.12f) return ANOMALY_ALGO_PERSIST;
    return algorithm;
}

static void draw_anomaly_boxes_rgba(uint8_t *rgba, int rgba_stride,
                                    int width, int height,
                                    const anomaly_box_t *boxes, int box_count) {
    if (rgba == NULL || boxes == NULL || width <= 0 || height <= 0 || box_count <= 0) return;
    int min_dim    = (width < height) ? width : height;
    int stroke_max = clamp_i32((int)lroundf((double)min_dim * 0.006), 2, 8);

    for (int i = 0; i < box_count; i++) {
        const anomaly_box_t *box = &boxes[i];
        int stroke = clamp_i32((int)lroundf(stroke_max * (double)box->weight), 1, stroke_max);
        int left   = clamp_i32((int)lroundf(box->left_norm   * (float)(width  - 1)), 0, width  - 1);
        int right  = clamp_i32((int)lroundf(box->right_norm  * (float)(width  - 1)), 0, width  - 1);
        int top    = clamp_i32((int)lroundf(box->top_norm    * (float)(height - 1)), 0, height - 1);
        int bottom = clamp_i32((int)lroundf(box->bottom_norm * (float)(height - 1)), 0, height - 1);
        if (right <= left || bottom <= top) continue;

        if (box->draw_crosshair != 0u) {
            int cx = (left + right) / 2;
            int cy = (top  + bottom) / 2;
            int box_half_w = (right - left) / 2;
            int box_half_h = (bottom - top) / 2;
            int max_gap_half_x = box_half_w - stroke;
            int max_gap_half_y = box_half_h - stroke;
            int gap_half_x = (max_gap_half_x <= stroke * 2)
                    ? stroke
                    : clamp_i32(box_half_w / 3, stroke * 2, max_gap_half_x);
            int gap_half_y = (max_gap_half_y <= stroke * 2)
                    ? stroke
                    : clamp_i32(box_half_h / 3, stroke * 2, max_gap_half_y);
            for (int t = 0; t < stroke; t++) {
                int horiz_y = cy - (stroke / 2) + t;
                int vert_x  = cx - (stroke / 2) + t;
                draw_rgba_hline(rgba, rgba_stride, width, height, left, cx - gap_half_x, horiz_y, box->r, box->g, box->b);
                draw_rgba_hline(rgba, rgba_stride, width, height, cx + gap_half_x, right, horiz_y, box->r, box->g, box->b);
                draw_rgba_vline(rgba, rgba_stride, width, height, top, cy - gap_half_y, vert_x, box->r, box->g, box->b);
                draw_rgba_vline(rgba, rgba_stride, width, height, cy + gap_half_y, bottom, vert_x, box->r, box->g, box->b);
            }
        } else {
            for (int t = 0; t < stroke; t++) {
                int top_y   = top    + t;
                int bottom_y = bottom - t;
                int left_x  = left   + t;
                int right_x = right  - t;
                if (top_y <= bottom_y) {
                    draw_rgba_hline(rgba, rgba_stride, width, height, left, right, top_y, box->r, box->g, box->b);
                    if (bottom_y != top_y) {
                        draw_rgba_hline(rgba, rgba_stride, width, height, left, right, bottom_y, box->r, box->g, box->b);
                    }
                }
                if (left_x <= right_x) {
                    draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, left_x, box->r, box->g, box->b);
                    if (right_x != left_x) {
                        draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, right_x, box->r, box->g, box->b);
                    }
                }
            }
        }
    }
}

// ── Similarity transform ────────────────────────────────────────��──────────

// Fits a 2-D similarity transform (rotation + isotropic scale + translation)
// from N point correspondences src→dst in normalized [0,1] frame coordinates.
// Closed-form least-squares; no external library required.
// Returns identity with valid=false when degenerate (n<2 or anchors collinear).
similarity_2d_t fit_similarity_2d(
        const float *src_x, const float *src_y,
        const float *dst_x, const float *dst_y,
        int n) {
    similarity_2d_t r = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, false};
    if (n < 2) return r;

    double S = 0.0, Sx = 0.0, Sy = 0.0;
    double Sdx = 0.0, Sdy = 0.0, Sxdx = 0.0, Srot = 0.0;
    for (int i = 0; i < n; i++) {
        double xi = src_x[i], yi = src_y[i];
        double dxi = dst_x[i], dyi = dst_y[i];
        S    += xi*xi + yi*yi;
        Sx   += xi;    Sy   += yi;
        Sdx  += dxi;   Sdy  += dyi;
        Sxdx += xi*dxi + yi*dyi;
        Srot += xi*dyi - yi*dxi;
    }
    // Normal-equation denominator: N*Σ(x²+y²) - (Σx)²-(Σy)²
    double D = (double)n * S - (Sx*Sx + Sy*Sy);
    if (fabs(D) < 1e-10) return r;   // degenerate anchor geometry

    double a  = ((double)n * Sxdx - Sx*Sdx - Sy*Sdy) / D;
    double b  = ((double)n * Srot  + Sy*Sdx - Sx*Sdy) / D;
    double tx = (Sdx - Sx*a + Sy*b) / (double)n;
    double ty = (Sdy - Sy*a - Sx*b) / (double)n;

    double res = 0.0;
    for (int i = 0; i < n; i++) {
        double ex = a*src_x[i] - b*src_y[i] + tx - dst_x[i];
        double ey = b*src_x[i] + a*src_y[i] + ty - dst_y[i];
        res += sqrt(ex*ex + ey*ey);
    }
    r.a = (float)a;   r.b  = (float)b;
    r.tx = (float)tx; r.ty = (float)ty;
    r.mean_residual = (float)(res / (double)n);
    r.valid = true;
    return r;
}

static anomaly_registration_model_t estimate_gmv_registration_model(
        const uint8_t        *curr_luma,
        const anomaly_state_t *state,
        int                   width,
        int                   height,
        int                   roi_x0,
        int                   roi_x1,
        int                   roi_y0,
        int                   roi_y1,
        int                   motion_sample_step,
        int                   motion_step,
        int                   motion_w,
        int                   motion_h) {
    anomaly_registration_model_t model = make_registration_model(
        ANOMALY_REGISTRATION_GMV,
        motion_sample_step,
        motion_step);
    model.debug_valid = (curr_luma != NULL &&
        state != NULL &&
        state->prev_registration_luma != NULL &&
        state->prev_registration_luma_width == motion_w &&
        state->prev_registration_luma_height == motion_h);

    if (!model.debug_valid) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE;
        return model;
    }

    int ph = ANOMALY_GMV_PATCH_HALF;
    int sr = ANOMALY_GMV_SEARCH_RADIUS;

    int roi_mgx0 = roi_x0 / motion_step;
    int roi_mgx1 = (roi_x1 - 1) / motion_step;
    int roi_mgy0 = roi_y0 / motion_step;
    int roi_mgy1 = (roi_y1 - 1) / motion_step;
    roi_mgx0 = roi_mgx0 < 0 ? 0 : roi_mgx0;
    roi_mgx1 = roi_mgx1 >= motion_w ? motion_w - 1 : roi_mgx1;
    roi_mgy0 = roi_mgy0 < 0 ? 0 : roi_mgy0;
    roi_mgy1 = roi_mgy1 >= motion_h ? motion_h - 1 : roi_mgy1;

    float fw = (float)(width > 1 ? width - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);

    int anchor_dx[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    int anchor_dy[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    int anchor_ax[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    int anchor_ay[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    int anchor_count = 0;

    for (int gy = 0; gy < ANOMALY_GMV_ZONE_GRID; gy++) {
        for (int gx = 0; gx < ANOMALY_GMV_ZONE_GRID; gx++) {
            int zx0 = roi_mgx0 + (roi_mgx1 - roi_mgx0) * gx / ANOMALY_GMV_ZONE_GRID;
            int zx1 = roi_mgx0 + (roi_mgx1 - roi_mgx0) * (gx + 1) / ANOMALY_GMV_ZONE_GRID;
            int zy0 = roi_mgy0 + (roi_mgy1 - roi_mgy0) * gy / ANOMALY_GMV_ZONE_GRID;
            int zy1 = roi_mgy0 + (roi_mgy1 - roi_mgy0) * (gy + 1) / ANOMALY_GMV_ZONE_GRID;
            zx0 = clamp_i32(zx0, ph + sr, motion_w - 1 - ph - sr);
            zx1 = clamp_i32(zx1, ph + sr, motion_w - 1 - ph - sr);
            zy0 = clamp_i32(zy0, ph + sr, motion_h - 1 - ph - sr);
            zy1 = clamp_i32(zy1, ph + sr, motion_h - 1 - ph - sr);
            if (zx1 < zx0 || zy1 < zy0) continue;

            int ax = -1, ay = -1;
            int best_feature = -1;
            for (int cy = zy0; cy <= zy1; cy++) {
                for (int cx = zx0; cx <= zx1; cx++) {
                    int feature = gmv_feature_score(curr_luma, motion_w, motion_h, cx, cy);
                    if (feature > best_feature) {
                        best_feature = feature;
                        ax = cx;
                        ay = cy;
                    }
                }
            }
            if (ax < 0 || ay < 0 || best_feature < ANOMALY_GMV_MIN_TEXTURE_SCORE) continue;

            int  best_dx = 0, best_dy = 0;
            long best_sad = 0x7FFFFFFFL;
            long second_best_sad = 0x7FFFFFFFL;
            for (int dy = -sr; dy <= sr; dy++) {
                for (int dx = -sr; dx <= sr; dx++) {
                    long sad = 0;
                    bool valid_patch = true;
                    for (int ky = -ph; ky <= ph; ky++) {
                        for (int kx = -ph; kx <= ph; kx++) {
                            int cx = ax + kx;
                            int cy = ay + ky;
                            int px = ax + dx + kx;
                            int py = ay + dy + ky;
                            if (cx < 0 || cx >= motion_w || cy < 0 || cy >= motion_h ||
                                px < 0 || px >= motion_w || py < 0 || py >= motion_h) {
                                valid_patch = false;
                                break;
                            }
                            int cv = curr_luma[cy * motion_w + cx];
                            int pv = state->prev_registration_luma[py * motion_w + px];
                            int d = cv - pv;
                            sad += d < 0 ? -d : d;
                        }
                        if (!valid_patch) break;
                    }
                    if (!valid_patch) continue;
                    if (sad < best_sad) {
                        second_best_sad = best_sad;
                        best_sad = sad;
                        best_dx = dx;
                        best_dy = dy;
                    } else if (sad < second_best_sad) {
                        second_best_sad = sad;
                    }
                }
            }
            if (second_best_sad < 0x7FFFFFFFL &&
                (second_best_sad - best_sad) < ANOMALY_GMV_MIN_MATCH_MARGIN) {
                continue;
            }
            if (model.anchor_count < ANOMALY_GMV_MAX_DEBUG_ANCHORS) {
                anomaly_debug_gmv_anchor_t *dbg = &model.anchors[model.anchor_count++];
                dbg->valid = true;
                dbg->zone_gx = gx;
                dbg->zone_gy = gy;
                dbg->pixel_x = ax * motion_step;
                dbg->pixel_y = ay * motion_step;
                dbg->x_norm = (float)dbg->pixel_x / fw;
                dbg->y_norm = (float)dbg->pixel_y / fh;
                dbg->texture_score = best_feature;
                dbg->match_dx = best_dx;
                dbg->match_dy = best_dy;
                dbg->best_sad = best_sad >= 0x7FFFFFFFL ? -1 : (int)best_sad;
                dbg->second_best_sad = second_best_sad >= 0x7FFFFFFFL ? -1 : (int)second_best_sad;
            }
            anchor_ax[anchor_count] = ax;
            anchor_ay[anchor_count] = ay;
            anchor_dx[anchor_count] = best_dx;
            anchor_dy[anchor_count] = best_dy;
            anchor_count++;
        }
    }

    if (anchor_count < ANOMALY_GMV_MIN_ANCHORS) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS;
        return model;
    }

    float src_x[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    float src_y[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    float dst_x[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    float dst_y[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    for (int i = 0; i < anchor_count; i++) {
        src_x[i] = (float)(anchor_ax[i] * motion_step) / fw;
        src_y[i] = (float)(anchor_ay[i] * motion_step) / fh;
        dst_x[i] = (float)((anchor_ax[i] + anchor_dx[i]) * motion_step) / fw;
        dst_y[i] = (float)((anchor_ay[i] + anchor_dy[i]) * motion_step) / fh;
    }
    model.similarity = fit_similarity_2d(src_x, src_y, dst_x, dst_y, anchor_count);

    if (model.similarity.valid && anchor_count >= 3) {
        float worst_residual = -1.0f;
        int worst_idx = -1;
        for (int i = 0; i < anchor_count; i++) {
            float ex = model.similarity.a * src_x[i] - model.similarity.b * src_y[i] +
                model.similarity.tx - dst_x[i];
            float ey = model.similarity.b * src_x[i] + model.similarity.a * src_y[i] +
                model.similarity.ty - dst_y[i];
            float residual = sqrtf(ex * ex + ey * ey);
            if (residual > worst_residual) {
                worst_residual = residual;
                worst_idx = i;
            }
        }
    if (worst_idx >= 0 && worst_residual > (ANOMALY_GMV_RESIDUAL_THRESH * 1.5f)) {
            float src_x2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            float src_y2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            float dst_x2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            float dst_y2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            int kept = 0;
            for (int i = 0; i < anchor_count; i++) {
                if (i == worst_idx) continue;
                src_x2[kept] = src_x[i];
                src_y2[kept] = src_y[i];
                dst_x2[kept] = dst_x[i];
                dst_y2[kept] = dst_y[i];
                kept++;
            }
            similarity_2d_t refit = fit_similarity_2d(src_x2, src_y2, dst_x2, dst_y2, kept);
            if (refit.valid && refit.mean_residual < model.similarity.mean_residual) {
                model.similarity = refit;
            }
        }
    }
    compute_registration_consistency_stats(
        &model,
        src_x,
        src_y,
        dst_x,
        dst_y,
        anchor_count,
        &model.fit_anchor_residual_std,
        &model.fit_anchor_residual_max,
        &model.fit_motion_dx_std,
        &model.fit_motion_dy_std,
        &model.fit_quadrant_residual_spread);

    float scale = registration_model_scale(&model);
    model.fit_min_scale = scale;
    model.fit_max_scale = scale;
    bool motion_too_large = registration_motion_exceeds_search(&model, width, height, 0.85f);
    if (!model.similarity.valid) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID;
        model.scene_discontinuity = true;
    } else if (model.similarity.mean_residual > ANOMALY_GMV_RESIDUAL_THRESH) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH;
        model.scene_discontinuity = true;
    } else if (motion_too_large) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE;
        model.scene_discontinuity = true;
    } else if (scale < ANOMALY_GMV_MIN_SCALE || scale > ANOMALY_GMV_MAX_SCALE) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE;
        model.scene_discontinuity = true;
    }
    model.affine[0] = model.similarity.a;
    model.affine[1] = -model.similarity.b;
    model.affine[2] = model.similarity.tx;
    model.affine[3] = model.similarity.b;
    model.affine[4] = model.similarity.a;
    model.affine[5] = model.similarity.ty;
    return model;
}

static anomaly_registration_model_t estimate_affine_registration_model(
        const uint8_t         *curr_luma,
        const anomaly_state_t *state,
        int                    width,
        int                    height,
        int                    roi_x0,
        int                    roi_x1,
        int                    roi_y0,
        int                    roi_y1,
        int                    motion_sample_step,
        int                    motion_step,
        int                    motion_w,
        int                    motion_h) {
    anomaly_registration_model_t model = make_registration_model(
        ANOMALY_REGISTRATION_AFFINE,
        motion_sample_step,
        motion_step);
    model.debug_valid = (curr_luma != NULL &&
        state != NULL &&
        state->prev_registration_luma != NULL &&
        state->prev_registration_luma_width == motion_w &&
        state->prev_registration_luma_height == motion_h);
    if (!model.debug_valid) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE;
        return model;
    }

    int roi_mgx0 = clamp_i32(roi_x0 / motion_step, 2, motion_w - 3);
    int roi_mgx1 = clamp_i32((roi_x1 - 1) / motion_step, 2, motion_w - 3);
    int roi_mgy0 = clamp_i32(roi_y0 / motion_step, 2, motion_h - 3);
    int roi_mgy1 = clamp_i32((roi_y1 - 1) / motion_step, 2, motion_h - 3);
    if (roi_mgx1 <= roi_mgx0 || roi_mgy1 <= roi_mgy0) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE;
        return model;
    }

    int corner_x[64];
    int corner_y[64];
    int corner_score[64];
    int corner_count = detect_affine_corners(
        state->prev_registration_luma,
        motion_w,
        motion_h,
        roi_mgx0,
        roi_mgx1,
        roi_mgy0,
        roi_mgy1,
        3,
        64,
        corner_x,
        corner_y,
        corner_score);
    if (corner_count < 3) {
        model.anchor_count = corner_count;
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS;
        return model;
    }

    int base_dx = 0;
    int base_dy = 0;
    estimate_translation_seed(
        state->prev_registration_luma,
        curr_luma,
        motion_w,
        motion_h,
        roi_mgx0,
        roi_mgx1,
        roi_mgy0,
        roi_mgy1,
        8,
        &base_dx,
        &base_dy);

    affine_match_t matches[64];
    int match_count = track_affine_features(
        state->prev_registration_luma,
        curr_luma,
        motion_w,
        motion_h,
        corner_x,
        corner_y,
        corner_count,
        base_dx,
        base_dy,
        3,
        6,
        matches,
        64);
    model.tracked_match_count = match_count;
    if (match_count < 3) {
        model.anchor_count = corner_count < ANOMALY_GMV_MAX_DEBUG_ANCHORS ? corner_count : ANOMALY_GMV_MAX_DEBUG_ANCHORS;
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES;
        return model;
    }

    float affine_grid[6];
    float mean_residual_grid = 0.0f;
    bool fit_ok = false;
    if (match_count >= 8 &&
        fit_affine_least_squares(matches, match_count, affine_grid)) {
        int fast_inlier_count = 0;
        float fast_mean_residual = 0.0f;
        float fast_max_residual = 0.0f;
        if (summarize_affine_match_residuals(
                affine_grid,
                matches,
                match_count,
                1.5f,
                &fast_inlier_count,
                &fast_mean_residual,
                &fast_max_residual)) {
            // In the steady-state target-only case we often have dozens of
            // clean matches with near-zero residual. Accept the cheap
            // least-squares fit directly when it is already overwhelmingly
            // consistent, and fall back to RANSAC only for noisier frames.
            int required_inliers = match_count - 2;
            int relaxed_inliers = (match_count * 92 + 99) / 100;
            if (required_inliers < 6) required_inliers = 6;
            if (relaxed_inliers < required_inliers) relaxed_inliers = required_inliers;
            if (fast_inlier_count >= relaxed_inliers &&
                fast_mean_residual <= 0.45f &&
                fast_max_residual <= 1.75f) {
                mean_residual_grid = fast_mean_residual;
                fit_ok = true;
            }
        }
    }
    if (!fit_ok && !fit_affine_ransac(matches, match_count, affine_grid, &mean_residual_grid)) {
        model.anchor_count = corner_count < ANOMALY_GMV_MAX_DEBUG_ANCHORS ? corner_count : ANOMALY_GMV_MAX_DEBUG_ANCHORS;
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED;
        return model;
    }

    float fw = (float)(width > 1 ? width - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);
    float motion_fw = (float)(motion_w > 1 ? motion_w - 1 : 1);
    float motion_fh = (float)(motion_h > 1 ? motion_h - 1 : 1);
    float src_x_norm[64];
    float src_y_norm[64];
    float dst_x_norm[64];
    float dst_y_norm[64];
    for (int i = 0; i < match_count; i++) {
        src_x_norm[i] = (matches[i].x0 * (float)motion_step) / fw;
        src_y_norm[i] = (matches[i].y0 * (float)motion_step) / fh;
        dst_x_norm[i] = (matches[i].x1 * (float)motion_step) / fw;
        dst_y_norm[i] = (matches[i].y1 * (float)motion_step) / fh;
    }
    // Convert motion-grid affine fit to normalized-frame coordinates.
    model.affine[0] = affine_grid[0];
    model.affine[1] = affine_grid[1] * (fw / fh) * (motion_fh / motion_fw);
    model.affine[2] = (affine_grid[2] * (float)motion_step) / fw;
    model.affine[3] = affine_grid[3] * (fh / fw) * (motion_fw / motion_fh);
    model.affine[4] = affine_grid[4];
    model.affine[5] = (affine_grid[5] * (float)motion_step) / fh;
    summarize_affine_as_similarity(model.affine, mean_residual_grid * ((float)motion_step / fmaxf(fw, fh)),
                                   &model.similarity);

    float linear00 = model.affine[0];
    float linear01 = model.affine[1];
    float linear10 = model.affine[3];
    float linear11 = model.affine[4];
    float det = linear00 * linear11 - linear01 * linear10;
    float frob0 = sqrtf(linear00 * linear00 + linear10 * linear10);
    float frob1 = sqrtf(linear01 * linear01 + linear11 * linear11);
    float max_scale = frob0 > frob1 ? frob0 : frob1;
    float min_scale = frob0 < frob1 ? frob0 : frob1;
    model.fit_det = det;
    model.fit_min_scale = min_scale;
    model.fit_max_scale = max_scale;
    bool motion_too_large = registration_motion_exceeds_search(&model, width, height, 0.85f);
    model.anchor_count = corner_count < ANOMALY_GMV_MAX_DEBUG_ANCHORS ? corner_count : ANOMALY_GMV_MAX_DEBUG_ANCHORS;
    for (int i = 0; i < model.anchor_count; i++) {
        anomaly_debug_gmv_anchor_t *dbg = &model.anchors[i];
        dbg->valid = true;
        dbg->zone_gx = 0;
        dbg->zone_gy = 0;
        dbg->pixel_x = corner_x[i] * motion_step;
        dbg->pixel_y = corner_y[i] * motion_step;
        dbg->x_norm = (float)dbg->pixel_x / fw;
        dbg->y_norm = (float)dbg->pixel_y / fh;
        dbg->texture_score = corner_score[i];
        dbg->match_dx = 0;
        dbg->match_dy = 0;
        dbg->best_sad = 0;
        dbg->second_best_sad = 0;
    }
    compute_registration_consistency_stats(
        &model,
        src_x_norm,
        src_y_norm,
        dst_x_norm,
        dst_y_norm,
        match_count,
        &model.fit_anchor_residual_std,
        &model.fit_anchor_residual_max,
        &model.fit_motion_dx_std,
        &model.fit_motion_dy_std,
        &model.fit_quadrant_residual_spread);
    if (!model.similarity.valid) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED;
        model.scene_discontinuity = true;
    } else if (model.similarity.mean_residual > (ANOMALY_GMV_RESIDUAL_THRESH * 1.2f)) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH;
        model.scene_discontinuity = true;
    } else if (motion_too_large) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE;
        model.scene_discontinuity = true;
    } else if (max_scale > ANOMALY_GMV_MAX_SCALE * 1.15f ||
               min_scale < ANOMALY_GMV_MIN_SCALE * 0.85f) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE;
        model.scene_discontinuity = true;
    } else if (det <= 0.0f) {
        model.invalid_reason = ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET;
        model.scene_discontinuity = true;
    }
    return model;
}

static anomaly_registration_model_t estimate_registration_model(
        const anomaly_config_t *cfg,
        const uint8_t          *curr_luma,
        const anomaly_state_t  *state,
        int                     width,
        int                     height,
        int                     roi_x0,
        int                     roi_x1,
        int                     roi_y0,
        int                     roi_y1,
        int                     motion_sample_step,
        int                     motion_step,
        int                     motion_w,
        int                     motion_h) {
    int mode = normalize_registration_mode(cfg);
    switch (mode) {
        case ANOMALY_REGISTRATION_AFFINE:
            return estimate_affine_registration_model(
                curr_luma,
                state,
                width,
                height,
                roi_x0,
                roi_x1,
                roi_y0,
                roi_y1,
                motion_sample_step,
                motion_step,
                motion_w,
                motion_h);
        case ANOMALY_REGISTRATION_GMV:
        default:
            return estimate_gmv_registration_model(
                curr_luma,
                state,
                width,
                height,
                roi_x0,
                roi_x1,
                roi_y0,
                roi_y1,
                motion_sample_step,
                motion_step,
                motion_w,
                motion_h);
    }
}

static void populate_registration_debug(
        const anomaly_registration_model_t *model,
        anomaly_result_t                   *result_out) {
    if (model == NULL || result_out == NULL) return;
    result_out->gmv_debug.valid = model->debug_valid;
    result_out->gmv_debug.scene_discontinuity = model->scene_discontinuity;
    result_out->gmv_debug.sample_step = model->sample_step;
    result_out->gmv_debug.motion_step = model->motion_step;
    result_out->gmv_debug.anchor_count = model->anchor_count;
    result_out->gmv_debug.invalid_reason = model->invalid_reason;
    result_out->gmv_debug.tracked_match_count = model->tracked_match_count;
    result_out->gmv_debug.fit_a = model->similarity.a;
    result_out->gmv_debug.fit_b = model->similarity.b;
    result_out->gmv_debug.fit_tx = model->similarity.tx;
    result_out->gmv_debug.fit_ty = model->similarity.ty;
    result_out->gmv_debug.fit_scale = registration_model_scale(model);
    result_out->gmv_debug.fit_theta_deg =
        atan2f(model->similarity.b, model->similarity.a) * (180.0f / 3.14159265f);
    result_out->gmv_debug.fit_mean_residual = model->similarity.mean_residual;
    result_out->gmv_debug.fit_det = model->fit_det;
    result_out->gmv_debug.fit_min_scale = model->fit_min_scale;
    result_out->gmv_debug.fit_max_scale = model->fit_max_scale;
    result_out->gmv_debug.fit_anchor_residual_std = model->fit_anchor_residual_std;
    result_out->gmv_debug.fit_anchor_residual_max = model->fit_anchor_residual_max;
    result_out->gmv_debug.fit_motion_dx_std = model->fit_motion_dx_std;
    result_out->gmv_debug.fit_motion_dy_std = model->fit_motion_dy_std;
    result_out->gmv_debug.fit_quadrant_residual_spread = model->fit_quadrant_residual_spread;
    for (int i = 0; i < model->anchor_count && i < ANOMALY_GMV_MAX_DEBUG_ANCHORS; i++) {
        result_out->gmv_debug.anchors[i] = model->anchors[i];
    }
}

static anomaly_registration_health_t classify_registration_health(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height) {
    if (model == NULL || !model->debug_valid) {
        return ANOMALY_REG_HEALTH_UNKNOWN;
    }
    if (!registration_model_valid(model) || model->scene_discontinuity) {
        return ANOMALY_REG_HEALTH_INVALID;
    }

    float scale = registration_model_scale(model);
    float residual = model->similarity.mean_residual;
    bool motion_too_large = registration_motion_exceeds_search(model, width, height, 0.70f);
    bool scale_far = scale < 0.80f || scale > 1.20f;
    bool scale_soft = scale < 0.90f || scale > 1.10f;

    if (motion_too_large ||
        scale_far ||
        residual > (ANOMALY_GMV_RESIDUAL_THRESH * 1.5f)) {
        return ANOMALY_REG_HEALTH_HARD_DEGRADED;
    }
    if (scale_soft || residual > (ANOMALY_GMV_RESIDUAL_THRESH * 0.75f)) {
        return ANOMALY_REG_HEALTH_SOFT_DEGRADED;
    }
    return ANOMALY_REG_HEALTH_HEALTHY;
}

static void summarize_roi_cells(
        anomaly_roi_state_t          *roi_state,
        const float                  *motion_support_map,
        anomaly_registration_health_t registration_health) {
    if (roi_state == NULL || !roi_state->valid) return;
    int width = roi_state->width;
    int height = roi_state->height;
    if (width <= 0 || height <= 0 || roi_state->cell_cols <= 0 || roi_state->cell_rows <= 0) {
        return;
    }
    size_t cell_count = (size_t)roi_state->cell_cols * (size_t)roi_state->cell_rows;
    if (!ensure_roi_cell_capacity(roi_state, cell_count)) return;
    memset(roi_state->cell_summaries, 0, cell_count * sizeof(anomaly_roi_cell_summary_t));
    int cell_span = roi_grid_cell_span(roi_state->sample_step);
    if (cell_span <= 0) cell_span = 1;
    float reg_conf = registration_health_confidence(registration_health);
    for (int sy = 0; sy < height; sy++) {
        int cell_y = sy / cell_span;
        if (cell_y >= roi_state->cell_rows) cell_y = roi_state->cell_rows - 1;
        for (int sx = 0; sx < width; sx++) {
            int cell_x = sx / cell_span;
            if (cell_x >= roi_state->cell_cols) cell_x = roi_state->cell_cols - 1;
            size_t idx = (size_t)sy * (size_t)width + (size_t)sx;
            size_t cell_idx = (size_t)cell_y * (size_t)roi_state->cell_cols + (size_t)cell_x;
            anomaly_roi_cell_summary_t *cell = &roi_state->cell_summaries[cell_idx];
            if (roi_state->valid_mask[idx]) cell->valid_count++;
            if (roi_state->fresh_mask[idx]) cell->fresh_count++;
            if (roi_state->carried_mask[idx]) cell->carried_count++;
            if (roi_state->new_exposed_mask[idx]) cell->newly_exposed_count++;
            if (roi_state->valid_mask[idx] &&
                roi_state->coverage_age[idx] > ANOMALY_ROI_REALTIME_CARRY_EXPIRY) {
                cell->stale_count++;
            }
            if (roi_state->new_exposed_mask[idx]) cell->scan_flags |= ANOMALY_SCAN_FLAG_NEW_EXPOSED;
            if (roi_state->valid_mask[idx] &&
                roi_state->coverage_age[idx] > ANOMALY_ROI_REALTIME_CARRY_EXPIRY) {
                cell->scan_flags |= ANOMALY_SCAN_FLAG_STALE;
            }
            if (roi_state->reg_confidence[idx] < 0.50f) {
                cell->scan_flags |= ANOMALY_SCAN_FLAG_LOW_CONFIDENCE;
            }
            if (roi_state->thermal_score != NULL &&
                roi_state->thermal_score[idx] > cell->max_thermal_score) {
                cell->max_thermal_score = roi_state->thermal_score[idx];
            }
            if (roi_state->color_raw_score != NULL &&
                roi_state->color_raw_score[idx] > cell->max_color_score) {
                cell->max_color_score = roi_state->color_raw_score[idx];
            }
            float motion_support = motion_support_map != NULL ? motion_support_map[idx] : 0.0f;
            if (motion_support > cell->max_motion_support) {
                cell->max_motion_support = motion_support;
            }
            if (roi_state->reg_confidence[idx] > cell->registration_quality) {
                cell->registration_quality = roi_state->reg_confidence[idx];
            } else if (cell->registration_quality <= 0.0f) {
                cell->registration_quality = reg_conf;
            }
        }
    }
}

static void annotate_target_revisit_cells(
        anomaly_roi_state_t      *roi_state,
        const anomaly_state_t    *state) {
    if (roi_state == NULL || state == NULL || !roi_state->valid ||
        roi_state->cell_summaries == NULL ||
        roi_state->cell_cols <= 0 || roi_state->cell_rows <= 0) {
        return;
    }
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->forced_revisit) continue;
        float revisit_radius = track->support_radius_norm;
        if (revisit_radius < track->half_w_norm) revisit_radius = track->half_w_norm;
        if (revisit_radius < track->half_h_norm) revisit_radius = track->half_h_norm;
        if (revisit_radius < 0.01f) revisit_radius = 0.01f;
        float x0_norm = clamp01f(track->center_x_norm - revisit_radius);
        float x1_norm = clamp01f(track->center_x_norm + revisit_radius);
        float y0_norm = clamp01f(track->center_y_norm - revisit_radius);
        float y1_norm = clamp01f(track->center_y_norm + revisit_radius);
        int cell_x0 = clamp_i32((int)floorf(x0_norm * (float)roi_state->cell_cols),
                                0, roi_state->cell_cols - 1);
        int cell_x1 = clamp_i32((int)floorf(x1_norm * (float)roi_state->cell_cols),
                                0, roi_state->cell_cols - 1);
        int cell_y0 = clamp_i32((int)floorf(y0_norm * (float)roi_state->cell_rows),
                                0, roi_state->cell_rows - 1);
        int cell_y1 = clamp_i32((int)floorf(y1_norm * (float)roi_state->cell_rows),
                                0, roi_state->cell_rows - 1);
        for (int cell_y = cell_y0; cell_y <= cell_y1; cell_y++) {
            for (int cell_x = cell_x0; cell_x <= cell_x1; cell_x++) {
                size_t cell_idx = (size_t)cell_y * (size_t)roi_state->cell_cols + (size_t)cell_x;
                roi_state->cell_summaries[cell_idx].scan_flags |= ANOMALY_SCAN_FLAG_TARGET_REVISIT;
            }
        }
    }
}

static bool build_selective_refresh_mask(
        const anomaly_state_t               *state,
        const anomaly_registration_model_t  *model,
        anomaly_rescan_mode_t                mode,
        int                                  frame_width,
        int                                  frame_height,
        int                                  roi_x0,
        int                                  roi_y0,
        int                                  roi_x1,
        int                                  roi_y1,
        int                                  sample_step,
        int                                  sg_w,
        int                                  sg_h,
        uint8_t                             *refresh_mask,
        int                                 *selected_count_out,
        uint32_t                            *reason_flags_out) {
    if (selected_count_out != NULL) *selected_count_out = 0;
    if (reason_flags_out != NULL) *reason_flags_out = 0u;
    if (refresh_mask == NULL || state == NULL ||
        (mode != ANOMALY_RESCAN_MODE_PARTIAL &&
         mode != ANOMALY_RESCAN_MODE_TARGET_ONLY)) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        return false;
    }

    const anomaly_roi_state_t *prev = &state->roi_state;
    if (!prev->valid ||
        prev->sample_step != sample_step ||
        prev->width <= 0 || prev->height <= 0 ||
        prev->cell_cols <= 0 || prev->cell_rows <= 0 ||
        prev->valid_mask == NULL ||
        prev->cell_summaries == NULL ||
        !registration_model_valid(model)) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        return false;
    }

    size_t total_samples = (size_t)sg_w * (size_t)sg_h;
    memset(refresh_mask, 0, total_samples * sizeof(uint8_t));
    float fw = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float fh = (float)(frame_height > 1 ? frame_height - 1 : 1);
    anomaly_inverse_affine_t inv = registration_inverse_affine(model);
    if (!inv.valid) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        return false;
    }
    int prev_cell_span = roi_grid_cell_span(prev->sample_step);
    if (prev_cell_span <= 0) return false;

    uint32_t required_flags = (mode == ANOMALY_RESCAN_MODE_TARGET_ONLY)
        ? ANOMALY_SCAN_FLAG_TARGET_REVISIT
        : (ANOMALY_SCAN_FLAG_NEW_EXPOSED |
           ANOMALY_SCAN_FLAG_STALE |
           ANOMALY_SCAN_FLAG_TARGET_REVISIT);
    bool target_only = (mode == ANOMALY_RESCAN_MODE_TARGET_ONLY);
    int selected_count = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        int y = roi_y0 + sy * sample_step + sample_step / 2;
        if (y >= roi_y1) y = roi_y1 - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            int x = roi_x0 + sx * sample_step + sample_step / 2;
            if (x >= roi_x1) x = roi_x1 - 1;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            bool select_sample = false;
            float nx = clamp01f((float)x / fw);
            float ny = clamp01f((float)y / fh);
            float px = 0.0f;
            float py = 0.0f;
            if (!registration_invert_point_fast(&inv, nx, ny, &px, &py)) {
                select_sample = !target_only;
            } else {
                int prev_px = clamp_i32((int)lroundf(px * fw), 0, frame_width - 1);
                int prev_py = clamp_i32((int)lroundf(py * fh), 0, frame_height - 1);
                if (prev_px < prev->roi_x0 || prev_px >= prev->roi_x1 ||
                    prev_py < prev->roi_y0 || prev_py >= prev->roi_y1) {
                    select_sample = !target_only;
                } else {
                    int prev_sx = (prev_px - prev->roi_x0) / prev->sample_step;
                    int prev_sy = (prev_py - prev->roi_y0) / prev->sample_step;
                    if (prev_sx < 0 || prev_sy < 0 ||
                        prev_sx >= prev->width || prev_sy >= prev->height) {
                        select_sample = !target_only;
                    } else {
                        size_t prev_idx = (size_t)prev_sy * (size_t)prev->width + (size_t)prev_sx;
                        if (!prev->valid_mask[prev_idx]) {
                            select_sample = !target_only;
                        } else {
                            int cell_x = clamp_i32(prev_sx / prev_cell_span, 0, prev->cell_cols - 1);
                            int cell_y = clamp_i32(prev_sy / prev_cell_span, 0, prev->cell_rows - 1);
                            size_t cell_idx = (size_t)cell_y * (size_t)prev->cell_cols + (size_t)cell_x;
                            uint32_t scan_flags = prev->cell_summaries[cell_idx].scan_flags;
                            select_sample = (scan_flags & required_flags) != 0u;
                        }
                    }
                }
            }
            refresh_mask[idx] = select_sample ? 1u : 0u;
            selected_count += select_sample ? 1 : 0;
        }
    }

    if (!target_only && selected_count <= 0) {
        for (int sy = 0; sy < sg_h; sy++) {
            for (int sx = 0; sx < sg_w; sx++) {
                if (((sx + sy) & 1) != 0) continue;
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                if (refresh_mask[idx] != 0u) continue;
                refresh_mask[idx] = 1u;
                selected_count++;
            }
        }
    }

    if (selected_count_out != NULL) *selected_count_out = selected_count;
    if (selected_count <= 0) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_EMPTY;
        return false;
    }
    if (!target_only && selected_count >= (int)(total_samples * 0.95f)) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_TOO_BROAD;
        return false;
    }
    return true;
}

static void update_roi_state_full_refresh(
        anomaly_state_t                  *state,
        int                               roi_x0,
        int                               roi_y0,
        int                               roi_x1,
        int                               roi_y1,
        int                               sample_step,
        int                               sg_w,
        int                               sg_h,
        const float                      *sg_luma,
        const float                      *saliency_spatial_map,
        const float                      *saliency_motion_map,
        const float                      *thermal_delta_map,
        bool                              bg_valid,
        int                               black_hot,
        float                             thermal_min_delta,
        float                             delta_mean,
        float                             delta_norm,
        anomaly_registration_health_t     registration_health) {
    anomaly_roi_state_t *roi_state = &state->roi_state;
    if (!ensure_roi_state_capacity(roi_state, (size_t)sg_w * (size_t)sg_h)) {
        clear_roi_state(roi_state);
        return;
    }

    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    roi_state->valid = true;
    roi_state->roi_x0 = roi_x0;
    roi_state->roi_y0 = roi_y0;
    roi_state->roi_x1 = roi_x1;
    roi_state->roi_y1 = roi_y1;
    roi_state->width = sg_w;
    roi_state->height = sg_h;
    roi_state->sample_step = sample_step;
    roi_state->cell_size_px = ANOMALY_ROI_CELL_TARGET_SIZE_PX;
    roi_state->cell_cols = (sg_w + roi_grid_cell_span(sample_step) - 1) / roi_grid_cell_span(sample_step);
    roi_state->cell_rows = (sg_h + roi_grid_cell_span(sample_step) - 1) / roi_grid_cell_span(sample_step);
    float reg_conf = registration_health_confidence(registration_health);
    for (size_t i = 0; i < sg_count; i++) {
        roi_state->last_luma[i] = sg_luma[i];
        roi_state->thermal_score[i] = saliency_spatial_map != NULL ? saliency_spatial_map[i] : -1.0f;
        float temporal_score = -1.0f;
        if (bg_valid && state->bg_luma != NULL) {
            float delta = thermal_delta_from_maps(
                thermal_delta_map,
                state->bg_luma,
                sg_luma,
                i,
                black_hot != 0);
            if (delta >= thermal_min_delta) {
                temporal_score = (delta - delta_mean) / delta_norm;
            }
        }
        roi_state->temporal_score[i] = temporal_score;
        roi_state->valid_mask[i] = 1u;
        roi_state->fresh_mask[i] = 1u;
        roi_state->carried_mask[i] = 0u;
        roi_state->new_exposed_mask[i] = 0u;
        roi_state->reg_confidence[i] = reg_conf;
        roi_state->coverage_age[i] = 0u;
    }
    summarize_roi_cells(roi_state, saliency_motion_map, registration_health);
    annotate_target_revisit_cells(roi_state, state);
}

static bool update_roi_state_selective_refresh(
        anomaly_state_t                  *state,
        const anomaly_registration_model_t *registration,
        int                               frame_width,
        int                               frame_height,
        int                               roi_x0,
        int                               roi_y0,
        int                               roi_x1,
        int                               roi_y1,
        int                               sample_step,
        int                               sg_w,
        int                               sg_h,
        const float                      *sg_luma,
        const float                      *saliency_spatial_map,
        const float                      *saliency_motion_map,
        const float                      *thermal_delta_map,
        bool                              bg_valid,
        int                               black_hot,
        float                             thermal_min_delta,
        float                             delta_mean,
        float                             delta_norm,
        anomaly_registration_health_t     registration_health,
        const uint8_t                    *refresh_mask) {
    if (state == NULL || registration == NULL || refresh_mask == NULL) return false;
    anomaly_roi_state_t *roi_state = &state->roi_state;
    if (!roi_state->valid ||
        roi_state->sample_step != sample_step ||
        roi_state->width != sg_w || roi_state->height != sg_h ||
        roi_state->last_luma == NULL ||
        roi_state->thermal_score == NULL ||
        roi_state->temporal_score == NULL ||
        roi_state->valid_mask == NULL ||
        roi_state->coverage_age == NULL ||
        !registration_model_valid(registration) ||
        !ensure_roi_state_capacity(roi_state, (size_t)sg_w * (size_t)sg_h)) {
        return false;
    }

    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    int prev_roi_x0 = roi_state->roi_x0;
    int prev_roi_y0 = roi_state->roi_y0;
    int prev_roi_x1 = roi_state->roi_x1;
    int prev_roi_y1 = roi_state->roi_y1;
    int prev_width = roi_state->width;
    int prev_height = roi_state->height;
    int prev_sample_step = roi_state->sample_step;
    if (!ensure_prev_roi_snapshot_capacity(state, sg_count)) {
        return false;
    }

    float *prev_last_luma = state->scratch_prev_roi_last_luma;
    float *prev_thermal_score = state->scratch_prev_roi_thermal_score;
    float *prev_temporal_score = state->scratch_prev_roi_temporal_score;
    uint8_t *prev_valid_mask = state->scratch_prev_roi_valid_mask;
    uint8_t *prev_coverage_age = state->scratch_prev_roi_coverage_age;

    memcpy(prev_last_luma, roi_state->last_luma, sg_count * sizeof(float));
    memcpy(prev_thermal_score, roi_state->thermal_score, sg_count * sizeof(float));
    memcpy(prev_temporal_score, roi_state->temporal_score, sg_count * sizeof(float));
    memcpy(prev_valid_mask, roi_state->valid_mask, sg_count * sizeof(uint8_t));
    memcpy(prev_coverage_age, roi_state->coverage_age, sg_count * sizeof(uint8_t));

    roi_state->valid = true;
    roi_state->roi_x0 = roi_x0;
    roi_state->roi_y0 = roi_y0;
    roi_state->roi_x1 = roi_x1;
    roi_state->roi_y1 = roi_y1;
    roi_state->width = sg_w;
    roi_state->height = sg_h;
    roi_state->sample_step = sample_step;
    roi_state->cell_size_px = ANOMALY_ROI_CELL_TARGET_SIZE_PX;
    roi_state->cell_cols = (sg_w + roi_grid_cell_span(sample_step) - 1) / roi_grid_cell_span(sample_step);
    roi_state->cell_rows = (sg_h + roi_grid_cell_span(sample_step) - 1) / roi_grid_cell_span(sample_step);

    float reg_conf = registration_health_confidence(registration_health);
    float fw = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float fh = (float)(frame_height > 1 ? frame_height - 1 : 1);
    anomaly_inverse_affine_t inv = registration_inverse_affine(registration);
    if (!inv.valid) return false;
    for (int sy = 0; sy < sg_h; sy++) {
        int y = roi_y0 + sy * sample_step + sample_step / 2;
        if (y >= roi_y1) y = roi_y1 - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            int x = roi_x0 + sx * sample_step + sample_step / 2;
            if (x >= roi_x1) x = roi_x1 - 1;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (refresh_mask[idx] != 0u) {
                roi_state->last_luma[idx] = sg_luma[idx];
                roi_state->thermal_score[idx] =
                    saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                float temporal_score = -1.0f;
                if (bg_valid && state->bg_luma != NULL) {
                    float delta = thermal_delta_from_maps(
                        thermal_delta_map,
                        state->bg_luma,
                        sg_luma,
                        idx,
                        black_hot != 0);
                    if (delta >= thermal_min_delta) {
                        temporal_score = (delta - delta_mean) / delta_norm;
                    }
                }
                roi_state->temporal_score[idx] = temporal_score;
                roi_state->valid_mask[idx] = 1u;
                roi_state->fresh_mask[idx] = 1u;
                roi_state->carried_mask[idx] = 0u;
                roi_state->new_exposed_mask[idx] = 0u;
                roi_state->reg_confidence[idx] = reg_conf;
                roi_state->coverage_age[idx] = 0u;
                continue;
            }

            float nx = clamp01f((float)x / fw);
            float ny = clamp01f((float)y / fh);
            float px = 0.0f;
            float py = 0.0f;
            bool carried = false;
            if (registration_invert_point_fast(&inv, nx, ny, &px, &py)) {
                int prev_px = clamp_i32((int)lroundf(px * fw), 0, frame_width - 1);
                int prev_py = clamp_i32((int)lroundf(py * fh), 0, frame_height - 1);
                if (prev_px >= prev_roi_x0 && prev_px < prev_roi_x1 &&
                    prev_py >= prev_roi_y0 && prev_py < prev_roi_y1) {
                    int prev_sx = (prev_px - prev_roi_x0) / prev_sample_step;
                    int prev_sy = (prev_py - prev_roi_y0) / prev_sample_step;
                    if (prev_sx >= 0 && prev_sy >= 0 &&
                        prev_sx < prev_width && prev_sy < prev_height) {
                        size_t prev_idx = (size_t)prev_sy * (size_t)prev_width + (size_t)prev_sx;
                        if (prev_valid_mask[prev_idx]) {
                            roi_state->last_luma[idx] = prev_last_luma[prev_idx];
                            roi_state->thermal_score[idx] = prev_thermal_score[prev_idx];
                            roi_state->temporal_score[idx] = prev_temporal_score[prev_idx];
                            roi_state->valid_mask[idx] = 1u;
                            roi_state->fresh_mask[idx] = 0u;
                            roi_state->carried_mask[idx] = 1u;
                            roi_state->new_exposed_mask[idx] = 0u;
                            roi_state->reg_confidence[idx] = reg_conf;
                            roi_state->coverage_age[idx] =
                                prev_coverage_age[prev_idx] < 255u ? (uint8_t)(prev_coverage_age[prev_idx] + 1u) : 255u;
                            carried = true;
                        }
                    }
                }
            }
            if (!carried) {
                roi_state->last_luma[idx] = sg_luma[idx];
                roi_state->thermal_score[idx] = -1.0f;
                roi_state->temporal_score[idx] = -1.0f;
                roi_state->valid_mask[idx] = 0u;
                roi_state->fresh_mask[idx] = 0u;
                roi_state->carried_mask[idx] = 0u;
                roi_state->new_exposed_mask[idx] = 1u;
                roi_state->reg_confidence[idx] = 0.0f;
                roi_state->coverage_age[idx] = 0u;
            }
        }
    }

    summarize_roi_cells(roi_state, saliency_motion_map, registration_health);
    annotate_target_revisit_cells(roi_state, state);
    return true;
}

static anomaly_scan_plan_t build_scan_plan(
        const anomaly_state_t               *state,
        const anomaly_registration_model_t  *model,
        anomaly_registration_health_t        base_registration_health,
        int                                  frame_width,
        int                                  frame_height,
        int                                  roi_x0,
        int                                  roi_y0,
        int                                  roi_x1,
        int                                  roi_y1,
        int                                  sample_step,
        int                                  sg_w,
        int                                  sg_h,
        bool                                 appearance_refresh_ran,
        bool                                 scene_discontinuity,
        anomaly_registration_health_t       *registration_health_out) {
    anomaly_scan_plan_t plan;
    memset(&plan, 0, sizeof(plan));
    plan.mode = appearance_refresh_ran
        ? ANOMALY_RESCAN_MODE_FULL
        : ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP;
    plan.sampled_width = sg_w;
    plan.sampled_height = sg_h;
    plan.total_samples = sg_w > 0 && sg_h > 0 ? sg_w * sg_h : 0;
    plan.target_revisit_track_count = target_revisit_track_count(state);

    anomaly_registration_health_t refined = base_registration_health;
    if (!appearance_refresh_ran || plan.total_samples <= 0) {
        if (!appearance_refresh_ran) {
            plan.reason_flags |= ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH;
        }
        if (plan.total_samples <= 0) {
            plan.reason_flags |= ANOMALY_SCAN_REASON_NO_SAMPLES;
        }
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }

    plan.valid = true;
    const anomaly_roi_state_t *prev = state != NULL ? &state->roi_state : NULL;
    bool prev_valid = true;
    if (prev == NULL) {
        prev_valid = false;
    } else {
        if (!prev->valid ||
            prev->width <= 0 ||
            prev->height <= 0 ||
            prev->sample_step <= 0 ||
            prev->valid_mask == NULL) {
            prev_valid = false;
            plan.reason_flags |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
        }
    }
    if (!registration_model_valid(model)) {
        prev_valid = false;
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
    }
    if (scene_discontinuity) {
        prev_valid = false;
        plan.reason_flags |= ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY;
    }
    if (!prev_valid) {
        if ((plan.reason_flags & (ANOMALY_SCAN_REASON_PREV_STATE_INVALID |
                                  ANOMALY_SCAN_REASON_REG_INVALID |
                                  ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY)) == 0u) {
            plan.reason_flags |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
        }
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }

    float reg_conf = registration_health_confidence(base_registration_health);
    int stale_limit = ANOMALY_ROI_REALTIME_CARRY_EXPIRY;
    float fw = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float fh = (float)(frame_height > 1 ? frame_height - 1 : 1);
    anomaly_inverse_affine_t inv = registration_inverse_affine(model);
    if (!inv.valid) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }

    for (int sy = 0; sy < sg_h; sy++) {
        int y = roi_y0 + sy * sample_step + sample_step / 2;
        if (y >= roi_y1) y = roi_y1 - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            int x = roi_x0 + sx * sample_step + sample_step / 2;
            if (x >= roi_x1) x = roi_x1 - 1;
            float nx = clamp01f((float)x / fw);
            float ny = clamp01f((float)y / fh);
            float px = 0.0f;
            float py = 0.0f;
            if (!registration_invert_point_fast(&inv, nx, ny, &px, &py)) {
                plan.newly_exposed_samples++;
                continue;
            }
            int prev_px = clamp_i32((int)lroundf(px * fw), 0, frame_width - 1);
            int prev_py = clamp_i32((int)lroundf(py * fh), 0, frame_height - 1);
            if (prev_px < prev->roi_x0 || prev_px >= prev->roi_x1 ||
                prev_py < prev->roi_y0 || prev_py >= prev->roi_y1) {
                plan.newly_exposed_samples++;
                continue;
            }
            int prev_sx = (prev_px - prev->roi_x0) / prev->sample_step;
            int prev_sy = (prev_py - prev->roi_y0) / prev->sample_step;
            if (prev_sx < 0 || prev_sy < 0 || prev_sx >= prev->width || prev_sy >= prev->height) {
                plan.newly_exposed_samples++;
                continue;
            }
            size_t prev_idx = (size_t)prev_sy * (size_t)prev->width + (size_t)prev_sx;
            if (!prev->valid_mask[prev_idx]) {
                plan.newly_exposed_samples++;
                continue;
            }
            plan.carried_samples++;
            int age = (int)prev->coverage_age[prev_idx] + 1;
            if (age > stale_limit) {
                plan.stale_samples++;
            }
        }
    }

    if (plan.total_samples > 0) {
        float inv_total = 1.0f / (float)plan.total_samples;
        plan.warped_valid_fraction = (float)plan.carried_samples * inv_total;
        plan.newly_exposed_fraction = (float)plan.newly_exposed_samples * inv_total;
        plan.stale_fraction = (float)plan.stale_samples * inv_total;
    }

    if (refined == ANOMALY_REG_HEALTH_HEALTHY) {
        if (plan.warped_valid_fraction < 0.65f || plan.newly_exposed_fraction > 0.35f) {
            refined = ANOMALY_REG_HEALTH_HARD_DEGRADED;
        } else if (plan.warped_valid_fraction < 0.80f || plan.newly_exposed_fraction > 0.20f ||
                   reg_conf < 0.90f) {
            refined = ANOMALY_REG_HEALTH_SOFT_DEGRADED;
        }
    } else if (refined == ANOMALY_REG_HEALTH_SOFT_DEGRADED) {
        if (plan.warped_valid_fraction < 0.65f || plan.newly_exposed_fraction > 0.35f) {
            refined = ANOMALY_REG_HEALTH_HARD_DEGRADED;
        }
    }

    bool force_full = false;
    if (scene_discontinuity) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY;
        force_full = true;
    }
    if (refined == ANOMALY_REG_HEALTH_INVALID) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
        force_full = true;
    }
    if (refined == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_HARD_DEGRADED;
        force_full = true;
    }
    if (plan.warped_valid_fraction < 0.80f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_WARP_LOW;
        force_full = true;
    }
    if (plan.newly_exposed_fraction > 0.25f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH;
        force_full = true;
    }
    if (plan.stale_fraction > 0.35f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_STALE_HIGH;
        force_full = true;
    }
    if (prev->sample_step != sample_step) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH;
        force_full = true;
    }

    if (force_full) {
        plan.mode = ANOMALY_RESCAN_MODE_FULL;
    } else if (plan.target_revisit_track_count > 0 &&
               plan.newly_exposed_fraction < 0.05f &&
               plan.stale_fraction < 0.10f) {
        plan.mode = ANOMALY_RESCAN_MODE_TARGET_ONLY;
        plan.reason_flags |= ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE;
    } else {
        plan.mode = ANOMALY_RESCAN_MODE_PARTIAL;
        plan.reason_flags |= ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE;
    }

    if (registration_health_out != NULL) *registration_health_out = refined;
    return plan;
}

static void age_roi_state_one_frame(
        anomaly_state_t                  *state,
        bool                              scene_discontinuity,
        anomaly_registration_health_t     registration_health) {
    if (state == NULL) return;
    anomaly_roi_state_t *roi_state = &state->roi_state;
    if (!roi_state->valid || roi_state->width <= 0 || roi_state->height <= 0) return;
    if (scene_discontinuity ||
        registration_health == ANOMALY_REG_HEALTH_INVALID ||
        registration_health == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        clear_roi_state(roi_state);
        return;
    }
    size_t pixel_count = (size_t)roi_state->width * (size_t)roi_state->height;
    float reg_conf = registration_health_confidence(registration_health);
    for (size_t i = 0; i < pixel_count; i++) {
        roi_state->fresh_mask[i] = 0;
        roi_state->carried_mask[i] = roi_state->valid_mask[i] ? 1u : 0u;
        roi_state->new_exposed_mask[i] = 0;
        if (roi_state->valid_mask[i] && roi_state->coverage_age[i] < 255u) {
            roi_state->coverage_age[i] += 1u;
        }
        roi_state->reg_confidence[i] = reg_conf;
    }
    summarize_roi_cells(roi_state, NULL, registration_health);
    annotate_target_revisit_cells(roi_state, state);
}

static void update_prev_luma_state(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h) {
    if (state == NULL || curr_luma == NULL) return;
    if (ensure_u8_capacity(&state->prev_luma, &state->prev_luma_capacity, motion_count)) {
        memcpy(state->prev_luma, curr_luma, motion_count * sizeof(uint8_t));
        state->prev_luma_width = motion_w;
        state->prev_luma_height = motion_h;
    } else {
        if (state->prev_luma != NULL) {
            free(state->prev_luma);
            state->prev_luma = NULL;
        }
        state->prev_luma_capacity = 0;
        state->prev_luma_width = 0;
        state->prev_luma_height = 0;
    }
}

static void update_prev_registration_luma_state(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h) {
    if (state == NULL || curr_luma == NULL) return;
    if (ensure_u8_capacity(&state->prev_registration_luma,
                           &state->prev_registration_luma_capacity,
                           motion_count)) {
        memcpy(state->prev_registration_luma, curr_luma, motion_count * sizeof(uint8_t));
        state->prev_registration_luma_width = motion_w;
        state->prev_registration_luma_height = motion_h;
    } else {
        if (state->prev_registration_luma != NULL) {
            free(state->prev_registration_luma);
            state->prev_registration_luma = NULL;
        }
        state->prev_registration_luma_capacity = 0;
        state->prev_registration_luma_width = 0;
        state->prev_registration_luma_height = 0;
    }
}

bool anomaly_probe_thermal_point(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        const uint8_t          *rgba,
        int                     rgba_stride,
        int                     width,
        int                     height,
        float                   point_x_norm,
        float                   point_y_norm,
        anomaly_probe_t        *probe_out) {
    if (probe_out == NULL) return false;
    memset(probe_out, 0, sizeof(*probe_out));
    if (cfg == NULL || rgba == NULL || width <= 0 || height <= 0) return false;
    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) == 0) return false;

    probe_out->thermal_min_delta = effective_thermal_min_delta(cfg);

    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)width);
    int roi_x1 = width - roi_x0;
    int roi_y0 = (int)(margin * (float)height);
    int roi_y1 = height - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = width;  }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = height; }

    int px = clamp_i32((int)lroundf(clamp01f(point_x_norm) * (float)(width - 1)), 0, width - 1);
    int py = clamp_i32((int)lroundf(clamp01f(point_y_norm) * (float)(height - 1)), 0, height - 1);
    probe_out->pixel_x = px;
    probe_out->pixel_y = py;
    probe_out->inside_scan_zone = (px >= roi_x0 && px < roi_x1 && py >= roi_y0 && py < roi_y1);
    if (!probe_out->inside_scan_zone) return true;

    int sample_step = effective_sample_step(cfg, width, height);
    int roi_w = roi_x1 - roi_x0;
    int roi_h = roi_y1 - roi_y0;
    if (roi_w <= 0) roi_w = 1;
    if (roi_h <= 0) roi_h = 1;
    int sg_w = (roi_w + sample_step - 1) / sample_step;
    int sg_h = (roi_h + sample_step - 1) / sample_step;
    if (sg_w <= 0 || sg_h <= 0) return false;

    int sx = clamp_i32((px - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
    int sy = clamp_i32((py - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
    probe_out->sample_x = sx;
    probe_out->sample_y = sy;

    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    float *sg_luma = (float *)malloc(sg_count * sizeof(float));
    float *ii_sum = (float *)malloc(sg_count * sizeof(float));
    float *ii_sum2 = (float *)malloc(sg_count * sizeof(float));
    if (sg_luma == NULL || ii_sum == NULL || ii_sum2 == NULL) {
        free(sg_luma);
        free(ii_sum);
        free(ii_sum2);
        return false;
    }

    for (int gy = 0; gy < sg_h; gy++) {
        int y = roi_y0 + gy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        int row_offset = gy * sg_w;
        int prev_row_offset = row_offset - sg_w;
        for (int gx = 0; gx < sg_w; gx++) {
            int x = roi_x0 + gx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            const uint8_t *p = row + (x * 4);
            float r = (float)p[0], g = (float)p[1], b = (float)p[2];
            float v = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
            float v2 = v * v;
            int idx = row_offset + gx;
            sg_luma[idx] = v;
            float a = (gy > 0) ? ii_sum[prev_row_offset + gx] : 0.0f;
            float l = (gx > 0) ? ii_sum[idx - 1] : 0.0f;
            float al = (gy > 0 && gx > 0) ? ii_sum[prev_row_offset + (gx - 1)] : 0.0f;
            ii_sum[idx] = v + a + l - al;

            float a2 = (gy > 0) ? ii_sum2[prev_row_offset + gx] : 0.0f;
            float l2 = (gx > 0) ? ii_sum2[idx - 1] : 0.0f;
            float al2 = (gy > 0 && gx > 0) ? ii_sum2[prev_row_offset + (gx - 1)] : 0.0f;
            ii_sum2[idx] = v2 + a2 + l2 - al2;
        }
    }

    const int R = effective_thermal_window_radius_cells(sample_step);
    int wx0 = sx - R; if (wx0 < 0) wx0 = 0;
    int wx1 = sx + R; if (wx1 >= sg_w) wx1 = sg_w - 1;
    int wy0 = sy - R; if (wy0 < 0) wy0 = 0;
    int wy1 = sy + R; if (wy1 >= sg_h) wy1 = sg_h - 1;
    int n = (wx1 - wx0 + 1) * (wy1 - wy0 + 1);
    float lum = sg_luma[sy * sg_w + sx];
    float mean = ii_query(ii_sum, sg_w, wx0, wy0, wx1, wy1) / (float)n;
    float sum2 = ii_query(ii_sum2, sg_w, wx0, wy0, wx1, wy1);
    float std = sqrtf(fmaxf(sum2 / (float)n - mean * mean, 1.0f));
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    float abs_delta = black_hot ? (mean - lum) : (lum - mean);

    probe_out->valid = true;
    probe_out->sample_luma = (float)lum;
    probe_out->spatial_mean = (float)mean;
    probe_out->spatial_std = (float)std;
    probe_out->spatial_abs_delta = (float)abs_delta;
    probe_out->spatial_score = (abs_delta >= probe_out->thermal_min_delta)
                               ? (abs_delta / std) : -1.0f;

    bool bg_ready = (state != NULL
                     && state->bg_luma != NULL
                     && state->bg_sg_w == sg_w
                     && state->bg_sg_h == sg_h
                     && state->bg_warmup >= ANOMALY_THERMAL_BG_WARMUP);
    probe_out->bg_ready = bg_ready;
    probe_out->used_temporal_score = bg_ready;
    probe_out->effective_score = probe_out->spatial_score;

    if (bg_ready) {
        double sum_d = 0.0, sum_d2 = 0.0;
        int cnt_d = 0;
        for (int i = 0; i < sg_w * sg_h; i++) {
            float d = black_hot
                      ? (state->bg_luma[i] - (float)sg_luma[i])
                      : ((float)sg_luma[i] - state->bg_luma[i]);
            if (d > 0.0f) {
                sum_d += d;
                sum_d2 += (double)d * d;
                cnt_d++;
            }
        }
        double delta_mean = cnt_d > 0 ? sum_d / (double)cnt_d : 0.0;
        double delta_var = cnt_d > 1
                           ? fmax(sum_d2 / (double)cnt_d - delta_mean * delta_mean, 0.0)
                           : 0.0;
        double delta_norm = sqrt(delta_var);
        if (delta_norm < (double)ANOMALY_THERMAL_BG_NORM) {
            delta_norm = (double)ANOMALY_THERMAL_BG_NORM;
        }
        float delta = black_hot
                      ? (state->bg_luma[sy * sg_w + sx] - (float)lum)
                      : ((float)lum - state->bg_luma[sy * sg_w + sx]);
        probe_out->temporal_delta = delta;
        probe_out->temporal_mean = (float)delta_mean;
        probe_out->temporal_norm = (float)delta_norm;
        probe_out->temporal_score = (delta >= probe_out->thermal_min_delta)
                                    ? (float)((delta - delta_mean) / delta_norm)
                                    : -1.0f;
        probe_out->effective_score = probe_out->temporal_score;
    } else {
        probe_out->temporal_score = -1.0f;
    }

    free(sg_luma);
    free(ii_sum);
    free(ii_sum2);
    return true;
}

// ── State lifecycle ───────────────────────���────────────────────────────────

void anomaly_state_init(anomaly_state_t *state) {
    memset(state, 0, sizeof(*state));
    if (state != NULL) {
        state->next_target_track_id = 1;
    }
}

void anomaly_state_reset(anomaly_state_t *state) {
    if (state == NULL) return;
    state->frame_counter = 0;
    state->color_phase_counter = 0;
    memset(state->acc_cx,     0, sizeof(state->acc_cx));
    memset(state->acc_cy,     0, sizeof(state->acc_cy));
    memset(state->acc_hits,   0, sizeof(state->acc_hits));
    memset(state->acc_hold,   0, sizeof(state->acc_hold));
    memset(state->acc_presence_mask, 0, sizeof(state->acc_presence_mask));
    memset(state->acc_active, 0, sizeof(state->acc_active));
    if (state->prev_luma != NULL) {
        free(state->prev_luma);
        state->prev_luma = NULL;
    }
    state->prev_luma_width  = 0;
    state->prev_luma_height = 0;
    state->prev_luma_capacity = 0;
    if (state->prev_registration_luma != NULL) {
        free(state->prev_registration_luma);
        state->prev_registration_luma = NULL;
    }
    state->prev_registration_luma_width = 0;
    state->prev_registration_luma_height = 0;
    state->prev_registration_luma_capacity = 0;
    state->cached_registration_valid = false;
    state->cached_registration_reuse_budget = 0;
    if (state->motion_persist != NULL) {
        free(state->motion_persist);
        state->motion_persist = NULL;
    }
    state->motion_persist_w = 0;
    state->motion_persist_h = 0;
    if (state->bg_luma != NULL) {
        free(state->bg_luma);
        state->bg_luma = NULL;
    }
    state->bg_sg_w  = 0;
    state->bg_sg_h  = 0;
    state->bg_warmup = 0;
    state->publish_hold_frames = 0;
    state->publish_stable_frames = 0;
    if (state->thermal_target_persist != NULL) {
        free(state->thermal_target_persist);
        state->thermal_target_persist = NULL;
    }
    state->thermal_target_persist_w = 0;
    state->thermal_target_persist_h = 0;
    free(state->color_recent_hist);
    state->color_recent_hist = NULL;
    state->color_recent_hist_bins = 0;
    free(state->scratch_color_hist);
    state->scratch_color_hist = NULL;
    state->scratch_color_hist_bins = 0;
    release_roi_state(&state->roi_state);
    clear_all_target_tracks(state);
    memset(state->saliency_aux_cx, 0, sizeof(state->saliency_aux_cx));
    memset(state->saliency_aux_cy, 0, sizeof(state->saliency_aux_cy));
    memset(state->saliency_aux_hits, 0, sizeof(state->saliency_aux_hits));
    memset(state->saliency_aux_hold, 0, sizeof(state->saliency_aux_hold));
    memset(state->saliency_aux_active, 0, sizeof(state->saliency_aux_active));
    state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        state->saliency_aux_display_algorithm[i] = ANOMALY_ALGO_PERSIST;
    }
    free(state->scratch_luma);
    state->scratch_luma = NULL;
    state->scratch_luma_capacity = 0;
    free(state->scratch_registration_luma);
    state->scratch_registration_luma = NULL;
    free(state->scratch_registration_tmp);
    state->scratch_registration_tmp = NULL;
    state->scratch_registration_luma_capacity = 0;
    free(state->scratch_u8);
    state->scratch_u8 = NULL;
    state->scratch_u8_capacity = 0;
    free(state->scratch_sg_luma);
    state->scratch_sg_luma = NULL;
    free(state->scratch_ii_sum);
    state->scratch_ii_sum = NULL;
    free(state->scratch_ii_sum2);
    state->scratch_ii_sum2 = NULL;
    state->scratch_sampled_grid_capacity = 0;
    free(state->scratch_saliency_spatial);
    state->scratch_saliency_spatial = NULL;
    free(state->scratch_saliency_color);
    state->scratch_saliency_color = NULL;
    free(state->scratch_saliency_motion);
    state->scratch_saliency_motion = NULL;
    free(state->scratch_saliency_registration);
    state->scratch_saliency_registration = NULL;
    free(state->scratch_thermal_delta);
    state->scratch_thermal_delta = NULL;
    state->scratch_saliency_capacity = 0;
    free(state->scratch_patch_score);
    state->scratch_patch_score = NULL;
    free(state->scratch_patch_selection);
    state->scratch_patch_selection = NULL;
    state->scratch_patch_capacity = 0;
    free(state->scratch_prev_roi_last_luma);
    state->scratch_prev_roi_last_luma = NULL;
    free(state->scratch_prev_roi_thermal_score);
    state->scratch_prev_roi_thermal_score = NULL;
    free(state->scratch_prev_roi_temporal_score);
    state->scratch_prev_roi_temporal_score = NULL;
    free(state->scratch_prev_roi_color_luma);
    state->scratch_prev_roi_color_luma = NULL;
    free(state->scratch_prev_roi_color_u);
    state->scratch_prev_roi_color_u = NULL;
    free(state->scratch_prev_roi_color_v);
    state->scratch_prev_roi_color_v = NULL;
    free(state->scratch_prev_roi_color_raw_score);
    state->scratch_prev_roi_color_raw_score = NULL;
    free(state->scratch_prev_roi_color_contrast_weight);
    state->scratch_prev_roi_color_contrast_weight = NULL;
    free(state->scratch_prev_roi_valid_mask);
    state->scratch_prev_roi_valid_mask = NULL;
    free(state->scratch_prev_roi_coverage_age);
    state->scratch_prev_roi_coverage_age = NULL;
    free(state->scratch_prev_roi_color_valid_mask);
    state->scratch_prev_roi_color_valid_mask = NULL;
    free(state->scratch_prev_roi_color_phase_x);
    state->scratch_prev_roi_color_phase_x = NULL;
    free(state->scratch_prev_roi_color_phase_y);
    state->scratch_prev_roi_color_phase_y = NULL;
    state->scratch_prev_roi_capacity = 0;
    free(state->scratch_refresh_mask);
    state->scratch_refresh_mask = NULL;
    state->scratch_refresh_mask_capacity = 0;
    free(state->scratch_i32);
    state->scratch_i32 = NULL;
    state->scratch_i32_capacity = 0;
}

void anomaly_state_cleanup(anomaly_state_t *state) {
    anomaly_state_reset(state);
}

// ── Main processing ─────────────────────────────────────────────���──────────

int anomaly_process_frame(
        anomaly_state_t        *state,
        const anomaly_config_t *cfg,
        uint8_t                *rgba,
        int                     rgba_stride,
        int                     width,
        int                     height,
        int64_t                 source_ts_us,
        anomaly_result_t       *result_out) {
    (void)source_ts_us;

    anomaly_debug_timing_t timing;
    memset(&timing, 0, sizeof(timing));
    timing.compiled = ANOMALY_DEBUG_TIMING != 0;
    int64_t frame_started_us = anomaly_timing_now_us();

    if (result_out != NULL) {
        memset(result_out, 0, sizeof(*result_out));
        result_out->box_count        = 0;
        result_out->had_discontinuity = false;
        result_out->registration_health = ANOMALY_REG_HEALTH_UNKNOWN;
        result_out->rescan_mode = ANOMALY_RESCAN_MODE_UNSET;
        result_out->scan_plan.mode = ANOMALY_RESCAN_MODE_UNSET;
    }

    if (cfg == NULL) {
        finalize_result_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    bool show_hot_overlay = cfg->show_hot_overlay;
    bool anomaly_detection_active = cfg->enabled && cfg->algorithm_mask != 0;
    if (!anomaly_detection_active && !show_hot_overlay) {
        finalize_result_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    if (width <= 0 || height <= 0) {
        finalize_result_timing(result_out, &timing, frame_started_us);
        return 0;
    }

    int frame_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
    float thermal_min_delta = effective_thermal_min_delta(cfg);
    bool use_publish_transition_gating = cfg->min_hits > 1;
    state->frame_counter += 1;
    bool bg_temporal_ready = (state->bg_luma != NULL &&
                              state->bg_warmup >= ANOMALY_THERMAL_BG_WARMUP &&
                              state->bg_sg_w > 0 && state->bg_sg_h > 0);
    bool bg_publish_ready = bg_temporal_ready &&
                            (!use_publish_transition_gating ||
                             state->bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES) ||
                             state->publish_stable_frames >= ANOMALY_PUBLISH_STABLE_RELEASE_FRAMES);
    if (state->publish_hold_frames > 0) {
        state->publish_hold_frames -= 1;
    }
    bool transition_warmup_block =
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0 &&
        use_publish_transition_gating &&
        state->prev_luma != NULL &&
        !bg_publish_ready;
    bool publish_hold_active = use_publish_transition_gating && state->publish_hold_frames > 0;
    bool should_refresh_appearance = ((state->frame_counter % frame_stride) == 0);
    if (!should_refresh_appearance) {
        age_roi_tracks_one_frame(state);
    }

    // Keep anomaly sampling on the configured scan zone, but let registration
    // pull features from a wider ROI so selective refresh is not starved.
    int roi_x0 = 0;
    int roi_x1 = width;
    int roi_y0 = 0;
    int roi_y1 = height;
    compute_centered_roi_bounds(
        width,
        height,
        cfg->scan_zone,
        &roi_x0,
        &roi_y0,
        &roi_x1,
        &roi_y1);
    int registration_roi_x0 = 0;
    int registration_roi_x1 = width;
    int registration_roi_y0 = 0;
    int registration_roi_y1 = height;
    compute_registration_roi_bounds(
        width,
        height,
        &registration_roi_x0,
        &registration_roi_y0,
        &registration_roi_x1,
        &registration_roi_y1);

    int sample_step = effective_sample_step(cfg, width, height);
    int motion_sample_step = effective_motion_sample_step(cfg, width, height);
    int roi_w = roi_x1 - roi_x0;
    int roi_h = roi_y1 - roi_y0;
    if (roi_w <= 0) roi_w = 1;
    if (roi_h <= 0) roi_h = 1;
    int sg_w = (roi_w + sample_step - 1) / sample_step;
    int sg_h = (roi_h + sample_step - 1) / sample_step;

    // ── Build full-frame luma grid (needed for GMV offset lookups) ───────
    int motion_step  = motion_sample_step * 2;
    int motion_w     = (width  + motion_step - 1) / motion_step;
    int motion_h     = (height + motion_step - 1) / motion_step;
    size_t motion_count = (size_t)motion_w * (size_t)motion_h;
    uint8_t *curr_luma  = NULL;
    uint8_t *curr_registration_luma = NULL;
    int64_t stage_started_us = anomaly_timing_now_us();
    if (motion_count > 0) {
        if (!ensure_u8_capacity(&state->scratch_luma, &state->scratch_luma_capacity, motion_count)) {
            curr_luma = NULL;
        } else {
            curr_luma = state->scratch_luma;
        }
        if (curr_luma != NULL) {
            int idx = 0;
            for (int y = 0; y < height && idx < (int)motion_count; y += motion_step) {
                const uint8_t *row = rgba + (y * rgba_stride);
                for (int x = 0; x < width && idx < (int)motion_count; x += motion_step) {
                    const uint8_t *px = row + (x * 4);
                    curr_luma[idx++] = (uint8_t)((54 * px[0] + 183 * px[1] + 18 * px[2]) >> 8);
                }
            }
        }
        if (curr_luma != NULL &&
            ensure_u8_capacity(&state->scratch_registration_luma,
                               &state->scratch_registration_luma_capacity,
                               motion_count) &&
            ensure_u8_capacity(&state->scratch_registration_tmp,
                               &state->scratch_registration_luma_capacity,
                               motion_count)) {
            curr_registration_luma = state->scratch_registration_luma;
            registration_prefilter_luma_grid(
                curr_luma,
                motion_w,
                motion_h,
                state->scratch_registration_tmp,
                curr_registration_luma);
        } else {
            curr_registration_luma = curr_luma;
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_REGISTRATION_PREP, stage_started_us);

    int registration_mode = normalize_registration_mode(cfg);
    stage_started_us = anomaly_timing_now_us();
    anomaly_registration_model_t registration;
    bool reused_registration = try_load_cached_registration_model(
        &registration,
        state,
        registration_mode,
        motion_sample_step,
        motion_step,
        motion_w,
        motion_h);
    if (!reused_registration) {
        registration = estimate_registration_model(
            cfg,
            curr_registration_luma,
            state,
            width,
            height,
            registration_roi_x0,
            registration_roi_x1,
            registration_roi_y0,
            registration_roi_y1,
            motion_sample_step,
            motion_step,
            motion_w,
            motion_h);
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_REGISTRATION_SOLVE, stage_started_us);
    similarity_2d_t sim = registration.similarity;
    bool scene_discontinuity = registration.scene_discontinuity;
    anomaly_registration_health_t registration_health_base =
        classify_registration_health(&registration, width, height);
    anomaly_registration_health_t registration_health = registration_health_base;
    stage_started_us = anomaly_timing_now_us();
    anomaly_scan_plan_t scan_plan = build_scan_plan(
        state,
        &registration,
        registration_health_base,
        width,
        height,
        roi_x0,
        roi_y0,
        roi_x1,
        roi_y1,
        sample_step,
        sg_w,
        sg_h,
        should_refresh_appearance,
        scene_discontinuity,
        &registration_health);
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SCAN_PLANNING, stage_started_us);
    anomaly_rescan_mode_t rescan_mode = scan_plan.mode;
    cache_registration_model(state, &registration, registration_health, rescan_mode);
    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    uint8_t *appearance_refresh_mask = NULL;
    bool selective_refresh_active = false;
    uint32_t selective_refresh_reason_flags = 0u;
    stage_started_us = anomaly_timing_now_us();
    if ((rescan_mode == ANOMALY_RESCAN_MODE_PARTIAL ||
         rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY) &&
        sg_count > 0) {
        if (ensure_u8_capacity(
                &state->scratch_refresh_mask,
                &state->scratch_refresh_mask_capacity,
                sg_count)) {
            int selected_samples = 0;
            appearance_refresh_mask = state->scratch_refresh_mask;
            selective_refresh_active = build_selective_refresh_mask(
                    state,
                    &registration,
                    rescan_mode,
                    width,
                    height,
                    roi_x0,
                    roi_y0,
                    roi_x1,
                    roi_y1,
                    sample_step,
                    sg_w,
                    sg_h,
                    appearance_refresh_mask,
                    &selected_samples,
                    &selective_refresh_reason_flags);
            scan_plan.refresh_mask_selected_samples = selected_samples;
            if (sg_count > 0) {
                scan_plan.refresh_mask_selected_fraction =
                    (float)selected_samples / (float)sg_count;
            }
        }
        if (!selective_refresh_active) {
            appearance_refresh_mask = NULL;
            rescan_mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.reason_flags |= selective_refresh_reason_flags;
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_REFRESH_MASK_BUILD, stage_started_us);

    if (result_out != NULL) {
        result_out->had_discontinuity = scene_discontinuity;
        result_out->registration_ran_this_frame = true;
        result_out->appearance_refresh_ran_this_frame = should_refresh_appearance;
        result_out->registration_health = registration_health;
        result_out->rescan_mode = rescan_mode;
        result_out->scan_plan = scan_plan;
        populate_registration_debug(&registration, result_out);
    }

    // ── Compensate accumulators for camera motion (or wipe on discontinuity)
    // T⁻¹(p) = Aᵀ * (p - t) / (a²+b²)  where Aᵀ = [[a,b],[-b,a]]
    stage_started_us = anomaly_timing_now_us();
    if (scene_discontinuity) {
        clear_all_roi_tracks(state);
    } else {
        for (int ai = 0; ai < 4; ai++) {
            if (state->acc_active[ai] && registration_model_valid(&registration)) {
            float nx = 0.0f;
            float ny = 0.0f;
            if (registration_invert_point(&registration, state->acc_cx[ai], state->acc_cy[ai], &nx, &ny)) {
                state->acc_cx[ai] = nx < 0.0f ? 0.0f : (nx > 1.0f ? 1.0f : nx);
                state->acc_cy[ai] = ny < 0.0f ? 0.0f : (ny > 1.0f ? 1.0f : ny);
            }
            }
        }
    }
    predict_target_tracks_with_registration(
            state,
            &registration,
            registration_health,
            scene_discontinuity);
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_TARGET_TRACKING, stage_started_us);

    if (!should_refresh_appearance) {
        age_roi_state_one_frame(state, scene_discontinuity, registration_health);
        update_prev_luma_state(state, curr_luma, motion_count, motion_w, motion_h);
        update_prev_registration_luma_state(
            state,
            curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
            motion_count,
            motion_w,
            motion_h);
        int skipped_box_count = 0;
        anomaly_box_t skipped_boxes[ANOMALY_MAX_BOXES_PER_FRAME];
        bool publish_allowed = !transition_warmup_block && !publish_hold_active;
        if (anomaly_detection_active && publish_allowed) {
            const int skipped_motion_box_algorithm =
                    (cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0
                    ? ANOMALY_ALGO_MOTION_TOLERANCE
                    : ANOMALY_ALGO_MOTION;
            skipped_box_count = assemble_anomaly_boxes(
                    state,
                    cfg,
                    skipped_motion_box_algorithm,
                    skipped_boxes,
                    ANOMALY_MAX_BOXES_PER_FRAME);
        }
        if (result_out != NULL) {
            result_out->box_count = skipped_box_count;
            for (int i = 0; i < skipped_box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++) {
                result_out->boxes[i] = skipped_boxes[i];
            }
        }
        stage_started_us = anomaly_timing_now_us();
        if (rgba != NULL) {
            if (show_hot_overlay) {
                draw_hot_overlay_rgba(rgba, rgba_stride, width, height, cfg->thermal_polarity);
            }
            if (skipped_box_count > 0) {
                draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, skipped_boxes, skipped_box_count);
            }
        }
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_OVERLAY_DRAW, stage_started_us);
        finalize_result_timing(result_out, &timing, frame_started_us);
        return skipped_box_count;
    }

    // ── Statistics pass ──────────────────────────────────────────────────
    // Thermal detection uses integral-image local statistics so each sample
    // point is compared against its immediate pixel neighbourhood rather than
    // a coarse tile.  This is critical for aerial SAR footage: the scene has
    // high global variance (tree crowns vs. clearings) so any tile large
    // enough to contain reliable statistics also spans multiple features,
    // making a person's subtle warmth statistically invisible at 8σ.
    // With a small window (ANOMALY_THERMAL_WIN_RADIUS sampled pixels ≈
    // RADIUS×sample_step real pixels) the window usually stays inside one
    // clearing, giving the person's darker signature a fair local comparison.
    //
    // Color detection retains the tile-grid approach (ANOMALY_LOCAL_TILE_SIZE);
    // in IR footage all channels are near-greyscale so colour is less useful,
    // and the tile grid is cheap to keep for visible-light modes.

    // Heap-allocate integral image arrays (freed at end of this function).
    // Two channels: luma sum and luma sum-of-squares for variance.
    stage_started_us = anomaly_timing_now_us();
    if (!ensure_float_capacity(&state->scratch_sg_luma, &state->scratch_sampled_grid_capacity, sg_count) ||
        !ensure_float_capacity(&state->scratch_ii_sum, &state->scratch_sampled_grid_capacity, sg_count) ||
        !ensure_float_capacity(&state->scratch_ii_sum2, &state->scratch_sampled_grid_capacity, sg_count)) {
        update_prev_luma_state(state, curr_luma, motion_count, motion_w, motion_h);
        update_prev_registration_luma_state(
            state,
            curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
            motion_count,
            motion_w,
            motion_h);
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);
        finalize_result_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    float *sg_luma  = state->scratch_sg_luma;
    float *ii_sum   = state->scratch_ii_sum;
    float *ii_sum2  = state->scratch_ii_sum2;

    bool color_algorithm_enabled =
        anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0;
    bool need_color_support = color_algorithm_enabled;
    bool need_thermal_support_map =
        anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0;
    bool need_color_support_map = color_algorithm_enabled;

    int color_phase_index = 0;
    int color_phase_x = 0;
    int color_phase_y = 0;
    color_sampling_phase_for_frame(state, sample_step, &color_phase_index, &color_phase_x, &color_phase_y);
    bool color_forced_full_refresh = false;
    uint32_t color_fallback_reason_flags = 0u;
    int color_fresh_sample_count = 0;
    int color_carried_sample_count = 0;
    int color_unsampled_new_count = 0;

    // Global sums (used for thermal/global luma stats).
    double sum_l = 0.0, sum_l2 = 0.0;
    int sample_count = 0;

    // Fill sampled-luma grid and integral images in one pass.
    for (int sy = 0; sy < sg_h; sy++) {
        int y  = roi_y0 + sy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        int row_offset = sy * sg_w;
        int prev_row_offset = row_offset - sg_w;
        for (int sx = 0; sx < sg_w; sx++) {
            int x  = roi_x0 + sx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            const uint8_t *px = row + (x * 4);
            float r = (float)px[0], g = (float)px[1], b = (float)px[2];
            float lum = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
            float lum2 = lum * lum;
            int idx = row_offset + sx;
            sg_luma[idx] = lum;
            float a = (sy > 0) ? ii_sum[prev_row_offset + sx] : 0.0f;
            float l = (sx > 0) ? ii_sum[idx - 1] : 0.0f;
            float al = (sy > 0 && sx > 0) ? ii_sum[prev_row_offset + (sx - 1)] : 0.0f;
            ii_sum[idx] = lum + a + l - al;
            float a2 = (sy > 0) ? ii_sum2[prev_row_offset + sx] : 0.0f;
            float l2 = (sx > 0) ? ii_sum2[idx - 1] : 0.0f;
            float al2 = (sy > 0 && sx > 0) ? ii_sum2[prev_row_offset + (sx - 1)] : 0.0f;
            ii_sum2[idx] = lum2 + a2 + l2 - al2;
            sum_l  += lum; sum_l2 += lum2;
            sample_count++;
        }
    }

    if (sample_count <= 1) {
        if (curr_luma != NULL) {
            if (ensure_u8_capacity(&state->prev_luma, &state->prev_luma_capacity, motion_count)) {
                memcpy(state->prev_luma, curr_luma, motion_count * sizeof(uint8_t));
                state->prev_luma_width  = motion_w;
                state->prev_luma_height = motion_h;
            } else {
                free(state->prev_luma);
                state->prev_luma = NULL;
                state->prev_luma_capacity = 0;
                state->prev_luma_width = 0;
                state->prev_luma_height = 0;
            }
        }
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);
        finalize_result_timing(result_out, &timing, frame_started_us);
        return 0;
    }

    if (need_color_support) {
        bool color_sampling_prepared = prepare_color_sampling_state(
                state,
                &registration,
                rgba,
                rgba_stride,
                width,
                height,
                roi_x0,
                roi_y0,
                roi_x1,
                roi_y1,
                sample_step,
                sg_w,
                sg_h,
                selective_refresh_active,
                appearance_refresh_mask,
                color_phase_x,
                color_phase_y,
                &color_forced_full_refresh,
                &color_fallback_reason_flags,
                &color_fresh_sample_count,
                &color_carried_sample_count,
                &color_unsampled_new_count);
        if (!color_sampling_prepared) {
            color_forced_full_refresh = true;
            color_fallback_reason_flags |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
            prepare_color_sampling_state(
                    state,
                    &registration,
                    rgba,
                    rgba_stride,
                    width,
                    height,
                    roi_x0,
                    roi_y0,
                    roi_x1,
                    roi_y1,
                    sample_step,
                    sg_w,
                    sg_h,
                    false,
                    NULL,
                    color_phase_x,
                    color_phase_y,
                    &color_forced_full_refresh,
                    &color_fallback_reason_flags,
                    &color_fresh_sample_count,
                    &color_carried_sample_count,
                    &color_unsampled_new_count);
        }
        scan_plan.reason_flags |= color_fallback_reason_flags;
        if (color_forced_full_refresh && result_out != NULL) {
            result_out->scan_plan.reason_flags |= color_fallback_reason_flags;
        }
        if (result_out != NULL) {
            result_out->scan_plan = scan_plan;
        }
    }

    double g_mean_l = sum_l / (double)sample_count;
    double g_std_l  = sqrt(fmax((sum_l2/(double)sample_count) - g_mean_l*g_mean_l, 1.0));
    uint8_t *color_frame_hist = NULL;
    int color_hist_valid_samples = 0;
    if (need_color_support) {
        anomaly_roi_state_t *roi_state = &state->roi_state;
        compute_color_contrast_weights(roi_state, sg_w, sg_h);
        if (ensure_color_hist_capacity(&state->scratch_color_hist, &state->scratch_color_hist_bins) &&
            ensure_color_hist_capacity(&state->color_recent_hist, &state->color_recent_hist_bins)) {
            color_frame_hist = state->scratch_color_hist;
            color_hist_valid_samples = build_color_frame_histogram(roi_state, sg_w, sg_h, color_frame_hist);
            if (color_hist_valid_samples <= 0) {
                memset(color_frame_hist, 0, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
            }
        } else {
            color_frame_hist = NULL;
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);

    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    bool bg_valid = (state->bg_luma != NULL
                     && state->bg_sg_w == sg_w
                     && state->bg_sg_h == sg_h
                     && state->bg_warmup >= ANOMALY_THERMAL_BG_WARMUP
                     && !scene_discontinuity);
    bool compute_spatial_thermal_scores =
        anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_MOTION |
                                ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0 &&
        !(bg_valid && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0);

    // ── Per-pixel scoring ────────────────────────────────────────────────
    // Thermal: integral-image local window of radius ANOMALY_THERMAL_WIN_RADIUS
    //   sampled pixels.  At sample_step=4 (HD/FHD) that is a
    //   (2R+1)×(2R+1) window of roughly (2R+1)×4 real pixels on each side.
    //   R=3 → 7×7 samples ≈ 28×28 real pixels — small enough to stay inside
    //   a single clearing yet large enough for reliable statistics (49 samples).
    //   Produces a best-candidate pixel (best_thermal) used as fallback during
    //   EMA background warmup; after warmup the temporal pass below replaces it.
    // Color: frame-level rarity gate followed by the existing support/blob stages.

    // Inline integral-image rectangle query.
    bool need_motion_candidates =
        anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0;
    float *saliency_spatial_map = NULL;
    float *saliency_color_map = NULL;
    float *saliency_motion_map = NULL;
    float *saliency_registration_map = NULL;
    float *color_support_scratch = NULL;
    if (need_thermal_support_map || need_color_support_map || need_motion_candidates) {
        if ((!need_thermal_support_map ||
             ensure_float_capacity(&state->scratch_saliency_spatial, &state->scratch_saliency_capacity, sg_count)) &&
            (!need_color_support_map ||
             (ensure_float_capacity(&state->scratch_saliency_color, &state->scratch_saliency_capacity, sg_count) &&
              ensure_float_capacity(&state->scratch_patch_score, &state->scratch_patch_capacity, sg_count))) &&
            (!((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) ||
             (ensure_float_capacity(&state->scratch_saliency_motion, &state->scratch_saliency_capacity, sg_count) &&
              ensure_float_capacity(&state->scratch_saliency_registration, &state->scratch_saliency_capacity, sg_count)))) {
            if (need_thermal_support_map) saliency_spatial_map = state->scratch_saliency_spatial;
            if (need_color_support_map) saliency_color_map = state->scratch_saliency_color;
            if (need_color_support_map) color_support_scratch = state->scratch_patch_score;
            if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
                saliency_motion_map  = state->scratch_saliency_motion;
                saliency_registration_map = state->scratch_saliency_registration;
            }
        }
        if (saliency_spatial_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_spatial_map[i] = -1.0f;
        }
        if (saliency_color_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_color_map[i] = 0.0f;
        }
        if (saliency_motion_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_motion_map[i] = 0.0f;
        }
        if (saliency_registration_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_registration_map[i] = 1.0f;
        }
    }
    float best_color = -1.0f, best_thermal = -1.0f, best_persist = -1.0f;
    int   best_color_x = 0, best_color_y = 0;
    int   best_thermal_x = 0, best_thermal_y = 0;
    int   best_persist_x = 0, best_persist_y = 0;
    int   best_thermal_candidate_idx = -1;
    float best_thermal_candidate_score = -1.0f;
    anomaly_debug_candidate_t saliency_top[ANOMALY_DEBUG_TOP_CANDIDATES];
    memset(saliency_top, 0, sizeof(saliency_top));
    int saliency_top_count = 0;
    float saliency_tracked_score_pre = -1.0f;
    bool saliency_switch_suppressed = false;
    bool color_selective_refresh_active = selective_refresh_active && !color_forced_full_refresh;
    int color_active_min_sx = 0;
    int color_active_min_sy = 0;
    int color_active_max_sx = sg_w > 0 ? (sg_w - 1) : -1;
    int color_active_max_sy = sg_h > 0 ? (sg_h - 1) : -1;
    if (color_algorithm_enabled && color_selective_refresh_active && appearance_refresh_mask != NULL) {
        int color_bounds_pad = color_support_patch_radius(cfg, width, height, sample_step);
        compute_active_mask_bounds(
                appearance_refresh_mask,
                sg_w,
                sg_h,
                color_bounds_pad,
                &color_active_min_sx,
                &color_active_min_sy,
                &color_active_max_sx,
                &color_active_max_sy);
    }

    const int R = effective_thermal_window_radius_cells(sample_step);

    for (int sy = 0; sy < sg_h; sy++) {
        int y  = roi_y0 + sy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;

        for (int sx = 0; sx < sg_w; sx++) {
            int x  = roi_x0 + sx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;

            float lum = sg_luma[idx];
            bool thermal_refresh_skip = selective_refresh_active && appearance_refresh_mask[idx] == 0u;
            bool color_refresh_skip = color_selective_refresh_active && appearance_refresh_mask[idx] == 0u;

            if (compute_spatial_thermal_scores && !thermal_refresh_skip) {
#if ANOMALY_DEBUG_TIMING
                int64_t branch_started_us = anomaly_timing_now_us();
#endif
                // Integral-image window query.
                int wx0 = sx - R; if (wx0 < 0) wx0 = 0;
                int wx1 = sx + R; if (wx1 >= sg_w) wx1 = sg_w - 1;
                int wy0 = sy - R; if (wy0 < 0) wy0 = 0;
                int wy1 = sy + R; if (wy1 >= sg_h) wy1 = sg_h - 1;
                int    n    = (wx1-wx0+1) * (wy1-wy0+1);
                float wsum  = ii_query(ii_sum,  sg_w, wx0, wy0, wx1, wy1);
                float wsum2 = ii_query(ii_sum2, sg_w, wx0, wy0, wx1, wy1);
                float mean  = wsum / (float)n;
                float std   = sqrtf(fmaxf(wsum2/(float)n - mean*mean, 1.0f));
                float abs_delta = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                                   ? (mean - lum) : (lum - mean);
                if (abs_delta >= thermal_min_delta) {
                    float ts = abs_delta / std;
                    float global_dark_score = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                        ? (float)((g_mean_l - lum) / g_std_l)
                        : (float)((lum - g_mean_l) / g_std_l);
                    if (global_dark_score < 0.0f) global_dark_score = 0.0f;
                    if (global_dark_score > 3.0f) global_dark_score = 3.0f;
                    float saliency_spatial = ts + 0.85f * global_dark_score;
                    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
                        ts > best_thermal) {
                        best_thermal = ts; best_thermal_x = x; best_thermal_y = y;
                    }
                    if (saliency_spatial_map != NULL) {
                        saliency_spatial_map[idx] = saliency_spatial;
                    }
                }
#if ANOMALY_DEBUG_TIMING
                anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_THERMAL_SCORING, branch_started_us);
#endif
            }

            if (color_algorithm_enabled && !color_refresh_skip) {
#if ANOMALY_DEBUG_TIMING
                int64_t branch_started_us = anomaly_timing_now_us();
#endif
                anomaly_roi_state_t *roi_state = &state->roi_state;
                if (roi_state->color_valid_mask != NULL &&
                    roi_state->color_valid_mask[idx] != 0u &&
                    color_frame_hist != NULL) {
                    int u_bin = (int)roi_state->color_u_bin[idx];
                    int v_bin = (int)roi_state->color_v_bin[idx];
                    float rarity = score_color_hist_family_rarity(
                        color_frame_hist,
                        state->color_recent_hist,
                        u_bin,
                        v_bin);
                    roi_state->color_raw_score[idx] = rarity;
                    if (saliency_color_map != NULL) {
                        int local_support = local_uv_support_count(
                            roi_state,
                            sg_w,
                            sg_h,
                            sx,
                            sy,
                            u_bin,
                            v_bin);
                        if (rarity < ANOMALY_COLOR_RARITY_MIN ||
                            local_support < ANOMALY_COLOR_LOCAL_SUPPORT_MIN) {
                            saliency_color_map[idx] = 0.0f;
                        } else {
                            float score = (rarity - ANOMALY_COLOR_RARITY_MIN) *
                                          ANOMALY_COLOR_RARITY_SCALE;
                            float support_scale = clampf(
                                0.60f + 0.20f * (float)(local_support - ANOMALY_COLOR_LOCAL_SUPPORT_MIN),
                                0.60f,
                                1.20f);
                            saliency_color_map[idx] = clampf(score * support_scale, 0.0f, 4.0f);
                        }
                    }
                } else {
                    if (saliency_color_map != NULL) saliency_color_map[idx] = 0.0f;
                    if (roi_state->color_raw_score != NULL) roi_state->color_raw_score[idx] = 0.0f;
                }
#if ANOMALY_DEBUG_TIMING
                anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_SCORING, branch_started_us);
#endif
            }
        }
    }

    anomaly_motion_candidate_t color_candidates[ANOMALY_MAX_COLOR_CANDIDATES];
    memset(color_candidates, 0, sizeof(color_candidates));
    int color_candidate_count = 0;
    int best_color_candidate_idx = -1;
    float color_candidate_area[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_span[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_fill[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_center_share[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_base_score[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_final_score[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_quality[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_isolation[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_ring_fraction[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_support_mass[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_contrast_weight[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_retention_rank[ANOMALY_MAX_COLOR_CANDIDATES];
    bool color_candidate_above_threshold[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_min_x[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_min_y[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_max_x[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_max_y[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_hist_key[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_hist_current_count[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_hist_recent_count[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_hist_rarity[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_hist_nonzero_bins = 0;
    float color_hist_max_current_count = 0.0f;
    float color_hist_max_recent_count = 0.0f;
    if (color_algorithm_enabled &&
        saliency_color_map != NULL &&
        color_support_scratch != NULL &&
        ensure_u8_capacity(&state->scratch_u8, &state->scratch_u8_capacity, sg_count) &&
        ensure_int_capacity(&state->scratch_i32, &state->scratch_i32_capacity, sg_count)) {
#if ANOMALY_DEBUG_TIMING
        int64_t color_post_started_us = anomaly_timing_now_us();
#endif
        float color_support_peak = 0.0f;
        int color_seed_min_sx = sg_w;
        int color_seed_min_sy = sg_h;
        int color_seed_max_sx = -1;
        int color_seed_max_sy = -1;
        int color_seed_count = 0;
        build_color_support_map(
                cfg,
                saliency_color_map,
                state->roi_state.color_contrast_weight,
                sg_w,
                sg_h,
                width,
                height,
                sample_step,
                color_active_min_sx,
                color_active_min_sy,
                color_active_max_sx,
                color_active_max_sy,
                saliency_color_map,
                color_support_scratch,
                &color_support_peak,
                &color_seed_min_sx,
                &color_seed_min_sy,
                &color_seed_max_sx,
                &color_seed_max_sy,
                &color_seed_count);
        anomaly_color_blob_candidate_t color_blob_candidates[ANOMALY_MAX_COLOR_CANDIDATES];
        memset(color_blob_candidates, 0, sizeof(color_blob_candidates));
        if (color_support_peak >= 0.55f &&
            color_seed_count > 0 &&
            color_seed_max_sx >= color_seed_min_sx &&
            color_seed_max_sy >= color_seed_min_sy) {
            extract_color_blob_candidates(
                    cfg,
                    saliency_color_map,
                    state->roi_state.color_contrast_weight,
                    sg_w,
                    sg_h,
                    width,
                    height,
                    roi_x0,
                    roi_y0,
                    sample_step,
                    color_seed_min_sx,
                    color_seed_min_sy,
                    color_seed_max_sx,
                    color_seed_max_sy,
                    state->scratch_u8,
                    state->scratch_i32,
                    color_blob_candidates,
                    &color_candidate_count);
        }
#if ANOMALY_DEBUG_TIMING
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_SCORING, color_post_started_us);
#endif
        best_color = -1.0f;
        best_color_x = 0;
        best_color_y = 0;
        for (int ci = 0; ci < color_candidate_count; ci++) {
            color_candidates[ci] = color_blob_candidates[ci].candidate;
            color_candidate_area[ci] = color_blob_candidates[ci].area;
            color_candidate_span[ci] = color_blob_candidates[ci].span;
            color_candidate_fill[ci] = color_blob_candidates[ci].fill;
            color_candidate_center_share[ci] = color_blob_candidates[ci].center_share;
            color_candidate_base_score[ci] = color_blob_candidates[ci].peak_support;
            color_candidate_final_score[ci] = color_blob_candidates[ci].candidate.color_score;
            color_candidate_quality[ci] = color_blob_candidates[ci].quality;
            color_candidate_isolation[ci] = color_blob_candidates[ci].isolation_score;
            color_candidate_ring_fraction[ci] = color_blob_candidates[ci].ring_fraction;
            color_candidate_support_mass[ci] = color_blob_candidates[ci].support_mass;
            color_candidate_contrast_weight[ci] =
                state->roi_state.color_contrast_weight != NULL
                ? state->roi_state.color_contrast_weight[(size_t)color_candidates[ci].sg_y * (size_t)sg_w +
                                                        (size_t)color_candidates[ci].sg_x]
                : 1.0f;
            color_candidate_retention_rank[ci] = color_blob_candidates[ci].retention_rank;
            color_candidate_above_threshold[ci] =
                color_blob_candidates[ci].candidate.color_score >= cfg->score_threshold;
            color_candidate_min_x[ci] = color_blob_candidates[ci].min_x;
            color_candidate_min_y[ci] = color_blob_candidates[ci].min_y;
            color_candidate_max_x[ci] = color_blob_candidates[ci].max_x;
            color_candidate_max_y[ci] = color_blob_candidates[ci].max_y;
            color_candidate_hist_key[ci] = -1;
            color_candidate_hist_current_count[ci] = 0.0f;
            color_candidate_hist_recent_count[ci] = 0.0f;
            color_candidate_hist_rarity[ci] = 0.0f;
            if (color_frame_hist != NULL && state->roi_state.color_valid_mask != NULL) {
                size_t cidx = (size_t)color_candidates[ci].sg_y * (size_t)sg_w +
                              (size_t)color_candidates[ci].sg_x;
                if (state->roi_state.color_valid_mask[cidx] != 0u) {
                    int u_bin = (int)state->roi_state.color_u_bin[cidx];
                    int v_bin = (int)state->roi_state.color_v_bin[cidx];
                    int key = color_hist_key(u_bin, v_bin);
                    color_candidate_hist_key[ci] = key;
                    color_candidate_hist_current_count[ci] = (float)color_frame_hist[key];
                    color_candidate_hist_recent_count[ci] = state->color_recent_hist != NULL
                        ? (float)state->color_recent_hist[key]
                        : 0.0f;
                    color_candidate_hist_rarity[ci] = score_color_hist_family_rarity(
                        color_frame_hist,
                        state->color_recent_hist,
                        u_bin,
                        v_bin);
                }
            }
            if (color_candidate_above_threshold[ci]) {
                if (best_color_candidate_idx < 0 ||
                    compare_color_blob_rank(&color_blob_candidates[ci],
                                            &color_blob_candidates[best_color_candidate_idx]) < 0) {
                    best_color_candidate_idx = ci;
                }
            } else if (color_blob_candidates[ci].candidate.color_score > best_color) {
                best_color = color_blob_candidates[ci].candidate.color_score;
                best_color_x = color_blob_candidates[ci].candidate.pixel_x;
                best_color_y = color_blob_candidates[ci].candidate.pixel_y;
            }
        }
        if (best_color_candidate_idx >= 0) {
            best_color = color_candidates[best_color_candidate_idx].color_score;
            best_color_x = color_candidates[best_color_candidate_idx].pixel_x;
            best_color_y = color_candidates[best_color_candidate_idx].pixel_y;
        }
    }
    if (color_frame_hist != NULL) {
        for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
            float cur = (float)color_frame_hist[i];
            float rec = state->color_recent_hist != NULL ? (float)state->color_recent_hist[i] : 0.0f;
            if (cur > 0.0f) color_hist_nonzero_bins++;
            if (cur > color_hist_max_current_count) color_hist_max_current_count = cur;
            if (rec > color_hist_max_recent_count) color_hist_max_recent_count = rec;
        }
        update_color_recent_histogram(
            state,
            color_frame_hist,
            scene_discontinuity || color_forced_full_refresh || color_hist_valid_samples <= 0);
    }

    anomaly_motion_candidate_t motion_candidates[ANOMALY_MAX_MOTION_CANDIDATES];
    memset(motion_candidates, 0, sizeof(motion_candidates));
    anomaly_motion_candidate_t thermal_candidates[ANOMALY_MAX_THERMAL_CANDIDATES];
    memset(thermal_candidates, 0, sizeof(thermal_candidates));
    int motion_candidate_count = 0;
    int thermal_candidate_count = 0;
    float thermal_candidate_area[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_span[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_fill[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_center_share[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_base_score[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_temporal_score[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_quality_score[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_context_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_parent_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_isolation_rank[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_peak_delta[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_mean_delta[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_score_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_history_scale_debug[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_apparent_size_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_isolation_track_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_patch_support[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_motion_support[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_singleton_score_scale[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_retention_rank_debug[ANOMALY_MAX_THERMAL_CANDIDATES];
    bool thermal_candidate_singleton_blob_debug[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_area_rank[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_span_rank[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_center_rank[ANOMALY_MAX_THERMAL_CANDIDATES];
    float thermal_candidate_quality_rank[ANOMALY_MAX_THERMAL_CANDIDATES];
    bool thermal_candidate_above_threshold[ANOMALY_MAX_THERMAL_CANDIDATES];
    int thermal_candidate_min_x[ANOMALY_MAX_THERMAL_CANDIDATES];
    int thermal_candidate_min_y[ANOMALY_MAX_THERMAL_CANDIDATES];
    int thermal_candidate_max_x[ANOMALY_MAX_THERMAL_CANDIDATES];
    int thermal_candidate_max_y[ANOMALY_MAX_THERMAL_CANDIDATES];
    float motion_candidate_support[ANOMALY_MAX_MOTION_CANDIDATES];
    int motion_candidate_support_x[ANOMALY_MAX_MOTION_CANDIDATES];
    int motion_candidate_support_y[ANOMALY_MAX_MOTION_CANDIDATES];
    anomaly_thermal_target_trace_t thermal_target_trace;
    memset(&thermal_target_trace, 0, sizeof(thermal_target_trace));
    thermal_target_trace.suppressor_sx = -1;
    thermal_target_trace.suppressor_sy = -1;
    thermal_target_trace.component_seed_x = -1;
    thermal_target_trace.component_seed_y = -1;
    thermal_target_trace.component_peak_x = -1;
    thermal_target_trace.component_peak_y = -1;
    thermal_target_trace.extracted_rank = -1;
    thermal_target_trace.winning_rank = -1;
    for (int i = 0; i < ANOMALY_MAX_THERMAL_CANDIDATES; i++) {
        thermal_candidate_area[i] = 0.0f;
        thermal_candidate_span[i] = 0.0f;
        thermal_candidate_fill[i] = 0.0f;
        thermal_candidate_center_share[i] = 0.0f;
        thermal_candidate_base_score[i] = -1.0f;
        thermal_candidate_temporal_score[i] = -1.0f;
        thermal_candidate_quality_score[i] = 0.0f;
        thermal_candidate_context_scale[i] = 1.0f;
        thermal_candidate_parent_scale[i] = 1.0f;
        thermal_candidate_isolation_rank[i] = 0.0f;
        thermal_candidate_peak_delta[i] = 0.0f;
        thermal_candidate_mean_delta[i] = 0.0f;
        thermal_candidate_score_scale[i] = 1.0f;
        thermal_candidate_history_scale_debug[i] = 1.0f;
        thermal_candidate_apparent_size_scale[i] = 1.0f;
        thermal_candidate_isolation_track_scale[i] = 1.0f;
        thermal_candidate_patch_support[i] = 0.0f;
        thermal_candidate_motion_support[i] = 0.0f;
        thermal_candidate_singleton_score_scale[i] = 1.0f;
        thermal_candidate_retention_rank_debug[i] = 0.0f;
        thermal_candidate_singleton_blob_debug[i] = false;
        thermal_candidate_area_rank[i] = 0.0f;
        thermal_candidate_span_rank[i] = 0.0f;
        thermal_candidate_center_rank[i] = 0.0f;
        thermal_candidate_quality_rank[i] = 0.0f;
        thermal_candidate_above_threshold[i] = false;
        thermal_candidate_min_x[i] = 0;
        thermal_candidate_min_y[i] = 0;
        thermal_candidate_max_x[i] = 0;
        thermal_candidate_max_y[i] = 0;
    }
    for (int i = 0; i < ANOMALY_MAX_MOTION_CANDIDATES; i++) {
        motion_candidate_support[i] = -1.0f;
        motion_candidate_support_x[i] = 0;
        motion_candidate_support_y[i] = 0;
    }

    stage_started_us = anomaly_timing_now_us();
    // ── One-sided EMA thermal background: score + update ────────────────
    // The background model tracks each pixel's "cold" (background) state.
    // Fast adaptation toward brighter/colder (α=ALPHA_COOL per analyzed frame)
    // means legitimate scene changes (drone drift, lighting) are absorbed
    // quickly.  Slow adaptation toward darker/warmer (α=ALPHA_WARM) means a
    // subject that is persistently warmer than its local history scores high
    // every frame the camera is on them.  Score = (bg - current) / NORM.
    //
    // This second thermal pass REPLACES the spatial score from the loop above
    // once the background model has warmed up.  During the first
    // ANOMALY_THERMAL_BG_WARMUP analyzed frames after init or scene cut, the
    // spatial integral-image score above provides uninterrupted coverage.
    float *thermal_delta_map = NULL;
    float delta_mean = 0.0f;
    float delta_norm = ANOMALY_THERMAL_BG_NORM;
    float frame_blob_contrast_mean = 0.0f;
    float frame_blob_contrast_std = 0.0f;
    if (result_out != NULL) {
        result_out->saliency_debug.bg_ready = bg_valid;
    }

    if (anomaly_detection_active && bg_valid && (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
        if (ensure_float_capacity(&state->scratch_thermal_delta, &state->scratch_saliency_capacity, sg_count)) {
            thermal_delta_map = state->scratch_thermal_delta;
        }
        double sum_d = 0.0, sum_d2 = 0.0;
        int cnt_d = 0;
        for (int i = 0; i < sg_w * sg_h; i++) {
            float d = black_hot
                ? (state->bg_luma[i] - (float)sg_luma[i])
                : ((float)sg_luma[i] - state->bg_luma[i]);
            if (thermal_delta_map != NULL) thermal_delta_map[i] = d;
            if (d > 0.0f) { sum_d += d; sum_d2 += (double)d * d; cnt_d++; }
        }
        delta_mean = cnt_d > 0 ? (float)(sum_d / (double)cnt_d) : 0.0f;
        double delta_var = cnt_d > 1
            ? fmax(sum_d2 / (double)cnt_d - delta_mean * delta_mean, 0.0) : 0.0;
        delta_norm = (float)sqrt(delta_var);
        if (delta_norm < ANOMALY_THERMAL_BG_NORM)
            delta_norm = ANOMALY_THERMAL_BG_NORM;
        estimate_framewide_blob_contrast_stats(
                thermal_delta_map,
                state->bg_luma,
                sg_luma,
                sg_w,
                sg_h,
                bg_valid,
                black_hot != 0,
                thermal_min_delta,
                &frame_blob_contrast_mean,
                &frame_blob_contrast_std);
    }

    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 && bg_valid) {
        // Temporal background score replaces the spatial integral-image score.
        // Two-pass approach implementing per-frame noise-floor normalisation:
        //   When the camera pans over warm vegetation the bg_delta map has a
        //   high mean and high spread — nearly all pixels look "warm."  Scoring
        //   each pixel relative to the frame's own bg_delta distribution (mean
        //   and std) automatically raises the bar in high-motion frames and
        //   lowers it in quiet frames.  A subject that is merely the warmest
        //   warm thing in a sea of panning-induced warmth scores only modestly;
        //   a subject that is dramatically warmer than the frame's temporal
        //   noise floor scores very high.
        //
        //   Pass 1: accumulate positive bg_delta statistics (mean, std).
        //   Pass 2: score = (delta - delta_mean) / delta_norm
        //     where delta_norm = max(delta_std, ANOMALY_THERMAL_BG_NORM).
        //   Using the fixed NORM as a floor ensures the formula degrades
        //   gracefully to the old fixed-threshold behaviour in quiet frames.

        // Pass 2 — find the pixel with the highest relative temporal score.
        best_thermal   = -1.0f;
        best_thermal_x = 0;
        best_thermal_y = 0;
        for (int sy = 0; sy < sg_h; sy++) {
            for (int sx = 0; sx < sg_w; sx++) {
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                if (selective_refresh_active && appearance_refresh_mask[idx] == 0u) {
                    continue;
                }
                // Positive delta: pixel is warmer than its stored background.
                float delta = thermal_delta_map != NULL
                    ? thermal_delta_map[idx]
                    : thermal_delta_from_maps(
                        thermal_delta_map,
                        state->bg_luma,
                        sg_luma,
                        idx,
                        black_hot != 0);
                if (delta < thermal_min_delta) {
                    if (saliency_spatial_map != NULL) {
                        saliency_spatial_map[idx] = -1.0f;
                    }
                    continue;
                }
                // Relative score: std-devs above the frame's temporal mean.
                float ts = (delta - delta_mean) / delta_norm;
                if (saliency_spatial_map != NULL) {
                    saliency_spatial_map[idx] = ts;
                }
                if (ts > best_thermal) {
                    best_thermal   = ts;
                    best_thermal_x = roi_x0 + sx * sample_step;
                    best_thermal_y = roi_y0 + sy * sample_step;
                }
            }
        }
    }

    if (anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
        saliency_spatial_map != NULL) {
        bool thermal_publish_settled =
            cfg->min_hits <= 1 ||
            state->bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES);
        if (scene_discontinuity || state->thermal_target_persist == NULL ||
            state->thermal_target_persist_w != sg_w || state->thermal_target_persist_h != sg_h) {
            free(state->thermal_target_persist);
            state->thermal_target_persist =
                (float *)calloc((size_t)sg_w * (size_t)sg_h, sizeof(float));
            state->thermal_target_persist_w =
                state->thermal_target_persist != NULL ? sg_w : 0;
            state->thermal_target_persist_h =
                state->thermal_target_persist != NULL ? sg_h : 0;
        } else if (state->thermal_target_persist != NULL) {
            for (size_t i = 0; i < sg_count; i++) {
                state->thermal_target_persist[i] *= ANOMALY_THERMAL_TARGET_HISTORY_DECAY;
            }
        }
        float *thermal_patch_selection = NULL;
        float *thermal_value_map = NULL;
        uint8_t *thermal_visited = NULL;
        int *thermal_queue = NULL;
        if (ensure_float_capacity(&state->scratch_patch_score, &state->scratch_patch_capacity, sg_count) &&
            ensure_float_capacity(&state->scratch_patch_selection, &state->scratch_patch_capacity, sg_count) &&
            ensure_u8_capacity(&state->scratch_u8, &state->scratch_u8_capacity, sg_count) &&
            ensure_int_capacity(&state->scratch_i32, &state->scratch_i32_capacity, sg_count)) {
            thermal_value_map = state->scratch_patch_score;
            thermal_patch_selection = state->scratch_patch_selection;
            thermal_visited = state->scratch_u8;
            thermal_queue = state->scratch_i32;
        }
        if (thermal_value_map != NULL && thermal_patch_selection != NULL &&
            thermal_visited != NULL && thermal_queue != NULL) {
            anomaly_thermal_blob_candidate_t thermal_blob_candidates[ANOMALY_MAX_THERMAL_CANDIDATES];
            memset(thermal_blob_candidates, 0, sizeof(thermal_blob_candidates));
            extract_thermal_blob_candidates(
                    cfg,
                    saliency_spatial_map,
                    thermal_delta_map,
                    state->bg_luma,
                    sg_luma,
                    sg_w,
                    sg_h,
                    width,
                    height,
                    roi_x0,
                    roi_y0,
                    sample_step,
                    bg_valid,
                    black_hot != 0,
                    thermal_min_delta,
                    frame_blob_contrast_mean,
                    frame_blob_contrast_std,
                    thermal_visited,
                    thermal_queue,
                    thermal_value_map,
                    thermal_patch_selection,
                    thermal_blob_candidates,
                    &thermal_candidate_count,
                    &thermal_target_trace);
            float fallback_thermal = bg_valid ? -1.0f : best_thermal;
            int fallback_thermal_x = bg_valid ? 0 : best_thermal_x;
            int fallback_thermal_y = bg_valid ? 0 : best_thermal_y;
            best_thermal = -1.0f;
            best_thermal_x = 0;
            best_thermal_y = 0;
            best_thermal_candidate_idx = -1;
            best_thermal_candidate_score = -1.0f;
            float best_small_span_px = -1.0f;
            float small_target_limit_px = effective_thermal_small_target_span_px(cfg, width, height);
            for (int ci = 0; ci < thermal_candidate_count; ci++) {
                thermal_candidates[ci] = thermal_blob_candidates[ci].candidate;
                int sx = thermal_candidates[ci].sg_x;
                int sy = thermal_candidates[ci].sg_y;
                float area = thermal_blob_candidates[ci].area;
                float span = thermal_blob_candidates[ci].span;
                float fill = thermal_blob_candidates[ci].fill;
                float center_share = thermal_blob_candidates[ci].center_share;
                float peak_delta = thermal_blob_candidates[ci].peak_delta;
                float mean_delta = thermal_blob_candidates[ci].mean_delta;
                int bbox_min_x = thermal_blob_candidates[ci].min_x;
                int bbox_min_y = thermal_blob_candidates[ci].min_y;
                int bbox_max_x = thermal_blob_candidates[ci].max_x;
                int bbox_max_y = thermal_blob_candidates[ci].max_y;
                float quality = thermal_blob_candidates[ci].quality;
                float base_score = thermal_blob_candidates[ci].candidate.thermal_score;
                float history_scale = thermal_candidate_history_scale(state, sg_w, sg_h, sx, sy);
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                float patch_support = 0.0f;
                if (thermal_patch_selection != NULL) {
                    float local_patch_support = thermal_patch_selection[idx];
                    patch_support = local_patch_support > 0.0f ? local_patch_support : 0.0f;
                }
                float motion_support = 0.0f;
                if (saliency_motion_map != NULL) {
                    float local_motion_support = saliency_motion_map[idx];
                    motion_support = local_motion_support > 0.0f ? local_motion_support : 0.0f;
                }
                float context_scale = thermal_candidate_seed_context_scale(
                    thermal_delta_map,
                    state->bg_luma,
                    sg_luma,
                    sg_w,
                    sg_h,
                    sx,
                    sy,
                    sample_step,
                    bg_valid,
                    black_hot != 0,
                    thermal_min_delta,
                    0.0f,
                    frame_blob_contrast_mean,
                    frame_blob_contrast_std,
                    delta_norm);
                float parent_scale = thermal_candidate_parent_mass_scale(
                    thermal_delta_map,
                    state->bg_luma,
                    sg_luma,
                    sg_w,
                    sg_h,
                    sx,
                    sy,
                    sample_step,
                    bg_valid,
                    black_hot != 0,
                    thermal_min_delta,
                    frame_blob_contrast_mean,
                    frame_blob_contrast_std,
                    delta_norm);
                // Blob size is the primary rank signal; heat anomaly is the
                // secondary tie-breaker used among similarly small blobs.
                float score_scale = bg_valid ? (0.92f + 0.18f * quality)
                                             : (0.96f + 0.12f * quality);
                float final_score = base_score * score_scale * history_scale;
                float temporal_score = -1.0f;
                if (bg_valid) {
                    float delta = thermal_delta_from_maps(
                        thermal_delta_map,
                        state->bg_luma,
                        sg_luma,
                        idx,
                        black_hot != 0);
                    if (delta >= thermal_min_delta) {
                        temporal_score = (float)((delta - delta_mean) / delta_norm);
                        if (temporal_score > base_score) {
                            final_score = temporal_score * (0.55f + 0.55f * quality) * history_scale;
                        }
                    }
                }
                thermal_candidate_area[ci] = area;
                thermal_candidate_span[ci] = span;
                thermal_candidate_fill[ci] = fill;
                thermal_candidate_center_share[ci] = center_share;
                thermal_candidate_base_score[ci] = base_score;
                thermal_candidate_temporal_score[ci] = temporal_score;
                thermal_candidate_quality_score[ci] = quality;
                thermal_candidate_context_scale[ci] = context_scale;
                thermal_candidate_parent_scale[ci] = parent_scale;
                thermal_candidate_peak_delta[ci] = peak_delta;
                thermal_candidate_mean_delta[ci] = mean_delta;
                thermal_candidate_score_scale[ci] = score_scale;
                thermal_candidate_history_scale_debug[ci] = history_scale;
                thermal_candidate_min_x[ci] = bbox_min_x;
                thermal_candidate_min_y[ci] = bbox_min_y;
                thermal_candidate_max_x[ci] = bbox_max_x;
                thermal_candidate_max_y[ci] = bbox_max_y;
                float span_px = span * (float)sample_step;
                float apparent_size_scale = thermal_small_target_apparent_scale(cfg, span_px, width, height);
                thermal_candidate_apparent_size_scale[ci] = apparent_size_scale;
                float area_rank = area <= 0.0f ? 0.0f
                    : (area <= 2.0f ? 1.0f
                    : (area <= 4.0f ? 0.92f
                    : (area <= 6.0f ? 0.68f : 0.30f)));
                float span_rank = span_px <= 0.0f ? 0.0f
                    : (span_px <= 4.0f ? 1.0f
                    : (span_px <= 7.0f ? 0.85f
                    : (span_px <= small_target_limit_px ? 0.58f : 0.22f)));
                float center_rank = clampf((center_share - 0.40f) / 0.30f, 0.0f, 1.0f);
                float quality_rank = clampf((quality - 0.45f) / 0.55f, 0.0f, 1.0f);
                thermal_candidate_area_rank[ci] = area_rank;
                thermal_candidate_span_rank[ci] = span_rank;
                thermal_candidate_center_rank[ci] = center_rank;
                thermal_candidate_quality_rank[ci] = quality_rank;
                thermal_candidate_isolation_rank[ci] =
                    0.28f * area_rank +
                    0.26f * span_rank +
                    0.22f * center_rank +
                    0.24f * quality_rank;
                float history_bonus = clampf((history_scale - 1.0f) / ANOMALY_THERMAL_TARGET_HISTORY_GAIN, 0.0f, 1.0f);
                float isolation_track_scale =
                    1.0f + 0.34f * history_bonus * clampf((thermal_candidate_isolation_rank[ci] - 0.42f) / 0.40f, 0.0f, 1.0f);
                thermal_candidate_isolation_track_scale[ci] = isolation_track_scale;
                final_score *= isolation_track_scale;
                final_score *= apparent_size_scale;
                bool singleton_blob = area <= 1.0f && span <= 1.0f;
                bool coarse_singleton_blob = sample_step > 1 && singleton_blob;
                float singleton_score_scale = 1.0f;
                if (coarse_singleton_blob) {
                    int competing_singletons = 0;
                    for (int sj = 0; sj < thermal_candidate_count; sj++) {
                        if (sj == ci) continue;
                        if (thermal_blob_candidates[sj].area > 1.0f ||
                            thermal_blob_candidates[sj].span > 1.0f) {
                            continue;
                        }
                        float other_base_score = thermal_blob_candidates[sj].candidate.thermal_score;
                        if (other_base_score >= base_score - 1.25f) {
                            competing_singletons++;
                        }
                    }
                    if (competing_singletons >= 4) {
                        singleton_score_scale = 0.38f;
                    } else if (competing_singletons >= 2) {
                        singleton_score_scale = 0.72f;
                    } else if (competing_singletons >= 1) {
                        singleton_score_scale = 0.88f;
                    } else {
                        singleton_score_scale = 0.98f;
                    }
                    if (!thermal_publish_settled) {
                        singleton_score_scale *= 0.78f;
                    } else if (history_scale < 1.08f) {
                        singleton_score_scale *= 0.92f;
                    }
                    singleton_score_scale = clampf(singleton_score_scale, 0.20f, 1.18f);
                    final_score *= singleton_score_scale;
                } else if (singleton_blob && !thermal_publish_settled) {
                    final_score *= 0.42f;
                }
                float retention_rank = thermal_blob_candidates[ci].retention_rank;
                if (sample_step > 1) {
                    float score_rank =
                        clampf((final_score - cfg->score_threshold + 0.25f) / 2.50f, 0.0f, 1.0f);
                    float patch_rank = clampf((patch_support - 0.08f) / 0.40f, 0.0f, 1.0f);
                    float motion_rank = clampf((motion_support - 0.06f) / 0.28f, 0.0f, 1.0f);
                    float area_pref = area <= 1.0f ? 0.70f
                        : (area <= 4.0f ? 1.00f
                        : (area <= 6.0f ? 0.74f : 0.22f));
                    float span_pref = span_px <= 2.0f ? 0.70f
                        : (span_px <= 6.0f ? 1.00f
                        : (span_px <= small_target_limit_px ? 0.66f : 0.18f));
                    retention_rank =
                        0.24f * score_rank +
                        0.18f * quality_rank +
                        0.18f * patch_rank +
                        0.12f * motion_rank +
                        0.14f * history_bonus +
                        0.08f * area_pref +
                        0.06f * span_pref;
                    if (coarse_singleton_blob) {
                        retention_rank *= singleton_score_scale;
                    }
                    thermal_blob_candidates[ci].retention_rank = retention_rank;
                    thermal_blob_candidates[ci].retention_rank_valid = true;
                }
                thermal_candidates[ci].thermal_score = final_score;
                thermal_blob_candidates[ci].candidate.thermal_score = final_score;
                thermal_candidate_patch_support[ci] = patch_support;
                thermal_candidate_motion_support[ci] = motion_support;
                thermal_candidate_singleton_score_scale[ci] = singleton_score_scale;
                thermal_candidate_retention_rank_debug[ci] = retention_rank;
                thermal_candidate_singleton_blob_debug[ci] = coarse_singleton_blob;
                bool candidate_plausible = final_score >= cfg->score_threshold;
                thermal_candidate_above_threshold[ci] = candidate_plausible;
                if (candidate_plausible) {
                    if (best_thermal_candidate_idx < 0 ||
                        compare_thermal_blob_rank(&thermal_blob_candidates[ci],
                                                  &thermal_blob_candidates[best_thermal_candidate_idx]) < 0) {
                        best_thermal_candidate_idx = ci;
                        best_thermal_candidate_score = final_score;
                    }
                } else if (final_score > best_thermal) {
                    best_thermal = final_score;
                    best_thermal_x = thermal_candidates[ci].pixel_x;
                    best_thermal_y = thermal_candidates[ci].pixel_y;
                }
                if (final_score > cfg->score_threshold &&
                    span_px > 0.0f &&
                    span_px <= small_target_limit_px &&
                    (best_small_span_px < 0.0f || span_px < best_small_span_px)) {
                    best_small_span_px = span_px;
                }
            }
            if (best_thermal_candidate_idx >= 0) {
                best_thermal = best_thermal_candidate_score;
                best_thermal_x = thermal_candidates[best_thermal_candidate_idx].pixel_x;
                best_thermal_y = thermal_candidates[best_thermal_candidate_idx].pixel_y;
            }
            if (thermal_target_trace.enabled && thermal_target_trace.extracted_rank >= 0 &&
                best_thermal_candidate_idx >= 0 &&
                thermal_blob_candidates[thermal_target_trace.extracted_rank].candidate.sg_x ==
                    thermal_blob_candidates[best_thermal_candidate_idx].candidate.sg_x &&
                thermal_blob_candidates[thermal_target_trace.extracted_rank].candidate.sg_y ==
                    thermal_blob_candidates[best_thermal_candidate_idx].candidate.sg_y) {
                thermal_target_trace.winning_rank = thermal_target_trace.extracted_rank;
            }
            if (fallback_thermal > best_thermal) {
                best_thermal = fallback_thermal;
                best_thermal_x = fallback_thermal_x;
                best_thermal_y = fallback_thermal_y;
            }
            if (state->thermal_target_persist != NULL) {
                for (int ci = 0; ci < thermal_candidate_count; ci++) {
                    bool singleton_blob =
                        thermal_candidate_area[ci] <= 1.0f &&
                        thermal_candidate_span[ci] <= 1.0f;
                    if (singleton_blob && !thermal_publish_settled) continue;
                    float seed_strength = thermal_candidate_seed_strength(
                        thermal_candidate_base_score[ci],
                        thermal_candidate_quality_score[ci],
                        thermal_candidate_area[ci],
                        thermal_candidate_span[ci],
                        thermal_candidate_fill[ci],
                        thermal_candidate_center_share[ci],
                        thermal_candidate_isolation_rank[ci]);
                    if (thermal_candidate_area[ci] > 8.0f ||
                        thermal_candidate_isolation_rank[ci] < 0.40f) {
                        seed_strength *= 0.35f;
                    } else if (thermal_candidate_isolation_rank[ci] >= 0.62f &&
                               thermal_candidate_area[ci] <= 6.0f) {
                        seed_strength *= 1.20f;
                    }
                    stamp_thermal_target_support(
                        state->thermal_target_persist,
                        sg_w,
                        sg_h,
                        thermal_candidates[ci].sg_x,
                        thermal_candidates[ci].sg_y,
                        seed_strength);
                }
            }
        }
    }

    if (need_motion_candidates) {
        collect_motion_candidates(
                thermal_candidate_count > 0 ? state->scratch_patch_selection : saliency_spatial_map,
                saliency_color_map,
                sg_w,
                sg_h,
                roi_x0,
                roi_y0,
                sample_step,
                motion_candidates,
                &motion_candidate_count);
    }

    // Update (or initialise) the background EMA.
    if (scene_discontinuity || state->bg_luma == NULL
            || state->bg_sg_w != sg_w || state->bg_sg_h != sg_h) {
        // (Re-)initialise: seed background with the current frame's luma.
        free(state->bg_luma);
        state->bg_luma = (float *)malloc((size_t)sg_w * sg_h * sizeof(float));
        state->bg_sg_w  = sg_w;
        state->bg_sg_h  = sg_h;
        state->bg_warmup = 0;
        state->publish_stable_frames = 0;
        if (state->bg_luma) {
            for (int i = 0; i < sg_w * sg_h; i++)
                state->bg_luma[i] = (float)sg_luma[i];
        }
    } else {
        // EMA update: fast toward colder/brighter, slow toward warmer/darker.
        // black_hot is already declared in the temporal scoring section above.
        for (int i = 0; i < sg_w * sg_h; i++) {
            if (selective_refresh_active && appearance_refresh_mask[i] == 0u) {
                continue;
            }
            float cur = (float)sg_luma[i];
            float bg  = state->bg_luma[i];
            float delta = cur - bg;   // positive = pixel got brighter (colder in BH)
            // In black-hot: brighter = colder = background direction → fast adapt.
            // In white-hot: darker   = colder = background direction → fast adapt.
            bool toward_cold = black_hot ? (delta > 0.0f) : (delta < 0.0f);
            float alpha = toward_cold ? ANOMALY_THERMAL_BG_ALPHA_COOL
                                      : ANOMALY_THERMAL_BG_ALPHA_WARM;
            state->bg_luma[i] = bg + alpha * delta;
        }
        state->bg_warmup++;
    }

    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_THERMAL_SCORING, stage_started_us);

    // ── GMV-compensated motion scoring over ROI ──────────────────────────
    float best_motion = -1.0f;
    int   best_motion_x = 0, best_motion_y = 0;
    float motion_evidence_scale = effective_motion_evidence_scale(cfg);
    int   motion_top_count = 0;
    anomaly_debug_candidate_t motion_top[ANOMALY_DEBUG_TOP_CANDIDATES];
    memset(motion_top, 0, sizeof(motion_top));
    float best_motion_texture_scale = 0.0f;
    float best_motion_structure_scale = 0.0f;
    float best_motion_support_scale = 0.0f;
    float best_motion_persistence_scale = 1.0f;
    float best_motion_component_area_frac = 0.0f;
    float best_motion_component_span_frac = 0.0f;
    float best_motion_component_fill_ratio = 0.0f;
    float best_motion_zoom_scale = 1.0f;
    float best_motion_broad_scale = 1.0f;
    float debug_global_motion_load = 0.0f;

    bool use_motion_tolerance = (cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0;
    bool use_stable_motion = ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0) && !use_motion_tolerance;
    stage_started_us = anomaly_timing_now_us();
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0 &&
        curr_luma != NULL &&
        state->prev_luma != NULL &&
        state->prev_luma_width  == motion_w &&
        state->prev_luma_height == motion_h &&
        !scene_discontinuity) {
        int roi_mgx0 = roi_x0 / motion_step;
        int roi_mgx1 = (roi_x1 + motion_step - 1) / motion_step;
        int roi_mgy0 = roi_y0 / motion_step;
        int roi_mgy1 = (roi_y1 + motion_step - 1) / motion_step;
        roi_mgx0 = roi_mgx0 < 0 ? 0 : roi_mgx0;
        roi_mgx1 = roi_mgx1 > motion_w ? motion_w : roi_mgx1;
        roi_mgy0 = roi_mgy0 < 0 ? 0 : roi_mgy0;
        roi_mgy1 = roi_mgy1 > motion_h ? motion_h : roi_mgy1;

        float fw_m = (float)(width > 1 ? width - 1 : 1);
        float fh_m = (float)(height > 1 ? height - 1 : 1);
        float gmv_scale = registration_model_scale(&registration);
        float zoom_delta = fabsf(gmv_scale - 1.0f);
        float zoom_motion_scale = 1.0f;
        if (zoom_delta > 0.004f) {
            zoom_motion_scale = 1.0f - ((zoom_delta - 0.004f) / 0.014f);
            if (zoom_motion_scale < 0.0f) zoom_motion_scale = 0.0f;
        }

        if (state->motion_persist == NULL ||
            state->motion_persist_w != motion_w ||
            state->motion_persist_h != motion_h) {
            free(state->motion_persist);
            state->motion_persist = (float *)calloc(motion_count, sizeof(float));
            state->motion_persist_w = state->motion_persist != NULL ? motion_w : 0;
            state->motion_persist_h = state->motion_persist != NULL ? motion_h : 0;
        }

        const int disp_patch_half = 1;
        const int disp_search_radius = 2;
        const int global_stride = ANOMALY_MOTION_GLOBAL_STRIDE_CELLS;
        double global_sum = 0.0;
        double global_sum2 = 0.0;
        int global_count = 0;
        for (int my = roi_mgy0 + 1; my < roi_mgy1 - 1; my += global_stride) {
            for (int mx = roi_mgx0 + 1; mx < roi_mgx1 - 1; mx += global_stride) {
                int px_idx = 0;
                int py_idx = 0;
                if (!project_motion_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                         mx, my, &px_idx, &py_idx)) {
                    continue;
                }
                float metric_value = 0.0f;
                if (use_motion_tolerance) {
                    int best_dx = 0;
                    int best_dy = 0;
                    if (!find_residual_motion_displacement(
                            curr_luma, state->prev_luma, motion_w, motion_h,
                            mx, my, px_idx, py_idx,
                            disp_patch_half, disp_search_radius,
                            &best_dx, &best_dy, NULL)) {
                        continue;
                    }
                    metric_value =
                        sqrtf((float)(best_dx * best_dx + best_dy * best_dy)) * (float)motion_step;
                } else {
                    metric_value = (float)abs(
                        (int)curr_luma[my * motion_w + mx] -
                        (int)state->prev_luma[py_idx * motion_w + px_idx]);
                }
                global_sum += (double)metric_value;
                global_sum2 += (double)metric_value * (double)metric_value;
                global_count++;
            }
        }

        float global_motion_mean = 0.0f;
        float global_motion_std = (float)motion_step * 0.5f;
        float motion_floor_px = (float)motion_step;
        if (global_count > 0) {
            global_motion_mean = (float)(global_sum / (double)global_count);
            double variance = (global_sum2 / (double)global_count) -
                ((double)global_motion_mean * (double)global_motion_mean);
            global_motion_std = sqrtf((float)fmax(variance, 0.04));
            if (global_motion_std < (float)motion_step * 0.35f) {
                global_motion_std = (float)motion_step * 0.35f;
            }
            motion_floor_px = global_motion_mean + (0.75f * global_motion_std);
            if (motion_floor_px < (float)motion_step * 0.85f) {
                motion_floor_px = (float)motion_step * 0.85f;
            }
        }

        int strong_global_samples = 0;
        if (global_count > 0) {
            for (int my = roi_mgy0 + 1; my < roi_mgy1 - 1; my += global_stride) {
                for (int mx = roi_mgx0 + 1; mx < roi_mgx1 - 1; mx += global_stride) {
                    int px_idx = 0;
                    int py_idx = 0;
                    if (!project_motion_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                             mx, my, &px_idx, &py_idx)) {
                        continue;
                    }
                    float metric_value = 0.0f;
                    if (use_motion_tolerance) {
                        int best_dx = 0;
                        int best_dy = 0;
                        if (!find_residual_motion_displacement(
                                curr_luma, state->prev_luma, motion_w, motion_h,
                                mx, my, px_idx, py_idx,
                                disp_patch_half, disp_search_radius,
                                &best_dx, &best_dy, NULL)) {
                            continue;
                        }
                        metric_value =
                            sqrtf((float)(best_dx * best_dx + best_dy * best_dy)) * (float)motion_step;
                    } else {
                        metric_value = (float)abs(
                            (int)curr_luma[my * motion_w + mx] -
                            (int)state->prev_luma[py_idx * motion_w + px_idx]);
                    }
                    if (metric_value >= motion_floor_px + global_motion_std) {
                        strong_global_samples++;
                    }
                }
            }
        }
        debug_global_motion_load = global_count > 0
            ? ((float)strong_global_samples / (float)global_count)
            : 0.0f;
        float broad_motion_scale = 1.0f;
        if (debug_global_motion_load > 0.12f) {
            broad_motion_scale = 1.0f - ((debug_global_motion_load - 0.12f) / 0.18f);
            if (broad_motion_scale < 0.20f) broad_motion_scale = 0.20f;
        }
        best_motion_zoom_scale = zoom_motion_scale;
        best_motion_broad_scale = broad_motion_scale;

        if (result_out != NULL) {
            result_out->motion_debug.valid = global_count > 0 || motion_candidate_count > 0;
            result_out->motion_debug.scene_discontinuity = scene_discontinuity;
            result_out->motion_debug.sample_step = motion_sample_step;
            result_out->motion_debug.motion_step = motion_step;
            result_out->motion_debug.sample_count = global_count;
            result_out->motion_debug.residual_mean = global_motion_mean;
            result_out->motion_debug.residual_std = global_motion_std;
            result_out->motion_debug.zoom_motion_scale = zoom_motion_scale;
            result_out->motion_debug.broad_motion_scale = broad_motion_scale;
            result_out->motion_debug.global_motion_load = debug_global_motion_load;
        }

        if (state->motion_persist != NULL) {
            for (size_t i = 0; i < motion_count; i++) {
                state->motion_persist[i] *= 0.72f;
            }
        }

        for (int ci = 0; ci < motion_candidate_count; ci++) {
            int cand_mx = clamp_i32(motion_candidates[ci].pixel_x / motion_step, roi_mgx0, roi_mgx1 - 1);
            int cand_my = clamp_i32(motion_candidates[ci].pixel_y / motion_step, roi_mgy0, roi_mgy1 - 1);
            float dx_samples[(ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1) * (ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1)];
            float dy_samples[(ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1) * (ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1)];
            float mag_samples[(ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1) * (ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1)];
            int mx_samples[(ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1) * (ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1)];
            int my_samples[(ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1) * (ANOMALY_MOTION_LOCAL_RADIUS_CELLS * 2 + 1)];
            int sample_count_local = 0;
            float local_best_score = -1.0f;
            int local_best_mx = cand_mx;
            int local_best_my = cand_my;
            float local_best_texture_scale = 0.0f;
            float local_best_structure_scale = 0.0f;
            float local_best_support_scale = 0.0f;
            float local_best_registration_scale = 1.0f;

            for (int my = cand_my - ANOMALY_MOTION_LOCAL_RADIUS_CELLS;
                 my <= cand_my + ANOMALY_MOTION_LOCAL_RADIUS_CELLS; my++) {
                if (my <= roi_mgy0 || my >= roi_mgy1 - 1) continue;
                for (int mx = cand_mx - ANOMALY_MOTION_LOCAL_RADIUS_CELLS;
                     mx <= cand_mx + ANOMALY_MOTION_LOCAL_RADIUS_CELLS; mx++) {
                    if (mx <= roi_mgx0 || mx >= roi_mgx1 - 1) continue;
                    int px_idx = 0;
                    int py_idx = 0;
                    if (!project_motion_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                             mx, my, &px_idx, &py_idx)) {
                        continue;
                    }
                    float residual_metric = 0.0f;
                    float sample_dx = 0.0f;
                    float sample_dy = 0.0f;
                    if (use_motion_tolerance) {
                        int best_dx = 0;
                        int best_dy = 0;
                        if (!find_residual_motion_displacement(
                                curr_luma, state->prev_luma, motion_w, motion_h,
                                mx, my, px_idx, py_idx,
                                disp_patch_half, disp_search_radius,
                                &best_dx, &best_dy, NULL)) {
                            continue;
                        }
                        sample_dx = (float)best_dx;
                        sample_dy = (float)best_dy;
                        residual_metric =
                            sqrtf((float)(best_dx * best_dx + best_dy * best_dy)) * (float)motion_step;
                    } else {
                        residual_metric = (float)abs(
                            (int)curr_luma[my * motion_w + mx] -
                            (int)state->prev_luma[py_idx * motion_w + px_idx]);
                    }
                    dx_samples[sample_count_local] = sample_dx;
                    dy_samples[sample_count_local] = sample_dy;
                    mag_samples[sample_count_local] = residual_metric;
                    mx_samples[sample_count_local] = mx;
                    my_samples[sample_count_local] = my;
                    sample_count_local++;

                    int texture_score = gmv_feature_score(curr_luma, motion_w, motion_h, mx, my);
                    float texture_scale = motion_texture_scale(texture_score);
                    float structure_scale = motion_structure_scale(curr_luma, motion_w, motion_h, mx, my);
                    float support_scale = texture_scale < structure_scale ? texture_scale : structure_scale;
                    if (support_scale <= 0.0f) continue;

                    float registration_scale = 1.0f;
                    float registration_score = registration_residual_standout_score(
                        curr_luma,
                        state->prev_luma,
                        motion_w,
                        motion_h,
                        motion_step,
                        width,
                        height,
                        &registration,
                        mx,
                        my);
                    if (registration_score < ANOMALY_REG_RESIDUAL_SOFT_THRESH) {
                        float t = registration_score / ANOMALY_REG_RESIDUAL_SOFT_THRESH;
                        registration_scale =
                            ANOMALY_REG_RESIDUAL_MIN_SCALE +
                            (1.0f - ANOMALY_REG_RESIDUAL_MIN_SCALE) * clampf(t, 0.0f, 1.0f);
                        if (registration_score <= ANOMALY_REG_RESIDUAL_HARD_THRESH) {
                            registration_scale = ANOMALY_REG_RESIDUAL_MIN_SCALE;
                        }
                    }

                    float local_score = residual_metric - motion_floor_px;
                    if (local_score <= 0.0f) continue;
                    local_score = 1.0f + (local_score / fmaxf(global_motion_std, 1.0f));
                    local_score = 1.0f + ((local_score - 1.0f) * support_scale);
                    local_score = 1.0f + ((local_score - 1.0f) * registration_scale);
                    if (local_score > local_best_score) {
                        local_best_score = local_score;
                        local_best_mx = mx;
                        local_best_my = my;
                        local_best_texture_scale = texture_scale;
                        local_best_structure_scale = structure_scale;
                        local_best_support_scale = support_scale;
                        local_best_registration_scale = registration_scale;
                    }
                }
            }

            float candidate_motion_score = -1.0f;
            if (sample_count_local >= ANOMALY_MOTION_LOCAL_MIN_SAMPLES && local_best_score > 0.0f) {
                qsort(dx_samples, (size_t)sample_count_local, sizeof(float), compare_float_qsort);
                qsort(dy_samples, (size_t)sample_count_local, sizeof(float), compare_float_qsort);
                float median_dx = dx_samples[sample_count_local / 2];
                float median_dy = dy_samples[sample_count_local / 2];
                float dev_sum = 0.0f;
                int strong_local_samples = 0;
                for (int i = 0; i < sample_count_local; i++) {
                    float ddx = dx_samples[i] - median_dx;
                    float ddy = dy_samples[i] - median_dy;
                    dev_sum += sqrtf(ddx * ddx + ddy * ddy) * (float)motion_step;
                    if (mag_samples[i] >= motion_floor_px) strong_local_samples++;
                }
                float mean_dev = dev_sum / (float)sample_count_local;
                float coherence_scale = 1.0f - (mean_dev / fmaxf((float)motion_step * 2.5f, 1.0f));
                coherence_scale = clampf(coherence_scale, 0.20f, 1.0f);
                float density_scale = clampf((float)strong_local_samples / 6.0f, 0.35f, 1.0f);
                float proposal_scale = 0.70f +
                    0.30f * clampf(motion_candidates[ci].proposal_score / 4.0f, 0.0f, 1.0f);
                candidate_motion_score = local_best_score;
                candidate_motion_score =
                    1.0f + ((candidate_motion_score - 1.0f) * coherence_scale * density_scale);
                candidate_motion_score *= broad_motion_scale;
                candidate_motion_score *= zoom_motion_scale;
                candidate_motion_score *= motion_evidence_scale;
                candidate_motion_score *= proposal_scale;
                motion_candidate_support[ci] = candidate_motion_score;

                size_t persist_idx = (size_t)local_best_my * (size_t)motion_w + (size_t)local_best_mx;
                float persistence_scale = 1.0f;
                if (state->motion_persist != NULL) {
                    float prior_support = state->motion_persist[persist_idx];
                    if (prior_support < 0.08f) {
                        persistence_scale = 0.55f;
                    } else if (prior_support < 0.25f) {
                        persistence_scale = 0.80f;
                    } else {
                        persistence_scale = 0.95f + (0.25f * fminf(prior_support, 1.0f));
                    }
                    candidate_motion_score =
                        1.0f + ((candidate_motion_score - 1.0f) * persistence_scale);
                    float current_presence =
                        clampf((candidate_motion_score - 1.0f) / 2.5f, 0.0f, 1.0f);
                    if (current_presence > state->motion_persist[persist_idx]) {
                        state->motion_persist[persist_idx] = current_presence;
                    }
                }

                int pixel_x = local_best_mx * motion_step + motion_step / 2;
                int pixel_y = local_best_my * motion_step + motion_step / 2;
                motion_candidate_support_x[ci] = pixel_x;
                motion_candidate_support_y[ci] = pixel_y;
                maybe_insert_top_candidate(
                    motion_top,
                    &motion_top_count,
                    ANOMALY_DEBUG_TOP_CANDIDATES,
                    pixel_x,
                    pixel_y,
                    (float)pixel_x / fw_m,
                    (float)pixel_y / fh_m,
                    motion_candidates[ci].proposal_score,
                    0.0f,
                    candidate_motion_score);
                if (saliency_motion_map != NULL) {
                    int sal_sx = clamp_i32((pixel_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                    int sal_sy = clamp_i32((pixel_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                    float motion_support = candidate_motion_score;
                    if (motion_support > 4.0f) motion_support = 4.0f;
                    stamp_motion_support(
                        saliency_motion_map,
                        saliency_registration_map,
                        sg_w,
                        sg_h,
                        sal_sx,
                        sal_sy,
                        motion_support,
                        local_best_registration_scale);
                }
                if ((use_stable_motion || use_motion_tolerance) && candidate_motion_score > best_motion) {
                    best_motion = candidate_motion_score;
                    best_motion_x = pixel_x;
                    best_motion_y = pixel_y;
                    best_motion_texture_scale = local_best_texture_scale;
                    best_motion_structure_scale = local_best_structure_scale;
                    best_motion_support_scale = local_best_support_scale;
                    best_motion_persistence_scale = persistence_scale;
                }
            }
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_MOTION_SCORING, stage_started_us);

    stage_started_us = anomaly_timing_now_us();
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        bool saliency_motion_vector_ready =
            registration_model_valid(&registration) &&
            !scene_discontinuity &&
            saliency_motion_map != NULL &&
            saliency_registration_map != NULL;
        size_t score_count = (size_t)sg_w * (size_t)sg_h;
        float *patch_score_map = NULL;
        float *patch_selection_map = NULL;
        if (ensure_float_capacity(&state->scratch_patch_score, &state->scratch_patch_capacity, score_count) &&
            ensure_float_capacity(&state->scratch_patch_selection, &state->scratch_patch_capacity, score_count)) {
            patch_score_map = state->scratch_patch_score;
            patch_selection_map = state->scratch_patch_selection;
        }
        if (patch_score_map != NULL && patch_selection_map != NULL) {
            memset(saliency_top, 0, sizeof(saliency_top));
            saliency_top_count = 0;
                for (int sy = 0; sy < sg_h; sy++) {
                    for (int sx = 0; sx < sg_w; sx++) {
                        size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                        if (selective_refresh_active && appearance_refresh_mask[idx] == 0u) {
                            patch_score_map[idx] = -1.0f;
                            continue;
                        }
                        float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                        float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
                        float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
                        float registration_support = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
                        if (!saliency_motion_vector_ready ||
                            motion_support <= 0.0f ||
                            registration_support <= 0.0f) {
                            patch_score_map[idx] = -1.0f;
                            continue;
                        }
                        float thermal_temporal = 0.0f;
                        if (bg_valid) {
                            float delta = thermal_delta_from_maps(
                                thermal_delta_map,
                                state->bg_luma,
                                sg_luma,
                                idx,
                                black_hot != 0);
                            if (delta >= thermal_min_delta) {
                                thermal_temporal = (float)((delta - delta_mean) / delta_norm);
                            }
                        }

                        float spatial_evidence = thermal_spatial > 0.0f ? thermal_spatial : 0.0f;
                        if (color_support > 0.0f) spatial_evidence += 0.60f * color_support;

                        float temporal_evidence = thermal_temporal > 0.0f ? thermal_temporal : 0.0f;
                        if (motion_support > 0.0f) {
                            temporal_evidence += bg_valid ? (0.60f * motion_support)
                                                          : (0.45f * motion_support);
                    }

                    float saliency = bg_valid
                        ? (0.75f * spatial_evidence) + temporal_evidence
                        : spatial_evidence + temporal_evidence;
                    saliency *= registration_support;
                    if (saliency <= 0.0f) {
                        patch_score_map[idx] = -1.0f;
                        continue;
                    }
                    patch_score_map[idx] = saliency;
                    if (result_out != NULL) {
                        int px = roi_x0 + sx * sample_step;
                        int py = roi_y0 + sy * sample_step;
                        maybe_insert_top_candidate(
                                saliency_top, &saliency_top_count, ANOMALY_DEBUG_TOP_CANDIDATES,
                                px, py,
                                (float)px / (float)(width > 1 ? width - 1 : 1),
                                (float)py / (float)(height > 1 ? height - 1 : 1),
                                spatial_evidence, temporal_evidence, saliency);
                    }
                }
            }

            build_patch_selection_map(patch_score_map, sg_w, sg_h, patch_selection_map);
            if (bg_valid) {
                for (int sy = 0; sy < sg_h; sy++) {
                    for (int sx = 0; sx < sg_w; sx++) {
                        size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                        if (selective_refresh_active && appearance_refresh_mask[idx] == 0u) {
                            continue;
                        }
                        float final_score = patch_selection_map[idx];
                        if (final_score <= 0.0f) continue;
                        float boundary_scale = saliency_boundary_structure_scale(
                            patch_score_map,
                            thermal_delta_map,
                            state->bg_luma,
                            sg_luma,
                            sg_w,
                            sg_h,
                            sx,
                            sy,
                            bg_valid,
                            black_hot,
                            thermal_min_delta,
                            delta_norm);
                        patch_selection_map[idx] = final_score * boundary_scale;
                    }
                }
            }
            memset(saliency_top, 0, sizeof(saliency_top));
            saliency_top_count = 0;
            for (int sy = 0; sy < sg_h; sy++) {
                for (int sx = 0; sx < sg_w; sx++) {
                    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                    if (selective_refresh_active && appearance_refresh_mask[idx] == 0u) {
                        continue;
                    }
                    float final_score = patch_selection_map[idx];
                    if (final_score <= 0.0f) continue;
                    int px = roi_x0 + sx * sample_step;
                    int py = roi_y0 + sy * sample_step;
                    float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                    float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
                    float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
                    float registration_support = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
                    float spatial_evidence = thermal_spatial > 0.0f ? thermal_spatial : 0.0f;
                    if (color_support > 0.0f) spatial_evidence += 0.60f * color_support;
                    float temporal_evidence = 0.0f;
                    if (bg_valid) {
                        float delta = thermal_delta_map != NULL
                            ? thermal_delta_map[idx]
                            : thermal_delta_from_maps(
                                thermal_delta_map,
                                state->bg_luma,
                                sg_luma,
                                idx,
                                black_hot != 0);
                        if (delta >= thermal_min_delta) {
                            temporal_evidence = (float)((delta - delta_mean) / delta_norm);
                        }
                    }
                    spatial_evidence *= registration_support;
                    if (motion_support > 0.0f) {
                        temporal_evidence += bg_valid ? (0.60f * motion_support)
                                                      : (0.45f * motion_support);
                    }
                    temporal_evidence *= registration_support;
                    maybe_insert_top_candidate(
                            saliency_top, &saliency_top_count, ANOMALY_DEBUG_TOP_CANDIDATES,
                            px, py,
                            (float)px / (float)(width > 1 ? width - 1 : 1),
                            (float)py / (float)(height > 1 ? height - 1 : 1),
                            spatial_evidence, temporal_evidence, final_score);
                }
            }
            if (state->acc_active[3]) {
                float dbg_fw = (float)(width > 1 ? width - 1 : 1);
                float dbg_fh = (float)(height > 1 ? height - 1 : 1);
                int track_x = clamp_i32((int)lroundf(state->acc_cx[3] * dbg_fw), roi_x0, roi_x1 - 1);
                int track_y = clamp_i32((int)lroundf(state->acc_cy[3] * dbg_fh), roi_y0, roi_y1 - 1);
                int track_sx = clamp_i32((track_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int track_sy = clamp_i32((track_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                saliency_tracked_score_pre = -1.0f;
                for (int ny = track_sy - 1; ny <= track_sy + 1; ny++) {
                    if (ny < 0 || ny >= sg_h) continue;
                    for (int nx = track_sx - 1; nx <= track_sx + 1; nx++) {
                        if (nx < 0 || nx >= sg_w) continue;
                        float nearby = patch_selection_map[ny * sg_w + nx];
                        if (nearby > saliency_tracked_score_pre) {
                            saliency_tracked_score_pre = nearby;
                        }
                    }
                }
            }
            choose_best_dark_patch(
                    patch_selection_map,
                    sg_w, sg_h,
                    roi_x0, roi_y0, sample_step,
                    &best_persist, &best_persist_x, &best_persist_y);
            if (best_thermal >= cfg->score_threshold && bg_valid &&
                (best_persist < cfg->score_threshold || (best_persist_x > 0 || best_persist_y > 0))) {
                int persist_sx = clamp_i32((best_persist_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int persist_sy = clamp_i32((best_persist_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                int thermal_sx = clamp_i32((best_thermal_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int thermal_sy = clamp_i32((best_thermal_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                float persist_x_norm = (float)best_persist_x / (float)(width > 1 ? width - 1 : 1);
                float persist_y_norm = (float)best_persist_y / (float)(height > 1 ? height - 1 : 1);
                float thermal_x_norm = (float)best_thermal_x / (float)(width > 1 ? width - 1 : 1);
                float thermal_y_norm = (float)best_thermal_y / (float)(height > 1 ? height - 1 : 1);
                float dx = persist_x_norm - thermal_x_norm;
                float dy = persist_y_norm - thermal_y_norm;
                float separation = sqrtf(dx * dx + dy * dy);
                int persist_cue = classify_saliency_display_algorithm(
                        saliency_spatial_map,
                        saliency_color_map,
                        saliency_motion_map,
                        saliency_registration_map,
                        state->bg_luma,
                        sg_luma,
                        sg_w,
                        sg_h,
                        persist_sx,
                        persist_sy,
                        bg_valid,
                        black_hot,
                        thermal_min_delta,
                        delta_mean,
                        delta_norm);
                bool thermal_override =
                    separation >= 0.085f &&
                    persist_cue != ANOMALY_ALGO_MOTION &&
                    persist_cue != ANOMALY_ALGO_COLOR;
                if (thermal_override) {
                    float thermal_patch_score =
                        patch_selection_map[(size_t)thermal_sy * (size_t)sg_w + (size_t)thermal_sx];
                    best_persist = thermal_patch_score > best_thermal ? thermal_patch_score : best_thermal;
                    best_persist_x = best_thermal_x;
                    best_persist_y = best_thermal_y;
                }
            }
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SALIENCY_SCORING, stage_started_us);

    bool roi_state_updated = false;
    if (selective_refresh_active) {
        roi_state_updated = update_roi_state_selective_refresh(
                state,
                &registration,
                width,
                height,
                roi_x0,
                roi_y0,
                roi_x1,
                roi_y1,
                sample_step,
                sg_w,
                sg_h,
                sg_luma,
                saliency_spatial_map,
                saliency_motion_map,
                thermal_delta_map,
                bg_valid,
                black_hot,
                thermal_min_delta,
                delta_mean,
                delta_norm,
                registration_health,
                appearance_refresh_mask);
        if (!roi_state_updated) {
            rescan_mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.mode = ANOMALY_RESCAN_MODE_FULL;
            if (result_out != NULL) {
                result_out->rescan_mode = rescan_mode;
                result_out->scan_plan = scan_plan;
            }
        }
    }
    if (!roi_state_updated) {
        update_roi_state_full_refresh(
                state,
                roi_x0,
                roi_y0,
                roi_x1,
                roi_y1,
                sample_step,
                sg_w,
                sg_h,
                sg_luma,
                saliency_spatial_map,
                saliency_motion_map,
                thermal_delta_map,
                bg_valid,
                black_hot,
                thermal_min_delta,
                delta_mean,
                delta_norm,
                registration_health);
    }

    // ── Update prev_luma ────────────────────────────────────────────────
    update_prev_luma_state(state, curr_luma, motion_count, motion_w, motion_h);
    update_prev_registration_luma_state(
            state,
            curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
            motion_count,
            motion_w,
            motion_h);

    // ── Update per-algorithm accumulators ────────────────────────────────
    float fw = (float)(width  > 1 ? width  - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);

    float raw_cx[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
    float raw_cy[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    float saliency_aux_raw_cx[ANOMALY_SALIENCY_EXTRA_TRACKS];
    float saliency_aux_raw_cy[ANOMALY_SALIENCY_EXTRA_TRACKS];
    float saliency_aux_local_cx[ANOMALY_SALIENCY_EXTRA_TRACKS];
    float saliency_aux_local_cy[ANOMALY_SALIENCY_EXTRA_TRACKS];
    float saliency_aux_local_score[ANOMALY_SALIENCY_EXTRA_TRACKS];
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        saliency_aux_raw_cx[i] = -1.0f;
        saliency_aux_raw_cy[i] = -1.0f;
        saliency_aux_local_cx[i] = -1.0f;
        saliency_aux_local_cy[i] = -1.0f;
        saliency_aux_local_score[i] = -1.0f;
    }
    if (transition_warmup_block &&
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        clear_saliency_tracks(state);
    }
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_COLOR)   && best_color   >= cfg->score_threshold) {
        raw_cx[0] = (float)best_color_x   / fw;
        raw_cy[0] = (float)best_color_y   / fh;
    }
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) && best_thermal >= cfg->score_threshold) {
        raw_cx[1] = (float)best_thermal_x / fw;
        raw_cy[1] = (float)best_thermal_y / fh;
    }
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) &&
        best_motion  >= cfg->score_threshold) {
        raw_cx[2] = (float)best_motion_x  / fw;
        raw_cy[2] = (float)best_motion_y  / fh;
    }
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) &&
        !transition_warmup_block &&
        best_persist >= cfg->score_threshold) {
        raw_cx[3] = (float)best_persist_x / fw;
        raw_cy[3] = (float)best_persist_y / fh;
        int best_persist_sx = clamp_i32((best_persist_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
        int best_persist_sy = clamp_i32((best_persist_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
        state->saliency_display_algorithm = classify_saliency_display_algorithm(
                saliency_spatial_map,
                saliency_color_map,
                saliency_motion_map,
                saliency_registration_map,
                state->bg_luma,
                sg_luma,
                sg_w,
                sg_h,
                best_persist_sx,
                best_persist_sy,
                bg_valid,
                black_hot,
                thermal_min_delta,
                delta_mean,
                delta_norm);
        if (state->scratch_patch_selection != NULL) {
            for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
                find_saliency_local_support(
                        state,
                        ti,
                        state->scratch_patch_selection,
                        sg_w,
                        sg_h,
                        roi_x0,
                        roi_y0,
                        sample_step,
                        width,
                        height,
                        &saliency_aux_local_cx[ti],
                        &saliency_aux_local_cy[ti],
                        &saliency_aux_local_score[ti]);
            }
        }
        int aux_count = 0;
        for (int ci = 0; ci < saliency_top_count && aux_count < ANOMALY_SALIENCY_EXTRA_TRACKS; ci++) {
            const anomaly_debug_candidate_t *candidate = &saliency_top[ci];
            if (!candidate->valid || candidate->combined_score < cfg->score_threshold) continue;
            float dx_primary = candidate->x_norm - raw_cx[3];
            float dy_primary = candidate->y_norm - raw_cy[3];
            float primary_dist = sqrtf(dx_primary * dx_primary + dy_primary * dy_primary);
            if (primary_dist < ANOMALY_SALIENCY_SECONDARY_MIN_SEPARATION) continue;

            bool supports_existing_track = false;
            for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
                if (!state->saliency_aux_active[ti] || state->saliency_aux_hits[ti] < min_hits) continue;
                float dx_track = candidate->x_norm - state->saliency_aux_cx[ti];
                float dy_track = candidate->y_norm - state->saliency_aux_cy[ti];
                float track_dist = sqrtf(dx_track * dx_track + dy_track * dy_track);
                if (track_dist <= ANOMALY_SALIENCY_SECONDARY_TRACK_REACQUIRE_GATE) {
                    supports_existing_track = true;
                    break;
                }
            }
            if (!supports_existing_track &&
                candidate->combined_score + ANOMALY_SALIENCY_SECONDARY_SCORE_MARGIN < best_persist) {
                continue;
            }
            if (supports_existing_track &&
                candidate->combined_score + ANOMALY_SALIENCY_SECONDARY_TRACKED_SCORE_MARGIN < best_persist) {
                continue;
            }

            bool too_close_to_aux = false;
            for (int aj = 0; aj < aux_count; aj++) {
                float dx_aux = candidate->x_norm - saliency_aux_raw_cx[aj];
                float dy_aux = candidate->y_norm - saliency_aux_raw_cy[aj];
                float aux_dist = sqrtf(dx_aux * dx_aux + dy_aux * dy_aux);
                if (aux_dist < ANOMALY_SALIENCY_SECONDARY_MIN_SEPARATION) {
                    too_close_to_aux = true;
                    break;
                }
            }
            if (too_close_to_aux) continue;
            saliency_aux_raw_cx[aux_count] = candidate->x_norm;
            saliency_aux_raw_cy[aux_count] = candidate->y_norm;
            int aux_px = clamp_i32((int)lroundf(candidate->x_norm * fw), roi_x0, roi_x0 + (sg_w - 1) * sample_step);
            int aux_py = clamp_i32((int)lroundf(candidate->y_norm * fh), roi_y0, roi_y0 + (sg_h - 1) * sample_step);
            int aux_sx = clamp_i32((aux_px - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
            int aux_sy = clamp_i32((aux_py - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
            state->saliency_aux_display_algorithm[aux_count] = classify_saliency_display_algorithm(
                    saliency_spatial_map,
                    saliency_color_map,
                    saliency_motion_map,
                    saliency_registration_map,
                    state->bg_luma,
                    sg_luma,
                    sg_w,
                    sg_h,
                    aux_sx,
                    aux_sy,
                    bg_valid,
                    black_hot,
                    thermal_min_delta,
                    delta_mean,
                    delta_norm);
            aux_count++;
        }
        for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS && aux_count < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
            if (!state->saliency_aux_active[ti] || state->saliency_aux_hits[ti] < min_hits) continue;
            if (saliency_aux_local_score[ti] < cfg->score_threshold - ANOMALY_SALIENCY_SECONDARY_LOCAL_THRESHOLD_SLACK) {
                continue;
            }
            float x_norm = saliency_aux_local_cx[ti];
            float y_norm = saliency_aux_local_cy[ti];
            if (x_norm < 0.0f || y_norm < 0.0f) continue;
            float dx_primary = x_norm - raw_cx[3];
            float dy_primary = y_norm - raw_cy[3];
            float primary_dist = sqrtf(dx_primary * dx_primary + dy_primary * dy_primary);
            if (primary_dist < ANOMALY_SALIENCY_SECONDARY_MIN_SEPARATION) continue;

            bool too_close_to_aux = false;
            for (int aj = 0; aj < aux_count; aj++) {
                float dx_aux = x_norm - saliency_aux_raw_cx[aj];
                float dy_aux = y_norm - saliency_aux_raw_cy[aj];
                if (sqrtf(dx_aux * dx_aux + dy_aux * dy_aux) < ANOMALY_SALIENCY_SECONDARY_MIN_SEPARATION) {
                    too_close_to_aux = true;
                    break;
                }
            }
            if (too_close_to_aux) continue;
            saliency_aux_raw_cx[aux_count] = x_norm;
            saliency_aux_raw_cy[aux_count] = y_norm;
            int aux_px = clamp_i32((int)lroundf(x_norm * fw), roi_x0, roi_x0 + (sg_w - 1) * sample_step);
            int aux_py = clamp_i32((int)lroundf(y_norm * fh), roi_y0, roi_y0 + (sg_h - 1) * sample_step);
            int aux_sx = clamp_i32((aux_px - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
            int aux_sy = clamp_i32((aux_py - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
            state->saliency_aux_display_algorithm[aux_count] = classify_saliency_display_algorithm(
                    saliency_spatial_map,
                    saliency_color_map,
                    saliency_motion_map,
                    saliency_registration_map,
                    state->bg_luma,
                    sg_luma,
                    sg_w,
                    sg_h,
                    aux_sx,
                    aux_sy,
                    bg_valid,
                    black_hot,
                    thermal_min_delta,
                    delta_mean,
                    delta_norm);
            aux_count++;
        }
    } else {
        if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
            clear_saliency_tracks(state);
        } else {
            state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
            for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
                state->saliency_aux_display_algorithm[i] = ANOMALY_ALGO_PERSIST;
            }
        }
    }

    if (motion_candidate_count > 0) {
        float fw_norm = (float)(width > 1 ? width - 1 : 1);
        float fh_norm = (float)(height > 1 ? height - 1 : 1);
        for (int ci = 0; ci < motion_candidate_count; ci++) {
            float motion_support = motion_candidate_support[ci];
            if (motion_support <= 1.0f) continue;

            int cand_x = motion_candidate_support_x[ci] != 0 || motion_candidate_support_y[ci] != 0
                ? motion_candidate_support_x[ci]
                : motion_candidates[ci].pixel_x;
            int cand_y = motion_candidate_support_x[ci] != 0 || motion_candidate_support_y[ci] != 0
                ? motion_candidate_support_y[ci]
                : motion_candidates[ci].pixel_y;
            int cand_sx = clamp_i32((cand_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
            int cand_sy = clamp_i32((cand_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
            size_t cand_idx = (size_t)cand_sy * (size_t)sg_w + (size_t)cand_sx;
            float registration_support =
                saliency_registration_map != NULL ? saliency_registration_map[cand_idx] : 1.0f;
            float motion_excess = motion_support - 1.0f;
            float reliability =
                clampf((motion_support - 1.15f) / 1.35f, 0.0f, 1.0f) *
                clampf((registration_support - 0.55f) / 0.35f, 0.0f, 1.0f) *
                clampf((best_motion_support_scale - 0.30f) / 0.45f, 0.0f, 1.0f) *
                clampf((best_motion_zoom_scale - 0.45f) / 0.45f, 0.0f, 1.0f) *
                clampf((best_motion_broad_scale - 0.25f) / 0.50f, 0.0f, 1.0f);
            bool strong_motion_override =
                reliability >= 0.42f &&
                motion_support >= 1.55f &&
                registration_support >= 0.72f;

            if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
                motion_candidates[ci].thermal_score > 0.0f) {
                float thermal_base = motion_candidates[ci].thermal_score;
                if (bg_valid) {
                    float bg = state->bg_luma[cand_idx];
                    float lum = (float)sg_luma[cand_idx];
                    float delta = black_hot ? (bg - lum) : (lum - bg);
                    if (delta >= thermal_min_delta) {
                        float temporal_score = (float)((delta - delta_mean) / delta_norm);
                        if (temporal_score > thermal_base) thermal_base = temporal_score;
                    }
                }
                float boosted_thermal = thermal_base + (ANOMALY_THERMAL_MOTION_BOOST * motion_excess * reliability);
                if (best_thermal >= cfg->score_threshold) {
                    int cur_sx = clamp_i32((best_thermal_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                    int cur_sy = clamp_i32((best_thermal_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                    float dx = ((float)cand_x - (float)best_thermal_x) / fw_norm;
                    float dy = ((float)cand_y - (float)best_thermal_y) / fh_norm;
                    float separation = sqrtf(dx * dx + dy * dy);
                    if (cand_sx == cur_sx && cand_sy == cur_sy && motion_excess > 0.0f) {
                        if (boosted_thermal > best_thermal) best_thermal = boosted_thermal;
                    } else if (strong_motion_override &&
                               separation >= 0.020f &&
                               boosted_thermal > best_thermal + 0.22f) {
                        best_thermal = boosted_thermal;
                        best_thermal_x = cand_x;
                        best_thermal_y = cand_y;
                    }
                } else if (strong_motion_override && boosted_thermal > best_thermal) {
                    best_thermal = boosted_thermal;
                    best_thermal_x = cand_x;
                    best_thermal_y = cand_y;
                }
            }

            if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0 &&
                motion_candidates[ci].color_score > 0.0f) {
                float color_base = motion_candidates[ci].color_score + 2.0f;
                float boosted_color = color_base + (ANOMALY_COLOR_MOTION_BOOST * motion_excess * reliability);
                if (best_color >= cfg->score_threshold) {
                    int cur_sx = clamp_i32((best_color_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                    int cur_sy = clamp_i32((best_color_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                    float dx = ((float)cand_x - (float)best_color_x) / fw_norm;
                    float dy = ((float)cand_y - (float)best_color_y) / fh_norm;
                    float separation = sqrtf(dx * dx + dy * dy);
                    if (cand_sx == cur_sx && cand_sy == cur_sy && motion_excess > 0.0f) {
                        if (boosted_color > best_color) best_color = boosted_color;
                    } else if (strong_motion_override &&
                               separation >= 0.020f &&
                               boosted_color > best_color + 0.18f) {
                        best_color = boosted_color;
                        best_color_x = cand_x;
                        best_color_y = cand_y;
                    }
                } else if (strong_motion_override && boosted_color > best_color) {
                    best_color = boosted_color;
                    best_color_x = cand_x;
                    best_color_y = cand_y;
                }
            }
        }
    }

    if (anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0 &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_COLOR)) != 0) {
        bool thermal_publish_settled =
            cfg->min_hits <= 1 ||
            state->bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES);
        float derived_persist = -1.0f;
        int derived_persist_x = 0;
        int derived_persist_y = 0;

        if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
            best_thermal >= cfg->score_threshold) {
            float support = 0.0f;
            float motion_support = 0.0f;
            if (state->scratch_patch_selection != NULL) {
                int sx = clamp_i32((best_thermal_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int sy = clamp_i32((best_thermal_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                float patch_support = state->scratch_patch_selection[idx];
                support = patch_support > 0.0f ? patch_support : 0.0f;
                if (saliency_motion_map != NULL) {
                    float local_motion = saliency_motion_map[idx];
                    motion_support = local_motion > 0.0f ? local_motion : 0.0f;
                }
            }
            bool singleton_blob =
                best_thermal_candidate_idx >= 0 &&
                best_thermal_candidate_idx < thermal_candidate_count &&
                thermal_candidate_area[best_thermal_candidate_idx] <= 1.0f &&
                thermal_candidate_span[best_thermal_candidate_idx] <= 1.0f;
            bool weak_singleton =
                use_publish_transition_gating &&
                singleton_blob &&
                motion_support < 0.20f &&
                (!thermal_publish_settled || best_thermal < cfg->score_threshold + 0.65f);
            if (!weak_singleton) {
                derived_persist =
                    best_thermal +
                    (0.55f * support) +
                    (0.25f * motion_support);
                derived_persist_x = best_thermal_x;
                derived_persist_y = best_thermal_y;
            }
        }

        if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0 &&
            best_color >= cfg->score_threshold) {
            float support = 0.0f;
            float motion_support = 0.0f;
            if (state->scratch_patch_selection != NULL) {
                int sx = clamp_i32((best_color_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int sy = clamp_i32((best_color_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                float patch_support = state->scratch_patch_selection[idx];
                support = patch_support > 0.0f ? patch_support : 0.0f;
                if (saliency_motion_map != NULL) {
                    float local_motion = saliency_motion_map[idx];
                    motion_support = local_motion > 0.0f ? local_motion : 0.0f;
                }
            }
            float color_derived =
                best_color +
                (0.50f * support) +
                (0.20f * motion_support);
            if (color_derived > derived_persist) {
                derived_persist = color_derived;
                derived_persist_x = best_color_x;
                derived_persist_y = best_color_y;
            }
        }

        best_persist = derived_persist;
        best_persist_x = derived_persist_x;
        best_persist_y = derived_persist_y;
    }

    bool saliency_acc_pre_active = state->acc_active[3];
    int saliency_acc_pre_hits = state->acc_hits[3];
    float saliency_acc_pre_x = state->acc_cx[3];
    float saliency_acc_pre_y = state->acc_cy[3];

    float gate  = ANOMALY_ACC_GATE_RADIUS;
    float alpha = ANOMALY_ACC_EMA_ALPHA;
    for (int ai = 0; ai < 4; ai++) {
        uint8_t prior_presence = state->acc_presence_mask[ai];
        prior_presence = (uint8_t)((prior_presence << 1u) & ((1u << ANOMALY_MOTION_PRESENCE_WINDOW) - 1u));
        if (raw_cx[ai] >= 0.0f) {
            state->acc_presence_mask[ai] = (uint8_t)(prior_presence | 1u);
            if (!state->acc_active[ai]) {
                state->acc_cx[ai]     = raw_cx[ai];
                state->acc_cy[ai]     = raw_cy[ai];
                state->acc_hits[ai]   = 1;
                state->acc_hold[ai]   = ANOMALY_ACC_HOLD_FRAMES;
                state->acc_active[ai] = true;
            } else {
                float ddx  = raw_cx[ai] - state->acc_cx[ai];
                float ddy  = raw_cy[ai] - state->acc_cy[ai];
                float dist = sqrtf(ddx * ddx + ddy * ddy);
                bool suppress_switch = false;
                float blend_alpha = alpha;
                if (ai == 3 && saliency_acc_pre_active && saliency_acc_pre_hits >= min_hits) {
                    float switch_margin = 0.70f;
                    float inner_gate = gate * 0.45f;
                    bool stronger_new_winner =
                        saliency_tracked_score_pre > 0.0f &&
                        best_persist > saliency_tracked_score_pre + 0.55f &&
                        dist > gate * 0.16f;
                    if (saliency_tracked_score_pre > 0.0f &&
                        !stronger_new_winner &&
                        dist > inner_gate &&
                        best_persist < saliency_tracked_score_pre + switch_margin) {
                        suppress_switch = true;
                    }
                    // If the tracked neighborhood has gone weak but the raw saliency
                    // winner is clearly stronger, pull the latch back quickly instead
                    // of letting the EMA trail behind the true winner for dozens of frames.
                    if (!suppress_switch &&
                        stronger_new_winner) {
                        blend_alpha = 0.88f;
                    } else if (!suppress_switch &&
                        saliency_tracked_score_pre > 0.0f &&
                        best_persist > saliency_tracked_score_pre + 2.0f &&
                        dist > gate * 0.08f) {
                        blend_alpha = 0.75f;
                    }
                }
                if (dist <= gate) {
                    if (suppress_switch) {
                        saliency_switch_suppressed = true;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    } else {
                        state->acc_cx[ai] += blend_alpha * ddx;
                        state->acc_cy[ai] += blend_alpha * ddy;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    }
                } else {
                    if (suppress_switch) {
                        saliency_switch_suppressed = true;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    } else {
                        // Detection jumped to a new region; reset to new location.
                        state->acc_cx[ai]   = raw_cx[ai];
                        state->acc_cy[ai]   = raw_cy[ai];
                        state->acc_hits[ai] = 1;
                    }
                }
                state->acc_hold[ai] = ANOMALY_ACC_HOLD_FRAMES;
            }
        } else if (state->acc_active[ai]) {
            state->acc_presence_mask[ai] = prior_presence;
            int hold = state->acc_hold[ai] - 1;
            if (hold <= 0) {
                state->acc_active[ai] = false;
                state->acc_hits[ai]   = 0;
                state->acc_hold[ai]   = 0;
            } else {
                state->acc_hold[ai] = hold;
            }
        } else {
            state->acc_presence_mask[ai] = prior_presence;
        }
    }
    stage_started_us = anomaly_timing_now_us();
    for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
        update_saliency_aux_track(
                state,
                ti,
                saliency_aux_raw_cx[ti],
                saliency_aux_raw_cy[ti],
                gate * 0.90f,
                alpha);
    }

    anomaly_target_observation_t target_observations[4 + ANOMALY_SALIENCY_EXTRA_TRACKS];
    int target_observation_count = 0;
    float target_half_side = clampf(sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f)) * 0.5f, 0.01f, 0.10f);
    for (int ai = 0; ai < 4 && target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0])); ai++) {
        if (raw_cx[ai] < 0.0f || raw_cy[ai] < 0.0f) continue;
        anomaly_target_observation_t *obs = &target_observations[target_observation_count++];
        memset(obs, 0, sizeof(*obs));
        obs->valid = true;
        obs->algorithm = (ai == 0) ? ANOMALY_ALGO_COLOR :
                         (ai == 1) ? ANOMALY_ALGO_THERMAL :
                         (ai == 2) ? ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0
                                     ? ANOMALY_ALGO_MOTION_TOLERANCE
                                     : ANOMALY_ALGO_MOTION) :
                                     ANOMALY_ALGO_PERSIST;
        obs->center_x_norm = state->acc_cx[ai];
        obs->center_y_norm = state->acc_cy[ai];
        obs->half_w_norm = target_half_side;
        obs->half_h_norm = target_half_side;
        obs->support_radius_norm = target_half_side * 1.8f;
        obs->confidence = clampf(0.35f + 0.08f * (float)state->acc_hits[ai], 0.35f, 0.95f);
    }
    for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS &&
                     target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0])); ti++) {
        if (saliency_aux_raw_cx[ti] < 0.0f || saliency_aux_raw_cy[ti] < 0.0f) continue;
        anomaly_target_observation_t *obs = &target_observations[target_observation_count++];
        memset(obs, 0, sizeof(*obs));
        obs->valid = true;
        obs->algorithm = ANOMALY_ALGO_PERSIST;
        obs->center_x_norm = state->saliency_aux_cx[ti];
        obs->center_y_norm = state->saliency_aux_cy[ti];
        obs->half_w_norm = target_half_side * 0.90f;
        obs->half_h_norm = target_half_side * 0.90f;
        obs->support_radius_norm = target_half_side * 1.6f;
        obs->confidence = clampf(0.30f + 0.08f * (float)state->saliency_aux_hits[ti], 0.30f, 0.88f);
    }
    update_target_tracks_from_observations(
            state,
            target_observations,
            target_observation_count,
            registration_health);
    if (state->roi_state.valid) {
        annotate_target_revisit_cells(&state->roi_state, state);
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_TARGET_TRACKING, stage_started_us);

    bool publish_motion_unstable =
        !scene_discontinuity &&
        registration_model_valid(&registration) &&
        (sim.mean_residual > ANOMALY_PUBLISH_GMV_RESIDUAL_GATE ||
         best_motion_zoom_scale < ANOMALY_PUBLISH_ZOOM_SCALE_GATE ||
         debug_global_motion_load > ANOMALY_PUBLISH_GLOBAL_MOTION_GATE);
    bool publish_scene_stable =
        !scene_discontinuity &&
        bg_temporal_ready &&
        (!registration_model_valid(&registration) || sim.mean_residual <= ANOMALY_PUBLISH_STABLE_GMV_RESIDUAL) &&
        best_motion_zoom_scale >= ANOMALY_PUBLISH_STABLE_ZOOM_SCALE &&
        debug_global_motion_load <= ANOMALY_PUBLISH_STABLE_MOTION_LOAD;
    if (!use_publish_transition_gating) {
        state->publish_stable_frames = 0;
    } else if (scene_discontinuity) {
        state->publish_stable_frames = 0;
    } else if (publish_scene_stable) {
        if (state->publish_stable_frames < ANOMALY_PUBLISH_STABLE_RELEASE_FRAMES) {
            state->publish_stable_frames++;
        }
    } else if (state->publish_stable_frames > 0 &&
               (publish_motion_unstable ||
                !bg_temporal_ready ||
                debug_global_motion_load > ANOMALY_PUBLISH_STABLE_MOTION_LOAD * 2.0f)) {
        state->publish_stable_frames = 0;
    }
    if (!use_publish_transition_gating) {
        state->publish_hold_frames = 0;
    } else if (scene_discontinuity) {
        if (state->publish_hold_frames < ANOMALY_PUBLISH_DISCONTINUITY_HOLDOFF_FRAMES) {
            state->publish_hold_frames = ANOMALY_PUBLISH_DISCONTINUITY_HOLDOFF_FRAMES;
        }
    } else if (publish_motion_unstable) {
        if (state->publish_hold_frames < ANOMALY_PUBLISH_UNSTABLE_HOLDOFF_FRAMES) {
            state->publish_hold_frames = ANOMALY_PUBLISH_UNSTABLE_HOLDOFF_FRAMES;
        }
    }
    if (scene_discontinuity || publish_motion_unstable) {
        clear_all_roi_tracks(state);
    }
    bool publish_allowed =
        !transition_warmup_block &&
        !publish_hold_active &&
        !scene_discontinuity &&
        !publish_motion_unstable;

    // ── Assemble and draw boxes ─────────────────────��─────────────────��──
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int box_count = 0;
    if (anomaly_detection_active && publish_allowed) {
        const int motion_box_algorithm = use_motion_tolerance ? ANOMALY_ALGO_MOTION_TOLERANCE : ANOMALY_ALGO_MOTION;
        box_count = assemble_anomaly_boxes(
                state,
                cfg,
                motion_box_algorithm,
                boxes,
                ANOMALY_MAX_BOXES_PER_FRAME);
    }
    anomaly_box_t overlay_boxes[ANOMALY_MAX_OVERLAY_BOXES];
    int overlay_box_count = 0;
    for (int i = 0; i < box_count && i < ANOMALY_MAX_OVERLAY_BOXES; i++) {
        overlay_boxes[overlay_box_count++] = boxes[i];
    }
    if (cfg->show_candidate_blobs && anomaly_detection_active &&
        best_thermal_candidate_idx >= 0 &&
        best_thermal_candidate_idx < thermal_candidate_count &&
        overlay_box_count < ANOMALY_MAX_OVERLAY_BOXES) {
        float best_blob_overlay_score = -1.0f;
        for (int ci = 0; ci < thermal_candidate_count; ci++) {
            if (thermal_candidates[ci].thermal_score > best_blob_overlay_score) {
                best_blob_overlay_score = thermal_candidates[ci].thermal_score;
            }
        }
        float overlay_score_floor = cfg->score_threshold;
        if (best_blob_overlay_score > overlay_score_floor) {
            float stronger_floor = best_blob_overlay_score - ANOMALY_THERMAL_BLOB_OVERLAY_SCORE_MARGIN;
            if (stronger_floor > overlay_score_floor) overlay_score_floor = stronger_floor;
        }
        int ci = best_thermal_candidate_idx;
        bool blob_plausible =
            thermal_candidate_quality_score[ci] > 0.0f &&
            thermal_candidates[ci].thermal_score >= overlay_score_floor &&
            thermal_candidate_area[ci] <= 10.0f &&
            thermal_candidate_isolation_rank[ci] >= 0.42f;
        if (blob_plausible) {
            int min_x = thermal_candidate_min_x[ci];
            int min_y = thermal_candidate_min_y[ci];
            int max_x = thermal_candidate_max_x[ci];
            int max_y = thermal_candidate_max_y[ci];
            if (max_x >= min_x && max_y >= min_y) {
                int expand_px = sample_step > 1 ? (sample_step / 2) : 1;
                float left = ((float)(roi_x0 + min_x * sample_step - expand_px)) / (float)fw;
                float top = ((float)(roi_y0 + min_y * sample_step - expand_px)) / (float)fh;
                float right = ((float)(roi_x0 + (max_x + 1) * sample_step + expand_px)) / (float)fw;
                float bottom = ((float)(roi_y0 + (max_y + 1) * sample_step + expand_px)) / (float)fh;
                append_anomaly_rect(
                        overlay_boxes,
                        &overlay_box_count,
                        left,
                        top,
                        right,
                        bottom,
                        0xFF, 0xB0, 0x40,
                        0.35f + 0.20f * clampf(thermal_candidate_quality_score[ci], 0.0f, 1.0f),
                        false);
                if (overlay_box_count > 0) {
                    overlay_boxes[overlay_box_count - 1].algorithm = ANOMALY_ALGO_THERMAL;
                }
            }
        }
    }
    if (cfg->show_candidate_blobs && anomaly_detection_active &&
        best_color_candidate_idx >= 0 &&
        best_color_candidate_idx < color_candidate_count &&
        overlay_box_count < ANOMALY_MAX_OVERLAY_BOXES) {
        int ci = best_color_candidate_idx;
        bool blob_plausible =
            color_candidate_final_score[ci] >= cfg->score_threshold &&
            color_candidate_quality[ci] > 0.0f &&
            color_candidate_isolation[ci] >= 0.35f;
        if (blob_plausible) {
            int min_x = color_candidate_min_x[ci];
            int min_y = color_candidate_min_y[ci];
            int max_x = color_candidate_max_x[ci];
            int max_y = color_candidate_max_y[ci];
            if (max_x >= min_x && max_y >= min_y) {
                int expand_px = sample_step > 1 ? (sample_step / 2) : 1;
                float left = ((float)(roi_x0 + min_x * sample_step - expand_px)) / (float)fw;
                float top = ((float)(roi_y0 + min_y * sample_step - expand_px)) / (float)fh;
                float right = ((float)(roi_x0 + (max_x + 1) * sample_step + expand_px)) / (float)fw;
                float bottom = ((float)(roi_y0 + (max_y + 1) * sample_step + expand_px)) / (float)fh;
                append_anomaly_rect(
                        overlay_boxes,
                        &overlay_box_count,
                        left,
                        top,
                        right,
                        bottom,
                        0x40, 0xD0, 0xE8,
                        0.30f + 0.24f * clampf(color_candidate_quality[ci], 0.0f, 1.0f),
                        false);
                if (overlay_box_count > 0) {
                    overlay_boxes[overlay_box_count - 1].algorithm = ANOMALY_ALGO_COLOR;
                }
            }
        }
    }

    if (result_out != NULL) {
        result_out->box_count = box_count;
        for (int i = 0; i < box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++)
            result_out->boxes[i] = boxes[i];
        result_out->motion_debug.raw_candidate_valid = (best_motion >= 0.0f);
        result_out->motion_debug.raw_score = best_motion;
        result_out->motion_debug.raw_x_norm = (best_motion_x > 0 || best_motion_y > 0)
            ? ((float)best_motion_x / fw) : 0.0f;
        result_out->motion_debug.raw_y_norm = (best_motion_x > 0 || best_motion_y > 0)
            ? ((float)best_motion_y / fh) : 0.0f;
        result_out->motion_debug.winner_component_area_frac = best_motion_component_area_frac;
        result_out->motion_debug.winner_component_span_frac = best_motion_component_span_frac;
        result_out->motion_debug.winner_component_fill_ratio = best_motion_component_fill_ratio;
        result_out->motion_debug.zoom_motion_scale = best_motion_zoom_scale;
        result_out->motion_debug.broad_motion_scale = best_motion_broad_scale;
        result_out->motion_debug.global_motion_load = debug_global_motion_load;
        result_out->motion_debug.winner_texture_scale = best_motion_texture_scale;
        result_out->motion_debug.winner_structure_scale = best_motion_structure_scale;
        result_out->motion_debug.winner_support_scale = best_motion_support_scale;
        result_out->motion_debug.winner_persistence_scale = best_motion_persistence_scale;
        result_out->motion_debug.top_candidate_count = motion_top_count;
        for (int i = 0; i < motion_top_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
            result_out->motion_debug.top_candidates[i] = motion_top[i];
        }
        result_out->thermal_debug.bg_ready = bg_valid;
        result_out->thermal_debug.raw_candidate_valid = (best_thermal >= 0.0f);
        result_out->thermal_debug.raw_score = best_thermal;
        result_out->thermal_debug.raw_x_norm = (best_thermal_x > 0 || best_thermal_y > 0)
            ? ((float)best_thermal_x / fw) : 0.0f;
        result_out->thermal_debug.raw_y_norm = (best_thermal_x > 0 || best_thermal_y > 0)
            ? ((float)best_thermal_y / fh) : 0.0f;
        result_out->thermal_debug.winning_candidate_index = best_thermal_candidate_idx;
        result_out->thermal_debug.candidate_count = thermal_candidate_count;
        memset(&result_out->thermal_debug.target, 0, sizeof(result_out->thermal_debug.target));
        result_out->thermal_debug.target.enabled = thermal_target_trace.enabled;
        result_out->thermal_debug.target.valid = thermal_target_trace.valid;
        result_out->thermal_debug.target.inside_scan_zone = thermal_target_trace.inside_scan_zone;
        result_out->thermal_debug.target.pixel_x = thermal_target_trace.target_px;
        result_out->thermal_debug.target.pixel_y = thermal_target_trace.target_py;
        result_out->thermal_debug.target.sample_x = thermal_target_trace.target_sx;
        result_out->thermal_debug.target.sample_y = thermal_target_trace.target_sy;
        result_out->thermal_debug.target.x_norm = thermal_target_trace.target_x_norm;
        result_out->thermal_debug.target.y_norm = thermal_target_trace.target_y_norm;
        result_out->thermal_debug.target.target_delta = thermal_target_trace.target_delta;
        result_out->thermal_debug.target.target_score = thermal_target_trace.target_score;
        result_out->thermal_debug.target.hot_eligible = thermal_target_trace.hot_eligible;
        result_out->thermal_debug.target.started_component = thermal_target_trace.started_component;
        result_out->thermal_debug.target.local_max = thermal_target_trace.local_max;
        result_out->thermal_debug.target.suppressor_sample_x = thermal_target_trace.suppressor_sx;
        result_out->thermal_debug.target.suppressor_sample_y = thermal_target_trace.suppressor_sy;
        result_out->thermal_debug.target.suppressor_delta = thermal_target_trace.suppressor_delta;
        result_out->thermal_debug.target.suppressor_score = thermal_target_trace.suppressor_score;
        result_out->thermal_debug.target.component_seed_x = thermal_target_trace.component_seed_x;
        result_out->thermal_debug.target.component_seed_y = thermal_target_trace.component_seed_y;
        result_out->thermal_debug.target.component_peak_x = thermal_target_trace.component_peak_x;
        result_out->thermal_debug.target.component_peak_y = thermal_target_trace.component_peak_y;
        result_out->thermal_debug.target.component_area = thermal_target_trace.component_area;
        result_out->thermal_debug.target.component_span = thermal_target_trace.component_span;
        result_out->thermal_debug.target.component_fill = thermal_target_trace.component_fill;
        result_out->thermal_debug.target.component_peak_delta = thermal_target_trace.component_peak_delta;
        result_out->thermal_debug.target.component_mean_delta = thermal_target_trace.component_mean_delta;
        result_out->thermal_debug.target.component_quality = thermal_target_trace.component_quality;
        result_out->thermal_debug.target.component_rejected = thermal_target_trace.component_rejected;
        result_out->thermal_debug.target.rejection_gate = thermal_target_trace.rejection_gate;
        result_out->thermal_debug.target.dropped_by_cap = thermal_target_trace.dropped_by_cap;
        result_out->thermal_debug.target.dropped_by_nms = thermal_target_trace.dropped_by_nms;
        result_out->thermal_debug.target.replaced_by_nms = thermal_target_trace.replaced_by_nms;
        result_out->thermal_debug.target.nms_conflict_rank = thermal_target_trace.nms_conflict_rank;
        result_out->thermal_debug.target.nms_conflict_sample_x = thermal_target_trace.nms_conflict_sample_x;
        result_out->thermal_debug.target.nms_conflict_sample_y = thermal_target_trace.nms_conflict_sample_y;
        result_out->thermal_debug.target.extracted_rank = thermal_target_trace.extracted_rank;
        result_out->thermal_debug.target.winning_rank = thermal_target_trace.winning_rank;
        result_out->thermal_debug.target.stage = thermal_target_trace.stage;
        for (int i = 0; i < thermal_candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES; i++) {
            anomaly_debug_thermal_candidate_t *dbg = &result_out->thermal_debug.candidates[i];
            dbg->valid = true;
            dbg->pixel_x = thermal_candidates[i].pixel_x;
            dbg->pixel_y = thermal_candidates[i].pixel_y;
            dbg->x_norm = (float)thermal_candidates[i].pixel_x / fw;
            dbg->y_norm = (float)thermal_candidates[i].pixel_y / fh;
            dbg->bbox_left_norm = (float)(roi_x0 + thermal_candidate_min_x[i] * sample_step) / fw;
            dbg->bbox_top_norm = (float)(roi_y0 + thermal_candidate_min_y[i] * sample_step) / fh;
            dbg->bbox_right_norm = (float)(roi_x0 + thermal_candidate_max_x[i] * sample_step) / fw;
            dbg->bbox_bottom_norm = (float)(roi_y0 + thermal_candidate_max_y[i] * sample_step) / fh;
            dbg->base_score = thermal_candidate_base_score[i];
            dbg->final_score = thermal_candidates[i].thermal_score;
            dbg->temporal_score = thermal_candidate_temporal_score[i];
            dbg->area = thermal_candidate_area[i];
            dbg->span = thermal_candidate_span[i];
            dbg->fill = thermal_candidate_fill[i];
            dbg->center_share = thermal_candidate_center_share[i];
            dbg->quality = thermal_candidate_quality_score[i];
            dbg->isolation_rank = thermal_candidate_isolation_rank[i];
            dbg->peak_delta = thermal_candidate_peak_delta[i];
            dbg->mean_delta = thermal_candidate_mean_delta[i];
            dbg->score_scale = thermal_candidate_score_scale[i];
            dbg->history_scale = thermal_candidate_history_scale_debug[i];
            dbg->apparent_size_scale = thermal_candidate_apparent_size_scale[i];
            dbg->isolation_track_scale = thermal_candidate_isolation_track_scale[i];
            dbg->context_scale = thermal_candidate_context_scale[i];
            dbg->parent_scale = thermal_candidate_parent_scale[i];
            dbg->area_rank = thermal_candidate_area_rank[i];
            dbg->span_rank = thermal_candidate_span_rank[i];
            dbg->center_rank = thermal_candidate_center_rank[i];
            dbg->quality_rank = thermal_candidate_quality_rank[i];
            dbg->patch_support = thermal_candidate_patch_support[i];
            dbg->motion_support = thermal_candidate_motion_support[i];
            dbg->singleton_score_scale = thermal_candidate_singleton_score_scale[i];
            dbg->retention_rank = thermal_candidate_retention_rank_debug[i];
            dbg->singleton_blob = thermal_candidate_singleton_blob_debug[i];
            dbg->above_threshold = thermal_candidate_above_threshold[i];
        }
        memset(&result_out->color_debug, 0, sizeof(result_out->color_debug));
        result_out->color_debug.raw_candidate_valid = (best_color >= 0.0f);
        result_out->color_debug.raw_score = best_color;
        result_out->color_debug.raw_x_norm = (best_color_x > 0 || best_color_y > 0)
            ? ((float)best_color_x / fw) : 0.0f;
        result_out->color_debug.raw_y_norm = (best_color_x > 0 || best_color_y > 0)
            ? ((float)best_color_y / fh) : 0.0f;
        result_out->color_debug.active_phase_index = color_phase_index;
        result_out->color_debug.active_phase_x = color_phase_x;
        result_out->color_debug.active_phase_y = color_phase_y;
        result_out->color_debug.selective_reuse_active = selective_refresh_active && !color_forced_full_refresh;
        result_out->color_debug.forced_full_refresh = color_forced_full_refresh;
        result_out->color_debug.fallback_reason_flags = color_fallback_reason_flags;
        result_out->color_debug.fresh_sample_count = color_fresh_sample_count;
        result_out->color_debug.carried_sample_count = color_carried_sample_count;
        result_out->color_debug.unsampled_new_exposed_count = color_unsampled_new_count;
        if (sg_count > 0) {
            result_out->color_debug.fresh_sample_fraction = (float)color_fresh_sample_count / (float)sg_count;
            result_out->color_debug.carried_sample_fraction = (float)color_carried_sample_count / (float)sg_count;
            result_out->color_debug.unsampled_new_exposed_fraction = (float)color_unsampled_new_count / (float)sg_count;
        }
        result_out->color_debug.nonzero_histogram_bins = color_hist_nonzero_bins;
        result_out->color_debug.max_histogram_current_count = color_hist_max_current_count;
        result_out->color_debug.max_histogram_recent_count = color_hist_max_recent_count;
        result_out->color_debug.winning_candidate_index = best_color_candidate_idx;
        result_out->color_debug.candidate_count = color_candidate_count;
        for (int i = 0; i < color_candidate_count && i < ANOMALY_DEBUG_TOP_COLOR_CANDIDATES; i++) {
            anomaly_debug_color_candidate_t *dbg = &result_out->color_debug.candidates[i];
            dbg->valid = true;
            dbg->pixel_x = color_candidates[i].pixel_x;
            dbg->pixel_y = color_candidates[i].pixel_y;
            dbg->x_norm = (float)color_candidates[i].pixel_x / fw;
            dbg->y_norm = (float)color_candidates[i].pixel_y / fh;
            dbg->bbox_left_norm = (float)(roi_x0 + color_candidate_min_x[i] * sample_step) / fw;
            dbg->bbox_top_norm = (float)(roi_y0 + color_candidate_min_y[i] * sample_step) / fh;
            dbg->bbox_right_norm = (float)(roi_x0 + color_candidate_max_x[i] * sample_step) / fw;
            dbg->bbox_bottom_norm = (float)(roi_y0 + color_candidate_max_y[i] * sample_step) / fh;
            dbg->base_score = color_candidate_base_score[i];
            dbg->final_score = color_candidate_final_score[i];
            dbg->temporal_score = -1.0f;
            dbg->area = color_candidate_area[i];
            dbg->span = color_candidate_span[i];
            dbg->fill = color_candidate_fill[i];
            dbg->center_share = color_candidate_center_share[i];
            dbg->quality = color_candidate_quality[i];
            dbg->isolation_score = color_candidate_isolation[i];
            dbg->ring_fraction = color_candidate_ring_fraction[i];
            dbg->support_mass = color_candidate_support_mass[i];
            dbg->contrast_weight = color_candidate_contrast_weight[i];
            dbg->hist_key = color_candidate_hist_key[i];
            dbg->hist_current_count = color_candidate_hist_current_count[i];
            dbg->hist_recent_count = color_candidate_hist_recent_count[i];
            dbg->hist_rarity_score = color_candidate_hist_rarity[i];
            dbg->retention_rank = color_candidate_retention_rank[i];
            dbg->above_threshold = color_candidate_above_threshold[i];
        }
        result_out->saliency_debug.raw_candidate_valid = (best_persist >= 0.0f);
        result_out->saliency_debug.raw_score = best_persist;
        result_out->saliency_debug.raw_x_norm = (best_persist_x > 0 || best_persist_y > 0)
            ? ((float)best_persist_x / fw) : 0.0f;
        result_out->saliency_debug.raw_y_norm = (best_persist_x > 0 || best_persist_y > 0)
            ? ((float)best_persist_y / fh) : 0.0f;
        result_out->saliency_debug.tracked_score_pre = saliency_tracked_score_pre;
        result_out->saliency_debug.acc_pre_active = saliency_acc_pre_active;
        result_out->saliency_debug.acc_pre_hits = saliency_acc_pre_hits;
        result_out->saliency_debug.acc_pre_x_norm = saliency_acc_pre_x;
        result_out->saliency_debug.acc_pre_y_norm = saliency_acc_pre_y;
        result_out->saliency_debug.acc_post_active = state->acc_active[3];
        result_out->saliency_debug.acc_post_hits = state->acc_hits[3];
        result_out->saliency_debug.acc_post_x_norm = state->acc_cx[3];
        result_out->saliency_debug.acc_post_y_norm = state->acc_cy[3];
        result_out->saliency_debug.switch_suppressed = saliency_switch_suppressed;
        result_out->saliency_debug.top_candidate_count = saliency_top_count;
        for (int i = 0; i < saliency_top_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
            result_out->saliency_debug.top_candidates[i] = saliency_top[i];
        }
    }

    stage_started_us = anomaly_timing_now_us();
    if (rgba != NULL) {
        if (show_hot_overlay) {
            draw_hot_overlay_rgba(rgba, rgba_stride, width, height, cfg->thermal_polarity);
        }
        if (overlay_box_count > 0) {
            draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, overlay_boxes, overlay_box_count);
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_OVERLAY_DRAW, stage_started_us);
    advance_color_sampling_phase(state, should_refresh_appearance, sample_step);
    finalize_result_timing(result_out, &timing, frame_started_us);

    return box_count;
}

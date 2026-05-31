// Internal helpers shared by anomaly detector modules.
#pragma once

#include "anomaly_analysis.h"

#include <stdint.h>
#include <string.h>
#if ANOMALY_DEBUG_TIMING
#include <time.h>
#endif

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

#if ANOMALY_DEBUG_TIMING
static inline int64_t anomaly_timing_now_us(void) {
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
static inline int64_t anomaly_timing_now_us(void) {
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

static inline void anomaly_result_init(
        anomaly_result_t       *result_out,
        const anomaly_config_t *cfg) {
    if (result_out == NULL) return;

    memset(result_out, 0, sizeof(*result_out));
    result_out->box_count = 0;
    result_out->had_discontinuity = false;
    result_out->registration_health = ANOMALY_REG_HEALTH_UNKNOWN;
    result_out->rescan_mode = ANOMALY_RESCAN_MODE_UNSET;
    result_out->scan_plan.mode = ANOMALY_RESCAN_MODE_UNSET;
    result_out->adaptive_effective_stride = cfg != NULL && cfg->frame_stride > 0
        ? cfg->frame_stride
        : 1;
    result_out->adaptive_stable_frames = 0;
    result_out->adaptive_drop_hold_frames = 0;
    result_out->adaptive_motion_load = 0.0f;
    result_out->adaptive_reason_flags = 0u;
}

static inline void anomaly_result_finalize_timing(
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

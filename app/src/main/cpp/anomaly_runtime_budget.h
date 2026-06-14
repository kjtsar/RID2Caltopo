#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

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

typedef struct {
    int64_t interval_ms;
    int confidence;
} anomaly_detector_runtime_budget_source_interval_estimate_t;

typedef struct {
    int64_t desired_interval_ms;
    int64_t render_interval_ms;
} anomaly_detector_runtime_budget_render_interval_t;

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps);

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget);

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget);

int anomaly_detector_runtime_budget_trim_keep_latest_frames(
        int64_t source_interval_ms,
        int64_t target_latency_ms,
        int64_t default_source_interval_ms,
        int64_t default_target_latency_ms,
        int min_keep_frames,
        int max_keep_frames);

int anomaly_detector_runtime_budget_render_queue_hard_cap(
        int keep_latest_frames,
        int fallback_keep_latest_frames,
        int min_keep_frames,
        int min_extra_frames,
        int min_hard_cap_frames,
        int max_hard_cap_frames);

int64_t anomaly_detector_runtime_budget_target_latency_ms(
        int64_t stall_estimate_ms,
        int64_t proven_gap_ms,
        int64_t stall_floor_ms,
        int64_t processing_margin_ms,
        int64_t min_target_latency_ms,
        int64_t max_target_latency_ms);

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_update_source_interval_estimate(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t decode_delta_ms,
        int64_t default_interval_ms,
        int ema_pct,
        int confidence_step,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_apply_pts_source_interval(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t pts_interval_ms,
        bool force_direct,
        int64_t default_interval_ms,
        int direct_confidence_threshold,
        int confidence_floor,
        int64_t far_delta_threshold_ms,
        int near_blend_pct,
        int far_blend_pct,
        int64_t min_interval_ms,
        int64_t max_interval_ms);

int64_t anomaly_detector_runtime_budget_update_stall_estimate_ms(
        int64_t current_stall_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int rise_ema_pct,
        int decay_ema_pct,
        int64_t max_stall_ms);

int64_t anomaly_detector_runtime_budget_update_proven_gap_ms(
        int64_t current_proven_gap_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int blend_ema_pct,
        int64_t max_gap_ms);

int64_t anomaly_detector_runtime_budget_decay_toward_floor_ms(
        int64_t current_value_ms,
        int64_t floor_ms,
        int decay_ema_pct);

anomaly_detector_runtime_budget_render_interval_t
anomaly_detector_runtime_budget_desired_render_interval_ms(
        int64_t source_interval_ms,
        int64_t previous_interval_ms,
        int64_t buffered_span_ms,
        int64_t target_latency_ms,
        bool stall_active,
        int adjust_base_pct,
        int adjust_max_pct,
        int smoothing_pct);

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode);

#ifdef __cplusplus
}
#endif

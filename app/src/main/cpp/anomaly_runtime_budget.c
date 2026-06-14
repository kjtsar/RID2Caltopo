#include "anomaly_runtime_budget.h"

#include <math.h>

static float anomaly_detector_at_least(float value, float minimum) {
    return value >= minimum ? value : minimum;
}

static int64_t anomaly_detector_budget_clamp_i64(
        int64_t value,
        int64_t min_value,
        int64_t max_value) {
    if (value < min_value) {
        return min_value;
    }
    if (value > max_value) {
        return max_value;
    }
    return value;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_make_default(
        float frame_rate_fps) {
    (void)frame_rate_fps;
    anomaly_detector_runtime_budget_t budget = {
        .render_backlog_seconds = 0.0f,
        .startup_elapsed_seconds = 0.0f,
        .startup_skip_seconds = 0.25f,
        .cursory_backlog_seconds = 0.25f,
        .thorough_backlog_seconds = 0.5f,
        .max_backlog_seconds = 0.5f,
        .adapter_pressure = false,
    };
    return budget;
}

anomaly_detector_runtime_budget_t anomaly_detector_runtime_budget_normalize(
        anomaly_detector_runtime_budget_t budget) {
    if (budget.render_backlog_seconds < 0.0f) {
        budget.render_backlog_seconds = 0.0f;
    }
    if (budget.startup_elapsed_seconds < 0.0f) {
        budget.startup_elapsed_seconds = 0.0f;
    }

    budget.startup_skip_seconds =
        anomaly_detector_at_least(budget.startup_skip_seconds, 0.25f);
    budget.cursory_backlog_seconds =
        anomaly_detector_at_least(budget.cursory_backlog_seconds, 0.25f);
    budget.thorough_backlog_seconds =
        anomaly_detector_at_least(budget.thorough_backlog_seconds, 0.5f);
    if (budget.thorough_backlog_seconds < budget.cursory_backlog_seconds) {
        budget.thorough_backlog_seconds = budget.cursory_backlog_seconds;
    }

    budget.max_backlog_seconds =
        anomaly_detector_at_least(budget.max_backlog_seconds, 0.5f);
    if (budget.max_backlog_seconds < budget.thorough_backlog_seconds) {
        budget.max_backlog_seconds = budget.thorough_backlog_seconds;
    }
    return budget;
}

anomaly_detector_processing_mode_t anomaly_detector_runtime_budget_processing_mode(
        anomaly_detector_runtime_budget_t budget) {
    budget = anomaly_detector_runtime_budget_normalize(budget);
    if (budget.adapter_pressure) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.startup_elapsed_seconds < budget.startup_skip_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds <= budget.cursory_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
    }
    if (budget.render_backlog_seconds >= budget.thorough_backlog_seconds) {
        return ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH;
    }
    return ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY;
}

int anomaly_detector_runtime_budget_trim_keep_latest_frames(
        int64_t source_interval_ms,
        int64_t target_latency_ms,
        int64_t default_source_interval_ms,
        int64_t default_target_latency_ms,
        int min_keep_frames,
        int max_keep_frames) {
    if (source_interval_ms <= 0) {
        source_interval_ms = default_source_interval_ms;
    }
    if (source_interval_ms <= 0) {
        source_interval_ms = 1;
    }
    if (target_latency_ms <= 0) {
        target_latency_ms = default_target_latency_ms;
    }
    if (target_latency_ms <= 0) {
        target_latency_ms = 0;
    }
    if (min_keep_frames < 0) {
        min_keep_frames = 0;
    }
    if (max_keep_frames < min_keep_frames) {
        max_keep_frames = min_keep_frames;
    }

    int keep_latest = (int) ((target_latency_ms + source_interval_ms - 1) /
                             source_interval_ms);
    if (keep_latest < min_keep_frames) {
        keep_latest = min_keep_frames;
    }
    if (keep_latest > max_keep_frames) {
        keep_latest = max_keep_frames;
    }
    return keep_latest;
}

int anomaly_detector_runtime_budget_render_queue_hard_cap(
        int keep_latest_frames,
        int fallback_keep_latest_frames,
        int min_keep_frames,
        int min_extra_frames,
        int min_hard_cap_frames,
        int max_hard_cap_frames) {
    if (min_keep_frames < 0) {
        min_keep_frames = 0;
    }
    if (keep_latest_frames < min_keep_frames) {
        keep_latest_frames = fallback_keep_latest_frames;
    }
    if (keep_latest_frames < min_keep_frames) {
        keep_latest_frames = min_keep_frames;
    }
    if (min_extra_frames < 0) {
        min_extra_frames = 0;
    }
    if (max_hard_cap_frames < min_hard_cap_frames) {
        max_hard_cap_frames = min_hard_cap_frames;
    }

    int hard_cap = keep_latest_frames * 2;
    int extra_cap = keep_latest_frames + min_extra_frames;
    if (hard_cap < extra_cap) {
        hard_cap = extra_cap;
    }
    if (hard_cap < min_hard_cap_frames) {
        hard_cap = min_hard_cap_frames;
    }
    if (hard_cap > max_hard_cap_frames) {
        hard_cap = max_hard_cap_frames;
    }
    return hard_cap;
}

int64_t anomaly_detector_runtime_budget_target_latency_ms(
        int64_t stall_estimate_ms,
        int64_t proven_gap_ms,
        int64_t stall_floor_ms,
        int64_t processing_margin_ms,
        int64_t min_target_latency_ms,
        int64_t max_target_latency_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (stall_estimate_ms <= 0) {
        stall_estimate_ms = stall_floor_ms;
    }
    if (stall_estimate_ms < stall_floor_ms) {
        stall_estimate_ms = stall_floor_ms;
    }
    if (proven_gap_ms > stall_estimate_ms) {
        stall_estimate_ms = proven_gap_ms;
    }
    if (processing_margin_ms < 0) {
        processing_margin_ms = 0;
    }
    if (max_target_latency_ms < min_target_latency_ms) {
        max_target_latency_ms = min_target_latency_ms;
    }

    int64_t target_ms = (stall_estimate_ms * 2) + processing_margin_ms;
    if (target_ms < min_target_latency_ms) {
        target_ms = min_target_latency_ms;
    }
    if (target_ms > max_target_latency_ms) {
        target_ms = max_target_latency_ms;
    }
    return target_ms;
}

anomaly_detector_runtime_budget_source_interval_estimate_t
anomaly_detector_runtime_budget_update_source_interval_estimate(
        int64_t current_interval_ms,
        int current_confidence,
        int64_t decode_delta_ms,
        int64_t default_interval_ms,
        int ema_pct,
        int confidence_step,
        int64_t min_interval_ms,
        int64_t max_interval_ms) {
    anomaly_detector_runtime_budget_source_interval_estimate_t estimate = {
        .interval_ms = current_interval_ms,
        .confidence = current_confidence,
    };
    if (estimate.confidence < 0) {
        estimate.confidence = 0;
    }
    if (default_interval_ms <= 0) {
        default_interval_ms = min_interval_ms > 0 ? min_interval_ms : 1;
    }
    if (current_interval_ms <= 0) {
        current_interval_ms = default_interval_ms;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    if (ema_pct < 0) {
        ema_pct = 0;
    }
    if (ema_pct > 100) {
        ema_pct = 100;
    }
    if (confidence_step < 0) {
        confidence_step = 0;
    }

    int64_t new_interval_ms = current_interval_ms;
    if (decode_delta_ms > 0) {
        if (current_confidence <= 0) {
            new_interval_ms = decode_delta_ms;
        } else {
            new_interval_ms =
                    ((current_interval_ms * (100 - ema_pct)) +
                     (decode_delta_ms * ema_pct) + 50) / 100;
        }
    }
    if (new_interval_ms < min_interval_ms) {
        new_interval_ms = min_interval_ms;
    }
    if (new_interval_ms > max_interval_ms) {
        new_interval_ms = max_interval_ms;
    }
    estimate.interval_ms = new_interval_ms;

    if (estimate.confidence < 100) {
        estimate.confidence += confidence_step;
        if (estimate.confidence > 100) {
            estimate.confidence = 100;
        }
    }
    return estimate;
}

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
        int64_t max_interval_ms) {
    anomaly_detector_runtime_budget_source_interval_estimate_t estimate = {
        .interval_ms = current_interval_ms,
        .confidence = current_confidence,
    };
    if (estimate.confidence < 0) {
        estimate.confidence = 0;
    }
    if (default_interval_ms <= 0) {
        default_interval_ms = min_interval_ms > 0 ? min_interval_ms : 1;
    }
    if (current_interval_ms <= 0) {
        current_interval_ms = default_interval_ms;
    }
    if (min_interval_ms < 0) {
        min_interval_ms = 0;
    }
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    if (far_delta_threshold_ms < 0) {
        far_delta_threshold_ms = 0;
    }
    if (near_blend_pct < 0) {
        near_blend_pct = 0;
    }
    if (near_blend_pct > 100) {
        near_blend_pct = 100;
    }
    if (far_blend_pct < 0) {
        far_blend_pct = 0;
    }
    if (far_blend_pct > 100) {
        far_blend_pct = 100;
    }

    int64_t new_interval_ms = current_interval_ms;
    if (pts_interval_ms > 0) {
        if (force_direct || current_confidence < direct_confidence_threshold) {
            new_interval_ms = pts_interval_ms;
        } else {
            int64_t delta_ms = pts_interval_ms - current_interval_ms;
            if (delta_ms < 0) {
                delta_ms = -delta_ms;
            }
            int blend_pct = delta_ms >= far_delta_threshold_ms
                    ? far_blend_pct
                    : near_blend_pct;
            new_interval_ms =
                    ((current_interval_ms * (100 - blend_pct)) +
                     (pts_interval_ms * blend_pct) + 50) / 100;
            if (new_interval_ms == current_interval_ms &&
                pts_interval_ms != current_interval_ms) {
                new_interval_ms += pts_interval_ms > current_interval_ms ? 1 : -1;
            }
        }
    }
    if (new_interval_ms < min_interval_ms) {
        new_interval_ms = min_interval_ms;
    }
    if (new_interval_ms > max_interval_ms) {
        new_interval_ms = max_interval_ms;
    }
    estimate.interval_ms = new_interval_ms;
    if (estimate.confidence < confidence_floor) {
        estimate.confidence = confidence_floor;
    }
    if (estimate.confidence > 100) {
        estimate.confidence = 100;
    }
    return estimate;
}

int64_t anomaly_detector_runtime_budget_update_stall_estimate_ms(
        int64_t current_stall_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int rise_ema_pct,
        int decay_ema_pct,
        int64_t max_stall_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (max_stall_ms < stall_floor_ms) {
        max_stall_ms = stall_floor_ms;
    }
    if (current_stall_ms <= 0) {
        current_stall_ms = stall_floor_ms;
    }
    if (current_stall_ms < stall_floor_ms) {
        current_stall_ms = stall_floor_ms;
    }
    if (rise_ema_pct < 0) {
        rise_ema_pct = 0;
    }
    if (rise_ema_pct > 100) {
        rise_ema_pct = 100;
    }
    if (decay_ema_pct < 0) {
        decay_ema_pct = 0;
    }
    if (decay_ema_pct > 100) {
        decay_ema_pct = 100;
    }

    int ema_pct = gap_ms >= current_stall_ms ? rise_ema_pct : decay_ema_pct;
    int64_t new_stall_ms =
            ((current_stall_ms * (100 - ema_pct)) +
             (gap_ms * ema_pct) + 50) / 100;
    if (new_stall_ms < stall_floor_ms) {
        new_stall_ms = stall_floor_ms;
    }
    if (new_stall_ms > max_stall_ms) {
        new_stall_ms = max_stall_ms;
    }
    return new_stall_ms;
}

int64_t anomaly_detector_runtime_budget_update_proven_gap_ms(
        int64_t current_proven_gap_ms,
        int64_t gap_ms,
        int64_t stall_floor_ms,
        int blend_ema_pct,
        int64_t max_gap_ms) {
    if (stall_floor_ms < 0) {
        stall_floor_ms = 0;
    }
    if (max_gap_ms < stall_floor_ms) {
        max_gap_ms = stall_floor_ms;
    }
    if (current_proven_gap_ms <= 0) {
        current_proven_gap_ms = stall_floor_ms;
    }
    if (current_proven_gap_ms < stall_floor_ms) {
        current_proven_gap_ms = stall_floor_ms;
    }
    if (blend_ema_pct < 0) {
        blend_ema_pct = 0;
    }
    if (blend_ema_pct > 100) {
        blend_ema_pct = 100;
    }

    int64_t new_gap_ms = current_proven_gap_ms;
    if (gap_ms > current_proven_gap_ms) {
        new_gap_ms = gap_ms;
    } else {
        new_gap_ms =
                ((current_proven_gap_ms * (100 - blend_ema_pct)) +
                 (gap_ms * blend_ema_pct) + 50) / 100;
    }
    if (new_gap_ms < stall_floor_ms) {
        new_gap_ms = stall_floor_ms;
    }
    if (new_gap_ms > max_gap_ms) {
        new_gap_ms = max_gap_ms;
    }
    return new_gap_ms;
}

int64_t anomaly_detector_runtime_budget_decay_toward_floor_ms(
        int64_t current_value_ms,
        int64_t floor_ms,
        int decay_ema_pct) {
    if (floor_ms < 0) {
        floor_ms = 0;
    }
    if (current_value_ms < floor_ms) {
        current_value_ms = floor_ms;
    }
    if (decay_ema_pct < 0) {
        decay_ema_pct = 0;
    }
    if (decay_ema_pct > 100) {
        decay_ema_pct = 100;
    }

    int64_t new_value_ms =
            ((current_value_ms * (100 - decay_ema_pct)) +
             (floor_ms * decay_ema_pct) + 50) / 100;
    if (new_value_ms < floor_ms) {
        new_value_ms = floor_ms;
    }
    return new_value_ms;
}

anomaly_detector_runtime_budget_render_interval_t
anomaly_detector_runtime_budget_desired_render_interval_ms(
        int64_t source_interval_ms,
        int64_t previous_interval_ms,
        int64_t buffered_span_ms,
        int64_t target_latency_ms,
        bool stall_active,
        int adjust_base_pct,
        int adjust_max_pct,
        int smoothing_pct) {
    if (source_interval_ms <= 0) {
        source_interval_ms = 1;
    }
    if (previous_interval_ms <= 0) {
        previous_interval_ms = source_interval_ms;
    }
    if (adjust_base_pct < 0) {
        adjust_base_pct = 0;
    }
    if (adjust_base_pct > 100) {
        adjust_base_pct = 100;
    }
    if (adjust_max_pct < adjust_base_pct) {
        adjust_max_pct = adjust_base_pct;
    }
    if (adjust_max_pct > 100) {
        adjust_max_pct = 100;
    }
    if (smoothing_pct < 0) {
        smoothing_pct = 0;
    }
    if (smoothing_pct > 100) {
        smoothing_pct = 100;
    }

    double error_ratio = 0.0;
    if (target_latency_ms > 0) {
        error_ratio = (double) (buffered_span_ms - target_latency_ms) /
                      (double) target_latency_ms;
    }
    if (error_ratio < -0.5) error_ratio = -0.5;
    if (error_ratio > 4.0) error_ratio = 4.0;

    double adjust_pct = error_ratio * (double) adjust_base_pct;
    double backlog_ratio = target_latency_ms > 0
            ? (double) buffered_span_ms / (double) target_latency_ms
            : 1.0;
    double max_adjust_pct = (double) adjust_base_pct;
    if (backlog_ratio >= 1.5) max_adjust_pct = 20.0;
    if (backlog_ratio >= 2.0) max_adjust_pct = 28.0;
    if (backlog_ratio >= 3.0) max_adjust_pct = 35.0;
    if (backlog_ratio >= 5.0) max_adjust_pct = 40.0;
    if (max_adjust_pct > (double) adjust_max_pct) {
        max_adjust_pct = (double) adjust_max_pct;
    }
    if (adjust_pct < -(double) adjust_base_pct) {
        adjust_pct = -(double) adjust_base_pct;
    }
    if (adjust_pct > max_adjust_pct) {
        adjust_pct = max_adjust_pct;
    }

    int64_t desired_interval_ms =
            (int64_t) llround((double) source_interval_ms *
                              (100.0 - adjust_pct) / 100.0);
    bool preserve_during_stall =
            stall_active &&
            target_latency_ms > 0 &&
            buffered_span_ms <= ((target_latency_ms * 5) / 4);
    if (preserve_during_stall) {
        int64_t preserve_interval_ms = (source_interval_ms * 108 + 99) / 100;
        if (desired_interval_ms < preserve_interval_ms) {
            desired_interval_ms = preserve_interval_ms;
        }
    }

    int64_t min_interval_ms =
            (source_interval_ms * (100 - adjust_max_pct) + 99) / 100;
    int64_t max_interval_ms =
            (source_interval_ms * (100 + adjust_base_pct) + 99) / 100;
    if (max_interval_ms < min_interval_ms) {
        max_interval_ms = min_interval_ms;
    }
    desired_interval_ms = anomaly_detector_budget_clamp_i64(
            desired_interval_ms,
            min_interval_ms,
            max_interval_ms);

    int64_t smoothed_interval_ms =
            ((previous_interval_ms * (100 - smoothing_pct)) +
             (desired_interval_ms * smoothing_pct) + 50) / 100;
    smoothed_interval_ms = anomaly_detector_budget_clamp_i64(
            smoothed_interval_ms,
            min_interval_ms,
            max_interval_ms);

    anomaly_detector_runtime_budget_render_interval_t interval = {
        .desired_interval_ms = desired_interval_ms,
        .render_interval_ms = smoothed_interval_ms,
    };
    return interval;
}

const char *anomaly_detector_processing_mode_name(
        anomaly_detector_processing_mode_t mode) {
    switch (mode) {
        case ANOMALY_DETECTOR_PROCESSING_MODE_CURSORY:
            return "cursory";
        case ANOMALY_DETECTOR_PROCESSING_MODE_THOROUGH:
            return "thorough";
        default:
            return "unknown";
    }
}

#ifndef ANOMALY_RUNTIME_CONFIG_H
#define ANOMALY_RUNTIME_CONFIG_H

#include "anomaly_analysis_internal.h"
#include "anomaly_thermal_detector.h"

#include <math.h>
#include <stddef.h>

#define ANOMALY_RUNTIME_CONFIG_FLOAT_EPSILON 1.0e-6f

static inline bool anomaly_runtime_config_float_changed(float before, float after) {
    return fabsf(before - after) > ANOMALY_RUNTIME_CONFIG_FLOAT_EPSILON;
}

static inline void anomaly_runtime_config_raise_transition(
        anomaly_config_transition_t *transition,
        anomaly_config_transition_t  candidate) {
    if (transition != NULL && candidate > *transition) {
        *transition = candidate;
    }
}

static inline anomaly_config_transition_t anomaly_runtime_config_transition_classify(
        const anomaly_config_t *before,
        const anomaly_config_t *after) {
    if (before == NULL || after == NULL) {
        return ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE;
    }

    anomaly_config_transition_t transition = ANOMALY_CONFIG_TRANSITION_UNCHANGED;

    if (before->show_hot_overlay != after->show_hot_overlay ||
        before->show_candidate_blobs != after->show_candidate_blobs) {
        anomaly_runtime_config_raise_transition(
                &transition,
                ANOMALY_CONFIG_TRANSITION_DISPLAY_ONLY);
    }

    if (before->thermal_debug_target_enabled != after->thermal_debug_target_enabled ||
        anomaly_runtime_config_float_changed(
                before->thermal_debug_target_x_norm,
                after->thermal_debug_target_x_norm) ||
        anomaly_runtime_config_float_changed(
                before->thermal_debug_target_y_norm,
                after->thermal_debug_target_y_norm) ||
        before->color_debug_target_enabled != after->color_debug_target_enabled ||
        anomaly_runtime_config_float_changed(
                before->color_debug_target_x_norm,
                after->color_debug_target_x_norm) ||
        anomaly_runtime_config_float_changed(
                before->color_debug_target_y_norm,
                after->color_debug_target_y_norm)) {
        anomaly_runtime_config_raise_transition(
                &transition,
                ANOMALY_CONFIG_TRANSITION_DEBUG_ONLY);
    }

    if (before->enabled != after->enabled ||
        anomaly_runtime_config_float_changed(before->score_threshold, after->score_threshold) ||
        anomaly_runtime_config_float_changed(before->motion_evidence_scale, after->motion_evidence_scale) ||
        anomaly_runtime_config_float_changed(before->thermal_min_delta, after->thermal_min_delta) ||
        before->color_target_candidate_limit != after->color_target_candidate_limit ||
        before->min_hits != after->min_hits) {
        anomaly_runtime_config_raise_transition(
                &transition,
                ANOMALY_CONFIG_TRANSITION_LIVE_UPDATE);
    }

    if (before->algorithm_mask != after->algorithm_mask ||
        before->registration_mode != after->registration_mode ||
        before->movement_estimator_mode != after->movement_estimator_mode ||
        before->stride_mode != after->stride_mode ||
        before->frame_stride != after->frame_stride ||
        before->adaptive_min_stride_frames != after->adaptive_min_stride_frames ||
        before->adaptive_max_stride_frames != after->adaptive_max_stride_frames ||
        anomaly_runtime_config_float_changed(
                before->adaptive_max_stride_seconds,
                after->adaptive_max_stride_seconds) ||
        before->pixel_step != after->pixel_step ||
        anomaly_runtime_config_float_changed(before->min_area_fraction, after->min_area_fraction) ||
        before->thermal_polarity != after->thermal_polarity ||
        anomaly_runtime_config_float_changed(before->scan_zone, after->scan_zone) ||
        anomaly_runtime_config_float_changed(
                before->small_target_screen_fraction,
                after->small_target_screen_fraction) ||
        before->color_frontend_mode != after->color_frontend_mode ||
        before->target_color_family_mask != after->target_color_family_mask) {
        anomaly_runtime_config_raise_transition(
                &transition,
                ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE);
    }

    return transition;
}

static inline int anomaly_runtime_normalize_movement_estimator_mode(
        const anomaly_config_t *cfg) {
    if (cfg == NULL) return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    }
    if (cfg->movement_estimator_mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW) {
        return ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_SHADOW;
    }
    return ANOMALY_MOVEMENT_ESTIMATOR_LEGACY_AFFINE;
}

static inline float anomaly_runtime_effective_color_target_span_px(
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

static inline float anomaly_runtime_effective_thermal_min_delta(
        const anomaly_config_t *cfg) {
    if (cfg == NULL || cfg->thermal_min_delta <= 0.0f) {
        return ANOMALY_THERMAL_MIN_DELTA;
    }
    return cfg->thermal_min_delta;
}

static inline int anomaly_runtime_effective_small_target_sample_step_cap(
        const anomaly_config_t *cfg,
        int                     width,
        int                     height) {
    float small_target_span_px = effective_thermal_small_target_span_px(cfg, width, height);
    int cap = (int)floorf(small_target_span_px * 0.5f);
    if (cap < 1) cap = 1;
    if (cap > 8) cap = 8;
    return cap;
}

static inline int anomaly_runtime_effective_sample_step(
        const anomaly_config_t *cfg,
        int                     width,
        int                     height) {
    int max_step = anomaly_runtime_effective_small_target_sample_step_cap(cfg, width, height);
    const size_t max_sampled_cells = 1500000u;
    int min_memory_step = 1;
    if (width > 0 && height > 0) {
        float zone = cfg != NULL ? clampf(cfg->scan_zone, 0.5f, 1.0f) : 1.0f;
        float zone_cells = (float)width * (float)height * zone * zone;
        if (zone_cells > (float)max_sampled_cells) {
            min_memory_step = (int)ceilf(sqrtf(zone_cells / (float)max_sampled_cells));
            if (min_memory_step < 1) min_memory_step = 1;
        }
    }
    if (max_step < min_memory_step) max_step = min_memory_step;
    if (cfg != NULL && cfg->pixel_step > 0) {
        return clamp_i32(cfg->pixel_step, min_memory_step, max_step);
    }
    int sample_step = (width >= 1280 || height >= 720) ? 4 : 2;
    if (sample_step < 2) sample_step = 2;
    if (max_step < 2) max_step = 2;
    if (sample_step < min_memory_step) sample_step = min_memory_step;
    if (sample_step > max_step) sample_step = max_step;
    if (sample_step < 1) sample_step = 1;
    return sample_step;
}

static inline int anomaly_runtime_effective_motion_sample_step(
        const anomaly_config_t *cfg,
        int                     width,
        int                     height) {
    int sample_step = anomaly_runtime_effective_sample_step(cfg, width, height);
    // Motion tracks compact moving blobs, not every pixel-scale shimmer.
    int min_motion_step = (width >= 1280 || height >= 720) ? 3 : 2;
    if (sample_step < min_motion_step) sample_step = min_motion_step;
    return sample_step;
}

static inline float anomaly_runtime_effective_motion_evidence_scale(
        const anomaly_config_t *cfg) {
    if (cfg == NULL) return 1.0f;
    float scale = cfg->motion_evidence_scale;
    if (!isfinite(scale)) return 1.0f;
    if (scale < 0.10f) return 0.10f;
    if (scale > 4.00f) return 4.00f;
    return scale;
}

#endif // ANOMALY_RUNTIME_CONFIG_H

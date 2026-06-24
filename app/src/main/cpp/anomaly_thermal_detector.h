// Internal pure Thermal/IR helpers.
#pragma once

#include "anomaly_analysis_internal.h"

#include <math.h>
#include <stdbool.h>
#include <stddef.h>

#define ANOMALY_THERMAL_DETECTOR_WIN_RADIUS ANOMALY_THERMAL_WIN_RADIUS
#define ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP 4
#define ANOMALY_THERMAL_DETECTOR_GROWTH_MAX_RADIUS 12
#define ANOMALY_THERMAL_DETECTOR_SMALL_TARGET_SCREEN_FRACTION \
    ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT
#define ANOMALY_THERMAL_DETECTOR_BROAD_CONTEXT_RADIUS 8
#define ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_X 0.633f
#define ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_Y 0.551f
#define ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_RADIUS 0.050f

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
        ANOMALY_THERMAL_DETECTOR_WIN_RADIUS *
        ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(target_radius_px, sample_step, 1, 0);
}

static inline int effective_thermal_representative_radius_cells(int sample_step) {
    int target_radius_px = 3 * ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        3,
        ANOMALY_THERMAL_DETECTOR_GROWTH_MAX_RADIUS);
}

static inline int effective_thermal_growth_radius_cells(int sample_step) {
    int target_radius_px = 3 * ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        2,
        ANOMALY_THERMAL_DETECTOR_GROWTH_MAX_RADIUS);
}

static inline int effective_thermal_context_radius_cells(int sample_step) {
    int target_radius_px =
        ANOMALY_THERMAL_DETECTOR_BROAD_CONTEXT_RADIUS *
        ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        6,
        0);
}

static inline int effective_thermal_parent_mass_radius_cells(int sample_step) {
    int target_radius_px =
        (ANOMALY_THERMAL_DETECTOR_BROAD_CONTEXT_RADIUS + 2) *
        ANOMALY_THERMAL_DETECTOR_NORMALIZATION_REFERENCE_STEP;
    return thermal_radius_cells_for_real_px(
        target_radius_px,
        sample_step,
        6,
        0);
}

static inline bool anomaly_thermal_support_map_required(int algorithm_mask) {
    bool thermal_enabled = (algorithm_mask & ANOMALY_ALGO_THERMAL) != 0;
    bool persist_enabled = (algorithm_mask & ANOMALY_ALGO_PERSIST) != 0;
    bool motion_enabled =
        (algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) != 0;
    bool color_enabled = (algorithm_mask & ANOMALY_ALGO_COLOR) != 0;
    return thermal_enabled || persist_enabled || (motion_enabled && !color_enabled);
}

static inline bool anomaly_thermal_spatial_scores_required(
        bool detection_active,
        int  algorithm_mask,
        bool bg_valid) {
    if (!detection_active) return false;
    if ((algorithm_mask & ANOMALY_ALGO_THERMAL) != 0) {
        return !bg_valid;
    }
    if ((algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        return true;
    }
    bool motion_enabled =
        (algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) != 0;
    bool color_enabled = (algorithm_mask & ANOMALY_ALGO_COLOR) != 0;
    return motion_enabled && !color_enabled;
}

static inline bool anomaly_sampled_grid_integral_images_required(
        bool detection_active,
        int  algorithm_mask,
        bool bg_valid,
        bool selective_refresh_active) {
    if (anomaly_thermal_spatial_scores_required(detection_active, algorithm_mask, bg_valid)) {
        return true;
    }
    return detection_active &&
           selective_refresh_active &&
           (algorithm_mask & ANOMALY_ALGO_THERMAL) != 0;
}

static inline float effective_thermal_small_target_span_px(
        const anomaly_config_t *cfg,
        int                     frame_w,
        int                     frame_h) {
    float fw = (float)(frame_w > 0 ? frame_w : 1);
    float fh = (float)(frame_h > 0 ? frame_h : 1);
    float diagonal_px = sqrtf(fw * fw + fh * fh);
    float fraction = ANOMALY_THERMAL_DETECTOR_SMALL_TARGET_SCREEN_FRACTION;
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

static inline bool anomaly_thermal_candidate_near_reviewed_fp_cluster(float cx_norm, float cy_norm) {
    float dx = cx_norm - ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_X;
    float dy = cy_norm - ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_Y;
    float dist = sqrtf(dx * dx + dy * dy);
    return dist <= ANOMALY_THERMAL_PROVISIONAL_FP_CLUSTER_RADIUS + 0.000001f;
}

static inline bool anomaly_thermal_provisional_candidate_is_weak_parallax_singleton(
        float area,
        float span,
        float final_score,
        float threshold,
        bool  movement_tile_valid,
        bool  movement_parallax,
        bool  movement_independent,
        float movement_confidence) {
    if (area > 1.0f || span > 1.0f) return false;
    if (!movement_tile_valid || !movement_parallax || movement_independent) return false;
    if (!isfinite(final_score) || !isfinite(threshold) || !isfinite(movement_confidence)) {
        return false;
    }
    if (movement_confidence < 0.80f) return false;
    return final_score < threshold + 0.35f;
}

typedef struct anomaly_thermal_temporal_stats_t {
    bool  valid;
    float delta_mean;
    float delta_norm;
    float frame_blob_contrast_mean;
    float frame_blob_contrast_std;
    int   positive_delta_count;
} anomaly_thermal_temporal_stats_t;

typedef struct anomaly_thermal_shadow_shape_t {
    bool  local_peak_movement_tile_valid;
    float movement_motion_support;
    float local_peak_movement_motion_support;
    float target_spatial_score;
    float local_peak_raw_spatial_score;
    float micro_candidate_ring_hot_fraction;
    float micro_candidate_compactness;
    float micro_candidate_one_sided_support;
    float local_window_raw_delta_mean;
    int   micro_candidate_hot_count;
    int   local_window_hot_count;
    float micro_candidate_centroid_offset;
} anomaly_thermal_shadow_shape_t;

static inline float anomaly_thermal_shadow_raw_delta_rescue_score(
        float peak_delta,
        float mean_delta,
        float area,
        float span,
        float fill,
        float quality,
        float final_score,
        float threshold,
        float thermal_min_delta,
        float delta_norm,
        float movement_independent_score,
        bool  movement_independent,
        bool  near_tracked_target) {
    float raw_rank = clampf((peak_delta - thermal_min_delta) / fmaxf(delta_norm * 1.35f, 1.0f), 0.0f, 1.0f);
    float mean_rank = clampf((mean_delta - thermal_min_delta) / fmaxf(delta_norm * 1.15f, 1.0f), 0.0f, 1.0f);
    float area_rank = area <= 0.0f ? 0.0f
        : (area <= 1.0f ? 1.0f
        : (area <= 4.0f ? 0.82f
        : (area <= 6.0f ? 0.45f : 0.0f)));
    float span_rank = span <= 0.0f ? 0.0f
        : (span <= 1.0f ? 1.0f
        : (span <= 3.0f ? 0.82f
        : (span <= 5.0f ? 0.46f : 0.0f)));
    float fill_rank = clampf((fill - 0.18f) / 0.62f, 0.0f, 1.0f);
    float quality_rank = clampf(quality / 1.10f, 0.0f, 1.0f);
    float score_gap_rank = clampf((threshold - final_score) / 2.0f, 0.0f, 1.0f);
    float movement_rank = movement_independent ? fmaxf(movement_independent_score, 0.50f)
                                               : movement_independent_score * 0.35f;
    float track_rank = near_tracked_target ? 1.0f : 0.0f;
    return clampf(
        0.24f * raw_rank +
        0.10f * mean_rank +
        0.16f * area_rank +
        0.12f * span_rank +
        0.08f * fill_rank +
        0.10f * quality_rank +
        0.14f * movement_rank +
        0.10f * track_rank +
        0.06f * score_gap_rank,
        0.0f,
        1.0f);
}

static inline bool anomaly_thermal_shadow_raw_delta_rescue_eligible(
        float peak_delta,
        float area,
        float span,
        float quality,
        float final_score,
        float threshold,
        float thermal_min_delta,
        bool movement_independent) {
    return final_score < threshold &&
           peak_delta >= fmaxf(thermal_min_delta + 1.0f, thermal_min_delta * 1.25f) &&
           area > 0.0f && area <= 4.0f &&
           span > 0.0f && span <= 3.0f &&
           quality >= 0.25f &&
           movement_independent;
}

static inline anomaly_debug_movement_shadow_reject_t anomaly_thermal_shadow_movement_reject_reason(
        const anomaly_thermal_shadow_shape_t *shape,
        float                                 score_threshold) {
    if (shape == NULL) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE;
    }
    if (!shape->local_peak_movement_tile_valid) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE;
    }
    float motion_support = fmaxf(shape->movement_motion_support,
                                 shape->local_peak_movement_motion_support);
    if (motion_support < 1.0f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOTION_SUPPORT;
    }
    float spatial_score = fmaxf(shape->target_spatial_score,
                                shape->local_peak_raw_spatial_score);
    if (spatial_score < score_threshold) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_WEAK_THERMAL;
    }
    if (shape->micro_candidate_ring_hot_fraction < 0.0f ||
        shape->micro_candidate_compactness < 0.0f ||
        shape->micro_candidate_one_sided_support < 0.0f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_NO_LOCAL_SHAPE;
    }
    if (shape->micro_candidate_ring_hot_fraction > 0.25f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_RING_HOT;
    }
    if (shape->local_window_raw_delta_mean > 10.0f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_LOCAL_MEAN_HOT;
    }
    if (shape->micro_candidate_hot_count > 10 ||
        shape->local_window_hot_count > 10) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_TOO_MANY_HOT;
    }
    if (shape->micro_candidate_compactness >= 0.0f &&
        shape->micro_candidate_compactness < 0.40f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_LOW_COMPACTNESS;
    }
    if (shape->micro_candidate_one_sided_support > 0.60f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_EDGE_LIKE;
    }
    if (shape->micro_candidate_centroid_offset > 3.0f) {
        return ANOMALY_MOVEMENT_SHADOW_REJECT_CENTROID_DRIFT;
    }
    return ANOMALY_MOVEMENT_SHADOW_REJECT_NONE;
}

void compute_thermal_spatial_probe_at_sample(
        const float *sg_luma,
        int sg_w,
        int sg_h,
        int sx,
        int sy,
        int sample_step,
        bool black_hot,
        float *abs_delta_out,
        float *std_out,
        float *score_out);

anomaly_thermal_temporal_stats_t anomaly_thermal_compute_temporal_stats(
        float       *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         black_hot,
        float        thermal_min_delta,
        float        norm_floor);

void estimate_framewide_blob_contrast_stats(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float       *contrast_mean_out,
        float       *contrast_std_out);

float thermal_candidate_seed_context_scale(
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
        float        delta_norm);

float thermal_candidate_parent_mass_scale(
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
        float        delta_norm);

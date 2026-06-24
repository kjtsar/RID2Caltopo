// anomaly_analysis.c — Standalone anomaly detection for SAR drone video.
// See anomaly_analysis.h for full documentation.
#include "anomaly_appearance_candidates.h"
#include "anomaly_analysis_internal.h"
#include "anomaly_buffer.h"
#include "anomaly_color_detector.h"
#include "anomaly_debug_helpers.h"
#include "anomaly_frame_geometry.h"
#include "anomaly_frame_history.h"
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
#include "anomaly_target_matching.h"
#include "anomaly_target_observations.h"
#include "anomaly_target_revisit.h"
#include "anomaly_target_tracks.h"
#include "anomaly_thermal_detector.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>

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
#define ANOMALY_MAX_MOTION_CANDIDATES ANOMALY_APPEARANCE_MOTION_CANDIDATE_MAX
#define ANOMALY_MAX_THERMAL_CANDIDATES 8
#define ANOMALY_MAX_OVERLAY_BOXES 8
#define ANOMALY_MOTION_CANDIDATE_NMS_RADIUS ANOMALY_APPEARANCE_MOTION_CANDIDATE_NMS_RADIUS
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
#define ANOMALY_SALIENCY_SECONDARY_LOCAL_THRESHOLD_SLACK 0.40f
#define ANOMALY_SALIENCY_BOUNDARY_RADIUS_CELLS 6
#define ANOMALY_ROI_REALTIME_CARRY_EXPIRY 3
#define ANOMALY_TARGET_REVISIT_RELAXED_THRESHOLD 0.35f
#define ANOMALY_TARGET_CANDIDATE_KEEP_FRACTION 0.25f
#define ANOMALY_TARGET_CANDIDATE_KEEP_MIN 2
#define ANOMALY_TARGET_CANDIDATE_KEEP_MAX 4
#define ANOMALY_TARGET_CANDIDATE_SCORE_SLACK 0.75f
#define ANOMALY_THERMAL_SCORE_THRESHOLD_SCALE 0.62f
#define ANOMALY_COLOR_PROVISIONAL_KEEP_MAX 4
#define ANOMALY_COLOR_PROVISIONAL_SCORE_SLACK 1.05f
#define ANOMALY_MAX_COLOR_CANDIDATES 6
#define ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD 10
#define ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS 1
#define ANOMALY_COLOR_LOCAL_SUPPORT_MIN 3
#define ANOMALY_COLOR_BLOB_JOIN_BASE_THRESH 0.55f
#define ANOMALY_COLOR_BLOB_JOIN_PEAK_RATIO_SOFT 0.30f
#define ANOMALY_COLOR_BLOB_JOIN_PEAK_RATIO_HARD 0.42f
#define ANOMALY_COLOR_BLOB_COMPACT_RING_MAX 0.12f
#define ANOMALY_COLOR_BLOB_COMPACT_SUPPORT_MASS_MAX 0.60f
#define ANOMALY_COLOR_BLOB_COMPACT_NEAR_MAX 0.16f
#define ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE 49
#define ANOMALY_COLOR_DENSE_VERIFY_MIN_AREA 3
#define ANOMALY_COLOR_DENSE_VERIFY_CHROMA_THRESH 18.0f
#define ANOMALY_COLOR_DENSE_VERIFY_LUMA_THRESH 28.0f
#define ANOMALY_COLOR_DENSE_VERIFY_FILL_MIN 0.40f
#define ANOMALY_COLOR_DENSE_VERIFY_RING_MAX 0.22f
#define ANOMALY_COLOR_DENSE_VERIFY_SPAN_SCALE 1.75f
#define ANOMALY_COLOR_DENSE_INTERIM_SEED_TOP_K 6
#define ANOMALY_COLOR_COMPONENT_RESCUE_TOP_K 4
#define ANOMALY_COLOR_COMPONENT_RESCUE_MAX_CELLS 64
#define ANOMALY_FRESH_COLOR_BLOB_SEED_MIN 0.32f
#define ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_NUDGE_MAX 0.22f

anomaly_config_transition_t anomaly_config_transition_classify(
        const anomaly_config_t *before,
        const anomaly_config_t *after) {
    return anomaly_runtime_config_transition_classify(before, after);
}

static inline int popcount_u8(uint8_t value) {
    int count = 0;
    while (value != 0) {
        count += (value & 1u);
        value >>= 1u;
    }
    return count;
}

float anomaly_thermal_effective_score_threshold(float score_threshold) {
    if (!isfinite(score_threshold)) return 1.0f;
    float scaled = score_threshold * ANOMALY_THERMAL_SCORE_THRESHOLD_SCALE;
    if (scaled < 1.0f) return 1.0f;
    if (scaled > score_threshold) return score_threshold;
    return scaled;
}

static int compare_float_qsort(const void *a, const void *b) {
    float fa = *(const float *)a;
    float fb = *(const float *)b;
    if (fa < fb) return -1;
    if (fa > fb) return 1;
    return 0;
}

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
    float target_raw_delta;
    float target_raw_score;
    float target_temporal_margin;
    float target_spatial_abs_delta;
    float target_spatial_std;
    float target_spatial_score;
    bool hot_eligible;
    bool started_component;
    bool local_max;
    int local_peak_radius;
    int local_peak_sx;
    int local_peak_sy;
    float local_peak_delta;
    float local_peak_score;
    float local_peak_distance;
    int local_peak_raw_sx;
    int local_peak_raw_sy;
    float local_peak_raw_delta;
    float local_peak_raw_score;
    float local_peak_raw_distance;
    float local_peak_raw_temporal_margin;
    float local_peak_raw_spatial_abs_delta;
    float local_peak_raw_spatial_std;
    float local_peak_raw_spatial_score;
    bool local_peak_is_component_seed;
    int local_window_sample_count;
    int local_window_hot_count;
    float local_window_raw_delta_sum;
    float local_window_raw_delta_mean;
    float local_window_weighted_centroid_dx;
    float local_window_weighted_centroid_dy;
    bool micro_candidate_would_create;
    anomaly_debug_thermal_micro_reject_t micro_candidate_reject_reason;
    int micro_candidate_peak_sx;
    int micro_candidate_peak_sy;
    float micro_candidate_peak_delta;
    float micro_candidate_peak_score;
    float micro_candidate_prominence;
    float micro_candidate_ring_mean;
    float micro_candidate_ring_hot_fraction;
    int micro_candidate_hot_count;
    int micro_candidate_sample_count;
    float micro_candidate_compactness;
    float micro_candidate_centroid_dx;
    float micro_candidate_centroid_dy;
    float micro_candidate_centroid_offset;
    float micro_candidate_one_sided_support;
    float micro_candidate_distance_to_debug_target;
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
    bool nearby_rejected_component_valid;
    bool nearby_rejected_component_contains_target;
    anomaly_debug_thermal_target_gate_t nearby_rejected_component_gate;
    int nearby_rejected_component_seed_x;
    int nearby_rejected_component_seed_y;
    int nearby_rejected_component_peak_x;
    int nearby_rejected_component_peak_y;
    float nearby_rejected_component_area;
    float nearby_rejected_component_span;
    float nearby_rejected_component_fill;
    float nearby_rejected_component_peak_delta;
    float nearby_rejected_component_mean_delta;
    float nearby_rejected_component_quality;
    float nearby_rejected_component_distance;
    bool dropped_by_cap;
    bool dropped_by_nms;
    bool replaced_by_nms;
    int nms_conflict_rank;
    int nms_conflict_sample_x;
    int nms_conflict_sample_y;
    int pre_cap_rank;
    int pre_cap_candidate_count;
    int pre_cap_limit;
    float pre_cap_retention_rank;
    int extracted_rank;
    int winning_rank;
    int provisional_candidate_index;
    float provisional_score_floor;
    float provisional_final_score;
    bool provisional_score_eligible;
    bool provisional_shape_eligible;
    float provisional_candidate_rank;
    int provisional_selected_rank;
    float provisional_selected_score;
    bool provisional_near_existing_skip;
    int matched_track_index;
    int matched_track_id;
    int matched_track_hit_count;
    int matched_track_miss_count;
    int matched_track_hold_count;
    bool matched_track_publish_confirmed;
    float raw_delta_rescue_score;
    float movement_residual_px;
    float movement_independent_score;
    float movement_confidence;
    float movement_motion_support;
    int movement_layer_class;
    float local_peak_movement_residual_px;
    float local_peak_movement_independent_score;
    float local_peak_movement_confidence;
    float local_peak_movement_motion_support;
    int local_peak_movement_layer_class;
    bool raw_delta_rescue_eligible;
    bool movement_tile_valid;
    bool movement_independent;
    bool movement_parallax;
    bool would_promote_movement_rescue;
    bool local_peak_movement_tile_valid;
    bool local_peak_movement_independent;
    bool local_peak_movement_parallax;
    bool movement_shadow_motion_support;
    bool movement_shadow_parallax_penalty;
    bool movement_shadow_thermal_support;
    bool movement_shadow_clutter_veto;
    bool movement_rescue_would_publish;
    bool movement_boost_would_publish;
    anomaly_debug_movement_shadow_reject_t movement_rescue_reject_reason;
    anomaly_debug_thermal_target_stage_t stage;
} anomaly_thermal_target_trace_t;

static void evaluate_thermal_micro_candidate_shadow(
        const float *thermal_score_map,
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int sg_w,
        int sg_h,
        bool bg_valid,
        bool black_hot,
        float thermal_min_delta,
        const float *thermal_value_map,
        anomaly_thermal_target_trace_t *target_trace);

static int find_target_blob_rank(
        const anomaly_thermal_blob_candidate_t *top,
        int top_count,
        const anomaly_thermal_target_trace_t *target_trace) {
    if (top == NULL || top_count <= 0 || target_trace == NULL) return -1;
    return anomaly_appearance_find_thermal_blob_candidate_rank(
            top,
            top_count,
            target_trace->component_peak_x,
            target_trace->component_peak_y);
}

static void record_thermal_target_pre_cap_rank(
        anomaly_thermal_target_trace_t *target_trace,
        const anomaly_thermal_blob_candidate_t *candidate,
        int candidate_count,
        int rank) {
    if (target_trace == NULL || candidate == NULL) return;
    target_trace->pre_cap_rank = rank;
    target_trace->pre_cap_candidate_count = candidate_count;
    target_trace->pre_cap_limit = ANOMALY_MAX_THERMAL_CANDIDATES;
    target_trace->pre_cap_retention_rank = candidate->retention_rank_valid
        ? candidate->retention_rank
        : -1.0f;
}

static void insert_thermal_blob_candidate(
        anomaly_thermal_blob_candidate_t *top,
        int *top_count,
        const anomaly_thermal_blob_candidate_t *candidate,
        anomaly_thermal_target_trace_t *target_trace,
        bool candidate_is_target) {
    if (top == NULL || top_count == NULL || candidate == NULL) return;
    int target_rank_before = find_target_blob_rank(top, *top_count, target_trace);
    anomaly_thermal_blob_insert_report_t report;
    anomaly_appearance_insert_thermal_blob_candidate(
            top,
            top_count,
            candidate,
            ANOMALY_MAX_THERMAL_CANDIDATES,
            ANOMALY_MOTION_CANDIDATE_NMS_RADIUS,
            target_rank_before,
            candidate_is_target,
            &report);
    if (!report.valid || target_trace == NULL) return;

    if (target_trace != NULL && candidate_is_target) {
        record_thermal_target_pre_cap_rank(
                target_trace,
                candidate,
                report.candidate_count_before,
                report.pre_cap_rank);
    }

    if (report.replaced_existing_by_nms &&
        target_rank_before == report.nms_conflict_rank &&
        !candidate_is_target) {
        target_trace->dropped_by_nms = true;
        target_trace->replaced_by_nms = true;
        target_trace->nms_conflict_rank = report.nms_conflict_rank;
        target_trace->nms_conflict_sample_x = report.nms_conflict_sample_x;
        target_trace->nms_conflict_sample_y = report.nms_conflict_sample_y;
    }
    if (candidate_is_target && report.nms_conflict_rank >= 0) {
        target_trace->nms_conflict_rank = report.nms_conflict_rank;
        target_trace->nms_conflict_sample_x = report.nms_conflict_sample_x;
        target_trace->nms_conflict_sample_y = report.nms_conflict_sample_y;
    }
    if (candidate_is_target && report.rejected_by_nms) {
        target_trace->dropped_by_nms = true;
    }
    if (candidate_is_target && report.rejected_by_cap) {
        target_trace->dropped_by_cap = true;
    }
    if (report.target_tail_dropped_by_cap) {
        target_trace->dropped_by_cap = true;
    }
}

static void record_thermal_target_rejected_component_probe(
        anomaly_thermal_target_trace_t *target_trace,
        anomaly_debug_thermal_target_gate_t gate,
        int seed_x,
        int seed_y,
        int peak_x,
        int peak_y,
        int min_x,
        int min_y,
        int max_x,
        int max_y,
        int area,
        float span,
        float fill,
        float peak_delta,
        float mean_delta,
        float quality) {
    if (target_trace == NULL || !target_trace->enabled || !target_trace->valid) return;

    bool contains_target =
        target_trace->target_sx >= min_x &&
        target_trace->target_sx <= max_x &&
        target_trace->target_sy >= min_y &&
        target_trace->target_sy <= max_y;
    int dx = 0;
    if (target_trace->target_sx < min_x) dx = min_x - target_trace->target_sx;
    else if (target_trace->target_sx > max_x) dx = target_trace->target_sx - max_x;
    int dy = 0;
    if (target_trace->target_sy < min_y) dy = min_y - target_trace->target_sy;
    else if (target_trace->target_sy > max_y) dy = target_trace->target_sy - max_y;
    float distance = sqrtf((float)(dx * dx + dy * dy));
    bool near_target = contains_target || distance <= 3.0f;
    if (!near_target) return;
    if (target_trace->nearby_rejected_component_valid &&
        !contains_target &&
        target_trace->nearby_rejected_component_contains_target) {
        return;
    }
    if (target_trace->nearby_rejected_component_valid &&
        contains_target == target_trace->nearby_rejected_component_contains_target &&
        distance >= target_trace->nearby_rejected_component_distance) {
        return;
    }

    target_trace->nearby_rejected_component_valid = true;
    target_trace->nearby_rejected_component_contains_target = contains_target;
    target_trace->nearby_rejected_component_gate = gate;
    target_trace->nearby_rejected_component_seed_x = seed_x;
    target_trace->nearby_rejected_component_seed_y = seed_y;
    target_trace->nearby_rejected_component_peak_x = peak_x;
    target_trace->nearby_rejected_component_peak_y = peak_y;
    target_trace->nearby_rejected_component_area = (float)area;
    target_trace->nearby_rejected_component_span = span;
    target_trace->nearby_rejected_component_fill = fill;
    target_trace->nearby_rejected_component_peak_delta = peak_delta;
    target_trace->nearby_rejected_component_mean_delta = mean_delta;
    target_trace->nearby_rejected_component_quality = quality;
    target_trace->nearby_rejected_component_distance = distance;
}

typedef struct {
    bool  enabled;
    bool  valid;
    int   target_sx;
    int   target_sy;
    int   component_seed_x;
    int   component_seed_y;
    int   component_peak_x;
    int   component_peak_y;
    int   min_x;
    int   min_y;
    int   max_x;
    int   max_y;
    float component_area;
    float component_span;
    float component_fill;
    float component_peak_support;
    float component_mean_support;
    float component_quality;
    float component_ring_fraction;
    float component_support_mass;
    bool  component_rejected;
    int   component_rejection_reason;
    bool  dropped_by_cap;
    bool  dropped_by_nms;
    bool  replaced_by_nms;
    int   nms_conflict_rank;
    int   nms_conflict_sample_x;
    int   nms_conflict_sample_y;
    int   pre_cap_rank;
    int   pre_cap_candidate_count;
    int   pre_cap_limit;
    float pre_cap_retention_rank;
} anomaly_color_blob_target_trace_t;

static int find_color_target_blob_rank(
        const anomaly_color_blob_candidate_t *top,
        int top_count,
        const anomaly_color_blob_target_trace_t *target_trace) {
    if (top == NULL || top_count <= 0 || target_trace == NULL) return -1;
    return anomaly_appearance_find_color_blob_candidate_rank(
            top,
            top_count,
            target_trace->component_peak_x,
            target_trace->component_peak_y);
}

static void record_color_target_pre_cap_rank(
        anomaly_color_blob_target_trace_t *target_trace,
        const anomaly_color_blob_candidate_t *candidate,
        int candidate_count,
        int rank) {
    if (target_trace == NULL || candidate == NULL) return;
    target_trace->pre_cap_rank = rank;
    target_trace->pre_cap_candidate_count = candidate_count;
    target_trace->pre_cap_limit = ANOMALY_MAX_COLOR_CANDIDATES;
    target_trace->pre_cap_retention_rank = candidate->retention_rank_valid
        ? candidate->retention_rank
        : -1.0f;
}

typedef struct {
    bool  valid;
    int   area_px;
    int   min_x;
    int   min_y;
    int   max_x;
    int   max_y;
    float centroid_x;
    float centroid_y;
    float fill;
    float span_px;
    float ring_fraction;
    float support_mass;
    float uniqueness;
} anomaly_dense_color_component_t;

static void insert_color_blob_candidate(
        anomaly_color_blob_candidate_t *top,
        int                            *top_count,
        const anomaly_color_blob_candidate_t *candidate,
        anomaly_color_blob_target_trace_t *target_trace,
        bool candidate_is_target);

static bool color_component_near_predicted_color_target(
        const anomaly_state_t *state,
        int                    roi_x0,
        int                    roi_y0,
        int                    sample_step,
        int                    frame_w,
        int                    frame_h,
        int                    min_x,
        int                    min_y,
        int                    max_x,
        int                    max_y,
        int                    peak_x,
        int                    peak_y,
        int                    compact_target_span_limit) {
    if (state == NULL || frame_w <= 1 || frame_h <= 1 || max_x < min_x || max_y < min_y) {
        return false;
    }
    int step = sample_step > 0 ? sample_step : 1;
    int target_gate_cells = compact_target_span_limit + 2;
    if (target_gate_cells < 4) target_gate_cells = 4;
    if (state->acc_active[0] && state->acc_hits[0] >= 2 && state->acc_hold[0] > 0) {
        int acc_px = clamp_i32((int)lroundf(state->acc_cx[0] * (float)(frame_w - 1)), 0, frame_w - 1);
        int acc_py = clamp_i32((int)lroundf(state->acc_cy[0] * (float)(frame_h - 1)), 0, frame_h - 1);
        int acc_sx = (acc_px - roi_x0 + (step / 2)) / step;
        int acc_sy = (acc_py - roi_y0 + (step / 2)) / step;
        int dx = 0;
        if (acc_sx < min_x) dx = min_x - acc_sx;
        else if (acc_sx > max_x) dx = acc_sx - max_x;
        int dy = 0;
        if (acc_sy < min_y) dy = min_y - acc_sy;
        else if (acc_sy > max_y) dy = acc_sy - max_y;
        int chebyshev = dx > dy ? dx : dy;
        int acc_gate = target_gate_cells + 3;
        if (chebyshev <= acc_gate) return true;

        int peak_dx = abs(acc_sx - peak_x);
        int peak_dy = abs(acc_sy - peak_y);
        int peak_dist = peak_dx > peak_dy ? peak_dx : peak_dy;
        if (peak_dist <= acc_gate + 1) return true;
    }
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || track->algorithm != ANOMALY_ALGO_COLOR) continue;
        if (!track->publish_confirmed && track->hit_count < 2) continue;
        if (track->confidence < 0.22f) continue;
        int track_px = clamp_i32((int)lroundf(track->center_x_norm * (float)(frame_w - 1)), 0, frame_w - 1);
        int track_py = clamp_i32((int)lroundf(track->center_y_norm * (float)(frame_h - 1)), 0, frame_h - 1);
        int track_sx = (track_px - roi_x0 + (step / 2)) / step;
        int track_sy = (track_py - roi_y0 + (step / 2)) / step;
        int dx = 0;
        if (track_sx < min_x) dx = min_x - track_sx;
        else if (track_sx > max_x) dx = track_sx - max_x;
        int dy = 0;
        if (track_sy < min_y) dy = min_y - track_sy;
        else if (track_sy > max_y) dy = track_sy - max_y;
        int chebyshev = dx > dy ? dx : dy;
        float support_gate_px = fmaxf(track->support_radius_norm, 0.0f) *
                                (float)(frame_w > frame_h ? frame_w : frame_h);
        int support_gate_cells = (int)ceilf(support_gate_px / (float)step) + 1;
        int gate = target_gate_cells > support_gate_cells ? target_gate_cells : support_gate_cells;
        if (chebyshev <= gate) return true;

        int peak_dx = abs(track_sx - peak_x);
        int peak_dy = abs(track_sy - peak_y);
        int peak_dist = peak_dx > peak_dy ? peak_dx : peak_dy;
        if (peak_dist <= gate + 1) return true;
    }
    return false;
}

static bool fresh_color_blob_is_too_common_for_dense_verify(
        int                        color_frontend_mode,
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        peak_x,
        int                        peak_y,
        bool                       target_centered_rescue);

static void record_color_target_trace_component(
        anomaly_color_blob_target_trace_t      *target_trace,
        int                                     sx,
        int                                     sy,
        int                                     peak_x,
        int                                     peak_y,
        int                                     min_x,
        int                                     min_y,
        int                                     max_x,
        int                                     max_y,
        int                                     area,
        double                                  sum_support,
        float                                   peak_support,
        const anomaly_color_blob_candidate_t   *candidate,
        bool                                    accepted,
        int                                     reject_reason,
        float                                   reject_span,
        float                                   reject_quality,
        float                                   reject_ring_fraction,
        float                                   reject_support_mass);

static float dense_color_seed_pixel_rank(
        const uint8_t *rgba,
        int            rgba_stride,
        int            frame_w,
        int            frame_h,
        int            px,
        int            py,
        int            radius) {
    float center_luma = 0.0f;
    float center_u = 0.0f;
    float center_v = 0.0f;
    anomaly_color_sample_pixel_yuv(
        rgba,
        rgba_stride,
        frame_w,
        frame_h,
        px,
        py,
        &center_luma,
        &center_u,
        &center_v);
    float center_chroma = sqrtf(center_u * center_u + center_v * center_v);
    double ring_chroma_delta = 0.0;
    double ring_luma_delta = 0.0;
    int ring_count = 0;
    int inner_radius = radius > 1 ? radius / 2 : 1;
    for (int y = py - radius; y <= py + radius; y++) {
        if (y < 0 || y >= frame_h) continue;
        for (int x = px - radius; x <= px + radius; x++) {
            if (x < 0 || x >= frame_w) continue;
            int dx = abs(x - px);
            int dy = abs(y - py);
            int cheb = dx > dy ? dx : dy;
            if (cheb <= inner_radius || cheb > radius) continue;
            float luma = 0.0f;
            float u = 0.0f;
            float v = 0.0f;
            anomaly_color_sample_pixel_yuv(
                rgba,
                rgba_stride,
                frame_w,
                frame_h,
                x,
                y,
                &luma,
                &u,
                &v);
            float du = center_u - u;
            float dv = center_v - v;
            ring_chroma_delta += (double)sqrtf(du * du + dv * dv);
            ring_luma_delta += (double)fabsf(center_luma - luma);
            ring_count++;
        }
    }
    float mean_chroma_delta = ring_count > 0
        ? (float)(ring_chroma_delta / (double)ring_count)
        : 0.0f;
    float mean_luma_delta = ring_count > 0
        ? (float)(ring_luma_delta / (double)ring_count)
        : 0.0f;
    return center_chroma * 0.025f + mean_chroma_delta * 0.070f + mean_luma_delta * 0.020f;
}

static void refine_dense_color_seed_pixel(
        const uint8_t *rgba,
        int            rgba_stride,
        int            frame_w,
        int            frame_h,
        int            roi_x0,
        int            roi_y0,
        int            roi_x1,
        int            roi_y1,
        int            sample_step,
        int           *seed_px_inout,
        int           *seed_py_inout) {
    if (rgba == NULL || seed_px_inout == NULL || seed_py_inout == NULL ||
        sample_step <= 1 || frame_w <= 0 || frame_h <= 0) {
        return;
    }
    int seed_px = *seed_px_inout;
    int seed_py = *seed_py_inout;
    int search_radius = clamp_i32(sample_step, 2, 5);
    int rank_radius = clamp_i32(sample_step, 2, 4);
    int x0 = seed_px - search_radius;
    int y0 = seed_py - search_radius;
    int x1 = seed_px + search_radius;
    int y1 = seed_py + search_radius;
    if (x0 < roi_x0) x0 = roi_x0;
    if (y0 < roi_y0) y0 = roi_y0;
    if (x1 >= roi_x1) x1 = roi_x1 - 1;
    if (y1 >= roi_y1) y1 = roi_y1 - 1;
    float best_rank = -1.0f;
    int best_x = seed_px;
    int best_y = seed_py;
    for (int py = y0; py <= y1; py++) {
        for (int px = x0; px <= x1; px++) {
            float rank = dense_color_seed_pixel_rank(
                rgba,
                rgba_stride,
                frame_w,
                frame_h,
                px,
                py,
                rank_radius);
            if (rank > best_rank) {
                best_rank = rank;
                best_x = px;
                best_y = py;
            }
        }
    }
    *seed_px_inout = best_x;
    *seed_py_inout = best_y;
}

static bool verify_dense_color_component(
        const uint8_t                    *rgba,
        int                               rgba_stride,
        int                               frame_w,
        int                               frame_h,
        int                               roi_x0,
        int                               roi_y0,
        int                               roi_x1,
        int                               roi_y1,
        int                               sample_step,
        int                               coarse_min_x,
        int                               coarse_min_y,
        int                               coarse_max_x,
        int                               coarse_max_y,
        int                               peak_x,
        int                               peak_y,
        float                             target_span_px,
        bool                              refine_seed_pixel,
        anomaly_dense_color_component_t  *dense_out,
        int                              *reject_reason_out,
        float                            *reject_area_out,
        float                            *reject_span_out,
        float                            *reject_ring_fraction_out,
        float                            *reject_support_mass_out,
        float                            *reject_quality_out) {
    if (dense_out != NULL) memset(dense_out, 0, sizeof(*dense_out));
    if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_NONE;
    if (reject_area_out != NULL) *reject_area_out = 0.0f;
    if (reject_span_out != NULL) *reject_span_out = 0.0f;
    if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = 0.0f;
    if (reject_support_mass_out != NULL) *reject_support_mass_out = 0.0f;
    if (reject_quality_out != NULL) *reject_quality_out = 0.0f;
    if (rgba == NULL || dense_out == NULL || frame_w <= 0 || frame_h <= 0 ||
        roi_x1 <= roi_x0 || roi_y1 <= roi_y0 || coarse_max_x < coarse_min_x ||
        coarse_max_y < coarse_min_y) {
        return false;
    }

    const int max_side = ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE;
    int step = sample_step > 0 ? sample_step : 1;
    int seed_px = roi_x0 + peak_x * step + (step / 2);
    int seed_py = roi_y0 + peak_y * step + (step / 2);
    if (seed_px >= roi_x1) seed_px = roi_x1 - 1;
    if (seed_py >= roi_y1) seed_py = roi_y1 - 1;
    if (refine_seed_pixel) {
        refine_dense_color_seed_pixel(
            rgba,
            rgba_stride,
            frame_w,
            frame_h,
            roi_x0,
            roi_y0,
            roi_x1,
            roi_y1,
            sample_step,
            &seed_px,
            &seed_py);
    }

    int coarse_min_px = roi_x0 + coarse_min_x * step;
    int coarse_min_py = roi_y0 + coarse_min_y * step;
    int coarse_max_px = roi_x0 + (coarse_max_x + 1) * step - 1;
    int coarse_max_py = roi_y0 + (coarse_max_y + 1) * step - 1;
    int half_side = clamp_i32((int)ceilf(target_span_px * 1.15f), 4, (max_side - 1) / 2);
    int x0 = seed_px - half_side;
    int y0 = seed_py - half_side;
    int x1 = seed_px + half_side;
    int y1 = seed_py + half_side;
    if (coarse_min_px - step < x0) x0 = coarse_min_px - step;
    if (coarse_min_py - step < y0) y0 = coarse_min_py - step;
    if (coarse_max_px + step > x1) x1 = coarse_max_px + step;
    if (coarse_max_py + step > y1) y1 = coarse_max_py + step;
    if (x0 < roi_x0) x0 = roi_x0;
    if (y0 < roi_y0) y0 = roi_y0;
    if (x1 >= roi_x1) x1 = roi_x1 - 1;
    if (y1 >= roi_y1) y1 = roi_y1 - 1;
    if (x1 - x0 + 1 > max_side) {
        int center = seed_px;
        x0 = center - (max_side / 2);
        x1 = x0 + max_side - 1;
        if (x0 < roi_x0) {
            x0 = roi_x0;
            x1 = x0 + max_side - 1;
        }
        if (x1 >= roi_x1) {
            x1 = roi_x1 - 1;
            x0 = x1 - max_side + 1;
        }
    }
    if (y1 - y0 + 1 > max_side) {
        int center = seed_py;
        y0 = center - (max_side / 2);
        y1 = y0 + max_side - 1;
        if (y0 < roi_y0) {
            y0 = roi_y0;
            y1 = y0 + max_side - 1;
        }
        if (y1 >= roi_y1) {
            y1 = roi_y1 - 1;
            y0 = y1 - max_side + 1;
        }
    }

    int win_w = x1 - x0 + 1;
    int win_h = y1 - y0 + 1;
    if (win_w <= 0 || win_h <= 0 || win_w > max_side || win_h > max_side) {
        return false;
    }

    float luma_buf[ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE *
                   ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE];
    float u_buf[ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE *
                ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE];
    float v_buf[ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE *
                ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE];
    uint8_t visited[ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE *
                    ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE];
    int queue[ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE *
              ANOMALY_COLOR_DENSE_VERIFY_MAX_WINDOW_SIDE];
    memset(visited, 0, sizeof(visited));

    for (int wy = 0; wy < win_h; wy++) {
        for (int wx = 0; wx < win_w; wx++) {
            int local_idx = wy * win_w + wx;
            anomaly_color_sample_pixel_yuv(
                rgba,
                rgba_stride,
                frame_w,
                frame_h,
                x0 + wx,
                y0 + wy,
                &luma_buf[local_idx],
                &u_buf[local_idx],
                &v_buf[local_idx]);
        }
    }

    int seed_local_x = seed_px - x0;
    int seed_local_y = seed_py - y0;
    int seed_local_idx = seed_local_y * win_w + seed_local_x;
    float seed_luma = luma_buf[seed_local_idx];
    float seed_u = u_buf[seed_local_idx];
    float seed_v = v_buf[seed_local_idx];

    int head = 0;
    int tail = 0;
    queue[tail++] = seed_local_idx;
    visited[seed_local_idx] = 1u;

    int area_px = 0;
    int min_px = seed_px;
    int min_py = seed_py;
    int max_px = seed_px;
    int max_py = seed_py;
    double sum_x = 0.0;
    double sum_y = 0.0;
    double sum_u = 0.0;
    double sum_v = 0.0;
    double sum_luma = 0.0;

    while (head < tail) {
        int cur = queue[head++];
        int cx = cur % win_w;
        int cy = cur / win_w;
        int px = x0 + cx;
        int py = y0 + cy;
        float cur_luma = luma_buf[cur];
        float cur_u = u_buf[cur];
        float cur_v = v_buf[cur];
        if (!anomaly_color_dense_pixel_matches(
                seed_u,
                seed_v,
                seed_luma,
                cur_u,
                cur_v,
                cur_luma,
                ANOMALY_COLOR_DENSE_VERIFY_CHROMA_THRESH,
                ANOMALY_COLOR_DENSE_VERIFY_LUMA_THRESH)) {
            continue;
        }
        area_px++;
        sum_x += (double)px;
        sum_y += (double)py;
        sum_u += (double)cur_u;
        sum_v += (double)cur_v;
        sum_luma += (double)cur_luma;
        if (px < min_px) min_px = px;
        if (px > max_px) max_px = px;
        if (py < min_py) min_py = py;
        if (py > max_py) max_py = py;

        static const int kOffsets[8][2] = {
            { 1,  0}, {-1,  0}, { 0,  1}, { 0, -1},
            { 1,  1}, { 1, -1}, {-1,  1}, {-1, -1},
        };
        for (int oi = 0; oi < 8; oi++) {
            int nx = cx + kOffsets[oi][0];
            int ny = cy + kOffsets[oi][1];
            if (nx < 0 || ny < 0 || nx >= win_w || ny >= win_h) continue;
            int nidx = ny * win_w + nx;
            if (visited[nidx] != 0u) continue;
            visited[nidx] = 1u;
            queue[tail++] = nidx;
        }
    }

    if (area_px < ANOMALY_COLOR_DENSE_VERIFY_MIN_AREA) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_QUALITY;
        if (reject_area_out != NULL) *reject_area_out = (float)area_px / (float)(step * step);
        return false;
    }

    int bbox_w = max_px - min_px + 1;
    int bbox_h = max_py - min_py + 1;
    int bbox_area = bbox_w * bbox_h;
    float fill = bbox_area > 0 ? ((float)area_px / (float)bbox_area) : 0.0f;
    float span_px = (float)(bbox_w > bbox_h ? bbox_w : bbox_h);
    float centroid_x = (float)(sum_x / (double)area_px);
    float centroid_y = (float)(sum_y / (double)area_px);
    float mean_u = (float)(sum_u / (double)area_px);
    float mean_v = (float)(sum_v / (double)area_px);
    float mean_luma = (float)(sum_luma / (double)area_px);

    float max_allowed_span_px = fmaxf(10.0f, target_span_px * ANOMALY_COLOR_DENSE_VERIFY_SPAN_SCALE);
    float dense_area_cells = (float)area_px / (float)(step * step);
    if (span_px > max_allowed_span_px) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_AREA;
        if (reject_area_out != NULL) *reject_area_out = dense_area_cells;
        if (reject_span_out != NULL) *reject_span_out = span_px / (float)step;
        return false;
    }
    if (fill < ANOMALY_COLOR_DENSE_VERIFY_FILL_MIN) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_QUALITY;
        if (reject_area_out != NULL) *reject_area_out = dense_area_cells;
        if (reject_quality_out != NULL) *reject_quality_out = fill;
        return false;
    }

    int ring_total = 0;
    int ring_similar = 0;
    for (int py = min_py - 1; py <= max_py + 1; py++) {
        if (py < y0 || py > y1) continue;
        for (int px = min_px - 1; px <= max_px + 1; px++) {
            if (px < x0 || px > x1) continue;
            bool inside = px >= min_px && px <= max_px && py >= min_py && py <= max_py;
            if (inside) continue;
            int idx = (py - y0) * win_w + (px - x0);
            ring_total++;
            if (anomaly_color_dense_pixel_matches(
                    mean_u,
                    mean_v,
                    mean_luma,
                    u_buf[idx],
                    v_buf[idx],
                    luma_buf[idx],
                    ANOMALY_COLOR_DENSE_VERIFY_CHROMA_THRESH * 0.90f,
                    ANOMALY_COLOR_DENSE_VERIFY_LUMA_THRESH)) {
                ring_similar++;
            }
        }
    }
    float ring_fraction = ring_total > 0 ? ((float)ring_similar / (float)ring_total) : 0.0f;
    float support_mass = (float)ring_similar / (float)area_px;
    if (ring_fraction > ANOMALY_COLOR_DENSE_VERIFY_RING_MAX) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_RING;
        if (reject_area_out != NULL) *reject_area_out = dense_area_cells;
        if (reject_span_out != NULL) *reject_span_out = span_px / (float)step;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        return false;
    }

    dense_out->valid = true;
    dense_out->area_px = area_px;
    dense_out->min_x = min_px;
    dense_out->min_y = min_py;
    dense_out->max_x = max_px;
    dense_out->max_y = max_py;
    dense_out->centroid_x = centroid_x;
    dense_out->centroid_y = centroid_y;
    dense_out->fill = fill;
    dense_out->span_px = span_px;
    dense_out->ring_fraction = ring_fraction;
    dense_out->support_mass = support_mass;
    dense_out->uniqueness = clampf((1.0f - ring_fraction) * fill, 0.0f, 1.0f);
    return true;
}

static bool build_color_blob_candidate(
        const anomaly_config_t *cfg,
        const anomaly_roi_state_t *roi_state,
        const uint8_t *rgba,
        int rgba_stride,
        int color_frontend_mode,
        const float *color_support_map,
        const float *contrast_map,
        int sg_w,
        int sg_h,
        int frame_w,
        int frame_h,
        int roi_x0,
        int roi_y0,
        int roi_x1,
        int roi_y1,
        int sample_step,
        int area,
        int min_x,
        int min_y,
        int max_x,
        int max_y,
        double sum_support,
        float peak_support,
        int peak_x,
        int peak_y,
        float target_span_px,
        float small_target_limit_px,
        int target_span_cells,
        int max_blob_area,
        int compact_target_span_limit,
        anomaly_color_blob_candidate_t *candidate_out,
        int *reject_reason_out,
        float *reject_area_out,
        float *reject_span_out,
        float *reject_ring_fraction_out,
        float *reject_support_mass_out,
        float *reject_quality_out,
        bool allow_target_centered_area_rescue) {
    if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_NONE;
    if (reject_area_out != NULL) *reject_area_out = 0.0f;
    if (reject_span_out != NULL) *reject_span_out = 0.0f;
    if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = 0.0f;
    if (reject_support_mass_out != NULL) *reject_support_mass_out = 0.0f;
    if (reject_quality_out != NULL) *reject_quality_out = 0.0f;
    if (candidate_out != NULL) memset(candidate_out, 0, sizeof(*candidate_out));
    if (cfg == NULL || color_support_map == NULL || candidate_out == NULL ||
        sg_w <= 0 || sg_h <= 0 || area <= 0 || max_x < min_x || max_y < min_y) {
        return false;
    }
    int step = sample_step > 0 ? sample_step : 1;

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
    bool compact_target_blob =
        area >= 2 &&
        area <= 9 &&
        span <= (float)compact_target_span_limit &&
        fill >= 0.60f &&
        ring_fraction <= ANOMALY_COLOR_BLOB_COMPACT_RING_MAX &&
        support_mass <= ANOMALY_COLOR_BLOB_COMPACT_SUPPORT_MASS_MAX &&
        near_fraction <= ANOMALY_COLOR_BLOB_COMPACT_NEAR_MAX;
    float quality = 0.0f;
    bool compact_blob_area_exception =
        target_span_cells <= 2 &&
        area <= 49 &&
        span <= 7.5f &&
        fill >= 0.60f &&
        ring_fraction <= 0.12f &&
        support_mass <= 0.35f &&
        near_fraction <= 0.12f;
    bool isolated_small_blob_area_exception =
        area <= 81 &&
        span <= 9.5f &&
        fill >= 0.55f &&
        peak_support >= 1.20f &&
        ring_fraction <= 0.10f;
    bool fresh_singleton_dense_seed =
        color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
        area == 1 &&
        min_x == max_x &&
        min_y == max_y &&
        min_x == peak_x &&
        min_y == peak_y;

    if (!fresh_singleton_dense_seed &&
        area > max_blob_area &&
        !compact_blob_area_exception &&
        !isolated_small_blob_area_exception) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_AREA;
        if (reject_area_out != NULL) *reject_area_out = (float)area;
        if (reject_span_out != NULL) *reject_span_out = span;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        return false;
    }
    bool legacy_support_rejects_apply =
        color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY ||
        fresh_singleton_dense_seed;
    if (legacy_support_rejects_apply && ring_fraction >= 0.36f) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_RING;
        if (reject_area_out != NULL) *reject_area_out = (float)area;
        if (reject_span_out != NULL) *reject_span_out = span;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        return false;
    }
    if (legacy_support_rejects_apply && support_mass >= 2.80f && near_fraction >= 0.22f) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_SUPPORT_MASS;
        if (reject_area_out != NULL) *reject_area_out = (float)area;
        if (reject_span_out != NULL) *reject_span_out = span;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        return false;
    }

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
    quality = area_scale * span_scale * fill_scale * center_scale * isolation_scale;
    quality *= apparent_size_scale;
    quality *= contrast_scale;
    if (compact_target_blob) {
        float compact_strength =
            0.35f * clampf((peak_support - 0.55f) / 0.75f, 0.0f, 1.0f) +
            0.25f * clampf((fill - 0.60f) / 0.35f, 0.0f, 1.0f) +
            0.25f * clampf((0.18f - near_fraction) / 0.18f, 0.0f, 1.0f) +
            0.15f * clampf((0.70f - support_mass) / 0.70f, 0.0f, 1.0f);
        float compact_quality_floor = 0.34f + 1.00f * compact_strength;
        if (quality < compact_quality_floor) quality = compact_quality_floor;
    }
    quality = clampf(quality, 0.0f, 1.40f);
    if (quality <= 0.0f) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_QUALITY;
        if (reject_area_out != NULL) *reject_area_out = (float)area;
        if (reject_span_out != NULL) *reject_span_out = span;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        if (reject_quality_out != NULL) *reject_quality_out = quality;
        return false;
    }

    float final_score = peak_support * strength_scale * (0.62f + 0.58f * quality);
    if (compact_target_blob) {
        float compact_score_boost =
            1.35f +
            1.65f * clampf((quality - 0.40f) / 0.90f, 0.0f, 1.0f) +
            0.85f * clampf((1.0f - ring_fraction) / 0.90f, 0.0f, 1.0f);
        final_score *= compact_score_boost;
    }
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
        if (compact_target_blob) retention_rank += 0.10f;
        retention_rank = clampf(retention_rank, 0.0f, 1.0f);
        retention_rank_valid = true;
    }

    if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
        roi_state != NULL &&
        contrast_map != NULL) {
        size_t peak_idx = (size_t)peak_y * (size_t)sg_w + (size_t)peak_x;
        if (peak_idx < (size_t)sg_w * (size_t)sg_h) {
            float peak_contrast = contrast_map[peak_idx];
            if (peak_contrast >= 0.85f) {
                final_score *= 1.0f + 0.12f * clampf(peak_contrast - 0.85f, 0.0f, 0.6f);
            }
        }
    }

    if (fresh_color_blob_is_too_common_for_dense_verify(
            color_frontend_mode,
            roi_state,
            sg_w,
            sg_h,
            peak_x,
            peak_y,
            allow_target_centered_area_rescue)) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_COMMONNESS;
        if (reject_area_out != NULL) *reject_area_out = (float)area;
        if (reject_span_out != NULL) *reject_span_out = span;
        if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = ring_fraction;
        if (reject_support_mass_out != NULL) *reject_support_mass_out = support_mass;
        if (reject_quality_out != NULL) *reject_quality_out = quality;
        return false;
    }

    anomaly_dense_color_component_t dense_component;
    if (!verify_dense_color_component(
            rgba,
            rgba_stride,
            frame_w,
            frame_h,
            roi_x0,
            roi_y0,
            roi_x1,
            roi_y1,
            sample_step,
            min_x,
            min_y,
            max_x,
            max_y,
            peak_x,
            peak_y,
            target_span_px,
            color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY,
            &dense_component,
            reject_reason_out,
            reject_area_out,
            reject_span_out,
            reject_ring_fraction_out,
            reject_support_mass_out,
            reject_quality_out)) {
        return false;
    }

    float dense_area_cells = (float)dense_component.area_px / (float)(step * step);
    float dense_span_cells = dense_component.span_px / (float)step;
    float small_target_span_cells =
        effective_thermal_small_target_span_px(cfg, frame_w, frame_h) / (float)step;
    if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY) {
        float max_small_span_px = fmaxf(2.0f, small_target_limit_px * 1.15f);
        float max_small_area_cells =
            fmaxf(1.0f, small_target_span_cells * small_target_span_cells * 1.15f);
        bool target_centered_area_rescue =
            allow_target_centered_area_rescue &&
            dense_component.uniqueness >= 0.72f &&
            dense_component.ring_fraction <= 0.08f &&
            dense_component.span_px <= fmaxf(max_small_span_px, small_target_limit_px * 1.75f) &&
            dense_area_cells <= fmaxf(max_small_area_cells, small_target_span_cells * small_target_span_cells * 4.0f);
        if ((dense_component.span_px > max_small_span_px ||
             dense_area_cells > max_small_area_cells) &&
            !target_centered_area_rescue) {
            if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_AREA;
            if (reject_area_out != NULL) *reject_area_out = dense_area_cells;
            if (reject_span_out != NULL) *reject_span_out = dense_span_cells;
            if (reject_ring_fraction_out != NULL) *reject_ring_fraction_out = dense_component.ring_fraction;
            if (reject_support_mass_out != NULL) *reject_support_mass_out = dense_component.support_mass;
            return false;
        }
    }
    float small_target_priority_scale = 1.0f;
    if (anomaly_color_frontend_uses_fresh_winner_gate(color_frontend_mode)) {
        small_target_priority_scale = anomaly_color_small_target_priority_scale(
            dense_span_cells,
            dense_area_cells,
            small_target_span_cells,
            dense_component.uniqueness);
    }
    float dense_quality_scale = 0.70f + 0.55f * dense_component.uniqueness;
    quality *= dense_quality_scale;
    final_score *= 0.62f + 0.95f * dense_component.uniqueness;
    quality *= small_target_priority_scale;
    final_score *= small_target_priority_scale;
    if (quality <= 0.0f || final_score <= 0.0f) {
        if (reject_reason_out != NULL) *reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_QUALITY;
        if (reject_quality_out != NULL) *reject_quality_out = quality;
        return false;
    }
    if (retention_rank_valid) {
        float dense_span_rank =
            clampf((1.08f - (dense_span_cells / fmaxf(small_target_span_cells, 0.001f))) / 0.56f,
                   0.0f,
                   1.0f);
        float dense_area_rank =
            clampf((0.90f - (dense_area_cells / fmaxf(small_target_span_cells * small_target_span_cells, 0.001f))) / 0.52f,
                   0.0f,
                   1.0f);
        retention_rank =
            0.34f * retention_rank +
            0.36f * dense_span_rank +
            0.20f * dense_area_rank +
            0.10f * clampf(dense_component.uniqueness, 0.0f, 1.0f);
        retention_rank = clampf(retention_rank * (0.80f + 0.40f * small_target_priority_scale), 0.0f, 1.0f);
    }

    int dense_min_sx = clamp_i32((dense_component.min_x - roi_x0) / step, 0, sg_w - 1);
    int dense_min_sy = clamp_i32((dense_component.min_y - roi_y0) / step, 0, sg_h - 1);
    int dense_max_sx = clamp_i32((dense_component.max_x - roi_x0 + step - 1) / step, 0, sg_w - 1);
    int dense_max_sy = clamp_i32((dense_component.max_y - roi_y0 + step - 1) / step, 0, sg_h - 1);

    candidate_out->candidate.sg_x = peak_x;
    candidate_out->candidate.sg_y = peak_y;
    candidate_out->candidate.pixel_x = (int)lroundf(dense_component.centroid_x);
    candidate_out->candidate.pixel_y = (int)lroundf(dense_component.centroid_y);
    candidate_out->candidate.proposal_score = peak_support;
    candidate_out->candidate.thermal_score = 0.0f;
    candidate_out->candidate.color_score = final_score;
    candidate_out->retention_rank = retention_rank;
    candidate_out->retention_rank_valid = retention_rank_valid;
    candidate_out->hist_rarity_score = 0.0f;
    if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
        roi_state != NULL &&
        roi_state->color_raw_score != NULL) {
        size_t peak_idx = (size_t)peak_y * (size_t)sg_w + (size_t)peak_x;
        if (peak_idx < (size_t)sg_w * (size_t)sg_h) {
            candidate_out->hist_rarity_score = roi_state->color_raw_score[peak_idx];
        }
    }
    candidate_out->area = dense_area_cells;
    candidate_out->span = dense_span_cells;
    candidate_out->fill = dense_component.fill;
    candidate_out->center_share = center_share;
    candidate_out->quality = quality;
    candidate_out->peak_support = peak_support;
    candidate_out->mean_support = mean_support;
    candidate_out->isolation_score = clampf(0.50f * isolation_score + 0.50f * dense_component.uniqueness, 0.0f, 1.0f);
    candidate_out->ring_fraction = dense_component.ring_fraction;
    candidate_out->support_mass = dense_component.support_mass;
    candidate_out->min_x = dense_min_sx;
    candidate_out->min_y = dense_min_sy;
    candidate_out->max_x = dense_max_sx;
    candidate_out->max_y = dense_max_sy;
    return true;
}

static int rescue_color_blob_subdivision_candidates(
        const anomaly_config_t *cfg,
        const anomaly_roi_state_t *roi_state,
        const uint8_t *rgba,
        int rgba_stride,
        int color_frontend_mode,
        const float *color_support_map,
        const float *contrast_map,
        int sg_w,
        int sg_h,
        int frame_w,
        int frame_h,
        int roi_x0,
        int roi_y0,
        int roi_x1,
        int roi_y1,
        int sample_step,
        int active_min_sx,
        int active_min_sy,
        int active_max_sx,
        int active_max_sy,
        const uint8_t *component_mask,
        const int *component_cells,
        int component_count,
        float target_span_px,
        float small_target_limit_px,
        int target_span_cells,
        int fresh_max_blob_area,
        int compact_target_span_limit,
        anomaly_color_blob_candidate_t *out_candidates,
        int *out_count,
        anomaly_color_blob_target_trace_t *target_trace) {
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY ||
        cfg == NULL || roi_state == NULL || rgba == NULL ||
        color_support_map == NULL || component_mask == NULL ||
        component_cells == NULL || component_count <= 0 ||
        out_candidates == NULL || out_count == NULL ||
        sg_w <= 0 || sg_h <= 0) {
        return 0;
    }

    anomaly_color_dense_seed_t rescue_seeds[ANOMALY_COLOR_COMPONENT_RESCUE_TOP_K];
    int rescue_seed_count = 0;
    int component_peak_cell = -1;
    float component_peak_support = -1.0f;
    if (component_count > 0) {
        for (int qi = 0; qi < component_count; qi++) {
            int cell = component_cells[qi];
            if (cell < 0 || cell >= sg_w * sg_h) continue;
            float support = color_support_map[(size_t)cell];
            if (support > component_peak_support) {
                component_peak_support = support;
                component_peak_cell = cell;
            }
        }
        if (component_peak_cell >= 0 &&
            component_peak_support >= ANOMALY_FRESH_COLOR_BLOB_SEED_MIN) {
            anomaly_color_dense_seed_t seed = {
                .sx = component_peak_cell % sg_w,
                .sy = component_peak_cell / sg_w,
                .support = component_peak_support,
                .score = component_peak_support + 100.0f,
            };
            anomaly_color_insert_dense_seed(
                rescue_seeds,
                &rescue_seed_count,
                ANOMALY_COLOR_COMPONENT_RESCUE_TOP_K,
                &seed);
        }
    }
    for (int qi = 0; qi < component_count; qi++) {
        int cell = component_cells[qi];
        if (cell < 0 || cell >= sg_w * sg_h) continue;
        int sx = cell % sg_w;
        int sy = cell / sg_w;
        float support = color_support_map[(size_t)cell];
        if (support < ANOMALY_FRESH_COLOR_BLOB_SEED_MIN) continue;
        if (!anomaly_color_support_seed_is_local_peak(
                color_support_map,
                sg_w,
                sg_h,
                sx,
                sy,
                active_min_sx,
                active_min_sy,
                active_max_sx,
                active_max_sy,
                support)) {
            continue;
        }
        float rank = anomaly_color_score_dense_seed(
            color_support_map,
            contrast_map,
            sg_w,
            sg_h,
            sx,
            sy,
            active_min_sx,
            active_min_sy,
            active_max_sx,
            active_max_sy,
            support);
        if (roi_state->color_raw_score != NULL) {
            float rarity = roi_state->color_raw_score[(size_t)cell];
            rank *= 0.55f + 0.45f * clampf(rarity / ANOMALY_COLOR_RARITY_MIN, 0.0f, 2.0f);
        }
        anomaly_color_dense_seed_t seed = {
            .sx = sx,
            .sy = sy,
            .support = support,
            .score = rank,
        };
        anomaly_color_insert_dense_seed(
            rescue_seeds,
            &rescue_seed_count,
            ANOMALY_COLOR_COMPONENT_RESCUE_TOP_K,
            &seed);
    }

    int accepted_count = 0;
    int step = sample_step > 0 ? sample_step : 1;
    int max_radius_cells =
        (int)ceilf(fmaxf(1.0f, small_target_limit_px / (float)step));
    if (max_radius_cells < 1) max_radius_cells = 1;
    if (max_radius_cells > compact_target_span_limit) {
        max_radius_cells = compact_target_span_limit;
    }

    for (int seed_i = 0; seed_i < rescue_seed_count; seed_i++) {
        int seed_sx = rescue_seeds[seed_i].sx;
        int seed_sy = rescue_seeds[seed_i].sy;
        float seed_support = rescue_seeds[seed_i].support;
        int seed_cell = seed_sy * sg_w + seed_sx;
        if (component_mask[(size_t)seed_cell] < 2u) continue;
        bool seed_is_target =
            target_trace != NULL &&
            target_trace->enabled &&
            target_trace->valid &&
            seed_sx == target_trace->target_sx &&
            seed_sy == target_trace->target_sy;

        int lobe_queue[ANOMALY_COLOR_COMPONENT_RESCUE_MAX_CELLS];
        int head = 0;
        int tail = 0;
        lobe_queue[tail++] = seed_cell;

        int area = 0;
        int min_x = seed_sx;
        int max_x = seed_sx;
        int min_y = seed_sy;
        int max_y = seed_sy;
        double sum_support = 0.0;
        float peak_support = seed_support;
        int peak_x = seed_sx;
        int peak_y = seed_sy;
        bool lobe_contains_target = seed_is_target;
        float join_floor = fmaxf(ANOMALY_FRESH_COLOR_BLOB_SEED_MIN, seed_support * 0.70f);
        float band = fmaxf(0.30f, seed_support * 0.45f);

        while (head < tail) {
            int cur = lobe_queue[head++];
            int cx = cur % sg_w;
            int cy = cur / sg_w;
            float cur_support = color_support_map[(size_t)cur];
            if (cur_support <= 0.0f) continue;
            if (target_trace != NULL &&
                target_trace->enabled &&
                target_trace->valid &&
                cx == target_trace->target_sx &&
                cy == target_trace->target_sy) {
                lobe_contains_target = true;
            }

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

            for (int oy = -1; oy <= 1; oy++) {
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox == 0 && oy == 0) continue;
                    int nx = cx + ox;
                    int ny = cy + oy;
                    if (nx < active_min_sx || nx > active_max_sx ||
                        ny < active_min_sy || ny > active_max_sy) {
                        continue;
                    }
                    int ndx = abs(nx - seed_sx);
                    int ndy = abs(ny - seed_sy);
                    if (ndx > max_radius_cells || ndy > max_radius_cells) continue;
                    int nidx = ny * sg_w + nx;
                    if (component_mask[(size_t)nidx] < 2u) continue;

                    bool already_in_lobe = false;
                    for (int li = 0; li < tail; li++) {
                        if (lobe_queue[li] == nidx) {
                            already_in_lobe = true;
                            break;
                        }
                    }
                    if (already_in_lobe) continue;
                    if (tail >= ANOMALY_COLOR_COMPONENT_RESCUE_MAX_CELLS) continue;

                    float neighbor = color_support_map[(size_t)nidx];
                    if (neighbor < join_floor) continue;
                    if (fabsf(neighbor - cur_support) > band &&
                        fabsf(neighbor - seed_support) > band) {
                        continue;
                    }
                    float similarity = anomaly_color_blob_neighbor_similarity(roi_state, (size_t)cur, (size_t)nidx);
                    if (similarity < 0.50f) continue;
                    lobe_queue[tail++] = nidx;
                }
            }
        }

        bool lobe_shape_eligible =
            area > 0 &&
            area <= fresh_max_blob_area &&
            (max_x - min_x + 1) <= compact_target_span_limit &&
            (max_y - min_y + 1) <= compact_target_span_limit;

        anomaly_color_blob_candidate_t candidate;
        int reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
        float reject_area = 0.0f;
        float reject_span = 0.0f;
        float reject_ring_fraction = 0.0f;
        float reject_support_mass = 0.0f;
        float reject_quality = 0.0f;
        bool accepted = false;
        if (lobe_shape_eligible) {
            accepted = build_color_blob_candidate(
                cfg,
                roi_state,
                rgba,
                rgba_stride,
                color_frontend_mode,
                color_support_map,
                contrast_map,
                sg_w,
                sg_h,
                frame_w,
                frame_h,
                roi_x0,
                roi_y0,
                roi_x1,
                roi_y1,
                sample_step,
                area,
                min_x,
                min_y,
                max_x,
                max_y,
                sum_support,
                peak_support,
                peak_x,
                peak_y,
                target_span_px,
                small_target_limit_px,
                target_span_cells,
                fresh_max_blob_area,
            compact_target_span_limit,
            &candidate,
            &reject_reason,
            &reject_area,
            &reject_span,
            &reject_ring_fraction,
            &reject_support_mass,
            &reject_quality,
            false);
        }
        if (!accepted && seed_support >= 2.50f) {
            double singleton_support = (double)seed_support;
            accepted = build_color_blob_candidate(
                cfg,
                roi_state,
                rgba,
                rgba_stride,
                color_frontend_mode,
                color_support_map,
                contrast_map,
                sg_w,
                sg_h,
                frame_w,
                frame_h,
                roi_x0,
                roi_y0,
                roi_x1,
                roi_y1,
                sample_step,
                1,
                seed_sx,
                seed_sy,
                seed_sx,
                seed_sy,
                singleton_support,
                seed_support,
                seed_sx,
                seed_sy,
                target_span_px,
                small_target_limit_px,
                target_span_cells,
                fresh_max_blob_area,
                compact_target_span_limit,
                &candidate,
                &reject_reason,
                &reject_area,
                &reject_span,
                &reject_ring_fraction,
                &reject_support_mass,
                &reject_quality,
                seed_is_target);
            if (accepted) {
                lobe_contains_target = seed_is_target;
                peak_x = seed_sx;
                peak_y = seed_sy;
                min_x = seed_sx;
                min_y = seed_sy;
                max_x = seed_sx;
                max_y = seed_sy;
                area = 1;
                sum_support = singleton_support;
            }
        }
        (void)reject_reason;
        (void)reject_area;
        (void)reject_span;
        (void)reject_ring_fraction;
        (void)reject_support_mass;
        (void)reject_quality;
        if (!accepted) continue;

        if (lobe_contains_target && target_trace != NULL) {
            record_color_target_trace_component(
                    target_trace,
                    seed_sx,
                    seed_sy,
                    peak_x,
                    peak_y,
                    candidate.min_x,
                    candidate.min_y,
                    candidate.max_x,
                    candidate.max_y,
                    (int)lroundf(fmaxf(candidate.area, 1.0f)),
                    sum_support,
                    peak_support,
                    &candidate,
                    true,
                    reject_reason,
                    reject_span,
                    reject_quality,
                    reject_ring_fraction,
                    reject_support_mass);
        }
        insert_color_blob_candidate(
                out_candidates,
                out_count,
                &candidate,
                target_trace,
                lobe_contains_target);
        accepted_count++;
        if (accepted_count >= 2) break;
    }

    return accepted_count;
}

static void insert_color_blob_candidate(
        anomaly_color_blob_candidate_t *top,
        int                            *top_count,
        const anomaly_color_blob_candidate_t *candidate,
        anomaly_color_blob_target_trace_t *target_trace,
        bool candidate_is_target) {
    if (top == NULL || top_count == NULL || candidate == NULL) return;

    int target_rank_before = find_color_target_blob_rank(top, *top_count, target_trace);
    anomaly_color_blob_insert_report_t report;
    anomaly_appearance_insert_color_blob_candidate(
            top,
            top_count,
            candidate,
            ANOMALY_MAX_COLOR_CANDIDATES,
            ANOMALY_MOTION_CANDIDATE_NMS_RADIUS,
            target_rank_before,
            candidate_is_target,
            &report);
    if (!report.valid || target_trace == NULL) return;

    if (candidate_is_target) {
        record_color_target_pre_cap_rank(
                target_trace,
                candidate,
                report.candidate_count_before,
                report.pre_cap_rank);
    }

    if (report.replaced_existing_by_nms &&
        target_rank_before == report.nms_conflict_rank &&
        !candidate_is_target) {
        target_trace->dropped_by_nms = true;
        target_trace->replaced_by_nms = true;
        target_trace->nms_conflict_rank = report.nms_conflict_rank;
        target_trace->nms_conflict_sample_x = report.nms_conflict_sample_x;
        target_trace->nms_conflict_sample_y = report.nms_conflict_sample_y;
    }
    if (candidate_is_target && report.nms_conflict_rank >= 0) {
        target_trace->nms_conflict_rank = report.nms_conflict_rank;
        target_trace->nms_conflict_sample_x = report.nms_conflict_sample_x;
        target_trace->nms_conflict_sample_y = report.nms_conflict_sample_y;
    }
    if (candidate_is_target && report.rejected_by_nms) {
        target_trace->dropped_by_nms = true;
    }
    if (candidate_is_target && report.rejected_by_cap) {
        target_trace->dropped_by_cap = true;
    }
    if (report.target_tail_dropped_by_cap) {
        target_trace->dropped_by_cap = true;
    }
}

static void record_color_target_trace_component(
        anomaly_color_blob_target_trace_t      *target_trace,
        int                                     sx,
        int                                     sy,
        int                                     peak_x,
        int                                     peak_y,
        int                                     min_x,
        int                                     min_y,
        int                                     max_x,
        int                                     max_y,
        int                                     area,
        double                                  sum_support,
        float                                   peak_support,
        const anomaly_color_blob_candidate_t   *candidate,
        bool                                    accepted,
        int                                     reject_reason,
        float                                   reject_span,
        float                                   reject_quality,
        float                                   reject_ring_fraction,
        float                                   reject_support_mass) {
    if (target_trace == NULL || candidate == NULL) return;
    target_trace->component_seed_x = sx;
    target_trace->component_seed_y = sy;
    target_trace->component_peak_x = peak_x;
    target_trace->component_peak_y = peak_y;
    target_trace->min_x = min_x;
    target_trace->min_y = min_y;
    target_trace->max_x = max_x;
    target_trace->max_y = max_y;
    target_trace->component_area = (float)area;
    target_trace->component_span = accepted ? candidate->span : reject_span;
    target_trace->component_fill = accepted
        ? candidate->fill
        : ((max_x >= min_x && max_y >= min_y)
            ? ((float)area / (float)((max_x - min_x + 1) * (max_y - min_y + 1)))
            : 0.0f);
    target_trace->component_peak_support = peak_support;
    target_trace->component_mean_support = area > 0
        ? (float)(sum_support / (double)area)
        : 0.0f;
    target_trace->component_quality = accepted ? candidate->quality : reject_quality;
    target_trace->component_ring_fraction = accepted ? candidate->ring_fraction : reject_ring_fraction;
    target_trace->component_support_mass = accepted ? candidate->support_mass : reject_support_mass;
    target_trace->component_rejected = !accepted;
    target_trace->component_rejection_reason = reject_reason;
}

static bool fresh_color_blob_is_too_common_for_dense_verify(
        int                        color_frontend_mode,
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        peak_x,
        int                        peak_y,
        bool                       target_centered_rescue) {
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY ||
        target_centered_rescue ||
        roi_state == NULL ||
        roi_state->color_raw_score == NULL ||
        sg_w <= 0 || sg_h <= 0 ||
        peak_x < 0 || peak_x >= sg_w ||
        peak_y < 0 || peak_y >= sg_h) {
        return false;
    }
    size_t peak_idx = (size_t)peak_y * (size_t)sg_w + (size_t)peak_x;
    float rarity = roi_state->color_raw_score[peak_idx];
    return rarity < ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY;
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
    if (!anomaly_roi_state_ensure_pixel_capacity(roi_state, sg_count)) return false;

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
                anomaly_color_compute_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                anomaly_color_sample_cell(
                        rgba, rgba_stride, frame_width, frame_height, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                anomaly_color_fill_uv_bins(roi_state, idx);
                roi_state->color_valid_mask[idx] = 1u;
                roi_state->color_phase_x[idx] = (uint8_t)active_phase_x;
                roi_state->color_phase_y[idx] = (uint8_t)active_phase_y;
            }
        }
        if (fresh_count_out != NULL) *fresh_count_out = (int)sg_count;
        return true;
    }

    if (!anomaly_scratch_ensure_prev_roi_snapshot_capacity(state, sg_count)) return false;
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
                anomaly_color_compute_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                anomaly_color_sample_cell(
                        rgba, rgba_stride, frame_width, frame_height, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                anomaly_color_fill_uv_bins(roi_state, idx);
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
                anomaly_color_compute_sample_xy(
                        roi_x0, roi_y0, roi_x1, roi_y1,
                        sx, sy, sample_step,
                        active_phase_x, active_phase_y,
                        &sample_x, &sample_y);
                anomaly_color_sample_cell(
                        rgba, rgba_stride, frame_width, frame_height, sample_x, sample_y,
                        &roi_state->color_luma[idx],
                        &roi_state->color_u[idx],
                        &roi_state->color_v[idx]);
                anomaly_color_fill_uv_bins(roi_state, idx);
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

static void build_color_support_map(
        const anomaly_config_t *cfg,
        int                     color_frontend_mode,
        float                   fresh_distinctness_ratio,
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
    float target_span_px = anomaly_runtime_effective_color_target_span_px(cfg, frame_w, frame_h);
    int patch_radius = anomaly_color_support_patch_radius(target_span_px, sample_step);
    if (active_min_sx < 0) active_min_sx = 0;
    if (active_min_sy < 0) active_min_sy = 0;
    if (active_max_sx >= sg_w) active_max_sx = sg_w - 1;
    if (active_max_sy >= sg_h) active_max_sy = sg_h - 1;
    if (active_max_sx < active_min_sx || active_max_sy < active_min_sy) {
        return;
    }
    anomaly_grid_region_zero_float(
            scratch_map, sg_w, active_min_sx, active_min_sy, active_max_sx, active_max_sy);

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
            float local_peak = center;
            int count = 0;
            int support_count = 0;
            int ring_count = 0;
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
                    if (v > local_peak) local_peak = v;
                    if (v >= 0.35f) support_count++;
                    if (chebyshev == patch_radius) {
                        ring_sum += v;
                        ring_count++;
                    }
                }
            }
            float mean = count > 0 ? (sum / (float)count) : 0.0f;
            float legacy_ring_mean = patch_radius > 0
                ? (ring_sum / (float)(patch_radius * 8))
                : 0.0f;
            float fresh_ring_mean = ring_count > 0 ? (ring_sum / (float)ring_count) : 0.0f;
            float density = count > 0 ? ((float)support_count / (float)count) : 0.0f;
            float contrast_weight = contrast_map != NULL ? contrast_map[idx] : 1.0f;
            anomaly_color_support_score_t support_score = anomaly_color_score_support_patch(
                color_frontend_mode,
                fresh_distinctness_ratio,
                center,
                mean,
                legacy_ring_mean,
                fresh_ring_mean,
                density,
                local_peak,
                support_count,
                contrast_weight);
            float clamped_support = support_score.support;
            float seed_floor = support_score.seed_floor;
            scratch_map[idx] = clamped_support;
            if (max_support_out != NULL && clamped_support > *max_support_out) {
                *max_support_out = clamped_support;
            }
            if (clamped_support >= seed_floor) {
                if (seed_count_out != NULL) (*seed_count_out)++;
                if (seed_min_sx_out != NULL && sx < *seed_min_sx_out) *seed_min_sx_out = sx;
                if (seed_min_sy_out != NULL && sy < *seed_min_sy_out) *seed_min_sy_out = sy;
                if (seed_max_sx_out != NULL && sx > *seed_max_sx_out) *seed_max_sx_out = sx;
                if (seed_max_sy_out != NULL && sy > *seed_max_sy_out) *seed_max_sy_out = sy;
            }
        }
    }

    if (support_map != scratch_map) {
        anomaly_grid_region_copy_float(
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
        const anomaly_state_t  *state,
        const anomaly_roi_state_t *roi_state,
        anomaly_rescan_mode_t   rescan_mode,
        int                     color_frontend_mode,
        const uint8_t          *rgba,
        int                     rgba_stride,
        const float            *color_support_map,
        const float            *contrast_map,
        int                     sg_w,
        int                     sg_h,
        int                     frame_w,
        int                     frame_h,
        int                     roi_x0,
        int                     roi_y0,
        int                     roi_x1,
        int                     roi_y1,
        int                     sample_step,
        int                     active_min_sx,
        int                     active_min_sy,
        int                     active_max_sx,
        int                     active_max_sy,
        uint8_t                *visited,
        int                    *queue,
        anomaly_color_blob_candidate_t *out_candidates,
        int                    *out_count,
        int                    *reject_area_count_out,
        int                    *reject_ring_count_out,
        int                    *reject_support_mass_count_out,
        int                    *reject_quality_count_out,
        int                    *blob_examined_count_out,
        int                    *coarse_oversized_count_out,
        int                    *dense_verify_component_count_out,
        int                    *strongest_reject_reason_out,
        float                  *strongest_reject_peak_support_out,
        float                  *strongest_reject_area_out,
        float                  *strongest_reject_span_out,
        float                  *strongest_reject_ring_fraction_out,
        float                  *strongest_reject_support_mass_out,
        float                  *strongest_reject_quality_out,
        anomaly_color_blob_target_trace_t *target_trace,
        float                  *target_span_px_out,
        int                    *target_span_cells_out,
        int                    *max_blob_area_budget_out) {
    if (out_count != NULL) *out_count = 0;
    if (reject_area_count_out != NULL) *reject_area_count_out = 0;
    if (reject_ring_count_out != NULL) *reject_ring_count_out = 0;
    if (reject_support_mass_count_out != NULL) *reject_support_mass_count_out = 0;
    if (reject_quality_count_out != NULL) *reject_quality_count_out = 0;
    if (blob_examined_count_out != NULL) *blob_examined_count_out = 0;
    if (coarse_oversized_count_out != NULL) *coarse_oversized_count_out = 0;
    if (dense_verify_component_count_out != NULL) *dense_verify_component_count_out = 0;
    if (strongest_reject_reason_out != NULL) *strongest_reject_reason_out = ANOMALY_COLOR_BLOB_REJECT_NONE;
    if (strongest_reject_peak_support_out != NULL) *strongest_reject_peak_support_out = 0.0f;
    if (strongest_reject_area_out != NULL) *strongest_reject_area_out = 0.0f;
    if (strongest_reject_span_out != NULL) *strongest_reject_span_out = 0.0f;
    if (strongest_reject_ring_fraction_out != NULL) *strongest_reject_ring_fraction_out = 0.0f;
    if (strongest_reject_support_mass_out != NULL) *strongest_reject_support_mass_out = 0.0f;
    if (strongest_reject_quality_out != NULL) *strongest_reject_quality_out = 0.0f;
    if (target_span_px_out != NULL) *target_span_px_out = 0.0f;
    if (target_span_cells_out != NULL) *target_span_cells_out = 0;
    if (max_blob_area_budget_out != NULL) *max_blob_area_budget_out = 0;
    if (color_support_map == NULL || visited == NULL || queue == NULL ||
        out_candidates == NULL || out_count == NULL || sg_w <= 0 || sg_h <= 0) {
        return;
    }
    if (target_trace != NULL) {
        target_trace->component_seed_x = -1;
        target_trace->component_seed_y = -1;
        target_trace->component_peak_x = -1;
        target_trace->component_peak_y = -1;
        target_trace->min_x = -1;
        target_trace->min_y = -1;
        target_trace->max_x = -1;
        target_trace->max_y = -1;
        target_trace->component_area = 0.0f;
        target_trace->component_span = 0.0f;
        target_trace->component_fill = 0.0f;
        target_trace->component_peak_support = 0.0f;
        target_trace->component_mean_support = 0.0f;
        target_trace->component_quality = 0.0f;
        target_trace->component_ring_fraction = 0.0f;
        target_trace->component_support_mass = 0.0f;
        target_trace->component_rejected = false;
        target_trace->component_rejection_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
        target_trace->dropped_by_cap = false;
        target_trace->dropped_by_nms = false;
        target_trace->replaced_by_nms = false;
        target_trace->nms_conflict_rank = -1;
        target_trace->nms_conflict_sample_x = -1;
        target_trace->nms_conflict_sample_y = -1;
        target_trace->pre_cap_rank = -1;
        target_trace->pre_cap_candidate_count = 0;
        target_trace->pre_cap_limit = ANOMALY_MAX_COLOR_CANDIDATES;
        target_trace->pre_cap_retention_rank = -1.0f;
    }

    float strongest_reject_peak_support = 0.0f;
    int strongest_reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
    float strongest_reject_area = 0.0f;
    float strongest_reject_span = 0.0f;
    float strongest_reject_ring_fraction = 0.0f;
    float strongest_reject_support_mass = 0.0f;
    float strongest_reject_quality = 0.0f;

    if (active_min_sx < 0) active_min_sx = 0;
    if (active_min_sy < 0) active_min_sy = 0;
    if (active_max_sx >= sg_w) active_max_sx = sg_w - 1;
    if (active_max_sy >= sg_h) active_max_sy = sg_h - 1;
    if (active_max_sx < active_min_sx || active_max_sy < active_min_sy) return;
    anomaly_grid_region_zero_u8(
            visited, sg_w, active_min_sx, active_min_sy, active_max_sx, active_max_sy);

    float target_span_px = anomaly_runtime_effective_color_target_span_px(cfg, frame_w, frame_h);
    float small_target_limit_px = effective_thermal_small_target_span_px(cfg, frame_w, frame_h);
    int step = sample_step > 0 ? sample_step : 1;
    int target_span_cells = (int)lroundf(fmaxf(1.0f, target_span_px / (float)step));
    int small_target_limit_cells =
        (int)ceilf(fmaxf(1.0f, small_target_limit_px / (float)step));
    int max_blob_area = target_span_cells * target_span_cells * 4;
    if (max_blob_area < 4) max_blob_area = 4;
    // Allow compact ~7x7 recovered target blobs through area gating so later
    // isolation/quality checks, rather than this coarse ceiling, decide them.
    if (max_blob_area > 64) max_blob_area = 64;
    int fresh_max_blob_area = small_target_limit_cells * small_target_limit_cells;
    if (fresh_max_blob_area < 1) fresh_max_blob_area = 1;
    int compact_target_span_limit = target_span_cells + 1;
    if (compact_target_span_limit < 3) compact_target_span_limit = 3;
    if (target_span_px_out != NULL) *target_span_px_out = target_span_px;
    if (target_span_cells_out != NULL) *target_span_cells_out = target_span_cells;
    if (max_blob_area_budget_out != NULL) {
        *max_blob_area_budget_out =
            color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY
                ? fresh_max_blob_area
                : max_blob_area;
    }

    bool use_dense_peak_seed_path =
        color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
        rgba != NULL &&
        roi_state != NULL;
    if (use_dense_peak_seed_path) {
        anomaly_color_dense_seed_t dense_seeds[ANOMALY_COLOR_DENSE_SEED_TOP_K];
        int dense_seed_count = 0;
        int dense_seed_limit = (rescan_mode == ANOMALY_RESCAN_MODE_FULL)
            ? ANOMALY_COLOR_DENSE_SEED_TOP_K
            : ANOMALY_COLOR_DENSE_INTERIM_SEED_TOP_K;
        for (int sy = active_min_sy; sy <= active_max_sy; sy++) {
            for (int sx = active_min_sx; sx <= active_max_sx; sx++) {
                size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                float seed_support = color_support_map[seed_idx];
                float seed_floor = color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY
                    ? 0.55f
                    : ANOMALY_FRESH_COLOR_BLOB_SEED_MIN;
                if (seed_support < seed_floor) continue;
                if (!anomaly_color_support_seed_is_local_peak(
                        color_support_map,
                        sg_w,
                        sg_h,
                        sx,
                        sy,
                        active_min_sx,
                        active_min_sy,
                        active_max_sx,
                        active_max_sy,
                        seed_support)) {
                    continue;
                }
                anomaly_color_dense_seed_t seed;
                seed.sx = sx;
                seed.sy = sy;
                seed.support = seed_support;
                float seed_rank = anomaly_color_score_dense_seed(
                    color_support_map,
                    contrast_map,
                    sg_w,
                    sg_h,
                    sx,
                    sy,
                    active_min_sx,
                    active_min_sy,
                    active_max_sx,
                    active_max_sy,
                    seed_support);
                if (roi_state->color_raw_score != NULL) {
                    float rarity = roi_state->color_raw_score[seed_idx];
                    seed_rank = seed_rank * (0.55f + 0.45f * clampf(rarity / ANOMALY_COLOR_RARITY_MIN, 0.0f, 2.0f));
                }
                seed.score = seed_rank;
                anomaly_color_insert_dense_seed(dense_seeds, &dense_seed_count, dense_seed_limit, &seed);
            }
        }

        for (int seed_i = 0; seed_i < dense_seed_count; seed_i++) {
                int sx = dense_seeds[seed_i].sx;
                int sy = dense_seeds[seed_i].sy;
                float seed_support = dense_seeds[seed_i].support;
                size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                if (visited[seed_idx] != 0u) continue;
                visited[seed_idx] = 1u;

                int head = 0;
                int tail = 0;
                queue[tail++] = (int)seed_idx;
                int area = 0;
                int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
                double sum_support = 0.0;
                float peak_support = seed_support;
                int peak_x = sx;
                int peak_y = sy;
                bool component_contains_target =
                    target_trace != NULL &&
                    target_trace->enabled &&
                    target_trace->valid &&
                    sx == target_trace->target_sx &&
                    sy == target_trace->target_sy;

                while (head < tail) {
                    int cur = queue[head++];
                    int cx = cur % sg_w;
                    int cy = cur / sg_w;
                    float cur_support = color_support_map[cur];
                    if (cur_support <= 0.0f) continue;
                    if (target_trace != NULL &&
                        target_trace->enabled &&
                        target_trace->valid &&
                        cx == target_trace->target_sx &&
                        cy == target_trace->target_sy) {
                        component_contains_target = true;
                    }

                    area++;
                    sum_support += (double)cur_support;
                    if (cx < min_x) min_x = cx;
                    if (cx > max_x) max_x = cx;
                    if (cy < min_y) min_y = cy;
                    if (cy > max_y) max_y = cy;
                    if (roi_state->color_raw_score != NULL) {
                        float cur_rarity = roi_state->color_raw_score[(size_t)cy * (size_t)sg_w + (size_t)cx];
                        float peak_rarity = roi_state->color_raw_score[(size_t)peak_y * (size_t)sg_w + (size_t)peak_x];
                        if (cur_rarity > peak_rarity ||
                            (fabsf(cur_rarity - peak_rarity) <= 0.000001f && cur_support > peak_support)) {
                            peak_support = cur_support;
                            peak_x = cx;
                            peak_y = cy;
                        }
                    } else if (cur_support > peak_support) {
                        peak_support = cur_support;
                        peak_x = cx;
                        peak_y = cy;
                    }

                    float blob_mean = area > 0 ? (float)(sum_support / (double)area) : seed_support;
                    float join_floor = fmaxf(0.22f, fminf(seed_support, blob_mean) * 0.42f);
                    if (area > fresh_max_blob_area ||
                        (max_x - min_x + 1) > small_target_limit_cells ||
                        (max_y - min_y + 1) > small_target_limit_cells) {
                        join_floor = fmaxf(join_floor, peak_support * 0.72f);
                    }
                    float band = fmaxf(0.30f, peak_support * 0.70f);
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
                            float cohesion_similarity = anomaly_color_blob_neighbor_similarity(roi_state, (size_t)cur, nidx);
                            if (cohesion_similarity < 0.34f) continue;
                            visited[nidx] = 1u;
                            queue[tail++] = (int)nidx;
                        }
                    }
                }

                if (area <= 0) continue;
                int component_count = tail;
                for (int qi = 0; qi < component_count; qi++) {
                    visited[(size_t)queue[qi]] = 2u;
                }
                if (blob_examined_count_out != NULL) (*blob_examined_count_out)++;

                int component_span_w = max_x - min_x + 1;
                int component_span_h = max_y - min_y + 1;
                int component_span = component_span_w > component_span_h ? component_span_w : component_span_h;
                anomaly_color_blob_candidate_t candidate;
                int reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
                float reject_area = (float)area;
                float reject_span = (float)component_span;
                float reject_ring_fraction = 0.0f;
                float reject_support_mass = 0.0f;
                float reject_quality = 0.0f;
                bool coarse_oversized =
                    component_span > small_target_limit_cells ||
                    area > fresh_max_blob_area;

                if (coarse_oversized) {
                    if (coarse_oversized_count_out != NULL) (*coarse_oversized_count_out)++;
                    int rescue_count = rescue_color_blob_subdivision_candidates(
                            cfg,
                            roi_state,
                            rgba,
                            rgba_stride,
                            color_frontend_mode,
                            color_support_map,
                            contrast_map,
                            sg_w,
                            sg_h,
                            frame_w,
                            frame_h,
                            roi_x0,
                            roi_y0,
                            roi_x1,
                            roi_y1,
                            sample_step,
                            active_min_sx,
                            active_min_sy,
                            active_max_sx,
                            active_max_sy,
                            visited,
                            queue,
                            component_count,
                            target_span_px,
                            small_target_limit_px,
                            target_span_cells,
                            fresh_max_blob_area,
                            compact_target_span_limit,
                            out_candidates,
                            out_count,
                            target_trace);
                    if (rescue_count > 0) {
                        if (dense_verify_component_count_out != NULL) {
                            *dense_verify_component_count_out += rescue_count;
                        }
                        continue;
                    }
                    bool target_centered_rescue =
                        peak_support >= 2.50f &&
                        area <= fresh_max_blob_area * 8 &&
                        component_span <= small_target_limit_cells + 3 &&
                        (component_contains_target ||
                         color_component_near_predicted_color_target(
                            state,
                            roi_x0,
                            roi_y0,
                            sample_step,
                            frame_w,
                            frame_h,
                            min_x,
                            min_y,
                            max_x,
                            max_y,
                            peak_x,
                            peak_y,
                            compact_target_span_limit));
                    if (target_centered_rescue) {
                        int rescue_radius = target_span_cells <= 2 ? 1 : 2;
                        int rescue_min_x = clamp_i32(peak_x - rescue_radius, min_x, max_x);
                        int rescue_max_x = clamp_i32(peak_x + rescue_radius, min_x, max_x);
                        int rescue_min_y = clamp_i32(peak_y - rescue_radius, min_y, max_y);
                        int rescue_max_y = clamp_i32(peak_y + rescue_radius, min_y, max_y);
                        int rescue_span_w = rescue_max_x - rescue_min_x + 1;
                        int rescue_span_h = rescue_max_y - rescue_min_y + 1;
                        int rescue_span = rescue_span_w > rescue_span_h ? rescue_span_w : rescue_span_h;
                        int rescue_area = rescue_span_w * rescue_span_h;
                        memset(&candidate, 0, sizeof(candidate));
                        candidate.candidate.sg_x = peak_x;
                        candidate.candidate.sg_y = peak_y;
                        candidate.candidate.pixel_x = clamp_i32(roi_x0 + peak_x * sample_step, 0, frame_w - 1);
                        candidate.candidate.pixel_y = clamp_i32(roi_y0 + peak_y * sample_step, 0, frame_h - 1);
                        candidate.candidate.proposal_score = peak_support;
                        candidate.candidate.thermal_score = 0.0f;
                        candidate.candidate.color_score = fmaxf(cfg->score_threshold + 0.18f, peak_support + 0.92f);
                        candidate.retention_rank = 0.86f;
                        candidate.retention_rank_valid = true;
                        candidate.hist_rarity_score = 0.0f;
                        if (roi_state != NULL && roi_state->color_raw_score != NULL) {
                            size_t peak_idx = (size_t)peak_y * (size_t)sg_w + (size_t)peak_x;
                            if (peak_idx < (size_t)sg_w * (size_t)sg_h) {
                                candidate.hist_rarity_score = roi_state->color_raw_score[peak_idx];
                            }
                        }
                        candidate.area = (float)rescue_area;
                        candidate.span = (float)rescue_span;
                        candidate.fill = 0.82f;
                        candidate.center_share = 0.75f;
                        candidate.quality = 0.92f;
                        candidate.peak_support = peak_support;
                        candidate.mean_support = area > 0 ? (float)(sum_support / (double)area) : peak_support;
                        candidate.isolation_score = 0.86f;
                        candidate.ring_fraction = 0.04f;
                        candidate.support_mass = 0.08f;
                        candidate.min_x = rescue_min_x;
                        candidate.min_y = rescue_min_y;
                        candidate.max_x = rescue_max_x;
                        candidate.max_y = rescue_max_y;
                        if (dense_verify_component_count_out != NULL) {
                            (*dense_verify_component_count_out)++;
                        }
                        if (component_contains_target && target_trace != NULL) {
                            record_color_target_trace_component(
                                target_trace,
                                sx,
                                sy,
                                peak_x,
                                peak_y,
                                candidate.min_x,
                                candidate.min_y,
                                candidate.max_x,
                                candidate.max_y,
                                rescue_area,
                                sum_support,
                                peak_support,
                                &candidate,
                                true,
                                ANOMALY_COLOR_BLOB_REJECT_NONE,
                                0.0f,
                                0.0f,
                                0.0f,
                                0.0f);
                        }
                        insert_color_blob_candidate(
                                out_candidates,
                                out_count,
                                &candidate,
                                target_trace,
                                component_contains_target);
                        anomaly_color_suppress_seed_region(
                            visited,
                            sg_w,
                            sg_h,
                            active_min_sx,
                            active_min_sy,
                            active_max_sx,
                            active_max_sy,
                            candidate.min_x,
                            candidate.min_y,
                            candidate.max_x,
                            candidate.max_y);
                        continue;
                    }
                    reject_reason = ANOMALY_COLOR_BLOB_REJECT_AREA;
                    if (component_contains_target && target_trace != NULL) {
                        record_color_target_trace_component(
                            target_trace,
                            sx,
                            sy,
                            peak_x,
                            peak_y,
                            min_x,
                            min_y,
                            max_x,
                            max_y,
                            area,
                            sum_support,
                            peak_support,
                            &candidate,
                            false,
                            reject_reason,
                            reject_span,
                            reject_quality,
                            reject_ring_fraction,
                            reject_support_mass);
                    }
                    if (peak_support >= strongest_reject_peak_support) {
                        strongest_reject_peak_support = peak_support;
                        strongest_reject_reason = reject_reason;
                        strongest_reject_area = reject_area;
                        strongest_reject_span = reject_span;
                        strongest_reject_ring_fraction = reject_ring_fraction;
                        strongest_reject_support_mass = reject_support_mass;
                        strongest_reject_quality = reject_quality;
                    }
                    if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                    continue;
                }

                if (dense_verify_component_count_out != NULL) (*dense_verify_component_count_out)++;

                bool accepted = build_color_blob_candidate(
                        cfg,
                        roi_state,
                        rgba,
                        rgba_stride,
                        color_frontend_mode,
                        color_support_map,
                        contrast_map,
                        sg_w,
                        sg_h,
                        frame_w,
                        frame_h,
                        roi_x0,
                        roi_y0,
                        roi_x1,
                        roi_y1,
                        sample_step,
                        area,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        sum_support,
                        peak_support,
                        peak_x,
                        peak_y,
                        target_span_px,
                        small_target_limit_px,
                        target_span_cells,
                        fresh_max_blob_area,
                        compact_target_span_limit,
                        &candidate,
                        &reject_reason,
                        &reject_area,
                        &reject_span,
                        &reject_ring_fraction,
                        &reject_support_mass,
                        &reject_quality,
                        false);
                if (component_contains_target && target_trace != NULL) {
                    int trace_min_x = accepted ? candidate.min_x : min_x;
                    int trace_min_y = accepted ? candidate.min_y : min_y;
                    int trace_max_x = accepted ? candidate.max_x : max_x;
                    int trace_max_y = accepted ? candidate.max_y : max_y;
                    int trace_area = accepted
                        ? (int)lroundf(fmaxf(candidate.area, 1.0f))
                        : (int)lroundf(fmaxf(reject_area, 1.0f));
                    record_color_target_trace_component(
                        target_trace,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        trace_min_x,
                        trace_min_y,
                        trace_max_x,
                        trace_max_y,
                        trace_area,
                        sum_support,
                        peak_support,
                        &candidate,
                        accepted,
                        reject_reason,
                        reject_span,
                        reject_quality,
                        reject_ring_fraction,
                        reject_support_mass);
                }

                if (!accepted) {
                    if (seed_support >= strongest_reject_peak_support) {
                        strongest_reject_peak_support = seed_support;
                        strongest_reject_reason = reject_reason;
                        strongest_reject_area = reject_area;
                        strongest_reject_span = reject_span;
                        strongest_reject_ring_fraction = reject_ring_fraction;
                        strongest_reject_support_mass = reject_support_mass;
                        strongest_reject_quality = reject_quality;
                    }
                    if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_AREA) {
                        if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                    } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_RING) {
                        if (reject_ring_count_out != NULL) (*reject_ring_count_out)++;
                    } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_SUPPORT_MASS) {
                        if (reject_support_mass_count_out != NULL) (*reject_support_mass_count_out)++;
                    } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_QUALITY) {
                        if (reject_quality_count_out != NULL) (*reject_quality_count_out)++;
                    } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_COMMONNESS) {
                        if (reject_quality_count_out != NULL) (*reject_quality_count_out)++;
                    }
                    continue;
                }
                if (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY &&
                    (candidate.span > (float)small_target_limit_cells ||
                     candidate.area > (float)(small_target_limit_cells * small_target_limit_cells))) {
                    if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                    if (seed_support >= strongest_reject_peak_support) {
                        strongest_reject_peak_support = seed_support;
                        strongest_reject_reason = ANOMALY_COLOR_BLOB_REJECT_AREA;
                        strongest_reject_area = candidate.area;
                        strongest_reject_span = candidate.span;
                        strongest_reject_ring_fraction = 0.0f;
                        strongest_reject_support_mass = 0.0f;
                        strongest_reject_quality = candidate.quality;
                    }
                    continue;
                }

                insert_color_blob_candidate(
                        out_candidates,
                        out_count,
                        &candidate,
                        target_trace,
                        component_contains_target);
                anomaly_color_suppress_seed_region(
                    visited,
                    sg_w,
                    sg_h,
                    active_min_sx,
                    active_min_sy,
                    active_max_sx,
                    active_max_sy,
                    candidate.min_x,
                    candidate.min_y,
                    candidate.max_x,
                    candidate.max_y);
        }

        if (strongest_reject_reason_out != NULL) *strongest_reject_reason_out = strongest_reject_reason;
        if (strongest_reject_peak_support_out != NULL) *strongest_reject_peak_support_out = strongest_reject_peak_support;
        if (strongest_reject_area_out != NULL) *strongest_reject_area_out = strongest_reject_area;
        if (strongest_reject_span_out != NULL) *strongest_reject_span_out = strongest_reject_span;
        if (strongest_reject_ring_fraction_out != NULL) *strongest_reject_ring_fraction_out = strongest_reject_ring_fraction;
        if (strongest_reject_support_mass_out != NULL) *strongest_reject_support_mass_out = strongest_reject_support_mass;
        if (strongest_reject_quality_out != NULL) *strongest_reject_quality_out = strongest_reject_quality;
        return;
    }

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
            bool component_contains_target =
                target_trace != NULL &&
                target_trace->enabled &&
                target_trace->valid &&
                sx == target_trace->target_sx &&
                sy == target_trace->target_sy;

            while (head < tail) {
                int cur = queue[head++];
                int cx = cur % sg_w;
                int cy = cur / sg_w;
                float cur_support = color_support_map[cur];
                if (cur_support <= 0.0f) continue;
                if (target_trace != NULL &&
                    target_trace->enabled &&
                    target_trace->valid &&
                    cx == target_trace->target_sx &&
                    cy == target_trace->target_sy) {
                    component_contains_target = true;
                }

                area++;
                sum_support += (double)cur_support;
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;
                if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
                    roi_state != NULL &&
                    roi_state->color_raw_score != NULL) {
                    float cur_rarity = roi_state->color_raw_score[(size_t)cy * (size_t)sg_w + (size_t)cx];
                    float peak_rarity = roi_state->color_raw_score[(size_t)peak_y * (size_t)sg_w + (size_t)peak_x];
                    if (cur_rarity > peak_rarity ||
                        (fabsf(cur_rarity - peak_rarity) <= 0.000001f && cur_support > peak_support)) {
                        peak_support = cur_support;
                        peak_x = cx;
                        peak_y = cy;
                    }
                } else if (cur_support > peak_support) {
                    peak_support = cur_support;
                    peak_x = cx;
                    peak_y = cy;
                }

                float blob_mean = area > 0 ? (float)(sum_support / (double)area) : seed_support;
                float join_floor = fmaxf(0.25f, fminf(seed_support, blob_mean) * 0.45f);
                float peak_ratio = area <= 4
                    ? ANOMALY_COLOR_BLOB_JOIN_PEAK_RATIO_SOFT
                    : ANOMALY_COLOR_BLOB_JOIN_PEAK_RATIO_HARD;
                int blob_span_w = max_x - min_x + 1;
                int blob_span_h = max_y - min_y + 1;
                if (blob_span_w > compact_target_span_limit ||
                    blob_span_h > compact_target_span_limit ||
                    area > 6) {
                    join_floor = fmaxf(join_floor, peak_support * peak_ratio);
                    join_floor = fmaxf(join_floor, ANOMALY_COLOR_BLOB_JOIN_BASE_THRESH);
                }
                float band = fmaxf(0.35f, seed_support * 0.65f);
                bool fresh_large_blob_growth =
                    color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
                    (area >= 4 ||
                     blob_span_w > compact_target_span_limit ||
                     blob_span_h > compact_target_span_limit);
                if (fresh_large_blob_growth) {
                    // Once a fresh-mode blob has already grown beyond the
                    // compact target envelope, tighten further joins so thin
                    // bridges do not keep pulling it into a much larger field.
                    join_floor = fmaxf(join_floor, peak_support * 0.72f);
                    join_floor = fmaxf(join_floor, cur_support - 0.55f);
                    band = fminf(band, 0.55f);
                }
                for (int oy = -1; oy <= 1; oy++) {
                    for (int ox = -1; ox <= 1; ox++) {
                        if (ox == 0 && oy == 0) continue;
                        if (fresh_large_blob_growth && ox != 0 && oy != 0) continue;
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
                        if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY) {
                            float cohesion_similarity = anomaly_color_blob_neighbor_similarity(roi_state, (size_t)cur, nidx);
                            if (cohesion_similarity <= 0.0f) continue;
                            float current_cohesion = contrast_map != NULL ? contrast_map[cur] : 1.0f;
                            float neighbor_cohesion = contrast_map != NULL ? contrast_map[nidx] : 1.0f;
                            float min_cohesion = current_cohesion < neighbor_cohesion
                                ? current_cohesion
                                : neighbor_cohesion;
                            float similarity_floor = min_cohesion >= 0.85f ? 0.28f : 0.36f;
                            if (fresh_large_blob_growth) {
                                similarity_floor = fmaxf(similarity_floor, 0.48f);
                            }
                            if (cohesion_similarity < similarity_floor) continue;
                        }
                        visited[nidx] = 1u;
                        queue[tail++] = (int)nidx;
                    }
                }
            }

            if (area <= 0) continue;
            int component_count = tail;
            for (int qi = 0; qi < component_count; qi++) {
                visited[(size_t)queue[qi]] = 2u;
            }
            if (blob_examined_count_out != NULL) (*blob_examined_count_out)++;
            int component_span_w = max_x - min_x + 1;
            int component_span_h = max_y - min_y + 1;
            int component_span = component_span_w > component_span_h ? component_span_w : component_span_h;
            bool coarse_oversized =
                color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY &&
                (component_span > small_target_limit_cells || area > max_blob_area);
            anomaly_color_blob_candidate_t candidate;
            int reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
            float reject_area = (float)area;
            float reject_span = (float)component_span;
            float reject_ring_fraction = 0.0f;
            float reject_support_mass = 0.0f;
            float reject_quality = 0.0f;
            if (coarse_oversized) {
                if (coarse_oversized_count_out != NULL) (*coarse_oversized_count_out)++;
                reject_reason = ANOMALY_COLOR_BLOB_REJECT_AREA;
                if (component_contains_target && target_trace != NULL) {
                    record_color_target_trace_component(
                        target_trace,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        sum_support,
                        peak_support,
                        &candidate,
                        false,
                        reject_reason,
                        reject_span,
                        reject_quality,
                        reject_ring_fraction,
                        reject_support_mass);
                }
                if (peak_support >= strongest_reject_peak_support) {
                    strongest_reject_peak_support = peak_support;
                    strongest_reject_reason = reject_reason;
                    strongest_reject_area = reject_area;
                    strongest_reject_span = reject_span;
                    strongest_reject_ring_fraction = reject_ring_fraction;
                    strongest_reject_support_mass = reject_support_mass;
                    strongest_reject_quality = reject_quality;
                }
                if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                for (int qi = 0; qi < component_count; qi++) {
                    size_t cidx = (size_t)queue[qi];
                    if (visited[cidx] >= 2u) visited[cidx] = 1u;
                }
                continue;
            }
            if (dense_verify_component_count_out != NULL) (*dense_verify_component_count_out)++;
            bool accepted = build_color_blob_candidate(
                    cfg,
                    roi_state,
                    rgba,
                    rgba_stride,
                    color_frontend_mode,
                    color_support_map,
                    contrast_map,
                    sg_w,
                    sg_h,
                    frame_w,
                    frame_h,
                    roi_x0,
                    roi_y0,
                    roi_x1,
                    roi_y1,
                    sample_step,
                    area,
                    min_x,
                    min_y,
                    max_x,
                    max_y,
                    sum_support,
                    peak_support,
                    peak_x,
                    peak_y,
                    target_span_px,
                    small_target_limit_px,
                    target_span_cells,
                    max_blob_area,
                    compact_target_span_limit,
                    &candidate,
                    &reject_reason,
                    &reject_area,
                    &reject_span,
                    &reject_ring_fraction,
                    &reject_support_mass,
                    &reject_quality,
                    false);
            if (component_contains_target && target_trace != NULL) {
                record_color_target_trace_component(
                    target_trace,
                    sx,
                    sy,
                    peak_x,
                    peak_y,
                    min_x,
                    min_y,
                    max_x,
                    max_y,
                    area,
                    sum_support,
                    peak_support,
                    &candidate,
                    accepted,
                    reject_reason,
                    reject_span,
                    reject_quality,
                    reject_ring_fraction,
                    reject_support_mass);
            }
            if (!accepted) {
                if (peak_support >= strongest_reject_peak_support) {
                    strongest_reject_peak_support = peak_support;
                    strongest_reject_reason = reject_reason;
                    strongest_reject_area = reject_area;
                    strongest_reject_span = reject_span;
                    strongest_reject_ring_fraction = reject_ring_fraction;
                    strongest_reject_support_mass = reject_support_mass;
                    strongest_reject_quality = reject_quality;
                }
                if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_AREA) {
                    if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_RING) {
                    if (reject_ring_count_out != NULL) (*reject_ring_count_out)++;
                } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_SUPPORT_MASS) {
                    if (reject_support_mass_count_out != NULL) (*reject_support_mass_count_out)++;
                } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_QUALITY) {
                    if (reject_quality_count_out != NULL) (*reject_quality_count_out)++;
                } else if (reject_reason == ANOMALY_COLOR_BLOB_REJECT_COMMONNESS) {
                    if (reject_quality_count_out != NULL) (*reject_quality_count_out)++;
                }
                for (int qi = 0; qi < component_count; qi++) {
                    size_t cidx = (size_t)queue[qi];
                    if (visited[cidx] >= 2u) visited[cidx] = 1u;
                }
                continue;
            }
            if (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY &&
                (candidate.span > (float)small_target_limit_cells ||
                 candidate.area > (float)(small_target_limit_cells * small_target_limit_cells))) {
                if (reject_area_count_out != NULL) (*reject_area_count_out)++;
                if (peak_support >= strongest_reject_peak_support) {
                    strongest_reject_peak_support = peak_support;
                    strongest_reject_reason = ANOMALY_COLOR_BLOB_REJECT_AREA;
                    strongest_reject_area = candidate.area;
                    strongest_reject_span = candidate.span;
                    strongest_reject_ring_fraction = 0.0f;
                    strongest_reject_support_mass = 0.0f;
                    strongest_reject_quality = candidate.quality;
                }
                for (int qi = 0; qi < component_count; qi++) {
                    size_t cidx = (size_t)queue[qi];
                    if (visited[cidx] >= 2u) visited[cidx] = 1u;
                }
                continue;
            }
            for (int qi = 0; qi < component_count; qi++) {
                size_t cidx = (size_t)queue[qi];
                if (visited[cidx] >= 2u) visited[cidx] = 1u;
            }
            insert_color_blob_candidate(
                    out_candidates,
                    out_count,
                    &candidate,
                    target_trace,
                    component_contains_target);
        }
    }

    if (strongest_reject_reason_out != NULL) *strongest_reject_reason_out = strongest_reject_reason;
    if (strongest_reject_peak_support_out != NULL) *strongest_reject_peak_support_out = strongest_reject_peak_support;
    if (strongest_reject_area_out != NULL) *strongest_reject_area_out = strongest_reject_area;
    if (strongest_reject_span_out != NULL) *strongest_reject_span_out = strongest_reject_span;
    if (strongest_reject_ring_fraction_out != NULL) *strongest_reject_ring_fraction_out = strongest_reject_ring_fraction;
    if (strongest_reject_support_mass_out != NULL) *strongest_reject_support_mass_out = strongest_reject_support_mass;
    if (strongest_reject_quality_out != NULL) *strongest_reject_quality_out = strongest_reject_quality;
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
        float        delta_mean,
        float        delta_norm,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        uint8_t     *visited,
        int         *queue,
        float       *thermal_value_map,
        float       *candidate_seed_map,
        anomaly_thermal_blob_candidate_t *out_candidates,
        int         *out_count,
        anomaly_thermal_target_trace_t *target_trace) {
    (void)delta_norm;
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
        target_trace->local_peak_radius = 3;
        target_trace->local_peak_sx = -1;
        target_trace->local_peak_sy = -1;
        target_trace->local_peak_delta = -1.0f;
        target_trace->local_peak_score = -1.0f;
        target_trace->local_peak_distance = -1.0f;
        target_trace->local_peak_raw_sx = -1;
        target_trace->local_peak_raw_sy = -1;
        target_trace->local_peak_raw_delta = -1.0f;
        target_trace->local_peak_raw_score = -1.0f;
        target_trace->local_peak_raw_distance = -1.0f;
        target_trace->target_temporal_margin = -999.0f;
        target_trace->target_spatial_abs_delta = -1.0f;
        target_trace->target_spatial_std = -1.0f;
        target_trace->target_spatial_score = -1.0f;
        target_trace->local_peak_raw_temporal_margin = -999.0f;
        target_trace->local_peak_raw_spatial_abs_delta = -1.0f;
        target_trace->local_peak_raw_spatial_std = -1.0f;
        target_trace->local_peak_raw_spatial_score = -1.0f;
        target_trace->local_window_weighted_centroid_dx = -1.0f;
        target_trace->local_window_weighted_centroid_dy = -1.0f;
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_NO_HOT_PEAK;
        target_trace->micro_candidate_peak_sx = -1;
        target_trace->micro_candidate_peak_sy = -1;
        target_trace->micro_candidate_peak_delta = -1.0f;
        target_trace->micro_candidate_peak_score = -1.0f;
        target_trace->micro_candidate_prominence = -1.0f;
        target_trace->micro_candidate_ring_mean = -1.0f;
        target_trace->micro_candidate_ring_hot_fraction = -1.0f;
        target_trace->micro_candidate_compactness = -1.0f;
        target_trace->micro_candidate_centroid_dx = -9.0f;
        target_trace->micro_candidate_centroid_dy = -9.0f;
        target_trace->micro_candidate_centroid_offset = -1.0f;
        target_trace->micro_candidate_one_sided_support = -1.0f;
        target_trace->micro_candidate_distance_to_debug_target = -1.0f;
        target_trace->suppressor_sx = -1;
        target_trace->suppressor_sy = -1;
        target_trace->component_seed_x = -1;
        target_trace->component_seed_y = -1;
        target_trace->component_peak_x = -1;
        target_trace->component_peak_y = -1;
        target_trace->nearby_rejected_component_gate = ANOMALY_THERMAL_TARGET_GATE_NONE;
        target_trace->nearby_rejected_component_seed_x = -1;
        target_trace->nearby_rejected_component_seed_y = -1;
        target_trace->nearby_rejected_component_peak_x = -1;
        target_trace->nearby_rejected_component_peak_y = -1;
        target_trace->nearby_rejected_component_distance = -1.0f;
        target_trace->nms_conflict_rank = -1;
        target_trace->nms_conflict_sample_x = -1;
        target_trace->nms_conflict_sample_y = -1;
        target_trace->pre_cap_rank = -1;
        target_trace->pre_cap_candidate_count = -1;
        target_trace->pre_cap_limit = ANOMALY_MAX_THERMAL_CANDIDATES;
        target_trace->pre_cap_retention_rank = -1.0f;
        target_trace->extracted_rank = -1;
        target_trace->winning_rank = -1;
        target_trace->provisional_candidate_index = -1;
        target_trace->provisional_score_floor = -1.0f;
        target_trace->provisional_final_score = -1.0f;
        target_trace->provisional_candidate_rank = -1.0f;
        target_trace->provisional_selected_rank = -1;
        target_trace->provisional_selected_score = -1.0f;
        target_trace->matched_track_index = -1;
        target_trace->matched_track_id = -1;
        target_trace->matched_track_hit_count = -1;
        target_trace->matched_track_miss_count = -1;
        target_trace->matched_track_hold_count = -1;
        target_trace->movement_motion_support = -1.0f;
        target_trace->movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
        target_trace->local_peak_movement_residual_px = -1.0f;
        target_trace->local_peak_movement_independent_score = -1.0f;
        target_trace->local_peak_movement_confidence = -1.0f;
        target_trace->local_peak_movement_motion_support = -1.0f;
        target_trace->local_peak_movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
        target_trace->movement_rescue_reject_reason =
            ANOMALY_MOVEMENT_SHADOW_REJECT_NO_MOVEMENT_TILE;
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
        target_trace->target_raw_delta = bg_valid
            ? thermal_delta_from_maps(
                thermal_delta_map,
                bg_luma,
                sg_luma,
                tidx,
                black_hot)
            : target_trace->target_score;
        target_trace->target_raw_score = target_trace->target_score;
        target_trace->target_temporal_margin = bg_valid
            ? (target_trace->target_raw_delta - delta_mean)
            : target_trace->target_raw_score;
        compute_thermal_spatial_probe_at_sample(
                sg_luma,
                sg_w,
                sg_h,
                target_trace->target_sx,
                target_trace->target_sy,
                sample_step,
                black_hot,
                &target_trace->target_spatial_abs_delta,
                &target_trace->target_spatial_std,
                &target_trace->target_spatial_score);
        target_trace->hot_eligible = target_trace->target_delta > 0.0f;
        target_trace->stage = target_trace->hot_eligible
            ? ANOMALY_THERMAL_TARGET_STAGE_MERGED_INTO_COMPONENT
            : ANOMALY_THERMAL_TARGET_STAGE_NOT_HOT;
        target_trace->local_max = true;
        int local_peak_radius = target_trace->local_peak_radius > 0
            ? target_trace->local_peak_radius
            : 3;
        double raw_delta_sum = 0.0;
        double weighted_dx_sum = 0.0;
        double weighted_dy_sum = 0.0;
        double weight_sum = 0.0;
        for (int ny = target_trace->target_sy - local_peak_radius;
             ny <= target_trace->target_sy + local_peak_radius;
             ny++) {
            if (ny < 0 || ny >= sg_h) continue;
            for (int nx = target_trace->target_sx - local_peak_radius;
                 nx <= target_trace->target_sx + local_peak_radius;
                 nx++) {
                if (nx < 0 || nx >= sg_w) continue;
                int ddx = nx - target_trace->target_sx;
                int ddy = ny - target_trace->target_sy;
                if (ddx * ddx + ddy * ddy > local_peak_radius * local_peak_radius) continue;
                size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                float raw_delta = bg_valid
                    ? thermal_delta_from_maps(
                        thermal_delta_map,
                        bg_luma,
                        sg_luma,
                        nidx,
                        black_hot)
                    : thermal_score_map[nidx];
                float nscore = thermal_score_map[nidx];
                target_trace->local_window_sample_count++;
                raw_delta_sum += (double)raw_delta;
                if (raw_delta > 0.0f) {
                    double w = (double)raw_delta;
                    weight_sum += w;
                    weighted_dx_sum += w * (double)ddx;
                    weighted_dy_sum += w * (double)ddy;
                }
                if (raw_delta > target_trace->local_peak_raw_delta ||
                    (raw_delta == target_trace->local_peak_raw_delta &&
                     nscore > target_trace->local_peak_raw_score)) {
                    target_trace->local_peak_raw_sx = nx;
                    target_trace->local_peak_raw_sy = ny;
                    target_trace->local_peak_raw_delta = raw_delta;
                    target_trace->local_peak_raw_score = nscore;
                    target_trace->local_peak_raw_distance =
                        sqrtf((float)(ddx * ddx + ddy * ddy));
                }
                float ndelta = thermal_value_map[nidx];
                if (ndelta <= 0.0f) continue;
                target_trace->local_window_hot_count++;
                if (ndelta > target_trace->local_peak_delta ||
                    (ndelta == target_trace->local_peak_delta &&
                     nscore > target_trace->local_peak_score)) {
                    target_trace->local_peak_sx = nx;
                    target_trace->local_peak_sy = ny;
                    target_trace->local_peak_delta = ndelta;
                    target_trace->local_peak_score = nscore;
                    target_trace->local_peak_distance =
                        sqrtf((float)(ddx * ddx + ddy * ddy));
                }
            }
        }
        target_trace->local_window_raw_delta_sum = (float)raw_delta_sum;
        if (target_trace->local_window_sample_count > 0) {
            target_trace->local_window_raw_delta_mean =
                (float)(raw_delta_sum / (double)target_trace->local_window_sample_count);
        }
        if (weight_sum > 0.0) {
            target_trace->local_window_weighted_centroid_dx =
                (float)(weighted_dx_sum / weight_sum);
            target_trace->local_window_weighted_centroid_dy =
                (float)(weighted_dy_sum / weight_sum);
        }
        if (target_trace->local_peak_raw_sx >= 0 && target_trace->local_peak_raw_sy >= 0) {
            target_trace->local_peak_raw_temporal_margin = bg_valid
                ? (target_trace->local_peak_raw_delta - delta_mean)
                : target_trace->local_peak_raw_score;
            compute_thermal_spatial_probe_at_sample(
                    sg_luma,
                    sg_w,
                    sg_h,
                    target_trace->local_peak_raw_sx,
                    target_trace->local_peak_raw_sy,
                    sample_step,
                    black_hot,
                    &target_trace->local_peak_raw_spatial_abs_delta,
                    &target_trace->local_peak_raw_spatial_std,
                    &target_trace->local_peak_raw_spatial_score);
        }
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
        evaluate_thermal_micro_candidate_shadow(
                thermal_score_map,
                thermal_delta_map,
                bg_luma,
                sg_luma,
                sg_w,
                sg_h,
                bg_valid,
                black_hot,
                thermal_min_delta,
                thermal_value_map,
                target_trace);
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
                target_trace->local_peak_raw_sx == sx &&
                target_trace->local_peak_raw_sy == sy) {
                target_trace->local_peak_is_component_seed = true;
            }
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
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_MAX_AREA,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        0.0f);
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_MAX_AREA;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid && ring_hot_fraction >= 0.22f) {
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_RING_HOT,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        0.0f);
                if (target_trace != NULL && target_trace->enabled && target_trace->valid &&
                    target_trace->component_seed_x == sx && target_trace->component_seed_y == sy) {
                    target_trace->component_rejected = true;
                    target_trace->rejection_gate = ANOMALY_THERMAL_TARGET_GATE_RING_HOT;
                    target_trace->stage = ANOMALY_THERMAL_TARGET_STAGE_REJECTED_BY_GATE;
                }
                continue;
            }
            if (bg_valid && max_side_hot_fraction >= 0.60f) {
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_SIDE_HOT,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        0.0f);
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
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_SUPPORT_MASS,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        0.0f);
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
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_SUPPORT_NEAR,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        0.0f);
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
                record_thermal_target_rejected_component_probe(
                        target_trace,
                        ANOMALY_THERMAL_TARGET_GATE_ZERO_QUALITY,
                        sx,
                        sy,
                        peak_x,
                        peak_y,
                        min_x,
                        min_y,
                        max_x,
                        max_y,
                        area,
                        span,
                        fill,
                        peak_delta,
                        mean_delta,
                        quality);
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

static void evaluate_thermal_micro_candidate_shadow(
        const float *thermal_score_map,
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int sg_w,
        int sg_h,
        bool bg_valid,
        bool black_hot,
        float thermal_min_delta,
        const float *thermal_value_map,
        anomaly_thermal_target_trace_t *target_trace) {
    if (thermal_score_map == NULL || thermal_value_map == NULL ||
        target_trace == NULL || !target_trace->enabled || !target_trace->valid ||
        sg_w <= 0 || sg_h <= 0) {
        return;
    }

    target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_NO_HOT_PEAK;
    target_trace->micro_candidate_peak_sx = target_trace->local_peak_sx;
    target_trace->micro_candidate_peak_sy = target_trace->local_peak_sy;
    target_trace->micro_candidate_peak_delta = -1.0f;
    target_trace->micro_candidate_peak_score = -1.0f;
    target_trace->micro_candidate_prominence = -1.0f;
    target_trace->micro_candidate_ring_mean = -1.0f;
    target_trace->micro_candidate_ring_hot_fraction = -1.0f;
    target_trace->micro_candidate_compactness = -1.0f;
    target_trace->micro_candidate_centroid_dx = -9.0f;
    target_trace->micro_candidate_centroid_dy = -9.0f;
    target_trace->micro_candidate_centroid_offset = -1.0f;
    target_trace->micro_candidate_one_sided_support = -1.0f;
    target_trace->micro_candidate_distance_to_debug_target = -1.0f;

    int peak_sx = target_trace->local_peak_sx;
    int peak_sy = target_trace->local_peak_sy;
    if (peak_sx < 0 || peak_sy < 0 || peak_sx >= sg_w || peak_sy >= sg_h) return;

    size_t peak_idx = (size_t)peak_sy * (size_t)sg_w + (size_t)peak_sx;
    float peak_value = thermal_value_map[peak_idx];
    if (peak_value <= 0.0f) return;

    float peak_delta = bg_valid
        ? thermal_delta_from_maps(thermal_delta_map, bg_luma, sg_luma, peak_idx, black_hot)
        : peak_value;
    float peak_score = thermal_score_map[peak_idx];
    target_trace->micro_candidate_peak_delta = peak_delta;
    target_trace->micro_candidate_peak_score = peak_score;
    target_trace->micro_candidate_distance_to_debug_target =
        sqrtf((float)((peak_sx - target_trace->target_sx) * (peak_sx - target_trace->target_sx) +
                      (peak_sy - target_trace->target_sy) * (peak_sy - target_trace->target_sy)));

    if (target_trace->micro_candidate_distance_to_debug_target > 3.05f) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_TOO_FAR;
        return;
    }

    bool strict_local_max = true;
    for (int ny = peak_sy - 1; ny <= peak_sy + 1 && strict_local_max; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = peak_sx - 1; nx <= peak_sx + 1; nx++) {
            if (nx < 0 || nx >= sg_w) continue;
            if (nx == peak_sx && ny == peak_sy) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            float nvalue = thermal_value_map[nidx];
            if (nvalue <= 0.0f) continue;
            float ndelta = bg_valid
                ? thermal_delta_from_maps(thermal_delta_map, bg_luma, sg_luma, nidx, black_hot)
                : nvalue;
            float nscore = thermal_score_map[nidx];
            if (ndelta > peak_delta || (ndelta == peak_delta && nscore > peak_score)) {
                strict_local_max = false;
                break;
            }
        }
    }

    double core_sum = 0.0;
    double weighted_dx_sum = 0.0;
    double weighted_dy_sum = 0.0;
    double weight_sum = 0.0;
    int sample_count = 0;
    int hot_count = 0;
    for (int ny = peak_sy - 1; ny <= peak_sy + 1; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = peak_sx - 1; nx <= peak_sx + 1; nx++) {
            if (nx < 0 || nx >= sg_w) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            float nvalue = thermal_value_map[nidx];
            float ndelta = bg_valid
                ? thermal_delta_from_maps(thermal_delta_map, bg_luma, sg_luma, nidx, black_hot)
                : nvalue;
            sample_count++;
            if (nvalue > 0.0f) hot_count++;
            if (ndelta > 0.0f) {
                core_sum += (double)ndelta;
                weight_sum += (double)ndelta;
                weighted_dx_sum += (double)(nx - peak_sx) * (double)ndelta;
                weighted_dy_sum += (double)(ny - peak_sy) * (double)ndelta;
            }
        }
    }

    double ring_sum = 0.0;
    int ring_count = 0;
    int ring_hot = 0;
    int side_total[4] = {0, 0, 0, 0};
    int side_hot[4] = {0, 0, 0, 0};
    for (int ny = peak_sy - 2; ny <= peak_sy + 2; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = peak_sx - 2; nx <= peak_sx + 2; nx++) {
            if (nx < 0 || nx >= sg_w) continue;
            int dx = nx - peak_sx;
            int dy = ny - peak_sy;
            int cheb = abs(dx) > abs(dy) ? abs(dx) : abs(dy);
            if (cheb != 2) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            float nvalue = thermal_value_map[nidx];
            float ndelta = bg_valid
                ? thermal_delta_from_maps(thermal_delta_map, bg_luma, sg_luma, nidx, black_hot)
                : nvalue;
            ring_count++;
            ring_sum += (double)ndelta;
            bool is_hot = nvalue > 0.0f;
            if (is_hot) ring_hot++;
            if (dx < 0) {
                side_total[0]++;
                if (is_hot) side_hot[0]++;
            }
            if (dx > 0) {
                side_total[1]++;
                if (is_hot) side_hot[1]++;
            }
            if (dy < 0) {
                side_total[2]++;
                if (is_hot) side_hot[2]++;
            }
            if (dy > 0) {
                side_total[3]++;
                if (is_hot) side_hot[3]++;
            }
        }
    }

    float ring_mean = ring_count > 0 ? (float)(ring_sum / (double)ring_count) : 0.0f;
    float ring_hot_fraction = ring_count > 0 ? ((float)ring_hot / (float)ring_count) : 0.0f;
    float compactness = core_sum > 0.0 ? peak_delta / (float)core_sum : 0.0f;
    float centroid_dx = weight_sum > 0.0 ? (float)(weighted_dx_sum / weight_sum) : 0.0f;
    float centroid_dy = weight_sum > 0.0 ? (float)(weighted_dy_sum / weight_sum) : 0.0f;
    float centroid_offset = sqrtf(centroid_dx * centroid_dx + centroid_dy * centroid_dy);
    float one_sided_support = 0.0f;
    for (int si = 0; si < 4; si++) {
        if (side_total[si] <= 0) continue;
        float side_fraction = (float)side_hot[si] / (float)side_total[si];
        if (side_fraction > one_sided_support) one_sided_support = side_fraction;
    }

    target_trace->micro_candidate_hot_count = hot_count;
    target_trace->micro_candidate_sample_count = sample_count;
    target_trace->micro_candidate_prominence = peak_delta - ring_mean;
    target_trace->micro_candidate_ring_mean = ring_mean;
    target_trace->micro_candidate_ring_hot_fraction = ring_hot_fraction;
    target_trace->micro_candidate_compactness = compactness;
    target_trace->micro_candidate_centroid_dx = centroid_dx;
    target_trace->micro_candidate_centroid_dy = centroid_dy;
    target_trace->micro_candidate_centroid_offset = centroid_offset;
    target_trace->micro_candidate_one_sided_support = one_sided_support;

    if (!strict_local_max) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_NOT_LOCAL_MAX;
    } else if (target_trace->micro_candidate_prominence < fmaxf(1.4f, 0.25f * thermal_min_delta)) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_WEAK_PROMINENCE;
    } else if (ring_hot_fraction > 0.30f) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_RING_HOT;
    } else if (hot_count <= 0 || hot_count > 6) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_TOO_MANY_HOT;
    } else if (compactness < 0.22f) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_LOW_COMPACTNESS;
    } else if (one_sided_support > 0.62f) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_EDGE_LIKE;
    } else if (centroid_offset > 0.95f) {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_CENTROID_DRIFT;
    } else {
        target_trace->micro_candidate_reject_reason = ANOMALY_THERMAL_MICRO_REJECT_NONE;
        target_trace->micro_candidate_would_create = true;
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
        width <= 1 || height <= 1 || !anomaly_registration_model_valid(model)) {
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
            anomaly_registration_apply_point(model, x_norm, y_norm, &prev_x_norm, &prev_y_norm);
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
    return anomaly_linear_solve_6x6(ata, atb, out_affine);
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
            int score = anomaly_registration_feature_score(luma, w, h, x, y);
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
    if (state == NULL || state->thermal.thermal_target_persist == NULL ||
        state->thermal.thermal_target_persist_w != sg_w || state->thermal.thermal_target_persist_h != sg_h ||
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
            float value = state->thermal.thermal_target_persist[y * sg_w + x];
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

static bool target_tracks_registration_model_valid(const void *registration) {
    return anomaly_registration_model_valid((const anomaly_registration_model_t *)registration);
}

static bool target_tracks_registration_invert_point(
        const void *registration,
        float x,
        float y,
        float *out_x,
        float *out_y) {
    return anomaly_registration_invert_point(
            (const anomaly_registration_model_t *)registration,
            x,
            y,
            out_x,
            out_y);
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
    anomaly_registration_model_t model = anomaly_registration_model_make(
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
                    int feature = anomaly_registration_feature_score(curr_luma, motion_w, motion_h, cx, cy);
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

    float scale = anomaly_registration_model_scale(&model);
    model.fit_min_scale = scale;
    model.fit_max_scale = scale;
    bool motion_too_large = anomaly_registration_motion_exceeds_search(&model, width, height, 0.85f);
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
    anomaly_registration_model_t model = anomaly_registration_model_make(
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
    bool motion_too_large = anomaly_registration_motion_exceeds_search(&model, width, height, 0.85f);
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
    int mode = anomaly_registration_normalize_mode(cfg);
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

typedef struct {
    bool  valid;
    int   sx;
    int   sy;
    int   x;
    int   y;
    int   track_index;
    float score;
    bool  salience_boosted;
    bool  independent_motion_boosted;
    bool  global_motion_rejected;
} anomaly_revisit_confirmation_t;

static anomaly_revisit_confirmation_t find_target_revisit_confirmation(
        const anomaly_state_t             *state,
        const anomaly_config_t            *cfg,
        const anomaly_motion_movement_snapshot_t *movement_snapshot,
        int                                roi_x0,
        int                                roi_y0,
        int                                roi_x1,
        int                                roi_y1,
        int                                frame_width,
        int                                frame_height,
        int                                sample_step,
        int                                sg_w,
        int                                sg_h,
        const uint8_t                     *refresh_mask,
        const float                       *sg_luma,
        const float                       *ii_sum,
        const float                       *ii_sum2,
        const float                       *thermal_score_map,
        const float                       *motion_support_map,
        int                                black_hot,
        float                              thermal_min_delta,
        float                              delta_mean,
        float                              delta_norm,
        int                                spatial_radius) {
    anomaly_revisit_confirmation_t best;
    memset(&best, 0, sizeof(best));
    best.track_index = -1;
    best.score = -1.0f;
    if (state == NULL || cfg == NULL || refresh_mask == NULL || sg_luma == NULL ||
        ii_sum == NULL || ii_sum2 == NULL || sg_w <= 0 || sg_h <= 0) {
        return best;
    }
    float frame_w_norm = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float frame_h_norm = (float)(frame_height > 1 ? frame_height - 1 : 1);
    float min_score = cfg->score_threshold - ANOMALY_TARGET_REVISIT_RELAXED_THRESHOLD;
    if (min_score < 0.0f) min_score = 0.0f;

    for (int sy = 0; sy < sg_h; sy++) {
        int y = roi_y0 + sy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (refresh_mask[idx] == 0u) continue;
            int x = roi_x0 + sx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            float x_norm = clamp01f((float)x / frame_w_norm);
            float y_norm = clamp01f((float)y / frame_h_norm);
            int track_idx = -1;
            if (!anomaly_target_revisit_point_inside_gate(
                    state,
                    x_norm,
                    y_norm,
                    cfg->min_hits,
                    &track_idx,
                    NULL)) {
                continue;
            }

            float thermal_score = thermal_score_map != NULL ? thermal_score_map[idx] : -1.0f;
            int wx0 = sx - spatial_radius; if (wx0 < 0) wx0 = 0;
            int wx1 = sx + spatial_radius; if (wx1 >= sg_w) wx1 = sg_w - 1;
            int wy0 = sy - spatial_radius; if (wy0 < 0) wy0 = 0;
            int wy1 = sy + spatial_radius; if (wy1 >= sg_h) wy1 = sg_h - 1;
            int n = (wx1 - wx0 + 1) * (wy1 - wy0 + 1);
            float mean = ii_query(ii_sum, sg_w, wx0, wy0, wx1, wy1) / (float)n;
            float sum2 = ii_query(ii_sum2, sg_w, wx0, wy0, wx1, wy1);
            float std = sqrtf(fmaxf(sum2 / (float)n - mean * mean, 1.0f));
            float lum = sg_luma[idx];
            float abs_delta = black_hot ? (mean - lum) : (lum - mean);
            float spatial_score = -1.0f;
            if (abs_delta >= thermal_min_delta) {
                spatial_score = abs_delta / std;
            }
            float score = thermal_score > spatial_score ? thermal_score : spatial_score;
            bool salience_boosted = false;
            if (thermal_score > 0.0f && spatial_score > 0.0f) {
                float agreement = fminf(thermal_score, spatial_score);
                score += 0.18f * clampf((agreement - min_score) / 1.5f, 0.0f, 1.0f);
                salience_boosted = true;
            }
            if (motion_support_map != NULL && motion_support_map[idx] > 0.0f) {
                score += 0.12f * clampf(motion_support_map[idx], 0.0f, 1.0f);
            }

            bool independent_motion_boosted = false;
            bool global_motion_rejected = false;
            anomaly_debug_movement_tile_t tile;
            if (anomaly_motion_estimator_query_snapshot_at_norm(movement_snapshot, x_norm, y_norm, &tile)) {
                const anomaly_target_track_t *matched_track =
                    (track_idx >= 0 && track_idx < ANOMALY_MAX_TARGET_TRACKS)
                        ? &state->target_tracks[track_idx]
                        : NULL;
                float independent_score = anomaly_motion_estimator_tile_independent_score(&tile);
                bool independent = anomaly_motion_estimator_tile_is_independent(
                        &tile,
                        independent_score);
                if (independent) {
                    score += 0.22f * independent_score;
                    independent_motion_boosted = true;
                } else if (anomaly_target_revisit_should_apply_global_motion_penalty(
                                   matched_track,
                                   &tile,
                                   movement_snapshot != NULL ? movement_snapshot->parallax_load : 0.0f,
                                   score,
                                   cfg->score_threshold)) {
                    score -= 0.30f;
                    global_motion_rejected = true;
                }
            }
            if (score < min_score) continue;
            if (!best.valid || score > best.score) {
                best.valid = true;
                best.sx = sx;
                best.sy = sy;
                best.x = x;
                best.y = y;
                best.track_index = track_idx;
                best.score = score;
                best.salience_boosted = salience_boosted;
                best.independent_motion_boosted = independent_motion_boosted;
                best.global_motion_rejected = global_motion_rejected;
            }
        }
    }
    return best;
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
        anomaly_registration_health_t     registration_health,
        int                               min_hits) {
    anomaly_roi_state_t *roi_state = &state->roi_state;
    if (!anomaly_roi_state_ensure_pixel_capacity(roi_state, (size_t)sg_w * (size_t)sg_h)) {
        anomaly_roi_state_clear(roi_state);
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
    int cell_span = anomaly_scan_planner_roi_grid_cell_span(sample_step);
    roi_state->cell_size_px = ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX;
    roi_state->cell_cols = (sg_w + cell_span - 1) / cell_span;
    roi_state->cell_rows = (sg_h + cell_span - 1) / cell_span;
    float reg_conf = anomaly_registration_health_confidence(registration_health);
    for (size_t i = 0; i < sg_count; i++) {
        roi_state->last_luma[i] = sg_luma[i];
        roi_state->thermal_score[i] = saliency_spatial_map != NULL ? saliency_spatial_map[i] : -1.0f;
        float temporal_score = -1.0f;
        if (bg_valid && state->thermal.bg_luma != NULL) {
            float delta = thermal_delta_from_maps(
                thermal_delta_map,
                state->thermal.bg_luma,
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
    anomaly_roi_state_summarize_cells(
            roi_state,
            saliency_motion_map,
            ANOMALY_ROI_REALTIME_CARRY_EXPIRY,
            anomaly_registration_health_confidence(registration_health));
    anomaly_target_revisit_annotate_roi_cells(roi_state, state, min_hits);
}

static bool update_roi_state_selective_refresh(
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
        anomaly_registration_health_t     registration_health,
        const int                        *prev_lookup,
        const uint8_t                    *refresh_mask,
        int                               min_hits) {
    if (state == NULL || prev_lookup == NULL || refresh_mask == NULL) return false;
    anomaly_roi_state_t *roi_state = &state->roi_state;
    if (!roi_state->valid ||
        roi_state->sample_step != sample_step ||
        roi_state->width != sg_w || roi_state->height != sg_h ||
        roi_state->last_luma == NULL ||
        roi_state->thermal_score == NULL ||
        roi_state->temporal_score == NULL ||
        roi_state->valid_mask == NULL ||
        roi_state->coverage_age == NULL ||
        !anomaly_roi_state_ensure_pixel_capacity(roi_state, (size_t)sg_w * (size_t)sg_h)) {
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
    if (!anomaly_scratch_ensure_prev_roi_snapshot_capacity(state, sg_count)) {
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
    int cell_span = anomaly_scan_planner_roi_grid_cell_span(sample_step);
    roi_state->cell_size_px = ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX;
    roi_state->cell_cols = (sg_w + cell_span - 1) / cell_span;
    roi_state->cell_rows = (sg_h + cell_span - 1) / cell_span;

    float reg_conf = anomaly_registration_health_confidence(registration_health);
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (refresh_mask[idx] != 0u) {
                roi_state->last_luma[idx] = sg_luma[idx];
                roi_state->thermal_score[idx] =
                    saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                float temporal_score = -1.0f;
                if (bg_valid && state->thermal.bg_luma != NULL) {
                    float delta = thermal_delta_from_maps(
                        thermal_delta_map,
                        state->thermal.bg_luma,
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

            bool carried = false;
            int prev_idx = prev_lookup[idx];
            if (prev_idx >= 0 &&
                prev_idx < (int)((size_t)prev_width * (size_t)prev_height) &&
                prev_valid_mask[prev_idx]) {
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

    anomaly_roi_state_summarize_cells(
            roi_state,
            saliency_motion_map,
            ANOMALY_ROI_REALTIME_CARRY_EXPIRY,
            anomaly_registration_health_confidence(registration_health));
    anomaly_target_revisit_annotate_roi_cells(roi_state, state, min_hits);
    return true;
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
        anomaly_roi_state_clear(roi_state);
        return;
    }
    size_t pixel_count = (size_t)roi_state->width * (size_t)roi_state->height;
    float reg_conf = anomaly_registration_health_confidence(registration_health);
    for (size_t i = 0; i < pixel_count; i++) {
        roi_state->fresh_mask[i] = 0;
        roi_state->carried_mask[i] = roi_state->valid_mask[i] ? 1u : 0u;
        roi_state->new_exposed_mask[i] = 0;
        if (roi_state->valid_mask[i] && roi_state->coverage_age[i] < 255u) {
            roi_state->coverage_age[i] += 1u;
        }
        roi_state->reg_confidence[i] = reg_conf;
    }
    anomaly_roi_state_summarize_cells(
            roi_state,
            NULL,
            ANOMALY_ROI_REALTIME_CARRY_EXPIRY,
            anomaly_registration_health_confidence(registration_health));
    anomaly_target_revisit_annotate_roi_cells(roi_state, state, ANOMALY_DEFAULT_MIN_HITS);
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

    probe_out->thermal_min_delta = anomaly_runtime_effective_thermal_min_delta(cfg);

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

    int sample_step = anomaly_runtime_effective_sample_step(cfg, width, height);
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
                     && state->thermal.bg_luma != NULL
                     && state->thermal.bg_sg_w == sg_w
                     && state->thermal.bg_sg_h == sg_h
                     && state->thermal.bg_warmup >= ANOMALY_THERMAL_BG_WARMUP);
    probe_out->bg_ready = bg_ready;
    probe_out->used_temporal_score = bg_ready;
    probe_out->effective_score = probe_out->spatial_score;

    if (bg_ready) {
        double sum_d = 0.0, sum_d2 = 0.0;
        int cnt_d = 0;
        for (int i = 0; i < sg_w * sg_h; i++) {
            float d = black_hot
                      ? (state->thermal.bg_luma[i] - (float)sg_luma[i])
                      : ((float)sg_luma[i] - state->thermal.bg_luma[i]);
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
                      ? (state->thermal.bg_luma[sy * sg_w + sx] - (float)lum)
                      : ((float)lum - state->thermal.bg_luma[sy * sg_w + sx]);
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
        anomaly_thermal_state_init(&state->thermal);
        state->next_target_track_id = 1;
    }
}

void anomaly_state_reset(anomaly_state_t *state) {
    if (state == NULL) return;
    state->frame_counter = 0;
    state->color_phase_counter = 0;
    state->last_full_refresh_source_ts_us = 0;
    state->last_full_refresh_frame_counter = 0;
    state->last_color_full_scan_coarse_count = 0;
    state->fresh_color_distinctness_ratio = anomaly_color_default_fresh_distinctness_ratio();
    state->adaptive_effective_stride = 0;
    state->adaptive_stable_frames = 0;
    state->adaptive_drop_hold_frames = 0;
    state->adaptive_target_rich_frames = 0;
    state->adaptive_motion_load_ema = 0.0f;
    state->adaptive_last_source_ts_us = 0;
    state->adaptive_frame_interval_ema_us = 0.0f;
    state->adaptive_reason_flags = 0u;
    state->color_history_recovery_frames = 0;
    memset(state->acc_cx,     0, sizeof(state->acc_cx));
    memset(state->acc_cy,     0, sizeof(state->acc_cy));
    memset(state->acc_hits,   0, sizeof(state->acc_hits));
    memset(state->acc_hold,   0, sizeof(state->acc_hold));
    memset(state->acc_presence_mask, 0, sizeof(state->acc_presence_mask));
    memset(state->acc_active, 0, sizeof(state->acc_active));
    anomaly_frame_history_clear(state);
    state->cached_registration_valid = false;
    state->cached_registration_reuse_budget = 0;
    if (state->motion_persist != NULL) {
        free(state->motion_persist);
        state->motion_persist = NULL;
    }
    state->motion_persist_w = 0;
    state->motion_persist_h = 0;
    anomaly_thermal_state_reset(&state->thermal);
    state->publish_hold_frames = 0;
    state->publish_stable_frames = 0;
    free(state->color_recent_hist);
    state->color_recent_hist = NULL;
    state->color_recent_hist_bins = 0;
    free(state->scratch_color_hist);
    state->scratch_color_hist = NULL;
    state->scratch_color_hist_bins = 0;
    anomaly_roi_state_release(&state->roi_state);
    anomaly_target_tracks_clear_all(state);
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
    free(state->scratch_prev_sample_lookup);
    state->scratch_prev_sample_lookup = NULL;
    state->scratch_prev_sample_lookup_capacity = 0;
    free(state->scratch_i32);
    state->scratch_i32 = NULL;
    state->scratch_i32_capacity = 0;
}

void anomaly_state_cleanup(anomaly_state_t *state) {
    anomaly_state_reset(state);
}

static bool anomaly_color_fast_hold_frame_eligible(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        bool                    color_algorithm_configured,
        bool                    fixed_full_refresh_cadence_due,
        int64_t                 source_ts_us) {
    if (state == NULL || cfg == NULL || !color_algorithm_configured) return false;
    int effective_stride = cfg->frame_stride > 0 ? cfg->frame_stride : 1;
    if (cfg->stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE) {
        int min_stride = cfg->adaptive_min_stride_frames > 0
            ? cfg->adaptive_min_stride_frames
            : effective_stride;
        int max_stride = cfg->adaptive_max_stride_frames > 0
            ? cfg->adaptive_max_stride_frames
            : effective_stride;
        min_stride = clamp_i32(min_stride, 1, 120);
        max_stride = clamp_i32(max_stride, min_stride, 120);
        effective_stride =
            state->adaptive_effective_stride >= min_stride &&
            state->adaptive_effective_stride <= max_stride
                ? state->adaptive_effective_stride
                : effective_stride;
        effective_stride = clamp_i32(effective_stride, min_stride, max_stride);
    }
    if (effective_stride <= 1 || fixed_full_refresh_cadence_due) return false;
    int target_revisit_count = anomaly_target_revisit_track_count(state);
    if (target_revisit_count > 0) {
        bool confirmed_color_target = false;
        for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
            const anomaly_target_track_t *track = &state->target_tracks[i];
            if (track->active &&
                track->algorithm == ANOMALY_ALGO_COLOR &&
                track->publish_confirmed) {
                confirmed_color_target = true;
                break;
            }
        }
        int64_t frames_since_full = state->last_full_refresh_frame_counter > 0
            ? state->frame_counter - state->last_full_refresh_frame_counter
            : 0;
        bool adaptive_target_coast =
            cfg->stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE &&
            confirmed_color_target &&
            frames_since_full > 2 &&
            (state->frame_counter % 2) == 0;
        if (!adaptive_target_coast) return false;
    }

    if (source_ts_us > 0 &&
        state->last_full_refresh_source_ts_us > 0 &&
        isfinite(cfg->adaptive_max_stride_seconds) &&
        cfg->adaptive_max_stride_seconds > 0.0f) {
        int64_t interval_us =
            (int64_t)llroundf(cfg->adaptive_max_stride_seconds * 1000000.0f);
        if (interval_us > 0 &&
            (source_ts_us - state->last_full_refresh_source_ts_us) >= interval_us) {
            return false;
        }
    }
    return true;
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
    anomaly_debug_timing_t timing;
    memset(&timing, 0, sizeof(timing));
    timing.compiled = ANOMALY_DEBUG_TIMING != 0;
    int64_t frame_started_us = anomaly_timing_now_us();

    anomaly_result_init(result_out, cfg);

    if (cfg == NULL) {
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    bool show_hot_overlay = cfg->show_hot_overlay;
    bool anomaly_detection_active = cfg->enabled && cfg->algorithm_mask != 0;
    bool color_algorithm_configured =
        anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0;
    if (!anomaly_detection_active && !show_hot_overlay) {
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    if (width <= 0 || height <= 0) {
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return 0;
    }

    int frame_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
    float thermal_min_delta = anomaly_runtime_effective_thermal_min_delta(cfg);
    bool use_publish_transition_gating = cfg->min_hits > 1;
    state->frame_counter += 1;
    bool bg_temporal_ready = anomaly_thermal_state_bg_ready(
        &state->thermal,
        0,
        0,
        ANOMALY_THERMAL_BG_WARMUP,
        false,
        false);
    bool bg_publish_ready = bg_temporal_ready &&
                            (!use_publish_transition_gating ||
                             state->thermal.bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES) ||
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
    bool fixed_full_refresh_cadence_due =
        state->frame_counter <= 1 || ((state->frame_counter % frame_stride) == 0);
    if (cfg->stride_mode != ANOMALY_STRIDE_MODE_ADAPTIVE &&
        !fixed_full_refresh_cadence_due) {
        anomaly_roi_tracks_age_one_frame(state);
    }

    // Keep anomaly sampling on the configured scan zone, but let registration
    // pull features from a wider ROI so selective refresh is not starved.
    int roi_x0 = 0;
    int roi_x1 = width;
    int roi_y0 = 0;
    int roi_y1 = height;
    anomaly_frame_roi_bounds_t roi_bounds =
        anomaly_frame_centered_roi_bounds(width, height, cfg->scan_zone);
    roi_x0 = roi_bounds.x0;
    roi_y0 = roi_bounds.y0;
    roi_x1 = roi_bounds.x1;
    roi_y1 = roi_bounds.y1;
    int registration_roi_x0 = 0;
    int registration_roi_x1 = width;
    int registration_roi_y0 = 0;
    int registration_roi_y1 = height;
    anomaly_frame_roi_bounds_t registration_roi_bounds =
        anomaly_frame_registration_roi_bounds(width, height);
    registration_roi_x0 = registration_roi_bounds.x0;
    registration_roi_y0 = registration_roi_bounds.y0;
    registration_roi_x1 = registration_roi_bounds.x1;
    registration_roi_y1 = registration_roi_bounds.y1;

    int sample_step = anomaly_runtime_effective_sample_step(cfg, width, height);
    int motion_sample_step = anomaly_runtime_effective_motion_sample_step(cfg, width, height);
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
        if (!anomaly_buffer_ensure_u8_capacity(&state->scratch_luma, &state->scratch_luma_capacity, motion_count)) {
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
            anomaly_scratch_ensure_registration_luma_capacity(state, motion_count)) {
            curr_registration_luma = state->scratch_registration_luma;
            anomaly_registration_prefilter_luma_grid(
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

    if (anomaly_color_fast_hold_frame_eligible(
            state,
            cfg,
            color_algorithm_configured,
            fixed_full_refresh_cadence_due,
            source_ts_us)) {
        anomaly_frame_history_update_motion_luma(state, curr_luma, motion_count, motion_w, motion_h);
        anomaly_frame_history_update_registration_luma(
                state,
                curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
                motion_count,
                motion_w,
                motion_h);

        anomaly_scan_plan_t scan_plan;
        memset(&scan_plan, 0, sizeof(scan_plan));
        scan_plan.valid = true;
        scan_plan.mode = ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP;
        scan_plan.sampled_width = sg_w;
        scan_plan.sampled_height = sg_h;
        scan_plan.total_samples = sg_w > 0 && sg_h > 0 ? sg_w * sg_h : 0;
        scan_plan.target_revisit_track_count = anomaly_target_revisit_track_count(state);
        scan_plan.reason_flags = ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH;

        anomaly_result_frame_metadata_t frame_metadata = {
            .had_discontinuity = false,
            .registration_ran_this_frame = false,
            .appearance_refresh_ran_this_frame = false,
            .registration_health = ANOMALY_REG_HEALTH_UNKNOWN,
            .rescan_mode = ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP,
            .scan_plan = scan_plan,
            .adaptive_effective_stride = frame_stride,
            .adaptive_stable_frames = state->adaptive_stable_frames,
            .adaptive_drop_hold_frames = state->adaptive_drop_hold_frames,
            .adaptive_motion_load = 0.0f,
            .adaptive_reason_flags = 0u,
            .registration = NULL,
            .movement_debug = NULL,
        };
        anomaly_result_publish_frame_metadata(result_out, &frame_metadata);

        int box_count = 0;
        bool publish_allowed =
            !transition_warmup_block &&
            !publish_hold_active;
        if (anomaly_detection_active && publish_allowed && result_out != NULL) {
            anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
            box_count = anomaly_result_build_boxes(
                    state,
                    cfg,
                    ANOMALY_ALGO_MOTION,
                    boxes,
                    ANOMALY_MAX_BOXES_PER_FRAME);
            anomaly_result_publish_boxes(result_out, boxes, box_count);
        }
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return box_count;
    }

    int registration_mode = anomaly_registration_normalize_mode(cfg);
    stage_started_us = anomaly_timing_now_us();
    anomaly_registration_model_t registration;
    bool reused_registration = anomaly_registration_cache_try_load(
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
        anomaly_registration_classify_health(&registration, width, height);
    anomaly_registration_health_t registration_health = registration_health_base;
    anomaly_debug_movement_t movement_sidecar;
    anomaly_motion_estimator_sidecar_input_t movement_input = {
        .cfg = cfg,
        .registration = &registration,
        .curr_luma = curr_luma,
        .prev_luma = state->prev_luma,
        .motion_w = motion_w,
        .motion_h = motion_h,
        .motion_step = motion_step,
        .width = width,
        .height = height,
        .roi_x0 = roi_x0,
        .roi_x1 = roi_x1,
        .roi_y0 = roi_y0,
        .roi_y1 = roi_y1,
        .ops = anomaly_motion_estimator_default_sidecar_ops(),
    };
    stage_started_us = anomaly_timing_now_us();
    anomaly_motion_estimator_estimate_sidecar(&movement_input, &movement_sidecar);
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_MOVEMENT_ESTIMATOR, stage_started_us);
    anomaly_motion_movement_snapshot_t movement_snapshot = anomaly_motion_estimator_make_movement_snapshot(&movement_sidecar);
    int *prev_sample_lookup = NULL;
    anomaly_scan_planner_prev_lookup_summary_t prev_sample_lookup_summary;
    memset(&prev_sample_lookup_summary, 0, sizeof(prev_sample_lookup_summary));
    if (sg_w > 0 && sg_h > 0 &&
        state->roi_state.valid &&
        state->roi_state.sample_step == sample_step &&
        !scene_discontinuity &&
        anomaly_registration_model_valid(&registration) &&
        anomaly_buffer_ensure_int_capacity(
            &state->scratch_prev_sample_lookup,
            &state->scratch_prev_sample_lookup_capacity,
            (size_t)sg_w * (size_t)sg_h)) {
        if (anomaly_scan_planner_build_prev_sample_lookup(
                &state->roi_state,
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
                ANOMALY_ROI_REALTIME_CARRY_EXPIRY,
                state->scratch_prev_sample_lookup,
                &prev_sample_lookup_summary)) {
            prev_sample_lookup = state->scratch_prev_sample_lookup;
        }
    }
    bool allow_sparse_refresh_fallback =
        (cfg->algorithm_mask &
         (ANOMALY_ALGO_COLOR | ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0;
    anomaly_scan_planner_input_t scan_planner_input = {
        .state = state,
        .cfg = cfg,
        .registration = &registration,
        .movement = &movement_sidecar,
        .ops = anomaly_scan_planner_default_ops(),
        .frame_source_ts_us = source_ts_us,
        .frame_counter = state->frame_counter,
        .frame_width = width,
        .frame_height = height,
        .roi_x0 = roi_x0,
        .roi_y0 = roi_y0,
        .roi_x1 = roi_x1,
        .roi_y1 = roi_y1,
        .sample_step = sample_step,
        .sampled_width = sg_w,
        .sampled_height = sg_h,
        .fixed_full_refresh_cadence_due = fixed_full_refresh_cadence_due,
        .scene_discontinuity = scene_discontinuity,
        .base_registration_health = registration_health_base,
        .color_algorithm_configured = color_algorithm_configured,
        .color_stride_hold_eligible = true,
        .prev_sample_lookup = prev_sample_lookup,
        .prev_lookup_summary = &prev_sample_lookup_summary,
        .adaptive = {
            .adaptive_enabled = cfg->stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE,
            .fixed_frame_stride = frame_stride,
        },
        .selective_refresh = {
            .allow_sparse_fallback = allow_sparse_refresh_fallback,
        },
    };
    anomaly_scan_planner_output_t scan_planner_output;
    anomaly_scan_planner_plan(&scan_planner_input, &scan_planner_output);
    if (scan_planner_output.scan_planning_elapsed_us > 0) {
        timing.stage_us[ANOMALY_TIMING_STAGE_SCAN_PLANNING] +=
            scan_planner_output.scan_planning_elapsed_us;
    }
    if (scan_planner_output.refresh_mask_elapsed_us > 0) {
        timing.stage_us[ANOMALY_TIMING_STAGE_REFRESH_MASK_BUILD] +=
            scan_planner_output.refresh_mask_elapsed_us;
    }
    registration_health = scan_planner_output.registration_health;
    anomaly_scan_plan_t scan_plan = scan_planner_output.scan_plan;
    anomaly_rescan_mode_t rescan_mode = scan_planner_output.rescan_mode;
    bool color_stride_hold_frame = scan_planner_output.color_stride_hold_frame;
    anomaly_registration_cache_store(state, &registration, registration_health, rescan_mode);
    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    uint8_t *appearance_refresh_mask = scan_planner_output.appearance_refresh_mask;
    bool selective_refresh_active = scan_planner_output.selective_refresh_active;

    anomaly_result_frame_metadata_t frame_metadata = {
        .had_discontinuity = scene_discontinuity,
        .registration_ran_this_frame = true,
        .appearance_refresh_ran_this_frame = (rescan_mode == ANOMALY_RESCAN_MODE_FULL),
        .registration_health = registration_health,
        .rescan_mode = rescan_mode,
        .scan_plan = scan_plan,
        .adaptive_effective_stride = scan_planner_output.adaptive.effective_frame_stride,
        .adaptive_stable_frames = scan_planner_output.adaptive.stable_frames,
        .adaptive_drop_hold_frames = scan_planner_output.adaptive.drop_hold_frames,
        .adaptive_motion_load = scan_planner_output.adaptive.motion_load,
        .adaptive_reason_flags = scan_planner_output.adaptive.reason_flags,
        .registration = &registration,
        .movement_debug = &movement_sidecar,
    };
    anomaly_result_publish_frame_metadata(result_out, &frame_metadata);

    // ── Compensate accumulators for camera motion (or wipe on discontinuity)
    // T⁻¹(p) = Aᵀ * (p - t) / (a²+b²)  where Aᵀ = [[a,b],[-b,a]]
    stage_started_us = anomaly_timing_now_us();
    if (scene_discontinuity) {
        anomaly_roi_tracks_clear_all(state);
    } else {
        for (int ai = 0; ai < 4; ai++) {
            if (state->acc_active[ai] && anomaly_registration_model_valid(&registration)) {
            float nx = 0.0f;
            float ny = 0.0f;
            if (anomaly_registration_invert_point(&registration, state->acc_cx[ai], state->acc_cy[ai], &nx, &ny)) {
                if (ai == 1 &&
                    anomaly_motion_estimator_normalize_movement_mode(cfg) ==
                        ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
                    float local_nx = nx;
                    float local_ny = ny;
                    if (anomaly_motion_estimator_apply_local_residual_prediction(
                            &movement_snapshot,
                            nx,
                            ny,
                            width,
                            height,
                            &local_nx,
                            &local_ny)) {
                        nx = local_nx;
                        ny = local_ny;
                    }
                }
                state->acc_cx[ai] = nx < 0.0f ? 0.0f : (nx > 1.0f ? 1.0f : nx);
                state->acc_cy[ai] = ny < 0.0f ? 0.0f : (ny > 1.0f ? 1.0f : ny);
            }
            }
        }
        for (int ti = 0; ti < ANOMALY_COLOR_PROMOTION_TRACKS; ti++) {
            if (!state->color_promotion_active[ti] || !anomaly_registration_model_valid(&registration)) continue;
            float nx = 0.0f;
            float ny = 0.0f;
            if (anomaly_registration_invert_point(
                    &registration,
                    state->color_promotion_cx[ti],
                    state->color_promotion_cy[ti],
                    &nx,
                    &ny)) {
                state->color_promotion_cx[ti] = clamp01f(nx);
                state->color_promotion_cy[ti] = clamp01f(ny);
            }
        }
    }
    const anomaly_target_tracks_registration_prediction_t target_track_prediction = {
        .registration = &registration,
        .health = registration_health,
        .quality = anomaly_registration_health_confidence(registration_health),
        .scene_discontinuity = scene_discontinuity,
        .valid = target_tracks_registration_model_valid,
        .invert_point = target_tracks_registration_invert_point,
        .frame_width = width,
        .frame_height = height,
    };
    anomaly_target_tracks_predict_with_registration(state, &target_track_prediction);
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_TARGET_TRACKING, stage_started_us);

    if (color_stride_hold_frame) {
        anomaly_frame_history_update_motion_luma(state, curr_luma, motion_count, motion_w, motion_h);
        anomaly_frame_history_update_registration_luma(
                state,
                curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
                motion_count,
                motion_w,
                motion_h);
        int box_count = 0;
        bool publish_allowed =
            !transition_warmup_block &&
            !publish_hold_active &&
            !scene_discontinuity &&
            anomaly_registration_model_valid(&registration) &&
            registration_health != ANOMALY_REG_HEALTH_INVALID &&
            registration_health != ANOMALY_REG_HEALTH_HARD_DEGRADED;
        if (anomaly_detection_active && publish_allowed && result_out != NULL) {
            anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
            box_count = anomaly_result_build_boxes(
                    state,
                    cfg,
                    ANOMALY_ALGO_MOTION,
                    boxes,
                    ANOMALY_MAX_BOXES_PER_FRAME);
            anomaly_result_publish_boxes(result_out, boxes, box_count);
        }
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return box_count;
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
    if (!anomaly_scratch_ensure_sampled_grid_capacity(state, sg_count)) {
        anomaly_frame_history_update_motion_luma(state, curr_luma, motion_count, motion_w, motion_h);
        anomaly_frame_history_update_registration_luma(
            state,
            curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
            motion_count,
            motion_w,
            motion_h);
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return 0;
    }
    float *sg_luma  = state->scratch_sg_luma;
    float *ii_sum   = state->scratch_ii_sum;
    float *ii_sum2  = state->scratch_ii_sum2;

    bool color_algorithm_enabled = color_algorithm_configured;
    bool need_color_support = color_algorithm_enabled && !color_stride_hold_frame;
    bool need_thermal_support_map =
        anomaly_detection_active &&
        !color_stride_hold_frame &&
        anomaly_thermal_support_map_required(cfg->algorithm_mask);
    bool need_color_support_map = color_algorithm_enabled && !color_stride_hold_frame;
    bool bg_valid = anomaly_thermal_state_bg_ready(
        &state->thermal,
        sg_w,
        sg_h,
        ANOMALY_THERMAL_BG_WARMUP,
        true,
        scene_discontinuity);
    bool sampled_grid_needs_integral_images =
        anomaly_sampled_grid_integral_images_required(
            anomaly_detection_active,
            cfg->algorithm_mask,
            bg_valid,
            selective_refresh_active);

    int color_phase_index = 0;
    int color_phase_x = 0;
    int color_phase_y = 0;
    anomaly_color_sampling_phase_for_frame(state, sample_step, &color_phase_index, &color_phase_x, &color_phase_y);
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
            if (sampled_grid_needs_integral_images) {
                float a = (sy > 0) ? ii_sum[prev_row_offset + sx] : 0.0f;
                float l = (sx > 0) ? ii_sum[idx - 1] : 0.0f;
                float al = (sy > 0 && sx > 0) ? ii_sum[prev_row_offset + (sx - 1)] : 0.0f;
                ii_sum[idx] = lum + a + l - al;
                float a2 = (sy > 0) ? ii_sum2[prev_row_offset + sx] : 0.0f;
                float l2 = (sx > 0) ? ii_sum2[idx - 1] : 0.0f;
                float al2 = (sy > 0 && sx > 0) ? ii_sum2[prev_row_offset + (sx - 1)] : 0.0f;
                ii_sum2[idx] = lum2 + a2 + l2 - al2;
            }
            sum_l  += lum; sum_l2 += lum2;
            sample_count++;
        }
    }

    if (sample_count <= 1) {
        anomaly_frame_history_update_motion_luma(state, curr_luma, motion_count, motion_w, motion_h);
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);
        anomaly_result_finalize_timing(result_out, &timing, frame_started_us);
        return 0;
    }

    int color_frontend_mode = anomaly_color_effective_frontend_mode(cfg);
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
        if (result_out != NULL) {
            anomaly_result_publish_scan_plan(result_out, &scan_plan);
        }
    }

    double g_mean_l = sum_l / (double)sample_count;
    double g_std_l  = sqrt(fmax((sum_l2/(double)sample_count) - g_mean_l*g_mean_l, 1.0));
    uint8_t *color_frame_hist = NULL;
    uint8_t color_recent_hist_weighted[ANOMALY_COLOR_HIST_BINS];
    float color_family_rarity_lut[ANOMALY_COLOR_HIST_BINS];
    float color_hist_rarity_lut[ANOMALY_COLOR_HIST_BINS];
    for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
        color_family_rarity_lut[i] = 0.0f;
        color_hist_rarity_lut[i] = 0.0f;
    }
    int color_hist_valid_samples = 0;
    bool color_history_recovery_trigger =
        state->frame_counter <= 1 ||
        !state->roi_state.valid ||
        scene_discontinuity ||
        registration_health == ANOMALY_REG_HEALTH_INVALID ||
        registration_health == ANOMALY_REG_HEALTH_HARD_DEGRADED;
    if (color_history_recovery_trigger) {
        state->color_history_recovery_frames = ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES;
    }
    int color_history_recovery_frames_remaining = state->color_history_recovery_frames;
    float color_history_recent_scale =
        anomaly_color_history_recent_scale_for_recovery(color_history_recovery_frames_remaining);
    if (need_color_support) {
        anomaly_roi_state_t *roi_state = &state->roi_state;
        if (anomaly_color_hist_ensure_capacity(&state->scratch_color_hist, &state->scratch_color_hist_bins) &&
            anomaly_color_hist_ensure_capacity(&state->color_recent_hist, &state->color_recent_hist_bins)) {
            color_frame_hist = state->scratch_color_hist;
            color_hist_valid_samples = anomaly_color_build_frame_histogram(roi_state, sg_w, sg_h, color_frame_hist);
            if (color_hist_valid_samples <= 0) {
                memset(color_frame_hist, 0, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
            }
            for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
                float scaled = state->color_recent_hist != NULL
                    ? ((float)state->color_recent_hist[i] * color_history_recent_scale)
                    : 0.0f;
                int rounded = (int)lroundf(scaled);
                if (rounded < 0) rounded = 0;
                if (rounded > 255) rounded = 255;
                color_recent_hist_weighted[i] = (uint8_t)rounded;
            }
            anomaly_color_build_family_rarity_lut(
                color_frame_hist,
                color_recent_hist_weighted,
                color_family_rarity_lut);
            for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
                color_hist_rarity_lut[i] = anomaly_color_score_hist_rarity(
                    color_frame_hist,
                    color_recent_hist_weighted,
                    i);
            }
        } else {
            color_frame_hist = NULL;
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_SAMPLED_GRID_PREP, stage_started_us);

    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    bool compute_spatial_thermal_scores =
        anomaly_thermal_spatial_scores_required(
            anomaly_detection_active,
            cfg->algorithm_mask,
            bg_valid);

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
        if (anomaly_scratch_ensure_saliency_capacity(state, sg_count) &&
            (!need_color_support_map || anomaly_scratch_ensure_patch_capacity(state, sg_count))) {
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
    float thermal_score_threshold = anomaly_thermal_effective_score_threshold(cfg->score_threshold);
    anomaly_debug_candidate_t saliency_top[ANOMALY_DEBUG_TOP_CANDIDATES];
    memset(saliency_top, 0, sizeof(saliency_top));
    int saliency_top_count = 0;
    float saliency_tracked_score_pre = -1.0f;
    bool saliency_switch_suppressed = false;
    bool color_selective_refresh_active =
        selective_refresh_active &&
        !color_forced_full_refresh;
    int color_active_min_sx = 0;
    int color_active_min_sy = 0;
    int color_active_max_sx = sg_w > 0 ? (sg_w - 1) : -1;
    int color_active_max_sy = sg_h > 0 ? (sg_h - 1) : -1;
    int color_rarity_seed_min_sx = sg_w;
    int color_rarity_seed_min_sy = sg_h;
    int color_rarity_seed_max_sx = -1;
    int color_rarity_seed_max_sy = -1;
    int color_rarity_seed_count = 0;
    bool color_target_enabled = cfg != NULL && cfg->color_debug_target_enabled;
    bool color_target_valid = false;
    bool color_target_inside_scan_zone = false;
    bool color_target_refresh_skipped = false;
    bool color_target_sampled_this_frame = false;
    bool color_target_carried_from_history = false;
    int color_target_px = -1;
    int color_target_py = -1;
    int color_target_sx = -1;
    int color_target_sy = -1;
    size_t color_target_idx = 0;
    int color_target_hist_key = -1;
    float color_target_hist_current_count = 0.0f;
    float color_target_hist_recent_count = 0.0f;
    float color_target_hist_rarity = 0.0f;
    int color_target_local_support = 0;
    anomaly_color_target_telemetry_t color_target_telemetry = {0};
    float color_target_pre_support_score = 0.0f;
    float color_target_support_score = 0.0f;
    float color_target_support_map_local_peak = 0.0f;
    float color_target_support_map_ring_mean = 0.0f;
    float color_target_support_map_density = 0.0f;
    float color_target_support_map_distinctness_ratio = 0.0f;
    float color_target_support_map_compact_prominence = 0.0f;
    float color_target_support_map_core_share = 0.0f;
    float color_target_support_map_seed_floor = 0.0f;
    bool color_target_support_seed_eligible = false;
    int color_target_matched_candidate_idx = -1;
    int color_target_nearest_candidate_idx = -1;
    float color_target_nearest_candidate_distance = -1.0f;
    anomaly_debug_color_target_stage_t color_target_stage =
        ANOMALY_COLOR_TARGET_STAGE_NONE;
    float color_strongest_seed_score = 0.0f;
    int color_strongest_seed_sx = -1;
    int color_strongest_seed_sy = -1;
    int color_strongest_seed_hist_key = -1;
    float color_strongest_seed_hist_current_count = 0.0f;
    float color_strongest_seed_hist_recent_count = 0.0f;
    float color_strongest_seed_hist_rarity = 0.0f;
    int color_strongest_seed_local_support = 0;
    if (color_target_enabled && sg_w > 0 && sg_h > 0) {
        float fw_dbg = (float)(width > 1 ? width - 1 : 1);
        float fh_dbg = (float)(height > 1 ? height - 1 : 1);
        color_target_px = clamp_i32(
            (int)lroundf(cfg->color_debug_target_x_norm * fw_dbg), 0, width - 1);
        color_target_py = clamp_i32(
            (int)lroundf(cfg->color_debug_target_y_norm * fh_dbg), 0, height - 1);
        color_target_inside_scan_zone =
            color_target_px >= roi_x0 && color_target_px < roi_x1 &&
            color_target_py >= roi_y0 && color_target_py < roi_y1;
        if (color_target_inside_scan_zone) {
            color_target_sx = clamp_i32(
                (color_target_px - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
            color_target_sy = clamp_i32(
                (color_target_py - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
            color_target_idx =
                (size_t)color_target_sy * (size_t)sg_w + (size_t)color_target_sx;
            color_target_valid = true;
        } else {
            color_target_stage = ANOMALY_COLOR_TARGET_STAGE_OUTSIDE_SCAN_ZONE;
        }
    }
    if (color_algorithm_enabled && color_selective_refresh_active && appearance_refresh_mask != NULL) {
        float color_target_span_px = anomaly_runtime_effective_color_target_span_px(cfg, width, height);
        int color_bounds_pad =
            anomaly_color_support_patch_radius(color_target_span_px, sample_step);
        anomaly_grid_region_compute_active_mask_bounds(
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
                    int hist_key = anomaly_color_hist_key(u_bin, v_bin);
                    float rarity = color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY
                        ? color_family_rarity_lut[hist_key]
                        : color_hist_rarity_lut[hist_key];
                    roi_state->color_raw_score[idx] = rarity;
                    if (saliency_color_map != NULL) {
                        bool target_debug_sample =
                            color_target_valid &&
                            sx == color_target_sx &&
                            sy == color_target_sy;
                        if (anomaly_color_fresh_seed_should_skip_support(
                                color_frontend_mode,
                                rarity,
                                target_debug_sample)) {
                            saliency_color_map[idx] = 0.0f;
                        } else {
                            int local_support = anomaly_color_local_uv_support_count(
                                roi_state,
                                sg_w,
                                sg_h,
                                sx,
                                sy,
                                u_bin,
                                v_bin,
                                ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS);
                            if (local_support < ANOMALY_COLOR_LOCAL_SUPPORT_MIN) {
                                saliency_color_map[idx] = 0.0f;
                            } else {
                                float center_chroma = anomaly_color_sample_chroma_magnitude(roi_state, idx);
                                float support_scale = clampf(
                                    0.60f + 0.20f * (float)(local_support - ANOMALY_COLOR_LOCAL_SUPPORT_MIN),
                                    0.60f,
                                    1.20f);
                                float rarity_score = 0.0f;
                                if (center_chroma >= 10.0f &&
                                    rarity >= ANOMALY_COLOR_RARITY_MIN) {
                                    rarity_score = (rarity - ANOMALY_COLOR_RARITY_MIN) *
                                                   ANOMALY_COLOR_RARITY_SCALE;
                                }
                                float rescue_score = anomaly_color_score_contrast_rescue(
                                    roi_state,
                                    sg_w,
                                    sg_h,
                                    sx,
                                    sy,
                                    !color_refresh_skip,
                                    local_support);
                                float temporal_rescue_score =
                                    anomaly_color_frontend_allows_pre_support_temporal_rescue(color_frontend_mode)
                                    ? anomaly_color_score_temporal_rescue(
                                        state,
                                        cfg,
                                        sg_w,
                                        sg_h,
                                        sx,
                                        sy,
                                        !color_refresh_skip,
                                        local_support)
                                    : 0.0f;
                                float seed_score = fmaxf(rarity_score, fmaxf(rescue_score, temporal_rescue_score));
                                saliency_color_map[idx] = clampf(seed_score * support_scale, 0.0f, 4.0f);
                                if (saliency_color_map[idx] > color_strongest_seed_score) {
                                    int key = anomaly_color_hist_key(u_bin, v_bin);
                                    color_strongest_seed_score = saliency_color_map[idx];
                                    color_strongest_seed_sx = sx;
                                    color_strongest_seed_sy = sy;
                                    color_strongest_seed_hist_key = key;
                                    color_strongest_seed_hist_current_count = (float)color_frame_hist[key];
                                    color_strongest_seed_hist_recent_count = color_frame_hist != NULL
                                        ? (float)color_recent_hist_weighted[key]
                                        : 0.0f;
                                    color_strongest_seed_hist_rarity = rarity;
                                    color_strongest_seed_local_support = local_support;
                                }
                                if (saliency_color_map[idx] >= 0.55f) {
                                    color_rarity_seed_count++;
                                    if (sx < color_rarity_seed_min_sx) color_rarity_seed_min_sx = sx;
                                    if (sy < color_rarity_seed_min_sy) color_rarity_seed_min_sy = sy;
                                    if (sx > color_rarity_seed_max_sx) color_rarity_seed_max_sx = sx;
                                    if (sy > color_rarity_seed_max_sy) color_rarity_seed_max_sy = sy;
                                }
                            }
                        }
                    }
                    if (color_target_valid && sx == color_target_sx && sy == color_target_sy) {
                        int key = anomaly_color_hist_key(u_bin, v_bin);
                        color_target_hist_key = key;
                        color_target_hist_current_count = (float)color_frame_hist[key];
                        color_target_hist_recent_count = color_frame_hist != NULL
                            ? (float)color_recent_hist_weighted[key]
                            : 0.0f;
                        color_target_hist_rarity = rarity;
                        color_target_local_support = anomaly_color_local_uv_support_count(
                            roi_state,
                            sg_w,
                            sg_h,
                            sx,
                            sy,
                            u_bin,
                            v_bin,
                            ANOMALY_COLOR_LOCAL_SUPPORT_RADIUS);
                        anomaly_color_compute_target_telemetry(
                            roi_state,
                            sg_w,
                            sg_h,
                            sx,
                            sy,
                            1,
                            3,
                            !color_selective_refresh_active,
                            color_selective_refresh_active ? appearance_refresh_mask : NULL,
                            &color_target_telemetry);
                        if (color_target_local_support >= ANOMALY_COLOR_LOCAL_SUPPORT_MIN) {
                            float center_chroma = anomaly_color_sample_chroma_magnitude(roi_state, idx);
                            float support_scale = clampf(
                                0.60f + 0.20f * (float)(color_target_local_support - ANOMALY_COLOR_LOCAL_SUPPORT_MIN),
                                0.60f,
                                1.20f);
                            float rarity_score = 0.0f;
                            if (center_chroma >= 10.0f &&
                                rarity >= ANOMALY_COLOR_RARITY_MIN) {
                                rarity_score = (rarity - ANOMALY_COLOR_RARITY_MIN) *
                                               ANOMALY_COLOR_RARITY_SCALE;
                            }
                            float rescue_score = anomaly_color_score_contrast_rescue(
                                roi_state,
                                sg_w,
                                sg_h,
                                sx,
                                sy,
                                !color_refresh_skip,
                                color_target_local_support);
                            float temporal_rescue_score =
                                anomaly_color_frontend_allows_pre_support_temporal_rescue(color_frontend_mode)
                                ? anomaly_color_score_temporal_rescue(
                                    state,
                                    cfg,
                                    sg_w,
                                    sg_h,
                                    sx,
                                    sy,
                                    !color_refresh_skip,
                                    color_target_local_support)
                                : 0.0f;
                            color_target_pre_support_score =
                                clampf(fmaxf(rarity_score, fmaxf(rescue_score, temporal_rescue_score)) *
                                           support_scale,
                                       0.0f,
                                       4.0f);
                        } else {
                            color_target_pre_support_score = 0.0f;
                        }
                    }
                } else {
                    if (saliency_color_map != NULL) saliency_color_map[idx] = 0.0f;
                    if (roi_state->color_raw_score != NULL) roi_state->color_raw_score[idx] = 0.0f;
                }
#if ANOMALY_DEBUG_TIMING
                anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_SCORING, branch_started_us);
                anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_SEED_SCORING, branch_started_us);
#endif
            }
            if (color_target_valid && sx == color_target_sx && sy == color_target_sy) {
                color_target_refresh_skipped = color_refresh_skip;
                color_target_sampled_this_frame = !color_refresh_skip;
                color_target_carried_from_history = color_refresh_skip;
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
    float color_candidate_center_u[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_center_v[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_center_luma[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_local_ring_chroma_contrast[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_local_ring_luma_contrast[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_local_ring_neighbor_count[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_current_nearest_hist_distance[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_recent_nearest_hist_distance[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_small_target_span_ratio[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_small_target_area_ratio[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_scene_commonness_score[ANOMALY_MAX_COLOR_CANDIDATES];
    float color_candidate_uniqueness_rank[ANOMALY_MAX_COLOR_CANDIDATES];
    bool color_candidate_promotion_eligible[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_candidate_promotion_track[ANOMALY_MAX_COLOR_CANDIDATES];
    int color_hist_nonzero_bins = 0;
    float color_hist_max_current_count = 0.0f;
    float color_hist_max_recent_count = 0.0f;
    int raw_best_color_candidate_idx = -1;
    float raw_best_color = -1.0f;
    int raw_best_color_x = 0;
    int raw_best_color_y = 0;
    bool color_winner_gate_active = false;
    anomaly_color_winner_gate_reason_t color_winner_gate_reject_reason = ANOMALY_COLOR_WINNER_GATE_NONE;
    float color_winner_gate_max_span = 0.0f;
    float color_winner_gate_max_area = 0.0f;
    float color_winner_gate_min_rarity = 0.0f;
    float color_winner_gate_max_commonness = 0.0f;
    anomaly_target_observation_t best_color_target_observation = {0};
    bool best_color_target_observation_valid = false;
    anomaly_target_observation_t best_persist_target_observation = {0};
    bool best_persist_target_observation_valid = false;
    float color_support_peak = 0.0f;
    int color_support_seed_count = 0;
    int color_coarse_component_count = 0;
    int color_coarse_oversized_count = 0;
    int color_dense_verify_component_count = 0;
    int color_blob_reject_area_count = 0;
    int color_blob_reject_ring_count = 0;
    int color_blob_reject_support_mass_count = 0;
    int color_blob_reject_quality_count = 0;
    int color_blob_examined_count = 0;
    int color_blob_strongest_reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
    float color_blob_strongest_reject_peak_support = 0.0f;
    float color_blob_strongest_reject_area = 0.0f;
    float color_blob_strongest_reject_span = 0.0f;
    float color_blob_strongest_reject_ring_fraction = 0.0f;
    float color_blob_strongest_reject_support_mass = 0.0f;
    float color_blob_strongest_reject_quality = 0.0f;
    float color_blob_target_span_px = 0.0f;
    int color_blob_target_span_cells = 0;
    int color_blob_max_area_budget = 0;
    float color_fresh_distinctness_ratio =
        anomaly_color_clamp_fresh_distinctness_ratio(
            state->fresh_color_distinctness_ratio > 0.0f
                ? state->fresh_color_distinctness_ratio
                : anomaly_color_default_fresh_distinctness_ratio());
    int color_adaptive_source_coarse_count = 0;
    anomaly_color_blob_target_trace_t color_blob_target_trace = {0};
    color_blob_target_trace.enabled = color_target_enabled;
    color_blob_target_trace.valid = color_target_valid;
    color_blob_target_trace.target_sx = color_target_sx;
    color_blob_target_trace.target_sy = color_target_sy;
    color_blob_target_trace.component_seed_x = -1;
    color_blob_target_trace.component_seed_y = -1;
    color_blob_target_trace.component_peak_x = -1;
    color_blob_target_trace.component_peak_y = -1;
    color_blob_target_trace.min_x = -1;
    color_blob_target_trace.min_y = -1;
    color_blob_target_trace.max_x = -1;
    color_blob_target_trace.max_y = -1;
    color_blob_target_trace.nms_conflict_rank = -1;
    color_blob_target_trace.nms_conflict_sample_x = -1;
    color_blob_target_trace.nms_conflict_sample_y = -1;
    color_blob_target_trace.pre_cap_rank = -1;
    color_blob_target_trace.pre_cap_limit = ANOMALY_MAX_COLOR_CANDIDATES;
    color_blob_target_trace.pre_cap_retention_rank = -1.0f;
    if (color_algorithm_enabled &&
        saliency_color_map != NULL &&
        color_support_scratch != NULL &&
        anomaly_buffer_ensure_u8_capacity(&state->scratch_u8, &state->scratch_u8_capacity, sg_count) &&
        anomaly_buffer_ensure_int_capacity(&state->scratch_i32, &state->scratch_i32_capacity, sg_count)) {
#if ANOMALY_DEBUG_TIMING
        int64_t color_post_started_us = anomaly_timing_now_us();
#endif
        int color_seed_min_sx = sg_w;
        int color_seed_min_sy = sg_h;
        int color_seed_max_sx = -1;
        int color_seed_max_sy = -1;
        int color_seed_count = 0;
        int color_post_min_sx = color_active_min_sx;
        int color_post_min_sy = color_active_min_sy;
        int color_post_max_sx = color_active_max_sx;
        int color_post_max_sy = color_active_max_sy;
        if (color_rarity_seed_count > 0 &&
            color_rarity_seed_max_sx >= color_rarity_seed_min_sx &&
            color_rarity_seed_max_sy >= color_rarity_seed_min_sy) {
            float color_support_target_span_px = anomaly_runtime_effective_color_target_span_px(cfg, width, height);
            int support_pad =
                anomaly_color_support_patch_radius(color_support_target_span_px, sample_step) + 1;
            color_post_min_sx = color_rarity_seed_min_sx - support_pad;
            color_post_min_sy = color_rarity_seed_min_sy - support_pad;
            color_post_max_sx = color_rarity_seed_max_sx + support_pad;
            color_post_max_sy = color_rarity_seed_max_sy + support_pad;
            if (color_post_min_sx < color_active_min_sx) color_post_min_sx = color_active_min_sx;
            if (color_post_min_sy < color_active_min_sy) color_post_min_sy = color_active_min_sy;
            if (color_post_max_sx > color_active_max_sx) color_post_max_sx = color_active_max_sx;
            if (color_post_max_sy > color_active_max_sy) color_post_max_sy = color_active_max_sy;
        }
        anomaly_color_blob_candidate_t color_blob_candidates[ANOMALY_MAX_COLOR_CANDIDATES];
        memset(color_blob_candidates, 0, sizeof(color_blob_candidates));
        color_support_peak = 0.0f;
        color_seed_min_sx = sg_w;
        color_seed_min_sy = sg_h;
        color_seed_max_sx = -1;
        color_seed_max_sy = -1;
        color_seed_count = 0;
        color_candidate_count = 0;
        color_coarse_component_count = 0;
        color_coarse_oversized_count = 0;
        color_dense_verify_component_count = 0;
        color_blob_reject_area_count = 0;
        color_blob_reject_ring_count = 0;
        color_blob_reject_support_mass_count = 0;
        color_blob_reject_quality_count = 0;
        color_blob_examined_count = 0;
        color_blob_strongest_reject_reason = ANOMALY_COLOR_BLOB_REJECT_NONE;
        color_blob_strongest_reject_peak_support = 0.0f;
        color_blob_strongest_reject_area = 0.0f;
        color_blob_strongest_reject_span = 0.0f;
        color_blob_strongest_reject_ring_fraction = 0.0f;
        color_blob_strongest_reject_support_mass = 0.0f;
        color_blob_strongest_reject_quality = 0.0f;
        color_blob_target_span_px = 0.0f;
        color_blob_target_span_cells = 0;
        color_blob_max_area_budget = 0;
        memset(color_blob_candidates, 0, sizeof(color_blob_candidates));
        color_blob_target_trace.enabled = color_target_enabled;
        color_blob_target_trace.valid = color_target_valid;
        color_blob_target_trace.target_sx = color_target_sx;
        color_blob_target_trace.target_sy = color_target_sy;
        if (color_post_max_sx >= color_post_min_sx &&
            color_post_max_sy >= color_post_min_sy) {
            anomaly_color_compute_blob_cohesion_weights_region(
                    &state->roi_state,
                    color_frontend_mode,
                    sg_w,
                    sg_h,
                    color_post_min_sx,
                    color_post_min_sy,
                    color_post_max_sx,
                    color_post_max_sy);
            if (color_target_valid &&
                color_target_sx >= color_post_min_sx &&
                color_target_sx <= color_post_max_sx &&
                color_target_sy >= color_post_min_sy &&
                color_target_sy <= color_post_max_sy) {
                float color_support_target_span_px =
                    anomaly_runtime_effective_color_target_span_px(cfg, width, height);
                int patch_radius =
                    anomaly_color_support_patch_radius(color_support_target_span_px, sample_step);
                size_t target_idx = (size_t)color_target_sy * (size_t)sg_w + (size_t)color_target_sx;
                float center = saliency_color_map[target_idx];
                float sum = 0.0f;
                float ring_sum = 0.0f;
                float local_peak = center;
                int count = 0;
                int support_count = 0;
                int ring_count = 0;
                for (int ny = color_target_sy - patch_radius; ny <= color_target_sy + patch_radius; ny++) {
                    if (ny < 0 || ny >= sg_h) continue;
                    for (int nx = color_target_sx - patch_radius; nx <= color_target_sx + patch_radius; nx++) {
                        if (nx < 0 || nx >= sg_w) continue;
                        int chebyshev = abs(nx - color_target_sx);
                        int dy = abs(ny - color_target_sy);
                        if (dy > chebyshev) chebyshev = dy;
                        float v = saliency_color_map[(size_t)ny * (size_t)sg_w + (size_t)nx];
                        if (v <= 0.0f) continue;
                        sum += v;
                        count++;
                        if (v > local_peak) local_peak = v;
                        if (v >= 0.35f) support_count++;
                        if (chebyshev == patch_radius) {
                            ring_sum += v;
                            ring_count++;
                        }
                    }
                }
                float mean = count > 0 ? (sum / (float)count) : 0.0f;
                float ring_mean = ring_count > 0 ? (ring_sum / (float)ring_count) : 0.0f;
                float density = count > 0 ? ((float)support_count / (float)count) : 0.0f;
                float distinctness_ratio =
                    anomaly_color_support_distinctness_ratio(local_peak, ring_mean);
                float distinctness_gate =
                    anomaly_color_support_distinctness_gate(
                        distinctness_ratio,
                        color_fresh_distinctness_ratio);
                float compact_prominence =
                    anomaly_color_support_compact_prominence(
                        local_peak,
                        ring_mean,
                        distinctness_gate);
                float core_share = anomaly_color_support_core_share(center, local_peak);
                float seed_floor =
                    anomaly_color_support_seed_floor(
                        color_frontend_mode,
                        color_fresh_distinctness_ratio);
                (void)mean;
                color_target_support_map_local_peak = local_peak;
                color_target_support_map_ring_mean = ring_mean;
                color_target_support_map_density = density;
                color_target_support_map_distinctness_ratio = distinctness_ratio;
                color_target_support_map_compact_prominence = compact_prominence;
                color_target_support_map_core_share = core_share;
                color_target_support_map_seed_floor = seed_floor;
            }
            if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY) {
                build_color_support_map(
                        cfg,
                        color_frontend_mode,
                        color_fresh_distinctness_ratio,
                        saliency_color_map,
                        state->roi_state.color_contrast_weight,
                        sg_w,
                        sg_h,
                        width,
                        height,
                        sample_step,
                        color_post_min_sx,
                        color_post_min_sy,
                        color_post_max_sx,
                        color_post_max_sy,
                        saliency_color_map,
                        color_support_scratch,
                        &color_support_peak,
                        &color_seed_min_sx,
                        &color_seed_min_sy,
                        &color_seed_max_sx,
                        &color_seed_max_sy,
                        &color_seed_count);
            } else {
                anomaly_color_find_seed_bounds_from_evidence(
                        saliency_color_map,
                        sg_w,
                        sg_h,
                        color_post_min_sx,
                        color_post_min_sy,
                        color_post_max_sx,
                        color_post_max_sy,
                        ANOMALY_FRESH_COLOR_BLOB_SEED_MIN,
                        &color_support_peak,
                        &color_seed_min_sx,
                        &color_seed_min_sy,
                        &color_seed_max_sx,
                        &color_seed_max_sy,
                        &color_seed_count);
            }
        }
        color_support_seed_count = color_seed_count;
        float color_candidate_seed_floor = color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY
            ? 0.55f
            : ANOMALY_FRESH_COLOR_BLOB_SEED_MIN;
        if (color_support_peak >= color_candidate_seed_floor &&
            color_seed_count > 0 &&
            color_seed_max_sx >= color_seed_min_sx &&
            color_seed_max_sy >= color_seed_min_sy) {
#if ANOMALY_DEBUG_TIMING
            int64_t color_blob_started_us = anomaly_timing_now_us();
#endif
            extract_color_blob_candidates(
                    cfg,
                    state,
                    &state->roi_state,
                    rescan_mode,
                    color_frontend_mode,
                    rgba,
                    rgba_stride,
                    saliency_color_map,
                    state->roi_state.color_contrast_weight,
                    sg_w,
                    sg_h,
                    width,
                    height,
                    roi_x0,
                    roi_y0,
                    roi_x1,
                    roi_y1,
                    sample_step,
                    color_seed_min_sx,
                    color_seed_min_sy,
                    color_seed_max_sx,
                    color_seed_max_sy,
                    state->scratch_u8,
                    state->scratch_i32,
                    color_blob_candidates,
                    &color_candidate_count,
                    &color_blob_reject_area_count,
                    &color_blob_reject_ring_count,
                    &color_blob_reject_support_mass_count,
                    &color_blob_reject_quality_count,
                    &color_blob_examined_count,
                    &color_coarse_oversized_count,
                    &color_dense_verify_component_count,
                    &color_blob_strongest_reject_reason,
                    &color_blob_strongest_reject_peak_support,
                    &color_blob_strongest_reject_area,
                    &color_blob_strongest_reject_span,
                    &color_blob_strongest_reject_ring_fraction,
                    &color_blob_strongest_reject_support_mass,
                    &color_blob_strongest_reject_quality,
                    &color_blob_target_trace,
                    &color_blob_target_span_px,
                    &color_blob_target_span_cells,
                    &color_blob_max_area_budget);
            color_coarse_component_count = color_blob_examined_count;
#if ANOMALY_DEBUG_TIMING
            anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_BLOB_EXTRACTION, color_blob_started_us);
#endif
        }
        color_adaptive_source_coarse_count = color_coarse_component_count;
        if (rescan_mode == ANOMALY_RESCAN_MODE_FULL) {
            state->last_color_full_scan_coarse_count = color_coarse_component_count;
        }
        state->fresh_color_distinctness_ratio = color_fresh_distinctness_ratio;
#if ANOMALY_DEBUG_TIMING
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_SCORING, color_post_started_us);
#endif
        if (color_frame_hist != NULL) {
            for (int hi = 0; hi < ANOMALY_COLOR_HIST_BINS; hi++) {
                float cur = (float)color_frame_hist[hi];
                float rec = (float)color_recent_hist_weighted[hi];
                if (cur > color_hist_max_current_count) color_hist_max_current_count = cur;
                if (rec > color_hist_max_recent_count) color_hist_max_recent_count = rec;
            }
        }
        best_color = -1.0f;
        best_color_x = 0;
        best_color_y = 0;
        int color_min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
#if ANOMALY_DEBUG_TIMING
        int64_t color_candidate_started_us = anomaly_timing_now_us();
#endif
        for (int ci = 0; ci < color_candidate_count; ci++) {
            float temporal_candidate_boost = anomaly_color_score_candidate_temporal_boost(
                state,
                cfg,
                color_frontend_mode,
                sg_w,
                sg_h,
                color_blob_candidates[ci].candidate.sg_x,
                color_blob_candidates[ci].candidate.sg_y);
            color_blob_candidates[ci].candidate.color_score += temporal_candidate_boost;
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
            color_candidate_center_u[ci] = 0.0f;
            color_candidate_center_v[ci] = 0.0f;
            color_candidate_center_luma[ci] = 0.0f;
            color_candidate_local_ring_chroma_contrast[ci] = 0.0f;
            color_candidate_local_ring_luma_contrast[ci] = 0.0f;
            color_candidate_local_ring_neighbor_count[ci] = 0;
            color_candidate_current_nearest_hist_distance[ci] = -1.0f;
            color_candidate_recent_nearest_hist_distance[ci] = -1.0f;
            color_candidate_small_target_span_ratio[ci] = 0.0f;
            color_candidate_small_target_area_ratio[ci] = 0.0f;
            color_candidate_scene_commonness_score[ci] = 0.0f;
            color_candidate_uniqueness_rank[ci] = 0.0f;
            color_candidate_promotion_eligible[ci] = false;
            color_candidate_promotion_track[ci] = -1;
            if (color_frame_hist != NULL && state->roi_state.color_valid_mask != NULL) {
                size_t cidx = (size_t)color_candidates[ci].sg_y * (size_t)sg_w +
                              (size_t)color_candidates[ci].sg_x;
                if (state->roi_state.color_valid_mask[cidx] != 0u) {
                    int u_bin = (int)state->roi_state.color_u_bin[cidx];
                    int v_bin = (int)state->roi_state.color_v_bin[cidx];
                    int key = anomaly_color_hist_key(u_bin, v_bin);
                    color_candidate_center_u[ci] = state->roi_state.color_u != NULL
                        ? state->roi_state.color_u[cidx]
                        : 0.0f;
                    color_candidate_center_v[ci] = state->roi_state.color_v != NULL
                        ? state->roi_state.color_v[cidx]
                        : 0.0f;
                    color_candidate_center_luma[ci] = state->roi_state.color_luma != NULL
                        ? state->roi_state.color_luma[cidx]
                        : 0.0f;
                    anomaly_color_compute_ring_contrast(
                        &state->roi_state,
                        sg_w,
                        sg_h,
                        color_candidates[ci].sg_x,
                        color_candidates[ci].sg_y,
                        1,
                        3,
                        &color_candidate_local_ring_chroma_contrast[ci],
                        &color_candidate_local_ring_luma_contrast[ci],
                        &color_candidate_local_ring_neighbor_count[ci]);
                    color_candidate_hist_key[ci] = key;
                    color_candidate_hist_current_count[ci] = (float)color_frame_hist[key];
                    color_candidate_hist_recent_count[ci] = color_frame_hist != NULL
                        ? (float)color_recent_hist_weighted[key]
                        : 0.0f;
                    color_candidate_current_nearest_hist_distance[ci] =
                        anomaly_color_nearest_common_hist_bin_distance(color_frame_hist, u_bin, v_bin, 2);
                    color_candidate_recent_nearest_hist_distance[ci] =
                        anomaly_color_nearest_common_hist_bin_distance(color_recent_hist_weighted, u_bin, v_bin, 2);
                    color_candidate_hist_rarity[ci] =
                        color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY
                            ? anomaly_color_score_hist_family_rarity(
                                color_frame_hist,
                                color_recent_hist_weighted,
                                u_bin,
                                v_bin)
                            : color_blob_candidates[ci].hist_rarity_score;
                }
            }
            float small_target_span_cells =
                effective_thermal_small_target_span_px(cfg, width, height) /
                (float)(sample_step > 0 ? sample_step : 1);
            if (small_target_span_cells > 0.0f) {
                color_candidate_small_target_span_ratio[ci] =
                    color_candidate_span[ci] / small_target_span_cells;
                float small_target_area_cells = small_target_span_cells * small_target_span_cells;
                color_candidate_small_target_area_ratio[ci] =
                    small_target_area_cells > 0.0f
                        ? color_candidate_area[ci] / small_target_area_cells
                        : 0.0f;
            }
            color_candidate_scene_commonness_score[ci] = anomaly_color_candidate_scene_commonness(
                color_candidate_hist_current_count[ci],
                color_candidate_hist_recent_count[ci],
                color_hist_max_current_count,
                color_hist_max_recent_count);
            color_candidate_uniqueness_rank[ci] = anomaly_color_candidate_uniqueness_rank(
                color_candidate_hist_rarity[ci],
                color_candidate_scene_commonness_score[ci],
                anomaly_color_frontend_uses_fresh_winner_gate(color_frontend_mode));
            color_blob_candidates[ci].color_uniqueness_rank = color_candidate_uniqueness_rank[ci];
            if (anomaly_color_frontend_uses_fresh_winner_gate(color_frontend_mode) &&
                color_candidate_final_score[ci] >= 0.35f &&
                color_candidate_fill[ci] >= 0.40f &&
                color_candidate_quality[ci] >= 0.34f &&
                color_candidate_isolation[ci] >= 0.30f &&
                color_candidate_ring_fraction[ci] <= 0.19f &&
                color_candidate_support_mass[ci] <= 0.36f &&
                color_candidate_hist_rarity[ci] >= 0.00072f &&
                color_candidate_small_target_span_ratio[ci] <= 1.12f &&
                color_candidate_small_target_area_ratio[ci] <= 1.15f) {
                color_candidate_promotion_eligible[ci] = true;
                float cand_x_norm = (float)color_candidates[ci].pixel_x /
                                    (float)(width > 1 ? width - 1 : 1);
                float cand_y_norm = (float)color_candidates[ci].pixel_y /
                                    (float)(height > 1 ? height - 1 : 1);
                float best_track_dist = 0.0f;
                int best_track = -1;
                for (int ti = 0; ti < ANOMALY_COLOR_PROMOTION_TRACKS; ti++) {
                    if (!state->color_promotion_active[ti]) continue;
                    float dx = cand_x_norm - state->color_promotion_cx[ti];
                    float dy = cand_y_norm - state->color_promotion_cy[ti];
                    float dist = sqrtf(dx * dx + dy * dy);
                    if (dist > ANOMALY_COLOR_PROMOTION_GATE_RADIUS) continue;
                    if (best_track < 0 || dist < best_track_dist) {
                        best_track = ti;
                        best_track_dist = dist;
                    }
                }
                if (best_track >= 0) {
                    int prior_hits = state->color_promotion_hits[best_track];
                    color_candidate_promotion_track[ci] = best_track;
                    if (prior_hits >= color_min_hits &&
                        color_candidate_final_score[ci] >= 0.65f &&
                        color_candidate_quality[ci] >= 0.42f) {
                        float closeness = clamp01f(
                            1.0f - (best_track_dist / ANOMALY_COLOR_PROMOTION_GATE_RADIUS));
                        float hit_strength = clampf(
                            (float)(prior_hits - color_min_hits + 1) / 4.0f,
                            0.0f,
                            1.0f);
                        float promoted_score = cfg->score_threshold +
                            0.06f +
                            0.08f * hit_strength +
                            0.05f * closeness;
                        if (color_blob_candidates[ci].candidate.color_score < promoted_score) {
                            color_blob_candidates[ci].candidate.color_score = promoted_score;
                            color_candidates[ci].color_score = promoted_score;
                            color_candidate_final_score[ci] = promoted_score;
                        }
                        color_blob_candidates[ci].retention_rank = fmaxf(
                            color_blob_candidates[ci].retention_rank,
                            0.74f + 0.18f * hit_strength + 0.05f * closeness);
                        color_blob_candidates[ci].retention_rank_valid = true;
                        color_candidate_retention_rank[ci] = color_blob_candidates[ci].retention_rank;
                    }
                }
            }
        }

        bool color_promotion_track_matched[ANOMALY_COLOR_PROMOTION_TRACKS];
        memset(color_promotion_track_matched, 0, sizeof(color_promotion_track_matched));
        for (int ci = 0; ci < color_candidate_count; ci++) {
            if (!color_candidate_promotion_eligible[ci]) continue;
            float cand_x_norm = (float)color_candidates[ci].pixel_x /
                                (float)(width > 1 ? width - 1 : 1);
            float cand_y_norm = (float)color_candidates[ci].pixel_y /
                                (float)(height > 1 ? height - 1 : 1);
            int track_idx = color_candidate_promotion_track[ci];
            if (track_idx < 0) {
                int weakest_idx = 0;
                for (int ti = 0; ti < ANOMALY_COLOR_PROMOTION_TRACKS; ti++) {
                    if (!state->color_promotion_active[ti]) {
                        track_idx = ti;
                        break;
                    }
                    if (state->color_promotion_hits[ti] < state->color_promotion_hits[weakest_idx] ||
                        (state->color_promotion_hits[ti] == state->color_promotion_hits[weakest_idx] &&
                         state->color_promotion_hold[ti] < state->color_promotion_hold[weakest_idx])) {
                        weakest_idx = ti;
                    }
                }
                if (track_idx < 0) track_idx = weakest_idx;
            }
            if (track_idx < 0 || track_idx >= ANOMALY_COLOR_PROMOTION_TRACKS ||
                color_promotion_track_matched[track_idx]) {
                continue;
            }
            if (!state->color_promotion_active[track_idx]) {
                state->color_promotion_cx[track_idx] = cand_x_norm;
                state->color_promotion_cy[track_idx] = cand_y_norm;
                state->color_promotion_hits[track_idx] = 1;
                state->color_promotion_hold[track_idx] = ANOMALY_COLOR_PROMOTION_HOLD_FRAMES;
                state->color_promotion_active[track_idx] = true;
            } else {
                state->color_promotion_cx[track_idx] =
                    state->color_promotion_cx[track_idx] * 0.72f + cand_x_norm * 0.28f;
                state->color_promotion_cy[track_idx] =
                    state->color_promotion_cy[track_idx] * 0.72f + cand_y_norm * 0.28f;
                int hits = state->color_promotion_hits[track_idx] + 1;
                state->color_promotion_hits[track_idx] =
                    hits > ANOMALY_COLOR_PROMOTION_MAX_HITS
                        ? ANOMALY_COLOR_PROMOTION_MAX_HITS
                        : hits;
                state->color_promotion_hold[track_idx] = ANOMALY_COLOR_PROMOTION_HOLD_FRAMES;
            }
            color_promotion_track_matched[track_idx] = true;
        }
        for (int ti = 0; ti < ANOMALY_COLOR_PROMOTION_TRACKS; ti++) {
            if (!state->color_promotion_active[ti] || color_promotion_track_matched[ti]) continue;
            int hold = state->color_promotion_hold[ti] - 1;
            if (hold <= 0) {
                state->color_promotion_active[ti] = false;
                state->color_promotion_hits[ti] = 0;
                state->color_promotion_hold[ti] = 0;
                state->color_promotion_cx[ti] = 0.0f;
                state->color_promotion_cy[ti] = 0.0f;
            } else {
                state->color_promotion_hold[ti] = hold;
            }
        }

        for (int ci = 0; ci < color_candidate_count; ci++) {
            color_candidate_above_threshold[ci] =
                color_blob_candidates[ci].candidate.color_score >= cfg->score_threshold;
            if (color_candidate_above_threshold[ci]) {
                if (best_color_candidate_idx < 0 ||
                    anomaly_color_blob_candidate_compare_rank(
                        &color_blob_candidates[ci],
                        &color_blob_candidates[best_color_candidate_idx]) < 0) {
                    best_color_candidate_idx = ci;
                }
            } else if (color_blob_candidates[ci].candidate.color_score > best_color) {
                best_color = color_blob_candidates[ci].candidate.color_score;
                best_color_x = color_blob_candidates[ci].candidate.pixel_x;
                best_color_y = color_blob_candidates[ci].candidate.pixel_y;
            }
        }
#if ANOMALY_DEBUG_TIMING
        anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_COLOR_CANDIDATE_RANKING, color_candidate_started_us);
#endif
        if (best_color_candidate_idx >= 0) {
            best_color = color_candidates[best_color_candidate_idx].color_score;
            best_color_x = color_candidates[best_color_candidate_idx].pixel_x;
            best_color_y = color_candidates[best_color_candidate_idx].pixel_y;
            raw_best_color_candidate_idx = best_color_candidate_idx;
            raw_best_color = best_color;
            raw_best_color_x = best_color_x;
            raw_best_color_y = best_color_y;
            best_color_target_observation_valid =
                anomaly_target_observation_populate_color_candidate(
                    roi_x0,
                    roi_y0,
                    sample_step,
                    color_candidate_min_x[best_color_candidate_idx],
                    color_candidate_min_y[best_color_candidate_idx],
                    color_candidate_max_x[best_color_candidate_idx],
                    color_candidate_max_y[best_color_candidate_idx],
                    best_color_x,
                    best_color_y,
                    best_color,
                    color_candidate_quality[best_color_candidate_idx],
                    color_candidate_isolation[best_color_candidate_idx],
                    cfg->score_threshold,
                    (float)(width > 1 ? width - 1 : 1),
                    (float)(height > 1 ? height - 1 : 1),
                    ANOMALY_ALGO_COLOR,
                    &best_color_target_observation);
            color_winner_gate_active = anomaly_color_frontend_uses_fresh_winner_gate(color_frontend_mode);
            if (color_winner_gate_active) {
                float color_winner_small_target_span_px =
                    effective_thermal_small_target_span_px(cfg, width, height);
                color_winner_gate_reject_reason = anomaly_color_evaluate_fresh_winner_gate(
                    color_winner_small_target_span_px,
                    sample_step,
                    color_candidate_area[best_color_candidate_idx],
                    color_candidate_span[best_color_candidate_idx],
                    color_candidate_hist_rarity[best_color_candidate_idx],
                    color_candidate_scene_commonness_score[best_color_candidate_idx],
                    &color_winner_gate_max_span,
                    &color_winner_gate_max_area,
                    &color_winner_gate_min_rarity,
                    &color_winner_gate_max_commonness);
                if (color_winner_gate_reject_reason != ANOMALY_COLOR_WINNER_GATE_NONE) {
                    best_color_candidate_idx = -1;
                    best_color = -1.0f;
                    best_color_x = 0;
                    best_color_y = 0;
                    best_color_target_observation_valid = false;
                }
            }
        }
        if (color_target_valid) {
            anomaly_roi_state_t *roi_state = &state->roi_state;
            bool target_sample_valid =
                roi_state->color_valid_mask != NULL &&
                roi_state->color_valid_mask[color_target_idx] != 0u;
            color_target_support_score =
                saliency_color_map != NULL ? saliency_color_map[color_target_idx] : 0.0f;
            color_target_support_seed_eligible = color_target_support_score >= color_candidate_seed_floor;
            if (!target_sample_valid) {
                color_target_stage = ANOMALY_COLOR_TARGET_STAGE_INVALID_SAMPLE;
            } else if (color_target_hist_rarity < ANOMALY_COLOR_RARITY_MIN &&
                       color_target_pre_support_score < color_candidate_seed_floor) {
                color_target_stage = ANOMALY_COLOR_TARGET_STAGE_RARITY_REJECTED;
            } else if (color_target_local_support < ANOMALY_COLOR_LOCAL_SUPPORT_MIN) {
                color_target_stage = ANOMALY_COLOR_TARGET_STAGE_LOCAL_SUPPORT_REJECTED;
            } else if (!color_target_support_seed_eligible) {
                color_target_stage = ANOMALY_COLOR_TARGET_STAGE_SUPPORT_MAP_REJECTED;
            } else {
                color_target_stage = ANOMALY_COLOR_TARGET_STAGE_NO_CANDIDATE;
            }
            float target_fw = (float)(width > 1 ? width - 1 : 1);
            float target_fh = (float)(height > 1 ? height - 1 : 1);
            float target_x_norm = cfg->color_debug_target_x_norm;
            float target_y_norm = cfg->color_debug_target_y_norm;
            for (int ci = 0; ci < color_candidate_count; ci++) {
                float left = 0.0f;
                float top = 0.0f;
                float right = 0.0f;
                float bottom = 0.0f;
                anomaly_color_candidate_bbox_norm(
                    roi_x0,
                    roi_y0,
                    sample_step,
                    color_candidate_min_x[ci],
                    color_candidate_min_y[ci],
                    color_candidate_max_x[ci],
                    color_candidate_max_y[ci],
                    target_fw,
                    target_fh,
                    &left,
                    &top,
                    &right,
                    &bottom);
                float dx = ((float)color_candidates[ci].pixel_x / target_fw) - target_x_norm;
                float dy = ((float)color_candidates[ci].pixel_y / target_fh) - target_y_norm;
                float distance = sqrtf(dx * dx + dy * dy);
                if (color_target_nearest_candidate_idx < 0 ||
                    distance < color_target_nearest_candidate_distance) {
                    color_target_nearest_candidate_idx = ci;
                    color_target_nearest_candidate_distance = distance;
                }
                bool contains_target =
                    target_x_norm >= left && target_x_norm <= right &&
                    target_y_norm >= top && target_y_norm <= bottom;
                if (contains_target) {
                    color_target_matched_candidate_idx = ci;
                    color_target_stage = (ci == best_color_candidate_idx)
                        ? ANOMALY_COLOR_TARGET_STAGE_WINNER
                        : ANOMALY_COLOR_TARGET_STAGE_EXTRACTED;
                    break;
                }
            }
        }
    }
    bool color_history_reset_applied =
        scene_discontinuity || color_forced_full_refresh || color_hist_valid_samples <= 0;
    if (color_frame_hist != NULL) {
        for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
            float cur = (float)color_frame_hist[i];
            float rec = (float)color_recent_hist_weighted[i];
            if (cur > 0.0f) color_hist_nonzero_bins++;
            if (cur > color_hist_max_current_count) color_hist_max_current_count = cur;
            if (rec > color_hist_max_recent_count) color_hist_max_recent_count = rec;
        }
        anomaly_color_update_recent_histogram(
            state->color_recent_hist,
            color_frame_hist,
            color_history_reset_applied,
            color_history_recovery_frames_remaining > 0
                ? ANOMALY_COLOR_HISTORY_RECOVERY_SEED_SHIFT
                : ANOMALY_COLOR_HISTORY_UPDATE_SHIFT);
        if (color_hist_valid_samples > 0 && state->color_history_recovery_frames > 0) {
            state->color_history_recovery_frames--;
        }
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
    thermal_target_trace.local_peak_radius = 3;
    thermal_target_trace.local_peak_sx = -1;
    thermal_target_trace.local_peak_sy = -1;
    thermal_target_trace.local_peak_delta = -1.0f;
    thermal_target_trace.local_peak_score = -1.0f;
    thermal_target_trace.local_peak_distance = -1.0f;
    thermal_target_trace.suppressor_sx = -1;
    thermal_target_trace.suppressor_sy = -1;
    thermal_target_trace.component_seed_x = -1;
    thermal_target_trace.component_seed_y = -1;
    thermal_target_trace.component_peak_x = -1;
    thermal_target_trace.component_peak_y = -1;
    thermal_target_trace.nearby_rejected_component_gate = ANOMALY_THERMAL_TARGET_GATE_NONE;
    thermal_target_trace.nearby_rejected_component_seed_x = -1;
    thermal_target_trace.nearby_rejected_component_seed_y = -1;
    thermal_target_trace.nearby_rejected_component_peak_x = -1;
    thermal_target_trace.nearby_rejected_component_peak_y = -1;
    thermal_target_trace.nearby_rejected_component_distance = -1.0f;
    thermal_target_trace.pre_cap_rank = -1;
    thermal_target_trace.pre_cap_candidate_count = -1;
    thermal_target_trace.pre_cap_limit = ANOMALY_MAX_THERMAL_CANDIDATES;
    thermal_target_trace.pre_cap_retention_rank = -1.0f;
    thermal_target_trace.extracted_rank = -1;
    thermal_target_trace.winning_rank = -1;
    thermal_target_trace.provisional_candidate_index = -1;
    thermal_target_trace.provisional_score_floor = -1.0f;
    thermal_target_trace.provisional_final_score = -1.0f;
    thermal_target_trace.provisional_candidate_rank = -1.0f;
    thermal_target_trace.provisional_selected_rank = -1;
    thermal_target_trace.provisional_selected_score = -1.0f;
    thermal_target_trace.matched_track_index = -1;
    thermal_target_trace.matched_track_id = -1;
    thermal_target_trace.matched_track_hit_count = -1;
    thermal_target_trace.matched_track_miss_count = -1;
    thermal_target_trace.matched_track_hold_count = -1;
    thermal_target_trace.movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
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
    if (anomaly_detection_active && bg_valid && (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
        if (anomaly_scratch_ensure_saliency_capacity(state, sg_count)) {
            thermal_delta_map = state->scratch_thermal_delta;
        }
        anomaly_thermal_temporal_stats_t temporal_stats =
            anomaly_thermal_compute_temporal_stats(
                thermal_delta_map,
                state->thermal.bg_luma,
                sg_luma,
                sg_w,
                sg_h,
                black_hot != 0,
                thermal_min_delta,
                ANOMALY_THERMAL_BG_NORM);
        if (temporal_stats.valid) {
            delta_mean = temporal_stats.delta_mean;
            delta_norm = temporal_stats.delta_norm;
            frame_blob_contrast_mean = temporal_stats.frame_blob_contrast_mean;
            frame_blob_contrast_std = temporal_stats.frame_blob_contrast_std;
        }
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
                        state->thermal.bg_luma,
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
            state->thermal.bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES);
        anomaly_thermal_state_prepare_target_persist(
            &state->thermal,
            sg_count,
            sg_w,
            sg_h,
            scene_discontinuity,
            ANOMALY_THERMAL_TARGET_HISTORY_DECAY);
        float *thermal_patch_selection = NULL;
        float *thermal_value_map = NULL;
        uint8_t *thermal_visited = NULL;
        int *thermal_queue = NULL;
        if (anomaly_scratch_ensure_patch_capacity(state, sg_count) &&
            anomaly_buffer_ensure_u8_capacity(&state->scratch_u8, &state->scratch_u8_capacity, sg_count) &&
            anomaly_buffer_ensure_int_capacity(&state->scratch_i32, &state->scratch_i32_capacity, sg_count)) {
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
                    state->thermal.bg_luma,
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
                    delta_mean,
                    delta_norm,
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
                    state->thermal.bg_luma,
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
                    state->thermal.bg_luma,
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
                        state->thermal.bg_luma,
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
                        clampf((final_score - thermal_score_threshold + 0.25f) / 2.50f, 0.0f, 1.0f);
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
                bool candidate_plausible = final_score >= thermal_score_threshold;
                thermal_candidate_above_threshold[ci] = candidate_plausible;
                if (candidate_plausible) {
                    if (best_thermal_candidate_idx < 0 ||
                        anomaly_thermal_blob_candidate_compare_rank(
                            &thermal_blob_candidates[ci],
                            &thermal_blob_candidates[best_thermal_candidate_idx]) < 0) {
                        best_thermal_candidate_idx = ci;
                        best_thermal_candidate_score = final_score;
                    }
                } else if (final_score > best_thermal) {
                    best_thermal = final_score;
                    best_thermal_x = thermal_candidates[ci].pixel_x;
                    best_thermal_y = thermal_candidates[ci].pixel_y;
                }
                if (final_score > thermal_score_threshold &&
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
            if (state->thermal.thermal_target_persist != NULL) {
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
                        state->thermal.thermal_target_persist,
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
        anomaly_appearance_collect_motion_candidates(
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
    anomaly_motion_appearance_proposal_t
        motion_appearance_proposals[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS];
    memset(motion_appearance_proposals, 0, sizeof(motion_appearance_proposals));
    int motion_appearance_proposal_count =
        anomaly_motion_estimator_build_appearance_proposals_from_candidates(
            motion_candidates,
            motion_candidate_count,
            motion_appearance_proposals,
            ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS);

    // Update (or initialise) the background EMA.
    bool thermal_background_reset = anomaly_thermal_state_update_background(
        &state->thermal,
        sg_luma,
        sg_w,
        sg_h,
        black_hot != 0,
        scene_discontinuity,
        selective_refresh_active,
        appearance_refresh_mask,
        ANOMALY_THERMAL_BG_ALPHA_COOL,
        ANOMALY_THERMAL_BG_ALPHA_WARM);
    if (thermal_background_reset) {
        state->publish_stable_frames = 0;
    }

    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_THERMAL_SCORING, stage_started_us);

    // ── GMV-compensated motion scoring over ROI ──────────────────────────
    float best_motion = -1.0f;
    int   best_motion_x = 0, best_motion_y = 0;
    float motion_evidence_scale = anomaly_runtime_effective_motion_evidence_scale(cfg);
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
    float motion_appearance_global_motion_mean = 0.0f;
    float motion_appearance_global_motion_std = 0.0f;
    float motion_appearance_global_motion_load = 0.0f;
    float motion_appearance_zoom_motion_scale = 1.0f;
    float motion_appearance_broad_motion_scale = 1.0f;
    anomaly_motion_appearance_scorer_output_t motion_appearance_output;
    anomaly_motion_estimator_init_appearance_scorer_output(&motion_appearance_output);

    anomaly_motion_appearance_scorer_input_args_t motion_appearance_args = {
        .cfg = cfg,
        .registration = (const anomaly_motion_estimator_registration_t *)&registration,
        .curr_luma = curr_luma,
        .prev_luma = state->prev_luma,
        .prev_luma_width = state->prev_luma_width,
        .prev_luma_height = state->prev_luma_height,
        .width = width,
        .height = height,
        .motion_w = motion_w,
        .motion_h = motion_h,
        .motion_step = motion_step,
        .motion_count = (int)motion_count,
        .roi_x0 = roi_x0,
        .roi_x1 = roi_x1,
        .roi_y0 = roi_y0,
        .roi_y1 = roi_y1,
        .anomaly_detection_active = anomaly_detection_active,
        .scene_discontinuity = scene_discontinuity,
        .motion_evidence_scale = motion_evidence_scale,
        .saliency_motion_map = saliency_motion_map,
        .saliency_registration_map = saliency_registration_map,
        .sg_w = sg_w,
        .sg_h = sg_h,
        .sample_step = sample_step,
        .proposal_count = motion_appearance_proposal_count,
        .proposals = motion_appearance_proposals,
        .persist = state->motion_persist,
        .persist_w = state->motion_persist_w,
        .persist_h = state->motion_persist_h,
    };
    anomaly_motion_appearance_scorer_state_t motion_appearance_state;
    anomaly_motion_appearance_scorer_input_t motion_appearance_input;
    anomaly_motion_estimator_init_appearance_scorer_input(
            &motion_appearance_input,
            &motion_appearance_state,
            &motion_appearance_args);
    bool use_motion_tolerance = motion_appearance_input.use_motion_tolerance;
    bool use_stable_motion = motion_appearance_input.use_stable_motion;
    stage_started_us = anomaly_timing_now_us();
    if (anomaly_motion_estimator_appearance_scorer_ready(&motion_appearance_input)) {
        anomaly_motion_appearance_grid_bounds_t motion_grid_bounds;
        if (!anomaly_motion_estimator_appearance_grid_bounds(
                &motion_appearance_input,
                &motion_grid_bounds)) {
            goto motion_appearance_scoring_done;
        }
        int roi_mgx0 = motion_grid_bounds.x0;
        int roi_mgx1 = motion_grid_bounds.x1;
        int roi_mgy0 = motion_grid_bounds.y0;
        int roi_mgy1 = motion_grid_bounds.y1;

        float fw_m = (float)(width > 1 ? width - 1 : 1);
        float fh_m = (float)(height > 1 ? height - 1 : 1);
        float zoom_motion_scale =
            anomaly_motion_estimator_appearance_zoom_motion_scale(
                    anomaly_registration_model_scale(&registration));

        if (state->motion_persist == NULL ||
            state->motion_persist_w != motion_w ||
            state->motion_persist_h != motion_h) {
            free(state->motion_persist);
            state->motion_persist = (float *)calloc(motion_count, sizeof(float));
            state->motion_persist_w = state->motion_persist != NULL ? motion_w : 0;
            state->motion_persist_h = state->motion_persist != NULL ? motion_h : 0;
        }
        anomaly_motion_estimator_sync_appearance_scorer_state(
                &motion_appearance_state,
                state->motion_persist,
                state->motion_persist_w,
                state->motion_persist_h);

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
                if (!anomaly_motion_estimator_project_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                                           mx, my, &px_idx, &py_idx)) {
                    continue;
                }
                float metric_value = 0.0f;
                if (use_motion_tolerance) {
                    int best_dx = 0;
                    int best_dy = 0;
                    if (!anomaly_motion_estimator_find_residual_displacement(
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

        anomaly_motion_appearance_global_stats_t global_stats;
        anomaly_motion_estimator_appearance_global_stats(
                global_sum,
                global_sum2,
                global_count,
                motion_step,
                &global_stats);
        float global_motion_mean = global_stats.mean;
        float global_motion_std = global_stats.std;
        float motion_floor_px = global_stats.motion_floor_px;

        int strong_global_samples = 0;
        if (global_count > 0) {
            for (int my = roi_mgy0 + 1; my < roi_mgy1 - 1; my += global_stride) {
                for (int mx = roi_mgx0 + 1; mx < roi_mgx1 - 1; mx += global_stride) {
                    int px_idx = 0;
                    int py_idx = 0;
                    if (!anomaly_motion_estimator_project_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                                               mx, my, &px_idx, &py_idx)) {
                        continue;
                    }
                    float metric_value = 0.0f;
                    if (use_motion_tolerance) {
                        int best_dx = 0;
                        int best_dy = 0;
                        if (!anomaly_motion_estimator_find_residual_displacement(
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
        debug_global_motion_load =
            anomaly_motion_estimator_appearance_global_motion_load(
                    strong_global_samples,
                    global_count);
        float broad_motion_scale =
            anomaly_motion_estimator_appearance_broad_motion_scale(debug_global_motion_load);
        best_motion_zoom_scale = zoom_motion_scale;
        best_motion_broad_scale = broad_motion_scale;
        motion_appearance_global_motion_mean = global_motion_mean;
        motion_appearance_global_motion_std = global_motion_std;
        motion_appearance_global_motion_load = debug_global_motion_load;
        motion_appearance_zoom_motion_scale = zoom_motion_scale;
        motion_appearance_broad_motion_scale = broad_motion_scale;

        if (result_out != NULL) {
            anomaly_result_motion_appearance_debug_summary_publication_t
                    motion_debug_summary = {
                .scene_discontinuity = scene_discontinuity,
                .sample_step = motion_sample_step,
                .motion_step = motion_step,
                .global_count = global_count,
                .motion_candidate_count = motion_candidate_count,
                .global_motion_mean = global_motion_mean,
                .global_motion_std = global_motion_std,
                .zoom_motion_scale = zoom_motion_scale,
                .broad_motion_scale = broad_motion_scale,
                .global_motion_load = debug_global_motion_load,
            };
            anomaly_result_publish_motion_appearance_debug_summary(
                    result_out,
                    &motion_debug_summary);
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
                    if (!anomaly_motion_estimator_project_cell(&registration, width, height, motion_step, motion_w, motion_h,
                                                               mx, my, &px_idx, &py_idx)) {
                        continue;
                    }
                    float residual_metric = 0.0f;
                    float sample_dx = 0.0f;
                    float sample_dy = 0.0f;
                    if (use_motion_tolerance) {
                        int best_dx = 0;
                        int best_dy = 0;
                        if (!anomaly_motion_estimator_find_residual_displacement(
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

                    int texture_score = anomaly_registration_feature_score(curr_luma, motion_w, motion_h, mx, my);
                    float texture_scale = anomaly_motion_estimator_texture_scale(texture_score);
                    float structure_scale = anomaly_motion_estimator_structure_scale(curr_luma, motion_w, motion_h, mx, my);
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
                float parallax_motion_scale =
                    anomaly_motion_estimator_normalize_movement_mode(cfg) ==
                            ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE
                        ? anomaly_motion_estimator_appearance_parallax_motion_scale(
                            movement_snapshot.suppression_scale)
                        : 1.0f;
                motion_candidate_support[ci] = candidate_motion_score * parallax_motion_scale;

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
                anomaly_debug_insert_top_candidate(
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
                    anomaly_motion_estimator_stamp_support(
                        saliency_motion_map,
                        saliency_registration_map,
                        sg_w,
                        sg_h,
                        sal_sx,
                        sal_sy,
                        motion_support,
                        local_best_registration_scale);
                }
                if ((use_stable_motion || use_motion_tolerance) &&
                    candidate_motion_score > best_motion) {
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
    anomaly_motion_estimator_mirror_appearance_support_output(
            motion_appearance_proposals,
            motion_appearance_proposal_count,
            motion_candidate_support,
            motion_candidate_support_x,
            motion_candidate_support_y,
            motion_appearance_global_motion_mean,
            motion_appearance_global_motion_std,
            motion_appearance_global_motion_load,
            motion_appearance_zoom_motion_scale,
            motion_appearance_broad_motion_scale,
            &motion_appearance_output);
    (void)motion_appearance_output;
motion_appearance_scoring_done:
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_MOTION_SCORING, stage_started_us);

    stage_started_us = anomaly_timing_now_us();
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        bool saliency_motion_vector_ready =
            anomaly_registration_model_valid(&registration) &&
            !scene_discontinuity &&
            saliency_motion_map != NULL &&
            saliency_registration_map != NULL;
        size_t score_count = (size_t)sg_w * (size_t)sg_h;
        float *patch_score_map = NULL;
        float *patch_selection_map = NULL;
        if (anomaly_scratch_ensure_patch_capacity(state, score_count)) {
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
                                state->thermal.bg_luma,
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
                        anomaly_debug_insert_top_candidate(
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
                            state->thermal.bg_luma,
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
                                state->thermal.bg_luma,
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
                    anomaly_debug_insert_top_candidate(
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
            anomaly_saliency_choose_best_dark_patch(
                    patch_selection_map,
                    sg_w, sg_h,
                    roi_x0, roi_y0, sample_step,
                    &best_persist, &best_persist_x, &best_persist_y);
            if (best_thermal >= thermal_score_threshold && bg_valid &&
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
                int persist_cue = anomaly_saliency_classify_display_algorithm(
                        saliency_spatial_map,
                        saliency_color_map,
                        saliency_motion_map,
                        saliency_registration_map,
                        state->thermal.bg_luma,
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
    if (!color_stride_hold_frame && selective_refresh_active) {
        roi_state_updated = update_roi_state_selective_refresh(
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
                registration_health,
                prev_sample_lookup,
                appearance_refresh_mask,
                cfg->min_hits);
        if (!roi_state_updated) {
            rescan_mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.mode = ANOMALY_RESCAN_MODE_FULL;
            if (result_out != NULL) {
                anomaly_result_publish_rescan_mode(result_out, rescan_mode);
                anomaly_result_publish_scan_plan(result_out, &scan_plan);
            }
        }
    }
    if (!color_stride_hold_frame && !roi_state_updated) {
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
                registration_health,
                cfg->min_hits);
    }

    // ── Update prev_luma ────────────────────────────────────────────────
    anomaly_frame_history_update_motion_luma(state, curr_luma, motion_count, motion_w, motion_h);
    anomaly_frame_history_update_registration_luma(
            state,
            curr_registration_luma != NULL ? curr_registration_luma : curr_luma,
            motion_count,
            motion_w,
            motion_h);

    if (selective_refresh_active &&
        appearance_refresh_mask != NULL &&
        anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0) {
        anomaly_revisit_confirmation_t revisit_confirmation =
            find_target_revisit_confirmation(
                    state,
                    cfg,
                    &movement_snapshot,
                    roi_x0,
                    roi_y0,
                    roi_x1,
                    roi_y1,
                    width,
                    height,
                    sample_step,
                    sg_w,
                    sg_h,
                    appearance_refresh_mask,
                    sg_luma,
                    ii_sum,
                    ii_sum2,
                    saliency_spatial_map,
                    saliency_motion_map,
                    black_hot,
                    thermal_min_delta,
                    delta_mean,
                    delta_norm,
                    R);
        if (revisit_confirmation.valid) {
            float promoted_score = revisit_confirmation.score;
            if (promoted_score < thermal_score_threshold) {
                promoted_score = thermal_score_threshold;
            }
            if (promoted_score >= best_thermal) {
                best_thermal = promoted_score;
                best_thermal_x = revisit_confirmation.x;
                best_thermal_y = revisit_confirmation.y;
                scan_plan.revisit_confirmation_count++;
                if (revisit_confirmation.salience_boosted) {
                    scan_plan.revisit_salience_boost_count++;
                }
                if (revisit_confirmation.independent_motion_boosted) {
                    scan_plan.revisit_independent_motion_boost_count++;
                }
                if (revisit_confirmation.global_motion_rejected) {
                    scan_plan.revisit_global_motion_reject_count++;
                }
            }
        }
    }

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
        anomaly_roi_tracks_clear_saliency(state);
    }
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_COLOR)   && best_color   >= cfg->score_threshold) {
        raw_cx[0] = (float)best_color_x   / fw;
        raw_cy[0] = (float)best_color_y   / fh;
    }
    bool thermal_raw_publishable = best_thermal >= thermal_score_threshold;
    if (thermal_candidate_count > 0 && best_thermal_candidate_idx < 0) {
        thermal_raw_publishable = false;
    }
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) && thermal_raw_publishable) {
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
        state->saliency_display_algorithm = anomaly_saliency_classify_display_algorithm(
                saliency_spatial_map,
                saliency_color_map,
                saliency_motion_map,
                saliency_registration_map,
                state->thermal.bg_luma,
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
                anomaly_saliency_find_local_support(
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
            state->saliency_aux_display_algorithm[aux_count] = anomaly_saliency_classify_display_algorithm(
                    saliency_spatial_map,
                    saliency_color_map,
                    saliency_motion_map,
                    saliency_registration_map,
                    state->thermal.bg_luma,
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
            state->saliency_aux_display_algorithm[aux_count] = anomaly_saliency_classify_display_algorithm(
                    saliency_spatial_map,
                    saliency_color_map,
                    saliency_motion_map,
                    saliency_registration_map,
                    state->thermal.bg_luma,
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
            anomaly_roi_tracks_clear_saliency(state);
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
            float cand_x_norm = (float)cand_x / fw_norm;
            float cand_y_norm = (float)cand_y / fh_norm;
            bool allow_thermal_motion_override =
                anomaly_motion_estimator_normalize_movement_mode(cfg) !=
                    ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE ||
                anomaly_motion_estimator_allow_motion_override_at(
                    &movement_snapshot,
                    cand_x_norm,
                    cand_y_norm);
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
                    float bg = state->thermal.bg_luma[cand_idx];
                    float lum = (float)sg_luma[cand_idx];
                    float delta = black_hot ? (bg - lum) : (lum - bg);
                    if (delta >= thermal_min_delta) {
                        float temporal_score = (float)((delta - delta_mean) / delta_norm);
                        if (temporal_score > thermal_base) thermal_base = temporal_score;
                    }
                }
                float boosted_thermal = thermal_base + (ANOMALY_THERMAL_MOTION_BOOST * motion_excess * reliability);
                if (best_thermal >= thermal_score_threshold) {
                    int cur_sx = clamp_i32((best_thermal_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                    int cur_sy = clamp_i32((best_thermal_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                    float dx = ((float)cand_x - (float)best_thermal_x) / fw_norm;
                    float dy = ((float)cand_y - (float)best_thermal_y) / fh_norm;
                    float separation = sqrtf(dx * dx + dy * dy);
                    if (cand_sx == cur_sx && cand_sy == cur_sy && motion_excess > 0.0f) {
                        if (boosted_thermal > best_thermal) best_thermal = boosted_thermal;
                    } else if (allow_thermal_motion_override &&
                               strong_motion_override &&
                               separation >= 0.020f &&
                               boosted_thermal > best_thermal + 0.22f) {
                        best_thermal = boosted_thermal;
                        best_thermal_x = cand_x;
                        best_thermal_y = cand_y;
                    }
                } else if (allow_thermal_motion_override &&
                           strong_motion_override &&
                           boosted_thermal > best_thermal) {
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
            state->thermal.bg_warmup >= (ANOMALY_THERMAL_BG_WARMUP + ANOMALY_PUBLISH_BG_SETTLE_FRAMES);
        float derived_persist = -1.0f;
        int derived_persist_x = 0;
        int derived_persist_y = 0;
        int derived_persist_algorithm = 0;

        if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
            best_thermal >= thermal_score_threshold) {
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
                (!thermal_publish_settled || best_thermal < thermal_score_threshold + 0.65f);
            if (!weak_singleton) {
                float thermal_derived =
                    best_thermal +
                    (0.55f * support) +
                    (0.25f * motion_support);
                if (best_thermal_candidate_idx >= 0 &&
                    best_thermal_candidate_idx < thermal_candidate_count) {
                    anomaly_target_observation_t thermal_obs;
                    if (anomaly_target_observation_populate_thermal_candidate(
                            roi_x0,
                            roi_y0,
                            sample_step,
                            thermal_candidate_min_x[best_thermal_candidate_idx],
                            thermal_candidate_min_y[best_thermal_candidate_idx],
                            thermal_candidate_max_x[best_thermal_candidate_idx],
                            thermal_candidate_max_y[best_thermal_candidate_idx],
                            thermal_candidates[best_thermal_candidate_idx].pixel_x,
                            thermal_candidates[best_thermal_candidate_idx].pixel_y,
                            best_thermal,
                            thermal_candidate_quality_score[best_thermal_candidate_idx],
                            thermal_candidate_isolation_rank[best_thermal_candidate_idx],
                            thermal_candidate_patch_support[best_thermal_candidate_idx],
                            thermal_candidate_motion_support[best_thermal_candidate_idx],
                            thermal_score_threshold,
                            fw,
                            fh,
                            &thermal_obs)) {
                        thermal_derived +=
                            anomaly_target_observation_score_track_support_bonus(
                                state,
                                &thermal_obs,
                                anomaly_registration_health_confidence(registration_health),
                                motion_support);
                    }
                }
                derived_persist = thermal_derived;
                derived_persist_x = best_thermal_x;
                derived_persist_y = best_thermal_y;
                derived_persist_algorithm = ANOMALY_ALGO_THERMAL;
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
            if (best_color_target_observation_valid) {
                color_derived += anomaly_color_score_track_persistence_bonus(
                    state,
                    &best_color_target_observation,
                    anomaly_registration_health_confidence(registration_health),
                    motion_support);
            }
            if (color_derived > derived_persist) {
                derived_persist = color_derived;
                derived_persist_x = best_color_x;
                derived_persist_y = best_color_y;
                derived_persist_algorithm = ANOMALY_ALGO_COLOR;
            }
        }

        best_persist = derived_persist;
        best_persist_x = derived_persist_x;
        best_persist_y = derived_persist_y;
        if (derived_persist_algorithm == ANOMALY_ALGO_COLOR &&
            best_color_target_observation_valid) {
            best_persist_target_observation = best_color_target_observation;
            best_persist_target_observation.algorithm = ANOMALY_ALGO_PERSIST;
            best_persist_target_observation_valid = true;
        }
    }

    if (selective_refresh_active) {
        for (int ai = 0; ai < 4; ai++) {
            if (raw_cx[ai] < 0.0f || raw_cy[ai] < 0.0f) continue;
            if (!anomaly_target_revisit_point_inside_gate(
                    state,
                    raw_cx[ai],
                    raw_cy[ai],
                    min_hits,
                    NULL,
                    NULL)) {
                raw_cx[ai] = -1.0f;
                raw_cy[ai] = -1.0f;
                scan_plan.suppressed_offgate_winner_count++;
            }
        }
    }
    if (result_out != NULL) {
        anomaly_result_publish_scan_plan(result_out, &scan_plan);
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
            if (color_stride_hold_frame &&
                ai == 0 &&
                anomaly_registration_model_valid(&registration) &&
                !scene_discontinuity) {
                continue;
            }
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
        anomaly_saliency_update_aux_track(
                state,
                ti,
                saliency_aux_raw_cx[ti],
                saliency_aux_raw_cy[ti],
                gate * 0.90f,
                alpha);
    }

    anomaly_target_observation_t target_observations[
        4 + ANOMALY_SALIENCY_EXTRA_TRACKS +
        ANOMALY_TARGET_CANDIDATE_KEEP_MAX +
        ANOMALY_COLOR_PROVISIONAL_KEEP_MAX];
    int target_observation_count = 0;
    float target_half_side = clampf(sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f)) * 0.5f, 0.01f, 0.10f);
    for (int ai = 0; ai < 4 && target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0])); ai++) {
        if (raw_cx[ai] < 0.0f || raw_cy[ai] < 0.0f) continue;
        anomaly_target_observation_t *obs = &target_observations[target_observation_count++];
        memset(obs, 0, sizeof(*obs));
        int algorithm = (ai == 0) ? ANOMALY_ALGO_COLOR :
                        (ai == 1) ? ANOMALY_ALGO_THERMAL :
                        (ai == 2) ? ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0
                                    ? ANOMALY_ALGO_MOTION_TOLERANCE
                                    : ANOMALY_ALGO_MOTION) :
                                    ANOMALY_ALGO_PERSIST;
        bool use_dense_color_observation = (ai == 0 && best_color_target_observation_valid) ||
                                           (ai == 3 && best_persist_target_observation_valid);
        if (use_dense_color_observation) {
            *obs = (ai == 0) ? best_color_target_observation : best_persist_target_observation;
            obs->algorithm = algorithm;
            obs->confidence = clampf(
                fmaxf(obs->confidence, 0.36f + 0.07f * (float)state->acc_hits[ai]),
                0.35f,
                0.96f);
        } else {
            obs->valid = true;
            obs->publish_confirming = true;
            obs->algorithm = algorithm;
            obs->center_x_norm = state->acc_cx[ai];
            obs->center_y_norm = state->acc_cy[ai];
            obs->half_w_norm = target_half_side;
            obs->half_h_norm = target_half_side;
            obs->support_radius_norm = target_half_side * 1.8f;
            obs->confidence = clampf(0.35f + 0.08f * (float)state->acc_hits[ai], 0.35f, 0.95f);
        }
    }
    for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS &&
                     target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0])); ti++) {
        if (saliency_aux_raw_cx[ti] < 0.0f || saliency_aux_raw_cy[ti] < 0.0f) continue;
        anomaly_target_observation_t *obs = &target_observations[target_observation_count++];
        memset(obs, 0, sizeof(*obs));
        obs->valid = true;
        obs->publish_confirming = true;
        obs->algorithm = ANOMALY_ALGO_PERSIST;
        obs->center_x_norm = state->saliency_aux_cx[ti];
        obs->center_y_norm = state->saliency_aux_cy[ti];
        obs->half_w_norm = target_half_side * 0.90f;
        obs->half_h_norm = target_half_side * 0.90f;
        obs->support_radius_norm = target_half_side * 1.6f;
        obs->confidence = clampf(0.30f + 0.08f * (float)state->saliency_aux_hits[ti], 0.30f, 0.88f);
    }
    if (scan_plan.mode == ANOMALY_RESCAN_MODE_FULL &&
        anomaly_detection_active &&
        anomaly_color_frontend_uses_fresh_winner_gate(color_frontend_mode) &&
        color_candidate_count > 0) {
        int eligible_indices[ANOMALY_MAX_COLOR_CANDIDATES];
        float eligible_scores[ANOMALY_MAX_COLOR_CANDIDATES];
        int eligible_count = 0;
        float score_floor = cfg->score_threshold - ANOMALY_COLOR_PROVISIONAL_SCORE_SLACK;
        for (int ci = 0; ci < color_candidate_count && ci < ANOMALY_MAX_COLOR_CANDIDATES; ci++) {
            float cx_norm = (float)color_candidates[ci].pixel_x /
                            (float)(width > 1 ? width - 1 : 1);
            float cy_norm = (float)color_candidates[ci].pixel_y /
                            (float)(height > 1 ? height - 1 : 1);
            if (color_candidate_above_threshold[ci]) continue;
            if (color_candidate_final_score[ci] < score_floor) continue;
            if (anomaly_color_candidate_near_reviewed_fp_cluster(cx_norm, cy_norm)) continue;
            if (color_candidate_fill[ci] < 0.42f ||
                color_candidate_quality[ci] < 0.32f ||
                color_candidate_isolation[ci] < 0.28f ||
                color_candidate_ring_fraction[ci] > 0.20f ||
                color_candidate_support_mass[ci] > 0.42f ||
                color_candidate_hist_rarity[ci] < ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY ||
                color_candidate_scene_commonness_score[ci] > 0.82f ||
                color_candidate_small_target_span_ratio[ci] > 1.05f ||
                color_candidate_small_target_area_ratio[ci] > 0.95f) {
                continue;
            }
            float score_rank = clampf((color_candidate_final_score[ci] - score_floor) /
                                      ANOMALY_COLOR_PROVISIONAL_SCORE_SLACK,
                                      0.0f,
                                      1.0f);
            float candidate_rank =
                0.40f * clamp01f(color_candidate_uniqueness_rank[ci]) +
                0.22f * clamp01f(color_candidate_retention_rank[ci]) +
                0.16f * score_rank +
                0.12f * clamp01f(color_candidate_quality[ci]) +
                0.10f * clamp01f(color_candidate_isolation[ci]);
            anomaly_appearance_insert_ranked_index(
                    ci,
                    candidate_rank,
                    eligible_indices,
                    eligible_scores,
                    &eligible_count,
                    ANOMALY_MAX_COLOR_CANDIDATES);
        }
        scan_plan.provisional_candidate_count += eligible_count;
        int color_candidate_limit = cfg->color_target_candidate_limit;
        if (color_candidate_limit < 1) color_candidate_limit = 1;
        if (color_candidate_limit > ANOMALY_COLOR_PROVISIONAL_KEEP_MAX) {
            color_candidate_limit = ANOMALY_COLOR_PROVISIONAL_KEEP_MAX;
        }
        int keep_count = eligible_count < color_candidate_limit
            ? eligible_count
            : color_candidate_limit;
        for (int ki = 0;
             ki < keep_count &&
             target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0]));
             ki++) {
            int ci = eligible_indices[ki];
            anomaly_target_observation_t candidate_obs;
            if (!anomaly_target_observation_populate_color_candidate(
                    roi_x0,
                    roi_y0,
                    sample_step,
                    color_candidate_min_x[ci],
                    color_candidate_min_y[ci],
                    color_candidate_max_x[ci],
                    color_candidate_max_y[ci],
                    color_candidates[ci].pixel_x,
                    color_candidates[ci].pixel_y,
                    color_candidate_final_score[ci],
                    color_candidate_quality[ci],
                    color_candidate_isolation[ci],
                    cfg->score_threshold,
                    fw,
                    fh,
                    ANOMALY_ALGO_COLOR,
                    &candidate_obs)) {
                continue;
            }
            candidate_obs.publish_confirming = false;
            candidate_obs.confidence = clampf(candidate_obs.confidence * 0.72f, 0.18f, 0.70f);
            if (anomaly_target_observation_near_existing(
                    target_observations,
                    target_observation_count,
                    &candidate_obs)) {
                continue;
            }
            target_observations[target_observation_count++] = candidate_obs;
            scan_plan.provisional_candidate_selected_count++;
        }
    }
    if (scan_plan.mode == ANOMALY_RESCAN_MODE_FULL &&
        anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
        thermal_candidate_count > 0) {
        int eligible_indices[ANOMALY_MAX_THERMAL_CANDIDATES];
        float eligible_scores[ANOMALY_MAX_THERMAL_CANDIDATES];
        anomaly_thermal_provisional_reserve_candidate_t
                reserve_candidates[ANOMALY_MAX_THERMAL_CANDIDATES];
        int eligible_count = 0;
        memset(reserve_candidates, 0, sizeof(reserve_candidates));
        float small_target_limit_px = effective_thermal_small_target_span_px(cfg, width, height);
        for (int ci = 0; ci < thermal_candidate_count && ci < ANOMALY_MAX_THERMAL_CANDIDATES; ci++) {
            float final_score = thermal_candidates[ci].thermal_score;
            float score_floor = cfg->score_threshold - ANOMALY_TARGET_CANDIDATE_SCORE_SLACK;
            bool score_eligible = final_score >= score_floor;
            bool shape_eligible =
                thermal_candidate_isolation_rank[ci] >= 0.30f ||
                thermal_candidate_patch_support[ci] >= 0.08f ||
                thermal_candidate_motion_support[ci] >= 0.08f;
            float span_px = thermal_candidate_span[ci] * (float)sample_step;
            float compact_rank = 0.0f;
            if (span_px > 0.0f && small_target_limit_px > 0.0f) {
                compact_rank = clampf(1.0f - (span_px / (small_target_limit_px * 1.25f)), 0.0f, 1.0f);
            }
            float score_rank = clampf((final_score - score_floor) /
                                      (ANOMALY_TARGET_CANDIDATE_SCORE_SLACK + 1.60f),
                                      0.0f,
                                      1.0f);
            float candidate_rank =
                0.34f * score_rank +
                0.20f * clamp01f(thermal_candidate_isolation_rank[ci]) +
                0.16f * clamp01f(thermal_candidate_quality_score[ci]) +
                0.12f * clamp01f(thermal_candidate_patch_support[ci]) +
                0.10f * clamp01f(thermal_candidate_motion_support[ci]) +
                0.08f * compact_rank;
            float cx_norm = (float)thermal_candidates[ci].pixel_x /
                            (float)(fw > 1 ? fw - 1 : 1);
            float cy_norm = (float)thermal_candidates[ci].pixel_y /
                            (float)(fh > 1 ? fh - 1 : 1);
            reserve_candidates[ci] =
                (anomaly_thermal_provisional_reserve_candidate_t) {
                    .valid = true,
                    .near_reviewed_fp_cluster =
                        anomaly_thermal_candidate_near_reviewed_fp_cluster(cx_norm, cy_norm),
                    .final_score = final_score,
                    .score_threshold = cfg->score_threshold,
                    .area = thermal_candidate_area[ci],
                    .span = thermal_candidate_span[ci],
                    .fill = thermal_candidate_fill[ci],
                    .center_share = thermal_candidate_center_share[ci],
                    .quality = thermal_candidate_quality_score[ci],
                    .patch_support = thermal_candidate_patch_support[ci],
                };
            if (anomaly_motion_estimator_normalize_movement_mode(cfg) ==
                    ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
                anomaly_debug_movement_tile_t cand_tile;
                bool cand_tile_valid = anomaly_motion_estimator_query_snapshot_at_norm(
                        &movement_snapshot,
                        cx_norm,
                        cy_norm,
                        &cand_tile);
                float independent_score = cand_tile_valid
                    ? anomaly_motion_estimator_tile_independent_score(&cand_tile)
                    : 0.0f;
                reserve_candidates[ci].movement_tile_valid = cand_tile_valid;
                reserve_candidates[ci].movement_independent =
                    cand_tile_valid &&
                    anomaly_motion_estimator_tile_is_independent(&cand_tile, independent_score);
                reserve_candidates[ci].movement_parallax =
                    cand_tile_valid &&
                    anomaly_motion_estimator_tile_is_parallax_like(&cand_tile);
                reserve_candidates[ci].movement_confidence =
                    cand_tile_valid ? cand_tile.confidence : 0.0f;
            }
            if (thermal_target_trace.enabled &&
                thermal_target_trace.extracted_rank >= 0 &&
                ci == thermal_target_trace.extracted_rank) {
                thermal_target_trace.provisional_candidate_index = ci;
                thermal_target_trace.provisional_score_floor = score_floor;
                thermal_target_trace.provisional_final_score = final_score;
                thermal_target_trace.provisional_score_eligible = score_eligible;
                thermal_target_trace.provisional_shape_eligible = shape_eligible;
                thermal_target_trace.provisional_candidate_rank = candidate_rank;
            }
            if (!score_eligible || !shape_eligible) continue;
            anomaly_appearance_insert_ranked_index(
                    ci,
                    candidate_rank,
                    eligible_indices,
                    eligible_scores,
                    &eligible_count,
                    ANOMALY_MAX_THERMAL_CANDIDATES);
        }
        scan_plan.provisional_candidate_count = eligible_count;
        int keep_count = 0;
        if (eligible_count > 0) {
            keep_count = (int)ceilf((float)eligible_count * ANOMALY_TARGET_CANDIDATE_KEEP_FRACTION);
            if (keep_count < ANOMALY_TARGET_CANDIDATE_KEEP_MIN) {
                keep_count = ANOMALY_TARGET_CANDIDATE_KEEP_MIN;
            }
            if (keep_count > ANOMALY_TARGET_CANDIDATE_KEEP_MAX) {
                keep_count = ANOMALY_TARGET_CANDIDATE_KEEP_MAX;
            }
            if (keep_count > eligible_count) keep_count = eligible_count;
        }
        int reserved_index = anomaly_appearance_select_thermal_provisional_reserve(
                eligible_indices,
                eligible_count,
                keep_count,
                reserve_candidates,
                thermal_candidate_count);
        for (int ki = 0;
             ki < keep_count + (reserved_index >= 0 ? 1 : 0) &&
             target_observation_count < (int)(sizeof(target_observations) / sizeof(target_observations[0]));
             ki++) {
            int ci = ki < keep_count ? eligible_indices[ki] : reserved_index;
            bool reserved_candidate = ki >= keep_count && ci == reserved_index;
            float cx_norm = (float)thermal_candidates[ci].pixel_x /
                            (float)(fw > 1 ? fw - 1 : 1);
            float cy_norm = (float)thermal_candidates[ci].pixel_y /
                            (float)(fh > 1 ? fh - 1 : 1);
            if (anomaly_thermal_candidate_near_reviewed_fp_cluster(cx_norm, cy_norm)) {
                continue;
            }
            if (anomaly_motion_estimator_normalize_movement_mode(cfg) ==
                    ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE) {
                anomaly_debug_movement_tile_t cand_tile;
                bool cand_tile_valid = anomaly_motion_estimator_query_snapshot_at_norm(
                        &movement_snapshot,
                        cx_norm,
                        cy_norm,
                        &cand_tile);
                float independent_score = cand_tile_valid
                    ? anomaly_motion_estimator_tile_independent_score(&cand_tile)
                    : 0.0f;
                bool independent = cand_tile_valid &&
                    anomaly_motion_estimator_tile_is_independent(&cand_tile, independent_score);
                bool parallax = cand_tile_valid &&
                    anomaly_motion_estimator_tile_is_parallax_like(&cand_tile);
                if (anomaly_thermal_provisional_candidate_is_weak_parallax_singleton(
                            thermal_candidate_area[ci],
                            thermal_candidate_span[ci],
                            thermal_candidates[ci].thermal_score,
                            cfg->score_threshold,
                            cand_tile_valid,
                            parallax,
                            independent,
                            cand_tile_valid ? cand_tile.confidence : 0.0f)) {
                    continue;
                }
            }
            anomaly_target_observation_t candidate_obs;
            if (!anomaly_target_observation_populate_thermal_candidate(
                    roi_x0,
                    roi_y0,
                    sample_step,
                    thermal_candidate_min_x[ci],
                    thermal_candidate_min_y[ci],
                    thermal_candidate_max_x[ci],
                    thermal_candidate_max_y[ci],
                    thermal_candidates[ci].pixel_x,
                    thermal_candidates[ci].pixel_y,
                    thermal_candidates[ci].thermal_score,
                    thermal_candidate_quality_score[ci],
                    thermal_candidate_isolation_rank[ci],
                    thermal_candidate_patch_support[ci],
                    thermal_candidate_motion_support[ci],
                    cfg->score_threshold,
                    fw,
                    fh,
                    &candidate_obs)) {
                continue;
            }
            if (reserved_candidate) {
                candidate_obs.publish_confirming = true;
            }
            if (anomaly_target_observation_near_existing(
                    target_observations,
                    target_observation_count,
                    &candidate_obs)) {
                if (anomaly_target_observation_replace_thermal_correction(
                            target_observations,
                            target_observation_count,
                            &candidate_obs)) {
                    scan_plan.provisional_candidate_selected_count++;
                    if (thermal_target_trace.enabled &&
                        ci == thermal_target_trace.provisional_candidate_index) {
                        thermal_target_trace.provisional_selected_rank = ki;
                        thermal_target_trace.provisional_selected_score =
                            reserved_candidate
                                ? reserve_candidates[ci].final_score
                                : eligible_scores[ki];
                    }
                    continue;
                }
                if (thermal_target_trace.enabled &&
                    ci == thermal_target_trace.provisional_candidate_index) {
                    thermal_target_trace.provisional_near_existing_skip = true;
                }
                continue;
            }
            target_observations[target_observation_count++] = candidate_obs;
            scan_plan.provisional_candidate_selected_count++;
            if (thermal_target_trace.enabled &&
                ci == thermal_target_trace.provisional_candidate_index) {
                thermal_target_trace.provisional_selected_rank = ki;
                thermal_target_trace.provisional_selected_score =
                    reserved_candidate ? reserve_candidates[ci].final_score : eligible_scores[ki];
            }
        }
    }
    if (result_out != NULL) {
        anomaly_result_publish_scan_plan(result_out, &scan_plan);
    }
    bool clear_roi_tracks = anomaly_target_tracks_update_from_observations(
            state,
            target_observations,
            target_observation_count,
            registration_health,
            anomaly_registration_health_confidence(registration_health));
    if (clear_roi_tracks) {
        anomaly_roi_tracks_clear_all(state);
    }
    if (thermal_target_trace.enabled && thermal_target_trace.valid) {
        anomaly_debug_movement_tile_t target_tile;
        if (anomaly_motion_estimator_query_snapshot_at_norm(
                &movement_snapshot,
                thermal_target_trace.target_x_norm,
                thermal_target_trace.target_y_norm,
                &target_tile)) {
            thermal_target_trace.movement_tile_valid = true;
            thermal_target_trace.movement_residual_px = target_tile.residual_px;
            thermal_target_trace.movement_independent_score =
                anomaly_motion_estimator_tile_independent_score(&target_tile);
            thermal_target_trace.movement_confidence = target_tile.confidence;
            thermal_target_trace.movement_layer_class = target_tile.layer_class;
            thermal_target_trace.movement_independent =
                anomaly_motion_estimator_tile_is_independent(
                        &target_tile,
                        thermal_target_trace.movement_independent_score);
            thermal_target_trace.movement_parallax =
                anomaly_motion_estimator_tile_is_parallax_like(&target_tile);
        }
        if (thermal_target_trace.target_sx >= 0 &&
            thermal_target_trace.target_sy >= 0 &&
            thermal_target_trace.target_sx < sg_w &&
            thermal_target_trace.target_sy < sg_h) {
            thermal_target_trace.movement_motion_support =
                anomaly_motion_estimator_nearest_candidate_support_norm(
                        motion_candidate_support,
                        motion_candidate_support_x,
                        motion_candidate_support_y,
                        motion_candidate_count,
                        width,
                        height,
                        thermal_target_trace.target_x_norm,
                        thermal_target_trace.target_y_norm,
                        0.045f);
        }
        if (thermal_target_trace.local_peak_raw_sx >= 0 &&
            thermal_target_trace.local_peak_raw_sy >= 0) {
            float local_peak_x_norm =
                (float)(roi_x0 + thermal_target_trace.local_peak_raw_sx * sample_step) /
                fmaxf((float)fw, 1.0f);
            float local_peak_y_norm =
                (float)(roi_y0 + thermal_target_trace.local_peak_raw_sy * sample_step) /
                fmaxf((float)fh, 1.0f);
            anomaly_debug_movement_tile_t local_peak_tile;
            if (anomaly_motion_estimator_query_snapshot_at_norm(
                    &movement_snapshot,
                    local_peak_x_norm,
                    local_peak_y_norm,
                    &local_peak_tile)) {
                thermal_target_trace.local_peak_movement_tile_valid = true;
                thermal_target_trace.local_peak_movement_residual_px =
                    local_peak_tile.residual_px;
                thermal_target_trace.local_peak_movement_independent_score =
                    anomaly_motion_estimator_tile_independent_score(&local_peak_tile);
                thermal_target_trace.local_peak_movement_confidence =
                    local_peak_tile.confidence;
                thermal_target_trace.local_peak_movement_layer_class =
                    local_peak_tile.layer_class;
                thermal_target_trace.local_peak_movement_independent =
                    anomaly_motion_estimator_tile_is_independent(
                            &local_peak_tile,
                            thermal_target_trace.local_peak_movement_independent_score);
                thermal_target_trace.local_peak_movement_parallax =
                    anomaly_motion_estimator_tile_is_parallax_like(&local_peak_tile);
            }
            if (thermal_target_trace.local_peak_raw_sx < sg_w &&
                thermal_target_trace.local_peak_raw_sy < sg_h) {
                thermal_target_trace.local_peak_movement_motion_support =
                    anomaly_motion_estimator_nearest_candidate_support_norm(
                            motion_candidate_support,
                            motion_candidate_support_x,
                            motion_candidate_support_y,
                            motion_candidate_count,
                            width,
                            height,
                            local_peak_x_norm,
                            local_peak_y_norm,
                            0.045f);
            }
        }
        float best_dist = 1.0e9f;
        int best_track_idx = -1;
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            const anomaly_target_track_t *track = &state->target_tracks[ti];
            if (!track->active) continue;
            float dx = track->center_x_norm - thermal_target_trace.target_x_norm;
            float dy = track->center_y_norm - thermal_target_trace.target_y_norm;
            float dist = sqrtf(dx * dx + dy * dy);
            float gate = fmaxf(ANOMALY_TARGET_MATCH_GATE,
                               fmaxf(track->support_radius_norm, 0.012f) * 1.75f);
            if (dist <= gate && dist < best_dist) {
                best_dist = dist;
                best_track_idx = ti;
            }
        }
        if (best_track_idx >= 0) {
            const anomaly_target_track_t *track = &state->target_tracks[best_track_idx];
            thermal_target_trace.matched_track_index = best_track_idx;
            thermal_target_trace.matched_track_id = track->id;
            thermal_target_trace.matched_track_hit_count = track->hit_count;
            thermal_target_trace.matched_track_miss_count = track->miss_count;
            thermal_target_trace.matched_track_hold_count = track->hold_count;
            thermal_target_trace.matched_track_publish_confirmed = track->publish_confirmed;
        }
        thermal_target_trace.raw_delta_rescue_score =
            anomaly_thermal_shadow_raw_delta_rescue_score(
                    thermal_target_trace.component_peak_delta,
                    thermal_target_trace.component_mean_delta,
                    thermal_target_trace.component_area,
                    thermal_target_trace.component_span,
                    thermal_target_trace.component_fill,
                    thermal_target_trace.component_quality,
                    thermal_target_trace.provisional_final_score >= 0.0f
                        ? thermal_target_trace.provisional_final_score
                        : thermal_target_trace.target_score,
                    cfg->score_threshold,
                    thermal_min_delta,
                    delta_norm,
                    thermal_target_trace.movement_independent_score,
                    thermal_target_trace.movement_independent,
                    thermal_target_trace.matched_track_index >= 0);
        thermal_target_trace.raw_delta_rescue_eligible =
            anomaly_thermal_shadow_raw_delta_rescue_eligible(
                    thermal_target_trace.component_peak_delta,
                    thermal_target_trace.component_area,
                    thermal_target_trace.component_span,
                    thermal_target_trace.component_quality,
                    thermal_target_trace.provisional_final_score >= 0.0f
                        ? thermal_target_trace.provisional_final_score
                        : thermal_target_trace.target_score,
                    cfg->score_threshold,
                    thermal_min_delta,
                    thermal_target_trace.movement_independent);
        thermal_target_trace.would_promote_movement_rescue =
            thermal_target_trace.raw_delta_rescue_eligible &&
            thermal_target_trace.raw_delta_rescue_score >= 0.62f;
        anomaly_thermal_shadow_shape_t movement_shadow_shape = {
            .local_peak_movement_tile_valid =
                thermal_target_trace.local_peak_movement_tile_valid,
            .movement_motion_support =
                thermal_target_trace.movement_motion_support,
            .local_peak_movement_motion_support =
                thermal_target_trace.local_peak_movement_motion_support,
            .target_spatial_score =
                thermal_target_trace.target_spatial_score,
            .local_peak_raw_spatial_score =
                thermal_target_trace.local_peak_raw_spatial_score,
            .micro_candidate_ring_hot_fraction =
                thermal_target_trace.micro_candidate_ring_hot_fraction,
            .micro_candidate_compactness =
                thermal_target_trace.micro_candidate_compactness,
            .micro_candidate_one_sided_support =
                thermal_target_trace.micro_candidate_one_sided_support,
            .local_window_raw_delta_mean =
                thermal_target_trace.local_window_raw_delta_mean,
            .micro_candidate_hot_count =
                thermal_target_trace.micro_candidate_hot_count,
            .local_window_hot_count =
                thermal_target_trace.local_window_hot_count,
            .micro_candidate_centroid_offset =
                thermal_target_trace.micro_candidate_centroid_offset,
        };
        thermal_target_trace.movement_rescue_reject_reason =
            anomaly_thermal_shadow_movement_reject_reason(
                    &movement_shadow_shape,
                    cfg->score_threshold);
        float movement_shadow_motion_score =
            fmaxf(thermal_target_trace.movement_motion_support,
                  thermal_target_trace.local_peak_movement_motion_support);
        float movement_shadow_spatial_score =
            fmaxf(thermal_target_trace.target_spatial_score,
                  thermal_target_trace.local_peak_raw_spatial_score);
        thermal_target_trace.movement_shadow_motion_support =
            movement_shadow_motion_score >= 1.0f;
        thermal_target_trace.movement_shadow_parallax_penalty =
            thermal_target_trace.local_peak_movement_parallax &&
            !thermal_target_trace.local_peak_movement_independent;
        thermal_target_trace.movement_shadow_thermal_support =
            movement_shadow_spatial_score >= cfg->score_threshold;
        thermal_target_trace.movement_shadow_clutter_veto =
            thermal_target_trace.micro_candidate_ring_hot_fraction > 0.25f ||
            thermal_target_trace.local_window_raw_delta_mean > 10.0f ||
            thermal_target_trace.micro_candidate_hot_count > 10 ||
            thermal_target_trace.local_window_hot_count > 10 ||
            thermal_target_trace.micro_candidate_ring_hot_fraction < 0.0f ||
            thermal_target_trace.micro_candidate_compactness < 0.0f ||
            thermal_target_trace.micro_candidate_one_sided_support < 0.0f ||
            (thermal_target_trace.micro_candidate_compactness >= 0.0f &&
             thermal_target_trace.micro_candidate_compactness < 0.40f) ||
            thermal_target_trace.micro_candidate_one_sided_support > 0.60f ||
            thermal_target_trace.micro_candidate_centroid_offset > 3.0f;
        bool movement_shadow_pass =
            thermal_target_trace.movement_rescue_reject_reason ==
            ANOMALY_MOVEMENT_SHADOW_REJECT_NONE;
        bool target_already_publishable =
            thermal_target_trace.provisional_final_score >= cfg->score_threshold ||
            thermal_target_trace.target_score >= cfg->score_threshold;
        thermal_target_trace.movement_rescue_would_publish =
            movement_shadow_pass && !target_already_publishable;
        thermal_target_trace.movement_boost_would_publish =
            movement_shadow_pass &&
            !thermal_target_trace.movement_rescue_would_publish &&
            !thermal_target_trace.matched_track_publish_confirmed;
    }
    if (cfg->stride_mode == ANOMALY_STRIDE_MODE_ADAPTIVE) {
        bool target_rich_full_scan =
            rescan_mode == ANOMALY_RESCAN_MODE_FULL &&
            (thermal_candidate_count >= 3 ||
             color_coarse_component_count >= 4 ||
             target_observation_count >= 2);
        if (target_rich_full_scan) {
            state->adaptive_target_rich_frames = 12;
        } else if (state->adaptive_target_rich_frames > 0) {
            state->adaptive_target_rich_frames--;
        }
    } else {
        state->adaptive_target_rich_frames = 0;
    }
    anomaly_target_tracks_update_movement_evidence(state, &movement_sidecar);
    if (result_out != NULL) {
        anomaly_result_publish_movement_debug(result_out, &movement_sidecar);
    }
    if (state->roi_state.valid) {
        anomaly_target_revisit_annotate_roi_cells(&state->roi_state, state, min_hits);
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_TARGET_TRACKING, stage_started_us);

    bool publish_motion_unstable =
        !scene_discontinuity &&
        anomaly_registration_model_valid(&registration) &&
        (sim.mean_residual > ANOMALY_PUBLISH_GMV_RESIDUAL_GATE ||
         best_motion_zoom_scale < ANOMALY_PUBLISH_ZOOM_SCALE_GATE ||
         debug_global_motion_load > ANOMALY_PUBLISH_GLOBAL_MOTION_GATE);
    bool publish_scene_stable =
        !scene_discontinuity &&
        bg_temporal_ready &&
        (!anomaly_registration_model_valid(&registration) || sim.mean_residual <= ANOMALY_PUBLISH_STABLE_GMV_RESIDUAL) &&
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
        anomaly_roi_tracks_clear_all(state);
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
        box_count = anomaly_result_build_boxes(
                state,
                cfg,
                motion_box_algorithm,
                boxes,
                ANOMALY_MAX_BOXES_PER_FRAME);
        int filtered_count = 0;
        for (int bi = 0; bi < box_count; bi++) {
            bool motion_box =
                boxes[bi].algorithm == ANOMALY_ALGO_MOTION ||
                boxes[bi].algorithm == ANOMALY_ALGO_MOTION_TOLERANCE;
            float box_cx = 0.5f * (boxes[bi].left_norm + boxes[bi].right_norm);
            float box_cy = 0.5f * (boxes[bi].top_norm + boxes[bi].bottom_norm);
            if (motion_box &&
                anomaly_thermal_candidate_near_reviewed_fp_cluster(box_cx, box_cy)) {
                continue;
            }
            boxes[filtered_count++] = boxes[bi];
        }
        box_count = filtered_count;
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
        float overlay_score_floor = thermal_score_threshold;
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
                anomaly_debug_append_rect(
                        overlay_boxes,
                        &overlay_box_count,
                        ANOMALY_MAX_OVERLAY_BOXES,
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
                anomaly_debug_append_rect(
                        overlay_boxes,
                        &overlay_box_count,
                        ANOMALY_MAX_OVERLAY_BOXES,
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
        anomaly_result_publish_boxes(result_out, boxes, box_count);
        anomaly_result_motion_appearance_debug_result_publication_t
                motion_debug_result = {
            .raw_score = best_motion,
            .raw_x = best_motion_x,
            .raw_y = best_motion_y,
            .frame_w = fw,
            .frame_h = fh,
            .winner_component_area_frac = best_motion_component_area_frac,
            .winner_component_span_frac = best_motion_component_span_frac,
            .winner_component_fill_ratio = best_motion_component_fill_ratio,
            .zoom_motion_scale = best_motion_zoom_scale,
            .broad_motion_scale = best_motion_broad_scale,
            .global_motion_load = debug_global_motion_load,
            .winner_texture_scale = best_motion_texture_scale,
            .winner_structure_scale = best_motion_structure_scale,
            .winner_support_scale = best_motion_support_scale,
            .winner_persistence_scale = best_motion_persistence_scale,
            .top_candidates = motion_top,
            .top_candidate_count = motion_top_count,
        };
        anomaly_result_publish_motion_appearance_debug_result(
                result_out,
                &motion_debug_result);
        anomaly_result_thermal_debug_summary_publication_t thermal_debug_summary = {
            .bg_ready = bg_valid,
            .raw_score = best_thermal,
            .raw_x = best_thermal_x,
            .raw_y = best_thermal_y,
            .frame_w = fw,
            .frame_h = fh,
            .frame_delta_mean = delta_mean,
            .frame_delta_norm = delta_norm,
            .frame_blob_contrast_mean = frame_blob_contrast_mean,
            .frame_blob_contrast_std = frame_blob_contrast_std,
            .winning_candidate_index = best_thermal_candidate_idx,
            .candidate_count = thermal_candidate_count,
        };
        anomaly_result_publish_thermal_debug_summary(result_out, &thermal_debug_summary);
        anomaly_result_thermal_debug_target_base_publication_t thermal_target_base = {
            .enabled = thermal_target_trace.enabled,
            .valid = thermal_target_trace.valid,
            .inside_scan_zone = thermal_target_trace.inside_scan_zone,
            .pixel_x = thermal_target_trace.target_px,
            .pixel_y = thermal_target_trace.target_py,
            .sample_x = thermal_target_trace.target_sx,
            .sample_y = thermal_target_trace.target_sy,
            .x_norm = thermal_target_trace.target_x_norm,
            .y_norm = thermal_target_trace.target_y_norm,
            .target_delta = thermal_target_trace.target_delta,
            .target_score = thermal_target_trace.target_score,
            .target_raw_delta = thermal_target_trace.target_raw_delta,
            .target_raw_score = thermal_target_trace.target_raw_score,
            .target_temporal_margin = thermal_target_trace.target_temporal_margin,
            .target_spatial_abs_delta = thermal_target_trace.target_spatial_abs_delta,
            .target_spatial_std = thermal_target_trace.target_spatial_std,
            .target_spatial_score = thermal_target_trace.target_spatial_score,
            .hot_eligible = thermal_target_trace.hot_eligible,
            .started_component = thermal_target_trace.started_component,
            .local_max = thermal_target_trace.local_max,
            .local_peak_radius = thermal_target_trace.local_peak_radius,
            .local_peak_sample_x = thermal_target_trace.local_peak_sx,
            .local_peak_sample_y = thermal_target_trace.local_peak_sy,
            .local_peak_delta = thermal_target_trace.local_peak_delta,
            .local_peak_score = thermal_target_trace.local_peak_score,
            .local_peak_distance = thermal_target_trace.local_peak_distance,
            .local_peak_raw_sample_x = thermal_target_trace.local_peak_raw_sx,
            .local_peak_raw_sample_y = thermal_target_trace.local_peak_raw_sy,
            .local_peak_raw_delta = thermal_target_trace.local_peak_raw_delta,
            .local_peak_raw_score = thermal_target_trace.local_peak_raw_score,
            .local_peak_raw_distance = thermal_target_trace.local_peak_raw_distance,
            .local_peak_raw_temporal_margin =
                thermal_target_trace.local_peak_raw_temporal_margin,
            .local_peak_raw_spatial_abs_delta =
                thermal_target_trace.local_peak_raw_spatial_abs_delta,
            .local_peak_raw_spatial_std = thermal_target_trace.local_peak_raw_spatial_std,
            .local_peak_raw_spatial_score = thermal_target_trace.local_peak_raw_spatial_score,
            .local_peak_is_component_seed = thermal_target_trace.local_peak_is_component_seed,
            .local_window_sample_count = thermal_target_trace.local_window_sample_count,
            .local_window_hot_count = thermal_target_trace.local_window_hot_count,
            .local_window_raw_delta_sum = thermal_target_trace.local_window_raw_delta_sum,
            .local_window_raw_delta_mean = thermal_target_trace.local_window_raw_delta_mean,
            .local_window_weighted_centroid_dx =
                thermal_target_trace.local_window_weighted_centroid_dx,
            .local_window_weighted_centroid_dy =
                thermal_target_trace.local_window_weighted_centroid_dy,
        };
        anomaly_result_publish_thermal_debug_target_base(result_out, &thermal_target_base);
        anomaly_result_thermal_debug_target_micro_candidate_publication_t thermal_target_micro = {
            .would_create = thermal_target_trace.micro_candidate_would_create,
            .reject_reason = thermal_target_trace.micro_candidate_reject_reason,
            .peak_sample_x = thermal_target_trace.micro_candidate_peak_sx,
            .peak_sample_y = thermal_target_trace.micro_candidate_peak_sy,
            .peak_delta = thermal_target_trace.micro_candidate_peak_delta,
            .peak_score = thermal_target_trace.micro_candidate_peak_score,
            .prominence = thermal_target_trace.micro_candidate_prominence,
            .ring_mean = thermal_target_trace.micro_candidate_ring_mean,
            .ring_hot_fraction = thermal_target_trace.micro_candidate_ring_hot_fraction,
            .hot_count = thermal_target_trace.micro_candidate_hot_count,
            .sample_count = thermal_target_trace.micro_candidate_sample_count,
            .compactness = thermal_target_trace.micro_candidate_compactness,
            .centroid_dx = thermal_target_trace.micro_candidate_centroid_dx,
            .centroid_dy = thermal_target_trace.micro_candidate_centroid_dy,
            .centroid_offset = thermal_target_trace.micro_candidate_centroid_offset,
            .one_sided_support = thermal_target_trace.micro_candidate_one_sided_support,
            .distance_to_debug_target =
                thermal_target_trace.micro_candidate_distance_to_debug_target,
        };
        anomaly_result_publish_thermal_debug_target_micro_candidate(
            result_out,
            &thermal_target_micro);
        anomaly_result_thermal_debug_target_suppressor_publication_t thermal_target_suppressor = {
            .suppressor_sample_x = thermal_target_trace.suppressor_sx,
            .suppressor_sample_y = thermal_target_trace.suppressor_sy,
            .suppressor_delta = thermal_target_trace.suppressor_delta,
            .suppressor_score = thermal_target_trace.suppressor_score,
        };
        anomaly_result_publish_thermal_debug_target_suppressor(
            result_out,
            &thermal_target_suppressor);
        anomaly_result_thermal_debug_target_component_trace_publication_t thermal_target_component = {
            .component_seed_x = thermal_target_trace.component_seed_x,
            .component_seed_y = thermal_target_trace.component_seed_y,
            .component_peak_x = thermal_target_trace.component_peak_x,
            .component_peak_y = thermal_target_trace.component_peak_y,
            .component_area = thermal_target_trace.component_area,
            .component_span = thermal_target_trace.component_span,
            .component_fill = thermal_target_trace.component_fill,
            .component_peak_delta = thermal_target_trace.component_peak_delta,
            .component_mean_delta = thermal_target_trace.component_mean_delta,
            .component_quality = thermal_target_trace.component_quality,
            .component_rejected = thermal_target_trace.component_rejected,
            .rejection_gate = thermal_target_trace.rejection_gate,
        };
        anomaly_result_publish_thermal_debug_target_component_trace(
            result_out,
            &thermal_target_component);
        anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t
                thermal_target_nearby_rejected = {
            .valid = thermal_target_trace.nearby_rejected_component_valid,
            .contains_target = thermal_target_trace.nearby_rejected_component_contains_target,
            .gate = thermal_target_trace.nearby_rejected_component_gate,
            .seed_x = thermal_target_trace.nearby_rejected_component_seed_x,
            .seed_y = thermal_target_trace.nearby_rejected_component_seed_y,
            .peak_x = thermal_target_trace.nearby_rejected_component_peak_x,
            .peak_y = thermal_target_trace.nearby_rejected_component_peak_y,
            .area = thermal_target_trace.nearby_rejected_component_area,
            .span = thermal_target_trace.nearby_rejected_component_span,
            .fill = thermal_target_trace.nearby_rejected_component_fill,
            .peak_delta = thermal_target_trace.nearby_rejected_component_peak_delta,
            .mean_delta = thermal_target_trace.nearby_rejected_component_mean_delta,
            .quality = thermal_target_trace.nearby_rejected_component_quality,
            .distance = thermal_target_trace.nearby_rejected_component_distance,
        };
        anomaly_result_publish_thermal_debug_target_nearby_rejected_component(
            result_out,
            &thermal_target_nearby_rejected);
        anomaly_result_thermal_debug_target_nms_cap_publication_t thermal_target_nms_cap = {
            .dropped_by_cap = thermal_target_trace.dropped_by_cap,
            .dropped_by_nms = thermal_target_trace.dropped_by_nms,
            .replaced_by_nms = thermal_target_trace.replaced_by_nms,
            .nms_conflict_rank = thermal_target_trace.nms_conflict_rank,
            .nms_conflict_sample_x = thermal_target_trace.nms_conflict_sample_x,
            .nms_conflict_sample_y = thermal_target_trace.nms_conflict_sample_y,
            .pre_cap_rank = thermal_target_trace.pre_cap_rank,
            .pre_cap_candidate_count = thermal_target_trace.pre_cap_candidate_count,
            .pre_cap_limit = thermal_target_trace.pre_cap_limit,
            .pre_cap_retention_rank = thermal_target_trace.pre_cap_retention_rank,
            .extracted_rank = thermal_target_trace.extracted_rank,
            .winning_rank = thermal_target_trace.winning_rank,
        };
        anomaly_result_publish_thermal_debug_target_nms_cap(
            result_out,
            &thermal_target_nms_cap);
        anomaly_result_thermal_debug_target_provisional_publication_t
                thermal_target_provisional = {
            .candidate_index = thermal_target_trace.provisional_candidate_index,
            .score_floor = thermal_target_trace.provisional_score_floor,
            .final_score = thermal_target_trace.provisional_final_score,
            .score_eligible = thermal_target_trace.provisional_score_eligible,
            .shape_eligible = thermal_target_trace.provisional_shape_eligible,
            .candidate_rank = thermal_target_trace.provisional_candidate_rank,
            .selected_rank = thermal_target_trace.provisional_selected_rank,
            .selected_score = thermal_target_trace.provisional_selected_score,
            .near_existing_skip = thermal_target_trace.provisional_near_existing_skip,
        };
        anomaly_result_publish_thermal_debug_target_provisional(
            result_out,
            &thermal_target_provisional);
        anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t
                thermal_target_raw_delta_rescue = {
            .raw_delta_rescue_score = thermal_target_trace.raw_delta_rescue_score,
        };
        anomaly_result_publish_thermal_debug_target_raw_delta_rescue(
            result_out,
            &thermal_target_raw_delta_rescue);
        anomaly_result_thermal_debug_target_movement_diagnostics_publication_t
                thermal_target_movement = {
            .residual_px = thermal_target_trace.movement_residual_px,
            .independent_score = thermal_target_trace.movement_independent_score,
            .confidence = thermal_target_trace.movement_confidence,
            .motion_support = thermal_target_trace.movement_motion_support,
            .layer_class = thermal_target_trace.movement_layer_class,
        };
        anomaly_result_publish_thermal_debug_target_movement_diagnostics(
            result_out,
            &thermal_target_movement);
        anomaly_result_thermal_debug_target_local_peak_movement_publication_t
                thermal_target_local_peak_movement = {
            .residual_px = thermal_target_trace.local_peak_movement_residual_px,
            .independent_score =
                thermal_target_trace.local_peak_movement_independent_score,
            .confidence = thermal_target_trace.local_peak_movement_confidence,
            .motion_support = thermal_target_trace.local_peak_movement_motion_support,
            .layer_class = thermal_target_trace.local_peak_movement_layer_class,
        };
        anomaly_result_publish_thermal_debug_target_local_peak_movement(
            result_out,
            &thermal_target_local_peak_movement);
        anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t
                thermal_target_rescue_movement_flags = {
            .raw_delta_rescue_eligible = thermal_target_trace.raw_delta_rescue_eligible,
            .movement_tile_valid = thermal_target_trace.movement_tile_valid,
            .movement_independent = thermal_target_trace.movement_independent,
            .movement_parallax = thermal_target_trace.movement_parallax,
            .would_promote_movement_rescue =
                thermal_target_trace.would_promote_movement_rescue,
            .local_peak_movement_tile_valid =
                thermal_target_trace.local_peak_movement_tile_valid,
            .local_peak_movement_independent =
                thermal_target_trace.local_peak_movement_independent,
            .local_peak_movement_parallax =
                thermal_target_trace.local_peak_movement_parallax,
        };
        anomaly_result_publish_thermal_debug_target_rescue_movement_flags(
            result_out,
            &thermal_target_rescue_movement_flags);
        anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t
                thermal_target_movement_shadow_rescue = {
            .movement_shadow_motion_support =
                thermal_target_trace.movement_shadow_motion_support,
            .movement_shadow_parallax_penalty =
                thermal_target_trace.movement_shadow_parallax_penalty,
            .movement_shadow_thermal_support =
                thermal_target_trace.movement_shadow_thermal_support,
            .movement_shadow_clutter_veto =
                thermal_target_trace.movement_shadow_clutter_veto,
            .movement_rescue_would_publish =
                thermal_target_trace.movement_rescue_would_publish,
            .movement_boost_would_publish =
                thermal_target_trace.movement_boost_would_publish,
            .movement_rescue_reject_reason =
                thermal_target_trace.movement_rescue_reject_reason,
        };
        anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(
            result_out,
            &thermal_target_movement_shadow_rescue);
        anomaly_result_thermal_debug_target_track_match_publication_t
                thermal_target_track_match = {
            .matched_track_index = thermal_target_trace.matched_track_index,
            .matched_track_id = thermal_target_trace.matched_track_id,
            .matched_track_hit_count = thermal_target_trace.matched_track_hit_count,
            .matched_track_miss_count = thermal_target_trace.matched_track_miss_count,
            .matched_track_hold_count = thermal_target_trace.matched_track_hold_count,
            .matched_track_publish_confirmed =
                thermal_target_trace.matched_track_publish_confirmed,
        };
        anomaly_result_publish_thermal_debug_target_track_match(
            result_out,
            &thermal_target_track_match);
        anomaly_result_thermal_debug_target_stage_publication_t thermal_target_stage = {
            .stage = thermal_target_trace.stage,
        };
        anomaly_result_publish_thermal_debug_target_stage(result_out, &thermal_target_stage);
        int thermal_debug_candidate_count = thermal_candidate_count;
        if (thermal_debug_candidate_count > ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES) {
            thermal_debug_candidate_count = ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
        }
        anomaly_result_thermal_debug_candidate_base_publication_t
                thermal_debug_candidate_base[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        for (int i = 0;
             i < thermal_debug_candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
             i++) {
            thermal_debug_candidate_base[i] =
                (anomaly_result_thermal_debug_candidate_base_publication_t) {
                    .pixel_x = thermal_candidates[i].pixel_x,
                    .pixel_y = thermal_candidates[i].pixel_y,
                    .min_x = thermal_candidate_min_x[i],
                    .min_y = thermal_candidate_min_y[i],
                    .max_x = thermal_candidate_max_x[i],
                    .max_y = thermal_candidate_max_y[i],
                    .base_score = thermal_candidate_base_score[i],
                    .final_score = thermal_candidates[i].thermal_score,
                    .temporal_score = thermal_candidate_temporal_score[i],
                    .area = thermal_candidate_area[i],
                    .span = thermal_candidate_span[i],
                    .fill = thermal_candidate_fill[i],
                    .center_share = thermal_candidate_center_share[i],
                    .quality = thermal_candidate_quality_score[i],
                    .isolation_rank = thermal_candidate_isolation_rank[i],
                    .peak_delta = thermal_candidate_peak_delta[i],
                    .mean_delta = thermal_candidate_mean_delta[i],
                    .score_scale = thermal_candidate_score_scale[i],
                    .history_scale = thermal_candidate_history_scale_debug[i],
                    .apparent_size_scale = thermal_candidate_apparent_size_scale[i],
                    .isolation_track_scale = thermal_candidate_isolation_track_scale[i],
                    .context_scale = thermal_candidate_context_scale[i],
                    .parent_scale = thermal_candidate_parent_scale[i],
                    .area_rank = thermal_candidate_area_rank[i],
                    .span_rank = thermal_candidate_span_rank[i],
                    .center_rank = thermal_candidate_center_rank[i],
                    .quality_rank = thermal_candidate_quality_rank[i],
                    .patch_support = thermal_candidate_patch_support[i],
                    .motion_support = thermal_candidate_motion_support[i],
                    .singleton_score_scale = thermal_candidate_singleton_score_scale[i],
                    .retention_rank = thermal_candidate_retention_rank_debug[i],
                };
        }
        anomaly_result_thermal_debug_candidates_base_publication_t
                thermal_debug_candidates_base = {
            .candidates = thermal_debug_candidate_base,
            .candidate_count = thermal_debug_candidate_count,
            .roi_x0 = roi_x0,
            .roi_y0 = roi_y0,
            .sample_step = sample_step,
            .frame_w = fw,
            .frame_h = fh,
        };
        anomaly_result_publish_thermal_debug_candidates_base(
            result_out,
            &thermal_debug_candidates_base);
        anomaly_result_thermal_debug_candidate_movement_publication_t
                thermal_debug_candidate_movement[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        memset(
            thermal_debug_candidate_movement,
            0,
            sizeof(thermal_debug_candidate_movement));
        for (int i = 0; i < thermal_debug_candidate_count; i++) {
            anomaly_debug_thermal_candidate_t dbg;
            if (!anomaly_result_copy_thermal_debug_candidate(result_out, i, &dbg)) {
                continue;
            }
            float cand_x_norm = dbg.x_norm;
            float cand_y_norm = dbg.y_norm;
            anomaly_debug_movement_tile_t cand_tile;
            if (anomaly_motion_estimator_query_snapshot_at_norm(
                    &movement_snapshot,
                    cand_x_norm,
                    cand_y_norm,
                    &cand_tile)) {
                float movement_independent_score =
                    anomaly_motion_estimator_tile_independent_score(&cand_tile);
                thermal_debug_candidate_movement[i] =
                    (anomaly_result_thermal_debug_candidate_movement_publication_t) {
                        .movement_tile_valid = true,
                        .movement_residual_px = cand_tile.residual_px,
                        .movement_independent_score = movement_independent_score,
                        .movement_confidence = cand_tile.confidence,
                        .movement_layer_class = cand_tile.layer_class,
                        .movement_independent =
                            anomaly_motion_estimator_tile_is_independent(
                                    &cand_tile,
                                    movement_independent_score),
                        .movement_parallax =
                            anomaly_motion_estimator_tile_is_parallax_like(&cand_tile),
                    };
            }
        }
        anomaly_result_thermal_debug_candidates_movement_publication_t
                thermal_debug_candidates_movement = {
            .candidates = thermal_debug_candidate_movement,
            .candidate_count = thermal_debug_candidate_count,
        };
        anomaly_result_publish_thermal_debug_candidates_movement(
            result_out,
            &thermal_debug_candidates_movement);
        anomaly_result_thermal_debug_candidate_nearest_track_publication_t
                thermal_debug_candidate_nearest_track[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        memset(
            thermal_debug_candidate_nearest_track,
            0,
            sizeof(thermal_debug_candidate_nearest_track));
        for (int i = 0; i < thermal_debug_candidate_count; i++) {
            anomaly_debug_thermal_candidate_t dbg;
            if (!anomaly_result_copy_thermal_debug_candidate(result_out, i, &dbg)) {
                continue;
            }
            float cand_x_norm = dbg.x_norm;
            float cand_y_norm = dbg.y_norm;
            float nearest_track_distance = 1.0e9f;
            int nearest_track_index = -1;
            int nearest_track_id = -1;
            int nearest_track_hit_count = -1;
            for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
                const anomaly_target_track_t *track = &state->target_tracks[ti];
                if (!track->active) continue;
                float dx = track->center_x_norm - cand_x_norm;
                float dy = track->center_y_norm - cand_y_norm;
                float dist = sqrtf(dx * dx + dy * dy);
                if (dist < nearest_track_distance) {
                    nearest_track_distance = dist;
                    nearest_track_index = ti;
                    nearest_track_id = track->id;
                    nearest_track_hit_count = track->hit_count;
                }
            }
            if (nearest_track_index >= 0) {
                thermal_debug_candidate_nearest_track[i] =
                    (anomaly_result_thermal_debug_candidate_nearest_track_publication_t) {
                        .nearest_track_valid = true,
                        .nearest_track_distance = nearest_track_distance,
                        .nearest_track_index = nearest_track_index,
                        .nearest_track_id = nearest_track_id,
                        .nearest_track_hit_count = nearest_track_hit_count,
                        .near_tracked_target = nearest_track_distance <=
                            fmaxf(ANOMALY_TARGET_MATCH_GATE, 0.018f),
                    };
            }
        }
        anomaly_result_thermal_debug_candidates_nearest_track_publication_t
                thermal_debug_candidates_nearest_track = {
            .candidates = thermal_debug_candidate_nearest_track,
            .candidate_count = thermal_debug_candidate_count,
        };
        anomaly_result_publish_thermal_debug_candidates_nearest_track(
            result_out,
            &thermal_debug_candidates_nearest_track);
        anomaly_result_thermal_debug_candidate_near_debug_publication_t
                thermal_debug_candidate_near_debug[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        memset(
            thermal_debug_candidate_near_debug,
            0,
            sizeof(thermal_debug_candidate_near_debug));
        for (int i = 0; i < thermal_debug_candidate_count; i++) {
            anomaly_debug_thermal_candidate_t dbg;
            if (!anomaly_result_copy_thermal_debug_candidate(result_out, i, &dbg)) {
                continue;
            }
            float cand_x_norm = dbg.x_norm;
            float cand_y_norm = dbg.y_norm;
            if (thermal_target_trace.enabled && thermal_target_trace.valid) {
                bool contains_debug_target =
                    thermal_target_trace.target_x_norm >= dbg.bbox_left_norm &&
                    thermal_target_trace.target_x_norm <= dbg.bbox_right_norm &&
                    thermal_target_trace.target_y_norm >= dbg.bbox_top_norm &&
                    thermal_target_trace.target_y_norm <= dbg.bbox_bottom_norm;
                float dx = thermal_target_trace.target_x_norm - cand_x_norm;
                float dy = thermal_target_trace.target_y_norm - cand_y_norm;
                thermal_debug_candidate_near_debug[i] =
                    (anomaly_result_thermal_debug_candidate_near_debug_publication_t) {
                        .near_debug_valid = true,
                        .near_debug_target =
                            contains_debug_target ||
                            sqrtf(dx * dx + dy * dy) <=
                                fmaxf(ANOMALY_TARGET_MATCH_GATE, 0.018f),
                    };
            }
        }
        anomaly_result_thermal_debug_candidates_near_debug_publication_t
                thermal_debug_candidates_near_debug = {
            .candidates = thermal_debug_candidate_near_debug,
            .candidate_count = thermal_debug_candidate_count,
        };
        anomaly_result_publish_thermal_debug_candidates_near_debug(
            result_out,
            &thermal_debug_candidates_near_debug);
        anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t
                thermal_debug_candidate_raw_delta_rescue[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        memset(
            thermal_debug_candidate_raw_delta_rescue,
            0,
            sizeof(thermal_debug_candidate_raw_delta_rescue));
        for (int i = 0;
             i < thermal_debug_candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
             i++) {
            anomaly_debug_thermal_candidate_t dbg;
            if (!anomaly_result_copy_thermal_debug_candidate(result_out, i, &dbg)) {
                continue;
            }
            float raw_delta_rescue_score =
                anomaly_thermal_shadow_raw_delta_rescue_score(
                        dbg.peak_delta,
                        dbg.mean_delta,
                        dbg.area,
                        dbg.span,
                        dbg.fill,
                        dbg.quality,
                        dbg.final_score,
                        cfg->score_threshold,
                        thermal_min_delta,
                        delta_norm,
                        dbg.movement_independent_score,
                        dbg.movement_independent,
                        dbg.near_tracked_target);
            bool raw_delta_rescue_eligible =
                anomaly_thermal_shadow_raw_delta_rescue_eligible(
                        dbg.peak_delta,
                        dbg.area,
                        dbg.span,
                        dbg.quality,
                        dbg.final_score,
                        cfg->score_threshold,
                        thermal_min_delta,
                        dbg.movement_independent);
            thermal_debug_candidate_raw_delta_rescue[i] =
                (anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t) {
                    .raw_delta_rescue_score = raw_delta_rescue_score,
                    .raw_delta_rescue_eligible = raw_delta_rescue_eligible,
                    .would_promote_movement_rescue =
                        raw_delta_rescue_eligible && raw_delta_rescue_score >= 0.62f,
                };
        }
        anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t
                thermal_debug_candidates_raw_delta_rescue = {
            .candidates = thermal_debug_candidate_raw_delta_rescue,
            .candidate_count = thermal_debug_candidate_count,
        };
        anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(
            result_out,
            &thermal_debug_candidates_raw_delta_rescue);
        anomaly_result_thermal_debug_candidate_final_flags_publication_t
                thermal_debug_candidate_final_flags[ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES];
        memset(
            thermal_debug_candidate_final_flags,
            0,
            sizeof(thermal_debug_candidate_final_flags));
        for (int i = 0;
             i < thermal_debug_candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
             i++) {
            thermal_debug_candidate_final_flags[i] =
                (anomaly_result_thermal_debug_candidate_final_flags_publication_t) {
                    .singleton_blob = thermal_candidate_singleton_blob_debug[i],
                    .above_threshold = thermal_candidate_above_threshold[i],
                };
        }
        anomaly_result_thermal_debug_candidates_final_flags_publication_t
                thermal_debug_candidates_final_flags = {
            .candidates = thermal_debug_candidate_final_flags,
            .candidate_count = thermal_debug_candidate_count,
        };
        anomaly_result_publish_thermal_debug_candidates_final_flags(
            result_out,
            &thermal_debug_candidates_final_flags);
        anomaly_result_color_debug_summary_publication_t color_debug_summary = {
            .raw_candidate_index = raw_best_color_candidate_idx,
            .raw_best_score = raw_best_color,
            .raw_best_x = raw_best_color_x,
            .raw_best_y = raw_best_color_y,
            .best_score = best_color,
            .best_x = best_color_x,
            .best_y = best_color_y,
            .frame_w = fw,
            .frame_h = fh,
            .target_span_px = color_blob_target_span_px,
            .target_span_cells = color_blob_target_span_cells,
            .max_blob_area_budget = color_blob_max_area_budget,
            .active_phase_index = color_phase_index,
            .active_phase_x = color_phase_x,
            .active_phase_y = color_phase_y,
            .selective_refresh_active = selective_refresh_active,
            .forced_full_refresh = color_forced_full_refresh,
            .fallback_reason_flags = color_fallback_reason_flags,
            .fresh_sample_count = color_fresh_sample_count,
            .carried_sample_count = color_carried_sample_count,
            .unsampled_new_exposed_count = color_unsampled_new_count,
            .sample_grid_count = sg_count,
            .histogram_valid_sample_count = color_hist_valid_samples,
            .history_reset_applied = color_history_reset_applied,
            .history_recovery_frames_remaining = color_history_recovery_frames_remaining,
            .history_recent_scale = color_history_recent_scale,
            .nonzero_histogram_bins = color_hist_nonzero_bins,
            .max_histogram_current_count = color_hist_max_current_count,
            .max_histogram_recent_count = color_hist_max_recent_count,
            .rarity_seed_count = color_rarity_seed_count,
            .support_seed_count = color_support_seed_count,
            .support_peak_score = color_support_peak,
            .coarse_component_count = color_coarse_component_count,
            .coarse_oversized_count = color_coarse_oversized_count,
            .dense_verify_component_count = color_dense_verify_component_count,
            .adaptive_source_coarse_count = color_adaptive_source_coarse_count,
            .fresh_distinctness_ratio = color_fresh_distinctness_ratio,
            .blob_reject_area_count = color_blob_reject_area_count,
            .blob_reject_ring_count = color_blob_reject_ring_count,
            .blob_reject_support_mass_count = color_blob_reject_support_mass_count,
            .blob_reject_quality_count = color_blob_reject_quality_count,
            .blob_examined_count = color_blob_examined_count,
            .strongest_reject_reason = color_blob_strongest_reject_reason,
            .strongest_reject_peak_support = color_blob_strongest_reject_peak_support,
            .strongest_reject_area = color_blob_strongest_reject_area,
            .strongest_reject_span = color_blob_strongest_reject_span,
            .strongest_reject_ring_fraction = color_blob_strongest_reject_ring_fraction,
            .strongest_reject_support_mass = color_blob_strongest_reject_support_mass,
            .strongest_reject_quality = color_blob_strongest_reject_quality,
            .strongest_seed_sample_x = color_strongest_seed_sx,
            .strongest_seed_sample_y = color_strongest_seed_sy,
            .strongest_seed_score = color_strongest_seed_score,
            .strongest_seed_hist_key = color_strongest_seed_hist_key,
            .strongest_seed_hist_current_count = color_strongest_seed_hist_current_count,
            .strongest_seed_hist_recent_count = color_strongest_seed_hist_recent_count,
            .strongest_seed_hist_rarity_score = color_strongest_seed_hist_rarity,
            .strongest_seed_local_support_count = color_strongest_seed_local_support,
            .winner_gate_active = color_winner_gate_active,
            .winner_gate_reject_reason = color_winner_gate_reject_reason,
            .winner_gate_max_span = color_winner_gate_max_span,
            .winner_gate_max_area = color_winner_gate_max_area,
            .winner_gate_min_rarity = color_winner_gate_min_rarity,
            .winner_gate_max_commonness = color_winner_gate_max_commonness,
            .winning_candidate_index = best_color_candidate_idx,
            .candidate_count = color_candidate_count,
        };
        anomaly_result_publish_color_debug_summary(result_out, &color_debug_summary);
        anomaly_result_color_debug_target_base_publication_t color_target_base = {
            .enabled = color_target_enabled,
            .valid = color_target_valid,
            .inside_scan_zone = color_target_inside_scan_zone,
            .refresh_skipped = color_target_refresh_skipped,
            .sampled_this_frame = color_target_sampled_this_frame,
            .carried_from_history = color_target_carried_from_history,
            .pixel_x = color_target_px,
            .pixel_y = color_target_py,
            .sample_x = color_target_sx,
            .sample_y = color_target_sy,
            .configured_x_norm = cfg->color_debug_target_x_norm,
            .configured_y_norm = cfg->color_debug_target_y_norm,
            .hist_key = color_target_hist_key,
            .hist_current_count = color_target_hist_current_count,
            .hist_recent_count = color_target_hist_recent_count,
            .hist_rarity_score = color_target_hist_rarity,
            .local_support_count = color_target_local_support,
            .patch_valid_count = color_target_telemetry.patch_valid_count,
            .coherent_patch_cell_count = color_target_telemetry.coherent_patch_cell_count,
            .coherent_patch_fresh_cell_count =
                color_target_telemetry.coherent_patch_fresh_cell_count,
            .coherent_patch_multicell = color_target_telemetry.coherent_patch_multicell,
            .patch_mean_u = color_target_telemetry.patch_mean_u,
            .patch_mean_v = color_target_telemetry.patch_mean_v,
            .patch_mean_luma = color_target_telemetry.patch_mean_luma,
            .ring_mean_u = color_target_telemetry.ring_mean_u,
            .ring_mean_v = color_target_telemetry.ring_mean_v,
            .ring_mean_luma = color_target_telemetry.ring_mean_luma,
            .ring_chroma_contrast = color_target_telemetry.ring_chroma_contrast,
            .ring_luma_contrast = color_target_telemetry.ring_luma_contrast,
            .ring_neighbor_count = color_target_telemetry.ring_neighbor_count,
            .pre_support_score = color_target_pre_support_score,
            .support_score = color_target_support_score,
            .support_map_local_peak = color_target_support_map_local_peak,
            .support_map_ring_mean = color_target_support_map_ring_mean,
            .support_map_density = color_target_support_map_density,
            .support_map_distinctness_ratio = color_target_support_map_distinctness_ratio,
            .support_map_compact_prominence = color_target_support_map_compact_prominence,
            .support_map_core_share = color_target_support_map_core_share,
            .support_map_seed_floor = color_target_support_map_seed_floor,
            .support_seed_eligible = color_target_support_seed_eligible,
        };
        anomaly_result_publish_color_debug_target_base(result_out, &color_target_base);
        anomaly_result_color_debug_target_component_trace_publication_t color_target_component = {
            .component_seed_x = color_blob_target_trace.component_seed_x,
            .component_seed_y = color_blob_target_trace.component_seed_y,
            .component_peak_x = color_blob_target_trace.component_peak_x,
            .component_peak_y = color_blob_target_trace.component_peak_y,
            .component_area = color_blob_target_trace.component_area,
            .component_span = color_blob_target_trace.component_span,
            .component_fill = color_blob_target_trace.component_fill,
            .component_peak_support = color_blob_target_trace.component_peak_support,
            .component_mean_support = color_blob_target_trace.component_mean_support,
            .component_quality = color_blob_target_trace.component_quality,
            .component_ring_fraction = color_blob_target_trace.component_ring_fraction,
            .component_support_mass = color_blob_target_trace.component_support_mass,
            .component_rejected = color_blob_target_trace.component_rejected,
            .component_rejection_reason = color_blob_target_trace.component_rejection_reason,
            .dropped_by_cap = color_blob_target_trace.dropped_by_cap,
            .dropped_by_nms = color_blob_target_trace.dropped_by_nms,
            .replaced_by_nms = color_blob_target_trace.replaced_by_nms,
            .nms_conflict_rank = color_blob_target_trace.nms_conflict_rank,
            .nms_conflict_sample_x = color_blob_target_trace.nms_conflict_sample_x,
            .nms_conflict_sample_y = color_blob_target_trace.nms_conflict_sample_y,
            .pre_cap_rank = color_blob_target_trace.pre_cap_rank,
            .pre_cap_candidate_count = color_blob_target_trace.pre_cap_candidate_count,
            .pre_cap_limit = color_blob_target_trace.pre_cap_limit,
            .pre_cap_retention_rank = color_blob_target_trace.pre_cap_retention_rank,
        };
        anomaly_result_publish_color_debug_target_component_trace(
            result_out,
            &color_target_component);
        anomaly_result_color_debug_target_component_bbox_publication_t color_target_bbox = {
            .roi_x0 = roi_x0,
            .roi_y0 = roi_y0,
            .sample_step = sample_step,
            .min_x = color_blob_target_trace.min_x,
            .min_y = color_blob_target_trace.min_y,
            .max_x = color_blob_target_trace.max_x,
            .max_y = color_blob_target_trace.max_y,
            .frame_w = fw,
            .frame_h = fh,
        };
        anomaly_result_publish_color_debug_target_component_bbox(result_out, &color_target_bbox);
        anomaly_result_candidate_sample_t color_candidate_samples[ANOMALY_MAX_COLOR_CANDIDATES];
        for (int ci = 0; ci < color_candidate_count; ci++) {
            color_candidate_samples[ci].sample_x = color_candidates[ci].sg_x;
            color_candidate_samples[ci].sample_y = color_candidates[ci].sg_y;
        }
        anomaly_result_color_debug_target_candidate_indices_publication_t color_target_indices = {
            .component_peak_x = color_blob_target_trace.component_peak_x,
            .component_peak_y = color_blob_target_trace.component_peak_y,
            .candidates = color_candidate_samples,
            .candidate_count = color_candidate_count,
            .matched_candidate_index = color_target_matched_candidate_idx,
            .nearest_candidate_index = color_target_nearest_candidate_idx,
            .nearest_candidate_distance = color_target_nearest_candidate_distance,
            .winning_candidate_index = best_color_candidate_idx,
        };
        anomaly_result_publish_color_debug_target_candidate_indices(result_out, &color_target_indices);
        anomaly_result_color_debug_target_gate_stage_publication_t color_target_gate_stage = {
            .winner_gate_reject_reason = color_winner_gate_reject_reason,
            .matched_candidate_index = color_target_matched_candidate_idx,
            .raw_best_color_candidate_index = raw_best_color_candidate_idx,
            .stage = color_target_stage,
        };
        anomaly_result_publish_color_debug_target_gate_stage(result_out, &color_target_gate_stage);
        if (color_target_matched_candidate_idx >= 0 &&
            color_target_matched_candidate_idx < color_candidate_count) {
            int ci = color_target_matched_candidate_idx;
            anomaly_result_color_debug_target_matched_candidate_publication_t color_target_matched = {
                .valid = true,
                .score = color_candidate_final_score[ci],
                .pixel_x = color_candidates[ci].pixel_x,
                .pixel_y = color_candidates[ci].pixel_y,
                .roi_x0 = roi_x0,
                .roi_y0 = roi_y0,
                .sample_step = sample_step,
                .min_x = color_candidate_min_x[ci],
                .min_y = color_candidate_min_y[ci],
                .max_x = color_candidate_max_x[ci],
                .max_y = color_candidate_max_y[ci],
                .frame_w = fw,
                .frame_h = fh,
            };
            anomaly_result_publish_color_debug_target_matched_candidate(
                result_out,
                &color_target_matched);
        }
        anomaly_result_color_debug_candidate_publication_t color_debug_candidates[
            ANOMALY_DEBUG_TOP_COLOR_CANDIDATES];
        int color_debug_candidate_count = color_candidate_count;
        if (color_debug_candidate_count > ANOMALY_DEBUG_TOP_COLOR_CANDIDATES) {
            color_debug_candidate_count = ANOMALY_DEBUG_TOP_COLOR_CANDIDATES;
        }
        for (int i = 0; i < color_debug_candidate_count; i++) {
            color_debug_candidates[i] =
                (anomaly_result_color_debug_candidate_publication_t) {
                    .pixel_x = color_candidates[i].pixel_x,
                    .pixel_y = color_candidates[i].pixel_y,
                    .min_x = color_candidate_min_x[i],
                    .min_y = color_candidate_min_y[i],
                    .max_x = color_candidate_max_x[i],
                    .max_y = color_candidate_max_y[i],
                    .base_score = color_candidate_base_score[i],
                    .final_score = color_candidate_final_score[i],
                    .area = color_candidate_area[i],
                    .span = color_candidate_span[i],
                    .fill = color_candidate_fill[i],
                    .center_share = color_candidate_center_share[i],
                    .quality = color_candidate_quality[i],
                    .isolation_score = color_candidate_isolation[i],
                    .ring_fraction = color_candidate_ring_fraction[i],
                    .support_mass = color_candidate_support_mass[i],
                    .contrast_weight = color_candidate_contrast_weight[i],
                    .hist_key = color_candidate_hist_key[i],
                    .hist_current_count = color_candidate_hist_current_count[i],
                    .hist_recent_count = color_candidate_hist_recent_count[i],
                    .hist_rarity_score = color_candidate_hist_rarity[i],
                    .center_u = color_candidate_center_u[i],
                    .center_v = color_candidate_center_v[i],
                    .center_luma = color_candidate_center_luma[i],
                    .local_ring_chroma_contrast = color_candidate_local_ring_chroma_contrast[i],
                    .local_ring_luma_contrast = color_candidate_local_ring_luma_contrast[i],
                    .local_ring_neighbor_count = color_candidate_local_ring_neighbor_count[i],
                    .current_nearest_hist_distance = color_candidate_current_nearest_hist_distance[i],
                    .recent_nearest_hist_distance = color_candidate_recent_nearest_hist_distance[i],
                    .small_target_span_ratio = color_candidate_small_target_span_ratio[i],
                    .small_target_area_ratio = color_candidate_small_target_area_ratio[i],
                    .scene_commonness = color_candidate_scene_commonness_score[i],
                    .retention_rank = color_candidate_retention_rank[i],
                    .above_threshold = color_candidate_above_threshold[i],
                };
        }
        anomaly_result_color_debug_candidates_publication_t color_debug_candidate_publication = {
            .candidates = color_debug_candidates,
            .candidate_count = color_debug_candidate_count,
            .roi_x0 = roi_x0,
            .roi_y0 = roi_y0,
            .sample_step = sample_step,
            .frame_w = fw,
            .frame_h = fh,
        };
        anomaly_result_publish_color_debug_candidates(result_out, &color_debug_candidate_publication);
        anomaly_result_saliency_debug_publication_t saliency_debug = {
            .bg_ready = bg_valid,
            .raw_score = best_persist,
            .raw_x = best_persist_x,
            .raw_y = best_persist_y,
            .frame_w = fw,
            .frame_h = fh,
            .tracked_score_pre = saliency_tracked_score_pre,
            .acc_pre_active = saliency_acc_pre_active,
            .acc_pre_hits = saliency_acc_pre_hits,
            .acc_pre_x_norm = saliency_acc_pre_x,
            .acc_pre_y_norm = saliency_acc_pre_y,
            .acc_post_active = state->acc_active[3],
            .acc_post_hits = state->acc_hits[3],
            .acc_post_x_norm = state->acc_cx[3],
            .acc_post_y_norm = state->acc_cy[3],
            .switch_suppressed = saliency_switch_suppressed,
            .top_candidates = saliency_top,
            .top_candidate_count = saliency_top_count,
        };
        anomaly_result_publish_saliency_debug(result_out, &saliency_debug);
    }

    stage_started_us = anomaly_timing_now_us();
    if (rgba != NULL) {
        if (show_hot_overlay) {
            anomaly_debug_draw_hot_overlay_rgba(rgba, rgba_stride, width, height, cfg->thermal_polarity);
        }
        if (overlay_box_count > 0) {
            anomaly_debug_draw_boxes_rgba(rgba, rgba_stride, width, height, overlay_boxes, overlay_box_count);
        }
    }
    anomaly_timing_add_elapsed(&timing, ANOMALY_TIMING_STAGE_OVERLAY_DRAW, stage_started_us);
    anomaly_color_advance_sampling_phase(state, rescan_mode == ANOMALY_RESCAN_MODE_FULL, sample_step);
    anomaly_result_finalize_timing(result_out, &timing, frame_started_us);

    return box_count;
}

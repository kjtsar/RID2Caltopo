#include "anomaly_result_builder.h"

#include "anomaly_color_detector.h"
#include "anomaly_debug_helpers.h"
#include "anomaly_motion_estimator.h"

#include <math.h>
#include <stdint.h>
#include <string.h>

static float anomaly_result_clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

#define ANOMALY_RESULT_MAX_STALE_COLOR_MISSES 3
#define ANOMALY_RESULT_MAX_CARRIED_THERMAL_MISSES 2
#define ANOMALY_RESULT_MIN_CARRIED_THERMAL_RESIDUAL_PX 8.0f

static bool anomaly_result_is_stale_color_target_track(const anomaly_target_track_t *track) {
    return track != NULL &&
           track->active &&
           track->publish_confirmed &&
           track->algorithm == ANOMALY_ALGO_COLOR &&
           !track->fresh_observation &&
           track->miss_count > ANOMALY_RESULT_MAX_STALE_COLOR_MISSES;
}

static bool anomaly_result_allows_carried_thermal_targets(const anomaly_config_t *cfg) {
    return anomaly_motion_estimator_normalize_movement_mode(cfg) ==
           ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
}

static bool anomaly_result_has_carried_thermal_motion_support(
        const anomaly_target_track_t *track,
        const anomaly_config_t       *cfg) {
    if (!anomaly_result_allows_carried_thermal_targets(cfg) || track == NULL) return false;
    if (track->movement_valid_frames < 3 || track->movement_window_frames < 3) return false;
    if (track->movement_parallax_frames * 2 < track->movement_valid_frames) return false;
    if (!isfinite(track->movement_confidence_sum) || track->movement_valid_frames <= 0) return false;
    float mean_confidence =
        track->movement_confidence_sum / (float)track->movement_valid_frames;
    if (mean_confidence < 0.55f) return false;
    if (!isfinite(track->last_movement_residual_px)) return false;
    return track->last_movement_residual_px >= ANOMALY_RESULT_MIN_CARRIED_THERMAL_RESIDUAL_PX;
}

static bool anomaly_result_is_stale_thermal_target_track(
        const anomaly_target_track_t *track,
        const anomaly_config_t       *cfg) {
    int max_carried_misses =
        anomaly_result_has_carried_thermal_motion_support(track, cfg)
            ? ANOMALY_RESULT_MAX_CARRIED_THERMAL_MISSES
            : 0;
    return track != NULL &&
           track->active &&
           track->publish_confirmed &&
           track->algorithm == ANOMALY_ALGO_THERMAL &&
           !track->fresh_observation &&
           track->miss_count > max_carried_misses;
}

int anomaly_result_build_boxes(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     motion_box_algorithm,
        anomaly_box_t          *boxes,
        int                     max_boxes) {
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
    bool color_target_track_published = false;
    bool stale_color_lock_pending = false;
    bool stale_thermal_lock_pending = false;
    if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0 &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) != 0) {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            const anomaly_target_track_t *track = &state->target_tracks[ti];
            if (track->active &&
                track->publish_confirmed &&
                track->hit_count >= min_hits &&
                track->algorithm == ANOMALY_ALGO_COLOR) {
                if (anomaly_result_is_stale_color_target_track(track)) {
                    stale_color_lock_pending = true;
                    continue;
                }
                color_target_track_published = true;
            }
        }
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) != 0) {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            const anomaly_target_track_t *track = &state->target_tracks[ti];
            if (track->active &&
                track->publish_confirmed &&
                track->hit_count >= min_hits &&
                anomaly_result_is_stale_thermal_target_track(track, cfg)) {
                stale_thermal_lock_pending = true;
            }
        }
    }
    int box_count = 0;
    bool emitted_color_target_track = false;
    bool emitted_carried_thermal_target_track = false;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS && box_count < max_boxes; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->publish_confirmed || track->hit_count < min_hits) continue;
        if (anomaly_result_is_stale_color_target_track(track) ||
            anomaly_result_is_stale_thermal_target_track(track, cfg) ||
            (stale_color_lock_pending && track->algorithm == ANOMALY_ALGO_COLOR)) {
            continue;
        }
        if (color_target_track_published &&
            track->algorithm == ANOMALY_ALGO_COLOR &&
            emitted_color_target_track) {
            continue;
        }
        if (color_target_track_published &&
            (track->algorithm == ANOMALY_ALGO_MOTION ||
             track->algorithm == ANOMALY_ALGO_MOTION_TOLERANCE ||
             track->algorithm == motion_box_algorithm)) {
            continue;
        }
        if (emitted_carried_thermal_target_track &&
            track->algorithm == ANOMALY_ALGO_THERMAL) {
            continue;
        }
        int rgb_idx = 3;
        if (track->algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
        else if (track->algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
        else if (track->algorithm == motion_box_algorithm || track->algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
        float weight = anomaly_result_clampf(0.25f + track->confidence + 0.05f * (float)track->hit_count, 0.25f, 1.0f);
        anomaly_debug_append_center_box(
                boxes,
                &box_count,
                max_boxes,
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
            if (track->algorithm == ANOMALY_ALGO_COLOR) {
                emitted_color_target_track = true;
            }
            if (anomaly_result_has_carried_thermal_motion_support(track, cfg) &&
                track->algorithm == ANOMALY_ALGO_THERMAL &&
                !track->fresh_observation) {
                emitted_carried_thermal_target_track = true;
            }
        }
    }
    if (box_count > 0) return box_count;
    if (stale_color_lock_pending || stale_thermal_lock_pending) return 0;
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
        anomaly_debug_append_center_box(
                boxes,
                &box_count,
                max_boxes,
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
            anomaly_debug_append_center_box(
                    boxes,
                    &box_count,
                    max_boxes,
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

void anomaly_result_publish_scan_plan(
        anomaly_result_t          *result_out,
        const anomaly_scan_plan_t *scan_plan) {
    if (result_out == NULL || scan_plan == NULL) return;
    result_out->scan_plan = *scan_plan;
}

void anomaly_result_publish_rescan_mode(
        anomaly_result_t      *result_out,
        anomaly_rescan_mode_t rescan_mode) {
    if (result_out == NULL) return;
    result_out->rescan_mode = rescan_mode;
}

void anomaly_result_publish_frame_metadata(
        anomaly_result_t                      *result_out,
        const anomaly_result_frame_metadata_t *metadata) {
    if (result_out == NULL || metadata == NULL) return;
    result_out->had_discontinuity = metadata->had_discontinuity;
    result_out->registration_ran_this_frame = metadata->registration_ran_this_frame;
    result_out->appearance_refresh_ran_this_frame = metadata->appearance_refresh_ran_this_frame;
    result_out->registration_health = metadata->registration_health;
    anomaly_result_publish_rescan_mode(result_out, metadata->rescan_mode);
    anomaly_result_publish_scan_plan(result_out, &metadata->scan_plan);
    result_out->adaptive_effective_stride = metadata->adaptive_effective_stride;
    result_out->adaptive_stable_frames = metadata->adaptive_stable_frames;
    result_out->adaptive_drop_hold_frames = metadata->adaptive_drop_hold_frames;
    result_out->adaptive_motion_load = metadata->adaptive_motion_load;
    result_out->adaptive_reason_flags = metadata->adaptive_reason_flags;
    anomaly_debug_populate_registration_model(metadata->registration, result_out);
    if (metadata->movement_debug != NULL) {
        result_out->movement_debug = *metadata->movement_debug;
    }
}

void anomaly_result_publish_movement_debug(
        anomaly_result_t              *result_out,
        const anomaly_debug_movement_t *movement_debug) {
    if (result_out == NULL || movement_debug == NULL) return;
    result_out->movement_debug = *movement_debug;
}

void anomaly_result_publish_motion_appearance_debug_summary(
        anomaly_result_t                                                   *result_out,
        const anomaly_result_motion_appearance_debug_summary_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_motion_estimator_populate_appearance_debug_summary(
            &result_out->motion_debug,
            debug->scene_discontinuity,
            debug->sample_step,
            debug->motion_step,
            debug->global_count,
            debug->motion_candidate_count,
            debug->global_motion_mean,
            debug->global_motion_std,
            debug->zoom_motion_scale,
            debug->broad_motion_scale,
            debug->global_motion_load);
}

void anomaly_result_publish_motion_appearance_debug_result(
        anomaly_result_t                                                  *result_out,
        const anomaly_result_motion_appearance_debug_result_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_motion_estimator_populate_appearance_debug_result(
            &result_out->motion_debug,
            debug->raw_score,
            debug->raw_x,
            debug->raw_y,
            debug->frame_w,
            debug->frame_h,
            debug->winner_component_area_frac,
            debug->winner_component_span_frac,
            debug->winner_component_fill_ratio,
            debug->zoom_motion_scale,
            debug->broad_motion_scale,
            debug->global_motion_load,
            debug->winner_texture_scale,
            debug->winner_structure_scale,
            debug->winner_support_scale,
            debug->winner_persistence_scale,
            debug->top_candidates,
            debug->top_candidate_count);
}

void anomaly_result_publish_saliency_debug(
        anomaly_result_t                                  *result_out,
        const anomaly_result_saliency_debug_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_debug_saliency_t *saliency = &result_out->saliency_debug;
    saliency->bg_ready = debug->bg_ready;
    saliency->raw_candidate_valid = debug->raw_score >= 0.0f;
    saliency->raw_score = debug->raw_score;
    saliency->raw_x_norm = (debug->raw_x > 0 || debug->raw_y > 0) && debug->frame_w != 0.0f
        ? ((float)debug->raw_x / debug->frame_w)
        : 0.0f;
    saliency->raw_y_norm = (debug->raw_x > 0 || debug->raw_y > 0) && debug->frame_h != 0.0f
        ? ((float)debug->raw_y / debug->frame_h)
        : 0.0f;
    saliency->tracked_score_pre = debug->tracked_score_pre;
    saliency->acc_pre_active = debug->acc_pre_active;
    saliency->acc_pre_hits = debug->acc_pre_hits;
    saliency->acc_pre_x_norm = debug->acc_pre_x_norm;
    saliency->acc_pre_y_norm = debug->acc_pre_y_norm;
    saliency->acc_post_active = debug->acc_post_active;
    saliency->acc_post_hits = debug->acc_post_hits;
    saliency->acc_post_x_norm = debug->acc_post_x_norm;
    saliency->acc_post_y_norm = debug->acc_post_y_norm;
    saliency->switch_suppressed = debug->switch_suppressed;
    saliency->top_candidate_count = debug->top_candidate_count;
    if (debug->top_candidates == NULL) return;
    for (int i = 0; i < debug->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        saliency->top_candidates[i] = debug->top_candidates[i];
    }
}

void anomaly_result_publish_thermal_debug_summary(
        anomaly_result_t                                         *result_out,
        const anomaly_result_thermal_debug_summary_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    thermal->bg_ready = debug->bg_ready;
    thermal->raw_candidate_valid = debug->raw_score >= 0.0f;
    thermal->raw_score = debug->raw_score;
    thermal->raw_x_norm = (debug->raw_x > 0 || debug->raw_y > 0) && debug->frame_w != 0.0f
        ? ((float)debug->raw_x / debug->frame_w)
        : 0.0f;
    thermal->raw_y_norm = (debug->raw_x > 0 || debug->raw_y > 0) && debug->frame_h != 0.0f
        ? ((float)debug->raw_y / debug->frame_h)
        : 0.0f;
    thermal->frame_delta_mean = debug->frame_delta_mean;
    thermal->frame_delta_norm = debug->frame_delta_norm;
    thermal->frame_blob_contrast_mean = debug->frame_blob_contrast_mean;
    thermal->frame_blob_contrast_std = debug->frame_blob_contrast_std;
    thermal->winning_candidate_index = debug->winning_candidate_index;
    thermal->candidate_count = debug->candidate_count;
}

void anomaly_result_publish_thermal_debug_target_base(
        anomaly_result_t                                             *result_out,
        const anomaly_result_thermal_debug_target_base_publication_t *target) {
    if (result_out == NULL || target == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    memset(out, 0, sizeof(*out));
    out->enabled = target->enabled;
    out->valid = target->valid;
    out->inside_scan_zone = target->inside_scan_zone;
    out->pixel_x = target->pixel_x;
    out->pixel_y = target->pixel_y;
    out->sample_x = target->sample_x;
    out->sample_y = target->sample_y;
    out->x_norm = target->x_norm;
    out->y_norm = target->y_norm;
    out->target_delta = target->target_delta;
    out->target_score = target->target_score;
    out->target_raw_delta = target->target_raw_delta;
    out->target_raw_score = target->target_raw_score;
    out->target_temporal_margin = target->target_temporal_margin;
    out->target_spatial_abs_delta = target->target_spatial_abs_delta;
    out->target_spatial_std = target->target_spatial_std;
    out->target_spatial_score = target->target_spatial_score;
    out->hot_eligible = target->hot_eligible;
    out->started_component = target->started_component;
    out->local_max = target->local_max;
    out->local_peak_radius = target->local_peak_radius;
    out->local_peak_sample_x = target->local_peak_sample_x;
    out->local_peak_sample_y = target->local_peak_sample_y;
    out->local_peak_delta = target->local_peak_delta;
    out->local_peak_score = target->local_peak_score;
    out->local_peak_distance = target->local_peak_distance;
    out->local_peak_raw_sample_x = target->local_peak_raw_sample_x;
    out->local_peak_raw_sample_y = target->local_peak_raw_sample_y;
    out->local_peak_raw_delta = target->local_peak_raw_delta;
    out->local_peak_raw_score = target->local_peak_raw_score;
    out->local_peak_raw_distance = target->local_peak_raw_distance;
    out->local_peak_raw_temporal_margin = target->local_peak_raw_temporal_margin;
    out->local_peak_raw_spatial_abs_delta = target->local_peak_raw_spatial_abs_delta;
    out->local_peak_raw_spatial_std = target->local_peak_raw_spatial_std;
    out->local_peak_raw_spatial_score = target->local_peak_raw_spatial_score;
    out->local_peak_is_component_seed = target->local_peak_is_component_seed;
    out->local_window_sample_count = target->local_window_sample_count;
    out->local_window_hot_count = target->local_window_hot_count;
    out->local_window_raw_delta_sum = target->local_window_raw_delta_sum;
    out->local_window_raw_delta_mean = target->local_window_raw_delta_mean;
    out->local_window_weighted_centroid_dx = target->local_window_weighted_centroid_dx;
    out->local_window_weighted_centroid_dy = target->local_window_weighted_centroid_dy;
}

void anomaly_result_publish_thermal_debug_target_micro_candidate(
        anomaly_result_t                                                        *result_out,
        const anomaly_result_thermal_debug_target_micro_candidate_publication_t *micro) {
    if (result_out == NULL || micro == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->micro_candidate_would_create = micro->would_create;
    out->micro_candidate_reject_reason = micro->reject_reason;
    out->micro_candidate_peak_sample_x = micro->peak_sample_x;
    out->micro_candidate_peak_sample_y = micro->peak_sample_y;
    out->micro_candidate_peak_delta = micro->peak_delta;
    out->micro_candidate_peak_score = micro->peak_score;
    out->micro_candidate_prominence = micro->prominence;
    out->micro_candidate_ring_mean = micro->ring_mean;
    out->micro_candidate_ring_hot_fraction = micro->ring_hot_fraction;
    out->micro_candidate_hot_count = micro->hot_count;
    out->micro_candidate_sample_count = micro->sample_count;
    out->micro_candidate_compactness = micro->compactness;
    out->micro_candidate_centroid_dx = micro->centroid_dx;
    out->micro_candidate_centroid_dy = micro->centroid_dy;
    out->micro_candidate_centroid_offset = micro->centroid_offset;
    out->micro_candidate_one_sided_support = micro->one_sided_support;
    out->micro_candidate_distance_to_debug_target = micro->distance_to_debug_target;
}

void anomaly_result_publish_thermal_debug_target_suppressor(
        anomaly_result_t                                            *result_out,
        const anomaly_result_thermal_debug_target_suppressor_publication_t *suppressor) {
    if (result_out == NULL || suppressor == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->suppressor_sample_x = suppressor->suppressor_sample_x;
    out->suppressor_sample_y = suppressor->suppressor_sample_y;
    out->suppressor_delta = suppressor->suppressor_delta;
    out->suppressor_score = suppressor->suppressor_score;
}

void anomaly_result_publish_thermal_debug_target_component_trace(
        anomaly_result_t                                                       *result_out,
        const anomaly_result_thermal_debug_target_component_trace_publication_t *trace) {
    if (result_out == NULL || trace == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->component_seed_x = trace->component_seed_x;
    out->component_seed_y = trace->component_seed_y;
    out->component_peak_x = trace->component_peak_x;
    out->component_peak_y = trace->component_peak_y;
    out->component_area = trace->component_area;
    out->component_span = trace->component_span;
    out->component_fill = trace->component_fill;
    out->component_peak_delta = trace->component_peak_delta;
    out->component_mean_delta = trace->component_mean_delta;
    out->component_quality = trace->component_quality;
    out->component_rejected = trace->component_rejected;
    out->rejection_gate = trace->rejection_gate;
}

void anomaly_result_publish_thermal_debug_target_nearby_rejected_component(
        anomaly_result_t                                                                 *result_out,
        const anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t *component) {
    if (result_out == NULL || component == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->nearby_rejected_component_valid = component->valid;
    out->nearby_rejected_component_contains_target = component->contains_target;
    out->nearby_rejected_component_gate = component->gate;
    out->nearby_rejected_component_seed_x = component->seed_x;
    out->nearby_rejected_component_seed_y = component->seed_y;
    out->nearby_rejected_component_peak_x = component->peak_x;
    out->nearby_rejected_component_peak_y = component->peak_y;
    out->nearby_rejected_component_area = component->area;
    out->nearby_rejected_component_span = component->span;
    out->nearby_rejected_component_fill = component->fill;
    out->nearby_rejected_component_peak_delta = component->peak_delta;
    out->nearby_rejected_component_mean_delta = component->mean_delta;
    out->nearby_rejected_component_quality = component->quality;
    out->nearby_rejected_component_distance = component->distance;
}

void anomaly_result_publish_thermal_debug_target_nms_cap(
        anomaly_result_t                                          *result_out,
        const anomaly_result_thermal_debug_target_nms_cap_publication_t *nms_cap) {
    if (result_out == NULL || nms_cap == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->dropped_by_cap = nms_cap->dropped_by_cap;
    out->dropped_by_nms = nms_cap->dropped_by_nms;
    out->replaced_by_nms = nms_cap->replaced_by_nms;
    out->nms_conflict_rank = nms_cap->nms_conflict_rank;
    out->nms_conflict_sample_x = nms_cap->nms_conflict_sample_x;
    out->nms_conflict_sample_y = nms_cap->nms_conflict_sample_y;
    out->pre_cap_rank = nms_cap->pre_cap_rank;
    out->pre_cap_candidate_count = nms_cap->pre_cap_candidate_count;
    out->pre_cap_limit = nms_cap->pre_cap_limit;
    out->pre_cap_retention_rank = nms_cap->pre_cap_retention_rank;
    out->extracted_rank = nms_cap->extracted_rank;
    out->winning_rank = nms_cap->winning_rank;
}

void anomaly_result_publish_thermal_debug_target_provisional(
        anomaly_result_t                                              *result_out,
        const anomaly_result_thermal_debug_target_provisional_publication_t *provisional) {
    if (result_out == NULL || provisional == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->provisional_candidate_index = provisional->candidate_index;
    out->provisional_score_floor = provisional->score_floor;
    out->provisional_final_score = provisional->final_score;
    out->provisional_score_eligible = provisional->score_eligible;
    out->provisional_shape_eligible = provisional->shape_eligible;
    out->provisional_candidate_rank = provisional->candidate_rank;
    out->provisional_selected_rank = provisional->selected_rank;
    out->provisional_selected_score = provisional->selected_score;
    out->provisional_near_existing_skip = provisional->near_existing_skip;
}

void anomaly_result_publish_thermal_debug_target_raw_delta_rescue(
        anomaly_result_t                                                    *result_out,
        const anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t *rescue) {
    if (result_out == NULL || rescue == NULL) return;
    result_out->thermal_debug.target.raw_delta_rescue_score =
        rescue->raw_delta_rescue_score;
}

void anomaly_result_publish_thermal_debug_target_movement_diagnostics(
        anomaly_result_t                                                            *result_out,
        const anomaly_result_thermal_debug_target_movement_diagnostics_publication_t *movement) {
    if (result_out == NULL || movement == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->movement_residual_px = movement->residual_px;
    out->movement_independent_score = movement->independent_score;
    out->movement_confidence = movement->confidence;
    out->movement_motion_support = movement->motion_support;
    out->movement_layer_class = movement->layer_class;
}

void anomaly_result_publish_thermal_debug_target_local_peak_movement(
        anomaly_result_t                                                          *result_out,
        const anomaly_result_thermal_debug_target_local_peak_movement_publication_t *movement) {
    if (result_out == NULL || movement == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->local_peak_movement_residual_px = movement->residual_px;
    out->local_peak_movement_independent_score = movement->independent_score;
    out->local_peak_movement_confidence = movement->confidence;
    out->local_peak_movement_motion_support = movement->motion_support;
    out->local_peak_movement_layer_class = movement->layer_class;
}

void anomaly_result_publish_thermal_debug_target_rescue_movement_flags(
        anomaly_result_t                                                                 *result_out,
        const anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t    *flags) {
    if (result_out == NULL || flags == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->raw_delta_rescue_eligible = flags->raw_delta_rescue_eligible;
    out->movement_tile_valid = flags->movement_tile_valid;
    out->movement_independent = flags->movement_independent;
    out->movement_parallax = flags->movement_parallax;
    out->would_promote_movement_rescue = flags->would_promote_movement_rescue;
    out->local_peak_movement_tile_valid = flags->local_peak_movement_tile_valid;
    out->local_peak_movement_independent = flags->local_peak_movement_independent;
    out->local_peak_movement_parallax = flags->local_peak_movement_parallax;
}

void anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(
        anomaly_result_t                                                                 *result_out,
        const anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t   *shadow_rescue) {
    if (result_out == NULL || shadow_rescue == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->movement_shadow_motion_support = shadow_rescue->movement_shadow_motion_support;
    out->movement_shadow_parallax_penalty = shadow_rescue->movement_shadow_parallax_penalty;
    out->movement_shadow_thermal_support = shadow_rescue->movement_shadow_thermal_support;
    out->movement_shadow_clutter_veto = shadow_rescue->movement_shadow_clutter_veto;
    out->movement_rescue_would_publish = shadow_rescue->movement_rescue_would_publish;
    out->movement_boost_would_publish = shadow_rescue->movement_boost_would_publish;
    out->movement_rescue_reject_reason = shadow_rescue->movement_rescue_reject_reason;
}

void anomaly_result_publish_thermal_debug_target_track_match(
        anomaly_result_t                                                           *result_out,
        const anomaly_result_thermal_debug_target_track_match_publication_t        *track_match) {
    if (result_out == NULL || track_match == NULL) return;
    anomaly_debug_thermal_target_t *out = &result_out->thermal_debug.target;
    out->matched_track_index = track_match->matched_track_index;
    out->matched_track_id = track_match->matched_track_id;
    out->matched_track_hit_count = track_match->matched_track_hit_count;
    out->matched_track_miss_count = track_match->matched_track_miss_count;
    out->matched_track_hold_count = track_match->matched_track_hold_count;
    out->matched_track_publish_confirmed =
        track_match->matched_track_publish_confirmed;
}

void anomaly_result_publish_thermal_debug_target_stage(
        anomaly_result_t                                                  *result_out,
        const anomaly_result_thermal_debug_target_stage_publication_t     *stage) {
    if (result_out == NULL || stage == NULL) return;
    result_out->thermal_debug.target.stage = stage->stage;
}

void anomaly_result_publish_thermal_debug_candidates_base(
        anomaly_result_t                                                *result_out,
        const anomaly_result_thermal_debug_candidates_base_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_base_publication_t *src =
            &candidates->candidates[i];
        anomaly_debug_thermal_candidate_t *dbg = &thermal->candidates[i];
        dbg->valid = true;
        dbg->pixel_x = src->pixel_x;
        dbg->pixel_y = src->pixel_y;
        dbg->x_norm = (float)src->pixel_x / candidates->frame_w;
        dbg->y_norm = (float)src->pixel_y / candidates->frame_h;
        anomaly_color_candidate_bbox_norm(
            candidates->roi_x0,
            candidates->roi_y0,
            candidates->sample_step,
            src->min_x,
            src->min_y,
            src->max_x,
            src->max_y,
            candidates->frame_w,
            candidates->frame_h,
            &dbg->bbox_left_norm,
            &dbg->bbox_top_norm,
            &dbg->bbox_right_norm,
            &dbg->bbox_bottom_norm);
        dbg->base_score = src->base_score;
        dbg->final_score = src->final_score;
        dbg->temporal_score = src->temporal_score;
        dbg->area = src->area;
        dbg->span = src->span;
        dbg->fill = src->fill;
        dbg->center_share = src->center_share;
        dbg->quality = src->quality;
        dbg->isolation_rank = src->isolation_rank;
        dbg->peak_delta = src->peak_delta;
        dbg->mean_delta = src->mean_delta;
        dbg->score_scale = src->score_scale;
        dbg->history_scale = src->history_scale;
        dbg->apparent_size_scale = src->apparent_size_scale;
        dbg->isolation_track_scale = src->isolation_track_scale;
        dbg->context_scale = src->context_scale;
        dbg->parent_scale = src->parent_scale;
        dbg->area_rank = src->area_rank;
        dbg->span_rank = src->span_rank;
        dbg->center_rank = src->center_rank;
        dbg->quality_rank = src->quality_rank;
        dbg->patch_support = src->patch_support;
        dbg->motion_support = src->motion_support;
        dbg->singleton_score_scale = src->singleton_score_scale;
        dbg->retention_rank = src->retention_rank;
        dbg->movement_layer_class = ANOMALY_MOVEMENT_LAYER_UNKNOWN;
        dbg->nearest_track_index = -1;
        dbg->nearest_track_id = -1;
        dbg->nearest_track_hit_count = -1;
    }
}

bool anomaly_result_copy_thermal_debug_candidate(
        const anomaly_result_t            *result_out,
        int                                candidate_index,
        anomaly_debug_thermal_candidate_t *candidate_out) {
    if (result_out == NULL || candidate_out == NULL) return false;
    if (candidate_index < 0 ||
        candidate_index >= ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES) {
        return false;
    }
    *candidate_out = result_out->thermal_debug.candidates[candidate_index];
    return true;
}

void anomaly_result_publish_thermal_debug_candidates_movement(
        anomaly_result_t                                                    *result_out,
        const anomaly_result_thermal_debug_candidates_movement_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_movement_publication_t *src =
            &candidates->candidates[i];
        if (!src->movement_tile_valid) continue;
        anomaly_debug_thermal_candidate_t *dbg = &thermal->candidates[i];
        dbg->movement_tile_valid = true;
        dbg->movement_residual_px = src->movement_residual_px;
        dbg->movement_independent_score = src->movement_independent_score;
        dbg->movement_confidence = src->movement_confidence;
        dbg->movement_layer_class = src->movement_layer_class;
        dbg->movement_independent = src->movement_independent;
        dbg->movement_parallax = src->movement_parallax;
    }
}

void anomaly_result_publish_thermal_debug_candidates_nearest_track(
        anomaly_result_t                                                         *result_out,
        const anomaly_result_thermal_debug_candidates_nearest_track_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_nearest_track_publication_t *src =
            &candidates->candidates[i];
        if (!src->nearest_track_valid) continue;
        anomaly_debug_thermal_candidate_t *dbg = &thermal->candidates[i];
        dbg->nearest_track_distance = src->nearest_track_distance;
        dbg->nearest_track_index = src->nearest_track_index;
        dbg->nearest_track_id = src->nearest_track_id;
        dbg->nearest_track_hit_count = src->nearest_track_hit_count;
        dbg->near_tracked_target = src->near_tracked_target;
    }
}

void anomaly_result_publish_thermal_debug_candidates_near_debug(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_thermal_debug_candidates_near_debug_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_near_debug_publication_t *src =
            &candidates->candidates[i];
        if (!src->near_debug_valid) continue;
        thermal->candidates[i].near_debug_target = src->near_debug_target;
    }
}

void anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(
        anomaly_result_t                                                             *result_out,
        const anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t *src =
            &candidates->candidates[i];
        anomaly_debug_thermal_candidate_t *dbg = &thermal->candidates[i];
        dbg->raw_delta_rescue_score = src->raw_delta_rescue_score;
        dbg->raw_delta_rescue_eligible = src->raw_delta_rescue_eligible;
        dbg->would_promote_movement_rescue = src->would_promote_movement_rescue;
    }
}

void anomaly_result_publish_thermal_debug_candidates_final_flags(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_thermal_debug_candidates_final_flags_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_thermal_t *thermal = &result_out->thermal_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_THERMAL_CANDIDATES;
         i++) {
        const anomaly_result_thermal_debug_candidate_final_flags_publication_t *src =
            &candidates->candidates[i];
        anomaly_debug_thermal_candidate_t *dbg = &thermal->candidates[i];
        dbg->singleton_blob = src->singleton_blob;
        dbg->above_threshold = src->above_threshold;
    }
}

void anomaly_result_publish_color_debug_summary(
        anomaly_result_t                                       *result_out,
        const anomaly_result_color_debug_summary_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_debug_color_t *color = &result_out->color_debug;
    memset(color, 0, sizeof(*color));
    color->raw_candidate_valid =
        debug->raw_candidate_index >= 0 || debug->best_score >= 0.0f;
    color->raw_score =
        debug->raw_candidate_index >= 0 ? debug->raw_best_score : debug->best_score;
    color->raw_x_norm =
        (debug->raw_candidate_index >= 0 &&
         (debug->raw_best_x > 0 || debug->raw_best_y > 0))
        ? ((float)debug->raw_best_x / debug->frame_w)
        : ((debug->best_x > 0 || debug->best_y > 0)
           ? ((float)debug->best_x / debug->frame_w)
           : 0.0f);
    color->raw_y_norm =
        (debug->raw_candidate_index >= 0 &&
         (debug->raw_best_x > 0 || debug->raw_best_y > 0))
        ? ((float)debug->raw_best_y / debug->frame_h)
        : ((debug->best_x > 0 || debug->best_y > 0)
           ? ((float)debug->best_y / debug->frame_h)
           : 0.0f);
    color->target_span_px = debug->target_span_px;
    color->target_span_cells = debug->target_span_cells;
    color->max_blob_area_budget = debug->max_blob_area_budget;
    color->active_phase_index = debug->active_phase_index;
    color->active_phase_x = debug->active_phase_x;
    color->active_phase_y = debug->active_phase_y;
    color->selective_reuse_active =
        debug->selective_refresh_active && !debug->forced_full_refresh;
    color->forced_full_refresh = debug->forced_full_refresh;
    color->fallback_reason_flags = debug->fallback_reason_flags;
    color->fresh_sample_count = debug->fresh_sample_count;
    color->carried_sample_count = debug->carried_sample_count;
    color->unsampled_new_exposed_count = debug->unsampled_new_exposed_count;
    if (debug->sample_grid_count > 0) {
        color->fresh_sample_fraction =
            (float)debug->fresh_sample_count / (float)debug->sample_grid_count;
        color->carried_sample_fraction =
            (float)debug->carried_sample_count / (float)debug->sample_grid_count;
        color->unsampled_new_exposed_fraction =
            (float)debug->unsampled_new_exposed_count / (float)debug->sample_grid_count;
    }
    color->histogram_valid_sample_count = debug->histogram_valid_sample_count;
    color->history_reset_applied = debug->history_reset_applied;
    color->history_recovery_frames_remaining = debug->history_recovery_frames_remaining;
    color->history_recent_scale = debug->history_recent_scale;
    color->nonzero_histogram_bins = debug->nonzero_histogram_bins;
    color->max_histogram_current_count = debug->max_histogram_current_count;
    color->max_histogram_recent_count = debug->max_histogram_recent_count;
    color->rarity_seed_count = debug->rarity_seed_count;
    color->support_seed_count = debug->support_seed_count;
    color->support_peak_score = debug->support_peak_score;
    color->coarse_component_count = debug->coarse_component_count;
    color->coarse_oversized_count = debug->coarse_oversized_count;
    color->dense_verify_component_count = debug->dense_verify_component_count;
    color->adaptive_source_coarse_count = debug->adaptive_source_coarse_count;
    color->fresh_distinctness_ratio = debug->fresh_distinctness_ratio;
    color->blob_reject_area_count = debug->blob_reject_area_count;
    color->blob_reject_ring_count = debug->blob_reject_ring_count;
    color->blob_reject_support_mass_count = debug->blob_reject_support_mass_count;
    color->blob_reject_quality_count = debug->blob_reject_quality_count;
    color->blob_examined_count = debug->blob_examined_count;
    color->strongest_reject_reason = debug->strongest_reject_reason;
    color->strongest_reject_peak_support = debug->strongest_reject_peak_support;
    color->strongest_reject_area = debug->strongest_reject_area;
    color->strongest_reject_span = debug->strongest_reject_span;
    color->strongest_reject_ring_fraction = debug->strongest_reject_ring_fraction;
    color->strongest_reject_support_mass = debug->strongest_reject_support_mass;
    color->strongest_reject_quality = debug->strongest_reject_quality;
    memset(&color->strongest_seed, 0, sizeof(color->strongest_seed));
    color->strongest_seed.valid = debug->strongest_seed_score > 0.0f;
    color->strongest_seed.sample_x = debug->strongest_seed_sample_x;
    color->strongest_seed.sample_y = debug->strongest_seed_sample_y;
    color->strongest_seed.score = debug->strongest_seed_score;
    color->strongest_seed.hist_key = debug->strongest_seed_hist_key;
    color->strongest_seed.hist_current_count = debug->strongest_seed_hist_current_count;
    color->strongest_seed.hist_recent_count = debug->strongest_seed_hist_recent_count;
    color->strongest_seed.hist_rarity_score = debug->strongest_seed_hist_rarity_score;
    color->strongest_seed.local_support_count = debug->strongest_seed_local_support_count;
    color->raw_candidate_index = debug->raw_candidate_index;
    color->winner_gate_active = debug->winner_gate_active;
    color->winner_gate_reject_reason = debug->winner_gate_reject_reason;
    color->winner_gate_max_span = debug->winner_gate_max_span;
    color->winner_gate_max_area = debug->winner_gate_max_area;
    color->winner_gate_min_rarity = debug->winner_gate_min_rarity;
    color->winner_gate_max_commonness = debug->winner_gate_max_commonness;
    color->winning_candidate_index = debug->winning_candidate_index;
    color->candidate_count = debug->candidate_count;
}

void anomaly_result_publish_color_debug_target_base(
        anomaly_result_t                                           *result_out,
        const anomaly_result_color_debug_target_base_publication_t *target) {
    if (result_out == NULL || target == NULL) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    memset(out, 0, sizeof(*out));
    out->enabled = target->enabled;
    out->valid = target->valid;
    out->inside_scan_zone = target->inside_scan_zone;
    out->refresh_skipped = target->refresh_skipped;
    out->sampled_this_frame = target->sampled_this_frame;
    out->carried_from_history = target->carried_from_history;
    out->pixel_x = target->pixel_x;
    out->pixel_y = target->pixel_y;
    out->sample_x = target->sample_x;
    out->sample_y = target->sample_y;
    out->x_norm = target->enabled ? target->configured_x_norm : 0.0f;
    out->y_norm = target->enabled ? target->configured_y_norm : 0.0f;
    out->hist_key = target->hist_key;
    out->hist_current_count = target->hist_current_count;
    out->hist_recent_count = target->hist_recent_count;
    out->hist_rarity_score = target->hist_rarity_score;
    out->local_support_count = target->local_support_count;
    out->patch_valid_count = target->patch_valid_count;
    out->coherent_patch_cell_count = target->coherent_patch_cell_count;
    out->coherent_patch_fresh_cell_count = target->coherent_patch_fresh_cell_count;
    out->coherent_patch_multicell = target->coherent_patch_multicell;
    out->patch_mean_u = target->patch_mean_u;
    out->patch_mean_v = target->patch_mean_v;
    out->patch_mean_luma = target->patch_mean_luma;
    out->ring_mean_u = target->ring_mean_u;
    out->ring_mean_v = target->ring_mean_v;
    out->ring_mean_luma = target->ring_mean_luma;
    out->ring_chroma_contrast = target->ring_chroma_contrast;
    out->ring_luma_contrast = target->ring_luma_contrast;
    out->ring_neighbor_count = target->ring_neighbor_count;
    out->pre_support_score = target->pre_support_score;
    out->support_score = target->support_score;
    out->support_map_local_peak = target->support_map_local_peak;
    out->support_map_ring_mean = target->support_map_ring_mean;
    out->support_map_density = target->support_map_density;
    out->support_map_distinctness_ratio = target->support_map_distinctness_ratio;
    out->support_map_compact_prominence = target->support_map_compact_prominence;
    out->support_map_core_share = target->support_map_core_share;
    out->support_map_seed_floor = target->support_map_seed_floor;
    out->support_seed_eligible = target->support_seed_eligible;
}

void anomaly_result_publish_color_debug_target_component_trace(
        anomaly_result_t                                                      *result_out,
        const anomaly_result_color_debug_target_component_trace_publication_t *trace) {
    if (result_out == NULL || trace == NULL) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    out->component_seed_x = trace->component_seed_x;
    out->component_seed_y = trace->component_seed_y;
    out->component_peak_x = trace->component_peak_x;
    out->component_peak_y = trace->component_peak_y;
    out->component_area = trace->component_area;
    out->component_span = trace->component_span;
    out->component_fill = trace->component_fill;
    out->component_peak_support = trace->component_peak_support;
    out->component_mean_support = trace->component_mean_support;
    out->component_quality = trace->component_quality;
    out->component_ring_fraction = trace->component_ring_fraction;
    out->component_support_mass = trace->component_support_mass;
    out->component_rejected = trace->component_rejected;
    out->component_rejection_reason = trace->component_rejection_reason;
    out->dropped_by_cap = trace->dropped_by_cap;
    out->dropped_by_nms = trace->dropped_by_nms;
    out->replaced_by_nms = trace->replaced_by_nms;
    out->nms_conflict_rank = trace->nms_conflict_rank;
    out->nms_conflict_sample_x = trace->nms_conflict_sample_x;
    out->nms_conflict_sample_y = trace->nms_conflict_sample_y;
    out->pre_cap_rank = trace->pre_cap_rank;
    out->pre_cap_candidate_count = trace->pre_cap_candidate_count;
    out->pre_cap_limit = trace->pre_cap_limit;
    out->pre_cap_retention_rank = trace->pre_cap_retention_rank;
}

void anomaly_result_publish_color_debug_target_component_bbox(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_color_debug_target_component_bbox_publication_t *bbox) {
    if (result_out == NULL || bbox == NULL) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    anomaly_color_candidate_bbox_norm(
        bbox->roi_x0,
        bbox->roi_y0,
        bbox->sample_step,
        bbox->min_x,
        bbox->min_y,
        bbox->max_x,
        bbox->max_y,
        bbox->frame_w,
        bbox->frame_h,
        &out->component_bbox_left_norm,
        &out->component_bbox_top_norm,
        &out->component_bbox_right_norm,
        &out->component_bbox_bottom_norm);
}

void anomaly_result_publish_color_debug_target_candidate_indices(
        anomaly_result_t                                                       *result_out,
        const anomaly_result_color_debug_target_candidate_indices_publication_t *indices) {
    if (result_out == NULL || indices == NULL) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    out->extracted_candidate_index = -1;
    if (indices->component_peak_x >= 0 &&
        indices->component_peak_y >= 0 &&
        indices->candidates != NULL) {
        for (int ci = 0; ci < indices->candidate_count; ci++) {
            if (indices->candidates[ci].sample_x == indices->component_peak_x &&
                indices->candidates[ci].sample_y == indices->component_peak_y) {
                out->extracted_candidate_index = ci;
                break;
            }
        }
    }
    out->matched_candidate_index = indices->matched_candidate_index;
    if (out->extracted_candidate_index < 0 &&
        indices->matched_candidate_index >= 0 &&
        indices->matched_candidate_index < indices->candidate_count) {
        out->extracted_candidate_index = indices->matched_candidate_index;
    }
    out->nearest_candidate_index = indices->nearest_candidate_index;
    out->nearest_candidate_distance = indices->nearest_candidate_distance;
    out->winning_candidate_index = indices->winning_candidate_index;
    out->winning_rank =
        indices->matched_candidate_index == indices->winning_candidate_index
            ? indices->winning_candidate_index
            : -1;
}

void anomaly_result_publish_color_debug_target_gate_stage(
        anomaly_result_t                                                 *result_out,
        const anomaly_result_color_debug_target_gate_stage_publication_t *gate_stage) {
    if (result_out == NULL || gate_stage == NULL) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    out->rejected_by_winner_gate =
        gate_stage->winner_gate_reject_reason != ANOMALY_COLOR_WINNER_GATE_NONE &&
        gate_stage->matched_candidate_index >= 0 &&
        gate_stage->matched_candidate_index == gate_stage->raw_best_color_candidate_index;
    out->winner_gate_reject_reason =
        out->rejected_by_winner_gate
            ? gate_stage->winner_gate_reject_reason
            : ANOMALY_COLOR_WINNER_GATE_NONE;
    out->stage = gate_stage->stage;
}

void anomaly_result_publish_color_debug_target_matched_candidate(
        anomaly_result_t                                                        *result_out,
        const anomaly_result_color_debug_target_matched_candidate_publication_t *matched) {
    if (result_out == NULL || matched == NULL) return;
    if (!matched->valid) return;
    anomaly_debug_color_target_t *out = &result_out->color_debug.target;
    out->matched_candidate_score = matched->score;
    out->matched_candidate_x_norm = (float)matched->pixel_x / matched->frame_w;
    out->matched_candidate_y_norm = (float)matched->pixel_y / matched->frame_h;
    anomaly_color_candidate_bbox_norm(
        matched->roi_x0,
        matched->roi_y0,
        matched->sample_step,
        matched->min_x,
        matched->min_y,
        matched->max_x,
        matched->max_y,
        matched->frame_w,
        matched->frame_h,
        &out->matched_bbox_left_norm,
        &out->matched_bbox_top_norm,
        &out->matched_bbox_right_norm,
        &out->matched_bbox_bottom_norm);
}

void anomaly_result_publish_color_debug_candidates(
        anomaly_result_t                                         *result_out,
        const anomaly_result_color_debug_candidates_publication_t *candidates) {
    if (result_out == NULL || candidates == NULL || candidates->candidates == NULL) return;
    if (candidates->candidate_count <= 0) return;
    anomaly_debug_color_t *color = &result_out->color_debug;
    for (int i = 0;
         i < candidates->candidate_count && i < ANOMALY_DEBUG_TOP_COLOR_CANDIDATES;
         i++) {
        const anomaly_result_color_debug_candidate_publication_t *src =
            &candidates->candidates[i];
        anomaly_debug_color_candidate_t *dbg = &color->candidates[i];
        dbg->valid = true;
        dbg->pixel_x = src->pixel_x;
        dbg->pixel_y = src->pixel_y;
        dbg->x_norm = (float)src->pixel_x / candidates->frame_w;
        dbg->y_norm = (float)src->pixel_y / candidates->frame_h;
        anomaly_color_candidate_bbox_norm(
            candidates->roi_x0,
            candidates->roi_y0,
            candidates->sample_step,
            src->min_x,
            src->min_y,
            src->max_x,
            src->max_y,
            candidates->frame_w,
            candidates->frame_h,
            &dbg->bbox_left_norm,
            &dbg->bbox_top_norm,
            &dbg->bbox_right_norm,
            &dbg->bbox_bottom_norm);
        dbg->base_score = src->base_score;
        dbg->final_score = src->final_score;
        dbg->temporal_score = -1.0f;
        dbg->area = src->area;
        dbg->span = src->span;
        dbg->fill = src->fill;
        dbg->center_share = src->center_share;
        dbg->quality = src->quality;
        dbg->isolation_score = src->isolation_score;
        dbg->ring_fraction = src->ring_fraction;
        dbg->support_mass = src->support_mass;
        dbg->contrast_weight = src->contrast_weight;
        dbg->hist_key = src->hist_key;
        dbg->hist_current_count = src->hist_current_count;
        dbg->hist_recent_count = src->hist_recent_count;
        dbg->hist_rarity_score = src->hist_rarity_score;
        dbg->small_target_span_ratio = src->small_target_span_ratio;
        dbg->small_target_area_ratio = src->small_target_area_ratio;
        dbg->scene_commonness = src->scene_commonness;
        dbg->retention_rank = src->retention_rank;
        dbg->above_threshold = src->above_threshold;
    }
}

void anomaly_result_publish_boxes(
        anomaly_result_t    *result_out,
        const anomaly_box_t *boxes,
        int                  box_count) {
    if (result_out == NULL) return;
    result_out->box_count = box_count;
    if (boxes == NULL) return;
    for (int i = 0; i < box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++) {
        result_out->boxes[i] = boxes[i];
    }
}

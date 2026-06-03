#include "anomaly_result_builder.h"

#include "anomaly_color_detector.h"
#include "anomaly_debug_helpers.h"

#include <math.h>
#include <stdint.h>
#include <string.h>

static float anomaly_result_clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
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
    int box_count = 0;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS && box_count < max_boxes; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->publish_confirmed || track->hit_count < min_hits) continue;
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

void anomaly_result_publish_frame_metadata(
        anomaly_result_t                      *result_out,
        const anomaly_result_frame_metadata_t *metadata) {
    if (result_out == NULL || metadata == NULL) return;
    result_out->had_discontinuity = metadata->had_discontinuity;
    result_out->registration_ran_this_frame = metadata->registration_ran_this_frame;
    result_out->appearance_refresh_ran_this_frame = metadata->appearance_refresh_ran_this_frame;
    result_out->registration_health = metadata->registration_health;
    result_out->rescan_mode = metadata->rescan_mode;
    result_out->scan_plan = metadata->scan_plan;
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

void anomaly_result_publish_saliency_debug(
        anomaly_result_t                                  *result_out,
        const anomaly_result_saliency_debug_publication_t *debug) {
    if (result_out == NULL || debug == NULL) return;
    anomaly_debug_saliency_t *saliency = &result_out->saliency_debug;
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

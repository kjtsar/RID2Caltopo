#ifndef ANOMALY_RESULT_BUILDER_H
#define ANOMALY_RESULT_BUILDER_H

#include "anomaly_analysis.h"

typedef struct anomaly_registration_model_t anomaly_registration_model_t;

typedef struct {
    bool had_discontinuity;
    bool registration_ran_this_frame;
    bool appearance_refresh_ran_this_frame;
    anomaly_registration_health_t registration_health;
    anomaly_rescan_mode_t rescan_mode;
    anomaly_scan_plan_t scan_plan;
    int adaptive_effective_stride;
    int adaptive_stable_frames;
    int adaptive_drop_hold_frames;
    float adaptive_motion_load;
    uint32_t adaptive_reason_flags;
    const anomaly_registration_model_t *registration;
    const anomaly_debug_movement_t *movement_debug;
} anomaly_result_frame_metadata_t;

void anomaly_result_publish_movement_debug(
        anomaly_result_t              *result_out,
        const anomaly_debug_movement_t *movement_debug);

void anomaly_result_publish_scan_plan(
        anomaly_result_t          *result_out,
        const anomaly_scan_plan_t *scan_plan);

void anomaly_result_publish_rescan_mode(
        anomaly_result_t      *result_out,
        anomaly_rescan_mode_t rescan_mode);

typedef struct {
    bool scene_discontinuity;
    int sample_step;
    int motion_step;
    int global_count;
    int motion_candidate_count;
    float global_motion_mean;
    float global_motion_std;
    float zoom_motion_scale;
    float broad_motion_scale;
    float global_motion_load;
} anomaly_result_motion_appearance_debug_summary_publication_t;

typedef struct {
    float raw_score;
    int raw_x;
    int raw_y;
    float frame_w;
    float frame_h;
    float winner_component_area_frac;
    float winner_component_span_frac;
    float winner_component_fill_ratio;
    float zoom_motion_scale;
    float broad_motion_scale;
    float global_motion_load;
    float winner_texture_scale;
    float winner_structure_scale;
    float winner_support_scale;
    float winner_persistence_scale;
    const anomaly_debug_candidate_t *top_candidates;
    int top_candidate_count;
} anomaly_result_motion_appearance_debug_result_publication_t;

void anomaly_result_publish_motion_appearance_debug_summary(
        anomaly_result_t                                                   *result_out,
        const anomaly_result_motion_appearance_debug_summary_publication_t *debug);

void anomaly_result_publish_motion_appearance_debug_result(
        anomaly_result_t                                                  *result_out,
        const anomaly_result_motion_appearance_debug_result_publication_t *debug);

typedef struct {
    bool bg_ready;
    float raw_score;
    int raw_x;
    int raw_y;
    float frame_w;
    float frame_h;
    float tracked_score_pre;
    bool acc_pre_active;
    int acc_pre_hits;
    float acc_pre_x_norm;
    float acc_pre_y_norm;
    bool acc_post_active;
    int acc_post_hits;
    float acc_post_x_norm;
    float acc_post_y_norm;
    bool switch_suppressed;
    const anomaly_debug_candidate_t *top_candidates;
    int top_candidate_count;
} anomaly_result_saliency_debug_publication_t;

typedef struct {
    bool bg_ready;
    float raw_score;
    int raw_x;
    int raw_y;
    float frame_w;
    float frame_h;
    float frame_delta_mean;
    float frame_delta_norm;
    float frame_blob_contrast_mean;
    float frame_blob_contrast_std;
    int winning_candidate_index;
    int candidate_count;
} anomaly_result_thermal_debug_summary_publication_t;

typedef struct {
    bool enabled;
    bool valid;
    bool inside_scan_zone;
    int pixel_x;
    int pixel_y;
    int sample_x;
    int sample_y;
    float x_norm;
    float y_norm;
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
    int local_peak_sample_x;
    int local_peak_sample_y;
    float local_peak_delta;
    float local_peak_score;
    float local_peak_distance;
    int local_peak_raw_sample_x;
    int local_peak_raw_sample_y;
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
} anomaly_result_thermal_debug_target_base_publication_t;

typedef struct {
    bool would_create;
    anomaly_debug_thermal_micro_reject_t reject_reason;
    int peak_sample_x;
    int peak_sample_y;
    float peak_delta;
    float peak_score;
    float prominence;
    float ring_mean;
    float ring_hot_fraction;
    int hot_count;
    int sample_count;
    float compactness;
    float centroid_dx;
    float centroid_dy;
    float centroid_offset;
    float one_sided_support;
    float distance_to_debug_target;
} anomaly_result_thermal_debug_target_micro_candidate_publication_t;

typedef struct {
    int suppressor_sample_x;
    int suppressor_sample_y;
    float suppressor_delta;
    float suppressor_score;
} anomaly_result_thermal_debug_target_suppressor_publication_t;

typedef struct {
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
} anomaly_result_thermal_debug_target_component_trace_publication_t;

typedef struct {
    bool valid;
    bool contains_target;
    anomaly_debug_thermal_target_gate_t gate;
    int seed_x;
    int seed_y;
    int peak_x;
    int peak_y;
    float area;
    float span;
    float fill;
    float peak_delta;
    float mean_delta;
    float quality;
    float distance;
} anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t;

typedef struct {
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
} anomaly_result_thermal_debug_target_nms_cap_publication_t;

typedef struct {
    int candidate_index;
    float score_floor;
    float final_score;
    bool score_eligible;
    bool shape_eligible;
    float candidate_rank;
    int selected_rank;
    float selected_score;
    bool near_existing_skip;
} anomaly_result_thermal_debug_target_provisional_publication_t;

typedef struct {
    float raw_delta_rescue_score;
} anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t;

typedef struct {
    float residual_px;
    float independent_score;
    float confidence;
    float motion_support;
    int layer_class;
} anomaly_result_thermal_debug_target_movement_diagnostics_publication_t;

typedef struct {
    float residual_px;
    float independent_score;
    float confidence;
    float motion_support;
    int layer_class;
} anomaly_result_thermal_debug_target_local_peak_movement_publication_t;

typedef struct {
    bool raw_delta_rescue_eligible;
    bool movement_tile_valid;
    bool movement_independent;
    bool movement_parallax;
    bool would_promote_movement_rescue;
    bool local_peak_movement_tile_valid;
    bool local_peak_movement_independent;
    bool local_peak_movement_parallax;
} anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t;

typedef struct {
    bool movement_shadow_motion_support;
    bool movement_shadow_parallax_penalty;
    bool movement_shadow_thermal_support;
    bool movement_shadow_clutter_veto;
    bool movement_rescue_would_publish;
    bool movement_boost_would_publish;
    anomaly_debug_movement_shadow_reject_t movement_rescue_reject_reason;
} anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t;

typedef struct {
    int matched_track_index;
    int matched_track_id;
    int matched_track_hit_count;
    int matched_track_miss_count;
    int matched_track_hold_count;
    bool matched_track_publish_confirmed;
} anomaly_result_thermal_debug_target_track_match_publication_t;

typedef struct {
    anomaly_debug_thermal_target_stage_t stage;
} anomaly_result_thermal_debug_target_stage_publication_t;

typedef struct {
    int pixel_x;
    int pixel_y;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
    float base_score;
    float final_score;
    float temporal_score;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float isolation_rank;
    float peak_delta;
    float mean_delta;
    float score_scale;
    float history_scale;
    float apparent_size_scale;
    float isolation_track_scale;
    float context_scale;
    float parent_scale;
    float area_rank;
    float span_rank;
    float center_rank;
    float quality_rank;
    float patch_support;
    float motion_support;
    float singleton_score_scale;
    float retention_rank;
} anomaly_result_thermal_debug_candidate_base_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_base_publication_t *candidates;
    int candidate_count;
    int roi_x0;
    int roi_y0;
    int sample_step;
    float frame_w;
    float frame_h;
} anomaly_result_thermal_debug_candidates_base_publication_t;

typedef struct {
    bool movement_tile_valid;
    float movement_residual_px;
    float movement_independent_score;
    float movement_confidence;
    int movement_layer_class;
    bool movement_independent;
    bool movement_parallax;
} anomaly_result_thermal_debug_candidate_movement_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_movement_publication_t *candidates;
    int candidate_count;
} anomaly_result_thermal_debug_candidates_movement_publication_t;

typedef struct {
    bool nearest_track_valid;
    float nearest_track_distance;
    int nearest_track_index;
    int nearest_track_id;
    int nearest_track_hit_count;
    bool near_tracked_target;
} anomaly_result_thermal_debug_candidate_nearest_track_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_nearest_track_publication_t *candidates;
    int candidate_count;
} anomaly_result_thermal_debug_candidates_nearest_track_publication_t;

typedef struct {
    bool near_debug_valid;
    bool near_debug_target;
} anomaly_result_thermal_debug_candidate_near_debug_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_near_debug_publication_t *candidates;
    int candidate_count;
} anomaly_result_thermal_debug_candidates_near_debug_publication_t;

typedef struct {
    float raw_delta_rescue_score;
    bool raw_delta_rescue_eligible;
    bool would_promote_movement_rescue;
} anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_raw_delta_rescue_publication_t *candidates;
    int candidate_count;
} anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t;

typedef struct {
    bool singleton_blob;
    bool above_threshold;
} anomaly_result_thermal_debug_candidate_final_flags_publication_t;

typedef struct {
    const anomaly_result_thermal_debug_candidate_final_flags_publication_t *candidates;
    int candidate_count;
} anomaly_result_thermal_debug_candidates_final_flags_publication_t;

typedef struct {
    int raw_candidate_index;
    float raw_best_score;
    int raw_best_x;
    int raw_best_y;
    float best_score;
    int best_x;
    int best_y;
    float frame_w;
    float frame_h;
    float target_span_px;
    int target_span_cells;
    int max_blob_area_budget;
    int active_phase_index;
    int active_phase_x;
    int active_phase_y;
    bool selective_refresh_active;
    bool forced_full_refresh;
    uint32_t fallback_reason_flags;
    int fresh_sample_count;
    int carried_sample_count;
    int unsampled_new_exposed_count;
    int sample_grid_count;
    int histogram_valid_sample_count;
    bool history_reset_applied;
    int history_recovery_frames_remaining;
    float history_recent_scale;
    int nonzero_histogram_bins;
    float max_histogram_current_count;
    float max_histogram_recent_count;
    int rarity_seed_count;
    int support_seed_count;
    float support_peak_score;
    int coarse_component_count;
    int coarse_oversized_count;
    int dense_verify_component_count;
    int adaptive_source_coarse_count;
    float fresh_distinctness_ratio;
    int blob_reject_area_count;
    int blob_reject_ring_count;
    int blob_reject_support_mass_count;
    int blob_reject_quality_count;
    int blob_examined_count;
    int strongest_reject_reason;
    float strongest_reject_peak_support;
    float strongest_reject_area;
    float strongest_reject_span;
    float strongest_reject_ring_fraction;
    float strongest_reject_support_mass;
    float strongest_reject_quality;
    int strongest_seed_sample_x;
    int strongest_seed_sample_y;
    float strongest_seed_score;
    uint32_t strongest_seed_hist_key;
    float strongest_seed_hist_current_count;
    float strongest_seed_hist_recent_count;
    float strongest_seed_hist_rarity_score;
    int strongest_seed_local_support_count;
    bool winner_gate_active;
    int winner_gate_reject_reason;
    float winner_gate_max_span;
    float winner_gate_max_area;
    float winner_gate_min_rarity;
    float winner_gate_max_commonness;
    int winning_candidate_index;
    int candidate_count;
} anomaly_result_color_debug_summary_publication_t;

typedef struct {
    bool enabled;
    bool valid;
    bool inside_scan_zone;
    bool refresh_skipped;
    bool sampled_this_frame;
    bool carried_from_history;
    int pixel_x;
    int pixel_y;
    int sample_x;
    int sample_y;
    float configured_x_norm;
    float configured_y_norm;
    int hist_key;
    float hist_current_count;
    float hist_recent_count;
    float hist_rarity_score;
    int local_support_count;
    int patch_valid_count;
    int coherent_patch_cell_count;
    int coherent_patch_fresh_cell_count;
    bool coherent_patch_multicell;
    float patch_mean_u;
    float patch_mean_v;
    float patch_mean_luma;
    float ring_mean_u;
    float ring_mean_v;
    float ring_mean_luma;
    float ring_chroma_contrast;
    float ring_luma_contrast;
    int ring_neighbor_count;
    float pre_support_score;
    float support_score;
    float support_map_local_peak;
    float support_map_ring_mean;
    float support_map_density;
    float support_map_distinctness_ratio;
    float support_map_compact_prominence;
    float support_map_core_share;
    float support_map_seed_floor;
    bool support_seed_eligible;
} anomaly_result_color_debug_target_base_publication_t;

typedef struct {
    int component_seed_x;
    int component_seed_y;
    int component_peak_x;
    int component_peak_y;
    float component_area;
    float component_span;
    float component_fill;
    float component_peak_support;
    float component_mean_support;
    float component_quality;
    float component_ring_fraction;
    float component_support_mass;
    bool component_rejected;
    int component_rejection_reason;
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
} anomaly_result_color_debug_target_component_trace_publication_t;

typedef struct {
    int roi_x0;
    int roi_y0;
    int sample_step;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
    float frame_w;
    float frame_h;
} anomaly_result_color_debug_target_component_bbox_publication_t;

typedef struct {
    int sample_x;
    int sample_y;
} anomaly_result_candidate_sample_t;

typedef struct {
    int component_peak_x;
    int component_peak_y;
    const anomaly_result_candidate_sample_t *candidates;
    int candidate_count;
    int matched_candidate_index;
    int nearest_candidate_index;
    float nearest_candidate_distance;
    int winning_candidate_index;
} anomaly_result_color_debug_target_candidate_indices_publication_t;

typedef struct {
    int winner_gate_reject_reason;
    int matched_candidate_index;
    int raw_best_color_candidate_index;
    anomaly_debug_color_target_stage_t stage;
} anomaly_result_color_debug_target_gate_stage_publication_t;

typedef struct {
    bool valid;
    float score;
    int pixel_x;
    int pixel_y;
    int roi_x0;
    int roi_y0;
    int sample_step;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
    float frame_w;
    float frame_h;
} anomaly_result_color_debug_target_matched_candidate_publication_t;

typedef struct {
    int pixel_x;
    int pixel_y;
    int min_x;
    int min_y;
    int max_x;
    int max_y;
    float base_score;
    float final_score;
    float area;
    float span;
    float fill;
    float center_share;
    float quality;
    float isolation_score;
    float ring_fraction;
    float support_mass;
    float contrast_weight;
    int hist_key;
    float hist_current_count;
    float hist_recent_count;
    float hist_rarity_score;
    float center_u;
    float center_v;
    float center_luma;
    float local_ring_chroma_contrast;
    float local_ring_luma_contrast;
    int local_ring_neighbor_count;
    float current_nearest_hist_distance;
    float recent_nearest_hist_distance;
    float small_target_span_ratio;
    float small_target_area_ratio;
    float scene_commonness;
    float retention_rank;
    bool above_threshold;
} anomaly_result_color_debug_candidate_publication_t;

typedef struct {
    const anomaly_result_color_debug_candidate_publication_t *candidates;
    int candidate_count;
    int roi_x0;
    int roi_y0;
    int sample_step;
    float frame_w;
    float frame_h;
} anomaly_result_color_debug_candidates_publication_t;

int anomaly_result_build_boxes(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     motion_box_algorithm,
        anomaly_box_t          *boxes,
        int                     max_boxes);

void anomaly_result_publish_frame_metadata(
        anomaly_result_t                      *result_out,
        const anomaly_result_frame_metadata_t *metadata);

void anomaly_result_publish_saliency_debug(
        anomaly_result_t                                  *result_out,
        const anomaly_result_saliency_debug_publication_t *debug);

void anomaly_result_publish_thermal_debug_summary(
        anomaly_result_t                                          *result_out,
        const anomaly_result_thermal_debug_summary_publication_t  *debug);

void anomaly_result_publish_thermal_debug_target_base(
        anomaly_result_t                                              *result_out,
        const anomaly_result_thermal_debug_target_base_publication_t  *target);

void anomaly_result_publish_thermal_debug_target_micro_candidate(
        anomaly_result_t                                                           *result_out,
        const anomaly_result_thermal_debug_target_micro_candidate_publication_t    *micro);

void anomaly_result_publish_thermal_debug_target_suppressor(
        anomaly_result_t                                               *result_out,
        const anomaly_result_thermal_debug_target_suppressor_publication_t *suppressor);

void anomaly_result_publish_thermal_debug_target_component_trace(
        anomaly_result_t                                                       *result_out,
        const anomaly_result_thermal_debug_target_component_trace_publication_t *trace);

void anomaly_result_publish_thermal_debug_target_nearby_rejected_component(
        anomaly_result_t                                                                  *result_out,
        const anomaly_result_thermal_debug_target_nearby_rejected_component_publication_t *component);

void anomaly_result_publish_thermal_debug_target_nms_cap(
        anomaly_result_t                                           *result_out,
        const anomaly_result_thermal_debug_target_nms_cap_publication_t *nms_cap);

void anomaly_result_publish_thermal_debug_target_provisional(
        anomaly_result_t                                               *result_out,
        const anomaly_result_thermal_debug_target_provisional_publication_t *provisional);

void anomaly_result_publish_thermal_debug_target_raw_delta_rescue(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_thermal_debug_target_raw_delta_rescue_publication_t *rescue);

void anomaly_result_publish_thermal_debug_target_movement_diagnostics(
        anomaly_result_t                                                             *result_out,
        const anomaly_result_thermal_debug_target_movement_diagnostics_publication_t *movement);

void anomaly_result_publish_thermal_debug_target_local_peak_movement(
        anomaly_result_t                                                           *result_out,
        const anomaly_result_thermal_debug_target_local_peak_movement_publication_t *movement);

void anomaly_result_publish_thermal_debug_target_rescue_movement_flags(
        anomaly_result_t                                                                  *result_out,
        const anomaly_result_thermal_debug_target_rescue_movement_flags_publication_t     *flags);

void anomaly_result_publish_thermal_debug_target_movement_shadow_rescue(
        anomaly_result_t                                                                  *result_out,
        const anomaly_result_thermal_debug_target_movement_shadow_rescue_publication_t    *shadow_rescue);

void anomaly_result_publish_thermal_debug_target_track_match(
        anomaly_result_t                                                            *result_out,
        const anomaly_result_thermal_debug_target_track_match_publication_t         *track_match);

void anomaly_result_publish_thermal_debug_target_stage(
        anomaly_result_t                                                   *result_out,
        const anomaly_result_thermal_debug_target_stage_publication_t      *stage);

void anomaly_result_publish_thermal_debug_candidates_base(
        anomaly_result_t                                                *result_out,
        const anomaly_result_thermal_debug_candidates_base_publication_t *candidates);

bool anomaly_result_copy_thermal_debug_candidate(
        const anomaly_result_t              *result_out,
        int                                  candidate_index,
        anomaly_debug_thermal_candidate_t   *candidate_out);

void anomaly_result_publish_thermal_debug_candidates_movement(
        anomaly_result_t                                                    *result_out,
        const anomaly_result_thermal_debug_candidates_movement_publication_t *candidates);

void anomaly_result_publish_thermal_debug_candidates_nearest_track(
        anomaly_result_t                                                         *result_out,
        const anomaly_result_thermal_debug_candidates_nearest_track_publication_t *candidates);

void anomaly_result_publish_thermal_debug_candidates_near_debug(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_thermal_debug_candidates_near_debug_publication_t *candidates);

void anomaly_result_publish_thermal_debug_candidates_raw_delta_rescue(
        anomaly_result_t                                                             *result_out,
        const anomaly_result_thermal_debug_candidates_raw_delta_rescue_publication_t *candidates);

void anomaly_result_publish_thermal_debug_candidates_final_flags(
        anomaly_result_t                                                     *result_out,
        const anomaly_result_thermal_debug_candidates_final_flags_publication_t *candidates);

void anomaly_result_publish_color_debug_summary(
        anomaly_result_t                                       *result_out,
        const anomaly_result_color_debug_summary_publication_t *debug);

void anomaly_result_publish_color_debug_target_base(
        anomaly_result_t                                            *result_out,
        const anomaly_result_color_debug_target_base_publication_t  *target);

void anomaly_result_publish_color_debug_target_component_trace(
        anomaly_result_t                                                       *result_out,
        const anomaly_result_color_debug_target_component_trace_publication_t  *trace);

void anomaly_result_publish_color_debug_target_component_bbox(
        anomaly_result_t                                                      *result_out,
        const anomaly_result_color_debug_target_component_bbox_publication_t  *bbox);

void anomaly_result_publish_color_debug_target_candidate_indices(
        anomaly_result_t                                                        *result_out,
        const anomaly_result_color_debug_target_candidate_indices_publication_t *indices);

void anomaly_result_publish_color_debug_target_gate_stage(
        anomaly_result_t                                                  *result_out,
        const anomaly_result_color_debug_target_gate_stage_publication_t  *gate_stage);

void anomaly_result_publish_color_debug_target_matched_candidate(
        anomaly_result_t                                                         *result_out,
        const anomaly_result_color_debug_target_matched_candidate_publication_t  *matched);

void anomaly_result_publish_color_debug_candidates(
        anomaly_result_t                                        *result_out,
        const anomaly_result_color_debug_candidates_publication_t *candidates);

void anomaly_result_publish_boxes(
        anomaly_result_t    *result_out,
        const anomaly_box_t *boxes,
        int                  box_count);

#endif // ANOMALY_RESULT_BUILDER_H

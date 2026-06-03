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

typedef struct {
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

void anomaly_result_publish_boxes(
        anomaly_result_t    *result_out,
        const anomaly_box_t *boxes,
        int                  box_count);

#endif // ANOMALY_RESULT_BUILDER_H

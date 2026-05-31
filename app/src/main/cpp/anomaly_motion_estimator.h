// Internal MotionEstimator contract sketch.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#include "anomaly_appearance_candidates.h"
#include "anomaly_detector_internal.h"

typedef struct anomaly_registration_model_t anomaly_motion_estimator_registration_t;

typedef bool (*anomaly_motion_estimator_project_cell_fn)(
        const anomaly_motion_estimator_registration_t *registration,
        int width,
        int height,
        int motion_step,
        int motion_w,
        int motion_h,
        int mx,
        int my,
        int *px_idx_out,
        int *py_idx_out);

typedef bool (*anomaly_motion_estimator_find_residual_displacement_fn)(
        const uint8_t *curr_luma,
        const uint8_t *prev_luma,
        int motion_w,
        int motion_h,
        int mx,
        int my,
        int pred_x,
        int pred_y,
        int patch_half,
        int search_radius,
        int *best_dx_out,
        int *best_dy_out,
        int *best_sad_out);

typedef bool (*anomaly_motion_estimator_registration_valid_fn)(
        const anomaly_motion_estimator_registration_t *registration);

bool anomaly_motion_estimator_find_residual_displacement(
        const uint8_t *curr_luma,
        const uint8_t *prev_luma,
        int            motion_w,
        int            motion_h,
        int            mx,
        int            my,
        int            pred_x,
        int            pred_y,
        int            patch_half,
        int            search_radius,
        int           *best_dx_out,
        int           *best_dy_out,
        int           *best_sad_out);

float anomaly_motion_estimator_texture_scale(int texture_score);

float anomaly_motion_estimator_structure_scale(
        const uint8_t *luma,
        int            w,
        int            h,
        int            x,
        int            y);

typedef struct {
    anomaly_motion_estimator_project_cell_fn project_cell;
    anomaly_motion_estimator_find_residual_displacement_fn find_residual_displacement;
    anomaly_motion_estimator_registration_valid_fn registration_valid;
} anomaly_motion_estimator_sidecar_ops_t;

typedef struct {
    const anomaly_config_t                         *cfg;
    const anomaly_motion_estimator_registration_t  *registration;
    const uint8_t                                  *curr_luma;
    const uint8_t                                  *prev_luma;
    int                                             motion_w;
    int                                             motion_h;
    int                                             motion_step;
    int                                             width;
    int                                             height;
    int                                             roi_x0;
    int                                             roi_x1;
    int                                             roi_y0;
    int                                             roi_y1;
    const anomaly_motion_estimator_sidecar_ops_t   *ops;
} anomaly_motion_estimator_sidecar_input_t;

void anomaly_motion_estimator_estimate_sidecar(
        const anomaly_motion_estimator_sidecar_input_t *input,
        anomaly_debug_movement_t                      *movement_out);

typedef enum {
    ANOMALY_MOTION_TILE_UNKNOWN = 0,
    ANOMALY_MOTION_TILE_BACKGROUND = 1,
    ANOMALY_MOTION_TILE_PARALLAX = 2,
    ANOMALY_MOTION_TILE_LOCAL_OUTLIER = 3,
    ANOMALY_MOTION_TILE_UNSTABLE = 4,
} anomaly_motion_tile_class_t;

// Registration remains a separate producer for now. The first MotionEstimator
// extraction should consume this kind of registration-backed snapshot instead
// of owning registration solving itself.
typedef struct {
    bool     valid;
    int      registration_mode;
    int      registration_health;
    float    affine_curr_to_prev[6];
    float    similarity_a;
    float    similarity_b;
    float    similarity_tx;
    float    similarity_ty;
    float    mean_residual_px;
    float    confidence;
} anomaly_motion_registration_snapshot_t;

typedef struct {
    int sample_step;
    int motion_step;
    int roi_x0;
    int roi_y0;
    int roi_x1;
    int roi_y1;
} anomaly_motion_estimator_config_t;

typedef struct {
    const anomaly_frame_input_t *frame;
    const anomaly_motion_registration_snapshot_t *registration;
    const anomaly_motion_estimator_config_t *config;
} anomaly_motion_estimator_input_t;

// Phase 1 producer output: transitional backing-store view over the current
// registration-backed movement sidecar.
typedef struct {
    bool                            valid;
    const anomaly_debug_movement_t *movement;
    int                             sample_count;
    int                             tile_cols;
    int                             tile_rows;
    float                           confidence;
    float                           parallax_load;
    float                           local_outlier_load;
    float                           suppression_scale;
} anomaly_motion_movement_snapshot_t;

anomaly_motion_movement_snapshot_t anomaly_motion_estimator_make_movement_snapshot(
        const anomaly_debug_movement_t *movement);

bool anomaly_motion_estimator_query_snapshot_at_norm(
        const anomaly_motion_movement_snapshot_t *snapshot,
        float                                    x_norm,
        float                                    y_norm,
        anomaly_debug_movement_tile_t           *tile_out);

float anomaly_motion_estimator_tile_independent_score(
        const anomaly_debug_movement_tile_t *tile);

bool anomaly_motion_estimator_tile_is_parallax_like(
        const anomaly_debug_movement_tile_t *tile);

bool anomaly_motion_estimator_tile_is_independent(
        const anomaly_debug_movement_tile_t *tile,
        float                                independent_score);

float anomaly_motion_estimator_nearest_candidate_support_norm(
        const float *support,
        const int   *support_x,
        const int   *support_y,
        int          count,
        int          frame_w,
        int          frame_h,
        float        x_norm,
        float        y_norm,
        float        max_dist_norm);

void anomaly_motion_estimator_stamp_support(
        float *saliency_motion_map,
        float *saliency_registration_map,
        int    sg_w,
        int    sg_h,
        int    sg_x,
        int    sg_y,
        float  support,
        float  registration_scale);

// Phase 2 is deliberately separate: appearance proposals from IR/Color may ask
// for motion scoring later, but that scorer should not be confused with the
// reusable movement sidecar above.
#define ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS 4

typedef struct {
    int   sg_x;
    int   sg_y;
    int   pixel_x;
    int   pixel_y;
    float proposal_score;
    float thermal_score;
    float color_score;
} anomaly_motion_appearance_proposal_t;

int anomaly_motion_estimator_build_appearance_proposals_from_candidates(
        const anomaly_motion_candidate_t              *candidates,
        int                                            count,
        anomaly_motion_appearance_proposal_t          *out,
        int                                            out_capacity);

typedef struct {
    bool  valid;
    float score;
    int   pixel_x;
    int   pixel_y;
    float texture_scale;
    float structure_scale;
    float support_scale;
    float registration_scale;
    float persistence_scale;
} anomaly_motion_appearance_score_t;

typedef struct {
    float *persist;
    int    persist_w;
    int    persist_h;
} anomaly_motion_appearance_scorer_state_t;

typedef struct {
    const anomaly_config_t                              *cfg;
    const anomaly_motion_estimator_registration_t       *registration;
    const uint8_t                                       *curr_luma;
    const uint8_t                                       *prev_luma;
    int                                                  width;
    int                                                  height;
    int                                                  motion_w;
    int                                                  motion_h;
    int                                                  motion_step;
    int                                                  motion_count;
    int                                                  roi_x0;
    int                                                  roi_x1;
    int                                                  roi_y0;
    int                                                  roi_y1;
    bool                                                 anomaly_detection_active;
    bool                                                 scene_discontinuity;
    bool                                                 use_motion_tolerance;
    bool                                                 use_stable_motion;
    float                                                motion_evidence_scale;
    float                                               *saliency_motion_map;
    float                                               *saliency_registration_map;
    int                                                  sg_w;
    int                                                  sg_h;
    int                                                  sample_step;
    int                                                  proposal_count;
    const anomaly_motion_appearance_proposal_t          *proposals;
    anomaly_motion_appearance_scorer_state_t            *state;
} anomaly_motion_appearance_scorer_input_t;

typedef struct {
    bool                                  valid;
    float                                 global_motion_mean;
    float                                 global_motion_std;
    float                                 global_motion_load;
    float                                 zoom_motion_scale;
    float                                 broad_motion_scale;
    int                                   score_count;
    anomaly_motion_appearance_score_t     scores[ANOMALY_MOTION_APPEARANCE_MAX_PROPOSALS];
    anomaly_motion_appearance_score_t     winner;
} anomaly_motion_appearance_scorer_output_t;

void anomaly_motion_estimator_init_appearance_scorer_output(
        anomaly_motion_appearance_scorer_output_t *out);

bool anomaly_motion_estimator_appearance_score_is_winner_eligible(
        const anomaly_motion_appearance_score_t *score);

void anomaly_motion_estimator_mirror_appearance_support_output(
        const anomaly_motion_appearance_proposal_t       *proposals,
        int                                               proposal_count,
        const float                                      *support,
        const int                                        *support_x,
        const int                                        *support_y,
        float                                             global_motion_mean,
        float                                             global_motion_std,
        float                                             global_motion_load,
        float                                             zoom_motion_scale,
        float                                             broad_motion_scale,
        anomaly_motion_appearance_scorer_output_t        *out);

typedef struct {
    anomaly_motion_movement_snapshot_t movement_snapshot;
    const anomaly_motion_appearance_score_t *appearance_scores;
    int appearance_score_count;
} anomaly_motion_estimator_result_t;

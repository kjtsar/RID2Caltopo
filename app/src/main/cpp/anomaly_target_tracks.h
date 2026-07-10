// Internal target-track slot bookkeeping helpers.
#pragma once

#include "anomaly_analysis.h"
#include "anomaly_color_detector.h"
#include "anomaly_target_observations.h"

#include <stdbool.h>

// Retain exact shadow color history through the app's maximum adaptive stride.
#define ANOMALY_COLOR_SHADOW_MAX_AGE_FRAMES 60

typedef bool (*anomaly_target_tracks_registration_valid_fn)(const void *registration);
typedef bool (*anomaly_target_tracks_registration_invert_fn)(
        const void *registration,
        float x,
        float y,
        float *out_x,
        float *out_y);

typedef struct {
    const void                                      *registration;
    anomaly_registration_health_t                   health;
    float                                           quality;
    bool                                            scene_discontinuity;
    anomaly_target_tracks_registration_valid_fn     valid;
    anomaly_target_tracks_registration_invert_fn    invert_point;
    int                                             frame_width;
    int                                             frame_height;
} anomaly_target_tracks_registration_prediction_t;

typedef struct {
    bool                                  valid;
    bool                                  fresh_color_observation;
    float                                 center_x_norm;
    float                                 center_y_norm;
    const anomaly_color_blob_signature_t *signature;
} anomaly_color_shadow_candidate_t;

typedef struct {
    int   track_index;
    int   track_id;
    bool  temporal_valid;
    float temporal_consistency;
} anomaly_color_shadow_match_t;

void anomaly_target_tracks_clear_track(anomaly_target_track_t *track);

void anomaly_target_tracks_clear_all(anomaly_state_t *state);

int anomaly_target_tracks_find_best_observation_match(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        const bool                         *matched_tracks);

int anomaly_target_tracks_allocate_slot(anomaly_state_t *state);

bool anomaly_target_tracks_update_from_observations(
        anomaly_state_t                    *state,
        const anomaly_target_observation_t *observations,
        int                                 observation_count,
        anomaly_registration_health_t       registration_health,
        float                               registration_quality);

void anomaly_target_tracks_predict_with_registration(
        anomaly_state_t                                      *state,
        const anomaly_target_tracks_registration_prediction_t *prediction);

void anomaly_target_tracks_evaluate_color_shadow_candidates(
        const anomaly_state_t                   *state,
        anomaly_registration_health_t            registration_health,
        const anomaly_color_shadow_candidate_t  *candidates,
        int                                      candidate_count,
        anomaly_color_shadow_match_t            *matches_out);

void anomaly_target_tracks_commit_color_shadow_candidates(
        anomaly_state_t                         *state,
        anomaly_registration_health_t            registration_health,
        const anomaly_color_shadow_candidate_t  *candidates,
        const anomaly_color_shadow_match_t      *matches,
        int                                      candidate_count);

void anomaly_target_tracks_decay_movement_evidence(anomaly_target_track_t *track);

void anomaly_target_tracks_update_movement_evidence(
        anomaly_state_t          *state,
        anomaly_debug_movement_t *movement);

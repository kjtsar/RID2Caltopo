// Internal target-track slot bookkeeping helpers.
#pragma once

#include "anomaly_analysis.h"
#include "anomaly_target_observations.h"

#include <stdbool.h>

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
} anomaly_target_tracks_registration_prediction_t;

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

void anomaly_target_tracks_decay_movement_evidence(anomaly_target_track_t *track);

void anomaly_target_tracks_update_movement_evidence(
        anomaly_state_t          *state,
        anomaly_debug_movement_t *movement);

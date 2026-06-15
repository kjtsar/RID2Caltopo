#ifndef ANOMALY_TARGET_OBSERVATIONS_H
#define ANOMALY_TARGET_OBSERVATIONS_H

#include <stdbool.h>

typedef struct {
    bool  valid;
    bool  publish_confirming;
    int   algorithm;
    float center_x_norm;
    float center_y_norm;
    float half_w_norm;
    float half_h_norm;
    float support_radius_norm;
    float confidence;
} anomaly_target_observation_t;

bool anomaly_target_observation_populate_color_candidate(
        int                           roi_x0,
        int                           roi_y0,
        int                           sample_step,
        int                           min_x,
        int                           min_y,
        int                           max_x,
        int                           max_y,
        int                           pixel_x,
        int                           pixel_y,
        float                         candidate_score,
        float                         candidate_quality,
        float                         candidate_isolation,
        float                         score_threshold,
        float                         fw,
        float                         fh,
        int                           algorithm,
        anomaly_target_observation_t *obs_out);

bool anomaly_target_observation_populate_thermal_candidate(
        int                           roi_x0,
        int                           roi_y0,
        int                           sample_step,
        int                           min_x,
        int                           min_y,
        int                           max_x,
        int                           max_y,
        int                           pixel_x,
        int                           pixel_y,
        float                         candidate_score,
        float                         candidate_quality,
        float                         candidate_isolation,
        float                         candidate_patch_support,
        float                         candidate_motion_support,
        float                         score_threshold,
        float                         fw,
        float                         fh,
        anomaly_target_observation_t *obs_out);

bool anomaly_target_observation_near_existing(
        const anomaly_target_observation_t *observations,
        int                                 observation_count,
        const anomaly_target_observation_t *candidate);

bool anomaly_target_observation_replace_thermal_correction(
        anomaly_target_observation_t       *observations,
        int                                 observation_count,
        const anomaly_target_observation_t *candidate);

#endif

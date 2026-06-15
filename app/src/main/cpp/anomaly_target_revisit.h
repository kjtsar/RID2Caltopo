// Pure target-revisit policy helpers shared by anomaly detector modules.
#pragma once

#include "anomaly_analysis.h"

#include <stdbool.h>

#define ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN 0.20f
#define ANOMALY_TARGET_PROVISIONAL_REVISIT_SCALE 2.35f
#define ANOMALY_TARGET_REVISIT_MAX_RADIUS 0.085f

int anomaly_target_revisit_track_count(const anomaly_state_t *state);

void anomaly_target_revisit_adaptive_track_risk(
        const anomaly_state_t *state,
        int                    min_hits,
        bool                  *has_track_risk_out,
        bool                  *has_weak_lock_out);

float anomaly_target_revisit_radius_for_track(
        const anomaly_target_track_t *track,
        int                           min_hits);

void anomaly_target_revisit_annotate_roi_cells(
        anomaly_roi_state_t   *roi_state,
        const anomaly_state_t *state,
        int                    min_hits);

bool anomaly_target_revisit_point_inside_gate(
        const anomaly_state_t *state,
        float                  x_norm,
        float                  y_norm,
        int                    min_hits,
        int                   *track_index_out,
        float                 *gate_radius_out);

bool anomaly_target_revisit_should_apply_global_motion_penalty(
        const anomaly_target_track_t         *track,
        const anomaly_debug_movement_tile_t  *tile,
        float                                 parallax_load,
        float                                 score,
        float                                 score_threshold);

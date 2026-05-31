#include "anomaly_target_revisit.h"

#include "anomaly_analysis_internal.h"
#include "anomaly_scan_planner.h"

#include <math.h>

int anomaly_target_revisit_track_count(const anomaly_state_t *state) {
    if (state == NULL) return 0;
    int count = 0;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        const anomaly_target_track_t *track = &state->target_tracks[i];
        if (!track->active) continue;
        if (track->forced_revisit ||
            track->miss_count > 0 ||
            track->confidence >= ANOMALY_TARGET_REVISIT_CONFIDENCE_MIN) {
            count++;
        }
    }
    return count;
}

void anomaly_target_revisit_adaptive_track_risk(
        const anomaly_state_t *state,
        int                    min_hits,
        bool                  *has_track_risk_out,
        bool                  *has_weak_lock_out) {
    bool has_track_risk = false;
    bool has_weak_lock = false;
    if (state != NULL) {
        int required_hits = min_hits > 1 ? min_hits : 1;
        for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
            const anomaly_target_track_t *track = &state->target_tracks[i];
            if (!track->active) continue;
            if (!track->publish_confirmed ||
                track->hit_count < required_hits ||
                track->forced_revisit ||
                track->miss_count > 0) {
                has_track_risk = true;
            }
            if (track->confidence < 0.55f ||
                track->miss_count > 0 ||
                track->movement_parallax_frames > 0 ||
                (track->movement_window_frames > 0 &&
                 track->movement_valid_frames < track->movement_window_frames / 2)) {
                has_weak_lock = true;
            }
        }
    }
    if (has_track_risk_out != NULL) *has_track_risk_out = has_track_risk;
    if (has_weak_lock_out != NULL) *has_weak_lock_out = has_weak_lock;
}

float anomaly_target_revisit_radius_for_track(
        const anomaly_target_track_t *track,
        int                           min_hits) {
    if (track == NULL) return 0.0f;
    float revisit_radius = track->support_radius_norm;
    if (revisit_radius < track->half_w_norm) revisit_radius = track->half_w_norm;
    if (revisit_radius < track->half_h_norm) revisit_radius = track->half_h_norm;
    if (revisit_radius < 0.01f) revisit_radius = 0.01f;
    if (min_hits < 1) min_hits = 1;
    if (track->hit_count < min_hits) {
        revisit_radius *= ANOMALY_TARGET_PROVISIONAL_REVISIT_SCALE;
    } else if (track->miss_count > 0) {
        revisit_radius *= 1.45f;
    }
    return clampf(revisit_radius, 0.012f, ANOMALY_TARGET_REVISIT_MAX_RADIUS);
}

void anomaly_target_revisit_annotate_roi_cells(
        anomaly_roi_state_t   *roi_state,
        const anomaly_state_t *state,
        int                    min_hits) {
    if (roi_state == NULL || state == NULL || !roi_state->valid ||
        roi_state->cell_summaries == NULL ||
        roi_state->cell_cols <= 0 || roi_state->cell_rows <= 0) {
        return;
    }
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->forced_revisit) continue;
        float revisit_radius = anomaly_target_revisit_radius_for_track(track, min_hits);
        float x0_norm = clamp01f(track->center_x_norm - revisit_radius);
        float x1_norm = clamp01f(track->center_x_norm + revisit_radius);
        float y0_norm = clamp01f(track->center_y_norm - revisit_radius);
        float y1_norm = clamp01f(track->center_y_norm + revisit_radius);
        int cell_x0 = clamp_i32((int)floorf(x0_norm * (float)roi_state->cell_cols),
                                0, roi_state->cell_cols - 1);
        int cell_x1 = clamp_i32((int)floorf(x1_norm * (float)roi_state->cell_cols),
                                0, roi_state->cell_cols - 1);
        int cell_y0 = clamp_i32((int)floorf(y0_norm * (float)roi_state->cell_rows),
                                0, roi_state->cell_rows - 1);
        int cell_y1 = clamp_i32((int)floorf(y1_norm * (float)roi_state->cell_rows),
                                0, roi_state->cell_rows - 1);
        for (int cell_y = cell_y0; cell_y <= cell_y1; cell_y++) {
            for (int cell_x = cell_x0; cell_x <= cell_x1; cell_x++) {
                size_t cell_idx = (size_t)cell_y * (size_t)roi_state->cell_cols + (size_t)cell_x;
                roi_state->cell_summaries[cell_idx].scan_flags |= ANOMALY_SCAN_FLAG_TARGET_REVISIT;
            }
        }
    }
}

bool anomaly_target_revisit_point_inside_gate(
        const anomaly_state_t *state,
        float                  x_norm,
        float                  y_norm,
        int                    min_hits,
        int                   *track_index_out,
        float                 *gate_radius_out) {
    if (track_index_out != NULL) *track_index_out = -1;
    if (gate_radius_out != NULL) *gate_radius_out = 0.0f;
    if (state == NULL || x_norm < 0.0f || y_norm < 0.0f) return false;
    bool matched = false;
    float best_dist = 0.0f;
    int best_idx = -1;
    float best_radius = 0.0f;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->forced_revisit) continue;
        float radius = anomaly_target_revisit_radius_for_track(track, min_hits);
        float dx = x_norm - track->center_x_norm;
        float dy = y_norm - track->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        if (dist > radius) continue;
        if (!matched || dist < best_dist) {
            matched = true;
            best_dist = dist;
            best_idx = ti;
            best_radius = radius;
        }
    }
    if (track_index_out != NULL) *track_index_out = best_idx;
    if (gate_radius_out != NULL) *gate_radius_out = best_radius;
    return matched;
}

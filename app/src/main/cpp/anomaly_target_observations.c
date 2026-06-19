#include "anomaly_target_observations.h"

#include "anomaly_analysis.h"
#include "anomaly_analysis_internal.h"
#include "anomaly_target_matching.h"

#include <math.h>
#include <string.h>

#define ANOMALY_TARGET_OBSERVATION_CANDIDATE_SCORE_SLACK 0.75f

static void target_observation_candidate_bbox_norm(
        int    roi_x0,
        int    roi_y0,
        int    sample_step,
        int    min_x,
        int    min_y,
        int    max_x,
        int    max_y,
        float  fw,
        float  fh,
        float *left_out,
        float *top_out,
        float *right_out,
        float *bottom_out) {
    if (left_out != NULL) *left_out = 0.0f;
    if (top_out != NULL) *top_out = 0.0f;
    if (right_out != NULL) *right_out = 0.0f;
    if (bottom_out != NULL) *bottom_out = 0.0f;
    if (max_x < min_x || max_y < min_y) return;

    int expand_px = sample_step > 1 ? (sample_step / 2) : 1;
    float left = ((float)(roi_x0 + min_x * sample_step - expand_px)) / fw;
    float top = ((float)(roi_y0 + min_y * sample_step - expand_px)) / fh;
    float right = ((float)(roi_x0 + (max_x + 1) * sample_step + expand_px)) / fw;
    float bottom = ((float)(roi_y0 + (max_y + 1) * sample_step + expand_px)) / fh;
    if (left_out != NULL) *left_out = clampf(left, 0.0f, 1.0f);
    if (top_out != NULL) *top_out = clampf(top, 0.0f, 1.0f);
    if (right_out != NULL) *right_out = clampf(right, 0.0f, 1.0f);
    if (bottom_out != NULL) *bottom_out = clampf(bottom, 0.0f, 1.0f);
}

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
        anomaly_target_observation_t *obs_out) {
    if (obs_out == NULL || fw <= 0.0f || fh <= 0.0f ||
        max_x < min_x || max_y < min_y) {
        return false;
    }

    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    target_observation_candidate_bbox_norm(
            roi_x0,
            roi_y0,
            sample_step,
            min_x,
            min_y,
            max_x,
            max_y,
            fw,
            fh,
            &left,
            &top,
            &right,
            &bottom);
    if (right <= left || bottom <= top) return false;

    memset(obs_out, 0, sizeof(*obs_out));
    obs_out->valid = true;
    obs_out->publish_confirming = true;
    obs_out->algorithm = algorithm;
    obs_out->center_x_norm = clamp01f((float)pixel_x / fw);
    obs_out->center_y_norm = clamp01f((float)pixel_y / fh);
    obs_out->half_w_norm = clampf((right - left) * 0.5f, 0.006f, 0.12f);
    obs_out->half_h_norm = clampf((bottom - top) * 0.5f, 0.006f, 0.12f);
    float support_radius = fmaxf(obs_out->half_w_norm, obs_out->half_h_norm) * 1.55f;
    if (support_radius < 0.012f) support_radius = 0.012f;
    obs_out->support_radius_norm = clampf(support_radius, 0.012f, 0.16f);
    float score_excess = candidate_score - score_threshold;
    if (!isfinite(score_excess)) score_excess = 0.0f;
    obs_out->confidence = clampf(
            0.38f +
            0.18f * clamp01f(candidate_quality) +
            0.16f * clamp01f(candidate_isolation) +
            0.10f * clampf(score_excess / 1.6f, 0.0f, 1.0f),
            0.32f,
            0.96f);
    return true;
}

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
        anomaly_target_observation_t *obs_out) {
    if (obs_out == NULL || fw <= 0.0f || fh <= 0.0f ||
        max_x < min_x || max_y < min_y) {
        return false;
    }

    float left = 0.0f;
    float top = 0.0f;
    float right = 0.0f;
    float bottom = 0.0f;
    target_observation_candidate_bbox_norm(
            roi_x0,
            roi_y0,
            sample_step,
            min_x,
            min_y,
            max_x,
            max_y,
            fw,
            fh,
            &left,
            &top,
            &right,
            &bottom);
    if (right <= left || bottom <= top) return false;

    memset(obs_out, 0, sizeof(*obs_out));
    obs_out->valid = true;
    obs_out->publish_confirming = false;
    obs_out->algorithm = ANOMALY_ALGO_THERMAL;
    obs_out->center_x_norm = clamp01f((float)pixel_x / fw);
    obs_out->center_y_norm = clamp01f((float)pixel_y / fh);
    obs_out->half_w_norm = clampf((right - left) * 0.5f, 0.005f, 0.10f);
    obs_out->half_h_norm = clampf((bottom - top) * 0.5f, 0.005f, 0.10f);
    float support_radius = fmaxf(obs_out->half_w_norm, obs_out->half_h_norm) * 1.75f;
    obs_out->support_radius_norm = clampf(support_radius, 0.012f, 0.14f);
    float score_rank = clampf(
            (candidate_score - score_threshold + ANOMALY_TARGET_OBSERVATION_CANDIDATE_SCORE_SLACK) /
            (ANOMALY_TARGET_OBSERVATION_CANDIDATE_SCORE_SLACK + 1.40f),
            0.0f,
            1.0f);
    obs_out->confidence = clampf(
            0.20f +
            0.20f * score_rank +
            0.18f * clamp01f(candidate_quality) +
            0.14f * clamp01f(candidate_isolation) +
            0.08f * clamp01f(candidate_patch_support) +
            0.08f * clamp01f(candidate_motion_support),
            0.18f,
            0.82f);
    return true;
}

bool anomaly_target_observation_near_existing(
        const anomaly_target_observation_t *observations,
        int                                 observation_count,
        const anomaly_target_observation_t *candidate) {
    if (observations == NULL || candidate == NULL || !candidate->valid) return false;
    for (int oi = 0; oi < observation_count; oi++) {
        const anomaly_target_observation_t *obs = &observations[oi];
        if (!obs->valid) continue;
        float dx = obs->center_x_norm - candidate->center_x_norm;
        float dy = obs->center_y_norm - candidate->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        float gate = fmaxf(0.022f, fmaxf(obs->support_radius_norm, candidate->support_radius_norm) * 1.20f);
        if (dist <= gate) return true;
    }
    return false;
}

bool anomaly_target_observation_replace_thermal_correction(
        anomaly_target_observation_t       *observations,
        int                                 observation_count,
        const anomaly_target_observation_t *candidate) {
    if (observations == NULL ||
        candidate == NULL ||
        !candidate->valid ||
        candidate->algorithm != ANOMALY_ALGO_THERMAL) {
        return false;
    }

    for (int oi = 0; oi < observation_count; oi++) {
        anomaly_target_observation_t *obs = &observations[oi];
        if (!obs->valid || obs->algorithm != ANOMALY_ALGO_THERMAL) continue;
        float dx = obs->center_x_norm - candidate->center_x_norm;
        float dy = obs->center_y_norm - candidate->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        float support = fmaxf(obs->support_radius_norm, candidate->support_radius_norm);
        float duplicate_gate = fmaxf(0.022f, support * 0.55f);
        float correction_gate = fmaxf(0.022f, support * 1.20f);
        if (dist > duplicate_gate && dist <= correction_gate) {
            *obs = *candidate;
            return true;
        }
    }

    return false;
}

static bool target_observation_track_can_support(
        const anomaly_target_track_t       *track,
        const anomaly_target_observation_t *obs) {
    if (track == NULL || obs == NULL || !track->active || !obs->valid) return false;
    return track->algorithm == obs->algorithm || track->algorithm == ANOMALY_ALGO_PERSIST;
}

static int target_observation_find_best_track_support_match(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        float                              *dist_out,
        float                              *gate_out) {
    if (dist_out != NULL) *dist_out = 0.0f;
    if (gate_out != NULL) *gate_out = 0.0f;
    if (state == NULL || obs == NULL || !obs->valid) return -1;

    int best_idx = -1;
    float best_dist = 0.0f;
    float best_gate = 0.0f;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!target_observation_track_can_support(track, obs)) continue;
        float dx = obs->center_x_norm - track->center_x_norm;
        float dy = obs->center_y_norm - track->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        float gate = ANOMALY_TARGET_MATCH_GATE +
                     0.25f * fmaxf(track->support_radius_norm, obs->support_radius_norm);
        if (dist > gate) continue;
        if (best_idx < 0 || dist < best_dist) {
            best_idx = ti;
            best_dist = dist;
            best_gate = gate;
        }
    }

    if (best_idx >= 0) {
        if (dist_out != NULL) *dist_out = best_dist;
        if (gate_out != NULL) *gate_out = best_gate;
    }
    return best_idx;
}

float anomaly_target_observation_score_track_support_bonus(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        float                               registration_quality,
        float                               local_motion_support) {
    if (state == NULL || obs == NULL || !obs->valid) return 0.0f;
    if (registration_quality < 0.55f) return 0.0f;

    float dist = 0.0f;
    float gate = 0.0f;
    int track_idx =
        target_observation_find_best_track_support_match(state, obs, &dist, &gate);
    if (track_idx < 0 || gate <= 0.0f) return 0.0f;

    const anomaly_target_track_t *track = &state->target_tracks[track_idx];
    float track_lock = fminf(registration_quality, track->last_registration_quality);
    if (track_lock < 0.55f) return 0.0f;

    float closeness = clamp01f(1.0f - (dist / gate));
    float lock_factor = clampf((track_lock - 0.55f) / 0.45f, 0.0f, 1.0f);
    float base_bonus =
        (0.16f + 0.34f * closeness) *
        clamp01f(track->confidence) *
        lock_factor;

    float support_radius =
        fmaxf(track->support_radius_norm, fmaxf(obs->support_radius_norm, 0.01f));
    float disagreement_bonus = 0.0f;
    if (dist > support_radius * 0.18f && dist < gate * 0.95f) {
        float relative_offset =
            clampf((dist / support_radius) - 0.18f, 0.0f, 1.0f);
        disagreement_bonus =
            0.20f *
            relative_offset *
            clamp01f(local_motion_support) *
            lock_factor;
    }

    return base_bonus + disagreement_bonus;
}

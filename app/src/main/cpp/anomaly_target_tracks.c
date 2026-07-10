#include "anomaly_target_tracks.h"

#include "anomaly_analysis_internal.h"
#include "anomaly_motion_estimator.h"
#include "anomaly_target_matching.h"
#include "anomaly_target_revisit.h"

#include <math.h>
#include <limits.h>
#include <string.h>

#define ANOMALY_TARGET_MAX_CARRIED_MISSES ANOMALY_ACC_HOLD_FRAMES
#define ANOMALY_TARGET_MAX_UNSUPPORTED_THERMAL_REVISIT_MISSES 1
#define ANOMALY_TARGET_MAX_SUPPORTED_THERMAL_REVISIT_MISSES 2
#define ANOMALY_TARGET_MIN_REVISIT_THERMAL_RESIDUAL_PX 8.0f
#define ANOMALY_TARGET_CONFIDENCE_HIT_GAIN 0.22f
#define ANOMALY_TARGET_CONFIDENCE_ME_PERSISTENCE_GAIN 0.16f
#define ANOMALY_TARGET_CONFIDENCE_MISS_DECAY 0.22f
#define ANOMALY_TARGET_CONFIDENCE_POSITIONAL_MISS_DECAY 0.04f
#define ANOMALY_TARGET_CONFIRMED_COLOR_LOCK_GATE 0.040f
#define ANOMALY_COLOR_SHADOW_MATCH_GATE 0.040f
#define ANOMALY_COLOR_SHADOW_HISTORY_MASS 65535u

static void anomaly_target_tracks_invalidate_color_shadow_signature(
        anomaly_target_track_t *track) {
    if (track == NULL) return;
    track->shadow_color_signature_valid = false;
    track->shadow_color_signature_age = 0;
    track->shadow_color_signature_sample_count = 0u;
    memset(track->shadow_color_signature_histogram,
           0,
           sizeof(track->shadow_color_signature_histogram));
}

void anomaly_target_tracks_clear_track(anomaly_target_track_t *track) {
    if (track == NULL) return;
    memset(track, 0, sizeof(*track));
}

void anomaly_target_tracks_clear_all(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int i = 0; i < ANOMALY_MAX_TARGET_TRACKS; i++) {
        anomaly_target_tracks_clear_track(&state->target_tracks[i]);
    }
    state->next_target_track_id = 1;
}

int anomaly_target_tracks_find_best_observation_match(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        const bool                         *matched_tracks) {
    if (state == NULL || obs == NULL || !obs->valid) return -1;
    float best_dist = ANOMALY_TARGET_MATCH_GATE;
    int best_idx = -1;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || (matched_tracks != NULL && matched_tracks[ti])) continue;
        float dx = obs->center_x_norm - track->center_x_norm;
        float dy = obs->center_y_norm - track->center_y_norm;
        float dist = sqrtf(dx * dx + dy * dy);
        float gate = ANOMALY_TARGET_MATCH_GATE + 0.25f * track->support_radius_norm;
        if (dist > gate) continue;
        if (obs->algorithm != track->algorithm && dist > best_dist * 0.65f) continue;
        if (best_idx < 0 || dist < best_dist) {
            best_idx = ti;
            best_dist = dist;
        }
    }
    return best_idx;
}

int anomaly_target_tracks_allocate_slot(anomaly_state_t *state) {
    if (state == NULL) return -1;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        if (!state->target_tracks[ti].active) return ti;
    }
    int weakest_idx = 0;
    float weakest_score = state->target_tracks[0].confidence;
    for (int ti = 1; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        float score = state->target_tracks[ti].confidence - 0.10f * (float)state->target_tracks[ti].hit_count;
        if (score < weakest_score) {
            weakest_score = score;
            weakest_idx = ti;
        }
    }
    return weakest_idx;
}

static bool anomaly_target_tracks_should_hold_confirmed_color_lock(
        const anomaly_target_track_t       *track,
        const anomaly_target_observation_t *obs) {
    if (track == NULL || obs == NULL) {
        return false;
    }
    if (!track->active ||
        !track->publish_confirmed ||
        track->algorithm != ANOMALY_ALGO_COLOR ||
        obs->algorithm != ANOMALY_ALGO_COLOR ||
        obs->publish_confirming) {
        return false;
    }
    float dx = obs->center_x_norm - track->center_x_norm;
    float dy = obs->center_y_norm - track->center_y_norm;
    float dist = sqrtf(dx * dx + dy * dy);
    float gate = ANOMALY_TARGET_CONFIRMED_COLOR_LOCK_GATE +
                 0.50f * fmaxf(track->support_radius_norm, obs->support_radius_norm);
    return dist > gate;
}

static bool anomaly_target_tracks_should_apply_local_residual(
        const anomaly_target_track_t                         *track,
        const anomaly_target_tracks_registration_prediction_t *prediction) {
    if (track == NULL || prediction == NULL) return false;
    if (!track->active ||
        (track->algorithm != ANOMALY_ALGO_THERMAL &&
         track->algorithm != ANOMALY_ALGO_COLOR)) {
        return false;
    }
    if (!track->fresh_observation) return false;
    if (prediction->frame_width <= 1 || prediction->frame_height <= 1) return false;
    if (track->movement_valid_frames < 3 || track->movement_window_frames < 3) return false;
    if (track->movement_parallax_frames * 2 < track->movement_valid_frames) return false;
    float mean_confidence =
        track->movement_confidence_sum / (float)track->movement_valid_frames;
    if (mean_confidence < 0.55f) return false;
    if (!isfinite(track->last_movement_dx_px) ||
        !isfinite(track->last_movement_dy_px) ||
        !isfinite(track->last_movement_residual_px)) {
        return false;
    }
    if (track->last_movement_residual_px < 0.5f ||
        track->last_movement_residual_px > 96.0f) {
        return false;
    }
    if (fabsf(track->last_movement_dx_px) > 64.0f ||
        fabsf(track->last_movement_dy_px) > 64.0f) {
        return false;
    }
    return true;
}

static bool anomaly_target_tracks_has_supported_thermal_revisit(
        const anomaly_target_track_t *track) {
    if (track == NULL || !track->active || track->algorithm != ANOMALY_ALGO_THERMAL) {
        return false;
    }
    if (track->movement_valid_frames < 3 || track->movement_window_frames < 3) return false;
    if (track->movement_parallax_frames * 2 < track->movement_valid_frames) return false;
    if (!isfinite(track->movement_confidence_sum) || track->movement_valid_frames <= 0) {
        return false;
    }
    float mean_confidence =
        track->movement_confidence_sum / (float)track->movement_valid_frames;
    if (mean_confidence < 0.55f) return false;
    if (!isfinite(track->last_movement_residual_px)) return false;
    return track->last_movement_residual_px >= ANOMALY_TARGET_MIN_REVISIT_THERMAL_RESIDUAL_PX;
}

static bool anomaly_target_tracks_has_positional_thermal_support(
        const anomaly_target_track_t *track) {
    if (track == NULL || !track->active || track->algorithm != ANOMALY_ALGO_THERMAL) {
        return false;
    }
    if (!track->publish_confirmed || track->hit_count < 2 || track->hold_count <= 0) {
        return false;
    }
    if (!isfinite(track->last_registration_quality) || track->last_registration_quality < 0.55f) {
        return false;
    }
    if (track->movement_valid_frames < 3 || track->movement_window_frames < 3) return false;
    if (track->movement_parallax_frames * 2 < track->movement_valid_frames) return false;
    if (!isfinite(track->movement_confidence_sum) || track->movement_valid_frames <= 0) {
        return false;
    }
    float mean_confidence =
        track->movement_confidence_sum / (float)track->movement_valid_frames;
    return mean_confidence >= 0.55f;
}

static bool anomaly_target_tracks_has_me_persistent_thermal_support(
        const anomaly_target_track_t *track) {
    if (!anomaly_target_tracks_has_supported_thermal_revisit(track)) return false;
    if (track->hit_count < 2) return false;
    if (track->movement_independent_score_sum < 1.0f &&
        track->last_movement_independent_score < 0.35f) {
        return false;
    }
    return true;
}

static bool anomaly_target_tracks_should_force_revisit_after_miss(
        const anomaly_target_track_t *track,
        anomaly_registration_health_t registration_health) {
    if (track == NULL || registration_health < ANOMALY_REG_HEALTH_SOFT_DEGRADED) {
        return false;
    }
    if (track->algorithm != ANOMALY_ALGO_THERMAL || !track->publish_confirmed) {
        return track->miss_count <= ANOMALY_TARGET_MAX_CARRIED_MISSES;
    }
    int max_thermal_misses = ANOMALY_TARGET_MAX_UNSUPPORTED_THERMAL_REVISIT_MISSES;
    if (anomaly_target_tracks_has_positional_thermal_support(track)) {
        max_thermal_misses = ANOMALY_TARGET_MAX_CARRIED_MISSES;
    } else if (anomaly_target_tracks_has_supported_thermal_revisit(track)) {
        max_thermal_misses = ANOMALY_TARGET_MAX_SUPPORTED_THERMAL_REVISIT_MISSES;
    }
    return track->miss_count <= max_thermal_misses;
}

bool anomaly_target_tracks_update_from_observations(
        anomaly_state_t                    *state,
        const anomaly_target_observation_t *observations,
        int                                 observation_count,
        anomaly_registration_health_t       registration_health,
        float                               registration_quality) {
    if (state == NULL) return false;

    bool matched_tracks[ANOMALY_MAX_TARGET_TRACKS];
    memset(matched_tracks, 0, sizeof(matched_tracks));
    bool had_any_target_tracks = state->next_target_track_id > 1;

    for (int oi = 0; oi < observation_count; oi++) {
        const anomaly_target_observation_t *obs = &observations[oi];
        if (!obs->valid) continue;
        int track_idx = anomaly_target_tracks_find_best_observation_match(state, obs, matched_tracks);
        if (track_idx < 0) {
            track_idx = anomaly_target_tracks_allocate_slot(state);
            if (track_idx < 0) continue;
            anomaly_target_tracks_clear_track(&state->target_tracks[track_idx]);
            state->target_tracks[track_idx].active = true;
            state->target_tracks[track_idx].id = state->next_target_track_id++;
            if (state->next_target_track_id <= 0) state->next_target_track_id = 1;
        }

        anomaly_target_track_t *track = &state->target_tracks[track_idx];
        if (anomaly_target_tracks_should_hold_confirmed_color_lock(track, obs)) {
            track->fresh_observation = false;
            track->forced_revisit = true;
            continue;
        }

        track->active = true;
        track->algorithm = obs->algorithm;
        track->center_x_norm = obs->center_x_norm;
        track->center_y_norm = obs->center_y_norm;
        track->half_w_norm = obs->half_w_norm;
        track->half_h_norm = obs->half_h_norm;
        track->support_radius_norm = obs->support_radius_norm;
        float hit_gain = ANOMALY_TARGET_CONFIDENCE_HIT_GAIN;
        if (obs->algorithm == ANOMALY_ALGO_THERMAL &&
            anomaly_target_tracks_has_me_persistent_thermal_support(track)) {
            hit_gain += ANOMALY_TARGET_CONFIDENCE_ME_PERSISTENCE_GAIN;
        }
        track->confidence = clampf(fmaxf(track->confidence, obs->confidence) + hit_gain,
                                   0.0f,
                                   1.0f);
        if (obs->publish_confirming) {
            track->publish_confirmed = true;
        }
        track->hit_count++;
        track->miss_count = 0;
        track->hold_count = ANOMALY_ACC_HOLD_FRAMES;
        track->last_registration_quality = registration_quality;
        track->forced_revisit = true;
        track->fresh_observation = true;
        matched_tracks[track_idx] = true;
    }

    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || matched_tracks[ti]) continue;
        track->fresh_observation = false;
        track->miss_count++;
        track->hold_count--;
        bool positional_carry = anomaly_target_tracks_has_positional_thermal_support(track);
        float miss_decay = positional_carry
                ? ANOMALY_TARGET_CONFIDENCE_POSITIONAL_MISS_DECAY
                : ANOMALY_TARGET_CONFIDENCE_MISS_DECAY;
        track->confidence = clampf(track->confidence - miss_decay, 0.0f, 1.0f);
        track->forced_revisit =
            anomaly_target_tracks_should_force_revisit_after_miss(track, registration_health);
        if (track->hold_count <= 0 ||
            track->miss_count > ANOMALY_TARGET_MAX_CARRIED_MISSES ||
            registration_health <= ANOMALY_REG_HEALTH_HARD_DEGRADED ||
            track->confidence < 0.05f) {
            anomaly_target_tracks_clear_track(track);
        }
    }
    return had_any_target_tracks &&
           observation_count == 0 &&
           anomaly_target_revisit_track_count(state) == 0;
}

void anomaly_target_tracks_predict_with_registration(
        anomaly_state_t                                      *state,
        const anomaly_target_tracks_registration_prediction_t *prediction) {
    if (state == NULL || prediction == NULL) return;
    if (prediction->health != ANOMALY_REG_HEALTH_HEALTHY) {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            anomaly_target_tracks_invalidate_color_shadow_signature(
                &state->target_tracks[ti]);
        }
    } else {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            anomaly_target_track_t *track = &state->target_tracks[ti];
            if (!track->active || track->algorithm != ANOMALY_ALGO_COLOR ||
                !track->shadow_color_signature_valid) {
                continue;
            }
            if (track->shadow_color_signature_age < INT_MAX) {
                track->shadow_color_signature_age++;
            }
            if (track->shadow_color_signature_age >
                ANOMALY_COLOR_SHADOW_MAX_AGE_FRAMES) {
                anomaly_target_tracks_invalidate_color_shadow_signature(track);
            }
        }
    }
    if (prediction->scene_discontinuity ||
        prediction->health == ANOMALY_REG_HEALTH_INVALID ||
        prediction->health == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        anomaly_target_tracks_clear_all(state);
        return;
    }
    if (prediction->registration == NULL ||
        prediction->valid == NULL ||
        prediction->invert_point == NULL ||
        !prediction->valid(prediction->registration)) {
        return;
    }

    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active) continue;
        const float before_x = track->center_x_norm;
        const float before_y = track->center_y_norm;
        track->last_prediction_valid = false;
        track->last_prediction_local_residual_applied = false;
        track->last_prediction_inverse_failed = false;
        track->last_prediction_before_x_norm = before_x;
        track->last_prediction_before_y_norm = before_y;
        track->last_prediction_registration_x_norm = before_x;
        track->last_prediction_registration_y_norm = before_y;
        track->last_prediction_after_x_norm = before_x;
        track->last_prediction_after_y_norm = before_y;
        float nx = 0.0f;
        float ny = 0.0f;
        if (!prediction->invert_point(
                    prediction->registration,
                    before_x,
                    before_y,
                    &nx,
                    &ny)) {
            track->forced_revisit = true;
            track->last_prediction_inverse_failed = true;
            continue;
        }
        track->last_prediction_valid = true;
        track->last_prediction_registration_x_norm = nx;
        track->last_prediction_registration_y_norm = ny;
        if (anomaly_target_tracks_should_apply_local_residual(track, prediction)) {
            nx -= track->last_movement_dx_px / (float)(prediction->frame_width - 1);
            ny -= track->last_movement_dy_px / (float)(prediction->frame_height - 1);
            track->last_prediction_local_residual_applied = true;
        }
        track->center_x_norm = clamp01f(nx);
        track->center_y_norm = clamp01f(ny);
        track->last_prediction_after_x_norm = track->center_x_norm;
        track->last_prediction_after_y_norm = track->center_y_norm;
        track->last_registration_quality = prediction->quality;
        if (!track->fresh_observation) {
            track->forced_revisit = true;
        }
    }
}

static void anomaly_target_tracks_reset_color_shadow_match(
        anomaly_color_shadow_match_t *match) {
    if (match == NULL) return;
    match->track_index = -1;
    match->track_id = -1;
    match->temporal_valid = false;
    match->temporal_consistency = 1.0f;
}

static bool anomaly_target_tracks_color_shadow_signature_is_fresh(
        const anomaly_target_track_t *track) {
    return track != NULL && track->shadow_color_signature_valid &&
           track->shadow_color_signature_age >= 0 &&
           track->shadow_color_signature_age <= ANOMALY_COLOR_SHADOW_MAX_AGE_FRAMES &&
           track->shadow_color_signature_sample_count > 0u;
}

void anomaly_target_tracks_evaluate_color_shadow_candidates(
        const anomaly_state_t                   *state,
        anomaly_registration_health_t            registration_health,
        const anomaly_color_shadow_candidate_t  *candidates,
        int                                      candidate_count,
        anomaly_color_shadow_match_t            *matches_out) {
    if (matches_out == NULL || candidate_count <= 0) return;
    for (int ci = 0; ci < candidate_count; ci++) {
        anomaly_target_tracks_reset_color_shadow_match(&matches_out[ci]);
    }
    if (state == NULL || candidates == NULL ||
        registration_health != ANOMALY_REG_HEALTH_HEALTHY) {
        return;
    }

    bool matched_tracks[ANOMALY_MAX_TARGET_TRACKS];
    memset(matched_tracks, 0, sizeof(matched_tracks));
    for (int ci = 0; ci < candidate_count; ci++) {
        const anomaly_color_shadow_candidate_t *candidate = &candidates[ci];
        if (!candidate->valid || candidate->signature == NULL ||
            candidate->signature->sample_count == 0u) {
            continue;
        }
        int best_idx = -1;
        float best_dist = 0.0f;
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            const anomaly_target_track_t *track = &state->target_tracks[ti];
            if (matched_tracks[ti] || !track->active ||
                track->algorithm != ANOMALY_ALGO_COLOR) {
                continue;
            }
            float dx = candidate->center_x_norm - track->center_x_norm;
            float dy = candidate->center_y_norm - track->center_y_norm;
            float dist = sqrtf(dx * dx + dy * dy);
            float gate = ANOMALY_COLOR_SHADOW_MATCH_GATE +
                         0.25f * track->support_radius_norm;
            if (dist > gate) continue;
            if (best_idx < 0 || dist < best_dist) {
                best_idx = ti;
                best_dist = dist;
            }
        }
        if (best_idx < 0) continue;

        const anomaly_target_track_t *track = &state->target_tracks[best_idx];
        anomaly_color_shadow_match_t *match = &matches_out[ci];
        matched_tracks[best_idx] = true;
        match->track_index = best_idx;
        match->track_id = track->id;
        if (anomaly_target_tracks_color_shadow_signature_is_fresh(track)) {
            anomaly_color_blob_signature_t prior_signature;
            memset(&prior_signature, 0, sizeof(prior_signature));
            memcpy(prior_signature.histogram,
                   track->shadow_color_signature_histogram,
                   sizeof(prior_signature.histogram));
            prior_signature.sample_count = track->shadow_color_signature_sample_count;
            match->temporal_valid = true;
            match->temporal_consistency = anomaly_color_signature_similarity(
                candidate->signature,
                &prior_signature);
        }
    }
}

static void anomaly_target_tracks_store_color_shadow_signature(
        anomaly_target_track_t                 *track,
        const anomaly_color_blob_signature_t  *signature) {
    if (track == NULL || signature == NULL || signature->sample_count == 0u) return;
    uint32_t normalized[ANOMALY_COLOR_HIST_BINS];
    uint32_t normalized_sum = 0u;
    for (int key = 0; key < ANOMALY_COLOR_HIST_BINS; key++) {
        uint64_t scaled = (uint64_t)signature->histogram[key] *
                          (uint64_t)ANOMALY_COLOR_SHADOW_HISTORY_MASS;
        uint32_t value = (uint32_t)((scaled + signature->sample_count / 2u) /
                                    signature->sample_count);
        normalized[key] = value;
        normalized_sum += value;
    }

    uint32_t stored_sum = 0u;
    for (int key = 0; key < ANOMALY_COLOR_HIST_BINS; key++) {
        uint32_t value = normalized[key];
        if (track->shadow_color_signature_valid) {
            value = (uint32_t)(((uint64_t)track->shadow_color_signature_histogram[key] * 3u +
                                normalized[key] + 2u) / 4u);
        }
        track->shadow_color_signature_histogram[key] = value;
        stored_sum += value;
    }
    track->shadow_color_signature_valid = stored_sum > 0u && normalized_sum > 0u;
    track->shadow_color_signature_age = 0;
    track->shadow_color_signature_sample_count = stored_sum;
}

void anomaly_target_tracks_commit_color_shadow_candidates(
        anomaly_state_t                         *state,
        anomaly_registration_health_t            registration_health,
        const anomaly_color_shadow_candidate_t  *candidates,
        const anomaly_color_shadow_match_t      *matches,
        int                                      candidate_count) {
    if (state == NULL) return;

    bool claimed_tracks[ANOMALY_MAX_TARGET_TRACKS];
    memset(claimed_tracks, 0, sizeof(claimed_tracks));
    if (registration_health == ANOMALY_REG_HEALTH_HEALTHY &&
        candidates != NULL && matches != NULL) {
        for (int ci = 0; ci < candidate_count; ci++) {
            const anomaly_color_shadow_candidate_t *candidate = &candidates[ci];
            const anomaly_color_shadow_match_t *match = &matches[ci];
            if (!candidate->valid || !candidate->fresh_color_observation ||
                candidate->signature == NULL || candidate->signature->sample_count == 0u ||
                match->track_index < 0 || match->track_index >= ANOMALY_MAX_TARGET_TRACKS) {
                continue;
            }
            anomaly_target_track_t *track = &state->target_tracks[match->track_index];
            if (!track->active || !track->fresh_observation ||
                track->algorithm != ANOMALY_ALGO_COLOR || track->id != match->track_id ||
                claimed_tracks[match->track_index]) {
                continue;
            }
            anomaly_target_tracks_store_color_shadow_signature(track, candidate->signature);
            claimed_tracks[match->track_index] = true;
        }

        // Newly allocated production tracks had no pre-update shadow match.
        // Rematch only fresh exact observations, using the same strict shadow gate.
        for (int ci = 0; ci < candidate_count; ci++) {
            const anomaly_color_shadow_candidate_t *candidate = &candidates[ci];
            if (!candidate->valid || !candidate->fresh_color_observation ||
                candidate->signature == NULL || candidate->signature->sample_count == 0u) {
                continue;
            }
            int pre_idx = matches[ci].track_index;
            if (pre_idx >= 0 && pre_idx < ANOMALY_MAX_TARGET_TRACKS &&
                claimed_tracks[pre_idx]) {
                continue;
            }
            int best_idx = -1;
            float best_dist = 0.0f;
            for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
                anomaly_target_track_t *track = &state->target_tracks[ti];
                if (claimed_tracks[ti] || !track->active || !track->fresh_observation ||
                    track->algorithm != ANOMALY_ALGO_COLOR) {
                    continue;
                }
                float dx = candidate->center_x_norm - track->center_x_norm;
                float dy = candidate->center_y_norm - track->center_y_norm;
                float dist = sqrtf(dx * dx + dy * dy);
                float gate = ANOMALY_COLOR_SHADOW_MATCH_GATE +
                             0.25f * track->support_radius_norm;
                if (dist > gate) continue;
                if (best_idx < 0 || dist < best_dist) {
                    best_idx = ti;
                    best_dist = dist;
                }
            }
            if (best_idx < 0) continue;
            anomaly_target_tracks_store_color_shadow_signature(
                &state->target_tracks[best_idx],
                candidate->signature);
            claimed_tracks[best_idx] = true;
        }
    } else if (registration_health != ANOMALY_REG_HEALTH_HEALTHY) {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            anomaly_target_tracks_invalidate_color_shadow_signature(
                &state->target_tracks[ti]);
        }
    }
}

void anomaly_target_tracks_decay_movement_evidence(anomaly_target_track_t *track) {
    if (track == NULL) return;
    if (track->movement_window_frames > 0) track->movement_window_frames--;
    if (track->movement_valid_frames > track->movement_window_frames) {
        track->movement_valid_frames = track->movement_window_frames;
    }
    if (track->movement_independent_frames > track->movement_valid_frames) {
        track->movement_independent_frames = track->movement_valid_frames;
    }
    if (track->movement_parallax_frames > track->movement_valid_frames) {
        track->movement_parallax_frames = track->movement_valid_frames;
    }
    track->movement_independent_score_sum *= 0.90f;
    track->movement_confidence_sum *= 0.90f;
    track->last_movement_independent_score = 0.0f;
}

void anomaly_target_tracks_update_movement_evidence(
        anomaly_state_t          *state,
        anomaly_debug_movement_t *movement) {
    if (state == NULL || movement == NULL) return;
    movement->aoi_query_count = 0;
    movement->aoi_valid_count = 0;
    movement->aoi_independent_count = 0;
    movement->aoi_parallax_count = 0;
    movement->aoi_unstable_count = 0;
    movement->aoi_independent_score_mean = 0.0f;
    movement->aoi_confidence_mean = 0.0f;
    if (!movement->valid) {
        for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
            if (state->target_tracks[ti].active) {
                anomaly_target_tracks_decay_movement_evidence(&state->target_tracks[ti]);
            }
        }
        return;
    }

    anomaly_motion_movement_snapshot_t movement_snapshot =
        anomaly_motion_estimator_make_movement_snapshot(movement);
    bool can_mutate_track_evidence =
        movement->mode == ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE;
    float independent_sum = 0.0f;
    float confidence_sum = 0.0f;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS; ti++) {
        anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active) continue;
        movement->aoi_query_count++;
        anomaly_debug_movement_tile_t tile;
        if (!anomaly_motion_estimator_query_snapshot_at_norm(
                &movement_snapshot,
                track->center_x_norm,
                track->center_y_norm,
                &tile)) {
            anomaly_target_tracks_decay_movement_evidence(track);
            continue;
        }
        float independent_score = anomaly_motion_estimator_tile_independent_score(&tile);
        bool independent = anomaly_motion_estimator_tile_is_independent(
                &tile,
                independent_score);
        bool parallax = anomaly_motion_estimator_tile_is_parallax_like(&tile);
        bool unstable = tile.layer_class == ANOMALY_MOVEMENT_LAYER_UNSTABLE;

        if (can_mutate_track_evidence) {
            if (track->movement_window_frames < ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_window_frames++;
            }
            if (track->movement_valid_frames < ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_valid_frames++;
            }
            if (independent && track->movement_independent_frames < ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_independent_frames++;
            } else if (!independent && track->movement_independent_frames > 0 &&
                       track->movement_window_frames >= ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_independent_frames--;
            }
            if (parallax && track->movement_parallax_frames < ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_parallax_frames++;
            } else if (!parallax && track->movement_parallax_frames > 0 &&
                       track->movement_window_frames >= ANOMALY_AOI_MOVEMENT_WINDOW_FRAMES) {
                track->movement_parallax_frames--;
            }
            track->movement_independent_score_sum =
                clampf(track->movement_independent_score_sum * 0.92f + independent_score, 0.0f, 30.0f);
            track->movement_confidence_sum =
                clampf(track->movement_confidence_sum * 0.92f + tile.confidence, 0.0f, 30.0f);
            track->last_movement_dx_px = tile.dx_px;
            track->last_movement_dy_px = tile.dy_px;
            track->last_movement_residual_px = tile.residual_px;
            track->last_movement_independent_score = independent_score;
        }

        movement->aoi_valid_count++;
        if (independent) movement->aoi_independent_count++;
        if (parallax) movement->aoi_parallax_count++;
        if (unstable) movement->aoi_unstable_count++;
        independent_sum += independent_score;
        confidence_sum += tile.confidence;
    }
    if (movement->aoi_valid_count > 0) {
        float inv = 1.0f / (float)movement->aoi_valid_count;
        movement->aoi_independent_score_mean = independent_sum * inv;
        movement->aoi_confidence_mean = confidence_sum * inv;
    }
}

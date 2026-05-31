#include "anomaly_roi_tracks.h"

#include "anomaly_target_tracks.h"

static void clear_primary_track(
        anomaly_state_t *state,
        int              track_idx) {
    if (state == NULL || track_idx < 0 || track_idx >= 4) return;
    state->acc_active[track_idx] = false;
    state->acc_hits[track_idx] = 0;
    state->acc_hold[track_idx] = 0;
    state->acc_presence_mask[track_idx] = 0u;
    state->acc_cx[track_idx] = 0.0f;
    state->acc_cy[track_idx] = 0.0f;
}

static void clear_saliency_aux_track_state(
        anomaly_state_t *state,
        int              track_idx) {
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS) return;
    state->saliency_aux_active[track_idx] = false;
    state->saliency_aux_hits[track_idx] = 0;
    state->saliency_aux_hold[track_idx] = 0;
    state->saliency_aux_cx[track_idx] = 0.0f;
    state->saliency_aux_cy[track_idx] = 0.0f;
    state->saliency_aux_display_algorithm[track_idx] = ANOMALY_ALGO_PERSIST;
}

void anomaly_roi_tracks_clear_saliency(anomaly_state_t *state) {
    if (state == NULL) return;
    clear_primary_track(state, 3);
    state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        clear_saliency_aux_track_state(state, i);
    }
}

void anomaly_roi_tracks_clear_all(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int ai = 0; ai < 4; ai++) {
        clear_primary_track(state, ai);
    }
    for (int ti = 0; ti < ANOMALY_COLOR_PROMOTION_TRACKS; ti++) {
        state->color_promotion_active[ti] = false;
        state->color_promotion_hits[ti] = 0;
        state->color_promotion_hold[ti] = 0;
        state->color_promotion_cx[ti] = 0.0f;
        state->color_promotion_cy[ti] = 0.0f;
    }
    state->saliency_display_algorithm = ANOMALY_ALGO_PERSIST;
    for (int i = 0; i < ANOMALY_SALIENCY_EXTRA_TRACKS; i++) {
        clear_saliency_aux_track_state(state, i);
    }
    anomaly_target_tracks_clear_all(state);
}

void anomaly_roi_tracks_age_one_frame(anomaly_state_t *state) {
    if (state == NULL) return;
    for (int ai = 0; ai < 4; ai++) {
        if (!state->acc_active[ai]) continue;
        int hold = state->acc_hold[ai] - 1;
        if (hold <= 0) {
            clear_primary_track(state, ai);
        } else {
            state->acc_hold[ai] = hold;
        }
    }
    for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS; ti++) {
        if (!state->saliency_aux_active[ti]) continue;
        int hold = state->saliency_aux_hold[ti] - 1;
        if (hold <= 0) {
            clear_saliency_aux_track_state(state, ti);
        } else {
            state->saliency_aux_hold[ti] = hold;
        }
    }
}

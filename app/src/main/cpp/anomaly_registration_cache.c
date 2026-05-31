#include "anomaly_registration_cache.h"

#include <string.h>

void anomaly_registration_cache_store(
        anomaly_state_t                     *state,
        const anomaly_registration_model_t  *model,
        anomaly_registration_health_t        registration_health,
        anomaly_rescan_mode_t                rescan_mode) {
    if (state == NULL || model == NULL || !anomaly_registration_model_valid(model)) {
        if (state != NULL) {
            state->cached_registration_valid = false;
            state->cached_registration_reuse_budget = 0;
        }
        return;
    }
    state->cached_registration_valid = true;
    state->cached_registration_mode = model->mode;
    state->cached_registration_sample_step = model->sample_step;
    state->cached_registration_motion_step = model->motion_step;
    state->cached_registration_anchor_count = model->anchor_count;
    state->cached_registration_tracked_match_count = model->tracked_match_count;
    state->cached_registration_invalid_reason = model->invalid_reason;
    state->cached_registration_health = registration_health;
    state->cached_registration_last_rescan_mode = rescan_mode;
    memcpy(state->cached_registration_affine, model->affine, sizeof(model->affine));
    state->cached_registration_similarity_a = model->similarity.a;
    state->cached_registration_similarity_b = model->similarity.b;
    state->cached_registration_similarity_tx = model->similarity.tx;
    state->cached_registration_similarity_ty = model->similarity.ty;
    state->cached_registration_similarity_mean_residual = model->similarity.mean_residual;
    state->cached_registration_fit_det = model->fit_det;
    state->cached_registration_fit_min_scale = model->fit_min_scale;
    state->cached_registration_fit_max_scale = model->fit_max_scale;
    state->cached_registration_fit_anchor_residual_std = model->fit_anchor_residual_std;
    state->cached_registration_fit_anchor_residual_max = model->fit_anchor_residual_max;
    state->cached_registration_fit_motion_dx_std = model->fit_motion_dx_std;
    state->cached_registration_fit_motion_dy_std = model->fit_motion_dy_std;
    state->cached_registration_fit_quadrant_residual_spread = model->fit_quadrant_residual_spread;

    bool stable_affine =
        model->mode == ANOMALY_REGISTRATION_AFFINE &&
        registration_health == ANOMALY_REG_HEALTH_HEALTHY &&
        model->invalid_reason == ANOMALY_REG_INVALID_REASON_NONE &&
        !model->scene_discontinuity &&
        model->anchor_count >= 12 &&
        model->tracked_match_count >= 48 &&
        model->similarity.mean_residual <= 0.005f &&
        model->fit_anchor_residual_max <= 0.040f &&
        model->fit_motion_dx_std <= 0.010f &&
        model->fit_motion_dy_std <= 0.010f &&
        model->fit_quadrant_residual_spread <= 0.010f &&
        model->fit_det > 0.985f &&
        model->fit_det < 1.015f &&
        model->fit_min_scale >= 0.985f &&
        model->fit_max_scale <= 1.015f &&
        (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY ||
         rescan_mode == ANOMALY_RESCAN_MODE_PARTIAL);
    if (!stable_affine) {
        state->cached_registration_reuse_budget = 0;
    } else if (rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY &&
               model->tracked_match_count >= 56 &&
               model->similarity.mean_residual <= 0.004f &&
               model->fit_anchor_residual_max <= 0.020f &&
               model->fit_motion_dx_std <= 0.006f &&
               model->fit_motion_dy_std <= 0.006f &&
               model->fit_quadrant_residual_spread <= 0.004f &&
               model->fit_det > 0.992f &&
               model->fit_det < 1.008f &&
               model->fit_min_scale >= 0.992f &&
               model->fit_max_scale <= 1.008f) {
        state->cached_registration_reuse_budget = 2;
    } else {
        state->cached_registration_reuse_budget = 1;
    }
}

bool anomaly_registration_cache_try_load(
        anomaly_registration_model_t *model_out,
        anomaly_state_t              *state,
        int                           mode,
        int                           motion_sample_step,
        int                           motion_step,
        int                           motion_w,
        int                           motion_h) {
    if (model_out == NULL || state == NULL ||
        mode != ANOMALY_REGISTRATION_AFFINE ||
        !state->cached_registration_valid ||
        state->cached_registration_reuse_budget <= 0 ||
        state->cached_registration_mode != ANOMALY_REGISTRATION_AFFINE ||
        state->cached_registration_health != ANOMALY_REG_HEALTH_HEALTHY ||
        state->cached_registration_invalid_reason != ANOMALY_REG_INVALID_REASON_NONE ||
        state->cached_registration_sample_step != motion_sample_step ||
        state->cached_registration_motion_step != motion_step ||
        state->prev_registration_luma == NULL ||
        state->prev_registration_luma_width != motion_w ||
        state->prev_registration_luma_height != motion_h) {
        return false;
    }

    anomaly_registration_model_t model = anomaly_registration_model_make(
        ANOMALY_REGISTRATION_AFFINE,
        motion_sample_step,
        motion_step);
    memcpy(model.affine, state->cached_registration_affine, sizeof(model.affine));
    model.similarity.a = state->cached_registration_similarity_a;
    model.similarity.b = state->cached_registration_similarity_b;
    model.similarity.tx = state->cached_registration_similarity_tx;
    model.similarity.ty = state->cached_registration_similarity_ty;
    model.similarity.mean_residual = state->cached_registration_similarity_mean_residual;
    model.similarity.valid = true;
    model.debug_valid = true;
    model.anchor_count = state->cached_registration_anchor_count;
    model.tracked_match_count = state->cached_registration_tracked_match_count;
    model.invalid_reason =
        (anomaly_registration_invalid_reason_t)state->cached_registration_invalid_reason;
    model.fit_det = state->cached_registration_fit_det;
    model.fit_min_scale = state->cached_registration_fit_min_scale;
    model.fit_max_scale = state->cached_registration_fit_max_scale;
    model.fit_anchor_residual_std = state->cached_registration_fit_anchor_residual_std;
    model.fit_anchor_residual_max = state->cached_registration_fit_anchor_residual_max;
    model.fit_motion_dx_std = state->cached_registration_fit_motion_dx_std;
    model.fit_motion_dy_std = state->cached_registration_fit_motion_dy_std;
    model.fit_quadrant_residual_spread = state->cached_registration_fit_quadrant_residual_spread;
    *model_out = model;
    state->cached_registration_reuse_budget -= 1;
    return true;
}

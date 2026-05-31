// Portable Thermal/IR persistent state ownership.
#include "anomaly_thermal_state.h"

#include <stdlib.h>
#include <string.h>

void anomaly_thermal_state_init(anomaly_thermal_state_t *state) {
    if (state == NULL) return;
    memset(state, 0, sizeof(*state));
}

void anomaly_thermal_state_reset(anomaly_thermal_state_t *state) {
    if (state == NULL) return;

    free(state->bg_luma);
    state->bg_luma = NULL;
    state->bg_sg_w = 0;
    state->bg_sg_h = 0;
    state->bg_warmup = 0;

    free(state->thermal_target_persist);
    state->thermal_target_persist = NULL;
    state->thermal_target_persist_w = 0;
    state->thermal_target_persist_h = 0;
}

bool anomaly_thermal_state_bg_ready(
        const anomaly_thermal_state_t *state,
        int                            sg_w,
        int                            sg_h,
        int                            min_warmup,
        bool                           require_current_dims,
        bool                           scene_discontinuity) {
    if (state == NULL || scene_discontinuity || state->bg_luma == NULL) {
        return false;
    }
    if (state->bg_warmup < min_warmup || state->bg_sg_w <= 0 || state->bg_sg_h <= 0) {
        return false;
    }
    if (require_current_dims && (state->bg_sg_w != sg_w || state->bg_sg_h != sg_h)) {
        return false;
    }
    return true;
}

bool anomaly_thermal_state_update_background(
        anomaly_thermal_state_t *state,
        const float             *sg_luma,
        int                      sg_w,
        int                      sg_h,
        bool                     black_hot,
        bool                     scene_discontinuity,
        bool                     selective_refresh_active,
        const uint8_t           *appearance_refresh_mask,
        float                    alpha_cool,
        float                    alpha_warm) {
    if (state == NULL || sg_luma == NULL || sg_w <= 0 || sg_h <= 0) {
        return false;
    }

    size_t count = (size_t)sg_w * (size_t)sg_h;
    bool reset_background =
        scene_discontinuity ||
        state->bg_luma == NULL ||
        state->bg_sg_w != sg_w ||
        state->bg_sg_h != sg_h;
    if (reset_background) {
        float *new_bg = (float *)malloc(count * sizeof(float));
        free(state->bg_luma);
        state->bg_luma = new_bg;
        state->bg_sg_w = sg_w;
        state->bg_sg_h = sg_h;
        state->bg_warmup = 0;
        if (state->bg_luma != NULL) {
            for (size_t i = 0; i < count; i++) {
                state->bg_luma[i] = sg_luma[i];
            }
        }
        return true;
    }

    for (size_t i = 0; i < count; i++) {
        if (selective_refresh_active &&
            appearance_refresh_mask != NULL &&
            appearance_refresh_mask[i] == 0u) {
            continue;
        }
        float cur = sg_luma[i];
        float bg = state->bg_luma[i];
        float delta = cur - bg;
        bool toward_cold = black_hot ? (delta > 0.0f) : (delta < 0.0f);
        float alpha = toward_cold ? alpha_cool : alpha_warm;
        state->bg_luma[i] = bg + alpha * delta;
    }
    state->bg_warmup++;
    return false;
}

bool anomaly_thermal_state_prepare_target_persist(
        anomaly_thermal_state_t *state,
        size_t                   sg_count,
        int                      sg_w,
        int                      sg_h,
        bool                     scene_discontinuity,
        float                    decay) {
    if (state == NULL || sg_count == 0 || sg_w <= 0 || sg_h <= 0) {
        return false;
    }

    bool reset_persist =
        scene_discontinuity ||
        state->thermal_target_persist == NULL ||
        state->thermal_target_persist_w != sg_w ||
        state->thermal_target_persist_h != sg_h;
    if (reset_persist) {
        float *new_persist = (float *)calloc(sg_count, sizeof(float));
        free(state->thermal_target_persist);
        state->thermal_target_persist = new_persist;
        state->thermal_target_persist_w =
            state->thermal_target_persist != NULL ? sg_w : 0;
        state->thermal_target_persist_h =
            state->thermal_target_persist != NULL ? sg_h : 0;
    } else if (state->thermal_target_persist != NULL) {
        for (size_t i = 0; i < sg_count; i++) {
            state->thermal_target_persist[i] *= decay;
        }
    }
    return state->thermal_target_persist != NULL;
}

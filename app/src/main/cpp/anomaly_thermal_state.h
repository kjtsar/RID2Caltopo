// Portable Thermal/IR persistent state ownership.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct anomaly_thermal_state_t {
    // One-sided EMA thermal background model at sampled-grid resolution.
    float *bg_luma;
    int    bg_sg_w;
    int    bg_sg_h;
    int    bg_warmup;

    // Short-lived prior for compact thermal candidates at sampled-grid resolution.
    float *thermal_target_persist;
    int    thermal_target_persist_w;
    int    thermal_target_persist_h;
} anomaly_thermal_state_t;

void anomaly_thermal_state_init(anomaly_thermal_state_t *state);
void anomaly_thermal_state_reset(anomaly_thermal_state_t *state);

bool anomaly_thermal_state_bg_ready(
        const anomaly_thermal_state_t *state,
        int                            sg_w,
        int                            sg_h,
        int                            min_warmup,
        bool                           require_current_dims,
        bool                           scene_discontinuity);

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
        float                    alpha_warm);

bool anomaly_thermal_state_prepare_target_persist(
        anomaly_thermal_state_t *state,
        size_t                   sg_count,
        int                      sg_w,
        int                      sg_h,
        bool                     scene_discontinuity,
        float                    decay);

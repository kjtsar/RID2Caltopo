// Private scan-planning wrapper for anomaly_analysis.c.
#include "anomaly_scan_planner.h"
#include "anomaly_analysis_internal.h"
#include "anomaly_buffer.h"
#include "anomaly_registration_model.h"
#include "anomaly_registration_quality.h"
#include "anomaly_roi_tracks.h"
#include "anomaly_target_revisit.h"

#include <math.h>
#include <string.h>

#define ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD 10

int anomaly_scan_planner_roi_grid_cell_span(int sample_step) {
    int step = sample_step > 0 ? sample_step : 1;
    int span = (ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX + step - 1) / step;
    return span > 0 ? span : 1;
}

static bool default_registration_valid(
        const anomaly_scan_planner_registration_t *registration) {
    return anomaly_registration_model_valid(registration);
}

static int default_target_revisit_track_count(
        const anomaly_state_t *state) {
    return anomaly_target_revisit_track_count(state);
}

static void default_adaptive_target_track_risk(
        const anomaly_state_t *state,
        int                    min_hits,
        bool                  *has_track_risk_out,
        bool                  *has_weak_lock_out) {
    anomaly_target_revisit_adaptive_track_risk(
            state,
            min_hits,
            has_track_risk_out,
            has_weak_lock_out);
}

static bool default_ensure_refresh_mask_capacity(
        anomaly_state_t *state,
        size_t           sample_count,
        uint8_t        **refresh_mask_out) {
    if (refresh_mask_out != NULL) *refresh_mask_out = NULL;
    if (state == NULL || refresh_mask_out == NULL) return false;
    if (!anomaly_buffer_ensure_u8_capacity(
            &state->scratch_refresh_mask,
            &state->scratch_refresh_mask_capacity,
            sample_count)) {
        return false;
    }
    *refresh_mask_out = state->scratch_refresh_mask;
    return true;
}

static const anomaly_scan_planner_ops_t default_ops = {
    .registration_valid = default_registration_valid,
    .target_revisit_track_count = default_target_revisit_track_count,
    .adaptive_target_track_risk = default_adaptive_target_track_risk,
    .age_roi_tracks_one_frame = anomaly_roi_tracks_age_one_frame,
    .ensure_refresh_mask_capacity = default_ensure_refresh_mask_capacity,
};

const anomaly_scan_planner_ops_t *anomaly_scan_planner_default_ops(void) {
    return &default_ops;
}

bool anomaly_scan_planner_build_prev_sample_lookup(
        const anomaly_roi_state_t                 *prev,
        const anomaly_scan_planner_registration_t *registration,
        int                                        frame_width,
        int                                        frame_height,
        int                                        roi_x0,
        int                                        roi_y0,
        int                                        roi_x1,
        int                                        roi_y1,
        int                                        sample_step,
        int                                        sampled_width,
        int                                        sampled_height,
        int                                        stale_limit,
        int                                       *prev_lookup_out,
        anomaly_scan_planner_prev_lookup_summary_t *summary_out) {
    if (summary_out != NULL) {
        summary_out->carried_samples = 0;
        summary_out->newly_exposed_samples = 0;
        summary_out->stale_samples = 0;
    }
    if (prev == NULL || registration == NULL || prev_lookup_out == NULL ||
        frame_width <= 0 || frame_height <= 0 ||
        sample_step <= 0 || sampled_width <= 0 || sampled_height <= 0 ||
        !prev->valid || prev->sample_step <= 0 ||
        prev->width <= 0 || prev->height <= 0 ||
        prev->valid_mask == NULL ||
        !anomaly_registration_model_valid(registration)) {
        return false;
    }

    float fw = (float)(frame_width > 1 ? frame_width - 1 : 1);
    float fh = (float)(frame_height > 1 ? frame_height - 1 : 1);
    int carried_samples = 0;
    int newly_exposed_samples = 0;
    int stale_samples = 0;
    for (int sy = 0; sy < sampled_height; sy++) {
        int y = roi_y0 + sy * sample_step + sample_step / 2;
        if (y >= roi_y1) y = roi_y1 - 1;
        for (int sx = 0; sx < sampled_width; sx++) {
            int x = roi_x0 + sx * sample_step + sample_step / 2;
            if (x >= roi_x1) x = roi_x1 - 1;
            size_t idx = (size_t)sy * (size_t)sampled_width + (size_t)sx;
            prev_lookup_out[idx] = ANOMALY_SCAN_PLANNER_PREV_LOOKUP_INVALID;

            float nx = clamp01f((float)x / fw);
            float ny = clamp01f((float)y / fh);
            float px = 0.0f;
            float py = 0.0f;
            anomaly_registration_apply_point(registration, nx, ny, &px, &py);
            int prev_px = clamp_i32((int)lroundf(px * fw), 0, frame_width - 1);
            int prev_py = clamp_i32((int)lroundf(py * fh), 0, frame_height - 1);
            if (prev_px < prev->roi_x0 || prev_px >= prev->roi_x1 ||
                prev_py < prev->roi_y0 || prev_py >= prev->roi_y1) {
                newly_exposed_samples++;
                continue;
            }
            int prev_sx = (prev_px - prev->roi_x0) / prev->sample_step;
            int prev_sy = (prev_py - prev->roi_y0) / prev->sample_step;
            if (prev_sx < 0 || prev_sy < 0 ||
                prev_sx >= prev->width || prev_sy >= prev->height) {
                newly_exposed_samples++;
                continue;
            }
            size_t prev_idx = (size_t)prev_sy * (size_t)prev->width + (size_t)prev_sx;
            if (!prev->valid_mask[prev_idx]) {
                newly_exposed_samples++;
                continue;
            }

            prev_lookup_out[idx] = (int)prev_idx;
            carried_samples++;
            int age = (int)prev->coverage_age[prev_idx] + 1;
            if (age > stale_limit) stale_samples++;
        }
    }

    if (summary_out != NULL) {
        summary_out->carried_samples = carried_samples;
        summary_out->newly_exposed_samples = newly_exposed_samples;
        summary_out->stale_samples = stale_samples;
    }
    return true;
}

static bool periodic_full_refresh_due(
        const anomaly_state_t *state,
        const anomaly_config_t *cfg,
        int64_t                source_ts_us) {
    if (state == NULL) return false;
    int64_t interval_us = ANOMALY_FULL_RESCAN_INTERVAL_US;
    int interval_frames = ANOMALY_FULL_RESCAN_INTERVAL_FRAMES;
    if (cfg != NULL) {
        if (isfinite(cfg->adaptive_max_stride_seconds) &&
            cfg->adaptive_max_stride_seconds > 0.0f) {
            int64_t configured_us =
                (int64_t)llroundf(cfg->adaptive_max_stride_seconds * 1000000.0f);
            if (configured_us > interval_us) interval_us = configured_us;
        }
        if (cfg->adaptive_max_stride_frames > interval_frames) {
            interval_frames = cfg->adaptive_max_stride_frames;
        }
    }
    if (source_ts_us > 0 && state->last_full_refresh_source_ts_us > 0) {
        return (source_ts_us - state->last_full_refresh_source_ts_us) >=
               interval_us;
    }
    if (state->last_full_refresh_frame_counter > 0) {
        return (state->frame_counter - state->last_full_refresh_frame_counter) >=
               interval_frames;
    }
    return false;
}

static int adaptive_max_stride_frames_for_source(
        anomaly_state_t        *state,
        const anomaly_config_t *cfg,
        int64_t                 source_ts_us,
        int                     min_stride) {
    int max_stride = cfg != NULL && cfg->adaptive_max_stride_frames > 0
        ? cfg->adaptive_max_stride_frames
        : 33;
    if (source_ts_us > 0 && state != NULL) {
        if (state->adaptive_last_source_ts_us > 0 &&
            source_ts_us > state->adaptive_last_source_ts_us) {
            float dt_us = (float)(source_ts_us - state->adaptive_last_source_ts_us);
            if (dt_us > 1000.0f && dt_us < 1000000.0f) {
                if (state->adaptive_frame_interval_ema_us <= 0.0f) {
                    state->adaptive_frame_interval_ema_us = dt_us;
                } else {
                    state->adaptive_frame_interval_ema_us =
                        0.85f * state->adaptive_frame_interval_ema_us + 0.15f * dt_us;
                }
            }
        }
        state->adaptive_last_source_ts_us = source_ts_us;
    }
    if (cfg != NULL &&
        isfinite(cfg->adaptive_max_stride_seconds) &&
        cfg->adaptive_max_stride_seconds > 0.0f &&
        state != NULL &&
        state->adaptive_frame_interval_ema_us > 1000.0f) {
        int seconds_limited = (int)floorf(
                (cfg->adaptive_max_stride_seconds * 1000000.0f) /
                state->adaptive_frame_interval_ema_us + 0.5f);
        if (seconds_limited >= min_stride && seconds_limited < max_stride) {
            max_stride = seconds_limited;
        }
    }
    return clamp_i32(max_stride, min_stride, 120);
}

static int compute_adaptive_effective_stride(
        anomaly_state_t                    *state,
        const anomaly_config_t             *cfg,
        const anomaly_scan_planner_ops_t   *ops,
        anomaly_registration_health_t       registration_health,
        bool                                scene_discontinuity,
        const anomaly_debug_movement_t     *movement,
        int64_t                             source_ts_us,
        uint32_t                           *reason_flags_out,
        float                              *motion_load_out) {
    if (reason_flags_out != NULL) *reason_flags_out = 0u;
    if (motion_load_out != NULL) *motion_load_out = 0.0f;
    if (cfg == NULL) return 1;

    int fixed_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
    if (cfg->stride_mode != ANOMALY_STRIDE_MODE_ADAPTIVE || state == NULL) {
        return fixed_stride;
    }

    int min_stride = cfg->adaptive_min_stride_frames > 0
        ? cfg->adaptive_min_stride_frames
        : fixed_stride;
    min_stride = clamp_i32(min_stride, 1, 120);
    int max_stride = adaptive_max_stride_frames_for_source(
            state,
            cfg,
            source_ts_us,
            min_stride);
    fixed_stride = clamp_i32(fixed_stride, min_stride, max_stride);

    if (state->adaptive_effective_stride < min_stride ||
        state->adaptive_effective_stride > max_stride) {
        state->adaptive_effective_stride = fixed_stride;
    }

    float instant_motion_load = 0.0f;
    if (movement != NULL && movement->valid) {
        instant_motion_load =
            fmaxf(movement->parallax_load,
                  fmaxf(movement->local_outlier_load,
                        (float)movement->unstable_count / 8.0f));
    }
    if (state->adaptive_motion_load_ema <= 0.0f) {
        state->adaptive_motion_load_ema = instant_motion_load;
    } else {
        state->adaptive_motion_load_ema =
            0.70f * state->adaptive_motion_load_ema + 0.30f * instant_motion_load;
    }
    float motion_load = fmaxf(instant_motion_load, state->adaptive_motion_load_ema);

    bool target_track_risk = false;
    bool weak_target_lock = false;
    if (ops != NULL && ops->adaptive_target_track_risk != NULL) {
        ops->adaptive_target_track_risk(
                state,
                cfg->min_hits,
                &target_track_risk,
                &weak_target_lock);
    }
    bool target_rich_recent =
        state->adaptive_target_rich_frames > 0 ||
        state->last_color_full_scan_coarse_count >= 4;

    uint32_t reasons = 0u;
    int desired_stride = state->adaptive_effective_stride;
    if (desired_stride <= 0) desired_stride = fixed_stride;

    if (scene_discontinuity) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_SCENE_DISCONTINUITY;
        desired_stride = min_stride;
        state->adaptive_drop_hold_frames = 8;
    }
    if (registration_health == ANOMALY_REG_HEALTH_INVALID ||
        registration_health == ANOMALY_REG_HEALTH_UNKNOWN) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_REG_INVALID;
        desired_stride = min_stride;
        if (state->adaptive_drop_hold_frames < 6) state->adaptive_drop_hold_frames = 6;
    } else if (registration_health == ANOMALY_REG_HEALTH_HARD_DEGRADED ||
               registration_health == ANOMALY_REG_HEALTH_SOFT_DEGRADED) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_REG_DEGRADED;
        desired_stride = min_stride;
        if (state->adaptive_drop_hold_frames < 4) state->adaptive_drop_hold_frames = 4;
    }
    if (motion_load >= 0.24f ||
        (movement != NULL && movement->valid &&
         (movement->unstable_count >= 3 ||
          movement->aoi_parallax_count > 0 ||
          movement->aoi_unstable_count > 0))) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_MOVEMENT_LOAD;
        desired_stride = min_stride;
        if (state->adaptive_drop_hold_frames < 4) state->adaptive_drop_hold_frames = 4;
    }
    if (target_track_risk) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_TRACK;
        desired_stride = min_stride;
        if (state->adaptive_drop_hold_frames < 4) state->adaptive_drop_hold_frames = 4;
    }
    if (weak_target_lock) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_WEAK_TARGET_LOCK;
        desired_stride = min_stride;
        if (state->adaptive_drop_hold_frames < 4) state->adaptive_drop_hold_frames = 4;
    }
    if (target_rich_recent) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_TARGET_RICH_RECENT;
        int target_rich_stride = min_stride + 1;
        if (target_rich_stride > max_stride) target_rich_stride = max_stride;
        if (desired_stride > target_rich_stride) desired_stride = target_rich_stride;
        if (state->adaptive_drop_hold_frames < 2) state->adaptive_drop_hold_frames = 2;
    }

    bool low_motion = movement == NULL || !movement->valid ||
        (motion_load < 0.08f &&
         movement->unstable_count == 0 &&
         movement->local_outlier_count <= 1 &&
         movement->aoi_parallax_count == 0 &&
         movement->aoi_unstable_count == 0);
    bool stable_window =
        reasons == 0u &&
        registration_health == ANOMALY_REG_HEALTH_HEALTHY &&
        !scene_discontinuity &&
        low_motion &&
        state->roi_state.valid &&
        state->adaptive_drop_hold_frames <= 0;

    if (desired_stride < state->adaptive_effective_stride) {
        state->adaptive_effective_stride = desired_stride;
        state->adaptive_stable_frames = 0;
    } else if (stable_window) {
        reasons |= ANOMALY_ADAPTIVE_STRIDE_REASON_STABLE_WINDOW;
        state->adaptive_stable_frames++;
        if (state->adaptive_stable_frames >= 8 &&
            state->adaptive_effective_stride < max_stride) {
            state->adaptive_effective_stride++;
            state->adaptive_stable_frames = 0;
        }
    } else {
        state->adaptive_stable_frames = 0;
        if (state->adaptive_drop_hold_frames > 0 &&
            state->adaptive_effective_stride > min_stride) {
            state->adaptive_effective_stride--;
        }
    }

    if (state->adaptive_drop_hold_frames > 0) {
        state->adaptive_drop_hold_frames--;
    }
    state->adaptive_effective_stride =
        clamp_i32(state->adaptive_effective_stride, min_stride, max_stride);
    state->adaptive_reason_flags = reasons;
    if (reason_flags_out != NULL) *reason_flags_out = reasons;
    if (motion_load_out != NULL) *motion_load_out = motion_load;
    return state->adaptive_effective_stride;
}

static anomaly_scan_plan_t build_scan_plan(
        const anomaly_state_t                              *state,
        const anomaly_scan_planner_registration_t          *model,
        const anomaly_scan_planner_ops_t                   *ops,
        anomaly_registration_health_t                       base_registration_health,
        int                                                 sample_step,
        int                                                 sg_w,
        int                                                 sg_h,
        bool                                                full_refresh_cadence_due,
        bool                                                scene_discontinuity,
        bool                                                periodic_full_refresh_due_value,
        bool                                                selected_target_color_acquisition_pending,
        const anomaly_scan_planner_prev_lookup_summary_t   *prev_lookup_summary,
        anomaly_registration_health_t                      *registration_health_out) {
    anomaly_scan_plan_t plan;
    memset(&plan, 0, sizeof(plan));
    plan.mode = full_refresh_cadence_due
        ? ANOMALY_RESCAN_MODE_FULL
        : ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP;
    plan.sampled_width = sg_w;
    plan.sampled_height = sg_h;
    plan.total_samples = sg_w > 0 && sg_h > 0 ? sg_w * sg_h : 0;
    if (ops != NULL && ops->target_revisit_track_count != NULL) {
        plan.target_revisit_track_count = ops->target_revisit_track_count(state);
    }

    anomaly_registration_health_t refined = base_registration_health;
    if (!full_refresh_cadence_due) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH;
    }
    if (plan.total_samples <= 0) {
        if (plan.total_samples <= 0) {
            plan.reason_flags |= ANOMALY_SCAN_REASON_NO_SAMPLES;
        }
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }

    plan.valid = true;
    const anomaly_roi_state_t *prev = state != NULL ? &state->roi_state : NULL;
    bool prev_valid = true;
    if (prev == NULL) {
        prev_valid = false;
    } else {
        if (!prev->valid ||
            prev->width <= 0 ||
            prev->height <= 0 ||
            prev->sample_step <= 0 ||
            prev->valid_mask == NULL) {
            prev_valid = false;
            plan.reason_flags |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
        }
    }
    bool registration_valid =
        ops != NULL && ops->registration_valid != NULL && ops->registration_valid(model);
    if (!registration_valid) {
        prev_valid = false;
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
    }
    if (scene_discontinuity) {
        prev_valid = false;
        plan.reason_flags |= ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY;
    }
    if (!prev_valid) {
        if ((plan.reason_flags & (ANOMALY_SCAN_REASON_PREV_STATE_INVALID |
                                  ANOMALY_SCAN_REASON_REG_INVALID |
                                  ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY)) == 0u) {
            plan.reason_flags |= ANOMALY_SCAN_REASON_PREV_STATE_INVALID;
        }
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }

    float reg_conf = anomaly_registration_health_confidence(base_registration_health);
    if (prev_lookup_summary == NULL) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
        if (registration_health_out != NULL) *registration_health_out = refined;
        return plan;
    }
    plan.carried_samples = prev_lookup_summary->carried_samples;
    plan.newly_exposed_samples = prev_lookup_summary->newly_exposed_samples;
    plan.stale_samples = prev_lookup_summary->stale_samples;

    if (plan.total_samples > 0) {
        float inv_total = 1.0f / (float)plan.total_samples;
        plan.warped_valid_fraction = (float)plan.carried_samples * inv_total;
        plan.newly_exposed_fraction = (float)plan.newly_exposed_samples * inv_total;
        plan.stale_fraction = (float)plan.stale_samples * inv_total;
    }

    if (refined == ANOMALY_REG_HEALTH_HEALTHY) {
        if (plan.warped_valid_fraction < 0.65f || plan.newly_exposed_fraction > 0.35f) {
            refined = ANOMALY_REG_HEALTH_HARD_DEGRADED;
        } else if (plan.warped_valid_fraction < 0.80f || plan.newly_exposed_fraction > 0.20f ||
                   reg_conf < 0.90f) {
            refined = ANOMALY_REG_HEALTH_SOFT_DEGRADED;
        }
    } else if (refined == ANOMALY_REG_HEALTH_SOFT_DEGRADED) {
        if (plan.warped_valid_fraction < 0.65f || plan.newly_exposed_fraction > 0.35f) {
            refined = ANOMALY_REG_HEALTH_HARD_DEGRADED;
        }
    }

    bool force_full = false;
    if (scene_discontinuity) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY;
        force_full = true;
    }
    if (refined == ANOMALY_REG_HEALTH_INVALID) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_INVALID;
        force_full = true;
    }
    if (refined == ANOMALY_REG_HEALTH_HARD_DEGRADED) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_REG_HARD_DEGRADED;
        force_full = true;
    }
    if (plan.warped_valid_fraction < 0.80f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_WARP_LOW;
        force_full = true;
    }
    if (plan.newly_exposed_fraction > 0.25f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH;
        force_full = true;
    }
    if (plan.stale_fraction > 0.35f) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_STALE_HIGH;
    }
    if (prev->sample_step != sample_step) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH;
        force_full = true;
    }
    if (full_refresh_cadence_due || periodic_full_refresh_due_value) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH;
        force_full = true;
    }
    if (selected_target_color_acquisition_pending) {
        plan.reason_flags |= ANOMALY_SCAN_REASON_TARGET_COLOR_ACQUIRE;
        force_full = true;
    }

    if (force_full) {
        plan.mode = ANOMALY_RESCAN_MODE_FULL;
    } else if (plan.target_revisit_track_count > 0 &&
               plan.newly_exposed_fraction < 0.05f &&
               plan.stale_fraction < 0.10f) {
        plan.mode = ANOMALY_RESCAN_MODE_TARGET_ONLY;
        plan.reason_flags |= ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE;
        plan.reason_flags &= ~ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH;
    } else {
        plan.mode = ANOMALY_RESCAN_MODE_PARTIAL;
        plan.reason_flags |= ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE;
    }

    if (registration_health_out != NULL) *registration_health_out = refined;
    return plan;
}

bool anomaly_scan_planner_build_selective_refresh_mask(
        const anomaly_state_t *state,
        anomaly_rescan_mode_t  mode,
        bool                   allow_sparse_fallback,
        int                    sampled_width,
        int                    sampled_height,
        const int             *prev_sample_lookup,
        uint8_t               *refresh_mask,
        int                   *selected_count_out,
        uint32_t              *reason_flags_out) {
    if (selected_count_out != NULL) *selected_count_out = 0;
    if (reason_flags_out != NULL) *reason_flags_out = 0u;
    if (refresh_mask == NULL || state == NULL ||
        prev_sample_lookup == NULL ||
        (mode != ANOMALY_RESCAN_MODE_PARTIAL &&
         mode != ANOMALY_RESCAN_MODE_TARGET_ONLY)) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        return false;
    }

    const anomaly_roi_state_t *prev = &state->roi_state;
    if (!prev->valid ||
        prev->width <= 0 || prev->height <= 0 ||
        prev->cell_cols <= 0 || prev->cell_rows <= 0 ||
        prev->valid_mask == NULL ||
        prev->cell_summaries == NULL ||
        sampled_width <= 0 || sampled_height <= 0) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_BUILD_FAILED;
        return false;
    }

    size_t total_samples = (size_t)sampled_width * (size_t)sampled_height;
    memset(refresh_mask, 0, total_samples * sizeof(uint8_t));
    int prev_cell_span = anomaly_scan_planner_roi_grid_cell_span(prev->sample_step);
    if (prev_cell_span <= 0) return false;

    uint32_t required_flags = (mode == ANOMALY_RESCAN_MODE_TARGET_ONLY)
        ? ANOMALY_SCAN_FLAG_TARGET_REVISIT
        : (ANOMALY_SCAN_FLAG_NEW_EXPOSED |
           ANOMALY_SCAN_FLAG_STALE |
           ANOMALY_SCAN_FLAG_TARGET_REVISIT);
    bool target_only = (mode == ANOMALY_RESCAN_MODE_TARGET_ONLY);
    int selected_count = 0;
    for (int sy = 0; sy < sampled_height; sy++) {
        for (int sx = 0; sx < sampled_width; sx++) {
            size_t idx = (size_t)sy * (size_t)sampled_width + (size_t)sx;
            bool select_sample = false;
            int prev_idx = prev_sample_lookup[idx];
            if (prev_idx < 0) {
                select_sample = !target_only;
            } else {
                int prev_sx = prev_idx % prev->width;
                int prev_sy = prev_idx / prev->width;
                int cell_x = clamp_i32(prev_sx / prev_cell_span, 0, prev->cell_cols - 1);
                int cell_y = clamp_i32(prev_sy / prev_cell_span, 0, prev->cell_rows - 1);
                size_t cell_idx = (size_t)cell_y * (size_t)prev->cell_cols + (size_t)cell_x;
                uint32_t scan_flags = prev->cell_summaries[cell_idx].scan_flags;
                select_sample = (scan_flags & required_flags) != 0u;
            }
            refresh_mask[idx] = select_sample ? 1u : 0u;
            selected_count += select_sample ? 1 : 0;
        }
    }

    if (!target_only && allow_sparse_fallback && selected_count <= 0) {
        int sparse_phase = state->frame_counter % ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD;
        for (int sy = 0; sy < sampled_height; sy++) {
            for (int sx = 0; sx < sampled_width; sx++) {
                if (((sx + sy + sparse_phase) % ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD) != 0) continue;
                size_t idx = (size_t)sy * (size_t)sampled_width + (size_t)sx;
                if (refresh_mask[idx] != 0u) continue;
                refresh_mask[idx] = 1u;
                selected_count++;
            }
        }
    }

    if (selected_count_out != NULL) *selected_count_out = selected_count;
    if (selected_count <= 0 && target_only) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_EMPTY;
        return false;
    }
    if (!target_only && selected_count > 0 &&
        selected_count >= (int)(total_samples * 0.95f)) {
        if (reason_flags_out != NULL) *reason_flags_out |= ANOMALY_SCAN_REASON_MASK_TOO_BROAD;
        int sparse_phase = state->frame_counter % ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD;
        selected_count = 0;
        for (int sy = 0; sy < sampled_height; sy++) {
            for (int sx = 0; sx < sampled_width; sx++) {
                size_t idx = (size_t)sy * (size_t)sampled_width + (size_t)sx;
                bool keep_sample =
                    ((sx + sy + sparse_phase) % ANOMALY_COLOR_PARTIAL_SPARSE_FALLBACK_PERIOD) == 0;
                refresh_mask[idx] = keep_sample ? 1u : 0u;
                selected_count += keep_sample ? 1 : 0;
            }
        }
        if (selected_count_out != NULL) *selected_count_out = selected_count;
    }
    return true;
}

bool anomaly_scan_planner_plan(
        const anomaly_scan_planner_input_t *input,
        anomaly_scan_planner_output_t      *output) {
    if (output == NULL) return false;
    memset(output, 0, sizeof(*output));
    output->adaptive.effective_frame_stride =
        input != NULL && input->adaptive.fixed_frame_stride > 0
            ? input->adaptive.fixed_frame_stride
            : 1;
    output->registration_health = input != NULL
        ? input->base_registration_health
        : ANOMALY_REG_HEALTH_UNKNOWN;
    output->rescan_mode = ANOMALY_RESCAN_MODE_UNSET;
    output->scan_plan.mode = ANOMALY_RESCAN_MODE_UNSET;

    if (input == NULL || input->cfg == NULL || input->state == NULL) {
        return false;
    }

    anomaly_state_t *state = input->state;
    const anomaly_config_t *cfg = input->cfg;
    const anomaly_scan_planner_ops_t *ops = input->ops;

    uint32_t adaptive_reason_flags = 0u;
    float adaptive_motion_load = 0.0f;
    int effective_frame_stride = input->adaptive.fixed_frame_stride > 0
        ? input->adaptive.fixed_frame_stride
        : 1;
    bool full_refresh_cadence_due = input->fixed_full_refresh_cadence_due;
    if (input->adaptive.adaptive_enabled) {
        effective_frame_stride = compute_adaptive_effective_stride(
                state,
                cfg,
                ops,
                input->base_registration_health,
                input->scene_discontinuity,
                input->movement,
                input->frame_source_ts_us,
                &adaptive_reason_flags,
                &adaptive_motion_load);
        int64_t frames_since_full = state->last_full_refresh_frame_counter > 0
            ? state->frame_counter - state->last_full_refresh_frame_counter
            : effective_frame_stride;
        full_refresh_cadence_due =
            state->frame_counter <= 1 ||
            frames_since_full >= effective_frame_stride;
        if (!full_refresh_cadence_due &&
            ops != NULL &&
            ops->age_roi_tracks_one_frame != NULL) {
            ops->age_roi_tracks_one_frame(state);
        }
    } else {
        state->adaptive_effective_stride = effective_frame_stride;
        state->adaptive_stable_frames = 0;
        state->adaptive_drop_hold_frames = 0;
        state->adaptive_reason_flags = 0u;
    }

    int64_t scan_plan_started_us = anomaly_timing_now_us();
    bool force_periodic_full_refresh =
        input->adaptive.adaptive_enabled &&
        periodic_full_refresh_due(state, cfg, input->frame_source_ts_us);
    anomaly_registration_health_t registration_health = input->base_registration_health;
    bool selected_target_color_acquisition_pending =
        input->selected_target_color_acquisition_pending ||
        (cfg->target_color_family_mask != 0u &&
         anomaly_target_revisit_color_track_count(state) == 0);
    bool selected_target_color_confirmation_pending =
        cfg->target_color_family_mask != 0u &&
        anomaly_target_revisit_color_track_count(state) > 0 &&
        anomaly_target_revisit_confirmed_color_track_count(state, cfg->min_hits) == 0;
    anomaly_scan_plan_t scan_plan = build_scan_plan(
        state,
        input->registration,
        ops,
        input->base_registration_health,
        input->sample_step,
        input->sampled_width,
        input->sampled_height,
        full_refresh_cadence_due,
        input->scene_discontinuity,
        force_periodic_full_refresh,
        selected_target_color_acquisition_pending,
        input->prev_sample_lookup != NULL ? input->prev_lookup_summary : NULL,
        &registration_health);
    int64_t scan_plan_elapsed_us = anomaly_timing_now_us() - scan_plan_started_us;
    if (scan_plan_elapsed_us < 0) scan_plan_elapsed_us = 0;

    anomaly_rescan_mode_t rescan_mode = scan_plan.mode;
    bool color_stride_hold_frame =
        input->color_algorithm_configured &&
        input->color_stride_hold_eligible &&
        effective_frame_stride > 1 &&
        !full_refresh_cadence_due &&
        !force_periodic_full_refresh &&
        !selected_target_color_acquisition_pending &&
        !selected_target_color_confirmation_pending &&
        scan_plan.mode != ANOMALY_RESCAN_MODE_TARGET_ONLY;
    if (color_stride_hold_frame) {
        rescan_mode = ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP;
        scan_plan.mode = ANOMALY_RESCAN_MODE_APPEARANCE_STRIDE_SKIP;
        scan_plan.reason_flags |= ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH;
        bool registration_valid =
            ops != NULL && ops->registration_valid != NULL && ops->registration_valid(input->registration);
        if (!input->scene_discontinuity &&
            registration_valid &&
            state->acc_active[0] &&
            state->acc_hold[0] < ANOMALY_ACC_HOLD_FRAMES) {
            state->acc_hold[0] += 1;
        }
    }
    if (rescan_mode == ANOMALY_RESCAN_MODE_FULL) {
        if (input->frame_source_ts_us > 0) {
            state->last_full_refresh_source_ts_us = input->frame_source_ts_us;
        }
        state->last_full_refresh_frame_counter = state->frame_counter;
    }

    size_t sg_count = (size_t)input->sampled_width * (size_t)input->sampled_height;
    uint8_t *appearance_refresh_mask = NULL;
    bool selective_refresh_active = false;
    uint32_t selective_refresh_reason_flags = 0u;
    int selected_samples = 0;
    int64_t refresh_mask_started_us = anomaly_timing_now_us();
    if ((rescan_mode == ANOMALY_RESCAN_MODE_PARTIAL ||
         rescan_mode == ANOMALY_RESCAN_MODE_TARGET_ONLY) &&
        sg_count > 0) {
        bool have_mask_storage =
            ops != NULL &&
            ops->ensure_refresh_mask_capacity != NULL &&
            ops->ensure_refresh_mask_capacity(state, sg_count, &appearance_refresh_mask);
        if (have_mask_storage) {
            selective_refresh_active = anomaly_scan_planner_build_selective_refresh_mask(
                    state,
                    rescan_mode,
                    input->selective_refresh.allow_sparse_fallback,
                    input->sampled_width,
                    input->sampled_height,
                    input->prev_sample_lookup,
                    appearance_refresh_mask,
                    &selected_samples,
                    &selective_refresh_reason_flags);
            scan_plan.refresh_mask_selected_samples = selected_samples;
            if (sg_count > 0) {
                scan_plan.refresh_mask_selected_fraction =
                    (float)selected_samples / (float)sg_count;
            }
        }
        if (!selective_refresh_active) {
            appearance_refresh_mask = NULL;
            rescan_mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.mode = ANOMALY_RESCAN_MODE_FULL;
            scan_plan.reason_flags |= selective_refresh_reason_flags;
            output->forced_full_after_mask_failure = true;
        }
    }
    int64_t refresh_mask_elapsed_us = anomaly_timing_now_us() - refresh_mask_started_us;
    if (refresh_mask_elapsed_us < 0) refresh_mask_elapsed_us = 0;

    output->adaptive.effective_frame_stride = effective_frame_stride;
    output->adaptive.stable_frames = state->adaptive_stable_frames;
    output->adaptive.drop_hold_frames = state->adaptive_drop_hold_frames;
    output->adaptive.reason_flags = adaptive_reason_flags;
    output->adaptive.motion_load = adaptive_motion_load;
    output->rescan_mode = rescan_mode;
    output->scan_plan = scan_plan;
    output->registration_health = registration_health;
    output->appearance_refresh_mask = appearance_refresh_mask;
    output->selective_refresh_active = selective_refresh_active;
    output->selective_refresh_selected_samples = selected_samples;
    output->selective_refresh_selected_fraction = sg_count > 0
        ? (float)selected_samples / (float)sg_count
        : 0.0f;
    output->selective_refresh_reason_flags = selective_refresh_reason_flags;
    output->color_stride_hold_frame = color_stride_hold_frame;
    output->full_refresh_cadence_due = full_refresh_cadence_due;
    output->periodic_full_refresh_due = force_periodic_full_refresh;
    output->scan_planning_elapsed_us = scan_plan_elapsed_us;
    output->refresh_mask_elapsed_us = refresh_mask_elapsed_us;
    return true;
}

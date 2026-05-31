#include "anomaly_saliency_tracks.h"
#include "anomaly_analysis_internal.h"

#include <math.h>

#define ANOMALY_SALIENCY_SECONDARY_HOLD_BONUS 6
#define ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS 2

void anomaly_saliency_update_aux_track(
        anomaly_state_t *state,
        int              track_idx,
        float            raw_cx,
        float            raw_cy,
        float            gate,
        float            alpha) {
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS) return;
    int base_hold_frames = ANOMALY_ACC_HOLD_FRAMES;
    if (state->saliency_aux_hits[track_idx] >= 3) {
        base_hold_frames += ANOMALY_SALIENCY_SECONDARY_HOLD_BONUS;
    }
    if (raw_cx >= 0.0f && raw_cy >= 0.0f) {
        if (!state->saliency_aux_active[track_idx]) {
            state->saliency_aux_cx[track_idx] = raw_cx;
            state->saliency_aux_cy[track_idx] = raw_cy;
            state->saliency_aux_hits[track_idx] = 1;
            state->saliency_aux_hold[track_idx] = base_hold_frames;
            state->saliency_aux_active[track_idx] = true;
            return;
        }

        float ddx = raw_cx - state->saliency_aux_cx[track_idx];
        float ddy = raw_cy - state->saliency_aux_cy[track_idx];
        float dist = sqrtf(ddx * ddx + ddy * ddy);
        if (dist <= gate) {
            state->saliency_aux_cx[track_idx] += alpha * ddx;
            state->saliency_aux_cy[track_idx] += alpha * ddy;
            int h = state->saliency_aux_hits[track_idx] + 1;
            state->saliency_aux_hits[track_idx] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
        } else {
            state->saliency_aux_cx[track_idx] = raw_cx;
            state->saliency_aux_cy[track_idx] = raw_cy;
            state->saliency_aux_hits[track_idx] = 1;
        }
        state->saliency_aux_hold[track_idx] = base_hold_frames;
        return;
    }

    if (!state->saliency_aux_active[track_idx]) return;
    int hold = state->saliency_aux_hold[track_idx] - 1;
    if (hold <= 0) {
        state->saliency_aux_active[track_idx] = false;
        state->saliency_aux_hits[track_idx] = 0;
        state->saliency_aux_hold[track_idx] = 0;
    } else {
        state->saliency_aux_hold[track_idx] = hold;
    }
}

bool anomaly_saliency_find_local_support(
        const anomaly_state_t *state,
        int                    track_idx,
        const float           *patch_selection_map,
        int                    sg_w,
        int                    sg_h,
        int                    roi_x0,
        int                    roi_y0,
        int                    sample_step,
        int                    width,
        int                    height,
        float                 *x_norm_out,
        float                 *y_norm_out,
        float                 *score_out) {
    if (x_norm_out != NULL) *x_norm_out = -1.0f;
    if (y_norm_out != NULL) *y_norm_out = -1.0f;
    if (score_out != NULL) *score_out = -1.0f;
    if (state == NULL || track_idx < 0 || track_idx >= ANOMALY_SALIENCY_EXTRA_TRACKS ||
        !state->saliency_aux_active[track_idx] || patch_selection_map == NULL ||
        sg_w <= 0 || sg_h <= 0 || sample_step <= 0) {
        return false;
    }

    float fw = (float)(width > 1 ? width - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);
    int track_x = clamp_i32((int)lroundf(state->saliency_aux_cx[track_idx] * fw),
                            roi_x0,
                            roi_x0 + (sg_w - 1) * sample_step);
    int track_y = clamp_i32((int)lroundf(state->saliency_aux_cy[track_idx] * fh),
                            roi_y0,
                            roi_y0 + (sg_h - 1) * sample_step);
    int track_sx = clamp_i32((track_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
    int track_sy = clamp_i32((track_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);

    float best_score = -1.0f;
    int best_sx = track_sx;
    int best_sy = track_sy;
    for (int sy = track_sy - ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS;
         sy <= track_sy + ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS; sy++) {
        if (sy < 0 || sy >= sg_h) continue;
        for (int sx = track_sx - ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS;
             sx <= track_sx + ANOMALY_SALIENCY_SECONDARY_LOCAL_RADIUS_CELLS; sx++) {
            if (sx < 0 || sx >= sg_w) continue;
            float score = patch_selection_map[sy * sg_w + sx];
            if (score > best_score) {
                best_score = score;
                best_sx = sx;
                best_sy = sy;
            }
        }
    }
    if (best_score <= 0.0f) return false;

    int best_x = roi_x0 + best_sx * sample_step;
    int best_y = roi_y0 + best_sy * sample_step;
    if (x_norm_out != NULL) *x_norm_out = (float)best_x / fw;
    if (y_norm_out != NULL) *y_norm_out = (float)best_y / fh;
    if (score_out != NULL) *score_out = best_score;
    return true;
}

void anomaly_saliency_choose_best_dark_patch(
        const float *selection_map,
        int          sg_w,
        int          sg_h,
        int          roi_x0,
        int          roi_y0,
        int          sample_step,
        float       *best_score_out,
        int         *best_x_out,
        int         *best_y_out) {
    if (best_score_out != NULL) *best_score_out = -1.0f;
    if (best_x_out != NULL) *best_x_out = 0;
    if (best_y_out != NULL) *best_y_out = 0;
    if (selection_map == NULL || sg_w <= 0 || sg_h <= 0) return;

    float best_score = -1.0f;
    int best_sx = 0;
    int best_sy = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            float score = selection_map[sy * sg_w + sx];
            if (score > best_score) {
                best_score = score;
                best_sx = sx;
                best_sy = sy;
            }
        }
    }

    if (best_score_out != NULL) *best_score_out = best_score;
    if (best_x_out != NULL) *best_x_out = roi_x0 + best_sx * sample_step;
    if (best_y_out != NULL) *best_y_out = roi_y0 + best_sy * sample_step;
}

int anomaly_saliency_classify_display_algorithm(
        const float *saliency_spatial_map,
        const float *saliency_color_map,
        const float *saliency_motion_map,
        const float *saliency_registration_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        delta_mean,
        float        delta_norm) {
    if (sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) return ANOMALY_ALGO_PERSIST;
    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float registration = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
    float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
    float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
    float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
    float thermal_temporal = 0.0f;
    if (bg_valid && bg_luma != NULL && sg_luma != NULL) {
        float bg = bg_luma[idx];
        float lum = (float)sg_luma[idx];
        float delta = black_hot ? (bg - lum) : (lum - bg);
        if (delta >= thermal_min_delta) {
            thermal_temporal = (float)((delta - delta_mean) / delta_norm);
        }
    }

    float thermal_evidence = fmaxf(thermal_spatial > 0.0f ? thermal_spatial : 0.0f,
                                   thermal_temporal > 0.0f ? thermal_temporal : 0.0f);
    float color_evidence = color_support > 0.0f ? (0.60f * color_support) : 0.0f;
    float motion_evidence = motion_support > 0.0f
        ? ((bg_valid ? 0.60f : 0.45f) * motion_support)
        : 0.0f;
    thermal_evidence *= registration;
    color_evidence *= registration;
    motion_evidence *= registration;

    float best = thermal_evidence;
    float second = color_evidence > motion_evidence ? color_evidence : motion_evidence;
    int algorithm = ANOMALY_ALGO_THERMAL;
    if (color_evidence > best) {
        second = thermal_evidence > motion_evidence ? thermal_evidence : motion_evidence;
        best = color_evidence;
        algorithm = ANOMALY_ALGO_COLOR;
    }
    if (motion_evidence > best) {
        second = thermal_evidence > color_evidence ? thermal_evidence : color_evidence;
        best = motion_evidence;
        algorithm = ANOMALY_ALGO_MOTION;
    }
    if (best <= 0.0f) return ANOMALY_ALGO_PERSIST;
    if (second > 0.0f && best < second * 1.12f) return ANOMALY_ALGO_PERSIST;
    return algorithm;
}

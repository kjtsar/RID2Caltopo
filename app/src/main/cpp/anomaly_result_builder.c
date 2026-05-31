#include "anomaly_result_builder.h"

#include "anomaly_debug_helpers.h"

#include <math.h>
#include <stdint.h>

static float anomaly_result_clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

int anomaly_result_build_boxes(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     motion_box_algorithm,
        anomaly_box_t          *boxes,
        int                     max_boxes) {
    if (state == NULL || cfg == NULL || boxes == NULL || max_boxes <= 0) return 0;

    float box_side = sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f));
    box_side = (box_side < 0.02f) ? 0.02f : (box_side > 0.18f ? 0.18f : box_side);

    static const uint8_t algo_rgb[4][3] = {
        {0x2D, 0x6C, 0xFF},
        {0xF2, 0x30, 0x30},
        {0x23, 0xC5, 0x52},
        {0xFF, 0xE0, 0x3B},
    };
    static const float algo_box_scale[4] = {1.0f, 1.0f, 1.3f, 0.9f};
    int algo_bits[4] = {ANOMALY_ALGO_COLOR, ANOMALY_ALGO_THERMAL, motion_box_algorithm, ANOMALY_ALGO_PERSIST};
    bool saliency_primary =
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0;

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    int box_count = 0;
    for (int ti = 0; ti < ANOMALY_MAX_TARGET_TRACKS && box_count < max_boxes; ti++) {
        const anomaly_target_track_t *track = &state->target_tracks[ti];
        if (!track->active || !track->publish_confirmed || track->hit_count < min_hits) continue;
        int rgb_idx = 3;
        if (track->algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
        else if (track->algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
        else if (track->algorithm == motion_box_algorithm || track->algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
        float weight = anomaly_result_clampf(0.25f + track->confidence + 0.05f * (float)track->hit_count, 0.25f, 1.0f);
        anomaly_debug_append_center_box(
                boxes,
                &box_count,
                max_boxes,
                track->center_x_norm,
                track->center_y_norm,
                fmaxf(track->half_w_norm * 2.0f, box_side * 0.85f),
                fmaxf(track->half_h_norm * 2.0f, box_side * 0.85f),
                algo_rgb[rgb_idx][0],
                algo_rgb[rgb_idx][1],
                algo_rgb[rgb_idx][2],
                weight);
        if (box_count > 0) {
            boxes[box_count - 1].algorithm = track->algorithm;
        }
    }
    if (box_count > 0) return box_count;
    for (int ai = 0; ai < 4 && box_count < max_boxes; ai++) {
        if (saliency_primary && ai != 3) continue;
        if (!state->acc_active[ai]) continue;
        if (state->acc_hits[ai] < min_hits) continue;
        float weight = 0.35f + 0.13f * (float)(state->acc_hits[ai] - min_hits);
        if (weight > 1.0f) weight = 1.0f;
        float bw = box_side * algo_box_scale[ai];
        int cue_algorithm = ai == 3 ? state->saliency_display_algorithm : algo_bits[ai];
        int rgb_idx = ai;
        if (ai == 3) {
            if (cue_algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
            else if (cue_algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
            else if (cue_algorithm == motion_box_algorithm || cue_algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
            else rgb_idx = 3;
        }
        anomaly_debug_append_center_box(
                boxes,
                &box_count,
                max_boxes,
                state->acc_cx[ai],
                state->acc_cy[ai],
                bw,
                bw,
                algo_rgb[rgb_idx][0],
                algo_rgb[rgb_idx][1],
                algo_rgb[rgb_idx][2],
                weight);
        if (box_count > 0) {
            boxes[box_count - 1].algorithm = algo_bits[ai];
        }
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0 && !saliency_primary) {
        for (int ti = 0; ti < ANOMALY_SALIENCY_EXTRA_TRACKS && box_count < max_boxes; ti++) {
            if (!state->saliency_aux_active[ti]) continue;
            if (state->saliency_aux_hits[ti] < min_hits) continue;
            float weight = 0.30f + 0.11f * (float)(state->saliency_aux_hits[ti] - min_hits);
            if (weight > 0.92f) weight = 0.92f;
            float bw = box_side * 0.82f;
            int cue_algorithm = state->saliency_aux_display_algorithm[ti];
            int rgb_idx = 3;
            if (cue_algorithm == ANOMALY_ALGO_COLOR) rgb_idx = 0;
            else if (cue_algorithm == ANOMALY_ALGO_THERMAL) rgb_idx = 1;
            else if (cue_algorithm == motion_box_algorithm || cue_algorithm == ANOMALY_ALGO_MOTION) rgb_idx = 2;
            anomaly_debug_append_center_box(
                    boxes,
                    &box_count,
                    max_boxes,
                    state->saliency_aux_cx[ti],
                    state->saliency_aux_cy[ti],
                    bw,
                    bw,
                    algo_rgb[rgb_idx][0],
                    algo_rgb[rgb_idx][1],
                    algo_rgb[rgb_idx][2],
                    weight);
            if (box_count > 0) {
                boxes[box_count - 1].algorithm = ANOMALY_ALGO_PERSIST;
            }
        }
    }
    return box_count;
}

// Internal pure visible-color histogram and rarity helpers.
#pragma once

#include "anomaly_analysis.h"
#include "anomaly_target_observations.h"
#include "anomaly_target_matching.h"

#include <math.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

#define ANOMALY_COLOR_DETECTOR_U_MIN (-112.0f)
#define ANOMALY_COLOR_DETECTOR_U_MAX (112.0f)
#define ANOMALY_COLOR_DETECTOR_V_MIN (-112.0f)
#define ANOMALY_COLOR_DETECTOR_V_MAX (112.0f)
#define ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MIN 1.10f
#define ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT 1.28f
#define ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX 2.10f
#define ANOMALY_COLOR_DENSE_SEED_TOP_K 32
#define ANOMALY_COLOR_DENSE_SEED_NMS_RADIUS 2
#define ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN 3
#define ANOMALY_COLOR_CONTRAST_MIN_TOTAL_NEIGHBORS 4
#define ANOMALY_COLOR_CONTRAST_CHROMA_SOFT 3.0f
#define ANOMALY_COLOR_CONTRAST_CHROMA_HARD 14.0f
#define ANOMALY_COLOR_CONTRAST_LUMA_SOFT 2.0f
#define ANOMALY_COLOR_CONTRAST_LUMA_HARD 10.0f
#define ANOMALY_COLOR_CONTRAST_RESCUE_MIN 1.06f
#define ANOMALY_COLOR_RESCUE_SCORE_BASE 0.85f
#define ANOMALY_COLOR_RESCUE_SCORE_RANGE 1.45f
#define ANOMALY_FRESH_COLOR_WINNER_MAX_SPAN_SCALE 1.35f
#define ANOMALY_FRESH_COLOR_WINNER_MAX_AREA_SCALE 1.65f
#define ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY 0.00085f
#define ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS 0.92f
#define ANOMALY_FRESH_COLOR_WINNER_COMMONNESS_MIN_SPAN_RATIO 0.95f
#define ANOMALY_FRESH_COLOR_WINNER_COMMONNESS_MIN_AREA_RATIO 0.70f
#define ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_X 0.465f
#define ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_Y 0.255f
#define ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_RADIUS 0.060f
#define ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_SEED_FLOOR 0.58f
#define ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_MAX_CELLS 9

typedef struct {
    int   sx;
    int   sy;
    float support;
    float score;
} anomaly_color_dense_seed_t;

typedef struct {
    float support;
    float seed_floor;
    float compact_prominence;
    float core_share;
} anomaly_color_support_score_t;

static inline float anomaly_color_clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline int anomaly_color_support_patch_radius(float target_span_px, int sample_step) {
    int step = sample_step > 0 ? sample_step : 1;
    int patch_radius = (int)lroundf(fmaxf(1.0f, (0.5f * target_span_px) / (float)step));
    if (patch_radius > 4) patch_radius = 4;
    return patch_radius;
}

static inline float anomaly_color_support_distinctness_ratio(float local_peak, float ring_mean) {
    return local_peak / fmaxf(ring_mean, 0.08f);
}

static inline float anomaly_color_support_distinctness_gate(
        float distinctness_ratio,
        float fresh_distinctness_ratio) {
    return anomaly_color_clampf(
        (distinctness_ratio - (fresh_distinctness_ratio - 0.18f)) / 0.62f,
        0.0f,
        1.0f);
}

static inline float anomaly_color_support_compact_prominence(
        float local_peak,
        float ring_mean,
        float distinctness_gate) {
    return anomaly_color_clampf(
        0.65f * ((local_peak - ring_mean - 0.20f) / 1.20f) +
        0.35f * distinctness_gate,
        0.0f,
        1.0f);
}

static inline float anomaly_color_support_core_share(float center, float local_peak) {
    return anomaly_color_clampf(center / fmaxf(local_peak, 0.001f), 0.0f, 1.0f);
}

static inline bool anomaly_color_candidate_near_reviewed_fp_cluster(float cx_norm, float cy_norm) {
    float dx = cx_norm - ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_X;
    float dy = cy_norm - ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_Y;
    float dist = sqrtf(dx * dx + dy * dy);
    return dist <= ANOMALY_COLOR_PROVISIONAL_FP_CLUSTER_RADIUS;
}

static inline float anomaly_color_clamp01f(float value) {
    return anomaly_color_clampf(value, 0.0f, 1.0f);
}

static inline int anomaly_color_find_best_track_support_match(
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
        if (!track->active) continue;
        if (track->algorithm != ANOMALY_ALGO_COLOR &&
            track->algorithm != ANOMALY_ALGO_PERSIST) {
            continue;
        }
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

static inline float anomaly_color_score_track_persistence_bonus(
        const anomaly_state_t              *state,
        const anomaly_target_observation_t *obs,
        float                               registration_quality,
        float                               local_motion_support) {
    return anomaly_target_observation_score_track_support_bonus(
        state,
        obs,
        registration_quality,
        local_motion_support);
}

static inline float anomaly_color_support_seed_floor(
        int   color_frontend_mode,
        float fresh_distinctness_ratio) {
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY) {
        return 0.55f;
    }
    return
        0.55f +
        0.16f * anomaly_color_clampf(
            (fresh_distinctness_ratio - ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT) /
            (ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX -
             ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT),
            0.0f,
            1.0f);
}

static inline anomaly_color_support_score_t anomaly_color_score_support_patch(
        int   color_frontend_mode,
        float fresh_distinctness_ratio,
        float center,
        float mean,
        float legacy_ring_mean,
        float fresh_ring_mean,
        float density,
        float local_peak,
        int   support_count,
        float contrast_weight) {
    float support_weight = anomaly_color_clampf(0.55f + 0.45f * contrast_weight, 0.35f, 1.20f);
    float patch_support = 0.0f;
    float compact_prominence = 0.0f;
    float core_share = 0.0f;
    float seed_floor = 0.55f;
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY) {
        patch_support =
            0.55f * center +
            0.75f * mean +
            0.90f * density -
            0.30f * legacy_ring_mean;
    } else {
        float distinctness_ratio =
            anomaly_color_support_distinctness_ratio(local_peak, fresh_ring_mean);
        float distinctness_gate =
            anomaly_color_support_distinctness_gate(distinctness_ratio, fresh_distinctness_ratio);
        compact_prominence =
            anomaly_color_support_compact_prominence(
                local_peak,
                fresh_ring_mean,
                distinctness_gate);
        core_share = anomaly_color_support_core_share(center, local_peak);
        patch_support =
            center * (0.45f + 0.75f * compact_prominence) +
            mean * (0.12f + 0.28f * core_share) +
            0.30f * density -
            0.75f * fresh_ring_mean;
        patch_support *= 0.45f + 0.75f * distinctness_gate;
        support_weight *= anomaly_color_clampf(0.92f + 0.18f * distinctness_gate, 0.80f, 1.12f);
        seed_floor =
            anomaly_color_support_seed_floor(color_frontend_mode, fresh_distinctness_ratio);
    }
    patch_support *= support_weight;
    float clamped_support = anomaly_color_clampf(patch_support, 0.0f, 4.0f);
    if (color_frontend_mode != ANOMALY_COLOR_FRONTEND_LEGACY) {
        bool compact_peak_seed =
            center >= local_peak - 0.02f &&
            support_count <= ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_MAX_CELLS &&
            compact_prominence >= 0.45f &&
            core_share >= 0.82f &&
            fresh_ring_mean <= local_peak * 0.88f;
        if (compact_peak_seed) {
            float compact_seed_floor =
                fmaxf(seed_floor, ANOMALY_COLOR_SUPPORT_COMPACT_PEAK_SEED_FLOOR) +
                0.06f * anomaly_color_clampf((compact_prominence - 0.45f) / 0.35f, 0.0f, 1.0f) +
                0.04f * anomaly_color_clampf((0.75f - density) / 0.45f, 0.0f, 1.0f);
            clamped_support = fmaxf(clamped_support, compact_seed_floor);
        }
    }
    anomaly_color_support_score_t score = {
        clamped_support,
        seed_floor,
        compact_prominence,
        core_share,
    };
    return score;
}

static inline void anomaly_color_sample_pixel_yuv(
        const uint8_t *rgba,
        int            rgba_stride,
        int            frame_w,
        int            frame_h,
        int            px,
        int            py,
        float         *luma_out,
        float         *u_out,
        float         *v_out) {
    if (luma_out != NULL) *luma_out = 0.0f;
    if (u_out != NULL) *u_out = 0.0f;
    if (v_out != NULL) *v_out = 0.0f;
    if (rgba == NULL || rgba_stride <= 0 || frame_w <= 0 || frame_h <= 0 ||
        px < 0 || py < 0 || px >= frame_w || py >= frame_h) {
        return;
    }
    size_t sample_byte_offset = (size_t)px * 4u;
    if (sample_byte_offset + 3u >= (size_t)rgba_stride) return;
    const uint8_t *pixel = rgba + (size_t)py * (size_t)rgba_stride + sample_byte_offset;
    float r = (float)pixel[0];
    float g = (float)pixel[1];
    float b = (float)pixel[2];
    if (luma_out != NULL) *luma_out = (0.2126f * r) + (0.7152f * g) + (0.0722f * b);
    if (u_out != NULL) *u_out = (-0.14713f * r) - (0.28886f * g) + (0.43600f * b);
    if (v_out != NULL) *v_out = ( 0.61500f * r) - (0.51499f * g) - (0.10001f * b);
}

static inline void anomaly_color_sample_cell(
        const uint8_t *rgba,
        int            rgba_stride,
        int            frame_width,
        int            frame_height,
        int            sample_x,
        int            sample_y,
        float         *luma_out,
        float         *u_out,
        float         *v_out) {
    anomaly_color_sample_pixel_yuv(
        rgba,
        rgba_stride,
        frame_width,
        frame_height,
        sample_x,
        sample_y,
        luma_out,
        u_out,
        v_out);
}

static inline void anomaly_color_sampling_phase_for_frame(
        const anomaly_state_t *state,
        int sample_step,
        int *phase_index_out,
        int *phase_x_out,
        int *phase_y_out) {
    (void)state;
    int phase_index = 0;
    int step = sample_step > 0 ? sample_step : 1;
    int phase_x = step / 2;
    int phase_y = step / 2;
    if (phase_index_out != NULL) *phase_index_out = phase_index;
    if (phase_x_out != NULL) *phase_x_out = phase_x;
    if (phase_y_out != NULL) *phase_y_out = phase_y;
}

static inline void anomaly_color_advance_sampling_phase(
        anomaly_state_t *state,
        bool appearance_refresh_ran,
        int sample_step) {
    (void)state;
    (void)appearance_refresh_ran;
    (void)sample_step;
}

static inline void anomaly_color_compute_sample_xy(
        int roi_x0,
        int roi_y0,
        int roi_x1,
        int roi_y1,
        int sx,
        int sy,
        int sample_step,
        int phase_x,
        int phase_y,
        int *sample_x_out,
        int *sample_y_out) {
    int step = sample_step > 0 ? sample_step : 1;
    int cell_x0 = roi_x0 + sx * step;
    int cell_y0 = roi_y0 + sy * step;
    int cell_x1 = cell_x0 + step;
    int cell_y1 = cell_y0 + step;
    if (cell_x1 > roi_x1) cell_x1 = roi_x1;
    if (cell_y1 > roi_y1) cell_y1 = roi_y1;
    if (cell_x1 <= cell_x0) cell_x1 = cell_x0 + 1;
    if (cell_y1 <= cell_y0) cell_y1 = cell_y0 + 1;
    int local_phase_x = phase_x;
    int local_phase_y = phase_y;
    if (local_phase_x < 0) local_phase_x = 0;
    if (local_phase_y < 0) local_phase_y = 0;
    int max_local_x = cell_x1 - cell_x0 - 1;
    int max_local_y = cell_y1 - cell_y0 - 1;
    if (local_phase_x > max_local_x) local_phase_x = max_local_x;
    if (local_phase_y > max_local_y) local_phase_y = max_local_y;
    int sample_x = cell_x0 + local_phase_x;
    int sample_y = cell_y0 + local_phase_y;
    if (sample_x >= roi_x1) sample_x = roi_x1 - 1;
    if (sample_y >= roi_y1) sample_y = roi_y1 - 1;
    if (sample_x < roi_x0) sample_x = roi_x0;
    if (sample_y < roi_y0) sample_y = roi_y0;
    if (sample_x_out != NULL) *sample_x_out = sample_x;
    if (sample_y_out != NULL) *sample_y_out = sample_y;
}

static inline bool anomaly_color_dense_pixel_matches(
        float seed_u,
        float seed_v,
        float seed_luma,
        float px_u,
        float px_v,
        float px_luma,
        float chroma_thresh,
        float luma_thresh) {
    float du = px_u - seed_u;
    float dv = px_v - seed_v;
    float chroma_delta = sqrtf((du * du) + (dv * dv));
    float luma_delta = fabsf(px_luma - seed_luma);
    return chroma_delta <= chroma_thresh && luma_delta <= luma_thresh;
}

static inline bool anomaly_color_support_seed_is_local_peak(
        const float *support_map,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          min_sx,
        int          min_sy,
        int          max_sx,
        int          max_sy,
        float        seed_support) {
    if (support_map == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h) {
        return false;
    }

    for (int ny = sy - 1; ny <= sy + 1; ny++) {
        if (ny < min_sy || ny > max_sy || ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - 1; nx <= sx + 1; nx++) {
            if (nx < min_sx || nx > max_sx || nx < 0 || nx >= sg_w) continue;
            if (nx == sx && ny == sy) continue;
            float neighbor_support = support_map[(size_t)ny * (size_t)sg_w + (size_t)nx];
            if (neighbor_support <= 0.0f) continue;
            if (neighbor_support > seed_support + 0.02f) return false;
            if (fabsf(neighbor_support - seed_support) <= 0.02f) {
                if (ny < sy || (ny == sy && nx < sx)) return false;
            }
        }
    }
    return true;
}

static inline float anomaly_color_score_dense_seed(
        const float *support_map,
        const float *contrast_map,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          min_sx,
        int          min_sy,
        int          max_sx,
        int          max_sy,
        float        seed_support) {
    if (support_map == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h) {
        return 0.0f;
    }
    float neighbor_sum = 0.0f;
    float neighbor_max = 0.0f;
    int neighbor_count = 0;
    for (int ny = sy - 2; ny <= sy + 2; ny++) {
        if (ny < min_sy || ny > max_sy || ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - 2; nx <= sx + 2; nx++) {
            if (nx < min_sx || nx > max_sx || nx < 0 || nx >= sg_w) continue;
            if (nx == sx && ny == sy) continue;
            float neighbor = support_map[(size_t)ny * (size_t)sg_w + (size_t)nx];
            if (neighbor <= 0.0f) continue;
            neighbor_sum += neighbor;
            if (neighbor > neighbor_max) neighbor_max = neighbor;
            neighbor_count++;
        }
    }
    float neighbor_mean = neighbor_count > 0 ? neighbor_sum / (float)neighbor_count : 0.0f;
    float prominence = anomaly_color_clampf((seed_support - neighbor_mean + 0.20f) / 1.20f, 0.0f, 1.0f);
    float isolation = anomaly_color_clampf((seed_support - neighbor_max + 0.35f) / 0.95f, 0.0f, 1.0f);
    float compact_context = neighbor_count <= 8
        ? 1.0f
        : anomaly_color_clampf(1.0f - ((float)(neighbor_count - 8) / 18.0f), 0.25f, 1.0f);
    float contrast_weight = contrast_map != NULL
        ? contrast_map[(size_t)sy * (size_t)sg_w + (size_t)sx]
        : 1.0f;
    float contrast_score = anomaly_color_clampf((contrast_weight - 0.35f) / 0.85f, 0.0f, 1.0f);
    return seed_support *
        (0.42f + 0.30f * contrast_score + 0.20f * prominence +
         0.08f * isolation) * compact_context;
}

static inline void anomaly_color_find_seed_bounds_from_evidence(
        const float *evidence_map,
        int          sg_w,
        int          sg_h,
        int          active_min_sx,
        int          active_min_sy,
        int          active_max_sx,
        int          active_max_sy,
        float        seed_floor,
        float       *max_support_out,
        int         *seed_min_sx_out,
        int         *seed_min_sy_out,
        int         *seed_max_sx_out,
        int         *seed_max_sy_out,
        int         *seed_count_out) {
    if (max_support_out != NULL) *max_support_out = 0.0f;
    if (seed_count_out != NULL) *seed_count_out = 0;
    if (seed_min_sx_out != NULL) *seed_min_sx_out = sg_w;
    if (seed_min_sy_out != NULL) *seed_min_sy_out = sg_h;
    if (seed_max_sx_out != NULL) *seed_max_sx_out = -1;
    if (seed_max_sy_out != NULL) *seed_max_sy_out = -1;
    if (evidence_map == NULL || sg_w <= 0 || sg_h <= 0) return;
    if (active_min_sx < 0) active_min_sx = 0;
    if (active_min_sy < 0) active_min_sy = 0;
    if (active_max_sx >= sg_w) active_max_sx = sg_w - 1;
    if (active_max_sy >= sg_h) active_max_sy = sg_h - 1;
    if (active_max_sx < active_min_sx || active_max_sy < active_min_sy) return;

    for (int sy = active_min_sy; sy <= active_max_sy; sy++) {
        for (int sx = active_min_sx; sx <= active_max_sx; sx++) {
            float v = evidence_map[(size_t)sy * (size_t)sg_w + (size_t)sx];
            if (max_support_out != NULL && v > *max_support_out) {
                *max_support_out = v;
            }
            if (v < seed_floor) continue;
            if (seed_count_out != NULL) (*seed_count_out)++;
            if (seed_min_sx_out != NULL && sx < *seed_min_sx_out) *seed_min_sx_out = sx;
            if (seed_min_sy_out != NULL && sy < *seed_min_sy_out) *seed_min_sy_out = sy;
            if (seed_max_sx_out != NULL && sx > *seed_max_sx_out) *seed_max_sx_out = sx;
            if (seed_max_sy_out != NULL && sy > *seed_max_sy_out) *seed_max_sy_out = sy;
        }
    }
}

static inline void anomaly_color_insert_dense_seed(
        anomaly_color_dense_seed_t *seeds,
        int                        *seed_count,
        int                         max_seed_count,
        const anomaly_color_dense_seed_t *seed) {
    if (seeds == NULL || seed_count == NULL || seed == NULL) return;
    if (max_seed_count <= 0) return;
    if (max_seed_count > ANOMALY_COLOR_DENSE_SEED_TOP_K) {
        max_seed_count = ANOMALY_COLOR_DENSE_SEED_TOP_K;
    }
    for (int i = 0; i < *seed_count; i++) {
        int dx = abs(seeds[i].sx - seed->sx);
        int dy = abs(seeds[i].sy - seed->sy);
        if (dx <= ANOMALY_COLOR_DENSE_SEED_NMS_RADIUS &&
            dy <= ANOMALY_COLOR_DENSE_SEED_NMS_RADIUS) {
            if (seed->score > seeds[i].score) {
                seeds[i] = *seed;
                for (int j = i; j > 0 && seeds[j].score > seeds[j - 1].score; j--) {
                    anomaly_color_dense_seed_t tmp = seeds[j - 1];
                    seeds[j - 1] = seeds[j];
                    seeds[j] = tmp;
                }
                for (int j = i; j + 1 < *seed_count && seeds[j].score < seeds[j + 1].score; j++) {
                    anomaly_color_dense_seed_t tmp = seeds[j + 1];
                    seeds[j + 1] = seeds[j];
                    seeds[j] = tmp;
                }
            }
            return;
        }
    }

    int insert_at = *seed_count;
    for (int i = 0; i < *seed_count; i++) {
        if (seed->score > seeds[i].score) {
            insert_at = i;
            break;
        }
    }
    if (insert_at >= max_seed_count) return;
    int move_limit = *seed_count < max_seed_count
        ? *seed_count
        : max_seed_count - 1;
    for (int i = move_limit; i > insert_at; i--) {
        seeds[i] = seeds[i - 1];
    }
    seeds[insert_at] = *seed;
    if (*seed_count < max_seed_count) (*seed_count)++;
}

static inline int anomaly_color_effective_frontend_mode(const anomaly_config_t *cfg) {
    int requested = cfg != NULL ? cfg->color_frontend_mode : ANOMALY_COLOR_FRONTEND_LEGACY;
    if (requested == ANOMALY_COLOR_FRONTEND_FRESH_YUV) {
        // The public analyzer entrypoint still accepts RGBA. Until a YUV-native
        // sidecar is wired into the internal bridge, fall back to the fresh
        // current-frame RGBA frontend rather than silently behaving like legacy.
        return ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    }
    if (requested == ANOMALY_COLOR_FRONTEND_FRESH_RGBA) {
        return ANOMALY_COLOR_FRONTEND_FRESH_RGBA;
    }
    return ANOMALY_COLOR_FRONTEND_LEGACY;
}

static inline bool anomaly_color_frontend_allows_pre_support_temporal_rescue(
        int color_frontend_mode) {
    return color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY;
}

static inline bool anomaly_color_frontend_uses_fresh_winner_gate(int color_frontend_mode) {
    return color_frontend_mode == ANOMALY_COLOR_FRONTEND_FRESH_RGBA ||
           color_frontend_mode == ANOMALY_COLOR_FRONTEND_FRESH_YUV;
}

static inline anomaly_color_winner_gate_reason_t anomaly_color_evaluate_fresh_winner_gate(
        float small_target_span_px,
        int   sample_step,
        float candidate_area_cells,
        float candidate_span_cells,
        float candidate_hist_rarity,
        float candidate_scene_commonness,
        float *max_span_cells_out,
        float *max_area_cells_out,
        float *min_rarity_out,
        float *max_commonness_out) {
    float step = sample_step > 0 ? (float)sample_step : 1.0f;
    float small_target_span_cells = small_target_span_px / step;
    float max_span_cells = fmaxf(1.75f, small_target_span_cells * ANOMALY_FRESH_COLOR_WINNER_MAX_SPAN_SCALE);
    float max_area_cells = fmaxf(3.0f, small_target_span_cells * small_target_span_cells *
                                           ANOMALY_FRESH_COLOR_WINNER_MAX_AREA_SCALE);
    float commonness_min_span_cells =
        fmaxf(1.50f, small_target_span_cells * ANOMALY_FRESH_COLOR_WINNER_COMMONNESS_MIN_SPAN_RATIO);
    float commonness_min_area_cells =
        fmaxf(1.50f, small_target_span_cells * small_target_span_cells *
                        ANOMALY_FRESH_COLOR_WINNER_COMMONNESS_MIN_AREA_RATIO);
    float min_rarity = ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY;
    float max_commonness = ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS;
    if (max_span_cells_out != NULL) *max_span_cells_out = max_span_cells;
    if (max_area_cells_out != NULL) *max_area_cells_out = max_area_cells;
    if (min_rarity_out != NULL) *min_rarity_out = min_rarity;
    if (max_commonness_out != NULL) *max_commonness_out = max_commonness;

    bool oversize =
        candidate_span_cells > max_span_cells ||
        candidate_area_cells > max_area_cells;
    (void)commonness_min_span_cells;
    (void)commonness_min_area_cells;
    bool too_common =
        candidate_hist_rarity < min_rarity &&
        candidate_scene_commonness > max_commonness;
    if (oversize && too_common) return ANOMALY_COLOR_WINNER_GATE_SIZE_AND_COMMONNESS;
    if (oversize) return ANOMALY_COLOR_WINNER_GATE_SIZE;
    if (too_common) return ANOMALY_COLOR_WINNER_GATE_COMMONNESS;
    return ANOMALY_COLOR_WINNER_GATE_NONE;
}

static inline void anomaly_color_suppress_seed_region(
        uint8_t *visited,
        int      sg_w,
        int      sg_h,
        int      scan_min_sx,
        int      scan_min_sy,
        int      scan_max_sx,
        int      scan_max_sy,
        int      candidate_min_x,
        int      candidate_min_y,
        int      candidate_max_x,
        int      candidate_max_y) {
    if (visited == NULL || sg_w <= 0 || sg_h <= 0) return;

    int pad = 1;
    int suppress_min_x = candidate_min_x - pad;
    int suppress_min_y = candidate_min_y - pad;
    int suppress_max_x = candidate_max_x + pad;
    int suppress_max_y = candidate_max_y + pad;
    if (suppress_min_x < scan_min_sx) suppress_min_x = scan_min_sx;
    if (suppress_min_y < scan_min_sy) suppress_min_y = scan_min_sy;
    if (suppress_max_x > scan_max_sx) suppress_max_x = scan_max_sx;
    if (suppress_max_y > scan_max_sy) suppress_max_y = scan_max_sy;
    if (suppress_min_x < 0) suppress_min_x = 0;
    if (suppress_min_y < 0) suppress_min_y = 0;
    if (suppress_max_x >= sg_w) suppress_max_x = sg_w - 1;
    if (suppress_max_y >= sg_h) suppress_max_y = sg_h - 1;
    if (suppress_max_x < suppress_min_x || suppress_max_y < suppress_min_y) return;

    for (int sy = suppress_min_y; sy <= suppress_max_y; sy++) {
        for (int sx = suppress_min_x; sx <= suppress_max_x; sx++) {
            visited[(size_t)sy * (size_t)sg_w + (size_t)sx] = 1u;
        }
    }
}

static inline float anomaly_color_score_candidate_temporal_boost(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     color_frontend_mode,
        int                     sg_w,
        int                     sg_h,
        int                     sx,
        int                     sy) {
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY ||
        state == NULL || cfg == NULL || !state->acc_active[0] ||
        sg_w <= 0 || sg_h <= 0) {
        return 0.0f;
    }

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    if (state->acc_hits[0] < min_hits) return 0.0f;

    int prior_sx = (int)lroundf(state->acc_cx[0] * (float)(sg_w - 1));
    if (prior_sx < 0) prior_sx = 0;
    if (prior_sx > sg_w - 1) prior_sx = sg_w - 1;
    int prior_sy = (int)lroundf(state->acc_cy[0] * (float)(sg_h - 1));
    if (prior_sy < 0) prior_sy = 0;
    if (prior_sy > sg_h - 1) prior_sy = sg_h - 1;
    int chebyshev = abs(sx - prior_sx);
    int dy = abs(sy - prior_sy);
    if (dy > chebyshev) chebyshev = dy;
    if (chebyshev > ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS) return 0.0f;

    float proximity = 1.0f -
        ((float)chebyshev / (float)(ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1));
    float hit_strength = anomaly_color_clampf((float)(state->acc_hits[0] - min_hits) / 3.0f,
                                              0.0f,
                                              1.0f);
    float hold_strength = anomaly_color_clampf((float)state->acc_hold[0] /
                                                   (float)ANOMALY_ACC_HOLD_FRAMES,
                                               0.0f,
                                               1.0f);
    float strength = 0.55f * proximity + 0.25f * hit_strength + 0.20f * hold_strength;
    return 0.18f + 0.52f * anomaly_color_clampf(strength, 0.0f, 1.0f);
}

static inline float anomaly_color_score_temporal_rescue(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     sg_w,
        int                     sg_h,
        int                     sx,
        int                     sy,
        bool                    sampled_this_frame,
        int                     local_support) {
    if (state == NULL || cfg == NULL ||
        !sampled_this_frame ||
        !state->acc_active[0] ||
        local_support < ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) {
        return 0.0f;
    }

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    if (state->acc_hits[0] < min_hits || sg_w <= 0 || sg_h <= 0) return 0.0f;

    int prior_sx = (int)lroundf(state->acc_cx[0] * (float)(sg_w - 1));
    if (prior_sx < 0) prior_sx = 0;
    if (prior_sx > sg_w - 1) prior_sx = sg_w - 1;
    int prior_sy = (int)lroundf(state->acc_cy[0] * (float)(sg_h - 1));
    if (prior_sy < 0) prior_sy = 0;
    if (prior_sy > sg_h - 1) prior_sy = sg_h - 1;
    int dx = abs(sx - prior_sx);
    int dy = abs(sy - prior_sy);
    int chebyshev = dx > dy ? dx : dy;
    if (chebyshev > ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS) return 0.0f;

    float proximity = 1.0f -
        ((float)chebyshev / (float)(ANOMALY_COLOR_TEMPORAL_RESCUE_RADIUS_CELLS + 1));
    if (proximity < 0.0f) proximity = 0.0f;
    float hit_strength = anomaly_color_clampf((float)(state->acc_hits[0] - min_hits) / 3.0f,
                                              0.0f,
                                              1.0f);
    float hold_strength = anomaly_color_clampf((float)state->acc_hold[0] /
                                                   (float)ANOMALY_ACC_HOLD_FRAMES,
                                               0.0f,
                                               1.0f);
    float support_strength = anomaly_color_clampf(
        (float)(local_support - ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) / 4.0f,
        0.0f,
        1.0f);
    float strength =
        0.45f * proximity +
        0.25f * hit_strength +
        0.15f * hold_strength +
        0.15f * support_strength;
    return ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_BASE +
        ANOMALY_COLOR_TEMPORAL_RESCUE_SCORE_RANGE * strength;
}

static inline int anomaly_color_quantize_uv_bin(
        float value,
        float min_v,
        float max_v,
        int   bins) {
    float clamped = anomaly_color_clampf(value, min_v, max_v);
    float norm = (clamped - min_v) / fmaxf(max_v - min_v, 1e-6f);
    int bin = (int)(norm * (float)bins);
    if (bin < 0) bin = 0;
    if (bin >= bins) bin = bins - 1;
    return bin;
}

static inline int anomaly_color_hist_key(int u_bin, int v_bin) {
    return u_bin * ANOMALY_COLOR_V_BINS + v_bin;
}

static inline float anomaly_color_sample_chroma_magnitude(
        const anomaly_roi_state_t *roi_state,
        size_t                     idx) {
    if (roi_state == NULL || roi_state->color_u == NULL || roi_state->color_v == NULL) {
        return 0.0f;
    }
    float u = roi_state->color_u[idx];
    float v = roi_state->color_v[idx];
    return sqrtf((u * u) + (v * v));
}

static inline void anomaly_color_fill_uv_bins(
        anomaly_roi_state_t *roi_state,
        size_t               idx) {
    if (roi_state == NULL || roi_state->color_u_bin == NULL || roi_state->color_v_bin == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL) {
        return;
    }
    roi_state->color_u_bin[idx] = (uint8_t)anomaly_color_quantize_uv_bin(
        roi_state->color_u[idx],
        ANOMALY_COLOR_DETECTOR_U_MIN,
        ANOMALY_COLOR_DETECTOR_U_MAX,
        ANOMALY_COLOR_U_BINS);
    roi_state->color_v_bin[idx] = (uint8_t)anomaly_color_quantize_uv_bin(
        roi_state->color_v[idx],
        ANOMALY_COLOR_DETECTOR_V_MIN,
        ANOMALY_COLOR_DETECTOR_V_MAX,
        ANOMALY_COLOR_V_BINS);
}

static inline float anomaly_color_score_hist_rarity(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        int            key) {
    int cur = current_hist != NULL ? (int)current_hist[key] : 0;
    int rec = recent_hist != NULL ? (int)recent_hist[key] : 0;
    return 1.0f / (float)(cur + rec + 1);
}

static inline float anomaly_color_score_hist_family_rarity(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        int            center_u_bin,
        int            center_v_bin) {
    int family = 0;
    for (int dv = -1; dv <= 1; dv++) {
        int v_bin = center_v_bin + dv;
        if (v_bin < 0 || v_bin >= ANOMALY_COLOR_V_BINS) continue;
        for (int du = -1; du <= 1; du++) {
            int u_bin = center_u_bin + du;
            if (u_bin < 0 || u_bin >= ANOMALY_COLOR_U_BINS) continue;
            int key = anomaly_color_hist_key(u_bin, v_bin);
            family += current_hist != NULL ? (int)current_hist[key] : 0;
            family += recent_hist != NULL ? (int)recent_hist[key] : 0;
        }
    }
    return 1.0f / (float)(family + 1);
}

static inline float anomaly_color_candidate_scene_commonness(
        float hist_current_count,
        float hist_recent_count,
        float max_hist_current_count,
        float max_hist_recent_count) {
    float current_commonness = max_hist_current_count > 0.0f
        ? hist_current_count / max_hist_current_count
        : 0.0f;
    float recent_commonness = max_hist_recent_count > 0.0f
        ? hist_recent_count / max_hist_recent_count
        : 0.0f;
    return anomaly_color_clampf((0.40f * current_commonness) + (0.60f * recent_commonness),
                                0.0f,
                                1.0f);
}

static inline float anomaly_color_candidate_uniqueness_rank(
        float hist_rarity,
        float scene_commonness,
        bool  fresh_frontend) {
    float rarity_anchor = fresh_frontend
        ? (ANOMALY_FRESH_COLOR_WINNER_MIN_RARITY * 4.0f)
        : ANOMALY_COLOR_RARITY_MIN;
    float rarity_rank = anomaly_color_clampf(
        hist_rarity / fmaxf(rarity_anchor, 1e-6f),
        0.0f,
        1.0f);
    float uncommon_rank = anomaly_color_clampf(
        (ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS - scene_commonness) /
        fmaxf(ANOMALY_FRESH_COLOR_WINNER_MAX_COMMONNESS, 1e-6f),
        0.0f,
        1.0f);
    return anomaly_color_clampf(
        rarity_rank * (0.18f + 0.82f * uncommon_rank),
        0.0f,
        1.0f);
}

static inline float anomaly_color_small_target_priority_scale(
        float span_cells,
        float area_cells,
        float small_target_span_cells,
        float uniqueness) {
    if (small_target_span_cells <= 0.0f) return 1.0f;
    float span_ratio = span_cells / small_target_span_cells;
    float area_ratio = area_cells / (small_target_span_cells * small_target_span_cells);
    float scale;
    if (span_ratio <= 0.65f && area_ratio <= 0.28f) scale = 1.22f;
    else if (span_ratio <= 0.95f && area_ratio <= 0.62f) scale = 1.05f;
    else if (span_ratio <= 1.10f && area_ratio <= 0.95f) scale = 0.72f;
    else if (span_ratio <= 1.30f && area_ratio <= 1.20f) scale = 0.38f;
    else scale = 0.14f;

    if (span_ratio <= 0.95f && area_ratio <= 0.62f) {
        scale *= 1.0f + 0.12f * anomaly_color_clampf((uniqueness - 0.45f) / 0.35f,
                                                       0.0f,
                                                       1.0f);
    } else if (span_ratio > 1.10f || area_ratio > 0.95f) {
        scale *= 1.0f - 0.18f * anomaly_color_clampf((1.0f - uniqueness) / 0.45f,
                                                       0.0f,
                                                       1.0f);
    }
    return anomaly_color_clampf(scale, 0.05f, 1.40f);
}

static inline int anomaly_color_local_uv_support_count(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        center_sx,
        int                        center_sy,
        int                        center_u_bin,
        int                        center_v_bin,
        int                        support_radius) {
    if (roi_state == NULL || sg_w <= 0 || sg_h <= 0 || support_radius < 0 ||
        roi_state->color_valid_mask == NULL ||
        roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL) {
        return 0;
    }

    int support = 0;
    int sx0 = center_sx - support_radius;
    int sx1 = center_sx + support_radius;
    int sy0 = center_sy - support_radius;
    int sy1 = center_sy + support_radius;
    if (sx0 < 0) sx0 = 0;
    if (sy0 < 0) sy0 = 0;
    if (sx1 >= sg_w) sx1 = sg_w - 1;
    if (sy1 >= sg_h) sy1 = sg_h - 1;

    for (int sy = sy0; sy <= sy1; sy++) {
        for (int sx = sx0; sx <= sx1; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) continue;

            int u_bin = (int)roi_state->color_u_bin[idx];
            int v_bin = (int)roi_state->color_v_bin[idx];
            if (abs(u_bin - center_u_bin) <= 1 &&
                abs(v_bin - center_v_bin) <= 1) {
                support++;
            }
        }
    }
    return support;
}

static inline float anomaly_color_blob_neighbor_similarity(
        const anomaly_roi_state_t *roi_state,
        size_t                     lhs_idx,
        size_t                     rhs_idx) {
    if (roi_state == NULL ||
        roi_state->color_valid_mask == NULL ||
        roi_state->color_u == NULL ||
        roi_state->color_v == NULL ||
        roi_state->color_luma == NULL ||
        roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL ||
        roi_state->color_valid_mask[lhs_idx] == 0u ||
        roi_state->color_valid_mask[rhs_idx] == 0u) {
        return 0.0f;
    }

    int du_bin = abs((int)roi_state->color_u_bin[lhs_idx] - (int)roi_state->color_u_bin[rhs_idx]);
    int dv_bin = abs((int)roi_state->color_v_bin[lhs_idx] - (int)roi_state->color_v_bin[rhs_idx]);
    float du = roi_state->color_u[lhs_idx] - roi_state->color_u[rhs_idx];
    float dv = roi_state->color_v[lhs_idx] - roi_state->color_v[rhs_idx];
    float dl = fabsf(roi_state->color_luma[lhs_idx] - roi_state->color_luma[rhs_idx]);
    float chroma_delta = sqrtf((du * du) + (dv * dv));
    float bin_similarity = anomaly_color_clampf(1.0f - 0.30f * (float)(du_bin + dv_bin), 0.0f, 1.0f);
    float chroma_similarity = anomaly_color_clampf((56.0f - chroma_delta) / 56.0f, 0.0f, 1.0f);
    float luma_similarity = anomaly_color_clampf((32.0f - dl) / 32.0f, 0.0f, 1.0f);
    return 0.50f * bin_similarity + 0.35f * chroma_similarity + 0.15f * luma_similarity;
}

static inline void anomaly_color_compute_blob_cohesion_weights(
        anomaly_roi_state_t *roi_state,
        int                  color_frontend_mode,
        int                  sg_w,
        int                  sg_h) {
    if (roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_contrast_weight == NULL || sg_w <= 0 || sg_h <= 0) {
        return;
    }
    if (color_frontend_mode == ANOMALY_COLOR_FRONTEND_LEGACY) {
        size_t count = (size_t)sg_w * (size_t)sg_h;
        for (size_t idx = 0; idx < count; idx++) {
            roi_state->color_contrast_weight[idx] =
                roi_state->color_valid_mask[idx] != 0u ? 1.0f : 0.0f;
        }
        return;
    }
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) {
                roi_state->color_contrast_weight[idx] = 0.0f;
                continue;
            }

            float similarity_sum = 0.0f;
            int similarity_count = 0;
            int coherent_neighbors = 0;
            for (int ny = sy - 1; ny <= sy + 1; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
                    size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
                    float similarity = anomaly_color_blob_neighbor_similarity(roi_state, idx, nidx);
                    if (similarity <= 0.0f) continue;
                    similarity_sum += similarity;
                    similarity_count++;
                    if (similarity >= 0.58f) coherent_neighbors++;
                }
            }

            if (similarity_count <= 0) {
                roi_state->color_contrast_weight[idx] = 1.0f;
                continue;
            }

            float mean_similarity = similarity_sum / (float)similarity_count;
            float coherent_bonus = anomaly_color_clampf((float)(coherent_neighbors - 1) / 4.0f, 0.0f, 1.0f);
            float cohesion = 0.80f * mean_similarity + 0.20f * coherent_bonus;
            roi_state->color_contrast_weight[idx] =
                anomaly_color_clampf(0.80f + 0.35f * cohesion, 0.70f, 1.20f);
        }
    }
}

static inline void anomaly_color_candidate_bbox_norm(
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
    if (left_out != NULL) *left_out = anomaly_color_clampf(left, 0.0f, 1.0f);
    if (top_out != NULL) *top_out = anomaly_color_clampf(top, 0.0f, 1.0f);
    if (right_out != NULL) *right_out = anomaly_color_clampf(right, 0.0f, 1.0f);
    if (bottom_out != NULL) *bottom_out = anomaly_color_clampf(bottom, 0.0f, 1.0f);
}

typedef struct {
    int   patch_valid_count;
    int   coherent_patch_cell_count;
    int   coherent_patch_fresh_cell_count;
    bool  coherent_patch_multicell;
    float patch_mean_u;
    float patch_mean_v;
    float patch_mean_luma;
    float ring_mean_u;
    float ring_mean_v;
    float ring_mean_luma;
    float ring_chroma_contrast;
    float ring_luma_contrast;
    int   ring_neighbor_count;
} anomaly_color_target_telemetry_t;

bool anomaly_color_hist_ensure_capacity(uint8_t **buffer, size_t *capacity_bins);

int anomaly_color_build_frame_histogram(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        uint8_t                   *hist_out);

void anomaly_color_build_family_rarity_lut(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        float         *rarity_out);

float anomaly_color_history_recent_scale_for_recovery(int recovery_frames_remaining);

void anomaly_color_update_recent_histogram(
        uint8_t       *recent_hist,
        const uint8_t *current_hist,
        bool           reset_history,
        int            current_shift);

float anomaly_color_default_fresh_distinctness_ratio(void);

float anomaly_color_clamp_fresh_distinctness_ratio(float ratio);

void anomaly_color_compute_local_contrast(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        sx,
        int                        sy,
        float                     *avg_chroma_out,
        float                     *avg_luma_out,
        int                       *neighbor_count_out);

void anomaly_color_compute_ring_contrast(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        sx,
        int                        sy,
        int                        inner_radius,
        int                        outer_radius,
        float                     *avg_chroma_out,
        float                     *avg_luma_out,
        int                       *neighbor_count_out);

static inline float anomaly_color_score_contrast_rescue(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        sx,
        int                        sy,
        bool                       sampled_this_frame,
        int                        local_support) {
    if (roi_state == NULL ||
        !sampled_this_frame ||
        local_support < ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) {
        return 0.0f;
    }

    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float center_chroma = anomaly_color_sample_chroma_magnitude(roi_state, idx);

    float avg_chroma = 0.0f;
    float avg_luma = 0.0f;
    int neighbor_count = 0;
    anomaly_color_compute_ring_contrast(
            roi_state,
            sg_w,
            sg_h,
            sx,
            sy,
            1,
            3,
            &avg_chroma,
            &avg_luma,
            &neighbor_count);
    if (neighbor_count < ANOMALY_COLOR_CONTRAST_MIN_TOTAL_NEIGHBORS) {
        return 0.0f;
    }
    // Keep luma-only textured grayscale from being promoted into color seeds.
    if (center_chroma < 8.0f && avg_chroma < 6.0f) {
        return 0.0f;
    }

    float chroma_strength = anomaly_color_clampf(
        (avg_chroma - ANOMALY_COLOR_CONTRAST_CHROMA_SOFT) /
        (ANOMALY_COLOR_CONTRAST_CHROMA_HARD - ANOMALY_COLOR_CONTRAST_CHROMA_SOFT),
        0.0f,
        1.0f);
    float luma_strength = anomaly_color_clampf(
        (avg_luma - ANOMALY_COLOR_CONTRAST_LUMA_SOFT) /
        (ANOMALY_COLOR_CONTRAST_LUMA_HARD - ANOMALY_COLOR_CONTRAST_LUMA_SOFT),
        0.0f,
        1.0f);
    float contrast_weight = 1.0f + 0.45f * (0.65f * chroma_strength + 0.35f * luma_strength);
    if (contrast_weight < ANOMALY_COLOR_CONTRAST_RESCUE_MIN) return 0.0f;
    float contrast_strength = anomaly_color_clampf(
        (contrast_weight - ANOMALY_COLOR_CONTRAST_RESCUE_MIN) /
        (1.40f - ANOMALY_COLOR_CONTRAST_RESCUE_MIN),
        0.0f,
        1.0f);
    float support_strength = anomaly_color_clampf(
        (float)(local_support - ANOMALY_COLOR_RESCUE_LOCAL_SUPPORT_MIN) / 4.0f,
        0.0f,
        1.0f);
    float strength = 0.75f * contrast_strength + 0.25f * support_strength;
    return ANOMALY_COLOR_RESCUE_SCORE_BASE + ANOMALY_COLOR_RESCUE_SCORE_RANGE * strength;
}

void anomaly_color_compute_target_telemetry(
        const anomaly_roi_state_t        *roi_state,
        int                               sg_w,
        int                               sg_h,
        int                               sx,
        int                               sy,
        int                               inner_radius,
        int                               outer_radius,
        bool                              full_refresh,
        const uint8_t                    *refresh_mask,
        anomaly_color_target_telemetry_t *telemetry_out);

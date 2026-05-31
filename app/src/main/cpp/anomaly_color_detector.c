#include "anomaly_color_detector.h"

#include <stdlib.h>
#include <string.h>

bool anomaly_color_hist_ensure_capacity(uint8_t **buffer, size_t *capacity_bins) {
    if (buffer == NULL || capacity_bins == NULL) return false;
    if (*buffer != NULL && *capacity_bins >= ANOMALY_COLOR_HIST_BINS) return true;
    size_t old_bins = *capacity_bins;
    uint8_t *grown = (uint8_t *)realloc(*buffer, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
    if (grown == NULL) return false;
    if (ANOMALY_COLOR_HIST_BINS > old_bins) {
        memset(grown + old_bins, 0, (ANOMALY_COLOR_HIST_BINS - old_bins) * sizeof(uint8_t));
    }
    *buffer = grown;
    *capacity_bins = ANOMALY_COLOR_HIST_BINS;
    return true;
}

int anomaly_color_build_frame_histogram(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        uint8_t                   *hist_out) {
    if (roi_state == NULL || hist_out == NULL || sg_w <= 0 || sg_h <= 0 ||
        roi_state->color_valid_mask == NULL || roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL) {
        return 0;
    }

    memset(hist_out, 0, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
    int valid_samples = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u) continue;

            int u_bin = (int)roi_state->color_u_bin[idx];
            int v_bin = (int)roi_state->color_v_bin[idx];
            int key = anomaly_color_hist_key(u_bin, v_bin);
            if (hist_out[key] < 255u) hist_out[key] += 1u;
            valid_samples++;
        }
    }
    return valid_samples;
}

void anomaly_color_build_family_rarity_lut(
        const uint8_t *current_hist,
        const uint8_t *recent_hist,
        float         *rarity_out) {
    if (rarity_out == NULL) return;
    for (int v_bin = 0; v_bin < ANOMALY_COLOR_V_BINS; v_bin++) {
        for (int u_bin = 0; u_bin < ANOMALY_COLOR_U_BINS; u_bin++) {
            int key = anomaly_color_hist_key(u_bin, v_bin);
            rarity_out[key] = anomaly_color_score_hist_family_rarity(
                current_hist,
                recent_hist,
                u_bin,
                v_bin);
        }
    }
}

float anomaly_color_history_recent_scale_for_recovery(int recovery_frames_remaining) {
    if (recovery_frames_remaining <= 0) return 1.0f;
    if (recovery_frames_remaining >= ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES) return 0.0f;
    int warmed_frames = ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES - recovery_frames_remaining;
    if (warmed_frames <= 0) return 0.0f;
    if (warmed_frames >= ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES) return 1.0f;
    return (float)warmed_frames / (float)ANOMALY_COLOR_HISTORY_RECOVERY_FRAMES;
}

void anomaly_color_update_recent_histogram(
        uint8_t       *recent_hist,
        const uint8_t *current_hist,
        bool           reset_history,
        int            current_shift) {
    if (recent_hist == NULL || current_hist == NULL) return;

    if (current_shift < 0) current_shift = 0;
    if (current_shift > 7) current_shift = 7;

    if (reset_history) {
        memset(recent_hist, 0, ANOMALY_COLOR_HIST_BINS * sizeof(uint8_t));
    }

    for (int i = 0; i < ANOMALY_COLOR_HIST_BINS; i++) {
        uint16_t decayed = reset_history
            ? 0u
            : (uint16_t)(recent_hist[i] >> ANOMALY_COLOR_HISTORY_DECAY_SHIFT);
        uint16_t contribution = (uint16_t)(current_hist[i] >> current_shift);
        if (current_hist[i] > 0u && contribution == 0u) contribution = 1u;
        uint16_t combined = decayed + contribution;
        recent_hist[i] = (uint8_t)(combined > 255u ? 255u : combined);
    }
}

float anomaly_color_default_fresh_distinctness_ratio(void) {
    return ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_DEFAULT;
}

float anomaly_color_clamp_fresh_distinctness_ratio(float ratio) {
    return anomaly_color_clampf(
        ratio,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MIN,
        ANOMALY_FRESH_COLOR_DISTINCTNESS_RATIO_MAX);
}

void anomaly_color_compute_local_contrast(
        const anomaly_roi_state_t *roi_state,
        int                        sg_w,
        int                        sg_h,
        int                        sx,
        int                        sy,
        float                     *avg_chroma_out,
        float                     *avg_luma_out,
        int                       *neighbor_count_out) {
    if (avg_chroma_out != NULL) *avg_chroma_out = 0.0f;
    if (avg_luma_out != NULL) *avg_luma_out = 0.0f;
    if (neighbor_count_out != NULL) *neighbor_count_out = 0;
    if (roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL ||
        roi_state->color_luma == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h) {
        return;
    }
    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    if (roi_state->color_valid_mask[idx] == 0u) return;

    float center_u = roi_state->color_u[idx];
    float center_v = roi_state->color_v[idx];
    float center_luma = roi_state->color_luma[idx];
    float chroma_sum = 0.0f;
    float luma_sum = 0.0f;
    int neighbor_count = 0;
    for (int ny = sy - 1; ny <= sy + 1; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - 1; nx <= sx + 1; nx++) {
            if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            if (roi_state->color_valid_mask[nidx] == 0u) continue;
            float du = center_u - roi_state->color_u[nidx];
            float dv = center_v - roi_state->color_v[nidx];
            chroma_sum += sqrtf((du * du) + (dv * dv));
            luma_sum += fabsf(center_luma - roi_state->color_luma[nidx]);
            neighbor_count++;
        }
    }
    if (neighbor_count <= 0) return;
    if (avg_chroma_out != NULL) *avg_chroma_out = chroma_sum / (float)neighbor_count;
    if (avg_luma_out != NULL) *avg_luma_out = luma_sum / (float)neighbor_count;
    if (neighbor_count_out != NULL) *neighbor_count_out = neighbor_count;
}

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
        int                       *neighbor_count_out) {
    if (avg_chroma_out != NULL) *avg_chroma_out = 0.0f;
    if (avg_luma_out != NULL) *avg_luma_out = 0.0f;
    if (neighbor_count_out != NULL) *neighbor_count_out = 0;
    if (roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL ||
        roi_state->color_luma == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h ||
        outer_radius <= inner_radius) {
        return;
    }
    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    if (roi_state->color_valid_mask[idx] == 0u) return;

    float center_u = roi_state->color_u[idx];
    float center_v = roi_state->color_v[idx];
    float center_luma = roi_state->color_luma[idx];
    float chroma_sum = 0.0f;
    float luma_sum = 0.0f;
    int neighbor_count = 0;
    for (int ny = sy - outer_radius; ny <= sy + outer_radius; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - outer_radius; nx <= sx + outer_radius; nx++) {
            if (nx < 0 || nx >= sg_w || (nx == sx && ny == sy)) continue;
            int chebyshev = abs(nx - sx);
            int dy = abs(ny - sy);
            if (dy > chebyshev) chebyshev = dy;
            if (chebyshev <= inner_radius || chebyshev > outer_radius) continue;
            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            if (roi_state->color_valid_mask[nidx] == 0u) continue;
            float du = center_u - roi_state->color_u[nidx];
            float dv = center_v - roi_state->color_v[nidx];
            chroma_sum += sqrtf((du * du) + (dv * dv));
            luma_sum += fabsf(center_luma - roi_state->color_luma[nidx]);
            neighbor_count++;
        }
    }
    if (neighbor_count <= 0) return;
    if (avg_chroma_out != NULL) *avg_chroma_out = chroma_sum / (float)neighbor_count;
    if (avg_luma_out != NULL) *avg_luma_out = luma_sum / (float)neighbor_count;
    if (neighbor_count_out != NULL) *neighbor_count_out = neighbor_count;
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
        anomaly_color_target_telemetry_t *telemetry_out) {
    if (telemetry_out != NULL) memset(telemetry_out, 0, sizeof(*telemetry_out));
    if (telemetry_out == NULL ||
        roi_state == NULL || roi_state->color_valid_mask == NULL ||
        roi_state->color_u == NULL || roi_state->color_v == NULL ||
        roi_state->color_luma == NULL || roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL ||
        sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sy < 0 || sx >= sg_w || sy >= sg_h ||
        inner_radius < 0 || outer_radius <= inner_radius) {
        return;
    }

    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    if (roi_state->color_valid_mask[idx] == 0u) return;

    const float center_u = roi_state->color_u[idx];
    const float center_v = roi_state->color_v[idx];
    const float center_luma = roi_state->color_luma[idx];
    const int center_u_bin = (int)roi_state->color_u_bin[idx];
    const int center_v_bin = (int)roi_state->color_v_bin[idx];

    float patch_u_sum = 0.0f;
    float patch_v_sum = 0.0f;
    float patch_luma_sum = 0.0f;
    float ring_u_sum = 0.0f;
    float ring_v_sum = 0.0f;
    float ring_luma_sum = 0.0f;
    int patch_count = 0;
    int ring_count = 0;
    int coherent_count = 0;
    int coherent_fresh_count = 0;

    for (int ny = sy - outer_radius; ny <= sy + outer_radius; ny++) {
        if (ny < 0 || ny >= sg_h) continue;
        for (int nx = sx - outer_radius; nx <= sx + outer_radius; nx++) {
            if (nx < 0 || nx >= sg_w) continue;
            int chebyshev = abs(nx - sx);
            int dy = abs(ny - sy);
            if (dy > chebyshev) chebyshev = dy;
            if (chebyshev > outer_radius) continue;

            size_t nidx = (size_t)ny * (size_t)sg_w + (size_t)nx;
            if (roi_state->color_valid_mask[nidx] == 0u) continue;

            if (chebyshev <= inner_radius) {
                patch_u_sum += roi_state->color_u[nidx];
                patch_v_sum += roi_state->color_v[nidx];
                patch_luma_sum += roi_state->color_luma[nidx];
                patch_count++;
                int u_bin = (int)roi_state->color_u_bin[nidx];
                int v_bin = (int)roi_state->color_v_bin[nidx];
                if (abs(u_bin - center_u_bin) <= 1 &&
                    abs(v_bin - center_v_bin) <= 1) {
                    coherent_count++;
                    bool fresh_now = full_refresh;
                    if (!fresh_now && refresh_mask != NULL && refresh_mask[nidx] != 0u) {
                        fresh_now = true;
                    }
                    if (!fresh_now &&
                        refresh_mask == NULL &&
                        roi_state->fresh_mask != NULL &&
                        roi_state->fresh_mask[nidx] != 0u) {
                        fresh_now = true;
                    }
                    if (fresh_now) {
                        coherent_fresh_count++;
                    }
                }
            } else {
                ring_u_sum += roi_state->color_u[nidx];
                ring_v_sum += roi_state->color_v[nidx];
                ring_luma_sum += roi_state->color_luma[nidx];
                ring_count++;
            }
        }
    }

    telemetry_out->patch_valid_count = patch_count;
    telemetry_out->coherent_patch_cell_count = coherent_count;
    telemetry_out->coherent_patch_fresh_cell_count = coherent_fresh_count;
    telemetry_out->coherent_patch_multicell = coherent_count > 1;
    telemetry_out->ring_neighbor_count = ring_count;

    if (patch_count > 0) {
        telemetry_out->patch_mean_u = patch_u_sum / (float)patch_count;
        telemetry_out->patch_mean_v = patch_v_sum / (float)patch_count;
        telemetry_out->patch_mean_luma = patch_luma_sum / (float)patch_count;
    } else {
        telemetry_out->patch_mean_u = center_u;
        telemetry_out->patch_mean_v = center_v;
        telemetry_out->patch_mean_luma = center_luma;
    }

    if (ring_count > 0) {
        telemetry_out->ring_mean_u = ring_u_sum / (float)ring_count;
        telemetry_out->ring_mean_v = ring_v_sum / (float)ring_count;
        telemetry_out->ring_mean_luma = ring_luma_sum / (float)ring_count;
        float du = telemetry_out->patch_mean_u - telemetry_out->ring_mean_u;
        float dv = telemetry_out->patch_mean_v - telemetry_out->ring_mean_v;
        telemetry_out->ring_chroma_contrast = sqrtf((du * du) + (dv * dv));
        telemetry_out->ring_luma_contrast =
            fabsf(telemetry_out->patch_mean_luma - telemetry_out->ring_mean_luma);
    }
}

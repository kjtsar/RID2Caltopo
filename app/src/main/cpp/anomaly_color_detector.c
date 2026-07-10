#include "anomaly_color_detector.h"

#include <stdlib.h>
#include <string.h>

static void anomaly_color_reset_blob_signature(anomaly_color_blob_signature_t *signature) {
    if (signature == NULL) return;
    memset(signature, 0, sizeof(*signature));
    signature->predominant_u_bin = -1;
    signature->predominant_v_bin = -1;
}

static void anomaly_color_finalize_blob_signature(anomaly_color_blob_signature_t *signature) {
    if (signature == NULL || signature->sample_count == 0u) return;

    uint64_t best_family_count = 0u;
    for (int u_bin = 0; u_bin < ANOMALY_COLOR_U_BINS; u_bin++) {
        for (int v_bin = 0; v_bin < ANOMALY_COLOR_V_BINS; v_bin++) {
            if (signature->histogram[anomaly_color_hist_key(u_bin, v_bin)] == 0u) continue;
            uint64_t family_count = 0u;
            for (int du = -1; du <= 1; du++) {
                int family_u = u_bin + du;
                if (family_u < 0 || family_u >= ANOMALY_COLOR_U_BINS) continue;
                for (int dv = -1; dv <= 1; dv++) {
                    int family_v = v_bin + dv;
                    if (family_v < 0 || family_v >= ANOMALY_COLOR_V_BINS) continue;
                    family_count += signature->histogram[
                        anomaly_color_hist_key(family_u, family_v)];
                }
            }
            if (family_count > best_family_count) {
                best_family_count = family_count;
                signature->predominant_u_bin = u_bin;
                signature->predominant_v_bin = v_bin;
            }
        }
    }

    signature->predominant_family_count =
        best_family_count > UINT32_MAX ? UINT32_MAX : (uint32_t)best_family_count;
    signature->predominant_family_share =
        (float)best_family_count / (float)signature->sample_count;

    double entropy = 0.0;
    for (int key = 0; key < ANOMALY_COLOR_HIST_BINS; key++) {
        uint32_t count = signature->histogram[key];
        if (count == 0u) continue;
        double probability = (double)count / (double)signature->sample_count;
        entropy -= probability * log(probability);
    }
    signature->normalized_entropy =
        (float)(entropy / log((double)ANOMALY_COLOR_HIST_BINS));
    signature->normalized_entropy =
        anomaly_color_clampf(signature->normalized_entropy, 0.0f, 1.0f);
    signature->purity = anomaly_color_clampf(
        signature->predominant_family_share * (1.0f - signature->normalized_entropy),
        0.0f,
        1.0f);
}

bool anomaly_color_build_blob_signature(
        const uint8_t                  *u_bins,
        const uint8_t                  *v_bins,
        const uint8_t                  *include_mask,
        size_t                          sample_slots,
        anomaly_color_blob_signature_t *signature_out) {
    if (signature_out == NULL) return false;
    anomaly_color_reset_blob_signature(signature_out);
    if (sample_slots == 0u) return true;
    if (u_bins == NULL || v_bins == NULL) return false;

    for (size_t i = 0; i < sample_slots; i++) {
        if (include_mask != NULL && include_mask[i] == 0u) continue;
        int u_bin = (int)u_bins[i];
        int v_bin = (int)v_bins[i];
        if (u_bin < 0 || u_bin >= ANOMALY_COLOR_U_BINS ||
            v_bin < 0 || v_bin >= ANOMALY_COLOR_V_BINS) {
            continue;
        }
        int key = anomaly_color_hist_key(u_bin, v_bin);
        if (signature_out->histogram[key] < UINT32_MAX) {
            signature_out->histogram[key]++;
        }
        if (signature_out->sample_count < UINT32_MAX) {
            signature_out->sample_count++;
        }
    }
    anomaly_color_finalize_blob_signature(signature_out);
    return true;
}

float anomaly_color_candidate_excluded_family_rarity(
        const uint32_t                       *current_hist,
        const uint32_t                       *recent_hist,
        const anomaly_color_blob_signature_t *current_candidate,
        const anomaly_color_blob_signature_t *recent_candidate,
        int                                   center_u_bin,
        int                                   center_v_bin) {
    if (center_u_bin < 0 || center_u_bin >= ANOMALY_COLOR_U_BINS ||
        center_v_bin < 0 || center_v_bin >= ANOMALY_COLOR_V_BINS) {
        return 0.0f;
    }

    uint64_t family_count = 0u;
    for (int du = -1; du <= 1; du++) {
        int u_bin = center_u_bin + du;
        if (u_bin < 0 || u_bin >= ANOMALY_COLOR_U_BINS) continue;
        for (int dv = -1; dv <= 1; dv++) {
            int v_bin = center_v_bin + dv;
            if (v_bin < 0 || v_bin >= ANOMALY_COLOR_V_BINS) continue;
            int key = anomaly_color_hist_key(u_bin, v_bin);
            uint32_t current_count = current_hist != NULL ? current_hist[key] : 0u;
            uint32_t recent_count = recent_hist != NULL ? recent_hist[key] : 0u;
            uint32_t current_subtract = current_candidate != NULL
                ? current_candidate->histogram[key]
                : 0u;
            uint32_t recent_subtract = recent_candidate != NULL
                ? recent_candidate->histogram[key]
                : 0u;
            family_count += current_count > current_subtract
                ? (uint64_t)(current_count - current_subtract)
                : 0u;
            family_count += recent_count > recent_subtract
                ? (uint64_t)(recent_count - recent_subtract)
                : 0u;
        }
    }
    return 1.0f / (float)(family_count + 1u);
}

bool anomaly_color_build_local_ring_signature(
        const anomaly_roi_state_t      *roi_state,
        int                             sg_w,
        int                             sg_h,
        int                             bbox_min_x,
        int                             bbox_min_y,
        int                             bbox_max_x,
        int                             bbox_max_y,
        int                             ring_width_cells,
        const uint8_t                  *include_mask,
        const uint8_t                  *exclude_mask,
        anomaly_color_blob_signature_t *signature_out) {
    if (signature_out == NULL) return false;
    anomaly_color_reset_blob_signature(signature_out);
    if (roi_state == NULL || sg_w <= 0 || sg_h <= 0 || ring_width_cells <= 0 ||
        bbox_min_x > bbox_max_x || bbox_min_y > bbox_max_y ||
        bbox_max_x < 0 || bbox_max_y < 0 || bbox_min_x >= sg_w || bbox_min_y >= sg_h ||
        roi_state->color_valid_mask == NULL || roi_state->color_u_bin == NULL ||
        roi_state->color_v_bin == NULL) {
        return false;
    }

    int x0 = bbox_min_x < 0 ? 0 : bbox_min_x;
    int y0 = bbox_min_y < 0 ? 0 : bbox_min_y;
    int x1 = bbox_max_x >= sg_w ? sg_w - 1 : bbox_max_x;
    int y1 = bbox_max_y >= sg_h ? sg_h - 1 : bbox_max_y;
    int64_t ring_x0_wide = (int64_t)x0 - (int64_t)ring_width_cells;
    int64_t ring_y0_wide = (int64_t)y0 - (int64_t)ring_width_cells;
    int64_t ring_x1_wide = (int64_t)x1 + (int64_t)ring_width_cells;
    int64_t ring_y1_wide = (int64_t)y1 + (int64_t)ring_width_cells;
    int ring_x0 = ring_x0_wide < 0 ? 0 : (int)ring_x0_wide;
    int ring_y0 = ring_y0_wide < 0 ? 0 : (int)ring_y0_wide;
    int ring_x1 = ring_x1_wide >= sg_w ? sg_w - 1 : (int)ring_x1_wide;
    int ring_y1 = ring_y1_wide >= sg_h ? sg_h - 1 : (int)ring_y1_wide;

    for (int sy = ring_y0; sy <= ring_y1; sy++) {
        for (int sx = ring_x0; sx <= ring_x1; sx++) {
            if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) continue;
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (roi_state->color_valid_mask[idx] == 0u ||
                (include_mask != NULL && include_mask[idx] == 0u) ||
                (exclude_mask != NULL && exclude_mask[idx] != 0u)) {
                continue;
            }
            int u_bin = (int)roi_state->color_u_bin[idx];
            int v_bin = (int)roi_state->color_v_bin[idx];
            if (u_bin < 0 || u_bin >= ANOMALY_COLOR_U_BINS ||
                v_bin < 0 || v_bin >= ANOMALY_COLOR_V_BINS) {
                continue;
            }
            int key = anomaly_color_hist_key(u_bin, v_bin);
            if (signature_out->histogram[key] < UINT32_MAX) {
                signature_out->histogram[key]++;
            }
            if (signature_out->sample_count < UINT32_MAX) signature_out->sample_count++;
        }
    }
    anomaly_color_finalize_blob_signature(signature_out);
    return true;
}

static float anomaly_color_smoothed_signature_bin(
        const anomaly_color_blob_signature_t *signature,
        int                                   out_u,
        int                                   out_v) {
    float weighted_count = 0.0f;
    for (int du = -1; du <= 1; du++) {
        int src_u = out_u + du;
        if (src_u < 0 || src_u >= ANOMALY_COLOR_U_BINS) continue;
        for (int dv = -1; dv <= 1; dv++) {
            int src_v = out_v + dv;
            if (src_v < 0 || src_v >= ANOMALY_COLOR_V_BINS) continue;
            int weight = (du == 0 && dv == 0) ? 4 : ((du == 0 || dv == 0) ? 2 : 1);
            weighted_count += (float)weight *
                (float)signature->histogram[anomaly_color_hist_key(src_u, src_v)];
        }
    }
    return weighted_count;
}

float anomaly_color_signature_divergence(
        const anomaly_color_blob_signature_t *blob_signature,
        const anomaly_color_blob_signature_t *ring_signature) {
    if (blob_signature == NULL || ring_signature == NULL ||
        blob_signature->sample_count == 0u || ring_signature->sample_count == 0u) {
        return 0.0f;
    }

    float blob_smoothed[ANOMALY_COLOR_HIST_BINS];
    float ring_smoothed[ANOMALY_COLOR_HIST_BINS];
    float blob_mass = 0.0f;
    float ring_mass = 0.0f;
    for (int u_bin = 0; u_bin < ANOMALY_COLOR_U_BINS; u_bin++) {
        for (int v_bin = 0; v_bin < ANOMALY_COLOR_V_BINS; v_bin++) {
            int key = anomaly_color_hist_key(u_bin, v_bin);
            blob_smoothed[key] = anomaly_color_smoothed_signature_bin(
                blob_signature, u_bin, v_bin);
            ring_smoothed[key] = anomaly_color_smoothed_signature_bin(
                ring_signature, u_bin, v_bin);
            blob_mass += blob_smoothed[key];
            ring_mass += ring_smoothed[key];
        }
    }
    if (blob_mass <= 0.0f || ring_mass <= 0.0f) return 0.0f;

    float total_variation = 0.0f;
    for (int key = 0; key < ANOMALY_COLOR_HIST_BINS; key++) {
        float blob_probability = blob_smoothed[key] / blob_mass;
        float ring_probability = ring_smoothed[key] / ring_mass;
        total_variation += fabsf(blob_probability - ring_probability);
    }
    total_variation *= 0.5f;

    uint32_t evidence_samples = blob_signature->sample_count < ring_signature->sample_count
        ? blob_signature->sample_count
        : ring_signature->sample_count;
    float evidence_confidence = anomaly_color_clampf(
        (float)evidence_samples / (float)ANOMALY_COLOR_DIVERGENCE_FULL_CONFIDENCE_SAMPLES,
        0.0f,
        1.0f);
    return anomaly_color_clampf(total_variation * evidence_confidence, 0.0f, 1.0f);
}

float anomaly_color_signature_similarity(
        const anomaly_color_blob_signature_t *first_signature,
        const anomaly_color_blob_signature_t *second_signature) {
    if (first_signature == NULL || second_signature == NULL ||
        first_signature->sample_count == 0u || second_signature->sample_count == 0u) {
        return 1.0f;
    }
    return anomaly_color_clampf(
        1.0f - anomaly_color_signature_divergence(first_signature, second_signature),
        0.0f,
        1.0f);
}

float anomaly_color_signature_chroma_reliability(
        const anomaly_color_blob_signature_t *signature) {
    if (signature == NULL || signature->sample_count == 0u) return 0.0f;

    const float u_step = (ANOMALY_COLOR_DETECTOR_U_MAX - ANOMALY_COLOR_DETECTOR_U_MIN) /
        (float)ANOMALY_COLOR_U_BINS;
    const float v_step = (ANOMALY_COLOR_DETECTOR_V_MAX - ANOMALY_COLOR_DETECTOR_V_MIN) /
        (float)ANOMALY_COLOR_V_BINS;
    const float bin_diagonal = sqrtf(u_step * u_step + v_step * v_step);
    double weighted_u = 0.0;
    double weighted_v = 0.0;
    uint64_t histogram_samples = 0u;
    for (int u_bin = 0; u_bin < ANOMALY_COLOR_U_BINS; u_bin++) {
        float u = ANOMALY_COLOR_DETECTOR_U_MIN + ((float)u_bin + 0.5f) * u_step;
        for (int v_bin = 0; v_bin < ANOMALY_COLOR_V_BINS; v_bin++) {
            uint32_t count = signature->histogram[anomaly_color_hist_key(u_bin, v_bin)];
            if (count == 0u) continue;
            float v = ANOMALY_COLOR_DETECTOR_V_MIN + ((float)v_bin + 0.5f) * v_step;
            weighted_u += (double)count * (double)u;
            weighted_v += (double)count * (double)v;
            histogram_samples += count;
        }
    }
    if (histogram_samples == 0u) return 0.0f;

    float mean_u = (float)(weighted_u / (double)histogram_samples);
    float mean_v = (float)(weighted_v / (double)histogram_samples);
    float centroid_chroma = sqrtf(mean_u * mean_u + mean_v * mean_v);
    float neutral_gate = anomaly_color_clampf(
        (centroid_chroma - 0.5f * bin_diagonal) / (2.0f * bin_diagonal),
        0.0f,
        1.0f);

    double variance_sum = 0.0;
    for (int u_bin = 0; u_bin < ANOMALY_COLOR_U_BINS; u_bin++) {
        float u = ANOMALY_COLOR_DETECTOR_U_MIN + ((float)u_bin + 0.5f) * u_step;
        for (int v_bin = 0; v_bin < ANOMALY_COLOR_V_BINS; v_bin++) {
            uint32_t count = signature->histogram[anomaly_color_hist_key(u_bin, v_bin)];
            if (count == 0u) continue;
            float v = ANOMALY_COLOR_DETECTOR_V_MIN + ((float)v_bin + 0.5f) * v_step;
            float du = u - mean_u;
            float dv = v - mean_v;
            variance_sum += (double)count * (double)(du * du + dv * dv);
        }
    }
    float dispersion = sqrtf((float)(variance_sum / (double)histogram_samples));
    float coherence = 1.0f /
        (1.0f + dispersion / fmaxf(centroid_chroma, bin_diagonal));
    return anomaly_color_clampf(neutral_gate * coherence, 0.0f, 1.0f);
}

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

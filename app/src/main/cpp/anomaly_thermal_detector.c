// Pure Thermal/IR helper implementations.
#include "anomaly_thermal_detector.h"

#include <math.h>
#include <stdlib.h>

void compute_thermal_spatial_probe_at_sample(
        const float *sg_luma,
        int sg_w,
        int sg_h,
        int sx,
        int sy,
        int sample_step,
        bool black_hot,
        float *abs_delta_out,
        float *std_out,
        float *score_out) {
    if (abs_delta_out != NULL) *abs_delta_out = -1.0f;
    if (std_out != NULL) *std_out = -1.0f;
    if (score_out != NULL) *score_out = -1.0f;
    if (sg_luma == NULL || sg_w <= 0 || sg_h <= 0 ||
        sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return;
    }

    int radius = effective_thermal_window_radius_cells(sample_step);
    int wx0 = sx - radius; if (wx0 < 0) wx0 = 0;
    int wx1 = sx + radius; if (wx1 >= sg_w) wx1 = sg_w - 1;
    int wy0 = sy - radius; if (wy0 < 0) wy0 = 0;
    int wy1 = sy + radius; if (wy1 >= sg_h) wy1 = sg_h - 1;
    double sum = 0.0;
    double sum2 = 0.0;
    int count = 0;
    for (int ny = wy0; ny <= wy1; ny++) {
        for (int nx = wx0; nx <= wx1; nx++) {
            float v = sg_luma[(size_t)ny * (size_t)sg_w + (size_t)nx];
            sum += (double)v;
            sum2 += (double)v * (double)v;
            count++;
        }
    }
    if (count <= 0) return;
    float mean = (float)(sum / (double)count);
    double variance = fmax(sum2 / (double)count - (double)mean * (double)mean, 1.0);
    float std = (float)sqrt(variance);
    float lum = sg_luma[(size_t)sy * (size_t)sg_w + (size_t)sx];
    float abs_delta = black_hot ? (mean - lum) : (lum - mean);
    if (abs_delta_out != NULL) *abs_delta_out = abs_delta;
    if (std_out != NULL) *std_out = std;
    if (score_out != NULL) *score_out = abs_delta / std;
}

anomaly_thermal_temporal_stats_t anomaly_thermal_compute_temporal_stats(
        float       *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         black_hot,
        float        thermal_min_delta,
        float        norm_floor) {
    anomaly_thermal_temporal_stats_t stats = {
        .valid = false,
        .delta_mean = 0.0f,
        .delta_norm = norm_floor,
        .frame_blob_contrast_mean = 0.0f,
        .frame_blob_contrast_std = 0.0f,
        .positive_delta_count = 0,
    };
    if (bg_luma == NULL || sg_luma == NULL || sg_w <= 0 || sg_h <= 0) {
        return stats;
    }

    double sum_d = 0.0;
    double sum_d2 = 0.0;
    int count_d = 0;
    int sg_count = sg_w * sg_h;
    for (int i = 0; i < sg_count; i++) {
        float d = black_hot
            ? (bg_luma[i] - sg_luma[i])
            : (sg_luma[i] - bg_luma[i]);
        if (thermal_delta_map != NULL) thermal_delta_map[i] = d;
        if (d > 0.0f) {
            sum_d += (double)d;
            sum_d2 += (double)d * (double)d;
            count_d++;
        }
    }

    stats.valid = true;
    stats.positive_delta_count = count_d;
    stats.delta_mean = count_d > 0 ? (float)(sum_d / (double)count_d) : 0.0f;
    double delta_var = count_d > 1
        ? fmax(sum_d2 / (double)count_d -
               (double)stats.delta_mean * (double)stats.delta_mean,
               0.0)
        : 0.0;
    stats.delta_norm = (float)sqrt(delta_var);
    if (stats.delta_norm < norm_floor) {
        stats.delta_norm = norm_floor;
    }

    estimate_framewide_blob_contrast_stats(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            sg_w,
            sg_h,
            true,
            black_hot,
            thermal_min_delta,
            &stats.frame_blob_contrast_mean,
            &stats.frame_blob_contrast_std);
    return stats;
}

void estimate_framewide_blob_contrast_stats(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float       *contrast_mean_out,
        float       *contrast_std_out) {
    if (contrast_mean_out != NULL) *contrast_mean_out = 0.0f;
    if (contrast_std_out != NULL) *contrast_std_out = 0.0f;
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0) return;

    double sum = 0.0;
    double sum2 = 0.0;
    int count = 0;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            float delta0 = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (delta0 < thermal_min_delta) continue;
            if (sx + 1 < sg_w) {
                size_t nidx = idx + 1u;
                float delta1 = thermal_delta_map != NULL
                    ? thermal_delta_map[nidx]
                    : thermal_delta_from_maps(
                        thermal_delta_map,
                        bg_luma,
                        sg_luma,
                        nidx,
                        black_hot);
                if (delta1 >= thermal_min_delta) {
                    float hi = delta0 > delta1 ? delta0 : delta1;
                    float lo = delta0 > delta1 ? delta1 : delta0;
                    float ratio = lo / fmaxf(hi, 1.0f);
                    if (ratio >= 0.72f) {
                        float diff = fabsf(delta0 - delta1);
                        sum += (double)diff;
                        sum2 += (double)diff * (double)diff;
                        count++;
                    }
                }
            }
            if (sy + 1 < sg_h) {
                size_t nidx = idx + (size_t)sg_w;
                float delta1 = thermal_delta_map != NULL
                    ? thermal_delta_map[nidx]
                    : thermal_delta_from_maps(
                        thermal_delta_map,
                        bg_luma,
                        sg_luma,
                        nidx,
                        black_hot);
                if (delta1 >= thermal_min_delta) {
                    float hi = delta0 > delta1 ? delta0 : delta1;
                    float lo = delta0 > delta1 ? delta1 : delta0;
                    float ratio = lo / fmaxf(hi, 1.0f);
                    if (ratio >= 0.72f) {
                        float diff = fabsf(delta0 - delta1);
                        sum += (double)diff;
                        sum2 += (double)diff * (double)diff;
                        count++;
                    }
                }
            }
        }
    }
    if (count > 0) {
        float mean = (float)(sum / (double)count);
        float var = count > 1
            ? (float)fmax(sum2 / (double)count - (double)mean * (double)mean, 0.0)
            : 0.0f;
        if (contrast_mean_out != NULL) *contrast_mean_out = mean;
        if (contrast_std_out != NULL) *contrast_std_out = sqrtf(var);
    }
}

float thermal_candidate_seed_context_scale(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        representative_delta_ratio,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        float        delta_norm) {
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float seed_delta = thermal_delta_map != NULL
        ? thermal_delta_map[seed_idx]
        : thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            seed_idx,
            black_hot);
    if (seed_delta < thermal_min_delta) return 0.45f;

    int radius = effective_thermal_context_radius_cells(sample_step);
    static const int dirs[8][2] = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1},
    };
    int reach[8] = {0};
    int warm_dirs = 0;
    int cool_dirs = 0;
    float near_ratio_sum = 0.0f;
    int near_ratio_count = 0;
    float near_drop_sum = 0.0f;
    int near_drop_count = 0;
    int flat_dirs = 0;
    float sustain_floor = fmaxf(
        thermal_min_delta,
        fmaxf(seed_delta * 0.72f, seed_delta - fmaxf(2.25f, (float)delta_norm * 0.80f)));
    float near_floor = fmaxf(
        thermal_min_delta,
        fmaxf(seed_delta * 0.60f, seed_delta - fmaxf(3.25f, (float)delta_norm)));
    float frame_band = frame_contrast_mean + 1.10f * frame_contrast_std;
    if (frame_band < 0.8f) frame_band = 0.8f;
    float max_band = fmaxf(1.3f, seed_delta * 0.22f);
    if (frame_band > max_band) frame_band = max_band;

    for (int di = 0; di < 8; di++) {
        bool first_step_seen = false;
        for (int step = 1; step <= radius; step++) {
            int gx = sx + dirs[di][0] * step;
            int gy = sy + dirs[di][1] * step;
            if (gx < 0 || gx >= sg_w || gy < 0 || gy >= sg_h) break;
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (step == 1) {
                first_step_seen = true;
                if (delta > 0.0f) {
                    float drop = seed_delta - delta;
                    near_drop_sum += drop;
                    near_drop_count++;
                    if (drop < frame_band) flat_dirs++;
                } else {
                    near_drop_sum += seed_delta;
                    near_drop_count++;
                }
            }
            if (step <= 2 && delta > 0.0f) {
                near_ratio_sum += delta / fmaxf(seed_delta, 1.0f);
                near_ratio_count++;
            }
            if (delta >= sustain_floor) {
                reach[di] = step;
            } else {
                if (step == 1 && delta <= near_floor) {
                    cool_dirs++;
                }
                break;
            }
        }
        if (!first_step_seen) {
            cool_dirs++;
        }
        if (reach[di] >= 2) warm_dirs++;
    }

    int vertical = reach[0] + reach[4];
    int horizontal = reach[2] + reach[6];
    int diag_a = reach[1] + reach[5];
    int diag_b = reach[3] + reach[7];
    int major_axis = vertical;
    if (horizontal > major_axis) major_axis = horizontal;
    if (diag_a > major_axis) major_axis = diag_a;
    if (diag_b > major_axis) major_axis = diag_b;
    int minor_axis = vertical;
    if (horizontal < minor_axis) minor_axis = horizontal;
    if (diag_a < minor_axis) minor_axis = diag_a;
    if (diag_b < minor_axis) minor_axis = diag_b;
    float axis_anisotropy = (major_axis + minor_axis) > 0
        ? (float)(major_axis - minor_axis) / (float)(major_axis + minor_axis)
        : 0.0f;
    float near_ratio = near_ratio_count > 0 ? (near_ratio_sum / (float)near_ratio_count) : 0.0f;
    float near_drop_mean = near_drop_count > 0 ? (near_drop_sum / (float)near_drop_count) : seed_delta;
    float representative_ratio = clampf(representative_delta_ratio, 0.0f, 1.0f);

    float scale = 1.0f;
    if (major_axis >= 5 && axis_anisotropy >= 0.55f) {
        scale *= 0.50f;
    } else if (major_axis >= 4 && axis_anisotropy >= 0.40f) {
        scale *= 0.72f;
    }
    if (warm_dirs >= 4) {
        scale *= 0.68f;
    } else if (warm_dirs == 3) {
        scale *= 0.82f;
    }
    if (near_ratio >= fmaxf(0.62f, representative_ratio + 0.04f)) {
        scale *= 0.62f;
    } else if (near_ratio >= fmaxf(0.48f, representative_ratio - 0.02f)) {
        scale *= 0.82f;
    }
    if (near_drop_mean < frame_band) {
        scale *= 0.42f;
    } else if (near_drop_mean < frame_band * 1.4f) {
        scale *= 0.68f;
    } else if (near_drop_mean < frame_band * 1.9f) {
        scale *= 0.86f;
    }
    if (flat_dirs >= 5) {
        scale *= 0.32f;
    } else if (flat_dirs >= 3) {
        scale *= 0.58f;
    }
    if (near_drop_mean >= frame_band * 2.4f && flat_dirs <= 1 && major_axis <= 3) {
        scale *= 1.30f;
    } else if (near_drop_mean >= frame_band * 1.8f && flat_dirs <= 2 && major_axis <= 4) {
        scale *= 1.16f;
    }
    if (cool_dirs >= 5 && warm_dirs <= 2 && major_axis <= 3) {
        scale *= 1.24f;
    } else if (cool_dirs >= 4 && major_axis <= 4) {
        scale *= 1.12f;
    }
    return clampf(scale, 0.22f, 1.35f);
}

float thermal_candidate_parent_mass_scale(
        const float *thermal_delta_map,
        const float *bg_luma,
        const float *sg_luma,
        int          sg_w,
        int          sg_h,
        int          sx,
        int          sy,
        int          sample_step,
        bool         bg_valid,
        bool         black_hot,
        float        thermal_min_delta,
        float        frame_contrast_mean,
        float        frame_contrast_std,
        float        delta_norm) {
    (void)delta_norm;
    if (!bg_valid || (thermal_delta_map == NULL && (bg_luma == NULL || sg_luma == NULL)) ||
        sg_w <= 0 || sg_h <= 0 || sx < 0 || sx >= sg_w || sy < 0 || sy >= sg_h) {
        return 1.0f;
    }

    size_t seed_idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
    float seed_delta = thermal_delta_map != NULL
        ? thermal_delta_map[seed_idx]
        : thermal_delta_from_maps(
            thermal_delta_map,
            bg_luma,
            sg_luma,
            seed_idx,
            black_hot);
    if (seed_delta < thermal_min_delta) return 0.50f;

    int radius = effective_thermal_parent_mass_radius_cells(sample_step);
    float frame_band = frame_contrast_mean + 1.25f * frame_contrast_std;
    if (frame_band < 1.0f) frame_band = 1.0f;
    float max_band = fmaxf(1.8f, seed_delta * 0.34f);
    if (frame_band > max_band) frame_band = max_band;
    float parent_floor = fmaxf(thermal_min_delta, seed_delta - frame_band);

    int area = 0;
    int min_x = sx, max_x = sx, min_y = sy, max_y = sy;
    int dir_hits[8] = {0};
    static const int dirs[8][2] = {
        { 0, -1}, { 1, -1}, { 1,  0}, { 1,  1},
        { 0,  1}, {-1,  1}, {-1,  0}, {-1, -1},
    };
    for (int gy = clamp_i32(sy - radius, 0, sg_h - 1);
         gy <= clamp_i32(sy + radius, 0, sg_h - 1);
         gy++) {
        for (int gx = clamp_i32(sx - radius, 0, sg_w - 1);
             gx <= clamp_i32(sx + radius, 0, sg_w - 1);
             gx++) {
            int ring = abs(gx - sx);
            int dy = abs(gy - sy);
            if (dy > ring) ring = dy;
            if (ring > radius) continue;
            size_t idx = (size_t)gy * (size_t)sg_w + (size_t)gx;
            float delta = thermal_delta_map != NULL
                ? thermal_delta_map[idx]
                : thermal_delta_from_maps(
                    thermal_delta_map,
                    bg_luma,
                    sg_luma,
                    idx,
                    black_hot);
            if (delta < parent_floor) continue;
            area++;
            if (gx < min_x) min_x = gx;
            if (gx > max_x) max_x = gx;
            if (gy < min_y) min_y = gy;
            if (gy > max_y) max_y = gy;
            if (gx == sx && gy == sy) continue;
            for (int di = 0; di < 8; di++) {
                int dot = (gx - sx) * dirs[di][0] + (gy - sy) * dirs[di][1];
                if (dot > 0) dir_hits[di]++;
            }
        }
    }
    if (area <= 0) return 1.0f;

    float span = (float)(((max_x - min_x) > (max_y - min_y))
        ? (max_x - min_x + 1) : (max_y - min_y + 1));
    float span_px = span * (float)(sample_step > 0 ? sample_step : 1);
    int active_dirs = 0;
    for (int di = 0; di < 8; di++) {
        if (dir_hits[di] > 0) active_dirs++;
    }

    float scale = 1.0f;
    if (span_px >= 16.0f || area >= 24) {
        scale *= 0.28f;
    } else if (span_px >= 12.0f || area >= 16) {
        scale *= 0.46f;
    } else if (span_px >= 9.0f || area >= 10) {
        scale *= 0.68f;
    }
    if (active_dirs >= 6) {
        scale *= 0.55f;
    } else if (active_dirs >= 4) {
        scale *= 0.76f;
    }
    if (frame_band <= seed_delta * 0.14f && active_dirs <= 2 && span_px <= 6.0f) {
        scale *= 1.10f;
    }
    return clampf(scale, 0.18f, 1.10f);
}

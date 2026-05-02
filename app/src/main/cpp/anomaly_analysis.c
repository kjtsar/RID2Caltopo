// anomaly_analysis.c — Standalone anomaly detection for SAR drone video.
// See anomaly_analysis.h for full documentation.
#include "anomaly_analysis.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// ── Internal helpers ──────────────────────────��────────────────────────────

#define ANOMALY_LOCAL_MOTION_REGION_STRIDE_CELLS 4
#define ANOMALY_LOCAL_MOTION_REGION_RADIUS_CELLS 6
#define ANOMALY_LOCAL_MOTION_MIN_SAMPLES 10
#define ANOMALY_LOCAL_MOTION_INLIER_RADIUS_CELLS 1.35f
#define ANOMALY_LOCAL_MOTION_MIN_SCALE 0.12f
#define ANOMALY_REG_RESIDUAL_CENTER_HALF 1
#define ANOMALY_REG_RESIDUAL_RING_HALF 3
#define ANOMALY_REG_RESIDUAL_SOFT_THRESH 0.60f
#define ANOMALY_REG_RESIDUAL_HARD_THRESH 0.25f
#define ANOMALY_REG_RESIDUAL_MIN_SCALE 0.12f
#define ANOMALY_SALIENCY_RING_MARGIN 0.55f
#define ANOMALY_SALIENCY_RING_SOFT_SCALE 1.35f
#define ANOMALY_SALIENCY_RING_HARD_SCALE 0.18f
#define ANOMALY_SALIENCY_PLATEAU_SUPPORT 6

static inline float clamp01f(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static inline int clamp_i32(int value, int min_value, int max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline float clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline int popcount_u8(uint8_t value) {
    int count = 0;
    while (value != 0) {
        count += (value & 1u);
        value >>= 1u;
    }
    return count;
}

static inline float effective_thermal_min_delta(const anomaly_config_t *cfg) {
    if (cfg == NULL || cfg->thermal_min_delta <= 0.0f) {
        return ANOMALY_THERMAL_MIN_DELTA;
    }
    return cfg->thermal_min_delta;
}

static inline int effective_sample_step(const anomaly_config_t *cfg, int width, int height) {
    if (cfg != NULL && cfg->pixel_step > 0) {
        return clamp_i32(cfg->pixel_step, 1, 8);
    }
    return (width >= 1280 || height >= 720) ? 4 : 2;
}

static inline int effective_motion_sample_step(const anomaly_config_t *cfg, int width, int height) {
    int sample_step = effective_sample_step(cfg, width, height);
    // Motion should track compact moving blobs, not every pixel-scale shimmer.
    // Keep it intentionally coarser than thermal/detail sampling so 1px detail
    // does not explode motion cost or make canopy texture look like target motion.
    int min_motion_step = (width >= 1280 || height >= 720) ? 3 : 2;
    if (sample_step < min_motion_step) sample_step = min_motion_step;
    return sample_step;
}

static inline float effective_motion_evidence_scale(const anomaly_config_t *cfg) {
    if (cfg == NULL) return 1.0f;
    float scale = cfg->motion_evidence_scale;
    if (!isfinite(scale)) return 1.0f;
    if (scale < 0.10f) return 0.10f;
    if (scale > 4.00f) return 4.00f;
    return scale;
}

static inline float motion_texture_scale(int texture_score) {
    if (texture_score <= 8) return 0.0f;
    if (texture_score >= 24) return 1.0f;
    return (float)(texture_score - 8) / 16.0f;
}

static float motion_structure_scale(
        const uint8_t *luma,
        int            w,
        int            h,
        int            x,
        int            y) {
    if (luma == NULL || x <= 1 || x >= w - 2 || y <= 1 || y >= h - 2) {
        return 0.0f;
    }

    float sum_gxx = 0.0f;
    float sum_gyy = 0.0f;
    float sum_gxy = 0.0f;
    for (int ky = -1; ky <= 1; ky++) {
        for (int kx = -1; kx <= 1; kx++) {
            int sx = x + kx;
            int sy = y + ky;
            float gx = (float)luma[sy * w + (sx + 1)] - (float)luma[sy * w + (sx - 1)];
            float gy = (float)luma[(sy + 1) * w + sx] - (float)luma[(sy - 1) * w + sx];
            sum_gxx += gx * gx;
            sum_gyy += gy * gy;
            sum_gxy += gx * gy;
        }
    }

    float tr = sum_gxx + sum_gyy;
    if (tr < 1e-3f) return 0.0f;
    float det_term = (sum_gxx - sum_gyy) * (sum_gxx - sum_gyy) + 4.0f * sum_gxy * sum_gxy;
    float root = sqrtf(fmaxf(det_term, 0.0f));
    float minor = 0.5f * (tr - root);
    float corner_ratio = minor / tr;
    if (corner_ratio <= 0.03f) return 0.0f;
    if (corner_ratio >= 0.16f) return 1.0f;
    return (corner_ratio - 0.03f) / 0.13f;
}

static bool solve_3x3(
        const float a_in[3][3],
        const float b_in[3],
        float out[3]) {
    float a[3][4];
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) a[r][c] = a_in[r][c];
        a[r][3] = b_in[r];
    }

    for (int pivot = 0; pivot < 3; pivot++) {
        int best_row = pivot;
        float best_abs = fabsf(a[pivot][pivot]);
        for (int r = pivot + 1; r < 3; r++) {
            float cand = fabsf(a[r][pivot]);
            if (cand > best_abs) {
                best_abs = cand;
                best_row = r;
            }
        }
        if (best_abs < 1e-4f) return false;
        if (best_row != pivot) {
            for (int c = pivot; c < 4; c++) {
                float tmp = a[pivot][c];
                a[pivot][c] = a[best_row][c];
                a[best_row][c] = tmp;
            }
        }
        float inv = 1.0f / a[pivot][pivot];
        for (int c = pivot; c < 4; c++) a[pivot][c] *= inv;
        for (int r = 0; r < 3; r++) {
            if (r == pivot) continue;
            float factor = a[r][pivot];
            if (fabsf(factor) < 1e-6f) continue;
            for (int c = pivot; c < 4; c++) {
                a[r][c] -= factor * a[pivot][c];
            }
        }
    }

    out[0] = a[0][3];
    out[1] = a[1][3];
    out[2] = a[2][3];
    return true;
}

static int compare_float_qsort(const void *a, const void *b) {
    float fa = *(const float *)a;
    float fb = *(const float *)b;
    if (fa < fb) return -1;
    if (fa > fb) return 1;
    return 0;
}

static bool estimate_local_motion_region(
        const float   *motion_dx_map,
        const float   *motion_dy_map,
        const uint8_t *motion_valid_map,
        int            motion_w,
        int            motion_h,
        int            cx,
        int            cy,
        int            radius,
        float         *out_dx,
        float         *out_dy,
        float         *out_jitter) {
    if (motion_dx_map == NULL || motion_dy_map == NULL || motion_valid_map == NULL ||
        out_dx == NULL || out_dy == NULL || out_jitter == NULL ||
        motion_w <= 0 || motion_h <= 0) {
        return false;
    }

    int rx0 = clamp_i32(cx - radius, 0, motion_w - 1);
    int rx1 = clamp_i32(cx + radius, 0, motion_w - 1);
    int ry0 = clamp_i32(cy - radius, 0, motion_h - 1);
    int ry1 = clamp_i32(cy + radius, 0, motion_h - 1);
    int max_samples = (rx1 - rx0 + 1) * (ry1 - ry0 + 1);
    if (max_samples <= 0) return false;

    float *dx_samples = (float *)malloc((size_t)max_samples * sizeof(float));
    float *dy_samples = (float *)malloc((size_t)max_samples * sizeof(float));
    if (dx_samples == NULL || dy_samples == NULL) {
        free(dx_samples);
        free(dy_samples);
        return false;
    }

    int count = 0;
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            size_t idx = (size_t)y * (size_t)motion_w + (size_t)x;
            if (motion_valid_map[idx] == 0) continue;
            dx_samples[count] = motion_dx_map[idx];
            dy_samples[count] = motion_dy_map[idx];
            count++;
        }
    }
    if (count < ANOMALY_LOCAL_MOTION_MIN_SAMPLES) {
        free(dx_samples);
        free(dy_samples);
        return false;
    }

    qsort(dx_samples, (size_t)count, sizeof(float), compare_float_qsort);
    qsort(dy_samples, (size_t)count, sizeof(float), compare_float_qsort);
    float median_dx = dx_samples[count / 2];
    float median_dy = dy_samples[count / 2];

    float sum_dx = 0.0f;
    float sum_dy = 0.0f;
    float dev_sum = 0.0f;
    int inlier_count = 0;
    for (int y = ry0; y <= ry1; y++) {
        for (int x = rx0; x <= rx1; x++) {
            size_t idx = (size_t)y * (size_t)motion_w + (size_t)x;
            if (motion_valid_map[idx] == 0) continue;
            float ddx = motion_dx_map[idx] - median_dx;
            float ddy = motion_dy_map[idx] - median_dy;
            float dist = sqrtf(ddx * ddx + ddy * ddy);
            if (dist > ANOMALY_LOCAL_MOTION_INLIER_RADIUS_CELLS) continue;
            sum_dx += motion_dx_map[idx];
            sum_dy += motion_dy_map[idx];
            dev_sum += dist;
            inlier_count++;
        }
    }

    free(dx_samples);
    free(dy_samples);

    if (inlier_count < ANOMALY_LOCAL_MOTION_MIN_SAMPLES / 2) return false;
    *out_dx = sum_dx / (float)inlier_count;
    *out_dy = sum_dy / (float)inlier_count;
    *out_jitter = dev_sum / (float)inlier_count;
    return true;
}

static void sample_local_motion_field(
        const float *field_dx,
        const float *field_dy,
        const float *field_jitter,
        const uint8_t *field_valid,
        int field_w,
        int field_h,
        int region_stride,
        float cell_x,
        float cell_y,
        float *out_dx,
        float *out_dy,
        float *out_jitter,
        float *out_confidence) {
    if (out_dx == NULL || out_dy == NULL || out_jitter == NULL || out_confidence == NULL) return;
    *out_dx = 0.0f;
    *out_dy = 0.0f;
    *out_jitter = 0.0f;
    *out_confidence = 0.0f;
    if (field_dx == NULL || field_dy == NULL || field_jitter == NULL || field_valid == NULL ||
        field_w <= 0 || field_h <= 0 || region_stride <= 0) {
        return;
    }

    float gx = cell_x / (float)region_stride;
    float gy = cell_y / (float)region_stride;
    int ix = (int)floorf(gx);
    int iy = (int)floorf(gy);
    float fx = gx - (float)ix;
    float fy = gy - (float)iy;
    float sum_w = 0.0f;
    float sum_dx = 0.0f;
    float sum_dy = 0.0f;
    float sum_jitter = 0.0f;

    for (int oy = 0; oy <= 1; oy++) {
        for (int ox = 0; ox <= 1; ox++) {
            int sx = ix + ox;
            int sy = iy + oy;
            if (sx < 0 || sx >= field_w || sy < 0 || sy >= field_h) continue;
            size_t sidx = (size_t)sy * (size_t)field_w + (size_t)sx;
            if (field_valid[sidx] == 0) continue;
            float wx = ox == 0 ? (1.0f - fx) : fx;
            float wy = oy == 0 ? (1.0f - fy) : fy;
            float w = wx * wy;
            sum_w += w;
            sum_dx += field_dx[sidx] * w;
            sum_dy += field_dy[sidx] * w;
            sum_jitter += field_jitter[sidx] * w;
        }
    }

    if (sum_w <= 1e-4f) return;
    *out_dx = sum_dx / sum_w;
    *out_dy = sum_dy / sum_w;
    *out_jitter = sum_jitter / sum_w;
    *out_confidence = sum_w;
}

static bool find_residual_motion_displacement(
        const uint8_t *curr_luma,
        const uint8_t *prev_luma,
        int            motion_w,
        int            motion_h,
        int            mx,
        int            my,
        int            pred_x,
        int            pred_y,
        int            patch_half,
        int            search_radius,
        int           *best_dx_out,
        int           *best_dy_out,
        int           *best_sad_out) {
    if (curr_luma == NULL || prev_luma == NULL) return false;
    if (mx < patch_half || mx >= motion_w - patch_half ||
        my < patch_half || my >= motion_h - patch_half) {
        return false;
    }

    long best_sad = 0x7FFFFFFFL;
    long second_best_sad = 0x7FFFFFFFL;
    int best_dx = 0;
    int best_dy = 0;
    int best_dist2 = 0x7FFFFFFF;
    bool found = false;

    for (int dy = -search_radius; dy <= search_radius; dy++) {
        for (int dx = -search_radius; dx <= search_radius; dx++) {
            int cx = pred_x + dx;
            int cy = pred_y + dy;
            if (cx < patch_half || cx >= motion_w - patch_half ||
                cy < patch_half || cy >= motion_h - patch_half) {
                continue;
            }
            long sad = 0;
            for (int ky = -patch_half; ky <= patch_half; ky++) {
                for (int kx = -patch_half; kx <= patch_half; kx++) {
                    int curr_v = curr_luma[(my + ky) * motion_w + (mx + kx)];
                    int prev_v = prev_luma[(cy + ky) * motion_w + (cx + kx)];
                    int d = curr_v - prev_v;
                    sad += d < 0 ? -d : d;
                }
            }
            int dist2 = dx * dx + dy * dy;
            if (sad < best_sad || (sad == best_sad && dist2 < best_dist2)) {
                second_best_sad = best_sad;
                best_sad = sad;
                best_dx = dx;
                best_dy = dy;
                best_dist2 = dist2;
                found = true;
            } else if (sad < second_best_sad) {
                second_best_sad = sad;
            }
        }
    }

    if (!found) return false;
    if (second_best_sad < 0x7FFFFFFFL) {
        long sad_margin = second_best_sad - best_sad;
        if (sad_margin < 12) return false;
        if ((double)second_best_sad < (double)best_sad * 1.08) return false;
    }
    if (abs(best_dx) >= search_radius || abs(best_dy) >= search_radius) {
        return false;
    }
    if (best_dx_out != NULL) *best_dx_out = best_dx;
    if (best_dy_out != NULL) *best_dy_out = best_dy;
    if (best_sad_out != NULL) *best_sad_out = best_sad >= 0x7FFFFFFFL ? -1 : (int)best_sad;
    return true;
}

static bool bilinear_sample_u8(
        const uint8_t *grid,
        int            w,
        int            h,
        float          x,
        float          y,
        float         *out_value) {
    if (grid == NULL || out_value == NULL || w <= 1 || h <= 1) return false;
    if (x < 0.0f || y < 0.0f || x > (float)(w - 1) || y > (float)(h - 1)) return false;
    int x0 = (int)floorf(x);
    int y0 = (int)floorf(y);
    int x1 = x0 + 1;
    int y1 = y0 + 1;
    if (x1 >= w) x1 = w - 1;
    if (y1 >= h) y1 = h - 1;
    float fx = x - (float)x0;
    float fy = y - (float)y0;
    float p00 = (float)grid[y0 * w + x0];
    float p10 = (float)grid[y0 * w + x1];
    float p01 = (float)grid[y1 * w + x0];
    float p11 = (float)grid[y1 * w + x1];
    *out_value =
        p00 * (1.0f - fx) * (1.0f - fy) +
        p10 * fx * (1.0f - fy) +
        p01 * (1.0f - fx) * fy +
        p11 * fx * fy;
    return true;
}

static float registration_residual_standout_score(
        const uint8_t      *curr_luma,
        const uint8_t      *prev_luma,
        int                 motion_w,
        int                 motion_h,
        int                 motion_step,
        int                 width,
        int                 height,
        similarity_2d_t     sim,
        int                 mx,
        int                 my) {
    if (curr_luma == NULL || prev_luma == NULL || motion_w <= 1 || motion_h <= 1 ||
        width <= 1 || height <= 1 || !sim.valid) {
        return 0.0f;
    }

    const int center_half = ANOMALY_REG_RESIDUAL_CENTER_HALF;
    const int ring_half = ANOMALY_REG_RESIDUAL_RING_HALF;
    float fw = (float)(width - 1);
    float fh = (float)(height - 1);
    float center_sum = 0.0f;
    int center_count = 0;
    float ring_sum = 0.0f;
    float ring_sum2 = 0.0f;
    int ring_count = 0;

    for (int oy = -ring_half; oy <= ring_half; oy++) {
        for (int ox = -ring_half; ox <= ring_half; ox++) {
            int sx = mx + ox;
            int sy = my + oy;
            if (sx < 0 || sx >= motion_w || sy < 0 || sy >= motion_h) continue;

            float x_norm = ((float)(sx * motion_step)) / fw;
            float y_norm = ((float)(sy * motion_step)) / fh;
            float prev_x_norm = sim.a * x_norm - sim.b * y_norm + sim.tx;
            float prev_y_norm = sim.b * x_norm + sim.a * y_norm + sim.ty;
            float prev_x = (prev_x_norm * fw) / (float)motion_step;
            float prev_y = (prev_y_norm * fh) / (float)motion_step;
            float prev_sample = 0.0f;
            if (!bilinear_sample_u8(prev_luma, motion_w, motion_h, prev_x, prev_y, &prev_sample)) continue;

            float resid = fabsf((float)curr_luma[sy * motion_w + sx] - prev_sample);
            if (abs(ox) <= center_half && abs(oy) <= center_half) {
                center_sum += resid;
                center_count++;
            } else {
                ring_sum += resid;
                ring_sum2 += resid * resid;
                ring_count++;
            }
        }
    }

    if (center_count <= 0 || ring_count < 8) return 0.0f;
    float center_mean = center_sum / (float)center_count;
    float ring_mean = ring_sum / (float)ring_count;
    float ring_var = ring_sum2 / (float)ring_count - ring_mean * ring_mean;
    if (ring_var < 0.0f) ring_var = 0.0f;
    float ring_std = sqrtf(ring_var);
    if (ring_std < 1.0f) ring_std = 1.0f;
    return (center_mean - ring_mean) / ring_std;
}

static int gmv_feature_score(const uint8_t *luma, int w, int h, int x, int y) {
    if (luma == NULL || w <= 0 || h <= 0) return 0;
    if (x <= 0 || x >= w - 1 || y <= 0 || y >= h - 1) return 0;
    int c = luma[y * w + x];
    int score = 0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int v = luma[(y + dy) * w + (x + dx)];
            int d = c - v;
            score += d < 0 ? -d : d;
        }
    }
    return score;
}

static inline double ii_query(const double *ii, int stride,
                              int sx0, int sy0, int sx1, int sy1) {
    return ii[sy1 * stride + sx1]
           - (sx0 > 0 ? ii[sy1 * stride + (sx0 - 1)] : 0.0)
           - (sy0 > 0 ? ii[(sy0 - 1) * stride + sx1] : 0.0)
           + (sx0 > 0 && sy0 > 0 ? ii[(sy0 - 1) * stride + (sx0 - 1)] : 0.0);
}

static void build_patch_selection_map(
        const float *score_map,
        int          sg_w,
        int          sg_h,
        float       *selection_map) {
    if (selection_map == NULL || sg_w <= 0 || sg_h <= 0) return;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            float center = score_map[sy * sg_w + sx];
            selection_map[sy * sg_w + sx] = -1.0f;
            if (center <= 0.0f) continue;

            // Require a local maximum so clutter edges don't drag the centroid.
            bool is_peak = true;
            for (int ny = sy - 1; ny <= sy + 1 && is_peak; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w) continue;
                    if (nx == sx && ny == sy) continue;
                    if (score_map[ny * sg_w + nx] > center) {
                        is_peak = false;
                        break;
                    }
                }
            }
            if (!is_peak) continue;

            float top1 = center, top2 = -1.0f, top3 = -1.0f;
            int support = 0;
            float sum_w = 0.0f;
            float sum_dx = 0.0f, sum_dy = 0.0f;
            float sum_dx2 = 0.0f, sum_dy2 = 0.0f, sum_dxdy = 0.0f;
            float ring_sum = 0.0f;
            int ring_count = 0;
            for (int ny = sy - 1; ny <= sy + 1; ny++) {
                if (ny < 0 || ny >= sg_h) continue;
                for (int nx = sx - 1; nx <= sx + 1; nx++) {
                    if (nx < 0 || nx >= sg_w) continue;
                    float v = score_map[ny * sg_w + nx];
                    if (v <= 0.0f) continue;
                    support++;
                    float dx = (float)(nx - sx);
                    float dy = (float)(ny - sy);
                    sum_w += v;
                    sum_dx += dx * v;
                    sum_dy += dy * v;
                    sum_dx2 += dx * dx * v;
                    sum_dy2 += dy * dy * v;
                    sum_dxdy += dx * dy * v;
                    if (!(nx == sx && ny == sy)) {
                        ring_sum += v;
                        ring_count++;
                    }
                    if (v > top1) {
                        top3 = top2;
                        top2 = top1;
                        top1 = v;
                    } else if (v > top2) {
                        top3 = top2;
                        top2 = v;
                    } else if (v > top3) {
                        top3 = v;
                    }
                }
            }

            float sum = top1;
            int n = 1;
            if (top2 > 0.0f) { sum += top2; n++; }
            if (top3 > 0.0f) { sum += top3; n++; }
            float score = sum / (float)n;
            float ring_mean = ring_count > 0 ? (ring_sum / (float)ring_count) : 0.0f;
            float ring_margin = center - ring_mean;

            // Small support bonus rewards a tiny coherent cluster but never
            // dominates the raw darkness score; we still want very small targets.
            float support_bonus = 0.08f * (float)(support > 1 ? (support - 1) : 0);
            if (support_bonus > 0.32f) support_bonus = 0.32f;
            score += support_bonus;

            // Penalise elongated / edge-like support that tends to occur on
            // foliage boundaries and clearing edges. Small compact peaks keep
            // most of their score; broad one-sided ridges lose some of it.
            if (sum_w > 0.0f) {
                float mean_dx = sum_dx / sum_w;
                float mean_dy = sum_dy / sum_w;
                float var_x = fmaxf(sum_dx2 / sum_w - mean_dx * mean_dx, 0.0f);
                float var_y = fmaxf(sum_dy2 / sum_w - mean_dy * mean_dy, 0.0f);
                float cov_xy = (sum_dxdy / sum_w) - mean_dx * mean_dy;
                float tr = var_x + var_y;
                float det_term = (var_x - var_y) * (var_x - var_y) + 4.0f * cov_xy * cov_xy;
                float root = sqrtf(fmaxf(det_term, 0.0f));
                float major = 0.5f * (tr + root);
                float minor = 0.5f * (tr - root);
                float anisotropy = (major + minor) > 1e-4f
                    ? (major - minor) / (major + minor)
                    : 0.0f;
                float center_share = center / sum_w;
                float offset_mag = sqrtf(mean_dx * mean_dx + mean_dy * mean_dy);

                float elongation_penalty = 0.55f * anisotropy;
                float offset_penalty = 0.35f * offset_mag;
                float center_penalty = 0.0f;
                if (center_share < 0.34f) {
                    center_penalty = (0.34f - center_share) * 2.0f;
                }
                float compact_penalty = elongation_penalty + offset_penalty + center_penalty;
                if (compact_penalty > 1.10f) compact_penalty = 1.10f;
                score -= compact_penalty;
            }
            if (ring_margin < ANOMALY_SALIENCY_RING_MARGIN) {
                float penalty =
                    (ANOMALY_SALIENCY_RING_MARGIN - ring_margin) * ANOMALY_SALIENCY_RING_SOFT_SCALE;
                if (support >= ANOMALY_SALIENCY_PLATEAU_SUPPORT) {
                    penalty *= 1.35f;
                }
                score -= penalty;
                if (support >= ANOMALY_SALIENCY_PLATEAU_SUPPORT &&
                    ring_margin < (ANOMALY_SALIENCY_RING_MARGIN * 0.35f)) {
                    score *= ANOMALY_SALIENCY_RING_HARD_SCALE;
                }
            }
            selection_map[sy * sg_w + sx] = score;
        }
    }
}

static void build_motion_selection_map(
        const float *motion_z_map,
        const uint8_t *curr_luma,
        int          motion_w,
        int          motion_h,
        float       *selection_map,
        float       *component_area_frac_map,
        float       *component_span_frac_map,
        float       *component_fill_ratio_map) {
    if (selection_map == NULL || motion_z_map == NULL || motion_w <= 0 || motion_h <= 0) return;
    size_t cell_count = (size_t)motion_w * (size_t)motion_h;
    int *component_map = (int *)malloc(cell_count * sizeof(int));
    int *queue = (int *)malloc(cell_count * sizeof(int));
    uint8_t *mass_seen = (uint8_t *)malloc(cell_count * sizeof(uint8_t));
    if (component_map == NULL || queue == NULL || mass_seen == NULL) {
        free(component_map);
        free(queue);
        free(mass_seen);
        for (size_t i = 0; i < cell_count; i++) selection_map[i] = -1.0f;
        return;
    }

    for (size_t i = 0; i < cell_count; i++) {
        selection_map[i] = -1.0f;
        if (component_area_frac_map != NULL) component_area_frac_map[i] = 0.0f;
        if (component_span_frac_map != NULL) component_span_frac_map[i] = 0.0f;
        if (component_fill_ratio_map != NULL) component_fill_ratio_map[i] = 0.0f;
        component_map[i] = -1;
        mass_seen[i] = 0;
    }

    int component_id = 0;
    for (int my = 0; my < motion_h; my++) {
        for (int mx = 0; mx < motion_w; mx++) {
            int seed_idx = my * motion_w + mx;
            float seed_excess = motion_z_map[seed_idx] - 1.0f;
            if (seed_excess <= 0.0f || component_map[seed_idx] >= 0) continue;

            int head = 0, tail = 0;
            queue[tail++] = seed_idx;
            component_map[seed_idx] = component_id;

            int min_x = mx, max_x = mx;
            int min_y = my, max_y = my;
            int area = 0;
            float peak_excess = seed_excess;
            int peak_idx = seed_idx;
            float sum_excess = 0.0f;
            float luma_sum = 0.0f;
            float luma_sum_sq = 0.0f;

            while (head < tail) {
                int idx = queue[head++];
                int cx = idx % motion_w;
                int cy = idx / motion_w;
                float excess = motion_z_map[idx] - 1.0f;
                if (excess <= 0.0f) continue;

                area++;
                sum_excess += excess;
                if (curr_luma != NULL) {
                    float lum = (float)curr_luma[idx];
                    luma_sum += lum;
                    luma_sum_sq += lum * lum;
                }
                if (excess > peak_excess) {
                    peak_excess = excess;
                    peak_idx = idx;
                }
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;

                for (int ny = cy - 1; ny <= cy + 1; ny++) {
                    if (ny < 0 || ny >= motion_h) continue;
                    for (int nx = cx - 1; nx <= cx + 1; nx++) {
                        if (nx < 0 || nx >= motion_w) continue;
                        int nidx = ny * motion_w + nx;
                        if (component_map[nidx] >= 0) continue;
                        if ((motion_z_map[nidx] - 1.0f) <= 0.0f) continue;
                        component_map[nidx] = component_id;
                        queue[tail++] = nidx;
                    }
                }
            }

            if (area <= 0) {
                component_id++;
                continue;
            }

            float mean_excess = sum_excess / (float)area;
            int box_w = max_x - min_x + 1;
            int box_h = max_y - min_y + 1;
            int box_area = box_w * box_h;
            float fill_ratio = box_area > 0 ? ((float)area / (float)box_area) : 0.0f;
            float component_area_frac = cell_count > 0 ? ((float)area / (float)cell_count) : 0.0f;
            float footprint_area_frac = cell_count > 0 ? ((float)box_area / (float)cell_count) : 0.0f;
            float span_frac_w = motion_w > 0 ? ((float)box_w / (float)motion_w) : 1.0f;
            float span_frac_h = motion_h > 0 ? ((float)box_h / (float)motion_h) : 1.0f;
            float max_span_frac = span_frac_w > span_frac_h ? span_frac_w : span_frac_h;
            float aspect = (box_w > box_h)
                ? ((float)box_w / (float)(box_h > 0 ? box_h : 1))
                : ((float)box_h / (float)(box_w > 0 ? box_w : 1));

            int pad = 3;
            int rx0 = clamp_i32(min_x - pad, 0, motion_w - 1);
            int rx1 = clamp_i32(max_x + pad, 0, motion_w - 1);
            int ry0 = clamp_i32(min_y - pad, 0, motion_h - 1);
            int ry1 = clamp_i32(max_y + pad, 0, motion_h - 1);
            float outer_sum = 0.0f;
            int outer_count = 0;
            int quiet_count = 0;
            int moving_count = 0;
            float outer_luma_sum = 0.0f;
            int outer_luma_count = 0;
            for (int ry = ry0; ry <= ry1; ry++) {
                for (int rx = rx0; rx <= rx1; rx++) {
                    int ridx = ry * motion_w + rx;
                    if (component_map[ridx] == component_id) continue;
                    float excess = motion_z_map[ridx] - 1.0f;
                    if (excess < 0.0f) excess = 0.0f;
                    outer_sum += excess;
                    outer_count++;
                    if (excess <= 0.35f) {
                        quiet_count++;
                    } else {
                        moving_count++;
                    }
                    if (curr_luma != NULL) {
                        outer_luma_sum += (float)curr_luma[ridx];
                        outer_luma_count++;
                    }
                }
            }
            float outer_mean = outer_count > 0 ? (outer_sum / (float)outer_count) : 0.0f;
            float quiet_fraction = outer_count > 0 ? ((float)quiet_count / (float)outer_count) : 0.0f;
            float moving_fraction = outer_count > 0 ? ((float)moving_count / (float)outer_count) : 0.0f;
            float component_luma_mean = area > 0 ? (luma_sum / (float)area) : 0.0f;
            float component_luma_var = area > 0
                ? (luma_sum_sq / (float)area) - (component_luma_mean * component_luma_mean)
                : 0.0f;
            if (component_luma_var < 0.0f) component_luma_var = 0.0f;
            float component_luma_std = sqrtf(component_luma_var);
            float outer_luma_mean = outer_luma_count > 0 ? (outer_luma_sum / (float)outer_luma_count) : component_luma_mean;
            float tone_delta = fabsf(component_luma_mean - outer_luma_mean);
            float tone_coherence = tone_delta / (component_luma_std + 4.0f);
            int homogeneous_mass_count = 0;
            float homogeneous_mass_frac = 0.0f;
            if (curr_luma != NULL) {
                int mass_pad = ANOMALY_MOTION_HOMOGENEOUS_MASS_PAD;
                int mass_x0 = clamp_i32(min_x - mass_pad, 0, motion_w - 1);
                int mass_x1 = clamp_i32(max_x + mass_pad, 0, motion_w - 1);
                int mass_y0 = clamp_i32(min_y - mass_pad, 0, motion_h - 1);
                int mass_y1 = clamp_i32(max_y + mass_pad, 0, motion_h - 1);
                int mass_window_area = (mass_x1 - mass_x0 + 1) * (mass_y1 - mass_y0 + 1);
                if (mass_window_area > 0) {
                    memset(mass_seen, 0, cell_count * sizeof(uint8_t));
                    int mass_head = 0;
                    int mass_tail = 0;
                    queue[mass_tail++] = peak_idx;
                    mass_seen[peak_idx] = 1;
                    float seed_luma = (float)curr_luma[peak_idx];
                    while (mass_head < mass_tail) {
                        int idx = queue[mass_head++];
                        int cx = idx % motion_w;
                        int cy = idx / motion_w;
                        homogeneous_mass_count++;
                        for (int ny = cy - 1; ny <= cy + 1; ny++) {
                            if (ny < mass_y0 || ny > mass_y1) continue;
                            for (int nx = cx - 1; nx <= cx + 1; nx++) {
                                if (nx < mass_x0 || nx > mass_x1) continue;
                                int nidx = ny * motion_w + nx;
                                if (mass_seen[nidx]) continue;
                                float lum = (float)curr_luma[nidx];
                                if (fabsf(lum - seed_luma) > ANOMALY_MOTION_HOMOGENEOUS_MASS_DELTA) continue;
                                mass_seen[nidx] = 1;
                                queue[mass_tail++] = nidx;
                            }
                        }
                    }
                    homogeneous_mass_frac = (float)homogeneous_mass_count / (float)mass_window_area;
                }
            }

            float contrast_ratio = (mean_excess + 0.50f) / (outer_mean + 0.50f);
            if (contrast_ratio < 0.10f) contrast_ratio = 0.10f;
            if (contrast_ratio > 2.50f) contrast_ratio = 2.50f;

            float score = peak_excess;
            if (area <= 1) {
                score *= 0.15f;
            } else {
                float area_bonus = 0.22f * (float)(area - 1);
                if (area_bonus > 1.10f) area_bonus = 1.10f;
                score += area_bonus;
            }

            score *= contrast_ratio;
            score *= (0.45f + 0.85f * quiet_fraction);
            score -= 2.40f * moving_fraction;

            if (fill_ratio < ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO) {
                float fill_penalty = (ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO - fill_ratio) * 3.1f;
                if (fill_ratio < ANOMALY_MOTION_COMPONENT_MIN_FILL_RATIO) {
                    fill_penalty += (ANOMALY_MOTION_COMPONENT_MIN_FILL_RATIO - fill_ratio) * 2.6f;
                }
                if (fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                    fill_penalty += (ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO - fill_ratio) * 5.0f;
                }
                if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                    fill_penalty *= 1.35f;
                }
                score -= fill_penalty;
            }
            if (aspect > 2.5f) {
                float aspect_penalty = (aspect - 2.5f) * 0.55f;
                if (aspect_penalty > 2.0f) aspect_penalty = 2.0f;
                score -= aspect_penalty;
            } else if (aspect > ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) {
                float aspect_penalty = (aspect - ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) * 0.95f;
                if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                    aspect_penalty *= 1.25f;
                }
                score -= aspect_penalty;
            }
            if (area <= 2 && fill_ratio < 0.58f && aspect > 1.35f) {
                score -= 1.75f;
            } else if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                       fill_ratio < 0.50f &&
                       aspect > 1.20f) {
                score -= 0.90f;
            }
            if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                score -= 1.35f;
                if (max_span_frac > ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) {
                    score -= 1.10f;
                }
            }
            if (curr_luma != NULL) {
                if (tone_coherence < ANOMALY_MOTION_COMPONENT_MIN_TONE_COHERENCE) {
                    float tone_penalty =
                        (ANOMALY_MOTION_COMPONENT_MIN_TONE_COHERENCE - tone_coherence) * 2.2f;
                    if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX) {
                        tone_penalty *= 1.30f;
                    }
                    score -= tone_penalty;
                } else if (tone_coherence >= ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE &&
                           area >= 2 &&
                           area <= 10 &&
                           fill_ratio >= 0.50f) {
                    float tone_bonus =
                        (tone_coherence - ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE) * 0.35f;
                    if (tone_bonus > 0.45f) tone_bonus = 0.45f;
                    score += tone_bonus;
                }
                if (component_luma_std > 16.0f && area <= 6) {
                    float std_penalty = (component_luma_std - 16.0f) * 0.06f;
                    if (fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO) {
                        std_penalty *= 1.5f;
                    }
                    score -= std_penalty;
                }
                if (homogeneous_mass_count >= ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_COUNT &&
                    homogeneous_mass_frac >= ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) {
                    float mass_penalty = 1.0f +
                        ((float)(homogeneous_mass_count - ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_COUNT) * 0.12f);
                    if (homogeneous_mass_frac > ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) {
                        mass_penalty +=
                            (homogeneous_mass_frac - ANOMALY_MOTION_HOMOGENEOUS_MASS_SOFT_FRAC) * 4.0f;
                    }
                    if (area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_HARD_MAX) {
                        mass_penalty *= 1.25f;
                    }
                    score -= mass_penalty;
                }
            }
            bool veto_fragment =
                area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_FRAGMENT_FILL_VETO &&
                max_span_frac >= ANOMALY_MOTION_COMPONENT_FRAGMENT_SPAN_VETO;
            bool hard_veto_fragment =
                area <= ANOMALY_MOTION_COMPONENT_FRAGMENT_AREA_HARD_MAX &&
                fill_ratio < ANOMALY_MOTION_COMPONENT_SPARSE_FILL_RATIO &&
                max_span_frac >= ANOMALY_MOTION_COMPONENT_FRAGMENT_SPAN_VETO;
            if (hard_veto_fragment) {
                if (curr_luma == NULL || tone_coherence < (ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE + 0.35f)) {
                    score = 0.0f;
                }
            } else if (veto_fragment) {
                if (curr_luma == NULL || tone_coherence < ANOMALY_MOTION_COMPONENT_TARGET_TONE_COHERENCE) {
                    score *= 0.18f;
                }
            }
            if (curr_luma != NULL &&
                homogeneous_mass_count >= ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_COUNT &&
                homogeneous_mass_frac >= ANOMALY_MOTION_HOMOGENEOUS_MASS_HARD_FRAC) {
                score *= 0.10f;
            }
            if (component_area_frac > ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) {
                float t = (component_area_frac - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_AREA_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 3.2f * t;
            } else {
                float compact_bonus = 1.0f - (component_area_frac / ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (compact_bonus < 0.0f) compact_bonus = 0.0f;
                score += 0.35f * compact_bonus;
            }
            if (footprint_area_frac > ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) {
                float t = (footprint_area_frac - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_AREA_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_AREA_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 2.1f * t;
            }
            if (max_span_frac > ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) {
                float t = (max_span_frac - ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC) /
                    (ANOMALY_MOTION_COMPONENT_MAX_SPAN_FRAC - ANOMALY_MOTION_COMPONENT_TARGET_SPAN_FRAC);
                if (t < 0.0f) t = 0.0f;
                if (t > 1.0f) t = 1.0f;
                score -= 2.8f * t;
            }
            if (area >= 2 &&
                area <= 8 &&
                fill_ratio >= ANOMALY_MOTION_COMPONENT_TARGET_FILL_RATIO &&
                aspect <= ANOMALY_MOTION_COMPONENT_COMPACT_ASPECT) {
                float compact_blob_bonus = 0.18f * (float)(area - 1);
                if (compact_blob_bonus > 0.85f) compact_blob_bonus = 0.85f;
                score += compact_blob_bonus;
            }

            for (int i = 0; i < tail; i++) {
                int idx = queue[i];
                float excess = motion_z_map[idx] - 1.0f;
                if (excess <= 0.0f) continue;
                selection_map[idx] = score + 0.08f * (excess - mean_excess);
                if (component_area_frac_map != NULL) component_area_frac_map[idx] = component_area_frac;
                if (component_span_frac_map != NULL) component_span_frac_map[idx] = max_span_frac;
                if (component_fill_ratio_map != NULL) component_fill_ratio_map[idx] = fill_ratio;
            }
            component_id++;
        }
    }

    free(component_map);
    free(queue);
    free(mass_seen);
}

static void choose_best_dark_patch(
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
    int best_sx = 0, best_sy = 0;
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

static void maybe_insert_top_candidate(
        anomaly_debug_candidate_t *candidates,
        int                      *count,
        int                       max_count,
        int                       pixel_x,
        int                       pixel_y,
        float                     x_norm,
        float                     y_norm,
        float                     spatial_score,
        float                     temporal_score,
        float                     combined_score) {
    if (candidates == NULL || count == NULL || max_count <= 0 || combined_score <= 0.0f) return;

    int insert_at = *count;
    if (insert_at > max_count) insert_at = max_count;
    for (int i = 0; i < *count && i < max_count; i++) {
        if (combined_score > candidates[i].combined_score) {
            insert_at = i;
            break;
        }
    }
    if (insert_at >= max_count) return;

    int limit = *count < max_count ? *count : (max_count - 1);
    for (int i = limit; i > insert_at; i--) {
        candidates[i] = candidates[i - 1];
    }
    if (*count < max_count) (*count)++;

    anomaly_debug_candidate_t *slot = &candidates[insert_at];
    slot->valid = true;
    slot->pixel_x = pixel_x;
    slot->pixel_y = pixel_y;
    slot->x_norm = x_norm;
    slot->y_norm = y_norm;
    slot->spatial_score = spatial_score;
    slot->temporal_score = temporal_score;
    slot->combined_score = combined_score;
}

static void draw_rgba_hline(uint8_t *rgba, int rgba_stride,
                            int width, int height,
                            int x0, int x1, int y,
                            uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (y < 0 || y >= height) return;
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    x0 = clamp_i32(x0, 0, width - 1);
    x1 = clamp_i32(x1, 0, width - 1);
    uint8_t *row = rgba + (y * rgba_stride);
    for (int x = x0; x <= x1; x++) {
        uint8_t *px = row + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

static void draw_rgba_vline(uint8_t *rgba, int rgba_stride,
                            int width, int height,
                            int y0, int y1, int x,
                            uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (x < 0 || x >= width) return;
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    y0 = clamp_i32(y0, 0, height - 1);
    y1 = clamp_i32(y1, 0, height - 1);
    for (int y = y0; y <= y1; y++) {
        uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

static void draw_rgba_circle(uint8_t *rgba, int rgba_stride,
                             int width, int height,
                             int cx, int cy, int radius, int stroke,
                             uint8_t r, uint8_t g, uint8_t b) {
    if (rgba == NULL || width <= 0 || height <= 0 || radius <= 0 || stroke <= 0) return;
    for (int band = 0; band < stroke; band++) {
        int rr = radius + band;
        int x = rr;
        int y = 0;
        int err = 1 - x;
        while (x >= y) {
            int points[8][2] = {
                {cx + x, cy + y}, {cx + y, cy + x},
                {cx - y, cy + x}, {cx - x, cy + y},
                {cx - x, cy - y}, {cx - y, cy - x},
                {cx + y, cy - x}, {cx + x, cy - y},
            };
            for (int i = 0; i < 8; i++) {
                int px = points[i][0];
                int py = points[i][1];
                if (px < 0 || px >= width || py < 0 || py >= height) continue;
                uint8_t *dst = rgba + (py * rgba_stride) + (px * 4);
                dst[0] = r; dst[1] = g; dst[2] = b; dst[3] = 0xFF;
            }
            y++;
            if (err < 0) {
                err += 2 * y + 1;
            } else {
                x--;
                err += 2 * (y - x + 1);
            }
        }
    }
}

static inline double rgba_luma_at(const uint8_t *px) {
    return (0.2126 * (double)px[0]) + (0.7152 * (double)px[1]) + (0.0722 * (double)px[2]);
}

static bool row_has_active_content_range(const uint8_t *rgba, int rgba_stride,
                                         int width, int height, int y,
                                         int x0, int x1) {
    if (rgba == NULL || y < 0 || y >= height || width <= 0) return false;
    if (x0 < 0) x0 = 0;
    if (x1 >= width) x1 = width - 1;
    if (x1 <= x0) return false;
    const uint8_t *row = rgba + (y * rgba_stride);
    double min_luma = 0.0;
    double max_luma = 0.0;
    int textured_samples = 0;
    int sample_count = 0;
    bool first = true;
    int span = x1 - x0 + 1;
    int step = span > 320 ? 2 : 1;
    double prev_luma = 0.0;
    bool prev_valid = false;
    for (int x = x0; x <= x1; x += step) {
        double luma = rgba_luma_at(row + (x * 4));
        if (first) {
            min_luma = max_luma = luma;
            first = false;
        } else {
            if (luma < min_luma) min_luma = luma;
            if (luma > max_luma) max_luma = luma;
        }
        if (prev_valid && fabs(luma - prev_luma) >= 10.0) {
            textured_samples++;
        }
        prev_luma = luma;
        prev_valid = true;
        sample_count++;
    }
    if ((max_luma - min_luma) < 8.0) return false;
    if (sample_count <= 1) return false;
    double textured_fraction = (double)textured_samples / (double)(sample_count - 1);
    return textured_fraction >= 0.10;
}

static bool col_has_active_content_range(const uint8_t *rgba, int rgba_stride,
                                         int width, int height, int x,
                                         int y0, int y1) {
    if (rgba == NULL || x < 0 || x >= width || height <= 0) return false;
    if (y0 < 0) y0 = 0;
    if (y1 >= height) y1 = height - 1;
    if (y1 <= y0) return false;
    double min_luma = 0.0;
    double max_luma = 0.0;
    int textured_samples = 0;
    int sample_count = 0;
    bool first = true;
    int span = y1 - y0 + 1;
    int step = span > 320 ? 2 : 1;
    double prev_luma = 0.0;
    bool prev_valid = false;
    for (int y = y0; y <= y1; y += step) {
        const uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
        double luma = rgba_luma_at(px);
        if (first) {
            min_luma = max_luma = luma;
            first = false;
        } else {
            if (luma < min_luma) min_luma = luma;
            if (luma > max_luma) max_luma = luma;
        }
        if (prev_valid && fabs(luma - prev_luma) >= 10.0) {
            textured_samples++;
        }
        prev_luma = luma;
        prev_valid = true;
        sample_count++;
    }
    if ((max_luma - min_luma) < 8.0) return false;
    if (sample_count <= 1) return false;
    double textured_fraction = (double)textured_samples / (double)(sample_count - 1);
    return textured_fraction >= 0.10;
}

typedef bool (*active_span_fn)(const uint8_t *rgba, int rgba_stride,
                               int width, int height, int index,
                               int aux0, int aux1);

static void detect_best_active_span(const uint8_t *rgba, int rgba_stride,
                                    int width, int height,
                                    int length, int aux0, int aux1,
                                    active_span_fn is_active,
                                    int *start_out, int *end_out) {
    int best_start = 0;
    int best_end = length - 1;
    int best_len = -1;
    int center = length / 2;
    bool best_contains_center = false;
    int best_center_distance = length;

    int run_start = -1;
    for (int i = 0; i <= length; i++) {
        bool active = (i < length) && is_active(rgba, rgba_stride, width, height, i, aux0, aux1);
        if (active) {
            if (run_start < 0) run_start = i;
            continue;
        }
        if (run_start < 0) continue;
        int run_end = i - 1;
        int run_len = run_end - run_start + 1;
        bool contains_center = (run_start <= center && center <= run_end);
        int run_center = (run_start + run_end) / 2;
        int center_distance = abs(run_center - center);
        bool better = false;
        if (best_len < 0) {
            better = true;
        } else if (contains_center != best_contains_center) {
            better = contains_center;
        } else if (run_len != best_len) {
            better = run_len > best_len;
        } else if (center_distance != best_center_distance) {
            better = center_distance < best_center_distance;
        }
        if (better) {
            best_start = run_start;
            best_end = run_end;
            best_len = run_len;
            best_contains_center = contains_center;
            best_center_distance = center_distance;
        }
        run_start = -1;
    }

    if (best_len < 0) {
        best_start = 0;
        best_end = length - 1;
    }
    if (start_out) *start_out = best_start;
    if (end_out) *end_out = best_end;
}

static void detect_active_content_bounds(const uint8_t *rgba, int rgba_stride,
                                         int width, int height,
                                         int *x0_out, int *y0_out,
                                         int *x1_out, int *y1_out) {
    int x0 = 0;
    int y0 = 0;
    int x1 = width - 1;
    int y1 = height - 1;
    detect_best_active_span(rgba, rgba_stride, width, height,
                            height, 0, width - 1,
                            row_has_active_content_range,
                            &y0, &y1);
    detect_best_active_span(rgba, rgba_stride, width, height,
                            width, y0, y1,
                            col_has_active_content_range,
                            &x0, &x1);
    detect_best_active_span(rgba, rgba_stride, width, height,
                            height, x0, x1,
                            row_has_active_content_range,
                            &y0, &y1);

    if ((x1 - x0 + 1) < width / 3 || (y1 - y0 + 1) < height / 3) {
        x0 = 0;
        y0 = 0;
        x1 = width - 1;
        y1 = height - 1;
    }
    if (x0_out) *x0_out = x0;
    if (y0_out) *y0_out = y0;
    if (x1_out) *x1_out = x1;
    if (y1_out) *y1_out = y1;
}

static void draw_hot_overlay_rgba(uint8_t *rgba, int rgba_stride,
                                  int width, int height,
                                  int thermal_polarity) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    int content_x0 = 0, content_y0 = 0, content_x1 = width - 1, content_y1 = height - 1;
    detect_active_content_bounds(rgba, rgba_stride, width, height, &content_x0, &content_y0, &content_x1, &content_y1);
    double sum_luma = 0.0;
    double hottest_luma = 0.0;
    int hottest_x = 0;
    int hottest_y = 0;
    bool black_hot = (thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    bool first = true;
    for (int y = content_y0; y <= content_y1; y++) {
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = content_x0; x <= content_x1; x++) {
            const uint8_t *px = row + (x * 4);
            double luma = rgba_luma_at(px);
            sum_luma += luma;
            if (first ||
                (black_hot && luma < hottest_luma) ||
                (!black_hot && luma > hottest_luma)) {
                hottest_luma = luma;
                hottest_x = x;
                hottest_y = y;
                first = false;
            }
        }
    }
    if (first) return;

    int content_width = content_x1 - content_x0 + 1;
    int content_height = content_y1 - content_y0 + 1;
    double mean_luma = sum_luma / (double)(content_width * content_height);
    bool hottest_is_hot = black_hot ? (hottest_luma < mean_luma) : (hottest_luma > mean_luma);
    if (!hottest_is_hot) return;

    int min_dim = width < height ? width : height;
    int vicinity = clamp_i32((int)lround((double)min_dim * 0.028), 10, 24);
    int hot_left = hottest_x;
    int hot_right = hottest_x;
    int hot_top = hottest_y;
    int hot_bottom = hottest_y;
    int hot_count = 0;
    for (int y = hottest_y - vicinity; y <= hottest_y + vicinity; y++) {
        if (y < content_y0 || y > content_y1) continue;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = hottest_x - vicinity; x <= hottest_x + vicinity; x++) {
            if (x < content_x0 || x > content_x1) continue;
            int dx = x - hottest_x;
            int dy = y - hottest_y;
            if ((dx * dx) + (dy * dy) > (vicinity * vicinity)) continue;
            const uint8_t *px = row + (x * 4);
            double luma = rgba_luma_at(px);
            bool is_hot = black_hot ? (luma < mean_luma) : (luma > mean_luma);
            if (!is_hot) continue;
            hot_count++;
            if (x < hot_left) hot_left = x;
            if (x > hot_right) hot_right = x;
            if (y < hot_top) hot_top = y;
            if (y > hot_bottom) hot_bottom = y;
        }
    }

    int center_x = (hot_left + hot_right) / 2;
    int center_y = (hot_top + hot_bottom) / 2;
    int half_w = (hot_right - hot_left + 1) / 2;
    int half_h = (hot_bottom - hot_top + 1) / 2;
    int content_radius = (int)ceil(sqrt((double)(half_w * half_w + half_h * half_h)));
    int padding = clamp_i32((int)lround((double)min_dim * 0.008), 4, 8);
    int radius = clamp_i32(content_radius + padding, 8, min_dim / 5);
    int stroke = clamp_i32(2 + (hot_count / 24), 2, 5);
    draw_rgba_circle(rgba, rgba_stride, width, height, center_x, center_y, radius, stroke, 0xFF, 0x30, 0x30);
}

static void append_anomaly_box(anomaly_box_t *boxes, int *box_count,
                               float center_x_norm, float center_y_norm,
                               float box_w_norm, float box_h_norm,
                               uint8_t r, uint8_t g, uint8_t b,
                               float weight) {
    if (boxes == NULL || box_count == NULL) return;
    if (*box_count >= ANOMALY_MAX_BOXES_PER_FRAME) return;
    float half_w = box_w_norm * 0.5f;
    float half_h = box_h_norm * 0.5f;
    float left   = clamp01f(center_x_norm - half_w);
    float right  = clamp01f(center_x_norm + half_w);
    float top    = clamp01f(center_y_norm - half_h);
    float bottom = clamp01f(center_y_norm + half_h);
    if (right <= left || bottom <= top) return;
    anomaly_box_t *slot = &boxes[*box_count];
    slot->left_norm   = left;
    slot->top_norm    = top;
    slot->right_norm  = right;
    slot->bottom_norm = bottom;
    slot->r = r; slot->g = g; slot->b = b;
    slot->weight = weight < 0.0f ? 0.0f : (weight > 1.0f ? 1.0f : weight);
    *box_count += 1;
}

static int assemble_anomaly_boxes(const anomaly_state_t *state,
                                  const anomaly_config_t *cfg,
                                  int motion_box_algorithm,
                                  anomaly_box_t *boxes,
                                  int max_boxes) {
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

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    int box_count = 0;
    for (int ai = 0; ai < 4 && box_count < max_boxes; ai++) {
        if (!state->acc_active[ai]) continue;
        if (state->acc_hits[ai] < min_hits) continue;
        if (ai == 2) {
            uint8_t recent_mask =
                    state->acc_presence_mask[ai] &
                    (uint8_t)((1u << ANOMALY_MOTION_PRESENCE_WINDOW) - 1u);
            if (popcount_u8(recent_mask) < ANOMALY_MOTION_PRESENCE_MIN_HITS) {
                continue;
            }
        }
        float weight = 0.35f + 0.13f * (float)(state->acc_hits[ai] - min_hits);
        if (weight > 1.0f) weight = 1.0f;
        float bw = box_side * algo_box_scale[ai];
        append_anomaly_box(
                boxes,
                &box_count,
                state->acc_cx[ai],
                state->acc_cy[ai],
                bw,
                bw,
                algo_rgb[ai][0],
                algo_rgb[ai][1],
                algo_rgb[ai][2],
                weight);
        if (box_count > 0) {
            boxes[box_count - 1].algorithm = algo_bits[ai];
        }
    }
    return box_count;
}

static void draw_anomaly_boxes_rgba(uint8_t *rgba, int rgba_stride,
                                    int width, int height,
                                    const anomaly_box_t *boxes, int box_count) {
    if (rgba == NULL || boxes == NULL || width <= 0 || height <= 0 || box_count <= 0) return;
    int min_dim    = (width < height) ? width : height;
    int stroke_max = clamp_i32((int)lroundf((double)min_dim * 0.006), 2, 8);
    int cross_half = clamp_i32((int)lroundf((double)min_dim * 0.018), 6, 16);

    for (int i = 0; i < box_count; i++) {
        const anomaly_box_t *box = &boxes[i];
        int stroke = clamp_i32((int)lroundf(stroke_max * (double)box->weight), 1, stroke_max);
        int left   = clamp_i32((int)lroundf(box->left_norm   * (float)(width  - 1)), 0, width  - 1);
        int right  = clamp_i32((int)lroundf(box->right_norm  * (float)(width  - 1)), 0, width  - 1);
        int top    = clamp_i32((int)lroundf(box->top_norm    * (float)(height - 1)), 0, height - 1);
        int bottom = clamp_i32((int)lroundf(box->bottom_norm * (float)(height - 1)), 0, height - 1);
        if (right <= left || bottom <= top) continue;

        for (int t = 0; t < stroke; t++) {
            int top_y   = top    + t;
            int bottom_y = bottom - t;
            int left_x  = left   + t;
            int right_x = right  - t;
            if (top_y <= bottom_y) {
                draw_rgba_hline(rgba, rgba_stride, width, height, left, right, top_y,    box->r, box->g, box->b);
                if (bottom_y != top_y)
                    draw_rgba_hline(rgba, rgba_stride, width, height, left, right, bottom_y, box->r, box->g, box->b);
            }
            if (left_x <= right_x) {
                draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, left_x,  box->r, box->g, box->b);
                if (right_x != left_x)
                    draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, right_x, box->r, box->g, box->b);
            }
        }

        int cx = (left + right) / 2;
        int cy = (top  + bottom) / 2;
        int cross_start_x = cx - cross_half;
        int cross_end_x   = cx + cross_half;
        int cross_start_y = cy - cross_half;
        int cross_end_y   = cy + cross_half;
        for (int t = 0; t < stroke; t++) {
            int horiz_y = cy - (stroke / 2) + t;
            int vert_x  = cx - (stroke / 2) + t;
            draw_rgba_hline(rgba, rgba_stride, width, height, cross_start_x, cross_end_x, horiz_y, box->r, box->g, box->b);
            draw_rgba_vline(rgba, rgba_stride, width, height, cross_start_y, cross_end_y, vert_x,  box->r, box->g, box->b);
        }
    }
}

// ── Similarity transform ────────────────────────────────────────��──────────

// Fits a 2-D similarity transform (rotation + isotropic scale + translation)
// from N point correspondences src→dst in normalized [0,1] frame coordinates.
// Closed-form least-squares; no external library required.
// Returns identity with valid=false when degenerate (n<2 or anchors collinear).
similarity_2d_t fit_similarity_2d(
        const float *src_x, const float *src_y,
        const float *dst_x, const float *dst_y,
        int n) {
    similarity_2d_t r = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, false};
    if (n < 2) return r;

    double S = 0.0, Sx = 0.0, Sy = 0.0;
    double Sdx = 0.0, Sdy = 0.0, Sxdx = 0.0, Srot = 0.0;
    for (int i = 0; i < n; i++) {
        double xi = src_x[i], yi = src_y[i];
        double dxi = dst_x[i], dyi = dst_y[i];
        S    += xi*xi + yi*yi;
        Sx   += xi;    Sy   += yi;
        Sdx  += dxi;   Sdy  += dyi;
        Sxdx += xi*dxi + yi*dyi;
        Srot += xi*dyi - yi*dxi;
    }
    // Normal-equation denominator: N*Σ(x²+y²) - (Σx)²-(Σy)²
    double D = (double)n * S - (Sx*Sx + Sy*Sy);
    if (fabs(D) < 1e-10) return r;   // degenerate anchor geometry

    double a  = ((double)n * Sxdx - Sx*Sdx - Sy*Sdy) / D;
    double b  = ((double)n * Srot  + Sy*Sdx - Sx*Sdy) / D;
    double tx = (Sdx - Sx*a + Sy*b) / (double)n;
    double ty = (Sdy - Sy*a - Sx*b) / (double)n;

    double res = 0.0;
    for (int i = 0; i < n; i++) {
        double ex = a*src_x[i] - b*src_y[i] + tx - dst_x[i];
        double ey = b*src_x[i] + a*src_y[i] + ty - dst_y[i];
        res += sqrt(ex*ex + ey*ey);
    }
    r.a = (float)a;   r.b  = (float)b;
    r.tx = (float)tx; r.ty = (float)ty;
    r.mean_residual = (float)(res / (double)n);
    r.valid = true;
    return r;
}

bool anomaly_probe_thermal_point(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        const uint8_t          *rgba,
        int                     rgba_stride,
        int                     width,
        int                     height,
        float                   point_x_norm,
        float                   point_y_norm,
        anomaly_probe_t        *probe_out) {
    if (probe_out == NULL) return false;
    memset(probe_out, 0, sizeof(*probe_out));
    if (cfg == NULL || rgba == NULL || width <= 0 || height <= 0) return false;
    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) == 0) return false;

    probe_out->thermal_min_delta = effective_thermal_min_delta(cfg);

    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)width);
    int roi_x1 = width - roi_x0;
    int roi_y0 = (int)(margin * (float)height);
    int roi_y1 = height - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = width;  }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = height; }

    int px = clamp_i32((int)lroundf(clamp01f(point_x_norm) * (float)(width - 1)), 0, width - 1);
    int py = clamp_i32((int)lroundf(clamp01f(point_y_norm) * (float)(height - 1)), 0, height - 1);
    probe_out->pixel_x = px;
    probe_out->pixel_y = py;
    probe_out->inside_scan_zone = (px >= roi_x0 && px < roi_x1 && py >= roi_y0 && py < roi_y1);
    if (!probe_out->inside_scan_zone) return true;

    int sample_step = effective_sample_step(cfg, width, height);
    int roi_w = roi_x1 - roi_x0;
    int roi_h = roi_y1 - roi_y0;
    if (roi_w <= 0) roi_w = 1;
    if (roi_h <= 0) roi_h = 1;
    int sg_w = (roi_w + sample_step - 1) / sample_step;
    int sg_h = (roi_h + sample_step - 1) / sample_step;
    if (sg_w <= 0 || sg_h <= 0) return false;

    int sx = clamp_i32((px - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
    int sy = clamp_i32((py - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
    probe_out->sample_x = sx;
    probe_out->sample_y = sy;

    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    double *sg_luma = (double *)malloc(sg_count * sizeof(double));
    double *ii_sum = (double *)malloc(sg_count * sizeof(double));
    double *ii_sum2 = (double *)malloc(sg_count * sizeof(double));
    if (sg_luma == NULL || ii_sum == NULL || ii_sum2 == NULL) {
        free(sg_luma);
        free(ii_sum);
        free(ii_sum2);
        return false;
    }

    for (int gy = 0; gy < sg_h; gy++) {
        int y = roi_y0 + gy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int gx = 0; gx < sg_w; gx++) {
            int x = roi_x0 + gx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            const uint8_t *p = row + (x * 4);
            double r = (double)p[0], g = (double)p[1], b = (double)p[2];
            sg_luma[gy * sg_w + gx] = (0.2126 * r) + (0.7152 * g) + (0.0722 * b);
        }
    }

    for (int gy = 0; gy < sg_h; gy++) {
        for (int gx = 0; gx < sg_w; gx++) {
            double v = sg_luma[gy * sg_w + gx];
            double v2 = v * v;
            double a = (gy > 0) ? ii_sum[(gy - 1) * sg_w + gx] : 0.0;
            double l = (gx > 0) ? ii_sum[gy * sg_w + (gx - 1)] : 0.0;
            double al = (gy > 0 && gx > 0) ? ii_sum[(gy - 1) * sg_w + (gx - 1)] : 0.0;
            ii_sum[gy * sg_w + gx] = v + a + l - al;

            double a2 = (gy > 0) ? ii_sum2[(gy - 1) * sg_w + gx] : 0.0;
            double l2 = (gx > 0) ? ii_sum2[gy * sg_w + (gx - 1)] : 0.0;
            double al2 = (gy > 0 && gx > 0) ? ii_sum2[(gy - 1) * sg_w + (gx - 1)] : 0.0;
            ii_sum2[gy * sg_w + gx] = v2 + a2 + l2 - al2;
        }
    }

    const int R = ANOMALY_THERMAL_WIN_RADIUS;
    int wx0 = sx - R; if (wx0 < 0) wx0 = 0;
    int wx1 = sx + R; if (wx1 >= sg_w) wx1 = sg_w - 1;
    int wy0 = sy - R; if (wy0 < 0) wy0 = 0;
    int wy1 = sy + R; if (wy1 >= sg_h) wy1 = sg_h - 1;
    int n = (wx1 - wx0 + 1) * (wy1 - wy0 + 1);
    double lum = sg_luma[sy * sg_w + sx];
    double mean = ii_query(ii_sum, sg_w, wx0, wy0, wx1, wy1) / (double)n;
    double sum2 = ii_query(ii_sum2, sg_w, wx0, wy0, wx1, wy1);
    double std = sqrt(fmax(sum2 / (double)n - mean * mean, 1.0));
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    double abs_delta = black_hot ? (mean - lum) : (lum - mean);

    probe_out->valid = true;
    probe_out->sample_luma = (float)lum;
    probe_out->spatial_mean = (float)mean;
    probe_out->spatial_std = (float)std;
    probe_out->spatial_abs_delta = (float)abs_delta;
    probe_out->spatial_score = (abs_delta >= (double)probe_out->thermal_min_delta)
                               ? (float)(abs_delta / std) : -1.0f;

    bool bg_ready = (state != NULL
                     && state->bg_luma != NULL
                     && state->bg_sg_w == sg_w
                     && state->bg_sg_h == sg_h
                     && state->bg_warmup >= ANOMALY_THERMAL_BG_WARMUP);
    probe_out->bg_ready = bg_ready;
    probe_out->used_temporal_score = bg_ready;
    probe_out->effective_score = probe_out->spatial_score;

    if (bg_ready) {
        double sum_d = 0.0, sum_d2 = 0.0;
        int cnt_d = 0;
        for (int i = 0; i < sg_w * sg_h; i++) {
            float d = black_hot
                      ? (state->bg_luma[i] - (float)sg_luma[i])
                      : ((float)sg_luma[i] - state->bg_luma[i]);
            if (d > 0.0f) {
                sum_d += d;
                sum_d2 += (double)d * d;
                cnt_d++;
            }
        }
        double delta_mean = cnt_d > 0 ? sum_d / (double)cnt_d : 0.0;
        double delta_var = cnt_d > 1
                           ? fmax(sum_d2 / (double)cnt_d - delta_mean * delta_mean, 0.0)
                           : 0.0;
        double delta_norm = sqrt(delta_var);
        if (delta_norm < (double)ANOMALY_THERMAL_BG_NORM) {
            delta_norm = (double)ANOMALY_THERMAL_BG_NORM;
        }
        float delta = black_hot
                      ? (state->bg_luma[sy * sg_w + sx] - (float)lum)
                      : ((float)lum - state->bg_luma[sy * sg_w + sx]);
        probe_out->temporal_delta = delta;
        probe_out->temporal_mean = (float)delta_mean;
        probe_out->temporal_norm = (float)delta_norm;
        probe_out->temporal_score = (delta >= probe_out->thermal_min_delta)
                                    ? (float)((delta - delta_mean) / delta_norm)
                                    : -1.0f;
        probe_out->effective_score = probe_out->temporal_score;
    } else {
        probe_out->temporal_score = -1.0f;
    }

    free(sg_luma);
    free(ii_sum);
    free(ii_sum2);
    return true;
}

// ── State lifecycle ───────────────────────���────────────────────────────────

void anomaly_state_init(anomaly_state_t *state) {
    memset(state, 0, sizeof(*state));
}

void anomaly_state_reset(anomaly_state_t *state) {
    if (state == NULL) return;
    state->frame_counter = 0;
    memset(state->acc_cx,     0, sizeof(state->acc_cx));
    memset(state->acc_cy,     0, sizeof(state->acc_cy));
    memset(state->acc_hits,   0, sizeof(state->acc_hits));
    memset(state->acc_hold,   0, sizeof(state->acc_hold));
    memset(state->acc_presence_mask, 0, sizeof(state->acc_presence_mask));
    memset(state->acc_active, 0, sizeof(state->acc_active));
    if (state->prev_luma != NULL) {
        free(state->prev_luma);
        state->prev_luma = NULL;
    }
    state->prev_luma_width  = 0;
    state->prev_luma_height = 0;
    if (state->motion_persist != NULL) {
        free(state->motion_persist);
        state->motion_persist = NULL;
    }
    state->motion_persist_w = 0;
    state->motion_persist_h = 0;
    if (state->bg_luma != NULL) {
        free(state->bg_luma);
        state->bg_luma = NULL;
    }
    state->bg_sg_w  = 0;
    state->bg_sg_h  = 0;
    state->bg_warmup = 0;
}

void anomaly_state_cleanup(anomaly_state_t *state) {
    anomaly_state_reset(state);
}

// ── Main processing ─────────────────────────────────────────────���──────────

int anomaly_process_frame(
        anomaly_state_t        *state,
        const anomaly_config_t *cfg,
        uint8_t                *rgba,
        int                     rgba_stride,
        int                     width,
        int                     height,
        int64_t                 source_ts_us,
        anomaly_result_t       *result_out) {
    (void)source_ts_us;

    if (result_out != NULL) {
        memset(result_out, 0, sizeof(*result_out));
        result_out->box_count        = 0;
        result_out->had_discontinuity = false;
    }

    if (cfg == NULL) return 0;
    bool show_hot_overlay = cfg->show_hot_overlay;
    bool anomaly_detection_active = cfg->enabled && cfg->algorithm_mask != 0;
    if (!anomaly_detection_active && !show_hot_overlay) return 0;
    if (width <= 0 || height <= 0) return 0;

    int frame_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
    float thermal_min_delta = effective_thermal_min_delta(cfg);
    state->frame_counter += 1;
    if ((state->frame_counter % frame_stride) != 0) {
        int skipped_box_count = 0;
        anomaly_box_t skipped_boxes[ANOMALY_MAX_BOXES_PER_FRAME];
        if (anomaly_detection_active) {
            const int skipped_motion_box_algorithm =
                    (cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0
                    ? ANOMALY_ALGO_MOTION_TOLERANCE
                    : ANOMALY_ALGO_MOTION;
            skipped_box_count = assemble_anomaly_boxes(
                    state,
                    cfg,
                    skipped_motion_box_algorithm,
                    skipped_boxes,
                    ANOMALY_MAX_BOXES_PER_FRAME);
        }
        if (result_out != NULL) {
            result_out->box_count = skipped_box_count;
            for (int i = 0; i < skipped_box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++) {
                result_out->boxes[i] = skipped_boxes[i];
            }
        }
        if (rgba != NULL) {
            if (show_hot_overlay) {
                draw_hot_overlay_rgba(rgba, rgba_stride, width, height, cfg->thermal_polarity);
            }
            if (skipped_box_count > 0) {
                draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, skipped_boxes, skipped_box_count);
            }
        }
        return skipped_box_count;
    }

    // ── ROI bounds (centered scan zone) ─────────────────────────────────
    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)width);
    int roi_x1 = width  - roi_x0;
    int roi_y0 = (int)(margin * (float)height);
    int roi_y1 = height - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = width;  }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = height; }

    int sample_step = effective_sample_step(cfg, width, height);
    int motion_sample_step = effective_motion_sample_step(cfg, width, height);

    // ── Build full-frame luma grid (needed for GMV offset lookups) ───────
    int motion_step  = motion_sample_step * 2;
    int motion_w     = (width  + motion_step - 1) / motion_step;
    int motion_h     = (height + motion_step - 1) / motion_step;
    size_t motion_count = (size_t)motion_w * (size_t)motion_h;
    uint8_t *curr_luma  = NULL;
    if (motion_count > 0) {
        curr_luma = (uint8_t *)malloc(motion_count);
        if (curr_luma != NULL) {
            int idx = 0;
            for (int y = 0; y < height && idx < (int)motion_count; y += motion_step) {
                const uint8_t *row = rgba + (y * rgba_stride);
                for (int x = 0; x < width && idx < (int)motion_count; x += motion_step) {
                    const uint8_t *px = row + (x * 4);
                    curr_luma[idx++] = (uint8_t)((54 * px[0] + 183 * px[1] + 18 * px[2]) >> 8);
                }
            }
        }
    }

    // ── Camera motion estimation (similarity transform) ──────────────────
    // Sparse block-matching on a 3×3 anchor grid within the ROI produces
    // correspondences; closed-form least-squares fit yields the 4-parameter
    // similarity transform T that maps current-frame positions to their
    // matching positions in the previous frame:
    //   prev = T(curr):  prev_x = a*curr_x - b*curr_y + tx
    //                    prev_y = b*curr_x + a*curr_y + ty
    // Handles pan (tx/ty), yaw rotation (a=cosθ, b=sinθ), and zoom (s=√(a²+b²)).
    similarity_2d_t sim = {1.0f, 0.0f, 0.0f, 0.0f, 0.0f, false};
    bool scene_discontinuity = false;
    anomaly_debug_gmv_anchor_t gmv_debug_anchors[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
    int gmv_debug_anchor_count = 0;
    memset(gmv_debug_anchors, 0, sizeof(gmv_debug_anchors));

    if (curr_luma != NULL &&
        state->prev_luma != NULL &&
        state->prev_luma_width  == motion_w &&
        state->prev_luma_height == motion_h) {

        int ph = ANOMALY_GMV_PATCH_HALF;
        int sr = ANOMALY_GMV_SEARCH_RADIUS;

        int roi_mgx0 = roi_x0 / motion_step;
        int roi_mgx1 = (roi_x1 - 1) / motion_step;
        int roi_mgy0 = roi_y0 / motion_step;
        int roi_mgy1 = (roi_y1 - 1) / motion_step;
        roi_mgx0 = roi_mgx0 < 0         ? 0         : roi_mgx0;
        roi_mgx1 = roi_mgx1 >= motion_w ? motion_w-1 : roi_mgx1;
        roi_mgy0 = roi_mgy0 < 0         ? 0         : roi_mgy0;
        roi_mgy1 = roi_mgy1 >= motion_h ? motion_h-1 : roi_mgy1;

        float fw = (float)(width  > 1 ? width  - 1 : 1);
        float fh = (float)(height > 1 ? height - 1 : 1);

        int anchor_dx[ANOMALY_GMV_MAX_DEBUG_ANCHORS], anchor_dy[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
        int anchor_ax[ANOMALY_GMV_MAX_DEBUG_ANCHORS], anchor_ay[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
        int anchor_count = 0;

        for (int gy = 0; gy < ANOMALY_GMV_ZONE_GRID; gy++) {
            for (int gx = 0; gx < ANOMALY_GMV_ZONE_GRID; gx++) {
                int zx0 = roi_mgx0 + (roi_mgx1 - roi_mgx0) * gx / ANOMALY_GMV_ZONE_GRID;
                int zx1 = roi_mgx0 + (roi_mgx1 - roi_mgx0) * (gx + 1) / ANOMALY_GMV_ZONE_GRID;
                int zy0 = roi_mgy0 + (roi_mgy1 - roi_mgy0) * gy / ANOMALY_GMV_ZONE_GRID;
                int zy1 = roi_mgy0 + (roi_mgy1 - roi_mgy0) * (gy + 1) / ANOMALY_GMV_ZONE_GRID;
                zx0 = clamp_i32(zx0, ph + sr, motion_w - 1 - ph - sr);
                zx1 = clamp_i32(zx1, ph + sr, motion_w - 1 - ph - sr);
                zy0 = clamp_i32(zy0, ph + sr, motion_h - 1 - ph - sr);
                zy1 = clamp_i32(zy1, ph + sr, motion_h - 1 - ph - sr);
                if (zx1 < zx0 || zy1 < zy0) continue;

                int ax = -1, ay = -1;
                int best_feature = -1;
                for (int cy = zy0; cy <= zy1; cy++) {
                    for (int cx = zx0; cx <= zx1; cx++) {
                        int feature = gmv_feature_score(curr_luma, motion_w, motion_h, cx, cy);
                        if (feature > best_feature) {
                            best_feature = feature;
                            ax = cx;
                            ay = cy;
                        }
                    }
                }
                if (ax < 0 || ay < 0 || best_feature < ANOMALY_GMV_MIN_TEXTURE_SCORE) continue;

                int  best_dx = 0, best_dy = 0;
                long best_sad = 0x7FFFFFFFL;
                long second_best_sad = 0x7FFFFFFFL;
                for (int dy = -sr; dy <= sr; dy++) {
                    for (int dx = -sr; dx <= sr; dx++) {
                        long sad = 0;
                        bool valid_patch = true;
                        for (int ky = -ph; ky <= ph; ky++) {
                            for (int kx = -ph; kx <= ph; kx++) {
                                int cx = ax + kx;
                                int cy = ay + ky;
                                int px = ax + dx + kx;
                                int py = ay + dy + ky;
                                if (cx < 0 || cx >= motion_w || cy < 0 || cy >= motion_h ||
                                    px < 0 || px >= motion_w || py < 0 || py >= motion_h) {
                                    valid_patch = false;
                                    break;
                                }
                                int cv = curr_luma[cy * motion_w + cx];
                                int pv = state->prev_luma[py * motion_w + px];
                                int d = cv - pv; sad += d < 0 ? -d : d;
                            }
                            if (!valid_patch) break;
                        }
                        if (!valid_patch) continue;
                        if (sad < best_sad) {
                            second_best_sad = best_sad;
                            best_sad = sad;
                            best_dx = dx;
                            best_dy = dy;
                        } else if (sad < second_best_sad) {
                            second_best_sad = sad;
                        }
                    }
                }
                if (second_best_sad < 0x7FFFFFFFL &&
                    (second_best_sad - best_sad) < ANOMALY_GMV_MIN_MATCH_MARGIN) {
                    continue;
                }
                if (gmv_debug_anchor_count < ANOMALY_GMV_MAX_DEBUG_ANCHORS) {
                    anomaly_debug_gmv_anchor_t *dbg = &gmv_debug_anchors[gmv_debug_anchor_count++];
                    dbg->valid = true;
                    dbg->zone_gx = gx;
                    dbg->zone_gy = gy;
                    dbg->pixel_x = ax * motion_step;
                    dbg->pixel_y = ay * motion_step;
                    dbg->x_norm = (float)dbg->pixel_x / fw;
                    dbg->y_norm = (float)dbg->pixel_y / fh;
                    dbg->texture_score = best_feature;
                    dbg->match_dx = best_dx;
                    dbg->match_dy = best_dy;
                    dbg->best_sad = best_sad >= 0x7FFFFFFFL ? -1 : (int)best_sad;
                    dbg->second_best_sad = second_best_sad >= 0x7FFFFFFFL ? -1 : (int)second_best_sad;
                }
                anchor_ax[anchor_count] = ax;
                anchor_ay[anchor_count] = ay;
                anchor_dx[anchor_count] = best_dx;
                anchor_dy[anchor_count] = best_dy;
                anchor_count++;
            }
        }

        if (anchor_count >= ANOMALY_GMV_MIN_ANCHORS) {
            float src_x[ANOMALY_GMV_MAX_DEBUG_ANCHORS], src_y[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            float dst_x[ANOMALY_GMV_MAX_DEBUG_ANCHORS], dst_y[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
            for (int i = 0; i < anchor_count; i++) {
                src_x[i] = (float)(anchor_ax[i] * motion_step) / fw;
                src_y[i] = (float)(anchor_ay[i] * motion_step) / fh;
                dst_x[i] = (float)((anchor_ax[i] + anchor_dx[i]) * motion_step) / fw;
                dst_y[i] = (float)((anchor_ay[i] + anchor_dy[i]) * motion_step) / fh;
            }
            sim = fit_similarity_2d(src_x, src_y, dst_x, dst_y, anchor_count);

            if (sim.valid && anchor_count >= 3) {
                float worst_residual = -1.0f;
                int worst_idx = -1;
                for (int i = 0; i < anchor_count; i++) {
                    float ex = sim.a * src_x[i] - sim.b * src_y[i] + sim.tx - dst_x[i];
                    float ey = sim.b * src_x[i] + sim.a * src_y[i] + sim.ty - dst_y[i];
                    float residual = sqrtf(ex * ex + ey * ey);
                    if (residual > worst_residual) {
                        worst_residual = residual;
                        worst_idx = i;
                    }
                }
                if (worst_idx >= 0 && worst_residual > (ANOMALY_GMV_RESIDUAL_THRESH * 1.5f)) {
                    float src_x2[ANOMALY_GMV_MAX_DEBUG_ANCHORS], src_y2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
                    float dst_x2[ANOMALY_GMV_MAX_DEBUG_ANCHORS], dst_y2[ANOMALY_GMV_MAX_DEBUG_ANCHORS];
                    int kept = 0;
                    for (int i = 0; i < anchor_count; i++) {
                        if (i == worst_idx) continue;
                        src_x2[kept] = src_x[i];
                        src_y2[kept] = src_y[i];
                        dst_x2[kept] = dst_x[i];
                        dst_y2[kept] = dst_y[i];
                        kept++;
                    }
                    similarity_2d_t refit = fit_similarity_2d(src_x2, src_y2, dst_x2, dst_y2, kept);
                    if (refit.valid && refit.mean_residual < sim.mean_residual) {
                        sim = refit;
                    }
                }
            }

            float scale = sqrtf(sim.a * sim.a + sim.b * sim.b);
            if (!sim.valid ||
                sim.mean_residual > ANOMALY_GMV_RESIDUAL_THRESH ||
                scale < ANOMALY_GMV_MIN_SCALE ||
                scale > ANOMALY_GMV_MAX_SCALE) {
                scene_discontinuity = true;
            }
        }
    }

    if (result_out != NULL) result_out->had_discontinuity = scene_discontinuity;
    if (result_out != NULL) {
        result_out->gmv_debug.valid = (curr_luma != NULL &&
            state->prev_luma != NULL &&
            state->prev_luma_width == motion_w &&
            state->prev_luma_height == motion_h);
        result_out->gmv_debug.scene_discontinuity = scene_discontinuity;
        result_out->gmv_debug.sample_step = motion_sample_step;
        result_out->gmv_debug.motion_step = motion_step;
        result_out->gmv_debug.anchor_count = gmv_debug_anchor_count;
        result_out->gmv_debug.fit_a = sim.a;
        result_out->gmv_debug.fit_b = sim.b;
        result_out->gmv_debug.fit_tx = sim.tx;
        result_out->gmv_debug.fit_ty = sim.ty;
        result_out->gmv_debug.fit_scale = sqrtf(sim.a * sim.a + sim.b * sim.b);
        result_out->gmv_debug.fit_theta_deg = atan2f(sim.b, sim.a) * (180.0f / 3.14159265f);
        result_out->gmv_debug.fit_mean_residual = sim.mean_residual;
        for (int i = 0; i < gmv_debug_anchor_count && i < ANOMALY_GMV_MAX_DEBUG_ANCHORS; i++) {
            result_out->gmv_debug.anchors[i] = gmv_debug_anchors[i];
        }
    }

    // ── Compensate accumulators for camera motion (or wipe on discontinuity)
    // T⁻¹(p) = Aᵀ * (p - t) / (a²+b²)  where Aᵀ = [[a,b],[-b,a]]
    for (int ai = 0; ai < 4; ai++) {
        if (scene_discontinuity) {
            state->acc_active[ai] = false;
            state->acc_hits[ai]   = 0;
            state->acc_hold[ai]   = 0;
        } else if (state->acc_active[ai] && sim.valid) {
            float scale2 = sim.a * sim.a + sim.b * sim.b;
            if (scale2 < 1e-6f) scale2 = 1e-6f;
            float dx = state->acc_cx[ai] - sim.tx;
            float dy = state->acc_cy[ai] - sim.ty;
            float nx = ( sim.a * dx + sim.b * dy) / scale2;
            float ny = (-sim.b * dx + sim.a * dy) / scale2;
            state->acc_cx[ai] = nx < 0.0f ? 0.0f : (nx > 1.0f ? 1.0f : nx);
            state->acc_cy[ai] = ny < 0.0f ? 0.0f : (ny > 1.0f ? 1.0f : ny);
        }
    }

    // ── Statistics pass ──────────────────────────────────────────────────
    // Thermal detection uses integral-image local statistics so each sample
    // point is compared against its immediate pixel neighbourhood rather than
    // a coarse tile.  This is critical for aerial SAR footage: the scene has
    // high global variance (tree crowns vs. clearings) so any tile large
    // enough to contain reliable statistics also spans multiple features,
    // making a person's subtle warmth statistically invisible at 8σ.
    // With a small window (ANOMALY_THERMAL_WIN_RADIUS sampled pixels ≈
    // RADIUS×sample_step real pixels) the window usually stays inside one
    // clearing, giving the person's darker signature a fair local comparison.
    //
    // Color detection retains the tile-grid approach (ANOMALY_LOCAL_TILE_SIZE);
    // in IR footage all channels are near-greyscale so colour is less useful,
    // and the tile grid is cheap to keep for visible-light modes.

    int roi_w = roi_x1 - roi_x0;
    int roi_h = roi_y1 - roi_y0;
    if (roi_w <= 0) roi_w = 1;
    if (roi_h <= 0) roi_h = 1;

    // Sampled-grid dimensions for integral images.
    int sg_w = (roi_w + sample_step - 1) / sample_step;
    int sg_h = (roi_h + sample_step - 1) / sample_step;

    // Heap-allocate integral image arrays (freed at end of this function).
    // Two channels: luma sum and luma sum-of-squares for variance.
    double *sg_luma  = (double *)malloc((size_t)sg_w * sg_h * sizeof(double));
    double *ii_sum   = (double *)malloc((size_t)sg_w * sg_h * sizeof(double));
    double *ii_sum2  = (double *)malloc((size_t)sg_w * sg_h * sizeof(double));
    if (!sg_luma || !ii_sum || !ii_sum2) {
        free(sg_luma); free(ii_sum); free(ii_sum2);
        if (curr_luma) { free(state->prev_luma); state->prev_luma = curr_luma;
                         state->prev_luma_width = motion_w; state->prev_luma_height = motion_h; }
        return 0;
    }

    // Colour tile accumulators (kept for ANOMALY_ALGO_COLOR).
#define T ANOMALY_LOCAL_TILE_SIZE
    double tile_sum_l[T][T], tile_sum_l2[T][T];
    double tile_sum_u[T][T], tile_sum_u2[T][T];
    double tile_sum_v[T][T], tile_sum_v2[T][T];
    int    tile_n[T][T];
    for (int tr = 0; tr < T; tr++)
        for (int tc = 0; tc < T; tc++) {
            tile_sum_l[tr][tc] = tile_sum_l2[tr][tc] = 0.0;
            tile_sum_u[tr][tc] = tile_sum_u2[tr][tc] = 0.0;
            tile_sum_v[tr][tc] = tile_sum_v2[tr][tc] = 0.0;
            tile_n[tr][tc] = 0;
        }

    // Global sums (used for color-tile fallback and early-exit check).
    double sum_l = 0.0, sum_l2 = 0.0;
    double sum_u = 0.0, sum_u2 = 0.0;
    double sum_v = 0.0, sum_v2 = 0.0;
    int sample_count = 0;

    // Fill sampled-luma grid + colour tile accumulators in one pass.
    for (int sy = 0; sy < sg_h; sy++) {
        int y  = roi_y0 + sy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        int tr = sy * T / sg_h;
        if (tr >= T) tr = T - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int sx = 0; sx < sg_w; sx++) {
            int x  = roi_x0 + sx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            int tc = sx * T / sg_w;
            if (tc >= T) tc = T - 1;
            const uint8_t *px = row + (x * 4);
            double r = (double)px[0], g = (double)px[1], b = (double)px[2];
            double lum = (0.2126 * r) + (0.7152 * g) + (0.0722 * b);
            double u   = (-0.14713 * r) - (0.28886 * g) + (0.43600 * b);
            double v   = ( 0.61500 * r) - (0.51499 * g) - (0.10001 * b);
            sg_luma[sy * sg_w + sx] = lum;
            sum_l  += lum; sum_l2 += lum * lum;
            sum_u  += u;  sum_u2  += u * u;
            sum_v  += v;  sum_v2  += v * v;
            tile_sum_l[tr][tc]  += lum; tile_sum_l2[tr][tc]  += lum * lum;
            tile_sum_u[tr][tc]  += u;  tile_sum_u2[tr][tc]  += u * u;
            tile_sum_v[tr][tc]  += v;  tile_sum_v2[tr][tc]  += v * v;
            tile_n[tr][tc]++;
            sample_count++;
        }
    }

    if (sample_count <= 1) {
        free(sg_luma); free(ii_sum); free(ii_sum2);
        if (curr_luma != NULL) {
            if (state->prev_luma != NULL) free(state->prev_luma);
            state->prev_luma        = curr_luma;
            state->prev_luma_width  = motion_w;
            state->prev_luma_height = motion_h;
        }
        return 0;
    }

    // Build 2-D integral images from sg_luma (in-place row-then-column scan).
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            double v  = sg_luma[sy * sg_w + sx];
            double v2 = v * v;
            double a  = (sy > 0) ? ii_sum [(sy-1)*sg_w + sx]   : 0.0;
            double l  = (sx > 0) ? ii_sum [sy*sg_w + (sx-1)]   : 0.0;
            double al = (sy > 0 && sx > 0) ? ii_sum [(sy-1)*sg_w + (sx-1)] : 0.0;
            ii_sum [sy*sg_w + sx] = v  + a  + l  - al;
            double a2  = (sy > 0) ? ii_sum2[(sy-1)*sg_w + sx]   : 0.0;
            double l2  = (sx > 0) ? ii_sum2[sy*sg_w + (sx-1)]   : 0.0;
            double al2 = (sy > 0 && sx > 0) ? ii_sum2[(sy-1)*sg_w + (sx-1)] : 0.0;
            ii_sum2[sy*sg_w + sx] = v2 + a2 + l2 - al2;
        }
    }

    // Colour tile mean/std (fall back to global if tile too sparse).
    double g_mean_l = sum_l / (double)sample_count;
    double g_std_l  = sqrt(fmax((sum_l2/(double)sample_count) - g_mean_l*g_mean_l, 1.0));
    double g_mean_u = sum_u / (double)sample_count;
    double g_std_u  = sqrt(fmax((sum_u2/(double)sample_count) - g_mean_u*g_mean_u, 1.0));
    double g_mean_v = sum_v / (double)sample_count;
    double g_std_v  = sqrt(fmax((sum_v2/(double)sample_count) - g_mean_v*g_mean_v, 1.0));

    double tile_mean_l[T][T], tile_std_l[T][T];
    double tile_mean_u[T][T], tile_std_u[T][T];
    double tile_mean_v[T][T], tile_std_v[T][T];
    for (int tr = 0; tr < T; tr++) {
        for (int tc = 0; tc < T; tc++) {
            int n = tile_n[tr][tc];
            if (n >= ANOMALY_LOCAL_TILE_MIN_N) {
                double fn = (double)n;
                tile_mean_l[tr][tc] = tile_sum_l[tr][tc] / fn;
                tile_std_l[tr][tc]  = sqrt(fmax(tile_sum_l2[tr][tc]/fn - tile_mean_l[tr][tc]*tile_mean_l[tr][tc], 1.0));
                tile_mean_u[tr][tc] = tile_sum_u[tr][tc] / fn;
                tile_std_u[tr][tc]  = sqrt(fmax(tile_sum_u2[tr][tc]/fn - tile_mean_u[tr][tc]*tile_mean_u[tr][tc], 1.0));
                tile_mean_v[tr][tc] = tile_sum_v[tr][tc] / fn;
                tile_std_v[tr][tc]  = sqrt(fmax(tile_sum_v2[tr][tc]/fn - tile_mean_v[tr][tc]*tile_mean_v[tr][tc], 1.0));
            } else {
                tile_mean_l[tr][tc] = g_mean_l; tile_std_l[tr][tc] = g_std_l;
                tile_mean_u[tr][tc] = g_mean_u; tile_std_u[tr][tc] = g_std_u;
                tile_mean_v[tr][tc] = g_mean_v; tile_std_v[tr][tc] = g_std_v;
            }
        }
    }
#undef T

    // ── Per-pixel scoring ────────────────────────────────────────────────
    // Thermal: integral-image local window of radius ANOMALY_THERMAL_WIN_RADIUS
    //   sampled pixels.  At sample_step=4 (HD/FHD) that is a
    //   (2R+1)×(2R+1) window of roughly (2R+1)×4 real pixels on each side.
    //   R=3 → 7×7 samples ≈ 28×28 real pixels — small enough to stay inside
    //   a single clearing yet large enough for reliable statistics (49 samples).
    //   Produces a best-candidate pixel (best_thermal) used as fallback during
    //   EMA background warmup; after warmup the temporal pass below replaces it.
    // Color: 8×8 tile grid (unchanged).

    // Inline integral-image rectangle query.
    size_t sg_count = (size_t)sg_w * (size_t)sg_h;
    float *saliency_spatial_map = NULL;
    float *saliency_color_map = NULL;
    float *saliency_motion_map = NULL;
    float *saliency_registration_map = NULL;
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        saliency_spatial_map = (float *)malloc(sg_count * sizeof(float));
        saliency_color_map   = (float *)malloc(sg_count * sizeof(float));
        saliency_motion_map  = (float *)malloc(sg_count * sizeof(float));
        saliency_registration_map = (float *)malloc(sg_count * sizeof(float));
        if (saliency_spatial_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_spatial_map[i] = -1.0f;
        }
        if (saliency_color_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_color_map[i] = 0.0f;
        }
        if (saliency_motion_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_motion_map[i] = 0.0f;
        }
        if (saliency_registration_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_registration_map[i] = 1.0f;
        }
    }

    float best_color = -1.0f, best_thermal = -1.0f, best_persist = -1.0f;
    int   best_color_x = 0, best_color_y = 0;
    int   best_thermal_x = 0, best_thermal_y = 0;
    int   best_persist_x = 0, best_persist_y = 0;
    anomaly_debug_candidate_t saliency_top[ANOMALY_DEBUG_TOP_CANDIDATES];
    memset(saliency_top, 0, sizeof(saliency_top));
    int saliency_top_count = 0;
    float saliency_tracked_score_pre = -1.0f;
    bool saliency_switch_suppressed = false;

    const int R = ANOMALY_THERMAL_WIN_RADIUS;

    for (int sy = 0; sy < sg_h; sy++) {
        int y  = roi_y0 + sy * sample_step;
        if (y >= roi_y1) y = roi_y1 - 1;
        int tr = sy * ANOMALY_LOCAL_TILE_SIZE / sg_h;
        if (tr >= ANOMALY_LOCAL_TILE_SIZE) tr = ANOMALY_LOCAL_TILE_SIZE - 1;

        for (int sx = 0; sx < sg_w; sx++) {
            int x  = roi_x0 + sx * sample_step;
            if (x >= roi_x1) x = roi_x1 - 1;
            int tc = sx * ANOMALY_LOCAL_TILE_SIZE / sg_w;
            if (tc >= ANOMALY_LOCAL_TILE_SIZE) tc = ANOMALY_LOCAL_TILE_SIZE - 1;

            double lum = sg_luma[sy * sg_w + sx];

            if (anomaly_detection_active && (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
                // Integral-image window query.
                int wx0 = sx - R; if (wx0 < 0) wx0 = 0;
                int wx1 = sx + R; if (wx1 >= sg_w) wx1 = sg_w - 1;
                int wy0 = sy - R; if (wy0 < 0) wy0 = 0;
                int wy1 = sy + R; if (wy1 >= sg_h) wy1 = sg_h - 1;
                int    n    = (wx1-wx0+1) * (wy1-wy0+1);
                double wsum  = ii_query(ii_sum,  sg_w, wx0, wy0, wx1, wy1);
                double wsum2 = ii_query(ii_sum2, sg_w, wx0, wy0, wx1, wy1);
                double mean  = wsum / (double)n;
                double std   = sqrt(fmax(wsum2/(double)n - mean*mean, 1.0));
                double abs_delta = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                                   ? (mean - lum) : (lum - mean);
                if (abs_delta >= (double)thermal_min_delta) {
                    float ts = (float)(abs_delta / std);
                    float global_dark_score = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                        ? (float)((g_mean_l - lum) / g_std_l)
                        : (float)((lum - g_mean_l) / g_std_l);
                    if (global_dark_score < 0.0f) global_dark_score = 0.0f;
                    if (global_dark_score > 3.0f) global_dark_score = 3.0f;
                    float saliency_spatial = ts + 0.85f * global_dark_score;
                    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 &&
                        ts > best_thermal) {
                        best_thermal = ts; best_thermal_x = x; best_thermal_y = y;
                    }
                    if (saliency_spatial_map != NULL) {
                        saliency_spatial_map[sy * sg_w + sx] = saliency_spatial;
                    }
                }
            }

            if (anomaly_detection_active && (cfg->algorithm_mask & (ANOMALY_ALGO_COLOR | ANOMALY_ALGO_PERSIST)) != 0) {
                const uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
                double r = (double)px[0], g = (double)px[1], b = (double)px[2];
                double u = (-0.14713 * r) - (0.28886 * g) + (0.43600 * b);
                double v = ( 0.61500 * r) - (0.51499 * g) - (0.10001 * b);
                float cs = (float)(fabs((u - tile_mean_u[tr][tc]) / tile_std_u[tr][tc])
                                 + fabs((v - tile_mean_v[tr][tc]) / tile_std_v[tr][tc]));
                if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0 &&
                    cs > best_color) {
                    best_color = cs; best_color_x = x; best_color_y = y;
                }
                if (saliency_color_map != NULL) {
                    float color_support = cs - 2.0f;
                    if (color_support < 0.0f) color_support = 0.0f;
                    if (color_support > 4.0f) color_support = 4.0f;
                    saliency_color_map[sy * sg_w + sx] = color_support;
                }
            }
        }
    }
    // ── One-sided EMA thermal background: score + update ────────────────
    // The background model tracks each pixel's "cold" (background) state.
    // Fast adaptation toward brighter/colder (α=ALPHA_COOL per analyzed frame)
    // means legitimate scene changes (drone drift, lighting) are absorbed
    // quickly.  Slow adaptation toward darker/warmer (α=ALPHA_WARM) means a
    // subject that is persistently warmer than its local history scores high
    // every frame the camera is on them.  Score = (bg - current) / NORM.
    //
    // This second thermal pass REPLACES the spatial score from the loop above
    // once the background model has warmed up.  During the first
    // ANOMALY_THERMAL_BG_WARMUP analyzed frames after init or scene cut, the
    // spatial integral-image score above provides uninterrupted coverage.
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);

    bool bg_valid = (state->bg_luma != NULL
                     && state->bg_sg_w == sg_w
                     && state->bg_sg_h == sg_h
                     && state->bg_warmup >= ANOMALY_THERMAL_BG_WARMUP
                     && !scene_discontinuity);
    double delta_mean = 0.0;
    double delta_norm = (double)ANOMALY_THERMAL_BG_NORM;
    if (result_out != NULL) {
        result_out->saliency_debug.bg_ready = bg_valid;
    }

    if (anomaly_detection_active && bg_valid && (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
        double sum_d = 0.0, sum_d2 = 0.0;
        int cnt_d = 0;
        for (int i = 0; i < sg_w * sg_h; i++) {
            float d = black_hot
                ? (state->bg_luma[i] - (float)sg_luma[i])
                : ((float)sg_luma[i] - state->bg_luma[i]);
            if (d > 0.0f) { sum_d += d; sum_d2 += (double)d * d; cnt_d++; }
        }
        delta_mean = cnt_d > 0 ? sum_d / (double)cnt_d : 0.0;
        double delta_var = cnt_d > 1
            ? fmax(sum_d2 / (double)cnt_d - delta_mean * delta_mean, 0.0) : 0.0;
        delta_norm = sqrt(delta_var);
        if (delta_norm < (double)ANOMALY_THERMAL_BG_NORM)
            delta_norm = (double)ANOMALY_THERMAL_BG_NORM;
    }

    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 && bg_valid) {
        // Temporal background score replaces the spatial integral-image score.
        // Two-pass approach implementing per-frame noise-floor normalisation:
        //   When the camera pans over warm vegetation the bg_delta map has a
        //   high mean and high spread — nearly all pixels look "warm."  Scoring
        //   each pixel relative to the frame's own bg_delta distribution (mean
        //   and std) automatically raises the bar in high-motion frames and
        //   lowers it in quiet frames.  A subject that is merely the warmest
        //   warm thing in a sea of panning-induced warmth scores only modestly;
        //   a subject that is dramatically warmer than the frame's temporal
        //   noise floor scores very high.
        //
        //   Pass 1: accumulate positive bg_delta statistics (mean, std).
        //   Pass 2: score = (delta - delta_mean) / delta_norm
        //     where delta_norm = max(delta_std, ANOMALY_THERMAL_BG_NORM).
        //   Using the fixed NORM as a floor ensures the formula degrades
        //   gracefully to the old fixed-threshold behaviour in quiet frames.

        // Pass 2 — find the pixel with the highest relative temporal score.
        best_thermal   = -1.0f;
        best_thermal_x = 0;
        best_thermal_y = 0;
        for (int sy = 0; sy < sg_h; sy++) {
            for (int sx = 0; sx < sg_w; sx++) {
                double lum = sg_luma[sy * sg_w + sx];
                float  bg  = state->bg_luma[sy * sg_w + sx];
                // Positive delta: pixel is warmer than its stored background.
                float delta = black_hot ? (bg - (float)lum) : ((float)lum - bg);
                if (delta < thermal_min_delta) continue;
                // Relative score: std-devs above the frame's temporal mean.
                float ts = (float)((delta - delta_mean) / delta_norm);
                if (ts > best_thermal) {
                    best_thermal   = ts;
                    best_thermal_x = roi_x0 + sx * sample_step;
                    best_thermal_y = roi_y0 + sy * sample_step;
                }
            }
        }
    }
    // Update (or initialise) the background EMA.
    if (scene_discontinuity || state->bg_luma == NULL
            || state->bg_sg_w != sg_w || state->bg_sg_h != sg_h) {
        // (Re-)initialise: seed background with the current frame's luma.
        free(state->bg_luma);
        state->bg_luma = (float *)malloc((size_t)sg_w * sg_h * sizeof(float));
        state->bg_sg_w  = sg_w;
        state->bg_sg_h  = sg_h;
        state->bg_warmup = 0;
        if (state->bg_luma) {
            for (int i = 0; i < sg_w * sg_h; i++)
                state->bg_luma[i] = (float)sg_luma[i];
        }
    } else {
        // EMA update: fast toward colder/brighter, slow toward warmer/darker.
        // black_hot is already declared in the temporal scoring section above.
        for (int i = 0; i < sg_w * sg_h; i++) {
            float cur = (float)sg_luma[i];
            float bg  = state->bg_luma[i];
            float delta = cur - bg;   // positive = pixel got brighter (colder in BH)
            // In black-hot: brighter = colder = background direction → fast adapt.
            // In white-hot: darker   = colder = background direction → fast adapt.
            bool toward_cold = black_hot ? (delta > 0.0f) : (delta < 0.0f);
            float alpha = toward_cold ? ANOMALY_THERMAL_BG_ALPHA_COOL
                                      : ANOMALY_THERMAL_BG_ALPHA_WARM;
            state->bg_luma[i] = bg + alpha * delta;
        }
        state->bg_warmup++;
    }

    // ── GMV-compensated motion scoring over ROI ──────────────────────────
    float best_motion = -1.0f;
    int   best_motion_x = 0, best_motion_y = 0;
    float motion_evidence_scale = effective_motion_evidence_scale(cfg);
    int   motion_top_count = 0;
    anomaly_debug_candidate_t motion_top[ANOMALY_DEBUG_TOP_CANDIDATES];
    memset(motion_top, 0, sizeof(motion_top));
    float best_motion_texture_scale = 0.0f;
    float best_motion_structure_scale = 0.0f;
    float best_motion_support_scale = 0.0f;
    float best_motion_persistence_scale = 1.0f;
    float best_motion_component_area_frac = 0.0f;
    float best_motion_component_span_frac = 0.0f;
    float best_motion_component_fill_ratio = 0.0f;
    float best_motion_zoom_scale = 1.0f;
    float best_motion_broad_scale = 1.0f;
    float debug_global_motion_load = 0.0f;

    bool use_motion_tolerance = (cfg->algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE) != 0;
    bool use_stable_motion = ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0) && !use_motion_tolerance;
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE | ANOMALY_ALGO_PERSIST)) != 0 &&
        curr_luma != NULL &&
        state->prev_luma != NULL &&
        state->prev_luma_width  == motion_w &&
        state->prev_luma_height == motion_h &&
        !scene_discontinuity) {

        int roi_mgx0 = roi_x0 / motion_step;
        int roi_mgx1 = (roi_x1 + motion_step - 1) / motion_step;
        int roi_mgy0 = roi_y0 / motion_step;
        int roi_mgy1 = (roi_y1 + motion_step - 1) / motion_step;
        roi_mgx0 = roi_mgx0 < 0        ? 0        : roi_mgx0;
        roi_mgx1 = roi_mgx1 > motion_w ? motion_w : roi_mgx1;
        roi_mgy0 = roi_mgy0 < 0        ? 0        : roi_mgy0;
        roi_mgy1 = roi_mgy1 > motion_h ? motion_h : roi_mgy1;

        float fw_m = (float)(width  > 1 ? width  - 1 : 1);
        float fh_m = (float)(height > 1 ? height - 1 : 1);
        float gmv_scale = sqrtf(sim.a * sim.a + sim.b * sim.b);
        float zoom_delta = fabsf(gmv_scale - 1.0f);
        float zoom_motion_scale = 1.0f;
        if (zoom_delta > 0.004f) {
            zoom_motion_scale = 1.0f - ((zoom_delta - 0.004f) / 0.014f);
            if (zoom_motion_scale < 0.0f) zoom_motion_scale = 0.0f;
        }

        if (scene_discontinuity ||
                state->motion_persist == NULL ||
                state->motion_persist_w != motion_w ||
                state->motion_persist_h != motion_h) {
            free(state->motion_persist);
            state->motion_persist = (float *)calloc(motion_count, sizeof(float));
            state->motion_persist_w = state->motion_persist != NULL ? motion_w : 0;
            state->motion_persist_h = state->motion_persist != NULL ? motion_h : 0;
        }

        double metric_sum = 0.0, metric_sum2 = 0.0;
        int    metric_count = 0;
        const float motion_tolerance_px = 6.0f - (4.5f * (cfg != NULL ? cfg->motion_evidence_scale : 1.0f) / 2.0f);
        const int disp_patch_half = 1;
        const int disp_search_radius = 2;
        float *motion_dx_map = NULL;
        float *motion_dy_map = NULL;
        float *motion_mag_map = NULL;
        uint8_t *motion_valid_map = NULL;
        float *region_dx_field = NULL;
        float *region_dy_field = NULL;
        float *region_jitter_field = NULL;
        uint8_t *region_valid_field = NULL;
        int region_field_w = 0;
        int region_field_h = 0;
        const int region_stride_cells = ANOMALY_LOCAL_MOTION_REGION_STRIDE_CELLS;
        if (use_motion_tolerance) {
            motion_dx_map = (float *)malloc(motion_count * sizeof(float));
            motion_dy_map = (float *)malloc(motion_count * sizeof(float));
            motion_mag_map = (float *)malloc(motion_count * sizeof(float));
            motion_valid_map = (uint8_t *)malloc(motion_count * sizeof(uint8_t));
            if (motion_dx_map == NULL || motion_dy_map == NULL ||
                    motion_mag_map == NULL || motion_valid_map == NULL) {
                free(motion_dx_map);
                free(motion_dy_map);
                free(motion_mag_map);
                free(motion_valid_map);
                motion_dx_map = NULL;
                motion_dy_map = NULL;
                motion_mag_map = NULL;
                motion_valid_map = NULL;
                use_motion_tolerance = false;
                use_stable_motion = (cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0;
            } else {
                memset(motion_valid_map, 0, motion_count * sizeof(uint8_t));
            }
        }
        for (int my = roi_mgy0; my < roi_mgy1; my++) {
            float cy_n = (float)(my * motion_step) / fh_m;
            for (int mx = roi_mgx0; mx < roi_mgx1; mx++) {
                float cx_n  = (float)(mx * motion_step) / fw_m;
                float px_n  = sim.a * cx_n - sim.b * cy_n + sim.tx;
                float py_n  = sim.b * cx_n + sim.a * cy_n + sim.ty;
                int   px_idx = (int)(px_n * fw_m / (float)motion_step + 0.5f);
                int   py_idx = (int)(py_n * fh_m / (float)motion_step + 0.5f);
                if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) continue;
                if (use_motion_tolerance) {
                    int best_dx = 0, best_dy = 0;
                    if (!find_residual_motion_displacement(
                            curr_luma, state->prev_luma, motion_w, motion_h,
                            mx, my, px_idx, py_idx,
                            disp_patch_half, disp_search_radius,
                            &best_dx, &best_dy, NULL)) {
                        continue;
                    }
                    float residual_disp_px = sqrtf((float)(best_dx * best_dx + best_dy * best_dy)) * (float)motion_step;
                    size_t map_idx = (size_t)my * (size_t)motion_w + (size_t)mx;
                    motion_dx_map[map_idx] = (float)best_dx;
                    motion_dy_map[map_idx] = (float)best_dy;
                    motion_mag_map[map_idx] = residual_disp_px;
                    motion_valid_map[map_idx] = 1;
                    metric_sum  += (double)residual_disp_px;
                    metric_sum2 += (double)residual_disp_px * (double)residual_disp_px;
                    metric_count++;
                } else {
                    int d = abs((int)curr_luma[my * motion_w + mx] -
                                (int)state->prev_luma[py_idx * motion_w + px_idx]);
                    metric_sum  += (double)d;
                    metric_sum2 += (double)d * (double)d;
                    metric_count++;
                }
            }
        }
        if (use_motion_tolerance && motion_valid_map != NULL) {
            region_field_w = (motion_w + region_stride_cells - 1) / region_stride_cells;
            region_field_h = (motion_h + region_stride_cells - 1) / region_stride_cells;
            size_t region_count = (size_t)region_field_w * (size_t)region_field_h;
            region_dx_field = (float *)calloc(region_count, sizeof(float));
            region_dy_field = (float *)calloc(region_count, sizeof(float));
            region_jitter_field = (float *)calloc(region_count, sizeof(float));
            region_valid_field = (uint8_t *)calloc(region_count, sizeof(uint8_t));
            if (region_dx_field == NULL || region_dy_field == NULL ||
                region_jitter_field == NULL || region_valid_field == NULL) {
                free(region_dx_field);
                free(region_dy_field);
                free(region_jitter_field);
                free(region_valid_field);
                region_dx_field = NULL;
                region_dy_field = NULL;
                region_jitter_field = NULL;
                region_valid_field = NULL;
                region_field_w = 0;
                region_field_h = 0;
            } else {
                for (int ry = 0; ry < region_field_h; ry++) {
                    for (int rx = 0; rx < region_field_w; rx++) {
                        int region_cx = clamp_i32(rx * region_stride_cells + (region_stride_cells / 2), 0, motion_w - 1);
                        int region_cy = clamp_i32(ry * region_stride_cells + (region_stride_cells / 2), 0, motion_h - 1);
                        float local_dx = 0.0f;
                        float local_dy = 0.0f;
                        float local_jitter = 0.0f;
                        if (estimate_local_motion_region(
                                motion_dx_map,
                                motion_dy_map,
                                motion_valid_map,
                                motion_w,
                                motion_h,
                                region_cx,
                                region_cy,
                                ANOMALY_LOCAL_MOTION_REGION_RADIUS_CELLS,
                                &local_dx,
                                &local_dy,
                                &local_jitter)) {
                            size_t ridx = (size_t)ry * (size_t)region_field_w + (size_t)rx;
                            region_dx_field[ridx] = local_dx;
                            region_dy_field[ridx] = local_dy;
                            region_jitter_field[ridx] = local_jitter;
                            region_valid_field[ridx] = 1;
                        }
                    }
                }
            }
        }

        if (metric_count > 1) {
            double metric_mean = metric_sum / (double)metric_count;
            double metric_std  = use_motion_tolerance
                ? sqrt(fmax((metric_sum2 / (double)metric_count) - metric_mean * metric_mean, 0.01))
                : sqrt(fmax((metric_sum2 / (double)metric_count) - metric_mean * metric_mean, 1.0));
            if (result_out != NULL) {
                result_out->motion_debug.valid = true;
                result_out->motion_debug.scene_discontinuity = scene_discontinuity;
                result_out->motion_debug.sample_step = motion_sample_step;
                result_out->motion_debug.motion_step = motion_step;
                result_out->motion_debug.sample_count = metric_count;
                result_out->motion_debug.residual_mean = (float)metric_mean;
                result_out->motion_debug.residual_std = (float)metric_std;
                result_out->motion_debug.zoom_motion_scale = zoom_motion_scale;
                result_out->motion_debug.broad_motion_scale = 1.0f;
                result_out->motion_debug.global_motion_load = 0.0f;
            }
            float *motion_z_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_selection_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_presence_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_texture_scale_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_structure_scale_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_support_scale_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_persistence_scale_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_component_area_frac_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_component_span_frac_map = (float *)malloc(motion_count * sizeof(float));
            float *motion_component_fill_ratio_map = (float *)malloc(motion_count * sizeof(float));
            float *registration_residual_scale_map = (float *)malloc(motion_count * sizeof(float));
            if (motion_z_map != NULL && motion_selection_map != NULL) {
                for (size_t i = 0; i < motion_count; i++) {
                    motion_z_map[i] = -1.0f;
                    motion_selection_map[i] = -1.0f;
                }
            }
            if (motion_presence_map != NULL) {
                memset(motion_presence_map, 0, motion_count * sizeof(float));
            }
            if (motion_texture_scale_map != NULL) memset(motion_texture_scale_map, 0, motion_count * sizeof(float));
            if (motion_structure_scale_map != NULL) memset(motion_structure_scale_map, 0, motion_count * sizeof(float));
            if (motion_support_scale_map != NULL) memset(motion_support_scale_map, 0, motion_count * sizeof(float));
            if (motion_persistence_scale_map != NULL) {
                for (size_t i = 0; i < motion_count; i++) motion_persistence_scale_map[i] = 1.0f;
            }
            if (motion_component_area_frac_map != NULL) memset(motion_component_area_frac_map, 0, motion_count * sizeof(float));
            if (motion_component_span_frac_map != NULL) memset(motion_component_span_frac_map, 0, motion_count * sizeof(float));
            if (motion_component_fill_ratio_map != NULL) memset(motion_component_fill_ratio_map, 0, motion_count * sizeof(float));
            if (registration_residual_scale_map != NULL) {
                for (size_t i = 0; i < motion_count; i++) registration_residual_scale_map[i] = 1.0f;
            }
            int strong_motion_cells = 0;
            int scored_motion_cells = 0;
            for (int my = roi_mgy0; my < roi_mgy1; my++) {
                float cy_n = (float)(my * motion_step) / fh_m;
                for (int mx = roi_mgx0; mx < roi_mgx1; mx++) {
                    float cx_n  = (float)(mx * motion_step) / fw_m;
                    float px_n  = sim.a * cx_n - sim.b * cy_n + sim.tx;
                    float py_n  = sim.b * cx_n + sim.a * cy_n + sim.ty;
                    int   px_idx = (int)(px_n * fw_m / (float)motion_step + 0.5f);
                    int   py_idx = (int)(py_n * fh_m / (float)motion_step + 0.5f);
                    if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) continue;
                    size_t map_idx = (size_t)my * (size_t)motion_w + (size_t)mx;
                    float ms_raw;
                    if (use_motion_tolerance) {
                        if (mx <= roi_mgx0 + 1 || mx >= roi_mgx1 - 2 ||
                                my <= roi_mgy0 + 1 || my >= roi_mgy1 - 2) {
                            if (motion_z_map != NULL) {
                                motion_z_map[my * motion_w + mx] = 0.0f;
                            }
                            continue;
                        }
                        if (motion_valid_map == NULL || motion_valid_map[map_idx] == 0) {
                            continue;
                        }
                        float center_dx = motion_dx_map[map_idx];
                        float center_dy = motion_dy_map[map_idx];
                        float residual_disp_px = motion_mag_map[map_idx];
                        float region_model_dx = 0.0f;
                        float region_model_dy = 0.0f;
                        float region_model_jitter = 0.0f;
                        float region_model_conf = 0.0f;
                        sample_local_motion_field(
                                region_dx_field,
                                region_dy_field,
                                region_jitter_field,
                                region_valid_field,
                                region_field_w,
                                region_field_h,
                                region_stride_cells,
                                (float)mx,
                                (float)my,
                                &region_model_dx,
                                &region_model_dy,
                                &region_model_jitter,
                                &region_model_conf);
                        float neighborhood_residual_sum_px = 0.0f;
                        int neighborhood_residual_count = 0;
                        for (int ny = my - 2; ny <= my + 2; ny++) {
                            if (ny < roi_mgy0 || ny >= roi_mgy1) continue;
                            for (int nx = mx - 2; nx <= mx + 2; nx++) {
                                if (nx < roi_mgx0 || nx >= roi_mgx1) continue;
                                if (nx == mx && ny == my) continue;
                                size_t nidx = (size_t)ny * (size_t)motion_w + (size_t)nx;
                                if (motion_valid_map[nidx] == 0) continue;
                                neighborhood_residual_sum_px += motion_mag_map[nidx];
                                neighborhood_residual_count++;
                            }
                        }
                        float neighborhood_residual_mean_px =
                            neighborhood_residual_count > 0
                                ? (neighborhood_residual_sum_px / (float)neighborhood_residual_count)
                                : residual_disp_px;

                        float fit_m[3][3] = {{0}};
                        float fit_bx[3] = {0};
                        float fit_by[3] = {0};
                        int ring_count = 0;
                        for (int ny = my - 2; ny <= my + 2; ny++) {
                            if (ny < roi_mgy0 || ny >= roi_mgy1) continue;
                            for (int nx = mx - 2; nx <= mx + 2; nx++) {
                                if (nx < roi_mgx0 || nx >= roi_mgx1) continue;
                                int adx = nx - mx;
                                int ady = ny - my;
                                int cheb = abs(adx) > abs(ady) ? abs(adx) : abs(ady);
                                if (cheb != 2) continue;
                                size_t nidx = (size_t)ny * (size_t)motion_w + (size_t)nx;
                                if (motion_valid_map[nidx] == 0) continue;
                                float fx = (float)adx;
                                float fy = (float)ady;
                                float basis[3] = {fx, fy, 1.0f};
                                for (int r = 0; r < 3; r++) {
                                    for (int c = 0; c < 3; c++) {
                                        fit_m[r][c] += basis[r] * basis[c];
                                    }
                                    fit_bx[r] += basis[r] * motion_dx_map[nidx];
                                    fit_by[r] += basis[r] * motion_dy_map[nidx];
                                }
                                ring_count++;
                            }
                        }

                        float local_noise_px = fmaxf((float)metric_std, (float)motion_step * 0.75f);
                        float local_contrast_px = residual_disp_px;
                        float neighbor_isolation_scale = 1.0f;
                        if (ring_count >= 5) {
                            float coeff_x[3];
                            float coeff_y[3];
                            if (solve_3x3(fit_m, fit_bx, coeff_x) &&
                                    solve_3x3(fit_m, fit_by, coeff_y)) {
                                float pred_dx = coeff_x[2];
                                float pred_dy = coeff_y[2];
                                float ring_dev_sum = 0.0f;
                                for (int ny = my - 2; ny <= my + 2; ny++) {
                                    if (ny < roi_mgy0 || ny >= roi_mgy1) continue;
                                    for (int nx = mx - 2; nx <= mx + 2; nx++) {
                                        if (nx < roi_mgx0 || nx >= roi_mgx1) continue;
                                        int adx = nx - mx;
                                        int ady = ny - my;
                                        int cheb = abs(adx) > abs(ady) ? abs(adx) : abs(ady);
                                        if (cheb != 2) continue;
                                        size_t nidx = (size_t)ny * (size_t)motion_w + (size_t)nx;
                                        if (motion_valid_map[nidx] == 0) continue;
                                        float fit_dx = coeff_x[0] * (float)adx + coeff_x[1] * (float)ady + coeff_x[2];
                                        float fit_dy = coeff_y[0] * (float)adx + coeff_y[1] * (float)ady + coeff_y[2];
                                        float ddx = motion_dx_map[nidx] - fit_dx;
                                        float ddy = motion_dy_map[nidx] - fit_dy;
                                        ring_dev_sum += sqrtf(ddx * ddx + ddy * ddy) * (float)motion_step;
                                    }
                                }
                                float ring_jitter_px = ring_dev_sum / (float)ring_count;
                                float diff_dx = center_dx - pred_dx;
                                float diff_dy = center_dy - pred_dy;
                                local_contrast_px = sqrtf(diff_dx * diff_dx + diff_dy * diff_dy) * (float)motion_step;
                                local_noise_px = fmaxf(local_noise_px, ring_jitter_px + (float)motion_step * 0.25f);

                                float isolation_margin_px =
                                    ANOMALY_MOTION_NEIGHBOR_MARGIN_PX +
                                    (ANOMALY_MOTION_NEIGHBOR_MARGIN_SCALE * ring_jitter_px);
                                float residual_isolation_px =
                                    residual_disp_px - neighborhood_residual_mean_px - isolation_margin_px;
                                if (residual_isolation_px <= 0.0f) {
                                    if (ring_jitter_px <= ANOMALY_MOTION_NEIGHBOR_COHERENCE_PX) {
                                        neighbor_isolation_scale = ANOMALY_MOTION_NEIGHBOR_MIN_SCALE;
                                    } else {
                                        neighbor_isolation_scale = 0.35f;
                                    }
                                } else {
                                    float bonus = 1.0f + (residual_isolation_px / fmaxf(local_noise_px, 1.0f));
                                    if (bonus > ANOMALY_MOTION_NEIGHBOR_MAX_BONUS) {
                                        bonus = ANOMALY_MOTION_NEIGHBOR_MAX_BONUS;
                                    }
                                    neighbor_isolation_scale = bonus;
                                }
                            }
                        }

                        float excess_px = local_contrast_px - motion_tolerance_px;
                        if (region_model_conf > 0.0f) {
                            float region_ddx = center_dx - region_model_dx;
                            float region_ddy = center_dy - region_model_dy;
                            float region_contrast_px =
                                sqrtf(region_ddx * region_ddx + region_ddy * region_ddy) * (float)motion_step;
                            float region_noise_px =
                                fmaxf((float)motion_step * 0.50f,
                                      (region_model_jitter * (float)motion_step) + ((float)motion_step * 0.35f));
                            float region_margin_px =
                                (float)motion_step * 0.75f + (region_model_jitter * (float)motion_step * 0.65f);
                            float region_excess_px = region_contrast_px - region_margin_px;
                            if (region_excess_px <= 0.0f) {
                                excess_px = -1.0f;
                            } else {
                                if (region_contrast_px < local_contrast_px) {
                                    local_contrast_px = region_contrast_px;
                                }
                                local_noise_px = fmaxf(local_noise_px, region_noise_px);
                                if (region_excess_px < excess_px) {
                                    excess_px = region_excess_px;
                                }
                                if (region_model_jitter <= 0.75f) {
                                    neighbor_isolation_scale *= 0.75f;
                                    if (neighbor_isolation_scale < ANOMALY_LOCAL_MOTION_MIN_SCALE) {
                                        neighbor_isolation_scale = ANOMALY_LOCAL_MOTION_MIN_SCALE;
                                    }
                                }
                            }
                        }
                        if (excess_px <= 0.0f) {
                            ms_raw = 0.0f;
                        } else {
                            ms_raw = 1.0f + (excess_px / fmaxf(local_noise_px, 1.0f));
                            float residual_bonus = (residual_disp_px - motion_tolerance_px) / fmaxf(motion_tolerance_px, 1.0f);
                            if (residual_bonus > 0.0f) {
                                if (residual_bonus > 1.5f) residual_bonus = 1.5f;
                                ms_raw += 0.25f * residual_bonus;
                            }
                            ms_raw = 1.0f + ((ms_raw - 1.0f) * neighbor_isolation_scale);
                        }
                    } else {
                        int d = abs((int)curr_luma[my * motion_w + mx] -
                                    (int)state->prev_luma[py_idx * motion_w + px_idx]);
                        ms_raw = (float)(((double)d - metric_mean) / metric_std);
                    }

                    // Motion in near-flat regions is often just codec shimmer,
                    // stabilization residue, or parallax leftovers with no
                    // trustworthy local structure behind it. Require at least
                    // some local luma texture before we trust the motion score.
                    int texture_score = gmv_feature_score(curr_luma, motion_w, motion_h, mx, my);
                    float texture_scale = motion_texture_scale(texture_score);
                    float structure_scale = motion_structure_scale(curr_luma, motion_w, motion_h, mx, my);
                    float support_scale = texture_scale;
                    if (structure_scale < support_scale) support_scale = structure_scale;
                    if (motion_texture_scale_map != NULL) motion_texture_scale_map[map_idx] = texture_scale;
                    if (motion_structure_scale_map != NULL) motion_structure_scale_map[map_idx] = structure_scale;
                    if (motion_support_scale_map != NULL) motion_support_scale_map[map_idx] = support_scale;
                    if (support_scale <= 0.0f) {
                        ms_raw = 0.0f;
                    } else if (ms_raw > 1.0f) {
                        ms_raw = 1.0f + (ms_raw - 1.0f) * support_scale;
                    }
                    float registration_scale = 1.0f;
                    float registration_score = registration_residual_standout_score(
                        curr_luma,
                        state->prev_luma,
                        motion_w,
                        motion_h,
                        motion_step,
                        width,
                        height,
                        sim,
                        mx,
                        my);
                    if (registration_score < ANOMALY_REG_RESIDUAL_SOFT_THRESH) {
                        float t = registration_score / ANOMALY_REG_RESIDUAL_SOFT_THRESH;
                        registration_scale =
                            ANOMALY_REG_RESIDUAL_MIN_SCALE +
                            (1.0f - ANOMALY_REG_RESIDUAL_MIN_SCALE) * clampf(t, 0.0f, 1.0f);
                        if (registration_score <= ANOMALY_REG_RESIDUAL_HARD_THRESH) {
                            registration_scale = ANOMALY_REG_RESIDUAL_MIN_SCALE;
                        }
                    }
                    if (registration_residual_scale_map != NULL) {
                        registration_residual_scale_map[map_idx] = registration_scale;
                    }
                    if (ms_raw > 1.0f) {
                        ms_raw = 1.0f + (ms_raw - 1.0f) * registration_scale;
                    } else {
                        ms_raw *= registration_scale;
                    }
                    scored_motion_cells++;
                    if (ms_raw > 1.5f) {
                        strong_motion_cells++;
                    }

                    float ms = ms_raw;
                    float current_excess = ms_raw > 1.0f ? (ms_raw - 1.0f) : 0.0f;
                    if (current_excess > 0.0f && state->motion_persist != NULL) {
                        float prior_support = 0.0f;
                        for (int ny = my - 1; ny <= my + 1; ny++) {
                            if (ny < roi_mgy0 || ny >= roi_mgy1) continue;
                            for (int nx = mx - 1; nx <= mx + 1; nx++) {
                                if (nx < roi_mgx0 || nx >= roi_mgx1) continue;
                                float prior = state->motion_persist[ny * motion_w + nx];
                                if (prior > prior_support) prior_support = prior;
                            }
                        }
                        float persistence_scale;
                        if (prior_support < 0.08f) {
                            persistence_scale = 0.45f;
                        } else if (prior_support < 0.25f) {
                            persistence_scale = 0.70f;
                        } else {
                            persistence_scale = 0.90f + 0.30f * fminf(prior_support, 1.0f);
                        }
                        if (motion_persistence_scale_map != NULL) {
                            motion_persistence_scale_map[map_idx] = persistence_scale;
                        }
                        ms = 1.0f + current_excess * persistence_scale;
                        if (motion_presence_map != NULL) {
                            float presence = current_excess / 2.5f;
                            if (presence > 1.0f) presence = 1.0f;
                            motion_presence_map[map_idx] = presence;
                        }
                    }
                    if (motion_z_map != NULL) {
                        motion_z_map[map_idx] = ms;
                    }
                }
            }
            float global_motion_load = scored_motion_cells > 0
                ? ((float)strong_motion_cells / (float)scored_motion_cells)
                : 0.0f;
            debug_global_motion_load = global_motion_load;
            float broad_motion_scale = 1.0f;
            if (global_motion_load > 0.12f) {
                broad_motion_scale = 1.0f - ((global_motion_load - 0.12f) / 0.18f);
                if (broad_motion_scale < 0.20f) broad_motion_scale = 0.20f;
            }
            if (state->motion_persist != NULL) {
                for (size_t i = 0; i < motion_count; i++) {
                    float prior = state->motion_persist[i] * 0.72f;
                    float current = motion_presence_map != NULL ? motion_presence_map[i] : 0.0f;
                    state->motion_persist[i] = current > prior ? current : prior;
                }
            }
            if (motion_z_map != NULL && motion_selection_map != NULL) {
                build_motion_selection_map(
                    motion_z_map,
                    curr_luma,
                    motion_w,
                    motion_h,
                    motion_selection_map,
                    motion_component_area_frac_map,
                    motion_component_span_frac_map,
                    motion_component_fill_ratio_map);
            }
            for (int my = roi_mgy0; my < roi_mgy1; my++) {
                for (int mx = roi_mgx0; mx < roi_mgx1; mx++) {
                    float ms = (motion_selection_map != NULL)
                        ? motion_selection_map[my * motion_w + mx]
                        : -1.0f;
                    if (ms > 0.0f) {
                        ms *= broad_motion_scale;
                        ms *= zoom_motion_scale;
                        ms *= motion_evidence_scale;
                    }
                    int pixel_x = mx * motion_step + motion_step / 2;
                    int pixel_y = my * motion_step + motion_step / 2;
                    maybe_insert_top_candidate(
                        motion_top,
                        &motion_top_count,
                        ANOMALY_DEBUG_TOP_CANDIDATES,
                        pixel_x,
                        pixel_y,
                        (float)pixel_x / fw_m,
                        (float)pixel_y / fh_m,
                        motion_z_map != NULL ? motion_z_map[my * motion_w + mx] : -1.0f,
                        0.0f,
                        ms);
                    if (saliency_motion_map != NULL) {
                        float motion_support = ms;
                        if (motion_support > 0.0f) {
                            if (registration_residual_scale_map != NULL) {
                                motion_support *= registration_residual_scale_map[my * motion_w + mx];
                            }
                            if (motion_support > 4.0f) motion_support = 4.0f;
                            int sample_x = pixel_x;
                            int sample_y = pixel_y;
                            int sal_sx = clamp_i32((sample_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                            int sal_sy = clamp_i32((sample_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                            float *slot = &saliency_motion_map[sal_sy * sg_w + sal_sx];
                            if (motion_support > *slot) *slot = motion_support;
                            if (saliency_registration_map != NULL) {
                                float *reg_slot = &saliency_registration_map[sal_sy * sg_w + sal_sx];
                                float reg_scale = registration_residual_scale_map[my * motion_w + mx];
                                if (reg_scale < *reg_slot) *reg_slot = reg_scale;
                            }
                        }
                    }
                    if ((use_stable_motion || use_motion_tolerance) &&
                        ms > best_motion) {
                        best_motion   = ms;
                        best_motion_x = pixel_x;
                        best_motion_y = pixel_y;
                        best_motion_texture_scale = motion_texture_scale_map != NULL ? motion_texture_scale_map[my * motion_w + mx] : 0.0f;
                        best_motion_structure_scale = motion_structure_scale_map != NULL ? motion_structure_scale_map[my * motion_w + mx] : 0.0f;
                        best_motion_support_scale = motion_support_scale_map != NULL ? motion_support_scale_map[my * motion_w + mx] : 0.0f;
                        best_motion_persistence_scale = motion_persistence_scale_map != NULL ? motion_persistence_scale_map[my * motion_w + mx] : 1.0f;
                        best_motion_component_area_frac = motion_component_area_frac_map != NULL ? motion_component_area_frac_map[my * motion_w + mx] : 0.0f;
                        best_motion_component_span_frac = motion_component_span_frac_map != NULL ? motion_component_span_frac_map[my * motion_w + mx] : 0.0f;
                        best_motion_component_fill_ratio = motion_component_fill_ratio_map != NULL ? motion_component_fill_ratio_map[my * motion_w + mx] : 0.0f;
                        best_motion_zoom_scale = zoom_motion_scale;
                        best_motion_broad_scale = broad_motion_scale;
                    }
                }
            }
            free(motion_z_map);
            free(motion_selection_map);
            free(motion_presence_map);
            free(motion_texture_scale_map);
            free(motion_structure_scale_map);
            free(motion_support_scale_map);
            free(motion_persistence_scale_map);
            free(motion_component_area_frac_map);
            free(motion_component_span_frac_map);
            free(motion_component_fill_ratio_map);
            free(registration_residual_scale_map);
        }
        free(motion_dx_map);
        free(motion_dy_map);
        free(motion_mag_map);
        free(motion_valid_map);
        free(region_dx_field);
        free(region_dy_field);
        free(region_jitter_field);
        free(region_valid_field);
    }

    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        size_t score_count = (size_t)sg_w * (size_t)sg_h;
        float *patch_score_map = (float *)malloc(score_count * sizeof(float));
        float *patch_selection_map = (float *)malloc(score_count * sizeof(float));
        if (patch_score_map != NULL && patch_selection_map != NULL) {
            memset(saliency_top, 0, sizeof(saliency_top));
            saliency_top_count = 0;
            for (int sy = 0; sy < sg_h; sy++) {
                for (int sx = 0; sx < sg_w; sx++) {
                    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                    float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                    float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
                    float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
                    float registration_support = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
                    float thermal_temporal = 0.0f;
                    if (bg_valid) {
                        float bg = state->bg_luma[idx];
                        float lum = (float)sg_luma[idx];
                        float delta = black_hot ? (bg - lum) : (lum - bg);
                        if (delta >= thermal_min_delta) {
                            thermal_temporal = (float)((delta - delta_mean) / delta_norm);
                        }
                    }

                    float spatial_evidence = thermal_spatial > 0.0f ? thermal_spatial : 0.0f;
                    if (color_support > 0.0f) spatial_evidence += 0.60f * color_support;

                    float temporal_evidence = thermal_temporal > 0.0f ? thermal_temporal : 0.0f;
                    if (motion_support > 0.0f) {
                        temporal_evidence += bg_valid ? (0.60f * motion_support)
                                                      : (0.45f * motion_support);
                    }

                    float saliency = bg_valid
                        ? (0.75f * spatial_evidence) + temporal_evidence
                        : spatial_evidence + temporal_evidence;
                    saliency *= registration_support;
                    if (saliency <= 0.0f) {
                        patch_score_map[idx] = -1.0f;
                        continue;
                    }
                    patch_score_map[idx] = saliency;
                    if (result_out != NULL) {
                        int px = roi_x0 + sx * sample_step;
                        int py = roi_y0 + sy * sample_step;
                        maybe_insert_top_candidate(
                                saliency_top, &saliency_top_count, ANOMALY_DEBUG_TOP_CANDIDATES,
                                px, py,
                                (float)px / (float)(width > 1 ? width - 1 : 1),
                                (float)py / (float)(height > 1 ? height - 1 : 1),
                                spatial_evidence, temporal_evidence, saliency);
                    }
                }
            }

            build_patch_selection_map(patch_score_map, sg_w, sg_h, patch_selection_map);
            memset(saliency_top, 0, sizeof(saliency_top));
            saliency_top_count = 0;
            for (int sy = 0; sy < sg_h; sy++) {
                for (int sx = 0; sx < sg_w; sx++) {
                    size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
                    float final_score = patch_selection_map[idx];
                    if (final_score <= 0.0f) continue;
                    int px = roi_x0 + sx * sample_step;
                    int py = roi_y0 + sy * sample_step;
                    float thermal_spatial = saliency_spatial_map != NULL ? saliency_spatial_map[idx] : -1.0f;
                    float color_support = saliency_color_map != NULL ? saliency_color_map[idx] : 0.0f;
                    float motion_support = saliency_motion_map != NULL ? saliency_motion_map[idx] : 0.0f;
                    float registration_support = saliency_registration_map != NULL ? saliency_registration_map[idx] : 1.0f;
                    float spatial_evidence = thermal_spatial > 0.0f ? thermal_spatial : 0.0f;
                    if (color_support > 0.0f) spatial_evidence += 0.60f * color_support;
                    spatial_evidence *= registration_support;
                    float temporal_evidence = 0.0f;
                    if (bg_valid) {
                        float bg = state->bg_luma[idx];
                        float lum = (float)sg_luma[idx];
                        float delta = black_hot ? (bg - lum) : (lum - bg);
                        if (delta >= thermal_min_delta) {
                            temporal_evidence = (float)((delta - delta_mean) / delta_norm);
                        }
                    }
                    if (motion_support > 0.0f) {
                        temporal_evidence += bg_valid ? (0.60f * motion_support)
                                                      : (0.45f * motion_support);
                    }
                    temporal_evidence *= registration_support;
                    maybe_insert_top_candidate(
                            saliency_top, &saliency_top_count, ANOMALY_DEBUG_TOP_CANDIDATES,
                            px, py,
                            (float)px / (float)(width > 1 ? width - 1 : 1),
                            (float)py / (float)(height > 1 ? height - 1 : 1),
                            spatial_evidence, temporal_evidence, final_score);
                }
            }
            if (state->acc_active[3]) {
                float dbg_fw = (float)(width > 1 ? width - 1 : 1);
                float dbg_fh = (float)(height > 1 ? height - 1 : 1);
                int track_x = clamp_i32((int)lroundf(state->acc_cx[3] * dbg_fw), roi_x0, roi_x1 - 1);
                int track_y = clamp_i32((int)lroundf(state->acc_cy[3] * dbg_fh), roi_y0, roi_y1 - 1);
                int track_sx = clamp_i32((track_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                int track_sy = clamp_i32((track_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                saliency_tracked_score_pre = -1.0f;
                for (int ny = track_sy - 1; ny <= track_sy + 1; ny++) {
                    if (ny < 0 || ny >= sg_h) continue;
                    for (int nx = track_sx - 1; nx <= track_sx + 1; nx++) {
                        if (nx < 0 || nx >= sg_w) continue;
                        float nearby = patch_selection_map[ny * sg_w + nx];
                        if (nearby > saliency_tracked_score_pre) {
                            saliency_tracked_score_pre = nearby;
                        }
                    }
                }
            }
            choose_best_dark_patch(
                    patch_selection_map,
                    sg_w, sg_h,
                    roi_x0, roi_y0, sample_step,
                    &best_persist, &best_persist_x, &best_persist_y);
            free(patch_selection_map);
            free(patch_score_map);
        } else {
            free(patch_selection_map);
            free(patch_score_map);
        }
    }

    // ── Update prev_luma ────────────────────��────────────────────────────
    if (curr_luma != NULL) {
        if (state->prev_luma != NULL) free(state->prev_luma);
        state->prev_luma        = curr_luma;
        state->prev_luma_width  = motion_w;
        state->prev_luma_height = motion_h;
    }

    // ── Update per-algorithm accumulators ────────────────────────────────
    float fw = (float)(width  > 1 ? width  - 1 : 1);
    float fh = (float)(height > 1 ? height - 1 : 1);

    float raw_cx[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
    float raw_cy[4] = {-1.0f, -1.0f, -1.0f, -1.0f};
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_COLOR)   && best_color   >= cfg->score_threshold) {
        raw_cx[0] = (float)best_color_x   / fw;
        raw_cy[0] = (float)best_color_y   / fh;
    }
    if (anomaly_detection_active && (cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) && best_thermal >= cfg->score_threshold) {
        raw_cx[1] = (float)best_thermal_x / fw;
        raw_cy[1] = (float)best_thermal_y / fh;
    }
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_MOTION_TOLERANCE)) &&
        best_motion  >= cfg->score_threshold) {
        raw_cx[2] = (float)best_motion_x  / fw;
        raw_cy[2] = (float)best_motion_y  / fh;
    }
    if (anomaly_detection_active &&
        (cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) &&
        best_persist >= cfg->score_threshold) {
        raw_cx[3] = (float)best_persist_x / fw;
        raw_cy[3] = (float)best_persist_y / fh;
    }

    bool saliency_acc_pre_active = state->acc_active[3];
    int saliency_acc_pre_hits = state->acc_hits[3];
    float saliency_acc_pre_x = state->acc_cx[3];
    float saliency_acc_pre_y = state->acc_cy[3];
    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;

    float gate  = ANOMALY_ACC_GATE_RADIUS;
    float alpha = ANOMALY_ACC_EMA_ALPHA;
    for (int ai = 0; ai < 4; ai++) {
        uint8_t prior_presence = state->acc_presence_mask[ai];
        prior_presence = (uint8_t)((prior_presence << 1u) & ((1u << ANOMALY_MOTION_PRESENCE_WINDOW) - 1u));
        if (raw_cx[ai] >= 0.0f) {
            state->acc_presence_mask[ai] = (uint8_t)(prior_presence | 1u);
            if (!state->acc_active[ai]) {
                state->acc_cx[ai]     = raw_cx[ai];
                state->acc_cy[ai]     = raw_cy[ai];
                state->acc_hits[ai]   = 1;
                state->acc_hold[ai]   = ANOMALY_ACC_HOLD_FRAMES;
                state->acc_active[ai] = true;
            } else {
                float ddx  = raw_cx[ai] - state->acc_cx[ai];
                float ddy  = raw_cy[ai] - state->acc_cy[ai];
                float dist = sqrtf(ddx * ddx + ddy * ddy);
                bool suppress_switch = false;
                float blend_alpha = alpha;
                if (ai == 3 && saliency_acc_pre_active && saliency_acc_pre_hits >= min_hits) {
                    float switch_margin = 1.10f;
                    float inner_gate = gate * 0.45f;
                    if (saliency_tracked_score_pre > 0.0f &&
                        dist > inner_gate &&
                        best_persist < saliency_tracked_score_pre + switch_margin) {
                        suppress_switch = true;
                    }
                    // If the tracked neighborhood has gone weak but the raw saliency
                    // winner is clearly stronger, pull the latch back quickly instead
                    // of letting the EMA trail behind the true winner for dozens of frames.
                    if (!suppress_switch &&
                        saliency_tracked_score_pre > 0.0f &&
                        best_persist > saliency_tracked_score_pre + 2.0f &&
                        dist > gate * 0.08f) {
                        blend_alpha = 0.75f;
                    }
                }
                if (dist <= gate) {
                    if (suppress_switch) {
                        saliency_switch_suppressed = true;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    } else {
                        state->acc_cx[ai] += blend_alpha * ddx;
                        state->acc_cy[ai] += blend_alpha * ddy;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    }
                } else {
                    if (suppress_switch) {
                        saliency_switch_suppressed = true;
                        int h = state->acc_hits[ai] + 1;
                        state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                    } else {
                        // Detection jumped to a new region; reset to new location.
                        state->acc_cx[ai]   = raw_cx[ai];
                        state->acc_cy[ai]   = raw_cy[ai];
                        state->acc_hits[ai] = 1;
                    }
                }
                state->acc_hold[ai] = ANOMALY_ACC_HOLD_FRAMES;
            }
        } else if (state->acc_active[ai]) {
            state->acc_presence_mask[ai] = prior_presence;
            int hold = state->acc_hold[ai] - 1;
            if (hold <= 0) {
                state->acc_active[ai] = false;
                state->acc_hits[ai]   = 0;
                state->acc_hold[ai]   = 0;
            } else {
                state->acc_hold[ai] = hold;
            }
        } else {
            state->acc_presence_mask[ai] = prior_presence;
        }
    }

    // ── Assemble and draw boxes ─────────────────────��─────────────────��──
    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int box_count = 0;
    if (anomaly_detection_active) {
        const int motion_box_algorithm = use_motion_tolerance ? ANOMALY_ALGO_MOTION_TOLERANCE : ANOMALY_ALGO_MOTION;
        box_count = assemble_anomaly_boxes(
                state,
                cfg,
                motion_box_algorithm,
                boxes,
                ANOMALY_MAX_BOXES_PER_FRAME);
    }

    if (result_out != NULL) {
        result_out->box_count = box_count;
        for (int i = 0; i < box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++)
            result_out->boxes[i] = boxes[i];
        result_out->motion_debug.raw_candidate_valid = (best_motion >= 0.0f);
        result_out->motion_debug.raw_score = best_motion;
        result_out->motion_debug.raw_x_norm = (best_motion_x > 0 || best_motion_y > 0)
            ? ((float)best_motion_x / fw) : 0.0f;
        result_out->motion_debug.raw_y_norm = (best_motion_x > 0 || best_motion_y > 0)
            ? ((float)best_motion_y / fh) : 0.0f;
        result_out->motion_debug.winner_component_area_frac = best_motion_component_area_frac;
        result_out->motion_debug.winner_component_span_frac = best_motion_component_span_frac;
        result_out->motion_debug.winner_component_fill_ratio = best_motion_component_fill_ratio;
        result_out->motion_debug.zoom_motion_scale = best_motion_zoom_scale;
        result_out->motion_debug.broad_motion_scale = best_motion_broad_scale;
        result_out->motion_debug.global_motion_load = debug_global_motion_load;
        result_out->motion_debug.winner_texture_scale = best_motion_texture_scale;
        result_out->motion_debug.winner_structure_scale = best_motion_structure_scale;
        result_out->motion_debug.winner_support_scale = best_motion_support_scale;
        result_out->motion_debug.winner_persistence_scale = best_motion_persistence_scale;
        result_out->motion_debug.top_candidate_count = motion_top_count;
        for (int i = 0; i < motion_top_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
            result_out->motion_debug.top_candidates[i] = motion_top[i];
        }
        result_out->saliency_debug.raw_candidate_valid = (best_persist >= 0.0f);
        result_out->saliency_debug.raw_score = best_persist;
        result_out->saliency_debug.raw_x_norm = (best_persist_x > 0 || best_persist_y > 0)
            ? ((float)best_persist_x / fw) : 0.0f;
        result_out->saliency_debug.raw_y_norm = (best_persist_x > 0 || best_persist_y > 0)
            ? ((float)best_persist_y / fh) : 0.0f;
        result_out->saliency_debug.tracked_score_pre = saliency_tracked_score_pre;
        result_out->saliency_debug.acc_pre_active = saliency_acc_pre_active;
        result_out->saliency_debug.acc_pre_hits = saliency_acc_pre_hits;
        result_out->saliency_debug.acc_pre_x_norm = saliency_acc_pre_x;
        result_out->saliency_debug.acc_pre_y_norm = saliency_acc_pre_y;
        result_out->saliency_debug.acc_post_active = state->acc_active[3];
        result_out->saliency_debug.acc_post_hits = state->acc_hits[3];
        result_out->saliency_debug.acc_post_x_norm = state->acc_cx[3];
        result_out->saliency_debug.acc_post_y_norm = state->acc_cy[3];
        result_out->saliency_debug.switch_suppressed = saliency_switch_suppressed;
        result_out->saliency_debug.top_candidate_count = saliency_top_count;
        for (int i = 0; i < saliency_top_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
            result_out->saliency_debug.top_candidates[i] = saliency_top[i];
        }
    }

    if (rgba != NULL) {
        if (show_hot_overlay) {
            draw_hot_overlay_rgba(rgba, rgba_stride, width, height, cfg->thermal_polarity);
        }
        if (box_count > 0) {
            draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, boxes, box_count);
        }
    }

    free(saliency_motion_map);
    free(saliency_color_map);
    free(saliency_registration_map);
    free(saliency_spatial_map);
    free(sg_luma);
    free(ii_sum);
    free(ii_sum2);

    return box_count;
}

// anomaly_analysis.c — Standalone anomaly detection for SAR drone video.
// See anomaly_analysis.h for full documentation.
#include "anomaly_analysis.h"

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// ── Internal helpers ──────────────────────────��────────────────────────────

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

static inline float effective_thermal_min_delta(const anomaly_config_t *cfg) {
    if (cfg == NULL || cfg->thermal_min_delta <= 0.0f) {
        return ANOMALY_THERMAL_MIN_DELTA;
    }
    return cfg->thermal_min_delta;
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
            selection_map[sy * sg_w + sx] = score;
        }
    }
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

    int sample_step = (width >= 1280 || height >= 720) ? 4 : 2;
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
    memset(state->acc_active, 0, sizeof(state->acc_active));
    if (state->prev_luma != NULL) {
        free(state->prev_luma);
        state->prev_luma = NULL;
    }
    state->prev_luma_width  = 0;
    state->prev_luma_height = 0;
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

    if (cfg == NULL || !cfg->enabled || cfg->algorithm_mask == 0) return 0;
    if (width <= 0 || height <= 0) return 0;

    int frame_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
    float thermal_min_delta = effective_thermal_min_delta(cfg);
    state->frame_counter += 1;
    if ((state->frame_counter % frame_stride) != 0) return 0;

    // ── ROI bounds (centered scan zone) ─────────────────────────────────
    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)width);
    int roi_x1 = width  - roi_x0;
    int roi_y0 = (int)(margin * (float)height);
    int roi_y1 = height - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = width;  }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = height; }

    int sample_step = (width >= 1280 || height >= 720) ? 4 : 2;

    // ── Build full-frame luma grid (needed for GMV offset lookups) ───────
    int motion_step  = sample_step * 2;
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

        int anchor_dx[9], anchor_dy[9];
        int anchor_ax[9], anchor_ay[9];
        int anchor_count = 0;

        for (int gy = 0; gy < 3; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                int ax = roi_mgx0 + (roi_mgx1 - roi_mgx0) * (gx + 1) / 4;
                int ay = roi_mgy0 + (roi_mgy1 - roi_mgy0) * (gy + 1) / 4;
                if (ax < ph + sr || ax > motion_w - 1 - ph - sr) continue;
                if (ay < ph + sr || ay > motion_h - 1 - ph - sr) continue;

                int  best_dx = 0, best_dy = 0;
                long best_sad = 0x7FFFFFFFL;
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
                        if (sad < best_sad) { best_sad = sad; best_dx = dx; best_dy = dy; }
                    }
                }
                anchor_ax[anchor_count] = ax;
                anchor_ay[anchor_count] = ay;
                anchor_dx[anchor_count] = best_dx;
                anchor_dy[anchor_count] = best_dy;
                anchor_count++;
            }
        }

        if (anchor_count >= 2) {
            float src_x[9], src_y[9], dst_x[9], dst_y[9];
            for (int i = 0; i < anchor_count; i++) {
                src_x[i] = (float)(anchor_ax[i] * motion_step) / fw;
                src_y[i] = (float)(anchor_ay[i] * motion_step) / fh;
                dst_x[i] = (float)((anchor_ax[i] + anchor_dx[i]) * motion_step) / fw;
                dst_y[i] = (float)((anchor_ay[i] + anchor_dy[i]) * motion_step) / fh;
            }
            sim = fit_similarity_2d(src_x, src_y, dst_x, dst_y, anchor_count);

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
    if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
        saliency_spatial_map = (float *)malloc(sg_count * sizeof(float));
        saliency_color_map   = (float *)malloc(sg_count * sizeof(float));
        saliency_motion_map  = (float *)malloc(sg_count * sizeof(float));
        if (saliency_spatial_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_spatial_map[i] = -1.0f;
        }
        if (saliency_color_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_color_map[i] = 0.0f;
        }
        if (saliency_motion_map != NULL) {
            for (size_t i = 0; i < sg_count; i++) saliency_motion_map[i] = 0.0f;
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

            if ((cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
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

            if ((cfg->algorithm_mask & (ANOMALY_ALGO_COLOR | ANOMALY_ALGO_PERSIST)) != 0) {
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

    if (bg_valid && (cfg->algorithm_mask & (ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_PERSIST)) != 0) {
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

    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0 && bg_valid) {
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

    if ((cfg->algorithm_mask & (ANOMALY_ALGO_MOTION | ANOMALY_ALGO_PERSIST)) != 0 &&
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

        double diff_sum = 0.0, diff_sum2 = 0.0;
        int    diff_count = 0;
        for (int my = roi_mgy0; my < roi_mgy1; my++) {
            float cy_n = (float)(my * motion_step) / fh_m;
            for (int mx = roi_mgx0; mx < roi_mgx1; mx++) {
                float cx_n  = (float)(mx * motion_step) / fw_m;
                float px_n  = sim.a * cx_n - sim.b * cy_n + sim.tx;
                float py_n  = sim.b * cx_n + sim.a * cy_n + sim.ty;
                int   px_idx = (int)(px_n * fw_m / (float)motion_step + 0.5f);
                int   py_idx = (int)(py_n * fh_m / (float)motion_step + 0.5f);
                if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) continue;
                int d = abs((int)curr_luma[my * motion_w + mx] -
                            (int)state->prev_luma[py_idx * motion_w + px_idx]);
                diff_sum  += (double)d;
                diff_sum2 += (double)d * (double)d;
                diff_count++;
            }
        }

        if (diff_count > 1) {
            double diff_mean = diff_sum / (double)diff_count;
            double diff_std  = sqrt(fmax((diff_sum2 / (double)diff_count) - diff_mean * diff_mean, 1.0));
            for (int my = roi_mgy0; my < roi_mgy1; my++) {
                float cy_n = (float)(my * motion_step) / fh_m;
                for (int mx = roi_mgx0; mx < roi_mgx1; mx++) {
                    float cx_n  = (float)(mx * motion_step) / fw_m;
                    float px_n  = sim.a * cx_n - sim.b * cy_n + sim.tx;
                    float py_n  = sim.b * cx_n + sim.a * cy_n + sim.ty;
                    int   px_idx = (int)(px_n * fw_m / (float)motion_step + 0.5f);
                    int   py_idx = (int)(py_n * fh_m / (float)motion_step + 0.5f);
                    if (px_idx < 0 || px_idx >= motion_w || py_idx < 0 || py_idx >= motion_h) continue;
                    int d = abs((int)curr_luma[my * motion_w + mx] -
                                (int)state->prev_luma[py_idx * motion_w + px_idx]);
                    float ms = (float)(((double)d - diff_mean) / diff_std);
                    if (saliency_motion_map != NULL) {
                        float motion_support = ms - 1.0f;
                        if (motion_support > 0.0f) {
                            if (motion_support > 4.0f) motion_support = 4.0f;
                            int sample_x = mx * motion_step + motion_step / 2;
                            int sample_y = my * motion_step + motion_step / 2;
                            int sal_sx = clamp_i32((sample_x - roi_x0 + (sample_step / 2)) / sample_step, 0, sg_w - 1);
                            int sal_sy = clamp_i32((sample_y - roi_y0 + (sample_step / 2)) / sample_step, 0, sg_h - 1);
                            float *slot = &saliency_motion_map[sal_sy * sg_w + sal_sx];
                            if (motion_support > *slot) *slot = motion_support;
                        }
                    }
                    if ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0 &&
                        ms > best_motion) {
                        best_motion   = ms;
                        best_motion_x = mx * motion_step + motion_step / 2;
                        best_motion_y = my * motion_step + motion_step / 2;
                    }
                }
            }
        }
    }

    if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) != 0) {
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
                    float spatial_evidence = thermal_spatial > 0.0f ? thermal_spatial : 0.0f;
                    if (color_support > 0.0f) spatial_evidence += 0.60f * color_support;
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
    if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR)   && best_color   >= cfg->score_threshold) {
        raw_cx[0] = (float)best_color_x   / fw;
        raw_cy[0] = (float)best_color_y   / fh;
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) && best_thermal >= cfg->score_threshold) {
        raw_cx[1] = (float)best_thermal_x / fw;
        raw_cy[1] = (float)best_thermal_y / fh;
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION)  && best_motion  >= cfg->score_threshold) {
        raw_cx[2] = (float)best_motion_x  / fw;
        raw_cy[2] = (float)best_motion_y  / fh;
    }
    if ((cfg->algorithm_mask & ANOMALY_ALGO_PERSIST) &&
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
        if (raw_cx[ai] >= 0.0f) {
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
            int hold = state->acc_hold[ai] - 1;
            if (hold <= 0) {
                state->acc_active[ai] = false;
                state->acc_hits[ai]   = 0;
                state->acc_hold[ai]   = 0;
            } else {
                state->acc_hold[ai] = hold;
            }
        }
    }

    // ── Assemble and draw boxes ─────────────────────��─────────────────��──
    float box_side = sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f));
    box_side = (box_side < 0.02f) ? 0.02f : (box_side > 0.18f ? 0.18f : box_side);

    static const uint8_t algo_rgb[4][3] = {
        {0x2D, 0x6C, 0xFF},  // Color Outlier: blue
        {0xF2, 0x30, 0x30},  // Thermal Hotspot: red
        {0x23, 0xC5, 0x52},  // Motion: green
        {0xFF, 0xE0, 0x3B},  // Unified Saliency: yellow
    };
    static const float algo_box_scale[4] = {1.0f, 1.0f, 1.3f, 0.9f};
    static const int   algo_bits[4]      = {ANOMALY_ALGO_COLOR, ANOMALY_ALGO_THERMAL, ANOMALY_ALGO_MOTION, ANOMALY_ALGO_PERSIST};

    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int box_count = 0;

    for (int ai = 0; ai < 4; ai++) {
        if (!state->acc_active[ai]) continue;
        if (state->acc_hits[ai] < min_hits) continue;
        // Weight: 0.35 at min_hits, scaling to 1.0 at min_hits+5.
        float weight = 0.35f + 0.13f * (float)(state->acc_hits[ai] - min_hits);
        if (weight > 1.0f) weight = 1.0f;
        float bw = box_side * algo_box_scale[ai];
        append_anomaly_box(boxes, &box_count,
                           state->acc_cx[ai], state->acc_cy[ai],
                           bw, bw,
                           algo_rgb[ai][0], algo_rgb[ai][1], algo_rgb[ai][2],
                           weight);
        if (box_count > 0)
            boxes[box_count - 1].algorithm = algo_bits[ai];
    }

    if (result_out != NULL) {
        result_out->box_count = box_count;
        for (int i = 0; i < box_count && i < ANOMALY_MAX_BOXES_PER_FRAME; i++)
            result_out->boxes[i] = boxes[i];
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

    if (box_count > 0 && rgba != NULL)
        draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, boxes, box_count);

    free(saliency_motion_map);
    free(saliency_color_map);
    free(saliency_spatial_map);
    free(sg_luma);
    free(ii_sum);
    free(ii_sum2);

    return box_count;
}

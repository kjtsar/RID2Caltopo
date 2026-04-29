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
        result_out->box_count        = 0;
        result_out->had_discontinuity = false;
    }

    if (cfg == NULL || !cfg->enabled || cfg->algorithm_mask == 0) return 0;
    if (width <= 0 || height <= 0) return 0;

    int frame_stride = cfg->frame_stride < 1 ? 1 : cfg->frame_stride;
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
                        for (int ky = -ph; ky <= ph; ky++) {
                            for (int kx = -ph; kx <= ph; kx++) {
                                int cv = curr_luma[(ay + ky) * motion_w + (ax + kx)];
                                int pv = state->prev_luma[(ay + dy + ky) * motion_w + (ax + dx + kx)];
                                int d = cv - pv; sad += d < 0 ? -d : d;
                            }
                        }
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
    for (int ai = 0; ai < 3; ai++) {
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

    // ── Statistics pass: global + per-tile local stats ──────────────────
    // Global stats are the fallback for tiles with too few samples.
    // Local tile stats are what we actually use for scoring — they make the
    // detector ask "is this pixel anomalous for its immediate neighbourhood?"
    // rather than "is this pixel the global extreme?".  At 600 ft altitude a
    // person's warm legs may not be the hottest thing in the whole frame, but
    // they will stand out clearly within the surrounding 1/8-frame tile.
#define T ANOMALY_LOCAL_TILE_SIZE
    double sum_y = 0.0, sum_y2 = 0.0;
    double sum_u = 0.0, sum_u2 = 0.0;
    double sum_v = 0.0, sum_v2 = 0.0;
    int sample_count = 0;

    double tile_sum_y[T][T],  tile_sum_y2[T][T];
    double tile_sum_u[T][T],  tile_sum_u2[T][T];
    double tile_sum_v[T][T],  tile_sum_v2[T][T];
    int    tile_n[T][T];
    for (int tr = 0; tr < T; tr++)
        for (int tc = 0; tc < T; tc++) {
            tile_sum_y[tr][tc] = tile_sum_y2[tr][tc] = 0.0;
            tile_sum_u[tr][tc] = tile_sum_u2[tr][tc] = 0.0;
            tile_sum_v[tr][tc] = tile_sum_v2[tr][tc] = 0.0;
            tile_n[tr][tc] = 0;
        }

    int roi_w = roi_x1 - roi_x0;
    int roi_h = roi_y1 - roi_y0;
    if (roi_w <= 0) roi_w = 1;
    if (roi_h <= 0) roi_h = 1;

    for (int y = roi_y0; y < roi_y1; y += sample_step) {
        int tr = (y - roi_y0) * T / roi_h;
        if (tr < 0) tr = 0; if (tr >= T) tr = T - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = roi_x0; x < roi_x1; x += sample_step) {
            int tc = (x - roi_x0) * T / roi_w;
            if (tc < 0) tc = 0; if (tc >= T) tc = T - 1;
            const uint8_t *px = row + (x * 4);
            double r = (double)px[0], g = (double)px[1], b = (double)px[2];
            double lum = (0.2126 * r) + (0.7152 * g) + (0.0722 * b);
            double u   = (-0.14713 * r) - (0.28886 * g) + (0.43600 * b);
            double v   = ( 0.61500 * r) - (0.51499 * g) - (0.10001 * b);
            sum_y += lum; sum_y2 += lum * lum;
            sum_u += u;   sum_u2 += u * u;
            sum_v += v;   sum_v2 += v * v;
            sample_count++;
            tile_sum_y[tr][tc]  += lum; tile_sum_y2[tr][tc] += lum * lum;
            tile_sum_u[tr][tc]  += u;   tile_sum_u2[tr][tc] += u * u;
            tile_sum_v[tr][tc]  += v;   tile_sum_v2[tr][tc] += v * v;
            tile_n[tr][tc]++;
        }
    }

    if (sample_count <= 1) {
        if (curr_luma != NULL) {
            if (state->prev_luma != NULL) free(state->prev_luma);
            state->prev_luma        = curr_luma;
            state->prev_luma_width  = motion_w;
            state->prev_luma_height = motion_h;
        }
        return 0;
    }

    // Global fallback stats.
    double g_mean_y = sum_y / (double)sample_count;
    double g_std_y  = sqrt(fmax((sum_y2 / (double)sample_count) - g_mean_y * g_mean_y, 1.0));
    double g_mean_u = sum_u / (double)sample_count;
    double g_std_u  = sqrt(fmax((sum_u2 / (double)sample_count) - g_mean_u * g_mean_u, 1.0));
    double g_mean_v = sum_v / (double)sample_count;
    double g_std_v  = sqrt(fmax((sum_v2 / (double)sample_count) - g_mean_v * g_mean_v, 1.0));

    // Per-tile mean/std arrays; tiles with < ANOMALY_LOCAL_TILE_MIN_N samples
    // fall back to the global values.
    double tile_mean_y[T][T], tile_std_y[T][T];
    double tile_mean_u[T][T], tile_std_u[T][T];
    double tile_mean_v[T][T], tile_std_v[T][T];
    for (int tr = 0; tr < T; tr++) {
        for (int tc = 0; tc < T; tc++) {
            int n = tile_n[tr][tc];
            if (n >= ANOMALY_LOCAL_TILE_MIN_N) {
                double fn = (double)n;
                tile_mean_y[tr][tc] = tile_sum_y[tr][tc] / fn;
                tile_std_y[tr][tc]  = sqrt(fmax(tile_sum_y2[tr][tc]/fn - tile_mean_y[tr][tc]*tile_mean_y[tr][tc], 1.0));
                tile_mean_u[tr][tc] = tile_sum_u[tr][tc] / fn;
                tile_std_u[tr][tc]  = sqrt(fmax(tile_sum_u2[tr][tc]/fn - tile_mean_u[tr][tc]*tile_mean_u[tr][tc], 1.0));
                tile_mean_v[tr][tc] = tile_sum_v[tr][tc] / fn;
                tile_std_v[tr][tc]  = sqrt(fmax(tile_sum_v2[tr][tc]/fn - tile_mean_v[tr][tc]*tile_mean_v[tr][tc], 1.0));
            } else {
                tile_mean_y[tr][tc] = g_mean_y; tile_std_y[tr][tc] = g_std_y;
                tile_mean_u[tr][tc] = g_mean_u; tile_std_u[tr][tc] = g_std_u;
                tile_mean_v[tr][tc] = g_mean_v; tile_std_v[tr][tc] = g_std_v;
            }
        }
    }
#undef T

    // ── Per-pixel scoring over ROI (local tile stats) ────────────────────
    float best_color = -1.0f, best_thermal = -1.0f;
    int   best_color_x = 0, best_color_y = 0;
    int   best_thermal_x = 0, best_thermal_y = 0;

    for (int y = roi_y0; y < roi_y1; y += sample_step) {
        int tr = (y - roi_y0) * ANOMALY_LOCAL_TILE_SIZE / roi_h;
        if (tr < 0) tr = 0; if (tr >= ANOMALY_LOCAL_TILE_SIZE) tr = ANOMALY_LOCAL_TILE_SIZE - 1;
        const uint8_t *row = rgba + (y * rgba_stride);
        for (int x = roi_x0; x < roi_x1; x += sample_step) {
            int tc = (x - roi_x0) * ANOMALY_LOCAL_TILE_SIZE / roi_w;
            if (tc < 0) tc = 0; if (tc >= ANOMALY_LOCAL_TILE_SIZE) tc = ANOMALY_LOCAL_TILE_SIZE - 1;
            const uint8_t *px = row + (x * 4);
            double r = (double)px[0], g = (double)px[1], b = (double)px[2];
            double lum = (0.2126 * r) + (0.7152 * g) + (0.0722 * b);
            if ((cfg->algorithm_mask & ANOMALY_ALGO_THERMAL) != 0) {
                double mean_y = tile_mean_y[tr][tc];
                double std_y  = tile_std_y[tr][tc];
                float ts = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                           ? (float)((mean_y - lum) / std_y)
                           : (float)((lum - mean_y) / std_y);
                if (ts > best_thermal) { best_thermal = ts; best_thermal_x = x; best_thermal_y = y; }
            }
            if ((cfg->algorithm_mask & ANOMALY_ALGO_COLOR) != 0) {
                double u = (-0.14713 * r) - (0.28886 * g) + (0.43600 * b);
                double v = ( 0.61500 * r) - (0.51499 * g) - (0.10001 * b);
                float cs = (float)(fabs((u - tile_mean_u[tr][tc]) / tile_std_u[tr][tc])
                                 + fabs((v - tile_mean_v[tr][tc]) / tile_std_v[tr][tc]));
                if (cs > best_color) { best_color = cs; best_color_x = x; best_color_y = y; }
            }
        }
    }

    // ── GMV-compensated motion scoring over ROI ──────────────────────────
    float best_motion = -1.0f;
    int   best_motion_x = 0, best_motion_y = 0;

    if ((cfg->algorithm_mask & ANOMALY_ALGO_MOTION) != 0 &&
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
                    if (ms > best_motion) {
                        best_motion   = ms;
                        best_motion_x = mx * motion_step + motion_step / 2;
                        best_motion_y = my * motion_step + motion_step / 2;
                    }
                }
            }
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

    float raw_cx[3] = {-1.0f, -1.0f, -1.0f};
    float raw_cy[3] = {-1.0f, -1.0f, -1.0f};
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

    float gate  = ANOMALY_ACC_GATE_RADIUS;
    float alpha = ANOMALY_ACC_EMA_ALPHA;
    for (int ai = 0; ai < 3; ai++) {
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
                if (dist <= gate) {
                    state->acc_cx[ai] += alpha * ddx;
                    state->acc_cy[ai] += alpha * ddy;
                    int h = state->acc_hits[ai] + 1;
                    state->acc_hits[ai] = h > ANOMALY_ACC_MAX_HITS ? ANOMALY_ACC_MAX_HITS : h;
                } else {
                    // Detection jumped to a new region; reset to new location.
                    state->acc_cx[ai]   = raw_cx[ai];
                    state->acc_cy[ai]   = raw_cy[ai];
                    state->acc_hits[ai] = 1;
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

    static const uint8_t algo_rgb[3][3] = {
        {0xE6, 0x7E, 0x22},  // Color Outlier: orange
        {0xFB, 0x4D, 0x3D},  // Thermal Hotspot: red
        {0x26, 0xC6, 0xDA},  // Motion: cyan
    };
    static const float algo_box_scale[3] = {1.0f, 1.0f, 1.3f};
    static const int   algo_bits[3]      = {ANOMALY_ALGO_COLOR, ANOMALY_ALGO_THERMAL, ANOMALY_ALGO_MOTION};

    anomaly_box_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int box_count = 0;

    int min_hits = cfg->min_hits < 1 ? 1 : cfg->min_hits;
    for (int ai = 0; ai < 3; ai++) {
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
    }

    if (box_count > 0 && rgba != NULL)
        draw_anomaly_boxes_rgba(rgba, rgba_stride, width, height, boxes, box_count);

    return box_count;
}

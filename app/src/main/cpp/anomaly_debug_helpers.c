#include "anomaly_debug_helpers.h"
#include "anomaly_registration_model.h"

#include <math.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

static int anomaly_debug_clamp_i32(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static float anomaly_debug_clamp01f(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

const char *anomaly_debug_scan_reason_flag_name(uint32_t flag) {
    switch (flag) {
        case ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH:
            return "no-appearance-refresh";
        case ANOMALY_SCAN_REASON_NO_SAMPLES:
            return "no-samples";
        case ANOMALY_SCAN_REASON_PREV_STATE_INVALID:
            return "prev-state-invalid";
        case ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY:
            return "scene-discontinuity";
        case ANOMALY_SCAN_REASON_REG_INVALID:
            return "reg-invalid";
        case ANOMALY_SCAN_REASON_REG_HARD_DEGRADED:
            return "reg-hard-degraded";
        case ANOMALY_SCAN_REASON_WARP_LOW:
            return "warp-low";
        case ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH:
            return "new-exposed-high";
        case ANOMALY_SCAN_REASON_STALE_HIGH:
            return "stale-high";
        case ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH:
            return "sample-step-mismatch";
        case ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE:
            return "target-only-eligible";
        case ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE:
            return "partial-eligible";
        case ANOMALY_SCAN_REASON_MASK_BUILD_FAILED:
            return "mask-build-failed";
        case ANOMALY_SCAN_REASON_MASK_EMPTY:
            return "mask-empty";
        case ANOMALY_SCAN_REASON_MASK_TOO_BROAD:
            return "mask-too-broad";
        case ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH:
            return "periodic-full-refresh";
        default:
            return "unknown";
    }
}

void anomaly_debug_format_scan_reason_flags(
        uint32_t flags,
        char    *buffer,
        size_t   buffer_size) {
    if (buffer == NULL || buffer_size == 0) return;
    buffer[0] = '\0';
    if (flags == 0u) {
        snprintf(buffer, buffer_size, "none");
        return;
    }
    const uint32_t known_flags[] = {
        ANOMALY_SCAN_REASON_NO_APPEARANCE_REFRESH,
        ANOMALY_SCAN_REASON_NO_SAMPLES,
        ANOMALY_SCAN_REASON_PREV_STATE_INVALID,
        ANOMALY_SCAN_REASON_SCENE_DISCONTINUITY,
        ANOMALY_SCAN_REASON_REG_INVALID,
        ANOMALY_SCAN_REASON_REG_HARD_DEGRADED,
        ANOMALY_SCAN_REASON_WARP_LOW,
        ANOMALY_SCAN_REASON_NEW_EXPOSED_HIGH,
        ANOMALY_SCAN_REASON_STALE_HIGH,
        ANOMALY_SCAN_REASON_SAMPLE_STEP_MISMATCH,
        ANOMALY_SCAN_REASON_TARGET_ONLY_ELIGIBLE,
        ANOMALY_SCAN_REASON_PARTIAL_ELIGIBLE,
        ANOMALY_SCAN_REASON_MASK_BUILD_FAILED,
        ANOMALY_SCAN_REASON_MASK_EMPTY,
        ANOMALY_SCAN_REASON_MASK_TOO_BROAD,
        ANOMALY_SCAN_REASON_PERIODIC_FULL_REFRESH,
    };
    size_t offset = 0;
    for (size_t i = 0; i < sizeof(known_flags) / sizeof(known_flags[0]); i++) {
        uint32_t flag = known_flags[i];
        if ((flags & flag) == 0u) continue;
        int written = snprintf(buffer + offset,
                               buffer_size - offset,
                               "%s%s",
                               offset > 0 ? "|" : "",
                               anomaly_debug_scan_reason_flag_name(flag));
        if (written < 0) break;
        if ((size_t)written >= buffer_size - offset) {
            offset = buffer_size - 1;
            break;
        }
        offset += (size_t)written;
    }
}

const char *anomaly_debug_registration_invalid_reason_name(
        anomaly_registration_invalid_reason_t reason) {
    switch (reason) {
        case ANOMALY_REG_INVALID_REASON_NONE:
            return "none";
        case ANOMALY_REG_INVALID_REASON_DEBUG_INPUT_UNAVAILABLE:
            return "debug-input-unavailable";
        case ANOMALY_REG_INVALID_REASON_GMV_TOO_FEW_ANCHORS:
            return "gmv-too-few-anchors";
        case ANOMALY_REG_INVALID_REASON_GMV_FIT_INVALID:
            return "gmv-fit-invalid";
        case ANOMALY_REG_INVALID_REASON_GMV_RESIDUAL_TOO_HIGH:
            return "gmv-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_GMV_MOTION_TOO_LARGE:
            return "gmv-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_GMV_SCALE_OUT_OF_RANGE:
            return "gmv-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_ROI_DEGENERATE:
            return "affine-roi-degenerate";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_CORNERS:
            return "affine-too-few-corners";
        case ANOMALY_REG_INVALID_REASON_AFFINE_TOO_FEW_MATCHES:
            return "affine-too-few-matches";
        case ANOMALY_REG_INVALID_REASON_AFFINE_FIT_FAILED:
            return "affine-fit-failed";
        case ANOMALY_REG_INVALID_REASON_AFFINE_RESIDUAL_TOO_HIGH:
            return "affine-residual-too-high";
        case ANOMALY_REG_INVALID_REASON_AFFINE_MOTION_TOO_LARGE:
            return "affine-motion-too-large";
        case ANOMALY_REG_INVALID_REASON_AFFINE_SCALE_OUT_OF_RANGE:
            return "affine-scale-out-of-range";
        case ANOMALY_REG_INVALID_REASON_AFFINE_NEGATIVE_DET:
            return "affine-negative-det";
        default:
            return "unknown";
    }
}

void anomaly_debug_populate_registration_model(
        const anomaly_registration_model_t *model,
        anomaly_result_t                   *result_out) {
    if (model == NULL || result_out == NULL) return;
    result_out->gmv_debug.valid = model->debug_valid;
    result_out->gmv_debug.scene_discontinuity = model->scene_discontinuity;
    result_out->gmv_debug.sample_step = model->sample_step;
    result_out->gmv_debug.motion_step = model->motion_step;
    result_out->gmv_debug.anchor_count = model->anchor_count;
    result_out->gmv_debug.invalid_reason = model->invalid_reason;
    result_out->gmv_debug.tracked_match_count = model->tracked_match_count;
    result_out->gmv_debug.fit_a = model->similarity.a;
    result_out->gmv_debug.fit_b = model->similarity.b;
    result_out->gmv_debug.fit_tx = model->similarity.tx;
    result_out->gmv_debug.fit_ty = model->similarity.ty;
    result_out->gmv_debug.fit_scale = anomaly_registration_model_scale(model);
    result_out->gmv_debug.fit_theta_deg =
        atan2f(model->similarity.b, model->similarity.a) * (180.0f / 3.14159265f);
    result_out->gmv_debug.fit_mean_residual = model->similarity.mean_residual;
    result_out->gmv_debug.fit_det = model->fit_det;
    result_out->gmv_debug.fit_min_scale = model->fit_min_scale;
    result_out->gmv_debug.fit_max_scale = model->fit_max_scale;
    result_out->gmv_debug.fit_anchor_residual_std = model->fit_anchor_residual_std;
    result_out->gmv_debug.fit_anchor_residual_max = model->fit_anchor_residual_max;
    result_out->gmv_debug.fit_motion_dx_std = model->fit_motion_dx_std;
    result_out->gmv_debug.fit_motion_dy_std = model->fit_motion_dy_std;
    result_out->gmv_debug.fit_quadrant_residual_spread = model->fit_quadrant_residual_spread;
    for (int i = 0; i < model->anchor_count && i < ANOMALY_GMV_MAX_DEBUG_ANCHORS; i++) {
        result_out->gmv_debug.anchors[i] = model->anchors[i];
    }
}

void anomaly_debug_insert_top_candidate(
        anomaly_debug_candidate_t *candidates,
        int                       *count,
        int                        max_count,
        int                        pixel_x,
        int                        pixel_y,
        float                      x_norm,
        float                      y_norm,
        float                      spatial_score,
        float                      temporal_score,
        float                      combined_score) {
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

void anomaly_debug_draw_rgba_hline(
        uint8_t *rgba,
        int      rgba_stride,
        int      width,
        int      height,
        int      x0,
        int      x1,
        int      y,
        uint8_t  r,
        uint8_t  g,
        uint8_t  b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (y < 0 || y >= height) return;
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    x0 = anomaly_debug_clamp_i32(x0, 0, width - 1);
    x1 = anomaly_debug_clamp_i32(x1, 0, width - 1);
    uint8_t *row = rgba + (y * rgba_stride);
    for (int x = x0; x <= x1; x++) {
        uint8_t *px = row + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

void anomaly_debug_draw_rgba_vline(
        uint8_t *rgba,
        int      rgba_stride,
        int      width,
        int      height,
        int      y0,
        int      y1,
        int      x,
        uint8_t  r,
        uint8_t  g,
        uint8_t  b) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    if (x < 0 || x >= width) return;
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    y0 = anomaly_debug_clamp_i32(y0, 0, height - 1);
    y1 = anomaly_debug_clamp_i32(y1, 0, height - 1);
    for (int y = y0; y <= y1; y++) {
        uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
        px[0] = r; px[1] = g; px[2] = b; px[3] = 0xFF;
    }
}

void anomaly_debug_draw_rgba_circle(
        uint8_t *rgba,
        int      rgba_stride,
        int      width,
        int      height,
        int      cx,
        int      cy,
        int      radius,
        int      stroke,
        uint8_t  r,
        uint8_t  g,
        uint8_t  b) {
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

void anomaly_debug_draw_boxes_rgba(
        uint8_t             *rgba,
        int                  rgba_stride,
        int                  width,
        int                  height,
        const anomaly_box_t *boxes,
        int                  box_count) {
    if (rgba == NULL || boxes == NULL || width <= 0 || height <= 0 || box_count <= 0) return;
    int min_dim    = (width < height) ? width : height;
    int stroke_max = anomaly_debug_clamp_i32((int)lroundf((double)min_dim * 0.006), 2, 8);

    for (int i = 0; i < box_count; i++) {
        const anomaly_box_t *box = &boxes[i];
        int stroke = anomaly_debug_clamp_i32((int)lroundf(stroke_max * (double)box->weight), 1, stroke_max);
        int underlay_stroke = anomaly_debug_clamp_i32(stroke + 2, 2, stroke_max + 2);
        int left   = anomaly_debug_clamp_i32((int)lroundf(box->left_norm   * (float)(width  - 1)), 0, width  - 1);
        int right  = anomaly_debug_clamp_i32((int)lroundf(box->right_norm  * (float)(width  - 1)), 0, width  - 1);
        int top    = anomaly_debug_clamp_i32((int)lroundf(box->top_norm    * (float)(height - 1)), 0, height - 1);
        int bottom = anomaly_debug_clamp_i32((int)lroundf(box->bottom_norm * (float)(height - 1)), 0, height - 1);
        if (right <= left || bottom <= top) continue;

        if (box->draw_crosshair != 0u) {
            int cx = (left + right) / 2;
            int cy = (top  + bottom) / 2;
            int box_half_w = (right - left) / 2;
            int box_half_h = (bottom - top) / 2;
            int max_gap_half_x = box_half_w - stroke;
            int max_gap_half_y = box_half_h - stroke;
            int gap_half_x = (max_gap_half_x <= stroke * 2)
                    ? stroke
                    : anomaly_debug_clamp_i32(box_half_w / 3, stroke * 2, max_gap_half_x);
            int gap_half_y = (max_gap_half_y <= stroke * 2)
                    ? stroke
                    : anomaly_debug_clamp_i32(box_half_h / 3, stroke * 2, max_gap_half_y);
            for (int t = 0; t < underlay_stroke; t++) {
                int horiz_y = cy - (underlay_stroke / 2) + t;
                int vert_x  = cx - (underlay_stroke / 2) + t;
                anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, cx - gap_half_x, horiz_y, 0x00, 0x00, 0x00);
                anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, cx + gap_half_x, right, horiz_y, 0x00, 0x00, 0x00);
                anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, cy - gap_half_y, vert_x, 0x00, 0x00, 0x00);
                anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, cy + gap_half_y, bottom, vert_x, 0x00, 0x00, 0x00);
            }
            for (int t = 0; t < stroke; t++) {
                int horiz_y = cy - (stroke / 2) + t;
                int vert_x  = cx - (stroke / 2) + t;
                anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, cx - gap_half_x, horiz_y, box->r, box->g, box->b);
                anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, cx + gap_half_x, right, horiz_y, box->r, box->g, box->b);
                anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, cy - gap_half_y, vert_x, box->r, box->g, box->b);
                anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, cy + gap_half_y, bottom, vert_x, box->r, box->g, box->b);
            }
        } else {
            for (int t = 0; t < underlay_stroke; t++) {
                int top_y   = top    + t;
                int bottom_y = bottom - t;
                int left_x  = left   + t;
                int right_x = right  - t;
                if (top_y <= bottom_y) {
                    anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, right, top_y, 0x00, 0x00, 0x00);
                    if (bottom_y != top_y) {
                        anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, right, bottom_y, 0x00, 0x00, 0x00);
                    }
                }
                if (left_x <= right_x) {
                    anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, left_x, 0x00, 0x00, 0x00);
                    if (right_x != left_x) {
                        anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, right_x, 0x00, 0x00, 0x00);
                    }
                }
            }
            for (int t = 0; t < stroke; t++) {
                int top_y   = top    + t;
                int bottom_y = bottom - t;
                int left_x  = left   + t;
                int right_x = right  - t;
                if (top_y <= bottom_y) {
                    anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, right, top_y, box->r, box->g, box->b);
                    if (bottom_y != top_y) {
                        anomaly_debug_draw_rgba_hline(rgba, rgba_stride, width, height, left, right, bottom_y, box->r, box->g, box->b);
                    }
                }
                if (left_x <= right_x) {
                    anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, left_x, box->r, box->g, box->b);
                    if (right_x != left_x) {
                        anomaly_debug_draw_rgba_vline(rgba, rgba_stride, width, height, top, bottom, right_x, box->r, box->g, box->b);
                    }
                }
            }
        }
    }
}

static inline double anomaly_debug_rgba_luma_at(const uint8_t *px) {
    return (0.2126 * (double)px[0]) + (0.7152 * (double)px[1]) + (0.0722 * (double)px[2]);
}

static bool anomaly_debug_row_has_active_content_range(
        const uint8_t *rgba,
        int            rgba_stride,
        int            width,
        int            height,
        int            y,
        int            x0,
        int            x1) {
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
        double luma = anomaly_debug_rgba_luma_at(row + (x * 4));
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

static bool anomaly_debug_col_has_active_content_range(
        const uint8_t *rgba,
        int            rgba_stride,
        int            width,
        int            height,
        int            x,
        int            y0,
        int            y1) {
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
        double luma = anomaly_debug_rgba_luma_at(px);
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

typedef bool (*anomaly_debug_active_span_fn)(const uint8_t *rgba, int rgba_stride,
                                             int width, int height, int index,
                                             int aux0, int aux1);

static void anomaly_debug_detect_best_active_span(
        const uint8_t                *rgba,
        int                           rgba_stride,
        int                           width,
        int                           height,
        int                           length,
        int                           aux0,
        int                           aux1,
        anomaly_debug_active_span_fn  is_active,
        int                          *start_out,
        int                          *end_out) {
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

static bool anomaly_debug_tile_has_active_content(
        const uint8_t *rgba,
        int            rgba_stride,
        int            width,
        int            height,
        int            x0,
        int            y0,
        int            x1,
        int            y1) {
    if (rgba == NULL || width <= 0 || height <= 0) return false;
    x0 = anomaly_debug_clamp_i32(x0, 0, width - 1);
    x1 = anomaly_debug_clamp_i32(x1, 0, width - 1);
    y0 = anomaly_debug_clamp_i32(y0, 0, height - 1);
    y1 = anomaly_debug_clamp_i32(y1, 0, height - 1);
    if (x1 <= x0 || y1 <= y0) return false;

    double min_luma = 0.0;
    double max_luma = 0.0;
    double sum_luma = 0.0;
    int sample_count = 0;
    int horiz_edges = 0;
    int horiz_pairs = 0;
    int vert_edges = 0;
    int vert_pairs = 0;
    bool first = true;
    int span_w = x1 - x0 + 1;
    int span_h = y1 - y0 + 1;
    int step_x = span_w > 48 ? 2 : 1;
    int step_y = span_h > 48 ? 2 : 1;

    for (int y = y0; y <= y1; y += step_y) {
        const uint8_t *row = rgba + (y * rgba_stride);
        double prev_luma = 0.0;
        bool prev_valid = false;
        for (int x = x0; x <= x1; x += step_x) {
            double luma = anomaly_debug_rgba_luma_at(row + (x * 4));
            if (first) {
                min_luma = max_luma = luma;
                first = false;
            } else {
                if (luma < min_luma) min_luma = luma;
                if (luma > max_luma) max_luma = luma;
            }
            sum_luma += luma;
            sample_count++;
            if (prev_valid) {
                horiz_pairs++;
                if (fabs(luma - prev_luma) >= 10.0) horiz_edges++;
            }
            prev_luma = luma;
            prev_valid = true;
        }
    }
    for (int x = x0; x <= x1; x += step_x) {
        double prev_luma = 0.0;
        bool prev_valid = false;
        for (int y = y0; y <= y1; y += step_y) {
            const uint8_t *px = rgba + (y * rgba_stride) + (x * 4);
            double luma = anomaly_debug_rgba_luma_at(px);
            if (prev_valid) {
                vert_pairs++;
                if (fabs(luma - prev_luma) >= 10.0) vert_edges++;
            }
            prev_luma = luma;
            prev_valid = true;
        }
    }

    if (sample_count <= 0) return false;
    double mean_luma = sum_luma / (double)sample_count;
    double luma_range = max_luma - min_luma;
    double horiz_fraction = (horiz_pairs > 0) ? ((double)horiz_edges / (double)horiz_pairs) : 0.0;
    double vert_fraction = (vert_pairs > 0) ? ((double)vert_edges / (double)vert_pairs) : 0.0;

    if (luma_range < 12.0) return false;
    if (mean_luma < 28.0 || mean_luma > 245.0) return false;
    return horiz_fraction >= 0.08 && vert_fraction >= 0.08;
}

static bool anomaly_debug_detect_active_content_bounds_tiles(
        const uint8_t *rgba,
        int            rgba_stride,
        int            width,
        int            height,
        int           *x0_out,
        int           *y0_out,
        int           *x1_out,
        int           *y1_out) {
    if (rgba == NULL || width <= 0 || height <= 0) return false;
    int tile_size = anomaly_debug_clamp_i32(((width < height) ? width : height) / 18, 16, 48);
    int tiles_x = (width + tile_size - 1) / tile_size;
    int tiles_y = (height + tile_size - 1) / tile_size;
    if (tiles_x <= 0 || tiles_y <= 0) return false;

    size_t tile_count = (size_t)tiles_x * (size_t)tiles_y;
    uint8_t *active = (uint8_t *)calloc(tile_count, sizeof(uint8_t));
    uint8_t *visited = (uint8_t *)calloc(tile_count, sizeof(uint8_t));
    int *queue = (int *)malloc(tile_count * sizeof(int));
    if (active == NULL || visited == NULL || queue == NULL) {
        free(active);
        free(visited);
        free(queue);
        return false;
    }

    for (int ty = 0; ty < tiles_y; ty++) {
        int py0 = ty * tile_size;
        int py1 = anomaly_debug_clamp_i32(((ty + 1) * tile_size) - 1, 0, height - 1);
        for (int tx = 0; tx < tiles_x; tx++) {
            int px0 = tx * tile_size;
            int px1 = anomaly_debug_clamp_i32(((tx + 1) * tile_size) - 1, 0, width - 1);
            if (anomaly_debug_tile_has_active_content(rgba, rgba_stride, width, height, px0, py0, px1, py1)) {
                active[(ty * tiles_x) + tx] = 1u;
            }
        }
    }

    int best_tx0 = 0, best_ty0 = 0, best_tx1 = tiles_x - 1, best_ty1 = tiles_y - 1;
    int best_area = -1;
    bool best_contains_center = false;
    double best_center_distance = (double)(tiles_x + tiles_y);

    int center_tx = tiles_x / 2;
    int center_ty = tiles_y / 2;
    for (int ty = 0; ty < tiles_y; ty++) {
        for (int tx = 0; tx < tiles_x; tx++) {
            int start_index = (ty * tiles_x) + tx;
            if (!active[start_index] || visited[start_index]) continue;
            int head = 0;
            int tail = 0;
            queue[tail++] = start_index;
            visited[start_index] = 1u;
            int comp_tx0 = tx, comp_ty0 = ty, comp_tx1 = tx, comp_ty1 = ty;
            int comp_tiles = 0;

            while (head < tail) {
                int index = queue[head++];
                int cx = index % tiles_x;
                int cy = index / tiles_x;
                comp_tiles++;
                if (cx < comp_tx0) comp_tx0 = cx;
                if (cx > comp_tx1) comp_tx1 = cx;
                if (cy < comp_ty0) comp_ty0 = cy;
                if (cy > comp_ty1) comp_ty1 = cy;

                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 0 || nx >= tiles_x || ny < 0 || ny >= tiles_y) continue;
                        int next_index = (ny * tiles_x) + nx;
                        if (!active[next_index] || visited[next_index]) continue;
                        visited[next_index] = 1u;
                        queue[tail++] = next_index;
                    }
                }
            }

            int span_tiles_x = comp_tx1 - comp_tx0 + 1;
            int span_tiles_y = comp_ty1 - comp_ty0 + 1;
            int comp_area = span_tiles_x * span_tiles_y;
            bool contains_center =
                (comp_tx0 <= center_tx && center_tx <= comp_tx1 &&
                 comp_ty0 <= center_ty && center_ty <= comp_ty1);
            double comp_center_x = 0.5 * (double)(comp_tx0 + comp_tx1);
            double comp_center_y = 0.5 * (double)(comp_ty0 + comp_ty1);
            double dx = comp_center_x - (double)center_tx;
            double dy = comp_center_y - (double)center_ty;
            double center_distance = sqrt((dx * dx) + (dy * dy));
            bool better = false;
            if (best_area < 0) {
                better = true;
            } else if (contains_center != best_contains_center) {
                better = contains_center;
            } else if (comp_area != best_area) {
                better = comp_area > best_area;
            } else if (center_distance != best_center_distance) {
                better = center_distance < best_center_distance;
            }
            if (better) {
                best_tx0 = comp_tx0;
                best_ty0 = comp_ty0;
                best_tx1 = comp_tx1;
                best_ty1 = comp_ty1;
                best_area = comp_area;
                best_contains_center = contains_center;
                best_center_distance = center_distance;
            }
        }
    }

    free(active);
    free(visited);
    free(queue);

    if (best_area < 0) return false;

    int x0 = best_tx0 * tile_size;
    int y0 = best_ty0 * tile_size;
    int x1 = anomaly_debug_clamp_i32(((best_tx1 + 1) * tile_size) - 1, 0, width - 1);
    int y1 = anomaly_debug_clamp_i32(((best_ty1 + 1) * tile_size) - 1, 0, height - 1);
    if ((x1 - x0 + 1) < width / 3 || (y1 - y0 + 1) < height / 3) {
        return false;
    }
    if (x0_out) *x0_out = x0;
    if (y0_out) *y0_out = y0;
    if (x1_out) *x1_out = x1;
    if (y1_out) *y1_out = y1;
    return true;
}

static void anomaly_debug_detect_active_content_bounds(
        const uint8_t *rgba,
        int            rgba_stride,
        int            width,
        int            height,
        int           *x0_out,
        int           *y0_out,
        int           *x1_out,
        int           *y1_out) {
    int x0 = 0;
    int y0 = 0;
    int x1 = width - 1;
    int y1 = height - 1;
    if (!anomaly_debug_detect_active_content_bounds_tiles(rgba, rgba_stride, width, height,
                                                          &x0, &y0, &x1, &y1)) {
        anomaly_debug_detect_best_active_span(rgba, rgba_stride, width, height,
                                              height, 0, width - 1,
                                              anomaly_debug_row_has_active_content_range,
                                              &y0, &y1);
        anomaly_debug_detect_best_active_span(rgba, rgba_stride, width, height,
                                              width, y0, y1,
                                              anomaly_debug_col_has_active_content_range,
                                              &x0, &x1);
        anomaly_debug_detect_best_active_span(rgba, rgba_stride, width, height,
                                              height, x0, x1,
                                              anomaly_debug_row_has_active_content_range,
                                              &y0, &y1);
    }

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

void anomaly_debug_draw_hot_overlay_rgba(
        uint8_t *rgba,
        int      rgba_stride,
        int      width,
        int      height,
        int      thermal_polarity) {
    if (rgba == NULL || width <= 0 || height <= 0) return;
    int content_x0 = 0, content_y0 = 0, content_x1 = width - 1, content_y1 = height - 1;
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
            double luma = anomaly_debug_rgba_luma_at(px);
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
    int vicinity = anomaly_debug_clamp_i32((int)lround((double)min_dim * 0.028), 10, 24);
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
            double luma = anomaly_debug_rgba_luma_at(px);
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
    int padding = anomaly_debug_clamp_i32((int)lround((double)min_dim * 0.008), 4, 8);
    int radius = anomaly_debug_clamp_i32(content_radius + padding, 8, min_dim / 5);
    int stroke = anomaly_debug_clamp_i32(2 + (hot_count / 24), 2, 5);
    anomaly_debug_draw_rgba_circle(rgba, rgba_stride, width, height, center_x, center_y, radius, stroke, 0xFF, 0x30, 0x30);
}

void anomaly_debug_append_center_box(
        anomaly_box_t *boxes,
        int           *box_count,
        int            max_boxes,
        float          center_x_norm,
        float          center_y_norm,
        float          box_w_norm,
        float          box_h_norm,
        uint8_t        r,
        uint8_t        g,
        uint8_t        b,
        float          weight) {
    if (boxes == NULL || box_count == NULL || max_boxes <= 0) return;
    if (*box_count >= max_boxes) return;
    float half_w = box_w_norm * 0.5f;
    float half_h = box_h_norm * 0.5f;
    float left   = anomaly_debug_clamp01f(center_x_norm - half_w);
    float right  = anomaly_debug_clamp01f(center_x_norm + half_w);
    float top    = anomaly_debug_clamp01f(center_y_norm - half_h);
    float bottom = anomaly_debug_clamp01f(center_y_norm + half_h);
    if (right <= left || bottom <= top) return;
    anomaly_box_t *slot = &boxes[*box_count];
    slot->left_norm   = left;
    slot->top_norm    = top;
    slot->right_norm  = right;
    slot->bottom_norm = bottom;
    slot->r = r; slot->g = g; slot->b = b;
    slot->draw_crosshair = 1u;
    slot->weight = anomaly_debug_clamp01f(weight);
    *box_count += 1;
}

void anomaly_debug_append_rect(
        anomaly_box_t *boxes,
        int           *box_count,
        int            max_boxes,
        float          left_norm,
        float          top_norm,
        float          right_norm,
        float          bottom_norm,
        uint8_t        r,
        uint8_t        g,
        uint8_t        b,
        float          weight,
        bool           draw_crosshair) {
    if (boxes == NULL || box_count == NULL || max_boxes <= 0) return;
    if (*box_count >= max_boxes) return;
    float left = anomaly_debug_clamp01f(left_norm);
    float top = anomaly_debug_clamp01f(top_norm);
    float right = anomaly_debug_clamp01f(right_norm);
    float bottom = anomaly_debug_clamp01f(bottom_norm);
    if (right <= left || bottom <= top) return;
    anomaly_box_t *slot = &boxes[*box_count];
    slot->left_norm = left;
    slot->top_norm = top;
    slot->right_norm = right;
    slot->bottom_norm = bottom;
    slot->r = r; slot->g = g; slot->b = b;
    slot->draw_crosshair = draw_crosshair ? 1u : 0u;
    slot->weight = anomaly_debug_clamp01f(weight);
    *box_count += 1;
}

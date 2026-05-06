// anomaly_video_test.c — Run anomaly detection on a captured MP4 and produce:
//
//   1. An annotated MP4 with detection boxes drawn on every frame
//   2. A CSV detection log with one row per visible box per frame
//
// The CSV has a blank "label" column at the right edge.  Open it in
// Numbers or Excel alongside the annotated video, scrub to each flagged
// timestamp, and fill in G (good / true-positive), B (bad / false-positive),
// or ? (unsure).  Those labels become the ground truth for regression tests.
//
// --probe cx,cy  diagnostic mode: for every frame, sample the same detector-side
//   thermal statistics used by anomaly_analysis.c at the given normalised
//   position and write them to a separate _probe.csv file.  This tells you what
//   score the live detector sees at the subject's known location.
//
// Build:  cmake --build build
// Usage:  ./build/anomaly_video_test <input.mp4> [options]
//         ./build/anomaly_video_test PowerHouse3.mp4 -p bh -t 2.8
//         ./build/anomaly_video_test PowerHouse1.mp4 -p bh --probe 0.42,0.28
//
// Requires ffmpeg and ffprobe on PATH.

#include "anomaly_analysis.h"

#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

// ── pixel-font frame-number overlay ───────────────────────────────────────
// 5×7 bitmap glyphs for digits 0-9.  Each byte is one row, MSB = leftmost px.
static const uint8_t kDigitFont[10][7] = {
    { 0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E }, // 0
    { 0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E }, // 1
    { 0x0E, 0x11, 0x01, 0x06, 0x08, 0x10, 0x1F }, // 2
    { 0x0E, 0x11, 0x01, 0x06, 0x01, 0x11, 0x0E }, // 3
    { 0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02 }, // 4
    { 0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E }, // 5
    { 0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E }, // 6
    { 0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08 }, // 7
    { 0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E }, // 8
    { 0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C }, // 9
};
static const uint8_t kLetterCFont[7] = { 0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E };
static const uint8_t kLetterSFont[7] = { 0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E };
static const uint8_t kLetterTFont[7] = { 0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04 };
static const uint8_t kMinusFont[7]   = { 0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00 };
#define FONT_W 5
#define FONT_H 7

static double clamp_double(double value, double min_value, double max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

// Draw a single scaled pixel at (px,py) with RGBA colour; clips to frame.
static void put_pixel(uint8_t *rgba, int stride, int W, int H,
                      int px, int py, uint8_t r, uint8_t g, uint8_t b) {
    if (px < 0 || py < 0 || px >= W || py >= H) return;
    uint8_t *p = rgba + py * stride + px * 4;
    p[0] = r; p[1] = g; p[2] = b; p[3] = 0xFF;
}

// Draw one digit at pixel origin (ox, oy) with the given scale and colour.
static void draw_digit(uint8_t *rgba, int stride, int W, int H,
                       int digit, int ox, int oy, int scale,
                       uint8_t r, uint8_t g, uint8_t b) {
    if (digit < 0 || digit > 9) return;
    const uint8_t *glyph = kDigitFont[digit];
    for (int row = 0; row < FONT_H; row++) {
        uint8_t bits = glyph[row];
        for (int col = 0; col < FONT_W; col++) {
            if (bits & (0x10 >> col)) {
                for (int sy = 0; sy < scale; sy++)
                    for (int sx = 0; sx < scale; sx++)
                        put_pixel(rgba, stride, W, H,
                                  ox + col * scale + sx,
                                  oy + row * scale + sy, r, g, b);
            }
        }
    }
}

static const uint8_t *glyph_for_char(char ch) {
    if (ch >= '0' && ch <= '9') return kDigitFont[ch - '0'];
    switch (ch) {
        case 'C': return kLetterCFont;
        case 'S': return kLetterSFont;
        case 'T': return kLetterTFont;
        case '-': return kMinusFont;
        default:  return NULL;
    }
}

static void draw_char(uint8_t *rgba, int stride, int W, int H,
                      char ch, int ox, int oy, int scale,
                      uint8_t r, uint8_t g, uint8_t b) {
    const uint8_t *glyph = glyph_for_char(ch);
    if (glyph == NULL) return;
    for (int row = 0; row < FONT_H; row++) {
        uint8_t bits = glyph[row];
        for (int col = 0; col < FONT_W; col++) {
            if (bits & (0x10 >> col)) {
                for (int sy = 0; sy < scale; sy++)
                    for (int sx = 0; sx < scale; sx++)
                        put_pixel(rgba, stride, W, H,
                                  ox + col * scale + sx,
                                  oy + row * scale + sy, r, g, b);
            }
        }
    }
}

static void draw_text(uint8_t *rgba, int stride, int W, int H,
                      const char *text, int x0, int y0, int scale,
                      uint8_t r, uint8_t g, uint8_t b) {
    if (text == NULL) return;
    int glyph_w = FONT_W * scale + scale;
    for (int i = 0; text[i] != '\0'; i++) {
        draw_char(rgba, stride, W, H, text[i], x0 + i * glyph_w, y0, scale, r, g, b);
    }
}

static void fill_rect(uint8_t *rgba, int stride, int W, int H,
                      int x0, int y0, int x1, int y1,
                      uint8_t r, uint8_t g, uint8_t b) {
    if (x0 > x1) { int t = x0; x0 = x1; x1 = t; }
    if (y0 > y1) { int t = y0; y0 = y1; y1 = t; }
    if (x1 < 0 || y1 < 0 || x0 >= W || y0 >= H) return;
    if (x0 < 0) x0 = 0; if (x1 >= W) x1 = W - 1;
    if (y0 < 0) y0 = 0; if (y1 >= H) y1 = H - 1;
    for (int y = y0; y <= y1; y++) {
        uint8_t *row = rgba + y * stride;
        for (int x = x0; x <= x1; x++) {
            uint8_t *p = row + x * 4;
            p[0] = r; p[1] = g; p[2] = b; p[3] = 0xFF;
        }
    }
}

static void stroke_rect(uint8_t *rgba, int stride, int W, int H,
                        int x0, int y0, int x1, int y1, int stroke,
                        uint8_t r, uint8_t g, uint8_t b) {
    if (stroke < 1) stroke = 1;
    for (int t = 0; t < stroke; t++) {
        fill_rect(rgba, stride, W, H, x0, y0 + t, x1, y0 + t, r, g, b);
        fill_rect(rgba, stride, W, H, x0, y1 - t, x1, y1 - t, r, g, b);
        fill_rect(rgba, stride, W, H, x0 + t, y0, x0 + t, y1, r, g, b);
        fill_rect(rgba, stride, W, H, x1 - t, y0, x1 - t, y1, r, g, b);
    }
}

static void draw_crosshair(uint8_t *rgba, int stride, int W, int H,
                           int cx, int cy, int half, int stroke,
                           uint8_t r, uint8_t g, uint8_t b) {
    if (stroke < 1) stroke = 1;
    for (int t = 0; t < stroke; t++) {
        fill_rect(rgba, stride, W, H, cx - half, cy + t, cx + half, cy + t, r, g, b);
        fill_rect(rgba, stride, W, H, cx + t, cy - half, cx + t, cy + half, r, g, b);
    }
}

static float rgba_luma(const uint8_t *px) {
    return (0.2126f * (float)px[0]) + (0.7152f * (float)px[1]) + (0.0722f * (float)px[2]);
}

static void compute_pixel_support(const uint8_t *rgba, int stride, int W, int H,
                                  int cx, int cy, int black_hot,
                                  float *center_out, float *mean9_out,
                                  float *top3mean_out, int *near_count_out) {
    if (center_out) *center_out = 0.0f;
    if (mean9_out) *mean9_out = 0.0f;
    if (top3mean_out) *top3mean_out = 0.0f;
    if (near_count_out) *near_count_out = 0;
    if (rgba == NULL || W <= 0 || H <= 0) return;

    float vals[9];
    int n = 0;
    for (int y = cy - 1; y <= cy + 1; y++) {
        int yy = y < 0 ? 0 : (y >= H ? H - 1 : y);
        const uint8_t *row = rgba + yy * stride;
        for (int x = cx - 1; x <= cx + 1; x++) {
            int xx = x < 0 ? 0 : (x >= W ? W - 1 : x);
            vals[n++] = rgba_luma(row + xx * 4);
        }
    }

    float center = vals[4];
    float sum = 0.0f;
    for (int i = 0; i < 9; i++) sum += vals[i];
    for (int i = 0; i < 9 - 1; i++) {
        for (int j = i + 1; j < 9; j++) {
            bool swap = black_hot ? (vals[j] < vals[i]) : (vals[j] > vals[i]);
            if (swap) {
                float t = vals[i];
                vals[i] = vals[j];
                vals[j] = t;
            }
        }
    }
    float top3 = (vals[0] + vals[1] + vals[2]) / 3.0f;
    float near_threshold = 8.0f;
    int near_count = 0;
    for (int i = 0; i < 9; i++) {
        float v = vals[i];
        if (black_hot ? (v <= center + near_threshold) : (v >= center - near_threshold)) {
            near_count++;
        }
    }

    if (center_out) *center_out = center;
    if (mean9_out) *mean9_out = sum / 9.0f;
    if (top3mean_out) *top3mean_out = top3;
    if (near_count_out) *near_count_out = near_count;
}

static void find_extreme_pixel(const uint8_t *rgba, int stride, int W, int H,
                               const anomaly_config_t *cfg,
                               int *best_x_out, int *best_y_out, float *best_luma_out) {
    if (best_x_out) *best_x_out = 0;
    if (best_y_out) *best_y_out = 0;
    if (best_luma_out) *best_luma_out = 0.0f;
    if (rgba == NULL || cfg == NULL || W <= 0 || H <= 0) return;

    float margin = (1.0f - cfg->scan_zone) * 0.5f;
    int roi_x0 = (int)(margin * (float)W);
    int roi_x1 = W - roi_x0;
    int roi_y0 = (int)(margin * (float)H);
    int roi_y1 = H - roi_y0;
    if (roi_x1 <= roi_x0) { roi_x0 = 0; roi_x1 = W; }
    if (roi_y1 <= roi_y0) { roi_y0 = 0; roi_y1 = H; }

    int best_x = roi_x0;
    int best_y = roi_y0;
    float best_luma = rgba_luma(rgba + roi_y0 * stride + roi_x0 * 4);
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);

    for (int y = roi_y0; y < roi_y1; y++) {
        const uint8_t *row = rgba + y * stride;
        for (int x = roi_x0; x < roi_x1; x++) {
            float luma = rgba_luma(row + x * 4);
            if ((black_hot && luma < best_luma) || (!black_hot && luma > best_luma)) {
                best_luma = luma;
                best_x = x;
                best_y = y;
            }
        }
    }

    if (best_x_out) *best_x_out = best_x;
    if (best_y_out) *best_y_out = best_y;
    if (best_luma_out) *best_luma_out = best_luma;
}

static void draw_number_badge(uint8_t *rgba, int stride, int W, int H,
                              int x0, int y0, int number, int scale,
                              uint8_t bg_r, uint8_t bg_g, uint8_t bg_b,
                              uint8_t fg_r, uint8_t fg_g, uint8_t fg_b) {
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", number);
    int ndigits = (int)strlen(buf);
    int glyph_w = FONT_W * scale + scale;
    int glyph_h = FONT_H * scale;
    int pad = scale;
    fill_rect(rgba, stride, W, H,
              x0, y0,
              x0 + ndigits * glyph_w - scale + pad * 2,
              y0 + glyph_h + pad * 2,
              bg_r, bg_g, bg_b);
    for (int i = 0; i < ndigits; i++) {
        draw_digit(rgba, stride, W, H, buf[i] - '0',
                   x0 + pad + i * glyph_w, y0 + pad, scale,
                   fg_r, fg_g, fg_b);
    }
}

static void draw_text_badge(uint8_t *rgba, int stride, int W, int H,
                            int x0, int y0, const char *text, int scale,
                            uint8_t bg_r, uint8_t bg_g, uint8_t bg_b,
                            uint8_t fg_r, uint8_t fg_g, uint8_t fg_b) {
    if (text == NULL) return;
    int nchars = (int)strlen(text);
    int glyph_w = FONT_W * scale + scale;
    int glyph_h = FONT_H * scale;
    int pad = scale;
    fill_rect(rgba, stride, W, H,
              x0, y0,
              x0 + nchars * glyph_w - scale + pad * 2,
              y0 + glyph_h + pad * 2,
              bg_r, bg_g, bg_b);
    draw_text(rgba, stride, W, H, text, x0 + pad, y0 + pad, scale, fg_r, fg_g, fg_b);
}

// Render the frame number in the top-left corner.
// White text on an opaque black badge for maximum legibility.
static void draw_frame_number(uint8_t *rgba, int W, int H, int frame_num) {
    // Choose scale so the label is comfortably readable at any resolution.
    int scale = (W >= 1280 || H >= 720) ? 5 : 3;
    int glyph_w = FONT_W * scale + scale;   // inter-character gap
    int glyph_h = FONT_H * scale;

    // Convert frame number to digit array (up to 6 digits).
    char buf[8];
    snprintf(buf, sizeof(buf), "%d", frame_num);
    int ndigits = (int)strlen(buf);

    int margin = scale * 2;
    int pad = scale * 2;
    int bg_x0 = margin - pad;
    int bg_y0 = margin - pad;
    int bg_x1 = margin + ndigits * glyph_w - scale + pad;
    int bg_y1 = margin + glyph_h + pad;
    fill_rect(rgba, W * 4, W, H, bg_x0, bg_y0, bg_x1, bg_y1, 0, 0, 0);

    // White foreground with a thicker black outline.
    for (int d = 0; d < ndigits; d++) {
        int digit = buf[d] - '0';
        int ox    = margin + d * glyph_w;
        int oy    = margin;
        draw_digit(rgba, W * 4, W, H, digit, ox - 2, oy,     scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox + 2, oy,     scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy - 2, scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy + 2, scale, 0, 0, 0);
        draw_digit(rgba, W * 4, W, H, digit, ox,     oy,     scale, 255, 255, 255);
    }
}

static void draw_saliency_debug_overlay(uint8_t *rgba, int W, int H,
                                        const uint8_t *raw_rgba,
                                        const anomaly_config_t *cfg,
                                        const anomaly_result_t *result) {
    if (rgba == NULL || raw_rgba == NULL || cfg == NULL || result == NULL) return;
    const anomaly_debug_saliency_t *dbg = &result->saliency_debug;
    int min_dim = W < H ? W : H;
    int stroke = (W >= 1280 || H >= 720) ? 2 : 1;
    int label_scale = (W >= 1280 || H >= 720) ? 2 : 1;
    int cross_half = min_dim / 60;
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    if (cross_half < 6) cross_half = 6;
    if (cross_half > 14) cross_half = 14;

    float box_side = sqrtf(fmaxf(cfg->min_area_fraction, 0.0001f));
    box_side = (box_side < 0.02f) ? 0.02f : (box_side > 0.18f ? 0.18f : box_side);
    box_side *= 0.9f;  // match saliency box scale in anomaly_analysis.c
    int half_w = (int)lroundf(box_side * 0.5f * (float)(W - 1));
    int half_h = (int)lroundf(box_side * 0.5f * (float)(H - 1));

    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        if (!c->valid) continue;
        int cx = c->pixel_x;
        int cy = c->pixel_y;
        int x0 = cx - half_w;
        int y0 = cy - half_h;
        int x1 = cx + half_w;
        int y1 = cy + half_h;
        uint8_t r = (i == 0) ? 255 : 255;
        uint8_t g = (i == 0) ? 255 : 255;
        uint8_t b = (i == 0) ? 255 : 255;
        stroke_rect(rgba, W * 4, W, H, x0, y0, x1, y1, stroke, r, g, b);
        draw_number_badge(rgba, W * 4, W, H,
                          x0, y0 - (FONT_H * label_scale + label_scale * 2 + 2),
                          i + 1, label_scale,
                          0, 0, 0,
                          255, 255, 255);
        char score_buf[32];
        int s10 = (int)lroundf(c->spatial_score * 10.0f);
        int t10 = (int)lroundf(c->temporal_score * 10.0f);
        int c10 = (int)lroundf(c->combined_score * 10.0f);
        snprintf(score_buf, sizeof(score_buf), "S%dT%dC%d", s10, t10, c10);
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + 2,
                        score_buf, label_scale,
                        0, 0, 0,
                        255, 255, 255);
        float center = 0.0f, mean9 = 0.0f, top3 = 0.0f;
        int near_count = 0;
        compute_pixel_support(raw_rgba, W * 4, W, H, cx, cy, black_hot,
                              &center, &mean9, &top3, &near_count);
        char support_buf[32];
        snprintf(support_buf, sizeof(support_buf), "N%dK%d", near_count, (int)lroundf(top3));
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + (FONT_H * label_scale + label_scale * 2 + 4),
                        support_buf, label_scale,
                        0, 0, 0,
                        255, 255, 255);
    }

    if (dbg->acc_post_active) {
        int cx = (int)lroundf(dbg->acc_post_x_norm * (float)(W - 1));
        int cy = (int)lroundf(dbg->acc_post_y_norm * (float)(H - 1));
        draw_crosshair(rgba, W * 4, W, H, cx, cy, cross_half, stroke + 1, 0x23, 0xC5, 0x52);
        draw_number_badge(rgba, W * 4, W, H,
                          6, H - (FONT_H * label_scale + label_scale * 2 + 10),
                          dbg->acc_post_hits, label_scale,
                          0, 0, 0,
                          0x23, 0xC5, 0x52);
    }

    int extreme_x = 0, extreme_y = 0;
    float extreme_luma = 0.0f;
    find_extreme_pixel(raw_rgba, W * 4, W, H, cfg, &extreme_x, &extreme_y, &extreme_luma);
    draw_crosshair(rgba, W * 4, W, H, extreme_x, extreme_y, cross_half / 2, stroke + 1, 0xF2, 0x30, 0x30);
    float extreme_mean9 = 0.0f, extreme_top3 = 0.0f;
    int extreme_near_count = 0;
    compute_pixel_support(raw_rgba, W * 4, W, H, extreme_x, extreme_y, black_hot,
                          NULL, &extreme_mean9, &extreme_top3, &extreme_near_count);
    char extreme_buf[32];
    snprintf(extreme_buf, sizeof(extreme_buf), "X%dN%d", (int)lroundf(extreme_luma), extreme_near_count);
    draw_text_badge(rgba, W * 4, W, H,
                    extreme_x + cross_half / 2 + 4, extreme_y,
                    extreme_buf, label_scale,
                    0, 0, 0,
                    0xF2, 0x30, 0x30);

    draw_number_badge(rgba, W * 4, W, H,
                      6 + (FONT_W * label_scale + label_scale) * 2,
                      H - (FONT_H * label_scale + label_scale * 2 + 10),
                      dbg->bg_ready ? 1 : 0, label_scale,
                      0, 0, 0,
                      255, 255, 255);
}

static void draw_motion_debug_overlay(uint8_t *rgba, int W, int H,
                                      const anomaly_result_t *result) {
    if (rgba == NULL || result == NULL) return;
    const anomaly_debug_motion_t *dbg = &result->motion_debug;
    if (!dbg->valid) return;

    int label_scale = (W >= 1280 || H >= 720) ? 2 : 1;
    int stroke = (W >= 1280 || H >= 720) ? 2 : 1;
    float box_side = 0.06f;
    int half_w = (int)lroundf(box_side * 0.5f * (float)(W - 1));
    int half_h = (int)lroundf(box_side * 0.5f * (float)(H - 1));

    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        if (!c->valid) continue;
        int cx = c->pixel_x;
        int cy = c->pixel_y;
        int x0 = cx - half_w;
        int y0 = cy - half_h;
        int x1 = cx + half_w;
        int y1 = cy + half_h;
        stroke_rect(rgba, W * 4, W, H, x0, y0, x1, y1, stroke, 0x23, 0xC5, 0x52);
        draw_number_badge(rgba, W * 4, W, H,
                          x0, y0 - (FONT_H * label_scale + label_scale * 2 + 2),
                          i + 1, label_scale,
                          0, 0, 0,
                          0x23, 0xC5, 0x52);
        char score_buf[32];
        int z10 = (int)lroundf(c->spatial_score * 10.0f);
        int c10 = (int)lroundf(c->combined_score * 10.0f);
        snprintf(score_buf, sizeof(score_buf), "Z%dC%d", z10, c10);
        draw_text_badge(rgba, W * 4, W, H,
                        x0, y1 + 2,
                        score_buf, label_scale,
                        0, 0, 0,
                        0x23, 0xC5, 0x52);
    }

    if (dbg->raw_candidate_valid) {
        int cx = (int)lroundf(dbg->raw_x_norm * (float)(W - 1));
        int cy = (int)lroundf(dbg->raw_y_norm * (float)(H - 1));
        draw_crosshair(rgba, W * 4, W, H, cx, cy, 8, stroke + 1, 0x23, 0xC5, 0x52);
    }

    char stats_buf[128];
    snprintf(stats_buf, sizeof(stats_buf), "M%d S%d U%d V%d Z%d B%d",
             (int)lroundf(dbg->residual_mean),
             (int)lroundf(dbg->residual_std),
             dbg->sample_step,
             dbg->motion_step,
             (int)lroundf(dbg->zoom_motion_scale * 100.0f),
             (int)lroundf(dbg->broad_motion_scale * 100.0f));
    draw_text_badge(rgba, W * 4, W, H,
                    6, 26,
                    stats_buf, label_scale,
                    0, 0, 0,
                    0x23, 0xC5, 0x52);
}

// ── helpers ────────────────────────────────────────────────────────────────

static const char *algo_label(int algo) {
    switch (algo) {
        case ANOMALY_ALGO_COLOR:   return "color";
        case ANOMALY_ALGO_THERMAL: return "thermal";
        case ANOMALY_ALGO_MOTION:  return "motion";
        case ANOMALY_ALGO_MOTION_TOLERANCE: return "motion_tolerance";
        case ANOMALY_ALGO_PERSIST: return "saliency";
        default:                   return "unknown";
    }
}

static double monotonic_seconds(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + ((double)ts.tv_nsec / 1000000000.0);
}

static const char *realtime_descriptor(double realtime_factor) {
    if (realtime_factor > 1.02) return "faster than realtime";
    if (realtime_factor < 0.98) return "slower than realtime";
    return "equal to realtime";
}

static void json_write_string(FILE *out, const char *text) {
    fputc('"', out);
    if (text != NULL) {
        for (const unsigned char *p = (const unsigned char *)text; *p != '\0'; p++) {
            switch (*p) {
                case '\\': fputs("\\\\", out); break;
                case '"':  fputs("\\\"", out); break;
                case '\n': fputs("\\n", out); break;
                case '\r': fputs("\\r", out); break;
                case '\t': fputs("\\t", out); break;
                default:
                    if (*p < 0x20) {
                        fprintf(out, "\\u%04x", (unsigned int)*p);
                    } else {
                        fputc((int)*p, out);
                    }
                    break;
            }
        }
    }
    fputc('"', out);
}

static void dump_saliency_debug(FILE *out, int frame_num, double time_s,
                                const uint8_t *raw_rgba, int W, int H,
                                const anomaly_config_t *cfg,
                                const anomaly_result_t *result) {
    if (out == NULL || raw_rgba == NULL || cfg == NULL || result == NULL) return;
    const anomaly_debug_saliency_t *dbg = &result->saliency_debug;
    int black_hot = (cfg->thermal_polarity == ANOMALY_THERMAL_BLACK_HOT);
    fprintf(out, "\nSaliency debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  bg_ready=%d raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f)\n",
            dbg->bg_ready ? 1 : 0,
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm);
    fprintf(out, "  tracked_score_pre=%.3f switch_suppressed=%d\n",
            (double)dbg->tracked_score_pre,
            dbg->switch_suppressed ? 1 : 0);
    fprintf(out, "  acc_pre:  active=%d hits=%d xy=(%.4f, %.4f)\n",
            dbg->acc_pre_active ? 1 : 0, dbg->acc_pre_hits,
            (double)dbg->acc_pre_x_norm, (double)dbg->acc_pre_y_norm);
    fprintf(out, "  acc_post: active=%d hits=%d xy=(%.4f, %.4f)\n",
            dbg->acc_post_active ? 1 : 0, dbg->acc_post_hits,
            (double)dbg->acc_post_x_norm, (double)dbg->acc_post_y_norm);
    fprintf(out, "  top candidates (%d):\n", dbg->top_candidate_count);
    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        float center = 0.0f, mean9 = 0.0f, top3 = 0.0f;
        int near_count = 0;
        compute_pixel_support(raw_rgba, W * 4, W, H, c->pixel_x, c->pixel_y, black_hot,
                              &center, &mean9, &top3, &near_count);
        fprintf(out, "    #%d px=(%d,%d) xy=(%.4f,%.4f) spatial=%.3f temporal=%.3f combined=%.3f\n",
                i + 1, c->pixel_x, c->pixel_y,
                (double)c->x_norm, (double)c->y_norm,
                (double)c->spatial_score,
                (double)c->temporal_score,
                (double)c->combined_score);
        fprintf(out, "       support: center=%.1f mean9=%.1f top3=%.1f near_count=%d\n",
                (double)center, (double)mean9, (double)top3, near_count);
    }
    if (result->box_count > 0) {
        fprintf(out, "  visible boxes:\n");
        for (int i = 0; i < result->box_count; i++) {
            const anomaly_box_t *b = &result->boxes[i];
            fprintf(out, "    box[%d] algo=%s center=(%.4f,%.4f) size=(%.4f,%.4f) weight=%.2f\n",
                    i, algo_label(b->algorithm),
                    (double)((b->left_norm + b->right_norm) * 0.5f),
                    (double)((b->top_norm + b->bottom_norm) * 0.5f),
                    (double)(b->right_norm - b->left_norm),
                    (double)(b->bottom_norm - b->top_norm),
                    (double)b->weight);
        }
    }
    fflush(out);
}

static void dump_motion_debug(FILE *out, int frame_num, double time_s,
                              const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_motion_t *dbg = &result->motion_debug;
    if (!dbg->valid) return;
    fprintf(out, "\nMotion debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  discontinuity=%d sample_step=%d motion_step=%d samples=%d\n",
            dbg->scene_discontinuity ? 1 : 0,
            dbg->sample_step,
            dbg->motion_step,
            dbg->sample_count);
    fprintf(out, "  residual_mean=%.3f residual_std=%.3f\n",
            (double)dbg->residual_mean,
            (double)dbg->residual_std);
    fprintf(out, "  zoom_scale=%.3f broad_scale=%.3f motion_load=%.3f winner(tex=%.2f str=%.2f sup=%.2f pers=%.2f)\n",
            (double)dbg->zoom_motion_scale,
            (double)dbg->broad_motion_scale,
            (double)dbg->global_motion_load,
            (double)dbg->winner_texture_scale,
            (double)dbg->winner_structure_scale,
            (double)dbg->winner_support_scale,
            (double)dbg->winner_persistence_scale);
    fprintf(out, "  raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f)\n",
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm);
    fprintf(out, "  top motion candidates (%d):\n", dbg->top_candidate_count);
    for (int i = 0; i < dbg->top_candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_candidate_t *c = &dbg->top_candidates[i];
        fprintf(out, "    #%d px=(%d,%d) xy=(%.4f,%.4f) z=%.3f combined=%.3f\n",
                i + 1, c->pixel_x, c->pixel_y,
                (double)c->x_norm, (double)c->y_norm,
                (double)c->spatial_score,
                (double)c->combined_score);
    }
}

static void dump_thermal_debug(FILE *out, int frame_num, double time_s,
                               const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_thermal_t *dbg = &result->thermal_debug;
    fprintf(out, "\nThermal debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  bg_ready=%d raw_valid=%d raw_score=%.3f raw_xy=(%.4f, %.4f) winner_idx=%d\n",
            dbg->bg_ready ? 1 : 0,
            dbg->raw_candidate_valid ? 1 : 0,
            (double)dbg->raw_score,
            (double)dbg->raw_x_norm,
            (double)dbg->raw_y_norm,
            dbg->winning_candidate_index);
    fprintf(out, "  thermal candidates (%d):\n", dbg->candidate_count);
    for (int i = 0; i < dbg->candidate_count && i < ANOMALY_DEBUG_TOP_CANDIDATES; i++) {
        const anomaly_debug_thermal_candidate_t *c = &dbg->candidates[i];
        fprintf(out,
                "    #%d px=(%d,%d) xy=(%.4f,%.4f) base=%.3f final=%.3f area=%.1f span=%.1f fill=%.2f center=%.2f quality=%.2f isolation=%.2f%s\n",
                i + 1,
                c->pixel_x,
                c->pixel_y,
                (double)c->x_norm,
                (double)c->y_norm,
                (double)c->base_score,
                (double)c->final_score,
                (double)c->area,
                (double)c->span,
                (double)c->fill,
                (double)c->center_share,
                (double)c->quality,
                (double)c->isolation_rank,
                (i == dbg->winning_candidate_index) ? "  <winner>" : "");
    }
}

static void dump_gmv_debug(FILE *out, int frame_num, double time_s,
                           const anomaly_result_t *result) {
    if (out == NULL || result == NULL) return;
    const anomaly_debug_gmv_t *dbg = &result->gmv_debug;
    if (!dbg->valid) return;
    fprintf(out, "\nGMV debug for frame %d (%.3fs)\n", frame_num, time_s);
    fprintf(out, "  discontinuity=%d sample_step=%d motion_step=%d anchors=%d\n",
            dbg->scene_discontinuity ? 1 : 0,
            dbg->sample_step,
            dbg->motion_step,
            dbg->anchor_count);
    fprintf(out, "  fit: a=%.5f b=%.5f tx=%.5f ty=%.5f scale=%.5f theta=%.2fdeg residual=%.5f\n",
            (double)dbg->fit_a,
            (double)dbg->fit_b,
            (double)dbg->fit_tx,
            (double)dbg->fit_ty,
            (double)dbg->fit_scale,
            (double)dbg->fit_theta_deg,
            (double)dbg->fit_mean_residual);
    for (int i = 0; i < dbg->anchor_count && i < ANOMALY_GMV_MAX_DEBUG_ANCHORS; i++) {
        const anomaly_debug_gmv_anchor_t *a = &dbg->anchors[i];
        fprintf(out,
                "    anchor[%d] zone=(%d,%d) px=(%d,%d) xy=(%.4f,%.4f) tex=%d match=(%d,%d) sad=%d second=%d\n",
                i,
                a->zone_gx,
                a->zone_gy,
                a->pixel_x,
                a->pixel_y,
                (double)a->x_norm,
                (double)a->y_norm,
                a->texture_score,
                a->match_dx,
                a->match_dy,
                a->best_sad,
                a->second_best_sad);
    }
}

// Remove extension and directory prefix to get a bare basename.
static void basename_noext(const char *path, char *out, size_t out_sz) {
    const char *slash = strrchr(path, '/');
    const char *name  = slash ? slash + 1 : path;
    const char *dot   = strrchr(name, '.');
    size_t len = dot ? (size_t)(dot - name) : strlen(name);
    if (len >= out_sz) len = out_sz - 1;
    memcpy(out, name, len);
    out[len] = '\0';
}

static void usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s <input.mp4> [options]\n"
        "\n"
        "Output files (written alongside the input by default):\n"
        "  -o <file.mp4>    Annotated video  (default: <input>_annotated.mp4)\n"
        "  -c <file.csv>    Detection log    (default: <input>_detections.csv)\n"
        "  --no-video       Skip annotated video (CSV only)\n"
        "\n"
        "Detector settings:\n"
        "  -t <float>       Score threshold  (default: %.1f)\n"
        "  -m <int>         Min hits to show box (default: %d)\n"
        "  -s <float>       Scan zone 0.5–1.0    (default: %.2f)\n"
        "  -a <int>         Algorithm mask 1=color 2=thermal 4=motion 8=saliency (default: 7)\n"
        "  -p <wh|bh>       Thermal polarity: wh=white-hot bh=black-hot (default: wh)\n"
        "  --registration <gmv|affine>\n"
        "                   Camera registration backend (default: gmv)\n"
        "  --stride <int>   Analyze every Nth frame (default: 1)\n"
        "  --min-delta <f>  Override ANOMALY_THERMAL_MIN_DELTA (default: %.1f)\n"
        "  --debug-frame N  Dump saliency candidate details for frame N\n"
        "  --debug-time-start S  Dump debug details beginning at time S seconds\n"
        "  --debug-time-end S    Dump debug details ending at time S seconds\n"
        "  --debug-overlay  Burn saliency candidate ranks / latch marker into video\n"
        "  --time-start S   Decode and score beginning at source time S seconds\n"
        "  --time-end S     Stop scoring at source time S seconds\n"
        "  --summary-json <file>\n"
        "                   Write a machine-readable run summary for reviewed\n"
        "                   clip scoring / regression reports\n"
        "\n"
        "Diagnostic:\n"
        "  --probe cx,cy    Sample the detector's scored point nearest the given\n"
        "                   normalised position\n"
        "                   every frame; write detector-side spatial/temporal scores\n"
        "                   to _probe.csv. Use this to tune threshold / min-delta.\n"
        "\n"
        "Tip for IR/thermal footage: add  -p bh -a 6  (thermal + motion, black-hot)\n",
        prog,
        (double)ANOMALY_DEFAULT_SCORE_THRESHOLD,
        ANOMALY_DEFAULT_MIN_HITS,
        (double)ANOMALY_SCAN_ZONE_DEFAULT,
        (double)ANOMALY_THERMAL_MIN_DELTA);
}

// ── main ───────────────────────────────────────────────────────────────────

int main(int argc, char **argv) {
    if (argc < 2 || !strcmp(argv[1], "-h") || !strcmp(argv[1], "--help")) {
        usage(argv[0]);
        return argc < 2 ? 1 : 0;
    }

    const char *input = argv[1];
    char output_video[1024] = "";
    char output_csv[1024]   = "";
    char output_summary_json[1024] = "";
    int  write_video = 1;

    anomaly_config_t cfg = {
        .enabled           = true,
        .algorithm_mask    = ANOMALY_ALGO_COLOR | ANOMALY_ALGO_THERMAL | ANOMALY_ALGO_MOTION,
        .registration_mode = ANOMALY_REGISTRATION_GMV,
        .frame_stride      = 1,
        .score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD,
        .motion_evidence_scale = 1.0f,
        .min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION,
        .thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT,
        .scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT,
        .min_hits          = ANOMALY_DEFAULT_MIN_HITS,
        .thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA,
    };

    // Probe state (--probe cx,cy).
    int    probe_active  = 0;
    float  probe_cx      = 0.0f, probe_cy = 0.0f;
    char   probe_csv_path[1024] = "";
    int    debug_frame = -1;
    double debug_time_start = -1.0;
    double debug_time_end = -1.0;
    double clip_time_start = 0.0;
    double clip_time_end = -1.0;
    int    debug_overlay = 0;
    // min-delta override (0 = use compiled-in constant).
    float  min_delta_override = 0.0f;

    for (int i = 2; i < argc; i++) {
        if      (!strcmp(argv[i], "-o")          && i+1 < argc) snprintf(output_video, sizeof(output_video), "%s", argv[++i]);
        else if (!strcmp(argv[i], "-c")          && i+1 < argc) snprintf(output_csv,   sizeof(output_csv),   "%s", argv[++i]);
        else if (!strcmp(argv[i], "-t")          && i+1 < argc) cfg.score_threshold   = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-m")          && i+1 < argc) cfg.min_hits          = atoi(argv[++i]);
        else if (!strcmp(argv[i], "-s")          && i+1 < argc) cfg.scan_zone         = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-a")          && i+1 < argc) cfg.algorithm_mask    = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--registration") && i+1 < argc) {
            const char *mode = argv[++i];
            cfg.registration_mode = strcmp(mode, "affine") == 0
                                   ? ANOMALY_REGISTRATION_AFFINE
                                   : ANOMALY_REGISTRATION_GMV;
        }
        else if (!strcmp(argv[i], "--stride")    && i+1 < argc) cfg.frame_stride      = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--min-delta") && i+1 < argc) min_delta_override    = (float)atof(argv[++i]);
        else if (!strcmp(argv[i], "-p")          && i+1 < argc) {
            cfg.thermal_polarity = strcmp(argv[++i], "bh") == 0
                                   ? ANOMALY_THERMAL_BLACK_HOT : ANOMALY_THERMAL_WHITE_HOT;
        }
        else if (!strcmp(argv[i], "--probe")     && i+1 < argc) {
            if (sscanf(argv[++i], "%f,%f", &probe_cx, &probe_cy) == 2) {
                probe_active = 1;
            } else {
                fprintf(stderr, "Error: --probe expects cx,cy (e.g. --probe 0.42,0.28)\n");
                return 1;
            }
        }
        else if (!strcmp(argv[i], "--debug-frame") && i+1 < argc) {
            debug_frame = atoi(argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-time-start") && i+1 < argc) {
            debug_time_start = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-time-end") && i+1 < argc) {
            debug_time_end = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--time-start") && i+1 < argc) {
            clip_time_start = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--time-end") && i+1 < argc) {
            clip_time_end = atof(argv[++i]);
        }
        else if (!strcmp(argv[i], "--summary-json") && i+1 < argc) {
            snprintf(output_summary_json, sizeof(output_summary_json), "%s", argv[++i]);
        }
        else if (!strcmp(argv[i], "--debug-overlay")) {
            debug_overlay = 1;
        }
        else if (!strcmp(argv[i], "--no-video")) write_video = 0;
        else { fprintf(stderr, "Unknown option: %s\n\n", argv[i]); usage(argv[0]); return 1; }
    }

    // ── Auto-detect dimensions and fps with ffprobe ──────────────────────
    int    W = 0, H = 0;
    double fps = 30.0, full_duration_s = 0.0;
    {
        char cmd[2048];
        snprintf(cmd, sizeof(cmd),
            "ffprobe -v error -select_streams v:0 "
            "-show_entries stream=width,height,r_frame_rate,duration "
            "-of csv=p=0 \"%s\" 2>/dev/null", input);
        FILE *p = popen(cmd, "r");
        if (!p) { fprintf(stderr, "Error: ffprobe failed — is ffprobe on PATH?\n"); return 1; }
        int fps_n = 30, fps_d = 1;
        fscanf(p, "%d,%d,%d/%d,%lf", &W, &H, &fps_n, &fps_d, &full_duration_s);
        pclose(p);
        if (fps_d > 0) fps = (double)fps_n / (double)fps_d;
    }
    if (W <= 0 || H <= 0) {
        fprintf(stderr, "Error: could not read video info from \"%s\"\n", input);
        return 1;
    }
    if (clip_time_start < 0.0) clip_time_start = 0.0;
    if (full_duration_s > 0.0) {
        clip_time_start = clamp_double(clip_time_start, 0.0, full_duration_s);
    }
    if (clip_time_end >= 0.0 && clip_time_end < clip_time_start) {
        fprintf(stderr, "Error: --time-end must be >= --time-start\n");
        return 1;
    }
    double requested_media_end = clip_time_end;
    if (requested_media_end < 0.0) {
        requested_media_end = full_duration_s;
    } else if (full_duration_s > 0.0 && requested_media_end > full_duration_s) {
        requested_media_end = full_duration_s;
    }
    double clip_duration_s = 0.0;
    if (requested_media_end > clip_time_start) {
        clip_duration_s = requested_media_end - clip_time_start;
    } else if (full_duration_s > 0.0 && clip_time_end < 0.0) {
        clip_duration_s = full_duration_s - clip_time_start;
    }
    int total_frames = (clip_duration_s > 0.0) ? (int)(clip_duration_s * fps + 0.5) : 0;

    // ── Build default output paths in the same directory as the input ────
    char dir_prefix[512] = "";
    {
        const char *slash = strrchr(input, '/');
        if (slash) {
            size_t dlen = (size_t)(slash - input) + 1;
            if (dlen >= sizeof(dir_prefix)) dlen = sizeof(dir_prefix) - 1;
            memcpy(dir_prefix, input, dlen);
            dir_prefix[dlen] = '\0';
        }
    }
    char base[256];
    basename_noext(input, base, sizeof(base));

    if (!output_video[0])
        snprintf(output_video, sizeof(output_video), "%s%s_annotated.mp4", dir_prefix, base);
    if (!output_csv[0])
        snprintf(output_csv, sizeof(output_csv), "%s%s_detections.csv", dir_prefix, base);
    if (probe_active && !probe_csv_path[0])
        snprintf(probe_csv_path, sizeof(probe_csv_path), "%s%s_probe.csv", dir_prefix, base);

    // Effective min-delta (CLI override or compiled-in constant).
    double effective_min_delta = (min_delta_override > 0.0f)
                                 ? (double)min_delta_override
                                 : (double)ANOMALY_THERMAL_MIN_DELTA;
    cfg.thermal_min_delta = (float)effective_min_delta;

    // ── Print run summary ────────────────────────────────────────────────
    fprintf(stderr, "\n");
    fprintf(stderr, "Input      : %s\n", input);
    fprintf(stderr, "Dimensions : %dx%d @ %.2f fps", W, H, fps);
    if (total_frames > 0) fprintf(stderr, "  (~%d frames, %.1fs)", total_frames, clip_duration_s);
    fprintf(stderr, "\n");
    fprintf(stderr, "CSV        : %s\n", output_csv);
    if (write_video) fprintf(stderr, "Video      : %s\n", output_video);
    if (output_summary_json[0]) fprintf(stderr, "Summary    : %s\n", output_summary_json);
    if (probe_active) fprintf(stderr, "Probe CSV  : %s  (cx=%.3f cy=%.3f)\n",
                              probe_csv_path, (double)probe_cx, (double)probe_cy);
    if (clip_time_start > 0.0 || clip_time_end >= 0.0) {
        if (clip_time_end >= 0.0) {
            fprintf(stderr, "Clip range : %.3fs to %.3fs\n", clip_time_start, requested_media_end);
        } else {
            fprintf(stderr, "Clip range : %.3fs to end\n", clip_time_start);
        }
    }
    fprintf(stderr, "\n");
    fprintf(stderr, "Detector settings:\n");
    fprintf(stderr, "  threshold  = %.2f\n", (double)cfg.score_threshold);
    fprintf(stderr, "  min_hits   = %d\n",   cfg.min_hits);
    fprintf(stderr, "  scan_zone  = %.2f\n", (double)cfg.scan_zone);
    fprintf(stderr, "  algorithm  = %d (%s%s%s)\n", cfg.algorithm_mask,
        (cfg.algorithm_mask & ANOMALY_ALGO_COLOR)   ? "color "   : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_THERMAL) ? "thermal " : "",
        (cfg.algorithm_mask & ANOMALY_ALGO_MOTION)  ? "motion "  : "");
    if (cfg.algorithm_mask & ANOMALY_ALGO_MOTION_TOLERANCE)
        fprintf(stderr, "               %s\n", "motion_tolerance");
    if (cfg.algorithm_mask & ANOMALY_ALGO_PERSIST)
        fprintf(stderr, "               %s\n", "saliency");
    fprintf(stderr, "  polarity   = %s\n",
        cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "black-hot" : "white-hot");
    fprintf(stderr, "  register   = %s\n",
        cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv");
    fprintf(stderr, "  stride     = %d\n",   cfg.frame_stride);
    fprintf(stderr, "  min_delta  = %.1f%s\n", effective_min_delta,
            min_delta_override > 0.0f ? " (override)" : "");
    fprintf(stderr, "\n");

    // ── Allocate frame buffer ────────────────────────────────────────────
    size_t frame_bytes = (size_t)W * H * 4;
    uint8_t *rgba = malloc(frame_bytes);
    int debug_window_active = (debug_time_start >= 0.0 || debug_time_end >= 0.0);
    uint8_t *raw_rgba = (debug_overlay || debug_frame > 0 || debug_window_active) ? malloc(frame_bytes) : NULL;
    if (!rgba) { fprintf(stderr, "Out of memory\n"); return 1; }
    if ((debug_overlay || debug_frame > 0 || debug_window_active) && !raw_rgba) {
        fprintf(stderr, "Out of memory\n");
        free(rgba);
        return 1;
    }

    // ── Open CSV ─────────────────────────────────────────────────────────
    FILE *csv = fopen(output_csv, "w");
    if (!csv) { fprintf(stderr, "Cannot write %s\n", output_csv); return 1; }

    // Header lines document the settings so the file is self-contained.
    fprintf(csv, "# input: %s\n", input);
    fprintf(csv, "# threshold: %.2f  min_hits: %d  scan_zone: %.2f  "
                 "algo: %d  polarity: %s  stride: %d  registration: %s\n",
            (double)cfg.score_threshold, cfg.min_hits, (double)cfg.scan_zone,
            cfg.algorithm_mask,
            cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "bh" : "wh",
            cfg.frame_stride,
            cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv");
    if (clip_time_end >= 0.0 || clip_duration_s > 0.0) {
        fprintf(csv, "# clip_start_s: %.3f  clip_end_s: %.3f\n",
                clip_time_start,
                clip_time_end >= 0.0 ? requested_media_end : (clip_time_start + clip_duration_s));
    } else {
        fprintf(csv, "# clip_start_s: %.3f  clip_end_s: end\n", clip_time_start);
    }
    fprintf(csv, "# label column: G=good/true-positive  "
                 "B=bad/false-positive  ?=unsure  (leave blank to skip)\n");
    fprintf(csv, "frame,time_s,algorithm,cx_norm,cy_norm,"
                 "box_w_norm,box_h_norm,weight,label\n");

    // ── Open FFmpeg input pipe ───────────────────────────────────────────
    char in_cmd[2048];
    if (clip_duration_s > 0.0) {
        snprintf(in_cmd, sizeof(in_cmd),
            "ffmpeg -ss %.6f -i \"%s\" -t %.6f -pix_fmt rgba -f rawvideo pipe:1 2>/dev/null",
            clip_time_start, input, clip_duration_s);
    } else {
        snprintf(in_cmd, sizeof(in_cmd),
            "ffmpeg -ss %.6f -i \"%s\" -pix_fmt rgba -f rawvideo pipe:1 2>/dev/null",
            clip_time_start, input);
    }
    FILE *in_pipe = popen(in_cmd, "r");
    if (!in_pipe) { fprintf(stderr, "Error: ffmpeg decode pipe failed\n"); return 1; }

    // ── Open FFmpeg output pipe (annotated video) ────────────────────────
    FILE *out_pipe = NULL;
    if (write_video) {
        char out_cmd[2048];
        snprintf(out_cmd, sizeof(out_cmd),
            "ffmpeg -f rawvideo -pix_fmt rgba -video_size %dx%d -framerate %.4f "
            "-i pipe:0 -vf format=yuv420p -c:v libx264 -crf 23 -movflags +faststart "
            "-y \"%s\" 2>/dev/null",
            W, H, fps, output_video);
        out_pipe = popen(out_cmd, "w");
        if (!out_pipe) { fprintf(stderr, "Error: ffmpeg encode pipe failed\n"); return 1; }
    }

    // ── Open probe CSV (if requested) ────────────────────────────────────
    FILE *probe_csv = NULL;
    if (probe_active) {
        probe_csv = fopen(probe_csv_path, "w");
        if (!probe_csv) {
            fprintf(stderr, "Cannot write probe CSV: %s\n", probe_csv_path);
            return 1;
        }
        fprintf(probe_csv, "# probe location: cx=%.4f cy=%.4f  polarity=%s\n",
                (double)probe_cx, (double)probe_cy,
                cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "black-hot" : "white-hot");
        fprintf(probe_csv, "# effective_score matches anomaly_analysis.c exactly:\n");
        fprintf(probe_csv, "#   warmup  -> spatial_abs_delta / spatial_std\n");
        fprintf(probe_csv, "#   bg_ready -> (temporal_delta - temporal_mean) / temporal_norm\n");
        fprintf(probe_csv, "# threshold=%.2f  min_delta=%.1f\n",
                (double)cfg.score_threshold, effective_min_delta);
        fprintf(probe_csv,
                "frame,time_s,px,py,inside_scan_zone,bg_ready,used_temporal,"
                "sample_luma,spatial_mean,spatial_std,spatial_abs_delta,spatial_score,"
                "temporal_delta,temporal_mean,temporal_norm,temporal_score,"
                "effective_score,passes_min_delta,passes_threshold\n");
    }

    // ── Process frames ───────────────────────────────────────────────────
    anomaly_state_t state;
    anomaly_state_init(&state);

    int    frame_num        = 0;
    int    detection_frames = 0;
    int    total_boxes      = 0;
    double wall_start_s     = monotonic_seconds();

    while (fread(rgba, 1, frame_bytes, in_pipe) == frame_bytes) {
        frame_num++;
        double local_time_s = (double)(frame_num - 1) / fps;
        double time_s = clip_time_start + local_time_s;
        if (raw_rgba) memcpy(raw_rgba, rgba, frame_bytes);

        // ── Per-frame probe ──────────────────────────────────────────────
        if (probe_csv) {
            anomaly_probe_t probe;
            int ok = anomaly_probe_thermal_point(&state, &cfg, rgba, W * 4, W, H,
                                                 probe_cx, probe_cy, &probe);
            int passes_delta = ok && probe.valid && probe.inside_scan_zone
                               && ((probe.used_temporal_score ? probe.temporal_delta : probe.spatial_abs_delta)
                                   >= (float)effective_min_delta);
            int passes_threshold = ok && probe.valid && probe.inside_scan_zone
                                   && (probe.effective_score >= cfg.score_threshold);
            fprintf(probe_csv,
                    "%d,%.3f,%d,%d,%d,%d,%d,%.1f,%.1f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%d,%d\n",
                    frame_num, time_s, probe.pixel_x, probe.pixel_y,
                    probe.inside_scan_zone ? 1 : 0,
                    probe.bg_ready ? 1 : 0,
                    probe.used_temporal_score ? 1 : 0,
                    probe.sample_luma,
                    probe.spatial_mean,
                    probe.spatial_std,
                    probe.spatial_abs_delta,
                    probe.spatial_score,
                    probe.temporal_delta,
                    probe.temporal_mean,
                    probe.temporal_norm,
                    probe.temporal_score,
                    probe.effective_score,
                    passes_delta, passes_threshold);
        }

        anomaly_result_t result;
        anomaly_process_frame(&state, &cfg, rgba, W * 4, W, H, 0, &result);
        int debug_this_frame = (debug_frame > 0 && frame_num == debug_frame);
        if (debug_window_active) {
            int at_or_after_start = (debug_time_start < 0.0) || (time_s >= debug_time_start);
            int at_or_before_end = (debug_time_end < 0.0) || (time_s <= debug_time_end);
            if (at_or_after_start && at_or_before_end) debug_this_frame = 1;
        }
        if (debug_this_frame) {
            dump_gmv_debug(stderr, frame_num, time_s, &result);
            dump_thermal_debug(stderr, frame_num, time_s, &result);
            dump_saliency_debug(stderr, frame_num, time_s, raw_rgba, W, H, &cfg, &result);
            dump_motion_debug(stderr, frame_num, time_s, &result);
        }

        if (result.box_count > 0) {
            detection_frames++;
            for (int i = 0; i < result.box_count; i++) {
                const anomaly_box_t *b = &result.boxes[i];
                float cx = (b->left_norm  + b->right_norm)  * 0.5f;
                float cy = (b->top_norm   + b->bottom_norm) * 0.5f;
                float bw = b->right_norm  - b->left_norm;
                float bh = b->bottom_norm - b->top_norm;
                fprintf(csv, "%d,%.3f,%s,%.4f,%.4f,%.4f,%.4f,%.2f,\n",
                        frame_num, time_s, algo_label(b->algorithm),
                        (double)cx, (double)cy, (double)bw, (double)bh,
                        (double)b->weight);
                total_boxes++;
            }
        }

        // Progress line: overwrite in place.
        if (frame_num % 30 == 0 || frame_num == 1) {
            int elapsed = (int)(monotonic_seconds() - wall_start_s);
            if (total_frames > 0) {
                int eta = (frame_num > 0 && elapsed > 0)
                          ? (int)((double)elapsed / frame_num * (total_frames - frame_num))
                          : 0;
                fprintf(stderr, "\r  frame %5d / %d  (%.1fs)  "
                                "detections: %d frames  ETA: %ds     ",
                        frame_num, total_frames, time_s, detection_frames, eta);
            } else {
                fprintf(stderr, "\r  frame %5d  (%.1fs)  detections: %d frames   ",
                        frame_num, time_s, detection_frames);
            }
            fflush(stderr);
        }

        if (out_pipe) {
            if (debug_overlay && (cfg.algorithm_mask & ANOMALY_ALGO_PERSIST)) {
                draw_saliency_debug_overlay(rgba, W, H, raw_rgba, &cfg, &result);
            }
            if (debug_overlay && (cfg.algorithm_mask & ANOMALY_ALGO_MOTION)) {
                draw_motion_debug_overlay(rgba, W, H, &result);
            }
            draw_frame_number(rgba, W, H, frame_num);
            fwrite(rgba, 1, frame_bytes, out_pipe);
        }
    }
    fprintf(stderr, "\n\n");

    // ── Tear down ────────────────────────────────────────────────────────
    pclose(in_pipe);
    if (out_pipe) pclose(out_pipe);
    fclose(csv);
    if (probe_csv) fclose(probe_csv);
    anomaly_state_cleanup(&state);
    free(rgba);
    free(raw_rgba);

    // ── Summary ──────────────────────────────────────────────────────────
    double analysis_wall_s = monotonic_seconds() - wall_start_s;
    if (analysis_wall_s < 0.0) analysis_wall_s = 0.0;
    double media_span_s = (fps > 0.0 ? (double)frame_num / fps : 0.0);
    if (clip_duration_s > 0.0) {
        media_span_s = clip_duration_s;
    }
    double realtime_factor = (analysis_wall_s > 0.0 && media_span_s > 0.0)
                             ? (media_span_s / analysis_wall_s)
                             : 0.0;
    fprintf(stderr, "Done in %.1fs.\n", analysis_wall_s);
    if (media_span_s > 0.0 && analysis_wall_s > 0.0) {
        fprintf(stderr, "  Input duration   : %.1f s\n", media_span_s);
        fprintf(stderr, "  Analysis wall    : %.1f s\n", analysis_wall_s);
        fprintf(stderr, "  Realtime factor  : %.2fx realtime (%s)\n",
                realtime_factor, realtime_descriptor(realtime_factor));
    }
    fprintf(stderr, "  Frames processed : %d\n", frame_num);
    fprintf(stderr, "  Frames with boxes: %d (%.1f%%)\n",
            detection_frames,
            frame_num > 0 ? 100.0 * detection_frames / frame_num : 0.0);
    fprintf(stderr, "  Total box events : %d\n", total_boxes);
    fprintf(stderr, "  CSV written      : %s\n", output_csv);
    if (write_video)
        fprintf(stderr, "  Video written    : %s\n", output_video);
    if (probe_active)
        fprintf(stderr, "  Probe CSV        : %s\n", probe_csv_path);
    fprintf(stderr, "\n");
    if (output_summary_json[0]) {
        FILE *summary = fopen(output_summary_json, "w");
        if (!summary) {
            fprintf(stderr, "Warning: could not write summary JSON: %s\n", output_summary_json);
        } else {
            fprintf(summary, "{\n");
            fprintf(summary, "  \"input\": ");
            json_write_string(summary, input);
            fprintf(summary, ",\n  \"csv\": ");
            json_write_string(summary, output_csv);
            fprintf(summary, ",\n  \"video\": ");
            if (write_video) {
                json_write_string(summary, output_video);
            } else {
                fprintf(summary, "null");
            }
            fprintf(summary, ",\n  \"clip_start_s\": %.6f,\n", clip_time_start);
            if (clip_time_end >= 0.0) {
                fprintf(summary, "  \"clip_end_s\": %.6f,\n", requested_media_end);
            } else {
                fprintf(summary, "  \"clip_end_s\": null,\n");
            }
            fprintf(summary, "  \"fps\": %.6f,\n", fps);
            fprintf(summary, "  \"frame_count\": %d,\n", frame_num);
            fprintf(summary, "  \"detection_frames\": %d,\n", detection_frames);
            fprintf(summary, "  \"total_boxes\": %d,\n", total_boxes);
            fprintf(summary, "  \"analysis_wall_s\": %.6f,\n", analysis_wall_s);
            fprintf(summary, "  \"media_span_s\": %.6f,\n", media_span_s);
            fprintf(summary, "  \"realtime_factor\": %.6f,\n", realtime_factor);
            fprintf(summary, "  \"realtime_label\": ");
            json_write_string(summary, realtime_descriptor(realtime_factor));
            fprintf(summary, ",\n  \"config\": {\n");
            fprintf(summary, "    \"threshold\": %.6f,\n", (double)cfg.score_threshold);
            fprintf(summary, "    \"min_hits\": %d,\n", cfg.min_hits);
            fprintf(summary, "    \"scan_zone\": %.6f,\n", (double)cfg.scan_zone);
            fprintf(summary, "    \"algorithm_mask\": %d,\n", cfg.algorithm_mask);
            fprintf(summary, "    \"polarity\": ");
            json_write_string(summary,
                              cfg.thermal_polarity == ANOMALY_THERMAL_BLACK_HOT ? "bh" : "wh");
            fprintf(summary, ",\n    \"stride\": %d,\n", cfg.frame_stride);
            fprintf(summary, "    \"registration\": ");
            json_write_string(summary,
                              cfg.registration_mode == ANOMALY_REGISTRATION_AFFINE ? "affine" : "gmv");
            fprintf(summary, ",\n    \"thermal_min_delta\": %.6f\n", effective_min_delta);
            fprintf(summary, "  }\n}\n");
            fclose(summary);
        }
    }
    if (probe_active) {
        fprintf(stderr, "Probe interpretation:\n");
        fprintf(stderr, "  spatial_* rows describe the sampled-window score used during\n");
        fprintf(stderr, "    thermal background warmup.\n");
        fprintf(stderr, "  temporal_* rows describe the background-delta score used once\n");
        fprintf(stderr, "    bg_ready=1.\n");
        fprintf(stderr, "  effective_score is the exact value compared against threshold.\n");
        fprintf(stderr, "  If abs_delta/delta is small (< %.0f) but effective_score is large,\n",
                effective_min_delta);
        fprintf(stderr, "    investigate a noisy uniform region or a high frame delta mean.\n");
        fprintf(stderr, "  If effective_score > %.1f with min_delta > %.0f, the detector should\n",
                (double)cfg.score_threshold, effective_min_delta);
        fprintf(stderr, "    fire, assuming the probe location is inside the scan zone (%.0f%%).\n\n",
                100.0 * cfg.scan_zone);
    } else {
        fprintf(stderr, "Next step: open the CSV in Numbers/Excel and watch the\n");
        fprintf(stderr, "annotated video alongside it.  For each row, fill in the\n");
        fprintf(stderr, "label column:  G = good detection,  B = false positive,\n");
        fprintf(stderr, "               ? = unsure.\n\n");
    }

    return 0;
}

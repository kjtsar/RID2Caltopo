#include "anomaly_target_color_detector.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

static float target_color_clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static int target_color_max3(int a, int b, int c) {
    int m = a > b ? a : b;
    return m > c ? m : c;
}

static int target_color_min3(int a, int b, int c) {
    int m = a < b ? a : b;
    return m < c ? m : c;
}

static int target_color_family_slot(uint32_t family) {
    switch (family & ANOMALY_TARGET_COLOR_ALL) {
        case ANOMALY_TARGET_COLOR_RED: return 0;
        case ANOMALY_TARGET_COLOR_BLUE: return 1;
        case ANOMALY_TARGET_COLOR_YELLOW_ORANGE: return 2;
        case ANOMALY_TARGET_COLOR_GREEN: return 3;
        case ANOMALY_TARGET_COLOR_BLACK: return 4;
        case ANOMALY_TARGET_COLOR_WHITE: return 5;
        case ANOMALY_TARGET_COLOR_SKIN: return 6;
        default: return -1;
    }
}

void anomaly_target_color_scratch_init(anomaly_target_color_scratch_t *scratch) {
    if (scratch == NULL) return;
    memset(scratch, 0, sizeof(*scratch));
}

void anomaly_target_color_scratch_cleanup(anomaly_target_color_scratch_t *scratch) {
    if (scratch == NULL) return;
    free(scratch->family_mask_grid);
    free(scratch->visited_grid);
    free(scratch->queue);
    memset(scratch, 0, sizeof(*scratch));
}

void anomaly_target_color_result_init(anomaly_target_color_result_t *result) {
    if (result == NULL) return;
    memset(result, 0, sizeof(*result));
}

uint32_t anomaly_target_color_classify_rgb(uint8_t r8, uint8_t g8, uint8_t b8) {
    int r = (int)r8;
    int g = (int)g8;
    int b = (int)b8;
    int max_c = target_color_max3(r, g, b);
    int min_c = target_color_min3(r, g, b);
    int chroma = max_c - min_c;
    float luma = (0.2126f * (float)r) + (0.7152f * (float)g) + (0.0722f * (float)b);

    if (max_c <= 48 && chroma <= 34) return ANOMALY_TARGET_COLOR_BLACK;
    if (min_c >= 210 && chroma <= 48) return ANOMALY_TARGET_COLOR_WHITE;
    if (r >= 120 && g >= 70 && b >= 45 &&
        r > g + 28 && r > b + 48 && g >= b - 8 && g <= r - 18 &&
        luma >= 95.0f && luma <= 205.0f) {
        return ANOMALY_TARGET_COLOR_SKIN;
    }
    if (r >= 150 && g >= 95 && b <= 120 && r >= g - 15 && chroma >= 70) {
        return ANOMALY_TARGET_COLOR_YELLOW_ORANGE;
    }
    if (r >= 145 && r > g + 55 && r > b + 45 && g <= 135 && chroma >= 70) {
        return ANOMALY_TARGET_COLOR_RED;
    }
    if (b >= 120 && b > r + 45 && b > g + 35 && chroma >= 65) {
        return ANOMALY_TARGET_COLOR_BLUE;
    }
    if (g >= 105 && g > r + 35 && g > b + 30 && chroma >= 55) {
        return ANOMALY_TARGET_COLOR_GREEN;
    }
    return ANOMALY_TARGET_COLOR_NONE;
}

int anomaly_target_color_count_families(uint32_t family_mask) {
    uint32_t mask = family_mask & ANOMALY_TARGET_COLOR_ALL;
    int count = 0;
    while (mask != 0u) {
        count += (int)(mask & 1u);
        mask >>= 1u;
    }
    return count;
}

anomaly_target_color_component_score_t anomaly_target_color_score_component(
        uint32_t family_mask,
        int support_count,
        int bbox_area_cells,
        float density,
        float compactness,
        float local_contrast) {
    anomaly_target_color_component_score_t score = {0};
    score.family_mask = family_mask & ANOMALY_TARGET_COLOR_ALL;
    score.distinct_family_count = anomaly_target_color_count_families(score.family_mask);
    score.support_count = support_count;
    if (score.family_mask == 0u || support_count <= 0 || bbox_area_cells <= 0) {
        return score;
    }

    float density_clamped = target_color_clampf(density, 0.0f, 1.0f);
    float compact_clamped = target_color_clampf(compactness, 0.0f, 1.0f);
    float contrast_bonus = target_color_clampf(local_contrast / 80.0f, 0.0f, 0.55f);
    float support_bonus = target_color_clampf((float)(support_count - 1) / 16.0f, 0.0f, 0.38f);
    float size_penalty = bbox_area_cells > 220 ? target_color_clampf((float)(bbox_area_cells - 220) / 240.0f, 0.0f, 0.80f) : 0.0f;

    score.score =
        (1.05f * (float)score.distinct_family_count) +
        (0.55f * density_clamped) +
        (0.35f * compact_clamped) +
        contrast_bonus +
        support_bonus -
        size_penalty;
    score.score = target_color_clampf(score.score, 0.0f, 4.25f);
    score.confidence = target_color_clampf(score.score / 4.25f, 0.0f, 1.0f);

    float threshold = score.distinct_family_count >= 3 ? 2.70f :
                      score.distinct_family_count == 2 ? 2.35f :
                      2.20f;
    score.accepted =
        support_count >= 3 &&
        density_clamped >= 0.20f &&
        compact_clamped >= 0.30f &&
        local_contrast >= 12.0f &&
        score.score >= threshold;
    return score;
}

static bool target_color_scratch_ensure(
        anomaly_target_color_scratch_t *scratch,
        size_t cell_count) {
    if (scratch == NULL) return false;
    if (cell_count == 0u) return true;
    if (scratch->cell_capacity < cell_count) {
        uint8_t *family = (uint8_t *)realloc(scratch->family_mask_grid, cell_count);
        if (family == NULL) return false;
        scratch->family_mask_grid = family;
        uint8_t *visited = (uint8_t *)realloc(scratch->visited_grid, cell_count);
        if (visited == NULL) return false;
        scratch->visited_grid = visited;
        scratch->cell_capacity = cell_count;
    }
    if (scratch->queue_capacity < cell_count) {
        int *queue = (int *)realloc(scratch->queue, cell_count * sizeof(*queue));
        if (queue == NULL) return false;
        scratch->queue = queue;
        scratch->queue_capacity = cell_count;
    }
    return true;
}

static void target_color_insert_roi(
        anomaly_target_color_result_t *result,
        const anomaly_target_color_roi_t *roi) {
    if (result == NULL || roi == NULL) return;
    int count = result->roi_count;
    if (count < ANOMALY_TARGET_COLOR_MAX_ROIS) {
        result->rois[count] = *roi;
        result->roi_count = count + 1;
        return;
    }
    int weakest = 0;
    for (int i = 1; i < ANOMALY_TARGET_COLOR_MAX_ROIS; i++) {
        if (result->rois[i].score < result->rois[weakest].score) weakest = i;
    }
    if (roi->score > result->rois[weakest].score) result->rois[weakest] = *roi;
}

bool anomaly_target_color_detect_rgba(
        const uint8_t                  *rgba,
        int                             rgba_stride,
        int                             frame_width,
        int                             frame_height,
        uint32_t                        selected_family_mask,
        anomaly_target_color_scratch_t *scratch,
        anomaly_target_color_result_t  *result) {
    if (result != NULL) anomaly_target_color_result_init(result);
    if (result != NULL) result->selected_family_mask = selected_family_mask & ANOMALY_TARGET_COLOR_ALL;
    uint32_t selected = selected_family_mask & ANOMALY_TARGET_COLOR_ALL;
    if (selected == 0u) return true;
    if (rgba == NULL || scratch == NULL || result == NULL ||
        rgba_stride <= 0 || frame_width <= 0 || frame_height <= 0) {
        return false;
    }

    int sample_step = (frame_width >= 1280 || frame_height >= 720) ? 4 : 2;
    int grid_w = (frame_width + sample_step - 1) / sample_step;
    int grid_h = (frame_height + sample_step - 1) / sample_step;
    size_t cell_count = (size_t)grid_w * (size_t)grid_h;
    if (!target_color_scratch_ensure(scratch, cell_count)) return false;
    memset(scratch->family_mask_grid, 0, cell_count);
    memset(scratch->visited_grid, 0, cell_count);

    enum { TARGET_COLOR_FAMILY_SLOTS = 7 };
    int family_counts[TARGET_COLOR_FAMILY_SLOTS] = {0};
    double family_r_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_g_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_b_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_luma_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_chroma_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_r_sq_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_g_sq_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_b_sq_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_luma_sq_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};
    double family_chroma_sq_sum[TARGET_COLOR_FAMILY_SLOTS] = {0.0};

    double frame_luma_sum = 0.0;
    for (int gy = 0; gy < grid_h; gy++) {
        int py = gy * sample_step;
        if (py >= frame_height) py = frame_height - 1;
        for (int gx = 0; gx < grid_w; gx++) {
            int px = gx * sample_step;
            if (px >= frame_width) px = frame_width - 1;
            const uint8_t *p = rgba + (size_t)py * (size_t)rgba_stride + (size_t)px * 4u;
            float luma = (0.2126f * (float)p[0]) + (0.7152f * (float)p[1]) + (0.0722f * (float)p[2]);
            frame_luma_sum += (double)luma;
            uint32_t family = anomaly_target_color_classify_rgb(p[0], p[1], p[2]) & selected;
            result->sampled_pixels++;
            if (family != 0u) {
                scratch->family_mask_grid[(size_t)gy * (size_t)grid_w + (size_t)gx] = (uint8_t)family;
                result->selected_pixel_count++;
                int slot = target_color_family_slot(family);
                if (slot >= 0) {
                    int max_c = target_color_max3((int)p[0], (int)p[1], (int)p[2]);
                    int min_c = target_color_min3((int)p[0], (int)p[1], (int)p[2]);
                    float chroma = (float)(max_c - min_c);
                    family_counts[slot]++;
                    family_r_sum[slot] += (double)p[0];
                    family_g_sum[slot] += (double)p[1];
                    family_b_sum[slot] += (double)p[2];
                    family_luma_sum[slot] += (double)luma;
                    family_chroma_sum[slot] += (double)chroma;
                    family_r_sq_sum[slot] += (double)p[0] * (double)p[0];
                    family_g_sq_sum[slot] += (double)p[1] * (double)p[1];
                    family_b_sq_sum[slot] += (double)p[2] * (double)p[2];
                    family_luma_sq_sum[slot] += (double)luma * (double)luma;
                    family_chroma_sq_sum[slot] += (double)chroma * (double)chroma;
                }
            }
        }
    }
    float frame_luma_mean = result->sampled_pixels > 0
        ? (float)(frame_luma_sum / (double)result->sampled_pixels)
        : 0.0f;
    bool family_pervasive[TARGET_COLOR_FAMILY_SLOTS] = {false};
    float family_r_mean[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_g_mean[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_b_mean[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_luma_mean[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_chroma_mean[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_rgb_std[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_luma_std[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    float family_chroma_std[TARGET_COLOR_FAMILY_SLOTS] = {0.0f};
    for (int slot = 0; slot < TARGET_COLOR_FAMILY_SLOTS; slot++) {
        int count = family_counts[slot];
        if (count <= 0 || result->sampled_pixels <= 0) continue;
        float prevalence = (float)count / (float)result->sampled_pixels;
        family_pervasive[slot] = prevalence >= 0.30f;
        double inv_count = 1.0 / (double)count;
        double r_mean = family_r_sum[slot] * inv_count;
        double g_mean = family_g_sum[slot] * inv_count;
        double b_mean = family_b_sum[slot] * inv_count;
        double luma_mean = family_luma_sum[slot] * inv_count;
        double chroma_mean = family_chroma_sum[slot] * inv_count;
        double r_var = fmax(0.0, family_r_sq_sum[slot] * inv_count - (r_mean * r_mean));
        double g_var = fmax(0.0, family_g_sq_sum[slot] * inv_count - (g_mean * g_mean));
        double b_var = fmax(0.0, family_b_sq_sum[slot] * inv_count - (b_mean * b_mean));
        double luma_var = fmax(0.0, family_luma_sq_sum[slot] * inv_count - (luma_mean * luma_mean));
        double chroma_var = fmax(0.0, family_chroma_sq_sum[slot] * inv_count - (chroma_mean * chroma_mean));
        family_r_mean[slot] = (float)r_mean;
        family_g_mean[slot] = (float)g_mean;
        family_b_mean[slot] = (float)b_mean;
        family_luma_mean[slot] = (float)luma_mean;
        family_chroma_mean[slot] = (float)chroma_mean;
        family_rgb_std[slot] = (float)sqrt(r_var + g_var + b_var);
        family_luma_std[slot] = (float)sqrt(luma_var);
        family_chroma_std[slot] = (float)sqrt(chroma_var);
    }
    const float component_contrast_floor = 12.0f;
    for (int gy = 0; gy < grid_h; gy++) {
        int py = gy * sample_step;
        if (py >= frame_height) py = frame_height - 1;
        for (int gx = 0; gx < grid_w; gx++) {
            size_t idx = (size_t)gy * (size_t)grid_w + (size_t)gx;
            uint32_t family = scratch->family_mask_grid[idx];
            if (family == 0u) continue;
            int px = gx * sample_step;
            if (px >= frame_width) px = frame_width - 1;
            const uint8_t *p = rgba + (size_t)py * (size_t)rgba_stride + (size_t)px * 4u;
            float luma = (0.2126f * (float)p[0]) + (0.7152f * (float)p[1]) + (0.0722f * (float)p[2]);
            int slot = target_color_family_slot(family);
            bool common_selected_background = false;
            if (slot >= 0 && family_pervasive[slot]) {
                int max_c = target_color_max3((int)p[0], (int)p[1], (int)p[2]);
                int min_c = target_color_min3((int)p[0], (int)p[1], (int)p[2]);
                float chroma = (float)(max_c - min_c);
                float dr = (float)p[0] - family_r_mean[slot];
                float dg = (float)p[1] - family_g_mean[slot];
                float db = (float)p[2] - family_b_mean[slot];
                float rgb_distance = sqrtf((dr * dr) + (dg * dg) + (db * db));
                float rgb_floor = fmaxf(42.0f, (2.40f * family_rgb_std[slot]) + 12.0f);
                float luma_floor = fmaxf(18.0f, (2.20f * family_luma_std[slot]) + 6.0f);
                float chroma_floor = fmaxf(24.0f, (2.20f * family_chroma_std[slot]) + 6.0f);
                common_selected_background =
                    rgb_distance <= rgb_floor &&
                    fabsf(luma - family_luma_mean[slot]) <= luma_floor &&
                    fabsf(chroma - family_chroma_mean[slot]) <= chroma_floor;
            }
            bool low_frame_luma_contrast =
                slot < 0 || (!family_pervasive[slot] && fabsf(luma - frame_luma_mean) < component_contrast_floor);
            if (common_selected_background || low_frame_luma_contrast) {
                scratch->family_mask_grid[idx] = 0u;
            }
        }
    }

    static const int dx4[4] = {1, -1, 0, 0};
    static const int dy4[4] = {0, 0, 1, -1};
    for (int gy = 0; gy < grid_h; gy++) {
        for (int gx = 0; gx < grid_w; gx++) {
            int start = gy * grid_w + gx;
            if (scratch->family_mask_grid[start] == 0u || scratch->visited_grid[start]) continue;
            result->candidate_component_count++;

            int qh = 0;
            int qt = 0;
            scratch->queue[qt++] = start;
            scratch->visited_grid[start] = 1u;
            int count = 0;
            int min_x = gx;
            int max_x = gx;
            int min_y = gy;
            int max_y = gy;
            uint32_t family_mask = 0u;
            double sx_sum = 0.0;
            double sy_sum = 0.0;
            double luma_sum = 0.0;
            while (qh < qt) {
                int idx = scratch->queue[qh++];
                int cx = idx % grid_w;
                int cy = idx / grid_w;
                uint32_t family = scratch->family_mask_grid[idx];
                family_mask |= family;
                count++;
                if (cx < min_x) min_x = cx;
                if (cx > max_x) max_x = cx;
                if (cy < min_y) min_y = cy;
                if (cy > max_y) max_y = cy;
                sx_sum += (double)cx;
                sy_sum += (double)cy;
                int px = cx * sample_step;
                int py = cy * sample_step;
                if (px >= frame_width) px = frame_width - 1;
                if (py >= frame_height) py = frame_height - 1;
                const uint8_t *p = rgba + (size_t)py * (size_t)rgba_stride + (size_t)px * 4u;
                luma_sum += (0.2126 * (double)p[0]) + (0.7152 * (double)p[1]) + (0.0722 * (double)p[2]);
                for (int ni = 0; ni < 4; ni++) {
                    int nx = cx + dx4[ni];
                    int ny = cy + dy4[ni];
                    if (nx < 0 || ny < 0 || nx >= grid_w || ny >= grid_h) continue;
                    int nidx = ny * grid_w + nx;
                    if (scratch->visited_grid[nidx] || scratch->family_mask_grid[nidx] == 0u) continue;
                    scratch->visited_grid[nidx] = 1u;
                    scratch->queue[qt++] = nidx;
                }
            }

            int bbox_w = max_x - min_x + 1;
            int bbox_h = max_y - min_y + 1;
            int bbox_area = bbox_w * bbox_h;
            float density = bbox_area > 0 ? (float)count / (float)bbox_area : 0.0f;
            float compactness = bbox_w > bbox_h
                ? (float)bbox_h / (float)bbox_w
                : (float)bbox_w / (float)bbox_h;
            float mean_luma = count > 0 ? (float)(luma_sum / (double)count) : frame_luma_mean;
            float contrast = fabsf(mean_luma - frame_luma_mean);
            anomaly_target_color_component_score_t score =
                anomaly_target_color_score_component(
                    family_mask,
                    count,
                    bbox_area,
                    density,
                    compactness,
                    contrast);
            if (!score.accepted) continue;

            float center_x = (((float)(sx_sum / (double)count) * (float)sample_step) + (0.5f * (float)sample_step)) /
                             (float)frame_width;
            float center_y = (((float)(sy_sum / (double)count) * (float)sample_step) + (0.5f * (float)sample_step)) /
                             (float)frame_height;
            float half_w = fmaxf(((float)bbox_w * (float)sample_step) / (2.0f * (float)frame_width), 0.025f);
            float half_h = fmaxf(((float)bbox_h * (float)sample_step) / (2.0f * (float)frame_height), 0.025f);
            anomaly_target_color_roi_t roi = {
                .family_mask = score.family_mask,
                .distinct_family_count = score.distinct_family_count,
                .support_count = score.support_count,
                .score = score.score,
                .confidence = score.confidence,
                .center_x_norm = target_color_clampf(center_x, 0.0f, 1.0f),
                .center_y_norm = target_color_clampf(center_y, 0.0f, 1.0f),
                .half_w_norm = target_color_clampf(half_w, 0.0f, 0.50f),
                .half_h_norm = target_color_clampf(half_h, 0.0f, 0.50f),
                .density = density,
            };
            target_color_insert_roi(result, &roi);
        }
    }

    return true;
}

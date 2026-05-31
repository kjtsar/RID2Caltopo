#include "anomaly_grid_region.h"

#include <stddef.h>
#include <string.h>

bool anomaly_grid_region_compute_active_mask_bounds(
        const uint8_t *mask,
        int sg_w,
        int sg_h,
        int pad,
        int *min_sx_out,
        int *min_sy_out,
        int *max_sx_out,
        int *max_sy_out) {
    if (mask == NULL || sg_w <= 0 || sg_h <= 0 ||
        min_sx_out == NULL || min_sy_out == NULL ||
        max_sx_out == NULL || max_sy_out == NULL) {
        return false;
    }

    int min_sx = sg_w;
    int min_sy = sg_h;
    int max_sx = -1;
    int max_sy = -1;
    for (int sy = 0; sy < sg_h; sy++) {
        for (int sx = 0; sx < sg_w; sx++) {
            size_t idx = (size_t)sy * (size_t)sg_w + (size_t)sx;
            if (mask[idx] == 0u) continue;
            if (sx < min_sx) min_sx = sx;
            if (sx > max_sx) max_sx = sx;
            if (sy < min_sy) min_sy = sy;
            if (sy > max_sy) max_sy = sy;
        }
    }
    if (max_sx < min_sx || max_sy < min_sy) return false;

    if (pad < 0) pad = 0;
    min_sx -= pad;
    min_sy -= pad;
    max_sx += pad;
    max_sy += pad;
    if (min_sx < 0) min_sx = 0;
    if (min_sy < 0) min_sy = 0;
    if (max_sx >= sg_w) max_sx = sg_w - 1;
    if (max_sy >= sg_h) max_sy = sg_h - 1;

    *min_sx_out = min_sx;
    *min_sy_out = min_sy;
    *max_sx_out = max_sx;
    *max_sy_out = max_sy;
    return true;
}

void anomaly_grid_region_zero_float(
        float *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (map == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memset(&map[row_idx], 0, (size_t)span * sizeof(float));
    }
}

void anomaly_grid_region_copy_float(
        float *dst,
        const float *src,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (dst == NULL || src == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memcpy(&dst[row_idx], &src[row_idx], (size_t)span * sizeof(float));
    }
}

void anomaly_grid_region_zero_u8(
        uint8_t *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy) {
    if (map == NULL || sg_w <= 0) return;
    if (max_sx < min_sx || max_sy < min_sy) return;
    int span = max_sx - min_sx + 1;
    for (int sy = min_sy; sy <= max_sy; sy++) {
        size_t row_idx = (size_t)sy * (size_t)sg_w + (size_t)min_sx;
        memset(&map[row_idx], 0, (size_t)span * sizeof(uint8_t));
    }
}

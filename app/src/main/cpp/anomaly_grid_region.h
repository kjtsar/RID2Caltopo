#ifndef ANOMALY_GRID_REGION_H
#define ANOMALY_GRID_REGION_H

#include <stdbool.h>
#include <stdint.h>

bool anomaly_grid_region_compute_active_mask_bounds(
        const uint8_t *mask,
        int sg_w,
        int sg_h,
        int pad,
        int *min_sx_out,
        int *min_sy_out,
        int *max_sx_out,
        int *max_sy_out);

void anomaly_grid_region_zero_float(
        float *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy);

void anomaly_grid_region_copy_float(
        float *dst,
        const float *src,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy);

void anomaly_grid_region_zero_u8(
        uint8_t *map,
        int sg_w,
        int min_sx,
        int min_sy,
        int max_sx,
        int max_sy);

#endif  // ANOMALY_GRID_REGION_H

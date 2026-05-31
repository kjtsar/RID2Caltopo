#include "anomaly_roi_state.h"
#include "anomaly_scan_planner.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static bool resize_u8_buffer(uint8_t **buffer, size_t count) {
    if (buffer == NULL) return false;
    if (count == 0) return true;
    uint8_t *grown = (uint8_t *)realloc(*buffer, count * sizeof(uint8_t));
    if (grown == NULL) return false;
    *buffer = grown;
    return true;
}

static bool resize_float_buffer(float **buffer, size_t count) {
    if (buffer == NULL) return false;
    if (count == 0) return true;
    float *grown = (float *)realloc(*buffer, count * sizeof(float));
    if (grown == NULL) return false;
    *buffer = grown;
    return true;
}

bool anomaly_roi_state_ensure_pixel_capacity(anomaly_roi_state_t *roi_state,
                                             size_t pixel_count) {
    if (roi_state == NULL) return false;
    if (pixel_count == 0) return true;
    if (roi_state->pixel_capacity >= pixel_count &&
        roi_state->last_luma != NULL &&
        roi_state->thermal_score != NULL &&
        roi_state->temporal_score != NULL &&
        roi_state->color_luma != NULL &&
        roi_state->color_u != NULL &&
        roi_state->color_v != NULL &&
        roi_state->color_raw_score != NULL &&
        roi_state->color_contrast_weight != NULL &&
        roi_state->color_u_bin != NULL &&
        roi_state->color_v_bin != NULL &&
        roi_state->valid_mask != NULL &&
        roi_state->fresh_mask != NULL &&
        roi_state->carried_mask != NULL &&
        roi_state->new_exposed_mask != NULL &&
        roi_state->color_valid_mask != NULL &&
        roi_state->color_phase_x != NULL &&
        roi_state->color_phase_y != NULL &&
        roi_state->reg_confidence != NULL &&
        roi_state->coverage_age != NULL) {
        return true;
    }
    if (!resize_float_buffer(&roi_state->last_luma, pixel_count) ||
        !resize_float_buffer(&roi_state->thermal_score, pixel_count) ||
        !resize_float_buffer(&roi_state->temporal_score, pixel_count) ||
        !resize_float_buffer(&roi_state->color_luma, pixel_count) ||
        !resize_float_buffer(&roi_state->color_u, pixel_count) ||
        !resize_float_buffer(&roi_state->color_v, pixel_count) ||
        !resize_float_buffer(&roi_state->color_raw_score, pixel_count) ||
        !resize_float_buffer(&roi_state->color_contrast_weight, pixel_count) ||
        !resize_u8_buffer(&roi_state->color_u_bin, pixel_count) ||
        !resize_u8_buffer(&roi_state->color_v_bin, pixel_count) ||
        !resize_u8_buffer(&roi_state->valid_mask, pixel_count) ||
        !resize_u8_buffer(&roi_state->fresh_mask, pixel_count) ||
        !resize_u8_buffer(&roi_state->carried_mask, pixel_count) ||
        !resize_u8_buffer(&roi_state->new_exposed_mask, pixel_count) ||
        !resize_u8_buffer(&roi_state->color_valid_mask, pixel_count) ||
        !resize_u8_buffer(&roi_state->color_phase_x, pixel_count) ||
        !resize_u8_buffer(&roi_state->color_phase_y, pixel_count) ||
        !resize_float_buffer(&roi_state->reg_confidence, pixel_count) ||
        !resize_u8_buffer(&roi_state->coverage_age, pixel_count)) {
        return false;
    }
    roi_state->pixel_capacity = pixel_count;
    return true;
}

bool anomaly_roi_state_ensure_cell_capacity(anomaly_roi_state_t *roi_state,
                                            size_t cell_count) {
    if (roi_state == NULL) return false;
    if (cell_count == 0) return true;
    if (roi_state->cell_capacity >= cell_count && roi_state->cell_summaries != NULL) {
        return true;
    }
    anomaly_roi_cell_summary_t *grown = (anomaly_roi_cell_summary_t *)realloc(
        roi_state->cell_summaries,
        cell_count * sizeof(anomaly_roi_cell_summary_t));
    if (grown == NULL) return false;
    roi_state->cell_summaries = grown;
    roi_state->cell_capacity = cell_count;
    return true;
}

void anomaly_roi_state_summarize_cells(
        anomaly_roi_state_t *roi_state,
        const float         *motion_support_map,
        int                  carry_expiry_frames,
        float                registration_confidence) {
    if (roi_state == NULL || !roi_state->valid) return;
    int width = roi_state->width;
    int height = roi_state->height;
    if (width <= 0 || height <= 0 || roi_state->cell_cols <= 0 || roi_state->cell_rows <= 0) {
        return;
    }
    size_t cell_count = (size_t)roi_state->cell_cols * (size_t)roi_state->cell_rows;
    if (!anomaly_roi_state_ensure_cell_capacity(roi_state, cell_count)) return;
    memset(roi_state->cell_summaries, 0, cell_count * sizeof(anomaly_roi_cell_summary_t));
    int cell_span = anomaly_scan_planner_roi_grid_cell_span(roi_state->sample_step);
    if (cell_span <= 0) cell_span = 1;
    for (int sy = 0; sy < height; sy++) {
        int cell_y = sy / cell_span;
        if (cell_y >= roi_state->cell_rows) cell_y = roi_state->cell_rows - 1;
        for (int sx = 0; sx < width; sx++) {
            int cell_x = sx / cell_span;
            if (cell_x >= roi_state->cell_cols) cell_x = roi_state->cell_cols - 1;
            size_t idx = (size_t)sy * (size_t)width + (size_t)sx;
            size_t cell_idx = (size_t)cell_y * (size_t)roi_state->cell_cols + (size_t)cell_x;
            anomaly_roi_cell_summary_t *cell = &roi_state->cell_summaries[cell_idx];
            if (roi_state->valid_mask[idx]) cell->valid_count++;
            if (roi_state->fresh_mask[idx]) cell->fresh_count++;
            if (roi_state->carried_mask[idx]) cell->carried_count++;
            if (roi_state->new_exposed_mask[idx]) cell->newly_exposed_count++;
            if (roi_state->valid_mask[idx] &&
                roi_state->coverage_age[idx] > carry_expiry_frames) {
                cell->stale_count++;
            }
            if (roi_state->new_exposed_mask[idx]) cell->scan_flags |= ANOMALY_SCAN_FLAG_NEW_EXPOSED;
            if (roi_state->valid_mask[idx] &&
                roi_state->coverage_age[idx] > carry_expiry_frames) {
                cell->scan_flags |= ANOMALY_SCAN_FLAG_STALE;
            }
            if (roi_state->reg_confidence[idx] < 0.50f) {
                cell->scan_flags |= ANOMALY_SCAN_FLAG_LOW_CONFIDENCE;
            }
            if (roi_state->thermal_score != NULL &&
                roi_state->thermal_score[idx] > cell->max_thermal_score) {
                cell->max_thermal_score = roi_state->thermal_score[idx];
            }
            if (roi_state->color_raw_score != NULL &&
                roi_state->color_raw_score[idx] > cell->max_color_score) {
                cell->max_color_score = roi_state->color_raw_score[idx];
            }
            float motion_support = motion_support_map != NULL ? motion_support_map[idx] : 0.0f;
            if (motion_support > cell->max_motion_support) {
                cell->max_motion_support = motion_support;
            }
            if (roi_state->reg_confidence[idx] > cell->registration_quality) {
                cell->registration_quality = roi_state->reg_confidence[idx];
            } else if (cell->registration_quality <= 0.0f) {
                cell->registration_quality = registration_confidence;
            }
        }
    }
}

void anomaly_roi_state_clear(anomaly_roi_state_t *roi_state) {
    if (roi_state == NULL) return;
    roi_state->valid = false;
    roi_state->roi_x0 = 0;
    roi_state->roi_y0 = 0;
    roi_state->roi_x1 = 0;
    roi_state->roi_y1 = 0;
    roi_state->width = 0;
    roi_state->height = 0;
    roi_state->sample_step = 0;
    roi_state->cell_size_px = 0;
    roi_state->cell_cols = 0;
    roi_state->cell_rows = 0;
    if (roi_state->pixel_capacity > 0) {
        memset(roi_state->color_valid_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_phase_x, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_phase_y, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_u_bin, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->color_v_bin, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->valid_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->fresh_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->carried_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->new_exposed_mask, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        memset(roi_state->coverage_age, 0, roi_state->pixel_capacity * sizeof(uint8_t));
        if (roi_state->color_luma != NULL) {
            memset(roi_state->color_luma, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_u != NULL) {
            memset(roi_state->color_u, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_v != NULL) {
            memset(roi_state->color_v, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_raw_score != NULL) {
            memset(roi_state->color_raw_score, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->color_contrast_weight != NULL) {
            memset(roi_state->color_contrast_weight, 0, roi_state->pixel_capacity * sizeof(float));
        }
        if (roi_state->reg_confidence != NULL) {
            memset(roi_state->reg_confidence, 0, roi_state->pixel_capacity * sizeof(float));
        }
    }
    if (roi_state->cell_capacity > 0 && roi_state->cell_summaries != NULL) {
        memset(roi_state->cell_summaries, 0,
               roi_state->cell_capacity * sizeof(anomaly_roi_cell_summary_t));
    }
}

void anomaly_roi_state_release(anomaly_roi_state_t *roi_state) {
    if (roi_state == NULL) return;
    free(roi_state->last_luma);
    free(roi_state->thermal_score);
    free(roi_state->temporal_score);
    free(roi_state->color_luma);
    free(roi_state->color_u);
    free(roi_state->color_v);
    free(roi_state->color_raw_score);
    free(roi_state->color_contrast_weight);
    free(roi_state->color_u_bin);
    free(roi_state->color_v_bin);
    free(roi_state->valid_mask);
    free(roi_state->fresh_mask);
    free(roi_state->carried_mask);
    free(roi_state->new_exposed_mask);
    free(roi_state->color_valid_mask);
    free(roi_state->color_phase_x);
    free(roi_state->color_phase_y);
    free(roi_state->reg_confidence);
    free(roi_state->coverage_age);
    free(roi_state->cell_summaries);
    memset(roi_state, 0, sizeof(*roi_state));
}

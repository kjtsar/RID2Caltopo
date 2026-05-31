#ifndef ANOMALY_DEBUG_HELPERS_H
#define ANOMALY_DEBUG_HELPERS_H

#include "anomaly_analysis.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct anomaly_registration_model_t anomaly_registration_model_t;

const char *anomaly_debug_scan_reason_flag_name(uint32_t flag);

void anomaly_debug_format_scan_reason_flags(
        uint32_t flags,
        char    *buffer,
        size_t   buffer_size);

const char *anomaly_debug_registration_invalid_reason_name(
        anomaly_registration_invalid_reason_t reason);

void anomaly_debug_populate_registration_model(
        const anomaly_registration_model_t *model,
        anomaly_result_t                   *result_out);

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
        float                      combined_score);

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
        uint8_t  b);

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
        uint8_t  b);

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
        uint8_t  b);

void anomaly_debug_draw_boxes_rgba(
        uint8_t             *rgba,
        int                  rgba_stride,
        int                  width,
        int                  height,
        const anomaly_box_t *boxes,
        int                  box_count);

void anomaly_debug_draw_hot_overlay_rgba(
        uint8_t *rgba,
        int      rgba_stride,
        int      width,
        int      height,
        int      thermal_polarity);

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
        float          weight);

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
        bool           draw_crosshair);

#endif // ANOMALY_DEBUG_HELPERS_H

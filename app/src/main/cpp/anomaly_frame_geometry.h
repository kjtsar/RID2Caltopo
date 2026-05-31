// Internal frame/ROI geometry helpers for anomaly_analysis.c.
#pragma once

typedef struct anomaly_frame_roi_bounds_t {
    int x0;
    int y0;
    int x1;
    int y1;
} anomaly_frame_roi_bounds_t;

static inline float anomaly_frame_geometry_clampf(float value, float min_value, float max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static inline anomaly_frame_roi_bounds_t anomaly_frame_centered_roi_bounds(
        int   width,
        int   height,
        float zone_fraction) {
    anomaly_frame_roi_bounds_t bounds = {
        .x0 = 0,
        .y0 = 0,
        .x1 = width,
        .y1 = height,
    };
    if (width > 0 && height > 0) {
        float zone = anomaly_frame_geometry_clampf(zone_fraction, 0.5f, 1.0f);
        float margin = (1.0f - zone) * 0.5f;
        bounds.x0 = (int)(margin * (float)width);
        bounds.x1 = width - bounds.x0;
        bounds.y0 = (int)(margin * (float)height);
        bounds.y1 = height - bounds.y0;
        if (bounds.x1 <= bounds.x0) { bounds.x0 = 0; bounds.x1 = width; }
        if (bounds.y1 <= bounds.y0) { bounds.y0 = 0; bounds.y1 = height; }
    }
    return bounds;
}

static inline anomaly_frame_roi_bounds_t anomaly_frame_registration_roi_bounds(
        int width,
        int height) {
    anomaly_frame_roi_bounds_t bounds = {
        .x0 = 0,
        .y0 = 0,
        .x1 = width > 0 ? width : 0,
        .y1 = height > 0 ? height : 0,
    };
    return bounds;
}

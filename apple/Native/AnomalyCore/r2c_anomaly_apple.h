#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define R2C_ANOMALY_ALGORITHM_COLOR 0x01
#define R2C_ANOMALY_ALGORITHM_THERMAL 0x02
#define R2C_ANOMALY_ALGORITHM_MOTION 0x04
#define R2C_ANOMALY_ALGORITHM_SALIENCY 0x08
#define R2C_ANOMALY_MAX_BOXES 4

typedef struct R2CAnomalyRuntime R2CAnomalyRuntime;

typedef struct {
    float left;
    float top;
    float right;
    float bottom;
    float weight;
    uint8_t red;
    uint8_t green;
    uint8_t blue;
    uint8_t draw_crosshair;
    int32_t algorithm;
} R2CAnomalyBox;

typedef struct {
    int64_t frame_ordinal;
    int32_t raw_box_count;
    int32_t stable_box_count;
    int32_t annotation_count;
    int32_t hot_overlay_valid;
    float hot_center_x;
    float hot_center_y;
    float hot_radius;
    float hot_stroke;
    R2CAnomalyBox boxes[R2C_ANOMALY_MAX_BOXES];
} R2CAnomalyFrameResult;

typedef struct {
    int32_t enabled;
    int32_t show_hot_overlay;
    int32_t show_candidate_blobs;
    int32_t algorithm_mask;
    int32_t registration_mode;
    int32_t movement_estimator_mode;
    int32_t stride_mode;
    int32_t frame_stride;
    int32_t adaptive_min_stride_frames;
    float adaptive_max_stride_seconds;
    int32_t pixel_step;
    float score_threshold;
    float motion_evidence_scale;
    float min_area_fraction;
    int32_t thermal_polarity;
    float scan_zone;
    int32_t min_hits;
    float thermal_min_delta;
    float small_target_screen_fraction;
    int32_t color_frontend_mode;
    int32_t color_target_candidate_limit;
    uint32_t target_color_family_mask;
} R2CAnomalyConfiguration;

R2CAnomalyRuntime *R2CAnomalyCreate(int32_t algorithm_mask, float frame_rate_fps);
void R2CAnomalyDestroy(R2CAnomalyRuntime *runtime);
int32_t R2CAnomalyApplyConfiguration(
        R2CAnomalyRuntime *runtime,
        const R2CAnomalyConfiguration *configuration);

/// Converts a Core Video 32BGRA frame to the detector's RGBA contract and runs
/// the existing portable RID2Caltopo anomaly core. Returns zero on success.
int32_t R2CAnomalyProcessBGRA(
        R2CAnomalyRuntime *runtime,
        const uint8_t *bgra,
        int32_t bytes_per_row,
        int32_t width,
        int32_t height,
        int64_t timestamp_us,
        R2CAnomalyFrameResult *result_out);

int32_t R2CAnomalyFrameResultCopyBox(
        const R2CAnomalyFrameResult *result,
        int32_t index,
        R2CAnomalyBox *box_out);

#ifdef __cplusplus
}
#endif

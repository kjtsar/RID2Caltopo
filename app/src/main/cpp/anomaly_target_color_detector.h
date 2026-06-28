#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define ANOMALY_TARGET_COLOR_NONE          0x00u
#define ANOMALY_TARGET_COLOR_RED           0x01u
#define ANOMALY_TARGET_COLOR_BLUE          0x02u
#define ANOMALY_TARGET_COLOR_YELLOW_ORANGE 0x04u
#define ANOMALY_TARGET_COLOR_GREEN         0x08u
#define ANOMALY_TARGET_COLOR_BLACK         0x10u
#define ANOMALY_TARGET_COLOR_WHITE         0x20u
#define ANOMALY_TARGET_COLOR_SKIN          0x40u
#define ANOMALY_TARGET_COLOR_ALL           0x7Fu

#define ANOMALY_TARGET_COLOR_MAX_ROIS 2

typedef struct {
    bool accepted;
    uint32_t family_mask;
    int distinct_family_count;
    int support_count;
    float score;
    float confidence;
} anomaly_target_color_component_score_t;

typedef struct {
    uint32_t family_mask;
    int distinct_family_count;
    int support_count;
    float score;
    float confidence;
    float center_x_norm;
    float center_y_norm;
    float half_w_norm;
    float half_h_norm;
    float density;
} anomaly_target_color_roi_t;

typedef struct {
    uint32_t selected_family_mask;
    int sampled_pixels;
    int selected_pixel_count;
    int candidate_component_count;
    int roi_count;
    anomaly_target_color_roi_t rois[ANOMALY_TARGET_COLOR_MAX_ROIS];
} anomaly_target_color_result_t;

typedef struct {
    uint8_t *family_mask_grid;
    uint8_t *visited_grid;
    int *queue;
    size_t cell_capacity;
    size_t queue_capacity;
} anomaly_target_color_scratch_t;

void anomaly_target_color_scratch_init(anomaly_target_color_scratch_t *scratch);
void anomaly_target_color_scratch_cleanup(anomaly_target_color_scratch_t *scratch);
void anomaly_target_color_result_init(anomaly_target_color_result_t *result);

uint32_t anomaly_target_color_classify_rgb(uint8_t r, uint8_t g, uint8_t b);
int anomaly_target_color_count_families(uint32_t family_mask);

anomaly_target_color_component_score_t anomaly_target_color_score_component(
        uint32_t family_mask,
        int support_count,
        int bbox_area_cells,
        float density,
        float compactness,
        float local_contrast);

bool anomaly_target_color_detect_rgba(
        const uint8_t                  *rgba,
        int                             rgba_stride,
        int                             frame_width,
        int                             frame_height,
        uint32_t                        selected_family_mask,
        anomaly_target_color_scratch_t *scratch,
        anomaly_target_color_result_t  *result);

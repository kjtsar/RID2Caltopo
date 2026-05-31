#ifndef ANOMALY_APPEARANCE_DETECTOR_H
#define ANOMALY_APPEARANCE_DETECTOR_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "anomaly_analysis.h"
#include "anomaly_appearance_candidates.h"
#include "anomaly_frame.h"
#include "anomaly_motion_estimator.h"
#include "anomaly_target_observations.h"

// Internal modal appearance detector boundary. Thermal/IR and visible color are
// mutually selective modes; motion and future shape evidence remain additive
// producers/validators outside this modal contract.
typedef enum {
    ANOMALY_APPEARANCE_DETECTOR_THERMAL = 1,
    ANOMALY_APPEARANCE_DETECTOR_COLOR = 2,
} anomaly_appearance_detector_mode_t;

typedef struct {
    anomaly_appearance_detector_mode_t mode;
    int                                algorithm_mask;
    float                              score_threshold;
    int                                thermal_polarity;
    float                              thermal_min_delta;
    float                              small_target_screen_fraction;
    int                                color_frontend_mode;
    bool                               thermal_debug_target_enabled;
    float                              thermal_debug_target_x_norm;
    float                              thermal_debug_target_y_norm;
    bool                               color_debug_target_enabled;
    float                              color_debug_target_x_norm;
    float                              color_debug_target_y_norm;
} anomaly_appearance_detector_config_t;

typedef struct {
    const anomaly_frame_input_t              *frame;
    int                                       roi_x0;
    int                                       roi_y0;
    int                                       roi_x1;
    int                                       roi_y1;
    int                                       sample_step;
    int                                       sg_w;
    int                                       sg_h;
    const float                              *sg_luma;
    const float                              *ii_sum;
    const float                              *ii_sum2;
    int                                       rescan_mode;
    const anomaly_scan_plan_t                *scan_plan;
    const uint8_t                            *appearance_refresh_mask;
    bool                                      selective_refresh_active;
    bool                                      color_stride_hold_frame;
    int64_t                                   source_timestamp_us;
    const void                               *registration;
    const anomaly_motion_movement_snapshot_t *movement;
} anomaly_appearance_frame_context_t;

typedef struct {
    float   *thermal_score_map;
    float   *thermal_delta_map;
    float   *color_support_map;
    float   *color_raw_score_map;
    uint8_t *visited_mask;
    int     *queue_x;
    int     *queue_y;
    float   *patch_score;
    float   *patch_selection;
    size_t   sample_capacity;
    size_t   queue_capacity;
    size_t   patch_capacity;
} anomaly_appearance_scratch_t;

typedef struct {
    bool  valid;
    float score;
    int   pixel_x;
    int   pixel_y;
    int   winning_candidate_index;
    int   raw_candidate_index;
    int   candidate_count;
} anomaly_appearance_best_t;

typedef struct {
    anomaly_appearance_detector_mode_t       mode;
    anomaly_appearance_best_t                best;
    anomaly_thermal_blob_candidate_t        *thermal_candidates;
    int                                      thermal_candidate_count;
    int                                      thermal_candidate_capacity;
    anomaly_color_blob_candidate_t          *color_candidates;
    int                                      color_candidate_count;
    int                                      color_candidate_capacity;
    anomaly_target_observation_t            *target_observations;
    int                                      target_observation_count;
    int                                      target_observation_capacity;
    anomaly_debug_thermal_t                 *thermal_debug;
    anomaly_debug_color_t                   *color_debug;
    int64_t                                  thermal_timing_us;
    int64_t                                  color_timing_us;
} anomaly_appearance_detector_result_t;

typedef struct anomaly_appearance_detector_ops {
    anomaly_appearance_detector_mode_t mode;
    bool (*reset)(void *state);
    bool (*prepare_frame)(void                                      *state,
                          const anomaly_appearance_detector_config_t *config,
                          const anomaly_appearance_frame_context_t   *context,
                          anomaly_appearance_scratch_t               *scratch);
    bool (*score_frame)(void                                      *state,
                        const anomaly_appearance_detector_config_t *config,
                        const anomaly_appearance_frame_context_t   *context,
                        anomaly_appearance_scratch_t               *scratch,
                        anomaly_appearance_detector_result_t       *result);
    bool (*extract_candidates)(void                                      *state,
                               const anomaly_appearance_detector_config_t *config,
                               const anomaly_appearance_frame_context_t   *context,
                               anomaly_appearance_scratch_t               *scratch,
                               anomaly_appearance_detector_result_t       *result);
    bool (*select_observations)(void                                      *state,
                                const anomaly_appearance_detector_config_t *config,
                                const anomaly_appearance_frame_context_t   *context,
                                anomaly_appearance_detector_result_t       *result);
    void (*export_debug)(const void                                *state,
                         const anomaly_appearance_detector_result_t *result);
} anomaly_appearance_detector_ops_t;

#endif

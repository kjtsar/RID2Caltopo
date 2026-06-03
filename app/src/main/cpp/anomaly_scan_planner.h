// Internal ScanPlanner contract sketch.
#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "anomaly_detector_internal.h"

typedef struct anomaly_registration_model_t anomaly_scan_planner_registration_t;

typedef struct {
    int carried_samples;
    int newly_exposed_samples;
    int stale_samples;
} anomaly_scan_planner_prev_lookup_summary_t;

#define ANOMALY_SCAN_PLANNER_PREV_LOOKUP_INVALID (-1)

typedef struct {
    bool     adaptive_enabled;
    int      fixed_frame_stride;
    uint32_t reason_flags;
    float    motion_load;
} anomaly_scan_planner_adaptive_input_t;

typedef struct {
    int      effective_frame_stride;
    int      stable_frames;
    int      drop_hold_frames;
    uint32_t reason_flags;
    float    motion_load;
} anomaly_scan_planner_adaptive_output_t;

typedef struct {
    bool     active;
    bool     allow_sparse_fallback;
    int      selected_samples;
    float    selected_fraction;
    uint32_t reason_flags;
} anomaly_scan_planner_selective_refresh_t;

#define ANOMALY_SCAN_FLAG_NEW_EXPOSED    0x01u
#define ANOMALY_SCAN_FLAG_STALE          0x02u
#define ANOMALY_SCAN_FLAG_TARGET_REVISIT 0x04u
#define ANOMALY_SCAN_FLAG_LOW_CONFIDENCE 0x08u

#define ANOMALY_SCAN_PLANNER_ROI_CELL_TARGET_SIZE_PX 16

int anomaly_scan_planner_roi_grid_cell_span(int sample_step);

typedef struct {
    bool (*registration_valid)(
            const anomaly_scan_planner_registration_t *registration);
    int (*target_revisit_track_count)(
            const anomaly_state_t *state);
    void (*adaptive_target_track_risk)(
            const anomaly_state_t *state,
            int                    min_hits,
            bool                  *has_track_risk_out,
            bool                  *has_weak_lock_out);
    void (*age_roi_tracks_one_frame)(
            anomaly_state_t *state);
    bool (*ensure_refresh_mask_capacity)(
            anomaly_state_t *state,
            size_t           sample_count,
            uint8_t        **refresh_mask_out);
} anomaly_scan_planner_ops_t;

const anomaly_scan_planner_ops_t *anomaly_scan_planner_default_ops(void);

typedef struct {
    anomaly_state_t                              *state;
    const anomaly_config_t                      *cfg;
    const anomaly_scan_planner_registration_t   *registration;
    const anomaly_debug_movement_t              *movement;
    const anomaly_scan_planner_ops_t            *ops;

    int64_t frame_source_ts_us;
    int64_t frame_counter;

    int frame_width;
    int frame_height;
    int roi_x0;
    int roi_y0;
    int roi_x1;
    int roi_y1;

    int sample_step;
    int sampled_width;
    int sampled_height;

    bool fixed_full_refresh_cadence_due;
    bool scene_discontinuity;
    anomaly_registration_health_t base_registration_health;

    bool color_algorithm_configured;
    bool color_stride_hold_eligible;

    const int *prev_sample_lookup;
    const anomaly_scan_planner_prev_lookup_summary_t *prev_lookup_summary;

    anomaly_scan_planner_adaptive_input_t adaptive;
    anomaly_scan_planner_selective_refresh_t selective_refresh;
} anomaly_scan_planner_input_t;

typedef struct {
    anomaly_scan_planner_adaptive_output_t adaptive;

    anomaly_rescan_mode_t rescan_mode;
    anomaly_scan_plan_t scan_plan;
    anomaly_registration_health_t registration_health;

    uint8_t *appearance_refresh_mask;
    bool selective_refresh_active;
    int selective_refresh_selected_samples;
    float selective_refresh_selected_fraction;
    uint32_t selective_refresh_reason_flags;

    bool color_stride_hold_frame;
    bool full_refresh_cadence_due;
    bool periodic_full_refresh_due;
    bool forced_full_after_mask_failure;
    int64_t scan_planning_elapsed_us;
    int64_t refresh_mask_elapsed_us;
} anomaly_scan_planner_output_t;

// Future wrapper for the current planning block that spans adaptive effective
// stride, scan-plan selection, and appearance refresh-mask construction.
//
// Color sampling remains downstream for now: prepare_color_sampling_state() can
// still force a full refresh after this initial plan, so this contract must not
// be treated as owning final color sampling coverage yet.
bool anomaly_scan_planner_plan(
        const anomaly_scan_planner_input_t *input,
        anomaly_scan_planner_output_t      *output);

bool anomaly_scan_planner_build_selective_refresh_mask(
        const anomaly_state_t *state,
        anomaly_rescan_mode_t  mode,
        bool                   allow_sparse_fallback,
        int                    sampled_width,
        int                    sampled_height,
        const int             *prev_sample_lookup,
        uint8_t               *refresh_mask,
        int                   *selected_count_out,
        uint32_t              *reason_flags_out);

bool anomaly_scan_planner_build_prev_sample_lookup(
        const anomaly_roi_state_t                 *prev,
        const anomaly_scan_planner_registration_t *registration,
        int                                        frame_width,
        int                                        frame_height,
        int                                        roi_x0,
        int                                        roi_y0,
        int                                        roi_x1,
        int                                        roi_y1,
        int                                        sample_step,
        int                                        sampled_width,
        int                                        sampled_height,
        int                                        stale_limit,
        int                                       *prev_lookup_out,
        anomaly_scan_planner_prev_lookup_summary_t *summary_out);

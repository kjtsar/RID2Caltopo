// Shadow-only motion-aware scene coverage scheduler.
#pragma once

#include <stdbool.h>
#include <stdint.h>

#define ANOMALY_SCENE_COVERAGE_COLS 8
#define ANOMALY_SCENE_COVERAGE_ROWS 6
#define ANOMALY_SCENE_COVERAGE_BLOCK_COUNT 48

typedef struct anomaly_roi_state_t anomaly_roi_state_t;
typedef struct anomaly_debug_movement_t anomaly_debug_movement_t;
typedef struct anomaly_scan_planner_prev_lookup_summary_t
        anomaly_scan_planner_prev_lookup_summary_t;

typedef enum {
    ANOMALY_SCENE_COVERAGE_FULL_REQUIRED = 0,
    ANOMALY_SCENE_COVERAGE_LOCKED_INCREMENTAL = 1,
    ANOMALY_SCENE_COVERAGE_RECOVERY = 2,
} anomaly_scene_coverage_mode_t;

typedef enum {
    ANOMALY_SCENE_COVERAGE_REASON_INVALID_INPUT = 0x00000001u,
    ANOMALY_SCENE_COVERAGE_REASON_STARTUP = 0x00000002u,
    ANOMALY_SCENE_COVERAGE_REASON_SCENE_DISCONTINUITY = 0x00000004u,
    ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_HARD = 0x00000008u,
    ANOMALY_SCENE_COVERAGE_REASON_LOOKUP_MISSING = 0x00000010u,
    ANOMALY_SCENE_COVERAGE_REASON_GRID_MISMATCH = 0x00000020u,
    ANOMALY_SCENE_COVERAGE_REASON_WARP_LOW = 0x00000040u,
    ANOMALY_SCENE_COVERAGE_REASON_NEW_EXPOSED_HIGH = 0x00000080u,
    ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_SOFT = 0x00000100u,
    ANOMALY_SCENE_COVERAGE_REASON_REGISTRATION_GAP = 0x00000200u,
    ANOMALY_SCENE_COVERAGE_REASON_MOVEMENT_WEAK = 0x00000400u,
    ANOMALY_SCENE_COVERAGE_REASON_MAX_AGE = 0x00000800u,
    ANOMALY_SCENE_COVERAGE_REASON_TIMESTAMP_FALLBACK = 0x00001000u,
    ANOMALY_SCENE_COVERAGE_REASON_MANDATORY_OVER_BUDGET = 0x00002000u,
    ANOMALY_SCENE_COVERAGE_REASON_RECOVERY_HYSTERESIS = 0x00004000u,
    ANOMALY_SCENE_COVERAGE_REASON_TARGET_REVISIT = 0x00008000u,
    ANOMALY_SCENE_COVERAGE_REASON_LOCAL_UNTRUSTED = 0x00010000u,
} anomaly_scene_coverage_reason_t;

typedef struct {
    int64_t shadow_last_selected_frame;
    int64_t shadow_last_selected_source_ts_us;
    float coverage_debt;
} anomaly_scene_coverage_block_state_t;

typedef struct {
    bool valid;
    anomaly_scene_coverage_mode_t mode;
    int healthy_recovery_frames;
    int sampled_width;
    int sampled_height;
    anomaly_scene_coverage_block_state_t
            blocks[ANOMALY_SCENE_COVERAGE_BLOCK_COUNT];
} anomaly_scene_coverage_state_t;

typedef struct {
    int locked_budget_blocks;
    int recovery_budget_blocks;
    int relock_healthy_frames;
    int64_t max_age_us;
    int max_age_frames;
    float minimum_movement_confidence;
} anomaly_scene_coverage_policy_t;

typedef struct {
    int64_t frame_counter;
    int64_t source_ts_us;
    bool registration_attempted;
    bool scene_discontinuity;
    int registration_health;
    const int *prev_sample_lookup;
    const anomaly_scan_planner_prev_lookup_summary_t *prev_lookup_summary;
    const anomaly_roi_state_t *previous_roi;
    const anomaly_debug_movement_t *movement;
    int sampled_width;
    int sampled_height;
    const anomaly_scene_coverage_policy_t *policy;
} anomaly_scene_coverage_input_t;

typedef struct {
    bool valid;
    anomaly_scene_coverage_mode_t mode;
    uint64_t selected_block_mask;
    uint64_t mandatory_block_mask;
    int selected_blocks;
    int mandatory_blocks;
    int estimated_selected_samples;
    float selected_fraction;
    float max_coverage_debt;
    int64_t max_age_us;
    int max_age_frames;
    float newly_exposed_fraction;
    uint32_t reason_flags;
} anomaly_scene_coverage_shadow_t;

void anomaly_scene_coverage_state_init(anomaly_scene_coverage_state_t *state);
void anomaly_scene_coverage_state_reset(anomaly_scene_coverage_state_t *state);
bool anomaly_scene_coverage_scheduler_observe(
        anomaly_scene_coverage_state_t *state,
        const anomaly_scene_coverage_input_t *input,
        anomaly_scene_coverage_shadow_t *shadow_out);

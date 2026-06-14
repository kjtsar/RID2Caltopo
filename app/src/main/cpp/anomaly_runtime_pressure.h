#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

typedef enum {
    ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL = 0,
    ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE = 1,
    ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE = 2,
    ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL = 3,
} anomaly_runtime_pressure_mode_t;

typedef struct {
    int queue_capacity;
    int recover_depth;
    int analyze_alternate_pct;
    int bypass_alternate_pct;
    int bypass_all_pct;
} anomaly_runtime_pressure_policy_t;

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make_default(
        int queue_capacity);

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make(
        int queue_capacity,
        int recover_depth,
        int analyze_alternate_pct,
        int bypass_alternate_pct,
        int bypass_all_pct);

int anomaly_runtime_pressure_depth_threshold(int queue_capacity, int pct);

anomaly_runtime_pressure_mode_t anomaly_runtime_pressure_select_mode(
        anomaly_runtime_pressure_policy_t policy,
        anomaly_runtime_pressure_mode_t current_mode,
        int queue_depth_before_dequeue);

bool anomaly_runtime_pressure_should_bypass_analysis(
        anomaly_runtime_pressure_mode_t mode,
        int64_t pressure_frame_counter);

int anomaly_runtime_pressure_backlog_frame_capacity(
        int64_t backlog_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int min_frames,
        int hard_capacity);

int anomaly_runtime_pressure_oldest_drop_count_for_admission(
        int queue_depth,
        int desired_depth);

int anomaly_runtime_pressure_queue_storage_capacity(
        int current_capacity,
        int min_capacity,
        int initial_capacity,
        int hard_capacity);

const char *anomaly_runtime_pressure_mode_name(
        anomaly_runtime_pressure_mode_t mode);

#ifdef __cplusplus
}
#endif

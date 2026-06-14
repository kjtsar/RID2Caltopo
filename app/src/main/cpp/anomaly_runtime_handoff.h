#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

typedef struct {
    int64_t frame_id;
    int64_t generation_id;
    int64_t source_ts_us;
    int64_t enqueued_at_ms;
    int width;
    int height;
    bool has_frame;
} anomaly_runtime_handoff_frame_t;

typedef enum {
    ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE = 0,
    ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS = 1,
} anomaly_runtime_handoff_action_t;

typedef enum {
    ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY = 0,
    ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED = 1,
    ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION = 2,
    ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS = 3,
    ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME = 4,
} anomaly_runtime_handoff_reason_t;

typedef struct {
    anomaly_runtime_handoff_action_t action;
    anomaly_runtime_handoff_reason_t reason;
} anomaly_runtime_handoff_decision_t;

typedef struct {
    int processed_delta;
    int skipped_delta;
    int forwarded_without_analysis_delta;
    int annotated_delta;
} anomaly_runtime_handoff_outcome_t;

anomaly_runtime_handoff_frame_t anomaly_runtime_handoff_frame_make(
        int64_t frame_id,
        int64_t generation_id,
        int64_t source_ts_us,
        int64_t enqueued_at_ms,
        int width,
        int height,
        bool has_frame);

bool anomaly_runtime_handoff_frame_ready(
        anomaly_runtime_handoff_frame_t frame);

bool anomaly_runtime_handoff_frame_is_stale(
        anomaly_runtime_handoff_frame_t frame,
        int64_t current_generation_id);

anomaly_runtime_handoff_decision_t anomaly_runtime_handoff_decide(
        anomaly_runtime_handoff_frame_t frame,
        bool processing_enabled,
        int64_t current_generation_id,
        bool pressure_bypass);

anomaly_runtime_handoff_outcome_t anomaly_runtime_handoff_outcome_for_decision(
        anomaly_runtime_handoff_decision_t decision,
        bool overlay_present);

const char *anomaly_runtime_handoff_reason_name(
        anomaly_runtime_handoff_reason_t reason);

#ifdef __cplusplus
}
#endif

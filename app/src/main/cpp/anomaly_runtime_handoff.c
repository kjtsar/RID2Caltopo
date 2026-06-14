#include "anomaly_runtime_handoff.h"

static anomaly_runtime_handoff_decision_t anomaly_runtime_handoff_decision(
        anomaly_runtime_handoff_action_t action,
        anomaly_runtime_handoff_reason_t reason) {
    anomaly_runtime_handoff_decision_t decision = {
        .action = action,
        .reason = reason,
    };
    return decision;
}

anomaly_runtime_handoff_frame_t anomaly_runtime_handoff_frame_make(
        int64_t frame_id,
        int64_t generation_id,
        int64_t source_ts_us,
        int64_t enqueued_at_ms,
        int width,
        int height,
        bool has_frame) {
    anomaly_runtime_handoff_frame_t frame = {
        .frame_id = frame_id,
        .generation_id = generation_id,
        .source_ts_us = source_ts_us,
        .enqueued_at_ms = enqueued_at_ms,
        .width = width,
        .height = height,
        .has_frame = has_frame,
    };
    return frame;
}

bool anomaly_runtime_handoff_frame_ready(
        anomaly_runtime_handoff_frame_t frame) {
    return frame.has_frame && frame.width > 0 && frame.height > 0;
}

bool anomaly_runtime_handoff_frame_is_stale(
        anomaly_runtime_handoff_frame_t frame,
        int64_t current_generation_id) {
    return frame.generation_id != current_generation_id;
}

anomaly_runtime_handoff_decision_t anomaly_runtime_handoff_decide(
        anomaly_runtime_handoff_frame_t frame,
        bool processing_enabled,
        int64_t current_generation_id,
        bool pressure_bypass) {
    if (!anomaly_runtime_handoff_frame_ready(frame)) {
        return anomaly_runtime_handoff_decision(
                ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS,
                ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME);
    }
    if (!processing_enabled) {
        return anomaly_runtime_handoff_decision(
                ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS,
                ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED);
    }
    if (anomaly_runtime_handoff_frame_is_stale(frame, current_generation_id)) {
        return anomaly_runtime_handoff_decision(
                ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS,
                ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION);
    }
    if (pressure_bypass) {
        return anomaly_runtime_handoff_decision(
                ANOMALY_RUNTIME_HANDOFF_ACTION_FORWARD_WITHOUT_ANALYSIS,
                ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS);
    }
    return anomaly_runtime_handoff_decision(
            ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE,
            ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY);
}

anomaly_runtime_handoff_outcome_t anomaly_runtime_handoff_outcome_for_decision(
        anomaly_runtime_handoff_decision_t decision,
        bool overlay_present) {
    bool analyzed = decision.action == ANOMALY_RUNTIME_HANDOFF_ACTION_ANALYZE;
    anomaly_runtime_handoff_outcome_t outcome = {
        .processed_delta = 1,
        .skipped_delta = analyzed ? 0 : 1,
        .forwarded_without_analysis_delta = analyzed ? 0 : 1,
        .annotated_delta = overlay_present ? 1 : 0,
    };
    return outcome;
}

const char *anomaly_runtime_handoff_reason_name(
        anomaly_runtime_handoff_reason_t reason) {
    switch (reason) {
        case ANOMALY_RUNTIME_HANDOFF_REASON_ANALYZE_READY:
            return "analyze-ready";
        case ANOMALY_RUNTIME_HANDOFF_REASON_PROCESSING_DISABLED:
            return "processing-disabled";
        case ANOMALY_RUNTIME_HANDOFF_REASON_STALE_GENERATION:
            return "stale-generation";
        case ANOMALY_RUNTIME_HANDOFF_REASON_PRESSURE_BYPASS:
            return "pressure-bypass";
        case ANOMALY_RUNTIME_HANDOFF_REASON_INVALID_FRAME:
            return "invalid-frame";
        default:
            return "unknown";
    }
}

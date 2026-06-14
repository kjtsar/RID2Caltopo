#include "anomaly_runtime_pressure.h"

#include <stdint.h>

#define ANOMALY_RUNTIME_PRESSURE_DEFAULT_RECOVER_DEPTH 2
#define ANOMALY_RUNTIME_PRESSURE_DEFAULT_ANALYZE_ALTERNATE_PCT 50
#define ANOMALY_RUNTIME_PRESSURE_DEFAULT_BYPASS_ALTERNATE_PCT 66
#define ANOMALY_RUNTIME_PRESSURE_DEFAULT_BYPASS_ALL_PCT 80

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make_default(
        int queue_capacity) {
    return anomaly_runtime_pressure_policy_make(
            queue_capacity,
            ANOMALY_RUNTIME_PRESSURE_DEFAULT_RECOVER_DEPTH,
            ANOMALY_RUNTIME_PRESSURE_DEFAULT_ANALYZE_ALTERNATE_PCT,
            ANOMALY_RUNTIME_PRESSURE_DEFAULT_BYPASS_ALTERNATE_PCT,
            ANOMALY_RUNTIME_PRESSURE_DEFAULT_BYPASS_ALL_PCT);
}

anomaly_runtime_pressure_policy_t anomaly_runtime_pressure_policy_make(
        int queue_capacity,
        int recover_depth,
        int analyze_alternate_pct,
        int bypass_alternate_pct,
        int bypass_all_pct) {
    if (queue_capacity < 0) {
        queue_capacity = 0;
    }
    anomaly_runtime_pressure_policy_t policy = {
        .queue_capacity = queue_capacity,
        .recover_depth = recover_depth,
        .analyze_alternate_pct = analyze_alternate_pct,
        .bypass_alternate_pct = bypass_alternate_pct,
        .bypass_all_pct = bypass_all_pct,
    };
    return policy;
}

int anomaly_runtime_pressure_depth_threshold(int queue_capacity, int pct) {
    if (queue_capacity <= 0) {
        return 0;
    }
    int threshold = (queue_capacity * pct + 99) / 100;
    if (threshold < 1) {
        threshold = 1;
    }
    if (threshold > queue_capacity) {
        threshold = queue_capacity;
    }
    return threshold;
}

anomaly_runtime_pressure_mode_t anomaly_runtime_pressure_select_mode(
        anomaly_runtime_pressure_policy_t policy,
        anomaly_runtime_pressure_mode_t current_mode,
        int queue_depth_before_dequeue) {
    if (queue_depth_before_dequeue <= policy.recover_depth) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL;
    }

    int bypass_all_threshold = anomaly_runtime_pressure_depth_threshold(
            policy.queue_capacity,
            policy.bypass_all_pct);
    int bypass_alternate_threshold = anomaly_runtime_pressure_depth_threshold(
            policy.queue_capacity,
            policy.bypass_alternate_pct);
    int analyze_alternate_threshold = anomaly_runtime_pressure_depth_threshold(
            policy.queue_capacity,
            policy.analyze_alternate_pct);

    if (queue_depth_before_dequeue >= bypass_all_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL;
    }

    if (queue_depth_before_dequeue >= bypass_alternate_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE;
    }

    if (queue_depth_before_dequeue >= analyze_alternate_threshold) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE;
    }
    if (current_mode == ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE) {
        return ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE;
    }

    return ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL;
}

bool anomaly_runtime_pressure_should_bypass_analysis(
        anomaly_runtime_pressure_mode_t mode,
        int64_t pressure_frame_counter) {
    if (mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL) {
        return true;
    }
    if (mode == ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE) {
        return (pressure_frame_counter % 2) == 0;
    }
    return false;
}

int anomaly_runtime_pressure_backlog_frame_capacity(
        int64_t backlog_ms,
        int64_t source_interval_ms,
        int64_t default_source_interval_ms,
        int min_frames,
        int hard_capacity) {
    if (hard_capacity <= 0) {
        return 0;
    }
    int64_t interval = source_interval_ms > 0
            ? source_interval_ms
            : default_source_interval_ms;
    if (interval <= 0) {
        interval = 1;
    }
    if (backlog_ms < 0) {
        backlog_ms = 0;
    }
    int frames = (int) ((backlog_ms + interval - 1) / interval);
    if (frames < min_frames) {
        frames = min_frames;
    }
    if (frames < 0) {
        frames = 0;
    }
    if (frames > hard_capacity) {
        frames = hard_capacity;
    }
    return frames;
}

int anomaly_runtime_pressure_oldest_drop_count_for_admission(
        int queue_depth,
        int desired_depth) {
    if (queue_depth <= 0) {
        return 0;
    }
    if (desired_depth <= 0) {
        return queue_depth;
    }
    if (queue_depth < desired_depth) {
        return 0;
    }
    return queue_depth - desired_depth + 1;
}

int anomaly_runtime_pressure_queue_storage_capacity(
        int current_capacity,
        int min_capacity,
        int initial_capacity,
        int hard_capacity) {
    if (hard_capacity <= 0) {
        return 0;
    }
    if (min_capacity < 1) {
        min_capacity = 1;
    }
    if (min_capacity > hard_capacity) {
        return 0;
    }
    if (current_capacity >= min_capacity) {
        return current_capacity;
    }

    int new_capacity = current_capacity;
    if (new_capacity < initial_capacity) {
        new_capacity = initial_capacity;
    }
    if (new_capacity < 1) {
        new_capacity = 1;
    }
    while (new_capacity < min_capacity) {
        if (new_capacity < 4096) {
            new_capacity *= 2;
        } else {
            new_capacity += 1024;
        }
    }
    if (new_capacity > hard_capacity) {
        new_capacity = hard_capacity;
    }
    return new_capacity >= min_capacity ? new_capacity : 0;
}

const char *anomaly_runtime_pressure_mode_name(
        anomaly_runtime_pressure_mode_t mode) {
    switch (mode) {
        case ANOMALY_RUNTIME_PRESSURE_MODE_NORMAL:
            return "normal";
        case ANOMALY_RUNTIME_PRESSURE_MODE_ANALYZE_ALTERNATE:
            return "analyze-alternate";
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALTERNATE:
            return "bypass-alternate";
        case ANOMALY_RUNTIME_PRESSURE_MODE_BYPASS_ALL:
            return "bypass-all";
        default:
            return "unknown";
    }
}

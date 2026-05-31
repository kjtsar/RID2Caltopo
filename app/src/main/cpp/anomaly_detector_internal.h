// Internal detector-facing names for the future standalone module boundary.
#pragma once

#include "anomaly_analysis.h"
#include "anomaly_frame.h"

typedef anomaly_state_t  anomaly_detector_state_t;
typedef anomaly_config_t anomaly_detector_config_t;
typedef anomaly_result_t anomaly_detector_result_t;

// Adapter-only responsibilities stay outside this contract:
// FFmpeg sessions, decode queues, RGBA conversion, overlay-frame cloning,
// Android lifecycle reset policy, JNI marshaling, and Kotlin persistence.
typedef struct {
    anomaly_detector_state_t        *state;
    const anomaly_frame_input_t     *frame;
    const anomaly_detector_config_t *config;
    anomaly_detector_result_t       *result_out;
} anomaly_detector_process_args_t;


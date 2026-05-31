#include "anomaly_detector.h"

void anomaly_detector_state_init(anomaly_detector_state_t *state) {
    anomaly_state_init(state);
}

void anomaly_detector_state_reset(anomaly_detector_state_t *state) {
    anomaly_state_reset(state);
}

void anomaly_detector_state_cleanup(anomaly_detector_state_t *state) {
    anomaly_state_cleanup(state);
}

int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out) {
    if (state == NULL ||
        frame == NULL ||
        frame->frame_format != ANOMALY_FRAME_FORMAT_RGBA8888 ||
        frame->rgba == NULL ||
        frame->rgba_stride <= 0 ||
        frame->width <= 0 ||
        frame->height <= 0) {
        int64_t source_ts_us = frame != NULL ? frame->source_timestamp_us : 0;
        return anomaly_process_frame(
                state,
                config,
                NULL,
                0,
                0,
                0,
                source_ts_us,
                result_out);
    }

    return anomaly_process_frame(
            state,
            config,
            frame->rgba,
            frame->rgba_stride,
            frame->width,
            frame->height,
            frame->source_timestamp_us,
            result_out);
}

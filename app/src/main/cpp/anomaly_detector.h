// Consumer-facing C facade for the anomaly detector module boundary.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include "anomaly_analysis.h"
#include "anomaly_frame.h"

typedef anomaly_state_t  anomaly_detector_state_t;
typedef anomaly_config_t anomaly_detector_config_t;
typedef anomaly_result_t anomaly_detector_result_t;

void anomaly_detector_state_init(anomaly_detector_state_t *state);
void anomaly_detector_state_reset(anomaly_detector_state_t *state);
void anomaly_detector_state_cleanup(anomaly_detector_state_t *state);

int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out);

#ifdef __cplusplus
}
#endif

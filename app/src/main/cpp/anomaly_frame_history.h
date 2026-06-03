#ifndef ANOMALY_FRAME_HISTORY_H
#define ANOMALY_FRAME_HISTORY_H

#include "anomaly_analysis.h"

#include <stddef.h>
#include <stdint.h>

void anomaly_frame_history_update_motion_luma(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h);

void anomaly_frame_history_update_registration_luma(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h);

void anomaly_frame_history_clear(anomaly_state_t *state);

#endif // ANOMALY_FRAME_HISTORY_H

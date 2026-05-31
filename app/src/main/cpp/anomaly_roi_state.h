#ifndef ANOMALY_ROI_STATE_H
#define ANOMALY_ROI_STATE_H

#include "anomaly_analysis.h"

#include <stdbool.h>
#include <stddef.h>

bool anomaly_roi_state_ensure_pixel_capacity(anomaly_roi_state_t *roi_state,
                                             size_t pixel_count);
bool anomaly_roi_state_ensure_cell_capacity(anomaly_roi_state_t *roi_state,
                                            size_t cell_count);
void anomaly_roi_state_summarize_cells(
        anomaly_roi_state_t *roi_state,
        const float         *motion_support_map,
        int                  carry_expiry_frames,
        float                registration_confidence);
void anomaly_roi_state_clear(anomaly_roi_state_t *roi_state);
void anomaly_roi_state_release(anomaly_roi_state_t *roi_state);

#endif // ANOMALY_ROI_STATE_H

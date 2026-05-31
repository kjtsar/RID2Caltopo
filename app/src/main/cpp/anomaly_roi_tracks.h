// Internal ROI track lifecycle helpers.
#pragma once

#include "anomaly_analysis.h"

void anomaly_roi_tracks_clear_saliency(anomaly_state_t *state);

void anomaly_roi_tracks_clear_all(anomaly_state_t *state);

void anomaly_roi_tracks_age_one_frame(anomaly_state_t *state);

#ifndef ANOMALY_SCRATCH_H
#define ANOMALY_SCRATCH_H

#include "anomaly_analysis.h"

#include <stdbool.h>
#include <stddef.h>

bool anomaly_scratch_ensure_sampled_grid_capacity(anomaly_state_t *state, size_t count);
bool anomaly_scratch_ensure_registration_luma_capacity(anomaly_state_t *state, size_t count);
bool anomaly_scratch_ensure_saliency_capacity(anomaly_state_t *state, size_t count);
bool anomaly_scratch_ensure_patch_capacity(anomaly_state_t *state, size_t count);
bool anomaly_scratch_ensure_prev_roi_snapshot_capacity(anomaly_state_t *state, size_t sample_count);

#endif // ANOMALY_SCRATCH_H

#ifndef ANOMALY_REGISTRATION_CACHE_H
#define ANOMALY_REGISTRATION_CACHE_H

#include "anomaly_analysis.h"
#include "anomaly_registration_model.h"

void anomaly_registration_cache_store(
        anomaly_state_t                     *state,
        const anomaly_registration_model_t  *model,
        anomaly_registration_health_t        registration_health,
        anomaly_rescan_mode_t                rescan_mode);

bool anomaly_registration_cache_try_load(
        anomaly_registration_model_t *model_out,
        anomaly_state_t              *state,
        int                           mode,
        int                           motion_sample_step,
        int                           motion_step,
        int                           motion_w,
        int                           motion_h);

#endif // ANOMALY_REGISTRATION_CACHE_H

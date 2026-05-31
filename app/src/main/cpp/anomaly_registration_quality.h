#ifndef ANOMALY_REGISTRATION_QUALITY_H
#define ANOMALY_REGISTRATION_QUALITY_H

#include "anomaly_analysis.h"
#include "anomaly_registration_model.h"

static inline float anomaly_registration_health_confidence(
        anomaly_registration_health_t health) {
    switch (health) {
        case ANOMALY_REG_HEALTH_HEALTHY:
            return 1.0f;
        case ANOMALY_REG_HEALTH_SOFT_DEGRADED:
            return 0.60f;
        case ANOMALY_REG_HEALTH_HARD_DEGRADED:
            return 0.25f;
        case ANOMALY_REG_HEALTH_INVALID:
            return 0.0f;
        case ANOMALY_REG_HEALTH_UNKNOWN:
        default:
            return 0.10f;
    }
}

static inline anomaly_registration_health_t anomaly_registration_classify_health(
        const anomaly_registration_model_t *model,
        int                                 width,
        int                                 height) {
    if (model == NULL || !model->debug_valid) {
        return ANOMALY_REG_HEALTH_UNKNOWN;
    }
    if (!anomaly_registration_model_valid(model) || model->scene_discontinuity) {
        return ANOMALY_REG_HEALTH_INVALID;
    }

    float scale = anomaly_registration_model_scale(model);
    float residual = model->similarity.mean_residual;
    bool motion_too_large = anomaly_registration_motion_exceeds_search(model, width, height, 0.70f);
    bool scale_far = scale < 0.80f || scale > 1.20f;
    bool scale_soft = scale < 0.90f || scale > 1.10f;

    if (motion_too_large ||
        scale_far ||
        residual > (ANOMALY_GMV_RESIDUAL_THRESH * 1.5f)) {
        return ANOMALY_REG_HEALTH_HARD_DEGRADED;
    }
    if (scale_soft || residual > (ANOMALY_GMV_RESIDUAL_THRESH * 0.75f)) {
        return ANOMALY_REG_HEALTH_SOFT_DEGRADED;
    }
    return ANOMALY_REG_HEALTH_HEALTHY;
}

#endif // ANOMALY_REGISTRATION_QUALITY_H

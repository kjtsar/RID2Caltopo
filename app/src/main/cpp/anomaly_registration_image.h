#ifndef ANOMALY_REGISTRATION_IMAGE_H
#define ANOMALY_REGISTRATION_IMAGE_H

#include <stdint.h>

void anomaly_registration_prefilter_luma_grid(
        const uint8_t *src,
        int            width,
        int            height,
        uint8_t       *tmp,
        uint8_t       *dst);

int anomaly_registration_feature_score(
        const uint8_t *luma,
        int            width,
        int            height,
        int            x,
        int            y);

#endif // ANOMALY_REGISTRATION_IMAGE_H

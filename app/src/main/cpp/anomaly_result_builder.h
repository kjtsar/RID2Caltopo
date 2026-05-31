#ifndef ANOMALY_RESULT_BUILDER_H
#define ANOMALY_RESULT_BUILDER_H

#include "anomaly_analysis.h"

int anomaly_result_build_boxes(
        const anomaly_state_t  *state,
        const anomaly_config_t *cfg,
        int                     motion_box_algorithm,
        anomaly_box_t          *boxes,
        int                     max_boxes);

#endif // ANOMALY_RESULT_BUILDER_H

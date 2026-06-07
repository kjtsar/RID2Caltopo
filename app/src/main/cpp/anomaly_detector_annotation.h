// Annotation publication helpers for the standalone detector facade.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

#include "anomaly_analysis.h"

typedef anomaly_result_t anomaly_detector_result_t;
typedef anomaly_box_t    anomaly_detector_annotation_t;

bool anomaly_detector_annotation_cadence_allows_update(
        int64_t frame_ordinal,
        int     cadence_frames);

typedef struct {
    bool initialized;
    bool annotations_visible;
    int64_t last_update_frame_ordinal;
} anomaly_detector_annotation_cadence_state_t;

void anomaly_detector_annotation_cadence_state_init(
        anomaly_detector_annotation_cadence_state_t *state);

bool anomaly_detector_annotation_cadence_update_visibility(
        anomaly_detector_annotation_cadence_state_t *state,
        bool                                        desired_visible,
        int64_t                                     frame_ordinal,
        int                                         cadence_frames);

typedef struct {
    const anomaly_detector_annotation_t *boxes;
    int box_count;
} anomaly_detector_annotation_view_t;

anomaly_detector_annotation_view_t anomaly_detector_result_annotations(
        const anomaly_detector_result_t *result);

typedef struct {
    anomaly_detector_annotation_cadence_state_t visibility;
    anomaly_detector_annotation_t boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int box_count;
} anomaly_detector_annotation_cadence_snapshot_state_t;

void anomaly_detector_annotation_cadence_snapshot_state_init(
        anomaly_detector_annotation_cadence_snapshot_state_t *state);

anomaly_detector_annotation_view_t anomaly_detector_annotation_cadence_snapshot_view(
        const anomaly_detector_annotation_cadence_snapshot_state_t *state);

anomaly_detector_annotation_view_t anomaly_detector_annotation_cadence_update_snapshot(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        anomaly_detector_annotation_view_t                    desired_annotations,
        int64_t                                               frame_ordinal,
        int                                                   cadence_frames);

anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_cadence(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    cadence_frames);

anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_visibility_cadence(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    cadence_frames);

#ifdef __cplusplus
}
#endif

// Consumer-facing C facade for the anomaly detector module boundary.
#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stdbool.h>
#include <stdint.h>

#include "anomaly_analysis.h"
#include "anomaly_frame.h"
#include "anomaly_runtime_budget.h"

typedef anomaly_state_t  anomaly_detector_state_t;
typedef anomaly_config_t anomaly_detector_config_t;

#include "anomaly_detector_annotation.h"

void anomaly_detector_state_init(anomaly_detector_state_t *state);
void anomaly_detector_state_reset(anomaly_detector_state_t *state);
void anomaly_detector_state_cleanup(anomaly_detector_state_t *state);

bool anomaly_detector_frame_input_ready(const anomaly_frame_input_t *frame);

int anomaly_detector_default_window_frames(float frame_rate_fps);

anomaly_detector_config_t anomaly_detector_config_make_realtime_default(
        int   algorithm_mask,
        float frame_rate_fps);

typedef struct {
    anomaly_detector_state_t        *state;
    const anomaly_frame_input_t     *frame;
    const anomaly_detector_config_t *config;
    anomaly_detector_result_t       *result_out;
} anomaly_detector_process_args_t;

anomaly_detector_process_args_t anomaly_detector_process_args_make(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out);

bool anomaly_detector_process_args_frame_ready(const anomaly_detector_process_args_t *args);

bool anomaly_detector_process_args_may_annotate_frame(const anomaly_detector_process_args_t *args);

typedef struct {
    uint8_t *rgba;
    int rgba_stride;
    int width;
    int height;
    int64_t source_timestamp_us;
    bool annotations_may_be_in_place;
} anomaly_detector_frame_output_t;

anomaly_detector_frame_output_t anomaly_detector_process_args_frame_output(
        const anomaly_detector_process_args_t *args);

typedef struct {
    anomaly_detector_frame_output_t frame;
    anomaly_detector_annotation_view_t annotations;
} anomaly_detector_process_output_t;

anomaly_detector_process_output_t anomaly_detector_process_output(
        const anomaly_detector_process_args_t *args,
        const anomaly_detector_result_t       *result);

anomaly_detector_process_output_t anomaly_detector_process_output_apply_annotation_cadence(
        anomaly_detector_process_output_t                       desired_output,
        anomaly_detector_annotation_cadence_snapshot_state_t    *snapshot_state,
        int64_t                                                 frame_ordinal,
        int                                                     cadence_frames);

anomaly_detector_process_output_t anomaly_detector_process_frame(
        const anomaly_detector_process_args_t *args,
        int                                   *box_count_out);

anomaly_detector_process_output_t anomaly_detector_process_frame_apply_annotation_cadence(
        const anomaly_detector_process_args_t               *args,
        anomaly_detector_annotation_cadence_snapshot_state_t *snapshot_state,
        int64_t                                             frame_ordinal,
        int                                                 cadence_frames,
        int                                                 *box_count_out);

anomaly_detector_process_output_t anomaly_detector_process_frame_input(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out,
        int                             *box_count_out);

typedef struct {
    anomaly_detector_state_t state;
    anomaly_detector_config_t config;
    anomaly_detector_result_t result;
    anomaly_detector_annotation_cadence_snapshot_state_t annotation_cadence;
    int64_t frame_ordinal;
    int cadence_frames;
    int last_box_count;
} anomaly_detector_runtime_t;

void anomaly_detector_runtime_init(
        anomaly_detector_runtime_t *runtime,
        int                         algorithm_mask,
        float                       frame_rate_fps);

void anomaly_detector_runtime_init_with_config(
        anomaly_detector_runtime_t        *runtime,
        const anomaly_detector_config_t   *config,
        int                                cadence_frames);

anomaly_config_transition_t anomaly_detector_runtime_apply_config(
        anomaly_detector_runtime_t        *runtime,
        const anomaly_detector_config_t   *config,
        int                                cadence_frames);

void anomaly_detector_runtime_cleanup(anomaly_detector_runtime_t *runtime);

typedef struct {
    anomaly_detector_process_output_t output;
    int64_t frame_ordinal;
    int raw_box_count;
    int stable_box_count;
    int cadence_frames;
    bool annotations_visible;
} anomaly_detector_runtime_process_result_t;

anomaly_detector_runtime_process_result_t anomaly_detector_runtime_process_frame_result(
        anomaly_detector_runtime_t  *runtime,
        const anomaly_frame_input_t *frame);

anomaly_detector_process_output_t anomaly_detector_runtime_process_frame(
        anomaly_detector_runtime_t  *runtime,
        const anomaly_frame_input_t *frame);

const anomaly_detector_config_t *anomaly_detector_runtime_config(
        const anomaly_detector_runtime_t *runtime);

const anomaly_detector_result_t *anomaly_detector_runtime_result(
        const anomaly_detector_runtime_t *runtime);

anomaly_detector_annotation_view_t anomaly_detector_runtime_stable_annotations(
        const anomaly_detector_runtime_t *runtime);

int64_t anomaly_detector_runtime_frame_ordinal(
        const anomaly_detector_runtime_t *runtime);

int anomaly_detector_runtime_cadence_frames(
        const anomaly_detector_runtime_t *runtime);

int anomaly_detector_runtime_last_box_count(
        const anomaly_detector_runtime_t *runtime);

int anomaly_detector_process_with_args(const anomaly_detector_process_args_t *args);

int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out);

#ifdef __cplusplus
}
#endif

#include "anomaly_detector.h"

void anomaly_detector_state_init(anomaly_detector_state_t *state) {
    anomaly_state_init(state);
}

void anomaly_detector_state_reset(anomaly_detector_state_t *state) {
    anomaly_state_reset(state);
}

void anomaly_detector_state_cleanup(anomaly_detector_state_t *state) {
    anomaly_state_cleanup(state);
}

bool anomaly_detector_frame_input_ready(const anomaly_frame_input_t *frame) {
    return frame != NULL &&
           frame->frame_format == ANOMALY_FRAME_FORMAT_RGBA8888 &&
           frame->rgba != NULL &&
           frame->rgba_stride > 0 &&
           frame->width > 0 &&
           frame->height > 0;
}

int anomaly_detector_default_window_frames(float frame_rate_fps) {
    float fps = frame_rate_fps;
    if (!(fps > 0.0f)) {
        fps = 30.0f;
    }
    int frames = (int)((fps * 0.5f) + 0.5f);
    return frames > 0 ? frames : 1;
}

int anomaly_detector_sparse_overlay_window_frames(int target_eval_interval_frames) {
    if (target_eval_interval_frames <= 1) {
        return 1;
    }
    const int max_sparse_window_frames = 8;
    return target_eval_interval_frames < max_sparse_window_frames
               ? target_eval_interval_frames
               : max_sparse_window_frames;
}

anomaly_detector_config_t anomaly_detector_config_make_realtime_default(
        int   algorithm_mask,
        float frame_rate_fps) {
    const int window_frames = anomaly_detector_default_window_frames(frame_rate_fps);
    anomaly_detector_config_t config = {
        .enabled = true,
        .show_hot_overlay = false,
        .show_candidate_blobs = false,
        .algorithm_mask = algorithm_mask,
        .registration_mode = ANOMALY_REGISTRATION_GMV,
        .movement_estimator_mode = ANOMALY_MOVEMENT_ESTIMATOR_LAYERED_ACTIVE,
        .stride_mode = ANOMALY_STRIDE_MODE_FIXED,
        .frame_stride = window_frames,
        .adaptive_min_stride_frames = 2,
        .adaptive_max_stride_frames = window_frames,
        .adaptive_max_stride_seconds = 0.5f,
        .pixel_step = 0,
        .score_threshold = ANOMALY_DEFAULT_SCORE_THRESHOLD,
        .motion_evidence_scale = 1.0f,
        .min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION,
        .thermal_polarity = ANOMALY_THERMAL_WHITE_HOT,
        .scan_zone = ANOMALY_SCAN_ZONE_DEFAULT,
        .min_hits = ANOMALY_DEFAULT_MIN_HITS,
        .thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA,
        .small_target_screen_fraction = ANOMALY_SMALL_TARGET_SCREEN_FRACTION_DEFAULT,
        .color_frontend_mode =
            (algorithm_mask & ANOMALY_ALGO_COLOR)
                ? ANOMALY_COLOR_FRONTEND_FRESH_RGBA
                : ANOMALY_COLOR_FRONTEND_LEGACY,
        .color_target_candidate_limit = 1,
        .target_color_family_mask = 0u,
        .thermal_debug_target_enabled = false,
        .thermal_debug_target_x_norm = 0.0f,
        .thermal_debug_target_y_norm = 0.0f,
        .color_debug_target_enabled = false,
        .color_debug_target_x_norm = 0.0f,
        .color_debug_target_y_norm = 0.0f,
    };
    return config;
}

anomaly_detector_process_args_t anomaly_detector_process_args_make(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out) {
    anomaly_detector_process_args_t args = {
        .state = state,
        .frame = frame,
        .config = config,
        .result_out = result_out,
    };
    return args;
}

bool anomaly_detector_process_args_frame_ready(const anomaly_detector_process_args_t *args) {
    return args != NULL &&
           args->state != NULL &&
           anomaly_detector_frame_input_ready(args->frame);
}

bool anomaly_detector_process_args_may_annotate_frame(const anomaly_detector_process_args_t *args) {
    if (!anomaly_detector_process_args_frame_ready(args) || args->config == NULL) {
        return false;
    }
    return args->config->show_hot_overlay || args->config->enabled;
}

anomaly_detector_frame_output_t anomaly_detector_process_args_frame_output(
        const anomaly_detector_process_args_t *args) {
    anomaly_detector_frame_output_t output = {
        .rgba = NULL,
        .rgba_stride = 0,
        .width = 0,
        .height = 0,
        .source_timestamp_us = 0,
        .annotations_may_be_in_place = false,
    };
    if (!anomaly_detector_process_args_frame_ready(args)) {
        return output;
    }
    output.rgba = args->frame->rgba;
    output.rgba_stride = args->frame->rgba_stride;
    output.width = args->frame->width;
    output.height = args->frame->height;
    output.source_timestamp_us = args->frame->source_timestamp_us;
    output.annotations_may_be_in_place =
        anomaly_detector_process_args_may_annotate_frame(args);
    return output;
}

anomaly_detector_process_output_t anomaly_detector_process_output(
        const anomaly_detector_process_args_t *args,
        const anomaly_detector_result_t       *result) {
    anomaly_detector_process_output_t output = {
        .frame = anomaly_detector_process_args_frame_output(args),
        .annotations = anomaly_detector_result_annotations(result),
    };
    return output;
}

anomaly_detector_process_output_t anomaly_detector_process_output_apply_annotation_cadence(
        anomaly_detector_process_output_t                       desired_output,
        anomaly_detector_annotation_cadence_snapshot_state_t    *snapshot_state,
        int64_t                                                 frame_ordinal,
        int                                                     cadence_frames) {
    desired_output.annotations = anomaly_detector_annotation_cadence_update_snapshot(
            snapshot_state,
            desired_output.annotations,
            frame_ordinal,
            cadence_frames);
    return desired_output;
}

anomaly_detector_process_output_t anomaly_detector_process_frame(
        const anomaly_detector_process_args_t *args,
        int                                   *box_count_out) {
    int box_count = anomaly_detector_process_with_args(args);
    if (box_count_out != NULL) {
        *box_count_out = box_count;
    }
    const anomaly_detector_result_t *result =
        args != NULL ? args->result_out : NULL;
    return anomaly_detector_process_output(args, result);
}

anomaly_detector_process_output_t anomaly_detector_process_frame_apply_annotation_cadence(
        const anomaly_detector_process_args_t               *args,
        anomaly_detector_annotation_cadence_snapshot_state_t *snapshot_state,
        int64_t                                             frame_ordinal,
        int                                                 cadence_frames,
        int                                                 *box_count_out) {
    anomaly_detector_process_output_t desired_output =
        anomaly_detector_process_frame(args, box_count_out);
    return anomaly_detector_process_output_apply_annotation_cadence(
            desired_output,
            snapshot_state,
            frame_ordinal,
            cadence_frames);
}

anomaly_detector_process_output_t anomaly_detector_process_frame_input(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out,
        int                             *box_count_out) {
    const anomaly_detector_process_args_t args =
        anomaly_detector_process_args_make(state, frame, config, result_out);
    return anomaly_detector_process_frame(&args, box_count_out);
}

static int anomaly_detector_runtime_normalize_cadence_frames(int cadence_frames) {
    return cadence_frames > 0 ? cadence_frames : 1;
}

static void anomaly_detector_runtime_clear_sequence_state(
        anomaly_detector_runtime_t *runtime) {
    if (runtime == NULL) {
        return;
    }
    runtime->result = (anomaly_detector_result_t){0};
    anomaly_detector_annotation_cadence_snapshot_state_init(&runtime->annotation_cadence);
    runtime->frame_ordinal = 0;
    runtime->last_box_count = 0;
}

void anomaly_detector_runtime_init(
        anomaly_detector_runtime_t *runtime,
        int                         algorithm_mask,
        float                       frame_rate_fps) {
    if (runtime == NULL) {
        return;
    }
    anomaly_detector_state_init(&runtime->state);
    runtime->config =
        anomaly_detector_config_make_realtime_default(algorithm_mask, frame_rate_fps);
    runtime->cadence_frames = anomaly_detector_default_window_frames(frame_rate_fps);
    anomaly_detector_runtime_clear_sequence_state(runtime);
}

void anomaly_detector_runtime_init_with_config(
        anomaly_detector_runtime_t        *runtime,
        const anomaly_detector_config_t   *config,
        int                                cadence_frames) {
    if (runtime == NULL || config == NULL) {
        return;
    }
    anomaly_detector_state_init(&runtime->state);
    runtime->config = *config;
    runtime->cadence_frames =
        anomaly_detector_runtime_normalize_cadence_frames(cadence_frames);
    anomaly_detector_runtime_clear_sequence_state(runtime);
}

anomaly_config_transition_t anomaly_detector_runtime_apply_config(
        anomaly_detector_runtime_t        *runtime,
        const anomaly_detector_config_t   *config,
        int                                cadence_frames) {
    if (runtime == NULL || config == NULL) {
        return ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE;
    }
    const anomaly_config_transition_t transition =
        anomaly_config_transition_classify(&runtime->config, config);
    runtime->config = *config;
    runtime->cadence_frames =
        anomaly_detector_runtime_normalize_cadence_frames(cadence_frames);
    if (transition == ANOMALY_CONFIG_TRANSITION_RESET_DETECTOR_STATE) {
        anomaly_detector_state_reset(&runtime->state);
        anomaly_detector_runtime_clear_sequence_state(runtime);
    }
    return transition;
}

void anomaly_detector_runtime_cleanup(anomaly_detector_runtime_t *runtime) {
    if (runtime == NULL) {
        return;
    }
    anomaly_detector_state_cleanup(&runtime->state);
}

static anomaly_detector_runtime_process_result_t
anomaly_detector_runtime_empty_process_result(void) {
    anomaly_detector_runtime_process_result_t result = {
        .output = anomaly_detector_process_output(NULL, NULL),
        .frame_ordinal = -1,
        .raw_box_count = 0,
        .stable_box_count = 0,
        .cadence_frames = 0,
        .annotations_visible = false,
    };
    return result;
}

anomaly_detector_runtime_process_result_t anomaly_detector_runtime_process_frame_result(
        anomaly_detector_runtime_t  *runtime,
        const anomaly_frame_input_t *frame) {
    if (runtime == NULL) {
        return anomaly_detector_runtime_empty_process_result();
    }
    const int64_t processed_ordinal = runtime->frame_ordinal;
    const anomaly_detector_process_args_t args =
        anomaly_detector_process_args_make(
                &runtime->state,
                frame,
                &runtime->config,
                &runtime->result);
    anomaly_detector_process_output_t output =
        anomaly_detector_process_frame_apply_annotation_cadence(
                &args,
                &runtime->annotation_cadence,
                processed_ordinal,
                runtime->cadence_frames,
                &runtime->last_box_count);
    runtime->frame_ordinal++;
    anomaly_detector_runtime_process_result_t result = {
        .output = output,
        .frame_ordinal = processed_ordinal,
        .raw_box_count = runtime->last_box_count,
        .stable_box_count = output.annotations.box_count,
        .cadence_frames = runtime->cadence_frames,
        .annotations_visible = output.annotations.box_count > 0,
    };
    return result;
}

anomaly_detector_process_output_t anomaly_detector_runtime_process_frame(
        anomaly_detector_runtime_t  *runtime,
        const anomaly_frame_input_t *frame) {
    return anomaly_detector_runtime_process_frame_result(runtime, frame).output;
}

const anomaly_detector_config_t *anomaly_detector_runtime_config(
        const anomaly_detector_runtime_t *runtime) {
    return runtime != NULL ? &runtime->config : NULL;
}

const anomaly_detector_result_t *anomaly_detector_runtime_result(
        const anomaly_detector_runtime_t *runtime) {
    return runtime != NULL ? &runtime->result : NULL;
}

anomaly_detector_annotation_view_t anomaly_detector_runtime_stable_annotations(
        const anomaly_detector_runtime_t *runtime) {
    return anomaly_detector_annotation_cadence_snapshot_view(
            runtime != NULL ? &runtime->annotation_cadence : NULL);
}

int64_t anomaly_detector_runtime_frame_ordinal(
        const anomaly_detector_runtime_t *runtime) {
    return runtime != NULL ? runtime->frame_ordinal : -1;
}

int anomaly_detector_runtime_cadence_frames(
        const anomaly_detector_runtime_t *runtime) {
    return runtime != NULL ? runtime->cadence_frames : 0;
}

int anomaly_detector_runtime_last_box_count(
        const anomaly_detector_runtime_t *runtime) {
    return runtime != NULL ? runtime->last_box_count : 0;
}

int anomaly_detector_process_with_args(const anomaly_detector_process_args_t *args) {
    anomaly_detector_state_t *state = args != NULL ? args->state : NULL;
    const anomaly_frame_input_t *frame = args != NULL ? args->frame : NULL;
    const anomaly_detector_config_t *config = args != NULL ? args->config : NULL;
    anomaly_detector_result_t *result_out = args != NULL ? args->result_out : NULL;

    if (!anomaly_detector_process_args_frame_ready(args)) {
        int64_t source_ts_us = frame != NULL ? frame->source_timestamp_us : 0;
        return anomaly_process_frame(
                state,
                config,
                NULL,
                0,
                0,
                0,
                source_ts_us,
                result_out);
    }

    return anomaly_process_frame(
            state,
            config,
            frame->rgba,
            frame->rgba_stride,
            frame->width,
            frame->height,
            frame->source_timestamp_us,
            result_out);
}

int anomaly_detector_process(
        anomaly_detector_state_t        *state,
        const anomaly_frame_input_t     *frame,
        const anomaly_detector_config_t *config,
        anomaly_detector_result_t       *result_out) {
    const anomaly_detector_process_args_t args =
        anomaly_detector_process_args_make(state, frame, config, result_out);
    return anomaly_detector_process_with_args(&args);
}

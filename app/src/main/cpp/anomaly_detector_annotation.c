#include "anomaly_detector_annotation.h"

#include <stddef.h>

bool anomaly_detector_annotation_cadence_allows_update(
        int64_t frame_ordinal,
        int     cadence_frames) {
    if (frame_ordinal < 0) {
        return false;
    }
    if (cadence_frames <= 1) {
        return true;
    }
    return (frame_ordinal % (int64_t)cadence_frames) == 0;
}

static bool anomaly_detector_annotation_cadence_elapsed_since_update(
        const anomaly_detector_annotation_cadence_state_t *state,
        int64_t                                           frame_ordinal,
        int                                               cadence_frames) {
    if (state == NULL || frame_ordinal < 0) {
        return false;
    }
    if (cadence_frames <= 1) {
        return true;
    }
    if (!state->initialized || state->last_update_frame_ordinal < 0) {
        return true;
    }
    return frame_ordinal - state->last_update_frame_ordinal >= (int64_t)cadence_frames;
}

void anomaly_detector_annotation_cadence_state_init(
        anomaly_detector_annotation_cadence_state_t *state) {
    if (state == NULL) {
        return;
    }
    state->initialized = false;
    state->annotations_visible = false;
    state->last_update_frame_ordinal = -1;
}

bool anomaly_detector_annotation_cadence_update_visibility(
        anomaly_detector_annotation_cadence_state_t *state,
        bool                                        desired_visible,
        int64_t                                     frame_ordinal,
        int                                         cadence_frames) {
    if (state == NULL || frame_ordinal < 0) {
        return desired_visible;
    }
    if (!state->initialized) {
        state->initialized = true;
        state->annotations_visible = desired_visible;
        state->last_update_frame_ordinal = frame_ordinal;
        return state->annotations_visible;
    }
    if (desired_visible == state->annotations_visible) {
        return state->annotations_visible;
    }
    if (!anomaly_detector_annotation_cadence_allows_update(frame_ordinal, cadence_frames)) {
        return state->annotations_visible;
    }
    state->annotations_visible = desired_visible;
    state->last_update_frame_ordinal = frame_ordinal;
    return state->annotations_visible;
}

void anomaly_detector_annotation_cadence_snapshot_state_init(
        anomaly_detector_annotation_cadence_snapshot_state_t *state) {
    if (state == NULL) {
        return;
    }
    anomaly_detector_annotation_cadence_state_init(&state->visibility);
    state->box_count = 0;
}

static anomaly_detector_annotation_view_t anomaly_detector_annotation_empty_view(void) {
    anomaly_detector_annotation_view_t view = {
        .boxes = NULL,
        .box_count = 0,
    };
    return view;
}

anomaly_detector_annotation_view_t anomaly_detector_annotation_cadence_snapshot_view(
        const anomaly_detector_annotation_cadence_snapshot_state_t *state) {
    if (state == NULL || state->box_count <= 0) {
        return anomaly_detector_annotation_empty_view();
    }
    anomaly_detector_annotation_view_t view = {
        .boxes = state->boxes,
        .box_count = state->box_count,
    };
    return view;
}

static anomaly_detector_annotation_view_t anomaly_detector_annotation_normalize_view(
        anomaly_detector_annotation_view_t view) {
    if (view.boxes == NULL || view.box_count <= 0) {
        return anomaly_detector_annotation_empty_view();
    }
    if (view.box_count > ANOMALY_MAX_BOXES_PER_FRAME) {
        view.box_count = ANOMALY_MAX_BOXES_PER_FRAME;
    }
    return view;
}

static void anomaly_detector_annotation_snapshot_copy(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        anomaly_detector_annotation_view_t                    source) {
    if (state == NULL) {
        return;
    }
    source = anomaly_detector_annotation_normalize_view(source);
    state->box_count = source.box_count;
    for (int i = 0; i < source.box_count; i++) {
        state->boxes[i] = source.boxes[i];
    }
}

static bool anomaly_detector_annotation_snapshot_contains_color(
        const anomaly_detector_annotation_cadence_snapshot_state_t *state) {
    if (state == NULL) {
        return false;
    }
    int count = state->box_count;
    if (count > ANOMALY_MAX_BOXES_PER_FRAME) {
        count = ANOMALY_MAX_BOXES_PER_FRAME;
    }
    for (int i = 0; i < count; i++) {
        if (state->boxes[i].algorithm == ANOMALY_ALGO_COLOR) {
            return true;
        }
    }
    return false;
}

static bool anomaly_detector_annotation_view_has_immediate_thermal(
        anomaly_detector_annotation_view_t view,
        int64_t                            frame_ordinal,
        int                                cadence_frames) {
    if (frame_ordinal < 0 || cadence_frames <= 1 || frame_ordinal < (int64_t)cadence_frames) {
        return false;
    }
    view = anomaly_detector_annotation_normalize_view(view);
    for (int i = 0; i < view.box_count; i++) {
        const anomaly_detector_annotation_t *box = &view.boxes[i];
        if (box->algorithm == ANOMALY_ALGO_THERMAL && box->weight >= 0.95f) {
            return true;
        }
    }
    return false;
}

anomaly_detector_annotation_view_t anomaly_detector_result_annotations(
        const anomaly_detector_result_t *result) {
    anomaly_detector_annotation_view_t view = {
        .boxes = NULL,
        .box_count = 0,
    };
    if (result == NULL || result->box_count <= 0) {
        return view;
    }
    view.boxes = result->boxes;
    view.box_count = result->box_count;
    if (view.box_count > ANOMALY_MAX_BOXES_PER_FRAME) {
        view.box_count = ANOMALY_MAX_BOXES_PER_FRAME;
    }
    return view;
}

anomaly_detector_annotation_view_t anomaly_detector_annotation_cadence_update_snapshot(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        anomaly_detector_annotation_view_t                    desired_annotations,
        int64_t                                               frame_ordinal,
        int                                                   cadence_frames) {
    desired_annotations = anomaly_detector_annotation_normalize_view(desired_annotations);
    if (state == NULL || frame_ordinal < 0) {
        return desired_annotations;
    }

    bool was_initialized = state->visibility.initialized;
    bool was_visible = state->visibility.annotations_visible;
    bool desired_visible = desired_annotations.box_count > 0;
    bool update_allowed =
        anomaly_detector_annotation_cadence_allows_update(frame_ordinal, cadence_frames);
    bool visible = anomaly_detector_annotation_cadence_update_visibility(
            &state->visibility,
            desired_visible,
            frame_ordinal,
            cadence_frames);

    if (!was_initialized ||
        (was_visible != visible &&
         state->visibility.last_update_frame_ordinal == frame_ordinal) ||
        (visible && desired_visible && update_allowed)) {
        if (visible) {
            state->visibility.last_update_frame_ordinal = frame_ordinal;
            anomaly_detector_annotation_snapshot_copy(state, desired_annotations);
        } else {
            state->box_count = 0;
        }
    }

    return visible ? anomaly_detector_annotation_cadence_snapshot_view(state)
                   : anomaly_detector_annotation_empty_view();
}

anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_cadence(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    cadence_frames) {
    return anomaly_detector_annotation_cadence_update_snapshot(
            snapshot_state,
            anomaly_detector_result_annotations(result),
            frame_ordinal,
            cadence_frames);
}

anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_visibility_cadence(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    cadence_frames) {
    anomaly_detector_annotation_view_t desired_annotations =
            anomaly_detector_result_annotations(result);
    desired_annotations = anomaly_detector_annotation_normalize_view(desired_annotations);
    if (snapshot_state == NULL || frame_ordinal < 0) {
        return desired_annotations;
    }

    bool desired_visible = desired_annotations.box_count > 0;
    if (!desired_visible &&
        snapshot_state->visibility.annotations_visible &&
        anomaly_detector_annotation_snapshot_contains_color(snapshot_state)) {
        snapshot_state->visibility.initialized = true;
        snapshot_state->visibility.annotations_visible = false;
        snapshot_state->visibility.last_update_frame_ordinal = frame_ordinal;
        snapshot_state->box_count = 0;
        return anomaly_detector_annotation_empty_view();
    }
    if (desired_visible &&
        snapshot_state->visibility.initialized &&
        !snapshot_state->visibility.annotations_visible &&
        !anomaly_detector_annotation_cadence_allows_update(frame_ordinal, cadence_frames) &&
        anomaly_detector_annotation_cadence_elapsed_since_update(
                &snapshot_state->visibility,
                frame_ordinal,
                cadence_frames) &&
        anomaly_detector_annotation_view_has_immediate_thermal(
                desired_annotations,
                frame_ordinal,
                cadence_frames)) {
        snapshot_state->visibility.annotations_visible = true;
        snapshot_state->visibility.last_update_frame_ordinal = frame_ordinal;
        anomaly_detector_annotation_snapshot_copy(snapshot_state, desired_annotations);
        return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
    }
    if (desired_visible &&
        snapshot_state->visibility.initialized &&
        snapshot_state->visibility.annotations_visible &&
        !anomaly_detector_annotation_cadence_allows_update(frame_ordinal, cadence_frames) &&
        anomaly_detector_annotation_view_has_immediate_thermal(
                desired_annotations,
                frame_ordinal,
                cadence_frames)) {
        anomaly_detector_annotation_snapshot_copy(snapshot_state, desired_annotations);
        return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
    }
    return anomaly_detector_annotation_cadence_update_snapshot(
            snapshot_state,
            desired_annotations,
            frame_ordinal,
            cadence_frames);
}

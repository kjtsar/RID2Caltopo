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

static float anomaly_detector_annotation_lerpf(float previous, float current, float alpha) {
    return previous + (current - previous) * alpha;
}

static float anomaly_detector_annotation_box_center_x(const anomaly_detector_annotation_t *box) {
    return box != NULL ? (box->left_norm + box->right_norm) * 0.5f : 0.0f;
}

static float anomaly_detector_annotation_box_center_y(const anomaly_detector_annotation_t *box) {
    return box != NULL ? (box->top_norm + box->bottom_norm) * 0.5f : 0.0f;
}

static bool anomaly_detector_annotation_box_should_smooth(
        const anomaly_detector_annotation_t *previous,
        const anomaly_detector_annotation_t *current) {
    if (previous == NULL || current == NULL) {
        return false;
    }
    if (previous->algorithm != current->algorithm) {
        return false;
    }
    float dx = anomaly_detector_annotation_box_center_x(current) -
               anomaly_detector_annotation_box_center_x(previous);
    float dy = anomaly_detector_annotation_box_center_y(current) -
               anomaly_detector_annotation_box_center_y(previous);
    return (dx * dx + dy * dy) <= (0.12f * 0.12f);
}

static anomaly_detector_annotation_t anomaly_detector_annotation_box_smooth(
        anomaly_detector_annotation_t previous,
        anomaly_detector_annotation_t current) {
    if (!anomaly_detector_annotation_box_should_smooth(&previous, &current)) {
        return current;
    }
    const float alpha = 0.35f;
    current.left_norm = anomaly_detector_annotation_lerpf(previous.left_norm, current.left_norm, alpha);
    current.top_norm = anomaly_detector_annotation_lerpf(previous.top_norm, current.top_norm, alpha);
    current.right_norm = anomaly_detector_annotation_lerpf(previous.right_norm, current.right_norm, alpha);
    current.bottom_norm = anomaly_detector_annotation_lerpf(previous.bottom_norm, current.bottom_norm, alpha);
    current.weight = anomaly_detector_annotation_lerpf(previous.weight, current.weight, alpha);
    return current;
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
    bool visible = anomaly_detector_annotation_cadence_update_visibility(
            &snapshot_state->visibility,
            desired_visible,
            frame_ordinal,
            cadence_frames);
    if (!visible) {
        snapshot_state->box_count = 0;
        return anomaly_detector_annotation_empty_view();
    }
    if (desired_visible) {
        anomaly_detector_annotation_t previous_boxes[ANOMALY_MAX_BOXES_PER_FRAME];
        int previous_count = snapshot_state->box_count;
        if (previous_count > ANOMALY_MAX_BOXES_PER_FRAME) {
            previous_count = ANOMALY_MAX_BOXES_PER_FRAME;
        }
        for (int i = 0; i < previous_count; i++) {
            previous_boxes[i] = snapshot_state->boxes[i];
        }
        anomaly_detector_annotation_snapshot_copy(snapshot_state, desired_annotations);
        for (int i = 0; i < snapshot_state->box_count && i < previous_count; i++) {
            snapshot_state->boxes[i] = anomaly_detector_annotation_box_smooth(
                    previous_boxes[i],
                    snapshot_state->boxes[i]);
        }
    }
    return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
}

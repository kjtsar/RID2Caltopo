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
    state->last_desired_frame_ordinal = -1;
    state->stability_last_frame_ordinal = -1;
    for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
        state->stable_slots[i] = (anomaly_detector_annotation_stability_slot_t){0};
        state->stable_slots[i].last_seen_frame_ordinal = -1;
        state->stable_slots[i].published_until_frame_ordinal = -1;
    }
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

static int anomaly_detector_annotation_stability_window_frames(int window_frames) {
    if (window_frames <= 0) {
        return 1;
    }
    if (window_frames > 64) {
        return 64;
    }
    return window_frames;
}

static uint64_t anomaly_detector_annotation_stability_window_mask(int window_frames) {
    if (window_frames >= 64) {
        return UINT64_MAX;
    }
    return (UINT64_C(1) << (uint64_t)window_frames) - UINT64_C(1);
}

static int anomaly_detector_annotation_stability_required_hits(int window_frames) {
    int required = window_frames / 2 + 1;
    if (required < 3) {
        required = 3;
    }
    return required;
}

static int anomaly_detector_annotation_disappearance_hold_frames(
        int algorithm,
        int window_frames) {
    if (algorithm != ANOMALY_ALGO_COLOR) {
        return window_frames;
    }
    // Color targets can move noticeably while occluded, so cap the stale-box
    // interval while retaining enough history to suppress single-frame flicker.
    int hold_frames = (window_frames + 1) / 2;
    if (hold_frames < 3) hold_frames = 3;
    if (hold_frames > window_frames) hold_frames = window_frames;
    return hold_frames;
}

static int anomaly_detector_annotation_snapshot_disappearance_hold_frames(
        const anomaly_detector_annotation_cadence_snapshot_state_t *state,
        int                                                         window_frames) {
    if (state == NULL || state->box_count <= 0) {
        return window_frames;
    }
    for (int i = 0; i < state->box_count; i++) {
        if (state->boxes[i].algorithm != ANOMALY_ALGO_COLOR) {
            return window_frames;
        }
    }
    // Stability already owns Color's disappearance hold. Do not apply the
    // publication cadence a second time when its stabilized input goes empty.
    return 1;
}

static int anomaly_detector_annotation_popcount_u64(uint64_t v) {
    int count = 0;
    while (v != 0) {
        count += (int)(v & UINT64_C(1));
        v >>= 1;
    }
    return count;
}

static float anomaly_detector_annotation_center_x(
        const anomaly_detector_annotation_t *box) {
    return box != NULL ? (box->left_norm + box->right_norm) * 0.5f : 0.0f;
}

static float anomaly_detector_annotation_center_y(
        const anomaly_detector_annotation_t *box) {
    return box != NULL ? (box->top_norm + box->bottom_norm) * 0.5f : 0.0f;
}

static float anomaly_detector_annotation_center_distance_sq(
        const anomaly_detector_annotation_t *a,
        const anomaly_detector_annotation_t *b) {
    const float dx =
        anomaly_detector_annotation_center_x(a) -
        anomaly_detector_annotation_center_x(b);
    const float dy =
        anomaly_detector_annotation_center_y(a) -
        anomaly_detector_annotation_center_y(b);
    return dx * dx + dy * dy;
}

static void anomaly_detector_annotation_stability_age_slots(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        int64_t                                               frame_ordinal,
        int                                                   window_frames) {
    if (state == NULL || frame_ordinal < 0) {
        return;
    }
    int64_t delta = state->stability_last_frame_ordinal < 0
                        ? 0
                        : frame_ordinal - state->stability_last_frame_ordinal;
    if (delta < 0) {
        for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
            state->stable_slots[i] =
                (anomaly_detector_annotation_stability_slot_t){0};
            state->stable_slots[i].last_seen_frame_ordinal = -1;
            state->stable_slots[i].published_until_frame_ordinal = -1;
        }
        state->box_count = 0;
        delta = 0;
    }
    if (delta > 0) {
        const uint64_t window_mask =
            anomaly_detector_annotation_stability_window_mask(window_frames);
        for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
            anomaly_detector_annotation_stability_slot_t *slot =
                &state->stable_slots[i];
            if (!slot->active) {
                continue;
            }
            if (delta >= 64) {
                slot->hit_mask = 0;
            } else {
                slot->hit_mask = (slot->hit_mask << (uint64_t)delta) & window_mask;
            }
            if (slot->hit_mask == 0 ||
                frame_ordinal - slot->last_seen_frame_ordinal >=
                    (int64_t)window_frames) {
                const bool still_published =
                    slot->published_until_frame_ordinal >= frame_ordinal;
                if (still_published) {
                    continue;
                }
                *slot = (anomaly_detector_annotation_stability_slot_t){0};
                slot->last_seen_frame_ordinal = -1;
                slot->published_until_frame_ordinal = -1;
            }
        }
    }
    state->stability_last_frame_ordinal = frame_ordinal;
}

static int anomaly_detector_annotation_find_stability_slot(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        const anomaly_detector_annotation_t                  *box) {
    if (state == NULL || box == NULL) {
        return -1;
    }
    const float max_distance_sq = 0.12f * 0.12f;
    int best_idx = -1;
    float best_distance_sq = max_distance_sq;
    for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
        anomaly_detector_annotation_stability_slot_t *slot =
            &state->stable_slots[i];
        if (!slot->active || slot->algorithm != box->algorithm) {
            continue;
        }
        const float distance_sq =
            anomaly_detector_annotation_center_distance_sq(&slot->box, box);
        if (distance_sq <= best_distance_sq) {
            best_distance_sq = distance_sq;
            best_idx = i;
        }
    }
    if (best_idx >= 0) {
        return best_idx;
    }
    for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
        if (!state->stable_slots[i].active) {
            return i;
        }
    }
    int weakest_idx = 0;
    int weakest_hits =
        anomaly_detector_annotation_popcount_u64(state->stable_slots[0].hit_mask);
    for (int i = 1; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
        const int hits =
            anomaly_detector_annotation_popcount_u64(state->stable_slots[i].hit_mask);
        if (hits < weakest_hits ||
            (hits == weakest_hits &&
             state->stable_slots[i].box.weight < state->stable_slots[weakest_idx].box.weight)) {
            weakest_idx = i;
            weakest_hits = hits;
        }
    }
    return weakest_idx;
}

static void anomaly_detector_annotation_record_stability_observation(
        anomaly_detector_annotation_cadence_snapshot_state_t *state,
        const anomaly_detector_annotation_t                  *box,
        int64_t                                               frame_ordinal) {
    const int slot_idx = anomaly_detector_annotation_find_stability_slot(state, box);
    if (slot_idx < 0) {
        return;
    }
    anomaly_detector_annotation_stability_slot_t *slot =
        &state->stable_slots[slot_idx];
    if (!slot->active || slot->algorithm != box->algorithm) {
        *slot = (anomaly_detector_annotation_stability_slot_t){0};
        slot->active = true;
        slot->initialized = true;
        slot->algorithm = box->algorithm;
        slot->last_seen_frame_ordinal = -1;
        slot->published_until_frame_ordinal = -1;
    }
    slot->hit_mask |= UINT64_C(1);
    slot->last_seen_frame_ordinal = frame_ordinal;
    slot->box = *box;
}

static bool anomaly_detector_annotation_stability_slot_meets_threshold(
        const anomaly_detector_annotation_stability_slot_t *slot,
        int                                                required_hits) {
    if (slot == NULL || !slot->active || slot->hit_mask == 0) {
        return false;
    }
    return anomaly_detector_annotation_popcount_u64(slot->hit_mask) >= required_hits;
}

static bool anomaly_detector_annotation_stability_slot_eligible(
        const anomaly_detector_annotation_stability_slot_t *slot,
        int64_t                                            frame_ordinal) {
    return slot != NULL &&
           slot->active &&
           slot->published_until_frame_ordinal >= frame_ordinal;
}

static bool anomaly_detector_annotation_slot_stronger(
        const anomaly_detector_annotation_stability_slot_t *lhs,
        const anomaly_detector_annotation_stability_slot_t *rhs) {
    if (rhs == NULL) {
        return true;
    }
    if (lhs == NULL) {
        return false;
    }
    if (lhs->box.weight != rhs->box.weight) {
        return lhs->box.weight > rhs->box.weight;
    }
    return anomaly_detector_annotation_popcount_u64(lhs->hit_mask) >
           anomaly_detector_annotation_popcount_u64(rhs->hit_mask);
}

static int anomaly_detector_annotation_find_matching_eligible_slot(
        const anomaly_detector_annotation_t                   *box,
        const anomaly_detector_annotation_stability_slot_t    **eligible,
        const bool                                             *used,
        int                                                     eligible_count) {
    if (box == NULL || eligible == NULL || used == NULL) {
        return -1;
    }
    const float max_distance_sq = 0.12f * 0.12f;
    int best_idx = -1;
    float best_distance_sq = max_distance_sq;
    for (int i = 0; i < eligible_count; i++) {
        const anomaly_detector_annotation_stability_slot_t *slot = eligible[i];
        if (used[i] || slot == NULL || slot->algorithm != box->algorithm) {
            continue;
        }
        const float distance_sq =
            anomaly_detector_annotation_center_distance_sq(&slot->box, box);
        if (distance_sq <= best_distance_sq) {
            best_distance_sq = distance_sq;
            best_idx = i;
        }
    }
    return best_idx;
}

static anomaly_detector_annotation_t anomaly_detector_annotation_smooth_published_box(
        const anomaly_detector_annotation_t *previous,
        const anomaly_detector_annotation_t *current) {
    if (previous == NULL || current == NULL) {
        return current != NULL ? *current : (anomaly_detector_annotation_t){0};
    }
    const float alpha = 0.65f;
    anomaly_detector_annotation_t out = *current;
    out.left_norm = previous->left_norm + alpha * (current->left_norm - previous->left_norm);
    out.top_norm = previous->top_norm + alpha * (current->top_norm - previous->top_norm);
    out.right_norm = previous->right_norm + alpha * (current->right_norm - previous->right_norm);
    out.bottom_norm = previous->bottom_norm + alpha * (current->bottom_norm - previous->bottom_norm);
    out.weight = current->weight;
    return out;
}

anomaly_detector_annotation_view_t anomaly_detector_result_apply_annotation_stability(
        const anomaly_detector_result_t                       *result,
        anomaly_detector_annotation_cadence_snapshot_state_t   *snapshot_state,
        int64_t                                                frame_ordinal,
        int                                                    window_frames) {
    anomaly_detector_annotation_view_t desired_annotations =
            anomaly_detector_result_annotations(result);
    desired_annotations = anomaly_detector_annotation_normalize_view(desired_annotations);
    if (snapshot_state == NULL || frame_ordinal < 0) {
        return desired_annotations;
    }

    const int normalized_window_frames =
        anomaly_detector_annotation_stability_window_frames(window_frames);
    const int required_hits =
        anomaly_detector_annotation_stability_required_hits(normalized_window_frames);
    anomaly_detector_annotation_t previous_boxes[ANOMALY_MAX_BOXES_PER_FRAME];
    int previous_box_count = snapshot_state->box_count;
    if (previous_box_count < 0) {
        previous_box_count = 0;
    }
    if (previous_box_count > ANOMALY_MAX_BOXES_PER_FRAME) {
        previous_box_count = ANOMALY_MAX_BOXES_PER_FRAME;
    }
    for (int i = 0; i < previous_box_count; i++) {
        previous_boxes[i] = snapshot_state->boxes[i];
    }
    anomaly_detector_annotation_stability_age_slots(
            snapshot_state,
            frame_ordinal,
            normalized_window_frames);

    for (int i = 0; i < desired_annotations.box_count; i++) {
        anomaly_detector_annotation_record_stability_observation(
                snapshot_state,
                &desired_annotations.boxes[i],
                frame_ordinal);
    }

    const anomaly_detector_annotation_stability_slot_t *eligible
        [ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS];
    int eligible_count = 0;
    for (int i = 0; i < ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS; i++) {
        const anomaly_detector_annotation_stability_slot_t *slot =
            &snapshot_state->stable_slots[i];
        if (slot->last_seen_frame_ordinal == frame_ordinal &&
            anomaly_detector_annotation_stability_slot_meets_threshold(slot, required_hits)) {
            anomaly_detector_annotation_stability_slot_t *mutable_slot =
                &snapshot_state->stable_slots[i];
            const int hold_frames =
                anomaly_detector_annotation_disappearance_hold_frames(
                        mutable_slot->algorithm,
                        normalized_window_frames);
            const int64_t publish_until =
                frame_ordinal + (int64_t)hold_frames - 1;
            if (mutable_slot->published_until_frame_ordinal < publish_until) {
                mutable_slot->published_until_frame_ordinal = publish_until;
            }
        }
        if (!anomaly_detector_annotation_stability_slot_eligible(slot, frame_ordinal)) {
            continue;
        }
        eligible[eligible_count++] = slot;
    }
    for (int i = 0; i < eligible_count; i++) {
        for (int j = i + 1; j < eligible_count; j++) {
            if (anomaly_detector_annotation_slot_stronger(eligible[j], eligible[i])) {
                const anomaly_detector_annotation_stability_slot_t *tmp = eligible[i];
                eligible[i] = eligible[j];
                eligible[j] = tmp;
            }
        }
    }

    snapshot_state->box_count = 0;
    bool used[ANOMALY_DETECTOR_MAX_STABLE_ANNOTATION_SLOTS] = {0};
    for (int i = 0;
         i < previous_box_count && snapshot_state->box_count < ANOMALY_MAX_BOXES_PER_FRAME;
         i++) {
        int eligible_idx = anomaly_detector_annotation_find_matching_eligible_slot(
                &previous_boxes[i],
                eligible,
                used,
                eligible_count);
        if (eligible_idx < 0) {
            continue;
        }
        snapshot_state->boxes[snapshot_state->box_count++] =
            anomaly_detector_annotation_smooth_published_box(
                    &previous_boxes[i],
                    &eligible[eligible_idx]->box);
        used[eligible_idx] = true;
    }
    for (int i = 0; i < eligible_count &&
                    snapshot_state->box_count < ANOMALY_MAX_BOXES_PER_FRAME;
         i++) {
        if (used[i]) {
            continue;
        }
        snapshot_state->boxes[snapshot_state->box_count] = eligible[i]->box;
        snapshot_state->box_count += 1;
    }
    snapshot_state->visibility.initialized = true;
    snapshot_state->visibility.annotations_visible = snapshot_state->box_count > 0;
    snapshot_state->visibility.last_update_frame_ordinal = frame_ordinal;

    return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
}

anomaly_detector_annotation_view_t anomaly_detector_annotation_publish_on_elapsed_cadence(
        anomaly_detector_annotation_view_t                    desired_annotations,
        anomaly_detector_annotation_cadence_snapshot_state_t  *snapshot_state,
        int64_t                                               frame_ordinal,
        int                                                   cadence_frames) {
    desired_annotations = anomaly_detector_annotation_normalize_view(desired_annotations);
    if (snapshot_state == NULL || frame_ordinal < 0) {
        return desired_annotations;
    }
    if (desired_annotations.box_count > 0) {
        anomaly_detector_annotation_snapshot_copy(snapshot_state, desired_annotations);
        snapshot_state->last_desired_frame_ordinal = frame_ordinal;
        snapshot_state->visibility.initialized = true;
        snapshot_state->visibility.annotations_visible = true;
        snapshot_state->visibility.last_update_frame_ordinal = frame_ordinal;
        return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
    }

    const int disappearance_hold_frames =
        anomaly_detector_annotation_snapshot_disappearance_hold_frames(
                snapshot_state,
                cadence_frames);
    if (snapshot_state->visibility.initialized &&
        snapshot_state->visibility.annotations_visible &&
        snapshot_state->box_count > 0 &&
        snapshot_state->last_desired_frame_ordinal >= 0 &&
        frame_ordinal - snapshot_state->last_desired_frame_ordinal <
            (int64_t)disappearance_hold_frames) {
        return anomaly_detector_annotation_cadence_snapshot_view(snapshot_state);
    }

    snapshot_state->box_count = 0;
    snapshot_state->visibility.initialized = true;
    snapshot_state->visibility.annotations_visible = false;
    snapshot_state->visibility.last_update_frame_ordinal = frame_ordinal;
    return anomaly_detector_annotation_empty_view();
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

#include "anomaly_scratch.h"

#include "anomaly_buffer.h"

#include <stdlib.h>

bool anomaly_scratch_ensure_sampled_grid_capacity(anomaly_state_t *state, size_t count) {
    if (state == NULL) return false;
    if (count == 0) return true;
    if (state->scratch_sg_luma != NULL &&
        state->scratch_ii_sum != NULL &&
        state->scratch_ii_sum2 != NULL &&
        state->scratch_sampled_grid_capacity >= count) {
        return true;
    }

    float *sg_luma = (float *)realloc(state->scratch_sg_luma, count * sizeof(float));
    if (sg_luma == NULL) return false;
    state->scratch_sg_luma = sg_luma;

    float *ii_sum = (float *)realloc(state->scratch_ii_sum, count * sizeof(float));
    if (ii_sum == NULL) return false;
    state->scratch_ii_sum = ii_sum;

    float *ii_sum2 = (float *)realloc(state->scratch_ii_sum2, count * sizeof(float));
    if (ii_sum2 == NULL) return false;
    state->scratch_ii_sum2 = ii_sum2;

    state->scratch_sampled_grid_capacity = count;
    return true;
}

bool anomaly_scratch_ensure_registration_luma_capacity(anomaly_state_t *state, size_t count) {
    if (state == NULL) return false;
    if (count == 0) return true;
    if (state->scratch_registration_luma != NULL &&
        state->scratch_registration_tmp != NULL &&
        state->scratch_registration_luma_capacity >= count) {
        return true;
    }
    if (!anomaly_buffer_resize_u8(&state->scratch_registration_luma, count) ||
        !anomaly_buffer_resize_u8(&state->scratch_registration_tmp, count)) {
        return false;
    }
    state->scratch_registration_luma_capacity = count;
    return true;
}

bool anomaly_scratch_ensure_saliency_capacity(anomaly_state_t *state, size_t count) {
    if (state == NULL) return false;
    if (count == 0) return true;
    if (state->scratch_saliency_spatial != NULL &&
        state->scratch_saliency_color != NULL &&
        state->scratch_saliency_motion != NULL &&
        state->scratch_saliency_registration != NULL &&
        state->scratch_thermal_delta != NULL &&
        state->scratch_saliency_capacity >= count) {
        return true;
    }
    if (!anomaly_buffer_resize_float(&state->scratch_saliency_spatial, count) ||
        !anomaly_buffer_resize_float(&state->scratch_saliency_color, count) ||
        !anomaly_buffer_resize_float(&state->scratch_saliency_motion, count) ||
        !anomaly_buffer_resize_float(&state->scratch_saliency_registration, count) ||
        !anomaly_buffer_resize_float(&state->scratch_thermal_delta, count)) {
        return false;
    }
    state->scratch_saliency_capacity = count;
    return true;
}

bool anomaly_scratch_ensure_patch_capacity(anomaly_state_t *state, size_t count) {
    if (state == NULL) return false;
    if (count == 0) return true;
    if (state->scratch_patch_score != NULL &&
        state->scratch_patch_selection != NULL &&
        state->scratch_patch_capacity >= count) {
        return true;
    }
    if (!anomaly_buffer_resize_float(&state->scratch_patch_score, count) ||
        !anomaly_buffer_resize_float(&state->scratch_patch_selection, count)) {
        return false;
    }
    state->scratch_patch_capacity = count;
    return true;
}

bool anomaly_scratch_ensure_prev_roi_snapshot_capacity(anomaly_state_t *state, size_t sample_count) {
    if (state == NULL) return false;
    if (sample_count == 0) return true;
    if (state->scratch_prev_roi_capacity >= sample_count &&
        state->scratch_prev_roi_last_luma != NULL &&
        state->scratch_prev_roi_thermal_score != NULL &&
        state->scratch_prev_roi_temporal_score != NULL &&
        state->scratch_prev_roi_color_luma != NULL &&
        state->scratch_prev_roi_color_u != NULL &&
        state->scratch_prev_roi_color_v != NULL &&
        state->scratch_prev_roi_color_raw_score != NULL &&
        state->scratch_prev_roi_color_contrast_weight != NULL &&
        state->scratch_prev_roi_color_u_bin != NULL &&
        state->scratch_prev_roi_color_v_bin != NULL &&
        state->scratch_prev_roi_valid_mask != NULL &&
        state->scratch_prev_roi_coverage_age != NULL &&
        state->scratch_prev_roi_color_valid_mask != NULL &&
        state->scratch_prev_roi_color_phase_x != NULL &&
        state->scratch_prev_roi_color_phase_y != NULL) {
        return true;
    }
    return
        anomaly_buffer_resize_float(&state->scratch_prev_roi_last_luma, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_thermal_score, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_temporal_score, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_color_luma, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_color_u, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_color_v, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_color_raw_score, sample_count) &&
        anomaly_buffer_resize_float(&state->scratch_prev_roi_color_contrast_weight, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_color_u_bin, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_color_v_bin, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_valid_mask, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_coverage_age, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_color_valid_mask, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_color_phase_x, sample_count) &&
        anomaly_buffer_resize_u8(&state->scratch_prev_roi_color_phase_y, sample_count) &&
        ((state->scratch_prev_roi_capacity = sample_count), true);
}

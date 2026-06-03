#include "anomaly_frame_history.h"

#include "anomaly_buffer.h"

#include <stdlib.h>
#include <string.h>

static void anomaly_frame_history_update_u8(
        uint8_t       **buffer,
        size_t         *capacity,
        int            *width_out,
        int            *height_out,
        const uint8_t  *curr_luma,
        size_t          motion_count,
        int             motion_w,
        int             motion_h) {
    if (buffer == NULL || capacity == NULL || width_out == NULL || height_out == NULL ||
        curr_luma == NULL) {
        return;
    }
    if (anomaly_buffer_ensure_u8_capacity(buffer, capacity, motion_count)) {
        memcpy(*buffer, curr_luma, motion_count * sizeof(uint8_t));
        *width_out = motion_w;
        *height_out = motion_h;
    } else {
        if (*buffer != NULL) {
            free(*buffer);
            *buffer = NULL;
        }
        *capacity = 0;
        *width_out = 0;
        *height_out = 0;
    }
}

static void anomaly_frame_history_clear_u8(
        uint8_t **buffer,
        size_t   *capacity,
        int      *width_out,
        int      *height_out) {
    if (buffer == NULL || capacity == NULL || width_out == NULL || height_out == NULL) {
        return;
    }
    if (*buffer != NULL) {
        free(*buffer);
        *buffer = NULL;
    }
    *capacity = 0;
    *width_out = 0;
    *height_out = 0;
}

void anomaly_frame_history_update_motion_luma(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h) {
    if (state == NULL || curr_luma == NULL) return;
    anomaly_frame_history_update_u8(
            &state->prev_luma,
            &state->prev_luma_capacity,
            &state->prev_luma_width,
            &state->prev_luma_height,
            curr_luma,
            motion_count,
            motion_w,
            motion_h);
}

void anomaly_frame_history_update_registration_luma(
        anomaly_state_t *state,
        const uint8_t   *curr_luma,
        size_t           motion_count,
        int              motion_w,
        int              motion_h) {
    if (state == NULL || curr_luma == NULL) return;
    anomaly_frame_history_update_u8(
            &state->prev_registration_luma,
            &state->prev_registration_luma_capacity,
            &state->prev_registration_luma_width,
            &state->prev_registration_luma_height,
            curr_luma,
            motion_count,
            motion_w,
            motion_h);
}

void anomaly_frame_history_clear(anomaly_state_t *state) {
    if (state == NULL) return;
    anomaly_frame_history_clear_u8(
            &state->prev_luma,
            &state->prev_luma_capacity,
            &state->prev_luma_width,
            &state->prev_luma_height);
    anomaly_frame_history_clear_u8(
            &state->prev_registration_luma,
            &state->prev_registration_luma_capacity,
            &state->prev_registration_luma_width,
            &state->prev_registration_luma_height);
}

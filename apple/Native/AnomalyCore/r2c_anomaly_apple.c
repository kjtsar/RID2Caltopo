#include "r2c_anomaly_apple.h"

#include "anomaly_detector.h"

#include <stdlib.h>
#include <string.h>

struct R2CAnomalyRuntime {
    anomaly_detector_runtime_t detector;
    uint8_t *rgba;
    size_t rgba_capacity;
};

R2CAnomalyRuntime *R2CAnomalyCreate(int32_t algorithm_mask, float frame_rate_fps) {
    R2CAnomalyRuntime *runtime = calloc(1, sizeof(*runtime));
    if (runtime == NULL) return NULL;
    anomaly_detector_runtime_init(&runtime->detector, algorithm_mask, frame_rate_fps);
    return runtime;
}

void R2CAnomalyDestroy(R2CAnomalyRuntime *runtime) {
    if (runtime == NULL) return;
    anomaly_detector_runtime_cleanup(&runtime->detector);
    free(runtime->rgba);
    free(runtime);
}

int32_t R2CAnomalyProcessBGRA(
        R2CAnomalyRuntime *runtime,
        const uint8_t *bgra,
        int32_t bytes_per_row,
        int32_t width,
        int32_t height,
        int64_t timestamp_us,
        R2CAnomalyFrameResult *result_out) {
    if (runtime == NULL || bgra == NULL || result_out == NULL ||
        width <= 0 || height <= 0 || bytes_per_row < width * 4) return -1;

    size_t rgba_size = (size_t)width * (size_t)height * 4u;
    if (rgba_size > runtime->rgba_capacity) {
        uint8_t *replacement = realloc(runtime->rgba, rgba_size);
        if (replacement == NULL) return -2;
        runtime->rgba = replacement;
        runtime->rgba_capacity = rgba_size;
    }

    for (int32_t y = 0; y < height; y++) {
        const uint8_t *source = bgra + (size_t)y * (size_t)bytes_per_row;
        uint8_t *destination = runtime->rgba + (size_t)y * (size_t)width * 4u;
        for (int32_t x = 0; x < width; x++) {
            destination[x * 4] = source[x * 4 + 2];
            destination[x * 4 + 1] = source[x * 4 + 1];
            destination[x * 4 + 2] = source[x * 4];
            destination[x * 4 + 3] = source[x * 4 + 3];
        }
    }

    anomaly_frame_input_t frame = {
        .rgba = runtime->rgba,
        .rgba_stride = width * 4,
        .width = width,
        .height = height,
        .source_timestamp_us = timestamp_us,
        .frame_format = ANOMALY_FRAME_FORMAT_RGBA8888,
    };
    anomaly_detector_runtime_process_result_t result =
            anomaly_detector_runtime_process_frame_result(&runtime->detector, &frame);
    memset(result_out, 0, sizeof(*result_out));
    result_out->frame_ordinal = result.frame_ordinal;
    result_out->raw_box_count = result.raw_box_count;
    result_out->stable_box_count = result.stable_box_count;
    int count = result.output.annotations.box_count;
    if (count > R2C_ANOMALY_MAX_BOXES) count = R2C_ANOMALY_MAX_BOXES;
    result_out->annotation_count = count;
    for (int index = 0; index < count; index++) {
        const anomaly_box_t *source = &result.output.annotations.boxes[index];
        result_out->boxes[index] = (R2CAnomalyBox) {
            .left = source->left_norm,
            .top = source->top_norm,
            .right = source->right_norm,
            .bottom = source->bottom_norm,
            .weight = source->weight,
            .algorithm = source->algorithm,
        };
    }
    return 0;
}

int32_t R2CAnomalyFrameResultCopyBox(
        const R2CAnomalyFrameResult *result,
        int32_t index,
        R2CAnomalyBox *box_out) {
    if (result == NULL || box_out == NULL || index < 0 ||
        index >= result->annotation_count || index >= R2C_ANOMALY_MAX_BOXES) return -1;
    *box_out = result->boxes[index];
    return 0;
}

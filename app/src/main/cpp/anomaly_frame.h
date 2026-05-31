// Internal frame input contract for the standalone anomaly detector.
#pragma once

#include <stdint.h>

typedef enum {
    ANOMALY_FRAME_FORMAT_RGBA8888 = 1,
    ANOMALY_FRAME_FORMAT_RESERVED_YUV = 2,
} anomaly_frame_format_t;

typedef struct {
    uint8_t *rgba;
    int      rgba_stride;
    int      width;
    int      height;
    int64_t  source_timestamp_us;
    anomaly_frame_format_t frame_format;
} anomaly_frame_input_t;


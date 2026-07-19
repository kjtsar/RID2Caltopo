#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define R2C_ANOMALY_ALGORITHM_COLOR 0x01
#define R2C_ANOMALY_ALGORITHM_THERMAL 0x02
#define R2C_ANOMALY_MAX_BOXES 4

typedef struct R2CAnomalyRuntime R2CAnomalyRuntime;

typedef struct {
    float left;
    float top;
    float right;
    float bottom;
    float weight;
    int32_t algorithm;
} R2CAnomalyBox;

typedef struct {
    int64_t frame_ordinal;
    int32_t raw_box_count;
    int32_t stable_box_count;
    int32_t annotation_count;
    R2CAnomalyBox boxes[R2C_ANOMALY_MAX_BOXES];
} R2CAnomalyFrameResult;

R2CAnomalyRuntime *R2CAnomalyCreate(int32_t algorithm_mask, float frame_rate_fps);
void R2CAnomalyDestroy(R2CAnomalyRuntime *runtime);

/// Converts a Core Video 32BGRA frame to the detector's RGBA contract and runs
/// the existing portable RID2Caltopo anomaly core. Returns zero on success.
int32_t R2CAnomalyProcessBGRA(
        R2CAnomalyRuntime *runtime,
        const uint8_t *bgra,
        int32_t bytes_per_row,
        int32_t width,
        int32_t height,
        int64_t timestamp_us,
        R2CAnomalyFrameResult *result_out);

int32_t R2CAnomalyFrameResultCopyBox(
        const R2CAnomalyFrameResult *result,
        int32_t index,
        R2CAnomalyBox *box_out);

#ifdef __cplusplus
}
#endif

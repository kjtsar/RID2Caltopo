#ifndef R2C_LOCAL_PLAYBACK_CADENCE_H
#define R2C_LOCAL_PLAYBACK_CADENCE_H

#include <stdbool.h>
#include <stdint.h>
#include <string.h>

#define R2C_LOCAL_PLAYBACK_CADENCE_HISTORY 32
#define R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US 33333
#define R2C_LOCAL_PLAYBACK_MIN_SAMPLE_US 5000
#define R2C_LOCAL_PLAYBACK_MAX_SAMPLE_US 100000
#define R2C_LOCAL_PLAYBACK_MIN_TRUE_GAP_US 250000

typedef struct {
    int64_t previousSourceTimestampUs;
    int64_t nominalIntervalUs;
    int64_t samplesUs[R2C_LOCAL_PLAYBACK_CADENCE_HISTORY];
    int sampleCount;
    int sampleNext;
    bool started;
} R2CLocalPlaybackCadence;

static inline void R2CLocalPlaybackCadenceInit(R2CLocalPlaybackCadence *cadence) {
    if (cadence == NULL) return;
    memset(cadence, 0, sizeof(*cadence));
    cadence->nominalIntervalUs = R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US;
}

static inline int64_t R2CLocalPlaybackCadenceAverageUs(
    const R2CLocalPlaybackCadence *cadence
) {
    if (cadence == NULL || cadence->sampleCount <= 0) {
        return R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US;
    }
    int64_t sum = 0;
    int64_t minimum = INT64_MAX;
    int64_t maximum = 0;
    for (int index = 0; index < cadence->sampleCount; ++index) {
        int64_t value = cadence->samplesUs[index];
        sum += value;
        if (value < minimum) minimum = value;
        if (value > maximum) maximum = value;
    }
    int divisor = cadence->sampleCount;
    if (divisor >= 5) {
        sum -= minimum;
        sum -= maximum;
        divisor -= 2;
    }
    return divisor > 0 ? sum / divisor : R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US;
}

// Returns the wall-clock delay before presenting the next decoded picture.
// Ordinary timestamp jitter is replaced with a robust rolling cadence while
// genuine source gaps remain visible. Source timestamps are not rewritten, so
// telemetry and frame annotations retain their original media-time identity.
static inline int64_t R2CLocalPlaybackCadenceNextIntervalUs(
    R2CLocalPlaybackCadence *cadence,
    int64_t sourceTimestampUs
) {
    if (cadence == NULL) return R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US;
    if (!cadence->started) {
        cadence->started = true;
        cadence->previousSourceTimestampUs = sourceTimestampUs;
        return 0;
    }

    int64_t sourceIntervalUs = 0;
    if (sourceTimestampUs > 0 &&
        cadence->previousSourceTimestampUs > 0 &&
        sourceTimestampUs > cadence->previousSourceTimestampUs) {
        sourceIntervalUs = sourceTimestampUs - cadence->previousSourceTimestampUs;
    }
    if (sourceTimestampUs > 0) {
        cadence->previousSourceTimestampUs = sourceTimestampUs;
    }

    if (sourceIntervalUs >= R2C_LOCAL_PLAYBACK_MIN_SAMPLE_US &&
        sourceIntervalUs <= R2C_LOCAL_PLAYBACK_MAX_SAMPLE_US) {
        cadence->samplesUs[cadence->sampleNext] = sourceIntervalUs;
        cadence->sampleNext =
            (cadence->sampleNext + 1) % R2C_LOCAL_PLAYBACK_CADENCE_HISTORY;
        if (cadence->sampleCount < R2C_LOCAL_PLAYBACK_CADENCE_HISTORY) {
            cadence->sampleCount += 1;
        }
        if (cadence->sampleCount >= 3) {
            cadence->nominalIntervalUs = R2CLocalPlaybackCadenceAverageUs(cadence);
        }
    }

    int64_t nominalIntervalUs = cadence->nominalIntervalUs > 0
        ? cadence->nominalIntervalUs
        : R2C_LOCAL_PLAYBACK_DEFAULT_INTERVAL_US;
    int64_t trueGapThresholdUs = nominalIntervalUs * 4;
    if (trueGapThresholdUs < R2C_LOCAL_PLAYBACK_MIN_TRUE_GAP_US) {
        trueGapThresholdUs = R2C_LOCAL_PLAYBACK_MIN_TRUE_GAP_US;
    }
    if (sourceIntervalUs >= trueGapThresholdUs) {
        return sourceIntervalUs;
    }
    return nominalIntervalUs;
}

#endif

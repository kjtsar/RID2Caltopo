#ifndef R2C_FFMPEG_MOBILE_H
#define R2C_FFMPEG_MOBILE_H

#include <CoreVideo/CoreVideo.h>
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct R2CFFmpegSession R2CFFmpegSession;

typedef enum R2CFFmpegStatus {
    R2C_FFMPEG_STATUS_CONNECTING = 0,
    R2C_FFMPEG_STATUS_STREAMING = 1,
    R2C_FFMPEG_STATUS_ENDED = 2,
    R2C_FFMPEG_STATUS_FAILED = 3,
    R2C_FFMPEG_STATUS_STOPPED = 4,
} R2CFFmpegStatus;

// Starts a single RTSP/H.264 decoder on a background thread. Returns NULL
// when the session cannot be allocated or its worker cannot be started.
R2CFFmpegSession *R2CFFmpegSessionCreate(const char *url);

// Starts real-time, single-pass playback of a local H.264 recording. The
// decoder inspects non-picture packets for metadata but does not
// submit standalone SEI samples to VideoToolbox.
R2CFFmpegSession *R2CFFmpegSessionCreatePlayback(const char *url);

// Interrupts network I/O, joins the worker, and releases all retained frames.
void R2CFFmpegSessionDestroy(R2CFFmpegSession *session);

// Returns the newest hardware-decoded frame with a +1 retain count. The
// caller owns the returned CVPixelBufferRef. Older unread frames are dropped.
CVPixelBufferRef R2CFFmpegSessionCopyLatestFrame(
    R2CFFmpegSession *session,
    uint64_t *sequence,
    int64_t *presentationTimeMicroseconds
);

R2CFFmpegStatus R2CFFmpegSessionGetStatus(
    R2CFFmpegSession *session,
    char *detail,
    int detailCapacity
);

uint64_t R2CFFmpegSessionDecodedFrameCount(R2CFFmpegSession *session);

// Copies the newest gimbal pitch carried by FFmpeg format, stream, packet, or
// frame metadata. Returns false until the stream has supplied a finite value.
bool R2CFFmpegSessionCopyLatestGimbalPitchDegrees(
    R2CFFmpegSession *session,
    double *gimbalPitchDegrees
);
bool R2CFFmpegSessionCopyLatestCameraYawDegrees(
    R2CFFmpegSession *session,
    double *cameraYawDegrees
);
bool R2CFFmpegSessionCopyLatestHeadingDegrees(
    R2CFFmpegSession *session,
    double *headingDegrees
);
bool R2CFFmpegSessionCopyLatestDJICameraTelemetry(
    R2CFFmpegSession *session,
    double *azimuthDegrees,
    double *tiltDegrees,
    double *horizontalFovDegrees,
    double *verticalFovDegrees,
    double *attitudeAnglesDegrees,
    int attitudeAngleCapacity,
    // aircraft lat/lon, relative-up, reference lat/lon/alt, motion course
    double *positionValues,
    int positionValueCapacity,
    int64_t *sourceTimestampMicroseconds,
    uint64_t *sequence
);

const char *R2CFFmpegVersion(void);

#ifdef __cplusplus
}
#endif

#endif

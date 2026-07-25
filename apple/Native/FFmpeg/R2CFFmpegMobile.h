#ifndef R2C_FFMPEG_MOBILE_H
#define R2C_FFMPEG_MOBILE_H

#include <CoreVideo/CoreVideo.h>
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
const char *R2CFFmpegVersion(void);

#ifdef __cplusplus
}
#endif

#endif

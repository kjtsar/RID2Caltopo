#include "R2CFFmpegMobile.h"
#include "R2CH264Packet.h"
#include "../../../native/R2CDJICameraTelemetry.h"

#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/hwcontext.h>
#include <libavutil/log.h>
#include <libavutil/mem.h>
#include <libavutil/pixfmt.h>
#include <libavutil/time.h>

#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct R2CFFmpegSession {
    pthread_t worker;
    pthread_mutex_t lock;
    atomic_bool running;
    bool workerStarted;
    bool localPlayback;
    char *url;
    R2CFFmpegStatus status;
    char detail[256];
    CVPixelBufferRef latestFrame;
    uint64_t latestSequence;
    int64_t latestPresentationTimeMicroseconds;
    uint64_t decodedFrameCount;
    double latestGimbalPitchDegrees;
    bool hasGimbalPitch;
    double latestCameraYawDegrees;
    bool hasCameraYaw;
    double latestHeadingDegrees;
    bool hasHeading;
    double latestDJIAzimuthDegrees;
    double latestDJITiltDegrees;
    double latestDJIHorizontalFovDegrees;
    double latestDJIVerticalFovDegrees;
    double latestDJIAttitudeAnglesDegrees[9];
    double latestDJIPositionValues[7];
    uint8_t latestDJIType245Payload[128];
    size_t latestDJIType245PayloadSize;
    int32_t latestDJINorthMillimeters;
    int32_t latestDJIEastMillimeters;
    int32_t latestDJIDownMillimeters;
    bool hasDJICourseAnchor;
    int32_t djiCourseAnchorNorthMillimeters;
    int32_t djiCourseAnchorEastMillimeters;
    double latestDJICourseDegrees;
    bool hasDJICourse;
    int64_t latestDJISourceTimestampMicroseconds;
    uint64_t djiCameraTelemetrySequence;
    bool hasDJICameraTelemetry;
    char lastFFmpegLog[256];
};

static _Atomic(R2CFFmpegSession *) activeLogSession = NULL;

static void capture_ffmpeg_log(void *context, int level, const char *format, va_list arguments) {
    if (level > AV_LOG_VERBOSE) {
        return;
    }
    R2CFFmpegSession *session = atomic_load_explicit(&activeLogSession, memory_order_acquire);
    if (session == NULL) {
        return;
    }
    char line[256] = {0};
    int printPrefix = 1;
    av_log_format_line2(context, level, format, arguments, line, sizeof(line), &printPrefix);
    size_t length = strlen(line);
    while (length > 0 && (line[length - 1] == '\n' || line[length - 1] == '\r')) {
        line[--length] = '\0';
    }
    if (level <= AV_LOG_WARNING ||
        strstr(line, "VideoToolbox") != NULL ||
        strstr(line, "vt decoder") != NULL ||
        strstr(line, "videotoolbox") != NULL) {
        pthread_mutex_lock(&session->lock);
        snprintf(session->lastFFmpegLog, sizeof(session->lastFFmpegLog), "%s", line);
        pthread_mutex_unlock(&session->lock);
    }
}

static void set_status(
    R2CFFmpegSession *session,
    R2CFFmpegStatus status,
    const char *detail
) {
    pthread_mutex_lock(&session->lock);
    session->status = status;
    snprintf(session->detail, sizeof(session->detail), "%s", detail != NULL ? detail : "");
    pthread_mutex_unlock(&session->lock);
}

static void set_ffmpeg_error(
    R2CFFmpegSession *session,
    const char *operation,
    int error
) {
    char message[AV_ERROR_MAX_STRING_SIZE] = {0};
    char detail[256] = {0};
    char ffmpegDetail[256] = {0};
    av_strerror(error, message, sizeof(message));
    pthread_mutex_lock(&session->lock);
    snprintf(ffmpegDetail, sizeof(ffmpegDetail), "%s", session->lastFFmpegLog);
    pthread_mutex_unlock(&session->lock);
    snprintf(
        detail,
        sizeof(detail),
        "%s: %s (%d)%s%s",
        operation,
        message,
        error,
        ffmpegDetail[0] != '\0' ? "; " : "",
        ffmpegDetail
    );
    set_status(session, R2C_FFMPEG_STATUS_FAILED, detail);
}

static int interrupt_callback(void *opaque) {
    R2CFFmpegSession *session = opaque;
    return session == NULL || !atomic_load_explicit(&session->running, memory_order_relaxed);
}

static enum AVPixelFormat select_video_toolbox_format(
    AVCodecContext *context,
    const enum AVPixelFormat *formats
) {
    (void) context;
    for (const enum AVPixelFormat *format = formats; *format != AV_PIX_FMT_NONE; ++format) {
        if (*format == AV_PIX_FMT_VIDEOTOOLBOX) {
            return *format;
        }
    }
    return AV_PIX_FMT_NONE;
}

static bool metadata_key_contains(const char *key, const char *needle) {
    if (key == NULL || needle == NULL) {
        return false;
    }
    char lowered[128] = {0};
    size_t index = 0;
    for (; key[index] != '\0' && index + 1 < sizeof(lowered); ++index) {
        lowered[index] = (char) tolower((unsigned char) key[index]);
    }
    return strstr(lowered, needle) != NULL;
}

static void ingest_telemetry_metadata(R2CFFmpegSession *session, AVDictionary *metadata) {
    if (session == NULL || metadata == NULL) {
        return;
    }
    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(metadata, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        char *end = NULL;
        double value = strtod(entry->value, &end);
        if (end == entry->value || !isfinite(value)) {
            continue;
        }
        pthread_mutex_lock(&session->lock);
        if (metadata_key_contains(entry->key, "gimbal") &&
            metadata_key_contains(entry->key, "pitch")) {
            session->latestGimbalPitchDegrees = value;
            session->hasGimbalPitch = true;
        } else if ((metadata_key_contains(entry->key, "camera") ||
                    metadata_key_contains(entry->key, "gimbal")) &&
                   metadata_key_contains(entry->key, "yaw")) {
            session->latestCameraYawDegrees = value;
            session->hasCameraYaw = true;
        } else if (metadata_key_contains(entry->key, "heading") ||
                   metadata_key_contains(entry->key, "course")) {
            session->latestHeadingDegrees = value;
            session->hasHeading = true;
        }
        pthread_mutex_unlock(&session->lock);
    }
}

static void ingest_packet_telemetry_metadata(R2CFFmpegSession *session, AVPacket *packet) {
    if (session == NULL || packet == NULL) {
        return;
    }
    size_t metadataSize = 0;
    uint8_t *metadataBytes = av_packet_get_side_data(
        packet,
        AV_PKT_DATA_STRINGS_METADATA,
        &metadataSize
    );
    if (metadataBytes == NULL || metadataSize == 0) {
        return;
    }
    AVDictionary *metadata = NULL;
    if (av_packet_unpack_dictionary(metadataBytes, (int) metadataSize, &metadata) >= 0) {
        ingest_telemetry_metadata(session, metadata);
    }
    av_dict_free(&metadata);
}

static void publish_frame(
    R2CFFmpegSession *session,
    CVPixelBufferRef frame,
    int64_t presentationTimeMicroseconds
) {
    CVPixelBufferRetain(frame);
    pthread_mutex_lock(&session->lock);
    CVPixelBufferRef previous = session->latestFrame;
    session->latestFrame = frame;
    session->latestSequence += 1;
    session->latestPresentationTimeMicroseconds = presentationTimeMicroseconds;
    session->decodedFrameCount += 1;
    session->status = R2C_FFMPEG_STATUS_STREAMING;
    snprintf(session->detail, sizeof(session->detail), "VideoToolbox decoding");
    pthread_mutex_unlock(&session->lock);
    if (previous != NULL) {
        CVPixelBufferRelease(previous);
    }
}

static void *decode_worker(void *opaque) {
    R2CFFmpegSession *session = opaque;
    AVFormatContext *format = NULL;
    AVCodecContext *codec = NULL;
    AVPacket *packet = NULL;
    AVFrame *frame = NULL;
    AVBufferRef *hardwareDevice = NULL;
    AVDictionary *options = NULL;
    int videoStreamIndex = -1;
    int result = 0;
    int startupPacketErrors = 0;
    int nalLengthSize = 4;
    int64_t playbackFirstTimestampMicroseconds = AV_NOPTS_VALUE;
    int64_t playbackStartedAtMicroseconds = 0;

    avformat_network_init();
    atomic_store_explicit(&activeLogSession, session, memory_order_release);
    av_log_set_level(AV_LOG_VERBOSE);
    av_log_set_callback(capture_ffmpeg_log);
    format = avformat_alloc_context();
    if (format == NULL) {
        set_status(session, R2C_FFMPEG_STATUS_FAILED, "Could not allocate FFmpeg input");
        goto finished;
    }
    format->interrupt_callback.callback = interrupt_callback;
    format->interrupt_callback.opaque = session;

    if (!session->localPlayback) {
        av_dict_set(&options, "rtsp_transport", "tcp", 0);
        av_dict_set(&options, "allowed_media_types", "video", 0);
        av_dict_set(&options, "fflags", "nobuffer", 0);
        av_dict_set(&options, "flags", "low_delay", 0);
        av_dict_set(&options, "reorder_queue_size", "0", 0);
        av_dict_set(&options, "max_delay", "100000", 0);
        av_dict_set(&options, "probesize", "32768", 0);
        av_dict_set(&options, "analyzeduration", "0", 0);
        av_dict_set(&options, "fpsprobesize", "0", 0);
    }

    result = avformat_open_input(&format, session->url, NULL, &options);
    av_dict_free(&options);
    if (result < 0) {
        if (atomic_load(&session->running)) {
            set_ffmpeg_error(
                session,
                session->localPlayback ? "Recording open failed" : "RTSP open failed",
                result
            );
        }
        goto finished;
    }

    result = avformat_find_stream_info(format, NULL);
    if (result < 0) {
        if (atomic_load(&session->running)) {
            set_ffmpeg_error(session, "Stream probe failed", result);
        }
        goto finished;
    }

    videoStreamIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (videoStreamIndex < 0) {
        set_ffmpeg_error(session, "H.264 stream not found", videoStreamIndex);
        goto finished;
    }

    AVStream *stream = format->streams[videoStreamIndex];
    if (stream->codecpar->codec_id == AV_CODEC_ID_H264 &&
        stream->codecpar->extradata != NULL &&
        stream->codecpar->extradata_size >= 5 &&
        stream->codecpar->extradata[0] == 1) {
        nalLengthSize = (stream->codecpar->extradata[4] & 0x03) + 1;
    }
    ingest_telemetry_metadata(session, format->metadata);
    ingest_telemetry_metadata(session, stream->metadata);
    const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
    if (decoder == NULL) {
        set_status(session, R2C_FFMPEG_STATUS_FAILED, "H.264 decoder unavailable");
        goto finished;
    }

    codec = avcodec_alloc_context3(decoder);
    if (codec == NULL) {
        set_status(session, R2C_FFMPEG_STATUS_FAILED, "Could not allocate H.264 decoder");
        goto finished;
    }
    result = avcodec_parameters_to_context(codec, stream->codecpar);
    if (result < 0) {
        set_ffmpeg_error(session, "Decoder configuration failed", result);
        goto finished;
    }
    result = av_hwdevice_ctx_create(
        &hardwareDevice,
        AV_HWDEVICE_TYPE_VIDEOTOOLBOX,
        NULL,
        NULL,
        0
    );
    if (result < 0) {
        set_ffmpeg_error(session, "VideoToolbox device creation failed", result);
        goto finished;
    }
    codec->hw_device_ctx = av_buffer_ref(hardwareDevice);
    if (codec->hw_device_ctx == NULL) {
        set_status(session, R2C_FFMPEG_STATUS_FAILED, "VideoToolbox device reference failed");
        goto finished;
    }
    codec->get_format = select_video_toolbox_format;
    codec->thread_count = 1;
    result = avcodec_open2(codec, decoder, NULL);
    if (result < 0) {
        set_ffmpeg_error(session, "VideoToolbox decoder open failed", result);
        goto finished;
    }

    packet = av_packet_alloc();
    frame = av_frame_alloc();
    if (packet == NULL || frame == NULL) {
        set_status(session, R2C_FFMPEG_STATUS_FAILED, "Could not allocate decode buffers");
        goto finished;
    }
    set_status(session, R2C_FFMPEG_STATUS_CONNECTING, "Waiting for first H.264 frame");

    while (atomic_load_explicit(&session->running, memory_order_relaxed)) {
        result = av_read_frame(format, packet);
        if (result < 0) {
            if (atomic_load(&session->running)) {
                if (session->localPlayback && result == AVERROR_EOF) {
                    set_status(session, R2C_FFMPEG_STATUS_ENDED, "Playback complete");
                } else {
                    set_ffmpeg_error(
                        session,
                        session->localPlayback ? "Recording read ended" : "RTSP read ended",
                        result
                    );
                }
            }
            break;
        }
        if (packet->stream_index != videoStreamIndex) {
            av_packet_unref(packet);
            continue;
        }
        ingest_packet_telemetry_metadata(session, packet);
        if (stream->codecpar->codec_id == AV_CODEC_ID_H264) {
            R2CDJICameraTelemetry camera = {0};
            if (R2CDJIDecodeH264Packet(
                    packet->data,
                    (size_t) packet->size,
                    nalLengthSize,
                    &camera)) {
                pthread_mutex_lock(&session->lock);
                session->latestDJIAzimuthDegrees = camera.azimuthDegrees;
                session->latestDJITiltDegrees = camera.tiltDegrees;
                session->latestDJIHorizontalFovDegrees = camera.horizontalFovDegrees;
                session->latestDJIVerticalFovDegrees = camera.verticalFovDegrees;
                memcpy(
                    session->latestDJIAttitudeAnglesDegrees,
                    camera.attitudeAnglesDegrees,
                    sizeof(session->latestDJIAttitudeAnglesDegrees)
                );
                session->latestDJIType245PayloadSize = camera.type245PayloadSize;
                memcpy(
                    session->latestDJIType245Payload,
                    camera.type245Payload,
                    camera.type245PayloadSize
                );
                session->latestDJINorthMillimeters = camera.relativeNorthMillimeters;
                session->latestDJIEastMillimeters = camera.relativeEastMillimeters;
                session->latestDJIDownMillimeters = camera.downMillimeters;
                if (camera.relativeDisplacementValid && camera.positionValid) {
                    if (!session->hasDJICourseAnchor) {
                        session->djiCourseAnchorNorthMillimeters = camera.relativeNorthMillimeters;
                        session->djiCourseAnchorEastMillimeters = camera.relativeEastMillimeters;
                        session->hasDJICourseAnchor = true;
                    }
                    int64_t courseNorth = (int64_t) camera.relativeNorthMillimeters -
                        (int64_t) session->djiCourseAnchorNorthMillimeters;
                    int64_t courseEast = (int64_t) camera.relativeEastMillimeters -
                        (int64_t) session->djiCourseAnchorEastMillimeters;
                    if (hypot((double) courseNorth, (double) courseEast) >= 3000.0) {
                        session->latestDJICourseDegrees = fmod(
                            atan2((double) courseEast, (double) courseNorth) * 180.0 / M_PI + 360.0,
                            360.0);
                        session->hasDJICourse = true;
                        session->djiCourseAnchorNorthMillimeters = camera.relativeNorthMillimeters;
                        session->djiCourseAnchorEastMillimeters = camera.relativeEastMillimeters;
                    }
                    const double earthRadiusMeters = 6378137.0;
                    double northMeters = (double) camera.relativeNorthMillimeters / 1000.0;
                    double eastMeters = (double) camera.relativeEastMillimeters / 1000.0;
                    session->latestDJIPositionValues[0] = camera.latitudeDegrees +
                        northMeters / earthRadiusMeters * 180.0 / M_PI;
                    session->latestDJIPositionValues[1] = camera.longitudeDegrees +
                        eastMeters / (earthRadiusMeters * cos(camera.latitudeDegrees * M_PI / 180.0)) *
                        180.0 / M_PI;
                    session->latestDJIPositionValues[2] = camera.relativeUpMeters;
                    session->latestDJIPositionValues[3] = camera.latitudeDegrees;
                    session->latestDJIPositionValues[4] = camera.longitudeDegrees;
                    session->latestDJIPositionValues[5] = camera.altitudeMeters;
                    session->latestDJIPositionValues[6] = session->hasDJICourse
                        ? session->latestDJICourseDegrees : NAN;
                }
                session->latestDJISourceTimestampMicroseconds = packet->pts == AV_NOPTS_VALUE
                    ? 0
                    : av_rescale_q(packet->pts, stream->time_base, (AVRational) {1, 1000000});
                session->djiCameraTelemetrySequence += 1;
                session->hasDJICameraTelemetry = true;
                session->latestGimbalPitchDegrees = camera.tiltDegrees;
                session->hasGimbalPitch = true;
                pthread_mutex_unlock(&session->lock);
            }
        }
        if (session->localPlayback && stream->codecpar->codec_id == AV_CODEC_ID_H264) {
            R2CH264PacketContents contents = R2CH264InspectPacket(
                packet->data,
                (size_t) packet->size,
                nalLengthSize
            );
            if (R2CH264PacketIsSeiOnly(contents)) {
                av_packet_unref(packet);
                continue;
            }
        }
        if (session->localPlayback) {
            int64_t packetTimestamp = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
            if (packetTimestamp != AV_NOPTS_VALUE) {
                int64_t packetTimestampMicroseconds = av_rescale_q(
                    packetTimestamp,
                    stream->time_base,
                    (AVRational) {1, 1000000}
                );
                if (playbackFirstTimestampMicroseconds == AV_NOPTS_VALUE) {
                    playbackFirstTimestampMicroseconds = packetTimestampMicroseconds;
                    playbackStartedAtMicroseconds = av_gettime_relative();
                }
                int64_t dueAtMicroseconds = playbackStartedAtMicroseconds +
                    (packetTimestampMicroseconds - playbackFirstTimestampMicroseconds);
                while (atomic_load_explicit(&session->running, memory_order_relaxed)) {
                    int64_t remaining = dueAtMicroseconds - av_gettime_relative();
                    if (remaining <= 0) break;
                    av_usleep((unsigned int) (remaining > 10000 ? 10000 : remaining));
                }
            }
        }
        int packetFlags = packet->flags;
        int packetSize = packet->size;
        result = avcodec_send_packet(codec, packet);
        av_packet_unref(packet);
        if (result < 0 && result != AVERROR(EAGAIN)) {
            uint64_t decodedFrames = R2CFFmpegSessionDecodedFrameCount(session);
            if (decodedFrames == 0 &&
                (result == AVERROR_UNKNOWN || result == AVERROR_INVALIDDATA) &&
                startupPacketErrors < 180) {
                startupPacketErrors += 1;
                char detail[256] = {0};
                snprintf(
                    detail,
                    sizeof(detail),
                    "Waiting for decodable H.264 keyframe (error %d/%d, key=%d, bytes=%d)",
                    startupPacketErrors,
                    180,
                    (packetFlags & AV_PKT_FLAG_KEY) != 0,
                    packetSize
                );
                set_status(session, R2C_FFMPEG_STATUS_CONNECTING, detail);
                continue;
            }
            set_ffmpeg_error(session, "H.264 packet rejected", result);
            break;
        }

        while (atomic_load(&session->running)) {
            result = avcodec_receive_frame(codec, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                break;
            }
            if (result < 0) {
                set_ffmpeg_error(session, "VideoToolbox decode failed", result);
                goto finished;
            }
            if (frame->format != AV_PIX_FMT_VIDEOTOOLBOX || frame->data[3] == NULL) {
                set_status(session, R2C_FFMPEG_STATUS_FAILED, "Decoder returned a non-VideoToolbox frame");
                goto finished;
            }
            ingest_telemetry_metadata(session, frame->metadata);
            int64_t ptsMicroseconds = AV_NOPTS_VALUE;
            if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                ptsMicroseconds = av_rescale_q(
                    frame->best_effort_timestamp,
                    stream->time_base,
                    (AVRational) {1, 1000000}
                );
            }
            publish_frame(
                session,
                (CVPixelBufferRef) frame->data[3],
                ptsMicroseconds
            );
            av_frame_unref(frame);
        }
    }

finished:
    ;
    R2CFFmpegSession *expectedLogSession = session;
    atomic_compare_exchange_strong_explicit(
        &activeLogSession,
        &expectedLogSession,
        NULL,
        memory_order_acq_rel,
        memory_order_acquire
    );
    av_dict_free(&options);
    av_packet_free(&packet);
    av_frame_free(&frame);
    avcodec_free_context(&codec);
    av_buffer_unref(&hardwareDevice);
    if (format != NULL) {
        avformat_close_input(&format);
    }
    if (!atomic_load(&session->running)) {
        set_status(session, R2C_FFMPEG_STATUS_STOPPED, "Stopped");
    }
    return NULL;
}

static R2CFFmpegSession *create_session(const char *url, bool localPlayback) {
    if (url == NULL || url[0] == '\0') {
        return NULL;
    }
    R2CFFmpegSession *session = calloc(1, sizeof(*session));
    if (session == NULL) {
        return NULL;
    }
    session->url = strdup(url);
    if (session->url == NULL || pthread_mutex_init(&session->lock, NULL) != 0) {
        free(session->url);
        free(session);
        return NULL;
    }
    session->localPlayback = localPlayback;
    session->status = R2C_FFMPEG_STATUS_CONNECTING;
    snprintf(
        session->detail,
        sizeof(session->detail),
        "%s",
        localPlayback ? "Opening local recording" : "Opening local RTSP stream"
    );
    session->latestPresentationTimeMicroseconds = AV_NOPTS_VALUE;
    atomic_init(&session->running, true);
    if (pthread_create(&session->worker, NULL, decode_worker, session) != 0) {
        pthread_mutex_destroy(&session->lock);
        free(session->url);
        free(session);
        return NULL;
    }
    session->workerStarted = true;
    return session;
}

R2CFFmpegSession *R2CFFmpegSessionCreate(const char *url) {
    return create_session(url, false);
}

R2CFFmpegSession *R2CFFmpegSessionCreatePlayback(const char *url) {
    return create_session(url, true);
}

void R2CFFmpegSessionDestroy(R2CFFmpegSession *session) {
    if (session == NULL) {
        return;
    }
    atomic_store(&session->running, false);
    if (session->workerStarted) {
        pthread_join(session->worker, NULL);
    }
    pthread_mutex_lock(&session->lock);
    CVPixelBufferRef latest = session->latestFrame;
    session->latestFrame = NULL;
    pthread_mutex_unlock(&session->lock);
    if (latest != NULL) {
        CVPixelBufferRelease(latest);
    }
    pthread_mutex_destroy(&session->lock);
    free(session->url);
    free(session);
}

CVPixelBufferRef R2CFFmpegSessionCopyLatestFrame(
    R2CFFmpegSession *session,
    uint64_t *sequence,
    int64_t *presentationTimeMicroseconds
) {
    if (session == NULL) {
        return NULL;
    }
    pthread_mutex_lock(&session->lock);
    CVPixelBufferRef frame = session->latestFrame;
    if (frame != NULL) {
        CVPixelBufferRetain(frame);
    }
    if (sequence != NULL) {
        *sequence = session->latestSequence;
    }
    if (presentationTimeMicroseconds != NULL) {
        *presentationTimeMicroseconds = session->latestPresentationTimeMicroseconds;
    }
    pthread_mutex_unlock(&session->lock);
    return frame;
}

R2CFFmpegStatus R2CFFmpegSessionGetStatus(
    R2CFFmpegSession *session,
    char *detail,
    int detailCapacity
) {
    if (session == NULL) {
        if (detail != NULL && detailCapacity > 0) {
            snprintf(detail, (size_t) detailCapacity, "No decoder session");
        }
        return R2C_FFMPEG_STATUS_STOPPED;
    }
    pthread_mutex_lock(&session->lock);
    R2CFFmpegStatus status = session->status;
    if (detail != NULL && detailCapacity > 0) {
        snprintf(detail, (size_t) detailCapacity, "%s", session->detail);
    }
    pthread_mutex_unlock(&session->lock);
    return status;
}

uint64_t R2CFFmpegSessionDecodedFrameCount(R2CFFmpegSession *session) {
    if (session == NULL) {
        return 0;
    }
    pthread_mutex_lock(&session->lock);
    uint64_t count = session->decodedFrameCount;
    pthread_mutex_unlock(&session->lock);
    return count;
}

bool R2CFFmpegSessionCopyLatestGimbalPitchDegrees(
    R2CFFmpegSession *session,
    double *gimbalPitchDegrees
) {
    if (session == NULL || gimbalPitchDegrees == NULL) {
        return false;
    }
    pthread_mutex_lock(&session->lock);
    bool available = session->hasGimbalPitch;
    if (available) {
        *gimbalPitchDegrees = session->latestGimbalPitchDegrees;
    }
    pthread_mutex_unlock(&session->lock);
    return available;
}

bool R2CFFmpegSessionCopyLatestCameraYawDegrees(
    R2CFFmpegSession *session,
    double *cameraYawDegrees
) {
    if (session == NULL || cameraYawDegrees == NULL) {
        return false;
    }
    pthread_mutex_lock(&session->lock);
    bool available = session->hasCameraYaw;
    if (available) {
        *cameraYawDegrees = session->latestCameraYawDegrees;
    }
    pthread_mutex_unlock(&session->lock);
    return available;
}

bool R2CFFmpegSessionCopyLatestHeadingDegrees(
    R2CFFmpegSession *session,
    double *headingDegrees
) {
    if (session == NULL || headingDegrees == NULL) {
        return false;
    }
    pthread_mutex_lock(&session->lock);
    bool available = session->hasHeading;
    if (available) {
        *headingDegrees = session->latestHeadingDegrees;
    }
    pthread_mutex_unlock(&session->lock);
    return available;
}

bool R2CFFmpegSessionCopyLatestDJICameraTelemetry(
    R2CFFmpegSession *session,
    double *azimuthDegrees,
    double *tiltDegrees,
    double *horizontalFovDegrees,
    double *verticalFovDegrees,
    double *attitudeAnglesDegrees,
    int attitudeAngleCapacity,
    double *positionValues,
    int positionValueCapacity,
    int64_t *sourceTimestampMicroseconds,
    uint64_t *sequence
) {
    if (session == NULL || azimuthDegrees == NULL || tiltDegrees == NULL ||
        horizontalFovDegrees == NULL || verticalFovDegrees == NULL ||
        attitudeAnglesDegrees == NULL || attitudeAngleCapacity < 9 ||
        positionValues == NULL || positionValueCapacity < 7 ||
        sourceTimestampMicroseconds == NULL || sequence == NULL) {
        return false;
    }
    pthread_mutex_lock(&session->lock);
    bool available = session->hasDJICameraTelemetry;
    if (available) {
        *azimuthDegrees = session->latestDJIAzimuthDegrees;
        *tiltDegrees = session->latestDJITiltDegrees;
        *horizontalFovDegrees = session->latestDJIHorizontalFovDegrees;
        *verticalFovDegrees = session->latestDJIVerticalFovDegrees;
        memcpy(
            attitudeAnglesDegrees,
            session->latestDJIAttitudeAnglesDegrees,
            sizeof(session->latestDJIAttitudeAnglesDegrees)
        );
        memcpy(positionValues, session->latestDJIPositionValues, sizeof(session->latestDJIPositionValues));
        *sourceTimestampMicroseconds = session->latestDJISourceTimestampMicroseconds;
        *sequence = session->djiCameraTelemetrySequence;
    }
    pthread_mutex_unlock(&session->lock);
    return available;
}

bool R2CFFmpegSessionCopyLatestDJISEIPayload(
    R2CFFmpegSession *session,
    uint8_t *payload,
    int payloadCapacity,
    int *payloadSize,
    int32_t *northMillimeters,
    int32_t *eastMillimeters,
    int32_t *downMillimeters,
    int64_t *sourceTimestampMicroseconds,
    uint64_t *sequence
) {
    if (session == NULL || payload == NULL || payloadCapacity < 128 ||
        payloadSize == NULL || northMillimeters == NULL ||
        eastMillimeters == NULL || downMillimeters == NULL ||
        sourceTimestampMicroseconds == NULL || sequence == NULL) {
        return false;
    }
    pthread_mutex_lock(&session->lock);
    bool available = session->hasDJICameraTelemetry &&
        session->latestDJIType245PayloadSize <= (size_t) payloadCapacity;
    if (available) {
        memcpy(payload, session->latestDJIType245Payload, session->latestDJIType245PayloadSize);
        *payloadSize = (int) session->latestDJIType245PayloadSize;
        *northMillimeters = session->latestDJINorthMillimeters;
        *eastMillimeters = session->latestDJIEastMillimeters;
        *downMillimeters = session->latestDJIDownMillimeters;
        *sourceTimestampMicroseconds = session->latestDJISourceTimestampMicroseconds;
        *sequence = session->djiCameraTelemetrySequence;
    }
    pthread_mutex_unlock(&session->lock);
    return available;
}

const char *R2CFFmpegVersion(void) {
    return av_version_info();
}

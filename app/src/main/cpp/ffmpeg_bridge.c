#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <math.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <stdarg.h>
#include <time.h>
#include <unistd.h>

#if !defined(HAVE_FFMPEG)
#define HAVE_FFMPEG 0
#endif
#if !defined(HAVE_SWSCALE)
#define HAVE_SWSCALE 0
#endif

#if HAVE_FFMPEG
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/error.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libavutil/dict.h>
#if HAVE_SWSCALE
#include <libswscale/swscale.h>
#endif
#endif

#define TAG "ffmpeg_bridge"
#define MAX_SESSIONS 32
#include "anomaly_analysis.h"
#define RENDER_MIN_INTERVAL_MS 5
#define RENDER_MAX_INTERVAL_MS 1000
#define RENDER_DEFAULT_FPS 30
// ═══════════════════════════════════════════════════════════════════════════
// Bursty drone video and the adaptive render-control loop
// ═══════════════════════════════════════════════════════════════════════════
//
// Drone video (DJI over RTMP → MediaMTX → RTSP) is highly bursty and varies
// significantly across drone models and controller firmware:
//
//   • The drone controller batches H.264 NAL units and sends them in tight
//     bursts — several frames may arrive within a few ms of each other.
//   • Gaps of 200 – 800 ms between bursts are completely normal, and are
//     observed during manoeuvres, auto-exposure events, or controller-side
//     recording flushes.  Some models produce gaps > 900 ms routinely.
//   • A single IDR "keyframe" may carry a disproportionately large payload and
//     arrive well ahead of the next P-frame run.
//   • Different controller/drone combinations produce wildly different cadences.
//     Hardcoded thresholds tuned for one model will break others.
//
// Because of this burstiness the decode thread fills the render queue faster
// than the render thread drains it during a burst, then the queue sits empty
// during the gap.  A naïve approach (render as fast as possible) would drain
// the queue before the next burst and cause display stutter.
//
// ── Adaptive control overview ───────────────────────────────────────────────
// Rather than capping the queue or hard-coding a backpressure depth, the render
// thread uses a closed-loop controller that adapts to each stream's observed
// behaviour:
//
//  1. source_render_interval_ms  — EMA of inter-decode deltas and PTS-derived
//     cadence.  Represents the underlying "native" frame rate of this stream.
//
//  2. stall_estimate_ms  — asymmetric EMA of observed inter-burst gaps.
//     Rises quickly when a new gap is seen (RENDER_STALL_RISE_EMA_PCT),
//     decays very slowly after a long quiet period (RENDER_STALL_DECAY_EMA_PCT,
//     with a 5-second grace window before decay starts).
//
//  3. proven_gap_ms  — tracks the largest confirmed gap (> RENDER_PROVEN_GAP_TRIGGER_MS).
//     Only decays after 30 seconds of silence, and very slowly (2 % EMA).
//     This prevents the renderer from becoming over-eager after a drone that
//     normally has large gaps happens to transmit smoothly for a while.
//
//  4. target_latency_ms  — computed as max(stall_ms, proven_gap_ms) × 2 + margin,
//     clamped to [RENDER_TARGET_LATENCY_MIN_MS, RENDER_TARGET_LATENCY_MAX_MS].
//     This is the desired amount of buffered content the render thread aims to
//     maintain so it can bridge inter-burst gaps without stalling.
//
//  5. compute_desired_render_interval_ms_locked()  — PID-like controller.
//     Compares the current buffered_span_ms to target_latency_ms and adjusts the
//     render interval proportionally (±RENDER_INTERVAL_ADJUST_BASE_PCT to
//     ±RENDER_INTERVAL_ADJUST_MAX_PCT).  A smoothing EMA prevents abrupt steps.
//     During an active stall the interval is gently extended to conserve buffer.
//
// RENDER_QUEUE_INITIAL_CAPACITY is the queue's starting size; it grows
// automatically via ensure_render_queue_capacity() if needed.
//
// RENDER_QUEUE_BACKPRESSURE_DEPTH is reserved for a potential future
// decode-side backpressure mechanism.  It is NOT currently used in the decode
// loop — the adaptive render-control loop above is the sole mechanism for
// matching render rate to source rate.
#define RENDER_QUEUE_INITIAL_CAPACITY 32
#define RENDER_QUEUE_BACKPRESSURE_DEPTH 16  // reserved; not active — see notes above
#define RENDER_QUEUE_LOG_INTERVAL_MS 1000
#define RENDER_QUEUE_WARN_INTERVAL_MS 2000
#define RENDER_QUEUE_WARN_DEPTH 100
#define RENDER_LAG_LOG_INTERVAL_MS 1000
#define RENDER_STARTUP_OBSERVE_MS 2000
#define RENDER_SAMPLE_WINDOW_CAPACITY 16
#define RENDER_SOURCE_INTERVAL_DEFAULT_MS 33
#define RENDER_STALL_ESTIMATE_FLOOR_MS 300
#define RENDER_TARGET_LATENCY_MIN_MS 700
#define RENDER_TARGET_LATENCY_MAX_MS 1800
#define RENDER_PROCESSING_MARGIN_MS 100
#define RENDER_CADENCE_SAMPLE_MIN_MS 10
#define RENDER_CADENCE_SAMPLE_MAX_MS 120
#define RENDER_GAP_FLOOR_MS 150
#define RENDER_INTERVAL_ADJUST_MAX_PCT 40
#define RENDER_INTERVAL_ADJUST_BASE_PCT 12
#define RENDER_INTERVAL_SMOOTHING_PCT 15
#define RENDER_SOURCE_ESTIMATE_EMA_PCT 6
#define RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS 1000
#define RENDER_SOURCE_ESTIMATE_MIN_PCT 65
#define RENDER_SOURCE_ESTIMATE_MAX_PCT 150
#define RENDER_STALL_RISE_EMA_PCT 30
#define RENDER_STALL_DECAY_EMA_PCT 4
#define RENDER_STALL_DECAY_GRACE_MS 5000
#define RENDER_STALL_DECAY_INTERVAL_MS 1000
#define RENDER_PROVEN_GAP_TRIGGER_MS 900
#define RENDER_PROVEN_GAP_DECAY_EMA_PCT 2
#define RENDER_PROVEN_GAP_DECAY_GRACE_MS 30000
#define RENDER_PROVEN_GAP_DECAY_INTERVAL_MS 5000
#define RENDER_CONTROL_LOG_INTERVAL_MS 1000
#define RENDER_NO_SURFACE_LOG_INTERVAL_MS 2000
#define IO_STARTUP_INTERRUPT_MS 4000
#define ANOMALY_MAX_BOXES_PER_FRAME 3
// Set to 1 to enable telemetry extraction from packet side-data and frame
// metadata (drone flight data embedded in the stream).  This is expensive on
// CPU-constrained devices and has not yet yielded actionable insights, so it
// is disabled by default.  probe_event calls for frame_decoded /
// decoded_frame_gap are NOT gated by this flag — they remain active.
#define FFMPEG_TELEMETRY_ENABLED 0

#if HAVE_FFMPEG && HAVE_SWSCALE
typedef struct {
    AVFrame *frame;
    int64_t source_ts_us;
    int64_t enqueued_at_ms;
} render_queue_slot_t;

typedef struct {
    float left_norm;
    float top_norm;
    float right_norm;
    float bottom_norm;
    uint8_t r;
    uint8_t g;
    uint8_t b;
    float weight;   // stroke scale 0.0–1.0: thin on first hit, full at sustained detections
} anomaly_box_t;
#endif

typedef struct {
    jlong session_id;
    bool active;
    bool running;
    bool is_render;
    char designator[96];
    char url[256];
    pthread_t thread;

    jobject surface_global_ref;
    ANativeWindow *window;
    anomaly_config_t anomaly_cfg;    // protected by g_lock
    anomaly_state_t  anomaly_state;  // owned by decode thread; no locking needed
    // Manual render stride — set via nativeSetRenderStride().
    // When render_stride > 1, every (render_stride - 1) out of render_stride
    // non-keyframe packets are dropped *before* decode.  Keyframe (IDR) packets
    // always pass through so the decoder's reference-frame state is never broken.
    // This is an operator-controlled tool for extreme CPU-reduction scenarios
    // (e.g., one focused FFmpeg stream + several background streams on a
    // CPU-constrained device).  It operates entirely in the decode thread and is
    // independent of the adaptive render-control loop, which continues to run
    // normally on whatever frames do reach the render queue.
    // Default: 1 (no skip).
    int render_stride;
    int64_t render_stride_counter; // non-keyframe packet counter; reset on stride change
    // Surface-absent decode gate: set true when no render surface is attached.
    // The decode thread skips avcodec_send_packet while this flag is set, keeping
    // the RTSP connection alive for instant resume.  The render queue is flushed
    // immediately on detach via render_queue_flush_requested.
    volatile bool surface_paused;              // true until render surface is attached
    volatile bool render_queue_flush_requested; // render thread flushes on seeing this; clears after flush
    int64_t last_render_post_at_ms;
    int64_t last_no_surface_log_at_ms;
    int64_t source_render_interval_ms;
    int64_t render_interval_smoothed_ms;
    int64_t next_render_due_ms;
    int64_t render_drop_count;
    int last_logged_render_queue_depth;
    int64_t last_logged_render_drop_count;
    int64_t last_render_queue_log_at_ms;
    int64_t last_render_queue_warn_at_ms;
    int64_t last_render_lag_log_at_ms;
    int64_t last_render_control_log_at_ms;
    bool startup_observation_active;
    int64_t startup_started_at_ms;
    int source_interval_confidence;
    int64_t stall_estimate_ms;
    int64_t target_latency_ms;
    bool stall_active;
    int64_t last_decode_at_ms;
    int64_t last_valid_pts_us;
    int64_t last_source_estimate_update_at_ms;
    int64_t last_source_pts_relock_at_ms;
    int64_t last_gap_at_ms;
    int64_t last_stall_decay_at_ms;
    int64_t proven_gap_ms;
    int64_t last_proven_gap_decay_at_ms;
    int64_t cadence_samples_ms[RENDER_SAMPLE_WINDOW_CAPACITY];
    int cadence_sample_count;
    int cadence_sample_head;
    int64_t gap_samples_ms[RENDER_SAMPLE_WINDOW_CAPACITY];
    int gap_sample_count;
    int gap_sample_head;
    int64_t reader_stall_started_at_ms;
    int64_t last_reader_stall_log_at_ms;
    int64_t reader_stall_timeout_events;
    int64_t reader_stall_error_events;
    int64_t reader_waiting_since_ms;      // nonzero while decode thread is blocked in av_read_frame
    int64_t reader_reconnecting_since_ms; // nonzero while decode thread is between close and re-open
    int64_t last_reader_wait_event_ms;    // render thread: timestamp of last "reader_wait_long" probe
    bool packet_metadata_keys_logged;
    bool frame_metadata_keys_logged;
    int64_t io_interrupt_deadline_ms;

#if HAVE_FFMPEG
    AVFormatContext *fmt;
    AVCodecContext *codec;
    int video_stream_index;
    AVRational video_time_base;
#if HAVE_SWSCALE
    // Render conversion resources are used only by the render thread.
    pthread_t render_thread;
    bool render_thread_started;
    bool render_thread_stop;
    bool render_sync_ready;
    pthread_mutex_t render_lock;
    pthread_cond_t render_cond;
    render_queue_slot_t *render_queue;
    int render_queue_capacity;
    int render_queue_head;
    int render_queue_depth;
    struct SwsContext *sws;
    AVFrame *rgba_frame;
    uint8_t *rgba_buffer;
    int rgba_buffer_size;
    int rgba_width;
    int rgba_height;
    // Anomaly conversion resources are used by the decode thread.
    struct SwsContext *anomaly_sws;
    struct SwsContext *anomaly_back_sws;
    AVFrame *anomaly_rgba_frame;
    uint8_t *anomaly_rgba_buffer;
    int anomaly_rgba_buffer_size;
    int anomaly_rgba_width;
    int anomaly_rgba_height;
    enum AVPixelFormat anomaly_src_fmt;
#endif
#endif
} ffmpeg_session_t;

static JavaVM *g_vm = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static jlong g_next_session_id = 1;
static ffmpeg_session_t g_sessions[MAX_SESSIONS];
static jclass g_bridge_class = NULL;
static jclass g_caltopo_client_class = NULL;
static jmethodID g_dispatch_probe_event_mid = NULL;
static jmethodID g_ctdebug_mid = NULL;
static jmethodID g_ctwarn_mid = NULL;
static jmethodID g_cterror_mid = NULL;
static jmethodID g_register_debug_tag_mid = NULL;

static JNIEnv *get_env(bool *did_attach) {
    *did_attach = false;
    if (g_vm == NULL) return NULL;

    JNIEnv *env = NULL;
    int rc = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (rc == JNI_OK) return env;
    if (rc != JNI_EDETACHED) return NULL;

    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return NULL;
    *did_attach = true;
    return env;
}

static void release_env(bool did_attach) {
    if (did_attach && g_vm != NULL) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

static void dispatch_probe_event_ex(const char *designator,
                                 const char *event_type,
                                 int64_t session_id,
                                 const char *source_tag,
                                 double confidence,
                                 const char *remote_id,
                                 int64_t source_ts_us,
                                 int64_t render_latency_ms,
                                 double lat,
                                 double lng,
                                 double alt,
                                 double gimbal_pitch,
                                 double camera_yaw,
                                 double heading) {
    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL) return;
    if (g_bridge_class == NULL || g_dispatch_probe_event_mid == NULL) {
        release_env(did_attach);
        return;
    }

    jstring j_designator = (*env)->NewStringUTF(env, designator);
    jstring j_event_type = (*env)->NewStringUTF(env, event_type);
    jstring j_source_tag = (*env)->NewStringUTF(env, source_tag != NULL ? source_tag : "");
    jstring j_remote_id = (*env)->NewStringUTF(env, remote_id != NULL ? remote_id : "");
    (*env)->CallStaticVoidMethod(
            env,
            g_bridge_class,
            g_dispatch_probe_event_mid,
            j_designator,
            j_event_type,
            (jlong) session_id,
            j_source_tag,
            (jdouble) confidence,
            j_remote_id,
            (jlong) source_ts_us,
            (jlong) render_latency_ms,
            (jdouble) lat,
            (jdouble) lng,
            (jdouble) alt,
            (jdouble) gimbal_pitch,
            (jdouble) camera_yaw,
            (jdouble) heading);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
    (*env)->DeleteLocalRef(env, j_designator);
    (*env)->DeleteLocalRef(env, j_event_type);
    (*env)->DeleteLocalRef(env, j_source_tag);
    (*env)->DeleteLocalRef(env, j_remote_id);

    release_env(did_attach);
}

static void dispatch_probe_event(const char *designator,
                                 const char *event_type,
                                 int64_t session_id,
                                 int64_t source_ts_us,
                                 double lat,
                                 double lng,
                                 double alt,
                                 double gimbal_pitch,
                                 double camera_yaw,
                                 double heading) {
    dispatch_probe_event_ex(
            designator,
            event_type,
            session_id,
            "runtime",
            0.50,
            "",
            source_ts_us,
            0,
            lat,
            lng,
            alt,
            gimbal_pitch,
            camera_yaw,
            heading);
}

static void dispatch_probe_event_with_latency(const char *designator,
                                              const char *event_type,
                                              int64_t session_id,
                                              int64_t source_ts_us,
                                              int64_t render_latency_ms,
                                              double lat,
                                              double lng,
                                              double alt,
                                              double gimbal_pitch,
                                              double camera_yaw,
                                              double heading) {
    dispatch_probe_event_ex(
            designator,
            event_type,
            session_id,
            "runtime",
            0.50,
            "",
            source_ts_us,
            render_latency_ms,
            lat,
            lng,
            alt,
            gimbal_pitch,
            camera_yaw,
            heading);
}

static void ct_log_call(jmethodID method_id,
                        int android_prio,
                        const char *tag,
                        const char *fmt,
                        ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);

    const char *resolved_tag = (tag != NULL) ? tag : TAG;
    bool did_attach = false;
    JNIEnv *env = get_env(&did_attach);
    if (env == NULL || g_caltopo_client_class == NULL || method_id == NULL) {
        __android_log_print(android_prio, resolved_tag, "%s", buffer);
        release_env(did_attach);
        return;
    }

    jstring j_tag = (*env)->NewStringUTF(env, resolved_tag);
    jstring j_msg = (*env)->NewStringUTF(env, buffer);
    (*env)->CallStaticVoidMethod(env, g_caltopo_client_class, method_id, j_tag, j_msg);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        __android_log_print(android_prio, resolved_tag, "%s", buffer);
    }
    (*env)->DeleteLocalRef(env, j_tag);
    (*env)->DeleteLocalRef(env, j_msg);
    release_env(did_attach);
}

static void ct_debug(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_ctdebug_mid, ANDROID_LOG_DEBUG, tag, "%s", buffer);
}

static void ct_warn(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_ctwarn_mid, ANDROID_LOG_WARN, tag, "%s", buffer);
}

static void ct_error(const char *tag, const char *fmt, ...) {
    char buffer[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, ap);
    va_end(ap);
    ct_log_call(g_cterror_mid, ANDROID_LOG_ERROR, tag, "%s", buffer);
}

static int64_t monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000LL + (int64_t) ts.tv_nsec / 1000000LL;
}

static ffmpeg_session_t *find_session_locked(jlong session_id) {
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (g_sessions[i].active && g_sessions[i].session_id == session_id) {
            return &g_sessions[i];
        }
    }
    return NULL;
}

static bool session_running(ffmpeg_session_t *session) {
    bool running;
    pthread_mutex_lock(&g_lock);
    running = session->running;
    pthread_mutex_unlock(&g_lock);
    return running;
}

static void set_io_interrupt_timeout(ffmpeg_session_t *session, int64_t timeout_ms) {
    if (session == NULL) return;
    pthread_mutex_lock(&g_lock);
    session->io_interrupt_deadline_ms =
            timeout_ms > 0 ? monotonic_ms() + timeout_ms : 0;
    pthread_mutex_unlock(&g_lock);
}

static void clear_io_interrupt_timeout(ffmpeg_session_t *session) {
    if (session == NULL) return;
    pthread_mutex_lock(&g_lock);
    session->io_interrupt_deadline_ms = 0;
    pthread_mutex_unlock(&g_lock);
}

static int ffmpeg_interrupt_cb(void *opaque) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) opaque;
    if (session == NULL) return 1;
    int interrupt = 0;
    pthread_mutex_lock(&g_lock);
    if (!session->running) {
        interrupt = 1;
    } else if (session->io_interrupt_deadline_ms > 0 &&
               monotonic_ms() >= session->io_interrupt_deadline_ms) {
        interrupt = 1;
    }
    pthread_mutex_unlock(&g_lock);
    return interrupt;
}

static ANativeWindow *acquire_window(ffmpeg_session_t *session) {
    ANativeWindow *window = NULL;
    pthread_mutex_lock(&g_lock);
    if (session->window != NULL) {
        window = session->window;
        ANativeWindow_acquire(window);
    }
    pthread_mutex_unlock(&g_lock);
    return window;
}

#if HAVE_FFMPEG && HAVE_SWSCALE
static void ensure_rgba_resources(ffmpeg_session_t *session, int width, int height, enum AVPixelFormat src_fmt) {
    if (session->sws != NULL && session->rgba_width == width && session->rgba_height == height) {
        return;
    }

    if (session->sws != NULL) {
        sws_freeContext(session->sws);
        session->sws = NULL;
    }
    if (session->rgba_frame != NULL) {
        av_frame_free(&session->rgba_frame);
    }
    if (session->rgba_buffer != NULL) {
        av_free(session->rgba_buffer);
        session->rgba_buffer = NULL;
    }

    session->rgba_frame = av_frame_alloc();
    if (session->rgba_frame == NULL) return;

    session->rgba_buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    if (session->rgba_buffer_size <= 0) return;
    session->rgba_buffer = (uint8_t *) av_malloc((size_t) session->rgba_buffer_size);
    if (session->rgba_buffer == NULL) return;

    av_image_fill_arrays(
            session->rgba_frame->data,
            session->rgba_frame->linesize,
            session->rgba_buffer,
            AV_PIX_FMT_RGBA,
            width,
            height,
            1);

    session->sws = sws_getContext(
            width,
            height,
            src_fmt,
            width,
            height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);

    session->rgba_width = width;
    session->rgba_height = height;
}

static void ensure_anomaly_rgba_resources(ffmpeg_session_t *session,
                                          int width,
                                          int height,
                                          enum AVPixelFormat src_fmt) {
    if (session->anomaly_sws != NULL &&
        session->anomaly_back_sws != NULL &&
        session->anomaly_rgba_width == width &&
        session->anomaly_rgba_height == height &&
        session->anomaly_src_fmt == src_fmt) {
        return;
    }

    if (session->anomaly_sws != NULL) {
        sws_freeContext(session->anomaly_sws);
        session->anomaly_sws = NULL;
    }
    if (session->anomaly_back_sws != NULL) {
        sws_freeContext(session->anomaly_back_sws);
        session->anomaly_back_sws = NULL;
    }
    if (session->anomaly_rgba_frame != NULL) {
        av_frame_free(&session->anomaly_rgba_frame);
    }
    if (session->anomaly_rgba_buffer != NULL) {
        av_free(session->anomaly_rgba_buffer);
        session->anomaly_rgba_buffer = NULL;
    }

    session->anomaly_rgba_frame = av_frame_alloc();
    if (session->anomaly_rgba_frame == NULL) return;

    session->anomaly_rgba_buffer_size = av_image_get_buffer_size(AV_PIX_FMT_RGBA, width, height, 1);
    if (session->anomaly_rgba_buffer_size <= 0) return;
    session->anomaly_rgba_buffer = (uint8_t *) av_malloc((size_t) session->anomaly_rgba_buffer_size);
    if (session->anomaly_rgba_buffer == NULL) return;

    av_image_fill_arrays(
            session->anomaly_rgba_frame->data,
            session->anomaly_rgba_frame->linesize,
            session->anomaly_rgba_buffer,
            AV_PIX_FMT_RGBA,
            width,
            height,
            1);

    session->anomaly_sws = sws_getContext(
            width,
            height,
            src_fmt,
            width,
            height,
            AV_PIX_FMT_RGBA,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);
    session->anomaly_back_sws = sws_getContext(
            width,
            height,
            AV_PIX_FMT_RGBA,
            width,
            height,
            src_fmt,
            SWS_BILINEAR,
            NULL,
            NULL,
            NULL);

    session->anomaly_rgba_width = width;
    session->anomaly_rgba_height = height;
    session->anomaly_src_fmt = src_fmt;

    anomaly_state_reset(&session->anomaly_state);
}

static bool analyze_rgba_frame(ffmpeg_session_t *session,
                               int width,
                               int height,
                               uint8_t *rgba,
                               int rgba_stride,
                               int64_t source_ts_us) {
    anomaly_config_t cfg;
    pthread_mutex_lock(&g_lock);
    cfg = session->anomaly_cfg;
    pthread_mutex_unlock(&g_lock);
    return anomaly_process_frame(&session->anomaly_state, &cfg,
                                 rgba, rgba_stride, width, height,
                                 source_ts_us, NULL) > 0;
}


static bool anomaly_processing_enabled(ffmpeg_session_t *session) {
    if (session == NULL) return false;
    bool enabled = false;
    pthread_mutex_lock(&g_lock);
    enabled = session->anomaly_cfg.enabled && (session->anomaly_cfg.algorithm_mask != 0);
    pthread_mutex_unlock(&g_lock);
    return enabled;
}

static void analyze_decoded_frame(ffmpeg_session_t *session,
                                  AVFrame *decoded,
                                  int64_t source_ts_us) {
    if (session == NULL || decoded == NULL) return;
    if (!anomaly_processing_enabled(session)) return;

    ensure_anomaly_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    if (session->anomaly_sws == NULL || session->anomaly_rgba_frame == NULL) {
        return;
    }

    sws_scale(
            session->anomaly_sws,
            (const uint8_t *const *) decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            session->anomaly_rgba_frame->data,
            session->anomaly_rgba_frame->linesize);

    bool frame_annotated = analyze_rgba_frame(
            session,
            decoded->width,
            decoded->height,
            session->anomaly_rgba_frame->data[0],
            session->anomaly_rgba_frame->linesize[0],
            source_ts_us);
    if (!frame_annotated) return;
    if (session->anomaly_back_sws == NULL) return;
    if (av_frame_make_writable(decoded) < 0) return;

    sws_scale(
            session->anomaly_back_sws,
            (const uint8_t *const *) session->anomaly_rgba_frame->data,
            session->anomaly_rgba_frame->linesize,
            0,
            decoded->height,
            decoded->data,
            decoded->linesize);
}

static void render_frame_to_surface(ffmpeg_session_t *session,
                                    AVFrame *decoded,
                                    int64_t source_ts_us,
                                    int64_t render_latency_ms) {
    ensure_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    if (session->sws == NULL || session->rgba_frame == NULL) {
        dispatch_probe_event(session->designator, "render_skipped_no_rgba", session->session_id, source_ts_us,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        return;
    }

    sws_scale(
            session->sws,
            (const uint8_t *const *) decoded->data,
            decoded->linesize,
            0,
            decoded->height,
            session->rgba_frame->data,
            session->rgba_frame->linesize);

    ANativeWindow *window = acquire_window(session);
    if (window == NULL) {
        int64_t now_ms = monotonic_ms();
        if (now_ms - session->last_no_surface_log_at_ms >= RENDER_NO_SURFACE_LOG_INTERVAL_MS) {
            session->last_no_surface_log_at_ms = now_ms;
            ct_debug(TAG,
                     "render waiting for surface id=%lld designator=%s",
                     (long long) session->session_id,
                     session->designator);
        }
        return;
    }
    ANativeWindow_setBuffersGeometry(window, decoded->width, decoded->height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    int lock_rc = ANativeWindow_lock(window, &buffer, NULL);
    if (lock_rc == 0) {
        uint8_t *dst = (uint8_t *) buffer.bits;
        const int dst_stride = buffer.stride * 4;
        uint8_t *src = session->rgba_frame->data[0];
        const int src_stride = session->rgba_frame->linesize[0];
        const int copy_width = decoded->width * 4;
        for (int y = 0; y < decoded->height; y++) {
            memcpy(dst + (y * dst_stride), src + (y * src_stride), (size_t) copy_width);
        }
        ANativeWindow_unlockAndPost(window);
        session->last_render_post_at_ms = monotonic_ms();
        dispatch_probe_event_with_latency(session->designator,
                                          "frame_rendered",
                                          session->session_id,
                                          source_ts_us,
                                          render_latency_ms,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN);
    } else {
        ct_warn(TAG,
                "ANativeWindow_lock failed id=%lld designator=%s rc=%d width=%d height=%d",
                (long long) session->session_id,
                session->designator,
                lock_rc,
                decoded->width,
                decoded->height);
        dispatch_probe_event_with_latency(session->designator,
                                          "render_lock_failed",
                                          session->session_id,
                                          source_ts_us,
                                          render_latency_ms,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN,
                                          NAN);
    }

    ANativeWindow_release(window);
}
#endif

#if HAVE_FFMPEG
static int64_t pts_to_us(int64_t pts, AVRational tb) {
    if (pts == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(pts, tb, (AVRational) {1, 1000000});
}

static int64_t clamp_i64(int64_t value, int64_t min_value, int64_t max_value) {
    if (value < min_value) return min_value;
    if (value > max_value) return max_value;
    return value;
}

static int64_t current_render_interval_ms(ffmpeg_session_t *session) {
    if (session == NULL) return 0;
    if (session->render_interval_smoothed_ms > 0) {
        return clamp_i64(
                session->render_interval_smoothed_ms,
                RENDER_MIN_INTERVAL_MS,
                RENDER_MAX_INTERVAL_MS);
    }
    if (session->source_render_interval_ms > 0) {
        return clamp_i64(
                session->source_render_interval_ms,
                RENDER_MIN_INTERVAL_MS,
                RENDER_MAX_INTERVAL_MS);
    }
    return clamp_i64(
            (1000 + (RENDER_DEFAULT_FPS / 2)) / RENDER_DEFAULT_FPS,
            RENDER_MIN_INTERVAL_MS,
            RENDER_MAX_INTERVAL_MS);
}

static void push_sample_i64(int64_t *buf,
                            int *head,
                            int *count,
                            int capacity,
                            int64_t value) {
    if (buf == NULL || head == NULL || count == NULL || capacity <= 0) return;
    buf[*head] = value;
    *head = (*head + 1) % capacity;
    if (*count < capacity) {
        *count += 1;
    }
}

static int cmp_i64_asc(const void *a, const void *b) {
    int64_t av = *(const int64_t *) a;
    int64_t bv = *(const int64_t *) b;
    if (av < bv) return -1;
    if (av > bv) return 1;
    return 0;
}

static int64_t median_i64_copy(const int64_t *buf, int count) {
    if (buf == NULL || count <= 0) return 0;
    int64_t tmp[RENDER_SAMPLE_WINDOW_CAPACITY];
    if (count > RENDER_SAMPLE_WINDOW_CAPACITY) {
        count = RENDER_SAMPLE_WINDOW_CAPACITY;
    }
    memcpy(tmp, buf, (size_t) count * sizeof(int64_t));
    qsort(tmp, (size_t) count, sizeof(int64_t), cmp_i64_asc);
    if ((count & 1) != 0) {
        return tmp[count / 2];
    }
    return (tmp[(count / 2) - 1] + tmp[count / 2] + 1) / 2;
}

static bool decode_delta_is_gap(int64_t delta_ms, int64_t source_interval_ms) {
    if (delta_ms < RENDER_GAP_FLOOR_MS) return false;
    int64_t reference_ms = source_interval_ms > 0 ? source_interval_ms : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t threshold_ms = (reference_ms * 7 + 3) / 4;
    if (threshold_ms < RENDER_GAP_FLOOR_MS) threshold_ms = RENDER_GAP_FLOOR_MS;
    return delta_ms >= threshold_ms;
}

static bool decode_delta_is_plausible_cadence(int64_t delta_ms, int64_t source_interval_ms) {
    if (delta_ms < RENDER_CADENCE_SAMPLE_MIN_MS ||
        delta_ms > RENDER_CADENCE_SAMPLE_MAX_MS) {
        return false;
    }
    int64_t reference_ms = source_interval_ms > 0 ? source_interval_ms : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t min_ms = (reference_ms * RENDER_SOURCE_ESTIMATE_MIN_PCT + 99) / 100;
    int64_t max_ms = (reference_ms * RENDER_SOURCE_ESTIMATE_MAX_PCT + 99) / 100;
    if (min_ms < RENDER_CADENCE_SAMPLE_MIN_MS) min_ms = RENDER_CADENCE_SAMPLE_MIN_MS;
    if (max_ms > RENDER_CADENCE_SAMPLE_MAX_MS) max_ms = RENDER_CADENCE_SAMPLE_MAX_MS;
    return delta_ms >= min_ms && delta_ms <= max_ms;
}

static int64_t buffered_span_ms_locked(const ffmpeg_session_t *session) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 1) {
        return 0;
    }

    int64_t first_pts_us = 0;
    int64_t last_pts_us = 0;
    bool found = false;
    for (int i = 0; i < session->render_queue_depth; i++) {
        int idx = (session->render_queue_head + i) % session->render_queue_capacity;
        int64_t pts_us = session->render_queue[idx].source_ts_us;
        if (pts_us <= 0) continue;
        if (!found) {
            first_pts_us = pts_us;
            last_pts_us = pts_us;
            found = true;
            continue;
        }
        if (pts_us < last_pts_us) {
            return 0;
        }
        last_pts_us = pts_us;
    }
    if (!found || last_pts_us <= first_pts_us) return 0;
    return (last_pts_us - first_pts_us) / 1000;
}

static int64_t queue_pts_interval_ms_locked(const ffmpeg_session_t *session,
                                            int max_samples) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 1) {
        return 0;
    }

    if (max_samples < 2 || max_samples > session->render_queue_depth) {
        max_samples = session->render_queue_depth;
    }

    int start_offset = session->render_queue_depth - max_samples;
    int64_t first_pts_us = 0;
    int64_t last_pts_us = 0;
    int valid_count = 0;
    for (int i = start_offset; i < session->render_queue_depth; i++) {
        int idx = (session->render_queue_head + i) % session->render_queue_capacity;
        int64_t pts_us = session->render_queue[idx].source_ts_us;
        if (pts_us <= 0) continue;
        if (valid_count == 0) {
            first_pts_us = pts_us;
            last_pts_us = pts_us;
            valid_count = 1;
            continue;
        }
        if (pts_us < last_pts_us) {
            return 0;
        }
        last_pts_us = pts_us;
        valid_count += 1;
    }
    if (valid_count < 2 || last_pts_us <= first_pts_us) {
        return 0;
    }
    int64_t span_us = last_pts_us - first_pts_us;
    int64_t interval_ms = (span_us + ((int64_t) (valid_count - 1) * 500LL)) /
                          ((int64_t) (valid_count - 1) * 1000LL);
    return clamp_i64(interval_ms, RENDER_MIN_INTERVAL_MS, RENDER_MAX_INTERVAL_MS);
}

static int64_t compute_target_latency_ms_locked(ffmpeg_session_t *session) {
    if (session == NULL) return 1000;
    int64_t stall_ms = session->stall_estimate_ms > 0
            ? session->stall_estimate_ms
            : RENDER_STALL_ESTIMATE_FLOOR_MS;
    if (session->proven_gap_ms > stall_ms) {
        stall_ms = session->proven_gap_ms;
    }
    int64_t target_ms = (stall_ms * 2) + RENDER_PROCESSING_MARGIN_MS;
    return clamp_i64(
            target_ms,
            RENDER_TARGET_LATENCY_MIN_MS,
            RENDER_TARGET_LATENCY_MAX_MS);
}

static void update_source_interval_estimate_locked(ffmpeg_session_t *session,
                                                   int64_t decode_delta_ms) {
    if (session == NULL || decode_delta_ms <= 0) return;
    int64_t old_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t new_interval_ms;
    if (session->source_interval_confidence <= 0) {
        new_interval_ms = decode_delta_ms;
    } else {
        new_interval_ms =
                ((old_interval_ms * (100 - RENDER_SOURCE_ESTIMATE_EMA_PCT)) +
                 (decode_delta_ms * RENDER_SOURCE_ESTIMATE_EMA_PCT) + 50) / 100;
    }
    new_interval_ms = clamp_i64(new_interval_ms, RENDER_MIN_INTERVAL_MS, RENDER_MAX_INTERVAL_MS);
    session->source_render_interval_ms = new_interval_ms;
    if (session->source_interval_confidence < 100) {
        session->source_interval_confidence += 5;
        if (session->source_interval_confidence > 100) {
            session->source_interval_confidence = 100;
        }
    }
}

static void apply_pts_source_interval_locked(ffmpeg_session_t *session,
                                             int64_t pts_interval_ms,
                                             bool force_direct) {
    if (session == NULL || pts_interval_ms <= 0) return;

    int64_t old_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t new_interval_ms;
    if (force_direct || session->source_interval_confidence < 70) {
        new_interval_ms = pts_interval_ms;
    } else {
        int blend_pct = llabs(pts_interval_ms - old_interval_ms) >= 4 ? 35 : 20;
        new_interval_ms =
                ((old_interval_ms * (100 - blend_pct)) +
                 (pts_interval_ms * blend_pct) + 50) / 100;
        if (new_interval_ms == old_interval_ms && pts_interval_ms != old_interval_ms) {
            new_interval_ms += pts_interval_ms > old_interval_ms ? 1 : -1;
        }
    }

    session->source_render_interval_ms = clamp_i64(
            new_interval_ms,
            RENDER_MIN_INTERVAL_MS,
            RENDER_MAX_INTERVAL_MS);
    if (session->source_interval_confidence < 80) {
        session->source_interval_confidence = 80;
    }
}

static bool maybe_fast_relock_to_pts_locked(ffmpeg_session_t *session,
                                            int64_t buffered_span_ms,
                                            int64_t target_latency_ms,
                                            int64_t now_ms) {
    if (session == NULL || session->render_queue_depth < 8) return false;
    if (target_latency_ms <= 0) return false;
    if (buffered_span_ms < target_latency_ms) return false;
    if ((now_ms - session->last_source_pts_relock_at_ms) < 250) return false;

    int64_t current_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
    if (pts_interval_ms <= 0) return false;
    if (pts_interval_ms >= current_interval_ms - 3) return false;

    int64_t previous_interval_ms = session->source_render_interval_ms;
    apply_pts_source_interval_locked(session, pts_interval_ms, true);
    session->last_source_pts_relock_at_ms = now_ms;
    if (llabs(session->source_render_interval_ms - previous_interval_ms) >= 2) {
        ct_debug(TAG,
                 "render fast relock id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld ptsIntervalMs=%lld bufferedSpanMs=%lld targetLatencyMs=%lld queueDepth=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) previous_interval_ms,
                 (long long) session->source_render_interval_ms,
                 (long long) pts_interval_ms,
                 (long long) buffered_span_ms,
                 (long long) target_latency_ms,
                 session->render_queue_depth);
    }
    return true;
}

static void update_stall_estimate_locked(ffmpeg_session_t *session, int64_t gap_ms) {
    if (session == NULL || gap_ms < RENDER_GAP_FLOOR_MS) return;
    int64_t old_stall_ms = session->stall_estimate_ms > 0
            ? session->stall_estimate_ms
            : RENDER_STALL_ESTIMATE_FLOOR_MS;
    int64_t new_stall_ms;
    if (gap_ms >= old_stall_ms) {
        new_stall_ms =
                ((old_stall_ms * (100 - RENDER_STALL_RISE_EMA_PCT)) +
                 (gap_ms * RENDER_STALL_RISE_EMA_PCT) + 50) / 100;
    } else {
        new_stall_ms =
                ((old_stall_ms * (100 - RENDER_STALL_DECAY_EMA_PCT)) +
                 (gap_ms * RENDER_STALL_DECAY_EMA_PCT) + 50) / 100;
    }
    session->stall_estimate_ms = clamp_i64(
            new_stall_ms,
            RENDER_STALL_ESTIMATE_FLOOR_MS,
            RENDER_TARGET_LATENCY_MAX_MS);
    session->last_gap_at_ms = session->last_decode_at_ms;
    session->last_stall_decay_at_ms = session->last_decode_at_ms;
}

static void update_proven_gap_locked(ffmpeg_session_t *session, int64_t gap_ms) {
    if (session == NULL || gap_ms < RENDER_PROVEN_GAP_TRIGGER_MS) return;

    int64_t old_proven_gap_ms = session->proven_gap_ms > 0
            ? session->proven_gap_ms
            : RENDER_STALL_ESTIMATE_FLOOR_MS;
    int64_t new_proven_gap_ms = old_proven_gap_ms;
    if (gap_ms > old_proven_gap_ms) {
        new_proven_gap_ms = gap_ms;
    } else {
        new_proven_gap_ms =
                ((old_proven_gap_ms * 85) + (gap_ms * 15) + 50) / 100;
    }
    session->proven_gap_ms = clamp_i64(
            new_proven_gap_ms,
            RENDER_STALL_ESTIMATE_FLOOR_MS,
            RENDER_TARGET_LATENCY_MAX_MS);
    session->last_proven_gap_decay_at_ms = session->last_decode_at_ms;
}

static void maybe_decay_stall_estimate_locked(ffmpeg_session_t *session, int64_t now_ms) {
    if (session == NULL) return;
    if (session->stall_estimate_ms <= RENDER_STALL_ESTIMATE_FLOOR_MS) return;
    if (session->last_gap_at_ms <= 0) return;
    if ((now_ms - session->last_gap_at_ms) < RENDER_STALL_DECAY_GRACE_MS) return;
    if ((now_ms - session->last_stall_decay_at_ms) < RENDER_STALL_DECAY_INTERVAL_MS) return;

    int64_t old_stall_ms = session->stall_estimate_ms;
    session->stall_estimate_ms =
            ((session->stall_estimate_ms * (100 - RENDER_STALL_DECAY_EMA_PCT)) +
             (RENDER_STALL_ESTIMATE_FLOOR_MS * RENDER_STALL_DECAY_EMA_PCT) + 50) / 100;
    if (session->stall_estimate_ms < RENDER_STALL_ESTIMATE_FLOOR_MS) {
        session->stall_estimate_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    session->last_stall_decay_at_ms = now_ms;
    if (llabs(session->stall_estimate_ms - old_stall_ms) >= 20) {
        ct_debug(TAG,
                 "render stall estimate decayed id=%lld designator=%s oldMs=%lld newMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_stall_ms,
                 (long long) session->stall_estimate_ms);
    }
}

static void maybe_decay_proven_gap_locked(ffmpeg_session_t *session, int64_t now_ms) {
    if (session == NULL) return;
    if (session->proven_gap_ms <= RENDER_STALL_ESTIMATE_FLOOR_MS) return;
    if (session->last_gap_at_ms <= 0) return;
    if ((now_ms - session->last_gap_at_ms) < RENDER_PROVEN_GAP_DECAY_GRACE_MS) return;
    if ((now_ms - session->last_proven_gap_decay_at_ms) < RENDER_PROVEN_GAP_DECAY_INTERVAL_MS) return;

    int64_t old_proven_gap_ms = session->proven_gap_ms;
    session->proven_gap_ms =
            ((session->proven_gap_ms * (100 - RENDER_PROVEN_GAP_DECAY_EMA_PCT)) +
             (RENDER_STALL_ESTIMATE_FLOOR_MS * RENDER_PROVEN_GAP_DECAY_EMA_PCT) + 50) / 100;
    if (session->proven_gap_ms < RENDER_STALL_ESTIMATE_FLOOR_MS) {
        session->proven_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    session->last_proven_gap_decay_at_ms = now_ms;
    if (llabs(session->proven_gap_ms - old_proven_gap_ms) >= 20) {
        ct_debug(TAG,
                 "render proven gap decayed id=%lld designator=%s oldMs=%lld newMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_proven_gap_ms,
                 (long long) session->proven_gap_ms);
    }
}

static void log_startup_estimates(ffmpeg_session_t *session, const char *reason) {
    if (session == NULL) return;
    ct_debug(TAG,
             "render startup complete id=%lld designator=%s reason=%s sourceIntervalMs=%lld stallEstimateMs=%lld provenGapMs=%lld targetLatencyMs=%lld cadenceSamples=%d gapSamples=%d",
             (long long) session->session_id,
             session->designator,
             reason != NULL ? reason : "startup",
             (long long) session->source_render_interval_ms,
             (long long) session->stall_estimate_ms,
             (long long) session->proven_gap_ms,
             (long long) session->target_latency_ms,
             session->cadence_sample_count,
             session->gap_sample_count);
}

static void finalize_startup_estimates_locked(ffmpeg_session_t *session) {
    if (session == NULL) return;
    int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
    if (session->cadence_sample_count > 0) {
        session->source_render_interval_ms = clamp_i64(
                median_i64_copy(session->cadence_samples_ms, session->cadence_sample_count),
                RENDER_MIN_INTERVAL_MS,
                RENDER_MAX_INTERVAL_MS);
        session->source_interval_confidence = 60;
    } else if (session->source_render_interval_ms <= 0) {
        session->source_render_interval_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
        session->source_interval_confidence = 10;
    }
    if (pts_interval_ms > 0) {
        apply_pts_source_interval_locked(session, pts_interval_ms, true);
        session->last_source_pts_relock_at_ms = monotonic_ms();
    }
    if (session->gap_sample_count > 0) {
        int64_t startup_gap_ms = median_i64_copy(session->gap_samples_ms, session->gap_sample_count);
        if (startup_gap_ms < RENDER_STALL_ESTIMATE_FLOOR_MS) {
            startup_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
        }
        session->stall_estimate_ms = startup_gap_ms;
        if (startup_gap_ms >= RENDER_PROVEN_GAP_TRIGGER_MS) {
            session->proven_gap_ms = startup_gap_ms;
        }
    } else if (session->stall_estimate_ms <= 0) {
        session->stall_estimate_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    if (session->proven_gap_ms <= 0) {
        session->proven_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    }
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    session->render_interval_smoothed_ms = session->source_render_interval_ms;
    log_startup_estimates(session, "observe-window");
}

static void record_decode_timing_sample_locked(ffmpeg_session_t *session,
                                               int64_t decoded_at_ms,
                                               int64_t source_ts_us) {
    if (session == NULL || decoded_at_ms <= 0) return;

    int64_t previous_decode_at_ms = session->last_decode_at_ms;
    session->last_decode_at_ms = decoded_at_ms;
    if (source_ts_us > 0) {
        session->last_valid_pts_us = source_ts_us;
    }
    if (previous_decode_at_ms <= 0 || decoded_at_ms <= previous_decode_at_ms) {
        return;
    }

    int64_t decode_delta_ms = decoded_at_ms - previous_decode_at_ms;
    bool gap_sample = decode_delta_is_gap(decode_delta_ms, session->source_render_interval_ms);
    if (session->startup_observation_active) {
        if (gap_sample) {
            push_sample_i64(
                    session->gap_samples_ms,
                    &session->gap_sample_head,
                    &session->gap_sample_count,
                    RENDER_SAMPLE_WINDOW_CAPACITY,
                    decode_delta_ms);
        } else if (decode_delta_ms >= RENDER_CADENCE_SAMPLE_MIN_MS &&
                   decode_delta_ms <= RENDER_CADENCE_SAMPLE_MAX_MS) {
            push_sample_i64(
                    session->cadence_samples_ms,
                    &session->cadence_sample_head,
                    &session->cadence_sample_count,
                    RENDER_SAMPLE_WINDOW_CAPACITY,
                    decode_delta_ms);
        }
        return;
    }

    if (gap_sample) {
        push_sample_i64(
                session->gap_samples_ms,
                &session->gap_sample_head,
                &session->gap_sample_count,
                RENDER_SAMPLE_WINDOW_CAPACITY,
                decode_delta_ms);
        int64_t old_stall_ms = session->stall_estimate_ms;
        int64_t old_proven_gap_ms = session->proven_gap_ms;
        update_stall_estimate_locked(session, decode_delta_ms);
        update_proven_gap_locked(session, decode_delta_ms);
        session->target_latency_ms = compute_target_latency_ms_locked(session);
        if (llabs(session->stall_estimate_ms - old_stall_ms) >= 50) {
            ct_debug(TAG,
                     "render stall estimate updated id=%lld designator=%s oldMs=%lld newMs=%lld provenGapMs=%lld targetLatencyMs=%lld gapMs=%lld",
                     (long long) session->session_id,
                     session->designator,
                     (long long) old_stall_ms,
                     (long long) session->stall_estimate_ms,
                     (long long) session->proven_gap_ms,
                     (long long) session->target_latency_ms,
                     (long long) decode_delta_ms);
        } else if (llabs(session->proven_gap_ms - old_proven_gap_ms) >= 50) {
            ct_debug(TAG,
                     "render proven gap updated id=%lld designator=%s oldMs=%lld newMs=%lld targetLatencyMs=%lld gapMs=%lld",
                     (long long) session->session_id,
                     session->designator,
                     (long long) old_proven_gap_ms,
                     (long long) session->proven_gap_ms,
                     (long long) session->target_latency_ms,
                     (long long) decode_delta_ms);
        }
        return;
    }

    if (!decode_delta_is_plausible_cadence(decode_delta_ms, session->source_render_interval_ms)) {
        return;
    }
    push_sample_i64(
            session->cadence_samples_ms,
            &session->cadence_sample_head,
            &session->cadence_sample_count,
            RENDER_SAMPLE_WINDOW_CAPACITY,
            decode_delta_ms);

    if ((decoded_at_ms - session->last_source_estimate_update_at_ms) <
        RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS) {
        return;
    }

    int sample_count = session->cadence_sample_count;
    if (sample_count < 4) {
        return;
    }
    int64_t robust_interval_ms = median_i64_copy(session->cadence_samples_ms, sample_count);
    if (!decode_delta_is_plausible_cadence(robust_interval_ms, session->source_render_interval_ms)) {
        return;
    }

    int64_t old_interval_ms = session->source_render_interval_ms;
    update_source_interval_estimate_locked(session, robust_interval_ms);
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    session->last_source_estimate_update_at_ms = decoded_at_ms;
    if (llabs(session->source_render_interval_ms - old_interval_ms) >= 2) {
        ct_debug(TAG,
                 "render source estimate updated id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld targetLatencyMs=%lld robustSampleMs=%lld confidence=%d samples=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) old_interval_ms,
                 (long long) session->source_render_interval_ms,
                 (long long) session->target_latency_ms,
                 (long long) robust_interval_ms,
                 session->source_interval_confidence,
                 sample_count);
    }
}

static int64_t compute_desired_render_interval_ms_locked(ffmpeg_session_t *session,
                                                         int64_t buffered_span_ms,
                                                         int64_t now_ms) {
    if (session == NULL) {
        return RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    }

    int64_t source_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    maybe_decay_stall_estimate_locked(session, now_ms);
    maybe_decay_proven_gap_locked(session, now_ms);
    session->target_latency_ms = compute_target_latency_ms_locked(session);
    int64_t target_latency_ms = session->target_latency_ms > 0
            ? session->target_latency_ms
            : compute_target_latency_ms_locked(session);
    if (maybe_fast_relock_to_pts_locked(session, buffered_span_ms, target_latency_ms, now_ms)) {
        source_interval_ms = session->source_render_interval_ms;
        session->target_latency_ms = compute_target_latency_ms_locked(session);
        target_latency_ms = session->target_latency_ms > 0
                ? session->target_latency_ms
                : compute_target_latency_ms_locked(session);
    }
    if (session->last_decode_at_ms > 0) {
        int64_t stall_threshold_ms = source_interval_ms * 3;
        if (stall_threshold_ms < RENDER_GAP_FLOOR_MS) {
            stall_threshold_ms = RENDER_GAP_FLOOR_MS;
        }
        session->stall_active = (now_ms - session->last_decode_at_ms) >= stall_threshold_ms;
    } else {
        session->stall_active = false;
    }
    if (!session->stall_active &&
        (now_ms - session->last_source_pts_relock_at_ms) >= RENDER_SOURCE_ESTIMATE_UPDATE_INTERVAL_MS) {
        int64_t pts_interval_ms = queue_pts_interval_ms_locked(session, 24);
        if (pts_interval_ms > 0 &&
            decode_delta_is_plausible_cadence(pts_interval_ms, source_interval_ms)) {
            int64_t old_interval_ms = session->source_render_interval_ms;
            apply_pts_source_interval_locked(session, pts_interval_ms, false);
            source_interval_ms = session->source_render_interval_ms;
            session->last_source_pts_relock_at_ms = now_ms;
            if (llabs(session->source_render_interval_ms - old_interval_ms) >= 2) {
                ct_debug(TAG,
                         "render source estimate relocked id=%lld designator=%s oldIntervalMs=%lld newIntervalMs=%lld ptsIntervalMs=%lld targetLatencyMs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) old_interval_ms,
                         (long long) session->source_render_interval_ms,
                         (long long) pts_interval_ms,
                         (long long) session->target_latency_ms);
            }
        }
    }

    double error_ratio = 0.0;
    if (target_latency_ms > 0) {
        error_ratio = (double) (buffered_span_ms - target_latency_ms) / (double) target_latency_ms;
    }
    if (error_ratio < -0.5) error_ratio = -0.5;
    if (error_ratio > 4.0) error_ratio = 4.0;

    double adjust_pct = error_ratio * 12.0;
    double backlog_ratio = target_latency_ms > 0
            ? (double) buffered_span_ms / (double) target_latency_ms
            : 1.0;
    double max_adjust_pct = RENDER_INTERVAL_ADJUST_BASE_PCT;
    if (backlog_ratio >= 1.5) max_adjust_pct = 20.0;
    if (backlog_ratio >= 2.0) max_adjust_pct = 28.0;
    if (backlog_ratio >= 3.0) max_adjust_pct = 35.0;
    if (backlog_ratio >= 5.0) max_adjust_pct = 40.0;
    if (adjust_pct < -RENDER_INTERVAL_ADJUST_BASE_PCT) adjust_pct = -RENDER_INTERVAL_ADJUST_BASE_PCT;
    if (adjust_pct > max_adjust_pct) adjust_pct = max_adjust_pct;

    int64_t desired_interval_ms =
            (int64_t) llround((double) source_interval_ms * (100.0 - adjust_pct) / 100.0);
    bool preserve_during_stall =
            session->stall_active &&
            buffered_span_ms <= ((target_latency_ms * 5) / 4);
    if (preserve_during_stall) {
        int64_t preserve_interval_ms = (source_interval_ms * 108 + 99) / 100;
        if (desired_interval_ms < preserve_interval_ms) {
            desired_interval_ms = preserve_interval_ms;
        }
    }

    int64_t min_interval_ms = (source_interval_ms * (100 - RENDER_INTERVAL_ADJUST_MAX_PCT) + 99) / 100;
    int64_t max_interval_ms = (source_interval_ms * (100 + RENDER_INTERVAL_ADJUST_BASE_PCT) + 99) / 100;
    desired_interval_ms = clamp_i64(desired_interval_ms, min_interval_ms, max_interval_ms);

    int64_t previous_interval_ms = session->render_interval_smoothed_ms > 0
            ? session->render_interval_smoothed_ms
            : source_interval_ms;
    int64_t smoothed_interval_ms =
            ((previous_interval_ms * (100 - RENDER_INTERVAL_SMOOTHING_PCT)) +
             (desired_interval_ms * RENDER_INTERVAL_SMOOTHING_PCT) + 50) / 100;
    smoothed_interval_ms = clamp_i64(smoothed_interval_ms, min_interval_ms, max_interval_ms);
    session->render_interval_smoothed_ms = smoothed_interval_ms;

    bool periodic_log =
            (now_ms - session->last_render_control_log_at_ms) >= RENDER_CONTROL_LOG_INTERVAL_MS;
    if (periodic_log) {
        session->last_render_control_log_at_ms = now_ms;
        ct_debug(TAG,
                 "render control id=%lld designator=%s bufferedSpanMs=%lld targetLatencyMs=%lld stallEstimateMs=%lld provenGapMs=%lld sourceIntervalMs=%lld renderIntervalMs=%lld desiredIntervalMs=%lld stallActive=%d queueDepth=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) buffered_span_ms,
                 (long long) target_latency_ms,
                 (long long) session->stall_estimate_ms,
                 (long long) session->proven_gap_ms,
                 (long long) source_interval_ms,
                 (long long) smoothed_interval_ms,
                 (long long) desired_interval_ms,
                 session->stall_active ? 1 : 0,
                 session->render_queue_depth);
    }

    return smoothed_interval_ms;
}

#if HAVE_SWSCALE
static void log_render_queue_state(ffmpeg_session_t *session, int queue_depth, bool force) {
    if (session == NULL || !session->is_render) return;
    int64_t now_ms = monotonic_ms();
    if (queue_depth >= RENDER_QUEUE_WARN_DEPTH) {
        bool first_warn = session->last_render_queue_warn_at_ms <= 0;
        bool warn_interval_elapsed =
                (now_ms - session->last_render_queue_warn_at_ms) >= RENDER_QUEUE_WARN_INTERVAL_MS;
        if (first_warn || warn_interval_elapsed) {
            session->last_render_queue_warn_at_ms = now_ms;
            int64_t interval_ms = current_render_interval_ms(session);
            int64_t approx_backlog_ms = (int64_t) queue_depth * interval_ms;
            ct_warn(TAG,
                    "render queue backlog high id=%lld designator=%s depth=%d approxBacklogMs=%lld intervalMs=%lld",
                    (long long) session->session_id,
                    session->designator,
                    queue_depth,
                    (long long) approx_backlog_ms,
                    (long long) interval_ms);
        }
    }
    int depth_delta = queue_depth - session->last_logged_render_queue_depth;
    if (depth_delta < 0) depth_delta = -depth_delta;
    int last_depth = session->last_logged_render_queue_depth;
    bool crossed_backlog_band =
            (last_depth < 8 && queue_depth >= 8) ||
            (last_depth >= 8 && queue_depth < 8);
    bool significant_depth_change =
            depth_delta >= 8 || crossed_backlog_band;
    int64_t drop_delta = session->render_drop_count -
                         session->last_logged_render_drop_count;
    if (session->last_logged_render_drop_count < 0) {
        drop_delta = session->render_drop_count;
    }
    bool significant_drop = drop_delta >= 8;
    bool periodic_log = (now_ms - session->last_render_queue_log_at_ms) >= RENDER_QUEUE_LOG_INTERVAL_MS;
    bool first_log = session->last_render_queue_log_at_ms <= 0;
    if (!force && !first_log && !periodic_log && !significant_depth_change && !significant_drop) return;

    session->last_logged_render_queue_depth = queue_depth;
    session->last_logged_render_drop_count = session->render_drop_count;
    session->last_render_queue_log_at_ms = now_ms;
}

static void clear_render_queue_slot(render_queue_slot_t *slot) {
    if (slot == NULL) return;
    av_frame_free(&slot->frame);
    slot->source_ts_us = 0;
    slot->enqueued_at_ms = 0;
}

static void clear_render_queue(ffmpeg_session_t *session) {
    if (session == NULL) return;
    if (session->render_queue != NULL && session->render_queue_capacity > 0) {
        for (int i = 0; i < session->render_queue_depth; i++) {
            int idx = (session->render_queue_head + i) % session->render_queue_capacity;
            clear_render_queue_slot(&session->render_queue[idx]);
        }
    }
    session->render_queue_head = 0;
    session->render_queue_depth = 0;
}

static void reset_render_timing_state_locked(ffmpeg_session_t *session, bool observe_startup) {
    if (session == NULL) return;
    session->source_render_interval_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    session->render_interval_smoothed_ms = RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    session->next_render_due_ms = 0;
    session->last_render_control_log_at_ms = 0;
    session->startup_observation_active = observe_startup;
    session->startup_started_at_ms = monotonic_ms();
    session->source_interval_confidence = 0;
    session->stall_estimate_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    session->target_latency_ms = 1000;
    session->stall_active = false;
    session->last_decode_at_ms = 0;
    session->last_valid_pts_us = 0;
    session->last_source_estimate_update_at_ms = 0;
    session->last_gap_at_ms = 0;
    session->last_stall_decay_at_ms = 0;
    session->proven_gap_ms = RENDER_STALL_ESTIMATE_FLOOR_MS;
    session->last_proven_gap_decay_at_ms = 0;
    memset(session->cadence_samples_ms, 0, sizeof(session->cadence_samples_ms));
    session->cadence_sample_count = 0;
    session->cadence_sample_head = 0;
    memset(session->gap_samples_ms, 0, sizeof(session->gap_samples_ms));
    session->gap_sample_count = 0;
    session->gap_sample_head = 0;
}

static void trim_render_queue_to_latest(ffmpeg_session_t *session, int keep_latest) {
    if (session == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= keep_latest ||
        keep_latest < 1) {
        return;
    }

    int drop_count = session->render_queue_depth - keep_latest;
    for (int i = 0; i < drop_count; i++) {
        int idx = (session->render_queue_head + i) % session->render_queue_capacity;
        clear_render_queue_slot(&session->render_queue[idx]);
    }

    session->render_queue_head = (session->render_queue_head + drop_count) % session->render_queue_capacity;
    session->render_queue_depth = keep_latest;
    session->render_drop_count += drop_count;

    ct_warn(TAG,
            "render queue trimmed to live edge id=%lld designator=%s dropped=%d keep=%d",
            (long long) session->session_id,
            session->designator,
            drop_count,
            keep_latest);
}

static int compute_trim_keep_latest_locked(const ffmpeg_session_t *session,
                                           int64_t source_interval_ms,
                                           int64_t target_latency_ms) {
    if (session == NULL) return 8;
    if (source_interval_ms <= 0) {
        source_interval_ms = session->source_render_interval_ms > 0
                ? session->source_render_interval_ms
                : RENDER_SOURCE_INTERVAL_DEFAULT_MS;
    }
    if (target_latency_ms <= 0) {
        target_latency_ms = session->target_latency_ms > 0
                ? session->target_latency_ms
                : RENDER_TARGET_LATENCY_MIN_MS;
    }
    int keep_latest = (int) ((target_latency_ms + source_interval_ms - 1) / source_interval_ms);
    if (keep_latest < 8) keep_latest = 8;
    if (keep_latest > 36) keep_latest = 36;
    return keep_latest;
}

static int compute_render_queue_hard_cap_locked(const ffmpeg_session_t *session,
                                                int keep_latest) {
    if (session == NULL) return 24;
    if (keep_latest < 8) {
        keep_latest = compute_trim_keep_latest_locked(
                session,
                session->source_render_interval_ms,
                session->target_latency_ms);
    }
    int hard_cap = keep_latest * 2;
    if (hard_cap < (keep_latest + 12)) hard_cap = keep_latest + 12;
    if (hard_cap < 24) hard_cap = 24;
    if (hard_cap > 72) hard_cap = 72;
    return hard_cap;
}

static void destroy_render_queue_storage(ffmpeg_session_t *session) {
    if (session == NULL) return;
    clear_render_queue(session);
    if (session->render_queue != NULL) {
        free(session->render_queue);
        session->render_queue = NULL;
    }
    session->render_queue_capacity = 0;
}

static bool ensure_render_queue_capacity(ffmpeg_session_t *session, int min_capacity) {
    if (session == NULL) return false;
    if (min_capacity < 1) min_capacity = 1;
    if (session->render_queue != NULL && session->render_queue_capacity >= min_capacity) {
        return true;
    }

    int new_capacity = session->render_queue_capacity;
    if (new_capacity < RENDER_QUEUE_INITIAL_CAPACITY) {
        new_capacity = RENDER_QUEUE_INITIAL_CAPACITY;
    }
    while (new_capacity < min_capacity) {
        if (new_capacity < 4096) {
            new_capacity *= 2;
        } else {
            new_capacity += 1024;
        }
    }

    render_queue_slot_t *new_queue =
            (render_queue_slot_t *) calloc((size_t) new_capacity, sizeof(render_queue_slot_t));
    if (new_queue == NULL) return false;

    if (session->render_queue != NULL && session->render_queue_depth > 0 && session->render_queue_capacity > 0) {
        for (int i = 0; i < session->render_queue_depth; i++) {
            int src_idx = (session->render_queue_head + i) % session->render_queue_capacity;
            new_queue[i] = session->render_queue[src_idx];
        }
    }

    free(session->render_queue);
    session->render_queue = new_queue;
    session->render_queue_capacity = new_capacity;
    session->render_queue_head = 0;
    return true;
}

static int render_queue_tail_index(const ffmpeg_session_t *session) {
    if (session == NULL || session->render_queue_capacity <= 0) return 0;
    return (session->render_queue_head + session->render_queue_depth) % session->render_queue_capacity;
}

static bool enqueue_render_frame(ffmpeg_session_t *session,
                                 AVFrame *decoded,
                                 int64_t source_ts_us,
                                 int64_t enqueued_at_ms) {
    if (session == NULL || decoded == NULL) return false;
    if (!ensure_render_queue_capacity(session, session->render_queue_depth + 1)) return false;

    AVFrame *cloned = av_frame_clone(decoded);
    if (cloned == NULL) return false;

    int tail_idx = render_queue_tail_index(session);
    session->render_queue[tail_idx].frame = cloned;
    session->render_queue[tail_idx].source_ts_us = source_ts_us;
    session->render_queue[tail_idx].enqueued_at_ms = enqueued_at_ms;
    session->render_queue_depth += 1;
    int keep_latest = compute_trim_keep_latest_locked(
            session,
            session->source_render_interval_ms,
            session->target_latency_ms);
    int hard_cap = compute_render_queue_hard_cap_locked(session, keep_latest);
    if (session->render_queue_depth > hard_cap) {
        trim_render_queue_to_latest(session, keep_latest);
    }
    log_render_queue_state(session, session->render_queue_depth, false);
    return true;
}

static bool dequeue_due_render_frame_locked(ffmpeg_session_t *session,
                                            AVFrame **out_frame,
                                            int64_t *out_source_ts_us,
                                            int64_t *out_render_latency_ms) {
    if (session == NULL ||
        out_frame == NULL ||
        out_source_ts_us == NULL ||
        out_render_latency_ms == NULL ||
        session->render_queue == NULL ||
        session->render_queue_capacity <= 0 ||
        session->render_queue_depth <= 0) {
        return false;
    }

    int64_t now_ms = monotonic_ms();
    int oldest_index = session->render_queue_head;
    int64_t queue_age_ms = now_ms - session->render_queue[oldest_index].enqueued_at_ms;
    int64_t buffered_span_ms = buffered_span_ms_locked(session);

    if (session->startup_observation_active) {
        int64_t observe_elapsed_ms = now_ms - session->startup_started_at_ms;
        if (observe_elapsed_ms < RENDER_STARTUP_OBSERVE_MS) {
            bool periodic_log =
                    (now_ms - session->last_render_control_log_at_ms) >= RENDER_CONTROL_LOG_INTERVAL_MS;
            if (periodic_log) {
                session->last_render_control_log_at_ms = now_ms;
                ct_debug(TAG,
                         "render startup observing id=%lld designator=%s elapsedMs=%lld queueDepth=%d bufferedSpanMs=%lld queueAgeMs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) observe_elapsed_ms,
                         session->render_queue_depth,
                         (long long) buffered_span_ms,
                         (long long) queue_age_ms);
            }
            return false;
        }
        finalize_startup_estimates_locked(session);
        session->startup_observation_active = false;
        session->next_render_due_ms = now_ms;
    }

    int64_t interval_ms = compute_desired_render_interval_ms_locked(session, buffered_span_ms, now_ms);
    int64_t base_interval_ms = session->source_render_interval_ms > 0
            ? session->source_render_interval_ms
            : RENDER_SOURCE_INTERVAL_DEFAULT_MS;

    if (session->target_latency_ms > 0 &&
        buffered_span_ms >= (session->target_latency_ms * 2)) {
        int keep_latest =
                compute_trim_keep_latest_locked(session, base_interval_ms, session->target_latency_ms);
        trim_render_queue_to_latest(session, keep_latest);
        oldest_index = session->render_queue_head;
        buffered_span_ms = buffered_span_ms_locked(session);
    }

    if (session->next_render_due_ms <= 0) {
        session->next_render_due_ms = now_ms;
    }
    if (now_ms < session->next_render_due_ms) {
        return false;
    }

    int64_t scheduled_due_ms = session->next_render_due_ms;
    int64_t lag_ms = now_ms - scheduled_due_ms;
    int64_t lag_budget_ms = interval_ms - lag_ms;
    bool periodic_lag_log = (now_ms - session->last_render_lag_log_at_ms) >= RENDER_LAG_LOG_INTERVAL_MS;
    bool severe_lag = lag_ms >= (base_interval_ms * 2);
    if (severe_lag || periodic_lag_log) {
        session->last_render_lag_log_at_ms = now_ms;
    }

    *out_frame = session->render_queue[oldest_index].frame;
    *out_source_ts_us = session->render_queue[oldest_index].source_ts_us;
    session->render_queue[oldest_index].frame = NULL;
    session->render_queue[oldest_index].source_ts_us = 0;
    session->render_queue[oldest_index].enqueued_at_ms = 0;
    session->render_queue_head = (session->render_queue_head + 1) % session->render_queue_capacity;
    session->render_queue_depth -= 1;
    int64_t remaining_buffered_span_ms = buffered_span_ms_locked(session);
    *out_render_latency_ms = remaining_buffered_span_ms;
    if (session->render_queue_depth <= 0) {
        session->render_queue_depth = 0;
        session->render_queue_head = 0;
        *out_render_latency_ms = 0;
        session->next_render_due_ms = 0;
    }
    log_render_queue_state(session, session->render_queue_depth, false);

    session->next_render_due_ms = scheduled_due_ms + interval_ms;
    if (session->next_render_due_ms <= now_ms) {
        int64_t skipped_ticks = ((now_ms - session->next_render_due_ms) / interval_ms) + 1;
        session->next_render_due_ms += (skipped_ticks * interval_ms);
    }
    return *out_frame != NULL;
}

static void render_cond_timed_wait_ms(pthread_cond_t *cond,
                                      pthread_mutex_t *mutex,
                                      int64_t wait_ms) {
    if (cond == NULL || mutex == NULL || wait_ms <= 0) return;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += (time_t) (wait_ms / 1000);
    long add_ns = (long) ((wait_ms % 1000) * 1000000LL);
    ts.tv_nsec += add_ns;
    if (ts.tv_nsec >= 1000000000L) {
        ts.tv_sec += 1;
        ts.tv_nsec -= 1000000000L;
    }
    pthread_cond_timedwait(cond, mutex, &ts);
}

static void *render_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;
    if (session == NULL) return NULL;

    while (session_running(session)) {
        AVFrame *to_render = NULL;
        int64_t source_ts_us = 0;
        int64_t render_latency_ms = 0;
        int64_t wait_ms = 20;
        bool should_stop = false;

        pthread_mutex_lock(&session->render_lock);
        // Surface-detach flush: discard all queued frames when the surface has gone
        // away.  This prevents unbounded render-queue growth during the interval
        // between detach and the next surface attachment.
        if (session->render_queue_flush_requested) {
            clear_render_queue(session);
            session->render_queue_flush_requested = false;
        }
        if (session->render_queue_depth > 0 &&
            dequeue_due_render_frame_locked(session,
                                            &to_render,
                                            &source_ts_us,
                                            &render_latency_ms)) {
            pthread_mutex_unlock(&session->render_lock);
            render_frame_to_surface(session, to_render, source_ts_us, render_latency_ms);
            av_frame_free(&to_render);
            continue;
        }

        should_stop = session->render_thread_stop;
        if (session->render_queue_depth > 0) {
            int64_t now_ms = monotonic_ms();
            if (session->next_render_due_ms <= now_ms) {
                wait_ms = 1;
            } else {
                wait_ms = session->next_render_due_ms - now_ms;
                if (wait_ms > 20) wait_ms = 20;
            }
        }
        if (!should_stop) {
            render_cond_timed_wait_ms(&session->render_cond, &session->render_lock, wait_ms);
            should_stop = session->render_thread_stop;
        }
        pthread_mutex_unlock(&session->render_lock);

        // Monitor the decode thread for reader stalls and notify the Java side periodically.
        // With no socket timeout the decode thread blocks silently in av_read_frame() while
        // mediamtx is alive but the RTMP publisher is idle.  The render thread can see
        // reader_waiting_since_ms (written by the decode thread without the render lock; a
        // stale read is harmless here) and fires "reader_wait_long" events so the Java health-
        // classifier knows to suppress unnecessary session restarts.
        {
            int64_t now_ms = monotonic_ms();
            int64_t waiting_since = session->reader_waiting_since_ms; // lock-free read: acceptable
            if (waiting_since > 0) {
                int64_t wait_duration_ms = now_ms - waiting_since;
                int64_t last_event = session->last_reader_wait_event_ms;
                // First event after the stall has lasted 1 s; then every ~2.5 s thereafter.
                bool first_event  = (last_event == 0 && wait_duration_ms >= 1000);
                bool repeat_event = (last_event > 0  && (now_ms - last_event) >= 2500);
                if (first_event || repeat_event) {
                    session->last_reader_wait_event_ms = now_ms;
                    dispatch_probe_event(session->designator, "reader_wait_long",
                                         session->session_id, 0,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            } else {
                // Stall ended – reset so the next stall gets its own first-event at 1 s.
                session->last_reader_wait_event_ms = 0;
            }
        }

        if (should_stop) break;
    }
    return NULL;
}
#endif

typedef struct {
    bool has_any;
    bool has_explicit_ts;
    int64_t ts_us;
    char remote_id[128];
    double lat;
    double lng;
    double alt;
    double gimbal_pitch;
    double camera_yaw;
    double heading;
} telemetry_values_t;

static telemetry_values_t telemetry_values_init(void) {
    telemetry_values_t out;
    out.has_any = false;
    out.has_explicit_ts = false;
    out.ts_us = 0;
    out.remote_id[0] = '\0';
    out.lat = NAN;
    out.lng = NAN;
    out.alt = NAN;
    out.gimbal_pitch = NAN;
    out.camera_yaw = NAN;
    out.heading = NAN;
    return out;
}

static bool key_contains(const char *haystack, const char *needle) {
    return strstr(haystack, needle) != NULL;
}

static void lowercase_copy(char *dst, size_t dst_size, const char *src) {
    if (dst_size == 0) return;
    size_t i = 0;
    for (; src[i] != '\0' && i + 1 < dst_size; i++) {
        dst[i] = (char) tolower((unsigned char) src[i]);
    }
    dst[i] = '\0';
}

static bool parse_double_value(const char *val, double *out) {
    if (val == NULL || out == NULL) return false;
    char *end = NULL;
    double v = strtod(val, &end);
    if (end == val) return false;
    *out = v;
    return true;
}

static bool parse_int64_value(const char *val, int64_t *out) {
    if (val == NULL || out == NULL) return false;
    char *end = NULL;
    long long v = strtoll(val, &end, 10);
    if (end == val) return false;
    *out = (int64_t) v;
    return true;
}

static bool parse_string_value(const char *val, char *out, size_t out_size) {
    if (val == NULL || out == NULL || out_size == 0) return false;
    while (*val == ' ' || *val == '\t' || *val == '\n' || *val == '\r') val++;
    if (*val == '\0') return false;
    snprintf(out, out_size, "%s", val);
    size_t n = strlen(out);
    while (n > 0 &&
           (out[n - 1] == ' ' || out[n - 1] == '\t' || out[n - 1] == '\n' || out[n - 1] == '\r')) {
        out[n - 1] = '\0';
        n--;
    }
    return n > 0;
}

static void map_telemetry_key_value(telemetry_values_t *tv, const char *key, const char *val) {
    if (tv == NULL || key == NULL || val == NULL) return;

    char k[128];
    lowercase_copy(k, sizeof(k), key);

    double d = 0.0;
    int64_t i64 = 0;

    if ((strcmp(k, "lat") == 0 || key_contains(k, "latitude")) && parse_double_value(val, &d)) {
        tv->lat = d;
        tv->has_any = true;
        return;
    }
    if ((strcmp(k, "lon") == 0 || key_contains(k, "longitude") || key_contains(k, "long")) &&
        parse_double_value(val, &d)) {
        tv->lng = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "alt") && parse_double_value(val, &d)) {
        tv->alt = d;
        tv->has_any = true;
        return;
    }
    if (key_contains(k, "gimbal") && key_contains(k, "pitch") && parse_double_value(val, &d)) {
        tv->gimbal_pitch = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "camera") || key_contains(k, "gimbal")) && key_contains(k, "yaw") &&
        parse_double_value(val, &d)) {
        tv->camera_yaw = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "heading") || key_contains(k, "course")) && parse_double_value(val, &d)) {
        tv->heading = d;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "timestamp") || key_contains(k, "time_us") || key_contains(k, "pts")) &&
        parse_int64_value(val, &i64)) {
        tv->ts_us = i64;
        tv->has_explicit_ts = true;
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "remoteid") || key_contains(k, "remote_id") ||
         key_contains(k, "serial") || key_contains(k, "uasid")) &&
        parse_string_value(val, tv->remote_id, sizeof(tv->remote_id))) {
        tv->has_any = true;
    }
}

static telemetry_values_t collect_dict_telemetry_values(AVDictionary *dict, int64_t fallback_ts_us) {
    telemetry_values_t tv = telemetry_values_init();
    tv.ts_us = fallback_ts_us;
    if (dict == NULL) return tv;

    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        map_telemetry_key_value(&tv, entry->key, entry->value);
    }

    return tv;
}

static void emit_telemetry_values(ffmpeg_session_t *session,
                                  const char *source_tag,
                                  double confidence,
                                  const telemetry_values_t *tv) {
    if (session == NULL || tv == NULL || !tv->has_any) return;
    dispatch_probe_event_ex(
            session->designator,
            "telemetry",
            session->session_id,
            source_tag,
            confidence,
            tv->remote_id,
            tv->ts_us,
            0,
            tv->lat,
            tv->lng,
            tv->alt,
            tv->gimbal_pitch,
            tv->camera_yaw,
            tv->heading);
}

static void log_dict_keys_once(ffmpeg_session_t *session,
                               AVDictionary *dict,
                               const char *source_tag,
                               bool *already_logged) {
    if (session == NULL || dict == NULL || source_tag == NULL || already_logged == NULL || *already_logged) {
        return;
    }

    char keys[512];
    keys[0] = '\0';
    size_t used = 0;
    bool first = true;

    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        const char *sep = first ? "" : ",";
        int written = snprintf(keys + used, sizeof(keys) - used, "%s%s", sep, entry->key);
        if (written < 0) break;
        if ((size_t) written >= sizeof(keys) - used) {
            used = sizeof(keys) - 1;
            break;
        }
        used += (size_t) written;
        first = false;
    }

    if (first) {
        snprintf(keys, sizeof(keys), "<empty>");
    } else if (used >= sizeof(keys) - 1 && sizeof(keys) >= 5) {
        memcpy(keys + sizeof(keys) - 5, "...", 4);
        keys[sizeof(keys) - 1] = '\0';
    }

    *already_logged = true;
}

static void emit_dict_telemetry(ffmpeg_session_t *session,
                                AVDictionary *dict,
                                const char *source_tag,
                                double confidence,
                                int64_t fallback_ts_us) {
    if (session == NULL || dict == NULL) return;
    telemetry_values_t tv = collect_dict_telemetry_values(dict, fallback_ts_us);
    emit_telemetry_values(session, source_tag, confidence, &tv);
}

static bool fps_from_rate(AVRational rate, double *out_fps) {
    if (out_fps == NULL) return false;
    if (rate.num <= 0 || rate.den <= 0) return false;
    double fps = av_q2d(rate);
    if (!(fps > 1.0 && fps <= 240.0)) return false;
    *out_fps = fps;
    return true;
}

static int64_t interval_from_fps(double fps) {
    if (!(fps > 1.0)) return 0;
    int64_t ms = (int64_t) llround(1000.0 / fps);
    return clamp_i64(ms, RENDER_MIN_INTERVAL_MS, RENDER_MAX_INTERVAL_MS);
}

static int64_t provisional_interval_from_stream(const AVStream *stream,
                                                double *out_fps,
                                                const char **out_source) {
    if (out_fps != NULL) *out_fps = 0.0;
    if (out_source != NULL) *out_source = "none";
    if (stream == NULL) return 0;

    double fps = 0.0;
    if (fps_from_rate(stream->avg_frame_rate, &fps)) {
        if (out_fps != NULL) *out_fps = fps;
        if (out_source != NULL) *out_source = "avg_frame_rate";
        return interval_from_fps(fps);
    }
    if (fps_from_rate(stream->r_frame_rate, &fps)) {
        if (out_fps != NULL) *out_fps = fps;
        if (out_source != NULL) *out_source = "r_frame_rate";
        return interval_from_fps(fps);
    }
    return 0;
}

static int open_input_with_profile(ffmpeg_session_t *session, bool low_latency) {
    AVDictionary *opts = NULL;
    if (session == NULL) return AVERROR(EINVAL);

    if (session->fmt == NULL) {
        session->fmt = avformat_alloc_context();
        if (session->fmt == NULL) {
            return AVERROR(ENOMEM);
        }
    }
    session->fmt->interrupt_callback.callback = ffmpeg_interrupt_cb;
    session->fmt->interrupt_callback.opaque = session;

    // Keep transport deterministic for loopback stability.
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);
    // No rw_timeout/stimeout/timeout: we connect to localhost mediamtx which keeps the RTSP
    // TCP session alive even when the RTMP publisher is idle (via RTSP keepalives).
    // av_read_frame() will block until data arrives, the connection closes (EOF), or the
    // interrupt_callback fires (session->running = false).  ffmpeg's network loop polls every
    // ~100 ms and checks the interrupt_callback, so stop() is still responsive without a
    // per-socket timeout.  The render thread monitors reader_waiting_since_ms and emits
    // "reader_wait_long" probe events so the Java health-classifier stays informed.
    // The render path only needs video; subscribing to audio as well can add interleave stalls.
    if (session != NULL && session->is_render) {
        av_dict_set(&opts, "allowed_media_types", "video", 0);
    }

    if (low_latency) {
        av_dict_set(&opts, "fflags", "nobuffer", 0);
        av_dict_set(&opts, "flags", "low_delay", 0);
        av_dict_set(&opts, "flush_packets", "1", 0);
        av_dict_set(&opts, "reorder_queue_size", "0", 0);
        av_dict_set(&opts, "max_delay", "100000", 0);
        av_dict_set(&opts, "probesize", "32768", 0);
        av_dict_set(&opts, "analyzeduration", "0", 0);
        av_dict_set(&opts, "fpsprobesize", "0", 0);
    } else {
        // Compatibility fallback for streams that dislike aggressive probing.
        // Render sessions only need H264 video, so keep probing modest to shorten startup.
        if (session != NULL && session->is_render) {
            av_dict_set(&opts, "max_delay", "250000", 0);
            av_dict_set(&opts, "probesize", "65536", 0);
            av_dict_set(&opts, "analyzeduration", "250000", 0);
            av_dict_set(&opts, "fpsprobesize", "2", 0);
        } else {
            av_dict_set(&opts, "max_delay", "500000", 0);
            av_dict_set(&opts, "probesize", "262144", 0);
            av_dict_set(&opts, "analyzeduration", "1000000", 0);
        }
    }

    set_io_interrupt_timeout(session, IO_STARTUP_INTERRUPT_MS);
    int rc = avformat_open_input(&session->fmt, session->url, NULL, &opts);
    clear_io_interrupt_timeout(session);
    av_dict_free(&opts);
    return rc;
}

static int open_decoder(ffmpeg_session_t *session) {
    bool prefer_low_latency = true;
    int64_t phase_started_at_ms = monotonic_ms();
    ct_debug(TAG,
             "open_decoder begin id=%lld designator=%s preferLowLatency=%d render=%d",
             (long long) session->session_id,
             session->designator,
             prefer_low_latency ? 1 : 0,
             session->is_render ? 1 : 0);

    int rc = open_input_with_profile(session, prefer_low_latency);
    int64_t open_input_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder open_input id=%lld designator=%s profile=%s elapsedMs=%lld rc=%d",
             (long long) session->session_id,
             session->designator,
             prefer_low_latency ? "low-latency" : "compatibility",
             (long long) open_input_elapsed_ms,
             rc);
    if (rc < 0 && prefer_low_latency) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        memset(errbuf, 0, sizeof(errbuf));
        av_strerror(rc, errbuf, sizeof(errbuf));
        ct_warn(TAG,
                "low-latency open failed id=%lld designator=%s rc=%d err=%s; retrying with compatibility profile",
                (long long) session->session_id,
                session->designator,
                rc,
                errbuf);
        if (session->fmt != NULL) {
            avformat_close_input(&session->fmt);
        }
        phase_started_at_ms = monotonic_ms();
        rc = open_input_with_profile(session, false);
        open_input_elapsed_ms = monotonic_ms() - phase_started_at_ms;
        ct_debug(TAG,
                 "open_decoder open_input id=%lld designator=%s profile=compatibility elapsedMs=%lld rc=%d",
                 (long long) session->session_id,
                 session->designator,
                 (long long) open_input_elapsed_ms,
                 rc);
    }
    if (rc < 0) {
        return rc;
    }

    phase_started_at_ms = monotonic_ms();
    set_io_interrupt_timeout(session, IO_STARTUP_INTERRUPT_MS);
    rc = avformat_find_stream_info(session->fmt, NULL);
    clear_io_interrupt_timeout(session);
    int64_t find_stream_info_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder find_stream_info id=%lld designator=%s elapsedMs=%lld rc=%d",
             (long long) session->session_id,
             session->designator,
             (long long) find_stream_info_elapsed_ms,
             rc);
    if (rc < 0) {
        return rc;
    }

    phase_started_at_ms = monotonic_ms();
    int video_index = av_find_best_stream(session->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    int64_t find_best_stream_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder find_best_stream id=%lld designator=%s elapsedMs=%lld videoIndex=%d",
             (long long) session->session_id,
             session->designator,
             (long long) find_best_stream_elapsed_ms,
             video_index);
    if (video_index < 0) {
        return video_index;
    }

    AVStream *stream = session->fmt->streams[video_index];
    double provisional_fps = 0.0;
    const char *provisional_source = "none";
    int64_t provisional_interval_ms =
            provisional_interval_from_stream(stream, &provisional_fps, &provisional_source);
    if (provisional_interval_ms > 0) {
        session->source_render_interval_ms = provisional_interval_ms;
        session->render_interval_smoothed_ms = provisional_interval_ms;
    }

    const AVCodec *decoder = avcodec_find_decoder(stream->codecpar->codec_id);
    if (decoder == NULL) {
        return AVERROR_DECODER_NOT_FOUND;
    }

    session->codec = avcodec_alloc_context3(decoder);
    if (session->codec == NULL) {
        return AVERROR(ENOMEM);
    }

    rc = avcodec_parameters_to_context(session->codec, stream->codecpar);
    if (rc < 0) return rc;

    session->codec->thread_count = 1;
    session->codec->flags |= AV_CODEC_FLAG_LOW_DELAY;
    session->codec->flags2 |= AV_CODEC_FLAG2_FAST;

    phase_started_at_ms = monotonic_ms();
    rc = avcodec_open2(session->codec, decoder, NULL);
    int64_t codec_open_elapsed_ms = monotonic_ms() - phase_started_at_ms;
    ct_debug(TAG,
             "open_decoder codec_open id=%lld designator=%s elapsedMs=%lld rc=%d codec=%s",
             (long long) session->session_id,
             session->designator,
             (long long) codec_open_elapsed_ms,
             rc,
             decoder->name != NULL ? decoder->name : "unknown");
    if (rc < 0) {
        return rc;
    }

    session->video_stream_index = video_index;
    session->video_time_base = stream->time_base;

    emit_dict_telemetry(session, session->fmt->metadata, "format-metadata", 0.40, 0);
    emit_dict_telemetry(session, stream->metadata, "stream-metadata", 0.50, 0);
    return 0;
}

static void close_decoder(ffmpeg_session_t *session) {
#if HAVE_SWSCALE
    if (session->sws != NULL) {
        sws_freeContext(session->sws);
        session->sws = NULL;
    }
    if (session->rgba_frame != NULL) {
        av_frame_free(&session->rgba_frame);
    }
    if (session->rgba_buffer != NULL) {
        av_free(session->rgba_buffer);
        session->rgba_buffer = NULL;
    }
    if (session->anomaly_sws != NULL) {
        sws_freeContext(session->anomaly_sws);
        session->anomaly_sws = NULL;
    }
    if (session->anomaly_back_sws != NULL) {
        sws_freeContext(session->anomaly_back_sws);
        session->anomaly_back_sws = NULL;
    }
    if (session->anomaly_rgba_frame != NULL) {
        av_frame_free(&session->anomaly_rgba_frame);
    }
    if (session->anomaly_rgba_buffer != NULL) {
        av_free(session->anomaly_rgba_buffer);
        session->anomaly_rgba_buffer = NULL;
    }
    anomaly_state_cleanup(&session->anomaly_state);
    session->anomaly_rgba_buffer_size = 0;
    session->anomaly_rgba_width = 0;
    session->anomaly_rgba_height = 0;
    session->anomaly_src_fmt = AV_PIX_FMT_NONE;
#endif
    if (session->codec != NULL) {
        avcodec_free_context(&session->codec);
    }
    if (session->fmt != NULL) {
        avformat_close_input(&session->fmt);
    }
    session->video_stream_index = -1;
}

static void run_decode_loop(ffmpeg_session_t *session) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    memset(errbuf, 0, sizeof(errbuf));

    avformat_network_init();

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (pkt == NULL || frame == NULL) {
        dispatch_probe_event(session->designator, "decoder_alloc_error", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        if (pkt != NULL) av_packet_free(&pkt);
        if (frame != NULL) av_frame_free(&frame);
        return;
    }

    // Full render-state reset and render-thread startup happen only once, before
    // the first connection attempt.  Subsequent reconnects skip the EMA/buffer
    // fields so the tuned gap history survives the 20-second DJI session rotation.
    if (session->is_render) {
        session->render_drop_count = 0;
        session->last_logged_render_queue_depth = -1;
        session->last_logged_render_drop_count = -1;
        session->last_render_queue_log_at_ms = 0;
        session->last_render_queue_warn_at_ms = 0;
        session->last_render_lag_log_at_ms = 0;
        reset_render_timing_state_locked(session, true);
        session->gap_sample_head = 0;
        session->reader_stall_started_at_ms = 0;
        session->last_reader_stall_log_at_ms = 0;
        session->reader_stall_timeout_events = 0;
        session->reader_stall_error_events = 0;
        session->reader_waiting_since_ms = 0;
        session->reader_reconnecting_since_ms = 0;
        session->last_reader_wait_event_ms = 0;
        ct_debug(TAG,
                 "render startup observe begin id=%lld designator=%s observeMs=%d",
                 (long long) session->session_id,
                 session->designator,
                 RENDER_STARTUP_OBSERVE_MS);
#if HAVE_SWSCALE
        if (session->render_sync_ready) {
            pthread_mutex_lock(&session->render_lock);
            session->render_thread_stop = false;
            clear_render_queue(session);
            if (!ensure_render_queue_capacity(session, RENDER_QUEUE_INITIAL_CAPACITY)) {
                ct_warn(TAG,
                        "render queue prealloc failed id=%lld designator=%s",
                        (long long) session->session_id,
                        session->designator);
            }
            pthread_mutex_unlock(&session->render_lock);

            int render_thread_rc = pthread_create(&session->render_thread, NULL, render_thread_main, session);
            if (render_thread_rc == 0) {
                session->render_thread_started = true;
            } else {
                session->render_thread_started = false;
                ct_warn(TAG,
                        "render thread start failed id=%lld designator=%s rc=%d",
                        (long long) session->session_id,
                        session->designator,
                        render_thread_rc);
            }
        }
#endif
    }

    // ── Outer reconnect loop ────────────────────────────────────────────────
    // When mediamtx kicks an old RTMP publisher and accepts a new one (which
    // the DJI controller does every ~20 s), the RTSP stream signals EOF.
    // Rather than waiting for the Java layer to call stop/start, we close the
    // decoder, wait briefly for mediamtx to be ready, and reopen it ourselves.
    // The render thread stays alive throughout; reader_reconnecting_since_ms
    // tells it to hold the current frame instead of draining the queue.
    bool first_open = true;
    int64_t reconnect_delay_ms = 150; // head-start: DJI media arrives ~220 ms after connect
    int reconnect_failures = 0;

    while (session_running(session)) {

        if (!first_open) {
            // Signal the render thread to hold the last good frame.
            session->reader_reconnecting_since_ms = monotonic_ms();

            // Brief delay so mediamtx can accept the new DJI publisher before
            // we hammer it with avformat_open_input.
            usleep((useconds_t)(reconnect_delay_ms * 1000));
            if (!session_running(session)) break;
        }

        int rc = open_decoder(session);
        session->reader_reconnecting_since_ms = 0; // connected (or failed)
        session->reader_stall_started_at_ms = 0;
        session->last_reader_stall_log_at_ms = 0;
        session->reader_stall_timeout_events = 0;
        session->reader_stall_error_events = 0;
        session->reader_waiting_since_ms = 0;
        session->last_reader_wait_event_ms = 0;

        if (rc < 0) {
            av_strerror(rc, errbuf, sizeof(errbuf));
            if (first_open) {
                // Initial open failed: bad URL or server not running.
                // Emit error and stop — there is nothing useful to retry here.
                ct_error(TAG,
                         "open_decoder failed id=%lld designator=%s rc=%d err=%s",
                         (long long) session->session_id,
                         session->designator,
                         rc,
                         errbuf);
                dispatch_probe_event(session->designator, "decoder_open_error", session->session_id, 0,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
                break;
            }
            // Reconnect failed — mediamtx may not have a publisher yet.
            // Back off slightly and try again, up to a limit.
            reconnect_failures++;
            reconnect_delay_ms = (reconnect_delay_ms < 1000) ? reconnect_delay_ms + 100 : 1000;
            ct_debug(TAG,
                     "reconnect open failed id=%lld designator=%s attempt=%d rc=%d err=%s",
                     (long long) session->session_id,
                     session->designator,
                     reconnect_failures,
                     rc,
                     errbuf);
            if (reconnect_failures > 20) {
                ct_error(TAG,
                         "reconnect giving up id=%lld designator=%s after %d attempts",
                         (long long) session->session_id,
                         session->designator,
                         reconnect_failures);
                dispatch_probe_event(session->designator, "decoder_open_error", session->session_id, 0,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
                break;
            }
            continue;
        }

        // Successful (re)connect.
        reconnect_failures = 0;
        reconnect_delay_ms = 150;

        dispatch_probe_event(session->designator,
                             first_open ? "decoder_opened" : "decoder_reconnected",
                             session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);

        if (!first_open) {
            if (session->is_render) {
                reset_render_timing_state_locked(session, true);
                ct_debug(TAG,
                         "render startup observe begin id=%lld designator=%s observeMs=%d reconnect=1",
                         (long long) session->session_id,
                         session->designator,
                         RENDER_STARTUP_OBSERVE_MS);
            }
        }
        first_open = false;

    // ── Inner decode loop ───────────────────────────────────────────────────
    int64_t last_video_packet_at_ms = 0;
    int64_t last_decoded_frame_at_ms = 0;
    int startup_packet_log_count = 0;

    while (session_running(session)) {
        int64_t read_started_at_ms = monotonic_ms();
        // Expose blocking start time so the render thread can detect an implicit stall
        // before ETIMEDOUT fires on the socket timeout.
        session->reader_waiting_since_ms = read_started_at_ms;
        // Let ordinary source stalls block here until packets resume.
        // Explicit stop() still interrupts via ffmpeg_interrupt_cb when running=false.
        rc = av_read_frame(session->fmt, pkt);
        session->reader_waiting_since_ms = 0;
        int64_t read_elapsed_ms = monotonic_ms() - read_started_at_ms;
            if (read_elapsed_ms >= 500) {
                const char *err_suffix = "";
                if (rc < 0) {
                    av_strerror(rc, errbuf, sizeof(errbuf));
                    err_suffix = errbuf;
            }
            ct_debug(TAG,
                     "av_read_frame wait id=%lld designator=%s elapsedMs=%lld rc=%d%s%s",
                     (long long) session->session_id,
                     session->designator,
                     (long long) read_elapsed_ms,
                     rc,
                     rc < 0 ? " err=" : "",
                     rc < 0 ? err_suffix : "");
                if (read_elapsed_ms >= 1000) {
                    dispatch_probe_event(session->designator, "reader_wait_long", session->session_id, 0,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            }
        if (rc == AVERROR_EOF) {
            // Stream ended (publisher disconnected from mediamtx).
            // Break out of the inner loop so the outer reconnect loop can
            // close and reopen the decoder rather than spinning on a dead socket.
            av_packet_unref(pkt);
            break;
        }
        if (rc < 0) {
            int64_t now_ms = monotonic_ms();
            bool timeout = (rc == AVERROR(ETIMEDOUT));
            if (session->reader_stall_started_at_ms <= 0) {
                session->reader_stall_started_at_ms = now_ms;
            }
            if (timeout) {
                session->reader_stall_timeout_events += 1;
            } else {
                session->reader_stall_error_events += 1;
            }
            bool first_stall_log = session->last_reader_stall_log_at_ms <= 0;
            bool periodic_stall_log = (now_ms - session->last_reader_stall_log_at_ms) >= 1000;
            if (first_stall_log || periodic_stall_log) {
                session->last_reader_stall_log_at_ms = now_ms;
                av_strerror(rc, errbuf, sizeof(errbuf));
                ct_debug(TAG,
                         "reader stall id=%lld designator=%s stallMs=%lld timeoutEvents=%lld errorEvents=%lld rc=%d err=%s",
                         (long long) session->session_id,
                         session->designator,
                         (long long) (now_ms - session->reader_stall_started_at_ms),
                         (long long) session->reader_stall_timeout_events,
                         (long long) session->reader_stall_error_events,
                         rc,
                         errbuf);
            }
            av_packet_unref(pkt);
            continue;
        }
        if (session->reader_stall_started_at_ms > 0) {
            int64_t now_ms = monotonic_ms();
            int64_t stall_ms = now_ms - session->reader_stall_started_at_ms;
            ct_debug(TAG,
                     "reader stall recovered id=%lld designator=%s stallMs=%lld timeoutEvents=%lld errorEvents=%lld streamIndex=%d",
                     (long long) session->session_id,
                     session->designator,
                     (long long) stall_ms,
                     (long long) session->reader_stall_timeout_events,
                     (long long) session->reader_stall_error_events,
                     pkt->stream_index);
            session->reader_stall_started_at_ms = 0;
            session->last_reader_stall_log_at_ms = 0;
            session->reader_stall_timeout_events = 0;
            session->reader_stall_error_events = 0;
        }

        if (pkt->stream_index != session->video_stream_index) {
            av_packet_unref(pkt);
            continue;
        }

        int64_t now_ms = monotonic_ms();
        if (last_video_packet_at_ms != 0) {
            int64_t packet_gap_ms = now_ms - last_video_packet_at_ms;
            if (packet_gap_ms >= 500) {
                int64_t pkt_pts_us = pts_to_us(pkt->pts, session->video_time_base);
                ct_debug(TAG,
                         "video packet gap id=%lld designator=%s gapMs=%lld ptsUs=%lld",
                         (long long) session->session_id,
                         session->designator,
                         (long long) packet_gap_ms,
                         (long long) pkt_pts_us);
                dispatch_probe_event(session->designator, "video_packet_gap", session->session_id, pkt_pts_us,
                                     NAN, NAN, NAN, NAN, NAN, NAN);
            }
        }
        last_video_packet_at_ms = now_ms;

        if (last_decoded_frame_at_ms == 0 && startup_packet_log_count < 8) {
            bool is_keyframe = (pkt->flags & AV_PKT_FLAG_KEY) != 0;
            int64_t pkt_pts_us = pts_to_us(pkt->pts, session->video_time_base);
            startup_packet_log_count++;
            ct_debug(TAG,
                     "startup packet id=%lld designator=%s count=%d key=%d size=%d ptsUs=%lld dts=%lld",
                     (long long) session->session_id,
                     session->designator,
                     startup_packet_log_count,
                     is_keyframe ? 1 : 0,
                     pkt->size,
                     (long long) pkt_pts_us,
                     (long long) pkt->dts);
        }

#if FFMPEG_TELEMETRY_ENABLED
        telemetry_values_t packet_tv = telemetry_values_init();
#if defined(AV_PKT_DATA_STRINGS_METADATA)
        size_t sd_size = 0;
        uint8_t *sd = av_packet_get_side_data(pkt, AV_PKT_DATA_STRINGS_METADATA, &sd_size);
        if (sd != NULL && sd_size > 0) {
            AVDictionary *packet_dict = NULL;
            if (av_packet_unpack_dictionary(sd, (int) sd_size, &packet_dict) >= 0) {
                int64_t pkt_ts_us = pts_to_us(pkt->pts, session->video_time_base);
                log_dict_keys_once(
                        session,
                        packet_dict,
                        "packet-side-data",
                        &session->packet_metadata_keys_logged);
                packet_tv = collect_dict_telemetry_values(packet_dict, pkt_ts_us);
                emit_telemetry_values(session, "packet-side-data", 0.70, &packet_tv);
            }
            av_dict_free(&packet_dict);
        }
#endif  // AV_PKT_DATA_STRINGS_METADATA
#endif  // FFMPEG_TELEMETRY_ENABLED

        // Manual render stride (optional, set via nativeSetRenderStride):
        // When render_stride > 1, every N-1 out of N non-keyframe packets are
        // dropped before decode.  Keyframe (IDR) packets always pass through so
        // the decoder's reference-frame state is never broken.
        if (session->render_stride > 1) {
            bool is_keyframe = (pkt->flags & AV_PKT_FLAG_KEY) != 0;
            if (!is_keyframe) {
                session->render_stride_counter++;
                if ((session->render_stride_counter % session->render_stride) != 0) {
                    av_packet_unref(pkt);
                    continue;
                }
            }
        }

        // Surface-absent gate: discard packets without decoding when no render
        // surface is present.  The RTSP connection is maintained so that when the
        // surface returns (e.g. after a dialog dismissal) decode resumes from the
        // live edge with zero latency from accumulated backlog.
        if (session->is_render && session->surface_paused) {
            av_packet_unref(pkt);
            continue;
        }

        rc = avcodec_send_packet(session->codec, pkt);
        av_packet_unref(pkt);
        if (rc < 0) {
            continue;
        }

        while (session_running(session)) {
            rc = avcodec_receive_frame(session->codec, frame);
            if (rc == AVERROR(EAGAIN) || rc == AVERROR_EOF) {
                break;
            }
            if (rc < 0) {
                break;
            }

            int64_t pts_us = pts_to_us(frame->best_effort_timestamp, session->video_time_base);
#if FFMPEG_TELEMETRY_ENABLED
            log_dict_keys_once(
                    session,
                    frame->metadata,
                    "frame-metadata",
                    &session->frame_metadata_keys_logged);
            telemetry_values_t frame_tv = collect_dict_telemetry_values(frame->metadata, pts_us);
#endif  // FFMPEG_TELEMETRY_ENABLED
            int64_t decoded_at_ms = monotonic_ms();
            if (last_decoded_frame_at_ms != 0) {
                int64_t frame_gap_ms = decoded_at_ms - last_decoded_frame_at_ms;
                if (frame_gap_ms >= 500) {
                    ct_debug(TAG,
                             "decoded frame gap id=%lld designator=%s gapMs=%lld ptsUs=%lld",
                             (long long) session->session_id,
                             session->designator,
                             (long long) frame_gap_ms,
                             (long long) pts_us);
                    dispatch_probe_event(session->designator, "decoded_frame_gap", session->session_id, pts_us,
                                         NAN, NAN, NAN, NAN, NAN, NAN);
                }
            }
            last_decoded_frame_at_ms = decoded_at_ms;
            dispatch_probe_event(session->designator, "frame_decoded", session->session_id, pts_us,
                                 NAN, NAN, NAN, NAN, NAN, NAN);
#if FFMPEG_TELEMETRY_ENABLED
            emit_telemetry_values(session, "frame-metadata", 0.60, &frame_tv);
#endif  // FFMPEG_TELEMETRY_ENABLED

#if HAVE_SWSCALE
            if (session->is_render) {
                analyze_decoded_frame(session, frame, pts_us);
                if (session->render_thread_started && session->render_sync_ready) {
                    bool enqueued = false;
                    pthread_mutex_lock(&session->render_lock);
                    record_decode_timing_sample_locked(session, decoded_at_ms, pts_us);
                    enqueued = enqueue_render_frame(
                            session,
                            frame,
                            pts_us,
                            decoded_at_ms);
                    if (enqueued) {
                        pthread_cond_signal(&session->render_cond);
                    }
                    pthread_mutex_unlock(&session->render_lock);
                    if (!enqueued) {
                        // Queue ingest is effectively lossless unless allocation/clone fails.
                        session->render_drop_count += 1;
                        ct_warn(TAG,
                                "render queue enqueue failed id=%lld designator=%s; dropping frame",
                                (long long) session->session_id,
                                session->designator);
                    }
                } else {
                    render_frame_to_surface(session, frame, pts_us, 0);
                    session->next_render_due_ms = monotonic_ms() + current_render_interval_ms(session);
                }
            }
#endif
            av_frame_unref(frame);
        }
    } // ── end inner decode loop ───────────────────────────────────────────

    // Close the decoder so we can reopen it cleanly on the next iteration.
    // The render thread stays alive between reconnects.
    close_decoder(session);

    } // ── end outer reconnect loop ─────────────────────────────────────────

    // Ensure reconnecting flag is cleared on any exit path.
    session->reader_reconnecting_since_ms = 0;

#if HAVE_SWSCALE
    if (session->is_render && session->render_thread_started && session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        session->render_thread_stop = true;
        pthread_cond_signal(&session->render_cond);
        pthread_mutex_unlock(&session->render_lock);
        pthread_join(session->render_thread, NULL);
        session->render_thread_started = false;
    }
    if (session->is_render && session->render_sync_ready) {
        pthread_mutex_lock(&session->render_lock);
        clear_render_queue(session);
        pthread_mutex_unlock(&session->render_lock);
    }
#endif
    av_frame_free(&frame);
    av_packet_free(&pkt);
    // Note: close_decoder is called inside the outer loop on each iteration,
    // so we do NOT call it again here.
}
#endif

static void *session_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;

    dispatch_probe_event(session->designator, "session_started", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);

#if HAVE_FFMPEG
    dispatch_probe_event(session->designator, "decoder_backend_ffmpeg_linked", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    run_decode_loop(session);
#else
    dispatch_probe_event(session->designator, "decoder_backend_stub", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    while (session_running(session)) {
        int64_t now_us = ((int64_t) time(NULL)) * 1000000LL;
        const char *event = session->is_render ? "frame_render" : "frame_probe";
        dispatch_probe_event(session->designator, event, session->session_id, now_us,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        usleep(300000);
    }
#endif

    dispatch_probe_event(session->designator, "session_stopped", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    return NULL;
}

static jlong start_session(JNIEnv *env, jstring designator, jstring url, bool is_render) {
    const char *d = (*env)->GetStringUTFChars(env, designator, 0);
    const char *u = (*env)->GetStringUTFChars(env, url, 0);

    pthread_mutex_lock(&g_lock);

    ffmpeg_session_t *slot = NULL;
    for (int i = 0; i < MAX_SESSIONS; i++) {
        if (!g_sessions[i].active) {
            slot = &g_sessions[i];
            break;
        }
    }

    if (slot == NULL) {
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        ct_error(TAG, "No free ffmpeg session slots");
        return 0;
    }

    memset(slot, 0, sizeof(*slot));
    slot->session_id = g_next_session_id++;
    slot->active = true;
    slot->running = true;
    slot->is_render = is_render;
    slot->anomaly_cfg.enabled           = false;
    slot->anomaly_cfg.algorithm_mask    = ANOMALY_ALGO_THERMAL;
    slot->anomaly_cfg.frame_stride      = ANOMALY_DEFAULT_FRAME_STRIDE;
    slot->anomaly_cfg.score_threshold   = ANOMALY_DEFAULT_SCORE_THRESHOLD;
    slot->anomaly_cfg.min_area_fraction = ANOMALY_DEFAULT_MIN_AREA_FRACTION;
    slot->anomaly_cfg.thermal_polarity  = ANOMALY_THERMAL_WHITE_HOT;
    slot->anomaly_cfg.scan_zone         = ANOMALY_SCAN_ZONE_DEFAULT;
    slot->anomaly_cfg.min_hits          = ANOMALY_DEFAULT_MIN_HITS;
    slot->anomaly_cfg.thermal_min_delta = ANOMALY_THERMAL_MIN_DELTA;
    anomaly_state_init(&slot->anomaly_state);
    slot->render_stride = 1;
    slot->render_stride_counter = 0;
    // Render sessions start paused: no frames decoded until the TextureView surface
    // is attached.  Non-render sessions (probe/audio) are never paused this way.
    slot->surface_paused = slot->is_render;
    slot->render_queue_flush_requested = false;
#if HAVE_FFMPEG
    slot->video_stream_index = -1;
#if HAVE_SWSCALE
    slot->anomaly_src_fmt = AV_PIX_FMT_NONE;
    if (slot->is_render) {
        if (pthread_mutex_init(&slot->render_lock, NULL) != 0) {
            ct_error(TAG, "pthread_mutex_init failed for render_lock");
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        if (pthread_cond_init(&slot->render_cond, NULL) != 0) {
            ct_error(TAG, "pthread_cond_init failed for render_cond");
            pthread_mutex_destroy(&slot->render_lock);
            memset(slot, 0, sizeof(*slot));
            pthread_mutex_unlock(&g_lock);
            (*env)->ReleaseStringUTFChars(env, designator, d);
            (*env)->ReleaseStringUTFChars(env, url, u);
            return 0;
        }
        slot->render_sync_ready = true;
        slot->render_thread_started = false;
        slot->render_thread_stop = false;
        slot->render_queue = NULL;
        slot->render_queue_capacity = 0;
        slot->render_queue_head = 0;
        slot->render_queue_depth = 0;
    }
#endif
#endif
    snprintf(slot->designator, sizeof(slot->designator), "%s", d);
    snprintf(slot->url, sizeof(slot->url), "%s", u);
#if HAVE_FFMPEG && HAVE_SWSCALE
    if (slot->is_render) {
        if (!ensure_render_queue_capacity(slot, RENDER_QUEUE_INITIAL_CAPACITY)) {
            ct_warn(TAG,
                    "render queue prealloc failed id=%lld designator=%s",
                    (long long) slot->session_id,
                    slot->designator);
        }
    }
#endif

    int pthread_rc = pthread_create(&slot->thread, NULL, session_thread_main, slot);
    if (pthread_rc != 0) {
        ct_error(TAG, "pthread_create failed rc=%d", pthread_rc);
#if HAVE_FFMPEG && HAVE_SWSCALE
        if (slot->render_sync_ready) {
            destroy_render_queue_storage(slot);
            pthread_cond_destroy(&slot->render_cond);
            pthread_mutex_destroy(&slot->render_lock);
            slot->render_sync_ready = false;
        }
#endif
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }

    jlong session_id = slot->session_id;
    pthread_mutex_unlock(&g_lock);

    (*env)->ReleaseStringUTFChars(env, designator, d);
    (*env)->ReleaseStringUTFChars(env, url, u);
    return session_id;
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeIsAvailable(
        JNIEnv *env,
        jobject thiz
) {
    (void) env;
    (void) thiz;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeInitBridge(
        JNIEnv *env,
        jobject thiz
) {
    jclass local_cls = (*env)->GetObjectClass(env, thiz);
    if (local_cls == NULL) return;

    if (g_bridge_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_bridge_class);
        g_bridge_class = NULL;
    }
    g_bridge_class = (*env)->NewGlobalRef(env, local_cls);
    (*env)->DeleteLocalRef(env, local_cls);

    if (g_bridge_class == NULL) return;

    g_dispatch_probe_event_mid = (*env)->GetStaticMethodID(
            env,
            g_bridge_class,
            "dispatchNativeProbeEvent",
        "(Ljava/lang/String;Ljava/lang/String;JLjava/lang/String;DLjava/lang/String;JJDDDDDD)V");

    jclass caltopo_local_cls = (*env)->FindClass(env, "org/ncssar/rid2caltopo/data/CaltopoClient");
    if (caltopo_local_cls == NULL) {
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return;
    }

    if (g_caltopo_client_class != NULL) {
        (*env)->DeleteGlobalRef(env, g_caltopo_client_class);
        g_caltopo_client_class = NULL;
    }
    g_caltopo_client_class = (*env)->NewGlobalRef(env, caltopo_local_cls);
    (*env)->DeleteLocalRef(env, caltopo_local_cls);
    if (g_caltopo_client_class == NULL) return;

    g_ctdebug_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTDebug",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_ctwarn_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTWarn",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_cterror_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "CTError",
            "(Ljava/lang/String;Ljava/lang/String;)V");
    g_register_debug_tag_mid = (*env)->GetStaticMethodID(
            env,
            g_caltopo_client_class,
            "RegisterDebugTag",
            "(Ljava/lang/String;)V");

    if (g_register_debug_tag_mid != NULL) {
        jstring j_tag = (*env)->NewStringUTF(env, TAG);
        (*env)->CallStaticVoidMethod(env, g_caltopo_client_class, g_register_debug_tag_mid, j_tag);
        (*env)->DeleteLocalRef(env, j_tag);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
    }
}

JNIEXPORT jstring JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeDecoderBackend(
        JNIEnv *env,
        jobject thiz
) {
    (void) thiz;
#if HAVE_FFMPEG
    return (*env)->NewStringUTF(env, "ffmpeg-linked");
#else
    return (*env)->NewStringUTF(env, "stub");
#endif
}

JNIEXPORT jlong JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStartProbe(
        JNIEnv *env,
        jobject thiz,
        jstring designator,
        jstring rtsp_url
) {
    (void) thiz;
    return start_session(env, designator, rtsp_url, false);
}

JNIEXPORT jlong JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStartRender(
        JNIEnv *env,
        jobject thiz,
        jstring designator,
        jstring rtsp_url
) {
    (void) thiz;
    return start_session(env, designator, rtsp_url, true);
}

JNIEXPORT jboolean JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeAttachSurface(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jobject surface
) {
    (void) thiz;

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (window == NULL) return JNI_FALSE;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session == NULL || !session->active) {
        pthread_mutex_unlock(&g_lock);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    if (session->surface_global_ref != NULL) {
        (*env)->DeleteGlobalRef(env, session->surface_global_ref);
        session->surface_global_ref = NULL;
    }
    if (session->window != NULL) {
        ANativeWindow_release(session->window);
    }

    bool was_surface_paused = session->surface_paused;
    session->surface_global_ref = (*env)->NewGlobalRef(env, surface);
    session->window = window;
    session->surface_paused = false;  // surface ready — resume decode
    if (was_surface_paused && session->is_render) {
        clear_render_queue(session);
        session->render_queue_flush_requested = false;
        reset_render_timing_state_locked(session, false);
        ct_debug(TAG,
                 "render resume reset id=%lld designator=%s targetLatencyMs=%lld",
                 (long long) session->session_id,
                 session->designator,
                 (long long) session->target_latency_ms);
    }
    pthread_mutex_unlock(&g_lock);

    dispatch_probe_event(session->designator, "surface_attached", session->session_id, 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeDetachSurface(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        if (session->surface_global_ref != NULL) {
            (*env)->DeleteGlobalRef(env, session->surface_global_ref);
            session->surface_global_ref = NULL;
        }
        if (session->window != NULL) {
            ANativeWindow_release(session->window);
            session->window = NULL;
        }
        // Pause decode and request a render-queue flush.  The decode thread will
        // drop incoming packets until the surface is reattached; the render thread
        // will drain and discard any already-queued frames on its next iteration.
        session->surface_paused = true;
        session->render_queue_flush_requested = true;
        dispatch_probe_event(session->designator, "surface_detached", session->session_id, 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    }
    pthread_mutex_unlock(&g_lock);

}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeUpdateAnomalyConfig(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jboolean enabled,
        jint algorithm_mask,
        jint frame_stride,
        jfloat score_threshold,
        jfloat min_area_fraction,
        jint thermal_polarity,
        jfloat scan_zone,
        jint min_hits,
        jfloat thermal_min_delta
) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        float sz = (float) scan_zone;
        int   mh = (int)   min_hits;
        session->anomaly_cfg.enabled           = (enabled == JNI_TRUE);
        session->anomaly_cfg.algorithm_mask    = (int) algorithm_mask;
        session->anomaly_cfg.frame_stride      = ((int) frame_stride < 1) ? 1 : (int) frame_stride;
        session->anomaly_cfg.score_threshold   = fmaxf(0.1f, score_threshold);
        session->anomaly_cfg.min_area_fraction = fminf(fmaxf(min_area_fraction, 0.0001f), 0.20f);
        session->anomaly_cfg.thermal_polarity  = ((int) thermal_polarity == ANOMALY_THERMAL_BLACK_HOT)
                                                 ? ANOMALY_THERMAL_BLACK_HOT : ANOMALY_THERMAL_WHITE_HOT;
        session->anomaly_cfg.scan_zone         = sz < 0.5f ? 0.5f : (sz > 1.0f ? 1.0f : sz);
        session->anomaly_cfg.min_hits          = mh < 1 ? 1 : (mh > 10 ? 10 : mh);
        session->anomaly_cfg.thermal_min_delta = thermal_min_delta > 0.0f
                                                 ? thermal_min_delta : ANOMALY_THERMAL_MIN_DELTA;
        // Reset accumulators so stale boxes don't persist across config changes.
        anomaly_state_reset(&session->anomaly_state);
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeSetRenderStride(
        JNIEnv *env,
        jobject thiz,
        jlong session_id,
        jint stride
) {
    (void) env;
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session != NULL && session->active) {
        int new_stride = ((int) stride < 1) ? 1 : (int) stride;
        if (new_stride != session->render_stride) {
            ct_debug(TAG,
                     "render stride id=%lld designator=%s: %d -> %d",
                     (long long) session->session_id,
                     session->designator,
                     session->render_stride,
                     new_stride);
            session->render_stride = new_stride;
            session->render_stride_counter = 0;
        }
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStop(
        JNIEnv *env,
        jobject thiz,
        jlong session_id
) {
    (void) thiz;

    pthread_mutex_lock(&g_lock);
    ffmpeg_session_t *session = find_session_locked(session_id);
    if (session == NULL || !session->active) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    session->running = false;
    pthread_t thread = session->thread;
    pthread_mutex_unlock(&g_lock);

    pthread_join(thread, NULL);

    pthread_mutex_lock(&g_lock);
    if (session->surface_global_ref != NULL) {
        (*env)->DeleteGlobalRef(env, session->surface_global_ref);
        session->surface_global_ref = NULL;
    }
    if (session->window != NULL) {
        ANativeWindow_release(session->window);
        session->window = NULL;
    }
#if HAVE_FFMPEG && HAVE_SWSCALE
    if (session->render_sync_ready) {
        destroy_render_queue_storage(session);
        pthread_cond_destroy(&session->render_cond);
        pthread_mutex_destroy(&session->render_lock);
        session->render_sync_ready = false;
    }
#endif
    memset(session, 0, sizeof(*session));
    pthread_mutex_unlock(&g_lock);

}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

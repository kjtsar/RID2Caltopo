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

#if HAVE_FFMPEG
    AVFormatContext *fmt;
    AVCodecContext *codec;
    int video_stream_index;
    AVRational video_time_base;
#if HAVE_SWSCALE
    struct SwsContext *sws;
    AVFrame *rgba_frame;
    uint8_t *rgba_buffer;
    int rgba_buffer_size;
    int rgba_width;
    int rgba_height;
#endif
#endif
} ffmpeg_session_t;

static JavaVM *g_vm = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static jlong g_next_session_id = 1;
static ffmpeg_session_t g_sessions[MAX_SESSIONS];
static jclass g_bridge_class = NULL;
static jmethodID g_dispatch_probe_event_mid = NULL;

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
                                    const char *source_tag,
                                    double confidence,
                                    const char *remote_id,
                                    int64_t source_ts_us,
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
            j_source_tag,
            (jdouble) confidence,
            j_remote_id,
            (jlong) source_ts_us,
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
            "runtime",
            0.50,
            "",
            source_ts_us,
            lat,
            lng,
            alt,
            gimbal_pitch,
            camera_yaw,
            heading);
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

static void render_frame_to_surface(ffmpeg_session_t *session, AVFrame *decoded) {
    ANativeWindow *window = acquire_window(session);
    if (window == NULL) return;

    ensure_rgba_resources(session, decoded->width, decoded->height, decoded->format);
    if (session->sws == NULL || session->rgba_frame == NULL) {
        ANativeWindow_release(window);
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

    ANativeWindow_setBuffersGeometry(window, decoded->width, decoded->height, WINDOW_FORMAT_RGBA_8888);

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window, &buffer, NULL) == 0) {
        uint8_t *dst = (uint8_t *) buffer.bits;
        const int dst_stride = buffer.stride * 4;
        uint8_t *src = session->rgba_frame->data[0];
        const int src_stride = session->rgba_frame->linesize[0];
        const int copy_width = decoded->width * 4;
        for (int y = 0; y < decoded->height; y++) {
            memcpy(dst + (y * dst_stride), src + (y * src_stride), (size_t) copy_width);
        }
        ANativeWindow_unlockAndPost(window);
    }

    ANativeWindow_release(window);
}
#endif

#if HAVE_FFMPEG
static int64_t pts_to_us(int64_t pts, AVRational tb) {
    if (pts == AV_NOPTS_VALUE) return 0;
    return av_rescale_q(pts, tb, (AVRational) {1, 1000000});
}

typedef struct {
    bool has_any;
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
        tv->has_any = true;
        return;
    }
    if ((key_contains(k, "remoteid") || key_contains(k, "remote_id") ||
         key_contains(k, "serial") || key_contains(k, "uasid")) &&
        parse_string_value(val, tv->remote_id, sizeof(tv->remote_id))) {
        tv->has_any = true;
    }
}

static void emit_dict_telemetry(ffmpeg_session_t *session,
                                AVDictionary *dict,
                                const char *source_tag,
                                double confidence,
                                int64_t fallback_ts_us) {
    if (session == NULL || dict == NULL) return;

    telemetry_values_t tv = telemetry_values_init();
    tv.ts_us = fallback_ts_us;

    AVDictionaryEntry *entry = NULL;
    while ((entry = av_dict_get(dict, "", entry, AV_DICT_IGNORE_SUFFIX)) != NULL) {
        map_telemetry_key_value(&tv, entry->key, entry->value);
    }

    if (!tv.has_any) return;
    dispatch_probe_event_ex(
            session->designator,
            "telemetry",
            source_tag,
            confidence,
            tv.remote_id,
            tv.ts_us,
            tv.lat,
            tv.lng,
            tv.alt,
            tv.gimbal_pitch,
            tv.camera_yaw,
            tv.heading);
}

static int open_input_with_profile(ffmpeg_session_t *session, bool low_latency) {
    AVDictionary *opts = NULL;

    // Keep transport deterministic for loopback stability.
    av_dict_set(&opts, "rtsp_transport", "tcp", 0);

    if (low_latency) {
        av_dict_set(&opts, "fflags", "nobuffer", 0);
        av_dict_set(&opts, "flags", "low_delay", 0);
        av_dict_set(&opts, "flush_packets", "1", 0);
        av_dict_set(&opts, "allowed_media_types", "video", 0);
        av_dict_set(&opts, "reorder_queue_size", "0", 0);
        av_dict_set(&opts, "max_delay", "100000", 0);
        av_dict_set(&opts, "probesize", "32768", 0);
        av_dict_set(&opts, "analyzeduration", "0", 0);
        av_dict_set(&opts, "fpsprobesize", "0", 0);
    } else {
        // Compatibility fallback for streams that dislike aggressive probing.
        av_dict_set(&opts, "max_delay", "500000", 0);
        av_dict_set(&opts, "probesize", "262144", 0);
        av_dict_set(&opts, "analyzeduration", "1000000", 0);
    }

    int rc = avformat_open_input(&session->fmt, session->url, NULL, &opts);
    av_dict_free(&opts);
    return rc;
}

static int open_decoder(ffmpeg_session_t *session) {
    int rc = open_input_with_profile(session, true);
    if (rc < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        memset(errbuf, 0, sizeof(errbuf));
        av_strerror(rc, errbuf, sizeof(errbuf));
        __android_log_print(ANDROID_LOG_WARN, TAG,
                            "low-latency open failed id=%lld designator=%s rc=%d err=%s; retrying with compatibility profile",
                            (long long) session->session_id,
                            session->designator,
                            rc,
                            errbuf);
        if (session->fmt != NULL) {
            avformat_close_input(&session->fmt);
        }
        rc = open_input_with_profile(session, false);
    }
    if (rc < 0) {
        return rc;
    }

    rc = avformat_find_stream_info(session->fmt, NULL);
    if (rc < 0) {
        return rc;
    }

    int video_index = av_find_best_stream(session->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (video_index < 0) {
        return video_index;
    }

    AVStream *stream = session->fmt->streams[video_index];
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

    rc = avcodec_open2(session->codec, decoder, NULL);
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
    int rc = open_decoder(session);
    if (rc < 0) {
        av_strerror(rc, errbuf, sizeof(errbuf));
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "open_decoder failed id=%lld designator=%s rc=%d err=%s",
                            (long long) session->session_id,
                            session->designator,
                            rc,
                            errbuf);
        dispatch_probe_event(session->designator, "decoder_open_error", 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        return;
    }

    dispatch_probe_event(session->designator, "decoder_opened", 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);

    AVPacket *pkt = av_packet_alloc();
    AVFrame *frame = av_frame_alloc();
    if (pkt == NULL || frame == NULL) {
        dispatch_probe_event(session->designator, "decoder_alloc_error", 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        if (pkt != NULL) av_packet_free(&pkt);
        if (frame != NULL) av_frame_free(&frame);
        close_decoder(session);
        return;
    }

    while (session_running(session)) {
        rc = av_read_frame(session->fmt, pkt);
        if (rc == AVERROR_EOF) {
            usleep(50000);
            continue;
        }
        if (rc < 0) {
            usleep(20000);
            continue;
        }

        if (pkt->stream_index != session->video_stream_index) {
            av_packet_unref(pkt);
            continue;
        }

#if defined(AV_PKT_DATA_STRINGS_METADATA)
        size_t sd_size = 0;
        uint8_t *sd = av_packet_get_side_data(pkt, AV_PKT_DATA_STRINGS_METADATA, &sd_size);
        if (sd != NULL && sd_size > 0) {
            AVDictionary *packet_dict = NULL;
            if (av_packet_unpack_dictionary(sd, (int) sd_size, &packet_dict) >= 0) {
                int64_t pkt_ts_us = pts_to_us(pkt->pts, session->video_time_base);
                emit_dict_telemetry(session, packet_dict, "packet-side-data", 0.70, pkt_ts_us);
            }
            av_dict_free(&packet_dict);
        }
#endif

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
            dispatch_probe_event(session->designator, "frame_decoded", pts_us,
                                 NAN, NAN, NAN, NAN, NAN, NAN);
            emit_dict_telemetry(session, frame->metadata, "frame-metadata", 0.60, pts_us);

#if HAVE_SWSCALE
            if (session->is_render) {
                render_frame_to_surface(session, frame);
            }
#endif
            av_frame_unref(frame);
        }
    }

    av_frame_free(&frame);
    av_packet_free(&pkt);
    close_decoder(session);
}
#endif

static void *session_thread_main(void *arg) {
    ffmpeg_session_t *session = (ffmpeg_session_t *) arg;
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "session thread start id=%lld render=%d designator=%s",
                        (long long) session->session_id,
                        session->is_render ? 1 : 0,
                        session->designator);

    dispatch_probe_event(session->designator, "session_started", 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);

#if HAVE_FFMPEG
    dispatch_probe_event(session->designator, "decoder_backend_ffmpeg_linked", 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    run_decode_loop(session);
#else
    dispatch_probe_event(session->designator, "decoder_backend_stub", 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    while (session_running(session)) {
        int64_t now_us = ((int64_t) time(NULL)) * 1000000LL;
        const char *event = session->is_render ? "frame_render" : "frame_probe";
        dispatch_probe_event(session->designator, event, now_us,
                             NAN, NAN, NAN, NAN, NAN, NAN);
        usleep(300000);
    }
#endif

    dispatch_probe_event(session->designator, "session_stopped", 0,
                         NAN, NAN, NAN, NAN, NAN, NAN);
    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "session thread stop id=%lld designator=%s",
                        (long long) session->session_id,
                        session->designator);
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
        __android_log_print(ANDROID_LOG_ERROR, TAG, "No free ffmpeg session slots");
        return 0;
    }

    memset(slot, 0, sizeof(*slot));
    slot->session_id = g_next_session_id++;
    slot->active = true;
    slot->running = true;
    slot->is_render = is_render;
#if HAVE_FFMPEG
    slot->video_stream_index = -1;
#endif
    snprintf(slot->designator, sizeof(slot->designator), "%s", d);
    snprintf(slot->url, sizeof(slot->url), "%s", u);

    int pthread_rc = pthread_create(&slot->thread, NULL, session_thread_main, slot);
    if (pthread_rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed rc=%d", pthread_rc);
        memset(slot, 0, sizeof(*slot));
        pthread_mutex_unlock(&g_lock);
        (*env)->ReleaseStringUTFChars(env, designator, d);
        (*env)->ReleaseStringUTFChars(env, url, u);
        return 0;
    }

    jlong session_id = slot->session_id;
    pthread_mutex_unlock(&g_lock);

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "startSession(id=%lld render=%d designator=%s url=%s)",
                        (long long) session_id,
                        is_render ? 1 : 0,
                        d,
                        u);

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
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;DLjava/lang/String;JDDDDDD)V");
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

    session->surface_global_ref = (*env)->NewGlobalRef(env, surface);
    session->window = window;
    pthread_mutex_unlock(&g_lock);

    __android_log_print(ANDROID_LOG_INFO, TAG, "attachSurface(id=%lld)", (long long) session_id);
    dispatch_probe_event(session->designator, "surface_attached", 0,
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
        dispatch_probe_event(session->designator, "surface_detached", 0,
                             NAN, NAN, NAN, NAN, NAN, NAN);
    }
    pthread_mutex_unlock(&g_lock);

    __android_log_print(ANDROID_LOG_INFO, TAG, "detachSurface(id=%lld)", (long long) session_id);
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
    memset(session, 0, sizeof(*session));
    pthread_mutex_unlock(&g_lock);

    __android_log_print(ANDROID_LOG_INFO, TAG, "stop(session=%lld)", (long long) session_id);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

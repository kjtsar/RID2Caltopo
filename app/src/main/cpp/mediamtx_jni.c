#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>
#include <fcntl.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>
#include <errno.h>

#define LOG_TAG "MediaMTX"

static pid_t mediamtx_pid = -1;
static JavaVM* g_vm = NULL;
static jclass mediaMTXClassGlobal = NULL;
static jmethodID onLogLineMethod = NULL;
static jmethodID onEventJsonMethod = NULL;
static jmethodID onExitedMethod = NULL;

static void log_and_clear_jni_exception(JNIEnv *env, const char *context) {
    if (!(*env)->ExceptionCheck(env)) return;
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "JNI exception during %s", context);
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
}

#define MAX_TRACKED_ENTRIES 32
#define MAX_KEY_LEN 256
#define MAX_VALUE_LEN 512

typedef struct {
    char key[MAX_KEY_LEN];
    char value[MAX_VALUE_LEN];
} StringPair;

static StringPair rtmpConnPathMap[MAX_TRACKED_ENTRIES];
static size_t rtmpConnPathCount = 0;
static StringPair pathPublisherConnMap[MAX_TRACKED_ENTRIES];
static size_t pathPublisherConnCount = 0;
static char suppressStopForPath[MAX_TRACKED_ENTRIES][MAX_KEY_LEN];
static size_t suppressStopForPathCount = 0;
static char publisherHandoffClosingConns[MAX_TRACKED_ENTRIES][MAX_KEY_LEN];
static size_t publisherHandoffClosingConnsCount = 0;

static void copy_bounded(char *dst, size_t dst_size, const char *src) {
    if (dst_size == 0) return;
    strncpy(dst, src, dst_size - 1);
    dst[dst_size - 1] = '\0';
}

static int set_contains(char entries[MAX_TRACKED_ENTRIES][MAX_KEY_LEN], size_t count, const char *value) {
    for (size_t i = 0; i < count; ++i) {
        if (strcmp(entries[i], value) == 0) return 1;
    }
    return 0;
}

static void set_add(char entries[MAX_TRACKED_ENTRIES][MAX_KEY_LEN], size_t *count, const char *value) {
    if (set_contains(entries, *count, value)) return;
    if (*count >= MAX_TRACKED_ENTRIES) return;
    copy_bounded(entries[*count], MAX_KEY_LEN, value);
    (*count)++;
}

static int set_remove(char entries[MAX_TRACKED_ENTRIES][MAX_KEY_LEN], size_t *count, const char *value) {
    for (size_t i = 0; i < *count; ++i) {
        if (strcmp(entries[i], value) != 0) continue;
        for (size_t j = i + 1; j < *count; ++j) {
            copy_bounded(entries[j - 1], MAX_KEY_LEN, entries[j]);
        }
        (*count)--;
        if (*count < MAX_TRACKED_ENTRIES) entries[*count][0] = '\0';
        return 1;
    }
    return 0;
}

static const char* map_get(StringPair *entries, size_t count, const char *key) {
    for (size_t i = 0; i < count; ++i) {
        if (strcmp(entries[i].key, key) == 0) return entries[i].value;
    }
    return NULL;
}

static void map_put(StringPair *entries, size_t *count, const char *key, const char *value) {
    for (size_t i = 0; i < *count; ++i) {
        if (strcmp(entries[i].key, key) != 0) continue;
        copy_bounded(entries[i].value, MAX_VALUE_LEN, value);
        return;
    }
    if (*count >= MAX_TRACKED_ENTRIES) return;
    copy_bounded(entries[*count].key, MAX_KEY_LEN, key);
    copy_bounded(entries[*count].value, MAX_VALUE_LEN, value);
    (*count)++;
}

static int map_remove(StringPair *entries, size_t *count, const char *key, char *out_value, size_t out_size) {
    for (size_t i = 0; i < *count; ++i) {
        if (strcmp(entries[i].key, key) != 0) continue;
        if (out_value != NULL && out_size > 0) {
            copy_bounded(out_value, out_size, entries[i].value);
        }
        for (size_t j = i + 1; j < *count; ++j) {
            entries[j - 1] = entries[j];
        }
        (*count)--;
        if (*count < MAX_TRACKED_ENTRIES) {
            entries[*count].key[0] = '\0';
            entries[*count].value[0] = '\0';
        }
        return 1;
    }
    return 0;
}

static void json_escape_into(char *dst, size_t dst_size, const char *src) {
    size_t out = 0;
    if (dst_size == 0) return;
    for (size_t i = 0; src[i] != '\0' && out + 1 < dst_size; ++i) {
        char ch = src[i];
        const char *escape = NULL;
        switch (ch) {
            case '\\': escape = "\\\\"; break;
            case '"': escape = "\\\""; break;
            case '\n': escape = "\\n"; break;
            case '\r': escape = "\\r"; break;
            case '\t': escape = "\\t"; break;
            default: break;
        }
        if (escape != NULL) {
            size_t esc_len = strlen(escape);
            if (out + esc_len >= dst_size) break;
            memcpy(dst + out, escape, esc_len);
            out += esc_len;
            continue;
        }
        dst[out++] = ch;
    }
    dst[out] = '\0';
}

static int contains_ignore_case(const char *haystack, const char *needle) {
    size_t needle_len = strlen(needle);
    if (needle_len == 0) return 1;
    for (size_t i = 0; haystack[i] != '\0'; ++i) {
        size_t j = 0;
        while (j < needle_len &&
               haystack[i + j] != '\0' &&
               tolower((unsigned char)haystack[i + j]) == tolower((unsigned char)needle[j])) {
            ++j;
        }
        if (j == needle_len) return 1;
    }
    return 0;
}

static void emit_event_json(JNIEnv* reader_env, const char* json) {
    if (!onEventJsonMethod) {
        __android_log_print(ANDROID_LOG_ERROR, "MediaMTX-JNI",
                            "onEventJsonMethod is NULL, skipping structured event");
        return;
    }

    jstring jjson = (*reader_env)->NewStringUTF(reader_env, json);
    (*reader_env)->CallStaticVoidMethod(
            reader_env,
            mediaMTXClassGlobal,
            onEventJsonMethod,
            jjson
    );
    log_and_clear_jni_exception(reader_env, "onMediaMTXEventJson");
    (*reader_env)->DeleteLocalRef(reader_env, jjson);
}

static void emit_event_json_type_path_conn(JNIEnv* reader_env,
                                           const char* type,
                                           const char* path,
                                           const char* publisher_conn_id) {
    char escaped_path[MAX_VALUE_LEN * 2];
    char escaped_conn[MAX_VALUE_LEN * 2];
    char payload[(MAX_VALUE_LEN * 5)];
    json_escape_into(escaped_path, sizeof(escaped_path), path);
    if (publisher_conn_id != NULL && publisher_conn_id[0] != '\0') {
        json_escape_into(escaped_conn, sizeof(escaped_conn), publisher_conn_id);
        snprintf(payload, sizeof(payload),
                 "{\"type\":\"%s\",\"path\":\"%s\",\"publisherConnId\":\"%s\"}",
                 type, escaped_path, escaped_conn);
    } else {
        snprintf(payload, sizeof(payload),
                 "{\"type\":\"%s\",\"path\":\"%s\"}",
                 type, escaped_path);
    }
    emit_event_json(reader_env, payload);
}

static void emit_event_json_type_path(JNIEnv* reader_env, const char* type, const char* path) {
    emit_event_json_type_path_conn(reader_env, type, path, NULL);
}

static void emit_event_json_type_path_reason_conn(JNIEnv* reader_env,
                                                  const char* type,
                                                  const char* path,
                                                  const char* reason,
                                                  const char* publisher_conn_id) {
    char escaped_path[MAX_VALUE_LEN * 2];
    char escaped_reason[MAX_VALUE_LEN * 2];
    char escaped_conn[MAX_VALUE_LEN * 2];
    char payload[(MAX_VALUE_LEN * 6)];
    json_escape_into(escaped_path, sizeof(escaped_path), path);
    json_escape_into(escaped_reason, sizeof(escaped_reason), reason);
    if (publisher_conn_id != NULL && publisher_conn_id[0] != '\0') {
        json_escape_into(escaped_conn, sizeof(escaped_conn), publisher_conn_id);
        snprintf(payload, sizeof(payload),
                 "{\"type\":\"%s\",\"path\":\"%s\",\"publisherConnId\":\"%s\",\"reason\":\"%s\"}",
                 type, escaped_path, escaped_conn, escaped_reason);
    } else {
        snprintf(payload, sizeof(payload),
                 "{\"type\":\"%s\",\"path\":\"%s\",\"reason\":\"%s\"}",
                 type, escaped_path, escaped_reason);
    }
    emit_event_json(reader_env, payload);
}

static void emit_event_json_type_path_reason(JNIEnv* reader_env, const char* type, const char* path, const char* reason) {
    emit_event_json_type_path_reason_conn(reader_env, type, path, reason, NULL);
}

static void emit_event_json_type_version(JNIEnv* reader_env, const char* type, const char* version) {
    char escaped_version[MAX_VALUE_LEN];
    char payload[MAX_VALUE_LEN * 2];
    json_escape_into(escaped_version, sizeof(escaped_version), version);
    snprintf(payload, sizeof(payload),
             "{\"type\":\"%s\",\"version\":\"%s\"}",
             type, escaped_version);
    emit_event_json(reader_env, payload);
}

static void normalize_rtmp_close_reason(const char *reason, char *out, size_t out_size) {
    if (contains_ignore_case(reason, "extended chunk stream IDs are not supported")) {
        copy_bounded(out, out_size,
                     "RTMP closed: publisher uses extended chunk stream IDs unsupported by current MediaMTX");
        return;
    }
    if (contains_ignore_case(reason, "unexpected EOF")) {
        copy_bounded(out, out_size, "RTMP closed: publisher disconnected unexpectedly");
        return;
    }
    snprintf(out, out_size, "RTMP closed: %s", reason);
}

static void emit_structured_event_for_line(JNIEnv *reader_env, const char *line) {
    char key[MAX_KEY_LEN];
    char rem[MAX_VALUE_LEN];
    char value[MAX_VALUE_LEN];
    char normalized_reason[MAX_VALUE_LEN];
    value[0] = '\0';

    const char *path_marker = strstr(line, "[path ");
    if (path_marker != NULL &&
        sscanf(path_marker, "[path %255[^]]] %511[^\n]", key, rem) == 2) {
        if (strstr(rem, "runOnReady") != NULL) {
            set_remove(suppressStopForPath, &suppressStopForPathCount, key);
            return;
        }
        if (strstr(rem, "closing existing publisher") != NULL) {
            set_add(suppressStopForPath, &suppressStopForPathCount, key);
            const char *publisher_conn = map_get(pathPublisherConnMap, pathPublisherConnCount, key);
            if (publisher_conn != NULL) {
                set_add(publisherHandoffClosingConns, &publisherHandoffClosingConnsCount, publisher_conn);
            }
            emit_event_json_type_path_conn(reader_env, "stream_publisher_handoff", key, publisher_conn);
            return;
        }
        if (strstr(rem, "created") != NULL) {
            set_remove(suppressStopForPath, &suppressStopForPathCount, key);
            emit_event_json_type_path(reader_env, "stream_connecting", key);
            return;
        }
        if (strstr(rem, "destroyed") != NULL) {
            map_remove(pathPublisherConnMap, &pathPublisherConnCount, key, value, sizeof(value));
            set_remove(publisherHandoffClosingConns, &publisherHandoffClosingConnsCount, value);
            if (!set_contains(suppressStopForPath, suppressStopForPathCount, key)) {
                emit_event_json_type_path_conn(
                        reader_env,
                        "stream_stopped",
                        key,
                        value[0] != '\0' ? value : NULL
                );
            }
            return;
        }
    }

    const char *hls_marker = strstr(line, "[HLS] [muxer ");
    if (hls_marker != NULL &&
        sscanf(hls_marker, "[HLS] [muxer %255[^]]] %511[^\n]", key, rem) == 2) {
        if (strstr(rem, "created") != NULL) {
            emit_event_json_type_path(reader_env, "hls_started", key);
            return;
        }
        if (strstr(rem, "destroyed") != NULL &&
            !set_contains(suppressStopForPath, suppressStopForPathCount, key)) {
            emit_event_json_type_path(reader_env, "stream_stopped", key);
            return;
        }
    }

    const char *rtmp_conn_marker = strstr(line, "[RTMP] [conn ");
    if (rtmp_conn_marker != NULL &&
        sscanf(rtmp_conn_marker, "[RTMP] [conn %255[^]]] %511[^\n]", key, rem) == 2) {
        const char *publishing_path = strstr(rem, "is publishing to path '");
        if (publishing_path != NULL) {
            const char *path_start = publishing_path + strlen("is publishing to path '");
            const char *path_end = strchr(path_start, '\'');
            if (path_end != NULL) {
                size_t path_len = (size_t)(path_end - path_start);
                if (path_len >= sizeof(value)) path_len = sizeof(value) - 1;
                memcpy(value, path_start, path_len);
                value[path_len] = '\0';
                const char *previous_path_for_conn = map_get(rtmpConnPathMap, rtmpConnPathCount, key);
                const char *previous_conn_for_path = map_get(pathPublisherConnMap, pathPublisherConnCount, value);
                int duplicate_publisher_line =
                        previous_path_for_conn != NULL &&
                        strcmp(previous_path_for_conn, value) == 0 &&
                        previous_conn_for_path != NULL &&
                        strcmp(previous_conn_for_path, key) == 0;
                if (previous_path_for_conn != NULL &&
                    strcmp(previous_path_for_conn, value) != 0) {
                    const char *active_conn_for_previous =
                            map_get(pathPublisherConnMap, pathPublisherConnCount, previous_path_for_conn);
                    if (active_conn_for_previous != NULL &&
                        strcmp(active_conn_for_previous, key) == 0) {
                        map_remove(pathPublisherConnMap, &pathPublisherConnCount, previous_path_for_conn, NULL, 0);
                    }
                }
                map_put(rtmpConnPathMap, &rtmpConnPathCount, key, value);
                map_put(pathPublisherConnMap, &pathPublisherConnCount, value, key);
                set_remove(suppressStopForPath, &suppressStopForPathCount, value);
                if (duplicate_publisher_line) {
                    return;
                }
                emit_event_json_type_path_conn(reader_env, "stream_started", value, key);
                return;
            }
        }

        const char *closed_reason = strstr(rem, "closed:");
        if (closed_reason != NULL) {
            closed_reason += strlen("closed:");
            while (*closed_reason == ' ') closed_reason++;
            if (map_remove(rtmpConnPathMap, &rtmpConnPathCount, key, value, sizeof(value))) {
                const char *active_conn = map_get(pathPublisherConnMap, pathPublisherConnCount, value);
                if (active_conn != NULL && strcmp(active_conn, key) == 0) {
                    map_remove(pathPublisherConnMap, &pathPublisherConnCount, value, NULL, 0);
                }
                set_add(suppressStopForPath, &suppressStopForPathCount, value);
                if (set_remove(publisherHandoffClosingConns, &publisherHandoffClosingConnsCount, key) &&
                    strcasecmp(closed_reason, "terminated") == 0) {
                    return;
                }
                normalize_rtmp_close_reason(closed_reason, normalized_reason, sizeof(normalized_reason));
                emit_event_json_type_path_reason_conn(
                        reader_env,
                        "rtmp_session_closed",
                        value,
                        normalized_reason,
                        key
                );
                emit_event_json_type_path_reason_conn(reader_env, "stream_error", value, normalized_reason, key);
                return;
            }
        }
    }

    const char *no_publishing = strstr(line, "no one is publishing to path '");
    if (no_publishing != NULL) {
        const char *path_start = no_publishing + strlen("no one is publishing to path '");
        const char *path_end = strchr(path_start, '\'');
        if (path_end != NULL) {
            size_t path_len = (size_t)(path_end - path_start);
            if (path_len >= sizeof(key)) path_len = sizeof(key) - 1;
            memcpy(key, path_start, path_len);
            key[path_len] = '\0';
            if (map_remove(pathPublisherConnMap, &pathPublisherConnCount, key, value, sizeof(value))) {
                set_remove(publisherHandoffClosingConns, &publisherHandoffClosingConnsCount, value);
            }
            if (!set_contains(suppressStopForPath, suppressStopForPathCount, key)) {
                emit_event_json_type_path_conn(
                        reader_env,
                        "stream_stopped",
                        key,
                        value[0] != '\0' ? value : NULL
                );
            }
            return;
        }
    }

    const char *start_marker = strstr(line, "MediaMTX v");
    if (start_marker != NULL &&
        sscanf(start_marker, "MediaMTX %511s", value) == 1) {
        emit_event_json_type_version(reader_env, "server_started", value);
    }
}

static void jni_report_child_exit(pid_t pid, int status, int signaled) {
    if (!g_vm || !onExitedMethod) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "JNI exit callback not available");
        return;
    }

    JNIEnv *env = NULL;
    (*g_vm)->AttachCurrentThread(g_vm, &env, NULL);
    (*env)->CallStaticVoidMethod(
            env,
            mediaMTXClassGlobal,
            onExitedMethod,
            (jint)pid,
            (jint)status,
            (jint)signaled
    );
    log_and_clear_jni_exception(env, "onNativeProcessExit");
    (*g_vm)->DetachCurrentThread(g_vm);
}


static void* monitor_child(void *arg) {
    int status;
    int pid = mediamtx_pid;
    int sig = 0;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Monitoring MediaMTX pid %d", pid);

    int r = waitpid(pid, &status, 0);

    if (WIFEXITED(status)) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "MediaMTX exited with code %d",
                            WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        sig = WTERMSIG(status);
        if (sig == SIGRTMAX) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "MediaMTX terminated by Android (SIGRTMAX)");

        }
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "MediaMTX killed by signal %d",
                            WTERMSIG(status));
    }

    jni_report_child_exit(pid, status, sig);
    mediamtx_pid = -1;

    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "MediaMTX terminated, exiting launcher");
    return NULL;
}
static const char * MEDIAMTXNATIVE_CLASS_PATH = "org/ncssar/rid2caltopo/data/MediaMTXNative";
void init_jni(JNIEnv* env, jobject clazz) {
    if (mediaMTXClassGlobal != NULL) return;
    jclass localCls = (*env)->FindClass(env, MEDIAMTXNATIVE_CLASS_PATH);
    if (localCls == NULL) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "Failed to find '%s' class", MEDIAMTXNATIVE_CLASS_PATH);
        return;
    }
    mediaMTXClassGlobal = (*env)->NewGlobalRef(env, localCls);
    (*env)->DeleteLocalRef(env, localCls);

    onLogLineMethod = (*env)->GetStaticMethodID(
            env,
            mediaMTXClassGlobal,
            "onMediaMTXLogLine",
            "(Ljava/lang/String;)V"
    );
    onEventJsonMethod = (*env)->GetStaticMethodID(
            env,
            mediaMTXClassGlobal,
            "onMediaMTXEventJson",
            "(Ljava/lang/String;)V"
    );
    onExitedMethod = (*env)->GetStaticMethodID(
            env,
            mediaMTXClassGlobal,
            "onNativeProcessExit",
            "(III)V"
    );

}

JNIEXPORT void JNICALL
JNI_OnUnload(JavaVM* vm, void *reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        if (mediaMTXClassGlobal) {
            (*env)->DeleteGlobalRef(env, mediaMTXClassGlobal);
            mediaMTXClassGlobal = NULL;
        }
    }
}

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}


void emit_log_line(JNIEnv* reader_env, const char* line) {
    if (!onLogLineMethod) {
        __android_log_print(ANDROID_LOG_ERROR, "MediaMTX-JNI",
                            "onLogLineMethod is NULL, skipping log");
        return;
    }

    jstring jline = (*reader_env)->NewStringUTF(reader_env, line);
    (*reader_env)->CallStaticVoidMethod(
            reader_env,
            mediaMTXClassGlobal,
            onLogLineMethod,
            jline
    );
    log_and_clear_jni_exception(reader_env, "onMediaMTXLogLine");
    (*reader_env)->DeleteLocalRef(reader_env, jline);
}

static void* read_log(void *arg) {
    int fd = *(int *)arg;
    free(arg);
    JNIEnv* reader_env = NULL;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "MediaMTX read_log() running in pid:%d, tid:%ld",
                        getpid(), pthread_self());
    if ((*g_vm)->GetEnv(g_vm, (void **)&reader_env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &reader_env, NULL) != 0) {
            __android_log_print(ANDROID_LOG_INFO, "MediaMTX-JNI",
                                "read_log(): Attach failed");
            return NULL;
        }
    }

    char buf[1024];
    char linebuf[8192];
    size_t line_len = 0;
    while (1) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n <= 0) break;
        for (ssize_t i = 0; i < n; ++i) {
            char ch = buf[i];
            if (ch == '\r') {
                continue;
            }
            if (ch == '\n') {
                if (line_len == 0) {
                    continue;
                }
                linebuf[line_len] = '\0';
                emit_log_line(reader_env, linebuf);
                emit_structured_event_for_line(reader_env, linebuf);
                __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", linebuf);
                line_len = 0;
                continue;
            }

            if (line_len >= sizeof(linebuf) - 1) {
                linebuf[line_len] = '\0';
                emit_log_line(reader_env, linebuf);
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                    "MediaMTX log line exceeded buffer, flushing partial: %s",
                                    linebuf);
                line_len = 0;
            }

            linebuf[line_len++] = ch;
        }
    }

    if (line_len > 0) {
        linebuf[line_len] = '\0';
        emit_log_line(reader_env, linebuf);
        emit_structured_event_for_line(reader_env, linebuf);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", linebuf);
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "MediaMTX read_log() exiting from pid:%d, tid:%ld", getpid(), pthread_self());
    (*g_vm)->DetachCurrentThread(g_vm);
    return NULL;
}

JNIEXPORT jint JNICALL
Java_org_ncssar_rid2caltopo_data_MediaMTXNative_start(
        JNIEnv *env,
        jobject this,
        jstring binPath,
        jstring configPath) {

    if (mediamtx_pid > 0) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "MediaMTX already running in pid %d", mediamtx_pid);
        return 0;
    }
    init_jni(env, this);

    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "MediaMTX launcher/monitor running in pid %d", getpid());
    const char *bin = (*env)->GetStringUTFChars(env, binPath, NULL);
    const char *cfg = (*env)->GetStringUTFChars(env, configPath, NULL);

    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "MediaMTX launcher/monitor using bin:%s, cfg:%s", bin, cfg);
    int logpipe[2];
    pipe(logpipe);

    pid_t pid = fork();
    if (pid == 0) {
        // child
        close(logpipe[0]); // close read end
        dup2(logpipe[1], STDERR_FILENO);
        dup2(logpipe[1], STDOUT_FILENO);
        close(logpipe[1]);

        setenv("TERM", "xterm", 1);
        setenv("GOTRACEBACK", "all", 1);
        setenv("GODEBUG", "asyncpreemptoff=1", 1);
        setenv("STDOUT_SYNC", "1", 1);

        const char *msg = "child: before exec\n";
        write(STDERR_FILENO, msg, strlen(msg));
        execl("/system/bin/linker64",
              "linker64",
              bin,
              cfg,
              NULL);
        char errbuf[128];
        snprintf(errbuf, sizeof(errbuf),
                 "exec failed errno=%d\n", errno);
        write(STDERR_FILENO, errbuf, strlen(errbuf));

        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "exec failed");
        _exit(127);
    }
    close(logpipe[1]); // parent closes write end.
    if (pid < 0) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "fork failed");
        close(logpipe[0]);
        return -1;
    }
    pthread_t log_thread, monitor_thread;
    int *fdp = malloc(sizeof(int));
    *fdp = logpipe[0];
    pthread_create(&log_thread, NULL, read_log, fdp);
    pthread_detach(log_thread);

    mediamtx_pid = pid;
    pthread_create(&monitor_thread, NULL, monitor_child, NULL);
    pthread_detach(monitor_thread);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "MediaMTX running in pid %d", pid);


    return 0;
}

JNIEXPORT void JNICALL
Java_org_ncssar_rid2caltopo_data_MediaMTXNative_stop(
        JNIEnv *env,
        jobject this) {

    if (mediamtx_pid > 0) {
        kill(mediamtx_pid, SIGTERM);
        // let monitor_child() catch and report back the status so it can exit cleanly.
    }
}

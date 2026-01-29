#include <jni.h>
#include <unistd.h>
#include <sys/wait.h>
#include <android/log.h>
#include <fcntl.h>
#include <stdlib.h>
#include <pthread.h>
#include <string.h>
#include <errno.h>

#define LOG_TAG "MediaMTX"

static pid_t mediamtx_pid = -1;
static JavaVM* g_vm = NULL;
static jclass mediaMtxClassGlobal = NULL;
static jmethodID onLogLineMethod = NULL;

static void* monitor_child(void *arg) {
    int status;

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "Monitoring MediaMTX pid %d", mediamtx_pid);

    waitpid(mediamtx_pid, &status, 0);

    if (WIFEXITED(status)) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "MediaMTX exited with code %d",
                            WEXITSTATUS(status));
    } else if (WIFSIGNALED(status)) {
        int sig = WTERMSIG(status);
        if (sig == SIGRTMAX) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                                "MediaMTX terminated by Android (SIGRTMAX)");

        }
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "MediaMTX killed by signal %d",
                            WTERMSIG(status));
    }

    mediamtx_pid = -1;

    __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                        "MediaMTX terminated, exiting launcher");

    _exit(0);   // IMPORTANT: exit *process*, not thread
}

void init_jni(JNIEnv* env, jobject clazz) {
    mediaMtxClassGlobal = (*env)->NewGlobalRef(env, clazz);
    onLogLineMethod = (*env)->GetStaticMethodID(
            env,
            clazz,
            "onMediaMtxLogLine",
            "(Ljava/lang/String;)V"
    );
}
JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEnv* get_jni_env(void) {
    if (g_vm == NULL) return NULL;

    JNIEnv *env = NULL;
    jint result = (*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
        __android_log_print(ANDROID_LOG_INFO, "MediaMTX-JNI",
                            "Attaching log reader thread to JVM");
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) {
            __android_log_print(ANDROID_LOG_INFO, "MediaMTX-JNI",
                                "Attach failed");
            return NULL;
        }
    } else if (result != JNI_OK) {
        return NULL;
    }

    return env;
}


void emit_log_line(const char* line) {
    JNIEnv* env = get_jni_env();
    if (env == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "MediaMTX-JNI",
                            "JNIEnv is NULL, skipping log");
        return;
    }
    if (!onLogLineMethod) {
        __android_log_print(ANDROID_LOG_ERROR, "MediaMTX-JNI",
                            "onLogLineMethod is NULL, kipping log");
        return;
    }

    jstring jline = (*env)->NewStringUTF(env, line);
    (*env)->CallStaticVoidMethod(
            env,
            mediaMtxClassGlobal,
            onLogLineMethod,
            jline
    );
    (*env)->DeleteLocalRef(env, jline);
}

static void* read_log(void *arg) {
    int fd = *(int *)arg;
    free(arg);

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "MediaMTX read_log() running in pid:%d, tid:%ld", getpid(), pthread_self());
    char buf[256];
    while (1) {
        ssize_t n = read(fd, buf, sizeof(buf)-1);
        if (n <= 0) break;
        buf[n] = 0;
        emit_log_line(buf);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
    }
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
        waitpid(mediamtx_pid, NULL, 0);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "MediaMTX stopped");
        mediamtx_pid = -1;
    }
}

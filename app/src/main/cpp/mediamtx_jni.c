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
static jclass mediaMTXClassGlobal = NULL;
static jmethodID onLogLineMethod = NULL;
static jmethodID onExitedMethod = NULL;

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

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
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

    char buf[256];
    while (1) {
        ssize_t n = read(fd, buf, sizeof(buf)-1);
        if (n <= 0) break;
        buf[n] = 0;
        emit_log_line(reader_env, buf);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
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

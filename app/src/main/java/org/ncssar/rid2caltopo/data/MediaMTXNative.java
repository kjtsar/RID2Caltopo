package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;

import org.ncssar.rid2caltopo.app.MediaMTXService;

public final class MediaMTXNative {

    static {
        CTDebug("MediaMTXNative", "Loading libmediamtx_jni.so");
        // Load the JNI library first, so its symbols are available.
        System.loadLibrary("mediamtx_jni");
    }

    public static native int start(String binPath, String configPath);
    public static native void stop();

    public static void onNativeProcessExit(int pid, int status, int signaled) {
        MediaMTXService.onNativeProcessExit(pid, status, signaled);
    }

    public static void onMediaMTXLogLine(String line) {
        MediaMTXLogDispatcher.dispatch(line);
    }
}

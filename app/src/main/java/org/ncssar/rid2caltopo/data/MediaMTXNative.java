package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;

public final class MediaMTXNative {

    static {
        CTDebug("MediaMTXNative", "Loading libmediamtx_jni.so");
        // Load the JNI library first, so its symbols are available.
        System.loadLibrary("mediamtx_jni");
    }

    public static native int start(String binPath, String configPath);
    public static native void stop();
}

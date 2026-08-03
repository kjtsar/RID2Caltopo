package org.ncssar.rid2caltopo.data;

import android.content.Context;

import androidx.annotation.NonNull;

import org.webrtc.PeerConnectionFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide WebRTC initialization shared by preflight and media peers. */
final class ManagedWebRtcRuntime {
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private ManagedWebRtcRuntime() { }

    static void initialize(@NonNull Context context) {
        if (!INITIALIZED.compareAndSet(false, true)) return;
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                        .builder(context.getApplicationContext())
                        .createInitializationOptions());
    }
}

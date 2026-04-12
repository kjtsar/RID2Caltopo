package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface PeerTransportCallback {
    void onConnected(boolean reconnect, @NonNull String serverUri);
    void onConnectionLost(@Nullable Throwable cause);
    void onMessageArrived(@NonNull String topic, @NonNull byte[] payload);
}

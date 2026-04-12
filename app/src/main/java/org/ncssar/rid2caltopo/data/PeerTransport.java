package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface PeerTransport {
    void setCallback(@Nullable PeerTransportCallback callback);
    void connect(@Nullable String lastWillTopic,
                 @Nullable byte[] lastWillPayload,
                 int lastWillQos,
                 boolean lastWillRetained,
                 @Nullable Runnable onConnected,
                 @Nullable Runnable onFailure);
    void disconnect();
    boolean isConnected();
    void subscribe(@NonNull String topicFilter, int qos, @Nullable Runnable onSuccess, @Nullable Runnable onFailure);
    void publish(@NonNull String topic, @NonNull byte[] payload, int qos, boolean retained);
}

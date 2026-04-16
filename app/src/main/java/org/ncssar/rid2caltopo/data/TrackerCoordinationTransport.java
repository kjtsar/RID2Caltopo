package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

interface TrackerCoordinationTransport {

    interface Callback {
        void onOpen();
        void onMessage(@NonNull String text);
        void onClosed();
        void onFailure(@Nullable Throwable throwable);
    }

    void setCallback(@Nullable Callback callback);

    void connect(@NonNull String websocketUrl, @Nullable String apiKey);

    void disconnect();

    boolean isConnected();

    void send(@NonNull String text);
}

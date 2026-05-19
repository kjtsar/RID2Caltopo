package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

interface TrackerCoordinationTransport {

    interface Callback {
        void onOpen();
        void onMessage(@NonNull String text);
        void onClosed(int code, @NonNull String reason);
        void onFailure(@Nullable Throwable throwable, int responseCode, @Nullable String responseMessage);
    }

    void setCallback(@Nullable Callback callback);

    void connect(@NonNull String websocketUrl, @Nullable String apiKey);

    void disconnect();

    boolean isConnected();

    boolean send(@NonNull String text);
}

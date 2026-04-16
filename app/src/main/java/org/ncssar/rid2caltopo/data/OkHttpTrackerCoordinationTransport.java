package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class OkHttpTrackerCoordinationTransport implements TrackerCoordinationTransport {
    private static final int NORMAL_CLOSE_CODE = 1000;

    @NonNull private final OkHttpClient client = CaltopoSession.MyOkHttpClient.newBuilder().build();
    @Nullable private volatile Callback callback;
    @Nullable private volatile WebSocket webSocket;
    private volatile boolean connected;

    @Override
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public synchronized void connect(@NonNull String websocketUrl, @Nullable String apiKey) {
        disconnect();
        Request.Builder builder = new Request.Builder()
                .url(websocketUrl)
                .header("User-Agent", "RID2Caltopo/coordination");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("X-SAR-Token", apiKey);
        }
        webSocket = client.newWebSocket(builder.build(), new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                connected = true;
                Callback cb = callback;
                if (cb != null) cb.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Callback cb = callback;
                if (cb != null) cb.onMessage(text);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                connected = false;
                Callback cb = callback;
                if (cb != null) cb.onClosed();
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                connected = false;
                Callback cb = callback;
                int responseCode = 0;
                String responseMessage = null;
                if (response != null) {
                    responseCode = response.code();
                    responseMessage = response.message();
                    CaltopoClient.CTWarn(
                            "TrackerWsTransport",
                            "websocket failure response: code=" + response.code() +
                                    " message='" + response.message() + "'"
                    );
                }
                if (cb != null) cb.onFailure(t, responseCode, responseMessage);
            }
        });
    }

    @Override
    public synchronized void disconnect() {
        WebSocket socket = webSocket;
        webSocket = null;
        connected = false;
        if (socket != null) {
            socket.close(NORMAL_CLOSE_CODE, "client-stop");
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void send(@NonNull String text) {
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.send(text);
        }
    }
}

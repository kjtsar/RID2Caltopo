package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.io.EOFException;
import java.util.concurrent.TimeUnit;

final class OkHttpTrackerCoordinationTransport implements TrackerCoordinationTransport {
    private static final int NORMAL_CLOSE_CODE = 1000;
    private static final String CLIENT_STOP_REASON = "client-stop";

    @NonNull private final OkHttpClient client = CaltopoSession.MyOkHttpClient.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    @Nullable private volatile Callback callback;
    @Nullable private volatile WebSocket webSocket;
    @Nullable private volatile WebSocket closingWebSocket;
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
        WebSocket newSocket = client.newWebSocket(builder.build(), new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!isActiveSocket(webSocket)) {
                    return;
                }
                connected = true;
                CaltopoClient.CTInfo(
                        "TrackerWsTransport",
                        "websocket opened: code=" + response.code() +
                                " message='" + response.message() + "'"
                );
                Callback cb = callback;
                if (cb != null) cb.onOpen();
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!isActiveSocket(webSocket)) {
                    return;
                }
                Callback cb = callback;
                if (cb != null) cb.onMessage(text);
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                if (!isTrackedSocket(webSocket)) {
                    return;
                }
                if (isIntentionalClientStop(webSocket)) {
                    return;
                }
                CaltopoClient.CTWarn(
                        "TrackerWsTransport",
                        "websocket closing: code=" + code + " reason='" + reason + "'"
                );
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                boolean activeSocket = isActiveSocket(webSocket);
                boolean intentionalClientStop = isIntentionalClientStop(webSocket);
                if (activeSocket) {
                    connected = false;
                }
                clearClosingSocketIfMatches(webSocket);
                if (!activeSocket || intentionalClientStop) {
                    return;
                }
                CaltopoClient.CTWarn(
                        "TrackerWsTransport",
                        "websocket closed: code=" + code + " reason='" + reason + "'"
                );
                Callback cb = callback;
                if (cb != null) cb.onClosed();
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable Response response) {
                boolean activeSocket = isActiveSocket(webSocket);
                boolean intentionalClientStop = isIntentionalClientStop(webSocket);
                if (activeSocket) {
                    connected = false;
                }
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
                } else {
                    String throwableName = t.getClass().getSimpleName();
                    if (t instanceof EOFException) {
                        CaltopoClient.CTDebug(
                                "TrackerWsTransport",
                                "websocket transient disconnect without HTTP response: throwable=" +
                                        throwableName
                        );
                    } else {
                        CaltopoClient.CTWarn(
                                "TrackerWsTransport",
                                "websocket failure without HTTP response: throwable=" +
                                        throwableName
                        );
                    }
                }
                clearClosingSocketIfMatches(webSocket);
                if (!activeSocket || intentionalClientStop) {
                    return;
                }
                if (cb != null) cb.onFailure(t, responseCode, responseMessage);
            }
        });
        webSocket = newSocket;
    }

    @Override
    public synchronized void disconnect() {
        WebSocket socket = webSocket;
        webSocket = null;
        connected = false;
        if (socket != null) {
            closingWebSocket = socket;
            socket.close(NORMAL_CLOSE_CODE, CLIENT_STOP_REASON);
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

    private boolean isActiveSocket(@NonNull WebSocket candidate) {
        return candidate == webSocket;
    }

    private boolean isTrackedSocket(@NonNull WebSocket candidate) {
        return candidate == webSocket || candidate == closingWebSocket;
    }

    private boolean isIntentionalClientStop(@NonNull WebSocket candidate) {
        return candidate == closingWebSocket;
    }

    private void clearClosingSocketIfMatches(@NonNull WebSocket candidate) {
        if (candidate == closingWebSocket) {
            closingWebSocket = null;
        }
    }
}

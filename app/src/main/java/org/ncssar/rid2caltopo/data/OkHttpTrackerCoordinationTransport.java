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
    private static final String TAG = "TrackerWsTransport";
    private static final int NORMAL_CLOSE_CODE = 1000;
    private static final String CLIENT_STOP_REASON = "client-stop";

    @NonNull private final OkHttpClient client = CaltopoSession.MyOkHttpClient.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
    @Nullable private volatile Callback callback;
    @Nullable private volatile WebSocket webSocket;
    @Nullable private volatile WebSocket closingWebSocket;
    private volatile boolean connected;
    private volatile long socketConnectStartedAtMs;
    private volatile long socketOpenedAtMs;

    @Override
    public void setCallback(@Nullable Callback callback) {
        this.callback = callback;
    }

    @Override
    public synchronized void connect(@NonNull String websocketUrl, @Nullable String apiKey) {
        disconnect();
        socketConnectStartedAtMs = System.currentTimeMillis();
        socketOpenedAtMs = 0L;
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
                socketOpenedAtMs = System.currentTimeMillis();
                CaltopoClient.CTInfo(
                        TAG,
                        "websocket opened: code=" + response.code() +
                                " message='" + response.message() + "'" +
                                " handshakeMs=" + Math.max(socketOpenedAtMs - socketConnectStartedAtMs, 0L)
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
                        TAG,
                        "websocket closing: code=" + code + " reason='" + reason + "'" +
                                " socketAgeMs=" + socketAgeMs()
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
                        TAG,
                        "websocket closed: code=" + code + " reason='" + reason + "'" +
                                " socketAgeMs=" + socketAgeMs()
                );
                Callback cb = callback;
                if (cb != null) cb.onClosed(code, reason);
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
                            TAG,
                            "websocket failure response: code=" + response.code() +
                                    " message='" + response.message() + "'" +
                                    " socketAgeMs=" + socketAgeMs()
                    );
                } else {
                    String throwableName = t.getClass().getSimpleName();
                    if (t instanceof EOFException) {
                        CaltopoClient.CTDebug(
                                TAG,
                                "websocket transient disconnect without HTTP response: throwable=" +
                                        throwableName +
                                        " socketAgeMs=" + socketAgeMs()
                        );
                    } else {
                        CaltopoClient.CTWarn(
                                TAG,
                                "websocket failure without HTTP response: throwable=" +
                                        throwableName +
                                        " socketAgeMs=" + socketAgeMs()
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
        socketOpenedAtMs = 0L;
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

    private long socketAgeMs() {
        long nowMs = System.currentTimeMillis();
        long startMs = socketOpenedAtMs > 0L ? socketOpenedAtMs : socketConnectStartedAtMs;
        return startMs > 0L ? Math.max(nowMs - startMs, 0L) : -1L;
    }
}

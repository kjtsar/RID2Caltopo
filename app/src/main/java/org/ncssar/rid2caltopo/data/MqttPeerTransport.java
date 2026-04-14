package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttPeerTransport implements PeerTransport {
    private static final String TAG = "MqttPeerTransport";

    private final String brokerUri;
    private final MqttAsyncClient client;
    @Nullable private PeerTransportCallback callback;

    public MqttPeerTransport(@NonNull String brokerUri, @NonNull String clientId) throws MqttException {
        this.brokerUri = brokerUri;
        this.client = new MqttAsyncClient(brokerUri, clientId, new MemoryPersistence());
    }

    @Override
    public void setCallback(@Nullable PeerTransportCallback callback) {
        this.callback = callback;
        client.setCallback(new MqttCallbackExtended() {
            @Override public void connectComplete(boolean reconnect, String serverURI) {
                PeerTransportCallback cb = MqttPeerTransport.this.callback;
                if (cb == null) return;
                // Paho fires connectComplete on its own internal reconnect/callback thread.
                // Calling subscribe() from that thread while automaticReconnect=true causes
                // MqttAsyncClient.subscribeBase to throw (Paho v1.x lock contention bug).
                // Dispatch to a separate thread so subscribe() runs off the Paho stack.
                final String uri = serverURI != null ? serverURI : brokerUri;
                new Thread(() -> cb.onConnected(reconnect, uri), "mqtt-connect-notify").start();
            }

            @Override public void connectionLost(Throwable cause) {
                PeerTransportCallback cb = MqttPeerTransport.this.callback;
                if (cb != null) cb.onConnectionLost(cause);
            }

            @Override public void messageArrived(String topic, MqttMessage message) {
                PeerTransportCallback cb = MqttPeerTransport.this.callback;
                if (cb != null) cb.onMessageArrived(topic, message.getPayload());
            }

            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
    }

    @Override
    public void connect(@Nullable String lastWillTopic,
                        @Nullable byte[] lastWillPayload,
                        int lastWillQos,
                        boolean lastWillRetained,
                        @Nullable Runnable onConnected,
                        @Nullable Runnable onFailure) {
        try {
            MqttConnectOptions opts = new MqttConnectOptions();
            // cleanSession=true: don't accumulate stale session state on the public broker.
            // Retained messages (peer presence, drone ownership) are always replayed on subscribe
            // regardless of this flag, so arbitration state is recovered on reconnect.
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(30);
            opts.setConnectionTimeout(10);
            if (lastWillTopic != null && lastWillPayload != null) {
                opts.setWill(lastWillTopic, lastWillPayload, lastWillQos, lastWillRetained);
            }
            client.connect(opts, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken asyncActionToken) {
                    CTInfo(TAG, "MQTT connect succeeded.");
                    if (onConnected != null) onConnected.run();
                }

                @Override public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    CTError(TAG, "MQTT connect failed: " + (exception != null ? exception.getMessage() : "unknown"));
                    if (onFailure != null) onFailure.run();
                }
            });
        } catch (MqttException e) {
            CTError(TAG, "connect() raised.", e);
            if (onFailure != null) onFailure.run();
        }
    }

    @Override
    public void disconnect() {
        try {
            if (client.isConnected()) client.disconnect();
        } catch (MqttException e) {
            CTError(TAG, "disconnect() raised.", e);
        }
    }

    @Override
    public boolean isConnected() {
        return client.isConnected();
    }

    @Override
    public void subscribe(@NonNull String topicFilter, int qos, @Nullable Runnable onSuccess, @Nullable Runnable onFailure) {
        try {
            client.subscribe(topicFilter, qos, null, new IMqttActionListener() {
                @Override public void onSuccess(IMqttToken asyncActionToken) {
                    if (onSuccess != null) onSuccess.run();
                }

                @Override public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    CTError(TAG, "subscribe(" + topicFilter + ") failed: " +
                            (exception != null ? exception.getMessage() : "unknown"));
                    if (onFailure != null) onFailure.run();
                }
            });
        } catch (MqttException e) {
            CTError(TAG, "subscribe(" + topicFilter + ") raised.", e);
            if (onFailure != null) onFailure.run();
        }
    }

    @Override
    public void publish(@NonNull String topic, @NonNull byte[] payload, int qos, boolean retained) {
        try {
            MqttMessage msg = new MqttMessage(payload);
            msg.setQos(qos);
            msg.setRetained(retained);
            client.publish(topic, msg);
        } catch (MqttException e) {
            CTError(TAG, "publish(" + topic + ") raised.", e);
        }
    }
}

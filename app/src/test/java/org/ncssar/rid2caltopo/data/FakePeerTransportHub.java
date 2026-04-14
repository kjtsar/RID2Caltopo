package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory MQTT-like hub for peer-coordination tests.
 *
 * This intentionally models only the semantics R2CMqttManager relies on:
 * retained messages, exact topic delivery, and simple wildcard subscription.
 */
public final class FakePeerTransportHub {

    private final ConcurrentHashMap<String, FakePeerTransport> transports = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> retainedPayloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> retainedQos = new ConcurrentHashMap<>();

    @NonNull
    public PeerTransportFactory asFactory() {
        return (brokerUri, clientId) -> new FakePeerTransport(this, brokerUri, clientId);
    }

    void register(@NonNull FakePeerTransport transport) {
        transports.put(transport.clientId, transport);
    }

    void unregister(@NonNull FakePeerTransport transport) {
        transports.remove(transport.clientId);
        if (transport.lastWillTopic != null && transport.lastWillPayload != null) {
            publishInternal(
                    transport,
                    transport.lastWillTopic,
                    transport.lastWillPayload,
                    transport.lastWillQos,
                    transport.lastWillRetained
            )
            ;
        }
    }

    void publish(
            @NonNull FakePeerTransport publisher,
            @NonNull String topic,
            @NonNull byte[] payload,
            int qos,
            boolean retained
    ) {
        publishInternal(publisher, topic, payload, qos, retained);
    }

    private void publishInternal(
            @Nullable FakePeerTransport publisher,
            @NonNull String topic,
            @NonNull byte[] payload,
            int qos,
            boolean retained
    ) {
        if (retained) {
            if (payload.length == 0) {
                retainedPayloads.remove(topic);
                retainedQos.remove(topic);
            } else {
                retainedPayloads.put(topic, payload.clone());
                retainedQos.put(topic, qos);
            }
        }
        for (FakePeerTransport transport : transports.values()) {
            transport.deliver(topic, payload.clone());
        }
    }

    void subscribe(@NonNull FakePeerTransport subscriber, @NonNull String topicFilter) {
        Map<String, byte[]> snapshot = new LinkedHashMap<>(retainedPayloads);
        for (Map.Entry<String, byte[]> entry : snapshot.entrySet()) {
            if (matches(topicFilter, entry.getKey())) {
                subscriber.deliver(entry.getKey(), entry.getValue().clone());
            }
        }
    }

    static boolean matches(@NonNull String filter, @NonNull String topic) {
        String[] filterParts = filter.split("/");
        String[] topicParts = topic.split("/");
        int fi = 0;
        int ti = 0;
        while (fi < filterParts.length && ti < topicParts.length) {
            String f = filterParts[fi];
            if ("#".equals(f)) return true;
            if ("+".equals(f) || f.equals(topicParts[ti])) {
                fi++;
                ti++;
                continue;
            }
            return false;
        }
        if (fi == filterParts.length && ti == topicParts.length) return true;
        return fi == filterParts.length - 1 && "#".equals(filterParts[fi]);
    }

    static final class FakePeerTransport implements PeerTransport {
        private final FakePeerTransportHub hub;
        private final String brokerUri;
        private final CopyOnWriteArrayList<String> subscriptions = new CopyOnWriteArrayList<>();
        private volatile PeerTransportCallback callback;
        private volatile boolean connected;
        private volatile String lastWillTopic;
        private volatile byte[] lastWillPayload;
        private volatile int lastWillQos;
        private volatile boolean lastWillRetained;

        final String clientId;

        FakePeerTransport(
                @NonNull FakePeerTransportHub hub,
                @NonNull String brokerUri,
                @NonNull String clientId
        ) {
            this.hub = hub;
            this.brokerUri = brokerUri;
            this.clientId = clientId;
        }

        @Override
        public void setCallback(@Nullable PeerTransportCallback callback) {
            this.callback = callback;
        }

        @Override
        public void connect(
                @Nullable String lastWillTopic,
                @Nullable byte[] lastWillPayload,
                int lastWillQos,
                boolean lastWillRetained,
                @Nullable Runnable onConnected,
                @Nullable Runnable onFailure
        ) {
            this.lastWillTopic = lastWillTopic;
            this.lastWillPayload = lastWillPayload != null ? lastWillPayload.clone() : null;
            this.lastWillQos = lastWillQos;
            this.lastWillRetained = lastWillRetained;
            this.connected = true;
            hub.register(this);
            if (onConnected != null) onConnected.run();
            PeerTransportCallback cb = callback;
            if (cb != null) cb.onConnected(false, brokerUri);
        }

        @Override
        public void disconnect() {
            if (!connected) return;
            connected = false;
            hub.unregister(this);
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void subscribe(@NonNull String topicFilter, int qos, @Nullable Runnable onSuccess, @Nullable Runnable onFailure) {
            subscriptions.addIfAbsent(topicFilter);
            hub.subscribe(this, topicFilter);
            if (onSuccess != null) onSuccess.run();
        }

        @Override
        public void publish(@NonNull String topic, @NonNull byte[] payload, int qos, boolean retained) {
            hub.publish(this, topic, payload, qos, retained);
        }

        void deliver(@NonNull String topic, @NonNull byte[] payload) {
            if (!connected) return;
            if (!isSubscribed(topic)) return;
            PeerTransportCallback cb = callback;
            if (cb != null) cb.onMessageArrived(topic, payload);
        }

        private boolean isSubscribed(@NonNull String topic) {
            for (String filter : subscriptions) {
                if (FakePeerTransportHub.matches(filter, topic)) return true;
            }
            return false;
        }

        /** Returns true if the given filter string is registered as a subscription (exact match). */
        public boolean hasSubscription(@NonNull String filter) {
            return subscriptions.contains(filter);
        }
    }
}

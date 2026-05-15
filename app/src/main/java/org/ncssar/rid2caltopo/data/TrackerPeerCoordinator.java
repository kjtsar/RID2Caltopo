package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.ncssar.rid2caltopo.BuildConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracker-backed coordination channel that uses tracker.kjt.us as the public
 * rendezvous, lease, and optional sighting relay service.
 *
 * The tracker service assigns a single owner per drone stream. Only the owner
 * publishes ordered waypoints to CalTopo; non-owner sightings are forwarded to
 * the owner over the tracker websocket.
 */
public final class TrackerPeerCoordinator implements PeerCoordinator {
    private static final String TAG = "TrackerPeerCoord";
    private static final TrackerPeerCoordinator INSTANCE = new TrackerPeerCoordinator();
    interface TimeSource {
        long now();
    }
    interface HardFailureListener {
        void onHardFailure(int responseCode, @Nullable String responseMessage);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long HELLO_ACK_TIMEOUT_MS = 10_000L;
    private static final long HEARTBEAT_ACK_TIMEOUT_MS = 10_000L;
    private static final long ACK_WATCHDOG_INTERVAL_MS = 5_000L;
    private static final long HEARTBEAT_MIN_SEND_GAP_MS = 1_000L;
    private static final long CONNECT_GRACE_MS = 12_000L;
    private static final long DEFAULT_HANDOFF_DELAY_MS = 2_000L;
    private static volatile long handoffDelayMs = DEFAULT_HANDOFF_DELAY_MS;
    private static final long RECONNECT_BASE_DELAY_MS = 2_000L;
    private static final long RECONNECT_MAX_DELAY_MS = 10_000L;

    private static volatile TrackerCoordinationTransportFactory transportFactory =
            OkHttpTrackerCoordinationTransport::new;
    @NonNull private static volatile TimeSource timeSource = System::currentTimeMillis;
    @Nullable private static volatile String trackerApiKeyOverrideForTesting;
    @Nullable private static volatile String trackerUrlPrefixOverrideForTesting;

    private static final class PendingDrone {
        @NonNull final LiveTrackOwnerDelegate liveTrack;
        @NonNull final CtDroneSpec droneSpec;
        final double distMeters;
        final long firstSeenTs;
        @NonNull final DelayedExec fallbackTimer = new DelayedExec(false);
        @NonNull final DelayedExec ownershipActivationTimer = new DelayedExec(false);
        volatile boolean firstSightingSent;

        PendingDrone(@NonNull LiveTrackOwnerDelegate liveTrack,
                     @NonNull CtDroneSpec droneSpec,
                     double distMeters,
                     long firstSeenTs) {
            this.liveTrack = liveTrack;
            this.droneSpec = droneSpec;
            this.distMeters = distMeters;
            this.firstSeenTs = firstSeenTs;
        }
    }

    @NonNull private final ConcurrentHashMap<String, PendingDrone> pendingDrones = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, R2CMqttManager.PeerState> peers = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, String> ownerByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final ConcurrentHashMap<String, Long> leaseSeqByRemoteId = new ConcurrentHashMap<>();
    @NonNull private final DelayedExec heartbeatTimer = new DelayedExec(false);
    @NonNull private final DelayedExec reconnectTimer = new DelayedExec(false);
    @NonNull private final DelayedExec ackWatchdogTimer = new DelayedExec(false);
    @NonNull private final DelayedExec heartbeatCoalesceTimer = new DelayedExec(false);
    @Nullable private volatile String lastOutboundJsonForTesting;
    @Nullable private volatile String lastWaypointRemoteIdForTesting;

    @Nullable private volatile TrackerCoordinationTransport transport;
    @Nullable private volatile R2CMqttManager.PeerListChangedListener peerListChangedListener;
    @Nullable private volatile CoordinationIndicatorListener coordinationIndicatorListener;
    @Nullable private volatile HardFailureListener hardFailureListener;

    @Nullable private volatile String mapId;
    @Nullable private volatile String myGuid;
    @Nullable private volatile String myName;
    @Nullable private volatile String trackerApiKey;
    @Nullable private volatile String trackerWsUrl;
    private volatile double myLat;
    private volatile double myLon;
    private volatile long myCaltopoRttMs = 2_000L;
    private volatile boolean started;
    private volatile boolean hardFailureNotified;
    private volatile long nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
    private volatile long helloSeqSentAtMs;
    private volatile long helloAckAtMs;
    private volatile long heartbeatSeqCounter;
    private volatile long lastHeartbeatSeqSent;
    private volatile long lastHeartbeatSeqAcked;
    private volatile long lastHeartbeatSentAtMs;
    private volatile long lastHeartbeatAckAtMs;
    private volatile long lastOwnerLeaseExpireTs;
    private volatile int lastCloseCode;
    @NonNull private volatile String lastCloseReason = "";
    @NonNull private volatile String lastReconnectCause = "";
    private volatile long forcedReconnectCount;
    private volatile boolean heartbeatSendQueued;
    private volatile long reconnectScheduledAtMs;
    private volatile long reconnectTargetAtMs;
    private volatile boolean reconnectPending;

    private TrackerPeerCoordinator() {
    }

    @NonNull
    public static TrackerPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        stop();

        String trackerUrlPrefix = trackerUrlPrefixOverrideForTesting != null
                ? trackerUrlPrefixOverrideForTesting
                : CaltopoClient.GetTrackerCoordinationUrlPfx();
        String trackerApiKey = trackerApiKeyOverrideForTesting != null
                ? trackerApiKeyOverrideForTesting
                : CaltopoClient.GetTrackerCoordinationApiKey();
        String normalizedTrackerApiKey = normalizeTrackerToken(trackerApiKey);
        if (!trackerApiKey.equals(normalizedTrackerApiKey)) {
            CTWarn(TAG, "start(): trimming tracker token whitespace before websocket auth.");
        }
        trackerApiKey = normalizedTrackerApiKey;
        if (trackerUrlPrefix.isEmpty() || trackerApiKey.isEmpty()) {
            CTWarn(TAG, "start(): tracker coordination not configured; falling back to local ownership.");
            this.mapId = mapId;
            this.myGuid = guid;
            this.myName = name;
            this.started = true;
            notifyCoordinationIndicatorListener();
            return;
        }

        this.mapId = mapId;
        this.myGuid = guid;
        this.myName = name;
        this.trackerApiKey = trackerApiKey;
        this.trackerWsUrl = buildTrackerWebSocketUrl(trackerUrlPrefix);
        this.started = true;
        this.hardFailureNotified = false;
        this.nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
        this.helloSeqSentAtMs = 0L;
        this.helloAckAtMs = 0L;
        this.heartbeatSeqCounter = 0L;
        this.lastHeartbeatSeqSent = 0L;
        this.lastHeartbeatSeqAcked = 0L;
        this.lastHeartbeatSentAtMs = 0L;
        this.lastHeartbeatAckAtMs = 0L;
        this.lastOwnerLeaseExpireTs = 0L;
        this.lastCloseCode = 0;
        this.lastCloseReason = "";
        this.lastReconnectCause = "";
        this.forcedReconnectCount = 0L;
        this.reconnectScheduledAtMs = 0L;
        this.reconnectTargetAtMs = 0L;
        this.reconnectPending = false;
        CTInfo(TAG, String.format(Locale.US,
                "start(): wsUrl='%s' token=%s %s",
                this.trackerWsUrl,
                describeToken(trackerApiKey),
                CaltopoClient.DescribeTrackerCredentialSelection("coordination")));

        transport = transportFactory.create();
        transport.setCallback(new TrackerCoordinationTransport.Callback() {
            @Override
            public void onOpen() {
                CTInfo(TAG, "tracker websocket connected");
                reconnectTimer.stop();
                onTransportOpen(false);
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed(int code, @NonNull String reason) {
                lastCloseCode = code;
                lastCloseReason = reason;
                CTWarn(TAG, String.format(Locale.US,
                        "tracker websocket closed: code=%d reason='%s'", code, reason));
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                scheduleReconnect("closed", -1L);
            }

            @Override
            public void onFailure(@Nullable Throwable throwable, int responseCode, @Nullable String responseMessage) {
                if (throwable != null) {
                    String message = throwable.getMessage();
                    if (message == null || message.isEmpty()) {
                        message = throwable.getClass().getSimpleName();
                    }
                    if (throwable instanceof EOFException) {
                        CTInfo(TAG, "tracker websocket transient disconnect: " + message);
                    } else {
                        CTWarn(TAG, "tracker websocket failure: " + message);
                    }
                } else {
                    CTWarn(TAG, "tracker websocket failure: unknown");
                }
                lastCloseCode = responseCode;
                lastCloseReason = responseMessage != null ? responseMessage : "";
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect("failure", -1L);
            }
        });
        transport.connect(this.trackerWsUrl, trackerApiKey);
        notifyCoordinationIndicatorListener();
    }

    @Override
    public synchronized void stop() {
        heartbeatTimer.stop();
        reconnectTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        Iterator<Map.Entry<String, PendingDrone>> iterator = pendingDrones.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingDrone> entry = iterator.next();
            entry.getValue().fallbackTimer.stop();
            entry.getValue().ownershipActivationTimer.stop();
            entry.getValue().liveTrack.setLocalOwner(false);
            iterator.remove();
        }
        peers.clear();
        ownerByRemoteId.clear();
        leaseSeqByRemoteId.clear();
        notifyPeerListChanged();
        TrackerCoordinationTransport activeTransport = transport;
        transport = null;
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
        started = false;
        hardFailureNotified = false;
        reconnectPending = false;
        notifyCoordinationIndicatorListener();
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        if (droneSpec.isLocalArchiveOnly()) {
            liveTrack.setLocalOwner(false);
            pendingDrones.remove(droneSpec.getRemoteId());
            ownerByRemoteId.remove(droneSpec.getRemoteId());
            leaseSeqByRemoteId.remove(droneSpec.getRemoteId());
            return;
        }
        PendingDrone pending = new PendingDrone(liveTrack, droneSpec, distMeters, firstSeenTs);
        pendingDrones.put(droneSpec.getRemoteId(), pending);
        scheduleFallbackOwnership(pending);
        sendFirstSighting(pending);
    }

    @Override
    public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters,
                                   long timestampMsec,
                                   @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        lastWaypointRemoteIdForTesting = droneSpec.getRemoteId();
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "sighting");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("remoteId", droneSpec.getRemoteId());
            jo.put("mappedId", droneSpec.getMappedId());
            jo.put("trackLabel", droneSpec.trackLabel());
            jo.put("droneTs", timestampMsec);
            jo.put("lat", droneLat);
            jo.put("lng", droneLon);
            jo.put("altM", droneAlt);
            jo.put("distanceFromZoneM", distMeters);
            putTelemetry(jo, telemetry);
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "onWaypointReceived() raised", e);
        }
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        PendingDrone pending = pendingDrones.remove(remoteId);
        boolean localArchiveOnly = pending != null && pending.droneSpec.isLocalArchiveOnly();
        if (pending != null) {
            pending.fallbackTimer.stop();
            pending.ownershipActivationTimer.stop();
            pending.liveTrack.setLocalOwner(false);
        }
        ownerByRemoteId.remove(remoteId);
        leaseSeqByRemoteId.remove(remoteId);
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "drone_lost");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("remoteId", remoteId);
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "onDroneLost() raised", e);
        }
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        String ownerGuid = ownerByRemoteId.get(remoteId);
        return ownerGuid != null && ownerGuid.equals(myGuid);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        myCaltopoRttMs = rttMs;
    }

    @Override
    public long getCaltopoRttMs() {
        return myCaltopoRttMs;
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        if (started && isConnected()) {
            requestHeartbeat("position");
        }
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        peerListChangedListener = listener;
    }

    @Override
    public void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener) {
        coordinationIndicatorListener = listener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(getCoordinationIndicatorState());
        }
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return new ArrayList<>(peers.values());
    }

    @NonNull
    @Override
    public CoordinationIndicatorState getCoordinationIndicatorState() {
        if (!started || trackerWsUrl == null || trackerApiKey == null) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }
        return isConnected()
                ? CoordinationIndicatorState.HEALTHY
                : CoordinationIndicatorState.DEGRADED;
    }

    @NonNull
    @Override
    public String getCoordinationStatusText() {
        CoordinationIndicatorState state = getCoordinationIndicatorState();
        switch (state) {
            case HEALTHY:
                return "Tracker link healthy";
            case DEGRADED:
                return "Tracker link degraded";
            case UNCONFIGURED:
            default:
                return "Tracker link not configured";
        }
    }

    @NonNull
    @Override
    public List<String> getCoordinationDiagnosticLines() {
        ArrayList<String> lines = new ArrayList<>();
        long nowMs = nowMs();
        if (!started) {
            lines.add("Tracker coordinator stopped");
            return lines;
        }
        if (trackerWsUrl == null || trackerApiKey == null) {
            lines.add("Tracker websocket not configured");
            return lines;
        }

        if (helloAckAtMs > 0L) {
            lines.add("Hello ack " + formatDuration(nowMs - helloAckAtMs) + " ago");
        } else if (helloSeqSentAtMs > 0L) {
            lines.add("Waiting for hello ack " + formatDuration(nowMs - helloSeqSentAtMs));
        } else {
            lines.add("Waiting for tracker hello");
        }

        if (lastHeartbeatAckAtMs > 0L) {
            lines.add(String.format(Locale.US,
                    "Heartbeat ack %s ago (seq %d)",
                    formatDuration(nowMs - lastHeartbeatAckAtMs),
                    lastHeartbeatSeqAcked));
        } else if (lastHeartbeatSentAtMs > 0L) {
            lines.add(String.format(Locale.US,
                    "Waiting for heartbeat ack seq %d %s",
                    lastHeartbeatSeqSent,
                    formatDuration(nowMs - lastHeartbeatSentAtMs)));
        }

        if (reconnectPending) {
            lines.add("Reconnect pending in " + formatDuration(Math.max(reconnectTargetAtMs - nowMs, 0L)));
        } else if (!lastReconnectCause.isEmpty() && !"connected".equals(lastReconnectCause)) {
            lines.add("Last tracker event: " + lastReconnectCause);
        }

        if (lastCloseCode != 0 || !lastCloseReason.isEmpty()) {
            String reason = lastCloseReason.isEmpty() ? "" : " " + lastCloseReason;
            lines.add("Last close: " + lastCloseCode + reason);
        }

        return lines;
    }

    private boolean isConnected() {
        TrackerCoordinationTransport activeTransport = transport;
        return activeTransport != null && activeTransport.isConnected();
    }

    private void notifyCoordinationIndicatorListener() {
        CoordinationIndicatorListener listener = coordinationIndicatorListener;
        if (listener != null) {
            listener.onCoordinationIndicatorStateChanged(getCoordinationIndicatorState());
        }
    }

    private void scheduleReconnect() {
        scheduleReconnect("retry", -1L);
    }

    private void scheduleReconnect(@NonNull String cause, long overrideDelayMs) {
        if (!started || trackerWsUrl == null || trackerApiKey == null) return;
        long delayMs = overrideDelayMs >= 0 ? overrideDelayMs : nextReconnectDelayMs;
        long scheduledAtMs = nowMs();
        long targetAtMs = scheduledAtMs + delayMs;
        if (reconnectPending && reconnectTargetAtMs > 0L && reconnectTargetAtMs <= targetAtMs) {
            CTDebug(TAG, String.format(Locale.US,
                    "scheduleReconnect(): ignoring duplicate cause=%s existingDelayMs=%d requestedDelayMs=%d",
                    cause,
                    Math.max(reconnectTargetAtMs - reconnectScheduledAtMs, 0L),
                    delayMs));
            return;
        }
        reconnectPending = true;
        lastReconnectCause = cause;
        reconnectScheduledAtMs = scheduledAtMs;
        reconnectTargetAtMs = targetAtMs;
        CTDebug(TAG, String.format(Locale.US,
                "scheduleReconnect(): cause=%s delayMs=%d lastAckSeq=%d helloAckAgeMs=%d heartbeatAckAgeMs=%d closeCode=%d closeReason='%s'",
                cause,
                delayMs,
                lastHeartbeatSeqAcked,
                ageSince(helloAckAtMs),
                ageSince(lastHeartbeatAckAtMs),
                lastCloseCode,
                lastCloseReason));
        reconnectTimer.start(this::reconnect, delayMs, 0L);
        if (overrideDelayMs < 0) {
            nextReconnectDelayMs = Math.min(nextReconnectDelayMs * 2L, RECONNECT_MAX_DELAY_MS);
        }
    }

    private synchronized void reconnect() {
        if (!started || trackerWsUrl == null || trackerApiKey == null) {
            reconnectPending = false;
            return;
        }
        long reconnectStartedAtMs = nowMs();
        long schedulerSkewMs = reconnectTargetAtMs > 0L
                ? Math.max(reconnectStartedAtMs - reconnectTargetAtMs, 0L)
                : -1L;
        long queuedForMs = reconnectScheduledAtMs > 0L
                ? Math.max(reconnectStartedAtMs - reconnectScheduledAtMs, 0L)
                : -1L;
        CTInfo(TAG, String.format(Locale.US,
                "reconnect(): cause=%s queuedForMs=%d schedulerSkewMs=%d targetDelayMs=%d",
                lastReconnectCause,
                queuedForMs,
                schedulerSkewMs,
                reconnectTargetAtMs > reconnectScheduledAtMs
                        ? (reconnectTargetAtMs - reconnectScheduledAtMs)
                        : -1L));
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null) {
            activeTransport = transportFactory.create();
            transport = activeTransport;
        }
        activeTransport.setCallback(new TrackerCoordinationTransport.Callback() {
            @Override
            public void onOpen() {
                CTInfo(TAG, "tracker websocket reconnected");
                reconnectTimer.stop();
                onTransportOpen(true);
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed(int code, @NonNull String reason) {
                lastCloseCode = code;
                lastCloseReason = reason;
                heartbeatTimer.stop();
                ackWatchdogTimer.stop();
                notifyCoordinationIndicatorListener();
                scheduleReconnect("closed", -1L);
            }

            @Override
            public void onFailure(@Nullable Throwable throwable, int responseCode, @Nullable String responseMessage) {
                heartbeatTimer.stop();
                if (throwable != null) {
                    String message = throwable.getMessage();
                    if (message == null || message.isEmpty()) {
                        message = throwable.getClass().getSimpleName();
                    }
                    if (throwable instanceof EOFException) {
                        CTInfo(TAG, "tracker websocket reconnect transient disconnect: " + message);
                    } else {
                        CTWarn(TAG, "tracker websocket reconnect failure: " + message);
                    }
                }
                lastCloseCode = responseCode;
                lastCloseReason = responseMessage != null ? responseMessage : "";
                notifyCoordinationIndicatorListener();
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect("failure", -1L);
            }
        });
        activeTransport.connect(trackerWsUrl, trackerApiKey);
    }

    private void onTransportOpen(boolean reconnecting) {
        reconnectPending = false;
        nextReconnectDelayMs = RECONNECT_BASE_DELAY_MS;
        lastReconnectCause = reconnecting ? "reconnected" : "connected";
        sendHello();
        heartbeatSendQueued = false;
        heartbeatTimer.start(() -> requestHeartbeat("interval"), 0L, HEARTBEAT_INTERVAL_MS);
        ackWatchdogTimer.start(TrackerPeerCoordinator.this::checkAckLiveness, ACK_WATCHDOG_INTERVAL_MS, ACK_WATCHDOG_INTERVAL_MS);
        markAllPendingFirstSightingsDirty();
        replayPendingFirstSightings();
        notifyCoordinationIndicatorListener();
    }

    private void notifyHardFailureIfNeeded(int responseCode, @Nullable String responseMessage) {
        if ((responseCode == 401 || responseCode == 403) && !hardFailureNotified) {
            hardFailureNotified = true;
            HardFailureListener listener = hardFailureListener;
            if (listener != null) {
                listener.onHardFailure(responseCode, responseMessage);
            }
        }
    }

    private void scheduleFallbackOwnership(@NonNull PendingDrone pending) {
        pending.fallbackTimer.start(() -> {
            if (!isConnected() && pendingDrones.containsKey(pending.droneSpec.getRemoteId())) {
                CTWarn(TAG, String.format(Locale.US,
                        "Fallback ownership for %s after tracker connect grace expired.",
                        pending.droneSpec.getRemoteId()));
                applyOwnerAssignment(pending.droneSpec.getRemoteId(), myGuid, -1L, 0L);
            }
        }, CONNECT_GRACE_MS, 0L);
    }

    private void replayPendingFirstSightings() {
        for (PendingDrone pending : pendingDrones.values()) {
            sendFirstSighting(pending);
        }
    }

    private void markAllPendingFirstSightingsDirty() {
        for (PendingDrone pending : pendingDrones.values()) {
            pending.firstSightingSent = false;
        }
    }

    private void sendHello() {
        JSONObject jo = new JSONObject();
        try {
            helloSeqSentAtMs = nowMs();
            jo.put("type", "hello");
            jo.put("mapId", mapId);
            jo.put("incidentId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("lat", myLat);
            jo.put("lng", myLon);
            jo.put("appVersion", BuildConfig.VERSION_NAME);
            jo.put("caltopoRttMs", myCaltopoRttMs);
            CTDebug(TAG, String.format(Locale.US,
                    "sendHello(): mapId=%s zoneId=%s lat=%.6f lng=%.6f rttMs=%d",
                    mapId, myGuid, myLat, myLon, myCaltopoRttMs));
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHello() raised", e);
        }
    }

    private void sendHeartbeat() {
        JSONObject jo = new JSONObject();
        try {
            heartbeatSendQueued = false;
            if (lastHeartbeatSeqSent > lastHeartbeatSeqAcked) {
                long ackAgeMs = nowMs() - lastHeartbeatSentAtMs;
                if (ackAgeMs > HEARTBEAT_ACK_TIMEOUT_MS) {
                    forceReconnect(String.format(Locale.US,
                            "missed heartbeat_ack seq=%d ageMs=%d", lastHeartbeatSeqSent, ackAgeMs));
                }
                return;
            }
            long seq = ++heartbeatSeqCounter;
            lastHeartbeatSeqSent = seq;
            lastHeartbeatSentAtMs = nowMs();
            jo.put("type", "heartbeat");
            jo.put("seq", seq);
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("lat", myLat);
            jo.put("lng", myLon);
            jo.put("caltopoRttMs", myCaltopoRttMs);
            CTDebug(TAG, String.format(Locale.US,
                    "sendHeartbeat(): mapId=%s zoneId=%s lat=%.6f lng=%.6f rttMs=%d",
                    mapId, myGuid, myLat, myLon, myCaltopoRttMs));
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHeartbeat() raised", e);
        }
    }

    private void requestHeartbeat(@NonNull String reason) {
        if (!started || !isConnected()) return;
        if (heartbeatSendQueued) return;

        long nowMs = nowMs();
        long gapMs = nowMs - lastHeartbeatSentAtMs;
        long delayMs = (lastHeartbeatSentAtMs <= 0 || gapMs >= HEARTBEAT_MIN_SEND_GAP_MS)
                ? 0L
                : (HEARTBEAT_MIN_SEND_GAP_MS - gapMs);
        heartbeatSendQueued = true;
        if (delayMs > 0L) {
            CTDebug(TAG, String.format(Locale.US,
                    "requestHeartbeat(): coalescing reason=%s delayMs=%d", reason, delayMs));
        }
        heartbeatCoalesceTimer.start(this::sendHeartbeat, delayMs, 0L);
    }

    private void sendFirstSighting(@NonNull PendingDrone pending) {
        if (!started || pending.firstSightingSent || !isConnected()) {
            return;
        }
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "first_sighting");
            jo.put("mapId", mapId);
            jo.put("incidentId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("remoteId", pending.droneSpec.getRemoteId());
            jo.put("mappedId", pending.droneSpec.getMappedId());
            jo.put("trackLabel", pending.droneSpec.trackLabel());
            jo.put("droneTs", pending.firstSeenTs);
            jo.put("distanceFromZoneM", pending.distMeters);
            jo.put("lat", pending.droneSpec.lastLat);
            jo.put("lng", pending.droneSpec.lastLng);
            jo.put("altM", pending.droneSpec.lastAlt);
            jo.put("org", pending.droneSpec.getOrg());
            jo.put("model", pending.droneSpec.getModel());
            jo.put("ownerName", pending.droneSpec.getOwner());
            CTDebug(TAG, String.format(Locale.US,
                    "sendFirstSighting(): remoteId=%s mappedId=%s droneTs=%d dist=%.1f",
                    pending.droneSpec.getRemoteId(),
                    pending.droneSpec.getMappedId(),
                    pending.firstSeenTs,
                    pending.distMeters));
            sendJson(jo);
            pending.firstSightingSent = true;
        } catch (Exception e) {
            CTError(TAG, "sendFirstSighting() raised", e);
        }
    }

    private void sendJson(@NonNull JSONObject jo) {
        lastOutboundJsonForTesting = jo.toString();
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null || !activeTransport.isConnected()) return;
        activeTransport.send(jo.toString());
    }

    private void checkAckLiveness() {
        if (!started || !isConnected()) return;
        long nowMs = nowMs();
        if (helloAckAtMs < helloSeqSentAtMs && helloSeqSentAtMs > 0 &&
                nowMs - helloSeqSentAtMs > HELLO_ACK_TIMEOUT_MS) {
            forceReconnect(String.format(Locale.US,
                    "missed hello_ack ageMs=%d", nowMs - helloSeqSentAtMs));
            return;
        }
        if (lastHeartbeatSeqSent > lastHeartbeatSeqAcked && lastHeartbeatSentAtMs > 0 &&
                nowMs - lastHeartbeatSentAtMs > HEARTBEAT_ACK_TIMEOUT_MS) {
            forceReconnect(String.format(Locale.US,
                    "missed heartbeat_ack seq=%d ageMs=%d",
                    lastHeartbeatSeqSent, nowMs - lastHeartbeatSentAtMs));
        }
    }

    private synchronized void forceReconnect(@NonNull String cause) {
        if (!started) return;
        if (reconnectPending) {
            CTWarn(TAG, String.format(Locale.US,
                    "forceReconnect(): reconnect already pending; ignoring duplicate cause=%s lastReconnectCause=%s",
                    cause,
                    lastReconnectCause));
            return;
        }
        forcedReconnectCount++;
        lastReconnectCause = cause;
        CTWarn(TAG, String.format(Locale.US,
                "forceReconnect(): cause=%s lastAckSeq=%d helloAckAgeMs=%d heartbeatAckAgeMs=%d closeCode=%d closeReason='%s'",
                cause,
                lastHeartbeatSeqAcked,
                ageSince(helloAckAtMs),
                ageSince(lastHeartbeatAckAtMs),
                lastCloseCode,
                lastCloseReason));
        heartbeatTimer.stop();
        ackWatchdogTimer.stop();
        heartbeatCoalesceTimer.stop();
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport != null) {
            activeTransport.disconnect();
        }
        scheduleReconnect("forced-" + cause, 0L);
    }

    private void handleIncomingMessage(@NonNull String text) {
        try {
            JSONObject jo = new JSONObject(text);
            String type = jo.optString("type");
            switch (type) {
                case "hello_ack":
                    helloAckAtMs = nowMs();
                    CTDebug(TAG, String.format(Locale.US,
                            "hello_ack received from tracker after %d ms",
                            helloAckAtMs - helloSeqSentAtMs));
                    break;
                case "heartbeat_ack":
                    onHeartbeatAck(jo);
                    break;
                case "zone_update":
                    onZoneUpdate(jo.optJSONArray("zones"));
                    break;
                case "owner_assigned":
                    applyOwnerAssignment(
                            jo.optString("remoteId"),
                            firstNonEmpty(jo.optString("ownerGuid"), jo.optString("ownerZoneId")),
                            jo.optLong("leaseSeq", -1L),
                            jo.optLong("leaseExpireTs", 0L));
                    break;
                case "owner_expired":
                    clearOwner(jo.optString("remoteId"));
                    break;
                case "relay_sighting":
                    onRelaySighting(jo);
                    break;
                default:
                    CTDebug(TAG, "Ignoring tracker message type: " + type);
                    break;
            }
        } catch (Exception e) {
            CTError(TAG, "handleIncomingMessage() raised for: " + text, e);
        }
    }

    private void onZoneUpdate(@Nullable JSONArray zones) {
        peers.clear();
        if (zones != null) {
            for (int i = 0; i < zones.length(); i++) {
                JSONObject zone = zones.optJSONObject(i);
                if (zone == null) continue;
                String zoneGuid = firstNonEmpty(zone.optString("guid"), zone.optString("zoneId"));
                if (zoneGuid.isEmpty() || zoneGuid.equals(myGuid)) continue;
                R2CMqttManager.PeerState ps = new R2CMqttManager.PeerState(zoneGuid);
                ps.name = firstNonEmpty(zone.optString("name"), zoneGuid);
                ps.lat = zone.optDouble("lat", 0.0);
                ps.lon = zone.optDouble("lng", 0.0);
                ps.caltopoRttMs = zone.optLong("caltopoRttMs", 2_000L);
                ps.lastSeenMs = zone.optLong("lastSeenMs", nowMs());
                ps.online = zone.optBoolean("online", true);
                peers.put(zoneGuid, ps);
            }
        }
        notifyPeerListChanged();
    }

    private void onHeartbeatAck(@NonNull JSONObject jo) {
        long nowMs = nowMs();
        long ackSeq = jo.optLong("clientSeq", -1L);
        long ownerLeaseExpireTs = jo.optLong("ownerLeaseExpireTs", 0L);
        if (ackSeq <= 0) {
            CTWarn(TAG, "heartbeat_ack missing/invalid clientSeq");
            return;
        }
        if (ackSeq < lastHeartbeatSeqAcked) {
            CTWarn(TAG, String.format(Locale.US,
                    "heartbeat_ack stale seq=%d lastAckSeq=%d", ackSeq, lastHeartbeatSeqAcked));
            return;
        }
        if (ackSeq != lastHeartbeatSeqSent) {
            forceReconnect(String.format(Locale.US,
                    "heartbeat_ack seq mismatch ack=%d expected=%d", ackSeq, lastHeartbeatSeqSent));
            return;
        }
        lastHeartbeatSeqAcked = ackSeq;
        lastHeartbeatAckAtMs = nowMs;
        if (ownerLeaseExpireTs > 0) {
            lastOwnerLeaseExpireTs = ownerLeaseExpireTs;
        }
        CTDebug(TAG, String.format(Locale.US,
                "heartbeat_ack received: seq=%d rttMs=%d ownerLeaseExpireTs=%d",
                ackSeq, nowMs - lastHeartbeatSentAtMs, ownerLeaseExpireTs));
    }

    private void applyOwnerAssignment(@NonNull String remoteId, @Nullable String ownerGuid, long leaseSeq, long leaseExpireTs) {
        if (remoteId.isEmpty() || ownerGuid == null || ownerGuid.isEmpty()) return;
        Long previousLeaseSeq = leaseSeqByRemoteId.get(remoteId);
        if (leaseSeq >= 0 && previousLeaseSeq != null && leaseSeq < previousLeaseSeq) {
            CTWarn(TAG, String.format(Locale.US,
                    "applyOwnerAssignment(%s): ignoring stale leaseSeq=%d previousLeaseSeq=%d ownerGuid='%s'",
                    remoteId, leaseSeq, previousLeaseSeq, ownerGuid));
            return;
        }
        ownerByRemoteId.put(remoteId, ownerGuid);
        if (leaseSeq >= 0) {
            leaseSeqByRemoteId.put(remoteId, leaseSeq);
        }
        if (leaseExpireTs > 0 && ownerGuid.equals(myGuid)) {
            lastOwnerLeaseExpireTs = leaseExpireTs;
        }
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
            pending.fallbackTimer.stop();
            pending.ownershipActivationTimer.stop();
            if (ownerGuid.equals(myGuid)) {
                CTInfo(TAG, String.format(Locale.US,
                        "applyOwnerAssignment(%s): ownership granted locally; publishing in %d ms mappedId='%s' trackLabel='%s' queuedPoints=%d",
                        remoteId, handoffDelayMs, pending.droneSpec.getMappedId(), pending.droneSpec.trackLabel(), pending.liveTrack.getQueuedPointCount()));
                Runnable activateOwnership = () -> {
                    if (!started) return;
                    String currentOwnerGuid = ownerByRemoteId.get(remoteId);
                    if (myGuid != null && myGuid.equals(currentOwnerGuid)) {
                        pending.liveTrack.setLocalOwner(true);
                    }
                };
                if (handoffDelayMs <= 0L) {
                    activateOwnership.run();
                } else {
                    pending.ownershipActivationTimer.start(activateOwnership, handoffDelayMs, 0L);
                }
            } else {
                CTInfo(TAG, String.format(Locale.US,
                        "applyOwnerAssignment(%s): ownership assigned to peer guid='%s' mappedId='%s' trackLabel='%s'",
                        remoteId, ownerGuid, pending.droneSpec.getMappedId(), pending.droneSpec.trackLabel()));
                pending.liveTrack.setLocalOwner(false);
            }
        }
        notifyPeerListChanged();
    }

    private void clearOwner(@NonNull String remoteId) {
        ownerByRemoteId.remove(remoteId);
        leaseSeqByRemoteId.remove(remoteId);
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
            pending.ownershipActivationTimer.stop();
            pending.liveTrack.setLocalOwner(false);
        }
        notifyPeerListChanged();
    }

    private void onRelaySighting(@NonNull JSONObject jo) {
        String remoteId = jo.optString("remoteId");
        if (remoteId.isEmpty() || !isLocalOwner(remoteId)) return;
        String fromZoneId = firstNonEmpty(jo.optString("fromZoneId"), jo.optString("zoneId"));
        if (fromZoneId.equals(myGuid)) {
            CTDebug(TAG, "Ignoring self-relayed sighting for " + remoteId);
            return;
        }
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending == null) return;
        pending.liveTrack.onPeerWaypoint(
                fromZoneId,
                jo.optDouble("lat", 0.0),
                jo.optDouble("lng", 0.0),
                jo.optDouble("altM", 0.0),
                jo.optLong("droneTs", 0L),
                parseTelemetry(jo.optJSONObject("telemetry"))
        );
    }

    void handleOwnerAssignedForTesting(@NonNull String remoteId, @NonNull String ownerGuid, long leaseSeq) {
        applyOwnerAssignment(remoteId, ownerGuid, leaseSeq, 0L);
    }

    void handleOwnerExpiredForTesting(@NonNull String remoteId) {
        clearOwner(remoteId);
    }

    void handleRelaySightingForTesting(
            @NonNull String remoteId,
            @NonNull String fromZoneId,
            double lat,
            double lng,
            double altM,
            long droneTs,
            @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        if (!isLocalOwner(remoteId)) return;
        if (fromZoneId.equals(myGuid)) return;
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending == null) return;
        pending.liveTrack.onPeerWaypoint(fromZoneId, lat, lng, altM, droneTs, telemetry);
    }

    void markHeartbeatSentForTesting(long seq, long sentAtMs) {
        heartbeatSeqCounter = Math.max(heartbeatSeqCounter, seq);
        lastHeartbeatSeqSent = seq;
        lastHeartbeatSentAtMs = sentAtMs;
    }

    void handleHeartbeatAckForTesting(long ackSeq, long ownerLeaseExpireTs) {
        JSONObject jo = new JSONObject();
        try {
            jo.put("clientSeq", ackSeq);
            if (ownerLeaseExpireTs > 0L) {
                jo.put("ownerLeaseExpireTs", ownerLeaseExpireTs);
            }
        } catch (Exception ignored) {
        }
        onHeartbeatAck(jo);
    }

    void handleHelloAckForTesting() {
        helloAckAtMs = nowMs();
    }

    void checkAckLivenessForTesting() {
        checkAckLiveness();
    }

    @NonNull
    String getLastReconnectCauseForTesting() {
        return lastReconnectCause;
    }

    long getForcedReconnectCountForTesting() {
        return forcedReconnectCount;
    }

    @Nullable
    String getLastOutboundJsonForTesting() {
        return lastOutboundJsonForTesting;
    }

    @Nullable
    String getLastWaypointRemoteIdForTesting() {
        return lastWaypointRemoteIdForTesting;
    }

    long getLastHeartbeatSeqSentForTesting() {
        return lastHeartbeatSeqSent;
    }

    long getLastHeartbeatSeqAckedForTesting() {
        return lastHeartbeatSeqAcked;
    }

    private void notifyPeerListChanged() {
        R2CMqttManager.PeerListChangedListener listener = peerListChangedListener;
        if (listener == null) return;

        List<R2CMqttManager.PeerState> snapshot = new ArrayList<>(peers.values());
        if (myGuid != null && !myGuid.isEmpty()) {
            R2CMqttManager.PeerState self = new R2CMqttManager.PeerState(myGuid);
            self.name = myName != null && !myName.isEmpty() ? myName : myGuid;
            self.lat = myLat;
            self.lon = myLon;
            self.caltopoRttMs = myCaltopoRttMs;
            self.lastSeenMs = nowMs();
            self.online = started;
            snapshot.add(self);
        }
        for (R2CMqttManager.PeerState ps : snapshot) {
            ps.ownedDrones.clear();
        }
        for (PendingDrone pending : pendingDrones.values()) {
            String ownerGuid = ownerByRemoteId.get(pending.droneSpec.getRemoteId());
            if (ownerGuid == null) continue;
            R2CMqttManager.PeerState ps = null;
            for (R2CMqttManager.PeerState candidate : snapshot) {
                if (ownerGuid.equals(candidate.guid)) {
                    ps = candidate;
                    break;
                }
            }
            if (ps != null) {
                ps.ownedDrones.add(pending.droneSpec.copy());
            }
        }
        listener.onPeerListChanged(snapshot);
    }

    @NonNull
    private static String buildTrackerWebSocketUrl(@NonNull String trackerUrlPrefix) {
        String url = trackerUrlPrefix.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.startsWith("https://")) {
            url = "wss://" + url.substring("https://".length());
        } else if (url.startsWith("http://")) {
            url = "ws://" + url.substring("http://".length());
        }
        return url + "/ws/r2c";
    }

    @NonNull
    private static String describeToken(@Nullable String token) {
        if (token == null) return "<null>";
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return "<empty>";
        if (trimmed.length() <= 4) {
            return String.format(Locale.US, "len=%d suffix=%s", trimmed.length(), trimmed);
        }
        return String.format(Locale.US, "len=%d suffix=%s",
                trimmed.length(), trimmed.substring(trimmed.length() - 4));
    }

    @NonNull
    private static String normalizeTrackerToken(@Nullable String token) {
        return token == null ? "" : token.trim();
    }

    private static void putTelemetry(@NonNull JSONObject jo, @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        if (telemetry == null) return;
        JSONObject t = new JSONObject();
        try {
            if (telemetry.aircraftTrackDeg != null) t.put("headingDeg", telemetry.aircraftTrackDeg);
            if (telemetry.aircraftGsKnots != null) t.put("groundSpeedKnots", telemetry.aircraftGsKnots);
            if (telemetry.aircraftAltitudeRateFpm != null) t.put("verticalRateFpm", telemetry.aircraftAltitudeRateFpm);
            jo.put("telemetry", t);
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static CtDroneSpec.PositionTelemetry parseTelemetry(@Nullable JSONObject jo) {
        if (jo == null) return null;
        Double verticalRateFpm = jo.has("verticalRateFpm") ? jo.optDouble("verticalRateFpm") : null;
        Double groundSpeedKnots = jo.has("groundSpeedKnots") ? jo.optDouble("groundSpeedKnots") : null;
        Double headingDeg = jo.has("headingDeg") ? jo.optDouble("headingDeg") : null;
        return new CtDroneSpec.PositionTelemetry(verticalRateFpm, groundSpeedKnots, headingDeg);
    }

    @NonNull
    private static String firstNonEmpty(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isEmpty()) return first;
        return second != null ? second : "";
    }

    private long ageSince(long timestampMs) {
        if (timestampMs <= 0) return -1L;
        return nowMs() - timestampMs;
    }

    @NonNull
    private static String formatDuration(long durationMs) {
        long safeDurationMs = Math.max(durationMs, 0L);
        if (safeDurationMs < 1_000L) {
            return safeDurationMs + " ms";
        }
        long seconds = safeDurationMs / 1_000L;
        if (seconds < 60L) {
            return seconds + " sec";
        }
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        if (remainingSeconds == 0L) {
            return minutes + " min";
        }
        return minutes + " min " + remainingSeconds + " sec";
    }

    static void setTransportFactoryForTesting(@Nullable TrackerCoordinationTransportFactory factory) {
        transportFactory = factory != null ? factory : OkHttpTrackerCoordinationTransport::new;
    }

    static void setTrackerConfigForTesting(@Nullable String trackerUrlPrefix, @Nullable String trackerApiKey) {
        trackerUrlPrefixOverrideForTesting = trackerUrlPrefix;
        trackerApiKeyOverrideForTesting = trackerApiKey;
    }

    static void setHandoffDelayMsForTesting(long delayMs) {
        handoffDelayMs = Math.max(0L, delayMs);
    }

    static void setTimeSourceForTesting(@NonNull TimeSource override) {
        timeSource = override;
    }

    void setHardFailureListenerForTesting(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
    }

    void setHardFailureListener(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
    }

    private static long nowMs() {
        return timeSource.now();
    }

    static void resetForTesting() {
        INSTANCE.stop();
        INSTANCE.mapId = null;
        INSTANCE.myGuid = null;
        INSTANCE.myName = null;
        INSTANCE.trackerApiKey = null;
        INSTANCE.trackerWsUrl = null;
        INSTANCE.myLat = 0.0;
        INSTANCE.myLon = 0.0;
        INSTANCE.myCaltopoRttMs = 2_000L;
        INSTANCE.peerListChangedListener = null;
        INSTANCE.hardFailureListener = null;
        INSTANCE.hardFailureNotified = false;
        INSTANCE.forcedReconnectCount = 0L;
        INSTANCE.lastOutboundJsonForTesting = null;
        INSTANCE.lastWaypointRemoteIdForTesting = null;
        transportFactory = OkHttpTrackerCoordinationTransport::new;
        trackerUrlPrefixOverrideForTesting = null;
        trackerApiKeyOverrideForTesting = null;
        timeSource = System::currentTimeMillis;
        handoffDelayMs = DEFAULT_HANDOFF_DELAY_MS;
    }
}

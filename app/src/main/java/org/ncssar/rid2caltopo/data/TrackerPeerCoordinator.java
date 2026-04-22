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
    interface HardFailureListener {
        void onHardFailure(int responseCode, @Nullable String responseMessage);
    }

    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final long CONNECT_GRACE_MS = 12_000L;
    private static final long RECONNECT_DELAY_MS = 5_000L;

    private static volatile TrackerCoordinationTransportFactory transportFactory =
            OkHttpTrackerCoordinationTransport::new;
    @Nullable private static volatile String trackerApiKeyOverrideForTesting;
    @Nullable private static volatile String trackerUrlPrefixOverrideForTesting;

    private static final class PendingDrone {
        @NonNull final LiveTrackOwnerDelegate liveTrack;
        @NonNull final CtDroneSpec droneSpec;
        final double distMeters;
        final long firstSeenTs;
        @NonNull final DelayedExec fallbackTimer = new DelayedExec();
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
    @NonNull private final DelayedExec heartbeatTimer = new DelayedExec();
    @NonNull private final DelayedExec reconnectTimer = new DelayedExec();

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
                sendHello();
                heartbeatTimer.start(TrackerPeerCoordinator.this::sendHeartbeat, 0L, HEARTBEAT_INTERVAL_MS);
                replayPendingFirstSightings();
                notifyCoordinationIndicatorListener();
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed() {
                CTWarn(TAG, "tracker websocket closed");
                heartbeatTimer.stop();
                notifyCoordinationIndicatorListener();
                scheduleReconnect();
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
                heartbeatTimer.stop();
                notifyCoordinationIndicatorListener();
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect();
            }
        });
        transport.connect(this.trackerWsUrl, trackerApiKey);
        notifyCoordinationIndicatorListener();
    }

    @Override
    public synchronized void stop() {
        heartbeatTimer.stop();
        reconnectTimer.stop();
        Iterator<Map.Entry<String, PendingDrone>> iterator = pendingDrones.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PendingDrone> entry = iterator.next();
            entry.getValue().fallbackTimer.stop();
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
        notifyCoordinationIndicatorListener();
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
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
        if (pending != null) {
            pending.fallbackTimer.stop();
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
    public void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        if (started && isConnected()) {
            sendHeartbeat();
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
        if (!started || trackerWsUrl == null || trackerApiKey == null) return;
        reconnectTimer.start(this::reconnect, RECONNECT_DELAY_MS, 0L);
    }

    private synchronized void reconnect() {
        if (!started || trackerWsUrl == null || trackerApiKey == null) return;
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
                sendHello();
                heartbeatTimer.start(TrackerPeerCoordinator.this::sendHeartbeat, 0L, HEARTBEAT_INTERVAL_MS);
                replayPendingFirstSightings();
            }

            @Override
            public void onMessage(@NonNull String text) {
                handleIncomingMessage(text);
            }

            @Override
            public void onClosed() {
                heartbeatTimer.stop();
                scheduleReconnect();
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
                notifyHardFailureIfNeeded(responseCode, responseMessage);
                scheduleReconnect();
            }
        });
        activeTransport.connect(trackerWsUrl, trackerApiKey);
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
                applyOwnerAssignment(pending.droneSpec.getRemoteId(), myGuid, -1L);
            }
        }, CONNECT_GRACE_MS, 0L);
    }

    private void replayPendingFirstSightings() {
        for (PendingDrone pending : pendingDrones.values()) {
            sendFirstSighting(pending);
        }
    }

    private void sendHello() {
        JSONObject jo = new JSONObject();
        try {
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
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHello() raised", e);
        }
    }

    private void sendHeartbeat() {
        JSONObject jo = new JSONObject();
        try {
            jo.put("type", "heartbeat");
            jo.put("mapId", mapId);
            jo.put("zoneId", myGuid);
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("lat", myLat);
            jo.put("lng", myLon);
            jo.put("caltopoRttMs", myCaltopoRttMs);
            sendJson(jo);
        } catch (Exception e) {
            CTError(TAG, "sendHeartbeat() raised", e);
        }
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
            sendJson(jo);
            pending.firstSightingSent = true;
        } catch (Exception e) {
            CTError(TAG, "sendFirstSighting() raised", e);
        }
    }

    private void sendJson(@NonNull JSONObject jo) {
        TrackerCoordinationTransport activeTransport = transport;
        if (activeTransport == null || !activeTransport.isConnected()) return;
        activeTransport.send(jo.toString());
    }

    private void handleIncomingMessage(@NonNull String text) {
        try {
            JSONObject jo = new JSONObject(text);
            String type = jo.optString("type");
            switch (type) {
                case "hello_ack":
                    CTInfo(TAG, "hello_ack received from tracker");
                    break;
                case "zone_update":
                    onZoneUpdate(jo.optJSONArray("zones"));
                    break;
                case "owner_assigned":
                    applyOwnerAssignment(
                            jo.optString("remoteId"),
                            firstNonEmpty(jo.optString("ownerGuid"), jo.optString("ownerZoneId")),
                            jo.optLong("leaseSeq", -1L));
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
                ps.lastSeenMs = zone.optLong("lastSeenMs", System.currentTimeMillis());
                ps.online = zone.optBoolean("online", true);
                peers.put(zoneGuid, ps);
            }
        }
        notifyPeerListChanged();
    }

    private void applyOwnerAssignment(@NonNull String remoteId, @Nullable String ownerGuid, long leaseSeq) {
        if (remoteId.isEmpty() || ownerGuid == null || ownerGuid.isEmpty()) return;
        ownerByRemoteId.put(remoteId, ownerGuid);
        if (leaseSeq >= 0) {
            leaseSeqByRemoteId.put(remoteId, leaseSeq);
        }
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
            pending.fallbackTimer.stop();
            pending.liveTrack.setLocalOwner(ownerGuid.equals(myGuid));
        }
        notifyPeerListChanged();
    }

    private void clearOwner(@NonNull String remoteId) {
        ownerByRemoteId.remove(remoteId);
        leaseSeqByRemoteId.remove(remoteId);
        PendingDrone pending = pendingDrones.get(remoteId);
        if (pending != null) {
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
        applyOwnerAssignment(remoteId, ownerGuid, leaseSeq);
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
            self.lastSeenMs = System.currentTimeMillis();
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

    static void setTransportFactoryForTesting(@Nullable TrackerCoordinationTransportFactory factory) {
        transportFactory = factory != null ? factory : OkHttpTrackerCoordinationTransport::new;
    }

    static void setTrackerConfigForTesting(@Nullable String trackerUrlPrefix, @Nullable String trackerApiKey) {
        trackerUrlPrefixOverrideForTesting = trackerUrlPrefix;
        trackerApiKeyOverrideForTesting = trackerApiKey;
    }

    void setHardFailureListenerForTesting(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
    }

    void setHardFailureListener(@Nullable HardFailureListener listener) {
        hardFailureListener = listener;
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
        transportFactory = OkHttpTrackerCoordinationTransport::new;
        trackerUrlPrefixOverrideForTesting = null;
        trackerApiKeyOverrideForTesting = null;
    }
}

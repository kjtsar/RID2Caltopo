/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data;

import static org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTError;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTInfo;
import static org.ncssar.rid2caltopo.data.CaltopoClient.CTWarn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.ncssar.rid2caltopo.app.R2CApplication;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R2CMqttManager coordinates ownership of drone live tracks across multiple
 * R2C instances connected to the same CalTopo incident map.
 *
 * Each R2C instance connects to a shared MQTT broker using the CalTopo map ID
 * as the topic namespace — no extra configuration required.
 *
 * Topic structure:
 *   R2C/<mapId>/peer/<guid>                  retained, QoS 1, LWT
 *   R2C/<mapId>/drone/<remoteId>/detect/<guid>  non-retained, QoS 0
 *   R2C/<mapId>/drone/<remoteId>/owner       retained, QoS 1
 *
 * Ownership algorithm (runs identically on every instance):
 *   score = 0.75 * proximity_score + 0.25 * caltopo_rtt_score
 *   proximity_score  = 1 / (1 + dist_meters / DIST_SCALE_M)
 *   caltopo_rtt_score = 1 / (1 + ct_rtt_ms  / RTT_SCALE_MS)
 *   Tiebreaker: earliest first-seen timestamp, then lex-smallest GUID.
 *
 * Handoff: when ownership changes TO this instance, we wait HANDOFF_DELAY_MS
 * before starting CalTopo publication.  When ownership changes AWAY, we stop
 * immediately.  This produces a brief gap (at most 1-2 waypoints) rather than
 * overlap, protecting the 3 000-waypoint CalTopo limit.
 */
public class R2CMqttManager {

    private static final String TAG = "R2CMqttManager";

    // ── tuneable constants ────────────────────────────────────────────────────
    public  static final String DEFAULT_BROKER_URI     = "tcp://broker.hivemq.com:1883";
    private static final long   HEARTBEAT_INTERVAL_MS  = 10_000;
    private static final long   MQTT_CONNECT_GRACE_MS  = 3_000;  // max wait for broker before immediate-claim fallback
    private static final long   DETECT_INTERVAL_MS     = 4_000;
    private static final long   OBSERVATION_EXPIRY_MS  = 15_000;
    private static final long   OWNERSHIP_CHECK_MS     = 10_000;
    private static       long   HANDOFF_DELAY_MS       = 2_000;
    private static final long   DISCOVERY_WINDOW_MS    = 1_000;
    private static final double DIST_SCALE_M           = 200.0;
    private static final double RTT_SCALE_MS           = 1_000.0;
    private static final double WEIGHT_PROXIMITY       = 0.75;
    private static final double WEIGHT_RTT             = 0.25;
    // ─────────────────────────────────────────────────────────────────────────

    // ── public peer state (used by UI / ViewModel) ───────────────────────────
    public static class PeerState {
        public final String guid;
        public volatile String  name         = "";
        public volatile double  lat          = 0;
        public volatile double  lon          = 0;
        public volatile long    caltopoRttMs = 2_000; // pessimistic default
        public volatile boolean online       = false;
        public volatile long    lastSeenMs   = 0;
        /** Uptime timer started when we first hear from this peer. */
        public final SimpleTimer uptime = new SimpleTimer();
        /** Drones currently owned by this peer (for UI display). */
        public final List<CtDroneSpec> ownedDrones = Collections.synchronizedList(new ArrayList<>());

        PeerState(String guid) { this.guid = guid; }
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ── internal observation / ownership state ───────────────────────────────
    private static class DroneObservation implements PeerOwnershipEngine.ObservationView {
        final String observerGuid;
        volatile double droneLat, droneLon, droneAlt;
        volatile double distMeters;
        volatile long   ts;
        volatile long   firstSeenTs;

        DroneObservation(String guid, double lat, double lon, double alt,
                         double dist, long ts) {
            this.observerGuid = guid;
            this.droneLat = lat; this.droneLon = lon; this.droneAlt = alt;
            this.distMeters = dist;
            this.ts = this.firstSeenTs = ts;
        }

        @NonNull
        @Override
        public String getObserverGuid() {
            return observerGuid;
        }

        @Override
        public double getDistMeters() {
            return distMeters;
        }

        @Override
        public long getFirstSeenTs() {
            return firstSeenTs;
        }

        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }

        @Override
        public boolean isExpired(long nowMs) {
            return nowMs - ts > OBSERVATION_EXPIRY_MS;
        }
    }

    private static class DroneState {
        final String remoteId;
        final ConcurrentHashMap<String, DroneObservation> obs = new ConcurrentHashMap<>();
        volatile String ownerGuid           = null;
        volatile long   ownerChangedMs      = 0;
        volatile boolean localOwnerActive   = false;
        volatile long   lastEvalMs          = 0;

        DroneState(String rid) { this.remoteId = rid; }
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ── listener interfaces ───────────────────────────────────────────────────
    public interface PeerListChangedListener {
        void onPeerListChanged(@NonNull List<PeerState> peerList);
    }
    // ─────────────────────────────────────────────────────────────────────────

    // ── static state ─────────────────────────────────────────────────────────
    private static PeerTransport peerTransport;
    @Nullable private static PeerTransportFactory peerTransportFactory;
    private static String myGuid   = "";
    private static String myName   = "";
    private static String mapId    = "";
    private static String brokerUri = DEFAULT_BROKER_URI;

    private static volatile double myLat = 0, myLon = 0;
    private static volatile long   myCaltopoRttMs = 2_000;

    private static final ConcurrentHashMap<String, PeerState>   peers      = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, DroneState>  drones     = new ConcurrentHashMap<>();

    private static final DelayedExec heartbeatTimer      = new DelayedExec();
    private static final DelayedExec ownershipCheckTimer = new DelayedExec();
    private static final PeerOwnershipEngine ownershipEngine = new PeerOwnershipEngine(
            DIST_SCALE_M, RTT_SCALE_MS, WEIGHT_PROXIMITY, WEIGHT_RTT);
    private static final Object mqttLogLock = new Object();
    private static final long MQTT_LOG_SUPPRESS_WINDOW_MS = 30_000L;
    private static volatile boolean mqttConnected = false;
    @Nullable private static volatile String lastMqttDisconnectDetail = null;
    private static volatile long lastMqttDisconnectLogMs = 0L;
    private static volatile int suppressedMqttDisconnects = 0;

    private static volatile PeerListChangedListener peerListChangedListener;
    @Nullable private static volatile PeerCoordinator.CoordinationIndicatorListener coordinationIndicatorListener;
    private static volatile boolean initialized = false;
    @Nullable private static volatile String subscribedTopicFilter = null; // set on successful subscribe

    // network address monitoring (moved from R2CPeer)
    private static final Object AddrLock = new Object();
    private static volatile List<String> CurrentIpAddrs = Collections.emptyList();
    private static volatile boolean addrMonitorStarted  = false;
    private static ConnectivityManager.NetworkCallback addrCallback;
    // ─────────────────────────────────────────────────────────────────────────

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Connect to the MQTT broker and begin coordinating with peers.
     * Safe to call when the map is first opened.  No-op if already initialized.
     *
     * @param mapIdIn    CalTopo map ID — used as the topic namespace.
     * @param guidIn     This device's persistent UUID.
     * @param nameIn     Human-readable device name shown in the peer list.
     * @param brokerUriIn  Optional override; pass null for the default public broker.
     */
    public static synchronized void init(
            @NonNull String mapIdIn,
            @NonNull String guidIn,
            @NonNull String nameIn,
            @Nullable String brokerUriIn) {

        if (initialized) shutdown();

        mapId    = mapIdIn;
        myGuid   = guidIn;
        myName   = nameIn;
        brokerUri = (brokerUriIn != null && !brokerUriIn.isEmpty()) ? brokerUriIn : DEFAULT_BROKER_URI;

        CTDebug(TAG, String.format(Locale.US,
                "init(): mapId=%s guid=%s broker=%s", mapId, myGuid, brokerUri));

        try {
            String clientId = "r2c-" + myGuid;
            peerTransport = createPeerTransport(brokerUri, clientId);
            peerTransport.setCallback(new PeerTransportCallback() {
                @Override public void onConnected(boolean reconnect, @NonNull String serverURI) {
                    noteMqttConnected(reconnect, serverURI);
                    CTInfo(TAG, "connectComplete() reconnect=" + reconnect);
                    onMqttConnected();
                }

                @Override public void onConnectionLost(@Nullable Throwable cause) {
                    String detail = cause != null
                            ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                            : "unknown";
                    noteMqttConnectionLost(detail);
                }

                @Override public void onMessageArrived(@NonNull String topic, @NonNull byte[] payload) {
                    R2CMqttManager.onMessageArrived(topic, payload);
                }
            });
            byte[] lwtPayload = buildPeerPayload(false);
            peerTransport.connect(
                    peerTopic(myGuid),
                    lwtPayload,
                    1,
                    true,
                    () -> CTInfo(TAG, "MQTT connect succeeded."),
                    () -> CTError(TAG, "MQTT connect failed: unknown")
            );
            initialized = true;

        } catch (MqttException e) {
            CTError(TAG, "init(): PeerTransport creation failed.", e);
        }
    }

    /** Gracefully disconnect: clear retained messages, stop timers, close socket. */
    public static synchronized void shutdown() {
        if (!initialized) return;
        CTInfo(TAG, "shutdown()");
        initialized = false;

        heartbeatTimer.stop();
        ownershipCheckTimer.stop();

        if (peerTransport != null && peerTransport.isConnected()) {
            try {
                // Clear our retained peer presence
                publishRetained(peerTopic(myGuid), buildPeerPayload(false));
                // Clear retained owner claims for drones we own
                for (Map.Entry<String, DroneState> e : drones.entrySet()) {
                    if (myGuid.equals(e.getValue().ownerGuid)) {
                        clearRetained(ownerTopic(e.getKey()));
                    }
                }
                peerTransport.disconnect();
            } catch (Exception ex) {
                CTError(TAG, "shutdown(): disconnect raised.", ex);
            }
        }
        peers.clear();
        drones.clear();
        peerTransport = null;
        subscribedTopicFilter = null;
        resetMqttDisconnectLogging();
        notifyCoordinationIndicatorListener();
    }

    private static void noteMqttConnected(boolean reconnect, @NonNull String serverURI) {
        synchronized (mqttLogLock) {
            if (suppressedMqttDisconnects > 0) {
                CTInfo(TAG, String.format(Locale.US,
                        "MQTT reconnect complete after %d suppressed disconnect event(s). reconnect=%s server=%s",
                        suppressedMqttDisconnects, reconnect, serverURI));
            }
            mqttConnected = true;
            lastMqttDisconnectDetail = null;
            lastMqttDisconnectLogMs = 0L;
            suppressedMqttDisconnects = 0;
        }
        notifyCoordinationIndicatorListener();
    }

    private static void noteMqttConnectionLost(@NonNull String detail) {
        synchronized (mqttLogLock) {
            long now = System.currentTimeMillis();
            boolean detailChanged = !detail.equals(lastMqttDisconnectDetail);
            boolean stale = now - lastMqttDisconnectLogMs >= MQTT_LOG_SUPPRESS_WINDOW_MS;

            if (mqttConnected || detailChanged || stale) {
                String suffix = suppressedMqttDisconnects > 0
                        ? String.format(Locale.US, " (suppressed %d similar event(s))", suppressedMqttDisconnects)
                        : "";
                CTWarn(TAG, "connectionLost(): " + detail + suffix);
                mqttConnected = false;
                lastMqttDisconnectDetail = detail;
                lastMqttDisconnectLogMs = now;
                suppressedMqttDisconnects = 0;
            } else {
                suppressedMqttDisconnects++;
            }
        }
        notifyCoordinationIndicatorListener();
    }

    private static void resetMqttDisconnectLogging() {
        synchronized (mqttLogLock) {
            mqttConnected = false;
            lastMqttDisconnectDetail = null;
            lastMqttDisconnectLogMs = 0L;
            suppressedMqttDisconnects = 0;
        }
        notifyCoordinationIndicatorListener();
    }

    private static void notifyCoordinationIndicatorListener() {
        PeerCoordinator.CoordinationIndicatorListener listener = coordinationIndicatorListener;
        if (listener == null) return;
        PeerCoordinator.CoordinationIndicatorState state;
        if (!CaltopoClient.GetUsePeersFlag()) {
            state = PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED;
        } else if (isConnected()) {
            state = PeerCoordinator.CoordinationIndicatorState.HEALTHY;
        } else {
            state = PeerCoordinator.CoordinationIndicatorState.DEGRADED;
        }
        listener.onCoordinationIndicatorStateChanged(state);
    }

    // ── called on successful MQTT connect ─────────────────────────────────────

    private static void onMqttConnected() {
        try {
            // Subscribe to everything under our map's namespace
            String rootFilter = "R2C/" + mapId + "/#";
            peerTransport.subscribe(rootFilter, 1,
                () -> {
                    CTInfo(TAG, "Subscribed to " + rootFilter);
                    subscribedTopicFilter = rootFilter;
                    // Publish our own presence immediately so peers discover us
                    publishHeartbeat();
                    // Start periodic heartbeat
                    heartbeatTimer.start(R2CMqttManager::publishHeartbeat,
                            HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS);
                    // Start periodic ownership checks
                    ownershipCheckTimer.start(R2CMqttManager::checkAllOwnerships,
                            OWNERSHIP_CHECK_MS, OWNERSHIP_CHECK_MS);
                },
                () -> CTError(TAG, "subscribe() failed: ?")
            );
        } catch (Exception e) {
            CTError(TAG, "onMqttConnected(): subscribe raised.", e);
        }
    }

    // ── message dispatch ──────────────────────────────────────────────────────

    private static void onMessageArrived(@NonNull String topic, @NonNull byte[] payload) {
        try {
            // Topic structure: R2C/<mapId>/<category>/...
            String[] parts = topic.split("/");
            if (parts.length < 4) return;
            // parts[0]="R2C"  parts[1]=mapId  parts[2]=category
            String category = parts[2];

            switch (category) {
                case "peer": {
                    // R2C/<mapId>/peer/<guid>
                    if (parts.length < 4) return;
                    String senderGuid = parts[3];
                    if (myGuid.equals(senderGuid)) return; // echo of our own heartbeat
                    if (payload.length == 0) {
                        // Empty retained payload = explicit delete
                        peers.remove(senderGuid);
                        notifyPeerListChanged();
                        return;
                    }
                    onPeerHeartbeat(senderGuid, new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                    break;
                }
                case "drone": {
                    // R2C/<mapId>/drone/<remoteId>/<subtype>[/<guid>]
                    if (parts.length < 5) return;
                    String remoteId = parts[3];
                    String subtype  = parts[4];
                    switch (subtype) {
                        case "detect": {
                            if (parts.length < 6) return;
                            String senderGuid = parts[5];
                            if (myGuid.equals(senderGuid)) return;
                            if (payload.length == 0) return;
                            onDroneDetect(remoteId, senderGuid,
                                    new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                            break;
                        }
                        case "owner": {
                            if (payload.length == 0) {
                                // Owner claim cleared
                                DroneState ds = drones.get(remoteId);
                                if (ds != null) ds.ownerGuid = null;
                                return;
                            }
                            onDroneOwner(remoteId,
                                    new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                            break;
                        }
                        case "spec": {
                            if (payload.length == 0) return; // clear is a no-op for spec
                            onDroneSpec(remoteId,
                                    new JSONObject(new String(payload, StandardCharsets.UTF_8)));
                            break;
                        }
                    }
                    break;
                }
            }
        } catch (Exception e) {
            CTError(TAG, "onMessageArrived(): parse error for topic: " + topic, e);
        }
    }

    // ── incoming message handlers ─────────────────────────────────────────────

    private static void onPeerHeartbeat(@NonNull String senderGuid, @NonNull JSONObject jo) {
        PeerState ps = peers.get(senderGuid);
        boolean isNew = (ps == null);
        if (isNew) {
            ps = new PeerState(senderGuid);
            ps.uptime.restartTimer();
            peers.put(senderGuid, ps);
        }

        double prevLat = ps.lat, prevLon = ps.lon;

        ps.name          = jo.optString("name", senderGuid);
        ps.lat           = jo.optDouble("lat",  0);
        ps.lon           = jo.optDouble("lon",  0);
        ps.caltopoRttMs  = jo.optLong  ("ctRtt", 2_000);
        ps.online        = jo.optBoolean("online", true);
        ps.lastSeenMs    = System.currentTimeMillis();

        if (!ps.online) {
            // Peer went offline
            CTInfo(TAG, "Peer went offline: " + ps.name);
            // Any drones it owned should be re-evaluated
            for (DroneState ds : drones.values()) {
                if (senderGuid.equals(ds.ownerGuid)) {
                    scheduleOwnershipCheck(ds.remoteId, 500);
                }
            }
            peers.remove(senderGuid);
        } else if (isNew) {
            CTInfo(TAG, "New peer discovered: " + ps.name);
            // Re-evaluate all drones with fresh competitor
            for (DroneState ds : drones.values()) {
                scheduleOwnershipCheck(ds.remoteId, DISCOVERY_WINDOW_MS);
            }
        } else {
            // Existing peer; re-evaluate drones if peer moved significantly (>100 m)
            double movedM = distanceInMeters(prevLat, prevLon, ps.lat, ps.lon);
            if (movedM > 100) {
                for (DroneState ds : drones.values()) {
                    scheduleOwnershipCheck(ds.remoteId, 500);
                }
            }
        }
        notifyPeerListChanged();
    }

    private static void onDroneDetect(@NonNull String remoteId,
                                       @NonNull String senderGuid,
                                       @NonNull JSONObject jo) {
        DroneState ds = getOrCreateDroneState(remoteId);
        long now = System.currentTimeMillis();

        DroneObservation existing = ds.obs.get(senderGuid);
        if (existing == null) {
            existing = new DroneObservation(senderGuid,
                    jo.optDouble("dLat"), jo.optDouble("dLon"), jo.optDouble("dAlt"),
                    jo.optDouble("dist"), now);
            existing.firstSeenTs = jo.optLong("fts", now);
            ds.obs.put(senderGuid, existing);
        } else {
            existing.droneLat  = jo.optDouble("dLat");
            existing.droneLon  = jo.optDouble("dLon");
            existing.droneAlt  = jo.optDouble("dAlt");
            existing.distMeters= jo.optDouble("dist");
            existing.ts        = now;
            // firstSeenTs never moves backward
            long reportedFts = jo.optLong("fts", now);
            if (reportedFts < existing.firstSeenTs) existing.firstSeenTs = reportedFts;
        }

        // Debounce: don't re-evaluate more than once per 2 s per drone
        if (now - ds.lastEvalMs > 2_000) {
            checkOwnership(remoteId);
        }
    }

    private static void onDroneOwner(@NonNull String remoteId, @NonNull JSONObject jo) {
        String claimedOwner = jo.optString("guid", "");
        if (claimedOwner.isEmpty()) return;

        DroneState ds = getOrCreateDroneState(remoteId);
        String prevOwner = ds.ownerGuid;

        if (!claimedOwner.equals(prevOwner)) {
            CTDebug(TAG, String.format(Locale.US,
                    "onDroneOwner(%s): ownership transferred %s → %s",
                    remoteId, prevOwner, claimedOwner));
            ds.ownerGuid = claimedOwner;
            ds.ownerChangedMs = System.currentTimeMillis();
            applyOwnershipChange(remoteId, prevOwner, claimedOwner);
        }
    }

    /**
     * Handles an incoming retained drone-spec message from a peer.
     * Delegates to {@link CaltopoClient#ApplyRemoteDroneSpec} which updates the active spec
     * in place (if the drone is already being tracked) or pre-populates the persistent cache
     * (so the correct mappedId is used as soon as the first local waypoint arrives).
     */
    private static void onDroneSpec(@NonNull String remoteId, @NonNull JSONObject jo) {
        String newMappedId = jo.optString("mid",   "");
        String newOrg      = jo.optString("org",   "");
        String newModel    = jo.optString("model", "");
        String newOwner    = jo.optString("owner", "");
        if (newMappedId.isEmpty()) return;
        CTInfo(TAG, String.format(Locale.US,
                "onDroneSpec(%s): peer update mid=%s org=%s model=%s owner=%s",
                remoteId, newMappedId, newOrg, newModel, newOwner));
        CaltopoClient.ApplyRemoteDroneSpec(remoteId, newMappedId, newOrg, newModel, newOwner);
    }

    // ── ownership computation ─────────────────────────────────────────────────

    /** Deterministic owner selection from current observations. */
    @Nullable
    private static String computeOwnerGuid(@NonNull DroneState ds) {
        PeerOwnershipEngine.ScoreResult result = ownershipEngine.selectOwner(
                ds.obs.values(),
                observerGuid -> {
                    if (observerGuid.equals(myGuid)) return myCaltopoRttMs;
                    PeerState peer = peers.get(observerGuid);
                    return (peer != null) ? peer.caltopoRttMs : 2_000L;
                },
                System.currentTimeMillis()
        );
        return result.ownerGuid;
    }

    private static void checkOwnership(@NonNull String remoteId) {
        DroneState ds = drones.get(remoteId);
        if (ds == null) return;
        ds.lastEvalMs = System.currentTimeMillis();

        String newOwner = computeOwnerGuid(ds);
        String prevOwner = ds.ownerGuid;

        if (newOwner == null) {
            // No non-expired observations available — this happens immediately after a reconnect
            // before any detect messages have arrived.  The retained owner message from the broker
            // may already have set ds.ownerGuid correctly; don't overwrite it with null just
            // because ds.obs is temporarily empty.  Ownership is cleared through explicit paths:
            // onDroneLost(), an empty retained owner message, or a peer going offline.
            // Logged at verbose level only — this is the steady-state after a drone lands.
            return;
        }

        // Guard: if we're about to claim self-ownership but the current retained owner is a
        // different peer AND we have no observations from any peer yet (e.g. right after reconnect
        // when QoS-0 detect messages haven't re-arrived), hold off.  Acting on incomplete
        // information here causes ownership oscillation: we'd override the peer's retained claim,
        // the peer would counter-claim, and both devices would oscillate every reconnect cycle.
        if (myGuid.equals(newOwner) && prevOwner != null && !myGuid.equals(prevOwner)) {
            long nowMs = System.currentTimeMillis();
            boolean hasPeerObs = false;
            for (DroneObservation obs : ds.obs.values()) {
                if (!myGuid.equals(obs.observerGuid) && !obs.isExpired(nowMs)) {
                    hasPeerObs = true;
                    break;
                }
            }
            if (!hasPeerObs) {
                CTDebug(TAG, String.format(Locale.US,
                        "checkOwnership(%s): no peer observations yet — not contesting peer owner %s",
                        remoteId, prevOwner));
                return;
            }
        }

        if (!objectsEqual(newOwner, prevOwner)) {
            CTDebug(TAG, String.format(Locale.US,
                    "checkOwnership(%s): %s → %s", remoteId, prevOwner, newOwner));
            ds.ownerGuid = newOwner;
            ds.ownerChangedMs = System.currentTimeMillis();

            // Only the new owner publishes the retained owner claim
            if (myGuid.equals(newOwner)) {
                publishOwnerClaim(remoteId);
            }
            applyOwnershipChange(remoteId, prevOwner, newOwner);
        }
    }

    private static void checkAllOwnerships() {
        for (String rid : drones.keySet()) {
            checkOwnership(rid);
        }
    }

    /**
     * React to an ownership change for a drone.
     * - If we became the owner: schedule start of CalTopo publishing after handoff delay.
     * - If we lost ownership: stop CalTopo publishing immediately.
     */
    private static void applyOwnershipChange(@NonNull String remoteId,
                                              @Nullable String prevOwner,
                                              @Nullable String newOwner) {
        boolean wasMe = myGuid.equals(prevOwner);
        boolean isMe  = myGuid.equals(newOwner);

        if (wasMe && !isMe) {
            // Lost ownership — stop immediately
            CTInfo(TAG, "Lost ownership of " + remoteId);
            setLocalOwnership(remoteId, false);
        } else if (!wasMe && isMe) {
            // Won ownership — wait handoff delay then start
            CTInfo(TAG, "Gained ownership of " + remoteId + "; publishing in " + HANDOFF_DELAY_MS + " ms");
            DroneState ds = drones.get(remoteId);
            if (ds != null) ds.localOwnerActive = false;
            DelayedExec.RunAfterDelayInMsec(() -> {
                DroneState current = drones.get(remoteId);
                if (current != null && myGuid.equals(current.ownerGuid)) {
                    setLocalOwnership(remoteId, true);
                }
            }, HANDOFF_DELAY_MS);
        }
    }

    private static void setLocalOwnership(@NonNull String remoteId, boolean isOwner) {
        DroneState ds = drones.get(remoteId);
        if (ds != null) ds.localOwnerActive = isOwner;

        CaltopoLiveTrack liveTrack = CaltopoLiveTrack.GetLiveTrackForRemoteId(remoteId);
        if (liveTrack != null) {
            liveTrack.setLocalOwner(isOwner);
        }
    }

    // ── public API for CaltopoLiveTrack ───────────────────────────────────────

    /**
     * Called when CaltopoLiveTrack has its first waypoint for a drone.
     * Kicks off MQTT-based ownership determination.
     * After DISCOVERY_WINDOW_MS, ownership is evaluated and the livetrack is
     * notified via {@link CaltopoLiveTrack#setLocalOwner}.
     */
    public static void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                           @NonNull CtDroneSpec droneSpec,
                                           double distMeters,
                                           long firstSeenTs) {
        if (droneSpec.isLocalArchiveOnly()) {
            liveTrack.setLocalOwner(false);
            return;
        }
        onLiveTrackCreatedImpl(liveTrack, droneSpec, distMeters, firstSeenTs, false);
    }

    private static void onLiveTrackCreatedImpl(@NonNull LiveTrackOwnerDelegate liveTrack,
                                               @NonNull CtDroneSpec droneSpec,
                                               double distMeters,
                                               long firstSeenTs,
                                               boolean isRetry) {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) {
            // No MQTT peer connection — take ownership immediately.
            // If the user has "Use Peers" enabled but we still end up here it means either
            // (a) MQTT was never configured (no broker credentials loaded), or
            // (b) the broker connection hasn't been established yet.
            // In either case (b), defer once so that a briefly-slow broker connection does not
            // cause both devices to claim ownership simultaneously.
            String remoteIdForLog = droneSpec.getRemoteId();
            if (initialized && peerTransport != null) {
                if (!isRetry && droneSpec.isActive()) {
                    CTDebug(TAG, "onLiveTrackCreated(" + remoteIdForLog + "): " +
                            "MQTT not connected — deferring " + MQTT_CONNECT_GRACE_MS +
                            " ms to allow broker connection before falling back to immediate-claim.");
                    DelayedExec.RunAfterDelayInMsec(
                            () -> onLiveTrackCreatedImpl(liveTrack, droneSpec, distMeters, firstSeenTs, true),
                            MQTT_CONNECT_GRACE_MS);
                    return;
                }
                CTWarn(TAG, "onLiveTrackCreated(" + remoteIdForLog + "): " +
                        "MQTT configured but not connected — claiming ownership immediately. " +
                        "Check broker reachability; peer ownership arbitration is unavailable.");
            } else {
                CTDebug(TAG, "onLiveTrackCreated(" + remoteIdForLog + "): " +
                        "MQTT not initialized — claiming ownership immediately (no-peers mode).");
            }
            liveTrack.setLocalOwner(true);
            return;
        }

        String remoteId = droneSpec.getRemoteId();
        DroneState ds = getOrCreateDroneState(remoteId);

        // Register our own observation
        DroneObservation myObs = ds.obs.get(myGuid);
        if (myObs == null) {
            myObs = new DroneObservation(myGuid,
                    droneSpec.lastLat, droneSpec.lastLng, droneSpec.lastAlt,
                    distMeters, firstSeenTs);
            ds.obs.put(myGuid, myObs);
        }

        // Publish a detect message so peers know we see this drone
        publishDetect(remoteId, droneSpec, distMeters, firstSeenTs);

        // Wait for retained messages to arrive, then evaluate
        DelayedExec.RunAfterDelayInMsec(() -> checkOwnership(remoteId), DISCOVERY_WINDOW_MS);
    }

    /**
     * Called each time a new waypoint arrives for a drone we're currently tracking.
     * Updates our observation and optionally re-publishes the detect message.
     */
    public static void onWaypointReceived(@NonNull String remoteId,
                                           double droneLat, double droneLon, double droneAlt,
                                           double distMeters) {
        if (!initialized) return;
        CtDroneSpec activeSpec = CaltopoClient.GetDroneSpec(remoteId);
        if (activeSpec != null && activeSpec.isLocalArchiveOnly()) return;

        DroneState ds = drones.get(remoteId);
        if (ds == null) return;

        DroneObservation myObs = ds.obs.get(myGuid);
        long now = System.currentTimeMillis();
        if (myObs != null) {
            myObs.droneLat  = droneLat;
            myObs.droneLon  = droneLon;
            myObs.droneAlt  = droneAlt;
            myObs.distMeters= distMeters;
            myObs.ts        = now;

            // Throttle detect publications to DETECT_INTERVAL_MS
            if (now - (myObs.ts - 1) > DETECT_INTERVAL_MS) {
                CtDroneSpec spec = CaltopoClient.GetDroneSpec(remoteId);
                if (spec != null) publishDetect(remoteId, spec, distMeters, myObs.firstSeenTs);
            }
        }
    }

    /**
     * Called when a drone goes inactive (no more waypoints).
     * Clears our detect publication so competitors know we've lost signal.
     */
    public static void onDroneLost(@NonNull String remoteId) {
        if (!initialized) return;

        DroneState ds = drones.get(remoteId);
        if (ds == null) return;
        ds.obs.remove(myGuid);

        if (myGuid.equals(ds.ownerGuid)) {
            // Release ownership — clear retained owner claim
            clearRetained(ownerTopic(remoteId));
            ds.ownerGuid = null;
        }
        drones.remove(remoteId);
    }

    /** Returns true if this instance currently owns the drone. */
    public static boolean isLocalOwner(@NonNull String remoteId) {
        DroneState ds = drones.get(remoteId);
        return ds != null && ds.localOwnerActive;
    }

    /**
     * Called by {@link CaltopoClient} whenever a drone spec field (mappedId, org, model, owner)
     * changes locally — e.g. the user selects a drone in the confirmation dialog or edits it.
     * Publishes the updated spec as a retained MQTT message so all peers on the same map
     * immediately receive and apply the new label.
     */
    public static void onDroneSpecChanged(@NonNull String remoteId) {
        publishDroneSpec(remoteId);
    }

    /** Called by CaltopoLiveTrack when it measures a new CalTopo RTT. */
    public static void updateCaltopoRtt(long rttMs) {
        myCaltopoRttMs = rttMs;
        // Re-evaluate drones since our RTT might make us better or worse candidates
        for (DroneState ds : drones.values()) {
            if (myGuid.equals(ds.ownerGuid) || ds.obs.containsKey(myGuid)) {
                scheduleOwnershipCheck(ds.remoteId, 500);
            }
        }
    }

    /** Called by CaltopoMap when our GPS location is updated. */
    public static void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
    }

    // ── publish helpers ───────────────────────────────────────────────────────

    private static void publishHeartbeat() {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) return;
        publishRetained(peerTopic(myGuid), buildPeerPayload(true));
        checkPeerExpiry();
    }

    /**
     * Remove peers whose heartbeat has not been seen for more than
     * {@code 3 × HEARTBEAT_INTERVAL_MS} (30 s).  Called on every heartbeat tick
     * so stale peers are evicted promptly even when the remote device is killed
     * without sending an explicit offline message.
     */
    private static void checkPeerExpiry() {
        long now = System.currentTimeMillis();
        long expiryMs = 3 * HEARTBEAT_INTERVAL_MS; // 30 seconds
        boolean changed = false;
        Iterator<Map.Entry<String, PeerState>> it = peers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PeerState> e = it.next();
            PeerState ps = e.getValue();
            if (now - ps.lastSeenMs > expiryMs) {
                CTInfo(TAG, String.format(Locale.US,
                        "checkPeerExpiry(): removing stale peer '%s' (last seen %d ms ago)",
                        ps.name, now - ps.lastSeenMs));
                it.remove();
                changed = true;
            }
        }
        if (changed) notifyPeerListChanged();
    }

    private static void publishDetect(@NonNull String remoteId,
                                       @NonNull CtDroneSpec spec,
                                       double distMeters,
                                       long firstSeenTs) {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) return;
        try {
            JSONObject jo = new JSONObject();
            jo.put("dLat", spec.lastLat);
            jo.put("dLon", spec.lastLng);
            jo.put("dAlt", spec.lastAlt);
            jo.put("dist", distMeters);
            jo.put("fts",  firstSeenTs);
            jo.put("ts",   System.currentTimeMillis());
            peerTransport.publish(
                    detectTopic(remoteId, myGuid),
                    jo.toString().getBytes(StandardCharsets.UTF_8),
                    0,
                    false
            );
        } catch (Exception e) {
            CTError(TAG, "publishDetect() raised.", e);
        }
    }

    private static void publishOwnerClaim(@NonNull String remoteId) {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) return;
        try {
            JSONObject jo = new JSONObject();
            jo.put("guid", myGuid);
            jo.put("name", myName);
            jo.put("ts",   System.currentTimeMillis());
            publishRetained(ownerTopic(remoteId), jo.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            CTError(TAG, "publishOwnerClaim() raised.", e);
        }
    }

    private static void publishRetained(@NonNull String topic, @NonNull byte[] payload) {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) return;
        try {
            peerTransport.publish(topic, payload, 1, true);
        } catch (Exception e) {
            CTError(TAG, "publishRetained(" + topic + ") raised.", e);
        }
    }

    private static void publishDroneSpec(@NonNull String remoteId) {
        if (!initialized || peerTransport == null || !peerTransport.isConnected()) return;
        CtDroneSpec spec = CaltopoClient.GetDroneSpec(remoteId);
        if (spec == null) return;
        if (spec.isLocalArchiveOnly()) return;
        String mid = spec.getMappedId();
        if (mid.isEmpty() || mid.equals(remoteId)) return; // don't publish trivial (unset) spec
        try {
            JSONObject jo = new JSONObject();
            jo.put("mid",   mid);
            jo.put("org",   spec.getOrg());
            jo.put("model", spec.getModel());
            jo.put("owner", spec.getOwner());
            jo.put("ts",    System.currentTimeMillis());
            publishRetained(specTopic(remoteId), jo.toString().getBytes(StandardCharsets.UTF_8));
            CTInfo(TAG, String.format(Locale.US,
                    "publishDroneSpec(%s): mid=%s", remoteId, mid));
        } catch (Exception e) {
            CTError(TAG, "publishDroneSpec() raised.", e);
        }
    }

    /** Delete a retained message by publishing empty payload. */
    private static void clearRetained(@NonNull String topic) {
        publishRetained(topic, new byte[0]);
    }

    private static byte[] buildPeerPayload(boolean online) {
        try {
            JSONObject jo = new JSONObject();
            jo.put("name",   myName);
            jo.put("lat",    myLat);
            jo.put("lon",    myLon);
            jo.put("ctRtt",  myCaltopoRttMs);
            jo.put("ts",     System.currentTimeMillis());
            jo.put("online", online);
            return jo.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    // ── topic builders ────────────────────────────────────────────────────────

    private static String peerTopic(@NonNull String guid) {
        return "R2C/" + mapId + "/peer/" + guid;
    }

    private static String detectTopic(@NonNull String remoteId, @NonNull String guid) {
        return "R2C/" + mapId + "/drone/" + remoteId + "/detect/" + guid;
    }

    private static String specTopic(@NonNull String remoteId) {
        return "R2C/" + mapId + "/drone/" + remoteId + "/spec";
    }

    private static String ownerTopic(@NonNull String remoteId) {
        return "R2C/" + mapId + "/drone/" + remoteId + "/owner";
    }

    // ── misc helpers ──────────────────────────────────────────────────────────

    private static DroneState getOrCreateDroneState(@NonNull String remoteId) {
        return drones.computeIfAbsent(remoteId, DroneState::new);
    }

    private static void scheduleOwnershipCheck(@NonNull String remoteId, long delayMs) {
        DelayedExec.RunAfterDelayInMsec(() -> checkOwnership(remoteId), delayMs);
    }

    private static void notifyPeerListChanged() {
        PeerListChangedListener listener = peerListChangedListener;
        if (listener == null) return;
        List<PeerState> snapshot = new ArrayList<>(peers.values());
        // Populate each peer's ownedDrones list for UI
        for (PeerState ps : snapshot) ps.ownedDrones.clear();
        for (Map.Entry<String, DroneState> e : drones.entrySet()) {
            String ownerGuid = e.getValue().ownerGuid;
            if (ownerGuid == null) continue;   // ownerGuid is unresolved; ConcurrentHashMap rejects null keys
            PeerState ps = peers.get(ownerGuid);
            if (ps != null) {
                CtDroneSpec spec = CaltopoClient.GetDroneSpec(e.getKey());
                if (spec != null) ps.ownedDrones.add(spec);
            }
        }
        listener.onPeerListChanged(snapshot);
    }

    private static double distanceInMeters(double lat1, double lon1, double lat2, double lon2) {
        float[] result = {0};
        android.location.Location.distanceBetween(lat1, lon1, lat2, lon2, result);
        return result[0];
    }

    private static boolean objectsEqual(@Nullable Object a, @Nullable Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ── peer list listener ────────────────────────────────────────────────────

    public static void SetPeerListChangedListener(@Nullable PeerListChangedListener listener) {
        peerListChangedListener = listener;
    }

    public static void SetCoordinationIndicatorListener(@Nullable PeerCoordinator.CoordinationIndicatorListener listener) {
        coordinationIndicatorListener = listener;
        notifyCoordinationIndicatorListener();
    }

    public static @NonNull List<PeerState> GetPeerList() {
        return new ArrayList<>(peers.values());
    }

    public static synchronized void SetPeerTransportFactoryForTesting(@Nullable PeerTransportFactory factory) {
        peerTransportFactory = factory;
    }

    /** Returns true if the transport is currently connected (for tests and UI diagnostics). */
    public static boolean isConnected() {
        return initialized && peerTransport != null && peerTransport.isConnected();
    }

    @NonNull
    public static String GetBrokerUri() {
        return brokerUri != null ? brokerUri : DEFAULT_BROKER_URI;
    }

    /**
     * Returns true if a successful subscribe has been recorded for the given filter.
     * Intended for unit tests only.
     */
    public static boolean isSubscribedForTesting(@NonNull String topicFilter) {
        return topicFilter.equals(subscribedTopicFilter);
    }

    /**
     * Directly invokes ownership evaluation for a remoteId, bypassing DISCOVERY_WINDOW_MS.
     * Intended for unit tests only.
     */
    public static void checkOwnershipForTesting(@NonNull String remoteId) {
        checkOwnership(remoteId);
    }

    /** Overrides the handoff delay for unit tests (pass 0 to apply ownership synchronously). */
    public static void setHandoffDelayMsForTesting(long ms) {
        HANDOFF_DELAY_MS = ms;
    }

    /** Returns the current ownerGuid for a drone (for unit tests). */
    @Nullable
    public static String getOwnerGuidForTesting(@NonNull String remoteId) {
        DroneState ds = drones.get(remoteId);
        return ds != null ? ds.ownerGuid : null;
    }

    /** Returns the localOwnerActive flag for a drone (for unit tests). */
    public static boolean getLocalOwnerActiveForTesting(@NonNull String remoteId) {
        DroneState ds = drones.get(remoteId);
        return ds != null && ds.localOwnerActive;
    }

    /** Returns the number of observations recorded for a drone (for unit tests). */
    public static int getObservationCountForTesting(@NonNull String remoteId) {
        DroneState ds = drones.get(remoteId);
        return ds != null ? ds.obs.size() : 0;
    }

    /**
     * Directly injects a peer observation without going through MQTT message parsing.
     * Use in unit tests where org.json stubs (returnDefaultValues=true) would otherwise
     * cause all optDouble/optLong calls to return 0 regardless of the payload content.
     */
    public static void addPeerObservationForTesting(
            @NonNull String remoteId,
            @NonNull String peerGuid,
            double distMeters,
            long firstSeenTs) {
        DroneState ds = getOrCreateDroneState(remoteId);
        long now = System.currentTimeMillis();
        DroneObservation obs = new DroneObservation(peerGuid, 0, 0, 0, distMeters, now);
        obs.firstSeenTs = firstSeenTs;
        ds.obs.put(peerGuid, obs);
    }

    /** Full reset for unit tests: shuts down if running, clears all static state. */
    public static synchronized void resetForTesting() {
        shutdown();
        peers.clear();
        drones.clear();
        peerTransportFactory = null;
        subscribedTopicFilter = null;
        HANDOFF_DELAY_MS = 2_000;
        myGuid    = "";
        myName    = "";
        mapId     = "";
        brokerUri = DEFAULT_BROKER_URI;
    }

    @NonNull
    private static final PeerCoordinator DEFAULT_COORDINATOR = new PeerCoordinator() {
        @Override
        public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
            R2CMqttManager.init(mapId, guid, name, brokerUri);
        }

        @Override
        public void stop() {
            R2CMqttManager.shutdown();
        }

        @Override
        public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                       @NonNull CtDroneSpec droneSpec,
                                       double distMeters,
                                       long firstSeenTs) {
            R2CMqttManager.onLiveTrackCreated(liveTrack, droneSpec, distMeters, firstSeenTs);
        }

        @Override
        public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                       double droneLat,
                                       double droneLon,
                                       double droneAlt,
                                       double distMeters,
                                       long timestampMsec,
                                       @Nullable CtDroneSpec.PositionTelemetry telemetry) {
            R2CMqttManager.onWaypointReceived(droneSpec.getRemoteId(), droneLat, droneLon, droneAlt, distMeters);
        }

        @Override
        public void onDroneLost(@NonNull String remoteId) {
            R2CMqttManager.onDroneLost(remoteId);
        }

        @Override
        public boolean isLocalOwner(@NonNull String remoteId) {
            return R2CMqttManager.isLocalOwner(remoteId);
        }

        @Override
        public void updateCaltopoRtt(long rttMs) {
            R2CMqttManager.updateCaltopoRtt(rttMs);
        }

        @Override
        public long getCaltopoRttMs() {
            return myCaltopoRttMs;
        }

        @Override
        public void updateMyPosition(double lat, double lon) {
            R2CMqttManager.updateMyPosition(lat, lon);
        }

        @Override
        public void setPeerListChangedListener(@Nullable PeerListChangedListener listener) {
            R2CMqttManager.SetPeerListChangedListener(listener);
        }

        @Override
        public void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener) {
            R2CMqttManager.SetCoordinationIndicatorListener(listener);
        }

        @NonNull
        @Override
        public List<PeerState> getPeerList() {
            return R2CMqttManager.GetPeerList();
        }

        @NonNull
        @Override
        public CoordinationIndicatorState getCoordinationIndicatorState() {
            if (!CaltopoClient.GetUsePeersFlag()) {
                return CoordinationIndicatorState.UNCONFIGURED;
            }
            return R2CMqttManager.isConnected()
                    ? CoordinationIndicatorState.HEALTHY
                    : CoordinationIndicatorState.DEGRADED;
        }
    };

    @NonNull
    public static PeerCoordinator GetDefaultCoordinator() {
        return DEFAULT_COORDINATOR;
    }

    @NonNull
    private static PeerTransport createPeerTransport(@NonNull String brokerUri, @NonNull String clientId) throws MqttException {
        if (peerTransportFactory != null) {
            return peerTransportFactory.create(brokerUri, clientId);
        }
        return new MqttPeerTransport(brokerUri, clientId);
    }

    // ── network address monitoring (replaces R2CPeer networking) ─────────────

    public static void InitializeNetworkAddressMonitor(@Nullable Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        synchronized (AddrLock) {
            if (addrMonitorStarted) return;
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            addrCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(@NonNull Network n)    { refreshNetworkState(appContext, cm, "available"); }
                @Override public void onLost(@NonNull Network n)         { refreshNetworkState(appContext, cm, "lost"); }
                @Override public void onLinkPropertiesChanged(
                        @NonNull Network n, @NonNull LinkProperties lp)  { refreshNetworkState(appContext, cm, "link_properties"); }
                @Override public void onCapabilitiesChanged(
                        @NonNull Network n, @NonNull NetworkCapabilities nc) { refreshNetworkState(appContext, cm, "capabilities"); }
            };
            try {
                // Track the effective default route, including an unvalidated controller LAN.
                cm.registerDefaultNetworkCallback(addrCallback);
                addrMonitorStarted = true;
                refreshIpAddrs(cm);
                NetworkDiagnostics.recordCurrentNetwork(appContext, cm, "startup", true);
            } catch (Exception e) {
                CTError(TAG, "InitializeNetworkAddressMonitor(): registerNetworkCallback raised.", e);
            }
        }
    }

    private static void refreshNetworkState(
            @NonNull Context context,
            @NonNull ConnectivityManager cm,
            @NonNull String reason) {
        refreshIpAddrs(cm);
        NetworkDiagnostics.recordCurrentNetwork(context, cm, reason, false);
    }

    private static void refreshIpAddrs(@NonNull ConnectivityManager cm) {
        ArrayList<String> addrs = new ArrayList<>();
        try {
            for (Network net : cm.getAllNetworks()) {
                LinkProperties lp = cm.getLinkProperties(net);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress ia = la.getAddress();
                    if (ia instanceof Inet4Address && !ia.isLoopbackAddress()) {
                        String addr = ia.getHostAddress();
                        if (addr != null && !addr.isEmpty() && !addrs.contains(addr))
                            addrs.add(addr);
                    }
                }
            }
        } catch (Exception e) {
            CTError(TAG, "refreshIpAddrs() raised.", e);
        }
        CurrentIpAddrs = Collections.unmodifiableList(addrs);
    }

    /** Returns the first non-loopback IPv4 address, or empty string.
     *  Queries NetworkInterface directly so it is always current without
     *  waiting for a ConnectivityManager callback to fire. */
    @NonNull
    public static String GetMyIpAddress() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                CTInfo(TAG, "GetMyIpAddress(): getNetworkInterfaces() returned null");
                return "";
            }
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                boolean up = iface.isUp();
                boolean loopback = iface.isLoopback();
                CTInfo(TAG, String.format(Locale.US,
                        "GetMyIpAddress(): iface=%s up=%b loopback=%b", iface.getName(), up, loopback));
                if (!up || loopback) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    String host = addr.getHostAddress();
                    CTInfo(TAG, String.format(Locale.US,
                            "GetMyIpAddress():   addr=%s isIPv4=%b isLoopback=%b",
                            host, addr instanceof Inet4Address, addr.isLoopbackAddress()));
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        if (host != null && !host.isEmpty()) {
                            CTInfo(TAG, "GetMyIpAddress(): returning " + host);
                            return host;
                        }
                    }
                }
            }
        } catch (Exception e) {
            CTError(TAG, "GetMyIpAddress() raised.", e);
        }
        CTInfo(TAG, "GetMyIpAddress(): no suitable address found, returning empty");
        return "";
    }

    /** Returns all IPv4 addresses as a JSON array (for log header). */
    @NonNull
    public static JSONArray GetMyIpAddresses() {
        JSONArray ja = new JSONArray();
        for (String addr : CurrentIpAddrs) ja.put(addr);
        return ja;
    }
}

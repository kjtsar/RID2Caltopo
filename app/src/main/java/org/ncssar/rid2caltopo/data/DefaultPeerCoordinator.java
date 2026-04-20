package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Default app runtime peer coordinator.
 *
 * This is the first extraction seam for multi-runtime testing: callers can
 * depend on the {@link PeerCoordinator} interface today while the production
 * app continues to route through the existing static {@link R2CMqttManager}.
 */
public final class DefaultPeerCoordinator implements PeerCoordinator {
    private static final DefaultPeerCoordinator INSTANCE = new DefaultPeerCoordinator();
    @Nullable private static volatile PeerCoordinator mqttCoordinatorOverrideForTesting;

    private static final class ActiveTrackRegistration {
        @NonNull final LiveTrackOwnerDelegate liveTrack;
        @NonNull final CtDroneSpec droneSpec;
        final double distMeters;
        final long firstSeenTs;

        ActiveTrackRegistration(@NonNull LiveTrackOwnerDelegate liveTrack,
                                @NonNull CtDroneSpec droneSpec,
                                double distMeters,
                                long firstSeenTs) {
            this.liveTrack = liveTrack;
            this.droneSpec = droneSpec;
            this.distMeters = distMeters;
            this.firstSeenTs = firstSeenTs;
        }
    }

    @NonNull private final Map<String, ActiveTrackRegistration> activeTracks = new ConcurrentHashMap<>();
    @Nullable private volatile R2CMqttManager.PeerListChangedListener peerListChangedListener;
    @Nullable private volatile String startedMapId;
    @Nullable private volatile String startedGuid;
    @Nullable private volatile String startedName;
    @Nullable private volatile String startedBrokerUri;
    private volatile boolean trackerSelected;
    private volatile double myLat;
    private volatile double myLon;
    private volatile long myCaltopoRttMs = 2_000L;
    @NonNull private volatile PeerCoordinator activeCoordinator = getMqttCoordinator();

    private DefaultPeerCoordinator() { }

    @NonNull
    public static DefaultPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        boolean nextTrackerSelected = shouldUseTrackerCoordinator();
        PeerCoordinator nextCoordinator = nextTrackerSelected
                ? TrackerPeerCoordinator.getInstance()
                : getMqttCoordinator();
        if (sameString(startedMapId, mapId) &&
                sameString(startedGuid, guid) &&
                sameString(startedName, name) &&
                sameString(startedBrokerUri, brokerUri) &&
                trackerSelected == nextTrackerSelected &&
                activeCoordinator == nextCoordinator) {
            CaltopoClient.CTDebug(
                    "DefaultPeerCoord",
                    String.format(
                            "start(): ignoring duplicate start for mapId='%s' guid='%s' using %s coordination",
                            mapId,
                            guid,
                            trackerSelected ? "tracker" : "mqtt"
                    )
            );
            return;
        }
        startedMapId = mapId;
        startedGuid = guid;
        startedName = name;
        startedBrokerUri = brokerUri;
        trackerSelected = nextTrackerSelected;
        activeCoordinator = nextCoordinator;
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(this::handleTrackerHardFailure);
        }
        CaltopoClient.CTInfo(
                "DefaultPeerCoord",
                String.format(
                        "start(): using %s coordination for mapId='%s' guid='%s' trackerConfigured=%s brokerUriPresent=%s",
                        trackerSelected ? "tracker" : "mqtt",
                        mapId,
                        guid,
                        trackerSelected,
                        brokerUri != null && !brokerUri.isEmpty()
                )
        );
        if (peerListChangedListener != null) {
            activeCoordinator.setPeerListChangedListener(peerListChangedListener);
        }
        activeCoordinator.start(mapId, guid, name, brokerUri);
        activeCoordinator.updateCaltopoRtt(myCaltopoRttMs);
        activeCoordinator.updateMyPosition(myLat, myLon);
    }

    @Override
    public void stop() {
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(null);
        }
        activeCoordinator.stop();
        trackerSelected = false;
        startedMapId = null;
        startedGuid = null;
        startedName = null;
        startedBrokerUri = null;
        activeTracks.clear();
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        activeTracks.put(droneSpec.getRemoteId(),
                new ActiveTrackRegistration(liveTrack, droneSpec, distMeters, firstSeenTs));
        activeCoordinator.onLiveTrackCreated(liveTrack, droneSpec, distMeters, firstSeenTs);
    }

    @Override
    public void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters,
                                   long timestampMsec,
                                   @Nullable CtDroneSpec.PositionTelemetry telemetry) {
        activeCoordinator.onWaypointReceived(
                droneSpec,
                droneLat,
                droneLon,
                droneAlt,
                distMeters,
                timestampMsec,
                telemetry
        );
    }

    @Override
    public void onDroneLost(@NonNull String remoteId) {
        activeTracks.remove(remoteId);
        activeCoordinator.onDroneLost(remoteId);
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        return activeCoordinator.isLocalOwner(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        myCaltopoRttMs = rttMs;
        activeCoordinator.updateCaltopoRtt(rttMs);
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        activeCoordinator.updateMyPosition(lat, lon);
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        peerListChangedListener = listener;
        activeCoordinator.setPeerListChangedListener(listener);
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return activeCoordinator.getPeerList();
    }

    @NonNull
    @Override
    public CoordinationIndicatorState getCoordinationIndicatorState() {
        boolean peersEnabled = CaltopoClient.GetUsePeersFlag();
        boolean trackerConfigured = !CaltopoClient.GetTrackerCoordinationUrlPfx().isEmpty()
                && !CaltopoClient.GetTrackerCoordinationApiKey().isEmpty();
        boolean mqttConfigured = peersEnabled;

        if (!trackerConfigured && !mqttConfigured) {
            return CoordinationIndicatorState.UNCONFIGURED;
        }

        CoordinationIndicatorState activeState = activeCoordinator.getCoordinationIndicatorState();
        if (activeState == CoordinationIndicatorState.HEALTHY) {
            return CoordinationIndicatorState.HEALTHY;
        }

        if (trackerConfigured || mqttConfigured) {
            return CoordinationIndicatorState.DEGRADED;
        }

        return CoordinationIndicatorState.UNCONFIGURED;
    }

    private boolean shouldUseTrackerCoordinator() {
        return CaltopoClient.GetUsePeersFlag()
                && !CaltopoClient.GetTrackerApiKey().isEmpty()
                && !CaltopoClient.GetTrackerUrlPfx().isEmpty();
    }

    private void handleTrackerHardFailure(int responseCode, @Nullable String responseMessage) {
        if (!trackerSelected) return;
        CaltopoClient.CTWarn(
                "DefaultPeerCoord",
                String.format("Tracker coordination hard-failed with code=%d message='%s'; falling back to MQTT.",
                        responseCode, responseMessage == null ? "" : responseMessage)
        );
        switchToCoordinator(getMqttCoordinator(), false);
    }

    private synchronized void switchToCoordinator(@NonNull PeerCoordinator coordinator, boolean tracker) {
        if (startedMapId == null || startedGuid == null || startedName == null) return;
        if (activeCoordinator == coordinator && trackerSelected == tracker) return;
        if (trackerSelected) {
            TrackerPeerCoordinator.getInstance().setHardFailureListener(null);
        }
        activeCoordinator.stop();
        activeCoordinator = coordinator;
        trackerSelected = tracker;
        if (peerListChangedListener != null) {
            activeCoordinator.setPeerListChangedListener(peerListChangedListener);
        }
        activeCoordinator.start(startedMapId, startedGuid, startedName, startedBrokerUri);
        activeCoordinator.updateCaltopoRtt(myCaltopoRttMs);
        activeCoordinator.updateMyPosition(myLat, myLon);
        for (ActiveTrackRegistration registration : activeTracks.values()) {
            activeCoordinator.onLiveTrackCreated(
                    registration.liveTrack,
                    registration.droneSpec,
                    registration.distMeters,
                    registration.firstSeenTs
            );
        }
    }

    @NonNull
    private static PeerCoordinator getMqttCoordinator() {
        return mqttCoordinatorOverrideForTesting != null
                ? mqttCoordinatorOverrideForTesting
                : R2CMqttManager.GetDefaultCoordinator();
    }

    static void setMqttCoordinatorOverrideForTesting(@Nullable PeerCoordinator coordinator) {
        mqttCoordinatorOverrideForTesting = coordinator;
    }

    private static boolean sameString(@Nullable String left, @Nullable String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }
}

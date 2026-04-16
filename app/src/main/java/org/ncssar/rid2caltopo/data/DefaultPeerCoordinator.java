package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    @NonNull private volatile PeerCoordinator activeCoordinator = R2CMqttManager.GetDefaultCoordinator();

    private DefaultPeerCoordinator() { }

    @NonNull
    public static DefaultPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        activeCoordinator = shouldUseTrackerCoordinator()
                ? TrackerPeerCoordinator.getInstance()
                : R2CMqttManager.GetDefaultCoordinator();
        activeCoordinator.start(mapId, guid, name, brokerUri);
    }

    @Override
    public void stop() {
        activeCoordinator.stop();
    }

    @Override
    public void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
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
        activeCoordinator.onDroneLost(remoteId);
    }

    @Override
    public boolean isLocalOwner(@NonNull String remoteId) {
        return activeCoordinator.isLocalOwner(remoteId);
    }

    @Override
    public void updateCaltopoRtt(long rttMs) {
        activeCoordinator.updateCaltopoRtt(rttMs);
    }

    @Override
    public void updateMyPosition(double lat, double lon) {
        activeCoordinator.updateMyPosition(lat, lon);
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        activeCoordinator.setPeerListChangedListener(listener);
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return activeCoordinator.getPeerList();
    }

    private boolean shouldUseTrackerCoordinator() {
        return !CaltopoClient.GetTrackerApiKey().isEmpty()
                && !CaltopoClient.GetTrackerUrlPfx().isEmpty();
    }
}

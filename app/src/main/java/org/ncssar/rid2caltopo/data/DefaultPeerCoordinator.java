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

    private DefaultPeerCoordinator() { }

    @NonNull
    public static DefaultPeerCoordinator getInstance() {
        return INSTANCE;
    }

    @Override
    public void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri) {
        R2CMqttManager.init(mapId, guid, name, brokerUri);
    }

    @Override
    public void stop() {
        R2CMqttManager.shutdown();
    }

    @Override
    public void onLiveTrackCreated(@NonNull CaltopoLiveTrack liveTrack,
                                   @NonNull CtDroneSpec droneSpec,
                                   double distMeters,
                                   long firstSeenTs) {
        R2CMqttManager.onLiveTrackCreated(liveTrack, droneSpec, distMeters, firstSeenTs);
    }

    @Override
    public void onWaypointReceived(@NonNull String remoteId,
                                   double droneLat,
                                   double droneLon,
                                   double droneAlt,
                                   double distMeters) {
        R2CMqttManager.onWaypointReceived(remoteId, droneLat, droneLon, droneAlt, distMeters);
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
    public void updateMyPosition(double lat, double lon) {
        R2CMqttManager.updateMyPosition(lat, lon);
    }

    @Override
    public void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener) {
        R2CMqttManager.SetPeerListChangedListener(listener);
    }

    @NonNull
    @Override
    public List<R2CMqttManager.PeerState> getPeerList() {
        return R2CMqttManager.GetPeerList();
    }
}

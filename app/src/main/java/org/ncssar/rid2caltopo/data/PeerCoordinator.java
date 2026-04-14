package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

public interface PeerCoordinator {
    void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri);
    void stop();
    void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                            @NonNull CtDroneSpec droneSpec,
                            double distMeters,
                            long firstSeenTs);
    void onWaypointReceived(@NonNull String remoteId,
                            double droneLat,
                            double droneLon,
                            double droneAlt,
                            double distMeters);
    void onDroneLost(@NonNull String remoteId);
    boolean isLocalOwner(@NonNull String remoteId);
    void updateCaltopoRtt(long rttMs);
    void updateMyPosition(double lat, double lon);
    void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener);
    @NonNull List<R2CMqttManager.PeerState> getPeerList();
}

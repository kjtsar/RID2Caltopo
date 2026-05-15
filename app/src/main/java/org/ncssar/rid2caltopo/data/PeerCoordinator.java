package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

public interface PeerCoordinator {
    interface CoordinationIndicatorListener {
        void onCoordinationIndicatorStateChanged(@NonNull CoordinationIndicatorState state);
    }

    enum CoordinationIndicatorState {
        HEALTHY,
        DEGRADED,
        UNCONFIGURED
    }

    void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri);
    void stop();
    void onLiveTrackCreated(@NonNull LiveTrackOwnerDelegate liveTrack,
                            @NonNull CtDroneSpec droneSpec,
                            double distMeters,
                            long firstSeenTs);
    void onWaypointReceived(@NonNull CtDroneSpec droneSpec,
                            double droneLat,
                            double droneLon,
                            double droneAlt,
                            double distMeters,
                            long timestampMsec,
                            @Nullable CtDroneSpec.PositionTelemetry telemetry);
    void onDroneLost(@NonNull String remoteId);
    default void onDroneConfirmed(@NonNull String remoteId,
                                  long flightStartMsec,
                                  @NonNull String org,
                                  @NonNull String model,
                                  @NonNull String owner,
                                  @NonNull String mappedId) {
    }
    boolean isLocalOwner(@NonNull String remoteId);
    void updateCaltopoRtt(long rttMs);
    long getCaltopoRttMs();
    void updateMyPosition(double lat, double lon);
    void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener);
    void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener);
    @NonNull List<R2CMqttManager.PeerState> getPeerList();
    @NonNull CoordinationIndicatorState getCoordinationIndicatorState();
    @NonNull
    default String getCoordinationStatusText() {
        switch (getCoordinationIndicatorState()) {
            case HEALTHY:
                return "R2C link healthy";
            case DEGRADED:
                return "R2C link degraded";
            case UNCONFIGURED:
            default:
                return "R2C link not configured";
        }
    }
    @NonNull
    default List<String> getCoordinationDiagnosticLines() {
        return Collections.emptyList();
    }
}

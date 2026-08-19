package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

public interface PeerCoordinator {
    interface CoordinationIndicatorListener {
        void onCoordinationIndicatorStateChanged(@NonNull CoordinationIndicatorState state);
    }
    interface VideoStreamRequestListener {
        void onVideoStreamRequest(@NonNull VideoStreamViewRequest request);
        default void onVideoPreflightResult(
                @NonNull String requestId,
                @NonNull String routeKind,
                long estimatedUplinkBps) {
        }
        default void onVideoPreflightFailure(
                @NonNull String requestId,
                @NonNull String reason) {
        }
        default void onVideoStreamRequestCancelled(@NonNull String requestId) {
        }
        default void onVideoMediaOffer(@NonNull VideoMediaOffer offer) {
        }
        default void onRecordingDownloadRequest(@NonNull RecordingDownloadRequest request) {
        }
    }

    enum CoordinationIndicatorState {
        HEALTHY,
        IDLE,
        DEGRADED,
        UNCONFIGURED
    }

    void start(@NonNull String mapId, @NonNull String guid, @NonNull String name, @Nullable String brokerUri);
    default void resumeAfterReauthentication() {
    }
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
                                  @NonNull String org,
                                  @NonNull String model,
                                  @NonNull String owner,
                                  @NonNull String mappedId) {
    }
    boolean isLocalOwner(@NonNull String remoteId);
    default boolean isLocalAlertEligible(@NonNull String remoteId) {
        return true;
    }
    void updateCaltopoRtt(long rttMs);
    long getCaltopoRttMs();
    void updateMyPosition(double lat, double lon);
    void setPeerListChangedListener(@Nullable R2CMqttManager.PeerListChangedListener listener);
    void setCoordinationIndicatorListener(@Nullable CoordinationIndicatorListener listener);
    default void setVideoStreamRequestListener(
            @Nullable VideoStreamRequestListener listener) {
    }
    default void updateManagedVideoStreams(
            @NonNull String incidentName,
            @NonNull List<ManagedVideoStreamAdvertisement> streams) {
    }
    @NonNull
    default List<ManagedVideoStreamAdvertisement> getManagedVideoStreams() {
        return Collections.emptyList();
    }
    default boolean shouldRefreshManagedVideoThumbnails() {
        return false;
    }
    default void respondToVideoStreamRequest(
            @NonNull String requestId,
            boolean approved,
            int selectedWidth,
            int selectedHeight,
            double selectedFps,
            long selectedBitrateBps) {
    }
    default void sendVideoMediaAnswer(@NonNull String requestId, @NonNull String sdp) {
    }
    default void sendVideoStreamTerminated(@NonNull String requestId, @NonNull String reason) {
    }
    default void respondToRecordingDownloadRequest(@NonNull String requestId, boolean approved) {
    }
    default void uploadRecordingDownload(@NonNull RecordingDownloadRequest request) {
    }
    @NonNull List<R2CMqttManager.PeerState> getPeerList();
    @NonNull CoordinationIndicatorState getCoordinationIndicatorState();
    @NonNull
    default String getCoordinationStatusText() {
        switch (getCoordinationIndicatorState()) {
            case HEALTHY:
                return "R2C link healthy";
            case IDLE:
                return "R2C link idle";
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

package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * Minimal runtime container for incrementally isolating formerly-global services.
 *
 * This first slice only carries the peer coordinator. Additional runtime-owned
 * services (CalTopo session, tracker publisher, clock, etc.) can be added
 * here as they are extracted from static singletons.
 */
public final class R2cRuntime {
    @NonNull private final String runtimeId;
    @NonNull private final PeerCoordinator peerCoordinator;
    @NonNull private final CalTopoSessionGateway calTopoSessionGateway;
    @NonNull private final TrackerPublisher trackerPublisher;

    public R2cRuntime(@NonNull String runtimeId,
                      @NonNull PeerCoordinator peerCoordinator,
                      @NonNull CalTopoSessionGateway calTopoSessionGateway,
                      @NonNull TrackerPublisher trackerPublisher) {
        this.runtimeId = runtimeId;
        this.peerCoordinator = peerCoordinator;
        this.calTopoSessionGateway = calTopoSessionGateway;
        this.trackerPublisher = trackerPublisher;
    }

    @NonNull
    public String getRuntimeId() {
        return runtimeId;
    }

    @NonNull
    public PeerCoordinator getPeerCoordinator() {
        return peerCoordinator;
    }

    @NonNull
    public CalTopoSessionGateway getCalTopoSessionGateway() {
        return calTopoSessionGateway;
    }

    @NonNull
    public TrackerPublisher getTrackerPublisher() {
        return trackerPublisher;
    }
}

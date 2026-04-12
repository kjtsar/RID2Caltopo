package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory hub for {@link OwnershipTestPeerCoordinator} fixtures.
 */
public final class OwnershipTestPeerHub {

    @NonNull private final CopyOnWriteArrayList<OwnershipTestPeerCoordinator> coordinators =
            new CopyOnWriteArrayList<>();

    public void register(@NonNull OwnershipTestPeerCoordinator coordinator) {
        coordinators.addIfAbsent(coordinator);
    }

    public void unregister(@NonNull OwnershipTestPeerCoordinator coordinator) {
        coordinators.remove(coordinator);
    }

    public void publishObservation(@NonNull OwnershipTestPeerCoordinator sender,
                                   @NonNull String remoteId,
                                   double distMeters,
                                   long firstSeenTs) {
        for (OwnershipTestPeerCoordinator coordinator : coordinators) {
            if (coordinator == sender) continue;
            coordinator.receiveObservation(sender.getGuidForTesting(), remoteId, distMeters, firstSeenTs);
        }
    }

    public void removeObservation(@NonNull OwnershipTestPeerCoordinator sender,
                                  @NonNull String remoteId) {
        for (OwnershipTestPeerCoordinator coordinator : coordinators) {
            if (coordinator == sender) continue;
            coordinator.removeObservationFor(sender.getGuidForTesting(), remoteId);
        }
    }

    public long lookupRttMs(@NonNull String guid) {
        for (OwnershipTestPeerCoordinator coordinator : coordinators) {
            if (coordinator.getGuidForTesting().equals(guid)) {
                return coordinator.getCaltopoRttMsForTesting();
            }
        }
        return 2_000L;
    }

    @NonNull
    public List<OwnershipTestPeerCoordinator> snapshotCoordinators() {
        return new ArrayList<>(coordinators);
    }
}

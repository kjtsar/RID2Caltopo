package org.ncssar.rid2caltopo.data;

import androidx.annotation.NonNull;

/**
 * Small test fixture for assembling isolated runtimes without touching the
 * production singletons directly.
 */
public final class TestR2cRuntimeFactory {

    public static final class Fixture {
        @NonNull public final R2cRuntime runtime;
        @NonNull public final PeerCoordinator peerCoordinator;
        @NonNull public final FakeCalTopoSessionGateway calTopoSessionGateway;
        @NonNull public final FakeTrackerPublisher trackerPublisher;

        Fixture(@NonNull R2cRuntime runtime,
                @NonNull PeerCoordinator peerCoordinator,
                @NonNull FakeCalTopoSessionGateway calTopoSessionGateway,
                @NonNull FakeTrackerPublisher trackerPublisher) {
            this.runtime = runtime;
            this.peerCoordinator = peerCoordinator;
            this.calTopoSessionGateway = calTopoSessionGateway;
            this.trackerPublisher = trackerPublisher;
        }

        public void register() {
            R2cRuntimeRegistry.registerRuntime(runtime);
        }

        public void setAsDefaultRuntime() {
            R2cRuntimeRegistry.setDefaultRuntimeForTesting(runtime);
        }
    }

    private TestR2cRuntimeFactory() { }

    @NonNull
    public static Fixture create(@NonNull String runtimeId) {
        FakePeerCoordinator peerCoordinator = new FakePeerCoordinator(runtimeId);
        FakeCalTopoSessionGateway calTopoSessionGateway = new FakeCalTopoSessionGateway(runtimeId);
        FakeTrackerPublisher trackerPublisher = new FakeTrackerPublisher(runtimeId);
        R2cRuntime runtime = new R2cRuntime(runtimeId, peerCoordinator, calTopoSessionGateway, trackerPublisher);
        return new Fixture(runtime, peerCoordinator, calTopoSessionGateway, trackerPublisher);
    }

    @NonNull
    public static OwnershipFixture createOwnershipFixture(@NonNull String runtimeId,
                                                          @NonNull OwnershipTestPeerHub hub) {
        OwnershipTestPeerCoordinator peerCoordinator = new OwnershipTestPeerCoordinator(runtimeId, hub);
        FakeCalTopoSessionGateway calTopoSessionGateway = new FakeCalTopoSessionGateway(runtimeId);
        FakeTrackerPublisher trackerPublisher = new FakeTrackerPublisher(runtimeId);
        peerCoordinator.setCalTopoSessionGatewayForTesting(calTopoSessionGateway);
        peerCoordinator.setTrackerPublisherForTesting(trackerPublisher);
        R2cRuntime runtime = new R2cRuntime(runtimeId, peerCoordinator, calTopoSessionGateway, trackerPublisher);
        return new OwnershipFixture(runtime, peerCoordinator, calTopoSessionGateway, trackerPublisher);
    }

    public static final class OwnershipFixture extends Fixture {
        @NonNull public final OwnershipTestPeerCoordinator ownershipPeerCoordinator;

        OwnershipFixture(@NonNull R2cRuntime runtime,
                         @NonNull OwnershipTestPeerCoordinator peerCoordinator,
                         @NonNull FakeCalTopoSessionGateway calTopoSessionGateway,
                         @NonNull FakeTrackerPublisher trackerPublisher) {
            super(runtime, peerCoordinator, calTopoSessionGateway, trackerPublisher);
            this.ownershipPeerCoordinator = peerCoordinator;
        }
    }
}

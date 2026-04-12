package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2cRuntimeOwnershipFixtureTest {

    @After
    fun tearDown() {
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun ownershipFixturesExposeSameSingleOwnerAcrossRuntimes() {
        val hub = OwnershipTestPeerHub()
        val alpha = TestR2cRuntimeFactory.createOwnershipFixture("alpha-runtime", hub)
        val bravo = TestR2cRuntimeFactory.createOwnershipFixture("bravo-runtime", hub)

        alpha.register()
        bravo.register()

        alpha.ownershipPeerCoordinator.start("map-1", "alpha", "Alpha", null)
        bravo.ownershipPeerCoordinator.start("map-1", "bravo", "Bravo", null)
        alpha.ownershipPeerCoordinator.updateCaltopoRtt(900)
        bravo.ownershipPeerCoordinator.updateCaltopoRtt(100)

        alpha.ownershipPeerCoordinator.observeRemoteId("RID-1", 20.0, 100)
        bravo.ownershipPeerCoordinator.observeRemoteId("RID-1", 200.0, 150)

        assertEquals("alpha", alpha.ownershipPeerCoordinator.getOwnerGuid("RID-1"))
        assertEquals("alpha", bravo.ownershipPeerCoordinator.getOwnerGuid("RID-1"))
        assertTrue(alpha.ownershipPeerCoordinator.isLocalOwner("RID-1"))
        assertFalse(bravo.ownershipPeerCoordinator.isLocalOwner("RID-1"))
    }

    @Test
    fun onlyOwningRuntimePublishesTrackPointsToCalTopo() {
        val hub = OwnershipTestPeerHub()
        val alpha = TestR2cRuntimeFactory.createOwnershipFixture("alpha-runtime", hub)
        val bravo = TestR2cRuntimeFactory.createOwnershipFixture("bravo-runtime", hub)

        alpha.ownershipPeerCoordinator.start("map-1", "alpha", "Alpha", null)
        bravo.ownershipPeerCoordinator.start("map-1", "bravo", "Bravo", null)
        alpha.ownershipPeerCoordinator.updateCaltopoRtt(900)
        bravo.ownershipPeerCoordinator.updateCaltopoRtt(100)

        alpha.ownershipPeerCoordinator.observeRemoteId("RID-1", 20.0, 100)
        bravo.ownershipPeerCoordinator.observeRemoteId("RID-1", 200.0, 150)

        assertTrue(alpha.ownershipPeerCoordinator.publishTrackPointIfOwner("RID-1", 35.0, 140.0, 25.0))
        assertFalse(bravo.ownershipPeerCoordinator.publishTrackPointIfOwner("RID-1", 35.0, 140.0, 25.0))

        assertEquals(1, alpha.calTopoSessionGateway.countOperations("startLiveTrack"))
        assertEquals(1, alpha.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
        assertEquals(0, bravo.calTopoSessionGateway.countOperations("startLiveTrack"))
        assertEquals(0, bravo.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
    }

    @Test
    fun visitingTeamOwnerPublishesToItsTrackerButNotHostsTracker() {
        val hub = OwnershipTestPeerHub()
        val host = TestR2cRuntimeFactory.createOwnershipFixture("host-runtime", hub)
        val partner = TestR2cRuntimeFactory.createOwnershipFixture("partner-runtime", hub)

        host.ownershipPeerCoordinator.start("map-1", "host", "Host", null)
        partner.ownershipPeerCoordinator.start("map-1", "partner", "Partner", null)
        host.ownershipPeerCoordinator.updateCaltopoRtt(900)
        partner.ownershipPeerCoordinator.updateCaltopoRtt(100)

        host.ownershipPeerCoordinator.observeRemoteId("RID-MA", 250.0, 100)
        partner.ownershipPeerCoordinator.observeRemoteId("RID-MA", 15.0, 100)

        val geoJson = """{"type":"FeatureCollection","features":[]}"""
        assertFalse(host.ownershipPeerCoordinator.publishTrackerIfOwner("RID-MA", geoJson))
        assertTrue(partner.ownershipPeerCoordinator.publishTrackerIfOwner("RID-MA", geoJson))

        assertEquals("partner", host.ownershipPeerCoordinator.getOwnerGuid("RID-MA"))
        assertEquals("partner", partner.ownershipPeerCoordinator.getOwnerGuid("RID-MA"))
        assertEquals(0, host.trackerPublisher.countPublications())
        assertEquals(1, partner.trackerPublisher.countPublications())
    }
}

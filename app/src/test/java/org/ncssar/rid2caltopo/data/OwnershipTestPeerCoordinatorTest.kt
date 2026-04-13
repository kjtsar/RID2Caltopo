package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipTestPeerCoordinatorTest {

    // --- Helpers ---

    private fun twoStarted(
        alphaGuid: String = "alpha",
        bravoGuid: String = "bravo",
    ): Triple<OwnershipTestPeerHub, OwnershipTestPeerCoordinator, OwnershipTestPeerCoordinator> {
        val hub = OwnershipTestPeerHub()
        val alpha = OwnershipTestPeerCoordinator("alpha-runtime", hub)
        val bravo = OwnershipTestPeerCoordinator("bravo-runtime", hub)
        alpha.start("map-1", alphaGuid, "Alpha", null)
        bravo.start("map-1", bravoGuid, "Bravo", null)
        return Triple(hub, alpha, bravo)
    }

    // --- Initial ownership ---

    @Test
    fun twoRuntimesAgreeOnSingleOwnerForSameDrone() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(900)
        bravo.updateCaltopoRtt(100)

        alpha.observeRemoteId("RID-1", 20.0, 100)
        bravo.observeRemoteId("RID-1", 200.0, 150)

        assertEquals("alpha", alpha.getOwnerGuid("RID-1"))
        assertEquals("alpha", bravo.getOwnerGuid("RID-1"))
        assertTrue(alpha.isLocalOwner("RID-1"))
        assertFalse(bravo.isLocalOwner("RID-1"))
    }

    @Test
    fun tieBreakFallsBackToGuidWhenScoreAndFirstSeenMatch() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-2", 100.0, 100)
        bravo.observeRemoteId("RID-2", 100.0, 100)

        assertEquals("alpha", alpha.getOwnerGuid("RID-2"))
        assertEquals("alpha", bravo.getOwnerGuid("RID-2"))
        assertTrue(alpha.isLocalOwner("RID-2"))
        assertFalse(bravo.isLocalOwner("RID-2"))
    }

    // --- Handoff: proximity change ---

    @Test
    fun handoff_closerPeerTakesOwnershipWhenDistanceUpdates() {
        // alpha owns initially because it is closer
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-1", 50.0, 100)
        bravo.observeRemoteId("RID-1", 500.0, 100)

        assertTrue(alpha.isLocalOwner("RID-1"))
        assertFalse(bravo.isLocalOwner("RID-1"))

        // drone moves — bravo is now much closer
        alpha.observeRemoteId("RID-1", 800.0, 100)
        bravo.observeRemoteId("RID-1", 10.0, 100)

        assertFalse(alpha.isLocalOwner("RID-1"))
        assertTrue(bravo.isLocalOwner("RID-1"))
        assertEquals("bravo", alpha.getOwnerGuid("RID-1"))
        assertEquals("bravo", bravo.getOwnerGuid("RID-1"))
    }

    @Test
    fun handoff_bothPeersAgreeOnNewOwnerAfterProximityChange() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-X", 30.0, 100)
        bravo.observeRemoteId("RID-X", 400.0, 100)

        assertEquals("alpha", alpha.getOwnerGuid("RID-X"))
        assertEquals("alpha", bravo.getOwnerGuid("RID-X"))

        // positions swap
        alpha.observeRemoteId("RID-X", 600.0, 100)
        bravo.observeRemoteId("RID-X", 15.0, 100)

        // both sides must agree
        assertEquals("bravo", alpha.getOwnerGuid("RID-X"))
        assertEquals("bravo", bravo.getOwnerGuid("RID-X"))
    }

    // --- Handoff: CalTopo RTT change ---

    @Test
    fun handoff_improvedRttTriggersTakeover() {
        // bravo wins initially because its RTT is better
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(2_000)
        bravo.updateCaltopoRtt(100)

        alpha.observeRemoteId("RID-2", 100.0, 100)
        bravo.observeRemoteId("RID-2", 100.0, 100)

        assertTrue(bravo.isLocalOwner("RID-2"))
        assertFalse(alpha.isLocalOwner("RID-2"))

        // alpha's CalTopo connection improves dramatically; next observation cycle propagates
        // the updated metrics — mirroring the production 4-second detect interval.
        alpha.updateCaltopoRtt(50)
        alpha.observeRemoteId("RID-2", 100.0, 100)
        bravo.observeRemoteId("RID-2", 100.0, 100)

        assertTrue(alpha.isLocalOwner("RID-2"))
        assertFalse(bravo.isLocalOwner("RID-2"))
        assertEquals("alpha", bravo.getOwnerGuid("RID-2"))
    }

    @Test
    fun handoff_rttChangeTriggersRecomputeForAllDrones() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(2_000)
        bravo.updateCaltopoRtt(100)

        // bravo wins both drones via RTT
        alpha.observeRemoteId("RID-A", 100.0, 100)
        bravo.observeRemoteId("RID-A", 100.0, 100)
        alpha.observeRemoteId("RID-B", 100.0, 100)
        bravo.observeRemoteId("RID-B", 100.0, 100)

        assertTrue(bravo.isLocalOwner("RID-A"))
        assertTrue(bravo.isLocalOwner("RID-B"))

        // alpha's RTT improves; next observation cycle propagates the updated metrics.
        alpha.updateCaltopoRtt(50)
        alpha.observeRemoteId("RID-A", 100.0, 100)
        bravo.observeRemoteId("RID-A", 100.0, 100)
        alpha.observeRemoteId("RID-B", 100.0, 100)
        bravo.observeRemoteId("RID-B", 100.0, 100)

        assertTrue(alpha.isLocalOwner("RID-A"))
        assertTrue(alpha.isLocalOwner("RID-B"))
        assertFalse(bravo.isLocalOwner("RID-A"))
        assertFalse(bravo.isLocalOwner("RID-B"))
    }

    // --- Handoff: peer goes offline ---

    @Test
    fun handoff_owningPeerDisconnects_remainingPeerTakesOwnership() {
        val (hub, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        // alpha owns because it was closer
        alpha.observeRemoteId("RID-3", 20.0, 100)
        bravo.observeRemoteId("RID-3", 300.0, 150)

        assertTrue(alpha.isLocalOwner("RID-3"))

        // alpha goes offline — its observations are removed from bravo's view
        alpha.onDroneLost("RID-3")
        hub.unregister(alpha)

        // bravo is now the only observer; it must take ownership
        assertTrue(bravo.isLocalOwner("RID-3"))
        assertEquals("bravo", bravo.getOwnerGuid("RID-3"))
    }

    @Test
    fun handoff_nonOwningPeerDisconnects_ownerIsUnchanged() {
        val (hub, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        // alpha owns because it is closer
        alpha.observeRemoteId("RID-4", 10.0, 100)
        bravo.observeRemoteId("RID-4", 400.0, 150)

        assertTrue(alpha.isLocalOwner("RID-4"))

        // bravo (non-owner) disconnects
        bravo.onDroneLost("RID-4")
        hub.unregister(bravo)

        // alpha retains ownership as the sole remaining observer
        assertTrue(alpha.isLocalOwner("RID-4"))
        assertEquals("alpha", alpha.getOwnerGuid("RID-4"))
    }

    // --- Drone lost and rediscovered ---

    @Test
    fun droneLost_thenRediscovered_ownershipReestablished() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-5", 20.0, 100)
        bravo.observeRemoteId("RID-5", 300.0, 150)

        assertTrue(alpha.isLocalOwner("RID-5"))

        // both peers lose the drone
        alpha.onDroneLost("RID-5")
        bravo.onDroneLost("RID-5")

        assertNull(alpha.getOwnerGuid("RID-5"))
        assertNull(bravo.getOwnerGuid("RID-5"))
        assertFalse(alpha.isLocalOwner("RID-5"))
        assertFalse(bravo.isLocalOwner("RID-5"))

        // drone reappears — bravo is now closest
        alpha.observeRemoteId("RID-5", 500.0, 200)
        bravo.observeRemoteId("RID-5", 15.0, 200)

        assertTrue(bravo.isLocalOwner("RID-5"))
        assertFalse(alpha.isLocalOwner("RID-5"))
        assertEquals("bravo", alpha.getOwnerGuid("RID-5"))
    }

    @Test
    fun droneLost_byOnePeer_otherPeerRetainsOwnership() {
        val (_, alpha, bravo) = twoStarted()
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        // alpha owns
        alpha.observeRemoteId("RID-6", 10.0, 100)
        bravo.observeRemoteId("RID-6", 300.0, 150)

        assertTrue(alpha.isLocalOwner("RID-6"))

        // only bravo loses sight of the drone; it removes its own observation and clears
        // its local owner state (no MQTT owner-claim message to update bravo's view of
        // who holds it now — that propagation only happens in the full MQTT path).
        bravo.onDroneLost("RID-6")

        // alpha still has an observation and correctly recomputes itself as owner
        assertTrue(alpha.isLocalOwner("RID-6"))
        // bravo shed its state cleanly — no stale ownership claim on its side
        assertNull(bravo.getOwnerGuid("RID-6"))
        assertFalse(bravo.isLocalOwner("RID-6"))
    }

    // --- Three-peer scenarios ---

    @Test
    fun threePeers_allAgreeOnClosestOwner() {
        val hub = OwnershipTestPeerHub()
        val alpha = OwnershipTestPeerCoordinator("alpha-runtime", hub)
        val bravo = OwnershipTestPeerCoordinator("bravo-runtime", hub)
        val charlie = OwnershipTestPeerCoordinator("charlie-runtime", hub)

        alpha.start("map-1", "alpha", "Alpha", null)
        bravo.start("map-1", "bravo", "Bravo", null)
        charlie.start("map-1", "charlie", "Charlie", null)

        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)
        charlie.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-7", 500.0, 100)
        bravo.observeRemoteId("RID-7", 50.0, 100)
        charlie.observeRemoteId("RID-7", 300.0, 100)

        // bravo is closest — all three must agree
        assertEquals("bravo", alpha.getOwnerGuid("RID-7"))
        assertEquals("bravo", bravo.getOwnerGuid("RID-7"))
        assertEquals("bravo", charlie.getOwnerGuid("RID-7"))
        assertTrue(bravo.isLocalOwner("RID-7"))
        assertFalse(alpha.isLocalOwner("RID-7"))
        assertFalse(charlie.isLocalOwner("RID-7"))
    }

    @Test
    fun threePeers_middlePeerDrops_remainingTwoAgreeOnNewOwner() {
        val hub = OwnershipTestPeerHub()
        val alpha = OwnershipTestPeerCoordinator("alpha-runtime", hub)
        val bravo = OwnershipTestPeerCoordinator("bravo-runtime", hub)
        val charlie = OwnershipTestPeerCoordinator("charlie-runtime", hub)

        alpha.start("map-1", "alpha", "Alpha", null)
        bravo.start("map-1", "bravo", "Bravo", null)
        charlie.start("map-1", "charlie", "Charlie", null)

        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)
        charlie.updateCaltopoRtt(500)

        // bravo is closest and owns
        alpha.observeRemoteId("RID-8", 400.0, 100)
        bravo.observeRemoteId("RID-8", 30.0, 100)
        charlie.observeRemoteId("RID-8", 200.0, 100)

        assertEquals("bravo", alpha.getOwnerGuid("RID-8"))
        assertTrue(bravo.isLocalOwner("RID-8"))

        // bravo (the owner) disconnects
        bravo.onDroneLost("RID-8")
        hub.unregister(bravo)

        // alpha and charlie must agree on a new owner — charlie is closer
        assertEquals("charlie", alpha.getOwnerGuid("RID-8"))
        assertEquals("charlie", charlie.getOwnerGuid("RID-8"))
        assertTrue(charlie.isLocalOwner("RID-8"))
        assertFalse(alpha.isLocalOwner("RID-8"))
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnershipTestPeerCoordinatorTest {

    @Test
    fun twoRuntimesAgreeOnSingleOwnerForSameDrone() {
        val hub = OwnershipTestPeerHub()
        val alpha = OwnershipTestPeerCoordinator("alpha-runtime", hub)
        val bravo = OwnershipTestPeerCoordinator("bravo-runtime", hub)

        alpha.start("map-1", "alpha", "Alpha", null)
        bravo.start("map-1", "bravo", "Bravo", null)
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
        val hub = OwnershipTestPeerHub()
        val alpha = OwnershipTestPeerCoordinator("alpha-runtime", hub)
        val bravo = OwnershipTestPeerCoordinator("bravo-runtime", hub)

        alpha.start("map-1", "alpha", "Alpha", null)
        bravo.start("map-1", "bravo", "Bravo", null)
        alpha.updateCaltopoRtt(500)
        bravo.updateCaltopoRtt(500)

        alpha.observeRemoteId("RID-2", 100.0, 100)
        bravo.observeRemoteId("RID-2", 100.0, 100)

        assertEquals("alpha", alpha.getOwnerGuid("RID-2"))
        assertEquals("alpha", bravo.getOwnerGuid("RID-2"))
        assertTrue(alpha.isLocalOwner("RID-2"))
        assertFalse(bravo.isLocalOwner("RID-2"))
    }
}

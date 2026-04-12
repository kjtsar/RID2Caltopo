package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerOwnershipEngineTest {

    private data class Observation(
        private val observerGuid: String,
        private val distMeters: Double,
        private val firstSeenTs: Long,
        private val expiresAtMs: Long = Long.MAX_VALUE
    ) : PeerOwnershipEngine.ObservationView {
        override fun getObserverGuid(): String = observerGuid
        override fun getDistMeters(): Double = distMeters
        override fun getFirstSeenTs(): Long = firstSeenTs
        override fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
    }

    @Test
    fun selectOwner_prefersHigherWeightedScore() {
        val engine = PeerOwnershipEngine()
        val now = 10_000L
        val result = engine.selectOwner(
            listOf(
                Observation("alpha", distMeters = 20.0, firstSeenTs = 100L),
                Observation("bravo", distMeters = 200.0, firstSeenTs = 50L)
            ),
            PeerOwnershipEngine.PeerMetricsLookup { guid ->
                if (guid == "alpha") 900L else 100L
            },
            now
        )

        assertEquals("alpha", result.ownerGuid)
    }

    @Test
    fun selectOwner_breaksTieByEarlierFirstSeenThenGuid() {
        val engine = PeerOwnershipEngine()
        val now = 10_000L

        val byFirstSeen = engine.selectOwner(
            listOf(
                Observation("bravo", distMeters = 100.0, firstSeenTs = 200L),
                Observation("alpha", distMeters = 100.0, firstSeenTs = 100L)
            ),
            PeerOwnershipEngine.PeerMetricsLookup { 500L },
            now
        )
        assertEquals("alpha", byFirstSeen.ownerGuid)

        val byGuid = engine.selectOwner(
            listOf(
                Observation("bravo", distMeters = 100.0, firstSeenTs = 100L),
                Observation("alpha", distMeters = 100.0, firstSeenTs = 100L)
            ),
            PeerOwnershipEngine.PeerMetricsLookup { 500L },
            now
        )
        assertEquals("alpha", byGuid.ownerGuid)
    }

    @Test
    fun selectOwner_ignoresExpiredObservations() {
        val engine = PeerOwnershipEngine()
        val now = 10_000L
        val result = engine.selectOwner(
            listOf(
                Observation("alpha", distMeters = 10.0, firstSeenTs = 1L, expiresAtMs = 5_000L),
                Observation("bravo", distMeters = 50.0, firstSeenTs = 2L)
            ),
            PeerOwnershipEngine.PeerMetricsLookup { 100L },
            now
        )

        assertEquals("bravo", result.ownerGuid)
    }

    @Test
    fun selectOwner_returnsNullWhenNoObservationsAreUsable() {
        val engine = PeerOwnershipEngine()
        val now = 10_000L
        val result = engine.selectOwner(
            listOf(
                Observation("alpha", distMeters = 10.0, firstSeenTs = 1L, expiresAtMs = 5_000L)
            ),
            PeerOwnershipEngine.PeerMetricsLookup { 100L },
            now
        )

        assertNull(result.ownerGuid)
    }
}

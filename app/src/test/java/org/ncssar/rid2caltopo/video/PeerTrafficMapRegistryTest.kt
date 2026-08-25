package org.ncssar.rid2caltopo.video

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrafficMapRegistryTest {
    @After
    fun tearDown() = PeerTrafficMapRegistry.clear()

    @Test
    fun `keeps the freshest sample and map fields without creating flight state`() {
        PeerTrafficMapRegistry.update(
            "zone-b", "RID-1", "Drone B", "rid", 4, 1_000, 1_050,
            39.1, -121.2, 820.0, 92.0, 18.5,
        )
        PeerTrafficMapRegistry.update(
            "zone-b", "RID-1", "Drone B", "rid", 3, 900, 1_060,
            39.0, -121.0, 810.0, 80.0, 12.0,
        )

        val point = PeerTrafficMapRegistry.points.value.values.single()
        assertEquals(1_000, point.sampleTimestampMsec)
        assertEquals(39.1, point.latitude, 0.0)
        assertEquals(92.0, point.headingDeg!!, 0.0)
        assertEquals(18.5, point.speedKnots!!, 0.0)
    }

    @Test
    fun `rejects invalid map positions`() {
        PeerTrafficMapRegistry.update(
            "zone-b", "RID-1", "Drone B", "rid", 1, 1_000, 1_050,
            0.0, 0.0, null, null, null,
        )

        assertTrue(PeerTrafficMapRegistry.points.value.isEmpty())
    }

    @Test
    fun `fresh peer sample replaces an older local sample immediately`() {
        val local = mapEntry(remoteId = "RID-1", timestampMsec = 1_000, latitude = 39.0)
        val peer = mapEntry(remoteId = "RID-1", timestampMsec = 1_001, latitude = 39.1)

        val selected = selectFreshestDroneMapEntries(listOf(local), listOf(peer))

        assertEquals(1, selected.size)
        assertEquals(39.1, selected.single().first.lat, 0.0)
    }

    @Test
    fun `equal or newer local sample wins over peer traffic`() {
        val local = mapEntry(remoteId = "RID-1", timestampMsec = 1_001, latitude = 39.0)
        val peer = mapEntry(remoteId = "RID-1", timestampMsec = 1_001, latitude = 39.1)

        val selected = selectFreshestDroneMapEntries(listOf(local), listOf(peer))

        assertEquals(1, selected.size)
        assertEquals(39.0, selected.single().first.lat, 0.0)
    }

    @Test
    fun `peer-only aircraft remains visible`() {
        val local = mapEntry(remoteId = "RID-1", timestampMsec = 1_000, latitude = 39.0)
        val peer = mapEntry(remoteId = "RID-2", timestampMsec = 1_000, latitude = 39.1)

        val selected = selectFreshestDroneMapEntries(listOf(local), listOf(peer))

        assertEquals(setOf("RID-1", "RID-2"), selected.map { it.first.remoteId }.toSet())
    }

    private fun mapEntry(
        remoteId: String,
        timestampMsec: Long,
        latitude: Double,
    ): Pair<DroneMapPoint, Boolean> = Pair(
        DroneMapPoint(
            designator = remoteId,
            remoteId = remoteId,
            lat = latitude,
            lng = -121.0,
            altitudeM = 500.0,
            timestampMsec = timestampMsec,
        ),
        false,
    )
}

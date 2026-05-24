package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

class WaypointTrackTest {
    private lateinit var trackMapField: Field
    private var originalTrackMap: MutableMap<Any, Any>? = null
    private var originalWaypointCount: Int = 0

    @Before
    fun setUp() {
        trackMapField = WaypointTrack::class.java.getDeclaredField("TrackMap").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        originalTrackMap = HashMap(trackMapField.get(null) as MutableMap<Any, Any>)
        originalWaypointCount = WaypointTrack.WaypointCount
        resetTracks()
        CtDroneSpec.MyLat = 0.0
        CtDroneSpec.MyLng = 0.0
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        val trackMap = trackMapField.get(null) as MutableMap<Any, Any>
        trackMap.clear()
        originalTrackMap?.let { trackMap.putAll(it) }
        WaypointTrack.WaypointCount = originalWaypointCount
        CtDroneSpec.MyLat = 0.0
        CtDroneSpec.MyLng = 0.0
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun addWaypointForTrack_recordsSnapshotInLatLngAltitudeTimestampOrder() {
        val drone = activeDrone("RID123", "RID123")

        WaypointTrack.AddWaypointForTrack(drone, 39.153061, -121.132946, 101L, 12_345L)

        val points = WaypointTrack.GetTrackPointsSnapshot(drone)
        assertEquals(1, points.size)
        assertEquals(39.153061, points[0].lat, 0.000001)
        assertEquals(-121.132946, points[0].lng, 0.000001)
        assertEquals(101.0, points[0].ele, 0.0)
        assertEquals(12_345L, points[0].timestampMsec)
        assertEquals(1, WaypointTrack.WaypointCount)
    }

    @Test
    fun renameTrack_movesExistingTrackToUpdatedDroneLabel() {
        val drone = activeDrone("RID456", "RID456")
        val oldTrackLabel = drone.trackLabel()
        WaypointTrack.AddWaypointForTrack(drone, 39.153061, -121.132946, 101L, 12_345L)

        drone.setMappedId("1sar7DjMn4Pr")

        assertTrue(oldTrackLabel != drone.trackLabel())
        assertEquals(emptyList<WaypointTrack.TrackPoint>(), WaypointTrack.GetTrackPointsSnapshot(CtDroneSpec("RID456")))
        val points = WaypointTrack.GetTrackPointsSnapshot(drone)
        assertEquals(1, points.size)
        assertEquals(12_345L, points.single().timestampMsec)
    }

    @Test
    fun renameTrack_mergesIntoExistingDestinationTrack() {
        val alpha = activeDrone("RID-A", "RID-A")
        WaypointTrack.AddWaypointForTrack(alpha, 39.153000, -121.132000, 100L, 1_000L)

        val bravo = activeDrone("RID-B", "RID-B")
        WaypointTrack.AddWaypointForTrack(bravo, 39.154000, -121.133000, 101L, 2_000L)

        WaypointTrack.RenameTrack(bravo.trackLabel(), alpha.trackLabel(), alpha)

        val points = WaypointTrack.GetTrackPointsSnapshot(alpha)
        assertEquals(2, points.size)
        assertEquals(listOf(1_000L, 2_000L), points.map { it.timestampMsec })
        assertEquals(emptyList<WaypointTrack.TrackPoint>(), WaypointTrack.GetTrackPointsSnapshot(bravo))
    }

    private fun activeDrone(remoteId: String, mappedId: String): CtDroneSpec {
        val drone = CtDroneSpec(remoteId, mappedId, "NCSSAR", "DJI Mini 4 Pro", "Pilot")
        assertTrue(
            drone.checkNewWaypoint(
                39.153061,
                -121.132946,
                100.0,
                1_000L,
                1_000L,
                true,
                CtDroneSpec.TransportTypeEnum.BT4
            )
        )
        return drone
    }

    private fun resetTracks() {
        @Suppress("UNCHECKED_CAST")
        val trackMap = trackMapField.get(null) as MutableMap<Any, Any>
        trackMap.clear()
        WaypointTrack.WaypointCount = 0
    }
}

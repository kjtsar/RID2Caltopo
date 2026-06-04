package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.WaypointTrack

class MapPaneLocalTrackSeedTest {
    @Test
    fun seedLocalTrackPointsFromSnapshot_backfillsFullFlightAndLatestRecentPoint() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.153000, -121.132000, 100.0, 1_000L),
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
            WaypointTrack.TrackPoint(39.155000, -121.134000, 102.0, 3_000L),
        )

        val changed = seedLocalTrackPointsFromSnapshot(
            mappedId = "1SAR7",
            snapshot = snapshot,
            receivedAtMsec = 10_000L,
            recentPoints = recent,
            flightPoints = flight
        )

        assertTrue(changed)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), flight.map { it.timestampMsec })
        assertEquals(listOf(3_000L), recent.map { it.timestampMsec })
        assertEquals("1SAR7", flight.first().mappedId)
    }

    @Test
    fun seedLocalTrackPointsFromSnapshot_doesNotDuplicateExistingFlightPoints() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(39.153000, -121.132000, 100.0, 1_000L),
            WaypointTrack.TrackPoint(39.154000, -121.133000, 101.0, 2_000L),
        )

        seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 10_000L, recent, flight)
        val changed = seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 11_000L, recent, flight)

        assertTrue(!changed)
        assertEquals(listOf(1_000L, 2_000L), flight.map { it.timestampMsec })
        assertEquals(listOf(2_000L), recent.map { it.timestampMsec })
    }

    @Test
    fun seedLocalTrackPointsFromSnapshot_ignoresInvalidCoordinates() {
        val recent = mutableListOf<LocalTrackPoint>()
        val flight = mutableListOf<LocalTrackPoint>()
        val snapshot = listOf(
            WaypointTrack.TrackPoint(0.0, 0.0, 100.0, 1_000L),
            WaypointTrack.TrackPoint(Double.NaN, -121.133000, 101.0, 2_000L),
        )

        val changed = seedLocalTrackPointsFromSnapshot("1SAR7", snapshot, 10_000L, recent, flight)

        assertTrue(!changed)
        assertEquals(emptyList<LocalTrackPoint>(), flight)
        assertEquals(emptyList<LocalTrackPoint>(), recent)
    }
}

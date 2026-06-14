import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec

class ClueCaptureSummaryTest {
    @Test
    fun buildClueCaptureSummary_includesUsngForClueWaypoint() {
        val summary = buildClueCaptureSummary(
            PendingClue(
                droneSpec = CtDroneSpec("RID123"),
                designator = "1SAR7",
                droneLat = 39.0,
                droneLng = -75.0,
                droneAlt = 120.0,
                lat = 39.9526,
                lng = -75.1652,
                alt = 102.0,
                headingDeg = 273.2,
                headingSourceLabel = "Camera yaw",
                aglMeters = 25.0,
                atoMeters = 40.0,
                gimbalAngleDeg = -45.0,
                timestamp = 1_000L,
                bitmap = null,
                preview = null,
                title = "Clue",
                description = ""
            )
        )

        assertTrue(summary.contains("  USNG: 18S VK "))
    }
}

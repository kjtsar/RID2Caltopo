import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
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
        assertTrue(summary.contains("  Gimbal angle at capture: -45.0°"))
        assertTrue(summary.contains("  AGL: 82'"))
        assertTrue(summary.contains("  ATO: 131'"))
        assertTrue(summary.contains("  Distance to clue:"))
    }

    @Test
    fun clueHeading_isNormalizedAndCannotRoundTo360() {
        assertEquals(1.0, normalizeClueHeading(361.0)!!, 0.0)
        assertEquals(359.0, normalizeClueHeading(-1.0)!!, 0.0)
        assertEquals("0.0", formatClueHeading(359.96))
        assertEquals("1.0", formatClueHeading(361.0))
    }

    @Test
    fun streamTelemetryDisplayStateUsesPairedMappedIdBeforeStreamDesignator() {
        val streamState = DroneDisplayState(
            headingDeg = 90.0,
            aglFt = 100.0,
            atoFt = 120.0
        )
        val pairedState = DroneDisplayState(
            headingDeg = 180.0,
            aglFt = 200.0,
            atoFt = 220.0
        )

        val resolved = streamTelemetryDisplayState(
            streamDesignator = "NCSSAR_MTRC4TD",
            pairedMappedId = "1SAR138DjMtrc4td",
            displayStateByDesignator = mapOf(
                "NCSSAR_MTRC4TD" to streamState,
                "1SAR138DjMtrc4td" to pairedState
            )
        )

        assertSame(pairedState, resolved)
    }

    @Test
    fun streamTelemetrySummaryLabelUsesPairedMappedIdBeforeStreamDesignator() {
        val droneSpec = CtDroneSpec("RID123").apply {
            setMappedId("1SAR138DjMtrc4td")
        }

        val label = streamTelemetrySummaryDesignatorLabel(
            streamDesignator = "NCSSAR_MTRC4TD",
            droneSpec = droneSpec
        )

        assertEquals("1SAR138DjMtrc4td", label)
    }
}

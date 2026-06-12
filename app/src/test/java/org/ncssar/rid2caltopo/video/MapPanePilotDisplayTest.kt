package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.PilotDisplayPreference

class MapPanePilotDisplayTest {
    @Test
    fun droneStatusLabel_includesExplicitUnitsAndHeading() {
        assertEquals(
            "ATO:125' AGL:90' RNG:420' HDG:273°",
            droneStatusLabelText(
                atoFeet = 125.2,
                aglFeet = 90.4,
                aglStale = false,
                rangeFeet = 420.0,
                headingDeg = 273.2
            )
        )
    }

    @Test
    fun droneStatusLabel_usesMissingTokensAndStaleAglMarker() {
        assertEquals(
            "ATO:--' AGL:75?' RNG:--' HDG:--°",
            droneStatusLabelText(
                atoFeet = null,
                aglFeet = 75.0,
                aglStale = true,
                rangeFeet = null,
                headingDeg = null
            )
        )
    }

    @Test
    fun droneStatusLabel_rejectsOutOfBandAltitudeTokens() {
        assertEquals(
            "ATO:--' AGL:--' RNG:1250' HDG:5°",
            droneStatusLabelText(
                atoFeet = 1_500.0,
                aglFeet = -1_500.0,
                aglStale = false,
                rangeFeet = 1_250.0,
                headingDeg = 365.0
            )
        )
    }

    @Test
    fun droneDetailLines_useRangeAndHeadingInCombinedPopup() {
        assertEquals(
            listOf(
                "Location: 39.12345, -121.12345 (Decimal Degrees)",
                "ATO: 121'",
                "AGL: 76?'",
                "RNG: 432'",
                "HDG: 218°"
            ),
            droneDetailLines(
                locationText = "39.12345, -121.12345",
                coordinateFormatLabel = "Decimal Degrees",
                atoFeet = 120.6,
                aglFeet = 75.5,
                aglStale = true,
                rangeFeet = 431.5,
                headingDeg = 217.7
            )
        )
    }

    @Test
    fun bearingLineToViewportEdge_extendsCardinalHeadingsToEdge() {
        val north = bearingLineToViewportEdge(50.0, 50.0, 0.0, 100, 100)
        assertEquals(50.0, north!!.endX, 0.01)
        assertEquals(0.0, north.endY, 0.01)

        val east = bearingLineToViewportEdge(50.0, 50.0, 90.0, 100, 100)
        assertEquals(100.0, east!!.endX, 0.01)
        assertEquals(50.0, east.endY, 0.01)

        val south = bearingLineToViewportEdge(50.0, 50.0, 180.0, 100, 100)
        assertEquals(50.0, south!!.endX, 0.01)
        assertEquals(100.0, south.endY, 0.01)

        val west = bearingLineToViewportEdge(50.0, 50.0, 270.0, 100, 100)
        assertEquals(0.0, west!!.endX, 0.01)
        assertEquals(50.0, west.endY, 0.01)
    }

    @Test
    fun bearingLineToViewportEdge_extendsDiagonalHeadingToFirstEdge() {
        val northeast = bearingLineToViewportEdge(50.0, 50.0, 45.0, 100, 100)

        assertEquals(100.0, northeast!!.endX, 0.01)
        assertEquals(0.0, northeast.endY, 0.01)
    }

    @Test
    fun bearingLineToViewportEdge_returnsNullForInvalidHeadingOrViewport() {
        assertNull(bearingLineToViewportEdge(50.0, 50.0, Double.NaN, 100, 100))
        assertNull(bearingLineToViewportEdge(50.0, 50.0, 90.0, 0, 100))
        assertNull(bearingLineToViewportEdge(50.0, 50.0, 90.0, 100, 0))
    }

    @Test
    fun pilotDisplayPreferencesByMappedId_fansOutToCurrentAndAliasedTracks() {
        val alphaPreference = PilotDisplayPreference(
            activeTrackColor = "#43A047",
            archiveTrackColor = "#8E24AA",
            bearingEnabled = true
        )
        val betaPreference = PilotDisplayPreference(
            activeTrackColor = "#E53935",
            archiveTrackColor = "#FB8C00",
            bearingEnabled = false
        )
        val mapped = pilotDisplayPreferencesByMappedId(
            dronePoints = listOf(
                DroneMapPoint(
                    designator = "ALPHA-M3",
                    remoteId = "rid-alpha",
                    lat = 39.0,
                    lng = -121.0,
                    altitudeM = 100.0,
                    timestampMsec = 1L,
                    droneSpec = CtDroneSpec("rid-alpha", "ALPHA-M3", "", "", "alpha")
                ),
                DroneMapPoint(
                    designator = "BETA-M3",
                    remoteId = "rid-beta",
                    lat = 39.1,
                    lng = -121.1,
                    altitudeM = 110.0,
                    timestampMsec = 2L,
                    droneSpec = CtDroneSpec("rid-beta", "BETA-M3", "", "", "beta")
                )
            ),
            mappedIdsByRemoteId = mapOf(
                "rid-alpha" to setOf("ALPHA-OLD", "ALPHA-M3"),
                "rid-beta" to setOf("BETA-OLD")
            )
        ) { pilotKey ->
            when (pilotKey) {
                "ALPHA" -> alphaPreference
                "BETA" -> betaPreference
                else -> PilotDisplayPreference()
            }
        }

        assertEquals(alphaPreference, mapped["ALPHA-M3"])
        assertEquals(alphaPreference, mapped["ALPHA-OLD"])
        assertEquals(betaPreference, mapped["BETA-M3"])
        assertEquals(betaPreference, mapped["BETA-OLD"])
    }
}

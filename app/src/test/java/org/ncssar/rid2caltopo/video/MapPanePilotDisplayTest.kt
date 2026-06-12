package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}

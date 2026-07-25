package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspaceMapOverlayAdapterTest {
    @Test
    fun buildsVisibleFacilityGridPolygonWithCeilingDetails() {
        val state = AirspaceUiState(
            records = listOf(
                FaaUasFacilityMapRecord(
                    objectId = 8862,
                    ceilingFeet = 200,
                    unit = "Feet",
                    primaryAirportFaaId = "BOI",
                    primaryAirportIcao = "KBOI",
                    primaryAirportName = "Boise Air Trml/Gowen Fld",
                    laancAvailable = true,
                    airspaceClasses = listOf("C"),
                    rings = listOf(
                        listOf(
                            AirspaceCoordinate(43.6083, -116.2083),
                            AirspaceCoordinate(43.6167, -116.2083),
                            AirspaceCoordinate(43.6167, -116.2000),
                            AirspaceCoordinate(43.6083, -116.2083)
                        )
                    )
                )
            )
        )

        val overlays = AirspaceMapOverlayAdapter.build(state)

        assertEquals(1, overlays.size)
        assertEquals(4, overlays.single().points.size)
        assertTrue(overlays.single().title.contains("Boise Air Trml/Gowen Fld"))
        assertTrue(overlays.single().title.contains("200 feet"))
    }

    @Test
    fun hidesFacilityGridWhenAirspaceStateIsNotVisible() {
        val state = AirspaceUiState(visible = false)
        assertTrue(AirspaceMapOverlayAdapter.build(state).isEmpty())
    }
}

package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspacePolicyTest {
    @Test
    fun fallonClassDRequiresAuthorizationAndShowsFacilityMapLimit() {
        val state = AirspacePolicy.buildUiState(
            records = listOf(
                FaaUasFacilityMapRecord(
                    objectId = 90600L,
                    ceilingFeet = 400,
                    unit = "Feet",
                    primaryAirportFaaId = "NFL",
                    primaryAirportIcao = "KNFL",
                    primaryAirportName = "Fallon NAS (Van Voorhis Fld)",
                    laancAvailable = true,
                    airspaceClasses = listOf("D")
                )
            ),
            loading = false,
            errorMessage = null
        )

        assertEquals(AirspaceChipSeverity.Danger, state.chipSeverity)
        assertEquals(
            "Airspace: Authorization required - Fallon NAS Class D; FAA grid limit 400 ft AGL",
            state.chipLabel
        )
        assertEquals("Fallon NAS Class D", state.summary)
        assertEquals(
            "Controlled airspace intersects the 1 mi operating area. FAA authorization is required before flight. " +
                "The displayed 400 ft AGL value is the lowest FAA UAS Facility Map limit across the area, " +
                "not the top of the controlled-airspace class. Requests above it require further FAA coordination.",
            state.detail
        )
    }

    @Test
    fun multipleFacilityMapGridsUseLowestLimitRegardlessOfResponseOrder() {
        val oneHundred = record(objectId = 2L, ceilingFeet = 100)
        val twoHundred = record(objectId = 1L, ceilingFeet = 200)

        val forward = AirspacePolicy.buildUiState(
            records = listOf(twoHundred, oneHundred),
            loading = false,
            errorMessage = null
        )
        val reverse = AirspacePolicy.buildUiState(
            records = listOf(oneHundred, twoHundred),
            loading = false,
            errorMessage = null
        )

        assertEquals(forward.chipLabel, reverse.chipLabel)
        assertEquals(
            "Airspace: Authorization required - Truckee Tahoe Airport Class D; FAA grid limit 100 ft AGL",
            forward.chipLabel
        )
        assertTrue(forward.detail.contains("lowest FAA UAS Facility Map limit"))
    }

    @Test
    fun unavailableAirspaceDataDoesNotClaimClear() {
        val state = AirspacePolicy.buildUiState(
            records = emptyList(),
            loading = false,
            errorMessage = "Controlled-airspace lookup unavailable"
        )

        assertEquals(AirspaceChipSeverity.Neutral, state.chipSeverity)
        assertEquals("Airspace unavailable", state.chipLabel)
    }

    private fun record(objectId: Long, ceilingFeet: Int) = FaaUasFacilityMapRecord(
        objectId = objectId,
        ceilingFeet = ceilingFeet,
        unit = "Feet",
        primaryAirportFaaId = "TRK",
        primaryAirportIcao = "KTRK",
        primaryAirportName = "Truckee Tahoe Airport",
        laancAvailable = true,
        airspaceClasses = listOf("D")
    )
}

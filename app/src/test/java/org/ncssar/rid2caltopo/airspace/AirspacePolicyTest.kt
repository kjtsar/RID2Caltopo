package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Test

class AirspacePolicyTest {
    @Test
    fun fallonClassDRequiresLaancAndShowsAutoApprovalCeiling() {
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

        assertEquals(AirspaceChipSeverity.Caution, state.chipSeverity)
        assertEquals("Airspace: LAANC required - Fallon NAS Class D up to 400 ft", state.chipLabel)
        assertEquals("Fallon NAS Class D", state.summary)
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
}

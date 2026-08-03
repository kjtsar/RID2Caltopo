package org.ncssar.rid2caltopo.notam

import org.ncssar.rid2caltopo.airspace.AirspaceChipSeverity
import org.ncssar.rid2caltopo.airspace.AirspaceUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotamPolicyTest {
    @Test
    fun toolbarUsesBriefAirspaceLabelWhileStateRetainsAirportDetails() {
        assertEquals(
            "Authorization required",
            conciseSafetyStatusLabel(
                useAirspaceLabel = true,
                severity = NotamChipSeverity.Danger,
                detailedLabel = "Airspace: Authorization required - KBOI Class C 100 ft grid"
            )
        )
        assertEquals(
            "Airspace nearby",
            conciseSafetyStatusLabel(
                useAirspaceLabel = true,
                severity = NotamChipSeverity.Caution,
                detailedLabel = "Airspace nearby - Gowen Field Class C 0.4 mi"
            )
        )
    }

    @Test
    fun pendingAirspaceStatusOverridesClearNotamLabel() {
        assertTrue(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Neutral,
                    chipLabel = "Airspace updating..."
                )
            )
        )
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Normal,
                    chipLabel = "Airspace clear"
                )
            )
        )
    }

    @Test
    fun normalAirspaceDoesNotMaskVisibleDisabledNotamStatus() {
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    chipSeverity = AirspaceChipSeverity.Normal,
                    chipLabel = "Airspace clear"
                )
            )
        )
    }

    @Test
    fun intersectingNonRestrictiveNoticeIsCautionNoticeNotRedRestriction() {
        val notice = NearbyNotam(
            id = "KZSE-6-7414",
            title = "Airspace service notice",
            summary = "Service unusable below 8000 ft.",
            distanceNm = 0.0,
            intersectsPilotBubble = true,
            severity = NotamChipSeverity.Normal
        )

        assertEquals(
            NotamChipSeverity.Caution,
            NotamPolicy.effectiveChipSeverity(
                notices = listOf(notice),
                configured = true,
                hasError = false
            )
        )
        assertEquals(
            "NOTAMs: NOTICE 0.0 mi",
            NotamPolicy.chipLabel(
                notices = listOf(notice),
                configured = true,
                loading = false,
                hasError = false
            )
        )
    }

    @Test
    fun intersectingDangerNoticeKeepsRedRestrictedLabel() {
        val notice = NearbyNotam(
            id = "restricted",
            title = "UAS airspace restriction",
            summary = "Restriction from surface to 8000 ft.",
            distanceNm = 0.0,
            intersectsPilotBubble = true,
            severity = NotamChipSeverity.Danger
        )

        assertEquals(
            NotamChipSeverity.Danger,
            NotamPolicy.effectiveChipSeverity(
                notices = listOf(notice),
                configured = true,
                hasError = false
            )
        )
        assertEquals(
            "NOTAMs: RESTRICTED 0.0 mi",
            NotamPolicy.chipLabel(
                notices = listOf(notice),
                configured = true,
                loading = false,
                hasError = false
            )
        )
    }

    @Test
    fun hiddenAirspaceDoesNotOverrideDisabledNotamStatus() {
        assertFalse(
            shouldUseAirspaceStatus(
                notamVisible = true,
                airspaceState = AirspaceUiState(
                    visible = false,
                    chipSeverity = AirspaceChipSeverity.Danger,
                    chipLabel = "Airspace warning"
                )
            )
        )
    }

    @Test
    fun oneStatuteMileRadiusDoesNotIncludeOneNauticalMileNotice() {
        val inside = NearbyNotam(
            id = "inside",
            title = "Inside",
            summary = "",
            distanceNm = 0.86
        )
        val outside = NearbyNotam(
            id = "outside",
            title = "Outside",
            summary = "",
            distanceNm = 1.0
        )

        val (visible, suppressed) = NotamPolicy.filterWithinRadius(
            notices = listOf(inside, outside),
            radiusStatuteMiles = 1
        )

        assertEquals(listOf(inside), visible)
        assertEquals(1, suppressed)
    }
}

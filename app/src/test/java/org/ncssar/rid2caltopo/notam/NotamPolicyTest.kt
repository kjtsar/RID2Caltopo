package org.ncssar.rid2caltopo.notam

import org.junit.Assert.assertEquals
import org.junit.Test

class NotamPolicyTest {
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
            "NOTAMs: NOTICE 0.0 NM",
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
            "NOTAMs: RESTRICTED 0.0 NM",
            NotamPolicy.chipLabel(
                notices = listOf(notice),
                configured = true,
                loading = false,
                hasError = false
            )
        )
    }
}

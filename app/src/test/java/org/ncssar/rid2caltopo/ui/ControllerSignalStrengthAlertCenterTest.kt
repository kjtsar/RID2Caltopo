package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerSignalStrengthAlertCenterTest {
    @After
    fun tearDown() {
        ControllerSignalStrengthAlertCenter.resetForTests()
        SpokenWarningCenter.resetForTests()
    }

    @Test
    fun update_ignoresWeakSignalWhenNoLiveStreamIsPresent() {
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 0,
            signalPercent = 40,
            nowMs = 1_000L
        )

        assertNull(ControllerSignalStrengthAlertCenter.uiState.value)
        assertNull(SpokenWarningCenter.requests.value)
    }

    @Test
    fun update_requiresWeakSignalToPersistBeforeSpeaking() {
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 1_000L
        )

        assertNull(ControllerSignalStrengthAlertCenter.uiState.value)
        assertNull(SpokenWarningCenter.requests.value)

        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 4_999L
        )

        assertNull(ControllerSignalStrengthAlertCenter.uiState.value)
        assertNull(SpokenWarningCenter.requests.value)

        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 5_000L
        )

        val alert = ControllerSignalStrengthAlertCenter.uiState.value
        assertEquals(55, alert?.signalPercent)
        assertEquals(1, alert?.liveStreamCount)
        assertEquals(
            SpokenWarningKind.ControllerSignalStrength,
            SpokenWarningCenter.requests.value?.kind
        )
    }

    @Test
    fun update_suppressesRepeatedSpeechInsideCooldown() {
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 1_000L
        )
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 5_000L
        )
        val firstRequest = SpokenWarningCenter.requests.value

        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 50,
            nowMs = 20_000L
        )

        assertTrue(firstRequest === SpokenWarningCenter.requests.value)
    }

    @Test
    fun update_repeatsSpeechAfterCooldownIfSignalStillWeak() {
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 1_000L
        )
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 5_000L
        )
        val firstRequest = SpokenWarningCenter.requests.value

        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 50,
            nowMs = 35_000L
        )

        assertEquals(
            SpokenWarningKind.ControllerSignalStrength,
            SpokenWarningCenter.requests.value?.kind
        )
        assertTrue(firstRequest !== SpokenWarningCenter.requests.value)
    }

    @Test
    fun update_clearsProblemWithoutSpeakingRecoveryWhenSignalRestores() {
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 1_000L
        )
        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 55,
            nowMs = 5_000L
        )
        val problemRequest = SpokenWarningCenter.requests.value

        ControllerSignalStrengthAlertCenter.update(
            liveStreamCount = 1,
            signalPercent = 70,
            nowMs = 6_000L
        )

        assertNull(ControllerSignalStrengthAlertCenter.uiState.value)
        assertTrue(problemRequest === SpokenWarningCenter.requests.value)
    }
}

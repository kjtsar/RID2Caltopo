package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.ncssar.rid2caltopo.data.FakePeerCoordinator
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.TestR2cRuntimeFactory

class ComplianceAlertCenterTest {
    @After
    fun tearDown() {
        ComplianceAlertCenter.resetForTests()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun freshOverLimitAltitudeCreatesAlert() {
        val nowMs = 100_000L
        setLocallyOwned("DRONE1")

        ComplianceAlertCenter.updateCandidates(
            listOf(candidate(aglFt = 215.0, telemetryTimestampMs = nowMs - 1_000L)),
            nowMs = nowMs,
        )

        assertNotNull(ComplianceAlertCenter.uiState.value)
    }

    @Test
    fun staleOverLimitAltitudeClearsExistingAlert() {
        val nowMs = 100_000L
        setLocallyOwned("DRONE1")
        ComplianceAlertCenter.updateCandidates(
            listOf(candidate(aglFt = 215.0, telemetryTimestampMs = nowMs - 1_000L)),
            nowMs = nowMs,
        )
        assertNotNull(ComplianceAlertCenter.uiState.value)

        ComplianceAlertCenter.updateCandidates(
            listOf(
                candidate(
                    aglFt = 215.0,
                    telemetryTimestampMs = nowMs - ComplianceAlertCenter.MAX_ALTITUDE_SAMPLE_AGE_MS - 1L,
                )
            ),
            nowMs = nowMs,
        )

        assertNull(ComplianceAlertCenter.uiState.value)
    }

    private fun setLocallyOwned(remoteId: String) {
        val fixture = TestR2cRuntimeFactory.create("compliance-alert")
        fixture.setAsDefaultRuntime()
        (fixture.peerCoordinator as FakePeerCoordinator).setLocalOwnership(remoteId, true)
    }

    private fun candidate(
        aglFt: Double,
        telemetryTimestampMs: Long,
    ) = ComplianceAlertCandidate(
        remoteId = "DRONE1",
        mappedId = "1sar7mn4pr",
        aglFt = aglFt,
        thresholdFt = 200.0,
        staleDem = false,
        telemetryTimestampMs = telemetryTimestampMs,
    )
}

package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.FakePeerCoordinator
import org.ncssar.rid2caltopo.data.R2cRuntimeRegistry
import org.ncssar.rid2caltopo.data.TestR2cRuntimeFactory

class ProximityAlertHostThresholdTest {
    @After
    fun tearDown() {
        ProximityAlertCenter.resetForTests()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun exactHorizontalAndVerticalThresholdsStillCountAsInside() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 40.0,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 56.57,
            currentThreeDFt = 60.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertTrue(decision.insideThreshold)
        assertTrue(decision.crossedIntoThreshold)
        assertTrue(decision.shouldAlert)
        assertFalse(decision.highSeverity)
        assertEquals(1.0, decision.severityScore, 0.0001)
    }

    @Test
    fun altitudeAboveThresholdBlocksAlertEvenWhenHorizontalIsInside() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 40.1,
            effectiveThreeDFt = 44.82,
            currentThreeDFt = 50.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertFalse(decision.insideThreshold)
        assertFalse(decision.shouldAlert)
        assertTrue(decision.highSeverity)
    }

    @Test
    fun nonTeamPairsIgnoreVerticalSeparationForTrafficProximity() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 400.0,
            effectiveThreeDFt = 400.5,
            currentThreeDFt = 400.5,
            thresholdFt = 40.0,
            predictionEnabled = true,
            altitudeSensitive = false
        )

        assertTrue(decision.insideThreshold)
        assertTrue(decision.shouldAlert)
        assertTrue(decision.highSeverity)
        assertEquals(0.5, decision.severityScore, 0.0001)
    }

    @Test
    fun proximityAlertRequiresAtLeastOneOwnedDroneButAllowsNonOwnedTraffic() {
        assertTrue(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = true,
                secondTeamDrone = true,
                secondLocalAlertEligible = false
            )
        )
        assertTrue(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = true,
                secondTeamDrone = false,
                secondLocalAlertEligible = false
            )
        )
        assertFalse(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = true,
                firstLocalAlertEligible = false,
                secondTeamDrone = false,
                secondLocalAlertEligible = false
            )
        )
        assertFalse(
            ProximityAlertCenter.shouldAlertForPairForTests(
                firstTeamDrone = false,
                firstLocalAlertEligible = true,
                secondTeamDrone = false,
                secondLocalAlertEligible = true
            )
        )
    }

    @Test
    fun updateDronesAlertsWhenOwnedDroneIsNearNonOwnedTraffic() {
        CaltopoClient.SetProximityAlertSpacingFeet(40)
        CaltopoClient.SetPredictiveHeadEnabled(false)
        val fixture = TestR2cRuntimeFactory.create("proximity-owner")
        fixture.setAsDefaultRuntime()
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        ProximityAlertCenter.resetForTests()

        val owned = proximityDrone(
            remoteId = "TEAMDRONE1",
            mappedId = "1sar7mn4pr",
            lat = 39.153099,
            lng = -121.132858,
            altMeters = 527.0,
            localArchiveOnly = false
        )
        val traffic = proximityDrone(
            remoteId = "TRAFFIC1",
            mappedId = "traffic1",
            lat = 39.153099,
            lng = -121.132828,
            altMeters = 544.5,
            localArchiveOnly = true
        )

        ProximityAlertCenter.updateDrones(listOf(owned, traffic))

        assertNull(ProximityAlertCenter.uiState.value)
        assertFalse(ProximityAlertCenter.debugPairs.value.single().alerting)

        peerCoordinator.setLocalOwnership("TEAMDRONE1", true)
        ProximityAlertCenter.resetForTests()
        ProximityAlertCenter.updateDrones(listOf(owned, traffic))

        val alert = ProximityAlertCenter.uiState.value
        assertNotNull(alert)
        assertEquals("1sar7mn4pr", alert?.nearestDroneMappedId)
        assertTrue(ProximityAlertCenter.debugPairs.value.single().alerting)

        ProximityAlertCenter.resetForTests()
        val otherTraffic = proximityDrone(
            remoteId = "TRAFFIC2",
            mappedId = "traffic2",
            lat = 39.153099,
            lng = -121.132858,
            altMeters = 527.0,
            localArchiveOnly = true
        )

        ProximityAlertCenter.updateDrones(listOf(traffic, otherTraffic))

        assertNull(ProximityAlertCenter.uiState.value)
        assertFalse(ProximityAlertCenter.debugPairs.value.single().alerting)
    }

    @Test
    fun highSeverityRequiresDroppingBelowSeventyFivePercentThreshold() {
        val atBoundary = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 30.0,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 50.0,
            currentThreeDFt = 55.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )
        val belowBoundary = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 29.9,
            effectiveVerticalFt = 40.0,
            effectiveThreeDFt = 49.93,
            currentThreeDFt = 55.0,
            thresholdFt = 40.0,
            predictionEnabled = true
        )

        assertFalse(atBoundary.highSeverity)
        assertTrue(belowBoundary.highSeverity)
    }

    @Test
    fun nonPredictiveModeDoesNotReAlertWhenPairIsStillInsideButSeparating() {
        val decision = ProximityAlertCenter.evaluateThresholdDecisionForTests(
            effectiveHorizontalFt = 20.0,
            effectiveVerticalFt = 10.0,
            effectiveThreeDFt = 22.36,
            currentThreeDFt = 22.36,
            thresholdFt = 40.0,
            predictionEnabled = false,
            previousHorizontalFt = 18.0,
            previousVerticalFt = 8.0,
            previousThreeDFt = 19.7
        )

        assertTrue(decision.insideThreshold)
        assertFalse(decision.crossedIntoThreshold)
        assertTrue(decision.isGettingFartherApart)
        assertFalse(decision.actuallyApproaching)
        assertFalse(decision.shouldAlert)
    }

    private fun proximityDrone(
        remoteId: String,
        mappedId: String,
        lat: Double,
        lng: Double,
        altMeters: Double,
        localArchiveOnly: Boolean
    ): CtDroneSpec {
        return CtDroneSpec(remoteId).apply {
            setMappedId(mappedId)
            setLocalArchiveOnly(localArchiveOnly)
            lastLat = lat
            lastLng = lng
            lastAlt = altMeters
            mostRecentMsecTimestamp = System.currentTimeMillis()
        }
    }
}

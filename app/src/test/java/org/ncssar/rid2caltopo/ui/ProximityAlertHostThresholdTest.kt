package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityAlertHostThresholdTest {
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
}

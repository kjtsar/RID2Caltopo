package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DroneSignalLossAlertCenterTest {
    @Test
    fun spokenWarningGate_doesNotReplayInitialRetainedAlert() {
        val gate = DroneSignalLossSpokenWarningGate(initialFlightKey = "flight-a")

        assertEquals(false, gate.shouldRequestWarning("flight-a"))
    }

    @Test
    fun spokenWarningGate_requestsWarningForNewAlertAfterHostStarts() {
        val gate = DroneSignalLossSpokenWarningGate(initialFlightKey = null)

        assertEquals(true, gate.shouldRequestWarning("flight-a"))
    }

    @Test
    fun spokenWarningGate_allowsAlertAfterClearingPreviousAlert() {
        val gate = DroneSignalLossSpokenWarningGate(initialFlightKey = "flight-a")

        assertEquals(false, gate.shouldRequestWarning(null))
        assertEquals(true, gate.shouldRequestWarning("flight-b"))
    }

    @Test
    fun effectiveIdleThreshold_keepsBootstrapFloorAfterCadenceIsLearned() {
        val thresholdMs = DroneSignalLossAlertCenter.effectiveIdleThresholdMsForTests(
            learnedIntervalMs = 2_231L,
            learnedSamples = 57,
            maxTrackDelayMs = 30_000L
        )

        assertEquals(10_000L, thresholdMs)
    }

    @Test
    fun effectiveIdleThreshold_usesDynamicThresholdWhenAboveBootstrapFloor() {
        val thresholdMs = DroneSignalLossAlertCenter.effectiveIdleThresholdMsForTests(
            learnedIntervalMs = 6_000L,
            learnedSamples = 3,
            maxTrackDelayMs = 30_000L
        )

        assertEquals(15_000L, thresholdMs)
    }

    @Test
    fun peerVisibleTelemetry_suppressesLocalSignalGap() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.shouldSuppressForPeerVisibleTelemetryForTests(
                signalIdleMs = 15_000L,
                trackTelemetryIdleMs = 1_000L,
                thresholdMs = 10_000L
            )
        )
    }

    @Test
    fun peerVisibleTelemetry_keepsTrueMeshSilenceEligible() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.shouldSuppressForPeerVisibleTelemetryForTests(
                signalIdleMs = 15_000L,
                trackTelemetryIdleMs = 15_000L,
                thresholdMs = 10_000L
            )
        )
    }

    @Test
    fun peerVisibleTelemetry_suppressesAtPeerTelemetryThresholdBoundary() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.shouldSuppressForPeerVisibleTelemetryForTests(
                signalIdleMs = 15_000L,
                trackTelemetryIdleMs = 10_000L,
                thresholdMs = 10_000L
            )
        )
    }

    @Test
    fun peerVisibleTelemetry_doesNotSuppressWhenLocalSignalIsAtThresholdBoundary() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.shouldSuppressForPeerVisibleTelemetryForTests(
                signalIdleMs = 10_000L,
                trackTelemetryIdleMs = 1_000L,
                thresholdMs = 10_000L
            )
        )
    }

    @Test
    fun returnedToBridge_requiresPriorBridgeVerification() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isReturnedToBridgeForTests(
                bridgeVerified = false,
                distanceFt = 10.0,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun returnedToBridge_detectsVerifiedDroneInsideBridgeRadius() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.isReturnedToBridgeForTests(
                bridgeVerified = true,
                distanceFt = 20.0,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun returnedToBridge_keepsVerifiedDroneEligibleOutsideBridgeRadius() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isReturnedToBridgeForTests(
                bridgeVerified = true,
                distanceFt = 20.1,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun stationaryNearBridge_requiresBridgeVerification() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isStationaryNearBridgeForTests(
                bridgeVerified = false,
                stationaryRidReports = true,
                referenceDistanceFt = 20.0,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun stationaryNearBridge_suppressesVerifiedStationaryDroneInsideBridgeRadius() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.isStationaryNearBridgeForTests(
                bridgeVerified = true,
                stationaryRidReports = true,
                referenceDistanceFt = 20.0,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun stationaryNearBridge_keepsStationaryDroneEligibleOutsideBridgeRadius() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isStationaryNearBridgeForTests(
                bridgeVerified = true,
                stationaryRidReports = true,
                referenceDistanceFt = 56.7,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun stationaryNearBridge_keepsMovingDroneEligible() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isStationaryNearBridgeForTests(
                bridgeVerified = true,
                stationaryRidReports = false,
                referenceDistanceFt = 20.0,
                bridgeCheckDistanceFt = 20.0
            )
        )
    }

    @Test
    fun returnedToTakeoff_requiresBridgeVerification() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isReturnedToTakeoffForTests(
                bridgeVerified = false,
                takeoffDistanceFt = 10.0,
                returnToTakeoffDistanceFt = 30.0
            )
        )
    }

    @Test
    fun returnedToTakeoff_detectsVerifiedDroneInsideTakeoffRadius() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.isReturnedToTakeoffForTests(
                bridgeVerified = true,
                takeoffDistanceFt = 30.0,
                returnToTakeoffDistanceFt = 30.0
            )
        )
    }

    @Test
    fun returnedToTakeoff_keepsVerifiedDroneEligibleOutsideTakeoffRadius() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isReturnedToTakeoffForTests(
                bridgeVerified = true,
                takeoffDistanceFt = 30.1,
                returnToTakeoffDistanceFt = 30.0
            )
        )
    }

    @Test
    fun bridgeRecentlySeen_remainsTrueThroughFreshnessBoundary() {
        assertEquals(
            true,
            DroneSignalLossAlertCenter.isBridgeRecentlySeenForTests(
                lastSeenMonotonicMs = 1_000L,
                nowMonotonicMs = 33_000L,
                freshnessMs = 32_000L
            )
        )
    }

    @Test
    fun bridgeRecentlySeen_turnsFalseAfterFreshnessBoundary() {
        assertEquals(
            false,
            DroneSignalLossAlertCenter.isBridgeRecentlySeenForTests(
                lastSeenMonotonicMs = 1_000L,
                nowMonotonicMs = 33_001L,
                freshnessMs = 32_000L
            )
        )
    }
}

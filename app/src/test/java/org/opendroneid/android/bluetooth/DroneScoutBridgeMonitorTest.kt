package org.opendroneid.android.bluetooth

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.ui.shouldMonitorDroneScoutBridgeAlerts

class DroneScoutBridgeMonitorTest {
    @After
    fun tearDown() {
        DroneScoutBridgeMonitor.resetForTests()
    }

    @Test
    fun recognizesDefaultRelayPingIdentity() {
        assertTrue(DroneScoutBridgeMonitor.isRelayPingIdentity("DroneScout Bridge"))
        assertTrue(DroneScoutBridgeMonitor.isRelayPingIdentity("dronescout_bridge 01"))
        assertFalse(DroneScoutBridgeMonitor.isRelayPingIdentity("1581F67QE239L00A00DE"))
    }

    @Test
    fun exposesFreshSignalAndAgesItOut() {
        assertEquals(32_000L, DroneScoutBridgeMonitor.SIGNAL_STALE_AFTER_MS)
        assertEquals(32_000L, DroneScoutBridgeMonitor.LOSS_ANNOUNCEMENT_AFTER_MS)
        DroneScoutBridgeMonitor.noteCandidate("DroneScout Bridge", -67, 1_000L)
        val signal = DroneScoutBridgeMonitor.signal.value

        assertEquals(-67, DroneScoutBridgeMonitor.currentRssi(signal, 1_001L))
        assertEquals(1L, signal?.eventCount)
        assertNull(
            DroneScoutBridgeMonitor.currentRssi(
                signal,
                1_000L + DroneScoutBridgeMonitor.SIGNAL_STALE_AFTER_MS + 1L,
            )
        )
    }

    @Test
    fun relayedAircraftPacketsRefreshBridgeAndIncrementCounter() {
        DroneScoutBridgeMonitor.noteCandidate("DroneScout Bridge", -51, 1_000L)
        DroneScoutBridgeMonitor.noteRelayedPacket(-63, 2_000L)

        val signal = DroneScoutBridgeMonitor.signal.value
        assertEquals(-63, signal?.rssiDbm)
        assertEquals(2_000L, signal?.lastSeenMonotonicMs)
        assertEquals(2L, signal?.eventCount)
    }

    @Test
    fun lossGateAnnouncesOncePerLossAndResetsAfterPing() {
        val gate = DroneScoutBridgeLossAnnouncementGate()

        assertFalse(gate.shouldAnnounce(true, null, 1_000L, muted = false))
        assertFalse(gate.shouldAnnounce(true, null, 33_000L, muted = false))
        assertTrue(gate.shouldAnnounce(true, null, 33_001L, muted = false))
        assertFalse(gate.shouldAnnounce(true, null, 40_000L, muted = false))
        assertFalse(gate.shouldAnnounce(true, 40_000L, 40_001L, muted = false))
        assertTrue(gate.shouldAnnounce(true, 40_000L, 72_001L, muted = false))
    }

    @Test
    fun lossGateHonorsMuteAndMonitoringState() {
        val gate = DroneScoutBridgeLossAnnouncementGate()

        assertFalse(gate.shouldAnnounce(true, null, 1_000L, muted = false))
        assertFalse(gate.shouldAnnounce(true, null, 33_001L, muted = true))
        assertFalse(gate.shouldAnnounce(true, null, 40_000L, muted = false))
        assertFalse(gate.shouldAnnounce(false, null, 41_000L, muted = false))
        assertFalse(gate.shouldAnnounce(true, null, 40_000L, muted = false))
        assertTrue(gate.shouldAnnounce(true, null, 72_001L, muted = false))
    }

    @Test
    fun spokenBridgeMonitoringRequiresAnActiveFlight() {
        assertFalse(shouldMonitorDroneScoutBridgeAlerts(scannerRunning = false, activeFlightCount = 1))
        assertFalse(shouldMonitorDroneScoutBridgeAlerts(scannerRunning = true, activeFlightCount = 0))
        assertTrue(shouldMonitorDroneScoutBridgeAlerts(scannerRunning = true, activeFlightCount = 1))
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TrackerPeerCoordinatorTest {

    private class FakeTransport : TrackerCoordinationTransport {
        private var transportCallback: TrackerCoordinationTransport.Callback? = null
        val sentMessages = CopyOnWriteArrayList<String>()
        var connected = false
        var connectCount = 0
        var autoOpen = true

        override fun setCallback(callback: TrackerCoordinationTransport.Callback?) {
            this.transportCallback = callback
        }

        override fun connect(websocketUrl: String, apiKey: String?) {
            connectCount++
            if (autoOpen) {
                open()
            }
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String) {
            sentMessages += text
        }

        fun open() {
            connected = true
            transportCallback?.onOpen()
        }

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            transportCallback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
        }
    }

    private class FakeClock(var nowMs: Long = 1_000L) : TrackerPeerCoordinator.TimeSource {
        override fun now(): Long = nowMs

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }

    private class FakeLiveTrack(private val remoteId: String) : LiveTrackOwnerDelegate {
        var localOwnerFlag = false
        var peerWaypointCount = 0

        override fun getRemoteId(): String = remoteId

        override fun setLocalOwner(isOwner: Boolean) {
            localOwnerFlag = isOwner
        }

        override fun onPeerWaypoint(
            sourceZoneId: String,
            lat: Double,
            lng: Double,
            altitudeMeters: Double,
            timestampMsec: Long,
            telemetry: CtDroneSpec.PositionTelemetry?
        ) {
            peerWaypointCount++
        }
    }

    private lateinit var transport: FakeTransport
    private lateinit var coordinator: TrackerPeerCoordinator
    private lateinit var clock: FakeClock

    @Before
    fun setUp() {
        transport = FakeTransport()
        clock = FakeClock()
        TrackerPeerCoordinator.setTransportFactoryForTesting { transport }
        TrackerPeerCoordinator.setTrackerConfigForTesting("https://tracker.example.org", "tracker-token")
        TrackerPeerCoordinator.setHandoffDelayMsForTesting(0L)
        TrackerPeerCoordinator.setTimeSourceForTesting(clock)
        coordinator = TrackerPeerCoordinator.getInstance()
    }

    @After
    fun tearDown() {
        TrackerPeerCoordinator.resetForTesting()
    }

    @Test
    fun start_connectsTransport() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        assertTrue(transport.connected)
    }

    @Test
    fun ownerAssignedToLocalZone_setsLocalOwner() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun relaySightingForOwnedDrone_isForwardedToLiveTrack() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        coordinator.handleRelaySightingForTesting(
            "DRONE1",
            "zone-bravo",
            39.1,
            -121.2,
            123.4,
            9876L,
            null
        )

        assertEquals(1, track.peerWaypointCount)
        assertFalse(track.localOwnerFlag.not())
    }

    @Test
    fun selfRelayedSighting_isIgnored() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)

        coordinator.handleRelaySightingForTesting(
            "DRONE1",
            "zone-alpha",
            39.1,
            -121.2,
            123.4,
            9876L,
            null
        )

        assertEquals(0, track.peerWaypointCount)
    }

    @Test
    fun hardAuthFailure_notifiesListenerOnce() {
        var failureCount = 0
        var lastCode = 0
        coordinator.setHardFailureListenerForTesting { responseCode, _ ->
            failureCount++
            lastCode = responseCode
        }

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.fail(403, "Forbidden")
        transport.fail(403, "Forbidden")

        assertEquals(1, failureCount)
        assertEquals(403, lastCode)
    }

    @Test
    fun staleOwnerAssignment_doesNotOverrideNewerLease() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 9L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 8L)

        assertTrue(track.localOwnerFlag)
        assertTrue(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun newerPeerOwnerAssignment_clearsLocalOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 5L)

        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun ownerExpired_clearsLocalOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        assertTrue(track.localOwnerFlag)

        coordinator.handleOwnerExpiredForTesting("DRONE1")

        assertFalse(track.localOwnerFlag)
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun missedHelloAck_forcesReconnectDeterministically() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        awaitTrue("expected reconnect attempt after missed hello ack") {
            transport.connectCount >= 2
        }
    }

    @Test
    fun missedHeartbeatAck_forcesReconnectDeterministically() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(9L, clock.now())
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()

        assertEquals(1L, coordinator.getForcedReconnectCountForTesting())
        awaitTrue("expected reconnect attempt after missed heartbeat ack") {
            transport.connectCount >= 2
        }
    }

    private fun awaitTrue(message: String, timeoutMs: Long = 500L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10L)
        }
        if (!condition()) {
            fail(message)
        }
    }

}

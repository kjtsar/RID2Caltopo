package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TrackerPeerCoordinatorTest {

    private class FakeTransport : TrackerCoordinationTransport {
        private var transportCallback: TrackerCoordinationTransport.Callback? = null
        val sentMessages = CopyOnWriteArrayList<String>()
        var connected = false

        override fun setCallback(callback: TrackerCoordinationTransport.Callback?) {
            this.transportCallback = callback
        }

        override fun connect(websocketUrl: String, apiKey: String?) {
            connected = true
            transportCallback?.onOpen()
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String) {
            sentMessages += text
        }

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            transportCallback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
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

    @Before
    fun setUp() {
        transport = FakeTransport()
        TrackerPeerCoordinator.setTransportFactoryForTesting { transport }
        TrackerPeerCoordinator.setTrackerConfigForTesting("https://tracker.example.org", "tracker-token")
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
}

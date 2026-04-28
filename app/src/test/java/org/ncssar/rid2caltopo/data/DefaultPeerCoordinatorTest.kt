package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DefaultPeerCoordinatorTest {
    private class FakeTransport : TrackerCoordinationTransport {
        var connected = false
        var connectCount = 0
        var autoOpen = true
        private var callback: TrackerCoordinationTransport.Callback? = null

        override fun setCallback(callback: TrackerCoordinationTransport.Callback?) {
            this.callback = callback
        }

        override fun connect(websocketUrl: String, apiKey: String?) {
            connectCount++
            if (autoOpen) {
                connected = true
                callback?.onOpen()
            }
        }

        override fun disconnect() {
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String) = Unit

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            callback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
        }
    }

    private lateinit var transport: FakeTransport
    private lateinit var mqttFallback: FakePeerCoordinator

    @Before
    fun setUp() {
        transport = FakeTransport()
        mqttFallback = FakePeerCoordinator("mqtt-fallback")
        TrackerPeerCoordinator.setTransportFactoryForTesting { transport }
        DefaultPeerCoordinator.setMqttCoordinatorOverrideForTesting(mqttFallback)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
    }

    @After
    fun tearDown() {
        DefaultPeerCoordinator.getInstance().stop()
        TrackerPeerCoordinator.resetForTesting()
        DefaultPeerCoordinator.setMqttCoordinatorOverrideForTesting(null)
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
    }

    @Test
    fun start_usesTrackerCoordinatorWhenConfigured() {
        DefaultPeerCoordinator.getInstance().start("MAP1", "zone-alpha", "Alpha", null)

        assertTrue(transport.connected)
    }

    @Test
    fun hardTrackerFailure_fallsBackToMqttCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = object : LiveTrackOwnerDelegate {
            override fun getRemoteId(): String = "DRONE1"
            override fun setLocalOwner(isOwner: Boolean) = Unit
        }
        coordinator.onLiveTrackCreated(track, drone, 42.0, 1234L)

        transport.fail(403, "Forbidden")

        assertTrue(mqttFallback.isStarted())
        assertEquals("MAP1", mqttFallback.getStartedMapId())
        assertEquals(1, mqttFallback.countEvents("onLiveTrackCreated"))
    }

    @Test
    fun duplicateStartWhileTrackerDegraded_doesNotForceRestart() {
        transport.autoOpen = false
        val coordinator = DefaultPeerCoordinator.getInstance()

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertFalse(transport.connected)
    }
}

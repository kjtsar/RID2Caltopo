package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class DefaultPeerCoordinatorTest {
    private class FakeTransport : TrackerCoordinationTransport {
        var connected = false
        var connectCount = 0
        var autoOpen = true
        val sentMessages = java.util.Collections.synchronizedList(mutableListOf<String>())
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

        override fun send(text: String): Boolean {
            sentMessages.add(text)
            return true
        }

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
        CaltopoClient.SetUsePeers(true)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
    }

    @After
    fun tearDown() {
        DefaultPeerCoordinator.getInstance().stop()
        TrackerPeerCoordinator.resetForTesting()
        DefaultPeerCoordinator.setMqttCoordinatorOverrideForTesting(null)
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        CaltopoClient.SetUsePeers(false)
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
    }

    @Test
    fun start_usesTrackerCoordinatorWhenConfigured() {
        DefaultPeerCoordinator.getInstance().start("MAP1", "zone-alpha", "Alpha", null)

        assertTrue(transport.connected)
    }

    @Test
    fun standaloneR2cCoordinationDefaultsOff() {
        CaltopoClient.ResetPersistedClientState()

        assertFalse(CaltopoClient.GetStandaloneR2cCoordinationEnabled())
    }

    @Test
    fun mapConnectedStartStillUsesTrackerWhenStandaloneToggleOff() {
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        DefaultPeerCoordinator.getInstance().start("MAP1", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertTrue(transport.connected)
    }

    @Test
    fun statusBeforeStartNamesConfiguredTrackerCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)

        assertEquals("Tracker link degraded", coordinator.coordinationStatusText)
        assertTrue(
            coordinator.coordinationDiagnosticLines.toString(),
            coordinator.coordinationDiagnosticLines.any { it == "Tracker coordinator waiting for map connection" }
        )
    }

    @Test
    fun statusBeforeStartShowsDisabledWhenStandaloneTrackerToggleOff() {
        val coordinator = DefaultPeerCoordinator.getInstance()

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        assertEquals(PeerCoordinator.CoordinationIndicatorState.UNCONFIGURED, coordinator.coordinationIndicatorState)
        assertEquals("Tracker link disabled", coordinator.coordinationStatusText)
        assertTrue(coordinator.isLocalAlertEligible("RID-1"))
        assertTrue(
            coordinator.coordinationDiagnosticLines.toString(),
            coordinator.coordinationDiagnosticLines.any { it == "Standalone tracker coordination disabled" }
        )
    }

    @Test
    fun stoppingTrackerCoordinationRestoresUngatedStandaloneAlerts() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.stop()

        assertTrue(coordinator.isLocalAlertEligible("RID-1"))
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
    fun droneConfirmed_forwardsToActiveCoordinator() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        val event = mqttFallback.latestEventOfKind("onDroneConfirmed")
        assertEquals("RID-1 mappedId=1sar7DjMn4Pr", event?.summary)
    }

    @Test
    fun droneConfirmed_usesTrackerCoordinatorWhenConfigured() {
        val coordinator = DefaultPeerCoordinator.getInstance()
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        val sentMessages = synchronized(transport.sentMessages) {
            transport.sentMessages.toList()
        }
        assertTrue(sentMessages.any {
            val message = JSONObject(it)
            message.optString("type") == "drone_confirmed" &&
                message.optString("remoteId") == "RID-1" &&
                !message.has("flight" + "StartMsec")
        })
        assertEquals(null, mqttFallback.latestEventOfKind("onDroneConfirmed"))
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

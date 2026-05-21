package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class TrackerPeerCoordinatorTest {

    private class FakeTransport : TrackerCoordinationTransport {
        private var transportCallback: TrackerCoordinationTransport.Callback? = null
        val sentMessages = CopyOnWriteArrayList<String>()
        var connected = false
        var connectCount = 0
        var disconnectCount = 0
        var autoOpen = true
        var rejectNextSend = false
        var rejectNextMessageType: String? = null

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
            disconnectCount++
            connected = false
        }

        override fun isConnected(): Boolean = connected

        override fun send(text: String): Boolean {
            rejectNextMessageType?.let { type ->
                if (JSONObject(text).optString("type") == type) {
                    rejectNextMessageType = null
                    return false
                }
            }
            if (rejectNextSend) {
                rejectNextSend = false
                return false
            }
            sentMessages += text
            return true
        }

        fun open() {
            connected = true
            transportCallback?.onOpen()
        }

        fun fail(responseCode: Int, responseMessage: String) {
            connected = false
            transportCallback?.onFailure(RuntimeException("HTTP $responseCode"), responseCode, responseMessage)
        }

        fun receive(text: String) {
            transportCallback?.onMessage(text)
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
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
        TrackerPeerCoordinator.resetForTesting()
    }

    @Test
    fun start_connectsTransport() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        assertTrue(transport.connected)
    }

    @Test
    fun start_allowsEmptyMapString() {
        coordinator.start("", "zone-alpha", "Alpha", null)

        assertEquals(1, transport.connectCount)
        assertTrue(transport.connected)
    }

    @Test
    fun diagnosticsReportPendingTrackerAck() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(7L, clock.nowMs)

        clock.advanceBy(2_000L)

        assertEquals("Tracker link healthy", coordinator.coordinationStatusText)
        val diagnosticLines = coordinator.coordinationDiagnosticLines
        assertTrue(diagnosticLines.any { it == "Hello ack 2 sec ago" })
        assertTrue(
            diagnosticLines.toString(),
            diagnosticLines.any { it == "Waiting for heartbeat ack seq 7 2 sec" }
        )
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
    fun localArchiveOnlyDrone_neverRequestsTrackerOwnership() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1").apply { setLocalArchiveOnly(true) }
        val track = FakeLiveTrack("DRONE1")

        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.onWaypointReceived(drone, 39.1, -121.2, 120.0, 50.0, 2000L, null)

        assertFalse(track.localOwnerFlag)
        val messages = transport.sentMessages.map { JSONObject(it) }
        assertTrue(messages.none { it.optString("type") == "first_sighting" && it.optString("remoteId") == "DRONE1" })
        assertEquals("DRONE1", coordinator.getLastWaypointRemoteIdForTesting())
        assertFalse(coordinator.isLocalOwner("DRONE1"))
    }

    @Test
    fun rejectedDroneConfirmationSend_isRetriedAfterHeartbeatAck() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()
        transport.rejectNextMessageType = "drone_confirmed"

        coordinator.onDroneConfirmed(
            "RID-1",
            "NCSSAR",
            "DJI Mini 4 Pro",
            "1sar7",
            "1sar7DjMn4Pr"
        )

        assertTrue(transport.sentMessages.none { JSONObject(it).optString("type") == "drone_confirmed" })

        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)

        val confirmations = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "drone_confirmed" }
        assertEquals(1, confirmations.size)
        assertEquals("RID-1", confirmations.single().optString("remoteId"))
    }

    @Test
    fun incomingDroneConfirmedAppliesSessionOnlyDroneSpec() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        transport.receive(
            JSONObject()
                .put("type", "drone_confirmed")
                .put("remoteId", "DRONE9")
                .put("confirmedByGuid", "zone-bravo")
                .put("mappedId", "MA12Autel")
                .put("trackLabel", "MA12Autel")
                .put("org", "MA-SAR")
                .put("model", "Autel Evo Max")
                .put("ownerName", "MA12")
                .toString()
        )

        val drone = CaltopoClient.GetDroneSpec("DRONE9")!!
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed("DRONE9"))
        assertEquals("MA12Autel", drone.mappedId)
        assertEquals("MA-SAR", drone.org)
        assertEquals("Autel Evo Max", drone.model)
        assertEquals("MA12", drone.owner)
        assertEquals(0, CaltopoClient.GetRidmapCount())
    }

    @Test
    fun idleParkDisconnectsAndPositionUpdateDoesNotWake() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)

        coordinator.parkIfIdleForTesting()

        assertEquals(PeerCoordinator.CoordinationIndicatorState.IDLE, coordinator.coordinationIndicatorState)
        assertFalse(transport.connected)
        val connectCountAfterPark = transport.connectCount

        coordinator.updateMyPosition(39.2, -121.2)

        assertEquals(connectCountAfterPark, transport.connectCount)
        assertEquals("Tracker link idle", coordinator.coordinationStatusText)
    }

    @Test
    fun firstSightingWakesParkedCoordinatorAndSendsClaim() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.parkIfIdleForTesting()
        transport.sentMessages.clear()

        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)

        assertTrue(transport.connected)
        assertEquals(2, transport.connectCount)
        val firstSightings = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "first_sighting" }
        assertEquals(1, firstSightings.size)
        assertEquals("DRONE1", firstSightings.single().optString("remoteId"))
    }

    @Test
    fun firstSightingOmitsNonFiniteCoordinatesAndDistance() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.sentMessages.clear()

        val drone = CtDroneSpec("DRONE1").apply {
            lastLat = Double.NaN
            lastLng = Double.POSITIVE_INFINITY
            lastAlt = Double.NaN
        }
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, Double.NaN, 1234L)

        val firstSighting = transport.sentMessages
            .map { JSONObject(it) }
            .single { it.optString("type") == "first_sighting" }
        assertEquals("DRONE1", firstSighting.optString("remoteId"))
        assertFalse(firstSighting.has("distanceFromZoneM"))
        assertFalse(firstSighting.has("lat"))
        assertFalse(firstSighting.has("lng"))
        assertFalse(firstSighting.has("altM"))
    }

    @Test
    fun sightingOmitsNonFiniteCoordinatesDistanceAndTelemetry() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(
            drone,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NaN,
            Double.NaN,
            2_000L,
            CtDroneSpec.PositionTelemetry(Double.NaN, 12.5, Double.POSITIVE_INFINITY)
        )

        val sighting = transport.sentMessages
            .map { JSONObject(it) }
            .single { it.optString("type") == "sighting" }
        assertEquals("DRONE1", sighting.optString("remoteId"))
        assertFalse(sighting.has("distanceFromZoneM"))
        assertFalse(sighting.has("lat"))
        assertFalse(sighting.has("lng"))
        assertFalse(sighting.has("altM"))
        val telemetry = sighting.getJSONObject("telemetry")
        assertEquals(12.5, telemetry.getDouble("groundSpeedKnots"), 0.0)
        assertFalse(telemetry.has("verticalRateFpm"))
        assertFalse(telemetry.has("headingDeg"))
    }

    @Test
    fun droneConfirmedWakesParkedCoordinatorAndFlushesSaveEvent() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.parkIfIdleForTesting()
        transport.sentMessages.clear()

        coordinator.onDroneConfirmed("RID-1", "NCSSAR", "Mavic", "Pilot", "1SAR7DJ")

        assertTrue(transport.connected)
        assertEquals(2, transport.connectCount)
        val confirmations = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "drone_confirmed" }
        assertEquals(1, confirmations.size)
        assertEquals("RID-1", confirmations.single().optString("remoteId"))
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
    fun nonOwnerSightingsAreThrottledToThreeSecondsPerDrone() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        clock.advanceBy(1_000L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 55.0, 3_000L, null)
        clock.advanceBy(2_000L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 60.0, 4_000L, null)

        val sightings = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "sighting" }
        assertEquals(2, sightings.size)
        assertEquals(2_000L, sightings[0].optLong("droneTs"))
        assertEquals(4_000L, sightings[1].optLong("droneTs"))
    }

    @Test
    fun ownerTelemetrySuppressesBackupHeartbeatUntilLivenessWindowAges() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.stopBackgroundTimersForTesting()
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-alpha", 4L)
        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)
        transport.sentMessages.clear()

        coordinator.onWaypointReceived(drone, 39.1, -121.1, 120.0, 50.0, 2_000L, null)
        coordinator.sendHeartbeatForTesting()

        var heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(0, heartbeats.size)

        clock.advanceBy(31_000L)
        coordinator.onWaypointReceived(drone, 39.2, -121.2, 121.0, 55.0, 3_000L, null)
        coordinator.sendHeartbeatForTesting()

        heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(0, heartbeats.size)

        clock.advanceBy(15_000L)
        coordinator.onWaypointReceived(drone, 39.3, -121.3, 122.0, 60.0, 4_000L, null)
        coordinator.sendHeartbeatForTesting()

        heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(1, heartbeats.size)
    }

    @Test
    fun peerOwnedDroneDoesNotSuppressTrackerLivenessHeartbeat() {
        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        coordinator.stopBackgroundTimersForTesting()
        val drone = CtDroneSpec("DRONE1")
        val track = FakeLiveTrack("DRONE1")
        coordinator.onLiveTrackCreated(track, drone, 50.0, 1234L)
        coordinator.handleOwnerAssignedForTesting("DRONE1", "zone-bravo", 4L)
        coordinator.markHeartbeatSentForTesting(1L, clock.now())
        coordinator.handleHeartbeatAckForTesting(1L, 0L)
        transport.sentMessages.clear()

        coordinator.sendHeartbeatForTesting()

        val heartbeats = transport.sentMessages
            .map { JSONObject(it) }
            .filter { it.optString("type") == "heartbeat" }
        assertEquals(1, heartbeats.size)
        assertFalse(track.localOwnerFlag)
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

    @Test
    fun repeatedMissedHeartbeatAckWhileReconnectPending_isCoalesced() {
        transport.autoOpen = false

        coordinator.start("MAP1", "zone-alpha", "Alpha", null)
        transport.open()
        coordinator.handleHelloAckForTesting()
        coordinator.markHeartbeatSentForTesting(9L, clock.now())
        clock.advanceBy(10_001L)

        coordinator.checkAckLivenessForTesting()
        awaitTrue("expected reconnect attempt after missed heartbeat ack") {
            transport.connectCount >= 2
        }
        val forcedReconnectsAfterFirstCheck = coordinator.getForcedReconnectCountForTesting()

        coordinator.checkAckLivenessForTesting()

        assertEquals(forcedReconnectsAfterFirstCheck, coordinator.getForcedReconnectCountForTesting())
        assertEquals(2, transport.connectCount)
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

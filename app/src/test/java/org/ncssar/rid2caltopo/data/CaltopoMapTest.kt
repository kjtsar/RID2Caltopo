package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import android.location.Location
import java.lang.reflect.Field

class CaltopoMapTest {

    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var mapStatusField: Field
    private lateinit var mapNodeField: Field
    private lateinit var folderIdField: Field
    private lateinit var archiveFolderIdField: Field
    private lateinit var myUuidField: Field
    private lateinit var resolvedMarkerIdField: Field
    private lateinit var shutdownInProgressField: Field
    private lateinit var disconnectInProgressField: Field
    private lateinit var currentRuntimeField: Field
    private lateinit var lastStandaloneScopeField: Field
    private lateinit var standaloneStartedField: Field
    private lateinit var standaloneEnabledForActiveFlightsField: Field
    private lateinit var clientStateField: Field
    private lateinit var lastWaypointTimestampField: Field
    private lateinit var appExitRequestedField: Field

    private lateinit var originalMapStatus: CaltopoMap.MapStatusListener.mapStatus
    private var originalMapNode: Any? = null
    private var originalFolderId: String? = null
    private var originalArchiveFolderId: String? = null
    private var originalMyUuid: String? = null
    private var originalResolvedMarkerId: String? = null
    private var originalShutdownInProgress: Boolean = false
    private var originalDisconnectInProgress: Boolean = false
    private var originalCurrentRuntime: Any? = null
    private var originalLastStandaloneScope: String? = null
    private var originalStandaloneStarted: Boolean = false
    private var originalStandaloneEnabledForActiveFlights: Any? = null
    private var originalClientState: Any? = null
    private var originalTrackerApiKey: String = ""
    private var originalTrackerUrlPfx: String = ""
    private var originalMyLocation: Location? = null
    private var originalLastWaypointTimestamp: Long = 0L
    private var originalAppExitRequested: Boolean = false

    @Before
    fun setUp() {
        fixture = TestR2cRuntimeFactory.create("map-test")
        fixture.setAsDefaultRuntime()

        mapStatusField = CaltopoMap::class.java.getDeclaredField("MapStatus").apply { isAccessible = true }
        mapNodeField = CaltopoMap::class.java.getDeclaredField("MapNode").apply { isAccessible = true }
        folderIdField = CaltopoMap::class.java.getDeclaredField("FolderId").apply { isAccessible = true }
        archiveFolderIdField = CaltopoMap::class.java.getDeclaredField("ArchiveFolderId").apply { isAccessible = true }
        myUuidField = CaltopoMap::class.java.getDeclaredField("MyUUID").apply { isAccessible = true }
        resolvedMarkerIdField = CaltopoMap::class.java.getDeclaredField("ResolvedMyDeviceMarkerId").apply { isAccessible = true }
        shutdownInProgressField = CaltopoMap::class.java.getDeclaredField("ShutdownInProgress").apply { isAccessible = true }
        disconnectInProgressField = CaltopoMap::class.java.getDeclaredField("DisconnectInProgress").apply { isAccessible = true }
        currentRuntimeField = CaltopoMap::class.java.getDeclaredField("CurrentRuntime").apply { isAccessible = true }
        lastStandaloneScopeField = CaltopoMap::class.java.getDeclaredField("LastStandaloneCoordinationScopeId").apply { isAccessible = true }
        standaloneStartedField = CaltopoMap::class.java.getDeclaredField("StandaloneCoordinationStarted").apply { isAccessible = true }
        standaloneEnabledForActiveFlightsField = CaltopoMap::class.java.getDeclaredField("StandaloneCoordinationEnabledForActiveFlights").apply { isAccessible = true }
        clientStateField = CaltopoClient::class.java.getDeclaredField("Ccstate").apply { isAccessible = true }
        lastWaypointTimestampField = CtDroneSpec::class.java.getDeclaredField("MostRecentWaypointTimestampInMsec").apply { isAccessible = true }
        appExitRequestedField = CaltopoClient::class.java.getDeclaredField("AppExitRequested").apply { isAccessible = true }

        originalMapStatus = mapStatusField.get(null) as CaltopoMap.MapStatusListener.mapStatus
        originalMapNode = mapNodeField.get(null)
        originalFolderId = folderIdField.get(null) as String?
        originalArchiveFolderId = archiveFolderIdField.get(null) as String?
        originalMyUuid = myUuidField.get(null) as String?
        originalResolvedMarkerId = resolvedMarkerIdField.get(null) as String?
        originalShutdownInProgress = shutdownInProgressField.getBoolean(null)
        originalDisconnectInProgress = disconnectInProgressField.getBoolean(null)
        originalCurrentRuntime = currentRuntimeField.get(null)
        originalLastStandaloneScope = lastStandaloneScopeField.get(null) as String?
        originalStandaloneStarted = standaloneStartedField.getBoolean(null)
        originalStandaloneEnabledForActiveFlights = standaloneEnabledForActiveFlightsField.get(null)
        originalClientState = clientStateField.get(null)
        clientStateField.set(null, ClientClassState())
        originalTrackerApiKey = CaltopoClient.GetTrackerApiKey()
        originalTrackerUrlPfx = CaltopoClient.GetTrackerUrlPfx()
        originalMyLocation = CaltopoMap.MyLocation
        originalLastWaypointTimestamp = lastWaypointTimestampField.getLong(null)
        originalAppExitRequested = appExitRequestedField.getBoolean(null)

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Map Test", 0L))
        folderIdField.set(null, "folder-test")
        archiveFolderIdField.set(null, "archive-folder-test")
        myUuidField.set(null, "marker-guid-test")
        resolvedMarkerIdField.set(null, "marker-guid-test")
        shutdownInProgressField.setBoolean(null, false)
        disconnectInProgressField.setBoolean(null, false)
        currentRuntimeField.set(null, fixture.runtime)
        lastStandaloneScopeField.set(null, "")
        standaloneStartedField.setBoolean(null, false)
        standaloneEnabledForActiveFlightsField.set(null, null)
        appExitRequestedField.setBoolean(null, false)
        CaltopoMap.resetAutoQuitRelocationForTesting()
    }

    @After
    fun tearDown() {
        mapStatusField.set(null, originalMapStatus)
        mapNodeField.set(null, originalMapNode)
        folderIdField.set(null, originalFolderId)
        archiveFolderIdField.set(null, originalArchiveFolderId)
        myUuidField.set(null, originalMyUuid)
        resolvedMarkerIdField.set(null, originalResolvedMarkerId)
        shutdownInProgressField.setBoolean(null, originalShutdownInProgress)
        disconnectInProgressField.setBoolean(null, originalDisconnectInProgress)
        currentRuntimeField.set(null, originalCurrentRuntime)
        lastStandaloneScopeField.set(null, originalLastStandaloneScope)
        standaloneStartedField.setBoolean(null, originalStandaloneStarted)
        standaloneEnabledForActiveFlightsField.set(null, originalStandaloneEnabledForActiveFlights)
        CaltopoMap.MyLocation = originalMyLocation
        CaltopoClient.SetTrackerApiKey(originalTrackerApiKey)
        CaltopoClient.SetTrackerUrlPfx(originalTrackerUrlPfx)
        lastWaypointTimestampField.setLong(null, originalLastWaypointTimestamp)
        appExitRequestedField.setBoolean(null, originalAppExitRequested)
        CaltopoMap.resetAutoQuitRelocationForTesting()
        clientStateField.set(null, originalClientState)
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun resetMapConnection_deletesMyDeviceMarker() {
        CaltopoMap.ResetMapConnection(1_000L)

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteMarker"))
        val deleteIndex = operations.indexOfFirst { it.kind == "deleteMarker" }
        assertTrue(operations.toString(), deleteIndex >= 0)
    }

    @Test
    fun archiveFeature_refreshesMapUpdatesAfterArchiveEditSucceeds() {
        val feature = JSONObject()
            .put("id", "track-archive-refresh")
            .put(
                "properties",
                JSONObject()
                    .put("class", "LiveTrack")
                    .put("title", "RID_120000Jun12")
                    .put("folderId", "folder-test")
            )

        CaltopoMap.ArchiveFeature(feature, "Shape", 1_000L, 0L)

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        val editIndex = operations.indexOfFirst { it.kind == "editObject" }
        val refreshIndex = operations.indexOfFirst { it.kind == "openMap" }
        assertTrue(operations.toString(), editIndex >= 0)
        assertTrue(operations.toString(), refreshIndex > editIndex)
    }

    @Test
    fun resetMapConnection_shutsDownPendingLiveTrackWithoutLiveTrackId() {
        val drone = CtDroneSpec("RID-PENDING-RESET")
        setDroneTrackLabel(drone, "RID-PENDING-RESET_123000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)
        liveTrack.publishDirect(39.2, -121.2, 501L, 2_000L)

        CaltopoMap.ResetMapConnection(0L)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertEquals(1, peerCoordinator.countEvents("onDroneLost"))
        assertEquals(0, queuedPointCount(liveTrack))
        assertFalse(liveTrack.isActive)
    }

    @Test
    fun updateMyLocation_startsStandaloneTrackerWithEmptyMapString() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)

        val location = Location("test").apply {
            latitude = 39.153061
            longitude = -121.132946
            accuracy = 5.0f
        }

        CaltopoMap.UpdateMyLocation(location)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertTrue(peerCoordinator.isStarted())
        assertEquals("", peerCoordinator.getStartedMapId())
    }

    @Test
    fun updateMyLocation_doesNotStartStandaloneTrackerWhenToggleOff() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        val location = Location("test").apply {
            latitude = 39.153061
            longitude = -121.132946
            accuracy = 5.0f
        }

        CaltopoMap.UpdateMyLocation(location)

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun ensureStandaloneTrackerCoordinationStarted_usesCachedLocation() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertTrue(peerCoordinator.isStarted())
        assertEquals("", peerCoordinator.getStartedMapId())
        assertEquals(1, peerCoordinator.countEvents("updateMyPosition"))
    }

    @Test
    fun disablingStandaloneToggleStopsActiveNoMapTrackerCoordination() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()
        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertTrue(peerCoordinator.isStarted())
        val drone = activateDrone("RIDTOGGLE1")

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)

        assertTrue(peerCoordinator.isStarted())

        drone.reset()

        assertFalse(peerCoordinator.isStarted())
    }

    @Test
    fun enablingStandaloneToggleMidFlightDoesNotStartNoMapTrackerUntilFlightWindowEnds() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetStandaloneR2cCoordinationEnabled(false)
        val drone = activateDrone("RIDTOGGLE2")
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoClient.SetStandaloneR2cCoordinationEnabled(true)
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertFalse(peerCoordinator.isStarted())

        drone.reset()
        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        assertTrue(peerCoordinator.isStarted())
    }

    @Test
    fun ensureStandaloneTrackerCoordinationStarted_updatesPositionWithoutTrackerCredentials() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetTrackerApiKey("")
        CaltopoClient.SetTrackerUrlPfx("")
        CaltopoMap.MyLocation = Location("cached").apply {
            latitude = 39.153062
            longitude = -121.132960
            accuracy = 8.0f
        }

        CaltopoMap.EnsureStandaloneTrackerCoordinationStarted()

        val peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        assertTrue(!peerCoordinator.isStarted())
        assertEquals(1, peerCoordinator.countEvents("updateMyPosition"))
    }

    @Test
    fun updateMyLocation_quitsAfterQuietAccurateTabletRelocation() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        assertTrue(CaltopoMap.isAutoQuitAfterRelocationEligibleForTesting(5.0f, nowMs))
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertTrue(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(1, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenGpsAccuracyIsTooPoor() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 8.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 8.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenDroneTelemetryWasRecent() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS + 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenDroneIsActive() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)
        activateDrone("RID-AUTOQUIT-ACTIVE")

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun updateMyLocation_doesNotQuitWhenNotConnectedToMap() {
        val nowMs = 1_000_000_000L
        var quitCount = 0
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoMap.setTimeSourceForTesting { nowMs }
        CaltopoMap.setQuitHandlerForTesting { quitCount++ }
        setLastWaypointTimestamp(nowMs - FIVE_MINUTES_MS - 1L)

        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153000, -121.132000, 5.0f)
        CaltopoMap.evaluateAutoQuitAfterRelocationForTesting(39.153200, -121.132000, 5.0f)

        assertEquals(0, quitCount)
        assertFalse(CaltopoMap.hasAutoQuitRelocationAnchorForTesting())
    }

    @Test
    fun getIncident_defaultsToTrainingWhenNotConnectedToMap() {
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)
        CaltopoClient.SetIncident("Old Incident")

        assertEquals("Training", CaltopoClient.GetIncident())
    }

    @Test
    fun getIncident_usesConnectedMapNameWithoutPersistingIt() {
        CaltopoClient.UpsertCaltopoProfile(
            CaltopoProfileRecord(
                "home-default",
                "Default",
                "HOME",
                CaltopoCredentials(),
                "caltopo.com",
                "Drone Tracks",
                "Old Incident",
                "1",
                "",
                "",
                false,
                0L,
                false,
                "",
                "",
                "",
                "",
                0L,
                ""
            ),
            true,
            false
        )
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Search Alpha", 0L))
        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)

        assertEquals("Search Alpha", CaltopoClient.GetIncident())
        assertEquals("Old Incident", CaltopoClient.GetCaltopoProfileById("home-default")!!.incident)

        mapNodeField.set(null, CaltopoNode.MapNode("map-test-2", "Search Bravo", 0L))

        assertEquals("Search Bravo", CaltopoClient.GetIncident())
        assertEquals("Old Incident", CaltopoClient.GetCaltopoProfileById("home-default")!!.incident)

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.down)
        mapNodeField.set(null, null)

        assertEquals("Training", CaltopoClient.GetIncident())
    }

    private fun activateDrone(remoteId: String): CtDroneSpec {
        val drone = CtDroneSpec(remoteId)
        val trackLabelField = CtDroneSpec::class.java.getDeclaredField("trackLabel").apply {
            isAccessible = true
        }
        trackLabelField.set(drone, "${remoteId}_active")
        val state = clientStateField.get(null) as ClientClassState
        state.droneSpecTable[drone.remoteId] = drone
        CaltopoMap.OnDroneSpecStatusChanged(true)
        return drone
    }

    private fun setLastWaypointTimestamp(timestampMs: Long) {
        lastWaypointTimestampField.setLong(null, timestampMs)
    }

    private fun setDroneTrackLabel(drone: CtDroneSpec, trackLabel: String) {
        val mappedId = trackLabel.substringBefore('_')
        CtDroneSpec::class.java.getDeclaredField("mappedId").apply {
            isAccessible = true
            set(drone, mappedId)
        }
        CtDroneSpec::class.java.getDeclaredField("trackLabel").apply {
            isAccessible = true
            set(drone, trackLabel)
        }
    }

    private fun queuedPointCount(liveTrack: CaltopoLiveTrack): Int {
        val field = CaltopoLiveTrack::class.java.getDeclaredField("linePoints").apply {
            isAccessible = true
        }
        val points = field.get(liveTrack) as Collection<*>
        return points.size
    }

    companion object {
        private const val FIVE_MINUTES_MS = 5L * 60L * 1000L
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    private lateinit var originalMapStatus: CaltopoMap.MapStatusListener.mapStatus
    private var originalMapNode: Any? = null
    private var originalFolderId: String? = null
    private var originalArchiveFolderId: String? = null
    private var originalMyUuid: String? = null
    private var originalResolvedMarkerId: String? = null
    private var originalShutdownInProgress: Boolean = false
    private var originalDisconnectInProgress: Boolean = false
    private var originalCurrentRuntime: Any? = null

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

        originalMapStatus = mapStatusField.get(null) as CaltopoMap.MapStatusListener.mapStatus
        originalMapNode = mapNodeField.get(null)
        originalFolderId = folderIdField.get(null) as String?
        originalArchiveFolderId = archiveFolderIdField.get(null) as String?
        originalMyUuid = myUuidField.get(null) as String?
        originalResolvedMarkerId = resolvedMarkerIdField.get(null) as String?
        originalShutdownInProgress = shutdownInProgressField.getBoolean(null)
        originalDisconnectInProgress = disconnectInProgressField.getBoolean(null)
        originalCurrentRuntime = currentRuntimeField.get(null)

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        mapNodeField.set(null, CaltopoNode.MapNode("map-test", "Map Test", 0L))
        folderIdField.set(null, "folder-test")
        archiveFolderIdField.set(null, "archive-folder-test")
        myUuidField.set(null, "marker-guid-test")
        resolvedMarkerIdField.set(null, "marker-guid-test")
        shutdownInProgressField.setBoolean(null, false)
        disconnectInProgressField.setBoolean(null, false)
        currentRuntimeField.set(null, fixture.runtime)
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
}

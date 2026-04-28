package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

class CaltopoLiveTrackTest {

    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var mapStatusField: Field
    private lateinit var folderIdField: Field
    private lateinit var archiveFolderIdField: Field
    private lateinit var originalMapStatus: CaltopoMap.MapStatusListener.mapStatus
    private var originalFolderId: String? = null
    private var originalArchiveFolderId: String? = null

    @Before
    fun setUp() {
        fixture = TestR2cRuntimeFactory.create("live-track-test")
        fixture.setAsDefaultRuntime()

        mapStatusField = CaltopoMap::class.java.getDeclaredField("MapStatus").apply { isAccessible = true }
        folderIdField = CaltopoMap::class.java.getDeclaredField("FolderId").apply { isAccessible = true }
        archiveFolderIdField = CaltopoMap::class.java.getDeclaredField("ArchiveFolderId").apply { isAccessible = true }

        originalMapStatus = mapStatusField.get(null) as CaltopoMap.MapStatusListener.mapStatus
        originalFolderId = folderIdField.get(null) as String?
        originalArchiveFolderId = archiveFolderIdField.get(null) as String?

        mapStatusField.set(null, CaltopoMap.MapStatusListener.mapStatus.up)
        folderIdField.set(null, "folder-test")
        archiveFolderIdField.set(null, "archive-folder-test")
    }

    @After
    fun tearDown() {
        mapStatusField.set(null, originalMapStatus)
        folderIdField.set(null, originalFolderId)
        archiveFolderIdField.set(null, originalArchiveFolderId)
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun shutdownAfterOwnershipLoss_deletesOrphanedLiveTrack() {
        val drone = CtDroneSpec("RID-ORPHAN")
        setDroneTrackLabel(drone, "RID-ORPHAN_120000Apr28")
        val liveTrack = CaltopoLiveTrack(drone, 39.1, -121.1, 500.0, 1_000L)
        liveTrack.mapStatusUpdate(CaltopoMap.MapStatusListener.mapStatus.up, null, null)

        forceLiveTrackId(liveTrack, "live-RIDORPHAN-test")

        liveTrack.setLocalOwner(false)
        liveTrack.shutdown(0L)

        val operations = fixture.calTopoSessionGateway.snapshotOperations()
        assertEquals(operations.toString(), 1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, fixture.calTopoSessionGateway.countOperations("addLine"))
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

    private fun forceLiveTrackId(liveTrack: CaltopoLiveTrack, liveTrackId: String) {
        val liveTrackIdField = CaltopoLiveTrack::class.java.getDeclaredField("liveTrackId").apply {
            isAccessible = true
        }
        liveTrackIdField.set(liveTrack, liveTrackId)
    }
}

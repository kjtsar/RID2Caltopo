package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CaltopoClientRemoteIdValidationTest {
    @Before
    fun setUp() {
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun clientForRemoteIdRejectsNonCanonicalRemoteId() {
        assertThrows(RuntimeException::class.java) {
            CaltopoClient.ClientForRemoteId("RID-ABC")
        }
    }

    @Test
    fun peerAndMqttPathsRejectNonCanonicalRemoteIds() {
        val remoteId = "RIDABC"
        CaltopoClient.ClientForRemoteId(remoteId)
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR7",
            "1SAR7m3"
        )

        CaltopoClient.ApplyRemoteDroneSpec("RID-ABC", "1SAR8m3", "NCSSAR", "DJI Mavic 3", "1SAR8")
        CaltopoClient.ApplyPeerDroneSpecConfirmation("RID ABC", "NCSSAR", "DJI Mavic 3", "1SAR8", "1SAR8m3")
        CaltopoClient.SaveDroneSpecUnknownConfirmation(" RIDABC ")

        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        assertEquals("1SAR7m3", drone.mappedId)
        assertEquals("1SAR7", drone.owner)
        assertTrue(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertNull(CaltopoClient.GetDroneSpec("RID-ABC"))
    }

    @Test
    fun currentDroneListUpdateDefersReentrantRebuildRequests() {
        val processingField = CaltopoClient::class.java
            .getDeclaredField("ProcessingDroneSpecUpdate")
            .apply { isAccessible = true }
        val pendingField = CaltopoClient::class.java
            .getDeclaredField("PendingDroneSpecUpdate")
            .apply { isAccessible = true }
        val processMethod = CaltopoClient::class.java
            .getDeclaredMethod("ProcessSortedCurrentDroneSpecArray", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }

        try {
            processingField.setBoolean(null, true)
            pendingField.setBoolean(null, false)

            processMethod.invoke(null, true)

            assertTrue(pendingField.getBoolean(null))
        } finally {
            processingField.setBoolean(null, false)
            pendingField.setBoolean(null, false)
        }
    }
}

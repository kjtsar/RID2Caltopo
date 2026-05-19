package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        assertFalse(CaltopoClient.IsCurrentPeerDroneConfirmed(remoteId))
        assertFalse(CaltopoClient.IsSessionUnknownDrone(remoteId))
        assertNull(CaltopoClient.GetDroneSpec("RID-ABC"))
    }
}

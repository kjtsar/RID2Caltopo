package org.opendroneid.android.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DroneScoutRelayMetadataTest {
    @Test
    fun parsesBridgeInsertedSelfId() {
        val metadata = DroneScoutRelayMetadata.parse("DS WIFI B -74 dBm drone")

        assertEquals(-74, metadata?.droneToBridgeRssiDbm)
        assertEquals("WIFI B", metadata?.receptionMode)
        assertEquals("drone", metadata?.sourceKind)
    }

    @Test
    fun toleratesCompliancePrefixAndRejectsOrdinarySelfId() {
        val metadata = DroneScoutRelayMetadata.parse("non-compl 2 DS WIB -91")

        assertEquals(-91, metadata?.droneToBridgeRssiDbm)
        assertEquals("WIB", metadata?.receptionMode)
        assertNull(DroneScoutRelayMetadata.parse("Search aircraft alpha"))
        assertNull(DroneScoutRelayMetadata.parse("DS BT5 0 dBm drone"))
    }
}

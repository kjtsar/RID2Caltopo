package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidDeviceIdentityTest {
    @Test
    fun assignedDeviceNameWinsOverHardwareFallback() {
        assertEquals(
            "Ken's S25 Ultra",
            AndroidDeviceIdentity.selectDisplayName(
                "Ken's S25 Ultra",
                "samsung SM-S938U1"
            )
        )
    }

    @Test
    fun emptyAndUnknownNamesUseNextCandidate() {
        assertEquals(
            "samsung SM-S938U1",
            AndroidDeviceIdentity.selectDisplayName(
                " ",
                "<unknown>",
                "samsung SM-S938U1"
            )
        )
    }
}

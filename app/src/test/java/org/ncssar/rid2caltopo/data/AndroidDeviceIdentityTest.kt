package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDeviceIdentityTest {
    @Test
    fun trackerEnrollmentReusesCanonicalCaltopoDeviceGuid() {
        val source = java.io.File(
            "src/main/java/org/ncssar/rid2caltopo/data/TrackerEnrollmentClient.kt"
        ).readText()
        assertTrue(source.contains(".put(\"installation_id\", CaltopoMap.GetMyUUID())"))
        assertFalse(source.contains("AndroidDeviceIdentity.installationId"))
    }

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

    @Test
    fun hardwareModelNameIncludesManufacturerWithoutDuplicatingIt() {
        assertEquals(
            "Samsung SM-S938U1",
            AndroidDeviceIdentity.modelName("samsung", "SM-S938U1")
        )
        assertEquals(
            "Google Pixel 9 Pro",
            AndroidDeviceIdentity.modelName("Google", "Google Pixel 9 Pro")
        )
    }
}

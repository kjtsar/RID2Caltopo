package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ncssar.rid2caltopo.BuildConfig
import org.junit.Assert.assertTrue
import org.ncssar.rid2caltopo.notam.NotamAuthManager

class ScannerScreenStatusTextTest {
    @Test
    fun buildStatusText_placesBuildVersionAndTimeAtTop() {
        val lines = buildStatusText(emptyList()).lines()

        assertEquals("BUILD_VERSION: ${BuildConfig.BUILD_VERSION}", lines[0])
        assertEquals("BUILD_TIME: ${BuildConfig.BUILD_TIME}", lines[1])
        assertEquals("", lines[2])
        assertEquals("Scanner Status", lines[3])
    }

    @Test
    fun buildConfigString_returnsUnknownWhenFieldIsMissing() {
        assertEquals(
            "unknown",
            buildConfigString("BUILD_VERSION", BuildConfigWithoutVersion::class.java)
        )
    }

    @Test
    fun faaTrackerStatusSeparatesLoadedRemoteConfigFromDeviceEnrollment() {
        val status = buildFaaTrackerAccessStatus(
            organization = "NCSSAR",
            credentialSource = NotamAuthManager.CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL,
            faaRemoteConfigLoaded = true,
            latestNotamResult = "FAA proxy rejected the credential (HTTP 403)."
        )

        assertTrue(status.contains("NCSSAR (operational identity only)"))
        assertTrue(status.contains("not a tracker device access grant"))
        assertTrue(status.contains("Tracker credential source: Organization-config credential"))
        assertTrue(status.contains("HTTP 403"))
    }

    private class BuildConfigWithoutVersion
}

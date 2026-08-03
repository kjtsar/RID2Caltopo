package org.ncssar.rid2caltopo.notam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient

class NotamAuthManagerTest {
    @Test
    fun proxyConfigurationRequiresOrganizationEnrollmentValues() {
        CaltopoClient.ResetPersistedClientState()
        assertFalse(NotamAuthManager.isConfigured())

        CaltopoClient.SetHomeTrackerCredentials("https://r2c-tracker.com", "tracker-token")
        assertFalse(NotamAuthManager.isConfigured())

        CaltopoClient.SetTrackerFaaProxyUrl("https://r2c-tracker.com/faa/notams")
        assertTrue(NotamAuthManager.isConfigured())
        assertEquals("https://r2c-tracker.com/faa/notams", NotamAuthManager.resolvedNotamUrl())
        assertEquals("tracker-token", NotamAuthManager.proxyToken())

        CaltopoClient.SetTrackerFaaProxyUrl("https://example.org/faa/notams")
        assertFalse(NotamAuthManager.isConfigured())
        CaltopoClient.ResetPersistedClientState()
    }
}

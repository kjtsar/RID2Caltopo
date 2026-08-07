package org.ncssar.rid2caltopo.notam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient

class NotamAuthManagerTest {
    @Test
    fun organizationTrackerCredentialsDeriveTrustedProxyUrlAndReportProvisioningMismatch() {
        CaltopoClient.ResetPersistedClientState()
        assertFalse(NotamAuthManager.isConfigured())

        CaltopoClient.SetHomeTrackerCredentials("https://r2c-tracker.com/ncssar", "tracker-token")
        assertTrue(NotamAuthManager.isConfigured())
        assertEquals(
            NotamAuthManager.CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL,
            NotamAuthManager.credentialSource()
        )
        assertEquals("https://r2c-tracker.com/faa/notams", NotamAuthManager.resolvedNotamUrl())
        assertTrue(NotamAuthManager.authorizationFailureMessage(403).contains("tracker credential was rejected"))
        assertTrue(NotamAuthManager.authorizationFailureMessage(403).contains("provisioning mismatch"))
        assertTrue(NotamAuthManager.authorizationFailureMessage(403).contains("device-enrollment QR"))

        CaltopoClient.SetTrackerFaaProxyUrl("https://r2c-tracker.com/faa/notams")
        assertTrue(NotamAuthManager.isConfigured())
        assertEquals(
            NotamAuthManager.CredentialSource.ORGANIZATION_CONFIG_CREDENTIAL,
            NotamAuthManager.credentialSource()
        )

        CaltopoClient.SetHomeTrackerCredentials(
            "https://r2c-tracker.com/ncssar",
            "r2c_dev_abcdefghijklmnopqrstuvwxyz0123456789"
        )
        assertEquals(
            NotamAuthManager.CredentialSource.MANAGED_DEVICE_ENROLLMENT,
            NotamAuthManager.credentialSource()
        )
        assertEquals("https://r2c-tracker.com/faa/notams", NotamAuthManager.resolvedNotamUrl())
        assertEquals(
            "r2c_dev_abcdefghijklmnopqrstuvwxyz0123456789",
            NotamAuthManager.proxyToken()
        )
        assertTrue(NotamAuthManager.authorizationFailureMessage(403).contains("managed device credential"))

        CaltopoClient.SetTrackerFaaProxyUrl("https://example.org/faa/notams")
        assertFalse(NotamAuthManager.isConfigured())
        CaltopoClient.ResetPersistedClientState()
    }
}

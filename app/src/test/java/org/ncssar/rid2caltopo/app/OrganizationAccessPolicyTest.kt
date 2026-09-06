package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationAccessPolicyTest {
    @Test
    fun organizationOrCaltopoTeamsConfigurationRequiresDeviceOwnerAuthentication() {
        assertFalse(organizationAccessAuthenticationRequired(null, false, false))
        assertFalse(organizationAccessAuthenticationRequired("", false, false))
        assertFalse(organizationAccessAuthenticationRequired("  \n", false, false))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", false, false))
        assertTrue(organizationAccessAuthenticationRequired("", true, false))
        assertTrue(organizationAccessAuthenticationRequired("", false, true))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", true, true))
    }

    @Test
    fun authenticatedSessionSurvivesTrustedArchivePickerUntilItsResult() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER)
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun authenticatedSessionSurvivesConfigQrScannerUntilItsResult() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.CONFIG_QR_SCANNER))
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.CONFIG_QR_SCANNER)
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun authenticatedSessionSurvivesTrackerReauthenticationBrowserUntilReturn() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(
            session.beginTrustedExternalFlow(
                OrganizationExternalFlow.TRACKER_REAUTHENTICATION_BROWSER
            )
        )
        assertTrue(session.activityStopped(isChangingConfigurations = false))
        assertTrue(session.isAuthenticated())

        session.completeTrustedExternalFlow(
            OrganizationExternalFlow.TRACKER_REAUTHENTICATION_BROWSER
        )
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun ordinaryBackgroundingInvalidatesAuthenticatedSession() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertFalse(session.activityStopped(isChangingConfigurations = false))
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun screenLockInvalidatesAuthenticationWhileRetainingPickerCompletion() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))

        session.invalidateForScreenLock()
        assertFalse(session.isAuthenticated())

        session.completeTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER)
        assertFalse(session.isAuthenticated())
    }

    @Test
    fun configurationChangePreservesAuthenticatedSession() {
        val session = OrganizationAccessSession()
        session.markAuthenticated()

        assertTrue(session.activityStopped(isChangingConfigurations = true))
        assertTrue(session.isAuthenticated())
    }

    @Test
    fun processSessionCannotStartOverlappingTrustedFlows() {
        val session = OrganizationAccessSession()
        assertFalse(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))

        session.markAuthenticated()
        assertTrue(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
        assertFalse(session.beginTrustedExternalFlow(OrganizationExternalFlow.ARCHIVE_DIRECTORY_PICKER))
    }
}

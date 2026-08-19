package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CViewModelCredentialRecoveryTest {
    @Test
    fun restoredCredentialsResumeAWaitingMapConnection() {
        assertTrue(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.RequestConfigFile,
                hasCredentials = true
            )
        )
    }

    @Test
    fun missingCredentialsRemainInTheExplicitWaitingState() {
        assertFalse(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.RequestConfigFile,
                hasCredentials = false
            )
        )
    }

    @Test
    fun restoredCredentialsDoNotInterruptAnotherOverlay() {
        assertFalse(
            shouldResumeMapConnectionAfterCredentialRestore(
                OverlayState.MapBrowser,
                hasCredentials = true
            )
        )
    }
}

package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CaltopoSettingsValidationTest {
    @Test
    fun blankCredentialFieldsLeaveStoredCredentialsAlone() {
        assertEquals(
            CaltopoCredentialFieldState.BLANK,
            caltopoCredentialFieldState(" ", "", "\t"),
        )
    }

    @Test
    fun completeCredentialFieldsCanBeSaved() {
        assertEquals(
            CaltopoCredentialFieldState.COMPLETE,
            caltopoCredentialFieldState("team", "credential", "secret"),
        )
    }

    @Test
    fun partialCredentialFieldsAreRejectedBeforeAnySettingsAreWritten() {
        assertEquals(
            CaltopoCredentialFieldState.PARTIAL,
            caltopoCredentialFieldState("team", "", "secret"),
        )
    }
}

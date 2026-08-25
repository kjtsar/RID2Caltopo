package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDirPromptStateTest {
    @Test
    fun persistentResetImmediatelyRequestsRequiredSetupAndSafetyRefresh() {
        val calls = mutableListOf<String>()

        resetPersistedStateAndRequestRequiredSetup(
            resetState = { calls += "reset" },
            resetNotamRuntimeState = { calls += "notam-reset" },
            requestArchiveSelection = { calls += "archive" },
            requestNotamRefresh = { calls += "notam" },
            requestAirspaceRefresh = { calls += "airspace" }
        )

        assertTrue(calls == listOf("reset", "notam-reset", "notam", "airspace", "archive"))
    }

    @Test
    fun forcedArchiveSetupDoesNotWaitForDriveRestoreEligibility() {
        assertTrue(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = true,
                driveRestoreEligibilityLoaded = false,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )
    }

    @Test
    fun missingArchiveUriDoesNotLaunchAgainWhilePickerIsOpen() {
        assertTrue(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )

        assertFalse(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = false,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = true
            )
        )
    }

    @Test
    fun missingArchiveUriDoesNotPromptWhenSessionArchiveDirIsAvailable() {
        assertFalse(
            shouldLaunchArchiveDirPicker(
                archiveUriMissing = true,
                sessionArchiveDirAvailable = true,
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = false
            )
        )
    }

    @Test
    fun archiveDirPromptMessagesExplainWhyTheSystemPickerWillOpen() {
        val initial = archiveDirPromptMessage(permissionMissing = false)
        assertTrue(initial.contains("archive folder", ignoreCase = true))
        assertTrue(initial.contains("drone tracks", ignoreCase = true))
        assertTrue(initial.contains("logs", ignoreCase = true))
        assertTrue(initial.contains("map data", ignoreCase = true))

        val expired = archiveDirPromptMessage(permissionMissing = true)
        assertTrue(expired.contains("permission again", ignoreCase = true))
        assertTrue(expired.contains("drone tracks", ignoreCase = true))
        assertTrue(expired.contains("logs", ignoreCase = true))
        assertTrue(expired.contains("map data", ignoreCase = true))
    }

    @Test
    fun archiveDirDisplayPathMakesAndroidTreeUriOperatorReadable() {
        assertEquals(
            "sdcard:/Drone Trax",
            archiveDirDisplayPath(
                "content://com.android.externalstorage.documents/tree/primary%3ADrone%20Trax"
            )
        )
        assertEquals(
            "1234-5678:/R2C Archive",
            archiveDirDisplayPath(
                "content://com.android.externalstorage.documents/tree/1234-5678%3AR2C%20Archive"
            )
        )
        assertNull(archiveDirDisplayPath(null))
    }

    @Test
    fun expiredArchiveGrantOffersOnlyPersistentFolderChoices() {
        val expired = archiveDirPromptActions(
            permissionMissing = true,
            previousArchivePath = "sdcard:/Drone Trax"
        )
        assertEquals("Continue using sdcard:/Drone Trax", expired.continueLabel)
        assertEquals("Choose a different archive folder", expired.chooseDifferentLabel)

        val initialSetup = archiveDirPromptActions(
            permissionMissing = false,
            previousArchivePath = null
        )
        assertEquals("Select archive folder", initialSetup.continueLabel)
        assertNull(initialSetup.chooseDifferentLabel)
    }
}

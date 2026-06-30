package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveDirPromptStateTest {
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
        assertTrue(initial.contains("archive directory", ignoreCase = true))
        assertTrue(initial.contains("drone tracks", ignoreCase = true))
        assertTrue(initial.contains("map cache", ignoreCase = true))

        val expired = archiveDirPromptMessage(permissionMissing = true)
        assertTrue(expired.contains("access expired", ignoreCase = true))
        assertTrue(expired.contains("archive directory", ignoreCase = true))
        assertTrue(expired.contains("drone tracks", ignoreCase = true))
        assertTrue(expired.contains("map cache", ignoreCase = true))
    }
}

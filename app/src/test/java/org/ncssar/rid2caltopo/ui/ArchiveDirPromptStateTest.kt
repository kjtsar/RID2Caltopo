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
                forceArchiveDirPrompt = false,
                driveRestoreEligibilityLoaded = true,
                showDriveRestoreDialog = false,
                driveSyncInProgress = false,
                archiveDirPickerOpen = true
            )
        )
    }
}

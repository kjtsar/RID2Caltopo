package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CActivityLogArchiveTest {
    @Test
    fun temporaryLocationOverrideClearsForNewTaskButNotConfigurationRecreation() {
        assertTrue(shouldClearTemporaryLocationOverrideOnCreate(hasSavedInstanceState = false))
        assertFalse(shouldClearTemporaryLocationOverrideOnCreate(hasSavedInstanceState = true))
    }

    @Test
    fun buildLogArchiveEntryName_keepsExistingTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13.txt"))
    }

    @Test
    fun buildLogArchiveEntryName_addsMissingTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13"))
    }

    @Test
    fun buildLogArchiveEntryName_defaultsBlankNames() {
        assertEquals("log_unknown.txt", buildLogArchiveEntryName(" "))
    }
}

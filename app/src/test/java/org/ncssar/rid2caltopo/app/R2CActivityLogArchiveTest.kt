package org.ncssar.rid2caltopo.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class R2CActivityLogArchiveTest {
    @Test
    fun trackerReauthenticationCallbackIsConsumedWithoutRecreatingDisclaimer() {
        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )
        val handlerStart = activitySource.indexOf("if (uri.scheme == \"r2creauth\")")
        val handlerEnd = activitySource.indexOf(
            "TrackerEnrollmentClient.normalizedEnrollmentUrl",
            startIndex = handlerStart,
        )
        assertTrue(handlerStart >= 0 && handlerEnd > handlerStart)
        val handler = activitySource.substring(handlerStart, handlerEnd)

        assertTrue(handler.contains("setIntent(Intent(this, R2CActivity::class.java)"))
        assertTrue(handler.contains("retryManagedConfigurationBootstrap(this)"))
        assertTrue(handler.contains(".resumeAfterReauthentication()"))
        assertFalse(handler.contains("recreate()"))
    }

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
    fun buildLogArchiveEntryName_collapsesLegacyDuplicateTxtSuffix() {
        assertEquals("Log_081054Apr13.txt", buildLogArchiveEntryName("Log_081054Apr13.txt.txt"))
    }

    @Test
    fun buildLogArchiveEntryName_defaultsBlankNames() {
        assertEquals("log_unknown.txt", buildLogArchiveEntryName(" "))
    }

    @Test
    fun trackerReauthenticationOpensOnlyOneBrowserAttemptAtATime() {
        assertTrue(shouldOpenTrackerReauthentication(browserAlreadyOpen = false))
        assertFalse(shouldOpenTrackerReauthentication(browserAlreadyOpen = true))
    }

    @Test
    fun trackerReauthenticationPresentsAnExplicitSignInAction() {
        val activitySource = projectSource(
            "app/src/main/java/org/ncssar/rid2caltopo/app/R2CActivity.kt"
        )

        assertTrue(activitySource.contains("Tracker sign-in required"))
        assertTrue(activitySource.contains("Text(\"Sign in\")"))
        assertTrue(activitySource.contains("Text(\"Continue offline\")"))
        assertTrue(activitySource.contains("onSignIn = ::openPendingTrackerReauthentication"))
    }

    private fun projectSource(relativePath: String): String {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        val candidates = listOf(
            File(workingDirectory, relativePath),
            File(workingDirectory.parentFile ?: workingDirectory, relativePath),
        )
        return requireNotNull(candidates.firstOrNull(File::isFile)) {
            "Unable to locate $relativePath from $workingDirectory"
        }.readText()
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.ncssar.rid2caltopo.video.ManagedVideoSessionRecordingCatalog

class MediaMTXConfigTest {
    @Test
    fun buildRuntimeConfig_disablesRecordingWhenCaptureOff() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = false,
            recordingRoot = File("/tmp/unused"),
        )

        assertTrue(config.contains("pathDefaults:\n  record: no"))
        assertFalse(config.contains("recordFormat: fmp4"))
        assertFalse(config.contains("\nrecord: no"))
    }

    @Test
    fun buildRuntimeConfig_enablesRecordingWhenCaptureOn() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = true,
            recordingRoot = File("/tmp/mediamtx-recordings"),
        )

        assertTrue(config.contains("pathDefaults:\n  record: yes"))
        assertTrue(config.contains("recordFormat: fmp4"))
        assertFalse(config.contains("\nrecord: yes"))
        assertTrue(config.contains("%path/%path_%Y-%m-%d_%H-%M-%S-%f"))
        assertTrue(config.contains("/tmp/mediamtx-recordings"))
    }

    @Test
    fun archiveTimestampIncludesDayAndTimeForOldAndNewFragments() {
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-08-12_09-20-51-000001.mp4"
            ) == "12Aug2026_092051"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "1sar7mn4pr_12Aug2026_092051-000001.mp4"
            ) == "12Aug2026_092051"
        )
    }

    @Test
    fun recordingSessionIdentitySurvivesCatalogRebuild() {
        val path = "/app/files/managed-video/map/1sar7_12Aug2026_092051.mp4"
        assertTrue(
            ManagedVideoSessionRecordingCatalog.sessionIdForPath(path) ==
                ManagedVideoSessionRecordingCatalog.sessionIdForPath(path)
        )
    }
}

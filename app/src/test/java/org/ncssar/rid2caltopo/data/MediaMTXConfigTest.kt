package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
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
    fun archiveTimestampLocalizesMediaMtxUtcAndPreservesExistingLocalNames() {
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-08-27_04-41-11-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "26Aug2026_214111_PDT"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-01-12_09-20-51-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "12Jan2026_012051_PST"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "1sar7mn4pr_12Aug2026_092051-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == "12Aug2026_092051_PDT"
        )
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "1sar7mn4pr_12Aug2026_092051_PDT-0700-000001.mp4",
                ZoneId.of("America/New_York"),
            ) == "12Aug2026_092051_PDT-0700"
        )
    }

    @Test
    fun archiveTimestampRejectsInvalidMediaMtxDate() {
        assertTrue(
            MediaMTXRecordingSync.archiveTimestampFromFragmentName(
                "2026-02-30_09-20-51-000001.mp4",
                ZoneId.of("America/Los_Angeles"),
            ) == null
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

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaMTXConfigTest {
    @Test
    fun buildRuntimeConfig_disablesRecordingWhenCaptureOff() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = false,
            recordingRoot = File("/tmp/unused"),
        )

        assertTrue(config.contains("record: no"))
        assertFalse(config.contains("recordFormat: fmp4"))
    }

    @Test
    fun buildRuntimeConfig_enablesRecordingWhenCaptureOn() {
        val config = MediaMTXConfig.buildRuntimeConfig(
            baseConfig = "logLevel: debug\nrtmp: yes\n",
            captureEnabled = true,
            recordingRoot = File("/tmp/mediamtx-recordings"),
        )

        assertTrue(config.contains("record: yes"))
        assertTrue(config.contains("recordFormat: fmp4"))
        assertTrue(config.contains("%path/%Y-%m-%d_%H-%M-%S-%f.mp4"))
        assertTrue(config.contains("/tmp/mediamtx-recordings"))
    }
}

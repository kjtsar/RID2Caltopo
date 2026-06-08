package org.ncssar.rid2caltopo.video.ffmpeg

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegBridgePressurePolicyTest {
    @Test
    fun liveAdQueuePressureFallsBackToRenderWithoutDisablingAd() {
        val bridgeSource = ffmpegBridgeSource()

        assertTrue(
            "native bridge should still have an explicit live AD queue pressure path",
            bridgeSource.contains("live-ad-pressure")
        )
        assertFalse(
            "live queue pressure should not disable AD and surface AD Overloaded",
            bridgeSource.contains("ad input queue full id=%lld designator=%s; disabling anomaly path")
        )
        assertFalse(
            "queue pressure should shed AD work instead of disabling the runtime",
            bridgeSource.contains("disable_anomaly_runtime(session, AD_PAUSE_REASON_OVERLOAD)")
        )
    }

    private fun ffmpegBridgeSource(): String {
        val projectDir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(projectDir, "src/main/cpp/ffmpeg_bridge.c"),
            File(projectDir, "app/src/main/cpp/ffmpeg_bridge.c"),
            File(projectDir.parentFile ?: projectDir, "app/src/main/cpp/ffmpeg_bridge.c")
        )
        val source = candidates.firstOrNull { it.isFile }
        requireNotNull(source) {
            "Unable to locate ffmpeg_bridge.c from ${projectDir.absolutePath}"
        }
        return source.readText()
    }
}

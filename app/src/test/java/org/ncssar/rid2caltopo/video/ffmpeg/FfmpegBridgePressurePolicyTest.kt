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

    @Test
    fun localPlaybackRenderControlUsesBacklogIntervalController() {
        val bridgeSource = ffmpegBridgeSource()
        val functionStart = bridgeSource.indexOf("static int64_t compute_desired_render_interval_ms_locked")
        assertTrue(
            "native bridge should have render interval control function",
            functionStart >= 0
        )
        val localBranchStart = bridgeSource.indexOf(
            "if (is_local_file_source(session)) {",
            startIndex = functionStart
        )
        assertTrue(
            "native bridge should have a local-file render control branch",
            localBranchStart >= 0
        )
        val localBranchEnd = bridgeSource.indexOf(
            "return smoothed_interval_ms;",
            startIndex = localBranchStart + 1
        )
        assertTrue(
            "local render control should return the smoothed backlog-aware interval",
            localBranchEnd > localBranchStart
        )
        val localBranch = bridgeSource.substring(localBranchStart, localBranchEnd)

        assertTrue(
            "local playback should use the backlog-aware render interval controller",
            localBranch.contains("anomaly_detector_runtime_budget_desired_render_interval_ms")
        )
    }

    @Test
    fun localRecordingDecoderWakesForRemoteVideoWithoutDisplaySurface() {
        val bridgeSource = ffmpegBridgeSource()
        val gateStart = bridgeSource.indexOf(
            "if (session->is_render && session->surface_paused &&"
        )
        assertTrue("native bridge should retain the surface-absent gate", gateStart >= 0)
        val gateEnd = bridgeSource.indexOf(
            "trace_begin_section(\"RID2C avcodec_send_packet\")",
            startIndex = gateStart
        )
        assertTrue("surface-absent gate should precede packet decoding", gateEnd > gateStart)
        val gate = bridgeSource.substring(gateStart, gateEnd)

        assertTrue(
            "a surface-less local decoder must wake when remote frame export starts",
            gate.contains("if (!surface_paused || remote_video_enabled) break;")
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

package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FfmpegBridgeRemoteVideoCadencePolicyTest {
    @Test
    fun remoteVideoCadence_accumulatesFractionalDeadline() {
        val source = sourceFile().readText()
        val start = source.indexOf("static void dispatch_remote_video_frame")
        val end = source.indexOf("static void", start + 1).let {
            if (it < 0) source.length else it
        }
        val function = source.substring(start, end)

        assertTrue(function.contains("double interval_ms = 1000.0 /"))
        assertTrue(function.contains("deadline_ms += interval_ms"))
        assertTrue(function.contains("interval_ms * 0.5"))
        assertFalse(function.contains("decoded_at_ms +\n                (int64_t) llround"))
    }

    private fun sourceFile(): File {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        return sequenceOf(
            File(projectDir, "src/main/cpp/ffmpeg_bridge.c"),
            File(projectDir, "app/src/main/cpp/ffmpeg_bridge.c"),
            File(projectDir.parentFile ?: projectDir, "app/src/main/cpp/ffmpeg_bridge.c"),
        ).firstOrNull(File::isFile)
            ?: error("Unable to locate ffmpeg_bridge.c from ${projectDir.absolutePath}")
    }
}

package org.ncssar.rid2caltopo.video.ffmpeg

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegBridgeLockOrderPolicyTest {
    @Test
    fun anomalyQueueCriticalSection_neverAcquiresGlobalSessionLock() {
        val function = cFunction("static void *ad_thread_main(void *arg) {", "typedef struct {")
        val criticalSectionStart = function.indexOf("pthread_mutex_lock(&session->ad_lock);")
        val criticalSectionEnd = function.indexOf(
            "pthread_mutex_unlock(&session->ad_lock);",
            criticalSectionStart,
        )
        assertTrue(criticalSectionStart >= 0)
        assertTrue(criticalSectionEnd > criticalSectionStart)

        val criticalSection = function.substring(criticalSectionStart, criticalSectionEnd)
        assertFalse(
            "AD queue lock must not call session_running(), which acquires g_lock",
            criticalSection.contains("session_running(session)"),
        )
        assertFalse(
            "AD queue lock must not directly acquire g_lock",
            criticalSection.contains("pthread_mutex_lock(&g_lock)"),
        )
    }

    @Test
    fun anomalyQueueAdmission_doesNotAcquireGlobalSessionLock() {
        val function = cFunction(
            "static bool enqueue_ad_input_frame_locked",
            "static bool dequeue_ad_input_frame_locked",
        )

        assertFalse(
            "Queue admission runs under ad_lock and must not call session_stopping()",
            function.contains("session_stopping(session)"),
        )
        assertFalse(
            "Queue admission runs under ad_lock and must not directly acquire g_lock",
            function.contains("pthread_mutex_lock(&g_lock)"),
        )
    }

    @Test
    fun overlayConfiguration_isSnapshottedBeforeAnalysisLock() {
        val function = cFunction(
            "static AVFrame *build_overlay_frame(ffmpeg_session_t *session,",
            "static bool apply_overlay_to_decoded_frame",
        )
        val globalLock = function.indexOf("pthread_mutex_lock(&g_lock);")
        val analysisLock = function.indexOf("pthread_mutex_lock(&session->anomaly_lock);")

        assertTrue("Expected configuration snapshot under g_lock", globalLock >= 0)
        assertTrue("Expected anomaly analysis critical section", analysisLock >= 0)
        assertTrue(
            "Global configuration must be copied before acquiring anomaly_lock",
            globalLock < analysisLock,
        )
        assertFalse(
            "Analysis critical section must not reacquire g_lock",
            function.substring(analysisLock).contains("pthread_mutex_lock(&g_lock)"),
        )
    }

    @Test
    fun renderQueueCriticalPaths_neverBlockOnGlobalSessionLock() {
        val dequeue = cFunction(
            "static bool dequeue_due_render_frame_locked",
            "static void render_cond_timed_wait_ms",
        )
        val enqueuePacket = cFunction(
            "static bool enqueue_render_packet_locked",
            "static bool enqueue_render_frame",
        )
        val enqueueFrame = cFunction(
            "static bool enqueue_render_frame",
            "static int ad_input_queue_tail_index",
        )

        assertFalse(dequeue.contains("pthread_mutex_lock(&g_lock)"))
        assertFalse(enqueuePacket.contains("session_stopping(session)"))
        assertFalse(enqueueFrame.contains("session_stopping(session)"))
    }

    @Test
    fun stopFlags_areWrittenUnderTheirPerSessionLocks() {
        val function = cFunction(
            "Java_org_ncssar_rid2caltopo_video_ffmpeg_FfmpegBridge_nativeStop",
            "JNIEXPORT jint JNICALL JNI_OnLoad",
        )
        val globalUnlock = function.indexOf("pthread_mutex_unlock(&g_lock);")
        val adLock = function.indexOf("pthread_mutex_lock(&session->ad_lock);")
        val adStop = function.indexOf("session->ad_thread_stop = true;")
        val renderLock = function.indexOf("pthread_mutex_lock(&session->render_lock);")
        val renderStop = function.indexOf("session->render_thread_stop = true;")

        assertTrue(globalUnlock >= 0)
        assertTrue(adLock > globalUnlock && adStop > adLock)
        assertTrue(renderLock > globalUnlock && renderStop > renderLock)
    }

    @Test
    fun managedVideoMetadataQuery_returnsCacheWithoutCallingNativeInline() {
        val source = sourceFile(
            "src/main/java/org/ncssar/rid2caltopo/video/ffmpeg/FfmpegProbeService.kt"
        ).readText()
        val startMarker = "fun videoSourceInfo(designator: String)"
        val endMarker = "private fun sessionIdsForDesignatorLocked"
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) { "Unable to isolate videoSourceInfo()" }
        val function = source.substring(start, end)

        assertTrue(
            "Native source metadata query must run on the FFmpeg control lane",
            function.contains("sessionExecutionLanes.executeControl"),
        )
        assertTrue(
            "UI caller must receive cached metadata without waiting for native code",
            function.contains("return request.second"),
        )
    }

    private fun cFunction(startMarker: String, endMarker: String): String {
        val source = sourceFile("src/main/cpp/ffmpeg_bridge.c").readText()
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        require(start >= 0 && end > start) {
            "Unable to isolate $startMarker from ffmpeg_bridge.c"
        }
        return source.substring(start, end)
    }

    private fun sourceFile(relativePath: String): File {
        val projectDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(projectDir, relativePath),
            File(projectDir, "app/$relativePath"),
            File(projectDir.parentFile ?: projectDir, "app/$relativePath"),
        )
        return requireNotNull(candidates.firstOrNull { it.isFile }) {
            "Unable to locate $relativePath from ${projectDir.absolutePath}"
        }
    }
}

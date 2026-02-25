package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaMtxLogParserTest {
    @Test
    fun parseLine_runOnReadyTwoLine_startedEmitsStarted() {
        val step1 = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[path alpha] runOnReady command",
        )
        assertNull(step1.event)
        assertEquals(setOf("alpha"), step1.state.pendingRunOnReadyPaths)

        val step2 = MediaMtxLogParser.parseLine(step1.state, "started")
        assertEquals(MediaMTXEvent.StreamStarted("alpha").path, (step2.event as MediaMTXEvent.StreamStarted).path)
        assertEquals(emptySet<String>(), step2.state.pendingRunOnReadyPaths)
    }

    @Test
    fun parseLine_rtmpPublishing_emitsStartedAndClearsPendingPath() {
        val preState = MediaMtxParserState(
            pendingRunOnReadyPaths = linkedSetOf("alpha"),
            pendingRunOnReadyVerbPaths = linkedSetOf("alpha"),
        )

        val result = MediaMtxLogParser.parseLine(
            preState,
            "[RTMP] [conn 127.0.0.1:5555] is publishing to path 'alpha'",
        )

        assertEquals(MediaMTXEvent.StreamStarted("alpha").path, (result.event as MediaMTXEvent.StreamStarted).path)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyPaths)
        assertEquals(emptySet<String>(), result.state.pendingRunOnReadyVerbPaths)
    }

    @Test
    fun parseLine_hlsCreated_emitsHlsStarted() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[HLS] [muxer bravo] created",
        )

        assertEquals(MediaMTXEvent.HlsStreamStarted("bravo").path, (result.event as MediaMTXEvent.HlsStreamStarted).path)
    }
}

package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("alpha", result.state.rtmpConnPathMap["127.0.0.1:5555"])
    }

    @Test
    fun parseLine_hlsCreated_emitsHlsStarted() {
        val result = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[HLS] [muxer bravo] created",
        )

        assertEquals(MediaMTXEvent.HlsStreamStarted("bravo").path, (result.event as MediaMTXEvent.HlsStreamStarted).path)
    }

    @Test
    fun parseLine_rtmpClosedWithMappedConnection_emitsErrorAndSuppressesImmediateStop() {
        val start = MediaMtxLogParser.parseLine(
            MediaMtxParserState(),
            "[RTMP] [conn 192.168.1.10:5000] is publishing to path 'alpha'",
        )
        assertTrue(start.event is MediaMTXEvent.StreamStarted)

        val closed = MediaMtxLogParser.parseLine(
            start.state,
            "[RTMP] [conn 192.168.1.10:5000] closed: received an audio packet, track is H264",
        )
        val error = closed.event as MediaMTXEvent.StreamError
        assertEquals("alpha", error.path)
        assertEquals(
            "RTMP closed: received an audio packet, track is H264",
            error.reason,
        )

        val stopped = MediaMtxLogParser.parseLine(
            closed.state,
            "[path alpha] runOnReady command stopped",
        )
        assertNull(stopped.event)
    }
}

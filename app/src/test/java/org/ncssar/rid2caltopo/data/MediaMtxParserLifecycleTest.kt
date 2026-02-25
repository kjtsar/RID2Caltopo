package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.testing.EventRecorder

class MediaMtxParserLifecycleTest {
    private fun parseEvents(lines: List<String>): List<MediaMTXEvent> {
        var state = MediaMtxParserState()
        val recorder = EventRecorder<MediaMTXEvent>()
        lines.forEach { line ->
            val result = MediaMtxLogParser.parseLine(state, line)
            state = result.state
            result.event?.let(recorder::record)
        }
        return recorder.events
    }

    @Test
    fun parseLine_singlePendingCanBeReplacedByNextPathBeforeStarted() {
        var state = MediaMtxParserState()

        val step1 = MediaMtxLogParser.parseLine(state, "[path alpha] runOnReady command")
        state = step1.state
        assertNull(step1.event)

        val step2 = MediaMtxLogParser.parseLine(state, "[path bravo] runOnReady command")
        state = step2.state
        assertNull(step2.event)

        val step3 = MediaMtxLogParser.parseLine(state, "started")
        state = step3.state
        val bravoStarted = step3.event as MediaMTXEvent.StreamStarted
        assertEquals("bravo", bravoStarted.path)

        val step4 = MediaMtxLogParser.parseLine(state, "[path alpha] runOnReady command started")
        val started = step4.event as MediaMTXEvent.StreamStarted
        assertEquals("alpha", started.path)
    }

    @Test
    fun parseLine_streamLifecycle_emitsOrderedTransitions() {
        val events = parseEvents(
            listOf(
                "[path scout1] created",
                "[RTMP] [conn 10.0.0.1:12000] is publishing to path 'scout1'",
                "no one is publishing to path 'scout1'",
            )
        )

        assertEquals(3, events.size)
        assertTrue(events[0] is MediaMTXEvent.StreamConnecting)
        assertTrue(events[1] is MediaMTXEvent.StreamStarted)
        assertTrue(events[2] is MediaMTXEvent.StreamStopped)
    }

    @Test
    fun parseLine_serverStartAndHlsCreated_areDetected() {
        val events = parseEvents(
            listOf(
                "MediaMTX v1.8.2",
                "[HLS] [muxer scout2] created",
            )
        )

        assertEquals(2, events.size)
        val serverStarted = events[0] as MediaMTXEvent.ServerStarted
        val hlsStarted = events[1] as MediaMTXEvent.HlsStreamStarted
        assertEquals("v1.8.2", serverStarted.version)
        assertEquals("scout2", hlsStarted.path)
    }
}

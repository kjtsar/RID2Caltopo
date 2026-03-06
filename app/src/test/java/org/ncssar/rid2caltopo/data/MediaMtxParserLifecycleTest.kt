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
    fun parseLine_runOnReadyTransitions_doNotEmitLifecycleEvents() {
        val events = parseEvents(
            listOf(
                "[path alpha] runOnReady command",
                "started",
                "[path alpha] runOnReady command started",
                "[path alpha] runOnReady command stopped",
            )
        )

        assertTrue(events.isEmpty())
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

    @Test
    fun dispatch_multilineChunk_splitsAndEmitsLifecycleEvents() {
        MediaMTXLogDispatcher.resetForTests()
        val recorder = EventRecorder<MediaMTXEvent>()
        MediaMTXLogDispatcher.addListener(recorder::record)

        MediaMTXLogDispatcher.dispatchChunk(
            """
            [path scout3] runOnReady command started
            [RTMP] [conn 10.0.0.1:12000] is publishing to path 'scout3'
            [HLS] [muxer scout3] created automatically
            """.trimIndent(),
            shouldLog = false,
        )

        assertEquals(2, recorder.events.size)
        assertTrue(recorder.events[0] is MediaMTXEvent.StreamStarted)
        assertTrue(recorder.events[1] is MediaMTXEvent.HlsStreamStarted)
    }

    @Test
    fun dispatch_splitPublishingLineAcrossChunks_reassemblesBeforeParsing() {
        MediaMTXLogDispatcher.resetForTests()
        val recorder = EventRecorder<MediaMTXEvent>()
        MediaMTXLogDispatcher.addListener(recorder::record)

        MediaMTXLogDispatcher.dispatchChunk(
            "[path autel/1SAR7EvMx4n] created\n[RTMP] [conn 10.0.0.1:12000] is publishing to p",
            shouldLog = false,
            flushTrailingFragment = false,
        )
        MediaMTXLogDispatcher.dispatchChunk(
            "ath 'autel/1SAR7EvMx4n'\n",
            shouldLog = false,
            flushTrailingFragment = false,
        )

        assertEquals(2, recorder.events.size)
        assertTrue(recorder.events[0] is MediaMTXEvent.StreamConnecting)
        assertTrue(recorder.events[1] is MediaMTXEvent.StreamStarted)
        assertEquals(
            "autel/1SAR7EvMx4n",
            (recorder.events[1] as MediaMTXEvent.StreamStarted).path,
        )
    }

    @Test
    fun parseLine_publisherRotation_emitsHandoffWithoutError() {
        val events = parseEvents(
            listOf(
                "[RTMP] [conn 10.0.0.1:12000] is publishing to path 'scout4'",
                "[path scout4] closing existing publisher",
                "[RTMP] [conn 10.0.0.1:12000] closed: terminated",
                "[RTMP] [conn 10.0.0.1:12001] is publishing to path 'scout4'",
            )
        )

        assertEquals(3, events.size)
        assertTrue(events[0] is MediaMTXEvent.StreamStarted)
        assertTrue(events[1] is MediaMTXEvent.StreamPublisherHandoff)
        assertTrue(events[2] is MediaMTXEvent.StreamStarted)
    }
}

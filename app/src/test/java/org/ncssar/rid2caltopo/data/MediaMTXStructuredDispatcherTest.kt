package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.testing.EventRecorder

class MediaMTXStructuredDispatcherTest {
    @Test
    fun parseEventJson_recordFileCompleted_decodesFinalizedFile() {
        val event = MediaMTXStructuredDispatcher.parseEventJson(
            """{"type":"record_file_completed","path":"alpha","filePath":"/records/alpha/file.mp4","durationMs":177400}"""
        ) as MediaMTXEvent.RecordFileCompleted

        assertEquals("alpha", event.path)
        assertEquals("/records/alpha/file.mp4", event.filePath)
        assertEquals(177_400L, event.durationMs)
    }

    @Test
    fun parseEventJson_streamStarted_decodesPath() {
        val event = MediaMTXStructuredDispatcher.parseEventJson(
            """{"type":"stream_started","path":"autel/1SAR7EvMx4n","publisherConnId":"127.0.0.1:5555"}"""
        )

        assertTrue(event is MediaMTXEvent.StreamStarted)
        val started = event as MediaMTXEvent.StreamStarted
        assertEquals("autel/1SAR7EvMx4n", started.path)
        assertEquals("127.0.0.1:5555", started.publisherConnId)
    }

    @Test
    fun dispatchEventJson_streamError_emitsListenerEvent() {
        MediaMTXStructuredDispatcher.resetForTests()
        val recorder = EventRecorder<MediaMTXEvent>()
        MediaMTXStructuredDispatcher.addListener(recorder::record)

        MediaMTXStructuredDispatcher.dispatchEventJson(
            """{"type":"stream_error","path":"autel/1SAR7EvMx4n","publisherConnId":"127.0.0.1:5555","reason":"RTMP closed: publisher disconnected unexpectedly"}"""
        )

        assertEquals(1, recorder.events.size)
        val event = recorder.events.single() as MediaMTXEvent.StreamError
        assertEquals("autel/1SAR7EvMx4n", event.path)
        assertEquals("127.0.0.1:5555", event.publisherConnId)
        assertEquals("RTMP closed: publisher disconnected unexpectedly", event.reason)
    }

    @Test
    fun dispatchEventJson_rtmpSessionClosed_emitsListenerEvent() {
        MediaMTXStructuredDispatcher.resetForTests()
        val recorder = EventRecorder<MediaMTXEvent>()
        MediaMTXStructuredDispatcher.addListener(recorder::record)

        MediaMTXStructuredDispatcher.dispatchEventJson(
            """{"type":"rtmp_session_closed","path":"autel/1SAR7EvMx4n","publisherConnId":"127.0.0.1:5555","reason":"RTMP closed: EOF"}"""
        )

        assertEquals(1, recorder.events.size)
        val event = recorder.events.single() as MediaMTXEvent.RtmpSessionClosed
        assertEquals("autel/1SAR7EvMx4n", event.path)
        assertEquals("127.0.0.1:5555", event.publisherConnId)
        assertEquals("RTMP closed: EOF", event.reason)
    }
}

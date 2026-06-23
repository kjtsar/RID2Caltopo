package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMTXBootstrapTest {
    @Test
    fun shouldApplyStructuredStreamLifecycleEvent_ignoresReaderProbeConnecting() {
        assertFalse(
            shouldApplyStructuredStreamLifecycleEvent(
                MediaMTXEvent.StreamConnecting("1sar7mn4pr")
            )
        )
    }

    @Test
    fun shouldApplyStructuredStreamLifecycleEvent_requiresPublisherForStop() {
        assertFalse(
            shouldApplyStructuredStreamLifecycleEvent(
                MediaMTXEvent.StreamStopped(path = "1sar7mn4pr", publisherConnId = null)
            )
        )
        assertTrue(
            shouldApplyStructuredStreamLifecycleEvent(
                MediaMTXEvent.StreamStopped(path = "1sar7mn4pr", publisherConnId = "10.0.0.1:12000")
            )
        )
    }
}

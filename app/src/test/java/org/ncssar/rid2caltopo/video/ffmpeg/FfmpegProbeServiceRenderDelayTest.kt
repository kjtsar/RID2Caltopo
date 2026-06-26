package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FfmpegProbeServiceRenderDelayTest {
    @Test
    fun quantizeRenderDelayMs_usesSmallBucketsForSubsecondLag() {
        assertEquals(600L, quantizeRenderDelayMs(649L))
        assertEquals(700L, quantizeRenderDelayMs(650L))
    }

    @Test
    fun quantizeRenderDelayMs_usesBroaderBucketsAsLagGrows() {
        assertEquals(2_250L, quantizeRenderDelayMs(2_350L))
        assertEquals(10_000L, quantizeRenderDelayMs(10_240L))
    }

    @Test
    fun updateSourceLagEstimate_tracksLagFromBestObservedSourceOffset() {
        val anchored = updateSourceLagEstimate(
            sourceClockOffsetMs = null,
            lastSourceTimestampUs = null,
            sourceTimestampUs = 9_500_000L,
            observedAtMs = 10_000L,
        )
        assertEquals(500L, anchored.sourceClockOffsetMs)
        assertEquals(0L, anchored.estimatedLagMs)

        val lagged = updateSourceLagEstimate(
            sourceClockOffsetMs = anchored.sourceClockOffsetMs,
            lastSourceTimestampUs = anchored.lastSourceTimestampUs,
            sourceTimestampUs = 10_500_000L,
            observedAtMs = 12_000L,
        )
        assertEquals(500L, lagged.sourceClockOffsetMs)
        assertEquals(1_000L, lagged.estimatedLagMs)
    }

    @Test
    fun updateSourceLagEstimate_resetsAnchorWhenSourceTimestampJumpsBackward() {
        val lagged = updateSourceLagEstimate(
            sourceClockOffsetMs = 500L,
            lastSourceTimestampUs = 10_500_000L,
            sourceTimestampUs = 200_000L,
            observedAtMs = 20_000L,
        )
        assertEquals(19_800L, lagged.sourceClockOffsetMs)
        assertEquals(0L, lagged.estimatedLagMs)
        assertEquals(200_000L, lagged.lastSourceTimestampUs)
    }

    @Test
    fun shouldRecoverRenderedNoProgress_doesNotRestartLocalFilePlayback() {
        assertFalse(
            shouldRecoverRenderedNoProgress(
                isLocalFilePlayback = true,
                renderedFrameCount = 47L,
                idlePollCount = 78,
                recoveryPollThreshold = 8,
                hasRecentReaderWait = false,
            )
        )
    }
}

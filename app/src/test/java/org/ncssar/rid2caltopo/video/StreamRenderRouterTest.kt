package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRenderRouterTest {

    // --- Helpers ---

    private fun liveInfo(designator: String) =
        StreamInfo(designator = designator, state = StreamState.LIVE)

    private fun nonLiveInfo(designator: String, state: StreamState = StreamState.CONNECTING) =
        StreamInfo(designator = designator, state = state)

    private fun useFfmpeg(
        designator: String,
        liveStreams: Map<String, StreamInfo>,
        focusedDesignator: String? = null,
        ffmpegAvailable: Boolean = true,
        displayedTileCount: Int = displayedTileCount(liveStreams, focusedDesignator),
    ) = StreamRenderRouter.useFfmpeg(
        designator = designator,
        liveStreams = liveStreams,
        focusedDesignator = focusedDesignator,
        ffmpegAvailable = ffmpegAvailable,
        displayedTileCount = displayedTileCount,
    )

    private fun displayedTileCount(
        liveStreams: Map<String, StreamInfo>,
        focusedDesignator: String?,
    ): Int {
        val liveCount = liveStreams.values.count { it.state == StreamState.LIVE }
        if (liveCount <= 0) return 0
        return if (focusedDesignator != null) 1 else liveCount
    }

    // --- Single stream ---

    @Test
    fun singleStream_noFocus_usesFfmpeg() {
        val streams = mapOf("d1" to liveInfo("d1"))
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = null))
    }

    @Test
    fun singleStream_focusedOnIt_usesFfmpeg() {
        val streams = mapOf("d1" to liveInfo("d1"))
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = "d1"))
    }

    @Test
    fun singleStream_focusedOnOtherDesignator_usesExoPlayer() {
        // Focused on something that isn't in the stream map — treat as if there's a focus
        // on a different stream, so this stream loses its "sole stream" FFmpeg slot.
        val streams = mapOf("d1" to liveInfo("d1"))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "other"))
    }

    // --- Multiple streams, no focus ---

    @Test
    fun twoStreams_noFocus_bothUseExoPlayer() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        // Neither gets FFmpeg when there's no focus and count > 1
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = null))
    }

    @Test
    fun threeStreams_noFocus_allUseExoPlayer() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
            "d3" to liveInfo("d3"),
        )
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d3", streams, focusedDesignator = null))
    }

    // --- Multiple streams, with focus ---

    @Test
    fun twoStreams_oneFocused_focusedUsesFfmpegOtherUsesExoPlayer() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = "d1"))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = "d1"))
    }

    @Test
    fun threeStreams_oneFocused_onlyFocusedUsesFfmpeg() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
            "d3" to liveInfo("d3"),
        )
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = "d1"))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = "d1"))
        assertFalse(useFfmpeg("d3", streams, focusedDesignator = "d1"))
    }

    @Test
    fun focusedStream_withMultipleDisplayedTiles_usesExoPlayer() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "d1", displayedTileCount = 2))
    }

    // --- Transition: sole stream gains a second stream ---

    @Test
    fun secondStreamArrives_firstStreamLosesFfmpegWhenUnfocused() {
        val singleStream = mapOf("d1" to liveInfo("d1"))
        assertTrue(useFfmpeg("d1", singleStream, focusedDesignator = null))

        val twoStreams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        assertFalse(useFfmpeg("d1", twoStreams, focusedDesignator = null))
        assertFalse(useFfmpeg("d2", twoStreams, focusedDesignator = null))
    }

    @Test
    fun secondStreamArrives_focusedFirstStreamKeepsFfmpeg() {
        val twoStreams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        assertTrue(useFfmpeg("d1", twoStreams, focusedDesignator = "d1"))
        assertFalse(useFfmpeg("d2", twoStreams, focusedDesignator = "d1"))
    }

    // --- Focus switch between streams ---

    @Test
    fun focusSwitch_newFocusedStreamGetsFfmpegOldLoseIt() {
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to liveInfo("d2"),
        )
        // d1 was focused
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = "d1"))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = "d1"))

        // focus switches to d2
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "d2"))
        assertTrue(useFfmpeg("d2", streams, focusedDesignator = "d2"))
    }

    // --- Non-live streams ---

    @Test
    fun connectingStream_neverUsesFfmpeg() {
        val streams = mapOf("d1" to nonLiveInfo("d1", StreamState.CONNECTING))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "d1"))
    }

    @Test
    fun errorStream_neverUsesFfmpeg() {
        val streams = mapOf("d1" to nonLiveInfo("d1", StreamState.ERROR))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "d1"))
    }

    @Test
    fun mixedLiveAndConnecting_liveCountOnlyConsidersLiveStreams() {
        // d1 is live, d2 is connecting — d1 should still get FFmpeg as the sole live stream
        val streams = mapOf(
            "d1" to liveInfo("d1"),
            "d2" to nonLiveInfo("d2", StreamState.CONNECTING),
        )
        assertTrue(useFfmpeg("d1", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("d2", streams, focusedDesignator = null))
    }

    // --- FFmpeg unavailable ---

    @Test
    fun ffmpegUnavailable_singleStream_usesExoPlayer() {
        val streams = mapOf("d1" to liveInfo("d1"))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = null, ffmpegAvailable = false))
    }

    @Test
    fun ffmpegUnavailable_focusedStream_usesExoPlayer() {
        val streams = mapOf("d1" to liveInfo("d1"))
        assertFalse(useFfmpeg("d1", streams, focusedDesignator = "d1", ffmpegAvailable = false))
    }

    // --- Unknown designator ---

    @Test
    fun unknownDesignator_returnsFalse() {
        val streams = mapOf("d1" to liveInfo("d1"))
        assertFalse(useFfmpeg("ghost", streams, focusedDesignator = null))
        assertFalse(useFfmpeg("ghost", streams, focusedDesignator = "ghost"))
    }

    @Test
    fun emptyStreamMap_returnsFalse() {
        assertFalse(useFfmpeg("d1", emptyMap(), focusedDesignator = null))
    }
}

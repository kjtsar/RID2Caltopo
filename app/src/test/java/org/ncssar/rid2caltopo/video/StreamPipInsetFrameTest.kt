package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamPipInsetFrameTest {
    @Test
    fun streamPipInsetSize_usesSixteenByNineAspectRatio() {
        val size = streamPipInsetSize(
            maxWidth = 1000f,
            maxHeight = 600f,
            insetFraction = 0.33f
        )

        assertEquals(322.08f, size.width, 0.01f)
        assertEquals(181.17f, size.height, 0.01f)
        assertEquals(STREAM_PIP_ASPECT_RATIO, size.width / size.height, 0.0001f)
    }

    @Test
    fun streamPipInsetSize_capsWidthWhenHeightWouldOverflow() {
        val size = streamPipInsetSize(
            maxWidth = 1000f,
            maxHeight = 180f,
            insetFraction = 0.55f
        )

        assertEquals(277.33f, size.width, 0.01f)
        assertEquals(156.0f, size.height, 0.01f)
        assertEquals(STREAM_PIP_ASPECT_RATIO, size.width / size.height, 0.0001f)
    }

    @Test
    fun streamPipHasStreamContent_allowsSingleVisibleStreamWithoutFocus() {
        assertEquals(true, streamPipHasStreamContent(visibleStreamCount = 1, focusedPath = null))
        assertEquals(true, streamPipHasStreamContent(visibleStreamCount = 0, focusedPath = "drone-1"))
        assertEquals(false, streamPipHasStreamContent(visibleStreamCount = 0, focusedPath = null))
    }

    @Test
    fun streamPipInsetTap_swapsBetweenStreamsAndMapLayouts() {
        assertEquals(StreamsLayoutMode.Map, streamPipInsetTapLayoutMode(StreamsLayoutMode.Streams))
        assertEquals(StreamsLayoutMode.Streams, streamPipInsetTapLayoutMode(StreamsLayoutMode.Map))
        assertEquals(StreamsLayoutMode.Both, streamPipInsetTapLayoutMode(StreamsLayoutMode.Both))
    }

    @Test
    fun streamsFullScreenChrome_hidesTopBarOnlyWhenAllowedAndActive() {
        assertEquals(
            StreamsFullScreenChrome(showTopBar = true, showExitChip = false),
            streamsFullScreenChrome(fullScreen = false, externalContentActive = false)
        )
        assertEquals(
            StreamsFullScreenChrome(showTopBar = false, showExitChip = true),
            streamsFullScreenChrome(fullScreen = true, externalContentActive = false)
        )
        assertEquals(
            StreamsFullScreenChrome(showTopBar = true, showExitChip = false),
            streamsFullScreenChrome(fullScreen = true, externalContentActive = true)
        )
    }

    @Test
    fun fullScreenExitChipLayout_keepsChipAwayFromSettingsWithLargeHitTarget() {
        assertEquals(
            FullScreenExitChipLayout(minWidthDp = 96f, minHeightDp = 48f, endPaddingDp = 84f),
            fullScreenExitChipLayout()
        )
    }

    @Test
    fun streamTileChromePresentation_fillsContainerAndHidesDuplicateTelemetryInFullScreen() {
        assertEquals(
            StreamTileChromePresentation(
                fillContainer = false,
                showStandaloneTelemetryOverlay = false
            ),
            streamTileChromePresentation(fullScreenContent = false, focused = true)
        )
        assertEquals(
            StreamTileChromePresentation(
                fillContainer = true,
                showStandaloneTelemetryOverlay = false
            ),
            streamTileChromePresentation(fullScreenContent = true, focused = true)
        )
    }

    @Test
    fun streamClueCaptureReady_requiresFfmpegTextureViewAndRenderedFrame() {
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = false,
                renderedFrameCount = 0,
                requiresRenderedFrame = true
            )
        )
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 0,
                requiresRenderedFrame = true
            )
        )
        assertEquals(
            true,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 1,
                requiresRenderedFrame = true
            )
        )
    }

    @Test
    fun streamClueCaptureReady_allowsPlayerTextureViewWithoutFfmpegFrameCallback() {
        assertEquals(
            false,
            streamClueCaptureReady(
                hasCaptureTarget = false,
                renderedFrameCount = 0,
                requiresRenderedFrame = false
            )
        )
        assertEquals(
            true,
            streamClueCaptureReady(
                hasCaptureTarget = true,
                renderedFrameCount = 0,
                requiresRenderedFrame = false
            )
        )
    }

    @Test
    fun streamClueCaptureStateKey_survivesStreamRevisionChanges() {
        assertEquals(
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 2L),
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 3L)
        )
        assertEquals(
            false,
            streamClueCaptureStateKey(designator = "1sar34DjN2", streamRevision = 2L) ==
                streamClueCaptureStateKey(designator = "neo2", streamRevision = 2L)
        )
    }

    @Test
    fun streamTileFocusPresentation_treatsSingleDisplayedTileAsFocusedWithoutBorder() {
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 1, explicitlyFocused = false)
        )
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 1, explicitlyFocused = true)
        )
    }

    @Test
    fun streamTileFocusPresentation_requiresExplicitFocusWhenGridHasMultipleTiles() {
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = false, showFocusBorder = false),
            streamTileFocusPresentation(displayedTileCount = 2, explicitlyFocused = false)
        )
        assertEquals(
            StreamTileFocusPresentation(effectiveFocused = true, showFocusBorder = true),
            streamTileFocusPresentation(displayedTileCount = 2, explicitlyFocused = true)
        )
    }
}

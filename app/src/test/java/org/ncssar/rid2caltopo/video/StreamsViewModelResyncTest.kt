import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.video.StreamInfo
import org.ncssar.rid2caltopo.video.StreamState
import org.ncssar.rid2caltopo.video.handleStreamTileSingleTap

class StreamsViewModelResyncTest {
    @Test
    fun managedVideoStartsDecoderWithoutAVisibleStreamTile() {
        assertTrue(
            shouldEnsureManagedVideoRenderSession(
                managedVideoSourceRequired = true,
                activeRenderSessionId = null,
            )
        )
        assertFalse(
            shouldEnsureManagedVideoRenderSession(
                managedVideoSourceRequired = false,
                activeRenderSessionId = null,
            )
        )
        assertFalse(
            shouldEnsureManagedVideoRenderSession(
                managedVideoSourceRequired = true,
                activeRenderSessionId = 41L,
            )
        )
    }

    @Test
    fun streamTileSingleTap_exitsSplitInsteadOfTogglingStreamFocus() {
        var splitExitCount = 0
        var focusToggleCount = 0

        handleStreamTileSingleTap(
            onStreamsPaneTap = { splitExitCount += 1 },
            onToggleFocus = { focusToggleCount += 1 },
        )

        assertEquals(1, splitExitCount)
        assertEquals(0, focusToggleCount)
    }

    @Test
    fun streamTileSingleTap_togglesFocusOutsideSplit() {
        var focusToggleCount = 0

        handleStreamTileSingleTap(
            onStreamsPaneTap = null,
            onToggleFocus = { focusToggleCount += 1 },
        )

        assertEquals(1, focusToggleCount)
    }

    @Test
    fun managedVideoConsumerKeepsDecoderWhenStreamsUiIsInactive() {
        assertTrue(
            shouldKeepFfmpegRender(
                streamsUiActive = false,
                normalRenderSelected = false,
                managedVideoSourceRequired = true,
            )
        )
        assertFalse(
            shouldKeepFfmpegRender(
                streamsUiActive = false,
                normalRenderSelected = true,
                managedVideoSourceRequired = false,
            )
        )
    }

    @Test
    fun previewLeaseReleaseDoesNotReleaseAnActiveRemoteRequestSource() {
        assertEquals(
            setOf("Rescue 1"),
            managedVideoRequiredSources(
                requestSources = setOf("Rescue 1"),
                previewSources = emptySet(),
            ),
        )
    }

    @Test
    fun chooseResyncSnapshot_prefersLastSyncedMapWhenFlowSnapshotIsMomentarilyEmpty() {
        val lastSynced = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 2L,
            )
        )

        val chosen = chooseResyncSnapshot(
            lastSyncedStreams = lastSynced,
            latestFlowValue = emptyMap(),
        )

        assertEquals(lastSynced, chosen)
    }

    @Test
    fun chooseResyncSnapshot_usesLatestFlowSnapshotWhenItIsAvailable() {
        val lastSynced = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 2L,
            )
        )
        val latestFlow = mapOf(
            "1SAR7n" to StreamInfo(
                designator = "1SAR7n",
                sourcePath = "1SAR7n",
                state = StreamState.LIVE,
                revision = 3L,
            )
        )

        val chosen = chooseResyncSnapshot(
            lastSyncedStreams = lastSynced,
            latestFlowValue = latestFlow,
        )

        assertEquals(latestFlow, chosen)
    }

    @Test
    fun focusAfterStreamSync_clearsFocusWhenSecondStreamBecomesLive() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr"),
            newlyVisibleLiveDesignators = setOf("1sar7mn4pr"),
        )

        assertEquals(null, focus)
    }

    @Test
    fun focusAfterStreamSync_clearsFocusWhenFourthStreamBecomesLive() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr", "1sar83", "1sar50"),
            newlyVisibleLiveDesignators = setOf("1sar50"),
        )

        assertEquals(null, focus)
    }

    @Test
    fun focusAfterStreamSync_keepsManualFocusWhenNoNewStreamArrived() {
        val focus = focusAfterStreamSync(
            currentFocus = "1sar7mn4pr",
            liveDesignators = setOf("1SAR33", "1sar7mn4pr"),
            newlyVisibleLiveDesignators = emptySet(),
        )

        assertEquals("1sar7mn4pr", focus)
    }

    @Test
    fun focusAfterStreamSync_clearsFocusWhenFocusedStreamDisappears() {
        val focus = focusAfterStreamSync(
            currentFocus = "1SAR33",
            liveDesignators = setOf("1sar7mn4pr"),
            newlyVisibleLiveDesignators = emptySet(),
        )

        assertEquals(null, focus)
    }

    @Test
    fun capturedVideoPlaybackPlan_replacesExistingLocalPlaybackInsteadOfCreatingDuplicate() {
        val plan = capturedVideoPlaybackPlan(
            normalizedName = "Red1.mp4",
            activeStreams = emptyMap(),
            localPlaybackEntries = mapOf(
                "Red1.mp4" to StreamInfo(
                    designator = "Red1.mp4",
                    state = StreamState.CONNECTING,
                    isLocalPlayback = true,
                )
            ),
        )

        assertEquals("Red1.mp4", plan.designator)
        assertEquals(setOf("Red1.mp4"), plan.localPlaybackDesignatorsToClose)
    }
}

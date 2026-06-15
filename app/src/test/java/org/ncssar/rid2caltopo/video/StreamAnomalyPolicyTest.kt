package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAnomalyPolicyTest {
    @Test
    fun focusedRealtimeStreamWithEnabledConfig_enablesNativeAnomaly() {
        assertTrue(
            StreamRenderRouter.shouldEnableNativeAnomaly(
                designator = "d1",
                focusedDesignator = "d1",
                isLocalPlayback = false,
                configEnabled = true,
            )
        )
    }

    @Test
    fun unfocusedRealtimeStreamWithEnabledConfig_disablesNativeAnomaly() {
        assertFalse(
            StreamRenderRouter.shouldEnableNativeAnomaly(
                designator = "d1",
                focusedDesignator = null,
                isLocalPlayback = false,
                configEnabled = true,
            )
        )
    }

    @Test
    fun localPlaybackWithEnabledConfig_enablesNativeAnomalyWithoutFocus() {
        assertTrue(
            StreamRenderRouter.shouldEnableNativeAnomaly(
                designator = "PowerHouse1.mp4",
                focusedDesignator = null,
                isLocalPlayback = true,
                configEnabled = true,
            )
        )
    }

    @Test
    fun localPlaybackWithDisabledConfig_disablesNativeAnomaly() {
        assertFalse(
            StreamRenderRouter.shouldEnableNativeAnomaly(
                designator = "PowerHouse1.mp4",
                focusedDesignator = null,
                isLocalPlayback = true,
                configEnabled = false,
            )
        )
    }
}

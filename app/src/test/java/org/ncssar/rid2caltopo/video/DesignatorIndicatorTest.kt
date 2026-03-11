package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class DesignatorIndicatorTest {
    @Test
    fun formatLiveState_reportsLiveUntilLagIsMaterial() {
        assertEquals("Live", formatLiveState(null))
        assertEquals("Live", formatLiveState(450L))
    }

    @Test
    fun formatLiveState_formatsSubsecondAndSecondLag() {
        assertEquals("lag:750ms", formatLiveState(750L))
        assertEquals("lag:2.3s", formatLiveState(2_250L))
    }
}

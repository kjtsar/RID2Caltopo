package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class ManagedVideoStreamPresenceTest {
    @After
    fun resetPresence() {
        ManagedVideoStreamPresence.resetForTests()
    }

    @Test
    fun `live stream is advertised before any telemetry association exists`() {
        val advertisements = ManagedVideoStreamPresence.snapshot(
            streams = mapOf(
                "camera/path" to StreamInfo(
                    designator = "Rescue 1",
                    sourcePath = "camera/path",
                    state = StreamState.LIVE,
                )
            ),
        )

        assertEquals(listOf("Rescue 1"), advertisements.map { it.droneDesignator })
        assertTrue(ManagedVideoStreamPresence.hasLiveDesignator("Rescue 1"))
    }

    @Test
    fun `implausible controller time base is advertised as nominal thirty fps`() {
        assertEquals(30.0, nominalManagedVideoSourceFps(240.0), 0.0)
        assertEquals(30.0, nominalManagedVideoSourceFps(24.0), 0.0)
    }

    @Test
    fun `slow and unavailable source cadence remains honest`() {
        assertEquals(12.5, nominalManagedVideoSourceFps(12.5), 0.0)
        assertEquals(0.0, nominalManagedVideoSourceFps(0.0), 0.0)
        assertEquals(0.0, nominalManagedVideoSourceFps(Double.NaN), 0.0)
    }
}

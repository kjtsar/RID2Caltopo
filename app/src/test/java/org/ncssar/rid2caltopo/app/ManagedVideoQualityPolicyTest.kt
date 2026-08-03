package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.VideoStreamViewRequest

class ManagedVideoQualityPolicyTest {
    private fun request(width: Int, height: Int, fps: Double, bitrate: Long) =
        VideoStreamViewRequest(
            "request", "viewer@example.org", "stream", "Training", "1sar1m4pr",
            width, height, fps, bitrate, "H264", "", true,
        )

    @Test
    fun `high is a complete 720p30 preset`() {
        val choices = videoQualityChoices(request(1920, 1080, 30.0, 4_000_000), 5_000_000)
        assertEquals(listOf("High", "Balanced", "Low", "Emergency"), choices.map { it.preset })
        assertEquals(1280 to 720, choices.first().let { it.width to it.height })
        assertEquals(30.0, choices.first().fps, 0.0)
        assertEquals(2_500_000L, choices.first().bitrateBps)
        assertTrue(choices.all { it.width % 2 == 0 && it.height % 2 == 0 })
    }

    @Test
    fun `measured flight uplink can select high instead of falling back to balanced`() {
        val high = videoQualityChoices(
            request(1280, 720, 30.0, 4_000_000),
            2_836_000,
        ).first()

        assertEquals("High", high.preset)
        assertEquals(LinkCapacity.MARGINAL, high.capacity)
    }

    @Test
    fun `emergency needs less bandwidth than balanced`() {
        val choices = videoQualityChoices(request(1920, 1080, 30.0, 6_000_000), 2_000_000)
        val balanced = choices.first { it.preset == "Balanced" }
        val emergency = choices.first { it.preset == "Emergency" }
        assertTrue(emergency.bitrateBps < balanced.bitrateBps)
        assertTrue(emergency.capacity != LinkCapacity.INSUFFICIENT)
    }

    @Test
    fun `bursty controller cadence still offers nominal thirty fps`() {
        val choices = videoQualityChoices(request(1280, 720, 21.0, 0), 10_000_000)
        assertEquals(30.0, choices.first().fps, 0.0)
        assertTrue(choices.first().capacity == LinkCapacity.ENOUGH)
    }

    @Test
    fun `small sources are not upscaled`() {
        val choices = videoQualityChoices(request(640, 480, 8.0, 0), 2_000_000)
        assertTrue(choices.all { it.width == 640 && it.height == 480 })
        assertTrue(choices.all { it.fps <= 8.0 })
    }

    @Test
    fun `failed-low measurement enables only the smallest fallback`() {
        val choices = videoQualityChoices(request(1280, 720, 30.0, 1_900_000), 100_000)
        val startable = choices.filter { it.capacity != LinkCapacity.INSUFFICIENT }

        assertEquals(1, startable.size)
        assertEquals(LinkCapacity.FALLBACK, startable.single().capacity)
        assertEquals(640 to 360, startable.single().let { it.width to it.height })
        assertEquals(5.0, startable.single().fps, 0.0)
        assertTrue(startable.single().bitrateBps <= 200_000)
    }
}

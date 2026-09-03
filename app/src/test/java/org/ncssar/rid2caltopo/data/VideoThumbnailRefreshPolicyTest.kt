package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoThumbnailRefreshPolicyTest {
    @Test
    fun `missing or invalid values use five second default`() {
        assertEquals(5.0, VideoThumbnailRefreshPolicy.normalize(null), 0.0)
        assertEquals(5.0, VideoThumbnailRefreshPolicy.normalize(Double.NaN), 0.0)
        assertEquals(5.0, VideoThumbnailRefreshPolicy.normalize(Double.POSITIVE_INFINITY), 0.0)
    }

    @Test
    fun `refresh interval is clamped and rendered in decimal seconds`() {
        assertEquals(0.5, VideoThumbnailRefreshPolicy.normalize(0.1), 0.0)
        assertEquals(60.0, VideoThumbnailRefreshPolicy.normalize(90.0), 0.0)
        assertEquals(2.3, VideoThumbnailRefreshPolicy.normalize(2.26), 0.0)
        assertEquals("5.0", VideoThumbnailRefreshPolicy.format(5.0))
        assertEquals(2_300L, VideoThumbnailRefreshPolicy.milliseconds(2.26))
    }
}

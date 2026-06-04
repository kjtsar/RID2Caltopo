package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.ncssar.rid2caltopo.BuildConfig

class ScannerScreenStatusTextTest {
    @Test
    fun buildStatusText_placesBuildVersionAndTimeAtTop() {
        val lines = buildStatusText(emptyList()).lines()

        assertEquals("BUILD_VERSION: ${BuildConfig.BUILD_VERSION}", lines[0])
        assertEquals("BUILD_TIME: ${BuildConfig.BUILD_TIME}", lines[1])
        assertEquals("", lines[2])
        assertEquals("Scanner Status", lines[3])
    }
}

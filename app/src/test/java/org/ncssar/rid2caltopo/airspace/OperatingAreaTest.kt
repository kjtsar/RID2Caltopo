package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Test

class OperatingAreaTest {
    @Test
    fun bvlosWaiverRadiusIsOneStatuteMile() {
        assertEquals(1.0, OperatingArea.radiusStatuteMiles, 0.0)
        assertEquals(0.868976, OperatingArea.radiusNm, 0.000001)
        assertEquals("1 mi operating area", OperatingArea.displayLabel)
    }
}

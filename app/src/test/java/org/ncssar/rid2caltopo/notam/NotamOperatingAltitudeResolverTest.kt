package org.ncssar.rid2caltopo.notam

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotamOperatingAltitudeResolverTest {
    @Test
    fun fallsBackToDemElevationWhenGpsAltitudeMissing() {
        val location = Location("test").apply {
            latitude = 39.07171
            longitude = -121.55153
        }

        val band = requireNotNull(NotamOperatingAltitudeResolver.resolve(location, demElevationMeters = 18.0))
        assertEquals(59.06, band.floorFeetMsl, 0.1)
        assertEquals(459.06, band.ceilingFeetMsl, 0.1)
    }

    @Test
    fun returnsNullWhenNoAltitudeSourceExists() {
        val location = Location("test").apply {
            latitude = 39.07171
            longitude = -121.55153
        }

        assertNull(NotamOperatingAltitudeResolver.resolve(location))
    }
}

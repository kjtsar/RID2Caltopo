package org.ncssar.rid2caltopo.notam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NotamAltitudeParserTest {
    @Test
    fun parsesSharedReferenceAltitudeBand() {
        val band = NotamAltitudeParser.parse(
            "AIRSPACE UAS WI AN AREA DEFINED AS 1.5NM RADIUS SFC-1200FT AGL"
        )

        val parsed = requireNotNull(band)
        assertNotNull(parsed)
        assertEquals(0.0, parsed.floorFeetMsl ?: Double.NaN, 0.01)
        assertNull(parsed.ceilingFeetMsl)
        assertEquals("SFC", parsed.floorLabel)
        assertEquals("1200FT", parsed.ceilingLabel)
        assertEquals("AGL", parsed.reference)
    }

    @Test
    fun parsesMixedReferenceAltitudeBand() {
        val band = NotamAltitudeParser.parse(
            "AIRSPACE WI AN AREA DEFINED AS 4100FT MSL-FL180"
        )

        val parsed = requireNotNull(band)
        assertNotNull(parsed)
        assertEquals(4100.0, parsed.floorFeetMsl ?: Double.NaN, 0.01)
        assertEquals(18000.0, parsed.ceilingFeetMsl ?: Double.NaN, 0.01)
        assertEquals("4100FT MSL", parsed.floorLabel)
        assertEquals("FL180", parsed.ceilingLabel)
        assertNull(parsed.reference)
    }
}

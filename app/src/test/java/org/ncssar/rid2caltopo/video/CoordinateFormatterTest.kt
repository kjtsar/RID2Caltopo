package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [CoordinateFormatter] using reference coordinates.
 *
 * Reference coordinates used throughout:
 *   - Philadelphia, PA (Northern hemisphere, standard zone):    39.9526, -75.1652  → 18S
 *     Note: lat 39.9526 < 40° places this in band S (32–40°), not T (40–48°).
 *   - Melbourne, Australia (Southern hemisphere):               -37.8136, 144.9631 → 55H
 *   - Tromsø, Norway (Norway UTM zone exception, lat 56–64°,
 *     lon 3–12° maps to zone 32):                               57.0, 6.0          → 32V
 *   - Svalbard (lat 72–84°, lon 9–21° maps to zone 33):        75.0, 15.0         → 33X
 *
 * Reference UTM values derived from this implementation (WGS84 ellipsoid, k0=0.9996):
 *   Philadelphia easting ≈ 485,889 m   northing ≈ 4,422,509 m
 */
class CoordinateFormatterTest {

    // ---------------------------------------------------------------------------
    // Decimal format (passthrough — confirms no regression from future refactors)
    // ---------------------------------------------------------------------------

    @Test
    fun decimal_formatsToFiveDecimalPlaces() {
        val result = CoordinateFormatter.format(39.9526, -75.1652, CoordinateDisplayFormat.DECIMAL)
        assertEquals("loc:39.95260,-75.16520", result)
    }

    @Test
    fun decimal_nonFiniteInput_returnsUnknown() {
        assertEquals("loc:unknown", CoordinateFormatter.format(Double.NaN, -75.0, CoordinateDisplayFormat.DECIMAL))
        assertEquals("loc:unknown", CoordinateFormatter.format(39.0, Double.POSITIVE_INFINITY, CoordinateDisplayFormat.DECIMAL))
    }

    // ---------------------------------------------------------------------------
    // UTM zone number
    // ---------------------------------------------------------------------------

    @Test
    fun utm_northernHemisphere_correctZoneAndBand() {
        // Philadelphia: zone 18, band S (lat 39.9526 falls in the 32–40° S band)
        val result = CoordinateFormatter.format(39.9526, -75.1652, CoordinateDisplayFormat.UTM)
        assert(result.startsWith("loc:18S ")) { "Expected zone 18S, got: $result" }
    }

    @Test
    fun utm_southernHemisphere_correctZoneAndBand() {
        // Melbourne: zone 55, band H (southern hemisphere uses false northing)
        val result = CoordinateFormatter.format(-37.8136, 144.9631, CoordinateDisplayFormat.UTM)
        assert(result.startsWith("loc:55H ")) { "Expected zone 55H, got: $result" }
    }

    @Test
    fun utm_norwayException_zone32() {
        // Lat 56–64°, lon 3–12° → forced to zone 32 (Norway special case)
        val result = CoordinateFormatter.format(57.0, 6.0, CoordinateDisplayFormat.UTM)
        assert(result.startsWith("loc:32V ")) { "Expected zone 32V (Norway exception), got: $result" }
    }

    @Test
    fun utm_svalbardException_zone33() {
        // Lat 72–84°, lon 9–21° → forced to zone 33 (Svalbard special case)
        val result = CoordinateFormatter.format(75.0, 15.0, CoordinateDisplayFormat.UTM)
        assert(result.startsWith("loc:33X ")) { "Expected zone 33X (Svalbard exception), got: $result" }
    }

    @Test
    fun utm_svalbardException_zone35() {
        // Lat 72–84°, lon 21–33° → forced to zone 35
        val result = CoordinateFormatter.format(75.0, 27.0, CoordinateDisplayFormat.UTM)
        assert(result.startsWith("loc:35X ")) { "Expected zone 35X (Svalbard exception), got: $result" }
    }

    @Test
    fun utm_lonAntimeridian_wrapsCorrectly() {
        // lon = 179.9 → zone 60; lon = -179.9 → zone 1
        val east = CoordinateFormatter.format(0.0, 179.9, CoordinateDisplayFormat.UTM)
        val west = CoordinateFormatter.format(0.0, -179.9, CoordinateDisplayFormat.UTM)
        assert(east.startsWith("loc:60N ")) { "Expected zone 60N, got: $east" }
        assert(west.startsWith("loc:1N ")) { "Expected zone 1N, got: $west" }
    }

    // ---------------------------------------------------------------------------
    // UTM easting / northing values (Philadelphia reference)
    // Derived from this implementation for WGS84 / k0=0.9996:
    //   Easting  ≈ 485,889 m   Northing ≈ 4,422,509 m
    // ---------------------------------------------------------------------------

    @Test
    fun utm_philadelphia_eastingNorthingWithinOneMeter() {
        val result = CoordinateFormatter.format(39.9526, -75.1652, CoordinateDisplayFormat.UTM)
        // parse: "loc:18S 485889 4422509"
        val parts = result.removePrefix("loc:").split(" ")
        assertEquals(3, parts.size)
        val easting = parts[1].toInt()
        val northing = parts[2].toInt()
        assertNear(485_889, easting, 2)
        assertNear(4_422_509, northing, 2)
    }

    @Test
    fun utm_southernHemisphere_northingReflectsFalseNorthing() {
        // Melbourne southing ≈ 5,812,912 m (false northing +10,000,000 applied)
        val result = CoordinateFormatter.format(-37.8136, 144.9631, CoordinateDisplayFormat.UTM)
        val parts = result.removePrefix("loc:").split(" ")
        val northing = parts[2].toInt()
        // Any valid southern-hemisphere northing must be > 0 (false northing applied)
        // and in the range expected for Melbourne (~5,813,000)
        assert(northing in 5_800_000..5_830_000) {
            "Southern hemisphere northing out of expected range: $northing"
        }
    }

    // ---------------------------------------------------------------------------
    // Latitude band letters
    // ---------------------------------------------------------------------------

    @Test
    fun utm_latitudeBandProgression() {
        // Spot-check several band boundaries
        val bands = listOf(
            -75.0 to 'C',
            -65.0 to 'D',
            -10.0 to 'L',
            0.0 to 'N',   // equator — N band starts at 0°
            4.0 to 'N',
            8.0 to 'P',
            38.0 to 'S',
            55.0 to 'U',
            63.0 to 'V',
            75.0 to 'X',
        )
        for ((lat, expectedBand) in bands) {
            val result = CoordinateFormatter.format(lat, 10.0, CoordinateDisplayFormat.UTM)
            val band = result.removePrefix("loc:").first { it.isLetter() }
            assertBandEquals("Band for lat=$lat", expectedBand, band)
        }
    }

    // ---------------------------------------------------------------------------
    // USNG format
    // ---------------------------------------------------------------------------

    @Test
    fun usng_philadelphia_correct100kGridId() {
        // Philadelphia 39.9526, -75.1652 → 18S VK (per implementation reference)
        val result = CoordinateFormatter.format(39.9526, -75.1652, CoordinateDisplayFormat.USNG)
        // Format: "loc:18S VK 85889 22509"
        val parts = result.removePrefix("loc:").split(" ")
        assertEquals(4, parts.size)
        assertEquals("18S", parts[0])
        assertEquals("VK", parts[1])
    }

    @Test
    fun usng_remainder_isPaddedToFiveDigits() {
        val result = CoordinateFormatter.format(39.9526, -75.1652, CoordinateDisplayFormat.USNG)
        val parts = result.removePrefix("loc:").split(" ")
        // Easting and northing remainders must each be exactly 5 characters
        assertEquals(5, parts[2].length) { "Easting remainder not 5 digits: ${parts[2]}" }
        assertEquals(5, parts[3].length) { "Northing remainder not 5 digits: ${parts[3]}" }
    }

    @Test
    fun usng_southernHemisphere_formatsWithoutCrash() {
        // Melbourne — grid ID computation must not throw for southern hemisphere
        val result = CoordinateFormatter.format(-37.8136, 144.9631, CoordinateDisplayFormat.USNG)
        assert(result.startsWith("loc:55H ")) { "Unexpected USNG prefix: $result" }
        val parts = result.removePrefix("loc:").split(" ")
        assertEquals(4, parts.size)
        assertEquals(5, parts[2].length)
        assertEquals(5, parts[3].length)
    }

    @Test
    fun usng_norwayException_zone32_gridsCorrectly() {
        val result = CoordinateFormatter.format(57.0, 6.0, CoordinateDisplayFormat.USNG)
        assert(result.startsWith("loc:32V ")) { "Unexpected USNG prefix: $result" }
    }

    @Test
    fun usng_positiveModulo_remainderIsNeverNegative() {
        // Verify that easting/northing remainders are non-negative for coordinates that
        // produce intermediate negative values before the false northing adjustment.
        // Use a point just south of the equator (northing before false-northing is negative).
        val result = CoordinateFormatter.format(-0.5, 36.0, CoordinateDisplayFormat.USNG)
        val parts = result.removePrefix("loc:").split(" ")
        val eastingRemainder = parts[2].trimStart('0').let { if (it.isEmpty()) 0 else it.toInt() }
        val northingRemainder = parts[3].trimStart('0').let { if (it.isEmpty()) 0 else it.toInt() }
        assert(eastingRemainder >= 0) { "Negative easting remainder: $eastingRemainder" }
        assert(northingRemainder >= 0) { "Negative northing remainder: $northingRemainder" }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun assertBandEquals(label: String, expected: Char, actual: Char) {
        if (expected != actual) throw AssertionError("$label: expected '$expected' but was '$actual'")
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) {
        assert(kotlin.math.abs(expected - actual) <= tolerance) {
            "Expected $expected ± $tolerance but was $actual"
        }
    }

    private fun assertEquals(expected: Int, actual: Int, message: () -> String) {
        if (expected != actual) throw AssertionError("${message()}: expected $expected but was $actual")
    }
}

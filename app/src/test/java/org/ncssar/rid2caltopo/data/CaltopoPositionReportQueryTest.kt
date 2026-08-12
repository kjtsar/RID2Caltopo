package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaltopoPositionReportQueryTest {
    @Test
    fun positionReportsUseGet() {
        assertEquals(CaltopoSession.CtsMethod_t.GET, CaltopoSession.positionReportMethod())
    }

    @Test
    fun positionQueryContainsColonQualifiedAircraftAndCameraParameters() {
        val parameters = CaltopoSession.buildPositionQueryParameters(
            CtDroneSpec.PositionTelemetry(140.0, 19.4384449, 92.0),
            CaltopoCameraMetadata(
                "https://r2c-tracker.com/t/Bz2DZg",
                "https://r2c-tracker.com/ncssar/api/v1/video/thumbnail/session-1",
            ),
        )

        assertEquals(140.0, parameters.getDouble("aircraft:altitude_rate"), 0.0)
        assertEquals(19.4384449, parameters.getDouble("aircraft:gs"), 0.0)
        assertEquals(92.0, parameters.getDouble("aircraft:track"), 0.0)
        assertEquals(
            "https://r2c-tracker.com/t/Bz2DZg",
            parameters.getString("camera:external_url"),
        )
        assertTrue(parameters.has("camera:thumbnail_url"))
        assertFalse(parameters.has("aircraft"))
        assertFalse(parameters.has("camera"))
    }

    @Test
    fun positionQueryOmitsEmptyAndNonFiniteValues() {
        val parameters = CaltopoSession.buildPositionQueryParameters(
            CtDroneSpec.PositionTelemetry(Double.NaN, null, Double.POSITIVE_INFINITY),
            CaltopoCameraMetadata("", null),
        )

        assertFalse(parameters.has("aircraft:altitude_rate"))
        assertFalse(parameters.has("aircraft:gs"))
        assertFalse(parameters.has("aircraft:track"))
        assertFalse(parameters.has("camera:external_url"))
        assertFalse(parameters.has("camera:thumbnail_url"))
    }
}

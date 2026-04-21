package org.ncssar.rid2caltopo.notam

import android.location.Location

internal data class OperatingAltitudeBand(
    val floorFeetMsl: Double,
    val ceilingFeetMsl: Double
)

internal object NotamOperatingAltitudeResolver {
    private const val FEET_PER_METER = 3.28084
    private const val MAX_OPERATION_ALTITUDE_FT_AGL = 400.0

    fun resolve(location: Location, demElevationMeters: Double? = null): OperatingAltitudeBand? {
        val floorFeetMsl = when {
            location.hasAltitude() -> location.altitude * FEET_PER_METER
            demElevationMeters != null -> demElevationMeters * FEET_PER_METER
            else -> return null
        }
        return OperatingAltitudeBand(
            floorFeetMsl = floorFeetMsl,
            ceilingFeetMsl = floorFeetMsl + MAX_OPERATION_ALTITUDE_FT_AGL
        )
    }
}

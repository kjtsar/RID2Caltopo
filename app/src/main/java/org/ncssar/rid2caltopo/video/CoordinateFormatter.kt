package org.ncssar.rid2caltopo.video

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

enum class CoordinateDisplayFormat(val storageValue: String, val label: String) {
    DECIMAL("decimal", "Decimal"),
    UTM("utm", "UTM"),
    USNG("usng", "USNG");

    companion object {
        fun fromStorage(value: String?): CoordinateDisplayFormat {
            return values().firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: DECIMAL
        }
    }
}

internal const val STREAM_COORDINATE_DISPLAY_FORMAT_KEY = "coordinate_display_format"

internal fun restoredCoordinateDisplayFormat(
    streamPreference: String?,
    legacyConfigPreference: String?,
): CoordinateDisplayFormat {
    val stored = streamPreference?.trim()?.takeIf { it.isNotEmpty() }
        ?: legacyConfigPreference
    return CoordinateDisplayFormat.fromStorage(stored)
}

internal object CoordinateFormatter {
    fun format(lat: Double, lng: Double, format: CoordinateDisplayFormat): String {
        if (!lat.isFinite() || !lng.isFinite()) return "loc:unknown"
        return when (format) {
            CoordinateDisplayFormat.DECIMAL ->
                String.format(Locale.US, "loc:%.5f,%.5f", lat, lng)
            CoordinateDisplayFormat.UTM -> formatUtm(lat, lng)
            CoordinateDisplayFormat.USNG -> formatUsng(lat, lng)
        }
    }

    private fun formatUtm(lat: Double, lng: Double): String {
        val utm = toUtm(lat, lng)
        return String.format(
            Locale.US,
            "loc:%d%s %06d %07d",
            utm.zoneNumber,
            utm.zoneLetter,
            utm.easting.roundToInt(),
            utm.northing.roundToInt()
        )
    }

    private fun formatUsng(lat: Double, lng: Double): String {
        val utm = toUtm(lat, lng)
        val grid = usng100kId(utm.zoneNumber, utm.easting, utm.northing)
        val eastingRemainder = positiveModulo(utm.easting.roundToInt(), 100_000)
        val northingRemainder = positiveModulo(utm.northing.roundToInt(), 100_000)
        return String.format(
            Locale.US,
            "loc:%d%s %s %05d %05d",
            utm.zoneNumber,
            utm.zoneLetter,
            grid,
            eastingRemainder,
            northingRemainder
        )
    }

    private fun toUtm(lat: Double, lng: Double): UtmCoordinate {
        val a = 6378137.0
        val eccSquared = 0.00669438
        val k0 = 0.9996

        val latClamped = lat.coerceIn(-80.0, 84.0)
        val lonTemp = ((lng + 180.0) - floor((lng + 180.0) / 360.0) * 360.0) - 180.0
        val latRad = Math.toRadians(latClamped)
        val lonRad = Math.toRadians(lonTemp)

        var zoneNumber = ((lonTemp + 180.0) / 6.0).toInt() + 1
        if (latClamped in 56.0..<64.0 && lonTemp in 3.0..<12.0) zoneNumber = 32
        if (latClamped in 72.0..<84.0) {
            zoneNumber = when {
                lonTemp in 0.0..<9.0 -> 31
                lonTemp in 9.0..<21.0 -> 33
                lonTemp in 21.0..<33.0 -> 35
                lonTemp in 33.0..<42.0 -> 37
                else -> zoneNumber
            }
        }

        val lonOrigin = (zoneNumber - 1) * 6 - 180 + 3
        val lonOriginRad = Math.toRadians(lonOrigin.toDouble())
        val eccPrimeSquared = eccSquared / (1.0 - eccSquared)

        val n = a / kotlin.math.sqrt(1 - eccSquared * sin(latRad).pow(2.0))
        val t = tan(latRad).pow(2.0)
        val c = eccPrimeSquared * cos(latRad).pow(2.0)
        val aTerm = cos(latRad) * (lonRad - lonOriginRad)

        val m = a * (
            (1
                - eccSquared / 4
                - 3 * eccSquared.pow(2.0) / 64
                - 5 * eccSquared.pow(3.0) / 256) * latRad
                - (3 * eccSquared / 8
                + 3 * eccSquared.pow(2.0) / 32
                + 45 * eccSquared.pow(3.0) / 1024) * sin(2 * latRad)
                + (15 * eccSquared.pow(2.0) / 256
                + 45 * eccSquared.pow(3.0) / 1024) * sin(4 * latRad)
                - (35 * eccSquared.pow(3.0) / 3072) * sin(6 * latRad)
            )

        var easting = k0 * n * (
            aTerm
                + (1 - t + c) * aTerm.pow(3.0) / 6.0
                + (5 - 18 * t + t * t + 72 * c - 58 * eccPrimeSquared) * aTerm.pow(5.0) / 120.0
            ) + 500000.0

        var northing = k0 * (
            m + n * tan(latRad) * (
                aTerm.pow(2.0) / 2.0
                    + (5 - t + 9 * c + 4 * c * c) * aTerm.pow(4.0) / 24.0
                    + (61 - 58 * t + t * t + 600 * c - 330 * eccPrimeSquared) * aTerm.pow(6.0) / 720.0
                )
            )

        if (latClamped < 0) northing += 10_000_000.0
        if (easting < 0.0) easting = 0.0
        if (northing < 0.0) northing = 0.0

        return UtmCoordinate(
            zoneNumber = zoneNumber,
            zoneLetter = latitudeBand(latClamped),
            easting = easting,
            northing = northing
        )
    }

    private fun latitudeBand(lat: Double): Char {
        return when {
            lat < -72 -> 'C'
            lat < -64 -> 'D'
            lat < -56 -> 'E'
            lat < -48 -> 'F'
            lat < -40 -> 'G'
            lat < -32 -> 'H'
            lat < -24 -> 'J'
            lat < -16 -> 'K'
            lat < -8 -> 'L'
            lat < 0 -> 'M'
            lat < 8 -> 'N'
            lat < 16 -> 'P'
            lat < 24 -> 'Q'
            lat < 32 -> 'R'
            lat < 40 -> 'S'
            lat < 48 -> 'T'
            lat < 56 -> 'U'
            lat < 64 -> 'V'
            lat < 72 -> 'W'
            else -> 'X'
        }
    }

    private fun usng100kId(zoneNumber: Int, easting: Double, northing: Double): String {
        val columnSets = arrayOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
        val rowSets = arrayOf("ABCDEFGHJKLMNPQRSTUV", "FGHJKLMNPQRSTUVABCDE")
        val setColumn = columnSets[(zoneNumber - 1) % columnSets.size]
        val setRow = rowSets[(zoneNumber - 1) % rowSets.size]

        var columnIndex = floor(easting / 100000.0).toInt()
        columnIndex = (columnIndex - 1).coerceAtLeast(0) % setColumn.length
        val rowIndex = floor(northing / 100000.0).toInt() % setRow.length
        return "${setColumn[columnIndex]}${setRow[rowIndex]}"
    }

    private fun positiveModulo(value: Int, modulus: Int): Int {
        val mod = value % modulus
        return if (mod < 0) mod + modulus else mod
    }

    private data class UtmCoordinate(
        val zoneNumber: Int,
        val zoneLetter: Char,
        val easting: Double,
        val northing: Double
    )
}

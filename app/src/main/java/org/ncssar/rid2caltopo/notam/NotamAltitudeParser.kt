package org.ncssar.rid2caltopo.notam

import java.util.Locale

internal object NotamAltitudeParser {
    private val commonReferenceRangeRegex = Regex(
        """\b(SFC|FL\d{2,3}|[0-9]{2,5}FT)\s*-\s*(FL\d{2,3}|[0-9]{2,5}FT)\s+(AGL|MSL)\b""",
        RegexOption.IGNORE_CASE
    )
    private val mixedReferenceRangeRegex = Regex(
        """\b(SFC|FL\d{2,3}|[0-9]{2,5}FT)(?:\s+(AGL|MSL))?\s*-\s*(FL\d{2,3}|[0-9]{2,5}FT)(?:\s+(AGL|MSL))?\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(sourceText: String): NotamAltitudeBand? {
        if (sourceText.isBlank()) return null
        val normalized = sourceText.uppercase(Locale.US)

        commonReferenceRangeRegex.find(normalized)?.let { match ->
            val floorRaw = match.groupValues[1]
            val ceilingRaw = match.groupValues[2]
            val reference = match.groupValues[3]
            return NotamAltitudeBand(
                floorFeetMsl = altitudeTokenToFeetMsl(floorRaw, reference),
                ceilingFeetMsl = altitudeTokenToFeetMsl(ceilingRaw, reference),
                floorLabel = floorRaw,
                ceilingLabel = ceilingRaw,
                reference = reference
            )
        }

        mixedReferenceRangeRegex.find(normalized)?.let { match ->
            val floorRaw = match.groupValues[1]
            val floorReference = match.groupValues[2].ifBlank { null }
            val ceilingRaw = match.groupValues[3]
            val ceilingReference = match.groupValues[4].ifBlank { null }
            return NotamAltitudeBand(
                floorFeetMsl = altitudeTokenToFeetMsl(floorRaw, floorReference),
                ceilingFeetMsl = altitudeTokenToFeetMsl(ceilingRaw, ceilingReference),
                floorLabel = formatLabel(floorRaw, floorReference),
                ceilingLabel = formatLabel(ceilingRaw, ceilingReference),
                reference = floorReference?.takeIf { it == ceilingReference }
            )
        }

        return null
    }

    private fun formatLabel(token: String, reference: String?): String =
        if (reference.isNullOrBlank()) token else "$token $reference"

    private fun altitudeTokenToFeetMsl(token: String, reference: String?): Double? {
        val normalized = token.uppercase(Locale.US)
        return when {
            normalized == "SFC" -> 0.0
            normalized.startsWith("FL") -> normalized.removePrefix("FL").toDoubleOrNull()?.times(100.0)
            normalized.endsWith("FT") && reference.equals("MSL", ignoreCase = true) ->
                normalized.removeSuffix("FT").toDoubleOrNull()
            normalized.endsWith("FT") && reference.equals("AGL", ignoreCase = true) -> null
            normalized.endsWith("FT") && reference.isNullOrBlank() ->
                normalized.removeSuffix("FT").toDoubleOrNull()
            else -> null
        }
    }
}

package org.ncssar.rid2caltopo.airspace

import org.json.JSONObject

object FaaUasFacilityMapParser {
    fun parse(body: String): List<FaaUasFacilityMapRecord> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        return buildList {
            for (i in 0 until features.length()) {
                val attributes = features.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                add(attributes.toFacilityMapRecord())
            }
        }
    }

    private fun JSONObject.toFacilityMapRecord(): FaaUasFacilityMapRecord {
        return FaaUasFacilityMapRecord(
            objectId = optLong("OBJECTID", -1L),
            ceilingFeet = if (has("CEILING") && !isNull("CEILING")) optInt("CEILING") else null,
            unit = optString("UNIT"),
            primaryAirportFaaId = optString("APT1_FAAID"),
            primaryAirportIcao = optString("APT1_ICAO"),
            primaryAirportName = optString("APT1_NAME"),
            laancAvailable = optInt("APT1_LAANC", 0) == 1,
            airspaceClasses = (1..5).mapNotNull { index ->
                optString("AIRSPACE_$index").trim().ifBlank { null }
            }
        )
    }
}

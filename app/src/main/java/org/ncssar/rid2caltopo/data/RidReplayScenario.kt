package org.ncssar.rid2caltopo.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * File-backed RID replay scenario.
 *
 * Example:
 * {
 *   "type": "rid_replay_scenario",
 *   "file_version": "1.0",
 *   "scenario_name": "Two drones over staging",
 *   "time_mode": "relative_ms",
 *   "speed_multiplier": 4.0,
 *   "drones": [
 *     {
 *       "remote_id": "RID-ALPHA",
 *       "mapped_id": "1SAR7DjMn4Pr",
 *       "org": "NCSSAR",
 *       "model": "DJI Mini 4 Pro",
 *       "owner": "Ken Taylor",
 *       "transport": "WIFI",
 *       "airborne": true,
 *       "points": [
 *         { "t_ms": 0, "lat": 35.771, "lng": 140.392, "alt_m": 25.0 },
 *         { "t_ms": 1000, "lat": 35.772, "lng": 140.393, "alt_m": 28.0, "gs_knots": 18.0 }
 *       ]
 *     }
 *   ]
 * }
 */
data class RidReplayScenario(
    val scenarioName: String,
    val timeMode: TimeMode,
    val speedMultiplier: Double,
    val drones: List<DroneTrack>
) {
    enum class TimeMode {
        RELATIVE_MS,
        ABSOLUTE_EPOCH_MS
    }

    data class DroneTrack(
        val remoteId: String,
        val mappedId: String,
        val org: String,
        val model: String,
        val owner: String,
        val transport: CtDroneSpec.TransportTypeEnum,
        val airborne: Boolean?,
        val points: List<Point>
    )

    data class Point(
        val tMs: Long,
        val lat: Double,
        val lng: Double,
        val altM: Double,
        val gsKnots: Double?,
        val trackDeg: Double?,
        val altitudeRateFpm: Double?,
        val airborne: Boolean?
    )
}

object RidReplayScenarioParser {
    private const val EXPECTED_TYPE = "rid_replay_scenario"

    @JvmStatic
    fun parse(jsonText: String): RidReplayScenario {
        val root = JsonParser.parseString(jsonText).asJsonObject
        val type = root.optTrimmedString("type")
        require(type == EXPECTED_TYPE) { "Unexpected replay file type '$type'." }

        val timeMode = when (root.optTrimmedString("time_mode", "relative_ms").lowercase()) {
            "relative_ms" -> RidReplayScenario.TimeMode.RELATIVE_MS
            "absolute_epoch_ms" -> RidReplayScenario.TimeMode.ABSOLUTE_EPOCH_MS
            else -> throw IllegalArgumentException("Unsupported time_mode in RID replay file.")
        }
        val speedMultiplier = root.optDouble("speed_multiplier", 1.0).let {
            if (it.isFinite() && it > 0.0) it else 1.0
        }
        val dronesJson = root.optJsonArray("drones") ?: JsonArray()
        val drones = ArrayList<RidReplayScenario.DroneTrack>(dronesJson.length())
        for (i in 0 until dronesJson.length()) {
            val droneJson = dronesJson.optJsonObject(i) ?: continue
            val remoteId = droneJson.optTrimmedString("remote_id")
            require(remoteId.isNotEmpty()) { "RID replay file contains a drone with no remote_id." }
            val transport = parseTransport(droneJson.optTrimmedString("transport", "WIFI"))
            val pointsJson = droneJson.optJsonArray("points") ?: JsonArray()
            val points = ArrayList<RidReplayScenario.Point>(pointsJson.length())
            for (j in 0 until pointsJson.length()) {
                val pointJson = pointsJson.optJsonObject(j) ?: continue
                points += RidReplayScenario.Point(
                    tMs = pointJson.optLong("t_ms"),
                    lat = pointJson.requiredDouble("lat"),
                    lng = pointJson.requiredDouble("lng"),
                    altM = pointJson.optDouble("alt_m"),
                    gsKnots = pointJson.optDoubleOrNull("gs_knots"),
                    trackDeg = pointJson.optDoubleOrNull("track_deg"),
                    altitudeRateFpm = pointJson.optDoubleOrNull("altitude_rate_fpm"),
                    airborne = pointJson.optNullableBoolean("airborne")
                )
            }
            require(points.isNotEmpty()) { "RID replay drone '$remoteId' has no points." }
            drones += RidReplayScenario.DroneTrack(
                remoteId = remoteId,
                mappedId = droneJson.optTrimmedString("mapped_id"),
                org = droneJson.optTrimmedString("org"),
                model = droneJson.optTrimmedString("model"),
                owner = droneJson.optTrimmedString("owner"),
                transport = transport,
                airborne = droneJson.optNullableBoolean("airborne"),
                points = points.sortedBy { it.tMs }
            )
        }
        require(drones.isNotEmpty()) { "RID replay file contained no drone tracks." }
        return RidReplayScenario(
            scenarioName = root.optTrimmedString("scenario_name").ifBlank { "RID Replay" },
            timeMode = timeMode,
            speedMultiplier = speedMultiplier,
            drones = drones
        )
    }

    private fun parseTransport(raw: String): CtDroneSpec.TransportTypeEnum =
        runCatching { CtDroneSpec.TransportTypeEnum.valueOf(raw.trim().uppercase()) }
            .getOrDefault(CtDroneSpec.TransportTypeEnum.WIFI)

    private fun JsonObject.optTrimmedString(name: String, defaultValue: String = ""): String {
        val element = get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        return element.asString.trim().ifBlank { defaultValue }
    }

    private fun JsonObject.optDouble(name: String, defaultValue: Double = 0.0): Double {
        val element = get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        return runCatching { element.asDouble }.getOrDefault(defaultValue)
    }

    private fun JsonObject.requiredDouble(name: String): Double {
        return get(name)?.asDouble
            ?: throw IllegalArgumentException("RID replay point missing required '$name'.")
    }

    private fun JsonObject.optLong(name: String, defaultValue: Long = 0L): Long {
        val element = get(name) ?: return defaultValue
        if (element.isJsonNull) return defaultValue
        return runCatching { element.asLong }.getOrDefault(defaultValue)
    }

    private fun JsonObject.optDoubleOrNull(name: String): Double? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        val value = runCatching { element.asDouble }.getOrNull() ?: return null
        return if (value.isFinite()) value else null
    }

    private fun JsonObject.optNullableBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (element.isJsonNull) return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    private fun JsonObject.optJsonArray(name: String): JsonArray? {
        val element = get(name) ?: return null
        if (element.isJsonNull || !element.isJsonArray) return null
        return element.asJsonArray
    }

    private fun JsonArray.optJsonObject(index: Int): JsonObject? {
        if (index < 0 || index >= size()) return null
        val element = get(index)
        if (element == null || element.isJsonNull || !element.isJsonObject) return null
        return element.asJsonObject
    }

    private fun JsonArray.length(): Int = size()

    @Suppress("unused")
    private fun JsonElement?.asSafeString(): String = when {
        this == null || isJsonNull -> ""
        else -> runCatching { asString }.getOrDefault(toString())
    }
}

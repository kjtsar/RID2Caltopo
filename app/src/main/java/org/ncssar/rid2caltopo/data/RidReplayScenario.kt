package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject

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
        val root = JSONObject(jsonText)
        val type = root.optString("type")
        require(type == EXPECTED_TYPE) { "Unexpected replay file type '$type'." }

        val timeMode = when (root.optString("time_mode", "relative_ms").lowercase()) {
            "relative_ms" -> RidReplayScenario.TimeMode.RELATIVE_MS
            "absolute_epoch_ms" -> RidReplayScenario.TimeMode.ABSOLUTE_EPOCH_MS
            else -> throw IllegalArgumentException("Unsupported time_mode in RID replay file.")
        }
        val speedMultiplier = root.optDouble("speed_multiplier", 1.0).let {
            if (it.isFinite() && it > 0.0) it else 1.0
        }
        val dronesJson = root.optJSONArray("drones") ?: JSONArray()
        val drones = ArrayList<RidReplayScenario.DroneTrack>(dronesJson.length())
        for (i in 0 until dronesJson.length()) {
            val droneJson = dronesJson.optJSONObject(i) ?: continue
            val remoteId = droneJson.optString("remote_id").trim()
            require(remoteId.isNotEmpty()) { "RID replay file contains a drone with no remote_id." }
            val transport = parseTransport(droneJson.optString("transport", "WIFI"))
            val pointsJson = droneJson.optJSONArray("points") ?: JSONArray()
            val points = ArrayList<RidReplayScenario.Point>(pointsJson.length())
            for (j in 0 until pointsJson.length()) {
                val pointJson = pointsJson.optJSONObject(j) ?: continue
                points += RidReplayScenario.Point(
                    tMs = pointJson.optLong("t_ms"),
                    lat = pointJson.getDouble("lat"),
                    lng = pointJson.getDouble("lng"),
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
                mappedId = droneJson.optString("mapped_id").trim(),
                org = droneJson.optString("org").trim(),
                model = droneJson.optString("model").trim(),
                owner = droneJson.optString("owner").trim(),
                transport = transport,
                airborne = droneJson.optNullableBoolean("airborne"),
                points = points.sortedBy { it.tMs }
            )
        }
        require(drones.isNotEmpty()) { "RID replay file contained no drone tracks." }
        return RidReplayScenario(
            scenarioName = root.optString("scenario_name").ifBlank { "RID Replay" },
            timeMode = timeMode,
            speedMultiplier = speedMultiplier,
            drones = drones
        )
    }

    private fun parseTransport(raw: String): CtDroneSpec.TransportTypeEnum =
        runCatching { CtDroneSpec.TransportTypeEnum.valueOf(raw.trim().uppercase()) }
            .getOrDefault(CtDroneSpec.TransportTypeEnum.WIFI)

    private fun JSONObject.optDoubleOrNull(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = optDouble(name)
        return if (value.isFinite()) value else null
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        if (!has(name) || isNull(name)) return null
        return optBoolean(name)
    }
}

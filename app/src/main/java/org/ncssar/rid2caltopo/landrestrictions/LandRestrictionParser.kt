package org.ncssar.rid2caltopo.landrestrictions

import kotlin.math.cos
import kotlin.math.hypot
import org.json.JSONArray
import org.json.JSONObject

object LandRestrictionParser {
    fun parse(
        payload: String,
        source: LandRestrictionSource,
        center: LandCoordinate,
        operatingRadiusNm: Double
    ): List<LandRestrictionArea> {
        val root = JSONObject(payload)
        root.optJSONObject("error")?.let { error ->
            throw IllegalArgumentException(error.optString("message", "Boundary service returned an error"))
        }
        val features = root.optJSONArray("features") ?: JSONArray()
        return buildList {
            for (index in 0 until features.length()) {
                val feature = features.optJSONObject(index) ?: continue
                val polygons = parsePolygons(feature.optJSONObject("geometry") ?: continue)
                if (polygons.isEmpty()) continue
                val properties = feature.optJSONObject("properties") ?: JSONObject()
                val name = firstString(properties, source.nameFields) ?: source.agency.displayName
                val identifier = firstString(properties, source.identifierFields) ?: name
                val contains = polygons.any { polygon ->
                    polygon.firstOrNull()?.let { pointInPolygon(center, it) } == true
                }
                val distance = if (contains) 0.0 else minimumBoundaryDistanceNm(center, polygons)
                add(
                    LandRestrictionArea(
                        id = "${source.id}:$identifier",
                        name = name,
                        agency = source.agency,
                        rule = source.rule,
                        polygons = polygons,
                        intersectsOperatingArea = contains || distance <= operatingRadiusNm,
                        containsOperator = contains,
                        distanceNm = distance,
                        detailsUrl = firstString(properties, source.detailsUrlFields)?.takeIf { it.startsWith("http") }
                    )
                )
            }
        }.sortedWith(
            compareByDescending<LandRestrictionArea> { it.containsOperator }
                .thenByDescending { it.intersectsOperatingArea }
                .thenBy { it.distanceNm }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }

    private fun parsePolygons(geometry: JSONObject): List<List<List<LandCoordinate>>> {
        val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> parseRings(coordinates)?.let(::listOf).orEmpty()
            "MultiPolygon" -> buildList {
                for (index in 0 until coordinates.length()) {
                    parseRings(coordinates.optJSONArray(index) ?: continue)?.let(::add)
                }
            }
            else -> emptyList()
        }
    }

    private fun parseRings(value: JSONArray): List<List<LandCoordinate>>? {
        val rings = buildList {
            for (ringIndex in 0 until value.length()) {
                val rawRing = value.optJSONArray(ringIndex) ?: continue
                val ring = buildList {
                    for (pointIndex in 0 until rawRing.length()) {
                        val rawPoint = rawRing.optJSONArray(pointIndex) ?: continue
                        if (rawPoint.length() < 2) continue
                        val longitude = rawPoint.optDouble(0, Double.NaN)
                        val latitude = rawPoint.optDouble(1, Double.NaN)
                        if (latitude.isFinite() && longitude.isFinite()) {
                            add(LandCoordinate(latitude, longitude))
                        }
                    }
                }
                if (ring.size >= 3) add(ring)
            }
        }
        return rings.takeIf { it.isNotEmpty() }
    }

    private fun firstString(properties: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            if (!properties.isNull(key)) {
                properties.optString(key).trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    private fun pointInPolygon(point: LandCoordinate, ring: List<LandCoordinate>): Boolean {
        if (ring.size < 3) return false
        var inside = false
        var previous = ring.lastIndex
        for (index in ring.indices) {
            val left = ring[index]
            val right = ring[previous]
            val denominator = (right.latitude - left.latitude).takeUnless { it == 0.0 } ?: Double.MIN_VALUE
            val crosses = (left.latitude > point.latitude) != (right.latitude > point.latitude) &&
                point.longitude < (right.longitude - left.longitude) *
                (point.latitude - left.latitude) / denominator + left.longitude
            if (crosses) inside = !inside
            previous = index
        }
        return inside
    }

    private fun minimumBoundaryDistanceNm(
        center: LandCoordinate,
        polygons: List<List<List<LandCoordinate>>>
    ): Double {
        val latitudeScale = 60.0405
        val longitudeScale = latitudeScale * cos(Math.toRadians(center.latitude))
        var closest = Double.POSITIVE_INFINITY
        polygons.flatten().filter { it.isNotEmpty() }.forEach { ring ->
            ring.indices.forEach { index ->
                val left = ring[index]
                val right = ring[(index + 1) % ring.size]
                val leftX = (left.longitude - center.longitude) * longitudeScale
                val leftY = (left.latitude - center.latitude) * latitudeScale
                val rightX = (right.longitude - center.longitude) * longitudeScale
                val rightY = (right.latitude - center.latitude) * latitudeScale
                val dx = rightX - leftX
                val dy = rightY - leftY
                val denominator = dx * dx + dy * dy
                val fraction = if (denominator == 0.0) 0.0
                else (-(leftX * dx + leftY * dy) / denominator).coerceIn(0.0, 1.0)
                closest = minOf(closest, hypot(leftX + fraction * dx, leftY + fraction * dy))
            }
        }
        return closest
    }
}

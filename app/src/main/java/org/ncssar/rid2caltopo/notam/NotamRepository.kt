package org.ncssar.rid2caltopo.notam

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebug
import org.ncssar.rid2caltopo.data.CaltopoClient.CTDebugEnabled
import org.ncssar.rid2caltopo.data.CaltopoMap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class NotamRepository {
    private data class GeometryProximity(
        val distanceNm: Double,
        val bearingDeg: Double?
    )

    private companion object {
        const val TAG = "NotamREST"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val _uiState = MutableStateFlow(buildUiState(null, configured = false, loading = false, notices = emptyList()))
    val uiState: StateFlow<NotamUiState> = _uiState.asStateFlow()

    private var lastRefreshLocation: Location? = null
    private var lastRefreshAtMs: Long = 0L
    private var lastFetchedNotices: List<NearbyNotam> = emptyList()
    private var lastErrorMessage: String? = null

    suspend fun refresh(force: Boolean = false) {
        val enabled = CaltopoClient.GetNotamEnabled()
        val configured = NotamAuthManager.isConfigured()
        val location = CaltopoMap.GetMyLocation()
        if (CTDebugEnabled(TAG)) {
            CTDebug(
                TAG,
                "refresh(force=$force) enabled=$enabled configured=$configured radiusNm=${CaltopoClient.GetNotamRadiusNm()} hasLocation=${location != null} accuracyM=${location?.accuracy ?: Float.NaN}"
            )
        }
        if (!enabled) {
            _uiState.value = NotamUiState(visible = false, enabled = false, configured = configured)
            return
        }

        if (!force && !shouldRefresh(location)) {
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "Skipping refresh due to debounce/stability thresholds.")
            }
            val retainedNotices = if (lastFetchedNotices.isNotEmpty()) lastFetchedNotices else _uiState.value.notices
            _uiState.value = buildUiState(
                location = location,
                configured = configured,
                loading = false,
                notices = retainedNotices
            )
            return
        }

        val retainedNotices = if (lastFetchedNotices.isNotEmpty()) lastFetchedNotices else _uiState.value.notices
        _uiState.value = buildUiState(
            location = location,
            configured = configured,
            loading = true,
            notices = retainedNotices
        )
        val now = System.currentTimeMillis()
        val notices = if (configured) {
            try {
                val fetched = fetchNearbyNotams(location)
                lastFetchedNotices = fetched
                lastErrorMessage = null
                if (CTDebugEnabled(TAG)) {
                    CTDebug(TAG, "Fetched ${fetched.size} nearby NOTAMs")
                }
                fetched
            } catch (e: Exception) {
                lastErrorMessage = e.message ?: "NOTAM request failed"
                if (CTDebugEnabled(TAG)) {
                    CTDebug(TAG, "Fetch failed: ${lastErrorMessage}")
                }
                lastFetchedNotices
            }
        } else {
            listOf(
                NearbyNotam(
                    id = "pending_credentials",
                    title = "FAA NOTAM credentials pending",
                    summary = "NOTAM monitoring is enabled, but FAA onboarding credentials have not been loaded yet.",
                    distanceNm = null,
                    effectiveText = "Waiting for credentials",
                    details = "Load the shared ct_credentials file after FAA onboarding completes to activate live nearby NOTAM queries.",
                    rawText = "",
                    severity = NotamChipSeverity.Neutral
                )
            )
        }
        lastRefreshLocation = location?.let { Location(it) }
        lastRefreshAtMs = now
        if (configured && lastErrorMessage == null) {
            CaltopoClient.SetNotamLastUpdatedEpochMs(now)
        }
        _uiState.value = buildUiState(
            location = location,
            configured = configured,
            loading = false,
            notices = notices
        )
    }

    private fun shouldRefresh(location: Location?): Boolean {
        if (lastRefreshAtMs == 0L) return true
        val now = System.currentTimeMillis()
        if (lastErrorMessage != null) {
            return now - lastRefreshAtMs >= 15_000L
        }
        if (!CaltopoClient.GetNotamAutoRefresh()) return false
        val refreshIntervalMs = CaltopoClient.GetNotamRefreshIntervalSeconds().coerceAtLeast(30) * 1_000L
        if (now - lastRefreshAtMs >= refreshIntervalMs) return true
        val previous = lastRefreshLocation ?: return true
        val current = location ?: return false
        val distanceMeters = previous.distanceTo(current)
        val distanceNm = distanceMeters / 1852.0
        if (CTDebugEnabled(TAG)) {
            CTDebug(
                TAG,
                "Debounce check ageMs=${now - lastRefreshAtMs} movedNm=${"%.3f".format(Locale.US, distanceNm)} refreshIntervalMs=$refreshIntervalMs"
            )
        }
        return distanceNm >= 0.25
    }

    private fun fetchNearbyNotams(location: Location?): List<NearbyNotam> {
        val current = location ?: throw IllegalStateException("Waiting for GPS location")
        val url = NotamAuthManager.resolvedApiBaseUrl()
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("v1/notams")
            .addQueryParameter("latitude", "%.6f".format(Locale.US, current.latitude))
            .addQueryParameter("longitude", "%.6f".format(Locale.US, current.longitude))
            .addQueryParameter("radius", CaltopoClient.GetNotamRadiusNm().toString())
            .build()
        if (CTDebugEnabled(TAG)) {
            CTDebug(TAG, "GET $url responseFormat=GEOJSON")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${NotamAuthManager.getBearerToken()}")
            .header("nmsResponseFormat", "GEOJSON")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "NOTAM query response http=${response.code}")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("NOTAM query failed with HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: throw IllegalStateException("NOTAM response did not include data")
            val features = data.optJSONArray("geojson") ?: JSONArray()
            if (CTDebugEnabled(TAG)) {
                CTDebug(TAG, "Parsed geojson featureCount=${features.length()} status='${json.optString("status")}' message='${json.optString("message")}'")
            }
            val notices = mutableListOf<NearbyNotam>()
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                notices += featureToNearbyNotam(feature, current)
            }
            return NotamPolicy.sort(notices)
        }
    }

    private fun featureToNearbyNotam(feature: JSONObject, current: Location): NearbyNotam {
        val properties = feature.optJSONObject("properties")
        val coreData = properties?.optJSONObject("coreNOTAMData")
        val notam = coreData?.optJSONObject("notam")
        val translations = coreData?.optJSONArray("notamTranslation")
        val geometry = feature.optJSONObject("geometry")

        val title = buildString {
            val locationCode = notam?.optString("icaoLocation").orEmpty().ifBlank { notam?.optString("location").orEmpty() }
            val number = buildNotamNumber(notam)
            if (locationCode.isNotBlank()) append(locationCode)
            if (number.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(number)
            }
            val text = notam?.optString("text").orEmpty()
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(" - ")
                append(text)
            }
        }.ifBlank { "Nearby NOTAM" }

        val reference = buildString {
            val locationCode = notam?.optString("icaoLocation").orEmpty().ifBlank { notam?.optString("location").orEmpty() }
            val number = buildNotamNumber(notam)
            if (locationCode.isNotBlank()) append(locationCode)
            if (number.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(number)
            }
        }
        val notamText = notam?.optString("text").orEmpty()
        val rawText = extractRawText(translations)
        val geometries = geometry?.toNotamGeometries().orEmpty()
        val proximity = geometry?.let { distanceToGeometry(current, it) }
        val distanceNm = proximity?.distanceNm
        val intersectsPilotBubble = distanceNm != null && distanceNm <= 1.0
        val severity = inferSeverity(title, rawText, intersectsPilotBubble)
        val effectiveText = buildEffectiveText(notam)
        val proximityText = proximityText(distanceNm, proximity?.bearingDeg)
        val classification = notam?.optString("classification").orEmpty().ifBlank { "unknown" }
        val humanized = NotamHumanizer.humanize(
            reference = reference,
            notamText = notamText,
            rawText = rawText,
            effectiveText = effectiveText,
            proximityText = proximityText,
            intersectsPilotBubble = intersectsPilotBubble,
            classification = classification,
            scheduleText = notam?.optString("schedule").orEmpty()
        )
        return NearbyNotam(
            id = notam?.optString("id").orEmpty().ifBlank { title },
            title = humanized.title,
            summary = humanized.summary,
            distanceNm = distanceNm,
            bearingText = proximity?.bearingDeg?.let(::compassDirection),
            proximityText = proximityText,
            intersectsPilotBubble = intersectsPilotBubble,
            effectiveText = effectiveText,
            details = humanized.details,
            rawText = rawText,
            rawTitle = title,
            rawReference = reference,
            severity = severity,
            geometries = geometries
        ).also {
            if (CTDebugEnabled(TAG)) {
                val geometryType = geometry?.optString("type").orEmpty().ifBlank { "none" }
                CTDebug(
                    TAG,
                    "notice id='${it.id}' severity=${it.severity} distanceNm=${it.distanceNm?.let { d -> "%.2f".format(Locale.US, d) } ?: "n/a"} " +
                        "intersects=${it.intersectsPilotBubble} geometryType=$geometryType drawableGeometries=${it.geometries.size} " +
                        "title='${it.title.take(120)}'"
                )
            }
        }
    }

    private fun buildNotamNumber(notam: JSONObject?): String {
        if (notam == null) return ""
        val series = notam.optString("series")
        val number = notam.optString("number")
        val year = notam.optString("year")
        return listOf(series, number, year).filter { it.isNotBlank() }.joinToString("/")
    }

    private fun extractRawText(translations: JSONArray?): String {
        if (translations == null) return ""
        for (i in 0 until translations.length()) {
            val item = translations.optJSONObject(i) ?: continue
            val domestic = item.optString("domestic_message")
            if (domestic.isNotBlank()) return domestic
            val icao = item.optString("icao_message")
            if (icao.isNotBlank()) return icao.replace('\n', ' ')
        }
        return ""
    }

    private fun buildEffectiveText(notam: JSONObject?): String {
        if (notam == null) return ""
        val start = notam.optString("effectiveStart")
        val end = notam.optString("effectiveEnd")
        return when {
            start.isNotBlank() && end.isNotBlank() -> "Active $start to $end"
            start.isNotBlank() -> "Active from $start"
            end.isNotBlank() -> "Active until $end"
            else -> ""
        }
    }

    private fun inferSeverity(title: String, rawText: String, intersectsPilotBubble: Boolean): NotamChipSeverity {
        val haystack = "$title $rawText".uppercase(Locale.US)
        if (intersectsPilotBubble && (haystack.contains("TFR") || haystack.contains("RESTRICT") || haystack.contains("UAS") || haystack.contains("PROHIBITED"))) {
            return NotamChipSeverity.Danger
        }
        if (haystack.contains("TFR") || haystack.contains("RESTRICT") || haystack.contains("UAS") || haystack.contains("HAZARD")) {
            return NotamChipSeverity.Caution
        }
        return NotamChipSeverity.Normal
    }

    private fun distanceToGeometry(origin: Location, geometry: JSONObject): GeometryProximity? {
        return when (geometry.optString("type")) {
            "Point" -> geometry.optJSONArray("coordinates")?.let { coords ->
                if (coords.length() >= 2) proximityToPoint(origin, coords.optDouble(1), coords.optDouble(0)) else null
            }
            "LineString" -> minDistanceToLine(origin, geometry.optJSONArray("coordinates"))
            "Polygon" -> distanceToPolygon(origin, geometry.optJSONArray("coordinates")?.optJSONArray(0))
            "MultiPolygon" -> {
                val groups = geometry.optJSONArray("coordinates") ?: return null
                var minDistance: GeometryProximity? = null
                for (i in 0 until groups.length()) {
                    val ring = groups.optJSONArray(i)?.optJSONArray(0)
                    val candidate = distanceToPolygon(origin, ring)
                    if (candidate != null) minDistance = minOfNullable(minDistance, candidate)
                }
                minDistance
            }
            "GeometryCollection" -> {
                val geometries = geometry.optJSONArray("geometries") ?: return null
                var minDistance: GeometryProximity? = null
                for (i in 0 until geometries.length()) {
                    val candidate = geometries.optJSONObject(i)?.let { distanceToGeometry(origin, it) }
                    if (candidate != null) minDistance = minOfNullable(minDistance, candidate)
                }
                minDistance
            }
            else -> null
        }
    }

    private fun minDistanceToLine(origin: Location, coordinates: JSONArray?): GeometryProximity? {
        if (coordinates == null) return null
        var minDistance: GeometryProximity? = null
        for (i in 0 until coordinates.length()) {
            val coords = coordinates.optJSONArray(i) ?: continue
            if (coords.length() < 2) continue
            val candidate = proximityToPoint(origin, coords.optDouble(1), coords.optDouble(0))
            minDistance = minOfNullable(minDistance, candidate)
        }
        return minDistance
    }

    private fun distanceToPolygon(origin: Location, ring: JSONArray?): GeometryProximity? {
        if (ring == null || ring.length() < 3) return null
        if (pointInPolygon(origin.latitude, origin.longitude, ring)) return GeometryProximity(0.0, null)
        return minDistanceToLine(origin, ring)
    }

    private fun pointInPolygon(lat: Double, lon: Double, ring: JSONArray): Boolean {
        var inside = false
        var j = ring.length() - 1
        for (i in 0 until ring.length()) {
            val pi = ring.optJSONArray(i) ?: continue
            val pj = ring.optJSONArray(j) ?: continue
            val xi = pi.optDouble(0)
            val yi = pi.optDouble(1)
            val xj = pj.optDouble(0)
            val yj = pj.optDouble(1)
            val intersects = ((yi > lat) != (yj > lat)) &&
                (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { it != 0.0 } ?: 1e-9) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    private fun proximityToPoint(origin: Location, lat: Double, lon: Double): GeometryProximity {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat - origin.latitude)
        val dLon = Math.toRadians(lon - origin.longitude)
        val lat1 = Math.toRadians(origin.latitude)
        val lat2 = Math.toRadians(lat)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return GeometryProximity((earthRadiusMeters * c) / 1852.0, bearing)
    }

    private fun JSONObject.toNotamGeometries(): List<NotamGeometry> {
        return when (optString("type")) {
            "Point" -> optJSONArray("coordinates")?.toPointGeometry()?.let(::listOf).orEmpty()
            "LineString" -> optJSONArray("coordinates")?.toCoordinateList()
                ?.takeIf { it.size >= 2 }
                ?.let { listOf(NotamGeometry.Line(it)) }
                .orEmpty()
            "Polygon" -> optJSONArray("coordinates")?.toPolygonGeometry()?.let(::listOf).orEmpty()
            "MultiPolygon" -> {
                val polygons = optJSONArray("coordinates") ?: return emptyList()
                buildList {
                    for (i in 0 until polygons.length()) {
                        polygons.optJSONArray(i)?.toPolygonGeometry()?.let(::add)
                    }
                }
            }
            "GeometryCollection" -> {
                val geometries = optJSONArray("geometries") ?: return emptyList()
                buildList {
                    for (i in 0 until geometries.length()) {
                        geometries.optJSONObject(i)?.toNotamGeometries()?.forEach(::add)
                    }
                }
            }
            else -> emptyList()
        }
    }

    private fun JSONArray.toPointGeometry(): NotamGeometry.Point? {
        if (length() < 2) return null
        return NotamGeometry.Point(
            NotamLatLng(
                latitude = optDouble(1),
                longitude = optDouble(0)
            )
        )
    }

    private fun JSONArray.toPolygonGeometry(): NotamGeometry.Polygon? {
        val rings = buildList {
            for (i in 0 until length()) {
                optJSONArray(i)?.toCoordinateList()?.takeIf { it.size >= 3 }?.let(::add)
            }
        }
        return rings.takeIf { it.isNotEmpty() }?.let { NotamGeometry.Polygon(it) }
    }

    private fun JSONArray.toCoordinateList(): List<NotamLatLng> {
        return buildList {
            for (i in 0 until length()) {
                val item = optJSONArray(i) ?: continue
                if (item.length() < 2) continue
                add(
                    NotamLatLng(
                        latitude = item.optDouble(1),
                        longitude = item.optDouble(0)
                    )
                )
            }
        }
    }

    private fun minOfNullable(a: GeometryProximity?, b: GeometryProximity): GeometryProximity =
        if (a == null || b.distanceNm < a.distanceNm) b else a

    private fun buildUiState(
        location: Location?,
        configured: Boolean,
        loading: Boolean,
        notices: List<NearbyNotam>
    ): NotamUiState {
        val enabled = CaltopoClient.GetNotamEnabled()
        if (!enabled) return NotamUiState(visible = false, enabled = false, configured = configured)

        val sorted = NotamPolicy.sort(notices)
        val (visibleNotices, suppressedNoticeCount) = NotamPolicy.filterWithinRadius(sorted, CaltopoClient.GetNotamRadiusNm())
        val nearestHiddenNotice = sorted.firstOrNull { notice ->
            notice !in visibleNotices
        }
        val lastUpdatedMs = maxOf(CaltopoClient.GetNotamLastUpdatedEpochMs(), lastRefreshAtMs)
        val stale = if (lastUpdatedMs == 0L) false else System.currentTimeMillis() - lastUpdatedMs > 180_000L
        val chipSeverity = when {
            lastErrorMessage != null && visibleNotices.isEmpty() -> NotamChipSeverity.Neutral
            visibleNotices.any { it.intersectsPilotBubble || it.severity == NotamChipSeverity.Danger } -> NotamChipSeverity.Danger
            visibleNotices.any { it.severity == NotamChipSeverity.Caution } -> NotamChipSeverity.Caution
            configured -> NotamChipSeverity.Normal
            else -> NotamChipSeverity.Neutral
        }
        val chipLabel = when {
            loading -> "NOTAMs updating..."
            lastErrorMessage != null && visibleNotices.isEmpty() -> "NOTAMs unavailable"
            visibleNotices.any { it.intersectsPilotBubble } -> {
                val first = visibleNotices.first { it.intersectsPilotBubble }
                val dist = first.distanceNm?.let { String.format(Locale.US, "%.1f NM", it) } ?: "pilot area"
                "NOTAMs: RESTRICTED $dist"
            }
            visibleNotices.isNotEmpty() -> "NOTAMs: ${visibleNotices.size} nearby"
            configured -> "NOTAMs clear"
            else -> "NOTAMs pending"
        }
        val statusLine = buildString {
            if (lastErrorMessage != null) {
                append(lastErrorMessage)
            } else if (location != null && location.hasAccuracy()) {
                append("GPS accuracy ")
                append("%.0f ft".format(Locale.US, location.accuracy * 3.28084))
            } else {
                append("Waiting for reliable GPS fix")
            }
        }
        return NotamUiState(
            visible = true,
            enabled = enabled,
            configured = configured,
            loading = loading,
            stale = stale,
            chipSeverity = chipSeverity,
            chipLabel = chipLabel,
            statusLine = statusLine,
            lastUpdatedText = formatLastUpdated(lastUpdatedMs, stale),
            queryLatitude = lastRefreshLocation?.latitude,
            queryLongitude = lastRefreshLocation?.longitude,
            radiusNm = CaltopoClient.GetNotamRadiusNm(),
            notices = visibleNotices,
            suppressedNoticeCount = suppressedNoticeCount,
            nearestHiddenNotice = nearestHiddenNotice,
            errorMessage = lastErrorMessage
        )
    }

    private fun proximityText(distanceNm: Double?, bearingDeg: Double?): String {
        if (distanceNm == null) return "Distance unavailable"
        if (distanceNm < 0.05) return "HERE"
        val distanceText = String.format(Locale.US, "%.1f NM", distanceNm)
        val bearingText = bearingDeg?.let(::compassDirection)
        return if (bearingText.isNullOrBlank()) distanceText else "$bearingText $distanceText"
    }

    private fun compassDirection(bearingDeg: Double): String {
        val directions = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
        val index = (((normalized + 11.25) / 22.5).toInt()) % directions.size
        return directions[index]
    }

    private fun formatLastUpdated(epochMs: Long, stale: Boolean): String? {
        if (epochMs <= 0L) return null
        val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
            .withZone(ZoneId.systemDefault())
        val prefix = if (stale) "Stale since " else "Updated "
        return prefix + formatter.format(Instant.ofEpochMilli(epochMs))
    }
}

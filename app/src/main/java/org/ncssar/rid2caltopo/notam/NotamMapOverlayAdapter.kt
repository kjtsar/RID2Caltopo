package org.ncssar.rid2caltopo.notam

import android.graphics.Color
import android.location.Location
import org.ncssar.rid2caltopo.airspace.OperatingArea
import org.osmdroid.util.GeoPoint
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class NotamMapPolygonSpec(
    val id: String,
    val notice: NearbyNotam?,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String
)

internal data class NotamMapLineSpec(
    val id: String,
    val notice: NearbyNotam,
    val points: List<GeoPoint>,
    val color: Int,
    val width: Float,
    val title: String
)

internal data class NotamMapPointSpec(
    val id: String,
    val notices: List<NearbyNotam>,
    val point: GeoPoint,
    val color: Int,
    val title: String
)

internal data class NotamMapOverlayState(
    val points: List<NotamMapPointSpec> = emptyList(),
    val lines: List<NotamMapLineSpec> = emptyList(),
    val polygons: List<NotamMapPolygonSpec> = emptyList()
)

internal object NotamMapOverlayAdapter {
    private const val PILOT_RING_ID = "notam_pilot_ring"
    private const val EARTH_RADIUS_NM = 3440.065
    private const val PILOT_RING_STROKE = 3.5f
    private const val NOTAM_POLYGON_STROKE = 4.0f
    private const val NOTAM_LINE_STROKE = 5.0f

    fun build(
        state: NotamUiState,
        myLocation: Location?
    ): NotamMapOverlayState {
        if (!state.enabled || !state.visible) return NotamMapOverlayState()
        val polygons = mutableListOf<NotamMapPolygonSpec>()
        val lines = mutableListOf<NotamMapLineSpec>()
        val pointBuckets = linkedMapOf<String, MutableList<NearbyNotamPoint>>()

        myLocation?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }?.let { location ->
            polygons += NotamMapPolygonSpec(
                id = PILOT_RING_ID,
                notice = null,
                points = buildCircle(location.latitude, location.longitude, OperatingArea.radiusNm),
                strokeColor = Color.parseColor("#1976D2"),
                fillColor = Color.parseColor("#101976D2"),
                strokeWidth = PILOT_RING_STROKE,
                title = "Pilot ${OperatingArea.displayLabel}"
            )
        }

        state.notices.forEach { notice ->
            val style = styleFor(notice)
            notice.geometries.forEachIndexed { index, geometry ->
                when (geometry) {
                    is NotamGeometry.Point -> {
                        val point = geometry.coordinate.toGeoPoint()
                        val key = pointKey(point)
                        pointBuckets.getOrPut(key) { mutableListOf() } += NearbyNotamPoint(
                            id = "${notice.id}:pt:$index",
                            notice = notice,
                            point = point
                        )
                    }

                    is NotamGeometry.Line -> {
                        val geoPoints = geometry.coordinates.map { it.toGeoPoint() }
                        if (geoPoints.size >= 2) {
                            lines += NotamMapLineSpec(
                                id = "${notice.id}:line:$index",
                                notice = notice,
                                points = geoPoints,
                                color = style.strokeColor,
                                width = NOTAM_LINE_STROKE,
                                title = notice.title
                            )
                        }
                    }

                    is NotamGeometry.Polygon -> {
                        geometry.rings.firstOrNull()?.map { it.toGeoPoint() }?.takeIf { it.size >= 3 }?.let { ring ->
                            polygons += NotamMapPolygonSpec(
                                id = "${notice.id}:poly:$index",
                                notice = notice,
                                points = ring,
                                strokeColor = style.strokeColor,
                                fillColor = style.fillColor,
                                strokeWidth = NOTAM_POLYGON_STROKE,
                                title = notice.title
                            )
                        }
                    }

                    is NotamGeometry.Collection -> {
                        geometry.geometries.forEachIndexed { nestedIndex, nested ->
                            val nestedState = build(
                                state = state.copy(notices = listOf(notice.copy(geometries = listOf(nested)))),
                                myLocation = null
                            )
                            polygons += nestedState.polygons.map { spec -> spec.copy(id = "${spec.id}:nested:$nestedIndex") }
                            lines += nestedState.lines.map { spec -> spec.copy(id = "${spec.id}:nested:$nestedIndex") }
                            nestedState.points.forEach { spec ->
                                val key = pointKey(spec.point)
                                pointBuckets.getOrPut(key) { mutableListOf() } += spec.notices.mapIndexed { noticeIndex, groupedNotice ->
                                    NearbyNotamPoint(
                                        id = "${spec.id}:nested:$nestedIndex:$noticeIndex",
                                        notice = groupedNotice,
                                        point = spec.point
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val points = pointBuckets.values.map { bucket ->
            val point = bucket.first().point
            val notices = NotamPolicy.sort(bucket.map { it.notice }.distinctBy { it.id })
            val highestSeverityNotice = notices.minByOrNull { severityRank(it) } ?: notices.first()
            NotamMapPointSpec(
                id = bucket.joinToString(separator = "|") { it.id },
                notices = notices,
                point = point,
                color = styleFor(highestSeverityNotice).strokeColor,
                title = if (notices.size == 1) notices.first().title else "${notices.size} nearby NOTAMs"
            )
        }

        return NotamMapOverlayState(
            points = points,
            lines = lines,
            polygons = polygons
        )
    }

    private data class NearbyNotamPoint(
        val id: String,
        val notice: NearbyNotam,
        val point: GeoPoint
    )

    private data class OverlayStyle(
        val strokeColor: Int,
        val fillColor: Int
    )

    private fun styleFor(notice: NearbyNotam): OverlayStyle {
        return when {
            notice.severity == NotamChipSeverity.Danger ->
                OverlayStyle(
                    strokeColor = Color.parseColor("#D32F2F"),
                    fillColor = Color.parseColor("#33D32F2F")
                )

            notice.intersectsPilotBubble || notice.severity == NotamChipSeverity.Caution ->
                OverlayStyle(
                    strokeColor = Color.parseColor("#F57C00"),
                    fillColor = Color.parseColor("#33F57C00")
                )

            else ->
                OverlayStyle(
                    strokeColor = Color.parseColor("#2E7D32"),
                    fillColor = Color.parseColor("#332E7D32")
                )
        }
    }

    private fun buildCircle(
        latitude: Double,
        longitude: Double,
        radiusNm: Double,
        steps: Int = 48
    ): List<GeoPoint> {
        val latRad = Math.toRadians(latitude)
        val lonRad = Math.toRadians(longitude)
        val angularDistance = radiusNm / EARTH_RADIUS_NM
        return buildList {
            for (i in 0..steps) {
                val bearing = (Math.PI * 2.0 * i) / steps.toDouble()
                val lat2 = asin(
                    sin(latRad) * cos(angularDistance) +
                        cos(latRad) * sin(angularDistance) * cos(bearing)
                )
                val lon2 = lonRad + atan2(
                    sin(bearing) * sin(angularDistance) * cos(latRad),
                    cos(angularDistance) - sin(latRad) * sin(lat2)
                )
                add(GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2)))
            }
        }
    }

    private fun pointKey(point: GeoPoint): String =
        String.format(Locale.US, "%.6f,%.6f", point.latitude, point.longitude)

    private fun severityRank(notice: NearbyNotam): Int = when {
        notice.severity == NotamChipSeverity.Danger -> 0
        notice.intersectsPilotBubble || notice.severity == NotamChipSeverity.Caution -> 1
        notice.severity == NotamChipSeverity.Normal -> 2
        else -> 3
    }

    private fun NotamLatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)
}

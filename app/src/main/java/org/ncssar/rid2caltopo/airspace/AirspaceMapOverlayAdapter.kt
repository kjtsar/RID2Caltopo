package org.ncssar.rid2caltopo.airspace

import android.graphics.Color
import org.osmdroid.util.GeoPoint

internal data class AirspacePolygonSpec(
    val id: String,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String
)

internal object AirspaceMapOverlayAdapter {
    fun build(state: AirspaceUiState): List<AirspacePolygonSpec> = buildList {
        if (!state.visible) return@buildList
        state.records.forEach { record ->
            val stroke = if (record.ceilingFeet == 0) Color.RED else Color.rgb(245, 124, 0)
            val alpha = if (record.ceilingFeet == 0) 0x38 else 0x20
            val fill = Color.argb(alpha, Color.red(stroke), Color.green(stroke), Color.blue(stroke))
            val ceiling = record.ceilingFeet?.let { "$it ${record.unit.lowercase()}" } ?: "ceiling not published"
            record.rings.forEachIndexed { index, ring ->
                val points = ring.map { GeoPoint(it.latitude, it.longitude) }
                if (points.size >= 3) {
                    add(
                        AirspacePolygonSpec(
                            id = "${record.objectId}:$index",
                            points = points,
                            strokeColor = stroke,
                            fillColor = fill,
                            strokeWidth = if (record.ceilingFeet == 0) 4f else 2f,
                            title = "${record.primaryAirportName}: $ceiling"
                        )
                    )
                }
            }
        }
    }
}

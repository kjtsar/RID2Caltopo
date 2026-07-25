package org.ncssar.rid2caltopo.landrestrictions

import android.graphics.Color
import org.osmdroid.util.GeoPoint

internal data class LandRestrictionPolygonSpec(
    val id: String,
    val area: LandRestrictionArea,
    val points: List<GeoPoint>,
    val strokeColor: Int,
    val fillColor: Int,
    val strokeWidth: Float,
    val title: String
)

internal object LandRestrictionMapOverlayAdapter {
    fun build(state: LandRestrictionUiState, showOnMap: Boolean): List<LandRestrictionPolygonSpec> {
        if (!state.enabled || !state.visible || !showOnMap) return emptyList()
        return buildList {
            state.areas.forEach { area ->
                val stroke = when (area.agency) {
                    LandAgency.NationalParkService -> Color.parseColor("#795548")
                    LandAgency.FishAndWildlifeService -> Color.parseColor("#8E24AA")
                    LandAgency.ForestService -> Color.parseColor("#2E7D32")
                    LandAgency.ColoradoParksAndWildlife -> Color.parseColor("#00897B")
                }
                val alpha = if (area.containsOperator) 0x38 else 0x20
                val fill = Color.argb(alpha, Color.red(stroke), Color.green(stroke), Color.blue(stroke))
                area.polygons.forEachIndexed { index, polygon ->
                    polygon.firstOrNull()?.map { GeoPoint(it.latitude, it.longitude) }
                        ?.takeIf { it.size >= 3 }
                        ?.let { points ->
                            add(
                                LandRestrictionPolygonSpec(
                                    id = "${area.id}:$index",
                                    area = area,
                                    points = points,
                                    strokeColor = stroke,
                                    fillColor = fill,
                                    strokeWidth = if (area.containsOperator) 4f else 2f,
                                    title = "${area.name}: ${area.rule.label}"
                                )
                            )
                        }
                }
            }
        }
    }
}

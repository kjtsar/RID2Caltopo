package org.ncssar.rid2caltopo.notam

data class NotamLatLng(
    val latitude: Double,
    val longitude: Double
)

sealed interface NotamGeometry {
    data class Point(val coordinate: NotamLatLng) : NotamGeometry
    data class Line(val coordinates: List<NotamLatLng>) : NotamGeometry
    data class Polygon(val rings: List<List<NotamLatLng>>) : NotamGeometry
    data class Collection(val geometries: List<NotamGeometry>) : NotamGeometry
}

enum class NotamChipSeverity {
    Neutral,
    Normal,
    Caution,
    Danger
}

data class NearbyNotam(
    val id: String,
    val title: String,
    val summary: String,
    val distanceNm: Double?,
    val bearingText: String? = null,
    val proximityText: String = "",
    val intersectsPilotBubble: Boolean = false,
    val effectiveText: String = "",
    val details: String = "",
    val rawText: String = "",
    val rawTitle: String = "",
    val rawReference: String = "",
    val updateType: String = "",
    val cancelationDate: String = "",
    val lastUpdated: String = "",
    val severity: NotamChipSeverity = NotamChipSeverity.Normal,
    val geometries: List<NotamGeometry> = emptyList()
)

data class NotamUiState(
    val visible: Boolean = false,
    val enabled: Boolean = false,
    val configured: Boolean = false,
    val loading: Boolean = false,
    val stale: Boolean = false,
    val chipSeverity: NotamChipSeverity = NotamChipSeverity.Neutral,
    val chipLabel: String = "NOTAMs unavailable",
    val statusLine: String = "",
    val lastUpdatedText: String? = null,
    val queryLatitude: Double? = null,
    val queryLongitude: Double? = null,
    val radiusNm: Int = 2,
    val notices: List<NearbyNotam> = emptyList(),
    val suppressedNoticeCount: Int = 0,
    val nearestHiddenNotice: NearbyNotam? = null,
    val errorMessage: String? = null
)

package org.ncssar.rid2caltopo.notam

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
    val severity: NotamChipSeverity = NotamChipSeverity.Normal
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
    val radiusNm: Int = 2,
    val notices: List<NearbyNotam> = emptyList(),
    val suppressedNoticeCount: Int = 0,
    val nearestHiddenNotice: NearbyNotam? = null,
    val errorMessage: String? = null
)

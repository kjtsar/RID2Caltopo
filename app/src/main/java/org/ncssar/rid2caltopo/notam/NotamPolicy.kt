package org.ncssar.rid2caltopo.notam

internal object NotamPolicy {
    private fun severityRank(severity: NotamChipSeverity): Int = when (severity) {
        NotamChipSeverity.Danger -> 0
        NotamChipSeverity.Caution -> 1
        NotamChipSeverity.Normal -> 2
        NotamChipSeverity.Neutral -> 3
    }

    fun sort(notices: List<NearbyNotam>): List<NearbyNotam> {
        return notices.sortedWith(
            compareBy<NearbyNotam>(
                { !it.intersectsPilotBubble },
                { severityRank(it.severity) },
                { it.distanceNm ?: Double.MAX_VALUE },
                { it.title }
            )
        )
    }

    fun filterWithinRadius(notices: List<NearbyNotam>, radiusNm: Int): Pair<List<NearbyNotam>, Int> {
        val visible = notices.filter { notice ->
            notice.intersectsPilotBubble ||
                notice.distanceNm == null ||
                notice.distanceNm <= radiusNm.toDouble()
        }
        return visible to (notices.size - visible.size)
    }
}

package org.ncssar.rid2caltopo.airspace

object AirspacePolicy {
    fun buildUiState(
        records: List<FaaUasFacilityMapRecord>,
        loading: Boolean,
        errorMessage: String?
    ): AirspaceUiState {
        if (loading) {
            return AirspaceUiState(
                loading = true,
                chipSeverity = AirspaceChipSeverity.Neutral,
                chipLabel = "Airspace updating...",
                records = records
            )
        }
        if (errorMessage != null && records.isEmpty()) {
            return AirspaceUiState(
                chipSeverity = AirspaceChipSeverity.Neutral,
                chipLabel = "Airspace unavailable",
                detail = errorMessage,
                errorMessage = errorMessage
            )
        }
        val controlled = records.filter { it.airspaceClasses.isNotEmpty() }
        if (controlled.isNotEmpty()) {
            // FAA guidance says an operation spanning multiple UASFM grids must use the
            // lowest published altitude. ArcGIS result order is not deterministic.
            val representative = controlled.minWithOrNull(
                compareBy<FaaUasFacilityMapRecord>(
                    { it.ceilingFeet ?: Int.MAX_VALUE },
                    { it.primaryAirportName },
                    { it.objectId }
                )
            )!!
            val airport = shortAirportName(representative.primaryAirportName)
            val classText = controlled
                .flatMap { it.airspaceClasses }
                .distinct()
                .sorted()
                .joinToString("/") { "Class $it" }
            val gridLimit = controlled.mapNotNull { it.ceilingFeet }.minOrNull()
            val gridLimitText = gridLimit?.let { "; FAA grid limit $it ft AGL" }.orEmpty()
            val coordinationText = gridLimit?.let {
                " The displayed $it ft AGL value is the lowest FAA UAS Facility Map limit across the area, not the top of the controlled-airspace class. Requests above it require further FAA coordination."
            }.orEmpty()
            return AirspaceUiState(
                chipSeverity = AirspaceChipSeverity.Danger,
                chipLabel = "Airspace: Authorization required - $airport $classText$gridLimitText",
                summary = "$airport $classText",
                detail = "Controlled airspace intersects the ${OperatingArea.displayLabel}. FAA authorization is required before flight.$coordinationText",
                records = records,
                errorMessage = errorMessage
            )
        }
        return AirspaceUiState(
            chipSeverity = AirspaceChipSeverity.Normal,
            chipLabel = "Airspace clear",
            summary = "No FAA UAS Facility Map grid at this location",
            records = records,
            errorMessage = errorMessage
        )
    }

    private fun shortAirportName(name: String): String =
        name.substringBefore(" (").ifBlank { "Controlled airspace" }
}

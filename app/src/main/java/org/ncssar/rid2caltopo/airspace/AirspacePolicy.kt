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
        val controlled = records.firstOrNull { it.airspaceClasses.isNotEmpty() }
        if (controlled != null) {
            val airport = shortAirportName(controlled.primaryAirportName)
            val classText = controlled.airspaceClasses.joinToString("/") { "Class $it" }
            val ceiling = controlled.ceilingFeet?.let { " up to $it ft" }.orEmpty()
            val prefix = if (controlled.laancAvailable) "LAANC required" else "Authorization required"
            return AirspaceUiState(
                chipSeverity = AirspaceChipSeverity.Caution,
                chipLabel = "Airspace: $prefix - $airport $classText$ceiling",
                summary = "$airport $classText",
                detail = "Controlled airspace intersects the ${OperatingArea.displayLabel}. FAA authorization is required before flight.",
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

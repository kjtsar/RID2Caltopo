package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.data.CtDroneSpec

data class DroneSpecConfirmationUiState(
    val remoteId: String,
    val organization: String,
    val pilotCallsign: String,
    val droneDescription: String,
    val initialPilotCallsign: String = pilotCallsign,
    val initialDroneDescription: String = droneDescription,
    val warning: String? = null,
    val pilotCallsignError: String? = null,
    val usesUnknownOrganizationDefault: Boolean = false
)

object DroneSpecConfirmationLogic {
    fun buildInitialState(
        drone: CtDroneSpec,
        defaultOrganization: String,
        defaultUnknownOrganization: String = "",
        defaultPilotCallsign: String = ""
    ): DroneSpecConfirmationUiState {
        val hasKnownSpecFields = drone.org.isNotBlank() || drone.model.isNotBlank() || drone.owner.isNotBlank()
        val guessedDescription = CtDroneSpec.GuessMakeModel(drone.remoteId)
        val usesUnknownOrganizationDefault = drone.org.isBlank() && drone.mappedId == drone.remoteId
        val droneDescription = drone.model
            .takeIf { it.isNotBlank() }
            ?: if (drone.mappedId == drone.remoteId) guessedDescription else ""
        val organization = when {
            drone.org.isNotBlank() -> drone.org
            usesUnknownOrganizationDefault -> defaultUnknownOrganization
            else -> defaultOrganization
        }
        val mappedPilotCallsign = if (hasKnownSpecFields && drone.mappedId != drone.remoteId) {
            guessPilotCallsign(drone, droneDescription, guessedDescription)
        } else {
            ""
        }
        val pilotCallsign = defaultPilotCallsign.trim().ifBlank { mappedPilotCallsign }
        val warning = if (drone.mappedId == drone.remoteId && drone.org.isBlank()) {
            "New or mutual-aid drone detected. Confirm org, pilot callsign, and drone description before continuing."
        } else {
            null
        }
        return DroneSpecConfirmationUiState(
            remoteId = drone.remoteId,
            organization = organization,
            pilotCallsign = pilotCallsign,
            droneDescription = droneDescription,
            initialPilotCallsign = pilotCallsign,
            initialDroneDescription = droneDescription,
            warning = warning,
            usesUnknownOrganizationDefault = usesUnknownOrganizationDefault
        )
    }

    fun shouldPreserveMappedId(
        existingMappedId: String,
        remoteId: String,
        initialPilotCallsign: String,
        savedPilotCallsign: String,
        initialDroneDescription: String,
        savedDroneDescription: String
    ): Boolean {
        if (existingMappedId.isBlank() || existingMappedId == remoteId) return false
        return initialPilotCallsign.trim() == savedPilotCallsign.trim() &&
            initialDroneDescription.trim() == savedDroneDescription.trim()
    }

    private fun guessPilotCallsign(
        drone: CtDroneSpec,
        droneDescription: String,
        guessedDescription: String
    ): String {
        val mappedId = drone.mappedId.trim()
        if (mappedId.isEmpty() || mappedId == drone.remoteId) return ""

        val candidateDescriptions = linkedSetOf<String>().apply {
            if (droneDescription.isNotBlank()) add(droneDescription)
            if (guessedDescription.isNotBlank()) add(guessedDescription)
            if (drone.model.isNotBlank()) add(drone.model)
        }
        for (description in candidateDescriptions) {
            val callsign = CtDroneSpec.GuessPilotCallsign(mappedId, description, drone.remoteId)
            if (callsign.isNotBlank() && callsign.length < mappedId.length) {
                return callsign
            }
        }
        val fallback = CtDroneSpec.GuessPilotCallsign(mappedId, "", drone.remoteId)
        return if (fallback.length < mappedId.length) fallback else ""
    }
}

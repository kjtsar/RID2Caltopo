package org.ncssar.rid2caltopo.ui

import org.ncssar.rid2caltopo.data.CtDroneSpec

data class DroneSpecConfirmationUiState(
    val remoteId: String,
    val organization: String,
    val pilotCallsign: String,
    val droneDescription: String,
    val warning: String? = null
)

object DroneSpecConfirmationLogic {
    fun buildInitialState(
        drone: CtDroneSpec,
        defaultOrganization: String
    ): DroneSpecConfirmationUiState {
        val hasKnownSpecFields = drone.org.isNotBlank() || drone.model.isNotBlank() || drone.owner.isNotBlank()
        val droneDescription = drone.model
            .takeIf { it.isNotBlank() }
            ?: if (drone.mappedId == drone.remoteId) CtDroneSpec.GuessMakeModel(drone.remoteId) else ""
        val organization = drone.org
            .takeIf { it.isNotBlank() }
            ?: defaultOrganization
        val pilotCallsign = if (hasKnownSpecFields && drone.mappedId != drone.remoteId) {
            CtDroneSpec.GuessPilotCallsign(
                drone.mappedId,
                droneDescription,
                drone.remoteId
            )
        } else {
            ""
        }
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
            warning = warning
        )
    }
}

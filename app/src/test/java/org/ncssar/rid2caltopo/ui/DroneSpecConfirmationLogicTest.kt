package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.ncssar.rid2caltopo.data.CtDroneSpec

class DroneSpecConfirmationLogicTest {
    @Test
    fun configuredDrone_prefillsExistingValuesFromRidmapSample() {
        val drone = CtDroneSpec(
            "1581F6Z9C24BH0036EJL",
            "1SAR7mm4p",
            "NCSSAR",
            "DJI Mavic Mini 4 Pro",
            "Ken Taylor"
        )

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "DEFAULT")

        assertEquals("1581F6Z9C24BH0036EJL", state.remoteId)
        assertEquals("NCSSAR", state.organization)
        assertEquals("1SAR7", state.pilotCallsign)
        assertEquals("DJI Mavic Mini 4 Pro", state.droneDescription)
        assertNull(state.warning)
    }

    @Test
    fun teamDrone_placeholderKeepsOrgAndModelButRequiresPilotCallsign() {
        val drone = CtDroneSpec(
            "1581F8HGX255W00A0H2W",
            "1sar1001m4td-1",
            "NCSSAR",
            "DJI Matrice 4TD 01",
            "NCSSAR"
        )

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "DEFAULT")

        assertEquals("NCSSAR", state.organization)
        assertEquals("", state.pilotCallsign)
        assertEquals("DJI Matrice 4TD 01", state.droneDescription)
        assertNull(state.warning)
    }

    @Test
    fun unknownMutualAidDrone_usesDefaultOrgAndGuessOnlyWhenMappedIdEqualsRemoteId() {
        val drone = CtDroneSpec("1581F6Z9C24BK0036M9Q")

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "NCSSAR")

        assertEquals("NCSSAR", state.organization)
        assertEquals("", state.pilotCallsign)
        assertEquals("DJI Mini 4 Pro", state.droneDescription)
        assertEquals(
            "New or mutual-aid drone detected. Confirm org, pilot callsign, and drone description before continuing.",
            state.warning
        )
    }

    @Test
    fun avata360RemoteId_prefillsDiscoveredModelDescription() {
        val drone = CtDroneSpec("1581FBLKC262T00B07G1")

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "NCSSAR")

        assertEquals("NCSSAR", state.organization)
        assertEquals("", state.pilotCallsign)
        assertEquals("DJI Avata 360", state.droneDescription)
    }

    @Test
    fun nonDefaultMappedId_doesNotGuessDescriptionWhenModelIsBlank() {
        val drone = CtDroneSpec(
            "1581F6Z9C249G0031CLB",
            "1SAR33m4p",
            "NCSSAR",
            "",
            "Jeff Landre"
        )

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "DEFAULT")

        assertEquals("NCSSAR", state.organization)
        assertEquals("1SAR33", state.pilotCallsign)
        assertEquals("", state.droneDescription)
        assertNull(state.warning)
    }

    @Test
    fun unknownDroneWithExternallyChangedMappedId_doesNotInventCallsign() {
        val drone = CtDroneSpec(
            "1668BR40EA00Z5VX",
            "1SAR7m4p",
            "",
            "",
            ""
        )

        val state = DroneSpecConfirmationLogic.buildInitialState(drone, defaultOrganization = "NCSSAR")

        assertEquals("NCSSAR", state.organization)
        assertEquals("", state.pilotCallsign)
        assertEquals("", state.droneDescription)
        assertNull(state.warning)
    }
}

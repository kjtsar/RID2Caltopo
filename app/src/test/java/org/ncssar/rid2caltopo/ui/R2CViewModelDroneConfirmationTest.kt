package org.ncssar.rid2caltopo.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.CaltopoLiveTrack
import org.ncssar.rid2caltopo.data.CtDroneSpec
import org.ncssar.rid2caltopo.data.SimpleTimer

class R2CViewModelDroneConfirmationTest {
    @Before
    fun setUp() {
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun trackerConfirmedRemoteIdDoesNotReopenDuringCurrentActiveLifecycle() {
        val remoteId = "DRONEPEER"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 1234L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun trackerConfirmationReplayBeforeLocalSightingSuppressesCurrentPanel() {
        val remoteId = "DRONELATE"
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(emptyList())

        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = 3456L)))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun operatorRequestedConfirmationStaysOpenAfterPeerSave() {
        val remoteId = "DRONEINSPECT"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 4567L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.requestDroneConfirmation(drone)
        assertNotNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun trackerConfirmationDoesNotSuppressAfterTrackerLifecycleEnds() {
        val remoteId = "DRONEREUSE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 5678L)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoClient.ClearCurrentPeerDroneConfirmation(remoteId)
        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun unconfirmedFlightStillOpensConfirmationPanel() {
        val remoteId = "DRONELOCAL"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 5678L)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun firstRidSightingOpensConfirmationBeforeAcceptedWaypoint() {
        val remoteId = "DRONEFIRSTSEEN"
        val drone = CtDroneSpec(remoteId)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    @Test
    fun suppressedFirstRidSightingDoesNotOpenConfirmationPanel() {
        val remoteId = "ELDORADOFIRSTSEEN"
        val drone = CaltopoClient.ClientForRemoteId(remoteId).droneSpec

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun suppressedActiveDroneDoesNotOpenConfirmationPanel() {
        val remoteId = "ELDORADOACTIVE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 6789L)
        drone.setLocalArchiveOnly(true)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun firstRidSightingPromptIsNotReopenedWhenFlightBecomesActive() {
        val remoteId = "DRONEFIRSTSEENACTIVE"
        val drone = CtDroneSpec(remoteId)

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(drone)
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.markPendingDroneConfirmationUnknown()
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(listOf(activeDrone(remoteId, waypointTimestampMsec = 6789L)))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun peerConfirmedFirstRidSightingDoesNotOpenConfirmationPanel() {
        val remoteId = "DRONEFIRSTSEENPEER"
        CaltopoClient.ApplyPeerDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR8",
            "1SAR8m3"
        )

        val viewModel = R2CViewModel(SimpleTimer())
        viewModel.onDroneConfirmationCandidate(CtDroneSpec(remoteId))

        assertNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun localTrackPointPublishesDroneToMainScreenList() {
        val remoteId = "DRONESCREEN"
        CaltopoClient.SaveDroneSpecConfirmation(
            remoteId,
            "NCSSAR",
            "DJI Mavic 3",
            "1SAR7",
            "1SAR7m3"
        )
        val viewModel = R2CViewModel(SimpleTimer())
        val client = CaltopoClient.ClientForRemoteId(remoteId)
        client.droneSpec.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            1234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        client.newWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            CtDroneSpec.TransportTypeEnum.BT4,
            true
        )

        assertEquals(remoteId, viewModel.drones.value.single().remoteId)
        assertNotNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun localStandalonePromptClearsWhenDroneLeavesActiveList() {
        val remoteId = "DRONESTANDALONE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 91011L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR7",
            droneDescription = "DJI Mavic 3"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)
        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNull(viewModel.pendingDroneConfirmation.value)

        viewModel.onDroneSpecsChanged(emptyList())
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
    }

    @Test
    fun localTrackFinishedClearsPromptLatchEvenBeforeDroneListGoesEmpty() {
        val remoteId = "DRONETRACKDONE"
        val drone = activeDrone(remoteId, waypointTimestampMsec = 121314L)
        val viewModel = R2CViewModel(SimpleTimer())

        viewModel.onDroneSpecsChanged(listOf(drone))
        assertNotNull(viewModel.pendingDroneConfirmation.value)
        viewModel.updatePendingDroneConfirmation(
            organization = "NCSSAR",
            pilotCallsign = "1SAR7",
            droneDescription = "DJI Mavic 3"
        )
        viewModel.savePendingDroneConfirmation()
        assertNull(viewModel.pendingDroneConfirmation.value)

        CaltopoLiveTrack.NotifyLocalTrackFinished(drone, "test track finished")
        viewModel.onDroneSpecsChanged(listOf(drone))

        assertNotNull(viewModel.pendingDroneConfirmation.value)
        assertEquals(remoteId, viewModel.pendingDroneConfirmation.value?.remoteId)
    }

    private fun activeDrone(remoteId: String, waypointTimestampMsec: Long): CtDroneSpec {
        val drone = CtDroneSpec(remoteId)
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            waypointTimestampMsec,
            waypointTimestampMsec,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        return drone
    }
}

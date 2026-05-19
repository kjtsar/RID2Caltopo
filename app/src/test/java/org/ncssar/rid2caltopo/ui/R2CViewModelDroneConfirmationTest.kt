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

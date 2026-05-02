package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class CaltopoClientUnknownPromotionTest {
    private lateinit var fixture: TestR2cRuntimeFactory.Fixture
    private lateinit var peerCoordinator: FakePeerCoordinator

    private class FakeLiveTrack(private val remoteId: String) : LiveTrackOwnerDelegate {
        override fun getRemoteId(): String = remoteId
        override fun setLocalOwner(isOwner: Boolean) = Unit
    }

    @Before
    fun setUp() {
        fixture = TestR2cRuntimeFactory.create("promotion-test")
        fixture.setAsDefaultRuntime()
        peerCoordinator = fixture.peerCoordinator as FakePeerCoordinator
        CaltopoClient.ResetPersistedClientState()
    }

    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun promoteLocalArchiveOnlyDrone_rejoinsCoordinatorForActiveFlight() {
        val remoteId = "DRONE1"
        CaltopoClient.SaveDroneSpecUnknownConfirmation(remoteId)
        val drone = CaltopoClient.GetDroneSpec(remoteId)!!
        drone.checkNewWaypoint(
            39.1,
            -121.2,
            120.0,
            1234L,
            1234L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertFalse(drone.trackLabel().isEmpty())

        val liveTrack = FakeLiveTrack(remoteId)
        CaltopoClient.promoteLocalArchiveOnlyDrone(remoteId, liveTrack)

        assertFalse(drone.isLocalArchiveOnly())
        val event = peerCoordinator.latestEventOfKind("onLiveTrackCreated")
        assertNotNull(event)
        assertEquals("DRONE1 dist=NaN", event!!.summary)
    }
}

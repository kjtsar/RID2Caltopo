package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class R2cRuntimeIsolationTest {

    @After
    fun tearDown() {
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun fixturesRegisterAsIndependentRuntimes() {
        val host = TestR2cRuntimeFactory.create("host")
        val mutualAid = TestR2cRuntimeFactory.create("mutual-aid")

        host.register()
        mutualAid.register()

        assertSame(host.runtime, R2cRuntimeRegistry.getRuntime("host"))
        assertSame(mutualAid.runtime, R2cRuntimeRegistry.getRuntime("mutual-aid"))
        assertNotSame(host.runtime, mutualAid.runtime)
        assertNotSame(host.peerCoordinator, mutualAid.peerCoordinator)
        assertNotSame(host.calTopoSessionGateway, mutualAid.calTopoSessionGateway)
    }

    @Test
    fun peerStateAndSessionOpsStayIsolatedPerRuntime() {
        val host = TestR2cRuntimeFactory.create("host")
        val mutualAid = TestR2cRuntimeFactory.create("mutual-aid")
        val hostPeerCoordinator = host.peerCoordinator as FakePeerCoordinator
        val mutualAidPeerCoordinator = mutualAid.peerCoordinator as FakePeerCoordinator

        val hostPeer = hostPeerCoordinator.newPeerState("peer-host", "Host Peer")
        hostPeerCoordinator.setPeerList(listOf(hostPeer))
        hostPeerCoordinator.setLocalOwnership("RID-HOST", true)
        host.calTopoSessionGateway.init(CaltopoCredentials("team-host", "cred-host", "secret-host"), "caltopo.com")
        host.calTopoSessionGateway.verifyAccount(null)

        assertEquals(1, hostPeerCoordinator.getPeerList().size)
        assertEquals("Host Peer", hostPeerCoordinator.getPeerList().first().name)
        assertEquals(0, mutualAidPeerCoordinator.getPeerList().size)
        assertEquals(true, hostPeerCoordinator.isLocalOwner("RID-HOST"))
        assertEquals(false, mutualAidPeerCoordinator.isLocalOwner("RID-HOST"))

        assertEquals(2, host.calTopoSessionGateway.snapshotOperations().size)
        assertEquals(0, mutualAid.calTopoSessionGateway.snapshotOperations().size)
        assertEquals("init", host.calTopoSessionGateway.snapshotOperations().first().kind)
        assertEquals("verifyAccount", host.calTopoSessionGateway.snapshotOperations().last().kind)
    }

    @Test
    fun defaultRuntimeSwapDoesNotLeakCustomFixtureAfterReset() {
        val host = TestR2cRuntimeFactory.create("host")
        host.setAsDefaultRuntime()

        assertSame(host.runtime, R2cRuntimeRegistry.getDefaultRuntime())
        assertSame(host.runtime, R2cRuntimeRegistry.getRuntime("host"))

        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()

        assertEquals("default", R2cRuntimeRegistry.getDefaultRuntime().runtimeId)
        assertNull(R2cRuntimeRegistry.getRuntime("host"))
    }
}

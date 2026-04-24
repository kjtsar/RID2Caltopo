package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiZoneOwnershipScenarioTest {

    private data class ZoneFixture(
        val guid: String,
        val fixture: TestR2cRuntimeFactory.OwnershipFixture
    )

    @After
    fun tearDown() {
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun sixZonesAndTwentyFourDrones_chooseSingleOwnerAndSinglePublisherPerDrone() {
        val zones = startZones(
            mapId = "matrix-map",
            rttsMs = listOf(120L, 180L, 240L, 300L, 360L, 420L)
        )

        val remoteIds = ArrayList<String>()
        repeat(zones.size) { ownerIndex ->
            repeat(4) { droneOffset ->
                val remoteId = "RID-${ownerIndex + 1}-${droneOffset + 1}"
                remoteIds += remoteId
                val firstSeenTs = 1_000L + ownerIndex * 100L + droneOffset * 10L
                zones.forEachIndexed { zoneIndex, zone ->
                    val distanceMeters = if (zoneIndex == ownerIndex) {
                        18.0 + droneOffset
                    } else {
                        320.0 + (kotlin.math.abs(zoneIndex - ownerIndex) * 45.0) + droneOffset
                    }
                    zone.fixture.ownershipPeerCoordinator.observeRemoteId(
                        remoteId,
                        distanceMeters,
                        firstSeenTs + zoneIndex
                    )
                }
            }
        }

        remoteIds.forEachIndexed { index, remoteId ->
            val expectedOwner = zones[index / 4].guid
            assertSingleAgreedOwner(zones, remoteId, expectedOwner)
            assertEquals(1, publishTrackPointFromAllZones(zones, remoteId, 39.0 + index, -121.0 - index))
            assertEquals(1, publishTrackerFromAllZones(zones, remoteId))
        }

        zones.forEach { zone ->
            assertEquals(4, zone.fixture.calTopoSessionGateway.countOperations("startLiveTrack"))
            assertEquals(4, zone.fixture.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
            assertEquals(4, zone.fixture.trackerPublisher.countPublications())
        }
    }

    @Test
    fun ownershipHandoffMovesPublicationToCloserZoneWithoutDuplicateWrites() {
        val zones = startZones(
            mapId = "handoff-map",
            rttsMs = listOf(300L, 300L, 300L)
        )
        val alpha = zones[0]
        val bravo = zones[1]
        val charlie = zones[2]
        val remoteId = "RID-HANDOFF-1"

        alpha.fixture.ownershipPeerCoordinator.observeRemoteId(remoteId, 25.0, 100L)
        bravo.fixture.ownershipPeerCoordinator.observeRemoteId(remoteId, 250.0, 100L)
        charlie.fixture.ownershipPeerCoordinator.observeRemoteId(remoteId, 400.0, 100L)

        assertSingleAgreedOwner(zones, remoteId, alpha.guid)
        assertEquals(1, publishTrackPointFromAllZones(zones, remoteId, 39.0, -121.0))

        bravo.fixture.ownershipPeerCoordinator.observeRemoteId(remoteId, 10.0, 200L)

        assertSingleAgreedOwner(zones, remoteId, bravo.guid)
        assertEquals(1, publishTrackPointFromAllZones(zones, remoteId, 39.001, -121.001))
        assertEquals(1, publishTrackerFromAllZones(zones, remoteId))

        assertEquals(1, alpha.fixture.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
        assertEquals(1, bravo.fixture.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
        assertEquals(0, charlie.fixture.calTopoSessionGateway.countOperations("addLiveTrackPoint"))
        assertEquals(0, alpha.fixture.trackerPublisher.countPublications())
        assertEquals(1, bravo.fixture.trackerPublisher.countPublications())
        assertEquals(0, charlie.fixture.trackerPublisher.countPublications())
    }

    @Test
    fun sixZoneTieResolvesByEarliestFirstSeenThenGuid() {
        val byFirstSeen = startZones(
            mapId = "tie-first-seen",
            rttsMs = listOf(250L, 250L, 250L, 250L, 250L, 250L)
        )
        val remoteId = "RID-TIE-1"
        byFirstSeen.forEachIndexed { index, zone ->
            zone.fixture.ownershipPeerCoordinator.observeRemoteId(
                remoteId,
                100.0,
                100L + index
            )
        }
        assertSingleAgreedOwner(byFirstSeen, remoteId, byFirstSeen.first().guid)

        val byGuid = startZones(
            mapId = "tie-guid",
            rttsMs = listOf(250L, 250L, 250L, 250L, 250L, 250L),
            guids = listOf("zone-f", "zone-d", "zone-b", "zone-e", "zone-c", "zone-a")
        )
        val guidTieRemoteId = "RID-TIE-2"
        byGuid.forEach { zone ->
            zone.fixture.ownershipPeerCoordinator.observeRemoteId(
                guidTieRemoteId,
                100.0,
                500L
            )
        }
        assertSingleAgreedOwner(byGuid, guidTieRemoteId, "zone-a")
    }

    private fun startZones(
        mapId: String,
        rttsMs: List<Long>,
        guids: List<String> = List(rttsMs.size) { index -> "zone-${'A' + index}".lowercase() }
    ): List<ZoneFixture> {
        val hub = OwnershipTestPeerHub()
        return guids.mapIndexed { index, guid ->
            val fixture = TestR2cRuntimeFactory.createOwnershipFixture("$mapId-runtime-$guid", hub)
            fixture.register()
            fixture.ownershipPeerCoordinator.start(mapId, guid, guid.uppercase(), null)
            fixture.ownershipPeerCoordinator.updateCaltopoRtt(rttsMs[index])
            ZoneFixture(guid, fixture)
        }
    }

    private fun assertSingleAgreedOwner(
        zones: List<ZoneFixture>,
        remoteId: String,
        expectedOwnerGuid: String
    ) {
        val owners = zones.map { it.fixture.ownershipPeerCoordinator.getOwnerGuid(remoteId) }
        assertTrue("every zone should agree on an owner for $remoteId: $owners", owners.all { it == expectedOwnerGuid })
        assertEquals(1, zones.count { it.fixture.ownershipPeerCoordinator.isLocalOwner(remoteId) })
    }

    private fun publishTrackPointFromAllZones(
        zones: List<ZoneFixture>,
        remoteId: String,
        lat: Double,
        lng: Double
    ): Int {
        return zones.count { zone ->
            zone.fixture.ownershipPeerCoordinator.publishTrackPointIfOwner(remoteId, lat, lng, 30.0)
        }
    }

    private fun publishTrackerFromAllZones(zones: List<ZoneFixture>, remoteId: String): Int {
        val geoJson = """{"type":"FeatureCollection","features":[]}"""
        return zones.count { zone ->
            zone.fixture.ownershipPeerCoordinator.publishTrackerIfOwner(remoteId, geoJson)
        }
    }
}

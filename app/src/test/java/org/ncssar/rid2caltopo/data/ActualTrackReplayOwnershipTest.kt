package org.ncssar.rid2caltopo.data

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class ActualTrackReplayOwnershipTest {

    private data class ZoneFixture(
        val guid: String,
        val lat: Double,
        val lng: Double,
        val fixture: TestR2cRuntimeFactory.OwnershipFixture
    )

    private data class TrackPoint(
        val tMs: Long,
        val lat: Double,
        val lng: Double,
        val altM: Double
    )

    private data class LoadedTrack(
        val remoteId: String,
        val mappedId: String,
        val owner: String,
        val model: String,
        val org: String,
        val points: List<TrackPoint>
    )

    private data class ReplayEvent(
        val remoteId: String,
        val point: TrackPoint,
        val firstSeenTs: Long
    )

    @After
    fun tearDown() {
        R2cRuntimeRegistry.resetDefaultRuntimeForTesting()
    }

    @Test
    fun copiedGeoJsonTracks_replayAcrossZones_withoutDuplicatePublication() {
        val avata = loadTrack("1SAR7a360_124825Apr22.json")
        val mavic = loadTrack("1SAR7Mvc3Pr_175847Apr22.json")
        val tracks = listOf(avata, mavic)
        val zones = startZones(
            mapId = "actual-track-map",
            zoneSeeds = listOf(
                Triple("zone-home", 39.153091, -121.132828),
                Triple("zone-south", 39.153873, -121.130608),
                Triple("zone-north", 39.154579, -121.131653),
                Triple("zone-west", 39.153515, -121.133049)
            )
        )
        val events = tracks.flatMap { track ->
            val firstSeenTs = track.points.minOf { it.tMs }
            track.points.map { point -> ReplayEvent(track.remoteId, point, firstSeenTs) }
        }.sortedBy { it.point.tMs }

        val ownerHistoryByRemoteId = linkedMapOf<String, MutableSet<String>>()
        events.forEach { event ->
            zones.forEach { zone ->
                zone.fixture.ownershipPeerCoordinator.observeRemoteId(
                    event.remoteId,
                    haversineMeters(zone.lat, zone.lng, event.point.lat, event.point.lng),
                    event.firstSeenTs
                )
            }

            val owners = zones.mapNotNull { it.fixture.ownershipPeerCoordinator.getOwnerGuid(event.remoteId) }.toSet()
            assertEquals("expected exactly one owner for ${event.remoteId} at ${event.point.tMs}", 1, owners.size)
            ownerHistoryByRemoteId.getOrPut(event.remoteId) { linkedSetOf() }.add(owners.first())

            val publishCount = zones.count { zone ->
                zone.fixture.ownershipPeerCoordinator.publishTrackPointIfOwner(
                    event.remoteId,
                    event.point.lat,
                    event.point.lng,
                    event.point.altM
                )
            }
            assertEquals("expected exactly one CalTopo publisher for ${event.remoteId}", 1, publishCount)
        }

        val totalLiveTrackStarts = zones.sumOf { it.fixture.calTopoSessionGateway.countOperations("startLiveTrack") }
        val totalLiveTrackPoints = zones.sumOf { it.fixture.calTopoSessionGateway.countOperations("addLiveTrackPoint") }
        val maxExpectedStarts = ownerHistoryByRemoteId.values.sumOf { it.size }

        assertTrue(
            "expected at least one live-track start per copied track",
            totalLiveTrackStarts >= tracks.size
        )
        assertTrue(
            "live-track starts should be bounded by distinct owner zones per track: starts=$totalLiveTrackStarts owners=$ownerHistoryByRemoteId",
            totalLiveTrackStarts <= maxExpectedStarts
        )
        assertEquals(events.size, totalLiveTrackPoints)
        assertTrue(
            "expected at least two different zones to own the copied tracks over time: $ownerHistoryByRemoteId",
            ownerHistoryByRemoteId.values.flatten().toSet().size >= 2
        )

        tracks.forEach { track ->
            assertTrue(
                "expected replayed track ${track.remoteId} to produce published points",
                zones.any { zone -> zone.fixture.calTopoSessionGateway.snapshotOperations().any { it.kind == "addLiveTrackPoint" && it.summary == track.remoteId } }
            )
        }
    }

    private fun startZones(
        mapId: String,
        zoneSeeds: List<Triple<String, Double, Double>>
    ): List<ZoneFixture> {
        val hub = OwnershipTestPeerHub()
        return zoneSeeds.map { (guid, lat, lng) ->
            val fixture = TestR2cRuntimeFactory.createOwnershipFixture("$mapId-$guid", hub)
            fixture.register()
            fixture.ownershipPeerCoordinator.start(mapId, guid, guid.uppercase(), null)
            fixture.ownershipPeerCoordinator.updateCaltopoRtt(200L)
            ZoneFixture(guid, lat, lng, fixture)
        }
    }

    private fun loadTrack(fileName: String): LoadedTrack {
        val resourcePath = "/org/ncssar/rid2caltopo/testing/$fileName"
        val jsonText = checkNotNull(javaClass.getResource(resourcePath)) {
            "Could not locate copied test asset '$resourcePath' on the test classpath."
        }.readText()
        val root = JsonParser.parseString(jsonText).asJsonObject
        val feature = root.getAsJsonArray("features")[0].asJsonObject
        val properties = feature.getAsJsonObject("properties")
        val r2cProp = properties.getAsJsonObject("r2c_prop")
        val coordinates = feature.getAsJsonObject("geometry").getAsJsonArray("coordinates")
        val points = ArrayList<TrackPoint>(coordinates.size())
        for (i in 0 until coordinates.size()) {
            val coord = coordinates[i].asJsonArray
            points += TrackPoint(
                tMs = coord[3].asString.toLong(),
                lat = coord[1].asString.toDouble(),
                lng = coord[0].asString.toDouble(),
                altM = coord[2].asString.toDouble()
            )
        }
        return LoadedTrack(
            remoteId = r2cProp.get("rid").asString,
            mappedId = r2cProp.get("mid")?.asString.orEmpty(),
            owner = r2cProp.get("owner")?.asString.orEmpty(),
            model = r2cProp.get("model")?.asString.orEmpty(),
            org = r2cProp.get("org")?.asString.orEmpty(),
            points = points.sortedBy(TrackPoint::tMs)
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latRad1 = lat1 * PI / 180.0
        val latRad2 = lat2 * PI / 180.0
        val deltaLat = (lat2 - lat1) * PI / 180.0
        val deltaLon = (lon2 - lon1) * PI / 180.0
        val a = sin(deltaLat / 2.0).pow(2.0) +
            cos(latRad1) * cos(latRad2) * sin(deltaLon / 2.0).pow(2.0)
        return 2.0 * 6_371_000.0 * asin(sqrt(a))
    }
}

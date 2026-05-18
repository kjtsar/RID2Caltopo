package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RidReplayScenarioParserTest {

    @Test
    fun parseScenario_readsDroneMetadataAndPoints() {
        val scenario = RidReplayScenarioParser.parse(
            """
            {
              "type": "rid_replay_scenario",
              "file_version": "1.0",
              "scenario_name": "Narita Test",
              "time_mode": "relative_ms",
              "speed_multiplier": 4.0,
              "drones": [
                {
                  "remote_id": "RID-ALPHA",
                  "mapped_id": "1SAR7DjMn4Pr",
                  "org": "NCSSAR",
                  "model": "DJI Mini 4 Pro",
                  "owner": "Ken Taylor",
                  "transport": "wifi",
                  "airborne": true,
                  "points": [
                    {
                      "t_ms": 1000,
                      "lat": 35.771,
                      "lng": 140.392,
                      "alt_m": 25.0,
                      "gs_knots": 18.5,
                      "track_deg": 92.0,
                      "altitude_rate_fpm": 140.0
                    },
                    {
                      "t_ms": 0,
                      "lat": 35.770,
                      "lng": 140.391,
                      "alt_m": 20.0
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("Narita Test", scenario.scenarioName)
        assertEquals(RidReplayScenario.TimeMode.RELATIVE_MS, scenario.timeMode)
        assertEquals(4.0, scenario.speedMultiplier, 0.0)
        assertEquals(1, scenario.drones.size)

        val drone = scenario.drones.first()
        assertEquals("RID-ALPHA", drone.remoteId)
        assertEquals("1SAR7DjMn4Pr", drone.mappedId)
        assertEquals("NCSSAR", drone.org)
        assertEquals("DJI Mini 4 Pro", drone.model)
        assertEquals("Ken Taylor", drone.owner)
        assertEquals(CtDroneSpec.TransportTypeEnum.WIFI, drone.transport)
        assertEquals(true, drone.airborne)
        assertEquals(2, drone.points.size)
        assertEquals(0L, drone.points[0].tMs)
        assertEquals(1000L, drone.points[1].tMs)
        assertEquals(18.5, drone.points[1].gsKnots!!, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun parseScenario_rejectsWrongType() {
        RidReplayScenarioParser.parse(
            """
            {
              "type": "ct_credentials",
              "drones": []
            }
            """.trimIndent()
        )
    }

    @Test
    fun parseScenario_defaultsUnknownTransportAndOptionalFields() {
        val scenario = RidReplayScenarioParser.parse(
            """
            {
              "type": "rid_replay_scenario",
              "drones": [
                {
                  "remote_id": "RID-BRAVO",
                  "transport": "bogus",
                  "points": [
                    { "t_ms": 0, "lat": 1.0, "lng": 2.0, "alt_m": 3.0 }
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val drone = scenario.drones.first()
        assertEquals(CtDroneSpec.TransportTypeEnum.WIFI, drone.transport)
        assertNull(drone.points.first().gsKnots)
        assertNull(drone.points.first().trackDeg)
        assertNull(drone.points.first().altitudeRateFpm)
    }

    @Test
    fun parseScenario_readsArchivedGeoJsonTrack() {
        val scenario = RidReplayScenarioParser.parse(
            """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "properties": {
                    "title": "1SAR7A360-120000",
                    "r2c_prop": {
                      "rid": "RID-ARCHIVE",
                      "mid": "1SAR7A360",
                      "org": "NCSSAR",
                      "model": "Avata 2",
                      "owner": "Pilot"
                    }
                  },
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [
                      [-121.01, 39.01, 100.0, 1750000001000],
                      [-121.02, 39.02, 110.0, 1750000000000]
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("Archived RID Replay", scenario.scenarioName)
        assertEquals(RidReplayScenario.TimeMode.ABSOLUTE_EPOCH_MS, scenario.timeMode)
        assertEquals(1.0, scenario.speedMultiplier, 0.0)
        assertEquals(1, scenario.drones.size)

        val drone = scenario.drones.first()
        assertEquals("RID-ARCHIVE", drone.remoteId)
        assertEquals("1SAR7A360", drone.mappedId)
        assertEquals("NCSSAR", drone.org)
        assertEquals("Avata 2", drone.model)
        assertEquals("Pilot", drone.owner)
        assertEquals(CtDroneSpec.TransportTypeEnum.R2C, drone.transport)
        assertEquals(true, drone.airborne)
        assertEquals(2, drone.points.size)
        assertEquals(1750000000000L, drone.points[0].tMs)
        assertEquals(39.02, drone.points[0].lat, 0.0)
        assertEquals(-121.02, drone.points[0].lng, 0.0)
        assertEquals(110.0, drone.points[0].altM, 0.0)
    }
}

package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaaUasFacilityMapParserTest {
    @Test
    fun parsesFallonClassDLaancFacilityMapFeature() {
        val records = FaaUasFacilityMapParser.parse(
            """
            {
              "features": [
                {
                  "attributes": {
                    "OBJECTID": 90600,
                    "CEILING": 400,
                    "UNIT": "Feet",
                    "APT1_FAAID": "NFL",
                    "APT1_ICAO": "KNFL",
                    "APT1_NAME": "Fallon NAS (Van Voorhis Fld)",
                    "APT1_LAANC": 1,
                    "AIRSPACE_1": "D",
                    "AIRSPACE_2": "",
                    "AIRSPACE_3": "",
                    "AIRSPACE_4": "",
                    "AIRSPACE_5": ""
                  },
                  "geometry": {
                    "rings": [[[-119.0,39.4],[-119.0,39.5],[-118.9,39.5],[-119.0,39.4]]]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(90600L, record.objectId)
        assertEquals(400, record.ceilingFeet)
        assertEquals("Fallon NAS (Van Voorhis Fld)", record.primaryAirportName)
        assertEquals("NFL", record.primaryAirportFaaId)
        assertEquals("KNFL", record.primaryAirportIcao)
        assertTrue(record.laancAvailable)
        assertEquals(listOf("D"), record.airspaceClasses)
        assertEquals(1, record.rings.size)
        assertEquals(AirspaceCoordinate(39.4, -119.0), record.rings.single().first())
    }
}

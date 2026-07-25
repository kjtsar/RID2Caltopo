package org.ncssar.rid2caltopo.airspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AirspaceRepositoryTest {
    @Test
    fun buildsFaaFacilityMapOperatingAreaQueryUrl() {
        val url = AirspaceRepository.buildFacilityMapQueryUrl(
            latitude = 39.47816,
            longitude = -118.78456
        ).toString()

        assertTrue(url.startsWith("https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/FAA_UAS_FacilityMap_Data/FeatureServer/0/query?"))
        assertTrue(url.contains("f=json"))
        assertTrue(url.contains("geometry=-118.784560%2C39.478160"))
        assertTrue(url.contains("geometryType=esriGeometryPoint"))
        assertTrue(url.contains("distance=1.000000"))
        assertTrue(url.contains("units=esriSRUnit_StatuteMile"))
        assertTrue(url.contains("spatialRel=esriSpatialRelIntersects"))
        assertTrue(url.contains("returnGeometry=true"))
        assertTrue(url.contains("outSR=4326"))
        assertTrue(url.contains("outFields=OBJECTID%2CCEILING%2CUNIT%2CAPT1_FAAID%2CAPT1_ICAO%2CAPT1_NAME%2CAPT1_LAANC%2CAIRSPACE_1%2CAIRSPACE_2%2CAIRSPACE_3%2CAIRSPACE_4%2CAIRSPACE_5"))
        assertEquals(0.868976, AirspaceRepository.OPERATING_RADIUS_NM, 0.000001)
    }
}

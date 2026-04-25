import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlin.math.cos

class ClueProjectionTest {
    @Test
    fun projectClueLocation_keepsPointUnderDroneAtMinusNinetyDegrees() {
        val projection = projectClueLocation(
            droneLat = 37.0,
            droneLng = -122.0,
            droneAlt = 150.0,
            headingDeg = 180.0,
            aglMeters = 30.0,
            gimbalAngleDeg = -90.0,
        )

        assertEquals(37.0, projection.lat, 1e-9)
        assertEquals(-122.0, projection.lng, 1e-9)
        assertEquals(120.0, projection.alt, 1e-9)
    }

    @Test
    fun projectClueLocation_projectsForwardUsingHeadingAndAgl() {
        val droneLat = 37.0
        val droneLng = -122.0
        val projection = projectClueLocation(
            droneLat = droneLat,
            droneLng = droneLng,
            droneAlt = 150.0,
            headingDeg = 0.0,
            aglMeters = 40.0,
            gimbalAngleDeg = -45.0,
        )

        val latMeters = (projection.lat - droneLat) * 111_320.0
        val lngMeters = (projection.lng - droneLng) * 111_320.0 * cos(Math.toRadians(droneLat))

        assertTrue("expected northward projection", projection.lat > droneLat)
        assertEquals(40.0, latMeters, 2.0)
        assertEquals(0.0, lngMeters, 1.0)
        assertEquals(110.0, projection.alt, 1e-9)
    }

    @Test
    fun projectClueLocation_fallsBackToDroneLocationWithoutProjectionInputs() {
        val projection = projectClueLocation(
            droneLat = 37.0,
            droneLng = -122.0,
            droneAlt = 150.0,
            headingDeg = null,
            aglMeters = 40.0,
            gimbalAngleDeg = -30.0,
        )

        assertEquals(37.0, projection.lat, 1e-9)
        assertEquals(-122.0, projection.lng, 1e-9)
        assertEquals(110.0, projection.alt, 1e-9)
    }

    @Test
    fun inferDemScaleToMeters_prefersFootScaleWhenRawDemMatchesFeet() {
        val scale = inferDemScaleToMeters(
            droneAltMeters = 548.0,
            knownGroundMeters = 529.2,
            droneDemRaw = 1563.3,
        )

        assertEquals(0.3048, scale, 1e-9)
    }

    @Test
    fun projectClueLocationWithDemSamples_usesRelativeTerrainWhenDemLooksFootBased() = runBlocking {
        val projection = projectClueLocationWithDemSamples(
            droneLat = 39.153294,
            droneLng = -121.132378,
            droneAlt = 548.0,
            headingDeg = 244.0,
            aglMeters = 18.8,
            gimbalAngleDeg = -31.0,
            sampleElevationMeters = { lat, lng ->
                if (kotlin.math.abs(lat - 39.153294) < 1e-6 && kotlin.math.abs(lng + 121.132378) < 1e-6) {
                    1563.3
                } else {
                    1545.0
                }
            },
        )

        assertTrue("expected DEM-refined projection to stay ahead of the drone", projection.lng < -121.1325)
        assertTrue("expected downhill terrain to lower projected ground elevation", projection.alt < 529.2)
    }
}

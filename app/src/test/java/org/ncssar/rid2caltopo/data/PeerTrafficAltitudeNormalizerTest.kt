package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerTrafficAltitudeNormalizerTest {
    @Test
    fun correctionAnchorsReportedGroundAltitudeToDemMsl() {
        val correction = PeerTrafficAltitudeNormalizer.correctionMeters(
            demMslMeters = 1_845.25,
            reportedGroundAltitudeMeters = 112.75,
        )

        assertEquals(1_732.5, correction, 0.000_001)
        assertEquals(
            1_875.25,
            PeerTrafficAltitudeNormalizer.normalizedMslMeters(142.75, correction),
            0.000_001,
        )
    }

    @Test
    fun relativeUpUsesTheSameReportedFlightDatumBeforeCorrection() {
        assertEquals(
            142.75,
            PeerTrafficAltitudeNormalizer.reportedAltitudeForRelativeUp(112.75, 30.0)!!,
            0.000_001,
        )
        assertEquals(
            null,
            PeerTrafficAltitudeNormalizer.reportedAltitudeForRelativeUp(null, 30.0),
        )
    }
}

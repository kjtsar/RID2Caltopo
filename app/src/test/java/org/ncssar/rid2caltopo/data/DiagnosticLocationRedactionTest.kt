package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLocationRedactionTest {
    @Test
    fun coordinateBearingMessagesAndRawTelemetryPayloadsAreRedacted() {
        val messages = listOf(
            "Published fused location lat=39.153017 lng=-121.133046 accuracy=7",
            "request?latitude=39.153017&longitude=-121.133046&radius=2",
            "geometry={\"type\":\"Point\",\"coordinates\":[-121.72,38.21]}",
            "airport center=37.104722,-116.761111 radiusNm=103",
            "tile source=Imagery z=14 x=2689 y=6226",
            "tile url=https://example.test/tile/14/6226/2689.jpg",
            "DEM USGS_1_n40w122.tif tieXY=-122.001667,40.001667",
            "DJI_SEI_PAYLOAD len=32 payload=00112233",
        )

        messages.forEach { message ->
            assertEquals(
                "[location details redacted]",
                CaltopoClient.RedactLocationFromDiagnosticMessage(message),
            )
        }
        assertEquals(
            "Location authorization changed; coordinates unavailable",
            CaltopoClient.RedactLocationFromDiagnosticMessage(
                "Location authorization changed; coordinates unavailable",
            ),
        )
    }
}

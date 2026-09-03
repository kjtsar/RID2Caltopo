package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamDesignatorClosestMatchTest {
    @Test
    fun missingManufacturerTokenHighlightsConfiguredDroneDesignator() {
        val closest = closestStreamTelemetryRemoteId(
            streamDesignator = "1sar1001mtrc4td",
            candidateTelemetry = listOf(
                StreamTelemetryState("RID-01", "1sar1001djmtrc4td"),
                StreamTelemetryState("RID-02", "1sar1002djmtrc4td"),
                StreamTelemetryState("RID-03", "1sar7djmn4pr")
            )
        )

        assertEquals("RID-01", closest)
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrimsOuterWhitespace() {
        val closest = closestStreamTelemetryRemoteId(
            streamDesignator = "  1SAR1001MTRC4TD ",
            candidateTelemetry = listOf(
                StreamTelemetryState("RID-01", "1sar1001mtrc4td"),
                StreamTelemetryState("RID-02", "1sar1002mtrc4td")
            )
        )

        assertEquals("RID-01", closest)
    }

    @Test
    fun equallyCloseDesignatorsDoNotCreateArbitrarySuggestion() {
        val closest = closestStreamTelemetryRemoteId(
            streamDesignator = "1sar1001djmtrc4td",
            candidateTelemetry = listOf(
                StreamTelemetryState("RID-01", "1sar1002djmtrc4td"),
                StreamTelemetryState("RID-02", "1sar1003djmtrc4td")
            )
        )

        assertNull(closest)
    }
}

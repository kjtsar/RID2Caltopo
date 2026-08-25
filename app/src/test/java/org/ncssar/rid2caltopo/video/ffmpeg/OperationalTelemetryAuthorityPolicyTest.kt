package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalTelemetryAuthorityPolicyTest {
    @Test
    fun packetProbeIsTheOnlyOperationalTelemetryAuthority() {
        assertTrue(OperationalTelemetryAuthorityPolicy.accepts(
            sessionId = 5L,
            telemetryProbeSessionId = 5L,
        ))
        assertFalse(OperationalTelemetryAuthorityPolicy.accepts(
            sessionId = 6L,
            telemetryProbeSessionId = 5L,
        ))
    }

    @Test
    fun rendererIsNotPromotedWhenPacketProbeIsUnavailable() {
        assertFalse(OperationalTelemetryAuthorityPolicy.accepts(
            sessionId = 6L,
            telemetryProbeSessionId = null,
        ))
    }
}

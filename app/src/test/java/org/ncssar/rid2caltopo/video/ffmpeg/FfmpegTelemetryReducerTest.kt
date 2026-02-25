package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FfmpegTelemetryReducerTest {
    @Test
    fun merge_prefersIncomingWhenPresent_andKeepsExistingFieldsOtherwise() {
        val existing = FfmpegTelemetry(
            sourceTag = "mavlink",
            confidence = 0.4,
            remoteId = "RID-1",
            latitude = 47.0,
            longitude = -122.0,
            altitudeMeters = 120.0,
        )
        val incoming = FfmpegTelemetry(
            confidence = 0.9,
            remoteId = null,
            latitude = null,
            headingDeg = 181.0,
        )

        val result = FfmpegTelemetryReducer.merge(existing, incoming, setOf("RID-1"))

        assertEquals("mavlink", result.mergedTelemetry.sourceTag)
        assertEquals(0.9, result.mergedTelemetry.confidence)
        assertEquals("RID-1", result.mergedTelemetry.remoteId)
        assertEquals(47.0, result.mergedTelemetry.latitude)
        assertEquals(181.0, result.mergedTelemetry.headingDeg)
        assertNull(result.addedRemoteId)
    }

    @Test
    fun merge_addsNewRemoteIdCandidateInOrder() {
        val result = FfmpegTelemetryReducer.merge(
            existing = null,
            incoming = FfmpegTelemetry(remoteId = "RID-2"),
            existingCandidates = linkedSetOf("RID-1"),
        )

        assertEquals(listOf("RID-1", "RID-2"), result.updatedCandidates.toList())
        assertEquals("RID-2", result.addedRemoteId)
    }

    @Test
    fun merge_duplicateRemoteIdDoesNotReAddCandidate() {
        val result = FfmpegTelemetryReducer.merge(
            existing = null,
            incoming = FfmpegTelemetry(remoteId = "RID-1"),
            existingCandidates = linkedSetOf("RID-1"),
        )

        assertEquals(listOf("RID-1"), result.updatedCandidates.toList())
        assertNull(result.addedRemoteId)
    }
}

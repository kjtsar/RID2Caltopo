package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.time.Instant

class ManagedVideoRecordingAssociationTest {
    @Test
    fun selectsRecordingThatOverlapsExactTrackInsteadOfLatestForDesignator() {
        val first = recording("first", startMs = 1_000L, endMs = 11_000L)
        val second = recording("second", startMs = 101_000L, endMs = 111_000L)

        assertEquals(
            first,
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(second, first),
                trackStartedAtMs = 2_000L,
                trackEndedAtMs = 10_000L,
                candidates = listOf("1SAR7"),
            ),
        )
        assertEquals(
            second,
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(second, first),
                trackStartedAtMs = 102_000L,
                trackEndedAtMs = 110_000L,
                candidates = listOf("1sar7"),
            ),
        )
    }

    @Test
    fun doesNotReuseStaleRecordingForLaterTrack() {
        val stale = recording("stale", startMs = 1_000L, endMs = 11_000L)

        assertNull(
            ManagedVideoSessionRecordingCatalog.selectForTrack(
                recordings = listOf(stale),
                trackStartedAtMs = 101_000L,
                trackEndedAtMs = 111_000L,
                candidates = listOf("1SAR7"),
            ),
        )
    }

    private fun recording(
        sessionID: String,
        startMs: Long,
        endMs: Long,
    ) = ManagedVideoSessionRecording(
        sessionId = sessionID,
        droneDesignator = "1SAR7",
        file = File("/$sessionID.mp4"),
        recordedAt = Instant.ofEpochMilli(endMs),
        durationMs = endMs - startMs,
        width = 1920,
        height = 1080,
        fps = 30.0,
        codec = "h264",
    )
}

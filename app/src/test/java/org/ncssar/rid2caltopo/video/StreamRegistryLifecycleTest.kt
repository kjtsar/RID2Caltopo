package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRegistryLifecycleTest {
    @Test
    fun staleConnectingEntries_areEvictedToPreventCapacityLockout() {
        val initial = StreamAdmissionState(
            active = mapOf(
                "a" to StreamInfo("a", StreamState.CONNECTING),
                "b" to StreamInfo("b", StreamState.CONNECTING),
                "c" to StreamInfo("c", StreamState.CONNECTING),
                "d" to StreamInfo("d", StreamState.CONNECTING),
            ),
            stateChangedAtMs = mapOf("a" to 0L, "b" to 0L, "c" to 0L, "d" to 0L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.admit(
            state = initial,
            path = "e",
            targetState = StreamState.CONNECTING,
            nowMs = 31_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        )

        assertTrue(result.admitted)
        assertEquals(setOf("a", "b", "c", "d"), result.evictedPaths)
        assertEquals(setOf("e"), result.state.active.keys)
    }

    @Test
    fun orphanErrorTransition_doesNotCreateStreamEntry() {
        val initial = StreamAdmissionState(
            active = emptyMap(),
            stateChangedAtMs = emptyMap(),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.markError(initial, path = "ghost", nowMs = 100L)

        assertFalse(result.changed)
        assertTrue(result.state.active.isEmpty())
    }

    @Test
    fun lifecycleTransitions_connectLiveStop() {
        val emptyState = StreamAdmissionState(
            active = emptyMap(),
            stateChangedAtMs = emptyMap(),
            rejectedPaths = emptySet(),
        )
        val connecting = StreamAdmissionPolicy.admit(
            state = emptyState,
            path = "d1",
            targetState = StreamState.CONNECTING,
            nowMs = 1_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        ).state
        assertEquals(StreamState.CONNECTING, connecting.active["d1"]?.state)

        val live = StreamAdmissionPolicy.admit(
            state = connecting,
            path = "d1",
            targetState = StreamState.LIVE,
            nowMs = 2_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        ).state
        assertEquals(StreamState.LIVE, live.active["d1"]?.state)

        val stopped = StreamAdmissionPolicy.markStopped(live, "d1").state
        assertFalse(stopped.active.containsKey("d1"))
    }

    @Test
    fun markError_recordsErrorDetail() {
        val initial = StreamAdmissionState(
            active = mapOf("d1" to StreamInfo("d1", StreamState.LIVE)),
            stateChangedAtMs = mapOf("d1" to 1_000L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.markError(
            state = initial,
            path = "d1",
            nowMs = 2_000L,
            reason = "RTMP closed: received an audio packet",
        )

        assertTrue(result.changed)
        assertEquals(StreamState.ERROR, result.state.active["d1"]?.state)
        assertEquals(
            "RTMP closed: received an audio packet",
            result.state.active["d1"]?.errorDetail,
        )
    }
}

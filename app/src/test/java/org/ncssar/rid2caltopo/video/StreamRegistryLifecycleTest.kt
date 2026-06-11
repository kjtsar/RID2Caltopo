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
                "a" to StreamInfo(designator = "a", state = StreamState.CONNECTING),
                "b" to StreamInfo(designator = "b", state = StreamState.CONNECTING),
                "c" to StreamInfo(designator = "c", state = StreamState.CONNECTING),
                "d" to StreamInfo(designator = "d", state = StreamState.CONNECTING),
            ),
            stateChangedAtMs = mapOf("a" to 0L, "b" to 0L, "c" to 0L, "d" to 0L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.admit(
            state = initial,
            designator = "e",
            sourcePath = "e",
            controllerProfile = StreamControllerProfile.GENERIC,
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
            designator = "d1",
            sourcePath = "d1",
            controllerProfile = StreamControllerProfile.GENERIC,
            targetState = StreamState.CONNECTING,
            nowMs = 1_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        ).state
        assertEquals(StreamState.CONNECTING, connecting.active["d1"]?.state)
        assertEquals(1L, connecting.active["d1"]?.revision)

        val live = StreamAdmissionPolicy.admit(
            state = connecting,
            designator = "d1",
            sourcePath = "d1",
            controllerProfile = StreamControllerProfile.GENERIC,
            targetState = StreamState.LIVE,
            nowMs = 2_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        ).state
        assertEquals(StreamState.LIVE, live.active["d1"]?.state)
        assertEquals(2L, live.active["d1"]?.revision)

        val republished = StreamAdmissionPolicy.admit(
            state = live,
            designator = "d1",
            sourcePath = "d1",
            controllerProfile = StreamControllerProfile.GENERIC,
            targetState = StreamState.LIVE,
            nowMs = 3_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        ).state
        assertEquals(StreamState.LIVE, republished.active["d1"]?.state)
        assertEquals(3L, republished.active["d1"]?.revision)

        val stopped = StreamAdmissionPolicy.markStopped(republished, "d1").state
        assertFalse(stopped.active.containsKey("d1"))
    }

    @Test
    fun markError_recordsErrorDetail() {
        val initial = StreamAdmissionState(
            active = mapOf("d1" to StreamInfo(designator = "d1", state = StreamState.LIVE)),
            stateChangedAtMs = mapOf("d1" to 1_000L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.markError(
            state = initial,
            path = "d1",
            nowMs = 2_000L,
            reason = "decoder failed to start",
        )

        assertTrue(result.changed)
        assertEquals(StreamState.ERROR, result.state.active["d1"]?.state)
        assertEquals(
            "decoder failed to start",
            result.state.active["d1"]?.errorDetail,
        )
    }

    @Test
    fun transientRtmpClose_isDeferredWhileStreamIsStillActive() {
        val initial = StreamAdmissionState(
            active = mapOf("d1" to StreamInfo(designator = "d1", state = StreamState.LIVE)),
            stateChangedAtMs = mapOf("d1" to 1_000L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.markError(
            state = initial,
            path = "d1",
            nowMs = 2_000L,
            reason = "RTMP closed: publisher disconnected unexpectedly",
        )

        assertFalse(result.changed)
        assertEquals(StreamState.LIVE, result.state.active["d1"]?.state)
    }

    @Test
    fun nonRtmpError_stillTransitionsToError() {
        val initial = StreamAdmissionState(
            active = mapOf("d1" to StreamInfo(designator = "d1", state = StreamState.LIVE)),
            stateChangedAtMs = mapOf("d1" to 1_000L),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.markError(
            state = initial,
            path = "d1",
            nowMs = 2_000L,
            reason = "decoder failed to start",
        )

        assertTrue(result.changed)
        assertEquals(StreamState.ERROR, result.state.active["d1"]?.state)
        assertEquals("decoder failed to start", result.state.active["d1"]?.errorDetail)
    }

    @Test
    fun fifthConcurrentLiveStream_isRejectedAndFourAdmittedStreamsRemain() {
        val initial = StreamAdmissionState(
            active = mapOf(
                "d1" to StreamInfo(designator = "d1", state = StreamState.LIVE),
                "d2" to StreamInfo(designator = "d2", state = StreamState.LIVE),
                "d3" to StreamInfo(designator = "d3", state = StreamState.LIVE),
                "d4" to StreamInfo(designator = "d4", state = StreamState.LIVE),
            ),
            stateChangedAtMs = mapOf(
                "d1" to 1_000L,
                "d2" to 1_000L,
                "d3" to 1_000L,
                "d4" to 1_000L,
            ),
            rejectedPaths = emptySet(),
        )

        val result = StreamAdmissionPolicy.admit(
            state = initial,
            designator = "d5",
            sourcePath = "d5",
            controllerProfile = StreamControllerProfile.GENERIC,
            targetState = StreamState.LIVE,
            nowMs = 2_000L,
            maxSimultaneousStreams = 4,
            staleConnectingMs = 30_000L,
            staleErrorMs = 120_000L,
        )

        assertFalse(result.admitted)
        assertTrue(result.shouldNotifyRejection)
        assertEquals("capacity", result.rejectionReason)
        assertEquals(setOf("d1", "d2", "d3", "d4"), result.state.active.keys)
        assertFalse(result.state.active.containsKey("d5"))
        assertEquals(setOf("d5"), result.state.rejectedPaths)
    }
}

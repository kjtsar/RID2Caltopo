package org.ncssar.rid2caltopo.video.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoFrameRestartPolicyTest {
    @Test
    fun evaluate_doesNotRestartDuringStartupGrace() {
        val decision = NoFrameRestartPolicy.evaluate(
            nowMs = 9_000L,
            liveAtMs = 7_000L,
            lastFrameAtMs = null,
            lastRestartAtMs = null,
            startupGraceMs = 4_000L,
            noFrameTimeoutMs = 2_000L,
            restartCooldownMs = 1_000L,
        )

        assertFalse(decision.shouldRestart)
        assertEquals("startup-grace", decision.reason)
    }

    @Test
    fun evaluate_doesNotRestartWhenFrameIsRecent() {
        val decision = NoFrameRestartPolicy.evaluate(
            nowMs = 20_000L,
            liveAtMs = 10_000L,
            lastFrameAtMs = 19_000L,
            lastRestartAtMs = null,
            startupGraceMs = 4_000L,
            noFrameTimeoutMs = 2_000L,
            restartCooldownMs = 1_000L,
        )

        assertFalse(decision.shouldRestart)
        assertEquals("frame-recent", decision.reason)
    }

    @Test
    fun evaluate_restartsWhenNoFramesAndCooldownExpired() {
        val decision = NoFrameRestartPolicy.evaluate(
            nowMs = 20_000L,
            liveAtMs = 5_000L,
            lastFrameAtMs = 10_000L,
            lastRestartAtMs = 15_000L,
            startupGraceMs = 4_000L,
            noFrameTimeoutMs = 3_000L,
            restartCooldownMs = 4_000L,
        )

        assertTrue(decision.shouldRestart)
        assertEquals("restart-needed", decision.reason)
    }
}

package org.ncssar.rid2caltopo.video.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRestartBackoffTest {
    @Test
    fun evaluate_rejectsWhenAlreadyRestarting() {
        val decision = StreamRestartBackoff.evaluate(
            isRestarting = true,
            lastRestartAtMs = null,
            nowMs = 1_000L,
            cooldownMs = 800L,
        )

        assertFalse(decision.allow)
        assertEquals("already-restarting", decision.reason)
    }

    @Test
    fun evaluate_rejectsDuringCooldown() {
        val decision = StreamRestartBackoff.evaluate(
            isRestarting = false,
            lastRestartAtMs = 1_000L,
            nowMs = 1_700L,
            cooldownMs = 800L,
        )

        assertFalse(decision.allow)
        assertEquals("cooldown", decision.reason)
    }

    @Test
    fun evaluate_allowsAfterCooldownExpires() {
        val decision = StreamRestartBackoff.evaluate(
            isRestarting = false,
            lastRestartAtMs = 1_000L,
            nowMs = 1_801L,
            cooldownMs = 800L,
        )

        assertTrue(decision.allow)
        assertEquals("cooldown-expired", decision.reason)
    }
}

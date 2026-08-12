package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationIdleTimeoutPolicyTest {
    @Test
    fun disabledTimeoutDoesNotSchedule() {
        assertEquals(
            ApplicationIdleTimeoutPolicy.DISABLED,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, 0L, 0L, 2_000L),
        )
    }

    @Test
    fun timerStartsAtApplicationLaunch() {
        assertEquals(
            7_200_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, 0L, 120L, 1_000L),
        )
    }

    @Test
    fun timerUsesRemainingTimeSinceLatestRidMessage() {
        val lastRidMessageAt = 3_600_000L
        val now = lastRidMessageAt + 1_800_000L

        assertEquals(
            5_400_000L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(1_000L, lastRidMessageAt, 120L, now),
        )
    }

    @Test
    fun timerExpiresAtRidMessageDeadline() {
        val lastRidMessageAt = 10_000L

        assertEquals(
            0L,
            ApplicationIdleTimeoutPolicy.remainingDelayMsec(
                1_000L,
                lastRidMessageAt,
                120L,
                lastRidMessageAt + 7_200_000L,
            ),
        )
    }
}

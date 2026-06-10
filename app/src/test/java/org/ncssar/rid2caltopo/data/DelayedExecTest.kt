package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelayedExecTest {
    @Test
    fun stopReportsOnlyActualRunningTransition() {
        val delayedExec = DelayedExec(false)

        assertFalse(delayedExec.stop())

        delayedExec.start({}, 60_000L, 0L)

        assertTrue(delayedExec.stop())
        assertFalse(delayedExec.stop())
    }
}

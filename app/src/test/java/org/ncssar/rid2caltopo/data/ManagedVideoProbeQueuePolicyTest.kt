package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedVideoProbeQueuePolicyTest {
    @Test
    fun probeBurstUsesShallowBackpressureBound() {
        assertTrue(ManagedVideoProbeQueuePolicy.maySend(0, 0))
        assertTrue(ManagedVideoProbeQueuePolicy.maySend(3, 255L * 1024L))
        assertFalse(ManagedVideoProbeQueuePolicy.maySend(4, 0))
        assertFalse(ManagedVideoProbeQueuePolicy.maySend(0, 256L * 1024L))
    }
}

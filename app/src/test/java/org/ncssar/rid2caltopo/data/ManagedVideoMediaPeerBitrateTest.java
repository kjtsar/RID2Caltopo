package org.ncssar.rid2caltopo.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ManagedVideoMediaPeerBitrateTest {
    @Test
    public void highProfileStartsConservativelyWithSelectedCeiling() {
        assertEquals(100_000, ManagedVideoMediaPeer.minimumBitrateForTarget(3_000_000));
        assertEquals(600_000, ManagedVideoMediaPeer.startupBitrateForTarget(3_000_000));
    }

    @Test
    public void emergencyProfileNeverStartsAboveItsTarget() {
        assertEquals(100_000, ManagedVideoMediaPeer.minimumBitrateForTarget(200_000));
        assertEquals(200_000, ManagedVideoMediaPeer.startupBitrateForTarget(200_000));
    }
}

package org.opendroneid.android.bluetooth;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WiFiScannerTest {
    @Test
    public void runWifiAwareSubscribe_handlesSecurityException() {
        boolean subscribed = WiFiScanner.runWifiAwareSubscribeForTesting(() -> {
            throw new SecurityException("missing fine location");
        });

        assertFalse(subscribed);
    }

    @Test
    public void runWifiAwareSubscribe_returnsTrueWhenSubscribeSucceeds() {
        boolean subscribed = WiFiScanner.runWifiAwareSubscribeForTesting(() -> {});

        assertTrue(subscribed);
    }
}

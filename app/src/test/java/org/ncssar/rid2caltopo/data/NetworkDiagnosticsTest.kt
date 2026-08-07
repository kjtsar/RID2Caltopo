package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDiagnosticsTest {
    @Test
    fun unchangedNetworkIsSuppressedUnlessForced() {
        val key = NetworkDiagnostics.transitionKey(
            "SARinternal",
            "abcdef012345",
            listOf("192.168.1.25"),
            true,
            false,
            false,
        )

        assertFalse(NetworkDiagnostics.shouldLogTransition(key, key, false))
        assertTrue(NetworkDiagnostics.shouldLogTransition(key, key, true))
    }

    @Test
    fun ssidChangeCreatesTransition() {
        val guest = NetworkDiagnostics.transitionKey(
            "SARguest", "abcdef012345", listOf("192.168.2.25"), false, false, false,
        )
        val internal = NetworkDiagnostics.transitionKey(
            "SARinternal", "123456abcdef", listOf("192.168.1.25"), true, false, false,
        )

        assertNotEquals(guest, internal)
        assertTrue(NetworkDiagnostics.shouldLogTransition(guest, internal, false))
    }
}

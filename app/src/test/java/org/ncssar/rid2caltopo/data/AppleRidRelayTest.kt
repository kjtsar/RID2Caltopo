package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class AppleRidRelayTest {
    @Test
    fun normalizedPayloadMatchesAppleExternalReceiverContract() {
        val payload = AppleRidRelay.buildPayload(
            AppleRidRelayObservation(
                remoteID = "RID-WIFI-01",
                source = "wifiNan",
                timestampMilliseconds = 1_700_000_000_123,
                latitude = 39.7392,
                longitude = -104.9903,
                altitudeMeters = 1_620.5,
                headingDegrees = 92.0,
                speedMetersPerSecond = 11.5,
                operatorLatitude = 39.74,
                operatorLongitude = -104.99,
                rssiDbm = -61,
            ),
        )
        val json = JSONObject(payload.toString(Charsets.UTF_8))

        assertEquals("RID-WIFI-01", json.getString("aircraft_id"))
        assertEquals("wifiNan", json.getString("source"))
        assertEquals(1_700_000_000_123, json.getLong("timestamp_ms"))
        assertEquals(39.7392, json.getDouble("latitude"), 0.0000001)
        assertEquals(-104.9903, json.getDouble("longitude"), 0.0000001)
        assertEquals(1_620.5, json.getDouble("altitude_m"), 0.001)
        assertEquals(92.0, json.getDouble("heading_deg"), 0.001)
        assertEquals(11.5, json.getDouble("speed_mps"), 0.001)
        assertEquals(-61, json.getInt("rssi_dbm"))
    }

    @Test
    fun optionalFieldsAreOmittedAndRelayRequiresEnabledDestination() {
        val json = JSONObject(
            AppleRidRelay.buildPayload(
                AppleRidRelayObservation(
                    remoteID = "RID-WIFI-02",
                    source = "wifiBeacon",
                    timestampMilliseconds = 123,
                    latitude = 1.0,
                    longitude = 2.0,
                ),
            ).toString(Charsets.UTF_8),
        )

        assertFalse(json.has("altitude_m"))
        assertFalse(json.has("operator_latitude"))
        assertFalse(AppleRidRelayConfig(enabled = false).isReady)
        assertFalse(AppleRidRelayConfig(enabled = true, host = " ").isReady)
        assertTrue(AppleRidRelayConfig(enabled = true, host = "udp://192.168.4.22:7654").isReady)
        assertEquals(
            "192.168.4.22",
            AppleRidRelayConfig(enabled = true, host = "udp://192.168.4.22:7654").normalizedHost,
        )
    }

    @Test
    fun sendsNormalizedDatagramToConfiguredAppleReceiver() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 2_000
            val observation = AppleRidRelayObservation(
                remoteID = "RID-WIFI-UDP",
                source = "wifiBeacon",
                timestampMilliseconds = 456,
                latitude = 39.1,
                longitude = -105.2,
            )

            assertTrue(
                AppleRidRelay.sendNow(
                    AppleRidRelayConfig(
                        enabled = true,
                        host = "127.0.0.1",
                        port = receiver.localPort,
                    ),
                    observation,
                ),
            )

            val buffer = ByteArray(2_048)
            val packet = DatagramPacket(buffer, buffer.size)
            receiver.receive(packet)
            val json = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
            assertEquals("RID-WIFI-UDP", json.getString("aircraft_id"))
            assertEquals("wifiBeacon", json.getString("source"))
            assertEquals(39.1, json.getDouble("latitude"), 0.0000001)
        }
    }
}

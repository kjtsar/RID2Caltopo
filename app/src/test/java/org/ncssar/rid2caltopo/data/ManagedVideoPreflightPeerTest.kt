package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.IceCandidate

class ManagedVideoPreflightPeerTest {
    @Test
    fun offerNormalizationRemovesOptionalMessageSizeAndRestoresCrlfTerminator() {
        val normalized = ManagedVideoSdp.normalizeRemoteOffer(
            "v=0\n" +
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\n" +
                "a=sctp-port:5000\n" +
                "a=max-message-size:262144"
        )

        assertFalse(normalized.contains("a=max-message-size:"))
        assertTrue(normalized.endsWith("\r\n"))
        assertEquals(
            "v=0\r\n" +
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
                "a=sctp-port:5000\r\n",
            normalized
        )
    }

    @Test
    fun gatheredRelayCandidateIsInsertedBeforeEndOfTargetMediaSection() {
        val answer =
            "v=0\r\n" +
                "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n" +
                "a=ice-ufrag:tablet\r\n" +
                "a=end-of-candidates\r\n"
        val candidate = IceCandidate(
            "0",
            0,
            "candidate:1 1 udp 1 192.0.2.1 50000 typ relay raddr 0.0.0.0 rport 0"
        )

        val completed = ManagedVideoSdp.withIceCandidates(answer, listOf(candidate))

        assertTrue(completed.contains("a=candidate:1 1 udp 1 192.0.2.1 50000 typ relay"))
        assertTrue(
            completed.indexOf("a=candidate:") < completed.indexOf("a=end-of-candidates")
        )
        assertTrue(completed.endsWith("\r\n"))
    }

    @Test
    fun privateHostCandidateIsNotSufficientForNonTrickleAnswer() {
        val host = IceCandidate(
            "0",
            0,
            "candidate:1 1 udp 2122260223 192.168.68.70 50000 typ host generation 0"
        )

        assertFalse(ManagedVideoSdp.hasRoutableIceCandidate(listOf(host)))
    }

    @Test
    fun serverReflexiveOrRelayCandidateMakesAnswerRoutable() {
        val srflx = IceCandidate(
            "0",
            0,
            "candidate:2 1 udp 1686052607 198.51.100.20 41000 typ srflx " +
                "raddr 192.168.68.70 rport 50000 generation 0"
        )
        val relay = IceCandidate(
            "0",
            0,
            "candidate:3 1 udp 1677734911 203.0.113.10 52000 typ relay " +
                "raddr 198.51.100.20 rport 41000 generation 0"
        )

        assertTrue(ManagedVideoSdp.hasRoutableIceCandidate(listOf(srflx)))
        assertTrue(ManagedVideoSdp.hasRoutableIceCandidate(listOf(relay)))
    }

    @Test
    fun routableCandidateFoldedIntoLocalSdpIsRecognizedWithoutCallbacks() {
        val completedAnswer =
            "v=0\r\n" +
                "m=video 9 UDP/TLS/RTP/SAVPF 96\r\n" +
                "a=candidate:2 1 udp 1686052607 198.51.100.20 41000 typ srflx " +
                "raddr 192.168.68.70 rport 50000\r\n"

        assertTrue(ManagedVideoSdp.hasRoutableIceCandidate(completedAnswer))
    }
}

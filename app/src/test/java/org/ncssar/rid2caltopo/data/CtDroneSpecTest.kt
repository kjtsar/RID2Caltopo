package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CtDroneSpecTest {
    @Test
    fun guessMakeModel_matchesKnownSerialPrefixes() {
        assertEquals("DJI Mini 4 Pro", CtDroneSpec.GuessMakeModel("1581F6Z9C24BH0036EJL"))
        assertEquals("DJI Mavic 3 Pro", CtDroneSpec.GuessMakeModel("1581F67QE239L00A00DE"))
        assertEquals("DJI Matrice 4TD", CtDroneSpec.GuessMakeModel("1581F8HGX255S00A0FZT"))
        assertEquals("DJI Avata 360", CtDroneSpec.GuessMakeModel("1581FBLKC262T00B07G1"))
        assertEquals("Autel Evo Max 4N", CtDroneSpec.GuessMakeModel("1748FEV3HMK924451281"))
        assertEquals("Potensic Atom LT", CtDroneSpec.GuessMakeModel("1910F916JJHWLHEFGVYC"))
        assertEquals("", CtDroneSpec.GuessMakeModel("1668BR40EA00Z5VX"))
    }

    @Test
    fun buildMappedId_usesModelAbbreviation() {
        assertEquals(
            "1sar7DjMn4Pr",
            CtDroneSpec.BuildMappedId("1sar7", "DJI Mini 4 Pro", "1581F6Z9C24BH0036EJL")
        )
        assertEquals(
            "1sar10PtnscAtm2lt",
            CtDroneSpec.BuildMappedId("1sar10", "Potensic Atom LT", "1910F916JJHWLHEFGVYC")
        )
    }

    @Test
    fun guessPilotCallsign_extractsPilotButClearsTeamPlaceholders() {
        assertEquals(
            "1sar7",
            CtDroneSpec.GuessPilotCallsign(
                "1sar7DjMn4Pr",
                "DJI Mini 4 Pro",
                "1581F6Z9C24BH0036EJL"
            )
        )
        assertEquals(
            "",
            CtDroneSpec.GuessPilotCallsign(
                "1sar1001DjMn4Pr-01",
                "DJI Mini 4 Pro",
                "1581F6Z9C2527003BZFX"
            )
        )
        assertEquals(
            "",
            CtDroneSpec.GuessPilotCallsign(
                "1668BR40EA00Z5VX",
                "",
                "1668BR40EA00Z5VX"
            )
        )
    }

    @Test
    fun signalIdleTime_tracksReceivedRidPacketBeforeWaypointAcceptance() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 12_345L

        drone.noteRidPositionPacketReceived(nowMs)

        assertEquals(nowMs, drone.mostRecentSignalMsecTimestamp)
        assertEquals(1500L, drone.signalIdleTimeInMsec(nowMs + 1500L))
    }

    @Test
    fun noteRidPositionPacketReceived_learnsPacketCadenceAtIngress() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 20_000L

        drone.noteRidPositionPacketReceived(nowMs)
        drone.noteRidPositionPacketReceived(nowMs + 3_000L)
        drone.noteRidPositionPacketReceived(nowMs + 9_000L)

        assertEquals(2, drone.learnedSignalIntervalSamples)
        assertEquals(3_750L, drone.learnedSignalIntervalMs)
    }

    @Test
    fun noteRidPositionPacketReceived_ignoresBurstSpacingForCadenceLearning() {
        val drone = CtDroneSpec("RID123")
        val nowMs = 30_000L

        drone.noteRidPositionPacketReceived(nowMs)
        drone.noteRidPositionPacketReceived(nowMs + 36L)
        drone.noteRidPositionPacketReceived(nowMs + 3_800L)
        drone.noteRidPositionPacketReceived(nowMs + 3_845L)
        drone.noteRidPositionPacketReceived(nowMs + 7_600L)

        assertEquals(nowMs + 7_600L, drone.mostRecentSignalMsecTimestamp)
        assertEquals(2, drone.learnedSignalIntervalSamples)
        assertEquals(3_761L, drone.learnedSignalIntervalMs)
    }
}

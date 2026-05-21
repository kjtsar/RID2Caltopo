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
    fun guessPilotCallsign_extractsPilotAndPreservesTeamSuffixes() {
        assertEquals(
            "1sar7",
            CtDroneSpec.GuessPilotCallsign(
                "1sar7DjMn4Pr",
                "DJI Mini 4 Pro",
                "1581F6Z9C24BH0036EJL"
            )
        )
        assertEquals(
            "1sar1001-01",
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
    fun buildMappedId_preservesTeamSuffixesInCallsign() {
        assertEquals(
            "1sar1002-02DjMtrc4td",
            CtDroneSpec.BuildMappedId("1sar1002-02", "DJI Matrice 4TD", "1581F8HGX256G00A0JPU")
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

    @Test
    fun updateAltitudeContext_firstLowAtoSampleSeedsImpliedTakeoffAltitude() {
        val drone = CtDroneSpec("RID123")

        drone.updateAltitudeContext(
            101.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )

        assertEquals(100.0, drone.impliedTakeoffAltM!!, 0.000001)
    }

    @Test
    fun updateAltitudeContext_followupLowAtoSampleDoesNotKeepNudgingSeed() {
        val drone = CtDroneSpec("RID123")

        drone.updateAltitudeContext(
            101.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )
        drone.updateAltitudeContext(
            103.0,
            CtDroneSpec.AltSourceEnum.BARO,
            1.0,
            true
        )

        assertEquals(100.0, drone.impliedTakeoffAltM!!, 0.000001)
    }

    @Test
    fun reset_clearsOutOfRangeClassification() {
        val drone = CtDroneSpec("RID123")

        drone.setOutOfRange(true)
        assertEquals(true, drone.isOutOfRange)

        drone.reset()

        assertEquals(false, drone.isOutOfRange)
    }

    @Test
    fun repeatedStationaryRidReports_areExposedForLandingSuppression() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            2_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            3_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(true, drone.hasStationaryRidReports())

        drone.checkNewWaypoint(
            39.0001,
            -121.0,
            100.0,
            4_000L,
            4_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )
        assertEquals(false, drone.hasStationaryRidReports())
    }

    @Test
    fun firstAcceptedWaypoint_recordsTakeoffLocationUntilReset() {
        val drone = CtDroneSpec("RID123")

        drone.checkNewWaypoint(
            39.0,
            -121.0,
            100.0,
            1_000L,
            1_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        assertEquals(true, drone.hasTakeoffLocation())
        assertEquals(39.0, drone.takeoffLat, 0.000001)
        assertEquals(-121.0, drone.takeoffLng, 0.000001)

        drone.checkNewWaypoint(
            39.001,
            -121.001,
            110.0,
            2_000L,
            2_000L,
            true,
            CtDroneSpec.TransportTypeEnum.BT4
        )

        assertEquals(39.0, drone.takeoffLat, 0.000001)
        assertEquals(-121.0, drone.takeoffLng, 0.000001)

        drone.reset()

        assertEquals(false, drone.hasTakeoffLocation())
    }
}

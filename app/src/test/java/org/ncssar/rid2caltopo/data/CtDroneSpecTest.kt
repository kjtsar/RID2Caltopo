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
}

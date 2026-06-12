package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PilotDisplayPrefsTest {
    @Test
    fun normalizePilotCallsign_trimsUppercasesAndRejectsBlank() {
        assertEquals("HARRY1", normalizePilotCallsign(" harry1 "))
        assertEquals("JENNIFER7", normalizePilotCallsign("Jennifer7"))
        assertNull(normalizePilotCallsign("   "))
        assertNull(normalizePilotCallsign(null))
    }

    @Test
    fun pilotDisplayPreference_defaultsMatchMapPaneColors() {
        val pref = PilotDisplayPreference()

        assertEquals("#1E88E5", pref.activeTrackColor)
        assertEquals("#FF00FF", pref.archiveTrackColor)
        assertEquals(false, pref.bearingEnabled)
    }

    @Test
    fun sanitizeTrackColor_acceptsHashOrBareHexAndFallsBack() {
        assertEquals("#00AAFF", sanitizeTrackColor("#00aaff", "#111111"))
        assertEquals("#AA00CC", sanitizeTrackColor("aa00cc", "#111111"))
        assertEquals("#111111", sanitizeTrackColor("blue", "#111111"))
        assertEquals("#111111", sanitizeTrackColor(null, "#111111"))
    }
}

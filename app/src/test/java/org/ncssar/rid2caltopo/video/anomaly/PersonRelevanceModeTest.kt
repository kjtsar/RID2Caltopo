package org.ncssar.rid2caltopo.video.anomaly

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonRelevanceModeTest {
    @Test
    fun defaultConfig_keepsPersonRelevanceOff() {
        assertEquals(PersonRelevanceMode.Off, AnomalyConfig().personRelevanceMode)
    }

    @Test
    fun realtimeReset_disablesPersonRelevance() {
        val reset = AnomalyConfig(
            enabled = true,
            personRelevanceMode = PersonRelevanceMode.Assist,
        ).resetToRealtimeDefaults()

        assertEquals(PersonRelevanceMode.Off, reset.personRelevanceMode)
    }

    @Test
    fun operatorLabelsAndGuidance_describeNonGatingModes() {
        assertEquals(listOf("Off", "Evaluate", "Assist"), PersonRelevanceMode.entries.map { it.label })
        assertEquals(listOf(0, 1, 2), PersonRelevanceMode.entries.map { it.nativeValue })
        assertTrue(PERSON_RELEVANCE_SUPPORTING_TEXT.contains("without changing ROI scores"))
        assertTrue(PERSON_RELEVANCE_SUPPORTING_TEXT.contains("never reject a target"))
    }
}

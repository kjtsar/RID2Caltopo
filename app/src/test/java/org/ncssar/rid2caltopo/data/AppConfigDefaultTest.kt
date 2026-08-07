package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigDefaultTest {
    @Test
    fun `missing protected-land config uses safe one-mile defaults`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
            .build()

        val defaults = AppConfigStore.resolveLandRestrictionDefaults(config)

        assertTrue(defaults.enabled)
        assertTrue(defaults.showOnMap)
        assertTrue(defaults.autoRefresh)
        assertEquals(1, defaults.radiusStatuteMiles)
    }

    @Test
    fun `explicit protected-land choices remain intact`() {
        val config = AppConfig.newBuilder()
            .setSchemaVersion(AppConfigStore.SCHEMA_VERSION)
            .setLandRestrictions(
                AppConfig.LandRestrictionConfig.newBuilder()
                    .setEnabled(false)
                    .setShowOnMap(false)
                    .setAutoRefresh(false)
                    .setRadiusNm(7)
            )
            .build()

        val defaults = AppConfigStore.resolveLandRestrictionDefaults(config)

        assertFalse(defaults.enabled)
        assertFalse(defaults.showOnMap)
        assertFalse(defaults.autoRefresh)
        assertEquals(7, defaults.radiusStatuteMiles)
    }

    @Test
    fun `client state starts with one-mile safety defaults`() {
        val state = ClientClassState()

        assertTrue(state.landRestrictionsEnabled)
        assertTrue(state.landRestrictionsShowOnMap)
        assertEquals(1, state.landRestrictionsRadiusNm)
        assertEquals(1, state.notamRadiusNm)
    }
}

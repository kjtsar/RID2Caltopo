package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationAccessPolicyTest {
    @Test
    fun organizationOrCaltopoTeamsConfigurationRequiresDeviceOwnerAuthentication() {
        assertFalse(organizationAccessAuthenticationRequired(null, false, false))
        assertFalse(organizationAccessAuthenticationRequired("", false, false))
        assertFalse(organizationAccessAuthenticationRequired("  \n", false, false))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", false, false))
        assertTrue(organizationAccessAuthenticationRequired("", true, false))
        assertTrue(organizationAccessAuthenticationRequired("", false, true))
        assertTrue(organizationAccessAuthenticationRequired("NCSSAR", true, true))
    }

    @Test
    fun authenticatedSessionSurvivesOnlyBriefBackgroundInactivity() {
        assertTrue(organizationAccessSessionRemainsValid(true, null, 50_000L))
        assertTrue(organizationAccessSessionRemainsValid(true, 10_000L, 24_999L))
        assertFalse(organizationAccessSessionRemainsValid(true, 10_000L, 25_000L))
        assertFalse(organizationAccessSessionRemainsValid(false, 10_000L, 10_001L))
        assertTrue(organizationAccessSessionRemainsValid(true, 20_000L, 19_000L))
    }
}

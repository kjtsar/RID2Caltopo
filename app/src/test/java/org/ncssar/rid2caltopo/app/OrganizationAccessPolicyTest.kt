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
}

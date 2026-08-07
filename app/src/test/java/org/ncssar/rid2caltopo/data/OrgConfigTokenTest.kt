package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgConfigTokenTest {
    @Test
    fun r2C2RoundTripsAndR2C1IsRejected() {
        val token = OrgConfigToken.encode(
            OrgConfigToken.OrgConfig(
                orgName = "NCSSAR",
                driveFileId = "drive-file",
                version = 2
            )
        )

        assertTrue(token.startsWith("R2C2:"))
        assertEquals("NCSSAR", OrgConfigToken.decode(token)?.orgName)
        assertNull(OrgConfigToken.decode("R2C1:${token.removePrefix("R2C2:")}"))
    }

    @Test
    fun r2C2BundleCarriesEnrollmentLocatorInsteadOfIssuedCredential() {
        val enrollmentUrl = "https://r2c-tracker.com/ncssar/enroll?token=campaign-token"
        val credentials = JSONObject()
            .put("type", "ct_credentials")
            .put("tracker_enrollment_url", enrollmentUrl)
        val bundle = JSONObject()
            .put("format", "rid2caltopo_org_config")
            .put("version", 2)
            .put("configs", JSONArray().put(credentials))

        assertEquals(enrollmentUrl, OrgConfigManager.trackerEnrollmentUrl(bundle.toString()))
        assertFalse(credentials.has("tracker_api_key"))

        credentials.put("tracker_api_key", "r2c_dev_source-tablet-secret")
        assertNull(OrgConfigManager.trackerEnrollmentUrl(bundle.toString()))
    }
}

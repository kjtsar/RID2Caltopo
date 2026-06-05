package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrgConfigBundleTest {
    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun buildOrgConfigBundle_includesOrgNameInCredentialsChild() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetHomeOrgName("NCSSAR")
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")

        val bundle = JSONObject(CaltopoClient.BuildOrgConfigBundle("NCSSAR"))
        val credentials = findConfig(bundle, "ct_credentials")

        assertEquals("NCSSAR", bundle.getString("org_name"))
        assertEquals("NCSSAR", credentials.getString("org_name"))
    }

    @Test
    fun applyOrgConfigBundle_restoresHomeOrgFromQrCredentialsChild() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetTrackerApiKey("tracker-token")
        CaltopoClient.SetTrackerUrlPfx("https://tracker.example.org")
        CaltopoClient.SetMutualAidTemplate(
            MutualAidTemplateRecord(
                "ma-team",
                "ma-cred",
                "ma-secret",
                "caltopo.com",
                "NCSSAR",
                "MAI"
            )
        )
        val bundle = CaltopoClient.BuildOrgConfigBundle("NCSSAR")
        checkNotNull(bundle)
        val json = JSONObject(bundle)
        findConfig(json, "ct_credentials").put("org_name", "NCSSAR")

        CaltopoClient.ResetPersistedClientState()

        assertTrue(CaltopoClient.ApplyOrgConfigBundle(json.toString()))
        assertEquals("NCSSAR", CaltopoClient.GetHomeOrgName())
    }

    private fun findConfig(bundle: JSONObject, type: String): JSONObject {
        val configs = bundle.getJSONArray("configs")
        for (i in 0 until configs.length()) {
            val config = configs.getJSONObject(i)
            if (config.optString("type") == type) return config
        }
        throw AssertionError("Missing config type $type")
    }
}

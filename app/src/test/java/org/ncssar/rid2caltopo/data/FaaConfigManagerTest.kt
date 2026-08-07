package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaaConfigManagerTest {
    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun plaintextImport_appliesOnlyFaaNotamFields() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.SetCaltopoCredentials(CaltopoCredentials("team", "cred", "secret"))
        CaltopoClient.SetTrackerApiKey("tracker-key")

        FaaConfigManager.importPlaintextConfig(samplePlaintext())

        assertEquals("team", CaltopoClient.GetCaltopoCredentials().teamId)
        assertEquals("tracker-key", CaltopoClient.GetTrackerApiKey())
        assertEquals("client-id", CaltopoClient.GetNotamClientId())
        assertEquals("client-secret", CaltopoClient.GetNotamClientSecret())
        assertTrue(CaltopoClient.GetNotamEnabled())
        assertTrue(CaltopoClient.GetNotamAutoRefresh())
        assertTrue(CaltopoClient.GetFaaPayloadEnc().contains(FaaConfigManager.TYPE_ENCRYPTED))
        assertFalse(CaltopoClient.GetFaaPayloadEnc().contains("client-secret"))
    }

    @Test
    fun r2C2OrgBundleExcludesLegacyFaaCredentialsAndBootstrap() {
        CaltopoClient.ResetPersistedClientState()
        FaaConfigManager.importPlaintextConfig(samplePlaintext())
        CaltopoClient.StoreFaaRemoteConfig(
            FaaConfigToken.encode(FaaConfigToken.FaaConfig("drive-id", "FAA label")),
            "FAA label",
            CaltopoClient.GetFaaPayloadEnc(),
            1234L,
            false,
            ""
        )

        val bundle = CaltopoClient.BuildOrgConfigBundle("Test Org")
        val json = JSONObject(bundle)
        val configs = json.getJSONArray("configs")
        val serialized = configs.toString()

        assertFalse(serialized.contains("client-secret"))
        assertFalse(serialized.contains("notam_client_secret"))
        assertFalse(serialized.contains(FaaConfigManager.TYPE_REMOTE))
        assertFalse(serialized.contains("R2CFAA1:"))
    }

    @Test
    fun authorizationFailure_marksCachedFaaConfigStale() {
        CaltopoClient.ResetPersistedClientState()
        FaaConfigManager.importPlaintextConfig(samplePlaintext())

        FaaConfigManager.markAuthorizationFailure("NOTAM authentication failed (HTTP 401).")

        assertTrue(CaltopoClient.GetFaaConfigStale())
        assertEquals("NOTAM authentication failed (HTTP 401).", CaltopoClient.GetFaaLastFailureReason())
    }

    private fun samplePlaintext(): JSONObject =
        JSONObject()
            .put("type", FaaConfigManager.TYPE_PLAINTEXT)
            .put("file_version", "1.0")
            .put("source_label", "FAA label")
            .put("notam_api_base_url", "https://example.test/nmsapi")
            .put("notam_token_url", "https://example.test/token")
            .put("notam_client_id", "client-id")
            .put("notam_client_secret", "client-secret")
            .put("notam_scope", "")
}

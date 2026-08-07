package org.ncssar.rid2caltopo.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OperationalProfileLifecycleTest {
    @After
    fun tearDown() {
        CaltopoClient.ResetPersistedClientState()
    }

    @Test
    fun operationalProfileOptions_identifyHomeAndMutualAidWithoutSecrets() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.UpsertCaltopoProfile(profile("home", "HOME", "YCSAR", 0L, "home-token"), true, false)
        CaltopoClient.UpsertCaltopoProfile(profile("ma", "MUTUAL_AID", "NCSSAR", Long.MAX_VALUE, "ma-token"), true, false)

        val options = CaltopoClient.GetOperationalProfileOptions()

        assertEquals(listOf("YCSAR", "NCSSAR-MA"), options.map { it[1] })
        assertEquals(listOf("Home organization", "Mutual Aid"), options.map { it[2] })
        options.forEach { option ->
            assertEquals(4, option.size)
        }
    }

    @Test
    fun expiredMutualAidProfile_isRemovedAndHomeCredentialsBecomeActive() {
        CaltopoClient.ResetPersistedClientState()
        CaltopoClient.UpsertCaltopoProfile(profile("home", "HOME", "YCSAR", 0L, "home-token"), true, false)
        CaltopoClient.UpsertCaltopoProfile(profile("ma", "MUTUAL_AID", "NCSSAR", 1_000L, "ma-token"), true, false)

        assertEquals(1, CaltopoClient.RemoveExpiredCaltopoProfiles(1_000L, false))

        assertEquals("home", CaltopoClient.GetActiveCaltopoProfileId())
        assertEquals("home-token", CaltopoClient.GetTrackerCoordinationApiKey())
        assertNull(CaltopoClient.GetCaltopoProfileById("ma"))
    }

    private fun profile(
        id: String,
        type: String,
        organization: String,
        expiresAtEpochMs: Long,
        trackerToken: String
    ) = CaltopoProfileRecord(
        id,
        organization,
        type,
        CaltopoCredentials("team-$id", "credential-$id", "secret-$id"),
        "caltopo.com",
        "Drone Tracks",
        "Incident",
        "1",
        trackerToken,
        "https://r2c-tracker.com/${organization.lowercase()}",
        type == "MUTUAL_AID",
        expiresAtEpochMs,
        type == "MUTUAL_AID",
        organization,
        if (type == "MUTUAL_AID") "map-ma" else "",
        "Incident",
        "MAI",
        0L,
        "$organization|Incident|1"
    )
}

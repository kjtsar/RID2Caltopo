package org.ncssar.rid2caltopo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ncssar.rid2caltopo.data.CaltopoClient
import org.ncssar.rid2caltopo.data.TrackerEnrollmentResult

class ImportConfigRoutingTest {
    @Test
    fun `json extension overrides generic provider mime type`() {
        assertEquals(
            ImportConfigFileKind.JSON_CONFIG,
            classifyImportConfigFile("NCSSAR_MutualAid.json", "application/octet-stream")
        )
    }

    @Test
    fun `zip extension routes to mutual aid package importer`() {
        assertEquals(
            ImportConfigFileKind.MUTUAL_AID_PACKAGE,
            classifyImportConfigFile("Incident_mutual_aid_package.zip", "application/octet-stream")
        )
    }

    @Test
    fun `mime types provide fallback when provider omits file name`() {
        assertEquals(
            ImportConfigFileKind.JSON_CONFIG,
            classifyImportConfigFile(null, "application/json; charset=utf-8")
        )
        assertEquals(
            ImportConfigFileKind.MUTUAL_AID_PACKAGE,
            classifyImportConfigFile(null, "application/zip")
        )
    }

    @Test
    fun `unrecognized files are rejected`() {
        assertEquals(
            ImportConfigFileKind.UNSUPPORTED,
            classifyImportConfigFile("notes.pdf", "application/pdf")
        )
    }

    @Test
    fun `tracker enrollment immediately refreshes both FAA safety sources`() {
        CaltopoClient.ResetPersistedClientState()
        var notamRefreshRequested = false
        var airspaceRefreshRequested = false
        var trackReplayRequested = false

        applyTrackerEnrollmentAndRefreshNotams(
            result = TrackerEnrollmentResult(
                organization = "TEST_ORG",
                trackerBaseUrl = "https://tracker.example.test/org",
                deviceToken = "test-device-token",
                faaProxyUrl = "https://tracker.example.test/faa/notams",
                enrollmentUrl = "https://r2c-tracker.com/test/enroll?token=campaign-token"
            ),
            requestNotamRefresh = { notamRefreshRequested = true },
            requestAirspaceRefresh = { airspaceRefreshRequested = true },
            requestTrackReplay = { trackReplayRequested = true }
        )

        assertEquals("https://tracker.example.test/faa/notams", CaltopoClient.GetTrackerFaaProxyUrl())
        assertEquals(
            "https://r2c-tracker.com/test/enroll?token=campaign-token",
            CaltopoClient.GetTrackerEnrollmentUrl()
        )
        assertTrue(notamRefreshRequested)
        assertTrue(airspaceRefreshRequested)
        assertTrue(trackReplayRequested)
        CaltopoClient.ResetPersistedClientState()
    }
}

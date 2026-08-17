package org.ncssar.rid2caltopo.data

import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CaltopoInterruptedTrackJournalTest {
    private lateinit var journalFile: File

    @Before
    fun setUp() {
        journalFile = File.createTempFile("caltopo-interrupted-", ".json")
        journalFile.delete()
        CaltopoInterruptedTrackJournal.setFileForTesting(journalFile)
    }

    @After
    fun tearDown() {
        CaltopoInterruptedTrackJournal.setFileForTesting(null)
        journalFile.delete()
        File(journalFile.parentFile, journalFile.name + ".tmp").delete()
    }

    @Test
    fun saveSurvivesReloadAndRemoveClearsOnlyMatchingTrack() {
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-A",
            "live-a",
            "A_120000Aug11",
            "",
            JSONArray("[[-121.1,39.1,500.0],[-121.2,39.2,501.0]]"),
        )
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-B",
            "live-b",
            "B_120100Aug11",
            "https://r2c-tracker.com/s/example",
            JSONArray("[[-121.3,39.3,502.0]]"),
        )

        val persisted = CaltopoInterruptedTrackJournal.entriesForTesting()
        assertEquals(2, persisted.length())
        assertTrue(journalFile.readText().contains("live-a"))
        assertEquals(2, persisted.getJSONObject(0).getJSONArray("points").length())

        CaltopoInterruptedTrackJournal.remove("live-a")

        val remaining = CaltopoInterruptedTrackJournal.entriesForTesting()
        assertEquals(1, remaining.length())
        assertEquals("live-b", remaining.getJSONObject(0).getString("liveTrackId"))
    }

    @Test
    fun relaunchRecoveryConvertsThenDeletesBeforeClearingJournal() {
        CaltopoInterruptedTrackJournal.save(
            "map-a",
            "RID-RECOVERY",
            "live-recovery",
            "200615Aug11",
            "",
            JSONArray("[[-121.132828,39.153080,527.0]]"),
        )
        val fixture = TestR2cRuntimeFactory.create("recovery-test")

        val recovering = CaltopoInterruptedTrackJournal.recover(
            "map-a",
            "archive-folder",
            fixture.runtime,
        )

        assertEquals(setOf("live-recovery"), recovering)
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("editObject"))
        assertEquals(1, fixture.calTopoSessionGateway.countOperations("deleteLiveTrack"))
        assertEquals(0, CaltopoInterruptedTrackJournal.entriesForTesting().length())
    }
}

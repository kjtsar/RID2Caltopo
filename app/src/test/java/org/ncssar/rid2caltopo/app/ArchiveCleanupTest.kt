package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveCleanupTest {
    @Test
    fun datedArchiveDirectoryRecognition_acceptsOnlyTracksDatePattern() {
        assertTrue(isDatedArchiveDirectoryName("tracks-24May2026"))
        assertFalse(isDatedArchiveDirectoryName("cache"))
        assertFalse(isDatedArchiveDirectoryName("tracks-2026-05-24"))
        assertFalse(isDatedArchiveDirectoryName("tracks-99May2026"))
        assertFalse(isDatedArchiveDirectoryName("other-24May2026"))
    }

    @Test
    fun formatArchiveAge_usesLargestRequestedWholeUnit() {
        assertEquals("<1 minute", formatArchiveAge(59_999L))
        assertEquals("1 minute", formatArchiveAge(60_000L))
        assertEquals("59 minutes", formatArchiveAge(59L * 60_000L))
        assertEquals("1 hour", formatArchiveAge(60L * 60_000L))
        assertEquals("23 hours", formatArchiveAge(23L * 60L * 60_000L))
        assertEquals("1 day", formatArchiveAge(24L * 60L * 60_000L))
        assertEquals("29 days", formatArchiveAge(29L * 24L * 60L * 60_000L))
        assertEquals("1 month", formatArchiveAge(30L * 24L * 60L * 60_000L))
        assertEquals("12 months", formatArchiveAge(364L * 24L * 60L * 60_000L))
        assertEquals("1 year", formatArchiveAge(365L * 24L * 60L * 60_000L))
    }

    @Test
    fun formatArchiveSize_formatsCommonUnits() {
        assertEquals("512 B", formatArchiveSize(512L))
        assertEquals("1.5 KB", formatArchiveSize(1536L))
        assertEquals("2.0 MB", formatArchiveSize(2L * 1024L * 1024L))
        assertEquals("3.0 GB", formatArchiveSize(3L * 1024L * 1024L * 1024L))
    }

    @Test
    fun summarizeArchiveEntries_countsRecursiveLogsKmzAndVideos() {
        val summary = summarizeArchiveEntries(
            listOf(
                ArchiveCleanupFileEntry("Log_120000May24.txt", "application/octet-stream", false, 100L),
                ArchiveCleanupFileEntry("flight.kmz", "application/vnd.google-earth.kmz", false, 200L),
                ArchiveCleanupFileEntry("A_120000.mp4", "application/octet-stream", false, 300L),
                ArchiveCleanupFileEntry(
                    name = "nested",
                    type = null,
                    isDirectory = true,
                    length = 0L,
                    children = listOf(
                        ArchiveCleanupFileEntry("B_120000.fmp4", "video/mp4", false, 400L),
                        ArchiveCleanupFileEntry("segment.ts", null, false, 500L),
                        ArchiveCleanupFileEntry("debug", "text/plain", false, 600L),
                    )
                )
            )
        )

        assertEquals(2100L, summary.totalBytes)
        assertEquals(2, summary.logFileCount)
        assertEquals(1, summary.kmzCount)
        assertEquals(3, summary.videoCount)
    }

    @Test
    fun buildArchiveCleanupOption_fallsBackToParsedDirectoryDateAndFlagsToday() {
        val nowMs = parseArchiveDirectoryDateMs("tracks-25May2026")!!
        val option = buildArchiveCleanupOption(
            directoryName = "tracks-24May2026",
            lastModifiedMs = 0L,
            entries = emptyList(),
            nowMs = nowMs,
            todayName = "tracks-24May2026",
        )!!

        assertEquals("1 day", option.ageLabel)
        assertTrue(option.isToday)
        assertEquals("0 B", option.sizeLabel)
    }

    @Test
    fun archiveCleanupSelection_defaultsEmptyAndProtectsToday() {
        val today = ArchiveCleanupDirectoryOption(
            directoryName = "tracks-24May2026",
            ageMs = 0L,
            ageLabel = "<1 minute",
            totalBytes = 0L,
            sizeLabel = "0 B",
            logFileCount = 0,
            kmzCount = 0,
            videoCount = 0,
            lastModifiedMs = 0L,
            isToday = true,
        )
        val old = today.copy(
            directoryName = "tracks-23May2026",
            ageMs = 24L * 60L * 60L * 1000L,
            ageLabel = "1 day",
            isToday = false,
        )

        assertTrue(defaultSelectedArchiveCleanupDirectories().isEmpty())
        assertFalse(canDeleteArchiveCleanupSelection(listOf(today, old), setOf(today.directoryName)))
        assertTrue(canDeleteArchiveCleanupSelection(listOf(today, old), setOf(old.directoryName)))
    }
}

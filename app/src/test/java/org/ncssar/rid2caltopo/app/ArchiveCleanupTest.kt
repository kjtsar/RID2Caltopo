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
    fun formatArchiveAge_usesUnboundedHours() {
        assertEquals("1:02:03", formatArchiveAge(((1 * 60 * 60) + (2 * 60) + 3) * 1000L))
        assertEquals("122:14:09", formatArchiveAge(((122 * 60 * 60) + (14 * 60) + 9) * 1000L))
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

        assertEquals("24:00:00", option.ageLabel)
        assertTrue(option.isToday)
        assertEquals("0 B", option.sizeLabel)
    }

    @Test
    fun archiveCleanupSelection_defaultsEmptyAndProtectsToday() {
        val today = ArchiveCleanupDirectoryOption(
            directoryName = "tracks-24May2026",
            ageMs = 0L,
            ageLabel = "0:00:00",
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
            ageLabel = "24:00:00",
            isToday = false,
        )

        assertTrue(defaultSelectedArchiveCleanupDirectories().isEmpty())
        assertFalse(canDeleteArchiveCleanupSelection(listOf(today, old), setOf(today.directoryName)))
        assertTrue(canDeleteArchiveCleanupSelection(listOf(today, old), setOf(old.directoryName)))
    }
}

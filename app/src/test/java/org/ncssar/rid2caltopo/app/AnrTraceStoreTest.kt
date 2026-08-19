package org.ncssar.rid2caltopo.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AnrTraceStoreTest {
    @Test
    fun traceFilenameUsesStableExitIdentity() {
        assertEquals("anr_1776498965560_18307.txt", anrTraceFilename(1_776_498_965_560L, 18_307))
    }

    @Test
    fun boundedCopyPreservesShortTrace() {
        val output = ByteArrayOutputStream()
        val result = copyAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output, 4)

        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(3L, result.bytesCopied)
        assertFalse(result.truncated)
    }

    @Test
    fun boundedCopyCapsOversizedTrace() {
        val output = ByteArrayOutputStream()
        val result = copyAtMost(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), output, 3)

        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(3L, result.bytesCopied)
        assertTrue(result.truncated)
    }
}

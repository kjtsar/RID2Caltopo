package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFileCompatTest {
    @Test
    fun textPlainCreationOmitsSuffixThatRawDocumentFileWouldDuplicate() {
        assertEquals("Log_183635Aug17", displayNameForCreateFile("text/plain", "Log_183635Aug17.txt"))
        assertEquals("r2c_reported", displayNameForCreateFile("text/plain", "r2c_reported.txt"))
    }

    @Test
    fun creationPreservesNamesWithoutTheMimeSuffixAndStripsMatchingSuffixes() {
        assertEquals("operator-notes", displayNameForCreateFile("text/plain", "operator-notes"))
        assertEquals("track", displayNameForCreateFile("application/json", "track.json"))
    }
}

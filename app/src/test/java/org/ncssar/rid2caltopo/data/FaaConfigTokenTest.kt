package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaaConfigTokenTest {
    @Test
    fun encodeDecode_roundTripsDriveFileAndLabel() {
        val token = FaaConfigToken.encode(
            FaaConfigToken.FaaConfig(
                driveFileId = "drive-file-123",
                label = "NCSSAR shared FAA NOTAM credentials"
            )
        )

        assertTrue(token.startsWith(FaaConfigToken.MAGIC_PREFIX))
        val decoded = requireNotNull(FaaConfigToken.decode(token))
        assertEquals("drive-file-123", decoded.driveFileId)
        assertEquals("NCSSAR shared FAA NOTAM credentials", decoded.label)
        assertTrue(decoded.isPublic)
    }

    @Test
    fun qrUri_roundTripsToken() {
        val token = FaaConfigToken.encode(FaaConfigToken.FaaConfig("file-id"))
        val uri = FaaConfigToken.toQrUri(token)

        assertTrue(uri.startsWith("r2cfaa1://"))
        assertEquals(token, FaaConfigToken.fromQrUri(uri))
    }

    @Test
    fun decode_rejectsInvalidToken() {
        assertNull(FaaConfigToken.decode("R2CFAA1:not-valid"))
        assertFalse(FaaConfigToken.isValidToken("R2C1:not-an-faa-token"))
    }

    @Test
    fun encryptPayload_roundTripsWithoutPlaintext() {
        val plaintext = """{"type":"ct_faa_credentials","notam_client_secret":"SECRET"}"""
        val encrypted = FaaConfigToken.encryptPayload(plaintext)

        assertFalse(encrypted.contains("SECRET"))
        assertEquals(plaintext, FaaConfigToken.decryptPayload(encrypted))
        assertNotNull(encrypted)
    }
}

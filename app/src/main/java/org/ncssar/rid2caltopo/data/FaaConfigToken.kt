package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import java.util.Base64

object FaaConfigToken {
    const val MAGIC_PREFIX = "R2CFAA1:"
    const val QR_SCHEME = "r2cfaa1"

    data class FaaConfig(
        val driveFileId: String,
        val label: String = "",
        val isPublic: Boolean = true,
        val version: Int = 1
    )

    fun encode(config: FaaConfig): String {
        val json = JSONObject()
            .put("f", config.driveFileId)
            .put("l", config.label)
            .put("p", if (config.isPublic) 1 else 0)
            .put("v", config.version)
            .toString()
        val xored = xorBytes(json.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().encodeToString(xored)
        val remapped = buildString(b64.length) {
            for (c in b64) {
                val idx = STD_ALPHABET.indexOf(c)
                append(if (idx >= 0) CUSTOM_ALPHABET[idx] else c)
            }
        }
        return MAGIC_PREFIX + remapped
    }

    fun decode(token: String): FaaConfig? {
        return try {
            if (!token.startsWith(MAGIC_PREFIX)) return null
            val encoded = token.removePrefix(MAGIC_PREFIX)
            val remapped = buildString(encoded.length) {
                for (c in encoded) {
                    val idx = CUSTOM_ALPHABET.indexOf(c)
                    append(if (idx >= 0) STD_ALPHABET[idx] else c)
                }
            }
            val xored = Base64.getDecoder().decode(remapped)
            val json = JSONObject(String(xorBytes(xored), Charsets.UTF_8))
            FaaConfig(
                driveFileId = json.optString("f", ""),
                label = json.optString("l", ""),
                isPublic = json.optInt("p", 1) != 0,
                version = json.optInt("v", 1)
            ).takeIf { it.driveFileId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun isValidToken(token: String): Boolean = decode(token.trim()) != null

    fun toQrUri(token: String): String =
        "$QR_SCHEME://" + token.removePrefix(MAGIC_PREFIX)

    fun fromQrUri(uri: String): String? {
        if (!uri.startsWith("$QR_SCHEME://")) return null
        return MAGIC_PREFIX + uri.removePrefix("$QR_SCHEME://")
    }

    @JvmStatic
    fun encryptPayload(plaintext: String): String {
        val xored = xorBytes(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(xored)
    }

    @JvmStatic
    fun decryptPayload(encoded: String): String {
        val xored = Base64.getDecoder().decode(encoded)
        return String(xorBytes(xored), Charsets.UTF_8)
    }

    private fun xorBytes(input: ByteArray): ByteArray =
        ByteArray(input.size) { i ->
            (input[i].toInt() xor OrgConfigToken.XOR_KEY[i % OrgConfigToken.XOR_KEY.size].toInt()).toByte()
        }

    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
    private const val CUSTOM_ALPHABET =
        "r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/="
}

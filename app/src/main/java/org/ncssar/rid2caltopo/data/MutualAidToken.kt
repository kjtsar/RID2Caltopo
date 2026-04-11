package org.ncssar.rid2caltopo.data

import android.util.Base64
import org.json.JSONObject

object MutualAidToken {
    const val MAGIC_PREFIX = "R2CMA1:"

    data class MutualAidConfig(
        val sourceOrg: String,
        val driveFileId: String,
        val isPublic: Boolean = true,
        val version: Int = 1
    )

    fun encode(config: MutualAidConfig): String {
        val json = JSONObject()
            .put("o", config.sourceOrg)
            .put("f", config.driveFileId)
            .put("p", if (config.isPublic) 1 else 0)
            .put("v", config.version)
            .toString()
        val xored = xorBytes(json.toByteArray(Charsets.UTF_8))
        val b64 = Base64.encodeToString(xored, Base64.NO_WRAP)
        val remapped = buildString(b64.length) {
            for (c in b64) {
                val idx = STD_ALPHABET.indexOf(c)
                append(if (idx >= 0) CUSTOM_ALPHABET[idx] else c)
            }
        }
        return MAGIC_PREFIX + remapped
    }

    fun decode(token: String): MutualAidConfig? {
        return try {
            if (!token.startsWith(MAGIC_PREFIX)) return null
            val encoded = token.removePrefix(MAGIC_PREFIX)
            val remapped = buildString(encoded.length) {
                for (c in encoded) {
                    val idx = CUSTOM_ALPHABET.indexOf(c)
                    append(if (idx >= 0) STD_ALPHABET[idx] else c)
                }
            }
            val xored = Base64.decode(remapped, Base64.NO_WRAP)
            val json = JSONObject(String(xorBytes(xored), Charsets.UTF_8))
            MutualAidConfig(
                sourceOrg = json.optString("o", ""),
                driveFileId = json.optString("f", ""),
                isPublic = json.optInt("p", 1) != 0,
                version = json.optInt("v", 1)
            ).takeIf { it.driveFileId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun isValidToken(token: String): Boolean = decode(token.trim()) != null

    @JvmStatic
    fun encryptPayload(plaintext: String): String {
        val xored = xorBytes(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(xored, Base64.NO_WRAP)
    }

    @JvmStatic
    fun decryptPayload(encoded: String): String {
        val xored = Base64.decode(encoded, Base64.NO_WRAP)
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

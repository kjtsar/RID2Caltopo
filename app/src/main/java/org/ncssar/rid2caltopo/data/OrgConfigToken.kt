/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data

import org.json.JSONObject
import java.util.Base64

/**
 * Encodes and decodes org-config join tokens for the QR-based org join flow.
 *
 * Token format:  R2C2:<obfuscated_payload>
 *
 * Two-layer obfuscation (not encryption — the goal is opacity to casual readers,
 * not cryptographic security):
 *   1. XOR each payload byte with a cycling app-specific key.
 *   2. Base64-encode the result, then substitute each character through a
 *      shuffled alphabet so standard decoders produce garbage.
 *
 * A standard QR scanner shows "R2C2:r2cXXX..." — recognisably a custom
 * format, but completely opaque without the app.
 *
 * The same XOR key is also used to obfuscate the ct_credentials block inside
 * the Drive bundle ([encryptPayload] / [decryptPayload]), so that the Drive
 * file is not plaintext credentials even though it is publicly readable.
 */
object OrgConfigToken {

    /** Magic prefix used to identify a valid join token. */
    const val MAGIC_PREFIX = "R2C2:"
    const val QR_SCHEME = "r2c2"

    /** XOR key — 13 bytes, cycles across payload via index mod 13. */
    internal val XOR_KEY = "RID2CaltopoQR".toByteArray(Charsets.UTF_8)

    // Standard RFC 4648 base64 alphabet (with padding '=').
    private const val STD_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="

    // Shuffled custom alphabet — starts with "r2c" as a branding cue.
    // Contains every character from STD_ALPHABET exactly once; order is scrambled.
    // Construction:
    //   r, 2, c  — branding prefix (consume one lowercase, one digit, one lowercase)
    //   N–Z, A–M — all 26 uppercase, rotated to de-correlate
    //   n,o,p,q,s,t,u,v,w,x,y,z — lowercase without 'r' or 'c' (12 chars)
    //   a,b,d,e,f,g,h,i,j,k,l,m — lowercase without 'c', continued (12 chars)
    //   0,1,3,4,5,6,7,8,9        — digits without '2' (9 chars)
    //   +, /, =                  — padding/special (3 chars)
    private const val CUSTOM_ALPHABET =
        "r2cNOPQRSTUVWXYZABCDEFGHIJKLMnopqstuvwxyzabdefghijklm013456789+/="

    init {
        check(STD_ALPHABET.length == CUSTOM_ALPHABET.length) {
            "BUG: alphabet length mismatch (${STD_ALPHABET.length} vs ${CUSTOM_ALPHABET.length})"
        }
        check(STD_ALPHABET.toSet() == CUSTOM_ALPHABET.toSet()) {
            "BUG: custom alphabet must contain exactly the same characters as the standard one"
        }
    }

    data class OrgConfig(
        val orgName: String,
        val driveFileId: String,
        val isPublic: Boolean = true,
        val version: Int = 2
    )

    /** Encode an [OrgConfig] into an opaque join token string. */
    fun encode(config: OrgConfig): String {
        val json = JSONObject()
            .put("o", config.orgName)
            .put("f", config.driveFileId)
            .put("p", if (config.isPublic) 1 else 0)
            .put("v", config.version)
        val jsonText = json.toString()
        val xored = xorBytes(jsonText.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder().encodeToString(xored)
        val remapped = buildString(b64.length) {
            for (c in b64) {
                val idx = STD_ALPHABET.indexOf(c)
                append(if (idx >= 0) CUSTOM_ALPHABET[idx] else c)
            }
        }
        return MAGIC_PREFIX + remapped
    }

    /** Decode a join token. Returns null if the token is invalid or unrecognised. */
    fun decode(token: String): OrgConfig? {
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
            OrgConfig(
                orgName     = json.optString("o", ""),
                driveFileId = json.optString("f", ""),
                isPublic    = json.optInt("p", 1) != 0,
                version     = json.optInt("v", 1)
            ).takeIf { it.driveFileId.isNotBlank() && it.version == 2 }
        } catch (_: Exception) {
            null
        }
    }

    fun isValidToken(token: String): Boolean = decode(token.trim()) != null

    /**
     * XOR-encrypt [plaintext] with the app key and return a standard base64 string.
     * Used to obfuscate the ct_credentials block stored in the public Drive bundle
     * so that credentials are not in plaintext even in a publicly readable file.
     */
    @JvmStatic
    fun encryptPayload(plaintext: String): String {
        val xored = xorBytes(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(xored)
    }

    /**
     * Produce the byte-stable credential JSON shared with the Apple app.
     * Empty optional values are omitted and keys are sorted before encryption so
     * equivalent credentials generate the same tracker comparison value.
     */
    @JvmStatic
    fun canonicalCredentialPayload(values: Map<String, String>): String =
        values.entries
            .filter { it.value.isNotEmpty() }
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}", separator = ",") {
                "${JSONObject.quote(it.key)}:${JSONObject.quote(it.value)}"
            }

    /**
     * Reverse of [encryptPayload].  Decodes base64 then XOR-decrypts.
     */
    @JvmStatic
    fun decryptPayload(encoded: String): String {
        val xored = Base64.getDecoder().decode(encoded)
        return String(xorBytes(xored), Charsets.UTF_8)
    }

    internal fun xorBytes(input: ByteArray): ByteArray =
        ByteArray(input.size) { i ->
            (input[i].toInt() xor XOR_KEY[i % XOR_KEY.size].toInt()).toByte()
        }
}

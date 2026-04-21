/*
 * Copyright (C) 2025 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */

package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Manages the org-config bundle workflow:
 *   - Admin: build a bundle from current app state, encrypt credentials,
 *     upload to Drive, generate join token
 *   - Member: decode a join token, download bundle from Drive, decrypt
 *     credentials, apply to app state
 *
 * Security model:
 *   The Drive file is publicly readable (anyone with the file ID can download
 *   it).  The ct_ridmap block is stored in plaintext — it contains no secrets.
 *   The credential-bearing blocks are XOR-obfuscated with an app-specific key
 *   before upload and decrypted locally after download, so credentials are
 *   never stored in plaintext on Drive.  The file ID is embedded in the QR
 *   token using the same obfuscation layer, so casual scanners see nothing useful.
 *
 * Tokens are stored in SharedPreferences so the app remembers which org it has
 * joined (useful for display in the UI and future re-apply).
 */
object OrgConfigManager {

    private const val TAG = "OrgConfigManager"
    private const val PREFS = "org_config_join"
    private const val KEY_TOKEN = "join_token"
    private const val KEY_ORG_NAME = "org_name"

    // Type tags used in the bundle JSON to mark encrypted credential blocks.
    private const val TYPE_CREDENTIALS_ENC = "ct_credentials_enc"
    private const val TYPE_CREDENTIALS     = "ct_credentials"
    private const val TYPE_MUTUAL_AID_CREDENTIALS = "ct_mutual_aid_credentials"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Token / org-name persistence ──────────────────────────────────────────

    @JvmStatic
    fun storeToken(context: Context, token: String) {
        val config = OrgConfigToken.decode(token) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_ORG_NAME, config.orgName)
            .apply()
    }

    @JvmStatic
    fun getStoredToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)

    @JvmStatic
    fun getStoredOrgName(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORG_NAME, "") ?: ""

    @JvmStatic
    fun hasJoinedOrg(context: Context): Boolean = !getStoredToken(context).isNullOrBlank()

    /** Persist just the org name (e.g. to survive a Drive auth round-trip). */
    @JvmStatic
    fun storeOrgName(context: Context, orgName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ORG_NAME, orgName)
            .apply()
    }

    // ── Credential encryption helpers ─────────────────────────────────────────

    /**
     * Walk the "configs" array in [bundleJson] and replace any credential-bearing
     * object with an encrypted surrogate containing only
     * { "type": "ct_credentials_enc", "enc": "<XOR-base64>" }.
     */
    private fun encryptCredentialsInBundle(bundleJson: String): String {
        val bundle = JSONObject(bundleJson)
        val configs = bundle.optJSONArray("configs") ?: return bundleJson
        val secured = JSONArray()
        for (i in 0 until configs.length()) {
            val item = configs.getJSONObject(i)
            val type = item.optString("type")
            if (type == TYPE_CREDENTIALS || type == TYPE_MUTUAL_AID_CREDENTIALS) {
                val enc = OrgConfigToken.encryptPayload(item.toString())
                secured.put(JSONObject().put("type", TYPE_CREDENTIALS_ENC).put("enc", enc))
            } else {
                secured.put(item)
            }
        }
        bundle.put("configs", secured)
        return bundle.toString()
    }

    /**
     * Walk the "configs" array in [bundleJson] and replace any
     * "ct_credentials_enc" surrogate with the decrypted original object.
     */
    private fun decryptCredentialsInBundle(bundleJson: String): String {
        val bundle = JSONObject(bundleJson)
        val configs = bundle.optJSONArray("configs") ?: return bundleJson
        val restored = JSONArray()
        for (i in 0 until configs.length()) {
            val item = configs.getJSONObject(i)
            if (item.optString("type") == TYPE_CREDENTIALS_ENC) {
                val plaintext = OrgConfigToken.decryptPayload(item.getString("enc"))
                restored.put(JSONObject(plaintext))
            } else {
                restored.put(item)
            }
        }
        bundle.put("configs", restored)
        return bundle.toString()
    }

    // ── Admin: upload org config and return join token ─────────────────────────

    /**
     * Export the current app's ridmap + credentials to Drive, make the file
     * publicly readable, and encode the file ID into a join token.
     *
     * Credentials are XOR-encrypted before upload so the Drive file is never
     * plaintext credentials even though it is publicly readable.
     *
     * Runs on a background thread.  [callback] is called on the main thread with
     * (success, statusMessage, tokenOrNull).
     */
    @JvmStatic
    fun uploadOrgConfig(
        context: Context,
        account: GoogleSignInAccount,
        callback: (Boolean, String, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val orgName = CaltopoClient.GetHomeOrgName().ifBlank {
                    throw IllegalStateException("Load ct_credentials with org_name before exporting org config.")
                }
                val rawBundle = CaltopoClient.BuildOrgConfigBundle(orgName)
                    ?: throw IllegalStateException("Failed to build org config bundle.")
                val securedBundle = encryptCredentialsInBundle(rawBundle)
                val fileId = GoogleDriveConfigSync.uploadOrgConfigFile(
                    appContext, account, securedBundle, orgName
                )
                val token = OrgConfigToken.encode(
                    OrgConfigToken.OrgConfig(
                        orgName     = orgName,
                        driveFileId = fileId,
                        isPublic    = true
                    )
                )
                storeToken(appContext, token)
                CaltopoClient.CTDebug(TAG, "uploadOrgConfig(): upload complete, org='$orgName'")
                Triple(true, "Org config uploaded to Drive.", token)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "uploadOrgConfig() failed.", e)
                Triple(false, e.message ?: "Upload failed.", null)
            }
            mainHandler.post { callback(result.first, result.second, result.third) }
        }
    }

    // ── Member: download and apply org config ──────────────────────────────────

    /**
     * Decode [token], download the org config bundle from Drive (no auth required
     * because the file is publicly readable), decrypt the credentials block, and
     * apply the bundle to the current app state.
     *
     * Runs on a background thread.  [callback] is called on the main thread with
     * (success, statusMessage).
     */
    @JvmStatic
    fun joinFromToken(
        context: Context,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        val trimmed = token.trim()
        val config = OrgConfigToken.decode(trimmed)
        if (config == null) {
            callback(false, "Invalid join token.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val encJson = GoogleDriveConfigSync.downloadOrgConfigPublic(config.driveFileId)
                val plainJson = decryptCredentialsInBundle(encJson)
                val success = CaltopoClient.ApplyOrgConfigBundle(plainJson)
                if (success) {
                    storeToken(appContext, trimmed)
                    CaltopoClient.CTDebug(TAG, "joinFromToken(): joined org='${config.orgName}'")
                    true to "Joined '${config.orgName}'. Org config applied."
                } else {
                    false to "Org config downloaded but could not be applied."
                }
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "joinFromToken() failed.", e)
                false to (e.message ?: "Failed to download org config.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }
}

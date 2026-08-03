package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.json.JSONObject
import org.ncssar.rid2caltopo.notam.NotamAuthManager
import java.util.concurrent.Executors

object FaaConfigManager {
    private const val TAG = "FaaConfigManager"
    const val TYPE_PLAINTEXT = "ct_faa_credentials"
    const val TYPE_ENCRYPTED = "ct_faa_credentials_enc"
    const val TYPE_REMOTE = "ct_faa_remote_config"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isFaaTokenText(text: String): Boolean =
        FaaConfigToken.isValidToken(text.trim())

    @JvmStatic
    fun tryHandleRawConfigText(context: Context?, text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false
        if (isFaaTokenText(trimmed)) {
            importToken(context, trimmed) { _, message ->
                CaltopoClient.ShowToast(message)
            }
            return true
        }
        return try {
            val json = JSONObject(trimmed)
            when (json.optString("type").trim().lowercase()) {
                TYPE_REMOTE -> {
                    readRemoteConfigObject(json)
                    true
                }
                TYPE_ENCRYPTED -> {
                    applyEncryptedPayload(json.toString(), remoteToken = "", validate = false)
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    @JvmStatic
    fun readConfigObject(json: JSONObject): Boolean {
        return when (json.optString("type").trim().lowercase()) {
            TYPE_PLAINTEXT -> {
                importPlaintextConfig(json)
                true
            }
            TYPE_REMOTE -> {
                readRemoteConfigObject(json)
                true
            }
            TYPE_ENCRYPTED -> {
                applyEncryptedPayload(json.toString(), remoteToken = "", validate = false)
                true
            }
            else -> false
        }
    }

    @JvmStatic
    fun importPlaintextConfig(json: JSONObject) {
        val normalized = normalizePlaintextConfig(json)
        val encrypted = encryptedPayloadForPlaintext(normalized)
        CaltopoClient.StoreFaaRemoteConfig(
            CaltopoClient.GetFaaRemoteToken(),
            normalized.optString("source_label", normalized.optString("label", "FAA NOTAM credentials")),
            encrypted,
            System.currentTimeMillis(),
            false,
            ""
        )
        applyPlaintextConfig(normalized)
        CaltopoClient.CTDebug(TAG, "importPlaintextConfig(): FAA credentials loaded into local cache.")
    }

    @JvmStatic
    fun importLegacyCredentialsFromJson(json: JSONObject) {
        val clientId = json.optString("notam_client_id")
        val clientSecret = json.optString("notam_client_secret")
        if (clientId.isBlank() || clientSecret.isBlank()) return
        val faaJson = JSONObject()
            .put("type", TYPE_PLAINTEXT)
            .put("file_version", "1.0")
            .put("source_label", json.optString("source_label", json.optString("org_name", "Legacy FAA NOTAM credentials")))
            .put("notam_api_base_url", json.optString("notam_api_base_url"))
            .put("notam_token_url", json.optString("notam_token_url"))
            .put("notam_client_id", clientId)
            .put("notam_client_secret", clientSecret)
            .put("notam_scope", json.optString("notam_scope"))
        importPlaintextConfig(faaJson)
    }

    @JvmStatic
    fun encryptedPayloadForPlaintext(json: JSONObject): String {
        val normalized = normalizePlaintextConfig(json)
        val enc = FaaConfigToken.encryptPayload(normalized.toString())
        return JSONObject()
            .put("type", TYPE_ENCRYPTED)
            .put("file_version", "1.0")
            .put("enc", enc)
            .toString()
    }

    @JvmStatic
    fun readRemoteConfigObject(json: JSONObject) {
        val token = json.optString("faa_token")
        val label = json.optString("label")
        val encrypted = json.optString("faa_payload_enc")
        val lastValidated = json.optLong("last_validated_epoch_ms", 0L)
        val stale = json.optBoolean("stale", encrypted.isBlank())
        CaltopoClient.StoreFaaRemoteConfig(
            token,
            label,
            encrypted,
            lastValidated,
            stale,
            json.optString("last_failure_reason")
        )
        if (encrypted.isNotBlank()) {
            applyEncryptedPayload(encrypted, remoteToken = token, validate = false)
        }
    }

    @JvmStatic
    fun buildRemoteConfigObject(): JSONObject? {
        val token = CaltopoClient.GetFaaRemoteToken()
        val payload = CaltopoClient.GetFaaPayloadEnc()
        if (token.isBlank() && payload.isBlank()) return null
        return JSONObject()
            .put("type", TYPE_REMOTE)
            .put("file_version", "1.0")
            .put("faa_token", token)
            .put("label", CaltopoClient.GetFaaConfigLabel())
            .put("faa_payload_enc", payload)
            .put("last_validated_epoch_ms", CaltopoClient.GetFaaLastValidatedEpochMs())
            .put("stale", CaltopoClient.GetFaaConfigStale())
            .put("last_failure_reason", CaltopoClient.GetFaaLastFailureReason())
    }

    internal fun applyCachedPayloadToState(state: ClientClassState) {
        val payload = state.faaPayloadEnc
        if (payload.isNullOrBlank()) return
        val json = decryptEncryptedPayload(payload) ?: return
        state.notamApiBaseUrl = json.optString("notam_api_base_url")
        state.notamTokenUrl = json.optString("notam_token_url")
        state.notamClientId = json.optString("notam_client_id")
        state.notamClientSecret = json.optString("notam_client_secret")
        state.notamScope = json.optString("notam_scope")
    }

    @JvmStatic
    fun refreshIfNeededOnStartup(context: Context) {
        val token = CaltopoClient.GetFaaRemoteToken()
        if (token.isBlank()) return
        val needsRefresh = CaltopoClient.GetFaaPayloadEnc().isBlank() || CaltopoClient.GetFaaConfigStale()
        if (!needsRefresh) return
        importToken(context.applicationContext, token) { success, message ->
            if (success) {
                CaltopoClient.CTDebug(TAG, "refreshIfNeededOnStartup(): $message")
            } else {
                CaltopoClient.CTWarn(TAG, "refreshIfNeededOnStartup(): $message")
            }
        }
    }

    @JvmStatic
    fun importToken(
        context: Context?,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        val trimmed = token.trim()
        val config = FaaConfigToken.decode(trimmed)
        if (config == null) {
            callback(false, "Invalid FAA config token.")
            return
        }
        CaltopoClient.StoreFaaRemoteConfig(trimmed, config.label, CaltopoClient.GetFaaPayloadEnc(), 0L, true, "Refresh pending.")
        if (context == null) {
            callback(true, "FAA config token saved; refresh will run on next app start.")
            return
        }
        executor.execute {
            val result = try {
                val encrypted = GoogleDriveConfigSync.downloadFaaConfigPublic(config.driveFileId)
                applyEncryptedPayload(encrypted, remoteToken = trimmed, validate = true)
                true to "FAA config imported."
            } catch (e: Exception) {
                CaltopoClient.MarkFaaConfigStale(e.message ?: "FAA config refresh failed.")
                false to (e.message ?: "FAA config refresh failed.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    @JvmStatic
    fun uploadFaaConfig(
        context: Context,
        account: GoogleSignInAccount,
        callback: (Boolean, String, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val plaintext = buildPlaintextFromCurrent()
                    ?: throw IllegalStateException("No legacy FAA credential configuration is available to publish.")
                val encrypted = encryptedPayloadForPlaintext(plaintext)
                val label = plaintext.optString("source_label", "RID2Caltopo FAA NOTAM credentials")
                val fileId = GoogleDriveConfigSync.uploadFaaConfigFile(appContext, account, encrypted, label)
                val token = FaaConfigToken.encode(FaaConfigToken.FaaConfig(fileId, label))
                CaltopoClient.StoreFaaRemoteConfig(token, label, encrypted, System.currentTimeMillis(), false, "")
                Triple(true, "FAA config uploaded to Drive.", token)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "uploadFaaConfig() failed.", e)
                Triple(false, e.message ?: "FAA config upload failed.", null)
            }
            mainHandler.post { callback(result.first, result.second, result.third) }
        }
    }

    @JvmStatic
    fun markAuthorizationFailure(reason: String) {
        if (CaltopoClient.GetFaaRemoteToken().isBlank() && CaltopoClient.GetFaaPayloadEnc().isBlank()) return
        CaltopoClient.MarkFaaConfigStale(reason)
    }

    private fun applyEncryptedPayload(encryptedJson: String, remoteToken: String, validate: Boolean) {
        val json = decryptEncryptedPayload(encryptedJson)
            ?: throw IllegalArgumentException("FAA config payload could not be decoded.")
        applyPlaintextConfig(json)
        if (validate) {
            validateCurrentCredentials()
        }
        val token = remoteToken.ifBlank { CaltopoClient.GetFaaRemoteToken() }
        CaltopoClient.StoreFaaRemoteConfig(
            token,
            json.optString("source_label", json.optString("label", CaltopoClient.GetFaaConfigLabel())),
            encryptedJson,
            if (validate) System.currentTimeMillis() else CaltopoClient.GetFaaLastValidatedEpochMs(),
            false,
            ""
        )
    }

    private fun decryptEncryptedPayload(encryptedJson: String): JSONObject? {
        return try {
            val wrapper = JSONObject(encryptedJson)
            if (wrapper.optString("type") != TYPE_ENCRYPTED) return null
            JSONObject(FaaConfigToken.decryptPayload(wrapper.getString("enc")))
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "decryptEncryptedPayload() failed.", e)
            null
        }
    }

    private fun normalizePlaintextConfig(json: JSONObject): JSONObject =
        JSONObject()
            .put("type", TYPE_PLAINTEXT)
            .put("file_version", json.optString("file_version", "1.0"))
            .put("updated", json.optString("updated"))
            .put("source_label", json.optString("source_label", json.optString("label", "RID2Caltopo FAA NOTAM credentials")))
            .put("notam_api_base_url", json.optString("notam_api_base_url"))
            .put("notam_token_url", json.optString("notam_token_url"))
            .put("notam_client_id", json.optString("notam_client_id"))
            .put("notam_client_secret", json.optString("notam_client_secret"))
            .put("notam_scope", json.optString("notam_scope"))

    private fun applyPlaintextConfig(json: JSONObject) {
        val clientId = json.optString("notam_client_id")
        val clientSecret = json.optString("notam_client_secret")
        require(clientId.isNotBlank() && clientSecret.isNotBlank()) {
            "FAA config is missing NOTAM client credentials."
        }
        json.optString("notam_api_base_url").takeIf { it.isNotBlank() }?.let(CaltopoClient::SetNotamApiBaseUrl)
        json.optString("notam_token_url").takeIf { it.isNotBlank() }?.let(CaltopoClient::SetNotamTokenUrl)
        CaltopoClient.SetNotamClientId(clientId)
        CaltopoClient.SetNotamClientSecret(clientSecret)
        CaltopoClient.SetNotamScope(json.optString("notam_scope"))
        CaltopoClient.SetNotamAutoRefresh(true)
    }

    private fun buildPlaintextFromCurrent(): JSONObject? {
        val clientId = CaltopoClient.GetNotamClientId()
        val clientSecret = CaltopoClient.GetNotamClientSecret()
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        return JSONObject()
            .put("type", TYPE_PLAINTEXT)
            .put("file_version", "1.0")
            .put("updated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
            .put("source_label", CaltopoClient.GetFaaConfigLabel().ifBlank { "RID2Caltopo FAA NOTAM credentials" })
            .put("notam_api_base_url", CaltopoClient.GetNotamApiBaseUrl())
            .put("notam_token_url", CaltopoClient.GetNotamTokenUrl())
            .put("notam_client_id", clientId)
            .put("notam_client_secret", clientSecret)
            .put("notam_scope", CaltopoClient.GetNotamScope())
    }

    private fun validateCurrentCredentials() {
        if (!NotamAuthManager.isConfigured()) {
            throw NotamAuthManager.NotamAuthException.Service(
                "FAA NOTAM proxy access requires an r2c-tracker organization QR code."
            )
        }
    }
}

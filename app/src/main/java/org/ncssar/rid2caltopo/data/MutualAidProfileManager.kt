package org.ncssar.rid2caltopo.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import org.ncssar.rid2caltopo.data.CaltopoMap
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

object MutualAidProfileManager {
    private const val TAG = "MutualAidProfileMgr"
    private const val PREFS = "mutual_aid_profile"
    private const val KEY_TOKEN = "join_token"

    private const val FORMAT = "rid2caltopo_mutual_aid_profile"
    private const val TYPE_PROFILE_ENC = "caltopo_profile_enc"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @JvmStatic
    fun storeToken(context: Context, token: String) {
        val config = MutualAidToken.decode(token) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    @JvmStatic
    fun getStoredToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, null)

    @JvmStatic
    fun clearStoredToken(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    @JvmStatic
    fun defaultExpiryAtNextMidnight(): Long {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @JvmStatic
    fun uploadMutualAidProfile(
        context: Context,
        account: GoogleSignInAccount,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long,
        callback: (Boolean, String, String?) -> Unit
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val sourceOrg = CaltopoClient.GetMutualAidSourceLabel().ifBlank {
                    throw IllegalStateException("Load ct_mutual_aid_credentials with source_label before exporting MA config.")
                }
                val profile = buildProfile(
                    sourceOrg = sourceOrg,
                    displayName = displayName,
                    incident = incident,
                    opPeriod = opPeriod,
                    targetMapId = targetMapId,
                    targetMapTitle = targetMapTitle,
                    expiresAtEpochMs = expiresAtEpochMs
                )
                val bundleJson = buildBundle(sourceOrg, profile)
                val fileId = GoogleDriveConfigSync.uploadMutualAidProfileFile(
                    appContext,
                    account,
                    bundleJson,
                    sanitizeBundleName(displayName.ifBlank { sourceOrg })
                )
                val token = MutualAidToken.encode(
                    MutualAidToken.MutualAidConfig(
                        sourceOrg = sourceOrg,
                        driveFileId = fileId,
                        isPublic = true
                    )
                )
                storeToken(appContext, token)
                Triple(true, "Mutual aid profile uploaded to Drive.", token)
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "uploadMutualAidProfile() failed.", e)
                Triple(false, e.message ?: "Mutual aid upload failed.", null)
            }
            mainHandler.post { callback(result.first, result.second, result.third) }
        }
    }

    @JvmStatic
    fun applyBundle(context: Context, json: String): Pair<Boolean, String> {
        return try {
            val bundle = JSONObject(json)
            if (bundle.optString("format") != FORMAT) {
                return false to "Unexpected mutual aid bundle format."
            }
            val encryptedProfile = bundle.optJSONObject("profile")
            if (encryptedProfile == null || encryptedProfile.optString("type") != TYPE_PROFILE_ENC) {
                return false to "Mutual aid bundle did not contain an encrypted profile."
            }
            val plaintext = MutualAidToken.decryptPayload(encryptedProfile.optString("enc"))
            val profileJson = JSONObject(plaintext)
            val profile = parseProfile(profileJson)
            if (profile.profileId.isEmpty()) {
                return false to "Mutual aid profile is missing a profile ID."
            }
            CaltopoClient.UpsertCaltopoProfile(profile, true, true)
            true to "Mutual aid access installed for ${profile.displayName}."
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "applyBundle() failed.", e)
            false to (e.message ?: "Failed to apply mutual aid profile.")
        }
    }

    @JvmStatic
    fun buildEncryptedActiveProfilePayload(): String? {
        val profile = CaltopoClient.GetActiveCaltopoProfile() ?: return null
        val profileJson = JSONObject()
            .put("profile_id", profile.profileId)
            .put("display_name", profile.displayName)
            .put("team_id", profile.credentials.teamId ?: "")
            .put("credential_id", profile.credentials.credentialId ?: "")
            .put("credential_secret", profile.credentials.credentialSecret ?: "")
            .put("domain_and_port", profile.domainAndPort)
            .put("track_folder", profile.trackFolder)
            .put("incident", profile.incident)
            .put("op_period", profile.opPeriod)
            .put("tracker_api_key", profile.trackerApiKey)
            .put("tracker_url_prefix", profile.trackerUrlPfx)
            .put("auto_connect", profile.autoConnect)
            .put("expires_at_epoch_ms", profile.expiresAtEpochMs)
            .put("quiet_remove_on_expiry", profile.quietRemoveOnExpiry)
            .put("source_label", profile.sourceLabel)
            .put("target_map_id", profile.targetMapId)
            .put("target_map_title", profile.targetMapTitle)
            .put("target_folder_hint", profile.targetFolderHint)
            .put("imported_at_epoch_ms", profile.importedAtEpochMs)
            .put("import_dedupe_key", profile.importDedupeKey)
        return MutualAidToken.encryptPayload(profileJson.toString())
    }

    @JvmStatic
    fun buildEncryptedProfilePayloadForCurrentIncident(
        displayName: String = "",
        incident: String = CaltopoClient.GetIncident(),
        opPeriod: String = CaltopoClient.GetOpPeriod(),
        targetMapId: String = CaltopoMap.GetMapId(),
        targetMapTitle: String = CaltopoMap.GetMapName(),
        expiresAtEpochMs: Long = defaultExpiryAtNextMidnight()
    ): String? {
        return try {
            val profile = buildProfile(
                sourceOrg = CaltopoClient.GetMutualAidSourceLabel(),
                displayName = displayName,
                incident = incident,
                opPeriod = opPeriod,
                targetMapId = targetMapId,
                targetMapTitle = targetMapTitle,
                expiresAtEpochMs = expiresAtEpochMs
            )
            val profileJson = JSONObject()
                .put("profile_id", profile.profileId)
                .put("display_name", profile.displayName)
                .put("team_id", profile.credentials.teamId ?: "")
                .put("credential_id", profile.credentials.credentialId ?: "")
                .put("credential_secret", profile.credentials.credentialSecret ?: "")
                .put("domain_and_port", profile.domainAndPort)
                .put("track_folder", profile.trackFolder)
                .put("incident", profile.incident)
                .put("op_period", profile.opPeriod)
                .put("tracker_api_key", profile.trackerApiKey)
                .put("tracker_url_prefix", profile.trackerUrlPfx)
                .put("auto_connect", profile.autoConnect)
                .put("expires_at_epoch_ms", profile.expiresAtEpochMs)
                .put("quiet_remove_on_expiry", profile.quietRemoveOnExpiry)
                .put("source_label", profile.sourceLabel)
                .put("target_map_id", profile.targetMapId)
                .put("target_map_title", profile.targetMapTitle)
                .put("target_folder_hint", profile.targetFolderHint)
                .put("imported_at_epoch_ms", profile.importedAtEpochMs)
                .put("import_dedupe_key", profile.importDedupeKey)
            MutualAidToken.encryptPayload(profileJson.toString())
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "buildEncryptedProfilePayloadForCurrentIncident() failed.", e)
            null
        }
    }

    @JvmStatic
    fun installEncryptedProfilePayload(encryptedPayload: String): Pair<Boolean, String> {
        return try {
            val profileJson = JSONObject(MutualAidToken.decryptPayload(encryptedPayload))
            val profile = parseProfile(profileJson)
            CaltopoClient.UpsertCaltopoProfile(profile, true, true)
            true to "Mutual aid profile installed."
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "installEncryptedProfilePayload() failed.", e)
            false to (e.message ?: "Failed to install mutual aid profile.")
        }
    }

    @JvmStatic
    fun joinFromToken(
        context: Context,
        token: String,
        callback: (Boolean, String) -> Unit
    ) {
        val trimmed = token.trim()
        val config = MutualAidToken.decode(trimmed)
        if (config == null) {
            callback(false, "Invalid mutual aid token.")
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val result = try {
                val encJson = GoogleDriveConfigSync.downloadOrgConfigPublic(config.driveFileId)
                val applied = applyBundle(appContext, encJson)
                if (applied.first) {
                    storeToken(appContext, trimmed)
                    CaltopoClient.CTDebug(TAG, "joinFromToken(): joined mutual aid source='${config.sourceOrg}'")
                }
                applied
            } catch (e: Exception) {
                CaltopoClient.CTWarn(TAG, "joinFromToken() failed.", e)
                false to (e.message ?: "Failed to download mutual aid profile.")
            }
            mainHandler.post { callback(result.first, result.second) }
        }
    }

    private fun parseProfile(json: JSONObject): CaltopoProfileRecord {
        return CaltopoProfileRecord(
            json.optString("profile_id"),
            json.optString("display_name").ifBlank { "Mutual Aid" },
            "MUTUAL_AID",
            CaltopoCredentials(
                json.optString("team_id"),
                json.optString("credential_id"),
                json.optString("credential_secret")
            ),
            json.optString("domain_and_port", "caltopo.com"),
            json.optString("track_folder", "Drone Tracks"),
            json.optString("incident", "Training"),
            json.optString("op_period", "1"),
            json.optString("tracker_api_key"),
            json.optString("tracker_url_prefix"),
            json.optBoolean("auto_connect", true),
            json.optLong("expires_at_epoch_ms", 0L),
            json.optBoolean("quiet_remove_on_expiry", true),
            json.optString("source_label"),
            json.optString("target_map_id"),
            json.optString("target_map_title"),
            json.optString("target_folder_hint"),
            json.optLong("imported_at_epoch_ms", System.currentTimeMillis()),
            json.optString("import_dedupe_key")
        )
    }

    private fun buildBundle(sourceOrg: String, profile: CaltopoProfileRecord): String {
        val bundle = JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("generated", CaltopoClient.TimeDatestampString(System.currentTimeMillis()))
            .put("source_org", sourceOrg)
        val profileJson = JSONObject()
            .put("profile_id", profile.profileId)
            .put("display_name", profile.displayName)
            .put("team_id", profile.credentials.teamId ?: "")
            .put("credential_id", profile.credentials.credentialId ?: "")
            .put("credential_secret", profile.credentials.credentialSecret ?: "")
            .put("domain_and_port", profile.domainAndPort)
            .put("track_folder", profile.trackFolder)
            .put("incident", profile.incident)
            .put("op_period", profile.opPeriod)
            .put("tracker_api_key", profile.trackerApiKey)
            .put("tracker_url_prefix", profile.trackerUrlPfx)
            .put("auto_connect", profile.autoConnect)
            .put("expires_at_epoch_ms", profile.expiresAtEpochMs)
            .put("quiet_remove_on_expiry", profile.quietRemoveOnExpiry)
            .put("source_label", profile.sourceLabel)
            .put("target_map_id", profile.targetMapId)
            .put("target_map_title", profile.targetMapTitle)
            .put("target_folder_hint", profile.targetFolderHint)
            .put("imported_at_epoch_ms", profile.importedAtEpochMs)
            .put("import_dedupe_key", profile.importDedupeKey)
        val encryptedProfile = JSONObject()
            .put("type", TYPE_PROFILE_ENC)
            .put("enc", MutualAidToken.encryptPayload(profileJson.toString()))
        bundle.put("profile", encryptedProfile)
        return bundle.toString()
    }

    private fun buildProfile(
        sourceOrg: String,
        displayName: String,
        incident: String,
        opPeriod: String,
        targetMapId: String,
        targetMapTitle: String,
        expiresAtEpochMs: Long
    ): CaltopoProfileRecord {
        val template = CaltopoClient.GetMutualAidTemplate()
        require(CaltopoClient.HasMutualAidTemplate()) { "Load ct_mutual_aid_credentials before exporting MA config." }
        val active = CaltopoClient.GetActiveCaltopoProfile()
        val normalizedIncident = incident.ifBlank { CaltopoClient.GetIncident() }
        val normalizedOpPeriod = opPeriod.ifBlank { CaltopoClient.GetOpPeriod() }
        val normalizedMapId = targetMapId.ifBlank { CaltopoMap.GetMapId() }
        val normalizedMapTitle = targetMapTitle.ifBlank { CaltopoMap.GetMapName() }
        val finalDisplayName = displayName.ifBlank {
            listOf(sourceOrg, normalizedIncident, "OP$normalizedOpPeriod").joinToString(" ").trim()
        }
        val importedAt = System.currentTimeMillis()
        val dedupeKey = listOf(
            sourceOrg.trim(),
            normalizedIncident.trim(),
            normalizedOpPeriod.trim()
        ).joinToString("|")
        return CaltopoProfileRecord(
            "mai-${sanitizeBundleName(sourceOrg)}-${sanitizeBundleName(normalizedIncident)}-op${sanitizeBundleName(normalizedOpPeriod)}",
            finalDisplayName,
            "MUTUAL_AID",
            CaltopoCredentials(template.teamId, template.credentialId, template.credentialSecret),
            template.domainAndPort.ifBlank { active?.domainAndPort ?: CaltopoClient.GetCaltopoDomainAndPort() },
            active?.trackFolder ?: CaltopoClient.GetTrackFolderName(),
            normalizedIncident,
            normalizedOpPeriod,
            active?.trackerApiKey ?: CaltopoClient.GetTrackerApiKey(),
            active?.trackerUrlPfx ?: CaltopoClient.GetTrackerUrlPfx(),
            true,
            expiresAtEpochMs,
            true,
            sourceOrg.ifBlank { template.sourceLabel },
            normalizedMapId,
            normalizedMapTitle,
            template.targetFolderHint.ifBlank { active?.targetFolderHint ?: "MAI" },
            importedAt,
            dedupeKey
        )
    }

    private fun sanitizeBundleName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
        return if (cleaned.isBlank()) "mutual_aid" else cleaned
    }
}

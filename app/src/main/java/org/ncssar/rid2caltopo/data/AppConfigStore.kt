package org.ncssar.rid2caltopo.data

import android.app.backup.BackupManager
import android.content.ContentResolver
import android.content.Context
import android.content.UriPermission
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.dataStore
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Hashtable
import java.util.Locale

private val Context.appConfigDataStore: DataStore<AppConfig> by dataStore(
    fileName = "app_config.pb",
    serializer = AppConfigSerializer
)

object AppConfigStore {
    const val SCHEMA_VERSION = 1
    private const val MAX_LOADED_CONFIG_FILES = 6
    private const val TAG = "AppConfigStore"

    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun initialize(context: Context) {
        appContext = context.applicationContext
        CaltopoClient.CTDebug(TAG, "initialize(): datastore path=${context.filesDir.parent}/files/datastore/app_config.pb")
    }

    @JvmStatic
    fun isLegacyImportComplete(context: Context): Boolean {
        initialize(context)
        return readConfigBlocking().legacyImportComplete
    }

    @JvmStatic
    fun restoreClientState(context: Context): Any {
        initialize(context)
        val config = readConfigBlocking()
        CaltopoClient.CTDebug(
            TAG,
            "restoreClientState(): legacyImportComplete=${config.legacyImportComplete}, ridMappings=${config.ridMappingsCount}, loadedConfigFiles=${config.loadedConfigFilesCount}, archiveUriBlank=${config.archiveLocation.treeUri.isBlank()}"
        )
        return toClientState(config)
    }

    @JvmStatic
    fun importLegacyStateIfNeeded(context: Context, legacyState: Any?) {
        initialize(context)
        val typedState = legacyState as? ClientClassState ?: return
        val current = readConfigBlocking()
        if (current.legacyImportComplete) return
        CaltopoClient.CTDebug(TAG, "importLegacyStateIfNeeded(): importing legacy ClientClassState into proto store.")
        writeConfigBlocking(mergeStateIntoConfig(current, typedState, false, true))
    }

    @JvmStatic
    fun persistState(context: Context, state: Any, archivePermissionMissing: Boolean) {
        initialize(context)
        val typedState = state as? ClientClassState ?: return
        val current = readConfigBlocking()
        val updated = mergeStateIntoConfig(current, typedState, archivePermissionMissing, current.legacyImportComplete)
        writeConfigBlocking(updated)
        requestBackup(context, "persistState")
    }

    @JvmStatic
    fun recordLoadedConfigFile(
        context: Context,
        type: String,
        editor: String,
        updated: String
    ): String {
        initialize(context)
        val current = readConfigBlocking()
        val nowMs = System.currentTimeMillis()
        val display = String.format(
            Locale.US,
            "type:%s, editor:%s, dated:%s loaded at %s",
            type,
            editor,
            updated,
            CaltopoClient.TimeDatestampString(nowMs)
        )
        val dedupeKey = String.format(Locale.US, "type:%s|editor:%s|dated:%s", type, editor, updated)
        val updatedConfig = current.toBuilder()
            .clearLoadedConfigFiles()
            .addAllLoadedConfigFiles(mergeLoadedConfigFiles(current.loadedConfigFilesList, dedupeKey, display, nowMs))
            .build()
        writeConfigBlocking(updatedConfig)
        requestBackup(context, "recordLoadedConfigFile")
        return loadedConfigFilesDisplay(updatedConfig)
    }

    @JvmStatic
    fun getArchiveSelectionHint(context: Context): String {
        initialize(context)
        return readConfigBlocking().archiveLocation.selectionHintUri
    }

    @JvmStatic
    fun getArchiveRequiresRegrant(context: Context): Boolean {
        initialize(context)
        return readConfigBlocking().archiveLocation.requiresRegrant
    }

    @JvmStatic
    fun verifyArchiveAccess(context: Context, archivePath: String): Boolean {
        initialize(context)
        if (archivePath.isBlank()) return false
        return try {
            val archiveUri = Uri.parse(archivePath)
            hasPersistedReadPermission(context.contentResolver, archiveUri) &&
                DocumentFile.fromTreeUri(context, archiveUri)?.let { it.exists() && it.isDirectory && it.canRead() } == true
        } catch (_: Exception) {
            false
        }
    }

    private fun readConfigBlocking(): AppConfig {
        val context = requireNotNull(appContext) { "AppConfigStore not initialized." }
        val dataStoreFile = context.filesDir.resolve("datastore/app_config.pb")
        CaltopoClient.CTDebug(TAG, "readConfigBlocking(): exists=${dataStoreFile.exists()} size=${if (dataStoreFile.exists()) dataStoreFile.length() else 0}")
        return runBlocking {
            context.appConfigDataStore.data
                .catch { ex ->
                    if (ex is IOException) emit(AppConfigSerializer.defaultValue) else throw ex
                }
                .first()
        }
    }

    private fun writeConfigBlocking(config: AppConfig) {
        val context = requireNotNull(appContext) { "AppConfigStore not initialized." }
        runBlocking {
            context.appConfigDataStore.updateData { config }
        }
        val dataStoreFile = context.filesDir.resolve("datastore/app_config.pb")
        CaltopoClient.CTDebug(
            TAG,
            "writeConfigBlocking(): exists=${dataStoreFile.exists()} size=${if (dataStoreFile.exists()) dataStoreFile.length() else 0}, ridMappings=${config.ridMappingsCount}, loadedConfigFiles=${config.loadedConfigFilesCount}"
        )
    }

    private fun toClientState(config: AppConfig): ClientClassState {
        val state = ClientClassState()
        state.minDistanceInFeet = if (config.minDistanceFeet > 0) config.minDistanceFeet else CaltopoClient.MIN_DISTANCE_IN_FEET
        state.archivePath = config.archiveLocation.treeUri
        state.caltopoTrackFolder = config.caltopoTrackFolder.ifBlank { "Drone Tracks" }
        state.caltopoDomainAndPort = config.caltopoDomainAndPort.ifBlank { "caltopo.com" }
        state.caltopoCredentials = CaltopoCredentials(
            config.caltopoCredentials.teamId,
            config.caltopoCredentials.credentialId,
            config.caltopoCredentials.credentialSecret
        )
        state.newTrackDelayInSeconds = if (config.newTrackDelaySeconds > 0) config.newTrackDelaySeconds else 30
        state.debugLevel = config.debugLevel
        state.maxIdleTimeInMinutes = if (config.maxIdleTimeMinutes >= 0) config.maxIdleTimeMinutes else 120
        state.incident = config.incident.ifBlank { "Training" }
        state.opPeriod = config.opPeriod.ifBlank { "1" }
        state.trackerApiKey = config.trackerApiKey
        state.trackerUrlPfx = config.trackerUrlPrefix
        state.coordinateDisplayFormat = config.coordinateDisplayFormat.ifBlank { "decimal" }
        state.captureVideoStreamsFlag = config.captureVideoStreams
        state.usePeersFlag = config.usePeers
        state.cachedDroneSpecTable = Hashtable<String, CtDroneSpec>(16)
        for (mapping in config.ridMappingsList) {
            val spec = CtDroneSpec(
                mapping.remoteId,
                mapping.mappedId,
                mapping.org,
                mapping.model,
                mapping.owner
            )
            state.cachedDroneSpecTable[spec.remoteId] = spec
        }
        state.configFilesLoaded = loadedConfigFilesDisplay(config)
        state.droneSpecTable = Hashtable<String, CtDroneSpec>(16)
        state.goLiveFlag = false
        return state
    }

    private fun mergeStateIntoConfig(
        current: AppConfig,
        state: ClientClassState,
        archivePermissionMissing: Boolean,
        legacyImportComplete: Boolean
    ): AppConfig {
        val builder = current.toBuilder()
            .setSchemaVersion(SCHEMA_VERSION)
            .setLegacyImportComplete(legacyImportComplete)
            .setMinDistanceFeet(state.minDistanceInFeet)
            .setNewTrackDelaySeconds(state.newTrackDelayInSeconds)
            .setMaxIdleTimeMinutes(state.maxIdleTimeInMinutes)
            .setDebugLevel(state.debugLevel)
            .setCoordinateDisplayFormat(state.coordinateDisplayFormat ?: "decimal")
            .setCaptureVideoStreams(state.captureVideoStreamsFlag)
            .setUsePeers(state.usePeersFlag)
            .setCaltopoTrackFolder(state.caltopoTrackFolder ?: "")
            .setCaltopoDomainAndPort(state.caltopoDomainAndPort ?: "")
            .setIncident(state.incident ?: "")
            .setOpPeriod(state.opPeriod ?: "")
            .setTrackerApiKey(state.trackerApiKey ?: "")
            .setTrackerUrlPrefix(state.trackerUrlPfx ?: "")
            .setCaltopoCredentials(
                AppConfig.CaltopoCredentialsConfig.newBuilder()
                    .setTeamId(state.caltopoCredentials?.teamId ?: "")
                    .setCredentialId(state.caltopoCredentials?.credentialId ?: "")
                    .setCredentialSecret(state.caltopoCredentials?.credentialSecret ?: "")
                    .build()
            )

        val currentArchive = current.archiveLocation
        val archivePath = state.archivePath ?: ""
        val hint = when {
            archivePath.isNotBlank() -> archivePath
            currentArchive.selectionHintUri.isNotBlank() -> currentArchive.selectionHintUri
            else -> currentArchive.treeUri
        }
        builder.setArchiveLocation(
            AppConfig.ArchiveLocation.newBuilder()
                .setTreeUri(archivePath)
                .setSelectionHintUri(hint)
                .setRequiresRegrant(archivePermissionMissing || (archivePath.isBlank() && currentArchive.requiresRegrant))
                .build()
        )

        builder.clearRidMappings()
        state.cachedDroneSpecTable.values
            .sortedBy { it.remoteId }
            .forEach { spec ->
                builder.addRidMappings(
                    AppConfig.RidMapping.newBuilder()
                        .setRemoteId(spec.remoteId ?: "")
                        .setMappedId(spec.mappedId ?: "")
                        .setOrg(spec.org ?: "")
                        .setModel(spec.model ?: "")
                        .setOwner(spec.owner ?: "")
                        .build()
                )
            }

        val importedRecords = if (current.loadedConfigFilesCount == 0) {
            parseLegacyLoadedConfigFiles(state.configFilesLoaded)
        } else {
            current.loadedConfigFilesList
        }
        builder.clearLoadedConfigFiles()
        builder.addAllLoadedConfigFiles(importedRecords.take(MAX_LOADED_CONFIG_FILES))
        return builder.build()
    }

    private fun mergeLoadedConfigFiles(
        current: List<AppConfig.LoadedConfigFileRecord>,
        dedupeKey: String,
        display: String,
        loadedAtMs: Long
    ): List<AppConfig.LoadedConfigFileRecord> {
        val newRecord = AppConfig.LoadedConfigFileRecord.newBuilder()
            .setDedupeKey(dedupeKey)
            .setDisplayText(display)
            .setLoadedAtEpochMs(loadedAtMs)
            .build()
        val tail = if (current.firstOrNull()?.dedupeKey == dedupeKey) current.drop(1) else current
        return buildList {
            add(newRecord)
            addAll(tail.take(MAX_LOADED_CONFIG_FILES - 1))
        }
    }

    private fun parseLegacyLoadedConfigFiles(raw: String?): List<AppConfig.LoadedConfigFileRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeLast(MAX_LOADED_CONFIG_FILES)
            .asReversed()
            .mapIndexed { index, line ->
                AppConfig.LoadedConfigFileRecord.newBuilder()
                    .setDedupeKey(line)
                    .setDisplayText(line)
                    .setLoadedAtEpochMs(index.toLong())
                    .build()
            }
    }

    private fun loadedConfigFilesDisplay(config: AppConfig): String = config.loadedConfigFilesList
        .joinToString(separator = "\n") { it.displayText }

    private fun hasPersistedReadPermission(resolver: ContentResolver, uri: Uri): Boolean {
        val permissions: List<UriPermission> = resolver.persistedUriPermissions
        return permissions.any { permission -> permission.uri == uri && permission.isReadPermission }
    }

    private fun requestBackup(context: Context, reason: String) {
        try {
            BackupManager(context).dataChanged()
            CaltopoClient.CTDebug(TAG, "requestBackup(): signaled BackupManager after $reason")
        } catch (e: Exception) {
            CaltopoClient.CTWarn(TAG, "requestBackup(): unable to signal BackupManager after $reason", e)
        }
    }
}
